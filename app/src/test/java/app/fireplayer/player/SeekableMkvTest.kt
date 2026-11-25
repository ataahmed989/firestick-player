package app.fireplayer.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * MKV files whose track list (Tracks) comes after the clusters, run through Media3's own MKV reader (1.8.0) as the
 * player wraps it, read the way Media3's loader reads a film: the list must be read before the clusters, the film's
 * bytes once, and every block come out as a sample. Files laid out as usual must be read exactly as before, and files
 * whose SeekHead is wrong or missing as before too.
 */
class SeekableMkvTest {

    // --- A film, written the way muxers write it

    private class Film(
        val bytes: ByteArray,
        val segmentStart: Long,
        /** Where the track list really is. */
        val tracksAt: Long,
        val cuesAt: Long,
        /** Each cluster's start, and where its contents start (after its ID and size). */
        val clusters: List<Long>,
        val clusterContents: List<Long>,
        val videoTimesUs: List<Long>,
        val audioTimesUs: List<Long>,
    ) {
        val length get() = bytes.size.toLong()
    }

    private fun idBytes(id: Long): ByteArray {
        val n = when {
            id > 0xFFFFFF -> 4
            id > 0xFFFF -> 3
            id > 0xFF -> 2
            else -> 1
        }
        return ByteArray(n) { ((id shr (8 * (n - 1 - it))) and 0xFF).toByte() }
    }

    /** An element size, in the fewest bytes, or in [width] bytes. */
    private fun sizeBytes(n: Long, width: Int = 0): ByteArray {
        val w = if (width > 0) width else (1..8).first { n < (1L shl (7 * it)) - 1 }
        val b = ByteArray(w) { ((n shr (8 * (w - 1 - it))) and 0xFF).toByte() }
        b[0] = (b[0].toInt() or (0x80 shr (w - 1))).toByte()
        return b
    }

