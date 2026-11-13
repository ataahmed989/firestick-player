package app.fireplayer.subs

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import app.fireplayer.subs.FakeFilm.Break
import app.fireplayer.subs.OffsetTracker.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Listening ahead ([SpeechAhead]): the film's sound heard as it loads, before it plays. On made-up films: how soon the
 * first delay is known (against listening where the film plays), breaks followed before they're on screen, seeking
 * outside what's loaded, other subtitles for the same film, other films' subtitles; and the detector on real samples
 * (the middle of stereo, the centre of 5.1) and how the sound fed is pieced together.
 */
class SpeechAheadTest {

    private val min = 60_000L
    private val seeds = 1L..8L

    private fun near(expected: Long, actual: Long?, within: Long = 300) = actual != null && abs(expected - actual) <= within

    /**
     * Plays [film] the way the player does (a second's cushion, then as fast as it plays), loading it [loadRate] times
     * as fast as it plays up to [aheadMs] ahead, and listens ahead the way [AutoSync]'s worker does: what's loaded is
     * heard [decodeRate] times as fast as it plays (marked as the detector would, [FakeFilm.talking]), the sound's
     * format changing at each ad's edges is a hint, and [tracker] is stepped along what's been heard. Wall time goes
     * in steps of 100 ms.
     */
    private class AheadLoading(
        val film: FakeFilm,
        val tracker: OffsetTracker,
        val loadRate: Double = 8.0,
        val decodeRate: Double = 12.0,
        val aheadMs: Long = 120_000,
        private val formatHints: Boolean = true,
        startMs: Long = 0,
        val timeline: SpeechTimeline = SpeechTimeline(),
        val ahead: SpeechAhead = SpeechAhead(timeline),
        /** As the worker does: film loaded again that was heard before isn't heard again ([SpeechAhead.skipIfHeard]). */
        private val passOver: Boolean = false,
    ) {
        var wallMs = 0L
            private set
        var playMs = startMs
            private set
        private var loadedMs = startMs
        private var heardMs = startMs
        private var playsAt = CUSHION_MS
        /** Each decision: the wall time and where the film was playing when it was made. */
        val decisions = ArrayList<Triple<Long, Long, OffsetTracker.Decision>>()
        /** When a first delay was first in use (one look sure of it, or learned): the badge's "Matched in"; -1 before. */
        var inUseWallMs = -1L
            private set
        var inUseMs = 0L
            private set
        /** Film heard (marked) in all, and film passed over as heard before, ms. */
        var markedMs = 0L
            private set
        var passedMs = 0L
            private set

        init {
            ahead.startRun(startMs)
        }

        fun tick() {
            wallMs += 100
            if (wallMs >= playsAt) playMs = minOf(playMs + 100, film.lengthMs)
            loadedMs = minOf(loadedMs + (100 * loadRate).toLong(), playMs + aheadMs, film.lengthMs)
            val to = minOf(heardMs + (100 * decodeRate).toLong(), loadedMs)
            if (formatHints) for (ad in film.ads) {
                if (ad.fromMs in heardMs until to) tracker.hint(ad.fromMs, OffsetTracker.Hint.FORMAT)
                if (ad.toMs in heardMs until to) tracker.hint(ad.toMs, OffsetTracker.Hint.FORMAT)
            }
            if (passOver && ahead.skipIfHeard(heardMs * 1_000, to * 1_000)) {
                passedMs += to - heardMs
            } else {
                for (f in (heardMs / 10).toInt() until (to / 10).toInt()) ahead.mark(f, film.talking.get(f))
                markedMs += to - heardMs
            }
            heardMs = to
            for (d in ahead.advance(tracker)) decisions += Triple(wallMs, playMs, d)
            if (inUseWallMs < 0) (tracker.tentativeMs ?: tracker.segments.firstOrNull()?.delayMs)?.let { inUseWallMs = wallMs; inUseMs = it }
        }

        /** Until the film has played to [toMs]. */
        fun playTo(toMs: Long) {
            while (playMs < minOf(toMs, film.lengthMs)) tick()
        }

        /** Until [cond], for at most [maxWallMs]. */
        fun runUntil(maxWallMs: Long, cond: () -> Boolean) {
            while (wallMs < maxWallMs && !cond()) tick()
        }

        /** Skipped to [toMs], outside what was loaded: loading starts again there, and playing after a cushion. */
        fun seek(toMs: Long) {
            playMs = toMs
            loadedMs = toMs
            heardMs = toMs
            ahead.startRun(toMs)
            playsAt = wallMs + CUSHION_MS
        }

        fun first(o: Outcome) = decisions.firstOrNull { it.third.outcome == o }

        val moves get() = decisions.map { it.third }.filter { it.outcome == Outcome.MOVED }

        fun story() = decisions.filter { it.third.outcome != Outcome.WAIT }.joinToString("\n") { "  wall ${it.first / 1000} s, playing ${it.second / 1000}: ${it.third}" }

        companion object {
            const val CUSHION_MS = 1_000L
        }
    }

