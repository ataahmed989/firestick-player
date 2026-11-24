package app.fireplayer.vod.sources

import app.fireplayer.player.CopyHealth
import app.fireplayer.player.CopyLanguage
import app.fireplayer.player.MediaSpec
import app.fireplayer.vod.Addon
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.Measured
import app.fireplayer.vod.Offer
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.RankStep
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.ReleaseKey
import app.fireplayer.vod.SecretUrl
import app.fireplayer.vod.SoundFormat
import app.fireplayer.vod.SourceFacts
import app.fireplayer.vod.SourceGroup
import app.fireplayer.vod.TvCapabilities
import app.fireplayer.vod.TvFit
import app.fireplayer.vod.TvPlan
import app.fireplayer.vod.data.TmdbDetails
import app.fireplayer.vod.play.SourcesModel
import app.fireplayer.vod.play.TvPlanRules
import app.fireplayer.vod.sources.SourceFixtures.answer
import app.fireplayer.vod.sources.SourceFixtures.mediaFusion
import app.fireplayer.vod.sources.SourceFixtures.torrentio
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each rule of "Best for this TV" ([SourceRank]) on its own, with made-up copies (no links but ".invalid" ones). */
class RankRulesTest {

    /** A TV like the user's Q60R: HDR10 = HDR10+, then HLG; no Dolby Vision; DD and DD+ passed through; profile 7 as HDR10. */
    private val q60r = TvPlan(true, listOf(setOf(Hdr.HDR10, Hdr.HDR10_PLUS), setOf(Hdr.HLG)), 2160, setOf(SoundFormat.AC3, SoundFormat.EAC3),
        dv7AsHdr10 = true)
    private val dvTv = TvPlan(true, listOf(setOf(Hdr.DOLBY_VISION), setOf(Hdr.HDR10, Hdr.HDR10_PLUS), setOf(Hdr.HLG)), 2160, emptySet(),
        dv7AsHdr10 = true)

    private val day = 24 * 3600_000L
    private val now = 1_764_000_000_000L

    private fun input(
        plan: TvPlan = q60r,
        wanted: List<String> = listOf("en"),
        measured: (String) -> Measured? = { null },
        episode: Boolean = false,
        line: Double? = null,
        imaxAlways: Boolean = false,
        subtitles: String? = null,
    ) = RankInput(plan, wanted, measured, 120, episode, line, imaxAlways, subtitles, now)

    private fun src(
        key: String,
        height: Int? = 2160,
        hdr: Set<Hdr> = setOf(Hdr.HDR10),
        mbps: Double? = 60.0,
        origin: String? = "REMUX",
        dvProfile: Int? = null,
        langs: List<String> = emptyList(),
        subs: List<String> = emptyList(),
        audio: String? = null,
        channels: String? = null,
        size: Long? = 50_000_000_000L,
        bits: Int? = null,
        name: String = "Film.Name.2021.$key",
        hash: String? = null,
    ) = PlaybackSource(
        key = key,
        release = hash?.let { ReleaseKey(it, 0) },
        facts = SourceFacts(height = height, hdr = hdr, avgMbps = mbps, origin = origin, codec = "HEVC", dvProfile = dvProfile, bitDepth = bits,
            languages = langs, subs = subs, audio = audio, channels = channels, sizeBytes = size),
        offers = listOf(Offer(Addon.TORRENTIO, Readiness.PROBABLY_READY, name, link = SecretUrl.of("https://tio.invalid/$key")!!)),
        readiness = Readiness.PROBABLY_READY,
        group = SourceMerge.groupOf(SourceFacts(height = height), Readiness.PROBABLY_READY, false, false),
    )

    private fun keys(list: List<PlaybackSource>, input: RankInput = input()) = SourceRank.rank(list, input).map { it.key }

    private fun played(spec: MediaSpec, health: CopyHealth? = CopyHealth(watchedMs = 120_000, at = now)) = Measured(spec, health, now)

    // ---- Language: the film's own just above the viewer's dub of the same quality, for films and episodes alike ----

