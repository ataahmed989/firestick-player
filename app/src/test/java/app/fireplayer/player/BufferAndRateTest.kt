package app.fireplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The film buffer by free memory, what's kept behind held to a size, and the download speed for the log. */
class BufferAndRateTest {

    private val mib = 1L shl 20
    private val stickHeap = 384 * mib

    // ---- The buffer by the memory free at the start (FilmMemory.plan)

    @Test fun plentyFreeLeavesTheTiersBudget() {
        val plan = FilmMemory.plan(RamTier.SMALL, stickHeap, freeBytes = 600 * mib)
        assertEquals((128 * mib).toInt(), plan.budgetBytes)
        assertFalse(plan.byFreeMemory)
        assertEquals(6_000, plan.backBufferMs)
        assertEquals(24 * mib, plan.backCapBytes)
    }

    @Test fun littleFreeHoldsTheBufferDownButNotUnderItsFloor() {
        // 340 MiB free: 260 are left for the decoder's frames and the rest of the TV.
        val tight = FilmMemory.plan(RamTier.SMALL, stickHeap, freeBytes = 340 * mib)
        assertEquals((80 * mib).toInt(), tight.budgetBytes)
        assertTrue(tight.byFreeMemory)
        // The Stick as measured mid-film (64 to 152 MB free): the floor, never nothing.
        val starved = FilmMemory.plan(RamTier.SMALL, stickHeap, freeBytes = 100 * mib)
        assertEquals(FilmMemory.FREE_FLOOR_BYTES, starved.budgetBytes)
        assertTrue(starved.byFreeMemory)
        // Held down to the tiny tier's size, it keeps behind what that tier keeps.
        assertEquals(FilmMemory.LEAST_BACK_BUFFER_MS, starved.backBufferMs)
        assertEquals(16 * mib, starved.backCapBytes)
    }

    @Test fun freeMemoryNotKnownIsTheTiersBudgetAsBefore() {
        assertEquals(FilmMemory.plan(RamTier.SMALL, stickHeap), FilmMemory.plan(RamTier.SMALL, stickHeap, freeBytes = 0L))
        assertEquals((128 * mib).toInt(), FilmMemory.plan(RamTier.SMALL, stickHeap, freeBytes = -1L).budgetBytes)
    }

    @Test fun freeMemoryNeverRaisesABudgetAndTheBigTierHasNoBackCap() {
        val big = FilmMemory.plan(RamTier.LARGE, 1024 * mib, freeBytes = 4096 * mib)
        assertEquals((256 * mib).toInt(), big.budgetBytes)
        assertEquals(0L, big.backCapBytes)
        // The tiny tier's own budget is under the free-memory floor: it isn't raised to it.
        assertEquals((64 * mib).toInt(), FilmMemory.plan(RamTier.TINY, stickHeap, freeBytes = 50 * mib).budgetBytes)
        assertEquals(FilmMemory.FLOOR_BYTES, FilmMemory.plan(RamTier.TINY, 32 * mib, freeBytes = 50 * mib).budgetBytes)
    }

    // ---- The buffer once room has been made for it (FilmMemory.afterRoom)

    private val tier128 = (128 * mib).toInt()

    @Test fun aPlanSaysWhatItsTierWouldGiveWithRoom() {
        assertEquals(tier128, FilmMemory.plan(RamTier.SMALL, stickHeap, freeBytes = 340 * mib).tierBytes)
        assertEquals(tier128, FilmMemory.plan(RamTier.SMALL, stickHeap, freeBytes = 600 * mib).tierBytes)
        assertEquals(tier128, FilmMemory.plan(RamTier.SMALL, stickHeap).tierBytes)
        // The heap limit holds the tier's own size down too: that is as far as room can raise it.
        assertEquals((60 * mib).toInt(), FilmMemory.plan(RamTier.LARGE, 100 * mib, freeBytes = 50 * mib).tierBytes)
    }

