package app.fireplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlackBarsTest {
    private val w = 160
    private val h = 180
    private val lit = 0xFF808080.toInt()
    private val black = 0xFF000000.toInt()

    /** A picture lit from [top] to [bottom] lines and [left] to [right] dots, black around. */
    private fun frame(top: Int, bottom: Int, left: Int = 0, right: Int = w): BlackBars.Look {
        val px = IntArray(w * h) { black }
        for (y in top until bottom) for (x in left until right) px[y * w + x] = lit
        return BlackBars.measure(px, w, h)
    }

    @Test fun measuresBars() {
        val look = frame(24, 156)
        assertEquals(24, look.top)
        assertEquals(24, look.bottom)
        assertEquals(0, look.left)
        assertTrue(look.edgedV)
    }

    @Test fun barsNeedSeveralLooksThatAgree() {
        val bars = BlackBars()
        repeat(BlackBars.AGREE - 1) { assertFalse(bars.see(frame(24, 156))) }
        assertEquals(0f, bars.vertical, 0f)
        assertTrue(bars.see(frame(24, 156)))
        assertEquals(24.5f / h, bars.vertical, 0.0001f)
        assertEquals(0f, bars.horizontal, 0f)
    }

    @Test fun sideBarsOfAnOldShow() {
        val bars = BlackBars()
        repeat(BlackBars.AGREE) { bars.see(frame(0, h, 20, 140)) }
        assertEquals(20.5f / w, bars.horizontal, 0.0001f)
        assertEquals(0f, bars.vertical, 0f)
    }

    @Test fun aLogoOnBlackSaysNothing() {
        val bars = BlackBars()
        repeat(20) { assertFalse(bars.see(frame(70, 110, 60, 100))) }
        assertEquals(0f, bars.vertical, 0f)
        assertEquals(0f, bars.horizontal, 0f)
        // The film itself still gets its bars found after it.
        repeat(BlackBars.AGREE) { bars.see(frame(24, 156)) }
        assertEquals(24.5f / h, bars.vertical, 0.0001f)
    }

    @Test fun aBlackScreenSaysNothing() {
        val bars = BlackBars()
        repeat(20) { assertFalse(bars.see(frame(0, 0))) }
        assertFalse(bars.settled)
    }

    @Test fun darkScenesDoNotWidenTheBars() {
        val bars = BlackBars()
        repeat(BlackBars.AGREE) { bars.see(frame(24, 156)) }
        repeat(10) { assertFalse(bars.see(frame(60, 120))) }
        assertEquals(24.5f / h, bars.vertical, 0.0001f)
    }

    @Test fun aFullPictureMeansNoBarsAndNoMoreLooking() {
        val bars = BlackBars()
        repeat(BlackBars.DOUBTS) { bars.see(frame(0, h)) }
        assertTrue(bars.settled)
        repeat(20) { assertFalse(bars.see(frame(24, 156))) }
        assertEquals(0f, bars.vertical, 0f)
    }

    @Test fun pictureInsideTheBarsDropsThemForGood() {
        val bars = BlackBars()
        repeat(BlackBars.AGREE) { bars.see(frame(24, 156)) }
        assertFalse(bars.see(frame(0, h)))
        assertTrue(bars.see(frame(0, h)))
        assertEquals(0f, bars.vertical, 0f)
        repeat(20) { assertFalse(bars.see(frame(24, 156))) }
        assertEquals(0f, bars.vertical, 0f)
    }

    @Test fun unevenBarsUseTheSmaller() {
        val bars = BlackBars()
        repeat(BlackBars.AGREE) { bars.see(frame(30, 160)) }
        assertEquals(20.5f / h, bars.vertical, 0.0001f)
    }

    @Test fun aNewFilmStartsAfresh() {
        val bars = BlackBars()
        repeat(BlackBars.AGREE) { bars.see(frame(24, 156)) }
        bars.reset()
        assertEquals(0f, bars.vertical, 0f)
        assertFalse(bars.found)
    }
}
