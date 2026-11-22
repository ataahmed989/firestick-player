package app.fireplayer.vod.ui

import app.fireplayer.vod.RowStyle
import app.fireplayer.vod.data.Services
import app.fireplayer.vod.data.Service
import app.fireplayer.vod.data.Brand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

class VodSizesTest {

    private fun near(expected: Float, actual: Float, tolerance: Float = 0.05f) = assertEquals(expected, actual, tolerance)

    // ---- The title page's cards: the sizes of 2026-11-18, unchanged -------------------------------------------------

    @Test
    fun theTitlePagesPostersAreSixAndAHalfAcrossAtThreeByFour() {
        assertEquals(121f, VodSizes.POSTER_W)
        assertEquals(162f, VodSizes.POSTER_H)
        // 6.5 of them (with their gaps) fill the content's width from x 94 to the screen's edge.
        near(VodSizes.SCREEN_W - VodSizes.CONTENT_SCREEN_X, 6.5f * (VodSizes.across(6.5f) + VodSizes.GAP) - VodSizes.GAP)
        // Grown 16%: 141 x 188 dp (design-brief §6.3, whole dp rounded up, as the pictures are decoded).
        assertEquals(141, ceil(VodSizes.across(6.5f) * 1.16f).toInt())
        assertEquals(188, ceil(VodSizes.across(6.5f) * 4f / 3f * 1.16f).toInt())
    }

    @Test
    fun theTitlePagesStillsAreFourAndAHalfAcrossAtSixteenByNine() {
        assertEquals(182f, VodSizes.WIDE_W)
        assertEquals(102f, VodSizes.WIDE_H)
        assertEquals(211, ceil(VodSizes.across(4.5f) * 1.16f).toInt())
        assertEquals(119, ceil(VodSizes.across(4.5f) * 9f / 16f * 1.16f).toInt())
        // An episode card keeps its size whatever the row's.
        assertEquals(VodSizes.WIDE_W, VodSizes.cardW(RowStyle.EPISODE, large = true))
        assertEquals(VodSizes.WIDE_H, VodSizes.pictureH(RowStyle.EPISODE, large = true))
    }

    @Test
    fun theTitlePagesPitchesBandsAndFollowerShifts() {
        near(135f, VodSizes.pitch(RowStyle.POSTER))
        near(196f, VodSizes.pitch(RowStyle.WIDE))
        near(195.32f, VodSizes.pitch(RowStyle.RANKED), 0.01f)
        near(19.36f, VodSizes.extra(RowStyle.POSTER), 0.001f)
        near(29.12f, VodSizes.extra(RowStyle.WIDE), 0.001f)
        near(231.92f, VodSizes.bandH(RowStyle.POSTER), 0.01f)
        near(231.92f, VodSizes.bandH(RowStyle.RANKED), 0.01f)
        near(162.32f, VodSizes.bandH(RowStyle.WIDE), 0.01f)
        near(81.44f, VodSizes.bandH(RowStyle.GENRES), 0.01f)
        near(12.96f, VodSizes.cardTop(RowStyle.POSTER), 0.001f)
        near(8.16f, VodSizes.cardTop(RowStyle.WIDE), 0.001f)
        near(60.32f, VodSizes.slot(RowStyle.RANKED), 0.01f)
    }

    // ---- The browse pages' cards (the brief of 2026-11-19) ---------------------------------------------------------

    @Test
    fun continueWatchingIsAboutThreeAndAThirdAcross() {
        assertEquals(253f, VodSizes.BROWSE_WIDE_W)
        assertEquals(142f, VodSizes.BROWSE_WIDE_H)
        // Three whole cards and part of a fourth, with nothing chosen and with the first one grown.
        val content = VodSizes.SCREEN_W - VodSizes.CONTENT_SCREEN_X
        val pitch = VodSizes.pitch(RowStyle.WIDE, large = true)
        near(267f, pitch)
        val fourth = 3 * pitch
        assertTrue(fourth + 20f < content && fourth + VodSizes.BROWSE_WIDE_W > content)
        val fourthGrown = fourth + VodSizes.extra(RowStyle.WIDE, large = true)
        near(40.48f, VodSizes.extra(RowStyle.WIDE, large = true), 0.001f)
        assertTrue(fourthGrown + 20f < content)
    }

    @Test
    fun postersAreLargerByTheSameFactorAtThreeByFour() {
        assertEquals(169f, VodSizes.BROWSE_POSTER_W)
        assertEquals(226f, VodSizes.BROWSE_POSTER_H)
        // The same factor as the wide cards': 4.5 / 3.3 of the old sizes, within a dp or two.
        val factor = VodSizes.WIDE_ACROSS / VodSizes.BROWSE_WIDE_ACROSS
        near(VodSizes.POSTERS_ACROSS / factor, VodSizes.BROWSE_POSTERS_ACROSS, 0.05f)
        near(VodSizes.BROWSE_POSTER_W / VodSizes.POSTER_W, VodSizes.BROWSE_WIDE_W / VodSizes.WIDE_W, 0.02f)
        near(183f, VodSizes.pitch(RowStyle.POSTER, large = true))
        near(27.04f, VodSizes.extra(RowStyle.POSTER, large = true), 0.001f)
    }

