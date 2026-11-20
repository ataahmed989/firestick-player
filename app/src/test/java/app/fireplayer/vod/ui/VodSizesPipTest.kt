package app.fireplayer.vod.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live picture's sizes on Home and Search (DECISIONS.md of 2026-11-19). */
class VodSizesPipTest {

    @Test fun searchsPictureIs352By198InTheTopRight() {
        assertEquals(352f, VodSizes.SEARCH_PIP_W)
        assertEquals(198f, VodSizes.SEARCH_PIP_H, 0.001f)
        assertEquals(VodSizes.SEARCH_PIP_W * 9f / 16f, VodSizes.SEARCH_PIP_H, 0.001f)
        // Right-aligned at the page's right margin, beside the page's name.
        assertEquals(VodSizes.SCREEN_W - VodSizes.RIGHT_MARGIN, VodSizes.RAIL + VodSizes.searchPipX() + VodSizes.SEARCH_PIP_W, 0.001f)
        assertEquals(VodSizes.NAME_TOP, VodSizes.SEARCH_PIP_TOP)
    }

    /** A rectangle on the 960 x 540 dp screen. */
    private data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
        fun meets(o: Box) = l < o.r && o.l < r && t < o.b && o.t < b
    }

    @Test fun searchsRectanglesAt960By540NeverOverlapAndEveryPartStaysReadable() {
        val x = VodSizes.CONTENT_SCREEN_X
        val pip = Box(VodSizes.RAIL + VodSizes.searchPipX(), VodSizes.SEARCH_PIP_TOP,
            VodSizes.SCREEN_W - VodSizes.RIGHT_MARGIN, VodSizes.SEARCH_PIP_TOP + VodSizes.SEARCH_PIP_H)
        assertEquals(Box(584f, 12f, 936f, 210f), pip)
        // The pills, as wide as their words could ever be (0.7 em a letter).
        val letters = "Movies & TV Shows".length + "Live TV".length
        val pillsW = 4 * VodSizes.SEARCH_KIND_PAD_X + VodSizes.SEARCH_KIND_SPACE + letters * VodSizes.SEARCH_KIND.sp * 0.7f
        val pills = Box(x, VodSizes.VIEWPORT_TOP, x + pillsW, VodSizes.VIEWPORT_TOP + VodSizes.SEARCH_KIND_H)
        val barTop = VodSizes.VIEWPORT_TOP + VodSizes.SEARCH_KIND_H + VodSizes.SEARCH_KIND_GAP
        val bar = Box(x, barTop, x + VodSizes.searchBarW(pip = true), barTop + VodSizes.SEARCH_BAR_H)
        val trash = Box(bar.r + VodSizes.SEARCH_TRASH_GAP, barTop, bar.r + VodSizes.SEARCH_TRASH_GAP + VodSizes.SEARCH_TRASH,
            barTop + VodSizes.SEARCH_TRASH + VodSizes.SEARCH_TRASH_LABEL)
        val history = Box(x, VodSizes.searchListTop(), x + VodSizes.searchBarW(pip = true), VodSizes.SCREEN_H - VodSizes.SEARCH_LIST_BOTTOM)
        val resultsTop = VodSizes.searchResultsTop(pip = true)
        val results = Box(x, resultsTop, VodSizes.SCREEN_W, VodSizes.SCREEN_H)
        assertEquals(Box(94f, 92f, 504f, 136f), bar)
        assertEquals(226f, resultsTop, 0.001f)
        for ((name, box) in listOf("pills" to pills, "bar" to bar, "trash" to trash, "history" to history, "results" to results)) {
            assertFalse("$name meets the picture", box.meets(pip))
        }
        // The bin's name keeps its room before the picture; the history beside it; the results under it, full width.
        assertEquals(VodSizes.SEARCH_PIP_GAP, pip.l - trash.r, 0.001f)
        assertTrue(history.r < pip.l)
        assertEquals(pip.b + VodSizes.SEARCH_GAP, results.t, 0.001f)
        // Readable: the bar keeps 400 dp; a first row of posters shows whole under the picture, its chosen card grown.
        assertTrue(VodSizes.searchBarW(pip = true) >= 400f)
        val row = VodSizes.rowShownH(app.fireplayer.vod.RowStyle.POSTER, header = true, large = true)
        assertTrue("first row ends at ${results.t + row}", results.t + row <= VodSizes.SCREEN_H)
    }

    @Test fun searchWithoutThePictureIsUnchanged() {
        assertEquals(VodSizes.searchListTop(), VodSizes.searchResultsTop(pip = false), 0.001f)
        assertEquals(152f, VodSizes.searchListTop(), 0.001f)
        assertEquals(VodSizes.searchBarW(), VodSizes.searchBarW(pip = false), 0.001f)
    }

    @Test fun theBarIsShortenedSoTheBinClearsThePicture() {
        val barEnd = VodSizes.PAGE_X + VodSizes.searchBarW(pip = true) + VodSizes.SEARCH_TRASH_GAP + VodSizes.SEARCH_TRASH
        assertEquals(VodSizes.searchPipX() - VodSizes.SEARCH_PIP_GAP, barEnd, 0.001f)
        // Without the picture, the bar is as it was.
        assertEquals(VodSizes.searchBarW(), VodSizes.searchBarW(pip = false), 0.001f)
        assertTrue(VodSizes.searchBarW(pip = true) in 400f..VodSizes.searchBarW())
    }

    @Test fun onHomeTheStripSitsOverThePicturesBottomEdge() {
        // The window is the picture's shape, so there's no band: the strip lies over the picture, inside the window.
        val stripTop = VodSizes.HEADER_H - VodSizes.PIP_STRIP_INSET - VodSizes.PIP_STRIP_H
        assertTrue(stripTop > VodSizes.HEADER_H * 3f / 4f)
        val buttons = 3 * VodSizes.PIP_BUTTON + 2 * VodSizes.PIP_BUTTON_GAP
        val words = VodSizes.PIP_WINDOW_W - 2 * VodSizes.PIP_STRIP_INSET - VodSizes.PIP_STRIP_PAD_L - VodSizes.PIP_STRIP_PAD_R -
            VodSizes.PIP_WORDS_GAP - buttons
        assertTrue("room for the channel and programme on Home: $words dp", words >= 250f)
    }

    @Test fun theStripsButtonsFitItAndItsWordsHaveRoom() {
        assertTrue(VodSizes.PIP_BUTTON <= VodSizes.PIP_STRIP_H)
        assertTrue(VodSizes.PIP_ICON < VodSizes.PIP_BUTTON)
        val buttons = 3 * VodSizes.PIP_BUTTON + 2 * VodSizes.PIP_BUTTON_GAP
        val words = VodSizes.SEARCH_PIP_W - 2 * VodSizes.PIP_STRIP_INSET - VodSizes.PIP_STRIP_PAD_L - VodSizes.PIP_STRIP_PAD_R -
            VodSizes.PIP_WORDS_GAP - buttons
        assertTrue("room for the channel's name on Search: $words dp", words >= 100f)
    }
}
