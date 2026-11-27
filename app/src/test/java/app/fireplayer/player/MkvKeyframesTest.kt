package app.fireplayer.player

import androidx.media3.extractor.ChunkIndex
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An MKV's index read for the video's keyframes ([CueReader]), from index bytes written as muxers write them, and the
 * jumps' map made from them ([KeyframeSeekMap]) over a map as Media3 makes it from the same index (every point, whatever
 * its track). And the log's line for a jump ([JumpLine]). Read through Media3's own MKV reader in [SeekableMkvTest].
 */
class MkvKeyframesTest {

    // --- An index, written the way muxers write it

    private fun idBytes(id: Long): ByteArray {
        val n = when {
            id > 0xFFFFFF -> 4
            id > 0xFFFF -> 3
            id > 0xFF -> 2
            else -> 1
        }
        return ByteArray(n) { ((id shr (8 * (n - 1 - it))) and 0xFF).toByte() }
    }

    private fun sizeBytes(n: Long): ByteArray {
        val w = (1..8).first { n < (1L shl (7 * it)) - 1 }
        val b = ByteArray(w) { ((n shr (8 * (w - 1 - it))) and 0xFF).toByte() }
        b[0] = (b[0].toInt() or (0x80 shr (w - 1))).toByte()
        return b
    }

    private fun el(id: Long, vararg body: ByteArray): ByteArray {
        val payload = body.fold(ByteArray(0)) { a, b -> a + b }
        return idBytes(id) + sizeBytes(payload.size.toLong()) + payload
    }

    private fun uint(v: Long, n: Int = 0): ByteArray {
        val w = if (n > 0) n else (1..8).first { it == 8 || v < (1L shl (8 * it)) }
        return ByteArray(w) { ((v shr (8 * (w - 1 - it))) and 0xFF).toByte() }
    }

    /** A track's place in a point: its cluster, the place inside it and how long the block lasts, as mkvmerge writes. */
    private fun place(track: Int, cluster: Long) =
        el(0xB7, el(0xF7, uint(track.toLong())), el(0xF1, uint(cluster)), el(0xF0, uint(1234, 2)), el(0xB2, uint(400)))

    /** A point at [time] (in the file's unit) with a place for each of [places] (track to cluster). */
    private fun point(time: Long, vararg places: Pair<Int, Long>) =
        el(0xBB, el(0xB3, uint(time)), *places.map { place(it.first, it.second) }.toTypedArray())

    private fun cues(vararg children: ByteArray) = el(0x1C53BB6B, *children)

    private class Listed(val time: Long, val track: Int, val cluster: Long)

    /**
     * A film's index: a keyframe (track 1) every 4 s in its own cluster, 4 MB on from the last, and a subtitle line (track
     * 3) half a second into every second, in a cluster a third of the way between; a CRC-32 first and a Void after the
     * second point, as muxers leave them.
     */
    private fun listed(seconds: Int = 12): List<Listed> {
        val all = ArrayList<Listed>()
        for (s in 0 until seconds) {
            if (s % 4 == 0) all += Listed(s * 1000L, 1, s * 1_000_000L + 500)
            all += Listed(s * 1000L + 500, 3, s * 1_000_000L + 333_000)
        }
        return all
    }

    private fun indexOf(all: List<Listed>): ByteArray {
        val children = ArrayList<ByteArray>()
        children += el(0xBF, ByteArray(4))
        all.forEachIndexed { i, p ->
            children += point(p.time, p.track to p.cluster)
            if (i == 1) children += el(0xEC, ByteArray(40))
        }
        return cues(*children.toTypedArray())
    }

    /** What Media3 makes of the same index: every point's time and the place of its first track, from [segmentStart]. */
    private fun media3(all: List<Pair<Long, Long>>, segmentStart: Long, durationUs: Long, unitUs: Long = 1000): ChunkIndex {
        val n = all.size
        val times = LongArray(n) { all[it].first * unitUs }
        val offsets = LongArray(n) { segmentStart + all[it].second }
        val durations = LongArray(n) { if (it < n - 1) times[it + 1] - times[it] else durationUs - times[it] }
        val sizes = IntArray(n) { if (it < n - 1) (offsets[it + 1] - offsets[it]).toInt() else 1 }
        return ChunkIndex(sizes, offsets, durations, times)
    }

