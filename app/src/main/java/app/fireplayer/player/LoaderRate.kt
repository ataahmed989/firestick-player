package app.fireplayer.player

import android.net.Uri
import android.os.Debug
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.atomic.AtomicInteger

/**
 * How fast the film's connection brings it while the player is really loading, for the FirePlayerDiag log: to tell on
 * the TV whether the app or the line holds one download back (player-fixes-wip/THROUGHPUT.md).
 *
 * The play line's "down=" is bytes over whole seconds, pauses included: once the buffer is full the film is only topped
 * up as it plays, so "down=" falls to the film's own rate whatever the line could do. Here only time spent loading
 * counts: reads one after another on a connection with no hold from the buffer rules in between ([held]) and no gap
 * longer than [IDLE_GAP_NS]. Such a run's first [WARM_UP_BYTES] aren't counted either: after a pause they come at once
 * from what the TV's network stack queued meanwhile, and the server's TCP starts slow again, so they say nothing about
 * the line.
 *
 * Over the time counted it also keeps the share spent inside the connection's read, waiting on the network (and TLS),
 * the rest being the app's own work between reads (the MKV extractor, the copy into the buffer, the hand-off to the
 * playback thread every megabyte); and the loading thread's CPU time. A loader that waits on the network most of the
 * time with CPU to spare isn't held back by the app.
 *
 * [held] on the playback thread; each connection's reads on its loading thread ([Timed]); [take] on any thread.
 */
@OptIn(UnstableApi::class)
class LoaderRate {
    private val holds = AtomicInteger()
    // Since the last take, under this object's lock.
    private var countedBytes = 0L
    private var countedNs = 0L
    private var waitNs = 0L
    private var cpuNs = 0L
    private var cpuWallNs = 0L
    private var busyNs = 0L
    private var allBytes = 0L
    // The loading thread's CPU time can be read here (Debug.threadCpuTimeNanos gives -1 where it can't).
    @Volatile private var cpuKnown = true

    /** The buffer rules held loading back (the buffer is full): the next read starts a new run. Playback thread. */
    fun held() {
        holds.incrementAndGet()
    }

    /** What came since the last take, and starts afresh. */
    @Synchronized
    fun take(): Sample {
        val s = Sample(countedBytes, countedNs, waitNs, if (cpuWallNs > 0) cpuNs else -1L, cpuWallNs, busyNs, allBytes)
        countedBytes = 0L
        countedNs = 0L
        waitNs = 0L
        cpuNs = 0L
        cpuWallNs = 0L
        busyNs = 0L
        allBytes = 0L
        return s
    }

    @Synchronized
    private fun add(bytes: Int, busy: Long, counted: Boolean, readNs: Long, cpu: Long, cpuWall: Long) {
        allBytes += bytes
        busyNs += busy
        if (counted) {
            countedBytes += bytes
            countedNs += busy
            waitNs += readNs
        }
        cpuNs += cpu
        cpuWallNs += cpuWall
    }

    /** One connection's reads ([cpuClock]: the calling thread's CPU time in ns). On its loading thread only. */
    fun connection(cpuClock: () -> Long): Connection = Connection(cpuClock)

