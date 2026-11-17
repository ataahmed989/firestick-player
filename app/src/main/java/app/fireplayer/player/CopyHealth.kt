package app.fireplayer.player

/**
 * How a copy has played, over every time it was played: how long, how often it stopped to load (not counting the
 * waits after a jump), how long those waits were, how often it failed to play at all, and how many damaged stretches it
 * skipped. A few counters, kept beside what was measured of its picture ([MediaSpec]).
 *
 * Only a copy that keeps stopping to load counts against it ([troubled]). One that failed to play is said so in the
 * list, but isn't held against it: most failures are the moment's (the provider's screen limit, the network), not the copy's.
 */
data class CopyHealth(
    val watchedMs: Long = 0,
    val stalls: Int = 0,
    val stallMs: Long = 0,
    val failures: Int = 0,
    val skips: Int = 0,
) {
    operator fun plus(o: CopyHealth) = CopyHealth(
        watchedMs + o.watchedMs, stalls + o.stalls, stallMs + o.stallMs, failures + o.failures, skips + o.skips,
    )

    /**
     * It keeps stopping to load: at least [MIN_STALLS] waits, and one a minute of film or more, or a tenth of the time
     * spent waiting. A wait now and then over a long film doesn't count, nor does failing to play.
     */
    val troubled: Boolean get() = stalls >= MIN_STALLS &&
        (stalls * STALL_EVERY_MS >= watchedMs || stallMs * WAITING_SHARE >= watchedMs)

    /** "failed to play", "buffered 7× in 20 min", "smooth over 40 min", or null when too little is known. */
    fun summary(): String? {
        val min = watchedMs / 60_000
        return when {
            troubled -> if (min > 0) "buffered ${stalls}× in $min min" else "buffered ${stalls}× in the first minute"
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

        fun encode(h: CopyHealth): String = "${h.watchedMs}|${h.stalls}|${h.stallMs}|${h.failures}|${h.skips}"

        fun decode(text: String?): CopyHealth? {
            val f = text?.split('|') ?: return null
            if (f.size < 5) return null
            return CopyHealth(
                f[0].toLongOrNull() ?: 0, f[1].toIntOrNull() ?: 0, f[2].toLongOrNull() ?: 0,
                f[3].toIntOrNull() ?: 0, f[4].toIntOrNull() ?: 0,
            )
        }
    }
}
