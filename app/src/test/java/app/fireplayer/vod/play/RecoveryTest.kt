package app.fireplayer.vod.play

import app.fireplayer.player.FilmMemory
import app.fireplayer.player.FreshLinks
import app.fireplayer.subs.RecentAnswers
import app.fireplayer.vod.Day
import app.fireplayer.vod.Episode
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Kind
import app.fireplayer.vod.Opened
import app.fireplayer.vod.PlayOrigin
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.Season
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.StartAt
import app.fireplayer.vod.Step
import app.fireplayer.vod.Title
import app.fireplayer.vod.TitleDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Recovery past a stall's first aid (a refused link, a network that went away), the wait for film where little of it
 * fits, what Skip intro's times are asked by, and answers kept so they aren't asked for twice.
 */
class RecoveryTest {

    // ---- A refused link, and no network (Recovery, SourcePlayback) ----

    private val library = FakeLibrary()
    private val ticker = FakeTicker()
    private val log = ArrayList<String>()
    private val progress = ArrayList<String>()
    private val retries = ArrayList<Long>()
    private val stops = ArrayList<String>()
    private val started = ArrayList<Boolean>()

    private val host = object : SourcePlayback.Host {
        override fun showProgress(text: String) { progress += text }
        override fun startSource(ready: Opened.Ready, startMs: Long, switching: Boolean) { started += switching }
        override fun ask(question: String, choices: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) = Unit
        override fun stopWith(message: String) { stops += message }
        override fun notice(text: String) = Unit
        override fun positionMs() = 600_000L
        override fun retryAfter(ms: Long) { retries += ms }
        override fun decodeOnDevice() = Unit
        override fun playNext(next: PlayRequest) = Unit
        override fun log(text: String) { log += text }
    }

    private val film = PlayRequest(SourceTarget(Fixtures.film), StartAt.Beginning, PlayOrigin.TITLE_PAGE)

    private fun playing(): Pair<SourcePlayback, FakeSession> {
        val session = FakeSession(film)
        val p = SourcePlayback(film, session, library, ticker, host)
        p.start()
        session.ready(Fixtures.source(1))
        p.firstFrame()
        return p to session
    }

    @Test
    fun aRefusedLinkIsNotAskedAgain() {
        for (status in listOf(401, 403, 404, 410)) {
            assertTrue("$status", Recovery.linkRefused(status))
            assertTrue("$status", FreshLinks.refused(status))
        }
        for (status in listOf<Int?>(null, 200, 400, 416, 429, 500, 502, 503)) assertFalse("$status", Recovery.linkRefused(status))
        // The film's connection gives up on a quiet download before a small buffer has run dry.
        assertTrue(FreshLinks.READ_TIMEOUT_MS < 10_000)
    }

    @Test
    fun aRefusedLinkGetsAFreshOneForTheSameFileThenTheFilmPlaysOn() {
        val (p, session) = playing()
        session.step = Step.Refresh
        p.failed(2004, 403)
        assertEquals(listOf("k1"), session.refreshed.map { it.key })
        assertTrue(log.last(), log.last().contains("a fresh link for the same file"))
        // The fresh link takes the old one's place, a little before where the film stood.
        session.ready(Fixtures.source(1))
        assertEquals(listOf(false, true), started)
        assertTrue(stops.isEmpty())
        p.firstFrame()
        assertTrue(p.playing)
    }

    @Test
    fun theNetworkGoneIsWaitedForAndNothingIsHeldAgainstTheSource() {
        val (p, session) = playing()
        session.netDown = true
        p.failed(2001, null)
        // No failure counted, no stop, no switch: the ring says why, and the network is looked at now and then.
        assertTrue(session.failures.isEmpty())
        assertTrue(stops.isEmpty())
        assertEquals(1, session.opens.size)
        assertEquals(Recovery.NET_WAITING, progress.last())
        ticker.advance(60_000)
        assertTrue(retries.isEmpty())
        // Back: the same link again, once the Wi-Fi has settled.
        session.netDown = false
        ticker.advance(Recovery.NET_POLL_MS)
        assertEquals(listOf(Recovery.NET_SETTLE_MS), retries)
        assertTrue(log.any { it.startsWith("network back after") })
        // And only once: nothing more is asked until the film says how it went.
        ticker.advance(60_000)
        assertEquals(1, retries.size)
        p.firstFrame()
        assertTrue(p.playing)
    }

