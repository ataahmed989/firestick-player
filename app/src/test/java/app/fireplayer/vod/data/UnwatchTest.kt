package app.fireplayer.vod.data

import app.fireplayer.vod.Episode
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Kind
import app.fireplayer.vod.LastPlayed
import app.fireplayer.vod.Position
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
    fun anEpisodeAfterADoneShowsRecordIsNotPutInContinueWatching() {
        // Caught up: S1 E1 finished, the whole show marked watched; in neither row.
        play(1, 1, 23)
        now += 1000
        library.setWatched(show, true)
        assertFalse(library.inContinueWatching(show.id))
        library.setEpisodesWatched(listOf(e(2, 1)), false)
        assertFalse(library.isEpisodeWatched(e(2, 1)))
        assertFalse(library.inContinueWatching(show.id))
        assertTrue(library.continueWatching(null).isEmpty())
        assertTrue(library.nextEpisodes().isEmpty())
        // Its record went to that episode at 0:00, kept out; Mark watched on it again lets it out, done as before.
        val last = library.lastPlayed(Kind.SERIES, show.id)!!
        assertEquals(EpisodeRef(2, 1, "E1"), last.episode)
        assertTrue(last.hidden)
        library.setEpisodesWatched(listOf(e(2, 1)), true)
        assertFalse(library.lastPlayed(Kind.SERIES, show.id)!!.hidden)
        assertFalse(library.inContinueWatching(show.id))
        assertFalse(store.progress["series|${show.id}"]!!.hidden)
    }

    @Test
    fun upToHereBeforeASpecialsRecordIsNotPutInContinueWatching() {
        play(0, 1, 23)
        library.setEpisodesWatched(eps.filter { !it.isSpecial }, true)
        assertFalse(library.inContinueWatching(show.id))
        library.unwatchEpisodes(EpisodeMarks.upTo(eps, e(1, 3), now), e(1, 3))
        assertFalse(library.isEpisodeWatched(e(1, 1)))
        assertFalse(library.inContinueWatching(show.id))
        assertTrue(library.nextEpisodes().isEmpty())
    }

    /** A library whose show's episodes are [aired] (changed by a test as a new one airs), on its own store. */
    private class Airing(eps: List<Episode>, clock: () -> Long) {
        var aired: List<Episode> = eps
        val library = VodLibrary(MemoryLibraryStore(), clock, Executor { it.run() }, { aired }) {}
    }

    private fun s3e1() = Episode(Episode.idOf(show.id, 3, 1), 3, 1, "E1", released = now + day, runtimeMin = 23)

    @Test
    fun aWholeShowUnwatchedAndWatchedAgainComesBackInNextEpisodes() {
        val a = Airing(eps) { now }
        now += 1000
        a.library.recordProgress(show, EpisodeRef(2, 2, "E2"), 23 * min, 23 * min)
        assertFalse(a.library.inContinueWatching(show.id))
        now += 1000
        a.library.setWatched(show, false)
        now += 1000
        a.library.setWatched(show, true)
        assertFalse(a.library.lastPlayed(Kind.SERIES, show.id)!!.hidden)
        assertFalse(a.library.inContinueWatching(show.id))
        // A new episode airs: Next Episodes has it, as before Mark unwatched.
        val new = s3e1()
        a.aired = eps + new
        now += 2 * day
        assertEquals(listOf(show to new), a.library.nextEpisodes())
        assertFalse(a.library.inContinueWatching(show.id))
    }

    @Test
    fun anEpisodeUnwatchedAndWatchedAgainGoesOnToTheNext() {
        val a = Airing(eps) { now }
        a.library.setEpisodesWatched(eps.filter { !it.isSpecial }, true)
        now += 1000
        a.library.recordProgress(show, EpisodeRef(2, 2, "E2"), 23 * min, 23 * min)
        val new = s3e1()
        a.aired = eps + new
        now += 2 * day
        assertEquals(listOf(show to new), a.library.nextEpisodes())
        a.library.setEpisodesWatched(listOf(e(2, 2)), false)
        assertTrue(a.library.lastPlayed(Kind.SERIES, show.id)!!.hidden)
        a.library.setEpisodesWatched(listOf(e(2, 2)), true)
        assertEquals(EpisodeRef(3, 1, "E1"), a.library.continueEntries().single().episode)
        assertTrue(a.library.nextEpisodes().isEmpty())
    }

    @Test
    fun aShowMarkedWatchedLeavesContinueWatchingBeforeItsEpisodesAreKnown() {
        val lib = VodLibrary(MemoryLibraryStore(), { now }, direct, { null }) {}
        now += 1000
        lib.recordProgress(show, EpisodeRef(1, 1, "E1"), 23 * min, 23 * min)
        // Over, its episodes not known: kept as it was.
        assertTrue(lib.inContinueWatching(show.id))
        now += 1000
        lib.setWatched(show, true)
        assertFalse(lib.inContinueWatching(show.id))
        assertTrue(lib.continueWatching(null).isEmpty())
        // Played since (its episode over by its own mark): kept again while they aren't known.
        now += 1000
        lib.recordProgress(show, EpisodeRef(1, 2, "E2"), 23 * min, 23 * min)
        assertTrue(lib.inContinueWatching(show.id))
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

    // ---- What a film's or a whole show's windows offer (TitleMarks) ----

    @Test
    fun aTitlesWindowOffersBothMarksWhilePartWatched() {
        assertEquals(TitleMarks.Offer(watched = true, unwatched = false), TitleMarks.offer(watched = false, played = false))
        assertEquals(TitleMarks.Offer(watched = true, unwatched = true), TitleMarks.offer(watched = false, played = true))
        assertEquals(TitleMarks.Offer(watched = false, unwatched = true), TitleMarks.offer(watched = true, played = true))
        assertEquals(TitleMarks.Offer(watched = false, unwatched = true), TitleMarks.offer(watched = true, played = false))
    }

    @Test
    fun aShowPlayedSinceItsMarkIsNotFullyWatched() {
        val playing = LastPlayed(EpisodeRef(3, 1), Position(10 * min, 23 * min, now))
        // A film: its mark alone.
        assertTrue(TitleMarks.watched(Kind.MOVIE, marked = true, last = playing, inContinue = true))
        assertFalse(TitleMarks.watched(Kind.MOVIE, marked = false, last = null, inContinue = false))
        // A show: its mark, unless played since (in Continue Watching, not over) or on its Continue card.
        assertTrue(TitleMarks.watched(Kind.SERIES, marked = true, last = null, inContinue = false))
        assertFalse(TitleMarks.watched(Kind.SERIES, marked = true, last = playing, inContinue = false))
        assertTrue(TitleMarks.watched(Kind.SERIES, marked = true, last = playing.copy(hidden = true), inContinue = false))
        assertTrue(TitleMarks.watched(Kind.SERIES, marked = true, last = playing.copy(markedWatched = true), inContinue = false))
        assertFalse(TitleMarks.watched(Kind.SERIES, marked = true, last = null, inContinue = true))
        assertFalse(TitleMarks.watched(Kind.SERIES, marked = false, last = null, inContinue = false))
        // From the library: marked watched, then a new season started. Both marks, and Mark watched keeps every position.
        library.setWatched(show, true)
        play(2, 1, 10)
        val watched = TitleMarks.watched(Kind.SERIES, library.isWatched(show.id), library.lastPlayed(Kind.SERIES, show.id), false)
        assertFalse(watched)
        assertEquals(TitleMarks.Offer(watched = true, unwatched = true), TitleMarks.offer(watched, library.hasHistory(show)))
        library.setWatched(show, true)
        assertEquals(10 * min, library.positionOf(e(2, 1))!!.positionMs)
        assertFalse(library.inContinueWatching(show.id))
    }

    @Test
    fun aTitleHasAHistoryUntilMarkedUnwatched() {
        assertFalse(library.hasHistory(film))
        assertFalse(library.hasHistory(show))
        library.recordProgress(film, null, 50 * min, 142 * min)
        assertTrue(library.hasHistory(film))
        library.setWatched(film, false)
        assertFalse(library.hasHistory(film))
        // A show: an episode started, or one episode's own mark, is enough.
        library.setEpisodesWatched(listOf(e(1, 2)), true)
        assertTrue(library.hasHistory(show))
        play(1, 3, 5)
        library.setWatched(show, false)
        assertFalse(library.hasHistory(show))
        play(1, 1, 5)
        library.removeFromContinueWatching(show.id)
        assertTrue(library.hasHistory(show))
    }

    @Test
    fun aShowsFirstEpisodeIsItsFirstRegularOne() {
        assertEquals(e(1, 1), EpisodeMarks.first(eps.reversed()))
        assertEquals(e(0, 1), EpisodeMarks.first(listOf(e(0, 1))))
        assertNull(EpisodeMarks.first(null))
        assertNull(EpisodeMarks.first(emptyList()))
    }
}
