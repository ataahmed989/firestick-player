package app.fireplayer.vod.data

import app.fireplayer.subs.Tmdb
import app.fireplayer.vod.Card
import app.fireplayer.vod.From
import app.fireplayer.vod.Kind
import app.fireplayer.vod.RowSpec
import app.fireplayer.vod.RowStyle
import app.fireplayer.vod.Source
import app.fireplayer.vod.Title
import app.fireplayer.vod.TitleDetails
import app.fireplayer.vod.data.DefaultVodRepositoryTest.FakeHttp
import app.fireplayer.vod.data.DefaultVodRepositoryTest.FakeTmdb
import app.fireplayer.vod.hasImdbId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor

/** The title page's calls on the real repository: recorded Cinemeta answers, TMDB fixtures, no network. */
class TitlePageRepositoryTest {

    private val dir: File = Files.createTempDirectory("vodtitle").toFile()
    private var now = TitlePageFixtures.NOW
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

    private val dune = Title("tt15239678", Kind.MOVIE, "Dune: Part Two", year = "2024", tmdbId = 693134,
        background = "https://images.metahub.space/background/medium/tt15239678/img")
    private val korra = Title("tt1695360", Kind.SERIES, "The Legend of Korra", tmdbId = 33880)
    private val duneMeta = Cinemeta.metaUrl(Kind.MOVIE, dune.id)
    private val korraMeta = Cinemeta.metaUrl(Kind.SERIES, korra.id)

    private fun serveDune() {
        http.answers[duneMeta] = Fixtures.text("cinemeta_meta_film.json")
        tmdb.details["movie|693134"] = Fixtures.text("tmdb_movie_details_appended.json")
    }

    private fun DefaultVodRepository.allDetails(title: Title): List<Pair<TitleDetails, Boolean>> {
        val out = ArrayList<Pair<TitleDetails, Boolean>>()
        details(title) { d, fresh -> out += d to fresh }
        return out
    }

    private fun DefaultVodRepository.allSimilar(title: Title): List<Pair<List<Card>, Boolean>> {
        val out = ArrayList<Pair<List<Card>, Boolean>>()
        moreLikeThis(title) { cards, fresh -> out += cards to fresh }
        return out
    }

    @Test
    fun withoutAKeyTheMetaIsFetchedOnceThenStoredAbout4Hours() {
        serveDune()
        val first = repo().allDetails(dune)
        assertEquals(1, first.size)
        val (d, fresh) = first.single()
        assertTrue(fresh)
        assertEquals("Dune: Part Two", d.title.name)
        assertEquals(167, d.runtimeMin)
        assertEquals(From.CINEMETA, d.story!!.from)
        assertNull(d.ageRating)
        assertEquals("Won 2 Oscars", d.oscars)
        // No key: TMDB is never asked.
        assertTrue(tmdb.detailsCalls.isEmpty())
        // Opened again (FirePlayer restarted): from the disk, no fetch, one fresh call.
        val again = repo().allDetails(dune)
        assertEquals(listOf(true), again.map { it.second })
        assertEquals(d, again.single().first)
        assertEquals(1, http.count(duneMeta))
        // Four hours on: the stored details at once, then the fetched ones.
        now += CinemetaDetails.FRESH_MS
        val later = repo().allDetails(dune)
        assertEquals(listOf(false, true), later.map { it.second })
        assertEquals(2, http.count(duneMeta))
    }

    @Test
    fun withNothingStoredAndCinemetaDownTheTitleAloneIsTheOneCall() {
        val calls = repo().allDetails(dune)
        assertEquals(1, calls.size)
        assertTrue(calls.single().second)
        assertEquals("Dune: Part Two", calls.single().first.title.name)
        assertEquals(dune.background, calls.single().first.title.background)
        assertTrue(calls.single().first.seasons.isEmpty())
    }

    @Test
    fun withAKeyTmdbAddsRatingStoryAndCrewAndTheBackdropNeverChangesMidVisit() {
        key = "k".repeat(32)
        serveDune()
        val calls = repo().allDetails(dune)
        // Cinemeta's meta first, then with TMDB's details merged in (the last one fresh).
        assertEquals(listOf(false, true), calls.map { it.second })
        assertNull(calls[0].first.ageRating)
        val d = calls.last().first
        assertEquals("PG-13", d.ageRating)
        assertEquals(From.TMDB, d.story!!.from)
        assertEquals(From.TMDB, d.directors!!.from)
        assertEquals(listOf("tmdb|details|v1|movie|693134"), tmdbDetailsKeys())
        // The backdrop is the same in every call of the visit (the row's, as nothing was stored).
        assertTrue(calls.all { it.first.title.background == dune.background })
        assertEquals(listOf("movie|693134"), tmdb.detailsCalls)
        // The next visit: everything stored and fresh, TMDB's smaller backdrop chosen, no request.
        val next = repo().allDetails(dune)
        assertEquals(listOf(true), next.map { it.second })
        assertEquals("https://image.tmdb.org/t/p/w1280/fixture_b693134.jpg", next.single().first.title.background)
        assertEquals(1, tmdb.detailsCalls.size)
        assertEquals(1, http.count(duneMeta))
        // A day on: TMDB is asked again; a failure keeps what was stored.
        now += TmdbDetails.FRESH_MS
        tmdb.detailsError = java.io.IOException("offline")
        val stale = repo().allDetails(dune)
        assertEquals(true, stale.last().second)
        assertEquals("PG-13", stale.last().first.ageRating)
    }

