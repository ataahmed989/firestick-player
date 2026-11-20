package app.fireplayer.vod.ui

import app.fireplayer.live.ui.LivePicture
import app.fireplayer.shell.RailMotion
import app.fireplayer.shell.RailShape
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.DrawnSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home's top right while the live picture plays there (the user's request of 2026-11-20): a 16:9 window as tall as
 * the banner, ending where Recent channels ends, the banner narrowed beside it; and the one motion between the two
 * layouts. Screen dp on the 960 x 540 dp screen.
 */
class HomePipLayoutTest {

    private class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
        fun overlaps(o: Box) = left < o.right && o.left < right && top < o.bottom && o.top < bottom
        fun inside(o: Box, slack: Float = 0.001f) =
            left >= o.left - slack && top >= o.top - slack && right <= o.right + slack && bottom <= o.bottom + slack
        override fun toString() = "($left, $top, $right, $bottom)"
    }

    private val screen = Box(0f, 0f, VodSizes.SCREEN_W, VodSizes.SCREEN_H)
    private val top = VodSizes.VIEWPORT_TOP

    /** The banner and the window on the screen in Recent channels' layout ([pip] false) or the picture's. */
    private fun banner(pip: Boolean) =
        Box(VodSizes.HOME_HEADER_SCREEN_X, top, VodSizes.HOME_HEADER_SCREEN_X + VodSizes.homeHeaderW(pip), top + VodSizes.HEADER_H)

    private fun window(pip: Boolean) =
        Box(VodSizes.RAIL + VodSizes.windowX(pip), top, VodSizes.RAIL + VodSizes.windowX(pip) + VodSizes.windowW(pip), top + VodSizes.HEADER_H)

    @Test fun thePicturesWindowIs16By9AsTallAsTheBanner() {
        assertEquals(405f, VodSizes.PIP_WINDOW_W)
        assertEquals(VodSizes.HEADER_H * 16f / 9f, VodSizes.PIP_WINDOW_W, 0.5f)
        // A 16:9 channel fills it (whole dp: within the fill's allowance); a 4:3 one doesn't (it's fitted, with bars).
        assertTrue(LivePicture.fills(16f / 9f, VodSizes.PIP_WINDOW_W, VodSizes.HEADER_H))
        assertTrue(LivePicture.fills(0f, VodSizes.PIP_WINDOW_W, VodSizes.HEADER_H))
        assertFalse(LivePicture.fills(4f / 3f, VodSizes.PIP_WINDOW_W, VodSizes.HEADER_H))
        // Recent channels' box isn't 16:9: a 16:9 channel in it would show bars.
        assertFalse(LivePicture.fills(16f / 9f, VodSizes.WINDOW_W, VodSizes.HEADER_H))
    }

    @Test fun bothLayoutsKeepTheGapAndTheMarginAndFitTheScreen() {
        for (pip in listOf(false, true)) {
            val b = banner(pip)
            val w = window(pip)
            assertTrue("$b", b.inside(screen))
            assertTrue("$w", w.inside(screen))
            assertFalse(b.overlaps(w))
            // The banner starts where it always does, clear of the rail's window; 16 dp to the window; 24 dp margin.
            assertEquals(70f, b.left)
            assertTrue(b.left > VodSizes.RAIL_WINDOW_END)
            assertEquals(VodSizes.WINDOW_GAP, w.left - b.right, 0.001f)
            assertEquals(VodSizes.RIGHT_MARGIN, VodSizes.SCREEN_W - w.right, 0.001f)
            assertEquals(VodSizes.HEADER_H, w.height, 0.001f)
            assertEquals(b.top, w.top, 0.001f)
        }
        // Today's layout is unchanged; the picture's: the banner 70..515, the window 531..936.
        assertEquals(630f, banner(false).right, 0.001f)
        assertEquals(646f, window(false).left, 0.001f)
        assertEquals(515f, banner(true).right, 0.001f)
        assertEquals(531f, window(true).left, 0.001f)
        assertEquals(936f, window(true).right, 0.001f)
        assertEquals(445f, VodSizes.homeHeaderW(pip = true), 0.001f)
        // The page-local window places are the screen's less the rail's strip.
        assertEquals(VodSizes.windowX(), VodSizes.windowX(pip = false), 0.001f)
        assertEquals(VodSizes.homeHeaderW(), VodSizes.homeHeaderW(pip = false), 0.001f)
    }

    @Test fun theNarrowBannersPartsFitIt() {
        val card = VodSizes.homeHeaderW(pip = true)
        val h = VodSizes.HEADER_H
        val cardBox = Box(0f, 0f, card, h)
        assertTrue(VodSizes.beside(card))
        assertFalse(VodSizes.beside(VodSizes.homeHeaderW()))
        assertFalse(VodSizes.beside(VodSizes.tabHeaderW()))
        // The picture's box: the same 16:9 box as beside Recent channels (not decoded again), at the card's right,
        // its eased left edge 40 dp in.
        assertEquals(VodSizes.backdropW(VodSizes.homeHeaderW()), VodSizes.backdropW(card), 0.001f)
        val art = Box(card - VodSizes.backdropW(card), 0f, card, h)
        assertTrue(art.inside(cardBox))
        assertEquals(39.67f, art.left, 0.01f)
        // The words: 249 dp, ending as far from the card's right edge as on every banner (the picture's subject).
        val story = VodSizes.storyW(card)
        assertEquals(249f, story, 0.001f)
        val words = Box(VodSizes.HEADER_INSET, VodSizes.HEADER_TOP, VodSizes.HEADER_INSET + story, VodSizes.headerWordsEnd())
        assertTrue(words.inside(cardBox))
        assertEquals(card - VodSizes.HEADER_CLEAR, words.right, 0.001f)
        // The logo's box: Home's own 205 dp (the same logo either way), inside the words' column.
        assertEquals(VodSizes.headerLogoW(VodSizes.homeHeaderW()), VodSizes.headerLogoW(card), 0.001f)
        val logo = Box(words.left, VodSizes.HEADER_TOP, words.left + VodSizes.headerLogoW(card), VodSizes.HEADER_TOP + VodSizes.HEADER_TITLE_H)
        assertTrue(logo.inside(words))
        // The buttons at the bottom left, Watch now and Details (13 sp, at most 0.62 em a letter), the focused one
        // grown 4% about its centre, its glow 12.5 dp round it: inside the card, under the words.
        val glow = 12.5f
        val grow = Tokens.GROW_PILL - 1f
        fun pill(text: String, icon: Boolean) = 2 * VodSizes.HEADER_BUTTON_PAD + text.length * 0.62f * VodSizes.HEADER_BUTTON.sp +
            if (icon) VodSizes.HEADER_BUTTON_ICON + VodSizes.HEADER_BUTTON_ICON_GAP else 0f
        val watch = pill("Watch now", icon = true)
        val details = pill("Details", icon = false)
        val buttonsTop = VodSizes.headerButtonsTop() - VodSizes.HEADER_BUTTON_H * grow / 2f
        val buttons = Box(
            VodSizes.HEADER_INSET - watch * grow / 2f, buttonsTop,
            VodSizes.HEADER_INSET + watch + VodSizes.HEADER_BUTTON_GAP + details + details * grow / 2f,
            VodSizes.headerButtonsTop() + VodSizes.HEADER_BUTTON_H * (1f + grow / 2f),
        )
        assertTrue("$buttons", buttons.inside(cardBox))
        assertTrue(buttons.left - glow >= 0f)
        assertTrue(words.bottom + VodSizes.HEADER_WORDS_MIN_GAP <= VodSizes.headerButtonsTop())
        // The dots at the bottom right under the clear picture: inside the card's margin, clear of the buttons and
        // their glow, under them.
        val dotsX = VodSizes.headerDotsX(card)
        val dots = Box(dotsX, h - VodSizes.DOTS_BOTTOM - VodSizes.DOTS_H, dotsX + VodSizes.DOTS_W, h - VodSizes.DOTS_BOTTOM)
        assertTrue("$dots", dots.inside(Box(VodSizes.HEADER_INSET, 0f, card - VodSizes.HEADER_INSET, h)))
        assertTrue(buttons.right + glow <= dots.left)
        assertTrue(dots.top >= buttons.bottom)
        assertTrue(dots.left >= words.right)
        // Beside Recent channels the dots stay centred, as on every other banner.
        assertEquals((VodSizes.homeHeaderW() - VodSizes.DOTS_W) / 2f, VodSizes.headerDotsX(VodSizes.homeHeaderW()), 0.001f)
        assertEquals((VodSizes.tabHeaderW() - VodSizes.DOTS_W) / 2f, VodSizes.headerDotsX(VodSizes.tabHeaderW()), 0.001f)
        // The shade round the words: dark behind them, gone at the card's right edge.
        assertTrue(VodSizes.headerShade(VodSizes.HEADER_INSET, VodSizes.HEADER_TOP, card) >= 0.75f)
        val end = VodSizes.headerShade(words.right, VodSizes.headerWordsEnd() - 9f, card)
        assertTrue("$end", end > 0.15f && end < 0.3f)
        assertTrue(VodSizes.headerShade(card, h / 2f, card) < 0.02f)
    }

    @Test fun theMotionsBoundsMoveTogetherKeepingTheGap() {
        val windowRight = VodSizes.windowX(pip = false) + VodSizes.WINDOW_W
        assertEquals(windowRight, VodSizes.windowX(pip = true) + VodSizes.PIP_WINDOW_W, 0.001f)
        assertEquals(VodSizes.HOME_HEADER_X + VodSizes.homeHeaderW(pip = false), VodSizes.homeHeaderRight(0f), 0.001f)
        assertEquals(VodSizes.HOME_HEADER_X + VodSizes.homeHeaderW(pip = true), VodSizes.homeHeaderRight(1f), 0.001f)
        assertEquals(VodSizes.windowX(pip = false), VodSizes.homeWindowLeft(0f), 0.001f)
        assertEquals(VodSizes.windowX(pip = true), VodSizes.homeWindowLeft(1f), 0.001f)
        var lastRight = Float.MAX_VALUE
        for (i in 0..20) {
            val p = i / 20f
            val right = VodSizes.homeHeaderRight(p)
            val left = VodSizes.homeWindowLeft(p)
            assertEquals(VodSizes.WINDOW_GAP, left - right, 0.001f)
            assertTrue(right < lastRight)
            lastRight = right
            val bannerW = right - VodSizes.HOME_HEADER_X
            val windowW = windowRight - left
            assertTrue(bannerW in 445f..560f && windowW in 290f..405f)
            // Each window's surface, laid out at one width and drawn at this one, is drawn whole.
            assertTrue(DrawnSpan.covers(VodSizes.homeHeaderW(pip = false), bannerW))
            assertTrue(DrawnSpan.covers(VodSizes.homeHeaderW(pip = true), bannerW))
            assertTrue(DrawnSpan.covers(VodSizes.WINDOW_W, windowW))
            assertTrue(DrawnSpan.covers(VodSizes.PIP_WINDOW_W, windowW))
            // Half way, both are half way.
            if (i == 10) {
                assertEquals(502.5f, bannerW, 0.001f)
                assertEquals(347.5f, windowW, 0.001f)
            }
        }
    }

    @Test fun theMotionIsTheRailsAndTurnsBackFromWhereItIs() {
        val m = RailMotion(0f)
        m.aim(1f)
        var elapsed = 0f
        var last = 0f
        val seen = ArrayList<Float>()
        while (m.running) {
            m.step(RailShape.FRAME_MS)
            elapsed += RailShape.FRAME_MS
            assertTrue(m.value >= last)
            last = m.value
            seen.add(m.value)
        }
        assertEquals(1f, m.value)
        assertEquals(RailShape.MOVE_MS.toFloat(), elapsed, RailShape.FRAME_MS)
        // Away fast, landing softly: past half way in the first third of the time.
        assertTrue(seen[seen.size / 3] > 0.5f)
        // Closed part-way: it turns back from where it is (no jump), in less time.
        val back = RailMotion(0f)
        back.aim(1f)
        repeat(6) { back.step(RailShape.FRAME_MS) }
        val at = back.value
        back.aim(0f)
        back.step(0f)
        assertEquals(at, back.value, 0.0001f)
        var t = 0f
        while (back.running) {
            back.step(RailShape.FRAME_MS)
            t += RailShape.FRAME_MS
        }
        assertEquals(0f, back.value)
        assertTrue(t < RailShape.MOVE_MS)
    }

    @Test fun thePictureFollowsItsWindowAllTheWay() {
        // At 2 px a dp (a 1080p TV): the picture fitted in the window drawn at each frame stays inside it, and at the
        // ends it's where it's laid out (fitted in Recent channels' box; filling the 16:9 window).
        val d = 2f
        val h = VodSizes.HEADER_H * d
        val right = (VodSizes.windowX(pip = false) + VodSizes.WINDOW_W) * d
        val out = app.fireplayer.live.ui.PictureMotion.Box()
        for (aspect in listOf(16f / 9f, 4f / 3f, 0f)) {
            for (i in 0..10) {
                val left = VodSizes.homeWindowLeft(i / 10f) * d
                LivePicture.fit(left, 0f, right - left, h, aspect, out)
                assertTrue(out.left >= left - 0.01f && out.right <= right + 0.01f && out.top >= -0.01f && out.bottom <= h + 0.01f)
            }
        }
        val wide = VodSizes.windowX(pip = true) * d
        val filled = LivePicture.place(wide, 0f, right - wide, h, 16f / 9f, full = false, mode = 0, fill = true)
        assertEquals(LivePicture.Placement(946, 0, 810, 456), filled)
        // Without the fill a 16:9 channel would leave a pixel of bars there.
        val fitted = LivePicture.place(wide, 0f, right - wide, h, 16f / 9f, full = false, mode = 0)
        assertEquals(455, fitted.height)
        // The last frame's picture is where it's laid out at the end, to within a pixel.
        LivePicture.fit(wide, 0f, right - wide, h, 16f / 9f, out)
        assertEquals(filled.left.toFloat(), out.left, 1f)
        assertEquals(filled.bottom.toFloat(), out.bottom, 1f)
        // A 4:3 channel is fitted (bars at its sides), as everywhere else.
        val narrow = LivePicture.place(wide, 0f, right - wide, h, 4f / 3f, full = false, mode = 0, fill = true)
        assertEquals(608, narrow.width)
        assertEquals(456, narrow.height)
    }
}