    private fun media3(all: List<Listed>, durationUs: Long = 12_000_000L): ChunkIndex =
        media3(all.map { it.time to it.cluster }, SEGMENT, durationUs)

    /** [bytes] as the index at [at], handed over [piece] bytes at a time. */
    private fun read(bytes: ByteArray, piece: Int = bytes.size, at: Long = AT, track: Long = 1, unitNs: Long = 1_000_000): CueReader {
        val r = CueReader(at, track, unitNs)
        var i = 0
        while (i < bytes.size) {
            val n = minOf(piece, bytes.size - i)
            r.feed(at + i, bytes, i, n)
            i += n
        }
        return r
    }

    private fun mapOf(all: List<Listed>, piece: Int = Int.MAX_VALUE): KeyframeSeekMap {
        val bytes = indexOf(all)
        val r = read(bytes, minOf(piece, bytes.size))
        assertNull(r.failure)
        assertTrue(r.done)
        return r.build(media3(all), SEGMENT, LENGTH)!!
    }

    private fun assertPoint(timeUs: Long, position: Long, p: SeekPoint) {
        assertEquals(timeUs, p.timeUs)
        assertEquals(position, p.position)
    }

    // --- The video's keyframes, and nothing else

    @Test
    fun onlyTheVideoTracksPointsAreWhereAJumpLands() {
        val all = listed()
        val map = mapOf(all)
        assertEquals(3, map.count)
        assertEquals(15, map.points)
        assertTrue(map.isSeekable)
        assertEquals(12_000_000L, map.durationUs)
        // Between two keyframes: the one before, and the next.
        val mid = map.getSeekPoints(6_900_000L)
        assertPoint(4_000_000L, SEGMENT + 4_000_500L, mid.first)
        assertPoint(8_000_000L, SEGMENT + 8_000_500L, mid.second)
        // Media3's own answer for the same time is the subtitle line just before it.
        assertPoint(6_500_000L, SEGMENT + 6_333_000L, media3(all).getSeekPoints(6_900_000L).first)
        // On a keyframe: that one alone. A microsecond before it: the one before, then it.
        val on = map.getSeekPoints(4_000_000L)
        assertPoint(4_000_000L, SEGMENT + 4_000_500L, on.first)
        assertTrue(on.first === on.second)
        assertPoint(0L, SEGMENT + 500L, map.getSeekPoints(3_999_999L).first)
        assertPoint(4_000_000L, SEGMENT + 4_000_500L, map.getSeekPoints(3_999_999L).second)
        // After the last one: it alone, to the film's end.
        val late = map.getSeekPoints(11_900_000L)
        assertPoint(8_000_000L, SEGMENT + 8_000_500L, late.first)
        assertTrue(late.first === late.second)
        assertEquals(4_000_000L, map.keyframeUs(7_999_999L))
        assertEquals(8_000_000L, map.keyframeUs(8_000_000L))
        assertEquals(0L, map.keyframeUs(0L))
    }

    @Test
    fun beforeTheFirstKeyframeListedMedia3sAnswerStands() {
        // The video's first point at 4 s (the film's start isn't in the index): a jump before it goes as it always did.
        val all = listed().filter { it.track != 1 || it.time >= 4000 }
        val map = mapOf(all)
        assertEquals(2, map.count)
        assertPoint(1_500_000L, SEGMENT + 1_333_000L, map.getSeekPoints(1_700_000L).first)
        assertEquals(-1L, map.keyframeUs(3_999_999L))
        assertPoint(4_000_000L, SEGMENT + 4_000_500L, map.getSeekPoints(4_000_000L).first)
    }

