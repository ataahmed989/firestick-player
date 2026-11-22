package app.fireplayer.vod.data

import app.fireplayer.vod.data.ServiceHold.Option
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceHoldTest {

    private val netflix = Service.NETFLIX
    private val hulu = Service.HULU
    private val disney = Service.DISNEY

    @Test
    fun nothingPinnedOffersMoveAndPinToRail() {
        assertEquals(listOf(Option.MOVE, Option.PIN), ServiceHold.options(true, emptyList(), disney))
        assertEquals(listOf("Move", "Pin to rail"), ServiceHold.options(true, emptyList(), disney).map { it.label })
    }

    @Test
    fun firstPinnedOffersTheSecondShortcut() {
        assertEquals(listOf(Option.MOVE, Option.PIN_SECOND), ServiceHold.options(true, listOf(netflix), disney))
        assertEquals("Pin as second shortcut", Option.PIN_SECOND.label)
    }

    @Test
    fun bothPinnedOffersReplacingEitherNamingWhatItReplaces() {
        val pins = listOf(netflix, hulu)
        assertEquals(listOf(Option.MOVE, Option.REPLACE_FIRST, Option.REPLACE_SECOND), ServiceHold.options(true, pins, disney))
        assertEquals("Replaces Netflix", ServiceHold.detail(Option.REPLACE_FIRST, pins))
        assertEquals("Replaces Hulu", ServiceHold.detail(Option.REPLACE_SECOND, pins))
        assertNull(ServiceHold.detail(Option.MOVE, pins))
        assertNull(ServiceHold.detail(Option.UNPIN, pins))
    }

    @Test
    fun aPinnedServiceOffersUnpin() {
        assertEquals(listOf(Option.MOVE, Option.UNPIN), ServiceHold.options(true, listOf(netflix), netflix))
        assertEquals(listOf(Option.MOVE, Option.UNPIN), ServiceHold.options(true, listOf(netflix, hulu), hulu))
        assertEquals("Unpin from rail", Option.UNPIN.label)
    }

    @Test
    fun withTheShortcutsOffAPinnedServiceOffersPinToRailAgain() {
        assertEquals(listOf(Option.MOVE, Option.PIN), ServiceHold.options(false, listOf(netflix), netflix))
        assertEquals(listOf(netflix), ServiceHold.pinsAfter(Option.PIN, listOf(netflix), netflix))
        assertTrue(ServiceHold.onAfter(Option.PIN, false))
    }

    @Test
    fun pinningFillsTheFirstFreeShortcut() {
        assertEquals(listOf(disney), ServiceHold.pinsAfter(Option.PIN, emptyList(), disney))
        assertEquals(listOf(netflix, disney), ServiceHold.pinsAfter(Option.PIN_SECOND, listOf(netflix), disney))
    }

    @Test
    fun replacingTakesThatShortcutsPlace() {
        val pins = listOf(netflix, hulu)
        assertEquals(listOf(disney, hulu), ServiceHold.pinsAfter(Option.REPLACE_FIRST, pins, disney))
        assertEquals(listOf(netflix, disney), ServiceHold.pinsAfter(Option.REPLACE_SECOND, pins, disney))
    }

    @Test
    fun unpinningClosesUp() {
        assertEquals(listOf(hulu), ServiceHold.pinsAfter(Option.UNPIN, listOf(netflix, hulu), netflix))
        assertEquals(listOf(netflix), ServiceHold.pinsAfter(Option.UNPIN, listOf(netflix, hulu), hulu))
        assertEquals(emptyList<Service>(), ServiceHold.pinsAfter(Option.UNPIN, listOf(netflix), netflix))
        // Unpinning leaves the rail's switch as it was.
        assertFalse(ServiceHold.onAfter(Option.UNPIN, false))
        assertTrue(ServiceHold.onAfter(Option.UNPIN, true))
    }

    @Test
    fun pinningSwitchesTheShortcutsOnMovingDoesNot() {
        for (o in listOf(Option.PIN, Option.PIN_SECOND, Option.REPLACE_FIRST, Option.REPLACE_SECOND)) assertTrue(ServiceHold.onAfter(o, false))
        assertFalse(ServiceHold.onAfter(Option.MOVE, false))
        assertEquals(listOf(netflix, hulu), ServiceHold.pinsAfter(Option.MOVE, listOf(netflix, hulu), disney))
    }

    @Test
    fun repeatsAndExtrasInWhatWasStoredAreIgnored() {
        val stored = listOf(netflix, netflix, hulu, disney)
        assertEquals(listOf(Option.MOVE, Option.REPLACE_FIRST, Option.REPLACE_SECOND), ServiceHold.options(true, stored, Service.APPLE_TV))
        assertEquals(listOf(Option.MOVE, Option.PIN_SECOND), ServiceHold.options(true, listOf(hulu, hulu), disney))
    }
}
