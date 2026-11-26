package app.fireplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The film's buffer sized by the memory free as a film starts, on a TV short of memory: how small it starts, what it
 * never goes under, how it grows back once memory has been quiet, and how it steps down under pressure. The cases are
 * the two Fire TV Sticks as measured with FirePlayer 1.0.366 (1.7 GB each).
 */
class FilmBufferStartTest {

    private val mib = 1L shl 20
    // What Fire OS allows FirePlayer on the Sticks, and Android's low mark for a 1080p screen (147,456 kB).
    private val stickHeap = 384 * mib
    private val lowMark = 144 * mib
    private val longSpans = 16 * mib

    private fun mib(bytes: Int) = (bytes / mib).toInt()

    private fun stick(freeMiB: Long, rateMbps: Double = 0.0, lineMbps: Double = 0.0, keep: Int = 0) =
        FilmMemory.plan(RamTier.SMALL, stickHeap, freeMiB * mib, keep, lowMark, rateMbps, lineMbps)

    // ---- Why 397 MiB free still gave 112 MiB

    @Test fun theOldReserveLeftFireOsLessThanItsLowMark() {
        // All but 260 MiB: 137 MiB, so the whole 128, of which the long spans took 16. Anything from 388 MiB free did.
        val old = FilmMemory.plan(RamTier.LARGE, 256 * mib, 397 * mib)
        assertEquals(137, mib(old.budgetBytes))
        assertEquals(128L, minOf(128L, (397 * mib - FilmMemory.RESERVE_BYTES) / mib))
        assertEquals(388 * mib, FilmMemory.RESERVE_BYTES + 128 * mib)
        // With the decoder's 200 MiB taken, that reserve leaves 60 MiB: under the mark at which Fire OS calls memory low.
        assertTrue(FilmMemory.RESERVE_BYTES - FilmMemory.DECODER_BYTES < lowMark)
        // What a Stick's start leaves now: the decoder's, the player's own, the low mark and a margin over it.
        assertEquals(440 * mib, FilmMemory.startReserve(lowMark))
    }

    // ---- The Fire TV Stick 4K Plus: 397 MiB free, an 86 GB remux at about 58 Mb/s on a line bringing 83 to 92

    @Test fun thePlusStartsWithWhatKeepsEightSecondsAhead() {
        val plan = stick(397, rateMbps = 58.0, lineMbps = 83.0)
        // Free memory leaves nothing over the reserve: the buffer is what 8 s ahead and 4 s behind (up to 16 MiB) take.
        assertEquals(72, mib(plan.budgetBytes))
        assertEquals(FilmBufferPlan.StartBy.AHEAD, plan.startBy)
        assertEquals(8_000_000L, plan.aheadFloorUs)
        assertTrue(plan.byFreeMemory)
        assertEquals(128, mib(plan.tierBytes))
        assertEquals(4_000, plan.backBufferMs)
        assertEquals(16 * mib, plan.backCapBytes)
        assertEquals(440 * mib, plan.reserveBytes)
        // 58 Mb/s are 6.9 MiB a second: what's left ahead of the 16 MiB kept behind is 8 s of it.
        val aheadSeconds = (plan.budgetBytes - plan.backCapBytes) / (58_000_000.0 / 8)
        assertTrue("$aheadSeconds", aheadSeconds in 8.0..8.2)
    }

    @Test fun thePlusKeepsItsDownloadAsItWasAndHoldsFortyMiBLess() {
        val planned = stick(397, rateMbps = 58.0, lineMbps = 83.0)
        // The download goes by the old mark: long spans and wide sockets from 388 MiB free, as in 1.0.366.
        assertFalse(planned.leanDownload)
        assertTrue(FilmMemory.longSpansFit(planned))
        val plan = FilmMemory.withSecondRequest(planned, longSpans)
        // The spans come out of the size it grows to, not out of the seconds it needs ahead.
        assertEquals(72, mib(plan.budgetBytes))
        assertEquals(112, mib(plan.tierBytes))
        assertEquals(16, mib(plan.spanExtraBytes))
        assertTrue(plan.byFreeMemory)
        // 1.0.366: 112 MiB and 16 of spans. Now 72 and 16: 40 MiB less asked for in the film's first seconds.
        assertEquals(40, 112 + 16 - (mib(plan.budgetBytes) + mib(plan.spanExtraBytes)))
    }

