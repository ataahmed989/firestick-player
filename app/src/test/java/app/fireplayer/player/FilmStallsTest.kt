package app.fireplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A film that arrives about as fast as it plays: the wait after each stall, what's kept behind while it stalls, and
 * the download speed the line sustains. Figures as measured on a Fire TV Stick 4K Max with an 89 GB remux.
 */
class FilmStallsTest {

    private val mib = 1L shl 20
    private val s = 1_000_000L

    // ---- How many stalls (StallCount)

    @Test fun stallsCountUpWhileTheyKeepComing() {
        val c = StallCount()
        assertEquals(0, c.count(0))
        assertEquals(1, c.stalled(1_000))
        assertEquals(2, c.stalled(15_000))
        // Nine minutes on, still the same run of stalls.
        assertEquals(3, c.stalled(15_000 + 9 * 60_000))
        assertEquals(3, c.count(15_000 + 12 * 60_000))
    }

    @Test fun tenQuietMinutesForgetThem() {
        val c = StallCount()
        c.stalled(0)
        c.stalled(10_000)
        val later = 10_000 + StallCount.QUIET_MS + 1
        assertEquals(0, c.count(later))
        assertEquals(1, c.stalled(later))
    }

    // ---- The wait after a stall (StallCushion)

    @Test fun theFirstStallWaitsAsItAlwaysDid() {
        // 5 s of the film fit: half of it, as FilmMemory.waitUs gives a stall.
        val wanted = StallCushion.wantedUs(1, 1.0f)
        assertEquals(8 * s, wanted)
        assertEquals(FilmMemory.waitUs(wanted, 5 * s, FilmMemory.STALL_WAIT_LEAST_US), StallCushion.waitUs(1, wanted, 5 * s))
        assertEquals(2_500_000L, StallCushion.waitUs(1, wanted, 5 * s))
    }

    @Test fun eachStallInTheSameFilmWaitsForMoreOfWhatFits() {
        val room = 5 * s
        val first = StallCushion.waitUs(1, StallCushion.wantedUs(1, 0.97f), room)
        val second = StallCushion.waitUs(2, StallCushion.wantedUs(2, 0.97f), room)
        val third = StallCushion.waitUs(3, StallCushion.wantedUs(3, 0.97f), room)
        assertEquals(2_500_000L, first)
        assertEquals(3_750_000L, second)
        // From the third on: all that fits. Twice the cushion of the first, so about half as many stalls.
        assertEquals(room, third)
        assertEquals(room, StallCushion.waitUs(9, StallCushion.wantedUs(9, 0.97f), room))
    }

    @Test fun neverMoreThanWantedNorLessThanTwoSeconds() {
        // A light film of which two minutes fit: the count's 4, 10 and 20 s, not the room.
        assertEquals(4 * s, StallCushion.waitUs(1, StallCushion.wantedUs(1, 2f), 120 * s))
        assertEquals(10 * s, StallCushion.waitUs(2, StallCushion.wantedUs(2, 2f), 120 * s))
        assertEquals(20 * s, StallCushion.waitUs(3, StallCushion.wantedUs(3, 2f), 120 * s))
        // Almost nothing fits: still 2 s.
        assertEquals(FilmMemory.STALL_WAIT_LEAST_US, StallCushion.waitUs(1, 4 * s, 1 * s))
        assertEquals(FilmMemory.STALL_WAIT_LEAST_US, StallCushion.waitUs(3, 20 * s, 1 * s))
    }

    @Test fun whatFitsNotKnownWaitsForWhatsWanted() {
        // The full buffer ends that wait (FilmLoadControl), so it can't hang.
        assertEquals(20 * s, StallCushion.waitUs(3, 20 * s, 0L))
    }

    @Test fun aFilmFallingBehindWaitsForItsShortfall() {
        // Arriving at 0.8x: 12 s ahead to then play a minute; at 0.3x no more than half a minute.
        assertTrue(kotlin.math.abs(12 * s - StallCushion.forRate(0.8f)) < 100_000L)
        assertEquals(StallCushion.MAX_CUSHION_US, StallCushion.forRate(0.3f))
        assertEquals(0L, StallCushion.forRate(1.3f))
        assertTrue(kotlin.math.abs(12 * s - StallCushion.wantedUs(1, 0.8f)) < 100_000L)
        // Not measured yet: the count alone.
        assertEquals(4 * s, StallCushion.wantedUs(1, null))
        assertEquals(20 * s, StallCushion.wantedUs(4, null))
    }

    @Test fun waitsOnlyGrowWithTheCount() {
        for (room in listOf(0L, 2 * s, 5 * s, 12 * s, 40 * s, 200 * s)) {
            for (rate in listOf(null, 0.5f, 0.97f, 1.1f, 2f)) {
                var last = 0L
                for (n in 1..5) {
                    val wait = StallCushion.waitUs(n, StallCushion.wantedUs(n, rate), room)
                    assertTrue("room $room rate $rate stall $n", wait >= last)
                    last = wait
                }
            }
        }
    }

