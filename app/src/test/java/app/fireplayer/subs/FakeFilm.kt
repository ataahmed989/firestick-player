package app.fireplayer.subs

import java.util.BitSet
import java.util.Random

/**
 * A made-up film for auto sync tests: subtitle lines, the film made from them with breaks spliced in, and what the
 * speech detector would mark at each 10 ms of it (talking through most of each line with pauses, some lines missed
 * altogether, loud music and effects marked as talking now and then, and ads full of talking).
 */
class FakeFilm private constructor(
    val lines: List<SubtitleTimes.Line>,
    val talking: BitSet,
    val lengthMs: Long,
    val pieces: List<Piece>,
) {
    /** Film time [fromMs] to [toMs]: the subtitles' time plus [delayMs], or ads when [delayMs] is null. */
    class Piece(val fromMs: Long, val toMs: Long, val delayMs: Long?)

    /**
     * A break at subtitle time [subMs]: [adMs] of ads put into the film there, and [skipMs] of the subtitles' time
     * that isn't in the film (a gap the subtitles left for a bumper, or with [linesInSkip] a recap the film lacks).
     * After it the subtitles need [adMs] - [skipMs] more delay.
     */
    class Break(val subMs: Long, val adMs: Long, val skipMs: Long = 0, val linesInSkip: Boolean = false)

    val ads: List<Piece> get() = pieces.filter { it.delayMs == null }

    /** The delay the subtitles need at film time [ms]; in an ad, the one after it. */
    fun truthAt(ms: Long): Long {
        val p = pieces.indexOfFirst { ms < it.toMs }.let { if (it < 0) pieces.size - 1 else it }
        return pieces.drop(p).first { it.delayMs != null }.delayMs!!
    }

    val subs = SyncMatcher.Lines(lines)

    /** Line lengths and pauses: [lineMs] plus up to [lineSpreadMs], pauses [gapMs] plus up to [gapSpreadMs]. */
    class Pace(val lineMs: Long, val lineSpreadMs: Int, val gapMs: Long, val gapSpreadMs: Int)

    /** Talking film time from [fromMs] to [toMs], as the speech detector marks it. */
    fun talkMs(fromMs: Long, toMs: Long): Long =
        if (toMs <= fromMs) 0 else talking.get((fromMs / 10).toInt(), (toMs / 10).toInt()).cardinality() * 10L

    companion object {
        val SPARSE = Pace(2_000, 3_000, 1_000, 11_000)
        val TALKY = Pace(1_500, 2_500, 300, 2_400)

        fun make(
            seed: Long,
            minutes: Int,
            delayMs: Long,
            breaks: List<Break> = emptyList(),
            noise: Double = 0.08,
            missed: Double = 0.1,
            adTalk: Boolean = true,
            /** How long lines last and the pauses between them (shortest, and how much longer at most), in ms. The
             *  defaults talk sparsely (about 6 lines a minute); [TALKY] is a talkative TV episode (about 13). */
            pace: Pace = SPARSE,
            /** No lines in the film's first [introMs] (a theme song), loud music marked as talking now and then
             *  ([introNoise], as [noise]) on top of the usual noise. */
            introMs: Long = 0,
            introNoise: Double = 0.0,
        ): FakeFilm {
            val rnd = Random(seed)
            var lines = ArrayList<SubtitleTimes.Line>()
            var t = if (introMs > 0) maxOf(0L, introMs - delayMs) else 5_000L
            while (t < minutes * 60_000L) {
                val length = pace.lineMs + rnd.nextInt(pace.lineSpreadMs)
                lines += SubtitleTimes.Line(t, t + length)
                t += length + pace.gapMs + rnd.nextInt(pace.gapSpreadMs)
            }
            // A break falls between lines; where the subtitles left a gap for it, there are none in the gap.
            for (b in breaks) {
                lines = ArrayList(lines.filter { l ->
                    val straddles = l.startMs < b.subMs && l.endMs > b.subMs
                    val inGap = !b.linesInSkip && l.endMs > b.subMs && l.startMs < b.subMs + b.skipMs
                    !straddles && !inGap
                })
            }

            val pieces = ArrayList<Piece>()
            var d = delayMs
            var at = 0L
            for (b in breaks.sortedBy { it.subMs }) {
                val end = b.subMs + d
                pieces += Piece(at, end, d)
                if (b.adMs > 0) pieces += Piece(end, end + b.adMs, null)
                at = end + b.adMs
                d += b.adMs - b.skipMs
            }
            val length = minutes * 60_000L + d + 30_000L
            pieces += Piece(at, length, d)

            val talking = BitSet()
            for (p in pieces) {
                val pd = p.delayMs
                if (pd == null) {
                    if (!adTalk) continue
                    // Ads: voice-over and jingles, talking-like most of the time.
                    var f = (p.fromMs / 10).toInt()
                    val to = (p.toMs / 10).toInt()
                    while (f < to) {
                        val run = 30 + rnd.nextInt(270)
                        talking.set(f, minOf(f + run, to))
                        f += run + 10 + rnd.nextInt(70)
                    }
                    continue
                }
                for (l in lines) {
                    val start = l.startMs + pd
                    if (start < p.fromMs || start >= p.toMs) continue
                    if (rnd.nextDouble() < missed) continue
                    var f = (start / 10 + rnd.nextInt(20)).toInt()
                    val to = minOf((l.endMs + pd) / 10 - rnd.nextInt(30), p.toMs / 10).toInt()
                    while (f < to) {
                        val run = 15 + rnd.nextInt(40)
                        if (f >= 0) talking.set(f, minOf(f + run, to))
                        f += run + rnd.nextInt(15)
                    }
                }
            }
            var burst = 0
            for (f in 0 until (length / 10).toInt()) {
                if (burst == 0 && rnd.nextDouble() < noise / 30) burst = 30 + rnd.nextInt(200)
                if (burst > 0) { talking.set(f); burst-- }
            }
            if (introNoise > 0) {
                // Its own random numbers, so the rest of the film is as it would be without the intro's music.
                val music = Random(seed * 7_919 + 13)
                burst = 0
                for (f in 0 until (introMs / 10).toInt()) {
                    if (burst == 0 && music.nextDouble() < introNoise / 30) burst = 30 + music.nextInt(200)
                    if (burst > 0) { talking.set(f); burst-- }
                }
            }
            return FakeFilm(lines, talking, length, pieces)
        }
    }
}

