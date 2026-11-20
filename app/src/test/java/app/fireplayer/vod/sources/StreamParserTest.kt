package app.fireplayer.vod.sources

import app.fireplayer.vod.Addon
import app.fireplayer.vod.FailureKind
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.Readiness
import app.fireplayer.vod.ReleaseKey
import app.fireplayer.vod.SecretUrl
import app.fireplayer.vod.sources.SourceFixtures.H1
import app.fireplayer.vod.sources.SourceFixtures.H2
import app.fireplayer.vod.sources.SourceFixtures.answer
import app.fireplayer.vod.sources.SourceFixtures.mediaFusion
import app.fireplayer.vod.sources.SourceFixtures.torrentio
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamParserTest {

    @Test
    fun torrentioReadyStreamGivesFactsReleaseAndNoSecretInText() {
        val parsed = StreamParser.parse(Addon.TORRENTIO, answer(torrentio(H1, idx = 3)), runtimeMin = 120)
        val s = parsed.streams.single()
        assertEquals(ReleaseKey(H1, 3), s.release)
        assertEquals(Readiness.PROBABLY_READY, s.offer.readiness)
        assertEquals(2160, s.facts.height)
        assertTrue(Hdr.DOLBY_VISION in s.facts.hdr && Hdr.HDR10 in s.facts.hdr)
        assertEquals("WEB-DL", s.facts.origin)
        assertEquals("HEVC", s.facts.codec)
        assertEquals("DD+ Atmos", s.facts.audio)
        assertEquals("5.1", s.facts.channels)
        assertEquals("FLUX", s.facts.group)
        assertEquals(593, s.facts.seeders)
        assertEquals((29.26 * (1L shl 30)).toLong(), s.facts.sizeBytes)
        // 29.26 GiB over 120 min is about 34.9 Mb/s.
        assertEquals(34.9, s.facts.avgMbps!!, 0.1)
        assertFalse(s.facts.pack)
        assertFalse(s.offer.text.contains("FAKEKEY"))
        assertFalse(s.offer.toString().contains("FAKEKEY"))
        assertTrue(s.offer.link.reveal().contains("FAKEKEY"))
    }

    @Test
    fun torrentioDownloadRowIsNotReadyAndLanguagesComeFromFlags() {
        val row = torrentio(H2, ready = false, quality = "1080p", name = "Film.Name.2021.1080p.BluRay.x264-GRP", langs = "🇬🇧 / 🇮🇹")
        val s = StreamParser.parse(Addon.TORRENTIO, answer(row), null).streams.single()
        assertEquals(Readiness.NOT_READY, s.offer.readiness)
        assertEquals(1080, s.facts.height)
        assertEquals("BluRay", s.facts.origin)
        assertEquals("H.264", s.facts.codec)
        assertEquals(listOf("en", "it"), s.facts.languages)
        assertNull(s.facts.avgMbps)
    }

    @Test
    fun torrentioPackEpisodeKnowsItsFileAndPack() {
        val row = torrentio(
            H1, idx = 4, quality = "1080p", name = "Show.S02.1080p.WEB-DL.DDP5.1.H.264-NTb",
            file = "Show.S02/Show.S02E05.1080p.WEB-DL.DDP5.1.H.264-NTb.mkv", bingeGroup = H1,
        )
        val s = StreamParser.parse(Addon.TORRENTIO, answer(row), 45).streams.single()
        assertTrue(s.facts.pack)
        assertEquals(ReleaseKey(H1, 4), s.release)
        assertEquals("Show.S02E05.1080p.WEB-DL.DDP5.1.H.264-NTb.mkv", s.offer.filename)
    }

    @Test
    fun cinemaQualityAndThreeDAreRecognised() {
        val cam = torrentio(H1, quality = "CAM", name = "Film.Name.2026.HDCAM.x264-NOGRP")
        val ts = torrentio(H2, quality = "TeleSync", name = "Film.Name.2026.TS.x264")
        val parsed = StreamParser.parse(Addon.TORRENTIO, answer(cam, ts), null).streams
        assertTrue(parsed.all { it.cinema })
        val sbs = torrentio(H1, quality = "1080p 3D SBS", name = "Film.Name.2021.1080p.3D.HSBS.BluRay.x264")
        assertTrue(StreamParser.parse(Addon.TORRENTIO, answer(sbs), null).streams.single().threeD)
    }

    @Test
    fun torrentioErrorRowIsANoticeNotASource() {
        val parsed = StreamParser.parse(Addon.TORRENTIO, answer(SourceFixtures.torrentioKeyError()), null)
        assertTrue(parsed.streams.isEmpty())
        assertEquals(FailureKind.ACCOUNT, parsed.notice?.kind)
    }

    @Test
    fun mediaFusionUsesBingeGroupVideoSizeAndFileIdx() {
        val s = StreamParser.parse(Addon.MEDIAFUSION, answer(mediaFusion(H1, fileIdx = 7)), 120).streams.single()
        assertEquals(ReleaseKey(H1, 7), s.release)
        assertEquals(Readiness.PROBABLY_READY, s.offer.readiness)
        assertEquals(2160, s.facts.height)
        assertEquals(setOf(Hdr.DOLBY_VISION, Hdr.HDR10), s.facts.hdr)
        assertEquals("WEB-DL", s.facts.origin)
        assertEquals("HEVC", s.facts.codec)
        assertEquals("DD+ Atmos", s.facts.audio)
        assertEquals("5.1", s.facts.channels)
        assertEquals(31_420_000_000L, s.facts.sizeBytes)
        assertTrue(s.exactSize)
        assertEquals(listOf("en"), s.facts.languages)
        assertFalse(s.offer.text.contains("FAKECONFIG"))
    }

    @Test
    fun mediaFusionHourglassIsNotReady() {
        val s = StreamParser.parse(Addon.MEDIAFUSION, answer(mediaFusion(H2, ready = false, resolution = "1080p")), null)
            .streams.single()
        assertEquals(Readiness.NOT_READY, s.offer.readiness)
        assertEquals(1080, s.facts.height)
    }

    @Test
    fun mediaFusionBusyAndConfigNotices() {
        val busy = StreamParser.parse(Addon.MEDIAFUSION, answer(SourceFixtures.busyRow()), null)
        assertTrue(busy.busy)
        val config = JSONObject().put("name", "MediaFusion").put("description", "Invalid MediaFusion configuration")
            .put("url", "https://mf.invalid/static/exceptions/invalid_config.mp4")
        val parsed = StreamParser.parse(Addon.MEDIAFUSION, answer(config), null)
        assertEquals(FailureKind.CONFIG, parsed.notice?.kind)
        assertTrue(parsed.streams.isEmpty())
    }

    @Test
    fun unreadableAnswerIsEmpty() {
        assertTrue(StreamParser.parse(Addon.TORRENTIO, "<html>", null).streams.isEmpty())
        assertTrue(StreamParser.parse(Addon.TORRENTIO, "{}", null).streams.isEmpty())
    }

    @Test
    fun releaseFromLinksNeverTrustsTheSecretPart() {
        // A secret that happens to be 40 hex digits is not taken for the hash.
        val secret = "f".repeat(40)
        val link = SecretUrl.of("https://tio.invalid/resolve/realdebrid/$secret/$H1/null/2/x.mkv")!!
        assertEquals(ReleaseKey(H1, 2), StreamParser.releaseOf(link, null))
        val mf = SecretUrl.of("https://mf.invalid/streaming_provider/$secret/playback/realdebrid/$H2/1/5/x.mkv")!!
        assertEquals(ReleaseKey(H2, null), StreamParser.releaseOf(mf, null))
        assertNull(StreamParser.releaseOf(SecretUrl.of("https://x.invalid/$secret")!!, null))
    }

    @Test
    fun wordsReadAudioChannelsHdrAndGroups() {
        val w = Words.read("Film.2019.2160p.UHD.BluRay.REMUX.HDR10+.HEVC.TrueHD.7.1.Atmos-FraMeSToR")
        assertEquals(2160, w.height)
        assertEquals("REMUX", w.origin)
        assertEquals("TrueHD Atmos", w.audio)
        assertEquals("7.1", w.channels)
        assertTrue(Hdr.HDR10_PLUS in w.hdr)
        assertFalse(Hdr.HDR10 in w.hdr)
        assertEquals(7, Words.read("Film.2160p.BluRay.DV.FEL.HEVC").dvProfile)
        assertEquals(8, Words.read("Film.2160p.WEB-DL.DV.P8.HEVC").dvProfile)
        assertEquals("DTS-HD MA", Words.read("Film.1080p.BluRay.DTS-HD.MA.5.1.x264").audio)
        assertEquals("DD", Words.read("Show.S01E01.720p.HDTV.DD5.1.x264").audio)
        assertNull(Words.group("Film.2021.1080p.WEB-DL"))
        assertEquals("NTb", Words.group("Show.S01E01.1080p.WEB-DL.DDP5.1.H.264-NTb[eztv].mkv"))
        assertEquals(
            Words.sameName("Film.Name.2021.1080p.WEB-DL-GRP[TGx].mkv"),
            Words.sameName("www.Site.org - Film.Name.2021.1080p.WEB-DL-GRP.mkv"),
        )
        assertEquals(listOf("en", "hi"), Words.languageNames("English + Hindi"))
        assertEquals(listOf(Words.MULTI, "fr"), Words.languages("Multi Audio / 🇫🇷"))
    }
}
