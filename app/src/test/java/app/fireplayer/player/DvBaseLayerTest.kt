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
