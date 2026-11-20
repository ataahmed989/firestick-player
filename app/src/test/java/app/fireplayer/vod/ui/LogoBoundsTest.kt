package app.fireplayer.vod.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class LogoBoundsTest {
    @Test
    fun theVisiblePartIsFoundInsideEmptyMargins() {
        val w = 8
        val h = 6
        val px = IntArray(w * h)
        for (y in 2..3) for (x in 3..6) px[y * w + x] = 0xFFFFFFFF.toInt()
        px[0] = 0x10FFFFFF // too faint to count
        assertArrayEquals(intArrayOf(3, 2, 7, 4), LogoBounds.of(px, w, h))
    }

    @Test
    fun anEmptyPictureKeepsItsWholeSize() {
        assertArrayEquals(intArrayOf(0, 0, 4, 3), LogoBounds.of(IntArray(12), 4, 3))
    }
}
