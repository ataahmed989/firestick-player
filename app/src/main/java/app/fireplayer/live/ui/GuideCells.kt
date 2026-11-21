package app.fireplayer.live.ui

import app.fireplayer.live.Programme
import kotlin.math.abs
import kotlin.math.floor

/**
 * One cell of a guide row: a programme, or a gap with no listing ("No information"). A row's cells cover its whole
 * loaded time range with no holes and no overlaps, so the D-pad always has somewhere to go.
 */
class Cell(val start: Long, val stop: Long, val programme: Programme?) {
    /**
     * The title drawn last time, ellipsized for [cachedWidth] pixels in the semibold face when [cachedBold] (drawing
     * reuses it while both hold, so a frame allocates nothing).
     */
    var cachedText: CharSequence? = null
    var cachedWidth = -1
    var cachedBold = false

    fun contains(t: Long) = t in start until stop
    override fun toString() = "Cell($start-$stop ${programme?.title ?: "-"})"
}

/** Pure guide arithmetic, unit-tested: cells of a row, focus movement, and the time window. */
object GuideCells {
    const val SLOT_MS = 30 * 60 * 1000L

    /** The start of the 30-minute slot holding [t] (the guide's left edge at "now"). */
    fun slotStart(t: Long): Long = t - Math.floorMod(t, SLOT_MS)

    /**
     * The cells for one row over [from, to): its programmes (sorted, clipped of overlaps: a programme that starts
     * before the previous one ends is shortened to start where it ends), with gap cells wherever nothing is listed.
     */
    fun cells(programmes: List<Programme>, from: Long, to: Long): List<Cell> {
        if (to <= from) return emptyList()
        val out = ArrayList<Cell>()
        var t = from
        for (p in programmes.sortedBy { it.start }) {
            if (p.stop <= t) continue
            if (p.start >= to) break
            val s = maxOf(p.start, t)
            if (s > t) out += Cell(t, s, null)
            val e = minOf(p.stop, to)
            if (e > s) {
                // The cell tiles the row from [s]; the programme keeps its true times for display.
                out += Cell(s, e, p)
                t = e
            }
        }
        if (t < to) out += Cell(t, to, null)
        return out
    }

    /** The cell holding [t], else the nearest one. */
    fun at(cells: List<Cell>, t: Long): Int {
        if (cells.isEmpty()) return -1
        val i = cells.indexOfFirst { it.contains(t) }
        if (i >= 0) return i
        return if (t < cells.first().start) 0 else cells.size - 1
    }

    /**
     * Where the focus time goes when a cell gets focus: "now" for the programme on air (so moving up and down keeps
     * to what's on now), else the cell's start.
     */
    fun cursorFor(cell: Cell, now: Long): Long = if (cell.contains(now)) now else cell.start

    /**
     * The left edge of the visible time window that keeps [cell] in view: the window moves in 30-minute steps, never
     * earlier than [earliest] (the slot holding now), and only as far as needed.
     */
    fun windowFor(cell: Cell, windowStart: Long, windowMs: Long, earliest: Long): Long {
        var w = windowStart
        // Right: the cell's start must be inside the window with at least a slot of it showing.
        while (cell.start >= w + windowMs - SLOT_MS / 2) w += SLOT_MS
        // Left: back until the cell's start (or now) is showing.
        while (w > earliest && maxOf(cell.start, earliest) < w) w -= SLOT_MS
        return maxOf(w, earliest)
    }

    /** The x (pixels) of time [t] in a grid whose time window starts at [windowStart] on [gridLeft]. */
    fun xOf(t: Long, windowStart: Long, gridLeft: Float, pxPerMin: Float): Float = gridLeft + (t - windowStart) / 60_000f * pxPerMin

    /**
     * The left edge of a cell starting at [start] as drawn: clipped to the grid's left edge, then inset by half the
     * gap between cells. The focus highlight uses the same edges, so at rest it covers its cell exactly.
     */
    fun cellLeft(start: Long, windowStart: Long, gridLeft: Float, pxPerMin: Float, gap: Float): Float =
        maxOf(xOf(start, windowStart, gridLeft, pxPerMin), gridLeft) + gap / 2

    /** The right edge of a cell ending at [stop] as drawn: clipped to [right], inset by half the gap. */
    fun cellRight(stop: Long, windowStart: Long, gridLeft: Float, right: Float, pxPerMin: Float, gap: Float): Float =
        minOf(xOf(stop, windowStart, gridLeft, pxPerMin), right) - gap / 2

    // ---- Rows -----------------------------------------------------------------------------------------------------

    /**
     * The whole channel rows that fit under the time header in a guide [height] tall: what focus and scrolling count
     * with. Laid out by [rowHeight] and [headerHeight], they fill it exactly.
     */
    fun wholeRows(height: Float, headerH: Float, rowH: Float): Int = ((height - headerH) / rowH).toInt().coerceAtLeast(1)

    /**
     * The height of each of [rows] channel rows filling a guide [height] pixels tall under a time header at least
     * [minHeader] tall: the space under the header divided by [rows], in whole pixels (crisp hairlines and text), one
     * at least.
     */
    fun rowHeight(height: Float, minHeader: Float, rows: Int): Float =
        floor((height - minHeader) / rows.coerceAtLeast(1)).coerceAtLeast(1f)

