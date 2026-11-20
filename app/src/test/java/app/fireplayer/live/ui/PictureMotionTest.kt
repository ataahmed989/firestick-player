package app.fireplayer.live.ui

import app.fireplayer.live.ui.LivePicture.Placement
import app.fireplayer.live.ui.PictureMotion.Box
import app.fireplayer.player.PictureFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureMotionTest {
    private val eps = 0.001f

    /** The miniature's slot on a 1080p TV (384 x 216 dp at density 2, at 24 dp, 24 dp) and the whole screen. */
    private val slot = Box(48f, 48f, 816f, 480f)
    private val screen = Box(0f, 0f, 1920f, 1080f)
    private val radius = 24f

    private fun mini(aspect: Float) = LivePicture.place(slot.left, slot.top, slot.width, slot.height, aspect, full = false, mode = PictureFit.NORMAL)
    private fun full(aspect: Float, mode: Int = PictureFit.NORMAL) = LivePicture.place(0f, 0f, 1920f, 1080f, aspect, full = true, mode = mode)

    private fun assertBox(expected: Box, actual: Box) {
        assertEquals("left", expected.left, actual.left, eps)
        assertEquals("top", expected.top, actual.top, eps)
        assertEquals("right", expected.right, actual.right, eps)
        assertEquals("bottom", expected.bottom, actual.bottom, eps)
    }

    /** Where a view laid out at [laid] shows with the transform [t] (pivot at its top left), as the TV draws it. */
    private fun shownWith(laid: Placement, t: FloatArray): Box {
        val left = laid.left + t[2]
        val top = laid.top + t[3]
        return Box(left, top, left + laid.width * t[0], top + laid.height * t[1])
    }

    private fun settledInMiniature(aspect: Float = 16f / 9f) = PictureMotion().also { it.settle(mini(aspect), slot, radius, full = false) }

    @Test fun boxesMoveInAStraightLine() {
        val b = Box()
        assertBox(slot, b.lerp(slot, screen, 0f))
        assertBox(screen, b.lerp(slot, screen, 1f))
        assertBox(Box(24f, 24f, 1368f, 780f), b.lerp(slot, screen, 0.5f))
        assertEquals(5f, PictureMotion.mix(0f, 10f, 0.5f), eps)
        assertEquals(24f, PictureMotion.mix(24f, 0f, 0f), eps)
    }

    @Test fun theTransformShowsTheLaidOutSurfaceExactlyWhereThePictureIs() {
        val out = FloatArray(4)
        // Growing: laid out as the miniature, shown on the way to full screen (bigger than its layout).
        val laid = mini(16f / 9f)
        val shown = Box(10f, 20f, 1500f, 858.125f)
        PictureMotion.transform(laid, shown, out)
        assertBox(shown, shownWith(laid, out))
        // Shrinking from Zoom: laid out bigger than the screen and off its edges, shown smaller.
        val zoomed = full(16f / 9f, PictureFit.ZOOM)
        val small = Box(100f, 90f, 868f, 522f)
        PictureMotion.transform(zoomed, small, out)
        assertBox(small, shownWith(zoomed, out))
    }

    @Test fun theTransformIsTheIdentityWhereTheSurfaceIsLaidOut() {
        val out = FloatArray(4)
        val laid = mini(4f / 3f)
        PictureMotion.transform(laid, Box().set(laid), out)
        assertEquals(1f, out[0], eps)
        assertEquals(1f, out[1], eps)
        assertEquals(0f, out[2], eps)
        assertEquals(0f, out[3], eps)
    }

    @Test fun growingToFullScreenGoesFromTheFittedMiniatureToTheFittedScreen() {
        val m = settledInMiniature(4f / 3f)
        m.start(full(4f / 3f), screen, 0f, full = true)
        m.at(0f)
        // It starts exactly where it was: the 4:3 picture fitted in the slot, the hole round-cornered around the slot.
        assertBox(Box().set(mini(4f / 3f)), m.picture)
        assertBox(slot, m.hole)
        assertEquals(radius, m.radius, eps)
        assertEquals(0f, m.fullness, eps)
        m.at(0.5f)
        assertBox(Box().lerp(Box().set(mini(4f / 3f)), Box().set(full(4f / 3f)), 0.5f), m.picture)
        assertBox(Box().lerp(slot, screen, 0.5f), m.hole)
        assertEquals(radius / 2, m.radius, eps)
        assertEquals(0.5f, m.fullness, eps)
        m.at(1f)
        // It ends exactly at full screen's picture, square-cornered, all of full screen showing.
        assertBox(Box().set(full(4f / 3f)), m.picture)
        assertBox(screen, m.hole)
        assertEquals(0f, m.radius, eps)
        assertEquals(1f, m.fullness, eps)
    }

    @Test fun thePictureNeverLeavesTheHoleOnTheWay() {
        // A 4:3 channel: black bars beside it inside the hole all the way, never wallpaper over the picture.
        val m = settledInMiniature(4f / 3f)
        m.start(full(4f / 3f), screen, 0f, full = true)
        for (i in 0..20) {
            m.at(i / 20f)
            assertTrue(m.picture.left >= m.hole.left - eps && m.picture.right <= m.hole.right + eps)
            assertTrue(m.picture.top >= m.hole.top - eps && m.picture.bottom <= m.hole.bottom + eps)
        }
    }

    @Test fun shrinkingBackStartsFromFullScreen() {
        val m = PictureMotion()
        m.settle(full(16f / 9f, PictureFit.ZOOM), screen, 0f, full = true)
        m.start(mini(16f / 9f), slot, radius, full = false)
        m.at(0f)
        assertBox(Box().set(full(16f / 9f, PictureFit.ZOOM)), m.picture)
        assertBox(screen, m.hole)
        assertEquals(1f, m.fullness, eps)
        m.at(1f)
        assertBox(Box().set(mini(16f / 9f)), m.picture)
        assertBox(slot, m.hole)
        assertEquals(radius, m.radius, eps)
        assertEquals(0f, m.fullness, eps)
    }

    @Test fun aMoveInterruptedByAnotherCarriesOnFromWhereThePictureIs() {
        val m = settledInMiniature()
        m.start(full(16f / 9f), screen, 0f, full = true)
        m.at(0.4f)
        val pictureThen = Box().set(m.picture)
        val holeThen = Box().set(m.hole)
        val radiusThen = m.radius
        val fullnessThen = m.fullness
        // Back to the miniature before it got there: the new move starts where the picture is, no jump.
        m.start(mini(16f / 9f), slot, radius, full = false)
        m.at(0f)
        assertBox(pictureThen, m.picture)
        assertBox(holeThen, m.hole)
        assertEquals(radiusThen, m.radius, eps)
        assertEquals(fullnessThen, m.fullness, eps)
        m.at(1f)
        assertBox(Box().set(mini(16f / 9f)), m.picture)
        assertEquals(0f, m.fullness, eps)
    }

    @Test fun itKnowsWhereItIsAndWhereItsHeading() {
        val m = settledInMiniature()
        assertTrue(m.isAt(mini(16f / 9f), slot, radius, full = false))
        assertTrue(m.endsAt(mini(16f / 9f), slot, radius, full = false))
        m.start(full(16f / 9f), screen, 0f, full = true)
        m.at(0.3f)
        assertFalse(m.isAt(full(16f / 9f), screen, 0f, full = true))
        assertTrue(m.endsAt(full(16f / 9f), screen, 0f, full = true))
        assertFalse(m.endsAt(full(4f / 3f), screen, 0f, full = true))
        assertFalse(m.endsAt(mini(16f / 9f), slot, radius, full = false))
        m.at(1f)
        assertTrue(m.isAt(full(16f / 9f), screen, 0f, full = true))
        // The same place, but not full screen, is somewhere else (the dimmer and captions differ).
        assertFalse(m.isAt(full(16f / 9f), screen, 0f, full = false))
    }

    @Test fun nothingShownIsNothingOfFullScreen() {
        val m = PictureMotion()
        m.settle(full(16f / 9f), screen, 0f, full = true)
        assertEquals(1f, m.fullness, eps)
        m.clear()
        assertEquals(0f, m.fullness, eps)
    }
}