    @Test
    fun rankedNumbersGrowWithTheirPosters() {
        near(84.16f, VodSizes.slot(RowStyle.RANKED, large = true), 0.01f)
        assertEquals(0f, VodSizes.slot(RowStyle.POSTER, large = true))
        near(267.16f, VodSizes.pitch(RowStyle.RANKED, large = true), 0.01f)
        near(156.1f, VodSizes.BROWSE_POSTER_H * VodSizes.RANK_TEXT, 0.01f)
        // Glass Home: slot 124 of a 333 poster; text 230.
        near(124f / 333f, VodSizes.RANK_SLOT, 0.0001f)
    }

    @Test
    fun browseBandHeights() {
        near(306.16f, VodSizes.bandH(RowStyle.POSTER, true, VodSizes.ROW_GAP, large = true), 0.01f)
        near(306.16f, VodSizes.bandH(RowStyle.RANKED, true, VodSizes.ROW_GAP, large = true), 0.01f)
        near(208.72f, VodSizes.bandH(RowStyle.WIDE, true, VodSizes.ROW_GAP, large = true), 0.01f)
        // Chips keep their size.
        near(81.44f, VodSizes.bandH(RowStyle.GENRES, true, VodSizes.ROW_GAP, large = true), 0.01f)
        // The chosen card's growth is centred in the strip.
        near(18.08f, VodSizes.cardTop(RowStyle.POSTER, large = true), 0.001f)
        near(11.36f, VodSizes.cardTop(RowStyle.WIDE, large = true), 0.001f)
    }

    @Test
    fun picturesAreHeldForTheCardsThatShowAndOneAhead() {
        assertEquals(4, VodSizes.boundAfter(RowStyle.WIDE, large = true))
        assertEquals(5, VodSizes.boundAfter(RowStyle.POSTER, large = true))
        assertEquals(4, VodSizes.boundAfter(RowStyle.RANKED, large = true))
        assertEquals(7, VodSizes.boundAfter(RowStyle.POSTER, large = false))
        assertEquals(3, VodSizes.boundAfter(RowStyle.POSTER, large = true, most = 3))
    }

    // ---- The page ----------------------------------------------------------------------------------------------------

    @Test
    fun contentStartsAt94OnScreenAnd36InThePage() {
        assertEquals(58f, VodSizes.RAIL)
        assertEquals(36f, VodSizes.PAGE_X)
    }

    @Test
    fun homeHeaderAndWindowFillTheWidthWithTheRightMargin() {
        // Home's banner reaches out towards the rail: it starts 19 dp from the rail's floating window (which ends at
        // 51), its words (24 dp in) in line with the rows' titles at 94; the rows stay where they were.
        assertEquals(51f, VodSizes.RAIL_WINDOW_END)
        assertEquals(12f, VodSizes.HOME_HEADER_X)
        assertEquals(70f, VodSizes.HOME_HEADER_SCREEN_X)
        assertEquals(19f, VodSizes.HOME_HEADER_SCREEN_X - VodSizes.RAIL_WINDOW_END)
        assertEquals(VodSizes.CONTENT_SCREEN_X, VodSizes.HOME_HEADER_SCREEN_X + VodSizes.HEADER_INSET)
        // Wider (560 dp, it was 496), Recent channels narrower (290, it was 330) and pushed right.
        assertEquals(560f, VodSizes.homeHeaderW())
        assertEquals(290f, VodSizes.WINDOW_W)
        assertEquals(VodSizes.SCREEN_W, VodSizes.HOME_HEADER_SCREEN_X + VodSizes.homeHeaderW() + 16f + VodSizes.WINDOW_W + 24f)
        // The window's left edge 16 dp past the banner, its right edge 24 dp from the screen's (page x + rail = screen x).
        assertEquals(646f, VodSizes.windowX() + VodSizes.RAIL)
        assertEquals(936f, VodSizes.windowX() + VodSizes.WINDOW_W + VodSizes.RAIL)
        assertEquals(842f, VodSizes.tabHeaderW())
    }

    @Test
    fun recentChannelsStillHoldsItsRowsWhole() {
        // A row: the window's side padding, the row's own, the logo tile, the gap, then the two lines of text (cut
        // short with an ellipsis, never clipped); the scrollbar keeps to the window's right padding.
        val row = VodSizes.WINDOW_W - 2 * (VodSizes.RECENT_PAD_X - VodSizes.RECENT_ROW_PAD)
        val text = row - 2 * VodSizes.RECENT_ROW_PAD - VodSizes.RECENT_LOGO_W - VodSizes.RECENT_LOGO_GAP
        assertEquals(188f, text)
        assertTrue(VodSizes.RECENT_BAR_RIGHT + VodSizes.RECENT_BAR_W <= VodSizes.RECENT_PAD_X - VodSizes.RECENT_ROW_PAD)
    }

