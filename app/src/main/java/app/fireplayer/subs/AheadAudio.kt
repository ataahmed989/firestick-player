package app.fireplayer.subs

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The film's sound as it loads, ahead of where it plays, for auto sync to listen to before it's on ([SpeechAhead]).
 *
 * Filled on the loading thread by [EmbeddedCues] (only while auto sync listens ahead, and only the sound track
 * playing): each compressed sample as the file has it, with its time in the film, a few seconds of film to a [Piece].
 * Taken on auto sync's thread. Nothing is read twice and nothing extra is loaded: these are the bytes the player
 * loaded anyway, copied once. Bounded: at most [PIECES] pieces of [PIECE_BYTES] (3 MB), made only once something is
 * copied and let go with [release]. When the listener is behind and every piece is taken, samples are let go rather
 * than held: the loading thread never waits here, and a gap only means a little less was heard ahead (it's heard
 * where the film plays instead).
 */
@OptIn(UnstableApi::class)
class AheadAudio(
    /** A piece still being filled is handed over after this long all the same (loading paused, or at the end). */
    private val staleMs: Long = STALE_MS,
) {

    /** A few seconds of one sound track's samples, in order: sample i is bytes[startOf(i) until ends[i]]. */
    class Piece internal constructor(internal val epoch: Int) {
        val bytes = ByteArray(PIECE_BYTES)
        var size = 0
            private set
        val timesUs = LongArray(MAX_SAMPLES)
        val ends = IntArray(MAX_SAMPLES)
        var count = 0
            private set
        var format: Format? = null
            private set
        /** The file's number for the track. */
        var track = -1
            private set
        /** The film (the address the player was given). */
        var uri: String? = null
            private set
        /** Samples were let go just before this one (the listener was behind): a gap, not a jump in the film. */
        var afterDrop = false
            private set
        internal var startedNs = 0L

        fun startOf(i: Int): Int = if (i == 0) 0 else ends[i - 1]
        val firstUs: Long get() = timesUs[0]
        val lastUs: Long get() = timesUs[count - 1]

        internal fun begin(uri: String, track: Int, format: Format, afterDrop: Boolean) {
            this.uri = uri
            this.track = track
            this.format = format
            this.afterDrop = afterDrop
            size = 0
            count = 0
            startedNs = System.nanoTime()
        }

        internal fun add(data: ByteArray, offset: Int, length: Int, timeUs: Long) {
            System.arraycopy(data, offset, bytes, size, length)
            size += length
            timesUs[count] = timeUs
            ends[count] = size
            count++
        }

        internal fun clear() {
            uri = null
            format = null
            size = 0
            count = 0
        }
    }

    private val ready = ArrayBlockingQueue<Piece>(PIECES + 1)
    private val free = ArrayBlockingQueue<Piece>(PIECES + 1)
    // Under this object's lock: the piece the loading thread is filling, how many pieces exist, and the generation
    // they belong to ([release] starts a new one; a piece of an old one coming back is dropped).
    private var filling: Piece? = null
    private var allocated = 0
    private var epoch = 0
    private var dropping = false
    private var lastUs = -1L

    /** Samples let go because every piece was taken, and how much film they held (µs): for auto sync's cost log. */
    @Volatile var droppedSamples = 0L
        private set
    @Volatile var droppedUs = 0L
        private set

    /** Pieces waiting to be listened to. */
    fun pending(): Int = ready.size

    /**
     * On the loading thread: one sample of track [track] of the film [uri], [size] bytes of [data] from [offset], at
     * film time [timeUs]. Never waits: with no piece to put it in, it's let go and counted.
     */
    @Synchronized
    fun sample(uri: String, track: Int, format: Format, data: ByteArray, offset: Int, size: Int, timeUs: Long) {
        if (size <= 0 || size > PIECE_BYTES || timeUs < 0) return
        val prevUs = lastUs
        lastUs = timeUs
        var p = filling
        if (p != null && p.count > 0) {
            val last = p.lastUs
            val gap = timeUs < last || timeUs - last > GAP_US
            if (gap && kotlin.math.abs(timeUs - last) >= JUMP_US && uri == p.uri) {
                // Loading started somewhere else (a skip): what's waiting is of the film left behind.
                drainReady()
                recycleLocked(p)
                filling = null
                p = null
            } else if (gap || p.size + size > PIECE_BYTES || p.count == MAX_SAMPLES || timeUs - p.firstUs >= SPAN_US ||
                p.track != track || p.format !== format || p.uri != uri
            ) {
                handOver(p)
                p = null
            }
        }
        if (p == null) {
            p = filling ?: free.poll() ?: if (allocated < PIECES) { allocated++; Piece(epoch) } else null
            if (p == null) {
                droppedSamples++
                if (prevUs >= 0 && timeUs > prevUs && timeUs - prevUs <= GAP_US) droppedUs += timeUs - prevUs
                dropping = true
                return
            }
            p.begin(uri, track, format, dropping)
            dropping = false
            filling = p
        }
        p.add(data, offset, size, timeUs)
    }

    /**
     * The next piece, waiting up to [waitMs] for one; hand it back with [recycle] once read. A piece being filled
     * for longer than [staleMs] is handed over first (loading has paused).
     */
    fun take(waitMs: Long): Piece? {
        ready.poll()?.let { return it }
        flushStale()
        ready.poll()?.let { return it }
        return if (waitMs <= 0) null else ready.poll(waitMs, TimeUnit.MILLISECONDS)
    }

    fun recycle(piece: Piece) = synchronized(this) { recycleLocked(piece) }

    /** Lets go of every piece (they're made again if copying starts again). */
    @Synchronized
    fun release() {
        epoch++
        filling = null
        ready.clear()
        free.clear()
        allocated = 0
        dropping = false
        lastUs = -1
    }

    @Synchronized
    private fun flushStale() {
        val p = filling ?: return
        if (p.count > 0 && System.nanoTime() - p.startedNs >= staleMs * 1_000_000) handOver(p)
    }

    private fun handOver(p: Piece) {
        filling = null
        if (!ready.offer(p)) recycleLocked(p)
    }

    private fun drainReady() {
        while (true) recycleLocked(ready.poll() ?: return)
    }

    private fun recycleLocked(p: Piece) {
        if (p.epoch != epoch) return
        p.clear()
        free.offer(p)
    }

    companion object {
        // 48 pieces of 64 KB: at most 3 MB; AAC stereo fills a piece with 2 s of film, about 40 KB.
        const val PIECES = 48
        const val PIECE_BYTES = 65_536
        const val MAX_SAMPLES = 512
        // A piece holds at most this much film, so the listener gets it soon; a gap longer than this starts another.
        const val SPAN_US = 2_000_000L
        const val GAP_US = 1_000_000L
        // Further than this from the last sample: loading started somewhere else.
        const val JUMP_US = 10_000_000L
        const val STALE_MS = 1_500L
    }
}
