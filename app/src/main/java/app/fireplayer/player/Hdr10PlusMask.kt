package app.fireplayer.player

import androidx.media3.common.MimeTypes
import java.nio.ByteBuffer

/**
 * Hides HDR10+ from the decoder when the TV can't use it ([Hdr10PlusGate], [PacedVideoRenderer]).
 *
 * HDR10+ travels inside an HEVC film as an SEI message (ITU-T T.35, with Samsung's country and provider codes) ahead of
 * each frame's picture data. A TV without HDR10+ has no use for it, yet the decoder still takes it in for every frame:
 * on a Fire TV Stick 4K Max playing a Dolby Vision + HDR10+ film on a TV that reported no HDR, the MediaTek decoder
 * logged "Hdr10Plus Buffer Header is FULL" on every frame, and the errors stopped once HDR was turned on at the TV.
 * Here each such message's type byte is changed, in place, from 4 (T.35) to 254 (a type no decoder knows), so decoders
 * skip it: nothing moves, nothing is copied, and the picture and the film's other metadata (HDR10's own, and Dolby
 * Vision's, which comes after the picture data) are left as they are.
 *
 * Ported from Media3's HevcSeiUtil (androidx/media, main branch,
 * libraries/exoplayer/src/main/java/androidx/media3/exoplayer/video/HevcSeiUtil.java; Copyright 2026 The Android Open
 * Source Project, Apache License 2.0), which masks the other T.35 messages and keeps HDR10+: this masks HDR10+ only,
 * found as Media3's CodecSpecificDataUtil.isHdr10PlusMetadata finds it, read here past any emulation-prevention bytes.
 */
object Hdr10PlusMask {

    /** HEVC's prefix SEI: the messages that come before a frame's picture data. */
    private const val PREFIX_SEI = 39
    /** NAL unit types 0 to 31 are picture data (VCL): the prefix SEI is all before the first of them. */
    private const val LAST_VCL = 31
    /** user_data_registered_itu_t_t35. */
    private const val T35 = 4
    /** A reserved SEI payload type, which decoders skip (Media3's SEI_PAYLOAD_TYPE_UNSPECIFIED). */
    const val MASKED_TYPE = 254
    /**
     * How an HDR10+ message starts: country code 0xB5 (United States), provider code 0x003C (Samsung), provider
     * oriented code 0x0001, application identifier 4 (ST 2094-40).
     */
    private val HDR10_PLUS = intArrayOf(0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04)

    /** Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS (Android 10). */
    const val HDR_TYPE_HDR10_PLUS = 4

    /**
     * Whether the decoder of [codecMime] is given an HEVC stream: HEVC itself, or Dolby Vision carried on HEVC
     * ([codecs] dvhe or dvh1: profiles 4, 5, 7 and 8). Dolby Vision on H.264 or AV1 has no HEVC SEI to look at.
     */
    fun hevcStream(codecMime: String?, codecs: String?): Boolean = when (codecMime) {
        MimeTypes.VIDEO_H265 -> true
        MimeTypes.VIDEO_DOLBY_VISION -> codecs != null && (codecs.startsWith("dvhe") || codecs.startsWith("dvh1"))
        else -> false
    }

    /**
     * Whether HDR10+ is hidden from the decoder on a TV whose display reports [hdrTypes] (Display.HdrCapabilities;
     * null when it can't be read) under Android [sdk]:
     *  - Android 10 and later: whenever HDR10+ isn't among them.
     *  - Android 7 to 9 can't report HDR10+ at all (the type came with Android 10), so only on a TV reporting no HDR
     *    whatever: a TV with HDR may still take HDR10+ the way the TV box itself passes it on.
     *  - Before Android 7, or when the display can't be read: never, as before.
     */
    fun shouldMask(sdk: Int, hdrTypes: IntArray?): Boolean = when {
        sdk < 24 || hdrTypes == null -> false
        sdk >= 29 -> HDR_TYPE_HDR10_PLUS !in hdrTypes
        else -> hdrTypes.isEmpty()
    }

    /**
     * Masks every HDR10+ message in the prefix SEI of the access unit in [buffer], from its position to its limit
     * (Annex B: each NAL unit after a 00 00 01 or 00 00 00 01 start code, as Media3's extractors hand HEVC to the
     * decoder). Stops at the first picture (VCL) NAL unit, so the picture data, and a suffix SEI or Dolby Vision's RPU
     * after it, are never read. The position and limit stay as they were. Returns how many messages were masked.
     */
    fun mask(buffer: ByteBuffer, values: Hdr10PlusValues? = null): Int =
        if (buffer.isReadOnly) 0 else scan(buffer, write = true, values).coerceAtLeast(0)

    /**
     * How many HDR10+ messages the prefix SEI of the access unit in [buffer] carries, read as [mask] reads it, with
     * nothing changed: whether the film has HDR10+ ([PlayFacts.hdr10PlusSeen]) on a TV it isn't masked for. Both read
     * the last such message's numbers into [values] when given ([Hdr10PlusValues]).
     */
    fun count(buffer: ByteBuffer, values: Hdr10PlusValues? = null): Int = scan(buffer, write = false, values).coerceAtLeast(0)

