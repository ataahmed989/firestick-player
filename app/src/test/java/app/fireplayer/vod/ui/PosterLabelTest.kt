package app.fireplayer.vod.ui

import app.fireplayer.vod.RowStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The chosen poster's title under it: its wrap and ellipsis, its size, and the room rows and pages keep for it. */
class PosterLabelTest {

    /** One unit a character: widths are in characters. */
    private val chars: (String) -> Float = { it.length.toFloat() }

    private fun wrap(title: String, width: Int, lines: Int = VodSizes.LABEL_LINES) =
        PosterLabelText.wrap(title, width.toFloat(), lines, chars)

    // ---- Wrap and ellipsis ----------------------------------------------------------------------------------------

    @Test
    fun aShortTitleStaysOnOneLine() {
        assertEquals("Dune", wrap("Dune", 12))
        assertEquals("Dune: Part Two", wrap("Dune: Part Two", 14))
        // Spaces collapse; nothing at all shows nothing.
        assertEquals("Dune Part Two", wrap("  Dune   Part\tTwo ", 20))
        assertEquals("", wrap("", 12))
        assertEquals("", wrap("   ", 12))
    }

    @Test
    fun itBreaksAtWordBoundariesWithinThePostersWidth() {
        // The reference: two lines, broken between words.
        assertEquals("The Hunger Games:\nMockingjay – Part 2", wrap("The Hunger Games: Mockingjay – Part 2", 20))
        // A word that just fits stays on the line.
        assertEquals("The Lord of the Rings:\nThe Two Towers", wrap("The Lord of the Rings: The Two Towers", 22))
    }

    @Test
    fun pastTwoLinesTheSecondEndsInAnEllipsisAfterWholeWords() {
        assertEquals(
            "The Lord of the Rings:\nThe Fellowship of the…",
            wrap("The Lord of the Rings: The Fellowship of the Ring", 22),
        )
        // A separator left before the ellipsis goes.
        assertEquals("Star Wars:\nEpisode IV…", wrap("Star Wars: Episode IV – A New Hope", 12))
        assertEquals("Mission:\nImpossible…", wrap("Mission: Impossible, Dead Reckoning Part One", 12))
    }

    @Test
    fun aWordTooWideForALineIsCutWhereItStopsFitting() {
        // On the first line it goes on in the next; on the last, it ends in the ellipsis.
        assertEquals("Supercal\nifragil…", wrap("Supercalifragilistic", 8))
        assertEquals("Supercal\nifragili", wrap("Supercalifragili", 8))
        assertEquals("Up\nSupercal…", wrap("Up Supercalifragilistic", 9))
    }

    @Test
    fun oneLineOnly() {
        assertEquals("A…", wrap("A B C", 3, lines = 1))
        assertEquals("A B C", wrap("A B C", 5, lines = 1))
    }

    @Test
    fun neverMoreLinesThanAllowedNorWiderThanThePoster() {
        val titles = listOf(
            "The Hunger Games: Mockingjay – Part 2",
            "Harry Potter and the Deathly Hallows: Part 1",
            "Borat: Cultural Learnings of America for Make Benefit Glorious Nation of Kazakhstan",
            "Night of the Day of the Dawn of the Son of the Bride of the Return of the Revenge of the Terror",
            "Pneumonoultramicroscopicsilicovolcanoconiosis",
            "M3GAN",
        )
        for (title in titles) for (width in 4..40) {
            val lines = wrap(title, width).split('\n')
            assertTrue("$title at $width: ${lines.size} lines", lines.size <= VodSizes.LABEL_LINES)
            for (line in lines) assertTrue("$title at $width: \"$line\"", line.length <= width)
            // Whatever is shown is the title's start, in order (an ellipsis only at the very end).
            val shown = lines.joinToString("").removeSuffix(PosterLabelText.ELLIPSIS)
            assertFalse(shown.contains(PosterLabelText.ELLIPSIS))
            assertTrue("$title at $width: $lines", title.replace(" ", "").startsWith(shown.replace(" ", "").trimEnd(',', ':', ';', '–', '—', '-', '·')))
        }
    }

    // ---- Size ---------------------------------------------------------------------------------------------------

    @Test
    fun onlyPostersAndTopTenShowIt() {
        assertTrue(VodSizes.labelled(RowStyle.POSTER))
        assertTrue(VodSizes.labelled(RowStyle.RANKED))
        // Continue Watching, the services', a studio's logo cards, episodes and chips don't.
        for (s in listOf(RowStyle.WIDE, RowStyle.SERVICES, RowStyle.LOGO, RowStyle.EPISODE, RowStyle.GENRES, RowStyle.SEASONS)) {
            assertFalse(s.name, VodSizes.labelled(s))
            assertEquals(s.name, VodSizes.bandH(s, true, VodSizes.ROW_GAP, false, labelled = false), VodSizes.bandH(s, true, VodSizes.ROW_GAP), 0f)
        }
    }

