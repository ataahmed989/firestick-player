package app.fireplayer.live.ui

import app.fireplayer.live.ui.MultiviewLayout.Box
import app.fireplayer.live.ui.MultiviewLayout.Dir
import app.fireplayer.live.ui.MultiviewRules.Choice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Multiview's layouts at 960 x 540 dp, the D-pad between pictures, the held picture's choices and the leave rules. */
class MultiviewRulesTest {
    private val w = LiveWidths.SCREEN.toFloat()
    private val h = LiveWidths.SCREEN_H.toFloat()
    private val gap = LiveWidths.MV_GAP

    private fun assertBox(expected: Box, actual: Box) {
        assertEquals("left", expected.left, actual.left, 0.01f)
        assertEquals("top", expected.top, actual.top, 0.01f)
        assertEquals("width", expected.width, actual.width, 0.01f)
        assertEquals("height", expected.height, actual.height, 0.01f)
    }

    /** Every picture on the screen, none overlapping, each the TV's shape, the gap kept between them and round the edge. */
    private fun assertSane(layout: MultiviewLayout.Layout) {
        val all = layout.stops
        for (b in all) {
            assertTrue(b.left >= gap - 0.01f && b.top >= gap - 0.01f && b.right <= w - gap + 0.01f && b.bottom <= h - gap + 0.01f)
            assertEquals(MultiviewLayout.ASPECT, b.width / b.height, 0.001f)
        }
        for (i in all.indices) for (j in i + 1 until all.size) {
            val a = all[i]
            val b = all[j]
            val apart = a.right + gap <= b.left + 0.01f || b.right + gap <= a.left + 0.01f ||
                a.bottom + gap <= b.top + 0.01f || b.bottom + gap <= a.top + 0.01f
            assertTrue("$a and $b overlap or touch", apart)
        }
    }

    @Test fun twoPicturesSitSideBySideInTheMiddle() {
        val l = MultiviewLayout.of(2, null, w, h, gap)
        assertNull(l.add)
        assertBox(Box(8f, 138.375f, 468f, 263.25f), l.tiles[0])
        assertBox(Box(484f, 138.375f, 468f, 263.25f), l.tiles[1])
        assertSane(l)
    }

    @Test fun threePicturesFillAGridWithTheFourthCellForAdd() {
        val l = MultiviewLayout.of(3, null, w, h, gap)
        assertEquals(3, l.tiles.size)
        assertBox(Box(17.333f, 8f, 458.667f, 258f), l.tiles[0])
        assertBox(Box(484f, 8f, 458.667f, 258f), l.tiles[1])
        assertBox(Box(17.333f, 274f, 458.667f, 258f), l.tiles[2])
        assertBox(Box(484f, 274f, 458.667f, 258f), l.add!!)
        assertSane(l)
        // No room for a fourth (the setting says 3 at most): no "Add".
        assertNull(MultiviewLayout.of(3, null, w, h, gap, most = 3).add)
    }

    @Test fun fourPicturesFillTheGrid() {
        val l = MultiviewLayout.of(4, null, w, h, gap)
        assertNull(l.add)
        assertBox(Box(484f, 274f, 458.667f, 258f), l.tiles[3])
        assertSane(l)
    }

    @Test fun theLargePictureSitsOnTopWithTheOthersSmallInARowUnderIt() {
        val l = MultiviewLayout.of(4, 1, w, h, gap)
        assertNull(l.add)
        assertBox(Box(176f, 8f, 608f, 342f), l.tiles[1])
        // The others, in their order, three across under it.
        assertBox(Box(8f, 358f, 309.333f, 174f), l.tiles[0])
        assertBox(Box(325.333f, 358f, 309.333f, 174f), l.tiles[2])
        assertBox(Box(642.667f, 358f, 309.333f, 174f), l.tiles[3])
        assertSane(l)
        // Fewer under it: the same size, centred.
        val three = MultiviewLayout.of(3, 0, w, h, gap)
        assertBox(Box(166.667f, 358f, 309.333f, 174f), three.tiles[1])
        assertBox(Box(484f, 358f, 309.333f, 174f), three.tiles[2])
        assertSane(three)
        val two = MultiviewLayout.of(2, 1, w, h, gap)
        assertBox(Box(325.333f, 358f, 309.333f, 174f), two.tiles[0])
        assertBox(Box(176f, 8f, 608f, 342f), two.tiles[1])
        assertSane(two)
        // The large picture is the largest there is in any layout.
        assertTrue(608f > 468f)
    }