    @Test fun aBufferHeldDownAtTheStartGetsItsTiersSizeOnceThePageLetGo() {
        // Built with 340 MiB free (80 MiB); the page then lets go of 90 MiB while the film has loaded 10 MiB.
        val started = FilmMemory.plan(RamTier.SMALL, stickHeap, freeBytes = 340 * mib)
        val after = FilmMemory.afterRoom(started.budgetBytes, started.tierBytes, freeBytes = 420 * mib, heldBytes = 10 * mib)
        assertTrue(started.byFreeMemory)
        assertEquals(tier128, after)
    }

    @Test fun theNextEpisodeIsNotHeldDownByTheLastOnesBuffer() {
        // Built while the last episode's 128 MiB weren't collected yet: 200 MiB free, the floor.
        val started = FilmMemory.plan(RamTier.SMALL, stickHeap, freeBytes = 200 * mib)
        assertEquals(FilmMemory.FREE_FLOOR_BYTES, started.budgetBytes)
        // Collected: 328 MiB free, of which the new buffer may take all but the reserve; more when more is free.
        assertEquals((68 * mib).toInt(), FilmMemory.afterRoom(started.budgetBytes, started.tierBytes, 328 * mib, 0L))
        assertEquals(tier128, FilmMemory.afterRoom(started.budgetBytes, started.tierBytes, 400 * mib, 0L))
    }

    @Test fun whatTheBufferHoldsItselfCountsAsFreeForIt() {
        // 300 MiB free with 60 MiB of the film loaded is as 360 MiB free with none: 100 MiB.
        assertEquals((100 * mib).toInt(), FilmMemory.afterRoom((80 * mib).toInt(), tier128, 300 * mib, 60 * mib))
        assertEquals((100 * mib).toInt(), FilmMemory.afterRoom((80 * mib).toInt(), tier128, 360 * mib, 0L))
    }

    @Test fun roomNeverLowersABufferNorTakesItPastItsTier() {
        val held = (80 * mib).toInt()
        // The decoder took its frames meanwhile: less is free than at the start. Nothing changes.
        assertEquals(held, FilmMemory.afterRoom(held, tier128, freeBytes = 150 * mib, heldBytes = 20 * mib))
        assertEquals(held, FilmMemory.afterRoom(held, tier128, freeBytes = 1 * mib, heldBytes = 0L))
        // Not known: nothing changes.
        assertEquals(held, FilmMemory.afterRoom(held, tier128, freeBytes = 0L, heldBytes = 50 * mib))
        assertEquals(held, FilmMemory.afterRoom(held, tier128, freeBytes = -1L, heldBytes = 0L))
        // Plenty free: the tier's size, no more.
        assertEquals(tier128, FilmMemory.afterRoom(held, tier128, freeBytes = 4096 * mib, heldBytes = 0L))
        // Already at its tier's size: left alone.
        assertEquals(tier128, FilmMemory.afterRoom(tier128, tier128, freeBytes = 4096 * mib, heldBytes = 0L))
        assertEquals(tier128, FilmMemory.afterRoom(tier128, tier128, freeBytes = 100 * mib, heldBytes = 0L))
    }

    @Test fun aRaisedBufferIsSqueezedFromItsNewSize() {
        // Memory short as the room is made: three quarters of the new budget, as of any budget.
        val raised = FilmMemory.afterRoom((64 * mib).toInt(), tier128, 420 * mib, 0L)
        assertEquals(tier128, raised)
        assertEquals((96 * mib).toInt(), FilmMemory.capFor(raised, 1))
        assertEquals((64 * mib).toInt(), FilmMemory.capFor(raised, 2))
    }

    // ---- What came back since the player was built (FilmMemory.roomGained, and afterRoom with it)