    @Test
    fun homesBannerShowsItsPictureWholeBesideTheWords() {
        val home = VodSizes.homeHeaderW()
        // The picture in a box of its own shape (16:9) at the card's right, its left edge eased into the dark side.
        near(405.33f, VodSizes.backdropW(home), 0.01f)
        assertTrue(VodSizes.backdropW(home) < home)
        val boxLeft = home - VodSizes.backdropW(home)
        near(154.67f, boxLeft, 0.01f)
        near(316.8f, VodSizes.pictureClearX(home), 0.01f)
        // The words stay on the dark side and the eased edge, never over the clear picture.
        assertTrue(VodSizes.HEADER_INSET + VodSizes.storyW(home) <= VodSizes.pictureClearX(home) + 0.001f)
        // The backing behind the dark side reaches under the box (VodHeader), and the easing is the shared one.
        assertEquals(0.40f, VodSizes.BANNER_FADE_LEFT_TO)
        // The wide cards are as they were: the same box, the same words.
        near(561.6f, VodSizes.backdropW(VodSizes.tabHeaderW()), 0.001f)
        assertEquals(400f, VodSizes.storyW(VodSizes.tabHeaderW()))
    }

    @Test
    fun theBannerIsTallerAndTheFirstRowStartsUnderIt() {
        assertEquals(228f, VodSizes.HEADER_H)
        assertEquals(244f, VodSizes.firstRowTop())
        val firstRowScreen = VodSizes.VIEWPORT_TOP + VodSizes.firstRowTop()
        assertEquals(288f, firstRowScreen)
        assertEquals(496f, VodSizes.VIEWPORT_H)
        // Continue Watching under the banner shows whole, its chosen card grown; the next row's title shows under it,
        // its posters running on below the screen (nothing shrinks to fit).
        val wide = VodSizes.rowShownH(RowStyle.WIDE, header = true, large = true)
        assertTrue(firstRowScreen + wide <= VodSizes.SCREEN_H)
        val next = firstRowScreen + VodSizes.bandH(RowStyle.WIDE, true, VodSizes.ROW_GAP, large = true)
        assertTrue(next + VodSizes.ROW_TITLE_STYLE.lineSp < VodSizes.SCREEN_H)
        // Its title shows above the part of the screen a TV may cut off (it ends at about 513 dp).
        assertTrue(next + VodSizes.ROW_TITLE <= VodSizes.SCREEN_H - VodSizes.TV_SAFE)
        // Recent channels' top is the banner's, and it is as tall.
        assertEquals(VodSizes.HEADER_H, VodSizes.RECENT_PAD_TOP + VodSizes.RECENT_HEAD_H + VodSizes.RECENT_FIRST_TOP + VodSizes.recentListH() + VodSizes.RECENT_PAD_BOTTOM)
        assertTrue(next + VodSizes.bandH(RowStyle.POSTER, true, VodSizes.ROW_GAP, large = true) > VodSizes.SCREEN_H)
    }

    @Test
    fun aFirstRowOfPostersMovesUpJustFarEnoughToShowWhole() {
        val top = VodSizes.firstRowTop()
        val posters = VodSizes.rowShownH(RowStyle.POSTER, header = true, large = true)
        near(292.16f, posters, 0.01f)
        near(40.16f, RowMath.pageTarget(0, 0, posters, VodSizes.VIEWPORT_H) { top }, 0.01f)
        // Most of the banner still shows (188 dp of it, as before it was taller), its words and buttons with it: the
        // words, which start nearer the card's top than that, move down to stay whole under the cut, and still keep
        // off the buttons.
        assertTrue(VodSizes.HEADER_TOP < 40.16f)
        val shift = VodSizes.headerWordsShift(40.16f)
        near(VodSizes.HEADER_TOP_KEEP, VodSizes.HEADER_TOP + shift - 40.16f, 0.01f)
        assertTrue(VodSizes.headerWordsEnd() + shift <= VodSizes.headerButtonsTop() - VodSizes.HEADER_WORDS_MIN_GAP)
        val wide = VodSizes.rowShownH(RowStyle.WIDE, header = true, large = true)
        assertEquals(0f, RowMath.pageTarget(0, 0, wide, VodSizes.VIEWPORT_H) { top })
    }

    @Test
    fun scrolledDownAChosenRowAndMostOfTheNextShow() {
        val second = VodSizes.VIEWPORT_TOP + VodSizes.bandH(RowStyle.POSTER, true, VodSizes.ROW_GAP, large = true)
        assertTrue(second < VodSizes.SCREEN_H)
        // The next row's posters start on screen and run on below it.
        val posters = second + VodSizes.stripTop() + VodSizes.cardTop(RowStyle.POSTER, large = true)
        assertTrue(posters + 100f < VodSizes.SCREEN_H && posters + VodSizes.BROWSE_POSTER_H > VodSizes.SCREEN_H)
    }

