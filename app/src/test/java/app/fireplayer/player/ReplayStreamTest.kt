package app.fireplayer.player

import androidx.media3.common.C
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.SampleStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayStreamTest {
    private val sec = 1_000_000L

    /** A subtitle stream of [lines] (second, text) in order, read on from index [at]; it ends after the last. */
    private class Lines(val lines: List<Pair<Int, String>>) : SampleStream {
        constructor(first: Int, last: Int) : this((first..last).map { it to "line $it" })
        var at = 0
        /** The player put the stream at the line at or before [second] (a skip). */
        fun seek(second: Int) { at = lines.indexOfLast { it.first <= second }.coerceAtLeast(0) }
        override fun isReady() = true
        override fun maybeThrowError() {}
        override fun skipData(positionUs: Long) = 0
        override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
            if (at >= lines.size) {
                buffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM)
                return C.RESULT_BUFFER_READ
            }
            buffer.timeUs = lines[at].first * 1_000_000L
            buffer.setFlags(C.BUFFER_FLAG_KEY_FRAME)
            val text = lines[at].second.toByteArray()
            buffer.ensureSpaceForWrite(text.size)
            buffer.data!!.put(text)
            at++
            return C.RESULT_BUFFER_READ
        }
    }

    /** Reads [stream] as the text renderer does until it ends: each line's text, in the order handed over. */
    private fun read(stream: SampleStream): List<String> {
        val out = ArrayList<String>()
        val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        val holder = FormatHolder()
        var idle = 0
        while (out.size < 5_000 && idle < 100) {
            buffer.clear()
            val r = stream.readData(holder, buffer, 0)
            if (r == C.RESULT_NOTHING_READ) { idle++; continue }
            if (r != C.RESULT_BUFFER_READ || buffer.isEndOfStream) break
            assertTrue(buffer.isKeyFrame)
            buffer.flip()
            out += String(buffer.data!!.array(), buffer.data!!.arrayOffset(), buffer.data!!.limit())
        }
        return out
    }

    private fun lines(range: Iterable<Int>) = range.map { "line $it" }

    @Test
    fun afterASkipTheLinesBeforeTheNewPlaceComeFirstAndNoneTwice() {
        val inner = Lines(0, 600)
        val stream = ReplayStream(inner)
        // Read to the end, as the renderer does soon after the film starts.
        assertEquals(lines(0..600), read(stream))
        assertEquals(601, stream.keptCount)
        // A skip to 300 s with the subtitles 15 s late: the player puts the stream at 300, the clock is at 285.
        inner.seek(300)
        stream.replayFrom(285 * sec)
        val got = read(stream)
        // From 30 s before the clock: the line showing now first, then back, then on from the clock; then the stream's own.
        assertEquals(lines(285 downTo 255) + lines(286..600), got)
        assertEquals("each line once", got.size, got.toSet().size)
        assertTrue(stream.complete)
    }

    @Test
    fun theDelayTurnedUpWithTheStreamReadToItsEndHandsTheRestOverAgain() {
        val inner = Lines(0, 120)
        val stream = ReplayStream(inner)
        read(stream)
        // Nothing more in the stream; the clock stepped back to 50 s: every line still to come is handed over again.
        stream.replayFrom(50 * sec)
        assertEquals(lines(50 downTo 20) + lines(51..120), read(stream))
        // Again before those were all taken: none lost, none doubled.
        stream.replayFrom(60 * sec)
        val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        repeat(5) { buffer.clear(); stream.readData(FormatHolder(), buffer, 0) }
        stream.replayFrom(61 * sec)
        assertEquals(lines(61 downTo 31) + lines(62..120), read(stream))
        assertEquals(121, stream.keptCount)
    }

    @Test
    fun nothingKeptOrAFilmNotReadBeforeIsReadAsItAlwaysWas() {
        val inner = Lines(100, 130)
        val stream = ReplayStream(inner)
        // The film opened at 100 s and nothing was read before: only the stream's own lines, in order.
        stream.replayFrom(90 * sec)
        assertEquals(lines(100..130), read(stream))
        // Back to 110: the stream gives 110 on itself; the kept ones before it come first and none is doubled.
        inner.seek(110)
        stream.replayFrom(105 * sec)
        assertEquals(lines(105 downTo 100) + lines(106..130), read(stream))
    }

    @Test
    fun linesSharingATimeAreAllHandedOverOnce() {
        // Two lines at 50 s and at 51 s (one above, one below, say); a skip lands the stream on the second of a pair.
        val all = (0..100).flatMap { s -> if (s == 50 || s == 51) listOf(s to "top $s", s to "bottom $s") else listOf(s to "line $s") }
        val inner = Lines(all)
        val stream = ReplayStream(inner)
        assertEquals(all.map { it.second }, read(stream))
        inner.at = all.indexOf(50 to "bottom 50")
        stream.replayFrom(45 * sec)
        val got = read(stream)
        assertEquals("each line once", got.size, got.toSet().size)
        assertEquals(all.filter { it.first >= 15 }.map { it.second }.toSet(), got.toSet())
        // A skip while a line of the stream's own was read ahead and not handed over yet: it still comes.
        inner.seek(70)
        stream.replayFrom(69 * sec)
        val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        stream.readData(FormatHolder(), buffer, 0)
        inner.seek(90)
        stream.replayFrom(89 * sec)
        val after = read(stream)
        assertEquals("each line once", after.size, after.toSet().size)
        assertTrue(after.containsAll(lines(59..100)))
    }

    @Test
    fun moreLinesThanAreKeptAreStillAllReadOnce() {
        // Lines far bigger than any subtitle's: past what's kept, the stream is read on as it is, and it says so.
        val big = "x".repeat(200 * 1024)
        val inner = Lines((0..60).map { it to "$it $big" })
        val stream = ReplayStream(inner)
        assertEquals(61, read(stream).size)
        assertFalse(stream.complete)
        inner.seek(30)
        stream.replayFrom(20 * sec)
        val got = read(stream)
        assertEquals("each line once", got.size, got.toSet().size)
        assertTrue(got.map { it.substringBefore(' ').toInt() }.containsAll((30..60).toList()))
    }
}