    @Test fun whatCameBackIsTheRiseInWhatIsFreeForTheFilm() {
        // 330 MiB free with 10 MiB of film loaded before; 400 MiB free with 30 MiB loaded after: 90 MiB came back.
        assertEquals(90 * mib, FilmMemory.roomGained(beforeBytes = 340 * mib, afterBytes = 430 * mib))
        // Less free than before (the decoder took its frames meanwhile): nothing was seen to come back.
        assertEquals(0L, FilmMemory.roomGained(beforeBytes = 340 * mib, afterBytes = 250 * mib))
        // Not known on either side: nothing.
        assertEquals(0L, FilmMemory.roomGained(beforeBytes = 0L, afterBytes = 430 * mib))
        assertEquals(0L, FilmMemory.roomGained(beforeBytes = 340 * mib, afterBytes = 0L))
    }

    @Test fun theDecoderTakingItsFramesFirstDoesNotHoldTheBufferDown() {
        // Built with 340 MiB free (80 MiB). The page lets go of 90 MiB, but by the time that's read the decoder has
        // taken 150 MiB: only 270 MiB are free, with 10 MiB of film loaded. Read as it stands, nothing changes...
        val started = FilmMemory.plan(RamTier.SMALL, stickHeap, freeBytes = 340 * mib)
        assertEquals(started.budgetBytes, FilmMemory.afterRoom(started.budgetBytes, started.tierBytes, 270 * mib, 10 * mib))
        // ...but 90 MiB were seen to come back: free at the start, with them, is 430 MiB, as if the room had been made first.
        assertEquals(
            tier128,
            FilmMemory.afterRoom(started.budgetBytes, started.tierBytes, 270 * mib, 10 * mib, startFreeBytes = 340 * mib, gainedBytes = 90 * mib),
        )
        assertEquals(tier128, FilmMemory.plan(RamTier.SMALL, stickHeap, freeBytes = 430 * mib).budgetBytes)
    }

    @Test fun whatCameBackCountsOnlyAsFarAsItGoes() {
        val held = (80 * mib).toInt()
        // 20 MiB back on 340 at the start: 360 MiB, 100 MiB of buffer.
        assertEquals((100 * mib).toInt(), FilmMemory.afterRoom(held, tier128, 200 * mib, 0L, startFreeBytes = 340 * mib, gainedBytes = 20 * mib))
        // Nothing seen to come back, or the start's reading not known: only what's free now counts.
        assertEquals(held, FilmMemory.afterRoom(held, tier128, 200 * mib, 0L, startFreeBytes = 340 * mib, gainedBytes = 0L))
        assertEquals(held, FilmMemory.afterRoom(held, tier128, 200 * mib, 0L, startFreeBytes = 0L, gainedBytes = 90 * mib))
        // The better of the two readings counts, never their sum.
        assertEquals((110 * mib).toInt(), FilmMemory.afterRoom(held, tier128, 370 * mib, 0L, startFreeBytes = 340 * mib, gainedBytes = 20 * mib))
        // And with nothing known at all, nothing changes.
        assertEquals(held, FilmMemory.afterRoom(held, tier128, 0L, 0L, startFreeBytes = 0L, gainedBytes = 0L))
    }

    // ---- A player built in the place of another keeps its budget (FilmMemory.plan's keepBytes)

    @Test fun aRebuiltPlayerKeepsTheBudgetItsFilmHad() {
        // Rebuilt in the middle of a film: 200 MiB read as free, the old player's 128 MiB not yet taken back.
        val alone = FilmMemory.plan(RamTier.SMALL, stickHeap, freeBytes = 200 * mib)
        assertEquals(FilmMemory.FREE_FLOOR_BYTES, alone.budgetBytes)
        val rebuilt = FilmMemory.plan(RamTier.SMALL, stickHeap, freeBytes = 200 * mib, keepBytes = tier128)
        assertEquals(tier128, rebuilt.budgetBytes)
        assertFalse(rebuilt.byFreeMemory)
        // And what it keeps behind goes with the budget it has.
        assertEquals(6_000, rebuilt.backBufferMs)
        assertEquals(24 * mib, rebuilt.backCapBytes)
    }

