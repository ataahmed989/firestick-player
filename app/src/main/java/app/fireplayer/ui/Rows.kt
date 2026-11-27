package app.fireplayer.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import app.fireplayer.R

/**
 * A ScrollView that keeps the focused row in the middle and glides there,
 * instead of jumping when focus reaches the edge.
 */
class CenterScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    private val rect = Rect()
    // Our own glide: keeps its speed from one press to the next, so a held D-pad scrolls in one smooth run.
    private val glide = Spring(omega = 20f) { scrollTo(0, it.toInt()) }

    /** The tallest the list may grow, in pixels; 0 means no limit. */
    var maxHeight = 0

    // We do our own scrolling in requestChildFocus.
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var heightSpec = heightMeasureSpec
        if (maxHeight > 0) {
            val available = MeasureSpec.getSize(heightMeasureSpec)
            val limit = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maxHeight else minOf(maxHeight, available)
            heightSpec = MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST)
        }
        super.onMeasure(widthMeasureSpec, heightSpec)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // A new height moves the middle, so put the focused row back in it.
        findFocus()?.takeIf { it !== this }?.let { center(it, animate = false) }
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        if (focused != null) center(focused, animate = true)
    }

    override fun onDetachedFromWindow() {
        glide.stop()
        super.onDetachedFromWindow()
    }

    /**
     * A slim bar at the right edge, while the list is longer than the window: for a window read with Up and Down
     * ([SidePanel.scrollHint]). Off by default; its paints exist only while it's on.
     */
    var slimBar = false
        set(on) {
            if (field == on) return
            field = on
            // Made while a window shows it, let go with it.
            bar = if (on) SlimBar(resources.displayMetrics.density) else null
            invalidate()
        }

    /** While [slimBar] is on, told after each layout whether the list is longer than the window (posted, not mid-layout). */
    var onOverflow: ((Boolean) -> Unit)? = null

    private class SlimBar(density: Float) {
        val width = 3f * density
        val inset = 4f * density
        val minThumb = 24f * density
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
        val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8CFFFFFF.toInt() }
        val rect = RectF()
    }
    private var bar: SlimBar? = null

    /** How far the list reaches past the window, in px (0: it fits). */
    private fun overflow(): Int = ((getChildAt(0)?.height ?: 0) - (height - paddingTop - paddingBottom)).coerceAtLeast(0)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val report = onOverflow
        if (slimBar && report != null) {
            val over = overflow() > 0
            post { if (slimBar) report(over) }
        }
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        // After the list and its faded edges, so the bar isn't faded with them.
        val b = bar ?: return
        if (!slimBar) return
        val max = overflow()
        if (max <= 0) return
        // Drawn in the list's own coordinates: the window's top is at scrollY.
        val view = height - paddingTop - paddingBottom
        val trackTop = scrollY + paddingTop + b.inset
        val trackLength = view - 2 * b.inset
        if (trackLength <= 0f) return
        val right = (width - paddingRight).toFloat()
        val r = b.width / 2f
        b.rect.set(right - b.width, trackTop, right, trackTop + trackLength)
        canvas.drawRoundRect(b.rect, r, r, b.track)
        val thumb = (trackLength * view / (view + max)).coerceIn(b.minThumb.coerceAtMost(trackLength), trackLength)
        val top = trackTop + (trackLength - thumb) * (scrollY.toFloat() / max).coerceIn(0f, 1f)
        b.rect.set(right - b.width, top, right, top + thumb)
        canvas.drawRoundRect(b.rect, r, r, b.thumb)
    }

    /**
     * Glides the list on by [dy] px, within its ends: a window with nothing to select is read with Up and Down
     * ([SidePanel.scrollsWithArrows]). A held key keeps adding to where it's going, so it scrolls in one smooth run.
     */
    fun glideBy(dy: Int) {
        val content = getChildAt(0)?.height ?: 0
        val maxScroll = (content - (height - paddingTop - paddingBottom)).coerceAtLeast(0)
        if (!glide.moving) glide.snap(scrollY.toFloat())
        glide.glideTo((glide.target + dy).coerceIn(0f, maxScroll.toFloat()))
    }

    fun center(view: View, animate: Boolean) {
        if (!view.isLaidOut || view.isLayoutRequested) {
            view.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    if (view.isAttachedToWindow) center(view, animate = false)
                }
            })
            return
        }
        view.getDrawingRect(rect)
        offsetDescendantRectToMyCoords(view, rect)
        val content = getChildAt(0)?.height ?: 0
        val maxScroll = (content - (height - paddingTop - paddingBottom)).coerceAtLeast(0)
        val target = (rect.centerY() - height / 2).coerceIn(0, maxScroll)
        if (!animate || target == scrollY) {
            glide.snap(target.toFloat())
            return
        }
        // From where it is now (a scroll by other means since moves the start too).
        if (kotlin.math.abs(glide.value - scrollY) >= 1f) glide.snap(scrollY.toFloat())
        glide.glideTo(target.toFloat())
    }
}

