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
import app.fireplayer.vod.Step
import app.fireplayer.vod.ThisTv
import app.fireplayer.vod.Title
import app.fireplayer.vod.TvCapabilities
import app.fireplayer.vod.TvFit
import app.fireplayer.vod.TvPlan
import app.fireplayer.vod.play.PlayRules
import app.fireplayer.vod.play.SourcesModel
import app.fireplayer.vod.sources.SourceFixtures.answer
import app.fireplayer.vod.sources.SourceFixtures.torrentio
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executor

/**
 * The TV test of "Your Friendly Neighborhood Spider-Man" S1 E6 on a Fire TV Stick 4K Max (made-up hashes and ".invalid"
 * links, as every fixture): MediaFusion's row "4K · HDR10 · WEB · 10.2 GB" was a 1,083,950,014-byte WEBRip (group Amen,
 * 5.2 Mb/s) whose videoSize was its season pack's total; StremThru Torz listed the same file with its index and exact
 * size; Torrentio and Torz the 3.0 GB G66 WEB-DL, measured here at 14 Mb/s; MediaFusion another pack, 28.8 GB in all.
 */
class SeasonPackTest {

    private val show = Title("tt0000077", Kind.SERIES, "Your Friendly Neighborhood Spider-Man", runtime = "28 min")
    private val e6 = EpisodeRef(1, 6)
    private val e7 = EpisodeRef(1, 7)
    private val ep6 = SourceTarget(show, e6)
    private val ep7 = SourceTarget(show, e7)

    /** The Amen WEBRip's season pack (MediaFusion: no file index; Torz: index 5 for E6, 6 for E7). */
    private val ha = "a".repeat(40)
    /** A single-episode torrent of the same Amen file (Torz's "×2 copies"). */
    private val hc = "c".repeat(40)
    /** The G66 WEB-DL's season pack (Torrentio and Torz, index 5). */
    private val hg = "6".repeat(40)
    /** Another season pack, 28.8 GB in all (MediaFusion). */
    private val hp = "b".repeat(40)
    /** A 1080p WEB-DL (Torrentio). */
    private val hn = "d".repeat(40)

    private val amenBytes = 1_083_950_014L
    private val amenE7Bytes = 1_121_400_311L
    private val g66Bytes = (2.77 * (1L shl 30)).toLong()
    private val amenFile = "Your.Friendly.Neighborhood.Spider-Man.S01E06.2160p.WEBRip.x265.10bit.DV.HDR.DDP5.1-Amen.mkv"
    private val amenFileE7 = "Your.Friendly.Neighborhood.Spider-Man.S01E07.2160p.WEBRip.x265.10bit.DV.HDR.DDP5.1-Amen.mkv"
    private val g66Pack = "Your.Friendly.Neighborhood.Spider-Man.S01.2160p.DSNP.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-G66"
    private val g66File = "Your.Friendly.Neighborhood.Spider-Man.S01E06.2160p.DSNP.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-G66.mkv"
    private val packFile = "Your.Friendly.Neighborhood.Spider-Man.S01E06.2160p.DSNP.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-PACK.mkv"

    /** As MediaFusion's Rust backend writes an episode of a season pack: one size (the torrent's), no fileIdx. */
    private fun mfPack(hash: String, file: String, videoSize: Long, sizeWords: String, origin: String, episode: EpisodeRef = e6) =
        JSONObject().apply {
            put("name", "MediaFusion | ElfHosted 🧲 RD ⚡️ 4k")
            put("description", "🎨 DV|HDR 📺 $origin 🎞️ hevc 🎵 Dolby Digital Plus 🔊 5.1\n📦 $sizeWords 👤 12\n🌐 English\n🔗 Source | 🧑‍💻 Uploader")
            put("url", "https://mf.invalid/streaming_provider/FAKECONFIG_MF/playback/realdebrid/$hash/${episode.season}/${episode.episode}/$file")
            put("behaviorHints", JSONObject().apply {
                put("bingeGroup", "MediaFusion-|-ElfHosted-🎨 DV|HDR 📺 $origin 🎞️ hevc 🎵 Dolby Digital Plus-4K")
                put("filename", file)
                put("videoSize", videoSize)
            })
        }

    /**
     * As StremThru Torz writes one: the file's own size (videoSize) and its index in the link; [group]: its binge group
     * ("torz:<hash>" as StremThru writes it; most fixtures leave it out).
     */
    private fun torz(hash: String, idx: Int, file: String, videoSize: Long, origin: String = "WEBRip", group: String? = null) = JSONObject().apply {
        put("name", "⚡️ [RD] Torz\n2160p")
        put("description", "💿 $origin 🎞️ HEVC\n📺 DV HDR 🎧 DDP | 5.1\n💾 1.01 GB 📦 10.2 GB 👤 12\n📄 $file")
        put("url", "https://torz.invalid/stremio/torz/FAKECONFIG_TORZ/_/strem/tt0000077/rd/$hash/$idx/$file")
        put("behaviorHints", JSONObject().put("videoSize", videoSize).put("filename", file).apply { group?.let { put("bingeGroup", it) } })
    }

    private fun tioAnswer() = answer(
        torrentio(hg, idx = 5, quality = "4k DV | HDR", name = g66Pack, file = "$g66Pack/$g66File", size = "2.77 GB"),
        torrentio(hn, idx = 5, quality = "1080p", name = "Your.Friendly.Neighborhood.Spider-Man.S01E06.1080p.DSNP.WEB-DL.DDP5.1.H.264-NTb",
            size = "1.21 GB"),
    )

    private fun mfAnswer(episode: EpisodeRef = e6) = if (episode == e6) answer(
        mfPack(ha, amenFile, 10_200_000_000L, "10.2 GB", "WEBRip"),
        mfPack(hp, packFile, 28_800_000_000L, "28.8 GB", "WEB-DL"),
    ) else answer(mfPack(ha, amenFileE7, 10_200_000_000L, "10.2 GB", "WEBRip", e7))

    private fun torzAnswer(episode: EpisodeRef = e6) = if (episode == e6) answer(
        torz(ha, 5, amenFile, amenBytes),
        torz(hc, 0, amenFile, amenBytes),
        torz(hg, 5, g66File, g66Bytes, origin = "WEB-DL"),
    ) else answer(torz(ha, 6, amenFileE7, amenE7Bytes))

    private fun parsed(addon: Addon, json: String) = StreamParser.parse(addon, json, 28, episode = true).streams

    private fun answers(episode: EpisodeRef = e6) =
        listOf(parsed(Addon.MEDIAFUSION, mfAnswer(episode)), parsed(Addon.TORRENTIO, tioAnswer()), parsed(Addon.TORZ, torzAnswer(episode)))

    /** The Max on its TV: HDR10 (no Dolby Vision), DD and DD+ passed through; the line measured at 122 Mb/s. */
    private val plan = TvPlan(true, listOf(setOf(Hdr.HDR10, Hdr.HDR10_PLUS), setOf(Hdr.HLG)), 2160, setOf(SoundFormat.AC3, SoundFormat.EAC3),
        dv7AsHdr10 = true)
    private val now = 1_764_000_000_000L

    private fun input(measured: (String) -> Measured? = { null }, episode: EpisodeRef = e6) =
        RankInput(plan, listOf("en"), measured, 28, episode = true, lineMbps = 122.0, now = now, episodeRef = episode)

    /** What this TV measured when the Amen file played (MediaFusion's offer, no index: kept for E6 only). */
    private val amenPlayed = Measured(MediaSpec(sizeBytes = amenBytes, overallBps = 5_162_000, videoBps = 4_800_000,
        videoHow = MediaSpec.How.MEASURED, measuredSecs = 600, playedAt = now - 3_600_000), CopyHealth(watchedMs = 600_000, at = now), now)

    /** What it measured when the 3.0 GB G66 copy played before: 14 Mb/s, smoothly. */
    private val g66Played = Measured(MediaSpec(sizeBytes = g66Bytes, overallBps = 14_160_000, videoBps = 13_400_000,
        videoHow = MediaSpec.How.MEASURED, measuredSecs = 91, playedAt = now - 86_400_000), CopyHealth(watchedMs = 91_000, at = now), now)

    private fun ranked(measured: Map<String, Measured> = emptyMap(), episode: EpisodeRef = e6): List<PlaybackSource> {
        val merged = SourceMerge.merge(answers(episode), 28, episode = true) { r -> measured[r.measuredKey(episode)]?.spec?.sizeBytes }
        return SourceRank.rank(merged, input({ measured[it] }, episode))
    }

    private fun List<PlaybackSource>.holding(hash: String) = single { s -> s.offers.any { it.release?.infoHash == hash } }

    // ---- The add-ons' sizes ----

    @Test
    fun anEpisodesOneSizeFromMediaFusionIsItsSeasonPacksSoItShowsNoSizeAndNoRate() {
        val s = parsed(Addon.MEDIAFUSION, mfAnswer()).first()
        assertEquals(ReleaseKey(ha, null), s.release)
        assertNull(s.facts.sizeBytes)
        assertNull(s.facts.avgMbps)
        assertFalse(s.exactSize)
        assertTrue(s.torrentSize)
        // Its other facts are its file's.
        assertEquals(2160, s.facts.height)
        assertEquals("WEBRip", s.facts.origin)
        assertEquals("Amen", s.facts.group)
        // Only the words' size (no videoSize): the same.
        val words = JSONObject(mfPack(ha, amenFile, 10_200_000_000L, "10.2 GB", "WEBRip").toString()).apply {
            getJSONObject("behaviorHints").remove("videoSize")
        }
        val fromWords = parsed(Addon.MEDIAFUSION, answer(words)).single()
        assertNull(fromWords.facts.sizeBytes)
        assertTrue(fromWords.torrentSize)
        // A film's videoSize is its file's, as before.
        val film = StreamParser.parse(Addon.MEDIAFUSION, answer(mfPack(ha, amenFile, 10_200_000_000L, "10.2 GB", "WEBRip")), 120).streams.single()
        assertEquals(10_200_000_000L, film.facts.sizeBytes)
        assertTrue(film.exactSize)
        assertTrue(film.facts.sizeExact)
        assertFalse(film.torrentSize)
    }