    /** Phineas and Ferb S1E1 as on the Plus: an 11-minute cartoon, talkative, a 55 s theme song, subtitles 5.2 s early. */
    private fun phineas(seed: Long) = FakeFilm.make(seed, minutes = 11, delayMs = 5_200, pace = FakeFilm.TALKY, introMs = 55_000, introNoise = 0.25)

    private fun bumpers(seed: Long, pace: FakeFilm.Pace = FakeFilm.TALKY) = FakeFilm.make(seed, minutes = 48, delayMs = 1_400, pace = pace,
        breaks = (1..3).map { Break(it * 12 * min, adMs = 45_000, skipMs = 30_000) })

    @Test
    fun aPhineasLikeEpisodeLinesUpMinutesSooner() {
        for (loadRate in listOf(8.0, 4.0)) {
            val walls = ArrayList<Long>()
            val inUse = ArrayList<Long>()
            val playhead = ArrayList<Long>()
            for (seed in seeds) {
                val film = phineas(seed)
                val run = AheadLoading(film, OffsetTracker(film.subs), loadRate = loadRate)
                run.runUntil(3 * min) { run.first(Outcome.LEARNED) != null }
                val learned = run.first(Outcome.LEARNED)
                assertNotNull("seed $seed ×$loadRate: never learned\n${run.story()}", learned)
                assertTrue("seed $seed ×$loadRate: ${learned!!.third}\n${run.story()}", near(5_200, learned.third.delayMs))
                walls += learned.first
                assertTrue("seed $seed ×$loadRate: first in use ${run.inUseMs}\n${run.story()}", near(5_200, run.inUseMs, 400))
                inUse += run.inUseWallMs
                // The same, listening where the film plays (as before): the wall time is the film time plus the cushion.
                val p = Playback(film, OffsetTracker(film.subs))
                p.play(8 * min)
                val atPlayhead = p.decisions.firstOrNull { it.outcome == Outcome.LEARNED }
                assertNotNull("seed $seed: never learned where it plays", atPlayhead)
                assertTrue("seed $seed: ${atPlayhead!!}", near(5_200, atPlayhead.delayMs))
                playhead += atPlayhead.toMs + AheadLoading.CUSHION_MS
            }
            val sooner = walls.indices.map { playhead[it] - walls[it] }
            val w = walls.sorted()
            val ph = playhead.sorted()
            val u = inUse.sorted()
            println("Phineas-like, loading ×$loadRate: in use (Matched) ${u.first() / 1000}..${u.last() / 1000} s, median ${u[u.size / 2] / 1000} s")
            println("Phineas-like, loading ×$loadRate, heard ahead ×12: first delay ${w.first() / 1000}..${w.last() / 1000} s after play " +
                "(median ${w[w.size / 2] / 1000}); where it plays: ${ph.first() / 1000}..${ph.last() / 1000} s (median ${ph[ph.size / 2] / 1000}); " +
                "sooner by ${sooner.minOrNull()!! / 1000}..${sooner.maxOrNull()!! / 1000} s")
            // In every case far sooner than listening where it plays: by about what's loaded ahead (two minutes) at 8×,
            // by at least a minute at 4×. With the provider sending 8×, half of them within 30 s of pressing play; the
            // rest need talking further in than the two minutes loaded ahead, and wait for the film to get there.
            for (d in sooner) assertTrue("ahead $walls, where it plays $playhead", d >= if (loadRate >= 8.0) 90_000 else 60_000)
            if (loadRate >= 8.0) assertTrue("$walls", w[w.size / 2] <= 31_000)
            // In use ("Matched") sooner still: half within 15 s of pressing play at 8×, from the first minute of talking.
            if (loadRate >= 8.0) assertTrue("$inUse", u[u.size / 2] <= 15_500)
        }
    }

    /**
     * For each ad, where the film was playing when the delay was right for the lines after it ([decisions]: each with
     * where the film was playing), less where the ad ends (negative: ready before the ad is over on screen); null if
     * never. By the talking, where a change begins is only known to within a line or two, so it's judged from the
     * first line 10 s after the ad.
     */
    private fun followed(film: FakeFilm, decisions: List<Pair<Long, OffsetTracker.Decision>>): List<Long?> {
        val stretches = film.pieces.filter { it.delayMs != null }
        return film.ads.mapIndexed { k, ad ->
            val want = stretches[k + 1].delayMs!!
            val at = film.lines.map { it.startMs + want }.filter { it >= ad.toMs + 10_000 }.minOrNull() ?: return@mapIndexed null
            decisions.firstOrNull { (_, d) -> d.toMs >= ad.toMs && near(want, OffsetTracker.delayAt(d.segments, at)) }?.first?.minus(ad.toMs)
        }
    }