    @Test fun aKeptBudgetNeverGoesPastTheTiersNorUnderWhatFreeMemoryGives() {
        // The old player had 96 MiB: kept, still held down against the tier's 128.
        val kept = FilmMemory.plan(RamTier.SMALL, stickHeap, freeBytes = 200 * mib, keepBytes = (96 * mib).toInt())
        assertEquals((96 * mib).toInt(), kept.budgetBytes)
        assertTrue(kept.byFreeMemory)
        assertEquals(tier128, kept.tierBytes)
        // More is free than the old one had: the free memory's size, as for any player.
        assertEquals((100 * mib).toInt(), FilmMemory.plan(RamTier.SMALL, stickHeap, freeBytes = 360 * mib, keepBytes = (64 * mib).toInt()).budgetBytes)
        // A budget from a bigger tier (never happens on one TV) is still held to this one's.
        assertEquals(tier128, FilmMemory.plan(RamTier.SMALL, stickHeap, freeBytes = 200 * mib, keepBytes = (256 * mib).toInt()).budgetBytes)
        // None kept: as before.
        assertEquals(FilmMemory.plan(RamTier.SMALL, stickHeap, 200 * mib), FilmMemory.plan(RamTier.SMALL, stickHeap, 200 * mib, keepBytes = 0))
    }

    // ---- What a squeezed buffer has given back is collected a good piece at a time (FilmMemory.givenBack)

    @Test fun aSqueezedBuffersMemoryIsCollectedEvery16MiBItComesDown() {
        // Full at 128 MiB when memory ran low (it fills to 96 now): nothing yet, then at 112 and again at 96.
        assertFalse(FilmMemory.givenBack(step = 1, heldBytes = 128 * mib, heldAtCollectBytes = 128 * mib))
        assertFalse(FilmMemory.givenBack(step = 1, heldBytes = 120 * mib, heldAtCollectBytes = 128 * mib))
        assertTrue(FilmMemory.givenBack(step = 1, heldBytes = 112 * mib, heldAtCollectBytes = 128 * mib))
        assertFalse(FilmMemory.givenBack(step = 1, heldBytes = 100 * mib, heldAtCollectBytes = 112 * mib))
        assertTrue(FilmMemory.givenBack(step = 1, heldBytes = 96 * mib, heldAtCollectBytes = 112 * mib))
        // A fast film is well past a piece by the next check: one collection for all of it.
        assertTrue(FilmMemory.givenBack(step = 2, heldBytes = 64 * mib, heldAtCollectBytes = 128 * mib))
    }

    @Test fun aBufferHoldingAsMuchOrMoreHasGivenNothingBack() {
        // Paused, waiting, or down to its size and loading again: it holds what it held, or more.
        assertFalse(FilmMemory.givenBack(step = 1, heldBytes = 96 * mib, heldAtCollectBytes = 96 * mib))
        assertFalse(FilmMemory.givenBack(step = 2, heldBytes = 70 * mib, heldAtCollectBytes = 64 * mib))
    }

    @Test fun aBufferThatIsNotSqueezedIsNeverCollectedForIt() {
        // After a jump it holds far less for a moment: its memory is kept to load into again, not given back.
        assertFalse(FilmMemory.givenBack(step = 0, heldBytes = 4 * mib, heldAtCollectBytes = 128 * mib))
        assertFalse(FilmMemory.givenBack(step = 0, heldBytes = 128 * mib, heldAtCollectBytes = 128 * mib))
    }

    // ---- What's kept behind, held to a size (BackBuffer.keepUs)

    private val sixSeconds = 6_000_000L

    @Test fun an84MbpsFilmKeepsAbout2Point3SecondsBehindIn24MiB() {
        // 84 Mb/s: 10.5 MB a second. 12 s of it held is 126 MB.
        val held = 126_000_000L
        val keep = BackBuffer.keepUs(24 * mib, held, 12_000_000L, sixSeconds)
        assertEquals(2_396_745L, keep)
    }