    // ---- What's kept behind while the film stalls (BackBuffer.keepUs, tight)

    private val sixSeconds = 6 * s

    @Test fun aStallingRemuxKeepsHalfAsMuchBehind() {
        // 84 Mb/s: 10.5 MB a second, 126 MB held over 12 s.
        val held = 126_000_000L
        val usual = BackBuffer.keepUs(24 * mib, held, 12 * s, sixSeconds)
        val tight = BackBuffer.keepUs(24 * mib, held, 12 * s, sixSeconds, tight = true)
        assertEquals(2_396_745L, usual)
        assertEquals(usual / 2, tight)
        // About 12 MiB more for what's ahead.
        val freed = (usual - tight) * (held / 12) / s
        assertTrue(freed in 11 * mib..13 * mib)
    }

    @Test fun tightNeverKeepsUnderASecond() {
        // 300 Mb/s (nothing real): the usual rule's floor is 2 s, the tight one's 1 s.
        assertEquals(BackBuffer.LEAST_KEPT_US, BackBuffer.keepUs(24 * mib, 375_000_000L, 10 * s, sixSeconds))
        assertEquals(BackBuffer.TIGHT_LEAST_KEPT_US, BackBuffer.keepUs(24 * mib, 375_000_000L, 10 * s, sixSeconds, tight = true))
    }

    @Test fun aFilmTheCapDoesntCutKeepsAllItKept() {
        // 20 Mb/s: all 6 s fit under the cap, stalling or not: memory isn't what it's short of.
        val held = 75_000_000L
        assertEquals(sixSeconds, BackBuffer.keepUs(24 * mib, held, 30 * s, sixSeconds))
        assertEquals(sixSeconds, BackBuffer.keepUs(24 * mib, held, 30 * s, sixSeconds, tight = true))
        // No cap at all (TVs with 2.6 GiB and up), or the size not measured yet: Media3's own length.
        assertEquals(15 * s, BackBuffer.keepUs(0L, 126_000_000L, 12 * s, 15 * s, tight = true))
        assertEquals(sixSeconds, BackBuffer.keepUs(24 * mib, 20_000_000L, 2 * s, sixSeconds, tight = true))
    }

    // ---- What the line sustains (DownloadRate.sustainedMbps)

    @Test fun sustainedIsEveryFlatOutSecondNotTheBestThree() {
        val r = DownloadRate()
        var bytes = 0L
        var t = 0L
        r.sample(t, bytes, free = false)
        // A minute between 65 and 82 Mb/s, as measured: 9 MB a second on average.
        for (i in 0 until 60) {
            t += 1_000
            bytes += if (i % 2 == 0) 8_125_000 else 10_250_000
            r.sample(t, bytes, free = true)
        }
        assertEquals(73.5, r.sustainedMbps, 0.1)
        assertEquals(60, r.sustainedSeconds)
        // The log's counts start afresh every 10 s; this doesn't.
        r.take()
        assertEquals(73.5, r.sustainedMbps, 0.1)
        r.reset()
        assertEquals(0.0, r.sustainedMbps, 0.0)
        assertEquals(0, r.sustainedSeconds)
    }

    @Test fun secondsHeldBackByTheFilmDontCount() {
        val r = DownloadRate()
        var bytes = 0L
        var t = 0L
        r.sample(t, bytes, free = false)
        // Twenty seconds flat out at 200 Mb/s.
        repeat(20) {
            t += 1_000
            bytes += 25_000_000
            r.sample(t, bytes, free = true)
        }
        // Then a light film near its two minutes ahead, loading in bursts at its own 26 Mb/s: "free" by the buffer's
        // size, but not flat out. And seconds with the buffer full.
        repeat(30) {
            t += 1_000
            bytes += 3_250_000
            r.sample(t, bytes, free = true, flatOut = false)
        }
        repeat(30) {
            t += 1_000
            bytes += 3_250_000
            r.sample(t, bytes, free = false)
        }
        assertEquals(200.0, r.sustainedMbps, 0.1)
        assertEquals(20, r.sustainedSeconds)
    }

    @Test fun olderSecondsCountForLess() {
        val r = DownloadRate()
        var bytes = 0L
        var t = 0L
        r.sample(t, bytes, free = false)
        // Three minutes at 160 Mb/s, then the line drops to 72 for three more.
        repeat(180) {
            t += 1_000
            bytes += 20_000_000
            r.sample(t, bytes, free = true)
        }
        repeat(180) {
            t += 1_000
            bytes += 9_000_000
            r.sample(t, bytes, free = true)
        }
        assertTrue("${r.sustainedMbps}", r.sustainedMbps < 100.0)
        assertTrue(r.sustainedSeconds <= DownloadRate.SUSTAIN_MEMORY_MS / 1000 + 1)
    }
}
