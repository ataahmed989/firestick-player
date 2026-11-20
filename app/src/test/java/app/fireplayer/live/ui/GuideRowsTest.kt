package app.fireplayer.live.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guide's rows: how many whole ones show, where the rows scroll as focus moves, and long moves (going round). */
class GuideRowsTest {

    // At the Fire TV's density (2): the guide's 29 dp header and 35 dp rows (below).
    private val header = 58f
    private val row = 70f

    @Test fun eightWholeRowsFillTheGuideUnderTheMiniature() {
        // The guide from under the miniature to the screen's bottom edge: 540 - 14 - 217 = 309 dp, 618 px.
        val height = 618f
        val minHeader = 56f // 28 dp
        val rowH = GuideCells.rowHeight(height, minHeader, 8)
        val headerH = GuideCells.headerHeight(height, minHeader, 8, rowH)
        // (309 - 28) / 8 = 35.1 dp: 70 px rows, the header taking the 6 px they leave (29 dp).
        assertEquals(row, rowH, 0f)
        assertEquals(header, headerH, 0f)
        // Exactly eight whole rows, the last ending on the screen's bottom edge: none cut there.
        assertEquals(8, GuideCells.wholeRows(height, headerH, rowH))
        assertEquals(height, headerH + 8 * rowH, 0f)
    }

    @Test fun anyHeightFitsItsRowsExactlyInWholePixels() {
        for (h in 200..1200) {
            val height = h.toFloat()
            for (rows in listOf(1, 8, 10)) {
                val rowH = GuideCells.rowHeight(height, 56f, rows)
                val headerH = GuideCells.headerHeight(height, 56f, rows, rowH)
                assertEquals("h $h rows $rows", rowH, Math.floor(rowH.toDouble()).toFloat(), 0f)
                assertTrue("h $h rows $rows", headerH >= 56f && headerH < 56f + rows)
                assertEquals("h $h rows $rows", height, headerH + rows * rowH, 0f)
                assertEquals("h $h rows $rows", rows, GuideCells.wholeRows(height, headerH, rowH))
            }
        }
    }

    @Test fun aGuideTooShortForItsRowsStillHasRowsOfAPixel() {
        val rowH = GuideCells.rowHeight(20f, 56f, 8)
        assertEquals(1f, rowH, 0f)
        assertEquals(56f, GuideCells.headerHeight(20f, 56f, 8, rowH), 0f)
        assertEquals(1f, GuideCells.rowHeight(0f, 56f, 0), 0f)
    }

    @Test fun aShortGuideStillHasARow() {
        assertEquals(1, GuideCells.wholeRows(60f, header, row))
        assertEquals(1, GuideCells.wholeRows(0f, header, row))
    }

    @Test fun movingDownKeepsTheFocusOnWholeRowsWithOneRowOfRoom() {
        val visible = 8
        val count = 40
        var top = 0
        for (r in 0 until count) {
            top = GuideCells.topFor(r, top, visible, count)
            // Always one of the rows on screen (never the next one down, index top + visible).
            assertTrue("row $r top $top", r in top until top + visible)
            // A row of room under it, except at the end of the list.
            if (top < count - visible) assertTrue("row $r top $top", r <= top + visible - 2)
        }
        assertEquals(count - visible, top)
    }

    @Test fun movingUpKeepsTheFocusOnWholeRowsWithOneRowOfRoom() {
        val visible = 8
        val count = 40
        var top = count - visible
        for (r in count - 1 downTo 0) {
            top = GuideCells.topFor(r, top, visible, count)
            assertTrue("row $r top $top", r in top until top + visible)
            if (top > 0) assertTrue("row $r top $top", r >= top + 1)
        }
        assertEquals(0, top)
    }

    @Test fun goingRoundShowsTheOtherEnd() {
        // Down from the last channel to the first, and Up from the first to the last.
        assertEquals(0, GuideCells.topFor(0, 32, 8, 40))
        assertEquals(32, GuideCells.topFor(39, 0, 8, 40))
        // A list shorter than the screen never scrolls.
        assertEquals(0, GuideCells.topFor(5, 0, 8, 6))
        assertEquals(0, GuideCells.topFor(0, 0, 8, 6))
    }

    @Test fun tooFewRowsForRoomStillShowTheFocus() {
        for (visible in 1..2) {
            var top = 0
            for (r in 0 until 10) {
                top = GuideCells.topFor(r, top, visible, 10)
                assertTrue("visible $visible row $r top $top", r in top until top + visible)
            }
        }
    }

    @Test fun aLongMoveJumpsAndGlidesTheLastFewRows() {
        val screen = 8 * row
        val glide = 3 * row
        // Round from the last of 300 channels to the first: the rows start 3 rows short of the top.
        assertEquals(glide, GuideCells.scrollStart(291 * row, 0f, screen, glide), 0.01f)
        // And from the first to the last.
        assertEquals(291 * row - glide, GuideCells.scrollStart(0f, 291 * row, screen, glide), 0.01f)
        // A move within a screen (a row, a page) scrolls from where the rows are.
        assertEquals(10 * row, GuideCells.scrollStart(10 * row, 11 * row, screen, glide), 0.01f)
        assertEquals(10 * row, GuideCells.scrollStart(10 * row, 3 * row, screen, glide), 0.01f)
        // Further than the eight rows on screen is a long move.
        assertEquals(1 * row + glide, GuideCells.scrollStart(10 * row, 1 * row, screen, glide), 0.01f)
    }

    @Test fun theHighlightKeepsItsPlaceOnScreenThroughTheJump() {
        val g = FocusGlide()
        // Drawn on the last row of a long list (content y), the rows scrolled to 291 rows.
        val scroll = 291 * row
        g.begin(100f, header + 299 * row, 300f, header + 300 * row)
        val onScreen = header + 299 * row - scroll
        // The rows jump to 3 rows from the top: the glide's start moves with them.
        val jumped = 3 * row
        g.shiftY(jumped - scroll)
        g.fraction = 0f
        g.update(100f, header, 300f, header + row)
        assertEquals(onScreen, g.top - jumped, 0.01f)
        // At the end it's on the first row.
        g.fraction = 1f
        g.update(100f, header, 300f, header + row)
        assertEquals(header, g.top, 0.01f)
    }
}