/**
 * Plays a [FakeFilm] through an [OffsetTracker] the way [AutoSync]'s listening thread does: marks each 10 ms heard,
 * steps every [OffsetTracker.STEP_MS] of film played, says when the sound's format changes (at each ad's start and end, if [formatHints])
 * and when playing jumps. Like the listening thread, it steps at any 10 ms, not on whole seconds: pieces of 1.01 s.
 * Another subtitle file for the same film carries on with the [timeline] heard so far.
 */
class Playback(
    val film: FakeFilm,
    val tracker: OffsetTracker,
    private val formatHints: Boolean = true,
    val timeline: SpeechTimeline = SpeechTimeline(),
    startMs: Long = 0,
) {
    var posMs = startMs
        private set
    private var sinceStep = 0L
    val decisions = ArrayList<OffsetTracker.Decision>()
    val moves: List<OffsetTracker.Decision> get() = decisions.filter { it.outcome == OffsetTracker.Outcome.MOVED }
    val matches: Int get() = decisions.count { it.outcome != OffsetTracker.Outcome.WAIT }

    fun play(toMs: Long) {
        while (posMs < toMs) {
            val end = minOf(posMs + 1_010, toMs)
            if (formatHints) for (ad in film.ads) {
                if (ad.fromMs in posMs until end) tracker.hint(ad.fromMs, OffsetTracker.Hint.FORMAT)
                if (ad.toMs in posMs until end) tracker.hint(ad.toMs, OffsetTracker.Hint.FORMAT)
            }
            for (f in (posMs / 10).toInt() until (end / 10).toInt()) timeline.mark(f, film.talking.get(f))
            sinceStep += end - posMs
            posMs = end
            if (sinceStep >= OffsetTracker.STEP_MS) {
                decisions += tracker.step(timeline, posMs, sinceStep)
                sinceStep = 0
            }
        }
    }

    fun seek(toMs: Long) {
        posMs = toMs
        tracker.seeked(toMs, timeline)
    }
}
