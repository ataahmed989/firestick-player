package app.fireplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageFitTest {

    @Test
    fun aPosterSmallerThanTwiceTheCardIsNotShrunkWhileDecoding() {
        // metahub poster/small 300 x 450 for a 281 x 376 card.
        assertEquals(1, ImageFit.sampleSize(300, 450, 281, 376))
    }

    @Test
    fun aBigBackdropIsHalvedForTheHeader() {
        // 1920 x 1080 for the header's half-size box (496 x 195): the crop uses 1920 x 755, halved still covers it.
        assertEquals(2, ImageFit.sampleSize(1920, 1080, 496, 195))
        // 3840 wide: a quarter.
        assertEquals(4, ImageFit.sampleSize(3840, 2160, 496, 195))
    }

    @Test
    fun bothSidesStayAtLeastTheTarget() {
        // A tall poster for a wide card: the height decides.
        val s = ImageFit.sampleSize(1000, 1500, 423, 237)
        val scale = maxOf(423f / 1000f, 237f / 1500f)
        val cropW = 423f / scale
        assertEquals(true, cropW / s >= 423f)
        assertEquals(2, s)
    }

    @Test
    fun nonsenseSizesDecodeAsTheyAre() {
        assertEquals(1, ImageFit.sampleSize(0, 0, 100, 100))
        assertEquals(1, ImageFit.sampleSize(100, 100, 0, 100))
    }
}
