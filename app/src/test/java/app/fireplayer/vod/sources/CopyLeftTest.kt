package app.fireplayer.vod.sources

import app.fireplayer.player.CopyHealth
import app.fireplayer.player.MediaSpec
import app.fireplayer.vod.Addon
import app.fireplayer.vod.AddonSave
import app.fireplayer.vod.AddonSettings
import app.fireplayer.vod.AddonState
import app.fireplayer.vod.Card
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.FakeVodRepository
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
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.ReleaseKey
import app.fireplayer.vod.SecretUrl
import app.fireplayer.vod.SoundFormat
import app.fireplayer.vod.SourceList
import app.fireplayer.vod.SourceSession
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.StartAt
import app.fireplayer.vod.Step
import app.fireplayer.vod.ThisTv
import app.fireplayer.vod.Title
import app.fireplayer.vod.TvCapabilities
import app.fireplayer.vod.TvPlan
import app.fireplayer.vod.data.MemoryLibraryStore
import app.fireplayer.vod.data.ReleaseRow
import app.fireplayer.vod.data.VodLibrary
import app.fireplayer.vod.play.PlayRules
import app.fireplayer.vod.play.VodLaunch
import app.fireplayer.vod.sources.SourceFixtures.answer
import app.fireplayer.vod.sources.SourceFixtures.torrentio
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executor

/**
 * The copy left when its add-on gave no info hash (MediaFusion's, at times: its [app.fireplayer.vod.Offer.release] null,
 * known by its [CopyKey]): kept in vod.db as the copy a film or episode was left on, so Resume, a Continue card and the
 * Episodes sheet play it again, in the same session and after a restart, and the title page's Last used says the same.
 * The TV case: Your Friendly Neighborhood Spider-Man S1 E9 on the Fire TV Max, MediaFusion's "4K · WEB · ≈2.9 GB (pack
 * 28.8 GB)" hand-picked in the title page's Sources window, played and measured (its row then one with Torz's file of
 * its size to the byte); Resume then played the 3.1 GB HDR copy kept the day before. Made-up names, ".invalid" links,
 * fake tokens; the library is vod.db's rows in memory ([MemoryLibraryStore]), kept across "restarts".
 */
class CopyLeftTest {

    private val show = Title("tt0000077", Kind.SERIES, "Your Friendly Neighborhood Spider-Man", runtime = "28 min")
    private val e9 = EpisodeRef(1, 9)
    private val e10 = EpisodeRef(1, 10)
    private val ep9 = SourceTarget(show, e9)
    private val ep10 = SourceTarget(show, e10)
    private val now = 1_764_100_000_000L
    private val day = 86_400_000L

    /** The Max on its TV: HDR10 (no Dolby Vision), DD and DD+ passed through. */
    private val plan = TvPlan(true, listOf(setOf(Hdr.HDR10, Hdr.HDR10_PLUS), setOf(Hdr.HLG)), 2160, setOf(SoundFormat.AC3, SoundFormat.EAC3),
        dv7AsHdr10 = true)

    private val packTotal = 28_800_000_000L
    /** E9's file in the pack, as Picture & sound measured it. */
    private val e9Bytes = 2_903_456_789L
    private val packName = "Your.Friendly.Neighborhood.Spider-Man.S01.2160p.WEB.H265-GRP"
    /** Torz's torrent of the same pack (E9's file to the byte as the play measured MediaFusion's). */
    private val hz = "c".repeat(40)
    /** The 3.1 GB HDR10 copy kept on Nov 25: ranked first on this TV. */
    private val hk = "e".repeat(40)
    private val hdrName = "Your.Friendly.Neighborhood.Spider-Man.S01E09.2160p.WEB.HDR10.H265-FLUX"