    @Test fun thePlusSaysSoInTheLog() {
        val plan = FilmMemory.withSecondRequest(stick(397, rateMbps = 58.0, lineMbps = 83.0), longSpans)
        assertEquals(
            "Film buffer: SMALL memory, 397 MiB free (Fire OS calls it low under 144 MiB, swap 0 of 819 MiB free), " +
                "film about 58 Mb/s on a connection bringing 83: starts at 72 MiB, for 8 s of it ahead (free memory leaves " +
                "no more: 440 MiB are left for the decoder and Fire OS), up to 112 MiB once memory has been quiet, " +
                "4 s kept behind up to 16 MiB, 16 MiB given to the second request's long spans",
            FilmMemory.startWords(plan, 397 * mib, 58.0, 83.0) { "0 of 819 MiB" },
        )
    }

    // ---- The Fire TV Stick 4K Max: 540 MiB free, a 52 GB copy at about 35 Mb/s

    @Test fun theMaxStartsWithWhatIsFreeOverTheReserve() {
        val planned = stick(540, rateMbps = 35.0)
        // 540 less 440: 100 MiB. More than its floor (64 MiB; 8 s ahead of 35 Mb/s and 16 MiB behind are 50).
        assertEquals(100, mib(planned.budgetBytes))
        assertEquals(FilmBufferPlan.StartBy.FREE, planned.startBy)
        assertEquals(64, mib(planned.floorBytes))
        assertTrue(planned.byFreeMemory)
        val plan = FilmMemory.withSecondRequest(planned, longSpans)
        // 84 MiB and 16 of spans where it was 112 and 16; it grows to 112.
        assertEquals(84, mib(plan.budgetBytes))
        assertEquals(112, mib(plan.tierBytes))
        // 84 MiB less the 16 kept behind are 15 s of a 35 Mb/s film ahead.
        assertTrue((plan.budgetBytes - plan.backCapBytes) / (35_000_000.0 / 8) > 15.0)
        assertEquals(
            "Film buffer: SMALL memory, 540 MiB free (Fire OS calls it low under 144 MiB), film about 35 Mb/s: starts at " +
                "84 MiB, what's free once 440 MiB are left for the decoder and Fire OS, up to 112 MiB once memory has been " +
                "quiet, 4 s kept behind up to 16 MiB, 16 MiB given to the second request's long spans",
            FilmMemory.startWords(plan, 540 * mib, 35.0, 0.0) { null },
        )
    }

    // ---- Plenty of memory: nothing changes

    @Test fun aTvWithThreeGigabytesIsUnchangedWhateverIsFree() {
        val heap = 512 * mib
        for (free in listOf(100L, 300L, 397L, 450L, 516L, 540L, 2_000L)) {
            val plain = FilmMemory.plan(RamTier.LARGE, heap, free * mib)
            // The rule of 1.0.366: all but 260 MiB, between 64 and its 256.
            assertEquals("$free", (free - 260).coerceIn(64L, 256L).toInt(), mib(plain.budgetBytes))
            assertEquals("$free", free < 516, plain.byFreeMemory)
            assertEquals("$free", plain.byFreeMemory, plain.leanDownload)
            assertEquals(FilmMemory.RESERVE_BYTES, plain.reserveBytes)
            // Neither Fire OS's low mark nor the film's rate nor the line's speed moves it.
            assertEquals("$free", plain, FilmMemory.plan(RamTier.LARGE, heap, free * mib, 0, lowMark, 58.0, 83.0))
            assertEquals(0, plain.floorBytes)
            assertEquals(0L, plain.lowMarkBytes)
        }
        // Its squeeze is the two steps it always was, and its buffer never grows by the clock.
        val p = MemoryPressure(fine = RamTier.LARGE.short)
        assertTrue(p.trimAsked(2, 1_000))
        assertEquals(FilmMemory.STEPS, p.step)
        assertEquals(128 * mib, FilmMemory.capFor((256 * mib).toInt(), p.step).toLong())
        assertFalse(MemoryPressure(fine = false).mayGrow(10 * 60_000L))
    }

