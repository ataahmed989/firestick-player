package app.fireplayer.live.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveSleepTest {
    private val elapsed = 5_000_000L
    private val wall = 1_763_400_000_000L

    @Test fun minutesRunFromNow() {
        assertEquals(elapsed + 30 * 60_000L, LiveSleep.endsAt(30, elapsed, wall, null))
        assertEquals(elapsed + 120 * 60_000L, LiveSleep.endsAt(120, elapsed, wall, wall + 60_000L))
    }

    @Test fun offIsNoTimer() = assertEquals(0L, LiveSleep.endsAt(LiveSleep.OFF, elapsed, wall, wall + 60_000L))

    @Test fun endOfThisProgrammeIsTheGuidesEndTimeOnThePlayersClock() {
        // The programme ends in 23 minutes (wall clock): the timer runs out 23 minutes from now on the other clock.
        assertEquals(elapsed + 23 * 60_000L, LiveSleep.endsAt(LiveSleep.END_OF_PROGRAMME, elapsed, wall, wall + 23 * 60_000L))
    }

    @Test fun endOfThisProgrammeNeedsAKnownEndStillToCome() {
        assertEquals(0L, LiveSleep.endsAt(LiveSleep.END_OF_PROGRAMME, elapsed, wall, null))
        assertEquals(0L, LiveSleep.endsAt(LiveSleep.END_OF_PROGRAMME, elapsed, wall, wall))
        assertEquals(0L, LiveSleep.endsAt(LiveSleep.END_OF_PROGRAMME, elapsed, wall, wall - 1L))
    }

    @Test fun theLabelCountsMinutesLeftRoundedUp() {
        assertEquals("Off", LiveSleep.label(0L, elapsed))
        val ends = elapsed + 30 * 60_000L
        assertEquals("30 min", LiveSleep.label(ends, elapsed))
        assertEquals("30 min", LiveSleep.label(ends, elapsed + 59_999L))
        assertEquals("29 min", LiveSleep.label(ends, elapsed + 60_000L))
        assertEquals("1 min", LiveSleep.label(ends, ends - 30_000L))
        assertEquals("1 min", LiveSleep.label(ends, ends))
    }

    @Test fun theNextCheckIsWhenTheLabelChangesOrTheTimeIsUp() {
        val ends = elapsed + 125_000L
        // 2 min 5 s left ("3 min"): 5 s until it reads "2 min".
        assertEquals(5_000L, LiveSleep.nextCheckMs(ends, elapsed))
        assertEquals("2 min", LiveSleep.label(ends, elapsed + 5_000L))
        // Exactly 2 min left ("2 min"): a whole minute until "1 min".
        assertEquals(60_000L, LiveSleep.nextCheckMs(ends, elapsed + 5_000L))
        // In the last minute: when it runs out.
        assertEquals(30_000L, LiveSleep.nextCheckMs(ends, ends - 30_000L))
        // Never a busy loop, and nothing left to wait for once it's up.
        assertEquals(250L, LiveSleep.nextCheckMs(ends, ends - 10L))
        assertEquals(0L, LiveSleep.nextCheckMs(ends, ends))
    }
}
