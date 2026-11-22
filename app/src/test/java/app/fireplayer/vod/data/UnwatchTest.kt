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

/**
 * Mark unwatched (a film, a whole show, an episode, "up to here"): the marks, the history and where playback stopped
 * are cleared for exactly those, in one write and one listener call; a title in Continue Watching stays there at 0:00
 * of what the action was used on, one not in it isn't put in it ([VodLibrary.setWatched], [VodLibrary.unwatchEpisodes]).
 */
class UnwatchTest {
    private val day = 24 * 3600_000L
    private var now = 1_000 * day
    private val store = MemoryLibraryStore()
    private var changes = 0
    private val direct = Executor { it.run() }
    private val show = Title("tt1695360", Kind.SERIES, "The Legend of Korra")
    private val film = Title("tt15239678", Kind.MOVIE, "Dune: Part Two")
    private val min = 60_000L

    private fun ep(season: Int, episode: Int) = Episode(
        id = Episode.idOf(show.id, season, episode), season = season, episode = episode, name = "E$episode",
        released = now - 400 * day + (season * 20 + episode) * day, runtimeMin = 23,
    )

    private val eps = listOf(ep(0, 1), ep(1, 1), ep(1, 2), ep(1, 3), ep(1, 4), ep(2, 1), ep(2, 2))
    private val library = VodLibrary(store, { now }, direct, { if (it.id == show.id) eps else null }) { changes++ }

    private fun e(season: Int, episode: Int) = eps.first { it.season == season && it.episode == episode }

    private fun play(season: Int, episode: Int, positionMin: Long, lengthMin: Long = 23) {
        now += 1000
        library.recordProgress(show, EpisodeRef(season, episode, "E$episode"), positionMin * min, lengthMin * min)
    }

    /** Where the write and the listener stand, to compare after one action. */
    private fun counts() = changes to store.batches

    @Test
    fun aFilmWatchedBeforeAndPlayedAgainStaysInContinueWatchingAtTheStart() {
        library.recordProgress(film, null, 140 * min, 142 * min)
        assertTrue(library.isWatched(film.id))
        now += day
        library.recordProgress(film, null, 50 * min, 142 * min)
        assertTrue(library.inContinueWatching(film.id))
        val (c, b) = counts()
        library.setWatched(film, false)
        assertEquals(c + 1 to b + 1, counts())
        assertFalse(library.isWatched(film.id))
        val card = library.continueWatching(null).single()
        assertEquals(0f, card.progress!!, 0f)
        assertEquals("2h 22m left", card.caption)
        assertEquals(card, library.continueCard(Kind.MOVIE, film.id))
        // Its place in the row is kept: its time is the play's.
        assertEquals(now, library.progressOf(Kind.MOVIE, film.id)!!.at)
        assertEquals(0L, store.progress["movie|${film.id}"]!!.positionMs)
        assertFalse(library.lastPlayed(Kind.MOVIE, film.id)!!.resumable)
    }

    @Test
    fun aFilmNotInContinueWatchingIsNotPutInIt() {
        library.recordProgress(film, null, 141 * min, 142 * min)
        assertFalse(library.inContinueWatching(film.id))
        library.setWatched(film, false)
        assertFalse(library.isWatched(film.id))
        assertFalse(library.inContinueWatching(film.id))
        assertTrue(library.continueWatching(null).isEmpty())
        val last = library.lastPlayed(Kind.MOVIE, film.id)!!
        assertEquals(0L, last.position.positionMs)
        assertTrue(last.hidden)
        // Played again, it comes back as before.
        library.recordProgress(film, null, 10 * min, 142 * min)
        assertTrue(library.inContinueWatching(film.id))
    }

