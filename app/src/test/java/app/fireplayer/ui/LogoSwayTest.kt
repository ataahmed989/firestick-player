package app.fireplayer.ui

import app.fireplayer.shell.RailShape
import app.fireplayer.vod.RowStyle
import app.fireplayer.vod.data.Service
import app.fireplayer.vod.ui.VodSizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

class LogoSwayTest {

    private val reach = LogoSway.MAX_SHIFT_DP * LogoSway.FRONT_TRAVEL

    @Test
    fun glassHomesNumbers() {
        // Ui.FOCUS_MS, TileView.SWAY_MS / SWAY_CYCLES / REST_TILT_Y / MAX_SHIFT (5 of 1920 design px), the front of two
        // layers' travel ((1 - 0.3) * 2), and Ui.EASE, cubic-bezier(0.2, 0, 0, 1).
        assertEquals(180L, LogoSway.FOCUS_MS)
        assertEquals(2400L, LogoSway.SWAY_MS)
        assertEquals(1.25f, LogoSway.SWAY_CYCLES, 0f)
        assertEquals(-0.25f, LogoSway.REST_TILT_Y, 0f)
        assertEquals(2.5f, LogoSway.MAX_SHIFT_DP, 0f)
        assertEquals((1f - 0.3f) * 2f, LogoSway.FRONT_TRAVEL, 1e-6f)
        assertEquals(listOf(0.2f, 0f, 0f, 1f), listOf(RailShape.EASE_X1, RailShape.EASE_Y1, RailShape.EASE_X2, RailShape.EASE_Y2))
        assertEquals(0f, LogoSway.ease(0f), 0f)
        assertEquals(1f, LogoSway.ease(1f), 0f)
        // The sway starts and ends at rest, so nothing jumps.
        assertEquals(0f, LogoSway.swayX(0f), 1e-6f)
        assertEquals(LogoSway.REST_TILT_Y, LogoSway.swayY(0f), 1e-6f)
        assertEquals(0f, LogoSway.swayX(1f), 1e-6f)
        assertEquals(LogoSway.REST_TILT_Y, LogoSway.swayY(1f), 1e-6f)
    }

    @Test
    fun focusArrivingSwaysOnceAndSettlesALittleHigh() {
        val s = LogoSway()
        s.focusChanged(true, 1000)
        assertEquals(0f, s.dx, 0f)
        assertEquals(0f, s.dy, 0f)
        assertTrue(s.moving)
        // Focus arrives over 180 ms on the ease; the sway as Glass Home's, its tilt times the full reach.
        s.step(1090)
        assertEquals(LogoSway.ease(0.5f), s.focus, 1e-6f)
        s.step(1000 + 480)
        assertEquals(1f, s.focus, 0f)
        assertEquals(LogoSway.swayX(0.2f) * reach, s.dx, 1e-4f)
        assertEquals(LogoSway.swayY(0.2f) * reach, s.dy, 1e-4f)
        assertTrue(abs(s.dx) > 0.5f)
        // Then still, tipped back: the logo stands 0.875 dp high, and nothing is redrawn.
        assertFalse(s.step(1000 + 2400))
        assertEquals(0f, s.dx, 0f)
        assertEquals(LogoSway.REST_TILT_Y * reach, s.dy, 1e-6f)
        assertFalse(s.moving)
    }

    @Test
    fun focusGoingTakesItHomeFromWhereItIs() {
        val s = LogoSway()
        s.focusChanged(true, 0)
        s.step(400)
        val x = s.dx
        val y = s.dy
        s.focusChanged(false, 400)
        assertEquals(x, s.dx, 1e-6f)
        assertEquals(y, s.dy, 1e-6f)
        s.step(490)
        // The tilt holds; only focus falls, on the ease.
        assertEquals(x * (1f - LogoSway.ease(0.5f)), s.dx, 1e-4f)
        assertFalse(s.step(580))
        assertEquals(0f, s.dx, 0f)
        assertEquals(0f, s.dy, 0f)
    }

