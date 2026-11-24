package app.fireplayer.vod.sources

import app.fireplayer.player.CopyHealth
import app.fireplayer.vod.Addon
import app.fireplayer.vod.AddonSave
import app.fireplayer.vod.AddonSettings
import app.fireplayer.vod.AddonState
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Failure
import app.fireplayer.vod.FailureKind
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.Kind
import app.fireplayer.vod.LastRelease
import app.fireplayer.vod.LookupWhy
import app.fireplayer.vod.Measured
import app.fireplayer.vod.OpenWhy
import app.fireplayer.vod.Opened
import app.fireplayer.vod.PlayOrigin
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.PlaybackLibrary
import app.fireplayer.vod.ReleaseKey
import app.fireplayer.vod.SecretUrl
import app.fireplayer.vod.SourceList
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.StartAt
import app.fireplayer.vod.Step
import app.fireplayer.vod.ThisTv
import app.fireplayer.vod.Title
import app.fireplayer.vod.TvCapabilities
import app.fireplayer.vod.TvPlan
import app.fireplayer.vod.sources.SourceFixtures.H1
import app.fireplayer.vod.sources.SourceFixtures.H2
import app.fireplayer.vod.sources.SourceFixtures.H3
import app.fireplayer.vod.sources.SourceFixtures.answer
import app.fireplayer.vod.sources.SourceFixtures.mediaFusion
import app.fireplayer.vod.sources.SourceFixtures.torrentio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executor

/** Virtual time on one thread: what the main Looper does on the device. */
class FakeLoop : MainLoop {
    var now = 0L
    private var seq = 0L
    private class Job(val at: Long, val seq: Long, val r: Runnable)
    private val jobs = ArrayList<Job>()

    override fun post(r: Runnable) {
        jobs += Job(now, seq++, r)
    }

    override fun postDelayed(r: Runnable, delayMs: Long) {
        jobs += Job(now + delayMs, seq++, r)
    }

    override fun remove(r: Runnable) {
        jobs.removeAll { it.r === r }
    }

    /** Runs everything due within [ms] from now, in order, then sets the clock to the end. */
    fun advance(ms: Long = 0) {
        val end = now + ms
        while (true) {
            val next = jobs.filter { it.at <= end }.minWithOrNull(compareBy<Job>({ it.at }, { it.seq })) ?: break
            jobs.remove(next)
            if (next.at > now) now = next.at
            next.r.run()
        }
        now = end
    }
}

class EngineTest {
    private val film = Title("tt0000001", Kind.MOVIE, "Film", runtime = "120 min")
    private val show = Title("tt0000002", Kind.SERIES, "Show", runtime = "45 min")
    private val filmTarget = SourceTarget(film)

