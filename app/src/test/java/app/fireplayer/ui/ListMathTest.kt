package app.fireplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ListMathTest {

    // Manage groups' shape: two rows, a heading, then rows (the heading's index is 2).
    private val heading = 2
    private val size = 8
    private val isRow: (Int) -> Boolean = { it != heading }

    @Test fun rowsBetweenHeadingsAreOneCard() {
        assertEquals(RowGroups.Pos.SINGLE, ListMath.cardPos(rowBefore = false, rowAfter = false))
        assertEquals(RowGroups.Pos.FIRST, ListMath.cardPos(rowBefore = false, rowAfter = true))
        assertEquals(RowGroups.Pos.MIDDLE, ListMath.cardPos(rowBefore = true, rowAfter = true))
        assertEquals(RowGroups.Pos.LAST, ListMath.cardPos(rowBefore = true, rowAfter = false))
    }

    @Test fun upAndDownPassHeadings() {
        assertEquals(3, ListMath.step(1, size, down = true, held = false, isRow = isRow))
        assertEquals(1, ListMath.step(3, size, down = false, held = false, isRow = isRow))
        assertEquals(5, ListMath.step(4, size, down = true, held = false, isRow = isRow))
    }

    @Test fun theEndsGoRoundUnlessTheKeyIsHeld() {
        assertEquals(0, ListMath.step(7, size, down = true, held = false, isRow = isRow))
        assertEquals(7, ListMath.step(0, size, down = false, held = false, isRow = isRow))
        assertEquals(-1, ListMath.step(7, size, down = true, held = true, isRow = isRow))
        assertEquals(-1, ListMath.step(0, size, down = false, held = true, isRow = isRow))
    }

    @Test fun aNoteAtTheTopIsPassedWhenGoingRound() {
        // Reorder's shape: a note, then the entries.
        val reorder: (Int) -> Boolean = { it != 0 }
        assertEquals(1, ListMath.step(4, 5, down = true, held = false, isRow = reorder))
        assertEquals(4, ListMath.step(1, 5, down = false, held = false, isRow = reorder))
    }

    @Test fun listsOfRowsOnlyGoRoundToo() {
        // The tab rail (Live TV, Settings), the groups column and the guide's channels: every entry is a row.
        val all: (Int) -> Boolean = { true }
        assertEquals(0, ListMath.step(1, 2, down = true, held = false, isRow = all))
        assertEquals(1, ListMath.step(0, 2, down = false, held = false, isRow = all))
        assertEquals(1, ListMath.step(0, 2, down = true, held = false, isRow = all))
        assertEquals(0, ListMath.step(299, 300, down = true, held = false, isRow = all))
        assertEquals(299, ListMath.step(0, 300, down = false, held = false, isRow = all))
        // Held, the ends stop it; in between it moves on as usual.
        assertEquals(-1, ListMath.step(299, 300, down = true, held = true, isRow = all))
        assertEquals(-1, ListMath.step(0, 300, down = false, held = true, isRow = all))
        assertEquals(151, ListMath.step(150, 300, down = true, held = true, isRow = all))
        assertEquals(149, ListMath.step(150, 300, down = false, held = true, isRow = all))
    }

    @Test fun aSingleRowStaysPut() {
        assertEquals(-1, ListMath.step(0, 1, down = true, held = false) { true })
        assertEquals(-1, ListMath.step(1, 2, down = false, held = false) { it == 1 })
    }

    @Test fun focusStartsOnTheNearestRow() {
        assertEquals(3, ListMath.rowNear(2, size, isRow))
        assertEquals(0, ListMath.rowNear(0, size, isRow))
        // Past the end (a list that got shorter): the last row.
        assertEquals(7, ListMath.rowNear(40, size, isRow))
        // Nothing but headings and notes before the end: the row before.
        assertEquals(1, ListMath.rowNear(2, 3, isRow))
        assertEquals(-1, ListMath.rowNear(0, 0, isRow))
        assertEquals(-1, ListMath.rowNear(0, 2) { false })
    }

    @Test fun theSelectedRowGoesToTheMiddle() {
        // A 36 px row 500 px down a 600 px list: its middle (518) moves to 300.
        assertEquals(218, ListMath.centerBy(500, 536, 600, roomAbove = null, roomBelow = null))
        assertEquals(-200, ListMath.centerBy(82, 118, 600, roomAbove = null, roomBelow = null))
        assertEquals(0, ListMath.centerBy(282, 318, 600, roomAbove = 10, roomBelow = 10))
    }

    @Test fun noFurtherThanTheListGoes() {
        // Near the end: only as far as the last row allows.
        assertEquals(40, ListMath.centerBy(500, 536, 600, roomAbove = null, roomBelow = 40))
        // Near the top: only back to the first row.
        assertEquals(-12, ListMath.centerBy(82, 118, 600, roomAbove = 12, roomBelow = null))
        // A list shorter than the window doesn't move at all.
        assertEquals(0, ListMath.centerBy(500, 536, 600, roomAbove = 0, roomBelow = -100))
        assertEquals(0, ListMath.centerBy(82, 118, 600, roomAbove = -3, roomBelow = 0))
    }
}
