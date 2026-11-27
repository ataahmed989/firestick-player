package app.fireplayer.vod

import app.fireplayer.vod.play.PlayRules.CopyEnd
import app.fireplayer.vod.play.PlayRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watched rule ([countsAsWatched]): a known scene after the credits waits for that scene, known credits wait for
 * the credits, 3 s early; 90 % and the last 3 minutes count only where neither is known; the very end always counts.
 */
class WatchedRuleTest {
    private val min = 60_000L
    private val s = 1_000L

    @Test
    fun noCreditsKnownIsAsBefore() {
        val film = 120 * min
        assertFalse(countsAsWatched(100 * min, film, null))
        // 90 %.
        assertTrue(countsAsWatched(108 * min, film, null))
        assertFalse(countsAsWatched(108 * min - 1, film, null))
        // The last 3 minutes of a long film are past 90 % anyway; of a short episode they're not.
        val episode = 22 * min
        assertFalse(countsAsWatched(18 * min, episode, null))
        assertTrue(countsAsWatched(19 * min, episode, null))
        assertTrue(countsAsWatched(episode, episode, null))
        assertFalse(countsAsWatched(10 * min, 0L, null))
    }

    @Test
    fun knownCreditsWaitForTheCreditsThreeSecondsEarly() {
        // An episode whose credits start 1 minute before its end: the last 3 minutes and 90 % no longer count.
        val episode = 24 * min
        val credits = 23 * min
        assertFalse(countsAsWatched(22 * min, episode, credits))
        assertFalse(countsAsWatched(credits - 3 * s - 1, episode, credits))
        assertTrue(countsAsWatched(credits - 3 * s, episode, credits))
        assertTrue(countsAsWatched(credits + 30 * s, episode, credits))
        // A film whose credits start well before its last 3 minutes: into them counts, as before.
        val film = 120 * min
        assertFalse(countsAsWatched(108 * min, film, 110 * min))
        assertTrue(countsAsWatched(110 * min - 2 * s, film, 110 * min))
    }

    @Test
    fun aKnownSceneAfterTheCreditsWaitsForTheScene() {
        val film = 130 * min
        val credits = 118 * min
        val scene = 128 * min
        // 90 % (117 min), the credits and the last 3 minutes: not yet.
        assertFalse(countsAsWatched(117 * min, film, credits, scene))
        assertFalse(countsAsWatched(credits + min, film, credits, scene))
        assertFalse(countsAsWatched(127 * min + 30 * s, film, credits, scene))
        assertFalse(countsAsWatched(scene - 3 * s - 1, film, credits, scene))
        assertTrue(countsAsWatched(scene - 3 * s, film, credits, scene))
        assertTrue(countsAsWatched(scene + 20 * s, film, credits, scene))
        // A scene without the credits' start known waits all the same.
        assertFalse(countsAsWatched(127 * min, film, null, scene))
        assertTrue(countsAsWatched(scene, film, null, scene))
    }

    @Test
    fun aMidCreditsSceneBeforeTheScrollIsWaitedForAlone() {
        // The Avengers in IntroDB: its scene (8107 s) comes before the credits it times (8155 s).
        val film = 8_577 * s
        assertFalse(countsAsWatched(8_100 * s, film, 8_155 * s, 8_107 * s))
        assertTrue(countsAsWatched(8_104 * s, film, 8_155 * s, 8_107 * s))
    }

    @Test
    fun theVeryEndAlwaysCounts() {
        val film = 130 * min
        assertTrue(countsAsWatched(film, film, 118 * min, 129 * min + 50 * s))
        assertTrue(countsAsWatched(film - WatchedRule.VERY_END_MS, film, 118 * min, 129 * min + 58 * s))
        assertFalse(countsAsWatched(film - WatchedRule.VERY_END_MS - 1, film, 118 * min, 129 * min + 58 * s))
    }

    @Test
    fun timesOutsideTheSecondHalfAreNotTrusted() {
        val film = 120 * min
        // A "credits' start" in the first half isn't one: as if none were known.
        assertNull(WatchedRule.endAt(film, 50 * min, null))
        assertFalse(countsAsWatched(55 * min, film, 50 * min))
        assertTrue(countsAsWatched(108 * min, film, 50 * min))
        // A "scene" past the end, or before the half, falls back to the credits.
        assertEquals(110 * min, WatchedRule.endAt(film, 110 * min, 130 * min))
        assertEquals(110 * min, WatchedRule.endAt(film, 110 * min, 20 * min))
        assertEquals(119 * min, WatchedRule.endAt(film, 110 * min, 119 * min))
        assertNull(WatchedRule.endAt(0L, 110 * min, 119 * min))
    }

    @Test
    fun anotherAppsVideoWithoutCreditsKeepsItsLastThreeMinutesAndNo90Percent() {
        val film = 120 * min
        assertFalse(endReached(110 * min, film, null, null))
        assertFalse(endReached(117 * min, film, null, null))
        assertTrue(endReached(117 * min + 1, film, null, null))
        // With known credits: the watched rule.
        assertTrue(endReached(110 * min, film, 110 * min, null))
        assertFalse(endReached(118 * min, film, 119 * min, null))
        assertFalse(endReached(118 * min, film, 110 * min, 119 * min))
        assertTrue(endReached(film, film, 110 * min, 119 * min))
    }

    @Test
    fun aSwitchStartedPastItsEndKeepsNoPlaceTheRuleCountsAsWatched() {
        val film = 130 * min
        // Barely played from a switch at its end: its place is kept only while the rule doesn't count it as watched.
        assertTrue(PlayRules.keepsPlace(CopyEnd.BARELY_PLAYED, film + min, 125 * min, film, 118 * min, 128 * min))
        assertFalse(PlayRules.keepsPlace(CopyEnd.BARELY_PLAYED, film + min, 128 * min, film, 118 * min, 128 * min))
        assertFalse(PlayRules.keepsPlace(CopyEnd.BARELY_PLAYED, film + min, 125 * min, film, 118 * min))
    }

    @Test
    fun aStopHeldOpenIsntFinishedAndResumes() {
        val p = Position(23 * min, 24 * min, 0L)
        assertTrue(p.finished)
        assertFalse(p.resumable)
        val held = p.copy(held = true)
        assertFalse(held.finished)
        assertTrue(held.resumable)
        assertFalse(LastPlayed(null, held).finished)
        assertTrue(LastPlayed(null, held).resumable)
        // Marked watched since: finished whatever the position says.
        assertTrue(LastPlayed(null, held, markedWatched = true).finished)
        assertFalse(LastPlayed(null, held, markedWatched = true).resumable)
    }
}