    private val loop = FakeLoop()
    private val direct = Executor { it.run() }
    /** What each add-on answers next: JSON, or an exception to throw. */
    private val answers = HashMap<Addon, () -> String>()
    private val asked = ArrayList<Pair<Addon, String>>()
    /** Where each checked link goes (stage 2), by its info hash. */
    private val hops = HashMap<String, LinkCheck.Answer>()
    private val checked = ArrayList<String>()
    private val links = HashMap<Addon, SecretUrl>().apply {
        put(Addon.TORRENTIO, SecretUrl.of(SourceFixtures.TIO)!!)
        put(Addon.MEDIAFUSION, SecretUrl.of(SourceFixtures.MF)!!)
    }
    private var wall = 1_000_000_000L
    private val budgetStore = object : Budget.Store {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) {
            this.text = text
        }
    }
    private val recorded = ArrayList<LastRelease>()
    private var last: LastRelease? = null
    /** What this TV measured of each file played before, by its store key. */
    private val kept = HashMap<String, Measured>()

    private val library = object : PlaybackLibrary {
        override fun lastRelease(title: Title, episode: EpisodeRef?) = last
        override fun recordRelease(title: Title, episode: EpisodeRef?, release: LastRelease) {
            recorded += release
        }
        override fun recordPlayback(title: Title, episode: EpisodeRef?, positionMs: Long, durationMs: Long, creditsAtMs: Long?) = Unit
    }

    private val tv = object : ThisTv {
        override fun capabilities(): TvCapabilities? = null
        override fun checkAgain(onDone: (TvCapabilities) -> Unit) = Unit
        override fun plan() = TvPlan(true, listOf(setOf(Hdr.HDR10, Hdr.HDR10_PLUS), setOf(Hdr.HLG)), 2160, emptySet())
        override fun setPlan(plan: TvPlan) = Unit
        override fun useAutomatic() = Unit
    }

    private val settings = object : AddonSettings {
        override fun isSet(addon: Addon) = links.containsKey(addon)
        override fun summary(addon: Addon): String? = null
        override fun save(addon: Addon, link: String) = AddonSave.SAVED
        override fun remove(addon: Addon) {
            links.remove(addon)
        }
    }

    private val engine = DefaultSourcesRepository(
        addons = settings,
        links = { links[it] },
        thisTv = tv,
        library = library,
        http = StreamsHttp { base, target, _, _ ->
            val addon = if (base.host == "tio.invalid") Addon.TORRENTIO else Addon.MEDIAFUSION
            asked += addon to target.key
            answers[addon]?.invoke() ?: throw IOException("no answer set")
        },
        linkCheck = LinkCheck(
            { url, _, _ ->
                val hash = Regex("[0-9a-f]{40}").find(url)?.value ?: error("no hash")
                checked += hash
                hops[hash] ?: LinkCheck.Answer(302, "https://cdn.invalid/d/$hash/f.mkv", null)
            },
            { true },
            { loop.now },
        ),
        budget = Budget(budgetStore) { wall },
        measured = { kept[it] },
        language = { "en" },
        main = loop,
        io = direct,
        work = direct,
        elapsed = { loop.now },
        wall = { wall },
        online = { true },
    )

    private fun lists(target: SourceTarget = filmTarget, why: LookupWhy, only: Addon? = null): MutableList<SourceList> {
        val got = ArrayList<SourceList>()
        engine.lookUp(target, why, only) { got += it }
        return got
    }

    @Test
    fun aPageLookUpWaitsItsDwellAndCancellingSendsNothing() {
        answers[Addon.TORRENTIO] = { answer(torrentio(H1)) }
        answers[Addon.MEDIAFUSION] = { answer(mediaFusion(H2)) }
        val cancel = engine.lookUp(filmTarget, LookupWhy.PAGE, null) { }
        loop.advance(1_000)
        cancel.cancel()
        loop.advance(5_000)
        assertTrue(asked.isEmpty())
        val got = lists(why = LookupWhy.PAGE)
        loop.advance(1_499)
        assertTrue(asked.isEmpty())
        loop.advance(1)
        assertEquals(setOf(Addon.TORRENTIO, Addon.MEDIAFUSION), asked.map { it.first }.toSet())
        val final = got.last()
        assertTrue(final.complete)
        assertEquals(2, final.sources.size)
        assertEquals(AddonState.Answered(1), final.states[Addon.TORRENTIO])
    }

    @Test
    fun aSecondAskJoinsAndAKeptListIsReusedForTenMinutes() {
        answers[Addon.TORRENTIO] = { answer(torrentio(H1)) }
        answers[Addon.MEDIAFUSION] = { answer(mediaFusion(H2)) }
        lists(why = LookupWhy.PLAY)
        loop.advance()
        lists(why = LookupWhy.SOURCES)
        loop.advance()
        assertEquals(2, asked.size)
        assertTrue(engine.cached(filmTarget)!!.complete)
        loop.advance(DefaultSourcesRepository.KEEP_MS)
        assertNull(engine.cached(filmTarget))
        lists(why = LookupWhy.PLAY)
        loop.advance()
        assertEquals(4, asked.size)
    }

    @Test
    fun aFailedAddonCountsAsAnsweredForTwoMinutes() {
        answers[Addon.TORRENTIO] = { answer(torrentio(H1)) }
        answers[Addon.MEDIAFUSION] = { throw java.net.SocketTimeoutException() }
        val got = lists(why = LookupWhy.PLAY)
        loop.advance()
        val final = got.last()
        assertTrue(final.complete)
        assertTrue(final.states[Addon.MEDIAFUSION] is AddonState.Failed)
        assertEquals(1, final.sources.size)
        lists(why = LookupWhy.PLAY)
        loop.advance()
        assertEquals(1, asked.count { it.first == Addon.MEDIAFUSION })
        loop.advance(DefaultSourcesRepository.FAILED_FINAL_MS)
        answers[Addon.MEDIAFUSION] = { answer(mediaFusion(H2)) }
        val again = lists(why = LookupWhy.PLAY)
        loop.advance()
        assertEquals(2, asked.count { it.first == Addon.MEDIAFUSION })
        assertEquals(2, again.last().sources.size)
    }

    @Test
    fun theDeadlineMarksASlowAddonAndItsLateAnswerStillJoins() {
        answers[Addon.TORRENTIO] = { answer(torrentio(H1)) }
        answers[Addon.MEDIAFUSION] = { answer(mediaFusion(H2)) }
        // An io that holds MediaFusion's work until released.
        val held = ArrayList<Runnable>()
        val slowEngine = engineWith(Executor { r -> if (held.isEmpty() && asked.isNotEmpty()) held += r else r.run() })
        val got = ArrayList<SourceList>()
        slowEngine.lookUp(filmTarget, LookupWhy.PLAY, null) { got += it }
        loop.advance()
        assertEquals(1, held.size)
        assertTrue(got.last().states[Addon.MEDIAFUSION] is AddonState.Asking)
        assertFalse(got.last().complete)
        loop.advance(Addon.MEDIAFUSION.answerLimitMs)
        assertTrue(got.last().complete)
        assertTrue(got.last().states[Addon.MEDIAFUSION] is AddonState.Failed)
        held.removeAt(0).run()
        loop.advance()
        assertEquals(AddonState.Answered(1), got.last().states[Addon.MEDIAFUSION])
        assertEquals(2, got.last().sources.size)
    }

    private fun engineWith(io: Executor) = DefaultSourcesRepository(
        settings, { links[it] }, tv, library,
        StreamsHttp { base, target, _, _ ->
            val addon = if (base.host == "tio.invalid") Addon.TORRENTIO else Addon.MEDIAFUSION
            asked += addon to target.key
            answers[addon]!!.invoke()
        },
        LinkCheck({ _, _, _ -> LinkCheck.Answer(302, "https://cdn.invalid/d/x/f.mkv", null) }, { true }, { loop.now }),
        Budget(budgetStore) { wall }, { null }, { "en" }, loop, io, direct, { loop.now }, { wall }, { true },
    )

    @Test
    fun aNewerAutomaticLookUpCancelsTheOlderOneOnTheSameAddon() {
        answers[Addon.TORRENTIO] = { answer(torrentio(H1)) }
        answers[Addon.MEDIAFUSION] = { answer(mediaFusion(H2)) }
        val held = ArrayList<Runnable>()
        val e = engineWith(Executor { held += it })
        val first = ArrayList<SourceList>()
        e.lookUp(filmTarget, LookupWhy.PAGE_SOON, null) { first += it }
        loop.advance(500)
        assertEquals(2, held.size)
        e.lookUp(SourceTarget(Title("tt0000003", Kind.MOVIE, "Other")), LookupWhy.PAGE_SOON, null) { }
        loop.advance(500)
        // The first title's requests were cancelled: back to not asked, nothing kept.
        assertEquals(AddonState.NotAsked, first.last().states[Addon.TORRENTIO])
        held.toList().forEach { it.run() }
        loop.advance()
        assertNull(e.cached(filmTarget)?.sources?.firstOrNull())
    }

    @Test
    fun anAddonNotSetIsFinalAndBusyIsSkipped() {
        links.remove(Addon.MEDIAFUSION)
        answers[Addon.TORRENTIO] = { answer(torrentio(H1)) }
        val got = lists(why = LookupWhy.PLAY)
        loop.advance()
        assertEquals(AddonState.NotSet, got.last().states[Addon.MEDIAFUSION])
        assertTrue(got.last().complete)
        links[Addon.MEDIAFUSION] = SecretUrl.of(SourceFixtures.MF)!!
        answers[Addon.MEDIAFUSION] = { answer(SourceFixtures.busyRow()) }
        val show1 = lists(SourceTarget(show, EpisodeRef(1, 1)), LookupWhy.PLAY)
        loop.advance()
        assertTrue(show1.last().states[Addon.MEDIAFUSION] is AddonState.Busy)
        val n = asked.count { it.first == Addon.MEDIAFUSION }
        lists(SourceTarget(show, EpisodeRef(1, 2)), LookupWhy.PLAY)
        loop.advance()
        assertEquals(n, asked.count { it.first == Addon.MEDIAFUSION })
    }

    @Test
    fun okAgainOnThePagePlaysTheBestSoFarWithoutWaitingForTheOtherAddon() {
        answers[Addon.TORRENTIO] = { answer(torrentio(H1)) }
        answers[Addon.MEDIAFUSION] = { answer(mediaFusion(H2)) }
        // MediaFusion's answer is held back: the page's list has Torrentio's source only.
        val held = ArrayList<Runnable>()
        var hold = true
        val slowEngine = engineWith(Executor { r -> if (hold && asked.isNotEmpty()) { held += r; hold = false } else r.run() })
        val got = ArrayList<SourceList>()
        slowEngine.lookUp(filmTarget, LookupWhy.PLAY, null) { got += it }
        loop.advance()
        val soFar = got.last()
        assertFalse(soFar.complete)
        assertEquals(ReleaseKey(H1, 0), soFar.best?.release)

        // OK again: the page hands over the list as it stands, and the player checks its best at once.
        val session = slowEngine.session(PlayRequest(filmTarget, StartAt.Beginning, PlayOrigin.TITLE_PAGE, list = soFar))
        val opened = ArrayList<Opened>()
        session.open(null, OpenWhy.START) { opened += it }
        loop.advance()
        val ready = opened.last() as Opened.Ready
        assertEquals(ReleaseKey(H1, 0), ready.source.release)
        assertEquals(1, held.size)
        session.started(ready.source, ready.offer)

        // The other add-on's answer still joins the list: Next source goes on to it.
        held.removeAt(0).run()
        loop.advance()
        assertEquals(2, session.list?.sources?.size)
        val next = ArrayList<Opened>()
        session.open(null, OpenWhy.USER) { next += it }
        loop.advance()
        assertEquals(ReleaseKey(H2, 0), (next.last() as Opened.Ready).source.release)
    }

    @Test
    fun theBestSoFarFailingWaitsForTheAddonStillLooking() {
        answers[Addon.TORRENTIO] = { answer(torrentio(H1)) }
        answers[Addon.MEDIAFUSION] = { answer(mediaFusion(H2)) }
        val held = ArrayList<Runnable>()
        var hold = true
        val slowEngine = engineWith(Executor { r -> if (hold && asked.isNotEmpty()) { held += r; hold = false } else r.run() })
        val got = ArrayList<SourceList>()
        slowEngine.lookUp(filmTarget, LookupWhy.PLAY, null) { got += it }
        loop.advance()
        val session = slowEngine.session(PlayRequest(filmTarget, StartAt.Beginning, PlayOrigin.TITLE_PAGE, list = got.last()))
        val opened = ArrayList<Opened>()
        session.open(null, OpenWhy.START) { opened += it }
        loop.advance()
        val first = opened.last() as Opened.Ready
        // Its file turns out dead before it plays: nothing else is ready yet, so the other add-on is waited for.
        assertEquals(Step.TryNext, session.failed(first.source, Failure(FailureKind.FILE, Addon.TORRENTIO)))
        val after = ArrayList<Opened>()
        session.open(null, OpenWhy.FAILED) { after += it }
        loop.advance()
        assertTrue(after.last() is Opened.Looking)
        held.removeAt(0).run()
        loop.advance()
        assertEquals(ReleaseKey(H2, 0), (after.last() as Opened.Ready).source.release)
    }

    // ---- Sessions ----

    private fun open(session: app.fireplayer.vod.SourceSession, why: OpenWhy = OpenWhy.START): MutableList<Opened> {
        val got = ArrayList<Opened>()
        session.open(null, why) { got += it }
        loop.advance()
        return got
    }

    @Test
    fun playWaitsForBothAddonsThenChecksTheBestAndRecordsTheRelease() {
        answers[Addon.TORRENTIO] = { answer(torrentio(H1, quality = "1080p", name = "Film.1080p.WEB-DL.x264-A", size = "8 GB")) }
        answers[Addon.MEDIAFUSION] = { answer(mediaFusion(H2)) }
        val session = engine.session(PlayRequest(filmTarget, StartAt.Beginning, PlayOrigin.TITLE_PAGE))
        val got = open(session)
        val ready = got.last() as Opened.Ready
        assertEquals(ReleaseKey(H2, 0), ready.source.release)
        assertEquals(0L, ready.startMs)
        assertEquals("cdn.invalid", ready.link.host)
        assertTrue(got.any { it is Opened.Checking })
        session.started(ready.source, ready.offer)
        assertEquals(Addon.MEDIAFUSION, recorded.single().addon)
        assertEquals(ReleaseKey(H2, 0), recorded.single().release)
    }

    @Test
    fun aFileFailureSkipsToTheNextAndCountsStartTries() {
        answers[Addon.TORRENTIO] = { answer(torrentio(H1), torrentio(H2, name = "Film.2160p.WEB-DL.HDR.x265-B", size = "20 GB"), torrentio(H3, name = "Film.2160p.WEB-DL.HDR.x265-C", size = "18 GB")) }
        links.remove(Addon.MEDIAFUSION)
        hops[H1] = LinkCheck.Answer(302, "https://tio.invalid/videos/failed_rar_v3.mp4", null)
        hops[H2] = LinkCheck.Answer(302, "https://tio.invalid/videos/download_failed_v3.mp4", null)
        hops[H3] = LinkCheck.Answer(302, "https://tio.invalid/videos/failed_opening_v3.mp4", null)
        val session = engine.session(PlayRequest(filmTarget, StartAt.Beginning, PlayOrigin.TITLE_PAGE))
        val got = open(session)
        assertEquals(3, checked.size)
        assertEquals(3, session.attempts.size)
        assertTrue(session.attempts.all { it.failure?.kind == FailureKind.FILE })
        assertTrue(got.last() is Opened.NoneLeft)
    }

    @Test
    fun notReadyIsNeverPickedButAskedOnceAndFetchSaysItIsBeingFetched() {
        answers[Addon.TORRENTIO] = { answer(torrentio(H1, ready = false, seeders = 10), torrentio(H2, ready = false, name = "Film.1080p.WEB-DL.x264-Z", seeders = 90)) }
        links.remove(Addon.MEDIAFUSION)
        hops[H2] = LinkCheck.Answer(302, "https://tio.invalid/videos/downloading_v3.mp4", null)
        val session = engine.session(PlayRequest(filmTarget, StartAt.Beginning, PlayOrigin.TITLE_PAGE))
        val ask = open(session).last() as Opened.AskFetch
        assertTrue(checked.isEmpty())
        // The most seeded.
        assertEquals(ReleaseKey(H2, 0), ask.source.release)
        val fetched = ArrayList<Opened>()
        session.fetch(ask.source) { fetched += it }
        loop.advance()
        val stopped = fetched.last() as Opened.Stopped
        assertEquals(FailureKind.NOT_READY, stopped.failure.kind)
        assertEquals(FailureWords.FETCHING, stopped.message)
        // Asked once per title: a second play says nothing is left.
        val again = engine.session(PlayRequest(filmTarget, StartAt.Beginning, PlayOrigin.TITLE_PAGE))
        assertTrue(open(again).last() is Opened.NoneLeft)
    }

    @Test
    fun aContinueCardAsksOnlyItsAddonAndPlaysTheSameFile() {
        answers[Addon.TORRENTIO] = { answer(torrentio(H1)) }
        answers[Addon.MEDIAFUSION] = { answer(mediaFusion(H2), mediaFusion(H3, resolution = "1080p")) }
        val preferred = LastRelease(Addon.MEDIAFUSION, ReleaseKey(H3, 0), 1L)
        val session = engine.session(
            PlayRequest(filmTarget, StartAt.At(600_000), PlayOrigin.CONTINUE_CARD, preferred = preferred),
        )
        val ready = open(session).last() as Opened.Ready
        assertEquals(listOf(Addon.MEDIAFUSION), asked.map { it.first })
        assertEquals(ReleaseKey(H3, 0), ready.source.release)
        assertEquals(600_000L, ready.startMs)
        assertFalse(ready.resumedNear)
    }

    @Test
    fun aContinueCardWhoseFileIsGoneSearchesBothAndResumesNear() {
        answers[Addon.TORRENTIO] = { answer(torrentio(H1)) }
        answers[Addon.MEDIAFUSION] = { answer(mediaFusion(H2)) }
        val preferred = LastRelease(Addon.MEDIAFUSION, ReleaseKey(H3, 0), 1L)
        val session = engine.session(
            PlayRequest(filmTarget, StartAt.At(600_000), PlayOrigin.CONTINUE_CARD, preferred = preferred),
        )
        val ready = open(session).last() as Opened.Ready
        assertEquals(setOf(Addon.MEDIAFUSION, Addon.TORRENTIO), asked.map { it.first }.toSet())
        assertTrue(ready.resumedNear)
        assertEquals(595_000L, ready.startMs)
    }

    @Test
    fun aCopyWithNoPictureIsLeftForTheNextAndNotPickedByItselfAgain() {
        answers[Addon.TORRENTIO] = { answer(torrentio(H1), torrentio(H2, quality = "1080p", name = "Film.1080p.WEB-DL.x264-B")) }
        links.remove(Addon.MEDIAFUSION)
        val session = engine.session(PlayRequest(filmTarget, StartAt.Beginning, PlayOrigin.TITLE_PAGE))
        val first = open(session).last() as Opened.Ready
        assertEquals(ReleaseKey(H1, 0), first.source.release)
        session.started(first.source, first.offer)
        // This copy only: the next one down the list, whatever its format.
        assertEquals(Step.TryNext, session.failed(first.source, Failure(FailureKind.NO_PICTURE, Addon.TORRENTIO)))
        assertEquals(ReleaseKey(H2, 0), (open(session, OpenWhy.FAILED).last() as Opened.Ready).source.release)
    }

    @Test
    fun aCopyThatShowedNoPictureHereBeforeIsOnlyPlayedWhenPickedByHand() {
        kept[ReleaseKey(H1, 0).storeKey] = Measured(null, CopyHealth(watchedMs = 8_000, noPicture = 1), null)
        answers[Addon.TORRENTIO] = { answer(torrentio(H1), torrentio(H2, quality = "1080p", name = "Film.1080p.WEB-DL.x264-B")) }
        links.remove(Addon.MEDIAFUSION)
        val session = engine.session(PlayRequest(filmTarget, StartAt.Beginning, PlayOrigin.TITLE_PAGE))
        assertEquals(ReleaseKey(H2, 0), (open(session).last() as Opened.Ready).source.release)
        // Picked in Sources, it plays.
        val row = session.list!!.sources.first { it.release == ReleaseKey(H1, 0) }
        val byHand = engine.session(PlayRequest(filmTarget, StartAt.Beginning, PlayOrigin.SOURCES_PANEL, chosen = row))
        assertEquals(ReleaseKey(H1, 0), (open(byHand).last() as Opened.Ready).source.release)
    }

    @Test
    fun theFailureTableSteps() {
        answers[Addon.TORRENTIO] = { answer(torrentio(H1), torrentio(H2, name = "Film.1080p.WEB-DL.x264-B")) }
        links.remove(Addon.MEDIAFUSION)
        val session = engine.session(PlayRequest(filmTarget, StartAt.Beginning, PlayOrigin.TITLE_PAGE))
        val ready = open(session).last() as Opened.Ready
        val s = ready.source
        assertEquals(Step.DecodeOnDevice, session.failed(s, session.failureOf(5001, null)))
        assertEquals(Step.Refresh, session.failed(s, session.failureOf(2004, 403)))
        assertEquals(Step.TryNext, session.failed(s, session.failureOf(2004, 403)))
        assertEquals(Step.Retry(DefaultSourceSession.RATE_WAIT_MS), session.failed(s, session.failureOf(2004, 429)))
        assertTrue(session.failed(s, session.failureOf(2004, 429)) is Step.Stop)
        assertTrue(session.failed(s, app.fireplayer.vod.Failure(FailureKind.ACCOUNT)) is Step.Stop)
        assertEquals(Step.TryNext, session.failed(s, session.failureOf(4003, null)))
    }

    @Test
    fun switchesAreLimitedAndSpaced() {
        answers[Addon.TORRENTIO] = {
            answer(torrentio(H1), torrentio(H2, name = "Film.2160p.WEB-DL.HDR.x265-B"), torrentio(H3, name = "Film.2160p.WEB-DL.HDR.x265-C"),
                torrentio("6".repeat(40), name = "Film.1080p.WEB-DL.x264-D"))
        }
        links.remove(Addon.MEDIAFUSION)
        val session = engine.session(PlayRequest(filmTarget, StartAt.Beginning, PlayOrigin.TITLE_PAGE))
        var ready = open(session).last() as Opened.Ready
        assertFalse(session.maySwitch(loop.now))
        session.started(ready.source, ready.offer)
        assertTrue(session.maySwitch(loop.now))
        ready = open(session, OpenWhy.STALLS).last() as Opened.Ready
        assertNull(ready.startMs)
        assertTrue(ready.resumedNear)
        session.started(ready.source, ready.offer)
        assertFalse(session.maySwitch(loop.now + 60_000))
        loop.advance(DefaultSourceSession.SWITCH_GAP_MS)
        assertTrue(session.maySwitch(loop.now))
        ready = open(session, OpenWhy.STALLS).last() as Opened.Ready
        session.started(ready.source, ready.offer)
        loop.advance(DefaultSourceSession.SWITCH_GAP_MS)
        assertFalse(session.maySwitch(loop.now))
        // Next source by the user is always allowed.
        assertTrue(open(session, OpenWhy.USER).last() is Opened.Ready)
    }

    @Test
    fun aFailureWhileTheOtherAddonsSourcesAreRankedDoesntMakeAnEmptyCompleteList() {
        answers[Addon.TORRENTIO] = { answer(torrentio(H1), torrentio(H2, name = "Film.1080p.WEB-DL.x264-B")) }
        answers[Addon.MEDIAFUSION] = { throw IOException("down") }
        val got = lists(why = LookupWhy.PLAY)
        loop.advance()
        assertTrue(got.none { it.complete && it.sources.isEmpty() })
        assertTrue(got.last().complete)
        assertEquals(2, got.last().sources.size)
    }

    @Test
    fun aReadyRowsOfferNotOnRealDebridIsNeverOpenedByItself() {
        // The same file: ready on Torrentio, "⏳" on MediaFusion. Torrentio is down: MediaFusion's link isn't tried.
        answers[Addon.TORRENTIO] = { answer(torrentio(H1)) }
        answers[Addon.MEDIAFUSION] = { answer(mediaFusion(H1, ready = false)) }
        hops[H1] = LinkCheck.Answer(502, null, null)
        val session = engine.session(PlayRequest(filmTarget, StartAt.Beginning, PlayOrigin.TITLE_PAGE))
        val got = open(session)
        assertEquals(listOf(H1), checked)
        assertFalse(got.last() is Opened.Ready)
    }

    @Test
    fun aMergedRowRecordsTheFileThatPlayedAndResumesOnIt() {
        // Two uploads of one release (same name and size): the row is H1's, but only H2 is on Real-Debrid.
        answers[Addon.TORRENTIO] = { answer(torrentio(H1, ready = false), torrentio(H2)) }
        links.remove(Addon.MEDIAFUSION)
        val session = engine.session(PlayRequest(filmTarget, StartAt.Beginning, PlayOrigin.TITLE_PAGE))
        val ready = open(session).last() as Opened.Ready
        assertEquals(ReleaseKey(H1, 0), ready.source.release)
        assertEquals(ReleaseKey(H2, 0), ready.offer.release)
        assertEquals(listOf(H2), checked)
        session.started(ready.source, ready.offer)
        assertEquals(ReleaseKey(H2, 0), recorded.single().release)
        // Continue: the same file, from where it was.
        val again = engine.session(
            PlayRequest(filmTarget, StartAt.At(600_000), PlayOrigin.CONTINUE_CARD, preferred = recorded.single()),
        )
        val resumed = open(again).last() as Opened.Ready
        assertEquals(ReleaseKey(H2, 0), resumed.offer.release)
        assertEquals(600_000L, resumed.startMs)
        assertFalse(resumed.resumedNear)
    }

    @Test
    fun aResumeWhoseFileLeftRealDebridPlaysTheClosestReadyOneWithoutAsking() {
        answers[Addon.TORRENTIO] = { answer(torrentio(H1, ready = false), torrentio(H2, quality = "1080p", name = "Film.1080p.WEB-DL.x264-B", size = "8 GB")) }
        links.remove(Addon.MEDIAFUSION)
        val preferred = LastRelease(Addon.TORRENTIO, ReleaseKey(H1, 0), 1L)
        val session = engine.session(
            PlayRequest(filmTarget, StartAt.At(600_000), PlayOrigin.CONTINUE_CARD, preferred = preferred),
        )
        val ready = open(session).last() as Opened.Ready
        assertEquals(ReleaseKey(H2, 0), ready.source.release)
        assertTrue(ready.resumedNear)
        assertEquals(595_000L, ready.startMs)
    }

    @Test
    fun anExpiredLinkIsAskedForAgainWithoutUsingASwitch() {
        answers[Addon.TORRENTIO] = { answer(torrentio(H1), torrentio(H2, name = "Film.1080p.WEB-DL.x264-B")) }
        links.remove(Addon.MEDIAFUSION)
        val session = engine.session(PlayRequest(filmTarget, StartAt.Beginning, PlayOrigin.TITLE_PAGE))
        val ready = open(session).last() as Opened.Ready
        session.started(ready.source, ready.offer)
        assertEquals(Step.Refresh, session.failed(ready.source, session.failureOf(2004, 403)))
        val asks = asked.size
        val got = ArrayList<Opened>()
        session.refresh(ready.source, Addon.TORRENTIO) { got += it }
        loop.advance()
        assertEquals(asks + 1, asked.size)
        assertEquals(ready.source.key, (got.last() as Opened.Ready).source.key)
        assertTrue(session.maySwitch(loop.now))
    }

    @Test
    fun noAddonsSaysWhere() {
        links.clear()
        val session = engine.session(PlayRequest(filmTarget, StartAt.Beginning, PlayOrigin.TITLE_PAGE))
        assertEquals(FailureWords.NO_ADDONS, (open(session).last() as Opened.NoneLeft).message)
    }

    @Test
    fun theNextEpisodePrefersTheSameKind() {
        val ep = SourceTarget(show, EpisodeRef(1, 2))
        answers[Addon.TORRENTIO] = {
            answer(
                torrentio(H1, name = "Show.S01E02.2160p.WEB-DL.HDR.x265-BIG", bingeGroup = "torrentio|4k|BIG"),
                torrentio(H2, quality = "1080p", name = "Show.S01E02.1080p.WEB-DL.x264-NTb", size = "1.5 GB", bingeGroup = "torrentio|1080p|NTb"),
            )
        }
        links.remove(Addon.MEDIAFUSION)
        val prevList = ArrayList<SourceList>()
        engine.lookUp(ep, LookupWhy.NEXT_EPISODE, null) { prevList += it }
        loop.advance()
        val like = prevList.last().sources.first { it.facts.height == 1080 }
        val session = engine.session(PlayRequest(ep, StartAt.Beginning, PlayOrigin.NEXT_EPISODE, like = like))
        val ready = open(session).last() as Opened.Ready
        assertEquals(ReleaseKey(H2, 0), ready.source.release)
    }
}
