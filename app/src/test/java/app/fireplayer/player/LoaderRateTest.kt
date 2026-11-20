package app.fireplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The film's download speed while really loading (LoaderRate), and which way it leaves the TV (NetPath). Reads of
 * 8 KiB, as Android's HTTP stack hands them out; 80 Mb/s is 8192 bytes every 819.2 µs.
 */
class LoaderRateTest {

    private val mib = 1L shl 20
    private val ms = 1_000_000L

    /** CPU time of the loading thread: [share] of the wall clock. */
    private class Cpu(val share: Double) {
        var wallNs = 0L
        val clock: () -> Long = { (wallNs * share).toLong() }
    }

    /** [total] bytes in reads of 8 KiB, each taking [readNs] inside the read and [gapNs] after it; gives the end time. */
    private fun feed(c: LoaderRate.Connection, fromNs: Long, total: Long, readNs: Long, gapNs: Long, cpu: Cpu? = null): Long {
        var t = fromNs
        var left = total
        while (left > 0) {
            val n = minOf(left, 8192L).toInt()
            cpu?.wallNs = t + readNs
            c.read(t, t + readNs, n)
            t += readNs + gapNs
            left -= n
        }
        return t
    }

    @Test fun flatOutSpeedLeavesTheWarmUpOut() {
        val r = LoaderRate()
        val c = r.connection { -1L }
        // The first 6 MiB come at once (queued while the connection opened), the rest at 80 Mb/s.
        var t = feed(c, 0L, LoaderRate.WARM_UP_BYTES, readNs = 10_000L, gapNs = 0L)
        t = feed(c, t, 64 * mib, readNs = 819_200L, gapNs = 0L)
        val s = r.take()
        assertEquals(80.0, s.mbps, 0.5)
        assertEquals(100, s.waitPercent)
        assertEquals(70 * mib, s.allBytes)
        assertEquals(-1, s.cpuPercent)
        // All of it was loading time.
        assertEquals(100, s.busyPercent(t / ms))
        // Taken: afresh.
        assertEquals(0.0, r.take().mbps, 0.0)
    }

    @Test fun theAppsOwnTimeBetweenReadsCountsAgainstIt() {
        val r = LoaderRate()
        val c = r.connection { -1L }
        // 700 µs waiting in each read, 119.2 µs of the app's own work after it: still 80 Mb/s, 85% waiting.
        feed(c, 0L, 70 * mib, readNs = 700_000L, gapNs = 119_200L)
        val s = r.take()
        assertEquals(80.0, s.mbps, 0.5)
        assertEquals(85, s.waitPercent)
    }

    @Test fun aHoldFromTheBufferRulesStartsANewRun() {
        val r = LoaderRate()
        val c = r.connection { -1L }
        var t = feed(c, 0L, 40 * mib, readNs = 819_200L, gapNs = 0L)
        val before = r.take()
        // The buffer is full: held for 100 ms (under the idle gap), then what the network queued meanwhile comes at once.
        r.held()
        t = feed(c, t + 100 * ms, 4 * mib, readNs = 10_000L, gapNs = 0L)
        val queued = r.take()
        assertEquals(80.0, before.mbps, 0.5)
        assertEquals(0L, queued.countedNs)
        assertEquals(0.0, queued.mbps, 0.0)
        assertEquals(4 * mib, queued.allBytes)
        // Without the hold the same short pause would be the app's own time, and counted.
        val r2 = LoaderRate()
        val c2 = r2.connection { -1L }
        val t2 = feed(c2, 0L, 40 * mib, readNs = 819_200L, gapNs = 0L)
        r2.take()
        feed(c2, t2 + 100 * ms, 4 * mib, readNs = 819_200L, gapNs = 0L)
        assertTrue(r2.take().countedNs > 100 * ms)
    }

    @Test fun aLongPauseIsNotLoadingAndANewConnectionWarmsUpAgain() {
        val r = LoaderRate()
        val c = r.connection { -1L }
        var t = feed(c, 0L, 10 * mib, readNs = 819_200L, gapNs = 0L)
        r.take()
        // Half a second without a read: idle, not counted, and the next 6 MiB are a warm-up again.
        t = feed(c, t + 500 * ms, LoaderRate.WARM_UP_BYTES, readNs = 819_200L, gapNs = 0L)
        val idle = r.take()
        assertEquals(0L, idle.countedNs)
        // Only the reads were loading, not the pause before them.
        assertEquals(768 * 819_200L, idle.busyNs)
        c.opened()
        feed(c, t, 2 * mib, readNs = 819_200L, gapNs = 0L)
        assertEquals(0L, r.take().countedNs)
    }

    @Test fun cpuIsTheLoadingThreadsShareWhileCounted() {
        val r = LoaderRate()
        val cpu = Cpu(0.3)
        val c = r.connection(cpu.clock)
        feed(c, 0L, 70 * mib, readNs = 819_200L, gapNs = 0L, cpu = cpu)
        val s = r.take()
        assertEquals(30, s.cpuPercent)
        assertTrue(s.cpuWallNs > 5_000 * ms)
    }

    @Test fun cpuNotKnownIsLeftOut() {
        val r = LoaderRate()
        val c = r.connection { -1L }
        feed(c, 0L, 20 * mib, readNs = 819_200L, gapNs = 0L)
        assertEquals(-1, r.take().cpuPercent)
    }

    // ---- Which way the film goes (NetPath)

    @Test fun anExitNodeCarriesTheInternetTailscalesOwnRoutesDont() {
        // No exit node: Tailscale's addresses, its IPv6 range, a home subnet shared through it.
        assertFalse(NetPath.routesInternet(listOf(true to 10, false to 48, true to 24, true to 8)))
        assertFalse(NetPath.routesInternet(emptyList()))
        // An exit node: a default route, IPv4 or IPv6.
        assertTrue(NetPath.routesInternet(listOf(true to 10, true to 0)))
        assertTrue(NetPath.routesInternet(listOf(false to 0)))
        // An exit node leaving the home network out: many routes, together most addresses.
        assertTrue(NetPath.routesInternet(listOf(true to 1, true to 2, true to 5, true to 10)))
        assertTrue(NetPath.routesInternet(listOf(false to 3)))
    }

    @Test fun othersIsWhatTheRestOfTheTvReceived() {
        val before = longArrayOf(1_000L, 400L)
        // 10 MB came to the TV in a second, 9 MB of it to this app: 1 MB to the others, 8 Mb/s.
        assertEquals(8.0, NetPath.othersMbps(before, longArrayOf(10_001_000L, 9_000_400L), 1_000L), 0.001)
        // Not known, or counts started over: no figure.
        assertEquals(-1.0, NetPath.othersMbps(longArrayOf(-1L, -1L), longArrayOf(5L, 5L), 1_000L), 0.0)
        assertEquals(-1.0, NetPath.othersMbps(before, longArrayOf(500L, 600L), 1_000L), 0.0)
        assertEquals(-1.0, NetPath.othersMbps(before, before, 0L), 0.0)
    }
}
