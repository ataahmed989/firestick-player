package app.fireplayer.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import java.io.EOFException
import java.util.Locale

/**
 * Passes on each MKV's frame rate (from the video track's DefaultDuration), which Media3 leaves out,
 * so the TV can switch to the film's rate before playback starts.
 *
 * Also makes MKV files without a seek index (no Cues, as some recorders and editors such as VideoRedo
 * write them) skippable. Media3 plays such files but refuses to seek in them. This wraps every
 * Matroska extractor; when it reports an unseekable file of known length and duration, a jump is
 * found by reading cluster timestamps at a few points in the file and narrowing in on the target.
 *
 * And it gets past a damaged stretch of a file that has an index: where the bytes stop making sense (a provider's copy
 * with a blank hole in it, all zeros, was seen), it goes on from the next clusters the index lists that start as
 * clusters should, instead of failing at the same spot every time it's tried again (which looked like endless
 * buffering). With nothing usable after the damage, it fails as a damaged file rather than as a dropped connection, so
 * the error shows at once. A file without an index is left to fail as before.
 *
 * And a file that keeps its track list (Tracks) after its clusters, as some muxers write it, starts as quickly as any
 * other: Media3's reader passes over every block it meets before it knows the tracks, so the whole file was read before
 * the film could start (79 s for a 1 GB episode). The track list is read first instead ([SeekableMkvExtractor.Detour]).
 */
@OptIn(UnstableApi::class)
class SeekableMkvExtractorsFactory(
    private val base: ExtractorsFactory,
    /** Told of each damaged stretch gone past: the film's link, and the times it plays to and goes on from. */
    private val onSkip: ((uri: String?, fromUs: Long, toUs: Long) -> Unit)? = null,
    /** Whether how the file is laid out and when its tracks came go in the log: the film's player, not the preview's. */
    private val logs: Boolean = true,
) : ExtractorsFactory by base {

    override fun createExtractors(): Array<Extractor> = wrap(base.createExtractors(), null)

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(base.createExtractors(uri, responseHeaders), uri.toString())

    private fun wrap(extractors: Array<Extractor>, uri: String?): Array<Extractor> =
        Array(extractors.size) { i ->
            val e = extractors[i]
            if (e is MatroskaExtractor) SeekableMkvExtractor(e, uri, onSkip, logs) else e
        }
}

