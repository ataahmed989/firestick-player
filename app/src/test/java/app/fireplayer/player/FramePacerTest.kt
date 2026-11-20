package app.fireplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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

    // --- Media3's times wobbling, drifting, and frames dropped (a port of the research's model, sim/pacer_fix_sim.py)

    /**
     * Media3 sends out frame k at base + k frames, the film [driftPpm] slower than the TV (faster below 0), each time off
     * by up to [jitterMs] either way, at [phase] of a refresh from the TV's refreshes; it drops every [dropEvery]th frame.
     * The TV refreshes [refreshesEach] times a frame (2: a 25 fps film on a 50 Hz TV). The screen shows a frame at its first
     * refresh from the time it's given, and only the newest of those due at one refresh; the pacer hears of a frame shown
     * three frames later. The pacer is told of the frames missing before each one ([FrameGaps.between]) unless
     * [toldMissing] is false. [perFrame]: the rule before, each frame judged alone, for comparison.
     *
     * Counted from the second second on: the first frames go out on Media3's times until one has been seen on the screen.
     */
    private class Model(
        val jitterMs: Double = 0.0,
        val phase: Double = 0.0,
        val driftPpm: Double = 0.0,
        val dropEvery: Int = 0,
        val refreshesEach: Int = 1,
        val perFrame: Boolean = false,
        val toldMissing: Boolean = true,
        seed: Long = 1,
    ) {
        val tvNs = FILM_NS / refreshesEach
        val pacer = FramePacer()
        private val random = java.util.Random(seed)
        private val sent = ArrayList<Long>()
        /** Refresh → the frame shown at it. */
        val shown = java.util.TreeMap<Long, Int>()
        /** Frames Media3 dropped, with a frame after them. */
        var dropped = 0
        var skipped = 0
        /** Frames sent for a refresh another frame was sent for later: only that one shows. */
        var replaced = 0
        /** The furthest a frame was put from Media3's time for it, in refreshes, after the first second. */
        var furthest = 0.0
        private var oldRefresh = 0L
        private var oldLast = 0L

        fun play(frames: Int): Model {
            val base = 1_000L * FILM_NS + (phase * tvNs).toLong()
            var missing = 0
            for (k in 0 until frames) {
                val release = base + (k * FILM_NS * (1 + driftPpm * 1e-6)).toLong() +
                    ((random.nextDouble() * 2 - 1) * jitterMs * 1e6).toLong()
                val counted = k >= WARM
                if (dropEvery > 0 && k % dropEvery == dropEvery - 1) {
                    if (counted && k < frames - 1) dropped++
                    missing++
                    continue
                }
                val out = if (perFrame) oldPaced(release)
                    else pacer.paced(release, true, tvNs, 1, FILM_NS, if (toldMissing) missing else 0)
                missing = 0
                if (!perFrame && pacer.move == FramePacer.SAME) {
                    if (counted) skipped++
                    continue
                }
                sent += out
                val refresh = Math.floorDiv(out + tvNs - 1, tvNs)
                val before = shown.put(refresh, k)
                if (before != null && before >= WARM) replaced++
                if (counted) furthest = maxOf(furthest, abs(refresh * tvNs - release).toDouble() / tvNs)
                if (sent.size >= 3) {
                    val heard = Math.floorDiv(sent[sent.size - 3] + tvNs - 1, tvNs) * tvNs
                    if (perFrame) oldRefresh = heard else pacer.rendered(heard, true, tvNs, 1)
                }
            }
            return this
        }

        /** Frames lost: two sent for one refresh (only one shows), or not sent at all. */
        val lost: Int get() = replaced + skipped

        /** Times a frame stayed on the screen longer than a frame lasts: a frame shown twice. */
        val repeats: Int get() = shown.entries.zipWithNext()
            .count { (a, b) -> a.value >= WARM && b.key - a.key > refreshesEach }

        /** FramePacer.paced as it was: each frame moved a refresh when it alone was 0.6 of one off. */
        private fun oldPaced(release: Long): Long {
            val p = tvNs
            if (oldRefresh == 0L) {
                oldLast = 0L
                return release
            }
            var target: Long
            if (oldLast == 0L || abs(release - oldLast) > 3 * p) {
                target = oldRefresh + Math.round((release - oldRefresh).toDouble() / p) * p
            } else {
                val off = Math.floorMod(oldLast - oldRefresh, p)
                target = oldLast - (if (off > p / 2) off - p else off) + p
                val behind = release - target
                if (behind > 0.6 * p) target += p else if (behind < -0.6 * p) target -= p
            }
            oldLast = target
            return target - p / 2
        }
    }

    @Test
    fun aWobbleNearHalfARefreshLosesNoFrames() {
        // Media3's times 10 to 15 ms either way of their mean, near half a refresh from the TV's: judged one frame at a
        // time, one frame in seven or eight was lost; averaged, next to none.
        for ((jitter, phase) in listOf(10.0 to 0.45, 15.0 to 0.5, 15.0 to 0.25, 12.0 to 0.55)) {
            for (seed in 1L..3L) {
                val now = Model(jitterMs = jitter, phase = phase, seed = seed).play(FRAMES)
                val case = "±$jitter ms at $phase of a refresh, seed $seed"
                assertTrue("$case: ${now.lost} lost", now.lost <= FRAMES / 500)
                assertTrue("$case: ${now.repeats} shown twice", now.repeats <= FRAMES / 500)
                assertTrue("$case: ${now.furthest} refreshes off", now.furthest < 1.5)
            }
        }
        val before = Model(jitterMs = 15.0, phase = 0.5, perFrame = true).play(FRAMES)
        assertTrue("${before.lost} lost before", before.lost >= FRAMES / 10)
        assertTrue("${before.repeats} shown twice before", before.repeats >= FRAMES / 10)
        assertTrue(Model(jitterMs = 10.0, phase = 0.45, perFrame = true).play(FRAMES).lost >= FRAMES / 12)
    }

    @Test
    fun aFilmNotQuiteAtTheTvsRateIsStillKeptInStep() {
        // 0.1% apart, 20 frames over 20,000, with a wobble: each of them taken up by one move, and no more.
        val drift = FRAMES / 1000
        val slower = Model(jitterMs = 5.0, phase = 0.4, driftPpm = 1000.0).play(FRAMES)
        assertTrue("${slower.repeats} shown twice", slower.repeats in drift - 1..drift + 1)
        assertEquals(0, slower.lost)
        assertTrue("${slower.furthest} refreshes off", slower.furthest < 1.5)
        // A 24 fps film on a 23.976 Hz TV: a frame left out now and then (skipped, not sent), none shown twice.
        val faster = Model(jitterMs = 5.0, phase = 0.4, driftPpm = -1000.0).play(FRAMES)
        assertTrue("${faster.skipped} skipped", faster.skipped in drift - 1..drift + 1)
        assertEquals(faster.skipped, faster.lost)
        assertTrue("${faster.repeats} shown twice", faster.repeats <= 1)
        assertTrue("${faster.furthest} refreshes off", faster.furthest < 1.5)
    }

    @Test
    fun aFrameMedia3DroppedLeavesExactlyOneGap() {
        // (A 15 ms wobble right at half a refresh moves a frame now and then by itself: aWobbleNearHalfARefreshLosesNoFrames.)
        for (jitter in listOf(0.0, 10.0)) {
            for (phase in listOf(0.0, 0.3, 0.5, 0.7)) {
                val run = Model(jitterMs = jitter, phase = phase, dropEvery = 37).play(FRAMES)
                val case = "±$jitter ms at $phase of a refresh"
                assertEquals(case, run.dropped, run.repeats)
                assertEquals(case, 0, run.lost)
                assertTrue("$case: ${run.furthest} refreshes off", run.furthest < 1.5)
            }
        }
        // Not told of the frame missing (its length not known yet): Media3's time alone, a refresh on, is followed.
        for (phase in listOf(0.0, 0.3, 0.5, 0.7)) {
            val run = Model(phase = phase, dropEvery = 37, toldMissing = false).play(FRAMES)
            assertEquals("at $phase of a refresh", run.dropped, run.repeats)
            assertEquals("at $phase of a refresh", 0, run.lost)
        }
    }

    @Test
    fun aTvAtTwiceTheFilmsRateShowsEachFrameForTwoRefreshes() {
        // A 25 fps film on a 50 Hz TV (here 23.976 on 47.952): each frame two refreshes, even with a wobble.
        val run = Model(jitterMs = 6.0, phase = 0.3, refreshesEach = 2).play(FRAMES)
        assertEquals(0, run.lost)
        assertEquals(0, run.repeats)
        assertTrue(run.shown.keys.toList().takeLast(1000).zipWithNext().all { (a, b) -> b - a == 2L })
        // A frame Media3 dropped: one gap of four refreshes, followed at once.
        val dropping = Model(phase = 0.3, refreshesEach = 2, dropEvery = 50).play(FRAMES)
        assertEquals(0, dropping.lost)
        assertEquals(dropping.dropped, dropping.repeats)
    }

    @Test
    fun eachFramesMoveIsSaid() {
        val p = FILM_NS
        val pacer = FramePacer()
        assertEquals(1_000L, pacer.paced(1_000L, true, p, 1))
        assertEquals(FramePacer.MEDIA3, pacer.move)
        pacer.rendered(100 * p, true, p, 1)
        pacer.paced(101 * p, true, p, 1)
        assertEquals(FramePacer.START, pacer.move)
        pacer.paced(102 * p, true, p, 1)
        assertEquals(FramePacer.NEXT, pacer.move)
        // A frame missing in the film's times: the next goes a refresh further on, whatever Media3's time for it.
        assertEquals(104 * p - p / 2, pacer.paced(103 * p + p / 3, true, p, 1, p, missing = 1))
        assertEquals(FramePacer.FOLLOWED, pacer.move)
        // Media3's time a whole refresh on, with no frame missing: followed at once too.
        assertEquals(106 * p - p / 2, pacer.paced(106 * p, true, p, 1))
        assertEquals(FramePacer.FOLLOWED, pacer.move)
        assertEquals(107 * p - p / 2, pacer.paced(107 * p, true, p, 1))
        assertEquals(FramePacer.NEXT, pacer.move)
        // Media3's time back a whole frame: on the refresh of the frame before, so it isn't sent ...
        val skipped = pacer.paced(107 * p, true, p, 1)
        assertEquals(FramePacer.SAME, pacer.move)
        assertEquals(107 * p - p / 2, skipped)
        // ... and the next goes to the refresh after the last one sent.
        assertEquals(108 * p - p / 2, pacer.paced(108 * p, true, p, 1))
        assertEquals(FramePacer.NEXT, pacer.move)
        // Moved a refresh later only once the frames have been late for a while, not for one frame.
        var t = 108 * p
        val moves = ArrayList<Int>()
        repeat(80) {
            t += p
            pacer.paced(t + (0.7 * p).toLong(), true, p, 1)
            moves += pacer.move
        }
        assertEquals(FramePacer.NEXT, moves.first())
        assertEquals(1, moves.count { it == FramePacer.LATER })
        assertTrue(moves.all { it == FramePacer.NEXT || it == FramePacer.LATER })
        // A new decoder starts afresh.
        pacer.reset()
        assertEquals(t, pacer.paced(t, true, p, 1))
        assertEquals(FramePacer.MEDIA3, pacer.move)
    }

    private companion object {
        val FILM_NS = (1e9 * 1001 / 24000).toLong()
        const val SENT_EARLY_NS = 45_000_000L
        const val HEARD_AFTER_NS = 3_000_000L
        /** Frames in each run of the model: about 14 minutes of film. */
        const val FRAMES = 20_000
        /** The model's first second, before frames are paced, isn't counted. */
        const val WARM = 24
    }
}