    /** MediaFusion's pack copy for [target], its link hiding the torrent (no info hash): a token of that episode's. */
    private fun mf(target: SourceTarget, file: String? = packName, ready: Boolean = true) = JSONObject().apply {
        put("name", "MediaFusion | ElfHosted 🧲 RD ${if (ready) "⚡️" else "⏳"} 4k")
        put("description", "📺 WEB 🎞️ hevc 🎵 Dolby Digital Plus 🔊 5.1\n📦 28.8 GB 👤 12\n🌐 English")
        put("url", "https://mf.invalid/streaming_provider/FAKECONFIG_MF/stream/FAKETOKEN_${target.episode?.episode}/video.mkv")
        put("behaviorHints", JSONObject().apply {
            put("bingeGroup", "MediaFusion-|-ElfHosted-📺 WEB 🎞️ hevc 🎵 Dolby Digital Plus-4K")
            file?.let { put("filename", it) }
            put("videoSize", packTotal)
        })
    }

    private fun torz(hash: String, file: String, videoSize: Long, idx: Int) = JSONObject().apply {
        put("name", "⚡️ [RD] Torz\n2160p")
        put("description", "💿 WEB 🎞️ HEVC\n🎧 DDP | 5.1\n💾 2.9 GB 👤 12\n📄 $file")
        put("url", "https://torz.invalid/stremio/torz/FAKECONFIG_TORZ/_/strem/tt0000077/rd/$hash/$idx/$file")
        put("behaviorHints", JSONObject().put("videoSize", videoSize).put("filename", file))
    }

    /** What each add-on answers for an episode (null: an error); [ep9]'s and [ep10]'s files are their own. */
    private val answerOf = HashMap<Addon, (SourceTarget) -> String?>().apply {
        put(Addon.TORRENTIO) { t ->
            val e = t.episode!!.episode
            answer(torrentio(hk, idx = e - 1, quality = "4k HDR", name = hdrName.replace("E09", "E%02d".format(e)), size = "3.1 GB"))
        }
        put(Addon.MEDIAFUSION) { t -> answer(mf(t)) }
        put(Addon.TORZ) { t ->
            val e = t.episode!!.episode
            answer(torz(hz, "Your.Friendly.Neighborhood.Spider-Man.S01E%02d.2160p.WEB.H265-GRP.mkv".format(e), e9Bytes + (e - 9) * 7_000_000L, e - 1))
        }
    }

    // ---- The TV: the library's rows and what plays measured outlive a restart; the engine doesn't ----

    private val store = MemoryLibraryStore()
    private val direct = Executor { it.run() }
    /** "quality_seen": what plays measured, by release or copy key. */
    private val kept = HashMap<String, Measured>()
    private var library = RecordedLibrary(VodLibrary(store, { now }, direct) { })
    private var loop = FakeLoop()
    private val asked = ArrayList<Addon>()
    /** The add-on look-ups held unanswered (as an add-on that doesn't answer), by how many are still to hold. */
    private var holdLookups = 0
    private val held = ArrayList<Runnable>()
    private val links = HashMap<Addon, SecretUrl>().apply {
        put(Addon.TORRENTIO, SecretUrl.of(SourceFixtures.TIO)!!)
        put(Addon.MEDIAFUSION, SecretUrl.of(SourceFixtures.MF)!!)
        put(Addon.TORZ, SecretUrl.of("https://torz.invalid/FAKECONFIG_TORZ")!!)
    }
    private var engine = engine()

    private fun engine(): DefaultSourcesRepository {
        val main = loop
        return DefaultSourcesRepository(
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
            library = library,
            http = StreamsHttp { base, target, _, _ ->
                val addon = when (base.host) {
                    "tio.invalid" -> Addon.TORRENTIO
                    "mf.invalid" -> Addon.MEDIAFUSION
                    else -> Addon.TORZ
                }
                asked += addon
                answerOf[addon]?.invoke(target) ?: throw IOException("no answer")
            },
            // The film's link names the episode a MediaFusion token was for ("d/10"), nothing else of where it came from.
            linkCheck = LinkCheck({ url, _, _ ->
                LinkCheck.Answer(302, "https://cdn.invalid/d/${url.substringAfter("FAKETOKEN_", "x").substringBefore('/')}/f.mkv", null)
            }, { true }, { main.now }),
            budget = Budget(object : Budget.Store {
                var text: String? = null
                override fun read() = text
                override fun write(text: String) {
                    this.text = text
                }
            }) { now },
            measured = { kept[it] },
            language = { "en" },
            main = main,
            io = Executor { r -> if (holdLookups > 0) { holdLookups--; held += r } else r.run() },
            work = direct,
            elapsed = { main.now },
            wall = { now },
            online = { true },
            lineMbps = { 122.0 },
            seasonEpisodes = { title, season -> if (title.id == show.id && season == 1) 10 else null },
            log = { },
        )
    }

