package app.fireplayer.vod.data

import app.fireplayer.vod.Kind
import app.fireplayer.vod.data.TitlePageFixtures.tmdb
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TMDB's details call (synthetic answers in TMDB's documented shape) read for the title page. */
class TmdbDetailsTest {

    @Test
    fun aFilmsDetailsGiveRatingStoryCrewAndLists() {
        val e = tmdb("tmdb_movie_details_appended.json", Kind.MOVIE)
        assertEquals(693134, e.tmdbId)
        assertEquals("tt15239678", e.imdbId)
        // The theatrical certification, not the premiere's empty one nor the digital one.
        assertEquals("PG-13", e.ageRating)
        assertTrue(e.overview!!.startsWith("Follow the mythic journey"))
        assertEquals(167, e.runtimeMin)
        assertEquals("2024-02-27", e.releaseDate)
        assertEquals("/fixture_b693134.jpg", e.backdrop)
        assertEquals(listOf("Timothée Chalamet", "Zendaya", "Rebecca Ferguson", "Javier Bardem", "Josh Brolin", "Austin Butler"), e.cast)
        assertEquals(listOf("Denis Villeneuve"), e.directors)
        // Each writer once (Villeneuve is also the director); the composer isn't one.
        assertEquals(listOf("Denis Villeneuve", "Jon Spaihts", "Frank Herbert"), e.writers)
        assertEquals(20, e.recommendations.size)
        assertTrue(e.similar.isNotEmpty())
        assertTrue(e.createdBy.isEmpty())
    }

    @Test
    fun aShowsDetailsGiveRatingCreatorsAndItsImdbId() {
        val e = tmdb("tmdb_tv_details_appended.json", Kind.SERIES)
        assertEquals(33880, e.tmdbId)
        assertEquals("tt1695360", e.imdbId)
        assertEquals("TV-Y7", e.ageRating)
        assertEquals(listOf("Michael Dante DiMartino", "Bryan Konietzko"), e.createdBy)
        assertEquals("Ended", e.status)
        assertNull(e.runtimeMin)
        assertTrue(e.directors.isEmpty())
        // Items keep their own kind.
        assertTrue(e.recommendations.all { it.kind == Kind.SERIES })
        // No US rating, an empty overview, empty recommendations.
        val airing = tmdb("tmdb_tv_details_airing.json", Kind.SERIES)
        assertNull(airing.ageRating)
        assertNull(airing.overview)
        assertTrue(airing.recommendations.isEmpty())
        assertEquals("tt12324366", airing.imdbId)
    }

    @Test
    fun ratingsHideNotRatedAndRuntimeZeroIsNone() {
        val e = tmdb("tmdb_movie_details_upcoming.json", Kind.MOVIE)
        assertEquals("R", e.ageRating)
        assertNull(e.runtimeMin)
        assertNull(e.overview)
        val nr = JSONObject("""{"id":1,"release_dates":{"results":[{"iso_3166_1":"US","release_dates":[{"certification":"NR","type":3}]}]}}""")
        assertNull(TmdbDetails.ageRating(nr, Kind.MOVIE))
        val other = JSONObject("""{"id":1,"release_dates":{"results":[{"iso_3166_1":"US","release_dates":[{"certification":"","type":3},{"certification":"PG","type":4}]}]}}""")
        assertEquals("PG", TmdbDetails.ageRating(other, Kind.MOVIE))
        assertNull(TmdbDetails.ageRating(JSONObject("""{"content_ratings":{"results":[{"iso_3166_1":"GB","rating":"PG"}]}}"""), Kind.SERIES))
        assertNull(TmdbDetails.parse("""{"title":"No id"}""", Kind.MOVIE))
    }

    @Test
    fun theTrimmedFormReadsBackTheSame() {
        for ((name, kind) in listOf(
            "tmdb_movie_details_appended.json" to Kind.MOVIE,
            "tmdb_tv_details_appended.json" to Kind.SERIES,
            "tmdb_tv_details_airing.json" to Kind.SERIES,
            "tmdb_movie_details_upcoming.json" to Kind.MOVIE,
        )) {
            val e = tmdb(name, kind)
            assertEquals(name, e, TmdbDetails.read(JSONObject(TmdbDetails.write(e).toString())))
        }
        val film = TmdbDetails.write(tmdb("tmdb_movie_details_appended.json", Kind.MOVIE)).toString()
        assertTrue(film.length < Fixtures.text("tmdb_movie_details_appended.json").length)
    }

