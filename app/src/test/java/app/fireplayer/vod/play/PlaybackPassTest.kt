package app.fireplayer.vod.play

import app.fireplayer.player.ShowGuide
import app.fireplayer.vod.Day
import app.fireplayer.vod.EarlyOpen
import app.fireplayer.vod.Episode
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.From
import app.fireplayer.vod.Kind
import app.fireplayer.vod.LastRelease
import app.fireplayer.vod.Opened
import app.fireplayer.vod.OpenWhy
import app.fireplayer.vod.PlayOrigin
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.Position
import app.fireplayer.vod.ReleaseKey
import app.fireplayer.vod.Season
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.Sourced
import app.fireplayer.vod.StartAt
import app.fireplayer.vod.Title
import app.fireplayer.vod.TitleDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The playback pass's pure rules: ids instead of names, the stall's first aid, the start's timing, the early open. */
class PlaybackPassTest {

    // ---- Ids instead of names (PlayerMeta) ----

    private val show = Title("tt1695360", Kind.SERIES, "Show", year = "2012–2014", tmdbId = 1234, imdbRating = "8.1", genres = listOf("Drama"))
    private val day = 24 * 60 * 60_000L

    private fun ep(s: Int, e: Int, name: String, aired: Long? = 1_000L) =
        Episode(Episode.idOf(show.id, s, e), s, e, name, released = aired, day = Day(2013, 10, 5), overview = "About $name", still = "https://img.invalid/$s-$e", runtimeMin = 23)

    private val details = TitleDetails(
        title = show,
        runtimeMin = 23,
        ageRating = "TV-MA",
        story = Sourced("The whole story.", From.TMDB),
        seasons = listOf(
            Season(0, listOf(ep(0, 1, "A special"))),
            Season(1, listOf(ep(1, 1, "Pilot"), ep(1, 2, "Episode 2"))),
            Season(2, listOf(ep(2, 1, "Back"), ep(2, 2, "TBA", aired = 10 * day))),
        ),
    )

    private fun request(season: Int, episode: Int) =
        PlayRequest(SourceTarget(show, EpisodeRef(season, episode, "x")), StartAt.Beginning, PlayOrigin.TITLE_PAGE)

    @Test
    fun theGuideListsCinemetasAiredEpisodesAllMatchedByTheirNumbers() {
        val g = PlayerMeta.guide(details, now = day)!!
        assertEquals("Show (2012)", g.show)
        assertEquals("Show", g.searchName)
        assertEquals(1234, g.tmdbId)
        assertEquals(PlayerMeta.PORTAL, g.portal)
        // Not aired yet: not in the sheet (it couldn't play).
        assertEquals(listOf("0:1", "1:1", "1:2", "2:1"), g.episodes.map { it.id })
        assertEquals(listOf(0, 1, 2), g.seasons)
        assertTrue(g.episodes.all { it.match == ShowGuide.Match.SURE })
        val pilot = g.episode("1:1")!!
        assertEquals("S1 E1", pilot.label)
        assertEquals("Pilot", pilot.name)
        assertEquals("About Pilot", pilot.overview)
        assertEquals("2013-10-05", pilot.airDate)
        assertEquals(23, pilot.runtimeMin)
        // Cinemeta's stand-in names say nothing the numbers don't.
        assertEquals("", g.episode("1:2")!!.name)
    }

    @Test
    fun theEpisodePlayingIsFoundByTheNumbersHandedOverNeverByAName() {
        val g = PlayerMeta.guide(details, now = day)
        assertEquals("2:1", PlayerMeta.episode(g, request(2, 1))?.id)
        assertEquals("Back", PlayerMeta.episode(g, request(2, 1))?.name)
        // An episode the list doesn't hold (not aired): none, rather than a neighbour.
        assertNull(PlayerMeta.episode(g, request(2, 2)))
        assertNull(PlayerMeta.episode(g, PlayRequest(SourceTarget(Fixtures.film), StartAt.Beginning, PlayOrigin.TITLE_PAGE)))
        assertEquals(EpisodeRef(2, 1, "Back"), PlayerMeta.refOf(g!!.episode("2:1")!!))
    }

