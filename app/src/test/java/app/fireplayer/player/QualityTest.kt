package app.fireplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityTest {

    // --- What a codec string gives away

    @Test
    fun codecStringsTellProfileAndBitDepth() {
        assertEquals("Main 10", MediaSpec.profileIn("hvc1.2.4.L153.B0"))
        assertEquals(10, MediaSpec.bitDepthIn("hvc1.2.4.L153.B0"))
        assertEquals(8, MediaSpec.bitDepthIn("hev1.1.6.L120.90"))
        assertEquals("High", MediaSpec.profileIn("avc1.640028"))
        assertEquals(0, MediaSpec.bitDepthIn("avc1.640028"))
        assertEquals(10, MediaSpec.bitDepthIn("av01.0.08M.10"))
        assertEquals(10, MediaSpec.bitDepthIn("dvhe.08.06"))
        assertNull(MediaSpec.profileIn(null))
    }

    // --- The provider's probe of a file

    @Test
    fun aProviderProbeOfA4kHdrRemux() {
        val s = MediaSpec.fromProvider(
            mapOf(
                "codec_name" to "hevc", "profile" to "Main 10", "width" to "3840", "height" to "2024",
                "pix_fmt" to "yuv420p10le", "color_transfer" to "smpte2084", "avg_frame_rate" to "24000/1001",
                "tags.BPS" to "19800000",
            ),
            mapOf("codec_name" to "truehd", "profile" to "Dolby TrueHD + Dolby Atmos", "channels" to "8", "sample_rate" to "48000"),
            overallKbps = 0,
        )
        assertEquals("HEVC", s.videoCodec)
        assertEquals(10, s.bitDepth)
        assertEquals("HDR10", s.hdr)
        assertEquals(19_800_000L, s.videoBps)
        assertEquals(MediaSpec.How.STATED, s.videoHow)
        assertEquals(23.976f, s.fps, 0.001f)
        assertEquals("TrueHD Atmos 7.1", s.soundLine())
        assertEquals("HEVC · 10-bit · HDR10 · 20 Mb/s", s.pictureLine())
    }

    @Test
    fun withOnlyTheWholeFilesRateThePictureIsTheRestAndSaysSo() {
        val s = MediaSpec.fromProvider(
            mapOf("codec_name" to "h264", "width" to "1920", "height" to "1080", "pix_fmt" to "yuv420p"),
            mapOf("codec_name" to "ac3", "channels" to "6", "bit_rate" to "640000"),
            overallKbps = 8640,
        )
        assertEquals(8_000_000L, s.videoBps)
        assertEquals(MediaSpec.How.ESTIMATED, s.videoHow)
        assertEquals(8, s.bitDepth)
        assertNull(s.hdr)
        assertEquals("DD 5.1", s.soundLine())
    }

    @Test
    fun dolbyVisionFromSideData() {
        val s = MediaSpec.fromProvider(
            mapOf("codec_name" to "hevc", "color_transfer" to "smpte2084", "side_data" to "DOVI configuration record"),
            emptyMap(), 0,
        )
        assertEquals("Dolby Vision", s.hdr)
    }

    @Test
    fun aMeasurementKeptBetweenPlaysReadsBackTheSame() {
        val s = MediaSpec(videoCodec = "HEVC", profile = "Main 10", width = 3840, height = 2024, fps = 23.976f, bitDepth = 10,
            hdr = "HDR10", videoBps = 19_800_000, videoHow = MediaSpec.How.MEASURED, audioCodec = "TrueHD", atmos = true,
            channels = 8, sampleRate = 48000, audioBps = 4_100_000, audioHow = MediaSpec.How.MEASURED, sizeBytes = 15_920_000_000)
        assertEquals(s, MediaSpec.decode(MediaSpec.encode(s)))
        assertNull(MediaSpec.decode("garbage"))
    }

    @Test
    fun measurementsFromSeveralPlaysAreWeightedByTheFilmTheyCovered() {
        val before = MediaSpec(videoBps = 9_000_000, audioBps = 600_000, measuredSecs = 300)
        val now = MediaSpec(videoBps = 3_000_000, audioBps = 600_000, measuredSecs = 100, videoHow = MediaSpec.How.MEASURED)
        val m = MediaSpec.merged(before, now)
        assertEquals(7_500_000L, m.videoBps)
        assertEquals(400L, m.measuredSecs)
        assertEquals(now, MediaSpec.merged(null, now))
    }

    // --- Rates counted as the film loads

    @Test
    fun aTracksRateIsItsBytesOverTheFilmTheyCover() {
        val r = TrackRates()
        // 25 frames a second, 100,000 bytes each: 20 Mb/s.
        for (i in 0..(60 * 25)) r.add("1", i * 40_000L, 100_000)
        assertEquals(20_000_000.0, r.bps("1").toDouble(), 20_000_000 * 0.01)
        assertEquals(60, r.seconds("1"))
    }

    @Test
    fun aJumpDoesNotCountTheGapAsFilm() {
        val r = TrackRates()
        for (i in 0..(30 * 25)) r.add("1", i * 40_000L, 100_000)
        // Ten minutes on.
        for (i in 0..(30 * 25)) r.add("1", 600_000_000L + i * 40_000L, 100_000)
        assertEquals(20_000_000.0, r.bps("1").toDouble(), 20_000_000 * 0.01)
    }

    @Test
    fun framesSlightlyOutOfOrderAreFine() {
        val r = TrackRates()
        // B-frames: decode order 0, 3, 1, 2, 6, 4, 5…
        val order = listOf(0, 3, 1, 2)
        for (gop in 0 until 400) for (o in order) r.add("1", (gop * 4 + o) * 40_000L, 50_000)
        assertEquals(10_000_000.0, r.bps("1").toDouble(), 10_000_000 * 0.02)
    }

    @Test
    fun notEnoughFilmYetSaysNothing() {
        val r = TrackRates()
        for (i in 0..(5 * 25)) r.add("1", i * 40_000L, 100_000)
        assertEquals(0, r.bps("1"))
    }

    @Test
    fun theMergedSourcesIdFindsTheTrack() {
        val r = TrackRates()
        for (i in 0..(60 * 25)) r.add("2", i * 40_000L, 10_000)
        assertTrue(r.bps("0:2") > 0)
        assertEquals(0, r.bps("0:3"))
    }

    // --- Which copy is best

    private fun copy(codec: String, mbps: Double, w: Int = 3840, h: Int = 2160, depth: Int = 10, hdr: String? = "SDR",
        audio: String? = "DD+", channels: Int = 6) =
        MediaSpec(videoCodec = codec, width = w, height = h, bitDepth = depth, hdr = hdr, videoBps = (mbps * 1e6).toLong(),
            videoHow = MediaSpec.How.STATED, audioCodec = audio, channels = channels)

    @Test
    fun moreDataBeatsALargerFrame() {
        // The case that started this: 3840×2024 at 20 Mb/s over 3840×2160 at 6.
        val v = CopyRank.rank(listOf(copy("HEVC", 6.0, h = 2160, audio = "AAC", channels = 2), copy("HEVC", 19.8, h = 2024)), 0, false)
        assertEquals(1, v.best)
        assertEquals(0, v.against)
        assertTrue(v.reasons.first(), v.reasons.first().startsWith("3.3× the picture data"))
        assertTrue(v.reasons.any { it.contains("DD+ 5.1 sound instead of AAC Stereo") })
    }

    @Test
    fun closeCopiesAreToldApartByWidthThenDepthThenSound() {
        val v = CopyRank.rank(listOf(copy("HEVC", 10.0, w = 1920, h = 1080), copy("HEVC", 9.5)), 0, false)
        assertEquals(1, v.best)
        val s = CopyRank.rank(listOf(copy("HEVC", 10.0, audio = "AAC", channels = 2), copy("HEVC", 9.6, audio = "TrueHD", channels = 8)), 0, false)
        assertEquals(1, s.best)
    }

    @Test
    fun aNewerCodecNeedsLessAndTheReasonSaysSo() {
        // H.264 at 12 against HEVC at 9: HEVC packs more picture per bit.
        val v = CopyRank.rank(listOf(copy("H.264", 12.0, depth = 8), copy("HEVC", 9.0)), 0, false)
        assertEquals(1, v.best)
        assertTrue(v.reasons.first(), v.reasons.first().contains("HEVC needs roughly"))
    }

    @Test
    fun hdrWinsOnlyOnAnHdrScreenAndOnlyWithEnoughData() {
        val copies = listOf(copy("HEVC", 15.0, hdr = "SDR"), copy("HEVC", 12.0, hdr = "HDR10"))
        assertEquals(1, CopyRank.rank(copies, 0, hdrScreen = true).best)
        assertEquals(0, CopyRank.rank(copies, 0, hdrScreen = false).best)
        val starved = listOf(copy("HEVC", 15.0, hdr = "SDR"), copy("HEVC", 5.0, hdr = "HDR10"))
        assertEquals(0, CopyRank.rank(starved, 0, hdrScreen = true).best)
    }

    @Test
    fun copiesNobodyMeasuredAreNeverRecommended() {
        val v = CopyRank.rank(listOf(MediaSpec(), MediaSpec(videoCodec = "HEVC")), 0, false)
        assertNull(v.best)
    }

    @Test
    fun thePlayingCopyBeingBestIsComparedWithTheNext() {
        val v = CopyRank.rank(listOf(copy("HEVC", 20.0), copy("HEVC", 6.0)), 0, false)
        assertEquals(0, v.best)
        assertEquals(1, v.against)
    }

    @Test
    fun wholeFileRatesAreOnlyComparedWithEachOther() {
        // One copy has the picture's rate, the other only the whole file's: both are compared by the whole file's.
        val a = MediaSpec(videoCodec = "HEVC", videoBps = 18_000_000, overallBps = 20_000_000)
        val b = MediaSpec(videoCodec = "HEVC", overallBps = 8_000_000)
        val v = CopyRank.rank(listOf(a, b), 1, false)
        assertEquals(0, v.best)
        assertTrue(v.reasons.first(), v.reasons.first().contains("data (whole file)"))
    }

    @Test
    fun aCopyWithOnlyItsPictureRateStillCountsInWholeFileMode() {
        // The copy playing was measured but its size isn't known; the other only has the whole file's rate.
        val playing = MediaSpec(videoCodec = "HEVC", videoBps = 20_000_000, videoHow = MediaSpec.How.MEASURED, audioBps = 640_000)
        val other = MediaSpec(videoCodec = "HEVC", overallBps = 6_000_000)
        val v = CopyRank.rank(listOf(playing, other), 0, false)
        assertEquals(0, v.best)
        assertTrue(v.reasons.isNotEmpty())
    }

    @Test
    fun anEstimatedPictureRateIsComparedAsTheWholeFile() {
        // An estimate (the file's rate less one sound track) is never set against a measured picture rate.
        val measured = MediaSpec(videoCodec = "H.264", videoBps = 5_000_000, videoHow = MediaSpec.How.MEASURED, overallBps = 5_600_000)
        val estimated = MediaSpec(videoCodec = "H.264", videoBps = 5_400_000, videoHow = MediaSpec.How.ESTIMATED, overallBps = 6_000_000)
        val v = CopyRank.rank(listOf(measured, estimated), 0, false)
        assertTrue(v.reasons.joinToString(), v.reasons.any { it.contains("whole file") })
    }

    @Test
    fun bitsPerPixelNeedsTheFrameRate() {
        assertEquals(0.0, MediaSpec(videoBps = 10_000_000, width = 1920, height = 1080).bitsPerPixel, 0.0)
        assertTrue(MediaSpec(videoBps = 10_000_000, width = 1920, height = 1080, fps = 24f).bitsPerPixel > 0)
    }

    // --- How copies have played

    @Test
    fun aCopyThatKeepsBufferingOrFailedIsTroubled() {
        assertTrue(CopyHealth(watchedMs = 20 * 60_000L, stalls = 7, stallMs = 30_000).troubled)
        assertEquals("buffered 7× in 20 min", CopyHealth(watchedMs = 20 * 60_000L, stalls = 7, stallMs = 30_000).summary())
        assertTrue(CopyHealth(failures = 1).troubled)
        assertEquals("failed to play", CopyHealth(failures = 1).summary())
        // One wait in two minutes says little; a failure long since played past is forgiven.
        assertTrue(!CopyHealth(watchedMs = 2 * 60_000L, stalls = 1, stallMs = 3_000).troubled)
        assertTrue(!CopyHealth(watchedMs = 40 * 60_000L, failures = 1).troubled)
        assertEquals("smooth over 40 min", CopyHealth(watchedMs = 40 * 60_000L).summary())
    }

    @Test
    fun aCopyThatPlaysBeatsABetterOneThatBuffers() {
        val copies = listOf(copy("HEVC", 20.0), copy("HEVC", 9.0))
        val health = listOf(CopyHealth(watchedMs = 20 * 60_000L, stalls = 8, stallMs = 60_000), CopyHealth(watchedMs = 30 * 60_000L))
        val v = CopyRank.rank(copies, 0, false, health)
        assertEquals(1, v.best)
        assertEquals("the other buffered 8× in 20 min", v.reasons.first())
        assertTrue(v.reasons.contains("smooth over 30 min"))
        assertTrue(v.tradeOffs.joinToString(), v.tradeOffs.any { it.startsWith("less picture data") })
    }

    @Test
    fun whenEveryCopyBuffersQualityDecidesAndSaysSo() {
        val copies = listOf(copy("HEVC", 20.0), copy("HEVC", 9.0))
        val bad = CopyHealth(watchedMs = 20 * 60_000L, stalls = 8, stallMs = 60_000)
        val v = CopyRank.rank(copies, 1, false, listOf(bad, bad))
        assertEquals(0, v.best)
        assertEquals("buffered 8× in 20 min", v.tradeOffs.first())
    }

    @Test
    fun healthKeptBetweenPlaysAddsUp() {
        val a = CopyHealth(watchedMs = 1000, stalls = 1, stallMs = 200, failures = 0, skips = 1)
        assertEquals(a, CopyHealth.decode(CopyHealth.encode(a)))
        assertEquals(CopyHealth(2000, 2, 400, 0, 2), a + a)
    }

    // --- Which sound is wanted

    @Test
    fun theFilmsOwnLanguageFirstThenEnglish() {
        assertEquals(listOf("en"), CopyLanguage.wanted("en", "en"))
        assertEquals(listOf("en"), CopyLanguage.wanted(null, "en"))
        assertEquals(listOf("ko", "en"), CopyLanguage.wanted("ko", "en"))
        assertEquals(listOf("fr", "en"), CopyLanguage.wanted("fre", "eng"))
        // A Korean film: Korean sound first, English next, then a copy that may have either.
        val w = listOf("ko", "en")
        assertEquals(3, CopyLanguage.fit(w, "ko,en", null))
        assertEquals(2, CopyLanguage.fit(w, "en", null))
        assertEquals(0, CopyLanguage.fit(w, "fr", null))
        assertEquals(1, CopyLanguage.fit(w, "", CopyLanguage.MULTI))
        assertEquals(2, CopyLanguage.fit(w, "", "en"))
        assertEquals("Korean", CopyLanguage.levelName(w, 3))
        assertEquals("English", CopyLanguage.levelName(w, 2))
    }

    @Test
    fun aCopysLanguageFromItsTracksElseItsTag() {
        assertEquals("fr", CopyLanguage.tagIn("|FR| Mission: Impossible - Fallout"))
        assertEquals(CopyLanguage.MULTI, CopyLanguage.tagIn("|MULTI| Mission Impossible Fallout"))
        assertEquals("en", CopyLanguage.tagIn("EN - The Matrix (1999)"))
        assertNull(CopyLanguage.tagIn("Mission: Impossible - Fallout"))
        // What its tracks say beats its tag.
        assertEquals(3, CopyLanguage.fit(listOf("en"), "fr,eng", "fr"))
        assertEquals(0, CopyLanguage.fit(listOf("en"), "fr", "en"))
        assertEquals(1, CopyLanguage.fit(listOf("en"), "", CopyLanguage.MULTI))
        assertEquals(0, CopyLanguage.fit(listOf("en"), "", "fr"))
        assertTrue(CopyLanguage.hasSubs("en,fr", "eng"))
    }

    @Test
    fun theWantedSoundComesBeforeMorePictureData() {
        // A French copy with more data, an English one with less: for an English film, the English one.
        val copies = listOf(copy("HEVC", 20.0), copy("HEVC", 9.0))
        val v = CopyRank.rank(copies, 0, false, fits = listOf(0, 3), fitName = { if (it == 3) "English" else null })
        assertEquals(1, v.best)
        assertEquals("English sound", v.reasons.first())
        // The film's own language over English.
        val korean = CopyRank.rank(copies, 0, false, fits = listOf(2, 3), fitName = { mapOf(3 to "Korean", 2 to "English")[it] })
        assertEquals(1, korean.best)
        assertEquals("Korean sound", korean.reasons.first())
    }

    @Test
    fun subtitlesOnlyTipACloseCall() {
        val close = listOf(copy("HEVC", 10.0), copy("HEVC", 9.6))
        assertEquals(1, CopyRank.rank(close, 0, false, subs = listOf(false, true)).best)
        val far = listOf(copy("HEVC", 20.0), copy("HEVC", 9.0))
        assertEquals(0, CopyRank.rank(far, 1, false, subs = listOf(false, true)).best)
    }
}
