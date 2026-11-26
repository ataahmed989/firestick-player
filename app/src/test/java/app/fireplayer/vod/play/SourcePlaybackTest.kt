package app.fireplayer.vod.play

import app.fireplayer.vod.Addon
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.FailureKind
import app.fireplayer.vod.LastRelease
import app.fireplayer.vod.OpenWhy
import app.fireplayer.vod.Opened
import app.fireplayer.vod.PlayOrigin
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.ReleaseKey
import app.fireplayer.vod.SourceGroup
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.StartAt
import app.fireplayer.vod.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePlaybackTest {
    private val ticker = FakeTicker()
    private val library = FakeLibrary()
    private val log = ArrayList<String>()
    private var questions = ArrayList<Pair<String, List<Pair<String, () -> Unit>>>>()
    private val dismisses = ArrayList<() -> Unit>()
    private var position = 0L
    private var nextPlay: PlayRequest? = null
    private var starts = ArrayList<Triple<String, Long, Boolean>>()

    private val host = object : SourcePlayback.Host {
        override fun showProgress(text: String) { log += "progress $text" }
        override fun startSource(ready: Opened.Ready, startMs: Long, switching: Boolean) {
            starts += Triple(ready.source.key, startMs, switching)
        }
        override fun ask(question: String, choices: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) { questions += question to choices; dismisses += onDismiss }
        override fun stopWith(message: String) { log += "stop $message" }
        override fun notice(text: String) { log += "notice $text" }
        override fun positionMs() = position
        override fun retryAfter(ms: Long) { log += "retry $ms" }
        override fun decodeOnDevice() { log += "decode" }
        override fun playNext(next: PlayRequest) { nextPlay = next }
        override fun notWhole() { log += "not whole" }
        override fun runtimeMinutes() = runtime
        override fun whenBuffering() = mode
    }

    /** Settings › Playback › When a film keeps stalling, as the test sets it (Ask first unless changed). */
    private var mode = WhenBuffering.ASK

    /** The film's runtime as the title's details give it (0: the title's own). */
    private var runtime = 0

    private fun play(
        start: StartAt = StartAt.At(600_000),
        target: SourceTarget = SourceTarget(Fixtures.film),
        origin: PlayOrigin = PlayOrigin.TITLE_PAGE,
        chosen: app.fireplayer.vod.PlaybackSource? = null,
        next: EpisodeRef? = null,
    ): Pair<SourcePlayback, FakeSession> {
        val request = PlayRequest(target, start, origin, chosen = chosen, next = next)
        val session = FakeSession(request)
        val p = SourcePlayback(request, session, library, ticker, host)
        p.start()
        return p to session
    }

    @Test
    fun theFirstSourceStartsWhereTheRequestSaysWithoutAQuestion() {
        val (p, s) = play()
        assertEquals(listOf(null to OpenWhy.START), s.opens)
        s.answer(Opened.Checking(Fixtures.source(1)))
        assertTrue(log.last().startsWith("progress Checking the link · 4K"))
        s.ready(Fixtures.source(1))
        assertEquals(listOf(Triple("k1", 600_000L, false)), starts)
        assertEquals("k1", p.current?.key)
        assertTrue(questions.isEmpty())
    }

    @Test
    fun aResumeOnAnotherCopySaysTheOneLeftIsntAvailable() {
        val request = PlayRequest(SourceTarget(Fixtures.film), StartAt.At(600_000), PlayOrigin.CONTINUE_CARD,
            preferred = LastRelease(Addon.TORRENTIO, ReleaseKey(Fixtures.hash(9), 0), 1L))
        val session = FakeSession(request)
        session.list = Fixtures.list(request.target, listOf(Fixtures.source(1)))
        SourcePlayback(request, session, library, ticker, host).start()
        session.ready(Fixtures.source(1), startMs = 595_000, resumedNear = true)
        assertEquals(Triple("k1", 595_000L, false), starts.single())
        assertEquals("notice ${SourcePlayback.COPY_GONE_NOTE}", log.last())
    }

    @Test
    fun aResumeThatDidntTakeTheCopyItLeftLogsWhyOnce() {
        val logged = ArrayList<String>()
        val logging = object : SourcePlayback.Host by host {
            override fun log(text: String) { logged += text }
        }
        val request = PlayRequest(SourceTarget(Fixtures.film), StartAt.At(600_000), PlayOrigin.TITLE_PAGE,
            preferred = LastRelease(Addon.TORRENTIO, ReleaseKey(Fixtures.hash(9), 0), 1L))
        val session = FakeSession(request)
        session.resumeMissWords = "not cached any more"
        SourcePlayback(request, session, library, ticker, logging).start()
        session.ready(Fixtures.source(1), startMs = 595_000, resumedNear = true)
        assertEquals(listOf("resume: not the copy left (not cached any more)"), logged.filter { it.startsWith("resume") })
        // Another copy before the first frame (the first failed): not said again.
        session.ready(Fixtures.source(2), startMs = 595_000, resumedNear = true)
        assertEquals(1, logged.count { it.startsWith("resume") })
        // A resume that took its copy says nothing.
        val quiet = FakeSession(request)
        val heard = ArrayList<String>()
        SourcePlayback(request, quiet, library, ticker, object : SourcePlayback.Host by host {
            override fun log(text: String) { heard += text }
        }).start()
        quiet.ready(Fixtures.source(1), startMs = 600_000)
        assertTrue(heard.none { it.startsWith("resume") })
    }

    @Test
    fun aResumeOfACopyOfNoReleaseKnownSaysWhetherItsStillOfferedByItsKey() {
        val key = "x|mediafusion|0123456789abcdef|tt0133093"
        val request = PlayRequest(SourceTarget(Fixtures.film), StartAt.At(600_000), PlayOrigin.CONTINUE_CARD,
            preferred = LastRelease.ofCopy(Addon.MEDIAFUSION, key, 1L))
        // In no row of the full list (MediaFusion's other copy has another key): the one left isn't available.
        val other = Fixtures.source(2, Addon.MEDIAFUSION).let { s ->
            s.copy(release = null, offers = s.offers.map { it.copy(copyKey = "x|mediafusion|fedcba9876543210|tt0133093") })
        }
        val gone = FakeSession(request)
        gone.list = Fixtures.list(request.target, listOf(Fixtures.source(1), other))
        SourcePlayback(request, gone, library, ticker, host).start()
        gone.ready(Fixtures.source(1), startMs = 595_000, resumedNear = true)
        assertEquals("notice ${SourcePlayback.COPY_GONE_NOTE}", log.last())
        // Listed (late, after the pick; in a row it joined, beside another add-on's file of its size): only "Resumed near".
        val joined = Fixtures.source(3, also = Addon.MEDIAFUSION).let { s ->
            s.copy(offers = s.offers.map { if (it.addon == Addon.MEDIAFUSION) it.copy(copyKey = key) else it })
        }
        val listed = FakeSession(request)
        listed.list = Fixtures.list(request.target, listOf(Fixtures.source(1), joined))
        SourcePlayback(request, listed, library, ticker, host).start()
        listed.ready(Fixtures.source(1), startMs = 595_000, resumedNear = true)
        assertEquals("notice Resumed near ${PlayRules.clock(595_000)}", log.last())
    }

    @Test
    fun aResumeOfACopyOfNoReleaseKnownWhoseFileAnotherAddonOffersIsNotSaidToBeGone() {
        val key = "x|mediafusion|0123456789abcdef|tt0133093"
        val request = PlayRequest(SourceTarget(Fixtures.film), StartAt.At(600_000), PlayOrigin.CONTINUE_CARD,
            preferred = LastRelease.ofCopy(Addon.MEDIAFUSION, key, 1L))
        // MediaFusion's offer isn't listed, but the session knows Torz's row as that file (its size to the byte): listed
        // late, after the pick, it's only "Resumed near"; offered only not cached, the copy isn't available.
        val torz = Fixtures.source(3, Addon.TORZ)
        for ((readiness, note) in listOf(
            Readiness.PROBABLY_READY to "notice Resumed near ${PlayRules.clock(595_000)}",
            Readiness.NOT_READY to "notice ${SourcePlayback.COPY_GONE_NOTE}",
        )) {
            val row = torz.copy(offers = torz.offers.map { it.copy(readiness = readiness) })
            val session = FakeSession(request)
            session.opensLeftBy = { s, o -> s.key == row.key && o.addon == Addon.TORZ }
            session.list = Fixtures.list(request.target, listOf(Fixtures.source(1), row))
            SourcePlayback(request, session, library, ticker, host).start()
            session.ready(Fixtures.source(1), startMs = 595_000, resumedNear = true)
            assertEquals(note, log.last())
        }
    }

    @Test
    fun aResumeOnAnotherCopyWhileTheOneLeftIsListedIsOnlyResumedNear() {
        // The copy left was listed after the pick (a late answer): it isn't said to be unavailable.
        val request = PlayRequest(SourceTarget(Fixtures.film), StartAt.At(600_000), PlayOrigin.TITLE_PAGE,
            preferred = LastRelease(Addon.MEDIAFUSION, ReleaseKey(Fixtures.hash(2), 0), 1L))
        val session = FakeSession(request)
        session.list = Fixtures.list(request.target, listOf(Fixtures.source(1), Fixtures.source(2, Addon.MEDIAFUSION)))
        SourcePlayback(request, session, library, ticker, host).start()
        session.ready(Fixtures.source(1), startMs = 595_000, resumedNear = true)
        assertEquals("notice Resumed near ${PlayRules.clock(595_000)}", log.last())
    }

    @Test
    fun stageThreeAsksAtTheLimitForItsDataRate() {
        val (p, s) = play()
        s.ready(Fixtures.source(1, mbps = 10.0))
        ticker.advance(29_000)
        assertTrue(questions.isEmpty())
        ticker.advance(1_000)
        assertEquals("This source is slow to start.", questions.single().first)
        // Keep waiting: asked again after the same limit.
        questions.single().second.first { it.first == "Keep waiting" }.second()
        ticker.advance(30_000)
        assertEquals(2, questions.size)
        // Try next source: a failure (slow), and the next source is opened.
        questions.last().second.first { it.first == "Try next source" }.second()
        assertEquals(FailureKind.SLOW, s.failures.single().kind)
        assertEquals(null to OpenWhy.FAILED, s.opens.last())
        assertFalse(p.playing)
    }

    @Test
    fun offScreenTheLimitWaitsAndAStoppedPlayIsntAskedAbout() {
        val (p, s) = play()
        s.ready(Fixtures.source(1, mbps = 10.0))
        p.pause()
        ticker.advance(60_000)
        assertTrue(questions.isEmpty())
        p.resume()
        ticker.advance(30_000)
        assertEquals(1, questions.size)
        s.step = Step.Stop("Stopped")
        p.failed(2004, 500)
        p.pause()
        p.resume()
        ticker.advance(120_000)
        assertEquals(1, questions.size)
        PlayOutcomes.clear(SourceTarget(Fixtures.film))
    }

    @Test
    fun theFirstFrameEndsStageThreeAndRecordsTheRelease() {
        val (p, s) = play()
        s.ready(Fixtures.source(1, mbps = 60.0))
        ticker.advance(59_000)
        p.firstFrame()
        ticker.advance(10_000)
        assertTrue(questions.isEmpty())
        assertEquals(listOf("k1"), s.started.map { it.key })
        assertEquals(listOf("jumped", "playing"), s.stallEvents)
        p.firstFrame()
        assertEquals(1, s.started.size)
    }

    @Test
    fun stallsSwitchWhenAllowedThenOnlyNoteNextSource() {
        // Switch to the next copy: switched without asking, within the session's limit.
        mode = WhenBuffering.LIGHTER
        val (p, s) = play()
        s.ready(Fixtures.source(1))
        p.firstFrame()
        position = 3_600_000
        s.switchNow = true
        p.buffering(true)
        assertEquals(null to OpenWhy.STALLS, s.opens.last())
        s.ready(Fixtures.source(2))
        // Switched in place, 5 s before where it was.
        assertEquals(Triple("k2", 3_595_000L, true), starts.last())
        assertEquals("notice Resumed near 59:55", log.last())
        p.firstFrame()
        s.switchAllowed = false
        p.buffering(true)
        p.buffering(false)
        assertEquals(1, log.count { it == "notice ${SourcePlayback.LIMIT_NOTE}" })
        assertEquals(2, s.opens.size)
    }

    @Test
    fun aStallThatNeverEndsIsJudgedWhileItLasts() {
        mode = WhenBuffering.LIGHTER
        val (p, s) = play()
        s.ready(Fixtures.source(1))
        p.firstFrame()
        p.buffering(true)
        assertEquals(1, s.opens.size)
        p.tick()
        assertEquals(1, s.opens.size)
        // The stall goes on past the watch's limit: the tick switches without waiting for it to end.
        s.switchNow = true
        p.tick()
        assertEquals(null to OpenWhy.STALLS, s.opens.last())
        // While that switch is opened, the ticks wait for it.
        p.tick()
        assertEquals(2, s.opens.size)
        // Nothing to switch to: not tried again at once.
        s.answer(Opened.NoneLeft("No other source left"))
        p.tick()
        assertEquals(2, s.opens.size)
        ticker.advance(SourcePlayback.STALL_RETRY_MS)
        p.tick()
        assertEquals(3, s.opens.size)
        // Not stalled: ticks don't judge.
        s.answer(Opened.NoneLeft("No other source left"))
        p.buffering(false)
        ticker.advance(SourcePlayback.STALL_RETRY_MS)
        val opens = s.opens.size
        p.tick()
        assertEquals(opens, s.opens.size)
    }

    // ---- When a film keeps stalling: Ask first (the default) never changes copy without asking

    /** A play whose copy [first] showed its first frame at 1:00:00 and has stalled past the watch's limit. */
    private fun stalledOn(
        first: app.fireplayer.vod.PlaybackSource = Fixtures.source(1),
        chosen: app.fireplayer.vod.PlaybackSource? = null,
    ): Pair<SourcePlayback, FakeSession> {
        val (p, s) = play(chosen = chosen)
        s.ready(first)
        p.firstFrame()
        position = 3_600_000
        s.switchNow = true
        p.buffering(true)
        return p to s
    }

    private fun answer(label: String) = questions.last().second.first { it.first == label }.second()

    @Test
    fun askFirstAsksBeforeLeavingACopyFirePlayerPicked() {
        val (p, s) = stalledOn()
        assertEquals(FilmSupply.PLAIN_QUESTION, questions.single().first)
        assertEquals(listOf(FilmSupply.TRY_NEXT, FilmSupply.KEEP), questions.single().second.map { it.first })
        // Not switched: the only open is the start's.
        assertEquals(listOf(null to OpenWhy.START), s.opens)
        // While the question is up, the stall watch waits.
        p.tick()
        p.buffering(false)
        p.buffering(true)
        assertEquals(1, questions.size)
        assertEquals(1, s.opens.size)
    }

    @Test
    fun askFirstAsksAboutACopyPickedByHandAsBefore() {
        val mine = Fixtures.source(1)
        val (_, s) = stalledOn(mine, chosen = mine)
        assertEquals(FilmSupply.PLAIN_QUESTION, questions.single().first)
        assertEquals(listOf(mine to OpenWhy.START), s.opens)
    }

    @Test
    fun tryNextSourceSwitchesAndTheCopyItPlaysIsAskedAboutInItsTurn() {
        val (p, s) = stalledOn()
        answer(FilmSupply.TRY_NEXT)
        // At the current position, as Next source in Picture & sound.
        assertEquals(null to OpenWhy.USER, s.opens.last())
        s.ready(Fixtures.source(2))
        assertEquals(Triple("k2", PlayRules.switchStart(3_600_000), true), starts.last())
        // The copy it picked stalls too: asked about that one as well, not switched.
        p.firstFrame()
        p.buffering(true)
        assertEquals(2, questions.size)
        assertEquals(FilmSupply.PLAIN_QUESTION, questions.last().first)
        assertEquals(2, s.opens.size)
    }

    @Test
    fun keepThisSourceMeansNoSwitchLaterForThatCopy() {
        val (p, s) = stalledOn()
        answer(FilmSupply.KEEP)
        p.buffering(false)
        p.buffering(true)
        p.tick()
        ticker.advance(SourcePlayback.STALL_RETRY_MS)
        p.tick()
        assertEquals(1, questions.size)
        assertEquals(1, s.opens.size)
        // Back on the question is the same: it plays on, and isn't asked about again.
        val (q, t) = stalledOn()
        val back: () -> Unit = dismisses[dismisses.size - 1]
        back()
        q.buffering(false)
        q.buffering(true)
        q.tick()
        assertEquals(2, questions.size)
        assertEquals(1, t.opens.size)
    }

    @Test
    fun tryNextSourceAboutACopyLeftSinceLeavesTheOnePlayingNow() {
        val (p, s) = stalledOn()
        // While the question is up, the copy fails and the next one plays.
        p.failed(2004, 404)
        assertEquals(null to OpenWhy.FAILED, s.opens.last())
        s.ready(Fixtures.source(2))
        answer(FilmSupply.TRY_NEXT)
        // The question was about k1: k2 isn't left for it.
        assertEquals(2, s.opens.size)
        assertEquals("k2", p.current?.key)
        // Answered: k2's own stalls are asked about in their turn.
        p.firstFrame()
        p.buffering(true)
        assertEquals(2, questions.size)
        assertEquals(2, s.opens.size)
    }

    @Test
    fun switchToTheNextCopySwitchesWithoutAskingWhoeverPickedIt() {
        mode = WhenBuffering.LIGHTER
        val (_, s) = stalledOn()
        assertTrue(questions.isEmpty())
        assertEquals(null to OpenWhy.STALLS, s.opens.last())
        s.ready(Fixtures.source(2))
        assertEquals("notice Resumed near 59:55", log.last())
        val mine = Fixtures.source(3)
        val (_, t) = stalledOn(mine, chosen = mine)
        assertTrue(questions.isEmpty())
        assertEquals(null to OpenWhy.STALLS, t.opens.last())
    }

    @Test
    fun keepTryingNeitherAsksNorSwitches() {
        mode = WhenBuffering.KEEP
        val (p, s) = stalledOn()
        p.tick()
        p.buffering(false)
        p.buffering(true)
        assertTrue(questions.isEmpty())
        assertEquals(1, s.opens.size)
    }

    @Test
    fun underAskFirstACopyThatCantPlayIsStillLeftWithoutAsking() {
        // No picture: the next copy, with its note.
        val (p, s) = play()
        s.ready(Fixtures.source(1))
        p.firstFrame()
        p.noPicture()
        assertEquals(null to OpenWhy.FAILED, s.opens.last())
        s.ready(Fixtures.source(2))
        assertEquals("notice " + SourcePlayback.NO_PICTURE_NOTE, log.last())
        // Not the whole film: the same.
        val (q, t) = play(target = avatar)
        t.ready(Fixtures.source(1))
        assertTrue(q.lengthKnown(makingOfMs))
        assertEquals(null to OpenWhy.FAILED, t.opens.last())
        t.ready(Fixtures.source(2))
        assertEquals("notice " + SourcePlayback.NOT_WHOLE_NOTE, log.last())
        // A failure the player's retries didn't get past: the failure table's next source.
        val (r, u) = play()
        u.ready(Fixtures.source(1))
        r.firstFrame()
        r.failed(2004, 404)
        assertEquals(null to OpenWhy.FAILED, u.opens.last())
        assertTrue(questions.isEmpty())
        // The copy a fallback plays is a copy like any: its stalls are asked about.
        u.ready(Fixtures.source(2))
        r.firstFrame()
        u.switchNow = true
        r.buffering(true)
        assertEquals(FilmSupply.PLAIN_QUESTION, questions.single().first)
        assertEquals(2, u.opens.size)
    }

    @Test
    fun aResumeCountsAsAJumpForTheStallWatch() {
        val (p, s) = play()
        s.ready(Fixtures.source(1))
        p.firstFrame()
        s.stallEvents.clear()
        p.pause()
        p.resume()
        assertEquals(listOf("jumped"), s.stallEvents)
    }

    @Test
    fun backOnTheQuestionsIsNotNowOrKeepWaiting() {
        val (_, s) = play()
        val nr = Fixtures.source(9, readiness = Readiness.NOT_READY, group = SourceGroup.NOT_ON_RD)
        s.answer(Opened.AskFetch(nr))
        val back: () -> Unit = dismisses[dismisses.size - 1]
        back()
        assertEquals("stop ${SourcePlayback.NOT_FETCHED}", log.last())
        PlayOutcomes.clear(SourceTarget(Fixtures.film))

        val (_, s2) = play()
        s2.ready(Fixtures.source(1, mbps = 10.0))
        ticker.advance(30_000)
        assertEquals("This source is slow to start.", questions.last().first)
        val asked = questions.size
        val back2: () -> Unit = dismisses[dismisses.size - 1]
        back2()
        ticker.advance(30_000)
        assertEquals(asked + 1, questions.size)
    }

    @Test
    fun aStallWhileTheSkipPreviewLoadsIsExcusedFromTheStallWatch() {
        val (p, s) = play()
        s.ready(Fixtures.source(1))
        p.firstFrame()
        s.stallEvents.clear()
        p.buffering(true, excused = true)
        p.buffering(false)
        p.buffering(true)
        assertEquals(listOf("buffering true", "excused", "buffering false", "playing", "buffering true"), s.stallEvents)
    }

    @Test
    fun stallsBeforeTheFirstFrameAreStageThrees() {
        val (p, s) = play()
        s.ready(Fixtures.source(1))
        s.switchNow = true
        p.buffering(true)
        assertEquals(1, s.opens.size)
        assertTrue(s.stallEvents.isEmpty())
    }

    @Test
    fun failuresFollowTheirStep() {
        val (p, s) = play()
        s.ready(Fixtures.source(1))
        s.step = Step.Retry(30_000)
        p.failed(2004, 503)
        assertEquals("retry 30000", log.last())
        s.step = Step.DecodeOnDevice
        p.failed(5001, null)
        assertEquals("decode", log.last())
        s.step = Step.Refresh
        val opens = s.opens.size
        p.failed(2004, 403)
        // The session refreshes the same file's link (its add-on asked again), not a switch.
        assertEquals(listOf("k1"), s.refreshed.map { it.key })
        assertEquals(opens, s.opens.size)
        s.step = Step.Stop("Real-Debrid says your account has expired.")
        p.failed(2004, 403)
        assertEquals("stop Real-Debrid says your account has expired.", log.last())
        assertEquals("Real-Debrid says your account has expired.", PlayOutcomes.take(SourceTarget(Fixtures.film)))
        assertNull(PlayOutcomes.take(SourceTarget(Fixtures.film)))
    }

    @Test
    fun aCopyWithNoPictureGivesWayToTheNextWhichSaysWhy() {
        val (p, s) = play()
        s.ready(Fixtures.source(1))
        p.firstFrame()
        position = 1_200_000
        p.noPicture()
        // A failure of this copy, whatever the buffering setting says: the next one down the list.
        assertEquals(FailureKind.NO_PICTURE, s.failures.single().kind)
        assertEquals(null to OpenWhy.FAILED, s.opens.last())
        assertFalse(p.playing)
        assertTrue(questions.isEmpty())
        s.ready(Fixtures.source(2))
        assertEquals(Triple("k2", PlayRules.switchStart(1_200_000), true), starts.last())
        // Said as it starts, in place of "Resumed near".
        assertEquals("notice " + SourcePlayback.NO_PICTURE_NOTE, log.last())
        assertEquals("This copy showed no picture here · playing the next one", SourcePlayback.NO_PICTURE_NOTE)
        // Said once: the next switch is as before.
        p.firstFrame()
        p.nextSource()
        s.ready(Fixtures.source(3))
        assertTrue(log.last().startsWith("notice Resumed near"))
    }

    @Test
    fun aCopyWithNoPictureFromItsStartGivesWayBeforeTheSlowStartQuestion() {
        val (p, s) = play()
        s.ready(Fixtures.source(1, mbps = 10.0))
        // Its sound played, no frame came: never "first frame".
        ticker.advance(5_000)
        p.noPicture()
        s.ready(Fixtures.source(2, mbps = 10.0))
        // Where the play was asked to start: the copy before never played.
        assertEquals(600_000L, starts.last().second)
        assertEquals("notice " + SourcePlayback.NO_PICTURE_NOTE, log.last())
        // The first copy's slow-start limit is gone with it.
        ticker.advance(29_000)
        assertTrue(questions.isEmpty())
    }

    @Test
    fun aCopyWithNoPictureAndNoSwitchLeftStopsAndSaysSo() {
        val (p, s) = play()
        s.ready(Fixtures.source(1))
        p.firstFrame()
        p.noPicture()
        s.answer(Opened.NoneLeft("Switched source twice already. Use Next source in Picture & sound to try another."))
        assertEquals("stop Switched source twice already. Use Next source in Picture & sound to try another.", log.last())
        assertFalse(log.any { it == "notice " + SourcePlayback.NO_PICTURE_NOTE })
    }

    @Test
    fun nothingLeftWhilePlayingIsANoteNotAStop() {
        val (p, s) = play()
        s.ready(Fixtures.source(1))
        p.firstFrame()
        p.nextSource()
        assertEquals(null to OpenWhy.USER, s.opens.last())
        s.answer(Opened.NoneLeft("No other source left"))
        assertEquals("notice No other source left", log.last())
        assertTrue(p.playing)
    }

    @Test
    fun notOnRealDebridAsksOnceThenFetches() {
        val (_, s) = play()
        val nr = Fixtures.source(9, readiness = Readiness.NOT_READY, group = SourceGroup.NOT_ON_RD)
        s.answer(Opened.AskFetch(nr))
        val q = questions.single()
        assertTrue(q.first.startsWith("None of the ready sources played"))
        q.second.first { it.first == "Ask your debrid service to fetch it" }.second()
        assertEquals(listOf(nr), s.fetched)
        // "Not now" stops with a pointer to Sources.
        s.answer(Opened.AskFetch(nr))
        questions.last().second.first { it.first == "Not now" }.second()
        assertEquals("stop ${SourcePlayback.NOT_FETCHED}", log.last())
        PlayOutcomes.clear(SourceTarget(Fixtures.film))
    }

    @Test
    fun aRowNotOnRealDebridChosenInSourcesIsFetchedWithoutAskingAgain() {
        val nr = Fixtures.source(9, readiness = Readiness.NOT_READY, group = SourceGroup.NOT_ON_RD)
        val (_, s) = play(origin = PlayOrigin.SOURCES_PANEL, chosen = nr)
        assertEquals(listOf(nr), s.fetched)
        assertTrue(s.opens.isEmpty())
    }

    @Test
    fun theLibraryHearsOnlyAfterSomethingPlayed() {
        val (p, s) = play()
        p.record(1_000, 7_200_000, null)
        assertTrue(library.recorded.isEmpty())
        s.ready(Fixtures.source(1))
        p.firstFrame()
        p.record(6_600_000, 7_200_000, 6_500_000)
        assertEquals(listOf<Any?>(Fixtures.film.id, null, 6_600_000L, 7_200_000L, 6_500_000L), library.recorded.single())
    }

    @Test
    fun theNextEpisodeIsLookedUpOnceNearTheEndAndPlaysFromItsStart() {
        val ep = SourceTarget(Fixtures.show, EpisodeRef(1, 1))
        val (p, s) = play(start = StartAt.Beginning, target = ep, next = EpisodeRef(1, 2))
        s.ready(Fixtures.source(1))
        p.firstFrame()
        p.nearEnd(1_000_000, 2_700_000)
        assertTrue(s.nextAsked.isEmpty())
        p.nearEnd(2_500_000, 2_700_000)
        p.nearEnd(2_600_000, 2_700_000)
        assertEquals(listOf(EpisodeRef(1, 2)), s.nextAsked)
        assertFalse(p.ended(playNext = false))
        assertTrue(p.ended(playNext = true))
        val n = nextPlay!!
        assertEquals(EpisodeRef(1, 2), n.target.episode)
        assertEquals(StartAt.Beginning, n.start)
        assertEquals(PlayOrigin.NEXT_EPISODE, n.origin)
        assertEquals("k1", n.like?.key)
    }

    @Test
    fun theNextEpisodeFollowsTheOfferThatPlayedNotEveryAddonOfItsRow() {
        val ep = SourceTarget(Fixtures.show, EpisodeRef(1, 1))
        val (p, s) = play(start = StartAt.Beginning, target = ep, next = EpisodeRef(1, 2))
        // One file from two add-ons; it plays from the second add-on's offer.
        val both = Fixtures.source(1, also = Addon.MEDIAFUSION)
        val mf = both.offers.single { it.addon == Addon.MEDIAFUSION }
        s.answer(Opened.Ready(both, mf, mf.link, null, false))
        p.firstFrame()
        assertTrue(p.ended(playNext = true))
        val like = nextPlay!!.like!!
        assertEquals("k1", like.key)
        assertEquals(listOf(mf), like.offers)
        assertEquals(both.facts, like.facts)
        // The Episodes sheet's play hands on the same.
        assertEquals(listOf(mf), p.episodeRequest(EpisodeRef(1, 3), StartAt.Beginning).like?.offers)
        // A row of one offer goes on as it is.
        val (p2, s2) = play(start = StartAt.Beginning, target = ep, next = EpisodeRef(1, 2))
        s2.ready(Fixtures.source(2))
        p2.firstFrame()
        assertEquals(Fixtures.source(2), p2.nextRequest(EpisodeRef(1, 2)).like)
    }

    @Test
    fun aFilmHasNoNextAndClosingStopsEverything() {
        val (p, s) = play()
        assertFalse(p.ended(playNext = true))
        p.close()
        assertTrue(s.closed)
        s.ready(Fixtures.source(1))
        assertTrue(starts.isEmpty())
    }

    @Test
    fun aRowHeldForPlayFromBeginningStartsAtZero() {
        val (p, s) = play()
        s.ready(Fixtures.source(1))
        p.firstFrame()
        position = 1_000_000
        p.pick(Fixtures.source(2), beginning = true)
        s.ready(Fixtures.source(2))
        assertEquals(Triple("k2", 0L, true), starts.last())
    }

    // ---- A copy that ends without really playing is not the film's end ---------------------------------------------

    /** Avatar: Fire and Ash, about 198 min; its copy that is really a 5,708 s video. */
    private val avatar = SourceTarget(Fixtures.film.copy(runtime = "198 min"))
    private val makingOfMs = 5_708_000L

    @Test
    fun aCopyStartedPastItsEndGivesWayAtTheFilmsPlaceAndMarksNothingWatched() {
        // The film's runtime unknown (the title's 136 min: the copy isn't under 60 % of it): only its end gives it away.
        val (p, s) = play(start = StartAt.At(9_000_000))
        s.ready(Fixtures.source(1))
        p.firstFrame()
        ticker.advance(1_284_000)
        p.record(10_284_000, 11_880_000, null)
        assertEquals(1, library.recorded.size)
        // Next source at 2:51:24: the next copy starts 5 s earlier, past its own end, and ends 1.3 s later.
        position = 10_284_000
        p.nextSource()
        s.ready(Fixtures.source(2))
        assertEquals(Triple("k2", 10_279_000L, true), starts.last())
        p.firstFrame()
        ticker.advance(1_300)
        position = makingOfMs
        // Leaving now keeps nothing of it either (its "end" would mark the film watched).
        p.record(makingOfMs, makingOfMs, null)
        assertEquals(1, library.recorded.size)
        assertFalse(p.reachedEnd(makingOfMs))
        assertEquals(FailureKind.NOT_WHOLE, s.failures.single().kind)
        assertEquals(null to OpenWhy.FAILED, s.opens.last())
        assertTrue(log.contains("not whole"))
        assertFalse(p.playing)
        p.record(makingOfMs, makingOfMs, null)
        assertEquals(1, library.recorded.size)
        // The next copy plays from where the film was, not from the broken copy's end, and says why.
        s.ready(Fixtures.source(3))
        assertEquals(Triple("k3", 10_279_000L, true), starts.last())
        assertEquals("notice " + SourcePlayback.NOT_WHOLE_NOTE, log.last())
        assertEquals("This copy isn't the whole film · playing the next one", SourcePlayback.NOT_WHOLE_NOTE)
        // It plays and is kept as usual.
        p.firstFrame()
        ticker.advance(60_000)
        p.record(10_339_000, 11_880_000, null)
        assertEquals(listOf<Any?>(Fixtures.film.id, null, 10_339_000L, 11_880_000L, null), library.recorded.last())
    }

    @Test
    fun aFilmsCopyFarShorterThanTheFilmPickedByItselfGivesWayAsSoonAsItsLengthIsKnown() {
        val (p, s) = play(start = StartAt.At(600_000), target = avatar)
        s.ready(Fixtures.source(1))
        // The first READY: 1:35:08 of a 198 min film. Not played to its end.
        assertTrue(p.lengthKnown(makingOfMs))
        assertEquals(FailureKind.NOT_WHOLE, s.failures.single().kind)
        assertTrue(log.contains("not whole"))
        s.ready(Fixtures.source(2))
        assertEquals(600_000L, starts.last().second)
        assertEquals("notice " + SourcePlayback.NOT_WHOLE_NOTE, log.last())
        // A whole copy's length changes nothing, and is judged once.
        assertFalse(p.lengthKnown(11_880_000))
        assertFalse(p.lengthKnown(makingOfMs))
        assertEquals(1, s.failures.size)
        // The title's details' runtime counts too.
        runtime = 198
        val (q, t) = play(start = StartAt.At(600_000))
        t.ready(Fixtures.source(4))
        assertTrue(q.lengthKnown(makingOfMs))
    }

    @Test
    fun aCopysLengthIsJudgedOnceTheFilmsRuntimeIsKnown() {
        // No runtime at the first READY (the title's details not in yet): judged at a later READY, once they are.
        val (p, s) = play(start = StartAt.At(600_000), target = SourceTarget(Fixtures.film.copy(runtime = null)))
        s.ready(Fixtures.source(1))
        assertFalse(p.lengthKnown(makingOfMs))
        assertTrue(s.failures.isEmpty())
        runtime = 198
        assertTrue(p.lengthKnown(makingOfMs))
        assertEquals(FailureKind.NOT_WHOLE, s.failures.single().kind)
    }

    @Test
    fun aShortCopyChosenByHandPlaysButItsEndNeverMarksTheFilmWatched() {
        val short = Fixtures.source(7)
        val (p, s) = play(start = StartAt.At(600_000), target = avatar, origin = PlayOrigin.SOURCES_PANEL, chosen = short)
        s.ready(short)
        assertFalse(p.lengthKnown(makingOfMs))
        assertTrue(s.failures.isEmpty())
        // Kept as such all the same: not picked by itself again, nor the copy the title goes on from.
        assertTrue(log.contains("not whole"))
        assertEquals(listOf(short.key), s.notWholes.map { it.key })
        p.firstFrame()
        ticker.advance(makingOfMs)
        // Nothing of where it stands is the film's: not mid-way, nor its credits.
        p.record(3_000_000, makingOfMs, null)
        p.record(5_600_000, makingOfMs, 5_500_000)
        assertTrue(library.recorded.isEmpty())
        // Its end isn't the film's: the next copy, from where the film was when it started.
        assertFalse(p.reachedEnd(makingOfMs))
        assertEquals(FailureKind.NOT_WHOLE, s.failures.single().kind)
        s.ready(Fixtures.source(8))
        assertEquals(600_000L, starts.last().second)
        // Picked in the player's Sources window, the same.
        val (q, t) = play(start = StartAt.At(600_000), target = avatar)
        t.ready(Fixtures.source(1))
        q.firstFrame()
        q.pick(short, beginning = false)
        t.ready(short)
        assertFalse(q.lengthKnown(makingOfMs))
    }

    @Test
    fun anEndTheViewerJumpedToRightAfterAResumeIsTheFilmsEnd() {
        // Resumed at 1:00:00, then held Right to the end within 10 s of the first frame (to clear it from Continue
        // Watching): the film's end, as before, and the copy isn't kept as anything.
        val (p, s) = play(start = StartAt.At(3_600_000), target = avatar)
        s.ready(Fixtures.source(1))
        assertFalse(p.lengthKnown(11_880_000))
        p.firstFrame()
        ticker.advance(4_000)
        p.jumped()
        ticker.advance(2_000)
        assertTrue(p.reachedEnd(11_880_000))
        p.record(11_880_000, 11_880_000, null)
        assertEquals(listOf<Any?>(Fixtures.film.id, null, 11_880_000L, 11_880_000L, null), library.recorded.single())
        assertTrue(s.failures.isEmpty())
        assertFalse(log.contains("not whole"))
    }

    @Test
    fun aShorterCutStartedPastItsEndGivesWayButIsntKeptAsNotWhole() {
        // The extended cut (3:00:00) plays at 2:45:00 of a film whose runtime is 150 min; Next source picks the
        // theatrical cut (2:30:00), which starts past its end and ends at once.
        val (p, s) = play(start = StartAt.At(9_000_000), target = SourceTarget(Fixtures.film.copy(runtime = "150 min")))
        s.ready(Fixtures.source(1))
        assertFalse(p.lengthKnown(10_800_000))
        p.firstFrame()
        ticker.advance(900_000)
        position = 9_900_000
        p.nextSource()
        s.ready(Fixtures.source(2))
        assertFalse(p.lengthKnown(9_000_000))
        p.firstFrame()
        ticker.advance(1_000)
        assertFalse(p.reachedEnd(9_000_000))
        // Passed over for this play (the session skips it), but not kept as "Shorter than the film here": from its start
        // it plays whole.
        assertEquals(FailureKind.NOT_WHOLE, s.failures.single().kind)
        assertFalse(log.contains("not whole"))
        s.ready(Fixtures.source(3))
        assertEquals(Triple("k3", 9_895_000L, true), starts.last())
        assertEquals("notice " + SourcePlayback.NOT_WHOLE_NOTE, log.last())
    }

    @Test
    fun aCopyThatGaveWayIsNeverStartedOrJudgedAgain() {
        val (p, s) = play(start = StartAt.At(9_000_000))
        s.ready(Fixtures.source(1))
        p.firstFrame()
        ticker.advance(1_284_000)
        position = 10_284_000
        p.nextSource()
        s.ready(Fixtures.source(2))
        // No frame: it ended at once (the Avatar case).
        assertFalse(p.reachedEnd(makingOfMs))
        assertEquals(1, s.failures.size)
        val opens = s.opens.size
        // Left/Right on it while the next link is checked: its picture starts nothing (it isn't the copy the title goes
        // on from again), a READY holds it again without judging it again, and its end is still not the film's.
        p.firstFrame()
        assertEquals(listOf("k1"), s.started.map { it.key })
        assertFalse(p.playing)
        runtime = 198
        assertTrue(p.lengthKnown(makingOfMs))
        assertFalse(p.reachedEnd(makingOfMs))
        // Nothing counted twice: one failure, one open for the next copy.
        assertEquals(1, s.failures.size)
        assertEquals(opens, s.opens.size)
        // The next copy plays from where the film was, and is judged as usual.
        s.ready(Fixtures.source(3))
        assertEquals(Triple("k3", 10_279_000L, true), starts.last())
        assertFalse(p.lengthKnown(11_880_000))
        p.firstFrame()
        assertEquals(listOf("k1", "k3"), s.started.map { it.key })
    }

    @Test
    fun aWholeCopyPlayedToItsEndIsTheFilmsEnd() {
        val (p, s) = play(start = StartAt.At(9_000_000), target = avatar)
        s.ready(Fixtures.source(1))
        assertFalse(p.lengthKnown(11_880_000))
        p.firstFrame()
        ticker.advance(2_880_000)
        assertTrue(p.reachedEnd(11_880_000))
        p.record(11_880_000, 11_880_000, null)
        assertEquals(listOf<Any?>(Fixtures.film.id, null, 11_880_000L, 11_880_000L, null), library.recorded.single())
        assertTrue(s.failures.isEmpty())
        assertFalse(log.contains("not whole"))
    }

    @Test
    fun aCopyThatIsntWholeWithNothingLeftStopsSaysSoAndKeepsTheResumePoint() {
        val (p, s) = play(start = StartAt.At(600_000))
        s.ready(Fixtures.source(1))
        p.firstFrame()
        ticker.advance(60_000)
        p.record(660_000, 7_200_000, null)
        position = 660_000
        p.nextSource()
        s.ready(Fixtures.source(2))
        // No frame at all: it ended at once.
        assertFalse(p.reachedEnd(2_000_000))
        s.answer(Opened.NoneLeft("Switched source twice already. Use Next source in Picture & sound to try another."))
        assertEquals("stop This copy isn't the whole film. Switched source twice already. Use Next source in Picture & sound to try another.", log.last())
        // Back from the message keeps nothing of it: the resume point stays.
        p.record(2_000_000, 2_000_000, null)
        assertEquals(1, library.recorded.size)
    }

    @Test
    fun anEpisodesCopyIsOnlyJudgedByWhetherItReallyPlayed() {
        val (p, s) = play(start = StartAt.At(1_500_000), target = SourceTarget(Fixtures.show, EpisodeRef(1, 2)), next = EpisodeRef(1, 3))
        s.ready(Fixtures.source(1))
        // A 20 min file for the show's 45 min: never judged by its length.
        assertFalse(p.lengthKnown(1_200_000))
        p.firstFrame()
        ticker.advance(800)
        assertFalse(p.reachedEnd(1_200_000))
        s.ready(Fixtures.source(2))
        assertEquals(1_500_000L, starts.last().second)
        assertEquals("notice This copy isn't the whole episode · playing the next one", log.last())
        // One that played through is the episode's end.
        p.firstFrame()
        ticker.advance(30 * 60_000L)
        assertTrue(p.reachedEnd(2_700_000))
    }
}
