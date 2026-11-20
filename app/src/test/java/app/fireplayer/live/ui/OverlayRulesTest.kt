package app.fireplayer.live.ui

import app.fireplayer.live.ChannelGroup
import app.fireplayer.live.GuideScope
import app.fireplayer.live.ui.OverlayRules.Level
import app.fireplayer.live.ui.OverlayRules.Ok
import app.fireplayer.live.ui.OverlayRules.SearchStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The channel list over full screen and Multiview: its keys, its columns, Multiview's start and its search page. */
class OverlayRulesTest {

    // ---- Keys --------------------------------------------------------------------------------------------------------

    @Test fun fullScreenLeftOpensTheChannelsThenTheGroups() {
        assertEquals(Level.CHANNELS, OverlayRules.left(Level.CLOSED))
        assertEquals(Level.CHANNELS, OverlayRules.opening(picking = false))
        assertEquals(Level.GROUPS, OverlayRules.left(Level.CHANNELS))
        // Nothing further left of the groups.
        assertEquals(Level.GROUPS, OverlayRules.left(Level.GROUPS))
    }

    @Test fun rightStepsBackTowardsThePicture() {
        assertEquals(Level.CHANNELS, OverlayRules.right(Level.GROUPS, picking = false))
        assertEquals(Level.CLOSED, OverlayRules.right(Level.CHANNELS, picking = false))
        assertEquals(Level.CLOSED, OverlayRules.right(Level.CLOSED, picking = false))
    }

    @Test fun pickingRightNeverClosesTheList() {
        assertEquals(Level.CHANNELS, OverlayRules.right(Level.GROUPS, picking = true))
        assertEquals(Level.CHANNELS, OverlayRules.right(Level.CHANNELS, picking = true))
    }

    @Test fun backClosesEverything() {
        for (l in Level.values()) assertEquals(Level.CLOSED, OverlayRules.back(l))
    }

    @Test fun okOnAGroupShowsItsChannels() {
        assertEquals(Level.CHANNELS, OverlayRules.okOnGroup())
    }

    @Test fun okOnAChannelPlaysItAndASecondOkCloses() {
        assertEquals(Ok.PLAY, OverlayRules.okOnChannel(picking = false, isPlaying = false))
        assertEquals(Ok.CLOSE, OverlayRules.okOnChannel(picking = false, isPlaying = true))
        assertEquals(Ok.PICK, OverlayRules.okOnChannel(picking = true, isPlaying = false))
        assertEquals(Ok.PICK, OverlayRules.okOnChannel(picking = true, isPlaying = true))
    }

    @Test fun upAndDownGoRoundOnAFreshPressAndStopWhenHeld() {
        assertEquals(1, OverlayRules.step(0, 5, down = true, held = false))
        assertEquals(0, OverlayRules.step(4, 5, down = true, held = false))
        assertEquals(4, OverlayRules.step(0, 5, down = false, held = false))
        assertEquals(4, OverlayRules.step(4, 5, down = true, held = true))
        assertEquals(0, OverlayRules.step(0, 5, down = false, held = true))
        assertEquals(0, OverlayRules.step(0, 1, down = true, held = false))
        assertEquals(-1, OverlayRules.step(0, 0, down = true, held = false))
    }

    // ---- Which list each column shows ----------------------------------------------------------------------------

    @Test fun theChannelsShowWithTheProgrammesBesideThemInFullScreen() {
        assertTrue(OverlayRules.showsChannels(Level.CHANNELS))
        assertTrue(OverlayRules.showsProgrammes(Level.CHANNELS, picking = false))
        assertFalse(OverlayRules.showsGroups(Level.CHANNELS))
        assertEquals(0f, OverlayRules.channelsX(Level.CHANNELS, 224f), 0f)
    }

    @Test fun theGroupsReplaceTheProgrammesAndPushTheChannelsRight() {
        assertTrue(OverlayRules.showsGroups(Level.GROUPS))
        assertTrue(OverlayRules.showsChannels(Level.GROUPS))
        assertFalse(OverlayRules.showsProgrammes(Level.GROUPS, picking = false))
        assertEquals(224f, OverlayRules.channelsX(Level.GROUPS, 224f), 0f)
    }

