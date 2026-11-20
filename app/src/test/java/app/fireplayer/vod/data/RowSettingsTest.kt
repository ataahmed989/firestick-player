package app.fireplayer.vod.data

import app.fireplayer.vod.Card
import app.fireplayer.vod.Kind
import app.fireplayer.vod.Page
import app.fireplayer.vod.RowStyle
import app.fireplayer.vod.Source
import app.fireplayer.vod.Title
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RowSettingsTest {

    private val store = RowLayoutStore.Memory()
    private val year = { _: Kind -> 2026 }
    private fun settings() = RowSettings(store, year)
    private fun key(page: Page, what: String) = RowLayouts.addedKey(page, what)

    @Test
    fun withoutChangesEveryPageHasItsDefaultRows() {
        val s = settings()
        for (page in Page.entries) {
            assertEquals(PageRows.rows(page, 2026), s.rows(page))
            assertTrue(s.isDefault(page))
            assertTrue(s.entries(page).all { it.on && !it.added })
        }
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun switchesOrderAndAddedRowsAreKeptPerPage() {
        val s = settings()
        s.setOn(Page.HOME, "home.my_list", false)
        s.setOrder(Page.HOME, listOf("home.popular_shows", "home.continue"))
        assertTrue(s.add(Page.HOME, key(Page.HOME, "top10.both")))
        val expected = listOf("home.popular_shows", "home.continue", "home.next_episodes", "home.popular_movies", "home.add.top10.both")
        assertEquals(expected, s.rows(Page.HOME).map { it.key })
        assertFalse(s.isDefault(Page.HOME))
        // Read again from what was stored: the same, and the other pages untouched.
        val again = settings()
        assertEquals(expected, again.rows(Page.HOME).map { it.key })
        assertEquals(listOf(false), again.entries(Page.HOME).filter { it.spec.key == "home.my_list" }.map { it.on })
        assertEquals(PageRows.rows(Page.MOVIES, 2026), again.rows(Page.MOVIES))
        assertEquals(setOf(Page.HOME), store.saved.keys)
    }

    @Test
    fun addedRowsAreTheSameKindsOfRowsThePagesHave() {
        val home = { what: String -> RowLayouts.addedSpec(Page.HOME, key(Page.HOME, what), year)!! }
        home("top10.both").let {
            assertEquals("Top 10 This Week", it.label)
            assertEquals(RowStyle.RANKED, it.style)
            assertEquals(Source.Trending(null), it.source)
        }
        home("top10.series").let {
            assertEquals("Top 10 This Week · Series", it.label)
            assertEquals(RowStyle.RANKED, it.style)
        }
        home("genre.movie.Action").let {
            assertEquals("Action · Movies", it.label)
            assertEquals(Source.Catalog(Kind.MOVIE, Cinemeta.TOP, "Action"), it.source)
            assertEquals(RowStyle.POSTER, it.style)
        }
        home("new.series").let {
            assertEquals("New in 2026 · Series", it.label)
            assertEquals(Source.Catalog(Kind.SERIES, Cinemeta.YEAR, "2026"), it.source)
        }
        home("continue.movie").let {
            assertEquals(RowStyle.WIDE, it.style)
            assertTrue(it.continueRow)
            assertEquals(Source.ContinueWatching(Kind.MOVIE), it.source)
        }
        assertEquals(RowStyle.GENRES, home("genres.series").style)
        assertEquals(Source.Genres(Kind.SERIES), home("genres.series").source)
        assertEquals(Source.MyList, RowLayouts.addedSpec(Page.MOVIES, key(Page.MOVIES, "my_list"), year)!!.source)
        // On a kind's own tab, its rows need no kind after their name; the other kind's and both do.
        assertEquals("Action", RowLayouts.addedSpec(Page.MOVIES, key(Page.MOVIES, "genre.movie.Action"), year)!!.label)
        assertEquals("Popular · Series", RowLayouts.addedSpec(Page.MOVIES, key(Page.MOVIES, "popular.series"), year)!!.label)
        assertEquals("Top 10 This Week · Movies & Series", RowLayouts.addedSpec(Page.SHOWS, key(Page.SHOWS, "top10.both"), year)!!.label)
        // Not an added row, another page's, or nonsense.
        assertNull(RowLayouts.addedSpec(Page.HOME, "home.my_list", year))
        assertNull(RowLayouts.addedSpec(Page.HOME, key(Page.MOVIES, "popular.movie"), year))
        assertNull(RowLayouts.addedSpec(Page.HOME, key(Page.HOME, "popular.both"), year))
        assertNull(RowLayouts.addedSpec(Page.HOME, key(Page.HOME, "genre.movie"), year))
        assertNull(RowLayouts.addedSpec(Page.HOME, key(Page.HOME, "weather"), year))
    }

    @Test
    fun noRowTwiceOnAPage() {
        val s = settings()
        // Home has Popular Movies already: the same source by another key is no new row.
        assertFalse(s.add(Page.HOME, key(Page.HOME, "popular.movie")))
        assertFalse(s.add(Page.HOME, key(Page.HOME, "my_list")))
        assertTrue(s.add(Page.HOME, key(Page.HOME, "genre.movie.Action")))
        assertFalse(s.add(Page.HOME, key(Page.HOME, "genre.movie.Action")))
        assertTrue(s.add(Page.HOME, key(Page.HOME, "genre.series.Action")))
        assertFalse(s.add(Page.HOME, "home.unknown"))
        // Nor among the choices offered.
        val offered = s.choices(Page.HOME, hasTmdbKey = true).map { it.key }
        assertFalse(key(Page.HOME, "popular.movie") in offered)
        assertFalse(key(Page.HOME, "my_list") in offered)
        assertFalse(key(Page.HOME, "continue.both") in offered)
        assertFalse(key(Page.HOME, "next_episodes") in offered)
        assertTrue(key(Page.HOME, "featured.movie") in offered)
        assertEquals(offered.size, offered.toSet().size)
        val genres = s.genreChoices(Page.HOME, Kind.MOVIE, listOf("Action", "Comedy", "Action")).map { it.label }
        assertEquals(listOf("Comedy · Movies"), genres)
        // A stored list with repeats (by key or by source) keeps the first.
        store.saved[Page.MOVIES] = "+movies.popular\n+movies.popular\n-movies.add.popular.movie\n+movies.genres"
        assertEquals(1, settings().entries(Page.MOVIES).count { it.spec.source == Source.Catalog(Kind.MOVIE, Cinemeta.TOP) })
        // An added row stored before the default row showing the same: the default row is the one kept.
        store.saved[Page.MOVIES] = "+movies.add.popular.movie\n-movies.popular"
        val kept = settings().entries(Page.MOVIES).filter { it.spec.source == Source.Catalog(Kind.MOVIE, Cinemeta.TOP) }
        assertEquals(listOf(RowEntry(PageRows.rows(Page.MOVIES, 2026).single { it.key == "movies.popular" }, on = true, added = false)), kept)
    }

    @Test
    fun top10IsOfferedOnlyWithATmdbKey() {
        val s = settings()
        val without = s.choices(Page.HOME, hasTmdbKey = false)
        assertTrue(without.none { it.key.contains(".top10.") })
        val with = s.choices(Page.HOME, hasTmdbKey = true)
        assertEquals(
            listOf(key(Page.HOME, "top10.movie"), key(Page.HOME, "top10.series"), key(Page.HOME, "top10.both")),
            with.map { it.key }.filter { it.contains(".top10.") },
        )
        // Movies has its own Top 10: films' isn't offered again.
        assertFalse(key(Page.MOVIES, "top10.movie") in s.choices(Page.MOVIES, hasTmdbKey = true).map { it.key })
        assertEquals(listOf("Movies", "Series", RowLayouts.BOTH_GROUP), with.map { it.group }.distinct())
    }

    @Test
    fun onlyAddedRowsCanBeRemovedAndResetBringsTheDefaultsBack() {
        val s = settings()
        s.remove(Page.SHOWS, "shows.popular")
        assertTrue(s.rows(Page.SHOWS).any { it.key == "shows.popular" })
        val added = key(Page.SHOWS, "my_list")
        assertTrue(s.add(Page.SHOWS, added))
        assertTrue(s.entries(Page.SHOWS).single { it.spec.key == added }.added)
        s.remove(Page.SHOWS, added)
        assertFalse(s.rows(Page.SHOWS).any { it.key == added })
        s.setOn(Page.SHOWS, "shows.genres", false)
        s.reset(Page.SHOWS)
        assertEquals(PageRows.rows(Page.SHOWS, 2026), s.rows(Page.SHOWS))
        assertTrue(s.isDefault(Page.SHOWS))
        assertNull(store.saved[Page.SHOWS])
    }

    @Test
    fun storedRowsSurviveVersionsAndJunk() {
        // A row this version doesn't know goes; a default row missing from what was stored (new in a later version)
        // comes back on, after the default row before it; junk lines are skipped.
        store.saved[Page.MOVIES] = "+movies.featured\nnonsense\n-movies.popular\n+movies.add.weather\n+movies.continue"
        assertEquals(
            listOf(
                "movies.featured" to true, "movies.genres" to true, "movies.popular" to false, "movies.new" to true,
                "movies.continue" to true, "movies.top10" to true,
            ),
            settings().entries(Page.MOVIES).map { it.spec.key to it.on },
        )
        assertEquals(RowLayouts.decode("+a\n-b\n\nc"), listOf("a" to true, "b" to false))
        assertEquals("+a\n-b", RowLayouts.encode(listOf("a" to true, "b" to false)))
    }

    @Test
    fun newInFollowsTheYearOfEachKind() {
        val years = mapOf(Kind.MOVIE to 2026, Kind.SERIES to 2025)
        val s = RowSettings(store) { years.getValue(it) }
        assertTrue(s.add(Page.HOME, key(Page.HOME, "new.series")))
        assertEquals("New in 2025 · Series", s.rows(Page.HOME).last().label)
        assertEquals("New in 2026", s.rows(Page.MOVIES).single { it.key == "movies.new" }.label)
    }

    @Test
    fun pagesAreToldOnlyOfRealChanges() {
        val s = settings()
        var told = 0
        val listener = { told++; Unit }
        s.addListener(listener)
        s.addListener(listener)
        s.setOn(Page.HOME, "home.my_list", true)
        assertEquals(1, told)
        s.setOn(Page.HOME, "home.my_list", true)
        s.add(Page.HOME, key(Page.HOME, "my_list"))
        assertEquals(1, told)
        s.setOn(Page.HOME, "home.my_list", false)
        assertEquals(2, told)
        s.removeListener(listener)
        s.reset(Page.HOME)
        assertEquals(2, told)
    }

    @Test
    fun aTop10ForBothMixesFilmsAndSeriesAndIsFreshOnlyOnceBothAre() {
        val sent = ArrayList<Pair<List<String>, Boolean>>()
        val mix = DefaultVodRepository.TopMix { cards, fresh -> sent += cards.map { "${it.rank}:${it.title.id}" } to fresh }
        fun cards(prefix: String, n: Int) = (1..n).map { Card(Title("$prefix$it", Kind.MOVIE, "x"), rank = it) }
        mix.update(Kind.MOVIE, cards("m", 6), fresh = false)
        assertTrue(sent.isEmpty())
        mix.update(Kind.SERIES, cards("s", 6), fresh = true)
        assertEquals(listOf("1:m1", "2:s1", "3:m2", "4:s2", "5:m3", "6:s3", "7:m4", "8:s4", "9:m5", "10:s5") to false, sent.single())
        // The same list, now fresh: passed on once more, fresh.
        mix.update(Kind.MOVIE, cards("m", 6), fresh = true)
        assertEquals(2, sent.size)
        assertTrue(sent.last().second)
        mix.update(Kind.MOVIE, cards("m", 6), fresh = true)
        assertEquals(2, sent.size)
    }
}