    @Test
    fun aNetworkThatStaysAwayStopsThePlayInTheEnd() {
        val (p, session) = playing()
        session.netDown = true
        p.failed(2001, null)
        ticker.advance(Recovery.NET_WAIT_MS - Recovery.NET_POLL_MS)
        assertTrue(stops.isEmpty())
        ticker.advance(2 * Recovery.NET_POLL_MS)
        assertEquals(listOf(SourcePlayback.NO_NETWORK), stops)
        assertTrue(retries.isEmpty())
        assertEquals(0, ticker.pending)
    }

    @Test
    fun theWaitForTheNetworkEndsWithThePlayOrAnotherSource() {
        val (p, session) = playing()
        session.netDown = true
        p.failed(2001, null)
        // The viewer picks another source meanwhile: the wait is over.
        p.nextSource()
        session.ready(Fixtures.source(2))
        session.netDown = false
        ticker.advance(60_000)
        assertTrue(retries.isEmpty())
        // Leaving the screen, or closing the play, stops the looking too.
        session.netDown = true
        p.firstFrame()
        p.failed(2001, null)
        p.pause()
        assertEquals(0, ticker.pending)
        p.failed(2001, null)
        p.close()
        ticker.advance(60_000)
        assertTrue(retries.isEmpty())
    }

    @Test
    fun beforeAnyPictureNoNetworkStillGoesByTheFailureTable() {
        val session = FakeSession(film)
        val p = SourcePlayback(film, session, library, ticker, host)
        p.start()
        session.ready(Fixtures.source(1))
        session.netDown = true
        session.step = Step.Stop("No internet connection. Check the Wi-Fi and try again.")
        p.failed(2001, null)
        assertEquals(1, session.failures.size)
        assertEquals(1, stops.size)
    }

    @Test
    fun theStepsOfAWaitForTheNetwork() {
        assertEquals(Recovery.NetStep.PLAY, Recovery.netStep(online = true, waitedMs = 0))
        assertEquals(Recovery.NetStep.PLAY, Recovery.netStep(online = true, waitedMs = Recovery.NET_WAIT_MS * 2))
        assertEquals(Recovery.NetStep.WAIT, Recovery.netStep(online = false, waitedMs = Recovery.NET_WAIT_MS - 1))
        assertEquals(Recovery.NetStep.GIVE_UP, Recovery.netStep(online = false, waitedMs = Recovery.NET_WAIT_MS))
    }

    // ---- The wait for film where little of it fits (FilmMemory) ----

    private val mib = 1L shl 20
    private val s = 1_000_000L

    @Test
    fun whatFitsIsMeasuredWhenFullElseWorkedOutFromWhatIsLoaded() {
        // An 80 Mb/s film (10 MB a second): 2 s of it in 20 MiB, of 128 MiB.
        val room = FilmMemory.roomUs(0L, 2 * s, 20 * mib, 128 * mib)
        assertEquals(12.8, room / 1e6, 0.01)
        // Once the buffer has been full, what it then held is the answer.
        assertEquals(11 * s, FilmMemory.roomUs(11 * s, 2 * s, 20 * mib, 128 * mib))
        // Too little loaded to tell.
        assertEquals(0L, FilmMemory.roomUs(0L, s / 2, 20 * mib, 128 * mib))
        assertEquals(0L, FilmMemory.roomUs(0L, 2 * s, 1 * mib, 128 * mib))
        assertEquals(0L, FilmMemory.roomUs(0L, 2 * s, 20 * mib, 0L))
    }

    @Test
    fun theWaitIsNeverForMoreThanHalfOfWhatFits() {
        val room = 12 * s
        // The rules' 8 s cushion (a line that only just keeps up), 10 and 20 s after repeated stalls: 6 s each.
        for (wanted in listOf(8 * s, 10 * s, 20 * s, 30 * s)) assertEquals(6 * s, FilmMemory.waitUs(wanted, room, FilmMemory.STALL_WAIT_LEAST_US))
        // What the rules ask for anyway when it's less: a first stall's 4 s, a start's 1 s.
        assertEquals(4 * s, FilmMemory.waitUs(4 * s, room, FilmMemory.STALL_WAIT_LEAST_US))
        assertEquals(1 * s, FilmMemory.waitUs(1 * s, room, 1 * s))
        // A lighter film, of which a minute or more fits: untouched, whatever the rules ask for (30 s at most).
        assertEquals(20 * s, FilmMemory.waitUs(20 * s, 120 * s, FilmMemory.STALL_WAIT_LEAST_US))
        assertEquals(30 * s, FilmMemory.waitUs(30 * s, 60 * s, FilmMemory.STALL_WAIT_LEAST_US))
        // In between (40 s fit): the wait leaves a quarter of a minute to fill as it plays.
        assertEquals(25 * s, FilmMemory.waitUs(30 * s, 40 * s, FilmMemory.STALL_WAIT_LEAST_US))
        assertEquals(20 * s, FilmMemory.waitUs(20 * s, 40 * s, FilmMemory.STALL_WAIT_LEAST_US))
        // Not known yet: untouched.
        assertEquals(20 * s, FilmMemory.waitUs(20 * s, 0L, FilmMemory.STALL_WAIT_LEAST_US))
        // Hardly anything fits: still a couple of seconds after a stall, but never more than was asked.
        assertEquals(FilmMemory.STALL_WAIT_LEAST_US, FilmMemory.waitUs(10 * s, 3 * s, FilmMemory.STALL_WAIT_LEAST_US))
        assertEquals(1 * s, FilmMemory.waitUs(1 * s, 3 * s, FilmMemory.STALL_WAIT_LEAST_US))
    }