    /** The app starts again: a new engine over the same library rows (vod.db) and what was measured (quality_seen). */
    private fun restart() {
        loop = FakeLoop()
        library = RecordedLibrary(VodLibrary(store, { now }, direct) { })
        engine = engine()
    }

    private fun list(target: SourceTarget = ep9): SourceList {
        val got = ArrayList<SourceList>()
        val c = engine.lookUp(target, LookupWhy.PLAY, null) { got += it }
        loop.advance()
        c.cancel()
        return got.last()
    }

    private fun open(session: SourceSession, source: PlaybackSource? = null, why: OpenWhy = OpenWhy.START): List<Opened> {
        val got = ArrayList<Opened>()
        session.open(source, why) { got += it }
        loop.advance()
        return got
    }

    private fun List<PlaybackSource>.fromMf() = single { s -> s.offers.any { it.addon == Addon.MEDIAFUSION } }
    private fun List<PlaybackSource>.holding(hash: String) = single { s -> s.offers.any { it.release?.infoHash == hash } }
    private fun PlaybackSource.holds(hash: String) = offers.any { (it.release ?: release)?.infoHash == hash }

    private fun played(bytes: Long, mbps: Double) = Measured(
        MediaSpec(width = 3840, height = 2160, videoCodec = "HEVC", sizeBytes = bytes, overallBps = (mbps * 1_000_000).toLong(),
            videoBps = (mbps * 900_000).toLong(), videoHow = MediaSpec.How.MEASURED, measuredSecs = 600, playedAt = now, audioLangs = "en"),
        CopyHealth(watchedMs = 600_000, at = now), now,
    )

    /** The release kept the day before: the 3.1 GB HDR copy. */
    private val nov25 = LastRelease(Addon.TORRENTIO, ReleaseKey(hk, 8), now - day)

    /**
     * The TV case's first play: MediaFusion's pack copy hand-picked in the title page's Sources window, from its beginning;
     * its first frame, Picture & sound's figures kept under its key, and the list ranked again. Its key.
     */
    private fun playHandPicked(target: SourceTarget = ep9): String {
        val before = list(target)
        val mfRow = before.sources.fromMf()
        assertNull(mfRow.release)
        val session = engine.session(VodLaunch.fromTitlePage(target, StartAt.Beginning, before, library, chosen = mfRow))
        val ready = open(session, mfRow).last() as Opened.Ready
        assertEquals(Addon.MEDIAFUSION, ready.offer.addon)
        session.started(ready.source, ready.offer)
        val key = ready.offer.copyKey!!
        kept[key] = played(e9Bytes, 8.0)
        engine.rerank(target)
        loop.advance()
        session.close()
        return key
    }

    /** A Continue card for E9, stopped 10 minutes in. */
    private fun continueCard(episode: EpisodeRef = e9): PlayRequest {
        val repo = FakeVodRepository()
        repo.recordProgress(show, episode, 600_000, 1_680_000)
        return VodLaunch.fromContinueCard(Card(show, episode = episode), repo, library)!!
    }

    // ---- (a) Kept as the copy left, in vod.db's rows ----

    @Test
    fun aCopyOfNoInfoHashThatPlayedIsKeptAsTheCopyLeftAndReadBackAfterARestart() {
        library.recordRelease(show, e9, nov25)
        val key = playHandPicked()
        // In place of the release kept before: by its key, from MediaFusion.
        val left = library.lastRelease(show, e9)!!
        assertEquals(LastRelease.ofCopy(Addon.MEDIAFUSION, key, left.at), left)
        assertNull(left.release)
        assertEquals(key, left.key)
        // vod.db's row: the add-on's name, the key in the hash column, no file index; never a link, token or debrid key.
        val row = store.releases.values.single()
        assertEquals(listOf("tt0000077:1:9", "MEDIAFUSION", key, "-1"), listOf(row.id, row.addon, row.hash, row.fileIdx.toString()))
        assertTrue(key, key.matches(Regex("""x\|mediafusion\|[0-9a-f]{16}\|tt0000077:1:9""")))
        assertFalse(row.hash.contains("invalid") || row.hash.contains("FAKE"))
        // Read back by a new library over the same rows.
        restart()
        assertEquals(left, library.lastRelease(show, e9))
        // Another episode's copy left is its own.
        assertNull(library.lastRelease(show, e10))
    }

