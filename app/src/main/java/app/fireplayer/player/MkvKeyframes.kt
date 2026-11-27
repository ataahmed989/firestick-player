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
 * It's read before the film can start, so it's read lightly. A point lying whole in the piece handed over, written
 * the plain way muxers write them, is taken where it lies ([whole]) and only the bytes that matter are looked at: a
 * place that names another track first, as a subtitle line's does (most of a remux's index), is passed over from
 * there, and a time is only worked out for a keyframe. Anything else (a point across two pieces, a longer ID or size)
 * goes a byte at a time through the same rules, so the answer is the same however the bytes arrive.
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
    // The place being read names another track: the rest of it is passed over, and it counts as a place.
    private var other = false

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
            if (level == 1 && phase == ID && got == 0) {
                i = whole(b, i, end)
                if (i < 0) return
                if (i == end) break
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
     * The points lying whole in [b] from [from] up to [end], each written the plain way (one-byte IDs and sizes, numbers
     * under eight bytes), taken where they lie: the same rules as a byte at a time ([opened], [closed]), without the
     * steps. Stops at the first that isn't plain or isn't all there, which is then read a byte at a time. Returns where
     * in [b] reading goes on from, -1 when nothing more is wanted (the index's end, or given up).
     */
    private fun whole(b: ByteArray, from: Int, end: Int): Int {
        val video = videoTrack
        // No further than the index's own end.
        val limit = if (cuesEnd - next < end - from) from + (cuesEnd - next).toInt() else end
        var p = from
        each@ while (limit - p >= 2) {
            if (b[p] != POINT) break
            // A size in one byte has its top bit set; all ones is no size stated.
            val ps = b[p + 1].toInt()
            if (ps >= -1) break
            val pe = p + 2 + (ps and 0x7F)
            if (pe > limit) break
            var timeAt = -1
            var timeEnd = -1
            var places = 0
            var videoCluster = -1L
            var q = p + 2
            while (q < pe) {
                if (pe - q < 2) break@each
                val id = b[q]
                val size = b[q + 1].toInt()
                if (id >= 0 || size >= -1) break@each
                val e = q + 2 + (size and 0x7F)
                if (e > pe) break@each
                if (id == TIME) {
                    if (e - q > 9) break@each
                    timeAt = q + 2
                    timeEnd = e
                } else if (id == PLACES) {
                    var track = -1L
                    var cluster = -1L
                    var other = false
                    var r = q + 2
                    while (r < e) {
                        if (e - r < 2) break@each
                        val placeId = b[r]
                        val placeSize = b[r + 1].toInt()
                        if (placeId >= 0 || placeSize >= -1) break@each
                        val re = r + 2 + (placeSize and 0x7F)
                        if (re > e) break@each
                        if (placeId == TRACK || placeId == CLUSTER) {
                            if (re - r > 9) break@each
                            var v = 0L
                            var k = r + 2
                            while (k < re) {
                                v = (v shl 8) or (b[k].toLong() and 0xFF)
                                k++
                            }
                            if (placeId == CLUSTER) {
                                cluster = v
                            } else {
                                track = v
                                if (v != video) {
                                    other = true
                                    break
                                }
                            }
                        }
                        r = re
                    }
                    if (other) {
                        places++
                    } else if (cluster >= 0) {
                        places++
                        if (track == video && videoCluster < 0) videoCluster = cluster
                    }
                }
                q = e
            }
            if (timeAt < 0 || places == 0) break
            points++
            if (videoCluster >= 0) {
                var time = 0L
                var k = timeAt
                while (k < timeEnd) {
                    time = (time shl 8) or (b[k].toLong() and 0xFF)
                    k++
                }
                if (!add(time, videoCluster, alone = places == 1)) {
                    next += pe - from
                    return -1
                }
            }
            p = pe
        }
        next += p - from
        if (next == cuesEnd) done = true
        return if (done) -1 else p
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
                    other = false
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
            ID_CUE_TRACK -> {
                track = value
                if (value != videoTrack) other = true
            }
            ID_CUE_CLUSTER_POSITION -> cluster = value
        }
    }

    /** An element has ended at [next]: whatever ends there with it is closed. False when given up. */
    private fun closed(): Boolean {
        phase = ID
        got = 0
        if (level == 3) {
            if (next > placeEnd) return failed("a point's place ends early")
            if (next < placeEnd) {
                // Another track's place (a subtitle line's, mostly): nothing more of it is wanted.
                if (other) {
                    left = placeEnd - next
                    phase = SKIP
                }
                return true
            }
            // Another track's place counts as one without its cluster being looked for: the index's rules say it has one.
            if (other) {
                places++
            } else if (cluster >= 0) {
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
        // The same as bytes, for points taken where they lie.
        const val POINT = 0xBB.toByte()
        const val TIME = 0xB3.toByte()
        const val PLACES = 0xB7.toByte()
        const val TRACK = 0xF7.toByte()
        const val CLUSTER = 0xF1.toByte()
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
