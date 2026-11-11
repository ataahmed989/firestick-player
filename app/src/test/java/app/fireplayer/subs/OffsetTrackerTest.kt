package app.fireplayer.subs

import app.fireplayer.subs.FakeFilm.Break
import app.fireplayer.subs.OffsetTracker.Outcome
import app.fireplayer.subs.OffsetTracker.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OffsetTrackerTest {

    private val min = 60_000L

    private fun Playback.story() = decisions.filter { it.outcome != Outcome.WAIT }.joinToString("\n") { "  $it" }

    private fun near(expected: Long, actual: Long?, within: Long = 100) = actual != null && abs(expected - actual) <= within

    /**
     * Asserts the map has one segment per stretch of [film], each with the right delay, starting in the break (within
     * [slackMs]).
     */
    private fun assertFollows(film: FakeFilm, p: Playback, slackMs: Long = 5_000) {
        val segs = p.tracker.segments
        val stretches = film.pieces.filter { it.delayMs != null }
        assertEquals("segments ${segs}\n${p.story()}", stretches.size, segs.size)
        for ((k, s) in stretches.withIndex()) {
            // Fine-tuning stops at 150 ms off, the existing code's "nobody sees less than this".
            assertTrue("delay of stretch $k: ${segs[k]}\n${p.story()}", near(s.delayMs!!, segs[k].delayMs, 150))
            if (k == 0) continue
            // It takes over in the stretch nothing tells the two delays apart in: from the last line said before the
            // break to the first one after it (the break itself, and any quiet either side).
            val prev = stretches[k - 1]
            val lastBefore = film.lines.filter { it.startMs + prev.delayMs!! in prev.fromMs until prev.toMs }.maxOf { it.endMs + prev.delayMs!! }
            val firstAfter = film.lines.filter { it.startMs + s.delayMs!! in s.fromMs until s.toMs }.minOf { it.startMs + s.delayMs!! }
            assertTrue("start of stretch $k: ${segs[k]} vs ${lastBefore}..${firstAfter}\n${p.story()}",
                segs[k].fromMs in lastBefore - slackMs..firstAfter + slackMs)
        }
    }

    @Test
    fun alreadySyncedSubtitlesStayPut() {
        for ((i, delay) in listOf(0L, 1_400L, -2_300L).withIndex()) {
            val film = FakeFilm.make(seed = 10L + i, minutes = 90, delayMs = delay)
            val p = Playback(film, OffsetTracker(film.subs))
            p.play(film.lengthMs)
            val learned = p.decisions.filter { it.outcome == Outcome.LEARNED }
            assertEquals(p.story(), 1, learned.size)
            assertTrue(p.story(), near(delay, learned[0].delayMs))
            assertTrue("no new segments\n${p.story()}", p.moves.isEmpty())
            assertEquals(p.story(), 1, p.tracker.segments.size)
            assertTrue(p.story(), near(delay, p.tracker.delayAt(0)))
            println("already synced $delay: learned ${learned[0].delayMs} at ${OffsetTracker.clock(learned[0].toMs)}, ${p.matches} matches in 90 min")
        }
    }

    @Test
    fun savedDelayIsFollowedWithoutChange() {
        // Known from last time; the film is resumed half way.
        val film = FakeFilm.make(seed = 21, minutes = 90, delayMs = 2_000)
        val p = Playback(film, OffsetTracker(film.subs, listOf(Segment(0, 2_000))))
        p.seek(45 * min)
        p.play(film.lengthMs)
        assertTrue(p.story(), p.moves.isEmpty())
        assertTrue(p.story(), p.decisions.any { it.outcome == Outcome.KEPT })
        assertEquals(listOf(Segment(0, 2_000)), p.tracker.segments)
    }

    @Test
    fun oneAdBreakIsFollowedWithoutAddingAllOfIt() {
        for (hints in listOf(true, false)) {
            for (seed in 30L..32L) {
                // 45 s of ads where the subtitles had left 30 s for a bumper: they need 15 s more after it, not 45.
                val film = FakeFilm.make(seed, minutes = 50, delayMs = 1_400, breaks = listOf(Break(20 * min, adMs = 45_000, skipMs = 30_000)))
                val ad = film.ads.single()
                val p = Playback(film, OffsetTracker(film.subs), formatHints = hints)
                p.play(film.lengthMs)
                assertFollows(film, p)
                assertEquals(p.story(), 1, p.moves.size)
                assertFalse(p.story(), p.tracker.segments.any { near(1_400 + 45_000, it.delayMs, 1_000) })
                val took = p.moves[0].toMs - ad.toMs
                println("one break (format hints $hints, seed $seed): +15 s found ${took / 1000} s after it, from ${OffsetTracker.clock(p.moves[0].atMs)} (ads ${OffsetTracker.clock(ad.fromMs)}-${OffsetTracker.clock(ad.toMs)})")
                // This made-up film talks sparsely (about 6 lines a minute): SyncMatcher wants 12 lines and 30 s of talking.
                assertTrue("took ${took / 1000} s\n${p.story()}", took <= if (hints) 4 * min else 6 * min)
            }
        }
    }

    @Test
    fun severalBreaksBothWays() {
        for (hints in listOf(true, false)) {
            val film = FakeFilm.make(
                seed = 40, minutes = 100, delayMs = 0,
                breaks = listOf(
                    Break(15 * min, adMs = 45_000),                                  // +45 s
                    Break(40 * min, adMs = 0, skipMs = 20_000, linesInSkip = true),  // a recap the film lacks: −20 s
                    Break(70 * min, adMs = 90_000, skipMs = 30_000),                 // +60 s
                ),
            )
            val p = Playback(film, OffsetTracker(film.subs), formatHints = hints)
            p.play(film.lengthMs)
            assertFollows(film, p)
            for (m in p.moves) println("several breaks (format hints $hints): $m")
        }
    }

    @Test
    fun hintsAloneNeverMoveIt() {
        // Hints of every kind in the wrong places, on subtitles that are right all along: every 50 s (a flood, which
        // keeps restarting the stretch matched), and every 3 min (which still lets checks agree).
        for (every in listOf(50_000L, 3 * min)) {
            val film = FakeFilm.make(seed = 45, minutes = 60, delayMs = 1_200)
            val p = Playback(film, OffsetTracker(film.subs, listOf(Segment(0, 1_200))), formatHints = false)
            val kinds = OffsetTracker.Hint.values()
            var t = 0L
            var n = 0
            while (t < film.lengthMs) {
                p.play(t)
                p.tracker.hint(t - 20_000, kinds[n++ % kinds.size])
                t += every
            }
            p.play(film.lengthMs)
            assertTrue(p.story(), p.moves.isEmpty())
            assertEquals(listOf(Segment(0, 1_200)), p.tracker.segments)
            val kept = p.decisions.count { it.outcome == Outcome.KEPT }
            if (every > 60_000) assertTrue(p.story(), kept >= 5)
            println("hints every ${every / 1000} s: $n hints, ${p.matches} matches, kept $kept times, nothing moved")
        }
    }

    @Test
    fun oneMatchOnAdsAloneIsRarelySure() {
        // An hour of nothing but talking ads, matched 4 minutes at a time against the film's lines, 3 minutes either
        // way: how often is one match sure? (A change needs two in a row agreeing, each beating the delay in use.)
        var sure = 0
        var tries = 0
        for (seed in 0L..1L) {
            val ads = FakeFilm.make(seed = 200 + seed, minutes = 70, delayMs = 0, breaks = listOf(Break(5 * min, adMs = 60 * min, skipMs = 0)))
            val other = FakeFilm.make(seed = 300 + seed, minutes = 70, delayMs = 0)
            val tl = SpeechTimeline()
            for (f in 0 until (ads.lengthMs / 10).toInt()) tl.mark(f, ads.talking.get(f))
            var t = 6 * min
            while (t + 4 * min < 64 * min) {
                val h = SyncMatcher.heard(tl.speech, tl.heard, (t / 10).toInt(), ((t + 4 * min) / 10).toInt())!!
                // The lines of the film the ads were put in, and of another film, with the delay in use at 0.
                for (subs in listOf(ads.subs, other.subs)) {
                    tries++
                    if (SyncMatcher.match(h, subs, 0, OffsetTracker.RANGE_MS).confident) sure++
                }
                t += 60_000
            }
        }
        println("matches on ads alone: $sure sure out of $tries")
        assertTrue("$sure of $tries", sure * 50 <= tries)
    }

    @Test
    fun adsAloneDoNotMoveIt() {
        var sureInAds = 0
        var triedInAds = 0
        for (seed in 50L..57L) {
            // Three minutes of talking ads the subtitles fully allow for: the delay stays.
            val allowed = FakeFilm.make(seed, minutes = 40, delayMs = 800, breaks = listOf(Break(15 * min, adMs = 3 * min, skipMs = 3 * min)))
            val p = Playback(allowed, OffsetTracker(allowed.subs), formatHints = true)
            p.play(allowed.lengthMs)
            assertTrue(p.story(), p.moves.isEmpty())
            assertEquals(1, p.tracker.segments.size)

            // A long block of talking ads: nothing found in it, however long it goes on.
            val block = FakeFilm.make(seed, minutes = 40, delayMs = 800, breaks = listOf(Break(10 * min, adMs = 12 * min, skipMs = 11 * min)))
            val ad = block.ads.single()
            // With format hints, matching starts at the ads and keeps trying them as their talking comes in.
            val q = Playback(block, OffsetTracker(block.subs), formatHints = seed % 2 == 0L)
            q.play(ad.toMs)
            assertTrue(q.story(), q.moves.isEmpty())
            assertTrue(q.story(), q.tracker.segments.all { it.delayMs == q.tracker.segments[0].delayMs })
            // How often a match on mostly ads came out sure of something wrong (one alone never moves it).
            val inAd = q.decisions.filter { it.outcome != Outcome.WAIT && it.toMs > ad.fromMs + 60_000 && it.toMs <= ad.toMs }
            triedInAds += inAd.size
            sureInAds += inAd.count { it.result?.confident == true && !near(800, it.result?.delayMs, 400) }
            // Then the film comes back a minute later than the subtitles, and that is found from the film alone.
            q.play(block.lengthMs)
            // Over 12 minutes of ads the split's score wanders, so the first lines after decide it: a little looser.
            assertFollows(block, q, slackMs = 15_000)
        }
        println("ads: $triedInAds matches over 12 min ad blocks (8 films), $sureInAds of them sure of a wrong delay, none moved it")
    }

    @Test
    fun seekingBackAndForthAcrossBreaks() {
        val film = FakeFilm.make(seed = 60, minutes = 60, delayMs = 1_000, breaks = listOf(Break(20 * min, adMs = 40_000, skipMs = 10_000)))
        val after = 31_000L
        val p = Playback(film, OffsetTracker(film.subs))
        p.play(35 * min)
        assertFollows(film, p)
        val moves = p.moves.size

        // Back before the break: the earlier delay, and nothing heard again is matched again for nothing.
        p.seek(5 * min)
        assertTrue(near(1_000, p.tracker.delayAt(p.posMs)))
        val before = p.matches
        p.play(18 * min)
        assertEquals(p.story(), moves, p.moves.size)
        println("seeking back: ${p.matches - before} matches replaying 13 min heard before")
        // Forward past it: the later one.
        p.seek(33 * min)
        assertTrue(near(after, p.tracker.delayAt(p.posMs)))
        p.play(40 * min)
        assertEquals(p.story(), moves, p.moves.size)
    }

    @Test
    fun seekingIntoFilmNotHeardYetAfterABreak() {
        val film = FakeFilm.make(seed = 70, minutes = 60, delayMs = 500, breaks = listOf(Break(25 * min, adMs = 50_000, skipMs = 30_000)))
        val after = 20_500L
        val ad = film.ads.single()
        val p = Playback(film, OffsetTracker(film.subs))
        p.play(8 * min)
        assertTrue(p.story(), near(500, p.tracker.delayAt(0)))

        // Jump well past the break: found there, while before the jump the earlier delay stays.
        p.seek(40 * min)
        p.play(44 * min)
        assertTrue(p.story(), near(after, p.tracker.delayAt(42 * min)))
        assertTrue(p.story(), near(500, p.tracker.delayAt(7 * min)))
        val found = p.moves.last()
        println("after a jump: found ${found.delayMs} ${(found.toMs - 40 * min) / 1000} s after landing, from ${OffsetTracker.clock(found.atMs)}")

        // Back into the unheard stretch after the break: found there too.
        p.seek(30 * min)
        p.play(34 * min)
        assertTrue(p.story(), near(after, p.tracker.delayAt(31 * min)))

        // And playing through the break itself puts the change where it is.
        p.seek(20 * min)
        p.play(30 * min)
        assertFollows(film, p)
        assertTrue(p.story(), p.tracker.segments[1].fromMs in ad.fromMs - 5_000..ad.toMs + 5_000)
    }

    @Test
    fun resyncRelearnsFromWhatWasHeardAndCarriesOn() {
        val film = FakeFilm.make(
            seed = 80, minutes = 70, delayMs = 0,
            breaks = listOf(Break(15 * min, adMs = 30_000), Break(45 * min, adMs = 50_000, skipMs = 30_000)),
        )
        val p = Playback(film, OffsetTracker(film.subs))
        p.play(25 * min)
        assertEquals(p.story(), 2, p.tracker.segments.size)

        // Resync at 25 min: what was heard since the last break is used again, so it's sure again almost at once.
        p.tracker.relearn(p.posMs, centerMs = p.tracker.delayAt(p.posMs)!!)
        assertTrue(p.tracker.learning)
        // Listening starts again a moment later than where it stopped (the sound waiting in between is let go).
        p.seek(p.posMs + 400)
        val at = p.posMs
        p.play(28 * min)
        val learned = p.decisions.last { it.outcome == Outcome.LEARNED }
        println("resync: sure again ${(learned.toMs - at) / 1000} s after")
        assertTrue(p.story(), learned.toMs - at <= 30_000)
        assertTrue(p.story(), near(30_000, learned.delayMs))

        // It carries on following: the next break is found.
        p.play(film.lengthMs)
        assertTrue(p.story(), near(50_000, p.tracker.delayAt(film.lengthMs)))

        // Going back before the first break, which Resync forgot: found again, and the rest is kept.
        p.seek(3 * min)
        p.play(12 * min)
        assertFollows(film, p)
    }

    @Test
    fun resyncWithNothingHeardListensAfresh() {
        val film = FakeFilm.make(seed = 81, minutes = 30, delayMs = 3_000)
        val tracker = OffsetTracker(film.subs, listOf(Segment(0, 9_000)))
        tracker.relearn(10 * min, centerMs = 9_000)
        val p = Playback(film, tracker)
        p.seek(10 * min)
        p.play(20 * min)
        assertTrue(p.story(), near(3_000, tracker.delayAt(15 * min)))
    }

    @Test
    fun learningGivesUpOnSubtitlesForAnotherFilm() {
        val film = FakeFilm.make(seed = 90, minutes = 30, delayMs = 0)
        val other = FakeFilm.make(seed = 91, minutes = 30, delayMs = 0)
        val p = Playback(film, OffsetTracker(other.subs))
        p.play(15 * min)
        assertTrue(p.story(), p.decisions.any { it.outcome == Outcome.GAVE_UP })
        assertTrue(p.tracker.learning)
    }

    @Test
    fun segmentsAreKeptAndReadBack() {
        val segs = listOf(Segment(0, 1_400), Segment(1_213_400, 16_400), Segment(3_000_000, -2_000))
        assertEquals(segs, OffsetTracker.decode(OffsetTracker.encode(segs)))
        assertNull(OffsetTracker.decode(""))
        assertNull(OffsetTracker.decode("junk"))
        // Out of order, or not from the start: put right.
        assertEquals(listOf(Segment(0, 5), Segment(100, 7)), OffsetTracker.decode("100:7;20:5"))
        // A delay kept by an older version (one for the whole film) still counts.
        assertEquals(listOf(Segment(0, 2_500)), OffsetTracker.restore(null, 2_500))
        assertEquals(segs, OffsetTracker.restore(OffsetTracker.encode(segs), 2_500))
        assertNull(OffsetTracker.restore(null, null))

        // A tracker started from them carries on from them.
        val film = FakeFilm.make(seed = 95, minutes = 30, delayMs = 0)
        val t = OffsetTracker(film.subs, OffsetTracker.restore(OffsetTracker.encode(segs), null)!!)
        assertEquals(16_400L, t.delayAt(25 * min))
        assertEquals(1_400L, t.delayAt(5 * min))
        assertEquals(-2_000L, t.delayAt(55 * min))
    }

    @Test
    fun aDelaySetByHandWins() {
        val segs = listOf(Segment(0, 1_000), Segment(20 * min, 30_000))
        assertNull(OffsetTracker.followedDelayMs(byHand = true, synced = true, segments = segs, positionMs = 25 * min))
        assertNull(OffsetTracker.followedDelayMs(byHand = false, synced = false, segments = segs, positionMs = 25 * min))
        assertEquals(30_000L, OffsetTracker.followedDelayMs(byHand = false, synced = true, segments = segs, positionMs = 25 * min))
        assertEquals(1_000L, OffsetTracker.followedDelayMs(byHand = false, synced = true, segments = segs, positionMs = 5 * min))
    }

    @Test
    fun costOfOneCheck() {
        // Two hours heard; one check matches 4 minutes of it against shifts 3 minutes either way.
        val film = FakeFilm.make(seed = 100, minutes = 120, delayMs = 1_000)
        val tl = SpeechTimeline()
        for (f in 0 until (film.lengthMs / 10).toInt()) tl.mark(f, film.talking.get(f))
        val from = (60 * min / 10).toInt()
        val to = (64 * min / 10).toInt()
        fun once(): SyncMatcher.Result {
            val h = SyncMatcher.heard(tl.speech, tl.heard, from, to)!!
            SyncMatcher.rAt(h, film.subs, 1_000)
            return SyncMatcher.match(h, film.subs, 1_000, OffsetTracker.RANGE_MS)
        }
        repeat(10) { once() }
        val times = (1..20).map { val t0 = System.nanoTime(); once(); (System.nanoTime() - t0) / 1e6 }.sorted()
        val r = once()
        assertTrue("$r", r.confident && near(1_000, r.delayMs, 200))
        println("one check (4 min heard, ±3 min): median ${"%.1f".format(times[10])} ms, worst ${"%.1f".format(times.last())} ms")
        assertTrue("median ${times[10]} ms", times[10] < 200)

        // A whole 90 min film followed, matching included.
        val f2 = FakeFilm.make(seed = 101, minutes = 90, delayMs = 0, breaks = listOf(Break(30 * min, adMs = 45_000), Break(60 * min, adMs = 60_000)))
        val p = Playback(f2, OffsetTracker(f2.subs))
        val t0 = System.nanoTime()
        p.play(f2.lengthMs)
        val ms = (System.nanoTime() - t0) / 1e6
        println("whole 90 min film with 2 breaks: ${p.matches} matches, ${"%.0f".format(ms)} ms in all (marking included)")
        assertNotNull(p.tracker.delayAt(0))
    }
}