    @Test
    fun headerParts() {
        // Tuning round 1: the words at the top left, the buttons at the bottom left, one margin round them.
        assertEquals(24f, VodSizes.HEADER_INSET)
        assertEquals(VodSizes.HEADER_INSET, VodSizes.HEADER_TOP)
        assertEquals(VodSizes.HEADER_INSET, VodSizes.HEADER_BUTTONS_BOTTOM)
        // The block: the title's box (24..76), the facts (83..98), two lines of story (103..138.2).
        near(35.2f, VodSizes.headerStoryH(), 0.001f)
        near(114.2f, VodSizes.headerWordsH(), 0.001f)
        near(138.2f, VodSizes.headerWordsEnd(), 0.001f)
        // The buttons (170..204) at the bottom left, clear of it, and above the dots (214..219).
        assertEquals(170f, VodSizes.headerButtonsTop())
        assertTrue(VodSizes.headerButtonsTop() - VodSizes.headerWordsEnd() >= 16f)
        assertTrue(VodSizes.HEADER_BUTTONS_BOTTOM >= VodSizes.DOTS_BOTTOM + VodSizes.DOTS_H + 8f)
        // A focused button grows 4% about its centre, with a glow 12.5 dp out all round and a shadow that falls
        // 22.5 dp under it (GlassDrawable's FOCUS_GLOW and FOCUS_SHADOW). All of it lies inside the buttons' row, whose
        // padding is the card's margins and this room above: nothing of a focused button is cut.
        val grow = app.fireplayer.shell.Tokens.GROW_PILL - 1f
        val widest = 140f
        assertTrue(widest * grow / 2f + 12.5f <= VodSizes.HEADER_INSET)
        assertTrue(VodSizes.HEADER_BUTTON_H * grow / 2f + 12.5f <= VodSizes.HEADER_BUTTON_ROOM)
        assertTrue(VodSizes.HEADER_BUTTON_H * grow / 2f + 22.5f <= VodSizes.HEADER_BUTTONS_BOTTOM)
        // That room above the buttons is still below the words.
        assertTrue(VodSizes.headerButtonsTop() - VodSizes.HEADER_BUTTON_ROOM >= VodSizes.headerWordsEnd())
        // The backdrop, right-aligned: the wide cards' 561.6 dp box; Home's the picture's own shape.
        near(405.33f, VodSizes.backdropW(VodSizes.homeHeaderW()), 0.01f)
        near(561.6f, VodSizes.backdropW(VodSizes.tabHeaderW()), 0.001f)
        // The words' column: about 293 dp on Home (ending where its picture shows clear), 400 on the wide cards.
        near(292.8f, VodSizes.storyW(VodSizes.homeHeaderW()), 0.01f)
        assertEquals(400f, VodSizes.storyW(VodSizes.tabHeaderW()))
        assertTrue(VodSizes.HEADER_INSET + VodSizes.storyW(VodSizes.homeHeaderW()) <= VodSizes.homeHeaderW() - VodSizes.HEADER_CLEAR)
    }

    @Test
    fun theBannersTitleIsItsLogoInASmallBoxAtTheTopLeft() {
        // Up to 205 x 52 dp on Home, 240 x 52 on the wide cards: clearly smaller than the 40 sp name it replaces
        // (a 48 dp line as wide as the name).
        near(204.96f, VodSizes.headerLogoW(VodSizes.homeHeaderW()), 0.01f)
        assertEquals(240f, VodSizes.headerLogoW(VodSizes.tabHeaderW()))
        assertEquals(52f, VodSizes.HEADER_TITLE_H)
        assertTrue(VodSizes.headerLogoW(VodSizes.tabHeaderW()) <= VodSizes.storyW(VodSizes.tabHeaderW()))
        // Kept in memory no larger than it's shown: under 200 KB each at the Max's 2 px per dp.
        val bytes = (VodSizes.headerLogoW(VodSizes.tabHeaderW()) * 2f) * (VodSizes.HEADER_TITLE_H * 2f) * 4f
        assertTrue(bytes < 200f * 1024f)
        // Decoded with room for the empty margins logos come with.
        assertTrue(VodSizes.HEADER_LOGO_DECODE >= 2)
        // The box waits for a logo only briefly, and no longer than a followed card's picture takes to come.
        assertTrue(VodSizes.HEADER_LOGO_WAIT_MS in 200L..VodSizes.FOLLOW_ART_MS)
    }

    @Test
    fun aTitleWithoutALogoShowsItsNameSmallerThanBefore() {
        assertEquals(28f, VodSizes.headerTitleSp(measured = 200f, room = 300f))
        assertEquals(28f, VodSizes.headerTitleSp(measured = 300f, room = 300f))
        assertEquals(21f, VodSizes.headerTitleSp(measured = 300.5f, room = 300f))
        assertTrue(VodSizes.HEADER_TITLE_SP < 40f)
        // One line of the large size, or two of the small, fit the title's box (a line is 1.2 x its size).
        assertTrue(VodSizes.HEADER_TITLE.lineSp <= VodSizes.HEADER_TITLE_H)
        assertTrue(VodSizes.HEADER_TITLE_LINES * VodSizes.HEADER_TITLE_LONG.lineSp <= VodSizes.HEADER_TITLE_H)
    }

