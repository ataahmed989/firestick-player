package app.fireplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ReorderMotionTest {

    @Test
    fun theSpringsSettleAsGlassHomesDo() {
        // Glass Home's Spring.settleMs(): ceil(ln(1000) / (damping * 2π / response) * 1000).
        assertEquals(492L, ReorderMotion.LIFT.settleMs)
        assertEquals(574L, ReorderMotion.REORDER.settleMs)
        assertEquals(0f, ReorderMotion.REORDER.progress(0), 1e-6f)
        assertEquals(1f, ReorderMotion.REORDER.progress(574), 0f)
        // No bounce to speak of: at most 0.1 % past the target on the way, then there.
        for (ms in 0L..700L step 2) {
            val p = ReorderMotion.REORDER.progress(ms)
            assertTrue(p >= 0f && p <= 1.001f)
        }
        assertTrue(ReorderMotion.REORDER.progress(287) < ReorderMotion.REORDER.progress(288))
    }

    @Test
    fun aGlideKeepsItsSpeedAndComesToRest() {
        val s = ReorderMotion.LIFT
        assertEquals(100f, s.x(100f, 0f, 0f), 1e-3f)
        assertEquals(50f, s.v(0f, 50f, 0f), 1e-3f)
        assertTrue(abs(s.x(100f, 0f, 1f)) < 0.1f)
        assertTrue(abs(s.v(100f, 0f, 1f)) < 1f)
    }

    @Test
    fun theJiggleRocksAboutItsCentreEasingInOverTheFirstWobble() {
        assertEquals(0f, ReorderMotion.jiggleAngle(0), 1e-6f)
        // A quarter wobble in, a quarter of the way in: the ramp holds it at a quarter of its swing.
        assertEquals(1.6f * 0.25f, ReorderMotion.jiggleAngle(65), 1e-3f)
        // Past the first wobble: the whole swing, either way.
        assertEquals(1.6f, ReorderMotion.jiggleAngle(260 + 65), 1e-3f)
        assertEquals(-1.6f, ReorderMotion.jiggleAngle(260 + 195), 1e-3f)
        assertEquals(0f, ReorderMotion.jiggleAngle(520), 1e-3f)
    }

    @Test
    fun theArrowsCarryItOnePlaceAndStopAtTheEnds() {
        assertEquals(1, ReorderMath.step(0, 1, 6))
        assertEquals(0, ReorderMath.step(0, -1, 6))
        assertEquals(5, ReorderMath.step(5, 1, 6))
        assertEquals(4, ReorderMath.step(5, -1, 6))
    }

    @Test
    fun theOrderAfterMoves() {
        val order = listOf("disney", "hulu", "netflix", "hbo", "apple", "prime")
        var now = order
        var at = 0
        // Disney+ carried three places right, one back.
        for (dir in listOf(1, 1, 1, -1)) {
            val to = ReorderMath.step(at, dir, now.size)
            now = ReorderMath.moved(now, at, to)
            at = to
        }
        assertEquals(listOf("hulu", "netflix", "disney", "hbo", "apple", "prime"), now)
        assertEquals(2, at)
        // A longer move: the ones between move along one place.
        assertEquals(listOf("prime", "disney", "hulu", "netflix", "hbo", "apple"), ReorderMath.moved(order, 5, 0))
        assertEquals(order, ReorderMath.moved(order, 2, 2))
        assertEquals(order, ReorderMath.moved(order, 2, 9))
    }

    @Test
    fun eachCardsPlaceAfterAMove() {
        // Card 1 to place 3: 2 and 3 move back one place.
        assertEquals(listOf(0, 3, 1, 2, 4), (0..4).map { ReorderMath.placeAfter(it, 1, 3) })
        // Card 3 to place 1: 1 and 2 move on one place.
        assertEquals(listOf(0, 2, 3, 1, 4), (0..4).map { ReorderMath.placeAfter(it, 3, 1) })
    }

    @Test
    fun aHeldArrowStepsAfter300msThenEvery110ms() {
        assertTrue(ReorderMath.acceptsStep(repeat = false, time = 1000, downTime = 1000, last = 0))
        assertFalse(ReorderMath.acceptsStep(repeat = true, time = 1250, downTime = 1000, last = 1000))
        assertTrue(ReorderMath.acceptsStep(repeat = true, time = 1300, downTime = 1000, last = 1000))
        assertFalse(ReorderMath.acceptsStep(repeat = true, time = 1350, downTime = 1000, last = 1300))
        assertTrue(ReorderMath.acceptsStep(repeat = true, time = 1410, downTime = 1000, last = 1300))
    }
}
