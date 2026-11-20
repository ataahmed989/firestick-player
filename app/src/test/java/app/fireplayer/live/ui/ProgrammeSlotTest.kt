package app.fireplayer.live.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgrammeSlotTest {
    private val min = 60_000L
    private val hour = 60 * min
    /** 9:00 on some day, UTC. */
    private val nine = 20_000L * 24 * hour + 9 * hour

    @Test fun theHalfHourTheClockIsIn() {
        val s = ProgrammeSlot.of(nine + 12 * min, null, 0)
        assertEquals(nine, s.start)
        assertEquals(nine + 30 * min, s.end)
        val later = ProgrammeSlot.of(nine + 47 * min, null, 0)
        assertEquals(nine + 30 * min, later.start)
        assertEquals(nine + 60 * min, later.end)
    }

    @Test fun onTheHalfHourTheNewSlotHasStarted() {
        val s = ProgrammeSlot.of(nine + 30 * min, null, 0)
        assertEquals(nine + 30 * min, s.start)
        assertEquals(nine + 60 * min, s.end)
    }

    @Test fun endsAtTheNextProgrammeWhenItStartsBeforeTheSlotIsOver() {
        val s = ProgrammeSlot.of(nine + 5 * min, nine + 20 * min, 0)
        assertEquals(nine, s.start)
        assertEquals(nine + 20 * min, s.end)
    }

    @Test fun aNextProgrammeAfterTheSlotOrAlreadyStartedChangesNothing() {
        assertEquals(nine + 30 * min, ProgrammeSlot.of(nine + 5 * min, nine + 45 * min, 0).end)
        assertEquals(nine + 30 * min, ProgrammeSlot.of(nine + 5 * min, nine + 30 * min, 0).end)
        // Listings behind the clock (a "next" programme that should be on by now) don't end the slot early.
        assertEquals(nine + 30 * min, ProgrammeSlot.of(nine + 5 * min, nine + 2 * min, 0).end)
        assertEquals(nine + 30 * min, ProgrammeSlot.of(nine + 5 * min, nine + 5 * min, 0).end)
    }

    @Test fun halfHoursAreTheViewersOwnClocks() {
        // A clock 5 h 45 min ahead of UTC (Kathmandu): 9:12 UTC is 14:57 there, in the 14:30 slot = 8:45 to 9:15 UTC.
        val offset = (5 * hour + 45 * min).toInt()
        val s = ProgrammeSlot.of(nine + 12 * min, null, offset)
        assertEquals(nine - 15 * min, s.start)
        assertEquals(nine + 15 * min, s.end)
        // Behind UTC by whole hours (New York, winter): the same half hours.
        val ny = ProgrammeSlot.of(nine + 12 * min, null, (-5 * hour).toInt())
        assertEquals(nine, ny.start)
    }

    @Test fun beforeNineteenSeventyStillRoundsDown() {
        val s = ProgrammeSlot.of(-10 * min, null, 0)
        assertEquals(-30 * min, s.start)
        assertEquals(0L, s.end)
    }
}