    @Test
    fun anEnglishFilmPushesDownOnlyCopiesKnownToLackEnglish() {
        val unstated = src("unstated")
        val english = src("english", langs = listOf("en"))
        val russian = src("russian", mbps = 70.0, langs = listOf("ru"), size = 70_000_000_000L)
        val hd = src("hd", 1080, mbps = 20.0, origin = "WEB-DL", langs = listOf("en"))
        // Saying English is only a hair better than saying nothing; a 1080p copy saying English doesn't beat the 4K.
        assertEquals(listOf("english", "unstated", "hd", "russian"), keys(listOf(russian, hd, unstated, english)))
    }

    @Test
    fun aForeignFilmsOwnLanguageSitsRightAboveTheBestDubOfTheSameQuality() {
        val wanted = CopyLanguage.wanted("ko", "en")
        assertEquals(listOf("ko", "en"), wanted)
        val k1 = src("k1", langs = listOf("ko"), size = 60_000_000_000L)
        val k2 = src("k2", langs = listOf("ko"), size = 58_000_000_000L)
        val dub = src("dub", langs = listOf("en"), size = 62_000_000_000L)
        val unknown = src("unknown", size = 61_000_000_000L)
        val french = src("french", langs = listOf("fr"), size = 70_000_000_000L)
        assertEquals(listOf("k1", "dub", "k2", "unknown", "french"), keys(listOf(french, unknown, dub, k2, k1), input(wanted = wanted)))
        // A dub with clearly more picture wins: the language only decides between copies of the same quality.
        val small = src("ko720", 720, mbps = 5.0, origin = "WEB-DL", langs = listOf("ko"))
        assertEquals(listOf("dub", "ko720"), keys(listOf(small, dub), input(wanted = wanted)))
        // What a play heard counts over what the add-on wrote.
        val heard = src("heard", size = 1L, hash = SourceFixtures.H1)
        val measured = { k: String -> if (k == "rd|${SourceFixtures.H1}|0") played(MediaSpec(audioLangs = "kor")) else null }
        assertEquals("heard", keys(listOf(dub, heard), input(wanted = wanted, measured = measured)).first())
        // English subtitles add a little: between two copies in the film's own language, the one with them.
        val withSubs = src("subs", langs = listOf("ko"), subs = listOf("en"), size = 40_000_000_000L)
        assertEquals("subs", keys(listOf(k1, withSubs), input(wanted = wanted, subtitles = "en")).first())
        assertEquals("k1", keys(listOf(k1, withSubs), input(wanted = wanted)).first())
    }

    @Test
    fun episodesFollowTheSameLanguageRulesAndAreNeverImax() {
        val wanted = CopyLanguage.wanted("es", "en")
        val english = src("en", langs = listOf("en"), size = 9_000_000_000L)
        val spanish = src("es", langs = listOf("es"), size = 8_000_000_000L)
        assertEquals(listOf("es", "en"), keys(listOf(english, spanish), input(wanted = wanted, episode = true)))
        // An episode whose name says IMAX: no IMAX group, no IMAX edge.
        val imax = src("imax", size = 9_000_000_000L, name = "Show.S01E01.IMAX.2160p")
        val plain = src("plain", size = 10_000_000_000L)
        assertEquals(listOf("plain", "imax"), keys(listOf(imax, plain), input(episode = true)))
        assertFalse(SourceRank.isImaxCopy(imax, film = false))
        assertEquals(SourcesModel.Section.UHD, SourcesModel.sectionOf(imax, film = false))
        // The same copies of a film: the IMAX one first, in its own group.
        assertEquals(listOf("imax", "plain"), keys(listOf(imax, plain)))
        assertEquals(SourcesModel.Section.IMAX, SourcesModel.sectionOf(imax))
    }

    // ---- IMAX ----