    @Test fun pickingNeverShowsProgrammesAndClosedShowsNothing() {
        assertFalse(OverlayRules.showsProgrammes(Level.CHANNELS, picking = true))
        assertFalse(OverlayRules.showsChannels(Level.CLOSED))
        assertFalse(OverlayRules.showsGroups(Level.CLOSED))
        assertFalse(OverlayRules.showsProgrammes(Level.CLOSED, picking = false))
    }

    @Test fun theGroupsColumnListsThePlaylistsGroupsAsTheGuideDoes() {
        val groups = listOf(
            ChannelGroup("p", "news", "USA | News", 0),
            ChannelGroup("p", "kids", "USA | Family & Kids", 1),
            ChannelGroup("p", "gone", "Hidden", 2, hidden = true),
            ChannelGroup("q", "other", "Another playlist's", 0),
        )
        val e = OverlayRules.entries(picking = false, playlistId = "p", groups = groups)
        assertEquals(listOf("Favorites", "All channels", "USA | News", "USA | Family & Kids"), e.map { it.label })
        assertEquals(GuideScope.Favorites("p"), e[0].scope)
        assertEquals(GuideScope.AllChannels("p"), e[1].scope)
        assertEquals(GuideScope.Group("p", "kids"), e[3].scope)
        assertFalse(e.any { it.isSearch })
    }

    @Test fun pickingPutsSearchAtTheTopOfTheGroups() {
        val e = OverlayRules.entries(picking = true, playlistId = "p", groups = emptyList())
        assertEquals(OverlayRules.SEARCH, e[0].label)
        assertTrue(e[0].isSearch)
        assertNull(e[0].scope)
        assertEquals("Favorites", e[1].label)
        // The selection opens on the group shown, never on Search.
        assertEquals(1, OverlayRules.rowOf(e, null))
        assertEquals(2, OverlayRules.rowOf(e, GuideScope.AllChannels("p")))
        assertEquals(1, OverlayRules.rowOf(e, GuideScope.Group("p", "unknown")))
    }

    @Test fun aListOpensOnTheWantedChannelElseTheFirst() {
        val list = listOf("a", "b", "c")
        assertEquals(1, OverlayRules.focusIn(list, "b") { it })
        assertEquals(0, OverlayRules.focusIn(list, "z") { it })
        assertEquals(0, OverlayRules.focusIn(list, null as String?) { it })
        assertEquals(-1, OverlayRules.focusIn(emptyList<String>(), "a") { it })
    }

    @Test fun theProgrammeOnNowItsProgressAndMinutesLeft() {
        val starts = longArrayOf(0, 1_800_000, 3_600_000)
        val stops = longArrayOf(1_800_000, 3_600_000, 5_400_000)
        assertEquals(1, OverlayRules.onNow(3, 2_000_000, { starts[it] }, { stops[it] }))
        // Before the first: the first (the next one on).
        assertEquals(0, OverlayRules.onNow(3, -5, { starts[it] }, { stops[it] }))
        assertEquals(-1, OverlayRules.onNow(3, 6_000_000, { starts[it] }, { stops[it] }))
        assertEquals(0.5f, OverlayRules.progress(0, 1_800_000, 900_000), 0.0001f)
        assertEquals(1f, OverlayRules.progress(0, 10, 20), 0f)
        assertEquals(0f, OverlayRules.progress(10, 10, 20), 0f)
        assertEquals(26, OverlayRules.minutesLeft(26 * 60_000L, 0))
        assertEquals(1, OverlayRules.minutesLeft(1, 0))
        assertEquals(0, OverlayRules.minutesLeft(0, 5))
    }

    // ---- Multiview's start and its search page --------------------------------------------------------------------

    @Test fun multiviewStartsWithThePictureSmallAtTheTopRightAndTheGroupsOpen() {
        assertEquals(Level.GROUPS, OverlayRules.opening(picking = true))
        val b = OverlayRules.readyPicture(LiveWidths.SCREEN.toFloat(), LiveWidths.SCREEN_H.toFloat(), LiveWidths.GAP.toFloat())
        assertEquals(LiveWidths.SCREEN - LiveWidths.GAP.toFloat(), b[2], 0f)
        assertEquals(LiveWidths.GAP.toFloat(), b[1], 0f)
        assertEquals(LiveWidths.MINI.toFloat(), b[2] - b[0], 0f)
        assertEquals(LiveWidths.MINI_H.toFloat(), b[3] - b[1], 0f)
        // The groups and channels beside them end left of it.
        assertTrue(LiveWidths.OV_GROUPS_W + LiveWidths.OV_CHANNELS_W < b[0])
    }