    @Test
    fun mediaFusionsTwoSizesNameTheFileFirst() {
        // "📦 1.01 GB / 10.2 GB": the file, then its pack. videoSize the file's to the byte: exact.
        val fileSize = parsed(Addon.MEDIAFUSION, answer(mfPack(ha, amenFile, amenBytes, "1.01 GB / 10.2 GB", "WEBRip"))).single()
        assertEquals(amenBytes, fileSize.facts.sizeBytes)
        assertTrue(fileSize.exactSize)
        assertFalse(fileSize.torrentSize)
        // videoSize the pack's: the words' file size, not exact.
        val packSize = parsed(Addon.MEDIAFUSION, answer(mfPack(ha, amenFile, 10_200_000_000L, "1.01 GB / 10.2 GB", "WEBRip"))).single()
        assertEquals((1.01 * (1L shl 30)).toLong(), packSize.facts.sizeBytes)
        assertFalse(packSize.exactSize)
        assertFalse(packSize.torrentSize)
        assertEquals(5.2, packSize.facts.avgMbps!!, 0.1)
        // Written in decimal units ("1.08 GB"), the file's videoSize is still its own.
        assertTrue(parsed(Addon.MEDIAFUSION, answer(mfPack(ha, amenFile, amenBytes, "1.08 GB / 10.95 GB", "WEBRip"))).single().exactSize)
        assertTrue(Words.near(amenBytes, (1.08 * (1L shl 30)).toLong()))
        assertFalse(Words.near(10_200_000_000L, (1.01 * (1L shl 30)).toLong()))
        // Torz's videoSize is its file's.
        assertTrue(parsed(Addon.TORZ, torzAnswer()).all { it.exactSize && it.facts.sizeExact })
    }

    // ---- (a) One row per file ----

    @Test
    fun theAmenFileFromMediaFusionAndTorzIsOneRowOfItsOwnSize() {
        val rows = SourceMerge.merge(answers(), 28, episode = true)
        val amen = rows.holding(ha)
        assertEquals(amenBytes, amen.facts.sizeBytes)
        assertTrue(amen.facts.sizeExact)
        assertEquals("1.1 GB", PlayRules.size(amen.facts.sizeBytes!!))
        assertEquals(5.2, amen.facts.avgMbps!!, 0.1)
        assertEquals(setOf(Addon.MEDIAFUSION, Addon.TORZ), amen.addons.toSet())
        // MediaFusion's pack (no index), Torz's same pack (index 5), and Torz's single-episode torrent of the file.
        assertEquals(3, amen.copies)
        assertEquals(setOf(ReleaseKey(ha, null), ReleaseKey(ha, 5)), amen.offers.mapNotNull { it.release }.filter { it.infoHash == ha }.toSet())
        // G66 from Torrentio and Torz; MediaFusion's other pack; 1080p: four rows in all.
        assertEquals(4, rows.size)
        // The other pack has no size of its own, so no "Needs 137 Mb/s".
        val other = ranked().holding(hp)
        assertNull(other.facts.sizeBytes)
        assertNull(other.warning)
        assertNotEquals(TvFit.LIKELY_NOT, other.fit)
    }

    @Test
    fun twoHeightsOfAnEpisodeInOnePackStayTwoRows() {
        // MediaFusion's pack row (no index, 4K) answers first; Torrentio lists the pack's 2160p and 1080p files of E6.
        val pack = "Show.S01.2160p.1080p.WEB-DL"
        val mf = parsed(Addon.MEDIAFUSION, answer(mfPack(hn, amenFile, 30_000_000_000L, "30 GB", "WEBRip")))
        val tio = parsed(Addon.TORRENTIO, answer(
            torrentio(hn, idx = 5, quality = "4k DV | HDR", name = pack, file = "$pack/2160p/$amenFile", size = "2.77 GB"),
            torrentio(hn, idx = 15, quality = "1080p", name = pack, file = "$pack/1080p/Show.S01E06.1080p.WEB-DL.mkv", size = "1.2 GB"),
        ))
        val rows = SourceMerge.merge(listOf(mf, tio), 28, episode = true)
        assertEquals(2, rows.size)
        val uhd = rows.single { it.facts.height == 2160 }
        val fhd = rows.single { it.facts.height == 1080 }
        assertEquals(setOf(ReleaseKey(hn, null), ReleaseKey(hn, 5)), uhd.offers.mapNotNull { it.release }.toSet())
        assertEquals(listOf(ReleaseKey(hn, 15)), fhd.offers.mapNotNull { it.release })
        // Torrentio first: the pack row joins its file of the same height.
        val other = SourceMerge.merge(listOf(tio, mf), 28, episode = true)
        assertEquals(2, other.size)
        assertEquals(2, other.single { it.facts.height == 2160 }.offers.size)
        // A file is known the same way: the 4K pack row holds the 2160p file, not the 1080p one.
        assertTrue(FileSet.of(uhd, episode = true).holds(other.single { it.facts.height == 2160 }))
        val mfOnly = uhd.copy(offers = uhd.offers.filter { it.addon == Addon.MEDIAFUSION }, release = ReleaseKey(hn, null))
        assertFalse(FileSet.of(mfOnly, episode = true).holds(fhd))
        assertTrue(FileSet.of(mfOnly, episode = true).holds(uhd.copy(key = "t", offers = uhd.offers.filter { it.addon == Addon.TORRENTIO })))
    }

    @Test
    fun theMergeLooksUpWhatWasMeasuredOnceAStream() {
        val all = answers()
        var asked = 0
        SourceMerge.merge(all, 28, episode = true) { asked++; null }
        assertEquals(all.sumOf { a -> a.count { it.release != null } }, asked)
    }

    @Test
    fun aFilmsFilesKeepToHashAndIndex() {
        // A film's hash with and without an index (a collection holds several films): two rows, as before.
        val mf = StreamParser.parse(Addon.MEDIAFUSION, answer(SourceFixtures.mediaFusion(ha, fileIdx = null, videoSize = 31_420_000_000L)), 120).streams
        val tio = StreamParser.parse(Addon.TORRENTIO, answer(torrentio(ha, idx = 2, size = "12.00 GB")), 120).streams
        assertEquals(2, SourceMerge.merge(listOf(mf, tio), 120).size)
        // An episode's: one only when what the two say of the file agrees, never by the hash alone. Here their file names
        // differ ("-MFGRP" against "-FLUX") and only MediaFusion's size is to the byte: two rows. The same name: one.
        assertEquals(2, SourceMerge.merge(listOf(mf, tio), 120, episode = true).size)
        val named = StreamParser.parse(Addon.TORRENTIO, answer(torrentio(ha, idx = 2, size = "12.00 GB",
            name = "Film.Name.2021.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-MFGRP.$ha")), 120).streams
        assertEquals(1, SourceMerge.merge(listOf(mf, named), 120, episode = true).size)
        assertEquals(2, SourceMerge.merge(listOf(mf, named), 120).size)
    }

    @Test
    fun theNameRuleComparesTheRowsBestKnownSize() {
        // Torrentio stated 2.77 GB for its upload, but a play here measured 3.10 GB: a re-upload of the same name stated
        // at 3.09 GB is that file, not one 10 % off.
        val name = "Your.Friendly.Neighborhood.Spider-Man.S01E06.2160p.DSNP.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-G66"
        val a = torrentio(hg, idx = 5, quality = "4k DV | HDR", name = name, size = "2.77 GB")
        val b = torrentio(hn, idx = 0, quality = "4k DV | HDR", name = name, size = "3.09 GB")
        val streams = StreamParser.parse(Addon.TORRENTIO, answer(a, b), 28, episode = true).streams
        assertEquals(2, SourceMerge.merge(listOf(streams), 28, episode = true).size)
        val measured = (3.10 * (1L shl 30)).toLong()
        val one = SourceMerge.merge(listOf(streams), 28, episode = true) { if (it.infoHash == hg) measured else null }.single()
        assertEquals(2, one.copies)
        assertEquals(measured, one.facts.sizeBytes)
    }

    // ---- (b), (c) Best for this TV ----

    @Test
    fun beforeAnyPlayTheG66CopyRanksAboveTheAmenFile() {
        val list = ranked()
        val g66 = list.holding(hg)
        val amen = list.holding(ha)
        assertTrue(list.indexOf(g66) < list.indexOf(amen))
        // The Amen file's real rate starves its 4K picture: it counts under every well-fed 1080p.
        assertTrue(list.indexOf(list.holding(hn)) < list.indexOf(amen))
        assertEquals("4K · HDR10 · WEB · 1.1 GB", PlayRules.mainLine(amen, film = false, plan = plan))
        assertEquals("4K · HDR10 · WEB · 3.0 GB", PlayRules.mainLine(g66, film = false, plan = plan))
    }

    @Test
    fun onceBothPlayedHereTheHighestRealBitrateIsBest() {
        val measured = mapOf(ReleaseKey(ha, null).measuredKey(e6) to amenPlayed, ReleaseKey(hg, 5).storeKey to g66Played)
        val list = ranked(measured)
        val g66 = list.holding(hg)
        assertEquals(g66.key, SourceRank.best(list)?.key)
        // The other pack (no size of its own, never played) doesn't beat a copy that played well here at 14 Mb/s.
        assertTrue(list.indexOf(g66) < list.indexOf(list.holding(hp)))
        val amen = list.holding(ha)
        assertEquals(TvFit.MEASURED_OK, amen.fit)
        // What it played as here: its size and its rate.
        assertEquals("4K · HDR10 · WEB · 1.1 GB", PlayRules.mainLine(amen, film = false, plan = plan))
        assertTrue(PlayRules.badges(amen).contains("≈5 Mb/s"))
        val details = SourcesModel.details(amen, plan, film = false) { "Nov 25" }.toMap()
        assertEquals("1.1 GB", details["Size"])
        assertEquals("≈5 Mb/s average · measured here", details["Data rate"])
    }

