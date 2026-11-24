package app.fireplayer.vod.sources

import app.fireplayer.player.CopyHealth
import app.fireplayer.player.MediaSpec
import app.fireplayer.vod.Addon
import app.fireplayer.vod.AddonState
import app.fireplayer.vod.Attempt
import app.fireplayer.vod.Failure
import app.fireplayer.vod.FailureKind
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.Kind
import app.fireplayer.vod.Measured
import app.fireplayer.vod.OpenWhy
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.RankStep
import app.fireplayer.vod.SoundFormat
import app.fireplayer.vod.SourceList
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.Title
import app.fireplayer.vod.TvCapabilities
import app.fireplayer.vod.TvFit
import app.fireplayer.vod.play.PlayRules
import app.fireplayer.vod.play.SourcesModel
import app.fireplayer.vod.play.SourcesModel.Section
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

/**
 * The measured Avatar: Fire and Ash list (the second Stick, a Samsung Q60R: HDR10, HDR10+ and HLG, no Dolby Vision, Dolby
 * Digital and Dolby Digital Plus passed through), its 21 rows as synthetic add-on answers (".invalid" links, made-up
 * hashes), ranked by the user's order. Before the test plays and after them (what the plays measured).
 */
class AvatarRankTest {

    private val runtime = 195

    /** The Q60R as the second Stick read it: its Dolby Vision decoders are single-layer, so profile 7 plays as HDR10. */
    private val q60r = TvCapabilities(setOf(Hdr.HDR10, Hdr.HLG, Hdr.HDR10_PLUS), 3840, 2160, setOf(SoundFormat.AC3, SoundFormat.EAC3), 1L,
        dv7AsHdr10 = true)
    private val plan = TvPlans.automatic(q60r)

    private fun hash(i: Int) = String.format("%040x", i + 0xa0)

    // The rows by their letter in the evidence: A the IMAX copy, B the profile 7 remux, C and D the same 86.3 GB file.
    private val a = hash(1)
    private val b = hash(2)
    private val c = hash(3)
    private val d = hash(4)
    private val e = hash(5)

    /** The exact size of the 86.3 GB file, as MediaFusion gives it and as the play of Torrentio's measured it. */
    private val c86 = 86_261_275_745L

    private val tioRows = listOf(
        torrentio(b, quality = "4k DV", name = "Avatar.Fire.and.Ash.2025.2160p.UHD.BluRay.REMUX.DV.P7.HEVC.TrueHD.7.1.Atmos-GRPB",
            size = "79.07 GB", langs = "🇬🇧 / 🇪🇸 / 🇫🇷 / 🇮🇹 / 🇩🇪 / 🇯🇵"),
        torrentio(d, quality = "4k HDR10+", name = "Avatar.Fire.and.Ash.2025.2160p.UHD.BD.Remux.HDR10+.10bit.TrueHD.7.1-GRPD",
            size = "80.34 GB", langs = "🇷🇺 / 🇺🇦 / 🇰🇿 / 🇬🇧"),
        torrentio(e, quality = "4k DV | HDR10+", name = "Avatar.Fire.and.Ash.2025.2160p.UHD.BluRay.REMUX.DV.HDR10+.HEVC.10bit.TrueHD.7.1-GRPE",
            size = "75.81 GB", langs = "🇬🇧 / 🇷🇺"),
        torrentio(hash(9), quality = "1080p HDR", name = "Avatar.Fire.and.Ash.2025.1080p.BluRay.HDR10.x265.10bit.DTS.5.1-GRPI",
            size = "15.65 GB", langs = "🇬🇧 / 🇮🇹"),
        torrentio(hash(13), quality = "1080p HDR", name = "Avatar.Fire.and.Ash.2025.1080p.BluRay.HDR10.x265.10bit.AAC.7.1-GRPM", size = "13.50 GB"),
        torrentio(hash(14), quality = "720p", name = "Avatar.Fire.and.Ash.2025.720p.BluRay.x264.DD5.1-GRPN", size = "11.92 GB", langs = "🇬🇧 / 🇷🇺"),
        torrentio(hash(17), quality = "1080p 3D", name = "Avatar.Fire.and.Ash.2025.1080p.3D.BluRay.HSBS-GRPQ", size = "51.69 GB",
            langs = "🇬🇧 / 🇷🇺 / 🇺🇦"),
        torrentio(hash(18), quality = "1080p 3D", name = "Avatar.Fire.and.Ash.2025.1080p.3D.HEVC.DTS-HD.MA.7.1-GRPR", size = "54.30 GB"),
        torrentio(hash(19), quality = "", name = "Avatar.Fire.and.Ash.2025.BluRay-GRPS", size = "79.35 GB", langs = "🇬🇧 / 🇪🇸 / 🇫🇷"),
        torrentio(hash(20), quality = "", name = "Avatar.Fire.and.Ash.2025.BluRay.HEVC.DTS.7.1-GRPT", size = "34.27 GB"),
        torrentio(hash(21), quality = "SCR", name = "Avatar.Fire.and.Ash.2025.1080p.SCREENER.x264.AAC-GRPU", size = "3.07 GB"),
    )