    @Test
    fun bumpsAreFollowedBeforeTheyPlay() {
        for (pace in listOf("talky" to FakeFilm.TALKY, "sparse" to FakeFilm.SPARSE)) {
            val lags = ArrayList<Long>()
            val gains = ArrayList<Long>()
            var missed = 0
            for (seed in seeds) {
                val film = bumpers(seed, pace.second)
                val run = AheadLoading(film, OffsetTracker(film.subs))
                run.playTo(film.lengthMs)
                for (s in run.tracker.segments) assertTrue("${pace.first} seed $seed: made up ${s.delayMs}: ${run.tracker.segments}",
                    film.pieces.any { it.delayMs != null && near(it.delayMs, s.delayMs) })
                // The same, listening where the film plays (a decision there is made where the film is).
                val p = Playback(film, OffsetTracker(film.subs))
                p.play(film.lengthMs)
                val atPlayhead = followed(film, p.decisions.map { it.toMs to it })
                for ((k, lag) in followed(film, run.decisions.map { it.second to it.third }).withIndex()) {
                    if (lag == null) { missed++; continue }
                    lags += lag
                    val there = atPlayhead[k] ?: continue
                    assertTrue("${pace.first} seed $seed break ${k + 1}: ahead ${lag / 1000} s, where it plays ${there / 1000} s\n${run.story()}", lag < there)
                    gains += there - lag
                }
            }
            lags.sort()
            gains.sort()
            println("heard ahead, ${pace.first}, 3 breaks × ${seeds.count()}: the new delay ready ${-lags.last() / 1000} .. ${-lags.first() / 1000} s " +
                "before the ad ends on screen (median ${-lags[lags.size / 2] / 1000}; negative: after), ${gains[gains.size / 2] / 1000} s sooner than " +
                "where it plays (median), $missed never followed")
            // About two minutes sooner than listening where it plays (what's loaded ahead). In a talkative episode every
            // break is followed, most before the ad is over on screen and none long after (a first break, no jump known
            // yet, needs the most talking); in sparse talk, most within a minute of it.
            assertTrue("$gains", gains[gains.size / 2] >= 90_000)
            if (pace.second == FakeFilm.TALKY) {
                assertEquals(0, missed)
                assertTrue("followed $lags", lags[lags.size / 2] <= 0 && lags.last() <= 30_000)
            } else {
                assertTrue("missed $missed", missed <= 1)
                assertTrue("followed $lags", lags[lags.size / 2] <= 60_000)
            }
        }
    }

    @Test
    fun anotherFilmsSubtitlesLineUpNothing() {
        // Subtitles for something else (another episode): the proof needed is the tracker's, so heard ahead nothing
        // wrong is learnt any more often than where it plays. Now and then a delay nobody could tell from chance gets
        // through either way (a run or a few in 40 here, on different films each way, as where the looks fall differs).
        var learnedAhead = 0
        var learnedPlayhead = 0
        var runs = 0
        for (pace in listOf(FakeFilm.TALKY, FakeFilm.SPARSE)) for (seed in 1L..20L) {
            runs++
            val film = bumpers(seed, pace)
            val other = bumpers(seed + 100, pace)
            // Until the tracker learns something or gives up (auto sync stops listening then).
            fun done(o: Outcome) = o == Outcome.LEARNED || o == Outcome.GAVE_UP
            val run = AheadLoading(film, OffsetTracker(other.subs))
            run.runUntil(20 * min) { run.decisions.any { done(it.third.outcome) } }
            if (run.first(Outcome.LEARNED) != null) learnedAhead++
            val p = Playback(film, OffsetTracker(other.subs))
            while (p.posMs < 20 * min && p.decisions.none { done(it.outcome) }) p.play(p.posMs + 15_000)
            if (p.decisions.any { it.outcome == Outcome.LEARNED }) learnedPlayhead++
        }
        println("another film's subtitles: learnt something in $learnedAhead of $runs heard ahead, $learnedPlayhead where it plays")
        assertTrue("$learnedAhead of $runs, where it plays $learnedPlayhead", learnedAhead <= maxOf(1, learnedPlayhead))
    }

    @Test
    fun aSkipPastAShortBumperIsRightWithinSeconds() {
        // Delays known, then a skip over a bumper that puts the subtitles 1.5 s out. Listening ahead tells the tracker of
        // the jump (the landing's own first marks don't hide it), a quick look near the delay in use puts the new one
        // in use within seconds, and the film heard since confirms it into the map. Film before the jump keeps its own.
        val waits = ArrayList<Long>()
        for (seed in seeds) {
            val film = FakeFilm.make(seed, minutes = 40, delayMs = 1_400, pace = FakeFilm.TALKY, breaks = listOf(Break(12 * min, adMs = 1_500)))
            val tracker = OffsetTracker(film.subs, listOf(OffsetTracker.Segment(0, 1_400)))
            val run = AheadLoading(film, tracker, startMs = 2 * min)
            run.playTo(4 * min)
            val jumps = tracker.jumpChecks
            run.seek(20 * min)
            val at = run.wallMs
            fun inUse() = tracker.provisional?.delayMs ?: tracker.delayAt(21 * min)
            run.runUntil(at + 60_000) { near(2_900, inUse()) }
            assertEquals("seed $seed: the jump is told\n${run.story()}", jumps + 1, tracker.jumpChecks)
            assertTrue("seed $seed: in use ${inUse()}\n${run.story()}", near(2_900, inUse()))
            waits += run.wallMs - at
            run.runUntil(at + 3 * min) { tracker.provisional == null && near(2_900, tracker.delayAt(21 * min)) }
            assertNull("seed $seed: confirmed or dropped\n${run.story()}", tracker.provisional)
            assertTrue("seed $seed: in the map ${tracker.segments}\n${run.story()}", near(2_900, tracker.delayAt(21 * min)))
            assertTrue("seed $seed: before the jump ${tracker.segments}\n${run.story()}", near(1_400, tracker.delayAt(5 * min)))
        }
        waits.sort()
        assertTrue("right delay in use, wall after the skip: $waits", waits[waits.size / 2] <= 15_000)
    }

