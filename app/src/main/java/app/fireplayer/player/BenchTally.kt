package app.fireplayer.player

import java.util.Locale

/**
 * One player's figures for comparing one request with two on the TV, written to the log under "FirePlayerBench" every
 * minute while the film plays and once as its player goes ([PlaybackDiag]):
 *
 * "bench mode=2 time=312s flat=104.3Mb/s/187s got=2100.0MB r1=55.1Mb/s r2=53.9Mb/s wait=3% reopen=41ms/212
 *  minAhead=38.2s stalled=0.0s/0 dropped=3 twice=0 same=1 never=0 shown=7480 pssPeak=512.3MB"
 *
 * - mode: requests at a time, and why one when two were asked for ([TwoRanges.mode]).
 * - flat: what the film's loading brought while loading flat out, both requests together, over that many seconds
 *   ([LoaderRate]); got: all that came. r1, r2, wait, reopen: each request's own ([TwoRanges.rates]).
 * - minAhead: the least that was loaded ahead of the picture, read once a second from [SETTLE_MS] after the first
 *   frame and after each jump (just after either there is next to nothing loaded, whatever the line).
 * - stalled: seconds the film stood still to load, and how often (not the wait right after a jump).
 * - dropped (Media3's late frames), twice, same, never (the pacer's and the decoder's, [FrameCounts]), shown.
 * - pssPeak: the most memory the app held.
 *
 * Everything since [reset]. Only counts, times and sizes: nothing of the film or its link. All on the main thread but
 * [pss], which may come from any.
 */
class BenchTally {
    private var startedAt = -1L
    private var settledFrom = -1L
    private var minAheadMs = -1L
    private var stallSince = -1L
    private var stalledMs = 0L
    private var stalls = 0
    private val frames = LongArray(5)
    private var countedBytes = 0L
    private var countedNs = 0L
    private var allBytes = 0L
    @Volatile private var peakPssKb = 0L

    /** A new player at [nowMs]. */
    fun reset(nowMs: Long) {
        startedAt = nowMs
        settledFrom = -1L
        minAheadMs = -1L
        stallSince = -1L
        stalledMs = 0L
        stalls = 0
        frames.fill(0L)
        countedBytes = 0L
        countedNs = 0L
        allBytes = 0L
        peakPssKb = 0L
    }

    /** Whether a player's figures are being kept. */
    val running: Boolean get() = startedAt >= 0L

    /** The player went: nothing is kept until the next [reset]. */
    fun stop() {
        startedAt = -1L
    }

    /** The film's first frame is up. */
    fun firstFrame(nowMs: Long) {
        if (settledFrom < 0L) settledFrom = nowMs + SETTLE_MS
    }

    /** The viewer jumped: what's loaded ahead starts from nothing again. */
    fun jumped(nowMs: Long) {
        if (settledFrom >= 0L) settledFrom = nowMs + SETTLE_MS
    }

    /** Once a second: [aheadMs] is loaded ahead of the picture, without a gap. */
    fun ahead(nowMs: Long, aheadMs: Long) {
        if (settledFrom < 0L || nowMs < settledFrom || aheadMs < 0L) return
        if (minAheadMs < 0L || aheadMs < minAheadMs) minAheadMs = aheadMs
    }

    /** The film stopped to load. */
    fun stallStart(nowMs: Long) {
        if (stallSince >= 0L) return
        stallSince = nowMs
        stalls++
    }

    /** It plays again (or stopped for good). */
    fun stallEnd(nowMs: Long) {
        if (stallSince < 0L) return
        stalledMs += (nowMs - stallSince).coerceAtLeast(0L)
        stallSince = -1L
    }

    /** Frames since the last call, by [FrameTally]'s kinds. */
    fun frames(kind: Int, added: Long) {
        frames[kind] += added
    }

    /** What the loader brought since it was last asked ([LoaderRate.Sample]). */
    fun loader(countedBytes: Long, countedNs: Long, allBytes: Long) {
        this.countedBytes += countedBytes
        this.countedNs += countedNs
        this.allBytes += allBytes
    }

    /** The app holds [kb] kilobytes now (PSS). Any thread. */
    fun pss(kb: Long) {
        synchronized(this) {
            if (kb > peakPssKb) peakPssKb = kb
        }
    }

    /** Seconds stalled so far, a stall still going on at [nowMs] included. */
    fun stalledSeconds(nowMs: Long): Double =
        (stalledMs + if (stallSince >= 0L) (nowMs - stallSince).coerceAtLeast(0L) else 0L) / 1000.0

    /** The line at [nowMs]; [mode] and [rates] from [TwoRanges] ("1" and nothing without it); [last]: the player is going. */
    fun line(nowMs: Long, mode: String, rates: String, last: Boolean): String {
        val out = StringBuilder(260).append(if (last) "bench end mode=" else "bench mode=").append(mode)
            .append(" time=").append(((nowMs - startedAt).coerceAtLeast(0L) + 500) / 1000).append('s')
            .append(" flat=")
        if (countedNs > 0L) {
            out.append(String.format(Locale.US, "%.1fMb/s", countedBytes * 8_000.0 / countedNs))
                .append('/').append((countedNs + 500_000_000L) / 1_000_000_000L).append('s')
        } else {
            out.append("none")
        }
        out.append(" got=").append(String.format(Locale.US, "%.1fMB", allBytes / 1_048_576.0))
        if (rates.isNotEmpty()) out.append(' ').append(rates)
        out.append(" minAhead=")
        if (minAheadMs >= 0L) out.append(String.format(Locale.US, "%.1fs", minAheadMs / 1000.0)) else out.append("n/a")
        out.append(" stalled=").append(String.format(Locale.US, "%.1fs", stalledSeconds(nowMs))).append('/').append(stalls)
            .append(" dropped=").append(frames[FrameTally.DROPPED])
            .append(" twice=").append(frames[FrameTally.TWICE])
            .append(" same=").append(frames[FrameTally.SAME])
            .append(" never=").append(frames[FrameTally.NEVER])
            .append(" shown=").append(frames[FrameTally.RENDERED])
            .append(" pssPeak=")
        val peak = peakPssKb
        if (peak > 0L) out.append(String.format(Locale.US, "%.1fMB", peak / 1024.0)) else out.append("n/a")
        return out.toString()
    }

    companion object {
        /** What's loaded ahead counts from this long after the first frame or a jump. */
        const val SETTLE_MS = 20_000L
    }
}
