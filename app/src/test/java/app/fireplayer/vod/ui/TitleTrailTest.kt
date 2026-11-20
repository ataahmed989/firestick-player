package app.fireplayer.vod.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleTrailTest {

    @Test
    fun onlyTheTopThreeKeepTheirPages() {
        val trail = TitleTrail<String>(alive = 3, kept = 30)
        assertEquals(emptyList<String>(), trail.push("a"))
        assertEquals(emptyList<String>(), trail.push("b"))
        assertEquals(emptyList<String>(), trail.push("c"))
        // A fourth: the first leaves the alive window (its page is let go; the entry stays for Back).
        assertEquals(listOf("a"), trail.push("d"))
        assertFalse(trail.isAlive("a"))
        assertTrue(trail.isAlive("b"))
        assertEquals(listOf("b"), trail.push("e"))
        assertEquals(5, trail.size)
        assertEquals("e", trail.top)
    }

    @Test
    fun backWalksDownTheTrail() {
        val trail = TitleTrail<String>(alive = 3, kept = 30)
        listOf("a", "b", "c", "d").forEach { trail.push(it) }
        assertEquals("d", trail.pop())
        assertEquals("c", trail.top)
        // "a" is back within the window: its page is made again when Back reaches it.
        assertTrue(trail.isAlive("a"))
        assertEquals("c", trail.pop())
        assertEquals("b", trail.pop())
        assertEquals("a", trail.pop())
        assertNull(trail.pop())
        assertTrue(trail.isEmpty)
    }

    @Test
    fun aLongTrailForgetsItsOldestEntries() {
        val trail = TitleTrail<Int>(alive = 3, kept = 5)
        for (i in 1..5) trail.push(i)
        // The sixth: 1 is forgotten, and 3 leaves the alive window.
        assertEquals(listOf(1, 3), trail.push(6))
        assertEquals(listOf(2, 3, 4, 5, 6), trail.entries)
    }

    @Test
    fun clearGivesEveryEntryTopFirst() {
        val trail = TitleTrail<String>(alive = 3, kept = 30)
        listOf("a", "b", "c").forEach { trail.push(it) }
        assertEquals(listOf("c", "b", "a"), trail.clear())
        assertTrue(trail.isEmpty)
    }
}
