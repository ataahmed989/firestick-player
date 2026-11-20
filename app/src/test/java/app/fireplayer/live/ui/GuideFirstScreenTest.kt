package app.fireplayer.live.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A long list's first screenful, shown while the whole list is read: when the rest arrives nothing moves on screen,
 * and until then focus doesn't go round the few rows' ends.
 */
class GuideFirstScreenTest {

    @Test fun theFocusedRowStaysOnItsRowOfTheScreenWhenTheWholeListArrives() {
        // The first screenful: 16 rows before the focused channel, which shows on the screen's third row (top 14).
        // In the whole list that channel is row 12,345: the first row shown becomes 12,343.
        val top = GuideCells.topKeeping(oldTop = 14, oldFocus = 16, newFocus = 12_345, visible = 8, count = 30_000)
        assertEquals(12_343, top)
        // The same distance from the top of the screen as before.
        assertEquals(16 - 14, 12_345 - top)
    }

    @Test fun aListThatIsItsOwnFirstScreenfulKeepsItsPlace() {
        // Nothing before the first rows, and the whole list holds the same rows: nothing changes.
        assertEquals(0, GuideCells.topKeeping(oldTop = 0, oldFocus = 0, newFocus = 0, visible = 8, count = 24))
        assertEquals(5, GuideCells.topKeeping(oldTop = 5, oldFocus = 9, newFocus = 9, visible = 8, count = 40))
    }

    @Test fun theRowsNeverScrollPastEitherEndOfTheWholeList() {
        // The focused channel turned out to be near the list's end: the last screenful shows, focus within it.
        assertEquals(92, GuideCells.topKeeping(oldTop = 16, oldFocus = 16, newFocus = 98, visible = 8, count = 100))
        // Fewer channels than rows on screen: from the first row.
        assertEquals(0, GuideCells.topKeeping(oldTop = 2, oldFocus = 3, newFocus = 3, visible = 8, count = 5))
        // Rows above the part's focus that the whole list doesn't have before it.
        assertEquals(0, GuideCells.topKeeping(oldTop = 10, oldFocus = 16, newFocus = 2, visible = 8, count = 500))
        assertEquals(0, GuideCells.topKeeping(oldTop = 0, oldFocus = 0, newFocus = 0, visible = 8, count = 0))
    }

    @Test fun theFocusedRowIsAlwaysOnScreenAfterwards() {
        for (count in listOf(1, 5, 8, 9, 40, 1_000)) {
            for (newFocus in listOf(0, count / 2, count - 1)) {
                for (offset in 0 until 8) {
                    // The part showed its focus on screen: [offset] rows down from the first row shown.
                    val top = GuideCells.topKeeping(oldTop = 16 - offset, oldFocus = 16, newFocus = newFocus, visible = 8, count = count)
                    assertTrue("count $count focus $newFocus offset $offset top $top", newFocus in top until top + 8)
                    assertTrue(top >= 0 && top <= (count - 8).coerceAtLeast(0))
                }
            }
        }
    }

    @Test fun goingRoundIsToldFromAStepToTheRowBeside() {
        // Down from the last of 40 rows lands on the first; Up from the first on the last.
        assertTrue(GuideCells.wentRound(from = 39, to = 0, down = true))
        assertTrue(GuideCells.wentRound(from = 0, to = 39, down = false))
        // A plain step isn't.
        assertFalse(GuideCells.wentRound(from = 3, to = 4, down = true))
        assertFalse(GuideCells.wentRound(from = 4, to = 3, down = false))
        // Staying put (a one-row list) isn't either.
        assertFalse(GuideCells.wentRound(from = 0, to = 0, down = true))
        assertFalse(GuideCells.wentRound(from = 0, to = 0, down = false))
    }
}