    @Test
    fun imaxBreaksATieAfterPictureHdrSourceAndHdr10PlusUnlessItIsAlwaysFirst() {
        val imax = src("imax", name = "Film.2021.IMAX.2160p.REMUX", size = 40_000_000_000L)
        val plus = src("plus", hdr = setOf(Hdr.HDR10_PLUS))
        val web = src("web", mbps = 20.0, origin = "WEB-DL", name = "Film.2021.IMAX.2160p.WEB-DL")
        assertEquals(listOf("plus", "imax", "web"), keys(listOf(web, imax, plus)))
        assertEquals(listOf("imax", "web", "plus"), keys(listOf(web, imax, plus), input(imaxAlways = true)))
        // Open matte is mostly a 16:9 picture: not IMAX.
        assertFalse(SourceRank.isImax("Film.2021.Open.Matte.2160p"))
        assertTrue(SourceRank.isImax("Film.2021.IMX.2160p"))
    }

    @Test
    fun aPlayMeasuring190MovesThatCopyAloneIntoTheImaxGroup() {
        val wide = src("wide", hash = SourceFixtures.H1)
        val sibling = src("sibling", hash = SourceFixtures.H2)
        fun measured(aspect: Float) = { k: String ->
            if (k == "rd|${SourceFixtures.H1}|0") played(MediaSpec(width = 3840, height = 2020, aspect = aspect)) else null
        }
        val ranked = SourceRank.rank(listOf(sibling, wide), input(measured = measured(1.90f)))
        assertEquals(listOf("wide", "sibling"), ranked.map { it.key })
        assertTrue(SourceRank.isImaxCopy(ranked[0], film = true))
        assertFalse(SourceRank.isImaxCopy(ranked[1], film = true))
        assertEquals(SourcesModel.Section.IMAX, SourcesModel.sectionOf(ranked[0]))
        assertEquals(SourcesModel.Section.UHD, SourcesModel.sectionOf(ranked[1]))
        // A 16:9 picture isn't IMAX; nor is an episode.
        assertFalse(SourceRank.isImaxCopy(SourceRank.rank(listOf(wide), input(measured = measured(1.78f))).single(), film = true))
        assertFalse(SourceRank.isImaxCopy(ranked[0], film = false))
    }

    // ---- Dolby Vision as this TV shows it ----

    @Test
    fun profile7PlaysAsHdr10WhereTheStickPlaysItsBaseLayerAndWarnsOnlyWhereNothingCan() {
        val p7 = src("p7", hdr = setOf(Hdr.DOLBY_VISION), dvProfile = 7)
        val hdr10 = src("hdr10")
        for (plan in listOf(q60r, q60r.copy(dv7AsHdr10 = null))) {
            val (fit, warning) = SourceRank.fit(p7.facts, plan, null)
            assertEquals(TvFit.LIKELY, fit)
            assertNull(warning)
            assertEquals(SourceRank.hdrRank(hdr10.facts, plan), SourceRank.hdrRank(p7.facts, plan))
        }
        assertEquals(TvFit.LIKELY_NOT to SourceRank.DV7_WORDS, SourceRank.fit(p7.facts, q60r.copy(dv7AsHdr10 = false), null))
        // A profile 7 file with HDR10+ inside is HDR10+ here.
        assertEquals(setOf(Hdr.HDR10_PLUS, Hdr.HDR10), SourceRank.shownHdr(p7.facts.copy(hdr = setOf(Hdr.DOLBY_VISION, Hdr.HDR10_PLUS)), q60r))
        // On a Dolby Vision TV, a Stick with single-layer decoders still shows profile 7 as HDR10: profile 8 goes first there.
        val p8 = src("p8", hdr = setOf(Hdr.DOLBY_VISION), dvProfile = 8)
        assertEquals(setOf(Hdr.HDR10), SourceRank.shownHdr(p7.facts, dvTv))
        assertEquals(listOf("p8", "p7"), keys(listOf(p7, p8), input(dvTv)))
    }

