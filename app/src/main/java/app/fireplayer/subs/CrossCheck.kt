package app.fireplayer.subs

import kotlin.math.abs

/**
 * While lining up against the film's own subtitles, auto sync now and then listens for a minute or two and asks the
 * talking whether it agrees with the delays found ([judge]): a track from another cut of the film, or a bad one, would
 * line the subtitles up wrong all through, and only the talking can tell. Plain Kotlin, so tests can drive it.
 */
object CrossCheck {

    enum class Verdict { AGREES, DISAGREES, UNSURE }

    /** What the talking said: [delayMs] is its own best, [inUseMs] the delay found from the film's subtitles. */
    class Result(val verdict: Verdict, val delayMs: Long, val inUseMs: Long, val fromMs: Long, val toMs: Long, val note: String) {
        override fun toString() = "${verdict.name.lowercase()}: the talking says ${SyncMatcher.formatShift(delayMs)}, " +
            "in use ${SyncMatcher.formatShift(inUseMs)} | heard ${OffsetTracker.clock(fromMs)}-${OffsetTracker.clock(toMs)} | $note"
    }

    /**
     * Whether the talking heard in [fromMs]..[toMs] bears out [segments]. Only the longest part of it under one delay is
     * judged (a change inside it would blur both). It agrees when the delay in use is the best match there, or stands
     * out on it nearly as well; it disagrees only when the talking is sure of another delay a second or more away, which
     * clearly beats the one in use, and the one in use doesn't stand out at all. Anything else is unsure.
     */
    fun judge(tl: SpeechTimeline, lines: SyncMatcher.Lines, segments: List<OffsetTracker.Segment>, fromMs: Long, toMs: Long): Result {
        val inUse0 = OffsetTracker.delayAt(segments, fromMs) ?: 0L
        fun unsure(note: String, delay: Long = inUse0) = Result(Verdict.UNSURE, delay, inUse0, fromMs, toMs, note)
        if (segments.isEmpty() || toMs <= fromMs) return unsure("nothing to check")
        // The part with the most talking under one delay.
        val cuts = (listOf(fromMs) + segments.map { it.fromMs }.filter { it > fromMs && it < toMs } + listOf(toMs))
        var a = fromMs
        var b = toMs
        var most = -1L
        for (k in 0 until cuts.size - 1) {
            val talk = talkingMs(tl, cuts[k], cuts[k + 1])
            if (talk > most) { most = talk; a = cuts[k]; b = cuts[k + 1] }
        }
        val cur = OffsetTracker.delayAt(segments, a) ?: return unsure("nothing to check")
        if (most < MIN_TALK_MS) return Result(Verdict.UNSURE, cur, cur, a, b, "too little talking (${most / 1000} s)")
        val h = SyncMatcher.heard(tl.speech, tl.heard, frame(a), frame(b) + 1) ?: return Result(Verdict.UNSURE, cur, cur, a, b, "nothing heard")
        val res = SyncMatcher.match(h, lines, cur, OffsetTracker.RANGE_MS)
        val rHere = SyncMatcher.rAt(h, lines, cur)
        val zHere = SyncMatcher.zAt(h, lines, cur, cur, OffsetTracker.RANGE_MS)
        val numbers = "best r=${"%.2f".format(java.util.Locale.ROOT, res.r)} (z=${"%.1f".format(java.util.Locale.ROOT, res.z)}" +
            "${if (res.confident) ", sure" else ""}), in use r=${"%.2f".format(java.util.Locale.ROOT, rHere)} (z=${"%.1f".format(java.util.Locale.ROOT, zHere)})"
        val verdict = when {
            abs(res.delayMs - cur) < AGREE_MS && res.z >= AGREE_Z -> Verdict.AGREES
            zHere >= AGREE_Z && rHere >= res.r * AGREE_SHARE -> Verdict.AGREES
            res.confident && abs(res.delayMs - cur) >= DISAGREE_SHIFT_MS && res.r - rHere >= maxOf(MIN_GAIN, GAIN_SHARE * res.r) &&
                zHere < DISAGREE_Z -> Verdict.DISAGREES
            else -> Verdict.UNSURE
        }
        return Result(verdict, res.delayMs, cur, a, b, numbers)
    }

    private fun talkingMs(tl: SpeechTimeline, from: Long, to: Long): Long =
        if (to <= from) 0 else tl.speech.get(frame(from), frame(to)).also { it.and(tl.heard.get(frame(from), frame(to))) }.cardinality() * 10L

    private fun frame(ms: Long): Int = (maxOf(ms, 0L) / 10).toInt()

    /** Talking needed to judge (SyncMatcher wants about as much to be sure of anything). */
    const val MIN_TALK_MS = 30_000L
    // Agreeing: the best within this of the delay in use, standing out this far; or the delay in use standing out this
    // far and overlapping nearly as well as the best.
    private const val AGREE_MS = 400L
    private const val AGREE_Z = 3.0
    private const val AGREE_SHARE = 0.8
    // Disagreeing: sure of a delay this far off, beating the one in use as clearly as any change must, the one in use
    // not standing out as far as agreeing needs.
    private const val DISAGREE_SHIFT_MS = 1_000L
    private const val MIN_GAIN = 0.05
    private const val GAIN_SHARE = 0.25
    private const val DISAGREE_Z = AGREE_Z
}
