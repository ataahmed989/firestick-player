package app.fireplayer.subs

import java.util.BitSet

/**
 * The contract between subtitle auto sync and anything that wants to use its offsets elsewhere (the skip buttons):
 * it maps the DOWNLOADED SUBTITLES' timeline to THIS FILE's timeline, and nothing else.
 *
 * - Only offsets auto sync confirmed against the film's speech in this session count: a moment maps only when talking
 *   within [REACH_MS] of it, under the same offset, was matched. Unheard or unchecked film, a delay set by hand, and
 *   offsets restored from an earlier session that haven't been heard again give null.
 * - Around a break a clean time can fall on either side (before the ad, or after it): no answer there either.
 * - A stretch whose two ends fall under different offsets (it spans a break) gives null: it isn't one stretch here.
 * - It says nothing about other timelines. Times from elsewhere (SkipDB, IntroDB) may only be put through it when
 *   there is separate evidence they were taken on the subtitles' timeline: see [app.fireplayer.player.SkipRules.mapped].
 *
 * An immutable snapshot, made on the main thread, safe to read anywhere; [version] goes up with every new one.
 */
class SyncMapping(
    segments: List<OffsetTracker.Segment>,
    private val confirmedSeconds: BitSet,
    val version: Int,
    /** The film these subtitles were matched to: a mapping says nothing about any other. */
    val filmKey: String,
) {

    val segments: List<OffsetTracker.Segment> = segments.sortedBy { it.fromMs }

    /** Where [cleanMs] on the subtitles' timeline is in this file; null where it isn't known for sure (see above). */
    fun fileTimeOf(cleanMs: Long): Long? {
        val i = segmentFor(cleanMs) ?: return null
        val f = cleanMs + segments[i].delayMs
        return f.takeIf { confirmedNear(i, f) }
    }

    /**
     * The stretch [startMs]..[endMs] (subtitles' timeline) in this file; null unless both ends map under one offset,
     * each with talking matched near it. One exception: credits running to the end of a file [fileDurationMs] long,
     * under the last offset, need no talking at their end (there is none in credits). The file's end is where the
     * subtitles' timeline ends ([cleanLength]); for times whose source was matched against that length, with the start
     * confirmed, the lengths agreeing leaves no room for anything put in between.
     */
    fun fileRangeOf(startMs: Long, endMs: Long, fileDurationMs: Long = 0): LongRange? {
        if (endMs < startMs) return null
        val i = segmentFor(startMs) ?: return null
        val atEnd = fileDurationMs > 0 && i == segments.lastIndex && Math.abs(fileDurationMs - (endMs + segments[i].delayMs)) <= END_MS
        if (!atEnd && segmentFor(endMs) != i) return null
        val d = segments[i].delayMs
        val a = startMs + d
        val b = endMs + d
        if (!confirmedNear(i, a) || !(atEnd || confirmedNear(i, b))) return null
        return a..b
    }

    /**
     * How long the subtitles' timeline is for a file [fileDurationMs] long: the file less what the last offset adds.
     * Evidence for matching other sources to the subtitles' version, never a mapping in itself.
     */
    fun cleanLength(fileDurationMs: Long): Long? =
        segments.lastOrNull()?.let { fileDurationMs - it.delayMs }?.takeIf { it > 0 && fileDurationMs > 0 }

    /** The one offset under which [cleanMs] lands in its own stretch of the file; null for none or more than one. */
    private fun segmentFor(cleanMs: Long): Int? {
        var found: Int? = null
        for (i in segments.indices) {
            val f = cleanMs + segments[i].delayMs
            val end = segments.getOrNull(i + 1)?.fromMs ?: Long.MAX_VALUE
            if (f >= segments[i].fromMs && f < end) {
                if (found != null) return null
                found = i
            }
        }
        return found
    }

    private fun confirmedNear(i: Int, fileMs: Long): Boolean {
        val from = maxOf(segments[i].fromMs, fileMs - REACH_MS, 0L)
        val to = minOf(segments.getOrNull(i + 1)?.fromMs ?: Long.MAX_VALUE, fileMs + REACH_MS)
        if (to <= from) return false
        val a = (from / 1000).toInt()
        val b = ((to + 999) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val next = confirmedSeconds.nextSetBit(a)
        return next in a until b
    }

    companion object {
        /** How far from talking matched under the same offset a moment may be and still count as known. */
        const val REACH_MS = 90_000L
        /** How near a stretch's end must be to the file's for it to count as running to it. */
        const val END_MS = 5_000L
    }
}
