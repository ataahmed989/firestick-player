package app.fireplayer.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The guide's lists kept in memory: what is kept, for how long, and what a write does to them. */
class ScopeListsTest {

    private fun list(n: Int) = List(n) { it }

    @Test fun aListIsKeptForTheCountOfWritesItWasReadAt() {
        val lists = ScopeLists<String, Int>()
        val group = list(40)
        lists.put("group", 7, group)
        assertSame(group, lists.get("group", 7))
        // Something was written since (a star, a playlist update): what was read before is no good.
        assertNull(lists.get("group", 8))
        // And asked at an older count than it was read at, it isn't that count's list either.
        assertNull(lists.get("group", 6))
        assertNull(lists.get("other", 7))
    }

    @Test fun nothingIsKeptBeforeTheFirstRead() {
        val lists = ScopeLists<String, Int>()
        assertNull(lists.get("group", 0))
        assertEquals(0, lists.size)
    }

    @Test fun aNewCountDropsEverythingReadAtTheOldOne() {
        val lists = ScopeLists<String, Int>()
        lists.put("a", 1, list(10))
        lists.put("b", 1, list(10))
        lists.put("all", 1, list(30_000))
        assertEquals(3, lists.size)
        val fresh = list(12)
        lists.put("a", 2, fresh)
        assertEquals(1, lists.size)
        assertSame(fresh, lists.get("a", 2))
        assertNull(lists.get("b", 2))
        assertNull(lists.get("all", 2))
        // The old count's lists don't come back with the old count.
        assertNull(lists.get("b", 1))
    }

    @Test fun onlyTheLastLongListIsKept() {
        val lists = ScopeLists<String, Int>(smallMax = 100)
        val one = list(30_000)
        val two = list(2_000)
        lists.put("playlist one", 1, one)
        assertSame(one, lists.get("playlist one", 1))
        lists.put("playlist two", 1, two)
        assertNull(lists.get("playlist one", 1))
        assertSame(two, lists.get("playlist two", 1))
        assertEquals(1, lists.size)
    }

    @Test fun theSmallListsUsedLongestAgoGoFirst() {
        val lists = ScopeLists<String, Int>(smallMax = 100, smallKept = 3, smallTotal = 1_000)
        lists.put("a", 1, list(10))
        lists.put("b", 1, list(10))
        lists.put("c", 1, list(10))
        // "a" is looked at again: "b" is now the one used longest ago.
        lists.get("a", 1)
        lists.put("d", 1, list(10))
        assertNull(lists.get("b", 1))
        assertEquals(10, lists.get("a", 1)?.size)
        assertEquals(10, lists.get("c", 1)?.size)
        assertEquals(10, lists.get("d", 1)?.size)
    }

    @Test fun theSmallListsStayWithinTheirTotal() {
        val lists = ScopeLists<String, Int>(smallMax = 100, smallKept = 8, smallTotal = 250)
        lists.put("a", 1, list(100))
        lists.put("b", 1, list(100))
        lists.put("c", 1, list(100))
        // 300 channels between them is over 250: the oldest goes.
        assertNull(lists.get("a", 1))
        assertEquals(2, lists.size)
        // One list over the total by itself is still kept (the one just read is never dropped).
        val alone = ScopeLists<String, Int>(smallMax = 100, smallKept = 8, smallTotal = 50)
        alone.put("a", 1, list(80))
        assertEquals(80, alone.get("a", 1)?.size)
    }

    @Test fun aListReadAgainReplacesItsOldCopyWhateverItsSize() {
        val lists = ScopeLists<String, Int>(smallMax = 100)
        lists.put("group", 1, list(500))
        // The same key, now short (channels were hidden): it isn't kept twice.
        val short = list(20)
        lists.put("group", 1, short)
        assertSame(short, lists.get("group", 1))
        assertEquals(1, lists.size)
        val long = list(700)
        lists.put("group", 1, long)
        assertSame(long, lists.get("group", 1))
        assertEquals(1, lists.size)
    }

    @Test fun clearLetsGoOfEverything() {
        val lists = ScopeLists<String, Int>()
        lists.put("a", 3, list(10))
        lists.put("all", 3, list(30_000))
        lists.clear()
        assertEquals(0, lists.size)
        assertNull(lists.get("a", 3))
        assertNull(lists.get("all", 3))
    }
}
