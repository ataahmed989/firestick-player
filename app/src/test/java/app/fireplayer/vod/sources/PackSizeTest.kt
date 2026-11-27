package app.fireplayer.vod.sources

import app.fireplayer.player.CopyHealth
import app.fireplayer.player.MediaSpec
import app.fireplayer.vod.Addon
import app.fireplayer.vod.AddonSave
import app.fireplayer.vod.AddonState
import app.fireplayer.vod.AddonSettings
import app.fireplayer.vod.EarlyOpen
import app.fireplayer.vod.EpisodeRef
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
import app.fireplayer.vod.TvPlan
import app.fireplayer.vod.play.MeasuredRerank
import app.fireplayer.vod.play.PlayRules
import app.fireplayer.vod.play.SourcesModel
import app.fireplayer.vod.ui.VodSizes
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
 * An episode's MediaFusion size, which is its season pack's total or the episode's own, and what a play here measured of
 * the file (made-up hashes, ".invalid" links). The TV case: Spider-Man S1 E9 on the Fire TV Stick 4K Max, MediaFusion's
 * index-less copy of the Amen WEBRip's season pack (1,103,713,620 bytes; 10.2 GB the pack) beside MediaFusion's other
 * pack (28.8 GB, WEB-DL), played about 5 minutes and measured at 5.3 Mb/s, its picture 4.1 Mb/s.
 */
class PackSizeTest {

    private val show = Title("tt0000077", Kind.SERIES, "Your Friendly Neighborhood Spider-Man", runtime = "28 min")
    private val e9 = EpisodeRef(1, 9)
    private val ep9 = SourceTarget(show, e9)

    /** The Amen WEBRip's season pack. */
    private val ha = "a".repeat(40)
    /** A torrent of the Amen file alone (StremThru Torz). */
    private val hc = "c".repeat(40)
    /** The G66 WEB-DL's season pack (Torrentio and Torz). */
    private val hg = "6".repeat(40)
    /** Another season pack, 28.8 GB in all (MediaFusion). */
    private val hp = "b".repeat(40)
    private val hk = "e".repeat(40)
    private val hs = "f".repeat(40)
    private val hd = "d".repeat(40)

    private val amenBytes = 1_103_713_620L
    private val g66Bytes = 3_115_201_299L
    private val amenTorrent = "Your.Friendly.Neighborhood.Spider-Man.S01.2160p.WEBRip.x265.10bit.DV.HDR.DDP5.1-Amen"
    private val amenFile = "Your.Friendly.Neighborhood.Spider-Man.S01E09.2160p.WEBRip.x265.10bit.DV.HDR.DDP5.1-Amen.mkv"
    private val packTorrent = "Your.Friendly.Neighborhood.Spider-Man.S01.2160p.DSNP.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-PACK"
    private val g66Pack = "Your.Friendly.Neighborhood.Spider-Man.S01.2160p.DSNP.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-G66"
    private val g66File = "Your.Friendly.Neighborhood.Spider-Man.S01E09.2160p.DSNP.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-G66.mkv"
    private val kitsuneFile = "Your.Friendly.Neighborhood.Spider-Man.S01E09.2160p.DSNP.WEB-DL.DDP5.1.HDR.H.265-Kitsune.mkv"
    private val sdrFile = "Your.Friendly.Neighborhood.Spider-Man.S01E09.2160p.DSNP.WEB-DL.DDP5.1.H.265-SDR.mkv"

    /**
     * As MediaFusion writes an episode's stream: one size ([videoSize], [sizeWords]), no fileIdx unless [idx];
     * [file] as behaviorHints.filename (its file's name, or its torrent's when it doesn't index the file); [torrent] in
     * the description's "📂 torrent ┈➤ file" when given.
     */
    private fun mf(
        hash: String, file: String?, videoSize: Long?, sizeWords: String = "10.2 GB", origin: String = "WEBRip", torrent: String? = null,
        idx: Int? = null, hdr: String = "DV|HDR",
    ) = JSONObject().apply {
        put("name", "MediaFusion | ElfHosted 🧲 RD ⚡️ 4k")
        val folder = torrent?.let { "\n📂 $it" + (file?.let { f -> " ┈➤ $f" } ?: "") } ?: ""
        put("description", "🎨 $hdr 📺 $origin 🎞️ hevc 🎵 Dolby Digital Plus 🔊 5.1\n📦 $sizeWords 👤 12\n🌐 English$folder")
        put("url", "https://mf.invalid/streaming_provider/FAKECONFIG_MF/playback/realdebrid/$hash/1/9/x.mkv")
        idx?.let { put("fileIdx", it) }
        put("behaviorHints", JSONObject().apply {
            put("bingeGroup", "MediaFusion-|-ElfHosted-🎨 $hdr 📺 $origin 🎞️ hevc 🎵 Dolby Digital Plus-4K")
            file?.let { put("filename", it) }
            videoSize?.let { put("videoSize", it) }
        })
    }

    private fun torz(hash: String, idx: Int, file: String, videoSize: Long, origin: String = "WEB-DL", hdr: String = "DV HDR") = JSONObject().apply {
        put("name", "⚡️ [RD] Torz\n2160p")
        put("description", "💿 $origin 🎞️ HEVC\n📺 $hdr 🎧 DDP | 5.1\n💾 1 GB 👤 12\n📄 $file")
        put("url", "https://torz.invalid/stremio/torz/FAKECONFIG_TORZ/_/strem/tt0000077/rd/$hash/$idx/$file")
        put("behaviorHints", JSONObject().put("videoSize", videoSize).put("filename", file))
    }

    private fun parsed(addon: Addon, vararg streams: JSONObject) = StreamParser.parse(addon, answer(*streams), 28, episode = true).streams

    /** The Max on its TV: HDR10 (no Dolby Vision), DD and DD+ passed through. */
    private val plan = TvPlan(true, listOf(setOf(Hdr.HDR10, Hdr.HDR10_PLUS), setOf(Hdr.HLG)), 2160, setOf(SoundFormat.AC3, SoundFormat.EAC3),
        dv7AsHdr10 = true)
    private val now = 1_764_000_000_000L

    private fun input(measured: (String) -> Measured? = { null }, line: Double? = null) =
        RankInput(plan, listOf("en"), measured, 28, episode = true, lineMbps = line, now = now, episodeRef = e9)

