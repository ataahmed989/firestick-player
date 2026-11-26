package app.fireplayer.vod.sources

import app.fireplayer.player.CopyHealth
import app.fireplayer.player.MediaSpec
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
import app.fireplayer.vod.Measured
import app.fireplayer.vod.Opened
import app.fireplayer.vod.PlayOrigin
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.PlaybackLibrary
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.ReleaseKey
import app.fireplayer.vod.SecretUrl
import app.fireplayer.vod.SoundFormat
import app.fireplayer.vod.SourceList
import app.fireplayer.vod.SourceSession
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.StartAt
import app.fireplayer.vod.ThisTv
import app.fireplayer.vod.Title
import app.fireplayer.vod.TvCapabilities
import app.fireplayer.vod.TvFit
import app.fireplayer.vod.TvPlan
import app.fireplayer.vod.play.MeasuredRerank
import app.fireplayer.vod.play.PlayRules
import app.fireplayer.vod.play.SourcesModel
import app.fireplayer.vod.sources.SourceFixtures.answer
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
 * MediaFusion's copies whose answer and link show no info hash ([app.fireplayer.vod.Offer.release] null), known by
 * their words ([CopyKey]) so what a play here measured of them is kept and shown (made-up names, ".invalid" links, a
 * fake token where the link hides the torrent). The TV case: Your Friendly Neighborhood Spider-Man S1 E1 on the Fire TV
 * Stick 4K Plus, MediaFusion's "1080p · WEB · (18.9 GB)", whose play Picture & sound measured at 1.87 GB, and which
 * kept nothing ("measured not kept: this copy's file isn't known"): its row kept the pack's size and its place.
 */
class CopyKeyTest {

    private val show = Title("tt0000077", Kind.SERIES, "Your Friendly Neighborhood Spider-Man", runtime = "28 min")
    private val e1 = EpisodeRef(1, 1)
    private val e2 = EpisodeRef(1, 2)
    private val ep1 = SourceTarget(show, e1)
    private val ep2 = SourceTarget(show, e2)
    private val now = 1_764_000_000_000L

    /** The Max on its TV: HDR10 (no Dolby Vision), DD and DD+ passed through. */
    private val plan = TvPlan(true, listOf(setOf(Hdr.HDR10, Hdr.HDR10_PLUS), setOf(Hdr.HLG)), 2160, setOf(SoundFormat.AC3, SoundFormat.EAC3),
        dv7AsHdr10 = true)

    private val packTotal = 18_900_000_000L
    /** E1's file, as Picture & sound said it: "File 1.87 GB". */
    private val e1Bytes = 1_870_123_456L
    private val unclearName = "Your.Friendly.Neighborhood.Spider-Man.1080p.WEB.H264-NTb.mkv"
    private val packName = "Your.Friendly.Neighborhood.Spider-Man.S01.1080p.WEB.H264-NTb"
    private val hk = "e".repeat(40)
    private val hz = "c".repeat(40)

    /**
     * As MediaFusion writes an episode's stream when its link hides the torrent: no infoHash, no hash in the link (a
     * token), its one size ([videoSize], [sizeWords]); [file] as behaviorHints.filename; [extra] more description.
     */
    private fun mf(
        file: String?, videoSize: Long? = packTotal, sizeWords: String = "18.9 GB", seeders: Int = 12, ready: Boolean = true,
        hdr: String? = null, token: String = "A1", extra: String = "",
    ) = JSONObject().apply {
        put("name", "MediaFusion | ElfHosted 🧲 RD ${if (ready) "⚡️" else "⏳"} 1080p")
        val facts = listOfNotNull(hdr?.let { "🎨 $it" }, "📺 WEB", "🎞️ hevc", "🎵 Dolby Digital Plus", "🔊 5.1").joinToString(" ")
        put("description", "$facts\n📦 $sizeWords 👤 $seeders\n🌐 English$extra")
        put("url", "https://mf.invalid/streaming_provider/FAKECONFIG_MF/stream/FAKETOKEN_$token/video.mkv")
        put("behaviorHints", JSONObject().apply {
            put("bingeGroup", "MediaFusion-|-ElfHosted-${hdr?.let { "🎨 $it " } ?: ""}📺 WEB 🎞️ hevc 🎵 Dolby Digital Plus-1080P")
            file?.let { put("filename", it) }
            videoSize?.let { put("videoSize", it) }
        })
    }

