package app.fireplayer.live.ui

import app.fireplayer.live.Programme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideCellsTest {

    private val min = 60_000L
    private val base = 1_800_000_000_000L - Math.floorMod(1_800_000_000_000L, GuideCells.SLOT_MS)
    private fun p(startMin: Int, stopMin: Int, title: String) = Programme(base + startMin * min, base + stopMin * min, title, null, null)

    @Test fun cellsTileTheRowWithGaps() {
        val cells = GuideCells.cells(listOf(p(30, 60, "B"), p(-20, 10, "A"), p(90, 200, "C")), base, base + 120 * min)
        assertEquals(listOf("A", null, "B", null, "C"), cells.map { it.programme?.title })
        // No holes, no overlaps, exactly the requested range.
        assertEquals(base, cells.first().start)
        assertEquals(base + 120 * min, cells.last().stop)
        for (i in 1 until cells.size) assertEquals(cells[i - 1].stop, cells[i].start)
        // The clipped first cell still knows the programme's true start.
        assertEquals(base - 20 * min, cells[0].programme!!.start)
    }

    @Test fun overlappingProgrammesAreShortened() {
        val cells = GuideCells.cells(listOf(p(0, 40, "A"), p(30, 60, "B")), base, base + 60 * min)
        assertEquals(listOf("A", "B"), cells.map { it.programme?.title })
        assertEquals(base + 40 * min, cells[1].start)
    }

    @Test fun emptyRowIsOneNoInformationCell() {
        val cells = GuideCells.cells(emptyList(), base, base + 60 * min)
        assertEquals(1, cells.size)
        assertNull(cells[0].programme)
        assertTrue(GuideCells.cells(emptyList(), base, base).isEmpty())
    }

    @Test fun atFindsTheCellOrTheNearest() {
        val cells = GuideCells.cells(listOf(p(0, 30, "A"), p(30, 60, "B")), base, base + 60 * min)
        assertEquals(1, GuideCells.at(cells, base + 45 * min))
        assertEquals(0, GuideCells.at(cells, base - 5 * min))
        assertEquals(1, GuideCells.at(cells, base + 90 * min))
        assertEquals(-1, GuideCells.at(emptyList(), base))
    }

    @Test fun cursorStaysOnNowForTheProgrammeOnAir() {
        val cells = GuideCells.cells(listOf(p(0, 60, "On air"), p(60, 90, "Next")), base, base + 90 * min)
        val now = base + 12 * min
        assertEquals(now, GuideCells.cursorFor(cells[0], now))
        assertEquals(base + 60 * min, GuideCells.cursorFor(cells[1], now))
    }

    @Test fun windowMovesInHalfHourStepsAndNeverBeforeNow() {
        val window = 120 * min
        val earliest = base
        val later = GuideCells.cells(listOf(p(150, 180, "Later")), base, base + 300 * min).first { it.programme != null }
        val w = GuideCells.windowFor(later, base, window, earliest)
        assertTrue(later.start in w until w + window)
        assertEquals(0L, Math.floorMod(w - base, GuideCells.SLOT_MS))
        // Coming back left, the window returns, but not before the slot holding now.
        val first = GuideCells.cells(listOf(p(0, 30, "Now")), base, base + 30 * min)[0]
        assertEquals(base, GuideCells.windowFor(first, w, window, earliest))
        // A cell already visible doesn't move the window.
        val visible = GuideCells.cells(listOf(p(30, 60, "Soon")), base + 30 * min, base + 60 * min)[0]
        assertEquals(base, GuideCells.windowFor(visible, base, window, earliest))
    }

    @Test fun slotStart() {
        assertEquals(base, GuideCells.slotStart(base + 29 * min))
        assertEquals(base + 30 * min, GuideCells.slotStart(base + 30 * min))
    }

    // ---- Cell edges and the focus glide ------------------------------------------------------------------------------

    private val gridLeft = 512f
    private val right = 1872f
    private val pxPerMin = 9.2f
    private val gap = 6f

    @Test fun cellEdgesFollowTheTimeAndClipToTheGrid() {
        // A cell inside the window: its times to pixels, half a gap in from each end.
        assertEquals(gridLeft + 30 * pxPerMin + gap / 2, GuideCells.cellLeft(base + 30 * min, base, gridLeft, pxPerMin, gap), 0.01f)
        assertEquals(gridLeft + 60 * pxPerMin - gap / 2, GuideCells.cellRight(base + 60 * min, base, gridLeft, right, pxPerMin, gap), 0.01f)
        // On air since before the window: it starts at the grid's left edge.
        assertEquals(gridLeft + gap / 2, GuideCells.cellLeft(base - 90 * min, base, gridLeft, pxPerMin, gap), 0.01f)
        // Running past the right edge: it ends there.
        assertEquals(right - gap / 2, GuideCells.cellRight(base + 600 * min, base, gridLeft, right, pxPerMin, gap), 0.01f)
        // Wholly before the window: no width left.
        val l = GuideCells.cellLeft(base - 60 * min, base, gridLeft, pxPerMin, gap)
        val r = GuideCells.cellRight(base - 30 * min, base, gridLeft, right, pxPerMin, gap)
        assertTrue(r - l < 1f)
    }

    @Test fun glideRunsFromTheOldCellToTheNewOne() {
        val g = FocusGlide()
        // Settled: the highlight is the focused cell.
        g.update(100f, 10f, 300f, 36f)
        assertRect(g, 100f, 10f, 300f, 36f)
        assertFalse(g.moving)
        // Down a row to a cell further right.
        g.begin(100f, 10f, 300f, 36f)
        assertTrue(g.moving)
        g.update(200f, 68f, 500f, 94f)
        assertRect(g, 100f, 10f, 300f, 36f)
        g.fraction = 0.5f
        g.update(200f, 68f, 500f, 94f)
        assertRect(g, 150f, 39f, 400f, 65f)
        g.fraction = 1f
        g.update(200f, 68f, 500f, 94f)
        assertRect(g, 200f, 68f, 500f, 94f)
        assertFalse(g.moving)
    }

    @Test fun aNewGlideStartsWhereTheHighlightIsDrawn() {
        val g = FocusGlide()
        g.begin(0f, 0f, 100f, 26f)
        g.fraction = 0.4f
        g.update(200f, 0f, 300f, 26f)
        val l = g.left; val r = g.right
        // Another press mid-glide: it carries on from there, no jump back to either cell.
        g.begin(g.left, g.top, g.right, g.bottom)
        g.update(400f, 0f, 500f, 26f)
        assertRect(g, l, 0f, r, 26f)
        g.fraction = 1f
        g.update(400f, 0f, 500f, 26f)
        assertRect(g, 400f, 0f, 500f, 26f)
    }

    @Test fun glideStartMovesWithTheTimeWindow() {
        val g = FocusGlide()
        // The highlight at x 1700..1850 on screen; Right moves the window half an hour on, so the content shifts left
        // by that many pixels and is drawn shifted right by the same while it slides back (screen = content + slide).
        val shift = 30 * pxPerMin
        g.begin(1700f, 10f, 1850f, 36f)
        g.shiftX(-shift)
        g.update(1500f, 10f, 1700f, 36f)
        assertEquals(1700f, g.left + shift, 0.01f)
        assertEquals(1850f, g.right + shift, 0.01f)
    }

    @Test fun clearedGlideSitsOnTheTarget() {
        val g = FocusGlide()
        g.begin(0f, 0f, 10f, 10f)
        g.clear()
        g.update(50f, 60f, 70f, 80f)
        assertRect(g, 50f, 60f, 70f, 80f)
        assertFalse(g.moving)
    }

    private fun assertRect(g: FocusGlide, l: Float, t: Float, r: Float, b: Float) {
        assertEquals(l, g.left, 0.01f)
        assertEquals(t, g.top, 0.01f)
        assertEquals(r, g.right, 0.01f)
        assertEquals(b, g.bottom, 0.01f)
    }
}
