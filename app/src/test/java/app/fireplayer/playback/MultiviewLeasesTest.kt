package app.fireplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Multiview's pictures never open more streams than an account allows, counted with the app's other playback. */
class MultiviewLeasesTest {
    private val budget = ConnectionBudget()
    private val leases = MultiviewLeases(budget)

    @Test fun picturesStopAtTheAllowance() {
        assertTrue(leases.take(1, "a", 2))
        assertTrue(leases.take(2, "a", 2))
        assertFalse(leases.take(3, "a", 2))
        assertEquals(2, budget.inUse("a"))
        assertFalse(leases.holds(3))
        // Another account has its own allowance.
        assertTrue(leases.take(3, "b", 1))
        assertEquals(0, budget.free("b", 1))
    }

    @Test fun anUnknownAllowanceIsOneStream() {
        assertEquals(1, budget.free("m3u", 0))
        assertTrue(leases.take(1, "m3u", 0))
        assertFalse(leases.take(2, "m3u", 0))
        assertEquals(0, budget.free("m3u", 0))
    }

    @Test fun theOnePictureLeaseCountsOnceAndGoesBack() {
        assertTrue(leases.take(1, "a", 1))
        assertTrue(leases.take(1, "a", 1))
        assertEquals(1, budget.inUse("a"))
        leases.drop(1)
        assertEquals(0, budget.inUse("a"))
        assertTrue(leases.take(1, "a", 1))
        assertTrue(leases.take(2, "b", 1))
        leases.dropAll()
        assertEquals(0, budget.inUse("a"))
        assertEquals(0, budget.inUse("b"))
    }

    @Test fun changingAPicturesChannelKeepsItsConnectionWhenTheNewOneIsRefused() {
        assertTrue(leases.take(1, "a", 1))
        assertTrue(leases.take(2, "b", 1))
        // Picture 1 to a channel on b: b is full (picture 2), so picture 1 keeps a.
        assertFalse(leases.swap(1, "b", 1, oldLimit = 1))
        assertEquals(1, budget.inUse("a"))
        assertTrue(leases.holds(1))
        // Picture 2 to a channel on c: b goes back, c is taken.
        assertTrue(leases.swap(2, "c", 1, oldLimit = 1))
        assertEquals(0, budget.inUse("b"))
        assertEquals(1, budget.inUse("c"))
        // Its own account again: one lease, still.
        assertTrue(leases.swap(2, "c", 1, oldLimit = 1))
        assertEquals(1, budget.inUse("c"))
    }

    @Test fun theOnePictureFullScreenHandsItsConnectionToMultiview() {
        // Full screen holds the live session's lease; a second channel on the same account with room for two fits.
        assertTrue(budget.acquire("a", 2, "live"))
        assertEquals(1, budget.free("a", 2))
        // Multiview opens: the one picture's stream is stopped first, then the two pictures take theirs.
        budget.release("a", "live")
        assertTrue(leases.take(1, "a", 2))
        assertTrue(leases.take(2, "a", 2))
        assertFalse(leases.take(3, "a", 2))
        // Leaving: every picture's lease goes back before the one picture plays again.
        leases.dropAll()
        assertTrue(budget.acquire("a", 2, "live"))
        // An update asking the provider meanwhile can't get past the pictures either.
        assertTrue(leases.take(1, "a", 2))
        assertFalse(budget.acquire("a", 2, "update"))
    }
}