    @Test
    fun onAFreshTvThePackRowOfNoSizeRanksUnderTheG66Copy() {
        // Nothing played here yet: MediaFusion's 28.8 GB pack row has no size of its own, so no rate to beat the G66
        // copy's 14 Mb/s with; it was "Needs 137 Mb/s" before, never Best.
        val fresh = ranked()
        assertEquals(ReleaseKey(hg, 5), SourceRank.best(fresh)?.release)
        assertTrue(fresh.indexOf(fresh.holding(hg)) < fresh.indexOf(fresh.holding(hp)))
        assertTrue(fresh.holding(hp).facts.torrentTotal)
        assertFalse(fresh.holding(ha).facts.torrentTotal)
        // Only the Amen file measured (5.2 Mb/s starves its 4K picture, so it proves nothing): the same.
        val amenOnly = ranked(mapOf(ReleaseKey(ha, null).measuredKey(e6) to amenPlayed))
        assertEquals(ReleaseKey(hg, 5), SourceRank.best(amenOnly)?.release)
        // E7, whose G66 file (index 6) never played here: the G66 copy, not MediaFusion's pack row.
        val e7Answers = listOf(
            parsed(Addon.MEDIAFUSION, answer(mfPack(hp, packFile.replace("S01E06", "S01E07"), 28_800_000_000L, "28.8 GB", "WEB-DL", e7))),
            parsed(Addon.TORZ, answer(torz(hg, 6, g66File.replace("S01E06", "S01E07"), g66Bytes, origin = "WEB-DL"))),
        )
        val e7Ranked = SourceRank.rank(SourceMerge.merge(e7Answers, 28, episode = true), input(episode = e7))
        assertEquals(ReleaseKey(hg, 6), SourceRank.best(e7Ranked)?.release)
        assertEquals(ReleaseKey(hp, null), e7Ranked.last().release)
        // Once the pack row's own file played here and was measured, its rate is known and ranks as any other.
        val packPlayed = Measured(MediaSpec(sizeBytes = 6_000_000_000L, overallBps = 28_000_000, videoBps = 26_000_000,
            videoHow = MediaSpec.How.MEASURED, measuredSecs = 600, playedAt = now - 3_600_000), CopyHealth(watchedMs = 600_000, at = now), now)
        val measured = ranked(mapOf(ReleaseKey(hp, null).measuredKey(e6) to packPlayed))
        assertEquals(ReleaseKey(hp, null), SourceRank.best(measured)?.release)
    }

    @Test
    fun anUnknownRateNeverBeatsACopyThatPlayedWellHereAtARateMeasuredHere() {
        // A MediaFusion row that gave no size at all (not a pack's total) beside the G66 copy: its rate isn't known.
        val noSize = JSONObject(mfPack(hp, packFile, 1L, "", "WEB-DL").toString()).apply {
            getJSONObject("behaviorHints").remove("videoSize")
        }
        val answers = listOf(parsed(Addon.MEDIAFUSION, answer(noSize)),
            parsed(Addon.TORZ, answer(torz(hg, 5, g66File, g66Bytes, origin = "WEB-DL"))))
        val rows = SourceMerge.merge(answers, 28, episode = true)
        assertFalse(rows.single { it.release == ReleaseKey(hp, null) }.facts.torrentTotal)
        // Never played here: the guess stays where it was, after copies near the preferred rate, before those far off.
        val unplayed = SourceRank.rank(rows, input())
        assertEquals(ReleaseKey(hp, null), unplayed.first().release)
        // The G66 copy played well here at 14 Mb/s: it is Best now, and why names no copy beaten on the rate.
        val measured = mapOf(ReleaseKey(hg, 5).storeKey to g66Played)
        val played = SourceRank.rank(rows, input({ measured[it] }))
        assertEquals(ReleaseKey(hg, 5), played.first().release)
        val why = SourceRank.why(played, input({ measured[it] }))!!
        assertEquals(played.first().key, why.bestKey)
        // It lost frames here: a guess may beat it again.
        val lost = CopyHealth(watchedMs = 900_000, at = now, droppedFrames = 2_000, renderedFrames = 20_000)
        assertTrue(lost.choppy)
        val choppy = mapOf(ReleaseKey(hg, 5).storeKey to g66Played.copy(health = lost))
        assertEquals(ReleaseKey(hp, null), SourceRank.rank(rows, input({ choppy[it] })).first().release)
    }

    // ---- (e) An index-less file's records are this episode's ----

    @Test
    fun anIndexlessFilesMeasurementsAreKeptForItsEpisodeOnly() {
        assertEquals("rd|$ha|-1|1:6", ReleaseKey(ha, null).measuredKey(e6))
        // A file with an index, and a film's: the key as before, so what was measured before is still found.
        assertEquals("rd|$ha|5", ReleaseKey(ha, 5).measuredKey(e6))
        assertEquals("rd|$ha|-1", ReleaseKey(ha, null).measuredKey(null))
        // An older version reading the new key finds no release in it (it is skipped there).
        assertNull(ReleaseKey.parse(ReleaseKey(ha, null).measuredKey(e6)))

        // E6's Amen file measured, and an old shared record kept under the pack's key alone (whichever episode wrote it).
        val oldShared = Measured(MediaSpec(sizeBytes = 999_000_000L), CopyHealth(watchedMs = 1_000, noPicture = 1, at = now), now)
        val measured = mapOf(ReleaseKey(ha, null).measuredKey(e6) to amenPlayed, "rd|$ha|-1" to oldShared)
        val e7 = ranked(measured, e7).holding(ha)
        // E7's file of the same pack: none of E6's figures, nor the old shared record's.
        assertNull(e7.measured)
        assertEquals(amenE7Bytes, e7.facts.sizeBytes)
        assertNotEquals(TvFit.LIKELY_NOT, e7.fit)
        val e6Row = ranked(measured, e6).holding(ha)
        assertEquals(amenBytes, e6Row.measured?.spec?.sizeBytes)
        assertNull(e6Row.warning)
        // A film's index-less file reads the record kept as before.
        val film = StreamParser.parse(Addon.MEDIAFUSION, answer(SourceFixtures.mediaFusion(ha, fileIdx = null)), 120).streams
        val sameSize = oldShared.copy(spec = MediaSpec(sizeBytes = 31_420_000_000L, overallBps = 34_900_000))
        val filmRow = SourceRank.rank(SourceMerge.merge(listOf(film), 120),
            RankInput(plan, listOf("en"), { if (it == "rd|$ha|-1") sameSize else null }, 120, episode = false)).single()
        assertEquals(sameSize, filmRow.measured)
    }

    @Test
    fun aFilmsIndexlessRecordOfAnotherSizeWasAnotherFilmsOfItsCollection() {
        // Iron Man played from a collection torrent MediaFusion gives no index: Iron Man 2's row has the same key.
        val ironMan = Measured(MediaSpec(sizeBytes = 9_000_000_000L, overallBps = 9_800_000, hdr10Plus = true), CopyHealth(watchedMs = 60_000, at = now), now)
        val film = StreamParser.parse(Addon.MEDIAFUSION, answer(SourceFixtures.mediaFusion(ha, fileIdx = null)), 120).streams
        val rows = SourceMerge.merge(listOf(film), 120) { if (it == ReleaseKey(ha, null)) 9_000_000_000L else null }
        // The add-on's size to the byte stays the row's, for merging too.
        assertEquals(31_420_000_000L, rows.single().facts.sizeBytes)
        val row = SourceRank.rank(rows, RankInput(plan, listOf("en"), { if (it == "rd|$ha|-1") ironMan else null }, 120, episode = false)).single()
        assertEquals(31_420_000_000L, SourceRank.asPlayedHere(row.facts, row.measured).sizeBytes)
        assertEquals(31_420_000_000L, FileSet.exactSize(row))
        assertEquals(StreamParser.avgMbps(31_420_000_000L, 120)!!, SourceRank.asPlayedHere(row.facts, row.measured).avgMbps!!, 0.01)
        // The rest of what was measured is read as before (shared, as in 1.0.360).
        assertEquals(ironMan.health, row.measured?.health)
        // With an index, the file measured is this one: its size and rate.
        val indexed = StreamParser.parse(Addon.MEDIAFUSION, answer(SourceFixtures.mediaFusion(ha, fileIdx = 3)), 120).streams
        val own = SourceRank.rank(SourceMerge.merge(listOf(indexed), 120) { 9_000_000_000L },
            RankInput(plan, listOf("en"), { if (it == "rd|$ha|3") ironMan else null }, 120, episode = false)).single()
        assertEquals(9_000_000_000L, own.facts.sizeBytes)
        assertEquals(9.8, SourceRank.asPlayedHere(own.facts, own.measured).avgMbps!!, 0.01)
    }