    private fun rows(vararg answers: List<ParsedStream>, count: Int? = null, measured: Map<String, Measured> = emptyMap()): List<PlaybackSource> {
        val merged = SourceMerge.merge(answers.toList(), 28, episode = true, { count }) { r -> measured[r.measuredKey(e9)]?.spec?.sizeBytes }
        return SourceRank.rank(merged, input({ measured[it] }))
    }

    private fun line(s: PlaybackSource) = PlayRules.mainLine(s, film = false, plan = plan)
    private fun details(s: PlaybackSource) = SourcesModel.details(s, plan, film = false) { "Nov 25" }.toMap()
    private fun List<PlaybackSource>.holding(hash: String) = single { s -> s.offers.any { it.release?.infoHash == hash } }

    private val g66Played = Measured(MediaSpec(sizeBytes = g66Bytes, overallBps = 14_160_000, videoBps = 13_400_000,
        videoHow = MediaSpec.How.MEASURED, measuredSecs = 900, playedAt = now - 3_600_000), CopyHealth(watchedMs = 900_000, at = now), now)
    /** What Picture & sound showed after about 5 minutes of the Amen copy: 1.10 GB, 5.3 Mb/s, its picture 4.1 Mb/s. */
    private val amenPlayed = Measured(MediaSpec(width = 3840, height = 2160, videoCodec = "HEVC", sizeBytes = amenBytes,
        overallBps = 5_300_000, videoBps = 4_100_000, videoHow = MediaSpec.How.MEASURED, measuredSecs = 300, playedAt = now),
        CopyHealth(watchedMs = 300_000, at = now), now)

    // ---- (A) The TV case, through the engine: what a play measured reaches the row, and the list ranks again ----

