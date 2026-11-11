package app.fireplayer.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class SyncMatcherTest {

    /** A film's worth of made-up lines: 2 to 5 s each, with 1 to 12 s between them. */
    private fun lines(seed: Long, minutes: Int): List<SubtitleTimes.Line> {
        val rnd = Random(seed)
        val out = ArrayList<SubtitleTimes.Line>()
        var t = 5_000L
        while (t < minutes * 60_000L) {
            val length = 2_000L + rnd.nextInt(3_000)
            out += SubtitleTimes.Line(t, t + length)
            t += length + 1_000 + rnd.nextInt(11_000)
        }
        return out
    }

    /**
     * What the speech detector might hear for [lines] said [offsetMs] later: talking through most of each line (with
     * pauses), plus loud music and effects marked as talking now and then.
     */
    private fun heard(lines: List<SubtitleTimes.Line>, offsetMs: Long, seed: Long, noise: Double = 0.08): SpeechTimeline {
        val rnd = Random(seed)
        val tl = SpeechTimeline()
        val end = ((lines.last().endMs + offsetMs) / 10 + 3_000).toInt()
        val talking = java.util.BitSet()
        for (l in lines) {
            var f = ((l.startMs + offsetMs) / 10 + rnd.nextInt(20)).toInt()
            val to = ((l.endMs + offsetMs) / 10 - rnd.nextInt(30)).toInt()
            while (f < to) {
                val run = 15 + rnd.nextInt(40)
                if (f >= 0) talking.set(f, minOf(f + run, to))
                f += run + rnd.nextInt(15)
            }
        }
        var burst = 0
        for (f in 0 until end) {
            if (burst == 0 && rnd.nextDouble() < noise / 30) burst = 30 + rnd.nextInt(200)
            val loud = burst > 0
            if (burst > 0) burst--
            tl.mark(f, talking.get(f) || loud)
        }
        return tl
    }

    @Test
    fun findsTheShift() {
        for ((i, offset) in listOf(0L, 1_400L, -2_300L, 8_000L, -45_000L, 25_000L).withIndex()) {
            val lines = lines(seed = 10L + i, minutes = 20)
            val tl = heard(lines, offset, seed = 100L + i)
            val (speech, heard) = tl.snapshot()
            // Only the first four minutes, as if just started.
            heard.clear(24_000, heard.length())
            val result = SyncMatcher.match(speech, heard, SubtitleTimes.frames(lines), lines)
            assertNotNull(result)
            assertTrue("$offset: $result", result!!.confident)
            assertTrue("$offset: $result", abs(result.delayMs - offset) <= 100)
        }
    }

    @Test
    fun recheckLooksNearTheCurrentShiftOnly() {
        val lines = lines(seed = 3, minutes = 40)
        val tl = heard(lines, 2_000L, seed = 4)
        val (speech, heard) = tl.snapshot(fromFrame = 150_000)
        heard.clear(168_000, heard.length())
        val result = SyncMatcher.match(speech, heard, SubtitleTimes.frames(lines), lines, centerMs = 1_500, rangeMs = 10_000)
        assertNotNull(result)
        assertTrue(abs(result!!.delayMs - 2_000L) <= 100)
    }

    @Test
    fun notSureWithoutTalking() {
        // The lines of one film against what was heard in another: nothing lines up.
        val lines = lines(seed = 1, minutes = 20)
        val other = heard(lines(seed = 2, minutes = 20), 0L, seed = 5)
        val (speech, heard) = other.snapshot()
        val result = SyncMatcher.match(speech, heard, SubtitleTimes.frames(lines), lines)
        assertFalse("$result", result?.confident == true)
    }

    @Test
    fun readsSrtVttAndAss() {
        val srt = "1\n00:00:01,500 --> 00:00:03,000\nHello there.\n\n2\n00:00:04,000 --> 00:00:05,000\n[door slams]\n\n" +
            "3\n00:01:02,250 --> 00:01:04,000\n<i>Where are you?</i>\n\n4\n00:02:00,000 --> 00:02:03,000\n♪ la la la ♪\n"
        val s = SubtitleTimes.parse(srt, ass = false)
        assertEquals(listOf(1_500L, 62_250L), s.map { it.startMs })
        assertEquals(64_000L, s[1].endMs)

        val vtt = "WEBVTT\n\n00:05.000 --> 00:07.500\nShort times\n\n01:00:00.000 --> 01:00:01.000 align:start\nAn hour in\n"
        assertEquals(listOf(5_000L, 3_600_000L), SubtitleTimes.parse(vtt, ass = false).map { it.startMs })

        val ass = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:10.50,0:00:12.00,Default,,0,0,0,,{\\i1}Hi,{\\i0} you.\\NSecond line\n" +
            "Dialogue: 0,0:00:13.00,0:00:14.00,Sign,,0,0,0,,{\\p1}m 0 0 l 10 0 10 10{\\p0}\n"
        val a = SubtitleTimes.parse(ass, ass = true)
        assertEquals(1, a.size)
        assertEquals(10_500L, a[0].startMs)
        assertEquals(12_000L, a[0].endMs)
    }

    @Test
    fun formatsShifts() {
        assertEquals("+1.4 s", SyncMatcher.formatShift(1_400))
        assertEquals("−2.3 s", SyncMatcher.formatShift(-2_340))
        assertEquals("0.0 s", SyncMatcher.formatShift(20))
    }
}