/**
 * A row in a settings window or picker. Its text and icons are coloured from the row's own state:
 * dark on the white selected row, blue for the current choice, light otherwise.
 *
 * The colours are set here rather than through duplicateParentState, which left the text of the
 * selected row light, and so invisible on its white background.
 */
open class RowLayout(context: Context, private val colors: ColorStateList?) : LinearLayout(context) {

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        recolor()
    }

    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        recolor()
    }

    private fun recolor() {
        val list = colors ?: return
        val color = list.getColorForState(drawableState, list.defaultColor)
        for (i in 0 until childCount) {
            when (val child = getChildAt(i)) {
                is TextView -> if (child.tag != KEEP_COLORS && child.currentTextColor != color) child.setTextColor(color)
                is CoverImage -> Unit
                is ImageView -> if (child.tag != KEEP_COLORS && child.imageTintList?.defaultColor != color) child.imageTintList = ColorStateList.valueOf(color)
            }
        }
    }
}

/** A picture (a TMDB cover) in a row: never recoloured with the row's text, which would leave a blank shape. */
class CoverImage(context: Context) : androidx.appcompat.widget.AppCompatImageView(context)

/** Tag for a row's image or text that keeps its own colours (the glass switch, a badge) instead of the text colour. */
const val KEEP_COLORS = "keepColors"

/** Tag for a row and the small button beside it ([SidePanel.rowWithArrow]): the D-pad goes up and down past both. */
const val ROW_PAIR = "rowPair"

/** Shared look and D-pad behaviour for the rows in the settings windows and pickers. */
class Rows(private val context: Context) {

    val density = context.resources.displayMetrics.density
    private val font600: Typeface? = ResourcesCompat.getFont(context, R.font.figtree_600)
    private val font700: Typeface? by lazy { ResourcesCompat.getFont(context, R.font.figtree_700) }
    val textColors = ContextCompat.getColorStateList(context, R.color.sel_text_row)

    fun dp(value: Int) = (value * density).toInt()

    /**
     * How wide a row's value may grow before it's shortened, in dp: wider in a wide window ([SidePanel]), which is
     * wide so long names and values can be read.
     */
    var valueMaxDp = VALUE_MAX_DP