    private fun torz(hash: String, file: String, videoSize: Long, hdr: String = "", idx: Int = 0) = JSONObject().apply {
        put("name", "⚡️ [RD] Torz\n1080p")
        put("description", "💿 WEB 🎞️ HEVC\n📺 $hdr 🎧 DDP | 5.1\n💾 1 GB 👤 12\n📄 $file")
        put("url", "https://torz.invalid/stremio/torz/FAKECONFIG_TORZ/_/strem/tt0000077/rd/$hash/$idx/$file")
        put("behaviorHints", JSONObject().put("videoSize", videoSize).put("filename", file))
    }

    private fun parsed(addon: Addon, target: SourceTarget, vararg streams: JSONObject) =
        StreamParser.parse(addon, answer(*streams), 28, episode = true, streamId = target.streamId).streams

    private fun input(target: SourceTarget, measured: (String) -> Measured?) =
        RankInput(plan, listOf("en"), measured, 28, episode = true, now = now, episodeRef = target.episode)

    /** Merged and ranked as the engine does ([DefaultSourcesRepository.rebuild]): records by release, and by copy key. */
    private fun rows(target: SourceTarget, vararg answers: List<ParsedStream>, measured: Map<String, Measured> = emptyMap(), count: Int? = 10): List<PlaybackSource> {
        val merged = SourceMerge.merge(answers.toList(), 28, episode = true, { count }, { k -> measured[k]?.spec?.sizeBytes?.takeIf { it > 0 } }) { r ->
            measured[r.measuredKey(target.episode)]?.spec?.sizeBytes
        }
        return SourceRank.rank(merged, input(target) { measured[it] })
    }

    private fun line(s: PlaybackSource) = PlayRules.mainLine(s, film = false, plan = plan)
    private fun details(s: PlaybackSource) = SourcesModel.details(s, plan, film = false) { "Nov 26" }.toMap()
    private fun List<PlaybackSource>.fromMf() = single { s -> s.offers.any { it.addon == Addon.MEDIAFUSION } }
    private fun List<PlaybackSource>.holding(hash: String) = single { s -> s.offers.any { it.release?.infoHash == hash } }
    private fun copyKeyOf(s: PlaybackSource) = s.offers.first { it.addon == Addon.MEDIAFUSION }.copyKey!!

    /** What a play of [bytes] measured: [mbps] on average, its picture's rate a little less; [hdr] as its track declares. */
    private fun played(bytes: Long, mbps: Double, hdr: String? = null, hdr10Plus: Boolean = false) = Measured(
        MediaSpec(width = 1920, height = 1080, videoCodec = "HEVC", hdr = hdr, hdr10Plus = hdr10Plus, sizeBytes = bytes,
            overallBps = (mbps * 1_000_000).toLong(), videoBps = (mbps * 900_000).toLong(), videoHow = MediaSpec.How.MEASURED,
            measuredSecs = 600, playedAt = now, audioLangs = "en"),
        CopyHealth(watchedMs = 600_000, at = now), now,
    )

    // ---- (A) The TV case, through the engine ----

