package app.fireplayer.vod.ui

import app.fireplayer.vod.Card
import app.fireplayer.vod.Kind
import app.fireplayer.vod.RowSpec
import app.fireplayer.vod.RowStyle
import app.fireplayer.vod.Source
import app.fireplayer.vod.Title
import app.fireplayer.vod.data.Cinemeta
import app.fireplayer.vod.data.DefaultVodRepository
import app.fireplayer.vod.data.DefaultVodRepositoryTest
import app.fireplayer.vod.data.Fixtures
import app.fireplayer.vod.data.JsonCache
import app.fireplayer.vod.data.MemoryIdStore
import app.fireplayer.vod.data.MemoryLibraryStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.Executor

/** The pages' rules over the data side's real repository (recorded Cinemeta answers, no network). */
class RealRepositoryTest {

    private val dir: File = Files.createTempDirectory("vodpages").toFile()
    private var now = Instant.parse("2026-11-18T12:00:00Z").toEpochMilli()
    private val direct = Executor { it.run() }
    private val http = DefaultVodRepositoryTest.FakeHttp()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun repo() = DefaultVodRepository(
        http = http,
        tmdb = DefaultVodRepositoryTest.FakeTmdb(),
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

    private val shows = Source.Catalog(Kind.SERIES, "top")
    private val spec = RowSpec("shows.popular", "Popular", RowStyle.POSTER, shows)

    private fun serveShows() {
        http.answers[Cinemeta.catalogUrl(Kind.SERIES, "top", null, 0)] = Fixtures.text("cinemeta_catalog_series_top.json")
        http.answers[Cinemeta.catalogUrl(Kind.SERIES, "top", null, 50)] = Fixtures.text("cinemeta_catalog_last_page.json")
        http.answers[Cinemeta.catalogUrl(Kind.SERIES, "top", null, 100)] = Fixtures.text("cinemeta_catalog_end.json")
    }

    @Test
    fun seeAllLoadsMorePagesUntilTheEnd() {
        serveShows()
        val r = repo()
        // The row's own cards first, as the page opens See all with them.
        val first = ArrayList<Card>()
        r.load(spec, 0, RowRules.ROW_CARDS + 1) { cards, fresh -> if (fresh) first += cards }
        val feed = GridFeed(r, spec, first) { }
        assertEquals(4, feed.cards.size)
        feed.loadNext()
        feed.loadNext()
        assertEquals(8, feed.cards.size)
        assertFalse(feed.ended)
        feed.loadNext()
        assertTrue(feed.ended)
        assertEquals(8, feed.cards.map { it.title.id }.toSet().size)
        assertEquals(1, http.count(Cinemeta.catalogUrl(Kind.SERIES, "top", null, 50)))
    }

    @Test
    fun anOldCacheShowsFirstAndStaysWhenTheNetworkFails() {
        serveShows()
        repo().load(spec, 0, 16) { _, _ -> }
        // Hours later, offline: the stored page comes at once (not fresh), then the same as the last answer.
        now += 5 * 3600_000L
        http.answers.clear()
        val answers = ArrayList<Pair<Int, Boolean>>()
        val r = repo()
        r.load(spec, 0, 16) { cards, fresh -> answers += cards.size to fresh }
        assertEquals(listOf(4 to false, 4 to true), answers)
        assertTrue(RowRules.isFinal(shows, empty = false, fresh = false))
        val feed = GridFeed(r, spec, emptyList()) { }
        feed.loadNext()
        assertEquals(4, feed.cards.size)
        assertFalse(feed.loading)
    }

    @Test
    fun markWatchedTakesATitleOutOfContinueWatching() {
        val r = repo()
        var changes = 0
        r.addLibraryListener { changes++ }
        val film = Title("tt0111161", Kind.MOVIE, "A film")
        r.library.recordProgress(film, null, 30 * 60_000L, 142 * 60_000L)
        val cont = RowSpec("home.continue", "Continue Watching", RowStyle.WIDE, Source.ContinueWatching(null), continueRow = true)
        fun ids(): List<String> {
            val out = ArrayList<String>()
            r.load(cont, 0, 16) { cards, _ -> out += cards.map { it.title.id } }
            return out
        }
        assertEquals(listOf("tt0111161"), ids())
        r.setWatched(film, true)
        assertEquals(emptyList<String>(), ids())
        assertTrue(r.isWatched("tt0111161"))
        assertEquals(2, changes)
    }

    @Test
    fun wideCardsUseTheSmallBackdrop() {
        fun card(background: String?) = Card(Title("tt1", Kind.MOVIE, "A", poster = "P", background = background))
        assertEquals("https://images.metahub.space/background/small/tt1/img", wideArt(card("https://images.metahub.space/background/medium/tt1/img")))
        assertEquals("https://image.tmdb.org/t/p/w780/abc.jpg", wideArt(card("https://image.tmdb.org/t/p/w1280/abc.jpg")))
        assertEquals("P", wideArt(card(null)))
    }
}
