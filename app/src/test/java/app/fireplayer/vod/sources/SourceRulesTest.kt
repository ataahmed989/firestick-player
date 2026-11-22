package app.fireplayer.vod.sources

import app.fireplayer.vod.Addon
import app.fireplayer.vod.AddonSave
import app.fireplayer.vod.FailureKind
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.LookupWhy
import app.fireplayer.vod.SecretUrl
import app.fireplayer.vod.SoundFormat
import app.fireplayer.vod.TvCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Failure table, stage 2, budgets, stage 4, the add-on vault and This TV. No real links: ".invalid" placeholders only. */
class SourceRulesTest {

    // ---- Notices and player errors ----

    @Test
    fun noticesAreClassifiedByTheTable() {
        fun kind(url: String) = Notices.classify(url, null)?.kind
        assertEquals(FailureKind.NOT_READY, kind("https://tio.invalid/videos/downloading_v3.mp4"))
        assertEquals(FailureKind.FILE, kind("https://tio.invalid/videos/failed_rar_v3.mp4"))
        assertEquals(FailureKind.FILE, kind("https://tio.invalid/videos/failed_too_big_v2.mp4"))
        assertEquals(FailureKind.ACCOUNT, kind("https://tio.invalid/videos/failed_access_v3.mp4"))
        assertEquals(FailureKind.LIMITS, kind("https://tio.invalid/videos/limits_exceeded_v2.mp4"))
        assertEquals(FailureKind.RATE_LIMITED, kind("https://tio.invalid/videos/failed_unavailable_v1.mp4"))
        assertEquals(FailureKind.UNKNOWN, kind("https://tio.invalid/videos/failed_unexpected_v3.mp4"))
        assertEquals(FailureKind.NOT_READY, kind("https://mf.invalid/static/exceptions/torrent_not_downloaded.mp4"))
        assertEquals(FailureKind.LIMITS, kind("https://mf.invalid/static/exceptions/torrent_limit.mp4"))
        assertEquals(FailureKind.CONFIG, kind("https://mf.invalid/static/exceptions/invalid_config.mp4"))
        assertEquals(FailureKind.OUTAGE, kind("https://mf.invalid/static/exceptions/debrid_service_down_error.mp4"))
        assertEquals(FailureKind.LINK_EXPIRED, kind("https://slate.elfhosted.com/slate.m3u8?title=Link%20expired&body=x"))
        assertEquals(FailureKind.NOT_READY, kind("https://slate.elfhosted.com/slate.m3u8?title=Still%20downloading"))
        assertEquals(FailureKind.CONFIG, kind("https://slate.elfhosted.com/slate.m3u8?title=Invalid%20Config"))
        assertEquals(FailureKind.LINK_EXPIRED, kind("https://slate.elfhosted.com/slate.m3u8?title=Opened%20on%20a%20different%20connection"))
        assertNull(kind("https://cdn.invalid/d/ABC/film.mkv"))
        assertEquals("downloading", Notices.classify("https://tio.invalid/videos/downloading_v3.mp4", Addon.TORRENTIO)?.detail)
        assertTrue(Notices.isCopyright("failed_infringement"))
        assertTrue(Notices.isCopyright("content_infringing"))
    }

    @Test
    fun playerErrorsMapToTheTable() {
        fun kind(code: Int, http: Int? = null, online: Boolean = true) = PlayerErrors.failureOf(code, http, online, null).kind
        assertEquals(FailureKind.LINK_EXPIRED, kind(2004, 403))
        assertEquals(FailureKind.LINK_EXPIRED, kind(2004, 410))
        assertEquals(FailureKind.RATE_LIMITED, kind(2004, 429))
        assertEquals(FailureKind.FILE, kind(2004, 451))
        assertEquals(FailureKind.UNKNOWN, kind(2004, 503))
        assertEquals(FailureKind.FILE, kind(3003))
        assertEquals(FailureKind.FORMAT, kind(4003))
        assertEquals(FailureKind.AUDIO, kind(5001))
        assertEquals(FailureKind.UNKNOWN, kind(2001))
        assertEquals(FailureKind.NO_INTERNET, kind(2001, online = false))
    }

    // ---- Stage 2 ----

    private class FakeHop(private val answers: Map<String, () -> LinkCheck.Answer>) : LinkCheck.Hop {
        val asked = ArrayList<String>()
        override fun get(url: String, timeoutMs: Int, cancel: CallHandle): LinkCheck.Answer {
            asked += url
            return (answers.entries.firstOrNull { url.startsWith(it.key) }?.value ?: error("unexpected"))()
        }
    }

    private val link = SecretUrl.of("https://tio.invalid/resolve/realdebrid/FAKEKEY/${"1".repeat(40)}/null/0/f.mkv")!!