    @Test
    fun theIndexReadsTheSameHoweverItsBytesArrive() {
        val all = listed(40)
        val bytes = indexOf(all)
        val whole = read(bytes).build(media3(all, 40_000_000L), SEGMENT, LENGTH)!!
        assertEquals(10, whole.count)
        for (piece in listOf(1, 2, 3, 5, 7, 13, 64, 1000)) {
            val r = read(bytes, piece)
            assertNull("$piece at a time", r.failure)
            assertTrue(r.done)
            val map = r.build(media3(all, 40_000_000L), SEGMENT, LENGTH)!!
            assertEquals(whole.count, map.count)
            assertEquals(whole.points, map.points)
            for (t in 0L..40_000_000L step 700_000L) {
                assertPoint(whole.getSeekPoints(t).first.timeUs, whole.getSeekPoints(t).first.position, map.getSeekPoints(t).first)
            }
        }
    }

    @Test
    fun whatComesBeforeItAfterItAndTwiceIsPassedOver() {
        val all = listed()
        val bytes = indexOf(all)
        // The file around it: 300 bytes before, 200 after, every one of them the index's own ID over and over.
        val junk = idBytes(0x1C53BB6B)
        val file = ByteArray(300) { junk[it % 4] } + bytes + ByteArray(200) { junk[it % 4] }
        val from = AT - 300
        val r = CueReader(AT, 1, 1_000_000)
        assertTrue(r.near(from))
        // A piece ending before it; pieces 90 bytes long each starting 50 on from the last, so 40 bytes come twice.
        r.feed(from, file, 0, 250)
        var i = 250
        while (i < file.size) {
            r.feed(from + i, file, i, minOf(90, file.size - i))
            i += 50
        }
        assertNull(r.failure)
        assertTrue(r.done)
        assertTrue(r.over)
        assertFalse(r.near(AT))
        assertEquals(3, r.count)
        assertEquals(15, r.points)
        assertNotNull(r.build(media3(all), SEGMENT, LENGTH))
        // More of the file after it changes nothing.
        r.feed(AT + bytes.size, file, 0, 100)
        assertEquals(3, r.count)
    }

    @Test
    fun aStretchNeverSeenIsFineOnlyInsideWhatIsPassedOverAnyway() {
        val all = listed()
        val bytes = indexOf(all)
        // The Void's 40 bytes start after the CRC-32 (6 bytes) and the first two points.
        val voidAt = (0 until bytes.size - 2).first { bytes[it] == 0xEC.toByte() && bytes[it + 1] == (0x80 or 40).toByte() }
        val inVoid = voidAt + 2 + 10
        // Ten of its bytes never shown (skipped by the reader past the end of a piece): the rest reads on.
        val ok = CueReader(AT, 1, 1_000_000)
        ok.feed(AT, bytes, 0, inVoid)
        ok.feed(AT + inVoid + 10, bytes, inVoid + 10, bytes.size - inVoid - 10)
        assertNull(ok.failure)
        assertTrue(ok.done)
        assertEquals(3, ok.count)
        // All that was left of it never shown.
        val toEnd = CueReader(AT, 1, 1_000_000)
        toEnd.feed(AT, bytes, 0, inVoid)
        toEnd.feed(AT + voidAt + 42, bytes, voidAt + 42, bytes.size - voidAt - 42)
        assertNull(toEnd.failure)
        assertTrue(toEnd.done)
        // The reader saying so as it skips them (the index's very last bytes are followed by nothing to show it).
        val told = CueReader(AT, 1, 1_000_000)
        told.feed(AT, bytes, 0, inVoid)
        told.passed(AT + inVoid - 5, 35)
        told.feed(AT + voidAt + 42, bytes, voidAt + 42, bytes.size - voidAt - 42 - 2)
        assertFalse(told.done)
        told.passed(AT + bytes.size - 2, 2)
        assertNull(told.failure)
        assertTrue(told.done)
        assertEquals(3, told.count)
        // But a byte of a point's own never shown: given up, and jumps go by Media3's.
        val lost = CueReader(AT, 1, 1_000_000)
        lost.feed(AT, bytes, 0, voidAt + 42 + 3)
        lost.feed(AT + voidAt + 42 + 4, bytes, voidAt + 42 + 4, bytes.size - voidAt - 42 - 4)
        assertNotNull(lost.failure)
        assertFalse(lost.done)
        assertTrue(lost.over)
        assertNull(lost.build(media3(all), SEGMENT, LENGTH))
        // And one more than the Void had left.
        val past = CueReader(AT, 1, 1_000_000)
        past.feed(AT, bytes, 0, inVoid)
        past.feed(AT + voidAt + 43, bytes, voidAt + 43, bytes.size - voidAt - 43)
        assertNotNull(past.failure)
    }