    @Test
    fun aFilmHasNoGuideAndIsNamedWithItsYear() {
        assertNull(PlayerMeta.guide(TitleDetails(Fixtures.film), now = day))
        assertEquals("Film (1999)", PlayerMeta.shownName(Fixtures.film))
        assertEquals("Film (1999)", PlayerMeta.rawTitle(PlayRequest(SourceTarget(Fixtures.film), StartAt.Beginning, PlayOrigin.TITLE_PAGE)))
        assertEquals("Show S02E01", PlayerMeta.rawTitle(request(2, 1)))
        assertEquals(1999, PlayerMeta.year(Fixtures.film))
        assertEquals(2012, PlayerMeta.year(show))
        assertEquals("Untitled", PlayerMeta.shownName(Title("tt0000001", Kind.MOVIE, "Untitled")))
    }

    @Test
    fun detailsComeFromWhatTheTitlePageLoaded() {
        val a = PlayerMeta.about(details)
        assertEquals("Show", a.name)
        assertEquals("2012", a.year)
        assertEquals("The whole story.", a.overview)
        assertEquals(listOf("Drama"), a.genres)
        assertEquals(8.1, a.rating, 0.001)
        assertEquals("IMDb", a.ratingFrom)
        assertEquals("TV-MA", a.ageRating)
        // A show: its seasons (specials aside), no film length.
        assertEquals(2, a.seasons)
        assertEquals(0, a.runtime)
        val film = PlayerMeta.about(TitleDetails(Fixtures.film.copy(description = "Short story")))
        assertEquals(136, film.runtime)
        assertEquals(0, film.seasons)
        assertEquals("Short story", film.overview)
    }

    @Test
    fun theFinaleIsTheLastEpisodeListedOfAShowThatEnded() {
        val ended = details.copy(status = "Ended")
        // An episode not aired yet still comes after 2x01.
        assertFalse(PlayerMeta.isFinale(ended, EpisodeRef(2, 1)))
        assertTrue(PlayerMeta.isFinale(ended, EpisodeRef(2, 2)))
        // A show still running has no finale yet, and a special is never one.
        assertFalse(PlayerMeta.isFinale(details.copy(status = "Continuing"), EpisodeRef(2, 2)))
        assertFalse(PlayerMeta.isFinale(details, EpisodeRef(2, 2)))
        assertFalse(PlayerMeta.isFinale(ended, EpisodeRef(0, 1)))
    }

    @Test
    fun watchedSharesComeFromTheLibrarysPositions() {
        assertEquals(0f, PlayerMeta.watchedShare(null))
        assertEquals(0.5f, PlayerMeta.watchedShare(Position(600_000, 1_200_000, 1L)))
        // In the last three minutes: watched.
        assertEquals(1f, PlayerMeta.watchedShare(Position(1_100_000, 1_200_000, 1L)))
        assertEquals(0f, PlayerMeta.watchedShare(Position(5_000, 0, 1L)))
    }

    // ---- Another episode from the player's Episodes sheet ----

    private val library = FakeLibrary()
    private val ticker = FakeTicker()
    private var handed: PlayRequest? = null
    private val log = ArrayList<String>()
    private var reopens = 0
    private var canReopen = true
    private var position = 0L

    private val host = object : SourcePlayback.Host {
        override fun showProgress(text: String) = Unit
        override fun startSource(ready: Opened.Ready, startMs: Long, switching: Boolean) = Unit
        override fun ask(question: String, choices: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) = Unit
        override fun stopWith(message: String) = Unit
        override fun notice(text: String) = Unit
        override fun positionMs() = position
        override fun retryAfter(ms: Long) = Unit
        override fun decodeOnDevice() = Unit
        override fun playNext(next: PlayRequest) { handed = next }
        override fun reopen(): Boolean {
            if (canReopen) reopens++
            return canReopen
        }
        override fun log(text: String) { log += text }
    }

    private fun playing(req: PlayRequest = request(1, 1)): Pair<SourcePlayback, FakeSession> {
        val session = FakeSession(req)
        val p = SourcePlayback(req, session, library, ticker, host)
        p.start()
        session.ready(Fixtures.source(1))
        p.firstFrame()
        return p to session
    }

