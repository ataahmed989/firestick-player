package app.fireplayer.live.ui

import app.fireplayer.live.ChannelGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldPopupTest {

    // ---- OK pressed or held (PressHold) -------------------------------------------------------------------------

    @Test fun aShortPressPicksOnRelease() {
        val p = PressHold(500)
        assertFalse(p.down(0, false, 0))
        assertTrue(p.up(false))
        // Once: a second release with no press is nothing.
        assertFalse(p.up(false))
    }

    @Test fun holdingOpensOnceAndTheReleaseDoesNothing() {
        val p = PressHold(500)
        assertFalse(p.down(0, false, 0))
        // The first repeat comes flagged as a long press.
        assertTrue(p.down(1, true, 500))
        // Later repeats while still held: nothing more.
        assertFalse(p.down(2, false, 550))
        assertFalse(p.down(3, false, 600))
        assertFalse(p.up(false))
    }

    @Test fun aRepeatPastTheLongPressTimeHoldsEvenUnflagged() {
        val p = PressHold(500)
        p.down(0, false, 0)
        assertFalse(p.down(1, false, 120))
        assertTrue(p.down(2, false, 520))
        assertFalse(p.up(false))
    }

    @Test fun anEarlyRepeatStillEndsAsAShortPress() {
        val p = PressHold(500)
        p.down(0, false, 0)
        assertFalse(p.down(1, false, 200))
        assertTrue(p.up(false))
    }

    @Test fun aPressThatStartedElsewhereIsNotOurs() {
        val p = PressHold(500)
        // Focus came here while OK was held: its repeats and release do nothing.
        assertFalse(p.down(4, true, 700))
        assertFalse(p.down(5, false, 750))
        assertFalse(p.up(false))
    }

    @Test fun focusLeavingOrAnotherKeyCancelsThePick() {
        val p = PressHold(500)
        p.down(0, false, 0)
        p.cancel()
        assertFalse(p.down(1, true, 500))
        assertFalse(p.up(false))
    }

    @Test fun aCanceledReleaseDoesNotPick() {
        val p = PressHold(500)
        p.down(0, false, 0)
        assertFalse(p.up(true))
    }

    @Test fun aNewPressAfterAHoldStartsAfresh() {
        val p = PressHold(500)
        p.down(0, false, 0)
        assertTrue(p.down(1, true, 500))
        // The release went to the small window; the next press here is a normal one.
        assertFalse(p.down(0, false, 0))
        assertTrue(p.up(false))
    }

    // ---- Where the hold window goes (HoldPlace) ----------------------------------------------------------------------

    @Test fun itSitsBesideTheItemToItsRightCentredOnIt() {
        // A guide row's name (40..320): the window starts 8 past its right edge, centred on it.
        assertEquals(328f, HoldPlace.x(anchorLeft = 40f, anchorRight = 320f, width = 180f, gap = 8f, left = 0f, right = 960f), 0f)
        assertEquals(250f, HoldPlace.y(anchorCenterY = 300f, height = 100f, top = 0f, bottom = 540f), 0f)
        assertEquals(1, HoldPlace.side(x = 328f, width = 180f, anchorLeft = 40f, anchorRight = 320f))
        // Exactly touching the area's right edge still fits on the right.
        assertEquals(328f, HoldPlace.x(anchorLeft = 40f, anchorRight = 320f, width = 620f, gap = 8f, left = 0f, right = 948f), 0f)
    }

    @Test fun nearTheRightEdgeItFlipsToTheItemsLeft() {
        // A poster at 760..900: no room on its right (908 + 180 > 948), so 8 left of it.
        assertEquals(572f, HoldPlace.x(anchorLeft = 760f, anchorRight = 900f, width = 180f, gap = 8f, left = 12f, right = 948f), 0f)
        assertEquals(-1, HoldPlace.side(x = 572f, width = 180f, anchorLeft = 760f, anchorRight = 900f))
    }

    @Test fun withNoRoomOnEitherSideItSitsOverTheItemOnScreen() {
        // A history row as wide as the page: over its right end, inside the area.
        val x = HoldPlace.x(anchorLeft = 20f, anchorRight = 940f, width = 180f, gap = 8f, left = 12f, right = 948f)
        assertEquals(768f, x, 0f)
        assertEquals(0, HoldPlace.side(x, width = 180f, anchorLeft = 20f, anchorRight = 940f))
        // Never past the left edge, even when wider than the area.
        assertEquals(12f, HoldPlace.x(anchorLeft = 600f, anchorRight = 700f, width = 2000f, gap = 8f, left = 12f, right = 948f), 0f)
    }

    @Test fun itStaysInsideTheAreaTopAndBottom() {
        // A row at the top of the guide: the window starts at the guide's top.
        assertEquals(230f, HoldPlace.y(anchorCenterY = 240f, height = 100f, top = 230f, bottom = 530f), 0f)
        // A row at the bottom: it ends at the guide's bottom.
        assertEquals(430f, HoldPlace.y(anchorCenterY = 520f, height = 100f, top = 230f, bottom = 530f), 0f)
        // Taller than the area: from its top.
        assertEquals(230f, HoldPlace.y(anchorCenterY = 400f, height = 400f, top = 230f, bottom = 530f), 0f)
    }

    // ---- How big it is (HoldSize) ----------------------------------------------------------------------------------

    @Test fun itIsAsWideAsItsWidestLineAndItsRoom() {
        // "Mark watched up to here" 160 wide, 12 of room: 172, no empty room beside the words.
        assertEquals(172f, HoldSize.width(content = 160f, padding = 12f, min = 150f, max = 240f), 0f)
    }

    @Test fun aShortListIsStillAColumnAndALongTitleWraps() {
        // "Remove" alone: the narrowest window.
        assertEquals(150f, HoldSize.width(content = 60f, padding = 12f, min = 150f, max = 240f), 0f)
        // A long channel name: the widest, its title on two lines.
        assertEquals(240f, HoldSize.width(content = 400f, padding = 12f, min = 150f, max = 240f), 0f)
    }

    @Test fun aNarrowAreaWinsOverTheNarrowest() {
        assertEquals(120f, HoldSize.width(content = 60f, padding = 12f, min = 150f, max = 120f), 0f)
        assertEquals(0f, HoldSize.width(content = 60f, padding = 12f, min = 150f, max = -5f), 0f)
    }

    // ---- Move to top and Hide group (GroupEdits) -----------------------------------------------------------------

    private fun group(id: String) = ChannelGroup("p", id, id.uppercase(), 0)

    @Test fun moveToTopPutsTheGroupFirstAndKeepsTheOthersInOrder() {
        val groups = listOf(group("a"), group("b"), group("c"), group("d"))
        assertEquals(listOf("c", "a", "b", "d"), GroupEdits.toTop(groups, "c") { it.id }.map { it.id })
        assertEquals(listOf("d", "a", "b", "c"), GroupEdits.toTop(listOf("a", "b", "c", "d"), "d"))
    }

    @Test fun moveToTopLeavesTheListAsItIsWhenFirstOrAbsent() {
        val ids = listOf("a", "b", "c")
        assertSame(ids, GroupEdits.toTop(ids, "a"))
        assertSame(ids, GroupEdits.toTop(ids, "x"))
        assertTrue(GroupEdits.toTop(emptyList(), "a").isEmpty())
    }

    @Test fun afterHidingTheNextGroupTakesItsPlace() {
        assertEquals("c", GroupEdits.afterHide(listOf("a", "b", "c"), "b"))
        assertEquals("b", GroupEdits.afterHide(listOf("a", "b", "c"), "a"))
    }

    @Test fun afterHidingTheLastGroupTheOneAboveIsSelected() {
        assertEquals("b", GroupEdits.afterHide(listOf("a", "b", "c"), "c"))
    }

    @Test fun afterHidingTheOnlyGroupThereIsNoneLeft() {
        // The column then selects the playlist's All channels, the row above.
        assertNull(GroupEdits.afterHide(listOf("a"), "a"))
        assertNull(GroupEdits.afterHide(listOf("a", "b"), "x"))
    }
}
