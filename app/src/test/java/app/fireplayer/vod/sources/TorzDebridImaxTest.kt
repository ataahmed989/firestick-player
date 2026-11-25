package app.fireplayer.vod.sources

import app.fireplayer.player.CopyHealth
import app.fireplayer.vod.Addon
import app.fireplayer.vod.AddonSave
import app.fireplayer.vod.FailureKind
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.LookupWhy
import app.fireplayer.vod.Measured
import app.fireplayer.vod.Offer
import app.fireplayer.vod.Opened
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.ReleaseKey
import app.fireplayer.vod.SecretUrl
import app.fireplayer.vod.SourceFacts
import app.fireplayer.vod.TvFit
import app.fireplayer.vod.TvPlan
import app.fireplayer.vod.sources.SourceFixtures.H1
import app.fireplayer.vod.sources.SourceFixtures.H2
import app.fireplayer.vod.sources.SourceFixtures.H3
import app.fireplayer.vod.sources.SourceFixtures.answer
import app.fireplayer.vod.sources.SourceFixtures.mediaFusion
import app.fireplayer.vod.sources.SourceFixtures.torrentio
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StremThru Torz (the third add-on), the debrid services besides Real-Debrid (TorBox…), 4K IMAX first, and the one log
 * line a start writes. Every link is made up (example.org, "FAKE…"): no real add-on link, key or stream link is used.
 */
class TorzDebridImaxTest {

    private val torzBase = "https://stremthru.example.org/stremio/torz/FAKECONFIG_TORZ"

    private fun torzLink(hash: String, idx: Int, store: String = "tb", file: String = "Film.mkv") =
        "$torzBase/_/strem/tt0000001/$store/$hash/$idx/$file"

    private val remux = "Film.Name.2021.IMAX.2160p.BluRay.REMUX.HEVC.DV.TrueHD.7.1.Atmos-FRAMESTOR.mkv"

    /** A Torz stream as its default template writes it. */
    private fun torz(
        hash: String,
        idx: Int = 3,
        cached: Boolean = true,
        resolution: String = "2160p",
        description: String = "💿 BluRay REMUX 🎞️ hevc\n📺 DV HDR10 🎧 TrueHD, Atmos | 7.1\n" +
            "💾 58.2 GB 📦 60.1 GB 〽️ 9.1 MB/s 👤 42\n🌐 🇬🇧 🇮🇹\n🏷️ IMAX ⚙️ FRAMESTOR 🔍 Torz\n📄 $remux",
        filename: String? = remux,
        url: String = torzLink(hash, idx),
    ): JSONObject = JSONObject().apply {
        put("name", (if (cached) "⚡️ [TB]" else "[TB]") + "\nTorz\n" + resolution)
        put("description", description)
        put("url", url)
        put("behaviorHints", JSONObject().apply {
            filename?.let { put("filename", it) }
            put("bingeGroup", "torz:$hash")
        })
    }

    // ---- 1. StremThru Torz ----

    @Test
    fun torzCachedStreamGivesItsFactsReleaseAndReadyMark() {
        val s = StreamParser.parse(Addon.TORZ, answer(torz(H1)), runtimeMin = 120).streams.single()
        assertEquals(Addon.TORZ, s.offer.addon)
        assertEquals(Readiness.PROBABLY_READY, s.offer.readiness)
        assertEquals(ReleaseKey(H1, 3), s.release)
        // The stored key's "rd" is only a label: the same for every add-on and debrid service.
        assertEquals("rd|$H1|3", s.release!!.storeKey)
        assertEquals(2160, s.facts.height)
        assertTrue(Hdr.DOLBY_VISION in s.facts.hdr && Hdr.HDR10 in s.facts.hdr)
        assertEquals("REMUX", s.facts.origin)
        assertEquals("HEVC", s.facts.codec)
        assertEquals("TrueHD Atmos", s.facts.audio)
        assertEquals("7.1", s.facts.channels)
        assertEquals((58.2 * (1L shl 30)).toLong(), s.facts.sizeBytes)
        assertEquals(42, s.facts.seeders)
        assertEquals(listOf("en", "it"), s.facts.languages)
        assertEquals("FRAMESTOR", s.facts.group)
        assertEquals(remux, s.offer.filename)
        assertEquals("torz:$H1", s.offer.bingeGroup)
        assertFalse(s.cinema)
        assertFalse(s.offer.text.contains("FAKECONFIG"))
        assertFalse(s.offer.toString().contains("FAKECONFIG"))
        assertTrue(s.offer.link.reveal().contains("FAKECONFIG"))
    }