    @Test
    fun aCopysKeyRoundTripsThroughTheLibraryAndAReleaseReplacesIt() {
        val k = CopyKey.of(Addon.MEDIAFUSION, packName, null, "📦 28.8 GB", ep9.streamId)
        library.recordRelease(show, e9, LastRelease.ofCopy(Addon.MEDIAFUSION, k, 5L))
        assertEquals(k, library.lastRelease(show, e9)?.copyKey)
        assertEquals(Addon.MEDIAFUSION, RecordedLibrary(VodLibrary(store, { now }, direct) { }).lastRelease(show, e9)?.addon)
        // A release played since takes its place (one row a title or episode).
        library.recordRelease(show, e9, nov25)
        assertEquals(ReleaseKey(hk, 8), library.lastRelease(show, e9)?.release)
        assertNull(library.lastRelease(show, e9)?.copyKey)
        assertEquals(listOf(hk), store.releases.values.map { it.hash })
        // And back.
        library.recordRelease(show, e9, LastRelease.ofCopy(Addon.MEDIAFUSION, k, 6L))
        assertEquals(listOf(k), store.releases.values.map { it.hash })
        // Forgotten as any release (the show left Continue Watching).
        library.forgetReleases(show)
        assertTrue(store.releases.isEmpty())
        assertNull(library.lastRelease(show, e9))
    }

    // ---- (e) Releases kept as before; an earlier version reads no release from a copy's key ----

    @Test
    fun aReleaseRowReadsAsBeforeAndACopysKeyReadTheOldWayIsNoRelease() {
        // A release (what every version keeps): its hash and index, read as they always were.
        library.recordRelease(show, e9, nov25)
        val row = store.releases.values.single()
        assertEquals(listOf("TORRENTIO", hk, "8"), listOf(row.addon, row.hash, row.fileIdx.toString()))
        assertEquals(LastRelease(Addon.TORRENTIO, ReleaseKey(hk, 8), row.at), library.lastRelease(show, e9))
        // A row an earlier version wrote: upper case, no index (-1).
        store.releases.clear()
        store.saveRelease(ReleaseRow(Kind.SERIES, "tt0000077:1:9", "MEDIAFUSION", hk.uppercase(), -1, 5L))
        restart()
        assertEquals(LastRelease(Addon.MEDIAFUSION, ReleaseKey(hk, null), 5L), library.lastRelease(show, e9))
        // A copy's key, as an earlier version reads the row (ReleaseKey.of: 40 hex digits only): no release, so its resume
        // is the ranking's pick, as when nothing was kept.
        val k = CopyKey.of(Addon.MEDIAFUSION, packName, null, "📦 28.8 GB", ep9.streamId)
        library.recordRelease(show, e9, LastRelease.ofCopy(Addon.MEDIAFUSION, k, 6L))
        val copyRow = store.releases.values.single()
        assertEquals(-1, copyRow.fileIdx)
        assertNull(ReleaseKey.of(copyRow.hash, copyRow.fileIdx))
        assertNull(ReleaseKey.parse(copyRow.hash))
        // What an earlier version stores over it is read by this one as before.
        store.saveRelease(ReleaseRow(Kind.SERIES, "tt0000077:1:9", "TORRENTIO", hk, 8, 7L))
        restart()
        assertEquals(LastRelease(Addon.TORRENTIO, ReleaseKey(hk, 8), 7L), library.lastRelease(show, e9))
        // A key not whole, or of an add-on not known here: nothing (the ranking's pick).
        for (bad in listOf("x|mediafusion|zz|tt0000077:1:9", "x|mediafusion|0123456789abcdef|", "x|", "y|mediafusion|0123456789abcdef|tt0000077:1:9")) {
            store.saveRelease(ReleaseRow(Kind.SERIES, "tt0000077:1:9", "MEDIAFUSION", bad, -1, 8L))
            restart()
            assertNull(bad, library.lastRelease(show, e9))
        }
        store.saveRelease(ReleaseRow(Kind.SERIES, "tt0000077:1:9", "SOMEADDON", k, -1, 9L))
        restart()
        assertNull(library.lastRelease(show, e9))
        // A key not whole is never stored.
        library.recordRelease(show, e9, LastRelease.ofCopy(Addon.MEDIAFUSION, "x|mediafusion|nothex|tt0000077:1:9", 10L))
        assertEquals("SOMEADDON", store.releases.values.single().addon)
    }

