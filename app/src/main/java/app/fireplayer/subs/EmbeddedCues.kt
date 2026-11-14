package app.fireplayer.subs

import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput

/**
 * The timing of the film's own subtitle tracks, read as the film loads, for [AutoSync] to line downloaded subtitles up
 * against ([ReferenceCues]). Nothing is shown and nothing extra is loaded or decoded: Media3 already reads every
 * subtitle track in the file as it loads it, so [wrap] taps what its extractors put out and keeps only when each cue
 * starts and ends (and, for text, whether anyone is talking in it). Also kept: which stretches of the film have
 * loaded ([Source.covered]), as no cue there means no line there.
 *
 * Kept per film (the address the player was given, [Source.uri]), the latest few, so the next episode loading ahead
 * doesn't take the place of the one playing. Written on the loading thread, read on auto sync's: all under the
 * [Source]'s lock, and only a few numbers per cue.
 *
 * While auto sync listens ahead ([aheadUri]), the samples of the sound track playing in that film are also copied as
 * they load, into [ahead] (bounded; nothing waits on it): the sound the player loaded anyway, heard before it plays.
 * Otherwise nothing of the sound is copied, and it costs one look at [aheadUri] per sample.
 */
@OptIn(UnstableApi::class)
class EmbeddedCues {

    /** One film's subtitle tracks, as loaded so far. */
    class Source internal constructor(val uri: String) {
        /** Each picture and sound track's data rate, counted as the film loads (for its quality details). */
        val rates = app.fireplayer.player.TrackRates()
        // By the file's track number, so a new extractor for the same film (the player rebuilt) carries on the same.
        private val tracks = LinkedHashMap<Int, ReferenceCues>()
        // Loaded stretches of film (ms), as start-end pairs in order; the one being loaded now is [runFrom]..[runTo].
        private val runs = java.util.TreeMap<Long, Long>()
        private var runFrom = -1L
        private var runTo = -1L
        /** Goes up whenever anything changes, so a reader can tell it has news. */
        @Volatile var version = 0
            private set
        /** Whether the file has said which tracks it has. */
        @Volatile var tracksKnown = false
            internal set

        // The film's sound tracks by the file's number, and the one copied for listening ahead (see [aheadTrack]).
        private val audio = LinkedHashMap<Int, Format>()
        /** Goes up whenever a sound track is added or changes. */
        @Volatile var audioVersion = 0
            private set

        internal fun audioTrack(id: Int, format: Format) = synchronized(this) {
            if (audio[id] === format) return@synchronized
            audio[id] = format
            audioVersion++
        }

        /**
         * The sound track to listen to ahead, by the file's number (-1 with none yet): the one [playing] (the player's
         * id for it, which may have "<period>:" before the file's), else the only one, else the one marked default,
         * else the first.
         */
        fun aheadTrack(playing: String?): Int = synchronized(this) {
            if (audio.isEmpty()) return@synchronized -1
            if (playing != null) {
                audio.entries.firstOrNull { (_, f) -> f.id != null && (playing == f.id || playing.endsWith(":" + f.id)) }?.let { return@synchronized it.key }
            }
            if (audio.size == 1) return@synchronized audio.keys.first()
            audio.entries.firstOrNull { it.value.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 }?.key ?: audio.keys.first()
        }

        internal fun track(id: Int, info: ReferenceCues.Info) = synchronized(this) {
            if (tracks[id]?.info?.let { it.id == info.id && it.picture == info.picture } == true) return@synchronized
            tracks[id] = ReferenceCues(info)
            version++
        }

        internal fun cue(id: Int, startMs: Long, durationMs: Long?, cueCount: Int, texts: List<String>) = synchronized(this) {
            val t = tracks[id] ?: return@synchronized
            t.add(startMs, durationMs, cueCount, texts)
            version++
        }

        /** A sample of the sound or picture at [ms] was loaded: the film up to about there has loaded. */
        internal fun loaded(ms: Long) = synchronized(this) {
            // Behind where loading had got, not only before where it began: a skip back into the stretch being loaded
            // starts loading there again, and that film is gone over again (lined up afresh) from there.
            if (runFrom < 0 || ms < runTo - JUMP_MS || ms > runTo + JUMP_MS) {
                // Loading started somewhere else (a jump): the stretch before is kept as it was.
                if (runFrom >= 0) keep(runFrom, runTo)
                runFrom = ms
                runTo = ms
            } else if (ms > runTo) {
                runTo = ms
            } else {
                return@synchronized
            }
            version++
        }