    @Test
    fun torzWithoutTheLightningIsNotCachedAndAPrivateHashStillReads() {
        val row = torz(
            H2, cached = false, resolution = "1080p", filename = null,
            description = "📦 2.1 GB 👤 5\n📁 Film.Name.2021.1080p.WEB-DL.x264-GRP",
            url = torzLink("$H2-ZmFrZWxpbms", -1),
        )
        val s = StreamParser.parse(Addon.TORZ, answer(row), null).streams.single()
        assertEquals(Readiness.NOT_READY, s.offer.readiness)
        assertEquals(ReleaseKey(H2, null), s.release)
        assertEquals(1080, s.facts.height)
        assertEquals("WEB-DL", s.facts.origin)
        assertEquals("H.264", s.facts.codec)
        assertEquals("GRP", s.facts.group)
        assertNull(s.offer.filename)
        assertEquals("film name 2021 1080p web dl x264 grp", s.sameName)
    }

    @Test
    fun aTorzStreamInAnotherTemplateStillReadsTheStandardFields() {
        val row = JSONObject().apply {
            put("name", "Torz 4K")
            put("title", "Film.Name.2021.2160p.WEB-DL.DDP5.1.HEVC-GRP")
            put("url", "$torzBase/play/FAKE")
            put("infoHash", H3.uppercase())
            put("fileIdx", 2)
            put("behaviorHints", JSONObject().put("filename", "Film.Name.2021.2160p.WEB-DL.DDP5.1.HEVC-GRP.mkv"))
        }
        val s = StreamParser.parse(Addon.TORZ, answer(row), 120).streams.single()
        assertEquals(ReleaseKey(H3, 2), s.release)
        assertEquals(Readiness.UNKNOWN, s.offer.readiness)
        assertEquals(2160, s.facts.height)
        assertEquals("WEB-DL", s.facts.origin)
        assertEquals("DD+", s.facts.audio)
        assertEquals("5.1", s.facts.channels)
    }

    @Test
    fun stremThruNoticesAreNamedFailures() {
        fun kind(name: String) = Notices.classify("https://stremthru.example.org/v0/store/_/static/$name.mp4", Addon.TORZ)
        assertEquals(FailureKind.NOT_READY, kind("downloading")?.kind)
        assertEquals(Addon.TORZ, kind("downloading")?.addon)
        assertEquals("downloading", kind("downloading")?.detail)
        assertEquals(FailureKind.FILE, kind("download_failed")?.kind)
        assertEquals(FailureKind.FILE, kind("no_matching_file")?.kind)
        assertEquals(FailureKind.ACCOUNT, kind("401")?.kind)
        assertEquals(FailureKind.ACCOUNT, kind("payment_required")?.kind)
        assertEquals(FailureKind.LIMITS, kind("store_limit_exceeded")?.kind)
        assertEquals(FailureKind.RATE_LIMITED, kind("429")?.kind)
        assertEquals(FailureKind.FILE, kind("451")?.kind)
        assertTrue(Notices.isCopyright(kind("451")?.detail))
        assertEquals(FailureKind.UNKNOWN, kind("500")?.kind)
        // A notice in Torz's list is the strip's, not a source; with no add-on named, it is Torz's.
        val parsed = StreamParser.parse(Addon.TORZ, answer(torz(H1, url = "https://stremthru.example.org/v0/store/_/static/401.mp4")), null)
        assertTrue(parsed.streams.isEmpty())
        assertEquals(FailureKind.ACCOUNT, parsed.notice?.kind)
        assertEquals(Addon.TORZ, Notices.classify("https://stremthru.example.org/v0/store/_/static/429.mp4", null)?.addon)
    }

    @Test
    fun torzLinkCheckFollowsStremThruToTheDebridServiceOrItsNotice() {
        val link = SecretUrl.of(torzLink(H1, 3))!!
        val toCdn = LinkCheck.Hop { _, _, _ -> LinkCheck.Answer(302, "https://store-1.cdn.example.org/dl/FAKETOKEN/f.mkv", null) }
        val ready = LinkCheck(toCdn, { true }, { 0L }).check(Addon.TORZ, link, false) as LinkCheck.Result.Ready
        assertEquals("store-1.cdn.example.org", ready.link.host)
        val toNotice = LinkCheck.Hop { _, _, _ -> LinkCheck.Answer(302, "/v0/store/_/static/downloading.mp4", null) }
        val failed = LinkCheck(toNotice, { true }, { 0L }).check(Addon.TORZ, link, false) as LinkCheck.Result.Failed
        assertEquals(FailureKind.NOT_READY, failed.failure.kind)
        assertFalse(failed.failure.detail.orEmpty().contains("FAKE"))
    }

