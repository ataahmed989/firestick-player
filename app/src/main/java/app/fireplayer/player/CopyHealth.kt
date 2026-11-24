package app.fireplayer.player

import java.util.Locale

/**
 * How a copy has played, over every time it was played: how long, how often it stopped to load (not counting the
 * waits after a jump), how long those waits were, how often it failed to play at all, how many damaged stretches it
 * skipped, and how many of its frames were lost against how many were shown. A few counters, kept beside what was
 * measured of its picture ([MediaSpec]).
 *
 * Only a copy that keeps stopping to load, or loses more than one frame in [LOST_SHARE] over [SMOOTH_AFTER_MS] or more
 * of watching, counts against it ([troubled]). One that failed to play is said so in the list, but isn't held against it:
 * most failures are the moment's (the provider's screen limit, the network), not the copy's.
 *
 * [droppedFrames] counts every frame lost (dropped by Media3, shown twice, put on the refresh of the frame before, never
 * decoded: see [FrameTally]); [renderedFrames] the frames sent to the screen.
 *
 * [noPicture] counts the plays in which this Stick showed no picture for the copy ([PictureWatch]): unlike a failure
 * to start, that's the TV's and the copy's, so a copy with any is no longer picked for a play by itself. One play
 * after it with its picture, for [FAILURE_FORGIVEN_MS] or more, clears it ([plus]).
 */
data class CopyHealth(
    val watchedMs: Long = 0,
    val stalls: Int = 0,
    val stallMs: Long = 0,
    val failures: Int = 0,
    val skips: Int = 0,
    val droppedFrames: Long = 0,
    val renderedFrames: Long = 0,
    /** When it last played here (epoch ms), so "Buffered here before" can stop counting; 0 when not known. */
    val at: Long = 0,
    val noPicture: Int = 0,
) {
    /** [o], a later play, on top of these: a copy that showed no picture and then played with it is forgiven. */
    operator fun plus(o: CopyHealth) = CopyHealth(
        watchedMs + o.watchedMs, stalls + o.stalls, stallMs + o.stallMs, failures + o.failures, skips + o.skips,
        droppedFrames + o.droppedFrames, renderedFrames + o.renderedFrames, maxOf(at, o.at),
        if (o.noPicture == 0 && o.renderedFrames > 0 && o.watchedMs >= FAILURE_FORGIVEN_MS) 0 else noPicture + o.noPicture,
    )

    /**
     * It keeps stopping to load: at least [MIN_STALLS] waits, and one a minute of film or more, or a tenth of the time
     * spent waiting. A wait now and then over a long film doesn't count, nor does failing to play.
     */
    private val buffers: Boolean get() = stalls >= MIN_STALLS &&
        (stalls * STALL_EVERY_MS >= watchedMs || stallMs * WAITING_SHARE >= watchedMs)

    /** It loses more than one frame in [LOST_SHARE], over [SMOOTH_AFTER_MS] or more of watching. */
    val choppy: Boolean get() = watchedMs >= SMOOTH_AFTER_MS && droppedFrames * LOST_SHARE > droppedFrames + renderedFrames

    /** It keeps stopping to load, or loses frames ([choppy]). */
    val troubled: Boolean get() = buffers || choppy

    /** The share of its frames lost, in percent; 0 when no frames were counted. */
    val lostPercent: Double get() =
        if (droppedFrames + renderedFrames <= 0) 0.0 else 100.0 * droppedFrames / (droppedFrames + renderedFrames)

    /**
     * "showed no picture here", "failed to play", "buffered 7× in 20 min", "loses 4.2% of frames", "smooth over 40 min",
     * or null when too little is known.
     */
    fun summary(): String? {
        val min = watchedMs / 60_000
        return when {
            noPicture > 0 -> "showed no picture here"
            buffers -> if (min > 0) "buffered ${stalls}× in $min min" else "buffered ${stalls}× in the first minute"
            choppy -> "loses ${percent(lostPercent)} of frames"
            failures > 0 && watchedMs < FAILURE_FORGIVEN_MS -> if (failures == 1) "failed to play" else "failed to play $failures times"
            skips > 0 -> "skips damaged parts"
            watchedMs >= SMOOTH_AFTER_MS && stalls == 0 -> "smooth over $min min"
            else -> null
        }
    }

    companion object {
        /** Fewer waits than this say little, however close together. */
        const val MIN_STALLS = 4
        /** A wait this often (in film played), or oftener, is a copy that keeps buffering. */
        private const val STALL_EVERY_MS = 60_000L
        /** Or one part in this many of the time spent waiting. */
        private const val WAITING_SHARE = 10L
        /** A copy that failed once and then played this long is no longer said to have failed. */
        const val FAILURE_FORGIVEN_MS = 10 * 60_000L
        private const val SMOOTH_AFTER_MS = 10 * 60_000L
        /** More than one frame lost in this many (2%) is a copy that plays choppily. */
        const val LOST_SHARE = 50L

        /** "4.2%" under 10%, "17%" from there. */
        fun percent(p: Double): String =
            if (p < 10) String.format(Locale.US, "%.1f%%", p) else String.format(Locale.US, "%.0f%%", p)

        /**
         * "watched|stalls|stallMs|failures|skips|droppedFrames|renderedFrames", then "|at" when the date is known, and
         * "|at|noPicture" when there is one. The frame counts, the date and [noPicture] came later: a string kept before
         * them has five or seven fields, and still reads (with none counted); a version that knows fewer reads the first
         * of these.
         */
        fun encode(h: CopyHealth): String =
            "${h.watchedMs}|${h.stalls}|${h.stallMs}|${h.failures}|${h.skips}|${h.droppedFrames}|${h.renderedFrames}" +
                when {
                    h.noPicture > 0 -> "|${h.at}|${h.noPicture}"
                    h.at > 0 -> "|${h.at}"
                    else -> ""
                }

        fun decode(text: String?): CopyHealth? {
            val f = text?.split('|') ?: return null
            if (f.size < 5) return null
            return CopyHealth(
                f[0].toLongOrNull() ?: 0, f[1].toIntOrNull() ?: 0, f[2].toLongOrNull() ?: 0,
                f[3].toIntOrNull() ?: 0, f[4].toIntOrNull() ?: 0,
                f.getOrNull(5)?.toLongOrNull() ?: 0, f.getOrNull(6)?.toLongOrNull() ?: 0, f.getOrNull(7)?.toLongOrNull() ?: 0,
                f.getOrNull(8)?.toIntOrNull() ?: 0,
            )
        }
    }
}