    @Test
    fun readingThatBeginsInsideTheIndexOrSeesOnlyPartOfItGivesNone() {
        val all = listed()
        val bytes = indexOf(all)
        val inside = CueReader(AT, 1, 1_000_000)
        inside.feed(AT + 5, bytes, 5, bytes.size - 5)
        assertNotNull(inside.failure)
        assertNull(inside.build(media3(all), SEGMENT, LENGTH))
        // Half of it, and no more: not whole, so none.
        val half = CueReader(AT, 1, 1_000_000)
        half.feed(AT, bytes, 0, bytes.size / 2)
        assertNull(half.failure)
        assertFalse(half.done)
        assertTrue(half.near(AT + bytes.size / 2))
        assertFalse(half.near(AT + bytes.size / 2 + 1))
        assertNull(half.build(media3(all), SEGMENT, LENGTH))
        assertEquals("it wasn't seen whole", half.failure)
        // Nothing of it at all (the reader never went there).
        val none = CueReader(AT, 1, 1_000_000)
        none.feed(0, bytes, 0, bytes.size)
        assertNull(none.build(media3(all), SEGMENT, LENGTH))
    }

    @Test
    fun aFileReadFromItsStartAgainHasItsIndexReadAgain() {
        val all = listed()
        val bytes = indexOf(all)
        val r = CueReader(AT, 1, 1_000_000)
        r.feed(AT, bytes, 0, 100)
        r.restart()
        assertTrue(r.near(AT))
        r.feed(AT, bytes, 0, bytes.size)
        assertNull(r.failure)
        assertTrue(r.done)
        assertEquals(3, r.count)
        assertEquals(15, r.points)
        assertNotNull(r.build(media3(all), SEGMENT, LENGTH))
    }

    @Test
    fun onlyReadsNearTheIndexAreShownToIt() {
        val r = CueReader(50_000_000L, 1, 1_000_000)
        // The film's first bytes, gigabytes before an index at its end: not shown (read as they always were).
        assertFalse(r.near(0L))
        assertTrue(r.near(50_000_000L))
        assertTrue(r.near(50_000_000L - 4L * 1024 * 1024))
        assertFalse(r.near(50_000_000L - 4L * 1024 * 1024 - 1))
        assertFalse(r.near(50_000_001L))
        // An index before the clusters is come to from the film's first bytes.
        assertTrue(CueReader(5_000L, 1, 1_000_000).near(0L))
    }

    @Test
    fun aPointSharedWithAnotherTrackGivesTheVideosOwnPlace() {
        // As FFmpeg writes a line that starts with a keyframe: one point, the line's place first. Media3 lists that first
        // place; the video's is the one taken here. Two points for the video at one time: the first stands.
        val bytes = cues(
            point(0, 1 to 500L),
            point(4000, 3 to 3_900_000L, 1 to 4_000_500L),
            point(4000, 1 to 4_100_000L),
            point(4500, 3 to 4_333_000L),
            point(8000, 1 to 8_000_500L),
        )
        val r = read(bytes)
        assertNull(r.failure)
        assertEquals(3, r.count)
        assertEquals(5, r.points)
        val inner = media3(listOf(0L to 500L, 4000L to 3_900_000L, 4000L to 4_100_000L, 4500L to 4_333_000L, 8000L to 8_000_500L),
            SEGMENT, 12_000_000L)
        val map = r.build(inner, SEGMENT, LENGTH)!!
        assertPoint(4_000_000L, SEGMENT + 4_000_500L, map.getSeekPoints(5_000_000L).first)
        // Media3's own point for 4 s is the line's place, a cluster before the keyframe's.
        assertPoint(4_000_000L, SEGMENT + 3_900_000L, inner.getSeekPoints(4_000_000L).first)
    }

