package app.fireplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a copy's lost frames count against it, and copies on a TV that reports no HDR. */
class CopyFramesTest {

    // --- Frames lost, kept with each copy

    @Test
    fun aStringKeptBeforeTheFrameCountsStillReads() {
        // Five fields, as every copy played before the frame counts was kept.
        assertEquals(CopyHealth(600_000, 2, 5_000, 0, 1), CopyHealth.decode("600000|2|5000|0|1"))
        assertEquals(0L, CopyHealth.decode("600000|2|5000|0|1")!!.droppedFrames)
        assertNull(CopyHealth.decode("600000|2"))
        assertNull(CopyHealth.decode(null))
    }

    @Test
    fun theFrameCountsAreKeptAndAnOlderVersionStillReadsTheRest() {
        val h = CopyHealth(watchedMs = 1_200_000, stalls = 1, stallMs = 900, failures = 0, skips = 2, droppedFrames = 431, renderedFrames = 28_000)
        val text = CopyHealth.encode(h)
        assertEquals("1200000|1|900|0|2|431|28000", text)
        assertEquals(h, CopyHealth.decode(text))
        // A version that knows only the first five reads them as it always did.
        assertEquals("1200000|1|900|0|2", text.split('|').take(5).joinToString("|"))
        assertEquals(CopyHealth(2_400_000, 2, 1_800, 0, 4, 862, 56_000), h + h)
    }

    @Test
    fun aCopyThatLosesMoreThanTwoPercentOverTenMinutesIsTroubled() {
        val choppy = CopyHealth(watchedMs = 12 * 60_000L, droppedFrames = 2_900, renderedFrames = 14_400)
        assertTrue(choppy.troubled)
        assertEquals("loses 17% of frames", choppy.summary())
        assertEquals("loses 4.2% of frames", CopyHealth(watchedMs = 40 * 60_000L, droppedFrames = 2_400, renderedFrames = 55_000).summary())
        // Under ten minutes says too little, however many.
        assertFalse(CopyHealth(watchedMs = 9 * 60_000L, droppedFrames = 2_000, renderedFrames = 10_000).troubled)
        // Two in a hundred isn't more than 2%: still smooth.
        val edge = CopyHealth(watchedMs = 40 * 60_000L, droppedFrames = 1_150, renderedFrames = 56_350)
        assertFalse(edge.troubled)
        assertEquals("smooth over 40 min", edge.summary())
        // Copies kept before the counts (no frames counted) are as they were.
        assertEquals("smooth over 40 min", CopyHealth(watchedMs = 40 * 60_000L).summary())
    }

    @Test
    fun aCopyThatLosesFramesLosesToOneThatDoesnt() {
        val copies = listOf(copy("HEVC", 20.0), copy("HEVC", 12.0))
        val health = listOf(
            CopyHealth(watchedMs = 30 * 60_000L, droppedFrames = 7_000, renderedFrames = 36_000),
            CopyHealth(watchedMs = 20 * 60_000L, renderedFrames = 28_000),
        )
        val v = CopyRank.rank(copies, 0, false, health)
        assertEquals(1, v.best)
        assertEquals("the other loses 16% of frames", v.reasons.first())
    }

    // --- A TV that reports no HDR

    private fun copy(codec: String, mbps: Double, depth: Int = 10, hdr: String? = "SDR") =
        MediaSpec(videoCodec = codec, width = 3840, height = 2160, bitDepth = depth, hdr = hdr, videoBps = (mbps * 1e6).toLong(),
            videoHow = MediaSpec.How.STATED, audioCodec = "DD+", channels = 6)

    @Test
    fun onATvWithoutHdrAnSdrCopyWithEnoughDataComesFirst() {
        val copies = listOf(copy("HEVC", 15.0, hdr = "HDR10"), copy("HEVC", 12.0, depth = 8))
        val sdrTv = CopyRank.rank(copies, 0, hdrScreen = false)
        assertEquals(1, sdrTv.best)
        assertTrue(sdrTv.reasons.toString(), sdrTv.reasons.contains("SDR, as this TV shows it (the other's HDR10 is turned into SDR)"))
        assertTrue(sdrTv.reasons.none { it.contains("HDR10 instead") || it.contains("-bit instead") })
        // On a TV that shows HDR the HDR copy, with more data, stays first, and says why.
        val hdrTv = CopyRank.rank(copies, 1, hdrScreen = true)
        assertEquals(0, hdrTv.best)
        assertTrue(hdrTv.reasons.contains("HDR10 instead of SDR"))
        assertTrue(hdrTv.reasons.contains("10-bit instead of 8-bit"))
    }

