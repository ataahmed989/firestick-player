package app.fireplayer.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
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
    private var glide: ValueAnimator? = null

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
        glide?.cancel()
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
        glide?.cancel()
        if (!animate || target == scrollY) {
            scrollTo(0, target)
            return
        }
        // Our own glide: ScrollView's smooth scroll jumps instead when presses come quickly.
        glide = ValueAnimator.ofInt(scrollY, target).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { scrollTo(0, it.animatedValue as Int) }
            start()
        }
    }
}

/** Shared look and D-pad behaviour for the rows in the settings windows and pickers. */
class Rows(private val context: Context) {

    val density = context.resources.displayMetrics.density
    private val font600: Typeface? = ResourcesCompat.getFont(context, R.font.figtree_600)
    val textColors = ContextCompat.getColorStateList(context, R.color.sel_text_row)

    fun dp(value: Int) = (value * density).toInt()

    fun base(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        setBackgroundResource(R.drawable.bg_row)
        minimumHeight = dp(33)
        setPadding(dp(12), dp(6), dp(12), dp(6))
    }

    fun text(value: String) = TextView(context).apply {
        text = value
        typeface = font600
        isDuplicateParentStateEnabled = true
        setTextColor(textColors)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    fun icon(res: Int, sizeDp: Int) = ImageView(context).apply {
        setImageResource(res)
        isDuplicateParentStateEnabled = true
        imageTintList = textColors
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { marginStart = dp(8) }
    }

    /**
     * A label on the left, an optional value on the right, and an optional trailing icon.
     * [labelLines] above 1 lets a long label (a subtitle file name) wrap instead of being cut short.
     */
    fun build(label: String, value: String?, trailingIcon: Int?, labelLines: Int = 1): LinearLayout {
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
        if (value != null) row.addView(text(value).apply { alpha = 0.85f; maxWidth = dp(118) })
        if (trailingIcon != null) row.addView(icon(trailingIcon, 15))
        return row
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
            val rows = (0 until list.childCount).map { list.getChildAt(it) }.filter { it.isFocusable && it.isShown }
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