    @Test
    fun aTorrentOfOneEpisodeFileStillReadsWhatAnEarlierVersionKept() {
        // Torrentio gives a single-file torrent no index: 1.0.360 kept its health under "rd|<hash>|-1", and it showed no
        // picture here.
        val single = "e".repeat(40)
        val old = Measured(MediaSpec(sizeBytes = 1_300_000_000L), CopyHealth(watchedMs = 1_000, noPicture = 1, at = now), now)
        val tio = parsed(Addon.TORRENTIO, answer(torrentio(single, idx = -1, quality = "4k DV | HDR",
            name = "Your.Friendly.Neighborhood.Spider-Man.S01E06.2160p.WEB-DL.DV.HDR.H.265-SOLO", size = "1.21 GB")))
        assertEquals(ReleaseKey(single, null), tio.single().release)
        assertEquals("rd|$single|-1", ReleaseKey(single, null).earlierKey(e6, single = true))
        assertNull(ReleaseKey(single, null).earlierKey(e6, single = false))
        assertNull(ReleaseKey(single, 5).earlierKey(e6, single = true))
        assertNull(ReleaseKey(single, null).earlierKey(null, single = true))
        val stored = mapOf("rd|$single|-1" to old)
        val merged = SourceMerge.merge(listOf(tio), 28, episode = true)
        val row = SourceRank.rank(merged, input({ stored[it] })).single()
        assertEquals(old, row.measured)
        assertTrue(row.measured?.health?.setAside == true)
        assertEquals(TvFit.LIKELY_NOT, row.fit)
        // Something newer kept under its episode's key comes first.
        val newer = old.copy(health = CopyHealth(watchedMs = 5_000, at = now))
        val both = mapOf("rd|$single|-1" to old, ReleaseKey(single, null).measuredKey(e6) to newer)
        assertEquals(newer, SourceRank.rank(merged, input({ both[it] })).single().measured)
        // MediaFusion's index-less pack row never reads the old shared record ((e) above); nor through the engine.
        answerOf[Addon.TORRENTIO] = { answer(torrentio(single, idx = -1, quality = "4k DV | HDR",
            name = "Your.Friendly.Neighborhood.Spider-Man.S01E06.2160p.WEB-DL.DV.HDR.H.265-SOLO", size = "1.21 GB")) }
        kept["rd|$single|-1"] = old
        kept["rd|$ha|-1"] = old
        val l = list()
        assertEquals(old, l.sources.holding(single).measured)
        assertNull(l.sources.holding(ha).measured)
        // The engine merges by its size measured then, too: the same file from Torz, of that size to the byte.
        answerOf[Addon.TORZ] = { answer(torz(hc, 0, "Another.Name.For.It.S01E06.2160p.WEB-DL.mkv", 1_300_000_000L, origin = "WEB-DL")) }
        engine.forget()
        loop.advance()
        assertEquals(1, list().sources.count { it.holds(single) || it.holds(hc) })
    }

    // ---- Next source: never the file playing ----

    @Test
    fun nextOrderDropsThePlayingFileWhenItsRowWasKeyedAgain() {
        val list = ranked()
        val amen = list.holding(ha)
        val same = FileSet.of(amen, episode = true)
        // Its key gone (merged or keyed anew since): the row holding its file stands in, and is never next. The rest of
        // its group (4K) first, then 1080p, as from its own row.
        val order = SourceRank.nextOrder(list, "rd|gone|-1", film = false, current = amen) { same.holds(it) }
        assertTrue(order.none { s -> s.offers.any { it.release?.infoHash == ha } })
        assertEquals(listOf(hg, hp, hn), order.map { it.release?.infoHash })
        // Its row in no form: its own group from the top, then the groups below.
        val without = list.filterNot { it.key == amen.key }
        assertEquals(listOf(hg, hp, hn), SourceRank.nextOrder(without, "rd|gone|-1", film = false, current = amen) { same.holds(it) }
            .map { it.release?.infoHash })
        // The playing copy as another add-on's row of its own (Torz's, not merged): left out too.
        val torzOnly = amen.copy(key = "torz-row", offers = amen.offers.filter { it.addon == Addon.TORZ }, release = ReleaseKey(ha, 5))
        val split = list.map { if (it.key == amen.key) torzOnly else it }
        val mfOnly = amen.copy(offers = amen.offers.filter { it.addon == Addon.MEDIAFUSION }, release = ReleaseKey(ha, null))
        val alone = FileSet.of(mfOnly, episode = true)
        assertTrue(SourceRank.nextOrder(split, amen.key, film = false, current = mfOnly) { alone.holds(it) }.none { it.key == "torz-row" })
        // No copy known: the list's order, as before.
        assertEquals(list.filter { it.automatic }.map { it.key }, SourceRank.nextOrder(list, null, film = false).map { it.key })
    }

    @Test
    fun aFileIsKnownByItsReleasesAnEpisodesIndexlessHashOrItsExactSize() {
        val list = ranked()
        val amen = list.holding(ha)
        val g66 = list.holding(hg)
        val mfOnly = amen.copy(key = "mf", offers = amen.offers.filter { it.addon == Addon.MEDIAFUSION }, release = ReleaseKey(ha, null),
            facts = amen.facts.copy(sizeBytes = null, sizeExact = false))
        val torzOnly = amen.copy(key = "torz", offers = amen.offers.filter { it.addon == Addon.TORZ }, release = ReleaseKey(ha, 5),
            facts = amen.facts.copy(sizeBytes = 1L, sizeExact = true))
        // The same pack, one side without the index: the same episode file, as their file names say.
        assertTrue(FileSet.of(mfOnly, episode = true).holds(torzOnly))
        assertTrue(FileSet.of(torzOnly, episode = true).holds(mfOnly))
        // The hash alone says nothing: MediaFusion's file named as the pack's E7 isn't Torz's E6, nor one it names none.
        val misnamed = mfOnly.copy(offers = mfOnly.offers.map { it.copy(filename = amenFileE7) })
        assertFalse(FileSet.of(misnamed, episode = true).holds(torzOnly))
        assertFalse(FileSet.of(torzOnly, episode = true).holds(misnamed))
        val unnamed = mfOnly.copy(offers = mfOnly.offers.map { it.copy(filename = null) })
        assertFalse(FileSet.of(unnamed, episode = true).holds(torzOnly))
        assertFalse(FileSet.of(torzOnly, episode = true).holds(unnamed))
        // Not for a film (a collection's hash holds several films).
        assertFalse(FileSet.of(mfOnly, episode = false).holds(torzOnly))
        // Its size to the byte, under another hash.
        val twin = g66.copy(key = "twin", release = ReleaseKey(hc, 0), offers = g66.offers.map { it.copy(release = ReleaseKey(hc, 0)) })
        assertTrue(FileSet.of(g66, episode = false).holds(twin))
        // A rounded size is never the same file.
        val rounded = twin.copy(facts = twin.facts.copy(sizeExact = false))
        assertFalse(FileSet.of(g66.copy(facts = g66.facts.copy(sizeExact = false)), episode = false).holds(rounded))
        assertFalse(FileSet.of(g66, episode = true).holds(amen))
    }

    // ---- The engine: (d) while the Amen copy plays ----

    private val loop = FakeLoop()
    private val direct = Executor { it.run() }
    private val kept = HashMap<String, Measured>()
    private var last: LastRelease? = null
    private val recorded = ArrayList<LastRelease>()
    private val links = HashMap<Addon, SecretUrl>().apply {
        put(Addon.TORRENTIO, SecretUrl.of(SourceFixtures.TIO)!!)
        put(Addon.MEDIAFUSION, SecretUrl.of(SourceFixtures.MF)!!)
        put(Addon.TORZ, SecretUrl.of("https://torz.invalid/FAKECONFIG_TORZ")!!)
    }
    /** What each add-on answers for each target. */
    private val answerOf = HashMap<Addon, (SourceTarget) -> String>().apply {
        put(Addon.TORRENTIO) { tioAnswer() }
        put(Addon.MEDIAFUSION) { t -> mfAnswer(t.episode ?: e6) }
        put(Addon.TORZ) { t -> torzAnswer(t.episode ?: e6) }
    }

