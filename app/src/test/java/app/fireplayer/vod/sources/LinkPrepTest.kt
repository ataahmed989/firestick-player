package app.fireplayer.vod.sources

import app.fireplayer.vod.Addon
import app.fireplayer.vod.AddonSave
import app.fireplayer.vod.AddonSettings
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Failure
import app.fireplayer.vod.FailureKind
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.Kind
import app.fireplayer.vod.LastRelease
import app.fireplayer.vod.LookupWhy
import app.fireplayer.vod.OpenWhy
import app.fireplayer.vod.Opened
import app.fireplayer.vod.PlayOrigin
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.PlaybackLibrary
import app.fireplayer.vod.ReleaseKey
import app.fireplayer.vod.SecretUrl
import app.fireplayer.vod.SourceSession
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

/**
 * The link of the copy OK would play, checked ahead while the viewer rests on it ([LinkPrep]), with fakes: every add-on
 * answer and every link is made up (".invalid" hosts, "FAKEKEY"); the engine's network thread is a queue run by hand, so
 * a check can be caught still under way.
 */
class LinkPrepTest {
    private val film = Title("tt0000001", Kind.MOVIE, "Film", runtime = "120 min")
    private val other = Title("tt0000009", Kind.MOVIE, "Other film", runtime = "100 min")
    private val filmTarget = SourceTarget(film)