    @Test
    fun torzMergesWithTheOtherAddonsSameFileAndKeepsItsName() {
        val tio = StreamParser.parse(Addon.TORRENTIO, answer(torrentio(H1, idx = 3)), 120).streams
        val tz = StreamParser.parse(Addon.TORZ, answer(torz(H1, idx = 3)), 120).streams
        val row = SourceMerge.merge(listOf(tz, tio), 120).single()
        assertEquals(listOf(Addon.TORRENTIO, Addon.TORZ), row.offers.map { it.addon })
        assertTrue(row.bothAddons)
        assertEquals(Readiness.PROBABLY_READY, row.readiness)
        assertEquals("StremThru Torz", Addon.TORZ.label)
    }

    @Test
    fun torzHasAnAnswerLimitAndTheTorrentioGuard() {
        assertTrue(Addon.TORZ.answerLimitMs in 10_000L..25_000L)
        var now = 1_000_000L
        val b = Budget(object : Budget.Store {
            var text: String? = null
            override fun read() = text
            override fun write(text: String) {
                this.text = text
            }
        }) { now }
        repeat(Budget.TIO_AUTO) {
            assertTrue(b.mayAsk(Addon.TORZ, LookupWhy.PAGE))
            b.asked(Addon.TORZ, LookupWhy.PAGE)
            now += 1000
        }
        assertFalse(b.mayAsk(Addon.TORZ, LookupWhy.PAGE))
        assertTrue(b.mayAsk(Addon.TORZ, LookupWhy.PLAY))
        assertTrue(b.mayAsk(Addon.TORRENTIO, LookupWhy.PAGE))
    }

    // ---- Settings › Add-ons: Torz's link, stored like the others ----

    private class MemoryStore : AddonVault.Store {
        val values = HashMap<String, String>()
        override fun get(name: String) = values[name]
        override fun put(name: String, value: String?): Boolean {
            if (value == null) values.remove(name) else values[name] = value
            return true
        }
    }

    private object XorSealer : AddonVault.Sealer {
        override fun seal(plain: ByteArray) = ByteArray(plain.size) { (plain[it].toInt() xor 0x5a).toByte() }
        override fun open(sealed: ByteArray) = seal(sealed)
    }

    @Test
    fun torzLinkIsCheckedAndStoredSealedLikeTheOthers() {
        val store = MemoryStore()
        val vault = AddonVault(store, XorSealer) {}
        assertEquals(AddonSave.SAVED, vault.save(Addon.TORZ, " stremio://stremthru.example.org/stremio/torz/FAKECONFIG_TORZ/manifest.json "))
        assertEquals(torzBase, vault.link(Addon.TORZ)?.reveal())
        assertEquals("stremthru.example.org · …TORZ", vault.summary(Addon.TORZ))
        assertTrue(store.values.containsKey("link_torz"))
        assertTrue(store.values.values.none { it.contains("FAKECONFIG") || it.contains("example.org") })
        assertEquals(torzBase, AddonVault(store, XorSealer) {}.link(Addon.TORZ)?.reveal())
        // Another StremThru add-on, or another add-on's link, isn't Torz's; Torz's isn't theirs.
        assertEquals(AddonSave.NOT_THIS_ADDON, vault.save(Addon.TORZ, "https://stremthru.example.org/stremio/wrap/FAKE/manifest.json"))
        assertEquals(AddonSave.NOT_THIS_ADDON, vault.save(Addon.TORZ, "https://torrentio.example.org/FAKE/manifest.json"))
        assertEquals(AddonSave.NOT_THIS_ADDON, vault.save(Addon.TORRENTIO, "$torzBase/manifest.json"))
        assertEquals(AddonSave.NOT_THIS_ADDON, vault.save(Addon.MEDIAFUSION, "$torzBase/manifest.json"))
        assertEquals(AddonSave.NOT_A_LINK, vault.save(Addon.TORZ, "stremthru torz"))
        assertEquals(torzBase, vault.link(Addon.TORZ)?.reveal())
    }

    // ---- 2. Debrid services besides Real-Debrid ----

    private fun tioNamed(name: String) =
        StreamParser.parse(Addon.TORRENTIO, answer(torrentio(H1).put("name", "$name Torrentio\n4k DV | HDR")), 120).streams.single().offer.readiness

