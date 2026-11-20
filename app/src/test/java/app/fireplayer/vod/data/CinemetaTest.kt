package app.fireplayer.vod.data

import app.fireplayer.vod.Kind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CinemetaTest {

    @Test
    fun addressesAreWrittenOneWay() {
        assertEquals("https://v3-cinemeta.strem.io/catalog/movie/top.json", Cinemeta.catalogUrl(Kind.MOVIE, "top", null, 0))
        assertEquals("https://v3-cinemeta.strem.io/catalog/movie/top/skip=50.json", Cinemeta.catalogUrl(Kind.MOVIE, "top", null, 50))
        assertEquals(
            "https://v3-cinemeta.strem.io/catalog/movie/top/genre=Action&skip=50.json",
            Cinemeta.catalogUrl(Kind.MOVIE, "top", "Action", 50),
        )
        assertEquals(
            "https://v3-cinemeta.strem.io/catalog/series/year/genre=2026.json",
            Cinemeta.catalogUrl(Kind.SERIES, "year", "2026", 0),
        )
        assertEquals(
            "https://v3-cinemeta.strem.io/catalog/series/imdbRating/genre=Reality-TV.json",
            Cinemeta.catalogUrl(Kind.SERIES, "imdbRating", "Reality-TV", 0),
        )
        // A space is %20, never +.
        assertEquals(
            "https://v3-cinemeta.strem.io/catalog/movie/top/genre=Science%20Fiction.json",
            Cinemeta.catalogUrl(Kind.MOVIE, "top", "Science Fiction", 0),
        )
        assertEquals("https://v3-cinemeta.strem.io/meta/series/tt0903747.json", Cinemeta.metaUrl(Kind.SERIES, "tt0903747"))
    }

    @Test
    fun aFilmPageBecomesTitles() {
        val page = Cinemeta.parseCatalog(Fixtures.text("cinemeta_catalog_movie_top.json"), Kind.MOVIE, "top", null, 2026)
        assertEquals(8, page.titles.size)
        assertTrue(page.hasMore)
        assertEquals(4 * 3600_000L, page.maxAgeMs)
        val odyssey = page.titles[0]
        assertEquals("tt33764258", odyssey.id)
        assertEquals(Kind.MOVIE, odyssey.kind)
        assertEquals("The Odyssey", odyssey.name)
        assertEquals("2026", odyssey.year)
        assertEquals("8.4", odyssey.imdbRating)
        assertEquals(listOf("Action", "Adventure", "Drama"), odyssey.genres)
        assertEquals("173 min", odyssey.runtime)
        assertEquals(1368337, odyssey.tmdbId)
        assertEquals("https://images.metahub.space/poster/small/tt33764258/img", odyssey.poster)
        assertEquals("https://images.metahub.space/background/medium/tt33764258/img", odyssey.background)
        assertEquals("https://images.metahub.space/logo/medium/tt33764258/img", odyssey.logo)
        // A blank rating is none.
        assertNull(page.titles.single { it.name == "Resident Evil" }.imdbRating)
    }

    @Test
    fun aSeriesPageSkipsEpisodesAndKeepsItsYears() {
        val page = Cinemeta.parseCatalog(Fixtures.text("cinemeta_catalog_series_top.json"), Kind.SERIES, "top", null, 2026)
        assertEquals(listOf("tt7631058", "tt8014982", "tt31510819", "tt23112594"), page.titles.map { it.id })
        assertEquals("2022–", page.titles[0].year)
        // A miniseries can be a single year.
        assertEquals("2026", page.titles[3].year)
        // tt8014982 is "The Body" here, a series.
        assertEquals("The Body", page.titles[1].name)
        assertEquals(Kind.SERIES, page.titles[1].kind)
        assertEquals(291507, page.titles[1].tmdbId)
    }

    @Test
    fun cinemetasJunkLeavesFilmCatalogs() {
        val page = Cinemeta.parseCatalog(
            Fixtures.text("cinemeta_catalog_movie_year_2026_noise.json"), Kind.MOVIE, "year", "2026", 2026,
        )
        // Zeta (2025, wrong year), The Prince of Arcadia (1932, cross-wired, no year), Barber's Sorrow (2002, no year)
        // and Cyberpunk: Edgerunners (a series) go; the three 2026 films stay.
        assertEquals(listOf("Heartland", "Ebenezer: A Christmas Carol", "How to Rob a Bank"), page.titles.map { it.name })
    }

    @Test
    fun theFilmFiltersOneByOne() {
        val noise = Fixtures.text("cinemeta_catalog_movie_year_2026_noise.json")
        // As Popular (not a year catalog): only the year filter is off, so Zeta (2025, has a year) stays.
        val popular = Cinemeta.parseCatalog(noise, Kind.MOVIE, "top", null, 2026)
        assertEquals(listOf("Zeta", "Heartland", "Ebenezer: A Christmas Carol", "How to Rob a Bank"), popular.titles.map { it.name })
        fun film(fields: String) = JSONObject("""{"id":"tt1","type":"movie","name":"X",$fields}""")
        fun junk(fields: String) = Cinemeta.isJunkFilm(film(fields), Cinemeta.parsePreview(film(fields), Kind.MOVIE)!!, 2026)
        assertTrue(junk(""""releaseInfo":"2022–","year":"2022–""""))
        assertTrue(junk(""""releaseInfo":"2008-2013","year":"2008-2013""""))
        assertTrue(junk(""""releaseInfo":"1932""""))
        assertFalse(junk(""""releaseInfo":"2025""""))
        assertFalse(junk(""""releaseInfo":"1999","year":"1999""""))
        assertFalse(junk(""""releaseInfo":"2026-07-17","year":"2026""""))
    }

    @Test
    fun missingFieldsAreTolerated() {
        val metas = Fixtures.json("cinemeta_previews_missing_fields.json").getJSONArray("metas")
        val titles = (0 until metas.length()).map { Cinemeta.parsePreview(metas.getJSONObject(it), Kind.MOVIE) }
        // Campus Drivers: no description, a blank rating, genres given.
        val campus = titles[0]!!
        assertEquals(Kind.SERIES, campus.kind)
        assertNull(campus.description)
        assertNull(campus.imdbRating)
        assertEquals("2026–", campus.year)
        // L'Avocat: no genres at all.
        assertEquals(emptyList<String>(), titles[1]!!.genres)
        assertEquals("2027", titles[1]!!.year)
        // Knife Edge: no year field, no runtime.
        assertNull(titles[2]!!.runtime)
        assertEquals("2025–", titles[2]!!.year)
        assertEquals("https://live.metahub.space/poster/small/tt36935784/img", titles[2]!!.poster)
        // Only an id, a type and a name: pictures are metahub's for the id.
        val minimal = titles[3]!!
        assertEquals("Synthetic Minimal", minimal.name)
        assertNull(minimal.year)
        assertNull(minimal.tmdbId)
        assertEquals("https://images.metahub.space/poster/small/tt0000001/img", minimal.poster)
        // The older "genre" field, a null runtime and poster, blank strings.
        val old = titles[4]!!
        assertEquals(listOf("Drama"), old.genres)
        assertEquals("1999", old.year)
        assertNull(old.runtime)
        assertNull(old.description)
        assertEquals("https://images.metahub.space/poster/small/tt0000002/img", old.poster)
        assertEquals("2024–", titles[5]!!.year)
        // Not an IMDb id.
        assertNull(titles[6])
    }

    @Test
    fun theLastPageAndTheEnd() {
        val last = Cinemeta.parseCatalog(Fixtures.text("cinemeta_catalog_last_page.json"), Kind.SERIES, "year", "2026", 2026)
        assertEquals(4, last.titles.size)
        assertFalse(last.hasMore)
        val end = Cinemeta.parseCatalog(Fixtures.text("cinemeta_catalog_end.json"), Kind.MOVIE, "top", "Nonexistent", 2026)
        assertTrue(end.titles.isEmpty())
        assertFalse(end.hasMore)
        // Without hasMore: more as long as titles came.
        assertFalse(Cinemeta.parseCatalog("""{"metas":[]}""", Kind.MOVIE, "top", null, 2026).hasMore)
        assertTrue(Cinemeta.parseCatalog(
            """{"metas":[{"id":"tt1","type":"movie","name":"A","releaseInfo":"2020","year":"2020"}]}""", Kind.MOVIE, "top", null, 2026,
        ).hasMore)
    }

    @Test
    fun repeatsAndOtherTypesGo() {
        val text = """{"metas":[
            {"id":"tt1","type":"movie","name":"A","releaseInfo":"2020","year":"2020"},
            {"id":"tt1","type":"movie","name":"A again","releaseInfo":"2020","year":"2020"},
            {"id":"tt2","type":"series","name":"B","releaseInfo":"2020"},
            {"id":"tt3","type":"movie","name":"","releaseInfo":"2020"}
        ],"hasMore":true,"cacheMaxAge":600}"""
        val page = Cinemeta.parseCatalog(text, Kind.MOVIE, "top", null, 2026)
        assertEquals(listOf("A"), page.titles.map { it.name })
        assertEquals(600_000L, page.maxAgeMs)
    }

    @Test
    fun metasAreReadWithTheirKind() {
        val film = Cinemeta.parseMeta(Fixtures.text("cinemeta_meta_movie.json"), Kind.MOVIE)!!
        assertEquals("tt0137523", film.id)
        assertEquals("Fight Club", film.name)
        assertEquals("8.8", film.imdbRating)
        assertEquals(550, film.tmdbId)
        val show = Cinemeta.parseMeta(Fixtures.text("cinemeta_meta_series.json"), Kind.SERIES)!!
        assertEquals("tt0903747", show.id)
        assertEquals(Kind.SERIES, show.kind)
        assertEquals(1396, show.tmdbId)
        // Asked with the wrong type, Cinemeta answers {}.
        assertNull(Cinemeta.parseMeta(Fixtures.text("cinemeta_meta_empty.json"), Kind.SERIES))
        // A film's meta isn't taken for a series.
        assertNull(Cinemeta.parseMeta(Fixtures.text("cinemeta_meta_movie.json"), Kind.SERIES))
    }

    @Test
    fun theManifestGivesGenresAndYears() {
        val m = Cinemeta.parseManifest(Fixtures.text("cinemeta_manifest.json"))
        assertEquals(Cinemeta.MOVIE_GENRES, m.genres[Kind.MOVIE])
        assertEquals(Cinemeta.SERIES_GENRES, m.genres[Kind.SERIES])
        assertEquals("2026", m.years[Kind.MOVIE]!!.first())
        assertEquals(107, m.years[Kind.MOVIE]!!.size)
        assertEquals(67, m.years[Kind.SERIES]!!.size)
        // Without a manifest the built-in lists.
        assertEquals(22, Cinemeta.genres(null, Kind.SERIES).size)
        assertEquals(19, Cinemeta.genres(Cinemeta.Manifest(emptyMap(), emptyMap()), Kind.MOVIE).size)
    }

    @Test
    fun theNewYearIsThisYearWhenListed() {
        val years = listOf("2026", "2025", "2024")
        assertEquals(2026, Cinemeta.newYear(years, 2026))
        // On 1 January Cinemeta may not have the new year yet.
        assertEquals(2026, Cinemeta.newYear(years, 2027))
        assertEquals(2027, Cinemeta.newYear(null, 2027))
        assertEquals(2027, Cinemeta.newYear(emptyList(), 2027))
    }
}
