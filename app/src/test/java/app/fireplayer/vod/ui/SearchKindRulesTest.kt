package app.fireplayer.vod.ui

import android.view.KeyEvent
import app.fireplayer.live.ChannelKey
import app.fireplayer.live.LiveSearch
import app.fireplayer.live.LiveSearchRules
import app.fireplayer.live.ui.LiveSearchView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchKindRulesTest {

    // ---- Which row comes first ----

    @Test fun theRowHoldingTheBestMatchComesFirst() {
        // A show named exactly as searched: TV Shows above Movies.
        assertTrue(SearchRules.showsFirst(listOf("Breaking Bad: The Movie", "El Camino"), listOf("Breaking Bad"), "breaking bad"))
        // A film named exactly: Movies first.
        assertFalse(SearchRules.showsFirst(listOf("Dune"), listOf("Dune: Prophecy"), "Dune"))
        // Starts with it beats a word starting with it, which beats holding it.
        assertTrue(SearchRules.showsFirst(listOf("Mad Max: Fury Road"), listOf("Max Payne Show"), "max"))
        assertFalse(SearchRules.showsFirst(listOf("Mad Max"), listOf("Cinemax Nights"), "max"))
        // The best title anywhere in the row counts, not only the first.
        assertTrue(SearchRules.showsFirst(listOf("The Office Job", "Office Space"), listOf("Workplace", "The Office"), "the office"))
    }

    @Test fun theSameMatchGoesToTheEarlierPlaceThenMovies() {
        // Both start with it: the one earlier in its row.
        assertTrue(SearchRules.showsFirst(listOf("Other", "Lost Highway"), listOf("Lost"), "los"))
        assertFalse(SearchRules.showsFirst(listOf("Lost Highway"), listOf("Other", "Lost"), "los"))
        // The same rank and place: Movies first; neither matching (Cinemeta found them by other words): Movies first.
        assertFalse(SearchRules.showsFirst(listOf("Lost Highway"), listOf("Lost Girl"), "lost"))
        assertFalse(SearchRules.showsFirst(listOf("A"), listOf("B"), "zzz"))
        // A row with nothing that matches loses to one with any match; an empty row never comes first.
        assertTrue(SearchRules.showsFirst(listOf("Unrelated"), listOf("Some Lost Thing"), "lost"))
        assertFalse(SearchRules.showsFirst(listOf("Lost"), emptyList(), "lost"))
        assertTrue(SearchRules.showsFirst(emptyList(), listOf("Lost"), "lost"))
        // Case and spaces round the words don't matter (the app's one name ranking).
        assertTrue(SearchRules.showsFirst(listOf("Lost in Space"), listOf("LOST"), "  lost "))
    }

    @Test fun aRowsBestMatchIsItsBestRankThenItsPlace() {
        assertEquals(Long.MAX_VALUE, SearchRules.bestMatch(listOf("A", "B"), "zzz"))
        assertTrue(SearchRules.bestMatch(listOf("Lost"), "lost") < SearchRules.bestMatch(listOf("Lost Girl"), "lost"))
        assertTrue(SearchRules.bestMatch(listOf("x", "Lost Girl"), "lost") > SearchRules.bestMatch(listOf("Lost Girl"), "lost"))
        // A worse rank never wins by its place.
        assertTrue(SearchRules.bestMatch(listOf("Lost Girl"), "lost") < SearchRules.bestMatch(listOf("Lost"), "los") + 1_000_000L)
        assertTrue(SearchRules.bestMatch(List(15) { "x" } + "Lost", "lost") < SearchRules.bestMatch(listOf("Lost Girl"), "lost"))
    }

    // ---- Kinds and history ----

    @Test fun eachKindHasItsOwnHistoryAndHint() {
        assertEquals("history", SearchKind.VOD.historyKey)
        assertEquals("history_live", SearchKind.LIVE.historyKey)
        assertEquals("Search films and shows", SearchKind.VOD.hint)
        assertEquals("Search channels and programmes", SearchKind.LIVE.hint)
        assertEquals(listOf("Movies & TV Shows", "Live TV"), SearchKind.entries.map { it.label })
    }

    @Test fun eachKindKeepsItsLastWordsUntilCleared() {
        val w = SearchRules.LastWords()
        w.keep(SearchKind.VOD, "korra")
        w.keep(SearchKind.LIVE, "max")
        assertEquals("korra", w.of(SearchKind.VOD))
        assertEquals("max", w.of(SearchKind.LIVE))
        w.keep(SearchKind.VOD, "")
        assertEquals("", w.of(SearchKind.VOD))
        w.clear()
        assertEquals("", w.of(SearchKind.LIVE))
    }

    @Test fun pillsMoveLeftAndRightOnly() {
        assertEquals(SearchKind.LIVE, SearchRules.pillMove(SearchKind.VOD, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(SearchKind.VOD, SearchRules.pillMove(SearchKind.LIVE, KeyEvent.KEYCODE_DPAD_LEFT))
        assertNull(SearchRules.pillMove(SearchKind.VOD, KeyEvent.KEYCODE_DPAD_LEFT))
        assertNull(SearchRules.pillMove(SearchKind.LIVE, KeyEvent.KEYCODE_DPAD_RIGHT))
        assertNull(SearchRules.pillMove(SearchKind.VOD, KeyEvent.KEYCODE_DPAD_DOWN))
    }

    // ---- Back ----

    @Test fun backClearsResultsFirstThenLeaves() {
        assertEquals(SearchRules.Back.CLEAR, SearchRules.back(SearchKind.VOD, showsSomething = true))
        assertEquals(SearchRules.Back.CLEAR, SearchRules.back(SearchKind.LIVE, showsSomething = true))
        assertEquals(SearchRules.Back.RAIL, SearchRules.back(SearchKind.VOD, showsSomething = false))
        assertEquals(SearchRules.Back.LIVE_TV, SearchRules.back(SearchKind.LIVE, showsSomething = false))
    }

    // ---- Live TV search: matching and order ----

    @Test fun nameMatchRanks() {
        assertEquals(0, LiveSearchRules.rank("MAX", "max"))
        assertEquals(1, LiveSearchRules.rank("Max Payne", "max"))
        assertEquals(2, LiveSearchRules.rank("Mad Max: Fury Road", "max"))
        assertEquals(3, LiveSearchRules.rank("USA Cinemax East", "max"))
        assertEquals(-1, LiveSearchRules.rank("BBC One", "max"))
        assertEquals(-1, LiveSearchRules.rank("BBC One", "  "))
    }

    @Test fun likePatternTakesWildcardsLiterally() {
        assertEquals("%max%", LiveSearchRules.likePattern(" max "))
        assertEquals("%100\\%\\_a\\\\b%", LiveSearchRules.likePattern("100%_a\\b"))
    }

    @Test fun channelsBestMatchFirstKeepingGuideOrder() {
        val names = listOf("USA Cinemax East", "Max", "ActionMAX", "Max Sport", "BBC One", "Cinemax Action")
        assertEquals(listOf("Max", "Max Sport", "USA Cinemax East", "ActionMAX", "Cinemax Action"), LiveSearchRules.orderChannels(names, "max") { it })
    }

    private fun hit(title: String, start: Long, stop: Long, ch: String = "c") =
        LiveSearch.ProgrammeHit(LiveSearch.ChannelHit(ChannelKey("p", ch), ch, null, null, false), title, start, stop)

    @Test fun programmesOnNowFirstThenMatchThenSoonest() {
        val now = 1_000L
        val later = hit("Max Payne", 2_000, 3_000)
        val nowWord = hit("Mad Max: Fury Road", 900, 1_100)
        val nowExact = hit("Max", 500, 1_500)
        val soonerWordLater = hit("Mad Max 2", 1_500, 1_800)
        val ordered = LiveSearchRules.orderProgrammes(listOf(later, nowWord, soonerWordLater, nowExact), "max", now)
        assertEquals(listOf(nowExact, nowWord, later, soonerWordLater), ordered)
    }

    @Test fun onNowIsStartInclusiveStopExclusive() {
        assertTrue(LiveSearchRules.onNow(100, 200, 100))
        assertTrue(LiveSearchRules.onNow(100, 200, 199))
        assertFalse(LiveSearchRules.onNow(100, 200, 200))
        assertFalse(LiveSearchRules.onNow(100, 200, 99))
    }

    @Test fun oneProgrammePerChannelAndStart() {
        val a = hit("News", 0, 10, "a")
        val list = LiveSearchRules.distinct(listOf(a, hit("News", 0, 10, "a"), hit("News", 0, 10, "b")))
        assertEquals(2, list.size)
        assertEquals(a, list[0])
    }

    @Test fun listingsComeFromTheOwnEpgOrAnyWhenNone() {
        assertTrue(LiveSearchRules.listsFor("p1", "p1", playlistHasEpg = true))
        assertFalse(LiveSearchRules.listsFor("p1", "p2", playlistHasEpg = true))
        assertTrue(LiveSearchRules.listsFor("p1", "p2", playlistHasEpg = false))
    }

    // ---- The channel name's scrolling ----

    @Test fun marqueeOnlyForNamesTooLongAndAfterTheDelay() {
        assertEquals(300L, VodSizes.LIVE_MARQUEE_DELAY_MS)
        assertEquals(0f, LiveSearchView.Marquee.distance(80f, 120f), 0f)
        assertEquals(40f, LiveSearchView.Marquee.distance(160f, 120f), 0f)
        assertFalse(LiveSearchView.Marquee.scrolls(160f, 120f, 299L))
        assertTrue(LiveSearchView.Marquee.scrolls(160f, 120f, 300L))
        assertFalse(LiveSearchView.Marquee.scrolls(100f, 120f, 5_000L))
        assertEquals(1_000L, LiveSearchView.Marquee.durationMs(40f, 40f))
        assertEquals(250L, LiveSearchView.Marquee.durationMs(1f, 40f))
    }
}
