package app.fireplayer.subs

import java.util.BitSet
import java.util.TreeSet
import kotlin.math.abs

/**
 * Keeps downloaded subtitles lined up all through the film, for [AutoSync]: the subtitle delay by film position, as
 * [Segment]s, learnt by matching the subtitle lines against the talking heard so far ([SpeechTimeline], [SyncMatcher]).
 *
 * By position because IPTV films often have ad breaks, bumpers or recaps spliced in: subtitles that fit before a break
 * are late (or early) after it, so no single delay is right on both sides, and going back across a break has to bring
 * the earlier delay back.
 *
 * How it decides:
 *  - The first delay (nothing known yet): two sure matches in a row agree, as auto sync always did.
 *  - Then it keeps checking, lightly: every [PERIOD_MS] of film as a fallback, and sooner after a hint that the timing
 *    may have changed: a jump into film not heard yet, the sound's format changing (ads are often encoded differently),
 *    a long stretch without talking where lines are expected, or the delay in use no longer fitting the last minute
 *    and a half. A hint only says where to start looking; it never moves the delay by itself.
 *  - A new delay needs the same proof as the first, from talking heard after the hint (so not the break itself): two
 *    sure matches in a row agreeing within [AGREE_MS], with fresh talking between them, each clearly better than the
 *    delay in use on the same stretch. Shifts up to [RANGE_MS] either way are looked for, not just a few seconds.
 *  - The new delay takes over where the talking says the old one stopped fitting. Never at a place or by an amount
 *    worked out from how long a break or a silence lasted: the subtitles may already allow for part of it.
 *
 * No threads, clocks or Android here: times are film times (ms), the caller says how much film played between [step]s,
 * and what to do comes back as a [Decision]. Not thread safe: the listening thread calls it, and that same thread
 * writes the [SpeechTimeline] it reads.
 */
