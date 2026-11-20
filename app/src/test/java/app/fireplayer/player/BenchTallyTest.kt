package app.fireplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchTallyTest {

    @Test
    fun theLineBeforeAnythingCame() {
        val b = BenchTally()
        assertFalse(b.running)
        b.reset(1_000)
        assertTrue(b.running)
        assertEquals(
            "bench mode=1 time=2s flat=none got=0.0MB minAhead=n/a stalled=0.0s/0 dropped=0 twice=0 same=0 never=0 shown=0 pssPeak=n/a",
            b.line(3_000, "1", "", last = false),
        )
        b.stop()
        assertFalse(b.running)
    }

    @Test
    fun everythingAddsUpOverThePlayersLife() {
        val b = BenchTally()
        b.reset(0)
        // Two loader samples: 100 MB in 8 s flat out, then 50 MB in 4 s: 100 Mb/s over 12 s.
        b.loader(100_000_000, 8_000_000_000, 120_000_000)
        b.loader(50_000_000, 4_000_000_000, 37_286_400)
        for (k in 0 until 3) {
            b.frames(FrameTally.RENDERED, 24)
            b.frames(FrameTally.DROPPED, 1)
            b.frames(FrameTally.TWICE, 2)
            b.frames(FrameTally.SAME, 0)
            b.frames(FrameTally.NEVER, 3)
        }
        b.pss(400_000)
        b.pss(524_288)
        b.pss(450_000)
        assertEquals(
            "bench end mode=2 time=60s flat=100.0Mb/s/12s got=150.0MB r1=50.0Mb/s r2=50.0Mb/s wait=1% reopen=40ms/7" +
                " minAhead=n/a stalled=0.0s/0 dropped=3 twice=6 same=0 never=9 shown=72 pssPeak=512.0MB",
            b.line(60_000, "2", "r1=50.0Mb/s r2=50.0Mb/s wait=1% reopen=40ms/7", last = true),
        )
    }

    @Test
    fun theLeastLoadedAheadCountsOnlyOnceTheBufferHadTimeToFill() {
        val b = BenchTally()
        b.reset(0)
        // Before the first frame nothing counts.
        b.ahead(500, 0)
        b.firstFrame(1_000)
        // Just after it there's a second loaded, whatever the line.
        b.ahead(2_000, 1_000)
        b.ahead(20_999, 9_000)
        assertTrue(b.line(21_000, "1", "", false).contains("minAhead=n/a"))
        b.ahead(21_000, 15_000)
        b.ahead(22_000, 12_500)
        b.ahead(23_000, 30_000)
        assertTrue(b.line(23_000, "1", "", false).contains("minAhead=12.5s"))
        // A jump empties the buffer: that isn't the line falling behind.
        b.jumped(30_000)
        b.ahead(31_000, 800)
        b.ahead(49_999, 5_000)
        assertTrue(b.line(50_000, "1", "", false).contains("minAhead=12.5s"))
        b.ahead(50_000, 7_300)
        assertTrue(b.line(50_000, "1", "", false).contains("minAhead=7.3s"))
        // A stall is nothing loaded ahead.
        b.ahead(60_000, 0)
        assertTrue(b.line(60_000, "1", "", false).contains("minAhead=0.0s"))
    }

    @Test
    fun stallsAreCountedAndTimedOneStillGoingOnIncluded() {
        val b = BenchTally()
        b.reset(0)
        b.stallEnd(500)
        b.stallStart(10_000)
        // Told twice: still one.
        b.stallStart(10_500)
        b.stallEnd(12_500)
        b.stallEnd(13_000)
        assertEquals(2.5, b.stalledSeconds(20_000), 0.0001)
        b.stallStart(30_000)
        assertEquals(6.5, b.stalledSeconds(34_000), 0.0001)
        assertTrue(b.line(34_000, "1", "", true).contains(" stalled=6.5s/2 "))
        b.stallEnd(35_000)
        assertEquals(7.5, b.stalledSeconds(99_000), 0.0001)
        // A new player starts from nothing.
        b.reset(100_000)
        assertTrue(b.line(101_000, "1", "", false).contains(" stalled=0.0s/0 "))
    }
}