    @Test
    fun profile8IsItsHdr10PictureAndProfile5StillNeedsADolbyVisionTv() {
        val p8 = src("p8", hdr = setOf(Hdr.DOLBY_VISION, Hdr.HDR10_PLUS), dvProfile = 8)
        assertEquals(setOf(Hdr.HDR10_PLUS, Hdr.HDR10), SourceRank.shownHdr(p8.facts, q60r))
        assertEquals(TvFit.LIKELY, SourceRank.fit(p8.facts, q60r, null).first)
        val p5 = src("p5", hdr = setOf(Hdr.DOLBY_VISION), dvProfile = 5)
        assertEquals(TvFit.LIKELY_NOT to SourceRank.NEEDS_DV, SourceRank.fit(p5.facts, q60r, null))
        assertEquals(TvFit.LIKELY, SourceRank.fit(p5.facts, dvTv, null).first)
        // A file saying only "DV": warned until a play shows it on the plain decoder, then HDR10 and fine.
        val dv = src("dv", hdr = setOf(Hdr.DOLBY_VISION), hash = SourceFixtures.H1)
        assertEquals(SourceRank.NEEDS_DV, SourceRank.fit(dv.facts, q60r, null).second)
        val onHevc = played(MediaSpec(hdr = "Dolby Vision", dvProfile = 8, decoder = MediaSpec.Decoder.PLAIN))
        val ranked = SourceRank.rank(listOf(dv), input(measured = { onHevc })).single()
        assertEquals(TvFit.MEASURED_OK, ranked.fit)
        assertNull(ranked.warning)
        assertEquals(setOf(Hdr.HDR10), SourceRank.asPlayedHere(dv.facts, onHevc).hdr)
        // Profile 5 on a plain decoder isn't taken for HDR10.
        val p5Played = played(MediaSpec(dvProfile = 5, decoder = MediaSpec.Decoder.PLAIN))
        assertTrue(Hdr.DOLBY_VISION in SourceRank.asPlayedHere(p5.facts, p5Played).hdr)
    }

    // ---- Picture, source kind, HDR10+ ----

    @Test
    fun hdr10PlusComesAfterPictureAndSourceKindOnATvThatReportsIt() {
        val plusRemux = src("plusRemux", hdr = setOf(Hdr.HDR10_PLUS))
        val hdrRemux = src("hdrRemux")
        val plusWeb = src("plusWeb", hdr = setOf(Hdr.HDR10_PLUS), mbps = 25.0, origin = "WEB-DL")
        val hdrWeb = src("hdrWeb", mbps = 25.0, origin = "WEB-DL")
        assertEquals(listOf("plusRemux", "hdrRemux", "plusWeb", "hdrWeb"), keys(listOf(hdrWeb, plusWeb, hdrRemux, plusRemux)))
        // A TV without HDR10+: it shows HDR10+ as HDR10, so no edge for it either way.
        val hdr10Tv = TvPlans.automatic(TvCapabilities(setOf(Hdr.HDR10, Hdr.HLG), 3840, 2160, emptySet(), 1L))
        assertEquals(listOf(setOf(Hdr.HDR10), setOf(Hdr.HLG)), hdr10Tv.hdrOrder)
        assertEquals(setOf(Hdr.HDR10), SourceRank.shownHdr(plusRemux.facts, hdr10Tv))
        assertEquals(listOf("hdrRemux", "plusRemux"), keys(listOf(hdrRemux, plusRemux), input(hdr10Tv)))
        assertEquals(listOf("plusRemux", "hdrRemux"), keys(listOf(plusRemux, hdrRemux), input(hdr10Tv)))
        // A picture that played here without HDR10's brightness data goes after one with it.
        val blind = src("blind", hash = SourceFixtures.H1, size = 60_000_000_000L)
        val noInfo = played(MediaSpec(hdrInfo = false))
        assertEquals(listOf("hdrRemux", "blind"), keys(listOf(blind, hdrRemux), input(measured = { if (it.contains(SourceFixtures.H1)) noInfo else null })))
    }