    @Test
    fun itsSizeFollowsThePostersWidth() {
        assertEquals(VodSizes.LABEL_SP, VodSizes.labelSp(VodSizes.BROWSE_POSTER_W), 0.001f)
        // More Like This's smaller posters: about 10 sp (the reference); See all's in between.
        assertEquals(10f, VodSizes.labelSp(VodSizes.POSTER_W), 0.05f)
        assertTrue(VodSizes.labelSp(VodSizes.GRID_POSTER_W) in VodSizes.labelSp(VodSizes.POSTER_W)..VodSizes.LABEL_SP)
        assertEquals(VodSizes.labelSp(VodSizes.POSTER_W) / VodSizes.LABEL_SP, VodSizes.POSTER_W / VodSizes.BROWSE_POSTER_W, 0.0001f)
        // Its box holds two lines.
        assertEquals(2 * 14f * 1.2f + VodSizes.LABEL_SLACK, VodSizes.labelH(VodSizes.BROWSE_POSTER_W), 0.001f)
    }

    // ---- The room for it ----------------------------------------------------------------------------------------

    @Test
    fun aRowKeepsItsRoomUnderTheGrownPosterBeforeTheNextRowsTitle() {
        for (large in listOf(false, true)) for (style in listOf(RowStyle.POSTER, RowStyle.RANKED)) {
            val top = VodSizes.labelTop(style, true, large)
            // Under the grown poster's ring, never over it: the posters never move for it.
            assertTrue(top >= VodSizes.stripTop() + VodSizes.stripH(style, large) + VodSizes.RING)
            // It ends the row's gap before the next row's title.
            val bottom = top + VodSizes.labelH(VodSizes.cardW(style, large))
            assertEquals(VodSizes.bandH(style, true, VodSizes.ROW_GAP, large), bottom + VodSizes.ROW_GAP, 0.001f)
            assertEquals(VodSizes.rowShownH(style, true, large), bottom, 0.001f)
        }
    }

    @Test
    fun everyRowOfAPageLikeMarvelsShowsItsChosenTitleWholeTheLastOneToo() {
        // A studio page: the banner, then five rows of posters (a Top 10 among them).
        val styles = listOf(RowStyle.POSTER, RowStyle.POSTER, RowStyle.RANKED, RowStyle.POSTER, RowStyle.POSTER)
        val tops = FloatArray(styles.size)
        var y = VodSizes.firstRowTop()
        for (i in styles.indices) {
            tops[i] = y
            y += VodSizes.bandH(styles[i], true, VodSizes.ROW_GAP, large = true)
        }
        val last = styles.size - 1
        val end = tops[last] + VodSizes.rowShownH(styles[last], true, true) + VodSizes.ROW_GAP
        for (k in styles.indices) {
            val need = VodSizes.rowShownH(styles[k], true, true)
            val offset = RowMath.pageTarget(k, 0, need, VodSizes.VIEWPORT_H, end) { tops[it] }
            val titleBottom = VodSizes.VIEWPORT_TOP + tops[k] - offset + VodSizes.labelTop(styles[k], true, true) +
                VodSizes.labelH(VodSizes.cardW(styles[k], true))
            val rowTop = VodSizes.VIEWPORT_TOP + tops[k] - offset
            assertTrue("row $k: title ends at $titleBottom", titleBottom <= VodSizes.SCREEN_H + 0.01f)
            assertTrue("row $k: starts at $rowTop", rowTop >= VodSizes.VIEWPORT_TOP - 0.01f)
        }
        // The last row: at the screen's bottom, its title's last line on screen with the row gap under it.
        val offset = RowMath.pageTarget(last, 0, VodSizes.rowShownH(styles[last], true, true), VodSizes.VIEWPORT_H, end) { tops[it] }
        assertEquals(end - VodSizes.VIEWPORT_H, offset, 0.01f)
        assertTrue(offset < tops[last])
    }

    @Test
    fun aFilmsAndAShowsMoreLikeThisShowItsTitle() {
        // A film's: beside the column, its band (the title's room in it) ends on screen.
        val film = VodSizes.TITLE_HEADER_H + VodSizes.filmMoreTop() + VodSizes.bandH(RowStyle.POSTER)
        assertTrue(film - VodSizes.ROW_GAP <= VodSizes.SCREEN_H)
        // A show's: the page glides up until its band, the title's room in it, ends on screen.
        val moreTop = VodSizes.TITLE_HEADER_H + VodSizes.showStageH()
        val moreBottom = moreTop + VodSizes.bandH(RowStyle.POSTER)
        val g = VodSizes.glide(moreBottom, VodSizes.TITLE_HEADER_H + VodSizes.SEASON_TOP)
        assertTrue(moreTop - g + VodSizes.rowShownH(RowStyle.POSTER, true, false) <= VodSizes.SCREEN_H)
    }
}
