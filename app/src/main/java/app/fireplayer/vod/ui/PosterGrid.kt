package app.fireplayer.vod.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import app.fireplayer.shell.Tokens
import kotlin.math.roundToInt

/**
 * See all's posters: 5 across the content's width (154 x 205 dp, 18 dp apart), rows apart by room for the chosen one's title
 * ([VodSizes.GRID_ROW_GAP]), each at a fixed cell. Pages of posters are
 * appended as they come (one layout each); everything else is a translation: the page's Spring moves the grid up and
 * down. All its posters exist (up to See all's cap); only those near the chosen one hold pictures.
 */
@SuppressLint("ViewConstructor")
class PosterGrid(context: Context, private val t: Tokens) : ViewGroup(context) {

    private val cellW = t.dp(VodSizes.GRID_POSTER_W)
    private val cellH = t.dp(VodSizes.GRID_POSTER_H)
    private val pitchX = t.dpf(VodSizes.gridPitchX())
    private val pitchY = t.dpf(VodSizes.gridPitchY())
    private val top = t.dp(VodSizes.GRID_TOP)

    init {
        clipChildren = false
        clipToPadding = false
    }

    val columns: Int get() = VodSizes.GRID_COLUMNS

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val spec = MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY)
        val hSpec = MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(spec, hSpec)
        val rows = RowMath.gridRows(childCount, columns)
        setMeasuredDimension(t.dp(VodSizes.gridW()) + 1, top + (rows * pitchY).roundToInt() + cellH)
    }

    override fun onLayout(changed: Boolean, l: Int, tp: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val x = (RowMath.gridColumn(i, columns) * pitchX).roundToInt()
            val y = top + (RowMath.gridRow(i, columns) * pitchY).roundToInt()
            c.layout(x, y, x + cellW, y + cellH)
            // The chosen poster grows about its middle: the gaps leave room for it.
            c.pivotX = cellW / 2f
            c.pivotY = cellH / 2f
        }
    }

    override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean): Boolean = true

    override fun shouldDelayChildPressedState(): Boolean = false
}