    @Test
    fun torrentioCachedMarksOfEveryDebridService() {
        for (code in listOf("RD", "TB", "AD", "PM", "DL", "ED", "OC")) {
            assertEquals(code, Readiness.PROBABLY_READY, tioNamed("[$code+]"))
            assertEquals(code, Readiness.NOT_READY, tioNamed("[$code download]"))
        }
        // Not a mark at the start: nothing said.
        assertEquals(Readiness.UNKNOWN, tioNamed("Torrentio [TB+]"))
        assertEquals(Readiness.UNKNOWN, tioNamed("[XX+]"))
    }

    @Test
    fun mediaFusionAndTorzLightningMarksWithTorBox() {
        fun mf(name: String) =
            StreamParser.parse(Addon.MEDIAFUSION, answer(mediaFusion(H2).put("name", name)), 120).streams.single().offer.readiness
        assertEquals(Readiness.PROBABLY_READY, mf("MediaFusion | ElfHosted 🧲 TB ⚡️ 4k"))
        assertEquals(Readiness.NOT_READY, mf("MediaFusion | ElfHosted 🧲 TB ⏳ 4k"))
        assertEquals(Readiness.PROBABLY_READY, mf("MediaFusion | ElfHosted 🧲 RD ⚡️ 4k"))
        fun tz(first: String) =
            StreamParser.parse(Addon.TORZ, answer(torz(H1).put("name", "$first\nTorz\n2160p")), 120).streams.single().offer.readiness
        assertEquals(Readiness.PROBABLY_READY, tz("✨ ⚡️ [TB] 🔑"))
        assertEquals(Readiness.PROBABLY_READY, tz("⚡️ [RD]"))
        assertEquals(Readiness.NOT_READY, tz("[RD]"))
    }

    @Test
    fun errorRowsAndFailureWordsNameNoOneService() {
        assertEquals(FailureKind.ACCOUNT, Notices.listError("Invalid TorBox token", Addon.TORRENTIO)?.kind)
        assertEquals(FailureKind.ACCOUNT, Notices.listError("Invalid RealDebrid ApiKey/Token!", Addon.TORRENTIO)?.kind)
        assertEquals(FailureKind.ACCOUNT, Notices.listError("Invalid AllDebrid key", Addon.TORRENTIO)?.kind)
        for (kind in FailureKind.entries) {
            val words = FailureWords.stop(kind)
            assertFalse(words, words.contains("real-debrid.com"))
            assertFalse(words, words.startsWith("Real-Debrid"))
        }
        assertTrue(FailureWords.stop(FailureKind.ACCOUNT).contains("TorBox"))
        for (words in listOf(FailureWords.NONE_READY, FailureWords.GUESSES_WRONG, FailureWords.FETCHING, FailureWords.NOTHING_FOUND)) {
            assertFalse(words, words.contains("Real-Debrid"))
        }
        assertTrue(FailureWords.NO_ADDONS.contains("StremThru Torz"))
        for (kind in listOf(FailureKind.ACCOUNT, FailureKind.OUTAGE)) {
            val strip = FailureWords.addonFailed(Addon.TORZ, app.fireplayer.vod.Failure(kind, Addon.TORZ))
            assertTrue(strip, strip.startsWith("StremThru Torz · "))
            assertFalse(strip, strip.contains("Real-Debrid"))
        }
    }

    // ---- 3. 4K IMAX first (Settings › This TV › IMAX first: always; otherwise a tie-break, RankRulesTest) ----

    private val tu7000 = TvPlan(true, listOf(setOf(Hdr.HDR10, Hdr.HDR10_PLUS), setOf(Hdr.HLG)), 2160, emptySet())
    private val fullHdTv = tu7000.copy(topHeight = 1080)
    private val troubled = CopyHealth(watchedMs = 600_000, stalls = 12, stallMs = 120_000)

    private fun input(plan: TvPlan = tu7000, measured: (String) -> Measured? = { null }, always: Boolean = true) =
        RankInput(plan, listOf("en"), measured, 120, episode = false, imaxAlways = always)

