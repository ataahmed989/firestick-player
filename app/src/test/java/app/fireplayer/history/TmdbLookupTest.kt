package app.fireplayer.history

import app.fireplayer.history.WatchHistory.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class TmdbLookupTest {

    private var clock = 1_000_000L
    private val lookup = TmdbLookup { clock }
    private val asked = mutableListOf<Triple<String, Int?, Boolean>>()

    /** Stands for TMDB: answers with [answer], or fails like a TV without a network. */
    private fun tmdb(answer: () -> Int?): (String, Int?, Boolean) -> Int? = { name, year, show ->
        asked += Triple(name, year, show)
        answer()
    }

    private fun row(id: String, tmdbId: Int, kind: Kind = Kind.EPISODE) =
        WatchHistory.Row(id, kind, "Show $id", 0, 1, 8, null, tmdbId, 60_000, 1_400_000, 1)

    @Test
    fun theIdTmdbIsSureOfIsAskedForOnce() {
        assertEquals(33880, lookup.id("The Legend of Korra", 0, true, tmdb { 33880 }))
        // Every minute it plays: TMDB's answer stands.
        assertEquals(33880, lookup.id("The Legend of Korra", 0, true, tmdb { error("asked again") }))
        // Without a year it's asked without one.
        assertEquals(listOf(Triple("The Legend of Korra", null as Int?, true)), asked)
    }

    @Test
    fun oneTmdbIsntSureOfIsZeroAndNotAskedAgain() {
        assertEquals(0, lookup.id("Film Nobody Knows", 2019, false, tmdb { null }))
        assertEquals(0, lookup.id("Film Nobody Knows", 2019, false, tmdb { 5 }))
        assertEquals(1, asked.size)
        assertEquals(2019, asked.single().second)
    }

    @Test
    fun aFailureIsNullNeverThrownAndAskedAgainOnlyAWhileLater() {
        assertNull(lookup.id("Avatar", 2009, false, tmdb { throw IOException("no network") }))
        clock += TmdbLookup.RETRY_MS - 1
        assertNull(lookup.id("Avatar", 2009, false, tmdb { 19995 }))
        assertEquals(1, asked.size)
        clock += 1
        assertEquals(19995, lookup.id("Avatar", 2009, false, tmdb { 19995 }))
        assertEquals(2, asked.size)
    }

    @Test
    fun aFilmAndAShowOrAnotherYearAreTheirOwnQuestions() {
        assertEquals(1, lookup.id("Dune", 1984, false, tmdb { 1 }))
        assertEquals(2, lookup.id("Dune", 2021, false, tmdb { 2 }))
        assertEquals(3, lookup.id("Dune", 1984, true, tmdb { 3 }))
        assertEquals(3, asked.size)
    }

    @Test
    fun aNumberThatIsntAnIdIsZero() {
        assertEquals(0, lookup.id("Odd", 0, false, tmdb { -1 }))
    }

    @Test
    fun rowsWithoutAnIdAreTakenOnceEach() {
        val rows = listOf(row("a", 0), row("b", 1399), row("c", 0, Kind.MOVIE))
        assertEquals(listOf("a", "c"), lookup.toLookUp(rows).map { it.id })
        // Glass Home reads the rows again (and after each change): not again.
        assertTrue(lookup.toLookUp(rows).isEmpty())
        // A new one without an id is.
        assertEquals(listOf("d"), lookup.toLookUp(rows + row("d", 0)).map { it.id })
        // Couldn't be looked up after all (no key yet): taken again on the next read.
        lookup.lookUpAgain(rows.take(1))
        assertEquals(listOf("a"), lookup.toLookUp(rows).map { it.id })
        assertNull(lookup.toLookUp(rows).firstOrNull())
    }

    /** As Continue Watching does: [fill] with this lookup, asking [answer] about each row by its title. */
    private fun fill(rows: List<WatchHistory.Row>, answer: (String) -> Int?): Map<String, Int> {
        val found = LinkedHashMap<String, Int>()
        val idOf = { r: WatchHistory.Row -> lookup.id(r.title, r.year, true, tmdb { answer(r.title) }) }
        lookup.fill(rows, idOf) { r, id -> found[r.id] = id }
        return found
    }

    @Test
    fun aRowTmdbCouldntBeAskedAboutIsTakenAgainOnALaterReadAndAskedAgainOnlyAfterTheWait() {
        val korra = listOf(row("korra", 0))
        var reachable = false
        val answer = { _: String -> if (reachable) 33880 else throw IOException("no network") }
        assertTrue(fill(lookup.toLookUp(korra), answer).isEmpty())
        assertEquals(1, asked.size)
        // Back on the next read, the network back too: TMDB isn't asked again before the wait is over.
        reachable = true
        clock += TmdbLookup.RETRY_MS - 1
        assertTrue(fill(lookup.toLookUp(korra), answer).isEmpty())
        assertEquals(1, asked.size)
        clock += 1
        assertEquals(mapOf("korra" to 33880), fill(lookup.toLookUp(korra), answer))
        assertEquals(2, asked.size)
        // Found: not taken again (its entry now has the id).
        assertTrue(lookup.toLookUp(korra).isEmpty())
    }

    @Test
    fun theFirstRowTmdbCouldntBeAskedAboutStopsTheRestWhichAreGivenBack() {
        val rows = listOf(row("a", 0), row("b", 0), row("c", 0), row("d", 0))
        val answers = mapOf("Show a" to 1, "Show b" to null)
        val found = fill(lookup.toLookUp(rows)) { title ->
            if (title in answers) answers[title] else throw IOException("timed out")
        }
        assertEquals(mapOf("a" to 1), found)
        // Only one wait for a timeout: "d" isn't asked.
        assertEquals(listOf("Show a", "Show b", "Show c"), asked.map { it.first })
        // TMDB's answers stand ("a" found, "b" not sure of); "c" and the one after it are taken again.
        assertEquals(listOf("c", "d"), lookup.toLookUp(rows).map { it.id })
    }

    @Test
    fun rowsNotReachedWhenKeepingOneFailsAreGivenBack() {
        val rows = listOf(row("a", 0), row("b", 0))
        try {
            lookup.fill(lookup.toLookUp(rows), { 7 }) { _, _ -> throw IllegalStateException("couldn't keep it") }
            fail("not thrown")
        } catch (e: IllegalStateException) {
            assertEquals(listOf("a", "b"), lookup.toLookUp(rows).map { it.id })
        }
    }
}
