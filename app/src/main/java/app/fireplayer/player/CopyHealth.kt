package app.fireplayer.player

/**
 * How a copy has played, over every time it was played: how long, how often it stopped to load (not counting the
 * waits after a jump), how long those waits were, how often it failed to play at all, and how many damaged stretches it
 * skipped. A few counters, kept beside what was measured of its picture ([MediaSpec]).
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

    /** It failed, and hasn't played well since; or it keeps stopping to load (6 times an hour, or 2% of the time). */
    val troubled: Boolean get() = (failures > 0 && watchedMs < FAILURE_FORGIVEN_MS) ||
        (watchedMs >= JUDGED_AFTER_MS && (stalls * HOUR_MS / watchedMs >= STALLS_AN_HOUR || stallMs * 50 >= watchedMs))

    /** "failed to play", "buffered 7× in 20 min", "smooth over 40 min", or null when too little is known. */
    fun summary(): String? {
        val min = watchedMs / 60_000
        return when {
            failures > 0 && watchedMs < FAILURE_FORGIVEN_MS -> if (failures == 1) "failed to play" else "failed to play $failures times"
            troubled -> "buffered ${stalls}× in $min min"
            skips > 0 -> "skips damaged parts"
            watchedMs >= SMOOTH_AFTER_MS && stalls == 0 -> "smooth over $min min"
            else -> null
        }
    }

    companion object {
        private const val HOUR_MS = 3_600_000L
        private const val STALLS_AN_HOUR = 6L
        /** Stalls are only judged once this much was watched: one wait in the first minute says little. */
        const val JUDGED_AFTER_MS = 5 * 60_000L
        /** A copy that failed once and then played this long is taken to work. */
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