@OptIn(UnstableApi::class)
private class SeekableMkvExtractor(
    private val inner: Extractor,
    private val uri: String?,
    private val onSkip: ((uri: String?, fromUs: Long, toUs: Long) -> Unit)?,
    private val logs: Boolean,
) : Extractor by inner {

    private class Cluster(val position: Long, val timeUs: Long)

    private var input: ExtractorInput? = null
    private var length = C.LENGTH_UNSET.toLong()
    private var timescaleNs = 1_000_000L
    private var headerScanned = false

    /** Set once we've stood in for Media3's "can't seek" answer. */
    private var active = false
    private var fileDurationUs = C.TIME_UNSET
    private var dataStart = 0L
    private var first: Cluster? = null
    private var search: Search? = null
    // Where a finished search sent the player, until reading starts there; -1 when none is pending.
    private var landingPos = -1L
    private val buffer = ByteArray(CHUNK + TAIL)

    /** From the video track's DefaultDuration; 0 when the file doesn't say. */
    private var videoFrameRate = 0f

    /** The video track's Dolby Vision record's base signal ([MkvHeader.Video.dvSignal]); null without one. */
    private var dvSignal: Int? = null

    /** The file's own index, when it has one: where a damaged stretch is got past. */
    private var index: SeekMap? = null
    private var recovery: Recovery? = null
    // Damaged stretches got past in this file, so one that's damaged throughout ends in an error.
    private var recovered = 0
    // The latest time of film read since the last jump: where playback runs out before a damaged stretch. A jump
    // straight into one has read nothing, and playback goes on from where the jump was to ([jumpedToUs]).
    @Volatile private var lastSampleUs = C.TIME_UNSET
    private var jumpedToUs = 0L

    /** The track list read before the clusters, for a file that keeps it after them; null once read, or when not needed. */
    private var detour: Detour? = null

    // For the log: when the first read came (elapsedRealtime), and whether the first track and the end of the track
    // list have been told of yet. Once per file each.
    private var startedAt = 0L
    private var trackTold = false
    private var endTracksTold = false

    override fun init(output: ExtractorOutput) {
        inner.init(object : ExtractorOutput by output {
            override fun track(id: Int, type: Int): TrackOutput {
                if (!trackTold) {
                    trackTold = true
                    tell("mkv first track()")
                }
                val track = output.track(id, type)
                if (type != C.TRACK_TYPE_VIDEO && type != C.TRACK_TYPE_AUDIO) return track
                return object : TrackOutput by track {
                    override fun format(format: Format) {
                        val fps = videoFrameRate
                        val missing = type == C.TRACK_TYPE_VIDEO && format.frameRate <= 0f && fps > 0f
                        // A Dolby Vision track's record, which Media3's Matroska reader reads only for its codecs string:
                        // what its base layer is (8.1, 8.4, 8.2), kept on the format ([DvBaseLayer.recordSignal]).
                        val signal = dvSignal?.takeIf {
                            type == C.TRACK_TYPE_VIDEO && format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION && format.customData == null
                        }
                        if (!missing && signal == null) return track.format(format)
                        val b = format.buildUpon()
                        if (missing) b.setFrameRate(fps)
                        if (signal != null) b.setCustomData(DvBaseLayer.Signal(signal))
                        track.format(b.build())
                    }

                    // A Java default method, which `by` doesn't pass on by itself.
                    override fun durationUs(durationUs: Long) = track.durationUs(durationUs)

                    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                        if (timeUs > lastSampleUs || lastSampleUs == C.TIME_UNSET) lastSampleUs = timeUs
                        track.sampleMetadata(timeUs, flags, size, offset, cryptoData)
                    }
                }
            }

            override fun endTracks() {
                // Met again at the end of a file whose track list was read first ([Detour]): told of once.
                if (!endTracksTold) {
                    endTracksTold = true
                    tell("mkv endTracks")
                }
                output.endTracks()
            }

            override fun seekMap(seekMap: SeekMap) {
                if (logs) {
                    val seconds = if (seekMap.durationUs == C.TIME_UNSET) "unknown" else "${seekMap.durationUs / 1_000_000}s"
                    tell("mkv seekMap seekable=${seekMap.isSeekable} dur=$seconds")
                }
                val position = input?.position ?: -1L
                val usable = !seekMap.isSeekable && seekMap.durationUs != C.TIME_UNSET && length > 0 && position > 0
                if (!usable) {
                    if (seekMap.isSeekable && seekMap.durationUs != C.TIME_UNSET) index = seekMap
                    output.seekMap(seekMap)
                    return
                }
                active = true
                fileDurationUs = seekMap.durationUs
                // Normally this is the first cluster. If Media3 gave up after reading a broken index at the
                // end of the file, the clusters are found by scanning from the start instead.
                dataStart = if (position < length / 2) position else 0L
                output.seekMap(EstimatedSeekMap())
            }
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        this.input = input
        if (input.length != C.LENGTH_UNSET.toLong()) length = input.length
        if (!headerScanned && input.position == 0L) {
            if (startedAt == 0L) startedAt = SystemClock.elapsedRealtime()
            // Scanned once it's been read: a connection that failed on it (tried again from the start) has it read again.
            readHeader(input)
            headerScanned = true
        }
        // Null once the track list has been read (or when it's where it should be): one look per read.
        detour?.let { d -> d.step(input, seekPosition)?.let { return it } }
        search?.let { return it.step(input, seekPosition) }
        recovery?.let { return it.step(input, seekPosition) }
        val landing = landingPos
        if (landing >= 0) {
            // A reconnect that failed is tried again where the last connection had got to, not where the jump was to
            // land; reading on from there would start the film part-way into a cluster.
            if (input.position != landing) {
                seekPosition.position = landing
                return Extractor.RESULT_SEEK
            }
            landingPos = -1
        }
        // The file's index, at its end: Media3 reads it a byte or two at a time (millions of reads for a long film), each
        // going through every layer below, so it's handed out from big pieces read at once.
        val batched = if (length > 0 && input.position >= length - IndexCache.TAIL) BatchedInput(input) else null
        if (batched != null) this.input = batched
        return try {
            inner.read(batched ?: input, seekPosition)
        } catch (e: IllegalStateException) {
            batched?.finish()
            damaged(input, seekPosition, e)
        } catch (e: ParserException) {
            // Only bytes that don't make sense: a feature Media3 doesn't support stays that error.
            if (!e.contentIsMalformed) throw e
            batched?.finish()
            damaged(input, seekPosition, e)
        } finally {
            if (batched != null) {
                batched.finish()
                this.input = input
            }
        }
    }

    /** The bytes at the input's position don't read as MKV: goes on past them if the index allows, else fails. */
    private fun damaged(input: ExtractorInput, holder: PositionHolder, e: Exception): Int {
        val at = input.position
        val map = index
        // Without an index, it's left as it was: Media3's error, tried again by the player.
        if (map == null || length <= 0) throw e
        if (recovered >= MAX_RECOVERIES) {
            Log.w(TAG, "The file doesn't read at byte $at of $length, after $recovered damaged stretches got past", e)
            throw ParserException.createForMalformedContainer("Damaged at byte $at", e)
        }
        recovered++
        val r = Recovery(map, at, e)
        recovery = r
        return r.step(input, holder)
    }

    // A Java default method, which `by` doesn't pass on: the log names the reader underneath (MatroskaExtractor).
    override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation

    override fun seek(position: Long, timeUs: Long) {
        // Media3 starts every load with a seek: from the start, the track list is read first again; from anywhere else
        // (nothing asks for that before the tracks are known), the file is read as it would be without this.
        detour?.let { if (position == 0L) it.restart() else detour = null }
        landingPos = -1
        recovery = null
        lastSampleUs = C.TIME_UNSET
        jumpedToUs = timeUs
        inner.seek(position, timeUs)
        search = if (active && (position != 0L || timeUs != 0L)) Search(timeUs) else null
    }

    /**
     * Finds where the file reads again after [damagedAt]: index entries further and further on (each step twice as
     * far) until one starts as a cluster should, then halving the stretch between the last damaged entry and that one,
     * so as little of the film as possible is passed over. Each look is one request for a few bytes.
     */
    private inner class Recovery(private val map: SeekMap, private val damagedAt: Long, private val cause: Exception) {
        // The last place found damaged, and the earliest found to read (with the times either side, for halving).
        private var badPos = damagedAt
        private var good: SeekPoint? = null
        private var lo = 0L
        private var hi = 0L
        private var expandUs = firstTimeAfter(damagedAt)
        private var stepUs = FIRST_STEP_US
        private var visiting: SeekPoint? = null
        private var tries = 0
        private val head = ByteArray(4)

        fun step(input: ExtractorInput, holder: PositionHolder): Int {
            while (true) {
                val v = visiting ?: next() ?: return finish()
                visiting = v
                if (input.position != v.position) {
                    holder.position = v.position
                    return Extractor.RESULT_SEEK
                }
                visiting = null
                tries++
                if (startsCluster(input)) {
                    good = v
                    hi = v.timeUs
                } else {
                    badPos = v.position
                    lo = maxOf(lo, v.timeUs)
                }
            }
        }

        /** The next index entry to look at, or null when there's nothing more worth looking at. */
        private fun next(): SeekPoint? {
            if (tries >= MAX_PROBES) return null
            val g = good
            if (g == null) {
                while (true) {
                    val p = map.getSeekPoints(expandUs).first
                    if (p.position > badPos) return p.takeIf { it.position < length }
                    if (expandUs >= map.durationUs) return null
                    expandUs = minOf(map.durationUs, expandUs + stepUs)
                    stepUs *= 2
                }
            }
            while (hi - lo > 1) {
                val mid = lo + (hi - lo) / 2
                val p = map.getSeekPoints(mid).first
                when {
                    p.position <= badPos -> lo = mid
                    p.position >= g.position -> hi = mid
                    else -> return p
                }
            }
            return null
        }

        private fun startsCluster(input: ExtractorInput): Boolean {
            val ok = try {
                input.peekFully(head, 0, head.size, true)
            } catch (e: EOFException) {
                false
            } finally {
                input.resetPeekPosition()
            }
            return ok && head[0] == ID0 && head[1] == ID1 && head[2] == ID2 && head[3] == ID3
        }

        private fun finish(): Int {
            recovery = null
            val g = good
            if (g == null) {
                Log.w(TAG, "The file doesn't read at byte $damagedAt of $length, nor at the $tries places after it tried", cause)
                throw ParserException.createForMalformedContainer("Damaged from byte $damagedAt", cause)
            }
            val fromUs = lastSampleUs.takeIf { it != C.TIME_UNSET } ?: jumpedToUs
            Log.w(TAG, "The file doesn't read at byte $damagedAt of $length (${cause.message}); going on at " +
                "${g.timeUs / 1000} ms, byte ${g.position} ($tries looks), read to ${fromUs / 1000} ms")
            runCatching { onSkip?.invoke(uri, fromUs, g.timeUs) }
            inner.seek(g.position, g.timeUs)
            // The last look may have been further on than the place found: reading starts there (see [read]).
            landingPos = g.position
            return Extractor.RESULT_CONTINUE
        }

        /** The earliest time whose index entry starts after [position]. */
        private fun firstTimeAfter(position: Long): Long {
            var a = 0L
            var b = map.durationUs
            if (map.getSeekPoints(b).first.position <= position) return b
            while (b - a > 1) {
                val mid = a + (b - a) / 2
                if (map.getSeekPoints(mid).first.position > position) b = mid else a = mid
            }
            return b
        }
    }

    /**
     * The track list (Tracks) of a file that keeps it after its clusters, read before them. Without this Media3's reader
     * passes over every block it meets before it knows the tracks, and the film only starts once the whole file has
     * been read (79 s for a 1 GB episode).
     *
     * The reader reads the file's first bytes up to where the Segment's contents begin ([HEAD]), then the track list
     * where the SeekHead says it is ([TRACKS]), then goes back to where it stopped and reads on as ever, its index
     * (Cues) and all. It's told the file ends at each of those two points, so it reads nothing past them; nothing of
     * the film is read twice. What it costs: two more requests, once per film.
     *
     * When the track list isn't where the SeekHead says, or doesn't read as one whole, the reader goes back to where it
     * stopped and reads on from there as it would without this.
     */
    private inner class Detour(private val segmentStart: Long, private val tracksAt: Long) {
        private var phase = HEAD
        // Where the track list ends, once it was found whole where the SeekHead says; -1 before.
        private var tracksEnd = -1L

        /** The file is read from its start again (a new load): the same way again. */
        fun restart() {
            phase = HEAD
            tracksEnd = -1
        }

        /** The next step: what [read] returns; null when given up, and the file is read as before from here. */
        fun step(input: ExtractorInput, holder: PositionHolder): Int? =
            if (phase == HEAD) head(input, holder) else tracks(input, holder)

        private fun head(input: ExtractorInput, holder: PositionHolder): Int? {
            if (input.position >= segmentStart) return giveUp("at ${input.position}")
            val bounded = BoundedInput(input, segmentStart)
            val result = try {
                inner.read(bounded, holder)
            } catch (e: EOFException) {
                if (!bounded.overran) throw e
                // Something in the first bytes runs on past where the Segment's contents begin: the read it was refused
                // moved nothing, so the reader reads on from here as before.
                return giveUp("the header runs on past $segmentStart")
            }
            if (result != Extractor.RESULT_END_OF_INPUT) {
                // Anything before the end of the first bytes is as it would have been anyway.
                detour = null
                return result
            }
            // The file itself ended there: as before.
            if (input.position != segmentStart) return giveUp("ended at ${input.position}")
            phase = TRACKS
            holder.position = tracksAt
            return Extractor.RESULT_SEEK
        }

        private fun tracks(input: ExtractorInput, holder: PositionHolder): Int? {
            if (tracksEnd < 0) {
                if (input.position != tracksAt) {
                    holder.position = tracksAt
                    return Extractor.RESULT_SEEK
                }
                val end = check(input)
                if (end < 0) {
                    // Not there after all: back to where the reader stopped, which reads on as before.
                    detour = null
                    if (logs) Log.i(TAG, "mkv Tracks not found whole at $tracksAt: read as before")
                    holder.position = segmentStart
                    return Extractor.RESULT_SEEK
                }
                tracksEnd = end
            }
            val bounded = BoundedInput(input, tracksEnd)
            val result = try {
                inner.read(bounded, holder)
            } catch (e: EOFException) {
                if (!bounded.overran) throw e
                throw ParserException.createForMalformedContainer("Tracks run on past their end", e)
            }
            when (result) {
                Extractor.RESULT_END_OF_INPUT -> {
                    // The connection ended early, not the track list: tried again from here, as any read.
                    if (input.position != tracksEnd) throw EOFException()
                    detour = null
                    if (logs) Log.i(TAG, "mkv Tracks read first: $tracksAt-$tracksEnd, fps=${MkvHeader.fpsText(videoFrameRate)} " +
                        "dvRecord=${dvSignal ?: "none"}, back to $segmentStart")
                    holder.position = segmentStart
                    return Extractor.RESULT_SEEK
                }
                Extractor.RESULT_SEEK -> detour = null
            }
            return result
        }

        /**
         * Where the track list at [tracksAt] ends when it's there whole and reads as one ([MkvHeader.lateTracks]); -1
         * otherwise. Its frame rate and Dolby Vision record are taken from it, as from a file's first bytes. Only looks:
         * what it looked at stays with [input], for the reader to read from there.
         */
        private fun check(input: ExtractorInput): Long {
            val room = length - tracksAt
            try {
                val head = ByteArray(minOf(12L, room).toInt())
                if (!input.peekFully(head, 0, head.size, true)) return -1
                val size = MkvHeader.elementSize(head, head.size, MkvHeader.ID_TRACKS)
                if (size <= 0 || size > room || size > MAX_TRACKS) return -1
                input.resetPeekPosition()
                val whole = ByteArray(size.toInt())
                if (!input.peekFully(whole, 0, whole.size, true)) return -1
                val found = MkvHeader.lateTracks(whole, whole.size) ?: return -1
                found.video?.let {
                    videoFrameRate = it.frameRate
                    dvSignal = it.dvSignal
                }
                return tracksAt + size
            } catch (e: EOFException) {
                return -1
            } finally {
                input.resetPeekPosition()
            }
        }

        private fun giveUp(why: String): Int? {
            detour = null
            if (logs) Log.i(TAG, "mkv Tracks not read first ($why): read as before")
            return null
        }
    }

    /** "position/length +ms since the first read": where the reader is, for the log. */
    private fun tell(what: String) {
        if (!logs) return
        val ms = if (startedAt > 0) (SystemClock.elapsedRealtime() - startedAt).toString() else "?"
        Log.i(TAG, "$what at ${input?.position ?: -1}/$length +${ms}ms")
    }

    /** Points the player at a rough spot; [Search] then finds the exact cluster. */
    private inner class EstimatedSeekMap : SeekMap {
        override fun isSeekable(): Boolean = true
        override fun getDurationUs(): Long = fileDurationUs
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val fraction = if (fileDurationUs > 0) timeUs.toDouble() / fileDurationUs else 0.0
            val estimate = dataStart + (fraction.coerceIn(0.0, 1.0) * (length - dataStart)).toLong()
            return SeekMap.SeekPoints(SeekPoint(timeUs, estimate.coerceIn(dataStart, length - 1)))
        }
    }

    private inner class Search(private val targetUs: Long) {
        private var loPos = -1L
        private var loTime = 0L
        private var hiPos = length
        private var hiTime = fileDurationUs
        private var steps = 0

        fun step(input: ExtractorInput, holder: PositionHolder): Int {
            val start = first
            if (start == null) {
                // First find where the clusters begin, a few bytes before where Media3 noticed them.
                if (input.position != (dataStart - 16).coerceAtLeast(0)) return seekTo(holder, (dataStart - 16).coerceAtLeast(0))
                val found = scan(input, Long.MAX_VALUE, stopAfterFirst = true).firstOrNull() ?: return finish(holder, 0L)
                first = found
                return begin(holder, found)
            }
            if (loPos < 0) {
                // The spot the player opened is a straight-line guess; use it as the first probe.
                loPos = start.position
                loTime = start.timeUs
                if (targetUs - start.timeUs <= TOLERANCE_US) return finish(holder, start.position)
                if (input.position <= loPos) return seekTo(holder, nextProbe())
            }

            steps++
            val probe = input.position
            var clusters = scan(input, targetUs, stopAfterFirst = false)
            // Nothing found means the probe is inside one huge cluster (high-bitrate files), which says
            // nothing about where the target is: keep reading forward until a cluster turns up.
            while (clusters.isEmpty() && steps < MAX_STEPS && input.position < minOf(hiPos, length)) {
                steps++
                clusters = scan(input, targetUs, stopAfterFirst = false)
            }
            val before = clusters.lastOrNull { it.timeUs <= targetUs }
            val after = clusters.firstOrNull { it.timeUs > targetUs }
            if (before != null && before.position > loPos) {
                loPos = before.position
                loTime = before.timeUs
            }
            when {
                // The target sits between two clusters we just read: start at the earlier one.
                before != null && after != null -> return finish(holder, before.position)
                before != null && targetUs - before.timeUs <= TOLERANCE_US -> return finish(holder, before.position)
                after != null || clusters.isEmpty() -> {
                    if (probe < hiPos) {
                        hiPos = probe
                        hiTime = after?.timeUs ?: hiTime
                    }
                }
            }
            if (steps >= MAX_STEPS || hiPos - loPos <= MIN_RANGE) return finish(holder, loPos)
            return seekTo(holder, nextProbe())
        }

        private fun begin(holder: PositionHolder, start: Cluster): Int {
            loPos = start.position
            loTime = start.timeUs
            if (targetUs - start.timeUs <= TOLERANCE_US) return finish(holder, start.position)
            return seekTo(holder, nextProbe())
        }

        /** Guesses where the target is from the bytes and times either side, landing a bit early. */
        private fun nextProbe(): Long {
            val span = (hiTime - loTime).coerceAtLeast(1)
            val bytesPerUs = (hiPos - loPos).toDouble() / span
            val guess = loPos + ((targetUs - loTime) * bytesPerUs).toLong() - (BACKOFF_US * bytesPerUs).toLong()
            val low = loPos + 1
            val high = hiPos - 1
            return if (guess in low..high) guess else loPos + (hiPos - loPos) / 2
        }

        private fun finish(holder: PositionHolder, position: Long): Int {
            search = null
            landingPos = position
            return seekTo(holder, position)
        }

        private fun seekTo(holder: PositionHolder, position: Long): Int {
            holder.position = position
            return Extractor.RESULT_SEEK
        }
    }

    /**
     * Reads forward from the input's position and returns the clusters found, in order. Stops at
     * the first cluster later than [targetUs], or [stopAfterFirst], or after a few MB past the first.
     */
    private fun scan(input: ExtractorInput, targetUs: Long, stopAfterFirst: Boolean): List<Cluster> {
        val found = ArrayList<Cluster>()
        val scanStart = input.position
        var bufferStart = scanStart
        var filled = 0
        var firstAt = -1L
        while (true) {
            val remaining = length - (bufferStart + filled)
            if (remaining <= 0) break
            val n = minOf(CHUNK.toLong(), remaining).toInt()
            val ok = try {
                input.readFully(buffer, filled, n, true)
            } catch (e: EOFException) {
                false
            }
            if (!ok) break
            filled += n
            val atEnd = bufferStart + filled >= length
            val limit = if (atEnd) filled - 4 else filled - TAIL
            var i = 0
            while (i <= limit) {
                if (buffer[i] == ID0 && buffer[i + 1] == ID1 && buffer[i + 2] == ID2 && buffer[i + 3] == ID3) {
                    val time = clusterTime(buffer, i, filled)
                    if (time != null) {
                        val cluster = Cluster(bufferStart + i, time)
                        found += cluster
                        if (firstAt < 0) firstAt = cluster.position
                        if (stopAfterFirst || time > targetUs) return found
                    }
                }
                i++
            }
            if (firstAt >= 0 && bufferStart + filled - firstAt > AFTER_FIRST) break
            if (firstAt < 0 && bufferStart + filled - scanStart > MAX_SCAN) break
            // Keep the unchecked tail so a cluster header split across reads is still found.
            val keep = filled - i
            System.arraycopy(buffer, i, buffer, 0, keep)
            bufferStart += i
            filled = keep
        }
        return found
    }

    /** Parses a cluster header at [at]: ID, size, optional CRC-32, then its Timestamp element. */
    private fun clusterTime(b: ByteArray, at: Int, end: Int): Long? {
        var j = at + 4
        if (j >= end) return null
        val sizeLength = vintLength(b[j])
        if (sizeLength !in 1..8) return null
        j += sizeLength
        if (j + 2 > end) return null
        if (b[j] == 0xBF.toByte() && b[j + 1] == 0x84.toByte()) j += 6
        if (j + 2 > end || b[j] != 0xE7.toByte()) return null
        j++
        if (vintLength(b[j]) != 1) return null
        val count = b[j].toInt() and 0x7F
        j++
        if (count !in 1..8 || j + count > end) return null
        var raw = 0L
        repeat(count) { raw = (raw shl 8) or (b[j + it].toLong() and 0xFF) }
        val timeUs = raw * timescaleNs / 1000
        return timeUs.takeIf { it >= 0 && it <= fileDurationUs + 600_000_000L }
    }

    /**
     * Reads the TimecodeScale (units of cluster timestamps; most files use 1 ms), the video frame rate and its Dolby
     * Vision record ([MkvHeader]) from the start of the file; and from the same bytes, whether the track list is kept
     * after the clusters, to be read first ([Detour]). One line in the log for each file, of what the bytes say.
     */
    private fun readHeader(input: ExtractorInput) {
        val size = if (length > 0) minOf(65_536L, length).toInt() else 65_536
        val header = ByteArray(size)
        val ok = try {
            input.peekFully(header, 0, size, true)
        } catch (e: EOFException) {
            false
        } finally {
            input.resetPeekPosition()
        }
        if (!ok) return
        val video = runCatching { MkvHeader.video(header, size) }.getOrNull()
        video?.let {
            videoFrameRate = it.frameRate
            dvSignal = it.dvSignal
        }
        val layout = runCatching { MkvHeader.layout(header, size) }.getOrNull()
        val late = layout?.lateTracks(size, length) ?: -1L
        if (layout != null && late > 0) detour = Detour(layout.segmentStart, late)
        if (logs) Log.i(TAG, "mkv head: " + MkvHeader.describe(layout, video, length) + if (late > 0) ", Tracks read first" else "")
        for (i in 0 until size - 5) {
            if (header[i] == 0x2A.toByte() && header[i + 1] == 0xD7.toByte() && header[i + 2] == 0xB1.toByte()) {
                if (vintLength(header[i + 3]) != 1) continue
                val count = header[i + 3].toInt() and 0x7F
                if (count !in 1..8 || i + 4 + count > size) continue
                var value = 0L
                repeat(count) { value = (value shl 8) or (header[i + 4 + it].toLong() and 0xFF) }
                if (value > 0) timescaleNs = value
                return
            }
        }
    }

    private fun vintLength(first: Byte): Int = MkvHeader.vintLength(first)

    companion object {
        private const val TAG = "FirePlayerMkv"
        /** The parts of reading a track list kept after the clusters ([Detour]): the file's first bytes, then the list. */
        private const val HEAD = 0
        private const val TRACKS = 1
        /** A track list bigger than this isn't read first: a few kB is usual. */
        private const val MAX_TRACKS = 1L shl 20
        /** Damaged stretches got past in one file before it counts as damaged throughout. */
        private const val MAX_RECOVERIES = 20
        /** Places looked at after a damaged stretch: further on (each step twice as far, from 2 s), then halving back. */
        private const val MAX_PROBES = 32
        private const val FIRST_STEP_US = 2_000_000L
        private val ID0 = 0x1F.toByte()
        private val ID1 = 0x43.toByte()
        private val ID2 = 0xB6.toByte()
        private val ID3 = 0x75.toByte()
        private const val CHUNK = 256 * 1024
        private const val TAIL = 32
        private const val AFTER_FIRST = 8L * 1024 * 1024
        private const val MAX_SCAN = 32L * 1024 * 1024
        private const val MIN_RANGE = 512L * 1024
        private const val MAX_STEPS = 12
        /** Landing up to this far before the target is fine: the player decodes forward to it. */
        private const val TOLERANCE_US = 2_500_000L
        private const val BACKOFF_US = 1_500_000L
    }
}

