package app.fireplayer.player

import kotlin.math.abs
import kotlin.math.max

/**
 * When to look at a film's picture shape, and what a look says ([AspectSample]): a few looks in its first minutes of
 * playing ([PLAN_MS]), never more. The decoder's frame gives the shape unless the frame is 16:9 ([needsCopy]), where a
 * wider film may have its black bars inside the frame (a 2.39 or 1.90 film saved as 3840 x 2160): then a tiny copy of
 * the picture is measured ([ratioOf], from [BlackBars.measure]).
 *
 * A copy only counts when it can't be mistaken ([ratioOf]): the picture is lit right up to its four edges (a dark scene,
 * a black opening or a fade says nothing about where the picture ends), the bars at each end match (writing in one, or
 * a dark edge, doesn't), bars are on two sides at most, and there's more picture than bar. A look that says nothing is
 * not tried again: it's one look fewer. Pure: no Android.
 */
class AspectSamples {
    private val taken = ArrayList<AspectSample>(PLAN_MS.size)
    private var next = 0
    private var playedMs = 0L
    private var lastAt = -1L

    /** What the looks so far found, in the order taken. */
    val samples: List<AspectSample> get() = taken

    /** Every look planned was taken. */
    val done: Boolean get() = next >= PLAN_MS.size

    /** Called as the film plays, [now] a clock in ms: whether a look is due, after so much of it [playing]. */
    fun due(now: Long, playing: Boolean): Boolean {
        if (playing && lastAt >= 0) playedMs += (now - lastAt).coerceIn(0L, MAX_STEP_MS)
        lastAt = if (playing) now else -1L
        return !done && playedMs >= PLAN_MS[next]
    }

    /** The look due was taken at [atMs] in the film: [ratio] what it found, null when it could tell nothing. */
    fun took(ratio: Double?, atMs: Long) {
        if (done) return
        next++
        if (ratio != null) taken += AspectSample(ratio, atMs)
    }

    /** Another copy: looked at afresh. */
    fun reset() {
        taken.clear()
        next = 0
        playedMs = 0L
        lastAt = -1L
    }

    companion object {
        /** When to look, in ms of the copy playing: from 20 s in (past a studio's logo), up to 10 minutes. */
        val PLAN_MS = longArrayOf(20_000, 60_000, 120_000, 180_000, 240_000, 360_000, 480_000, 600_000)

        /** Between two calls of [due] more than this is the clock jumping (a pause it wasn't told of), not playing. */
        private const val MAX_STEP_MS = 2_000L

        /** The size of the copy measured: tall enough to tell a 1.85 film's bars (2 %) from a 1.90 one's (3 %). */
        const val COPY_WIDTH = 128
        const val COPY_HEIGHT = 288

        private const val WIDE = 16.0 / 9.0

        /** A decoded frame's shape as shown: [width] x [height] pixels, each [pixelRatio] times as wide as tall. */
        fun frameRatio(width: Int, height: Int, pixelRatio: Float): Double =
            if (width <= 0 || height <= 0) 0.0 else width * (if (pixelRatio > 0f) pixelRatio.toDouble() else 1.0) / height

        /** A 16:9 frame: a film wider than that may be inside it, between black bars, so it's measured. */
        fun needsCopy(frameRatio: Double): Boolean = abs(frameRatio - WIDE) <= 0.02

        /**
         * The picture's shape in [look], a copy of a frame shaped [frameRatio]; null when the copy can't say. The bars
         * are what [BlackBars.measure] found black from each edge in; the copy counts only when the picture next to
         * all four is lit across ([BlackBars.Look.edgedV], [BlackBars.Look.edgedH]), the bars at the two ends of each
         * side are the same within a line or two, the picture reaches the frame's edges one way or the other, and at
         * least half of each side is picture.
         */
        fun ratioOf(look: BlackBars.Look, frameRatio: Double): Double? {
            val h = look.height
            val w = look.width
            if (h <= 0 || w <= 0 || frameRatio <= 0.0) return null
            // Black all over: an opening, a fade.
            if (look.top >= h || look.left >= w) return null
            // Its edges dark: a dark scene's edge can't be told from a bar.
            if (!look.edgedV || !look.edgedH) return null
            if (abs(look.top - look.bottom) > slack(h) || abs(look.left - look.right) > slack(w)) return null
            // Bars on all four sides: more likely a lit shape in a dark scene than a film boxed in both ways.
            if (look.top > slack(h) && look.left > slack(w)) return null
            val shownH = h - look.top - look.bottom
            // A line or two of black at the sides is an encode's ragged edge, not a narrower film.
            val shownW = if (look.left > slack(w)) w - look.left - look.right else w
            if (shownH * 2 < h || shownW * 2 < w) return null
            return frameRatio * (shownW.toDouble() / w) / (shownH.toDouble() / h)
        }

        /** How far the bars at the two ends of a side may differ: a line or two (the line where bar and picture blend). */
        private fun slack(lines: Int): Int = max(2, lines / 100)
    }
}
