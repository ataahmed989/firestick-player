package app.fireplayer.player

/**
 * How many times this film has stopped to load, for the wait after each ([StallCushion]) and for what's kept behind
 * ([BackBuffer.keepUs]). Stalls count up for as long as they keep coming; [quietMs] without one and they're forgotten
 * (a film that stalled twice in its first minutes and then played an hour starts afresh). Jumps aren't stalls: Media3
 * only calls a wait a rebuffer when the film ran dry while playing. Times are elapsedRealtime ms; one thread.
 */
class StallCount(private val quietMs: Long = QUIET_MS) {
    private var count = 0
    private var lastAt = 0L

    /** A new stall began at [nowMs]: how many that makes. */
    fun stalled(nowMs: Long): Int {
        if (count > 0 && nowMs - lastAt > quietMs) count = 0
        count++
        lastAt = nowMs
        return count
    }

    /** How many stalls count at [nowMs] (0 once [quietMs] have passed since the last). */
    fun count(nowMs: Long): Int = if (count > 0 && nowMs - lastAt > quietMs) 0 else count

    companion object {
        const val QUIET_MS = 10 * 60_000L
    }
}

/**
 * How the film is arriving and stalling, as the film's buffer rules last saw it (FilmLoadControl), for the log line
 * and the supply check: [fillRate] seconds of film arriving per second of loading (null: not measured yet; under 1 it
 * falls behind), [stalls] in this film so far ([StallCount]), and [stallWaitMs], how much film the last stall waited
 * for before playing on (0: none yet).
 */
data class FilmFlow(val fillRate: Float?, val stalls: Int, val stallWaitMs: Long)

/**
 * How much film to wait for after a stall before playing on.
 *
 * A film that arrives about as fast as it plays (a 4K remux at 70 to 90 Mb/s on a line bringing 65 to 80) can't be
 * kept from stalling by any wait: the cushion only sets how long it plays until the next one. Measured on a Fire TV
 * Stick 4K Max, such a film played on after every stall with half of the 5 s that fit in memory, and stalled again 6
 * to 16 s later. So the wait grows with each stall in the same film ([StallCount]): 4 s wanted and half of what fits
 * after the first (as it always was), 10 s and three quarters after the second, 20 s and all that fits from the third
 * on, and never less than the film's own shortfall calls for ([forRate]). Fewer, longer waits instead of one every few
 * seconds. What fits is the memory's limit: a full buffer always plays on, whatever was wanted.
 *
 * Pure.
 */
object StallCushion {
    /** A film falling behind gets enough ahead to then play this long without stopping... */
    const val SMOOTH_US = 60_000_000L
    /** ...but never waits for more than this. */
    const val MAX_CUSHION_US = 30_000_000L

    /** Arriving at less than this share of the film's own rate, a stall waits for all that fits at once ([waitUs]). */
    const val SHORT_RATE = 0.9f

    /** What such a wait leaves unfilled of what fits: a tenth of it, at least a second. */
    fun shortHeadroomUs(roomUs: Long): Long = maxOf(1_000_000L, roomUs / 10)

    /**
     * How much to have before playing so it then plays [SMOOTH_US] without stopping, when [rate] seconds of film
     * arrive each second (the download's speed over the film's own, measured as it loads).
     */
    fun forRate(rate: Float): Long = when {
        rate >= 1.3f -> 0L
        // Only just keeping up: room for the connection's ups and downs.
        rate >= 1f -> 8_000_000L
        // Falling behind by (1 - rate) every second.
        else -> (SMOOTH_US * (1f - rate)).toLong().coerceAtMost(MAX_CUSHION_US)
    }

    /** What the [stalls]th stall in this film asks for, with the film arriving at [rate] (null: not measured yet). */
    fun wantedUs(stalls: Int, rate: Float?): Long {
        val byCount = when {
            stalls <= 1 -> 4_000_000L
            stalls == 2 -> 10_000_000L
            else -> 20_000_000L
        }
        return maxOf(byCount, rate?.let { forRate(it) } ?: 0L)
    }

    /**
     * The wait after the [stalls]th stall: [wantedUs], but no more than fits in this TV's memory ([roomUs] of this film
     * ahead; 0: not known, and the full buffer ends the wait). Of what fits, half after the first stall (the rest fills
     * as it plays, when the line allows), three quarters after the second, all of it from the third: by then the line
     * has shown it doesn't fill the rest. Never less than [FilmMemory.STALL_WAIT_LEAST_US]; a film of which a minute or
     * more fits waits for what it wants, as [FilmMemory.waitUs] has it.
     *
     * A film measured arriving at less than [SHORT_RATE] of its own rate ([rate]) can't be kept from stopping by any
     * wait: arriving at r, it can only play r of the time, and how long it plays after a wait goes with the wait (about
     * room / (1 - r)). So it waits once for all that fits, less [shortHeadroomUs], from the first stall on: half as many
     * stops, each longer, for the same waiting in all.
     */
    fun waitUs(stalls: Int, wantedUs: Long, roomUs: Long, rate: Float? = null): Long {
        if (roomUs <= 0L) return wantedUs
        if (rate != null && rate < SHORT_RATE) {
            return minOf(wantedUs, maxOf(FilmMemory.STALL_WAIT_LEAST_US, roomUs - shortHeadroomUs(roomUs)))
        }
        val share = when {
            stalls <= 1 -> roomUs / 2
            stalls == 2 -> roomUs / 4 * 3
            else -> roomUs
        }
        return minOf(wantedUs, maxOf(FilmMemory.STALL_WAIT_LEAST_US, share, roomUs - FilmMemory.WAIT_HEADROOM_US))
    }
}