    @Test fun fullScreensColumnsAndDetailsFitTheScreen() {
        val details = LiveWidths.SCREEN - LiveWidths.OV_CHANNELS_W - LiveWidths.OV_PROGRAMMES_W - LiveWidths.OV_DETAILS_GAP - LiveWidths.GAP
        assertTrue("details $details dp", details >= 300)
        // The channel row: a five-digit number (about 8 dp a digit at its size), logo and a name of 125 dp or more
        // beside them.
        val number = maxOf(LiveWidths.OV_NUMBER_W, 5 * 8)
        val name = LiveWidths.OV_CHANNELS_W - 2 * (LiveWidths.OV_PAD_X + LiveWidths.OV_ROW_PAD_X) - number -
            LiveWidths.OV_LOGO_W - 2 * LiveWidths.OV_GAP
        assertTrue("name $name dp", name >= 125)
        // The details' time line: the times, the bar and the minutes left fit the card.
        val inner = details - 2 * LiveWidths.OV_DETAILS_PAD
        assertTrue(LiveWidths.OV_DETAILS_TIME_W + LiveWidths.OV_DETAILS_BAR_W + LiveWidths.OV_GAP + 60 <= inner)
    }

    @Test fun everyColumnEndsOnAWholeRowTheSameRoomFromTheBottom() {
        val rows = LiveWidths.SCREEN_H - LiveWidths.OV_TOP - LiveWidths.OV_HEAD_H
        // Channels: nine whole rows, the header's top room left under them.
        val channelRow = (LiveWidths.SCREEN_H - 2 * LiveWidths.OV_TOP - LiveWidths.OV_HEAD_H) / LiveWidths.OV_CHANNEL_ROWS
        assertEquals(LiveWidths.OV_TOP, rows - channelRow * LiveWidths.OV_CHANNEL_ROWS)
        // Groups and programmes: whole rows, with room left under the last (never a cut row at the screen's edge).
        for (row in listOf(LiveWidths.OV_GROUP_ROW, LiveWidths.OV_PROGRAMME_ROW)) {
            val left = rows - rows / row * row
            assertTrue("$left dp under $row dp rows", left >= 8)
        }
        // A channel row's lines sit inside it, the name over the programme.
        assertTrue(LiveWidths.OV_NAME_AT > 0.2f && LiveWidths.OV_NAME_AT < LiveWidths.OV_SUB_AT && LiveWidths.OV_SUB_AT < 0.7f)
    }

    @Test fun theSearchPageShowsHistoryFirstAndTheBarNeedsOk() {
        assertEquals(SearchStart.HISTORY, OverlayRules.searchStart("", 3))
        assertEquals(SearchStart.HISTORY, OverlayRules.searchStart("  ", 1))
        assertEquals(SearchStart.BAR, OverlayRules.searchStart("", 0))
        assertEquals(SearchStart.BAR, OverlayRules.searchStart("news", 3))
        assertTrue(OverlayRules.showsHistory(""))
        assertFalse(OverlayRules.showsHistory("cnn"))
        assertFalse(OverlayRules.KEYBOARD_ON_OPEN)
    }

    @Test fun theSearchPagesHistoryLeavesOutTheChannelsOnScreen() {
        val recent = listOf("playing", "a", "b", "tile2", "c", "d")
        // Multiview's start: the one playing is picture 1, so the selection starts on a channel that can be added.
        assertEquals(listOf("a", "b", "tile2"), OverlayRules.history(recent, setOf("playing"), 3) { it })
        assertEquals(listOf("a", "b", "c", "d"), OverlayRules.history(recent, setOf("playing", "tile2"), 10) { it })
        assertEquals(recent.take(2), OverlayRules.history(recent, emptySet(), 2) { it })
        assertTrue(OverlayRules.history(listOf("playing"), setOf("playing"), 10) { it }.isEmpty())
        assertTrue(OverlayRules.history(recent, emptySet<String>(), 0) { it }.isEmpty())
    }
}
