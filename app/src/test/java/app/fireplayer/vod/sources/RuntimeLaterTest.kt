package app.fireplayer.vod.sources

import app.fireplayer.vod.Addon
import app.fireplayer.vod.AddonSave
import app.fireplayer.vod.AddonSettings
import app.fireplayer.vod.EpisodeRef
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
import app.fireplayer.vod.SourceList
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.StartAt
import app.fireplayer.vod.ThisTv
import app.fireplayer.vod.Title
import app.fireplayer.vod.TvCapabilities
import app.fireplayer.vod.TvPlan
import app.fireplayer.vod.sources.SourceFixtures.H1
import app.fireplayer.vod.sources.SourceFixtures.H2
import app.fireplayer.vod.sources.SourceFixtures.answer
import app.fireplayer.vod.sources.SourceFixtures.torrentio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * A film opened from Search first is looked up before its details are known (no runtime): the add-ons' sizes are then
 * ranked over an assumed 120 minutes. Once details bring its 198 minutes, the list held ranks again by them, from the
 * answers it has (nothing is asked again), and matches a look-up made with the details already known; a later visit
 * without a runtime keeps the one learnt. Until that rank is in, the list names no Best, so a play never starts the one
 * ranked by the old runtime.
 */
class RuntimeLaterTest {

    /** 198 minutes: 52 GB is about 38 Mb/s, 89.1 GB about 64. Over an assumed 120: about 62 and 106. */
    private val sparse = Title("tt0000077", Kind.MOVIE, "Long Film")
    private val rich = sparse.copy(runtime = "198 min")

    private val loop = FakeLoop()
    private val direct = Executor { it.run() }
    private var asked = 0
    /** Ranking runs off the main thread on the device: held here until [runWork], so a list can be seen mid-rank. */
    private val held = ArrayList<Runnable>()
    private var holdWork = false
    private val work = Executor { if (holdWork) held += it else it.run() }
    private val links = hashMapOf(Addon.TORRENTIO to SecretUrl.of(SourceFixtures.TIO)!!)
    private val plan = TvPlan(true, listOf(setOf(Hdr.HDR10, Hdr.HDR10_PLUS), setOf(Hdr.HLG)), 2160, emptySet())

    private fun engine() = DefaultSourcesRepository(
        addons = object : AddonSettings {
            override fun isSet(addon: Addon) = links.containsKey(addon)
            override fun summary(addon: Addon): String? = null
            override fun save(addon: Addon, link: String) = AddonSave.SAVED
            override fun remove(addon: Addon) = Unit
        },
        links = { links[it] },
        thisTv = object : ThisTv {
            override fun capabilities(): TvCapabilities? = null
            override fun checkAgain(onDone: (TvCapabilities) -> Unit) = Unit
            override fun plan() = plan
            override fun setPlan(plan: TvPlan) = Unit
            override fun useAutomatic() = Unit
        },
        library = object : PlaybackLibrary {
            override fun lastRelease(title: Title, episode: EpisodeRef?): LastRelease? = null
            override fun recordRelease(title: Title, episode: EpisodeRef?, release: LastRelease) = Unit
            override fun forgetReleases(title: Title) = Unit
            override fun forgetRelease(title: Title, episode: EpisodeRef?) = Unit
            override fun recordPlayback(title: Title, episode: EpisodeRef?, positionMs: Long, durationMs: Long, creditsAtMs: Long?, sceneAtMs: Long?) = Unit
        },
        http = StreamsHttp { _, _, _, _ ->
            asked++
            answer(
                torrentio(H1, size = "52 GB", quality = "4k HDR", name = "Long.Film.2025.2160p.BluRay.HDR.x265.DTS-HD.MA.5.1-GRP"),
                torrentio(H2, size = "89.1 GB", quality = "4k HDR", name = "Long.Film.2025.2160p.UHD.BluRay.HDR.HEVC.DTS-HD.MA.5.1-GRP"),
            )
        },
        linkCheck = LinkCheck(
            { url, _, _ ->
                val hash = Regex("[0-9a-f]{40}").find(url)?.value ?: error("no hash")
                LinkCheck.Answer(302, "https://cdn.invalid/d/$hash/f.mkv", null)
            },
            { true },
            { loop.now },
        ),
        budget = Budget(object : Budget.Store {
            var text: String? = null
            override fun read() = text
            override fun write(text: String) {
                this.text = text
            }
        }) { 1_000_000_000L },
        measured = { null },
        language = { "en" },
        main = loop,
        io = direct,
        work = work,
        elapsed = { loop.now },
        wall = { 1_000_000_000L },
        online = { true },
        // The default preferred bitrate, 60 Mb/s.
    )

    private fun runWork() {
        while (held.isNotEmpty()) held.removeAt(0).run()
        loop.advance()
    }

    private fun look(engine: DefaultSourcesRepository, target: SourceTarget): SourceList {
        var got: SourceList? = null
        val cancel = engine.lookUp(target, LookupWhy.SOURCES, null) { got = it }
        loop.advance()
        cancel.cancel()
        return got!!
    }

    private fun rates(list: SourceList) = list.sources.associate { it.release to it.facts.avgMbps }