    @Test
    fun aWholeShowGoesBackToItsFirstEpisodeInOneWrite() {
        library.setEpisodesWatched(listOf(e(1, 1), e(1, 2), e(1, 3)), true)
        play(1, 2, 22)
        play(1, 4, 10)
        assertTrue(library.inContinueWatching(show.id))
        val (c, b) = counts()
        library.setWatched(show, false)
        assertEquals(c + 1 to b + 1, counts())
        assertTrue(eps.none { library.isEpisodeWatched(it) })
        assertEquals(0L, library.positionOf(e(1, 4))!!.positionMs)
        assertEquals(0L, library.positionOf(e(1, 2))!!.positionMs)
        // Still in Continue Watching, at 0:00 of S1 E1 (not the special), its length from the show's runtime.
        val card = library.continueWatching(null).single()
        assertEquals(EpisodeRef(1, 1, "E1"), card.episode)
        assertEquals(0f, card.progress!!, 0f)
        assertEquals("S1 E1 · 23m left", card.caption)
        // Kept: a new library on the same store reads it all back.
        val again = VodLibrary(store, { now }, direct, { eps }) {}
        assertTrue(eps.none { again.isEpisodeWatched(it) })
        assertEquals(EpisodeRef(1, 1, "E1"), again.continueEntries().single().episode)
        assertEquals(0L, again.positionOf(e(1, 4))!!.positionMs)
    }

    @Test
    fun aWholeShowMarkedWatchedLeftContinueWatchingAndIsNotPutBack() {
        play(1, 3, 10)
        now += 1000
        library.setWatched(show, true)
        assertFalse(library.inContinueWatching(show.id))
        library.setWatched(show, false)
        assertTrue(eps.none { library.isEpisodeWatched(it) })
        assertFalse(library.inContinueWatching(show.id))
        assertTrue(library.nextEpisodes().isEmpty())
        // Its record starts it over: Play on its page is S1 E1.
        assertEquals(EpisodeRef(1, 1, "E1"), library.lastPlayed(Kind.SERIES, show.id)!!.episode)
    }

    @Test
    fun anEpisodeClearsOnlyItselfAndContinueWatchingGoesToIt() {
        library.setEpisodesWatched(listOf(e(1, 1), e(1, 2), e(1, 3)), true)
        play(1, 2, 22)
        play(1, 4, 10)
        val (c, b) = counts()
        library.setEpisodesWatched(listOf(e(1, 2)), false)
        assertEquals(c + 1 to b + 1, counts())
        assertFalse(library.isEpisodeWatched(e(1, 2)))
        assertTrue(library.isEpisodeWatched(e(1, 1)))
        assertTrue(library.isEpisodeWatched(e(1, 3)))
        assertEquals(0L, library.positionOf(e(1, 2))!!.positionMs)
        // An episode after it keeps where it stopped.
        assertEquals(10 * min, library.positionOf(e(1, 4))!!.positionMs)
        val card = library.continueCard(Kind.SERIES, show.id)!!
        assertEquals(EpisodeRef(1, 2, "E2"), card.episode)
        assertEquals("S1 E2 · 23m left", card.caption)
        assertEquals(0f, card.progress!!, 0f)
    }

    @Test
    fun upToHereClearsThatEpisodeAndEveryOneBeforeItInOneWrite() {
        library.setWatched(show, true)
        library.setEpisodesWatched(listOf(e(0, 1)), true)
        play(2, 1, 12)
        val (c, b) = counts()
        library.unwatchEpisodes(EpisodeMarks.upTo(eps, e(1, 3), now), e(1, 3))
        assertEquals(c + 1 to b + 1, counts())
        assertTrue(listOf(e(1, 1), e(1, 2), e(1, 3)).none { library.isEpisodeWatched(it) })
        // After it, and Specials: as they were.
        assertTrue(library.isEpisodeWatched(e(1, 4)))
        assertTrue(library.isEpisodeWatched(e(0, 1)))
        assertEquals(12 * min, library.positionOf(e(2, 1))!!.positionMs)
        assertEquals(EpisodeRef(1, 3, "E3"), library.continueEntries().single().episode)
        assertEquals(0L, library.continueEntries().single().positionMs)
        // Nothing left to clear: nothing written, nobody told.
        library.unwatchEpisodes(listOf(e(1, 1), e(1, 2), e(1, 3)), e(1, 3))
        assertEquals(c + 1 to b + 1, counts())
    }

