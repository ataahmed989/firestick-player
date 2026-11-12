package app.fireplayer.subs

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The film's sound copied as it loads, for listening ahead ([AheadAudio]): pieced up a few seconds at a time, a skip
 * leaving what was waiting behind, and bounded: when the listener is behind, samples are let go and counted, and the
 * loading thread never waits.
 */
class AheadAudioTest {

    private val aac = Format.Builder().setId("2").setSampleMimeType(MimeTypes.AUDIO_AAC).setChannelCount(2).setSampleRate(48_000).build()
    private val ac3 = Format.Builder().setId("3").setSampleMimeType(MimeTypes.AUDIO_AC3).setChannelCount(6).setSampleRate(48_000).build()

    /** A sample's bytes, telling which it is. */
    private fun bytes(k: Int, size: Int = 400) = ByteArray(size) { (k * 31 + it).toByte() }

    private fun AheadAudio.add(k: Int, timeUs: Long, size: Int = 400, format: Format = aac, track: Int = 2, uri: String = "film") =
        sample(uri, track, format, bytes(k, size), 0, size, timeUs)

    private fun sampleOf(p: AheadAudio.Piece, i: Int) = p.bytes.copyOfRange(p.startOf(i), p.ends[i])

    @Test
    fun piecesHoldAFewSecondsEach() {
        val ring = AheadAudio(staleMs = 60_000)
        // 20 ms samples for 3 s: the first piece is handed over as the one 2 s after its first comes.
        for (k in 0 until 150) ring.add(k, k * 20_000L)
        val p = ring.take(0)
        assertNotNull(p)
        assertEquals(100, p!!.count)
        assertEquals(0L, p.firstUs)
        assertEquals(1_980_000L, p.lastUs)
        assertEquals("film", p.uri)
        assertEquals(2, p.track)
        assertSame(aac, p.format)
        for (i in listOf(0, 37, 99)) assertArrayEquals(bytes(i), sampleOf(p, i))
        // The rest is still being filled.
        assertNull(ring.take(0))
        ring.recycle(p)
    }

    @Test
    fun aPieceIsHandedOverFullOrOnAnotherFormat() {
        val ring = AheadAudio(staleMs = 60_000)
        // 10 KB samples: six fit in 64 KB.
        for (k in 0 until 7) ring.add(k, k * 20_000L, size = 10_240)
        assertEquals(6, ring.take(0)!!.count)
        // At most 512 samples, however small.
        val small = AheadAudio(staleMs = 60_000)
        for (k in 0 until 513) small.add(k, k * 1_000L, size = 8)
        assertEquals(AheadAudio.MAX_SAMPLES, small.take(0)!!.count)
        // Another format, track or film: another piece.
        val mixed = AheadAudio(staleMs = 60_000)
        mixed.add(0, 0); mixed.add(1, 20_000)
        mixed.add(2, 40_000, format = ac3)
        mixed.add(3, 60_000, format = ac3, track = 3)
        mixed.add(4, 80_000, format = ac3, track = 3, uri = "next")
        mixed.add(5, 100_000, format = ac3, track = 3, uri = "next")
        val got = generateSequence { mixed.take(0) }.toList()
        assertEquals(listOf(2, 1, 1), got.map { it.count })
        assertEquals(listOf<Format?>(aac, ac3, ac3), got.map { it.format })
        assertEquals(listOf(2, 2, 3), got.map { it.track })
    }

    @Test
    fun aGapStartsAnotherPieceAndASkipLeavesWhatWasWaiting() {
        val ring = AheadAudio(staleMs = 60_000)
        for (k in 0 until 50) ring.add(k, k * 20_000L)
        // 1.5 s later: a gap (samples missing), a new piece.
        ring.add(50, 2_480_000)
        assertEquals(50, ring.take(0)!!.count)
        // Pieces waiting, then loading starts a minute ahead (a skip): what was waiting is let go.
        for (k in 51 until 200) ring.add(k, 2_480_000L + (k - 50) * 20_000L)
        assertTrue(ring.pending() >= 1)
        ring.add(1_000, 62_000_000)
        assertEquals(0, ring.pending())
        for (k in 1 until 120) ring.add(1_000 + k, 62_000_000L + k * 20_000L)
        val p = ring.take(0)!!
        assertEquals(62_000_000L, p.firstUs)
        assertArrayEquals(bytes(1_000), sampleOf(p, 0))
        // And going back, too.
        ring.add(2_000, 10_000_000)
        for (k in 1 until 120) ring.add(2_000 + k, 10_000_000L + k * 20_000L)
        assertEquals(10_000_000L, ring.take(0)!!.firstUs)
    }

    @Test
    fun withTheListenerBehindSamplesAreLetGoNeverWaitedFor() {
        val ring = AheadAudio(staleMs = 60_000)
        // Nobody takes anything: 48 pieces of 2 s each fill, then everything after is let go, at once.
        val started = System.nanoTime()
        val total = 20_000
        for (k in 0 until total) ring.add(k, k * 20_000L)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMs ms", tookMs < 2_000)
        assertEquals(AheadAudio.PIECES, ring.pending())
        // 48 pieces of 2 s kept; every sample after them let go, and how much film that was.
        val kept = AheadAudio.PIECES * 100
        assertEquals((total - kept).toLong(), ring.droppedSamples)
        assertEquals((total - kept) * 20_000L, ring.droppedUs)
        val first = ring.take(0)!!
        assertFalse(first.afterDrop)
        ring.recycle(first)
        // Once a piece is free again, copying goes on; that piece says samples were let go just before it.
        for (k in 0 until 101) ring.add(total + k, (total + k) * 20_000L)
        val pieces = generateSequence { ring.take(0) }.toList()
        assertEquals(AheadAudio.PIECES, pieces.size)
        assertEquals(total * 20_000L, pieces.last().firstUs)
        assertTrue(pieces.last().afterDrop)
        assertFalse(pieces[0].afterDrop)
        // The 101st found no piece free again.
        assertEquals((total - kept + 1).toLong(), ring.droppedSamples)
    }

    @Test
    fun releaseLetsGoOfEverything() {
        val ring = AheadAudio(staleMs = 60_000)
        for (k in 0 until 400) ring.add(k, k * 20_000L)
        val held = ring.take(0)!!
        assertTrue(ring.pending() > 0)
        ring.release()
        assertEquals(0, ring.pending())
        assertNull(ring.take(0))
        // A piece handed out before is dropped when it comes back, not used again.
        ring.recycle(held)
        for (k in 0 until 101) ring.add(k, k * 20_000L)
        val p = ring.take(0)!!
        assertTrue(p !== held)
        assertEquals(100, p.count)
        ring.recycle(p)
        // After a while with loading paused, a piece still being filled is handed over all the same.
        val quick = AheadAudio(staleMs = 0)
        quick.add(0, 0)
        assertEquals(1, quick.take(0)!!.count)
    }
}
