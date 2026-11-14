package app.fireplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DamageSkipsTest {

    @Test
    fun toldOnceWhenPlaybackLandsPastTheStretch() {
        val skips = DamageSkips()
        skips.add("film", 2_419_600_000L, 2_471_010_000L)
        assertNull(skips.reached("film", 2_400_000L))
        val s = skips.reached("film", 2_471_200L)
        assertNotNull(s)
        assertEquals(2_419_600L, s!!.fromMs)
        assertEquals(2_471_010L, s.toMs)
        assertNull(skips.reached("film", 2_471_400L))
    }

    @Test
    fun toldAgainAfterGoingBackBeforeIt() {
        val skips = DamageSkips()
        skips.add("film", 100_000_000L, 150_000_000L)
        assertNotNull(skips.reached("film", 150_100L))
        assertNull(skips.reached("film", 151_000L))
        assertNull(skips.reached("film", 90_000L))
        assertNotNull(skips.reached("film", 150_300L))
    }

    @Test
    fun theSameStretchFoundAgainIsKeptOnceAndOtherFilmsAreLeftOut() {
        val skips = DamageSkips()
        skips.add("film", 100_000_000L, 150_000_000L)
        skips.add("film", 101_000_000L, 150_000_000L)
        assertNull(skips.reached("other", 150_100L))
        assertNotNull(skips.reached("film", 150_100L))
        assertNull(skips.reached("film", 150_200L))
    }

    @Test
    fun farPastTheStretchSaysNothing() {
        val skips = DamageSkips()
        skips.add("film", 100_000_000L, 150_000_000L)
        assertNull(skips.reached("film", 300_000L))
    }
}