    @Test fun aStickWithPlentyFreeIsUnchanged() {
        // From 568 MiB free (the reserve and its 128): the whole size, all it keeps behind, long spans, wide sockets.
        for (free in listOf(568L, 600L, 900L)) {
            val plan = stick(free, rateMbps = 58.0, lineMbps = 83.0)
            assertEquals(128, mib(plan.budgetBytes))
            assertFalse(plan.byFreeMemory)
            assertFalse(plan.leanDownload)
            assertEquals(FilmBufferPlan.StartBy.TIER, plan.startBy)
            assertEquals(6_000, plan.backBufferMs)
            assertEquals(24 * mib, plan.backCapBytes)
            val shared = FilmMemory.withSecondRequest(plan, longSpans)
            assertEquals(112, mib(shared.budgetBytes))
            assertEquals(112, mib(shared.tierBytes))
            // Nothing to grow to, and the seconds floor has nothing to add: its limit is its budget, as ever.
            assertEquals(0L, FilmMemory.growNeed(shared.budgetBytes, shared.tierBytes, 0L, lowMark))
            assertEquals(shared.budgetBytes, FilmMemory.grown(shared.budgetBytes, shared.tierBytes, 900 * mib, 0L, lowMark))
        }
        assertEquals(
            "Film buffer: SMALL memory, 600 MiB free, 112 MiB, 6 s kept behind up to 24 MiB, 16 MiB given to the second request's long spans",
            FilmMemory.startWords(FilmMemory.withSecondRequest(stick(600), longSpans), 600 * mib, 58.0, 83.0) { error("not read") },
        )
        // A "2 GB" TV the same, from the reserve and its 176.
        val medium = FilmMemory.plan(RamTier.MEDIUM, stickHeap, 616 * mib, 0, lowMark, 58.0, 83.0)
        assertEquals(176, mib(medium.budgetBytes))
        assertFalse(medium.byFreeMemory)
        assertEquals(10_000, medium.backBufferMs)
    }

    @Test fun theSmallestTvsKeepTheir64MiB() {
        // The tiny tier's whole size is the floor: free memory never held it under, and doesn't now.
        for (free in listOf(50L, 300L, 800L)) {
            val plan = FilmMemory.plan(RamTier.TINY, stickHeap, free * mib, 0, lowMark, 58.0, 83.0)
            assertEquals(64, mib(plan.budgetBytes))
            assertFalse(plan.byFreeMemory)
        }
    }

    @Test fun theDownloadKeepsToTheOldMark() {
        // Short spans and narrow sockets exactly where 1.0.366 had them: under 388 MiB free on a Stick.
        assertTrue(stick(387).leanDownload)
        assertFalse(FilmMemory.longSpansFit(stick(387)))
        assertFalse(stick(388).leanDownload)
        assertTrue(FilmMemory.longSpansFit(stick(388)))
        assertTrue(stick(388).byFreeMemory)
        // A rebuilt player that keeps its film's whole budget keeps its long spans too, as before.
        assertFalse(stick(200, keep = (128 * mib).toInt()).leanDownload)
    }

    // ---- The seconds ahead a buffer held down never goes under

    @Test fun aThinLineKeepsEightSecondsAheadAndARoomyOneSix() {
        assertEquals(8_000_000L, FilmMemory.aheadFloorUs(null))
        assertEquals(8_000_000L, FilmMemory.aheadFloorUs(1.0f))
        assertEquals(8_000_000L, FilmMemory.aheadFloorUs(1.49f))
        assertEquals(6_000_000L, FilmMemory.aheadFloorUs(1.5f))
        assertEquals(6_000_000L, FilmMemory.aheadFloorUs(3f))
        // Both are more than the 5 s a squeezed buffer keeps.
        assertTrue(FilmMemory.ROOMY_AHEAD_US > FilmMemory.MIN_AHEAD_US)
    }

