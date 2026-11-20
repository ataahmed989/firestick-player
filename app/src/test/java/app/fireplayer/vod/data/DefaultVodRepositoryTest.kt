package app.fireplayer.vod.data

import app.fireplayer.subs.Tmdb
import app.fireplayer.vod.Card
import app.fireplayer.vod.HeaderFeed
import app.fireplayer.vod.Kind
import app.fireplayer.vod.Page
import app.fireplayer.vod.RowSpec
import app.fireplayer.vod.RowStyle
import app.fireplayer.vod.Source
import app.fireplayer.vod.Title
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DefaultVodRepositoryTest {

    private val dir: File = Files.createTempDirectory("vodrepo").toFile()
    private var now = Instant.parse("2026-11-18T12:00:00Z").toEpochMilli()
    private val direct = Executor { it.run() }
    private var main: Executor = direct
    private var key = ""
    private val http = FakeHttp()
    private val tmdb = FakeTmdb()
    private val ids = MemoryIdStore()
    private val library = MemoryLibraryStore()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** Answers from fixtures by address; anything else fails like a TV without a network. */
    class FakeHttp : Http {
        val answers = HashMap<String, String>()
        val asked = ArrayList<String>()
        var gate: CountDownLatch? = null

        @Synchronized private fun note(url: String) {
            asked += url
        }

        override fun get(url: String): String {
            note(url)
            gate?.await(5, TimeUnit.SECONDS)
            return answers[url] ?: throw IOException("offline")
        }

        fun count(url: String) = synchronized(this) { asked.count { it == url } }
    }

    /** TMDB from fixtures: the trending lists and the external ids table; title details and finds as set by a test. */
    class FakeTmdb : TmdbSource {
        var trendingCalls = 0
        val idCalls = ArrayList<Int>()
        var trendingError: Throwable? = null
        var idsError: Throwable? = null
        private val map = Fixtures.externalIds()
        /** Details answers by "movie|693134" / "series|33880"; a missing one answers 404 (null). */
        val details = HashMap<String, String>()
        val detailsCalls = ArrayList<String>()
        var detailsError: Throwable? = null
        /** Find answers by IMDb id; a missing one finds nothing. */
        val finds = HashMap<String, String>()
        val findCalls = ArrayList<String>()

        @Synchronized
        override fun details(kind: Kind, tmdbId: Int): String? {
            detailsCalls += "${kind.type}|$tmdbId"
            detailsError?.let { throw it }
            return details["${kind.type}|$tmdbId"]
        }

        @Synchronized
        override fun find(imdbId: String): String {
            findCalls += imdbId
            detailsError?.let { throw it }
            return finds[imdbId] ?: """{"movie_results":[],"tv_results":[]}"""
        }

        override fun trending(kind: Kind): String {
            trendingCalls++
            trendingError?.let { throw it }
            return Fixtures.text(if (kind == Kind.MOVIE) "tmdb_trending_movie_week.json" else "tmdb_trending_tv_week.json")
        }

        override fun externalIds(kind: Kind, tmdbId: Int): String? {
            idCalls += tmdbId
            idsError?.let { throw it }
            val table = map.getValue(if (kind == Kind.MOVIE) "movie" else "tv")
            if (!table.containsKey(tmdbId)) return null
            val imdb = table[tmdbId]
            return if (imdb == null) Fixtures.text("tmdb_external_ids_no_imdb.json") else """{"id":$tmdbId,"imdb_id":"$imdb"}"""
        }
    }

    private fun repo() = DefaultVodRepository(
        http = http,
        tmdb = tmdb,
        tmdbKey = { key },
        cache = JsonCache(File(dir, "vod")) { now },
        idStore = ids,
        libraryStore = library,
        main = Executor { main.execute(it) },
        io = direct,
        net = direct,
        lookups = direct,
        clock = { now },
    )

    private val movieTop = Cinemeta.catalogUrl(Kind.MOVIE, "top", null, 0)
    private val seriesTop = Cinemeta.catalogUrl(Kind.SERIES, "top", null, 0)

    private fun row(source: Source, style: RowStyle = RowStyle.POSTER) = RowSpec("test", "Test", style, source)

    /** Every answer a load gives: (ids, fresh). */
    private fun DefaultVodRepository.answers(row: RowSpec, skip: Int = 0, limit: Int = 16): List<Pair<List<String>, Boolean>> {
        val out = ArrayList<Pair<List<String>, Boolean>>()
        load(row, skip, limit) { cards, fresh -> out += cards.map { it.title.id } to fresh }
        return out
    }

    private fun DefaultVodRepository.cards(row: RowSpec): List<Card> {
        var last: List<Card> = emptyList()
        load(row, 0, 15) { cards, _ -> last = cards }
        return last
    }

    private fun DefaultVodRepository.feeds(page: Page): List<HeaderFeed> {
        val out = ArrayList<HeaderFeed>()
        header(page) { out += it }
        return out
    }

    private val popularIds by lazy {
        Cinemeta.parseCatalog(Fixtures.text("cinemeta_catalog_movie_top.json"), Kind.MOVIE, "top", null, 2026).titles.map { it.id }
    }

    @Test
    fun aRowIsFetchedOnceThenAnsweredFromTheCacheUntilItIsOld() {
        http.answers[movieTop] = Fixtures.text("cinemeta_catalog_movie_top.json")
        val popular = row(Source.Catalog(Kind.MOVIE, "top"))
        // Nothing cached: one answer, fresh.
        assertEquals(listOf(popularIds to true), repo().answers(popular))
        assertEquals(1, http.count(movieTop))
        // FirePlayer opened again: straight from the disk, no fetch.
        assertEquals(listOf(popularIds to true), repo().answers(popular))
        assertEquals(1, http.count(movieTop))
        // Four hours on: the old cards at once, then the refreshed ones.
        now += 4 * 3600_000L
        val r = repo()
        assertEquals(listOf(popularIds to false, popularIds to true), r.answers(popular))
        assertEquals(2, http.count(movieTop))
        // Fewer asked for: fewer given.
        assertEquals(listOf(popularIds.take(3) to true), r.answers(popular, limit = 3))
    }

    @Test
    fun aFailureWithNothingCachedIsOneEmptyFreshAnswer() {
        assertEquals(listOf(emptyList<String>() to true), repo().answers(row(Source.Catalog(Kind.MOVIE, "top"))))
    }

    @Test
    fun aFailureKeepsTheOldCards() {
        http.answers[movieTop] = Fixtures.text("cinemeta_catalog_movie_top.json")
        val popular = row(Source.Catalog(Kind.MOVIE, "top"))
        repo().answers(popular)
        now += 5 * 3600_000L
        http.answers.clear()
        assertEquals(listOf(popularIds to false, popularIds to true), repo().answers(popular))
    }

    @Test
    fun aCancelledLoadSaysNothing() {
        http.answers[movieTop] = Fixtures.text("cinemeta_catalog_movie_top.json")
        val queue = ArrayList<Runnable>()
        main = Executor { queue += it }
        val r = repo()
        var called = 0
        val cancel = r.load(row(Source.Catalog(Kind.MOVIE, "top"))) { _, _ -> called++ }
        val header = r.header(Page.MOVIES) { called++ }
        val genres = r.genres(Kind.MOVIE) { called++ }
        cancel.cancel()
        header.cancel()
        genres.cancel()
        queue.forEach { it.run() }
        assertEquals(0, called)
    }

    @Test
    fun seeAllStepsByFiftyUntilAnEmptyPage() {
        val shows = Source.Catalog(Kind.SERIES, "top")
        http.answers[seriesTop] = Fixtures.text("cinemeta_catalog_series_top.json")
        http.answers[Cinemeta.catalogUrl(Kind.SERIES, "top", null, 50)] = Fixtures.text("cinemeta_catalog_last_page.json")
        http.answers[Cinemeta.catalogUrl(Kind.SERIES, "top", null, 100)] = Fixtures.text("cinemeta_catalog_end.json")
        val r = repo()
        assertEquals(listOf(listOf("tt7631058", "tt8014982", "tt31510819", "tt23112594") to true), r.answers(row(shows), 0, 50))
        assertEquals(listOf(listOf("tt36885662", "tt32536168", "tt33501959", "tt9169516") to true), r.answers(row(shows), 50, 50))
        assertEquals(listOf(emptyList<String>() to true), r.answers(row(shows), 100, 50))
        // A request across two pages stops at the last one.
        assertEquals(8, r.answers(row(shows), 0, 100).last().first.size)
        assertEquals(1, http.count(Cinemeta.catalogUrl(Kind.SERIES, "top", null, 50)))
    }

    @Test
    fun genresAndYearsFollowTheirSources() {
        // "New" always asks for a year; a genre chip's See all filters Popular.
        val r = repo()
        r.answers(row(Source.Catalog(Kind.MOVIE, "year", "2026")))
        r.answers(row(Source.Catalog(Kind.MOVIE, "year")))
        r.answers(row(Source.Catalog(Kind.SERIES, "top", "Reality-TV")))
        assertEquals(2, http.count("https://v3-cinemeta.strem.io/catalog/movie/year/genre=2026.json"))
        assertEquals(1, http.count("https://v3-cinemeta.strem.io/catalog/series/top/genre=Reality-TV.json"))
    }

    @Test
    fun withoutAKeyThereIsNoTop10AndHeadersRotatePopular() {
        http.answers[movieTop] = Fixtures.text("cinemeta_catalog_movie_top.json")
        http.answers[seriesTop] = Fixtures.text("cinemeta_catalog_series_top.json")
        val r = repo()
        assertFalse(r.hasTmdbKey())
        assertEquals(listOf(emptyList<String>() to true), r.answers(row(Source.Trending(Kind.MOVIE), RowStyle.RANKED)))
        assertEquals(0, tmdb.trendingCalls)
        val movies = r.feeds(Page.MOVIES)
        assertEquals(listOf(popularIds), movies.map { f -> f.titles.map { it.id } })
        val home = r.feeds(Page.HOME).single().titles.map { it.id }
        val shows = listOf("tt7631058", "tt8014982", "tt31510819", "tt23112594")
        assertEquals(TmdbTrending.interleave(popularIds, shows), home)
        assertEquals(10, home.size)
        assertEquals(popularIds[0], home[0])
        assertEquals(shows[0], home[1])
    }

    @Test
    fun theTop10UsesTheFewestCallsAndKeepsWhatItLearns() {
        key = "0123456789abcdef0123456789abcdef"
        http.answers[movieTop] = Fixtures.text("cinemeta_catalog_movie_top.json")
        val top10 = row(Source.Trending(Kind.MOVIE), RowStyle.RANKED)
        val r = repo()
        assertTrue(r.hasTmdbKey())
        val first = r.answers(top10, 0, 15)
        assertEquals(listOf(Fixtures.expectedTop10("movie") to true), first)
        // One list; Cinemeta's Popular page knew 8 of the ids; one lookup found none, so one more was asked.
        assertEquals(1, tmdb.trendingCalls)
        assertEquals(listOf(999999901, 950028, 969681), tmdb.idCalls)

        val cards = r.cards(top10)
        assertEquals((1..10).toList(), cards.map { it.rank })
        val odyssey = cards[0].title
        assertEquals("8.4", odyssey.imdbRating)
        assertEquals("https://image.tmdb.org/t/p/w1280/synthMovieBackdrop00.jpg", odyssey.background)
        // Not on Cinemeta's page and no meta reachable: made from TMDB's list.
        val invite = cards[8].title
        assertEquals("The Invite", invite.name)
        assertEquals("https://images.metahub.space/poster/small/tt14173636/img", invite.poster)
        assertEquals(950028, invite.tmdbId)
        // "Primetime" has no TMDB backdrop: Cinemeta's.
        assertEquals("https://images.metahub.space/background/medium/tt34206385/img", cards[4].title.background)

        // Fresh for 6 hours: no calls.
        assertEquals(listOf(Fixtures.expectedTop10("movie") to true), repo().answers(top10, 0, 15))
        assertEquals(1, tmdb.trendingCalls)
        // Then the old list at once and a new one; ids already known cost nothing, the miss is still remembered.
        now += 6 * 3600_000L
        val expected = Fixtures.expectedTop10("movie")
        assertEquals(listOf(expected to false, expected to true), repo().answers(top10, 0, 15))
        assertEquals(2, tmdb.trendingCalls)
        assertEquals(3, tmdb.idCalls.size)
        // Two days later TMDB is asked about the title without an IMDb id again.
        now += IdMap.MISS_KEPT_MS
        repo().answers(top10, 0, 15)
        assertEquals(listOf(999999901, 950028, 969681, 999999901), tmdb.idCalls)
        // The key is never written anywhere in the cache.
        assertTrue(File(dir, "vod").listFiles()!!.none { it.readText().contains(key) })
    }

    @Test
    fun aRefusedKeyHidesTop10UntilTheKeyChanges() {
        key = "0123456789abcdef0123456789abcdef"
        http.answers[movieTop] = Fixtures.text("cinemeta_catalog_movie_top.json")
        tmdb.trendingError = Tmdb.HttpError(401, "TMDB didn't accept this key")
        val top10 = row(Source.Trending(Kind.MOVIE), RowStyle.RANKED)
        val r = repo()
        assertEquals(listOf(emptyList<String>() to true), r.answers(top10))
        assertFalse(r.hasTmdbKey())
        // Not asked again, also after FirePlayer restarts.
        repo().answers(top10)
        assertEquals(1, tmdb.trendingCalls)
        assertFalse(repo().hasTmdbKey())
        // The header shows Cinemeta's Popular.
        assertEquals(popularIds, r.feeds(Page.MOVIES).last().titles.map { it.id })
        assertTrue(File(dir, "vod").listFiles()!!.none { it.readText().contains(key) })
        // A new key is tried.
        key = "fedcba9876543210fedcba9876543210"
        tmdb.trendingError = null
        assertTrue(r.hasTmdbKey())
        assertEquals(Fixtures.expectedTop10("movie"), r.answers(top10, 0, 15).last().first)
    }

    @Test
    fun tooManyRequestsKeepsTheOldListAndPauses() {
        key = "0123456789abcdef0123456789abcdef"
        http.answers[movieTop] = Fixtures.text("cinemeta_catalog_movie_top.json")
        val top10 = row(Source.Trending(Kind.MOVIE), RowStyle.RANKED)
        repo().answers(top10, 0, 15)
        now += 7 * 3600_000L
        tmdb.trendingError = Tmdb.HttpError(429, "Your request count is over the allowed limit")
        val r = repo()
        val expected = Fixtures.expectedTop10("movie")
        assertEquals(listOf(expected to false, expected to true), r.answers(top10, 0, 15))
        assertEquals(2, tmdb.trendingCalls)
        // Paused: the old list, without asking.
        assertEquals(listOf(expected to false, expected to true), r.answers(top10, 0, 15))
        assertEquals(2, tmdb.trendingCalls)
    }

    @Test
    fun lookupsFailingPartWayGiveWhatIsKnownForAWhile() {
        key = "0123456789abcdef0123456789abcdef"
        http.answers[movieTop] = Fixtures.text("cinemeta_catalog_movie_top.json")
        tmdb.idsError = IOException("offline")
        val top10 = row(Source.Trending(Kind.MOVIE), RowStyle.RANKED)
        // The 8 Cinemeta knew, ranked without gaps.
        assertEquals(listOf(popularIds to true), repo().answers(top10, 0, 15))
        // Asked again after a few minutes, not 6 hours.
        tmdb.idsError = null
        now += DefaultVodRepository.RETRY_MS
        assertEquals(Fixtures.expectedTop10("movie"), repo().answers(top10, 0, 15).last().first)
    }

    @Test
    fun aTop10ForBothRanksFilmsAndSeriesInTurn() {
        val both = row(Source.Trending(null), RowStyle.RANKED)
        // Without a key: none, as for one kind.
        assertEquals(listOf(emptyList<String>() to true), repo().answers(both))
        key = "0123456789abcdef0123456789abcdef"
        http.answers[movieTop] = Fixtures.text("cinemeta_catalog_movie_top.json")
        http.answers[seriesTop] = Fixtures.text("cinemeta_catalog_series_top.json")
        val r = repo()
        val answers = r.answers(both, 0, 15)
        assertEquals(TmdbTrending.interleave(Fixtures.expectedTop10("movie"), Fixtures.expectedTop10("tv")), answers.last().first)
        assertTrue(answers.last().second)
        assertEquals((1..10).toList(), r.cards(both).map { it.rank })
    }

    @Test
    fun rowsFollowSettingsRows() {
        val store = RowLayoutStore.Memory()
        val r = DefaultVodRepository(http, tmdb, { key }, JsonCache(File(dir, "vod")) { now }, ids, library,
            main = direct, io = direct, net = direct, lookups = direct, clock = { now }, rowStore = store)
        var told = 0
        r.addRowsListener { told++ }
        r.rowSettings.setOn(Page.MOVIES, "movies.featured", false)
        assertTrue(r.rowSettings.add(Page.MOVIES, RowLayouts.addedKey(Page.MOVIES, "genre.movie.Action")))
        assertEquals(2, told)
        val keys = r.rows(Page.MOVIES).map { it.key }
        assertFalse("movies.featured" in keys)
        assertEquals("movies.add.genre.movie.Action", keys.last())
        // Kept: a new repository reads the same rows.
        val again = DefaultVodRepository(http, tmdb, { key }, JsonCache(File(dir, "vod")) { now }, ids, library,
            main = direct, io = direct, net = direct, lookups = direct, clock = { now }, rowStore = store)
        assertEquals(keys, again.rows(Page.MOVIES).map { it.key })
        // Other pages untouched.
        assertEquals(PageRows.rows(Page.HOME, 2026).map { it.key }, again.rows(Page.HOME).map { it.key })
    }

    @Test
    fun homesHeaderMixesBothTop10s() {
        key = "0123456789abcdef0123456789abcdef"
        http.answers[movieTop] = Fixtures.text("cinemeta_catalog_movie_top.json")
        http.answers[seriesTop] = Fixtures.text("cinemeta_catalog_series_top.json")
        val feeds = repo().feeds(Page.HOME)
        assertEquals(1, feeds.size)
        assertEquals(
            TmdbTrending.interleave(Fixtures.expectedTop10("movie"), Fixtures.expectedTop10("tv")),
            feeds.single().titles.map { it.id },
        )
        assertEquals(Fixtures.expectedTop10("tv"), repo().feeds(Page.SHOWS).single().titles.map { it.id })
    }

    @Test
    fun theLibraryReachesListenersAndRows() {
        val r = repo()
        var heard = 0
        val listener: () -> Unit = { heard++ }
        r.addLibraryListener(listener)
        val film = Title("tt33764258", Kind.MOVIE, "The Odyssey")
        r.setInMyList(film, true)
        assertEquals(1, heard)
        assertTrue(r.inMyList(film.id))
        assertEquals(listOf(listOf(film.id) to true), r.answers(row(Source.MyList)))
        r.library.recordProgress(film, null, 10 * 60_000L, 100 * 60_000L)
        assertEquals(listOf(listOf(film.id) to true), r.answers(row(Source.ContinueWatching(null), RowStyle.WIDE)))
        assertEquals("1h 30m left", r.cards(row(Source.ContinueWatching(Kind.MOVIE), RowStyle.WIDE)).single().caption)
        assertEquals(listOf(emptyList<String>() to true), r.answers(row(Source.ContinueWatching(Kind.SERIES), RowStyle.WIDE)))
        r.removeFromContinueWatching(film.id)
        assertEquals(listOf(emptyList<String>() to true), r.answers(row(Source.ContinueWatching(null), RowStyle.WIDE)))
        r.setWatched(film, true)
        assertTrue(r.isWatched(film.id))
        assertEquals(4, heard)
        r.removeLibraryListener(listener)
        r.setInMyList(film, false)
        assertEquals(4, heard)
        // Next Episodes is empty until playback records some.
        assertEquals(listOf(emptyList<String>() to true), r.answers(row(Source.NextEpisodes())))
    }

    @Test
    fun genresComeFromTheManifestOrTheBuiltInList() {
        val r = repo()
        val offline = ArrayList<List<String>>()
        r.genres(Kind.SERIES) { offline += it }
        assertEquals(listOf(Cinemeta.SERIES_GENRES), offline)
        http.answers[Cinemeta.manifestUrl()] = """{"catalogs":[{"type":"series","id":"top","extra":[{"name":"genre","options":["Drama","Comedy"]}]}]}"""
        val got = ArrayList<List<String>>()
        r.genres(Kind.SERIES) { got += it }
        assertEquals(listOf(Cinemeta.SERIES_GENRES, listOf("Drama", "Comedy")), got)
        // Stored for a day: at once, without asking.
        val asked = http.count(Cinemeta.manifestUrl())
        val again = ArrayList<List<String>>()
        repo().genres(Kind.SERIES) { again += it }
        assertEquals(listOf(listOf("Drama", "Comedy")), again)
        assertEquals(asked, http.count(Cinemeta.manifestUrl()))
    }

    @Test
    fun newIsTheNewestYearCinemetaHas() {
        now = Instant.parse("2027-01-01T12:00:00Z").toEpochMilli()
        val r = repo()
        // Nothing known: this year.
        assertEquals("New in 2027", r.rows(Page.MOVIES).single { it.key == "movies.new" }.label)
        http.answers[Cinemeta.manifestUrl()] = Fixtures.text("cinemeta_manifest.json")
        val rows = repo().rows(Page.SHOWS)
        val new = rows.single { it.key == "shows.new" }
        assertEquals("New in 2026", new.label)
        assertEquals(Source.Catalog(Kind.SERIES, "year", "2026"), new.source)
        assertNull(rows.firstOrNull { it.label == "New in 2027" })
    }

    @Test
    fun aPageAskedForByTwoRowsAtOnceIsFetchedOnce() {
        http.answers[movieTop] = Fixtures.text("cinemeta_catalog_movie_top.json")
        http.gate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val r = DefaultVodRepository(http, tmdb, { key }, JsonCache(File(dir, "vod")) { now }, ids, library,
                main = direct, io = direct, net = pool, lookups = pool, clock = { now })
            val done = CountDownLatch(2)
            val sizes = java.util.Collections.synchronizedList(ArrayList<Int>())
            repeat(2) {
                r.load(row(Source.Catalog(Kind.MOVIE, "top"))) { cards, fresh ->
                    if (fresh) {
                        sizes += cards.size
                        done.countDown()
                    }
                }
            }
            Thread.sleep(100)
            http.gate!!.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(8, 8), sizes.toList())
            assertEquals(1, http.count(movieTop))
        } finally {
            pool.shutdownNow()
        }
    }

    // ---- My List and Search tabs ----

    @Test
    fun myListRowsHoldOneKindEachTheLatestAddedFirst() {
        val r = repo()
        val first = Title("tt0000011", Kind.MOVIE, "One")
        val series = Title("tt0000012", Kind.SERIES, "Two")
        val latest = Title("tt0000013", Kind.MOVIE, "Three")
        r.setInMyList(first, true)
        now += 1_000
        r.setInMyList(series, true)
        now += 1_000
        r.setInMyList(latest, true)
        assertEquals(listOf(listOf(latest.id, first.id) to true), r.answers(row(Source.MyListOf(Kind.MOVIE))))
        assertEquals(listOf(listOf(series.id) to true), r.answers(row(Source.MyListOf(Kind.SERIES))))
        r.setInMyList(series, false)
        assertEquals(listOf(emptyList<String>() to true), r.answers(row(Source.MyListOf(Kind.SERIES))))
    }

    @Test
    fun aSearchIsAskedOnceKeptAFewMinutesAndNeverForBlankWords() {
        val url = Cinemeta.searchUrl(Kind.MOVIE, "dune")
        assertEquals("${Cinemeta.BASE}/catalog/movie/top/search=dune.json", url)
        http.answers[url] = """{"metas":[{"id":"tt1160419","type":"movie","name":"Dune"},""" +
            """{"id":"tt15239678","type":"movie","name":"Dune: Part Two"}]}"""
        val r = repo()
        val spec = row(Source.Search(Kind.MOVIE, " dune "))
        val found = listOf("tt1160419", "tt15239678")
        assertEquals(listOf(found to true), r.answers(spec))
        assertEquals(listOf(found to true), r.answers(spec))
        assertEquals(1, http.count(url))
        // A few minutes on, asked again; offline then, the older answer stays.
        now += Cinemeta.SEARCH_FRESH_MS + 1
        http.answers.remove(url)
        assertEquals(listOf(found to true), r.answers(spec))
        assertEquals(2, http.count(url))
        // Never kept on disk: a new start asks again (and offline finds nothing).
        assertEquals(listOf(emptyList<String>() to true), repo().answers(spec))
        val asked = http.asked.size
        assertEquals(listOf(emptyList<String>() to true), r.answers(row(Source.Search(Kind.SERIES, "   "))))
        assertEquals(asked, http.asked.size)
    }

    @Test
    fun aSearchCancelledBeforeItsTurnIsNeverSent() {
        val url = Cinemeta.searchUrl(Kind.SERIES, "bear")
        http.answers[url] = """{"metas":[{"id":"tt14452776","type":"series","name":"The Bear"}]}"""
        val queued = ArrayList<Runnable>()
        val r = DefaultVodRepository(
            http = http,
            tmdb = tmdb,
            tmdbKey = { key },
            cache = JsonCache(File(dir, "vod")) { now },
            idStore = ids,
            libraryStore = library,
            main = direct,
            io = direct,
            net = Executor { queued += it },
            lookups = direct,
            clock = { now },
        )
        var heard = 0
        r.load(row(Source.Search(Kind.SERIES, "bear")), 0, 16) { _, _ -> heard++ }.cancel()
        queued.forEach { it.run() }
        assertEquals(0, heard)
        assertEquals(0, http.count(url))
    }
}
