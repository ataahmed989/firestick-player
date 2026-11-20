package app.fireplayer.vod

import app.fireplayer.vod.play.PlayRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which episode comes next ([UpNext]), and that the title page's Play and the player's next episode agree with it. */
class UpNextTest {

    private val day = 24 * 3600_000L
    private val now = 1_000 * day
    private val show = "tt0000100"

    /** An episode that aired [daysAgo] days before [now] (negative: not yet; null: no date). */
    private fun ep(season: Int, episode: Int, daysAgo: Long? = 300) =
        Episode(Episode.idOf(show, season, episode), season, episode, "S$season E$episode", released = daysAgo?.let { now - it * day })

    // Three seasons, all aired long ago; a special aired since, and one listed first as Cinemeta does.
    private val three = listOf(
        ep(0, 1, 900), ep(0, 2, 2),
        ep(1, 1), ep(1, 2), ep(1, 3),
        ep(2, 1), ep(2, 2),
        ep(3, 1), ep(3, 2),
    )

    private val none: (Episode) -> Boolean = { false }

    private fun of(from: EpisodeRef, episodes: List<Episode>?, watched: (Episode) -> Boolean = none, over: Boolean = true, overAt: Long = now - day) =
        UpNext.of(from, over, overAt, episodes, now, watched)

    @Test
    fun theLastEpisodeOfASeasonGoesOnToTheNextSeasonsFirst() {
        assertEquals(UpNext.Up.Next(ep(2, 1), fresh = false), of(EpisodeRef(1, 3), three))
        assertEquals(UpNext.Up.Next(ep(3, 1), fresh = false), of(EpisodeRef(2, 2), three))
        // Within a season, the next one.
        assertEquals(ep(1, 2), (of(EpisodeRef(1, 1), three) as UpNext.Up.Next).episode)
    }

    @Test
    fun theFinalAiredEpisodeEndsTheShowNeverASpecialOrAnAnnouncedSeason() {
        // The special aired 2 days ago is never next.
        assertEquals(UpNext.Up.Done, of(EpisodeRef(3, 2), three))
        // A fourth season announced: one episode not aired yet, one with no date.
        val announced = three + listOf(ep(4, 1, -30), ep(4, 2, null))
        assertEquals(UpNext.Up.Done, of(EpisodeRef(3, 2), announced))
        assertNull(UpNext.after(announced, EpisodeRef(3, 2), now, none))
    }

    @Test
    fun episodesNotKnownYetKeepTheRecordAsItIs() {
        assertEquals(UpNext.Up.Unknown, of(EpisodeRef(1, 3), null))
        assertEquals(UpNext.Up.Unknown, of(EpisodeRef(1, 3), emptyList()))
        // Not over: its own episode, whatever is known.
        assertEquals(UpNext.Up.Own, of(EpisodeRef(1, 3), null, over = false))
        assertEquals(UpNext.Up.Own, of(EpisodeRef(3, 2), three, over = false))
    }

    @Test
    fun aSeasonFinishedOutOfOrderGoesOnToTheNextUnwatched() {
        // S1 E1 and E3 watched first, E2 last: the season is done, so S2 E1.
        val watched = setOf(ep(1, 1).id, ep(1, 3).id)
        assertEquals(ep(2, 1), (of(EpisodeRef(1, 2), three, { it.id in watched }) as UpNext.Up.Next).episode)
        // Everything after it watched (the whole show marked watched): nothing left.
        assertEquals(UpNext.Up.Done, of(EpisodeRef(1, 2), three, { true }))
    }

    @Test
    fun aGapInTheNumbersIsPassedOver() {
        val gap = listOf(ep(1, 1), ep(1, 2), ep(1, 4), ep(2, 1))
        assertEquals(ep(1, 4), UpNext.after(gap, EpisodeRef(1, 2), now, none))
        assertEquals(ep(2, 1), UpNext.after(gap, EpisodeRef(1, 4), now, none))
        // A record for the missing number goes on to the one after it too.
        assertEquals(ep(1, 4), UpNext.after(gap, EpisodeRef(1, 3), now, none))
        // One with no date between aired ones is passed over, as Play does.
        val undated = listOf(ep(1, 1), ep(1, 2, null), ep(1, 3))
        assertEquals(ep(1, 3), UpNext.after(undated, EpisodeRef(1, 1), now, none))
    }

    @Test
    fun anEpisodeAiredAfterTheRecordWasOverIsFresh() {
        // Caught up on S1 ten days ago; S2 E1 aired two days ago: Next Episodes', not Continue Watching's.
        val weekly = listOf(ep(1, 1), ep(1, 2), ep(2, 1, 2))
        assertEquals(UpNext.Up.Next(ep(2, 1, 2), fresh = true), of(EpisodeRef(1, 2), weekly, overAt = now - 10 * day))
        assertEquals(UpNext.Up.Next(ep(2, 1, 2), fresh = false), of(EpisodeRef(1, 2), weekly, overAt = now - day))
    }

    @Test
    fun playAndThePlayersNextAgreeWithContinueWatching() {
        fun finished(s: Int, e: Int) = LastPlayed(EpisodeRef(s, e), Position(39 * 60_000L, 40 * 60_000L, now - day))
        // A season's last episode: Play, the player and Continue Watching all go on to S2 E1.
        assertEquals(ep(2, 1), playTarget(three, finished(1, 3), now)!!.episode)
        assertEquals(PlayAction.PLAY, playTarget(three, finished(1, 3), now)!!.action)
        assertEquals(ep(2, 1), PlayRules.nextEpisode(three, EpisodeRef(1, 3), now))
        // The final one: Play watches again, the player has none.
        assertEquals(PlayAction.WATCH_AGAIN, playTarget(three, finished(3, 2), now)!!.action)
        assertNull(PlayRules.nextEpisode(three, EpisodeRef(3, 2), now))
        // Out of order: Play passes over the watched ones as Continue Watching does; the player goes on in order.
        val watched = setOf(ep(1, 1).id, ep(1, 3).id)
        assertEquals(ep(2, 1), playTarget(three, finished(1, 2), now) { it.id in watched }!!.episode)
        assertEquals(ep(1, 3), PlayRules.nextEpisode(three, EpisodeRef(1, 2), now))
        // Watched again (every one after it watched): Play goes on in order; after a special, it starts over.
        val again = playTarget(three, finished(1, 1), now) { true }!!
        assertEquals(ep(1, 2), again.episode)
        assertEquals(PlayAction.PLAY, again.action)
        val special = playTarget(three, finished(0, 1), now) { true }!!
        assertEquals(PlayAction.WATCH_AGAIN, special.action)
        assertNull(special.upcoming)
    }

    @Test
    fun regularEpisodesInOrderOncePerNumber() {
        val messy = listOf(ep(2, 1), ep(0, 1), ep(1, 2), ep(1, 1), ep(1, 2))
        assertEquals(listOf(ep(1, 1), ep(1, 2), ep(2, 1)), UpNext.regular(messy))
        assertTrue(UpNext.isAfter(ep(2, 1), EpisodeRef(1, 9)))
        assertFalse(UpNext.isAfter(ep(1, 1), EpisodeRef(1, 1)))
    }
}
