package app.fireplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveBufferTest {

    @Test fun mediumIsTheBufferLiveTvAlwaysHad() {
        // PlayerFactory.buildLive's load control before the setting existed: 15 s / 30 s / 1.5 s / 3 s.
        val m = LiveBuffer.MEDIUM
        assertEquals(listOf(15_000, 30_000, 1_500, 3_000), listOf(m.minMs, m.maxMs, m.startMs, m.afterStallMs))
    }

    @Test fun nothingSavedMeansMedium() {
        assertEquals(LiveBuffer.MEDIUM, LiveBuffer.named(null))
        assertEquals(LiveBuffer.MEDIUM, LiveBuffer.named("HUGE"))
        for (b in LiveBuffer.entries) assertEquals(b, LiveBuffer.named(b.name))
    }

    @Test fun theChoicesReadSmallMediumLarge() {
        assertEquals(listOf("Small", "Medium", "Large"), LiveBuffer.entries.map { it.label })
    }

    @Test fun everySizeIsOneMedia3Accepts() {
        // DefaultLoadControl refuses a start or after-stall amount above the minimum, or a minimum above the maximum.
        for (b in LiveBuffer.entries) {
            assertTrue(b.name, b.startMs in 1..b.minMs)
            assertTrue(b.name, b.afterStallMs in 1..b.minMs)
            assertTrue(b.name, b.minMs <= b.maxMs)
        }
    }

    @Test fun eachSizeStepsUpFromTheOneBefore() {
        // Small starts sooner and holds less; Large holds more and starts later.
        for ((smaller, larger) in LiveBuffer.entries.zipWithNext()) {
            assertTrue(smaller.minMs < larger.minMs)
            assertTrue(smaller.maxMs < larger.maxMs)
            assertTrue(smaller.startMs < larger.startMs)
            assertTrue(smaller.afterStallMs < larger.afterStallMs)
        }
        // The Fire TV is short on memory: even Large keeps under a minute ahead.
        assertTrue(LiveBuffer.LARGE.maxMs < 60_000)
    }
}
