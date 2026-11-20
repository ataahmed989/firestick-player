package app.fireplayer.live.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the guide's focus lands when its list is shown again. */
class GuideFocusTest {

    @Test fun theWantedChannelWins() {
        assertEquals(7, GuideFocus.rowAfter(found = 7, oldRow = 3, size = 10, reload = true))
        assertEquals(7, GuideFocus.rowAfter(found = 7, oldRow = 3, size = 10, reload = false))
    }

    @Test fun aReloadWithoutTheChannelKeepsTheRow() {
        assertEquals(3, GuideFocus.rowAfter(found = -1, oldRow = 3, size = 10, reload = true))
        // The list got shorter: the last row.
        assertEquals(4, GuideFocus.rowAfter(found = -1, oldRow = 9, size = 5, reload = true))
    }

    @Test fun anotherListStartsAtTheTop() {
        assertEquals(0, GuideFocus.rowAfter(found = -1, oldRow = 3, size = 10, reload = false))
        assertEquals(0, GuideFocus.rowAfter(found = -1, oldRow = 3, size = 0, reload = true))
    }
}
