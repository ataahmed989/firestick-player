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

    /** As StremThru Torz writes one: the file's own size (videoSize) and its index in the link. */
    private fun torz(hash: String, idx: Int, file: String, videoSize: Long, origin: String = "WEBRip") = JSONObject().apply {
        put("name", "⚡️ [RD] Torz\n2160p")
        put("description", "💿 $origin 🎞️ HEVC\n📺 DV HDR 🎧 DDP | 5.1\n💾 1.01 GB 📦 10.2 GB 👤 12\n📄 $file")
        put("url", "https://torz.invalid/stremio/torz/FAKECONFIG_TORZ/_/strem/tt0000077/rd/$hash/$idx/$file")
        put("behaviorHints", JSONObject().put("videoSize", videoSize).put("filename", file))
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
    fun aFilmsFilesKeepToHashAndIndex() {
        // A film's hash with and without an index (a collection holds several films): two rows, as before.
        val mf = StreamParser.parse(Addon.MEDIAFUSION, answer(SourceFixtures.mediaFusion(ha, fileIdx = null, videoSize = 31_420_000_000L)), 120).streams
        val tio = StreamParser.parse(Addon.TORRENTIO, answer(torrentio(ha, idx = 2, size = "12.00 GB")), 120).streams
        assertEquals(2, SourceMerge.merge(listOf(mf, tio), 120).size)
        // An episode's: one.
        assertEquals(1, SourceMerge.merge(listOf(mf, tio), 120, episode = true).size)
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
    fun anUnknownRateNeverBeatsACopyThatPlayedWellHereAtARateMeasuredHere() {
        // MediaFusion's other pack alone beside the G66 copy: its rate isn't known (its only size was the pack's).
        val answers = listOf(parsed(Addon.MEDIAFUSION, answer(mfPack(hp, packFile, 28_800_000_000L, "28.8 GB", "WEB-DL"))),
            parsed(Addon.TORZ, answer(torz(hg, 5, g66File, g66Bytes, origin = "WEB-DL"))))
        val rows = SourceMerge.merge(answers, 28, episode = true)
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
        val filmRow = SourceRank.rank(SourceMerge.merge(listOf(film), 120),
            RankInput(plan, listOf("en"), { if (it == "rd|$ha|-1") oldShared else null }, 120, episode = false)).single()
        assertEquals(oldShared, filmRow.measured)
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
        assertEquals(listOf(hp, hg, hn), order.map { it.release?.infoHash })
        // Its row in no form: its own group from the top, then the groups below.
        val without = list.filterNot { it.key == amen.key }
        assertEquals(listOf(hp, hg, hn), SourceRank.nextOrder(without, "rd|gone|-1", film = false, current = amen) { same.holds(it) }
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
        // The same pack, one side without the index: the same episode file.
        assertTrue(FileSet.of(mfOnly, episode = true).holds(torzOnly))
        assertTrue(FileSet.of(torzOnly, episode = true).holds(mfOnly))
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
}
