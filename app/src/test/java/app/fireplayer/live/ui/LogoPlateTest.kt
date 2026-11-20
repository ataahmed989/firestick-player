package app.fireplayer.live.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which plate a channel logo gets, from its pixels (once, as it loads). */
class LogoPlateTest {

    private val clear = 0x00000000

    /** A logo of [n] pixels: [inkShare] of them [ink], the rest see-through. */
    private fun logo(ink: Int, inkShare: Float, n: Int = 400): IntArray {
        val inked = (n * inkShare).toInt()
        return IntArray(n) { if (it < inked) ink else clear }
    }

    @Test fun aBlackWordmarkGetsTheLightPlate() {
        assertTrue(LogoPlate.light(logo(0xFF000000.toInt(), 0.3f)))
        // Dark grey ink too ("CINEMAX" in grey).
        assertTrue(LogoPlate.light(logo(0xFF404040.toInt(), 0.3f)))
        // Half see-through black ink is still dark ink.
        assertTrue(LogoPlate.light(logo(0x80000000.toInt(), 0.5f)))
    }

    @Test fun aWhiteWordmarkKeepsTheSoftPlate() {
        assertFalse(LogoPlate.light(logo(0xFFFFFFFF.toInt(), 0.3f)))
        // Mid grey reads a little better on the soft plate than on the light one.
        assertFalse(LogoPlate.light(logo(0xFF808080.toInt(), 0.3f)))
    }

    @Test fun colouredInkGoesByHowBrightItIs() {
        // Pure blue is dark (it vanishes on the dark wallpaper); pure red is bright enough.
        assertTrue(LogoPlate.light(logo(0xFF0000FF.toInt(), 0.3f)))
        assertFalse(LogoPlate.light(logo(0xFFFF0000.toInt(), 0.3f)))
    }

