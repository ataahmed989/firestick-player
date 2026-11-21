package app.fireplayer.live.ui

import app.fireplayer.vod.ui.VodSizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The guide's small motions: the highlight fading with the focus, a long channel name scrolling, their lengths. */
class GuideMotionTest {

    @Test fun theHighlightFadesByItsOwnStrength() {
        val white = 0xFFF4F2EE.toInt()
        assertEquals(white, GuideCells.faded(white, 1f))
        assertEquals(0x00F4F2EE, GuideCells.faded(white, 0f))
        assertEquals(0x80F4F2EE.toInt(), GuideCells.faded(white, 0.5f))
        // A colour that's see-through already keeps its share.
        assertEquals(0x40102030, GuideCells.faded(0x80102030.toInt(), 0.5f))
        // Never past its ends.
        assertEquals(white, GuideCells.faded(white, 1.4f))
        assertEquals(0x00F4F2EE, GuideCells.faded(white, -0.2f))
    }

    @Test fun aNamesRoomIsWhatTheColumnLeaves() {
        // Column 288, the name from 97, 4 short of the edge: 187.
        assertEquals(187f, GuideCells.nameRoom(288f, 97f, 4f, 0f), 0f)
        // The playing channel's ▶ takes its room after the name.
        assertEquals(172f, GuideCells.nameRoom(288f, 97f, 4f, 15f), 0f)
        // The wide column (the grid) leaves more.
        assertTrue(GuideCells.nameRoom(344f, 97f, 4f, 0f) > GuideCells.nameRoom(288f, 97f, 4f, 0f))
        assertEquals(0f, GuideCells.nameRoom(80f, 97f, 4f, 0f), 0f)
    }

    @Test fun aNameScrollsOnlyWhenItDoesNotFitItsRoom() {
        val room = GuideCells.nameRoom(288f, 97f, 4f, 0f)
        assertFalse(LiveSearchView.Marquee.scrolls(room - 1f, room, 10_000L))
        assertFalse(LiveSearchView.Marquee.scrolls(room, room, 10_000L))
        // Too long: after the app's usual pause, not before (a held key only moves the pause along).
        assertFalse(LiveSearchView.Marquee.scrolls(room + 40f, room, VodSizes.LIVE_MARQUEE_DELAY_MS - 1))
        assertTrue(LiveSearchView.Marquee.scrolls(room + 40f, room, VodSizes.LIVE_MARQUEE_DELAY_MS))
        assertEquals(40f, LiveSearchView.Marquee.distance(room + 40f, room), 0f)
    }

    @Test fun theMotionsAreShort() {
        for (ms in listOf(LiveWidths.GUIDE_FOCUS_MS, LiveWidths.GUIDE_LIST_FADE_MS, LiveWidths.DETAIL_STAR_MS, LiveWidths.CHEVRON_MS)) {
            assertTrue(ms in 120L..250L)
        }
        assertTrue(LiveWidths.DETAIL_STAR_FROM > 0.4f && LiveWidths.DETAIL_STAR_FROM < 1f)
        assertTrue(LiveWidths.DETAIL_STAR_OUTLINE < 1f)
    }
}
