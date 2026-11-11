package app.fireplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvRefreshTest {

    @Test
    fun framesAreLinedUpByHandOnlyWhereCallbacksMissTheTvsRefreshes() {
        // Frame callbacks every 16682 us, as measured on a Fire TV Stick 4K Plus.
        val callbackNs = 16_682_000L
        val at23976 = TvRefresh.periodNs(23.976f)
        assertEquals(41_708_333.0, at23976.toDouble(), 100.0)
        assertTrue(TvRefresh.unaligned(callbackNs, at23976))
        // The same callbacks on a TV at 59.94 Hz: Media3 lines them up.
        val at5994 = TvRefresh.periodNs(59.94f)
        assertEquals(16_683_350.0, at5994.toDouble(), 100.0)
        assertFalse(TvRefresh.unaligned(callbackNs, at5994))
        assertFalse(TvRefresh.unaligned(16_666_667L, TvRefresh.periodNs(60f)))
        assertTrue(TvRefresh.unaligned(callbackNs, TvRefresh.periodNs(50f)))
    }

    @Test
    fun media3sOwnTimingIsWorkedOutAsMedia3Does() {
        for (rate in listOf(59.94f, 23.976f, 50f, 60f)) {
            val duration = (1e9 / rate.toDouble()).toLong()
            assertEquals(duration to duration * 80 / 100, TvRefresh.media3Vsync(rate))
        }
    }
}