    @Test
    fun detailsPartsComeInTheSameAnswer() {
        val film = tmdb("tmdb_movie_details_appended.json", Kind.MOVIE)
        // A portrait per cast name, in the same order; one TMDB has none of stays empty in its place.
        assertEquals(listOf("/fixture_c1.jpg", "/fixture_c2.jpg", null, "/fixture_c4.jpg", "/fixture_c5.jpg", "/fixture_c6.jpg"), film.castPictures)
        assertEquals(film.cast.size, film.castPictures.size)
        assertEquals(listOf("Legendary Pictures"), film.studios)
        assertTrue(film.networks.isEmpty())
        assertEquals(listOf("United States of America"), film.countries)
        val show = tmdb("tmdb_tv_details_appended.json", Kind.SERIES)
        assertEquals(listOf("Nickelodeon"), show.networks)
        assertTrue(show.studios.isEmpty())
        // No portraits at all: none kept.
        assertTrue(show.castPictures.isEmpty())
        assertEquals(listOf("Disney+"), tmdb("tmdb_tv_details_airing.json", Kind.SERIES).networks)
        // Read defensively: odd entries, a duplicate name, a path that isn't one, more networks than kept.
        val odd = TmdbDetails.parse("""{"id":7,"networks":[{"name":"A"},"x",{"id":2},{"name":"A"},{"name":"B"},{"name":"C"},{"name":"D"}],
            "production_countries":[{"name":"France"},null],
            "credits":{"cast":[{"name":"One","profile_path":"x.jpg","order":1},{"name":"Two","profile_path":"/two.jpg","order":0},
            {"name":"Two","profile_path":"/again.jpg","order":2},{"profile_path":"/nameless.jpg"}]}}""", Kind.SERIES)!!
        assertEquals(listOf("A", "B", "C"), odd.networks)
        assertEquals(listOf("France"), odd.countries)
        assertEquals(listOf("Two", "One"), odd.cast)
        assertEquals(listOf("/two.jpg", null), odd.castPictures)
    }

    @Test
    fun storedDetailsReadBothWays() {
        val e = tmdb("tmdb_movie_details_appended.json", Kind.MOVIE)
        val stored = TmdbDetails.write(e)
        // The names stay a plain list, as every version stored them; Details' parts are keys of their own.
        assertEquals("Timothée Chalamet", stored.getJSONArray("cast").getString(0))
        assertEquals("", stored.getJSONArray("castPics").getString(2))
        // Details stored before these parts were kept read as before, with none of them.
        val old = JSONObject(stored.toString()).apply {
            remove("castPics"); remove("networks"); remove("studios"); remove("countries")
        }
        val read = TmdbDetails.read(old)!!
        assertEquals(e.copy(castPictures = emptyList(), networks = emptyList(), studios = emptyList(), countries = emptyList()), read)
        // Portraits that don't pair up with the names (a damaged entry) are left out, never shown beside the wrong name.
        val damaged = JSONObject(stored.toString()).apply { put("castPics", org.json.JSONArray(listOf("/a.jpg"))) }
        assertTrue(TmdbDetails.read(damaged)!!.castPictures.isEmpty())
        assertEquals(e.cast, TmdbDetails.read(damaged)!!.cast)
    }

    @Test
    fun oneCallAppendsEverythingAndKeysNeverHoldTheKey() {
        assertEquals("release_dates,credits,recommendations,similar", TmdbDetails.append(Kind.MOVIE))
        assertEquals("content_ratings,external_ids,credits,recommendations,similar", TmdbDetails.append(Kind.SERIES))
        assertEquals("tmdb|details|v1|tv|33880", TmdbDetails.key(Kind.SERIES, 33880))
        assertFalse(TmdbDetails.key(Kind.MOVIE, 1).contains("api_key"))
        assertEquals(1396, TmdbDetails.idFromFind("""{"movie_results":[],"tv_results":[{"id":1396}]}""", Kind.SERIES))
        assertNull(TmdbDetails.idFromFind("""{"movie_results":[],"tv_results":[{"id":1396}]}""", Kind.MOVIE))
    }
}
