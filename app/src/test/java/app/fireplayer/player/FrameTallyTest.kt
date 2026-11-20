package app.fireplayer.player

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameTallyTest {

    @Test
    fun theFirstSampleIsOnlyWhereCountingStarts() {
        val t = FrameTally()
        t.sample(5_000, dropped = 10, rendered = 1_000, shownTwice = 3, sameRefresh = 2, neverDecoded = 1)
        assertEquals(0L, t.minuteLost())
        assertEquals(0L, t.minute(FrameTally.RENDERED))
        assertEquals(0L, t.addedLost)
    }

    @Test
    fun eachKindIsKeptApart() {
        val t = FrameTally()
        t.sample(5_000, 10, 1_000, 3, 2, 1)
        t.sample(6_000, 12, 1_024, 3, 3, 2)
        assertEquals(2L, t.minute(FrameTally.DROPPED))
        assertEquals(24L, t.minute(FrameTally.RENDERED))
        assertEquals(0L, t.minute(FrameTally.TWICE))
        assertEquals(1L, t.minute(FrameTally.SAME))
        assertEquals(1L, t.minute(FrameTally.NEVER))
        assertEquals(4L, t.addedLost)
        assertEquals(24L, t.addedRendered)
        assertEquals("dropped 2 · shown twice 0 · same refresh 1 · never decoded 1", t.minuteLine())
    }

    @Test
    fun onlyTheLastMinuteCounts() {
        val t = FrameTally()
        // A frame dropped every second for 100 seconds: the last minute has 60 of them.
        for (s in 0..100) t.sample(s * 1_000L, s.toLong(), s * 24L, 0, 0, 0)
        assertEquals(60L, t.minute(FrameTally.DROPPED))
        assertEquals(60 * 24L, t.minute(FrameTally.RENDERED))
        // Nothing for two minutes (the screen was stopped): only what came after.
        t.sample(230_000, 102, 2_424, 0, 0, 0)
        assertEquals(2L, t.minute(FrameTally.DROPPED))
        // Two samples in the same second add up.
        t.sample(230_400, 103, 2_448, 0, 0, 0)
        assertEquals(3L, t.minute(FrameTally.DROPPED))
    }

    @Test
    fun media3sCountsFromANewDecoderStartAgain() {
        val t = FrameTally()
        t.sample(0, 50, 5_000, 0, 0, 0)
        // A new decoder session (another track, the next episode): its counts from 0, all of them new.
        t.sample(1_000, 2, 30, 0, 0, 0, fresh = true)
        assertEquals(2L, t.minute(FrameTally.DROPPED))
        assertEquals(30L, t.minute(FrameTally.RENDERED))
        // A count lower than the last is taken the same way, whoever says so.
        t.sample(2_000, 1, 20, 0, 0, 0)
        assertEquals(3L, t.minute(FrameTally.DROPPED))
        assertEquals(50L, t.minute(FrameTally.RENDERED))
    }
}
