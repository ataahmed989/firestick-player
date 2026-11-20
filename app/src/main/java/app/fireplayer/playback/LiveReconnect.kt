package app.fireplayer.playback

/**
 * When the live player tries a channel again after it failed or was dropped by the provider: after about 1, 2, 4 and
 * 8 seconds, then every 30 seconds for as long as the viewer stays on the channel. A stream that simply ended (the
 * provider closed it) is opened again almost at once the first time: what was loaded has played out by then. The
 * count starts again once the channel has played steadily, so a stream that keeps dying after a second isn't hammered
 * once a second. Pure: times are passed in (any steady clock, ms).
 */
class LiveReconnect(
    private val quickMs: LongArray = QUICK_MS,
    private val slowMs: Long = SLOW_MS,
    private val steadyMs: Long = STEADY_MS,
) {
    /** Tries asked for since the channel last played steadily (or was opened). */
    var attempt = 0
        private set

    /** The quick tries are used up: it's down to one every [slowMs]. */
    val slow: Boolean get() = attempt > quickMs.size

    private var playingSince = NONE

    /** The channel failed (or [ended]) at [now]: how long to wait before trying again. */
    fun failed(now: Long, ended: Boolean = false): Long {
        settle(now)
        val wait = when {
            ended && attempt == 0 -> ENDED_FIRST_MS
            attempt < quickMs.size -> quickMs[attempt]
            else -> slowMs
        }
        attempt++
        return wait
    }

    /** Frames are playing. */
    fun playing(now: Long) {
        if (playingSince == NONE) playingSince = now
    }

    /** Frames stopped (buffering, paused, or about to fail). */
    fun notPlaying(now: Long) = settle(now)

    /** Another channel, or this one opened afresh. */
    fun reset() {
        attempt = 0
        playingSince = NONE
    }

    private fun settle(now: Long) {
        if (playingSince != NONE && now - playingSince >= steadyMs) attempt = 0
        playingSince = NONE
    }

    companion object {
        private const val NONE = Long.MIN_VALUE
        val QUICK_MS = longArrayOf(1_000, 2_000, 4_000, 8_000)
        const val SLOW_MS = 30_000L
        /** Playing this long without a break counts as recovered. */
        const val STEADY_MS = 10_000L
        /** A stream the provider closed is opened again this soon the first time. */
        const val ENDED_FIRST_MS = 300L
    }
}
