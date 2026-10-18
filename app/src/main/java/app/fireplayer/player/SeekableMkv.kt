package app.fireplayer.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.mkv.MatroskaExtractor
import java.io.EOFException

/**
 * Makes MKV files without a seek index (no Cues, as some recorders and editors such as VideoRedo
 * write them) skippable. Media3 plays such files but refuses to seek in them. This wraps every
 * Matroska extractor; when it reports an unseekable file of known length and duration, a jump is
 * found by reading cluster timestamps at a few points in the file and narrowing in on the target.
 */
@OptIn(UnstableApi::class)
class SeekableMkvExtractorsFactory(private val base: ExtractorsFactory) : ExtractorsFactory by base {

    override fun createExtractors(): Array<Extractor> = wrap(base.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(base.createExtractors(uri, responseHeaders))

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> =
        Array(extractors.size) { i ->
            val e = extractors[i]
            if (e is MatroskaExtractor) SeekableMkvExtractor(e) else e
        }
}

@OptIn(UnstableApi::class)
private class SeekableMkvExtractor(private val inner: Extractor) : Extractor by inner {

    private class Cluster(val position: Long, val timeUs: Long)

    private var input: ExtractorInput? = null
    private var length = C.LENGTH_UNSET.toLong()
    private var timescaleNs = 1_000_000L
    private var headerScanned = false

    /** Set once we've stood in for Media3's "can't seek" answer. */
    private var active = false
    private var durationUs = C.TIME_UNSET
    private var dataStart = 0L
    private var first: Cluster? = null
    private var search: Search? = null
    private val buffer = ByteArray(CHUNK + TAIL)

    override fun init(output: ExtractorOutput) {
        inner.init(object : ExtractorOutput by output {
            override fun seekMap(seekMap: SeekMap) {
                val position = input?.position ?: -1L
                val usable = !seekMap.isSeekable && seekMap.durationUs != C.TIME_UNSET &&
                    length > 0 && position in 1 until length / 2
                if (!usable) {
                    output.seekMap(seekMap)
                    return
                }
                active = true
                durationUs = seekMap.durationUs
                dataStart = position
                output.seekMap(EstimatedSeekMap())
            }
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        this.input = input
        if (input.length != C.LENGTH_UNSET.toLong()) length = input.length
        if (!headerScanned && input.position == 0L) {
            headerScanned = true
            readTimescale(input)
        }
        search?.let { return it.step(input, seekPosition) }
        return inner.read(input, seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) {
        inner.seek(position, timeUs)
        search = if (active && (position != 0L || timeUs != 0L)) Search(timeUs) else null
    }

    /** Points the player at a rough spot; [Search] then finds the exact cluster. */
    private inner class EstimatedSeekMap : SeekMap {
        override fun isSeekable() = true
        override fun getDurationUs() = durationUs
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val fraction = if (durationUs > 0) timeUs.toDouble() / durationUs else 0.0
            val estimate = dataStart + (fraction.coerceIn(0.0, 1.0) * (length - dataStart)).toLong()
            return SeekMap.SeekPoints(SeekPoint(timeUs, estimate.coerceIn(dataStart, length - 1)))
        }
    }

    private inner class Search(private val targetUs: Long) {
        private var loPos = -1L
        private var loTime = 0L
        private var hiPos = length
        private var hiTime = durationUs
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
            val clusters = scan(input, targetUs, stopAfterFirst = false)
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
        return timeUs.takeIf { it >= 0 && it <= durationUs + 600_000_000L }
    }

    /** Finds the TimecodeScale (units of cluster timestamps) in the header; most files use 1 ms. */
    private fun readTimescale(input: ExtractorInput) {
        val size = if (length > 0) minOf(65_536L, length).toInt() else 4_096
        val header = ByteArray(size)
        val ok = try {
            input.peekFully(header, 0, size, true)
        } catch (e: EOFException) {
            false
        } finally {
            input.resetPeekPosition()
        }
        if (!ok) return
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

    private fun vintLength(first: Byte): Int {
        val v = first.toInt() and 0xFF
        return if (v == 0) 0 else Integer.numberOfLeadingZeros(v) - 23
    }

    companion object {
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
