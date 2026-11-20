package app.fireplayer.vod.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RowMathTest {

    private val lefts = { i: Int -> i * 270f }

    @Test
    fun offsetSitsOnACardAndGlidesBetweenTwo() {
        assertEquals(0f, RowMath.offset(0f, 16, lefts), 0f)
        assertEquals(540f, RowMath.offset(2f, 16, lefts), 0f)
        assertEquals(675f, RowMath.offset(2.5f, 16, lefts), 0.001f)
        // Never past the first or last card (a spring overshooting a little).
        assertEquals(0f, RowMath.offset(-0.3f, 16, lefts), 0f)
        assertEquals(15 * 270f, RowMath.offset(15.4f, 16, lefts), 0f)
        assertEquals(0f, RowMath.offset(3f, 0, lefts), 0f)
    }

    @Test
    fun offsetFollowsUnevenLefts() {
        val chips = floatArrayOf(0f, 100f, 260f, 300f)
        assertEquals(180f, RowMath.offset(1.5f, 4) { chips[it] }, 0.001f)
    }

    @Test
    fun shareOfTheGrowth() {
        assertEquals(1f, RowMath.share(3, 3f, 1f), 0f)
        assertEquals(0.5f, RowMath.share(3, 3.5f, 1f), 0.0001f)
        assertEquals(0.5f, RowMath.share(4, 3.5f, 1f), 0.0001f)
        assertEquals(0f, RowMath.share(5, 3.5f, 1f), 0f)
        // Shrunk back (focus elsewhere): nobody grown.
        assertEquals(0f, RowMath.share(3, 3f, 0f), 0f)
        assertEquals(0.25f, RowMath.share(3, 3f, 0.25f), 0.0001f)
    }

    @Test
    fun followersMakeRoomForTheGrownCard() {
        val extra = 38.72f
        // The chosen card and those before it stay; every card after it moves the full growth.
        assertEquals(0f, RowMath.follower(3, 3f, 1f, extra), 0f)
        assertEquals(0f, RowMath.follower(2, 3f, 1f, extra), 0f)
        assertEquals(extra, RowMath.follower(4, 3f, 1f, extra), 0f)
        assertEquals(extra, RowMath.follower(9, 3f, 1f, extra), 0f)
        // Mid-glide: the next card is half moved along.
        assertEquals(extra / 2, RowMath.follower(4, 3.5f, 1f, extra), 0.0001f)
        assertEquals(0f, RowMath.follower(4, 3.5f, 0f, extra), 0f)
    }

    @Test
    fun boundCardsAreTheSliverTheChosenAndWhatFits() {
        assertEquals(0..8, RowMath.boundCards(0, 16))
        assertEquals(4..13, RowMath.boundCards(5, 16))
        assertEquals(14..15, RowMath.boundCards(15, 16))
        assertEquals(0..2, RowMath.boundCards(1, 3))
        assertTrue(RowMath.boundCards(0, 0).isEmpty())
        assertEquals(15..15, RowMath.boundCards(40, 16, before = 0, after = 0))
    }

    @Test
    fun pageTargetShowsTheHeaderOnlyOnTheFirstRow() {
        val tops = floatArrayOf(422f, 746f, 1210f)
        assertEquals(0f, RowMath.pageTarget(0, 0) { tops[it] }, 0f)
        // A first row that fits under the header stays; one too tall moves up just far enough to show whole.
        assertEquals(0f, RowMath.pageTarget(0, 0, need = 500f, viewportH = 992f) { tops[it] }, 0f)
        assertEquals(58f, RowMath.pageTarget(0, 0, need = 628f, viewportH = 992f) { tops[it] }, 0f)
        // Never past its own top, and lower rows go to their tops as before.
        assertEquals(422f, RowMath.pageTarget(0, 0, need = 2_000f, viewportH = 992f) { tops[it] }, 0f)
        assertEquals(746f, RowMath.pageTarget(1, 0, need = 628f, viewportH = 992f) { tops[it] }, 0f)
        assertEquals(746f, RowMath.pageTarget(1, 0) { tops[it] }, 0f)
        assertEquals(1210f, RowMath.pageTarget(2, 0) { tops[it] }, 0f)
    }

    @Test
    fun boundRowsAreThoseInViewAndTheOneAbove() {
        // Bands of 464 px from 422 down (header 390 + 32), viewport 992 px.
        val tops = floatArrayOf(422f, 886f, 1350f, 1814f, 2278f)
        val heights = FloatArray(5) { 464f }
        // Header shown: rows 0 and 1 show (row 1 partly).
        assertEquals(setOf(0, 1), RowMath.boundRows(0, 0f, 992f, tops, heights))
        // Row 2 chosen, the page at its top: rows 2 and 3 show, row 4's title peeks in, and row 1 above is kept.
        assertEquals(setOf(1, 2, 3, 4), RowMath.boundRows(2, 1350f, 992f, tops, heights))
        // The last row: it and the one above.
        assertEquals(setOf(3, 4), RowMath.boundRows(4, 2278f, 992f, tops, heights))
    }

    @Test
    fun rankNumberFitsItsRoom() {
        val size = 223.8f
        // A single digit narrower than the room keeps the full size.
        assertEquals(size, RowMath.rankTextSize(size, 120f, 136.5f), 0f)
        // "10", wider than the room, is narrowed to fit exactly.
        val shrunk = RowMath.rankTextSize(size, 210f, 136.5f)
        assertEquals(size * 136.5f / 210f, shrunk, 0.001f)
        assertTrue(shrunk < size)
        // Text measured at the shrunk size (width scales with size) fills the room.
        assertEquals(136.5f, 210f * shrunk / size, 0.001f)
    }

    @Test
    fun rankBaselineIsJustAboveThePostersBottom() {
        assertEquals(324f - 324f * 2f / 333f, RowMath.rankBaseline(0f, 324f), 0.001f)
        assertEquals(10f + 324f - 324f * 2f / 333f, RowMath.rankBaseline(10f, 324f), 0.001f)
    }

    @Test
    fun gridCellsAndMoves() {
        assertEquals(0, RowMath.gridRow(5, 6))
        assertEquals(1, RowMath.gridRow(6, 6))
        assertEquals(5, RowMath.gridColumn(11, 6))
        assertEquals(0, RowMath.gridRows(0, 6))
        assertEquals(3, RowMath.gridRows(13, 6))
        // Down: the poster under it, or the last one when the row below is shorter, or nothing at the bottom.
        assertEquals(8, RowMath.gridDown(2, 13, 6))
        assertEquals(12, RowMath.gridDown(10, 13, 6))
        assertEquals(-1, RowMath.gridDown(12, 13, 6))
        assertEquals(-1, RowMath.gridDown(7, 12, 6))
        assertEquals(1, RowMath.gridUp(7, 6))
        assertEquals(-1, RowMath.gridUp(5, 6))
    }

    @Test
    fun gridAsksForMoreNearTheEnd() {
        assertFalse(RowMath.needMore(0, 9))
        assertFalse(RowMath.needMore(5, 9))
        assertTrue(RowMath.needMore(6, 9))
        assertTrue(RowMath.needMore(8, 9))
        assertTrue(RowMath.needMore(0, 3))
    }

    @Test
    fun gridTargetAndBoundRows() {
        assertEquals(0f, RowMath.gridTarget(0, 384f), 0f)
        assertEquals(768f, RowMath.gridTarget(2, 384f), 0f)
        assertEquals(0..3, RowMath.gridBoundRows(0, 10))
        assertEquals(3..7, RowMath.gridBoundRows(4, 10))
        assertEquals(8..9, RowMath.gridBoundRows(9, 10))
        assertTrue(RowMath.gridBoundRows(0, 0).isEmpty())
    }
}