    @Test
    fun aKeyframeListedForAnotherTrackNumberIsNotTheVideos() {
        // The video is track 2 in this file: track 1's points (its sound, say) aren't keyframes of the picture.
        val all = listed()
        val r = read(indexOf(all), track = 2)
        assertNull(r.failure)
        assertTrue(r.done)
        assertEquals(0, r.count)
        assertNull(r.build(media3(all), SEGMENT, LENGTH))
        assertEquals("it lists no keyframe of track 2", r.failure)
    }

    // --- An index that doesn't read as one, or doesn't agree with Media3's reading of it

    @Test
    fun anIndexThatDoesNotReadAsOneGivesNone() {
        val good = point(0, 1 to 500L)
        val bad = listOf(
            // Something else where the index should be.
            el(0x1F43B675, good),
            // No size stated.
            idBytes(0x1C53BB6B) + byteArrayOf(0xFF.toByte()) + good,
            // A point running on past the index's end.
            idBytes(0x1C53BB6B) + sizeBytes(good.size - 3L) + good,
            // The video's points out of order.
            cues(point(4000, 1 to 4_000_500L), point(0, 1 to 500L)),
            // A point with no place, and one with no time.
            cues(good, el(0xBB, el(0xB3, uint(4000)))),
            cues(good, el(0xBB, place(1, 4_000_500L))),
            // A time nine bytes long.
            cues(good, el(0xBB, el(0xB3, ByteArray(9)), place(1, 4_000_500L))),
            // A byte that can't start an ID.
            cues(good, byteArrayOf(0, 0, 0)),
        )
        for ((i, bytes) in bad.withIndex()) {
            for (piece in listOf(bytes.size, 1)) {
                val r = read(bytes, piece)
                assertNotNull("index $i", r.failure)
                assertFalse(r.done)
                assertNull(r.build(media3(listOf(0L to 500L), SEGMENT, 12_000_000L), SEGMENT, LENGTH))
            }
        }
    }

    @Test
    fun randomBytesNeverThrowAndNeverGiveAMap() {
        val random = kotlin.random.Random(7)
        val inner = media3(listed())
        repeat(300) {
            val bytes = random.nextBytes(random.nextInt(1, 400))
            // Half of them starting as an index does.
            if (it % 2 == 0 && bytes.size > 6) idBytes(0x1C53BB6B).copyInto(bytes)
            val r = read(bytes, random.nextInt(1, 50))
            assertNull(r.build(inner, SEGMENT, LENGTH))
        }
    }

    @Test
    fun keyframesThatDoNotAgreeWithMedia3sReadingAreNotUsed() {
        val all = listed()
        val bytes = indexOf(all)
        // The right reading.
        assertNotNull(read(bytes).build(media3(all), SEGMENT, LENGTH))
        // The file's time unit taken ten times too big here: the last keyframe isn't where Media3 has it.
        val unit = read(bytes, unitNs = 10_000_000)
        assertNull(unit.build(media3(all, 120_000_000L), SEGMENT, LENGTH))
        assertEquals("it doesn't agree with Media3's", unit.failure)
        // The Segment's start a byte off.
        val start = read(bytes)
        assertNull(start.build(media3(all), SEGMENT + 1, LENGTH))
        assertEquals("it doesn't agree with Media3's", start.failure)
        // Not known at all.
        assertNull(read(bytes).build(media3(all), -1, LENGTH))
        // A keyframe past the end of the file.
        val outside = read(bytes)
        assertNull(outside.build(media3(all), SEGMENT, SEGMENT + 8_000_000L))
        assertEquals("a keyframe outside the file", outside.failure)
        // One keyframe only: nothing to hold the time unit to.
        val one = listed(4)
        assertNull(read(indexOf(one)).build(media3(one, 4_000_000L), SEGMENT, LENGTH))
        // Another kind of map from Media3 (not its usual): asked the same, and believed when it says the same.
        val other = object : SeekMap {
            override fun isSeekable() = true
            override fun getDurationUs() = 12_000_000L
            override fun getSeekPoints(timeUs: Long) = media3(all).getSeekPoints(timeUs)
        }
        assertNotNull(read(bytes).build(other, SEGMENT, LENGTH))
        assertNull(read(bytes).build(other, SEGMENT + 1, LENGTH))
    }