/**
 * What the MKV reader takes from the start of a file (Segment › Tracks › TrackEntry), without reading anything more:
 * the video track's frame rate (DefaultDuration) and, for Dolby Vision, its configuration record's base signal
 * (BlockAdditionMapping with a dvcC, dvvC or dvwC BlockAddIDType: [DvBaseLayer.signalOf]). Pure.
 */
internal object MkvHeader {

    /** [frameRate] 0 when not stated; [dvSignal] null without a record. */
    class Video(val frameRate: Float, val dvSignal: Int?)

    /**
     * The video track in the first [size] bytes of [h]: the frame rate of the first one stating it (as before), the
     * record of the first one; null when the bytes hold no video track.
     */
    fun video(h: ByteArray, size: Int): Video? {
        val pick = VideoPick()
        forEachElement(h, 0, size) { id, start, end ->
            if (id == ID_SEGMENT) forEachElement(h, start, end) { id2, start2, end2 ->
                if (id2 == ID_TRACKS) forEachElement(h, start2, end2) { id3, start3, end3 ->
                    if (id3 == ID_TRACK_ENTRY) pick.entry(h, start3, end3)
                }
            }
        }
        return pick.video()
    }

    /** The TrackEntries' video track as they come: the frame rate of the first one stating it, the record of the first. */
    private class VideoPick {
        private var fps = 0f
        private var signal: Int? = null
        private var seen = false

