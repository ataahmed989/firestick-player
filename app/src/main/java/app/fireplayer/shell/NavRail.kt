package app.fireplayer.shell

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.fireplayer.R
import app.fireplayer.ui.Glass
import app.fireplayer.ui.GlassBackdrop
import app.fireplayer.ui.GlassBakes
import app.fireplayer.ui.GlassDrawable
import app.fireplayer.ui.GlassSource
import app.fireplayer.ui.GlassStyle
import app.fireplayer.ui.ListMath
import app.fireplayer.ui.PageGlass
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The left tab rail: the one FirePlayer brand mark, then the tabs, Settings' gear at the bottom.
 *
 * On Home, Movies, TV Shows, My List, Search and Settings it is a narrow floating window: inset from the screen's left,
 * top and bottom, with rounded corners, a fine outline and a soft shadow. On Live TV it is a strip attached to the
 * screen's left edge, full height and square, with a hairline down its right edge. It sits collapsed (icons only)
 * while the page has focus and widens with labels, and a larger mark and name, when focus comes into it: the same
 * window or strip, drawn over the page, so the page (and live video) never moves or changes size. Only tabs that work
 * are shown; the others are added as they're built (no placeholders).
 *
 * Both changes are motions of one shape ([RailShape]): widening and collapsing ([setExpanded]) moves the window's
 * right edge, and Live TV entered or left ([setAttached]) moves its bounds between the strip and the window while its
 * corners round and its outline and shadow ease in. They share one time and curve, either can turn round part-way from
 * where it is, and they can run together. The view is laid out once, as wide as the widened rail and the screen's full
 * height, with the tabs where they lie widened; the window is drawn inside it ([Surface]) and the labels, the mark and
 * the chosen tab's white are moved and cut to it by drawing alone. A frame of the motion redraws this view and
 * nothing else: nothing is laid out, measured, made or copied on the way.
 *
 * Classic: the classic dark fill, solid. Glass: the shared dark glass ([GlassStyle]); narrow, over the still wallpaper,
 * it is the tint alone, and widened over the page it shows the page behind it blurred ([PageGlass]), copied once
 * before the widening's first frame and let go when the collapse has ended.
 */
class NavRail(context: Context, private val t: Tokens) : LinearLayout(context) {

    enum class Tab(val label: String, val icon: Int) {
        SEARCH("Search", R.drawable.ic_search_rail),
        HOME("Home", R.drawable.ic_home_rail),
        MOVIES("Movies", R.drawable.ic_movies),
        SHOWS("TV Shows", R.drawable.ic_tv_shows),
        MY_LIST("My List", R.drawable.ic_my_list),
        LIVE("Live TV", R.drawable.ic_live),
        /** The two streaming-service pages visited last, newest first ([setServices]); not shown until one has been. */
        SERVICE_1("", 0),
        SERVICE_2("", 0),
        SETTINGS("Settings", R.drawable.ic_settings);

        val isService: Boolean get() = this == SERVICE_1 || this == SERVICE_2
    }

    var onTab: ((Tab) -> Unit)? = null
    /** Right from the rail: into the page. */
    var onEnterPage: (() -> Unit)? = null

    private val brandMark: ImageView
    private val items = LinkedHashMap<Tab, TabRow>()
    /** The tabs from the top down, for drawing the chosen one's white. */
    private val rows = ArrayList<TabRow>()
    /** The brand's name and the tabs' labels: seen only while the rail is wider than its icons. */
    private val words = ArrayList<TextView>()
    var selected: Tab = Tab.LIVE
        private set
    var expanded = false
        private set
    /** Attached to the screen's edge as a full-height strip (Live TV); otherwise the floating window. */
    var attached = true
        private set

    /** The rail's width collapsed: what the pages beside it leave free ([WIDTH_DP]). */
    val collapsedWidth = t.dp(WIDTH_DP)
    /**
     * The view's own width, always: the widened rail's, and room for its window's shadow beside it. So everything the
     * rail draws lies inside its view, and a frame of its motion redraws that part of the screen and no more.
     * Collapsed, the part right of the icons is empty and lets the page show (and a touch through).
     */
    val viewWidth = t.dp(EXPANDED_DP) + GlassBakes.pad(Glass.Kind.POPUP, false, t.density, calm = true)
    private val tabWidth = collapsedWidth - 2 * t.dp(SIDE_DP)

    // ---- The two motions (0 to 1 each), stepped once a frame while either runs --------------------------------------

    /** 0 icons only, 1 widened with labels. */
    private val widen = RailMotion(0f)
    /** 0 the floating window, 1 the strip attached to the screen's edge. */
    private val attach = RailMotion(1f)
    /** How much of its white each tab shows (0 none, 1 chosen): faded over [Tokens.FADE_MS] as focus moves. */
    private var glow = FloatArray(0)
    private var glowing = false
    private var ticking = false
    private var firstFrame = false
    private var lastFrameNanos = 0L
    private val frame = Choreographer.FrameCallback { nanos -> onFrame(nanos) }

    // ---- Where everything is drawn just now, px ([place]) -----------------------------------------------------------

    private var boxLeft = 0f
    private var boxTop = 0f
    private var boxRight = 0f
    private var boxBottom = 0f
    private var boxRadius = 0f
    /** How far right the tabs are drawn of where they are laid out. */
    private var shift = 0f
    private var tabsLeft = 0f
    private var tabsRight = 0f
    /** The window's outline, top highlight, sheen and shadow (1 the floating window, 0 the strip). */
    private var outline = 0f
    /** The hairline down the strip's right edge. */
    private var ruleShare = 1f
    /** The blurred page under the tint. */
    private var filmShare = 0f
    private var wordsAlpha = Float.NaN
    private var wordsShown = true

