package app.fireplayer.live.ui

import app.fireplayer.live.ChannelGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.Executor

class GroupListsTest {

    @Test fun okPicksUpAndPutsDown() {
        val r = Reorder(listOf("a", "b", "c"))
        assertEquals(-1, r.press(1))
        assertEquals(1, r.moving)
        assertEquals("Moving", r.value(1))
        assertEquals("1", r.value(0))
        // Another one picked up puts the first down.
        assertEquals(1, r.press(2))
        assertEquals(2, r.moving)
        // OK on the one moving puts it down.
        assertEquals(2, r.press(2))
        assertEquals(-1, r.moving)
        assertEquals("3", r.value(2))
    }

    @Test fun upAndDownCarryTheEntryAndStopAtTheEnds() {
        val r = Reorder(listOf("a", "b", "c", "d"))
        r.press(1)
        assertEquals(2, r.carry(1))
        assertEquals(listOf("a", "c", "b", "d"), r.items)
        assertEquals(3, r.carry(1))
        assertEquals(-1, r.carry(1))
        assertEquals(listOf("a", "c", "d", "b"), r.items)
        assertEquals(3, r.moving)
        assertEquals(2, r.carry(-1))
        assertEquals(1, r.carry(-1))
        assertEquals(0, r.carry(-1))
        assertEquals(-1, r.carry(-1))
        assertEquals(listOf("b", "a", "c", "d"), r.items)
    }

    @Test fun nothingIsCarriedUntilPickedUp() {
        val r = Reorder(listOf("a", "b"))
        assertEquals(-1, r.carry(1))
        assertEquals(listOf("a", "b"), r.items)
    }

    @Test fun moveToTopPutsDownWhatWasMoving() {
        val r = Reorder(listOf("a", "b", "c", "d"))
        r.press(3)
        assertEquals(3, r.toTop(2))
        assertEquals(listOf("c", "a", "b", "d"), r.items)
        assertEquals(-1, r.moving)
        // The top one stays where it is.
        assertEquals(-1, r.toTop(0))
        assertEquals(listOf("c", "a", "b", "d"), r.items)
    }

    private fun group(id: String, hidden: Boolean = false) = ChannelGroup("p", id, id.uppercase(), 0, hidden)

    @Test fun hiddenGroupsComeAfterTheShownOnesInTheirOrder() {
        val groups = listOf(group("news"), group("adult", hidden = true), group("sport"), group("radio", hidden = true))
        assertEquals(listOf("news", "sport", "adult", "radio"), FavoriteGroups.order(groups).map { it.id })
    }

    @Test fun countsSayHowManyFavoritesAGroupHolds() {
        assertNull(FavoriteGroups.countText(0))
        assertEquals("1 favorite", FavoriteGroups.countText(1))
        assertEquals("12 favorites", FavoriteGroups.countText(12))
    }

    @Test fun starringAChannelCountsInItsGroup() {
        val counts = HashMap<String, Int>()
        FavoriteGroups.adjust(counts, "news", favorite = true)
        FavoriteGroups.adjust(counts, "news", favorite = true)
        FavoriteGroups.adjust(counts, "sport", favorite = true)
        assertEquals(mapOf("news" to 2, "sport" to 1), counts)
        FavoriteGroups.adjust(counts, "sport", favorite = false)
        assertEquals(mapOf("news" to 2), counts)
        // A channel without a group counts nowhere; a count never goes below none.
        FavoriteGroups.adjust(counts, null, favorite = true)
        FavoriteGroups.adjust(counts, "radio", favorite = false)
        assertEquals(mapOf("news" to 2), counts)
    }

    @Test fun assignFavoritesHeadsTheShownGroupsAndTheHiddenOnes() {
        // Two shown, one hidden: Groups, news, sport, Hidden groups, adult.
        val both = FavoriteGroups.Headings(shown = 2, total = 3)
        assertEquals(5, both.size)
        assertEquals(0, both.shownHeading)
        assertEquals(3, both.hiddenHeading)
        assertEquals(listOf(null, 0, 1, null, 2), (0 until both.size).map { both.groupAt(it) })
        // All shown: only the Groups heading.
        val shownOnly = FavoriteGroups.Headings(shown = 2, total = 2)
        assertEquals(-1, shownOnly.hiddenHeading)
        assertEquals(listOf(null, 0, 1), (0 until shownOnly.size).map { shownOnly.groupAt(it) })
        // All hidden: only the Hidden groups heading.
        val hiddenOnly = FavoriteGroups.Headings(shown = 0, total = 2)
        assertEquals(-1, hiddenOnly.shownHeading)
        assertEquals(listOf(null, 0, 1), (0 until hiddenOnly.size).map { hiddenOnly.groupAt(it) })
        // Nothing past the end.
        assertNull(both.groupAt(5))
        assertNull(both.groupAt(-1))
    }

    /** Runs its tasks only when told, one at a time, like the settings' worker thread. */
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runAll() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    @Test fun onlyTheNewestWaitingValueIsStored() {
        val q = Queue()
        val stored = ArrayList<Int>()
        val save = LatestSave<Int>(q) { stored += it }
        save.submit(1)
        save.submit(2)
        save.submit(3)
        assertEquals(1, q.tasks.size)
        q.runAll()
        assertEquals(listOf(3), stored)
        // Later values are stored again, after the one before.
        save.submit(4)
        q.runAll()
        assertEquals(listOf(3, 4), stored)
    }

    @Test fun aValueSentWhileStoringIsStoredAfterIt() {
        val q = Queue()
        val stored = ArrayList<Int>()
        lateinit var save: LatestSave<Int>
        save = LatestSave(q) { v ->
            stored += v
            if (v == 1) save.submit(2)
        }
        save.submit(1)
        q.runAll()
        assertEquals(listOf(1, 2), stored)
    }

    @Test fun storedAtOnceOnADirectExecutor() {
        val stored = ArrayList<String>()
        val save = LatestSave<String>(Executor { it.run() }) { stored += it }
        save.submit("a")
        save.submit("b")
        assertEquals(listOf("a", "b"), stored)
    }
}
