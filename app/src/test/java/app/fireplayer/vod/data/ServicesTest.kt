package app.fireplayer.vod.data

import app.fireplayer.vod.Kind
import app.fireplayer.vod.Page
import app.fireplayer.vod.RowStyle
import app.fireplayer.vod.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServicesTest {

    @Test
    fun theRailKeepsTheTwoServicesVisitedLastNewestFirst() {
        var recent = emptyList<Service>()
        recent = Services.visited(recent, Service.NETFLIX)
        assertEquals(listOf(Service.NETFLIX), recent)
        recent = Services.visited(recent, Service.DISNEY)
        assertEquals(listOf(Service.DISNEY, Service.NETFLIX), recent)
        // Revisited: to the front, never twice.
        recent = Services.visited(recent, Service.NETFLIX)
        assertEquals(listOf(Service.NETFLIX, Service.DISNEY), recent)
        assertEquals(recent, Services.visited(recent, Service.NETFLIX))
        // A third pushes the oldest out.
        assertEquals(listOf(Service.HULU, Service.NETFLIX), Services.visited(recent, Service.HULU))
    }

    @Test
    fun recentServicesAreStoredAndReadBack() {
        val recent = listOf(Service.HBO_MAX, Service.APPLE_TV)
        assertEquals("hbo_max,apple_tv", Services.encode(recent))
        assertEquals(recent, Services.decode(Services.encode(recent)))
        assertEquals(emptyList<Service>(), Services.decode(null))
        assertEquals(emptyList<Service>(), Services.decode(""))
        // Unknown names, repeats and more than two are left out.
        assertEquals(listOf(Service.HULU, Service.DISNEY), Services.decode("hulu,gone,hulu,disney,netflix"))
    }

    @Test
    fun homeShowsTheFiveServicesInOrderUnderContinueWatching() {
        assertEquals(listOf("Disney+", "Hulu", "Netflix", "HBO Max", "Apple TV"), Services.cards().map { it.title.name })
        val home = PageRows.rows(Page.HOME, 2026).map { it.key }
        assertEquals(home.indexOf("home.continue") + 1, home.indexOf("home.services"))
        assertTrue(home.indexOf("home.services") < home.indexOf("home.popular_movies"))
        assertEquals("Streaming Services", Services.homeRow().label)
        for (card in Services.cards()) assertEquals(card.title.name, Services.serviceOf(card.title.id)?.label)
        assertNull(Services.serviceOf("brand:pixar"))
    }

    @Test
    fun aViewersOwnRowOrderGetsTheNewRowUnderContinueWatching() {
        val store = RowLayoutStore.Memory()
        store.write(Page.HOME, "+home.popular_movies\n+home.continue\n-home.my_list\n+home.next_episodes\n+home.popular_shows")
        val rows = RowSettings(store) { 2026 }
        assertEquals(
            listOf("home.popular_movies", "home.continue", "home.services", "home.next_episodes", "home.popular_shows"),
            rows.rows(Page.HOME).map { it.key },
        )
        // And it moves like any other row.
        rows.setOrder(Page.HOME, listOf("home.services"))
        assertEquals("home.services", rows.rows(Page.HOME).first().key)
    }

    @Test
    fun aServicePageHasItsPopularRowsAndDisneyItsBrands() {
        val netflix = Services.rows(Service.NETFLIX)
        assertEquals(listOf("Popular Movies", "Popular Shows"), netflix.map { it.label })
        assertEquals(listOf(Source.Discover(Kind.MOVIE, 8), Source.Discover(Kind.SERIES, 8)), netflix.map { it.source })
        val disney = Services.rows(Service.DISNEY)
        assertEquals(listOf(RowStyle.LOGO, RowStyle.POSTER, RowStyle.POSTER), disney.map { it.style })
        assertEquals(Source.Brands, disney.first().source)
        assertEquals(listOf("Disney", "Pixar", "Marvel", "Star Wars", "National Geographic"), Services.brandCards().map { it.title.name })
        assertEquals(Brand.PIXAR, Services.brandOf(Services.brandCards()[1].title.id))
        // A brand's collection: films and series, on Disney+, page after page.
        assertEquals(Source.Discover(null, 337, "3", null, full = true), Services.collection(Brand.PIXAR).source)
        assertEquals(setOf(337, 15, 8, 1899, 350), Service.entries.map { it.providerId }.toSet())
    }

    @Test
    fun oneColourWordmarksAreKnownByTheirAddress() {
        assertTrue(Services.isMono(Services.logoUrl(Service.DISNEY.logo)))
        assertFalse(Services.isMono(Services.logoUrl(Service.NETFLIX.logo)))
        assertFalse(Services.isMono(null))
        assertNull(Services.logoUrl(Brand.STAR_WARS.logo))
    }

    @Test
    fun discoverAsksForUsSubscriptionTitlesMostPopularFirst() {
        val row = Source.Discover(Kind.MOVIE, 337)
        assertEquals("discover/movie", Discover.path(Kind.MOVIE))
        assertEquals("discover/tv", Discover.path(Kind.SERIES))
        val q = Discover.params(row, Kind.MOVIE, 1).toMap()
        assertEquals("US", q["watch_region"])
        assertEquals("337", q["with_watch_providers"])
        assertEquals("flatrate", q["with_watch_monetization_types"])
        assertEquals("popularity.desc", q["sort_by"])
        assertEquals("false", q["include_adult"])
        assertEquals("1", q["page"])
        assertFalse("with_companies" in q)
        // A brand: its companies; a series by its networks when the brand has them.
        val natGeo = Services.collection(Brand.NAT_GEO).source as Source.Discover
        assertEquals("7521", Discover.params(natGeo, Kind.MOVIE, 3).toMap()["with_companies"])
        val tv = Discover.params(natGeo, Kind.SERIES, 3).toMap()
        assertEquals("43", tv["with_networks"])
        assertFalse("with_companies" in tv)
        assertEquals("3", tv["page"])
        assertEquals("337", tv["with_watch_providers"])
        val pixar = Services.collection(Brand.PIXAR).source as Source.Discover
        assertEquals("3", Discover.params(pixar, Kind.SERIES, 1).toMap()["with_companies"])
        // Each page is kept under its own name.
        assertFalse(Discover.key(pixar, Kind.MOVIE, 1) == Discover.key(pixar, Kind.SERIES, 1))
        assertFalse(Discover.key(pixar, Kind.MOVIE, 1) == Discover.key(pixar, Kind.MOVIE, 2))
        assertFalse(Discover.key(pixar, Kind.MOVIE, 1) == Discover.key(row, Kind.MOVIE, 1))
    }

    @Test
    fun onlyThePagesAGridAsksForAreRead() {
        // One kind: 20 to a page. Films and series in turn: 40.
        assertEquals(1..1, Discover.pages(0, 16, 1))
        assertEquals(1..3, Discover.pages(0, 50, 1))
        assertEquals(1..2, Discover.pages(0, 50, 2))
        assertEquals(2..3, Discover.pages(50, 50, 2))
        assertTrue(Discover.pages(0, 0, 2).isEmpty())
    }

    @Test
    fun aCollectionTakesFilmsAndSeriesInTurnWithoutGapsOrRepeats() {
        fun item(kind: Kind, n: Int) = TmdbDetails.Item(tmdbId = n, kind = kind, name = "$kind $n")
        // Films run to three pages; series end on their second (5 titles).
        val films = { p: Int -> if (p <= 3) (0 until 20).map { item(Kind.MOVIE, p * 100 + it) } else null }
        val series = { p: Int -> when (p) { 1 -> (0 until 20).map { item(Kind.SERIES, 100 + it) }; 2 -> (0 until 5).map { item(Kind.SERIES, 200 + it) }; else -> null } }
        val got = { k: Kind, p: Int -> if (k == Kind.MOVIE) films(p) else series(p) }
        val kinds = listOf(Kind.MOVIE, Kind.SERIES)
        val all = ArrayList<TmdbDetails.Item>()
        var skip = 0
        while (true) {
            val part = Discover.slice(Discover.pages(skip, 50, 2), kinds, skip, 50, got)
            if (part.isEmpty()) break
            all += part
            skip += 50
        }
        assertEquals(85, all.size)
        assertEquals(85, all.map { it.kind to it.tmdbId }.toSet().size)
        assertEquals(listOf(Kind.MOVIE, Kind.SERIES, Kind.MOVIE, Kind.SERIES), all.take(4).map { it.kind })
        // A row: the first 16 of its one kind's first page.
        val row = Discover.slice(1..1, listOf(Kind.MOVIE), 0, 16, got)
        assertEquals((100 until 116).toList(), row.map { it.tmdbId })
    }

    @Test
    fun aDiscoverAnswerIsReadWithItsLastPage() {
        val text = """{"page":2,"total_pages":2,"results":[{"id":11,"title":"Star Wars","release_date":"1977-05-25","poster_path":"/p.jpg","vote_count":9},{"id":0,"title":"x"}]}"""
        val page = Discover.parse(text, Kind.MOVIE, 2)
        assertEquals(listOf(11), page.items.map { it.tmdbId })
        assertEquals("/p.jpg", page.items[0].poster)
        assertTrue(page.last)
        assertFalse(Discover.parse(text, Kind.MOVIE, 1).last)
    }
}