    /** The mark is laid out at its widened size; collapsed it is drawn smaller, sharp at both sizes once at rest. */
    private val markBox = t.dp(RailShape.MARK_WIDE_DP)
    private val markWide = Matrix()
    private val markSmall = Matrix()
    private var markIsSmall = false

    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.focusFill }
    private val whiteAlpha = Color.alpha(t.focusFill)
    private val whiteRadius = t.dpf(RailShape.TAB_RADIUS_DP)
    private val tabBox = RectF()
    private val surface = Surface()

    init {
        orientation = VERTICAL
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        // Laid out once, as the widened strip's tabs lie: SIDE_DP from the widened rail's edges. Every other state is
        // drawn.
        setPadding(t.dp(SIDE_DP), t.dp(22), viewWidth - t.dp(EXPANDED_DP) + t.dp(SIDE_DP), t.dp(22))

        val brand = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            // The mark stays centred over the tabs' 22 dp icons (11 dp into each tab).
            setPadding(t.dp(22) - markBox / 2, 0, 0, 0)
        }
        brandMark = ImageView(context).apply {
            setImageResource(R.drawable.ic_brand)
            scaleType = ImageView.ScaleType.MATRIX
        }
        val markW = max(brandMark.drawable?.intrinsicWidth ?: 0, 1).toFloat()
        val markH = max(brandMark.drawable?.intrinsicHeight ?: 0, 1).toFloat()
        val small = t.dpf(RailShape.MARK_DP.toFloat())
        markWide.setScale(markBox / markW, markBox / markH)
        markSmall.setScale(small / markW, small / markH)
        markSmall.postTranslate((markBox - small) / 2f, (markBox - small) / 2f)
        brandMark.imageMatrix = markWide
        brand.addView(brandMark, LayoutParams(markBox, markBox))
        val name = t.label(context, RailShape.NAME_SP, t.bold).apply { text = "FirePlayer" }
        words += name
        brand.addView(name, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = t.dp(12) })
        addView(brand, LayoutParams(LayoutParams.MATCH_PARENT, t.dp(44)).apply { bottomMargin = t.dp(22) })

        for (tab in Tab.values()) {
            if (tab == Tab.SETTINGS) addView(View(context), LayoutParams(1, 0, 1f)) // Settings at the bottom
            val row = item(tab)
            if (tab.isService) row.visibility = GONE
            items[tab] = row
            rows += row
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, t.dp(44)).apply { bottomMargin = t.dp(6) })
        }
        glow = FloatArray(rows.size)
        background = surface
        applyLook()
        place()
        setSelected(Tab.LIVE)
    }

    private fun item(tab: Tab): TabRow {
        val colors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_selected), intArrayOf()),
            intArrayOf(t.accent, t.accent, t.text),
        )
        val label = t.label(context, 16f, t.semibold).apply {
            text = tab.label
            setTextColor(colors)
            isDuplicateParentStateEnabled = true
        }
        words += label
        return TabRow(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            contentDescription = tab.label
            setPadding(t.dp(11), 0, t.dp(12), 0)
            icon = ImageView(context).apply {
                setImageResource(tab.icon)
                // A service's icon has its own colours ([TabRow.showTint]).
                if (!tab.isService) imageTintList = colors
                isDuplicateParentStateEnabled = true
            }
            this.label = label
            addView(icon, LayoutParams(t.dp(22), t.dp(22)))
            addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = t.dp(14) })
            setOnClickListener { open(tab) }
            // The rail moves its own focus: the expanded rail is drawn over the page, so Android's nearest-view
            // search found the page (the guide) below a tab before the next tab, which sits further down.
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener isRailKey(keyCode)
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { open(tab); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { step(tab, down = true, held = event.repeatCount > 0); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { step(tab, down = false, held = event.repeatCount > 0); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> true // nothing to the left of the rail
                    else -> false
                }
            }
        }
    }

    /**
     * A tab. Its view is as wide as a widened tab at all times, so nothing is laid out when the rail widens. Seen from
     * the page (Android's search for what is to the Left) it is where its icon is, the collapsed rail's tab, as it
     * always was; its white is drawn by the rail, as wide as the rail is drawn just then.
     */
    private inner class TabRow(context: Context) : LinearLayout(context) {
        lateinit var icon: ImageView
        lateinit var label: TextView
        /** A service's tab: its icon's colour at rest (null: the icon's own colours); dark on the white while focused. */
        var service: ServiceTab? = null

        /** A service's icon in its resting colour, or dark on the tab's white the moment the tab holds focus. */
        fun showTint() {
            val s = service ?: return
            icon.imageTintList = if (isFocused) serviceFocused else s.rest?.let { ColorStateList.valueOf(it) }
        }

        init {
            // No background of its own any more: without this Android would draw its own highlight on the chosen tab.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) defaultFocusHighlightEnabled = false
        }

        override fun getFocusedRect(r: Rect) = r.set(0, 0, tabWidth, height)

        override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            showTint()
            wake()
        }
    }

    private fun isRailKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
        keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_LEFT

    /** OK or Right on a tab: show its page and go into it. */
    private fun open(tab: Tab) {
        if (tab != selected) onTab?.invoke(tab)
        onEnterPage?.invoke()
    }

    /**
     * Up/Down: the previous or next tab. Down from the last tab goes round to the first and Up from the first to the
     * last; [held], it stops there instead (holding the D-pad doesn't go round and round).
     */
    private fun step(from: Tab, down: Boolean, held: Boolean) {
        val tabs = items.filter { it.value.visibility == VISIBLE }.keys.toList()
        val to = ListMath.step(tabs.indexOf(from), tabs.size, down, held) { true }
        if (to >= 0) items[tabs[to]]?.requestFocus()
    }

    /**
     * Seen from the page, the rail is one target, the selected tab: Left from the page (or the guide's column) comes
     * back to the tab you came from, not to whichever tab happens to be nearest on screen.
     */
    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        if (hasFocus()) return super.addFocusables(views, direction, focusableMode)
        items[selected]?.let { if (it.isShown && it.isFocusable) views.add(it) }
    }

    /** A touch right of where the rail is drawn just now is the page's (the view is always the widened rail's width). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = ev.x <= boxRight && super.dispatchTouchEvent(ev)

    fun setSelected(tab: Tab) {
        selected = tab
        for ((k, v) in items) v.isSelected = k == tab
    }

    /**
     * The streaming-service pages visited last, newest first: a tab each under Live TV, with the rail's own focus,
     * labels and motion. Tabs beyond those given stay as they are (the list only grows).
     */
    fun setServices(services: List<ServiceTab>) {
        for ((i, tab) in SERVICE_TABS.withIndex()) {
            val row = items[tab] ?: continue
            val s = services.getOrNull(i) ?: continue
            if (row.service == s) continue
            row.service = s
            row.contentDescription = s.name
            row.label.text = s.name
            row.icon.setImageResource(s.icon)
            row.showTint()
            row.visibility = VISIBLE
        }
    }

    fun focusSelected() {
        items[selected]?.requestFocus()
    }

    /**
     * Wide with labels while focus is in the rail; icons only otherwise. The same window (or strip), drawn over the
     * page: its right edge moves out or back in one motion, the labels and the larger mark and name easing in and out
     * with it. Widened, its glass shows the page behind it, blurred: copied once before the first frame of the motion
     * (and again only if the page changes while the rail stays open); narrow, there is only the still wallpaper behind
     * it and nothing is copied.
     */
    fun setExpanded(on: Boolean) {
        if (expanded == on) return
        expanded = on
        surface.widening(on)
        move(widen, if (on) 1f else 0f, animate = true)
    }

    /**
     * The strip attached to the screen's edge ([on], Live TV) or the floating window (every other tab). With [animate]
     * it becomes the other in one motion: its bounds move, its corners round or square and its outline and shadow ease
     * in or out, in the time and on the curve widening has ([RailShape.MOVE_MS]).
     */
    fun setAttached(on: Boolean, animate: Boolean = false) {
        if (attached == on) return
        attached = on
        move(attach, if (on) 1f else 0f, animate)
    }

    private fun move(motion: RailMotion, to: Float, animate: Boolean) {
        if (animate) {
            motion.aim(to)
            wake()
        } else {
            motion.snap(to)
            place()
            rested()
        }
    }

    // ---- Frames -----------------------------------------------------------------------------------------------------

    /**
     * Something is to move (a motion was aimed, focus moved between tabs): frames are stepped until all of it has
     * arrived. The first step is taken in the next frame, the one the change that started it is drawn in, so the rail
     * moves in step with the page changing. Out of sight nothing moves: it is all simply there (as it is with the
     * TV's animations switched off).
     */
    private fun wake() {
        if (ticking) return
        if (!isLaidOut || !isShown || !motionOn()) return settle()
        ticking = true
        firstFrame = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun motionOn() = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

    private fun onFrame(nanos: Long) {
        // Time is taken a frame at a time and never more than two frames at once ([RailShape.stepMs]): after a slow
        // frame (a page appearing, the page's picture being copied) the motion carries on from where it was.
        val dt = if (firstFrame) RailShape.FRAME_MS else RailShape.stepMs((nanos - lastFrameNanos) / 1_000_000f)
        firstFrame = false
        lastFrameNanos = nanos
        if (!isShown) {
            ticking = false
            return settle()
        }
        widen.step(dt)
        attach.step(dt)
        glowing = stepGlow(dt)
        place()
        rested()
        if (widen.running || attach.running || glowing) Choreographer.getInstance().postFrameCallback(frame) else ticking = false
    }

    /** The tabs' white a step nearer where focus is; whether any is still on its way. */
    private fun stepGlow(dt: Float): Boolean {
        val step = dt / Tokens.FADE_MS
        var moving = false
        for (i in rows.indices) {
            val to = if (rows[i].isFocused) 1f else 0f
            val now = glow[i]
            if (now == to) continue
            val next = if (to > now) min(now + step, to) else max(now - step, to)
            glow[i] = next
            if (next != to) moving = true
        }
        return moving
    }

    /** The activity is pausing: whatever is moving is where it was heading, at once. */
    fun finishMotion() {
        if (ticking) settle()
    }

    /** Everything where it was heading, at once (out of sight, or leaving the window): nothing moves unseen. */
    private fun settle() {
        if (ticking) {
            Choreographer.getInstance().removeFrameCallback(frame)
            ticking = false
        }
        widen.snap(widen.target)
        attach.snap(attach.target)
        for (i in rows.indices) glow[i] = if (rows[i].isFocused) 1f else 0f
        glowing = false
        place()
        rested()
    }

    /** What waits for the widening or collapsing to have ended (the page's picture taken afresh, or let go). */
    private fun rested() {
        if (!widen.running) surface.widened(expanded)
    }

    /**
     * Where everything is drawn for the two motions' values now ([RailShape]): the window's bounds and corners, the
     * tabs' place and their white's ends, how much of the labels shows and the mark's size. Only properties of views
     * already laid out are set (alpha, translation, scale), and this view is drawn again.
     */
    private fun place() {
        val wide = widen.value
        val edge = attach.value
        val d = t.density
        boxLeft = RailShape.insetXDp(edge) * d
        boxTop = RailShape.insetYDp(edge) * d
        boxRight = RailShape.rightDp(wide, edge) * d
        boxBottom = height - boxTop
        boxRadius = RailShape.radiusDp(edge) * d
        shift = RailShape.shiftDp(wide, edge) * d
        tabsLeft = RailShape.tabsLeftDp(wide, edge) * d
        tabsRight = RailShape.tabsRightDp(wide, edge) * d
        outline = RailShape.outline(edge)
        ruleShare = edge
        filmShare = RailShape.filmAlpha(wide)

        val alpha = RailShape.labelAlpha(wide)
        if (alpha != wordsAlpha) {
            wordsAlpha = alpha
            val slide = RailShape.labelSlideDp(wide) * d
            for (i in words.indices) {
                val word = words[i]
                word.alpha = alpha
                word.translationX = slide
            }
            // Not there at all while nothing of them shows (never GONE: that would lay the rail out again).
            val show = alpha > 0f
            if (show != wordsShown) {
                wordsShown = show
                for (i in words.indices) words[i].visibility = if (show) VISIBLE else INVISIBLE
            }
        }

        // In motion the mark is its widened drawing, scaled; at rest collapsed it is drawn afresh at that size.
        val small = wide == 0f && !widen.running
        if (small != markIsSmall) {
            markIsSmall = small
            brandMark.imageMatrix = if (small) markSmall else markWide
        }
        val scale = if (small) 1f else RailShape.markScale(wide)
        brandMark.scaleX = scale
        brandMark.scaleY = scale
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        surface.sized(h)
        place()
    }

    override fun dispatchDraw(canvas: Canvas) {
        // The chosen tab's white, from where the tabs begin to where they end just now: it grows with the rail.
        for (i in rows.indices) {
            val share = glow[i]
            if (share <= 0f) continue
            val row = rows[i]
            white.alpha = (whiteAlpha * share).toInt()
            tabBox.set(tabsLeft, row.top.toFloat(), tabsRight, row.bottom.toFloat())
            canvas.drawRoundRect(tabBox, whiteRadius, whiteRadius, white)
        }
        // The mark, the icons and the labels: moved to the tabs' place, and cut at the window's edges, so a label
        // comes into view as the edge passes it.
        val count = canvas.save()
        canvas.clipRect(boxLeft, boxTop, boxRight, boxBottom)
        canvas.translate(shift, 0f)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(count)
    }

    /**
     * The rail's surfaces in the look now on. Classic: the classic dark fill, solid, with the classic hairline (round
     * the window, or down the strip's edge). Glass: the shared dark glass, which never lets the page's words show
     * through the labels: widened over the page it shows the page blurred under its tint.
     */
    fun applyLook() {
        surface.look(Glass.on)
        invalidate()
    }

    /** As the selectors' fades end when a look is applied: the chosen tab's white is simply there. */
    override fun jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState()
        if (!glowing) return
        for (i in rows.indices) glow[i] = if (rows[i].isFocused) 1f else 0f
        invalidate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (ticking && !isShown) settle()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (ticking && visibility != VISIBLE) settle()
    }

    override fun onDetachedFromWindow() {
        if (ticking) settle()
        super.onDetachedFromWindow()
    }

    override fun generateDefaultLayoutParams(): LayoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    /**
     * The rail's background: one shape, drawn each time at the bounds and corner radius it has just then ([place]),
     * with paints made once. Glass: the soft shadow outside (a small picture of it made once, laid round the shape in
     * pieces), the page behind blurred while the rail is wide, the tint, a faint light from above, the hairline and
     * the highlight along the top edge, all from [GlassStyle]. Classic: the solid fill and its hairline. Either: the
     * strip's hairline down its right edge. Every round shape is drawn with a paint, so its corners are smooth at
     * every size (nothing is cut out of the canvas).
     */
    private inner class Surface : Drawable(), Drawable.Callback {
        private var glass = false
        private val box = RectF()
        private val part = RectF()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private var lineAlpha = 0
        private val rule = Paint(Paint.ANTI_ALIAS_FLAG)
        private var ruleAlpha = 0
        private val ruleWidth = t.dpf(1f)

        // Glass only.
        private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
        private var sheenHeight = 0f
        private val topLight = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val topLightHeight = t.dpf(RailShape.RADIUS_DP)
        private val shadePaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var shade: Bitmap? = null
        private var shadePad = 0
        private var shadeCorner = 0
        private val src = Rect()
        private val dst = RectF()

        // The blurred page behind the widened rail. The page's picture is PageGlass's, which works for glass it knows
        // ([watcher], never drawn): this draws its picture at the rail's shape of the moment.
        private var source: GlassSource? = null
        private var watcher: GlassDrawable? = null
        /** The picture is asked for at each drawing (widened, or widening from rest); otherwise the one held is drawn. */
        private var live = false
        /** Widening again before a collapse had ended: the picture held is kept, and taken afresh once at rest. */
        private var liveAtRest = false
        private val filmPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val noFilm = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GlassStyle.WINDOW_SOLID }
        private var film0: Bitmap? = null
        private var shader0: BitmapShader? = null
        private var film1: Bitmap? = null
        private var shader1: BitmapShader? = null
        private val filmMatrix = Matrix()
        private var placedX = Int.MIN_VALUE
        private var placedY = Int.MIN_VALUE
        private var placedScaleX = 0f
        private var placedScaleY = 0f
        private var placedOriginX = 0
        private var placedOriginY = 0
        private val location = IntArray(2)

        fun look(glass: Boolean) {
            this.glass = glass
            watcher?.let {
                it.backdropOn = false
                it.callback = null
            }
            watcher = null
            source = null
            live = false
            liveAtRest = false
            forgetFilm()
            if (glass) {
                fill.color = GlassStyle.WINDOW_TINT
                line.color = GlassStyle.HAIRLINE
                line.strokeWidth = GlassStyle.hairlinePx(t.density)
                rule.color = RailShape.GLASS_RULE
                topLight.strokeWidth = t.dpf(GlassStyle.HIGHLIGHT_DP)
                // Full along the top edge, fading round the corners to nothing down the sides.
                topLight.shader = LinearGradient(
                    0f, 0f, 0f, topLightHeight,
                    GlassStyle.HIGHLIGHT, GlassStyle.HIGHLIGHT and 0x00FFFFFF, Shader.TileMode.CLAMP,
                )
                bakeShade()
                source = pageGlass()
                // Before it's the rail's: narrow, nothing is copied for it.
                watcher = Glass.overlayDrawable(context, Glass.Kind.POPUP).also {
                    it.backdropOn = false
                    it.callback = this
                }
                if (expanded) goLive()
            } else {
                fill.color = RailShape.CLASSIC_FILL
                line.color = RailShape.CLASSIC_RULE
                line.strokeWidth = t.dpf(1f)
                rule.color = RailShape.CLASSIC_RULE
                topLight.shader = null
                shade = null
            }
            lineAlpha = line.alpha
            ruleAlpha = rule.alpha
            sized(height)
        }

        /** The view is [h] px high: the sheen's reach down the floating window. */
        fun sized(h: Int) {
            sheen.shader = null
            if (!glass || h <= 0) return
            sheenHeight = (h - 2 * t.dpf(RailShape.FLOAT_END_DP.toFloat())) * GlassStyle.SHEEN_SHARE
            if (sheenHeight <= 0f) return
            sheen.shader = LinearGradient(0f, 0f, 0f, sheenHeight, GlassStyle.SHEEN, GlassStyle.SHEEN and 0x00FFFFFF, Shader.TileMode.CLAMP)
        }

        /**
         * The window's soft shadow, once: the shared small window's own ([GlassBakes], so it is the shadow every such
         * window has), of a shape just large enough that its sides are even, with the window itself taken out. Drawn
         * round the rail's shape in pieces ([drawShade]), it fits any size the rail has.
         */
        private fun bakeShade() {
            val d = t.density
            val pad = GlassBakes.pad(Glass.Kind.POPUP, false, d, calm = true)
            val r = t.dpf(RailShape.RADIUS_DP)
            val corner = ceil(r).toInt() + pad
            val size = 2 * corner + 2
            val baked = GlassBakes.glass(Glass.Kind.POPUP, false, size, size, d, GlassBakes.CALM)
            val own = Bitmap.createBitmap(baked.width, baked.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(own)
            c.drawBitmap(baked, 0f, 0f, null)
            val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
            c.drawRoundRect(RectF(pad.toFloat(), pad.toFloat(), (pad + size).toFloat(), (pad + size).toFloat()), r, r, cut)
            shade = own
            shadePad = pad
            shadeCorner = corner
        }

        /** The activity's picture of the page behind its windows, or none (the glass is then dark where it's wide). */
        private fun pageGlass(): GlassSource? {
            var c: Context? = context
            while (c is ContextWrapper) {
                if (c is Activity) return (c as? GlassSource.Host)?.pageGlass
                c = c.baseContext
            }
            return null
        }

        /**
         * The rail starts to widen ([on]) or to collapse. Widening from rest, the page is copied before the frame now
         * coming is drawn (the motion's first), and not again until the motion has ended. Collapsing, nothing more is
         * copied for the rail at once; the picture it has is drawn until the motion ends ([widened]). Widening again
         * before that, it keeps that picture rather than copy in the middle of a motion.
         */
        fun widening(on: Boolean) {
            if (!glass) return
            if (on) {
                if (shader0 != null && !live) liveAtRest = true else goLive()
            } else {
                liveAtRest = false
                live = false
                watcher?.backdropOn = false
            }
        }

        /** The widening or collapsing has ended (or none is running). */
        fun widened(expanded: Boolean) {
            if (!glass) return
            if (expanded) {
                if (liveAtRest) {
                    liveAtRest = false
                    goLive()
                }
            } else if (shader0 != null || shader1 != null) {
                forgetFilm()
            }
        }

        private fun goLive() {
            val w = watcher ?: return
            live = true
            (source as? PageGlass)?.holdUntil(SystemClock.uptimeMillis() + RailShape.FILM_HOLD_MS)
            w.backdropOn = true
        }

        private fun forgetFilm() {
            filmPaint.shader = null
            film0 = null
            shader0 = null
            film1 = null
            shader1 = null
        }

        override fun draw(canvas: Canvas) {
            if (boxRight <= boxLeft || boxBottom <= boxTop) return
            box.set(boxLeft, boxTop, boxRight, boxBottom)
            val r = boxRadius
            val edge = outline
            if (glass) {
                if (edge > 0f) drawShade(canvas, edge)
                if (filmShare > 0f) drawFilm(canvas, r, filmShare)
            }
            shape(canvas, box, r, fill)
            if (edge > 0f) {
                if (glass) drawSheen(canvas, r, edge)
                val half = line.strokeWidth / 2f
                part.set(box.left + half, box.top + half, box.right - half, box.bottom - half)
                line.alpha = (lineAlpha * edge).toInt()
                shape(canvas, part, max(r - half, 0f), line)
                if (glass) drawTopLight(canvas, r, edge)
            }
            if (ruleShare > 0f) {
                // Down the straight part of the right edge: the whole of it once the corners are square.
                rule.alpha = (ruleAlpha * ruleShare).toInt()
                canvas.drawRect(box.right - ruleWidth, box.top + r, box.right, box.bottom - r, rule)
            }
        }

        private fun shape(canvas: Canvas, rect: RectF, r: Float, paint: Paint) {
            if (r <= 0f) canvas.drawRect(rect, paint) else canvas.drawRoundRect(rect, r, r, paint)
        }

        /** The faint light from above: the window's shape again, over its upper part only. */
        private fun drawSheen(canvas: Canvas, r: Float, strength: Float) {
            if (sheen.shader == null) return
            sheen.alpha = (255 * strength).toInt()
            val count = canvas.save()
            canvas.clipRect(box.left, box.top, box.right, box.top + sheenHeight)
            // The gradient is made from 0 down: moved to the window's top, wherever that is just now.
            canvas.translate(0f, box.top)
            part.set(box.left, 0f, box.right, box.height())
            shape(canvas, part, r, sheen)
            canvas.restoreToCount(count)
        }

        /**
         * The highlight along the top edge, following the corners: the shape's outline, lit at the top and fading to
         * nothing round the corners, as the shared glass's highlight thins there.
         */
        private fun drawTopLight(canvas: Canvas, r: Float, strength: Float) {
            topLight.alpha = (255 * strength).toInt()
            val half = topLight.strokeWidth / 2f
            val count = canvas.save()
            canvas.clipRect(box.left, box.top, box.right, box.top + topLightHeight + topLight.strokeWidth)
            canvas.translate(0f, box.top)
            part.set(box.left + half, half, box.right - half, box.height() - half)
            shape(canvas, part, max(r - half, 0f), topLight)
            canvas.restoreToCount(count)
        }

        /**
         * The shadow round the shape, from its small picture: each corner's piece as far as the middle of a short
         * side (cropped, never squeezed), and one line of each long side's stretched between them.
         */
        private fun drawShade(canvas: Canvas, strength: Float) {
            val bitmap = shade ?: return
            val alpha = (255 * strength).toInt()
            if (alpha <= 0) return
            shadePaint.alpha = alpha
            val pad = shadePad
            val full = bitmap.width
            val mid = pad + shadeCorner
            val cx = min(shadeCorner.toFloat(), box.width() / 2f)
            val cy = min(shadeCorner.toFloat(), box.height() / 2f)
            val sx = (cx + 0.5f).toInt()
            val sy = (cy + 0.5f).toInt()
            val p = pad.toFloat()
            piece(canvas, bitmap, 0, 0, pad + sx, pad + sy, box.left - p, box.top - p, box.left + cx, box.top + cy)
            piece(canvas, bitmap, full - pad - sx, 0, full, pad + sy, box.right - cx, box.top - p, box.right + p, box.top + cy)
            piece(canvas, bitmap, 0, full - pad - sy, pad + sx, full, box.left - p, box.bottom - cy, box.left + cx, box.bottom + p)
            piece(canvas, bitmap, full - pad - sx, full - pad - sy, full, full, box.right - cx, box.bottom - cy, box.right + p, box.bottom + p)
            if (box.height() > 2f * cy) {
                piece(canvas, bitmap, 0, mid, pad, mid + 1, box.left - p, box.top + cy, box.left, box.bottom - cy)
                piece(canvas, bitmap, full - pad, mid, full, mid + 1, box.right, box.top + cy, box.right + p, box.bottom - cy)
            }
            if (box.width() > 2f * cx) {
                piece(canvas, bitmap, mid, 0, mid + 1, pad, box.left + cx, box.top - p, box.right - cx, box.top)
                piece(canvas, bitmap, mid, full - pad, mid + 1, full, box.left + cx, box.bottom, box.right - cx, box.bottom + p)
            }
        }

        private fun piece(canvas: Canvas, bitmap: Bitmap, sl: Int, st: Int, sr: Int, sb: Int, dl: Float, dt: Float, dr: Float, db: Float) {
            src.set(sl, st, sr, sb)
            dst.set(dl, dt, dr, db)
            canvas.drawBitmap(bitmap, src, dst, shadePaint)
        }

        /**
         * The page behind, blurred, in the shape's bounds: so it comes into view with the moving edge. A newer picture
         * of the page fades in over the one before, as in every window over the page ([GlassDrawable]).
         */
        private fun drawFilm(canvas: Canvas, r: Float, strength: Float) {
            val bd = source
            if (!live || bd == null) {
                // In motion with the picture it had (or with none to be had): nothing is asked for.
                paintFilm(canvas, r, film0, strength)
                return
            }
            val top = bd.frame(GlassBackdrop.Kind.PANEL)
            val f = bd.fadeFraction()
            val under = if (f < 1f) bd.previous(GlassBackdrop.Kind.PANEL) else null
            if (top == null && under == null) {
                // The pictures were let go (low memory): the next is owed, below.
                forgetFilm()
            } else {
                placeFilm(bd)
            }
            if (f >= 1f || (top == null && under == null)) {
                paintFilm(canvas, r, top, strength)
            } else {
                // A crossfade without an offscreen layer: the lower one is drawn weaker, so the sum is a true mix.
                val topAlpha = strength * f
                val lower = if (topAlpha >= 1f) 0f else (strength * (1f - f) / (1f - topAlpha)).coerceIn(0f, 1f)
                paintFilm(canvas, r, under, lower)
                paintFilm(canvas, r, top, topAlpha)
                watcher?.let { bd.onFadeDrawn(it) }
            }
            bd.onGlassDrawn()
        }

        /** The picture, or the dark fill where there is none, at [strength]. */
        private fun paintFilm(canvas: Canvas, r: Float, film: Bitmap?, strength: Float) {
            if (strength <= 0f) return
            if (film == null) {
                noFilm.alpha = (RailShape.NO_FILM_ALPHA * strength).toInt()
                shape(canvas, box, r, noFilm)
            } else {
                filmPaint.shader = shaderFor(film)
                filmPaint.alpha = (255 * strength).toInt()
                shape(canvas, box, r, filmPaint)
            }
        }

        /** A shader for [film]: made once per picture (a new one comes twice a second at most), never per frame. */
        private fun shaderFor(film: Bitmap): BitmapShader {
            val s0 = shader0
            if (film === film0 && s0 != null) return s0
            val s1 = shader1
            if (film === film1 && s1 != null) return s1
            film1 = film0
            shader1 = s0
            val s = BitmapShader(film, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            s.setLocalMatrix(filmMatrix)
            film0 = film
            shader0 = s
            return s
        }

        /** Lines the picture up with the screen, by the rail's place in the layout (as [GlassDrawable] does). */
        private fun placeFilm(bd: GlassSource) {
            var x = 0
            var y = 0
            var v: View = this@NavRail
            var p = v.parent
            while (p is View) {
                x += v.left - p.scrollX
                y += v.top - p.scrollY
                v = p
                p = v.parent
            }
            v.getLocationOnScreen(location)
            x += location[0]
            y += location[1]
            val sx = bd.screenScale
            val sy = bd.screenScaleY
            val ox = bd.originX
            val oy = bd.originY
            if (x == placedX && y == placedY && sx == placedScaleX && sy == placedScaleY && ox == placedOriginX && oy == placedOriginY) return
            placedX = x
            placedY = y
            placedScaleX = sx
            placedScaleY = sy
            placedOriginX = ox
            placedOriginY = oy
            filmMatrix.setScale(sx, sy)
            filmMatrix.postTranslate((ox - x).toFloat(), (oy - y).toFloat())
            shader0?.setLocalMatrix(filmMatrix)
            shader1?.setLocalMatrix(filmMatrix)
        }

        override fun setAlpha(alpha: Int) {}

        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getPadding(padding: Rect): Boolean {
            padding.setEmpty()
            return false
        }

        override fun invalidateDrawable(who: Drawable) = invalidateSelf()

        override fun scheduleDrawable(who: Drawable, what: Runnable, time: Long) = scheduleSelf(what, time)

        override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)
    }

    /**
     * A streaming service's tab: its name, its icon (a one-colour shape, or a mark in its own colours) and the colour
     * the icon is drawn in at rest ([rest]; null: its own colours). Focused, every one is [RailShape.SERVICE_FOCUSED].
     */
    data class ServiceTab(val name: String, val icon: Int, val rest: Int?)

    private val serviceFocused = ColorStateList.valueOf(RailShape.SERVICE_FOCUSED)

    companion object {
        /** The tabs of the streaming-service pages visited last, newest first. */
        val SERVICE_TABS = listOf(Tab.SERVICE_1, Tab.SERVICE_2)

        /**
         * The collapsed rail's width (dp): its 44 dp tabs with [SIDE_DP] each side. The pages beside it start here (Live
         * TV's groups column, the other tabs' pages).
         */
        const val WIDTH_DP = 58
        /** Widened, with labels. */
        const val EXPANDED_DP = 212
        const val SIDE_DP = 7
        /** Widened as a floating window: the tabs (and the white of the chosen one) keep this much clear of its edges. */
        const val WINDOW_PAD_DP = 10
    }
}