    inner class Connection internal constructor(private val cpuClock: () -> Long) {
        private var lastEndNs = -1L
        private var holdsSeen = holds.get()
        // Bytes of this run still to come before any is counted.
        private var warm = WARM_UP_BYTES
        // The thread's CPU time at cpuMarkNs; below 0: not marked in this run yet.
        private var cpuMark = -1L
        private var cpuMarkNs = 0L

        /** A new connection: TCP starts slow again, so a new run. */
        fun opened() {
            lastEndNs = -1L
        }

        /** A read began at [startNs] and gave [bytes] by [endNs] (System.nanoTime). */
        fun read(startNs: Long, endNs: Long, bytes: Int) {
            val h = holds.get()
            val gap = startNs - lastEndNs
            val run = lastEndNs >= 0L && h == holdsSeen && gap in 0..IDLE_GAP_NS
            holdsSeen = h
            lastEndNs = endNs
            if (!run) {
                warm = WARM_UP_BYTES
                cpuMark = -1L
            }
            val readNs = (endNs - startNs).coerceAtLeast(0L)
            val busy = readNs + if (run) gap else 0L
            val counted = warm <= 0L
            if (!counted) warm -= bytes
            var cpu = 0L
            var cpuWall = 0L
            if (counted && cpuKnown && (cpuMark < 0L || endNs - cpuMarkNs >= CPU_EVERY_NS)) {
                val now = cpuClock()
                if (now < 0L) {
                    cpuKnown = false
                } else {
                    if (cpuMark >= 0L && now >= cpuMark) {
                        cpu = now - cpuMark
                        cpuWall = endNs - cpuMarkNs
                    }
                    cpuMark = now
                    cpuMarkNs = endNs
                }
            }
            add(bytes, busy, counted, readNs, cpu, cpuWall)
        }
    }

    /**
     * What came between two [take]s. [countedBytes] over [countedNs] is the speed while loading flat out ([mbps]);
     * [waitNs] of that was inside the connection's read; [cpuNs] of the loading thread's CPU over [cpuWallNs] of it
     * (-1: not known). [busyNs] is all the time spent loading, warm-ups included, and [allBytes] all that came.
     */
    class Sample(
        val countedBytes: Long,
        val countedNs: Long,
        val waitNs: Long,
        val cpuNs: Long,
        val cpuWallNs: Long,
        val busyNs: Long,
        val allBytes: Long,
    ) {
        /** Mb/s while loading flat out; 0 with nothing counted. */
        val mbps: Double get() = if (countedNs <= 0L) 0.0 else countedBytes * 8_000.0 / countedNs

        /** Percent of the time counted spent waiting inside the read; -1 with nothing counted. */
        val waitPercent: Int get() = percent(waitNs, countedNs)

        /** Percent of one core the loading thread used while counted; -1 when not known. */
        val cpuPercent: Int get() = if (cpuNs < 0L) -1 else percent(cpuNs, cpuWallNs)

        /** Percent of [windowMs] spent loading at all (two connections at once can make it more than 100). */
        fun busyPercent(windowMs: Long): Int = percent(busyNs, windowMs * 1_000_000L)

        private fun percent(part: Long, whole: Long): Int = if (whole <= 0L) -1 else ((part * 100 + whole / 2) / whole).toInt()
    }

    /** [upstream], the film's network connection, its reads timed for [rate]. */
    class Timed(private val upstream: DataSource, rate: LoaderRate) : DataSource {
        private val connection = rate.connection(CPU_CLOCK)

        override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            connection.opened()
            return upstream.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val start = System.nanoTime()
            val n = upstream.read(buffer, offset, length)
            if (n > 0) connection.read(start, System.nanoTime(), n)
            return n
        }

        override fun getUri(): Uri? = upstream.uri

        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

        override fun close() = upstream.close()

        class Factory(private val upstream: DataSource.Factory, private val rate: LoaderRate) : DataSource.Factory {
            override fun createDataSource(): DataSource = Timed(upstream.createDataSource(), rate)
        }
    }

    companion object {
        /** A pause longer than this between two reads is the player not loading (as in [RedirectMemoryDataSource]). */
        const val IDLE_GAP_NS = 250_000_000L
        /** Not counted at the start of each run (as in [RedirectMemoryDataSource]): queued data, and TCP's slow start. */
        const val WARM_UP_BYTES = 6L * 1024 * 1024
        /** The loading thread's CPU time is read at most this often. */
        const val CPU_EVERY_NS = 250_000_000L
        private val CPU_CLOCK: () -> Long = { Debug.threadCpuTimeNanos() }
    }
}
