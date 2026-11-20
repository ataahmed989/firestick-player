package app.fireplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveReconnectTest {

    @Test fun oneTwoFourEightThenEveryThirtySeconds() {
        val r = LiveReconnect()
        val waits = (1..7).map { r.failed(now = it * 100L) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 30_000L, 30_000L, 30_000L), waits)
    }

    @Test fun slowOnceTheQuickTriesAreUsedUp() {
        val r = LiveReconnect()
        repeat(4) { r.failed(0); assertFalse(r.slow) }
        r.failed(0)
        assertTrue(r.slow)
    }

    @Test fun aStreamThatEndedIsOpenedAgainAlmostAtOnceTheFirstTimeOnly() {
        val r = LiveReconnect()
        assertEquals(LiveReconnect.ENDED_FIRST_MS, r.failed(0, ended = true))
        assertEquals(2_000L, r.failed(10, ended = true))
        assertEquals(4_000L, r.failed(20, ended = true))
    }

    @Test fun playingSteadilyStartsTheCountAgain() {
        val r = LiveReconnect()
        r.failed(0); r.failed(0); r.failed(0)
        r.playing(1_000)
        r.notPlaying(1_000 + LiveReconnect.STEADY_MS)
        assertEquals(0, r.attempt)
        assertEquals(1_000L, r.failed(20_000))
    }

    @Test fun aStreamThatDiesAfterASecondKeepsBackingOff() {
        val r = LiveReconnect()
        assertEquals(1_000L, r.failed(0))
        r.playing(1_500)
        // Fails while still counted as playing: a second and a half isn't steady.
        assertEquals(2_000L, r.failed(3_000))
        r.playing(5_500)
        r.notPlaying(6_000)
        assertEquals(4_000L, r.failed(6_100))
    }

    @Test fun steadyPlayingIsCountedFromItsStartNotFromEachReport() {
        val r = LiveReconnect()
        r.failed(0)
        r.playing(1_000)
        r.playing(9_000)
        // Still playing when it fails, 10 s after it started.
        assertEquals(1_000L, r.failed(11_000))
    }

    @Test fun anotherChannelStartsAfresh() {
        val r = LiveReconnect()
        repeat(6) { r.failed(0) }
        r.reset()
        assertFalse(r.slow)
        assertEquals(1_000L, r.failed(0))
    }
}
