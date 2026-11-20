package app.fireplayer.vod.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.fireplayer.R
import app.fireplayer.live.Channel
import app.fireplayer.live.LiveDb
import app.fireplayer.live.Programme
import app.fireplayer.live.ui.LogoCache
import app.fireplayer.live.ui.LogoView
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.DrawnSpan
import app.fireplayer.ui.Glass
import app.fireplayer.ui.GlassStyle
import java.util.Date
import java.util.concurrent.Executors

/**
 * Home's top-right window (330 dp wide, as tall as the banner beside it), reserved for the live picture that will
 * follow the viewer across tabs. Until that exists it lists the channels last watched on Live TV: each one's logo on
 * its tile, its name, and under it, smaller, what's on now, with a fine line between rows. Three show at a time; with
 * more, the list moves with the chosen row (a translation) and a slim scrollbar at the right says where it is. Read
 * from the Live TV database on a background thread when the page starts.
 *
 * Focus comes in from the banner's Details ([takeFocus]); Up and Down move through the channels, Left goes back
 * ([onLeft]), Down past the last one leaves ([onDown]) and OK picks the channel ([onPick]).
 *
 * The live picture's wider window becomes it again when the picture closes: its surface is drawn reaching left of
 * where it's laid out, back to its own width in one motion ([drawFrom]), its contents hidden meanwhile and fading in
 * once it's there ([showContents]).
 */
@SuppressLint("ViewConstructor")
class RecentWindow(context: Context, private val t: Tokens) : LinearLayout(context) {

