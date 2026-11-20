package app.fireplayer.player

/**
 * What a film standing still gives back to the TV, and when. Paused, it holds everything a playing film does (its
 * buffer, the decoder's frames, the second request's segments, pictures and the drawing cache) for as long as the
 * screen stays up; nothing of that is needed until Play.
 *
 * - [Step.LIGHT] after [lightMs]: what costs nothing to bring back (decoded pictures, the skip preview, the drawing
 *   cache's unused pictures, the second request's segments). The film's buffer stays: Play goes on at once.
 * - [Step.STALE] [staleMs] after that: the drawing cache once more, where Android only lets go of what went unused
 *   for half a minute ([GraphicsTrim.staleOnly]).
 * - [Step.PLAYER] after [playerMs]: the player itself, its place kept; the next key press builds it again there.
 *
 * Nothing goes while a window is open over the film ([due]'s busy), and the clock starts again with every pause.
 * Times are elapsedRealtime. One thread (the player screen's).
 */
class PausedRoom(
    private val lightMs: Long = LIGHT_MS,
    private val staleMs: Long = GraphicsTrim.STALE_MS + 1_000L,
    private val playerMs: Long = PLAYER_MS,
) {
    enum class Step { LIGHT, STALE, PLAYER }

    // When the film came to stand still, or -1 while it plays (or its player is gone).
    private var since = -1L
    // When the light step was taken, or -1.
    private var lightAt = -1L
    private var staleDone = false

    /** Whether a film is standing still. */
    val still: Boolean get() = since >= 0L

    /** The film stands still (paused by the viewer) at [nowMs]; told again while it does, nothing changes. */
    fun still(nowMs: Long) {
        if (since >= 0L) return
        since = nowMs
        lightAt = -1L
        staleDone = false
    }

    /** It plays again, or there is no player any more. */
    fun moving() {
        since = -1L
        lightAt = -1L
        staleDone = false
    }

    /**
     * The step to take at [nowMs], counted as taken; null when none is due. [busy]: a window is open over the film (a
     * panel, the Episodes sheet, a question), and nothing goes meanwhile. After [Step.PLAYER] nothing stands still.
     */
    fun due(nowMs: Long, busy: Boolean): Step? {
        if (since < 0L || busy) return null
        val stood = nowMs - since
        if (stood >= playerMs) {
            moving()
            return Step.PLAYER
        }
        if (lightAt < 0L) {
            if (stood < lightMs) return null
            lightAt = nowMs
            return Step.LIGHT
        }
        if (!staleDone && nowMs - lightAt >= staleMs) {
            staleDone = true
            return Step.STALE
        }
        return null
    }

    /** How long from [nowMs] until [due] is worth asking again; -1 when no film stands still. */
    fun waitMs(nowMs: Long, busy: Boolean): Long {
        if (since < 0L) return -1L
        if (busy) return BUSY_CHECK_MS
        val next = when {
            lightAt < 0L -> since + lightMs
            !staleDone -> minOf(lightAt + staleMs, since + playerMs)
            else -> since + playerMs
        }
        return (next - nowMs).coerceAtLeast(0L)
    }

    /**
     * Fire OS says memory is critically short: a film standing still has nothing to protect, so its player goes now
     * (true), unless a window is open over it ([busy]) or it plays. Counted as taken.
     */
    fun critical(busy: Boolean): Boolean {
        if (since < 0L || busy) return false
        moving()
        return true
    }

    companion object {
        const val LIGHT_MS = 60_000L
        const val PLAYER_MS = 10 * 60_000L
        /** With a window open over the film, looked at again this often. */
        const val BUSY_CHECK_MS = 15_000L

        /**
         * Whether a paused Live TV channel is let go of after [pausedMs] ([LIVE_MS]): it can't be held past its
         * buffer, so the buffer and the decoder are kept for nothing. Not one that can be wound back ([seekable]: its
         * place may still be inside the provider's window).
         */
        fun liveSuspends(pausedMs: Long, seekable: Boolean): Boolean = !seekable && pausedMs >= LIVE_MS

        const val LIVE_MS = 10 * 60_000L

        /**
         * Whether a trim [level] (ComponentCallbacks2) is Fire OS asking the apps in the background for memory
         * (TRIM_MEMORY_BACKGROUND, MODERATE or COMPLETE): only that brings the shell's background release forward. The
         * screens merely being hidden (UI_HIDDEN) does not.
         */
        fun backgroundAsks(level: Int): Boolean = level >= TRIM_BACKGROUND

        // ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, by value so the rule runs without Android.
        private const val TRIM_BACKGROUND = 40
    }
}
