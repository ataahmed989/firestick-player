package app.fireplayer.vod.ui

import app.fireplayer.vod.Card
import app.fireplayer.vod.FakeVodRepository
import app.fireplayer.vod.Kind
import app.fireplayer.vod.Source
import app.fireplayer.vod.Title
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RowRulesTest {

    private val popular = Source.Catalog(Kind.MOVIE, "top")
    private val top10 = Source.Trending(Kind.MOVIE)
    private val myList = Source.MyList
    private val continueAll = Source.ContinueWatching(null)

    @Test
    fun anEmptyCachedAnswerWaitsForTheFreshOne() {
        assertFalse(RowRules.isFinal(popular, empty = true, fresh = false))
        assertTrue(RowRules.isFinal(popular, empty = true, fresh = true))
        assertTrue(RowRules.isFinal(popular, empty = false, fresh = false))
        // The library is on this TV: an empty answer is the answer.
        assertTrue(RowRules.isFinal(myList, empty = true, fresh = false))
        assertTrue(RowRules.isFinal(continueAll, empty = true, fresh = false))
        assertTrue(RowRules.isFinal(Source.NextEpisodes(), empty = true, fresh = false))
    }

    @Test
    fun seeAllCards() {
        assertTrue(RowRules.seeAll(popular, 16))
        assertTrue(RowRules.seeAll(popular, 4))
        assertFalse(RowRules.seeAll(popular, 0))
        assertFalse(RowRules.seeAll(top10, 10))
        assertFalse(RowRules.seeAll(myList, 15))
        assertTrue(RowRules.seeAll(myList, 16))
        assertFalse(RowRules.seeAll(continueAll, 3))
    }

    private fun cards(vararg ids: String) = ids.map { Card(Title(it, Kind.MOVIE, it)) }

    @Test
    fun newCardsWaitWhenTheyWouldMoveWhatTheViewerIsOn() {
        val old = cards("a", "b", "c")
        // Not in the row: always shown.
        assertTrue(RowRules.keepsFocus(-1, old, false, cards("x"), false))
        // On "b", still second: shown at once.
        assertTrue(RowRules.keepsFocus(1, old, false, cards("a", "b", "z"), false))
        // On "b", now something else there: held until the viewer leaves the row.
        assertFalse(RowRules.keepsFocus(1, old, false, cards("b", "a", "c"), false))
        // On See all (after the 3 cards): stays as long as there's still a See all.
        assertTrue(RowRules.keepsFocus(3, old, true, cards("x", "y"), true))
        assertFalse(RowRules.keepsFocus(3, old, true, cards("x", "y"), false))
    }

    @Test
    fun focusMovesOffItemsThatGo() {
        // On the 5th of 5, now 3: to the last one left.
        assertEquals(2, RowRules.focusAfter(4, 5, false, 3, false))
        // On See all, which goes: to the last card.
        assertEquals(1, RowRules.focusAfter(5, 5, true, 2, false))
        // Still there: stays.
        assertEquals(-1, RowRules.focusAfter(1, 5, true, 3, true))
        assertEquals(-1, RowRules.focusAfter(5, 5, true, 2, true))
        assertEquals(-1, RowRules.focusAfter(-1, 5, false, 1, false))
    }

    @Test
    fun myListPutsTheNewTitleFirstAndDropsARemovedOne() {
        val list = cards("a", "b")
        val added = RowRules.myListAfter(list, Title("c", Kind.SERIES, "C"), inList = true)
        assertEquals(listOf("c", "a", "b"), added.map { it.title.id })
        // Added again: moved to the front, not doubled.
        assertEquals(listOf("b", "a"), RowRules.myListAfter(list, Title("b", Kind.MOVIE, "b"), true).map { it.title.id })
        assertEquals(listOf("a"), RowRules.myListAfter(list, Title("b", Kind.MOVIE, "b"), false).map { it.title.id })
    }

    @Test
    fun headerFactsAreThePartsThatExist() {
        val t = Title("tt1", Kind.SERIES, "Show", year = "2014–2019", genres = listOf("Drama", "Crime", "Thriller"), imdbRating = "8.4", runtime = "49 min")
        assertEquals("2014–2019 · Drama · Crime · ★ 8.4 · 49 min", RowRules.facts(t, null))
        assertEquals("S2 E5 · 12m left · 2014–2019 · Drama · Crime · ★ 8.4 · 49 min", RowRules.facts(t, "S2 E5 · 12m left"))
        assertEquals("", RowRules.facts(Title("tt2", Kind.MOVIE, "Bare", imdbRating = ""), " "))
    }

    @Test
    fun theFakeRepositoryHasTheAgreedRows() {
        val repo = FakeVodRepository()
        val home = repo.rows(app.fireplayer.vod.Page.HOME)
        assertEquals(listOf("Continue Watching", "My List", "Next Episodes", "Popular Movies", "Popular Shows"), home.map { it.label })
        assertEquals(1, home.count { it.continueRow })
        val shows = repo.rows(app.fireplayer.vod.Page.SHOWS)
        assertEquals("Continue Watching · Series", shows.first().label)
        assertTrue(shows.first().continueRow)
    }

    @Test
    fun aContinueCardChangedInPlaceKeepsWhatTheRowShowsAndTakesItsNewProgress() {
        val ref = app.fireplayer.vod.EpisodeRef(2, 5)
        val shown = Card(Title("tt1", Kind.SERIES, "Korra", poster = "p"), progress = 0.4f, caption = "S2 E5 · 12m left", episode = ref, picture = "still")
        val now = Card(Title("tt1", Kind.SERIES, "Korra"), progress = 0f, caption = "S1 E1 · 23m left", episode = app.fireplayer.vod.EpisodeRef(1, 1))
        val card = RowRules.continueChanged(shown, now)
        assertEquals(shown.title, card.title)
        assertEquals("still", card.picture)
        assertEquals(0f, card.progress!!, 0f)
        assertEquals("S1 E1 · 23m left", card.caption)
        assertEquals(app.fireplayer.vod.EpisodeRef(1, 1), card.episode)
        // Nothing new: the same card (the row isn't redrawn).
        assertEquals(card, RowRules.continueChanged(card, now))
    }
}
