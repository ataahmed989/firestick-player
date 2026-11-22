package app.fireplayer.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GlassStyleTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    // ---- The cell glass (the guide's programme cells; the Search tab's bar and history pods) --------------------------

    @Test fun theCellGlassIsTheRowFillWithAHairlineAndAGentleHighlight() {
        val cell = GlassStyle.CELL
        assertEquals(GlassStyle.ROW_FILL, cell.fill)
        assertEquals(1, cell.coats)
        assertTrue(cell.edge)
        assertEquals(GlassStyle.ROW_HIGHLIGHT, cell.highlight)
        assertTrue(!cell.sheen)
        // A surface is outlined and lit like the app's other glass unless it says otherwise.
        assertEquals(GlassStyle.HIGHLIGHT, GlassStyle.Surface(GlassStyle.CONTROL_TINT).highlight)
        assertTrue(GlassStyle.Surface(GlassStyle.CONTROL_TINT).edge)
    }

    @Test fun aSearchPodIsARowInTheCellGlass() {
        // Its own kinds, last in the list (the kinds' order is part of the drawings' memory keys).
        assertEquals(listOf(Glass.Kind.CELL, Glass.Kind.CELL_BAR), Glass.Kind.entries.takeLast(2))
        // A row either way: it sits on the page with no film of its own, and nothing else is one.
        assertEquals(
            listOf(Glass.Kind.ROW, Glass.Kind.CELL, Glass.Kind.CELL_BAR),
            Glass.Kind.entries.filter { Glass.isRow(it) },
        )
    }

    @Test fun theSearchBarHasTheCellsSofterCornersAndItsPodsRoundEnds() {
        val d = 1.5f
        // A Settings-tab row (PageRow, 44 dp): the pods' ends are fully round, the bar's corners the programme cells' 8 dp.
        val w = (300 * d).toInt()
        val h = (PageRow.ROW_DP * d).toInt()
        assertEquals(h / 2f, GlassBakes.radius(Glass.Kind.CELL, w, h, d), 0.001f)
        assertEquals(8f, GlassStyle.RADIUS_CELL)
        assertEquals(GlassStyle.RADIUS_CELL * d, GlassBakes.radius(Glass.Kind.CELL_BAR, w, h, d), 0.001f)
        assertTrue(GlassBakes.radius(Glass.Kind.CELL_BAR, w, h, d) < h / 2f)
    }

    // ---- When the page under a window is copied again ---------------------------------------------------------------

    @Test fun aChangeIsCopiedOnceItHasSettled() {
        // Long after the last copy: only the settling time is waited.
        assertEquals(10_000L + GlassStyle.SETTLE_MS, GlassStyle.nextCaptureAt(now = 10_000L, capturedAt = 2_000L, perSec = 4))
    }

    @Test fun aPageThatKeepsChangingIsCopiedTwiceASecondAtMost() {
        // Just copied: the next one waits half a second, whatever the Glass refresh setting asks for.
        for (perSec in listOf(2, 4, 8)) {
            assertEquals(10_500L, GlassStyle.nextCaptureAt(now = 10_010L, capturedAt = 10_000L, perSec = perSec))
        }
    }

    @Test fun aSlowerSettingIsKept() {
        assertEquals(11_000L, GlassStyle.nextCaptureAt(now = 10_010L, capturedAt = 10_000L, perSec = 1))
        // Nonsense is read as the slowest setting, never as a division by nothing.
        assertEquals(11_000L, GlassStyle.nextCaptureAt(now = 10_010L, capturedAt = 10_000L, perSec = 0))
    }

    @Test fun theNextCopyIsNeverInThePast() {
        for (now in longArrayOf(0L, 1L, 499L, 500L, 5_000L)) {
            assertTrue(GlassStyle.nextCaptureAt(now, capturedAt = 0L, perSec = 2) >= now + GlassStyle.SETTLE_MS)
        }
    }

    // ---- The backdrop's size and blur ----------------------------------------------------------------------------------

    @Test fun theBackdropIsATenthOfTheScreen() {
        assertEquals(192, GlassStyle.backdropSize(1920))
        assertEquals(108, GlassStyle.backdropSize(1080))
        // Rounded up, so the picture always covers the screen; never nothing.
        assertEquals(193, GlassStyle.backdropSize(1921))
        assertEquals(1, GlassStyle.backdropSize(0))
    }

    @Test fun theBlurReachesAsFarOnScreenWhateverTheBackdropsScale() {
        // 22 dp at density 2 is 44 px: 4 backdrop pixels at a tenth, 5 at an eighth; one at least.
        assertEquals(4, GlassStyle.blurRadius(density = 2f, scale = 10f))
        assertEquals(5, GlassStyle.blurRadius(density = 2f, scale = 8f))
        assertEquals(1, GlassStyle.blurRadius(density = 0.1f, scale = 10f))
    }

    @Test fun hairlinesAreAWholePixelAtLeast() {
        assertEquals(1f, GlassStyle.hairlinePx(1f), 0f)
        assertEquals(1f, GlassStyle.hairlinePx(2f), 0f)
        assertEquals(2f, GlassStyle.hairlinePx(4f), 0f)
        assertEquals(1f, GlassStyle.cardEdgePx(2f), 0f)
    }

    @Test fun theTintIsTranslucentAndNotBlue() {
        for (tint in intArrayOf(GlassStyle.APP_SHADE.color, GlassStyle.RAIL_SHADE.color, GlassStyle.CONTROL_TINT)) {
            val a = tint ushr 24
            // What's behind shows through, and the glass is dark enough for white words on it.
            assertTrue("alpha $a", a in 0x80..0xD0)
        }
        // The controls' close to neutral: the colour comes from what's behind, not from a blue wash.
        val c = GlassStyle.CONTROL_TINT
        assertTrue((c and 0xFF) - ((c shr 16) and 0xFF) <= 12)
        // The windows' colours are never bluer than the wallpaper's own.
        assertEquals(GlassStyle.WALLPAPER or (0xFF shl 24), GlassStyle.WINDOW_SOLID)
        val w = channels(GlassStyle.WALLPAPER)
        for (shade in listOf(GlassStyle.APP_SHADE, GlassStyle.RAIL_SHADE)) {
            val t = channels(shade.color)
            assertTrue("$shade", t[2] - t[0] <= w[2] - w[0])
        }
    }

    // ---- The user's glass (Settings › Look: App glass, Rail, cards & header, Playback glass) ---------------------------

    /** A colour's red, green and blue. */
    private fun channels(c: Int) = floatArrayOf(((c shr 16) and 0xFF).toFloat(), ((c shr 8) and 0xFF).toFloat(), (c and 0xFF).toFloat())

    /** What the screen shows where [top] (ARGB) is laid over grey [b] (0 to 255), per channel, unrounded. */
    private fun shows(top: Int, b: Int): FloatArray {
        val a = (top ushr 24) / 255f
        val c = channels(top)
        return FloatArray(3) { c[it] * a + b * (1f - a) }
    }

    /**
     * The model itself, two layers over grey [b]: the tint's colour (the background's own, or [charcoal]% of the way to
     * charcoal) at the tint's strength, then black at the dimness's.
     */
    private fun model(tint: Int, dim: Int, charcoal: Int, b: Int): FloatArray {
        val t = tint / 100f
        val d = dim / 100f
        val colour = channels(GlassStyle.charcoalColor(charcoal))
        return FloatArray(3) { ((1f - t) * b + t * colour[it]) * (1f - d) }
    }

    private fun assertShows(message: String, want: FloatArray, got: FloatArray) {
        for (i in 0..2) assertTrue("$message channel $i: ${got[i]} for ${want[i]}", abs(got[i] - want[i]) <= 1f)
    }

    @Test fun noTintAndNoDimnessLayNothing() {
        // Nothing at all: the frosted glass as it is, and over the wallpaper it's the wallpaper.
        for (charcoal in 0..100 step GlassStyle.SHADE_STEP) {
            val clear = GlassStyle.Shade(0, charcoal = charcoal)
            assertEquals(GlassStyle.CLEAR, clear.color)
            assertEquals(GlassStyle.WINDOW_SOLID, clear.solid)
            for (b in intArrayOf(0, 77, 255)) assertArrayEquals(floatArrayOf(b.toFloat(), b.toFloat(), b.toFloat()), shows(clear.color, b), 0f)
        }
        assertEquals(GlassStyle.CLEAR, GlassStyle.Shade(0, 0).color)
    }

    @Test fun noTintAddsNoColourLayerAndDimnessStillDarkens() {
        for (dim in GlassStyle.SHADE_STEP..100 step GlassStyle.SHADE_STEP) {
            val color = GlassStyle.Shade(0, dim).color
            // Black alone, as strong as the dimness: no colour of the background's or anyone's.
            assertEquals("$dim%", 0, color and 0xFFFFFF)
            assertEquals("$dim%", (dim * 255 + 50) / 100, color ushr 24)
            // What shows through keeps its own colour, darkened by the dimness.
            for (b in intArrayOf(64, 200, 255)) assertShows("$dim% over $b", FloatArray(3) { b * (1f - dim / 100f) }, shows(color, b))
        }
        // All the way: black.
        assertEquals(0xFF000000.toInt(), GlassStyle.Shade(0, 100).color)
        assertEquals(0xFF000000.toInt(), GlassStyle.Shade(100, 100).color)
    }

    @Test fun dimnessDarkensTheTintedGlassWhateverTheTint() {
        for (tint in 0..100 step GlassStyle.SHADE_STEP) for (dim in 0..100 step GlassStyle.SHADE_STEP) {
            // The tinted glass first, then the dimness on it: the same share of light taken from whatever the tint left.
            // The tinted glass as it really is (each stored colour is rounded to whole steps, so two of them can differ by
            // more than the one step allowed).
            val both = GlassStyle.Shade(tint, dim).color
            for (b in intArrayOf(0, 128, 255)) {
                val first = model(tint, 0, 0, b)
                assertShows("$tint% $dim% over $b", FloatArray(3) { first[it] * (1f - dim / 100f) }, shows(both, b))
            }
        }
        // Each step of dimness is darker over a picture, at no tint, the default tint and a solid one.
        for (tint in intArrayOf(0, 58, 100)) {
            var last = Float.MAX_VALUE
            for (dim in 0..100 step GlassStyle.SHADE_STEP) {
                val now = shows(GlassStyle.Shade(tint, dim).color, 200).sum()
                assertTrue("dimness $dim% at $tint%", now < last || now == 0f)
                last = now
            }
        }
    }

    @Test fun aTintIsTheBackgroundsOwnColourAtItsStrength() {
        // App glass and Playback glass: the wallpaper's measured average, solid at 100%.
        assertEquals(0x10191F, GlassStyle.WALLPAPER)
        assertEquals(0xFF10191F.toInt(), GlassStyle.Shade(100, 0).color)
        // Over white, each step of tint calms what shows through a little more.
        var last = Float.MAX_VALUE
        for (tint in 0..100 step GlassStyle.SHADE_STEP) {
            val now = shows(GlassStyle.Shade(tint, 0).color, 255).sum()
            assertTrue("tint $tint%", now < last)
            last = now
        }
    }

    @Test fun theOneColourIsTheTwoLayersToTheLevel() {
        for (tint in 0..100 step GlassStyle.SHADE_STEP) for (dim in 0..100 step GlassStyle.SHADE_STEP) {
            for (charcoal in intArrayOf(0, 25, 50, 75, 100)) {
                val color = GlassStyle.Shade(tint, dim, charcoal).color
                for (b in intArrayOf(0, 64, 128, 200, 255)) {
                    assertShows("$tint% $dim% $charcoal% over $b", model(tint, dim, charcoal, b), shows(color, b))
                }
            }
        }
    }

    @Test fun charcoalRunsFromTheBackgroundsColourToCharcoal() {
        // The two ends: the wallpaper's measured average, and the neutral glass colour before it took the wallpaper's.
        assertEquals(0x0C0E12, GlassStyle.CHARCOAL)
        assertEquals(GlassStyle.WALLPAPER, GlassStyle.charcoalColor(0))
        assertEquals(GlassStyle.CHARCOAL, GlassStyle.charcoalColor(100))
        // Solid at full tint: the background's colour at 0% charcoal, charcoal at 100%.
        assertEquals(0xFF10191F.toInt(), GlassStyle.Shade(100, charcoal = 0).color)
        assertEquals(0xFF0C0E12.toInt(), GlassStyle.Shade(100, charcoal = 100).color)
        // Each step on the way is the straight line between them, darker or as dark in every channel, never bluer.
        val w = channels(GlassStyle.WALLPAPER)
        val k = channels(GlassStyle.CHARCOAL)
        var last = w
        for (c in GlassStyle.SHADE_STEP..100 step GlassStyle.SHADE_STEP) {
            val now = channels(GlassStyle.charcoalColor(c))
            for (i in 0..2) {
                assertTrue("$c% channel $i", now[i] <= last[i])
                assertTrue("$c% channel $i on the line", abs(now[i] - (w[i] + (k[i] - w[i]) * c / 100f)) <= 0.5f)
            }
            assertTrue("$c%: no bluer", now[2] - now[0] <= w[2] - w[0])
            last = now
        }
        // Charcoal is the neutral end: much less blue than the background.
        assertTrue(k[2] - k[0] < (w[2] - w[0]) / 2f)
        // It's only the tint's colour: with no tint it shows nothing.
        assertEquals(GlassStyle.CLEAR, GlassStyle.Shade(0, charcoal = 100).color)
    }

    @Test fun theDefaults() {
        // Rail, cards & header: exactly the glass before it took the wallpaper's colour, charcoal at 0x94.
        assertEquals(GlassStyle.Shade(58, charcoal = 100), GlassStyle.RAIL_SHADE)
        assertEquals(0x940C0E12.toInt(), GlassStyle.RAIL_SHADE.color)
        // App glass: the background's own colour at 58%, then darkened by 15%, laid as one colour.
        assertEquals(GlassStyle.Shade(58, dim = 15), GlassStyle.APP_SHADE)
        assertEquals(0, GlassStyle.APP_SHADE.charcoal)
        assertEquals(0xA40C1318.toInt(), GlassStyle.APP_SHADE.color)
        // That's the windows' look before (the wallpaper's colour at 0x94) with 15% of its light taken.
        val before = (0x94 shl 24) or GlassStyle.WALLPAPER
        for (b in intArrayOf(0, 64, 128, 200, 255)) {
            val was = shows(before, b)
            assertShows("over $b", FloatArray(3) { was[it] * 0.85f }, shows(GlassStyle.APP_SHADE.color, b))
        }
        // Playback glass: no tint and no darkening over playback, as before.
        assertEquals(GlassStyle.Shade(0, 0), GlassStyle.PLAYBACK_SHADE)
        assertEquals(0, GlassStyle.PLAYBACK_SHADE.color ushr 24)
    }

    @Test fun overTheWallpaperTheCardsGlassIsItsOwnColourSolid() {
        // The banner's backing is the card's glass over the wallpaper, so the two meet without a line.
        for (tint in 0..100 step 10) for (charcoal in 0..100 step 10) {
            val shade = GlassStyle.Shade(tint, charcoal = charcoal)
            val a = (shade.color ushr 24) / 255f
            val top = channels(shade.color)
            val w = channels(GlassStyle.WALLPAPER)
            assertShows("$tint% $charcoal%", FloatArray(3) { top[it] * a + w[it] * (1f - a) }, channels(shade.solid))
            assertEquals(0xFF, shade.solid ushr 24)
        }
        // With the background's own colour the tint over the wallpaper is the wallpaper.
        for (tint in 0..100 step 25) assertEquals(GlassStyle.WINDOW_SOLID, GlassStyle.Shade(tint, charcoal = 0).solid)
    }

    @Test fun tintDimnessAndCharcoalStepByFivePercentFromNoneToAll() {
        assertEquals(5, GlassStyle.SHADE_STEP)
        assertEquals(35, GlassStyle.stepShade(30, 1))
        assertEquals(25, GlassStyle.stepShade(30, -1))
        assertEquals(100, GlassStyle.stepShade(100, 1))
        assertEquals(0, GlassStyle.stepShade(0, -1))
        assertEquals(10, GlassStyle.stepShade(5, 3))
        assertEquals(5, GlassStyle.stepShade(5, 0))
        // A default between two steps (58%): off it onto the steps either way, and back onto it from either side.
        assertEquals(60, GlassStyle.stepShade(58, 1, stop = 58))
        assertEquals(55, GlassStyle.stepShade(58, -1, stop = 58))
        assertEquals(58, GlassStyle.stepShade(55, 1, stop = 58))
        assertEquals(58, GlassStyle.stepShade(60, -1, stop = 58))
        assertEquals(65, GlassStyle.stepShade(60, 1, stop = 58))
        // A stop on a step is just that step; one out of range is none.
        assertEquals(35, GlassStyle.stepShade(30, 1, stop = 35))
        assertEquals(100, GlassStyle.stepShade(95, 1, stop = 140))
        // All the way up from none and back: every step once, the default on the way both times.
        val up = generateSequence(0) { if (it == 100) null else GlassStyle.stepShade(it, 1, stop = 58) }.toList()
        assertEquals((0..100 step 5).toList().plus(58).sorted(), up)
        val down = generateSequence(100) { if (it == 0) null else GlassStyle.stepShade(it, -1, stop = 58) }.toList()
        assertEquals(up.reversed(), down)
        assertEquals(0, GlassStyle.clampShade(-20))
        assertEquals(100, GlassStyle.clampShade(140))
        // Out of range is read as the nearest end.
        assertEquals(GlassStyle.Shade(100, 0).color, GlassStyle.shadeColor(150, -10))
        assertEquals(GlassStyle.CHARCOAL, GlassStyle.charcoalColor(300))
        assertEquals(GlassStyle.WALLPAPER, GlassStyle.charcoalColor(-5))
    }

    @Test fun eachGlassTakesItsOwnShade() {
        val app = Glass.appShade
        val rail = Glass.railShade
        val playback = Glass.playbackShade
        try {
            assertFalse(Glass.useShades(app, rail, playback))
            val changes = Glass.shadeChanges
            val a = GlassStyle.Shade(30, dim = 20)
            val r = GlassStyle.Shade(70, charcoal = 50)
            val p = GlassStyle.Shade(10, dim = 0)
            assertTrue(Glass.useShades(a, r, p))
            assertEquals(changes + 1, Glass.shadeChanges)
            assertEquals(a, Glass.appShade)
            assertEquals(r, Glass.railShade)
            assertEquals(p, Glass.playbackShade)
            // App glass: windows over the page (the settings window, hold windows, pickers, notices).
            assertEquals(a.color, GlassBakes.tintOf(Glass.Kind.PANEL, GlassBakes.PANE))
            assertEquals(a.color, GlassBakes.tintOf(Glass.Kind.POPUP, GlassBakes.PANE))
            assertEquals(a.color, GlassBakes.tintOf(Glass.Kind.DIALOG_POPUP, GlassBakes.PANE))
            // Rail, cards & header: a window-shaped surface in the page itself (Recent channels, the banner's card).
            assertEquals(r.color, GlassBakes.tintOf(Glass.Kind.PANEL, GlassBakes.CALM))
            // Playback glass: windows over full-screen films and Live TV.
            assertEquals(p.color, GlassBakes.tintOf(Glass.Kind.PANEL, GlassBakes.FILM))
            assertEquals(p.color, GlassBakes.tintOf(Glass.Kind.DIALOG_POPUP, GlassBakes.FILM))
            // Those windows have Playback glass's layer baked in; the player's other glass (buttons, chips, rows) is
            // darkened by its dimness as it's drawn ([Glass.dim]): none here.
            assertTrue(GlassBakes.playbackPane(Glass.Kind.PANEL, GlassBakes.FILM))
            assertTrue(GlassBakes.playbackPane(Glass.Kind.POPUP, GlassBakes.FILM))
            assertFalse(GlassBakes.playbackPane(Glass.Kind.PANEL, GlassBakes.PANE))
            assertFalse(GlassBakes.playbackPane(Glass.Kind.PILL, GlassBakes.FILM))
            assertFalse(GlassBakes.playbackPane(Glass.Kind.ROW, GlassBakes.FILM))
            assertEquals(0f, Glass.dim, 0f)
            // The banner's backing and shading follow the card's glass (Classic keeps its own).
            val look = Glass.on
            try {
                Glass.on = true
                assertEquals(r.solid, Glass.windowSolid)
                Glass.on = false
                assertEquals(GlassStyle.WINDOW_SOLID, Glass.windowSolid)
            } finally {
                Glass.on = look
            }
            // Nothing else has a layer of the user's: controls, rows (and so the focus highlight) and outlines.
            assertEquals(0, GlassBakes.tintOf(Glass.Kind.PILL, GlassBakes.CALM))
            assertEquals(0, GlassBakes.tintOf(Glass.Kind.ROW, GlassBakes.FILM))
            assertEquals(0, GlassBakes.tintOf(Glass.Kind.ROW, GlassBakes.PANE))
            assertEquals(0, GlassBakes.tintOf(Glass.Kind.PANEL, GlassBakes.EDGE))
            // The same shades again change nothing.
            assertFalse(Glass.useShades(a, r, p))
            assertEquals(changes + 1, Glass.shadeChanges)
            // A dimness alone is a change too: Playback glass darkened with no tint.
            val dark = GlassStyle.Shade(0, dim = 25)
            assertTrue(Glass.useShades(a, r, dark))
            assertEquals(dark.color, GlassBakes.tintOf(Glass.Kind.PANEL, GlassBakes.FILM))
            assertNotEquals(GlassStyle.CLEAR, GlassBakes.tintOf(Glass.Kind.PANEL, GlassBakes.FILM))
            assertEquals(0.25f, Glass.dim, 0.0001f)
        } finally {
            Glass.useShades(app, rail, playback)
        }
    }

    // ---- A window's pane, by what it's over, and its cards -------------------------------------------------------------

    @Test fun overPlaybackAWindowAddsNoTintAndNoDarkByDefault() {
        val over = GlassStyle.Over.PLAYBACK
        // Nothing laid over the frosted picture: no colour, no dark, no light; the screen dimmer alone darkens it.
        assertEquals(0, GlassStyle.paneTint(over, GlassStyle.APP_SHADE, GlassStyle.PLAYBACK_SHADE) ushr 24)
        assertEquals(0, GlassStyle.paneSheen(over) ushr 24)
        // The film's window picture keeps the film's own light.
        assertEquals(1f, GlassStyle.PLAYBACK_PANE_LIGHT, 0f)
        // Until Playback glass is given a tint or a dimness: then that's what it lays over the picture.
        val set = GlassStyle.Shade(20, 10)
        assertEquals(set.color, GlassStyle.paneTint(over, GlassStyle.APP_SHADE, set))
    }

    @Test fun onPagesAWindowHasTheAppGlass() {
        val over = GlassStyle.Over.PAGE
        assertEquals(GlassStyle.APP_SHADE.color, GlassStyle.paneTint(over, GlassStyle.APP_SHADE, GlassStyle.PLAYBACK_SHADE))
        val set = GlassStyle.Shade(40, 0)
        assertEquals(set.color, GlassStyle.paneTint(over, set, GlassStyle.PLAYBACK_SHADE))
        assertEquals(GlassStyle.SHEEN, GlassStyle.paneSheen(over))
    }

    @Test fun theFilmStylesWindowsArePanesOverPlaybackAndEveryOtherOverThePage() {
        assertEquals(GlassStyle.Over.PLAYBACK, GlassBakes.over(GlassBakes.FILM))
        assertEquals(GlassStyle.Over.PAGE, GlassBakes.over(GlassBakes.PANE))
        assertEquals(
            listOf(Glass.Kind.PANEL, Glass.Kind.POPUP, Glass.Kind.DIALOG_POPUP),
            Glass.Kind.entries.filter { GlassBakes.isWindow(it) },
        )
        // Its own drawing, kept apart from the other looks' in the drawings' memory.
        assertEquals(4, setOf(GlassBakes.FILM, GlassBakes.CALM, GlassBakes.EDGE, GlassBakes.PANE).size)
    }

    @Test fun aPanesRimIsBrightOnTopFaintDownTheSidesAndSofterAtTheFoot() {
        // rgba(255,255,255,.42): the glass reference's bright upper edge.
        assertEquals(0x6BFFFFFF, GlassStyle.RIM_TOP)
        val top = GlassStyle.RIM_TOP ushr 24
        val side = GlassStyle.RIM_SIDE ushr 24
        val foot = GlassStyle.RIM_FOOT ushr 24
        assertTrue("top $top, foot $foot, side $side", top > foot && foot > side)
        // White light only: the rim takes no colour of its own.
        for (c in intArrayOf(GlassStyle.RIM_TOP, GlassStyle.RIM_SIDE, GlassStyle.RIM_FOOT, GlassStyle.RIM_GLOW)) {
            assertEquals(0xFFFFFF, c and 0xFFFFFF)
        }
    }

    @Test fun aCardsRowsKnowWhereTheyAre() {
        assertEquals(RowGroups.Pos.SINGLE, RowGroups.posOf(0, 1))
        assertEquals(RowGroups.Pos.FIRST, RowGroups.posOf(0, 3))
        assertEquals(RowGroups.Pos.MIDDLE, RowGroups.posOf(1, 3))
        assertEquals(RowGroups.Pos.LAST, RowGroups.posOf(2, 3))
        assertEquals(RowGroups.Pos.FIRST, RowGroups.posOf(0, 2))
        assertEquals(RowGroups.Pos.LAST, RowGroups.posOf(1, 2))
    }

    // ---- The blur, with the caller's arrays -----------------------------------------------------------------------------

    /** A box blur worked out the plain way (sums divided by the window), to compare against. */
    private fun plainPass(src: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean): IntArray {
        val out = IntArray(src.size)
        val window = 2 * r + 1
        for (y in 0 until h) for (x in 0 until w) {
            var sr = 0; var sg = 0; var sb = 0
            for (d in -r..r) {
                val xx = if (horizontal) (x + d).coerceIn(0, w - 1) else x
                val yy = if (horizontal) y else (y + d).coerceIn(0, h - 1)
                val c = src[yy * w + xx]
                sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
            }
            out[y * w + x] = rgb(sr / window, sg / window, sb / window)
        }
        return out
    }

    @Test fun theQuickDivisionGivesExactlyThePlainBlur() {
        val w = 23
        val h = 11
        var seed = 12345
        val px = IntArray(w * h) {
            seed = seed * 1103515245 + 12345
            rgb((seed ushr 16) and 0xFF, (seed ushr 8) and 0xFF, seed and 0xFF)
        }
        for (radius in intArrayOf(1, 4, 5, 9)) {
            var expected = px.copyOf()
            repeat(3) {
                expected = plainPass(expected, w, h, radius, horizontal = true)
                expected = plainPass(expected, w, h, radius, horizontal = false)
            }
            val a = px.copyOf()
            StillGlass.boxBlur3InPlace(a, IntArray(a.size), w, h, radius)
            assertArrayEquals("radius $radius", expected, a)
            // And the copying form is the same blur.
            assertArrayEquals(expected, StillGlass.boxBlur3(px, w, h, radius, 1f))
        }
    }

    @Test fun whiteStaysWhite() {
        // The largest sums there are: nothing overflows or rounds down a level.
        val px = IntArray(16 * 9) { rgb(255, 255, 255) }
        StillGlass.boxBlur3InPlace(px, IntArray(px.size), 16, 9, 7)
        assertTrue(px.all { it == rgb(255, 255, 255) })
    }
}
