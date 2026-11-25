package app.fireplayer.vod.data

import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Kind
import app.fireplayer.vod.Title
import app.fireplayer.vod.play.KeptTracks
import app.fireplayer.vod.play.MemoryTrackStore
import app.fireplayer.vod.play.ResumeTracks
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
    fun theCopyLeftIsForgottenWhenTheTitleLeavesContinueWatching() {
        fun keep(kind: Kind, id: String) = library.recordRelease(ReleaseRow(kind, id, "TORRENTIO", "a".repeat(40), 0, now))
        fun kept(kind: Kind, id: String) = library.lastRelease(kind, id) != null
        library.recordProgress(film, null, 600_000, 7_200_000)
        keep(Kind.MOVIE, film.id)
        // Mark unwatched keeps it in Continue Watching, and its copy.
        library.setWatched(film, false)
        assertTrue(kept(Kind.MOVIE, film.id))
        // Mark watched: it leaves, and its copy is forgotten, on the TV too.
        library.setWatched(film, true)
        assertFalse(kept(Kind.MOVIE, film.id))
        assertTrue(store.releases.isEmpty())
        // Remove from Continue Watching, and watched to the end: the same.
        library.recordProgress(film, null, 600_000, 7_200_000)
        keep(Kind.MOVIE, film.id)
        library.removeFromContinueWatching(film.id)
        assertFalse(kept(Kind.MOVIE, film.id))
        library.recordProgress(film, null, 600_000, 7_200_000)
        keep(Kind.MOVIE, film.id)
        library.recordProgress(film, null, 7_200_000, 7_200_000)
        assertFalse(kept(Kind.MOVIE, film.id))
        // A show: an episode watched to the end while the show stays keeps its episodes' copies.
        keep(Kind.SERIES, "${show.id}:1:1")
        keep(Kind.SERIES, "${show.id}:1:2")
        keep(Kind.SERIES, "${show.id}0:1:1")
        library.recordProgress(show, EpisodeRef(1, 1), 2_700_000, 2_700_000)
        assertTrue(kept(Kind.SERIES, "${show.id}:1:1"))
        // Taken out: every episode's copy goes, nothing of another title's.
        library.removeFromContinueWatching(show.id)
        assertFalse(kept(Kind.SERIES, "${show.id}:1:1"))
        assertFalse(kept(Kind.SERIES, "${show.id}:1:2"))
        assertTrue(kept(Kind.SERIES, "${show.id}0:1:1"))
        assertEquals(setOf("series|${show.id}0:1:1"), store.releases.keys)
    }

    @Test
    fun theSoundAndSubtitlesChosenAreForgottenWithTheCopyLeft() {
        val kept = KeptTracks(MemoryTrackStore())
        val lib = VodLibrary(MemoryLibraryStore(), { now }, direct, { null }, kept) { changes++ }
        fun choose(kind: Kind, id: String) = kept.chose(kind, id, subs = ResumeTracks.Subs.Off, now = now)
        fun chosen(kind: Kind, id: String) = kept.read(kind, id) != null
        // A film with choices kept and no copy (one that wasn't the whole film isn't kept): they go with the title all the same.
        lib.recordProgress(film, null, 600_000, 7_200_000)
        choose(Kind.MOVIE, film.id)
        // Mark unwatched keeps it in Continue Watching, and its choices.
        lib.setWatched(film, false)
        assertTrue(chosen(Kind.MOVIE, film.id))
        // Mark watched: it leaves, and they're forgotten.
        lib.setWatched(film, true)
        assertFalse(chosen(Kind.MOVIE, film.id))
        // Remove from Continue Watching (here, or in Glass Home's row, which does the same), and watched to the end.
        lib.recordProgress(film, null, 600_000, 7_200_000)
        choose(Kind.MOVIE, film.id)
        lib.recordRelease(ReleaseRow(Kind.MOVIE, film.id, "TORRENTIO", "a".repeat(40), 0, now))
        lib.removeFromContinueWatching(film.id)
        assertFalse(chosen(Kind.MOVIE, film.id))
        assertNull(lib.lastRelease(Kind.MOVIE, film.id))
        lib.recordProgress(film, null, 600_000, 7_200_000)
        choose(Kind.MOVIE, film.id)
        lib.recordProgress(film, null, 7_200_000, 7_200_000)
        assertFalse(chosen(Kind.MOVIE, film.id))
        // A show: an episode watched to the end while the show stays (and Mark unwatched) keeps every episode's.
        choose(Kind.SERIES, "${show.id}:1:1")
        choose(Kind.SERIES, "${show.id}:1:2")
        choose(Kind.SERIES, "${show.id}0:1:1")
        lib.recordProgress(show, EpisodeRef(1, 1), 2_700_000, 2_700_000)
        assertTrue(chosen(Kind.SERIES, "${show.id}:1:1"))
        lib.setWatched(show, false)
        assertTrue(chosen(Kind.SERIES, "${show.id}:1:1"))
        // Taken out: every episode's go, nothing of another title's.
        lib.removeFromContinueWatching(show.id)
        assertFalse(chosen(Kind.SERIES, "${show.id}:1:1"))
        assertFalse(chosen(Kind.SERIES, "${show.id}:1:2"))
        assertTrue(chosen(Kind.SERIES, "${show.id}0:1:1"))
        // Played again and marked watched as a whole: the same.
        lib.recordProgress(show, EpisodeRef(2, 1), 600_000, 2_700_000)
        choose(Kind.SERIES, "${show.id}:2:1")
        lib.setWatched(show, true)
        assertFalse(chosen(Kind.SERIES, "${show.id}:2:1"))
        assertTrue(chosen(Kind.SERIES, "${show.id}0:1:1"))
    }

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
