package app.fireplayer.vod.play

/**
 * The first thing tried when a film from the add-ons stops to load and nothing is arriving: the same source is opened
 * again where the film stands. A download that goes quiet on a connection still open (seen with Real-Debrid's servers)
 * comes back on a new connection, without the file or the timeline changing; left alone it's only noticed when the
 * connection times out, or after 20 s by the switching rule (design-brief.md §6.5, stage 4), which changes the file.
 *
 * So, in order: after [reopenAfterMs] of a stall with nothing new loaded for [quietMs], reopen ([shouldReopen] says so
 * once per stall, at most [maxReopens] times in [windowMs]); a stall that goes on is then left to the switching rules
 * ([app.fireplayer.vod.StallWatch]); errors go by the failure table. A film that is still loading, only slower than it
 * plays, is never reopened: a new connection to the same server wouldn't be faster.
 *
 * Times are elapsedRealtime ms. Pure; main thread.
 */
class StallAid(
    private val reopenAfterMs: Long = REOPEN_AFTER_MS,
    private val quietMs: Long = QUIET_MS,
    private val maxReopens: Int = MAX_REOPENS,
    private val windowMs: Long = WINDOW_MS,
) {
    private var since = -1L
    private var ahead = -1L
    private var grewAt = 0L
    private var reopened = false
    private val reopens = ArrayList<Long>()

    /** The film stopped to load at [now] (true), or plays again (false). */
    fun stalled(now: Long, on: Boolean) {
        if (!on) {
            since = -1L
            return
        }
        if (since >= 0) return
        since = now
        ahead = -1L
        grewAt = now
        reopened = false
    }

    /** A new source, or the viewer jumped: whatever stall there was is another matter. */
    fun reset() {
        since = -1L
    }

    /**
     * Called as the stall goes on, with how much of the film is loaded ahead ([aheadMs]). True, once per stall, when
     * the same source should be opened again now.
     */
    fun shouldReopen(now: Long, aheadMs: Long): Boolean {
        if (since < 0 || reopened) return false
        if (aheadMs > ahead) {
            // Something came: the connection is alive.
            if (ahead >= 0) grewAt = now
            ahead = aheadMs
        }
        if (now - since < reopenAfterMs || now - grewAt < quietMs) return false
        reopens.removeAll { now - it > windowMs }
        if (reopens.size >= maxReopens) return false
        reopened = true
        reopens += now
        return true
    }

    companion object {
        /** A stall this long with nothing arriving gets the source opened again. */
        const val REOPEN_AFTER_MS = 8_000L
        /** "Nothing arriving": the loaded part hasn't grown for this long. */
        const val QUIET_MS = 4_000L
        /** At most this many reopens in [WINDOW_MS]: a source that keeps going quiet is the switching rules'. */
        const val MAX_REOPENS = 2
        const val WINDOW_MS = 10 * 60_000L
    }
}
