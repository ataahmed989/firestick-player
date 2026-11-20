package app.fireplayer.vod.sources

import app.fireplayer.vod.StallWatch

/**
 * Stage 4's watch (design-brief §6.5): after [SETTLE_MS] of playing, switch on [MAX_STALLS] stalls within [WINDOW_MS],
 * one stall over [LONG_MS], or more than a quarter of [WINDOW_MS] spent buffering. A stall starting within [JUMP_MS] of
 * a seek, a source change or a resume doesn't count. Times are elapsedRealtime ms; main thread.
 */
class Stalls : StallWatch {

    private var playingSince = -1L
    private var lastJump = Long.MIN_VALUE / 2
    private var stallStart = -1L
    private var stallCounts = false
    /** Counted stalls that ended: start and end. */
    private val ended = ArrayList<LongArray>()

    /** Starts afresh (a new source started). */
    fun reset() {
        playingSince = -1L
        lastJump = Long.MIN_VALUE / 2
        stallStart = -1L
        stallCounts = false
        ended.clear()
    }

    override fun playing(now: Long) {
        if (playingSince < 0) playingSince = now
        if (stallStart >= 0) {
            if (stallCounts) ended += longArrayOf(stallStart, now)
            stallStart = -1L
        }
    }

    override fun jumped(now: Long) {
        lastJump = now
        // A wait already under way when the jump came is the jump's.
        if (stallStart >= 0) stallCounts = false
    }

    override fun buffering(now: Long, on: Boolean) {
        if (!on) {
            playing(now)
            return
        }
        if (stallStart >= 0) return
        stallStart = now
        stallCounts = playingSince >= 0 && now - playingSince >= SETTLE_MS && now - lastJump > JUMP_MS
    }

    override fun shouldSwitch(now: Long): Boolean {
        val from = now - WINDOW_MS
        ended.removeAll { it[1] < from }
        val ongoing = if (stallStart >= 0 && stallCounts) now - stallStart else -1L
        val count = ended.size + if (ongoing >= 0) 1 else 0
        if (count >= MAX_STALLS) return true
        if (ongoing > LONG_MS || ended.any { it[1] - it[0] > LONG_MS }) return true
        var waited = ended.sumOf { minOf(it[1], now) - maxOf(it[0], from) }
        if (ongoing >= 0) waited += now - maxOf(stallStart, from)
        return waited * 4 > WINDOW_MS
    }

    companion object {
        const val SETTLE_MS = 30_000L
        const val JUMP_MS = 10_000L
        const val WINDOW_MS = 120_000L
        const val LONG_MS = 20_000L
        const val MAX_STALLS = 3
    }
}
