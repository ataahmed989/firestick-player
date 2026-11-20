package app.fireplayer.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The contract's pure parts: what Play starts on a show, the resume rules, aired and NEW, stand-in ids. */
class VodContractTest {

    private val day = 24 * 3600_000L
    private val now = 1_000 * day
    private val show = "tt1695360"

    /** An episode that aired [daysAgo] days before [now] (negative: not aired yet). */
    private fun ep(season: Int, episode: Int, daysAgo: Long?) = Episode(
        id = Episode.idOf(show, season, episode),
        season = season,
        episode = episode,
        name = "S$season E$episode",
        released = daysAgo?.let { now - it * day },
    )

    // Specials listed first, as Cinemeta often does; two seasons; S2E3 not aired yet.
    private val episodes = listOf(
        ep(0, 1, 900), ep(0, 2, 800),
        ep(1, 1, 700), ep(1, 2, 690), ep(1, 3, 680),
        ep(2, 1, 300), ep(2, 2, 3), ep(2, 3, -5),
    )

    private fun record(season: Int, episode: Int, positionMs: Long, durationMs: Long = 40 * 60_000L, at: Long = now - 10 * day, marked: Boolean = false) =
        LastPlayed(EpisodeRef(season, episode), Position(positionMs, durationMs, at), markedWatched = marked)

    @Test
    fun noRecordStartsTheFirstAiredRegularEpisode() {
        val t = playTarget(episodes, null, now)!!
        assertEquals(ep(1, 1, 700), t.episode)
        assertEquals(PlayAction.PLAY, t.action)
        assertNull(t.position)
        // A record in Specials, or for an episode no longer listed, is treated as none.
        assertEquals(ep(1, 1, 700), playTarget(episodes, record(0, 2, 20 * 60_000L), now)!!.episode)
        assertEquals(ep(1, 1, 700), playTarget(episodes, record(7, 1, 20 * 60_000L), now)!!.episode)
    }

    @Test
    fun nothingAiredMeansNoTarget() {
        assertNull(playTarget(listOf(ep(0, 1, 30), ep(1, 1, -3)), null, now))
        assertNull(playTarget(emptyList(), null, now))
    }

    @Test
    fun anUnfinishedEpisodeResumesPast30Seconds() {
        val resume = playTarget(episodes, record(1, 2, 28 * 60_000L), now)!!
        assertEquals(ep(1, 2, 690), resume.episode)
        assertEquals(PlayAction.RESUME, resume.action)
        assertEquals(28 * 60_000L, resume.position!!.positionMs)

        val early = playTarget(episodes, record(1, 2, 30_000L), now)!!
        assertEquals(ep(1, 2, 690), early.episode)
        assertEquals(PlayAction.PLAY, early.action)
        assertNull(early.position)
    }

    @Test
    fun aFinishedEpisodeMovesOnAcrossSeasonsNeverIntoSpecials() {
        val next = playTarget(episodes, record(1, 3, 38 * 60_000L), now)!!
        assertEquals(ep(2, 1, 300), next.episode)
        assertEquals(PlayAction.PLAY, next.action)
        // S2E1 aired long before the record: not new.
        assertFalse(next.newEpisodes)

        // S2E2 aired 3 days ago, after a record from 10 days ago: "New episodes".
        val fresh = playTarget(episodes, record(2, 1, 0L, marked = true), now)!!
        assertEquals(ep(2, 2, 3), fresh.episode)
        assertTrue(fresh.newEpisodes)
    }

    @Test
    fun caughtUpWatchesAgainAndNamesTheNextEpisode() {
        val upToDate = playTarget(episodes, record(2, 2, 39 * 60_000L), now)!!
        assertEquals(ep(1, 1, 700), upToDate.episode)
        assertEquals(PlayAction.WATCH_AGAIN, upToDate.action)
        assertEquals(ep(2, 3, -5), upToDate.upcoming)

        val ended = listOf(ep(1, 1, 50), ep(1, 2, 40))
        val again = playTarget(ended, record(1, 2, 0L, marked = true), now)!!
        assertEquals(ep(1, 1, 50), again.episode)
        assertEquals(PlayAction.WATCH_AGAIN, again.action)
        assertNull(again.upcoming)
    }

    @Test
    fun aFinishedEpisodeGoesOnToTheNextAiredPastOneNotAired() {
        // S3E5 has no date (counted as not aired) while S3E6 and S3E7 aired: Play goes on to S3E6, not "Up to date".
        val gap = listOf(ep(3, 4, 30), ep(3, 5, null), ep(3, 6, 20), ep(3, 7, 13))
        val t = playTarget(gap, record(3, 4, 0L, marked = true), now)!!
        assertEquals(ep(3, 6, 20), t.episode)
        assertEquals(PlayAction.PLAY, t.action)
        assertNull(t.upcoming)
        // Nothing aired after it: caught up, the next one listed named.
        val ahead = listOf(ep(3, 4, 30), ep(3, 5, null), ep(3, 6, -4))
        val caught = playTarget(ahead, record(3, 4, 0L, marked = true), now)!!
        assertEquals(ep(3, 4, 30), caught.episode)
        assertEquals(PlayAction.WATCH_AGAIN, caught.action)
        assertEquals(ep(3, 5, null), caught.upcoming)
    }