    @Test
    fun quickLeftAndRightNeverJumpsNorSticks() {
        // A card focused and left again and again at every rhythm a remote can give, frames at 60 a second: each
        // frame moves the logo only a little, a change of focus never moves it at all, it never goes past its
        // reach, and it ends home.
        val frame = 16L
        for (period in listOf(16L, 33L, 50L, 70L, 90L, 110L, 140L, 170L, 200L, 260L, 400L)) {
            val s = LogoSway()
            var now = 0L
            var on = false
            var lastX = 0f
            var lastY = 0f
            var widest = 0f
            for (i in 0 until 4000 / frame) {
                now += frame
                s.step(now)
                assertTrue("frame $period", abs(s.dx - lastX) < 0.5f && abs(s.dy - lastY) < 0.5f)
                if (now % period < frame && now < 3000) {
                    val bx = s.dx
                    val by = s.dy
                    on = !on
                    s.focusChanged(on, now)
                    assertEquals(bx, s.dx, 1e-5f)
                    assertEquals(by, s.dy, 1e-5f)
                }
                lastX = s.dx
                lastY = s.dy
                widest = max(widest, max(abs(s.dx), abs(s.dy)))
            }
            assertTrue("reach $period: $widest", widest <= LogoSway.MAX_REACH_DP)
            if (on) {
                s.focusChanged(false, now)
                s.step(now + LogoSway.FOCUS_MS)
            }
            s.step(now + LogoSway.SWAY_MS)
            assertFalse(s.moving)
            assertEquals(0f, s.dx, 0f)
            assertEquals(0f, s.dy, 0f)
        }
    }

    @Test
    fun settlingStopsWhereItWasHeading() {
        val s = LogoSway()
        s.focusChanged(true, 0)
        s.step(300)
        s.settle(true)
        assertFalse(s.moving)
        assertEquals(0f, s.dx, 0f)
        assertEquals(LogoSway.REST_TILT_Y * reach, s.dy, 1e-6f)
        // Already focused: the same focus again doesn't start it over.
        s.focusChanged(true, 400)
        assertFalse(s.moving)
        s.settle(false)
        assertEquals(0f, s.dy, 0f)
    }

    @Test
    fun theLogoStaysInsideItsCardsRimAtItsFarthest() {
        // Home's six cards at 960 x 540: each logo's whole picture (its clear room too), drawn its farthest either way,
        // stays inside the card's fine rim and its rounded corners, so neither the card nor the ring outside it cuts it.
        val style = RowStyle.SERVICES
        val w = VodSizes.cardW(style)
        val h = VodSizes.cardH(style)
        val rim = VodSizes.LOGO_RIM
        val r = VodSizes.logoRadiusFor(h) - rim
        val d = LogoSway.MAX_REACH_DP
        for (service in Service.values()) {
            val (pw, ph) = pictureSize(service)
            val k = VodSizes.logoFit(VodSizes.logoShare(service), pw, ph, w, h)
            val lw = pw * k
            val lh = ph * k
            for (ox in listOf(-d, d)) for (oy in listOf(-d, d)) {
                val l = (w - lw) / 2f + ox
                val t = (h - lh) / 2f + oy
                for ((x, y) in listOf(l to t, l + lw to t, l to t + lh, l + lw to t + lh)) {
                    assertTrue("$service", insideRounded(x, y, rim, rim, w - rim, h - rim, r))
                }
            }
        }
    }

    private fun insideRounded(x: Float, y: Float, left: Float, top: Float, right: Float, bottom: Float, r: Float): Boolean {
        if (x < left || x > right || y < top || y > bottom) return false
        val cx = x.coerceIn(left + r, right - r)
        val cy = y.coerceIn(top + r, bottom - r)
        val ex = x - cx
        val ey = y - cy
        return sqrt(ex * ex + ey * ey) <= r
    }

    /** The logo's picture's size, from its PNG's header (res/drawable-nodpi/card_logo_*.png). */
    private fun pictureSize(service: Service): Pair<Int, Int> {
        val name = when (service) {
            Service.DISNEY -> "disney_plus"
            Service.HULU -> "hulu"
            Service.NETFLIX -> "netflix"
            Service.HBO_MAX -> "hbo_max"
            Service.APPLE_TV -> "apple_tv"
            Service.PRIME_VIDEO -> "prime_video"
        }
        val file = listOf("src/main/res/drawable-nodpi", "app/src/main/res/drawable-nodpi")
            .map { File(it, "card_logo_$name.png") }.firstOrNull { it.isFile }
        assertNotNull(file)
        val b = file!!.readBytes()
        fun int(at: Int) = ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
        return int(16) to int(20)
    }
}