    @Test
    fun theWordsMoveDownOnlyWhileThePageHidesTheCardsTop() {
        assertEquals(0f, VodSizes.headerWordsShift(0f))
        // Nothing until the cut comes near the words.
        assertEquals(0f, VodSizes.headerWordsShift(VodSizes.HEADER_TOP - VodSizes.HEADER_TOP_KEEP))
        near(4f, VodSizes.headerWordsShift(VodSizes.HEADER_TOP - VodSizes.HEADER_TOP_KEEP + 4f), 0.001f)
        // Never into the buttons, however far the page goes (the banner is sliding away by then).
        val most = VodSizes.headerWordsShift(10_000f)
        near(VodSizes.headerButtonsTop() - VodSizes.HEADER_WORDS_MIN_GAP, VodSizes.headerWordsEnd() + most, 0.001f)
        var last = 0f
        for (hidden in 0..300) {
            val shift = VodSizes.headerWordsShift(hidden.toFloat())
            assertTrue(shift >= last && shift <= most)
            last = shift
        }
    }

    @Test
    fun theFactsLineShowsWholeFactsOnly() {
        val measure: (String) -> Float = { it.length * 6f }
        val line = "S1 E1 · 22m left · 2005–2008 · Animation · Action · ★ 9.3 · 23 min"
        // Room for all of it: as it is.
        assertEquals(line, VodSizes.headerFacts(line, 1_000f, measure))
        // Home's 300 dp column: the last facts go, never half of one.
        assertEquals("S1 E1 · 22m left · 2005–2008 · Animation · Action", VodSizes.headerFacts(line, 300f, measure))
        assertEquals("S1 E1 · 22m left", VodSizes.headerFacts(line, 100f, measure))
        // The first stays even when it's too wide (the view cuts it short); a line of one fact is left alone.
        assertEquals("S1 E1", VodSizes.headerFacts(line, 10f, measure))
        assertEquals("2024", VodSizes.headerFacts("2024", 10f, measure))
        assertEquals("", VodSizes.headerFacts("", 300f, measure))
    }

    @Test
    fun theBannersShadingEasesOutToNothing() {
        val steps = VodSizes.HEADER_SHADE_ALPHAS
        // Strongest round the words, never rising on the way out, clear at its end.
        assertEquals(VodSizes.HEADER_SHADE_MAX, steps.first())
        assertEquals(0, steps.last())
        for (i in 1 until steps.size) assertTrue(steps[i] <= steps[i - 1])
        // Eased both ways: no step bigger than an eighth of the whole (no seam), and it starts and ends gently (no line
        // where the shade begins to fall or where it ends).
        for (i in 1 until steps.size) assertTrue(steps[i - 1] - steps[i] <= VodSizes.HEADER_SHADE_MAX / 8)
        assertTrue(steps[steps.size - 2] <= 6)
        val hold = (VodSizes.HEADER_SHADE_HOLD * VodSizes.HEADER_SHADE_STEPS).toInt()
        assertEquals(VodSizes.HEADER_SHADE_MAX, steps[hold])
        assertTrue(steps[hold] - steps[hold + 1] <= 6)
        val below = VodSizes.HEADER_SHADE_BOTTOM_ALPHAS
        assertEquals(0, below.first())
        for (i in 1 until below.size) assertTrue(below[i] > below[i - 1])
        // Lighter and lower than before (it was 55% from half way down): the picture's lower half shows.
        assertTrue(below.last() <= 0x50)
        assertTrue(VodSizes.HEADER_SHADE_BOTTOM_FROM >= 0.6f)
        // A wide card's gradient the same way: clear at its start, darkest at the bottom.
        assertEquals(0, VodSizes.WIDE_SCRIM_ALPHAS.first())
        assertEquals(VodSizes.SCRIM_BOTTOM ushr 24, VodSizes.WIDE_SCRIM_ALPHAS.last())
        // Its words: the name, the smaller caption line 2 dp under it, above the slim bar.
        assertTrue(VodSizes.CARD_CAPTION.sp < VodSizes.CARD_NAME.sp)
        assertTrue(VodSizes.CARD_BOTTOM_BAR >= VodSizes.WIDE_BAR_BOTTOM + VodSizes.BAR_H + 6f)
        val words = VodSizes.CARD_BOTTOM_BAR + VodSizes.CARD_CAPTION.lineSp + VodSizes.CARD_LINE_GAP + VodSizes.CARD_NAME.lineSp
        assertTrue(words < VodSizes.BROWSE_WIDE_H * (1f - VodSizes.WIDE_SCRIM_STOPS.first()))
        assertEquals(VodSizes.WIDE_SCRIM_STOPS.size, VodSizes.WIDE_SCRIM_ALPHAS.size)
        assertEquals(1f, VodSizes.WIDE_SCRIM_STOPS.last())
        // The picture above the words is clear: the gradient starts two fifths of the way down, or lower.
        assertTrue(VodSizes.WIDE_SCRIM_STOPS.first() >= 0.4f)
    }

