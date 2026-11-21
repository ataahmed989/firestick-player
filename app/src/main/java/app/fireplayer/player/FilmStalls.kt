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
 * How much of a heavy film to have before its first frame.
 *
 * A film's first seconds arrive slower than the rest: its connections are new, the second request starts a moment
 * after the first, and the provider's server takes a while to reach its speed. Measured on a Fire TV Stick 4K Max
 * with two requests: the first 10 s came at 0.74 of what a 70 Mb/s film plays at, then 80 to 86 Mb/s. Started with
 * one second ahead, the film fell 2.6 s behind in those 10 s and stalled. With [START_US] ahead it has more than it
 * falls behind by. A lighter film arrives several times faster than it plays from the first second and starts as it
 * always did; so does a heavy one after a jump.
 *
 * Pure.
 */
object HeavyStart {
    /** A film is heavy from this average on, in Mb/s. */
    const val HEAVY_MBPS = 50.0
    /** What a heavy film has ahead before its first frame, in µs. */
    const val START_US = 4_000_000L

    /** [bytes] over [durationUs] in Mb/s; null when either isn't known. */
    fun mbps(bytes: Long, durationUs: Long): Double? =
        if (bytes <= 0L || durationUs <= 0L) null else bytes * 8.0 / durationUs

    /**
     * Whether a film is heavy: by [fileMbps], the size its server gave over its own running time, or, where that
     * isn't known, by [sourceMbps], its source's average as known before it started (the size its add-on named over
     * the title's running time; a season in one file names the whole season's). Neither known: not heavy.
     */
    fun heavy(sourceMbps: Double?, fileMbps: Double?): Boolean {
        val known = fileMbps?.takeIf { it > 0.0 } ?: sourceMbps?.takeIf { it > 0.0 } ?: return false
        return known >= HEAVY_MBPS
    }

    /** The least to have before playing: [START_US] before a heavy film's [first] frame, else [usualUs]. */
    fun leastUs(first: Boolean, heavy: Boolean, usualUs: Long): Long =
        if (first && heavy) maxOf(START_US, usualUs) else usualUs
}

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
     */
    fun waitUs(stalls: Int, wantedUs: Long, roomUs: Long): Long {
        if (roomUs <= 0L) return wantedUs
        val share = when {
            stalls <= 1 -> roomUs / 2
            stalls == 2 -> roomUs / 4 * 3
            else -> roomUs
        }
        return minOf(wantedUs, maxOf(FilmMemory.STALL_WAIT_LEAST_US, share, roomUs - FilmMemory.WAIT_HEADROOM_US))
    }
}