    @Test
    fun aResumeOfAReleaseKeptPlaysItAsBefore() {
        library.recordRelease(show, e9, nov25)
        restart()
        val l = list()
        val hdr = l.sources.holding(hk)
        assertEquals(hdr.key, l.lastUsed?.key)
        for (request in listOf(VodLaunch.fromTitlePage(ep9, StartAt.At(600_000), l, library), continueCard())) {
            assertEquals(ReleaseKey(hk, 8), request.preferred?.release)
            assertNull(request.preferred?.copyKey)
            val s = engine.session(request)
            val ready = open(s).last() as Opened.Ready
            assertEquals(ReleaseKey(hk, 8), ready.offer.release)
            assertEquals(600_000L, ready.startMs)
            assertNull(s.resumeMiss())
            s.close()
        }
    }

    // ---- (b) Resume plays the copy left, merged row or not, whatever ranks first ----

    @Test
    fun resumeAndAContinueCardPlayTheCopyLeftThoughAnotherRanksFirstAndItsRowJoinedAnotherAddons() {
        library.recordRelease(show, e9, nov25)
        val key = playHandPicked()
        val l = list()
        // Measured, MediaFusion's copy joined Torz's file of its size to the byte: one row, the 3.1 GB HDR copy first.
        val row = l.sources.fromMf()
        assertEquals(setOf(Addon.MEDIAFUSION, Addon.TORZ), row.addons.toSet())
        assertTrue(row.holds(hz))
        val hdr = l.sources.holding(hk)
        assertEquals(hdr.key, l.best?.key)
        assertNotEquals(row.key, hdr.key)
        // The title page says it is Last used, as Resume will play it.
        assertEquals(row.key, l.lastUsed?.key)
        assertTrue(PlayRules.sourceLine(l)!!, PlayRules.sourceLine(l)!!.startsWith("Last used · 4K · WEB · 2.9 GB (pack 28.8 GB)"))
        // Resume on the title page: that copy, from MediaFusion (never Torz's offer in its row first), where it stopped.
        val page = VodLaunch.fromTitlePage(ep9, StartAt.At(600_000), l, library)
        assertEquals(key, page.preferred?.copyKey)
        val s1 = engine.session(page)
        val r1 = open(s1).last() as Opened.Ready
        assertEquals(row.key, r1.source.key)
        assertEquals(Addon.MEDIAFUSION, r1.offer.addon)
        assertEquals(key, r1.offer.copyKey)
        assertEquals(Addon.MEDIAFUSION, r1.source.offers.first().addon)
        assertEquals(600_000L, r1.startMs)
        assertFalse(r1.resumedNear)
        assertNull(s1.resumeMiss())
        s1.started(r1.source, r1.offer)
        // Still kept by its own key, though its row holds Torz's release now: a Continue card finds it in MediaFusion's
        // answer alone.
        assertEquals(LastRelease.ofCopy(Addon.MEDIAFUSION, key, library.lastRelease(show, e9)!!.at), library.lastRelease(show, e9))
        // Next source is never the copy playing, nor its file under Torz's name.
        val next = s1.nextPick()!!
        assertNotEquals(row.key, next.key)
        assertFalse(next.holds(hz))
        s1.close()
        // A Continue card (the list kept in memory): the same copy at once.
        val card = continueCard()
        assertEquals(PlayOrigin.CONTINUE_CARD, card.origin)
        assertEquals(key, card.preferred?.copyKey)
        val s2 = engine.session(card)
        val r2 = open(s2).last() as Opened.Ready
        assertEquals(key, r2.offer.copyKey)
        assertEquals(600_000L, r2.startMs)
        s2.close()
        // Before it joined Torz's row (nothing measured yet), the same: its own row.
        kept.clear()
        engine.rerank(ep9)
        loop.advance()
        val apart = list()
        assertEquals(2, apart.sources.count { it.holds(hz) || it.offers.any { o -> o.addon == Addon.MEDIAFUSION } })
        val s3 = engine.session(VodLaunch.fromTitlePage(ep9, StartAt.At(600_000), apart, library))
        val r3 = open(s3).last() as Opened.Ready
        assertEquals(key, r3.offer.copyKey)
        assertEquals(listOf(Addon.MEDIAFUSION), r3.source.addons)
    }

