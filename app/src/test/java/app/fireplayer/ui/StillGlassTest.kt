package app.fireplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StillGlassTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun aFlatPictureStaysFlat() {
        val px = IntArray(12 * 8) { rgb(40, 80, 160) }
        val out = StillGlass.boxBlur3(px, 12, 8, 3, 1f)
        assertTrue(out.all { it == rgb(40, 80, 160) })
    }

    @Test fun lightDimsEveryChannel() {
        val px = IntArray(4 * 4) { rgb(200, 100, 50) }
        val out = StillGlass.boxBlur3(px, 4, 4, 1, 0.5f)
        assertEquals(rgb(100, 50, 25), out[5])
    }

    @Test fun anEdgeIsSoftened() {
        val w = 20
        val px = IntArray(w * 2) { i -> if (i % w < w / 2) rgb(0, 0, 0) else rgb(255, 255, 255) }
        val out = StillGlass.boxBlur3(px, w, 2, 2, 1f)
        val mid = (out[w / 2] shr 16) and 0xFF
        assertTrue("edge should be grey, was $mid", mid in 60..200)
        // The input isn't changed.
        assertEquals(rgb(0, 0, 0), px[0])
    }
}