    @Test
    fun a4kRemuxCountsUpToFortyMbpsAndATenBitNameNeverBeatsARemux() {
        // 40 GB "2160p.x265.10bit.HDR10+" against an 88 GB remux: more picture data for the remux now.
        val encode = src("encode", hdr = setOf(Hdr.HDR10_PLUS), mbps = 30.0, origin = "BluRay", bits = 10)
        val remux = src("remux", mbps = 68.0)
        assertEquals(listOf("remux", "encode"), keys(listOf(encode, remux)))
        // Even when both carry the most a 4K picture counts, the remux is first: source kind before HDR10+ and bit depth.
        val heavy = src("heavy", hdr = setOf(Hdr.HDR10_PLUS), mbps = 45.0, origin = "BluRay", bits = 10)
        assertEquals(listOf("remux", "heavy"), keys(listOf(heavy, remux)))
        // A 4K HEVC encode saying 10-bit and one saying nothing tie on it (both are 10-bit).
        val said = src("said", origin = "BluRay", bits = 10, size = 1L)
        val unsaid = src("unsaid", origin = "BluRay", size = 2L)
        assertEquals(listOf("unsaid", "said"), keys(listOf(said, unsaid)))
    }

    // ---- Played here, buffered here ----

    @Test
    fun aShortPlayDoesntDecideBestButSmoothBreaksATie() {
        val web = src("web", 1080, mbps = 10.0, origin = "WEB-DL", hash = SourceFixtures.H1)
        val remux = src("remux")
        val twin = src("twin", hash = SourceFixtures.H2, size = 1L)
        val measured = { k: String -> if (k.contains(SourceFixtures.H1) || k.contains(SourceFixtures.H2)) played(MediaSpec(width = 1920, height = 1080)) else null }
        val ranked = SourceRank.rank(listOf(web, remux), input(measured = measured))
        assertEquals(listOf("remux", "web"), ranked.map { it.key })
        assertEquals(TvFit.MEASURED_OK, ranked[1].fit)
        // Two of the same quality: the one that played smoothly here, though smaller.
        assertEquals(listOf("twin", "remux"), keys(listOf(remux, twin), input(measured = measured)))
    }

    @Test
    fun bufferedHereStopsCountingAfterAWeekOrOnAFastEnoughConnection() {
        val copy = src("copy", mbps = 50.0, origin = "BluRay")
        fun buffered(at: Long) = Measured(null, CopyHealth(watchedMs = 600_000, stalls = 12, stallMs = 120_000, at = at), null)
        assertEquals(TvFit.LIKELY_NOT to SourceRank.BUFFERED, SourceRank.fit(copy.facts, q60r, buffered(now - day), now = now))
        assertEquals(TvFit.LIKELY, SourceRank.fit(copy.facts, q60r, buffered(now - 8 * day), now = now).first)
        // 1.33 times its average arriving: it stops counting (67 for a 50 Mb/s copy).
        assertTrue(SourceRank.troubled(buffered(now - day), 50.0, 66.0, now))
        assertFalse(SourceRank.troubled(buffered(now - day), 50.0, 67.0, now))
        // Frames lost aren't the connection's: only time clears those.
        val choppy = Measured(null, CopyHealth(watchedMs = 20 * 60_000L, droppedFrames = 2_000, renderedFrames = 10_000, at = now - day), null)
        assertTrue(SourceRank.troubled(choppy, 50.0, 200.0, now))
        assertFalse(SourceRank.troubled(choppy, 50.0, 200.0, now + 7 * day))
        // A record from before the date was kept: judged by the connection only.
        assertTrue(SourceRank.troubled(buffered(0L), 50.0, null, now))
    }

    // ---- The connection ----

    @Test
    fun aRemuxNeedsHalfAgainItsAverageAndOthersAThirdMore() {
        assertTrue(LineRate.room(58.0, 87.0, remux = true))
        assertFalse(LineRate.room(58.0, 86.0, remux = true))
        assertTrue(LineRate.room(52.0, 67.7, remux = false))
        assertFalse(LineRate.room(52.0, 67.0, remux = false))
        assertTrue(LineRate.room(58.0, null, remux = true))
        val remux = src("remux", mbps = 58.0)
        val bluray = src("bluray", mbps = 52.0, origin = "BluRay")
        // No reading: the remux. An 80 Mb/s evening: the BluRay, and the Best line says why.
        assertEquals("remux", keys(listOf(bluray, remux)).first())
        val slow = input(line = 80.0)
        val ranked = SourceRank.rank(listOf(remux, bluray), slow)
        assertEquals(listOf("bluray", "remux"), ranked.map { it.key })
        assertEquals(TvFit.LIKELY, ranked[1].fit)
        assertEquals(SourceRank.CLOSE_TO_LINE, ranked[1].warning)
        val why = SourceRank.why(ranked, slow)!!
        assertEquals("remux", why.beatenKey)
        assertEquals(RankStep.ROOM, why.step)
        assertEquals("≈58 Mb/s needs more room than your ≈80 Mb/s connection", why.lacks)
        // Without a reading, the old note on heavy copies; with one that leaves room, none.
        assertEquals("High data rate", SourceRank.fit(src("big", mbps = 70.0).facts, q60r, null).second)
        assertNull(SourceRank.fit(src("big", mbps = 70.0).facts, q60r, null, lineMbps = 120.0).second)
    }

