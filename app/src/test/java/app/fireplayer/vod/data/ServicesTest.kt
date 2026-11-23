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
    fun homeShowsTheSixServicesInOrderUnderContinueWatching() {
        assertEquals(listOf("Disney+", "Hulu", "Netflix", "HBO Max", "Apple TV", "Prime Video"), Services.cards().map { it.title.name })
        assertEquals(RowStyle.SERVICES, Services.homeRow().style)
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
        assertEquals(setOf(337, 15, 8, 1899, 350, 9), Service.entries.map { it.providerId }.toSet())
    }

    @Test
    fun primeVideosPageHasItsUsSubscriptionTitlesWithAdsOrNot() {
        val prime = Services.rows(Service.PRIME_VIDEO)
        assertEquals(listOf("Popular Movies", "Popular Shows"), prime.map { it.label })
        val movies = prime[0].source as Source.Discover
        assertEquals(Source.Discover(Kind.MOVIE, 9, alsoProvider = 2100), movies)
        val q = Discover.params(movies, Kind.MOVIE, 1).toMap()
        // Amazon Prime Video or its with-ads tier, on a US subscription.
        assertEquals("9|2100", q["with_watch_providers"])
        assertEquals("US", q["watch_region"])
        assertEquals("flatrate", q["with_watch_monetization_types"])
        // Kept apart from a row of provider 9 alone; the other services' rows ask as before.
        assertFalse(Discover.key(movies, Kind.MOVIE, 1) == Discover.key(Source.Discover(Kind.MOVIE, 9), Kind.MOVIE, 1))
        assertEquals("8", Discover.params(Services.rows(Service.NETFLIX)[0].source as Source.Discover, Kind.MOVIE, 1).toMap()["with_watch_providers"])
        assertEquals(
            "tmdb|discover|v1|${Kind.MOVIE.type}|8|||1",
            Discover.key(Source.Discover(Kind.MOVIE, 8), Kind.MOVIE, 1),
        )
        assertEquals(Service.PRIME_VIDEO, Services.serviceOf(Services.cards().last().title.id))
    }

    // ---- Home's order ---------------------------------------------------------------------------------------------

    @Test
    fun homesServiceOrderIsTheDefaultUntilTheViewerSetsOne() {
        val default = listOf(Service.DISNEY, Service.HULU, Service.NETFLIX, Service.HBO_MAX, Service.APPLE_TV, Service.PRIME_VIDEO)
        assertEquals(default, Services.order(null))
        assertEquals(default, Services.order(""))
        // The viewer's order, read back.
        val mine = listOf(Service.PRIME_VIDEO, Service.NETFLIX, Service.DISNEY, Service.APPLE_TV, Service.HULU, Service.HBO_MAX)
        assertEquals(mine, Services.order(Services.encode(mine)))
        assertEquals(mine.map { it.label }, Services.cards(mine).map { it.title.name })
        // Unknown names and repeats left out; every service once.
        assertEquals(default, Services.order("gone,disney,disney,hulu"))
    }

    @Test
    fun aServiceNewToTheAppJoinsASavedOrderAtTheEnd() {
        // Saved before Prime Video came: the viewer's five as they were, Prime Video after them.
        val saved = "netflix,hbo_max,disney,apple_tv,hulu"
        assertEquals(
            listOf(Service.NETFLIX, Service.HBO_MAX, Service.DISNEY, Service.APPLE_TV, Service.HULU, Service.PRIME_VIDEO),
            Services.order(saved),
        )
        // Two missing: after the rest, in their default order.
        assertEquals(
            listOf(Service.APPLE_TV, Service.NETFLIX, Service.DISNEY, Service.HULU, Service.HBO_MAX, Service.PRIME_VIDEO),
            Services.order("apple_tv,netflix"),
        )
    }

    @Test
    fun theViewersServiceOrderIsStoredAndHomeIsTold() {
        val store = RowLayoutStore.Memory()
        var told = 0
        val rows = RowSettings(store) { 2026 }.apply { addListener { told++ } }
        assertEquals(Service.entries, rows.serviceOrder())
        val mine = listOf(Service.PRIME_VIDEO) + Service.entries.dropLast(1)
        rows.setServiceOrder(mine)
        assertEquals(mine, rows.serviceOrder())
        assertEquals("prime_video,disney,hulu,netflix,hbo_max,apple_tv", store.services)
        assertEquals(1, told)
        // The same order again changes nothing; read again after a restart, it's the viewer's.
        rows.setServiceOrder(mine)
        assertEquals(1, told)
        assertEquals(mine, RowSettings(store) { 2026 }.serviceOrder())
        // Back to the default: nothing stored.
        rows.setServiceOrder(Service.entries)
        assertNull(store.services)
        assertEquals(2, told)
    }

    // ---- The rail's service shortcuts -------------------------------------------------------------------------------

    @Test
    fun withNothingPinnedTheShortcutsAreTheTwoServicesOpenedLastNewestFirst() {
        assertEquals(listOf<Service?>(null, null), Services.railSlots(true, emptyList(), emptyList()))
        assertEquals(listOf(Service.HULU, null), Services.railSlots(true, emptyList(), listOf(Service.HULU)))
        assertEquals(listOf(Service.HULU, Service.NETFLIX), Services.railSlots(true, emptyList(), listOf(Service.HULU, Service.NETFLIX)))
        // A visit is a page opening: the one opened last comes first.
        var recent = Services.visited(listOf(Service.HULU, Service.NETFLIX), Service.PRIME_VIDEO)
        assertEquals(listOf(Service.PRIME_VIDEO, Service.HULU), Services.railSlots(true, emptyList(), recent))
        recent = Services.visited(recent, Service.HULU)
        assertEquals(listOf(Service.HULU, Service.PRIME_VIDEO), Services.railSlots(true, emptyList(), recent))
    }

    @Test
    fun onePinnedKeepsTheTopShortcutAndTheOtherIsTheDifferentServiceOpenedLast() {
        val pinned = listOf(Service.DISNEY)
        assertEquals(listOf(Service.DISNEY, Service.NETFLIX), Services.railSlots(true, pinned, listOf(Service.NETFLIX, Service.HULU)))
        // The pinned one opened last: the second is the newest of the others, never the pinned one twice.
        assertEquals(listOf(Service.DISNEY, Service.NETFLIX), Services.railSlots(true, pinned, listOf(Service.DISNEY, Service.NETFLIX)))
        // No other yet: the second slot stays empty.
        assertEquals(listOf(Service.DISNEY, null), Services.railSlots(true, pinned, listOf(Service.DISNEY)))
        assertEquals(listOf(Service.DISNEY, null), Services.railSlots(true, pinned, emptyList()))
    }

    @Test
    fun twoPinnedKeepTheirSlotsWhateverWasOpened() {
        val pinned = listOf(Service.PRIME_VIDEO, Service.HBO_MAX)
        for (recent in listOf(emptyList(), listOf(Service.NETFLIX, Service.HULU), listOf(Service.HBO_MAX, Service.PRIME_VIDEO))) {
            assertEquals(listOf(Service.PRIME_VIDEO, Service.HBO_MAX), Services.railSlots(true, pinned, recent))
        }
    }

    @Test
    fun switchedOffTheRailShowsNoServiceShortcuts() {
        assertEquals(emptyList<Service?>(), Services.railSlots(false, listOf(Service.DISNEY), listOf(Service.NETFLIX)))
        assertEquals(emptyList<Service?>(), Services.railSlots(false, emptyList(), emptyList()))
    }

    @Test
    fun upToTwoDistinctServicesArePinned() {
        var pins = Services.pin(emptyList(), 0, Service.NETFLIX)
        assertEquals(listOf(Service.NETFLIX), pins)
        pins = Services.pin(pins, 1, Service.APPLE_TV)
        assertEquals(listOf(Service.NETFLIX, Service.APPLE_TV), pins)
        // A service pinned in the other slot changes places: still two, each once.
        assertEquals(listOf(Service.APPLE_TV, Service.NETFLIX), Services.pin(pins, 0, Service.APPLE_TV))
        assertEquals(listOf(Service.APPLE_TV, Service.NETFLIX), Services.pin(pins, 1, Service.NETFLIX))
        // The same service again in its own slot: as it was.
        assertEquals(pins, Services.pin(pins, 0, Service.NETFLIX))
        // A slot back to the most recent: the pins close up, one alone in the top slot.
        assertEquals(listOf(Service.APPLE_TV), Services.pin(pins, 0, null))
        assertEquals(listOf(Service.NETFLIX), Services.pin(pins, 1, null))
        assertEquals(listOf(Service.HULU), Services.pin(emptyList(), 1, Service.HULU))
        // Stored and read back, repeats and unknown names left out.
        assertEquals(pins, Services.decode(Services.encode(pins)))
        assertEquals(listOf(Service.HULU), Services.pin(listOf(Service.HULU, Service.HULU), 5, Service.NETFLIX))
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

    @Test
    fun huluNetflixPrimeVideoHboMaxAndAppleTvStartOnTheBannersDetailsDisneyPlusInItsRows() {
        val details = Service.entries.filter { Services.startsOnDetails(it) }.toSet()
        assertEquals(setOf(Service.HULU, Service.NETFLIX, Service.PRIME_VIDEO, Service.HBO_MAX, Service.APPLE_TV), details)
        // Disney+'s studio cards stay first.
        assertFalse(Services.startsOnDetails(Service.DISNEY))
    }
}
