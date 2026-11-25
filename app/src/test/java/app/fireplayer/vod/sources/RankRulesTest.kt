package app.fireplayer.vod.sources

import app.fireplayer.player.CopyHealth
import app.fireplayer.player.CopyLanguage
import app.fireplayer.player.DvBaseLayer
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
import app.fireplayer.vod.AddonState
import app.fireplayer.vod.Kind
import app.fireplayer.vod.SourceList
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.Title
import app.fireplayer.vod.data.TmdbDetails
import app.fireplayer.vod.play.PlayRules
import app.fireplayer.vod.play.SourcesModel
import app.fireplayer.vod.play.TvPlanRules
import app.fireplayer.vod.sources.SourceFixtures.answer
import app.fireplayer.vod.sources.SourceFixtures.mediaFusion
import app.fireplayer.vod.sources.SourceFixtures.torrentio
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        /** Settings › This TV › HDR10+ reaches this TV: on here, as the order was decided; off by default. */
        plus: Boolean = true,
        imaxMove: (String) -> Boolean? = { null },
        /** Settings › Playback › Preferred bitrate. */
        preferred: Double = 60.0,
    ) = RankInput(plan.copy(hdr10PlusReaches = plus), wanted, measured, 120, episode, line, imaxAlways, subtitles, now, imaxMove, preferred)

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
        codec: String? = "HEVC",
    ) = PlaybackSource(
        key = key,
        release = hash?.let { ReleaseKey(it, 0) },
        facts = SourceFacts(height = height, hdr = hdr, avgMbps = mbps, origin = origin, codec = codec, dvProfile = dvProfile, bitDepth = bits,
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
        // Where HDR10+ doesn't reach the TV (the default), it's no edge: IMAX breaks the tie.
        assertEquals(listOf("imax", "plus", "web"), keys(listOf(web, imax, plus), input(plus = false)))
        // Open matte is mostly a 16:9 picture: not IMAX.
        assertFalse(SourceRank.isImax("Film.2021.Open.Matte.2160p"))
        assertTrue(SourceRank.isImax("Film.2021.IMX.2160p"))
    }

    @Test
    fun theViewersMoveDecidesTheImaxGroupOverTheNameForFilmsOnly() {
        val plain = src("plain", hash = SourceFixtures.H1)
        val sibling = src("sibling", hash = SourceFixtures.H2)
        val named = src("named", name = "Film.2021.IMAX.2160p.REMUX", hash = SourceFixtures.H3)
        // Moved to IMAX: that copy alone goes into 4K IMAX, and breaks the tie as IMAX does; moved to 4K: an IMAX
        // name counts for nothing, in the ranking too.
        val moves = mapOf("rd|${SourceFixtures.H1}|0" to true, "rd|${SourceFixtures.H3}|0" to false)
        val ranked = SourceRank.rank(listOf(sibling, named, plain), input(imaxMove = { moves[it] }))
        assertEquals(listOf("plain", "sibling", "named"), ranked.map { it.key })
        assertEquals(listOf(true, null, false), ranked.map { it.imaxMove })
        assertEquals(listOf(SourcesModel.Section.IMAX, SourcesModel.Section.UHD, SourcesModel.Section.UHD), ranked.map { SourcesModel.sectionOf(it) })
        assertEquals("4K IMAX", app.fireplayer.vod.play.PlayRules.quality(ranked[0]))
        assertEquals("4K", app.fireplayer.vod.play.PlayRules.quality(ranked[2]))
        // Not moved: the name decides, as before; IMAX first: always follows the move.
        assertEquals(listOf("named", "sibling"), keys(listOf(sibling, named)))
        assertEquals("plain", keys(listOf(named, plain), input(imaxAlways = true, imaxMove = { moves[it] })).first())
        // An episode is never moved (nor IMAX).
        val episode = SourceRank.rank(listOf(plain), input(episode = true, imaxMove = { moves[it] })).single()
        assertNull(episode.imaxMove)
        assertEquals(SourcesModel.Section.UHD, SourcesModel.sectionOf(episode, film = false))
        // Details offers the move for a film's 4K copy whose file is known, either way; nothing else.
        assertEquals(SourcesModel.TO_UHD, SourcesModel.moveLabel(ranked[0], film = true))
        assertEquals(SourcesModel.TO_IMAX, SourcesModel.moveLabel(ranked[2], film = true))
        assertEquals(SourcesModel.TO_UHD, SourcesModel.moveLabel(named, film = true))
        assertNull(SourcesModel.moveLabel(plain, film = false))
        assertNull(SourcesModel.moveLabel(src("hd", 1080, hash = SourceFixtures.H4), film = true))
        assertNull(SourcesModel.moveLabel(src("nofile"), film = true))
    }

    @Test
    fun aMoveIsKeptInTheRecordsByFilmAndFileNeverALink() {
        val records = app.fireplayer.data.MemoryPrefs()
        var flushed = 0
        val moves = ImaxMoves(records) { flushed++ }
        val copy = src("c", hash = SourceFixtures.H1)
        moves.save("tt0000001", copy, imax = true)
        assertEquals(mapOf("imax|tt0000001|rd|${SourceFixtures.H1}|0" to "1"), records.values)
        assertEquals(1, flushed)
        assertEquals(true, moves.of("tt0000001")("rd|${SourceFixtures.H1}|0"))
        assertNull(moves.of("tt0000002")("rd|${SourceFixtures.H1}|0"))
        moves.save("tt0000001", copy, imax = false)
        assertEquals(false, moves.of("tt0000001")("rd|${SourceFixtures.H1}|0"))
        assertTrue(records.values.values.none { (it as String).contains("http") })
        // A row with no known file keeps nothing.
        moves.save("tt0000001", src("nofile"), imax = true)
        assertEquals(1, records.values.size)
        assertNull(ImaxMoves.decode(null))
        assertNull(ImaxMoves.decode("x"))
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
        // Not where it doesn't reach the TV (Settings › This TV, off by default): the TV gets their HDR10, and the list
        // keeps its order.
        assertEquals(listOf("hdrRemux", "plusRemux"), keys(listOf(hdrRemux, plusRemux), input(plus = false)))
        assertEquals(listOf("plusRemux", "hdrRemux"), keys(listOf(plusRemux, hdrRemux), input(plus = false)))
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
    fun nextSourceGoesDownItsOwnGroupThenTheGroupsBelowThenAbove() {
        val a = src("a")
        val b = src("b")
        val c = src("c", 1080)
        val d = src("d", mbps = 20.0)
        val e = src("e", 1080, mbps = 10.0)
        val hd = src("hd", 720)
        val imax = src("imax", name = "Film.2021.IMAX.2160p")
        val list = listOf(imax, a, c, hd, b, e, d)
        fun next(from: String?, l: List<PlaybackSource> = list) = SourceRank.nextOrder(l, from, film = true).map { it.key }
        // Down 4K from b, then 1080p and 720p as listed, then what's above it: 4K's a, then 4K IMAX.
        assertEquals(listOf("d", "c", "e", "hd", "a", "imax"), next("b"))
        // From the IMAX copy: no other IMAX copy, so 4K, then 1080p, then 720p.
        assertEquals(listOf("a", "b", "d", "c", "e", "hd"), next("imax"))
        // From the bottom of 1080p: 720p, then 1080p's c above it, then the groups above, top first.
        assertEquals(listOf("hd", "c", "imax", "a", "b", "d"), next("e"))
        // Never one that showed no picture here, nor one that isn't picked by itself.
        val dark = d.copy(measured = played(MediaSpec(), CopyHealth(noPicture = 1)))
        val uncached = c.copy(readiness = Readiness.NOT_READY)
        assertEquals(listOf("e", "hd", "a", "imax"), next("b", listOf(imax, a, uncached, hd, b, e, dark)))
        // A copy moved into 4K IMAX is next from the IMAX copy, ahead of 4K.
        assertEquals(listOf("d", "a", "b"), next("imax", listOf(imax, a, b, d.copy(imaxMove = true))).take(3))
        // Nothing playing: the list's order.
        assertEquals(list.map { it.key }, next(null))
        assertEquals(SourceGroup.THREE_D, SourceMerge.groupOf(SourceFacts(height = 1080), Readiness.PROBABLY_READY, cinema = false, threeD = true))
        assertEquals(SourceGroup.OTHER, SourceMerge.groupOf(SourceFacts(), Readiness.PROBABLY_READY, cinema = false, threeD = false))
    }

    // ---- Preferred bitrate (Settings › Playback): a soft preference among copies of the same picture ----

    /** A 4K HDR10 remux at [mbps], its size what that is over the two hours the inputs say. */
    private fun at(key: String, mbps: Double, origin: String? = "REMUX", height: Int? = 2160, hdr: Set<Hdr> = setOf(Hdr.HDR10),
        codec: String? = "HEVC", name: String = "Film.Name.2021.$key", hash: String? = null, dvProfile: Int? = null) =
        src(key, height, hdr, mbps, origin, dvProfile = dvProfile, size = (mbps * 900_000_000L).toLong(), name = name, hash = hash, codec = codec)

    @Test
    fun ofTwoCopiesNearThePreferredBitrateTheHigherWinsUnlessTheConnectionKeepsLess() {
        val r64 = at("64", 64.0)
        val r56 = at("56", 56.0)
        assertEquals(57_600_000_000L, r64.facts.sizeBytes)
        assertEquals(listOf("64", "56"), keys(listOf(r56, r64)))
        assertEquals(listOf("64", "56"), keys(listOf(r64, r56)))
        // 59 against 64: the nearer; 54 against 72: the nearer, though lower; 54 against 67: 67 (5.6 weighted) is nearer.
        assertEquals(listOf("59", "64"), keys(listOf(r64, at("59", 59.0))))
        assertEquals(listOf("54", "72"), keys(listOf(at("72", 72.0), at("54", 54.0))))
        assertEquals(listOf("67", "54"), keys(listOf(at("54", 54.0), at("67", 67.0))))
        // 61 against 59: nearly as far, so they tie and the higher (bigger) one wins.
        assertEquals(listOf("61", "59"), keys(listOf(at("59", 59.0), at("61", 61.0))))
        // 64 against a 56 Mb/s remux of the same picture: the rate decides before the remux label.
        assertEquals(listOf("web64", "56"), keys(listOf(r56, at("web64", 64.0, origin = "WEB-DL"))))
        // A 75 Mb/s evening keeps about 57.7 with room: 56 is nearer that.
        val slow = input(line = 75.0)
        val ranked = SourceRank.rank(listOf(r64, r56), slow)
        assertEquals(listOf("56", "64"), ranked.map { it.key })
        // The rate stays the ranking's own: the Best line names no copy beaten on it.
        val why = SourceRank.why(ranked, slow)!!
        assertNull(why.beatenKey)
        assertNull(why.lacks)
    }

    @Test
    fun nearThePreferredBitrateWinsOverARemuxLabelOfTheSamePicture() {
        val web = at("web58", 58.0, origin = "WEB-DL")
        val remux = at("remux85", 85.0)
        val ranked = SourceRank.rank(listOf(remux, web), input())
        assertEquals(listOf("web58", "remux85"), ranked.map { it.key })
        // The remux beaten on the rate isn't named: no rate wording in the Best line.
        val why = SourceRank.why(ranked, input())!!
        assertNull(why.beatenKey)
        assertNull(why.lacks)
        // The viewer's own target moves it.
        assertEquals(listOf("remux85", "web58"), keys(listOf(web, remux), input(preferred = 85.0)))
        assertNull(SourceRank.why(SourceRank.rank(listOf(web, remux), input(preferred = 85.0)), input(preferred = 85.0))!!.beatenKey)
        // The setting's ends and default.
        assertEquals(60, SourceRank.PREFERRED_MBPS)
        assertEquals(20, SourceRank.preferredMbps(5))
        assertEquals(120, SourceRank.preferredMbps(500))
        assertEquals(65, SourceRank.preferredMbps(65))
    }

    @Test
    fun resolutionAndTheHdrTheTvGetsComeBeforeTheBitrateAndTheRemuxLabel() {
        // Case 1: a 4K HDR WEB-DL far from 60 against a 1080p SDR remux close to it.
        val web4k = at("web4k", 22.0, origin = "WEB-DL")
        val remux1080 = at("remux1080", 58.0, height = 1080, hdr = emptySet(), codec = "H.264")
        val ranked = SourceRank.rank(listOf(remux1080, web4k), input())
        assertEquals(listOf("web4k", "remux1080"), ranked.map { it.key })
        val why = SourceRank.why(ranked, input())!!
        assertEquals(RankStep.HEIGHT, why.step)
        assertEquals("lower resolution", why.lacks)
        // A 4K SDR remux at 58: HDR decides, before the rate.
        val sdr4k = at("sdr4k", 58.0, hdr = emptySet())
        val sdrRanked = SourceRank.rank(listOf(sdr4k, web4k), input())
        assertEquals(listOf("web4k", "sdr4k"), sdrRanked.map { it.key })
        assertEquals(RankStep.HDR, SourceRank.why(sdrRanked, input())!!.step)
    }

    @Test
    fun a4kCopyStarvedOfDataCountsJustUnderTheResolutionBelow() {
        val starved = at("starved", 6.0)
        val remux1080 = at("remux1080", 30.0, height = 1080, hdr = emptySet())
        val x265 = at("x265", 10.0, height = 1080, origin = "BluRay")
        assertEquals(listOf("remux1080", "starved"), keys(listOf(starved, remux1080)))
        val ranked = SourceRank.rank(listOf(starved, x265), input())
        assertEquals(listOf("x265", "starved"), ranked.map { it.key })
        val why = SourceRank.why(ranked, input())!!
        assertEquals(RankStep.HEIGHT, why.step)
        assertEquals("less picture data", why.lacks)
        // HEVC 4K needs about 8 Mb/s, H.264 about 12.7.
        assertEquals(listOf("fed", "x265"), keys(listOf(x265, at("fed", 9.0))))
        assertEquals(listOf("x265", "avc"), keys(listOf(at("avc", 11.0, codec = "H.264"), x265)))
        // Alone, it is still the Best, with no warning.
        val alone = SourceRank.rank(listOf(starved), input())
        assertEquals(TvFit.LIKELY, alone.single().fit)
        assertEquals("starved", SourceRank.best(alone)?.key)
    }

    @Test
    fun anUnknownBitrateIsNeitherNearNorFarAndKeepsItsResolution() {
        val near = at("near", 58.0)
        val unknown = src("unknown", mbps = null, size = null)
        val far = at("far", 85.0)
        assertEquals(listOf("near", "unknown", "far"), keys(listOf(far, unknown, near)))
        assertEquals(listOf("unknown", "hd"), keys(listOf(at("hd", 60.0, height = 1080), unknown)))
        assertEquals(TvFit.LIKELY to null, SourceRank.fit(unknown.facts, q60r, null, lineMbps = 30.0))
        assertTrue(LineRate.room(null, 30.0, remux = true))
        // An unknown one sits after copies within about 6 Mb/s (55, 67) and before those further off (53, 68).
        assertEquals(listOf("r67", "r55", "unknown", "r68", "r53"),
            keys(listOf(at("r53", 53.0), at("r68", 68.0), unknown, at("r55", 55.0), at("r67", 67.0))))
        // An unknown one beaten on the rate isn't named.
        val ranked = SourceRank.rank(listOf(unknown, near), input())
        assertEquals(listOf("near", "unknown"), ranked.map { it.key })
        assertNull(SourceRank.why(ranked, input())!!.beatenKey)
    }

    @Test
    fun withNoRuntimeTheSizeOverAnAssumedLengthGivesTheRate() {
        // An episode whose meta has no runtime (45 minutes assumed): a 2 GB 4K (about 5.9 Mb/s) is starved, after a 3 GB 1080p.
        val episode = RankInput(q60r, listOf("en"), { null }, null, episode = true, now = now)
        val starved = src("starved4k", mbps = null, origin = "WEB-DL", size = 2_000_000_000L)
        val hd = src("hd", 1080, mbps = null, origin = "WEB-DL", size = 3_000_000_000L)
        assertEquals(listOf("hd", "starved4k"), keys(listOf(starved, hd), episode))
        // A film (120 minutes assumed): 54 GB is 60 Mb/s, 72 GB 80; the preferred bitrate still decides, ranking only.
        val film = RankInput(q60r, listOf("en"), { null }, null, episode = false, now = now)
        val near = src("web60", mbps = null, origin = "WEB-DL", size = 54_000_000_000L)
        val far = src("remux80", mbps = null, size = 72_000_000_000L)
        assertEquals(listOf("web60", "remux80"), keys(listOf(far, near), film))
        assertEquals(TvFit.LIKELY to null, SourceRank.fit(far.facts, q60r, null))
    }

    @Test
    fun whenTheConnectionKeepsNeitherNearCopyASmootherLighterOneIsBest() {
        val r58 = at("r58", 58.0)
        val r64 = at("r64", 64.0)
        val enc = at("enc40", 40.0, origin = "BluRay")
        val web = at("web25", 25.0, origin = "WEB-DL")
        val line60 = input(line = 60.0)
        val ranked = SourceRank.rank(listOf(r64, r58, web, enc), line60)
        assertEquals(listOf("enc40", "web25", "r58", "r64"), ranked.map { it.key })
        assertEquals(TvFit.LIKELY_NOT, ranked[2].fit)
        assertEquals("Needs 58 Mb/s · this connection brought 60", ranked[2].warning)
        val why = SourceRank.why(ranked, line60)!!
        assertEquals("r58", why.beatenKey)
        assertEquals(RankStep.PLAYS, why.step)
        assertEquals("Needs 58 Mb/s · this connection brought 60", why.lacks)
        // Every copy too fast for a 50 Mb/s line: the one nearest what it keeps.
        assertEquals(listOf("a47", "b56", "r64"), keys(listOf(r64, at("b56", 56.0), at("a47", 47.0)), input(line = 50.0)))
    }

    // ---- Dolby Vision: the picture this TV gets ----

    @Test
    fun profile7Hdr10AndProfile8AtTheSameRateTieOnThePictureTheTvGets() {
        val p7 = src("p7", hdr = setOf(Hdr.DOLBY_VISION), dvProfile = 7, mbps = 58.0, size = 52_300_000_000L)
        val hdr10 = src("hdr10", mbps = 58.0, size = 52_200_000_000L)
        val p8 = src("p8", hdr = setOf(Hdr.DOLBY_VISION), dvProfile = 8, mbps = 58.0, size = 52_100_000_000L)
        val ranked = SourceRank.rank(listOf(p8, hdr10, p7), input())
        assertEquals(listOf("p7", "hdr10", "p8"), ranked.map { it.key })
        assertTrue(ranked.all { it.fit == TvFit.LIKELY && it.warning == null })
        assertEquals(SourceRank.hdrRank(hdr10.facts, q60r), SourceRank.hdrRank(p7.facts, q60r))
        assertEquals(SourceRank.hdrRank(hdr10.facts, q60r), SourceRank.hdrRank(p8.facts, q60r))
    }

    @Test
    fun aProfile8FileIsItsBaseLayerAsReadWhenItPlayed() {
        val hdr10 = src("hdr10", mbps = 58.0, size = 52_200_000_000L)
        val p8 = src("p8hlg", hdr = setOf(Hdr.DOLBY_VISION), dvProfile = 8, mbps = 58.0, size = 60_000_000_000L, hash = SourceFixtures.H1)
        fun playedAs(signal: Int) = played(MediaSpec(hdr = "Dolby Vision", dvProfile = 8, decoder = MediaSpec.Decoder.PLAIN, dvSignal = signal))
        val hlg = playedAs(DvBaseLayer.SIGNAL_HLG)
        assertEquals(setOf(Hdr.HLG), SourceRank.shownHdr(SourceRank.asPlayedHere(p8.facts, hlg), q60r))
        val ranked = SourceRank.rank(listOf(p8, hdr10), input(measured = { if (it.contains(SourceFixtures.H1)) hlg else null }))
        assertEquals(listOf("hdr10", "p8hlg"), ranked.map { it.key })
        assertEquals("HLG", PlayRules.tvHdr(ranked[1], q60r))
        assertEquals("HLG (Dolby Vision profile 8 file)", hlg.spec!!.rangeLine(hdrScreen = true))
        assertTrue(SourcesModel.details(ranked[1], q60r) { "" }.contains("Dolby Vision" to "Profile 8 · played here as HLG"))
        // Measured HDR10 (8.1): HDR10, as a native HDR10 copy.
        val pq = playedAs(DvBaseLayer.SIGNAL_HDR10)
        assertEquals(setOf(Hdr.HDR10), SourceRank.asPlayedHere(p8.facts, pq).hdr)
        assertEquals("HDR10 (Dolby Vision profile 8 file)", pq.spec!!.rangeLine(hdrScreen = true))
        assertEquals(listOf("p8hlg", "hdr10"), keys(listOf(hdr10, p8), input(measured = { if (it.contains(SourceFixtures.H1)) pq else null })))
        // Kept before its base was read: not said to be HDR10.
        assertEquals("Dolby Vision profile 8 file", playedAs(0).spec!!.rangeLine(hdrScreen = true))
    }

    @Test
    fun aProfile8NameSaysItsBaseAndABareOneIsntCalledHdr10UntilItPlays() {
        val named = Words.read("Film.2021.2160p.WEB-DL.DV.P8.4.HEVC")
        assertEquals(8, named.dvProfile)
        assertTrue(Hdr.HLG in named.hdr)
        assertTrue(Hdr.HDR10 in Words.read("Film.2021.2160p.DV.P8.1.HEVC").hdr)
        assertEquals(setOf(Hdr.DOLBY_VISION), Words.read("Film.2021.2160p.DV.P8.HEVC").hdr)
        assertEquals(7, Words.read("Film.2021.2160p.DV.P7.1080p.HEVC").dvProfile)
        // "DV HLG": its HLG, not a Dolby Vision-only file.
        val dvHlg = src("dvhlg", hdr = setOf(Hdr.DOLBY_VISION, Hdr.HLG))
        assertEquals(TvFit.LIKELY to null, SourceRank.fit(dvHlg.facts, q60r, null))
        assertEquals(setOf(Hdr.HLG), SourceRank.shownHdr(dvHlg.facts, q60r))
        // A bare profile 8: ranked as its likely HDR10, called "DV" until a play reads it.
        val bare = SourceRank.rank(listOf(src("bare", hdr = setOf(Hdr.DOLBY_VISION), dvProfile = 8)), input()).single()
        assertEquals(setOf(Hdr.HDR10), SourceRank.shownHdr(bare.facts, q60r))
        assertEquals("DV", PlayRules.tvHdr(bare, q60r))
        assertFalse(SourcesModel.details(bare, q60r) { "" }.any { it.first == "This TV shows" })
        assertEquals(setOf(Hdr.DOLBY_VISION), SourceRank.why(listOf(bare), input())!!.hdr)
    }

    // ---- Frames lost here: a copy that stuttered goes down, quietly ----

    private fun stuttering(at: Long, watched: Long = 5 * 60_000L, rendered: Long = 7_000, late: Long = 300) =
        Measured(MediaSpec(hdr = "HDR10", dvProfile = 7, dvBaseLayer = 7, decoder = MediaSpec.Decoder.PLAIN),
            CopyHealth(watchedMs = watched, droppedFrames = late, renderedFrames = rendered, lateFrames = late, at = at), now)

    @Test
    fun aCopyThatLostFramesHereLatelyGoesBelowOneOfTheSamePictureWithoutAWord() {
        val p7 = src("p7", hdr = setOf(Hdr.DOLBY_VISION), dvProfile = 7, mbps = 59.0, size = 53_100_000_000L, hash = SourceFixtures.H1)
        val other = at("other", 75.0)
        fun with(m: Measured) = input(measured = { if (it.contains(SourceFixtures.H1)) m else null })
        val shaky = with(stuttering(now - day))
        val ranked = SourceRank.rank(listOf(p7, other), shaky)
        assertEquals(listOf("other", "p7"), ranked.map { it.key })
        assertEquals(TvFit.MEASURED_OK, ranked[1].fit)
        assertNull(ranked[1].warning)
        assertNull(SourceRank.why(ranked, shaky)!!.beatenKey)
        // A week on, too short a watch, or 1 % lost: no penalty.
        assertEquals("p7", keys(listOf(other, p7), with(stuttering(now - 7 * day))).first())
        assertEquals("p7", keys(listOf(other, p7), with(stuttering(now - day, watched = 90_000))).first())
        assertEquals("p7", keys(listOf(other, p7), with(stuttering(now - day, late = 70))).first())
        // A later smooth two hours dilutes it away.
        val later = stuttering(now - day).let { it.copy(health = it.health!! + CopyHealth(watchedMs = 7_200_000, renderedFrames = 172_800, at = now)) }
        assertEquals("p7", keys(listOf(other, p7), with(later)).first())
        // IMAX first: always passes over one that stuttered.
        val imax = src("imax", mbps = 59.0, name = "Film.2021.IMAX.2160p.REMUX", hash = SourceFixtures.H1)
        assertEquals("other", keys(listOf(imax, other), input(imaxAlways = true, measured = { if (it.contains(SourceFixtures.H1)) stuttering(now - day) else null })).first())
    }

    @Test
    fun framesLostWithoutBufferingArentCalledBuffered() {
        val copy = src("copy", mbps = 50.0, origin = "BluRay")
        val choppy = Measured(null, CopyHealth(watchedMs = 20 * 60_000L, droppedFrames = 2_000, renderedFrames = 10_000, at = now - day), null)
        // A copy recorded as choppy (frames shown twice count too, and records kept before the decoder's share) still
        // goes down, quietly.
        assertEquals(TvFit.LIKELY to null, SourceRank.fit(copy.facts, q60r, choppy, now = now))
        assertTrue(SourceRank.stuttered(choppy, now))
        assertFalse(SourceRank.stuttered(choppy, now + 7 * day))
        val other = src("other", mbps = 50.0, origin = "BluRay", size = 40_000_000_000L)
        val shaky = SourceRank.rank(listOf(copy.copy(release = ReleaseKey(SourceFixtures.H1, 0)), other),
            input(measured = { if (it.contains(SourceFixtures.H1)) choppy else null }))
        assertEquals(listOf("other", "copy"), shaky.map { it.key })
        assertNull(shaky[1].warning)
        val buffered = Measured(null, CopyHealth(watchedMs = 600_000, stalls = 12, stallMs = 120_000, at = now - day), null)
        assertEquals(TvFit.LIKELY_NOT to SourceRank.BUFFERED, SourceRank.fit(copy.facts, q60r, buffered, now = now))
    }

    // ---- IMAX with the preferred bitrate ----

    @Test
    fun withImaxFirstOffBestMayComeFromThe4kGroupAndTheImaxGroupStaysFirst() {
        val imax = at("imax51", 51.0, name = "Film.2021.IMAX.2160p.REMUX")
        val remux = at("remux59", 59.0)
        val off = input(plus = false)
        val ranked = SourceRank.rank(listOf(imax, remux), off)
        assertEquals(listOf("remux59", "imax51"), ranked.map { it.key })
        // Beaten on the rate: the IMAX copy isn't named.
        val why = SourceRank.why(ranked, off)!!
        assertNull(why.beatenKey)
        val list = SourceList(SourceTarget(Title("tt0000001", Kind.MOVIE, "Film")), mapOf(Addon.TORRENTIO to AddonState.Answered(2)),
            ranked, SourceRank.best(ranked), null, why)
        val view = SourcesModel.view(list)
        assertEquals(SourcesModel.Section.IMAX, view.groups.first().section)
        assertEquals("remux59", view.best?.key)
        // As near 60 both (61 and 59): IMAX breaks the tie.
        assertEquals(listOf("imax61", "remux59"), keys(listOf(remux, at("imax61", 61.0, name = "Film.2021.IMAX.2160p.REMUX")), off))
        // IMAX first: always, and the viewer's move to 4K.
        assertEquals("imax51", keys(listOf(remux, imax), input(imaxAlways = true)).first())
        val movedOut = at("moved", 51.0, name = "Film.2021.IMAX.2160p.REMUX", hash = SourceFixtures.H2)
        val moved = SourceRank.rank(listOf(movedOut), input(imaxMove = { false })).single()
        assertEquals(SourcesModel.Section.UHD, SourcesModel.sectionOf(moved))
    }

    @Test
    fun episodesRankByTheSameSteps() {
        // 45 minutes: 9 GB is 26.7 Mb/s, 5 GB 14.8, 3 GB 8.9.
        assertEquals(26.7, StreamParser.avgMbps(9_000_000_000L, 45)!!, 0.05)
        val big = src("big", mbps = 26.7, origin = "WEB-DL", size = 9_000_000_000L)
        val small = src("small", mbps = 14.8, origin = "WEB-DL", size = 5_000_000_000L)
        val hd = src("hd", 1080, mbps = 8.9, origin = "WEB-DL", size = 3_000_000_000L)
        val ranked = SourceRank.rank(listOf(hd, small, big), input(episode = true))
        assertEquals(listOf("big", "small", "hd"), ranked.map { it.key })
        assertEquals(listOf("small", "hd"), SourceRank.nextOrder(ranked, "big", film = false).map { it.key })
        assertNotNull(SourceRank.why(ranked, input(episode = true)))
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
