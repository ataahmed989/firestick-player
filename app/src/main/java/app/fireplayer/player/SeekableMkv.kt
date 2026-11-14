package app.fireplayer.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
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
 */
@OptIn(UnstableApi::class)
class SeekableMkvExtractorsFactory(
    private val base: ExtractorsFactory,
    /** Told of each damaged stretch gone past: the film's link, and the times it plays to and goes on from. */
    private val onSkip: ((uri: String?, fromUs: Long, toUs: Long) -> Unit)? = null,
) : ExtractorsFactory by base {

    override fun createExtractors(): Array<Extractor> = wrap(base.createExtractors(), null)

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(base.createExtractors(uri, responseHeaders), uri.toString())

    private fun wrap(extractors: Array<Extractor>, uri: String?): Array<Extractor> =
        Array(extractors.size) { i ->
            val e = extractors[i]
            if (e is MatroskaExtractor) SeekableMkvExtractor(e, uri, onSkip) else e
        }
}

@OptIn(UnstableApi::class)
private class SeekableMkvExtractor(
    private val inner: Extractor,
    private val uri: String?,
    private val onSkip: ((uri: String?, fromUs: Long, toUs: Long) -> Unit)?,
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

    /** The file's own index, when it has one: where a damaged stretch is got past. */
    private var index: SeekMap? = null
    private var recovery: Recovery? = null
    // Damaged stretches got past in this file, so one that's damaged throughout ends in an error.
    private var recovered = 0
    // The latest time of film read since the last jump: where playback runs out before a damaged stretch. A jump
    // straight into one has read nothing, and playback goes on from where the jump was to ([jumpedToUs]).
    @Volatile private var lastSampleUs = C.TIME_UNSET
    private var jumpedToUs = 0L

    override fun init(output: ExtractorOutput) {
        inner.init(object : ExtractorOutput by output {
            override fun track(id: Int, type: Int): TrackOutput {
                val track = output.track(id, type)
                if (type != C.TRACK_TYPE_VIDEO && type != C.TRACK_TYPE_AUDIO) return track
                return object : TrackOutput by track {
                    override fun format(format: Format) {
                        val fps = videoFrameRate
                        val missing = type == C.TRACK_TYPE_VIDEO && format.frameRate <= 0f && fps > 0f
                        track.format(if (missing) format.buildUpon().setFrameRate(fps).build() else format)
                    }

                    // A Java default method, which `by` doesn't pass on by itself.
                    override fun durationUs(durationUs: Long) = track.durationUs(durationUs)

                    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
                        if (timeUs > lastSampleUs || lastSampleUs == C.TIME_UNSET) lastSampleUs = timeUs
                        track.sampleMetadata(timeUs, flags, size, offset, cryptoData)
                    }
                }
            }

            override fun seekMap(seekMap: SeekMap) {
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
            headerScanned = true
            readHeader(input)
        }
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
        return try {
            inner.read(input, seekPosition)
        } catch (e: IllegalStateException) {
            damaged(input, seekPosition, e)
        } catch (e: ParserException) {
            // Only bytes that don't make sense: a feature Media3 doesn't support stays that error.
            if (!e.contentIsMalformed) throw e
            damaged(input, seekPosition, e)
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

    override fun seek(position: Long, timeUs: Long) {
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
     * Reads the TimecodeScale (units of cluster timestamps; most files use 1 ms) and the video frame
     * rate from the start of the file.
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
        runCatching { readFrameRate(header, size) }
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

    private fun readFrameRate(h: ByteArray, size: Int) {
        forEachElement(h, 0, size) { id, start, end ->
            if (id == ID_SEGMENT) forEachElement(h, start, end) { id2, start2, end2 ->
                if (id2 == ID_TRACKS) forEachElement(h, start2, end2) { id3, start3, end3 ->
                    if (id3 == ID_TRACK_ENTRY && videoFrameRate == 0f) readTrackEntry(h, start3, end3)
                }
            }
        }
    }

    private fun readTrackEntry(h: ByteArray, start: Int, end: Int) {
        var video = false
        var frameNs = 0L
        forEachElement(h, start, end) { id, s, e ->
            when (id) {
                ID_TRACK_TYPE -> video = uint(h, s, e) == 1L
                ID_DEFAULT_DURATION -> frameNs = uint(h, s, e)
            }
        }
        // 8 to 250 fps; anything else is a bad value.
        if (video && frameNs in 4_000_000L..125_000_000L) videoFrameRate = 1e9f / frameNs
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

    private fun vintLength(first: Byte): Int {
        val v = first.toInt() and 0xFF
        return if (v == 0) 0 else Integer.numberOfLeadingZeros(v) - 23
    }

    companion object {
        private const val TAG = "FirePlayerMkv"
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
        private const val ID_SEGMENT = 0x18538067L
        private const val ID_TRACKS = 0x1654AE6BL
        private const val ID_TRACK_ENTRY = 0xAEL
        private const val ID_TRACK_TYPE = 0x83L
        private const val ID_DEFAULT_DURATION = 0x23E383L
    }
}
