package app.fireplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmMemoryTest {

    private val mib = 1L shl 20
    private val gib = 1L shl 30
    // What Fire OS allows FirePlayer (largeHeap) on the Fire TV Sticks: dalvik.vm.heapsize 384m.
    private val stickHeap = 384 * mib

    private fun gib(x: Double) = (x * gib).toLong()

    // ---- Tiers

    @Test fun theFireTvStick4kMaxIsTheSmallTier() {
        // AFTKRT reports about 1,710 MB in all (1.59 GiB).
        assertEquals(RamTier.SMALL, RamTier.of(1_710_000_000L, lowRam = false))
    }

    @Test fun tiersGoByTheTvsMemoryInAll() {
        assertEquals(RamTier.TINY, RamTier.of(gib(1.0), false))
        assertEquals(RamTier.TINY, RamTier.of(gib(1.4) - 1, false))
        assertEquals(RamTier.SMALL, RamTier.of(gib(1.4), false))
        assertEquals(RamTier.SMALL, RamTier.of(gib(1.8) - 1, false))
        assertEquals(RamTier.MEDIUM, RamTier.of(gib(1.8), false))
        assertEquals(RamTier.MEDIUM, RamTier.of(gib(2.6) - 1, false))
        assertEquals(RamTier.LARGE, RamTier.of(gib(2.6), false))
        assertEquals(RamTier.LARGE, RamTier.of(4 * gib, false))
    }

    @Test fun aLowMemoryDeviceIsTheTinyTierWhateverItReports() {
        assertEquals(RamTier.TINY, RamTier.of(4 * gib, lowRam = true))
    }

    @Test fun memoryNotReportedIsTheLargestTierHeldToTheHeapLimit() {
        assertEquals(RamTier.LARGE, RamTier.of(0L, false))
        // 0.6 of a 384 MiB heap, as before tiers.
        assertEquals((stickHeap * 0.6).toInt(), FilmMemory.plan(RamTier.LARGE, stickHeap).budgetBytes)
    }

    // ---- The film's buffer for each tier

    @Test fun eachTierGetsItsBudgetAndWhatItKeepsBehind() {
        val bigHeap = 512 * mib
        fun plan(t: RamTier) = FilmMemory.plan(t, bigHeap).let { (it.budgetBytes / mib).toInt() to it.backBufferMs }
        assertEquals(64 to 4_000, plan(RamTier.TINY))
        assertEquals(128 to 6_000, plan(RamTier.SMALL))
        assertEquals(176 to 10_000, plan(RamTier.MEDIUM))
        assertEquals(256 to 15_000, plan(RamTier.LARGE))
    }

    @Test fun theStickGetsAboutHalfItsOldBuffer() {
        val plan = FilmMemory.plan(RamTier.of(1_710_000_000L, false), stickHeap)
        assertEquals(128 * mib, plan.budgetBytes.toLong())
        assertEquals(6_000, plan.backBufferMs)
        // It was 0.6 x 384 MiB = 230 MiB, with 13 s kept behind.
        assertTrue(plan.budgetBytes < stickHeap * 0.6)
    }

    @Test fun theHeapLimitStillCapsTheBudgetAndWhatsKeptBehind() {
        // A 2.6 GiB+ TV whose app heap is 192 MiB: 0.6 of it, 115 MiB, under even the 128 MiB tier, so 4 s behind.
        val plan = FilmMemory.plan(RamTier.LARGE, 192 * mib)
        assertEquals((192 * mib * 0.6).toInt(), plan.budgetBytes)
        assertEquals(4_000, plan.backBufferMs)
        // Held to 230 MiB by the Stick's heap limit, a large TV keeps what a 176 MiB one does.
        assertEquals(10_000, FilmMemory.plan(RamTier.LARGE, stickHeap).backBufferMs)
    }

    @Test fun neverUnder48NorOver400MiB() {
        assertEquals(FilmMemory.FLOOR_BYTES, FilmMemory.plan(RamTier.TINY, 32 * mib).budgetBytes)
        assertEquals(FilmMemory.FLOOR_BYTES, FilmMemory.plan(RamTier.LARGE, 16 * mib).budgetBytes)
        for (t in RamTier.entries) assertTrue(FilmMemory.plan(t, 8 * gib).budgetBytes <= FilmMemory.CEILING_BYTES)
    }

    @Test fun lessMemoryNeverMeansMoreBuffer() {
        for ((smaller, larger) in RamTier.entries.zipWithNext()) {
            assertTrue(smaller.budgetMiB < larger.budgetMiB)
            assertTrue(smaller.backBufferMs < larger.backBufferMs)
        }
        assertEquals(FilmMemory.LEAST_BACK_BUFFER_MS, RamTier.entries.minOf { it.backBufferMs })
    }

    // ---- Squeezing it while the TV is short of memory

    @Test fun squeezeStepsAreThreeQuartersThenHalf() {
        val budget = (128 * mib).toInt()
        assertEquals(budget, FilmMemory.capFor(budget, 0))
        assertEquals((96 * mib).toInt(), FilmMemory.capFor(budget, 1))
        assertEquals((64 * mib).toInt(), FilmMemory.capFor(budget, 2))
        // Anything past the last step is the last step.
        assertEquals((64 * mib).toInt(), FilmMemory.capFor(budget, 5))
    }

    @Test fun squeezedNeverBelow48MiB() {
        val budget = (64 * mib).toInt()
        assertEquals(FilmMemory.FLOOR_BYTES, FilmMemory.capFor(budget, 1))
        assertEquals(FilmMemory.FLOOR_BYTES, FilmMemory.capFor(budget, 2))
        assertEquals(FilmMemory.FLOOR_BYTES, FilmMemory.capFor(FilmMemory.FLOOR_BYTES, 2))
    }

    @Test fun aSqueezedBufferStillLoadsWhenLittleIsAhead() {
        val budget = (128 * mib).toInt()
        val cap = FilmMemory.capFor(budget, 2)
        assertEquals(cap, FilmMemory.fillLimit(cap, budget, FilmMemory.MIN_AHEAD_US))
        assertEquals(cap, FilmMemory.fillLimit(cap, budget, 60_000_000L))
        // What's kept behind can't shrink while playing: under 5 s ahead, the whole budget may be used.
        assertEquals(budget, FilmMemory.fillLimit(cap, budget, FilmMemory.MIN_AHEAD_US - 1))
        assertEquals(budget, FilmMemory.fillLimit(cap, budget, 0L))
        // Not squeezed: the budget either way.
        assertEquals(budget, FilmMemory.fillLimit(budget, budget, 60_000_000L))
    }

    // ---- When to squeeze, and when to give it back

    @Test fun lowMemoryOnceIsStepOne() {
        val p = MemoryPressure()
        assertFalse(p.checked(low = false, nowMs = 0))
        assertTrue(p.checked(low = true, nowMs = 10_000))
        assertEquals(1, p.step)
    }

    @Test fun stillLowHalfAMinuteLaterIsStepTwo() {
        val p = MemoryPressure()
        assertTrue(p.checked(true, 0))
        assertFalse(p.checked(true, 10_000))
        assertFalse(p.checked(true, 20_000))
        assertEquals(1, p.step)
        assertTrue(p.checked(true, 30_000))
        assertEquals(2, p.step)
        assertFalse(p.checked(true, 40_000))
        assertEquals(2, p.step)
    }

    @Test fun aBreakInTheLowReadingsStartsTheHalfMinuteAgain() {
        val p = MemoryPressure()
        p.checked(true, 0)
        p.checked(false, 10_000)
        p.checked(true, 20_000)
        p.checked(true, 40_000)
        assertEquals(1, p.step)
        p.checked(true, 50_000)
        assertEquals(2, p.step)
    }

    @Test fun trimRequestsGoStraightToTheirStep() {
        val p = MemoryPressure()
        assertTrue(p.trimAsked(1, 0))
        assertEquals(1, p.step)
        assertTrue(p.trimAsked(2, 1_000))
        assertEquals(2, p.step)
        // A milder request never gives memory back.
        assertFalse(p.trimAsked(1, 2_000))
        assertEquals(2, p.step)
    }

    @Test fun stepsBackUpOneStepEveryTwoMinutesWithoutPressure() {
        val p = MemoryPressure()
        p.trimAsked(2, 0)
        var t = 10_000L
        while (t < 120_000L) {
            assertFalse(p.checked(false, t))
            t += 10_000L
        }
        assertEquals(2, p.step)
        assertTrue(p.checked(false, 120_000))
        assertEquals(1, p.step)
        assertFalse(p.checked(false, 230_000))
        assertTrue(p.checked(false, 240_000))
        assertEquals(0, p.step)
        assertFalse(p.checked(false, 600_000))
        assertEquals(0, p.step)
    }

    @Test fun pressureAgainRestartsTheTwoMinutes() {
        val p = MemoryPressure()
        p.checked(true, 0)
        p.checked(false, 10_000)
        // Fire OS asks to trim at 100 s: no step back till 220 s.
        p.trimAsked(1, 100_000)
        assertFalse(p.checked(false, 130_000))
        assertFalse(p.checked(false, 210_000))
        assertTrue(p.checked(false, 220_000))
        assertEquals(0, p.step)
    }

    // ---- The skip preview

    @Test fun onlyTvsWith2_6GiBOrMoreKeepFourKPreviewsAndTheLongHold() {
        assertFalse(RamTier.LARGE.short)
        assertEquals(15_000L, RamTier.LARGE.previewHoldMs)
        for (t in listOf(RamTier.TINY, RamTier.SMALL, RamTier.MEDIUM)) {
            assertTrue(t.name, t.short)
            assertEquals(t.name, 3_000L, t.previewHoldMs)
        }
    }
}