    @Test
    fun aRefusedKeyCountsAsNone() {
        key = "k".repeat(32)
        serveDune()
        tmdb.detailsError = Tmdb.HttpError(401, "TMDB didn't accept this key")
        val r = repo()
        val calls = r.allDetails(dune)
        assertTrue(calls.last().second)
        assertNull(calls.last().first.ageRating)
        assertFalse(r.hasTmdbKey())
        // No TMDB More Like This either: Cinemeta's (nothing served here), never an error.
        val similar = r.allSimilar(dune)
        assertTrue(similar.last().second)
    }

    @Test
    fun aCancelledRequestSaysNothing() {
        serveDune()
        val queued = ArrayList<Runnable>()
        main = Executor { queued += it }
        val r = repo()
        var heard = 0
        val cancel = r.details(dune) { _, _ -> heard++ }
        val cancel2 = r.moreLikeThis(dune) { _, _ -> heard++ }
        val cancel3 = r.resolve(dune) { heard++ }
        cancel.cancel()
        cancel2.cancel()
        cancel3.cancel()
        queued.forEach { it.run() }
        assertEquals(0, heard)
    }

    @Test
    fun aShowsSeasonsComeSortedWithItsRankFromTheStoredTop10() {
        http.answers[korraMeta] = Fixtures.text("cinemeta_meta_show_ended_specials.json")
        val d = repo().allDetails(korra).last().first
        assertEquals(listOf(1, 2, 3, 4, 0), d.seasons.map { it.number })
        assertEquals(99, d.episodes.size)
        assertEquals("Ended", d.status)
        assertEquals(From.CINEMETA, d.creators!!.from)
        assertNull(d.top10Rank)
        // With a key and a stored Top 10 holding the show at 4.
        key = "k".repeat(32)
        val cache = JsonCache(File(dir, "vod")) { now }
        val top = (1..10).map { i -> Card(Title(if (i == 4) korra.id else "tt$i", Kind.SERIES, "S$i"), rank = i) }
        cache.write("tmdb|trending|series|week", org.json.JSONObject().put("cards", TitleJson.writeCards(top)), 6 * 3600_000L, now)
        tmdb.details["series|33880"] = Fixtures.text("tmdb_tv_details_appended.json")
        val ranked = repo().allDetails(korra).last().first
        assertEquals(4, ranked.top10Rank)
        assertEquals("TV-Y7", ranked.ageRating)
        assertEquals(From.TMDB, ranked.creators!!.from)
        // A day old Top 10 no longer ranks it.
        now += DefaultVodRepository.RANK_KEPT_MS
        assertNull(repo().allDetails(korra).last().first.top10Rank)
    }

    @Test
    fun aMetaWithoutATmdbIdFindsItOnce() {
        key = "k".repeat(32)
        val floor = Title("tt7914040", Kind.SERIES, "The Floor")
        http.answers[Cinemeta.metaUrl(Kind.SERIES, floor.id)] = Fixtures.text("cinemeta_meta_show_no_tmdb_id.json")
        tmdb.finds[floor.id] = """{"movie_results":[],"tv_results":[{"id":222100}]}"""
        tmdb.details["series|222100"] = """{"id":222100,"overview":"Contestants compete on a giant floor.","content_ratings":{"results":[{"iso_3166_1":"US","rating":"TV-PG"}]}}"""
        val d = repo().allDetails(floor).last().first
        assertEquals("TV-PG", d.ageRating)
        assertEquals(222100, d.title.tmdbId)
        assertEquals(listOf(floor.id), tmdb.findCalls)
        // The find is kept: the next visit asks nothing new.
        repo().allDetails(floor)
        assertEquals(1, tmdb.findCalls.size)
    }

