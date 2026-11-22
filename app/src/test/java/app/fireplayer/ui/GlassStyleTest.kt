package app.fireplayer.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        for (tint in intArrayOf(GlassStyle.WINDOW_TINT, GlassStyle.CONTROL_TINT)) {
            val a = tint ushr 24
            val r = (tint shr 16) and 0xFF
            val b = tint and 0xFF
            // What's behind shows through, and the glass is dark enough for white words on it.
            assertTrue("alpha $a", a in 0x80..0xD0)
            // Close to neutral: the colour comes from what's behind, not from a blue wash.
            assertTrue("blue $b over red $r", b - r <= 12)
        }
    }

    // ---- A window's header band and its cards -----------------------------------------------------------------------------

    @Test fun theHeaderBandIsACoolBlueTintFadingDownOverTheWindowsGlass() {
        val top = GlassStyle.HEADER_TINT_TOP
        val bottom = GlassStyle.HEADER_TINT_BOTTOM
        for (tint in intArrayOf(top, bottom)) {
            val a = tint ushr 24
            val r = (tint shr 16) and 0xFF
            val b = tint and 0xFF
            // Blue, and only a tint: the window's dark glass and what's behind it still show through.
            assertTrue("blue $b over red $r", b - r >= 0x60)
            assertTrue("alpha $a", a in 0x10..0x60)
        }
        // Strongest at the top, fading towards the band's foot; one colour.
        assertTrue((top ushr 24) > (bottom ushr 24))
        assertEquals(top and 0xFFFFFF, bottom and 0xFFFFFF)
    }

    @Test fun theHeaderBandsUpperEdgeIsTheGlassReferencesBrightEdge() {
        // rgba(255,255,255,.42): white at 0x6B.
        assertEquals(0x6BFFFFFF, GlassStyle.HEADER_EDGE)
        // Brighter than a window's own top highlight, so it reads as the reflective edge.
        assertTrue((GlassStyle.HEADER_EDGE ushr 24) > (GlassStyle.HIGHLIGHT ushr 24))
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
