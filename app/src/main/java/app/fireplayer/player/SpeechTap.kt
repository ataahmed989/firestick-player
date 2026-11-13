package app.fireplayer.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A copy of the sound the player is playing, for subtitle auto sync to listen to (see [app.fireplayer.subs.AutoSync]).
 *
 * Only while [listening]: otherwise the sound goes by untouched and this costs nothing. The copy is the sound as it
 * reaches the speakers' side of the player, so no second connection to the provider is ever opened: with surround
 * passthrough on it's the Dolby (or DTS, AAC) frames as they go to the TV, which the listener decodes itself; with it
 * off, the decoded samples. Each piece carries its time in the film. When the listener falls behind, pieces are
 * dropped rather than held: a gap only means a little less was heard.
 */
class SpeechTap {

    /** How the sound in a [Piece] is stored. [encoded] means compressed frames of [mimeType]. */
    class Kind(val format: Format, val encoded: Boolean, val pcmEncoding: Int, val channels: Int, val sampleRate: Int)

    class Piece(val bytes: ByteArray, var size: Int, var filmUs: Long, var kind: Kind?)

    @Volatile var listening = false
        set(value) {
            field = value
            if (!value) {
                // Nobody listens: the pieces are let go, waiting or spare, so nothing is held for the rest of the film.
                // One still being read comes back through [recycle] and is the first used again.
                ready.clear()
                free.clear()
            }
        }

    /** Pieces let go because the listener was behind: a gap in what's heard. Counted for auto sync's cost log. */
    @Volatile var dropped = 0L
        private set

    private val ready = ArrayBlockingQueue<Piece>(PIECES)
    private val free = ArrayBlockingQueue<Piece>(PIECES)

    /** The next piece of sound, waiting up to [waitMs]; hand it back with [recycle] once read. */
    fun take(waitMs: Long): Piece? = ready.poll(waitMs, TimeUnit.MILLISECONDS)

    fun recycle(piece: Piece) {
        piece.kind = null
        free.offer(piece)
    }

    /** Called on the playback thread: copies [buffer] (without moving it) if anyone is listening. */
    internal fun offer(buffer: ByteBuffer, filmUs: Long, kind: Kind) {
        if (!listening) return
        val size = buffer.remaining()
        if (size <= 0 || size > PIECE_BYTES) return
        if (ready.size >= PIECES) { dropped++; return }
        // A spare piece, or a new one as big as the sound needs: only made with none spare and room to wait in, so
        // there are never more than can wait (and the one being read). One too small for this sound is replaced.
        var piece = free.poll()
        if (piece == null || piece.bytes.size < size) piece = Piece(ByteArray(maxOf(size, MIN_PIECE_BYTES)), 0, 0, null)
        buffer.duplicate().get(piece.bytes, 0, size)
        piece.size = size
        piece.filmUs = filmUs
        piece.kind = kind
        if (!ready.offer(piece)) { recycle(piece); dropped++ }
    }

    companion object {
        // At most 48 pieces waiting, each as big as the sound handed over at a time (8 KB or more, 64 KB at most):
        // usually a few hundred KB, 3 MB at the very most, only while listening, and given back when it stops.
        internal const val PIECES = 48
        private const val PIECE_BYTES = 65_536
        internal const val MIN_PIECE_BYTES = 8 * 1024

        /** What auto sync can make sense of: plain 16-bit or float samples, or compressed sound FFmpeg decodes. */
        fun kindOf(format: Format): Kind? {
            val mime = format.sampleMimeType ?: return null
            if (mime == MimeTypes.AUDIO_RAW) {
                val pcm = format.pcmEncoding
                if (pcm != C.ENCODING_PCM_16BIT && pcm != C.ENCODING_PCM_FLOAT) return null
                return Kind(format, false, pcm, format.channelCount, format.sampleRate)
            }
            // TrueHD (Blu-ray lossless) is the one too heavy to decode on the side.
            if (mime == MimeTypes.AUDIO_TRUEHD) return null
            return Kind(format, true, C.ENCODING_INVALID, format.channelCount, format.sampleRate)
        }
    }
}

/** Hands a copy of each piece of sound to [tap] on its way to [sink]. */
@OptIn(UnstableApi::class)
class SpeechTapAudioSink(sink: AudioSink, private val tap: SpeechTap) : ForwardingAudioSink(sink) {

    @Volatile private var kind: SpeechTap.Kind? = null
    private var streamOffsetUs = 0L
    // The sink is handed the same buffer again until it takes all of it: copied the first time only.
    private var lastBuffer: ByteBuffer? = null
    private var lastTimeUs = Long.MIN_VALUE

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        kind = SpeechTap.kindOf(inputFormat)
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        streamOffsetUs = outputStreamOffsetUs
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (tap.listening && (buffer !== lastBuffer || presentationTimeUs != lastTimeUs)) {
            lastBuffer = buffer
            lastTimeUs = presentationTimeUs
            kind?.let { tap.offer(buffer, presentationTimeUs - streamOffsetUs, it) }
        }
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun flush() {
        lastBuffer = null
        lastTimeUs = Long.MIN_VALUE
        super.flush()
    }
}