    @Test
    fun moreLikeThisWithAKeyComesFromTheSameTmdbCall() {
        key = "k".repeat(32)
        serveDune()
        val r = repo()
        r.allDetails(dune)
        val calls = r.allSimilar(dune)
        // Stored by the details call and fresh: one fresh answer, no new request.
        assertEquals(listOf(true), calls.map { it.second })
        assertEquals(1, tmdb.detailsCalls.size)
        val cards = calls.single().first
        assertEquals(15, cards.size)
        assertFalse(cards.any { it.title.id == dune.id })
        // Dune (2021) isn't known here yet: a stand-in to resolve; its poster is TMDB's.
        val first = cards.first().title
        assertEquals("tmdb:movie:438631", first.id)
        assertFalse(first.hasImdbId)
        assertEquals(438631, first.tmdbId)
        assertEquals("https://image.tmdb.org/t/p/w185/fixture_p438631.jpg", first.poster)
    }

    @Test
    fun moreLikeThisFetchesTmdbItselfWhenOpenedFirst() {
        key = "k".repeat(32)
        tmdb.details["movie|693134"] = Fixtures.text("tmdb_movie_details_appended.json")
        // Oppenheimer is known from a Cinemeta page: its card is Cinemeta's, with metahub's poster.
        http.answers[Cinemeta.catalogUrl(Kind.MOVIE, "top", null, 0)] = """{"metas":[{"id":"tt15398776","type":"movie","name":"Oppenheimer","releaseInfo":"2023","year":"2023","moviedb_id":872585,"imdbRating":"8.2"}]}"""
        val r = repo()
        r.load(RowSpec("p", "Popular", RowStyle.POSTER, Source.Catalog(Kind.MOVIE, "top"))) { _, _ -> }
        val cards = r.allSimilar(dune).last().first
        val opp = cards.first { it.title.tmdbId == 872585 }.title
        assertEquals("tt15398776", opp.id)
        assertEquals("8.2", opp.imdbRating)
        assertEquals(VodArt.poster("tt15398776"), opp.poster)
        assertEquals(1, tmdb.detailsCalls.size)
    }

    @Test
    fun withoutAKeyMoreLikeThisIsTheDirectorsFilmsThenTheGenre() {
        serveDune()
        val search = Cinemeta.searchUrl(Kind.MOVIE, "Denis Villeneuve")
        assertEquals("https://v3-cinemeta.strem.io/catalog/movie/top/search=Denis%20Villeneuve.json", search)
        http.answers[search] = Fixtures.text("cinemeta_search_director_villeneuve.json")
        val genre = Cinemeta.catalogUrl(Kind.MOVIE, "top", "Action", 0)
        http.answers[genre] = Fixtures.text("cinemeta_catalog_movie_genre_action_cut.json")
        val r = repo()
        val calls = r.allSimilar(dune)
        assertEquals(listOf(true), calls.map { it.second })
        val ids = calls.single().first.map { it.title.id }
        assertEquals(15, ids.size)
        assertEquals(listOf("tt1160419", "tt1392214", "tt2543164", "tt1856101", "tt3397884"), ids.take(5))
        assertEquals("tt33764258", ids[5])
        assertFalse(dune.id in ids)
        assertTrue(ids.all { it.startsWith("tt") })
        // Kept: asked again, nothing is fetched.
        val asked = http.asked.size
        assertEquals(listOf(true), repo().allSimilar(dune).map { it.second })
        assertEquals(asked, http.asked.size)
        // A show: the genre only.
        http.answers[korraMeta] = Fixtures.text("cinemeta_meta_show_ended_specials.json")
        http.answers[Cinemeta.catalogUrl(Kind.SERIES, "top", "Animation", 0)] = Fixtures.text("cinemeta_catalog_series_genre_animation_cut.json")
        val shows = r.allSimilar(korra).last().first.map { it.title.id }
        assertEquals(listOf("tt7441658", "tt2560140", "tt0388629"), shows.take(3))
        // Nothing reachable: one empty answer.
        assertEquals(listOf(emptyList<Card>() to true), repo().allSimilar(Title("tt404", Kind.MOVIE, "Nothing")))
    }

    @Test
    fun moreLikeThisBuiltFromPartsNeverStoredWaitsForTheFreshAnswer() {
        val genre = Cinemeta.catalogUrl(Kind.MOVIE, "top", "Action", 0)
        http.answers[genre] = Fixtures.text("cinemeta_catalog_movie_genre_action_cut.json")
        val r = repo()
        // Another Action film's page stored the genre's Popular page.
        r.allSimilar(Title("tt0000001", Kind.MOVIE, "Other", genres = listOf("Action")))
        assertEquals(1, http.count(genre))
        // Dune's meta was never stored: a list from the genre alone would be replaced by the director's films first.
        serveDune()
        http.answers[Cinemeta.searchUrl(Kind.MOVIE, "Denis Villeneuve")] = Fixtures.text("cinemeta_search_director_villeneuve.json")
        val calls = r.allSimilar(dune.copy(genres = listOf("Action")))
        assertEquals(listOf(true), calls.map { it.second })
        assertEquals("tt1160419", calls.single().first.first().title.id)
        // Stored now (parts old, not missing): the stored answer shows at once, the fresh one follows.
        now += CinemetaDetails.FRESH_MS + 1
        assertEquals(listOf(false, true), repo().allSimilar(dune.copy(genres = listOf("Action"))).map { it.second })
    }