class OffsetTracker(
    val lines: SyncMatcher.Lines,
    known: List<Segment> = emptyList(),
    /** Where the first delay is looked for while nothing is known: [learnCenterMs] ± [learnRangeMs]. */
    private var learnCenterMs: Long = 0,
    private var learnRangeMs: Long = SyncMatcher.RANGE_MS,
) {

    /** From film time [fromMs] on, until the next one, subtitles are shown [delayMs] later. */
    data class Segment(val fromMs: Long, val delayMs: Long)

    /** What made it think the timing may have changed here. */
    enum class Hint { SEEK, FORMAT, QUIET, MISFIT, RESYNC }

    enum class Outcome {
        /** Nothing worth matching yet. */
        WAIT,
        /** Matched, but not sure enough to change anything (yet). */
        NOT_SURE,
        /** Matched: the delay in use is right here. */
        KEPT,
        /** The first delay was found. */
        LEARNED,
        /** A new delay takes over from [Decision.atMs]. */
        MOVED,
        /** The delay in use was off by a little (less than [MIN_SHIFT_MS]), twice the same way: fine-tuned. */
        TUNED,
        /** No first delay after [LEARN_MAX_MS] of film: listening can stop. */
        GAVE_UP,
    }

    class Decision(
        val outcome: Outcome,
        /** The delays by position after this decision. */
        val segments: List<Segment>,
        /** The delay this is about: the one in use, the one found, or the best try. */
        val delayMs: Long,
        /** Where a new delay takes over ([Outcome.MOVED]); else the film position. */
        val atMs: Long,
        /** The stretch of film matched. */
        val fromMs: Long,
        val toMs: Long,
        val result: SyncMatcher.Result?,
        /** The overlap of the delay in use on the same stretch (NaN while learning). */
        val rHere: Double,
        val note: String,
    ) {
        /** One line for the log, with the numbers to check it by. */
        override fun toString(): String = buildString {
            append(outcome.name.lowercase()).append(' ').append(SyncMatcher.formatShift(delayMs))
            if (outcome == Outcome.MOVED) append(" from ").append(clock(atMs))
            append(" | heard ").append(clock(fromMs)).append('-').append(clock(toMs))
            result?.let { append(" | best ").append(it) }
            if (!rHere.isNaN()) append(" | in use r=").append("%.3f".format(java.util.Locale.ROOT, rHere))
            if (note.isNotEmpty()) append(" | ").append(note)
            append(" | segments ").append(segments.joinToString(" ") { "${clock(it.fromMs)}${SyncMatcher.formatShift(it.delayMs)}" })
        }
    }

    /** A sure match not yet repeated: [delayMs] for the stretch [fromMs]..[toMs] of the segment from [segmentFromMs]. */
    private class Candidate(val delayMs: Long, val segmentFromMs: Long, val fromMs: Long, val toMs: Long)

    private val map = ArrayList<Segment>(tidy(known))
    // Seconds of film where a match agreed with the map as it is: playing them again needs no matching.
    private val verified = BitSet()
    // The latest place the timing may have changed that hasn't been checked yet, or -1.
    private var suspectMs = -1L
    private var suspectWhy: Hint? = null
    // Places the timing may have changed (hints, and where new delays took over): Resync starts from the last one.
    private val breaks = TreeSet<Long>()
    private var candidate: Candidate? = null
    // The same for fine-tuning the delay in use.
    private var tuning: Candidate? = null
    private var sinceCheckMs = 0L
    // Where the last match ended, so a follow-up waits for talking after it.
    private var lastCheckToMs = -1L
    private var learnPlayedMs = 0L
    private var quietHintedMs = -1L
    // Matching in a segment found after a break starts no earlier than its proof did (segment start to film time).
    private val floors = HashMap<Long, Long>()

    val segments: List<Segment> get() = map.toList()
    val learning: Boolean get() = map.isEmpty()

    /** The delay for film time [ms], or null while nothing is known. */
    fun delayAt(ms: Long): Long? = delayAt(map, ms)

    /**
     * Some time after the last call ([playedMs] of film heard, new or again), with the newest heard at [nowMs]: matches
     * if it's time to, and says what to do.
     */
    fun step(timeline: SpeechTimeline, nowMs: Long, playedMs: Long): Decision {
        sinceCheckMs += playedMs
        // Gone back before it: nothing between there and here to look at.
        if (suspectMs > nowMs) suspectMs = -1
        return if (map.isEmpty()) learnStep(timeline, nowMs, playedMs) else followStep(timeline, nowMs)
    }

    /** Something outside the talking suggests the timing may change at [atMs] (a format change, say). */
    fun hint(atMs: Long, why: Hint) {
        // The later place wins: matching then starts after both. A jump always wins, as it's where the film is now.
        if (why == Hint.SEEK || suspectMs < 0 || atMs > suspectMs) {
            suspectMs = atMs
            suspectWhy = why
        }
        noteBreak(atMs)
    }

    private fun noteBreak(atMs: Long) {
        breaks.add(atMs)
        while (breaks.size > MAX_BREAKS) breaks.pollFirst()
    }

    /**
     * Playing jumped to [toMs] (or listening started there): if that wasn't heard before, the timing there is checked
     * as soon as there's talking. Landing just after film heard (pieces dropped, listening restarted) isn't a jump.
     */
    fun seeked(toMs: Long, timeline: SpeechTimeline) {
        if (toMs < 0) return
        val f = frame(toMs)
        val lastHeard = timeline.heard.previousSetBit(f)
        if (lastHeard < 0 || lastHeard < f - NEAR_FRAMES) hint(toMs, Hint.SEEK)
    }

    /**
     * Resync: forgets the delays found and finds one afresh around [atMs], within [centerMs] ± [RANGE_MS], from the
     * talking heard since the last place the timing may have changed (so often at once), else from what's heard next.
     * Then it carries on following the film as before.
     */
    fun relearn(atMs: Long, centerMs: Long) {
        val since = breaks.floor(atMs) ?: -1L
        map.clear()
        verified.clear()
        floors.clear()
        candidate = null
        tuning = null
        lastCheckToMs = -1
        learnCenterMs = centerMs
        learnRangeMs = RANGE_MS
        learnPlayedMs = 0
        sinceCheckMs = 0
        suspectMs = since
        suspectWhy = if (since >= 0) Hint.RESYNC else null
    }

    // ---- Finding the first delay ----------------------------------------------

    private fun learnStep(tl: SpeechTimeline, now: Long, playedMs: Long): Decision {
        learnPlayedMs += playedMs
        val from = maxOf(0L, suspectMs, now - LEARN_WINDOW_MS)
        val fresh = lastCheckToMs !in from..now || talkingMs(tl, lastCheckToMs, now) >= FRESH_TALK_MS
        val d = if (!fresh || talkingMs(tl, from, now) < MIN_TALK_MS) waiting(now) else learnCheck(tl, from, now)
        if (d.outcome != Outcome.LEARNED && learnPlayedMs >= LEARN_MAX_MS) {
            return Decision(Outcome.GAVE_UP, segments, d.delayMs, now, from, now, d.result, Double.NaN, "no sure match in ${LEARN_MAX_MS / 60_000} min")
        }
        return d
    }

    private fun learnCheck(tl: SpeechTimeline, from: Long, now: Long): Decision {
        sinceCheckMs = 0
        lastCheckToMs = now
        val h = SyncMatcher.heard(tl.speech, tl.heard, frame(from), frame(now) + 1) ?: return waiting(now)
        val res = SyncMatcher.match(h, lines, learnCenterMs, learnRangeMs)
        fun decide(o: Outcome, note: String = "") = Decision(o, segments, res.delayMs, now, from, now, res, Double.NaN, note)
        if (!res.confident) {
            candidate = null
            return decide(Outcome.NOT_SURE)
        }
        val before = candidate
        if (before == null || abs(before.delayMs - res.delayMs) > AGREE_MS) {
            candidate = Candidate(res.delayMs, -1, from, now)
            return decide(Outcome.NOT_SURE, "sure once")
        }
        if (freshTalkMs(tl, before, from, now) < FRESH_TALK_MS) return decide(Outcome.NOT_SURE, "waiting for fresh talking")
        map.add(Segment(0, res.delayMs))
        candidate = null
        suspectMs = -1
        verify(from, now)
        return decide(Outcome.LEARNED)
    }

    // ---- Following it through the film -------------------------------------------

    private fun followStep(tl: SpeechTimeline, now: Long): Decision {
        val i = indexAt(now)
        val seg = map[i]
        if (suspectMs >= 0 && (suspectMs < seg.fromMs || now - suspectMs > SUSPECT_FOR_MS)) suspectMs = -1
        candidate?.let { if (it.segmentFromMs != seg.fromMs || it.toMs > now) candidate = null }
        val floor = floors[seg.fromMs]?.takeIf { it < now } ?: seg.fromMs
        if (suspectMs < 0 && candidate == null) lookForBreaks(tl, now, seg.delayMs, floor)
        val from = maxOf(floor, suspectMs, now - WINDOW_MS)
        val due = when {
            // Something to follow up: again as soon as there's new talking to go on (none in silence or a quiet ad).
            candidate != null || suspectMs >= 0 -> lastCheckToMs !in from..now || talkingMs(tl, lastCheckToMs, now) >= FRESH_TALK_MS
            else -> sinceCheckMs >= PERIOD_MS && !mostlyVerified(from, now)
        }
        if (!due || talkingMs(tl, from, now) < MIN_TALK_MS) return waiting(now)
        return check(tl, i, from, now)
    }

    /** Matches the stretch [from]..[now] against the delay in use and the others in reach, and acts on it. */
    private fun check(tl: SpeechTimeline, i: Int, from: Long, now: Long): Decision {
        sinceCheckMs = 0
        lastCheckToMs = now
        val why = if (suspectMs >= 0) "after ${suspectWhy?.name?.lowercase()} hint" else "periodic check"
        val seg = map[i]
        val cur = seg.delayMs
        val h = SyncMatcher.heard(tl.speech, tl.heard, frame(from), frame(now) + 1) ?: return waiting(now)
        val res = SyncMatcher.match(h, lines, cur, RANGE_MS)
        val rHere = SyncMatcher.rAt(h, lines, cur)
        fun decide(o: Outcome, delay: Long, note: String = "", at: Long = now) = Decision(o, segments, delay, at, from, now, res, rHere, note)

        if (abs(res.delayMs - cur) < MIN_SHIFT_MS) {
            candidate = null
            if (!res.confident) {
                tuning = null
                return decide(Outcome.NOT_SURE, cur)
            }
            suspectMs = -1
            verify(from, now)
            // A little off, the same way twice: fine-tuned, as the one recheck two thirds in used to. The first delay
            // after a break comes from a couple of minutes of talking, so this is mostly where it gets exact.
            val t = tuning
            if (abs(res.delayMs - cur) < TUNE_MS) {
                tuning = null
            } else if (t != null && t.segmentFromMs == seg.fromMs && abs(t.delayMs - res.delayMs) <= AGREE_MS &&
                freshTalkMs(tl, t, from, now) >= FRESH_TALK_MS
            ) {
                tuning = null
                map[i] = Segment(seg.fromMs, res.delayMs)
                joinNeighbours()
                return decide(Outcome.TUNED, res.delayMs)
            } else {
                tuning = Candidate(res.delayMs, seg.fromMs, from, now)
            }
            return decide(Outcome.KEPT, cur)
        }
        tuning = null
        // Another delay must win clearly on this very stretch, not just be sure: the one in use fitting nearly as well
        // means the stretch is mixed (a break inside it) or the match is shaky.
        if (!res.confident || res.r - rHere < maxOf(MIN_GAIN, GAIN_SHARE * res.r)) {
            candidate = null
            return decide(Outcome.NOT_SURE, res.delayMs, if (res.confident) "not clearly better than the delay in use" else "")
        }
        val before = candidate
        if (before == null || abs(before.delayMs - res.delayMs) > AGREE_MS) {
            candidate = Candidate(res.delayMs, seg.fromMs, from, now)
            return decide(Outcome.NOT_SURE, res.delayMs, "sure once")
        }
        if (freshTalkMs(tl, before, from, now) < FRESH_TALK_MS) return decide(Outcome.NOT_SURE, res.delayMs, "waiting for fresh talking")
        if (map.size >= MAX_SEGMENTS) {
            candidate = null
            return decide(Outcome.NOT_SURE, res.delayMs, "too many changes in one film")
        }
        // Where the new delay takes over: from the talking, looking back from the proof. Not a sliver of a segment
        // right after the last change: that one just had the wrong delay.
        val lo = maxOf(seg.fromMs, from - LOOKBACK_MS)
        val at = split(tl, lo, now, cur, res.delayMs).let { if (it < 0) from else it }
            .coerceIn(seg.fromMs, now)
            .let { if (it - seg.fromMs < MIN_SEGMENT_MS) seg.fromMs else it }
        put(tl, i, at, res.delayMs, from, now)
        candidate = null
        suspectMs = -1
        noteBreak(at)
        return decide(Outcome.MOVED, res.delayMs, why, at)
    }

    /** Cheap looks at the latest film for signs the timing changed; each only sets where the next match starts. */
    private fun lookForBreaks(tl: SpeechTimeline, now: Long, cur: Long, floor: Long) {
        // No talking for a while where the subtitles expect lines: a silent bumper, a quiet ad, or a cut.
        val lastTalk = tl.speech.previousSetBit(frame(now))
        val quietFrom = maxOf(floor, if (lastTalk < 0) 0L else lastTalk * 10L)
        if (now - quietFrom >= QUIET_MS && quietFrom > quietHintedMs &&
            heardMs(tl, quietFrom, now) * 5 >= (now - quietFrom) * 4 &&
            lines.onScreen(cur, quietFrom, now) >= QUIET_LINES
        ) {
            quietHintedMs = quietFrom
            hint(quietFrom, Hint.QUIET)
            return
        }
        // The delay in use no longer fits the last minute and a half, though there were lines and talking in it.
        val from = maxOf(floor, now - MISFIT_WINDOW_MS)
        if (mostlyVerified(from, now)) return
        if (lines.onScreen(cur, from, now) < MISFIT_LINES || talkingMs(tl, from, now) < MISFIT_TALK_MS) return
        val h = SyncMatcher.heard(tl.speech, tl.heard, frame(from), frame(now) + 1) ?: return
        if (SyncMatcher.rAt(h, lines, cur) >= MISFIT_R) return
        hint(fitEnd(tl, cur, floor, now), Hint.MISFIT)
    }

    /** The latest place before [now] where [delay] still fitted: where its running overlap peaked. */
    private fun fitEnd(tl: SpeechTimeline, delay: Long, floor: Long, now: Long): Long {
        val from = maxOf(floor, now - WINDOW_MS)
        val h = SyncMatcher.heard(tl.speech, tl.heard, frame(from), frame(now) + 1) ?: return maxOf(from, now - MISFIT_WINDOW_MS)
        val f = SyncMatcher.fits(h, lines, delay)
        var sum = 0.0
        var best = 0.0
        var at = h.blockMs(0)
        for (k in 0 until h.blocks) {
            sum += f[k]
            if (sum > best) { best = sum; at = h.blockMs(k) + 100 }
        }
        return at
    }

    /**
     * Where delay [before] gives way to [after] between film times [lo] and [hi]: the place that splits the talking
     * heard best between them, [before] fitting up to it and [after] from it (a change point, block by block). Over a
     * break neither fits, so it lands somewhere in it. Where nothing was heard (the viewer jumped) nothing says either
     * way: that stretch goes to [before], as nothing there proved it wrong. -1 when too little was heard.
     */
    private fun split(tl: SpeechTimeline, lo: Long, hi: Long, before: Long, after: Long): Long {
        val h = SyncMatcher.heard(tl.speech, tl.heard, frame(lo), frame(hi) + 1) ?: return -1
        val f0 = SyncMatcher.fits(h, lines, before)
        val f1 = SyncMatcher.fits(h, lines, after)
        // Score of splitting just before block k: [before]'s fit up to it, [after]'s from it. Ties go to the later place.
        var score = f1.sum()
        var best = score
        var bestK = 0
        for (k in 1..h.blocks) {
            score += f0[k - 1] - f1[k - 1]
            if (score >= best) { best = score; bestK = k }
        }
        // Next to a stretch not heard, a few seconds of talking can't tell the two apart: the split goes to its end.
        var k = bestK
        for (j in maxOf(1, bestK - SNAP_BLOCKS)..minOf(h.blocks - 1, bestK + SNAP_BLOCKS)) {
            if (h.blockMs(j) - h.blockMs(j - 1) >= GAP_MS) { k = j; if (j >= bestK) break }
        }
        return if (k < h.blocks) h.blockMs(k) else h.blockMs(h.blocks - 1) + 100
    }

    /** Puts [delay] in from [at], in segment [i], proven by [from]..[now]. */
    private fun put(tl: SpeechTimeline, i: Int, at: Long, delay: Long, from: Long, now: Long) {
        val seg = map[i]
        val end = map.getOrNull(i + 1)?.fromMs ?: Long.MAX_VALUE
        // Further on, a stretch already checked with the delay in use (the viewer had jumped back): it keeps that
        // delay, from where the talking between here and there says it comes back.
        val later = verified.nextSetBit(sec(now))
        var back = if (later >= 0 && later * 1000L < end) later * 1000L else end
        if (back < end) {
            val s = split(tl, now, minOf(end, back + RETURN_LOOK_MS), delay, seg.delayMs)
            if (s >= 0) back = s.coerceIn(now, back)
        }
        verified.clear(sec(at), if (back == Long.MAX_VALUE) maxOf(verified.length(), sec(at)) else sec(back))
        if (back < end) map.add(i + 1, Segment(back, seg.delayMs))
        if (at <= seg.fromMs) map[i] = Segment(seg.fromMs, delay) else map.add(i + 1, Segment(at, delay))
        if (from > at) floors[maxOf(at, seg.fromMs)] = from
        verify(from, now)
        joinNeighbours()
    }

    /** Segments next to each other with (nearly) the same delay become one. */
    private fun joinNeighbours() {
        var k = 1
        while (k < map.size) {
            if (abs(map[k].delayMs - map[k - 1].delayMs) < MIN_SHIFT_MS) {
                floors.remove(map[k].fromMs)
                map.removeAt(k)
            } else {
                k++
            }
        }
    }

    // ---- Small helpers ----------------------------------------------------------

    private fun waiting(now: Long) = Decision(Outcome.WAIT, segments, delayAt(now) ?: learnCenterMs, now, now, now, null, Double.NaN, "")

    private fun indexAt(ms: Long): Int = indexAt(map, ms)

    private fun talkingMs(tl: SpeechTimeline, from: Long, to: Long): Long =
        if (to <= from) 0 else tl.speech.get(frame(from), frame(to)).cardinality() * 10L

    private fun heardMs(tl: SpeechTimeline, from: Long, to: Long): Long =
        if (to <= from) 0 else tl.heard.get(frame(from), frame(to)).cardinality() * 10L

    /** Talking in [from]..[now] that the earlier try [c] didn't have. */
    private fun freshTalkMs(tl: SpeechTimeline, c: Candidate, from: Long, now: Long): Long {
        val all = talkingMs(tl, from, now)
        val a = maxOf(from, c.fromMs)
        val b = minOf(now, c.toMs)
        return if (b > a) all - talkingMs(tl, a, b) else all
    }

    private fun verify(from: Long, to: Long) {
        if (sec(to) > sec(from)) verified.set(sec(from), sec(to))
    }

    private fun mostlyVerified(from: Long, to: Long): Boolean {
        val a = sec(from)
        val b = sec(to)
        if (b <= a) return true
        return verified.get(a, b).cardinality() * 5 >= (b - a) * 4
    }

    companion object {
        /** How far either way of the delay in use a new one is looked for once one is known (and on Resync). */
        const val RANGE_MS = 3 * 60_000L
        /** Two sure matches this close are the same delay. */
        const val AGREE_MS = 100L
        /** Changes smaller than this aren't worth a new segment (and are within what matching can tell apart). */
        const val MIN_SHIFT_MS = 400L
        /** Without a hint, a check every this much film. */
        const val PERIOD_MS = 3 * 60_000L
        /** The most film one check matches: its cost is this × the shifts tried. */
        const val WINDOW_MS = 4 * 60_000L
        /** While learning the first delay: the most film matched, and how long to try. */
        const val LEARN_WINDOW_MS = 6 * 60_000L
        const val LEARN_MAX_MS = 6 * 60_000L
        // Talking needed in a stretch before matching it is worth it (SyncMatcher wants as much to be sure).
        private const val MIN_TALK_MS = 30_000L
        // Talking the second of two agreeing matches must have that the first didn't.
        private const val FRESH_TALK_MS = 4_000L
        // How much better than the delay in use a new one must fit the same stretch: at least this much more overlap,
        // and at least this share of its own.
        private const val MIN_GAIN = 0.05
        private const val GAIN_SHARE = 0.25
        // Hints: this long without talking where at least so many lines are expected...
        private const val QUIET_MS = 20_000L
        private const val QUIET_LINES = 3
        // ...or the delay in use overlapping less than this over the last minute and a half, with lines and talking in it.
        private const val MISFIT_WINDOW_MS = 90_000L
        private const val MISFIT_LINES = 6
        private const val MISFIT_TALK_MS = 10_000L
        private const val MISFIT_R = 0.05
        // A hint not followed up this long after is dropped (checks carry on every PERIOD_MS).
        private const val SUSPECT_FOR_MS = 8 * 60_000L
        // Fine-tuning the delay in use: two sure matches agreeing on one at least this far from it (what the one
        // recheck two thirds in used to move it for).
        private const val TUNE_MS = 150L
        // How far back from its proof the place a new delay takes over is looked for, and how far on the place the
        // delay in use comes back (when the stretch after was checked with it).
        private const val LOOKBACK_MS = 30 * 60_000L
        private const val RETURN_LOOK_MS = 2 * 60_000L
        // A stretch not heard this long, and how near the split it counts (in 100 ms blocks of talking heard).
        private const val GAP_MS = 10_000L
        private const val SNAP_BLOCKS = 200
        // No new segment this short right after the last one: the last one's delay was wrong instead.
        private const val MIN_SEGMENT_MS = 20_000L
        private const val MAX_SEGMENTS = 64
        private const val MAX_BREAKS = 64
        // Landing within 10 s after film heard is carrying on, not a jump.
        private const val NEAR_FRAMES = 1_000

        /** The delay for film time [ms] in [segments], or null when there are none. */
        fun delayAt(segments: List<Segment>, ms: Long): Long? {
            if (segments.isEmpty()) return null
            var d = segments[0].delayMs
            for (s in segments) if (s.fromMs <= ms) d = s.delayMs else break
            return d
        }

        /** Which of [segments] film time [ms] is in (0 when none). */
        fun indexAt(segments: List<Segment>, ms: Long): Int {
            var i = 0
            while (i + 1 < segments.size && segments[i + 1].fromMs <= ms) i++
            return i
        }

        /** For keeping: "from:delay;from:delay", in ms. */
        fun encode(segments: List<Segment>): String = segments.joinToString(";") { "${it.fromMs}:${it.delayMs}" }

        /** Reads what [encode] wrote; null when there's nothing usable. */
        fun decode(text: String?): List<Segment>? {
            if (text.isNullOrBlank()) return null
            val out = text.split(';').mapNotNull { part ->
                val bits = part.split(':')
                val from = bits.getOrNull(0)?.trim()?.toLongOrNull()
                val delay = bits.getOrNull(1)?.trim()?.toLongOrNull()
                if (bits.size == 2 && from != null && delay != null && from >= 0) Segment(from, delay) else null
            }
            return tidy(out).ifEmpty { null }
        }

        /**
         * What was kept for a subtitle and film: the delays by position ([encode]), or else the one delay an older
         * version kept for the whole film ([legacyMs]); null when neither.
         */
        fun restore(text: String?, legacyMs: Long?): List<Segment>? =
            decode(text) ?: legacyMs?.let { listOf(Segment(0, it)) }

        /**
         * The delay auto sync puts in at film time [positionMs], or null to leave the delay alone: a delay set by hand
         * wins over everything found (and is one delay for the whole film), and nothing is put in unless synced.
         */
        fun followedDelayMs(byHand: Boolean, synced: Boolean, segments: List<Segment>, positionMs: Long): Long? =
            if (byHand || !synced) null else delayAt(segments, positionMs)

        /** In order, one per start, the first from the film's start. */
        private fun tidy(list: List<Segment>): List<Segment> {
            val sorted = list.sortedBy { it.fromMs }.distinctBy { it.fromMs }.toMutableList()
            if (sorted.isNotEmpty() && sorted[0].fromMs != 0L) sorted[0] = Segment(0, sorted[0].delayMs)
            return sorted
        }

        private fun frame(ms: Long): Int = (maxOf(ms, 0L) / 10).toInt()
        private fun sec(ms: Long): Int = (maxOf(ms, 0L) / 1000).toInt()

        /** Film time for the log: "1:02:03". */
        fun clock(ms: Long): String {
            val s = maxOf(ms, 0L) / 1000
            return "%d:%02d:%02d".format(java.util.Locale.ROOT, s / 3600, s / 60 % 60, s % 60)
        }
    }
}