    @Test
    fun theBannersShadeIsRoundTheWordsAndLeavesThePictureClear() {
        val home = VodSizes.homeHeaderW()
        val wordsEnd = VodSizes.HEADER_INSET + VodSizes.storyW(home)
        fun shade(x: Float, y: Float) = VodSizes.headerShade(x, y, home)
        // Behind the logo and the words' start: dark enough for white and pale ink.
        assertTrue(shade(VodSizes.HEADER_INSET, VodSizes.HEADER_TOP) >= 0.75f)
        assertTrue(shade(VodSizes.HEADER_INSET + 100f, VodSizes.HEADER_TOP + 26f) >= 0.7f)
        assertTrue(shade(VodSizes.HEADER_INSET, VodSizes.headerWordsEnd()) >= 0.7f)
        // Behind the buttons at the bottom left (their glass has its own tint): Watch now, then Details' far end.
        val buttonsY = VodSizes.headerButtonsTop() + VodSizes.HEADER_BUTTON_H / 2f
        assertTrue(shade(VodSizes.HEADER_INSET + 58f, buttonsY) >= 0.5f)
        assertTrue(shade(VodSizes.HEADER_INSET + 196f, buttonsY) >= 0.25f)
        // Where the story ends it is fading, though still there (the words have their own soft shadow).
        val end = shade(wordsEnd, VodSizes.headerWordsEnd() - 9f)
        assertTrue(end > 0.15f && end < 0.3f)
        // The picture's subject, just right of the words, is clearer than it was (27% there before), and from there on
        // the picture is as good as bare: its right side, its top right corner, its lower middle (62% before).
        assertTrue(shade(340f, 100f) < 0.24f)
        assertTrue(shade(400f, 100f) < 0.1f)
        assertTrue(shade(450f, 60f) < 0.05f)
        assertTrue(shade(home, 0f) < 0.02f)
        assertEquals(0f, shade(home, VodSizes.HEADER_H / 2f))
        assertTrue(shade(300f, 180f) < 0.2f)
        // It only ever falls away from the words: along a row, and down a column right of the buttons.
        for (y in listOf(30f, 100f, 180f)) {
            var last = 1f
            for (x in 0..home.toInt() step 8) {
                val a = shade(x.toFloat(), y)
                assertTrue(a <= last + 0.0001f)
                last = a
            }
        }
        // The wide cards (Movies, TV Shows, My List): the same shade round their wider block, gone before the part of
        // the picture that shows whole (its left edge is eased away up to 40% of its box).
        val wide = VodSizes.tabHeaderW()
        val pictureFrom = wide - VodSizes.backdropW(wide) * (1f - VodSizes.BANNER_FADE_LEFT_TO)
        near(505f, pictureFrom, 0.1f)
        assertTrue(VodSizes.headerShade(pictureFrom + 60f, 100f, wide) < 0.06f)
        assertEquals(0f, VodSizes.headerShade(wide - 100f, 100f, wide))
        assertTrue(VodSizes.headerShade(VodSizes.HEADER_INSET + 100f, VodSizes.HEADER_TOP + 26f, wide) >= 0.7f)
        // The same place in the block, the same shade, whichever the card.
        near(shade(wordsEnd, 100f), VodSizes.headerShade(VodSizes.HEADER_INSET + VodSizes.storyW(wide), 100f, wide), 0.001f)
    }

    @Test
    fun myListsBannerLeavesARowOfPostersWholeUnderIt() {
        assertEquals(246f, VodSizes.fixedRowsTop())
        val room = VodSizes.SCREEN_H - VodSizes.fixedRowsTop()
        assertTrue(room >= VodSizes.rowShownH(RowStyle.POSTER, header = true, large = true))
        // Without buttons its words (the same block at its top left) end well inside the shorter card.
        assertTrue(VodSizes.headerWordsEnd() <= VodSizes.MY_LIST_HEADER_H - VodSizes.HEADER_INSET)
    }

    @Test
    fun recentChannelsIsAsTallAsTheBanner() {
        val h = VodSizes.RECENT_PAD_TOP + VodSizes.RECENT_HEAD_H + VodSizes.RECENT_FIRST_TOP + VodSizes.recentListH() + VodSizes.RECENT_PAD_BOTTOM
        assertEquals(VodSizes.HEADER_H, h)
        // A row holds its logo tile and its two lines.
        assertTrue(VodSizes.RECENT_LOGO_H + 2 * VodSizes.RECENT_FOCUS_INSET < VodSizes.RECENT_ROW_H)
        assertTrue(VodSizes.RECENT_NAME.lineSp + VodSizes.RECENT_LINE_GAP + VodSizes.RECENT_NOW.lineSp < VodSizes.RECENT_ROW_H - 2 * VodSizes.RECENT_FOCUS_INSET)
        // The programme line is the smaller one.
        assertTrue(VodSizes.RECENT_NOW.sp < VodSizes.RECENT_NAME.sp)
    }

