package app.fireplayer.subs

import app.fireplayer.subs.FakeFilm.Break
import app.fireplayer.subs.OffsetTracker.Outcome
import app.fireplayer.subs.OffsetTracker.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The parts of auto sync that make its sources work together, on made-up films: Resync checking one stretch again and
 * keeping the rest, a new episode starting from the last one's delay, and the talking checking the film's own subtitles.
 */
class SyncFlowTest {

    private val min = 60_000L

    private fun near(expected: Long, actual: Long?, within: Long = 300) = actual != null && abs(expected - actual) <= within

    private fun Playback.story() = decisions.filter { it.outcome != Outcome.WAIT }.joinToString("\n") { "  $it" }

    private fun threeBreaks(seed: Long) = FakeFilm.make(seed, minutes = 48, delayMs = 1_300, pace = FakeFilm.TALKY,
        breaks = listOf(Break(12 * min, adMs = 45_000, skipMs = 41_300), Break(24 * min, adMs = 30_000, skipMs = 25_000),
            Break(36 * min, adMs = 60_000, skipMs = 52_000)))

    private fun truth(film: FakeFilm) = film.pieces.filter { it.delayMs != null }.map { Segment(it.fromMs, it.delayMs!!) }

    @Test
    fun resyncWithEverythingRightKeepsEveryDelay() {
        // All four delays known and right; Resync in the third stretch: nothing changes, anywhere.
        for (seed in 1L..6L) {
            val film = threeBreaks(seed)
            val known = truth(film)
            val p = Playback(film, OffsetTracker(film.subs, known))
            p.play(30 * min)
            p.tracker.recheck(30 * min, p.timeline, reject = true)
            p.play(40 * min)
            val segs = p.tracker.segments
            assertTrue("seed $seed: $segs\n${p.story()}", segs.size == known.size && segs.indices.all { near(known[it].delayMs, segs[it].delayMs, 150) && near(known[it].fromMs, segs[it].fromMs, 1_000) })
            assertTrue("seed $seed\n${p.story()}", p.moves.isEmpty())
        }
    }

    @Test
    fun resyncFixesTheStretchItsInAndKeepsTheRest() {
        // The third stretch's delay is wrong by 3 s (found wrongly, say); the others are right. Resync there puts the
        // third right, from where it starts, and leaves the first, second and fourth as they were.
        var fixed = 0
        val seeds = 1L..8L
        for (seed in seeds) {
            val film = threeBreaks(seed)
            val right = truth(film)
            val wrong = right.mapIndexed { k, s -> if (k == 2) Segment(s.fromMs, s.delayMs + 3_000) else s }
            val p = Playback(film, OffsetTracker(film.subs, wrong, guessing = false))
            p.play(28 * min)
            p.tracker.recheck(28 * min, p.timeline, reject = true)
            p.play(35 * min)
            val segs = p.tracker.segments
            // Never anything else touched.
            assertTrue("seed $seed: $segs\n${p.story()}", near(right[0].delayMs, p.tracker.delayAt(5 * min)))
            assertTrue("seed $seed: $segs\n${p.story()}", near(right[1].delayMs, p.tracker.delayAt(20 * min)))
            assertTrue("seed $seed: $segs\n${p.story()}", near(right[3].delayMs, p.tracker.delayAt(45 * min)))
            if (near(right[2].delayMs, p.tracker.delayAt(30 * min)) && near(right[2].delayMs, p.tracker.delayAt(26 * min))) fixed++
        }
        println("Resync in a stretch 3 s off: put right within 7 min in $fixed of ${seeds.count()}, the other stretches kept in all")
        assertTrue("fixed $fixed", fixed >= seeds.count() - 1)
    }