    @Test
    fun aSkipWithNothingChangedIsKeptWithinSeconds() {
        val waits = ArrayList<Long>()
        for (seed in seeds) {
            val film = FakeFilm.make(seed, minutes = 40, delayMs = 1_400, pace = FakeFilm.TALKY)
            val tracker = OffsetTracker(film.subs, listOf(OffsetTracker.Segment(0, 1_400)))
            val run = AheadLoading(film, tracker, startMs = 2 * min)
            run.playTo(4 * min)
            run.seek(20 * min)
            val at = run.wallMs
            val before = run.decisions.size
            run.runUntil(at + 2 * min) { run.decisions.drop(before).any { it.third.outcome == Outcome.KEPT } }
            assertTrue("seed $seed: kept\n${run.story()}", run.decisions.drop(before).any { it.third.outcome == Outcome.KEPT })
            assertNull("seed $seed: nothing else put in use\n${run.story()}", tracker.provisional)
            assertTrue("seed $seed: ${tracker.segments}", tracker.segments.size == 1 && near(1_400, tracker.delayAt(21 * min)))
            waits += run.wallMs - at
        }
        waits.sort()
        assertTrue("kept, wall after the skip: $waits", waits[waits.size / 2] <= 15_000)
    }

    @Test
    fun seekingOutsideWhatsLoaded() {
        for (seed in seeds) {
            val film = bumpers(seed)
            val run = AheadLoading(film, OffsetTracker(film.subs))
            run.playTo(16 * min); run.seek(30 * min); run.playTo(33 * min); run.seek(5 * min); run.playTo(14 * min); run.seek(40 * min); run.playTo(film.lengthMs)
            val truth = film.pieces.mapNotNull { it.delayMs }
            for (s in run.tracker.segments) assertTrue("seek seed $seed: ${run.tracker.segments}", truth.any { near(it, s.delayMs) })
            // Where it played, after the first minutes, the delay is right.
            for (at in listOf(6 * min, 10 * min, 13 * min, 31 * min + 30_000, 32 * min + 30_000, 41 * min, 47 * min)) {
                assertTrue("seek seed $seed at ${at / 1000} s: ${run.tracker.segments}\n${run.story()}", near(film.truthAt(at), run.tracker.delayAt(at)))
            }
        }
    }

    @Test
    fun otherSubtitlesForTheSameFilmLineUpAtOnce() {
        // Ten minutes in, another subtitle file (timed 2 s earlier) for the same film: a new tracker brought to what's
        // been heard ahead is matched there at once, on everything heard, rather than over the next minutes.
        for (seed in seeds) {
            val film = FakeFilm.make(seed, minutes = 30, delayMs = 1_300, pace = FakeFilm.TALKY)
            val run = AheadLoading(film, OffsetTracker(film.subs))
            run.playTo(10 * min)
            val other = SyncMatcher.Lines(film.lines.map { SubtitleTimes.Line(it.startMs - 2_000, it.endMs - 2_000) })
            val tracker = OffsetTracker(other)
            val first = run.ahead.rebase(tracker)
            assertNotNull(first)
            val rest = ArrayList<OffsetTracker.Decision>()
            var steps = 0
            while (rest.none { it.outcome == Outcome.LEARNED } && first!!.outcome != Outcome.LEARNED && steps++ < 3) {
                // The next few seconds heard ahead: one step each.
                for (f in 0 until 500) run.ahead.mark((run.ahead.edgeMs / 10).toInt(), film.talking.get((run.ahead.edgeMs / 10).toInt()))
                rest += run.ahead.advance(tracker)
            }
            val learned = (listOf(first!!) + rest).firstOrNull { it.outcome == Outcome.LEARNED }
            assertNotNull("seed $seed: $first ${rest.joinToString()}", learned)
            assertTrue("seed $seed: $learned", near(3_300, learned!!.delayMs))
        }
    }

