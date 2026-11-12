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
    /** Jumps remembered from other episodes of the show, tried first after a break (see [guess]). */
    knownJumps: List<Long> = emptyList(),
    /** Whether the delays worth trying first are tried ([guess]); off, every change is searched for. */
    private val guessing: Boolean = true,
    /** How a guess is proven ([guess]); tests try others. By default [GuessRules.CLEAN] when [clean], else the usual. */
    rules: GuessRules? = null,
    /**
     * The timeline is the film's own subtitle track ([ReferenceSync]), not talking heard: matched against it, the right
     * delay overlaps nearly perfectly, so a match that near-perfect is sure on far less ([cleanSure]), and the delay in
     * use not fitting any more shows on less too.
     */
    val clean: Boolean = false,
    /**
     * While nothing is known: the delay the show's last episode started with (the same subtitles' source), tried first
     * as a guess is ([priorStep]), so a new episode is usually lined up within its first few lines.
     */
    private val priorMs: Long? = null,
) {

    val rules: GuessRules = rules ?: if (clean) GuessRules.CLEAN else GuessRules()

    /**
     * How a delay tried first is proven ([guess]): the first look on the latest [talkMs] of talking with [lines] lines,
     * standing out by [z1]; the second on [freshMs] of talking since (waiting up to [freshMaxMs]) by [z2]; on both
     * looks, the delay in use overlapping less than [misfitR] (it has stopped fitting), and on both together the best
     * of every shift in reach being the guess, the next best at most [runnerUp] of it. With [needsHint], only after a
     * hint; with [fromHint], on talking after it only.
     */
    data class GuessRules(
        val talkMs: Long = 10_000,
        val lines: Int = 3,
        val z1: Double = 2.5,
        val freshMs: Long = 6_000,
        val freshMaxMs: Long = 20_000,
        val z2: Double = 2.5,
        val misfitR: Double = Double.POSITIVE_INFINITY,
        val runnerUp: Double = 0.9,
        val needsHint: Boolean = true,
        val fromHint: Boolean = true,
        /** The first look takes up to this much of the talking since the hint (at least [talkMs]). */
        val talkMaxMs: Long = talkMs,
    ) {
        companion object {
            /**
             * On the film's own subtitles: the right guess overlaps nearly perfectly and a wrong one hardly at all, so
             * half the lines prove it as well (and it's back in seconds after a jump).
             */
            val CLEAN = GuessRules(talkMs = 6_000, lines = 3, z1 = 2.0, freshMs = 4_000, freshMaxMs = 15_000, z2 = 2.0, talkMaxMs = 15_000)
        }
    }

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

    /** A guess [delayMs] that stood out on the talking [fromMs]..[toMs] in the segment from [segmentFromMs] ([guess]). */
    private class Tried(val delayMs: Long, val segmentFromMs: Long, val fromMs: Long, val toMs: Long)

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
    // After Resync: the delay the viewer turned down, and where. It only comes back on talking heard after the press:
    // from [freshFromMs] on (where it was pressed, or past what had been heard ahead of there by then).
    private var rejectedMs: Long? = null
    private var resyncAtMs = -1L
    private var freshFromMs = -1L
    private val knownJumps = knownJumps.filter { abs(it) >= MIN_SHIFT_MS && abs(it) <= RANGE_MS - EDGE_MS }.take(GUESS_MAX)
    // Where the sound's format changed (an ad spliced in starts and ends with one, often): ad lengths to try.
    private val formats = TreeSet<Long>()
    // A guess that stood out on the latest talking ([guess]): it moves the delay once talking since bears it out too.
    private var tried: Tried? = null
    // Talking up to here went into a guess that wasn't borne out: the next one is looked for on talking after it.
    private var guessFloorMs = -1L
    // Film played since the last full step: the matching that costs is done every [FULL_STEP_MS], guesses every step.
    private var sinceFullStepMs = 0L
    // Where the talking a guess was proven on began ([guess]): once there's plenty more, the delay is fitted exactly.
    private var polishFromMs = -1L
    // While learning: the prior ([priorMs]) standing out once, waiting for talking since to bear it out ([priorStep]);
    // talking up to [priorFloorMs] went into a look at it that wasn't borne out.
    private var priorTried: Tried? = null
    private var priorFloorMs = -1L
    // Changes found after a jump past them, by where the new delay starts: where the change may begin (the start of the
    // stretch before it never heard). Once that stretch is heard, the change is placed in it ([refine]).
    private val open = java.util.TreeMap<Long, Long>()
    // How much of each such stretch had been heard at the last look, so it's only looked at again with more.
    private val openHeard = HashMap<Long, Int>()

    val segments: List<Segment> get() = map.toList()

    /**
     * Finding the first delay: the one a look was sure of, waiting for the next look to confirm it; else null. Auto sync
     * puts it in use meanwhile (subtitles right seconds sooner), and if the next looks find another, that one takes over.
     */
    val tentativeMs: Long? get() = if (map.isEmpty()) candidate?.takeIf { it.segmentFromMs < 0 }?.delayMs else null
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
        // A jump: one stretch never heard of GAP_MS or more (as for a seek), not pieces dropped here and there.
        if (longestUnheard(tl, frame(lastOk), frame(s.fromMs)) * 10L >= GAP_MS) lastOk else s.fromMs
    }

    /** The longest run of 10 ms marks in [from] until [to] never heard; stops once one is as long as a jump. */
    private fun longestUnheard(tl: SpeechTimeline, from: Int, to: Int): Int {
        var longest = 0
        var f = tl.heard.nextClearBit(from)
        while (f < to) {
            val next = tl.heard.nextSetBit(f)
            val end = if (next < 0 || next > to) to else next
            longest = maxOf(longest, end - f)
            if (longest * 10L >= GAP_MS) break
            f = tl.heard.nextClearBit(end)
        }
        return longest
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
        sinceFullStepMs += playedMs
        // Gone back before it: nothing between there and here to look at.
        if (suspectMs > nowMs) suspectMs = -1
        // Gone back before the first of two matches: a second would be of another stretch.
        candidate?.let { if (it.toMs > nowMs) candidate = null }
        tried?.let { if (it.toMs > nowMs) tried = null }
        if (guessFloorMs > nowMs) guessFloorMs = -1
        // Gone back before where Resync was pressed: elsewhere in the film, the delay turned down may well be right.
        if (nowMs < resyncAtMs) rejectedMs = null
        // Called every [STEP_MS]; all but guessing waits for [FULL_STEP_MS], as it always did.
        val full = sinceFullStepMs >= FULL_STEP_MS
        if (full) sinceFullStepMs = 0
        if (priorFloorMs > nowMs) priorFloorMs = -1
        priorTried?.let { if (it.toMs > nowMs) priorTried = null }
        if (map.isEmpty()) {
            learnPlayedMs += playedMs
            priorStep(timeline, nowMs)?.let { return it }
            // On the film's own subtitles a match costs little and is sure on a few lines: looked at every step.
            return if (full || clean) learnStep(timeline, nowMs) else waiting(nowMs)
        }
        refine(timeline, nowMs)?.let { return it }
        return followStep(timeline, nowMs, full)
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
        if (why == Hint.FORMAT) {
            formats.add(atMs)
            while (formats.size > MAX_BREAKS) formats.pollFirst()
        }
        noteBreak(atMs)
    }

    /** The jumps this film made (each delay less the one before it), for remembering with the show. */
    fun jumps(): List<Long> = map.zipWithNext { a, b -> b.delayMs - a.delayMs }.filter { abs(it) >= MIN_SHIFT_MS }

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
     * Listening ahead, the film up to [heardToMs] was heard before the press, though it's after [atMs]. Without [reject]
     * (auto sync turned on, not Resync: nobody said the delay in use is wrong), [centerMs] is only where it looks from.
     */
    fun relearn(atMs: Long, centerMs: Long, timeline: SpeechTimeline, heardToMs: Long = -1, reject: Boolean = true) {
        val lastBreak = breaks.floor(atMs) ?: 0L
        val fit = fitEnd(timeline, centerMs, lastBreak, atMs)
        val since = if (atMs - fit <= FITS_TO_NOW_MS) lastBreak else fit
        map.clear()
        verified.clear()
        exact.clear()
        floors.clear()
        candidate = null
        tried = null
        guessFloorMs = -1
        polishFromMs = -1
        priorTried = null
        open.clear()
        openHeard.clear()
        lastCheckToMs = -1
        learnCenterMs = centerMs
        learnRangeMs = RANGE_MS
        learnPlayedMs = 0
        sinceCheckMs = 0
        // The first step after Resync matches at once, with what was heard already.
        sinceFullStepMs = FULL_STEP_MS
        misses = 0
        suspectMs = since
        suspectWhy = Hint.RESYNC
        rejectedMs = if (reject) centerMs else null
        resyncAtMs = atMs
        freshFromMs = maxOf(atMs, heardToMs)
    }

    /**
     * Resync once delays are known: the stretch around [atMs] (from the change before it, or where the delay in use
     * stopped fitting, to the next change) is matched again, and every other delay is kept. With [reject], the viewer
     * says the delay in use is wrong there, so it only comes back on talking heard after the press; without (lining up
     * against the film's own subtitles, which can't be wrong the way a guess from the talking can), it's simply checked
     * again. Given [nowMs] (where the timeline ends, ahead of where the film plays), that's done at once and the decision
     * returned; else at the next [step]. Null with nothing known yet (then it's [relearn]). Listening ahead, the film up
     * to [heardToMs] was heard before the press, though it's after [atMs].
     */
    fun recheck(atMs: Long, timeline: SpeechTimeline, reject: Boolean, nowMs: Long? = null, heardToMs: Long = -1): Decision? {
        if (map.isEmpty()) return null
        val i = indexAt(atMs)
        val seg = map[i]
        val end = map.getOrNull(i + 1)?.fromMs ?: Long.MAX_VALUE
        val lastBreak = maxOf(seg.fromMs, breaks.floor(atMs) ?: 0L)
        val fit = if (reject) fitEnd(timeline, seg.delayMs, lastBreak, atMs) else atMs
        val since = if (atMs - fit <= FITS_TO_NOW_MS) lastBreak else fit
        // What was found right in this stretch is to be found again.
        val to = if (end == Long.MAX_VALUE) maxOf(verified.length(), exact.length()) else sec(end)
        if (to > sec(since)) {
            verified.clear(sec(since), to)
            exact.clear(sec(since), to)
        }
        candidate = null
        tried = null
        guessFloorMs = -1
        polishFromMs = -1
        lastCheckToMs = -1
        floors.remove(seg.fromMs)
        // Matched at the next full step, whatever the spacing was.
        sinceCheckMs = PERIOD_MS
        sinceFullStepMs = FULL_STEP_MS
        misses = 0
        suspectMs = since
        suspectWhy = Hint.RESYNC
        if (reject) {
            rejectedMs = seg.delayMs
            resyncAtMs = atMs
            freshFromMs = maxOf(atMs, heardToMs)
        } else {
            rejectedMs = null
        }
        val now = nowMs ?: return null
        val hi = minOf(now, end)
        val from = maxOf(since, hi - WINDOW_MS)
        if (hi <= from || talkingMs(timeline, from, hi) < minTalkMs) {
            return Decision(Outcome.NOT_SURE, segments, seg.delayMs, hi, from, hi, null, Double.NaN, "checking again: too little to go on yet")
        }
        return check(timeline, i, from, hi)
    }

    // ---- Finding the first delay ----------------------------------------------

    /**
     * While learning, the delay the show's last episode started with ([priorMs]) is tried first, as a guess after a
     * break is ([guess]): it must stand out on the latest [GuessRules.talkMs] of talking, then again on
     * [GuessRules.freshMs] of talking since, and on both together be the best of every shift within [learnRangeMs] of
     * it. Not borne out by [GuessRules.freshMaxMs] of talking since, it's looked at again on talking after that; the
     * search for the first delay goes on meanwhile as ever.
     */
    private fun priorStep(tl: SpeechTimeline, now: Long): Decision? {
        val prior = priorMs ?: return null
        // After Resync the viewer has said what's in use is wrong: no shortcut then.
        if (rejectedMs != null) return null
        val start = maxOf(0L, suspectMs, heardFrom(tl, now), priorFloorMs)
        priorTried?.let { t ->
            if (t.fromMs < start) priorTried = null else return confirmPrior(tl, t, now)
        }
        val b = maxOf(start, talkFrom(tl, now, rules.talkMaxMs))
        if (b < 0 || talkingMs(tl, b, now) < rules.talkMs || lines.onScreen(prior, b, now) < rules.lines) return null
        val h = SyncMatcher.heard(tl.speech, tl.heard, frame(b), frame(now) + 1) ?: return null
        if (SyncMatcher.rAt(h, lines, prior) < MIN_GUESS_R) return null
        val z = SyncMatcher.zAt(h, lines, prior, prior, learnRangeMs)
        if (z < rules.z1) return null
        priorTried = Tried(prior, -1, b, now)
        return Decision(Outcome.NOT_SURE, segments, prior, now, b, now, null, Double.NaN,
            "the last episode's delay, stands out once (z=${"%.1f".format(java.util.Locale.ROOT, z)})")
    }

    /** The second look at the last episode's delay ([priorStep]): on talking since the first, and on both together. */
    private fun confirmPrior(tl: SpeechTimeline, t: Tried, now: Long): Decision? {
        val fresh = talkingMs(tl, t.toMs, now)
        if (fresh < rules.freshMs) return null
        val note = "the last episode's delay"
        fun notYet(why: String): Decision? {
            if (fresh < rules.freshMaxMs) return null
            priorTried = null
            priorFloorMs = now
            return Decision(Outcome.NOT_SURE, segments, t.delayMs, now, t.fromMs, now, null, Double.NaN, "$note, $why")
        }
        val h2 = SyncMatcher.heard(tl.speech, tl.heard, frame(t.toMs), frame(now) + 1) ?: return notYet("too little heard since")
        if (SyncMatcher.rAt(h2, lines, t.delayMs) < MIN_GUESS_R) return notYet("doesn't fit the talking since")
        val z2 = SyncMatcher.zAt(h2, lines, t.delayMs, t.delayMs, learnRangeMs)
        if (z2 < rules.z2) return notYet("not borne out since (z=${"%.1f".format(java.util.Locale.ROOT, z2)})")
        val hp = SyncMatcher.heard(tl.speech, tl.heard, frame(t.fromMs), frame(now) + 1) ?: return notYet("too little heard")
        val res = SyncMatcher.match(hp, lines, t.delayMs, learnRangeMs)
        if (abs(res.delayMs - t.delayMs) > GUESS_SLACK_MS || res.r < MIN_GUESS_R || res.runnerUp > rules.runnerUp * res.r) {
            return notYet("another shift fits both looks as well")
        }
        priorTried = null
        val fitted = SyncMatcher.match(hp, lines, res.delayMs, GUESS_REFINE_MS).delayMs.takeIf { abs(it - t.delayMs) <= GUESS_SLACK_MS } ?: res.delayMs
        val delay = if (SyncMatcher.rAt(hp, lines, t.delayMs) >= SyncMatcher.rAt(hp, lines, fitted) - GUESS_PRIOR_R) t.delayMs else fitted
        map.add(Segment(0, delay))
        candidate = null
        suspectMs = -1
        checked(tl, delay, t.fromMs, now, exactly = false)
        polishFromMs = t.fromMs
        return Decision(Outcome.LEARNED, segments, delay, now, t.fromMs, now, res, Double.NaN,
            "$note, borne out (z=${"%.1f".format(java.util.Locale.ROOT, z2)} since)")
    }

    private fun learnStep(tl: SpeechTimeline, now: Long): Decision {
        val c = candidate
        val from = maxOf(0L, suspectMs, now - LEARN_WINDOW_MS, c?.fitsFromMs ?: 0L)
        val due = if (c != null) talkingMs(tl, c.toMs, now) >= c.waitTalkMs else lastCheckToMs !in from..now || talkingMs(tl, lastCheckToMs, now) >= FRESH_TALK_MS
        val d = if (!due || talkingMs(tl, from, now) < minTalkMs) waiting(now) else learnCheck(tl, from, now)
        if (d.outcome != Outcome.LEARNED && learnPlayedMs >= LEARN_MAX_MS) {
            return Decision(Outcome.GAVE_UP, segments, d.delayMs, now, from, now, d.result, Double.NaN, "no sure match in ${LEARN_MAX_MS / 60_000} min")
        }
        return d
    }

    private fun learnCheck(tl: SpeechTimeline, wholeFrom: Long, now: Long): Decision {
        sinceCheckMs = 0
        lastCheckToMs = now
        var from = wholeFrom
        val h = SyncMatcher.heard(tl.speech, tl.heard, frame(from), frame(now) + 1) ?: return waiting(now)
        // By the talking, how far the best stands out is judged against shifts up to [RANGE_MS] away (see SyncMatcher.match)
        val zRange = if (clean) learnRangeMs else maxOf(learnRangeMs, RANGE_MS)
        var res = SyncMatcher.match(h, lines, learnCenterMs, learnRangeMs, zRange)
        // Not sure of the whole stretch: a break inside it (a bumper: the talking before and after it fits delays a second
        // or two apart) blurs both, until the stretch has moved on past it, minutes later. The latest part alone, after
        // where such a break would be, is looked at too: sure of it, that's what's gone by (confirmed as any first delay).
        if (!clean && !sure(res, learnCenterMs, learnRangeMs)) {
            for (span in LEARN_RECENT_MS) {
                val recentFrom = now - span
                if (recentFrom - from < LEARN_RECENT_MIN_CUT_MS || talkingMs(tl, recentFrom, now) < minTalkMs) continue
                val hr = SyncMatcher.heard(tl.speech, tl.heard, frame(recentFrom), frame(now) + 1) ?: continue
                val rr = SyncMatcher.match(hr, lines, learnCenterMs, learnRangeMs, zRange)
                if (sure(rr, learnCenterMs, learnRangeMs)) {
                    from = recentFrom
                    res = rr
                    break
                }
            }
        }
        fun decide(o: Outcome, note: String = "") = Decision(o, segments, res.delayMs, now, from, now, res, Double.NaN, note)
        if (!sure(res, learnCenterMs, learnRangeMs)) {
            looselyConfirmed(tl, res, from, now)?.let { return it }
            candidate = null
            return decide(Outcome.NOT_SURE, unsureNote(res))
        }
        // The delay the viewer turned down with Resync, from talking heard before: listens afresh for it.
        val rejected = rejectedMs
        if (rejected != null && abs(res.delayMs - rejected) < MIN_SHIFT_MS && from < freshFromMs) {
            candidate = null
            suspectMs = minOf(freshFromMs, now)
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
                // The first delay: confirmed on a little fresh talking, as the subtitles are wrong until it is
                candidate = Candidate(res.delayMs, -1, from, now, fits).also { if (!clean) it.waitTalkMs = LEARN_CONFIRM_TALK_MS }
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

    private fun followStep(tl: SpeechTimeline, now: Long, full: Boolean): Decision {
        val i = indexAt(now)
        val seg = map[i]
        if (suspectMs >= 0 && (suspectMs < seg.fromMs || now - suspectMs > SUSPECT_FOR_MS)) suspectMs = -1
        candidate?.let { if (it.segmentFromMs != seg.fromMs) candidate = null }
        tried?.let { if (it.segmentFromMs != seg.fromMs) tried = null }
        val floor = floors[seg.fromMs]?.takeIf { it < now } ?: seg.fromMs
        if (full && suspectMs < 0 && candidate == null) lookForBreaks(tl, now, seg.delayMs, floor)
        if (full) polish(tl, i, now)?.let { return it }
        if (guessing && (suspectMs >= 0 || !rules.needsHint)) guess(tl, i, floor, now)?.let { return it }
        if (!full) return waiting(now)
        val c = candidate
        // A second match looks only at the talking the first fitted from: after the break, not the break itself.
        val from = maxOf(floor, suspectMs, now - WINDOW_MS, c?.fitsFromMs ?: 0L)
        val due = when {
            c != null -> talkingMs(tl, c.toMs, now) >= c.waitTalkMs
            // Something to follow up: again as soon as there's new talking to go on (none in silence or a quiet ad).
            suspectMs >= 0 -> sinceCheckMs >= spacing() && (lastCheckToMs !in from..now || talkingMs(tl, lastCheckToMs, now) >= FRESH_TALK_MS)
            else -> sinceCheckMs >= PERIOD_MS && !mostlyVerified(from, now)
        }
        if (!due || talkingMs(tl, from, now) < minTalkMs) return waiting(now)
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
        // After Resync: the delay the viewer turned down, from talking heard before the press, doesn't count.
        val rejected = rejectedMs
        if (rejected != null && abs(res.delayMs - rejected) < TUNE_MS) {
            if (from < freshFromMs) {
                candidate = null
                suspectMs = minOf(freshFromMs, now)
                suspectWhy = Hint.RESYNC
                return decide(Outcome.NOT_SURE, cur, "the delay Resync turned down: only talking from the press on can bring it back")
            }
            rejectedMs = null
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
            rejectedMs = null
            noteBreak(at)
            noteOpen(tl, at, seg.fromMs)
        }
        joinNeighbours()
        // The stretch reported is the one the proof went by.
        return Decision(if (close) Outcome.TUNED else Outcome.MOVED, segments, delayAt(at) ?: res.delayMs, at, proofFrom, now, res, rHere, note)
    }

    /**
     * The quick way back after a break. A few delays are worth trying before searching every shift: the delay in use
     * plus a jump this film made before (or other episodes of the show did: bumpers and ads are often the same length
     * each time), plus two such jumps (a jump past both), the delays used elsewhere in the film, and the delay plus the
     * length of an ad the sound's format marked at both ends. Being few and named beforehand, one of them standing out
     * means far more than the best of every shift doing so, so they're proven on far less talking, in two looks that
     * share none of it ([rules]):
     *  1. On the latest [GuessRules.talkMs] of talking (never across a jump, nor before the segment's own proof, nor
     *     before the hint), the guess that fits best must beat the delay in use as clearly as any change must, and
     *     stand out from all the shifts in reach by [GuessRules.z1].
     *  2. On talking since, at least [GuessRules.freshMs] of it, that same guess must again beat the delay in use and
     *     stand out by [GuessRules.z2]; and on both looks together, the best of every shift in reach must be that very
     *     guess. Not borne out by [GuessRules.freshMaxMs] of talking since, it's dropped, and the next guess is looked
     *     for on talking after that.
     * Only while something says the timing may have changed (a hint: the sound's format, a quiet stretch where lines
     * were expected, the delay in use no longer fitting, a jump) and from there on: in ordinary talking, a wrong guess
     * fits a few lines now and then by chance, and looked at every few seconds all through the film, that adds up.
     * Looked at every [STEP_MS] of film, so it's in use as soon as the talking allows. Else the search goes on as before.
     */
    private fun guess(tl: SpeechTimeline, i: Int, floor: Long, now: Long): Decision? {
        val seg = map[i]
        val cur = seg.delayMs
        // Never on talking from both sides of a jump, nor on talking a guess that wasn't borne out already had.
        val start = maxOf(floor, heardFrom(tl, now), guessFloorMs, if (rules.fromHint) suspectMs else -1L)
        tried?.let { t ->
            if (t.fromMs < start || t.delayMs == cur) tried = null else return confirmGuess(tl, i, t, now)
        }
        val tries = guesses(cur, now)
        if (tries.isEmpty()) return null
        val b = maxOf(start, talkFrom(tl, now, rules.talkMaxMs))
        if (b < 0 || talkingMs(tl, b, now) < rules.talkMs) return null
        val h = SyncMatcher.heard(tl.speech, tl.heard, frame(b), frame(now) + 1) ?: return null
        val rHere = SyncMatcher.rAt(h, lines, cur)
        var best = 0L
        var bestR = Double.NEGATIVE_INFINITY
        for (g in tries) {
            if (lines.onScreen(g, b, now) < rules.lines) continue
            val r = SyncMatcher.rAt(h, lines, g)
            if (r > bestR) { bestR = r; best = g }
        }
        if (rHere >= rules.misfitR || bestR < MIN_GUESS_R || bestR - rHere < maxOf(MIN_GAIN, GAIN_SHARE * bestR)) return null
        val z = SyncMatcher.zAt(h, lines, best, cur, RANGE_MS)
        if (z < rules.z1) return null
        tried = Tried(best, seg.fromMs, b, now)
        return Decision(Outcome.NOT_SURE, segments, best, now, b, now, null, rHere,
            "tried first (${SyncMatcher.formatShift(best - cur)}), stands out once (z=${"%.1f".format(java.util.Locale.ROOT, z)})")
    }

    /** The second look at guess [t] ([guess]): on talking since its first, and on both together. */
    private fun confirmGuess(tl: SpeechTimeline, i: Int, t: Tried, now: Long): Decision? {
        val seg = map[i]
        val cur = seg.delayMs
        val fresh = talkingMs(tl, t.toMs, now)
        if (fresh < rules.freshMs) return null
        val note = "tried first (${SyncMatcher.formatShift(t.delayMs - cur)})"
        fun notYet(why: String): Decision? {
            if (fresh < rules.freshMaxMs) return null
            tried = null
            guessFloorMs = now
            return Decision(Outcome.NOT_SURE, segments, t.delayMs, now, t.fromMs, now, null, Double.NaN, "$note, $why")
        }
        val h2 = SyncMatcher.heard(tl.speech, tl.heard, frame(t.toMs), frame(now) + 1) ?: return notYet("too little heard since")
        val r2 = SyncMatcher.rAt(h2, lines, t.delayMs)
        val rHere2 = SyncMatcher.rAt(h2, lines, cur)
        if (rHere2 >= rules.misfitR || r2 - rHere2 < maxOf(MIN_GAIN, GAIN_SHARE * r2)) return notYet("not better than the delay in use since")
        val z2 = SyncMatcher.zAt(h2, lines, t.delayMs, cur, RANGE_MS)
        if (z2 < rules.z2) return notYet("not borne out since (z=${"%.1f".format(java.util.Locale.ROOT, z2)})")
        // Both looks together: the best of every shift in reach is the guess itself (the same jump, give or take a
        // few frames), clear of the next best.
        val hp = SyncMatcher.heard(tl.speech, tl.heard, frame(t.fromMs), frame(now) + 1) ?: return notYet("too little heard")
        val res = SyncMatcher.match(hp, lines, cur, RANGE_MS)
        val rHere = SyncMatcher.rAt(hp, lines, cur)
        if (abs(res.delayMs - t.delayMs) > GUESS_SLACK_MS || res.r < MIN_GUESS_R || res.runnerUp > rules.runnerUp * res.r) {
            return notYet("another shift fits both looks as well")
        }
        tried = null
        // On all of it, the exact delay (to a tenth of a second or two); the same bumper again is most likely the very
        // same jump, so the guess itself unless the talking clearly prefers that.
        val fitted = SyncMatcher.match(hp, lines, res.delayMs, GUESS_REFINE_MS).delayMs.takeIf { abs(it - t.delayMs) <= GUESS_SLACK_MS } ?: res.delayMs
        val delay = if (SyncMatcher.rAt(hp, lines, t.delayMs) >= SyncMatcher.rAt(hp, lines, fitted) - GUESS_PRIOR_R) t.delayMs else fitted
        if (map.size >= MAX_SEGMENTS) return null
        val proofFrom = t.fromMs
        val at = moveAt(tl, i, delay, proofFrom, now)
        if (!put(tl, i, at, delay, proofFrom, now, verified)) return null
        if (proofFrom > at) floors[maxOf(at, seg.fromMs)] = proofFrom
        candidate = null
        suspectMs = -1
        rejectedMs = null
        misses = 0
        sinceCheckMs = 0
        lastCheckToMs = now
        noteBreak(at)
        noteOpen(tl, at, seg.fromMs)
        joinNeighbours()
        polishFromMs = proofFrom
        return Decision(Outcome.MOVED, segments, delayAt(at) ?: delay, at, proofFrom, now, res, rHere,
            "$note, borne out (z=${"%.1f".format(java.util.Locale.ROOT, z2)} since)")
    }

    /**
     * Where the stretch heard up to [now] began: after the last part not heard for [GAP_MS] or more (a jump), looking
     * back at most [WINDOW_MS]. Pieces dropped here and there don't end it.
     */
    private fun heardFrom(tl: SpeechTimeline, now: Long): Long {
        val limit = frame(maxOf(0L, now - WINDOW_MS))
        var f = frame(now)
        while (f > limit) {
            val gapEnd = tl.heard.previousClearBit(f)
            if (gapEnd < limit) return limit * 10L
            val before = tl.heard.previousSetBit(gapEnd)
            if (before < 0 || (gapEnd - before) * 10L >= GAP_MS) return (gapEnd + 1) * 10L
            f = before
        }
        return limit * 10L
    }

    /**
     * A delay a guess moved to was proven on under a minute of talking: right to a few tenths of a second. Once there's
     * [POLISH_TALK_MS] of talking for it, it's fitted exactly, within [GUESS_SLACK_MS] of where it is, once.
     */
    private fun polish(tl: SpeechTimeline, i: Int, now: Long): Decision? {
        val from = polishFromMs
        if (from < 0) return null
        if (now < from || indexAt(from) != i) { polishFromMs = -1; return null }
        if (talkingMs(tl, from, now) < POLISH_TALK_MS) return null
        polishFromMs = -1
        val seg = map[i]
        val h = SyncMatcher.heard(tl.speech, tl.heard, frame(from), frame(now) + 1) ?: return null
        val m = SyncMatcher.match(h, lines, seg.delayMs, GUESS_SLACK_MS)
        val rHere = SyncMatcher.rAt(h, lines, seg.delayMs)
        val off = abs(m.delayMs - seg.delayMs)
        // Only where the talking clearly prefers another (a guess is most likely the very same jump again).
        val rThere = SyncMatcher.rAt(h, lines, m.delayMs)
        if (off < TUNE_MS || off >= GUESS_SLACK_MS || m.r < MIN_GUESS_R || rThere - rHere < GUESS_PRIOR_R) return null
        map[i] = Segment(seg.fromMs, m.delayMs)
        verify(from, now)
        joinNeighbours()
        return Decision(Outcome.TUNED, segments, m.delayMs, seg.fromMs, from, now, m, rHere, "a delay tried first, fitted exactly")
    }

    /** The delays worth trying first while [cur] is in use at [now] (see [guess]), nearest-to-likely first. */
    private fun guesses(cur: Long, now: Long): List<Long> {
        val out = ArrayList<Long>()
        fun add(d: Long) {
            val shift = abs(d - cur)
            if (shift >= MIN_SHIFT_MS && shift <= RANGE_MS - EDGE_MS && out.none { abs(it - d) < GUESS_SAME_MS }) out += d
        }
        val jumps = (jumps() + knownJumps)
        for (j in jumps) add(cur + j)
        for (s in map) add(s.delayMs)
        // Ads the sound's format marked at both ends, ending before here
        var prev = -1L
        for (f in formats.headSet(now, true)) {
            if (prev >= 0 && f - prev in AD_MIN_MS..AD_MAX_MS) add(cur + (f - prev))
            prev = f
        }
        for (a in jumps.indices) for (c in a until jumps.size) add(cur + jumps[a] + jumps[c])
        return out.take(GUESS_MAX)
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
        val from = maxOf(floor, now - misfitWindowMs)
        if (mostlyVerified(from, now)) return
        if (fitsLatest(tl, cur, from, now)) return
        hint(fitEnd(tl, cur, floor, now), Hint.MISFIT)
    }

    /** Whether [delay] fits the last minute and a half of [from]..[now]; true when there are too few lines or talking to say. */
    private fun fitsLatest(tl: SpeechTimeline, delay: Long, from: Long, now: Long): Boolean {
        val a = maxOf(from, now - misfitWindowMs)
        if (lines.onScreen(delay, a, now) < misfitLines || talkingMs(tl, a, now) < MISFIT_TALK_MS) return true
        val h = SyncMatcher.heard(tl.speech, tl.heard, frame(a), frame(now) + 1) ?: return true
        return SyncMatcher.rAt(h, lines, delay) >= MISFIT_R
    }

    /** The latest place before [now] where [delay] still fitted: where its running overlap peaked. */
    private fun fitEnd(tl: SpeechTimeline, delay: Long, floor: Long, now: Long): Long {
        val from = maxOf(floor, now - WINDOW_MS)
        val h = SyncMatcher.heard(tl.speech, tl.heard, frame(from), frame(now) + 1) ?: return maxOf(from, now - misfitWindowMs)
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
        if (clean && halvesAgree(tl, delay, center, range, from, now)) return true
        val m = talkFrom(tl, now, AT_ONCE_TALK_MS)
        if (m <= from || talkingMs(tl, from, m) < minTalkMs) return false
        val first = SyncMatcher.heard(tl.speech, tl.heard, frame(from), frame(m)) ?: return false
        val a = SyncMatcher.match(first, lines, center, range)
        if (!sure(a, center, range) || abs(a.delayMs - delay) > AGREE_MS) return false
        val latest = SyncMatcher.heard(tl.speech, tl.heard, frame(m), frame(now) + 1) ?: return false
        return SyncMatcher.zAt(latest, lines, delay, center, range) >= FRESH_Z
    }

    /**
     * On the film's own subtitles: [delay] borne out by each half of the lines in [from]..[now] on its own, overlapping
     * well ([CLEAN_HALF_R]) and standing out ([CLEAN_HALF_Z]) on both: two stretches that share nothing, as two matches
     * in a row are, without waiting for more to load.
     */
    private fun halvesAgree(tl: SpeechTimeline, delay: Long, center: Long, range: Long, from: Long, now: Long): Boolean {
        val total = talkingMs(tl, from, now)
        if (total < 2 * CLEAN_HALF_TALK_MS) return false
        val m = talkFrom(tl, now, total / 2)
        if (m <= from) return false
        for ((a, b) in listOf(from to m, m to now)) {
            if (lines.onScreen(delay, a, b) < CLEAN_HALF_LINES) return false
            val h = SyncMatcher.heard(tl.speech, tl.heard, frame(a), frame(b) + 1) ?: return false
            if (SyncMatcher.rAt(h, lines, delay) < CLEAN_HALF_R) return false
            if (SyncMatcher.zAt(h, lines, delay, center, range) < CLEAN_HALF_Z) return false
        }
        return true
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
     * The first delay, sure once, confirmed by the next look although that look isn't sure on its own: its best shift is
     * still within [LOOSE_AGREE_MS] of it, and on the talking heard since the first look alone the delay stands out
     * ([LOOSE_FRESH_Z]). Where music runs under nearly everything (a cartoon), every look's overlap stays low and two
     * sure looks in a row took minutes; one sure look and the next one pointing the same way is proof enough. Null when
     * it isn't confirmed this way.
     */
    private fun looselyConfirmed(tl: SpeechTimeline, res: SyncMatcher.Result, from: Long, now: Long): Decision? {
        val c = candidate ?: return null
        if (c.segmentFromMs >= 0 || abs(res.delayMs - c.delayMs) > LOOSE_AGREE_MS || res.r < LOOSE_MIN_R) return null
        if (talkingMs(tl, c.toMs, now) < c.waitTalkMs || rejectedMs?.let { abs(c.delayMs - it) < MIN_SHIFT_MS } == true) return null
        val h = SyncMatcher.heard(tl.speech, tl.heard, frame(c.toMs), frame(now) + 1) ?: return null
        val z = SyncMatcher.zAt(h, lines, c.delayMs, learnCenterMs, learnRangeMs)
        if (z < LOOSE_FRESH_Z || !fitsLatest(tl, c.delayMs, from, now)) return null
        map.add(Segment(0, c.delayMs))
        candidate = null
        suspectMs = -1
        rejectedMs = null
        checked(tl, c.delayMs, c.fitsFromMs, now, exactly = false)
        return Decision(Outcome.LEARNED, segments, c.delayMs, now, from, now, res, Double.NaN,
            "sure once, the next look agreeing (z=${"%.1f".format(java.util.Locale.ROOT, z)} since)")
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
            .let { pastGap(tl, it, now) }
            .coerceIn(seg.fromMs, now)
            // That close after the last change, the last change had the wrong delay instead.
            .let { if (it - seg.fromMs < MIN_SEGMENT_MS) seg.fromMs else it }
    }

    /**
     * A change found after a jump past it can be anywhere in the stretch never heard: [at] on (or in) such a stretch goes
     * to its end, so it keeps the delay before (nothing there said otherwise) until it's heard ([refine]).
     */
    private fun pastGap(tl: SpeechTimeline, at: Long, now: Long): Long {
        val f = frame(at)
        if (tl.heard.get(f)) return at
        val next = tl.heard.nextSetBit(f)
        if (next < 0 || next * 10L > now) return at
        val start = tl.heard.previousSetBit(f) + 1
        return if ((next - start) * 10L >= GAP_MS) next * 10L else at
    }

    /** A change put in at [at], the segment before starting at [prevFrom]: right after a stretch never heard, it's open. */
    private fun noteOpen(tl: SpeechTimeline, at: Long, prevFrom: Long) {
        val gap = unheardBefore(tl, at, prevFrom)
        if (gap >= 0 && at - gap >= GAP_MS && map.any { it.fromMs == at }) {
            open[at] = gap
            openHeard[at] = tl.heard.get(frame(gap), frame(at)).cardinality()
        }
    }

    /** Where the stretch never heard that ends at [at] begins (not before [lowest]); -1 when the film before [at] was heard. */
    private fun unheardBefore(tl: SpeechTimeline, at: Long, lowest: Long): Long {
        val f = frame(at) - 1
        if (f < frame(lowest) || tl.heard.get(f)) return -1
        return maxOf(lowest, (tl.heard.previousSetBit(f) + 1) * 10L)
    }

    /**
     * A change found after a jump past it ([open]): once more of the stretch skipped is heard (the viewer went back
     * into it), the change is placed in it, as for any change ([split]): both delays are proven already, only where one
     * gives way to the other is found here. Only moves the change within that stretch; what's still never heard keeps
     * the delay before.
     */
    private fun refine(tl: SpeechTimeline, now: Long): Decision? {
        if (open.isEmpty()) return null
        for ((from, lo) in open.entries.toList()) {
            val k = map.indexOfFirst { it.fromMs == from }
            val lowest = if (k > 0) maxOf(lo, map[k - 1].fromMs + MIN_SEGMENT_MS) else from
            if (k <= 0 || lowest >= from) {
                open.remove(from)
                openHeard.remove(from)
                continue
            }
            val heardNow = tl.heard.get(frame(lowest), frame(from)).cardinality()
            if (heardNow == openHeard[from] || heardNow * 10L < OPEN_HEARD_MS) continue
            openHeard[from] = heardNow
            val before = map[k - 1].delayMs
            val after = map[k].delayMs
            val s = split(tl, lowest, from, before, after)
            if (s < 0) continue
            var at = s.coerceIn(lowest, from)
            // Each side must bear its own delay out, not just suit it better than the other: a third delay in there
            // (two breaks skipped) is left to be found as any change is.
            val sides = listOf(supports(tl, lowest, at, before), supports(tl, at, from, after))
            if (false in sides || sides.all { it == null }) continue
            if (sides[1] == null) {
                // Only the delay before is borne out so far (what comes after isn't loaded or heard yet): the change is
                // past that, so the stretch it may be in narrows; it stays where it is until the delay after is heard too.
                if (from - at >= GAP_MS) {
                    open[from] = at
                    openHeard[from] = tl.heard.get(frame(at), frame(from)).cardinality()
                } else {
                    open.remove(from)
                    openHeard.remove(from)
                }
                continue
            }
            // Nothing heard from there on to the change: it may still be anywhere in that stretch, which keeps the delay
            // before; else it's open only if right after a stretch never heard.
            val next = tl.heard.nextSetBit(frame(at))
            val openFrom = if (next < 0 || next >= frame(from)) at.also { at = from } else unheardBefore(tl, at, lowest)
            open.remove(from)
            openHeard.remove(from)
            if (openFrom >= 0 && at - openFrom >= GAP_MS) {
                open[at] = openFrom
                openHeard[at] = tl.heard.get(frame(openFrom), frame(at)).cardinality()
            }
            if (at == from) continue
            map[k] = Segment(at, after)
            floors.remove(from)?.let { floors[at] = it }
            return Decision(Outcome.MOVED, segments, after, at, lowest, from, null, Double.NaN, "placed in the stretch skipped before")
        }
        return null
    }

    /**
     * Whether the talking heard in [from]..[to] bears [delay] out (the best of the shifts within a few seconds of it is
     * that delay), or null with too little talking there to say.
     */
    private fun supports(tl: SpeechTimeline, from: Long, to: Long, delay: Long): Boolean? {
        if (to <= from || talkingMs(tl, from, to) < OPEN_TALK_MS) return null
        val h = SyncMatcher.heard(tl.speech, tl.heard, frame(from), frame(to) + 1) ?: return null
        val m = SyncMatcher.match(h, lines, delay, OPEN_REACH_MS)
        return abs(m.delayMs - delay) <= GUESS_SLACK_MS && m.r >= MIN_GUESS_R
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
        // Next to a stretch not heard, a few seconds of talking can't tell the two apart: the split goes to its end, so
        // the stretch keeps the delay before (see [pastGap]).
        var k = bestK
        for (j in maxOf(1, bestK - SNAP_BLOCKS)..minOf(h.blocks - 1, bestK + SNAP_BLOCKS)) {
            if (h.blockMs(j) - h.blockMs(j - 1) >= GAP_MS) { k = j; if (j >= bestK) break }
        }
        if (k != bestK) return h.blockMs(k)
        val at = if (k < h.blocks) h.blockMs(k) else h.blockMs(h.blocks - 1) + 100
        // On the film's own subtitles, a break is where they show nothing for a while: the split goes in the middle of it.
        if (clean) lineless(tl, at, lo, hi)?.let { return it }
        return at
    }

    /**
     * On the film's own subtitles ([clean]): the middle of the longest stretch loaded with no line on screen that reaches
     * within [LINELESS_NEAR_MS] of [atMs], if one of at least [LINELESS_MS] does, between [lo] and [hi]. Lines either
     * side of it fit one delay or the other, but nothing in it says which, and its middle is furthest from both (the
     * film's own lines and the downloaded ones can start and end a second apart).
     */
    private fun lineless(tl: SpeechTimeline, atMs: Long, lo: Long, hi: Long): Long? {
        val first = frame(maxOf(lo, atMs - LINELESS_NEAR_MS))
        val last = frame(minOf(hi, atMs + LINELESS_NEAR_MS))
        var bestLen = 0
        var bestMiddle = -1
        var f = first
        while (f < last) {
            // A run with no line, loaded all through: from after the line before it to the next line (or the end).
            val start = tl.speech.nextClearBit(f)
            if (start >= last) break
            val end = minOf(tl.speech.nextSetBit(start).let { if (it < 0) Int.MAX_VALUE else it }, frame(hi))
            val from = maxOf(tl.speech.previousSetBit(start) + 1, frame(lo))
            val loaded = tl.heard.get(from, maxOf(from, end)).cardinality()
            if (end - from == loaded && end - from > bestLen) { bestLen = end - from; bestMiddle = (from + end) / 2 }
            f = maxOf(end, start + 1)
        }
        return if (bestLen * 10L >= LINELESS_MS) bestMiddle * 10L else null
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
        // Only a second wholly inside this segment: the one the next segment begins in was found right under its delay.
        if (later >= 0 && (later + 1) * 1000L <= end) {
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
        // Only for a change of a second or more: closer than that, the shifts near the delay in use sit on the same
        // peak's shoulder, and neither "sure" nor fresh talking can tell them from it (nor does beating it by enough
        // apply below [SMALL_SHIFT_MS]).
        if (sure(near, cur, nearMs) && abs(near.delayMs - wide.delayMs) <= AGREE_MS &&
            abs(near.delayMs - cur) >= SMALL_SHIFT_MS) return near to nearMs
        return wide to RANGE_MS
    }

    /** Sure by [SyncMatcher], and not at the edge of the shifts tried: a best there is likely a slope up to one beyond. */
    private fun sure(res: SyncMatcher.Result, centerMs: Long, rangeMs: Long) =
        (res.confident || clean && cleanSure(res)) && abs(res.delayMs - centerMs) <= rangeMs - EDGE_MS

    /**
     * On the film's own subtitles: the best overlap near-perfect ([CLEAN_R]), standing out as far as any sure match
     * must, and well clear of the next best, on [CLEAN_TALK_MS] of lines and [CLEAN_LINES] of them. Talking heard never
     * overlaps that well (music, pauses within lines, lines missed), so this is only ever met there.
     */
    private fun cleanSure(res: SyncMatcher.Result) =
        res.r >= CLEAN_R && res.z >= CLEAN_Z && res.runnerUp <= res.r * CLEAN_RUNNER_UP &&
            res.speechMs >= CLEAN_TALK_MS && res.lines >= CLEAN_LINES

    // Talking needed before a match is tried, and the stretch the delay in use must have stopped fitting over.
    private val minTalkMs = if (clean) CLEAN_TALK_MS else MIN_TALK_MS
    private val misfitWindowMs = if (clean) CLEAN_MISFIT_WINDOW_MS else MISFIT_WINDOW_MS
    private val misfitLines = if (clean) CLEAN_MISFIT_LINES else MISFIT_LINES

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
        /** How often (film time) the caller steps the tracker: guesses are looked at each time ([guess]). */
        const val STEP_MS = 5_000L
        /** Matching that costs (searching every shift) is looked at no more often than this. */
        const val FULL_STEP_MS = 15_000L
        /** Without a hint, a check every this much film. */
        const val PERIOD_MS = 3 * 60_000L
        /** The most film one check matches: its cost is this × the shifts tried. */
        const val WINDOW_MS = 4 * 60_000L
        /** While learning the first delay: the most film matched, and how long to try. */
        const val LEARN_WINDOW_MS = 6 * 60_000L
        // Finding the first delay, not sure of the whole stretch: the latest this much of it looked at alone (the longest
        // first), when it leaves at least [LEARN_RECENT_MIN_CUT_MS] of the stretch out.
        val LEARN_RECENT_MS = longArrayOf(150_000L, 100_000L)
        const val LEARN_RECENT_MIN_CUT_MS = 30_000L
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
        // The looser confirmation of a first delay ([looselyConfirmed]): how near the next look's best must be, how far
        // the delay must stand out on the talking since, and the least overlap that look may have.
        private const val LOOSE_AGREE_MS = 500L
        // Fresh talking the first delay's confirming look needs (a later change needs [CONFIRM_TALK_MS]).
        private const val LEARN_CONFIRM_TALK_MS = 5_000L
        private const val LOOSE_FRESH_Z = 3.0
        private const val LOOSE_MIN_R = 0.15
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
        // Trying known jumps first ([guess], [GuessRules]): a guess must overlap at least MIN_GUESS_R, and on both looks
        // together the best of the whole reach be within GUESS_SLACK_MS of it (the same jump, give or take a few frames).
        // A wrong delay named beforehand stands out by 2.5 on 10 s of talking under 1% of the time, the right one 60% of
        // the time in a talkative film and nearly always on 20 s (ResyncBench prints what the rules give). At most
        // GUESS_MAX guesses; two closer than GUESS_SAME_MS are one.
        private const val MIN_GUESS_R = 0.15
        private const val GUESS_SLACK_MS = 300L
        private const val GUESS_REFINE_MS = 1_000L
        private const val GUESS_PRIOR_R = 0.02
        private const val POLISH_TALK_MS = 90_000L
        private const val GUESS_SAME_MS = 300L
        private const val GUESS_MAX = 8
        const val JUMPS_KEPT = 4
        // An ad marked by format changes at both ends lasts this long.
        private const val AD_MIN_MS = 3_000L
        private const val AD_MAX_MS = 4 * 60_000L
        // ...or the delay in use overlapping less than this over the last minute and a half, with lines and talking in it.
        private const val MISFIT_WINDOW_MS = 90_000L
        private const val MISFIT_LINES = 6
        private const val MISFIT_TALK_MS = 10_000L
        private const val MISFIT_R = 0.05
        // On the film's own subtitles ([clean]): sure on this little, at least this near-perfect...
        private const val CLEAN_R = 0.45
        private const val CLEAN_Z = 4.5
        private const val CLEAN_RUNNER_UP = 0.6
        private const val CLEAN_TALK_MS = 12_000L
        private const val CLEAN_LINES = 6
        // ...and the delay in use not fitting the last half minute (with lines in it) says it may have changed.
        private const val CLEAN_MISFIT_WINDOW_MS = 30_000L
        private const val CLEAN_MISFIT_LINES = 3
        // ...and a change is put in a stretch with no line this long, reaching this near where it was found.
        private const val LINELESS_MS = 3_000L
        private const val LINELESS_NEAR_MS = 10_000L
        // ...and a delay found is borne out at once when each half of the lines bears it out alone: this much talking in
        // each, this many lines, this overlap and standing out this far.
        private const val CLEAN_HALF_TALK_MS = 5_000L
        private const val CLEAN_HALF_LINES = 3
        private const val CLEAN_HALF_R = 0.3
        private const val CLEAN_HALF_Z = 3.0
        // A change found after a jump past it is placed again once this much more of the stretch skipped is heard.
        private const val OPEN_HEARD_MS = 5_000L
        // ...where each side must have this much talking bearing its delay out, the best within this reach of it.
        private const val OPEN_TALK_MS = 4_000L
        private const val OPEN_REACH_MS = 3_000L
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
        /**
         * The jumps a show's episodes made, with this film's ([segments]) first: newest first, one of any two closer than
         * a few frames, at most [JUMPS_KEPT].
         */
        fun rememberJumps(known: List<Long>, segments: List<Segment>): List<Long> {
            val out = ArrayList<Long>()
            val mine = segments.zipWithNext { a, b -> b.delayMs - a.delayMs }.filter { abs(it) >= MIN_SHIFT_MS }.reversed()
            for (j in mine + known) if (out.none { abs(it - j) < GUESS_SAME_MS }) out += j
            return out.take(JUMPS_KEPT)
        }

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
            decode(text)?.let { withoutSlivers(it) } ?: legacyMs?.let { listOf(Segment(0, it)) }

        /**
         * Delays kept by an older version can hold a stretch shorter than any found now ([MIN_SEGMENT_MS]): a few
         * seconds at another delay between two breaks, which no film has. Such a stretch goes (the one before it
         * carries on), and neighbours left with the same delay become one.
         */
        internal fun withoutSlivers(list: List<Segment>): List<Segment> {
            val out = ArrayList<Segment>()
            for ((i, seg) in list.withIndex()) {
                val next = list.getOrNull(i + 1)
                if (i > 0 && next != null && next.fromMs - seg.fromMs < MIN_SEGMENT_MS) continue
                if (out.isNotEmpty() && out.last().delayMs == seg.delayMs) continue
                out += seg
            }
            return out
        }

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