    /**
     * One sample as a frame, for [Hdr10PlusWatch]: [mask] ([write]) or [count], or [NO_PICTURE] when no picture (VCL) NAL
     * unit was met in it, so it isn't a frame as this reads them (cut short, empty, not Annex B) and says nothing of
     * whether the film's frames come with HDR10+ or without. A read-only buffer is only counted.
     */
    fun frame(buffer: ByteBuffer, write: Boolean, values: Hdr10PlusValues? = null): Int =
        scan(buffer, write && !buffer.isReadOnly, values)

    /** [frame]'s answer for a sample without picture data. */
    const val NO_PICTURE = -1

    /** How many HDR10+ messages were met (and masked, with [write]) before the first picture NAL unit; [NO_PICTURE] without one. */
    private fun scan(buffer: ByteBuffer, write: Boolean, values: Hdr10PlusValues?): Int {
        val limit = buffer.limit()
        var search = buffer.position()
        if (limit - search < 4) return NO_PICTURE
        var masked = 0
        var picture = false
        while (search < limit) {
            val nal = startCode(buffer, search, limit)
            // The NAL unit header's first byte follows the three bytes 00 00 01.
            if (nal + 3 >= limit) break
            val type = (buffer.get(nal + 3).toInt() and 0x7E) shr 1
            if (type <= LAST_VCL) {
                picture = true
                break
            }
            if (type == PREFIX_SEI) {
                // Its messages start after the two-byte header and end where the next NAL unit starts.
                val end = startCode(buffer, nal + 5, limit)
                masked += maskMessages(buffer, nal + 5, end, write, values)
                search = end
            } else {
                search = nal + 3
            }
        }
        // Messages met without a picture after them are still told of: a sample cut short was masked all the same.
        return if (picture || masked > 0) masked else NO_PICTURE
    }

    /** Masks ([write]) or counts the HDR10+ messages of one prefix SEI NAL unit, whose messages lie from [from] to [end]. */
    private fun maskMessages(buffer: ByteBuffer, from: Int, end: Int, write: Boolean, values: Hdr10PlusValues?): Int {
        var at = from
        // Zero bytes just read, carried across the whole NAL unit: 00 00 03 is an emulation-prevention byte, which the
        // encoder put in so the data can't look like a start code, and which isn't part of the message.
        var zeros = 0
        var masked = 0
        while (at < end) {
            // payloadType: 0xFF bytes adding 255 each, then a last byte. T.35 is the single byte 4.
            var payloadType = 0
            var typeAt = -1
            var typeRead = false
            while (at < end) {
                val b = buffer.get(at).toInt() and 0xFF
                if (b == 0x03 && zeros >= 2) {
                    zeros = 0
                    at++
                    continue
                }
                zeros = if (b == 0) zeros + 1 else 0
                payloadType += b
                if (b != 0xFF) {
                    if (payloadType == T35) typeAt = at
                    at++
                    typeRead = true
                    break
                }
                at++
            }
            // Cut short (or only the closing bits left): nothing more to look at.
            if (!typeRead) break
            // payloadSize, read the same way.
            var payloadSize = 0
            var sizeRead = false
            while (at < end) {
                val b = buffer.get(at).toInt() and 0xFF
                if (b == 0x03 && zeros >= 2) {
                    zeros = 0
                    at++
                    continue
                }
                zeros = if (b == 0) zeros + 1 else 0
                payloadSize += b
                at++
                if (b != 0xFF) {
                    sizeRead = true
                    break
                }
            }
            if (!sizeRead) break
            if (typeAt >= 0 && isHdr10Plus(buffer, at, end, zeros, payloadSize)) {
                values?.take(buffer, at, end, zeros, payloadSize)
                if (write) buffer.put(typeAt, MASKED_TYPE.toByte())
                masked++
            }
            // Past the payload: payloadSize bytes of the message, not counting emulation-prevention bytes.
            var left = payloadSize
            while (left > 0 && at < end) {
                val b = buffer.get(at).toInt() and 0xFF
                at++
                if (b == 0x03 && zeros >= 2) {
                    zeros = 0
                } else {
                    zeros = if (b == 0) zeros + 1 else 0
                    left--
                }
            }
        }
        return masked
    }

    /**
     * Whether the T.35 payload starting at [start] (with [zeros] zero bytes just before it) is HDR10+: its first
     * bytes, emulation-prevention bytes left out, are [HDR10_PLUS], all inside the message's [size] and before [end].
     */
    private fun isHdr10Plus(buffer: ByteBuffer, start: Int, end: Int, zeros: Int, size: Int): Boolean {
        if (size < HDR10_PLUS.size) return false
        var at = start
        var z = zeros
        var i = 0
        while (i < HDR10_PLUS.size) {
            if (at >= end) return false
            val b = buffer.get(at).toInt() and 0xFF
            at++
            if (b == 0x03 && z >= 2) {
                z = 0
                continue
            }
            z = if (b == 0) z + 1 else 0
            if (b != HDR10_PLUS[i]) return false
            i++
        }
        return true
    }

    /**
     * Where the next start code (00 00 01) from [from] begins: the offset of its first 00, so its NAL unit's header is
     * 3 bytes on (a four-byte 00 00 00 01 is found from its second 00). [limit] when there's none.
     */
    private fun startCode(buffer: ByteBuffer, from: Int, limit: Int): Int {
        var zeros = 0
        var at = from
        while (at < limit) {
            val b = buffer.get(at).toInt() and 0xFF
            if (zeros >= 2 && b == 0x01) return at - 2
            zeros = if (b == 0) zeros + 1 else 0
            at++
        }
        return limit
    }
}
