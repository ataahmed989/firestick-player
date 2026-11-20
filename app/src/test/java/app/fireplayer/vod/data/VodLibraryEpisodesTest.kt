package app.fireplayer.vod.data

import app.fireplayer.vod.Episode
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Kind
import app.fireplayer.vod.Title
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/** The library's episode side: per-episode marks, the show's mark, positions, Continue Watching questions. */
class VodLibraryEpisodesTest {

    private val day = 24 * 3600_000L
    private var now = 1_000 * day
    private val store = MemoryLibraryStore()
    private var changes = 0
    private val direct = Executor { it.run() }
    private val library = VodLibrary(store, { now }, direct) { changes++ }

    private val show = Title("tt1695360", Kind.SERIES, "The Legend of Korra")
    private val film = Title("tt15239678", Kind.MOVIE, "Dune: Part Two")

    private fun ep(season: Int, episode: Int, airedDaysAgo: Long) = Episode(
        id = Episode.idOf(show.id, season, episode), season = season, episode = episode, name = "E$episode",
        released = now - airedDaysAgo * day,
    )

    @Test
    fun episodesAreMarkedOneByOneInOneWrite() {
        val eps = listOf(ep(1, 1, 30), ep(1, 2, 23), ep(1, 3, 16))
        library.setEpisodesWatched(eps.take(2), true)
        assertEquals(1, changes)
        assertEquals(1, store.batches)
        assertTrue(library.isEpisodeWatched(eps[0]))
        assertTrue(library.isEpisodeWatched(eps[1]))
        assertFalse(library.isEpisodeWatched(eps[2]))
        // The show itself isn't marked; an episode's mark is its own row.
        assertFalse(library.isWatched(show.id))
        assertEquals(true, store.watched["series|tt1695360:1:1"]!!.watched)
        // Already so: nothing written, nobody told.
        library.setEpisodesWatched(eps.take(2), true)
        assertEquals(1, changes)
        library.setEpisodesWatched(listOf(eps[1]), false)
        assertFalse(library.isEpisodeWatched(eps[1]))
        assertEquals(2, changes)
        // Kept: a new library on the same store reads them back.
        val again = VodLibrary(store, { now }, direct) {}
        assertTrue(again.isEpisodeWatched(eps[0]))
        assertFalse(again.isEpisodeWatched(eps[1]))
    }

    @Test
    fun theShowsMarkCoversOnlyEpisodesAiredByThenAndTheNewerMarkWins() {
        val old = ep(1, 1, 30)
        val upcoming = ep(1, 2, -5)
        library.setWatched(show, true)
        assertTrue(library.isEpisodeWatched(old))
        // Aired after the show was marked: new, not watched.
        assertFalse(library.isEpisodeWatched(upcoming))
        now += 10 * day
        assertFalse(library.isEpisodeWatched(upcoming))
        // One episode unmarked after the show's mark: that one only.
        library.setEpisodesWatched(listOf(old), false)
        assertFalse(library.isEpisodeWatched(old))
        // Marked again as a whole: covers both now.
        library.setWatched(show, true)
        assertTrue(library.isEpisodeWatched(old))
        assertTrue(library.isEpisodeWatched(upcoming))
        // Marked not watched as a whole: its episodes' own marks go too.
        library.setEpisodesWatched(listOf(ep(1, 3, 1)), true)
        library.setWatched(show, false)
        assertFalse(library.isEpisodeWatched(old))
        assertFalse(library.isEpisodeWatched(ep(1, 3, 1)))
        assertFalse(library.isWatched(show.id))
    }

    @Test
    fun unmarkingAShowWithOnlyEpisodeMarksClearsThem() {
        val e = ep(2, 5, 3)
        library.setEpisodesWatched(listOf(e), true)
        val before = changes
        library.setWatched(show, false)
        assertFalse(library.isEpisodeWatched(e))
        assertEquals(before + 1, changes)
        // Nothing marked at all: nothing to do.
        library.setWatched(Title("tt9", Kind.SERIES, "Other"), false)
        assertEquals(before + 1, changes)
    }