    @Test
    fun detailsLaterRankTheHeldAnswersAsADetailsFirstLookUpWouldWithNoNewRequest() {
        val engine = engine()
        // Search first: no runtime. 52 GB over an assumed 120 min is about 58 Mb/s, nearest 60.
        val first = look(engine, SourceTarget(sparse))
        assertEquals(1, asked)
        assertEquals(ReleaseKey(H1, 0), first.best?.release)
        // Details bring 198 min: the title page hands its target over ([SourcesRepository.cached]); nothing is asked again.
        engine.cached(SourceTarget(rich))
        loop.advance()
        val later = engine.cached(SourceTarget(rich))!!
        assertEquals(1, asked)
        assertTrue(later.complete)
        assertEquals(ReleaseKey(H2, 0), later.best?.release)

        // A look-up made with the details already known (another engine): the same rates and the same Best.
        val other = engine()
        val known = look(other, SourceTarget(rich))
        assertEquals(rates(known), rates(later))
        assertEquals(known.best?.release, later.best?.release)
        // Each rate is the file's size over 198 minutes (the add-ons' GB read as GiB: about 64 and 38 Mb/s).
        for (row in later.sources) {
            assertEquals(row.facts.sizeBytes!! * 8.0 / (198 * 60) / 1_000_000.0, row.facts.avgMbps!!, 0.01)
        }

        // Back from Search, still without a runtime: the one learnt stays, nothing ranks again, nothing is asked.
        asked = 0
        val again = look(engine, SourceTarget(sparse))
        assertEquals(0, asked)
        assertEquals(ReleaseKey(H2, 0), again.best?.release)
        assertEquals(rates(later), rates(again))
    }

    @Test
    fun whileRankingByTheNewRuntimeTheListNamesNoBestAndAPlayWaitsForIt() {
        val engine = engine()
        look(engine, SourceTarget(sparse))
        holdWork = true
        // The details arrive as Play is pressed: the title page hands the runtime over.
        val mid = engine.cached(SourceTarget(rich))!!
        assertFalse(mid.complete)
        assertNull(mid.best)
        assertTrue(mid.sources.isEmpty())
        val session = engine.session(PlayRequest(SourceTarget(rich), StartAt.Beginning, PlayOrigin.TITLE_PAGE, list = mid))
        val opened = ArrayList<Opened>()
        session.open(null, OpenWhy.START) { opened += it }
        loop.advance()
        assertTrue(opened.none { it is Opened.Ready })
        // The rank by 198 min lands: the play starts the copy nearest 60 Mb/s at that length.
        holdWork = false
        runWork()
        val ready = opened.filterIsInstance<Opened.Ready>().single()
        assertEquals(ReleaseKey(H2, 0), ready.source.release)
        assertEquals(1, asked)
    }

    @Test
    fun aLookUpBringingTheRuntimeRanksTheHeldAnswersAgainWithoutAskingAgain() {
        val engine = engine()
        assertEquals(ReleaseKey(H1, 0), look(engine, SourceTarget(sparse)).best?.release)
        assertEquals(1, asked)
        // The Sources panel (or a play) looks the title up with its runtime, nothing handed over before.
        holdWork = true
        val got = ArrayList<SourceList>()
        val cancel = engine.lookUp(SourceTarget(rich), LookupWhy.SOURCES, null) { got += it }
        loop.advance()
        // Until the rank by 198 min is in: still looking, no Best.
        assertFalse(got.last().complete)
        assertNull(got.last().best)
        holdWork = false
        runWork()
        assertTrue(got.last().complete)
        assertEquals(ReleaseKey(H2, 0), got.last().best?.release)
        // The answers held were ranked again: the add-ons weren't asked.
        assertEquals(1, asked)
        cancel.cancel()
    }

    @Test
    fun aPlayHandedTheListRankedWithoutTheRuntimeWaitsForTheNewRankAndStartsItsBest() {
        val engine = engine()
        // The title page's list from Search: complete, its Best ranked over an assumed 120 minutes.
        val stale = look(engine, SourceTarget(sparse))
        assertTrue(stale.complete)
        assertEquals(ReleaseKey(H1, 0), stale.best?.release)
        holdWork = true
        // Play, its target with the runtime the details brought, handed that stale list.
        val session = engine.session(PlayRequest(SourceTarget(rich), StartAt.Beginning, PlayOrigin.TITLE_PAGE, list = stale))
        val opened = ArrayList<Opened>()
        session.open(null, OpenWhy.START) { opened += it }
        loop.advance()
        assertTrue(opened.none { it is Opened.Ready })
        holdWork = false
        runWork()
        assertEquals(ReleaseKey(H2, 0), opened.filterIsInstance<Opened.Ready>().single().source.release)
        assertEquals(1, asked)
    }

    @Test
    fun aRuntimeAlreadyKnownRanksNothingAgain() {
        val engine = engine()
        look(engine, SourceTarget(rich))
        holdWork = true
        // The same runtime again, or none: no rank, the list stays complete.
        assertTrue(engine.cached(SourceTarget(rich))!!.complete)
        assertTrue(engine.cached(SourceTarget(sparse))!!.complete)
        assertTrue(held.isEmpty())
        holdWork = false
    }
}