        fun entry(h: ByteArray, start: Int, end: Int) {
            if (fps != 0f) return
            trackEntry(h, start, end)?.let { v ->
                fps = v.frameRate
                if (!seen) signal = v.dvSignal
                seen = true
            }
        }

        fun video(): Video? = if (seen) Video(fps, signal) else null
    }

    /**
     * Where things are in an MKV, from its first bytes ([layout]). Positions count from the start of the file; -1 when
     * the SeekHead doesn't list one.
     */
    class Layout(
        /** Where the Segment's contents begin (its first child). */
        val segmentStart: Long,
        /** Whether the track list (Tracks) starts within the bytes read. */
        val tracksInHead: Boolean,
        /** Whether a cluster starts within the bytes read before any track list. */
        val clusterFirst: Boolean,
        val info: Long,
        val tracks: Long,
        val cues: Long,
        val tags: Long,
    ) {
        /**
         * Where the track list is, when it's kept after the clusters and so wants reading first ([SeekableMkvExtractor]);
         * -1 otherwise. That's when it isn't in the first [headSize] bytes but the SeekHead lists it further on, inside
         * the file's [length], and either a cluster came first in those bytes or it's in the second half of the file
         * (where only what follows the clusters can be). A file whose track list merely sits beyond the first bytes but
         * before the clusters is read as it always was.
         */
        fun lateTracks(headSize: Int, length: Long): Long = when {
            length <= 0 || tracksInHead || tracks < 0 -> -1
            tracks < headSize || tracks <= segmentStart || tracks > length - MIN_TRACKS -> -1
            !clusterFirst && tracks <= length / 2 -> -1
            else -> tracks
        }
    }

