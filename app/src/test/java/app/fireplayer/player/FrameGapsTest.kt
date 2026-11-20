package app.fireplayer.player

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameGapsTest {

    /** Frame [k]'s time in a 23.976 fps Matroska film: whole milliseconds, so 41 or 42 ms apart. */
    private fun us(k: Int): Long = 10_000_000L + (k * 1001L * 1000 / 24000) * 1000

    /** Frames [from] until [to] go to the screen with Media3's counts as given; what each says was never decoded, summed. */
    private fun FrameGaps.play(from: Int, to: Int, dropped: Int = 0, skipped: Int = 0): Int =
        (from until to).sumOf { frame(us(it), dropped, skipped) }

    @Test
    fun framesThatFollowOnHaveNoGaps() {
        val gaps = FrameGaps()
        assertEquals(0, gaps.play(0, 500))
        assertEquals(0, gaps.between)
        // The middle gap of the latest: 42 ms (Matroska's rounding), against 41.7 as the film would say.
        assertEquals(42_000L, gaps.frameUs)
    }

    @Test
    fun aFrameTheDecoderNeverGaveIsCounted() {
        val gaps = FrameGaps()
        gaps.play(0, 100)
        // Frame 100 never came, and Media3 neither dropped nor skipped it.
        assertEquals(1, gaps.frame(us(101), 0, 0))
        assertEquals(1, gaps.between)
        // Three in a row.
        assertEquals(3, gaps.frame(us(105), 0, 0))
        assertEquals(0, gaps.frame(us(106), 0, 0))
    }

    @Test
    fun framesMedia3DroppedOrSkippedAreItsOwnToCount() {
        val gaps = FrameGaps()
        gaps.play(0, 100)
        // Media3 dropped frame 100 (too late): a gap, but its own count says so.
        assertEquals(0, gaps.frame(us(101), 1, 0))
        assertEquals(1, gaps.between)
        // Of the three missing before frame 107 it skipped one and dropped another: the third was never decoded.
        gaps.play(102, 104, dropped = 1)
        assertEquals(1, gaps.frame(us(107), 2, 1))
    }

    @Test
    fun aFrameSkippedHereDoesntExplainAGap() {
        val gaps = FrameGaps()
        gaps.play(0, 100)
        // Frame 100 is skipped by the renderer itself (put on the refresh of the one before): Media3's count goes up by one.
        gaps.frame(us(100), 0, 0)
        gaps.skippedHere()
        assertEquals(0, gaps.frame(us(101), 0, 1))
        // Frame 102 never comes: the skip before it doesn't account for it.
        assertEquals(1, gaps.frame(us(103), 0, 1))
    }

    @Test
    fun aJumpOrANewDecoderIsNotAGap() {
        val gaps = FrameGaps()
        gaps.play(0, 100)
        // Two seconds on: a jump.
        assertEquals(0, gaps.frame(us(148), 0, 0))
        // Started afresh: the first frame after has nothing before it.
        gaps.reset()
        assertEquals(0, gaps.frame(us(300), 0, 0))
        assertEquals(0, gaps.between)
        // A new renderer counts from 0 again: a count lower than the last is all new drops.
        gaps.play(301, 310, dropped = 5)
        assertEquals(0, gaps.frame(us(311), 1, 0))
        assertEquals(1, gaps.frame(us(313), 1, 0))
    }

    @Test
    fun gapsCountOnlyOnceTheFramesHaveBeenMeasured() {
        val gaps = FrameGaps()
        gaps.stated(41_708L)
        // Until then the film's stated length is the pacer's to go by, but no gap is counted on it alone.
        assertEquals(41_708L, gaps.frameUs)
        gaps.frame(us(0), 0, 0)
        assertEquals(0, gaps.frame(us(3), 0, 0))
        assertEquals(0, gaps.play(4, 10))
        assertEquals(42_000L, gaps.frameUs)
        assertEquals(2, gaps.frame(us(12), 0, 0))
        // Nothing stated, nothing measured: nothing known.
        assertEquals(0L, FrameGaps().frameUs)
    }

    @Test
    fun whatTheFramesDoWinsOverWhatTheFileSays() {
        // An interlaced film stating its fields' rate, twice its frames': no gaps, and its frames' own length.
        val gaps = FrameGaps()
        gaps.stated(20_854L)
        assertEquals(0, gaps.play(0, 300))
        assertEquals(42_000L, gaps.frameUs)
        assertEquals(1, gaps.frame(us(301), 0, 0))
        // The next film at another rate: measured afresh.
        gaps.stated(16_683L)
        assertEquals(16_683L, gaps.frameUs)
        gaps.stated(16_683L)
        assertEquals(16_683L, gaps.frameUs)
    }
}