/**
 * The rail's shapes and its motion, in dp and shares of the way: where its window lies inside its view, how round its
 * corners are, where the tabs and the chosen one's white begin and end and how much of the labels shows, for any point
 * of its two motions. [wide] is the widening (0 icons only, 1 widened with labels) and [attach] the change between the
 * floating window (0) and the strip attached to the screen's edge (1); the view starts at the screen's left edge.
 * Every look value of the rail and of its motion is here. Pure numbers, so they can be checked without a screen.
 */
object RailShape {
    /** The floating window's distance from the view's left edge, and from where the rail's width ends on the right. */
    const val FLOAT_SIDE_DP = NavRail.SIDE_DP
    /** And from its top and bottom. */
    const val FLOAT_END_DP = 8
    /** The floating window's corners: the shared small windows'. The strip's are square. */
    const val RADIUS_DP = GlassStyle.RADIUS_POPUP
    /** The chosen tab's white. */
    const val TAB_RADIUS_DP = 12f
    /** The brand mark's box collapsed and widened, and the name beside it (shown only widened). */
    const val MARK_DP = 30
    const val MARK_WIDE_DP = 38
    const val NAME_SP = 24f

    /** A service's icon on a focused tab's white: dark, whatever its resting colour. */
    const val SERVICE_FOCUSED = 0xFF10141A.toInt()
    /** The resting colours of the one-colour service icons: Disney's light-blue D; white (HBO Max, Apple TV). */
    const val SERVICE_LIGHT_BLUE = 0xFF8CCBFF.toInt()
    const val SERVICE_WHITE = 0xFFFFFFFF.toInt()