    private val mfRows = listOf(
        mediaFusion(a, hdr = "DV|HDR", origin = "BluRay REMUX", audio = "TrueHD|Atmos", channels = "7.1",
            filename = "Avatar.Fire.and.Ash.2025.IMAX.2160p.DV.HDR.REMUX.TrueHD.Atmos.7.1-GRPA.mkv", videoSize = 74_830_000_000L,
            languages = "English + Hindi"),
        mediaFusion(c, hdr = "DV|HDR10+", origin = "BluRay REMUX", audio = "TrueHD", channels = "7.1",
            filename = "Avatar.Fire.and.Ash.2025.2160p.BluRay.REMUX.DV.HDR10Plus.TrueHD.7.1-GRPC.mkv", videoSize = c86,
            languages = "English + Russian + Ukrainian"),
        mediaFusion(hash(6), hdr = "HDR", origin = "BluRay REMUX", audio = "", channels = "",
            filename = "Avatar.Fire.and.Ash.2025.2160p.BluRay.REMUX.HDR-GRPF.mkv", videoSize = 81_200_000_000L, languages = "English + Russian"),
        mediaFusion(hash(7), hdr = "HDR", origin = "BluRay", audio = "Dolby Digital", channels = "5.1",
            filename = "Avatar.Fire.and.Ash.2025.2160p.BluRay.HDR.x265.DD5.1-GRPG.mkv", videoSize = 72_700_000_000L, languages = "English + Italian"),
        mediaFusion(hash(8), resolution = "1440p", hdr = "", origin = "", codec = "", audio = "", channels = "",
            filename = "Avatar.Fire.and.Ash.2025.1440p-GRPH.mkv", videoSize = 70_400_000_000L, languages = "English + Russian"),
        mediaFusion(hash(10), resolution = "1080p", hdr = "", origin = "BluRay REMUX", codec = "avc", audio = "", channels = "",
            filename = "Avatar.Fire.and.Ash.2025.1080p.BluRay.REMUX.AVC-GRPJ.mkv", videoSize = 49_000_000_000L, languages = "English"),
        mediaFusion(hash(11), resolution = "1080p", hdr = "", origin = "BluRay", codec = "", audio = "", channels = "",
            filename = "Avatar.Fire.and.Ash.2025.1080p.BluRay-GRPK.mkv", videoSize = 28_900_000_000L, languages = "English"),
        mediaFusion(hash(12), resolution = "1080p", hdr = "", origin = "BluRay", codec = "avc", audio = "", channels = "",
            filename = "Avatar.Fire.and.Ash.2025.1080p.BluRay.x264-GRPL.mkv", videoSize = 19_600_000_000L, languages = "English + Italian"),
        mediaFusion(hash(15), resolution = "720p", hdr = "", origin = "BluRay", codec = "avc", audio = "Dolby Digital Plus", channels = "5.1",
            filename = "Avatar.Fire.and.Ash.2025.720p.BluRay.x264.DDP5.1-GRPO.mkv", videoSize = 3_300_000_000L,
            languages = "English + Hindi + Kannada + Tamil + Telugu + Malayalam"),
        mediaFusion(hash(16), resolution = "1080p", hdr = "", origin = "BluRay REMUX", codec = "", audio = "", channels = "",
            filename = "Avatar.Fire.and.Ash.2025.1080p.3D.HSBS.BluRay.REMUX-GRPP.mkv", videoSize = 81_700_000_000L, languages = "English + Ukrainian"),
    )

    private fun parsed(addon: Addon, rows: List<JSONObject>) = StreamParser.parse(addon, answer(*rows.toTypedArray()), runtime).streams

    /** Torrentio answered first, MediaFusion next. */
    private val answers = listOf(parsed(Addon.TORRENTIO, tioRows), parsed(Addon.MEDIAFUSION, mfRows))

