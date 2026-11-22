package app.fireplayer.vod.ui

import app.fireplayer.shell.TextStyle
import app.fireplayer.ui.ImageFit
import app.fireplayer.vod.RowStyle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The title page's geometry (design-brief.md §6.4) and the shared look values step 1's pages moved here unchanged. */
class VodSizesTitleTest {

    private fun near(expected: Float, actual: Float, tolerance: Float = 0.01f) = assertEquals(expected, actual, tolerance)

    @Test
    fun stepOnesRowsAreUnchanged() {
        // The strips, card tops and bands step 1 had before the item pivot was generalised.
        near(187.92f, VodSizes.stripH(RowStyle.POSTER))
        near(187.92f, VodSizes.stripH(RowStyle.RANKED))
        near(118.32f, VodSizes.stripH(RowStyle.WIDE))
        near(37.44f, VodSizes.stripH(RowStyle.GENRES))
        near(12.96f, VodSizes.cardTop(RowStyle.POSTER))
        near(8.16f, VodSizes.cardTop(RowStyle.WIDE))
        near(0.72f, VodSizes.cardTop(RowStyle.GENRES))
        near(262.98f, VodSizes.bandH(RowStyle.POSTER))
        near(162.32f, VodSizes.bandH(RowStyle.WIDE))
        near(81.44f, VodSizes.bandH(RowStyle.GENRES))
        // Each card grows about the middle of its picture (the measured height's half, as before).
        near(81f, VodSizes.pivotY(RowStyle.POSTER))
        near(51f, VodSizes.pivotY(RowStyle.WIDE))
        near(18f, VodSizes.pivotY(RowStyle.GENRES))
        near(0.04f, VodSizes.GROW_CHIP, 0.0001f)
    }