    @Test
    fun resumeRules() {
        val length = 100 * 60_000L
        assertFalse(Position(30_000L, length, 0).resumable)
        assertTrue(Position(30_001L, length, 0).resumable)
        // The last 3 minutes are the end.
        assertTrue(Position(length - 3 * 60_000L, length, 0).finished)
        assertFalse(Position(length - 3 * 60_000L, length, 0).resumable)
        assertTrue(Position(length - 3 * 60_000L - 1, length, 0).resumable)
        // Unknown length: resumable past 30 s, never finished.
        assertTrue(Position(5 * 60_000L, 0, 0).resumable)
        assertFalse(Position(5 * 60_000L, 0, 0).finished)
        assertNull(Position(5 * 60_000L, 0, 0).fraction)
        assertEquals(0.25f, Position(25 * 60_000L, length, 0).fraction!!, 0.0001f)
        assertEquals(75 * 60_000L, Position(25 * 60_000L, length, 0).leftMs)
        assertTrue(LastPlayed(null, Position(10 * 60_000L, length, 0), markedWatched = true).finished)
        assertFalse(LastPlayed(null, Position(10 * 60_000L, length, 0), markedWatched = true).resumable)
        assertTrue(LastPlayed(null, Position(10 * 60_000L, length, 0), hidden = true).resumable)
    }

    @Test
    fun airedAndNew() {
        assertTrue(ep(2, 2, 3).aired(now))
        assertTrue(ep(2, 2, 3).isNew(now))
        assertFalse(ep(2, 1, 300).isNew(now))
        assertFalse(ep(2, 3, -5).aired(now))
        assertFalse(ep(2, 3, -5).isNew(now))
        assertFalse(ep(1, 1, null).aired(now))
        assertEquals(show, ep(2, 2, 3).showId)
        assertTrue(ep(0, 1, 900).isSpecial)
    }

    @Test
    fun detailsBeforeTheyCome() {
        val title = Title("tt15239678", Kind.MOVIE, "Dune: Part Two", year = "2024", description = "Paul…", runtime = "167 min")
        val d = TitleDetails.of(title)
        assertEquals(167, d.runtimeMin)
        assertEquals(Sourced("Paul…", From.CINEMETA), d.story)
        assertTrue(d.seasons.isEmpty())
        assertEquals(126, TitleDetails.runtimeMinutes("2h 6m"))
        assertEquals(60, TitleDetails.runtimeMinutes("1h"))
        assertEquals(23, TitleDetails.runtimeMinutes("23 min"))
        assertNull(TitleDetails.runtimeMinutes(""))
    }

    @Test
    fun filmsNotOutYet() {
        val film = TitleDetails(Title("tt1", Kind.MOVIE, "A", year = "2027"))
        assertFalse(film.isOut(now, 2026))
        assertTrue(film.copy(title = film.title.copy(year = "2026")).isOut(now, 2026))
        assertFalse(film.copy(releasedAt = now + day).isOut(now, 2026))
        assertTrue(film.copy(releasedAt = now - day).isOut(now, 2026))
    }

    @Test
    fun seasonsCountWithoutSpecials() {
        val d = TitleDetails(
            Title(show, Kind.SERIES, "Korra"),
            seasons = listOf(Season(1, episodes.filter { it.season == 1 }), Season(2, episodes.filter { it.season == 2 }), Season(0, episodes.filter { it.season == 0 })),
        )
        assertEquals(2, d.seasonCount)
        assertEquals(8, d.episodes.size)
        assertTrue(d.seasons.last().isSpecials)
    }

    @Test
    fun standInIds() {
        assertEquals("tmdb:movie:693134", standInId(Kind.MOVIE, 693134))
        assertEquals("tmdb:series:33880", standInId(Kind.SERIES, 33880))
        assertFalse(Title(standInId(Kind.MOVIE, 1), Kind.MOVIE, "X", tmdbId = 1).hasImdbId)
        assertTrue(Title("tt0137523", Kind.MOVIE, "Fight Club").hasImdbId)
    }

    @Test
    fun theFakeMarksUpToHere() {
        val repo = FakeVodRepository().apply { now = this@VodContractTest.now }
        val details = TitleDetails(Title(show, Kind.SERIES, "Korra"), seasons = listOf(Season(1, episodes.filter { it.season == 1 }), Season(2, episodes.filter { it.season == 2 })))
        repo.markWatchedUpTo(details, ep(2, 1, 300))
        assertTrue(repo.isEpisodeWatched(ep(1, 3, 680)))
        assertTrue(repo.isEpisodeWatched(ep(2, 1, 300)))
        assertFalse(repo.isEpisodeWatched(ep(2, 2, 3)))
        assertFalse(repo.isEpisodeWatched(ep(0, 1, 900)))
    }
}
