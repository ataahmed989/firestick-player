package app.fireplayer.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
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
class RowLayout(context: Context, private val colors: ColorStateList?) : LinearLayout(context) {

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
                maxWidth = dp(118)
            })
        } else if (value != null) {
            row.addView(text(value).apply { alpha = 0.85f; maxWidth = dp(118) })
        }
        if (trailingIcon != null) row.addView(icon(trailingIcon, 15))
        return row
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