    var onLeft: (() -> Unit)? = null
    var onDown: (() -> Unit)? = null
    var onPick: ((Channel) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val logos = LogoCache(t.dp(VodSizes.RECENT_LOGO_PX)) { _, _ -> refreshLogos() }
    private val lines = ArrayList<Line>()
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val body = FrameLayout(context)
    private val emptyText = t.label(context, VodSizes.RECENT_EMPTY).apply {
        text = "Channels you watch on Live TV appear here"
        maxLines = 3
    }
    private var shown: List<Channel> = emptyList()
    private var generation = 0
    @Volatile private var released = false
    /** The row chosen last, and the first row showing. */
    private var chosen = 0
    private var first = 0
    private val rowH = t.dp(VodSizes.RECENT_ROW_H)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = RectF()

    /** Its surface (glass or classic), drawn here so it can reach past its left edge ([drawFrom]). */
    private var surface: Drawable? = null
    /** Where its surface's left edge is drawn, px from its own: 0 at rest, less while the picture's window narrows. */
    private var drawnLeft = 0f
    /** How much of its contents (the heading, the channels, the scrollbar) shows: they wait for its own width. */
    private var contents = 1f
    private var contentsFade: android.animation.ValueAnimator? = null

    /** One channel's row: draws its own focus shape and the fine line under it. */
    private inner class Line(context: Context) : LinearLayout(context) {
        val logo = LogoView(context)
        val name: TextView = t.label(context, VodSizes.RECENT_NAME)
        val now: TextView = t.label(context, VodSizes.RECENT_NOW)
        var logoUrl: String? = null
        var channel: Channel? = null
        /** Whether a line is drawn under it (every row but the last). */
        var divider = false
            set(v) {
                if (field == v) return
                field = v
                invalidate()
            }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shape = RectF()
        private var okDown = false

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = GONE
            isFocusable = true
            setWillNotDraw(false)
            val pad = t.dp(VodSizes.RECENT_ROW_PAD)
            setPadding(pad, 0, pad, 0)
            addView(logo, LayoutParams(t.dp(VodSizes.RECENT_LOGO_W), t.dp(VodSizes.RECENT_LOGO_H)))
            val text = LinearLayout(context).apply { orientation = VERTICAL }
            text.addView(name, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            text.addView(now, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = t.dp(VodSizes.RECENT_LINE_GAP) })
            // A fixed width (what's left of the row), so new words only redraw.
            addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = t.dp(VodSizes.RECENT_LOGO_GAP) })
            setOnFocusChangeListener { _, has ->
                okDown = false
                invalidate()
                if (has) onRowFocused(this)
            }
            setOnKeyListener { _, keyCode, event -> onRowKey(this, keyCode, event) }
        }

        /** OK acts on its release, and only when it went down on this row. */
        fun ok(event: KeyEvent): Boolean {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) okDown = true
                KeyEvent.ACTION_UP -> {
                    val was = okDown
                    okDown = false
                    if (was && !event.isCanceled) channel?.let { onPick?.invoke(it) }
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (isFocused) {
                // Chosen: a soft fill and the cards' white ring.
                val ring = t.dpf(VodSizes.RECENT_FOCUS_RING)
                val inset = t.dpf(VodSizes.RECENT_FOCUS_INSET)
                val r = t.dpf(VodSizes.CARD_RADIUS)
                shape.set(ring / 2f, inset, w - ring / 2f, h - inset)
                paint.style = Paint.Style.FILL
                paint.color = GlassStyle.ROW_HIGHLIGHT
                canvas.drawRoundRect(shape, r, r, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = ring
                paint.color = VodSizes.RING_COLOR
                canvas.drawRoundRect(shape, r, r, paint)
            } else if (divider) {
                val line = GlassStyle.hairlinePx(resources.displayMetrics.density)
                paint.style = Paint.Style.FILL
                paint.color = GlassStyle.HAIRLINE
                canvas.drawRect(paddingLeft.toFloat(), h - line, w - paddingRight, h, paint)
            }
        }
    }

    init {
        orientation = VERTICAL
        isFocusable = false
        setWillNotDraw(false)
        // The rows reach a little outside the heading's edge (their focus shape's room).
        val side = t.dp(VodSizes.RECENT_PAD_X - VodSizes.RECENT_ROW_PAD)
        setPadding(side, t.dp(VodSizes.RECENT_PAD_TOP), side, t.dp(VodSizes.RECENT_PAD_BOTTOM))
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_history)
            setColorFilter(t.headingIcon)
        }, LayoutParams(t.dp(VodSizes.RECENT_ICON), t.dp(VodSizes.RECENT_ICON)))
        head.addView(t.label(context, VodSizes.RECENT_HEAD).apply { text = "Recent channels"; setTextColor(t.heading) },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = t.dp(VodSizes.RECENT_ICON_GAP) })
        addView(head, LayoutParams(LayoutParams.MATCH_PARENT, t.dp(VodSizes.RECENT_HEAD_H)).apply {
            leftMargin = t.dp(VodSizes.RECENT_ROW_PAD)
            rightMargin = t.dp(VodSizes.RECENT_ROW_PAD)
        })
        // The list's box clips: three rows show, the others wait above and below.
        body.addView(list, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0))
        body.addView(emptyText, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = t.dp(VodSizes.RECENT_ROW_PAD)
            rightMargin = t.dp(VodSizes.RECENT_ROW_PAD)
            topMargin = t.dp(VodSizes.RECENT_EMPTY_TOP - VodSizes.RECENT_FIRST_TOP)
        })
        addView(body, LayoutParams(LayoutParams.MATCH_PARENT, t.dp(VodSizes.recentListH())).apply { topMargin = t.dp(VodSizes.RECENT_FIRST_TOP) })
        applyLook()
    }

    fun applyLook() {
        surface?.callback = null
        surface = Glass.surface(context, Glass.Kind.PANEL).also { it.callback = this }
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        surface?.let { d ->
            val b = d.bounds
            if (b.left != 0 || b.top != 0 || b.right != width || b.bottom != height) d.setBounds(0, 0, width, height)
            DrawnSpan.draw(canvas, d, drawnLeft, width.toFloat())
        }
        super.draw(canvas)
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === surface || super.verifyDrawable(who)

    /** Its surface drawn from [leftPx] (px from its own left edge; 0 at rest): drawing only, nothing laid out. */
    fun drawFrom(leftPx: Float) {
        if (drawnLeft == leftPx) return
        drawnLeft = leftPx
        invalidate()
    }

    /** Its contents hidden at once ([on] false), or shown: fading in with [animate] (its window has its width now). */
    fun showContents(on: Boolean, animate: Boolean) {
        contentsFade?.cancel()
        contentsFade = null
        val to = if (on) 1f else 0f
        if (!animate || contents == to) return setContents(to)
        contentsFade = android.animation.ValueAnimator.ofFloat(contents, to).apply {
            duration = VodSizes.RECENT_FADE_IN_MS
            addUpdateListener { setContents(it.animatedValue as Float) }
            start()
        }
    }

    private fun setContents(a: Float) {
        contents = a
        for (i in 0 until childCount) getChildAt(i).alpha = a
        // The scrollbar is drawn here.
        invalidate()
    }

    /** Focus to the channel chosen last (the first, at first); false with none to take it. */
    fun takeFocus(): Boolean {
        if (shown.isEmpty()) return false
        chosen = chosen.coerceIn(0, shown.size - 1)
        return lines[chosen].requestFocus()
    }

    /** Focus wanted on the first channel once the list is read again ([focusFirst]). */
    private var firstWanted = false
    /** Where focus waits meanwhile when there was no channel to take it ([focusFirst]'s fallback). */
    private var firstFallback: android.view.View? = null

    /**
     * Focus to the first channel (after the live picture's Close, which this window replaces): read again, as the
     * channel just watched is first now. With none listed yet, focus waits on [fallback] and comes here once they're
     * read, unless the viewer has moved on meanwhile.
     */
    fun focusFirst(fallback: () -> Unit) {
        firstWanted = true
        firstFallback = null
        refresh()
        if (shown.isEmpty()) {
            fallback()
            firstFallback = rootView.findFocus()
            return
        }
        chosen = 0
        scrollTo(0, animate = false)
        lines[0].requestFocus()
    }

    private fun onRowFocused(line: Line) {
        val i = lines.indexOf(line)
        if (i < 0) return
        chosen = i
        scrollTo(VodSizes.recentFirstShown(i, first, shown.size), animate = true)
    }

    private fun onRowKey(line: Line, keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            return line.ok(event)
        }
        val dpad = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        if (event.action != KeyEvent.ACTION_DOWN) return dpad
        val i = lines.indexOf(line)
        when (keyCode) {
            // One channel per press, also while held; nothing wraps.
            KeyEvent.KEYCODE_DPAD_UP -> if (i > 0) lines[i - 1].requestFocus()
            KeyEvent.KEYCODE_DPAD_DOWN -> if (i < shown.size - 1) lines[i + 1].requestFocus() else onDown?.invoke()
            KeyEvent.KEYCODE_DPAD_LEFT -> onLeft?.invoke()
            KeyEvent.KEYCODE_DPAD_RIGHT -> Unit
            else -> return false
        }
        return true
    }

    /** Row [to] becomes the first showing: the list moves (a translation), the scrollbar with it. */
    private fun scrollTo(to: Int, animate: Boolean) {
        first = to
        val y = -(to * rowH).toFloat()
        list.animate().cancel()
        if (!animate || !isShown) {
            list.translationY = y
            invalidate()
            return
        }
        if (list.translationY == y) return
        list.animate().translationY(y).setDuration(VodSizes.RECENT_SCROLL_MS).setUpdateListener { invalidate() }.start()
    }

    /** The scrollbar, over the window's right edge beside the list: only while there are more channels than show. */
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val n = shown.size
        val showing = VodSizes.RECENT_ROWS_SHOWN
        if (n <= showing || contents <= 0f) return
        val w = t.dpf(VodSizes.RECENT_BAR_W)
        val right = width - t.dpf(VodSizes.RECENT_BAR_RIGHT)
        val top = body.top.toFloat()
        val h = body.height.toFloat()
        bar.set(right - w, top, right, top + h)
        barPaint.color = GlassStyle.ROW_FILL
        barPaint.alpha = (barPaint.alpha * contents).toInt()
        canvas.drawRoundRect(bar, w / 2f, w / 2f, barPaint)
        val thumbH = h * showing / n
        val most = ((n - showing) * rowH).toFloat()
        val at = if (most > 0f) (-list.translationY / most).coerceIn(0f, 1f) else 0f
        val y = top + (h - thumbH) * at
        bar.set(right - w, y, right, y + thumbH)
        barPaint.color = GlassStyle.HIGHLIGHT
        barPaint.alpha = (barPaint.alpha * contents).toInt()
        canvas.drawRoundRect(bar, w / 2f, w / 2f, barPaint)
    }

    /** Reads the channels last watched and what's on them now (background thread), then shows them. */
    fun refresh() {
        if (released) return
        val gen = ++generation
        val ctx = context.applicationContext
        pool.execute {
            val result = try {
                val db = LiveDb.get(ctx)
                val channels = db.recent().filter { !it.blocked }.take(VodSizes.RECENT_MAX)
                val now = System.currentTimeMillis()
                val progs = db.programmes(channels, now, now + 1)
                channels to progs
            } catch (e: Exception) {
                null
            }
            main.post {
                if (released || gen != generation || result == null) return@post
                show(result.first, result.second)
            }
        }
    }

    fun release() {
        released = true
        firstFallback = null
        list.animate().cancel()
        logos.onChange = null
        logos.clear()
    }

    private fun show(channels: List<Channel>, programmes: Map<app.fireplayer.live.ChannelKey, List<Programme>>) {
        // Focus on a row that goes: to the last one that stays first, or out of the window (a focused view hidden
        // would hand focus anywhere).
        val focused = lines.indexOfFirst { it.isFocused }
        if (focused >= channels.size) {
            if (channels.isEmpty()) onLeft?.invoke() else lines[channels.size - 1].requestFocus()
        }
        shown = channels
        while (lines.size < channels.size) {
            val line = Line(context)
            list.addView(line, LayoutParams(LayoutParams.MATCH_PARENT, rowH))
            lines += line
        }
        // As tall as its rows (taller than its box, which clips): laid out again only when their number changes.
        val lp = list.layoutParams
        val listH = channels.size * rowH
        if (lp.height != listH) {
            lp.height = listH
            list.layoutParams = lp
        }
        val now = System.currentTimeMillis()
        val time = DateFormat.getTimeFormat(context)
        lines.forEachIndexed { i, line ->
            val ch = channels.getOrNull(i)
            line.channel = ch
            if (ch == null) {
                line.visibility = GONE
                line.logoUrl = null
                return@forEachIndexed
            }
            line.visibility = VISIBLE
            line.divider = i < channels.size - 1
            line.contentDescription = ch.name
            line.name.text = ch.name
            val on = programmes[ch.key]?.firstOrNull { it.isOn(now) }
            line.now.text = on?.let { "${it.title} · ${time.format(Date(it.start))}–${time.format(Date(it.stop))}" } ?: "No guide for now"
            line.logoUrl = ch.logo
        }
        emptyText.visibility = if (channels.isEmpty()) VISIBLE else GONE
        chosen = if (channels.isEmpty()) 0 else chosen.coerceIn(0, channels.size - 1)
        // The list stays where it was when it still can; the chosen row stays in view.
        val at = lines.indexOfFirst { it.isFocused }.takeIf { it >= 0 } ?: first
        scrollTo(VodSizes.recentFirstShown(at, first, channels.size), animate = false)
        refreshLogos()
        invalidate()
        // After the live picture's Close: on the first channel, unless the viewer has moved on from the window.
        if (firstWanted) {
            firstWanted = false
            val waiting = firstFallback
            firstFallback = null
            val here = lines.firstOrNull()?.isFocused == true || waiting?.isFocused == true
            if (here && channels.isNotEmpty()) {
                chosen = 0
                scrollTo(0, animate = false)
                lines[0].requestFocus()
            }
        }
    }

    private fun refreshLogos() {
        for (line in lines) {
            val url = line.logoUrl
            if (url.isNullOrEmpty()) {
                line.logo.set(null, plate = false)
                continue
            }
            val logo = logos.get(url)
            line.logo.set(logo, plate = logo != null || !logos.missing(url))
        }
    }

    private companion object {
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "FirePlayerRecent").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    }
}
