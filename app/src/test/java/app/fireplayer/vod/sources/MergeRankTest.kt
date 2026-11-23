package app.fireplayer.vod.sources

import app.fireplayer.player.CopyHealth
import app.fireplayer.player.MediaSpec
import app.fireplayer.vod.Addon
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.Measured
import app.fireplayer.vod.Offer
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.ReleaseKey
import app.fireplayer.vod.SecretUrl
import app.fireplayer.vod.SourceFacts
import app.fireplayer.vod.SourceGroup
import app.fireplayer.vod.TvFit
import app.fireplayer.vod.TvPlan
import app.fireplayer.vod.sources.SourceFixtures.H1
import app.fireplayer.vod.sources.SourceFixtures.H2
import app.fireplayer.vod.sources.SourceFixtures.H3
import app.fireplayer.vod.sources.SourceFixtures.answer
import app.fireplayer.vod.sources.SourceFixtures.mediaFusion
import app.fireplayer.vod.sources.SourceFixtures.torrentio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MergeRankTest {

    /** The user's Samsung TU7000: HDR10 and HDR10+ equal, then HLG; no Dolby Vision; 4K; no passthrough known. */
    private val tu7000 = TvPlan(true, listOf(setOf(Hdr.HDR10, Hdr.HDR10_PLUS), setOf(Hdr.HLG)), 2160, emptySet())

    private fun input(plan: TvPlan = tu7000, measured: (String) -> Measured? = { null }) =
        RankInput(plan, listOf("en"), measured, 120, episode = false)

    private fun tio(vararg rows: org.json.JSONObject) = StreamParser.parse(Addon.TORRENTIO, answer(*rows), 120).streams
    private fun mf(vararg rows: org.json.JSONObject) = StreamParser.parse(Addon.MEDIAFUSION, answer(*rows), 120).streams

    @Test
    fun sameHashAcrossAddonsIsOneRowWithBothAddonsReadyFirstThenTorrentio() {
        val merged = SourceMerge.merge(listOf(mf(mediaFusion(H1, fileIdx = 0)), tio(torrentio(H1, idx = 0, ready = false))), 120)
        val row = merged.single()
        assertTrue(row.bothAddons)
        assertEquals(Readiness.PROBABLY_READY, row.readiness)
        // MediaFusion said ready, Torrentio didn't: MediaFusion's link first.
        assertEquals(listOf(Addon.MEDIAFUSION, Addon.TORRENTIO), row.offers.map { it.addon })
        // MediaFusion's exact size, Torrentio's seeders.
        assertEquals(31_420_000_000L, row.facts.sizeBytes)
        assertEquals(593, row.facts.seeders)
        // The key stays the first answer's.
        assertEquals("rd|$H1|0", row.key)
    }

    @Test
    fun bothReadyPutsTorrentioFirst() {
        val row = SourceMerge.merge(listOf(mf(mediaFusion(H1)), tio(torrentio(H1, idx = 0))), 120).single()
        assertEquals(Addon.TORRENTIO, row.offers.first().addon)
    }

    @Test
    fun reUploadsWithTheSameNameAndSizeMergeAsCopies() {
        val name = "Film.Name.2021.1080p.WEB-DL.DDP5.1.H.264-GRP"
        val a = torrentio(H1, quality = "1080p", name = name, size = "10.00 GB")
        val b = torrentio(H2, quality = "1080p", name = "$name[TGx]", size = "10.05 GB")
        val c = torrentio(H3, quality = "1080p", name = name, size = "12.00 GB")
        val merged = SourceMerge.mergeKeyed(listOf(tio(a, b, c)), 120)
        val rows = merged.sources
        // Both releases lead to the merged row (a Continue card finds its file in a ×2 row).
        assertEquals(rows[0].key, merged.rowOf["rd|$H2|0"])
        assertEquals(rows[0].key, merged.rowOf["rd|$H1|0"])
        assertEquals(2, rows.size)
        assertEquals(2, rows[0].copies)
        assertEquals(1, rows[1].copies)
    }

    @Test
    fun groupsFollowReadinessCinemaAndHeight() {
        val rows = SourceMerge.merge(
            listOf(
                tio(
                    torrentio(H1),
                    torrentio(H2, quality = "720p", name = "Film.720p.WEB-DL.x264-A"),
                    torrentio(H3, ready = false, quality = "1080p", name = "Film.1080p.BluRay.x264-B"),
                    torrentio("6".repeat(40), quality = "CAM", name = "Film.2026.HDCAM.x264"),
                    torrentio("7".repeat(40), quality = "WEB-DL", name = "Film.WEB-DL.x264-C"),
                ),
            ),
            120,
        )
        assertEquals(
            listOf(SourceGroup.UHD, SourceGroup.LOWER, SourceGroup.NOT_ON_RD, SourceGroup.CINEMA, SourceGroup.OTHER),
            rows.map { it.group },
        )
        assertFalse(rows[2].automatic)
        assertFalse(rows[3].automatic)
    }

    private fun source(
        key: String,
        height: Int?,
        hdr: Set<Hdr> = emptySet(),
        mbps: Double? = 20.0,
        codec: String? = "HEVC",
        dvProfile: Int? = null,
        ready: Readiness = Readiness.PROBABLY_READY,
        langs: List<String> = emptyList(),
        origin: String? = "WEB-DL",
        hash: String? = null,
    ) = PlaybackSource(
        key = key,
        release = hash?.let { ReleaseKey(it, 0) },
        facts = SourceFacts(height = height, hdr = hdr, avgMbps = mbps, codec = codec, dvProfile = dvProfile, languages = langs, origin = origin),
        offers = listOf(Offer(Addon.TORRENTIO, ready, key, link = SecretUrl.of("https://tio.invalid/$key")!!)),
        readiness = ready,
        group = SourceMerge.groupOf(SourceFacts(height = height), ready, false, false),
    )

    @Test
    fun tu7000PrefersHdr10OverDolbyVisionOnlyAndPutsProfile7Last() {
        val dvOnly = source("dvonly", 2160, setOf(Hdr.DOLBY_VISION), mbps = 30.0)
        val dvP7 = source("p7", 2160, setOf(Hdr.DOLBY_VISION, Hdr.HDR10), mbps = 60.0, dvProfile = 7, origin = "REMUX")
        val hdr10 = source("hdr10", 2160, setOf(Hdr.HDR10), mbps = 25.0)
        val dvBase = source("dvbase", 2160, setOf(Hdr.DOLBY_VISION, Hdr.HDR10), mbps = 26.0)
        val ranked = SourceRank.rank(listOf(dvOnly, dvP7, hdr10, dvBase), input())
        // Dolby Vision with an HDR10 base plays as HDR10 here: the two are equals.
        assertEquals(setOf("dvbase", "hdr10"), ranked.take(2).map { it.key }.toSet())
        assertEquals(TvFit.LIKELY_NOT, ranked.first { it.key == "dvonly" }.fit)
        assertEquals("Needs a Dolby Vision TV", ranked.first { it.key == "dvonly" }.warning)
        assertEquals("Dolby Vision 7: may not play here", ranked.first { it.key == "p7" }.warning)
        assertTrue(SourceRank.best(ranked)?.key in setOf("dvbase", "hdr10"))
    }

    @Test
    fun aProfile7CopyThisTvPlayedAsItsBaseLayerIsHdr10Here() {
        val p7 = source("p7", 2160, setOf(Hdr.DOLBY_VISION, Hdr.HDR10), mbps = 60.0, dvProfile = 7, origin = "REMUX", hash = H1)
        val hdr10 = source("hdr10", 2160, setOf(Hdr.HDR10), mbps = 25.0, hash = H2)
        val asBase = Measured(MediaSpec(videoCodec = "HEVC", width = 3840, height = 2160, bitDepth = 10, hdr = "HDR10", dvBaseLayer = 7), null, null)
        val measured = { key: String -> if (key == "rd|$H1|0") asBase else null }
        val ranked = SourceRank.rank(listOf(hdr10, p7), input(measured = measured))
        // Played here, as HDR10: not "may not play here", and the best.
        val row = ranked.first { it.key == "p7" }
        assertEquals(TvFit.MEASURED_OK, row.fit)
        assertNull(row.warning)
        assertEquals("p7", SourceRank.best(ranked)?.key)
        assertEquals("4K · HDR10 · Remux", app.fireplayer.vod.play.PlayRules.mainLine(row))
        // On a Dolby Vision TV it isn't counted as Dolby Vision either: this TV plays it as HDR10.
        val dvTv = TvPlan(true, listOf(setOf(Hdr.DOLBY_VISION), setOf(Hdr.HDR10, Hdr.HDR10_PLUS)), 2160, emptySet())
        assertEquals(SourceRank.hdrRank(hdr10.facts, dvTv), SourceRank.hdrRank(SourceRank.asPlayedHere(p7.facts, asBase), dvTv))
        // Never played here, or played as Dolby Vision: as the add-ons have it.
        assertEquals("Dolby Vision 7: may not play here", SourceRank.rank(listOf(p7), input()).single().warning)
        val asDv = Measured(MediaSpec(videoCodec = "HEVC", hdr = "Dolby Vision"), null, null)
        assertEquals(p7.facts, SourceRank.asPlayedHere(p7.facts, asDv))
        assertEquals(hdr10.facts, SourceRank.asPlayedHere(hdr10.facts, asBase))
    }

    @Test
    fun aDolbyVisionTvRanksDolbyVisionFirst() {
        val dvTv = TvPlan(true, listOf(setOf(Hdr.DOLBY_VISION), setOf(Hdr.HDR10, Hdr.HDR10_PLUS)), 2160, emptySet())
        val dvOnly = source("dvonly", 2160, setOf(Hdr.DOLBY_VISION), mbps = 25.0)
        val hdr10 = source("hdr10", 2160, setOf(Hdr.HDR10), mbps = 25.0)
        assertEquals("dvonly", SourceRank.rank(listOf(hdr10, dvOnly), input(dvTv)).first().key)
    }

    @Test
    fun anSdrTvPutsSdrFirstAtAboutTheSameData() {
        val sdrTv = TvPlan(true, emptyList(), 2160, emptySet())
        val sdr = source("sdr", 2160, mbps = 22.0)
        val hdr = source("hdr", 2160, setOf(Hdr.HDR10), mbps = 24.0)
        assertEquals("sdr", SourceRank.rank(listOf(hdr, sdr), input(sdrTv)).first().key)
        assertEquals("hdr", SourceRank.rank(listOf(sdr, hdr), input()).first().key)
    }

    @Test
    fun aStarved4kLosesToAWellFed1080p() {
        val starved = source("4k", 2160, mbps = 4.0)
        val fed = source("1080", 1080, mbps = 12.0)
        assertEquals("1080", SourceRank.rank(listOf(starved, fed), input()).first().key)
        // A well-fed 4K beats a bigger 1080p remux.
        val good4k = source("4k", 2160, mbps = 25.0)
        val remux = source("remux", 1080, mbps = 30.0, codec = "H.264", origin = "REMUX")
        assertEquals("4k", SourceRank.rank(listOf(remux, good4k), input()).first().key)
    }

    @Test
    fun notReadyAndCinemaAreNeverBest() {
        val notReady = source("nr", 2160, setOf(Hdr.HDR10), mbps = 40.0, ready = Readiness.NOT_READY)
        val ok = source("ok", 1080, mbps = 8.0)
        val ranked = SourceRank.rank(listOf(notReady, ok), input())
        assertEquals("ok", SourceRank.best(ranked)?.key)
        assertNull(SourceRank.best(SourceRank.rank(listOf(notReady), input())))
    }

    @Test
    fun theSoundWantedAndMeasuredFactsWeigh() {
        val italian = source("it", 2160, setOf(Hdr.HDR10), mbps = 30.0, langs = listOf("it"))
        val english = source("en", 2160, setOf(Hdr.HDR10), mbps = 20.0, langs = listOf("en", "it"))
        assertEquals("en", SourceRank.rank(listOf(italian, english), input()).first().key)
        // Played here smoothly beats a "probably ready" guess; one that kept buffering goes down.
        val played = source("played", 1080, mbps = 8.0, hash = H1)
        val troubled = source("troubled", 2160, setOf(Hdr.HDR10), mbps = 25.0, hash = H2)
        val measured = { key: String ->
            when (key) {
                "rd|$H1|0" -> Measured(MediaSpec(videoCodec = "HEVC", width = 1920, height = 1080), CopyHealth(watchedMs = 3_600_000), null)
                "rd|$H2|0" -> Measured(null, CopyHealth(watchedMs = 600_000, stalls = 12, stallMs = 120_000), null)
                else -> null
            }
        }
        val ranked = SourceRank.rank(listOf(troubled, played), input(measured = measured))
        assertEquals(listOf("played", "troubled"), ranked.map { it.key })
        assertEquals(TvFit.MEASURED_OK, ranked[0].fit)
        assertEquals("Buffered here before", ranked[1].warning)
    }

    @Test
    fun aDataRateCapAndAOneThousandEightyTvRankDown() {
        val capped = tu7000.copy(automatic = false, maxMbps = 30)
        val big = source("big", 2160, setOf(Hdr.HDR10), mbps = 70.0, origin = "REMUX")
        val small = source("small", 2160, setOf(Hdr.HDR10), mbps = 20.0)
        val ranked = SourceRank.rank(listOf(big, small), input(capped))
        assertEquals("small", ranked.first().key)
        assertEquals("Over your 30 Mb/s limit", ranked[1].warning)
        val fhdTv = tu7000.copy(topHeight = 1080)
        val fhd = source("fhd", 1080, mbps = 10.0)
        assertEquals("fhd", SourceRank.rank(listOf(small, fhd), input(fhdTv)).first().key)
    }

    @Test
    fun theSameKindPrefersTheBingeGroupThenAddonAndResolution() {
        fun withGroup(s: PlaybackSource, group: String?) = s.copy(offers = s.offers.map { it.copy(bingeGroup = group) })
        val like = withGroup(source("prev", 1080, mbps = 6.0), "torrentio|1080p|NTb")
        val sameGroup = withGroup(source("same", 1080, mbps = 6.0), "torrentio|1080p|NTb")
        val sameKind = withGroup(source("kind", 1080, mbps = 6.0), "other")
        val other = withGroup(source("4k", 2160, mbps = 25.0), "x")
        assertEquals("same", SourceRank.mostLike(listOf(other, sameKind, sameGroup), like)?.key)
        assertEquals("kind", SourceRank.mostLike(listOf(other, sameKind), like)?.key)
        assertNull(SourceRank.mostLike(listOf(other), like))
    }
}
