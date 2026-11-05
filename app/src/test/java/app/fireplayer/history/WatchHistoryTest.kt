package app.fireplayer.history

import app.fireplayer.history.WatchHistory.Kind
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
    fun rowsNeverCarryTheLink() {
        val h = WatchHistory()
        h.play(movie(1, 6 * minute))
        h.play(episode(1, 1, 1, 6 * minute))
        for (row in h.rows()) {
            assertFalse(row.toString().contains("USER"))
            assertFalse(row.toString().contains("http"))
        }
    }
}