    @Test fun aLogoWithItsOwnBackgroundIsJustFramed() {
        // A black tile with white letters (a JPEG, say): it brings its own background.
        val px = IntArray(400) { if (it % 10 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        assertFalse(LogoPlate.light(px))
    }

    @Test fun mixedInkIsAveragedByHowMuchOfEachThereIs() {
        // Mostly white letters with a thin black outline: soft.
        val px = IntArray(400) { when { it < 100 -> 0xFFFFFFFF.toInt(); it < 120 -> 0xFF000000.toInt(); else -> clear } }
        assertFalse(LogoPlate.light(px))
        // Mostly black with a little white: light.
        val dark = IntArray(400) { when { it < 100 -> 0xFF000000.toInt(); it < 110 -> 0xFFFFFFFF.toInt(); else -> clear } }
        assertTrue(LogoPlate.light(dark))
    }

    @Test fun nothingToSeeKeepsTheSoftPlate() {
        assertFalse(LogoPlate.light(IntArray(100)))
        assertFalse(LogoPlate.light(IntArray(0)))
        // Only the pixels counted are looked at.
        assertFalse(LogoPlate.light(logo(0xFF000000.toInt(), 0.3f), count = 0))
    }

    @Test fun thePlatesAreTheTwoColours() {
        assertEquals(LogoPlate.LIGHT, LogoPlate.color(true))
        assertEquals(LogoPlate.SOFT, LogoPlate.color(false))
    }

    // ---- The glass look's tiles -----------------------------------------------------------------------------------

    private fun lightOnGlass(px: IntArray) = LogoPlate.light(px, darkInk = LogoPlate.DARK_INK_GLASS)

    @Test fun onGlassOnlyReallyDarkInkGetsTheLightTile() {
        // Black and dark grey wordmarks vanish on dark glass: the light tile.
        assertTrue(lightOnGlass(logo(0xFF000000.toInt(), 0.3f)))
        assertTrue(lightOnGlass(logo(0xFF404040.toInt(), 0.3f)))
        // Pure blue too.
        assertTrue(lightOnGlass(logo(0xFF0000FF.toInt(), 0.3f)))
        // White, mid grey and red stay on the dark tile, as they stay on the classic soft plate.
        assertFalse(lightOnGlass(logo(0xFFFFFFFF.toInt(), 0.3f)))
        assertFalse(lightOnGlass(logo(0xFF808080.toInt(), 0.3f)))
        assertFalse(lightOnGlass(logo(0xFFFF0000.toInt(), 0.3f)))
    }

    @Test fun theGlassTileTreatsDarkInkAsTheClassicPlateDoes() {
        // Seen on the TV: black wordmarks with a little colour in them (average ink between about 0.10 and 0.17)
        // vanished on the dark glass tile, so the glass look uses the classic threshold.
        val deepRed = logo(0xFFD00000.toInt(), 0.3f)
        assertTrue(LogoPlate.light(deepRed))
        assertTrue(lightOnGlass(deepRed))
        assertEquals(LogoPlate.DARK_INK, LogoPlate.DARK_INK_GLASS)
        // A logo with its own background is just framed there too.
        assertFalse(lightOnGlass(IntArray(400) { if (it % 10 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }))
    }

    @Test fun theLightTileIsFrostedNotSolid() {
        // See-through enough that what's behind tints it (the classic light plate is 80%), light enough for black ink.
        val alpha = LogoPlate.GLASS_LIGHT ushr 24
        assertTrue(alpha in 0x80..0xB8)
        assertTrue(alpha < (LogoPlate.LIGHT ushr 24))
        assertEquals(LogoPlate.LIGHT and 0xFFFFFF, LogoPlate.GLASS_LIGHT and 0xFFFFFF)
    }

    @Test fun aTilesCornersFollowItsHeight() {
        // Glass, at 2 px per dp: a guide row's 28 dp tile has about 6 dp corners, the details' 42 dp tile about 9 dp.
        assertEquals(12.3f, LogoPlate.radius(56f, glass = true, density = 2f), 0.1f)
        assertEquals(18.5f, LogoPlate.radius(84f, glass = true, density = 2f), 0.1f)
        // Classic: 4 dp as before on plates up to a guide row's; the larger details plate's grow with it.
        assertEquals(8f, LogoPlate.radius(56f, glass = false, density = 2f), 0.001f)
        assertEquals(8f, LogoPlate.radius(40f, glass = false, density = 2f), 0.001f)
        assertEquals(12f, LogoPlate.radius(84f, glass = false, density = 2f), 0.001f)
    }

    // The large logo's plate (LogoBadge): 92 px high with 18 x 12 px of room, so the logo's box is 68 px high.

    private fun badge(w: Int, h: Int) = LogoPlate.badgeWidth(w, h, height = 92, padX = 18f, padY = 12f, minWidth = 92, maxWidth = 208)

    @Test fun theBadgeIsAsWideAsItsLogoNeeds() {
        // Twice as wide as tall: 136 x 68 inside, plus the room each side.
        assertEquals(136 + 36, badge(200, 100))
        assertEquals(136 + 36, badge(50, 25))
    }

    @Test fun aTallLogoGetsTheLeastWidthAndAVeryWideOneTheMost() {
        // Square: 68 x 68 inside, plus the room each side.
        assertEquals(68 + 36, badge(100, 100))
        assertEquals(92, badge(40, 160))
        assertEquals(208, badge(600, 100))
        // Nothing to show (no logo yet): the least width.
        assertEquals(92, badge(0, 0))
    }

    @Test fun aLogoIsFittedWholeAndNeverStretched() {
        // A very wide logo in the widest badge's box (172 x 68): the width decides, and the same scale serves both sides.
        val s = LogoPlate.fitScale(600, 100, 172f, 68f)
        assertEquals(172f / 600, s, 1e-6f)
        assertTrue(100 * s <= 68f)
        // A tall one: the height decides.
        assertEquals(68f / 160, LogoPlate.fitScale(40, 160, 56f, 68f), 1e-6f)
        assertEquals(0f, LogoPlate.fitScale(0, 10, 56f, 68f), 0f)
    }
}