    @Test
    fun onATvWithoutHdrHdrIsNoReasonToPickACopy() {
        // The SDR copy has too little data (under 70% of the HDR one's): the HDR copy stays first, but its HDR and 10 bits
        // aren't given as reasons, and what it gives up is said.
        val copies = listOf(copy("HEVC", 20.0, hdr = "HDR10"), copy("HEVC", 8.0, depth = 8))
        val v = CopyRank.rank(copies, 1, hdrScreen = false)
        assertEquals(0, v.best)
        assertTrue(v.reasons.none { it.contains("HDR") || it.contains("-bit instead") })
        assertTrue(v.tradeOffs.toString(), v.tradeOffs.contains("HDR10 shown as SDR on this TV"))
        // A copy whose file doesn't say isn't taken for SDR: no copy is put first for it.
        val unknown = listOf(copy("HEVC", 15.0, hdr = "HDR10"), copy("HEVC", 12.0, hdr = null))
        assertEquals(0, CopyRank.rank(unknown, 1, hdrScreen = false).best)
    }

    @Test
    fun theRangeRowSaysWhenTheTvShowsHdrAsSdr() {
        assertEquals("HDR10 · shown as SDR (TV reports no HDR)", MediaSpec(hdr = "HDR10").rangeLine(hdrScreen = false))
        assertEquals("Dolby Vision · shown as SDR (TV reports no HDR)", MediaSpec(hdr = "Dolby Vision").rangeLine(hdrScreen = false))
        assertEquals("HDR10", MediaSpec(hdr = "HDR10").rangeLine(hdrScreen = true))
        assertEquals("SDR", MediaSpec(hdr = "SDR").rangeLine(hdrScreen = false))
        assertEquals("Not stated by the file", MediaSpec().rangeLine(hdrScreen = false))
    }

    @Test
    fun theRangeRowSaysHdr10PlusAndTheDolbyVisionFileItCameFrom() {
        // HDR10+ seen in the picture, on the user's Sticks, where it never reached the TV from FirePlayer: in the file, and
        // what the TV gets; also for a profile 7 file played as its base layer (Avatar's UHD remuxes).
        assertEquals("HDR10+ in the file · TV gets HDR10", MediaSpec(hdr = "HDR10", hdr10Plus = true).rangeLine(hdrScreen = true))
        assertEquals("HDR10+ in the file · TV gets HDR10 (Dolby Vision profile 7 base layer)",
            MediaSpec(hdr = "HDR10", dvBaseLayer = 7, hdr10Plus = true).rangeLine(hdrScreen = true))
        // Where Settings › This TV says it reaches the TV.
        assertEquals("HDR10+", MediaSpec(hdr = "HDR10", hdr10Plus = true).rangeLine(hdrScreen = true, plusReaches = true))
        assertEquals("HDR10+ (Dolby Vision profile 7 base layer)",
            MediaSpec(hdr = "HDR10", dvBaseLayer = 7, hdr10Plus = true).rangeLine(hdrScreen = true, plusReaches = true))
        assertEquals("HDR10 (Dolby Vision profile 7 base layer)", MediaSpec(hdr = "HDR10", dvBaseLayer = 7).rangeLine(hdrScreen = true))
        // Profile 8 on the HEVC decoder (a TV without Dolby Vision): the HDR10 picture inside it, not "Dolby Vision", once
        // its record or picture said HDR10 (8.1); not called HDR10 before that was read.
        val p8 = MediaSpec(hdr = "Dolby Vision", dvProfile = 8, decoder = MediaSpec.Decoder.PLAIN, dvSignal = DvBaseLayer.SIGNAL_HDR10)
        assertEquals("HDR10 (Dolby Vision profile 8 file)", p8.rangeLine(hdrScreen = true))
        assertEquals("Dolby Vision profile 8 file", p8.copy(dvSignal = 0).rangeLine(hdrScreen = true))
        assertEquals("HDR10+ in the file · TV gets HDR10 (Dolby Vision profile 8 file)", p8.copy(hdr10Plus = true).rangeLine(hdrScreen = true))
        // Shown as Dolby Vision, or profile 5 (no HDR10 inside): as the file has it.
        assertEquals("Dolby Vision", p8.copy(decoder = MediaSpec.Decoder.DOLBY_VISION).rangeLine(hdrScreen = true))
        assertEquals("Dolby Vision", p8.copy(dvProfile = 5).rangeLine(hdrScreen = true))
        // HDR10+ only describes an HDR10 picture, and not on a TV that shows HDR as SDR.
        assertEquals("HLG", MediaSpec(hdr = "HLG", hdr10Plus = true).rangeLine(hdrScreen = true))
        assertEquals("HDR10 · shown as SDR (TV reports no HDR)", MediaSpec(hdr = "HDR10", hdr10Plus = true).rangeLine(hdrScreen = false))
    }
}
