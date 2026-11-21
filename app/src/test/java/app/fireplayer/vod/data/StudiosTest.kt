package app.fireplayer.vod.data

import app.fireplayer.vod.CuratedTitle
import app.fireplayer.vod.Kind
import app.fireplayer.vod.Source
import app.fireplayer.vod.ui.RowRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudiosTest {

    @Test
    fun eachStudioPageHasItsRowsInOrder() {
        assertEquals(
            listOf("Popular Disney Movies", "Walt Disney Animation Studios", "Live-Action Adventures", "Disney Musicals"),
            Studios.rows(Brand.DISNEY).map { it.label },
        )
        assertEquals(listOf("Popular Pixar", "Feature Films", "Shorts", "Series & Specials"), Studios.rows(Brand.PIXAR).map { it.label })
        assertEquals(
            listOf("Popular Marvel", "MCU Movies", "MCU Series", "Marvel Animation", "Beyond the MCU"),
            Studios.rows(Brand.MARVEL).map { it.label },
        )
        assertEquals(
            listOf("Skywalker Saga", "Standalone Movies", "Live-Action Series", "Animated Series", "Shorts & Specials"),
            Studios.rows(Brand.STAR_WARS).map { it.label },
        )
        // National Geographic keeps its collection.
        assertTrue(Studios.rows(Brand.NAT_GEO).isEmpty())
        assertFalse(Studios.hasPage(Brand.NAT_GEO))
        assertTrue(Studios.hasPage(Brand.MARVEL))
    }

    @Test
    fun everyRowIsDisneyPlusInTheUsAndGoesOn() {
        val rows = Brand.entries.flatMap { Studios.rows(it) }
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        for (row in rows) {
            when (val s = row.source) {
                is Source.Discover -> {
                    assertEquals(337, s.provider)
                    assertTrue(s.paged)
                    assertNotNull(s.companies)
                    val q = Discover.params(s, s.kind ?: Kind.MOVIE, 1).toMap()
                    assertEquals("US", q["watch_region"])
                    assertEquals("337", q["with_watch_providers"])
                }
                is Source.Curated -> {
                    assertEquals(337, s.provider)
                    assertTrue(s.titles.isNotEmpty())
                    assertEquals(s.titles.size, s.titles.map { it.tmdbId }.toSet().size)
                }
                else -> throw AssertionError("Unexpected source in ${row.key}")
            }
            assertTrue(RowRules.pages(row.source))
        }
        // Disney Pictures: Disney-branded companies only.
        val disney = Studios.rows(Brand.DISNEY).map { (it.source as Source.Discover).companies }
        assertEquals(listOf("2|6125", "6125", "2", "2|6125"), disney)
        val adventures = Discover.params(Studios.rows(Brand.DISNEY)[2].source as Source.Discover, Kind.MOVIE, 1).toMap()
        assertEquals("12", adventures["with_genres"])
        assertEquals("16", adventures["without_genres"])
        val shorts = Studios.rows(Brand.PIXAR)[2].source as Source.Discover
        assertEquals("40", Discover.params(shorts, Kind.MOVIE, 1).toMap()["with_runtime.lte"])
        // A narrowed row's pages are kept apart from the plain ones; plain keys are as they were.
        val plain = Source.Discover(Kind.MOVIE, 337, "3")
        assertFalse(Discover.key(shorts, Kind.MOVIE, 1) == Discover.key(plain, Kind.MOVIE, 1))
        assertEquals("tmdb|discover|v1|movie|337|3||1", Discover.key(plain, Kind.MOVIE, 1))
        assertEquals(Discover.key(plain, Kind.MOVIE, 1), Discover.key(plain.copy(paged = true), Kind.MOVIE, 1))
    }

    @Test
    fun theOrderedListsAreInTheirOrder() {
        assertEquals(listOf(1893, 1894, 1895, 11, 1891, 1892, 140607, 181808, 181812), Studios.SKYWALKER_SAGA.map { it.tmdbId })
        for (list in listOf(Studios.MCU_MOVIES, Studios.PIXAR_FEATURES)) {
            assertEquals(list.map { it.year }.sorted(), list.map { it.year })
        }
        assertEquals("Iron Man", Studios.MCU_MOVIES.first().name)
        assertEquals("Toy Story", Studios.PIXAR_FEATURES.first().name)
    }

    @Test
    fun theWholeCollectionsStayBrowsable() {
        assertEquals(Services.collection(Brand.PIXAR), Studios.seeAll(Studios.rows(Brand.PIXAR)[0]))
        assertEquals(Services.collection(Brand.MARVEL), Studios.seeAll(Studios.rows(Brand.MARVEL)[0]))
        assertEquals(Services.collection(Brand.STAR_WARS), Studios.seeAll(Studios.rows(Brand.STAR_WARS)[0]))
        assertNull(Studios.seeAll(Studios.rows(Brand.MARVEL)[1]))
        assertNull(Studios.seeAll(Studios.rows(Brand.DISNEY)[0]))
        // A Discover row that goes on ends with See all (its own grid); a service's first-page row and a chosen list don't.
        assertTrue(RowRules.seeAll(Studios.rows(Brand.DISNEY)[0].source, 16))
        assertFalse(RowRules.seeAll(Source.Discover(Kind.MOVIE, 337), 16))
        assertFalse(RowRules.seeAll(Studios.rows(Brand.MARVEL)[1].source, 16))
    }

    @Test
    fun aChosenTitleIsReadWithWhereItPlays() {
        val text = """{"id":1893,"title":"Star Wars: Episode I - The Phantom Menace","release_date":"1999-05-19",
            "poster_path":"/p.jpg","imdb_id":"tt0120915",
            "watch/providers":{"results":{"US":{"flatrate":[{"provider_id":337}],"buy":[{"provider_id":2}]},"GB":{"flatrate":[{"provider_id":8}]}}}}"""
        val e = Curated.parse(text, Kind.MOVIE, 337)!!
        assertTrue(e.on)
        assertEquals("tt0120915", e.imdb)
        assertEquals(1893, e.item.tmdbId)
        assertTrue(Curated.matches(Studios.SKYWALKER_SAGA[0], e.item))
        // Another service, or only to buy: not carried.
        assertFalse(Curated.parse(text, Kind.MOVIE, 8)!!.on)
        assertFalse(Curated.parse(text, Kind.MOVIE, 2)!!.on)
        // Kept and read back the same.
        val back = Curated.read(Curated.write(e))!!
        assertEquals(e.item, back.item)
        assertEquals(e.imdb, back.imdb)
        assertTrue(back.on)
        // A series: its IMDb id among its external ids.
        val show = Curated.parse(
            """{"id":82856,"name":"The Mandalorian","first_air_date":"2019-11-12","external_ids":{"imdb_id":"tt8111088"}}""", Kind.SERIES, 337,
        )!!
        assertEquals("tt8111088", show.imdb)
        assertEquals(Kind.SERIES, show.item.kind)
        assertFalse(show.on)
        assertNull(Curated.parse("{}", Kind.MOVIE, 337))
    }

    @Test
    fun anIdThatIsAnotherTitleIsLeftOut() {
        fun item(name: String, date: String?) = TmdbDetails.Item(1, Kind.MOVIE, name, date)
        val wallE = CuratedTitle(10681, "WALL·E", 2008)
        assertTrue(Curated.matches(wallE, item("WALL·E", "2008-06-22")))
        assertTrue(Curated.matches(wallE, item("Wall-E", "2008-06-22")))
        assertFalse(Curated.matches(wallE, item("Some Other Film", "2008-06-22")))
        assertFalse(Curated.matches(wallE, item("WALL·E", "1998-06-22")))
        assertFalse(Curated.matches(wallE, item("WALL·E", null)))
        // A year either side is the same title (a festival date).
        assertTrue(Curated.matches(CuratedTitle(1, "Agents of S.H.I.E.L.D.", 2013), item("Marvel's Agents of S.H.I.E.L.D.", "2014-01-01")))
    }

    @Test
    fun aRowTakesMoreNearItsEnd() {
        assertTrue(RowRules.nearEnd(10, 15))
        assertFalse(RowRules.nearEnd(9, 15))
        assertFalse(RowRules.nearEnd(0, 0))
        val curated = Studios.rows(Brand.STAR_WARS)[0].source
        val discover = Studios.rows(Brand.DISNEY)[0].source
        // A chosen list that came up short has ended; Discover only when nothing came.
        assertTrue(RowRules.endsAt(curated, 9))
        assertFalse(RowRules.endsAt(curated, 16))
        assertFalse(RowRules.endsAt(discover, 9))
        assertTrue(RowRules.endsAt(discover, 0))
        assertFalse(RowRules.pages(Source.Discover(Kind.MOVIE, 337)))
        assertFalse(RowRules.pages(Source.MyList))
    }
}
