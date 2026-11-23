package app.fireplayer.vod.ui

import app.fireplayer.vod.data.Brand
import app.fireplayer.vod.data.Service
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PagePlaceTest {

    @Test
    fun aPageMadeAgainOpensOnTheRowAndCardsItWasLeftOn() {
        // Movies left on its third row ("trending"), on card 7 there and card 3 in "top": let go of while a film plays.
        val kept = PagePlace.keep("trending", mapOf("continue" to 0, "top" to 3, "trending" to 7), PagePlace.none())
        assertEquals("trending", kept.lastRow)
        // Made again when Movies next opens: each row starts on the card chosen in it, once, as it shows.
        assertEquals(7, kept.takeChosen("trending", 16))
        assertEquals(3, kept.takeChosen("top", 16))
        // A row shown again later (an empty answer, then cards) starts on its first card, as any row does.
        assertEquals(0, kept.takeChosen("trending", 16))
        // A row that wasn't there before (Settings › Rows added it meanwhile) starts on its first card.
        assertEquals(0, kept.takeChosen("new-row", 16))
    }

    @Test
    fun aCardChosenPastWhatTheRowHasNowStaysInIt() {
        val kept = PagePlace.keep("trending", mapOf("trending" to 14), PagePlace.none())
        // The list came back shorter: its last card.
        assertEquals(4, kept.takeChosen("trending", 5))
        assertEquals(0, PagePlace.keep(null, mapOf("top" to 3), PagePlace.none()).takeChosen("top", 0))
    }

    @Test
    fun aPageLetGoOfAgainBeforeItsRowsCameKeepsWhatItStillHadToRestore() {
        val first = PagePlace.keep("top", mapOf("top" to 5, "trending" to 2), PagePlace.none())
        // Made again; only "top" has shown (the viewer chose card 6 there) when it's let go of again.
        assertEquals(5, first.takeChosen("top", 16))
        val again = PagePlace.keep("top", mapOf("top" to 6), first)
        assertEquals("top", again.lastRow)
        assertEquals(6, again.takeChosen("top", 16))
        assertEquals(2, again.takeChosen("trending", 16))
    }

    @Test
    fun aFirstOpenHasNoPlace() {
        val none = PagePlace.none()
        assertNull(none.lastRow)
        assertEquals(0, none.takeChosen("top", 16))
    }

    @Test
    fun everyHiddenPageGoesButTheShownOneAndTheServiceUnderAStudio() {
        // On Home: Movies, a service's page and a studio's page go; Home stays.
        assertFalse(PagePlace.goes(isCurrent = true, service = null, studio = null, currentService = null, currentStudio = null))
        assertTrue(PagePlace.goes(isCurrent = false, service = null, studio = null, currentService = null, currentStudio = null))
        assertTrue(PagePlace.goes(false, Service.NETFLIX, null, null, null))
        assertTrue(PagePlace.goes(false, Service.DISNEY, Brand.entries.first(), null, null))
        // On a Disney+ studio's page: Disney+'s own page stays (Back returns to it as it was); other services' go.
        val studio = Brand.MARVEL
        assertFalse(PagePlace.goes(false, Service.DISNEY, null, Service.DISNEY, studio))
        assertTrue(PagePlace.goes(false, Service.NETFLIX, null, Service.DISNEY, studio))
        // Another studio's page, left before: it goes.
        assertTrue(PagePlace.goes(false, Service.DISNEY, Brand.PIXAR, Service.DISNEY, studio))
        // On Live TV or Settings (no film page shown): every film page goes.
        assertTrue(PagePlace.goes(false, null, null, null, null))
    }

    @Test
    fun eachPageKeepsItsOwnPlace() {
        val keys = setOf(
            PagePlace.keyOf(VodTab.HOME, null, null),
            PagePlace.keyOf(VodTab.MOVIES, null, null),
            PagePlace.keyOf(VodTab.SHOWS, null, null),
            PagePlace.keyOf(VodTab.HOME, Service.NETFLIX, null),
            PagePlace.keyOf(VodTab.HOME, Service.DISNEY, null),
            PagePlace.keyOf(VodTab.HOME, Service.DISNEY, Brand.entries.first()),
        )
        assertEquals(6, keys.size)
    }
}
