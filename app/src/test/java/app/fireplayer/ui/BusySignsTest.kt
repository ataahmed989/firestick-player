package app.fireplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BusySignsTest {

    @Test fun theSymbolWaitsThreeHundredMilliseconds() {
        val s = BusySigns()
        assertEquals(300L, s.update(busy = true, reconnecting = false, now = 1_000))
        assertFalse(s.spinner)
        assertEquals(100L, s.update(true, false, 1_200))
        assertFalse(s.spinner)
        assertEquals(BusySigns.NONE, s.update(true, false, 1_300))
        assertTrue(s.spinner)
    }

    @Test fun aQuickChannelChangeNeverShowsIt() {
        val s = BusySigns()
        s.update(true, false, 0)
        s.update(true, false, 250)
        assertEquals(BusySigns.NONE, s.update(false, false, 280))
        assertFalse(s.spinner)
        // The next wait is counted from its own start.
        assertEquals(300L, s.update(true, false, 5_000))
        assertFalse(s.spinner)
    }

    @Test fun goneTheMomentFramesPlay() {
        val s = BusySigns()
        s.update(true, true, 0)
        s.update(true, true, 4_000)
        assertTrue(s.spinner)
        assertTrue(s.text)
        s.update(false, false, 4_001)
        assertFalse(s.spinner)
        assertFalse(s.text)
    }

    @Test fun zappingOnWhileItWaitsKeepsTheOneWait() {
        val s = BusySigns()
        s.update(true, false, 0)
        // Still busy (another channel asked for): not counted afresh, so it shows 300 ms after the first.
        s.update(true, false, 200)
        s.update(true, false, 400)
        assertTrue(s.spinner)
    }

    @Test fun reconnectingIsSaidOnlyAfterAFewSeconds() {
        val s = BusySigns()
        assertEquals(300L, s.update(true, true, 0))
        assertEquals(2_700L, s.update(true, true, 300))
        assertTrue(s.spinner)
        assertFalse(s.text)
        assertEquals(BusySigns.NONE, s.update(true, true, 3_000))
        assertTrue(s.text)
    }

    @Test fun theTextCountsFromWhenReconnectingBegan() {
        val s = BusySigns()
        s.update(true, false, 0)
        // Buffering for 2 s, then the stream fails and a reconnect starts.
        assertEquals(3_000L, s.update(true, true, 2_000))
        assertFalse(s.text)
        s.update(true, true, 5_000)
        assertTrue(s.text)
        // Loading again without reconnecting: the text goes, the symbol stays.
        s.update(true, false, 5_100)
        assertFalse(s.text)
        assertTrue(s.spinner)
    }

    @Test fun aRefusedChannelHasItsLineWithTheSymbolNotSecondsLater() {
        val s = BusySigns()
        // Shown with the symbol (after 300 ms), no 3 s wait for the reason.
        assertEquals(300L, s.update(true, true, 0, refused = true))
        assertTrue(s.text)
        s.update(true, true, 300, refused = true)
        assertTrue(s.spinner)
        // It plays: both gone.
        s.update(false, false, 400)
        assertFalse(s.text)
        assertFalse(s.spinner)
    }
}