    @Test
    fun theTitlePagesResumeBeforeEveryAddonAnsweredWaitsForTheCopyLeftsAddon() {
        val key = playHandPicked()
        restart()
        // OK pressed again while it waited: Torrentio's answer only (the HDR copy, best so far); MediaFusion not yet.
        val got = ArrayList<SourceList>()
        val c = engine.lookUp(ep9, LookupWhy.PLAY, Addon.TORRENTIO) { got += it }
        loop.advance()
        c.cancel()
        val partial = got.last()
        assertFalse(partial.complete)
        assertEquals(ReleaseKey(hk, 8), partial.best?.release)
        val s = engine.session(VodLaunch.fromTitlePage(ep9, StartAt.At(600_000), partial, library))
        val ready = open(s).last() as Opened.Ready
        // The copy left, once MediaFusion listed it; not the best so far.
        assertEquals(key, ready.offer.copyKey)
        assertEquals(600_000L, ready.startMs)
    }

    // ---- (c) After a restart ----

    @Test
    fun afterARestartTheCopyLeftIsLastUsedAndAContinueCardPlaysItFromItsAddonAlone() {
        library.recordRelease(show, e9, nov25)
        val key = playHandPicked()
        restart()
        // The Sources window: Last used on that copy's row (joined with Torz's file again), not the 3.1 GB copy's.
        val l = list()
        val row = l.sources.fromMf()
        assertTrue(row.holds(hz))
        assertEquals(row.key, l.lastUsed?.key)
        assertEquals(l.sources.holding(hk).key, l.best?.key)
        // A Continue card after another restart: MediaFusion asked alone, its copy plays at once.
        restart()
        asked.clear()
        val card = continueCard()
        assertEquals(key, card.preferred?.copyKey)
        val s = engine.session(card)
        val ready = open(s).last() as Opened.Ready
        assertEquals(listOf(Addon.MEDIAFUSION), asked)
        assertEquals(key, ready.offer.copyKey)
        assertEquals(600_000L, ready.startMs)
        assertFalse(ready.resumedNear)
        s.started(ready.source, ready.offer)
        // The others are asked a while after it started; Next source then has every copy and never names it.
        loop.advance(DefaultSourceSession.WIDEN_AFTER_MS)
        assertEquals(setOf(Addon.TORRENTIO, Addon.MEDIAFUSION, Addon.TORZ), asked.toSet())
        val next = s.nextPick()!!
        assertNotEquals(s.rowKey(ready.source), next.key)
        assertFalse(next.holds(hz))
        assertEquals(key, library.lastRelease(show, e9)?.copyKey)
        s.close()
        // Resume on the title page after a restart: the same.
        restart()
        val again = list()
        val s2 = engine.session(VodLaunch.fromTitlePage(ep9, StartAt.At(600_000), again, library))
        val r2 = open(s2).last() as Opened.Ready
        assertEquals(key, r2.offer.copyKey)
        assertEquals(again.lastUsed?.key, r2.source.key)
    }

    // ---- (d) The copy left no longer offered ----