    /** Classic: a little darker than the classic wallpaper along the screen's left edge (about #0C1117). */
    const val CLASSIC_FILL = 0xFF080B0F.toInt()
    /** The hairline down the strip's right edge, as at the groups column's edge, one per look; classic's also rounds its window. */
    const val CLASSIC_RULE = 0x14FFFFFF
    const val GLASS_RULE = 0x1FFFFFFF
    /** Glass, widened, where the page behind can't be shown: of [GlassStyle.WINDOW_SOLID], so no word behind can be read. */
    const val NO_FILM_ALPHA = 0xE0

    // ---- The motion -------------------------------------------------------------------------------------------------

    /** Widening, collapsing, and the change between strip and window, start to end, ms. */
    const val MOVE_MS = 280L
    /**
     * Its curve, a cubic Bezier through these two points (as CSS's cubic-bezier): away fast (half the way in the first
     * fifth of the time, after one gentle frame) and a long soft landing.
     */
    const val EASE_X1 = 0.2f
    const val EASE_Y1 = 0f
    const val EASE_X2 = 0f
    const val EASE_Y2 = 1f
    /** A motion turned round part-way takes that part of [MOVE_MS], this share of it at least. */
    const val SHORT_MOVE = 0.5f
    /** One frame on the TV, ms, and the most time a single frame may move the rail on (two frames). */
    const val FRAME_MS = 1000f / 60f
    const val MAX_STEP_MS = 2f * FRAME_MS
    /** The labels (and the name) fade in between these shares of the widening, sliding in from this far left. */
    const val LABEL_FROM = 0.3f
    const val LABEL_FULL = 0.85f
    const val LABEL_SLIDE_DP = 8f
    /** The blurred page fades in under the tint over this first share of the widening. */
    const val FILM_FULL = 0.2f
    /** After the page was copied for the widening rail, it isn't copied again for this long: never in the motion. */
    const val FILM_HOLD_MS = MOVE_MS + 120L

