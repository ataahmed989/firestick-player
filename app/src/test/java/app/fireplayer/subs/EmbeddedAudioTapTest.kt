package app.fireplayer.subs

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Copying the film's sound as it loads ([EmbeddedCues], for [AheadAudio]): through an extractor's output as Media3 1.8
 * drives it, only while armed for this film and only the sound track playing, the bytes exactly as the player got
 * them, and the player's own copy untouched.
 */
class EmbeddedAudioTapTest {

    /** What the player's side (its sample queue) was given, call by call. */
    private class Recorder : TrackOutput {
        val bytes = ByteArrayOutputStream()
        val calls = ArrayList<String>()
        var format: Format? = null

        override fun format(format: Format) { this.format = format; calls += "format ${format.id}" }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            // As SampleQueue reads: into its own buffer, as much as the input gives in one go.
            val buf = ByteArray(length)
            val n = input.read(buf, 0, length)
            if (n > 0) bytes.write(buf, 0, n)
            calls += "data $n"
            return n
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            bytes.write(data.data, data.position, length)
            data.skipBytes(length)
            calls += "array $length"
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            calls += "sample $timeUs $flags $size $offset"
        }
    }

    private class Output : ExtractorOutput {
        val tracks = HashMap<Int, Recorder>()
        override fun track(id: Int, type: Int): TrackOutput = Recorder().also { tracks[id] = it }
        override fun endTracks() {}
        override fun seekMap(seekMap: SeekMap) {}
    }

    /** The file, read at most [chunk] bytes at a time (as a network read gives what's arrived). */
    private class FileReader(private val data: ByteArray, private val chunk: Int) : DataReader {
        var pos = 0
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (pos >= data.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, chunk, data.size - pos)
            System.arraycopy(data, pos, buffer, offset, n)
            pos += n
            return n
        }
    }

    private fun format(id: String, default: Boolean = false) = Format.Builder().setId(id).setSampleMimeType(MimeTypes.AUDIO_AAC)
        .setChannelCount(2).setSampleRate(48_000).setSelectionFlags(if (default) C.SELECTION_FLAG_DEFAULT else 0).build()

    private fun sample(k: Int) = ByteArray(300 + k % 7 * 50) { (k * 13 + it * 7).toByte() }

    /** [count] samples of 20 ms from [fromUs], the extractor's way: from the file in bits ([reader]) or as arrays. */
    private fun write(out: TrackOutput, count: Int, fromUs: Long = 0, reader: Boolean = true, flags: Int = C.BUFFER_FLAG_KEY_FRAME, from: Int = 0) {
        for (k in from until from + count) {
            val bytes = sample(k)
            if (reader) {
                val r = FileReader(bytes, 97)
                var left = bytes.size
                while (left > 0) left -= out.sampleData(r, left, false, TrackOutput.SAMPLE_DATA_PART_MAIN)
            } else {
                out.sampleData(ParsableByteArray(bytes), bytes.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
            }
            out.sampleMetadata(fromUs + (k - from) * 20_000L, flags, bytes.size, 0, null)
        }
    }

    private fun taken(cues: EmbeddedCues): List<AheadAudio.Piece> = generateSequence { cues.ahead.take(0) }.toList()

    private fun samplesOf(pieces: List<AheadAudio.Piece>) = pieces.flatMap { p -> (0 until p.count).map { p.bytes.copyOfRange(p.startOf(it), p.ends[it]) } }

    @Test
    fun theSamplesAreCopiedExactlyWhileArmed() {
        for (viaReader in listOf(true, false)) {
            val cues = EmbeddedCues()
            val inner = Output()
            val out = cues.output("film", inner)
            val track = out.track(2, C.TRACK_TYPE_AUDIO)
            track.format(format("2"))
            cues.playingAudioId = "0:2"
            cues.aheadUri = "film"
            write(track, 150, reader = viaReader)
            val pieces = taken(cues)
            assertEquals(1, pieces.size)
            assertEquals(100, pieces[0].count)
            assertEquals("film", pieces[0].uri)
            assertEquals(2, pieces[0].track)
            val got = samplesOf(pieces)
            for (k in got.indices) assertArrayEquals("sample $k", sample(k), got[k])
            for (k in 0 until 100) assertEquals(k * 20_000L, pieces[0].timesUs[k])
            // The player got every byte, in the same calls as without the tap.
            val plain = Output()
            val untapped = plain.track(2, C.TRACK_TYPE_AUDIO)
            untapped.format(format("2"))
            write(untapped, 150, reader = viaReader)
            assertArrayEquals(plain.tracks[2]!!.bytes.toByteArray(), inner.tracks[2]!!.bytes.toByteArray())
            assertEquals(plain.tracks[2]!!.calls, inner.tracks[2]!!.calls)
            // And how far the film has loaded is noted as before.
            assertEquals(149 * 20L, cues.source("film")!!.loading().second)
        }
    }

    @Test
    fun samplesGivenTogetherAreSplitByTheirOffsets() {
        // Matroska lacing: three samples' bytes in one go, then each sample with how many bytes after it are already given.
        val cues = EmbeddedCues()
        val track = cues.output("film", Output()).track(1, C.TRACK_TYPE_AUDIO)
        track.format(format("1"))
        cues.aheadUri = "film"
        var t = 0L
        for (block in 0 until 40) {
            val parts = (0 until 3).map { sample(block * 3 + it) }
            val all = parts.reduce { a, b -> a + b }
            val r = FileReader(all, 1_000)
            var left = all.size
            while (left > 0) left -= track.sampleData(r, left, false, TrackOutput.SAMPLE_DATA_PART_MAIN)
            var after = all.size
            for (p in parts) {
                after -= p.size
                track.sampleMetadata(t, C.BUFFER_FLAG_KEY_FRAME, p.size, after, null)
                t += 20_000
            }
        }
        val got = samplesOf(taken(cues))
        assertEquals(100, got.size)
        for (k in got.indices) assertArrayEquals("sample $k", sample(k), got[k])
    }

    @Test
    fun nothingIsCopiedUnlessArmedForThisFilm() {
        val cues = EmbeddedCues()
        val track = cues.output("film", Output()).track(2, C.TRACK_TYPE_AUDIO)
        track.format(format("2"))
        write(track, 150)
        assertNull(cues.ahead.take(0))
        cues.aheadUri = "another film"
        write(track, 150, fromUs = 3_000_000)
        assertNull(cues.ahead.take(0))
        // Armed part way through: from the next sample on.
        cues.aheadUri = "film"
        write(track, 150, fromUs = 6_000_000)
        val pieces = taken(cues)
        assertEquals(6_000_000L, pieces[0].firstUs)
        cues.aheadUri = null
        cues.ahead.release()
        write(track, 150, fromUs = 9_000_000)
        assertNull(cues.ahead.take(0))
    }

    @Test
    fun onlyTheSoundTrackPlaying() {
        fun run(playing: String?, defaultId: String?): Set<Int> {
            val cues = EmbeddedCues()
            val out = cues.output("film", Output())
            val one = out.track(1, C.TRACK_TYPE_AUDIO).apply { format(format("1", default = defaultId == "1")) }
            val two = out.track(2, C.TRACK_TYPE_AUDIO).apply { format(format("2", default = defaultId == "2")) }
            cues.playingAudioId = playing
            cues.aheadUri = "film"
            for (b in 0 until 150) {
                write(one, 1, fromUs = b * 20_000L, from = b)
                write(two, 1, fromUs = b * 20_000L, from = b)
            }
            return taken(cues).map { it.track }.toSet()
        }
        // The player's id ("<period>:" before the file's, with downloaded subtitles merged in) or the file's own.
        assertEquals(setOf(2), run("0:2", null))
        assertEquals(setOf(1), run("1", null))
        assertEquals(setOf(2), run("2", "1"))
        // Not known yet: the one marked default, else the first.
        assertEquals(setOf(2), run(null, "2"))
        assertEquals(setOf(1), run(null, null))
        // "0:12" isn't "2".
        assertEquals(setOf(1), run("0:12", null))
    }

    @Test
    fun protectedOrUnusualSamplesAreLeftOut() {
        val cues = EmbeddedCues()
        val inner = Output()
        val track = cues.output("film", inner).track(2, C.TRACK_TYPE_AUDIO)
        track.format(format("2"))
        cues.aheadUri = "film"
        write(track, 150, flags = C.BUFFER_FLAG_KEY_FRAME or C.BUFFER_FLAG_ENCRYPTED)
        write(track, 150, fromUs = 3_000_000, flags = C.BUFFER_FLAG_KEY_FRAME or C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA)
        for (k in 0 until 150) {
            val bytes = sample(k)
            track.sampleData(ParsableByteArray(bytes), bytes.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
            track.sampleMetadata(6_000_000L + k * 20_000L, C.BUFFER_FLAG_KEY_FRAME, bytes.size, 0, TrackOutput.CryptoData(C.CRYPTO_MODE_AES_CTR, ByteArray(16), 0, 0))
        }
        assertNull(cues.ahead.take(0))
        // A sample bigger than any sound needs (over 256 KB) is let go; the ones after it are copied again.
        val big = ByteArray(300 * 1024)
        track.sampleData(ParsableByteArray(big), big.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        track.sampleMetadata(9_000_000, C.BUFFER_FLAG_KEY_FRAME, big.size, 0, null)
        write(track, 150, fromUs = 9_020_000)
        val pieces = taken(cues)
        assertEquals(9_020_000L, pieces[0].firstUs)
        assertArrayEquals(sample(0), samplesOf(pieces)[0])
        // The player got all of it regardless.
        assertTrue(inner.tracks[2]!!.calls.count { it.startsWith("sample") } == 4 * 150 + 1)
    }
}