    @Test
    fun aStandInResolvesFromTheIdMapOrItsOwnDetailsCall() {
        key = "k".repeat(32)
        val r = repo()
        val standIn = Title("tmdb:movie:999999801", Kind.MOVIE, "Unmapped Film", year = "2024",
            poster = "https://image.tmdb.org/t/p/w185/p.jpg", background = "https://image.tmdb.org/t/p/w1280/b.jpg", tmdbId = 999999801)
        tmdb.details["movie|999999801"] = """{"id":999999801,"imdb_id":"tt9999901","title":"Unmapped Film"}"""
        var got: Title? = null
        var calls = 0
        r.resolve(standIn) { got = it; calls++ }
        assertEquals(1, calls)
        assertEquals("tt9999901", got!!.id)
        assertEquals(VodArt.poster("tt9999901"), got!!.poster)
        assertEquals(standIn.background, got!!.background)
        assertEquals(999999801, got!!.tmdbId)
        // Learnt: the next time without a request.
        r.resolve(standIn) { got = it }
        assertEquals(1, tmdb.detailsCalls.size)
        // A title with its IMDb id: as it is, at once.
        r.resolve(dune) { got = it }
        assertEquals(dune, got)
        // TMDB has no IMDb id for it: null, and remembered for a while.
        tmdb.details["movie|5"] = """{"id":5,"imdb_id":null,"title":"No id"}"""
        var none: Title? = dune
        r.resolve(Title("tmdb:movie:5", Kind.MOVIE, "No id", tmdbId = 5)) { none = it }
        assertNull(none)
        assertEquals(TmdbTrending.Known.Miss, IdMap(ids, { now }, direct).known(Kind.MOVIE, 5))
        // No key and nothing known: null.
        key = ""
        var nokey: Title? = dune
        repo().resolve(Title("tmdb:movie:6", Kind.MOVIE, "Six", tmdbId = 6)) { nokey = it }
        assertNull(nokey)
    }

    @Test
    fun theLibraryAnswersTheTitlePage() {
        http.answers[korraMeta] = Fixtures.text("cinemeta_meta_show_ended_specials.json")
        val r = repo()
        var heard = 0
        r.addLibraryListener { heard++ }
        val d = r.allDetails(korra).last().first
        val s2e3 = d.episodes.first { it.season == 2 && it.episode == 3 }
        r.markWatchedUpTo(d, s2e3)
        assertEquals(1, heard)
        assertEquals(1, library.batches)
        assertTrue(r.isEpisodeWatched(d.episodes.first()))
        assertTrue(r.isEpisodeWatched(s2e3))
        assertFalse(r.isEpisodeWatched(d.episodes.first { it.season == 2 && it.episode == 4 }))
        assertFalse(d.episodes.filter { it.isSpecial }.any(r::isEpisodeWatched))
        assertEquals(12 + 3, d.episodes.count(r::isEpisodeWatched))
        r.setEpisodesWatched(listOf(s2e3), false)
        assertFalse(r.isEpisodeWatched(s2e3))
        assertEquals(2, heard)
        // Playback (step 3) writes where it stopped; the page reads it back.
        r.recordProgress(korra, s2e3.ref, 10 * 60_000L, 23 * 60_000L)
        assertEquals(3, heard)
        assertTrue(r.inContinueWatching(korra.id))
        assertEquals(10 * 60_000L, r.positionOf(s2e3)!!.positionMs)
        assertNotNull(r.lastPlayed(korra))
        assertNull(r.positionOf(d.episodes.first()))
        r.removeFromContinueWatching(korra.id)
        assertFalse(r.inContinueWatching(korra.id))
        assertTrue(r.lastPlayed(korra)!!.hidden)
    }

    @Test
    fun theCacheNeverHoldsTheKey() {
        key = "SECRET-KEY-0123456789"
        serveDune()
        http.answers[korraMeta] = Fixtures.text("cinemeta_meta_show_ended_specials.json")
        tmdb.details["series|33880"] = Fixtures.text("tmdb_tv_details_appended.json")
        val r = repo()
        r.allDetails(dune)
        r.allDetails(korra)
        r.allSimilar(dune)
        r.allSimilar(korra)
        val files = File(dir, "vod").listFiles().orEmpty()
        assertTrue(files.isNotEmpty())
        assertTrue(files.none { it.readText().contains(key) })
    }

    private fun tmdbDetailsKeys(): List<String> {
        val cache = JsonCache(File(dir, "vod")) { now }
        return listOf(TmdbDetails.key(Kind.MOVIE, 693134)).filter { cache.read(it) != null }
    }
}