    @Test fun theFloorIsThoseSecondsOfTheFilmAndWhatIsKeptBehind() {
        val cap = 16 * mib
        // 58 Mb/s on 83 (1.4 times): 8 s and 16 MiB behind, 72 MiB. On 120 (2.1 times): 6 s, 58 MiB.
        assertEquals(72 * mib, FilmMemory.floorBytes(58.0, 83.0, 4_000, cap).toLong())
        assertEquals(58 * mib, FilmMemory.floorBytes(58.0, 120.0, 4_000, cap).toLong())
        // The line not measured: the longer of the two.
        assertEquals(72 * mib, FilmMemory.floorBytes(58.0, 0.0, 4_000, cap).toLong())
        // An 80 Mb/s remux on a 90 Mb/s line: 93 MiB. A lighter film keeps all 4 s behind: 20 Mb/s, 29 MiB.
        assertEquals(93 * mib, FilmMemory.floorBytes(80.0, 90.0, 4_000, cap).toLong())
        assertEquals(29 * mib, FilmMemory.floorBytes(20.0, 0.0, 4_000, cap).toLong())
        // The film's rate not known: no floor of its own.
        assertEquals(0, FilmMemory.floorBytes(0.0, 83.0, 4_000, cap))
        assertEquals(0, FilmMemory.floorBytes(Double.NaN, 83.0, 4_000, cap))
    }

    @Test fun aHeavyFilmStartsBiggerThanALightOneWithTheSameMemoryFree() {
        // 300 MiB free: nothing over the reserve.
        assertEquals(64, mib(stick(300).budgetBytes))
        assertEquals(FilmBufferPlan.StartBy.FLOOR, stick(300).startBy)
        assertEquals(64, mib(stick(300, rateMbps = 20.0, lineMbps = 90.0).budgetBytes))
        assertEquals(72, mib(stick(300, rateMbps = 58.0, lineMbps = 83.0).budgetBytes))
        assertEquals(93, mib(stick(300, rateMbps = 80.0, lineMbps = 90.0).budgetBytes))
        // The same film on a line with room for it: 6 s ahead are under the 64 MiB any film starts with.
        assertEquals(64, mib(stick(300, rateMbps = 58.0, lineMbps = 120.0).budgetBytes))
        // Never past the TV's own size: a film too heavy for it gets all of that, and what the tier keeps behind.
        val huge = stick(300, rateMbps = 150.0, lineMbps = 160.0)
        assertEquals(128, mib(huge.budgetBytes))
        assertFalse(huge.byFreeMemory)
        assertEquals(6_000, huge.backBufferMs)
        // And where free memory gives more than the floor, the floor changes nothing.
        assertEquals(100, mib(stick(540, rateMbps = 58.0, lineMbps = 83.0).budgetBytes))
    }

    @Test fun theSecondsAreKeptAsTheFilmLoadsWhateverItsRateWasSaidToBe() {
        val budget = (72 * mib).toInt()
        val tier = (112 * mib).toInt()
        val floor = FilmMemory.THIN_AHEAD_US
        // Not squeezed: the budget only limits what's held beyond 8 s ahead; under that, all the TV spares.
        assertEquals(tier, FilmMemory.fillLimit(budget, budget, floor - 1, tier, floor))
        assertEquals(budget, FilmMemory.fillLimit(budget, budget, floor, tier, floor))
        assertEquals(budget, FilmMemory.fillLimit(budget, budget, 60_000_000L, tier, floor))
        // Squeezed: the 5 s it never went under, now against a small budget too.
        val cap = FilmMemory.capFor(budget, 2)
        assertEquals(cap, FilmMemory.fillLimit(cap, budget, 6_000_000L, tier, floor))
        assertEquals(cap, FilmMemory.fillLimit(cap, budget, FilmMemory.MIN_AHEAD_US, tier, floor))
        assertEquals(tier, FilmMemory.fillLimit(cap, budget, FilmMemory.MIN_AHEAD_US - 1, tier, floor))
        // A buffer at its TV's size: the budget under 5 s ahead, the cap over, as ever.
        val whole = (128 * mib).toInt()
        val half = FilmMemory.capFor(whole, FilmMemory.STEPS)
        assertEquals(whole, FilmMemory.fillLimit(half, whole, FilmMemory.MIN_AHEAD_US - 1))
        assertEquals(half, FilmMemory.fillLimit(half, whole, FilmMemory.MIN_AHEAD_US))
        assertEquals(whole, FilmMemory.fillLimit(whole, whole, 7_000_000L))
    }

