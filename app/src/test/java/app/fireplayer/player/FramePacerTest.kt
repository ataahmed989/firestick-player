package app.fireplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacerTest {

    /**
     * A film played onto a modelled screen. Media3 sends each frame out about 45 ms before its time (with the time it
     * gives, [FramePacer.paced] changes it); the screen shows a frame at its first refresh from then on, and only the
     * newest of frames due at one refresh; when a frame was shown, the renderer hears of it a few ms later.
     */
    private class Run(
        val screenNs: Long,
        val pacer: FramePacer,
        val periodNs: Long,
        val mode: Int = 1,
        val unaligned: Boolean = true,
    ) {
        val shown = ArrayList<Long>()
        var collisions = 0
        /** How many frames had been heard of as shown when the pacer said the screen disagrees (-1: never). */
        var trippedAfter = -1
        private var heard = 0
        private val pending = ArrayDeque<Long>()
        private var lastPresent = Long.MIN_VALUE

        fun play(frames: Int, startNs: Long = 1_000_000_000L, frameNs: Long = FILM_NS) {
            for (i in 0 until frames) {
                val release = startNs + i * frameNs
                val sentAt = release - SENT_EARLY_NS
                while (pending.isNotEmpty() && pending.first() + HEARD_AFTER_NS <= sentAt) {
                    heard++
                    if (pacer.rendered(pending.removeFirst(), unaligned, periodNs, mode) && trippedAfter < 0) trippedAfter = heard
                }
                val out = pacer.paced(release, unaligned, periodNs, mode)
                var present = refreshFrom(maxOf(out, sentAt))
                if (present <= lastPresent) {
                    // Both due at the same refresh: only this one shows.
                    collisions++
                    present = lastPresent
                    shown.removeAt(shown.size - 1)
                    pending.removeLast()
                }
                shown += present
                pending.addLast(present)
                lastPresent = present
            }
        }

        private fun refreshFrom(ns: Long): Long = Math.floorDiv(ns + screenNs - 1, screenNs) * screenNs

        /** How many refreshes apart the frames shown were, from frame [from] on. */
        fun gaps(from: Int = 0): List<Long> = (from + 1 until shown.size).map { (shown[it] - shown[it - 1] + screenNs / 2) / screenNs }
    }

    @Test
    fun oneFramePerRefreshAtTheFilmsRate() {
        val run = Run(screenNs = FILM_NS, pacer = FramePacer(), periodNs = TvRefresh.periodNs(23.976f))
        run.play(2_000)
        assertEquals(-1, run.trippedAfter)
        assertEquals(0, run.collisions)
        assertTrue(run.gaps(from = 10).all { it == 1L })
    }

    @Test
    fun aStaleRefreshOnA5994TvFallsBackToMedia3sTimes() {
        // The TV came on at 59.94 Hz while frames were still paced for the 23.976 Hz measured before.
        val run = Run(screenNs = TvRefresh.periodNs(59.94f), pacer = FramePacer(), periodNs = TvRefresh.periodNs(23.976f))
        run.play(48)
        // What was seen: frames 1, 4 or 5 refreshes apart instead of 2 and 3 in turn.
        val before = run.gaps(from = 2)
        assertTrue("gaps $before", before.any { it == 1L || it >= 4L })
        assertTrue("tripped after ${run.trippedAfter}", run.trippedAfter in 1..FramePacer.JUDGED * 2)
        assertEquals(TvRefresh.periodNs(23.976f), run.pacer.offGridPeriodNs)
        // From then on Media3's times, evenly 2 and 3 refreshes apart.
        val from = run.shown.size
        run.play(800, startNs = 1_000_000_000L + 48 * FILM_NS)
        val after = run.gaps(from = from)
        val even = after.count { it == 2L || it == 3L }
        assertTrue("$even of ${after.size} even", even >= after.size * 99 / 100)
        // Still judged on Media3's times, and still not fitting: left to Media3.
        assertEquals(TvRefresh.periodNs(23.976f), run.pacer.offGridPeriodNs)
    }

    @Test
    fun aScreenThatFitsAgainOnTheSameModeIsPacedAgain() {
        // The TV off or reconnecting for a moment, with Fire OS keeping the 23.976 Hz mode: frames reach the screen out of
        // step with the refresh measured, so they go out on Media3's times.
        val pacer = FramePacer()
        val period = TvRefresh.periodNs(23.976f)
        Run(screenNs = TvRefresh.periodNs(59.94f), pacer = pacer, periodNs = period).play(48)
        assertEquals(period, pacer.offGridPeriodNs)
        // The TV back on the same mode, measured the same: once the frames fit its refreshes again, they're paced again.
        val back = Run(screenNs = FILM_NS, pacer = pacer, periodNs = period)
        back.play(200, startNs = 10_000_000_000L)
        assertEquals(0L, pacer.offGridPeriodNs)
        assertEquals(-1, back.trippedAfter)
        assertTrue(back.gaps(from = 60).all { it == 1L })
        assertNotEquals(20_000_000_000L, pacer.paced(20_000_000_000L, true, period, 1))
    }

    @Test
    fun theScreenIsntJudgedWhileMedia3LinesFramesUp() {
        val run = Run(screenNs = TvRefresh.periodNs(59.94f), pacer = FramePacer(), periodNs = TvRefresh.periodNs(23.976f), unaligned = false)
        run.play(500)
        assertEquals(-1, run.trippedAfter)
        assertEquals(0L, run.pacer.offGridPeriodNs)
    }

    @Test
    fun anotherRefreshOrModeMeasuredPacesAgain() {
        val pacer = FramePacer()
        val stale = TvRefresh.periodNs(23.976f)
        Run(screenNs = TvRefresh.periodNs(59.94f), pacer = pacer, periodNs = stale, mode = 1).play(48)
        assertEquals(stale, pacer.offGridPeriodNs)
        val release = 5_000_000_000L
        // Measured the same again, for the same mode: still Media3's times, so no new burst of uneven frames.
        assertEquals(release, pacer.paced(release, true, stale, 1))
        assertFalse(pacer.rendered(release, true, stale, 1))
        // Another refresh measured, or the same after the TV was on another mode: paced again.
        assertNotEquals(release + 7_000_000L, pacer.paced(release + 7_000_000L, true, TvRefresh.periodNs(24f), 1))
        val onGrid = pacer.paced(release + 9_000_000L, true, stale, 2)
        assertNotEquals(release + 9_000_000L, onGrid)
        // A new decoder starts afresh.
        pacer.reset()
        assertEquals(0L, pacer.offGridPeriodNs)
    }

    @Test
    fun aFewLateFramesDontCount() {
        // On the right refresh, with one frame in twelve heard of 6 ms late (two intervals off each time): under a third,
        // so it's left paced.
        val pacer = FramePacer()
        val p = TvRefresh.periodNs(23.976f)
        var t = 1_000_000_000L
        var tripped = false
        for (i in 0 until 2_000) {
            t += p
            tripped = tripped || pacer.rendered(if (i % 12 == 0) t + 6_000_000L else t, true, p, 1)
        }
        assertFalse(tripped)
    }

    private companion object {
        val FILM_NS = (1e9 * 1001 / 24000).toLong()
        const val SENT_EARLY_NS = 45_000_000L
        const val HEARD_AFTER_NS = 3_000_000L
    }
}
