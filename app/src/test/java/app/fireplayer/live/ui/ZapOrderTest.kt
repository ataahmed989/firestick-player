package app.fireplayer.live.ui

import app.fireplayer.live.ChannelKey
import app.fireplayer.live.GuideScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Channel ▲ / ▼ within the playing channel's group. */
class ZapOrderTest {

    private val keys = listOf("a", "b", "c").map { ChannelKey("p", it) }
    private fun next(from: String?, delta: Int) = ZapOrder.next(keys, from?.let { ChannelKey("p", it) }, delta) { it }?.channelId

    @Test fun upGoesOnDownTheListAndDownBack() {
        assertEquals("b", next("a", +1))
        assertEquals("a", next("b", -1))
    }

    @Test fun roundAtTheEnds() {
        assertEquals("a", next("c", +1))
        assertEquals("c", next("a", -1))
    }

    @Test fun heldKeysAddUpAndWrap() {
        assertEquals("b", next("a", +4))
        assertEquals("b", next("a", -5))
    }

    @Test fun aChannelOutsideTheListCountsFromTheFirst() {
        assertEquals("b", next("zz", +1))
        assertEquals("c", next(null, -1))
    }

    @Test fun anEmptyListGoesNowhere() {
        assertNull(ZapOrder.next(emptyList<ChannelKey>(), keys[0], 1) { it })
        assertEquals(-1, ZapOrder.nextIndex(0, 0, 1))
    }

    @Test fun theListStartedFromComesFirstThenTheLastThenItsOwnGroup() {
        val fav = GuideScope.Favorites("p")
        val last = GuideScope.Group("p", "news")
        assertEquals(listOf(fav, GuideScope.Group("p", "g"), GuideScope.AllChannels("p")), ZapOrder.candidates(fav, last, "p", "g"))
        assertEquals(listOf(last, GuideScope.Group("p", "g"), GuideScope.AllChannels("p")), ZapOrder.candidates(null, last, "p", "g"))
        assertEquals(listOf(GuideScope.Group("p", "g"), GuideScope.AllChannels("p")), ZapOrder.candidates(null, null, "p", "g"))
    }

    @Test fun noRepeatsAndNoGroupWithoutOne() {
        assertEquals(listOf(GuideScope.Group("p", "g"), GuideScope.AllChannels("p")), ZapOrder.candidates(GuideScope.Group("p", "g"), null, "p", "g"))
        assertEquals(listOf(GuideScope.AllChannels("p")), ZapOrder.candidates(null, null, "p", null))
        assertEquals(listOf(GuideScope.AllPlaylists, GuideScope.AllChannels("p")), ZapOrder.candidates(GuideScope.AllPlaylists, null, "p", null))
    }
}
