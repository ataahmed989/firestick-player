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
                println("one break (format hints $hints, seed $seed): +15 s found ${took / 1000} s after it, from ${OffsetTracker.clock(p.moves[0].atMs)}, " +
                    "proven on ${OffsetTracker.clock(p.moves[0].fromMs)}-${OffsetTracker.clock(p.moves[0].toMs)} (ads ${OffsetTracker.clock(ad.fromMs)}-${OffsetTracker.clock(ad.toMs)})")
                // The proof is the talking after the break, not the break itself (nor what came before it).
                assertTrue(p.story(), p.moves[0].fromMs >= ad.toMs - 5_000)
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
            for (m in p.moves) {
                println("several breaks (format hints $hints): $m")
                film.ads.lastOrNull { it.fromMs <= m.toMs }?.let { assertTrue(p.story(), m.fromMs >= it.toMs - 5_000) }
            }
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
    fun aChangeFoundRunningIntoAStretchFoundRightBefore() {
        // 45-60 min played first with the delay from the start (right there, after a cut that undoes an earlier ad),
        // then back to 42-44 min: the +60 s found there runs into the stretch already found right. Steps fall anywhere
        // in a second, as on the TV. (These seeds all threw here once.)
        var moved = 0
        for (seed in listOf(2L, 5L, 7L, 8L, 9L, 11L)) for (back in listOf(42 * min, 42 * min + 30_000, 43 * min, 43 * min + 30_000, 44 * min)) {
            val film = FakeFilm.make(seed, minutes = 70, delayMs = 0, breaks = listOf(
                Break(20 * min, adMs = 60_000),
                Break(44 * min, adMs = 0, skipMs = 60_000, linesInSkip = true),
            ))
            val p = Playback(film, OffsetTracker(film.subs), formatHints = false)
            p.play(6 * min)
            p.seek(45 * min + 370)
            p.play(60 * min)
            p.seek(back + 370)
            p.play(52 * min)
            val segs = p.tracker.segments
            assertTrue("$segs", segs.zipWithNext().all { (a, b) -> a.fromMs < b.fromMs })
            assertTrue(p.story(), segs.all { near(0, it.delayMs, 400) || near(60_000, it.delayMs, 400) })
            // Where it was found right with no delay, it still has none.
            assertTrue(p.story(), near(0, p.tracker.delayAt(46 * min), 400))
            if (p.moves.isNotEmpty()) {
                moved++
                assertTrue(p.story(), near(60_000, p.tracker.delayAt(back + 30_000), 400))
            }
        }
        println("a change running into a stretch found right: $moved of 30 runs moved, all in order")
        assertTrue(moved >= 5)
    }

    @Test
    fun smallChangesAfterABreakAreFollowedWithoutFlipFlopping() {
        // A break the subtitles allow for all but half a second of (or 0.6 s too much): too close to the delay in use
        // for a wide margin, so agreeing matches carry it; once changed it stays changed, and before the break stays.
        for (shift in listOf(450L, -600L)) for (seed in 1L..3L) {
            val film = FakeFilm.make(seed, minutes = 50, delayMs = 0, breaks = listOf(Break(20 * min, adMs = 30_000, skipMs = 30_000 - shift)))
            val p = Playback(film, OffsetTracker(film.subs))
            val applied = ArrayList<Long>()
            while (p.posMs < film.lengthMs) {
                p.play(minOf(p.posMs + 30_000, film.lengthMs))
                p.tracker.delayAt(p.posMs)?.let { applied += it }
            }
            // Where half a second takes over can't be told as closely as for a bigger change: the two delays differ
            // only at the edges of lines.
            assertFollows(film, p, slackMs = 60_000)
            val first = applied.indexOfFirst { near(shift, it, 150) }
            assertTrue(p.story(), first >= 0)
            assertTrue("$applied\n${p.story()}", applied.drop(first).all { near(shift, it, 200) })
            assertTrue(p.story(), near(0, p.tracker.delayAt(10 * min), 150))
            println("small change $shift ms (seed $seed): found ${(p.moves.firstOrNull()?.toMs ?: 0) / 1000 - 20 * 60} s after 20:00, segments ${p.tracker.segments}")
        }
    }

    @Test
    fun aChangeOfSecondsInSparseTalkIsJudgedNearTheDelayInUse() {
        // 3 s more after 20:00 in a film whose talking is hard to hear (music, lines missed). Among all the shifts within
        // 3 minutes, chance matches keep it "not sure" for many minutes, or for good; among those within 20 s, it stands
        // out. Never a delay that's neither. (Searching only: the sound's format marking the 3 s ad at both ends, the
        // delay plus 3 s is tried first and found, see aBumperTheSameLengthAgainIsFollowedQuickly.)
        var wideFound = 0
        for (seed in 1L..8L) {
            val film = FakeFilm.make(seed, minutes = 45, delayMs = 1_400, breaks = listOf(Break(20 * min, adMs = 3_150)), noise = 0.2, missed = 0.3)
            val times = listOf(0L, OffsetTracker.NEAR_MS).map { nearMs ->
                val p = Playback(film, OffsetTracker(film.subs, nearMs = nearMs, guessing = false))
                p.play(film.lengthMs)
                assertTrue(p.story(), p.tracker.segments.all { near(1_400, it.delayMs, 300) || near(4_550, it.delayMs, 300) })
                p.moves.firstOrNull { near(4_550, it.delayMs, 300) }?.let { it.toMs - 20 * min }
            }
            if (times[0] != null) wideFound++
            println("3 s change in sparse talk (seed $seed): whole reach only ${times[0]?.div(1000)} s, near the delay in use ${times[1]?.div(1000)} s")
            assertNotNull("seed $seed", times[1])
            assertTrue("seed $seed: ${times[1]}", times[1]!! <= 12 * min)
        }
        assertTrue("whole reach only found it $wideFound times of 8", wideFound < 8)
    }

    @Test
    fun aChangeFoundBackwardsReachesTheNextOneWithoutASliver() {
        // Three stretches (+1.4 s, +3.4 s from 10:00, +4.7 s from 20:00). Heard to 8:00, a jump past 20:00 finds +4.7
        // where the viewer landed (not on a whole second); back at 12:30, +3.4 is found. It runs on to the +4.7, with
        // no few seconds of the first delay left in between (the second the +4.7 begins in isn't the first's).
        for (seed in 1L..3L) {
            val film = FakeFilm.make(seed, minutes = 40, delayMs = 1_400, breaks = listOf(Break(10 * min, adMs = 2_000), Break(20 * min, adMs = 1_300)))
            val p = Playback(film, OffsetTracker(film.subs))
            p.play(8 * min)
            p.seek(20 * min + 30_450)
            p.play(25 * min)
            p.seek(12 * min + 30_000)
            p.play(19 * min)
            val segs = p.tracker.segments
            assertTrue("$segs\n${p.story()}", segs.any { near(3_400, it.delayMs, 300) } && segs.any { near(4_700, it.delayMs, 300) })
            val i = segs.indexOfFirst { near(3_400, it.delayMs, 300) }
            assertTrue("$segs\n${p.story()}", near(4_700, segs[i + 1].delayMs, 300))
        }
    }

    @Test
    fun judgingNearTheDelayInUseMakesNoChangeOfUnderASecond() {
        // Sparse talking with no change at all, and with one 30 s ad: the shifts just beside the right one can look sure
        // among those near it, but a change of under a second is never judged there, so none is made up.
        for (seed in 1L..16L) {
            val film = FakeFilm.make(seed, minutes = 50, delayMs = 1_400, noise = 0.2, missed = 0.3)
            val p = Playback(film, OffsetTracker(film.subs))
            p.play(film.lengthMs)
            // One delay throughout (learnt within what sparse talking allows), no changes made up.
            assertEquals("seed $seed: ${p.tracker.segments}\n${p.story()}", 1, p.tracker.segments.size)
            assertTrue("seed $seed: ${p.tracker.segments}\n${p.story()}", near(1_400, p.tracker.segments[0].delayMs, 300))
        }
        val film = FakeFilm.make(7, minutes = 50, delayMs = 1_400, breaks = listOf(Break(20 * min, adMs = 30_000)), noise = 0.2, missed = 0.3)
        val p = Playback(film, OffsetTracker(film.subs))
        p.play(film.lengthMs)
        assertTrue("${p.tracker.segments}\n${p.story()}", p.tracker.segments.all { near(1_400, it.delayMs, 300) || near(31_400, it.delayMs, 300) })
        assertTrue("${p.tracker.segments}\n${p.story()}", near(31_400, p.tracker.delayAt(45 * min), 300))
    }

    @Test
    fun soundDroppedHereAndThereIsNoJump() {
        // Played straight through a break with a few seconds' sound missed every minute (the listener falling behind):
        // the change is where the talking put it, not anywhere since the last stretch found right.
        val film = FakeFilm.make(seed = 31, minutes = 40, delayMs = 500, breaks = listOf(Break(20 * min, adMs = 60_000)))
        val p = Playback(film, OffsetTracker(film.subs))
        p.play(film.lengthMs)
        assertEquals(p.story(), 2, p.tracker.segments.size)
        for (m in 1 until 40) {
            val f = (m * min / 10).toInt()
            p.timeline.heard.clear(f, f + 400)
        }
        assertEquals(p.tracker.segments.map { it.fromMs }, p.tracker.changeFrom(p.timeline))
    }

    /** Seconds after each ad ended until its delay was followed; null where it wasn't. */
    private fun followTimes(film: FakeFilm, p: Playback): List<Long?> = film.ads.mapIndexed { k, ad ->
        val want = film.pieces.filter { it.delayMs != null }[k + 1].delayMs!!
        p.moves.firstOrNull { near(want, it.delayMs, 300) && it.toMs >= ad.toMs }?.let { (it.toMs - ad.toMs) / 1000 }
    }

    @Test
    fun aBumperTheSameLengthAgainIsFollowedQuickly() {
        // Three bumpers, each needing 15 s more: after the first, the same jump is tried first and proven on the talking
        // since in about a minute, where searching every shift takes about three.
        val breaks = (1..3).map { Break(it * 12 * min, adMs = 45_000, skipMs = 30_000) }
        val quick = ArrayList<Long>()
        val slow = ArrayList<Long>()
        for (seed in 1L..8L) {
            val film = FakeFilm.make(seed, minutes = 48, delayMs = 1_400, breaks = breaks)
            val p = Playback(film, OffsetTracker(film.subs))
            p.play(film.lengthMs)
            assertFollows(film, p, slackMs = 60_000)
            val off = Playback(film, OffsetTracker(film.subs, guessing = false)).also { it.play(film.lengthMs) }
            val t = followTimes(film, p)
            val tOff = followTimes(film, off)
            println("same bumper again (seed $seed): ${t.drop(1)} s after the ad, searching every shift ${tOff.drop(1)} s")
            quick += t.drop(1).map { it!! }
            slow += tOff.drop(1).map { it!! }
            assertTrue(p.story(), p.moves.any { it.note.contains("tried first") })
        }
        quick.sort()
        slow.sort()
        println("median ${quick[quick.size / 2]} s, was ${slow[slow.size / 2]} s")
        // This made-up film talks sparsely (about 6 lines a minute): with more talking it's quicker still.
        assertTrue("$quick vs $slow", quick[quick.size / 2] <= 120 && quick.sum() < slow.sum() * 3 / 4)
    }

    @Test
    fun aJumpRememberedFromAnotherEpisodeIsTriedFirst() {
        // One bumper, the same length as one in another episode of the show: tried first from the start.
        val breaks = listOf(Break(12 * min, adMs = 45_000, skipMs = 30_000))
        var faster = 0
        for (seed in 1L..8L) {
            val film = FakeFilm.make(seed, minutes = 30, delayMs = 1_400, breaks = breaks)
            val p = Playback(film, OffsetTracker(film.subs, knownJumps = listOf(15_000L)))
            p.play(film.lengthMs)
            val fresh = Playback(film, OffsetTracker(film.subs)).also { it.play(film.lengthMs) }
            // Where the change is put is as precise as when it's searched for (a line or two after the ad, at times).
            assertFollows(film, p, slackMs = 60_000)
            val t = followTimes(film, p)[0]!!
            val tFresh = followTimes(film, fresh)[0]!!
            println("remembered jump (seed $seed): $t s, not remembered $tFresh s")
            if (t < tFresh) faster++
        }
        assertTrue("faster in $faster of 8", faster >= 6)
    }

    @Test
    fun wrongGuessesDoNotMoveIt() {
        // Breaks of other lengths than the jumps tried first (and one 1.5 s off), sparse talking too: no delay is made up.
        val cases = listOf(
            listOf(Break(12 * min, adMs = 45_000, skipMs = 30_000), Break(24 * min, adMs = 70_000, skipMs = 30_000), Break(36 * min, adMs = 20_000, skipMs = 30_000)),
            listOf(Break(12 * min, adMs = 45_000, skipMs = 30_000), Break(24 * min, adMs = 46_500, skipMs = 30_000)),
            listOf(Break(12 * min, adMs = 45_000, skipMs = 30_000)),
        )
        for (breaks in cases) for ((noise, missed) in listOf(0.08 to 0.1, 0.2 to 0.3)) for (seed in 1L..8L) {
            val film = FakeFilm.make(seed, minutes = 48, delayMs = 1_400, breaks = breaks, noise = noise, missed = missed)
            val p = Playback(film, OffsetTracker(film.subs, knownJumps = listOf(15_000L, -8_000L)))
            p.play(film.lengthMs)
            val truth = film.pieces.mapNotNull { it.delayMs }
            assertTrue("seed $seed noise $noise: ${p.tracker.segments}\n${p.story()}",
                p.tracker.segments.all { s -> truth.any { near(it, s.delayMs, 300) } })
        }
    }

    @Test
    fun jumpsAreRememberedNewestFirst() {
        val segs = listOf(Segment(0, 1_400), Segment(600_000, 16_400), Segment(1_200_000, 16_500), Segment(1_800_000, 31_400))
        // 15 s twice (and 0.1 s between, too little to be a jump): once, the newest, then the older ones, at most 4.
        assertEquals(listOf(14_900L, -8_000L, 60_000L, 5_000L), OffsetTracker.rememberJumps(listOf(15_050L, -8_000L, 60_000L, 5_000L, 9_000L), segs))
    }

    @Test
    fun whereAChangeMayBeginAfterAJumpPastIt() {
        // Heard to 8:00, then a jump to 40:00, past a break at 25:00: the change is put where the viewer landed, but it
        // may be anywhere from the end of the talking found right before (about 8:00). Played through, it's known.
        val film = FakeFilm.make(seed = 70, minutes = 60, delayMs = 500, breaks = listOf(Break(25 * min, adMs = 50_000, skipMs = 30_000)))
        val p = Playback(film, OffsetTracker(film.subs))
        p.play(8 * min)
        p.seek(40 * min)
        p.play(44 * min)
        assertEquals(p.story(), 2, p.tracker.segments.size)
        val jumped = p.tracker.changeFrom(p.timeline)
        assertEquals(0L, jumped[0])
        assertTrue("$jumped ${p.tracker.segments}", jumped[1] <= 8 * min + 5_000 && jumped[1] < p.tracker.segments[1].fromMs)
        p.seek(20 * min)
        p.play(30 * min)
        val heard = p.tracker.changeFrom(p.timeline)
        assertEquals("$heard ${p.tracker.segments}", p.tracker.segments[1].fromMs, heard[1])
    }

    @Test
    fun aChangeBeyondTheRangeSearchedIsNotGuessed() {
        // 4 or 5 minutes of ads the subtitles don't allow for: the right delay is beyond the 3 minutes searched. No
        // delay is made up (the subtitles stay as they were), and the checks that keep coming out unsure are spaced out.
        for (seed in 1L..4L) {
            val ad = if (seed % 2 == 0L) 240_000L else 300_000L
            val film = FakeFilm.make(seed, minutes = 65, delayMs = 0, breaks = listOf(Break(12 * min, adMs = ad)))
            val p = Playback(film, OffsetTracker(film.subs), formatHints = seed <= 2)
            p.play(film.lengthMs)
            assertTrue(p.story(), p.moves.isEmpty())
            assertTrue(p.story(), p.tracker.segments.all { near(0, it.delayMs, 400) })
            val late = p.decisions.count { it.outcome != Outcome.WAIT && it.toMs > 35 * min }
            println("${ad / 1000} s of ads (seed $seed): ${p.matches} matches in all, $late in the last 30 min, nothing moved")
            // Every 15 s of talking would be over 100.
            assertTrue(p.story(), late <= 20)
        }
    }

    @Test
    fun aDelayFoundElsewhereIsBorneOutByFreshTalking() {
        // What the second of two agreeing matches rests on: on talking the first didn't have, the right delay stands
        // out from the shifts 3 minutes either way; a wrong one, named beforehand, rarely does.
        for (talk in listOf(10_000L, 20_000L)) {
            var right = 0
            var wrong = 0
            var n = 0
            for (seed in 1L..4L) {
                val film = FakeFilm.make(seed, minutes = 60, delayMs = 0)
                val tl = SpeechTimeline()
                for (f in 0 until (film.lengthMs / 10).toInt()) tl.mark(f, film.talking.get(f))
                val rnd = java.util.Random(seed)
                var t = 5 * min
                while (t < 55 * min) {
                    var e = t
                    while (tl.speech.get((t / 10).toInt(), (e / 10).toInt()).cardinality() * 10L < talk) e += 1_000
                    val h = SyncMatcher.heard(tl.speech, tl.heard, (t / 10).toInt(), (e / 10).toInt())!!
                    val inUse = -45_000L
                    var other: Long
                    do { other = inUse - 175_000 + rnd.nextInt(350_000) } while (abs(other) < 3_000)
                    if (SyncMatcher.zAt(h, film.subs, 0, inUse, OffsetTracker.RANGE_MS) >= 2.0) right++
                    if (SyncMatcher.zAt(h, film.subs, other, inUse, OffsetTracker.RANGE_MS) >= 2.0) wrong++
                    n++
                    t += 30_000
                }
            }
            println("on ${talk / 1000} s of fresh talking: the right delay stands out ${right * 100 / n}% of the time, a wrong one ${"%.1f".format(wrong * 100.0 / n)}% ($n tries)")
            assertTrue(wrong * 20 <= n)
            if (talk >= 20_000) assertTrue(right * 10 >= n * 8)
        }
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
    fun resyncRelearnsAndCarriesOn() {
        val film = FakeFilm.make(
            seed = 80, minutes = 70, delayMs = 0,
            breaks = listOf(Break(15 * min, adMs = 30_000), Break(45 * min, adMs = 50_000, skipMs = 30_000)),
        )
        val p = Playback(film, OffsetTracker(film.subs))
        p.play(25 * min)
        assertEquals(p.story(), 2, p.tracker.segments.size)

        // Resync at 25 min, though the delay was right: the viewer turned it down, so talking heard before the press
        // doesn't bring it back; talking after it does (the subtitles stay as they are meanwhile).
        val at = p.posMs
        p.tracker.relearn(at, centerMs = p.tracker.delayAt(at)!!, p.timeline)
        assertTrue(p.tracker.learning)
        // Listening starts again a moment later than where it stopped (the sound waiting in between is let go).
        p.seek(at + 400)
        p.play(30 * min)
        val learned = p.decisions.last { it.outcome == Outcome.LEARNED }
        println("resync on a delay that was right: sure again ${(learned.toMs - at) / 1000} s after, from talking after the press")
        assertTrue(p.story(), learned.fromMs >= at)
        assertTrue(p.story(), learned.toMs - at <= 3 * min)
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
    fun resyncSoonAfterABreakNotFoundYet() {
        // Subtitles right until 30 s of ads they don't allow for, with no format change to hint at it: the viewer sees
        // them late after it and presses Resync before the tracker has found it. The late delay mustn't come back from
        // what was heard before the break; the right one is found as soon as the talking after it allows.
        val took = ArrayList<Long>()
        for (seed in 1L..4L) for (after in listOf(30_000L, 60_000L, 90_000L)) {
            val film = FakeFilm.make(seed, minutes = 40, delayMs = 0, breaks = listOf(Break(20 * min, adMs = 30_000)))
            val ad = film.ads.single()
            val p = Playback(film, OffsetTracker(film.subs), formatHints = false)
            p.play(ad.toMs + after)
            val at = p.posMs
            val turnedDown = p.tracker.delayAt(at)!!
            if (near(30_000, turnedDown, 400)) continue
            p.tracker.relearn(at, turnedDown, p.timeline)
            p.seek(at + 400)
            p.play(at + 6 * min)
            val since = p.decisions.filter { it.toMs > at }
            assertFalse(p.story(), since.any { it.outcome == Outcome.LEARNED && near(turnedDown, it.delayMs, 400) })
            val right = since.first { (it.outcome == Outcome.LEARNED || it.outcome == Outcome.MOVED) && near(30_000, p.tracker.delayAt(it.toMs), 400) }
            took += right.toMs - at
        }
        println("resync ${listOf(30, 60, 90)} s after a break not found yet (4 films): right again ${took.map { it / 1000 }} s after the press")
        assertTrue("$took", took.size >= 8)
        assertTrue("$took", took.max() <= 5 * min)
    }

    @Test
    fun otherSubtitlesSoonAfterABreakFitWhereTheFilmIs() {
        // Other subtitles picked 1.5 or 2.5 min after a break nobody found yet: they carry on with what was heard, and
        // are lined up for where the film is now, not for before the break.
        val learnedFor = ArrayList<String>()
        for (seed in 1L..6L) for (after in listOf(90_000L, 150_000L)) {
            val film = FakeFilm.make(seed, minutes = 40, delayMs = 0, breaks = listOf(Break(20 * min, adMs = 45_000)))
            val ad = film.ads.single()
            val before = Playback(film, OffsetTracker(film.subs), formatHints = false)
            before.play(ad.toMs + after)
            val p = Playback(film, OffsetTracker(film.subs), formatHints = false, timeline = before.timeline, startMs = before.posMs)
            p.seek(before.posMs)
            p.play(before.posMs + 4 * min)
            val learned = p.decisions.first { it.outcome == Outcome.LEARNED }
            learnedFor += "${SyncMatcher.formatShift(learned.delayMs)} ${(learned.toMs - before.posMs) / 1000}s"
            assertTrue(p.story(), near(45_000, p.tracker.delayAt(p.posMs), 400))
        }
        println("other subtitles after a break: learned $learnedFor after the switch")
    }

    @Test
    fun aDelaySureOnceIsTheOneLearnedNearlyAlways() {
        // Auto sync puts in use what one look was sure of, while the next looks confirm it: it must nearly always be
        // the delay then learned, so the subtitles are right seconds sooner and seldom moved twice.
        var tentatives = 0
        var wrong = 0
        val story = StringBuilder()
        for (seed in 1L..24L) {
            val delay = (seed % 7 - 3) * 1_700L
            val film = FakeFilm.make(seed = 300 + seed, minutes = 20, delayMs = delay)
            val p = Playback(film, OffsetTracker(film.subs))
            val seen = LinkedHashSet<Long>()
            while (p.posMs < 12 * min && p.tracker.segments.isEmpty()) {
                p.play(p.posMs + OffsetTracker.STEP_MS)
                p.tracker.tentativeMs?.let { seen += it }
            }
            assertNull("no tentative once learned", p.tracker.tentativeMs)
            tentatives += seen.size
            val bad = seen.filter { !near(delay, it, 400) }
            wrong += bad.size
            if (bad.isNotEmpty()) story.append("seed $seed (delay $delay): put in use ${bad.joinToString()}\n")
        }
        println("delays sure once: $tentatives, not the right one: $wrong\n$story")
        assertTrue("$tentatives sure once, $wrong wrong\n$story", tentatives >= 12 && wrong * 10 <= tentatives)
    }

    @Test
    fun turnedOnTheDelayInUseIsNotTurnedDown() {
        // Auto sync turned on with a delay set by hand, the film around heard already (kept ready, heard ahead): the
        // first look may take the delay in use at once. Resync instead turns it down until fresh talking brings it back.
        val film = FakeFilm.make(seed = 82, minutes = 30, delayMs = 3_000)
        val heard = Playback(film, OffsetTracker(SyncMatcher.Lines(emptyList())))
        heard.play(6 * min)
        for (reject in listOf(false, true)) {
            val tracker = OffsetTracker(film.subs)
            tracker.relearn(6 * min, centerMs = 3_000, heard.timeline, heardToMs = 6 * min, reject = reject)
            val d = tracker.step(heard.timeline, 6 * min, OffsetTracker.STEP_MS)
            val atOnce = (d.outcome == Outcome.LEARNED && near(3_000, d.delayMs, 400)) || near(3_000, tracker.tentativeMs, 400)
            assertEquals("reject=$reject: $d", !reject, atOnce)
        }
    }

    @Test
    fun aBreakJustAfterListeningStartsDoesntHoldUpTheFirstDelay() {
        // Listening starts a minute and a half before a short bumper that moves the delay by 2.3 s (Legion on the Fire TV
        // Stick 4K Max: +6.7 s, then +9.0 s at 31:33). The talking either side of it fits two delays, so a look over both
        // is sure of neither; the stretch after it, looked at alone, is.
        val took = ArrayList<Long>()
        val story = StringBuilder()
        var wrong = 0
        for (seed in 1L..10L) {
            val film = FakeFilm.make(seed = 400 + seed, minutes = 30, delayMs = 6_700, breaks = listOf(Break(10 * min, adMs = 2_300)))
            val start = film.ads.single().fromMs - 90_000
            val p = Playback(film, OffsetTracker(film.subs), formatHints = false, startMs = start)
            p.seek(start)
            p.play(start + 12 * min)
            val learned = p.decisions.firstOrNull { it.outcome == Outcome.LEARNED }
            val after = film.ads.single().toMs + 60_000
            val right = learned != null && near(9_000, p.tracker.delayAt(maxOf(after, learned.toMs)), 400)
            if (!right) wrong++
            took += learned?.let { it.toMs - start } ?: Long.MAX_VALUE
            story.append("seed $seed: ${learned?.let { "${SyncMatcher.formatShift(it.delayMs)} after ${(it.toMs - start) / 1000} s" } ?: "never"}${if (right) "" else " WRONG"}\n")
        }
        val sorted = took.sorted()
        println("learned across a break: median ${sorted[sorted.size / 2] / 1000} s, worst ${sorted.last() / 1000} s, wrong $wrong\n$story")
        assertEquals(story.toString(), 0, wrong)
        // Looking at the whole stretch only: median 303 s (the stretch had to move past the break). Now the part after it is
        // looked at alone too: as soon as there's talking enough after the break to be sure (about 100 s of film here).
        assertTrue(story.toString(), sorted[sorted.size / 2] <= 270_000)
    }

    @Test
    fun aDelayFromBeforeABreakIsntLearnedAfterIt() {
        // Listening from a minute before a 2.3 s bumper: a delay sure once on the talking before it (by the overlap, by
        // sentence starts or steady) is only kept once the talking since bears it out, so the film past the bumper never
        // ends up with the delay from before it saved (the review of sentence starts: 8 in 40 did).
        var wrong = 0
        val story = StringBuilder()
        for (seed in 401L..420L) for (before in listOf(60_000L, 75_000L)) {
            val film = FakeFilm.make(seed = seed, minutes = 20, delayMs = 6_700, pace = FakeFilm.TALKY, breaks = listOf(Break(10 * min, adMs = 2_300)))
            val ad = film.ads.single()
            val start = ad.fromMs - before
            val p = Playback(film, OffsetTracker(film.subs), formatHints = false, startMs = start)
            p.seek(start)
            p.play(start + 8 * min)
            val learned = p.decisions.firstOrNull { it.outcome == Outcome.LEARNED } ?: continue
            if (learned.toMs > ad.toMs + 30_000 && !near(9_000, learned.delayMs, 400)) {
                wrong++
                story.append("seed $seed, ${before / 1000} s before: $learned\n")
            }
        }
        assertEquals(story.toString(), 0, wrong)
    }

    @Test
    fun aSkipWhileFindingTheFirstDelayIsAFreshTry() {
        // Subtitles that never match: after LEARN_MAX_MS of film it gives up once (auto sync then tries afresh rather than
        // stopping), and a skip far away is a fresh try too, not counted towards giving up.
        val film = FakeFilm.make(seed = 90, minutes = 40, delayMs = 0)
        val other = FakeFilm.make(seed = 91, minutes = 40, delayMs = 0)
        val tracker = OffsetTracker(other.subs)
        val p = Playback(film, tracker)
        p.play(5 * min)
        assertTrue(p.story(), p.decisions.none { it.outcome == Outcome.GAVE_UP })
        p.seek(20 * min)
        p.play(25 * min)
        assertTrue("a skip starts the count again\n${p.story()}", p.decisions.none { it.outcome == Outcome.GAVE_UP })
        p.play(28 * min)
        val gaveUp = p.decisions.indexOfFirst { it.outcome == Outcome.GAVE_UP }
        assertTrue("gives up after six minutes of trying\n${p.story()}", gaveUp >= 0)
        tracker.learnAgain()
        val after = p.decisions.size
        p.play(29 * min)
        assertTrue("tries afresh after learnAgain\n${p.story()}", p.decisions.drop(after).none { it.outcome == Outcome.GAVE_UP })
    }

    @Test
    fun aSkipOntoFilmNotHeardIsCheckedThereAndKept() {
        // Delays known: a skip far ahead is counted (the panel says Rechecking) and a look after it keeps or moves the
        // delay; going back over film heard already isn't a skip to check.
        val film = FakeFilm.make(seed = 82, minutes = 40, delayMs = 2_000)
        val tracker = OffsetTracker(film.subs)
        val p = Playback(film, tracker)
        p.play(8 * min)
        assertTrue(p.story(), near(2_000, tracker.delayAt(5 * min)))
        assertEquals(0, tracker.jumpChecks)
        p.seek(25 * min)
        assertEquals(1, tracker.jumpChecks)
        assertEquals(25 * min, tracker.jumpedToMs)
        val after = p.decisions.size
        p.play(31 * min)
        assertTrue("a look after the skip keeps or moves the delay\n${p.story()}", p.decisions.drop(after).any {
            it.toMs > 25 * min && (it.outcome == Outcome.KEPT || it.outcome == Outcome.MOVED)
        })
        p.seek(26 * min)
        assertEquals("back over film heard already", 1, tracker.jumpChecks)
    }

    @Test
    fun resyncWithNothingHeardListensAfresh() {
        val film = FakeFilm.make(seed = 81, minutes = 30, delayMs = 3_000)
        val tracker = OffsetTracker(film.subs, listOf(Segment(0, 9_000)))
        val p = Playback(film, tracker)
        tracker.relearn(10 * min, centerMs = 9_000, p.timeline)
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
    fun aStretchTooShortToBeRealIsDroppedWhenReadBack() {
        // Kept by an older version on a Fire TV: 13 s at the first stretch's delay between two breaks.
        val kept = listOf(
            Segment(0, 1_400), Segment(779_000, 3_400), Segment(1_197_000, 1_400), Segment(1_210_000, 4_700),
            Segment(1_748_000, 6_500),
        )
        assertEquals(
            listOf(Segment(0, 1_400), Segment(779_000, 3_400), Segment(1_210_000, 4_700), Segment(1_748_000, 6_500)),
            OffsetTracker.restore(OffsetTracker.encode(kept), null),
        )
        // Left with the same delay on both sides of it, the two become one.
        val same = listOf(Segment(0, 1_400), Segment(600_000, 4_700), Segment(990_000, 1_400), Segment(1_000_000, 4_700))
        assertEquals(listOf(Segment(0, 1_400), Segment(600_000, 4_700)), OffsetTracker.restore(OffsetTracker.encode(same), null))
        // The last stretch runs to the end of the film, however late it starts; the first is always kept.
        val late = listOf(Segment(0, 1_400), Segment(2_000_000, 3_000))
        assertEquals(late, OffsetTracker.restore(OffsetTracker.encode(late), null))
        assertEquals(listOf(Segment(0, 900), Segment(5_000, 1_400)), OffsetTracker.withoutSlivers(listOf(Segment(0, 900), Segment(5_000, 1_400))))
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
        // The most one check does: a first sure match confirmed at once from what was heard, which also matches all
        // but the latest 20 s of talking (about 3.5 min) and weighs the delay on that latest talking (about 45 s).
        val mid = (63 * min + 15_000) / 10
        fun most() {
            once()
            SyncMatcher.match(SyncMatcher.heard(tl.speech, tl.heard, from, mid.toInt())!!, film.subs, 1_000, OffsetTracker.RANGE_MS)
            SyncMatcher.zAt(SyncMatcher.heard(tl.speech, tl.heard, mid.toInt(), to)!!, film.subs, 1_000, 1_000, OffsetTracker.RANGE_MS)
        }
        repeat(10) { once(); most() }
        val times = (1..20).map { val t0 = System.nanoTime(); once(); (System.nanoTime() - t0) / 1e6 }.sorted()
        val mostTimes = (1..20).map { val t0 = System.nanoTime(); most(); (System.nanoTime() - t0) / 1e6 }.sorted()
        val r = once()
        assertTrue("$r", r.confident && near(1_000, r.delayMs, 200))
        println("one check (4 min heard, ±3 min): median ${"%.1f".format(times[10])} ms, worst ${"%.1f".format(times.last())} ms; " +
            "confirming at once as well: median ${"%.1f".format(mostTimes[10])} ms")
        assertTrue("median ${times[10]} ms", times[10] < 200)
        assertTrue("median ${mostTimes[10]} ms", mostTimes[10] < 400)

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