    /**
     * The Segment's start, what its SeekHead lists, and whether a cluster or the track list comes first, in the first
     * [size] bytes of [h] (from the file's start); null when they hold no Segment.
     */
    fun layout(h: ByteArray, size: Int): Layout? {
        var segmentStart = -1L
        var tracksInHead = false
        var clusterFirst = false
        val listed = longArrayOf(-1, -1, -1, -1)
        forEachElement(h, 0, size) { id, start, end ->
            if (id != ID_SEGMENT || segmentStart >= 0) return@forEachElement
            segmentStart = start.toLong()
            forEachElement(h, start, end) { id2, start2, end2 ->
                when (id2) {
                    ID_SEEK_HEAD -> forEachElement(h, start2, end2) { id3, start3, end3 ->
                        if (id3 == ID_SEEK) seekEntry(h, start3, end3, segmentStart, listed)
                    }
                    ID_TRACKS -> tracksInHead = true
                    ID_CLUSTER -> if (!tracksInHead) clusterFirst = true
                }
            }
        }
        if (segmentStart < 0) return null
        return Layout(segmentStart, tracksInHead, clusterFirst, listed[0], listed[1], listed[2], listed[3])
    }

    /** A SeekHead's entry: where it says the Info, the track list, the index (Cues) or the Tags are; the first of each. */
    private fun seekEntry(h: ByteArray, start: Int, end: Int, segmentStart: Long, listed: LongArray) {
        var id = -1L
        var position = -1L
        forEachElement(h, start, end) { id2, s, e ->
            when (id2) {
                ID_SEEK_ID -> id = uint(h, s, e)
                ID_SEEK_POSITION -> if (e - s in 1..8) position = uint(h, s, e)
            }
        }
        if (position < 0) return
        val k = when (id) {
            ID_INFO -> 0
            ID_TRACKS -> 1
            ID_CUES -> 2
            ID_TAGS -> 3
            else -> return
        }
        if (listed[k] < 0) listed[k] = segmentStart + position
    }

