package app.fireplayer.player

import app.fireplayer.player.FreezeWatch.Event
import app.fireplayer.player.FreezeWatch.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FreezeWatchTest {

    private var t = 1_000_000L
    private val watch = FreezeWatch { t }

    // The film as the player reports it; ticks move the clock on 200 ms, as PlayerActivity's do.
    private var playing = true
    private var waiting = false
    private var held = false
    private var position = 41 * 60_000L
    private var ahead = 75_000L
    private var duration = 2 * 3_600_000L
    private var live = false
    private var seekable = true
    private var switchAt = 0L
    private var silent = 0L

    private fun sample() = FreezeWatch.Sample(
        playing = playing,
        waiting = waiting,
        held = held,
        positionMs = position,
        bufferedMs = position + ahead,
        durationMs = duration,
        live = live,
        seekable = seekable,
        switchAtMs = switchAt,
        threadSilentMs = silent,
    )

    /** One tick: the clock moves on 200 ms, the film by [filmMs], and the watch is asked. */
    private fun tick(filmMs: Long = 0L): Event? {
        t += 200
        position += filmMs
        return watch.check(sample())
    }

    /** Ticks for [ms], the film moving [filmPerTick] each; fails on any event. */
    private fun quiet(ms: Long, filmPerTick: Long = 0L) {
        repeat((ms / 200).toInt()) { i -> tick(filmPerTick)?.let { fail("Unexpected ${describe(it)} after ${(i + 1) * 200} ms") } }
    }

    /** Ticks until something happens (within [maxMs]); returns it and how long it took. */
    private fun until(maxMs: Long = 120_000L, filmPerTick: Long = 0L): Pair<Event, Long> {
        val from = t
        while (t - from < maxMs) tick(filmPerTick)?.let { return it to t - from }
        fail("Nothing within $maxMs ms")
        throw AssertionError()
    }

    private fun describe(e: Event) = when (e) {
        is Event.Act -> "Act(${e.step})"
        is Event.Recovered -> "Recovered(${e.after})"
    }

    private fun act(e: Event): Event.Act = e as? Event.Act ?: throw AssertionError("Expected an Act, got ${describe(e)}")

    /**
     * Ticks until [step] comes, [afterMs] after the film was last seen. [fresh]: nothing marks where it was yet (the first
     * tick does, and the wait counts from there); else it was moving until now, last seen up to two ticks ago.
     */
    private fun expectAct(step: Step, afterMs: Long, fresh: Boolean = true): Event.Act {
        val (e, took) = until()
        val a = act(e)
        assertEquals(step, a.step)
        if (fresh) assertEquals(afterMs + 200, took) else assertTrue("took $took", took in afterMs - 400..afterMs)
        return a
    }

    /** What PlayerActivity does for a pause and play: held for the second's gap, then playing again. */
    private fun ourPausePlay() {
        held = true
        playing = false
        quiet(1_000)
        held = false
        playing = true
    }

    @Test
    fun aFilmStandingStillWhilePlayingIsPausedAndPlayedAt5s() {
        val a = expectAct(Step.PAUSE_PLAY, FreezeWatch.STUCK_MS)
        assertEquals(position, a.positionMs)
        assertEquals(FreezeWatch.STUCK_MS, a.stuckMs)
        assertEquals(75_000L, a.aheadMs)
        assertTrue(!a.threadSilent)
    }

    @Test
    fun aPlayingFilmIsNeverTouched() {
        quiet(10 * 60_000L, filmPerTick = 200)
        // Half speed, as a struggling decoder might manage.
        quiet(10 * 60_000L, filmPerTick = 100)
    }

    @Test
    fun movementUnderAQuarterSecondIsStandingStill() {
        val from = t
        var e: Event? = null
        var i = 0
        while (e == null && t - from < 60_000) {
            position += if (i++ % 2 == 0) 100 else -100
            e = tick()
        }
        assertEquals(Step.PAUSE_PLAY, act(e!!).step)
        assertEquals(FreezeWatch.STUCK_MS + 200, t - from)
    }

    @Test
    fun notPlayingNeverCounts() {
        playing = false
        quiet(3_600_000L)
        waiting = true
        quiet(3_600_000L)
        waiting = false
        playing = true
        held = true
        quiet(3_600_000L)
    }

    @Test
    fun anyBreakStartsTheWaitAgain() {
        quiet(4_000)
        playing = false
        quiet(1_000)
        playing = true
        quiet(4_000)
        watch.jumped()
        quiet(4_000)
        ahead = 2_000
        quiet(1_000)
        ahead = 75_000
        quiet(4_000)
        held = true
        quiet(1_000)
        held = false
        quiet(4_000)
    }

    @Test
    fun littleLoadedAheadIsLeftToTheSpinner() {
        ahead = 1_000
        quiet(60_000)
        ahead = FreezeWatch.MIN_AHEAD_MS
        expectAct(Step.PAUSE_PLAY, FreezeWatch.STUCK_MS)
    }

    @Test
    fun theEndLiveAndUnknownLengthsAreNotWatched() {
        position = duration - FreezeWatch.END_GUARD_MS
        quiet(60_000)
        position = 60_000
        live = true
        quiet(60_000)
        live = false
        duration = -9_223_372_036_854_775_807L // C.TIME_UNSET
        quiet(60_000)
        duration = 0
        quiet(60_000)
    }

    @Test
    fun soundComingBackAfterARefreshSwitchIsWaitedOut() {
        val t0 = t
        switchAt = t0
        playing = false
        quiet(2_000)
        playing = true
        // Nothing before the sound's window and the stuck time have both passed.
        val (e, _) = until()
        assertEquals(Step.PAUSE_PLAY, act(e).step)
        assertEquals(FreezeWatch.SWITCH_SOUND_MS + FreezeWatch.STUCK_MS, t - t0)
    }

    @Test
    fun stepsGoFurtherWhileNothingHelps() {
        val first = expectAct(Step.PAUSE_PLAY, FreezeWatch.STUCK_MS)
        val frozeAt = t - first.stuckMs
        ourPausePlay()
        val jump = expectAct(Step.JUMP, FreezeWatch.STEP_WAIT_MS)
        assertEquals(t - frozeAt, jump.stuckMs)
        watch.jumped()
        expectAct(Step.NEW_PLAYER, FreezeWatch.STEP_WAIT_MS)
        watch.jumped()
        val message = expectAct(Step.MESSAGE, FreezeWatch.STEP_WAIT_MS)
        assertEquals(t - frozeAt, message.stuckMs)
        // The message is up: nothing more, whatever the film does.
        quiet(3_600_000L)
        silent = 60_000
        quiet(60_000)
        silent = 0
        watch.reset()
        expectAct(Step.PAUSE_PLAY, FreezeWatch.STUCK_MS)
    }

    @Test
    fun aStepThatWorksIsReportedAndKept() {
        val first = expectAct(Step.PAUSE_PLAY, FreezeWatch.STUCK_MS)
        val frozeAt = t - first.stuckMs
        ourPausePlay()
        val (e, took) = until(filmPerTick = 200)
        val back = e as Event.Recovered
        assertEquals(Step.PAUSE_PLAY, back.after)
        assertEquals(t - frozeAt, back.stuckMs)
        assertTrue(took <= 1_600)
        quiet(20_000, filmPerTick = 200)
        // Frozen again soon after: the next step, after the usual wait for a new freeze.
        val again = expectAct(Step.JUMP, FreezeWatch.STUCK_MS, fresh = false)
        assertEquals(FreezeWatch.STUCK_MS, again.stuckMs)
    }

    @Test
    fun aMinuteOfPlayingStartsTheStepsAgain() {
        expectAct(Step.PAUSE_PLAY, FreezeWatch.STUCK_MS)
        ourPausePlay()
        assertTrue(until(filmPerTick = 200).first is Event.Recovered)
        quiet(FreezeWatch.HEALTHY_MS + 1_000, filmPerTick = 200)
        expectAct(Step.PAUSE_PLAY, FreezeWatch.STUCK_MS, fresh = false)
    }

    @Test
    fun anUnseekableFilmGoesFromPausingStraightToANewPlayer() {
        seekable = false
        expectAct(Step.PAUSE_PLAY, FreezeWatch.STUCK_MS)
        ourPausePlay()
        expectAct(Step.NEW_PLAYER, FreezeWatch.STEP_WAIT_MS)
    }

    @Test
    fun aSilentPlayerThreadGetsANewPlayer() {
        // Moving, so only the thread could set it off.
        silent = FreezeWatch.THREAD_SILENT_MS - 1
        quiet(10_000, filmPerTick = 200)
        silent = FreezeWatch.THREAD_SILENT_MS
        val a = act(tick(200)!!)
        assertEquals(Step.NEW_PLAYER, a.step)
        assertTrue(a.threadSilent)
        assertEquals(FreezeWatch.THREAD_SILENT_MS, a.stuckMs)
        // Loading counts too: a jump sent to a blocked thread leaves the player loading.
        silent = 0
        watch.jumped()
        playing = false
        waiting = true
        silent = FreezeWatch.THREAD_SILENT_MS
        assertEquals(Step.MESSAGE, act(tick()!!).step)
    }

    @Test
    fun aSilentThreadWhilePausedOrHeldIsLeftAlone() {
        silent = 60_000
        playing = false
        quiet(60_000)
        playing = true
        held = true
        quiet(60_000)
        held = false
        assertEquals(Step.NEW_PLAYER, act(tick()!!).step)
    }

    @Test
    fun aJumpAfterAStepIsNotTakenForPlaying() {
        expectAct(Step.PAUSE_PLAY, FreezeWatch.STUCK_MS)
        ourPausePlay()
        watch.jumped()
        position += 60_000
        expectAct(Step.JUMP, FreezeWatch.STEP_WAIT_MS)
    }

    @Test
    fun goingBackwardsWithoutAJumpIsNotPlaying() {
        expectAct(Step.PAUSE_PLAY, FreezeWatch.STUCK_MS)
        ourPausePlay()
        // Back a second at a time: no film played, so no recovery.
        quiet(5_000, filmPerTick = -1_000)
        // Still waiting on the step taken: standing still again gets the next one.
        expectAct(Step.JUMP, FreezeWatch.STEP_WAIT_MS, fresh = false)
    }

    @Test
    fun nothingAfterResetUntilTheFilmStandsStillAgain() {
        expectAct(Step.PAUSE_PLAY, FreezeWatch.STUCK_MS)
        watch.reset()
        quiet(60_000, filmPerTick = 200)
        assertNull(tick(200))
    }
}
