package app.fireplayer.vod.data

import app.fireplayer.history.LibraryContinue
import app.fireplayer.history.WatchHistory
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

/**
 * A show stays in Continue Watching across its seasons: finishing a season's last episode goes on to the next season's
 * first, and the show leaves only once there's nothing more to watch ([VodLibrary.continueEntries], [UpNext]).
 */
class ContinueSeasonsTest {
    private val day = 24 * 3600_000L
    private var now = 1_000 * day
    private val direct = Executor { it.run() }
    private val show = Title("tt0000100", Kind.SERIES, "Three Seasons")
    private val lists = HashMap<String, List<Episode>?>()
    private var asked = 0
    private val library = VodLibrary(MemoryLibraryStore(), { now }, direct, { asked++; lists[it.id] }) { }

    private fun ep(season: Int, episode: Int, daysAgo: Long? = 300) =
        Episode(Episode.idOf(show.id, season, episode), season, episode, "Episode $episode", released = daysAgo?.let { now - it * day })

    // Seasons 1-3 aired long ago; a special since.
    private val three = listOf(ep(1, 1), ep(1, 2), ep(1, 3), ep(2, 1), ep(2, 2), ep(3, 1), ep(3, 2), ep(0, 1, 2))

    private fun finish(season: Int, episode: Int) {
        now += 1000
        library.recordProgress(show, EpisodeRef(season, episode), 39 * 60_000L, 40 * 60_000L)
    }

    @Test
    fun theLastEpisodeOfSeasonOneOfThreeGoesOnToSeasonTwo() {
        lists[show.id] = three
        finish(1, 3)
        val card = library.continueWatching(null).single()
        assertEquals(show, card.title)
        assertEquals("S2 E1", card.caption)
        assertNull(card.progress)
        assertEquals(EpisodeRef(2, 1, "Episode 1"), card.episode)
        assertEquals(card, library.continueWatching(Kind.SERIES).single())
        assertTrue(library.continueWatching(Kind.MOVIE).isEmpty())
        assertTrue(library.inContinueWatching(show.id))
        // Watch now and Glass Home's row play the same episode, from its start.
        assertEquals(EpisodeRef(2, 1, "Episode 1"), library.upNext(show))
        val entry = library.continueEntries().single()
        assertEquals(entry, library.continueEntry(Kind.SERIES, show.id))
        assertNull(library.continueEntry(Kind.MOVIE, show.id))
        val row = LibraryContinue.row(entry)!!
        assertEquals(LibraryContinue.idOf(Kind.SERIES, show.id), row.id)
        assertEquals(WatchHistory.Kind.EPISODE, row.kind)
        assertEquals(listOf(2, 1, 0), listOf(row.season, row.episode, row.positionMs.toInt()))
        // Not a new episode: not in Next Episodes too.
        assertTrue(library.nextEpisodes().isEmpty())
        // The record itself still says where it was left (the title page's Play reads it).
        assertEquals(EpisodeRef(1, 3), library.lastPlayed(Kind.SERIES, show.id)!!.episode)
    }

    @Test
    fun theFinalEpisodeOfTheFinalSeasonEndsIt() {
        // A fourth season announced, nothing aired: it doesn't count; nor does the special aired since.
        val s4e1 = ep(4, 1, -20)
        lists[show.id] = three + listOf(s4e1, ep(4, 2, null))
        finish(3, 2)
        assertTrue(library.continueWatching(null).isEmpty())
        assertTrue(library.continueEntries().isEmpty())
        assertFalse(library.inContinueWatching(show.id))
        assertNull(library.upNext(show))
        assertTrue(library.nextEpisodes().isEmpty())
        // S4 E1 airs: a new episode, so Next Episodes has it (Continue Watching doesn't).
        now += 21 * day
        assertTrue(library.continueWatching(null).isEmpty())
        assertEquals(listOf(show to s4e1), library.nextEpisodes())
    }

    @Test
    fun aShowWhoseEpisodesArentKnownYetStaysAndIsWorkedOutOnceTheyAre() {
        finish(1, 3)
        val waiting = library.continueWatching(null).single()
        assertEquals(EpisodeRef(1, 3), waiting.episode)
        assertTrue(library.inContinueWatching(show.id))
        assertEquals(EpisodeRef(1, 3), library.upNext(show))
        assertTrue(asked > 0)
        lists[show.id] = three
        assertEquals("S2 E1", library.continueWatching(null).single().caption)
    }

    @Test
    fun aSeasonFinishedOutOfOrderGoesOnToTheNextUnwatched() {
        lists[show.id] = three
        library.setEpisodesWatched(listOf(ep(1, 1), ep(1, 3)), true)
        finish(1, 2)
        assertEquals(EpisodeRef(2, 1, "Episode 1"), library.continueWatching(null).single().episode)
    }

    @Test
    fun aGapInTheEpisodeNumbersIsPassedOver() {
        lists[show.id] = listOf(ep(1, 1), ep(1, 2), ep(1, 4), ep(2, 1))
        finish(1, 2)
        assertEquals("S1 E4", library.continueWatching(null).single().caption)
        finish(1, 4)
        assertEquals("S2 E1", library.continueWatching(null).single().caption)
    }

    @Test
    fun theNextEpisodesOwnResumePointCarriesOver() {
        lists[show.id] = three
        now += 1000
        library.recordProgress(show, EpisodeRef(2, 1), 10 * 60_000L, 40 * 60_000L)
        finish(1, 3)
        val card = library.continueWatching(null).single()
        assertEquals("S2 E1 · 30m left", card.caption)
        assertEquals(0.25f, card.progress!!, 0.0001f)
    }

    @Test
    fun anUnfinishedEpisodeStaysAsItWasAndMarkingTheShowWatchedTakesItOut() {
        lists[show.id] = three
        now += 1000
        library.recordProgress(show, EpisodeRef(1, 3), 20 * 60_000L, 40 * 60_000L)
        assertEquals("S1 E3 · 20m left", library.continueWatching(null).single().caption)
        assertEquals(0, asked)
        // The hold window's Mark watched (the whole show): nothing left to watch.
        now += 1000
        library.setWatched(show, true)
        assertTrue(library.continueWatching(null).isEmpty())
        // Taken out of Continue Watching: gone however it goes on.
        now += 1000
        library.setWatched(show, false)
        finish(1, 3)
        assertEquals(1, library.continueWatching(null).size)
        library.removeFromContinueWatching(show.id)
        assertTrue(library.continueWatching(null).isEmpty())
        assertFalse(library.inContinueWatching(show.id))
    }
}