    @Test
    fun recentChannelsMovesOnlyAsFarAsTheChosenRowNeeds() {
        // Three show: rows 0..2 need no move.
        assertEquals(0, VodSizes.recentFirstShown(at = 0, first = 0, n = 8))
        assertEquals(0, VodSizes.recentFirstShown(at = 2, first = 0, n = 8))
        // Down past the last one showing: one row at a time.
        assertEquals(1, VodSizes.recentFirstShown(at = 3, first = 0, n = 8))
        assertEquals(5, VodSizes.recentFirstShown(at = 7, first = 4, n = 8))
        // Back up inside what shows: no move; above it: that row becomes the first.
        assertEquals(5, VodSizes.recentFirstShown(at = 6, first = 5, n = 8))
        assertEquals(4, VodSizes.recentFirstShown(at = 4, first = 5, n = 8))
        // A list that got shorter: never past its end; three or fewer never move.
        assertEquals(1, VodSizes.recentFirstShown(at = 3, first = 5, n = 4))
        assertEquals(0, VodSizes.recentFirstShown(at = 2, first = 3, n = 3))
        assertEquals(0, VodSizes.recentFirstShown(at = 0, first = 0, n = 0))
    }

    @Test
    fun gridIsFiveAcrossTheContentsWidth() {
        assertEquals(154f, VodSizes.GRID_POSTER_W)
        assertEquals(205f, VodSizes.GRID_POSTER_H)
        // It ends at the content's right margin, as the banner does.
        assertEquals(VodSizes.tabHeaderW(), VodSizes.gridW())
        assertEquals(172f, VodSizes.gridPitchX())
        assertEquals(235f, VodSizes.gridPitchY())
        // The grown poster (about its middle) and its ring stay inside the gaps.
        assertTrue(VodSizes.GRID_POSTER_W * VodSizes.GROW / 2 + VodSizes.RING < VodSizes.GRID_GAP)
        assertTrue(VodSizes.GRID_POSTER_H * VodSizes.GROW / 2 + VodSizes.RING < VodSizes.GRID_ROW_GAP)
        // Two rows of posters fill the viewport under the name line.
        assertTrue(VodSizes.GRID_TOP + 2 * VodSizes.gridPitchY() <= VodSizes.VIEWPORT_H)
        // The first row's chosen poster, grown, and its ring show whole under the name line.
        assertTrue(VodSizes.GRID_TOP >= VodSizes.GRID_POSTER_H * VodSizes.GROW / 2 + VodSizes.RING)
    }

    @Test
    fun `the five logo cards all show and stay in place`() {
        val style = RowStyle.LOGO
        assertTrue(VodSizes.still(style))
        assertFalse(VodSizes.still(RowStyle.POSTER))
        // Five share the content's width, the normal gap apart: the last ends at the right margin.
        val w = VodSizes.cardW(style)
        near(VodSizes.tabHeaderW(), VodSizes.LOGO_ACROSS * w + (VodSizes.LOGO_ACROSS - 1) * VodSizes.GAP, 0.01f)
        near(VodSizes.SCREEN_W - VodSizes.RIGHT_MARGIN, VodSizes.CONTENT_SCREEN_X + 4 * VodSizes.pitch(style) + w, 0.01f)
        // Nothing moves along for the chosen card: it grows about its middle, inside the gaps, with its ring.
        assertEquals(0f, VodSizes.extra(style))
        val out = w * VodSizes.grow(style) / 2 + VodSizes.RING
        assertTrue(out < VodSizes.GAP)
        // Never under the rail, never off the screen's right edge.
        assertTrue(VodSizes.CONTENT_SCREEN_X - out > VodSizes.RAIL)
        assertTrue(VodSizes.SCREEN_W - VodSizes.RIGHT_MARGIN + out < VodSizes.SCREEN_W)
        // A row without a title keeps room for the ring above its cards.
        assertTrue(VodSizes.stripTop(header = false) >= VodSizes.RING)
        near(VodSizes.stripTop(), VodSizes.stripTop(header = true), 0.001f)
    }