    @Test
    fun outOfContinueWatchingAnEpisodeStaysOut() {
        play(1, 2, 10)
        library.removeFromContinueWatching(show.id)
        library.setEpisodesWatched(listOf(e(1, 1)), true)
        // About its record's episode: the record starts it over, still out.
        library.unwatchEpisodes(listOf(e(1, 1), e(1, 2)), e(1, 2))
        assertFalse(library.inContinueWatching(show.id))
        assertEquals(0L, library.lastPlayed(Kind.SERIES, show.id)!!.position.positionMs)
        assertTrue(library.lastPlayed(Kind.SERIES, show.id)!!.hidden)
        // Not about it: the record is left as it is.
        play(2, 1, 10)
        library.removeFromContinueWatching(show.id)
        library.setEpisodesWatched(listOf(e(1, 1)), true)
        library.setEpisodesWatched(listOf(e(1, 1)), false)
        assertEquals(EpisodeRef(2, 1, "E1"), library.lastPlayed(Kind.SERIES, show.id)!!.episode)
        assertEquals(10 * min, library.lastPlayed(Kind.SERIES, show.id)!!.position.positionMs)
    }

    @Test
    fun aShowMarkedWatchedBeforeGoesOnAtTheEpisodeChosenUnwatched() {
        // The show's mark covers the episode chosen: cleared, the record there doesn't read as "marked since".
        play(2, 2, 10)
        now += 1000
        library.setWatched(show, true)
        play(2, 2, 12)
        assertTrue(library.inContinueWatching(show.id))
        library.setEpisodesWatched(listOf(e(1, 4)), false)
        val entry = library.continueEntries().single()
        assertEquals(EpisodeRef(1, 4, "E4"), entry.episode)
        assertFalse(library.lastPlayed(Kind.SERIES, show.id)!!.markedWatched)
    }

    @Test
    fun playbackWritesItsRecordPositionAndMarkTogether() {
        val b = store.batches
        play(1, 1, 22)
        assertEquals(b + 1, store.batches)
        assertTrue(store.watched["series|${e(1, 1).id}"]!!.watched)
        assertEquals(22 * min, store.positions[e(1, 1).id]!!.positionMs)
    }

    // ---- What an episode's hold window offers (EpisodeMarks) ----

    @Test
    fun theHoldWindowOffersOnlyWhatChangesSomething() {
        val watched = HashSet<String>()
        val played = HashSet<String>()
        fun offer(e: Episode) = EpisodeMarks.offer(eps, e, now, { it.id in watched }, { it.id in played })
        // Nothing watched: Mark watched, and up to here past the first episode only.
        assertEquals(EpisodeMarks.Offer(watched = true, unwatched = false, watchedUpTo = false, unwatchedUpTo = false), offer(e(1, 1)))
        assertEquals(EpisodeMarks.Offer(watched = true, unwatched = false, watchedUpTo = true, unwatchedUpTo = false), offer(e(1, 3)))
        // S2 E1 counts S1 before it, across seasons; a special counts only the specials before it.
        watched += listOf(e(1, 1).id, e(1, 2).id, e(1, 3).id, e(1, 4).id)
        assertEquals(EpisodeMarks.Offer(watched = true, unwatched = false, watchedUpTo = false, unwatchedUpTo = true), offer(e(2, 1)))
        assertEquals(EpisodeMarks.Offer(watched = true, unwatched = false, watchedUpTo = false, unwatchedUpTo = false), offer(e(0, 1)))
        // Watched itself: Mark unwatched; played part way: both single ones.
        assertEquals(EpisodeMarks.Offer(watched = false, unwatched = true, watchedUpTo = false, unwatchedUpTo = true), offer(e(1, 4)))
        played += e(2, 1).id
        assertEquals(EpisodeMarks.Offer(watched = true, unwatched = true, watchedUpTo = false, unwatchedUpTo = true), offer(e(2, 1)))
        // Only played before it (nothing watched): unwatched up to here clears that.
        watched.clear()
        assertEquals(EpisodeMarks.Offer(watched = true, unwatched = false, watchedUpTo = true, unwatchedUpTo = true), offer(e(2, 2)))
    }

    @Test
    fun aShowsFirstEpisodeIsItsFirstRegularOne() {
        assertEquals(e(1, 1), EpisodeMarks.first(eps.reversed()))
        assertEquals(e(0, 1), EpisodeMarks.first(listOf(e(0, 1))))
        assertNull(EpisodeMarks.first(null))
        assertNull(EpisodeMarks.first(emptyList()))
    }
}
