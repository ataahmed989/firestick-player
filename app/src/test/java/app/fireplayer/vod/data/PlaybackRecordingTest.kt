package app.fireplayer.vod.data

import app.fireplayer.vod.Addon
import app.fireplayer.vod.Card
import app.fireplayer.vod.Episode
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Kind
import app.fireplayer.vod.LastRelease
import app.fireplayer.vod.ReleaseKey
import app.fireplayer.vod.RowSpec
import app.fireplayer.vod.RowStyle
import app.fireplayer.vod.Source
import app.fireplayer.vod.Title
import app.fireplayer.vod.data.DefaultVodRepositoryTest.FakeHttp
import app.fireplayer.vod.data.DefaultVodRepositoryTest.FakeTmdb
import app.fireplayer.vod.sources.RecordedLibrary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor

/** What playback records (step 3): the release last used, watched past 90 % or into the credits, Next Episodes. */
class PlaybackRecordingTest {
    private var now = TitlePageFixtures.NOW
    private val direct = Executor { it.run() }
    private val store = MemoryLibraryStore()
    private var changes = 0
    private val library = VodLibrary(store, { now }, direct) { changes++ }
    private val recorded = RecordedLibrary(library)
    private val film = Title("tt0000001", Kind.MOVIE, "Film")
    private val show = Title("tt1695360", Kind.SERIES, "The Legend of Korra")
    private val hash = "a".repeat(40)

    @Test
    fun theLastReleaseIsKeptByTitleAndEpisodeWithoutALink() {
        assertNull(recorded.lastRelease(film, null))
        recorded.recordRelease(film, null, LastRelease(Addon.MEDIAFUSION, ReleaseKey(hash, 3), now))
        recorded.recordRelease(show, EpisodeRef(2, 5), LastRelease(Addon.TORRENTIO, ReleaseKey(hash, null), now))
        assertEquals(LastRelease(Addon.MEDIAFUSION, ReleaseKey(hash, 3), now), recorded.lastRelease(film, null)?.copy(at = now))
        assertEquals(ReleaseKey(hash, null), recorded.lastRelease(show, EpisodeRef(2, 5))?.release)
        assertNull(recorded.lastRelease(show, EpisodeRef(2, 6)))
        // Stored as hash, index and add-on name only, and read back by a new library.
        val row = store.releases.values.first { it.id == film.id }
        assertEquals(listOf("MEDIAFUSION", hash, "3"), listOf(row.addon, row.hash, row.fileIdx.toString()))
        assertEquals("tt1695360:2:5", store.releases.values.first { it.kind == Kind.SERIES }.id)
        val again = RecordedLibrary(VodLibrary(store, { now }, direct) { })
        assertEquals(Addon.TORRENTIO, again.lastRelease(show, EpisodeRef(2, 5))?.addon)
    }

    @Test
    fun past90PercentOrIntoTheCreditsCountsAsWatched() {
        recorded.recordPlayback(film, null, 50 * 60_000L, 120 * 60_000L, creditsAtMs = null)
        assertFalse(library.isWatched(film.id))
        assertTrue(library.inContinueWatching(film.id))
        now += 1000
        // Into the credits (at 1:45 of 2:00) but under 90 %.
        recorded.recordPlayback(film, null, 106 * 60_000L, 120 * 60_000L, creditsAtMs = 105 * 60_000L)
        assertTrue(library.isWatched(film.id))
        assertFalse(library.inContinueWatching(film.id))
        // An episode past 90 %: watched, and its show goes on in Continue Watching (kept as it was here, where its
        // episodes aren't known).
        val other = Title("tt0000009", Kind.SERIES, "Other")
        recorded.recordPlayback(other, EpisodeRef(1, 1), 41 * 60_000L, 45 * 60_000L, creditsAtMs = null)
        assertTrue(library.isEpisodeWatched(Episode(Episode.idOf(other.id, 1, 1), 1, 1, "")))
        assertTrue(library.lastPlayed(Kind.SERIES, other.id)!!.finished)
        assertTrue(library.inContinueWatching(other.id))
    }

    private val dir: File = Files.createTempDirectory("vodnext").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun aFinishedEpisodeGoesOnInContinueWatchingAcrossSeasonsNotInNextEpisodes() {
        val http = FakeHttp()
        val metaUrl = Cinemeta.metaUrl(Kind.SERIES, show.id)
        http.answers[metaUrl] = Fixtures.text("cinemeta_meta_show_ended_specials.json")
        val repo = DefaultVodRepository(
            http = http,
            tmdb = FakeTmdb(),
            tmdbKey = { "" },
            cache = JsonCache(File(dir, "vod")) { now },
            idStore = MemoryIdStore(),
            libraryStore = MemoryLibraryStore(),
            main = direct,
            io = direct,
            net = direct,
            lookups = direct,
            clock = { now },
        )
        var changed = 0
        repo.addLibraryListener { changed++ }
        val next = RowSpec("next", "Next Episodes", RowStyle.EPISODE, Source.NextEpisodes())
        val cont = RowSpec("continue", "Continue Watching", RowStyle.WIDE, Source.ContinueWatching(null), continueRow = true)
        fun cards(row: RowSpec): List<Card> {
            var got: List<Card> = emptyList()
            repo.load(row) { c, _ -> got = c }
            return got
        }
        fun finish(season: Int, episode: Int) {
            now += 1000
            RecordedLibrary(repo.library).recordPlayback(show, EpisodeRef(season, episode), 23 * 60_000L, 24 * 60_000L, null)
        }
        // Half way through S1 E1: Continue Watching as it was; nothing asked of Cinemeta.
        repo.recordProgress(show, EpisodeRef(1, 1), 10 * 60_000L, 24 * 60_000L)
        assertEquals("S1 E1 · 14m left", cards(cont).single().caption)
        assertTrue(cards(next).isEmpty())
        assertEquals(0, http.count(metaUrl))
        // The last episode of season 1 (of 4) finished, the show never opened here: kept as it was, its episodes
        // fetched once, and the rows told; then it goes on with season 2's first.
        finish(1, 12)
        val before = changed
        assertEquals(EpisodeRef(1, 12), cards(cont).single().episode)
        assertEquals(1, http.count(metaUrl))
        assertTrue(changed > before)
        val s2 = cards(cont).single()
        assertEquals("S2 E1", s2.caption)
        assertNull(s2.progress)
        assertEquals(EpisodeRef(2, 1), s2.episode?.copy(name = null))
        assertTrue(cards(next).isEmpty())
        // Watch now (a card naming no episode) plays it too.
        assertEquals(EpisodeRef(2, 1), repo.upNext(show)?.copy(name = null))
        // The last episode of the last season: done; Specials don't count.
        finish(4, 13)
        assertTrue(cards(cont).isEmpty())
        assertTrue(cards(next).isEmpty())
        assertFalse(repo.inContinueWatching(show.id))
        assertNull(repo.upNext(show))
        // Stored episodes aren't fetched again.
        assertEquals(1, http.count(metaUrl))
    }
}
