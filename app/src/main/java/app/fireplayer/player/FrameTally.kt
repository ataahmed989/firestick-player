package app.fireplayer.player

/**
 * The frames shown and lost over the last minute, second by second: for Picture & sound's "Dropped" row, the
 * FirePlayerDiag log ([PlaybackDiag]) and how each copy plays ([CopyHealth]).
 *
 * [sample] is given the counts so far once a second: Media3's own (frames it dropped, frames it sent to the screen,
 * from its DecoderCounters) and [FrameCounts]'. Each kind is kept apart:
 * - [DROPPED]: Media3 dropped them (too late);
 * - [TWICE]: the frame before was shown twice ([FrameCounts.shownTwice]);
 * - [SAME]: put on the refresh of the frame before, so skipped ([FrameCounts.sameRefresh]);
 * - [NEVER]: the decoder never gave them ([FrameCounts.neverDecoded]);
 * - [RENDERED]: sent to the screen.
 *
 * Media3's counts start again with each new decoder session ([sample]'s fresh, or a count lower than the last).
 * Nothing is made after it's built. On the main thread.
 */
class FrameTally(private val seconds: Int = 60) {

    private val buckets = Array(KINDS) { LongArray(seconds) }
    private val last = LongArray(KINDS)
    private var lastSecond = Long.MIN_VALUE

    /** What the latest [sample] added, by kind. */
    private val added = LongArray(KINDS)

    /** Frames lost of every kind in the latest [sample]. */
    val addedLost: Long get() = added[DROPPED] + added[TWICE] + added[SAME] + added[NEVER]

    /** Frames sent to the screen in the latest [sample]. */
    val addedRendered: Long get() = added[RENDERED]

    /** What the latest [sample] added of [kind]. */
    fun added(kind: Int): Long = added[kind]

    /**
     * The counts so far at [nowMs] (any clock in ms that only goes forward). [fresh]: Media3's counts are from a new
     * decoder session, so they count from 0 again.
     */
    fun sample(
        nowMs: Long,
        dropped: Long,
        rendered: Long,
        shownTwice: Long,
        sameRefresh: Long,
        neverDecoded: Long,
        fresh: Boolean = false,
    ) {
        val second = nowMs / 1000
        if (lastSecond == Long.MIN_VALUE) {
            // The first sample: only where counting starts from.
            lastSecond = second
            set(dropped, rendered, shownTwice, sameRefresh, neverDecoded)
            added.fill(0)
            return
        }
        // Seconds gone by since the last sample start empty (all of them, after more than the minute).
        if (second > lastSecond) {
            val gone = minOf(second - lastSecond, seconds.toLong()).toInt()
            for (k in 1..gone) {
                val slot = Math.floorMod(lastSecond + k, seconds.toLong()).toInt()
                for (b in buckets) b[slot] = 0
            }
            lastSecond = second
        }
        val slot = Math.floorMod(second, seconds.toLong()).toInt()
        add(DROPPED, dropped, fresh, slot)
        add(RENDERED, rendered, fresh, slot)
        add(TWICE, shownTwice, false, slot)
        add(SAME, sameRefresh, false, slot)
        add(NEVER, neverDecoded, false, slot)
    }

    private fun add(kind: Int, now: Long, fresh: Boolean, slot: Int) {
        val d = if (fresh || now < last[kind]) now else now - last[kind]
        last[kind] = now
        added[kind] = d
        buckets[kind][slot] += d
    }

    private fun set(dropped: Long, rendered: Long, shownTwice: Long, sameRefresh: Long, neverDecoded: Long) {
        last[DROPPED] = dropped
        last[RENDERED] = rendered
        last[TWICE] = shownTwice
        last[SAME] = sameRefresh
        last[NEVER] = neverDecoded
    }

    /** Frames of [kind] over the last minute (the last [seconds] seconds sampled). */
    fun minute(kind: Int): Long {
        var sum = 0L
        for (v in buckets[kind]) sum += v
        return sum
    }

    /** Frames lost of every kind over the last minute. */
    fun minuteLost(): Long = minute(DROPPED) + minute(TWICE) + minute(SAME) + minute(NEVER)

    /** "dropped 3 · shown twice 0 · same refresh 1 · never decoded 0", over the last minute. */
    fun minuteLine(): String =
        "dropped ${minute(DROPPED)} · shown twice ${minute(TWICE)} · same refresh ${minute(SAME)} · never decoded ${minute(NEVER)}"

    companion object {
        const val DROPPED = 0
        const val TWICE = 1
        const val SAME = 2
        const val NEVER = 3
        const val RENDERED = 4
        private const val KINDS = 5
    }
}