    private val loop = FakeLoop()
    private val direct = Executor { it.run() }
    private val kept = HashMap<String, Measured>()
    private var seasonCount: Int? = 10
    private var countAsked = 0
    private var last: LastRelease? = null
    private val links = HashMap<Addon, SecretUrl>().apply {
        put(Addon.TORRENTIO, SecretUrl.of(SourceFixtures.TIO)!!)
        put(Addon.MEDIAFUSION, SecretUrl.of(SourceFixtures.MF)!!)
        put(Addon.TORZ, SecretUrl.of("https://torz.invalid/FAKECONFIG_TORZ")!!)
    }
    private val answerOf = HashMap<Addon, () -> String>().apply {
        put(Addon.TORRENTIO) {
            answer(
                torrentio(hg, idx = 8, quality = "4k DV | HDR", name = g66Pack, file = "$g66Pack/$g66File", size = "2.90 GB"),
                torrentio(hs, idx = 0, quality = "4k", name = sdrFile, size = "2.90 GB"),
                torrentio(hd, idx = 0, quality = "4k", name = sdrFile.replace("-SDR", "-SDR2"), size = "2.85 GB"),
            )
        }
        // The Amen pack named by its torrent (MediaFusion doesn't index its file), and the 28.8 GB pack.
        put(Addon.MEDIAFUSION) {
            answer(mf(ha, amenTorrent, 10_200_000_000L), mf(hp, packTorrent, 28_800_000_000L, "28.8 GB", origin = "WEB-DL"))
        }
        put(Addon.TORZ) {
            answer(
                torz(hg, 8, g66File, g66Bytes),
                torz(hk, 0, kitsuneFile, 3_120_000_000L, hdr = "HDR"),
                torz(hc, 0, amenFile, amenBytes, origin = "WEBRip"),
            )
        }
    }
    private val library = object : PlaybackLibrary {
        override fun lastRelease(title: Title, episode: EpisodeRef?) = last
        override fun recordRelease(title: Title, episode: EpisodeRef?, release: LastRelease) {
            last = release
        }
        override fun recordPlayback(title: Title, episode: EpisodeRef?, positionMs: Long, durationMs: Long, creditsAtMs: Long?, sceneAtMs: Long?) = Unit
    }
    private val tv = object : ThisTv {
        override fun capabilities(): TvCapabilities? = null
        override fun checkAgain(onDone: (TvCapabilities) -> Unit) = Unit
        override fun plan() = plan
        override fun setPlan(plan: TvPlan) = Unit
        override fun useAutomatic() = Unit
    }
    private val engine = DefaultSourcesRepository(
        addons = object : AddonSettings {
            override fun isSet(addon: Addon) = links.containsKey(addon)
            override fun summary(addon: Addon): String? = null
            override fun save(addon: Addon, link: String) = AddonSave.SAVED
            override fun remove(addon: Addon) = Unit
        },
        links = { links[it] },
        thisTv = tv,
        library = library,
        http = StreamsHttp { base, _, _, _ ->
            val addon = when (base.host) {
                "tio.invalid" -> Addon.TORRENTIO
                "mf.invalid" -> Addon.MEDIAFUSION
                else -> Addon.TORZ
            }
            answerOf[addon]?.invoke() ?: throw IOException("no answer set")
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
        seasonEpisodes = { title, season ->
            countAsked++
            if (title.id == show.id && season == 1) seasonCount else null
        },
        log = { logged += it },
    )
    private val logged = ArrayList<String>()

    private fun list(): SourceList {
        val got = ArrayList<SourceList>()
        engine.lookUp(ep9, LookupWhy.PLAY, null) { got += it }
        loop.advance()
        return got.last()
    }

    private fun open(session: SourceSession, source: PlaybackSource? = null, why: OpenWhy = OpenWhy.START): List<Opened> {
        val got = ArrayList<Opened>()
        session.open(source, why) { got += it }
        loop.advance()
        return got
    }

    private fun List<PlaybackSource>.fromMf(hash: String) = single { s -> s.offers.any { it.addon == Addon.MEDIAFUSION && it.release?.infoHash == hash } }

    @Test
    fun theTvCaseMediaFusionsPackCopyShowsWhatItsPlayMeasuredAndRanksAsStarved() {
        kept[ReleaseKey(hg, 8).storeKey] = g66Played
        val before = list()
        val amen = before.sources.fromMf(ha)
        val pack = before.sources.fromMf(hp)
        // MediaFusion's two season packs said the same on the TV ("4K · HDR10 · WEB", no size, the same place): told
        // apart now by their shares of the pack, ranked by them.
        assertEquals("4K · HDR10 · WEB · ≈1.0 GB (pack 10.2 GB)", line(amen))
        assertEquals("4K · HDR10 · WEB · ≈2.9 GB (pack 28.8 GB)", line(pack))
        assertNotEquals(line(amen), line(pack))
        // The season's count was read from what's stored of the show, once each rank holding a pack: not for Torrentio's
        // answer alone, then for MediaFusion's and Torz's.
        assertEquals(2, countAsked)
        // Picked in Sources and playing from MediaFusion's own offer (no index: kept for E9 only). The session picks the
        // chosen row itself here; the TV hands it in to open (aRowChosenInSourcesPlaysOnTheListItsPlayRanksAgain).
        val session = engine.session(PlayRequest(ep9, StartAt.Beginning, PlayOrigin.SOURCES_PANEL, list = before, chosen = amen))
        var told = 0
        session.setListener { told++ }
        val ready = open(session).last() as Opened.Ready
        assertEquals(Addon.MEDIAFUSION, ready.offer.addon)
        session.started(ready.source, ready.offer)
        // What the player keeps the play under (PlayerActivity.startSource), and the list it ranks again: once.
        val key = (ready.offer.release ?: ready.source.release)!!.measuredKey(ep9.episode)
        assertEquals("rd|$ha|-1|1:9", key)
        kept[key] = amenPlayed
        told = 0
        engine.rerank(ep9)
        loop.advance()
        // The Sources window open (the session's listener) has the new list.
        assertTrue(told > 0)
        val after = session.list!!
        val row = after.sources.fromMf(ha)
        assertEquals("4K · HDR10 · WEB · 1.1 GB (pack 10.2 GB)", line(row))
        assertEquals("1.10 GB · measured here (pack 10.2 GB)", details(row)["Size"])
        assertEquals("≈5 Mb/s average · measured here", details(row)["Data rate"])
        // The same file from Torz (a torrent of it alone, the same size to the byte) is its row now.
        assertEquals(setOf(Addon.MEDIAFUSION, Addon.TORZ), row.addons.toSet())
        // Starved at 4K (its picture 4.1 Mb/s): under every well-fed 4K copy, the SDR ones too.
        val fed = after.sources.filter { s -> s.key != row.key && (s.facts.height ?: 0) >= 2160 && s.offers.none { it.release?.infoHash == hp } }
        assertEquals(4, fed.size)
        for (s in fed) assertTrue("${line(s)} above", after.sources.indexOf(s) < after.sources.indexOf(row))
        // The other pack is as it was.
        assertEquals("4K · HDR10 · WEB · ≈2.9 GB (pack 28.8 GB)", line(after.sources.fromMf(hp)))
        // The Sources window says the same.
        val items = SourcesModel.items(after, now, setOf(SourcesModel.Section.UHD), playingKey = session.rowKey(ready.source))
        assertEquals("4K · HDR10 · WEB · 1.1 GB (pack 10.2 GB)", items.first { it.key == SourcesModel.PLAYING_KEY }.label)
    }

    /**
     * The TV's way in, which the test above missed: a row chosen in a Sources window is handed to the session to open
     * (at OK on the title page by [EarlyOpen], or by [app.fireplayer.vod.play.SourcePlayback.start]), not picked by it.
     * Such a play kept the list handed over at OK all through: the engine ranked the play's figures in, but the player's
     * Sources window, its Playing row and Next source never had them (the Amen row still "≈1.0 GB (pack 10.2 GB)" after 3
     * minutes measured, Torz's "1.1 GB" beside it). It watches the list now, as a pick of its own does.
     */
    @Test
    fun aRowChosenInSourcesPlaysOnTheListItsPlayRanksAgain() {
        val before = list()
        val amen = before.sources.fromMf(ha)
        assertEquals("4K · HDR10 · WEB · ≈1.0 GB (pack 10.2 GB)", line(amen))
        val request = PlayRequest(ep9, StartAt.Beginning, PlayOrigin.SOURCES_PANEL, list = before, chosen = amen)
        // As PlayHandoff starts it at OK, and the player takes it over.
        val early = EarlyOpen(engine.session(request), amen, 0L)
        val session = early.session
        val got = ArrayList<Opened>()
        early.attach { got += it }
        loop.advance()
        val ready = got.last() as Opened.Ready
        assertEquals(Addon.MEDIAFUSION, ready.offer.addon)
        session.started(ready.source, ready.offer)
        // What PlayerActivity keeps the play under, and its rank once the figures are first kept.
        val key = (ready.offer.release ?: ready.source.release)!!.measuredKey(ep9.episode)
        kept[key] = amenPlayed
        logged.clear()
        engine.rerank(ep9)
        loop.advance()
        val after = session.list!!
        val row = after.sources.fromMf(ha)
        assertEquals("4K · HDR10 · WEB · 1.1 GB (pack 10.2 GB)", line(row))
        // Torz's file of the same size to the byte is its row now.
        assertEquals(setOf(Addon.MEDIAFUSION, Addon.TORZ), row.addons.toSet())
        assertEquals(before.sources.size - 1, after.sources.size)
        // The Playing row is that row, as measured.
        val playing = session.rowKey(ready.source)
        assertEquals(row.key, playing)
        val items = SourcesModel.items(after, now, setOf(SourcesModel.Section.UHD), playingKey = playing)
        assertEquals("4K · HDR10 · WEB · 1.1 GB (pack 10.2 GB)", items.first { it.key == SourcesModel.PLAYING_KEY }.label)
        // Ranked by the figures: starved at 4K, under every well-fed 4K copy; Next source never names it.
        val fed = after.sources.filter { s -> s.key != row.key && (s.facts.height ?: 0) >= 2160 && s.offers.none { it.release?.infoHash == hp } }
        assertEquals(4, fed.size)
        for (s in fed) assertTrue("${line(s)} above", after.sources.indexOf(s) < after.sources.indexOf(row))
        assertNotEquals(row.key, session.nextPick()?.key)
        // The log says what the row was looked up by, as fingerprints: never a key, whose hash names the torrent.
        assertTrue(logged.toString(), logged.any { it.startsWith("rank: row ${SourceLog.fp(row.key)} measured=${SourceLog.fp(key)} tried=${SourceLog.fp(key)},") })
        assertTrue(logged.toString(), logged.none { ha in it || "rd|" in it })
        assertTrue(SourceLog.fp(key).matches(Regex("[0-9a-f]{8}")))
        // The same from the player's own Sources window: a row picked there plays on the list as it is now.
        val again = engine.session(request)
        again.open(amen, OpenWhy.USER) { }
        loop.advance()
        assertEquals("4K · HDR10 · WEB · 1.1 GB (pack 10.2 GB)", line(again.list!!.sources.fromMf(ha)))
    }

    /** A row not cached yet, fetched at the viewer's word, plays on the list as it is now too. */
    @Test
    fun aRowFetchedFromSourcesPlaysOnTheListItsPlayRanksAgain() {
        answerOf[Addon.TORRENTIO] = { answer(torrentio(hd, idx = 0, ready = false, quality = "4k", name = sdrFile, size = "2.85 GB")) }
        val before = list()
        val chosen = before.sources.single { s -> s.offers.any { it.release?.infoHash == hd } }
        val session = engine.session(PlayRequest(ep9, StartAt.Beginning, PlayOrigin.SOURCES_PANEL, list = before, chosen = chosen))
        session.fetch(chosen) { }
        loop.advance()
        kept[ReleaseKey(hd, 0).storeKey] = Measured(MediaSpec(sizeBytes = 3_550_000_123L, overallBps = 14_500_000, videoBps = 13_000_000,
            videoHow = MediaSpec.How.MEASURED, measuredSecs = 300, playedAt = now), null, now)
        engine.rerank(ep9)
        loop.advance()
        assertEquals("3.1 GB", PlayRules.sizeWords(SourceRank.asPlayedHere(chosen.facts, chosen.measured)))
        val played = session.list!!.sources.single { s -> s.key == chosen.key }
        assertEquals("3.6 GB", PlayRules.sizeWords(SourceRank.asPlayedHere(played.facts, played.measured)))
    }

    /**
     * Torrentio and Torz both list the Amen file in its season pack (index 8: one row), MediaFusion the pack with no
     * index, named by its torrent. Once a play measured MediaFusion's file to the byte as Torz gives it, the three are
     * one row, in any order of answers: Torrentio's name of the file (no size to the byte) never tells it apart from a
     * torrent's name, which is no file's. A file of another size stays apart.
     */
    @Test
    fun aTorrentsNameNeverTellsAFileApartFromItsOwnName() {
        val pack = parsed(Addon.MEDIAFUSION, mf(ha, amenTorrent, 10_200_000_000L))
        val tio = parsed(Addon.TORRENTIO, torrentio(ha, idx = 8, quality = "4k DV | HDR", name = amenTorrent, file = "$amenTorrent/$amenFile", size = "1.03 GB"))
        val tz = parsed(Addon.TORZ, torz(ha, 8, amenFile, amenBytes, origin = "WEBRip"))
        val measured = mapOf(ReleaseKey(ha, null).measuredKey(e9) to amenPlayed)
        for (order in listOf(listOf(tio, pack, tz), listOf(pack, tz, tio), listOf(tz, tio, pack))) {
            val before = rows(*order.toTypedArray(), count = 10)
            assertEquals(2, before.size)
            assertEquals("4K · HDR10 · WEB · ≈1.0 GB (pack 10.2 GB)", line(before.fromMf(ha)))
            val row = rows(*order.toTypedArray(), count = 10, measured = measured).single()
            assertEquals(setOf(Addon.MEDIAFUSION, Addon.TORRENTIO, Addon.TORZ), row.addons.toSet())
            assertEquals("4K · HDR10 · WEB · 1.1 GB (pack 10.2 GB)", line(row))
            // The file a play opened from MediaFusion is that row's (Next source, Sources' marks).
            val played = FileSet(episode = true) { r -> amenBytes.takeIf { r == ReleaseKey(ha, null) } }
                .add(row, row.offers.first { it.addon == Addon.MEDIAFUSION })
            assertTrue(played.holds(before.first { s -> s.offers.any { it.addon == Addon.TORZ } }))
        }
        // Torz's file of another size: another file, two rows, whatever the names say.
        val other = parsed(Addon.TORZ, torz(ha, 8, amenFile, amenBytes + 4096, origin = "WEBRip"))
        assertEquals(2, rows(tio, pack, other, count = 10, measured = measured).size)
        // Nor does an estimate ever join them.
        assertEquals(2, rows(tio, pack, tz, count = 10).size)
    }

    @Test
    fun aPlaysMeasuredFiguresRankTheListOncePerPlayWhateverAnEarlierPlayKept() {
        val r = MeasuredRerank()
        r.newPlay()
        assertTrue(r.kept(sized = true))
        assertFalse(r.kept(sized = true))
        // Played again (its record kept by the play before): its figures rank the list again, once.
        r.newPlay()
        assertTrue(r.kept(sized = true))
        assertFalse(r.kept(sized = true))
        // The picture's rate first, the file's size later: once for each.
        r.newPlay()
        assertTrue(r.kept(sized = false))
        assertFalse(r.kept(sized = false))
        assertTrue(r.kept(sized = true))
        assertFalse(r.kept(sized = true))
    }

    // ---- (B) What MediaFusion's one size is ----

    @Test
    fun theNamesSayPackOrOneEpisode() {
        fun kind(n: String) = PackWords.one(n).kind
        for (pack in listOf(
            "Show.S01.2160p.WEB-DL-G", "Show Season 1 2160p", "Show.Complete.Series.1080p", "Show.S01.Complete.2160p",
            "Show.S01E06-10.2160p", "Show.S01E06-E10.2160p", "Show.E01-E08.1080p", "Show.S01-S02.1080p", "Show Season 1-2 1080p",
        )) assertEquals(pack, PackWords.Kind.PACK, kind(pack))
        for (one in listOf("Show.S01E06.2160p.mkv", "Show.1x06.720p", "Show Episode 6 1080p", "Show.S01E06-720p.mkv", "Show.S01E06.10bit.mkv")) {
            assertEquals(one, PackWords.Kind.SINGLE, kind(one))
        }
        for (none in listOf("Show.2160p.WEBRip.DTS-HD.1920x1080-X", "Film.2021.2160p.x265.10bit")) assertEquals(none, PackWords.Kind.UNSAID, kind(none))
        assertEquals(5, PackWords.one("Show.S01E06-10.2160p").episodes)
        assertEquals(5, PackWords.one("Show.S01E06-E10.2160p").episodes)
        assertEquals(8, PackWords.one("Show.E01-E08.1080p").episodes)
        assertTrue(PackWords.one("Show.S01.2160p").oneSeason)
        assertFalse(PackWords.one("Show.S01-S02.1080p").oneSeason)
        assertFalse(PackWords.one("Show.Complete.Series").oneSeason)
        // A torrent's "S01" with its file's "S01E09": a pack's file.
        assertEquals(PackWords.Kind.PACK, PackWords.read(listOf("📂 Show.S01.2160p "), listOf("Show.S01E09.mkv")).kind)
        // Only a torrent's own name says one episode: a file's names its episode in a pack too.
        assertEquals(PackWords.Kind.SINGLE, PackWords.read(listOf("Show.S01E09.2160p", null)).kind)
        assertEquals(PackWords.Kind.UNSAID, PackWords.read(emptyList(), listOf("Show.S01E09.2160p.mkv", null)).kind)
        assertEquals(PackWords.Kind.UNSAID, PackWords.read(listOf(null, "")).kind)
    }

    @Test
    fun theNamesSayRangesSeasonsAndAnimesEpisodesAsTheyAre() {
        fun read(n: String) = PackWords.one(n)
        // Ranges of the "1x01" form and with "to", counted, before one episode is read in them.
        for ((name, count) in listOf(
            "La Casa de Papel 1x01-1x13 [1080p]" to 13, "Show.1x01-10.720p" to 10, "Show.S01E01.to.S01E10.1080p" to 10,
            "Show S01E01 to S01E10" to 10, "Show.E01.to.E08.1080p" to 8,
        )) {
            assertEquals(name, PackWords.Kind.PACK, read(name).kind)
            assertEquals(name, count, read(name).episodes)
            assertTrue(name, read(name).oneSeason)
        }
        // Anime's one episode, "S3 - 01": never a run of seasons, nor a season's pack.
        for (one in listOf("[SubsPlease] Kaguya-sama wa Kokurasetai S3 - 01 (1080p).mkv", "[Judas] Mushoku Tensei S2 - 05 [1080p].mkv")) {
            assertEquals(one, PackWords.Kind.SINGLE, read(one).kind)
        }
        // ...and its batch, a range.
        assertEquals(12, read("[Judas] Mushoku Tensei S2 - 01-12 [1080p]").episodes)
        // Seasons spaced around the dash still a run when both say "S"; several "Sxx" a run too.
        for (run in listOf("Show S01 - S02 1080p", "Show.S01-02.1080p", "Show.S01.S02.S03.1080p", "Show Seasons 1 & 2")) {
            assertEquals(run, PackWords.Kind.PACK, read(run).kind)
            assertFalse(run, read(run).oneSeason)
            assertNull(run, read(run).episodes)
        }
        assertTrue(read("Show.S01.2160p.S01.Extras").oneSeason)
        // A range across seasons: a pack of no count.
        for (across in listOf("Show.S01E01-S02E05.1080p", "Show.1x01-2x05.720p")) {
            assertEquals(across, PackWords.Kind.PACK, read(across).kind)
            assertNull(across, read(across).episodes)
            assertFalse(across, read(across).oneSeason)
        }
        // A resolution after a dash is no range.
        assertEquals(PackWords.Kind.SINGLE, read("Show.1x05-1080p").kind)
        assertEquals(PackWords.Kind.SINGLE, read("Show S2 - 05 - 1080p").kind)
    }

    @Test
    fun aPackWithARangeInItsNameIsSharedByItsEpisodes() {
        val name = "Your.Friendly.Neighborhood.Spider-Man.S01E06-10.2160p.WEB-DL.DDP5.1.HDR.H.265-RNG"
        // A 27.3 GB copy (130 Mb/s), a rate further from the preferred 60 Mb/s than the share's 9.5.
        val heavy = torz(hk, 0, kitsuneFile, 27_300_000_000L, hdr = "HDR")
        val list = rows(parsed(Addon.MEDIAFUSION, mf(ha, name, 10_000_000_000L, "10 GB", origin = "WEB-DL", hdr = "HDR")), parsed(Addon.TORZ, heavy),
            count = 10)
        val pack = list.fromMf(ha)
        // E06-10: five episodes, whatever the season counts.
        assertEquals(2_000_000_000L, pack.facts.sizeBytes)
        assertTrue(pack.facts.sizeEstimated)
        assertFalse(pack.facts.sizeExact)
        assertEquals(5, pack.facts.packEpisodes)
        assertEquals("4K · HDR10 · WEB · ≈2.0 GB (pack 10.0 GB)", line(pack))
        assertEquals("≈2.0 GB (estimated: pack 10.0 GB ÷ 5 episodes)", details(pack)["Size"])
        assertEquals("≈9 Mb/s average (estimated)", details(pack)["Data rate"])
        assertTrue(PlayRules.badges(pack).contains("≈2.0 GB (pack 10.0 GB)"))
        // Ranked by it as by a stated size: above the copy further off the preferred rate, which a size not known
        // never is ([SourceRank.keysOf]).
        assertEquals(listOf(pack.key, list.holding(hk).key), list.map { it.key })
    }

    @Test
    fun aSeasonPackIsSharedByItsSeasonsEpisodesWhenTheyreStoredElseItsSizeIsSaidAsThePacks() {
        val heavy = parsed(Addon.TORZ, torz(hk, 0, kitsuneFile, 27_300_000_000L, hdr = "HDR"))
        val mfAnswer = parsed(Addon.MEDIAFUSION, mf(ha, amenTorrent, 10_200_000_000L, hdr = "HDR"))
        val counted = rows(mfAnswer, heavy, count = 10).fromMf(ha)
        assertEquals(1_020_000_000L, counted.facts.sizeBytes)
        assertEquals("4K · HDR10 · WEB · ≈1.0 GB (pack 10.2 GB)", line(counted))
        assertEquals("≈1.0 GB (estimated: pack 10.2 GB ÷ 10 episodes)", details(counted)["Size"])
        // The season's count not stored: the pack's size, in parentheses, and ranked as a size not known (under every
        // copy of a known rate it ties with).
        val list = rows(mfAnswer, heavy, count = null)
        val uncounted = list.fromMf(ha)
        assertNull(uncounted.facts.sizeBytes)
        assertNull(uncounted.facts.avgMbps)
        assertTrue(uncounted.facts.torrentTotal)
        assertEquals("4K · HDR10 · WEB · (pack 10.2 GB)", line(uncounted))
        assertEquals("10.2 GB (pack)", details(uncounted)["Size"])
        assertNull(details(uncounted)["Data rate"])
        assertEquals(listOf(list.holding(hk).key, uncounted.key), list.map { it.key })
        // A run of seasons isn't shared by one season's count.
        val run = rows(parsed(Addon.MEDIAFUSION, mf(ha, "Show.S01-S02.2160p.WEBRip-Amen", 20_400_000_000L, "20.4 GB")), count = 10).single()
        assertNull(run.facts.sizeBytes)
        assertEquals("(pack 20.4 GB)", PlayRules.sizeWords(run.facts))
        // The count is asked once a list, and only for a pack.
        var asked = 0
        SourceMerge.merge(listOf(mfAnswer, parsed(Addon.MEDIAFUSION, mf(hp, packTorrent, 28_800_000_000L, "28.8 GB"))), 28, episode = true,
            { asked++; 10 })
        assertEquals(1, asked)
        SourceMerge.merge(listOf(heavy), 28, episode = true, { asked++; 10 })
        assertEquals(1, asked)
    }

    @Test
    fun aTorrentOfOneEpisodeKeepsItsSizeShownAndRankedAsAnyOther() {
        // Its torrent and its file name one episode: its size is its own, never shared out, whatever the season counts.
        val single = parsed(Addon.MEDIAFUSION, mf(hc, amenFile, amenBytes, "1.03 GB", torrent = amenFile.removeSuffix(".mkv")))
        val list = rows(single, parsed(Addon.TORZ, torz(hg, 8, g66File, g66Bytes)), count = 10)
        val row = list.fromMf(hc)
        assertEquals(amenBytes, row.facts.sizeBytes)
        assertFalse(row.facts.sizeEstimated)
        assertNull(row.facts.packBytes)
        assertFalse(row.facts.torrentTotal)
        assertEquals("4K · HDR10 · WEB · 1.1 GB", line(row))
        assertEquals("1.1 GB", details(row)["Size"])
        assertEquals("≈5 Mb/s average (from size)", details(row)["Data rate"])
        // Ranked as any copy of that rate: starved at 4K, under the G66 copy.
        assertEquals(listOf(list.holding(hg).key, row.key), list.map { it.key })
        // Stated, not to the byte: it tells no file apart...
        assertFalse(row.facts.sizeExact)
        assertNull(FileSet.exactSize(row))
        // ...but another add-on's file of its videoSize to the byte is the same file (another torrent, another name).
        val same = rows(single, parsed(Addon.TORZ, torz(hk, 0, "Other.Name.2160p.WEBRip-X.mkv", amenBytes, origin = "WEBRip")))
        assertEquals(1, same.size)
        assertEquals(setOf(Addon.MEDIAFUSION, Addon.TORZ), same.single().addons.toSet())
        assertTrue(same.single().facts.sizeExact)
        val other = rows(single, parsed(Addon.TORZ, torz(hk, 0, "Other.Name.2160p.WEBRip-X.mkv", amenBytes + 5_000_000, origin = "WEBRip")))
        assertEquals(2, other.size)
        // Named by its torrent alone (MediaFusion indexes no file of it) as one episode, nothing else said: its size as
        // stated too.
        val named = rows(parsed(Addon.MEDIAFUSION, mf(hc, amenFile.removeSuffix(".mkv"), amenBytes, "1.03 GB")), count = 10).single()
        assertEquals(amenBytes, named.facts.sizeBytes)
        assertEquals("1.1 GB", PlayRules.sizeWords(named.facts))
    }

    @Test
    fun aFilesNameNeverSaysItsTorrentIsOneEpisode() {
        // E01 of a 25 GB season pack only MediaFusion lists: its first file (index 0), named as the episode's file, the
        // pack's size. Not "25.0 GB" at 74 Mb/s ranked as the episode's: said in parentheses, ranked as a size not known.
        val e01 = "Show.S01E01.2160p.WEB-DL.DDP5.1.H.265-GRP.mkv"
        val heavy = parsed(Addon.TORZ, torz(hk, 0, kitsuneFile, 27_300_000_000L, hdr = "HDR"))
        for (idx in listOf(0, 3, null)) {
            val list = rows(parsed(Addon.MEDIAFUSION, mf(ha, e01, 25_000_000_000L, "25 GB", origin = "WEB-DL", idx = idx, hdr = "HDR")), heavy,
                count = 10)
            val row = list.fromMf(ha)
            assertNull("$idx", row.facts.sizeBytes)
            assertNull("$idx", row.facts.avgMbps)
            assertFalse("$idx", row.facts.sizeEstimated)
            assertEquals("$idx", "4K · HDR10 · WEB · (25.0 GB)", line(row))
            assertEquals("$idx", listOf(list.holding(hk).key, row.key), list.map { it.key })
        }
        // The 28.8 GB pack the same: never "28.8 GB" as the episode's.
        val pack = rows(parsed(Addon.MEDIAFUSION, mf(hp, "Show.S01E01.2160p.DSNP.WEB-DL-PACK.mkv", 28_800_000_000L, "28.8 GB", idx = 0))).single()
        assertEquals("(28.8 GB)", PlayRules.sizeWords(pack.facts))
        // With an index, even a name with no extension is the file's (MediaFusion names a file it indexes by its own).
        val bare = rows(parsed(Addon.MEDIAFUSION, mf(hp, "Show.S01E01.2160p.DSNP.WEB-DL-PACK", 28_800_000_000L, "28.8 GB", idx = 0))).single()
        assertEquals("(28.8 GB)", PlayRules.sizeWords(bare.facts))
        // Index-less and named by its torrent ("📂 torrent ┈➤ file" too) as one episode: its torrent is that episode.
        val torrentNamed = rows(parsed(Addon.MEDIAFUSION, mf(hc, e01, amenBytes, "1.03 GB", torrent = e01.removeSuffix(".mkv"), idx = 0))).single()
        assertEquals(amenBytes, torrentNamed.facts.sizeBytes)
        assertEquals("1.1 GB", PlayRules.sizeWords(torrentNamed.facts))
    }

    @Test
    fun aTorrentOfOneEpisodeBesideATextFileOrASampleIsNeverShared() {
        // "Show.S01E09.1080p.WEB.H264-GRP" = RARBG.txt, the episode's 1.50 GB file, a 60 MB sample: MediaFusion lists it
        // under index 1 (the text file is first), named as the file, of the torrent's 1.56 GB.
        val file = "Show.S01E09.1080p.WEB.H264-GRP.mkv"
        val torrent = "Show.S01E09.1080p.WEB.H264-GRP"
        val total = 1_560_000_000L
        val video = 1_500_000_000L
        val mfRow = parsed(Addon.MEDIAFUSION, mf(hs, file, total, "1.56 GB", origin = "WEB", idx = 1))
        // Alone: nothing says, so no guess ("(1.6 GB)"), never "≈156 MB (pack 1.6 GB)".
        val alone = rows(mfRow, count = 10).single()
        assertFalse(alone.facts.sizeEstimated)
        assertEquals("(1.6 GB)", PlayRules.sizeWords(alone.facts))
        // Torrentio's file of it under an index, of its stated size near the torrent's: one episode's, its size its own.
        val tio = parsed(Addon.TORRENTIO, torrentio(hs, idx = 1, quality = "1080p", name = torrent, file = "$torrent/$file", size = "1.40 GB"))
        val withTio = rows(mfRow, tio, count = 10).fromMf(hs)
        assertFalse(withTio.facts.sizeEstimated)
        assertNull(withTio.facts.packBytes)
        assertNotNull(withTio.facts.sizeBytes)
        // Torz's exact size for the video alone, 4% under the torrent's (its sample): the same.
        val tz = parsed(Addon.TORZ, torz(hs, 1, file, video, origin = "WEB", hdr = ""))
        val withTorz = rows(mfRow, tz, count = 10).fromMf(hs)
        assertFalse(withTorz.facts.sizeEstimated)
        assertNull(withTorz.facts.packBytes)
        // Torrentio's index never outweighs Torz's size near the torrent's.
        val both = rows(mfRow, tio, tz, count = 10).fromMf(hs)
        assertFalse(both.facts.sizeEstimated)
        assertNull(both.facts.packBytes)
        // Torrentio listing it with no file index: its size may be the torrent's, so it says nothing.
        val noIdx = parsed(Addon.TORRENTIO, torrentio(hs, idx = -1, quality = "1080p", name = torrent, size = "1.45 GB"))
        assertEquals("(1.6 GB)", PlayRules.sizeWords(rows(mfRow, noIdx, count = 10).fromMf(hs).facts))
        // Its torrent named as one episode while a size says pack (an extra as big as the episode): the pack's size, no
        // guess of the episode's.
        val named = parsed(Addon.MEDIAFUSION, mf(hs, torrent, 3_000_000_000L, "3.0 GB", origin = "WEB"))
        val extra = parsed(Addon.TORZ, torz(hs, 1, file, video, origin = "WEB", hdr = ""))
        val shown = rows(named, extra, count = 10).fromMf(hs)
        assertFalse(shown.facts.sizeEstimated)
        // (One row with Torz's file of its name: its size to the byte.)
        assertEquals(video, shown.facts.sizeBytes)
        val apart = rows(named, parsed(Addon.TORZ, torz(hs, 1, "Other.Cut.1080p.WEB.H264-X.mkv", video, origin = "WEB", hdr = "")), count = 10)
        assertEquals(2, apart.size)
        assertFalse(apart.fromMf(hs).facts.sizeEstimated)
        assertEquals("(pack 3.0 GB)", PlayRules.sizeWords(apart.fromMf(hs).facts))
    }

    @Test
    fun aSizeNothingTellsIsSaidInParenthesesAndRankedAsNotKnown() {
        // No name saying pack or episode, no other add-on of its torrent.
        val unsaid = parsed(Addon.MEDIAFUSION, mf(ha, "Spider-Man.2160p.WEBRip.DDP5.1-X.mkv", 10_200_000_000L, hdr = "HDR"))
        val list = rows(unsaid, parsed(Addon.TORZ, torz(hk, 0, kitsuneFile, 27_300_000_000L, hdr = "HDR")), count = 10)
        val row = list.fromMf(ha)
        assertNull(row.facts.sizeBytes)
        assertTrue(row.facts.packUnclear)
        assertTrue(row.facts.torrentTotal)
        assertEquals("4K · HDR10 · WEB · (10.2 GB)", line(row))
        assertEquals("10.2 GB (pack or episode)", details(row)["Size"])
        assertEquals(listOf(list.holding(hk).key, row.key), list.map { it.key })
        // Nor any name at all.
        val bare = rows(parsed(Addon.MEDIAFUSION, mf(ha, null, 10_200_000_000L))).single()
        assertEquals("(10.2 GB)", PlayRules.sizeWords(bare.facts))
    }

    @Test
    fun theOtherAddonsSayWhetherItsAPack() {
        val named = parsed(Addon.MEDIAFUSION, mf(ha, amenFile, 10_200_000_000L))
        // Torrentio lists its torrent's file under an index: a torrent of several files, its size the pack's. (Of another
        // name here, so the two stay apart.)
        val tio = parsed(Addon.TORRENTIO, torrentio(ha, idx = 8, quality = "4k DV | HDR", name = amenTorrent,
            file = "$amenTorrent/Another.Name.S01E09.2160p.WEBRip-Amen.mkv", size = "1.03 GB"))
        val apart = rows(named, tio, count = 10)
        assertEquals(2, apart.size)
        assertEquals("≈1.0 GB (pack 10.2 GB)", PlayRules.sizeWords(apart.fromMf(ha).facts))
        // Of the same name, it is MediaFusion's file: one row, of Torrentio's size for the file (stated beats a share).
        val sameTio = parsed(Addon.TORRENTIO, torrentio(ha, idx = 8, quality = "4k DV | HDR", name = amenTorrent, file = "$amenTorrent/$amenFile",
            size = "1.03 GB"))
        val one = rows(named, sameTio, count = 10).single()
        assertEquals("1.1 GB", PlayRules.sizeWords(one.facts))
        assertFalse(one.facts.sizeEstimated)
        // Torz's file of the torrent is smaller to the byte: the same.
        val smaller = parsed(Addon.TORZ, torz(ha, 8, "Another.Name.2160p.WEBRip-Amen.mkv", amenBytes, origin = "WEBRip"))
        assertTrue(rows(named, smaller, count = 10).fromMf(ha).facts.sizeEstimated)
        // The same file by name from another torrent, smaller to the byte: the same.
        val byName = parsed(Addon.TORZ, torz(hc, 0, amenFile, amenBytes, origin = "WEBRip"))
        assertTrue(rows(named, byName, count = 10).fromMf(ha).facts.sizeEstimated)
        // Its own index past the first says nothing alone (a text file or a sample may come first): no guess.
        val indexed = rows(parsed(Addon.MEDIAFUSION, mf(ha, amenFile, 10_200_000_000L, idx = 8)), count = 10).single()
        assertFalse(indexed.facts.sizeEstimated)
        assertEquals("(10.2 GB)", PlayRules.sizeWords(indexed.facts))
        // Torrentio's file of the torrent near its size, under an index: not a pack (a torrent of one file and a sample).
        val near = parsed(Addon.TORRENTIO, torrentio(ha, idx = 8, quality = "4k DV | HDR", name = amenTorrent,
            file = "$amenTorrent/Another.Name.S01E09.2160p.WEBRip-Amen.mkv", size = "9.40 GB"))
        assertFalse(rows(named, near, count = 10).fromMf(ha).facts.sizeEstimated)
        // Torz's only file of the torrent, of its size to the byte: one episode's torrent, its size its own.
        val whole = parsed(Addon.MEDIAFUSION, mf(hc, "Spider-Man.2160p.WEBRip-X.mkv", amenBytes, "1.03 GB"))
        val alone = rows(whole, parsed(Addon.TORZ, torz(hc, 0, "Spider-Man.2160p.WEBRip-X.mkv", amenBytes, origin = "WEBRip")), count = 10)
        assertEquals(1, alone.size)
        assertEquals(amenBytes, alone.single().facts.sizeBytes)
        assertFalse(alone.single().facts.sizeEstimated)
        // A film is as before: MediaFusion's videoSize is its file's to the byte.
        val film = StreamParser.parse(Addon.MEDIAFUSION, answer(mf(ha, amenTorrent, 10_200_000_000L)), 120).streams.single()
        assertEquals(10_200_000_000L, film.facts.sizeBytes)
        assertTrue(film.exactSize)
        assertNull(film.facts.packBytes)
    }

    @Test
    fun aRowTooLongForItsRoomLeavesOutThePacksSizeNeverCutInAWord() {
        val chars = VodSizes.SOURCES_LINE_CHARS
        val list = rows(parsed(Addon.MEDIAFUSION, mf(ha, amenTorrent, 10_200_000_000L, hdr = "HDR")), count = 10)
        val row = list.single()
        assertEquals("4K · HDR10 · WEB · ≈1.0 GB (pack 10.2 GB)", PlayRules.mainLine(row, chars, film = false, plan = plan))
        // 46 characters: past the row's room, so the estimate goes alone (Details says the pack's).
        val remux = row.copy(facts = row.facts.copy(origin = "REMUX", hdr = setOf(Hdr.HDR10_PLUS), sizeBytes = 10_240_000_000L,
            packBytes = 102_400_000_000L))
        assertEquals("4K · HDR10+ · Remux · ≈10.2 GB", PlayRules.mainLine(remux, chars, film = false, plan = plan))
        assertEquals("≈10.2 GB (estimated: pack 102.4 GB ÷ 10 episodes)", details(remux)["Size"])
        // With room (the hold window's title, Details' own), all of it.
        assertEquals("4K · HDR10+ · Remux · ≈10.2 GB (pack 102.4 GB)", PlayRules.mainLine(remux, film = false, plan = plan))
        // The Sources window's rows as the line says.
        val listed = SourceList(ep9, mapOf(Addon.MEDIAFUSION to AddonState.Answered(1)), list, list.first(), plan = plan, wanted = listOf("en"))
        val labels = SourcesModel.items(listed, now, setOf(SourcesModel.Section.UHD), lineChars = chars).mapNotNull { it.source?.let { _ -> it.label } }
        assertTrue(labels.toString(), labels.all { it.length <= chars })
        assertTrue(labels.toString(), "4K · HDR10 · WEB · ≈1.0 GB (pack 10.2 GB)" in labels)
    }

    @Test
    fun anEstimateNeverMergesNorTellsFilesApart() {
        // MediaFusion's pack shared out to 1,020,000,000 bytes, and a Torz file of that size to the byte and of the
        // pack's own name, from another torrent: two rows, by neither the size rule nor the name rule.
        val pack = parsed(Addon.MEDIAFUSION, mf(ha, amenTorrent, 10_200_000_000L))
        val twin = parsed(Addon.TORZ, torz(hc, 0, amenTorrent, 1_020_000_000L, origin = "WEBRip"))
        val list = rows(pack, twin, count = 10)
        assertEquals(2, list.size)
        val mf = list.fromMf(ha)
        val tz = list.holding(hc)
        assertEquals(1_020_000_000L, mf.facts.sizeBytes)
        // Not a size to the byte: no file is told by it, and its offer carries none.
        assertNull(FileSet.exactSize(mf))
        assertNull(mf.offers.single().size)
        assertFalse(FileSet.of(mf, episode = true).holds(tz))
        assertFalse(FileSet.of(tz, episode = true).holds(mf))
        // Nor in its own torrent: Torz's index-less file of the pack (no size of its own) isn't told apart from it by it.
        assertNull(SourceMerge.samePackFile(null, null, 2160, 2160, null, null, null, null))
    }

    @Test
    fun whatAPlayMeasuredReplacesTheEstimate() {
        val pack = parsed(Addon.MEDIAFUSION, mf(ha, amenTorrent, 10_200_000_000L))
        val g66 = parsed(Addon.TORZ, torz(hg, 8, g66File, g66Bytes))
        val before = rows(pack, g66, count = 10)
        assertTrue(before.fromMf(ha).facts.sizeEstimated)
        val measured = mapOf(ReleaseKey(ha, null).measuredKey(e9) to amenPlayed)
        val after = rows(pack, g66, count = 10, measured = measured)
        val row = after.fromMf(ha)
        assertEquals(amenBytes, row.facts.sizeBytes)
        assertTrue(row.facts.sizeExact)
        assertFalse(row.facts.sizeEstimated)
        assertEquals(10_200_000_000L, row.facts.packBytes)
        assertEquals("4K · HDR10 · WEB · 1.1 GB (pack 10.2 GB)", line(row))
        assertEquals("1.10 GB · measured here (pack 10.2 GB)", details(row)["Size"])
        // As played here too, whatever the list had.
        val played = SourceRank.asPlayedHere(before.fromMf(ha).facts, amenPlayed)
        assertEquals("1.1 GB (pack 10.2 GB)", PlayRules.sizeWords(played))
        assertEquals(5.3, played.avgMbps!!, 0.01)
        // Its picture starved at 4K: under the G66 copy.
        assertEquals(listOf(after.holding(hg).key, row.key), after.map { it.key })
    }
}
