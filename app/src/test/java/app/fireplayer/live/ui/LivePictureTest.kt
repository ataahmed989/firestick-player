package app.fireplayer.live.ui

import app.fireplayer.live.ui.LivePicture.Placement
import app.fireplayer.player.PictureFit
import org.junit.Assert.assertEquals
import org.junit.Test

class LivePictureTest {
    /** Full screen on a 1080p TV. */
    private fun full(mode: Int, aspect: Float) = LivePicture.place(0f, 0f, 1920f, 1080f, aspect, full = true, mode = mode)

    @Test fun normalFitsThePictureCentredOnTheScreen() {
        assertEquals(Placement(0, 0, 1920, 1080), full(PictureFit.NORMAL, 16f / 9f))
        assertEquals(Placement(240, 0, 1440, 1080), full(PictureFit.NORMAL, 4f / 3f))
        assertEquals(Placement(0, 140, 1920, 800), full(PictureFit.NORMAL, 2.4f))
        // Shape not known yet: the whole screen, as before.
        assertEquals(Placement(0, 0, 1920, 1080), full(PictureFit.NORMAL, 0f))
    }

    @Test fun fillScreenAndZoomAreBiggerThanTheScreenAndCentredOnIt() {
        // A 4:3 channel filling the screen: a quarter of its height is cut, an eighth at the top and the bottom.
        assertEquals(Placement(0, -180, 1920, 1440), full(PictureFit.FILL, 4f / 3f))
        assertEquals(Placement(-336, 0, 2592, 1080), full(PictureFit.FILL, 2.4f))
        assertEquals(Placement(-144, -81, 2208, 1242), full(PictureFit.ZOOM, 16f / 9f))
    }

    @Test fun stretchAndFixedShapesAreTheVodPlayersChoices() {
        assertEquals(Placement(0, 0, 1920, 1080), full(PictureFit.STRETCH, 4f / 3f))
        assertEquals(Placement(0, 0, 1920, 1080), full(PictureFit.WIDE_16_9, 4f / 3f))
        assertEquals(Placement(240, 0, 1440, 1080), full(PictureFit.TV_4_3, 16f / 9f))
        assertEquals(Placement(0, 128, 1920, 823), full(PictureFit.CINEMA_21_9, 16f / 9f))
    }

    @Test fun theMiniatureAlwaysFitsWhateverDisplaySizeSays() {
        for (mode in PictureFit.NORMAL..PictureFit.CINEMA_21_9) {
            // A 4:3 channel in a 249x140 slot at (100, 50): as tall as the slot, centred across it.
            assertEquals(Placement(131, 50, 186, 140), LivePicture.place(100f, 50f, 249f, 140f, 4f / 3f, full = false, mode = mode))
        }
        // Shape not known yet: 16:9, as before.
        assertEquals(Placement(100, 50, 248, 140), LivePicture.place(100f, 50f, 249f, 140f, 0f, full = false, mode = PictureFit.FILL))
    }

    @Test fun captionsGoOnThePartOfThePictureOnScreen() {
        assertEquals(Placement(0, 0, 1920, 1080), LivePicture.onScreen(Placement(-144, -81, 2208, 1242), 1920, 1080))
        assertEquals(Placement(0, 0, 1920, 1080), LivePicture.onScreen(Placement(0, -180, 1920, 1440), 1920, 1080))
        // All on screen already: unchanged.
        assertEquals(Placement(240, 0, 1440, 1080), LivePicture.onScreen(Placement(240, 0, 1440, 1080), 1920, 1080))
    }
}