    // ---- Sound as heard ----

    @Test
    fun soundIsCappedAtWhatTheTvTakesAsItIs() {
        val ddpAtmos = src("ddpAtmos", audio = "DD+ Atmos", channels = "5.1", size = 3L)
        val ddp = src("ddp", audio = "DD+", channels = "5.1", size = 2L)
        val trueHd = src("truehd", audio = "TrueHD Atmos", channels = "7.1", size = 1L)
        // This TV takes DD and DD+: they come first, and Atmos gives no edge (bigger first between them).
        assertEquals(listOf("ddpAtmos", "ddp", "truehd"), keys(listOf(trueHd, ddp, ddpAtmos)))
        // A receiver taking TrueHD and Atmos: lossless first, then DD+ Atmos.
        val receiver = q60r.copy(sound = setOf(SoundFormat.AC3, SoundFormat.EAC3, SoundFormat.EAC3_JOC, SoundFormat.TRUEHD))
        assertEquals(listOf("truehd", "ddpAtmos", "ddp"), keys(listOf(ddp, ddpAtmos, trueHd), input(receiver)))
    }

    // ---- The same file from two add-ons ----

    @Test
    fun theSameFileIsOneRowByExactSizeOrAfterAPlayByTheSizeMeasured() {
        val exact = 86_261_275_745L
        val mf = StreamParser.parse(Addon.MEDIAFUSION, answer(mediaFusion(SourceFixtures.H1, hdr = "DV|HDR10+", videoSize = exact,
            filename = "A.Film.2025.2160p.REMUX.DV.HDR10Plus-A.mkv")), 120).streams
        val mf2 = StreamParser.parse(Addon.MEDIAFUSION, answer(mediaFusion(SourceFixtures.H2, hdr = "HDR", videoSize = exact,
            filename = "Another.Name.2160p-B.mkv")), 120).streams
        // StremThru Torz gives exact sizes too.
        val torz = StreamParser.parse(Addon.TORZ, JSONObject().put("streams", org.json.JSONArray().put(JSONObject()
            .put("name", "⚡️ [TB] Torz\n2160p")
            .put("description", "📺 HDR\n📄 Another.Name.2160p-B.mkv")
            .put("url", "https://stremthru.example.org/stremio/torz/FAKECONFIG_TORZ/_/strem/tt0000001/tb/${SourceFixtures.H4}/0/Film.mkv")
            .put("behaviorHints", JSONObject().put("videoSize", exact).put("filename", "Another.Name.2160p-B.mkv")))).toString(), 120).streams
        val tio = StreamParser.parse(Addon.TORRENTIO, answer(torrentio(SourceFixtures.H3, quality = "4k HDR10+", size = "80.34 GB",
            name = "Film.2025.2160p.UHD.BD.Remux.HDR10+.TrueHD-C")), 120).streams
        // Two add-ons' exact sizes the same: one row, labels joined.
        val both = SourceMerge.merge(listOf(mf, torz), 120).single()
        assertEquals(setOf(Hdr.DOLBY_VISION, Hdr.HDR10_PLUS, Hdr.HDR10), both.facts.hdr)
        assertEquals(listOf(Addon.MEDIAFUSION, Addon.TORZ), both.addons)
        // One add-on's two files of one size stay apart (told apart by name, as before).
        assertEquals(2, SourceMerge.merge(listOf(mf, mf2), 120).size)
        // Torrentio's rounded size isn't exact: apart, until a play here measured its file's exact size.
        assertEquals(2, SourceMerge.merge(listOf(tio, mf), 120).size)
        val merged = SourceMerge.merge(listOf(tio, mf), 120) { if (it.infoHash == SourceFixtures.H3) exact else null }.single()
        assertTrue(merged.bothAddons)
        assertEquals(exact, merged.facts.sizeBytes)
        assertEquals(setOf(Hdr.HDR10_PLUS, Hdr.DOLBY_VISION), merged.facts.hdr)
        // Another size measured: apart.
        assertEquals(2, SourceMerge.merge(listOf(tio, mf), 120) { exact + 1 }.size)
    }