    /** The rail's width as it is drawn: the collapsed rail's to the widened rail's. */
    fun widthDp(wide: Float): Float = between(NavRail.WIDTH_DP.toFloat(), NavRail.EXPANDED_DP.toFloat(), wide)

    /** The shape's left edge (and how far short of the rail's width its right edge stops): inset floating, none attached. */
    fun insetXDp(attach: Float): Float = FLOAT_SIDE_DP * (1f - attach)

    /** Its distance from the view's top and from its bottom. */
    fun insetYDp(attach: Float): Float = FLOAT_END_DP * (1f - attach)

    /** Its right edge: the one that moves as the rail widens. */
    fun rightDp(wide: Float, attach: Float): Float = widthDp(wide) - insetXDp(attach)

    /** Its width. */
    fun windowWidthDp(wide: Float, attach: Float): Float = rightDp(wide, attach) - insetXDp(attach)

    /** Its corner radius: the window's, growing from the strip's square corners. */
    fun radiusDp(attach: Float): Float = RADIUS_DP * (1f - attach)

    /** How strong the window's outline, top highlight, sheen and shadow are: all there floating, none on the strip. */
    fun outline(attach: Float): Float = (1f - attach).coerceIn(0f, 1f)

    /**
     * How far right of their collapsed place the tabs (the mark, the icons, the labels) are drawn. Widened as a window
     * they step in from its edge by [NavRail.WINDOW_PAD_DP]; collapsed, and on the strip, they stand where they always
     * do. So nothing moves sideways as Live TV is entered or left with the rail narrow, nor when Live TV is left with
     * it wide (the widened strip becoming the narrow window).
     */
    fun shiftDp(wide: Float, attach: Float): Float = NavRail.WINDOW_PAD_DP * (wide - attach).coerceIn(0f, 1f)

