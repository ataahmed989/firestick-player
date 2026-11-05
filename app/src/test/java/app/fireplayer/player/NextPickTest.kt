package app.fireplayer.player

import app.fireplayer.player.NextPick.Real
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextPickTest {

    private fun s1(vararg episodes: Int?): List<Real?> = episodes.map { e -> e?.let { Real(7, 1, it) } }

    /** Every place in the list, in turn, and what plays after it. */
    private fun allAfter(reals: List<Real?>): List<Int?> = reals.indices.map { NextPick.after(it, reals) }

    @Test
    fun aCopyOfTheEpisodePlayingIsPassedOverForTheRealNext() {
        // TiviMate's E1…E13; the provider carries E1…E6 twice each, so TiviMate's E11 and E12 are both the real E6.
        val reals = s1(1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 8)
        // Playing TiviMate's E11 (the real E6): its E13 (the real E7) is next, not E12.
        assertEquals(12, NextPick.after(10, reals))
        assertEquals(12, NextPick.after(11, reals))
        assertEquals(2, NextPick.after(0, reals))
        assertEquals(13, NextPick.after(12, reals))
        assertNull(NextPick.after(13, reals))
    }

    @Test
    fun aListWithoutCopiesPlaysInItsOrder() {
        val reals = s1(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        assertEquals((1..9).toList() + listOf(null), allAfter(reals))
        // Over two seasons: the last of the first, then the second's first.
        val seasons = (1..4).map { Real(7, 1, it) } + (1..3).map { Real(7, 2, it) }
        assertEquals((1..6).toList() + listOf(null), allAfter(seasons))
    }

    @Test
    fun withoutTheRealNumbersTheListPlaysInItsOrder() {
        assertEquals(listOf(1, 2, 3, null), allAfter(s1(null, null, null, null)))
        // Only the one playing known: the rest may be anything, so the next in the list.
        assertEquals(1, NextPick.after(0, s1(6, null, null)))
        // Only the others known.
        assertEquals(1, NextPick.after(0, s1(null, 6, 7)))
        // One not known before the real next may be it.
        assertEquals(1, NextPick.after(0, s1(6, null, 7)))
        assertEquals(2, NextPick.after(0, s1(6, 6, null, 7)))
        // Another show's videos aren't this one's numbers.
        val mixed = listOf(Real(7, 1, 6), Real(9, 1, 6), Real(7, 1, 7))
        assertEquals(1, NextPick.after(0, mixed))
    }

    @Test
    fun theRealNextEvenWhenTheListHasItLaterOn() {
        // The next season's first after the last of this one, past a copy.
        val reals = listOf(Real(7, 1, 10), Real(7, 1, 10), Real(7, 2, 1))
        assertEquals(2, NextPick.after(0, reals))
        // Out of order: the real next first; what's passed over stays to come.
        assertEquals(2, NextPick.after(0, s1(6, 8, 7)))
    }

    @Test
    fun onlyCopiesLeftEndsTheList() {
        assertNull(NextPick.after(1, s1(5, 6, 6, 6)))
        // A copy of one before it in the list, after the last episode.
        assertNull(NextPick.after(2, s1(1, 2, 3, 1)))
        // A copy of one already watched is passed over too.
        assertEquals(3, NextPick.after(0, s1(6, 7, 7, 8), watched = listOf(Real(7, 1, 7))))
        // One not known may be anything: it still plays.
        assertEquals(2, NextPick.after(1, s1(5, 6, null, 6)))
    }

    @Test
    fun theProvidersOwnListPassesOverASecondCopyToo() {
        // As the next-episode look-up lists a show: by season and number, a copy right after the original.
        val reals = listOf(Real.of(0, 1, 5), Real.of(0, 1, 6), Real.of(0, 1, 6), Real.of(0, 1, 7))
        assertEquals(3, NextPick.after(1, reals))
        // Unnumbered episodes (0) aren't known.
        assertNull(Real.of(0, 1, 0))
        assertEquals(1, NextPick.after(0, listOf(Real.of(0, 1, 0), Real.of(0, 1, 0))))
    }
}