    // ---- What Skip intro's times are asked by (PlayerMeta) ----

    private val show = Title("tt1695360", Kind.SERIES, "Show", year = "2012–2014", tmdbId = 1234, runtime = "45 min")

    private fun ep(season: Int, number: Int, minutes: Int?) =
        Episode(Episode.idOf(show.id, season, number), season, number, "E$number", released = 1_000L, day = Day(2013, 10, 5), runtimeMin = minutes)

    private val details = TitleDetails(
        title = show,
        runtimeMin = 45,
        seasons = listOf(Season(1, listOf(ep(1, 1, 62), ep(1, 2, null))), Season(2, listOf(ep(2, 1, 48)))),
    )

    private fun episode(season: Int, number: Int) =
        PlayRequest(SourceTarget(show, EpisodeRef(season, number, "x")), StartAt.Beginning, PlayOrigin.TITLE_PAGE)

    @Test
    fun skipTimesAreKeptByTheIdsHandedOverNeverAnotherEpisodes() {
        assertEquals("tt1695360/2/1", PlayerMeta.skipKey(episode(2, 1)))
        assertEquals("tt0133093/film", PlayerMeta.skipKey(film))
        // Each episode its own, and the same whatever is known of the show by then.
        assertFalse(PlayerMeta.skipKey(episode(1, 2)) == PlayerMeta.skipKey(episode(2, 1)))
        assertFalse(PlayerMeta.skipKey(episode(1, 12)) == PlayerMeta.skipKey(episode(11, 2)))
    }

    @Test
    fun theLengthSkipTimesAreWeighedAgainstIsTheEpisodesOwnWhereKnown() {
        // A long first episode isn't weighed against the show's usual 45 minutes.
        assertEquals(62, PlayerMeta.runtimeMinutes(episode(1, 1), details))
        // Not listed for this one, or the details not in yet: the show's usual length.
        assertEquals(45, PlayerMeta.runtimeMinutes(episode(1, 2), details))
        assertEquals(45, PlayerMeta.runtimeMinutes(episode(1, 1), null))
        assertEquals(45, PlayerMeta.runtimeMinutes(episode(3, 9), details))
        // A film: its own.
        assertEquals(136, PlayerMeta.runtimeMinutes(film, null))
        // Unknown: 0, and IntroDB's times go by their other evidence.
        val bare = PlayRequest(SourceTarget(Title("tt0000001", Kind.MOVIE, "Bare")), StartAt.Beginning, PlayOrigin.TITLE_PAGE)
        assertEquals(0, PlayerMeta.runtimeMinutes(bare, null))
    }

    // ---- Answers kept, so the same thing isn't asked twice (RecentAnswers) ----

    @Test
    fun theSameSearchIsAskedOnce() {
        var now = 0L
        val kept = RecentAnswers<String>(max = 2, keptMs = 1_000L) { now }
        var asks = 0
        fun ask(q: String) = kept.getOrAsk(q) { asks++; "answer to $q" }
        assertEquals("answer to a", ask("a"))
        assertEquals("answer to a", ask("a"))
        assertEquals(1, asks)
        // Too old: asked again.
        now = 1_000L
        assertNull(kept.get("a"))
        ask("a")
        assertEquals(2, asks)
        // The one asked for longest ago makes room.
        ask("b")
        ask("a")
        ask("c")
        assertEquals(2, kept.size)
        assertNull(kept.get("b"))
        assertEquals("answer to a", kept.get("a"))
    }

    @Test
    fun aFailedAskKeepsNothing() {
        val kept = RecentAnswers<String>(max = 4, keptMs = 1_000L) { 0L }
        runCatching { kept.getOrAsk("a") { throw IllegalStateException("no answer") } }
        assertNull(kept.get("a"))
        assertEquals("now", kept.getOrAsk("a") { "now" })
    }
}