    private fun src(
        key: String,
        height: Int?,
        name: String,
        mbps: Double = 20.0,
        hdr: Set<Hdr> = setOf(Hdr.HDR10),
        dvProfile: Int? = null,
        origin: String = "WEB-DL",
        ready: Readiness = Readiness.PROBABLY_READY,
        hash: String? = null,
        filename: String? = null,
    ) = PlaybackSource(
        key = key,
        release = hash?.let { ReleaseKey(it, 0) },
        facts = SourceFacts(height = height, hdr = hdr, avgMbps = mbps, codec = "HEVC", dvProfile = dvProfile, origin = origin),
        offers = listOf(Offer(Addon.TORZ, ready, name, filename, link = SecretUrl.of("https://stremthru.example.org/$key")!!)),
        readiness = ready,
        group = SourceMerge.groupOf(SourceFacts(height = height), ready, false, false),
    )

    /** The same sources with the IMAX words taken out: today's order. */
    private fun plain(list: List<PlaybackSource>) = list.map { s ->
        s.copy(offers = s.offers.map { o -> o.copy(text = "Film.Name.2021", filename = null) })
    }

    private fun keys(list: List<PlaybackSource>, input: RankInput = input()) = SourceRank.rank(list, input).map { it.key }

    @Test
    fun a4kImaxThisTvPlaysRanksAboveEveryOtherSourceAndIsTheBest() {
        val remux = src("remux", 2160, "Film.Name.2021.2160p.BluRay.REMUX", mbps = 55.0, origin = "REMUX")
        val web = src("web", 2160, "Film.Name.2021.2160p.WEB-DL", mbps = 25.0)
        val imax = src("imax", 2160, "Film.Name.2021.IMAX.2160p.WEB-DL", mbps = 12.0)
        val hd = src("hd", 1080, "Film.Name.2021.1080p.BluRay", mbps = 20.0)
        val ranked = SourceRank.rank(listOf(remux, web, hd, imax), input())
        assertEquals("imax", ranked.first().key)
        assertEquals("imax", SourceRank.best(ranked)?.key)
        // Without the word, the same file ranks where it did.
        assertNotEquals("imax", keys(plain(listOf(remux, web, hd, imax))).first())
        // The others keep today's order.
        assertEquals(keys(plain(listOf(remux, web, hd))), ranked.drop(1).map { it.key })
    }

    @Test
    fun imaxAndImxAsWordsInAnyCaseInTheNameOrFileName() {
        val other = src("other", 2160, "Film.Name.2021.2160p.BluRay.REMUX", mbps = 55.0, origin = "REMUX")
        for (name in listOf("Film.2021.IMAX.2160p", "Film.2021.imx.2160p", "Film (2021) [IMAX] 2160p")) {
            assertEquals(name, "wish", keys(listOf(other, src("wish", 2160, name, mbps = 12.0))).first())
        }
        assertEquals("wish", keys(listOf(other, src("wish", 2160, "Film.2021.2160p", mbps = 12.0, filename = "Film.2021.IMAX.2160p.mkv"))).first())
        // Open matte is mostly a 16:9 picture: not IMAX (the user's stricter rule).
        for (name in listOf("Film.2021.SIMAX.2160p", "Film.2021.IMAXX.2160p", "Maximum.2021.2160p", "Film.2021.Opener.Matter.2160p",
            "Film 2021 Open Matte 2160p", "Film.2021.OpenMatte.2160p", "Film.2021.OPEN-MATTE.2160p")) {
            assertEquals(name, "other", keys(listOf(other, src("wish", 2160, name, mbps = 12.0))).first())
            assertFalse(name, SourceRank.isImax(name))
        }
    }

    @Test
    fun a1080pImaxDoesNotJump() {
        val list = listOf(
            src("uhd", 2160, "Film.Name.2021.2160p.WEB-DL", mbps = 25.0),
            src("hd", 1080, "Film.Name.2021.1080p.BluRay", mbps = 18.0, origin = "BluRay"),
            src("hdImax", 1080, "Film.Name.2021.IMAX.1080p.WEB-DL", mbps = 9.0),
        )
        assertEquals(keys(plain(list)), keys(list))
        assertFalse(SourceRank.rank(list, input()).first { it.key == "hdImax" }.let { SourceRank.imaxFirst(it) })
    }