    /** The tabs' left edge: where the chosen tab's white begins. */
    fun tabsLeftDp(wide: Float, attach: Float): Float = NavRail.SIDE_DP + shiftDp(wide, attach)

    /** The tabs' right edge, where the white ends: it follows the rail's moving edge. */
    fun tabsRightDp(wide: Float, attach: Float): Float = widthDp(wide) - NavRail.SIDE_DP - shiftDp(wide, attach)

    /** How much of the labels and the name shows. */
    fun labelAlpha(wide: Float): Float = ramp(wide, LABEL_FROM, LABEL_FULL)

    /** How far left of their place they are drawn meanwhile (0 or less): they slide in as they appear. */
    fun labelSlideDp(wide: Float): Float = -LABEL_SLIDE_DP * (1f - labelAlpha(wide))

    /** The mark's size, as a share of its widened one. */
    fun markScale(wide: Float): Float = between(MARK_DP / MARK_WIDE_DP.toFloat(), 1f, wide.coerceIn(0f, 1f))

    /** How much of the blurred page shows under the tint: none collapsed (there is only the wallpaper behind). */
    fun filmAlpha(wide: Float): Float = ramp(wide, 0f, FILM_FULL)

    /** The motion's curve: the share of the way done after the share [t] of the time. Exactly 0 at 0 and 1 at 1. */
    fun ease(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        // The curve's point whose time is t, by halving (time only ever grows along the curve).
        var lo = 0f
        var hi = 1f
        var s = t
        repeat(EASE_HALVINGS) {
            if (bezier(s, EASE_X1, EASE_X2) < t) lo = s else hi = s
            s = (lo + hi) / 2f
        }
        return bezier(s, EASE_Y1, EASE_Y2).coerceIn(0f, 1f)
    }