    @Test
    fun anEpisodeChosenInThePlayerIsANewPlayOfItsOwnSources() {
        val (p, _) = playing()
        library.recordRelease(show, EpisodeRef(2, 1), LastRelease(app.fireplayer.vod.Addon.TORRENTIO, ReleaseKey(Fixtures.hash(7), 0), 1L))
        p.playEpisode(EpisodeRef(2, 1, "Back"), StartAt.At(300_000))
        val r = handed!!
        assertEquals(SourceTarget(show, EpisodeRef(2, 1, "Back")).key, r.target.key)
        assertEquals(StartAt.At(300_000), r.start)
        // Resuming: the same file as last time first; the kind of source playing now is the fallback.
        assertEquals(ReleaseKey(Fixtures.hash(7), 0), r.preferred?.release)
        assertEquals("k1", r.like?.key)
        assertEquals(show.id, r.imdbId)
        assertEquals(2, r.season)
        // From its beginning: no "last release" pinned.
        p.playEpisode(EpisodeRef(2, 1, "Back"), StartAt.Beginning)
        assertNull(handed!!.preferred)
    }

    @Test
    fun nextIsOnlyKnownOnceThePageOrTheDetailsSaid() {
        val (p, _) = playing()
        assertFalse(p.nextKnown)
        assertNull(p.next)
        val (q, _) = playing(request(1, 1).copy(next = EpisodeRef(1, 2)))
        assertTrue(q.nextKnown)
    }

    // ---- A stall's first aid, then the switching rules (StallAid) ----

    @Test
    fun aStallWithNothingArrivingReopensTheSameSourceOnce() {
        val aid = StallAid()
        aid.stalled(0, true)
        assertFalse(aid.shouldReopen(1_000, 0))
        assertFalse(aid.shouldReopen(7_900, 0))
        assertTrue(aid.shouldReopen(8_000, 0))
        // Once per stall.
        assertFalse(aid.shouldReopen(12_000, 0))
        assertFalse(aid.shouldReopen(30_000, 0))
    }

    @Test
    fun aStallThatIsStillLoadingIsNeverReopened() {
        val aid = StallAid()
        aid.stalled(0, true)
        var ahead = 0L
        var t = 0L
        // A slow line: something arrives every second.
        while (t <= 30_000) {
            assertFalse(aid.shouldReopen(t, ahead))
            ahead += 200
            t += 1_000
        }
        // It goes quiet (nothing more than was there at 30 s): four seconds on, the reopen.
        val last = ahead - 200
        assertFalse(aid.shouldReopen(32_000, last))
        assertTrue(aid.shouldReopen(34_000, last))
    }

    @Test
    fun reopensAreLimitedSoASourceThatKeepsGoingQuietIsLeftToTheSwitchRule() {
        val aid = StallAid()
        var t = 0L
        repeat(2) {
            aid.stalled(t, true)
            assertTrue(aid.shouldReopen(t + 8_000, 0))
            aid.stalled(t + 9_000, false)
            t += 60_000
        }
        aid.stalled(t, true)
        assertFalse(aid.shouldReopen(t + 20_000, 0))
        // Ten minutes after the first, one is allowed again.
        aid.stalled(t + 21_000, false)
        aid.stalled(StallAid.WINDOW_MS + 61_000, true)
        assertTrue(aid.shouldReopen(StallAid.WINDOW_MS + 70_000, 0))
    }

    @Test
    fun aStallEndingOrAJumpStartsAfresh() {
        val aid = StallAid()
        aid.stalled(0, true)
        aid.stalled(5_000, false)
        assertFalse(aid.shouldReopen(9_000, 0))
        aid.stalled(10_000, true)
        aid.reset()
        assertFalse(aid.shouldReopen(30_000, 0))
    }

    @Test
    fun thePlayerReopensFirstAndSwitchesOnlyWhenTheRuleSays() {
        val (p, s) = playing()
        position = 3_723_000
        p.buffering(true)
        ticker.advance(7_000)
        p.tick(0)
        assertEquals(0, reopens)
        ticker.advance(1_000)
        p.tick(0)
        assertEquals(1, reopens)
        assertTrue(log.last().startsWith("stall 8 s with nothing arriving: same source reopened at 1:02:03"))
        // No switch yet: the rule hasn't said so.
        assertEquals(1, s.opens.size)
        ticker.advance(12_000)
        s.switchNow = true
        p.tick(0)
        assertEquals(null to OpenWhy.STALLS, s.opens.last())
        assertEquals(1, reopens)
        assertTrue(log.any { it.startsWith("stalls past the limit") })
    }

