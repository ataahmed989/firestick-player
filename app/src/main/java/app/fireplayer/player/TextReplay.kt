package app.fireplayer.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import java.util.ArrayDeque
import java.util.TreeMap

/**
 * The text renderer on a clock shifted by the subtitle delay, with the lines it would otherwise miss handed to it again.
 *
 * Media3 reads a subtitle stream on from where the film is, and drops the lines it has gone past. With the subtitles
 * set later (a delay above zero) the lines due now lie before that: after a skip none showed until the delay's worth
 * of film had played (15 s with a delay of 15 s), and after the delay was turned up (by hand, or by auto sync) none
 * until the clock had caught up. So the lines read are kept ([ReplayStream]: a film's subtitles are a few hundred KB)
 * and handed over again from a little before the shifted clock: after every position reset (a skip), and when the
 * delay has gone up by enough to see.
 *
 * Only what the renderer is handed changes: nothing of the delay itself, of what sets it, or of the film's sound and
 * picture. Anything unexpected and the stream is read as it always was.
 */
@OptIn(UnstableApi::class)
internal class TextOffsetRenderer(
    renderer: Renderer,
    private val shiftUs: () -> Long,
) : ForwardingRenderer(renderer) {
    // The stream the player handed over (it asks for it back, and compares), and the one the renderer reads instead.
    private var original: SampleStream? = null
    private var replay: ReplayStream? = null
    private var streamOffsetUs = 0L
    // The shift the lines the renderer holds are complete for: a later one (the delay turned down) drops none.
    private var heldShiftUs = Long.MIN_VALUE

    override fun enable(
        configuration: RendererConfiguration, formats: Array<Format>, stream: SampleStream, positionUs: Long, joining: Boolean,
        mayRenderStartOfStream: Boolean, startPositionUs: Long, offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        super.enable(configuration, formats, wrap(formats, stream, offsetUs), positionUs, joining, mayRenderStartOfStream, startPositionUs, offsetUs, mediaPeriodId)
        replayAround(positionUs)
    }

    override fun replaceStream(
        formats: Array<Format>, stream: SampleStream, startPositionUs: Long, offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        super.replaceStream(formats, wrap(formats, stream, offsetUs), startPositionUs, offsetUs, mediaPeriodId)
        heldShiftUs = shiftUs()
    }

    /** The player's own stream, as it handed it over: it tells by this whether the renderer is on the stream it means. */
    override fun getStream(): SampleStream? {
        val read = super.getStream()
        return if (read != null && read === replay) original else read
    }

    override fun disable() {
        super.disable()
        original = null
        replay = null
        heldShiftUs = Long.MIN_VALUE
    }

    override fun resetPosition(positionUs: Long) {
        super.resetPosition(positionUs)
        replayAround(positionUs)
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val shift = shiftUs()
        if (replay != null && heldShiftUs != Long.MIN_VALUE && shift <= heldShiftUs - REPLAY_STEP_US) {
            // The delay went up: the lines due now were dropped as gone by. The renderer starts afresh where it is (as
            // it does after a skip, its stream's end kept as the player marked it) and is handed the kept ones.
            val wasFinal = isCurrentStreamFinal
            super.resetPosition(positionUs)
            if (wasFinal) setCurrentStreamFinal()
            replayAround(positionUs)
        } else if (shift > heldShiftUs) {
            heldShiftUs = shift
        }
        super.render(positionUs + shift, elapsedRealtimeUs)
    }

    private fun wrap(formats: Array<Format>, stream: SampleStream, offsetUs: Long): SampleStream {
        streamOffsetUs = offsetUs
        original = stream
        // Only lines parsed as the file is read, each a sample of its own, which the renderer keeps side by side
        // whatever order they come in. Anything else is read as it always was.
        val plain = formats.isNotEmpty() && formats.all {
            it.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES && it.cueReplacementBehavior == Format.CUE_REPLACEMENT_BEHAVIOR_MERGE
        }
        replay = if (plain) ReplayStream(stream) else null
        return replay ?: stream
    }

    private fun replayAround(positionUs: Long) {
        val shift = shiftUs()
        heldShiftUs = shift
        replay?.replayFrom(positionUs + shift - streamOffsetUs)
    }

    private companion object {
        // The delay turned up by this much or more: the lines due now are handed over again (less isn't seen).
        const val REPLAY_STEP_US = 250_000L
    }
}

/**
 * A subtitle stream that keeps the lines read from it and can hand them over again ([replayFrom]): those from
 * [LOOKBACK_US] before a time up to where the stream itself goes on from, the ones nearest before that time first (the
 * line showing now is back on the next pass). Each line once: the stream's own are never doubled. Read from the
 * playback thread only.
 */
