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
 *  - The first delay (nothing known yet): two sure matches agree, as auto sync always did. It must also fit the latest
 *    talking; if it doesn't, it was the delay before a break inside what was heard, and the talking after that is used.
 *  - Then it keeps checking, lightly: every [PERIOD_MS] of film as a fallback, and sooner after a hint that the timing
 *    may have changed: a jump into film not heard yet, the sound's format changing (ads are often encoded differently),
 *    a long stretch without talking where lines are expected, or the delay in use no longer fitting the last minute
 *    and a half. A hint only says where to start looking; it never moves the delay by itself. Follow-ups that keep
 *    coming out unsure are spaced out, so talking that fits no delay in reach isn't matched every few seconds.
 *  - A new delay needs proof from talking heard after the hint: a sure match clearly better than the delay in use on
 *    the same stretch, then a second sure match agreeing within [AGREE_MS] on the talking the first fitted from (so not
 *    the break itself), with talking the first didn't have, on which alone the delay must stand out as well. Shifts up
 *    to [RANGE_MS] either way are looked for, not just a few seconds; a best match at the very edge of that isn't trusted.
 *  - The new delay takes over where the talking says the old one stopped fitting. Never at a place or by an amount
 *    worked out from how long a break or a silence lasted: the subtitles may already allow for part of it.
 *  - Smaller changes (under [MIN_SHIFT_MS]) fine-tune the delay in use, with the same proof: for the whole segment while
 *    no part of it was found exactly right, else from where the talking says it changed after the part that was.
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
    /** See [search]; 0 judges every change in the whole [RANGE_MS] only. */
    private val nearMs: Long = NEAR_MS,
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
        /** The delay this is about: the one in use, the one now in force ([Outcome.MOVED], [Outcome.TUNED]), or the best try. */
        val delayMs: Long,
        /** Where a new delay takes over ([Outcome.MOVED], [Outcome.TUNED]); else the film position. */
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
            if (outcome == Outcome.MOVED || outcome == Outcome.TUNED) append(" from ").append(clock(atMs))
            append(" | heard ").append(clock(fromMs)).append('-').append(clock(toMs))
            result?.let { append(" | best ").append(it) }
            if (!rHere.isNaN()) append(" | in use r=").append("%.3f".format(java.util.Locale.ROOT, rHere))
            if (note.isNotEmpty()) append(" | ").append(note)
            append(" | segments ").append(segments.joinToString(" ") { "${clock(it.fromMs)}${SyncMatcher.formatShift(it.delayMs)}" })
        }
    }

    /**
     * A sure match waiting for a second: [delayMs], matched on [fromMs]..[toMs] in the segment from [segmentFromMs]. The
     * talking it fits starts at [fitsFromMs]: the second match starts there, leaving out the break and what came before.
     */
    private class Candidate(val delayMs: Long, val segmentFromMs: Long, val fromMs: Long, val toMs: Long, val fitsFromMs: Long) {
        // Talking after [toMs] the second match waits for: one look at CONFIRM_TALK_MS, one more at twice that.
        var waitTalkMs = CONFIRM_TALK_MS
    }

    private val map = ArrayList<Segment>(tidy(known))
    // Seconds of film where a match agreed with the map as it is: playing them again needs no matching.
    private val verified = BitSet()
    // Seconds where a sure match put the delay within TUNE_MS of the map's: fine-tuning leaves them as they are.
    private val exact = BitSet()
    // The latest place the timing may have changed that hasn't been checked yet, or -1.
    private var suspectMs = -1L
    private var suspectWhy: Hint? = null
    // Places the timing may have changed (hints, and where new delays took over): Resync starts from the last one.
    private val breaks = TreeSet<Long>()
    private var candidate: Candidate? = null
    private var sinceCheckMs = 0L
    // Where the last match ended, so a follow-up waits for talking after it.
    private var lastCheckToMs = -1L
    private var learnPlayedMs = 0L
    private var quietHintedMs = -1L
    // Matching in a segment found after a break starts no earlier than its proof did (segment start to film time).
    private val floors = HashMap<Long, Long>()
    // Follow-ups of a hint in a row that weren't sure of anything: after a few, they're spaced out ([spacing]).
    private var misses = 0
    // After Resync: the delay the viewer turned down, and where. It only comes back on talking heard after that.
    private var rejectedMs: Long? = null
    private var resyncAtMs = -1L

    val segments: List<Segment> get() = map.toList()
    /** Seconds of film where a match agreed with the map in this session (a copy), for [SyncMapping]. */
    fun confirmedSeconds(): BitSet = verified.clone() as BitSet

    /**
     * For each segment, where its change may really begin: its own start, or, when some of the film between the last
     * second found right under the offset before and that start was never heard (a jump past the break), the end of
     * that talking. The change can be anywhere in between. See [SyncMapping].
     */
    fun changeFrom(tl: SpeechTimeline): List<Long> = map.mapIndexed { k, s ->
        if (k == 0) return@mapIndexed s.fromMs
        val prevFrom = map[k - 1].fromMs
        val v = verified.previousSetBit(sec(s.fromMs) - 1)
        val lastOk = if (v < sec(prevFrom)) prevFrom else minOf(s.fromMs, (v + 1) * 1000L)
        if (s.fromMs <= lastOk) return@mapIndexed s.fromMs
        val a = frame(lastOk)
        val b = frame(s.fromMs)
        val unheardMs = ((b - a) - tl.heard.get(a, b).cardinality()) * 10L
        if (unheardMs >= GAP_MS) lastOk else s.fromMs
    }
    val confirmedCount: Int get() = verified.cardinality()
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
        // Gone back before the first of two matches: a second would be of another stretch.
        candidate?.let { if (it.toMs > nowMs) candidate = null }
        // Gone back before where Resync was pressed: elsewhere in the film, the delay turned down may well be right.
        if (nowMs < resyncAtMs) rejectedMs = null
        return if (map.isEmpty()) learnStep(timeline, nowMs, playedMs) else followStep(timeline, nowMs)
    }

    /** Something outside the talking suggests the timing may change at [atMs] (a format change, say). */
    fun hint(atMs: Long, why: Hint) {
        // The later place wins: matching then starts after both. A jump always wins, as it's where the film is now.
        if (why == Hint.SEEK || suspectMs < 0 || atMs > suspectMs) {
            suspectMs = atMs
            suspectWhy = why
        }
        // Something new: follow-ups are quick again (a misfit or a quiet stretch coming back again isn't new).
        if (why == Hint.SEEK || why == Hint.FORMAT || why == Hint.RESYNC) misses = 0
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
     * Resync: forgets the delays found and finds one afresh around [atMs], within [centerMs] ± [RANGE_MS], then carries
     * on following the film as before. The viewer says [centerMs] is wrong here, so:
     *  - talking it still fitted isn't used again: where it stopped fitting (a break not found yet), the talking from
     *    there on is; where it seems to fit up to now, all since the last break is;
     *  - [centerMs] itself only comes back on talking heard after the press (so in a minute or two, as the subtitles
     *    stay as they are meanwhile); any other delay is taken from what was heard already, often at once.
     */
    fun relearn(atMs: Long, centerMs: Long, timeline: SpeechTimeline) {
        val lastBreak = breaks.floor(atMs) ?: 0L
        val fit = fitEnd(timeline, centerMs, lastBreak, atMs)
        val since = if (atMs - fit <= FITS_TO_NOW_MS) lastBreak else fit
        map.clear()
        verified.clear()
        exact.clear()
        floors.clear()
        candidate = null
        lastCheckToMs = -1
        learnCenterMs = centerMs
        learnRangeMs = RANGE_MS
        learnPlayedMs = 0
        sinceCheckMs = 0
        misses = 0
        suspectMs = since
        suspectWhy = Hint.RESYNC
        rejectedMs = centerMs
        resyncAtMs = atMs
    }

    // ---- Finding the first delay ----------------------------------------------

    private fun learnStep(tl: SpeechTimeline, now: Long, playedMs: Long): Decision {
        learnPlayedMs += playedMs
        val c = candidate
        val from = maxOf(0L, suspectMs, now - LEARN_WINDOW_MS, c?.fitsFromMs ?: 0L)
        val due = if (c != null) talkingMs(tl, c.toMs, now) >= c.waitTalkMs else lastCheckToMs !in from..now || talkingMs(tl, lastCheckToMs, now) >= FRESH_TALK_MS
        val d = if (!due || talkingMs(tl, from, now) < MIN_TALK_MS) waiting(now) else learnCheck(tl, from, now)
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
        if (!sure(res, learnCenterMs, learnRangeMs)) {
            candidate = null
            return decide(Outcome.NOT_SURE, unsureNote(res))
        }
        // The delay the viewer turned down with Resync, from talking heard before: listens afresh for it.
        val rejected = rejectedMs
        if (rejected != null && abs(res.delayMs - rejected) < MIN_SHIFT_MS && from < resyncAtMs) {
            candidate = null
            suspectMs = resyncAtMs
            suspectWhy = Hint.RESYNC
            return decide(Outcome.NOT_SURE, "the delay Resync turned down: only talking from the press on can bring it back")
        }
        // Sure, but it doesn't fit the latest talking: the delay before a break inside this stretch. Looks after it.
        if (!fitsLatest(tl, res.delayMs, from, now)) {
            candidate = null
            suspectMs = fitEnd(tl, res.delayMs, from, now)
            suspectWhy = Hint.MISFIT
            noteBreak(suspectMs)
            return decide(Outcome.NOT_SURE, "doesn't fit the latest talking")
        }
        val before = candidate
        var note = ""
        // Only the talking the proof went by counts as found right: not what came before a break inside the stretch.
        var proofFrom = from
        if (before == null || abs(before.delayMs - res.delayMs) > AGREE_MS) {
            val fits = fitStart(tl, res.delayMs, from, now)
            if (!confirmsAtOnce(tl, res.delayMs, learnCenterMs, learnRangeMs, fits, now)) {
                candidate = Candidate(res.delayMs, -1, from, now, fits)
                return decide(Outcome.NOT_SURE, "sure once")
            }
            proofFrom = fits
            note = AT_ONCE
        } else {
            confirmFails(tl, before, res.delayMs, learnCenterMs, learnRangeMs, now)?.let { return decide(Outcome.NOT_SURE, it) }
        }
        map.add(Segment(0, res.delayMs))
        candidate = null
        suspectMs = -1
        rejectedMs = null
        checked(tl, res.delayMs, proofFrom, now, exactly = false)
        return decide(Outcome.LEARNED, note)
    }

    // ---- Following it through the film -------------------------------------------

    private fun followStep(tl: SpeechTimeline, now: Long): Decision {
        val i = indexAt(now)
        val seg = map[i]
        if (suspectMs >= 0 && (suspectMs < seg.fromMs || now - suspectMs > SUSPECT_FOR_MS)) suspectMs = -1
        candidate?.let { if (it.segmentFromMs != seg.fromMs) candidate = null }
        val floor = floors[seg.fromMs]?.takeIf { it < now } ?: seg.fromMs
        if (suspectMs < 0 && candidate == null) lookForBreaks(tl, now, seg.delayMs, floor)
        val c = candidate
        // A second match looks only at the talking the first fitted from: after the break, not the break itself.
        val from = maxOf(floor, suspectMs, now - WINDOW_MS, c?.fitsFromMs ?: 0L)
        val due = when {
            c != null -> talkingMs(tl, c.toMs, now) >= c.waitTalkMs
            // Something to follow up: again as soon as there's new talking to go on (none in silence or a quiet ad).
            suspectMs >= 0 -> sinceCheckMs >= spacing() && (lastCheckToMs !in from..now || talkingMs(tl, lastCheckToMs, now) >= FRESH_TALK_MS)
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
        val (res, range) = search(h, cur)
        val rHere = SyncMatcher.rAt(h, lines, cur)
        fun decide(o: Outcome, delay: Long, note: String = "", at: Long = now) = Decision(o, segments, delay, at, from, now, res, rHere, note)

        if (!sure(res, cur, range)) {
            candidate = null
            if (suspectMs >= 0) misses++
            return decide(Outcome.NOT_SURE, cur, unsureNote(res))
        }
        // Judged in the nearer reach only, a match on its own doesn't end the back-off: what it leads to does.
        if (range == RANGE_MS) misses = 0
        val shift = abs(res.delayMs - cur)
        if (shift < TUNE_MS) {
            // Right here, to within what anyone sees.
            candidate = null
            suspectMs = -1
            misses = 0
            checked(tl, cur, from, now, exactly = true)
            return decide(Outcome.KEPT, cur)
        }
        // Another delay must win on this very stretch, not just be sure: the one in use fitting nearly as well means
        // the stretch is mixed (a break inside it) or the match is shaky. Under a second off, the one in use still
        // overlaps much of the talking, so it can't be beaten by much: there, the matches repeating carry the proof.
        val gain = res.r - rHere
        if (shift >= MIN_SHIFT_MS && (gain <= 0 || shift >= SMALL_SHIFT_MS && gain < maxOf(MIN_GAIN, GAIN_SHARE * res.r))) {
            candidate = null
            return decide(Outcome.NOT_SURE, res.delayMs, "not clearly better than the delay in use")
        }
        // Less than MIN_SHIFT_MS off, the delay in use is still about right here: nothing more to follow up.
        val close = shift < MIN_SHIFT_MS
        val exactEnd = if (close) exactEnd(seg, now) else -1L
        if (close) {
            suspectMs = -1
            misses = 0
            verify(from, now)
            // Part of this segment was found exactly right: a small change after it, which would be a segment of its
            // own, has to be well beyond the matches' own scatter (about 100 ms either way).
            if (exactEnd >= 0 && shift < SPLIT_TUNE_MS) {
                candidate = null
                return decide(Outcome.KEPT, cur)
            }
        }
        val meanwhile = if (close) Outcome.KEPT else Outcome.NOT_SURE
        val shown = if (close) cur else res.delayMs
        val before = candidate
        // The talking the proof goes by: after the break, not the break itself.
        val proofFrom: Long
        var note = if (range != RANGE_MS) "$why, $NEAR" else why
        if (before == null || abs(before.delayMs - res.delayMs) > AGREE_MS) {
            val fits = fitStart(tl, res.delayMs, from, now)
            if (!confirmsAtOnce(tl, res.delayMs, cur, range, fits, now)) {
                candidate = Candidate(res.delayMs, seg.fromMs, from, now, fits)
                return decide(meanwhile, shown, if (range != RANGE_MS) "sure once, $NEAR" else "sure once")
            }
            proofFrom = fits
            note += ", $AT_ONCE"
        } else {
            confirmFails(tl, before, res.delayMs, cur, range, now)?.let { return decide(meanwhile, shown, it) }
            proofFrom = from
        }
        candidate = null
        misses = 0
        if (map.size >= MAX_SEGMENTS) return decide(meanwhile, shown, "too many changes in one film")
        val at = if (close) tuneAt(tl, i, res.delayMs, exactEnd, now) else moveAt(tl, i, res.delayMs, proofFrom, now)
        if (!put(tl, i, at, res.delayMs, proofFrom, now, if (close) exact else verified)) {
            return decide(meanwhile, shown, "goes against a stretch already found right")
        }
        if (!close) {
            if (proofFrom > at) floors[maxOf(at, seg.fromMs)] = proofFrom
            suspectMs = -1
            noteBreak(at)
        }
        joinNeighbours()
        // The stretch reported is the one the proof went by.
        return Decision(if (close) Outcome.TUNED else Outcome.MOVED, segments, delayAt(at) ?: res.delayMs, at, proofFrom, now, res, rHere, note)
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
        if (fitsLatest(tl, cur, from, now)) return
        hint(fitEnd(tl, cur, floor, now), Hint.MISFIT)
    }

    /** Whether [delay] fits the last minute and a half of [from]..[now]; true when there are too few lines or talking to say. */
    private fun fitsLatest(tl: SpeechTimeline, delay: Long, from: Long, now: Long): Boolean {
        val a = maxOf(from, now - MISFIT_WINDOW_MS)
        if (lines.onScreen(delay, a, now) < MISFIT_LINES || talkingMs(tl, a, now) < MISFIT_TALK_MS) return true
        val h = SyncMatcher.heard(tl.speech, tl.heard, frame(a), frame(now) + 1) ?: return true
        return SyncMatcher.rAt(h, lines, delay) >= MISFIT_R
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
     * The earliest place in [from]..[now] from which [delay] fits: where its overlap, added up back from [now], peaks.
     * Each block also costs a little (a fifth of the stretch's average), so where the delay merely doesn't fit (ads,
     * the talking before a break) the sum falls instead of wandering up by chance and taking the start with it.
     */
    private fun fitStart(tl: SpeechTimeline, delay: Long, from: Long, now: Long): Long {
        val h = SyncMatcher.heard(tl.speech, tl.heard, frame(from), frame(now) + 1) ?: return from
        val f = SyncMatcher.fits(h, lines, delay)
        val cost = maxOf(0.0, f.sum() / h.blocks) * FIT_COST
        var sum = 0.0
        var best = 0.0
        var at = from
        for (k in h.blocks - 1 downTo 0) {
            sum += f[k] - cost
            if (sum > best) { best = sum; at = h.blockMs(k) }
        }
        return at
    }

    /**
     * Whether a first sure match of [delay] on [from]..[now] is borne out at once by talking already heard (after
     * Resync, or for other subtitles, say): two stretches that share nothing, the same as two matches in a row. On all
     * but the latest [AT_ONCE_TALK_MS] of talking it must be sure of the same, and on that latest talking alone it must
     * stand out ([FRESH_Z]). With too little heard for both, it waits for a second match as usual.
     */
    private fun confirmsAtOnce(tl: SpeechTimeline, delay: Long, center: Long, range: Long, from: Long, now: Long): Boolean {
        val m = talkFrom(tl, now, AT_ONCE_TALK_MS)
        if (m <= from || talkingMs(tl, from, m) < MIN_TALK_MS) return false
        val first = SyncMatcher.heard(tl.speech, tl.heard, frame(from), frame(m)) ?: return false
        val a = SyncMatcher.match(first, lines, center, range)
        if (!sure(a, center, range) || abs(a.delayMs - delay) > AGREE_MS) return false
        val latest = SyncMatcher.heard(tl.speech, tl.heard, frame(m), frame(now) + 1) ?: return false
        return SyncMatcher.zAt(latest, lines, delay, center, range) >= FRESH_Z
    }

    /** The place before [now] with [talkMs] of talking after it, or -1 when there's less. */
    private fun talkFrom(tl: SpeechTimeline, now: Long, talkMs: Long): Long {
        var f = frame(now) + 1
        repeat((talkMs / 10).toInt()) {
            f = tl.speech.previousSetBit(f - 1)
            if (f < 0) return -1
        }
        return f * 10L
    }

    /**
     * Why a second sure match of [delay], agreeing with [c], doesn't confirm it yet, or null when it does. It must have
     * talking the first didn't, and on that talking alone [delay] must stand out from the other shifts around [center]:
     * two matches of mostly the same stretch would only repeat a chance best. Not borne out at the first look, it gets
     * one more with twice the talking, then it's dropped.
     */
    private fun confirmFails(tl: SpeechTimeline, c: Candidate, delay: Long, center: Long, range: Long, now: Long): String? {
        if (talkingMs(tl, c.toMs, now) < c.waitTalkMs) return "waiting for fresh talking"
        val h = SyncMatcher.heard(tl.speech, tl.heard, frame(c.toMs), frame(now) + 1)
        val z = if (h == null) 0.0 else SyncMatcher.zAt(h, lines, delay, center, range)
        val zText = "%.1f".format(java.util.Locale.ROOT, z)
        if (z >= FRESH_Z) return null
        if (c.waitTalkMs < 2 * CONFIRM_TALK_MS) {
            c.waitTalkMs = 2 * CONFIRM_TALK_MS
            return "not borne out by the latest talking yet (z=$zText)"
        }
        candidate = null
        return "not borne out by the latest talking (z=$zText)"
    }

    /** Where the last stretch of [seg] before [now] found exactly right ends, or -1 when none of it was. */
    private fun exactEnd(seg: Segment, now: Long): Long {
        val last = exact.previousSetBit(sec(now) - 1)
        return if (last < sec(seg.fromMs)) -1 else (last + 1) * 1000L
    }

    /**
     * Where fine-tuning takes over: the whole segment while no part of it was found exactly right (the delay was only
     * ever an estimate), else after the part that was ([exactEnd]), where the talking says it changed.
     */
    private fun tuneAt(tl: SpeechTimeline, i: Int, delay: Long, exactEnd: Long, now: Long): Long {
        val seg = map[i]
        if (exactEnd < 0) return seg.fromMs
        val s = split(tl, exactEnd, now, seg.delayMs, delay)
        return (if (s < 0) exactEnd else s).coerceIn(exactEnd, now).let { if (it - seg.fromMs < MIN_SEGMENT_MS) seg.fromMs else it }
    }

    /** Where a new delay takes over: from the talking, looking back from the proof. Not a sliver after the last change. */
    private fun moveAt(tl: SpeechTimeline, i: Int, delay: Long, from: Long, now: Long): Long {
        val seg = map[i]
        val lo = maxOf(seg.fromMs, from - LOOKBACK_MS)
        return split(tl, lo, now, seg.delayMs, delay).let { if (it < 0) from else it }
            .coerceIn(seg.fromMs, now)
            // That close after the last change, the last change had the wrong delay instead.
            .let { if (it - seg.fromMs < MIN_SEGMENT_MS) seg.fromMs else it }
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

    /**
     * Puts [delay] in from [at], in segment [i], proven by [from]..[now]. Further on, a stretch [kept] says was right
     * with the delay in use (the viewer had played it, then gone back) keeps that delay, from where the talking between
     * says it comes back. False, changing nothing, when that leaves the new delay no room.
     */
    private fun put(tl: SpeechTimeline, i: Int, at: Long, delay: Long, from: Long, now: Long, kept: BitSet): Boolean {
        val seg = map[i]
        val end = map.getOrNull(i + 1)?.fromMs ?: Long.MAX_VALUE
        var back = end
        val later = kept.nextSetBit(sec(now))
        if (later >= 0 && later * 1000L < end) {
            // That stretch can start before here, this proof having run into it: then the return is looked for between.
            val start = maxOf(at, (if (later == sec(now)) kept.previousClearBit(later) + 1 else later) * 1000L)
            val lo = minOf(start, now)
            val hi = maxOf(start, now)
            val s = split(tl, lo, minOf(end, hi + RETURN_LOOK_MS), delay, seg.delayMs)
            back = if (s < 0) start else s.coerceIn(lo, hi)
        }
        if (back <= at) return false
        val to = if (back == Long.MAX_VALUE) maxOf(verified.length(), exact.length(), sec(at)) else sec(back)
        verified.clear(sec(at), to)
        exact.clear(sec(at), to)
        if (back < end) map.add(i + 1, Segment(back, seg.delayMs))
        if (at <= seg.fromMs) map[i] = Segment(seg.fromMs, delay) else map.add(i + 1, Segment(at, delay))
        // Checked, but not found exactly right: these matches are what set the delay, so only a later one can say that.
        verify(maxOf(from, at), minOf(now, back))
        return true
    }

    /** Segments next to each other with delays nobody could tell apart become one (the earlier one's delay). */
    private fun joinNeighbours() {
        var k = 1
        while (k < map.size) {
            if (abs(map[k].delayMs - map[k - 1].delayMs) < TUNE_MS) {
                floors.remove(map[k].fromMs)
                map.removeAt(k)
            } else {
                k++
            }
        }
    }

    // ---- Small helpers ----------------------------------------------------------

    private fun waiting(now: Long) = Decision(Outcome.WAIT, segments, delayAt(now) ?: learnCenterMs, now, now, now, null, Double.NaN, "")

    /** Sure by [SyncMatcher], and not at the edge of the shifts tried: a best there is likely a slope up to one beyond. */
    /**
     * The best shift within [RANGE_MS] of the delay in use [cur], and the reach it was judged in. Among the thousands
     * of shifts that far, chance alone makes some match nearly as well as a real but modest one (sparse talking, music
     * under it), so a change of a few seconds can stay "not sure" for minutes. Judged again among the shifts within
     * [NEAR_MS] only, by the same rule, it counts when it is also the very best of the whole reach: never over a
     * better match further off. Everything after (two matches agreeing, fresh talking, beating the delay in use) is
     * asked of it as of any other.
     */
    private fun search(h: SyncMatcher.Heard, cur: Long): Pair<SyncMatcher.Result, Long> {
        val wide = SyncMatcher.match(h, lines, cur, RANGE_MS)
        if (sure(wide, cur, RANGE_MS)) return wide to RANGE_MS
        if (nearMs <= EDGE_MS) return wide to RANGE_MS
        val near = SyncMatcher.match(h, lines, cur, nearMs)
        if (sure(near, cur, nearMs) && abs(near.delayMs - wide.delayMs) <= AGREE_MS) return near to nearMs
        return wide to RANGE_MS
    }

    private fun sure(res: SyncMatcher.Result, centerMs: Long, rangeMs: Long) =
        res.confident && abs(res.delayMs - centerMs) <= rangeMs - EDGE_MS

    private fun unsureNote(res: SyncMatcher.Result) = if (res.confident) "at the edge of the shifts tried" else ""

    /** How much film to let by between follow-ups of a hint: none at first, then doubling up to [PERIOD_MS] while unsure. */
    private fun spacing(): Long = if (misses <= FREE_MISSES) 0 else minOf(PERIOD_MS, TRY_MS shl minOf(misses - FREE_MISSES, 8))

    private fun indexAt(ms: Long): Int = indexAt(map, ms)

    private fun talkingMs(tl: SpeechTimeline, from: Long, to: Long): Long =
        if (to <= from) 0 else tl.speech.get(frame(from), frame(to)).cardinality() * 10L

    private fun heardMs(tl: SpeechTimeline, from: Long, to: Long): Long =
        if (to <= from) 0 else tl.heard.get(frame(from), frame(to)).cardinality() * 10L

    /**
     * A sure match agreed with [delay] on [from]..[now]: checked as far as the talking says it fits (a break near the
     * end isn't), and found [exactly] right there when the match was a check of a delay already in use.
     */
    private fun checked(tl: SpeechTimeline, delay: Long, from: Long, now: Long, exactly: Boolean) {
        val to = fitEnd(tl, delay, from, now)
        verify(from, to)
        if (exactly && sec(to) > sec(from)) exact.set(sec(from), sec(to))
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
        /** The nearer reach a change is judged in again when the whole [RANGE_MS] can't call it (see [search]). */
        const val NEAR_MS = 20_000L
        private const val NEAR = "judged near the delay in use"
        /** Two sure matches this close are the same delay. */
        const val AGREE_MS = 100L
        /** Changes smaller than this only fine-tune the delay in use (it's still about right where it is). */
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
        // New talking a follow-up of a hint waits for.
        private const val FRESH_TALK_MS = 4_000L
        // Talking the second of two agreeing matches must have that the first didn't, and how far the delay must stand
        // out on it alone. On made-up films, a wrong delay stands out that far 1 to 3% of the time; the right one does
        // 70% of the time on 10 s of talking and 90% on 20 s (OffsetTrackerTest prints these).
        private const val CONFIRM_TALK_MS = 10_000L
        private const val FRESH_Z = 2.0
        // Confirming from talking already heard takes this much of the latest talking for the second stretch.
        private const val AT_ONCE_TALK_MS = 2 * CONFIRM_TALK_MS
        private const val AT_ONCE = "sure on two stretches at once"
        // How much better than the delay in use a new one a second or more away must fit the same stretch: at least
        // this much more overlap, and at least this share of its own.
        private const val SMALL_SHIFT_MS = 1_000L
        private const val MIN_GAIN = 0.05
        private const val GAIN_SHARE = 0.25
        // A best match this close to the edge of the shifts tried isn't trusted.
        private const val EDGE_MS = 5_000L
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
        // Follow-ups of a hint that weren't sure of anything, before they're spaced out; the step they start from.
        private const val FREE_MISSES = 8
        private const val TRY_MS = 15_000L
        // Fine-tuning the delay in use: two sure matches agreeing on one at least this far from it (what the one
        // recheck two thirds in used to move it for). Neighbouring delays closer than this are one.
        private const val TUNE_MS = 150L
        // A fine-tuning after a part found exactly right, which makes a segment of its own: at least this far.
        private const val SPLIT_TUNE_MS = 300L
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
        // On Resync, a delay that still fitted this close to now fits up to now.
        private const val FITS_TO_NOW_MS = 30_000L
        // Where a delay starts fitting: each block's cost, as a share of the stretch's average fit.
        private const val FIT_COST = 0.2

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
