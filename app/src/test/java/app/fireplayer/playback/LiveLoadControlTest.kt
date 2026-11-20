package app.fireplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live buffer's stall wait, memory ceiling and stall watch. */
class LiveLoadControlTest {

    // Medium: 3 s after a stall, 15 s minimum.
    private fun medium() = LiveCushion(afterStallUs = 3_000_000, minBufferUs = 15_000_000)

    @Test fun waitAfterAStallGrowsWithEachStallUpToThreeTimes() {
        val c = medium()
        c.stalls = 1
        assertEquals(3_000_000, c.afterStallNeedUs(0))
        c.stalls = 2
        assertEquals(6_000_000, c.afterStallNeedUs(0))
        c.stalls = 3
        assertEquals(9_000_000, c.afterStallNeedUs(0))
        c.stalls = 9
        assertEquals(9_000_000, c.afterStallNeedUs(0))
    }

    @Test fun neverWaitsForMoreThanTheMinimumBuffer() {
        // Small: 2 s after a stall, 5 s minimum.
        val c = LiveCushion(2_000_000, 5_000_000)
        c.stalls = 3
        assertEquals(5_000_000, c.afterStallNeedUs(0))
    }

    @Test fun aLiveWindowWaitsForAtMostHalfItsOffset() {
        val c = medium()
        c.stalls = 3
        assertEquals(4_000_000, c.afterStallNeedUs(8_000_000))
    }

    @Test fun memoryStepsLowerTheCeilingButKeepAFloor() {
        val c = medium()
        assertEquals(LiveCushion.NORMAL_BYTES, c.capBytes())
        c.memoryStep = 1
        assertEquals(LiveCushion.SQUEEZE_1_BYTES, c.capBytes())
        c.memoryStep = 2
        assertEquals(LiveCushion.SQUEEZE_2_BYTES, c.capBytes())
        c.stalls = 3
        assertEquals(9_000_000, c.floorUs())
        c.stalls = 0
        assertEquals(LiveCushion.FLOOR_US, c.floorUs())
    }

    @Test fun trimLevelsMapToSteps() {
        assertEquals(0, LiveCushion.stepFor(5))
        assertEquals(1, LiveCushion.stepFor(10))
        assertEquals(2, LiveCushion.stepFor(15))
    }

    @Test fun aStallThatRefillsIsLeftAlone() {
        val w = LiveStallWatch(limitMs = 12_000, progressMs = 500)
        w.started(0, 0)
        assertFalse(w.stuck(4_000, 1_000))
        assertFalse(w.stuck(8_000, 2_000))
        assertFalse(w.stuck(16_000, 3_000))
        assertFalse(w.stuck(20_000, 4_000))
    }

    @Test fun aStallWithNothingArrivingReconnects() {
        val w = LiveStallWatch(limitMs = 12_000, progressMs = 500)
        w.started(0, 200)
        assertFalse(w.stuck(4_000, 300))
        assertFalse(w.stuck(8_000, 300))
        assertTrue(w.stuck(12_000, 400))
    }

    @Test fun progressAfterAQuietSpellStartsTheCountAgain() {
        val w = LiveStallWatch(limitMs = 12_000, progressMs = 500)
        w.started(0, 0)
        assertFalse(w.stuck(8_000, 0))
        assertFalse(w.stuck(10_000, 800))
        assertFalse(w.stuck(20_000, 900))
        assertTrue(w.stuck(22_000, 900))
    }
}
