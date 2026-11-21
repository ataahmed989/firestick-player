package app.fireplayer.vod.ui

import kotlin.math.abs
import kotlin.math.floor

/**
 * The maths of Films & Shows' rows and pages, without Android types (unit-tested). The row motion is the Episodes
 * sheet's (ui/EpisodesSheet.applyMotion): a row's place is a card number [f] (between two while it glides) and the
 * chosen card's growth [g] (0..1); every card's scale and shift and the strip's offset follow from those two.
 */
internal object RowMath {

    /**
     * How far the strip is moved left so card [f] sits at the anchor: between two cards' lefts while it glides.
     * [left] gives item i's left edge in the strip (known without a layout pass); [n] items.
     */
    fun offset(f: Float, n: Int, left: (Int) -> Float): Float {
        if (n <= 0) return 0f
        val c = f.coerceIn(0f, (n - 1).toFloat())
        val i0 = floor(c).toInt()
        val frac = c - i0
        val l0 = left(i0)
        val l1 = left(minOf(i0 + 1, n - 1))
        return l0 + (l1 - l0) * frac
    }

    /** How much of the growth item [i] has: all of it at f = i, none a card away. */
    fun share(i: Int, f: Float, g: Float): Float = (1f - abs(i - f)).coerceIn(0f, 1f) * g.coerceIn(0f, 1f)

    /** How far item [i] moves along to make room for the grown card before it ([extra] is that card's growth). */
    fun follower(i: Int, f: Float, g: Float, extra: Float): Float = extra * g.coerceIn(0f, 1f) * (i - f).coerceIn(0f, 1f)

    /**
     * The cards of a row that hold pictures, with [at] chosen: the sliver before it, it, the ones that fit on screen
     * after it and one ahead ([before] and [after]), within the row's [n] cards. Empty for an empty row.
     */
    fun boundCards(at: Int, n: Int, before: Int = 1, after: Int = 8): IntRange {
        if (n <= 0) return IntRange.EMPTY
        val a = at.coerceIn(0, n - 1)
        return (a - before).coerceAtLeast(0)..(a + after).coerceAtMost(n - 1)
    }

    /**
     * Where the page's column goes (its upward offset) with row [focused] chosen: 0 (the header shows) on the first
     * row shown, else that row's top, so it sits just under the name line. [rowTops] are the rows' tops in the column,
     * [firstShown] the first row shown. A first row too tall to show whole under the header (its title and grown card
     * need [need] of the viewport's [viewportH]) moves up just far enough that it does, never past its own top.
     * The column never goes past where its [end] (the last row's bottom, with the room under it) meets the screen's
     * bottom: the last row sits at the bottom with the row above it still showing, not alone at the top.
     */
    fun pageTarget(
        focused: Int, firstShown: Int, need: Float = 0f, viewportH: Float = Float.MAX_VALUE, end: Float = Float.MAX_VALUE,
        rowTops: (Int) -> Float,
    ): Float {
        if (focused > firstShown) return minOf(rowTops(focused), (end - viewportH).coerceAtLeast(0f))
        val top = rowTops(focused)
        return (top + need - viewportH).coerceIn(0f, top.coerceAtLeast(0f))
    }

    /**
     * Rows whose pictures are kept with the column at [offset] (where it is heading): those that show in the viewport
     * [viewportH] tall, and the one above the chosen [focused] row. [tops] and [heights] of the rows shown, in order.
     */
    fun boundRows(focused: Int, offset: Float, viewportH: Float, tops: FloatArray, heights: FloatArray): Set<Int> {
        val out = HashSet<Int>()
        for (i in tops.indices) {
            val top = tops[i] - offset
            val bottom = top + heights[i]
            if (bottom > 0f && top < viewportH) out += i
        }
        if (focused >= 0 && focused < tops.size) {
            out += focused
            if (focused > 0) out += focused - 1
        }
        return out
    }

    /**
     * The rank number's text size: [size] unless the number, [measured] wide at that size, is wider than [room]; then
     * shrunk to fit ("10"), as Glass Home's ranked shelf does.
     */
    fun rankTextSize(size: Float, measured: Float, room: Float): Float =
        if (measured > room && measured > 0f) size * room / measured else size

    /** The rank number's baseline: just above the poster's bottom. */
    fun rankBaseline(posterTop: Float, posterH: Float): Float = posterTop + posterH - VodSizes.RANK_BASELINE * posterH

    // ---- The See all grid -----------------------------------------------------------------------------------------

    /** Grid row of item [i] with [columns] across. */
    fun gridRow(i: Int, columns: Int): Int = i / columns

    fun gridColumn(i: Int, columns: Int): Int = i % columns

    fun gridRows(n: Int, columns: Int): Int = if (n <= 0) 0 else (n + columns - 1) / columns

    /** Down from item [i] of [n]: the one under it, or the last item when the row below is shorter; -1 at the bottom. */
    fun gridDown(i: Int, n: Int, columns: Int): Int {
        val below = i + columns
        if (below < n) return below
        return if (gridRow(n - 1, columns) > gridRow(i, columns)) n - 1 else -1
    }

    /** Up from item [i]: the one above it, or -1 on the first grid row. */
    fun gridUp(i: Int, columns: Int): Int = if (i - columns >= 0) i - columns else -1

    /** Whether the grid should ask for its next page: the chosen row is within [threshold] rows of the end. */
    fun needMore(focusedRow: Int, rows: Int, threshold: Int = 3): Boolean = rows - 1 - focusedRow < threshold

    /** The grid's upward offset with grid row [row] chosen: rows [pitch] apart, the first row at the top. */
    fun gridTarget(row: Int, pitch: Float): Float = if (row <= 0) 0f else row * pitch

    /** Grid rows whose pictures are kept with row [row] chosen. */
    fun gridBoundRows(row: Int, rows: Int, before: Int = 1, after: Int = 3): IntRange {
        if (rows <= 0) return IntRange.EMPTY
        val r = row.coerceIn(0, rows - 1)
        return (r - before).coerceAtLeast(0)..(r + after).coerceAtMost(rows - 1)
    }
}
