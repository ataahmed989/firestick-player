package app.fireplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PictureFitTest {
    private fun size(mode: Int, aspect: Float, barV: Float = 0f, barH: Float = 0f) =
        PictureFit.size(mode, aspect, 1920, 1080, barV, barH)

    @Test fun nothingBeforeTheScreenIsMeasured() = assertNull(PictureFit.size(PictureFit.NORMAL, 1.78f, 0, 0))

    @Test fun normalKeepsTheFilmsShapeInsideTheScreen() {
        assertEquals(1920 to 1080, size(PictureFit.NORMAL, 16f / 9f))
        assertEquals(1920 to 800, size(PictureFit.NORMAL, 2.4f))
        assertEquals(1440 to 1080, size(PictureFit.NORMAL, 4f / 3f))
        // Not known yet, or as good as the screen's: full screen.
        assertEquals(1920 to 1080, size(PictureFit.NORMAL, 0f))
        assertEquals(1920 to 1080, size(PictureFit.NORMAL, 1920f / 1088f))
    }

    @Test fun fillScreenCoversTheScreen() {
        assertEquals(2592 to 1080, size(PictureFit.FILL, 2.4f))
        assertEquals(1920 to 1440, size(PictureFit.FILL, 4f / 3f))
        assertEquals(1920 to 1080, size(PictureFit.FILL, 16f / 9f))
    }

    @Test fun fillScreenLeavesSavedBarsOffTheScreen() {
        // A 2.4 film saved as 16:9: an eighth of the height is bar, top and bottom.
        val (w, h) = size(PictureFit.FILL, 16f / 9f, barV = 0.13f)!!
        // The picture between the bars is as tall as the screen.
        assertEquals(1080f, h * (1f - 0.26f), 1f)
        // And keeps its shape.
        assertEquals(16f / 9f, w.toFloat() / h, 0.01f)
    }

    @Test fun zoomIsBiggerThanNormal() {
        assertEquals(2208 to 1242, size(PictureFit.ZOOM, 16f / 9f))
        assertEquals(2208 to 920, size(PictureFit.ZOOM, 2.4f))
    }

    @Test fun stretchFillsTheScreenWhateverTheShape() {
        assertEquals(1920 to 1080, size(PictureFit.STRETCH, 2.4f))
        assertEquals(1920 to 1080, size(PictureFit.STRETCH, 4f / 3f))
        val (w, h) = size(PictureFit.STRETCH, 16f / 9f, barV = 0.125f, barH = 0.1f)!!
        assertEquals(1440, h)
        assertEquals(2400, w)
    }

    @Test fun shapesAreAsBefore() {
        assertEquals(1920 to 1080, size(PictureFit.WIDE_16_9, 2.4f))
        assertEquals(1440 to 1080, size(PictureFit.TV_4_3, 16f / 9f))
        assertEquals(1920 to 823, size(PictureFit.CINEMA_21_9, 16f / 9f))
    }
}