    @Test fun aFilmUnderTheCapsRateKeepsTheWholeLength() {
        // 25 Mb/s: 6 s are 19 MB, under 24 MiB.
        val held = 25_000_000L / 8 * 30
        assertEquals(sixSeconds, BackBuffer.keepUs(24 * mib, held, 30_000_000L, sixSeconds))
    }

    @Test fun neverLessThanTwoSecondsAndNothingBeforeTheSizeIsKnown() {
        // 300 Mb/s (no such film, but the floor holds).
        assertEquals(BackBuffer.LEAST_KEPT_US, BackBuffer.keepUs(24 * mib, 375_000_000L, 10_000_000L, sixSeconds))
        // Too little held to tell the film's size by: Media3's own length.
        assertEquals(sixSeconds, BackBuffer.keepUs(24 * mib, 30_000_000L, 2_000_000L, sixSeconds))
        assertEquals(sixSeconds, BackBuffer.keepUs(24 * mib, 0L, 10_000_000L, sixSeconds))
        // No cap: as Media3 has it.
        assertEquals(sixSeconds, BackBuffer.keepUs(0L, 126_000_000L, 12_000_000L, sixSeconds))
        // A length already at the least isn't cut further.
        assertEquals(2_000_000L, BackBuffer.keepUs(mib, 126_000_000L, 12_000_000L, 2_000_000L))
    }

    // ---- The download's speed (DownloadRate)

    @Test fun everySecondCountsButOnlyFreeOnesSayWhatTheLineCarries() {
        val r = DownloadRate()
        var bytes = 0L
        var t = 0L
        r.sample(t, bytes, free = false)
        // Three seconds loading freely at 300 Mb/s (37.5 MB a second).
        repeat(3) {
            t += 1_000
            bytes += 37_500_000
            r.sample(t, bytes, free = true)
        }
        // Then the buffer is full: seven seconds at the film's own 84 Mb/s.
        repeat(7) {
            t += 1_000
            bytes += 10_500_000
            r.sample(t, bytes, free = false)
        }
        assertEquals(148.8, r.mbps, 0.05)
        assertEquals(300.0, r.freeMbps, 0.05)
        assertEquals(3, r.freeSeconds)
        assertEquals(300.0, r.bestFreeMbps, 0.05)
        // The next line starts afresh; the best stays.
        r.take()
        assertEquals(0.0, r.mbps, 0.0)
        assertEquals(0, r.freeSeconds)
        assertEquals(300.0, r.bestFreeMbps, 0.05)
    }

    @Test fun theBestNeedsThreeFreeSecondsInARow() {
        val r = DownloadRate()
        var bytes = 0L
        var t = 0L
        r.sample(t, bytes, free = false)
        for (free in listOf(true, true, false, true, true)) {
            t += 1_000
            bytes += 25_000_000
            r.sample(t, bytes, free)
        }
        assertEquals(0.0, r.bestFreeMbps, 0.0)
        t += 1_000
        bytes += 25_000_000
        r.sample(t, bytes, free = true)
        assertEquals(200.0, r.bestFreeMbps, 0.05)
        // A slower stretch later doesn't lower it.
        repeat(4) {
            t += 1_000
            bytes += 5_000_000
            r.sample(t, bytes, free = true)
        }
        assertEquals(200.0, r.bestFreeMbps, 0.05)
    }

    @Test fun aNewConnectionsCountOrALongGapIsntARate() {
        val r = DownloadRate()
        r.sample(0, 1_000_000, free = true)
        r.sample(1_000, 2_000_000, free = true)
        // The count starting over (another player): nothing is added for that second.
        r.sample(2_000, 500, free = true)
        // Away for a minute.
        r.sample(62_000, 90_000_000, free = true)
        assertEquals(8.0, r.mbps, 0.01)
        r.reset()
        assertEquals(0.0, r.bestFreeMbps, 0.0)
        assertEquals(0.0, r.mbps, 0.0)
    }
}