    @Test
    fun aReopenThePlayerCantMakeIsntLoggedAndAnErrorSaysItsStep() {
        val (p, s) = playing()
        canReopen = false
        p.buffering(true)
        ticker.advance(9_000)
        p.tick(0)
        assertTrue(log.none { it.contains("reopened") })
        p.buffering(false)
        assertEquals("playing again after a 9 s stall", log.last())
        s.step = app.fireplayer.vod.Step.Refresh
        p.failed(2004, 403)
        assertTrue(log.last().startsWith("error 2004 (HTTP 403) counted as "))
        assertTrue(log.last().endsWith("a fresh link for the same file"))
        assertEquals(1, s.refreshed.size)
    }

    // ---- The start's timing (LaunchLog) ----

    @Test
    fun theStartsLineHasEachStepOnceInOrderSinceOk() {
        val l = LaunchLog(1_000)
        l.mark(LaunchLog.SCREEN, 1_212)
        l.mark(LaunchLog.LINK, 2_480)
        l.mark(LaunchLog.LINK, 9_999)
        l.mark(LaunchLog.PLAYER, 2_731)
        l.mark(LaunchLog.FRAME, 4_102)
        assertEquals(1_480L, l.at(LaunchLog.LINK))
        assertEquals(-1L, l.at(LaunchLog.LOADED))
        assertFalse(l.done)
        assertEquals("start screen +212ms · link +1480ms · player +1731ms · frame +3102ms (early)", l.line("early"))
        assertTrue(l.done)
        // Later first frames (a jump, another source) aren't the start's.
        l.mark(LaunchLog.LOADED, 5_000)
        assertEquals(-1L, l.at(LaunchLog.LOADED))
        assertEquals("start screen +0ms", LaunchLog(500).also { it.mark(LaunchLog.SCREEN, 400) }.line())
    }

    // ---- The link check begun at OK (EarlyOpen) ----

    @Test
    fun whatTheEarlyOpenAnsweredIsHandedToThePlayerInOrder() {
        val req = PlayRequest(SourceTarget(Fixtures.film), StartAt.Beginning, PlayOrigin.TITLE_PAGE)
        val session = FakeSession(req)
        val early = EarlyOpen(session, null, 123L)
        assertEquals(listOf(null to OpenWhy.START), session.opens)
        // The link is checked and ready before the player's screen exists.
        session.answer(Opened.Checking(Fixtures.source(1)))
        session.ready(Fixtures.source(1))
        val starts = ArrayList<String>()
        val h = object : SourcePlayback.Host by host {
            override fun startSource(ready: Opened.Ready, startMs: Long, switching: Boolean) { starts += ready.source.key }
        }
        val p = SourcePlayback(req, session, library, ticker, h, early)
        p.start()
        // No second open: the one begun at OK is the play's.
        assertEquals(1, session.opens.size)
        assertEquals(listOf("k1"), starts)
        assertEquals("k1", p.current?.key)
    }

    @Test
    fun anEarlyOpenStillCheckingGoesOnInThePlayerAndOneNobodyTakesEnds() {
        val req = PlayRequest(SourceTarget(Fixtures.film), StartAt.Beginning, PlayOrigin.TITLE_PAGE)
        val session = FakeSession(req)
        val early = EarlyOpen(session, null, 0L)
        val p = SourcePlayback(req, session, library, ticker, host, early)
        p.start()
        assertNull(p.current)
        session.ready(Fixtures.source(2))
        assertEquals("k2", p.current?.key)

        val orphanSession = FakeSession(req)
        val orphan = EarlyOpen(orphanSession, null, 0L)
        orphan.drop()
        assertTrue(orphanSession.closed)
        // Nothing reaches a player after that.
        var got = 0
        orphan.attach { got++ }
        orphanSession.ready(Fixtures.source(1))
        assertEquals(0, got)
    }
}
