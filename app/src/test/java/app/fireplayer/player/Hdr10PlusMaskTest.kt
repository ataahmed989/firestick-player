package app.fireplayer.player

import androidx.media3.common.MimeTypes
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** HDR10+ masked in HEVC access units as Media3 hands them to the decoder (Annex B), built here byte by byte. */
class Hdr10PlusMaskTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private val start4 = bytes(0, 0, 0, 1)
    private val start3 = bytes(0, 0, 1)

    // NAL unit types.
    private val trailR = 1
    private val idr = 19
    private val vps = 32
    private val sps = 33
    private val pps = 34
    private val aud = 35
    private val prefixSei = 39
    private val suffixSei = 40
    private val dolbyVisionRpu = 62

    /** A NAL unit: start code, two-byte header (layer 0, temporal id 0), then [payload] as it is in the stream. */
    private fun nal(type: Int, payload: ByteArray, start: ByteArray = start4) = start + bytes(type shl 1, 1) + payload

    /** A value as SEI writes it: 0xFF for each 255, then the rest. */
    private fun sized(value: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var left = value
        while (left >= 255) {
            out.write(0xFF)
            left -= 255
        }
        out.write(left)
        return out.toByteArray()
    }

    /** One SEI message: its type, its size, its payload (before emulation prevention). */
    private fun message(type: Int, payload: ByteArray) = sized(type) + sized(payload.size) + payload

    /** An encoder's emulation prevention: 03 after two zero bytes whenever the next byte is 00 to 03. */
    private fun escape(rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var zeros = 0
        for (b in rbsp) {
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v <= 3) {
                out.write(3)
                zeros = 0
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** A prefix SEI NAL unit's payload: the messages, the closing bits, escaped. */
    private fun sei(vararg messages: ByteArray): ByteArray =
        escape(messages.fold(ByteArray(0)) { all, m -> all + m } + bytes(0x80))

    /** An HDR10+ payload (ST 2094-40): its T.35 prefix, then data with zeros an encoder must escape. */
    private val hdr10PlusPayload = bytes(
        0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04, // country, provider, provider oriented code, application id
        0x01, 0x40, 0x00, 0x00, 0x02, 0x7F, 0x10, 0x00, 0x00, 0x00, 0x33, 0x80, 0x55, 0x21, 0x00, 0x00, 0x01, 0x6C,
    )

    /** CEA-708 captions (ATSC A/53, "GA94"): T.35 too, from another provider, and must stay. */
    private val captionsPayload = bytes(0xB5, 0x00, 0x31, 0x47, 0x41, 0x39, 0x34, 0x03, 0xC1, 0xFF, 0xFC, 0x80, 0x80)

    /** Dolby's T.35 code (0x003B), as Dolby Vision uses in other codecs: must stay. */
    private val dolbyPayload = bytes(0xB5, 0x00, 0x3B, 0x00, 0x00, 0x08, 0x00, 0x37, 0xCD, 0x08)

    /** Mastering display colour volume (SEI 137), part of plain HDR10: must stay. */
    private val masteringDisplay = message(137, ByteArray(24) { (it * 11 + 3).toByte() })
    /** Content light level (SEI 144): must stay. */
    private val lightLevel = message(144, bytes(0x03, 0xE8, 0x01, 0x90))

    private val parameterSets = nal(vps, bytes(0x0C, 0x01, 0xFF, 0xFF)) + nal(sps, bytes(0x01, 0x01, 0x60, 0x00, 0x00, 0x03, 0x00, 0xB0)) +
        nal(pps, bytes(0xC1, 0x72, 0xB4, 0x62))
    private val slice = bytes(0xAF, 0x12, 0x34, 0x00, 0x00, 0x03, 0x01, 0x99, 0x5A, 0x00, 0x00, 0x03, 0x00, 0x42)

    /** An IDR access unit with [seiPayload] as its prefix SEI. */
    private fun idrUnit(seiPayload: ByteArray, start: ByteArray = start4) =
        nal(aud, bytes(0x10), start) + parameterSets + nal(prefixSei, seiPayload, start) + nal(idr, slice, start)

    private fun masked(unit: ByteArray): Pair<Int, ByteArray> {
        val copy = unit.copyOf()
        val count = Hdr10PlusMask.mask(ByteBuffer.wrap(copy))
        return count to copy
    }

    @Test fun hdr10PlusIsMaskedInPlace() {
        val unit = idrUnit(sei(masteringDisplay, lightLevel, message(4, hdr10PlusPayload)))
        val (count, out) = masked(unit)
        assertEquals(1, count)
        // The same unit with type 254 instead of 4: escaped alike (both above 3), so only that byte differs.
        assertArrayEquals(idrUnit(sei(masteringDisplay, lightLevel, message(Hdr10PlusMask.MASKED_TYPE, hdr10PlusPayload))), out)
        assertEquals(unit.size, out.size)
        assertEquals(1, unit.indices.count { unit[it] != out[it] })
    }

    @Test fun threeByteStartCodesToo() {
        val unit = idrUnit(sei(message(4, hdr10PlusPayload)), start3)
        val (count, out) = masked(unit)
        assertEquals(1, count)
        assertArrayEquals(idrUnit(sei(message(Hdr10PlusMask.MASKED_TYPE, hdr10PlusPayload)), start3), out)
    }

    @Test fun otherT35PayloadsAndOtherMessagesStay() {
        val unit = idrUnit(sei(message(4, captionsPayload), message(4, dolbyPayload), masteringDisplay, lightLevel))
        val (count, out) = masked(unit)
        assertEquals(0, count)
        assertArrayEquals(unit, out)
    }

    @Test fun onlyTheHdr10PlusMessageAmongOthers() {
        val unit = idrUnit(sei(message(4, captionsPayload), message(4, hdr10PlusPayload), message(4, dolbyPayload)))
        val (count, out) = masked(unit)
        assertEquals(1, count)
        assertArrayEquals(
            idrUnit(sei(message(4, captionsPayload), message(Hdr10PlusMask.MASKED_TYPE, hdr10PlusPayload), message(4, dolbyPayload))),
            out,
        )
    }

    @Test fun everyPrefixSeiBeforeThePicture() {
        val unit = nal(aud, bytes(0x50)) + nal(prefixSei, sei(message(4, hdr10PlusPayload))) +
            nal(prefixSei, sei(lightLevel, message(4, hdr10PlusPayload))) + nal(trailR, slice)
        val (count, out) = masked(unit)
        assertEquals(2, count)
        val expected = nal(aud, bytes(0x50)) + nal(prefixSei, sei(message(254, hdr10PlusPayload))) +
            nal(prefixSei, sei(lightLevel, message(254, hdr10PlusPayload))) + nal(trailR, slice)
        assertArrayEquals(expected, out)
    }

    @Test fun nothingAfterThePictureDataIsTouched() {
        // A prefix SEI after a slice (which a decoder takes as the next frame's) and a suffix SEI are never read, nor is
        // Dolby Vision's RPU, even holding bytes that look like an HDR10+ message.
        val lookalike = bytes(0x01, 0x04, 0x18) + hdr10PlusPayload.copyOf(18)
        val unit = nal(aud, bytes(0x50)) + nal(trailR, slice) + nal(prefixSei, sei(message(4, hdr10PlusPayload))) +
            nal(suffixSei, sei(message(4, hdr10PlusPayload))) + nal(dolbyVisionRpu, lookalike)
        val (count, out) = masked(unit)
        assertEquals(0, count)
        assertArrayEquals(unit, out)
    }

    @Test fun dolbyVisionRpuBeforeThePictureIsSkipped() {
        val lookalike = bytes(0x01, 0x04, 0x18) + hdr10PlusPayload.copyOf(18)
        val unit = nal(dolbyVisionRpu, lookalike) + nal(prefixSei, sei(message(4, hdr10PlusPayload))) + nal(idr, slice)
        val (count, out) = masked(unit)
        assertEquals(1, count)
        assertArrayEquals(nal(dolbyVisionRpu, lookalike) + nal(prefixSei, sei(message(254, hdr10PlusPayload))) + nal(idr, slice), out)
    }

    @Test fun emulationPreventionBytesAreSkippedWhileCounting() {
        // A user-data message of zeros, escaped to more bytes than its size says: read without leaving out its 03s,
        // the messages after it would be misread.
        val zeros = message(5, ByteArray(40))
        val rbspSize = zeros.size
        val unit = idrUnit(sei(zeros, message(4, hdr10PlusPayload)))
        assertTrue(sei(zeros).size > rbspSize + 1)
        val (count, out) = masked(unit)
        assertEquals(1, count)
        assertArrayEquals(idrUnit(sei(zeros, message(254, hdr10PlusPayload))), out)
    }

    @Test fun escapedTypeAndSizeBytesAreReadAsTheirValues() {
        // A payload ending in 00, then a buffering period message (type 0, size 2): 00 00 02 is written 00 00 03 02,
        // so the size byte comes after an emulation-prevention byte.
        val endsInZero = message(5, bytes(0x11, 0x22, 0x00))
        val bufferingPeriod = message(0, bytes(0xAA, 0xBB))
        // A payload ending in 00 00, then a picture timing message (type 1): its type byte is escaped the same way.
        val endsInZeros = message(5, bytes(0x11, 0x22, 0x00, 0x00))
        val pictureTiming = message(1, bytes(0x00, 0x00, 0x00))
        val before = arrayOf(endsInZero, bufferingPeriod, endsInZeros, pictureTiming)
        val raw = before.fold(ByteArray(0)) { all, m -> all + m }
        assertTrue(sei(*before).size >= raw.size + 1 + 3)
        val unit = idrUnit(sei(*before, message(4, hdr10PlusPayload)))
        val (count, out) = masked(unit)
        assertEquals(1, count)
        assertArrayEquals(idrUnit(sei(*before, message(254, hdr10PlusPayload))), out)
    }

    @Test fun notT35OrTooShortIsLeft() {
        // Type 259 (FF 04) is not T.35, and a T.35 message shorter than the HDR10+ prefix can't be HDR10+.
        val unit = idrUnit(sei(message(259, hdr10PlusPayload), message(4, hdr10PlusPayload.copyOf(5))))
        val (count, out) = masked(unit)
        assertEquals(0, count)
        assertArrayEquals(unit, out)
    }

    @Test fun cutShortAnywhereNeverThrowsAndMasksOnlyAWholePrefix() {
        val unit = idrUnit(sei(masteringDisplay, message(4, hdr10PlusPayload)))
        val full = masked(unit).second
        val typeAt = unit.indices.first { unit[it] != full[it] }
        // The last byte of the HDR10+ prefix: from the type byte, past the size byte, six bytes on.
        val prefixEnd = typeAt + 2 + 5
        for (n in 0..unit.size) {
            val cut = unit.copyOf(n)
            val count = Hdr10PlusMask.mask(ByteBuffer.wrap(cut))
            assertEquals("cut at $n", if (n > prefixEnd) 1 else 0, count)
            for (i in 0 until n) assertEquals("cut at $n, byte $i", if (i == typeAt && count == 1) full[i] else unit[i], cut[i])
        }
    }

    @Test fun junkAndEmptyBuffersAreHarmless() {
        assertEquals(0, Hdr10PlusMask.mask(ByteBuffer.allocate(0)))
        assertEquals(0, Hdr10PlusMask.mask(ByteBuffer.wrap(bytes(0, 0, 1))))
        assertEquals(0, Hdr10PlusMask.mask(ByteBuffer.wrap(bytes(0, 0, 0, 1, prefixSei shl 1))))
        assertEquals(0, Hdr10PlusMask.mask(ByteBuffer.wrap(bytes(0, 0, 0, 1, prefixSei shl 1, 1))))
        assertEquals(0, Hdr10PlusMask.mask(ByteBuffer.wrap(bytes(0, 0, 0, 1, prefixSei shl 1, 1, 0x04))))
        assertEquals(0, Hdr10PlusMask.mask(ByteBuffer.wrap(bytes(0, 0, 0, 1, prefixSei shl 1, 1, 0xFF, 0xFF, 0xFF))))
        val noise = ByteArray(4096) { ((it * 7919) xor (it shr 3)).toByte() }
        Hdr10PlusMask.mask(ByteBuffer.wrap(noise.copyOf()))
        // A read-only buffer can't be changed: left alone.
        val unit = idrUnit(sei(message(4, hdr10PlusPayload)))
        assertEquals(0, Hdr10PlusMask.mask(ByteBuffer.wrap(unit.copyOf()).asReadOnlyBuffer()))
    }

    @Test fun onlyBetweenPositionAndLimitWhichStayAsTheyWere() {
        val unit = idrUnit(sei(message(4, hdr10PlusPayload)))
        val before = bytes(0x00, 0x00, 0x00, 0x01, prefixSei shl 1, 0x01, 0x04, 0x18) + hdr10PlusPayload
        val after = unit
        val all = before + unit + after
        // A direct buffer, as MediaCodec's input buffers are.
        val buffer = ByteBuffer.allocateDirect(all.size)
        buffer.put(all)
        buffer.position(before.size)
        buffer.limit(before.size + unit.size)
        assertEquals(1, Hdr10PlusMask.mask(buffer))
        assertEquals(before.size, buffer.position())
        assertEquals(before.size + unit.size, buffer.limit())
        buffer.clear()
        val out = ByteArray(all.size).also { buffer.get(it) }
        assertArrayEquals(before + masked(unit).second + after, out)
    }

    @Test fun hevcStreamsAreHevcAndDolbyVisionOnHevc() {
        assertTrue(Hdr10PlusMask.hevcStream(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.B0"))
        assertTrue(Hdr10PlusMask.hevcStream(MimeTypes.VIDEO_H265, null))
        assertTrue(Hdr10PlusMask.hevcStream(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.08.06"))
        assertTrue(Hdr10PlusMask.hevcStream(MimeTypes.VIDEO_DOLBY_VISION, "dvh1.05.06"))
        assertFalse(Hdr10PlusMask.hevcStream(MimeTypes.VIDEO_DOLBY_VISION, "dvav.09.05"))
        assertFalse(Hdr10PlusMask.hevcStream(MimeTypes.VIDEO_DOLBY_VISION, "dav1.10.06"))
        assertFalse(Hdr10PlusMask.hevcStream(MimeTypes.VIDEO_DOLBY_VISION, null))
        assertFalse(Hdr10PlusMask.hevcStream(MimeTypes.VIDEO_H264, "avc1.640028"))
        assertFalse(Hdr10PlusMask.hevcStream(MimeTypes.VIDEO_AV1, null))
        assertFalse(Hdr10PlusMask.hevcStream(null, null))
    }

    @Test fun maskedOnlyWhenTheTvLacksHdr10Plus() {
        val hdr10 = 2
        val hlg = 3
        val dolbyVision = 1
        val hdr10Plus = Hdr10PlusMask.HDR_TYPE_HDR10_PLUS
        // Android 11 (Fire OS 8): the TV that reported no HDR, an HDR10-only TV, and an HDR10+ TV.
        assertTrue(Hdr10PlusMask.shouldMask(30, intArrayOf()))
        assertTrue(Hdr10PlusMask.shouldMask(30, intArrayOf(dolbyVision, hdr10, hlg)))
        assertFalse(Hdr10PlusMask.shouldMask(30, intArrayOf(hdr10, hdr10Plus)))
        // Android 7 to 9 can't report HDR10+: only a TV without any HDR.
        assertTrue(Hdr10PlusMask.shouldMask(28, intArrayOf()))
        assertFalse(Hdr10PlusMask.shouldMask(28, intArrayOf(hdr10, hlg)))
        assertFalse(Hdr10PlusMask.shouldMask(24, intArrayOf(hdr10)))
        // Unknown: as before.
        assertFalse(Hdr10PlusMask.shouldMask(23, intArrayOf()))
        assertFalse(Hdr10PlusMask.shouldMask(30, null))
    }
}