    private val loop = FakeLoop()
    private val io = ArrayList<Runnable>()
    private val answers = HashMap<Addon, () -> String>()
    private val asked = ArrayList<Pair<Addon, String>>()
    /** Each link check's info hash, in order: ahead or a play's. */
    private val checked = ArrayList<String>()
    /** What a link check answers next for a hash (once), else a redirect to the film's file. */
    private val hopOnce = HashMap<String, LinkCheck.Answer>()
    private val lines = ArrayList<String>()
    private var filmPlays = false
    private var last: LastRelease? = null
    private val links = HashMap<Addon, SecretUrl>().apply {
        put(Addon.TORRENTIO, SecretUrl.of(SourceFixtures.TIO)!!)
        put(Addon.MEDIAFUSION, SecretUrl.of(SourceFixtures.MF)!!)
    }
    private val budgetStore = object : Budget.Store {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) {
            this.text = text
        }
    }

    private val library = object : PlaybackLibrary {
        override fun lastRelease(title: Title, episode: EpisodeRef?) = last
        override fun recordRelease(title: Title, episode: EpisodeRef?, release: LastRelease) = Unit
        override fun forgetReleases(title: Title) = Unit
        override fun forgetRelease(title: Title, episode: EpisodeRef?) = Unit
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
            { url, _, cancel ->
                if (cancel.cancelled) throw IOException("cancelled")
                val hash = Regex("[0-9a-f]{40}").find(url)?.value ?: error("no hash")
                checked += hash
                hopOnce.remove(hash) ?: LinkCheck.Answer(302, "https://cdn.invalid/d/$hash/f.mkv", null)
            },
            { true },
            { loop.now },
        ),
        budget = Budget(budgetStore) { 1_000_000_000L + loop.now },
        measured = { null },
        language = { "en" },
        main = loop,
        io = Executor { io += it },
        work = Executor { it.run() },
        elapsed = { loop.now },
        wall = { 1_000_000_000L + loop.now },
        online = { true },
        log = { lines += it },
        filmPlays = { filmPlays },
    )

    /** Runs the network thread's queue and the main thread until both are quiet. */
    private fun settle() {
        loop.advance()
        while (io.isNotEmpty()) {
            val now = io.toList()
            io.clear()
            now.forEach { it.run() }
            loop.advance()
        }
    }

    /** The network thread's work queued now, once, then the main thread (later work stays queued). */
    private fun step() {
        val now = io.toList()
        io.clear()
        now.forEach { it.run() }
        loop.advance()
    }

    /** A Continue card's resume of MediaFusion's 1080p copy H3 (cached), OK pressed at [pressedAt]. */
    private fun continueCard(pressedAt: Long = 0L) = PlayRequest(
        filmTarget, StartAt.At(600_000), PlayOrigin.CONTINUE_CARD,
        preferred = LastRelease(Addon.MEDIAFUSION, ReleaseKey(H3, 0), 1L), pressedAt = pressedAt,
    )

    private fun answers() {
        answers[Addon.TORRENTIO] = { answer(torrentio(H1)) }
        answers[Addon.MEDIAFUSION] = { answer(mediaFusion(H2), mediaFusion(H3, resolution = "1080p")) }
    }

    private fun open(session: SourceSession): MutableList<Opened> {
        val got = ArrayList<Opened>()
        session.open(null, OpenWhy.START) { got += it }
        settle()
        return got
    }

    @Test
    fun restingOnAContinueCardChecksItsCopysLinkOnceAndOkStartsFromIt() {
        answers()
        engine.prepare(continueCard())
        settle()
        // Only the copy's own add-on, as an early look-up; that copy's link, checked once.
        assertEquals(listOf(Addon.MEDIAFUSION), asked.map { it.first })
        assertEquals(listOf(H3), checked)
        assertTrue(lines.any { it.startsWith("ahead: link ready in") })
        loop.advance(5_000)
        val session = engine.session(continueCard(pressedAt = loop.now))
        val got = open(session)
        val ready = got.last() as Opened.Ready
        assertEquals(ReleaseKey(H3, 0), ready.source.release)
        assertEquals("https://cdn.invalid/d/$H3/f.mkv", ready.link.reveal())
        assertEquals(600_000L, ready.startMs)
        // Not checked again: the play took the link checked ahead.
        assertEquals(listOf(H3), checked)
        assertEquals(listOf(Addon.MEDIAFUSION), asked.map { it.first })
        assertEquals("link checked ahead: ready 5000 ms before OK", session.aheadNote())
        // Taken once: a second play checks for itself.
        val again = engine.session(continueCard())
        assertTrue(open(again).last() is Opened.Ready)
        assertEquals(listOf(H3, H3), checked)
        assertNull(again.aheadNote())
        // Never a link in the log.
        assertTrue(lines.none { "invalid" in it || "FAKE" in it || H3 in it })
    }

    @Test
    fun okJoinsTheCheckStillUnderWayNeverSendingItTwice() {
        answers()
        engine.prepare(continueCard())
        // The add-on answered; the link's check is queued, not done.
        step()
        assertTrue(checked.isEmpty())
        loop.advance(1_200)
        val session = engine.session(continueCard(pressedAt = loop.now))
        val got = ArrayList<Opened>()
        session.open(null, OpenWhy.START) { got += it }
        loop.advance()
        assertTrue(got.none { it is Opened.Ready })
        settle()
        assertTrue(got.last() is Opened.Ready)
        assertEquals(listOf(H3), checked)
        assertEquals("link check ahead joined: begun 1200 ms before OK", session.aheadNote())
        // Focus moving on afterwards (the page goes as the player comes up) doesn't touch the play's link.
        assertFalse(engine.prep.busy)
    }

    @Test
    fun focusMovingOnStopsItAndANewerRestReplacesIt() {
        answers()
        val rest = engine.prepare(continueCard())
        step()
        rest.cancel()
        settle()
        // Stopped before it ran: nothing checked, nothing kept.
        assertTrue(checked.isEmpty())
        assertFalse(engine.prep.busy)
        // Kept, then another rest: the kept link goes, and a play of the first checks for itself.
        engine.prepare(continueCard())
        settle()
        assertEquals(listOf(H3), checked)
        // The other film: only Torrentio lists it (the film's answers stay kept).
        answers[Addon.MEDIAFUSION] = { throw IOException("down") }
        engine.prepare(PlayRequest(SourceTarget(other), StartAt.Beginning, PlayOrigin.CONTINUE_CARD))
        settle()
        assertEquals(listOf(H3, H1), checked)
        open(engine.session(continueCard()))
        assertEquals(listOf(H3, H1, H3), checked)
    }

    @Test
    fun aKeptLinkGoesAfterTwoMinutes() {
        answers()
        engine.prepare(continueCard())
        settle()
        loop.advance(LinkPrep.KEEP_MS)
        assertFalse(engine.prep.busy)
        assertTrue(open(engine.session(continueCard())).last() is Opened.Ready)
        assertEquals(listOf(H3, H3), checked)
    }

    @Test
    fun nothingIsCheckedAheadWhileAFilmPlays() {
        answers()
        filmPlays = true
        engine.prepare(continueCard())
        settle()
        assertTrue(asked.isEmpty())
        assertTrue(checked.isEmpty())
        assertFalse(engine.prep.busy)
    }

    @Test
    fun onlyACachedCopyNeverOneToFetchAndNothingAskedOrKeptForThePlay() {
        answers[Addon.TORRENTIO] = { answer(torrentio(H1, ready = false)) }
        answers[Addon.MEDIAFUSION] = { answer(mediaFusion(H2, ready = false)) }
        val fresh = PlayRequest(filmTarget, StartAt.Beginning, PlayOrigin.CONTINUE_CARD)
        engine.prepare(fresh)
        settle()
        assertTrue(checked.isEmpty())
        assertFalse(engine.prep.busy)
        // The play still asks whether the debrid service should fetch one: the pick ahead marked nothing.
        assertTrue(engine.fetchAsked.isEmpty())
        assertTrue(open(engine.session(fresh)).last() is Opened.AskFetch)
    }

    @Test
    fun theCopyLeftWhenItIsCachedElseTheRankingsPick() {
        // The copy left isn't cached any more: the ranking's pick (cached) is the one checked ahead, as the play picks it.
        answers[Addon.TORRENTIO] = { answer(torrentio(H1)) }
        answers[Addon.MEDIAFUSION] = { answer(mediaFusion(H2), mediaFusion(H3, ready = false, resolution = "1080p")) }
        engine.prepare(continueCard())
        settle()
        assertEquals(1, checked.size)
        val session = engine.session(continueCard())
        val ready = open(session).last() as Opened.Ready
        assertEquals(checked.single(), ready.source.release!!.infoHash)
        assertEquals(1, checked.size)
        assertTrue(ready.resumedNear)
    }

    @Test
    fun aPlayOfAnotherCopyOrTitleDropsIt() {
        answers()
        engine.prepare(continueCard())
        settle()
        // A fresh start of the same film plays the ranking's 4K pick, not the copy left: that one's link goes.
        val fresh = engine.session(PlayRequest(filmTarget, StartAt.Beginning, PlayOrigin.TITLE_PAGE))
        val ready = open(fresh).last() as Opened.Ready
        assertTrue(ready.source.release != ReleaseKey(H3, 0))
        assertEquals(2, checked.size)
        assertFalse(engine.prep.busy)
        assertNull(fresh.aheadNote())
        // Another title's play: dropped as it begins.
        engine.prepare(continueCard())
        settle()
        assertTrue(engine.prep.busy)
        engine.session(PlayRequest(SourceTarget(other), StartAt.Beginning, PlayOrigin.TITLE_PAGE))
        assertFalse(engine.prep.busy)
    }

    @Test
    fun aNetworkChangeDropsIt() {
        answers()
        engine.prepare(continueCard())
        settle()
        assertTrue(engine.prep.busy)
        engine.forget()
        assertFalse(engine.prep.busy)
    }

    @Test
    fun aCheckAheadThatFailedIsForgottenAndThePlayChecksItself() {
        answers()
        hopOnce[H3] = LinkCheck.Answer(503, null, null)
        engine.prepare(continueCard())
        settle()
        assertFalse(engine.prep.busy)
        val ready = open(engine.session(continueCard())).last() as Opened.Ready
        assertEquals(ReleaseKey(H3, 0), ready.source.release)
        assertEquals(listOf(H3, H3), checked)
    }

    @Test
    fun aKeptLinkThePlayerCantOpenIsCheckedAfreshOnceUncounted() {
        answers()
        engine.prepare(continueCard())
        settle()
        val session = engine.session(continueCard())
        val ready = open(session).last() as Opened.Ready
        // The player is refused (HTTP 403) before the first frame: the same copy is checked again, nothing counted.
        assertEquals(Step.Refresh, session.failed(ready.source, Failure(FailureKind.LINK_EXPIRED, Addon.MEDIAFUSION, "player error 2004 (HTTP 403)")))
        assertTrue(session.attempts.isEmpty())
        val got = ArrayList<Opened>()
        session.refresh(ready.source, Addon.MEDIAFUSION) { got += it }
        settle()
        val fresh = got.last() as Opened.Ready
        assertEquals(ReleaseKey(H3, 0), fresh.source.release)
        assertEquals(600_000L, fresh.startMs)
        // Checked afresh without asking the add-on again.
        assertEquals(listOf(H3, H3), checked)
        assertEquals(listOf(Addon.MEDIAFUSION), asked.map { it.first })
        assertTrue(session.aheadNote()!!.endsWith("refused by then: checked again"))
        // From here the table as ever: its one refresh still there, then the next copy.
        assertEquals(Step.Refresh, session.failed(fresh.source, Failure(FailureKind.LINK_EXPIRED, Addon.MEDIAFUSION, "HTTP 403")))
        assertEquals(1, session.attempts.size)
    }

    @Test
    fun aJoinedCheckIsAPlaysOwnAndFollowsTheTable() {
        answers()
        engine.prepare(continueCard())
        step()
        val session = engine.session(continueCard())
        val ready = open(session).last() as Opened.Ready
        session.failed(ready.source, Failure(FailureKind.LINK_EXPIRED, Addon.MEDIAFUSION, "HTTP 403"))
        assertEquals(1, session.attempts.size)
    }

    @Test
    fun aTitlePagesPlayUsesItsListAsItStandsAndAsksNothingMore() {
        answers()
        engine.lookUp(filmTarget, LookupWhy.PAGE_SOON, null) { }
        loop.advance(DefaultSourcesRepository.SOON_DWELL_MS)
        settle()
        assertEquals(2, asked.size)
        val list = engine.cached(filmTarget)!!
        assertTrue(list.complete)
        val request = PlayRequest(filmTarget, StartAt.Beginning, PlayOrigin.TITLE_PAGE, list = list)
        engine.prepare(request)
        settle()
        assertEquals(2, asked.size)
        assertEquals(listOf(list.best!!.release!!.infoHash), checked)
        val ready = open(engine.session(request)).last() as Opened.Ready
        assertEquals(list.best!!.key, ready.source.key)
        assertEquals(1, checked.size)
    }

    @Test
    fun aRestsLookUpIsAnEarlyOneInMediaFusionsBudget() {
        val budget = Budget(object : Budget.Store {
            override fun read(): String? = null
            override fun write(text: String) = Unit
        }) { 1_000L }
        repeat(Budget.MF_EARLY) { budget.asked(Addon.MEDIAFUSION, LookupWhy.AHEAD) }
        assertFalse(budget.mayAsk(Addon.MEDIAFUSION, LookupWhy.AHEAD))
        assertFalse(budget.mayAsk(Addon.MEDIAFUSION, LookupWhy.PAGE))
        assertTrue(budget.mayAsk(Addon.MEDIAFUSION, LookupWhy.PLAY))
    }
}