        private fun keep(from: Long, to: Long) {
            var a = from
            var b = to
            // Joined with any it overlaps.
            runs.floorEntry(b)?.let { if (it.value >= a) { a = minOf(a, it.key); b = maxOf(b, it.value); runs.remove(it.key) } }
            while (true) {
                val e = runs.ceilingEntry(a) ?: break
                if (e.key > b) break
                b = maxOf(b, e.value)
                runs.remove(e.key)
            }
            runs[a] = b
            while (runs.size > MAX_RUNS) runs.pollFirstEntry()
        }

        /**
         * What's known now: the track to line up against ([ReferenceCues.choose]) with its cues, and the stretches
         * loaded. Cues are only trusted up to a few seconds before where loading has got to (the file stores them
         * among the pictures and sound of about when they start). Null while there's no track worth using.
         */
        fun snapshot(): Snapshot? = snapshot(null)

        /**
         * As [snapshot], with the track whose [ReferenceCues.Info.key] is [key] when there is one (and it isn't down to
         * a few lines), else the one [ReferenceCues.choose] picks.
         */
        fun snapshot(key: String?): Snapshot? = synchronized(this) {
            val covered = covered()
            val total = covered.sumOf { it[1] - it[0] }
            val chosen = key?.let { k -> tracks.values.firstOrNull { it.info.key == k } }
                ?.takeIf { total < ReferenceCues.MIN_COVERED_MS || it.perMinute(total) >= ReferenceCues.MIN_PER_MINUTE }
                ?: ReferenceCues.choose(tracks.values.toList(), total) ?: return@synchronized null
            Snapshot(chosen.info, chosen.intervals(runTo), covered, if (runFrom >= 0) runFrom else -1, runTo, version, chosen.count)
        }

        /** Every track that could be lined up against (not forced, not down to a few lines), with its cues so far. */
        fun candidates(): List<Track> = synchronized(this) {
            val total = covered().sumOf { it[1] - it[0] }
            tracks.values.filter { !it.info.forced && (total < ReferenceCues.MIN_COVERED_MS || it.perMinute(total) >= ReferenceCues.MIN_PER_MINUTE) }
                .map { Track(it.info, it.intervals(runTo), it.count) }
        }

        /** The stretches of film loaded (ms), each trusted up to [SETTLE_MS] before its end. */
        fun covered(): List<LongArray> = synchronized(this) {
            val all = java.util.TreeMap(runs)
            if (runFrom >= 0) {
                var a = runFrom
                var b = runTo
                all.floorEntry(b)?.let { if (it.value >= a) { a = minOf(a, it.key); b = maxOf(b, it.value); all.remove(it.key) } }
                all[a] = b
            }
            all.entries.mapNotNull { (a, b) -> if (b - SETTLE_MS > a) longArrayOf(a, b - SETTLE_MS) else null }
        }

        /** Where the stretch being loaded now began and how far it has got (ms); -1s before anything loaded. */
        fun loading(): Pair<Long, Long> = synchronized(this) { runFrom to runTo }

        /** Whether the file has any track that could ever be used (not only forced ones). */
        fun hasCandidates(): Boolean = synchronized(this) { tracks.values.any { !it.info.forced } }

        /** For the log: each track and its cues so far. */
        fun describe(): String = synchronized(this) { tracks.values.joinToString { "${it.info}: ${it.count} cues" } }
    }

    /** One of a film's tracks as it stands: its cues (start-end, ms) so far. */
    class Track(val info: ReferenceCues.Info, val cues: List<LongArray>, val count: Int)

    /** One look at a [Source]: see [Source.snapshot]. Times in ms. */
    class Snapshot(
        val track: ReferenceCues.Info,
        val cues: List<LongArray>,
        val covered: List<LongArray>,
        /** Where the stretch being loaded now began, and how far it has got (before [SETTLE_MS] is taken off). */
        val loadingFromMs: Long,
        val loadedToMs: Long,
        val version: Int,
        val cueCount: Int,
    )