    // ---- Growing back once memory has been quiet

    @Test fun aBufferStartedSmallGrowsAStepAtATimeAfterTwoQuietMinutes() {
        val tier = (112 * mib).toInt()
        var budget = (72 * mib).toInt()
        val p = MemoryPressure(fine = true)
        p.filmStarted(0L)
        // The film's first two minutes: nothing, however much is free.
        var t = 10_000L
        while (t < 120_000L) {
            assertFalse("$t", p.mayGrow(t))
            t += 10_000L
        }
        // Then a step with every 30 s more of quiet: 88 MiB at 2:00, 104 at 2:30, its 112 at 3:00.
        val sizes = ArrayList<Int>()
        while (t <= 300_000L) {
            assertFalse(p.checked(low = false, nowMs = t))
            if (p.mayGrow(t)) {
                val next = FilmMemory.grown(budget, tier, 300 * mib, budget.toLong(), lowMark)
                if (next > budget) {
                    budget = next
                    p.grew(t)
                    sizes += mib(next) * 1000 + (t / 1000).toInt()
                }
            }
            t += 10_000L
        }
        assertEquals(listOf(88 * 1000 + 120, 104 * 1000 + 150, 112 * 1000 + 180), sizes)
        assertEquals(tier, budget)
        assertEquals(0L, FilmMemory.growNeed(budget, tier, budget.toLong(), lowMark))
    }

    @Test fun aStepUpNeedsRoomOverFireOsLowMark() {
        val tier = (112 * mib).toInt()
        val budget = (72 * mib).toInt()
        val full = budget.toLong()
        // The step (16), the low mark (144) and the margin (32): 192 MiB free.
        assertEquals(192 * mib, FilmMemory.growNeed(budget, tier, full, lowMark))
        assertEquals(budget, FilmMemory.grown(budget, tier, 191 * mib, full, lowMark))
        assertEquals(88, mib(FilmMemory.grown(budget, tier, 192 * mib, full, lowMark)))
        // What it has yet to fill of its present size is memory still to be taken: 40 of 72 MiB held, 32 more.
        assertEquals(224 * mib, FilmMemory.growNeed(budget, tier, 40 * mib, lowMark))
        assertEquals(budget, FilmMemory.grown(budget, tier, 200 * mib, 40 * mib, lowMark))
        // The last step is what's left to the TV's size, and nothing is known means nothing is taken.
        assertEquals(184 * mib, FilmMemory.growNeed((104 * mib).toInt(), tier, 104 * mib, lowMark))
        assertEquals(tier, FilmMemory.grown((104 * mib).toInt(), tier, 184 * mib, 104 * mib, lowMark))
        assertEquals(budget, FilmMemory.grown(budget, tier, 0L, full, lowMark))
        // Fire OS's mark not said: Android's own; an odd one is held between 96 and 256 MiB.
        assertEquals(144 * mib, FilmMemory.lowMark(0L))
        assertEquals(96 * mib, FilmMemory.lowMark(8 * mib))
        assertEquals(256 * mib, FilmMemory.lowMark(900 * mib))
        assertEquals(192 * mib, FilmMemory.growNeed(budget, tier, full, 0L))
    }