    private val library = object : PlaybackLibrary {
        override fun lastRelease(title: Title, episode: EpisodeRef?) = last
        override fun recordRelease(title: Title, episode: EpisodeRef?, release: LastRelease) {
            recorded += release
            last = release
        }
        override fun recordPlayback(title: Title, episode: EpisodeRef?, positionMs: Long, durationMs: Long, creditsAtMs: Long?) = Unit
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
        http = StreamsHttp { base, target, _, _ ->
            val addon = when (base.host) {
                "tio.invalid" -> Addon.TORRENTIO
                "mf.invalid" -> Addon.MEDIAFUSION
                else -> Addon.TORZ
            }
            answerOf[addon]?.invoke(target) ?: throw IOException("no answer set")
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
    )

    private fun list(target: SourceTarget = ep6): SourceList {
        val got = ArrayList<SourceList>()
        engine.lookUp(target, LookupWhy.PLAY, null) { got += it }
        loop.advance()
        return got.last()
    }

    private fun open(session: SourceSession, source: PlaybackSource? = null, why: OpenWhy = OpenWhy.START): List<Opened> {
        val got = ArrayList<Opened>()
        session.open(source, why) { got += it }
        loop.advance()
        return got
    }

    /** The Amen copy picked in Sources and playing from MediaFusion's offer, the G66 copy measured here before. */
    private fun amenPlaying(): Pair<SourceSession, Opened.Ready> {
        kept[ReleaseKey(hg, 5).storeKey] = g66Played
        val l = list()
        val amen = l.sources.holding(ha)
        val session = engine.session(PlayRequest(ep6, StartAt.Beginning, PlayOrigin.SOURCES_PANEL, list = l, chosen = amen))
        val ready = open(session).last() as Opened.Ready
        assertEquals(amen.key, ready.source.key)
        assertEquals(Addon.MEDIAFUSION, ready.offer.addon)
        session.started(ready.source, ready.offer)
        return session to ready
    }

    private fun PlaybackSource.holds(hash: String) = offers.any { it.release?.infoHash == hash } || release?.infoHash == hash

    @Test
    fun whileTheAmenCopyPlaysNextSourceIsTheG66CopyNeverTheAmenFile() {
        val (session, ready) = amenPlaying()
        val l = session.list!!
        assertEquals(l.sources.holding(hg).key, l.best?.key)
        assertEquals(ReleaseKey(hg, 5), session.nextPick()?.release)
        // MediaFusion's answer goes (expired, or asked again): the Amen file's row is Torz's now, under another key.
        engine.dropAnswer(ep6, Addon.MEDIAFUSION)
        loop.advance()
        val after = session.list!!
        val amenNow = after.sources.holding(ha)
        assertNotEquals(ready.source.key, amenNow.key)
        assertTrue(after.sources.none { it.key == ready.source.key })
        // The file playing is still known in its new row: the Sources window marks it Playing, and Next source passes it.
        assertEquals(amenNow.key, session.rowKey(ready.source))
        assertEquals(ReleaseKey(hg, 5), session.nextPick()?.release)
        // With the G66 copy gone too, Next source is the 1080p one, never the file playing under its new key.
        session.failed(after.sources.holding(hg), Failure(FailureKind.FILE))
        val next = session.nextPick()!!
        assertFalse(next.holds(ha) || next.holds(hc))
        assertEquals(ReleaseKey(hn, 5), next.release)
        // The Sources window says the same.
        val view = SourcesModel.view(after, session.rowKey(ready.source), session.attempts, next = session::nextPick)
        assertEquals(next.key, view.next?.key)
    }

    @Test
    fun whileTheFirstCopyLoadsNextSourceIsAnotherCopy() {
        // Play from the title page: the Best (the G66 copy) is handed to the player and loads; no first frame yet.
        kept[ReleaseKey(hg, 5).storeKey] = g66Played
        val l = list()
        val session = engine.session(PlayRequest(ep6, StartAt.Beginning, PlayOrigin.TITLE_PAGE, list = l))
        val ready = open(session).last() as Opened.Ready
        assertEquals(l.best?.key, ready.source.key)
        val next = session.nextPick()!!
        assertFalse(next.holds(hg))
        // The Sources window and Picture & sound say the same: Playing is the copy loading, Next source another.
        val view = SourcesModel.view(session.list!!, session.rowKey(ready.source), session.attempts, next = session::nextPick)
        assertEquals(ready.source.key, view.playing?.key)
        assertNotEquals(view.playing?.key, view.next?.key)
        // Next source pressed while it loads: another file, never the same one again.
        val other = open(session, why = OpenWhy.USER).last() as Opened.Ready
        assertFalse(other.source.holds(hg))
        assertEquals(next.key, other.source.key)
        // That one loads in turn, then plays: the copy left while it loaded isn't Next source again.
        assertFalse(session.nextPick()!!.let { it.key == other.source.key || it.holds(hg) })
        session.started(other.source, other.offer)
        assertFalse(session.nextPick()!!.let { it.key == other.source.key || it.holds(hg) })
    }

    @Test
    fun whileTheCopySwitchedToLoadsNextSourceIsNeitherItNorTheOneLeft() {
        val (session, amen) = amenPlaying()
        val g66 = open(session, why = OpenWhy.USER).last() as Opened.Ready
        assertTrue(g66.source.holds(hg))
        val next = session.nextPick()!!
        assertFalse(next.holds(hg) || next.holds(ha) || next.holds(hc))
        assertNotEquals(amen.source.key, next.key)
        // Pressed again while the G66 copy loads: neither copy.
        val third = open(session, why = OpenWhy.USER).last() as Opened.Ready
        assertFalse(third.source.holds(hg) || third.source.holds(ha) || third.source.holds(hc))
    }

    /** The Torrentio answer alone: the G66 copy (4K) and the NTb one (1080p). */
    private fun twoCopies(): Pair<SourceSession, Opened.Ready> {
        answerOf[Addon.MEDIAFUSION] = { answer() }
        answerOf[Addon.TORZ] = { answer() }
        val l = list()
        assertEquals(2, l.sources.size)
        val session = engine.session(PlayRequest(ep6, StartAt.Beginning, PlayOrigin.TITLE_PAGE, list = l))
        val a = open(session).last() as Opened.Ready
        assertTrue(a.source.holds(hg))
        session.started(a.source, a.offer)
        return session to a
    }

    @Test
    fun aSwitchWhoseCopyWontStartGoesBackToTheCopyThatPlayed() {
        val (session, a) = twoCopies()
        // Next source: the NTb copy loads, and is slow to start.
        val b = open(session, why = OpenWhy.USER).last() as Opened.Ready
        assertTrue(b.source.holds(hn))
        assertEquals(Step.TryNext, session.failed(b.source, Failure(FailureKind.SLOW)))
        // Nothing new is left for Next source to name...
        assertNull(session.nextPick())
        // ...but the play doesn't end: back to the G66 copy, which played fine.
        val back = open(session, why = OpenWhy.FAILED).last() as Opened.Ready
        assertEquals(a.source.key, back.source.key)
    }

    @Test
    fun aStallSwitchWithNothingNewGoesBackToTheCopyBefore() {
        val (session, a) = twoCopies()
        val b = open(session, why = OpenWhy.USER).last() as Opened.Ready
        session.started(b.source, b.offer)
        // The NTb copy keeps stalling: nothing new, so the G66 copy again (never the one stalling).
        val back = open(session, why = OpenWhy.STALLS).last() as Opened.Ready
        assertEquals(a.source.key, back.source.key)
        // Asked for by the viewer, Next source names nothing it played.
        session.started(back.source, back.offer)
        assertNull(session.nextPick())
    }

    @Test
    fun nextSourceNeverGoesBackToACopyPlayedInThisPlay() {
        answerOf[Addon.MEDIAFUSION] = { answer() }
        answerOf[Addon.TORZ] = { answer(torz(ha, 5, amenFile, amenBytes)) }
        val l = list()
        assertEquals(3, l.sources.size)
        val session = engine.session(PlayRequest(ep6, StartAt.Beginning, PlayOrigin.TITLE_PAGE, list = l))
        val first = open(session).last() as Opened.Ready
        session.started(first.source, first.offer)
        val played = mutableListOf(first.source.key)
        while (true) {
            val next = session.nextPick() ?: break
            assertFalse(next.key in played)
            val ready = open(session, why = OpenWhy.USER).last() as Opened.Ready
            assertEquals(next.key, ready.source.key)
            session.started(ready.source, ready.offer)
            played += ready.source.key
        }
        assertEquals(3, played.size)
        // Every copy played: Next source says none is left (or offers to fetch one), never one already played.
        val last = open(session, why = OpenWhy.USER).last()
        assertTrue(last is Opened.NoneLeft || last is Opened.AskFetch)
    }

    @Test
    fun lighterIsByTheRateMeasuredHereAndNeverTheFilePlaying() {
        val (session, _) = amenPlaying()
        // The G66 copy measured 14 Mb/s here, as its size says.
        assertEquals(ReleaseKey(hg, 5), session.lighter(20.0)?.release)
        assertNull(session.lighter(13.0)?.takeIf { it.holds(hg) })
        // Measured lower than its size says (9 Mb/s): it fits under 10.
        kept[ReleaseKey(hg, 5).storeKey] = g66Played.copy(spec = g66Played.spec!!.copy(overallBps = 9_000_000))
        engine.rerank(ep6)
        loop.advance()
        assertEquals(ReleaseKey(hg, 5), session.lighter(10.0)?.release)
        // The Amen file (5.2 Mb/s) is playing: never offered as lighter than itself.
        assertTrue(session.lighter(6.0)?.holds(ha) != true)
    }

    @Test
    fun aCopyThatShowedNoPictureIsLeftInEveryRowHoldingItsFile() {
        // Two rows of one file from one add-on (its own copies of one size stay apart by name).
        answerOf[Addon.MEDIAFUSION] = {
            answer(mfPack(ha, amenFile, amenBytes, "1.01 GB / 10.2 GB", "WEBRip"),
                mfPack(hc, "Another.Name.For.It.S01E06.2160p.WEBRip-X.mkv", amenBytes, "1.01 GB / 1.01 GB", "WEBRip"))
        }
        answerOf[Addon.TORZ] = { answer() }
        val l = list()
        assertEquals(2, l.sources.count { it.facts.sizeBytes == amenBytes })
        val first = l.sources.holding(ha)
        val session = engine.session(PlayRequest(ep6, StartAt.Beginning, PlayOrigin.SOURCES_PANEL, list = l, chosen = first))
        val ready = open(session).last() as Opened.Ready
        assertEquals(Step.TryNext, session.failed(ready.source, Failure(FailureKind.NO_PICTURE)))
        val next = open(session, why = OpenWhy.FAILED).last() as Opened.Ready
        assertFalse(next.source.holds(hc))
        assertFalse(next.source.holds(ha))
    }

    @Test
    fun lastUsedIsTheCopyThatStartedThoughARankWasUnderWay() {
        last = LastRelease(Addon.TORRENTIO, ReleaseKey(hg, 5), 1L)
        val l = list()
        val g66 = l.sources.holding(hg)
        assertEquals(g66.key, engine.cached(ep6)!!.lastUsed?.key)
        val amen = l.sources.holding(ha)
        val session = engine.session(PlayRequest(ep6, StartAt.Beginning, PlayOrigin.SOURCES_PANEL, list = l, chosen = amen))
        // A rank read the last release (the G66 copy's) and is about to post; then the Amen copy starts.
        engine.rerank(ep6)
        session.started(amen, amen.offers.first())
        assertEquals(amen.key, engine.cached(ep6)!!.lastUsed?.key)
        loop.advance()
        assertEquals(amen.key, engine.cached(ep6)!!.lastUsed?.key)
    }

    @Test
    fun lastUsedFindsTheRowByItsFileOnceItWasKeyedAgain() {
        val l = list()
        val amen = l.sources.holding(ha)
        engine.dropAnswer(ep6, Addon.MEDIAFUSION)
        loop.advance()
        val session = engine.session(PlayRequest(ep6, StartAt.Beginning, PlayOrigin.SOURCES_PANEL, list = l, chosen = amen))
        // Its row as it was picked (MediaFusion's key), started from Torz's offer of the same file.
        session.started(amen, amen.offers.first { it.addon == Addon.TORZ })
        val now = engine.cached(ep6)!!
        assertEquals(now.sources.holding(ha).key, now.lastUsed?.key)
        assertNotEquals(amen.key, now.lastUsed?.key)
    }

    @Test
    fun aResumeThatCantTakeTheCopyItLeftSaysWhy() {
        // The copy left isn't listed any more.
        val gone = LastRelease(Addon.TORRENTIO, ReleaseKey(hp.replace('b', 'e'), 3), 1L)
        val s1 = engine.session(PlayRequest(ep6, StartAt.At(91_000), PlayOrigin.TITLE_PAGE, preferred = gone))
        assertTrue(open(s1).last() is Opened.Ready)
        assertEquals("not listed any more", s1.resumeMiss())
        // Listed, but no longer cached.
        answerOf[Addon.TORRENTIO] = {
            answer(torrentio(hg, idx = 5, ready = false, quality = "4k DV | HDR", name = g66Pack, file = "$g66Pack/$g66File", size = "2.77 GB"))
        }
        answerOf[Addon.TORZ] = { answer(torz(ha, 5, amenFile, amenBytes)) }
        engine.forget()
        loop.advance()
        val left = LastRelease(Addon.TORRENTIO, ReleaseKey(hg, 5), 1L)
        val s2 = engine.session(PlayRequest(ep6, StartAt.At(91_000), PlayOrigin.TITLE_PAGE, preferred = left))
        assertTrue(open(s2).last() is Opened.Ready)
        assertEquals("not cached any more", s2.resumeMiss())
        // The copy left plays: nothing to say.
        val s3 = engine.session(PlayRequest(ep6, StartAt.At(91_000), PlayOrigin.TITLE_PAGE,
            preferred = LastRelease(Addon.TORZ, ReleaseKey(ha, 5), 1L)))
        val ready = open(s3).last() as Opened.Ready
        assertEquals(ReleaseKey(ha, 5), ready.offer.release)
        assertNull(s3.resumeMiss())
        // Never a link in it.
        assertFalse(s2.resumeMiss()!!.contains("invalid"))
    }

    // ---- One row only by what the add-ons say of the file, never by the info hash alone ----

    private val amenFileE8 = amenFile.replace("S01E06", "S01E08")

    /**
     * MediaFusion's offer for [episode] in a pack it numbers differently: its link names the season and episode, and the
     * file it opens for them is [file] (its filename says so), not the one Torz's index names.
     */
    private fun mfOther(file: String = amenFileE7, episode: EpisodeRef = e6) =
        mfPack(ha, file, 10_200_000_000L, "10.2 GB", "WEBRip", episode)

    /** [json] without the file's name (behaviorHints.filename). */
    private fun noName(json: JSONObject) = JSONObject(json.toString()).apply { getJSONObject("behaviorHints").remove("filename") }

    private fun PlaybackSource.onlyFrom(addon: Addon) = addons == listOf(addon)

    @Test
    fun aPackMediaFusionNumbersDifferentlyStaysTwoRowsEachPlayingItsOwnLink() {
        val mf = parsed(Addon.MEDIAFUSION, answer(mfOther()))
        val tz = parsed(Addon.TORZ, answer(torz(ha, 5, amenFile, amenBytes)))
        for (order in listOf(listOf(mf, tz), listOf(tz, mf))) {
            val rows = SourceMerge.merge(order, 28, episode = true)
            assertEquals(2, rows.size)
            val mfRow = rows.single { it.onlyFrom(Addon.MEDIAFUSION) }
            val tzRow = rows.single { it.onlyFrom(Addon.TORZ) }
            // Each offers its own add-on's file through that add-on's own link: MediaFusion's season and episode, Torz's index.
            assertEquals(ReleaseKey(ha, null), mfRow.offers.single().release)
            assertTrue(mfRow.offers.single().link.reveal().contains("/$ha/1/6/"))
            assertEquals(ReleaseKey(ha, 5), tzRow.offers.single().release)
            assertTrue(tzRow.offers.single().link.reveal().contains("/rd/$ha/5/"))
            assertEquals(amenBytes, tzRow.offers.single().size)
            assertNull(mfRow.offers.single().size)
            // Neither is the other's file where a play looks (Next source, skips, what was tried).
            assertFalse(FileSet.of(mfRow, episode = true).holds(tzRow))
            assertFalse(FileSet.of(tzRow, episode = true).holds(mfRow))
        }
        // What this TV kept of MediaFusion's file (no picture, its own size) is its row's alone.
        val noPicture = Measured(MediaSpec(sizeBytes = amenE7Bytes), CopyHealth(watchedMs = 1_000, noPicture = 1, at = now), now)
        val kept = mapOf(ReleaseKey(ha, null).measuredKey(e6) to noPicture)
        val rows = SourceRank.rank(SourceMerge.merge(listOf(mf, tz), 28, episode = true) { kept[it.measuredKey(e6)]?.spec?.sizeBytes },
            input({ kept[it] }))
        assertEquals(2, rows.size)
        assertEquals(TvFit.LIKELY_NOT, rows.single { it.onlyFrom(Addon.MEDIAFUSION) }.fit)
        val tzRow = rows.single { it.onlyFrom(Addon.TORZ) }
        assertNull(tzRow.measured)
        assertNotEquals(TvFit.LIKELY_NOT, tzRow.fit)
        assertEquals(amenBytes, tzRow.facts.sizeBytes)
    }

    @Test
    fun anAlignedPackIsOneRowByItsFileNamesEachOfferKeepingItsOwnLink() {
        val mf = parsed(Addon.MEDIAFUSION, answer(mfPack(ha, amenFile, 10_200_000_000L, "10.2 GB", "WEBRip")))
        val tz = parsed(Addon.TORZ, answer(torz(ha, 5, amenFile, amenBytes)))
        for (order in listOf(listOf(mf, tz), listOf(tz, mf))) {
            val row = SourceMerge.merge(order, 28, episode = true).single()
            assertEquals(setOf(Addon.MEDIAFUSION, Addon.TORZ), row.addons.toSet())
            val mfOffer = row.offers.single { it.addon == Addon.MEDIAFUSION }
            val tzOffer = row.offers.single { it.addon == Addon.TORZ }
            assertEquals(ReleaseKey(ha, null), mfOffer.release)
            assertTrue(mfOffer.link.reveal().contains("/$ha/1/6/"))
            assertEquals(ReleaseKey(ha, 5), tzOffer.release)
            assertTrue(tzOffer.link.reveal().contains("/rd/$ha/5/"))
            assertEquals(amenBytes, row.facts.sizeBytes)
        }
    }

    @Test
    fun anAlignedPackIsOneRowByItsSizeToTheByteAlone() {
        val tz = parsed(Addon.TORZ, answer(torz(ha, 5, amenFile, amenBytes)))
        // MediaFusion names no file, but gives the file's size to the byte ("1.01 GB / 10.2 GB" and its videoSize).
        val sized = parsed(Addon.MEDIAFUSION, answer(noName(mfPack(ha, amenFile, amenBytes, "1.01 GB / 10.2 GB", "WEBRip"))))
        assertNull(sized.single().offer.filename)
        assertTrue(sized.single().exactSize)
        assertEquals(1, SourceMerge.merge(listOf(sized, tz), 28, episode = true).size)
        // Only its pack's size, and no name: nothing tells, two rows...
        val bare = parsed(Addon.MEDIAFUSION, answer(noName(mfPack(ha, amenFile, 10_200_000_000L, "10.2 GB", "WEBRip"))))
        assertEquals(2, SourceMerge.merge(listOf(bare, tz), 28, episode = true).size)
        // ...until its file plays here and measures Torz's size to the byte: one.
        val mfKey = ReleaseKey(ha, null).measuredKey(e6)
        val same = mapOf(mfKey to amenBytes)
        val one = SourceMerge.merge(listOf(bare, tz), 28, episode = true) { same[it.measuredKey(e6)] }.single()
        assertEquals(setOf(Addon.MEDIAFUSION, Addon.TORZ), one.addons.toSet())
        // Another size: another file.
        val other = mapOf(mfKey to 1_300_000_000L)
        assertEquals(2, SourceMerge.merge(listOf(bare, tz), 28, episode = true) { other[it.measuredKey(e6)] }.size)
        // Sizes to the byte decide before names: the same name of another size is another file.
        val named = parsed(Addon.MEDIAFUSION, answer(mfPack(ha, amenFile, 10_200_000_000L, "10.2 GB", "WEBRip")))
        assertEquals(2, SourceMerge.merge(listOf(named, tz), 28, episode = true) { other[it.measuredKey(e6)] }.size)
    }

    @Test
    fun twoIndexesOfOneTorrentAreNeverOneRow() {
        // Torrentio's index 5 and Torz's index 6 of one pack, of one name and nearly one size: two files.
        val tio = parsed(Addon.TORRENTIO, answer(torrentio(ha, idx = 5, quality = "4k DV | HDR",
            name = amenFile.removeSuffix(".mkv"), size = "1.01 GB")))
        val tz = parsed(Addon.TORZ, answer(torz(ha, 6, amenFile, amenBytes)))
        assertEquals(tio.single().sameName, tz.single().sameName)
        assertEquals(2, SourceMerge.merge(listOf(tio, tz), 28, episode = true).size)
        assertEquals(2, SourceMerge.merge(listOf(tio, tz), 28, episode = false).size)
        // Not by the size to the byte either (Torrentio's file measured here at Torz's size).
        val rows = SourceMerge.merge(listOf(tio, tz), 28, episode = true) { if (it == ReleaseKey(ha, 5)) amenBytes else null }
        assertEquals(2, rows.size)
        val five = rows.single { it.release == ReleaseKey(ha, 5) }
        val six = rows.single { it.release == ReleaseKey(ha, 6) }
        assertEquals(FileSet.exactSize(five), FileSet.exactSize(six))
        assertFalse(FileSet.of(five, episode = true).holds(six))
        assertFalse(FileSet.of(six, episode = true).holds(five))
    }

    @Test
    fun aMeasurementThatTellsTheTwoApartPartsTheRowAndTheyShareNothing() {
        // One row by their names (MediaFusion's pack offer and Torz's index 5)...
        assertEquals(1, ranked().count { it.holds(ha) })
        // ...until MediaFusion's file played here measured another size than Torz's to the byte, and showed no picture.
        val other = 1_300_000_000L
        val played = Measured(MediaSpec(sizeBytes = other, overallBps = 6_190_000), CopyHealth(watchedMs = 1_000, noPicture = 1, at = now), now)
        val rows = ranked(mapOf(ReleaseKey(ha, null).measuredKey(e6) to played)).filter { it.holds(ha) }
        assertEquals(2, rows.size)
        val mf = rows.single { it.onlyFrom(Addon.MEDIAFUSION) }
        val tz = rows.single { Addon.TORZ in it.addons }
        // Its size, rate and health are MediaFusion's file's alone.
        assertEquals(played, mf.measured)
        assertEquals(other, mf.facts.sizeBytes)
        assertEquals(TvFit.LIKELY_NOT, mf.fit)
        assertNull(tz.measured)
        assertEquals(amenBytes, tz.facts.sizeBytes)
        assertEquals(5.2, SourceRank.asPlayedHere(tz.facts, tz.measured).avgMbps!!, 0.1)
        assertNotEquals(TvFit.LIKELY_NOT, tz.fit)
        assertFalse(FileSet.of(mf, episode = true).holds(tz))
        assertFalse(FileSet.of(tz, episode = true).holds(mf))
    }

    @Test
    fun aRowToldApartWhileItPlaysLeavesTheOtherAddonsFileFree() {
        answerOf[Addon.TORRENTIO] = { answer() }
        answerOf[Addon.TORZ] = { answer(torz(ha, 5, amenFile, amenBytes)) }
        list()
        // MediaFusion asked again, so Torz answered first: the one row keeps Torz's key.
        engine.dropAnswer(ep6, Addon.MEDIAFUSION)
        answerOf[Addon.MEDIAFUSION] = { answer(mfPack(ha, amenFile, 10_200_000_000L, "10.2 GB", "WEBRip")) }
        val l = list()
        val row = l.sources.single()
        assertEquals(setOf(Addon.MEDIAFUSION, Addon.TORZ), row.addons.toSet())
        assertEquals(ReleaseKey(ha, 5).storeKey, row.key)
        val session = engine.session(PlayRequest(ep6, StartAt.Beginning, PlayOrigin.SOURCES_PANEL, list = l, chosen = row))
        val ready = open(session).last() as Opened.Ready
        assertEquals(Addon.MEDIAFUSION, ready.offer.addon)
        session.started(ready.source, ready.offer)
        assertNull(session.nextPick())
        // MediaFusion's file measured here at another size than Torz's: the row parts on the next rank.
        kept[ReleaseKey(ha, null).measuredKey(e6)] = Measured(MediaSpec(sizeBytes = 1_300_000_000L, overallBps = 6_190_000),
            CopyHealth(watchedMs = 60_000, at = now), now)
        engine.rerank(ep6)
        loop.advance()
        val parted = session.list!!
        assertEquals(2, parted.sources.size)
        val mf = parted.sources.single { it.onlyFrom(Addon.MEDIAFUSION) }
        val tz = parted.sources.single { it.onlyFrom(Addon.TORZ) }
        // Torz's row (under the key the row played had) is another file: not the one playing, nor one tried.
        assertEquals(ready.source.key, tz.key)
        assertEquals(mf.key, session.rowKey(ready.source))
        assertEquals(tz.key, session.nextPick()?.key)
        val view = SourcesModel.view(parted, session.rowKey(ready.source), session.attempts, next = session::nextPick)
        assertEquals(mf.key, view.playing?.key)
        assertEquals(tz.key, view.next?.key)
        // MediaFusion's file shows no picture: Torz's file is not skipped with it, and plays through its own link and index.
        assertEquals(Step.TryNext, session.failed(ready.source, Failure(FailureKind.NO_PICTURE)))
        val next = open(session, why = OpenWhy.FAILED).last() as Opened.Ready
        assertEquals(tz.key, next.source.key)
        assertEquals(Addon.TORZ, next.offer.addon)
        assertEquals(ReleaseKey(ha, 5), next.offer.release)
        assertTrue(next.offer.link.reveal().contains("/rd/$ha/5/"))
    }

    @Test
    fun noPictureFromMediaFusionsFileNeverSkipsTorzsOtherFileOfThePack() {
        answerOf[Addon.TORRENTIO] = { answer() }
        answerOf[Addon.MEDIAFUSION] = { answer(mfOther()) }
        answerOf[Addon.TORZ] = { answer(torz(ha, 5, amenFile, amenBytes)) }
        val l = list()
        assertEquals(2, l.sources.size)
        val mf = l.sources.single { it.onlyFrom(Addon.MEDIAFUSION) }
        val tz = l.sources.single { it.onlyFrom(Addon.TORZ) }
        val session = engine.session(PlayRequest(ep6, StartAt.Beginning, PlayOrigin.SOURCES_PANEL, list = l, chosen = mf))
        val ready = open(session).last() as Opened.Ready
        assertEquals(Addon.MEDIAFUSION, ready.offer.addon)
        assertTrue(ready.offer.link.reveal().contains("/$ha/1/6/"))
        assertEquals(Step.TryNext, session.failed(ready.source, Failure(FailureKind.NO_PICTURE)))
        val next = open(session, why = OpenWhy.FAILED).last() as Opened.Ready
        assertEquals(tz.key, next.source.key)
        assertEquals(ReleaseKey(ha, 5), next.offer.release)
        assertTrue(next.offer.link.reveal().contains("/rd/$ha/5/"))
        // Nor does what this TV keeps of it: MediaFusion's row is set aside, Torz's isn't.
        kept[ReleaseKey(ha, null).measuredKey(e6)] = Measured(null, CopyHealth(watchedMs = 1_000, noPicture = 1, at = now), now)
        engine.rerank(ep6)
        loop.advance()
        val after = engine.cached(ep6)!!.sources
        assertTrue(after.single { it.onlyFrom(Addon.MEDIAFUSION) }.measured?.health?.setAside == true)
        assertNull(after.single { it.onlyFrom(Addon.TORZ) }.measured)
    }

    @Test
    fun theNextEpisodeFromAPackPlaysItsOwnFileThroughEachAddonsOwnLink() {
        answerOf[Addon.TORRENTIO] = { t -> if (t.episode == e7) answer() else tioAnswer() }
        // E6 played from the Amen row (MediaFusion's and Torz's offers: one file by its name); what this TV kept of E6's
        // files never applies to E7's.
        val (_, played) = amenPlaying()
        // As the player hands it on: the copy playing with the offer it plays from ([SourcePlayback.nextRequest]).
        val like = played.source.copy(offers = listOf(played.offer))
        val sick = Measured(null, CopyHealth(watchedMs = 1_000, noPicture = 1, at = now), now)
        kept[ReleaseKey(ha, null).measuredKey(e6)] = sick
        kept[ReleaseKey(ha, 5).storeKey] = sick
        // Play next episode: the same kind (MediaFusion's binge group), E7's own file.
        val next = engine.session(PlayRequest(ep7, StartAt.Beginning, PlayOrigin.NEXT_EPISODE, like = like))
        val ready = open(next).last() as Opened.Ready
        assertEquals(setOf(Addon.MEDIAFUSION, Addon.TORZ), ready.source.addons.toSet())
        assertTrue(ready.source.offers.none { it.release == ReleaseKey(ha, 5) })
        assertNull(ready.source.measured)
        assertEquals(Addon.MEDIAFUSION, ready.offer.addon)
        assertTrue(ready.offer.link.reveal().contains("/$ha/1/7/"))
        assertTrue(ready.source.offers.single { it.addon == Addon.TORZ }.link.reveal().contains("/rd/$ha/6/"))
        // Resumed from the Episodes sheet on the file E7 played from (Torz's index 6): that one, from its own link.
        val resumed = engine.session(PlayRequest(ep7, StartAt.At(91_000), PlayOrigin.NEXT_EPISODE,
            preferred = LastRelease(Addon.TORZ, ReleaseKey(ha, 6), 1L), like = like))
        val again = open(resumed).last() as Opened.Ready
        assertEquals(ReleaseKey(ha, 6), again.offer.release)
        assertTrue(again.offer.link.reveal().contains("/rd/$ha/6/"))
        // MediaFusion's file for E7 is the pack's E8 (it numbers it differently): two rows, so the binge group pulls
        // MediaFusion's own E7 link alone, never with Torz's E7 file in its row (nor Torz's link with MediaFusion's file).
        answerOf[Addon.MEDIAFUSION] = { t -> if (t.episode == e7) answer(mfOther(amenFileE8, e7)) else mfAnswer() }
        engine.forget()
        loop.advance()
        val other = engine.session(PlayRequest(ep7, StartAt.Beginning, PlayOrigin.NEXT_EPISODE, like = like))
        val pick = open(other).last() as Opened.Ready
        val rows = other.list!!.sources
        assertEquals(2, rows.size)
        assertEquals(listOf(ReleaseKey(ha, 6)), rows.single { it.onlyFrom(Addon.TORZ) }.offers.map { it.release })
        assertEquals(listOf(ReleaseKey(ha, null)), rows.single { it.onlyFrom(Addon.MEDIAFUSION) }.offers.map { it.release })
        assertEquals(1, pick.source.offers.size)
        // E6 played from MediaFusion's offer: E7 from MediaFusion's too, as when its row was MediaFusion's alone.
        assertEquals(Addon.MEDIAFUSION, pick.offer.addon)
        assertTrue(pick.offer.link.reveal().contains("/$ha/1/7/"))
    }

    @Test
    fun theNextEpisodeFollowsTheAddonItsCopyPlayedFromWhenThePacksRowsPart() {
        // StremThru Torz gives each torrent its own binge group ("torz:<hash>"); MediaFusion numbers E7 as the pack's E8
        // file, so E7's two rows of the pack part. E6 played from the Amen row of both add-ons, from MediaFusion's offer.
        answerOf[Addon.TORRENTIO] = { t -> if (t.episode == e7) answer() else tioAnswer() }
        answerOf[Addon.MEDIAFUSION] = { t -> if (t.episode == e7) answer(mfOther(amenFileE8, e7)) else mfAnswer() }
        answerOf[Addon.TORZ] = { t ->
            if (t.episode == e7) answer(torz(ha, 6, amenFileE7, amenE7Bytes, group = "torz:$ha"))
            else answer(torz(ha, 5, amenFile, amenBytes, group = "torz:$ha"), torz(hg, 5, g66File, g66Bytes, "WEB-DL", "torz:$hg"))
        }
        val (_, played) = amenPlaying()
        assertEquals(setOf(Addon.MEDIAFUSION, Addon.TORZ), played.source.addons.toSet())
        // As the player hands it on: the copy playing with the offer it plays from. E7 from MediaFusion's own link.
        val viaMf = engine.session(PlayRequest(ep7, StartAt.Beginning, PlayOrigin.NEXT_EPISODE,
            like = played.source.copy(offers = listOf(played.offer))))
        val mf = open(viaMf).last() as Opened.Ready
        assertEquals(2, viaMf.list!!.sources.size)
        assertEquals(Addon.MEDIAFUSION, mf.offer.addon)
        assertEquals(ReleaseKey(ha, null), mf.offer.release)
        assertTrue(mf.offer.link.reveal().contains("/$ha/1/7/"))
        // Had E6 played from Torz's offer of the row: E7 from Torz's, its own index.
        val tzOffer = played.source.offers.single { it.addon == Addon.TORZ }
        val viaTorz = engine.session(PlayRequest(ep7, StartAt.Beginning, PlayOrigin.NEXT_EPISODE,
            like = played.source.copy(offers = listOf(tzOffer))))
        val tz = open(viaTorz).last() as Opened.Ready
        assertEquals(Addon.TORZ, tz.offer.addon)
        assertEquals(ReleaseKey(ha, 6), tz.offer.release)
        assertTrue(tz.offer.link.reveal().contains("/rd/$ha/6/"))
    }

    // ---- An expired link: the same file again, by what it is ----

    /**
     * Torz answered first, so the Amen row of both add-ons is keyed by Torz's file, and plays from MediaFusion's offer;
     * then MediaFusion's file measured here at another size than Torz's, and the row parted: Torz's row keeps the key.
     * The session, the copy playing, and the two rows.
     */
    private fun partedWhilePlaying(): Pair<Pair<SourceSession, Opened.Ready>, Pair<PlaybackSource, PlaybackSource>> {
        answerOf[Addon.TORRENTIO] = { answer() }
        answerOf[Addon.TORZ] = { answer(torz(ha, 5, amenFile, amenBytes)) }
        list()
        engine.dropAnswer(ep6, Addon.MEDIAFUSION)
        answerOf[Addon.MEDIAFUSION] = { answer(mfPack(ha, amenFile, 10_200_000_000L, "10.2 GB", "WEBRip")) }
        val l = list()
        val row = l.sources.single()
        assertEquals(ReleaseKey(ha, 5).storeKey, row.key)
        val session = engine.session(PlayRequest(ep6, StartAt.Beginning, PlayOrigin.SOURCES_PANEL, list = l, chosen = row))
        val ready = open(session).last() as Opened.Ready
        assertEquals(Addon.MEDIAFUSION, ready.offer.addon)
        session.started(ready.source, ready.offer)
        kept[ReleaseKey(ha, null).measuredKey(e6)] = Measured(MediaSpec(sizeBytes = 1_300_000_000L, overallBps = 6_190_000),
            CopyHealth(watchedMs = 60_000, at = now), now)
        engine.rerank(ep6)
        loop.advance()
        val parted = session.list!!
        assertEquals(2, parted.sources.size)
        val mf = parted.sources.single { it.onlyFrom(Addon.MEDIAFUSION) }
        val tz = parted.sources.single { it.onlyFrom(Addon.TORZ) }
        assertEquals(ready.source.key, tz.key)
        return (session to ready) to (mf to tz)
    }

    /** [session]'s refresh of [source]'s link from [addon], as the player asks for it after [Step.Refresh]. */
    private fun refresh(session: SourceSession, source: PlaybackSource, addon: Addon): List<Opened> {
        val got = ArrayList<Opened>()
        session.refresh(source, addon) { got += it }
        loop.advance()
        return got
    }

    @Test
    fun anExpiredLinkOfAMergedPackRowIsRefreshedThoughItsRowWasKeyedAgain() {
        // MediaFusion answered first: the Amen row of both add-ons is keyed by its file, and plays from its offer.
        val (session, ready) = amenPlaying()
        assertEquals(ReleaseKey(ha, null).storeKey, ready.source.key)
        assertEquals(Step.Refresh, session.failed(ready.source, Failure(FailureKind.LINK_EXPIRED, Addon.MEDIAFUSION)))
        // Its answer is asked for again and joins the list last: the row is keyed by Torz's file now.
        val again = refresh(session, ready.source, Addon.MEDIAFUSION).last() as Opened.Ready
        assertEquals(ReleaseKey(ha, 5).storeKey, again.source.key)
        // The same file from MediaFusion's fresh link, not another copy, and not a switch.
        assertEquals(Addon.MEDIAFUSION, again.offer.addon)
        assertEquals(ReleaseKey(ha, null), again.offer.release)
        assertTrue(again.offer.link.reveal().contains("/$ha/1/6/"))
        assertTrue(session.maySwitch(loop.now))
        // It expires again: refreshed once only (its file's, under whichever key), so the next copy.
        session.started(again.source, again.offer)
        assertEquals(Step.TryNext, session.failed(again.source, Failure(FailureKind.LINK_EXPIRED, Addon.MEDIAFUSION)))
    }

    @Test
    fun anExpiredLinkAfterTheRowPartedRefreshesItsOwnFileNeverTheOtherAddons() {
        val (sr, rows) = partedWhilePlaying()
        val (session, ready) = sr
        val (mf, tz) = rows
        assertEquals(Step.Refresh, session.failed(ready.source, Failure(FailureKind.LINK_EXPIRED, Addon.MEDIAFUSION)))
        val again = refresh(session, ready.source, Addon.MEDIAFUSION).last() as Opened.Ready
        // MediaFusion's own row (its file), from its fresh link: never Torz's row under the key the copy had.
        assertEquals(mf.key, again.source.key)
        assertNotEquals(tz.key, again.source.key)
        assertEquals(Addon.MEDIAFUSION, again.offer.addon)
        assertTrue(again.offer.link.reveal().contains("/$ha/1/6/"))
        assertTrue(session.maySwitch(loop.now))
    }

    @Test
    fun aSwitchToTheOtherAddonsFileUnderTheOldKeyThatWontStartGoesBackToTheFilePlaying() {
        val (sr, rows) = partedWhilePlaying()
        val (session, ready) = sr
        val (mf, tz) = rows
        // Next source: Torz's file, under the key the copy playing had; it loads.
        val torz = open(session, why = OpenWhy.USER).last() as Opened.Ready
        assertEquals(tz.key, torz.source.key)
        assertEquals(Addon.TORZ, torz.offer.addon)
        // While it loads it is the copy Playing in Sources (its own row), and Next source isn't it.
        assertEquals(tz.key, session.rowKey(torz.source))
        assertNotEquals(tz.key, session.nextPick()?.key)
        // It is slow to start: Torz's file is left, not MediaFusion's, which played fine...
        assertEquals(Step.TryNext, session.failed(torz.source, Failure(FailureKind.SLOW)))
        val back = open(session, why = OpenWhy.FAILED).last() as Opened.Ready
        assertEquals(mf.key, back.source.key)
        assertEquals(Addon.MEDIAFUSION, back.offer.addon)
        assertEquals(ReleaseKey(ha, null), back.offer.release)
        // ...and Torz's file isn't named again.
        session.started(back.source, back.offer)
        assertNull(session.nextPick())
        assertEquals(mf.key, session.rowKey(ready.source))
    }

    @Test
    fun torzsPackWhoseFilesItDoesntKnowYetNeverReadsAnotherEpisodesEarlierRecord() {
        // StremThru Torz lists the Amen pack before it knows its files: "-1" for the index in its link, no file name and
        // no size. MediaFusion lists the same pack (no index either).
        val unknown = JSONObject().apply {
            put("name", "⚡️ [RD] Torz\n2160p")
            put("description", "💿 WEBRip 🎞️ HEVC\n📺 DV HDR 🎧 DDP | 5.1\n👤 12\n📁 Your.Friendly.Neighborhood.Spider-Man.S01.2160p.WEBRip-Amen")
            put("url", "https://torz.invalid/stremio/torz/FAKECONFIG_TORZ/_/strem/tt0000077/rd/$ha/-1/")
            put("behaviorHints", JSONObject().put("bingeGroup", "torz:$ha"))
        }
        assertEquals(ReleaseKey(ha, null), parsed(Addon.TORZ, answer(unknown)).single().release)
        answerOf[Addon.TORRENTIO] = { answer() }
        answerOf[Addon.TORZ] = { answer(unknown) }
        // What 1.0.360 kept under the pack's key alone: whichever episode last played from it (here a short file, not
        // the whole episode).
        val other = Measured(MediaSpec(sizeBytes = 300_000_000L, overallBps = 1_430_000), CopyHealth(watchedMs = 1_000, notWhole = 1, at = now), now)
        kept["rd|$ha|-1"] = other
        val row = list().sources.holding(ha)
        assertEquals(setOf(Addon.MEDIAFUSION, Addon.TORZ), row.addons.toSet())
        // None of it is E6's: not set aside, not its size or rate.
        assertNull(row.measured)
        assertNotEquals(TvFit.LIKELY_NOT, row.fit)
        assertNotEquals(300_000_000L, row.facts.sizeBytes)
        assertNull(FileSet.exactSize(row))
        assertFalse(ReleaseKey.indexesEveryFile(Addon.TORZ))
        assertFalse(ReleaseKey.indexesEveryFile(Addon.MEDIAFUSION))
        assertTrue(ReleaseKey.indexesEveryFile(Addon.TORRENTIO))
    }
}