    @Test
    fun a4kImaxThisTvCantPlayOrThatBufferedHereDoesNotJump() {
        val other = src("other", 2160, "Film.Name.2021.2160p.WEB-DL", mbps = 25.0)
        // Dolby Vision 7 on a Stick that can't play it as HDR10: "may not play here".
        val noRoute = input(tu7000.copy(dv7AsHdr10 = false))
        val p7 = src("p7", 2160, "Film.Name.2021.IMAX.2160p.DV", mbps = 30.0, hdr = setOf(Hdr.DOLBY_VISION, Hdr.HDR10), dvProfile = 7)
        assertEquals(keys(plain(listOf(other, p7)), noRoute), keys(listOf(other, p7), noRoute))
        assertEquals(TvFit.LIKELY_NOT, SourceRank.rank(listOf(p7), noRoute).single().fit)
        // A 1080p TV: no 4K source plays well, IMAX or not.
        val imax = src("imax", 2160, "Film.Name.2021.IMAX.2160p", mbps = 12.0, hash = H1)
        val hd = src("hd", 1080, "Film.Name.2021.1080p.BluRay", mbps = 12.0)
        assertEquals(keys(plain(listOf(hd, imax)), input(fullHdTv)), keys(listOf(hd, imax), input(fullHdTv)))
        assertEquals("hd", keys(listOf(hd, imax), input(fullHdTv)).first())
        // Marked troubled here (it buffered): not first.
        val buffered = input(measured = { if (it == ReleaseKey(H1, 0).storeKey) Measured(null, troubled, null) else null })
        assertEquals(keys(plain(listOf(other, imax)), buffered), keys(listOf(other, imax), buffered))
        assertEquals("other", keys(listOf(other, imax), buffered).first())
    }

    @Test
    fun imaxSourcesKeepTodaysOrderAmongThemselvesAndANotCachedOneIsNeverTheBest() {
        val a = src("a", 2160, "Film.Name.2021.IMAX.2160p.WEB-DL", mbps = 12.0)
        val b = src("b", 2160, "Film.Name.2021.IMAX.2160p.BluRay.REMUX", mbps = 50.0, origin = "REMUX")
        val c = src("c", 2160, "Film.Name.2021.Open.Matte.2160p.WEB-DL", mbps = 30.0)
        val other = src("other", 2160, "Film.Name.2021.2160p.BluRay.REMUX", mbps = 60.0, origin = "REMUX")
        val ranked = keys(listOf(other, a, b, c))
        assertEquals(keys(plain(listOf(a, b))), ranked.take(2))
        assertEquals(keys(plain(listOf(other, c))), ranked.drop(2))
        val notCached = src("nc", 2160, "Film.Name.2021.IMAX.2160p", mbps = 40.0, ready = Readiness.NOT_READY)
        assertEquals("other", SourceRank.best(SourceRank.rank(listOf(other, notCached), input()))?.key)
    }

    // ---- 4. The start's log line: add-on, cached or not, the stream's site only ----

    @Test
    fun theLogLineSaysAddonCachedAndTheSiteOnlyNeverTheLink() {
        val s = src("imax", 2160, "Film.Name.2021.IMAX.2160p")
        val link = SecretUrl.of("https://store-031.cdn.example.org/dl/FAKETOKEN/Film.mkv?token=FAKE&user=FAKEUSER")!!
        val line = SourceLog.line(Opened.Ready(s, s.offers.single(), link, null, false))
        assertEquals("Source: StremThru Torz · cached · streams from example.org", line)
        assertFalse(line.contains("FAKE"))
        assertFalse(line.contains("/") || line.contains("?") || line.contains("store-031"))
        val notCached = s.offers.single().copy(readiness = Readiness.NOT_READY, addon = Addon.TORRENTIO)
        val other = SourceLog.line(Opened.Ready(s, notCached, SecretUrl.of("https://stremthru.example.org/v0/proxy/FAKE")!!, null, false))
        assertEquals("Source: Torrentio · not cached (the debrid service fetches it) · streams from example.org (StremThru)", other)
    }

    @Test
    fun theSiteIsTheHostsLastLabelsWithTheServiceNamed() {
        // Hosts only (never a link): the debrid services' download hosts are named.
        assertEquals("tb-cdn.st (TorBox)", SourceLog.site("store-031.weur.tb-cdn.st"))
        assertEquals("real-debrid.com (Real-Debrid)", SourceLog.site("42.download.real-debrid.com"))
        assertEquals("torbox.app (TorBox)", SourceLog.site("cdn1.torbox.app"))
        assertEquals("elfhosted.com (ElfHosted)", SourceLog.site("someuser-mediafusion.elfhosted.com"))
        assertEquals("example.org (StremThru)", SourceLog.site("stremthru.example.org"))
        assertEquals("example.co.uk", SourceLog.site("a.b.example.co.uk"))
        assertEquals("example.org", SourceLog.site("example.org"))
        assertEquals("an IP address", SourceLog.site("203.0.113.7"))
        assertEquals("an unknown host", SourceLog.site(null))
    }
}
