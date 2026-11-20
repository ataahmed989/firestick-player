package app.fireplayer.vod.data

import app.fireplayer.vod.Kind
import app.fireplayer.vod.Title
import app.fireplayer.vod.data.TmdbTrending.Known
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbTrendingTest {

    private val map = Fixtures.externalIds()

    @Test
    fun aTrendingListIsReadInOrder() {
        val films = TmdbTrending.parse(Fixtures.text("tmdb_trending_movie_week.json"), Kind.MOVIE)
        assertEquals(13, films.size)
        val first = films[0]
        assertEquals(1368337, first.tmdbId)
        assertEquals("The Odyssey", first.name)
        assertEquals("2026", first.year)
        assertEquals("/synthMovieBackdrop00.jpg", first.backdrop)
        // No date, no backdrop.
        assertNull(films[2].year)
        assertNull(films[2].backdrop)
        val shows = TmdbTrending.parse(Fixtures.text("tmdb_trending_tv_week.json"), Kind.SERIES)
        assertEquals("The Lord of the Rings: The Rings of Power", shows[0].name)
        assertEquals("2022", shows[0].year)
        assertTrue(shows.all { it.kind == Kind.SERIES })
    }

    @Test
    fun theMixedListDropsPeople() {
        val all = TmdbTrending.parse(Fixtures.text("tmdb_trending_all_week.json"), null)
        assertEquals(12, all.size)
        assertTrue(all.none { it.name == "Ana de Armas" })
        assertEquals(Kind.SERIES, all[1].kind)
        // Asked for films only, the shows of a mixed list go too.
        assertTrue(TmdbTrending.parse(Fixtures.text("tmdb_trending_all_week.json"), Kind.MOVIE).all { it.kind == Kind.MOVIE })
        assertTrue(TmdbTrending.parse("""{"results":[{"id":1,"title":"X","adult":true}]}""", Kind.MOVIE).isEmpty())
        assertTrue(TmdbTrending.parse("""{"page":1}""", Kind.MOVIE).isEmpty())
    }

    @Test
    fun imdbIdsFromExternalIds() {
        assertEquals("tt0137523", TmdbTrending.imdbOf(Fixtures.text("tmdb_movie_external_ids.json")))
        assertEquals("tt0903747", TmdbTrending.imdbOf(Fixtures.text("tmdb_tv_external_ids.json")))
        assertNull(TmdbTrending.imdbOf(Fixtures.text("tmdb_external_ids_no_imdb.json")))
        assertNull(TmdbTrending.imdbOf("""{"imdb_id":""}"""))
        assertNull(TmdbTrending.imdbOf("""{"imdb_id":"nm0000001"}"""))
    }

    /** Walks [TmdbTrending.toAsk] like the repository does, answering from the map; the calls it made. */
    private fun resolve(kind: String, list: List<TmdbTrending.Candidate>, startKnown: Map<Int, Known> = emptyMap()): Pair<List<Pair<TmdbTrending.Candidate, String>>, List<Int>> {
        val known = HashMap(startKnown)
        val asked = ArrayList<Int>()
        val lookup = { c: TmdbTrending.Candidate -> known[c.tmdbId] ?: Known.Unknown }
        while (true) {
            val ask = TmdbTrending.toAsk(list, lookup)
            if (ask.isEmpty()) break
            for (c in ask) {
                asked += c.tmdbId
                known[c.tmdbId] = map.getValue(kind)[c.tmdbId]?.let { Known.Hit(it) } ?: Known.Miss
            }
        }
        return TmdbTrending.ranked(list, lookup) to asked
    }

    @Test
    fun theTop10IsExactlyTheExpectedTenRankedWithoutGaps() {
        for ((kind, file) in listOf("movie" to "tmdb_trending_movie_week.json", "tv" to "tmdb_trending_tv_week.json")) {
            val list = TmdbTrending.parse(Fixtures.text(file), if (kind == "movie") Kind.MOVIE else Kind.SERIES)
            val (ranked, asked) = resolve(kind, list)
            assertEquals(Fixtures.expectedTop10(kind), ranked.map { it.second })
            // Cold: one lookup per title until 10 are found, the one without an IMDb id included, never more.
            assertEquals(11, asked.size)
        }
    }

    @Test
    fun knownIdsCostNoCalls() {
        val list = TmdbTrending.parse(Fixtures.text("tmdb_trending_movie_week.json"), Kind.MOVIE)
        // Cinemeta's Popular page (the first 8 films) is known, and the film without an IMDb id was missed lately.
        val cinemeta = list.take(9).associate { c ->
            c.tmdbId to (map.getValue("movie")[c.tmdbId]?.let { Known.Hit(it) } ?: Known.Miss)
        }
        val (ranked, asked) = resolve("movie", list, cinemeta)
        assertEquals(Fixtures.expectedTop10("movie"), ranked.map { it.second })
        assertEquals(listOf(950028, 969681), asked)
        // Everything known: nothing asked.
        val (_, none) = resolve("movie", list, list.associate { c ->
            c.tmdbId to (map.getValue("movie")[c.tmdbId]?.let { Known.Hit(it) } ?: Known.Miss)
        })
        assertTrue(none.isEmpty())
    }

    @Test
    fun askingStopsWhereTenCanBeReached() {
        val list = (1..20).map { TmdbTrending.Candidate(it, Kind.MOVIE, "T$it", null, null, null) }
        val known = { c: TmdbTrending.Candidate -> if (c.tmdbId <= 6) Known.Hit("tt$c") else Known.Unknown }
        assertEquals(listOf(7, 8, 9, 10), TmdbTrending.toAsk(list, known).map { it.tmdbId })
        // Fewer than 10 exist: everything is ranked that has an id.
        val few = list.take(4)
        assertEquals(4, TmdbTrending.ranked(few, { Known.Hit("tt${it.tmdbId}") }).size)
    }

    @Test
    fun aTop10TitlePrefersCinemetasDataAndTmdbsBackdrop() {
        val c = TmdbTrending.Candidate(1368337, Kind.MOVIE, "The Odyssey", "2026", "TMDB's story", "/b.jpg")
        val cinemeta = Title("tt33764258", Kind.MOVIE, "The Odyssey", year = "2026", imdbRating = "8.4", genres = listOf("Action"),
            poster = VodArt.poster("tt33764258"), background = VodArt.background("tt33764258"), description = "Cinemeta's story")
        val t = TmdbTrending.title(c, "tt33764258", cinemeta)
        assertEquals("8.4", t.imdbRating)
        assertEquals("Cinemeta's story", t.description)
        assertEquals("https://image.tmdb.org/t/p/w1280/b.jpg", t.background)
        assertEquals(1368337, t.tmdbId)
        // Unknown to Cinemeta: made from TMDB's list, with metahub's pictures for the IMDb id.
        val bare = TmdbTrending.title(c.copy(backdrop = null), "tt33764258", null)
        assertEquals("TMDB's story", bare.description)
        assertEquals("https://images.metahub.space/poster/small/tt33764258/img", bare.poster)
        assertEquals("https://images.metahub.space/background/medium/tt33764258/img", bare.background)
        assertEquals("2026", bare.year)
        // A Cinemeta title of the other kind with the same id isn't used.
        val show = cinemeta.copy(kind = Kind.SERIES, name = "Another")
        assertEquals("The Odyssey", TmdbTrending.title(c, "tt33764258", show).name)
    }

    @Test
    fun homeTakesFilmsAndSeriesInTurn() {
        val films = (1..10).map { "M$it" }
        val shows = (1..10).map { "S$it" }
        assertEquals(listOf("M1", "S1", "M2", "S2", "M3", "S3", "M4", "S4", "M5", "S5"), TmdbTrending.interleave(films, shows))
        // The longer list fills in.
        assertEquals(listOf("M1", "S1", "M2", "S2", "S3", "S4", "S5", "S6", "S7", "S8"), TmdbTrending.interleave(films.take(2), shows))
        assertEquals(listOf("M1", "M2"), TmdbTrending.interleave(films.take(2), emptyList()))
    }
}
