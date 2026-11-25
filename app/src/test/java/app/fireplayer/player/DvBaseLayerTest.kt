package app.fireplayer.player

import android.media.MediaCodecInfo.CodecProfileLevel
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
        assertEquals(0, MediaSpec.decode(MediaSpec.encode(asBase).split('|').take(20).joinToString("|"))?.dvBaseLayer)
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

    // --- HDR10 colour for the base layer

    @Test
    fun aStatedHdr10ColourIsKeptAsItIs() {
        val info = ByteArray(25) { (it * 7).toByte() }
        val own = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorTransfer(C.COLOR_TRANSFER_ST2084)
            .setColorRange(C.COLOR_RANGE_FULL).setHdrStaticInfo(info).build()
        assertSame(own, DvBaseLayer.hdr10(own))
    }

    @Test
    fun hdr10IsFilledInWhereTheFileStatesNone() {
        val generic = DvBaseLayer.hdr10(null).hdrStaticInfo
        val pqOnly = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorTransfer(C.COLOR_TRANSFER_ST2084).build()
        val hlg = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorTransfer(C.COLOR_TRANSFER_HLG).build()
        for (own in listOf(null, ColorInfo.Builder().build(), pqOnly, hlg, ColorInfo.SDR_BT709_LIMITED)) {
            val c = DvBaseLayer.hdr10(own)
            assertEquals(C.COLOR_SPACE_BT2020, c.colorSpace)
            assertEquals(C.COLOR_TRANSFER_ST2084, c.colorTransfer)
            assertEquals(C.COLOR_RANGE_LIMITED, c.colorRange)
            assertArrayEquals(generic, c.hdrStaticInfo)
        }
        // The file's own range and static metadata are kept where it states them.
        assertEquals(C.COLOR_RANGE_FULL, DvBaseLayer.hdr10(ColorInfo.Builder().setColorRange(C.COLOR_RANGE_FULL).build()).colorRange)
        val info = ByteArray(25) { (it + 1).toByte() }
        val noTransfer = DvBaseLayer.hdr10(ColorInfo.Builder().setHdrStaticInfo(info).build())
        assertArrayEquals(info, noTransfer.hdrStaticInfo)
        assertEquals(C.COLOR_TRANSFER_ST2084, noTransfer.colorTransfer)
        assertEquals(C.COLOR_SPACE_BT2020, noTransfer.colorSpace)
    }

    // --- The base layer's brightness data from the SEI in its parameter sets

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** CTA-861.3 static metadata: type 1, then [v] (R, G, B, white (x, y), peak, black, MaxCLL, MaxFALL) little-endian. */
    private fun cta(vararg v: Int): ByteArray {
        val b = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0)
        for (x in v) b.putShort(x.toShort())
        return b.array()
    }

    /**
     * The hvcC (MKV CodecPrivate) ffmpeg 6.1 wrote for a clip libx265 3.5 encoded with master-display
     * G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(40000000,50) and max-cll 1452,400: VPS, SPS, PPS, then
     * a prefix SEI array of a light level unit and a mastering display unit (x265's long info unit left out). The
     * mastering display's black (00 00 00 32) carries an emulation prevention byte: 00 00 03 00 32.
     */
    private val x265Hvcc = hex(
        "0102200000009000000000001ef000fcfdfafa00000f04200001001840010c01ffff02200000030090000003000003001e9598092100" +
            "01002d42010102200000030090000003000003001ea0208104d96566924caf016a12201208000003000800000300c04022000100" +
            "074401c172b4224027000200094e01900405ac019080001e4e01891833c286c41d4c0bb884d03e803d13404202625a0000030000" +
            "3280",
    )

    /** [rbsp] as a NAL unit's bytes: an emulation prevention byte (03) after each two zeros followed by 00 to 03. */
    private fun escaped(rbsp: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var zeros = 0
        for (x in rbsp) {
            val v = x.toInt() and 0xFF
            if (zeros >= 2 && v <= 3) {
                out.write(3)
                zeros = 0
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** A mastering display message (payload 137): primaries green, blue, red, then white, peak and black (0.0001 cd/m2). */
    private fun mastering(g: IntArray, b: IntArray, r: IntArray, w: IntArray, peak: Long, black: Long): ByteArray {
        val m = ByteBuffer.allocate(24)
        for (v in g + b + r + w) m.putShort(v.toShort())
        m.putInt(peak.toInt())
        m.putInt(black.toInt())
        return bytes(137, 24) + m.array()
    }

    private fun light(cll: Int, fall: Int) = bytes(144, 4, cll shr 8, cll and 0xFF, fall shr 8, fall and 0xFF)

    /** An SEI NAL unit of [type] (39 prefix, 40 suffix) with [messages] and the stop bit, after a 4-byte start code. */
    private fun seiUnit(vararg messages: ByteArray, type: Int = 39): ByteArray =
        start4 + bytes(type shl 1, 1) + escaped(messages.fold(ByteArray(0)) { a, m -> a + m } + bytes(0x80))

    /** A UHD Blu-ray's HDR10: BT.2020 primaries (green, blue, red), D65, 1000 nits, 0.0001 nits; MaxCLL 1452, MaxFALL 400. */
    private val g2020 = intArrayOf(8500, 39850)
    private val b2020 = intArrayOf(6550, 2300)
    private val r2020 = intArrayOf(35400, 14600)
    private val d65 = intArrayOf(15635, 16450)

    @Test
    fun theFilesOwnBrightnessDataIsReadFromTheSeiInItsHvcc() {
        // As Media3's MKV and MP4 readers keep it: every unit of the hvcC, SEI arrays included, with start codes.
        val initData = androidx.media3.extractor.HevcConfig.parse(androidx.media3.common.util.ParsableByteArray(x265Hvcc))
            .initializationData
        val info = DvBaseLayer.seiStaticInfo(initData)
        // Red, green, blue, white; 4000 nits; 0.0050 nits (the escaped 50); MaxCLL 1452, MaxFALL 400.
        assertArrayEquals(cta(34000, 16000, 13250, 34500, 7500, 3000, 15635, 16450, 4000, 50, 1452, 400), info)
        // Given the decoder where the track states none; the track's own still comes first.
        val pqOnly = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorTransfer(C.COLOR_TRANSFER_ST2084).build()
        assertSame(info, DvBaseLayer.hdr10(null, info).hdrStaticInfo)
        assertSame(info, DvBaseLayer.hdr10(pqOnly, info).hdrStaticInfo)
        val own = ByteArray(25) { (it + 1).toByte() }
        assertArrayEquals(own, DvBaseLayer.hdr10(ColorInfo.Builder().setHdrStaticInfo(own).build(), info).hdrStaticInfo)
        assertEquals("HDR10 info from the file's SEI: 4000 nits, MaxCLL 1452, MaxFALL 400",
            DvBaseLayer.brightnessLine(info, fromTrack = false, fromSei = true))
    }

    @Test
    fun bothMessagesInOneUnitAsOnAUhdBluRay() {
        // Mastering display then light level in one prefix SEI unit, after a VPS; black 1 (escaped: 00 00 03 00 01).
        val vps = start4 + bytes(0x40, 0x01, 0x0c, 0x01)
        val one = vps + seiUnit(mastering(g2020, b2020, r2020, d65, 10_000_000, 1), light(1452, 400))
        val expected = cta(35400, 14600, 8500, 39850, 6550, 2300, 15635, 16450, 1000, 1, 1452, 400)
        assertArrayEquals(expected, DvBaseLayer.seiStaticInfo(listOf(one)))
        // A message before them whose type byte is 80 (128, structure of pictures) isn't taken for the stop bit.
        val sop = bytes(128, 2, 0x12, 0x34)
        assertArrayEquals(expected, DvBaseLayer.seiStaticInfo(listOf(vps + seiUnit(sop, mastering(g2020, b2020, r2020, d65, 10_000_000, 1), light(1452, 400)))))
        // 3-byte start codes; the record a Dolby Vision MP4 adds after the parameter sets is passed over.
        val three = start3 + bytes(39 shl 1, 1) + escaped(mastering(g2020, b2020, r2020, d65, 10_000_000, 1) + light(1452, 400) + bytes(0x80))
        assertArrayEquals(expected, DvBaseLayer.seiStaticInfo(listOf(record(DvBaseLayer.SIGNAL_BLU_RAY, 7), three)))
        // Without a light level message: MaxCLL and MaxFALL 0, "not known".
        assertArrayEquals(cta(35400, 14600, 8500, 39850, 6550, 2300, 15635, 16450, 1000, 1, 0, 0),
            DvBaseLayer.seiStaticInfo(listOf(seiUnit(mastering(g2020, b2020, r2020, d65, 10_000_000, 1)))))
        // Light levels that make no sense go as 0; the mastering display stays.
        assertArrayEquals(cta(35400, 14600, 8500, 39850, 6550, 2300, 15635, 16450, 1000, 1, 0, 0),
            DvBaseLayer.seiStaticInfo(listOf(seiUnit(mastering(g2020, b2020, r2020, d65, 10_000_000, 1), light(20_000, 400)))))
        // The first of each kind is used.
        assertArrayEquals(expected, DvBaseLayer.seiStaticInfo(listOf(one,
            seiUnit(mastering(g2020, b2020, r2020, d65, 40_000_000, 50), light(4000, 1000)))))
    }

    @Test
    fun noBrightnessDataWhereTheSeiIsMissingOrMakesNoSense() {
        val vps = start4 + bytes(0x40, 0x01, 0x0c, 0x01)
        assertNull(DvBaseLayer.seiStaticInfo(null))
        assertNull(DvBaseLayer.seiStaticInfo(emptyList()))
        assertNull(DvBaseLayer.seiStaticInfo(listOf(ByteArray(0))))
        // Parameter sets only (as most hvcCs), or only a light level message, or the messages in a suffix unit.
        assertNull(DvBaseLayer.seiStaticInfo(listOf(vps)))
        assertNull(DvBaseLayer.seiStaticInfo(listOf(vps + seiUnit(light(1452, 400)))))
        assertNull(DvBaseLayer.seiStaticInfo(listOf(vps + seiUnit(mastering(g2020, b2020, r2020, d65, 10_000_000, 1), type = 40))))
        // A peak under 100 or over 10000 nits, black not below it or past 16 bits, a coordinate past 1.
        for (bad in listOf(
            mastering(g2020, b2020, r2020, d65, 500_000, 1),
            mastering(g2020, b2020, r2020, d65, 200_000_000, 1),
            mastering(g2020, b2020, r2020, d65, 10_000_000, 10_000_000),
            mastering(g2020, b2020, r2020, d65, 10_000_000, 70_000),
            mastering(g2020, b2020, intArrayOf(60_000, 14600), d65, 10_000_000, 1),
        )) assertNull(DvBaseLayer.seiStaticInfo(listOf(vps + seiUnit(bad, light(1452, 400)))))
        // Cut short: the unit ends inside the message its size gives, or the size says less than the message holds.
        val whole = seiUnit(mastering(g2020, b2020, r2020, d65, 10_000_000, 1))
        assertNull(DvBaseLayer.seiStaticInfo(listOf(whole.copyOf(16))))
        val short = start4 + bytes(39 shl 1, 1, 137, 10) + ByteArray(10) { 0x11 } + bytes(0x80)
        assertNull(DvBaseLayer.seiStaticInfo(listOf(short)))
        // Only a header, a header and a stop bit: nothing, and nothing thrown.
        assertNull(DvBaseLayer.seiStaticInfo(listOf(start4 + bytes(39 shl 1, 1))))
        assertNull(DvBaseLayer.seiStaticInfo(listOf(start4 + bytes(39 shl 1, 1, 0x80))))
        assertNull(DvBaseLayer.seiStaticInfo(listOf(start4 + bytes(39 shl 1, 1, 0xFF, 0xFF, 0xFF))))
        // The generic data, said so in the log.
        assertEquals("HDR10 info generic: 1000 nits, MaxCLL 1000, MaxFALL 400 (none in the file's header)",
            DvBaseLayer.brightnessLine(DvBaseLayer.hdr10(null).hdrStaticInfo, fromTrack = false, fromSei = false))
        assertEquals("HDR10 info from the file's track: 4000 nits, MaxCLL 0, MaxFALL 0",
            DvBaseLayer.brightnessLine(cta(0, 0, 0, 0, 0, 0, 0, 0, 4000, 0, 0, 0), fromTrack = true, fromSei = false))
    }

    @Test
    fun randomBytesNeverThrowWhenReadForSei() {
        val random = Random(7)
        repeat(3000) {
            val b = ByteArray(random.nextInt(0, 120)) { random.nextInt(256).toByte() }
            // Start codes and SEI headers sprinkled in, so the messages are read.
            for (k in 0 until random.nextInt(0, 4)) {
                val at = random.nextInt(0, maxOf(1, b.size - 5))
                if (at + 5 <= b.size) {
                    b[at] = 0; b[at + 1] = 0; b[at + 2] = 1; b[at + 3] = (39 shl 1).toByte(); b[at + 4] = 1
                }
            }
            val info = DvBaseLayer.seiStaticInfo(listOf(b))
            if (info != null) assertEquals(25, info.size)
        }
    }

    // --- HDR10 colour for profile 8 on the HEVC decoder (Media3's own way back on a TV without Dolby Vision)

    @Test
    fun profile8OnTheHevcDecoderGetsHdr10ColourWhereItStatesNone() {
        val dv = MimeTypes.VIDEO_DOLBY_VISION
        val hevc = MimeTypes.VIDEO_H265
        val generic = DvBaseLayer.hdr10(null).hdrStaticInfo
        // The IMAX copy as measured: PQ with no static metadata (or no colour at all) reached the screen with none.
        val pqOnly = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorTransfer(C.COLOR_TRANSFER_ST2084)
            .setColorRange(C.COLOR_RANGE_LIMITED).build()
        for (own in listOf(null, ColorInfo.Builder().build(), pqOnly)) {
            val c = DvBaseLayer.hevcColour(dv, "dvhe.08.06", hevc, own)!!
            assertEquals(C.COLOR_SPACE_BT2020, c.colorSpace)
            assertEquals(C.COLOR_TRANSFER_ST2084, c.colorTransfer)
            assertEquals(C.COLOR_RANGE_LIMITED, c.colorRange)
            assertArrayEquals(generic, c.hdrStaticInfo)
        }
        // The file's own brightness data, where it states it without the rest.
        val info = ByteArray(25) { (it + 3).toByte() }
        val stated = ColorInfo.Builder().setHdrStaticInfo(info).build()
        assertArrayEquals(info, DvBaseLayer.hevcColour(dv, "dvh1.08.06", hevc, stated)!!.hdrStaticInfo)
        // HDR10 in full is left as it is; an HLG (8.4) or SDR (8.2) base keeps its own colour.
        val full = pqOnly.buildUpon().setHdrStaticInfo(info).build()
        assertNull(DvBaseLayer.hevcColour(dv, "dvhe.08.06", hevc, full))
        assertNull(DvBaseLayer.hevcColour(dv, "dvhe.08.06", hevc, ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_HLG).build()))
        assertNull(DvBaseLayer.hevcColour(dv, "dvhe.08.06", hevc, ColorInfo.SDR_BT709_LIMITED))
        // Only Dolby Vision profile 8, and only on the HEVC decoder: played as Dolby Vision, or another profile (7 goes
        // as its base layer with its own colour; 5 has no HDR10 inside), or an HEVC film, nothing changes.
        assertNull(DvBaseLayer.hevcColour(dv, "dvhe.08.06", dv, null))
        assertNull(DvBaseLayer.hevcColour(dv, "dvhe.05.06", hevc, null))
        assertNull(DvBaseLayer.hevcColour(dv, "dvhe.07.06", hevc, null))
        assertNull(DvBaseLayer.hevcColour(dv, null, hevc, null))
        assertNull(DvBaseLayer.hevcColour(hevc, "hvc1.2.4.L153.B0", hevc, null))
    }

    @Test
    fun aProfile8PictureThatSaysHlgOrSdrIsLeftAsItIsWhenItsFileStatesNoColour() {
        val dv = MimeTypes.VIDEO_DOLBY_VISION
        val hevc = MimeTypes.VIDEO_H265
        // An MKV without a Colour element: Media3 states no colour, its picture's SPS (the initialization data) does.
        // A 4K Main 10 SPS, BT.2020, its transfer PQ, HLG (8.4) or SDR (8.2).
        fun sps(hex: String) = listOf(ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() })
        val pq = sps("00000001420101022000000300900000030000030099a001e020021c4d965e4936b9a84880480000030000030000030004")
        val hlg = sps("00000001420101022000000300900000030000030099a001e020021c4d965e4936b9a84890480000030000030000030004")
        val sdr = sps("00000001420101022000000300900000030000030099a001e020021c4d965e4936b9a84808480000030000030000030004")
        assertEquals(C.COLOR_TRANSFER_ST2084, DvBaseLayer.hevcColour(dv, "dvhe.08.06", hevc, null, pq)!!.colorTransfer)
        assertNull(DvBaseLayer.hevcColour(dv, "dvhe.08.06", hevc, null, hlg))
        assertNull(DvBaseLayer.hevcColour(dv, "dvhe.08.06", hevc, ColorInfo.Builder().build(), sdr))
        // Stated by the file, its own word stands; no parameter sets to read: HDR10 colour, as before.
        assertNull(DvBaseLayer.hevcColour(dv, "dvhe.08.06", hevc, ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_HLG).build(), pq))
        assertTrue(DvBaseLayer.hevcColour(dv, "dvhe.08.06", hevc, null, emptyList()) != null)
        // A colour space other than BT.2020, stated without a transfer, isn't made BT.2020.
        assertNull(DvBaseLayer.hevcColour(dv, "dvhe.08.06", hevc, ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT709).build()))
    }

    // --- What a Dolby Vision file's base layer is: its configuration record (dvcC, dvvC, dvwC)

    /** A Dolby Vision decoder configuration record (24 bytes): version 1.0, [profile], level 6, RPU and BL, [signal]. */
    private fun record(signal: Int, profile: Int = 8) = ByteArray(24).also {
        it[0] = 1
        it[2] = (profile shl 1).toByte()
        it[3] = 0x35
        it[4] = (signal shl 4).toByte()
    }

    @Test
    fun aProfile8FilesRecordSaysWhatItsBaseLayerIs() {
        assertEquals(listOf(1, 2, 4, 6), listOf(1, 2, 4, 6).map { DvBaseLayer.signalOf(record(it)) })
        // An HEVC parameter set, a cut-off record, a profile out of range: none.
        assertNull(DvBaseLayer.signalOf(byteArrayOf(0, 0, 0, 1, 0x40, 1, 0x0c)))
        assertNull(DvBaseLayer.signalOf(record(4).copyOf(4)))
        assertNull(DvBaseLayer.signalOf(record(4, profile = 2)))
        // MP4: the record follows the parameter sets in the initialization data.
        val dv = MimeTypes.VIDEO_DOLBY_VISION
        val hevc = MimeTypes.VIDEO_H265
        val mp4 = Format.Builder().setSampleMimeType(dv).setCodecs("dvh1.08.06").setWidth(3840).setHeight(2160)
            .setInitializationData(listOf(byteArrayOf(0, 0, 0, 1, 0x40, 1, 0x0c), record(DvBaseLayer.SIGNAL_HLG))).build()
        assertEquals(DvBaseLayer.SIGNAL_HLG, DvBaseLayer.recordSignal(mp4))
        // MKV: kept on the format by the reader.
        val mkv = Format.Builder().setSampleMimeType(dv).setCodecs("dvhe.08.06").setCustomData(DvBaseLayer.Signal(DvBaseLayer.SIGNAL_SDR)).build()
        assertEquals(DvBaseLayer.SIGNAL_SDR, DvBaseLayer.recordSignal(mkv))
        val bare = mkv.buildUpon().setCustomData(null).build()
        assertNull(DvBaseLayer.recordSignal(bare))
        // With no record, the transfer the track states; nothing stated, not known.
        val pq = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorTransfer(C.COLOR_TRANSFER_ST2084).build()
        assertEquals(DvBaseLayer.SIGNAL_HDR10, DvBaseLayer.baseSignal(bare.buildUpon().setColorInfo(pq).build()))
        assertEquals(0, DvBaseLayer.baseSignal(bare))
        // An HLG or SDR base is never given HDR10 colour, even when the track states no colour at all.
        assertNull(DvBaseLayer.hevcColour(dv, "dvhe.08.06", hevc, null, null, DvBaseLayer.SIGNAL_HLG))
        assertNull(DvBaseLayer.hevcColour(dv, "dvhe.08.06", hevc, null, null, DvBaseLayer.SIGNAL_SDR))
        assertNotNull(DvBaseLayer.hevcColour(dv, "dvhe.08.06", hevc, null, null, DvBaseLayer.SIGNAL_HDR10))
        // Played on the HEVC decoder: kept as HLG, said as HLG, and not "no HDR brightness data".
        val spec = MediaSpec.of(mp4, null, decoderMime = hevc)
        assertEquals(MediaSpec.Decoder.PLAIN, spec.decoder)
        assertEquals(DvBaseLayer.SIGNAL_HLG, spec.dvSignal)
        assertNull(spec.hdrInfo)
        assertEquals("HLG (Dolby Vision profile 8 file)", spec.rangeLine(hdrScreen = true))
        assertEquals("HDR10 (Dolby Vision profile 8 file)", MediaSpec.of(bare.buildUpon().setColorInfo(pq).build(), null, decoderMime = hevc)
            .rangeLine(hdrScreen = true))
    }

    /** An EBML element: [id] in its own bytes, a one-byte size, then [body]. */
    private fun ebml(id: Long, vararg body: ByteArray): ByteArray {
        val payload = body.fold(ByteArray(0)) { a, b -> a + b }
        val n = when {
            id > 0xFFFFFF -> 4
            id > 0xFFFF -> 3
            id > 0xFF -> 2
            else -> 1
        }
        return ByteArray(n) { ((id shr (8 * (n - 1 - it))) and 0xFF).toByte() } + byteArrayOf((0x80 or payload.size).toByte()) + payload
    }

    private fun uint(v: Long, n: Int) = ByteArray(n) { ((v shr (8 * (n - 1 - it))) and 0xFF).toByte() }

    @Test
    fun theMkvReaderTakesTheVideoTracksRecordFromTheFilesFirstBytes() {
        // Segment › Tracks › an audio TrackEntry, then the video one with its DefaultDuration and BlockAdditionMapping.
        fun header(type: Long, signal: Int) = ebml(0x18538067, ebml(0x1654AE6B,
            ebml(0xAE, ebml(0x83, uint(2, 1))),
            ebml(0xAE, ebml(0x83, uint(1, 1)), ebml(0x23E383, uint(41_708_333, 4)),
                ebml(0x41E4, ebml(0x41E7, uint(type, 4)), ebml(0x41ED, record(signal))))))
        val dvcC = header(0x64766343, DvBaseLayer.SIGNAL_HLG)
        val video = MkvHeader.video(dvcC, dvcC.size)!!
        assertEquals(DvBaseLayer.SIGNAL_HLG, video.dvSignal)
        assertEquals(23.976f, video.frameRate, 0.001f)
        val dvvC = header(0x64767643, DvBaseLayer.SIGNAL_HDR10)
        assertEquals(DvBaseLayer.SIGNAL_HDR10, MkvHeader.video(dvvC, dvvC.size)!!.dvSignal)
        // Another kind of block addition: no record.
        val other = header(0x6D766343, DvBaseLayer.SIGNAL_HLG)
        assertNull(MkvHeader.video(other, other.size)!!.dvSignal)
        assertEquals(23.976f, MkvHeader.video(other, other.size)!!.frameRate, 0.001f)
    }

    @Test
    fun theGenericStaticMetadataIsCta861Type1LittleEndian() {
        val info = DvBaseLayer.hdr10(null).hdrStaticInfo!!
        assertEquals(25, info.size)
        val b = ByteBuffer.wrap(info).order(ByteOrder.LITTLE_ENDIAN)
        // Type 1.
        assertEquals(0, b.get().toInt())
        // Red, green, blue and white (x, y) in 0.00002 units: P3 primaries, D65 white; then 1000 cd/m2 and 0.0001 cd/m2
        // mastering luminance, MaxCLL 1000, MaxFALL 400.
        val values = IntArray(12) { b.getShort().toInt() and 0xFFFF }
        assertArrayEquals(intArrayOf(34000, 16000, 13250, 34500, 7500, 3000, 15635, 16450, 1000, 1, 1000, 400), values)
        // Low byte first: red x is 34000, 0x84D0.
        assertEquals(0xD0, info[1].toInt() and 0xFF)
        assertEquals(0x84, info[2].toInt() and 0xFF)
    }

    // --- Dolby Vision's units hidden from the HEVC decoder

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private val start4 = bytes(0, 0, 0, 1)
    private val start3 = bytes(0, 0, 1)

    /** A NAL unit header's first byte: the forbidden bit, the type, the layer id's high bit. */
    private fun header(type: Int, forbidden: Boolean = false, layerHigh: Boolean = false) =
        (if (forbidden) 0x80 else 0) or (type shl 1) or (if (layerHigh) 1 else 0)

    /** A NAL unit: start code, two-byte header ([first], then layer 0 and temporal id 0 unless [second] says), payload. */
    private fun unit(first: Int, payload: ByteArray, start: ByteArray = start4, second: Int = 1) =
        start + bytes(first, second) + payload

    private fun hidden(sample: ByteArray): Pair<Int, ByteArray> {
        val copy = sample.copyOf()
        return DvBaseLayer.hideDolbyVision(ByteBuffer.wrap(copy)) to copy
    }

    /** Picture data with emulation-prevention bytes in it. */
    private val slice = bytes(0xAF, 0x12, 0x34, 0x00, 0x00, 0x03, 0x01, 0x99, 0x5A, 0x00, 0x00, 0x03, 0x00, 0x42)
    /** An RPU's payload, holding bytes that would read as an RPU header after a start code. */
    private val rpu = bytes(0x01, 0x08, 0x09, 0x00, 0x00, 0x03, 0x01, 0x7C, 0x01, 0x22, 0x80)
    private val accessUnitDelimiter = unit(header(35), bytes(0x50))
    private val parameterSets = unit(header(32), bytes(0x0C, 0x01, 0xFF)) + unit(header(33), bytes(0x01, 0x60, 0x00, 0x00, 0x03, 0x00, 0xB0)) +
        unit(header(34), bytes(0xC1, 0x72))

    @Test
    fun theRpuAndEnhancementLayerBecomeType47WithEitherStartCode() {
        val base = accessUnitDelimiter + parameterSets + unit(header(39), bytes(0x89, 0x18, 0x80)) + unit(header(19), slice)
        val sample = base + unit(header(63), bytes(0x40, 0x01, 0x0C, 0x80)) + unit(header(63), slice, start3) +
            unit(header(62), rpu, start3)
        val (count, out) = hidden(sample)
        assertEquals(3, count)
        assertEquals(0x5E, header(47))
        val expected = base + unit(header(47), bytes(0x40, 0x01, 0x0C, 0x80)) + unit(header(47), slice, start3) +
            unit(header(47), rpu, start3)
        assertArrayEquals(expected, out)
        // Only those three bytes changed.
        assertEquals(3, sample.indices.count { sample[it] != out[it] })
    }

    @Test
    fun onlyTheTypeChangesNotTheForbiddenBitTheLayerIdOrTheSecondByte() {
        for (type in intArrayOf(62, 63)) for (forbidden in listOf(false, true)) for (layerHigh in listOf(false, true)) {
            for (start in listOf(start3, start4)) {
                val (count, out) = hidden(unit(header(type, forbidden, layerHigh), bytes(0x11, 0x80), start, second = 0x09))
                assertEquals(1, count)
                assertEquals(header(47, forbidden, layerHigh), out[start.size].toInt() and 0xFF)
                assertEquals(0x09, out[start.size + 1].toInt())
            }
        }
        assertEquals(0x5E, hidden(start4 + bytes(0x7C, 0x01)).second[4].toInt() and 0xFF)
        assertEquals(0x5F, hidden(start4 + bytes(0x7F, 0x01)).second[4].toInt() and 0xFF)
        assertEquals(0xDF, hidden(start4 + bytes(0xFF, 0x01)).second[4].toInt() and 0xFF)
    }

    @Test
    fun everyOtherTypeIsLeftAsItIs() {
        for (type in 0..63) for (start in listOf(start3, start4)) {
            val sample = accessUnitDelimiter + unit(header(type), bytes(0x22, 0x80), start) + unit(header(1), slice, start)
            val (count, out) = hidden(sample)
            if (type == 62 || type == 63) {
                assertEquals(1, count)
                assertEquals(1, sample.indices.count { sample[it] != out[it] })
            } else {
                assertEquals("type $type", 0, count)
                assertArrayEquals(sample, out)
            }
        }
    }

    @Test
    fun bytesThatAreNotAfterAStartCodeAreLeft() {
        // 00 01 7C (one zero), 00 00 03 01 7E (escaped), 00 00 02 7C and 01 01 7E: none is a start code.
        val sample = unit(header(1), bytes(0x00, 0x01, 0x7C, 0x00, 0x00, 0x03, 0x01, 0x7E, 0x00, 0x00, 0x02, 0x7C, 0x01, 0x01, 0x7E, 0x80))
        val (count, out) = hidden(sample)
        assertEquals(0, count)
        assertArrayEquals(sample, out)
    }

    @Test
    fun aSampleEndingInAStartCodeOrJustAHeader() {
        val body = unit(header(1), slice)
        // Ending inside a start code, or with no header after it: nothing to hide, nothing read past the end.
        for (tail in listOf(bytes(0), bytes(0, 0), bytes(0, 0, 0), bytes(0, 0, 1), bytes(0, 0, 0, 1))) {
            val (count, out) = hidden(body + tail)
            assertEquals(0, count)
            assertArrayEquals(body + tail, out)
        }
        // A header's first byte as the sample's last: hidden all the same.
        val (count, out) = hidden(body + start4 + bytes(header(62)))
        assertEquals(1, count)
        assertArrayEquals(body + start4 + bytes(header(47)), out)
    }

    @Test
    fun emptyShortAndReadOnlySamplesAreHarmless() {
        assertEquals(0, DvBaseLayer.hideDolbyVision(ByteBuffer.allocate(0)))
        val smallest = bytes(0, 0, 1, header(62))
        for (n in 0..3) assertEquals(0, DvBaseLayer.hideDolbyVision(ByteBuffer.wrap(smallest.copyOf(n))))
        assertEquals(1, DvBaseLayer.hideDolbyVision(ByteBuffer.wrap(smallest.copyOf())))
        // A buffer that can't be changed is left alone.
        assertEquals(0, DvBaseLayer.hideDolbyVision(ByteBuffer.wrap(smallest.copyOf()).asReadOnlyBuffer()))
    }

    @Test
    fun onlyBetweenPositionAndLimitWhichStayAsTheyWere() {
        val before = unit(header(62), rpu)
        val sample = parameterSets + unit(header(19), slice) + unit(header(63), slice) + unit(header(62), rpu)
        val after = unit(header(63), slice)
        val all = before + sample + after
        // A direct buffer, as MediaCodec's input buffers are.
        val buffer = ByteBuffer.allocateDirect(all.size)
        buffer.put(all)
        buffer.position(before.size)
        buffer.limit(before.size + sample.size)
        assertEquals(2, DvBaseLayer.hideDolbyVision(buffer))
        assertEquals(before.size, buffer.position())
        assertEquals(before.size + sample.size, buffer.limit())
        buffer.clear()
        val out = ByteArray(all.size).also { buffer.get(it) }
        assertArrayEquals(before + hidden(sample).second + after, out)
        // A start code's zeros before the position, or its header past the limit, aren't the sample's.
        val split = bytes(0, 0, 1, header(62), 0x01, 0x80)
        val late = ByteBuffer.wrap(split.copyOf())
        late.position(2)
        assertEquals(0, DvBaseLayer.hideDolbyVision(late))
        val cut = ByteBuffer.wrap(split.copyOf())
        cut.limit(3)
        assertEquals(0, DvBaseLayer.hideDolbyVision(cut))
        assertArrayEquals(split, ByteArray(split.size).also { cut.clear(); cut.get(it) })
    }

    @Test
    fun findsExactlyWhatReadingEveryByteFinds() {
        // Samples of every length up to a few thousand bytes, some dense with 00 and 01 (start codes, near misses, start
        // codes straddling the 8-byte steps), some mostly picture-like with rare zeros (long 8-byte runs), from every
        // position: the same bytes changed and the same count as the search played on the TV, which read every byte.
        val random = Random(47)
        val dense = intArrayOf(0x00, 0x00, 0x00, 0x01, 0x03, 0x7C, 0x7D, 0x7E, 0x7F, 0xFC, 0xFE, 0xFF)
        repeat(4000) { round ->
            val size = if (round % 4 == 0) random.nextInt(0, 4000) else random.nextInt(0, 64)
            val sparse = round % 2 == 0
            val all = ByteArray(size) {
                if (sparse && random.nextInt(40) != 0) random.nextInt(1, 256).toByte() else dense[random.nextInt(dense.size)].toByte()
            }
            val from = random.nextInt(0, minOf(size, 9) + 1)
            val to = random.nextInt(from, size + 1)
            val expected = all.copyOf()
            val wanted = everyByte(ByteBuffer.wrap(expected), from, to)
            val actual = all.copyOf()
            val buffer = ByteBuffer.wrap(actual)
            buffer.position(from)
            buffer.limit(to)
            assertEquals("round $round", wanted, DvBaseLayer.hideDolbyVision(buffer))
            assertArrayEquals("round $round", expected, actual)
            assertEquals(from, buffer.position())
            assertEquals(to, buffer.limit())
        }
    }

    /** The search as first played on the TV: every byte read in turn. */
    private fun everyByte(data: ByteBuffer, from: Int, end: Int): Int {
        var hidden = 0
        var i = from
        while (i + 3 < end) {
            if (data.get(i) == 0.toByte() && data.get(i + 1) == 0.toByte() && data.get(i + 2) == 1.toByte()) {
                val h = i + 3
                val b = data.get(h).toInt() and 0xFF
                val type = (b shr 1) and 0x3F
                if (type == 62 || type == 63) {
                    data.put(h, ((b and 0x81) or (DvBaseLayer.HIDDEN_TYPE shl 1)).toByte())
                    hidden++
                }
                i = h + 1
            } else {
                i++
            }
        }
        return hidden
    }
}