@OptIn(UnstableApi::class)
internal class ReplayStream(private val inner: SampleStream) : SampleStream {
    private class Kept(val timeUs: Long, val keyFrame: Boolean, val data: ByteArray)

    // The lines read, by their time in the stream (two can share one).
    private val kept = TreeMap<Long, ArrayList<Kept>>()
    private var keptBytes = 0
    // Asked for: from when, around which time; then the ones being handed over.
    private var pending = false
    private var aroundUs = 0L
    private var queue: ArrayDeque<Kept>? = null
    // Something unexpected happened: read as it always was from then on.
    private var broken = false

    /** How many lines are kept, for tests. */
    val keptCount: Int get() = kept.values.sumOf { it.size }

    /** The lines kept from a little before [timeUs] (stream time) are handed over again before the stream goes on. */
    fun replayFrom(timeUs: Long) {
        if (broken) return
        aroundUs = timeUs
        pending = true
        queue = null
    }

    override fun isReady(): Boolean = queue?.isNotEmpty() == true || inner.isReady
    override fun maybeThrowError() = inner.maybeThrowError()
    override fun skipData(positionUs: Long): Int = inner.skipData(positionUs)

    override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
        val special = SampleStream.FLAG_PEEK or SampleStream.FLAG_OMIT_SAMPLE_DATA or SampleStream.FLAG_REQUIRE_FORMAT
        if (broken || readFlags and special != 0) return inner.readData(formatHolder, buffer, readFlags)
        try {
            queue?.let { q ->
                val next = q.pollFirst()
                if (next != null) {
                    fill(buffer, next)
                    return C.RESULT_BUFFER_READ
                }
                queue = null
            }
            if (pending) {
                // Where the stream itself goes on from (looked at, not taken): the kept lines before that come first.
                val peeked = inner.readData(formatHolder, buffer, readFlags or SampleStream.FLAG_PEEK)
                if (peeked != C.RESULT_BUFFER_READ) return peeked
                val untilUs = if (buffer.isEndOfStream) Long.MAX_VALUE else buffer.timeUs
                buffer.clear()
                pending = false
                val q = lines(aroundUs, untilUs)
                val first = q.pollFirst()
                if (first != null) {
                    queue = q
                    fill(buffer, first)
                    return C.RESULT_BUFFER_READ
                }
            }
            val result = inner.readData(formatHolder, buffer, readFlags)
            if (result == C.RESULT_BUFFER_READ && !buffer.isEndOfStream) keep(buffer)
            return result
        } catch (e: RuntimeException) {
            broken = true
            pending = false
            queue = null
            kept.clear()
            buffer.clear()
            return inner.readData(formatHolder, buffer, readFlags)
        }
    }

    // The kept lines from LOOKBACK_US before [aroundUs] up to [untilUs]: nearest before the time first, then on from it.
    private fun lines(aroundUs: Long, untilUs: Long): ArrayDeque<Kept> {
        val q = ArrayDeque<Kept>()
        if (kept.isEmpty()) return q
        val from = aroundUs - LOOKBACK_US
        if (from >= untilUs) return q
        val split = minOf(aroundUs, untilUs - 1)
        for (list in kept.subMap(from, true, split, true).descendingMap().values) q.addAll(list)
        if (split < untilUs - 1) for (list in kept.subMap(split, false, untilUs, false).values) q.addAll(list)
        return q
    }

    private fun keep(buffer: DecoderInputBuffer) {
        val data = buffer.data ?: return
        val size = data.position()
        if (size <= 0 || size > MAX_LINE_BYTES) return
        val bytes = ByteArray(size)
        val copy = data.duplicate()
        copy.flip()
        copy.get(bytes)
        val at = kept.getOrPut(buffer.timeUs) { ArrayList(1) }
        if (at.any { it.data.contentEquals(bytes) }) return
        at += Kept(buffer.timeUs, buffer.isKeyFrame, bytes)
        keptBytes += size
        // Far more than any film's subtitles: let go rather than grow on.
        if (keptBytes > MAX_KEPT_BYTES) {
            kept.clear()
            keptBytes = 0
        }
    }

    private fun fill(buffer: DecoderInputBuffer, line: Kept) {
        buffer.clear()
        buffer.timeUs = line.timeUs
        buffer.setFlags(if (line.keyFrame) C.BUFFER_FLAG_KEY_FRAME else 0)
        buffer.ensureSpaceForWrite(line.data.size)
        buffer.data!!.put(line.data)
    }

    companion object {
        // A line can have started this long before the time asked for and still be showing.
        const val LOOKBACK_US = 30_000_000L
        private const val MAX_LINE_BYTES = 64 * 1024
        private const val MAX_KEPT_BYTES = 4 * 1024 * 1024
    }
}