    @Test
    fun keyframesListedPastTheFilmsLengthAreLeftOut() {
        // As Media3 leaves out the points after the film's length (some files list one there).
        val all = listed()
        val r = read(indexOf(all))
        val map = r.build(media3(all.filter { it.time <= 7_000 }, 7_000_000L), SEGMENT, LENGTH)!!
        assertEquals(2, map.count)
        assertPoint(4_000_000L, SEGMENT + 4_000_500L, map.getSeekPoints(6_999_999L).first)
        val last = map.getSeekPoints(6_999_999L)
        assertTrue(last.first === last.second)
    }

    // --- The log's line for a jump

    @Test
    fun theLogSaysWhereAJumpLandedAndHowSoonItsPictureCame() {
        // On a keyframe 1.4 s before where it was to go, loaded from there: picture and sound together.
        assertEquals(
            "jump to 2650732 ms: on from 2649280 ms, first picture at 2649280 ms (+0 ms from the sound) after 412 ms, " +
                "loaded from the keyframe at 2649280 ms (0 ms before)",
            JumpLine.text(2_650_732L, 2_649_280L, 2_649_280L, 412L, 2_649_280L),
        )
        // No keyframe within 2 s: on from 2 s before, decoded on from the keyframe 2.6 s before that.
        assertEquals(
            "jump to 2650732 ms: on from 2648732 ms, first picture at 2648774 ms (+42 ms from the sound) after 980 ms, " +
                "loaded from the keyframe at 2646100 ms (2632 ms before)",
            JumpLine.text(2_650_732L, 2_648_732L, 2_648_774L, 980L, 2_646_100L),
        )
        // What 1.0.366 did on the TV: the picture from 3.4 s after where the sound started.
        assertEquals(
            "jump to 2650732 ms: on from 2648732 ms, first picture at 2652149 ms (+3417 ms from the sound) after 1300 ms",
            JumpLine.text(2_650_732L, 2_648_732L, 2_652_149L, 1_300L),
        )
        // A film resumed part-way in.
        assertEquals(
            "start at 600000 ms: on from 600000 ms, first picture at 599990 ms (-10 ms from the sound) after 2100 ms",
            JumpLine.text(600_000L, 600_000L, 599_990L, 2_100L, start = true),
        )
    }

    @Test
    fun theKeyframeInTheLogIsOnlyThisJumpsOwn() {
        assertEquals(-1L, JumpLine.keyMs(null, 5_200L))
        assertEquals(4_000L, JumpLine.keyMs(MkvJump.Mark(5_200_000L, 4_000_000L), 5_200L))
        assertEquals(4_000L, JumpLine.keyMs(MkvJump.Mark(5_200_999L, 4_000_000L), 5_200L))
        // Another jump's note (the film had this one loaded, and didn't load again), or one made with no keyframes known.
        assertEquals(-1L, JumpLine.keyMs(MkvJump.Mark(9_000_000L, 8_000_000L), 5_200L))
        assertEquals(-1L, JumpLine.keyMs(MkvJump.Mark(5_200_000L, -1L), 5_200L))
    }

    private companion object {
        /** Where the index is in the file, where the Segment's contents begin, and the file's length. */
        const val AT = 50_000_000L
        const val SEGMENT = 52L
        const val LENGTH = 51_000_000L
    }
}