    @Test fun onePictureFillsTheScreen() {
        assertBox(Box(0f, 0f, w, h), MultiviewLayout.of(1, null, w, h, gap).tiles.single())
        assertTrue(MultiviewLayout.of(0, null, w, h, gap).stops.isEmpty())
    }

    @Test fun theDpadMovesBetweenPicturesSideBySide() {
        val s = MultiviewLayout.of(2, null, w, h, gap).stops
        assertEquals(1, MultiviewLayout.move(s, 0, Dir.RIGHT))
        assertEquals(0, MultiviewLayout.move(s, 1, Dir.LEFT))
        // Nothing that way: it stays.
        assertEquals(0, MultiviewLayout.move(s, 0, Dir.LEFT))
        assertEquals(0, MultiviewLayout.move(s, 0, Dir.UP))
        assertEquals(1, MultiviewLayout.move(s, 1, Dir.DOWN))
    }

    @Test fun theDpadMovesRoundTheGridAndOntoAdd() {
        val four = MultiviewLayout.of(4, null, w, h, gap).stops
        assertEquals(1, MultiviewLayout.move(four, 0, Dir.RIGHT))
        assertEquals(2, MultiviewLayout.move(four, 0, Dir.DOWN))
        assertEquals(1, MultiviewLayout.move(four, 3, Dir.UP))
        assertEquals(2, MultiviewLayout.move(four, 3, Dir.LEFT))
        val three = MultiviewLayout.of(3, null, w, h, gap).stops
        assertEquals(3, MultiviewLayout.move(three, 1, Dir.DOWN))
        assertEquals(3, MultiviewLayout.move(three, 2, Dir.RIGHT))
        assertEquals(1, MultiviewLayout.move(three, 3, Dir.UP))
    }

    @Test fun downFromTheLargePictureLandsOnTheSmallOneUnderIt() {
        val s = MultiviewLayout.of(4, 0, w, h, gap).stops
        assertEquals(2, MultiviewLayout.move(s, 0, Dir.DOWN))
        assertEquals(0, MultiviewLayout.move(s, 1, Dir.UP))
        assertEquals(0, MultiviewLayout.move(s, 3, Dir.UP))
        assertEquals(2, MultiviewLayout.move(s, 1, Dir.RIGHT))
        assertEquals(3, MultiviewLayout.move(s, 2, Dir.RIGHT))
        assertEquals(3, MultiviewLayout.move(s, 3, Dir.RIGHT))
        // Two small ones the same distance away: the first.
        val three = MultiviewLayout.of(3, 2, w, h, gap).stops
        assertEquals(0, MultiviewLayout.move(three, 2, Dir.DOWN))
    }

    @Test fun aHeldPictureOffersRemoveAddChangeAndFullScreen() {
        assertEquals(listOf(Choice.REMOVE, Choice.ADD, Choice.CHANGE, Choice.FULL_SCREEN), MultiviewRules.menu(2, 4))
        assertEquals(listOf(Choice.REMOVE, Choice.ADD, Choice.CHANGE, Choice.FULL_SCREEN), MultiviewRules.menu(3, 4))
        // No room for another: no Add.
        assertEquals(listOf(Choice.REMOVE, Choice.CHANGE, Choice.FULL_SCREEN), MultiviewRules.menu(4, 4))
        assertEquals(listOf(Choice.REMOVE, Choice.CHANGE, Choice.FULL_SCREEN), MultiviewRules.menu(2, 2))
        assertEquals(listOf("Remove", "Add channel", "Change channel", "Full screen"), Choice.entries.map { it.label })
    }

    @Test fun theSettingKeepsTwoToFourPictures() {
        assertEquals(2, MultiviewRules.most(1))
        assertEquals(3, MultiviewRules.most(3))
        assertEquals(4, MultiviewRules.most(9))
    }

    @Test fun okMakesAPictureLargeAndOkAgainGoesBackToEqual() {
        val m = MultiviewModel<String>(4)
        m.add("a", largeFor3Plus = false)
        m.add("b", largeFor3Plus = false)
        assertNull(m.featured)
        assertTrue(m.ok())
        assertEquals(1, m.featured)
        // Another picture: it becomes the large one.
        assertTrue(m.move(Dir.DOWN, m.layout(w, h, gap)))
        assertEquals(0, m.selected)
        m.ok()
        assertEquals(0, m.featured)
        m.ok()
        assertNull(m.featured)
    }