    /**
     * The size of the element at the start of the first [size] bytes of [b], its ID and size included, when it's
     * [id] with a size stated; -1 otherwise.
     */
    fun elementSize(b: ByteArray, size: Int, id: Long): Long {
        if (size < 1) return -1
        val idLength = vintLength(b[0])
        if (idLength !in 1..4 || idLength >= size) return -1
        var found = 0L
        for (k in 0 until idLength) found = (found shl 8) or (b[k].toLong() and 0xFF)
        if (found != id) return -1
        val sizeLength = vintLength(b[idLength])
        if (sizeLength !in 1..8 || idLength + sizeLength > size) return -1
        val data = sizeAt(b, idLength, sizeLength)
        if (data < 0) return -1
        return idLength + sizeLength + data
    }

    /** A track list read on its own ([lateTracks]): its video track, null when it has none. */
    class LateTracks(val video: Video?)

    /**
     * [b] holding a track list (Tracks) whole, from its ID to its end, as read from where a SeekHead says it is: its
     * video track as [video] gives it, when it reads as one whole: every element in it and in each of its TrackEntries
     * lying whole inside, with its size stated, and each TrackEntry having a TrackNumber and a CodecID. Null otherwise,
     * and then it's left to Media3 to read where it is, as ever.
     */
    fun lateTracks(b: ByteArray, size: Int): LateTracks? {
        val pick = VideoPick()
        var entries = 0
        var lists = 0
        val whole = wholeElements(b, 0, size) list@{ id, start, end ->
            if (id != ID_TRACKS || lists++ > 0) return@list false
            wholeElements(b, start, end) entry@{ id2, start2, end2 ->
                // Anything else in it (Void, CRC-32) Media3 passes over.
                if (id2 != ID_TRACK_ENTRY) return@entry true
                var number = false
                var codec = false
                val entryWhole = wholeElements(b, start2, end2) { id3, _, _ ->
                    if (id3 == ID_TRACK_NUMBER) number = true
                    if (id3 == ID_CODEC_ID) codec = true
                    true
                }
                if (!entryWhole || !number || !codec) return@entry false
                entries++
                pick.entry(b, start2, end2)
                true
            }
        }
        return if (whole && entries > 0) LateTracks(pick.video()) else null
    }

    /**
     * The log's words for a file's first bytes: "tracks=absent fps=none dvRecord=none
     * seekhead=Info@4185,Tracks@1083949310,Cues@1083100160,Tags@none len=1083950014". Sizes and places only.
     */
    fun describe(layout: Layout?, video: Video?, length: Long): String {
        fun at(p: Long) = if (p >= 0) p.toString() else "none"
        val seekHead = if (layout == null) "none"
        else "Info@${at(layout.info)},Tracks@${at(layout.tracks)},Cues@${at(layout.cues)},Tags@${at(layout.tags)}"
        return "tracks=${if (layout?.tracksInHead == true) "in-header" else "absent"} fps=${fpsText(video?.frameRate ?: 0f)} " +
            "dvRecord=${video?.dvSignal ?: "none"} seekhead=$seekHead len=$length"
    }

    /** "23.976", or "none" for 0. */
    fun fpsText(fps: Float): String = if (fps > 0f) String.format(Locale.US, "%.3f", fps) else "none"

