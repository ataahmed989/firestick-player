package app.fireplayer.vod.data

import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Kind
import app.fireplayer.vod.Title
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class VodLibraryTest {

    private var now = 1_000_000L
    private val store = MemoryLibraryStore()
    private var changes = 0
    private val direct = Executor { it.run() }
    private val library = VodLibrary(store, { now }, direct) { changes++ }

    private val film = Title("tt33764258", Kind.MOVIE, "The Odyssey", year = "2026")
    private val show = Title("tt0903747", Kind.SERIES, "Breaking Bad", year = "2008–2013")

    @Test
    fun myListAddsRemovesAndIsKept() {
        assertFalse(library.inMyList(film.id))
        library.setInMyList(film, true)
        now += 1
        library.setInMyList(show, true)
        assertTrue(library.inMyList(film.id))
        // The latest added first.
        assertEquals(listOf(show, film), library.myList())
        assertEquals(2, changes)
        // Adding again changes nothing.
        library.setInMyList(film, true)
        assertEquals(2, changes)
        library.setInMyList(film, false)
        assertFalse(library.inMyList(film.id))
        assertEquals(listOf(show), library.myList())
        // Taken out, not deleted: kept with its time, for a sync later.
        assertEquals(false, store.listed["movie|${film.id}"]!!.inList)
        // A new library on the same store reads it back.
        val again = VodLibrary(store, { now }, direct) {}
        assertEquals(listOf(show), again.myList())
        assertTrue(again.inMyList(show.id))
    }

    @Test
    fun aFilmAndASeriesWithTheSameIdAreTwoTitlesThatLeaveTogether() {
        val junk = Title(show.id, Kind.MOVIE, "Mirror")
        library.setInMyList(show, true)
        now += 1
        library.setInMyList(junk, true)
        // Kept apart, each as its own kind.
        assertEquals(listOf(junk, show), library.myList())
        // The pages ask by id: taken out from either card, the id leaves the list whichever kind was added.
        library.setInMyList(junk, false)
        assertTrue(library.myList().isEmpty())
        assertFalse(library.inMyList(show.id))
        assertEquals(false, store.listed["series|${show.id}"]!!.inList)
        assertEquals(false, store.listed["movie|${show.id}"]!!.inList)
    }

    @Test
    fun takingOutFromTheOtherKindsCardWorks() {
        // The series is listed; the film card with the same id says "Remove from list" (asked by id) and does it.
        val junk = Title(show.id, Kind.MOVIE, "Mirror")
        library.setInMyList(show, true)
        assertTrue(library.inMyList(junk.id))
        val before = changes
        library.setInMyList(junk, false)
        assertFalse(library.inMyList(show.id))
        assertEquals(before + 1, changes)
        assertNull(store.listed["movie|${show.id}"])
        // Nothing listed under the id: nothing changes, nobody is told.
        library.setInMyList(junk, false)
        assertEquals(before + 1, changes)
        // Watched marks the same way.
        library.setWatched(show, true)
        assertTrue(library.isWatched(junk.id))
        library.setWatched(junk, false)
        assertFalse(library.isWatched(show.id))
        assertEquals(false, store.watched["series|${show.id}"]!!.watched)
        assertNull(store.watched["movie|${show.id}"])
    }

    @Test
    fun watchedMarks() {
        library.setWatched(film, true)
        assertTrue(library.isWatched(film.id))
        assertFalse(library.isWatched(show.id))
        library.setWatched(film, false)
        assertFalse(library.isWatched(film.id))
        assertEquals(2, changes)
    }

    @Test
    fun continueWatchingLatestFirstWithCaptions() {
        library.recordProgress(film, null, 50 * 60_000L, 62 * 60_000L)
        now += 1000
        library.recordProgress(show, EpisodeRef(2, 5, "Peekaboo"), 35 * 60_000L, 47 * 60_000L)
        val all = library.continueWatching(null)
        assertEquals(listOf(show, film), all.map { it.title })
        assertEquals("S2 E5 · 12m left", all[0].caption)
        assertEquals(EpisodeRef(2, 5, "Peekaboo"), all[0].episode)
        assertEquals("12m left", all[1].caption)
        assertEquals(50f / 62f, all[1].progress!!, 0.0001f)
        assertEquals(listOf(film), library.continueWatching(Kind.MOVIE).map { it.title })
        assertEquals(listOf(show), library.continueWatching(Kind.SERIES).map { it.title })
    }

    @Test
    fun removedFromContinueWatchingKeepsItsResumePoint() {
        library.recordProgress(film, null, 10 * 60_000L, 100 * 60_000L)
        val before = changes
        library.removeFromContinueWatching(film.id)
        assertTrue(library.continueWatching(null).isEmpty())
        assertEquals(before + 1, changes)
        assertEquals(10 * 60_000L, library.progressOf(Kind.MOVIE, film.id)!!.positionMs)
        assertTrue(store.progress["movie|${film.id}"]!!.hidden)
        // Removing again, or something not there, changes nothing.
        library.removeFromContinueWatching(film.id)
        library.removeFromContinueWatching("tt404")
        assertEquals(before + 1, changes)
        // Played again: back in it.
        now += 1
        library.recordProgress(film, null, 11 * 60_000L, 100 * 60_000L)
        assertEquals(1, library.continueWatching(null).size)
    }

    @Test
    fun markedWatchedLeavesContinueWatchingUntilPlayedAgain() {
        library.recordProgress(film, null, 10 * 60_000L, 100 * 60_000L)
        now += 1
        library.setWatched(film, true)
        assertTrue(library.continueWatching(null).isEmpty())
        now += 1
        library.recordProgress(film, null, 12 * 60_000L, 100 * 60_000L)
        assertEquals(1, library.continueWatching(null).size)
    }

    @Test
    fun captionsRoundToTheMinute() {
        assertEquals("12m left", Captions.left(12 * 60_000L))
        assertEquals("1m left", Captions.left(10_000L))
        assertEquals("1h left", Captions.left(60 * 60_000L))
        assertEquals("1h 10m left", Captions.left(70 * 60_000L + 20_000))
        assertNull(Captions.caption(null, 0, 0))
        assertEquals("S1 E3", Captions.caption(EpisodeRef(1, 3), 0, 0))
        assertNull(Captions.fraction(5, 0))
        assertEquals(1f, Captions.fraction(150, 100)!!, 0f)
    }

    @Test
    fun titlesAndCardsSurviveJson() {
        val full = Title("tt1", Kind.SERIES, "A", "2022–", "p", "b", "l", "d", "7.5", listOf("Drama", "Crime"), "49 min", 42)
        assertEquals(full, TitleJson.read(JSONObject(TitleJson.write(full).toString())))
        val bare = Title("tt2", Kind.MOVIE, "B")
        assertEquals(bare, TitleJson.read(TitleJson.write(bare)))
        val card = app.fireplayer.vod.Card(full, progress = 0.5f, caption = "S2 E5 · 12m left", rank = 3,
            episode = EpisodeRef(2, 5, "Name"), badge = "NEW")
        assertEquals(card, TitleJson.readCard(JSONObject(TitleJson.writeCard(card).toString())))
        assertNull(TitleJson.read(JSONObject("""{"id":"tt3","type":"channel","name":"C"}""")))
    }
}
