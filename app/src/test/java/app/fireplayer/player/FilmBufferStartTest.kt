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

    // A Stick's buffer as 1.0.366 sized it: all but 260 MiB of what's free, between 64 and its 128 MiB.
    private fun asEver(freeMiB: Long) = (freeMiB - 260).coerceIn(64L, 128L)

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
        // Free memory leaves nothing over the reserve: the buffer is what 8 s ahead and what's kept behind take. Behind
        // it keeps what it always did (6 s, up to 24 MiB).
        assertEquals(80, mib(plan.budgetBytes))
        assertEquals(FilmBufferPlan.StartBy.AHEAD, plan.startBy)
        assertEquals(8_000_000L, plan.aheadFloorUs)
        assertTrue(plan.byFreeMemory)
        assertEquals(128, mib(plan.tierBytes))
        assertEquals(128, mib(plan.everBytes))
        assertEquals(6_000, plan.backBufferMs)
        assertEquals(24 * mib, plan.backCapBytes)
        assertEquals(440 * mib, plan.reserveBytes)
        // 58 Mb/s are 6.9 MiB a second: what's left ahead of the 24 MiB kept behind is 8 s of it.
        val aheadSeconds = (plan.budgetBytes - plan.backCapBytes) / (58_000_000.0 / 8)
        assertTrue("$aheadSeconds", aheadSeconds in 8.0..8.2)
    }

    @Test fun thePlusKeepsItsDownloadAsItWasAndHoldsThirtyTwoMiBLess() {
        val planned = stick(397, rateMbps = 58.0, lineMbps = 83.0)
        // The download goes by the old mark: long spans and wide sockets from 388 MiB free, as in 1.0.366.
        assertFalse(planned.leanDownload)
        assertTrue(FilmMemory.longSpansFit(planned))
        val plan = FilmMemory.withSecondRequest(planned, longSpans)
        // The spans come out of the size it grows to, not out of the seconds it needs ahead.
        assertEquals(80, mib(plan.budgetBytes))
        assertEquals(112, mib(plan.tierBytes))
        assertEquals(112, mib(plan.everBytes))
        assertEquals(16, mib(plan.spanExtraBytes))
        assertTrue(plan.byFreeMemory)
        // 1.0.366: 112 MiB and 16 of spans. Now 80 and 16: 32 MiB less asked for in the film's first seconds.
        assertEquals(32, 112 + 16 - (mib(plan.budgetBytes) + mib(plan.spanExtraBytes)))
    }

    @Test fun thePlusSaysSoInTheLog() {
        val plan = FilmMemory.withSecondRequest(stick(397, rateMbps = 58.0, lineMbps = 83.0), longSpans)
        assertEquals(
            "Film buffer: SMALL memory, 397 MiB free (Fire OS calls it low under 144 MiB, swap 0 of 819 MiB free), " +
                "film about 58 Mb/s on a connection bringing 83: starts at 80 MiB, for 8 s of it ahead (free memory leaves " +
                "no more: 440 MiB are left for the decoder and Fire OS), up to 112 MiB once memory has been quiet, " +
                "6 s kept behind up to 24 MiB, 16 MiB given to the second request's long spans",
            FilmMemory.startWords(plan, 397 * mib, 58.0, 83.0) { "0 of 819 MiB" },
        )
    }

    // ---- The Fire TV Stick 4K Max: 540 MiB free, a 52 GB copy at about 35 Mb/s

    @Test fun theMaxStartsWithWhatIsFreeOverTheReserve() {
        val planned = stick(540, rateMbps = 35.0)
        // 540 less 440: 100 MiB. More than its floor (64 MiB; 8 s ahead of 35 Mb/s and 24 MiB behind are 58).
        assertEquals(100, mib(planned.budgetBytes))
        assertEquals(FilmBufferPlan.StartBy.FREE, planned.startBy)
        assertEquals(64, mib(planned.floorBytes))
        assertTrue(planned.byFreeMemory)
        val plan = FilmMemory.withSecondRequest(planned, longSpans)
        // 84 MiB and 16 of spans where it was 112 and 16; it grows to 112.
        assertEquals(84, mib(plan.budgetBytes))
        assertEquals(112, mib(plan.tierBytes))
        // Behind it keeps what it always did; 84 MiB less those 24 are over 14 s of a 35 Mb/s film ahead.
        assertEquals(6_000, plan.backBufferMs)
        assertEquals(24 * mib, plan.backCapBytes)
        assertTrue((plan.budgetBytes - plan.backCapBytes) / (35_000_000.0 / 8) > 14.0)
        assertEquals(
            "Film buffer: SMALL memory, 540 MiB free (Fire OS calls it low under 144 MiB), film about 35 Mb/s: starts at " +
                "84 MiB, what's free once 440 MiB are left for the decoder and Fire OS, up to 112 MiB once memory has been " +
                "quiet, 6 s kept behind up to 24 MiB, 16 MiB given to the second request's long spans",
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
        val p = MemoryPressure(fine = RamTier.LARGE.tight)
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
    }

    @Test fun aTwoGigabyteTvIsUnchangedWhateverIsFree() {
        // Nothing was measured on one: its buffer starts, keeps behind and is squeezed as in 1.0.366.
        assertFalse(RamTier.MEDIUM.tight)
        assertTrue(RamTier.SMALL.tight)
        assertTrue(RamTier.TINY.tight)
        for (free in listOf(100L, 300L, 397L, 436L, 500L, 540L, 615L, 616L, 2_000L)) {
            val plain = FilmMemory.plan(RamTier.MEDIUM, stickHeap, free * mib)
            // All but 260 MiB, between 64 and its 176.
            assertEquals("$free", (free - 260).coerceIn(64L, 176L).toInt(), mib(plain.budgetBytes))
            assertEquals("$free", plain, FilmMemory.plan(RamTier.MEDIUM, stickHeap, free * mib, 0, lowMark, 58.0, 83.0))
            assertEquals(plain.budgetBytes, plain.everBytes)
            assertEquals(0L, plain.lowMarkBytes)
        }
        // 500 MiB free: its whole 176 MiB and the 10 s behind that one press back plays from.
        val plan = FilmMemory.plan(RamTier.MEDIUM, stickHeap, 500 * mib, 0, lowMark, 58.0, 83.0)
        assertEquals(176, mib(plan.budgetBytes))
        assertEquals(10_000, plan.backBufferMs)
        assertEquals(48 * mib, plan.backCapBytes)
        assertFalse(plan.byFreeMemory)
        val p = MemoryPressure(fine = RamTier.MEDIUM.tight)
        assertTrue(p.trimAsked(1, 1_000))
        assertEquals(2, p.step)
        assertFalse(p.mayGrow(10 * 60_000L))
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
        // As a Stick with enough free keeps behind (6 s, up to 24 MiB): 58 Mb/s on 83, 80 MiB.
        assertEquals(80 * mib, FilmMemory.floorBytes(58.0, 83.0, 6_000, 24 * mib).toLong())
        // As one with very little free does (4 s, up to 16 MiB).
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
        // 400 MiB free: nothing over the reserve.
        assertEquals(64, mib(stick(400).budgetBytes))
        assertEquals(FilmBufferPlan.StartBy.FLOOR, stick(400).startBy)
        assertEquals(64, mib(stick(400, rateMbps = 20.0, lineMbps = 90.0).budgetBytes))
        assertEquals(80, mib(stick(400, rateMbps = 58.0, lineMbps = 83.0).budgetBytes))
        assertEquals(101, mib(stick(400, rateMbps = 80.0, lineMbps = 90.0).budgetBytes))
        // The same film on a line with room for it: 6 s ahead.
        assertEquals(66, mib(stick(400, rateMbps = 58.0, lineMbps = 120.0).budgetBytes))
        // Never past the TV's own size: a film too heavy for it gets all of that.
        val huge = stick(400, rateMbps = 150.0, lineMbps = 160.0)
        assertEquals(128, mib(huge.budgetBytes))
        assertFalse(huge.byFreeMemory)
        assertEquals(6_000, huge.backBufferMs)
        // And where free memory gives more than the floor, the floor changes nothing.
        assertEquals(100, mib(stick(540, rateMbps = 58.0, lineMbps = 83.0).budgetBytes))
    }

    @Test fun theStartIsNeverBiggerThanItWasIn366() {
        // Under 388 MiB free 1.0.366 already held a Stick's buffer down. A heavy film's seconds ahead never make it
        // start with more than it had then: 300 MiB free, 64 MiB whatever the film.
        assertEquals(64, mib(stick(300).budgetBytes))
        assertEquals(64, mib(stick(300, rateMbps = 58.0, lineMbps = 83.0).budgetBytes))
        val heavy = stick(300, rateMbps = 80.0, lineMbps = 90.0)
        assertEquals(64, mib(heavy.budgetBytes))
        assertEquals(64, mib(heavy.everBytes))
        assertEquals(FilmBufferPlan.StartBy.FLOOR, heavy.startBy)
        // 340 MiB free (80 MiB then): the 58 Mb/s film's 8 s fit under it, the 80 Mb/s film's don't and it gets the 80.
        assertEquals(72, mib(stick(340, rateMbps = 58.0, lineMbps = 83.0).budgetBytes))
        assertEquals(FilmBufferPlan.StartBy.AHEAD, stick(340, rateMbps = 58.0, lineMbps = 83.0).startBy)
        val capped = stick(340, rateMbps = 80.0, lineMbps = 90.0)
        assertEquals(80, mib(capped.budgetBytes))
        assertEquals(FilmBufferPlan.StartBy.EVER, capped.startBy)
        assertEquals(8_000_000L, capped.aheadFloorUs)
        assertTrue(FilmMemory.startWords(capped, 340 * mib, 80.0, 90.0) { null }.contains(
            "starts at 80 MiB, as much as it ever started with when so little was free (8 s of it ahead would take more), up to 128 MiB",
        ))
        // Whatever is free and whatever the film: never more than then, with or without the second request's spans,
        // nor while it loads the seconds it's short of (the most its limit ever is).
        for (free in 60L..700L step 4) {
            for ((rate, line) in listOf(0.0 to 0.0, 15.0 to 83.0, 35.0 to 0.0, 58.0 to 83.0, 65.0 to 83.0, 80.0 to 90.0, 110.0 to 90.0)) {
                val planned = stick(free, rate, line)
                assertTrue("$free $rate", planned.budgetBytes <= asEver(free) * mib)
                assertEquals("$free $rate", asEver(free).toInt(), mib(planned.everBytes))
                assertTrue("$free $rate", planned.budgetBytes >= FilmMemory.FREE_FLOOR_BYTES)
                if (!FilmMemory.longSpansFit(planned)) continue
                val plan = FilmMemory.withSecondRequest(planned, longSpans)
                assertEquals("$free $rate", 112, mib(plan.everBytes))
                assertTrue("$free $rate", plan.budgetBytes <= plan.everBytes)
                for (aheadUs in listOf(0L, 4_999_999L, 5_000_000L, 7_999_999L, 8_000_000L, 30_000_000L)) {
                    val limit = FilmMemory.fillLimit(plan.budgetBytes, plan.budgetBytes, aheadUs, plan.everBytes, FilmMemory.THIN_AHEAD_US)
                    assertTrue("$free $rate $aheadUs", limit <= 112 * mib)
                }
            }
        }
    }

    @Test fun whatIsKeptBehindIsWhatItWasIn366() {
        // By the size the buffer had then, not the one it starts with now: 6 s up to 24 MiB from 388 MiB free, where
        // it had its whole 128; 4 s up to 16 MiB under that. So it doesn't change when the buffer has grown back.
        for (free in 60L..700L step 4) {
            for ((rate, line) in listOf(0.0 to 0.0, 20.0 to 90.0, 58.0 to 83.0, 80.0 to 90.0)) {
                val plan = stick(free, rate, line)
                assertEquals("$free $rate", if (free >= 388) 6_000 else 4_000, plan.backBufferMs)
                assertEquals("$free $rate", (if (free >= 388) 24 else 16) * mib, plan.backCapBytes)
                assertEquals(plan.backBufferMs, FilmMemory.withSecondRequest(plan, longSpans).backBufferMs)
            }
        }
        // A 20 Mb/s film started at the 64 MiB floor with 450 MiB free keeps all 6 s behind: 5 s back plays from memory.
        val light = stick(450, rateMbps = 20.0, lineMbps = 90.0)
        assertEquals(64, mib(light.budgetBytes))
        assertEquals(6_000_000L, BackBuffer.keepUs(light.backCapBytes, 40 * mib, 16_000_000L, light.backBufferMs * 1000L))
    }

    // ---- The wait before the first frame

    // The film ahead at which FilmLoadControl.shouldStartPlayback starts a film not stalled, from the same pieces it
    // uses (fillLimit, roomUs, waitUs, StallCushion.forRate): a film of [rateMbps] arriving at [fill] times its rate,
    // nothing kept behind yet, the buffer [budget] of [most] with [floorUs] to keep ahead.
    private fun firstFrameAtUs(budget: Int, most: Int, floorUs: Long, rateMbps: Double, fill: Float): Long {
        val bytesPerUs = rateMbps / 8.0
        val wanted = maxOf(1_000_000L, StallCushion.forRate(fill))
        var ahead = 50_000L
        while (ahead < 60_000_000L) {
            val allocated = (ahead * bytesPerUs).toLong()
            val limit = FilmMemory.fillLimit(budget, budget, ahead, most, floorUs)
            if (allocated >= limit) return ahead
            if (ahead >= FilmMemory.waitUs(wanted, FilmMemory.roomUs(0L, ahead, allocated, limit.toLong()), 1_000_000L)) return ahead
            ahead += 50_000L
        }
        return ahead
    }

    @Test fun theFirstFrameNeverWaitsLongerThanIn366() {
        for (two in listOf(false, true)) {
            for (free in listOf(136L, 200L, 300L, 320L, 340L, 387L, 388L, 397L, 450L, 540L, 567L, 568L, 700L)) {
                for ((rate, line) in listOf(0.0 to 0.0, 30.0 to 83.0, 58.0 to 70.0, 58.0 to 83.0, 65.0 to 83.0, 80.0 to 90.0)) {
                    for (fill in listOf(1.0f, 1.2f, 1.28f, 1.5f, 3f)) {
                        val planned = stick(free, rate, line)
                        val spans = two && FilmMemory.longSpansFit(planned)
                        val plan = if (spans) FilmMemory.withSecondRequest(planned, longSpans) else planned
                        val was = ((asEver(free) - if (spans) 16 else 0) * mib).toInt()
                        val floorUs = if (plan.aheadFloorUs > 0L) plan.aheadFloorUs else FilmMemory.THIN_AHEAD_US
                        // The film's real rate, where the plan didn't know it: a heavy one.
                        val real = if (rate > 0.0) rate else 65.0
                        val now = firstFrameAtUs(plan.budgetBytes, plan.everBytes, floorUs, real, fill)
                        val then = firstFrameAtUs(was, was, FilmMemory.MIN_AHEAD_US, real, fill)
                        assertTrue("$two $free $rate $fill: $now > $then", now <= then)
                        // Under 388 MiB free, where 1.0.366 held the buffer down too, a film this heavy waits exactly as long.
                        if (free < 388 && real >= 65.0) assertEquals("$two $free $rate $fill", then, now)
                    }
                }
            }
        }
        // The Godfather on the Plus with 300 MiB free: 65 Mb/s arriving at 1.28 times its rate. Half of the 8.3 s that
        // fit in its 64 MiB, as in 1.0.366; not the 8 s the TV's whole 128 MiB would make it.
        val tight = stick(300, rateMbps = 65.0, lineMbps = 83.0)
        val at = firstFrameAtUs(tight.budgetBytes, tight.everBytes, tight.aheadFloorUs, 65.0, 1.28f)
        assertTrue("$at", at in 4_100_000L..4_200_000L)
        assertTrue(firstFrameAtUs(tight.budgetBytes, 128 * mib.toInt(), tight.aheadFloorUs, 65.0, 1.28f) >= 8_000_000L)
        // Arriving at 1.3 times its rate and more: after a second, as ever.
        assertEquals(1_000_000L, firstFrameAtUs(tight.budgetBytes, tight.everBytes, tight.aheadFloorUs, 65.0, 1.5f))
    }

    @Test fun theSecondsAreKeptAsTheFilmLoadsWhateverItsRateWasSaidToBe() {
        val budget = (72 * mib).toInt()
        val tier = (112 * mib).toInt()
        val floor = FilmMemory.THIN_AHEAD_US
        // Not squeezed: the budget only limits what's held beyond 8 s ahead; under that, all it had in 1.0.366.
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
        // And one that has grown past what it had then, or started at it: its own limit whatever the seconds.
        assertEquals(whole, FilmMemory.fillLimit(whole, whole, 7_000_000L, tier, floor))
        assertEquals(half, FilmMemory.fillLimit(half, whole, 7_000_000L, tier, floor))
        assertEquals(whole, FilmMemory.fillLimit(half, whole, 4_000_000L, tier, floor))
    }

    // ---- Growing back once memory has been quiet

    @Test fun aBufferStartedSmallGrowsAStepAtATimeAfterTwoQuietMinutes() {
        val tier = (112 * mib).toInt()
        var budget = (80 * mib).toInt()
        val p = MemoryPressure(fine = true)
        p.filmStarted(0L)
        // The film's first two minutes: nothing, however much is free.
        var t = 10_000L
        while (t < 120_000L) {
            assertFalse("$t", p.mayGrow(t))
            t += 10_000L
        }
        // Then a step with every 30 s more of quiet: 96 MiB at 2:00, its 112 at 2:30.
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
        assertEquals(listOf(96 * 1000 + 120, 112 * 1000 + 150), sizes)
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
        // The Plus's 80 MiB with 16 of spans: 96 in all, 72 at a quarter back, so the buffer fills to 56.
        assertEquals(56 * mib, FilmMemory.capFor((80 * mib).toInt(), p.step, longSpans.toInt()).toLong())
        // A low reading after it stays at the quarter a low reading ever meant, until it has lasted 30 s: then an
        // eighth with each check, to half and no further.
        assertFalse(p.checked(true, 15_000L))
        assertFalse(p.checked(true, 25_000L))
        assertEquals(2, p.step)
        assertTrue(p.checked(true, 35_000L))
        assertEquals(3, p.step)
        assertTrue(p.checked(true, 45_000L))
        assertEquals(4, p.step)
        assertFalse(p.checked(true, 55_000L))
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

    @Test fun aLowReadingThatLastsReachesHalfByEighths() {
        val p = MemoryPressure(fine = true)
        val steps = listOf(0L, 10_000L, 20_000L, 30_000L, 40_000L, 50_000L).map { t -> p.checked(true, t); p.step }
        // A quarter while it's younger than 30 s, as ever; from 30 s on towards half, an eighth with each check.
        assertEquals(listOf(1, 2, 2, 3, 4, 4), steps)
        // As before on a TV with memory to spare: a quarter, then half at 30 s.
        val old = MemoryPressure()
        assertEquals(listOf(2, 2, 2, 4, 4, 4), listOf(0L, 10_000L, 20_000L, 30_000L, 40_000L, 50_000L).map { t -> old.checked(true, t); old.step })
        // Never further than that one at any check.
        steps.zip(listOf(2, 2, 2, 4, 4, 4)).forEach { (fine, coarse) -> assertTrue(fine <= coarse) }
        // A low reading that passes costs an eighth, where it cost a quarter.
        val blip = MemoryPressure(fine = true)
        blip.checked(true, 0L)
        blip.checked(false, 10_000L)
        assertEquals(1, blip.step)
        assertEquals(98 * mib, FilmMemory.capFor((112 * mib).toInt(), blip.step).toLong())
    }

    @Test fun signalsThatComeAndGoStopAtAQuarterAsTheyAlwaysDid() {
        // Low readings that come and go (free memory sitting at Fire OS's mark): low at 0:00, 0:20, 0:45, 1:10, not
        // low at the checks between. 1.0.366 stayed at a quarter; an eighth at a time gets there and stops.
        val fine = MemoryPressure(fine = true)
        val old = MemoryPressure()
        val lows = setOf(0L, 20_000L, 40_000L, 70_000L, 100_000L, 130_000L)
        var t = 0L
        while (t <= 140_000L) {
            fine.checked(t in lows, t)
            old.checked(t in lows, t)
            assertTrue("$t", fine.step <= old.step)
            assertTrue("$t", fine.step <= 2)
            t += 10_000L
        }
        assertEquals(2, fine.step)
        assertEquals(2, old.step)
        // A full Stick buffer (112 MiB and 16 of spans) stays at 80 MiB, with 8 s of a 58 Mb/s film ahead, not at 48.
        assertEquals(80 * mib, FilmMemory.capFor((112 * mib).toInt(), fine.step, longSpans.toInt()).toLong())

        // Fire OS asking again and again that memory is running low, 10 s apart: a quarter, as ever.
        val asked = MemoryPressure(fine = true)
        assertEquals(listOf(1, 2, 2, 2, 2), listOf(0L, 10_000L, 20_000L, 30_000L, 40_000L).map { asked.trimAsked(1, it); asked.step })
        // With low readings between them, none of them lasting: still a quarter.
        val mixed = MemoryPressure(fine = true)
        mixed.trimAsked(1, 0L)
        mixed.checked(true, 9_000L)
        mixed.checked(false, 19_000L)
        mixed.trimAsked(1, 21_000L)
        mixed.checked(true, 29_000L)
        mixed.checked(false, 39_000L)
        mixed.trimAsked(1, 45_000L)
        assertEquals(2, mixed.step)
        // Only "critical", or a low reading that has lasted 30 s, goes past it.
        assertTrue(mixed.trimAsked(2, 60_000L))
        assertEquals(4, mixed.step)
    }

    @Test fun itStepsBackAnEighthAtATimeAfterTwoQuietMinutes() {
        val p = MemoryPressure(fine = true)
        p.trimAsked(2, 0L)
        p.trimAsked(2, 10_000L)
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
        // The Plus's 80 MiB start: 68, 56, then the 48 no buffer goes under.
        assertEquals(listOf(80, 68, 56, 48, 48), (0..4).map { mib(FilmMemory.capFor((80 * mib).toInt(), it, longSpans.toInt())) })
    }

    // ---- A server slower than the film, in a buffer started small

    @Test fun aSmallStartStillMovesToAnotherServerWhilePlaying() {
        // The Max with 450 MiB free and a 30 Mb/s film: 64 MiB, 6 s kept behind up to 24 MiB.
        val plan = FilmMemory.withSecondRequest(stick(450, rateMbps = 30.0, lineMbps = 83.0), longSpans)
        assertEquals(64, mib(plan.budgetBytes))
        val fullUs = ((plan.budgetBytes - 6 * 30_000_000.0 / 8) / (30_000_000.0 / 8) * 1_000_000).toLong()
        assertTrue("$fullUs", fullUs in 11_500_000L..12_500_000L)
        // The 15 s a move asks for never fit (1.0.366 held 25 s of it): it moves with all but 2 s of what does, and
        // never with under the 5 s a squeezed buffer keeps.
        assertTrue(fullUs < FilmMemory.MOVE_COVER_US)
        assertEquals(fullUs - FilmMemory.MOVE_MARGIN_US, FilmMemory.moveCoverUs(fullUs, heldDown = true))
        assertTrue(FilmMemory.moveCoverUs(fullUs, heldDown = true) < fullUs)
        assertEquals(11_900_000L, FilmMemory.moveCoverUs(13_900_000L, heldDown = true))
        // The Max's 35 Mb/s copy at 84 MiB, a little over 14 s of it: 12 s and a bit.
        assertEquals(12_400_000L, FilmMemory.moveCoverUs(14_400_000L, heldDown = true))
        // Eight seconds ahead: 6 s, not the 10 s it never reaches. Under 7 s: 5 s.
        assertEquals(6_000_000L, FilmMemory.moveCoverUs(8_000_000L, heldDown = true))
        assertEquals(FilmMemory.MIN_AHEAD_US, FilmMemory.moveCoverUs(6_200_000L, heldDown = true))
        // Room for the 15 s, what fits not measured yet, or a buffer at the size it ever had: 15 s, as ever.
        assertEquals(15_000_000L, FilmMemory.moveCoverUs(25_000_000L, heldDown = true))
        assertEquals(15_000_000L, FilmMemory.moveCoverUs(17_000_000L, heldDown = true))
        assertEquals(15_000_000L, FilmMemory.moveCoverUs(0L, heldDown = true))
        assertEquals(15_000_000L, FilmMemory.moveCoverUs(13_900_000L, heldDown = false))
        assertEquals(15_000_000L, FilmMemory.moveCoverUs(5_000_000L, heldDown = false))
    }

    // How much of a film of [rateMbps] is ahead when the buffer of [plan] is full as it plays (FilmLoadControl stops
    // loading at FilmMemory.fillLimit, keeping [floorUs] ahead), with what's kept behind (6 s up to the plan's cap).
    private fun fullAheadUs(plan: FilmBufferPlan, floorUs: Long, rateMbps: Double): Long {
        val bytesPerUs = rateMbps / 8.0
        val behind = minOf(plan.backBufferMs * 1000.0 * bytesPerUs, plan.backCapBytes.toDouble())
        var ahead = FilmMemory.ROOM_MIN_US
        while (ahead < 120_000_000L) {
            val allocated = (ahead * bytesPerUs + behind).toLong()
            if (allocated >= FilmMemory.fillLimit(plan.budgetBytes, plan.budgetBytes, ahead, plan.everBytes, floorUs)) return ahead
            ahead += 50_000L
        }
        return ahead
    }

    @Test fun aHeavyFilmInASmallStartMovesBeforeItRunsDry() {
        // Found in review: the Max with 450 MiB free starts a 38 Mb/s film from TiviMate (its rate not known): 64 MiB,
        // of which 8 s and a little more are ahead when full, where 1.0.366's 112 held 19 s. The 10 s a move asked for
        // at the least were never there: a server fallen under the film's rate ran it dry, and it moved in the stall.
        val plan = FilmMemory.withSecondRequest(stick(450), longSpans)
        assertEquals(64, mib(plan.budgetBytes))
        assertEquals(112, mib(plan.everBytes))
        val full = fullAheadUs(plan, FilmMemory.THIN_AHEAD_US, 38.0)
        assertTrue("$full", full in 8_500_000L..9_200_000L)
        val cover = FilmMemory.moveCoverUs(full, heldDown = true)
        assertTrue("$cover $full", cover < full)
        assertEquals(full - FilmMemory.MOVE_MARGIN_US, cover)
        // 1.0.366 at its whole size: 19 s, and the 15 s it always asked for.
        val then = FilmMemory.withSecondRequest(stick(700), longSpans)
        assertTrue(fullAheadUs(then, FilmMemory.MIN_AHEAD_US, 38.0) in 19_000_000L..20_000_000L)
        assertEquals(FilmMemory.MOVE_COVER_US, FilmMemory.moveCoverUs(19_000_000L, heldDown = false))
        // Every start held down by free memory, whatever the film and with or without the long spans: what a move
        // asks for is less than what the full buffer holds, so a slow server is left while the film still plays.
        for (two in listOf(false, true)) {
            for (free in 300L..567L step 8) {
                for ((rate, line) in listOf(0.0 to 0.0, 30.0 to 83.0, 38.0 to 50.0, 45.0 to 83.0, 58.0 to 70.0, 58.0 to 90.0, 65.0 to 83.0, 80.0 to 90.0)) {
                    val planned = stick(free, rate, line)
                    val p = if (two && FilmMemory.longSpansFit(planned)) FilmMemory.withSecondRequest(planned, longSpans) else planned
                    if (p.budgetBytes >= p.everBytes) continue
                    val floorUs = if (p.aheadFloorUs > 0L) p.aheadFloorUs else FilmMemory.THIN_AHEAD_US
                    val fullUs = fullAheadUs(p, floorUs, if (rate > 0.0) rate else 38.0)
                    val asks = FilmMemory.moveCoverUs(fullUs, heldDown = true)
                    assertTrue("$two $free $rate: $asks of $fullUs", asks < fullUs)
                    assertTrue("$two $free $rate", asks >= FilmMemory.MIN_AHEAD_US)
                }
            }
        }
    }

    // ---- A stall in a buffer started small

    // The film ahead at which FilmLoadControl.shouldStartPlayback plays on in the [stalls]th stall, from the pieces it
    // uses (its limit, roomInStallUs, StallCushion): a film of [rateMbps] arriving at [fill] times its rate, [behindUs]
    // kept behind, the buffer filling to [fillBytes] and stopping at [limitAt] for what's ahead.
    private fun stallWaitUs(fillBytes: Int, limitAt: (Long) -> Int, rateMbps: Double, behindUs: Long, stalls: Int, fill: Float): Long {
        val bytesPerUs = rateMbps / 8.0
        val wanted = StallCushion.wantedUs(stalls, fill)
        var ahead = 0L
        while (ahead < 60_000_000L) {
            val allocated = ((ahead + behindUs) * bytesPerUs).toLong()
            if (allocated >= limitAt(ahead)) return ahead
            val room = FilmMemory.roomInStallUs(ahead, behindUs, allocated, fillBytes.toLong())
            if (ahead >= StallCushion.waitUs(stalls, wanted, room, fill)) return ahead
            ahead += 50_000L
        }
        return ahead
    }

    @Test fun aStallInASmallStartWaitsForWhatItDidIn366() {
        // Found in review: the Plus with 397 MiB free, its 58 Mb/s film at 80 MiB. After its second stall, on a line
        // bringing 0.95 of the film's rate, it played on with 6 s ahead where 1.0.366 waited for 10: on a line that
        // can't keep up, stalls came about twice as often until the buffer grew back.
        val plus = stick(397, rateMbps = 58.0, lineMbps = 83.0)
        val behindUs = (plus.backCapBytes / (58.0 / 8.0)).toLong()
        val wasFill = plus.everBytes
        val then = stallWaitUs(wasFill, { FilmMemory.fillLimit(wasFill, wasFill, it) }, 58.0, behindUs, 2, 0.95f)
        val before = stallWaitUs(plus.budgetBytes, { FilmMemory.fillLimit(plus.budgetBytes, plus.budgetBytes, it, plus.everBytes, plus.aheadFloorUs) }, 58.0, behindUs, 2, 0.95f)
        assertTrue("$then", then in 9_900_000L..10_100_000L)
        assertTrue("$before", before in 5_900_000L..6_200_000L)
        val stallFill = FilmMemory.stallFillBytes(plus.budgetBytes, plus.budgetBytes, plus.everBytes, 0)
        assertEquals(plus.everBytes, stallFill)
        assertEquals(then, stallWaitUs(stallFill, { FilmMemory.stallLimit(stallFill, plus.budgetBytes, plus.everBytes, it) }, 58.0, behindUs, 2, 0.95f))

        // Whatever is free, the film, the line, the squeeze and the stall: the wait is 1.0.366's, its limit never under
        // what the buffer's own is at that moment, and never over what it ever was.
        for (two in listOf(false, true)) {
            for (free in listOf(300L, 388L, 397L, 450L, 540L, 567L, 700L)) {
                for ((rate, line) in listOf(0.0 to 0.0, 38.0 to 50.0, 58.0 to 70.0, 58.0 to 83.0, 65.0 to 83.0, 80.0 to 90.0)) {
                    val planned = stick(free, rate, line)
                    val spans = two && FilmMemory.longSpansFit(planned)
                    val plan = if (spans) FilmMemory.withSecondRequest(planned, longSpans) else planned
                    val extra = plan.spanExtraBytes
                    val was = ((asEver(free) - if (spans) 16 else 0) * mib).toInt()
                    assertEquals(was, plan.everBytes)
                    val real = if (rate > 0.0) rate else 58.0
                    val behind = minOf(plan.backBufferMs * 1000L, (plan.backCapBytes / (real / 8.0)).toLong())
                    val floorUs = if (plan.aheadFloorUs > 0L) plan.aheadFloorUs else FilmMemory.THIN_AHEAD_US
                    for (step in 0..FilmMemory.STEPS) {
                        val cap = FilmMemory.capFor(plan.budgetBytes, step, extra)
                        val fillNow = FilmMemory.stallFillBytes(cap, plan.budgetBytes, plan.everBytes, step, extra)
                        val wasCap = FilmMemory.capFor(was, step, extra)
                        assertEquals("$two $free $rate $step", wasCap, fillNow)
                        for (aheadUs in listOf(0L, 4_999_999L, 5_000_000L, 7_999_999L, 8_000_000L, 30_000_000L)) {
                            val stall = FilmMemory.stallLimit(fillNow, plan.budgetBytes, plan.everBytes, aheadUs)
                            assertTrue(stall >= FilmMemory.fillLimit(cap, plan.budgetBytes, aheadUs, plan.everBytes, floorUs))
                            assertTrue(stall <= plan.everBytes)
                        }
                        for (stalls in 1..3) {
                            for (fill in listOf(0.7f, 0.95f, 1.1f)) {
                                val now = stallWaitUs(fillNow, { FilmMemory.stallLimit(fillNow, plan.budgetBytes, plan.everBytes, it) }, real, behind, stalls, fill)
                                val was366 = stallWaitUs(wasCap, { FilmMemory.fillLimit(wasCap, was, it) }, real, behind, stalls, fill)
                                assertEquals("$two $free $rate $step $stalls $fill", was366, now)
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- Another copy in the same player (Sources, Next source)

    @Test fun anotherCopyGetsItsOwnSecondsAndSize() {
        // The Plus with 450 MiB free starts a 15 Mb/s copy on a line bringing 83: 6 s of it ahead, the 64 MiB floor.
        val first = FilmMemory.withSecondRequest(stick(450, rateMbps = 15.0, lineMbps = 83.0), longSpans)
        assertEquals(64, mib(first.budgetBytes))
        assertEquals(6_000_000L, first.aheadFloorUs)
        // The viewer picks the 58 Mb/s remux: 8 s of it, and the size a start with it would have had.
        val fresh = FilmMemory.withSecondRequest(stick(450, rateMbps = 58.0, lineMbps = 83.0), longSpans)
        assertEquals(80, mib(fresh.budgetBytes))
        assertEquals(8_000_000L, FilmMemory.aheadFloorFor(58.0, 83.0))
        assertEquals(
            fresh.budgetBytes,
            FilmMemory.budgetForCopy(first.budgetBytes, first.everBytes, 58.0, 83.0, first.backBufferMs, first.backCapBytes),
        )
        // Never over what the buffer ever had, never smaller than it is, and a lighter copy changes nothing.
        assertEquals(first.everBytes, FilmMemory.budgetForCopy(first.budgetBytes, first.everBytes, 150.0, 160.0, first.backBufferMs, first.backCapBytes))
        assertEquals(first.budgetBytes, FilmMemory.budgetForCopy(first.budgetBytes, first.everBytes, 20.0, 83.0, first.backBufferMs, first.backCapBytes))
        assertEquals(fresh.budgetBytes, FilmMemory.budgetForCopy(fresh.budgetBytes, fresh.everBytes, 15.0, 83.0, fresh.backBufferMs, fresh.backCapBytes))
        // A copy whose rate isn't known: the size stays, and the longer of the two is kept ahead as it loads.
        assertEquals(first.budgetBytes, FilmMemory.budgetForCopy(first.budgetBytes, first.everBytes, 0.0, 83.0, first.backBufferMs, first.backCapBytes))
        assertEquals(8_000_000L, FilmMemory.aheadFloorFor(0.0, 83.0))
        assertEquals(8_000_000L, FilmMemory.aheadFloorFor(58.0, 0.0))
        assertEquals(6_000_000L, FilmMemory.aheadFloorFor(15.0, 83.0))
        // A buffer at the size it ever had (plenty free, or another TV): nothing to change.
        val whole = (112 * mib).toInt()
        assertEquals(whole, FilmMemory.budgetForCopy(whole, whole, 80.0, 90.0, 6_000, 24 * mib))
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
        // The same film's buffer whatever the swap: 80 MiB.
        assertEquals(80, mib(stick(397, rateMbps = 58.0, lineMbps = 83.0).budgetBytes))
    }
}