    @Test
    fun marksAtTheSameMillisecondStillKeepTheirOrder() {
        // The clock doesn't move between the two: the later mark still wins.
        val e = ep(1, 1, 30)
        library.setWatched(show, true)
        library.setEpisodesWatched(listOf(e), false)
        assertFalse(library.isEpisodeWatched(e))
        library.setWatched(show, true)
        assertTrue(library.isEpisodeWatched(e))
    }

    @Test
    fun playbackKeepsEachEpisodesPositionAndAFinishedOneIsWatched() {
        val e = ep(2, 5, 40)
        library.recordProgress(show, EpisodeRef(2, 5, "Peacekeepers"), 10 * 60_000L, 23 * 60_000L)
        assertEquals(1, changes)
        val p = library.positionOf(e)!!
        assertEquals(10 * 60_000L, p.positionMs)
        assertTrue(p.resumable)
        assertFalse(library.isEpisodeWatched(e))
        assertTrue(library.inContinueWatching(show.id))
        val last = library.lastPlayed(Kind.SERIES, show.id)!!
        assertEquals(EpisodeRef(2, 5, "Peacekeepers"), last.episode)
        assertFalse(last.finished)
        assertNull(library.positionOf(ep(2, 6, 33)))
        // Watched to the end: the episode is marked, the record counts as finished.
        now += 1000
        library.recordProgress(show, EpisodeRef(2, 5, "Peacekeepers"), 22 * 60_000L, 23 * 60_000L)
        assertEquals(2, changes)
        assertTrue(library.isEpisodeWatched(e))
        assertTrue(library.lastPlayed(Kind.SERIES, show.id)!!.finished)
        assertEquals(22 * 60_000L, store.positions[e.id]!!.positionMs)
        // A finished film is marked watched and leaves Continue Watching.
        library.recordProgress(film, null, 140 * 60_000L, 142 * 60_000L)
        assertTrue(library.isWatched(film.id))
        assertFalse(library.inContinueWatching(film.id))
    }

    @Test
    fun lastPlayedSaysWhenItWasMarkedWatchedSince() {
        library.recordProgress(film, null, 30 * 60_000L, 142 * 60_000L)
        assertFalse(library.lastPlayed(Kind.MOVIE, film.id)!!.markedWatched)
        assertTrue(library.lastPlayed(Kind.MOVIE, film.id)!!.resumable)
        library.setWatched(film, true)
        val last = library.lastPlayed(Kind.MOVIE, film.id)!!
        assertTrue(last.markedWatched)
        assertTrue(last.finished)
        assertFalse(last.resumable)
        assertFalse(library.inContinueWatching(film.id))
        // A series' record: its episode's own mark counts.
        library.recordProgress(show, EpisodeRef(1, 2), 5 * 60_000L, 23 * 60_000L)
        library.setEpisodesWatched(listOf(ep(1, 2, 50)), true)
        assertTrue(library.lastPlayed(Kind.SERIES, show.id)!!.markedWatched)
        assertNull(library.lastPlayed(Kind.MOVIE, "tt404"))
    }

    @Test
    fun takenOutOfContinueWatchingButTheRecordStays() {
        library.recordProgress(film, null, 30 * 60_000L, 142 * 60_000L)
        assertTrue(library.inContinueWatching(film.id))
        library.removeFromContinueWatching(film.id)
        assertFalse(library.inContinueWatching(film.id))
        val last = library.lastPlayed(Kind.MOVIE, film.id)!!
        assertTrue(last.hidden)
        assertEquals(30 * 60_000L, last.position.positionMs)
        assertFalse(library.inContinueWatching("tt404"))
    }

    @Test
    fun aRecordFromBeforeEpisodePositionsStillGivesItsEpisodesPosition() {
        // Stored by step 1's library: the series' record only.
        store.saveProgress(Progress(show, EpisodeRef(3, 2), 7 * 60_000L, 23 * 60_000L, now - day))
        val fresh = VodLibrary(store, { now }, direct) {}
        assertEquals(7 * 60_000L, fresh.positionOf(ep(3, 2, 100))!!.positionMs)
        assertNull(fresh.positionOf(ep(3, 3, 90)))
    }
}