    @Test
    fun theMovedLookValuesKeepTheirValues() {
        assertEquals(36f, VodSizes.CHIP_H)
        assertEquals(16f, VodSizes.CHIP_PAD)
        assertEquals(10f, VodSizes.CHIP_GAP)
        // The season shown: the accent (#3D8BFF in colors.xml) at 40%, the Episodes sheet's 0x663D8BFF.
        assertEquals(0x663D8BFF, (VodSizes.CHIP_SELECTED_ALPHA shl 24) or 0x3D8BFF)
        assertEquals(14f, VodSizes.PAGE_OMEGA)
        assertEquals(0.5f, VodSizes.PAGE_SETTLE)
        assertEquals(650L, VodSizes.HERO_FADE_MS)
        assertEquals(0.4f, VodSizes.ART_CROP_Y)
        assertEquals(3_500L, VodSizes.NOTICE_MS)
        assertEquals(450L, VodSizes.LIST_CLOSE_MS)
        assertEquals(0xFFF2F4F7.toInt(), VodSizes.RING_COLOR)
        assertEquals(0xFF26313F.toInt(), VodSizes.HOLDER_TOP)
        assertEquals(0xFF141A22.toInt(), VodSizes.HOLDER_BOTTOM)
        assertEquals(0xC7000000.toInt(), VodSizes.SCRIM_BOTTOM)
        assertEquals(0x47FFFFFF, VodSizes.TRACK_COLOR)
        assertEquals(0x59FFFFFF, VodSizes.DOT_OFF)
        assertEquals(TextStyle(16f, TextStyle.Weight.BOLD, TextStyle.Ink.OWN, 0xFFE3E9F0.toInt()), VodSizes.ROW_TITLE_STYLE)
        assertEquals(TextStyle(12.5f, TextStyle.Weight.REGULAR, TextStyle.Ink.OWN, 0xFFC3CFDC.toInt()), VodSizes.FACTS)
        assertEquals(TextStyle(13f, TextStyle.Weight.REGULAR, TextStyle.Ink.MUTED, lineGap = 2f), VodSizes.STORY)
        // The banner's name where a title has no logo: smaller than the 40 sp it was (tuning round 1).
        assertEquals(TextStyle(28f, TextStyle.Weight.BOLD), VodSizes.HEADER_TITLE)
        assertEquals(TextStyle(15.5f, TextStyle.Weight.BOLD, TextStyle.Ink.OWN, 0xFFFFFFFF.toInt()), VodSizes.CARD_NAME)
        assertEquals(TextStyle(12f, TextStyle.Weight.SEMIBOLD, TextStyle.Ink.OWN, 0xFFD6DEE8.toInt()), VodSizes.CARD_CAPTION)
        // The header's baked fade: step 1's eased stops.
        assertArrayEquals(intArrayOf(0x00, 0x24, 0x80, 0xDB, 0xFF), VodSizes.FADE_ALPHAS)
        assertArrayEquals(floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), VodSizes.FADE_STOPS, 0f)
        assertEquals(0.45f, VodSizes.HEADER_FADE_LEFT_TO)
        assertEquals(0.65f, VodSizes.HEADER_FADE_BOTTOM_FROM)
    }

    @Test
    fun episodeCardsAreWideCardsWithTwoLinesUnder() {
        assertEquals(VodSizes.WIDE_W, VodSizes.cardW(RowStyle.EPISODE))
        assertEquals(138f, VodSizes.cardH(RowStyle.EPISODE))
        assertEquals(102f, VodSizes.pictureH(RowStyle.EPISODE))
        near(51f, VodSizes.pivotY(RowStyle.EPISODE))
        // Grown 16% about the still's middle: 8.16 dp above it, its captions 13.92 dp further down.
        near(8.16f, VodSizes.cardTop(RowStyle.EPISODE))
        near(160.08f, VodSizes.stripH(RowStyle.EPISODE))
        near(196f, VodSizes.pitch(RowStyle.EPISODE))
        near(29.12f, VodSizes.extra(RowStyle.EPISODE), 0.001f)
        // The two caption lines fit under the still, with their gaps.
        val lines = VodSizes.EPISODE_CAPTION_GAP + VodSizes.EPISODE_TITLE.lineSp + VodSizes.EPISODE_LINE_GAP + VodSizes.EPISODE_META.lineSp
        assertTrue(VodSizes.pictureH(RowStyle.EPISODE) + lines <= VodSizes.EPISODE_ITEM_H)
        // Season chips (the rows' SEASONS style): as step 1's genre row.
        near(37.44f, VodSizes.stripH(RowStyle.SEASONS))
    }

    @Test
    fun theTextBlockHoldsItsLines() {
        // The title box, the facts row, two lines of story, then the source line, and about 8 dp under it.
        near(30f, VodSizes.TITLE_TOP)
        near(122f, VodSizes.titleFactsTop())
        near(150f, VodSizes.titleStoryTop())
        near(35.2f, VodSizes.titleStoryH())
        near(195.2f, VodSizes.titleSourceTop())
        val sourceBottom = VodSizes.titleSourceTop() + VodSizes.SOURCE.lineSp
        near(208.4f, sourceBottom)
        near(VodSizes.TITLE_HEADER_H - 8f, sourceBottom, 0.5f)
        // Tuning round 1: the story a step under the facts (the page headers' own size), the source line a small quiet
        // caption; each a size down from the one above it.
        assertEquals(VodSizes.FACTS.copy(sp = 14f), VodSizes.TITLE_FACTS)
        assertEquals(VodSizes.STORY.copy(sp = 13f), VodSizes.TITLE_STORY)
        assertEquals(TextStyle(11f, TextStyle.Weight.REGULAR, TextStyle.Ink.OWN, 0xFFA9B1BA.toInt()), VodSizes.SOURCE)
        assertTrue(VodSizes.SOURCE.sp < VodSizes.TITLE_STORY.sp && VodSizes.TITLE_STORY.sp < VodSizes.TITLE_FACTS.sp)
        assertEquals(VodSizes.POD_META.color, VodSizes.SOURCE.color)
        // The source line stands apart from the story (further than the story is from the facts, and than its own
        // lines are from each other), and is as far above the season dropdown as the pods are below it.
        assertTrue(VodSizes.SOURCE_GAP > VodSizes.STORY_GAP && VodSizes.STORY_GAP > VodSizes.TITLE_STORY.lineGap)
        val aboveDropdown = VodSizes.TITLE_HEADER_H + VodSizes.SEASON_TOP - sourceBottom
        val belowDropdown = VodSizes.STAGE_ROW_TOP - VodSizes.SEASON_TOP - VodSizes.SEASON_H
        near(16f, belowDropdown)
        near(belowDropdown, aboveDropdown, 0.5f)
        // A name at its large size fits the title box; the story's measure stays inside the facts' width.
        assertTrue(VodSizes.TITLE_NAME.lineSp <= VodSizes.TITLE_BOX_H && VodSizes.LOGO_W <= VodSizes.TITLE_BOX_W)
        assertTrue(VodSizes.TITLE_TEXT_W <= VodSizes.TITLE_BOX_W)
    }

    @Test
    fun theStageHoldsTheColumnAndThePodsBesideIt() {
        // Tuning round 1: the column is clearly shorter in length than the 216 dp it was.
        assertEquals(176f, VodSizes.ACTIONS_W)
        // The column starts at the content's x; the pods a gap after it, at screen x 294 (40 dp further left).
        near(236f, VodSizes.stageX())
        near(294f, VodSizes.RAIL + VodSizes.stageX())
        // About two pods and part of a third fit from there to the screen's right edge, also with the first one grown.
        val room = VodSizes.SCREEN_W - (VodSizes.RAIL + VodSizes.stageX())
        val third = room - 2 * VodSizes.podPitch()
        assertTrue(third > 0.2f * VodSizes.POD_W && third < 0.5f * VodSizes.POD_W)
        assertTrue(third - VodSizes.podExtra() > 0.15f * VodSizes.POD_W)
        // A pod: a 16:9 still and its base, which holds its two lines.
        near(VodSizes.POD_W * 9f / 16f, VodSizes.POD_PICTURE_H, 0.5f)
        near(200f, VodSizes.podH())
        val lines = VodSizes.POD_TEXT_TOP + VodSizes.POD_TITLE.lineSp + VodSizes.POD_LINE_GAP + VodSizes.POD_META.lineSp
        assertTrue(lines + 6f <= VodSizes.POD_BASE_H)
        // The chosen pod grows about its middle: its growth and ring stay inside the room above and below, and the
        // ring inside the gap left of it (where the pods' box cuts).
        val grows = VodSizes.podH() * VodSizes.POD_GROW / 2f + VodSizes.RING
        assertTrue(grows <= VodSizes.POD_ROOM)
        assertTrue(VodSizes.RING < VodSizes.POD_GAP)
        near(220f, VodSizes.podsBandH())
        // The season dropdown's row ends above that room; the first button is level with the pods' top.
        assertTrue(VodSizes.SEASON_TOP + VodSizes.SEASON_H <= VodSizes.STAGE_ROW_TOP - VodSizes.POD_ROOM)
        // The column with all its buttons (a resume point: four rows, the cards' gap apart) is exactly as tall as the
        // pods beside it, top and bottom level; on the TV each button and gap is a whole number of pixels.
        near(200f, VodSizes.actionsH())
        near(VodSizes.podH(), VodSizes.actionsH())
        assertEquals(VodSizes.GAP, VodSizes.BUTTON_GAP)
        assertTrue(VodSizes.BUTTON_H in 38f..40f)
        near(79f, VodSizes.BUTTON_H * 2f, 0.0001f)
        // A focused pill (grown 1.04 about its middle) stays clear of the pods' box and of the row under it; a focused
        // circle (1.08) of the circle beside it and of the row above.
        assertTrue(VodSizes.ACTIONS_W * 0.02f < VodSizes.STAGE_GAP - VodSizes.POD_GAP)
        assertTrue(VodSizes.BUTTON_H * 0.02f < VodSizes.BUTTON_GAP / 2f)
        assertTrue(VodSizes.BUTTON_H * 0.04f < VodSizes.BUTTON_GAP / 2f)
        // The room for a pill's words: the column inside 12 dp at each end, after its icon and the gap when it has one.
        near(131f, VodSizes.pillTextW(icon = true))
        near(152f, VodSizes.pillTextW(icon = false))
        // The round buttons are the pills' family: as tall as one, from the column's left edge, the rows' gap between
        // them; both inside the column.
        assertTrue(2 * VodSizes.BUTTON_H + VodSizes.BUTTON_GAP <= VodSizes.ACTIONS_W)
        assertTrue(VodSizes.CIRCLE_ICON < VodSizes.BUTTON_H && VodSizes.BUTTON_ICON < VodSizes.BUTTON.lineSp)
        // A resuming pill's progress line runs along the pill's straight part (its ends are round, half its height in
        // radius), well inside the curve at its height (the pill is [cut] narrower there at each end than at its
        // middle), and clear of the words above it (a 13 sp line centred in the pill).
        val r = VodSizes.BUTTON_H / 2f
        val below = r - VodSizes.PILL_PROGRESS_BOTTOM - VodSizes.PILL_PROGRESS_H / 2f
        val cut = r - kotlin.math.sqrt(r * r - below * below)
        assertTrue(VodSizes.PILL_PROGRESS_SIDE >= r && cut + 12f < VodSizes.PILL_PROGRESS_SIDE)
        assertTrue(VodSizes.ACTIONS_W - 2 * VodSizes.PILL_PROGRESS_SIDE >= 0.75f * VodSizes.ACTIONS_W)
        val lineTop = VodSizes.BUTTON_H - VodSizes.PILL_PROGRESS_BOTTOM - VodSizes.PILL_PROGRESS_H
        val wordsBottom = r + VodSizes.BUTTON.lineSp / 2f
        assertTrue(wordsBottom + 4f <= lineTop)
        // The gap over the line and the room under it are alike (within a dp).
        near(VodSizes.PILL_PROGRESS_BOTTOM, lineTop - wordsBottom, 1f)
    }

    /**
     * Figtree 600's letter widths (res/font/figtree_600.ttf, thousandths of an em) for the letters, digits and marks
     * the buttons' words use: a line's width at a size is their sum (the TV's own spacing of some pairs only makes it
     * narrower). [theLetterWidthsAreTheFontsOwn] checks them against the font file.
     */
    private object Figtree600 {
        private const val CHARS = " ,0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz·"
        val UNITS = intArrayOf(
            241, 245,
            645, 419, 571, 551, 628, 578, 574, 548, 613, 574,
            699, 612, 723, 695, 590, 550, 750, 750, 282, 537, 644, 530, 853, 774, 783, 594, 785, 637, 610, 572, 705, 711, 975, 653, 630, 635,
            524, 592, 544, 591, 551, 384, 596, 565, 250, 282, 517, 236, 864, 564, 581, 597, 586, 363, 466, 393, 564, 542, 815, 512, 556, 483,
            258,
        )

        fun width(s: String, sp: Float): Float = s.sumOf { c ->
            val i = CHARS.indexOf(c)
            require(i >= 0) { "no width for '$c'" }
            UNITS[i]
        } * sp / 1000f

        /**
         * The same widths read from a TrueType [file]: each letter's glyph (the cmap's format 4 table) and that glyph's
         * advance (hmtx), in thousandths of the font's em (head). Null for a file without those tables.
         */
        fun fromFile(file: java.io.File): IntArray? {
            val b = java.nio.ByteBuffer.wrap(file.readBytes())
            fun u16(at: Int) = b.getShort(at).toInt() and 0xFFFF
            val tables = HashMap<String, Int>()
            for (i in 0 until u16(4)) {
                val at = 12 + 16 * i
                tables[String(ByteArray(4) { b.get(at + it) }, Charsets.ISO_8859_1)] = b.getInt(at + 8)
            }
            val head = tables["head"] ?: return null
            val hhea = tables["hhea"] ?: return null
            val hmtx = tables["hmtx"] ?: return null
            val cmap = tables["cmap"] ?: return null
            val em = u16(head + 18)
            val metrics = u16(hhea + 34)
            val sub = (0 until u16(cmap + 2)).map { cmap + b.getInt(cmap + 4 + 8 * it + 4) }.firstOrNull { u16(it) == 4 } ?: return null
            val seg2 = u16(sub + 6)
            val ends = sub + 14
            val starts = ends + seg2 + 2
            val deltas = starts + seg2
            val offsets = deltas + seg2
            fun glyph(c: Int): Int {
                for (s in 0 until seg2 / 2) {
                    if (c > u16(ends + 2 * s)) continue
                    val start = u16(starts + 2 * s)
                    if (c < start) return 0
                    val offset = u16(offsets + 2 * s)
                    val delta = u16(deltas + 2 * s)
                    if (offset == 0) return (c + delta) and 0xFFFF
                    val g = u16(offsets + 2 * s + offset + 2 * (c - start))
                    return if (g == 0) 0 else (g + delta) and 0xFFFF
                }
                return 0
            }
            return IntArray(CHARS.length) { i ->
                val advance = u16(hmtx + 4 * minOf(glyph(CHARS[i].code), metrics - 1))
                Math.round(advance * 1000f / em)
            }
        }
    }

    /** A button's words at the buttons' size, dp (on the TV an sp is a dp). */
    private fun wordsW(s: String): Float = Figtree600.width(s, VodSizes.BUTTON.sp)

    /** Every film's Resume, whatever is left of it: "Resume · 1m left" to "Resume · 4h 59m left". */
    private fun filmResumes(): List<String> = (0..4).flatMap { h ->
        (0..59).mapNotNull { m ->
            val left = listOfNotNull("${h}h".takeIf { h > 0 }, "${m}m".takeIf { m > 0 }).joinToString(" ")
            if (left.isEmpty()) null else "Resume · $left left"
        }
    }

    @Test
    fun everyStatesWordsFitTheColumn() {
        // The words each state of a pill shows, at the buttons' size, are narrower than the pill's room for them, with
        // 2 dp to spare for the TV's own rounding: nothing is cut short or made smaller.
        val withIcon = listOf(
            "Play", "Play S1 E1", "Play S12 E24", "Play S2024 E120",
            "Resume S2 E5", "Resume S12 E24", "Resume S40 E40", "Resume S2024 E200", "Resume Special 12",
            "Watch again · S1 E1", "Watch again · S4 E4",
            "Play from beginning",
            "Sources", "Sources · 18", "Sources · 400",
        ) + filmResumes()
        // Play that can't play yet has no icon, nor has the wait for the add-ons.
        val withoutIcon = listOf(
            "Play", "Coming soon", "Coming 2027", "Coming May 20", "Coming Mar 6, 2027", "Coming May 20, 2030", "Coming Sep 30, 2027",
            "Waiting", "Waiting · 0 s", "Waiting · 40 s", "Waiting · 120 s",
        )
        for (s in withIcon) assertTrue("$s: ${wordsW(s)}", wordsW(s) + 2f <= VodSizes.pillTextW(icon = true))
        for (s in withoutIcon) assertTrue("$s: ${wordsW(s)}", wordsW(s) + 2f <= VodSizes.pillTextW(icon = false))
        // The column is sized from the widest of them with balanced ends: beside the usual widest ("Play from
        // beginning" and its icon) 16 dp or more is left at each end, about half the pill's height; beside the very
        // widest (a film with over four hours left) never less than the 12 dp the fit keeps.
        fun ends(s: String) = (VodSizes.ACTIONS_W - VodSizes.BUTTON_ICON - VodSizes.BUTTON_ICON_GAP - wordsW(s)) / 2f
        near(118.4f, wordsW("Play from beginning"), 0.1f)
        assertTrue(ends("Play from beginning") >= 16f && ends("Resume · 1h 52m left") >= 16f)
        assertTrue(withIcon.minOf { ends(it) } >= VodSizes.ACTIONS_PAD)
        // And it could not be much shorter: 12 dp less and "Play from beginning" would not fit.
        assertTrue(wordsW("Play from beginning") + 2f > VodSizes.pillTextW(icon = true) - 12f)
        // Words no room is made for have a shorter form that fits, so the pill never cuts them or steps their size
        // down: a rewatch that starts far into a show's numbering. What an episode's Resume says when read out, and the
        // wait's own words, are wider than the pill: it shows their short forms.
        val room = VodSizes.pillTextW(icon = true)
        fun fit(full: String, compact: String) = TitleRules.fitWords(full, compact, room, ::wordsW)
        assertEquals(TitleRules.Fit("Watch again", small = false), fit("Watch again · S2024 E101", "Watch again"))
        assertEquals(TitleRules.Fit("Watch again · S1 E1", small = false), fit("Watch again · S1 E1", "Watch again"))
        assertEquals(TitleRules.Fit("Resume · 1h 52m left", small = false), fit("Resume · 1h 52m left", "Resume"))
        assertTrue(wordsW("Resume S2 E5 · 12m left") > room)
        assertTrue(wordsW("Waiting for MediaFusion · 9 s") > VodSizes.pillTextW(icon = false))
        // The step down is only ever a last resort, and still a readable size.
        assertTrue(VodSizes.BUTTON_LONG.sp < VodSizes.BUTTON.sp && VodSizes.BUTTON_LONG.sp >= 11f)
    }

    @Test
    fun theLetterWidthsAreTheFontsOwn() {
        // The widths above are the font file's (the table can't go stale unseen if the file is ever replaced).
        val file = listOf("src/main/res/font/figtree_600.ttf", "app/src/main/res/font/figtree_600.ttf")
            .map { java.io.File(it) }.firstOrNull { it.isFile }
        // Gradle runs the tests in the module's folder; anywhere the file can't be found, there's nothing to compare.
        org.junit.Assume.assumeTrue("the app's font is where the tests run", file != null)
        val widths = Figtree600.fromFile(file!!)
        assertTrue("the font has the tables the widths are read from", widths != null)
        assertArrayEquals(Figtree600.UNITS, widths)
    }

    @Test
    fun theSeasonListShowsSixRowsAndGlidesTheRest() {
        // Up to six rows show; fewer seasons make a shorter window.
        near(2 * 8f + 2 * 34f + 4f, VodSizes.seasonListH(2))
        near(2 * 8f + 6 * 34f + 5 * 4f, VodSizes.seasonListH(6))
        near(VodSizes.seasonListH(6), VodSizes.seasonListH(30))
        // Under the dropdown, a long list still ends inside the screen.
        val top = VodSizes.TITLE_HEADER_H + VodSizes.SEASON_TOP + VodSizes.SEASON_H + VodSizes.SEASON_LIST_GAP
        assertTrue(top + VodSizes.seasonListH(30) <= VodSizes.SCREEN_H - VodSizes.SEASON_LIST_INSET)
        // A short list never moves.
        assertEquals(0, VodSizes.seasonListFirst(3, 0, 4))
        assertEquals(0, VodSizes.seasonListFirst(5, 0, 6))
        // A long one moves only as far as the chosen row needs, and never past its ends.
        assertEquals(0, VodSizes.seasonListFirst(5, 0, 12))
        assertEquals(1, VodSizes.seasonListFirst(6, 0, 12))
        assertEquals(1, VodSizes.seasonListFirst(3, 1, 12))
        assertEquals(0, VodSizes.seasonListFirst(0, 1, 12))
        assertEquals(6, VodSizes.seasonListFirst(11, 4, 12))
        // Opened on season 9 of 12 (asked for with that row in the middle): rows 6 to 11 show.
        assertEquals(5, VodSizes.seasonListFirst(8, 8 - VodSizes.SEASON_LIST_ROWS / 2, 12))
        assertEquals(0, VodSizes.seasonListFirst(1, 1 - VodSizes.SEASON_LIST_ROWS / 2, 12))
        assertEquals(6, VodSizes.seasonListFirst(11, 11 - VodSizes.SEASON_LIST_ROWS / 2, 12))
    }

    @Test
    fun aLongSeasonIsHeldAWindowAtATime() {
        // A usual season: all its cards.
        assertEquals(12, VodSizes.episodeWindow(12, 0, 0))
        assertEquals(0, VodSizes.episodeWindow(0, 0, 0))
        // A daily show's 200: 40 at first, 40 more once focus is within 10 of the last held, never fewer than held.
        assertEquals(40, VodSizes.episodeWindow(200, 0, 0))
        assertEquals(40, VodSizes.episodeWindow(200, 29, 40))
        assertEquals(80, VodSizes.episodeWindow(200, 30, 40))
        assertEquals(80, VodSizes.episodeWindow(200, 31, 80))
        assertEquals(200, VodSizes.episodeWindow(200, 150, 0))
        assertEquals(100, VodSizes.episodeWindow(100, 95, 80))
        // The Play target far into the season: held from the start.
        assertTrue(VodSizes.episodeWindow(200, 120, 0) > 120 + VodSizes.POD_BIND_AFTER)
    }

    @Test
    fun theHeaderStartsWhereTheRowsDo() {
        // The text block and the action column start at the content's x (screen 94 dp), as the rows' strips (PAGE_X) do.
        assertEquals(VodSizes.PAGE_X, VodSizes.titleTextX())
        assertEquals(VodSizes.CONTENT_SCREEN_X, VodSizes.RAIL + VodSizes.titleTextX())
        // The facts row (the tab headers' width) then ends 24 dp from the screen's right edge; the title box inside it.
        assertEquals(VodSizes.SCREEN_W - VodSizes.RIGHT_MARGIN, VodSizes.RAIL + VodSizes.titleTextX() + VodSizes.tabHeaderW())
        assertTrue(VodSizes.TITLE_BOX_W <= VodSizes.tabHeaderW() && VodSizes.TITLE_TEXT_W <= VodSizes.tabHeaderW())
    }

    @Test
    fun aFilmsMoreLikeThisFitsBesideTheColumn() {
        // Its posters start level with the first button, and its band ends on screen.
        near(VodSizes.STAGE_ROW_TOP, VodSizes.filmMoreTop() + VodSizes.stripTop() + VodSizes.cardTop(RowStyle.POSTER))
        val end = VodSizes.TITLE_HEADER_H + VodSizes.filmMoreTop() + VodSizes.bandH(RowStyle.POSTER)
        assertTrue(end <= VodSizes.SCREEN_H)
        // The stage holds the band, and the whole column with the circles' names.
        assertTrue(VodSizes.filmStageH() >= VodSizes.filmMoreTop() + VodSizes.bandH(RowStyle.POSTER))
        assertTrue(VodSizes.filmStageH() >= VodSizes.STAGE_ROW_TOP + VodSizes.actionsH() + VodSizes.CIRCLE_NAME_ROOM)
        assertTrue(VodSizes.TITLE_HEADER_H + VodSizes.STAGE_ROW_TOP + VodSizes.actionsH() + VodSizes.CIRCLE_NAME_ROOM <= VodSizes.SCREEN_H)
    }

    @Test
    fun aShowGlidesUpForMoreLikeThis() {
        // More Like This starts under the stage: its title shows above the screen's bottom (a hint), its posters don't.
        val moreTop = VodSizes.TITLE_HEADER_H + VodSizes.showStageH()
        near(504f, moreTop)
        assertTrue(moreTop + VodSizes.ROW_TITLE_STYLE.lineSp < VodSizes.SCREEN_H)
        assertTrue(moreTop + VodSizes.stripTop() + VodSizes.cardTop(RowStyle.POSTER) >= VodSizes.SCREEN_H)
        val moreBottom = moreTop + VodSizes.bandH(RowStyle.POSTER)
        val rowTop = VodSizes.TITLE_HEADER_H + VodSizes.SEASON_TOP
        val g = VodSizes.glide(moreBottom, rowTop)
        // Glided: More Like This wholly on screen, and all the text above the season dropdown's row gone.
        assertTrue(moreBottom - g <= VodSizes.SCREEN_H)
        assertTrue(g >= rowTop - VodSizes.GLIDE_KEEP_TOP)
        assertTrue(VodSizes.titleSourceTop() + VodSizes.SOURCE.lineSp - g <= 0.01f)
        // A page with nothing below never glides past its own top.
        assertEquals(0f, VodSizes.glide(100f, 10f))
    }

    @Test
    fun theTitlePagesPicturesStayInBudget() {
        // The backdrop at half the screen in ARGB (its fade baked in); a pod's still at its shown size in RGB_565, and
        // the handful bound at once (the one before the chosen pod, it, and the ones after) well under 2 MB.
        assertEquals(2_073_600, VodSizes.TITLE_ART_W * VodSizes.TITLE_ART_H * 4)
        val still = Math.ceil(VodSizes.POD_W * 2.0).toInt() * Math.ceil(VodSizes.POD_PICTURE_H * 2.0).toInt() * 2
        assertEquals(312_576, still)
        assertTrue(still * (2 + VodSizes.POD_BIND_AFTER) < 2_000_000)
        // The logo is kept whole inside 800 x 180 px (the title box's 440 x 84 dp on the TV is 880 x 168), never enlarged.
        assertEquals(800 to 180, ImageFit.inside(1600, 360, VodSizes.LOGO_DECODE_W, VodSizes.LOGO_DECODE_H))
        assertEquals(800 to 160, ImageFit.inside(1000, 200, VodSizes.LOGO_DECODE_W, VodSizes.LOGO_DECODE_H))
        assertEquals(200 to 50, ImageFit.inside(200, 50, VodSizes.LOGO_DECODE_W, VodSizes.LOGO_DECODE_H))
        assertEquals(1 to 1, ImageFit.inside(0, 50, 400, 155))
    }
}