    /**
     * The time header over [rows] rows [rowH] tall in a guide [height] tall: [minHeader] and the few pixels the whole rows
     * leave, so the last row ends on the guide's bottom edge.
     */
    fun headerHeight(height: Float, minHeader: Float, rows: Int, rowH: Float): Float =
        maxOf(minHeader, height - rows.coerceAtLeast(1) * rowH)

    /**
     * The first row shown with [row] focused: [top] (the first shown now) while the focus is at least a row inside the
     * [visible] whole rows' edges, else moved just enough, never past either end of the [count] rows. The focused row
     * is always one of the whole rows.
     */
    fun topFor(row: Int, top: Int, visible: Int, count: Int): Int {
        // One row of room each side shows where the focus is heading; too few rows for that: none.
        val room = if (visible >= 3) 1 else 0
        val maxTop = (count - visible).coerceAtLeast(0)
        return when {
            row < top + room -> row - room
            row > top + visible - 1 - room -> row - visible + 1 + room
            else -> top
        }.coerceIn(0, maxTop)
    }

    /**
     * The first row shown once a list's first screenful has become the whole list: the focused row, [oldFocus] in the
     * part and [newFocus] in the whole, stays on the row of the screen it was on ([oldTop] was the part's first row
     * shown), so nothing moves on screen as the rest arrives. Never past either end of the [count] rows.
     */
    fun topKeeping(oldTop: Int, oldFocus: Int, newFocus: Int, visible: Int, count: Int): Int =
        (newFocus - (oldFocus - oldTop)).coerceIn(0, (count - visible).coerceAtLeast(0))

    /** Whether a step from row [from] to row [to] ([down] or up) went round the list's end rather than to the row beside. */
    /**
     * The room a channel's name has in a channel column [column] wide: from [nameLeft] to [end] short of the column's
     * edge, less [marker] (the ▶ after the playing channel's name). A name wider than this is cut with an ellipsis, and
     * scrolls while its row has the focused programme.
     */
    fun nameRoom(column: Float, nameLeft: Float, end: Float, marker: Float): Float = (column - end - nameLeft - marker).coerceAtLeast(0f)

    /** [color] at [level] (0 to 1) of its own strength: the focus highlight fading in and out. */
    fun faded(color: Int, level: Float): Int {
        val a = ((color ushr 24) * level.coerceIn(0f, 1f) + 0.5f).toInt()
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    fun wentRound(from: Int, to: Int, down: Boolean): Boolean = if (down) to < from else to > from

    /**
     * Where the rows scroll from to reach [to] (pixels) from [from]: from where they are, unless that's further than
     * [longest] (round from the last channel to the first in a long list); then from [glide] short of [to]. The rows
     * jump there and glide the rest, rather than every row between flicking past (and asking for its logo).
     */
    fun scrollStart(from: Float, to: Float, longest: Float, glide: Float): Float = when {
        abs(to - from) <= longest -> from
        to < from -> to + glide
        else -> to - glide
    }
}

/**
 * The guide's white focus highlight gliding from one cell to the next (pure arithmetic, unit-tested). Rects are in the
 * grid's content coordinates (x as at rest in the current time window, y from the first row), so both ends move with
 * the rows while they scroll. The end is the focused cell as it is now, passed in every frame ([update]): listings
 * arriving or the guide's width changing mid-glide still land the highlight on its cell.
 *
 * A new glide starts from wherever the highlight is drawn ([begin] with the last [update]), so presses in quick
 * succession chain smoothly instead of jumping back to a cell.
 */
class FocusGlide {
    private var fromLeft = 0f
    private var fromTop = 0f
    private var fromRight = 0f
    private var fromBottom = 0f
    private var hasFrom = false

    /** Eased progress: 0 where the glide began, 1 on the focused cell. */
    var fraction = 1f

    /** The highlight's rect, from the last [update]. */
    var left = 0f
        private set
    var top = 0f
        private set
    var right = 0f
        private set
    var bottom = 0f
        private set

    /** On its way between two cells (both ends drawn differently from the focused cell alone). */
    val moving get() = hasFrom && fraction < 1f

    /** Starts a glide from this rect; the animation then runs [fraction] from 0 to 1. */
    fun begin(l: Float, t: Float, r: Float, b: Float) {
        fromLeft = l; fromTop = t; fromRight = r; fromBottom = b
        hasFrom = true
        fraction = 0f
    }

    /** No glide: the highlight sits on the focused cell. */
    fun clear() {
        hasFrom = false
        fraction = 1f
    }

    /** The time window moved by [dx] pixels of content: the start moves with what it was drawn over. */
    fun shiftX(dx: Float) {
        fromLeft += dx
        fromRight += dx
    }

    /** The rows jumped by [dy] pixels of content (a long move): the start moves with them, so it stays put on screen. */
    fun shiftY(dy: Float) {
        fromTop += dy
        fromBottom += dy
    }

    /** Works out the highlight for this frame, gliding towards the focused cell's rect. */
    fun update(toLeft: Float, toTop: Float, toRight: Float, toBottom: Float) {
        if (!moving) {
            left = toLeft; top = toTop; right = toRight; bottom = toBottom
            return
        }
        val f = fraction.coerceIn(0f, 1f)
        left = fromLeft + (toLeft - fromLeft) * f
        top = fromTop + (toTop - fromTop) * f
        right = fromRight + (toRight - fromRight) * f
        bottom = fromBottom + (toBottom - fromBottom) * f
    }
}