    @Test fun theSoundFollowsTheSelectedPictureButNotAdd() {
        val m = MultiviewModel<String>(4)
        listOf("a", "b", "c").forEach { m.add(it, largeFor3Plus = false) }
        assertEquals(2, m.audible)
        assertTrue(m.move(Dir.RIGHT, m.layout(w, h, gap)))
        assertTrue(m.onAdd)
        assertEquals(2, m.audible)
        assertFalse(m.ok())
        assertEquals("c", m.backTarget())
        assertTrue(m.move(Dir.UP, m.layout(w, h, gap)))
        assertEquals(1, m.audible)
    }

    @Test fun onePictureLeftLeavesForFullScreenOnIt() {
        val m = MultiviewModel<String>(4)
        m.add("a", false)
        m.add("b", false)
        assertEquals("b", m.remove(0))
        val three = MultiviewModel<String>(4)
        listOf("a", "b", "c").forEach { three.add(it, false) }
        assertNull(three.remove(1))
        assertEquals(listOf("a", "c"), three.tiles)
        assertEquals("a", MultiviewRules.leaveAfterRemove(listOf("a")))
        assertNull(MultiviewRules.leaveAfterRemove(listOf("a", "b")))
        assertTrue(MultiviewRules.leaveWithoutPick(1))
        assertFalse(MultiviewRules.leaveWithoutPick(2))
    }

    @Test fun removingKeepsTheLargePictureAndTheSelectionInPlace() {
        assertNull(MultiviewRules.featuredAfterRemove(2, 2, 3))
        assertEquals(1, MultiviewRules.featuredAfterRemove(2, 0, 3))
        assertEquals(0, MultiviewRules.featuredAfterRemove(0, 2, 3))
        assertNull(MultiviewRules.featuredAfterRemove(0, 1, 1))
        assertEquals(1, MultiviewRules.indexAfterRemove(1, 1, 3))
        assertEquals(1, MultiviewRules.indexAfterRemove(2, 2, 2))
        assertEquals(0, MultiviewRules.indexAfterRemove(1, 0, 3))
        // The selection on "Add" when a grid of 3 goes down to 2 (no "Add" there): on the last picture.
        val m = MultiviewModel<String>(4)
        listOf("a", "b", "c").forEach { m.add(it, false) }
        m.move(Dir.RIGHT, m.layout(w, h, gap))
        assertTrue(m.onAdd)
        m.remove(0)
        assertEquals(1, m.selected)
        assertFalse(m.onAdd)
    }

    @Test fun theOneLargeSettingOpensThreeOrFourWithTheNewPictureLarge() {
        assertNull(MultiviewRules.featuredAfterAdd(null, 2, 1, largeFor3Plus = true))
        assertEquals(2, MultiviewRules.featuredAfterAdd(null, 3, 2, largeFor3Plus = true))
        assertNull(MultiviewRules.featuredAfterAdd(null, 3, 2, largeFor3Plus = false))
        assertEquals(0, MultiviewRules.featuredAfterAdd(0, 4, 3, largeFor3Plus = true))
    }

    @Test fun theProvidersAllowanceIsSaidPlainly() {
        assertTrue(MultiviewRules.fits(0, 0))
        assertFalse(MultiviewRules.fits(1, 0))
        assertTrue(MultiviewRules.fits(1, 2))
        assertFalse(MultiviewRules.fits(2, 2))
        assertTrue(MultiviewRules.limitMessage(0).contains("one channel at a time"))
        assertEquals("This playlist allows one channel at a time", MultiviewRules.limitMessage(1))
        assertTrue(MultiviewRules.limitMessage(3).contains("all 3 are in use"))
        assertEquals("BBC One is already in Multiview", MultiviewRules.alreadyMessage("BBC One"))
    }

    @Test fun picturesHoldLessMemoryAndSmallerVideoTheMoreThereAre() {
        assertEquals(1, MultiviewRules.memoryStep(2))
        assertEquals(2, MultiviewRules.memoryStep(3))
        assertEquals(2, MultiviewRules.memoryStep(4))
        assertEquals(MultiviewRules.Role.LARGE, MultiviewRules.role(1, 3, 1))
        assertEquals(MultiviewRules.Role.SMALL, MultiviewRules.role(0, 3, 1))
        assertEquals(MultiviewRules.Role.EQUAL, MultiviewRules.role(0, 3, null))
        // Never a 4K decoder for any picture.
        for (r in MultiviewRules.Role.entries) assertTrue(MultiviewRules.maxVideo(r)[1] <= 1080)
        assertEquals(540, MultiviewRules.maxVideo(MultiviewRules.Role.SMALL)[1])
    }
}