    @Test
    fun theCopyLeftNoLongerOfferedThePlayIsTheRankingsPickWithoutAHang() {
        playHandPicked()
        // MediaFusion now offers another copy of its own (another release group's pack).
        val other = packName.replace("GRP", "OTHER")
        answerOf[Addon.MEDIAFUSION] = { t -> answer(mf(t, other)) }
        restart()
        asked.clear()
        val s = engine.session(continueCard())
        val ready = open(s).last() as Opened.Ready
        // MediaFusion's answer final without it: every add-on asked, the ranking's pick, a little before where it stopped.
        assertEquals(setOf(Addon.TORRENTIO, Addon.MEDIAFUSION, Addon.TORZ), asked.toSet())
        assertEquals(ReleaseKey(hk, 8), ready.offer.release)
        assertTrue(ready.resumedNear)
        assertEquals(600_000L - DefaultSourceSession.BACK_MS, ready.startMs)
        assertEquals("not listed any more", s.resumeMiss())
        s.close()
        // MediaFusion failing: the same.
        answerOf[Addon.MEDIAFUSION] = { null }
        restart()
        val failed = engine.session(continueCard())
        assertEquals(ReleaseKey(hk, 8), (open(failed).last() as Opened.Ready).offer.release)
        failed.close()
        // MediaFusion not answering at all: its limit ends the wait, then the others are asked.
        answerOf[Addon.MEDIAFUSION] = { t -> answer(mf(t)) }
        restart()
        holdLookups = 1
        val silent = engine.session(continueCard())
        val got = ArrayList<Opened>()
        silent.open(null, OpenWhy.START) { got += it }
        loop.advance()
        assertEquals(1, held.size)
        assertTrue(got.all { it is Opened.Looking })
        loop.advance(Addon.MEDIAFUSION.answerLimitMs)
        val late = got.last() as Opened.Ready
        assertEquals(ReleaseKey(hk, 8), late.offer.release)
        assertTrue(silent.list!!.states[Addon.MEDIAFUSION] is AddonState.Failed)
        // On the title page too: the ranking's pick.
        answerOf[Addon.MEDIAFUSION] = { t -> answer(mf(t, other)) }
        restart()
        val l = list()
        assertNull(l.lastUsed)
        val page = engine.session(VodLaunch.fromTitlePage(ep9, StartAt.At(600_000), l, library))
        assertEquals(ReleaseKey(hk, 8), (open(page).last() as Opened.Ready).offer.release)
    }

    @Test
    fun theCopyLeftSetAsideOrNotCachedIsntPlayedByItself() {
        val key = playHandPicked()
        // No longer cached: the same copy (the same key), never opened without asking.
        answerOf[Addon.MEDIAFUSION] = { t -> answer(mf(t, ready = false)) }
        restart()
        val uncached = engine.session(continueCard())
        val first = open(uncached).last() as Opened.Ready
        assertNull(first.offer.copyKey)
        assertEquals(ReleaseKey(hk, 8), first.offer.release)
        assertEquals("not cached any more", uncached.resumeMiss())
        assertEquals(key, uncached.list!!.sources.fromMf().offers.first { it.addon == Addon.MEDIAFUSION }.copyKey)
        // Cached again, but this Stick showed no picture for it.
        answerOf[Addon.MEDIAFUSION] = { t -> answer(mf(t)) }
        kept[key] = Measured(kept[key]!!.spec, CopyHealth(noPicture = 1, at = now), now)
        restart()
        val s = engine.session(continueCard())
        val ready = open(s).last() as Opened.Ready
        assertNotEquals(key, ready.offer.copyKey)
        assertEquals("set aside here: it showed no picture", s.resumeMiss())
    }

    // ---- (f) Not the whole episode ----