    private val sources = LinkedHashMap<String, Source>()

    /**
     * The film whose sound is copied as it loads ([ahead]), or null for none: set while auto sync listens ahead. Read
     * on the loading thread.
     */
    @Volatile var aheadUri: String? = null
    /** The player's id for the sound track playing ([Format.id]), so that's the one copied; null while unknown. */
    @Volatile var playingAudioId: String? = null
    /** The sound copied as it loads, for listening ahead. */
    val ahead = AheadAudio()

    /** The film loaded from [uri] (the address the player was given), or null when nothing of it has loaded. */
    fun source(uri: String?): Source? = if (uri == null) null else synchronized(sources) { sources[uri] }

    private fun sourceFor(uri: String): Source = synchronized(sources) {
        sources[uri] ?: Source(uri).also {
            sources[uri] = it
            while (sources.size > MAX_SOURCES) sources.remove(sources.keys.first())
        }
    }

    /** [base], with every extractor it makes passing the film's subtitle timing (and how far it has loaded) on here. */
    fun wrap(base: ExtractorsFactory): ExtractorsFactory = object : ExtractorsFactory {
        override fun createExtractors(): Array<Extractor> = base.createExtractors()

        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> {
            val made = base.createExtractors(uri, responseHeaders)
            val key = uri.toString()
            return Array(made.size) { Tapped(made[it]) { out -> output(key, out) } }
        }
    }

    /** [inner], tapped for the film loaded from [uri]: what an extractor of it puts out passes on here as well. */
    internal fun output(uri: String, inner: ExtractorOutput): ExtractorOutput = TapOutput(inner, sourceFor(uri), this)

