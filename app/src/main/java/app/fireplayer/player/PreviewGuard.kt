package app.fireplayer.player

/**
 * Keeps the skip preview's load off the film's source. The preview is a second player: a second connection to the
 * same server, a second decoder, and megabytes taken from the line the film is loading on. While a round of skipping
 * has it running, and for [AFTER_MS] after, a wait to load or a refused connection says nothing about the source
 * ([covers]): it isn't counted toward a switch. And when the film itself suffers while the preview runs (it stops to
 * load, [stalled]; its picture drops frames, [framesDropped]; what it has loaded runs low, [bufferFell]), the preview
 * is what goes, for the rest of that film.
 *
 * Times are elapsedRealtime ms. Pure; one thread; makes nothing after it's built.
 */
class PreviewGuard {

    /** A round of skipping has the preview running. */
    var active = false
        private set
    private var endedAt = NEVER
    private var startAheadMs = 0L
    private var dropped = 0L

    /** Skipping began with the preview on, with [aheadMs] of the film loaded ahead. More presses change nothing. */
    fun started(aheadMs: Long) {
        if (active) return
        active = true
        startAheadMs = aheadMs
        dropped = 0L
    }

    /** The round ended at [now]: the film jumped, skipping was called off, or the preview stopped. */
    fun ended(now: Long) {
        if (!active) return
        active = false
        endedAt = now
    }

    /** A new film, or a new player for this one. */
    fun reset() {
        active = false
        endedAt = NEVER
        startAheadMs = 0L
        dropped = 0L
    }

    /** Whether what the film does at [now] may be the preview's doing, so isn't held against the source. */
    fun covers(now: Long): Boolean = active || now - endedAt < AFTER_MS

    /**
     * The film stopped to load at [now] while it was playing; its last jump was at [lastJumpAt]. True when that's the
     * preview's doing, so the preview goes: it was running (and the film hadn't just jumped), or it had just ended and
     * the film hasn't jumped since (a wait after the jump is the jump's).
     */
    fun stalled(now: Long, lastJumpAt: Long): Boolean =
        if (active) now - lastJumpAt > JUMP_MS else covers(now) && lastJumpAt < endedAt

    /**
     * The film's decoder lost [count] more frames (too late, or never decoded). True once it has lost [DROPPED_LIMIT] in
     * one round with the preview running.
     */
    fun framesDropped(count: Long): Boolean {
        if (!active || count <= 0L) return false
        dropped += count
        return dropped >= DROPPED_LIMIT
    }

    /**
     * The film has [aheadMs] loaded ahead while it plays. True when the round began with a healthy [HEALTHY_MS] or more
     * and it's now under [LOW_MS]: the film isn't being fed, and is about to stop.
     */
    fun bufferFell(aheadMs: Long): Boolean = active && startAheadMs >= HEALTHY_MS && aheadMs < LOW_MS

    companion object {
        /** Preview while skipping starts Off. */
        const val DEFAULT_ON = false
        /** How long after the preview a wait or a refusal is still put down to it. */
        const val AFTER_MS = 8_000L
        /** A wait this soon after the film's own jump is the jump's. */
        const val JUMP_MS = 3_000L
        /** About half a second of film lost in one round of skipping. */
        const val DROPPED_LIMIT = 12L
        const val HEALTHY_MS = 4_000L
        const val LOW_MS = 1_500L
        private const val NEVER = Long.MIN_VALUE / 2

        /**
         * Whether the setting is turned Off this once: it was left On ([stored]; null: never chosen) when On was the
         * default, and this hasn't been done before ([done]). After that it's the viewer's to turn back on.
         */
        fun offOnce(stored: Boolean?, done: Boolean): Boolean = !done && stored == true
    }
}
