package app.fireplayer.vod.play

import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.FailureKind
import app.fireplayer.vod.OpenWhy
import app.fireplayer.vod.Opened
import app.fireplayer.vod.PlayOrigin
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.Readiness
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
    }

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
}
