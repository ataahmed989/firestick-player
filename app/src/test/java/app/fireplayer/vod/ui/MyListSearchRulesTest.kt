package app.fireplayer.vod.ui

import app.fireplayer.vod.Card
import app.fireplayer.vod.Kind
import app.fireplayer.vod.RowStyle
import app.fireplayer.vod.Source
import app.fireplayer.vod.Title
import app.fireplayer.vod.data.TabRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The My List and Search tabs' rows and rules (pure parts). */
class MyListSearchRulesTest {

    private val film = Title("tt1160419", Kind.MOVIE, "Dune")
    private val show = Title("tt0903747", Kind.SERIES, "Breaking Bad")

    @Test
    fun myListHasItsFilmsThenItsSeriesAsPosterRows() {
        val rows = TabRows.myList()
        assertEquals(listOf("My List · Movies", "My List · Series"), rows.map { it.label })
        assertEquals(listOf(Source.MyListOf(Kind.MOVIE), Source.MyListOf(Kind.SERIES)), rows.map { it.source })
        assertTrue(rows.all { it.style == RowStyle.POSTER && !it.continueRow })
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }

    @Test
    fun searchHasFilmsThenTvShowsForTheTrimmedWords() {
        val rows = TabRows.search("  dune ")
        assertEquals(listOf("Movies", "TV Shows"), rows.map { it.label })
        assertEquals(listOf(Source.Search(Kind.MOVIE, "dune"), Source.Search(Kind.SERIES, "dune")), rows.map { it.source })
        assertTrue(rows.all { it.style == RowStyle.POSTER })
        // The keys stay the same whatever is searched (the page keeps its two rows and changes what they ask for).
        assertEquals(rows.map { it.key }, TabRows.search("alien").map { it.key })
    }

    @Test
    fun myListRowsAreTheLibrarysAndTakeOnlyTheirKind() {
        val films = Source.MyListOf(Kind.MOVIE)
        assertTrue(RowRules.isLibrary(films))
        assertTrue(RowRules.isMyList(films))
        assertTrue(RowRules.isMyList(Source.MyList))
        assertFalse(RowRules.isMyList(Source.Search(Kind.MOVIE, "x")))
        assertTrue(RowRules.myListTakes(films, film))
        assertFalse(RowRules.myListTakes(films, show))
        assertTrue(RowRules.myListTakes(Source.MyList, show))
        // An empty answer is final (nothing more is coming).
        assertTrue(RowRules.isFinal(films, empty = true, fresh = false))
    }

    @Test
    fun aTitleAddedGoesFirstAndOneRemovedGoes() {
        val cards = listOf(Card(Title("tt1", Kind.MOVIE, "A")), Card(Title("tt2", Kind.MOVIE, "B")))
        assertEquals(listOf(film.id, "tt1", "tt2"), RowRules.myListAfter(cards, film, inList = true).map { it.title.id })
        assertEquals(listOf("tt2"), RowRules.myListAfter(cards, cards[0].title, inList = false).map { it.title.id })
    }

    @Test
    fun seeAllComesOnlyPastFifteen() {
        assertFalse(RowRules.seeAll(Source.MyListOf(Kind.MOVIE), 15))
        assertTrue(RowRules.seeAll(Source.MyListOf(Kind.SERIES), 16))
        assertFalse(RowRules.seeAll(Source.Search(Kind.MOVIE, "dune"), 9))
        assertTrue(RowRules.seeAll(Source.Search(Kind.MOVIE, "dune"), 16))
    }

    @Test
    fun theSearchBarAndHistoryFitBesideTheTrashButton() {
        assertEquals(VodSizes.tabHeaderW(), VodSizes.searchBarW() + VodSizes.SEARCH_TRASH_GAP + VodSizes.SEARCH_TRASH, 0.001f)
        assertEquals(VodSizes.VIEWPORT_TOP + VodSizes.SEARCH_KIND_H + VodSizes.SEARCH_KIND_GAP + VodSizes.SEARCH_BAR_H + VodSizes.SEARCH_GAP, VodSizes.searchListTop(), 0.001f)
        // My List's rows area holds a whole poster row under the fixed header.
        assertTrue(VodSizes.SCREEN_H - VodSizes.fixedRowsTop() >= VodSizes.bandH(RowStyle.POSTER, true, VodSizes.ROW_GAP))
    }
}
