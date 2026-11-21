package app.fireplayer.vod.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import app.fireplayer.shell.Tokens
import app.fireplayer.vod.RowStyle
import kotlin.math.roundToInt

/**
 * A row's items at fixed places, laid out once (and again only when the row's length changes): item i at i x the
 * row's pitch, or, for chips (genres, a show's seasons), one after another at their own widths. It's wider than the
 * screen and never scrolls itself (no scrolling view moves it when an item takes focus): the row's
 * [app.fireplayer.ui.RowMotion] moves it with translationX alone. It doesn't clip, so the white ring round the chosen
 * card shows. Each item grows from its left edge about the middle of its picture ([VodSizes.pivotY]).
 */
@SuppressLint("ViewConstructor")
class CardStrip(
    context: Context,
    private val t: Tokens,
    private val style: RowStyle,
    /** The browse pages' larger cards ([VodSizes]). */
    private val large: Boolean = false,
) : ViewGroup(context) {

    private val pitch = t.dpf(VodSizes.pitch(style, large))
    private val itemW = t.dp(VodSizes.itemW(style, large))
    private val cardH = t.dp(VodSizes.cardH(style, large))
    private val cardTop = t.dp(VodSizes.cardTop(style, large))
    private val pivotY = t.dpf(VodSizes.pivotY(style, large))
    /** A row whose cards stay in place grows its chosen card about its middle; the others from the left edge. */
    private val pivotX = if (VodSizes.still(style)) itemW / 2f else 0f
    private val chipGap = t.dp(VodSizes.CHIP_GAP)
    private val chips = VodSizes.isChips(style)

    init {
        clipChildren = false
        clipToPadding = false
    }

    /** Item [i]'s left edge, px: known without a layout pass (chips: once laid out). */
    fun itemLeft(i: Int): Float {
        if (chips) return getChildAt(i)?.left?.toFloat() ?: 0f
        return (i * pitch).roundToInt().toFloat()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = t.dp(VodSizes.stripH(style, large))
        var w = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (chips) {
                c.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(cardH, MeasureSpec.EXACTLY))
                w += c.measuredWidth + if (i > 0) chipGap else 0
            } else {
                c.measure(MeasureSpec.makeMeasureSpec(itemW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cardH, MeasureSpec.EXACTLY))
                w = itemLeft(i).toInt() + itemW
            }
        }
        // Wider than the screen: the parent's limit doesn't apply.
        setMeasuredDimension(w + t.dp(VodSizes.extra(style, large)), h)
    }

    override fun onLayout(changed: Boolean, l: Int, top: Int, r: Int, b: Int) {
        var x = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val left = if (chips) x else itemLeft(i).toInt()
            c.layout(left, cardTop, left + c.measuredWidth, cardTop + c.measuredHeight)
            x = left + c.measuredWidth + chipGap
            // Grows from its left edge, about the middle of its picture (the Episodes sheet's pivot).
            if (c !is RankedItem) {
                c.pivotX = pivotX
                c.pivotY = pivotY
            }
        }
    }

    /** The strip's width once laid out, px: how far its chips reach (a row of season pills moves only past its view). */
    val contentWidth: Int get() = if (childCount == 0) 0 else getChildAt(childCount - 1).right

    // Nothing above this strip scrolls to show a focused item: the row's motion places it.
    override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean): Boolean = true

    override fun shouldDelayChildPressedState(): Boolean = false
}