    private val loop = FakeLoop()
    private val direct = Executor { it.run() }
    private val kept = HashMap<String, Measured>()
    private var last: LastRelease? = null
    private val logged = ArrayList<String>()
    private var mfSeeders = 12
    private val links = HashMap<Addon, SecretUrl>().apply {
        put(Addon.MEDIAFUSION, SecretUrl.of(SourceFixtures.MF)!!)
        put(Addon.TORZ, SecretUrl.of("https://torz.invalid/FAKECONFIG_TORZ")!!)
    }
    private val answerOf = HashMap<Addon, () -> String>().apply {
        put(Addon.MEDIAFUSION) { answer(mf(unclearName, seeders = mfSeeders)) }
        // A light copy: 600 MB, about 2.9 Mb/s.
        put(Addon.TORZ) { answer(torz(hk, "Your.Friendly.Neighborhood.Spider-Man.S01E01.1080p.WEB.H264-LOW.mkv", 600_000_000L)) }
    }
    private val engine = DefaultSourcesRepository(
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
            override fun lastRelease(title: Title, episode: EpisodeRef?) = last
            override fun recordRelease(title: Title, episode: EpisodeRef?, release: LastRelease) {
                last = release
            }
            override fun recordPlayback(title: Title, episode: EpisodeRef?, positionMs: Long, durationMs: Long, creditsAtMs: Long?) = Unit
        },
        http = StreamsHttp { base, _, _, _ ->
            val addon = if (base.host == "mf.invalid") Addon.MEDIAFUSION else Addon.TORZ
            answerOf[addon]?.invoke() ?: throw IOException("no answer set")
        },
        linkCheck = LinkCheck({ url, _, _ -> LinkCheck.Answer(302, "https://cdn.invalid/d/${url.length}/f.mkv", null) }, { true }, { loop.now }),
        budget = Budget(object : Budget.Store {
            var text: String? = null
            override fun read() = text
            override fun write(text: String) {
                this.text = text
            }
        }) { now },
        measured = { kept[it] },
        language = { "en" },
        main = loop,
        io = direct,
        work = direct,
        elapsed = { loop.now },
        wall = { now },
        online = { true },
        lineMbps = { 122.0 },
        seasonEpisodes = { title, season -> if (title.id == show.id && season == 1) 10 else null },
        log = { logged += it },
    )

    private fun list(target: SourceTarget = ep1): SourceList {
        val got = ArrayList<SourceList>()
        engine.lookUp(target, LookupWhy.PLAY, null) { got += it }
        loop.advance()
        return got.last()
    }

    @Test
    fun theTvCaseACopyOfNoInfoHashKeepsWhatItsPlayMeasuredAndItsRowShowsAndRanksByIt() {
        val before = list()
        val mfRow = before.sources.fromMf()
        assertNull(mfRow.release)
        // Its one size may be the pack's or the episode's: said in parentheses, ranked as a rate not known.
        assertEquals("1080p · WEB · (18.9 GB)", line(mfRow))
        val light = before.sources.holding(hk)
        assertEquals(listOf(light.key, mfRow.key), before.sources.map { it.key })
        // Chosen in Sources, it plays from MediaFusion: no release, its own key for E1 from its words.
        val session = engine.session(PlayRequest(ep1, StartAt.Beginning, PlayOrigin.SOURCES_PANEL, list = before, chosen = mfRow))
        var told = 0
        session.setListener { told++ }
        val got = ArrayList<Opened>()
        session.open(mfRow) { got += it }
        loop.advance()
        val ready = got.last() as Opened.Ready
        session.started(ready.source, ready.offer)
        // What PlayerActivity.startSource keeps the play under: no release, so the offer's own key.
        assertNull(ready.offer.release ?: ready.source.release)
        val key = ready.offer.copyKey!!
        assertTrue(key, key.matches(Regex("""x\|mediafusion\|[0-9a-f]{16}\|tt0000077:1:1""")))
        assertFalse(key.contains("FAKE") || key.contains("invalid"))
        // Its figures are kept (Picture & sound's: 1.87 GB at 8.9 Mb/s), and the list ranks again once a play.
        kept[key] = played(e1Bytes, 8.9)
        val ranks = MeasuredRerank()
        assertTrue(ranks.kept(sized = true))
        told = 0
        logged.clear()
        engine.rerank(ep1)
        loop.advance()
        assertTrue(told > 0)
        val after = session.list!!
        val row = after.sources.fromMf()
        assertNotNull(row.measured)
        // Named by the size played here, with the pack's: the file is a tenth of the 18.9 GB, so the torrent is a pack.
        assertEquals("1080p · WEB · 1.9 GB (pack 18.9 GB)", line(row))
        assertEquals("1.87 GB · measured here (pack 18.9 GB)", details(row)["Size"])
        assertEquals("≈8 Mb/s average · measured here", details(row)["Data rate"])
        assertEquals(TvFit.MEASURED_OK, row.fit)
        // Ranked by its rate now: nearer the preferred 60 Mb/s than the light copy's 2.9.
        assertEquals(listOf(row.key, light.key), after.sources.map { it.key })
        // The Playing row and Last used are that row; Next source never names it.
        assertEquals(row.key, session.rowKey(ready.source))
        assertEquals(row.key, after.lastUsed?.key)
        assertNotEquals(row.key, session.nextPick()?.key)
        val items = SourcesModel.items(after, now, setOf(SourcesModel.Section.FHD), playingKey = session.rowKey(ready.source))
        assertEquals("1080p · WEB · 1.9 GB (pack 18.9 GB)", items.first { it.key == SourcesModel.PLAYING_KEY }.label)
        // The log says the row's record by fingerprints only: never the key, the link or its token.
        assertTrue(logged.toString(), logged.any { it == "rank: row ${SourceLog.fp(row.key)} measured=${SourceLog.fp(key)} tried=${SourceLog.fp(key)}" })
        assertTrue(logged.toString(), logged.none { "x|" in it || "FAKE" in it || "invalid" in it })
        // Another look-up (its seeders changed): the same copy, the same key, what it measured still shown.
        mfSeeders = 57
        engine.forget()
        loop.advance()
        val again = list().sources.fromMf()
        assertEquals(key, copyKeyOf(again))
        assertEquals("1080p · WEB · 1.9 GB (pack 18.9 GB)", line(again))
        // Another episode's copy of the same words is another key: E1's record never applies to E2.
        val e2Row = list(ep2).sources.fromMf()
        assertNotEquals(key, copyKeyOf(e2Row))
        assertNull(e2Row.measured)
        assertEquals("1080p · WEB · (18.9 GB)", line(e2Row))
    }

    // ---- (B) A pack's estimate replaced, and the rank ----

    @Test
    fun aPackRowsEstimateIsReplacedByItsMeasuredSizeBesideThePacksAndRanksByTheMeasuredRate() {
        val pack = parsed(Addon.MEDIAFUSION, ep1, mf(packName))
        // 2.6 GB, about 12.4 Mb/s.
        val tz = parsed(Addon.TORZ, ep1, torz(hk, "Your.Friendly.Neighborhood.Spider-Man.S01E01.1080p.WEB.H264-MID.mkv", 2_600_000_000L))
        val before = rows(ep1, pack, tz)
        val mfRow = before.fromMf()
        assertEquals("1080p · WEB · ≈1.9 GB (pack 18.9 GB)", line(mfRow))
        assertEquals("≈1.9 GB (estimated: pack 18.9 GB ÷ 10 episodes)", details(mfRow)["Size"])
        // Its estimate (≈9 Mb/s) is further from the preferred rate than Torz's 12.4.
        assertEquals(listOf(before.holding(hk).key, mfRow.key), before.map { it.key })
        // E1 played here at 3.6 GB, 17 Mb/s: no even share of the pack.
        val measured = mapOf(copyKeyOf(mfRow) to played(3_600_000_000L, 17.1))
        val after = rows(ep1, pack, tz, measured = measured)
        val row = after.fromMf()
        assertEquals("1080p · WEB · 3.6 GB (pack 18.9 GB)", line(row))
        assertEquals("3.60 GB · measured here (pack 18.9 GB)", details(row)["Size"])
        assertEquals("≈17 Mb/s average · measured here", details(row)["Data rate"])
        assertTrue(PlayRules.badges(row).contains("3.6 GB (pack 18.9 GB)"))
        assertEquals(listOf(row.key, after.holding(hk).key), after.map { it.key })
        // As played here too, whatever the list had.
        assertEquals("3.6 GB (pack 18.9 GB)", PlayRules.sizeWords(SourceRank.asPlayedHere(mfRow.facts, measured.values.single())))
        // Too long for its room, the pack's size goes first.
        assertEquals("1080p · WEB · 3.6 GB", PlayRules.mainLine(row, "1080p · WEB · 3.6 GB".length, film = false, plan = plan))
    }

    @Test
    fun anUnclearSizeIsThePacksOnlyWhenTheMeasuredFileIsFarLess() {
        // Nothing said whether 18.9 GB is the pack's or the episode's: the file a tenth of it, a pack.
        assertEquals(packTotal, SourceMerge.packBeside(e1Bytes, packTotal, unclear = true))
        // The file nearly all of it: the episode's own torrent, no pack to name.
        assertNull(SourceMerge.packBeside(18_400_000_000L, packTotal, unclear = true))
        // Two thirds of it: nothing tells, no pack named.
        assertNull(SourceMerge.packBeside(12_600_000_000L, packTotal, unclear = true))
        // A pack its names or the other add-ons said: named beside the file, unless the file is nearly all of it.
        assertEquals(packTotal, SourceMerge.packBeside(12_600_000_000L, packTotal, unclear = false))
        assertNull(SourceMerge.packBeside(18_400_000_000L, packTotal, unclear = false))
        assertNull(SourceMerge.packBeside(e1Bytes, null, unclear = false))
        // The row of a file nearly all of its torrent: its measured size alone, no parentheses.
        val single = parsed(Addon.MEDIAFUSION, ep1, mf(unclearName, videoSize = 1_900_000_000L, sizeWords = "1.9 GB"))
        val row = rows(ep1, single, measured = mapOf(single.single().offer.copyKey!! to played(1_870_000_000L, 8.9))).single()
        assertEquals("1080p · WEB · 1.9 GB", line(row))
        assertEquals("1.87 GB · measured here", details(row)["Size"])
    }

    // ---- (C) HDR the name left out ----

    @Test
    fun aCopyWhoseNameSaysNoHdrPlayedAsHdr10ShowsAndRanksAsHdr10() {
        val bare = parsed(Addon.MEDIAFUSION, ep1, mf(packName))
        // An HDR10 copy at 12.4 Mb/s.
        val hdr = parsed(Addon.TORZ, ep1, torz(hk, "Your.Friendly.Neighborhood.Spider-Man.S01E01.1080p.WEB.HDR.H265-HDRG.mkv", 2_600_000_000L, hdr = "HDR10"))
        val before = rows(ep1, bare, hdr)
        val mfRow = before.fromMf()
        assertNull(PlayRules.tvHdr(mfRow, plan))
        // HDR first: the HDR10 copy above, whatever the rates.
        assertEquals(listOf(before.holding(hk).key, mfRow.key), before.map { it.key })
        // Played here: its track said HDR10 (PQ), at 17 Mb/s.
        val measured = mapOf(copyKeyOf(mfRow) to played(3_600_000_000L, 17.1, hdr = "HDR10"))
        val after = rows(ep1, bare, hdr, measured = measured)
        val row = after.fromMf()
        assertEquals("1080p · HDR10 · WEB · 3.6 GB (pack 18.9 GB)", line(row))
        assertEquals("HDR10", PlayRules.tvHdr(row, plan))
        assertTrue(PlayRules.badges(row), PlayRules.badges(row).startsWith("1080p · HDR · WEB"))
        assertEquals("1080p · HDR (measured here) · HEVC", details(row)["Picture"])
        assertEquals("HDR10", details(row)["This TV shows"])
        // Of the same HDR now, the rate nearer the preferred one is first.
        assertEquals(listOf(row.key, after.holding(hk).key), after.map { it.key })
        // HDR10+ seen in its picture too: said as what this TV takes of it.
        val plus = rows(ep1, bare, hdr, measured = mapOf(copyKeyOf(mfRow) to played(3_600_000_000L, 17.1, hdr = "HDR10", hdr10Plus = true))).fromMf()
        assertEquals(setOf(Hdr.HDR10, Hdr.HDR10_PLUS), SourceRank.asPlayedHere(plus.facts, plus.measured).hdr)
        // Measured SDR, or nothing said by its track: as before.
        for (said in listOf("SDR", null)) {
            val sdr = rows(ep1, bare, hdr, measured = mapOf(copyKeyOf(mfRow) to played(3_600_000_000L, 17.1, hdr = said)))
            assertEquals("1080p · WEB · 3.6 GB (pack 18.9 GB)", line(sdr.fromMf()))
            assertEquals(listOf(sdr.holding(hk).key, sdr.fromMf().key), sdr.map { it.key })
        }
        // A Dolby Vision file played as its HDR10 base layer: HDR10 here.
        val dv = MediaSpec(hdr = "HDR10", dvBaseLayer = 7, dvProfile = 7, sizeBytes = 3_600_000_000L, videoHow = MediaSpec.How.MEASURED)
        assertEquals(setOf(Hdr.HDR10), SourceRank.asPlayedHere(mfRow.facts, Measured(dv, null, now)).hdr)
        // The name's own HDR stays as it is.
        val named = SourceRank.asPlayedHere(before.holding(hk).facts, played(2_600_000_000L, 12.4, hdr = "HLG"))
        assertEquals(setOf(Hdr.HDR10), named.hdr)
    }

    // ---- (D) The key ----

    @Test
    fun theKeyIsTheSameAcrossLookUpsAndAnotherForAnotherCopyOrEpisode() {
        fun keyOf(target: SourceTarget, stream: JSONObject) = parsed(Addon.MEDIAFUSION, target, stream).single().offer.copyKey!!
        val k = keyOf(ep1, mf(unclearName))
        // Seeders, the cached mark, another link token: the same copy.
        assertEquals(k, keyOf(ep1, mf(unclearName, seeders = 931, ready = false, token = "B7")))
        // Another episode, another file name, another size: another copy.
        assertNotEquals(k, keyOf(ep2, mf(unclearName)))
        assertNotEquals(k, keyOf(ep1, mf(unclearName.replace("NTb", "FLUX"))))
        assertNotEquals(k, keyOf(ep1, mf(unclearName, sizeWords = "12.0 GB")))
        // With no file name: its bingeGroup and description, without what changes between look-ups.
        val words = "\n📅 3d ⚡ Cached\n🔗 Source | 🧑‍💻 Uploader"
        val n = keyOf(ep1, mf(null, extra = words))
        assertEquals(n, keyOf(ep1, mf(null, seeders = 3, ready = false, extra = "\n📅 12d ⏳ Not cached\n🔗 Source | 🧑‍💻 Uploader")))
        assertNotEquals(n, keyOf(ep1, mf(null, sizeWords = "12.0 GB", extra = words)))
        assertNotEquals(n, keyOf(ep1, mf(null, hdr = "HDR10", extra = words)))
        assertNotEquals(n, keyOf(ep2, mf(null, extra = words)))
        assertEquals(CopyKey.stable("🎨 HDR 📺 WEB\n📦 18.9 GB 👤 12 ⚡️ [RD+] 2 days ago 40 seeders"), CopyKey.stable("🎨 HDR 📺 WEB 📦 18.9 GB 👤 7 ⏳ [RD download] 5 days ago 3 seeders"))
        // An age in words after its mark, whatever its unit, and a date: nothing of it stays.
        for (age in listOf("📅 3 days ago", "📅 2 months ago", "📅 1 year ago", "📅 5h", "📅 2024-05-01", "🕒 12 mins ago")) {
            assertEquals(age, "📺 web 🔗 source", CopyKey.stable("📺 WEB $age 🔗 Source"))
            assertEquals(age, n, keyOf(ep1, mf(null, extra = "\n$age ⚡ Cached\n🔗 Source | 🧑‍💻 Uploader")))
        }
        // "3D" and "5.1" are no age or seeders.
        assertTrue(CopyKey.stable("1080p 3D DDP 5.1").contains("3d"))
        assertTrue(CopyKey.stable("1080p 3D DDP 5.1").contains("5.1"))
        // Its shape: the add-on, 16 hex digits, the episode; never anything of the link.
        assertTrue(k, k.matches(Regex("""x\|mediafusion\|[0-9a-f]{16}\|tt0000077:1:1""")))
        assertTrue(CopyKey.isCopyKey(k))
        // A copy of a release has none (its release keys it), and a parse for no film or episode keys nothing.
        val keyed = parsed(Addon.TORZ, ep1, torz(hk, "A.S01E01.1080p.mkv", 1_000_000_000L)).single()
        assertNotNull(keyed.release)
        assertNull(keyed.offer.copyKey)
        assertNull(StreamParser.parse(Addon.MEDIAFUSION, answer(mf(unclearName)), 28, episode = true).streams.single().offer.copyKey)
    }

    @Test
    fun recordsKeptBeforeStayAndAreReadAsBefore() {
        // A copy's key is never read as a release's ("rd|<hash>|<idx>"): an earlier version ignores it, and it ignores theirs.
        val k = parsed(Addon.MEDIAFUSION, ep1, mf(unclearName)).single().offer.copyKey!!
        assertNull(ReleaseKey.parse(k))
        assertFalse(CopyKey.isCopyKey(ReleaseKey(hk, 0).storeKey))
        // What's kept under it reads back as any record ("quality_seen": the same text, the same "h|" for its health).
        val store = HashMap<String, String>()
        val spec = played(e1Bytes, 8.9, hdr = "HDR10").spec!!
        store[k] = MediaSpec.encode(spec)
        store["h|$k"] = CopyHealth.encode(CopyHealth(watchedMs = 600_000, at = now))
        assertEquals(spec, MediaSpec.decode(store.getValue(k)))
        assertEquals(600_000L, CopyHealth.decode(store["h|$k"])?.watchedMs)
        assertTrue(SourceLog.fp(k).matches(Regex("[0-9a-f]{8}")))
        // A copy of a release is still found under its release, and a copy's record never applies to it.
        val tz = parsed(Addon.TORZ, ep1, torz(hk, "Your.Friendly.Neighborhood.Spider-Man.S01E01.1080p.WEB.H264-LOW.mkv", 600_000_000L))
        val mfAnswer = parsed(Addon.MEDIAFUSION, ep1, mf(unclearName))
        val list = rows(ep1, mfAnswer, tz, measured = mapOf(ReleaseKey(hk, 0).storeKey to played(600_000_000L, 2.9), k to played(e1Bytes, 8.9)))
        assertEquals(600_000_000L, list.holding(hk).measured?.spec?.sizeBytes)
        assertEquals(e1Bytes, list.fromMf().measured?.spec?.sizeBytes)
        // Without its record, the copy is as the add-ons have it.
        val bare = rows(ep1, mfAnswer, tz, measured = mapOf(ReleaseKey(hk, 0).storeKey to played(600_000_000L, 2.9))).fromMf()
        assertNull(bare.measured)
        assertEquals("1080p · WEB · (18.9 GB)", line(bare))
    }

    // ---- (D2) Last used ----

    @Test
    fun aCopyOfNoInfoHashThatIsntTheWholeEpisodeIsntLastUsedAsTheListRanksAgain() {
        // The light Torz copy is the one kept as the episode's last.
        last = LastRelease(Addon.TORZ, ReleaseKey(hk, 0), now)
        val before = list()
        val light = before.sources.holding(hk)
        val mfRow = before.sources.fromMf()
        assertEquals(light.key, before.lastUsed?.key)
        fun play(): Pair<SourceSession, Opened.Ready> {
            val session = engine.session(PlayRequest(ep1, StartAt.Beginning, PlayOrigin.SOURCES_PANEL, list = before, chosen = mfRow))
            val got = ArrayList<Opened>()
            session.open(mfRow) { got += it }
            loop.advance()
            val ready = got.last() as Opened.Ready
            session.started(ready.source, ready.offer)
            engine.rerank(ep1)
            loop.advance()
            // Last used through ranks: no release of it is kept.
            assertEquals(mfRow.key, session.list!!.lastUsed?.key)
            assertEquals(ReleaseKey(hk, 0), last?.release)
            return session to ready
        }
        // Its length shows it isn't the whole episode: the list ranks again with the copy kept before it as Last used.
        val (session, ready) = play()
        session.notWhole(ready.source)
        loop.advance()
        assertEquals(light.key, session.list!!.lastUsed?.key)
        // Its picture again (the same copy retried) doesn't make it Last used either.
        session.started(ready.source, ready.offer)
        engine.rerank(ep1)
        loop.advance()
        assertEquals(light.key, session.list!!.lastUsed?.key)
        // Ended as not the whole episode: the same.
        val (other, again) = play()
        other.failed(again.source, Failure(FailureKind.NOT_WHOLE, Addon.MEDIAFUSION))
        loop.advance()
        assertEquals(light.key, other.list!!.lastUsed?.key)
        // A copy that is the whole episode stays Last used as the list ranks again.
        play()
        engine.rerank(ep1)
        loop.advance()
        assertEquals(mfRow.key, list().lastUsed?.key)
    }

    // ---- (E) Rows: only by the same size to the byte ----

    @Test
    fun aMeasuredCopyJoinsAnotherAddonsRowOnlyOfItsSizeToTheByte() {
        val mfAnswer = parsed(Addon.MEDIAFUSION, ep1, mf(unclearName))
        val k = mfAnswer.single().offer.copyKey!!
        // Another torrent's file of E1 to the byte as the play measured it, and one 4 KB larger.
        val same = parsed(Addon.TORZ, ep1, torz(hz, "Spider-Man.S01E01.1080p.WEB-X.mkv", e1Bytes))
        val other = parsed(Addon.TORZ, ep1, torz(hk, "Spider-Man.S01E01.1080p.WEB-Y.mkv", e1Bytes + 4096))
        // Never by its pack's size or an estimate: apart before it played.
        assertEquals(2, rows(ep1, mfAnswer, same).size)
        val measured = mapOf(k to played(e1Bytes, 8.9))
        for (order in listOf(listOf(mfAnswer, same), listOf(same, mfAnswer))) {
            val merged = SourceMerge.mergeKeyed(order, 28, episode = true, { 10 }, { measured[it]?.spec?.sizeBytes })
            val row = merged.sources.single()
            assertEquals(setOf(Addon.MEDIAFUSION, Addon.TORZ), row.addons.toSet())
            // Both add-ons, one file: no "×2".
            assertEquals(1, row.copies)
            // The row holding the copy, by its key, whichever add-on answered first.
            assertEquals(row.key, merged.rowOf[k])
        }
        assertEquals(2, rows(ep1, mfAnswer, other, measured = measured).size)
        // The file the play opened is the joined row's, and never the other's ([FileSet]).
        val before = rows(ep1, mfAnswer, same, other)
        val mfBefore = before.fromMf()
        val playedHere = rows(ep1, mfAnswer, same, other, measured = measured)
        val joined = playedHere.fromMf()
        val files = FileSet(episode = true).add(joined, joined.offers.first { it.addon == Addon.MEDIAFUSION })
        assertTrue(files.holds(joined))
        assertTrue(files.holds(before.holding(hz)))
        assertFalse(files.holds(before.holding(hk)))
        // By its key alone, before any size was measured.
        assertTrue(FileSet(episode = true).add(mfBefore, mfBefore.offers.single()).holds(mfBefore))
        assertFalse(FileSet(episode = true).add(mfBefore, mfBefore.offers.single()).holds(before.holding(hz)))
    }
}
