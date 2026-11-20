package app.fireplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PausedRoomTest {

    private val room = PausedRoom(lightMs = 60_000, staleMs = 31_000, playerMs = 600_000)

    @Test
    fun aPlayingFilmGivesNothingBack() {
        assertFalse(room.still)
        assertNull(room.due(1_000_000, busy = false))
        assertEquals(-1L, room.waitMs(1_000_000, busy = false))
        assertFalse(room.critical(busy = false))
    }

    @Test
    fun aShortPauseKeepsEverything() {
        room.still(1_000)
        assertTrue(room.still)
        assertNull(room.due(1_000, busy = false))
        assertNull(room.due(60_999, busy = false))
        assertEquals(60_000L, room.waitMs(1_000, busy = false))
        assertEquals(1L, room.waitMs(60_999, busy = false))
        room.moving()
        assertNull(room.due(700_000, busy = false))
    }

    @Test
    fun theStepsComeInOrderEachOnce() {
        room.still(0)
        assertEquals(PausedRoom.Step.LIGHT, room.due(60_000, busy = false))
        assertNull(room.due(60_001, busy = false))
        assertEquals(31_000L, room.waitMs(60_000, busy = false))
        assertNull(room.due(90_999, busy = false))
        assertEquals(PausedRoom.Step.STALE, room.due(91_000, busy = false))
        assertNull(room.due(200_000, busy = false))
        assertEquals(400_000L, room.waitMs(200_000, busy = false))
        assertEquals(PausedRoom.Step.PLAYER, room.due(600_000, busy = false))
        // The player is gone: nothing stands still, nothing more is due.
        assertFalse(room.still)
        assertNull(room.due(900_000, busy = false))
        assertEquals(-1L, room.waitMs(900_000, busy = false))
    }

    @Test
    fun playingAgainStartsTheClockAfresh() {
        room.still(0)
        assertEquals(PausedRoom.Step.LIGHT, room.due(60_000, busy = false))
        room.moving()
        room.still(100_000)
        assertNull(room.due(159_999, busy = false))
        assertEquals(PausedRoom.Step.LIGHT, room.due(160_000, busy = false))
        // Told again while it stands still: the clock isn't moved.
        room.still(500_000)
        assertEquals(PausedRoom.Step.STALE, room.due(191_000, busy = false))
        assertEquals(PausedRoom.Step.PLAYER, room.due(700_000, busy = false))
    }

    @Test
    fun nothingGoesWhileAWindowIsOpenAndItComesDueWhenItCloses() {
        room.still(0)
        assertNull(room.due(60_000, busy = true))
        assertEquals(PausedRoom.BUSY_CHECK_MS, room.waitMs(60_000, busy = true))
        assertNull(room.due(650_000, busy = true))
        assertFalse(room.critical(busy = true))
        // Closed after the long limit: the player goes straight away, the light step with it.
        assertEquals(0L, room.waitMs(650_000, busy = false))
        assertEquals(PausedRoom.Step.PLAYER, room.due(650_000, busy = false))
    }

    @Test
    fun theStaleStepNeverComesAfterThePlayerIsDue() {
        val quick = PausedRoom(lightMs = 60_000, staleMs = 31_000, playerMs = 80_000)
        quick.still(0)
        assertEquals(PausedRoom.Step.LIGHT, quick.due(60_000, busy = false))
        assertEquals(20_000L, quick.waitMs(60_000, busy = false))
        assertEquals(PausedRoom.Step.PLAYER, quick.due(91_000, busy = false))
    }

    @Test
    fun criticallyShortOfMemoryAPausedFilmsPlayerGoesAtOnce() {
        room.still(0)
        assertTrue(room.critical(busy = false))
        assertFalse(room.still)
        assertFalse(room.critical(busy = false))
        assertNull(room.due(700_000, busy = false))
    }

    @Test
    fun aPausedChannelIsLetGoOfOnlyWhenItCantBeWoundBack() {
        val limit = PausedRoom.LIVE_MS
        assertFalse(PausedRoom.liveSuspends(limit - 1, seekable = false))
        assertTrue(PausedRoom.liveSuspends(limit, seekable = false))
        assertFalse(PausedRoom.liveSuspends(limit * 3, seekable = true))
    }

    @Test
    fun onlyFireOsAskingTheBackgroundForMemoryBringsTheShellsReleaseForward() {
        // UI hidden (20) and the running levels (5, 10, 15) leave the 90 s wait alone.
        for (level in listOf(5, 10, 15, 20)) assertFalse(PausedRoom.backgroundAsks(level))
        // Background, moderate, complete.
        for (level in listOf(40, 60, 80)) assertTrue(PausedRoom.backgroundAsks(level))
    }
}