    private fun cat(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, at, p.size)
            at += p.size
        }
        return out
    }

    private fun el(id: Long, vararg body: ByteArray): ByteArray {
        val payload = cat(body.toList())
        return idBytes(id) + sizeBytes(payload.size.toLong()) + payload
    }

    /** An unsigned integer in [n] bytes (the fewest when 0). */
    private fun uint(v: Long, n: Int = 0): ByteArray {
        val w = if (n > 0) n else (1..8).first { it == 8 || v < (1L shl (8 * it)) }
        return ByteArray(w) { ((v shr (8 * (w - 1 - it))) and 0xFF).toByte() }
    }

    private fun float64(v: Double): ByteArray = ByteBuffer.allocate(8).putDouble(v).array()

    /** A Dolby Vision configuration record: version 1.0, profile 8, level 6, with base [signal]. */
    private fun record(signal: Int) = ByteArray(24).also {
        it[0] = 1
        it[2] = (8 shl 1).toByte()
        it[3] = 0x35
        it[4] = (signal shl 4).toByte()
    }

    private val ebmlHeader = el(0x1A45DFA3, el(0x4286, uint(1)), el(0x42F7, uint(1)), el(0x42F2, uint(4)), el(0x42F3, uint(8)),
        el(0x4282, "matroska".toByteArray()), el(0x4287, uint(4)), el(0x4285, uint(2)))

    private fun trackList(dv: Boolean, audioCodec: Boolean = true): ByteArray {
        val mapping = if (dv) el(0x41E4, el(0x41E7, uint(0x64766343)), el(0x41ED, record(DvBaseLayer.SIGNAL_HLG))) else ByteArray(0)
        val video = el(0xAE, el(0xD7, uint(1)), el(0x73C5, uint(11)), el(0x83, uint(1)), el(0x86, "V_VP9".toByteArray()),
            el(0x23E383, uint(41_708_333)), el(0xE0, el(0xB0, uint(1920)), el(0xBA, uint(1080))), mapping)
        return el(0x1654AE6B, video, soundEntry(audioCodec))
    }

    private fun soundEntry(codec: Boolean = true): ByteArray = el(0xAE, el(0xD7, uint(2)), el(0x73C5, uint(22)), el(0x83, uint(2)),
        if (codec) el(0x86, "A_AC3".toByteArray()) else ByteArray(0), el(0xE1, el(0xB5, float64(48_000.0)), el(0x9F, uint(6))))

    /**
     * One second a cluster: 24 pictures of 3000 bytes (the first a key frame) and 32 sound blocks of 500, in time order.
     * [broken]: its Timestamp element nine bytes long, which Media3 refuses as malformed.
     */
    private fun cluster(i: Int, random: Random, broken: Boolean = false, video: MutableList<Long>, audio: MutableList<Long>): ByteArray {
        val blocks = ArrayList<Pair<Int, ByteArray>>()
        for (k in 0 until 24) {
            val rel = k * 1000 / 24
            blocks += rel * 2 to block(1, rel, if (k == 0) 0x80 else 0, random.nextBytes(3000))
            if (!broken) video += (i * 1000L + rel) * 1000
        }
        for (k in 0 until 32) {
            val rel = k * 32
            blocks += rel * 2 + 1 to block(2, rel, 0x80, random.nextBytes(500))
            if (!broken) audio += (i * 1000L + rel) * 1000
        }
        blocks.sortBy { it.first }
        val time = if (broken) el(0xE7, ByteArray(9)) else el(0xE7, uint(i * 1000L))
        return el(0x1F43B675, time, *blocks.map { it.second }.toTypedArray())
    }

    private fun block(track: Int, rel: Int, flags: Int, data: ByteArray): ByteArray =
        el(0xA3, byteArrayOf((0x80 or track).toByte(), (rel shr 8).toByte(), rel.toByte(), flags.toByte()), data)

    private fun seekEntry(id: Long, position: Long) = el(0x4DBB, el(0x53AB, idBytes(id)), el(0x53AC, uint(position, 8)))

    /**
     * A film of [count] one-second clusters. [tracksLast]: its track list after the index, as FFmpeg and mkvpropedit can
     * write it; else after Info, as usual. [pad]: a Void this big before the first cluster (Tags and the like in real
     * files), so the clusters start beyond the first 64 KiB. [seekHead]: whether it has one, which lists Info, the track
     * list ([tracksListedAt] when given: a wrong place) and the index ([cues]: whether it has one).
     */
    private fun film(
        tracksLast: Boolean = true,
        pad: Int = 0,
        seekHead: Boolean = true,
        cues: Boolean = true,
        dv: Boolean = false,
        audioCodec: Boolean = true,
        count: Int = 12,
        brokenCluster: Int = -1,
        tracksListedAt: ((Film) -> Long)? = null,
    ): Film {
        val random = Random(42)
        val video = ArrayList<Long>()
        val audio = ArrayList<Long>()
        val clusterBytes = (0 until count).map { cluster(it, random, it == brokenCluster, video, audio) }
        val info = el(0x1549A966, el(0x2AD7B1, uint(1_000_000)), el(0x4489, float64(count * 1000.0)))
        val tracks = trackList(dv, audioCodec)
        val void = if (pad > 0) el(0xEC, ByteArray(pad)) else ByteArray(0)
        fun cuesOf(positions: List<Long>) = el(0x1C53BB6B, *positions.mapIndexed { i, p ->
            el(0xBB, el(0xB3, uint(i * 1000L, 8)), el(0xB7, el(0xF7, uint(1)), el(0xF1, uint(p, 8))))
        }.toTypedArray())
        fun seekHeadOf(info: Long, tracks: Long, cuesAt: Long) = if (!seekHead) ByteArray(0) else el(0x114D9B74,
            seekEntry(0x1549A966, info), seekEntry(0x1654AE6B, tracks), *(if (cues) arrayOf(seekEntry(0x1C53BB6B, cuesAt)) else emptyArray()))

        // The sizes don't hang on the places (written in fixed widths), so the places are worked out first.
        val headSize = seekHeadOf(0, 0, 0).size
        var rel = headSize.toLong()
        val infoRel = rel
        rel += info.size
        var tracksRel = -1L
        if (!tracksLast) {
            tracksRel = rel
            rel += tracks.size
        }
        rel += void.size
        val clusterRels = clusterBytes.map { c -> rel.also { rel += c.size } }
        val cuesRel = rel
        val cuesBytes = if (cues) cuesOf(clusterRels) else ByteArray(0)
        rel += cuesBytes.size
        if (tracksLast) {
            tracksRel = rel
            rel += tracks.size
        }
        val segmentStart = ebmlHeader.size + 4L + 8
        fun build(listedTracksRel: Long): ByteArray {
            val children = ArrayList<ByteArray>()
            children += seekHeadOf(infoRel, listedTracksRel, cuesRel)
            children += info
            if (!tracksLast) children += tracks
            children += void
            children += clusterBytes
            children += cuesBytes
            if (tracksLast) children += tracks
            val body = cat(children)
            return cat(listOf(ebmlHeader, idBytes(0x18538067), sizeBytes(body.size.toLong(), 8), body))
        }
        val clusters = clusterRels.map { segmentStart + it }
        // A cluster's contents start after its 4-byte ID and its size.
        val contents = clusters.mapIndexed { i, p -> p + 4 + MkvHeader.vintLength(clusterBytes[i][4]) }
        var made = Film(build(tracksRel), segmentStart, segmentStart + tracksRel, segmentStart + cuesRel, clusters, contents,
            video, audio)
        if (tracksListedAt != null) {
            made = Film(build(tracksListedAt(made) - segmentStart), made.segmentStart, made.tracksAt, made.cuesAt, made.clusters,
                made.clusterContents, video, audio)
        }
        return made
    }

    // --- Reading it as Media3's loader does

    /** Hands out the film a few kB at a time from where each connection opens, counting what was handed out. */
    private class Loader(private val file: ByteArray, private val clusterRegion: LongRange? = null) {
        val opens = ArrayList<Long>()
        var served = 0L
        var servedInClusters = 0L
        /** A connection drops once, at the first read from here on (-1: none does). */
        var dropAt = -1L
        /** A connection can't be opened here, once (-1: every one opens). */
        var failOpenAt = -1L
        private var cursor = 0L

        private fun open(position: Long): DefaultExtractorInput {
            if (position == failOpenAt) {
                failOpenAt = -1
                throw IOException("refused")
            }
            opens += position
            cursor = position
            val reader = DataReader { buffer, offset, length ->
                if (cursor >= file.size) return@DataReader C.RESULT_END_OF_INPUT
                if (dropAt in 0..cursor) {
                    dropAt = -1
                    throw IOException("dropped")
                }
                val n = minOf(length.toLong(), file.size - cursor, 4096L).toInt()
                System.arraycopy(file, cursor.toInt(), buffer, offset, n)
                clusterRegion?.let { r ->
                    val from = maxOf(cursor, r.first)
                    val to = minOf(cursor + n, r.last + 1)
                    if (to > from) servedInClusters += to - from
                }
                cursor += n
                served += n
                n
            }
            return DefaultExtractorInput(reader, position, file.size.toLong())
        }

        /**
         * Reads from [position] (after telling [extractor] so, as every load starts), following the places it asks to go
         * on from, until it ends or [stop]: a dropped connection is opened again where reading had got to. And as Media3's
         * own loader does (1.8.0, ProgressiveMediaPeriod.ExtractingLoadable.load), one that couldn't be opened is tried
         * again where the last connection had got to, not where it was asked to go.
         */
        fun load(extractor: Extractor, position: Long = 0, timeUs: Long = 0, stop: () -> Boolean = { false }): Int {
            extractor.seek(position, timeUs)
            var at = position
            var retries = 0
            val holder = PositionHolder()
            var last: DefaultExtractorInput? = null
            while (true) {
                val input = try {
                    open(at)
                } catch (e: IOException) {
                    if (++retries > 3) throw e
                    last?.let { at = it.position }
                    continue
                }
                last = input
                var result = Extractor.RESULT_CONTINUE
                try {
                    while (result == Extractor.RESULT_CONTINUE) {
                        if (stop()) return result
                        result = extractor.read(input, holder)
                    }
                } catch (e: IOException) {
                    if (e is ParserException || ++retries > 3) throw e
                    at = input.position
                    continue
                }
                if (result != Extractor.RESULT_SEEK) return result
                at = holder.position
            }
        }
    }

    /** What the reader gave: its tracks' formats and samples, and when it said the track list was over. */
    private class Out(private val loader: Loader) : ExtractorOutput {
        val events = ArrayList<String>()
        val tracks = LinkedHashMap<Int, Track>()
        var seekMap: SeekMap? = null
        var clusterBytesAtEndTracks = -1L
        private var sampled = false

        override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) { Track(id) }.also { events += "track $id" }

        override fun endTracks() {
            if (clusterBytesAtEndTracks < 0) clusterBytesAtEndTracks = loader.servedInClusters
            events += "endTracks"
        }

        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
            events += "seekMap"
        }

        fun clearSamples() = tracks.values.forEach { it.times.clear() }

        inner class Track(val id: Int) : TrackOutput {
            val formats = ArrayList<Format>()
            val times = ArrayList<Long>()
            private val scratch = ByteArray(8192)

            override fun format(format: Format) {
                formats += format
            }

            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                val n = input.read(scratch, 0, minOf(length, scratch.size))
                if (n == C.RESULT_END_OF_INPUT && !allowEndOfInput) throw EOFException()
                return n
            }

            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = data.skipBytes(length)

            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                if (!sampled) {
                    sampled = true
                    events += "first sample"
                }
                times += timeUs
            }
        }
    }

    private fun media3() = MatroskaExtractor(SubtitleParser.Factory.UNSUPPORTED)

    private fun wrapped(onSkip: ((String?, Long, Long) -> Unit)? = null): Extractor =
        SeekableMkvExtractorsFactory(ExtractorsFactory { arrayOf(media3()) }, onSkip).createExtractors().single()

    private class Read(val loader: Loader, val out: Out, val extractor: Extractor, val result: Int)

    private fun read(film: Film, extractor: Extractor, dropAt: Long = -1, failOpenAt: Long = -1): Read {
        val loader = Loader(film.bytes, film.clusters.first() until film.cuesAt)
        loader.dropAt = dropAt
        loader.failOpenAt = failOpenAt
        val out = Out(loader)
        extractor.init(out)
        return Read(loader, out, extractor, loader.load(extractor))
    }

    private fun Read.video() = out.tracks[1]!!
    private fun Read.audio() = out.tracks[2]!!

    // --- The track list after the clusters

    @Test
    fun aTrackListAfterTheClustersIsReadFirstAndEveryBlockComesOut() {
        val film = film(pad = 70_000)
        val r = read(film, wrapped())
        assertEquals(Extractor.RESULT_END_OF_INPUT, r.result)
        // The track list was over before a byte of the clusters had come, and before the first sample.
        assertEquals(0L, r.out.clusterBytesAtEndTracks)
        assertTrue(r.out.events.indexOf("endTracks") < r.out.events.indexOf("first sample"))
        assertTrue(r.out.events.indexOf("endTracks") < r.out.events.indexOf("seekMap"))
        // There and back once; then Media3's own trip to its index and back, as ever.
        assertEquals(listOf(0L, film.tracksAt, film.segmentStart, film.cuesAt, film.clusterContents[0]), r.loader.opens)
        // Every block came out, at its time.
        assertEquals(film.videoTimesUs, r.video().times)
        assertEquals(film.audioTimesUs, r.audio().times)
        val format = r.video().formats.first()
        assertEquals(MimeTypes.VIDEO_VP9, format.sampleMimeType)
        // The frame rate is the file's, from the track list read first, as from the first bytes of any other file.
        assertEquals(23.976f, format.frameRate, 0.001f)
        assertEquals(MimeTypes.AUDIO_AC3, r.audio().formats.first().sampleMimeType)
        // The film was read once (1 MB here): what's more is the first 64 KiB looked at on the first request (as ever),
        // and what a request had taken in ahead before the reader moved on (a 16 KiB piece at most, at the index).
        assertTrue(r.loader.served >= film.length)
        assertTrue("served ${r.loader.served} of ${film.length}", r.loader.served < film.length + 160_000)
    }

    @Test
    fun withoutItMedia3ReadsTheWholeFileBeforeItKnowsAnyTrack() {
        // What happened to the 1 GB episode: every block passed over, the tracks only known at the end of the file.
        val film = film(pad = 70_000)
        val r = read(film, media3())
        assertEquals(film.cuesAt - film.clusters.first(), r.out.clusterBytesAtEndTracks)
        assertTrue(r.video().times.isEmpty())
        assertTrue(r.audio().times.isEmpty())
    }

    @Test
    fun aClusterInTheFirstBytesBeforeTheTrackListAlsoSendsItFirst() {
        val film = film(pad = 0)
        assertTrue(film.clusters.first() < 65_536)
        val r = read(film, wrapped())
        assertEquals(listOf(0L, film.tracksAt, film.segmentStart, film.cuesAt, film.clusterContents[0]), r.loader.opens)
        assertEquals(film.videoTimesUs, r.video().times)
        assertEquals(film.audioTimesUs, r.audio().times)
        assertTrue(r.out.events.indexOf("endTracks") < r.out.events.indexOf("first sample"))
    }

    @Test
    fun aDolbyVisionRecordInALateTrackListIsKeptOnTheFormat() {
        val r = read(film(pad = 70_000, dv = true), wrapped())
        val format = r.video().formats.first()
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, format.sampleMimeType)
        assertEquals(DvBaseLayer.SIGNAL_HLG, DvBaseLayer.recordSignal(format))
        assertEquals(23.976f, format.frameRate, 0.001f)
    }

    @Test
    fun theIndexStillTakesJumpsAfterTheTrackListWasReadFirst() {
        val film = film(pad = 70_000)
        val r = read(film, wrapped())
        val map = r.out.seekMap!!
        assertTrue(map.isSeekable)
        val point = map.getSeekPoints(7_500_000L).first
        assertEquals(film.clusters[7], point.position)
        assertEquals(7_000_000L, point.timeUs)
        r.out.clearSamples()
        r.loader.load(r.extractor, point.position, point.timeUs, stop = { r.video().times.size >= 3 })
        assertEquals(listOf(7_000_000L, 7_041_000L, 7_083_000L), r.video().times)
    }

    @Test
    fun aFileWithoutAnIndexIsStillSearchedForAJump() {
        // No Cues: Media3 can't jump in it, and the cluster times are searched for instead (as before).
        val film = film(pad = 70_000, cues = false)
        val r = read(film, wrapped())
        assertEquals(film.videoTimesUs, r.video().times)
        val map = r.out.seekMap!!
        assertTrue(map.isSeekable)
        val point = map.getSeekPoints(7_500_000L).first
        r.out.clearSamples()
        r.loader.load(r.extractor, point.position, point.timeUs, stop = { r.video().times.isNotEmpty() })
        val landed = r.video().times.first()
        assertTrue("landed at $landed", landed in 5_000_000L..7_500_000L)
    }

    @Test
    fun aDamagedClusterIsStillGonePastAfterTheTrackListWasReadFirst() {
        val skips = ArrayList<Long>()
        val film = film(pad = 70_000, brokenCluster = 5)
        val r = read(film, wrapped { _, _, toUs -> skips += toUs })
        assertEquals(listOf(6_000_000L), skips)
        // Everything but the damaged second was played.
        assertEquals(film.videoTimesUs, r.video().times)
        assertTrue(r.video().times.none { it in 5_000_000L until 6_000_000L })
        assertEquals(6_000_000L, r.video().times.first { it >= 5_000_000L })
    }

    @Test
    fun aConnectionDroppedWhileLookingAtTheTrackListIsOpenedAgainThere() {
        val film = film(pad = 70_000)
        val r = read(film, wrapped(), dropAt = film.tracksAt)
        assertEquals(listOf(0L, film.tracksAt, film.tracksAt, film.segmentStart, film.cuesAt, film.clusterContents[0]), r.loader.opens)
        assertEquals(film.videoTimesUs, r.video().times)
        assertEquals(0L, r.out.clusterBytesAtEndTracks)
    }

    @Test
    fun aReconnectThatFailsOnTheWayBackFromTheTrackListIsAskedForAgain() {
        // Media3 tries it again where the last connection had got to: the end of the track list, the end of the file
        // here. Read on from there, no cluster would come, nor the index: the film would never start.
        val film = film(pad = 70_000)
        val r = read(film, wrapped(), failOpenAt = film.segmentStart)
        assertEquals(Extractor.RESULT_END_OF_INPUT, r.result)
        assertEquals(listOf(0L, film.tracksAt, film.length, film.segmentStart, film.cuesAt, film.clusterContents[0]), r.loader.opens)
        assertTrue(r.out.seekMap!!.isSeekable)
        assertEquals(film.videoTimesUs, r.video().times)
        assertEquals(film.audioTimesUs, r.audio().times)
        assertEquals(0L, r.out.clusterBytesAtEndTracks)
        // On the way there it already was: asked for again from where the first bytes ended.
        val there = read(film, wrapped(), failOpenAt = film.tracksAt)
        assertEquals(listOf(0L, film.segmentStart, film.tracksAt, film.segmentStart, film.cuesAt, film.clusterContents[0]),
            there.loader.opens)
        assertEquals(film.videoTimesUs, there.video().times)
    }

    @Test
    fun aReconnectThatFailsOnTheWayBackFromSomethingElseIsAskedForAgain() {
        // The SeekHead pointed at the last cluster: tried again there, the reader would take it for what follows the
        // first bytes, and Media3 would find no index. Asked for again, it's read as Media3 reads it.
        val film = film(pad = 70_000, tracksListedAt = { it.clusters.last() })
        val theirs = read(film, media3())
        val mine = read(film, wrapped(), failOpenAt = film.segmentStart)
        assertEquals(listOf(0L, film.clusters.last(), film.clusters.last(), film.segmentStart) + theirs.loader.opens.drop(1),
            mine.loader.opens)
        assertEquals(theirs.out.events, mine.out.events)
        assertTrue(mine.out.seekMap!!.isSeekable)
        assertEquals(theirs.video().times, mine.video().times)
        assertEquals(theirs.audio().times, mine.audio().times)
    }

    @Test
    fun aLoadStartedAgainFromTheStartReadsTheTrackListFirstAgain() {
        val film = film(pad = 70_000)
        val loader = Loader(film.bytes, film.clusters.first() until film.cuesAt)
        val out = Out(loader)
        val extractor = wrapped()
        extractor.init(out)
        // Stopped as it was about to read the track list; then loaded afresh from the start.
        loader.load(extractor, stop = { loader.opens.size == 2 })
        assertEquals(Extractor.RESULT_END_OF_INPUT, loader.load(extractor))
        assertEquals(listOf(0L, film.tracksAt, 0L, film.tracksAt, film.segmentStart, film.cuesAt, film.clusterContents[0]), loader.opens)
        assertEquals(film.videoTimesUs, out.tracks[1]!!.times)
        assertEquals(0L, out.clusterBytesAtEndTracks)
    }

    // --- Files laid out as usual, and SeekHeads that are wrong: read exactly as before

    private fun assertReadAsMedia3Does(film: Film, wrappedOpens: List<Long>? = null) {
        val mine = read(film, wrapped())
        val theirs = read(film, media3())
        assertEquals(wrappedOpens ?: theirs.loader.opens, mine.loader.opens)
        assertEquals(theirs.result, mine.result)
        assertEquals(theirs.out.tracks.keys, mine.out.tracks.keys)
        for ((id, track) in theirs.out.tracks) {
            assertEquals(track.times, mine.out.tracks[id]!!.times)
            // The same format, but for the frame rate the wrapper adds.
            assertEquals(track.formats.map { it.buildUpon().setFrameRate(Format.NO_VALUE.toFloat()).build() },
                mine.out.tracks[id]!!.formats.map { it.buildUpon().setFrameRate(Format.NO_VALUE.toFloat()).build() })
        }
        // The same things told in the same order: the tracks, the end of the list, the index, the first sample. (Bytes
        // fetched differ, as they always did: the wrapper looks at the first 64 KiB, and reads near the end in pieces.)
        assertEquals(theirs.out.events, mine.out.events)
    }

    @Test
    fun aFileWithItsTrackListAtTheStartIsReadExactlyAsBefore() {
        val film = film(tracksLast = false, pad = 70_000)
        // Nothing to read first, from what its first bytes say.
        val head = film.bytes.copyOf(65_536)
        assertEquals(-1L, MkvHeader.layout(head, head.size)!!.lateTracks(head.size, film.length))
        val mine = read(film, wrapped())
        val theirs = read(film, media3())
        // The same requests from the same places, the same samples.
        assertEquals(theirs.loader.opens, mine.loader.opens)
        assertEquals(listOf(0L, film.cuesAt, film.clusterContents[0]), mine.loader.opens)
        assertEquals(film.videoTimesUs, mine.video().times)
        assertEquals(theirs.video().times, mine.video().times)
        assertEquals(theirs.audio().times, mine.audio().times)
        assertEquals(23.976f, mine.video().formats.first().frameRate, 0.001f)
        assertReadAsMedia3Does(film)
        // And the same with its first cluster inside the first bytes.
        assertReadAsMedia3Does(film(tracksLast = false, pad = 0))
    }

    @Test
    fun aFileWithoutASeekHeadIsReadAsBefore() {
        assertReadAsMedia3Does(film(pad = 70_000, seekHead = false))
    }

    @Test
    fun aSeekHeadPointingPastTheEndIsLeftAlone() {
        assertReadAsMedia3Does(film(pad = 70_000, tracksListedAt = { it.length + 1000 }))
    }

    @Test
    fun aSeekHeadPointingAtSomethingElseGoesBackAndReadsAsBefore() {
        // The track list said to be where the last cluster is: looked at, not found, and back.
        val film = film(pad = 70_000, tracksListedAt = { it.clusters.last() })
        val theirs = read(film, media3())
        assertReadAsMedia3Does(film, listOf(0L, film.clusters.last(), film.segmentStart) + theirs.loader.opens.drop(1))
    }

    @Test
    fun aTrackListCutOffByTheEndOfTheFileIsLeftToMedia3() {
        val whole = film(pad = 70_000)
        // Cut after the picture's track: the list says it runs on past the end of the file (where Media3 meets the end
        // between two tracks).
        val cut = whole.bytes.size - soundEntry().size
        val film = Film(whole.bytes.copyOf(cut), whole.segmentStart, whole.tracksAt, whole.cuesAt, whole.clusters,
            whole.clusterContents, whole.videoTimesUs, whole.audioTimesUs)
        val theirs = read(film, media3())
        assertReadAsMedia3Does(film, listOf(0L, film.tracksAt, film.segmentStart) + theirs.loader.opens.drop(1))
    }

    @Test
    fun aTrackListThatDoesNotReadAsOneFailsAsItAlwaysDid() {
        // A track without a codec: Media3 refuses the file. Looked at first, it isn't read first, and Media3 refuses the
        // file where it always did, at the end.
        val film = film(pad = 70_000, audioCodec = false)
        for (extractor in listOf(wrapped(), media3())) {
            try {
                read(film, extractor)
                fail("read")
            } catch (e: ParserException) {
                assertTrue(e.contentIsMalformed)
            }
        }
    }

    // --- What the first bytes say

    @Test
    fun theFirstBytesSayWhereTheTrackListIsAndWhetherToReadItFirst() {
        val late = film(pad = 70_000)
        val head = late.bytes.copyOf(65_536)
        val layout = MkvHeader.layout(head, head.size)!!
        assertEquals(late.segmentStart, layout.segmentStart)
        assertFalse(layout.tracksInHead)
        assertFalse(layout.clusterFirst)
        assertEquals(late.tracksAt, layout.tracks)
        assertEquals(late.cuesAt, layout.cues)
        assertEquals(-1L, layout.tags)
        assertEquals(late.tracksAt, layout.lateTracks(head.size, late.length))
        assertEquals("tracks=absent fps=none dvRecord=none seekhead=Info@${layout.info},Tracks@${late.tracksAt}," +
            "Cues@${late.cuesAt},Tags@none len=${late.length}", MkvHeader.describe(layout, MkvHeader.video(head, head.size), late.length))
        // In the first half of the file with no cluster seen before it: it may well come before the clusters.
        assertEquals(-1L, layout.lateTracks(head.size, late.tracksAt * 2 + 2))
        // Its length unknown, or the list said to be within the bytes already read: left alone.
        assertEquals(-1L, layout.lateTracks(head.size, -1))
        assertEquals(-1L, MkvHeader.Layout(layout.segmentStart, false, true, -1, 4000, -1, -1).lateTracks(head.size, late.length))
        // A cluster seen first: read first wherever it is.
        val early = film(pad = 0)
        val earlyHead = early.bytes.copyOf(65_536)
        val earlyLayout = MkvHeader.layout(earlyHead, earlyHead.size)!!
        assertTrue(earlyLayout.clusterFirst)
        assertEquals(early.tracksAt, earlyLayout.lateTracks(earlyHead.size, early.length))
        // As usual: in the first bytes.
        val usual = film(tracksLast = false)
        val usualHead = usual.bytes.copyOf(65_536)
        val usualLayout = MkvHeader.layout(usualHead, usualHead.size)!!
        assertTrue(usualLayout.tracksInHead)
        assertFalse(usualLayout.clusterFirst)
        assertTrue(MkvHeader.describe(usualLayout, MkvHeader.video(usualHead, usualHead.size), usual.length)
            .startsWith("tracks=in-header fps=23.976 dvRecord=none"))
        // No Segment at all.
        assertNull(MkvHeader.layout(ebmlHeader, ebmlHeader.size))
    }

    @Test
    fun aTrackListIsOnlyTakenWhenItReadsAsOneWhole() {
        val list = trackList(dv = true)
        val found = MkvHeader.lateTracks(list, list.size)!!
        assertEquals(23.976f, found.video!!.frameRate, 0.001f)
        assertEquals(DvBaseLayer.SIGNAL_HLG, found.video!!.dvSignal)
        assertEquals(list.size.toLong(), MkvHeader.elementSize(list, 12, MkvHeader.ID_TRACKS))
        // Cut short, run on, a track with no codec, something else entirely: none.
        assertNull(MkvHeader.lateTracks(list, list.size - 1))
        assertNull(MkvHeader.lateTracks(list + byteArrayOf(0xEC.toByte(), 0x80.toByte()), list.size + 2))
        val noCodec = trackList(dv = false, audioCodec = false)
        assertNull(MkvHeader.lateTracks(noCodec, noCodec.size))
        val cluster = el(0x1F43B675, el(0xE7, uint(0)))
        assertNull(MkvHeader.lateTracks(cluster, cluster.size))
        assertEquals(-1L, MkvHeader.elementSize(cluster, cluster.size, MkvHeader.ID_TRACKS))
        // A list with only a sound track reads, with no video track in it.
        val sound = el(0x1654AE6B, el(0xAE, el(0xD7, uint(2)), el(0x83, uint(2)), el(0x86, "A_AC3".toByteArray())))
        assertNull(MkvHeader.lateTracks(sound, sound.size)!!.video)
        // Its size "unknown": not one whole.
        val unknown = idBytes(0x1654AE6B) + byteArrayOf(0xFF.toByte()) + list.copyOfRange(5, list.size)
        assertEquals(-1L, MkvHeader.elementSize(unknown, 12, MkvHeader.ID_TRACKS))
    }

    @Test
    fun randomBytesNeverThrowWhenLookedAtForWhereThingsAre() {
        val random = Random(3)
        repeat(500) {
            val b = random.nextBytes(random.nextInt(0, 400))
            val withSegment = idBytes(0x18538067) + byteArrayOf(0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
                0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) + b
            MkvHeader.layout(b, b.size)
            MkvHeader.layout(withSegment, withSegment.size)?.lateTracks(withSegment.size, 1L shl 30)
            MkvHeader.lateTracks(b, b.size)
            MkvHeader.elementSize(b, b.size, MkvHeader.ID_TRACKS)
        }
    }

    // --- Reading up to a point

    @Test
    fun aBoundedInputEndsWhereItsToldAndRefusesWholeWhatRunsPast() {
        val bytes = ByteArray(1000) { it.toByte() }
        val input = DefaultExtractorInput(source(bytes), 0, bytes.size.toLong())
        val bounded = BoundedInput(input, 100)
        val out = ByteArray(60)
        assertTrue(bounded.readFully(out, 0, 60, true))
        assertArrayEquals(bytes.copyOf(60), out)
        // 60 more would run past 100: refused whole, nothing taken; a skip or a peek likewise.
        for (refused in listOf<() -> Unit>({ bounded.readFully(out, 0, 60, true) }, { bounded.skipFully(41) },
            { bounded.peekFully(out, 0, 41) }, { bounded.advancePeekPosition(41, true) })) {
            try {
                refused()
                fail("past the end")
            } catch (e: EOFException) {
                assertTrue(bounded.overran)
            }
            assertEquals(60L, input.position)
            bounded.resetPeekPosition()
        }
        assertTrue(bounded.readFully(out, 0, 37, true))
        // A read asked for more than is left gives no more than is left.
        assertTrue(bounded.read(out, 0, 60) in 1..3)
        assertTrue(bounded.skipFully(100 - input.position.toInt(), true))
        assertEquals(100L, input.position)
        // At the end: the end of the file, as far as the reader can tell.
        assertFalse(bounded.readFully(out, 0, 1, true))
        assertFalse(bounded.skipFully(1, true))
        assertEquals(C.RESULT_END_OF_INPUT, bounded.read(out, 0, 1))
        assertEquals(C.RESULT_END_OF_INPUT, bounded.skip(1))
        assertEquals(C.RESULT_END_OF_INPUT, bounded.peek(out, 0, 1))
        // The rest of the file is still there for whoever reads on.
        input.readFully(out, 0, 10)
        assertArrayEquals(bytes.copyOfRange(100, 110), out.copyOf(10))
    }

    private fun source(bytes: ByteArray): DataReader {
        var at = 0
        return DataReader { buffer, offset, length ->
            if (at >= bytes.size) return@DataReader C.RESULT_END_OF_INPUT
            val n = minOf(length, bytes.size - at, 7)
            System.arraycopy(bytes, at, buffer, offset, n)
            at += n
            n
        }
    }

    // --- The log's "prepared" line

    @Test
    fun preparedIsToldOnceEachTimeTheTracksBecomeKnown() {
        val clock = PrepareClock()
        clock.start(1_000L, 5L * 1_048_576)
        assertNull(clock.tracks(empty = true, now = 2_000L, bytes = 6L * 1_048_576))
        assertEquals("prepared +71000ms got=1033.7MB", clock.tracks(empty = false, now = 72_000L, bytes = 5L * 1_048_576 + 1_083_950_014L))
        // Tracks changing again (another one chosen) says nothing more.
        assertNull(clock.tracks(empty = false, now = 80_000L, bytes = 0))
        // Let go and opened again (a reopen after a stall): timed from then.
        assertNull(clock.tracks(empty = true, now = 100_000L, bytes = 2_000L * 1_048_576))
        assertEquals("prepared +1500ms got=3.0MB", clock.tracks(empty = false, now = 101_500L, bytes = 2_003L * 1_048_576))
    }
}
