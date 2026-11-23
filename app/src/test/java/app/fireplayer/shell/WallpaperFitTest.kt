package app.fireplayer.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperFitTest {

    @Test
    fun theGlassWallpaperAtTheScreensSizeIsUsedAsDecoded() {
        // wallpaper_glass.png is 1920 x 1080, the Fire TV's screen: no second full-screen copy.
        assertTrue(WallpaperFit.asDecoded(1920, 1080, argb = true, w = 1920, h = 1080))
        // The copy it saves: one 1920 x 1080 ARGB picture (about 8 MB) made and drawn on every return from a film.
        assertEquals(8_294_400L, 1920L * 1080 * 4)
    }

    @Test
    fun anyOtherSizeOrFormatIsStillScaledAndCropped() {
        // The classic look's 1672 x 941 JPEG.
        assertFalse(WallpaperFit.asDecoded(1672, 941, argb = true, w = 1920, h = 1080))
        // The glass picture on a 720p screen, or a 4K one.
        assertFalse(WallpaperFit.asDecoded(1920, 1080, argb = true, w = 1280, h = 720))
        assertFalse(WallpaperFit.asDecoded(1920, 1080, argb = true, w = 3840, h = 2160))
        // Decoded in another format (a 16-bit PNG comes as half floats): copied into the screen's ARGB as before.
        assertFalse(WallpaperFit.asDecoded(1920, 1080, argb = false, w = 1920, h = 1080))
        // Not laid out yet.
        assertFalse(WallpaperFit.asDecoded(0, 0, argb = true, w = 0, h = 0))
    }
}