    @Test
    fun resyncTakesBackTheDelayTurnedDownOnlyFromTalkingHeardAfterThePress() {
        // Resync three minutes in, on a delay found by listening ahead: the two minutes after the press in the film were
        // heard before it, so they can't bring the delay turned down back; talking heard since can. With delays known
        // (checked again), and with none (found afresh).
        val took = ArrayList<Long>()
        for (seed in seeds) for (afresh in listOf(false, true)) {
            val film = phineas(seed)
            val run = AheadLoading(film, OffsetTracker(film.subs))
            run.playTo(3 * min)
            val at = run.playMs
            val turnedDown = run.tracker.delayAt(at)
            assertTrue("seed $seed: $turnedDown", near(5_200, turnedDown))
            val heardTo = run.ahead.edgeMs
            assertTrue("seed $seed: heard to $heardTo", heardTo - at >= 90_000)
            val n = run.decisions.size
            val first = if (afresh) {
                run.tracker.relearn(at, turnedDown!!, run.timeline, heardTo)
                run.ahead.rebase(run.tracker)
            } else {
                run.tracker.recheck(at, run.timeline, reject = true, nowMs = run.ahead.steppedToMs, heardToMs = heardTo)
            }
            run.playTo(7 * min)
            val after = listOfNotNull(first) + run.decisions.drop(n).map { it.third }
            val back = after.filter { (it.outcome == Outcome.KEPT || it.outcome == Outcome.LEARNED) && near(turnedDown!!, it.delayMs) }
            for (d in back) assertTrue("seed $seed afresh $afresh: $d\n${run.story()}", d.fromMs >= heardTo)
            assertTrue("seed $seed afresh $afresh: never back\n${run.story()}", back.isNotEmpty())
            took += back.first().toMs - heardTo
        }
        println("Resync on a delay found ahead: back on talking heard since, ${took.minOrNull()!! / 1000}..${took.maxOrNull()!! / 1000} s of film past what was heard by the press")
    }