    @Test
    fun aRightPriorLinesUpWithinTheFirstLines() {
        val gains = ArrayList<Long>()
        for (seed in 1L..10L) {
            val film = FakeFilm.make(seed, minutes = 20, delayMs = 1_300, pace = FakeFilm.TALKY)
            val withPrior = Playback(film, OffsetTracker(film.subs, learnCenterMs = 1_300, priorMs = 1_300))
            withPrior.play(10 * min)
            val without = Playback(film, OffsetTracker(film.subs))
            without.play(10 * min)
            val a = withPrior.decisions.first { it.outcome == Outcome.LEARNED }
            val b = without.decisions.first { it.outcome == Outcome.LEARNED }
            assertTrue("seed $seed\n${withPrior.story()}", near(1_300, a.delayMs))
            gains += b.toMs - a.toMs
        }
        gains.sort()
        println("the last episode's delay, right: learned ${gains.first() / 1000}..${gains.last() / 1000} s sooner (median ${gains[gains.size / 2] / 1000})")
        assertTrue("$gains", gains[gains.size / 2] > 0)
    }

    @Test
    fun aWrongPriorIsNeverTaken() {
        // The last episode started 1.2 s, 4 s or 25 s off this one: never learned, the right one is.
        var wrongTaken = 0
        var runs = 0
        for (seed in 1L..10L) for (off in listOf(1_200L, -4_000L, 25_000L)) for (pace in listOf(FakeFilm.TALKY, FakeFilm.SPARSE)) {
            val film = FakeFilm.make(seed, minutes = 20, delayMs = 1_300, pace = pace)
            val p = Playback(film, OffsetTracker(film.subs, learnCenterMs = 1_300 + off, priorMs = 1_300 + off))
            p.play(film.lengthMs)
            runs++
            val learned = p.decisions.firstOrNull { it.outcome == Outcome.LEARNED }
            if (learned != null && !near(1_300, learned.delayMs)) wrongTaken++
        }
        println("the last episode's delay, wrong: taken $wrongTaken times in $runs")
        assertEquals(0, wrongTaken)
    }

    @Test
    fun theTalkingAgreesWithRightDelaysAndNotWithWrongOnes() {
        val verdicts = HashMap<String, Int>()
        for (seed in 1L..10L) {
            val film = threeBreaks(seed)
            val p = Playback(film, OffsetTracker(film.subs, truth(film)))
            p.play(film.lengthMs)
            for (from in listOf(3 * min, 15 * min, 27 * min)) {
                val right = CrossCheck.judge(p.timeline, film.subs, truth(film), from, from + 2 * min)
                val off = CrossCheck.judge(p.timeline, film.subs, truth(film).map { Segment(it.fromMs, it.delayMs + 5_000) }, from, from + 2 * min)
                val little = CrossCheck.judge(p.timeline, film.subs, truth(film), from, from + 15_000)
                if (off.verdict != CrossCheck.Verdict.DISAGREES) println("  off, seed $seed: $off")
                verdicts.merge("right ${right.verdict}", 1, Int::plus)
                verdicts.merge("off ${off.verdict}", 1, Int::plus)
                assertTrue("seed $seed right: $right", right.verdict != CrossCheck.Verdict.DISAGREES)
                assertTrue("seed $seed off: $off", off.verdict != CrossCheck.Verdict.AGREES)
                assertEquals(CrossCheck.Verdict.UNSURE, little.verdict)
            }
        }
        println("checked against the talking (2 min): $verdicts")
        assertTrue("$verdicts", (verdicts["right AGREES"] ?: 0) >= 25)
        assertTrue("$verdicts", (verdicts["off DISAGREES"] ?: 0) >= 25)
    }

    @Test
    fun subtitleSources() {
        assertEquals("720p hdtv x264 avs", AutoSync.sourceOf("Legion.S01E04.720p.HDTV.x264-AVS"))
        assertEquals(AutoSync.sourceOf("Legion.S01E04.720p.HDTV.x264-AVS"), AutoSync.sourceOf("Legion.S01E05.Chapter.5.720p.HDTV.x264-AVS.srt"))
        assertEquals("1080p web h264 ntb", AutoSync.sourceOf("The.Show.S02E01.1080p.WEB.H264-NTb"))
        assertEquals("any", AutoSync.sourceOf("Legion 1x04"))
    }
}
