package app.fireplayer.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ChunkIndex
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint

/**
 * An MKV's index (Cues) read for where the picture can start: the points it lists for the video track, which are its
 * keyframes.
 *
 * Media3's reader (1.8.0) takes every point the index lists as a place a jump can land, whatever track it's for. But
 * muxers list each subtitle line too (mkvmerge and FFmpeg both do), and a line's point is the cluster the line is in:
 * the picture can't start there, only at the next keyframe, which in a heavy film is seconds on. A jump that landed on
 * such a point started the sound at once and showed the picture from seconds later, held still until the sound got to
 * it (seen on the TV: "Picture landed 3417 ms ahead of the sound after a jump"). Everything loaded in between was for
 * nothing, and the film had to jump again.
 *
 * The index's bytes are looked at as they pass on their way to Media3's reader ([BatchedInput]): no request more, no
 * byte read twice, nothing kept but two numbers a keyframe. From them [build] gives a [KeyframeSeekMap], which the
 * player jumps by instead of Media3's. Whenever the index doesn't read as expected, isn't seen whole, or doesn't
 * agree with what Media3 read from the same bytes, there is none and jumps go by Media3's own as they always did.
 *
 * Fed on the loading thread only.
 */
@OptIn(UnstableApi::class)
internal class CueReader(
    /** Where the index starts in the file. */
    val cuesAt: Long,
    /** The video track's number, as the index names it (CueTrack). */
    private val videoTrack: Long,
    /** The file's time unit in ns (TimestampScale). */
    private val timescaleNs: Long,
) {
    /** Why the index was given up on; null while it reads. */
    var failure: String? = null
        private set
    /** The whole index has been through. */
    var done = false
        private set
    /** The index's points, whatever track they're for. */
    var points = 0
        private set
    /** Its points for the video track: [timesUs] and [clusters] (counted from where the Segment's contents begin). */
    var count = 0
        private set
    private var timesUs = LongArray(1024)
    private var clusters = LongArray(1024)
    // The first and last of them that are a point's only place, which is what Media3 lists too: -1 with none.
    private var firstAlone = -1
    private var lastAlone = -1

    private var started = false
    // Where the next byte wanted is in the file.
    private var next = cuesAt

    private var phase = ID
    private var id = 0L
    private var idLength = 0
    private var size = 0L
    private var sizeLength = 0
    private var got = 0
    // Bytes left of the value being read, or passed over.
    private var left = 0L
    private var value = 0L

    // How deep: 0 before the index's own start, 1 in it, 2 in a point, 3 in a point's places for a track.
    private var level = 0
    private var cuesEnd = 0L
    private var pointEnd = 0L
    private var placeEnd = 0L
    private var time = -1L
    private var places = 0
    private var videoCluster = -1L
    private var track = -1L
    private var cluster = -1L

    /** Nothing more is wanted: read whole, or given up on. */
    val over: Boolean get() = done || failure != null

    /**
     * Whether a read of the file starting at [position] is to be shown here: one that starts in what's still wanted of
     * the index, or up to [NEAR] before it (an index kept before the clusters is come to from the file's first bytes).
     */
    fun near(position: Long): Boolean = !over && position <= next && cuesAt - position <= NEAR

    /** Starts over, for a file read from its start again. */
    fun restart() {
        if (over) return
        started = false
        next = cuesAt
        phase = ID
        got = 0
        level = 0
        points = 0
        count = 0
        firstAlone = -1
        lastAlone = -1
    }

    /**
     * [length] bytes of the file from [position], at [offset] in [b]. Bytes before the index, after it and seen before
     * are passed over; a stretch never seen is fine only inside something that isn't wanted ([skipped]).
     */
    fun feed(position: Long, b: ByteArray, offset: Int, length: Int) {
        if (over || length <= 0) return
        var from = offset
        var n = length
        var at = position
        if (!started) {
            if (at + n <= cuesAt) return
            // Reading that begins inside it has missed its start.
            if (at > cuesAt) return fail("the index was met at $at, not at its start")
            started = true
        }
        if (at > next) {
            if (!skipped(at - next)) return
        } else if (at < next) {
            val seen = next - at
            if (seen >= n) return
            from += seen.toInt()
            n -= seen.toInt()
            at = next
        }
        val end = from + n
        var i = from
        while (i < end) {
            if (phase == SKIP) {
                val pass = minOf(left, (end - i).toLong())
                i += pass.toInt()
                next += pass
                left -= pass
                if (left == 0L && !closed()) return
                continue
            }
            val v = b[i].toInt() and 0xFF
            i++
            next++
            when (phase) {
                ID -> {
                    if (got == 0) {
                        idLength = MkvHeader.vintLength(v.toByte())
                        if (idLength !in 1..4) return fail("a bad ID at ${next - 1}")
                        id = 0
                    }
                    id = (id shl 8) or v.toLong()
                    if (++got == idLength) {
                        phase = SIZE
                        got = 0
                    }
                }
                SIZE -> {
                    if (got == 0) {
                        sizeLength = MkvHeader.vintLength(v.toByte())
                        if (sizeLength !in 1..8) return fail("a bad size at ${next - 1}")
                        size = (v and (0xFF shr sizeLength)).toLong()
                    } else {
                        size = (size shl 8) or v.toLong()
                    }
                    if (++got == sizeLength) {
                        got = 0
                        if (size == (1L shl (7 * sizeLength)) - 1) return fail("no size stated at ${next - 1}")
                        if (!opened()) return
                    }
                }
                else -> {
                    value = (value shl 8) or v.toLong()
                    if (--left == 0L) {
                        took()
                        if (!closed()) return
                    }
                }
            }
        }
    }

    /**
     * [length] bytes of the file from [position] went by without being shown (the reader skipped them): fine where they
     * were seen before, or lie inside something that isn't wanted.
     */
    fun passed(position: Long, length: Int) {
        if (over) return
        val end = position + length
        if (!started) {
            if (end > cuesAt) fail("the index's start was passed over")
            return
        }
        if (end > next) skipped(end - next)
    }

    /** [bytes] went by unseen: only fine inside something passed over anyway. */
    private fun skipped(bytes: Long): Boolean {
        if (phase != SKIP || bytes > left) {
            fail("${bytes} bytes of it at $next weren't seen")
            return false
        }
        left -= bytes
        next += bytes
        return left > 0L || closed()
    }

    /** An element's ID and size are in ([id], [size]); [next] is where its contents begin. False when given up. */
    private fun opened(): Boolean {
        val end = next + size
        when (level) {
            0 -> {
                if (id != ID_CUES) return failed("no index at $cuesAt")
                cuesEnd = end
                level = 1
                phase = ID
                return closed()
            }
            1 -> {
                if (end > cuesEnd) return failed("a point runs on past the index")
                if (id == ID_CUE_POINT) {
                    pointEnd = end
                    level = 2
                    time = -1
                    places = 0
                    videoCluster = -1
                    phase = ID
                    return closed()
                }
            }
            2 -> {
                if (end > pointEnd) return failed("a point's contents run on past it")
                if (id == ID_CUE_TIME) return number()
                if (id == ID_CUE_TRACK_POSITIONS) {
                    placeEnd = end
                    level = 3
                    track = -1
                    cluster = -1
                    phase = ID
                    return closed()
                }
            }
            else -> {
                if (end > placeEnd) return failed("a point's place runs on past it")
                if (id == ID_CUE_TRACK || id == ID_CUE_CLUSTER_POSITION) return number()
            }
        }
        // Anything else (a Void, a CRC-32, the place inside the cluster, the block's number) is passed over.
        left = size
        phase = SKIP
        return left > 0L || closed()
    }

    /** The element just opened is a number to take. */
    private fun number(): Boolean {
        if (size > 8) return failed("a number of $size bytes")
        value = 0
        left = size
        phase = VALUE
        if (left > 0L) return true
        took()
        return closed()
    }

    private fun took() {
        when (id) {
            ID_CUE_TIME -> time = value
            ID_CUE_TRACK -> track = value
            ID_CUE_CLUSTER_POSITION -> cluster = value
        }
    }

    /** An element has ended at [next]: whatever ends there with it is closed. False when given up. */
    private fun closed(): Boolean {
        phase = ID
        got = 0
        if (level == 3) {
            if (next > placeEnd) return failed("a point's place ends early")
            if (next < placeEnd) return true
            if (cluster >= 0) {
                places++
                if (track == videoTrack && videoCluster < 0) videoCluster = cluster
            }
            level = 2
        }
        if (level == 2) {
            if (next > pointEnd) return failed("a point ends early")
            if (next < pointEnd) return true
            if (time < 0 || places == 0) return failed("a point without its time or its place")
            points++
            if (videoCluster >= 0 && !add(time, videoCluster, alone = places == 1)) return false
            level = 1
        }
        if (level == 1) {
            if (next > cuesEnd) return failed("the index ends early")
            if (next == cuesEnd) done = true
        }
        return !done
    }

    private fun add(time: Long, cluster: Long, alone: Boolean): Boolean {
        val us = scaled(time)
        if (us < 0) return failed("a time out of range")
        if (count > 0) {
            val last = timesUs[count - 1]
            if (us < last) return failed("its points aren't in order")
            // Listed twice: the first stands.
            if (us == last) return true
        }
        if (count == timesUs.size) {
            if (count >= MAX_POINTS) return failed("more than $MAX_POINTS keyframes")
            timesUs = timesUs.copyOf(count * 2)
            clusters = clusters.copyOf(count * 2)
        }
        timesUs[count] = us
        clusters[count] = cluster
        if (alone) {
            if (firstAlone < 0) firstAlone = count
            lastAlone = count
        }
        count++
        return true
    }

    /** A time in the file's unit as µs, as Media3 works it out; -1 when it doesn't fit. */
    private fun scaled(time: Long): Long {
        if (time < 0 || timescaleNs <= 0) return -1
        if (time > Long.MAX_VALUE / timescaleNs) return -1
        return time * timescaleNs / 1000
    }

    private fun failed(why: String): Boolean {
        fail(why)
        return false
    }

    private fun fail(why: String) {
        if (failure == null) failure = why
    }

    /**
     * The jumps' map from what was read, over [inner] (what Media3 read from the same index), for a file of [length]
     * bytes whose Segment's contents begin at [segmentStart]. Null, with [failure] saying why, when the index wasn't
     * read whole, lists no keyframe, or doesn't agree with [inner]: for the first and the last keyframe (of those that
     * are their point's only place, when there are any), [inner] must list the same time at the same place in the file.
     * That holds the time unit and the Segment's start used here to Media3's own.
     */
    fun build(inner: SeekMap, segmentStart: Long, length: Long): KeyframeSeekMap? {
        if (failure != null) return null
        if (!done) return failedNull("it wasn't seen whole")
        if (segmentStart < 0) return failedNull("the Segment's start isn't known")
        val durationUs = inner.durationUs
        // As Media3 does: points past the film's length are left out.
        var n = count
        while (n > 0 && timesUs[n - 1] > durationUs) n--
        if (n == 0) return failedNull("it lists no keyframe of track $videoTrack")
        val positions = LongArray(n)
        for (i in 0 until n) {
            val p = segmentStart + clusters[i]
            if (p < segmentStart || (length > 0 && p >= length)) return failedNull("a keyframe outside the file")
            positions[i] = p
        }
        // Those that are their point's only place when there are any: Media3 lists a point's first place alone.
        val a = if (firstAlone in 0 until n) firstAlone else 0
        val b = if (lastAlone in 0 until n) lastAlone else n - 1
        // Two, apart: one at the film's start alone would agree whatever the time unit.
        if (b == a) return failedNull("too few keyframes to check against Media3's")
        if (!listed(inner, timesUs[a], positions[a]) || !listed(inner, timesUs[b], positions[b])) {
            return failedNull("it doesn't agree with Media3's")
        }
        return KeyframeSeekMap(inner, if (n == timesUs.size) timesUs else timesUs.copyOf(n), positions, points)
    }

    private fun failedNull(why: String): KeyframeSeekMap? {
        fail(why)
        return null
    }

    /** Whether [inner] lists a point at [timeUs] in the cluster at [position]. */
    private fun listed(inner: SeekMap, timeUs: Long, position: Long): Boolean {
        if (inner is ChunkIndex) {
            // Points for other tracks at the same time come before or after it: any of them will do.
            var i = inner.getChunkIndex(timeUs)
            while (i < inner.length && inner.timesUs[i] == timeUs) {
                if (inner.offsets[i] == position) return true
                i++
            }
            return false
        }
        val p = inner.getSeekPoints(timeUs).first
        return p.timeUs == timeUs && p.position == position
    }

    private companion object {
        const val ID = 0
        const val SIZE = 1
        const val VALUE = 2
        const val SKIP = 3
        const val ID_CUES = 0x1C53BB6BL
        const val ID_CUE_POINT = 0xBBL
        const val ID_CUE_TIME = 0xB3L
        const val ID_CUE_TRACK_POSITIONS = 0xB7L
        const val ID_CUE_TRACK = 0xF7L
        const val ID_CUE_CLUSTER_POSITION = 0xF1L
        /** An index this close after where a read starts may be come to by that read. */
        const val NEAR = 4L * 1024 * 1024
        /** A keyframe every 40 ms of a film ten hours long is fewer. */
        const val MAX_POINTS = 1 shl 20
    }
}