    @Test
    fun aSkipWhileTheListenerIsBehindIsANewStretch() {
        // Continue Watching: paused at the start while the film loads far quicker than it's heard, so the pieces fill and
        // samples are let go; then Resume at 8 minutes, and loading starts there. That's a jump (a new stretch, the
        // tracker told), not a gap the tracker steps through as film it heard.
        val film = phineas(1)
        val raw = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT)
            .setChannelCount(2).setSampleRate(48_000).build()
        val ring = AheadAudio(staleMs = 60_000)
        val ahead = SpeechAhead(SpeechTimeline())
        val tracker = OffsetTracker(film.subs)
        val decisions = ArrayList<OffsetTracker.Decision>()
        // 20 ms of quiet stereo a sample.
        val quiet = ByteArray(960 * 4)
        fun load(fromMs: Long, seconds: Int) {
            for (k in 0 until seconds * 50) ring.sample("film", 1, raw, quiet, 0, quiet.size, (fromMs + k * 20L) * 1_000)
        }
        fun listen() {
            while (true) {
                val p = ring.take(0) ?: break
                if (p.afterDrop) ahead.expectGap()
                for (i in 0 until p.count) {
                    ahead.feed(ByteBuffer.wrap(p.bytes, p.startOf(i), p.ends[i] - p.startOf(i)), p.timesUs[i], 2, 48_000, C.ENCODING_PCM_16BIT)
                }
                ring.recycle(p)
                while (ahead.due()) decisions += ahead.advance(tracker)
            }
        }
        load(0, 30)
        assertTrue(ring.droppedSamples > 0)
        listen()
        assertEquals(1, ahead.runs)
        load(8 * min, 6)
        listen()
        assertEquals(2, ahead.runs)
        assertEquals(8 * min, ahead.runFromMs)
        assertTrue("${ahead.steppedToMs}", ahead.steppedToMs in 8 * min..8 * min + 6_000)
        assertTrue(decisions.joinToString("\n"), decisions.none { it.outcome == Outcome.GAVE_UP })
    }

    @Test
    fun aRewindOntoFilmHeardIsNotHeardAgainAndTheTrackerIsSteppedTheSame() {
        // Ten minutes played (twelve heard), then back to 4:00: the player loads the film from there again. Heard again
        // or passed over as heard before, the tracker is stepped along it the same and ends with the same delays; only
        // the last of the film heard before is heard again (so the detector has its bearings where the new film starts).
        for (seed in 1L..4L) {
            val film = bumpers(seed)
            fun run(passOver: Boolean): AheadLoading {
                val r = AheadLoading(film, OffsetTracker(film.subs), passOver = passOver)
                r.playTo(10 * min)
                r.seek(4 * min)
                r.playTo(16 * min)
                return r
            }
            val again = run(false)
            val passed = run(true)
            assertEquals("seed $seed", again.ahead.runs, passed.ahead.runs)
            assertEquals("seed $seed", again.ahead.runFromMs, passed.ahead.runFromMs)
            assertEquals("seed $seed", again.ahead.edgeMs, passed.ahead.edgeMs)
            assertEquals("seed $seed", again.ahead.steppedToMs, passed.ahead.steppedToMs)
            assertEquals("seed $seed", again.timeline.speech, passed.timeline.speech)
            assertEquals("seed $seed", again.timeline.heard, passed.timeline.heard)
            assertEquals("seed $seed\n${again.story()}\nand\n${passed.story()}", again.tracker.segments, passed.tracker.segments)
            assertEquals("seed $seed", again.decisions.map { it.third.outcome to it.third.toMs }, passed.decisions.map { it.third.outcome to it.third.toMs })
            assertEquals("seed $seed", 0L, again.passedMs)
            // From 4:00 to about 12:00 was heard before: all of it passed over but the last ten seconds or so.
            assertTrue("seed $seed: passed over ${passed.passedMs / 1000} s", passed.passedMs in 7 * min + 30_000..8 * min)
            assertEquals("seed $seed", again.markedMs, passed.markedMs + passed.passedMs)
            assertEquals("seed $seed", passed.passedMs, passed.ahead.skippedMs)
        }
    }

    @Test
    fun filmHeardBeforeIsPassedOverPieceByPiece() {
        // Talking two seconds in every four, for half a minute.
        fun voice(seconds: Double, fromS: Double = 0.0) = pcm(seconds, 2) { t, _ -> if ((t + fromS) % 4 < 2) 0.2 * sin(2 * PI * 900 * (t + fromS)) else 0.0 }
        val ahead = SpeechAhead(SpeechTimeline())
        feed(ahead, voice(30.0), 2)
        assertEquals(1, ahead.runs)
        val speech = ahead.timeline.speech.clone() as java.util.BitSet
        val decoded = ahead.decodedMs
        // Film not heard is never passed over, and nothing changes for asking.
        assertFalse(ahead.skipIfHeard(40_000_000, 42_000_000))
        assertFalse(ahead.skipIfHeard(-1, 2_000_000))
        assertEquals(1, ahead.runs)
        assertTrue("${ahead.edgeMs}", ahead.edgeMs in 29_700L..30_000L)
        // Back to 0:04, loading from there again in pieces of two seconds: a new stretch from the landing, going on
        // through what's passed over. The last ten seconds heard before aren't passed over.
        var us = 4_000_000L
        var passed = 0
        while (ahead.skipIfHeard(us, us + 2_000_000)) {
            us += 2_000_000
            passed++
            assertEquals(2, ahead.runs)
            assertEquals(4_000L, ahead.runFromMs)
            assertEquals(us / 1000, ahead.edgeMs)
            assertEquals(us, ahead.nextUs)
        }
        assertEquals(8, passed)
        assertEquals(20_000_000L, us)
        assertTrue(ahead.due())
        assertEquals(16_000L, ahead.skippedMs)
        assertEquals(decoded, ahead.decodedMs)
        assertEquals(speech, ahead.timeline.speech)
        // What follows is decoded: the same stretch going on, into film not heard before.
        feed(ahead, voice(20.0, fromS = 20.0), 2, startUs = 20_000_000)
        assertEquals(2, ahead.runs)
        assertEquals(4_000L, ahead.runFromMs)
        assertTrue("${ahead.edgeMs}", ahead.edgeMs in 39_700L..40_000L)
        assertTrue("${ahead.decodedMs - decoded}", ahead.decodedMs - decoded in 19_000L..20_000L)
        assertTrue(ahead.covers(0, 39_700))
        // Heard as before where it was heard again, and the talking after it is there.
        assertEquals(speech.get(400, 2_000), ahead.timeline.speech.get(400, 2_000))
        for (s in listOf(32, 36)) assertTrue("talking at $s s: ${talkShare(ahead.timeline, s * 1000L + 50, s * 1000L + 1_950)}",
            talkShare(ahead.timeline, s * 1000L + 50, s * 1000L + 1_950) >= 0.9)
        // A skip on to film heard before (0:10, more than ten seconds back from where the sound ends): another stretch.
        assertTrue(ahead.skipIfHeard(10_000_000, 12_000_000))
        assertEquals(3, ahead.runs)
        assertEquals(10_000L, ahead.runFromMs)
        // After samples were let go (the listener behind), film heard before a long way on is still the same stretch.
        ahead.expectGap()
        assertTrue(ahead.skipIfHeard(24_000_000, 26_000_000))
        assertEquals(3, ahead.runs)
        // With nothing heard yet in this sitting of it (the marks kept from before): a stretch begins.
        val later = SpeechAhead(ahead.timeline)
        assertTrue(later.skipIfHeard(6_000_000, 8_000_000))
        assertEquals(1, later.runs)
        assertEquals(6_000L, later.runFromMs)
        assertEquals(8_000L, later.edgeMs)
    }

    @Test
    fun behindOnlyOnceTheFilmWhereItPlaysHasLoaded() {
        val ahead = SpeechAhead(SpeechTimeline())
        // Nothing heard, nothing known of where it plays: not behind.
        assertFalse(ahead.behind(-1, Long.MAX_VALUE))
        ahead.startRun(0)
        for (f in 0 until 10_000) ahead.mark(f, false)
        assertEquals(100_000L, ahead.edgeMs)
        // Heard to 1:40. Playing at 1:30, or up to five seconds past what's heard: keeping up.
        assertFalse(ahead.behind(90_000, 220_000))
        assertFalse(ahead.behind(105_000, 220_000))
        // Playing at 1:50 with the film there loaded: behind.
        assertTrue(ahead.behind(110_000, 220_000))
        // A skip to 5:00: what's heard ends where the film was, but nothing at 5:00 has loaded yet (loading had got to
        // 3:40): that's the new position loading, however long it takes, not the listening behind.
        assertFalse(ahead.behind(300_000, 220_000))
        assertFalse(ahead.behind(300_000, -1))
        // Loading has started there, a keyframe before: not yet; then it has, and nothing of it is heard: behind.
        assertFalse(ahead.behind(300_000, 292_000))
        assertTrue(ahead.behind(300_000, 296_000))
        // The first of it heard: keeping up again.
        ahead.startRun(296_000)
        for (f in 29_600 until 29_900) ahead.mark(f, false)
        assertFalse(ahead.behind(300_000, 320_000))
    }

    @Test
    fun coversToleratesSmallHoles() {
        val ahead = SpeechAhead(SpeechTimeline())
        ahead.startRun(0)
        for (f in 0 until 1_000) if (f % 20 != 0) ahead.mark(f, false)
        // 5% unheard: covered; a whole second unheard in five: not.
        assertTrue(ahead.covers(0, 5_000))
        for (f in 200 until 300) ahead.timeline.heard.clear(f)
        assertFalse(ahead.covers(0, 5_000))
        assertTrue(ahead.covers(5_000, 5_000))
        assertFalse(ahead.covers(10_000, 15_000))
    }

    // ---- The detector on real samples, and how the sound fed is pieced together ------------------------------------

    /** [seconds] of 48 kHz sound, [channels] 16-bit, from [sample] (time s, channel → -1..1). */
    private fun pcm(seconds: Double, channels: Int, sample: (Double, Int) -> Double): ShortArray {
        val n = (seconds * 48_000).toInt()
        return ShortArray(n * channels) { i -> (sample((i / channels) / 48_000.0, i % channels).coerceIn(-1.0, 1.0) * 32_767).toInt().toShort() }
    }

    /** Fed as a decoder would hand it over: 1,024 frames at a time, each with its film time, from [startUs]. */
    private fun feed(ahead: SpeechAhead, samples: ShortArray, channels: Int, startUs: Long = 0) {
        val frames = samples.size / channels
        var f = 0
        while (f < frames) {
            val n = minOf(1_024, frames - f)
            val buf = ByteBuffer.allocate(n * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n * channels) buf.putShort(samples[f * channels + i])
            buf.flip()
            ahead.feed(buf, startUs + f * 1_000_000L / 48_000, channels, 48_000, C.ENCODING_PCM_16BIT)
            assertEquals("left as it was", 0, buf.position())
            f += n
        }
    }

    /** Share of 10 ms marked as talking in [fromMs]..[toMs]. */
    private fun talkShare(tl: SpeechTimeline, fromMs: Long, toMs: Long): Double {
        val a = (fromMs / 10).toInt()
        val b = (toMs / 10).toInt()
        return tl.speech.get(a, b).cardinality().toDouble() / (b - a)
    }

    // Talking (a voice-band tone) in these stretches (ms); music the rest of the time in the other stretches.
    private val talk = listOf(2_000L to 3_500L, 5_000L to 6_200L, 8_000L to 10_000L, 13_000L to 14_000L)
    private val musicAt = listOf(0L to 1_800L, 3_800L to 4_800L, 10_500L to 12_500L)

    private fun inAny(t: Double, spans: List<Pair<Long, Long>>) = spans.any { t * 1000 >= it.first && t * 1000 < it.second }

    @Test
    fun stereoHearsTheMiddleOnly() {
        // Dialogue the same in both sides; music loud but spread (much more in the sides' difference), and a little hiss.
        val rnd = Random(1)
        val samples = pcm(15.0, 2) { t, c ->
            val voice = if (inAny(t, talk)) 0.25 * sin(2 * PI * 900 * t) * (0.6 + 0.4 * sin(2 * PI * 4 * t)) else 0.0
            val music = if (inAny(t, musicAt)) 0.35 * sin(2 * PI * 700 * t) + 0.2 * sin(2 * PI * 1_300 * t) else 0.0
            val hiss = 0.002 * rnd.nextGaussian()
            voice + (if (c == 0) music else -0.7 * music) + hiss
        }
        val ahead = SpeechAhead(SpeechTimeline())
        feed(ahead, samples, 2)
        val tl = ahead.timeline
        for ((a, b) in talk) assertTrue("talking at $a..$b: ${talkShare(tl, a + 50, b - 50)}", talkShare(tl, a + 50, b - 50) >= 0.9)
        for ((a, b) in musicAt) assertTrue("music at $a..$b: ${talkShare(tl, a + 50, b - 50)}", talkShare(tl, a + 50, b - 50) <= 0.05)
        // Where it starts: within 30 ms of the talking starting.
        for ((a, _) in talk) {
            val first = tl.speech.nextSetBit((a / 10).toInt() - 20) * 10L
            assertTrue("talking at $a marked from $first", abs(first - a) <= 30)
        }
        assertEquals(1, ahead.runs)
        assertEquals(0L, ahead.runFromMs)
        assertTrue("${ahead.edgeMs}", ahead.edgeMs in 14_700L..15_000L)
    }

    @Test
    fun surroundHearsTheCentre() {
        // 5.1: dialogue in the centre (channel 2); loud music and effects in the rears and the bass only, which aren't
        // listened to; a little hiss in the fronts.
        val rnd = Random(2)
        val samples = pcm(15.0, 6) { t, c ->
            val voice = if (c == 2 && inAny(t, talk)) 0.3 * sin(2 * PI * 900 * t) else 0.0
            val music = if (c >= 3 && inAny(t, musicAt)) 0.5 * sin(2 * PI * 700 * t) else 0.0
            voice + music + if (c < 3) 0.002 * rnd.nextGaussian() else 0.0
        }
        val ahead = SpeechAhead(SpeechTimeline())
        feed(ahead, samples, 6)
        val tl = ahead.timeline
        for ((a, b) in talk) assertTrue("talking at $a..$b: ${talkShare(tl, a + 50, b - 50)}", talkShare(tl, a + 50, b - 50) >= 0.9)
        for ((a, b) in musicAt) assertTrue("music at $a..$b: ${talkShare(tl, a + 50, b - 50)}", talkShare(tl, a + 50, b - 50) <= 0.05)
    }

    @Test
    fun soundFedIsPiecedTogether() {
        val tone = pcm(4.0, 2) { t, _ -> 0.1 * sin(2 * PI * 900 * t) }
        val ahead = SpeechAhead(SpeechTimeline())
        // Exact times all along: one stretch, heard throughout.
        feed(ahead, tone, 2, startUs = 0)
        assertEquals(1, ahead.runs)
        assertTrue(ahead.covers(0, 3_800))
        // Two seconds let go (a gap shorter than a jump): the same stretch, the gap unheard.
        feed(ahead, tone, 2, startUs = 6_000_000)
        assertEquals(1, ahead.runs)
        assertEquals(0L, ahead.runFromMs)
        assertFalse(ahead.timeline.heard.get(450))
        assertTrue(ahead.covers(6_000, 9_800))
        // A minute ahead: loading started somewhere else, a new stretch.
        feed(ahead, tone, 2, startUs = 70_000_000)
        assertEquals(2, ahead.runs)
        assertEquals(70_000L, ahead.runFromMs)
        assertTrue(ahead.due())
        // Back: another.
        feed(ahead, tone, 2, startUs = 20_000_000)
        assertEquals(3, ahead.runs)
        assertEquals(20_000L, ahead.runFromMs)
        // Samples let go for long (the listener behind): however long the gap, the same stretch.
        ahead.expectGap()
        feed(ahead, tone, 2, startUs = 45_000_000)
        assertEquals(3, ahead.runs)
        // Rounded timestamps (to the ms, as in Matroska) are the same sound going on.
        val other = SpeechAhead(SpeechTimeline())
        var us = 0L
        var frames = 0L
        while (frames < 48_000L * 10) {
            val buf = ByteBuffer.allocate(1_024 * 4).order(ByteOrder.LITTLE_ENDIAN)
            other.feed(buf, us, 2, 48_000, C.ENCODING_PCM_16BIT)
            frames += 1_024
            us = Math.round(frames * 1_000.0 / 48_000) * 1_000
        }
        assertEquals(1, other.runs)
        assertTrue(other.covers(0, 9_800))
    }

    @Test
    fun otherSampleFormats() {
        // 24-bit, 32-bit and float little-endian give the same marks as 16-bit.
        val voice = pcm(6.0, 1) { t, _ -> if (t in 2.0..4.0) 0.3 * sin(2 * PI * 900 * t) else 0.001 * sin(2 * PI * 60 * t) }
        fun marks(encoding: Int): java.util.BitSet {
            val ahead = SpeechAhead(SpeechTimeline())
            val bytes = when (encoding) { C.ENCODING_PCM_24BIT -> 3; C.ENCODING_PCM_16BIT -> 2; else -> 4 }
            var f = 0
            while (f < voice.size) {
                val n = minOf(960, voice.size - f)
                val buf = ByteBuffer.allocate(n * bytes).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) {
                    val v = voice[f + i].toInt()
                    when (encoding) {
                        C.ENCODING_PCM_16BIT -> buf.putShort(v.toShort())
                        C.ENCODING_PCM_24BIT -> { val x = v shl 8; buf.put(x.toByte()); buf.put((x shr 8).toByte()); buf.put((x shr 16).toByte()) }
                        C.ENCODING_PCM_32BIT -> buf.putInt(v shl 16)
                        else -> buf.putFloat(v / 32_768f)
                    }
                }
                buf.flip()
                ahead.feed(buf, f * 1_000_000L / 48_000, 1, 48_000, encoding)
                f += n
            }
            return ahead.timeline.speech
        }
        val base = marks(C.ENCODING_PCM_16BIT)
        assertTrue(base.cardinality() > 150)
        for (e in listOf(C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)) assertEquals("encoding $e", base, marks(e))
    }
}