    private fun trackEntry(h: ByteArray, start: Int, end: Int): Video? {
        var video = false
        var frameNs = 0L
        var signal: Int? = null
        forEachElement(h, start, end) { id, s, e ->
            when (id) {
                ID_TRACK_TYPE -> video = uint(h, s, e) == 1L
                ID_DEFAULT_DURATION -> frameNs = uint(h, s, e)
                ID_BLOCK_ADDITION_MAPPING -> if (signal == null) signal = dvRecord(h, s, e)
            }
        }
        if (!video) return null
        // 8 to 250 fps; anything else is a bad value.
        return Video(if (frameNs in 4_000_000L..125_000_000L) 1e9f / frameNs else 0f, signal)
    }

    /** A BlockAdditionMapping's Dolby Vision record's signal; null when it holds another kind, or none. */
    private fun dvRecord(h: ByteArray, start: Int, end: Int): Int? {
        var type = 0L
        var extra: ByteArray? = null
        forEachElement(h, start, end) { id, s, e ->
            when (id) {
                ID_BLOCK_ADD_ID_TYPE -> type = uint(h, s, e)
                ID_BLOCK_ADD_ID_EXTRA_DATA -> extra = h.copyOfRange(s, e)
            }
        }
        if (type != DVCC && type != DVVC && type != DVWC) return null
        return extra?.let { DvBaseLayer.signalOf(it) }
    }

    private fun uint(b: ByteArray, start: Int, end: Int): Long {
        if (end - start !in 1..8) return 0L
        var value = 0L
        for (i in start until end) value = (value shl 8) or (b[i].toLong() and 0xFF)
        return value
    }

    /** Calls [block] with the ID and data range of each EBML element between [from] and [to]. */
    private fun forEachElement(b: ByteArray, from: Int, to: Int, block: (id: Long, start: Int, end: Int) -> Unit) {
        var i = from
        while (i < to) {
            val idLength = vintLength(b[i])
            if (idLength !in 1..4 || i + idLength >= to) return
            var id = 0L
            for (k in 0 until idLength) id = (id shl 8) or (b[i + k].toLong() and 0xFF)
            i += idLength
            val sizeLength = vintLength(b[i])
            if (sizeLength !in 1..8 || i + sizeLength > to) return
            var size = (b[i].toLong() and 0xFF) and (0xFF shr sizeLength).toLong()
            for (k in 1 until sizeLength) size = (size shl 8) or (b[i + k].toLong() and 0xFF)
            i += sizeLength
            // Unknown sizes, and elements cut off by the end of what was read, run to [to].
            val end = if (size > to - i) to else i + size.toInt()
            block(id, i, end)
            i = end
        }
    }

    /**
     * As [forEachElement], but every element must lie whole between [from] and [to], with its size stated: false when
     * one doesn't, or when [block] says so.
     */
    private fun wholeElements(b: ByteArray, from: Int, to: Int, block: (id: Long, start: Int, end: Int) -> Boolean): Boolean {
        var i = from
        while (i < to) {
            val idLength = vintLength(b[i])
            if (idLength !in 1..4 || i + idLength >= to) return false
            var id = 0L
            for (k in 0 until idLength) id = (id shl 8) or (b[i + k].toLong() and 0xFF)
            i += idLength
            val sizeLength = vintLength(b[i])
            if (sizeLength !in 1..8 || i + sizeLength > to) return false
            val size = sizeAt(b, i, sizeLength)
            i += sizeLength
            if (size < 0 || size > to - i) return false
            if (!block(id, i, i + size.toInt())) return false
            i += size.toInt()
        }
        return true
    }

    /** The element size of [length] bytes at [at]; -1 for "unknown" (every bit of it set). */
    private fun sizeAt(b: ByteArray, at: Int, length: Int): Long {
        var size = (b[at].toLong() and 0xFF) and (0xFF shr length).toLong()
        for (k in 1 until length) size = (size shl 8) or (b[at + k].toLong() and 0xFF)
        return if (size == (1L shl (7 * length)) - 1) -1 else size
    }

    fun vintLength(first: Byte): Int {
        val v = first.toInt() and 0xFF
        return if (v == 0) 0 else Integer.numberOfLeadingZeros(v) - 23
    }

    private const val ID_SEGMENT = 0x18538067L
    const val ID_TRACKS = 0x1654AE6BL
    private const val ID_SEEK_HEAD = 0x114D9B74L
    private const val ID_SEEK = 0x4DBBL
    private const val ID_SEEK_ID = 0x53ABL
    private const val ID_SEEK_POSITION = 0x53ACL
    private const val ID_INFO = 0x1549A966L
    private const val ID_CUES = 0x1C53BB6BL
    private const val ID_TAGS = 0x1254C367L
    private const val ID_CLUSTER = 0x1F43B675L
    private const val ID_TRACK_NUMBER = 0xD7L
    private const val ID_CODEC_ID = 0x86L
    /** The least a track list with one track in it can be. */
    private const val MIN_TRACKS = 8L
    private const val ID_TRACK_ENTRY = 0xAEL
    private const val ID_TRACK_TYPE = 0x83L
    private const val ID_DEFAULT_DURATION = 0x23E383L
    private const val ID_BLOCK_ADDITION_MAPPING = 0x41E4L
    private const val ID_BLOCK_ADD_ID_TYPE = 0x41E7L
    private const val ID_BLOCK_ADD_ID_EXTRA_DATA = 0x41EDL
    private const val DVCC = 0x64766343L
    private const val DVVC = 0x64767643L
    private const val DVWC = 0x64767743L
}

/**
 * [base], with reads handed out from pieces of [CHUNK] bytes peeked at once: what's handed out is only taken from
 * [base] (from what it already holds) a piece at a time, and at [finish], so [base]'s position is always right to go
 * on from. Anything else asked of it (a peek, a retry position) goes straight to [base] from then on.
 */
@OptIn(UnstableApi::class)
internal class BatchedInput(private val base: ExtractorInput) : ExtractorInput {
    private val chunk = ByteArray(CHUNK)
    private var chunkLength = 0
    // Handed out of the chunk, not yet taken from base.
    private var taken = 0
    private var direct = false
    private var ended = false

    /** Takes what was handed out from [base]; after this, everything goes straight to it. */
    fun finish() {
        flush()
        direct = true
    }

    private fun flush() {
        if (direct) return
        if (taken > 0) base.skipFully(taken)
        taken = 0
        chunkLength = 0
        base.resetPeekPosition()
    }

