package app.fireplayer.player

import androidx.media3.common.C
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.SampleStream
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayStreamTest {
    private val sec = 1_000_000L

    /** A subtitle stream of one line a second from [first] to [last] (seconds), read on from [at]; ends after the last. */
    private class Lines(val first: Int, val last: Int) : SampleStream {
        var at = first
        override fun isReady() = true
        override fun maybeThrowError() {}
        override fun skipData(positionUs: Long) = 0
        override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
            if (at > last) {
                buffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM)
                return C.RESULT_BUFFER_READ
            }
            buffer.timeUs = at * 1_000_000L
            buffer.setFlags(C.BUFFER_FLAG_KEY_FRAME)
            val text = "line $at".toByteArray()
            buffer.ensureSpaceForWrite(text.size)
            buffer.data!!.put(text)
            if (readFlags and SampleStream.FLAG_PEEK == 0) at++
            return C.RESULT_BUFFER_READ
        }
    }

    /** Reads [stream] as the text renderer does until it ends or [max] lines came: their times in seconds, in order. */
    private fun read(stream: SampleStream, max: Int = 1_000): List<Int> {
        val out = ArrayList<Int>()
        val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        val holder = FormatHolder()
        while (out.size < max) {
            buffer.clear()
            val r = stream.readData(holder, buffer, 0)
            if (r != C.RESULT_BUFFER_READ || buffer.isEndOfStream) break
            buffer.flip()
            val text = String(buffer.data!!.array(), buffer.data!!.arrayOffset(), buffer.data!!.limit())
            assertEquals("line ${buffer.timeUs / 1_000_000}", text)
            out += (buffer.timeUs / 1_000_000).toInt()
        }
        return out
    }

    @Test
    fun afterASkipTheLinesBeforeTheNewPlaceComeFirstAndNoneTwice() {
        val inner = Lines(0, 600)
        val stream = ReplayStream(inner)
        // Read to the end, as the renderer does soon after the film starts.
        assertEquals((0..600).toList(), read(stream))
        assertEquals(601, stream.keptCount)
        // A skip to 300 s with the subtitles 15 s late: the player puts the stream at 300, the clock is at 285.
        inner.at = 300
        stream.replayFrom(285 * sec)
        val got = read(stream)
        // From 30 s before the clock: the line showing now first, then back, then on from the clock; then the stream's own.
        assertEquals((285 downTo 255).toList() + (286..299).toList() + (300..600).toList(), got)
        assertEquals("each line once", got.size, got.toSet().size)
    }

    @Test
    fun theDelayTurnedUpWithTheStreamReadToItsEndHandsTheRestOverAgain() {
        val inner = Lines(0, 120)
        val stream = ReplayStream(inner)
        read(stream)
        // Nothing more in the stream; the clock stepped back to 50 s.
        stream.replayFrom(50 * sec)
        assertEquals((50 downTo 20).toList() + (51..120).toList(), read(stream))
        // Asked again with nothing new: the same, not doubled.
        stream.replayFrom(50 * sec)
        assertEquals((50 downTo 20).toList() + (51..120).toList(), read(stream))
        assertEquals(121, stream.keptCount)
    }

    @Test
    fun nothingKeptOrAFilmNotReadBeforeIsReadAsItAlwaysWas() {
        val inner = Lines(100, 130)
        val stream = ReplayStream(inner)
        // The film opened at 100 s and nothing was read before: only the stream's own lines.
        stream.replayFrom(90 * sec)
        assertEquals((100..130).toList(), read(stream))
        // Back to before anything read: the stream gives those itself, and the kept ones aren't doubled.
        inner.at = 110
        stream.replayFrom(105 * sec)
        assertEquals((105 downTo 100).toList() + (106..109).toList() + (110..130).toList(), read(stream))
    }
}