    private fun check(hop: LinkCheck.Hop, confirmed: Boolean = false, online: Boolean = true) =
        LinkCheck(hop, { online }, { 0L }).check(Addon.TORRENTIO, link, confirmed)

    @Test
    fun aRedirectToAnotherHostIsTheFilmsLink() {
        val hop = FakeHop(mapOf("https://tio.invalid/" to { LinkCheck.Answer(302, "https://cdn.invalid/d/X/f.mkv", null) }))
        val r = check(hop) as LinkCheck.Result.Ready
        assertEquals("cdn.invalid", r.link.host)
        assertEquals(1, hop.asked.size)
    }

    @Test
    fun aRedirectToANoticeIsAFailure() {
        val hop = FakeHop(mapOf("https://tio.invalid/" to { LinkCheck.Answer(302, "/videos/downloading_v3.mp4", null) }))
        val r = check(hop) as LinkCheck.Result.Failed
        assertEquals(FailureKind.NOT_READY, r.failure.kind)
        assertFalse(r.failure.detail.orEmpty().contains("FAKEKEY"))
    }

    @Test
    fun elfHostedHopsAreFollowedUntilTheSlate() {
        val hop = FakeHop(
            mapOf(
                "https://tio.invalid/resolve" to { LinkCheck.Answer(302, "https://tio.invalid/next/hop", null) },
                "https://tio.invalid/next" to { LinkCheck.Answer(302, "https://slate.elfhosted.com/slate.m3u8?title=Not%20ready%20yet", null) },
            ),
        )
        assertEquals(FailureKind.NOT_READY, (check(hop) as LinkCheck.Result.Failed).failure.kind)
        assertEquals(2, hop.asked.size)
    }

    @Test
    fun timeoutsStatusesAndNetworkFailures() {
        val slow = LinkCheck.Hop { _, _, _ -> throw SocketTimeoutException() }
        assertEquals(FailureKind.NOT_READY, (check(slow) as LinkCheck.Result.Failed).failure.kind)
        assertEquals(FailureKind.SLOW, (check(slow, confirmed = true) as LinkCheck.Result.Failed).failure.kind)
        val busy = LinkCheck.Hop { _, _, _ -> LinkCheck.Answer(429, null, null) }
        assertEquals(FailureKind.RATE_LIMITED, (check(busy) as LinkCheck.Result.Failed).failure.kind)
        val down = LinkCheck.Hop { _, _, _ -> throw UnknownHostException("tio.invalid") }
        assertEquals(FailureKind.ADDON_DOWN, (check(down) as LinkCheck.Result.Failed).failure.kind)
        val offline = LinkCheck.Hop { _, _, _ -> throw IOException("no route") }
        assertEquals(FailureKind.NO_INTERNET, (check(offline, online = false) as LinkCheck.Result.Failed).failure.kind)
        val page = LinkCheck.Hop { _, _, _ -> LinkCheck.Answer(200, null, "text/html") }
        assertEquals(FailureKind.UNKNOWN, (check(page) as LinkCheck.Result.Failed).failure.kind)
        val film = LinkCheck.Hop { _, _, _ -> LinkCheck.Answer(200, null, "video/x-matroska") }
        assertTrue(check(film) is LinkCheck.Result.Ready)
    }

    // ---- Budgets ----