    private fun spec(videoMbps: Int, size: Long, dvProfile: Int = 0, base: Int = 0, plus: Boolean = false, info: Boolean? = true, langs: String = "en") =
        MediaSpec(videoCodec = "HEVC", width = 3840, height = 2160, fps = 23.976f, bitDepth = 10, hdr = "HDR10",
            videoBps = videoMbps * 1_000_000L, videoHow = MediaSpec.How.MEASURED, sizeBytes = size, measuredSecs = 150,
            audioLangs = langs, dvBaseLayer = base, dvProfile = dvProfile,
            decoder = MediaSpec.Decoder.PLAIN, hdr10Plus = plus, hdrInfo = info, playedAt = 1_764_000_000_000L)

    private val smooth = CopyHealth(watchedMs = 150_000, renderedFrames = 3_000, droppedFrames = 1, at = 1_764_000_000_000L)

    /** What the five test plays measured (A, B, D and E by Torrentio's or MediaFusion's release; C is D's file). */
    private val plays = mapOf(
        "rd|$a|0" to Measured(spec(41, 74_830_000_000L, dvProfile = 8, info = false, langs = "en,hi"), smooth, 1L),
        "rd|$b|0" to Measured(spec(48, 84_900_123_456L, dvProfile = 7, base = 7, langs = "en,es,fr,it,de,ja"), smooth, 1L),
        "rd|$d|0" to Measured(spec(49, c86, dvProfile = 7, base = 7, plus = true, langs = "en,ru,uk,kk"), smooth, 1L),
        "rd|$e|0" to Measured(spec(51, 81_400_123_456L, dvProfile = 7, base = 7, plus = true, langs = "en,ru"), smooth, 1L),
    )

    private val film = SourceTarget(Title("tt1757678", Kind.MOVIE, "Avatar: Fire and Ash", runtime = "3h 15m"))

    private fun input(measured: (String) -> Measured? = { null }, imaxAlways: Boolean = false, p: app.fireplayer.vod.TvPlan = plan) =
        RankInput(p, listOf("en"), measured, runtime, episode = false, imaxAlways = imaxAlways, now = 1_764_100_000_000L)

    private fun ranked(input: RankInput, measuredSize: (app.fireplayer.vod.ReleaseKey) -> Long? = { null }) =
        SourceRank.rank(SourceMerge.merge(answers, runtime, measuredSize), input)

    private fun sourceList(ranked: List<PlaybackSource>, input: RankInput) = SourceList(film,
        mapOf(Addon.TORRENTIO to AddonState.Answered(11), Addon.MEDIAFUSION to AddonState.Answered(10)), ranked,
        SourceRank.best(ranked), null, SourceRank.why(ranked, input))

    private fun PlaybackSource.has(hash: String) = release?.infoHash == hash || offers.any { it.release?.infoHash == hash }

    private fun keyOf(list: List<PlaybackSource>, hash: String) = list.first { it.has(hash) }.key

    @Test
    fun beforeTheTestPlaysAnHdr10PlusRemuxIsBestAndTheImaxCopyComesAfterTheHdr10PlusOnes() {
        val input = input()
        val ranked = ranked(input)
        // 21 rows: nothing merges before a play (Torrentio's size is rounded, the names differ).
        assertEquals(21, ranked.size)
        val best = SourceRank.best(ranked)!!
        assertTrue(best.has(d) || best.has(c) || best.has(e))
        assertTrue(Hdr.HDR10_PLUS in best.facts.hdr)
        assertEquals("REMUX", best.facts.origin)
        // The 4K order: the three HDR10+ remuxes (bigger first), the IMAX copy, the profile 7 remux, the HDR10 remux, the BluRay.
        val order = ranked.take(7).map { s -> listOf(a, b, c, d, e, hash(6), hash(7)).first { s.has(it) } }
        assertEquals(listOf(d, c, e, a, b, hash(6), hash(7)), order)
        // The profile 7 remux plays here as HDR10: no "may not play here", and ranked as HDR10.
        val p7 = ranked.first { it.has(b) }
        assertEquals(TvFit.LIKELY, p7.fit)
        assertNull(p7.warning)
        assertEquals(setOf(Hdr.HDR10), SourceRank.shownHdr(p7.facts, plan))
        // The Best line says what decided it and what it beat.
        val list = sourceList(ranked, input)
        assertEquals("4K · HDR10+ · Remux · cached · ahead of the IMAX copy (no HDR10+)", SourcesModel.reason(best, list))
        assertEquals(RankStep.DYNAMIC_HDR, list.why?.step)
    }