    @Test fun aMemoryWarningStartsTheQuietAgain() {
        val p = MemoryPressure(fine = true)
        p.filmStarted(0L)
        // Fire OS asks at 1:40: no growing at 2:00, nor while anything is squeezed.
        assertTrue(p.trimAsked(1, 100_000L))
        assertFalse(p.mayGrow(120_000L))
        assertFalse(p.mayGrow(215_000L))
        // Two minutes on the squeeze steps back, and the buffer may grow 30 s after that.
        assertTrue(p.checked(false, 220_000L))
        assertEquals(0, p.step)
        assertFalse(p.mayGrow(240_000L))
        assertTrue(p.mayGrow(250_000L))
        // A low reading at the very check stops it.
        assertTrue(p.checked(true, 260_000L))
        assertFalse(p.mayGrow(260_000L))
    }

    @Test fun aNewPlayerWaitsOutItsOwnStart() {
        val p = MemoryPressure(fine = true)
        p.filmStarted(0L)
        assertTrue(p.mayGrow(120_000L))
        p.grew(120_000L)
        assertTrue(p.mayGrow(150_000L))
        // Another source, or the film woken: its decoder takes its frames again.
        p.filmStarted(160_000L)
        assertFalse(p.mayGrow(190_000L))
        assertFalse(p.mayGrow(270_000L))
        assertTrue(p.mayGrow(280_000L))
    }

    // ---- Stepping down under pressure

    @Test fun criticalTakesAQuarterNotHalfAtOnce() {
        // The Sticks went straight to half (48 MiB) within a second of the film's start.
        val old = MemoryPressure()
        old.trimAsked(2, 1_000L)
        assertEquals(48 * mib, FilmMemory.capFor((112 * mib).toInt(), old.step, longSpans.toInt()).toLong())

        val p = MemoryPressure(fine = true)
        assertTrue(p.trimAsked(2, 1_000L))
        assertEquals(2, p.step)
        // Fire OS's requests of the same moment are one event: the strongest of them counts, not their sum.
        assertFalse(p.trimAsked(1, 1_100L))
        assertFalse(p.trimAsked(2, 1_400L))
        assertFalse(p.checked(low = true, nowMs = 5_000L))
        assertEquals(2, p.step)
        // The Plus's 72 MiB with 16 of spans: 88 in all, 66 at a quarter back, so the buffer fills to 50.
        assertEquals(50 * mib, FilmMemory.capFor((72 * mib).toInt(), p.step, longSpans.toInt()).toLong())
        // Still low at the next checks: an eighth each, to half and no further.
        assertTrue(p.checked(true, 15_000L))
        assertEquals(3, p.step)
        assertTrue(p.checked(true, 25_000L))
        assertEquals(4, p.step)
        assertFalse(p.checked(true, 35_000L))
        assertEquals(FilmMemory.STEPS, p.step)
    }

    @Test fun runningLowThenCriticalInOneMomentIsAQuarter() {
        val p = MemoryPressure(fine = true)
        assertTrue(p.trimAsked(1, 1_000L))
        assertEquals(1, p.step)
        assertTrue(p.trimAsked(2, 1_200L))
        assertEquals(2, p.step)
        // A new event 10 s on steps from there.
        assertTrue(p.trimAsked(2, 11_200L))
        assertEquals(4, p.step)
    }

    @Test fun aLowReadingThatLastsReachesHalfAtTheSameHalfMinuteByEighths() {
        val p = MemoryPressure(fine = true)
        val steps = listOf(0L, 10_000L, 20_000L, 30_000L, 40_000L).map { t -> p.checked(true, t); p.step }
        assertEquals(listOf(1, 2, 3, 4, 4), steps)
        // As before on a TV with memory to spare: a quarter, then half at 30 s.
        val old = MemoryPressure()
        assertEquals(listOf(2, 2, 2, 4, 4), listOf(0L, 10_000L, 20_000L, 30_000L, 40_000L).map { t -> old.checked(true, t); old.step })
        // A low reading that passes costs an eighth, where it cost a quarter.
        val blip = MemoryPressure(fine = true)
        blip.checked(true, 0L)
        blip.checked(false, 10_000L)
        assertEquals(1, blip.step)
        assertEquals(98 * mib, FilmMemory.capFor((112 * mib).toInt(), blip.step).toLong())
    }

