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
        if (replay?.complete == true && heldShiftUs != Long.MIN_VALUE && shift <= heldShiftUs - REPLAY_STEP_US) {
            // The delay went up: the lines due now were dropped as gone by. The renderer starts afresh where it is (as
            // it does after a skip, its stream's end kept as the player marked it) and is handed the kept ones: only
            // while every line read is kept, as the lines to come are among them.
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
 * A subtitle stream that keeps the lines read from it and hands them over again when asked ([replayFrom]): those from
 * [LOOKBACK_US] before a time up to where the stream itself goes on from, the ones nearest before that time first (so
 * the line showing now is back on the next pass), then the stream's own. Every line is handed over once after each
 * [replayFrom], whichever way it comes; none is lost and none doubled. Should anything unexpected happen, or the lines
 * outgrow what's kept ([complete] is false then), it reads on as the stream alone would. Playback thread only.
 */
@OptIn(UnstableApi::class)
internal class ReplayStream(private val inner: SampleStream) : SampleStream {
    private class Line(val timeUs: Long, val keyFrame: Boolean, val data: ByteArray) {
        // The [epoch] it was last handed over in.
        var given = -1
    }

    // The lines read, by their time in the stream (two can share one).
    private val kept = TreeMap<Long, ArrayList<Line>>()
    private var keptBytes = 0
    // Goes up with every [replayFrom]: a line is handed over once in each.
    private var epoch = 0
    // Asked for, around which time; the kept lines being handed over; and the stream's own next line, read to know
    // where it goes on from, handed over after them.
    private var pending = false
    private var aroundUs = 0L
    private val queue = ArrayDeque<Line>()
    private var next: Line? = null
    private var broken = false

    /** Every line read so far is kept: the renderer can be started afresh from them. */
    var complete = true
        private set

    /** How many lines are kept, for tests. */
    val keptCount: Int get() = kept.values.sumOf { it.size }

    /** The lines kept from a little before [timeUs] (stream time) are handed over again before the stream goes on. */
    fun replayFrom(timeUs: Long) {
        if (broken) return
        epoch++
        aroundUs = timeUs
        pending = true
        queue.clear()
        // A line of the stream's own read ahead of time and not handed over yet is still to come: first in the queue.
        next?.let { queue.add(it) }
        next = null
    }

    override fun isReady(): Boolean = queue.isNotEmpty() || next != null || inner.isReady
    override fun maybeThrowError() = inner.maybeThrowError()
    override fun skipData(positionUs: Long): Int = inner.skipData(positionUs)

    override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
        val special = SampleStream.FLAG_PEEK or SampleStream.FLAG_OMIT_SAMPLE_DATA or SampleStream.FLAG_REQUIRE_FORMAT
        if (broken || readFlags and special != 0) return inner.readData(formatHolder, buffer, readFlags)
        return try {
            read(formatHolder, buffer, readFlags)
        } catch (e: RuntimeException) {
            // Never expected; rather than stop the film, read on as the stream alone would.
            broken = true
            complete = false
            pending = false
            queue.clear()
            next = null
            buffer.clear()
            inner.readData(formatHolder, buffer, readFlags)
        }
    }

    private fun read(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
        if (pending) {
            // Where the stream itself goes on from: its next line is read (and kept for after the kept ones before it).
            val result = inner.readData(formatHolder, buffer, readFlags)
            if (result != C.RESULT_BUFFER_READ) return result
            pending = false
            if (buffer.isEndOfStream) {
                // Nothing more of its own: all the kept ones from there on. The stream says it has ended again later.
                buffer.clear()
                enqueue(Long.MAX_VALUE)
            } else {
                val line = keep(buffer)
                buffer.clear()
                enqueue(line.timeUs)
                if (line.given != epoch && line !in queue) next = line
            }
        }
        while (true) {
            val line = queue.pollFirst() ?: break
            if (line.given == epoch) continue
            return hand(buffer, line)
        }
        next?.let {
            next = null
            if (it.given != epoch) return hand(buffer, it)
        }
        // The stream's own, each once: one handed over already since [replayFrom] (kept, at the very time the stream
        // went on from) is passed by.
        var passes = 0
        while (true) {
            val result = inner.readData(formatHolder, buffer, readFlags)
            if (result != C.RESULT_BUFFER_READ || buffer.isEndOfStream) return result
            val line = keep(buffer)
            if (line.given != epoch) {
                line.given = epoch
                return result
            }
            buffer.clear()
            if (++passes >= MAX_PASSES) return C.RESULT_NOTHING_READ
        }
    }

    // The kept lines from LOOKBACK_US before the time asked for up to [untilUs] (the stream's own next line's time, that
    // line apart): nearest before the time first, then on from it.
    private fun enqueue(untilUs: Long) {
        if (broken || kept.isEmpty()) return
        val from = aroundUs - LOOKBACK_US
        if (from > untilUs) return
        val split = minOf(aroundUs, untilUs)
        for (list in kept.subMap(from, true, split, true).descendingMap().values) queue.addAll(list)
        if (split < untilUs) for (list in kept.subMap(split, false, untilUs, true).values) queue.addAll(list)
    }

    /** The line in [buffer] as kept: the one kept before if it was read before, else kept now (if there's room). */
    private fun keep(buffer: DecoderInputBuffer): Line {
        val data = buffer.data
        val size = data?.position() ?: 0
        val bytes = ByteArray(size)
        if (data != null && size > 0) {
            val copy = data.duplicate()
            copy.flip()
            copy.get(bytes)
        }
        kept[buffer.timeUs]?.firstOrNull { it.data.contentEquals(bytes) }?.let { return it }
        val line = Line(buffer.timeUs, buffer.isKeyFrame, bytes)
        if (broken || !complete || size > MAX_LINE_BYTES || keptBytes + size > MAX_KEPT_BYTES) {
            // More than any film's subtitles should come to: read on, but not every line is kept from here.
            complete = false
            return line
        }
        kept.getOrPut(line.timeUs) { ArrayList(1) }.add(line)
        keptBytes += size
        return line
    }

    private fun hand(buffer: DecoderInputBuffer, line: Line): Int {
        line.given = epoch
        buffer.clear()
        buffer.timeUs = line.timeUs
        buffer.setFlags(if (line.keyFrame) C.BUFFER_FLAG_KEY_FRAME else 0)
        buffer.ensureSpaceForWrite(line.data.size)
        buffer.data!!.put(line.data)
        return C.RESULT_BUFFER_READ
    }

    companion object {
        // A line can have started this long before the time asked for and still be showing.
        const val LOOKBACK_US = 30_000_000L
        private const val MAX_LINE_BYTES = 256 * 1024
        private const val MAX_KEPT_BYTES = 8 * 1024 * 1024
        // Lines of the stream's own passed by in one read (handed over already) before saying "nothing yet".
        private const val MAX_PASSES = 64
    }
}