    @Test
    fun afterThePlaysTheSameFileIsOneRowAndItIsBestAheadOfTheImaxCopy() {
        val input = input(measured = { plays[it] })
        val ranked = ranked(input) { r -> plays[r.storeKey]?.spec?.sizeBytes }
        // The 86.3 GB file from both add-ons is one row now: the size Torrentio's play measured is MediaFusion's.
        assertEquals(20, ranked.size)
        val best = SourceRank.best(ranked)!!
        assertTrue(best.has(c) && best.has(d))
        assertTrue(best.bothAddons)
        assertEquals(c86, best.facts.sizeBytes)
        // Its labels are joined (MediaFusion's DV, both's HDR10+), and this TV shows it as HDR10+.
        assertTrue(Hdr.DOLBY_VISION in best.facts.hdr && Hdr.HDR10_PLUS in best.facts.hdr)
        assertEquals(setOf(Hdr.HDR10_PLUS, Hdr.HDR10), SourceRank.shownHdr(SourceRank.asPlayedHere(best.facts, best.measured), plan))
        assertEquals("4K · HDR10+ · Remux · 86.3 GB", PlayRules.mainLine(best))
        // The IMAX copy gave no HDR brightness data here: it goes below the profile 7 and HDR10 remuxes, still above the encode.
        val order = ranked.take(6).map { s -> listOf(a, b, d, e, hash(6), hash(7)).first { s.has(it) } }
        assertEquals(listOf(d, e, b, hash(6), a, hash(7)), order)
        // Played here: dated, and "played here" (no longer sorted above unplayed copies just for that).
        assertEquals(TvFit.MEASURED_OK, best.fit)
        assertNotNull(best.measured?.at)
        val reason = SourcesModel.reason(best, sourceList(ranked, input))
        assertTrue(reason, reason.startsWith("4K · HDR10+ · Remux · played here · ahead of the 84.9 GB Remux (no HDR10+)"))
    }

    @Test
    fun imaxFirstAlwaysPutsTheImaxCopyBackFirst() {
        val ranked = ranked(input(imaxAlways = true))
        assertTrue(SourceRank.best(ranked)!!.has(a))
        val list = sourceList(ranked, input(imaxAlways = true))
        assertEquals(RankStep.IMAX_ALWAYS, list.why?.step)
        assertEquals("4K IMAX · HDR10 · Remux · cached · ahead of the 86.3 GB Remux (not IMAX)", SourcesModel.reason(list.best!!, list))
    }

    @Test
    fun aTvThatCantPlayProfile7AsHdr10StillWarns() {
        val noRoute = TvPlans.automatic(q60r.copy(dv7AsHdr10 = false))
        val p7 = ranked(input(p = noRoute)).first { it.has(b) }
        assertEquals(TvFit.LIKELY_NOT, p7.fit)
        assertEquals(SourceRank.DV7_WORDS, p7.warning)
    }

    @Test
    fun theWindowsGroupsAreInOrderEachBestFirstWithThreeDAndUnstatedApart() {
        val input = input()
        val list = sourceList(ranked(input), input)
        val view = SourcesModel.view(list)
        assertEquals(listOf(Section.IMAX, Section.UHD, Section.FHD, Section.HD, Section.THREE_D, Section.UNSTATED, Section.CINEMA),
            view.groups.map { it.section })
        assertEquals(listOf(1, 6, 6, 2, 3, 2, 1), view.groups.map { it.rows.size })
        assertTrue(view.groups[1].best.has(d))
        assertEquals(list.best, view.best)
        assertNull(view.playing)
        assertFalse(view.soFar)
        // Next source after the Best: the next 4K copy down.
        assertTrue(view.next!!.has(c))
        assertEquals(Section.UHD, view.nextSection)
        // Playing the Best: no separate Best; Next source goes on in its group, past a copy that failed here.
        val playing = SourcesModel.view(list, playingKey = list.best!!.key,
            attempts = listOf(Attempt(list.sources.first { it.has(c) }, 0L, OpenWhy.USER, Failure(FailureKind.FILE))))
        assertTrue(playing.playingIsBest)
        assertNull(playing.best)
        assertTrue(playing.next!!.has(e))
        assertTrue(playing.reason!!.contains("ahead of the IMAX copy"))
        // The IMAX group's copy, playing: Next source has no other IMAX copy, so it goes on down the list.
        val onImax = SourcesModel.view(list, playingKey = keyOf(list.sources, a))
        assertFalse(onImax.playingIsBest)
        assertTrue(onImax.next!!.has(b))
    }
}