    private const val EASE_HALVINGS = 20

    /** A cubic Bezier from 0 to 1 with the control values [p1] and [p2], at [s]. */
    private fun bezier(s: Float, p1: Float, p2: Float): Float {
        val u = 1f - s
        return 3f * u * u * s * p1 + 3f * u * s * s * p2 + s * s * s
    }

    /** How long a motion over [distance] of the way takes, ms: 0 for none. */
    fun moveMs(distance: Float): Float = if (distance <= 0f) 0f else MOVE_MS * distance.coerceIn(SHORT_MOVE, 1f)

    /**
     * The time one frame moves a motion on, for [elapsedMs] since the frame before: that time, but never more than
     * [MAX_STEP_MS]. A frame that took long (the page's first drawing, its picture being copied) is followed by the
     * motion's next step, not by a jump over what was missed.
     */
    fun stepMs(elapsedMs: Float): Float = elapsedMs.coerceIn(0f, MAX_STEP_MS)

    /** 0 up to [from], 1 from [full], evenly between. */
    private fun ramp(v: Float, from: Float, full: Float): Float = ((v - from) / (full - from)).coerceIn(0f, 1f)

    /** [from] to [to], [f] of the way (0 at [from], 1 at [to]). */
    fun between(from: Float, to: Float, f: Float): Float = from + (to - from) * f
}

/**
 * One of the rail's motions: a value on its way to [target] along [RailShape.ease], stepped a frame at a time. Aimed
 * somewhere else part-way, it sets off from where it is (never from an end); it arrives at exactly [target]. Makes
 * nothing as it runs.
 */
class RailMotion(start: Float) {
    var value = start
        private set
    var target = start
        private set
    private var from = start
    private var elapsedMs = 0f
    private var durationMs = 0f

    val running: Boolean get() = durationMs > 0f

    /** Heads for [to] from where it is now. */
    fun aim(to: Float) {
        if (to == target) return
        target = to
        from = value
        elapsedMs = 0f
        durationMs = RailShape.moveMs(abs(to - from))
        if (durationMs <= 0f) value = to
    }

    /** There at once. */
    fun snap(to: Float) {
        target = to
        from = to
        value = to
        elapsedMs = 0f
        durationMs = 0f
    }

    /** [dtMs] further on. */
    fun step(dtMs: Float) {
        if (durationMs <= 0f) return
        elapsedMs += dtMs
        if (elapsedMs >= durationMs) {
            value = target
            durationMs = 0f
        } else {
            value = RailShape.between(from, target, RailShape.ease(elapsedMs / durationMs))
        }
    }
}
