package app.fireplayer.history

import app.fireplayer.vod.Day
import app.fireplayer.vod.Episode
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Kind
import app.fireplayer.vod.Title
import app.fireplayer.vod.data.Progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryContinueTest {

    private val film = Title("tt1375666", Kind.MOVIE, "Inception", year = "2010", tmdbId = 27205, poster = "https://img/p.jpg")
    private val show = Title("tt0903747", Kind.SERIES, "Breaking Bad", year = "2008–2013", tmdbId = 1396)

    @Test
    fun filmRow() {
        val row = LibraryContinue.row(Progress(film, null, 1_200_000, 8_880_000, 1_700_000_000_000))!!
        assertEquals("vod-m-tt1375666", row.id)
        assertEquals(WatchHistory.Kind.MOVIE, row.kind)
        assertEquals("movie", row.kind.code)
        assertEquals("Inception", row.title)
        assertEquals(2010, row.year)
        assertEquals(0, row.season)
        assertEquals(0, row.episode)
        assertNull(row.episodeTitle)
        assertEquals(27205, row.tmdbId)
        assertEquals(1_200_000L, row.positionMs)
        assertEquals(8_880_000L, row.durationMs)
        assertEquals(1_700_000_000_000L, row.lastWatched)
        assertNull(row.source)
        assertFalse(row.hasEpisodes)
    }

    @Test
    fun showRowIsItsEpisode() {
        val row = LibraryContinue.row(Progress(show, EpisodeRef(2, 5, "Breakage"), 60_000, 2_820_000, 5))!!
        assertEquals("vod-s-tt0903747", row.id)
        assertEquals("episode", row.kind.code)
        assertEquals(2008, row.year)
        assertEquals(2, row.season)
        assertEquals(5, row.episode)
        assertEquals("Breakage", row.episodeTitle)
        assertEquals(1396, row.tmdbId)
        assertTrue(row.hasEpisodes)
    }

    @Test
    fun showWithoutEpisodeAndUnmatchedTitles() {
        // A show naming no episode is a card on Home all the same: its row, no episode, its Episodes window to choose one.
        val noEpisode = LibraryContinue.row(Progress(show, null, 1, 2, 3))!!
        assertEquals("vod-s-tt0903747", noEpisode.id)
        assertEquals("episode", noEpisode.kind.code)
        assertEquals(0, noEpisode.season)
        assertEquals(0, noEpisode.episode)
        assertNull(noEpisode.episodeTitle)
        assertTrue(noEpisode.hasEpisodes)
        // Without a name Glass Home can't show it.
        assertNull(LibraryContinue.row(Progress(Title("tt2", Kind.MOVIE, " "), null, 1, 2, 3)))
        val bare = LibraryContinue.row(Progress(Title("tt1", Kind.MOVIE, "X"), null, -5, -1, 3))!!
        assertEquals(0, bare.tmdbId)
        assertEquals(0, bare.year)
        assertEquals(0L, bare.positionMs)
        assertEquals(0L, bare.durationMs)
    }

    @Test
    fun idsRoundTripAndNeverClash() {
        for (id in listOf("tt0903747", "kitsu:1234", "a/b?c d_é", "x_2f")) {
            for (kind in Kind.entries) {
                val rowId = LibraryContinue.idOf(kind, id)
                assertTrue(rowId, rowId.all { it.isLetterOrDigit() || it in ".:-_" })
                assertTrue(LibraryContinue.isLibraryId(rowId))
                assertEquals(kind to id, LibraryContinue.parse(rowId))
            }
        }
        assertEquals("vod-m-a_2fb", LibraryContinue.idOf(Kind.MOVIE, "a/b"))
        // The history's own ids (16 hex digits) and odd ones are not the library's.
        assertFalse(LibraryContinue.isLibraryId("0123456789abcdef"))
        assertNull(LibraryContinue.parse("0123456789abcdef"))
        assertNull(LibraryContinue.parse("vod-x-tt1"))
        assertNull(LibraryContinue.parse("vod-m-"))
        assertNull(LibraryContinue.parse("vod-m-tt_2"))
        assertNull(LibraryContinue.parse("vod-m-a/b"))
        assertNull(LibraryContinue.parse(null))
    }

    @Test
    fun episodeIds() {
        assertEquals("2:5", LibraryContinue.episodeId(2, 5))
        assertEquals(EpisodeRef(2, 5), LibraryContinue.parseEpisodeId("2:5"))
        assertEquals(EpisodeRef(0, 1), LibraryContinue.parseEpisodeId("0:1"))
        assertNull(LibraryContinue.parseEpisodeId("12345"))
        assertNull(LibraryContinue.parseEpisodeId("a:1"))
        assertNull(LibraryContinue.parseEpisodeId("1:-2"))
        assertNull(LibraryContinue.parseEpisodeId(null))
    }

    @Test
    fun episodeRowsAiredSortedWithItsOwn() {
        val now = 1_000_000L
        val eps = listOf(
            Episode("tt0903747:2:2", 2, 2, "Two", released = 10, day = Day(2009, 3, 15), still = "s22", runtimeMin = 47, overview = "o"),
            Episode("tt0903747:1:1", 1, 1, "Pilot", released = 5),
            Episode("tt0903747:2:9", 2, 9, "Later", released = now + 1),
            Episode("tt0903747:2:5", 2, 5, "Mine", released = null),
        )
        val rows = LibraryContinue.episodeRows(eps, EpisodeRef(2, 5), now) { if (it.episode == 2) 90_000 else 0 }
        assertEquals(listOf("1:1", "2:2", "2:5"), rows.map { it.episodeId })
        val two = rows[1]
        assertEquals("Two", two.name)
        assertEquals("o", two.overview)
        assertEquals("s22", two.image)
        assertEquals(47, two.runtimeMin)
        assertEquals("2009-03-15", two.airDate)
        assertEquals(90_000L, two.positionMs)
        assertFalse(two.current)
        assertTrue(rows[2].current)
        assertEquals("", rows[0].airDate)
        assertEquals(0, rows[0].runtimeMin)
    }
}
