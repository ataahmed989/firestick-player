package app.fireplayer.player

import android.media.MediaCodecInfo.CodecProfileLevel
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DvBaseLayerTest {

    private val stn = CodecProfileLevel.DolbyVisionProfileDvheStn
    private val dtb = CodecProfileLevel.DolbyVisionProfileDvheDtb
    private val st = CodecProfileLevel.DolbyVisionProfileDvheSt

    /** The Fire TV Stick 4K Max's Dolby Vision decoders: MediaTek's single-track ones, for profiles 5 and 8. */
    private val fireTv = listOf(
        DvBaseLayer.Decoder("OMX.MTK.VIDEO.DECODER.DVHE.STN", intArrayOf(stn)),
        DvBaseLayer.Decoder("OMX.MTK.VIDEO.DECODER.DVHE.STN.secure", intArrayOf(stn)),
        DvBaseLayer.Decoder("OMX.MTK.VIDEO.DECODER.DVHE.STH", intArrayOf(st)),
    )

    // --- The profile, from the codecs string

    @Test
    fun theProfileComesFromTheCodecsString() {
        assertEquals(7, DvBaseLayer.profileOf("dvhe.07.06"))
        assertEquals(7, DvBaseLayer.profileOf("dvh1.07.06"))
        assertEquals(8, DvBaseLayer.profileOf("dvhe.08.06"))
        assertEquals(5, DvBaseLayer.profileOf("dvh1.05.06"))
        assertEquals(9, DvBaseLayer.profileOf("dvav.09.05"))
        assertEquals(10, DvBaseLayer.profileOf("dav1.10.09"))
        // Without a level the profile is still said.
        assertEquals(7, DvBaseLayer.profileOf("dvhe.07"))
        assertNull(DvBaseLayer.profileOf("hvc1.2.4.L153.B0"))
        assertNull(DvBaseLayer.profileOf("dvhe"))
        assertNull(DvBaseLayer.profileOf("dvhe.xx.06"))
        assertNull(DvBaseLayer.profileOf("dvhe.11.06"))
        assertNull(DvBaseLayer.profileOf(""))
        assertNull(DvBaseLayer.profileOf(null))
    }

    @Test
    fun eachProfileIsItsOwnCodecProfileLevelBit() {
        assertEquals(0x80, DvBaseLayer.profileLevel(7))
        assertEquals(dtb, DvBaseLayer.profileLevel(7))
        assertEquals(stn, DvBaseLayer.profileLevel(5))
        assertEquals(st, DvBaseLayer.profileLevel(8))
    }

    // --- Which decoders play it

    @Test
    fun profile7GoesToTheHevcDecodersWhenNoDolbyVisionDecoderTakesIt() {
        assertTrue(DvBaseLayer.baseLayerInstead(7, fireTv, hevcDecoders = 2))
        // Profiles 5 and 8 stay on the Dolby Vision decoders, as before.
        assertFalse(DvBaseLayer.baseLayerInstead(5, fireTv, hevcDecoders = 2))
        assertFalse(DvBaseLayer.baseLayerInstead(8, fireTv, hevcDecoders = 2))
        assertFalse(DvBaseLayer.baseLayerInstead(null, fireTv, hevcDecoders = 2))
        // No Dolby Vision decoder at all: its base layer plays all the same.
        assertTrue(DvBaseLayer.baseLayerInstead(7, emptyList(), hevcDecoders = 1))
        // No HEVC decoder to take it: as Media3 has it.
        assertFalse(DvBaseLayer.baseLayerInstead(7, fireTv, hevcDecoders = 0))
    }

    @Test
    fun aTvWithAProfile7DecoderKeepsDolbyVision() {
        val dual = fireTv + DvBaseLayer.Decoder("c2.vendor.dolby-vision.decoder", intArrayOf(stn, dtb, st))
        assertFalse(DvBaseLayer.baseLayerInstead(7, dual, hevcDecoders = 2))
        assertTrue(DvBaseLayer.decodes("c2.vendor.dolby-vision.decoder", intArrayOf(stn, dtb, st), 7))
    }

    @Test
    fun mediaTeksSingleTrackDecodersAreSingleLayerWhateverTheyDeclare() {
        val claims = listOf(
            DvBaseLayer.Decoder("OMX.MTK.VIDEO.DECODER.DVHE.STN", intArrayOf(stn, dtb, st)),
            DvBaseLayer.Decoder("OMX.MTK.VIDEO.DECODER.DVHE.STN.secure", intArrayOf(stn, dtb, st)),
            DvBaseLayer.Decoder("OMX.MTK.VIDEO.DECODER.DVHE.STH", intArrayOf(dtb, st)),
        )
        assertTrue(DvBaseLayer.baseLayerInstead(7, claims, hevcDecoders = 2))
        assertTrue(DvBaseLayer.singleLayer("OMX.MTK.VIDEO.DECODER.DVHE.STH"))
        assertTrue(DvBaseLayer.singleLayer("OMX.MTK.VIDEO.DECODER.DVHE.STN.secure"))
        assertFalse(DvBaseLayer.singleLayer("OMX.MTK.VIDEO.DECODER.DVHE.STNX"))
        assertFalse(DvBaseLayer.singleLayer("OMX.MTK.VIDEO.DECODER.HEVC"))
        // The single-layer profiles they declare are still theirs.
        assertTrue(DvBaseLayer.decodes("OMX.MTK.VIDEO.DECODER.DVHE.STN", intArrayOf(stn, dtb), 5))
        assertFalse(DvBaseLayer.decodes("OMX.MTK.VIDEO.DECODER.DVHE.STN", intArrayOf(stn, dtb), 7))
    }

    @Test
    fun theTrackPlayingIsItsBaseLayerOnlyWhenTheTvPlaysItSo() {
        assertTrue(DvBaseLayer.playedAsBase(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.07.06", tvPlaysBase = true))
        assertFalse(DvBaseLayer.playedAsBase(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.07.06", tvPlaysBase = false))
        assertFalse(DvBaseLayer.playedAsBase(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.08.06", tvPlaysBase = true))
        assertFalse(DvBaseLayer.playedAsBase(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.B0", tvPlaysBase = true))
    }

    // --- The words

    @Test
    fun theWords() {
        assertEquals("HDR10 (Dolby Vision profile 7 base layer)", DvBaseLayer.words(7))
        assertEquals("Dolby Vision profile 7 played as HDR10: this TV's Dolby Vision decoder is single-layer", DvBaseLayer.logLine(3))
        assertEquals("Dolby Vision profile 7 played as HDR10: this TV has no Dolby Vision decoder", DvBaseLayer.logLine(0))
    }

    @Test
    fun pictureAndSoundSaysHdr10AndTheCopyIsKeptAsHdr10() {
        val pq = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorTransfer(C.COLOR_TRANSFER_ST2084)
            .setColorRange(C.COLOR_RANGE_LIMITED).build()
        val remux = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs("dvhe.07.06")
            .setWidth(3840).setHeight(2160).setFrameRate(23.976f).setColorInfo(pq).build()
        val asBase = MediaSpec.of(remux, null, baseLayer = true)
        assertEquals("HDR10", asBase.hdr)
        assertEquals(7, asBase.dvBaseLayer)
        assertEquals("HEVC", asBase.videoCodec)
        assertEquals(10, asBase.bitDepth)
        assertEquals("HDR10 (Dolby Vision profile 7 base layer)", asBase.rangeLine(hdrScreen = true))
        assertEquals("HDR10 (Dolby Vision profile 7 base layer) · shown as SDR (TV reports no HDR)", asBase.rangeLine(hdrScreen = false))
        assertEquals("HEVC · 10-bit · HDR10", asBase.pictureLine())
        // Kept between plays; what was kept before it was known reads back as played as the file has it.
        assertEquals(asBase, MediaSpec.decode(MediaSpec.encode(asBase)))
        assertEquals(0, MediaSpec.decode(MediaSpec.encode(asBase).substringBeforeLast('|'))?.dvBaseLayer)
        // Where it plays as Dolby Vision, it's said as before.
        val asDv = MediaSpec.of(remux, null)
        assertEquals("Dolby Vision", asDv.hdr)
        assertEquals(0, asDv.dvBaseLayer)
        assertEquals("Dolby Vision", asDv.rangeLine(hdrScreen = true))
        // Profile 8 is never its base layer here, and HDR10 and SDR films are as they were.
        val p8 = remux.buildUpon().setCodecs("dvhe.08.06").build()
        assertEquals("Dolby Vision", MediaSpec.of(p8, null, DvBaseLayer.playedAsBase(p8.sampleMimeType, p8.codecs, tvPlaysBase = true)).hdr)
        val hdr10 = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("hvc1.2.4.L153.B0").setColorInfo(pq).build()
        assertEquals("HDR10", MediaSpec.of(hdr10, null).rangeLine(hdrScreen = true))
        val sdr = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setColorInfo(ColorInfo.SDR_BT709_LIMITED).build()
        assertEquals("SDR", MediaSpec.of(sdr, null).rangeLine(hdrScreen = true))
    }
}