    // ---- Next source ----

    @Test
    fun nextSourceGoesDownTheListInItsOwnGroupFirst() {
        val a = src("a")
        val b = src("b")
        val c = src("c", 1080)
        val d = src("d", mbps = 20.0)
        val e = src("e", 1080, mbps = 10.0)
        val imax = src("imax", name = "Film.2021.IMAX.2160p")
        val list = listOf(imax, a, b, c, d, e)
        assertEquals(listOf("d", "c", "e", "a"), SourceRank.nextOrder(list, "b", film = true).map { it.key }.filter { it != "imax" })
        assertEquals(listOf("d", "c", "e", "a", "imax"), SourceRank.nextOrder(list, "b", film = true).map { it.key })
        // From the IMAX copy: no other IMAX copy, so down the list.
        assertEquals(listOf("a", "b", "c", "d", "e"), SourceRank.nextOrder(list, "imax", film = true).map { it.key })
        // Nothing playing: the list's order.
        assertEquals(list.map { it.key }, SourceRank.nextOrder(list, null, film = true).map { it.key })
        assertEquals(SourceGroup.THREE_D, SourceMerge.groupOf(SourceFacts(height = 1080), Readiness.PROBABLY_READY, cinema = false, threeD = true))
        assertEquals(SourceGroup.OTHER, SourceMerge.groupOf(SourceFacts(), Readiness.PROBABLY_READY, cinema = false, threeD = false))
    }

    // ---- What the TV reading and TMDB keep ----

    @Test
    fun theTvReadingKeepsWhatTheStickDoesWithProfile7AndOlderReadingsStillLoad() {
        val caps = TvCapabilities(setOf(Hdr.HDR10, Hdr.HLG, Hdr.HDR10_PLUS), 3840, 2160, setOf(SoundFormat.AC3), 5L, dv7AsHdr10 = true)
        assertEquals(caps, TvPlans.readCaps(TvPlans.writeCaps(caps)))
        assertEquals(caps.copy(dv7AsHdr10 = false), TvPlans.readCaps(TvPlans.writeCaps(caps.copy(dv7AsHdr10 = false))))
        val old = TvPlans.readCaps("HDR10,HLG,HDR10_PLUS;3840;2160;AC3;5")!!
        assertNull(old.dv7AsHdr10)
        assertEquals(true, TvPlans.automatic(caps).dv7AsHdr10)
        assertTrue(TvPlanRules.dolbyVisionNote(TvPlans.automatic(caps)).contains("profile 7 plays as its HDR10 picture"))
        assertTrue(TvPlanRules.dolbyVisionNote(TvPlans.automatic(caps)).contains("Profile 5 comes last"))
    }

    @Test
    fun tmdbsOriginalLanguageIsKeptWithItsDetails() {
        val details = TmdbDetails.parse(JSONObject().put("id", 7).put("original_language", "KO").toString(), app.fireplayer.vod.Kind.MOVIE)!!
        assertEquals("ko", details.originalLanguage)
        assertEquals("ko", TmdbDetails.read(TmdbDetails.write(details))?.originalLanguage)
        assertNull(TmdbDetails.read(TmdbDetails.write(details.copy(originalLanguage = null)))?.originalLanguage)
    }
}