    @Test
    fun `Home's six service cards fit across in fixed slots, in line with the rows`() {
        val style = RowStyle.SERVICES
        assertEquals(Service.entries.size, VodSizes.SERVICES_ACROSS)
        assertTrue(VodSizes.still(style))
        assertTrue(VodSizes.isLogo(style))
        assertEquals(0f, VodSizes.extra(style))
        val w = VodSizes.cardW(style)
        val n = VodSizes.SERVICES_ACROSS
        // The first card is in line with the rows below it (their first card, at the content's left edge).
        near(0f, VodSizes.logoSlotLeft(style, 0), 0.001f)
        // Five gaps, all the rows' gap: each card the same width, one pitch from the last.
        for (i in 1 until n) near(VodSizes.GAP, VodSizes.logoSlotLeft(style, i) - (VodSizes.logoSlotLeft(style, i - 1) + w), 0.001f)
        // All six fully across the content's width: the last ends at the rows' right margin.
        val lastRight = VodSizes.CONTENT_SCREEN_X + VodSizes.logoSlotLeft(style, n - 1) + w
        near(VodSizes.SCREEN_W - VodSizes.RIGHT_MARGIN, lastRight, 0.01f)
        near(VodSizes.tabHeaderW(), n * w + (n - 1) * VodSizes.GAP, 0.01f)
        // Room for the chosen card's growth and ring at both ends: clear of the rail's window on the left, and of the
        // screen's edge on the right with some space still to spare.
        val reach = VodSizes.logoReach(style)
        assertTrue(VodSizes.CONTENT_SCREEN_X - reach > VodSizes.RAIL_WINDOW_END)
        assertTrue(VodSizes.SCREEN_W - (lastRight + reach) >= 12f)
        // ...and between two cards: the chosen one's ring never touches the next.
        assertTrue(reach < VodSizes.GAP)
        // The cards keep their shape and corners, smaller than the studios' five.
        near(w * 9f / 16f, VodSizes.cardH(style), 0.01f)
        near(VodSizes.cardH(style) * VodSizes.LOGO_RADIUS_SHARE, VodSizes.logoRadius(style), 0.01f)
        assertTrue(w < VodSizes.cardW(RowStyle.LOGO))
        // Disney+'s studios keep five across.
        assertEquals(5, VodSizes.logoAcross(RowStyle.LOGO))
        near(VodSizes.logoW(5), VodSizes.cardW(RowStyle.LOGO), 0.001f)
    }

    @Test
    fun `the eleven logo cards share one 16 to 9 card shape and every logo sits whole inside it`() {
        // Home's six services at their size, Disney+'s five studios at theirs.
        val cards = Service.entries.map { RowStyle.SERVICES to VodSizes.logoShare(it) } +
            Brand.entries.map { RowStyle.LOGO to VodSizes.logoShare(it) }
        assertEquals(11, cards.size)
        for ((style, share) in cards) {
            val w = VodSizes.cardW(style)
            val h = VodSizes.cardH(style)
            near(w * 9f / 16f, h, 0.01f)
            // The pictures are 640 x 300 with clear room round the mark: each whole inside the card, room left on all sides.
            val k = VodSizes.logoFit(share, 640, 300, w, h)
            near(w * share, 640 * k, 0.01f)
            assertTrue(640 * k < w && 300 * k < h)
            // Read at half size it's drawn the same.
            near(640 * k, 320 * VodSizes.logoFit(share, 320, 150, w, h), 0.01f)
        }
        val w = VodSizes.cardW(RowStyle.LOGO)
        val h = VodSizes.cardH(RowStyle.LOGO)
        near(h * 0.12f, VodSizes.logoRadius(), 0.01f)
        // Marvel on the TV (2 px a dp): about 224 x 105 px, read at half size (320 x 150), never smaller than drawn.
        val marvel = w * 2f * VodSizes.logoShare(Brand.MARVEL)
        near(224.2f, marvel, 0.5f)
        assertEquals(2, VodSizes.logoSample(640, marvel))
        assertEquals(2, VodSizes.logoSample(640, w * 2f * VodSizes.logoShare(Service.DISNEY)))
        assertEquals(1, VodSizes.logoSample(640, 321f))
        // The shadow stays in the gap between two cards.
        assertTrue(VodSizes.logoShadowReach() < VodSizes.GAP)
    }

    @Test
    fun `nothing is fetched for a logo card`() {
        assertTrue((Services.cards() + Services.brandCards()).all { it.title.poster == null })
        assertEquals(6, Services.cards().size)
        assertEquals(5, Services.brandCards().size)
    }

    @Test
    fun studioLogosComeOutMuchTheSameSizeOnTheNameLine() {
        // The trimmed marks' shapes (px): Disney, Marvel and Star Wars stand at the full height; Pixar's long wordmark
        // and National Geographic's stand lower, none covering more than the compact ones.
        val maxH = 52
        val evenW = 132
        assertEquals(maxH, VodSizes.nameLogoH(512, 207, maxH, evenW))
        assertEquals(maxH, VodSizes.nameLogoH(512, 204, maxH, evenW))
        assertEquals(maxH, VodSizes.nameLogoH(488, 214, maxH, evenW))
        val pixar = VodSizes.nameLogoH(512, 85, maxH, evenW)
        val natGeo = VodSizes.nameLogoH(511, 142, maxH, evenW)
        assertTrue(pixar in 30..36)
        assertTrue(natGeo in 40..46)
        assertTrue(pixar * (pixar * 512 / 85) <= evenW * maxH)
        assertEquals(maxH, VodSizes.nameLogoH(0, 0, maxH, evenW))
    }
}