    fun base(): LinearLayout = RowLayout(context, textColors).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        Glass.background(this, Glass.Kind.ROW)
        minimumHeight = dp(33)
        setPadding(dp(12), dp(6), dp(12), dp(6))
    }

    fun text(value: String) = TextView(context).apply {
        text = value
        typeface = font600
        setTextColor(textColors)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    fun icon(res: Int, sizeDp: Int) = ImageView(context).apply {
        setImageResource(res)
        imageTintList = textColors
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { marginStart = dp(8) }
    }

    /**
     * A label on the left, an optional value on the right, and an optional trailing icon.
     * [labelLines] above 1 lets a long label (a subtitle file name) wrap instead of being cut short.
     * With the glass look an "On" / "Off" value is a switch, unless [asSwitch] is false (a picker's current value).
     */
    /** A stepper's value and arrows: blue, a deeper blue on the white selected row. */
    val stepColors: ColorStateList? = ContextCompat.getColorStateList(context, R.color.sel_step_value)

    fun build(
        label: String,
        value: String?,
        trailingIcon: Int?,
        labelLines: Int = 1,
        asSwitch: Boolean = true,
        valueColors: ColorStateList? = null,
    ): LinearLayout {
        val row = base()
        fill(row, label, value, trailingIcon, labelLines, asSwitch, valueColors)
        return row
    }

    /**
     * What [build] puts in a row, put into [row] (an empty row from [base]): a row whose words change
     * ([SidePanel.updateRow], a long list's row shown for another entry) gets them without a new row being made.
     * Returns the label.
     */
    fun fill(
        row: LinearLayout,
        label: String,
        value: String?,
        trailingIcon: Int?,
        labelLines: Int = 1,
        asSwitch: Boolean = true,
        valueColors: ColorStateList? = null,
    ): TextView {
        val labelView = text(label).apply {
            if (labelLines > 1) {
                isSingleLine = false
                maxLines = labelLines
                setLineSpacing(1f * density, 1f)
            }
        }
        row.addView(labelView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // A long value is shortened rather than pushing the label off the row.
        if (Glass.on && asSwitch && (value == "On" || value == "Off")) {
            // Glass look: on/off settings show a switch instead of the word. It takes the row's state, so an off
            // switch can turn dark on the white selected row. The on glow reaches past the switch into the row's
            // padding, so the row mustn't cut it off; nothing is moved or resized for it.
            row.clipChildren = false
            row.clipToPadding = false
            row.addView(ImageView(context).apply {
                setImageDrawable(Glass.toggle(context, value == "On"))
                contentDescription = value
                tag = KEEP_COLORS
                // Before it's added, so the row passes its state changes on.
                isDuplicateParentStateEnabled = true
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(20)).apply { marginStart = dp(8) }
            })
        } else if (value != null && asSwitch && (value == "On" || value == "Off")) {
            // Without the glass look: the word, On in blue and Off in a dull dark gray, each darker on the white
            // selected row so it stays readable there.
            row.addView(text(value).apply {
                tag = KEEP_COLORS
                // Before it's added, so the row passes its state changes on.
                isDuplicateParentStateEnabled = true
                setTextColor(ContextCompat.getColorStateList(context, if (value == "On") R.color.sel_value_on else R.color.sel_value_off))
            })
        } else if (value != null && valueColors != null) {
            row.addView(text(value).apply {
                tag = KEEP_COLORS
                // Before it's added, so the row passes its state changes on.
                isDuplicateParentStateEnabled = true
                setTextColor(valueColors)
                maxWidth = dp(valueMaxDp)
            })
        } else if (value != null) {
            row.addView(text(value).apply { alpha = VALUE_ALPHA; maxWidth = dp(valueMaxDp) })
        }
        if (trailingIcon != null) row.addView(icon(trailingIcon, 15))
        return labelView
    }

    /** How a [badge] looks: set (blue), not set (faded gray), refused (soft red), or being checked (gray). */
    /** [LIGHT]: on, but a step before [ACTIVE] (in use while it's confirmed, or checking again): a lighter blue. */
    enum class Badge { ACTIVE, LIGHT, INACTIVE, PROBLEM, BUSY }

    /**
     * A small pill on the right of a row saying how a setting stands ("Active", "Inactive"). It follows the row's
     * state: on the white selected row its colours turn darker, so the blue stays readable there.
     */
    fun badge(label: String, style: Badge): TextView {
        fun color(res: Int) = ContextCompat.getColor(context, res)
        val (textRes, fillRes, rimRes) = when (style) {
            Badge.ACTIVE -> Triple(R.color.badge_on_text, R.color.badge_on_fill, R.color.badge_on_rim)
            Badge.LIGHT -> Triple(R.color.badge_light_text, R.color.badge_light_fill, R.color.badge_light_rim)
            Badge.PROBLEM -> Triple(R.color.badge_problem, R.color.badge_problem_fill, R.color.badge_problem_rim)
            Badge.INACTIVE, Badge.BUSY -> Triple(R.color.badge_off, R.color.badge_off_fill, R.color.badge_off_rim)
        }
        val (focusedTextRes, focusedFillRes) = when (style) {
            Badge.ACTIVE -> R.color.badge_on_focused to R.color.badge_on_fill_focused
            Badge.LIGHT -> R.color.badge_light_focused to R.color.badge_light_fill_focused
            Badge.PROBLEM -> R.color.badge_problem_focused to R.color.badge_problem_fill_focused
            Badge.INACTIVE, Badge.BUSY -> R.color.badge_off_focused to R.color.badge_off_fill_focused
        }
        fun pill(fillColor: Int, rimColor: Int?) = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(fillColor)
            // The glass look's pills have a light rim, like its other glass shapes.
            if (rimColor != null) setStroke(dp(1).coerceAtLeast(1), rimColor)
        }
        val focused = intArrayOf(android.R.attr.state_focused)
        return TextView(context).apply {
            text = label
            tag = KEEP_COLORS
            // Before it's added, so the row passes its state changes on.
            isDuplicateParentStateEnabled = true
            typeface = font700
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            letterSpacing = 0.02f
            includeFontPadding = false
            isSingleLine = true
            gravity = Gravity.CENTER
            minWidth = dp(54)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            setTextColor(ColorStateList(arrayOf(focused, intArrayOf()), intArrayOf(color(focusedTextRes), color(textRes))))
            background = StateListDrawable().apply {
                setEnterFadeDuration(140)
                setExitFadeDuration(140)
                addState(focused, pill(color(focusedFillRes), null))
                addState(intArrayOf(), pill(color(fillRes), if (Glass.on) color(rimRes) else null))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(8) }
        }
    }

    fun listParams(top: Int = 2, bottom: Int = 2) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    companion object {
        /** A value's widest in a usual window, and in a wide one ([valueMaxDp]). */
        const val VALUE_MAX_DP = 118
        const val WIDE_VALUE_MAX_DP = 200

        /** How faint a row is drawn while it can't be used (not available, or busy). */
        const val DIM = 0.4f

        /** A row's value on the right: drawn this much fainter than its label. */
        const val VALUE_ALPHA = 0.85f

        /** A two-line row's second line (a copy's or a source's facts): its size, how faint, and its gap under the label. */
        const val NOTE_SP = 10.5f
        const val NOTE_ALPHA = 0.72f
        const val NOTE_GAP_DP = 2

        /** The gap between a two-line row's words and its value on the right. */
        const val VALUE_GAP_DP = 10

        /** Draws [row]'s words, icons and switch faint: its slice of the card stays as it is, so the card stays whole. */
        fun dim(row: ViewGroup) {
            for (i in 0 until row.childCount) row.getChildAt(i).alpha = DIM
        }

        /**
         * The colour of an "[n] of [total]" badge, the same everywhere: blue when all are, gray when none are, the
         * lighter blue in between, soft red for a [problem].
         */
        fun countBadge(n: Int, total: Int, problem: Boolean = false): Badge = when {
            problem -> Badge.PROBLEM
            total > 0 && n >= total -> Badge.ACTIVE
            n <= 0 -> Badge.INACTIVE
            else -> Badge.LIGHT
        }

        /**
         * Up / down move between the focusable rows of [list] and wrap around at either end.
         * Returns true when the key was handled.
         */
        fun verticalNav(list: ViewGroup, row: View, keyCode: Int, event: KeyEvent): Boolean {
            if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false
            if (event.action != KeyEvent.ACTION_DOWN) return true
            // A row paired with a small button counts once, as its row.
            val rows = (0 until list.childCount).map { list.getChildAt(it) }
                .mapNotNull { if (it.tag == ROW_PAIR) (it as? ViewGroup)?.getChildAt(0) else it }
                .filter { it.isFocusable && it.isShown }
            val index = rows.indexOf(row)
            if (index < 0 || rows.size < 2) return true
            val down = keyCode == KeyEvent.KEYCODE_DPAD_DOWN
            // Holding the key stops at the ends instead of spinning round and round.
            if (event.repeatCount > 0 && ((down && index == rows.size - 1) || (!down && index == 0))) return true
            val next = rows[(index + if (down) 1 else -1).mod(rows.size)]
            next.requestFocus()
            return true
        }
    }
}