    @Test
    fun aCopyOfNoInfoHashFoundNotWholePutsBackTheCopyKeptBefore() {
        library.recordRelease(show, e9, nov25)
        val l = list()
        val mfRow = l.sources.fromMf()
        val s = engine.session(VodLaunch.fromTitlePage(ep9, StartAt.Beginning, l, library, chosen = mfRow))
        val ready = open(s, mfRow).last() as Opened.Ready
        s.started(ready.source, ready.offer)
        assertEquals(ready.offer.copyKey, library.lastRelease(show, e9)?.copyKey)
        // Chosen by hand, it plays on, but isn't the copy left: the 3.1 GB copy is again, and Last used.
        s.notWhole(ready.source)
        loop.advance()
        assertEquals(ReleaseKey(hk, 8), library.lastRelease(show, e9)?.release)
        assertEquals(l.sources.holding(hk).key, engine.cached(ep9)!!.lastUsed?.key)
        // Its picture again: still not kept.
        s.started(ready.source, ready.offer)
        assertEquals(ReleaseKey(hk, 8), library.lastRelease(show, e9)?.release)
        restart()
        assertEquals(ReleaseKey(hk, 8), library.lastRelease(show, e9)?.release)
        assertEquals(list().sources.holding(hk).key, list().lastUsed?.key)
        // Ended as not the whole episode, with nothing kept before it (E10): nothing is kept.
        val l10 = list(ep10)
        val mf10 = l10.sources.fromMf()
        val s10 = engine.session(VodLaunch.fromTitlePage(ep10, StartAt.Beginning, l10, library, chosen = mf10))
        val r10 = open(s10, mf10).last() as Opened.Ready
        s10.started(r10.source, r10.offer)
        assertEquals(r10.offer.copyKey, library.lastRelease(show, e10)?.copyKey)
        assertEquals(Step.TryNext, s10.failed(r10.source, Failure(FailureKind.NOT_WHOLE, Addon.MEDIAFUSION)))
        assertNull(library.lastRelease(show, e10))
        assertTrue(store.releases.values.none { it.id == "tt0000077:1:10" })
    }

    // ---- (g) Fallbacks, Next source, and each episode's own file ----

    @Test
    fun aCopyLeftThatFailsFallsBackAsBeforeAndTheCopyThatPlaysIsKeptInstead() {
        val key = playHandPicked()
        restart()
        val l = list()
        val s = engine.session(VodLaunch.fromTitlePage(ep9, StartAt.At(600_000), l, library))
        val ready = open(s).last() as Opened.Ready
        assertEquals(key, ready.offer.copyKey)
        // No picture here: the next copy, never that file under Torz's name; near where it was.
        assertEquals(Step.TryNext, s.failed(ready.source, Failure(FailureKind.NO_PICTURE)))
        val next = open(s, why = OpenWhy.FAILED).last() as Opened.Ready
        assertFalse(next.source.holds(hz))
        assertNull(next.offer.copyKey)
        assertEquals(ReleaseKey(hk, 8), next.offer.release)
        s.started(next.source, next.offer)
        // That copy is the one left now.
        assertEquals(ReleaseKey(hk, 8), library.lastRelease(show, e9)?.release)
        assertEquals(Addon.TORRENTIO, library.lastRelease(show, e9)?.addon)
    }

    @Test
    fun eachEpisodeResumesItsOwnCopyLeftFromTheSeasonPack() {
        val key9 = playHandPicked(ep9)
        val key10 = playHandPicked(ep10)
        assertNotEquals(key9, key10)
        assertTrue(key10.endsWith("|tt0000077:1:10"))
        restart()
        // E10 resumed (the Episodes sheet's resume, a Continue card naming it): E10's own copy, E10's link.
        val card = continueCard(e10)
        assertEquals(key10, card.preferred?.copyKey)
        val s = engine.session(card)
        val ready = open(s).last() as Opened.Ready
        assertEquals(key10, ready.offer.copyKey)
        assertEquals("https://cdn.invalid/d/10/f.mkv", ready.link.reveal())
        s.close()
        // E9's copy left never applies to E10: no row of E10 offers it, the ranking's pick plays.
        val wrong = engine.session(PlayRequest(ep10, StartAt.At(600_000), PlayOrigin.CONTINUE_CARD,
            preferred = LastRelease.ofCopy(Addon.MEDIAFUSION, key9, 1L)))
        val r = open(wrong).last() as Opened.Ready
        assertNotEquals(key9, r.offer.copyKey)
        assertEquals("not listed any more", wrong.resumeMiss())
        // Each episode's Last used is its own.
        assertEquals(list(ep9).sources.fromMf().key, list(ep9).lastUsed?.key)
        assertEquals(list(ep10).sources.fromMf().key, list(ep10).lastUsed?.key)
    }
}