    private class MemoryTimes : Budget.Store {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) {
            this.text = text
        }
    }

    @Test
    fun mediaFusionEarlyAndAutomaticBudgets() {
        var now = 1_000_000L
        val store = MemoryTimes()
        val b = Budget(store) { now }
        repeat(Budget.MF_EARLY) {
            assertTrue(b.mayAsk(Addon.MEDIAFUSION, LookupWhy.PAGE))
            b.asked(Addon.MEDIAFUSION, LookupWhy.PAGE)
            now += 1000
        }
        assertFalse(b.mayAsk(Addon.MEDIAFUSION, LookupWhy.PAGE))
        assertTrue(b.mayAsk(Addon.MEDIAFUSION, LookupWhy.NEXT_EPISODE))
        repeat(Budget.MF_ALL - Budget.MF_EARLY) { b.asked(Addon.MEDIAFUSION, LookupWhy.PLAY) }
        assertFalse(b.mayAsk(Addon.MEDIAFUSION, LookupWhy.NEXT_EPISODE))
        // The user's own requests are never held back by the count.
        assertTrue(b.mayAsk(Addon.MEDIAFUSION, LookupWhy.PLAY))
        // A restart keeps the count.
        assertFalse(Budget(store) { now }.mayAsk(Addon.MEDIAFUSION, LookupWhy.PAGE))
        // An hour after the first ones, they fall out of the window.
        now += Budget.HOUR_MS
        assertTrue(b.mayAsk(Addon.MEDIAFUSION, LookupWhy.PAGE))
    }

    @Test
    fun busyPausesAndTorrentiosGuard() {
        var now = 5_000_000L
        val b = Budget(MemoryTimes()) { now }
        b.busy(Addon.MEDIAFUSION)
        assertFalse(b.mayAsk(Addon.MEDIAFUSION, LookupWhy.PLAY))
        assertTrue(b.busyUntil(Addon.MEDIAFUSION) > now)
        now += Budget.SKIP_MS
        assertTrue(b.mayAsk(Addon.MEDIAFUSION, LookupWhy.PLAY))
        assertFalse(b.mayAsk(Addon.MEDIAFUSION, LookupWhy.PAGE))
        now += Budget.NO_EARLY_MS
        assertTrue(b.mayAsk(Addon.MEDIAFUSION, LookupWhy.PAGE))
        repeat(Budget.TIO_AUTO) { b.asked(Addon.TORRENTIO, LookupWhy.PAGE) }
        assertFalse(b.mayAsk(Addon.TORRENTIO, LookupWhy.PAGE))
        assertTrue(b.mayAsk(Addon.TORRENTIO, LookupWhy.SOURCES))
        b.busy(Addon.TORRENTIO)
        assertFalse(b.mayAsk(Addon.TORRENTIO, LookupWhy.SOURCES))
    }

    // ---- Stage 4 ----

    @Test
    fun stallsCountOnlyAfterSettlingAndNotAfterJumps() {
        val w = Stalls()
        w.playing(0)
        // Within the first 30 s: doesn't count.
        w.buffering(10_000, true)
        w.buffering(35_000, false)
        assertFalse(w.shouldSwitch(35_000))
        // After a jump: doesn't count.
        w.jumped(100_000)
        w.buffering(105_000, true)
        w.buffering(130_000, false)
        assertFalse(w.shouldSwitch(130_000))
        // Three short ones within 2 minutes do.
        for (start in listOf(140_000L, 160_000L, 180_000L)) {
            w.buffering(start, true)
            w.buffering(start + 2_000, false)
        }
        assertTrue(w.shouldSwitch(185_000))
        // Two minutes later they've aged out.
        assertFalse(w.shouldSwitch(400_000))
    }

    @Test
    fun aStallTheSkipPreviewExcusedIsNeverCounted() {
        val w = Stalls()
        w.playing(0)
        // Three stalls in two minutes, each while the preview loaded beside the film: none counts.
        for (start in listOf(60_000L, 80_000L, 100_000L)) {
            w.buffering(start, true)
            w.excuse(start)
            w.buffering(start + 2_000, false)
        }
        assertFalse(w.shouldSwitch(105_000))
        // Nor one that goes on long past the limit.
        w.buffering(110_000, true)
        w.excuse(110_000)
        assertFalse(w.shouldSwitch(150_000))
        w.buffering(150_000, false)
        // An excuse with no stall under way changes nothing: the next one counts as ever.
        w.excuse(160_000)
        w.buffering(170_000, true)
        assertTrue(w.shouldSwitch(191_000))
    }

    @Test
    fun oneLongStallOrAQuarterOfTwoMinutesBuffering() {
        val w = Stalls()
        w.playing(0)
        w.buffering(60_000, true)
        assertFalse(w.shouldSwitch(75_000))
        assertTrue(w.shouldSwitch(81_000))
        val q = Stalls()
        q.playing(0)
        q.buffering(60_000, true)
        q.buffering(76_000, false)
        q.buffering(100_000, true)
        q.buffering(116_000, false)
        // 32 s of 120: over a quarter.
        assertTrue(q.shouldSwitch(120_000))
    }

    // ---- The vault ----

    @Test
    fun aTorrentioLinkWithPipesIsALink() {
        // Torrentio separates its options with "|", which java.net.URI refuses (a made-up key, not a real one).
        val pasted = "stremio://torrentio.strem.fun/providers=yts,eztv|sort=qualitysize|realdebrid=FAKEKEY123/manifest.json"
        val base = AddonVault.normalise(pasted)
        assertEquals("https://torrentio.strem.fun/providers=yts,eztv|sort=qualitysize|realdebrid=FAKEKEY123", base)
        assertEquals("torrentio.strem.fun", SecretUrl.of(base!!)!!.host)
        assertEquals(
            "https://torrentio.strem.fun/providers=yts,eztv%7Csort=qualitysize%7Crealdebrid=FAKEKEY123/stream/movie/tt0111161.json",
            app.fireplayer.vod.encodeLinkChars(base + "/stream/movie/tt0111161.json"),
        )
        // Already-encoded links stay as they are.
        assertEquals("torrentio.strem.fun", SecretUrl.of("https://torrentio.strem.fun/a%7Cb")!!.host)
    }

    private class MemoryStore : AddonVault.Store {
        val values = HashMap<String, String>()
        override fun get(name: String) = values[name]
        override fun put(name: String, value: String?): Boolean {
            if (value == null) values.remove(name) else values[name] = value
            return true
        }
    }

    /** A stand-in cipher for the JVM (the Keystore exists only on the device): reversible, never the plain text. */
    private object XorSealer : AddonVault.Sealer {
        override fun seal(plain: ByteArray) = ByteArray(plain.size) { (plain[it].toInt() xor 0x5a).toByte() }
        override fun open(sealed: ByteArray) = seal(sealed)
    }

    @Test
    fun vaultNormalisesStoresSealedAndSummarisesOnlyHostAndTail() {
        val store = MemoryStore()
        var changes = 0
        val vault = AddonVault(store, XorSealer) { changes++ }
        assertEquals(AddonSave.SAVED, vault.save(Addon.TORRENTIO, " stremio://tio.invalid/FAKEKEY_abcd/manifest.json "))
        assertEquals("https://tio.invalid/FAKEKEY_abcd", vault.link(Addon.TORRENTIO)?.reveal())
        assertEquals("tio.invalid · …abcd", vault.summary(Addon.TORRENTIO))
        assertTrue(store.values.values.none { it.contains("FAKEKEY") || it.contains("tio.invalid") })
        assertEquals(1, changes)
        // Saving the same again changes nothing; another vault reads it back.
        assertEquals(AddonSave.SAVED, vault.save(Addon.TORRENTIO, "https://tio.invalid/FAKEKEY_abcd/configure"))
        assertEquals(1, changes)
        assertEquals("https://tio.invalid/FAKEKEY_abcd", AddonVault(store, XorSealer) {}.link(Addon.TORRENTIO)?.reveal())
        assertEquals(AddonSave.NOT_A_LINK, vault.save(Addon.MEDIAFUSION, "not a link"))
        assertEquals(AddonSave.NOT_A_LINK, vault.save(Addon.MEDIAFUSION, "  "))
        assertEquals(AddonSave.NOT_THIS_ADDON, vault.save(Addon.MEDIAFUSION, "https://torrentio.invalid/FAKE"))
        assertFalse(vault.isSet(Addon.MEDIAFUSION))
        vault.remove(Addon.TORRENTIO)
        assertFalse(vault.isSet(Addon.TORRENTIO))
        assertEquals(2, changes)
        assertTrue(store.values.isEmpty())
    }

    @Test
    fun aVaultThatCantSealSavesNothing() {
        val refusing = object : AddonVault.Sealer {
            override fun seal(plain: ByteArray): ByteArray? = null
            override fun open(sealed: ByteArray): ByteArray? = null
        }
        val store = MemoryStore()
        assertEquals(AddonSave.COULD_NOT_STORE, AddonVault(store, refusing) {}.save(Addon.TORRENTIO, "https://tio.invalid/FAKE"))
        assertTrue(store.values.isEmpty())
    }

    // ---- This TV ----

    @Test
    fun automaticPlanFollowsTheTv() {
        val tu7000 = TvCapabilities(setOf(Hdr.HDR10, Hdr.HDR10_PLUS, Hdr.HLG), 3840, 2160, setOf(SoundFormat.AC3, SoundFormat.EAC3), 1L)
        val plan = TvPlans.automatic(tu7000)
        assertEquals(listOf(setOf(Hdr.HDR10, Hdr.HDR10_PLUS), setOf(Hdr.HLG)), plan.hdrOrder)
        assertEquals(2160, plan.topHeight)
        assertEquals(setOf(SoundFormat.AC3, SoundFormat.EAC3), plan.sound)
        // HDR off on the TV's input: SDR first.
        assertTrue(TvPlans.automatic(tu7000.copy(hdr = emptySet())).hdrOrder.isEmpty())
        val dv = TvPlans.automatic(tu7000.copy(hdr = setOf(Hdr.DOLBY_VISION, Hdr.HDR10), maxHeight = 1080))
        assertEquals(setOf(Hdr.DOLBY_VISION), dv.hdrOrder.first())
        assertEquals(1080, dv.topHeight)
        // Readings and the user's plan read back.
        assertEquals(tu7000, TvPlans.readCaps(TvPlans.writeCaps(tu7000)))
        val custom = plan.copy(automatic = false, maxMbps = 40)
        assertEquals(custom, TvPlans.readPlan(TvPlans.writePlan(custom)))
        assertNull(TvPlans.readPlan("junk"))
    }
}
