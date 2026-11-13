package app.fireplayer.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** The copy of the sound playing that auto sync listens to ([SpeechTap]): what it holds, and that it's given back. */
class SpeechTapTest {

    private val kind = SpeechTap.kindOf(
        Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT).setChannelCount(2).setSampleRate(48_000).build(),
    )!!

    private fun sound(size: Int, fill: Int = 7) = ByteBuffer.wrap(ByteArray(size) { fill.toByte() })

    @Test
    fun nothingIsCopiedUnlessListening() {
        val tap = SpeechTap()
        tap.offer(sound(4_096), 0, kind)
        assertNull(tap.take(0))
        tap.listening = true
        val buffer = sound(4_096)
        tap.offer(buffer, 20_000, kind)
        assertEquals("left as it was", 0, buffer.position())
        val piece = tap.take(0)
        assertNotNull(piece)
        assertEquals(4_096, piece!!.size)
        assertEquals(20_000L, piece.filmUs)
        assertSame(kind, piece.kind)
        assertTrue((0 until piece.size).all { piece.bytes[it] == 7.toByte() })
    }

    @Test
    fun aPieceIsAsBigAsTheSoundItHolds() {
        val tap = SpeechTap()
        tap.listening = true
        // Compressed frames of a couple of KB: the smallest piece, not 64 KB each.
        tap.offer(sound(1_792), 0, kind)
        val small = tap.take(0)!!
        assertEquals(SpeechTap.MIN_PIECE_BYTES, small.bytes.size)
        tap.recycle(small)
        // Read and handed back, it's the one used again.
        tap.offer(sound(2_048), 32_000, kind)
        val again = tap.take(0)!!
        assertSame(small, again)
        assertEquals(2_048, again.size)
        tap.recycle(again)
        // Sound too big for it (decoded 5.1): a bigger one takes its place.
        tap.offer(sound(36_864, fill = 3), 64_000, kind)
        val big = tap.take(0)!!
        assertEquals(36_864, big.bytes.size)
        assertEquals(36_864, big.size)
        assertTrue((0 until big.size).all { big.bytes[it] == 3.toByte() })
        // More than a piece ever holds isn't taken at all.
        tap.offer(sound(65_537), 96_000, kind)
        assertNull(tap.take(0))
    }

    @Test
    fun aListenerBehindLosesPiecesAndNoMoreAreMadeThanCanWait() {
        val tap = SpeechTap()
        tap.listening = true
        for (i in 0 until SpeechTap.PIECES + 10) tap.offer(sound(1_024), i * 20_000L, kind)
        assertEquals(10L, tap.dropped)
        var taken = 0
        var last = -1L
        while (true) {
            val p = tap.take(0) ?: break
            assertTrue("in order", p.filmUs > last)
            last = p.filmUs
            taken++
            tap.recycle(p)
        }
        assertEquals(SpeechTap.PIECES, taken)
        // The first to wait were kept, the latest let go.
        assertEquals((SpeechTap.PIECES - 1) * 20_000L, last)
    }

    @Test
    fun stoppingListeningGivesThePiecesBack() {
        val tap = SpeechTap()
        tap.listening = true
        for (i in 0 until 20) tap.offer(sound(1_024), i * 20_000L, kind)
        val inHand = tap.take(0)!!
        tap.listening = false
        // Nothing waits any more, and nothing is copied.
        assertNull(tap.take(0))
        tap.offer(sound(1_024), 0, kind)
        assertNull(tap.take(0))
        // The piece being read comes back as usual, and is the one used when listening starts again.
        tap.recycle(inHand)
        tap.listening = true
        tap.offer(sound(1_024), 500_000, kind)
        val next = tap.take(0)!!
        assertSame(inHand, next)
        assertEquals(500_000L, next.filmUs)
    }
}