    /** Bytes ready to hand out, reading the next piece if none are; false at the end of the file. */
    private fun ready(): Boolean {
        if (taken < chunkLength) return true
        if (ended) return false
        flush()
        val n = base.peek(chunk, 0, CHUNK)
        if (n == C.RESULT_END_OF_INPUT) {
            ended = true
            return false
        }
        chunkLength = n
        return true
    }

    private fun goDirect() {
        if (!direct) finish()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (direct) return base.read(buffer, offset, length)
        if (length == 0) return 0
        if (!ready()) return C.RESULT_END_OF_INPUT
        val n = minOf(length, chunkLength - taken)
        System.arraycopy(chunk, taken, buffer, offset, n)
        taken += n
        return n
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        if (direct) return base.readFully(target, offset, length, allowEndOfInput)
        if (length == 0) return true
        if (taken == chunkLength && !ready()) {
            if (allowEndOfInput) return false
            throw EOFException()
        }
        if (length <= chunkLength - taken) {
            System.arraycopy(chunk, taken, target, offset, length)
            taken += length
            return true
        }
        // More than the piece holds: all of it from base in one go, which either reads it all or leaves its position as
        // it was. Taken in parts, a failed read (a dropped connection) left base part of the way into it, and the read
        // tried again from there came out of step.
        return whole { base.readFully(target, offset, length, allowEndOfInput) }
    }

    /** [read] of a whole request from [base], after what was handed out is taken from it; pieces start afresh after. */
    private inline fun whole(read: () -> Boolean): Boolean {
        flush()
        return read()
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int) {
        readFully(target, offset, length, false)
    }

    override fun skip(length: Int): Int {
        if (direct) return base.skip(length)
        if (length == 0) return 0
        if (!ready()) return C.RESULT_END_OF_INPUT
        val n = minOf(length, chunkLength - taken)
        taken += n
        return n
    }

    override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean {
        if (direct) return base.skipFully(length, allowEndOfInput)
        if (length == 0) return true
        if (taken == chunkLength && length <= CHUNK && !ready()) {
            if (allowEndOfInput) return false
            throw EOFException()
        }
        if (length <= chunkLength - taken) {
            taken += length
            return true
        }
        // As in readFully: a skip past the piece is base's, in one go
        return whole { base.skipFully(length, allowEndOfInput) }
    }

    override fun skipFully(length: Int) {
        skipFully(length, false)
    }

    override fun peek(target: ByteArray, offset: Int, length: Int): Int {
        goDirect()
        return base.peek(target, offset, length)
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean {
        goDirect()
        return base.peekFully(target, offset, length, allowEndOfInput)
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int) {
        goDirect()
        base.peekFully(target, offset, length)
    }

    override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean {
        goDirect()
        return base.advancePeekPosition(length, allowEndOfInput)
    }

    override fun advancePeekPosition(length: Int) {
        goDirect()
        base.advancePeekPosition(length)
    }

    override fun resetPeekPosition() {
        if (direct) base.resetPeekPosition()
    }

    override fun getPeekPosition(): Long = if (direct) base.peekPosition else position

    override fun getPosition(): Long = if (direct) base.position else base.position + taken

    override fun getLength(): Long = base.length

    override fun <E : Throwable> setRetryPosition(position: Long, e: E) {
        goDirect()
        base.setRetryPosition(position, e)
    }

    companion object {
        const val CHUNK = 16 * 1024
    }
}

/**
 * [base] as if the file ended at [end]: reads, skips and peeks stop there. One that would run on past it is refused
 * whole ([overran]), [base] left where it was, so the reader can be given the rest of the file and go on from there.
 * For reading a track list kept after the clusters first ([SeekableMkvExtractor.Detour]): a few hundred calls a film
 * at most, so nothing is batched.
 */
@OptIn(UnstableApi::class)
internal class BoundedInput(private val base: ExtractorInput, private val end: Long) : ExtractorInput {
    /** Set when a read, skip or peek was refused for running on past [end]. */
    var overran = false
        private set

    private fun left(): Long = end - base.position

    private fun peekLeft(): Long = end - base.peekPosition

    /** Whether [length] bytes fit in [left]; false at the end (when that's allowed); refused otherwise. */
    private fun fits(length: Int, left: Long, allowEndOfInput: Boolean): Boolean {
        if (length <= left) return true
        if (left <= 0 && allowEndOfInput) return false
        overran = true
        throw EOFException()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val left = left()
        if (left <= 0) return C.RESULT_END_OF_INPUT
        return base.read(buffer, offset, minOf(length.toLong(), left).toInt())
    }

    override fun readFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean =
        fits(length, left(), allowEndOfInput) && base.readFully(target, offset, length, allowEndOfInput)

    override fun readFully(target: ByteArray, offset: Int, length: Int) {
        readFully(target, offset, length, false)
    }

    override fun skip(length: Int): Int {
        if (length == 0) return 0
        val left = left()
        if (left <= 0) return C.RESULT_END_OF_INPUT
        return base.skip(minOf(length.toLong(), left).toInt())
    }

    override fun skipFully(length: Int, allowEndOfInput: Boolean): Boolean =
        fits(length, left(), allowEndOfInput) && base.skipFully(length, allowEndOfInput)

    override fun skipFully(length: Int) {
        skipFully(length, false)
    }

    override fun peek(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val left = peekLeft()
        if (left <= 0) return C.RESULT_END_OF_INPUT
        return base.peek(target, offset, minOf(length.toLong(), left).toInt())
    }

    override fun peekFully(target: ByteArray, offset: Int, length: Int, allowEndOfInput: Boolean): Boolean =
        fits(length, peekLeft(), allowEndOfInput) && base.peekFully(target, offset, length, allowEndOfInput)

    override fun peekFully(target: ByteArray, offset: Int, length: Int) {
        peekFully(target, offset, length, false)
    }

    override fun advancePeekPosition(length: Int, allowEndOfInput: Boolean): Boolean =
        fits(length, peekLeft(), allowEndOfInput) && base.advancePeekPosition(length, allowEndOfInput)

    override fun advancePeekPosition(length: Int) {
        advancePeekPosition(length, false)
    }

    override fun resetPeekPosition() = base.resetPeekPosition()

    override fun getPeekPosition(): Long = base.peekPosition

    override fun getPosition(): Long = base.position

    override fun getLength(): Long = base.length

    override fun <E : Throwable> setRetryPosition(position: Long, e: E) = base.setRetryPosition(position, e)
}
