package app.fireplayer.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.util.concurrent.atomic.AtomicLong

/**
 * The film's bytes as they come from the network, counted for [DownloadRate]. Given to the film's HTTP connection
 * ([PlayerFactory.build]); any thread.
 */
@OptIn(UnstableApi::class)
class ByteMeter : TransferListener {
    private val total = AtomicLong()

    /** Bytes that have come so far. */
    val bytes: Long get() = total.get()

    /** The same connection's speed while really loading, pauses left out ([LoaderRate]). */
    val loader = LoaderRate()

    /** Bytes that came outside the film's own connection: a second request's ([TwoRanges]). */
    fun add(bytes: Int) {
        total.addAndGet(bytes.toLong())
    }

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        if (isNetwork) total.addAndGet(bytesTransferred.toLong())
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
}

/**
 * How fast the film downloads, for the FirePlayerDiag log: to tell on the TV whether Real-Debrid holds one connection
 * to a speed (which a second connection would get round) before any second connection is built.
 *
 * Once the film's buffer is full the download only keeps pace with the film (84 Mb/s for an 84 Mb/s film on any line),
 * which says nothing about the line. So seconds are kept apart: all of them ([mbps], since the last [take]), and
 * those in which the film loaded freely, the buffer not yet full ([freeMbps], [freeSeconds]). The fastest stretch of
 * [BEST_SECONDS] free seconds in a row since the film started is [bestFreeMbps]: what one connection has been seen to
 * carry. Compared on the TV with the line's own speed, it shows whether the server or the line is the limit.
 *
 * And what the line keeps up, not only its best three seconds: every free second of the last minutes together
 * ([sustainedMbps], over [sustainedSeconds]). A film whose own rate is close to that, or above it, can't be kept from
 * stalling by any buffer (see [app.fireplayer.vod.play.FilmSupply]).
 *
 * Fed once a second on the main thread ([sample]); nothing is made after it's built.
 */
class DownloadRate {
    private var lastBytes = -1L
    private var lastMs = 0L
    private var bytes = 0L
    private var ms = 0L
    private var freeBytes = 0L
    private var freeMs = 0L
    // Free seconds since the film started, the older ones counting for less ([SUSTAIN_MEMORY_MS]).
    private var longBytes = 0L
    private var longMs = 0L
    // The latest free seconds in a row, newest last: bytes and ms of each.
    private val runBytes = LongArray(BEST_SECONDS)
    private val runMs = LongArray(BEST_SECONDS)
    private var run = 0

    /** The fastest [BEST_SECONDS] free seconds in a row so far, in Mb/s; 0 before there were any. */
    var bestFreeMbps = 0.0
        private set

    /**
     * Mb/s over the seconds the film loaded freely, mostly the last [SUSTAIN_MEMORY_MS] of them: what this line keeps
     * up from this server. 0 with none; [take] doesn't start it afresh, [reset] does.
     */
    val sustainedMbps: Double get() = rate(longBytes, longMs)

    /** How many free seconds [sustainedMbps] is over. */
    val sustainedSeconds: Int get() = ((longMs + 500) / 1000).toInt()

    /** Seconds loaded freely since the last [take]. */
    val freeSeconds: Int get() = ((freeMs + 500) / 1000).toInt()

    /** Mb/s over every second since the last [take]; 0 with none. */
    val mbps: Double get() = rate(bytes, ms)

    /** Mb/s over the seconds loaded freely since the last [take]; 0 with none. */
    val freeMbps: Double get() = rate(freeBytes, freeMs)

    /**
     * [bytesSoFar] have come by [nowMs] (any clock in ms that only goes forward). [free]: the film loaded all of this
     * last second with room left in its buffer, so the line and the server set the speed, not the film's own rate.
     * [flatOut]: and with less of the film ahead than it ever stops loading at, so it loaded the whole second through
     * (a light film near its two minutes ahead loads in short bursts, each of which looks like loading): only such
     * seconds count for [sustainedMbps].
     */
    fun sample(nowMs: Long, bytesSoFar: Long, free: Boolean, flatOut: Boolean = free) {
        val before = lastBytes
        val since = nowMs - lastMs
        lastBytes = bytesSoFar
        lastMs = nowMs
        // The first sample, a new connection's count starting over, or a long gap (the player was away): from here on.
        if (before < 0L || bytesSoFar < before || since <= 0L || since > MAX_GAP_MS) {
            run = 0
            return
        }
        val got = bytesSoFar - before
        bytes += got
        ms += since
        if (!free) {
            run = 0
            return
        }
        freeBytes += got
        freeMs += since
        if (flatOut) {
            longBytes += got
            longMs += since
            // Mostly the last minutes, so it follows the line as the evening goes.
            if (longMs > SUSTAIN_MEMORY_MS) {
                longBytes /= 2
                longMs /= 2
            }
        }
        if (run == BEST_SECONDS) {
            System.arraycopy(runBytes, 1, runBytes, 0, BEST_SECONDS - 1)
            System.arraycopy(runMs, 1, runMs, 0, BEST_SECONDS - 1)
            run--
        }
        runBytes[run] = got
        runMs[run] = since
        run++
        if (run == BEST_SECONDS) {
            var b = 0L
            var t = 0L
            for (i in 0 until BEST_SECONDS) {
                b += runBytes[i]
                t += runMs[i]
            }
            val r = rate(b, t)
            if (r > bestFreeMbps) bestFreeMbps = r
        }
    }

    /** Starts the counts since the last log line afresh ([bestFreeMbps] stays). */
    fun take() {
        bytes = 0L
        ms = 0L
        freeBytes = 0L
        freeMs = 0L
    }

    /** A new player or film: everything afresh. */
    fun reset() {
        take()
        longBytes = 0L
        longMs = 0L
        lastBytes = -1L
        run = 0
        bestFreeMbps = 0.0
    }

    companion object {
        /** A stretch of this many free seconds in a row is long enough to call a speed. */
        const val BEST_SECONDS = 3
        private const val MAX_GAP_MS = 5_000L
        /** [sustainedMbps] halves what it holds each time it holds this many free seconds. */
        const val SUSTAIN_MEMORY_MS = 180_000L

        /** A buffer filled to less than this share of what it fills to still has room: loading isn't held back by it. */
        const val FREE_BELOW = 0.85

        /** With less than this ahead the film loads without a pause (it stops at two minutes ahead, or a full buffer). */
        const val FLAT_OUT_BELOW_MS = 60_000L

        private fun rate(bytes: Long, ms: Long): Double = if (ms <= 0L) 0.0 else bytes * 8.0 / 1_000.0 / ms
    }
}
