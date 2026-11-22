package app.fireplayer.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewGuardTest {

    private val longAgo = -1_000_000L

    @Test fun previewsStartOff() = assertFalse(PreviewGuard.DEFAULT_ON)

    @Test fun leftOnByTheOldDefaultIsTurnedOffOnce() {
        assertTrue(PreviewGuard.offOnce(stored = true, done = false))
        // Turned back on after that: left alone.
        assertFalse(PreviewGuard.offOnce(stored = true, done = true))
        assertFalse(PreviewGuard.offOnce(stored = false, done = false))
        // Never chosen: the new default already says Off.
        assertFalse(PreviewGuard.offOnce(stored = null, done = false))
    }

    @Test fun nothingIsCoveredBeforeAnyPreview() {
        val g = PreviewGuard()
        assertFalse(g.covers(0L))
        assertFalse(g.covers(100_000L))
        assertFalse(g.stalled(100_000L, longAgo))
    }

    @Test fun coversWhileRunningAndAFewSecondsAfter() {
        val g = PreviewGuard()
        g.started(aheadMs = 30_000L)
        assertTrue(g.covers(50_000L))
        g.ended(60_000L)
        assertTrue(g.covers(60_000L + PreviewGuard.AFTER_MS - 1))
        assertFalse(g.covers(60_000L + PreviewGuard.AFTER_MS))
    }

    @Test fun aStallWhileThePreviewRunsIsThePreviews() {
        val g = PreviewGuard()
        g.started(30_000L)
        assertTrue(g.stalled(50_000L, longAgo))
        // The film's own jump a moment ago (a chapter skipped mid-round): the wait is the jump's.
        assertFalse(g.stalled(50_000L, 49_000L))
    }

    @Test fun aStallJustAfterIsThePreviewsOnlyWithoutAJumpSince() {
        val g = PreviewGuard()
        g.started(30_000L)
        g.ended(60_000L)
        // Skipping called off: no jump.
        assertTrue(g.stalled(62_000L, longAgo))
        // The film jumped as the round ended: loading there is the jump's (and still not the source's).
        assertFalse(g.stalled(62_000L, 60_000L))
        assertTrue(g.covers(62_000L))
        assertFalse(g.stalled(60_000L + PreviewGuard.AFTER_MS, longAgo))
    }

    @Test fun droppedFramesAddUpOverOneRoundOnly() {
        val g = PreviewGuard()
        assertFalse(g.framesDropped(100L))
        g.started(30_000L)
        assertFalse(g.framesDropped(PreviewGuard.DROPPED_LIMIT - 1))
        assertFalse(g.framesDropped(0L))
        assertTrue(g.framesDropped(1L))
        g.ended(1_000L)
        assertFalse(g.framesDropped(100L))
        g.started(30_000L)
        assertFalse(g.framesDropped(PreviewGuard.DROPPED_LIMIT - 1))
    }

    @Test fun aBufferThatRunsLowFromHealthyIsThePreviews() {
        val g = PreviewGuard()
        assertFalse(g.bufferFell(0L))
        g.started(PreviewGuard.HEALTHY_MS)
        assertFalse(g.bufferFell(PreviewGuard.LOW_MS))
        assertTrue(g.bufferFell(PreviewGuard.LOW_MS - 1))
        g.ended(0L)
        assertFalse(g.bufferFell(0L))
        // Already low when skipping began (a film that only just keeps up): not the preview's doing.
        g.started(PreviewGuard.HEALTHY_MS - 1)
        assertFalse(g.bufferFell(0L))
    }

    @Test fun moreSkippingInOneRoundKeepsItsStart() {
        val g = PreviewGuard()
        g.started(aheadMs = 30_000L)
        // A later press, the film having played on with less loaded: still the round that began healthy.
        g.started(aheadMs = 1_000L)
        assertTrue(g.bufferFell(PreviewGuard.LOW_MS - 1))
        // Ending twice keeps the first end.
        g.ended(10_000L)
        g.ended(20_000L)
        assertFalse(g.covers(10_000L + PreviewGuard.AFTER_MS))
    }

    @Test fun aNewFilmStartsClean() {
        val g = PreviewGuard()
        g.started(30_000L)
        g.ended(60_000L)
        g.reset()
        assertFalse(g.active)
        assertFalse(g.covers(60_001L))
    }
}
