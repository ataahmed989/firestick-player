package app.fireplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTimingTest {

    @Test fun fromTheKeyPressToTheFirstFrame() {
        val t = LiveTiming()
        t.opened(now = 1_012, pressed = 1_000, kind = "TS", reusedPlayer = true)
        t.ready(1_600)
        assertEquals("Channel shown in 850 ms (key to open 12 ms, ready at 600 ms; TS; player reused)", t.firstFrame(1_850))
    }

    @Test fun loggedOncePerStream() {
        val t = LiveTiming()
        assertNull(t.firstFrame(5))
        t.opened(100, 100, "HLS", false)
        t.firstFrame(900)
        assertNull(t.firstFrame(950))
    }

    @Test fun withoutAKeyPressItCountsFromTheOpen() {
        val t = LiveTiming()
        t.opened(now = 500, pressed = 0, kind = "stream", reusedPlayer = false)
        assertEquals("Channel shown in 300 ms (key to open 0 ms, ready at 300 ms; stream; player new)", t.firstFrame(800))
        // A press "after" the open (a stale value) is not believed either.
        t.opened(now = 500, pressed = 900, kind = "stream", reusedPlayer = false)
        assertEquals("Channel shown in 100 ms (key to open 0 ms, ready at 100 ms; stream; player new)", t.firstFrame(600))
    }

    @Test fun aStreamClosedBeforeItShowedLogsNothing() {
        val t = LiveTiming()
        t.opened(100, 100, "TS", true)
        t.clear()
        assertNull(t.firstFrame(400))
    }

    @Test fun whereTheTimeWentWhenItIsKnown() {
        val t = LiveTiming()
        t.opened(now = 1_012, pressed = 1_000, kind = "TS", reusedPlayer = true)
        t.tracks(1_400)
        t.decoder(85)
        t.ready(1_600)
        assertEquals(
            "Channel shown in 850 ms (key to open 12 ms, ready at 600 ms; TS; player reused; tracks at 400 ms; video decoder 85 ms)",
            t.firstFrame(1_850),
        )
    }

    @Test fun onlyTheFirstTracksAndDecoderOfAStreamCount() {
        val t = LiveTiming()
        t.opened(now = 100, pressed = 100, kind = "HLS", reusedPlayer = true)
        t.tracks(300)
        // The tracks change again as captions are picked, and a second decoder starts on a change of quality.
        t.tracks(900)
        t.decoder(60)
        t.decoder(200)
        assertEquals(
            "Channel shown in 1000 ms (key to open 0 ms, ready at 1000 ms; HLS; player reused; tracks at 200 ms; video decoder 60 ms)",
            t.firstFrame(1_100),
        )
    }

    @Test fun whatWasNotedForOneStreamIsNotSaidOfTheNext() {
        val t = LiveTiming()
        t.opened(100, 100, "TS", true)
        t.tracks(200)
        t.decoder(70)
        t.firstFrame(500)
        // Noted with no stream being timed (the first frame is out): not kept for the next one.
        t.tracks(600)
        t.decoder(90)
        t.opened(1_000, 1_000, "TS", true)
        assertEquals("Channel shown in 400 ms (key to open 0 ms, ready at 400 ms; TS; player reused)", t.firstFrame(1_400))
        // A sound-only channel has no picture decoder; one that reports nonsense isn't believed.
        t.opened(2_000, 2_000, "TS", true)
        t.decoder(-1)
        t.tracks(2_250)
        assertEquals("Channel shown in 500 ms (key to open 0 ms, ready at 500 ms; TS; player reused; tracks at 250 ms)", t.firstFrame(2_500))
    }

    @Test fun theKindOfStreamNeverTheAddress() {
        assertEquals("HLS", LiveTiming.kindOf("index.M3U8"))
        assertEquals("DASH", LiveTiming.kindOf("manifest.mpd"))
        assertEquals("TS", LiveTiming.kindOf("1234.ts"))
        assertEquals("stream", LiveTiming.kindOf("1234"))
        assertEquals("stream", LiveTiming.kindOf(null))
    }
}
