package app.fireplayer.vod.data

import app.fireplayer.vod.Kind
import app.fireplayer.vod.Title
import app.fireplayer.vod.data.TitlePageFixtures.expected
import app.fireplayer.vod.data.TitlePageFixtures.meta
import app.fireplayer.vod.data.TitlePageFixtures.tmdb
import app.fireplayer.vod.hasImdbId
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** More Like This put together: TMDB's lists with a key, Cinemeta's director and genre without one. */
class MoreLikeThisTest {

    private val today = "2026-11-18"

    /** The TMDB → IMDb pairs Cinemeta's metas teach (those the expected file names). */
    private val pairs: Map<Int, String> by lazy {
        val out = HashMap<Int, String>()
        val mlt = expected.getJSONObject("more_like_this")
        for (k in mlt.keys()) {
            val a = mlt.getJSONArray(k)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                if (o.has("tmdb") && !o.isNull("tt")) out[o.getInt("tmdb")] = o.getString("tt")
            }
        }
        out
    }

    private val known = { _: Kind, id: Int -> pairs[id]?.let { TmdbTrending.Known.Hit(it) } ?: TmdbTrending.Known.Unknown }

    private fun expectedList(key: String): JSONArray = expected.getJSONObject("more_like_this").getJSONArray(key)

    private fun assertPicks(key: String, picks: List<MoreLikeThis.Pick>) {
        val want = expectedList(key)
        assertEquals(key, (0 until want.length()).map { want.getJSONObject(it).getInt("tmdb") }, picks.map { it.item.tmdbId })
        assertEquals(key, (0 until want.length()).map { want.getJSONObject(it).optString("tt").ifEmpty { null }.takeIf { s -> s != "null" } },
            picks.map { it.imdb })
        assertEquals(key, (0 until want.length()).map { want.getJSONObject(it).getString("name") }, picks.map { it.item.name })
        assertEquals(key, (0 until want.length()).map { want.getJSONObject(it).getString("year") }, picks.map { it.item.year })
    }

    @Test
    fun tmdbRecommendationsThenSimilarFiltered() {
        // Dune: Part Two: 15 recommendations (no poster, adult, few votes and future ones left out).
        assertPicks("tmdb_movie_details_appended.json",
            MoreLikeThis.fromTmdb(tmdb("tmdb_movie_details_appended.json", Kind.MOVIE), "tt15239678", today, known))
        // Korra: 8 recommendations topped up with 7 similar (itself, a repeat, few votes, upcoming left out).
        assertPicks("tmdb_tv_details_appended.json",
            MoreLikeThis.fromTmdb(tmdb("tmdb_tv_details_appended.json", Kind.SERIES), "tt1695360", today, known))
        // Percy Jackson: no recommendations, one usable similar title; I Play Rocky: nothing.
        assertPicks("tmdb_tv_details_airing.json",
            MoreLikeThis.fromTmdb(tmdb("tmdb_tv_details_airing.json", Kind.SERIES), "tt12324366", today, known))
        assertTrue(MoreLikeThis.fromTmdb(tmdb("tmdb_movie_details_upcoming.json", Kind.MOVIE), "tt11995650", today, known).isEmpty())
    }

    @Test
    fun aTitleTmdbHadNoImdbIdForLeavesTheRow() {
        val e = tmdb("tmdb_movie_details_appended.json", Kind.MOVIE)
        val missed = { k: Kind, id: Int -> if (id == 999999801) TmdbTrending.Known.Miss else known(k, id) }
        val picks = MoreLikeThis.fromTmdb(e, "tt15239678", today, missed)
        assertFalse(picks.any { it.item.tmdbId == 999999801 })
        assertEquals(15, picks.size)
        // Two TMDB titles pointing at one IMDb id: one card.
        val same = { _: Kind, _: Int -> TmdbTrending.Known.Hit("tt1") }
        assertEquals(1, MoreLikeThis.fromTmdb(e, "tt15239678", today, same).size)
    }

    @Test
    fun cardsUseMetahubOncTheImdbIdIsKnownElseAStandIn() {
        val e = tmdb("tmdb_movie_details_appended.json", Kind.MOVIE)
        val picks = MoreLikeThis.fromTmdb(e, "tt15239678", today, known)
        val dune = MoreLikeThis.card(picks.first(), null).title
        assertEquals("tt1160419", dune.id)
        assertEquals(VodArt.poster("tt1160419"), dune.poster)
        assertEquals("https://image.tmdb.org/t/p/w1280/fixture_b438631.jpg", dune.background)
        assertEquals(438631, dune.tmdbId)
        // Cinemeta's own title when it is known here (rating, genres).
        val cinemeta = Title("tt1160419", Kind.MOVIE, "Dune", year = "2021", imdbRating = "8.0", genres = listOf("Sci-Fi"))
        assertEquals("8.0", MoreLikeThis.card(picks.first(), cinemeta).title.imdbRating)
        val unmapped = MoreLikeThis.card(picks.first { it.imdb == null }, null).title
        assertEquals("tmdb:movie:999999801", unmapped.id)
        assertFalse(unmapped.hasImdbId)
        assertEquals(999999801, unmapped.tmdbId)
        assertEquals("https://image.tmdb.org/t/p/w185/fixture_p999999801.jpg", unmapped.poster)
        assertEquals("Unmapped Film", unmapped.name)
        assertEquals("2024", unmapped.year)
        assertEquals(999999801, MoreLikeThis.tmdbIdOfStandIn(unmapped.id))
        assertNull(MoreLikeThis.tmdbIdOfStandIn("tt1"))
    }

    @Test
    fun withoutAKeyADirectorsFilmsComeFirst() {
        val villeneuve = Cinemeta.parseSearch(Fixtures.text("cinemeta_search_director_villeneuve.json"), Kind.MOVIE).titles
        val picks = MoreLikeThis.byDirector(villeneuve, "Denis Villeneuve", "tt15239678", 2026)
        val want = expectedList("no key, film Dune: Part Two: director part (cinemeta_search_director_villeneuve.json)")
        assertEquals((0 until want.length()).map { want.getJSONObject(it).getString("tt") }, picks.map { it.id })
        // A noisy name: documentaries and namesakes are cut where the name gives them away.
        val tarantino = Cinemeta.parseSearch(Fixtures.text("cinemeta_search_director_tarantino.json"), Kind.MOVIE).titles
        val qt = MoreLikeThis.byDirector(tarantino, "Quentin Tarantino", "tt0110912", 2026)
        val wantQt = expectedList("no key, film Pulp Fiction: director part (cinemeta_search_director_tarantino.json)")
        assertEquals((0 until wantQt.length()).map { wantQt.getJSONObject(it).getString("tt") }, qt.map { it.id })
        assertTrue(MoreLikeThis.byDirector(villeneuve, " ", "tt1", 2026).isEmpty())
        assertEquals(86_400_000L, Cinemeta.parseSearch(Fixtures.text("cinemeta_search_director_villeneuve.json"), Kind.MOVIE).maxAgeMs)
    }

    @Test
    fun thenTheMainGenresPopularRankedByGenresShared() {
        val dune = meta("cinemeta_meta_film.json", Kind.MOVIE)
        val director = expectedList("no key, film Dune: Part Two: director part (cinemeta_search_director_villeneuve.json)")
        val exclude = (0 until director.length()).map { director.getJSONObject(it).getString("tt") }.toSet() + dune.title.id
        val action = Cinemeta.parseCatalog(Fixtures.text("cinemeta_catalog_movie_genre_action_cut.json"), Kind.MOVIE, "top", "Action", 2026).titles
        val films = MoreLikeThis.byGenre(action, dune.title.genres, exclude, 15 - director.length())
        val want = expectedList("no key, film Dune: Part Two: genre part (cinemeta_catalog_movie_genre_action_cut.json)")
        assertEquals((0 until want.length()).map { want.getJSONObject(it).getString("tt") }, films.map { it.id })

        val korra = meta("cinemeta_meta_show_ended_specials.json", Kind.SERIES)
        val animation = Cinemeta.parseCatalog(Fixtures.text("cinemeta_catalog_series_genre_animation_cut.json"), Kind.SERIES, "top", "Animation", 2026).titles
        val shows = MoreLikeThis.byGenre(animation, korra.title.genres, setOf(korra.title.id), 15)
        val wantShows = expectedList("no key, show Legend of Korra (cinemeta_catalog_series_genre_animation_cut.json)")
        assertEquals((0 until wantShows.length()).map { wantShows.getJSONObject(it).getString("tt") }, shows.map { it.id })
        assertTrue(MoreLikeThis.byGenre(animation, korra.title.genres, emptySet(), 0).isEmpty())
    }

    @Test
    fun joiningKeepsEachIdOnceNeverTheTitleAndAtMost15() {
        val self = Title("tt1", Kind.MOVIE, "Self", tmdbId = 9)
        fun card(id: String) = app.fireplayer.vod.Card(Title(id, Kind.MOVIE, id))
        val first = listOf(card("tt2"), card("tt1"), card("tmdb:movie:9"), card("tt3"))
        val more = (2..30).map { card("tt$it") }
        val joined = MoreLikeThis.join(self, first, more)
        assertEquals(15, joined.size)
        assertEquals(listOf("tt2", "tt3", "tt4", "tt5"), joined.take(4).map { it.title.id })
        assertEquals(joined.size, joined.map { it.title.id }.toSet().size)
    }
}