/**
 * Where a jump in an MKV can land with its picture: the video track's keyframes, as the file's index lists them
 * ([CueReader]). The point for a time is the last keyframe at or before it, in the cluster it's in, so the picture
 * starts on the very frame the sound starts at. Before the first keyframe listed, [inner]'s answer stands (what
 * Media3 read from the same index), as it did before.
 */
@OptIn(UnstableApi::class)
internal class KeyframeSeekMap(
    private val inner: SeekMap,
    private val timesUs: LongArray,
    private val positions: LongArray,
    /** How many points the index lists, for every track. */
    val points: Int,
) : SeekMap {

    val count: Int get() = timesUs.size

    override fun isSeekable(): Boolean = true

    override fun getDurationUs(): Long = inner.durationUs

    override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
        val i = floor(timeUs)
        if (i < 0) return inner.getSeekPoints(timeUs)
        val first = SeekPoint(timesUs[i], positions[i])
        if (first.timeUs >= timeUs || i == timesUs.size - 1) return SeekMap.SeekPoints(first)
        return SeekMap.SeekPoints(first, SeekPoint(timesUs[i + 1], positions[i + 1]))
    }

    /** The time of the last keyframe at or before [timeUs]; -1 when [timeUs] is before the first one listed. */
    fun keyframeUs(timeUs: Long): Long {
        val i = floor(timeUs)
        return if (i < 0) -1 else timesUs[i]
    }

    private fun floor(timeUs: Long): Int {
        var lo = 0
        var hi = timesUs.size - 1
        if (timeUs < timesUs[0]) return -1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (timesUs[mid] <= timeUs) lo = mid else hi = mid - 1
        }
        return lo
    }
}

/**
 * The film's last jump that had it load again from somewhere else, as its MKV reader saw it: for the log's line of
 * each jump ([JumpLine]). Set on the loading thread, read on the main one.
 */
internal object MkvJump {
    /** [timeUs]: where the jump was to; [keyUs]: the keyframe the file's index gave for it, -1 without one. */
    class Mark(val timeUs: Long, val keyUs: Long)

    @Volatile var last: Mark? = null
}