    @Test fun itStepsBackAnEighthAtATimeAfterTwoQuietMinutes() {
        val p = MemoryPressure(fine = true)
        p.trimAsked(2, 0L)
        p.checked(true, 10_000L)
        p.checked(true, 20_000L)
        assertEquals(4, p.step)
        val back = ArrayList<Long>()
        var t = 30_000L
        while (t <= 300_000L) {
            if (p.checked(false, t)) back += t
            t += 10_000L
        }
        // Two minutes after the last low reading, then every 30 s: whole again 3.5 min after it (it was 4 min).
        assertEquals(listOf(140_000L, 170_000L, 200_000L, 230_000L), back)
        assertEquals(0, p.step)
    }

    @Test fun aWarningWhileSteppingBackStartsTheTwoMinutesAgain() {
        val p = MemoryPressure(fine = true)
        p.trimAsked(2, 0L)
        assertTrue(p.checked(false, 120_000L))
        assertEquals(1, p.step)
        // Low again: down an eighth, and no step back for two minutes from here.
        assertTrue(p.checked(true, 130_000L))
        assertEquals(2, p.step)
        assertFalse(p.checked(false, 160_000L))
        assertFalse(p.checked(false, 240_000L))
        assertTrue(p.checked(false, 250_000L))
        assertTrue(p.checked(false, 280_000L))
        assertEquals(0, p.step)
    }

    @Test fun theSqueezedSizesOfTheSticksBuffers() {
        // 112 MiB and 16 of spans, an eighth at a time: 96, 80, 64, 48 (the two old steps were 80 and 48).
        val whole = (112 * mib).toInt()
        assertEquals(listOf(112, 96, 80, 64, 48), (0..4).map { mib(FilmMemory.capFor(whole, it, longSpans.toInt())) })
        // The Plus's 72 MiB start: 61, 50, then the 48 no buffer goes under.
        assertEquals(listOf(72, 61, 50, 48, 48), (0..4).map { mib(FilmMemory.capFor((72 * mib).toInt(), it, longSpans.toInt())) })
    }

    // ---- The memory kept for a player rebuilt, and the swap line

    @Test fun aRebuiltPlayerKeepsWhatItsFilmHadGrownTo() {
        // Grown to 104 MiB (88 and the spans' 16), rebuilt with 300 MiB free: the same again, not the floor.
        val rebuilt = stick(300, rateMbps = 58.0, lineMbps = 83.0, keep = (104 * mib).toInt())
        assertEquals(104, mib(rebuilt.budgetBytes))
        assertEquals(FilmBufferPlan.StartBy.KEPT, rebuilt.startBy)
        assertTrue(rebuilt.byFreeMemory)
    }

    @Test fun swapIsReadForTheLogOnly() {
        val lines = listOf("MemTotal:        1710000 kB", "SwapCached:        1000 kB", "SwapTotal:        838860 kB", "SwapFree:          12288 kB")
        assertEquals("12 of 819 MiB", FilmMemory.swapWords(lines))
        assertEquals("0 of 819 MiB", FilmMemory.swapWords(listOf("SwapTotal: 838860 kB", "SwapFree: 0 kB")))
        // No swap, or nothing to read: left out of the line.
        assertNull(FilmMemory.swapWords(listOf("SwapTotal: 0 kB", "SwapFree: 0 kB")))
        assertNull(FilmMemory.swapWords(listOf("MemTotal: 1710000 kB")))
        assertNull(FilmMemory.swapWords(emptyList()))
        // The same film's buffer whatever the swap: 72 MiB.
        assertEquals(72, mib(stick(397, rateMbps = 58.0, lineMbps = 83.0).budgetBytes))
    }
}
