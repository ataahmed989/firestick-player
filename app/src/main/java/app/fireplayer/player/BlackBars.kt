package app.fireplayer.player

/**
 * Finds black bars that are part of the film's file (a wide film saved as 16:9 with bars above and below, or an old
 * 4:3 show with bars at the sides), from small copies of the picture taken as it plays. Fill screen and Stretch leave
 * them off the screen; without this both would do nothing for such a file, its shape being the screen's already.
 *
 * Careful rather than quick, since a wrong guess crops the film:
 *  - a copy only counts when the picture right next to the bars is lit most of the way across (a logo on a black
 *    screen, or a dark scene, says nothing);
 *  - [AGREE] such copies must give the same bars before they're used;
 *  - the same on both sides: the smaller of the two is used;
 *  - anything lit inside bars in use, seen [DOUBTS] times running, and the film gets no bars for good (a film that
 *    changes shape, or writing in the bars);
 *  - picture reaching the edge [DOUBTS] times running means no bars, and no more looking.
 */
class BlackBars {
    /** One copy of the picture, measured ([measure]). */
    class Look(
        val width: Int,
        val height: Int,
        val top: Int,
        val bottom: Int,
        val left: Int,
        val right: Int,
        val edgedV: Boolean,
        val edgedH: Boolean,
    )

    private class Axis {
        var low = -1
        var agree = 0
        var shown = 0
        var doubt = 0
        var locked = false

        fun reset() {
            low = -1; agree = 0; shown = 0; doubt = 0; locked = false
        }

        /** [a], [b]: black lines at each end out of [size]; [edged]: the picture next to them is lit. True when [shown] changed. */
        fun see(a: Int, b: Int, size: Int, edged: Boolean): Boolean {
            if (locked) return false
            val bar = minOf(a, b)
            val least = maxOf(2, (size * MIN_BAR).toInt())
            if (shown > 0) {
                if (bar >= shown - 1) {
                    doubt = 0
                    return false
                }
                if (++doubt < DOUBTS) return false
                shown = 0
                locked = true
                return true
            }
            if (bar < least) {
                // Picture at the edge: no bars here.
                low = -1
                agree = 0
                if (++doubt >= DOUBTS) locked = true
                return false
            }
            doubt = 0
            if (low >= 0 && bar < low - 1) {
                // Something lit inside what looked like bars.
                low = if (edged && bar <= size * MAX_BAR) bar else -1
                agree = if (low >= 0) 1 else 0
                return false
            }
            if (!edged || bar > size * MAX_BAR) return false
            when {
                low < 0 -> { low = bar; agree = 1 }
                bar <= low + 1 -> { low = minOf(low, bar); agree++ }
                // Wider than before: a dark edge of this scene.
                else -> return false
            }
            if (agree < AGREE) return false
            shown = low
            return true
        }

        val settled get() = locked
    }

    private val rows = Axis()
    private val columns = Axis()
    private var height = 1
    private var width = 1

    /** The part of the film's height that's bar, at the top and again at the bottom. */
    val vertical: Float get() = if (rows.shown > 0) (rows.shown + 0.5f) / height else 0f

    /** The part of the film's width that's bar, at each side. */
    val horizontal: Float get() = if (columns.shown > 0) (columns.shown + 0.5f) / width else 0f

    /** Bars are in use: still looked at now and then, in case the film changes shape. */
    val found: Boolean get() = rows.shown > 0 || columns.shown > 0

    /** Nothing more to learn about this film. */
    val settled: Boolean get() = rows.settled && columns.settled

    /** Another film (or another picture size). */
    fun reset() {
        rows.reset()
        columns.reset()
    }

    /** Takes one look in; true when [vertical] or [horizontal] changed. */
    fun see(look: Look): Boolean {
        // Black all over: nothing to learn.
        if (look.top >= look.height || look.left >= look.width) return false
        height = look.height
        width = look.width
        val v = rows.see(look.top, look.bottom, look.height, look.edgedV)
        val h = columns.see(look.left, look.right, look.width, look.edgedH)
        return v || h
    }

    companion object {
        // Copies agreeing before bars are used, and copies running against them before they're dropped.
        const val AGREE = 5
        const val DOUBTS = 2
        // Bars thinner than this part of the picture aren't worth moving it for; wider than this isn't believed.
        private const val MIN_BAR = 0.02f
        private const val MAX_BAR = 0.3f
        // A dot is lit above this (of 255, its brightest colour); a line is black with no more lit dots than this.
        private const val DARK = 32
        private const val STRAY = 1
        // The picture's first line counts as lit with this much of it lit.
        private const val EDGE_LIT = 0.5f

        /** Measures a copy of the picture: [pixels] as ARGB, [width] x [height], line after line. */
        fun measure(pixels: IntArray, width: Int, height: Int): Look {
            val rowLit = IntArray(height)
            val colLit = IntArray(width)
            for (y in 0 until height) {
                val at = y * width
                var n = 0
                for (x in 0 until width) {
                    val p = pixels[at + x]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    if (r > DARK || g > DARK || b > DARK) {
                        n++
                        colLit[x]++
                    }
                }
                rowLit[y] = n
            }
            fun leading(lit: IntArray): Int {
                var i = 0
                while (i < lit.size && lit[i] <= STRAY) i++
                return i
            }
            fun trailing(lit: IntArray): Int {
                var i = 0
                while (i < lit.size && lit[lit.size - 1 - i] <= STRAY) i++
                return i
            }
            val top = leading(rowLit)
            val bottom = trailing(rowLit)
            val left = leading(colLit)
            val right = trailing(colLit)
            // One line in from the bar, past the line the two blend in.
            fun edged(lit: IntArray, a: Int, b: Int, across: Int): Boolean {
                val first = a + 1
                val last = lit.size - b - 2
                if (first >= last || across <= 0) return false
                val need = across * EDGE_LIT
                return lit[first] >= need && lit[last] >= need
            }
            return Look(
                width, height, top, bottom, left, right,
                edgedV = edged(rowLit, top, bottom, width - left - right),
                edgedH = edged(colLit, left, right, height - top - bottom),
            )
        }
    }
}
