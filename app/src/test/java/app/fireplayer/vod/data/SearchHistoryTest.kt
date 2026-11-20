package app.fireplayer.vod.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchHistoryTest {

    @Test
    fun aSearchGoesFirstAndAnOlderCopyOfItGoes() {
        var list = SearchHistoryRules.add(emptyList(), "dune")
        list = SearchHistoryRules.add(list, "alien")
        list = SearchHistoryRules.add(list, "  DUNE ")
        assertEquals(listOf("DUNE", "alien"), list)
    }

    @Test
    fun spacesAreTrimmedAndBlankSearchesAreNotKept() {
        assertEquals(listOf("the bear"), SearchHistoryRules.add(emptyList(), "  the   bear "))
        assertEquals(listOf("x"), SearchHistoryRules.add(listOf("x"), "   "))
    }

    @Test
    fun atMostTwentyAreKeptTheOldestGoingFirst() {
        var list = emptyList<String>()
        for (i in 1..25) list = SearchHistoryRules.add(list, "s$i")
        assertEquals(SearchHistoryRules.MAX, list.size)
        assertEquals("s25", list.first())
        assertEquals("s6", list.last())
    }

    @Test
    fun removeTakesOutThatSearchInAnyCase() {
        assertEquals(listOf("alien"), SearchHistoryRules.remove(listOf("Dune", "alien"), "dune"))
        assertEquals(listOf("alien"), SearchHistoryRules.remove(listOf("alien"), "missing"))
    }

    @Test
    fun theStoredListReadsBackAndBadTextReadsAsNone() {
        val list = listOf("dune", "the bear", "alien")
        assertEquals(list, SearchHistoryRules.read(SearchHistoryRules.write(list)))
        assertEquals(emptyList<String>(), SearchHistoryRules.read(null))
        assertEquals(emptyList<String>(), SearchHistoryRules.read("not json"))
        // Written by hand or an older build: cleaned, duplicates dropped (the newer kept), capped.
        assertEquals(listOf("dune", "alien"), SearchHistoryRules.read("""[" dune ","alien","DUNE",""]"""))
    }
}