    /** An extractor whose output is tapped; the [Source] is only made once it's the one reading the file. */
    private class Tapped(private val inner: Extractor, private val tap: (ExtractorOutput) -> ExtractorOutput) : Extractor {
        override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)
        override fun getSniffFailureDetails() = inner.sniffFailureDetails
        override fun init(output: ExtractorOutput) = inner.init(tap(output))
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = inner.read(input, seekPosition)
        override fun seek(position: Long, timeUs: Long) = inner.seek(position, timeUs)
        override fun release() = inner.release()
        override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation
    }

    private class TapOutput(private val inner: ExtractorOutput, private val source: Source, private val owner: EmbeddedCues) : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput {
            val track = inner.track(id, type)
            return when (type) {
                C.TRACK_TYPE_TEXT -> TextTap(track, source, id)
                C.TRACK_TYPE_AUDIO -> AudioTap(track, source, id, owner)
                C.TRACK_TYPE_VIDEO -> LoadedTap(track, source, id)
                else -> track
            }
        }

        override fun endTracks() {
            source.tracksKnown = true
            inner.endTracks()
        }

        override fun seekMap(seekMap: SeekMap) = inner.seekMap(seekMap)
    }

    /** Passes everything on; notes how far the film has loaded. */
    private class LoadedTap(private val inner: TrackOutput, private val source: Source, id: Int) : TrackOutput {
        // Its rate is kept under the id the player knows the track by (the format's), else the extractor's.
        private var key = id.toString()
        override fun format(format: Format) {
            format.id?.let { key = it }
            inner.format(format)
        }
        override fun durationUs(durationUs: Long) = inner.durationUs(durationUs)
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            inner.sampleData(input, length, allowEndOfInput, sampleDataPart)
        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = inner.sampleData(data, length, sampleDataPart)
        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            if (timeUs >= 0) source.loaded(timeUs / 1000)
            source.rates.add(key, timeUs, size)
            inner.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        }
    }

    /**
     * Passes everything on; notes how far the film has loaded; and while auto sync listens ahead to this film and this
     * is the sound track playing, copies each sample on to [ahead] with its time. The bytes are copied as the player
     * reads them into its own buffer (one copy, nothing read again), and whatever goes wrong copying only stops the
     * copying: the film loads as it would without this.
     */
    private class AudioTap(
        private val inner: TrackOutput,
        private val source: Source,
        private val id: Int,
        private val owner: EmbeddedCues,
    ) : TrackOutput {
        private var format: Format? = null
        private var key = id.toString()
        // The sample's bytes so far (and any of the samples after it already given, see sampleMetadata's offset).
        private var staging = ByteArray(0)
        private var staged = 0
        // Whether the sample being given is copied (decided as it starts); it outgrew [MAX_STAGING]; copying failed.
        private var copying = false
        private var overflow = false
        private var broken = false
        // What [wanted] last found, so each sample costs a look at two fields.
        private var lastUri: String? = null
        private var uriMatches = false
        private var lastPlaying: String? = null
        private var lastVersion = -1
        private var chosen = false
        private val reader = CopyingReader()

        override fun format(format: Format) {
            this.format = format
            format.id?.let { key = it }
            try { source.audioTrack(id, format) } catch (t: Throwable) { fail(t) }
            inner.format(format)
        }

        override fun durationUs(durationUs: Long) = inner.durationUs(durationUs)

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            if (!copyThis()) return inner.sampleData(input, length, allowEndOfInput, sampleDataPart)
            reader.input = input
            try {
                return inner.sampleData(reader, length, allowEndOfInput, sampleDataPart)
            } finally {
                reader.input = null
            }
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (length > 0 && copyThis()) {
                try { stage(data.data, data.position, length) } catch (t: Throwable) { fail(t) }
            }
            inner.sampleData(data, length, sampleDataPart)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            if (timeUs >= 0) source.loaded(timeUs / 1000)
            source.rates.add(key, timeUs, size)
            if (copying) {
                try { take(timeUs, flags, size, offset, cryptoData) } catch (t: Throwable) { fail(t) }
            } else {
                staged = 0
            }
            inner.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        }

        /** Whether the bytes being given now are copied: decided as each sample starts. */
        private fun copyThis(): Boolean {
            if (staged == 0 && !overflow) copying = !broken && try { wanted() } catch (t: Throwable) { fail(t); false }
            return copying
        }

        private fun wanted(): Boolean {
            val uri = owner.aheadUri ?: return false
            if (uri !== lastUri) {
                lastUri = uri
                uriMatches = uri == source.uri
            }
            if (!uriMatches || format == null) return false
            val playing = owner.playingAudioId
            val version = source.audioVersion
            if (playing !== lastPlaying || version != lastVersion) {
                lastPlaying = playing
                lastVersion = version
                chosen = source.aheadTrack(playing) == id
            }
            return chosen
        }

        private fun stage(bytes: ByteArray, at: Int, length: Int) {
            if (overflow) return
            val need = staged + length
            if (need > MAX_STAGING) {
                // A sample bigger than any sound needs: let go, copying starts again with the next.
                overflow = true
                return
            }
            if (need > staging.size) staging = staging.copyOf(minOf(MAX_STAGING, maxOf(need, staging.size * 2, 16 * 1024)))
            System.arraycopy(bytes, at, staging, staged, length)
            staged = need
        }

        /** The sample's metadata: its bytes, the last [size] before the [offset] given after it, go on to [ahead]. */
        private fun take(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            val f = format
            val plain = cryptoData == null && flags and (C.BUFFER_FLAG_ENCRYPTED or C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA) == 0
            if (!overflow && plain && f != null && size > 0 && offset >= 0 && timeUs >= 0 && staged >= size + offset) {
                owner.ahead.sample(source.uri, id, f, staging, staged - offset - size, size, timeUs)
            }
            if (overflow || offset <= 0 || offset > staged) {
                staged = 0
            } else {
                // Bytes of the samples after this one, given already: kept for them.
                System.arraycopy(staging, staged - offset, staging, 0, offset)
                staged = offset
            }
            overflow = false
        }

        private fun fail(t: Throwable) {
            if (!broken) Log.w(TAG, "Stopped copying the sound for listening ahead", t)
            broken = true
            copying = false
            overflow = false
            staged = 0
        }

        /** Reads through to the file as the player asks, copying what it read. */
        private inner class CopyingReader : DataReader {
            var input: DataReader? = null

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val n = input!!.read(buffer, offset, length)
                if (n > 0 && copying) {
                    try { stage(buffer, offset, n) } catch (t: Throwable) { fail(t) }
                }
                return n
            }
        }
    }

    /**
     * Passes everything on; keeps when each cue of a subtitle track starts and ends. Media3 hands on subtitles already
     * parsed ([MimeTypes.APPLICATION_MEDIA3_CUES]: each sample a cue list and how long it shows), which is all that's
     * read here: the words of text cues, and for pictures only that there is one (the picture itself isn't touched).
     */
    private class TextTap(private val inner: TrackOutput, private val source: Source, private val id: Int) : TrackOutput {
        private var parsed = false
        private val bytes = ParsableByteArray()
        private var size = 0

        override fun format(format: Format) {
            parsed = format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES
            if (parsed) {
                val mime = format.codecs ?: format.sampleMimeType
                val label = format.label ?: format.labels.firstOrNull()?.value
                source.track(id, ReferenceCues.Info(
                    id = format.id,
                    language = format.language,
                    label = label,
                    forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0 || ReferenceCues.forcedLabel(label),
                    hearingImpaired = format.roleFlags and (C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND or C.ROLE_FLAG_TRANSCRIBES_DIALOG) != 0 ||
                        ReferenceCues.hearingImpairedLabel(label),
                    picture = mime in PICTURE_FORMATS,
                ))
            }
            inner.format(format)
        }

        override fun durationUs(durationUs: Long) = inner.durationUs(durationUs)

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            // Parsed cues always come as a whole array (below); anything read straight from the file isn't kept.
            inner.sampleData(input, length, allowEndOfInput, sampleDataPart)

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            if (parsed && sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN && length > 0) {
                if (bytes.capacity() < size + length) bytes.ensureCapacity(size + length)
                System.arraycopy(data.data, data.position, bytes.data, size, length)
                size += length
            }
            inner.sampleData(data, length, sampleDataPart)
        }

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
            if (parsed && this.size >= size && size > 0) {
                read(timeUs, this.size - offset - size, size)
            }
            this.size = 0
            inner.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        }

        private fun read(timeUs: Long, at: Int, length: Int) {
            if (at < 0 || timeUs < 0) return
            // Never let a cue that can't be read hold up the film: it's left out.
            runCatching {
                val parcel = Parcel.obtain()
                val bundle: Bundle?
                try {
                    parcel.unmarshall(bytes.data, at, length)
                    parcel.setDataPosition(0)
                    bundle = parcel.readBundle(Bundle::class.java.classLoader)
                } finally {
                    parcel.recycle()
                }
                if (bundle == null) return
                @Suppress("DEPRECATION")
                val cues: ArrayList<Bundle> = bundle.getParcelableArrayList(CUES_KEY) ?: return
                val durationUs = bundle.getLong(DURATION_KEY, C.TIME_UNSET)
                val texts = cues.mapNotNull { it.getCharSequence(TEXT_KEY)?.toString() }
                source.cue(id, timeUs / 1000, if (durationUs == C.TIME_UNSET || durationUs <= 0) null else durationUs / 1000, cues.size, texts)
            }.onFailure { if (!warned) { warned = true; Log.w(TAG, "Couldn't read an embedded subtitle's timing", it) } }
        }

        private var warned = false
    }

    companion object {
        private const val TAG = "EmbeddedCues"
        // Further than this from where loading had got to: loading started somewhere else.
        private const val JUMP_MS = 10_000L
        // The file keeps a cue among the pictures and sound of about when it starts: trusted this far behind them.
        const val SETTLE_MS = 5_000L
        private const val MAX_RUNS = 64
        private const val MAX_SOURCES = 3
        // The most of one sound sample copied for listening ahead (a second of 7.1 PCM is 1 MB; compressed, far less).
        private const val MAX_STAGING = 256 * 1024
        // How Media3 (1.8) writes a sample of parsed cues: a Bundle of cue Bundles ("c") and how long they show
        // ("d", µs), as CueDecoder reads it; a text cue's words under Cue's field 0.
        private const val CUES_KEY = "c"
        private const val DURATION_KEY = "d"
        private val TEXT_KEY = Util.intToStringMaxRadix(0)
        private val PICTURE_FORMATS = setOf(MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_VOBSUB, MimeTypes.APPLICATION_DVBSUBS)
    }
}
