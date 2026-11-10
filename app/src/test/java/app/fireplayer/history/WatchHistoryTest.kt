package app.fireplayer.history

import app.fireplayer.history.WatchHistory.Kind
import app.fireplayer.player.NextPick
import app.fireplayer.player.TitleInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchHistoryTest {

    private val minute = 60_000L
    private var clock = 1_000_000L
    private var ids = 0

    private fun WatchHistory.play(w: WatchHistory.Watch): Boolean = watched(w, ++clock) { "id${++ids}" }

    // Made-up links: the history keeps whatever it's given, and hands none of it out.
    private fun movie(id: Int, playedMs: Long, positionMs: Long = 40 * minute, finished: Boolean = false) = WatchHistory.Watch(
        kind = Kind.MOVIE,
        keys = setOf("m|provider.example:80|$id"),
        videoKey = "m|provider.example:80|$id",
        title = "Film $id",
        year = 1999,
        tmdbId = 600 + id,
        positionMs = positionMs,
        durationMs = 120 * minute,
        playedMs = playedMs,
        finished = finished,
        resume = WatchHistory.Resume("http://provider.example/movie/USER/PASS/$id.mkv", title = "Film $id"),
    )

    private fun episode(
        show: Int,
        season: Int,
        number: Int,
        playedMs: Long,
        finished: Boolean = false,
        next: Boolean = false,
        keys: Set<String> = setOf("s|provider.example:80|$show", "n|show$show"),
    ) = WatchHistory.Watch(
        kind = Kind.EPISODE,
        keys = keys,
        videoKey = "e|provider.example:80|$show$season$number",
        title = "Show $show",
        season = season,
        episode = number,
        tmdbId = 900 + show,
        positionMs = 10 * minute,
        durationMs = 45 * minute,
        playedMs = playedMs,
        finished = finished,
        next = if (next) {
            WatchHistory.Next(
                "e|provider.example:80|$show$season${number + 1}", season, number + 1, null,
                WatchHistory.Resume("http://provider.example/series/USER/PASS/${number + 1}.mkv"),
            )
        } else null,
        resume = WatchHistory.Resume("http://provider.example/series/USER/PASS/$number.mkv"),
    )

    @Test
    fun showsOnlyAfterFiveMinutesOfPlayingOverAnyNumberOfSittings() {
        val h = WatchHistory()
        assertFalse(h.play(movie(1, 3 * minute)))
        assertTrue(h.rows().isEmpty())
        // Opened again without playing: still nothing.
        assertFalse(h.play(movie(1, 0)))
        assertTrue(h.play(movie(1, 2 * minute, positionMs = 50 * minute)))
        val row = h.rows().single()
        assertEquals("Film 1", row.title)
        assertEquals(Kind.MOVIE, row.kind)
        assertEquals(1999, row.year)
        assertEquals(601, row.tmdbId)
        assertEquals(50 * minute, row.positionMs)
        assertEquals(120 * minute, row.durationMs)
    }

    @Test
    fun nothingStartsWithoutPlaying() {
        val h = WatchHistory()
        assertFalse(h.play(movie(1, 0)))
        assertTrue(h.all.isEmpty())
        // A film played to its end that wasn't there doesn't start an entry either.
        assertFalse(h.play(movie(2, 10 * minute, finished = true)))
        assertTrue(h.all.isEmpty())
    }

    @Test
    fun aFinishedFilmLeaves() {
        val h = WatchHistory()
        h.play(movie(1, 10 * minute))
        assertEquals(1, h.rows().size)
        assertTrue(h.play(movie(1, 60 * minute, finished = true)))
        assertTrue(h.rows().isEmpty())
        assertTrue(h.all.isEmpty())
    }

    @Test
    fun aFinishedEpisodeMovesItsShowToTheNextFromTheStart() {
        val h = WatchHistory()
        h.play(episode(1, 2, 4, 10 * minute))
        val id = h.rows().single().id
        assertTrue(h.play(episode(1, 2, 4, 30 * minute, finished = true, next = true)))
        val row = h.rows().single()
        assertEquals(id, row.id)
        assertEquals(2, row.season)
        assertEquals(5, row.episode)
        assertEquals(0L, row.positionMs)
        assertEquals("http://provider.example/series/USER/PASS/5.mkv", h.record(id)!!.resume.uri)
    }

    @Test
    fun anEpisodeIsNotedByItsRealNumbersAndMovesOnToTheRealNext() {
        // TiviMate's E11, E12 and E13 of a list with a copy: the provider's E6, E6 again, and E7.
        val names = listOf("Show 1 S01E11", "Show 1 S01E12", "Show 1 S01E13")
        val reals = listOf(NextPick.Real(1, 1, 6), NextPick.Real(1, 1, 6), NextPick.Real(1, 1, 7))
        val shown = names.zip(reals) { n, r -> TitleInfo.parse(n, null).withEpisode(r.season, r.episode) }
        assertEquals("S1 E6", shown[0].subtitle)
        val next = NextPick.after(0, reals)!!
        assertEquals(2, next)
        fun watch(i: Int, playedMs: Long, finished: Boolean = false) = WatchHistory.Watch(
            kind = Kind.EPISODE,
            keys = setOf("s|provider.example:80|1"),
            videoKey = "e|provider.example:80|${11 + i}",
            title = shown[i].title,
            season = shown[i].season!!,
            episode = shown[i].episode!!,
            positionMs = 10 * minute,
            durationMs = 45 * minute,
            playedMs = playedMs,
            finished = finished,
            next = if (finished) {
                WatchHistory.Next(
                    "e|provider.example:80|${11 + next}", reals[next].season, reals[next].episode, null,
                    WatchHistory.Resume("http://provider.example/series/USER/PASS/${11 + next}.mkv"),
                )
            } else null,
            resume = WatchHistory.Resume("http://provider.example/series/USER/PASS/${11 + i}.mkv"),
        )
        val h = WatchHistory()
        h.play(watch(0, 10 * minute))
        assertEquals(1 to 6, h.rows().single().let { it.season to it.episode })
        h.play(watch(0, 30 * minute, finished = true))
        val row = h.rows().single()
        assertEquals(1 to 7, row.season to row.episode)
        assertEquals("http://provider.example/series/USER/PASS/13.mkv", h.record(row.id)!!.resume.uri)
    }

    @Test
    fun aFinishedEpisodeWithNoNextTakesItsShowAway() {
        val h = WatchHistory()
        h.play(episode(1, 1, 10, 10 * minute))
        assertTrue(h.play(episode(1, 1, 10, 30 * minute, finished = true)))
        assertTrue(h.rows().isEmpty())
    }

    @Test
    fun oneEntryPerShowAtItsLatestEpisodeNewestFirst() {
        val h = WatchHistory()
        h.play(episode(1, 1, 1, 10 * minute))
        h.play(movie(7, 10 * minute))
        h.play(episode(1, 1, 3, 2 * minute))
        h.play(episode(2, 4, 1, 6 * minute))
        val rows = h.rows()
        assertEquals(listOf("Show 2", "Show 1", "Film 7"), rows.map { it.title })
        assertEquals(3, rows[1].episode)
        assertTrue(rows.zipWithNext().all { (a, b) -> a.lastWatched > b.lastWatched })
    }

    @Test
    fun anotherEpisodeOnlyOpenedLeavesTheShowAtTheOneWatched() {
        val h = WatchHistory()
        h.play(episode(1, 1, 3, 10 * minute))
        // Opened and left before it played (or it wouldn't play).
        assertFalse(h.play(episode(1, 1, 1, 0)))
        assertEquals(3, h.rows().single().episode)
        // Played at all, it's the one now.
        assertTrue(h.play(episode(1, 1, 1, 1_000)))
        assertEquals(1, h.rows().single().episode)
    }

    @Test
    fun theSameShowByItsIdOrItsNameIsOneEntry() {
        val h = WatchHistory()
        h.play(episode(1, 1, 1, 3 * minute, keys = setOf("n|show1")))
        h.play(episode(1, 1, 2, 3 * minute, keys = setOf("s|provider.example:80|1", "n|show1")))
        // Later known by its id alone.
        h.play(episode(1, 1, 3, 1 * minute, keys = setOf("s|provider.example:80|1")))
        val row = h.rows().single()
        assertEquals(3, row.episode)
        assertEquals(1, h.all.size)
    }

    @Test
    fun removedStaysGoneUntilWatchedAgainForFiveMinutes() {
        val h = WatchHistory()
        h.play(episode(1, 1, 1, 20 * minute))
        val id = h.rows().single().id
        assertTrue(h.remove(id))
        assertTrue(h.rows().isEmpty())
        assertFalse(h.remove(id))
        // Any episode of it, for a while.
        assertFalse(h.play(episode(1, 1, 2, 4 * minute)))
        assertTrue(h.rows().isEmpty())
        assertTrue(h.play(episode(1, 1, 2, 1 * minute)))
        val back = h.rows().single()
        assertEquals(2, back.episode)
        assertNotEquals(id, back.id)
    }

    @Test
    fun aFilmLeavesAfterThirtyDaysAndAShowAfterSixtyUnwatched() {
        val h = WatchHistory()
        val day = 24 * 60 * minute
        h.play(movie(1, 10 * minute))
        h.play(episode(1, 1, 3, 10 * minute))
        val start = clock
        assertFalse(h.expire(start + 30 * day - 10))
        assertEquals(2, h.rows().size)
        assertTrue(h.expire(start + 30 * day + 1))
        assertEquals(listOf(Kind.EPISODE), h.rows().map { it.kind })
        assertFalse(h.expire(start + 60 * day - 10))
        assertTrue(h.expire(start + 60 * day + 1))
        assertTrue(h.rows().isEmpty())
        assertTrue(h.all.isEmpty())
    }

    @Test
    fun expiredComesBackOnlyAfterFiveMoreMinutesOfPlaying() {
        val h = WatchHistory()
        val day = 24 * 60 * minute
        h.play(movie(1, 20 * minute))
        h.play(episode(1, 1, 3, 20 * minute))
        val film = h.rows().first { it.kind == Kind.MOVIE }.id
        val show = h.rows().first { it.kind == Kind.EPISODE }.id
        // Played again for a moment, long after: its old minutes don't count.
        clock += 61 * day
        assertTrue(h.play(movie(1, 1 * minute)))
        assertTrue(h.rows().isEmpty())
        assertFalse(h.play(episode(1, 1, 4, 4 * minute)))
        assertTrue(h.rows().isEmpty())
        assertTrue(h.play(movie(1, 4 * minute)))
        assertTrue(h.play(episode(1, 1, 4, 1 * minute)))
        val rows = h.rows()
        assertEquals(2, rows.size)
        assertNotEquals(film, rows.first { it.kind == Kind.MOVIE }.id)
        val back = rows.first { it.kind == Kind.EPISODE }
        assertNotEquals(show, back.id)
        assertEquals(4, back.episode)
    }

    @Test
    fun watchingKeepsATitleFromExpiring() {
        val h = WatchHistory()
        val day = 24 * 60 * minute
        h.play(movie(1, 10 * minute))
        clock += 20 * day
        h.play(movie(1, 1 * minute))
        clock += 20 * day
        assertFalse(h.expire(clock))
        assertEquals(1, h.rows().size)
    }

    @Test
    fun atMostTwentyFilmsAndTwentyShows() {
        val h = WatchHistory()
        for (i in 1..25) h.play(movie(i, 6 * minute))
        for (i in 1..22) h.play(episode(i, 1, 1, 6 * minute))
        val rows = h.rows()
        assertEquals(20, rows.count { it.kind == Kind.MOVIE })
        assertEquals(20, rows.count { it.kind == Kind.EPISODE })
        // The oldest went.
        assertFalse(rows.any { it.title == "Film 5" })
        assertTrue(rows.any { it.title == "Film 6" })
        assertFalse(rows.any { it.title == "Show 2" })
    }

    @Test
    fun keptAsTextAndReadBackTheSame() {
        val h = WatchHistory()
        h.play(movie(1, 6 * minute).copy(title = "Tabs\tand\nlines \\ and \u001f"))
        h.play(
            episode(2, 1, 1, 2 * minute).copy(
                episodeTitle = "Pilot",
                resume = WatchHistory.Resume(
                    uri = "http://provider.example/series/USER/PASS/1.mkv",
                    type = null,
                    title = null,
                    headers = listOf("User-Agent", "TiviMate/5", "Referer", ""),
                    subtitles = listOf("http://subs.example/a.srt", "http://subs.example/b.srt"),
                    subtitleNames = listOf("English", null),
                ),
            ),
        )
        val text = h.encode()
        val back = WatchHistory.decode(text)
        assertEquals(h.all, back.all)
        assertEquals(text, back.encode())
        assertEquals(h.rows(), back.rows())
    }

    @Test
    fun unreadableTextIsAnEmptyHistory() {
        assertTrue(WatchHistory.decode(null).all.isEmpty())
        assertTrue(WatchHistory.decode("").all.isEmpty())
        assertTrue(WatchHistory.decode("something else\nx").all.isEmpty())
        val h = WatchHistory()
        h.play(movie(1, 6 * minute))
        // A damaged line goes; the rest is read.
        assertEquals(1, WatchHistory.decode(h.encode() + "broken\tline\n").all.size)
    }

    @Test
    fun aTmdbIdFoundLaterIsGivenToItsEntryAndTheRestStays() {
        val h = WatchHistory()
        h.play(episode(1, 1, 8, 10 * minute).copy(tmdbId = 0))
        h.play(movie(2, 10 * minute))
        val show = h.all.first { it.kind == Kind.EPISODE }
        assertEquals(0, show.tmdbId)
        val before = h.all
        assertTrue(h.setTmdbId(show.id, 33880))
        assertEquals(show.copy(tmdbId = 33880), h.record(show.id))
        // Its place in the row, and the other entries, stay as they were.
        assertEquals(before.map { it.id }, h.all.map { it.id })
        assertEquals(before.filter { it.id != show.id }, h.all.filter { it.id != show.id })
        assertEquals(33880, h.rows().first { it.id == show.id }.tmdbId)
        // The same id again, or an entry that isn't there: nothing changes.
        assertFalse(h.setTmdbId(show.id, 33880))
        assertFalse(h.setTmdbId("nope", 1))
        // Kept, and not lost when it's watched again without one.
        val back = WatchHistory.decode(h.encode())
        assertEquals(33880, back.record(show.id)!!.tmdbId)
        back.play(episode(1, 1, 9, 2 * minute).copy(tmdbId = 0))
        assertEquals(33880, back.record(show.id)!!.tmdbId)
        assertEquals(9, back.record(show.id)!!.episode)
    }

    @Test
    fun rowsNeverCarryTheLink() {
        val h = WatchHistory()
        h.play(movie(1, 6 * minute))
        h.play(episode(1, 1, 1, 6 * minute))
        for (row in h.rows()) {
            assertFalse(row.toString().contains("USER"))
            assertFalse(row.toString().contains("http"))
        }
    }

    @Test
    fun showsWithTheSameNameButDifferentProviderIdsStayApart() {
        val h = WatchHistory()
        fun ep(series: String, video: String) = WatchHistory.Watch(
            kind = WatchHistory.Kind.EPISODE, keys = setOf("s|host:80|$series", "n|shogun"), videoKey = video, title = "Shogun",
            season = 1, episode = 1, positionMs = 600_000, durationMs = 3_000_000, playedMs = 6 * 60_000,
            resume = WatchHistory.Resume("http://host/series/$video.mkv"),
        )
        var n = 0
        h.watched(ep("1", "a"), 1_000, { "id${n++}" })
        h.watched(ep("2", "b"), 2_000, { "id${n++}" })
        assertEquals("two shows, two rows", 2, h.rows().size)
        h.watched(ep("1", "c"), 3_000, { "id${n++}" })
        assertEquals("the first show again: still two rows", 2, h.rows().size)
    }
}
