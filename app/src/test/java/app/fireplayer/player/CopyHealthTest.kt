package app.fireplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A copy that wasn't the whole film here ([CopyHealth.notWhole]): kept with its other counters, readable both ways. */
class CopyHealthTest {

    @Test
    fun aCopyThatWasntTheWholeFilmIsKeptSoAndIsNeverPickedByItself() {
        val short = CopyHealth(watchedMs = 1_300, renderedFrames = 30, at = 1_764_000_000_000L, notWhole = 1)
        val text = CopyHealth.encode(short)
        assertEquals("1300|0|0|0|0|0|30|1764000000000|0|0|1", text)
        assertEquals(short, CopyHealth.decode(text))
        // An older version reads the fields it knows (the first ten) and ignores the last.
        assertEquals(short.copy(notWhole = 0), CopyHealth.decode(text.split('|').take(10).joinToString("|")))
        assertTrue(short.setAside)
        assertEquals("shorter than the film here", short.summary())
        // A later play, however long, doesn't make it the whole film.
        assertEquals(1, (short + CopyHealth(watchedMs = 60 * 60_000L, renderedFrames = 90_000)).notWhole)
        assertEquals(2, (short + CopyHealth(watchedMs = 900, notWhole = 1)).notWhole)
    }

    @Test
    fun recordsKeptBeforeItReadNoneAndAreWrittenAsBefore() {
        assertEquals(0, CopyHealth.decode("600000|2|5000|0|1")!!.notWhole)
        assertEquals(0, CopyHealth.decode("600000|2|5000|0|1|10|9000|1764000000000|1|4")!!.notWhole)
        assertEquals("8000|0|0|0|0|0|0", CopyHealth.encode(CopyHealth(watchedMs = 8_000)))
        assertEquals("8000|0|0|0|0|0|0|0|1", CopyHealth.encode(CopyHealth(watchedMs = 8_000, noPicture = 1)))
        assertEquals("8000|0|0|0|0|5|0|7|0|5", CopyHealth.encode(CopyHealth(watchedMs = 8_000, droppedFrames = 5, at = 7, lateFrames = 5)))
        val dark = CopyHealth(watchedMs = 8_000, noPicture = 1)
        assertTrue(dark.setAside)
        assertFalse(CopyHealth(watchedMs = 8_000).setAside)
    }
}
