package app.fireplayer.player

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil

/**
 * Dolby Vision profile 7 on a TV whose Dolby Vision decoders are single-layer: played as its HDR10 base layer.
 *
 * Profile 7 (UHD Blu-ray remuxes, "FEL" and "MEL") carries two layers in one track: an ordinary HEVC Main 10 HDR10
 * picture (BT.2020, PQ) and an enhancement layer in NAL units a plain HEVC decoder skips. Media3 still gives it to a
 * Dolby Vision decoder that only decodes the single-layer profiles (5 and 8), with a warning that the format exceeds
 * it (its own way back to HEVC, MediaCodecUtil.getAlternativeCodecMimeType, covers profiles 4, 8 and 9, not 7): on a
 * Fire TV Stick 4K Max (OMX.MTK.VIDEO.DECODER.DVHE.STN) a profile 7 remux played with a black picture, its frames
 * counted as shown and the sound playing, while the TV stayed out of HDR. Such a track goes to the HEVC decoder
 * instead ([PacedVideoRenderer]), which shows its base layer as HDR10, as Kodi and other players do. Profiles this TV's
 * Dolby Vision decoders take (5 and 8 there) stay on them as before, and a TV with a decoder for profile 7 keeps
 * playing it as Dolby Vision.
 *
 * What it changes is said where the picture is described: Picture & sound's Range ([words]), and the copy's measured
 * facts ([MediaSpec.dvBaseLayer]), by which the Sources list and Best for this TV count it as HDR10 on this TV.
 */
object DvBaseLayer {

    /** Dolby Vision's dual-layer profile with an HDR10 base layer (MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheDtb). */
    const val PROFILE = 7

    /**
     * Dolby Vision decoders that take the single-layer profiles only, whatever profiles they declare: MediaTek's
     * single-track ones, the only Dolby Vision decoders a Fire TV Stick 4K Max lists (with their secure versions).
     */
    private val SINGLE_LAYER = listOf("OMX.MTK.VIDEO.DECODER.DVHE.STN", "OMX.MTK.VIDEO.DECODER.DVHE.STH")

    /** Dolby Vision on HEVC, AVC or AV1, as its codecs string starts. */
    private val PREFIXES = listOf("dvhe", "dvh1", "dvav", "dva1", "dav1")

    /** The profile field, as Media3 reads it (CodecSpecificDataUtil): an ignored profile space, then the number. */
    private val PROFILE_FIELD = Regex("""^\D?(\d+)$""")

    /**
     * The film's video renderer found, for a profile 7 track, that none of this TV's Dolby Vision decoders takes profile
     * 7, so it plays as its HDR10 base layer ([PacedVideoRenderer]): the same for every profile 7 track, the decoders
     * being the TV's. Set on the playback thread before the track is selected; read on the main thread.
     */
    @Volatile var tvPlaysBase = false

    /** A Dolby Vision decoder of this TV: its name and the profiles its capabilities declare (CodecProfileLevel values). */
    class Decoder(val name: String, val profiles: IntArray)

    /** The Dolby Vision profile of a codecs string ("dvhe.07.06", "dvh1.08.06" → 7, 8); null when it isn't one. */
    fun profileOf(codecs: String?): Int? {
        val c = codecs ?: return null
        val parts = c.split('.')
        if (parts.size < 2 || parts[0] !in PREFIXES) return null
        val number = PROFILE_FIELD.matchEntire(parts[1])?.groupValues?.get(1) ?: return null
        return number.toIntOrNull()?.takeIf { it in 0..10 }
    }

    /** The CodecProfileLevel value of Dolby Vision [profile]: each profile has its own bit (profile 7, 0x80). */
    fun profileLevel(profile: Int): Int = 1 shl profile

    /** A decoder known to take single-layer Dolby Vision only, by its name (its secure version included). */
    fun singleLayer(name: String): Boolean = SINGLE_LAYER.any { name == it || name.startsWith("$it.") }

    /** Whether the Dolby Vision decoder [name], declaring [profiles], decodes [profile]. */
    fun decodes(name: String, profiles: IntArray, profile: Int): Boolean {
        if (profile == PROFILE && singleLayer(name)) return false
        val wanted = profileLevel(profile)
        return profiles.any { it == wanted }
    }

    /**
     * Whether a Dolby Vision track of [profile] goes to the HEVC decoders as its base layer: a profile 7 track, none of
     * this TV's Dolby Vision decoders ([dvDecoders]) decoding profile 7, and an HEVC decoder to take it ([hevcDecoders]
     * of them). Every other profile stays as Media3 has it.
     */
    fun baseLayerInstead(profile: Int?, dvDecoders: List<Decoder>, hevcDecoders: Int): Boolean =
        profile == PROFILE && hevcDecoders > 0 && dvDecoders.none { decodes(it.name, it.profiles, PROFILE) }

    /**
     * Whether the track playing ([mime], [codecs], as the file has them) is played as its HDR10 base layer: a profile 7
     * Dolby Vision track on a TV whose film renderer found it does that ([tvPlaysBase]).
     */
    fun playedAsBase(mime: String?, codecs: String?, tvPlaysBase: Boolean): Boolean =
        tvPlaysBase && mime == MimeTypes.VIDEO_DOLBY_VISION && profileOf(codecs) == PROFILE

    /** Picture & sound's Range for a track played as its base layer: "HDR10 (Dolby Vision profile 7 base layer)". */
    fun words(profile: Int): String = "HDR10 (Dolby Vision profile $profile base layer)"

    /** The log's one line when a track is played as its base layer; [dvDecoders]: how many Dolby Vision decoders the TV has. */
    fun logLine(dvDecoders: Int): String =
        if (dvDecoders > 0) "Dolby Vision profile $PROFILE played as HDR10: this TV's Dolby Vision decoder is single-layer"
        else "Dolby Vision profile $PROFILE played as HDR10: this TV has no Dolby Vision decoder"

    /**
     * The colour a profile 7 base layer is played with: its own when the file states HDR, otherwise HDR10 (BT.2020, PQ,
     * limited range), which is what a profile 7 base layer always is, with the track's own static metadata where it
     * states it, else [sei] (the film's own, from its parameter sets: [seiStaticInfo]), else generic. Without it the
     * decoder still outputs PQ but the TV is never switched to HDR, and on the 4K Max the video plane then stays black.
     */
    fun hdr10(own: androidx.media3.common.ColorInfo?, sei: ByteArray? = null): androidx.media3.common.ColorInfo {
        if (own != null && own.colorTransfer == androidx.media3.common.C.COLOR_TRANSFER_ST2084 && own.hdrStaticInfo != null) {
            return own
        }
        return androidx.media3.common.ColorInfo.Builder()
            .setColorSpace(androidx.media3.common.C.COLOR_SPACE_BT2020)
            .setColorRange(own?.colorRange?.takeIf { it != androidx.media3.common.Format.NO_VALUE } ?: androidx.media3.common.C.COLOR_RANGE_LIMITED)
            .setColorTransfer(androidx.media3.common.C.COLOR_TRANSFER_ST2084)
            .setHdrStaticInfo(own?.hdrStaticInfo ?: sei ?: genericStaticInfo())
            .build()
    }

    /**
     * The film's own HDR10 brightness data, from the SEI messages its parameter sets may carry. Media3 keeps every NAL
     * unit of a file's hvcC (MKV's CodecPrivate, MP4's hvcC box), SEI arrays included, in [initData] (Annex B), and
     * gives them to the decoder as they are, but reads none of them: its Matroska reader takes brightness data only from
     * the track's Colour element, which a profile 7 remux may not have, nor a profile 8 file ([hevcHdr10]). A UHD
     * Blu-ray's base layer carries its mastering display (mastering_display_colour_volume, payload 137) and light levels
     * (content_light_level_info, 144) in prefix SEI NAL units (type 39), and a file made from one may have them in its
     * hvcC too.
     *
     * As CTA-861.3 static metadata, laid out as [genericStaticInfo]. Null without a mastering display message whose
     * values make sense ([masteringOf]); light levels that aren't there or make no sense go as 0, CTA-861.3's "not
     * known". A few hundred bytes read once per format, never a sample.
     */
    @OptIn(UnstableApi::class)
    fun seiStaticInfo(initData: List<ByteArray>?): ByteArray? {
        var mastering: IntArray? = null
        var light: IntArray? = null
        for (data in initData ?: return null) {
            val flags = BooleanArray(3)
            var at = NalUnitUtil.findNalUnit(data, 0, data.size, flags)
            while (at + 3 < data.size) {
                val next = NalUnitUtil.findNalUnit(data, at + 3, data.size, flags)
                if (NalUnitUtil.getH265NalUnitType(data, at) == NalUnitUtil.H265_NAL_UNIT_TYPE_PREFIX_SEI && at + 5 < next) {
                    // Its messages, after the NAL unit's two-byte header, with emulation prevention taken out.
                    val sei = data.copyOfRange(at + 5, next)
                    forEachSei(sei, NalUnitUtil.unescapeStream(sei, sei.size)) { type, from, length ->
                        if (type == MASTERING_DISPLAY && mastering == null) mastering = masteringOf(sei, from, length)
                        if (type == CONTENT_LIGHT_LEVEL && light == null) light = lightOf(sei, from, length)
                    }
                }
                at = next
            }
        }
        val m = mastering ?: return null
        val b = java.nio.ByteBuffer.allocate(25).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put(0)
        for (v in m) b.putShort(v.toShort())
        for (v in light ?: IntArray(2)) b.putShort(v.toShort())
        return b.array()
    }

    /**
     * Calls [block] with each SEI message's payload type, and where its payload starts and how long it is, in the first
     * [size] bytes of [b] (an SEI NAL unit's messages, emulation prevention taken out), up to the stop bit that ends
     * them (a last 80, only zeros after it). A message cut short ends the reading.
     */
    private inline fun forEachSei(b: ByteArray, size: Int, block: (type: Int, from: Int, length: Int) -> Unit) {
        var i = 0
        while (i < size && !(b[i] == 0x80.toByte() && (i + 1 until size).all { b[it].toInt() == 0 })) {
            // Payload type, then size: as many FF bytes (255 each) as it takes, then the rest.
            var type = 0
            while (i < size && b[i] == 0xFF.toByte()) { type += 255; i++ }
            if (i >= size) return
            type += b[i++].toInt() and 0xFF
            var length = 0
            while (i < size && b[i] == 0xFF.toByte()) { length += 255; i++ }
            if (i >= size) return
            length += b[i++].toInt() and 0xFF
            if (length > size - i) return
            block(type, i, length)
            i += length
        }
    }

    /**
     * A mastering display message's values in CTA-861.3's order and units: red, green, blue and white (x, y) in 0.00002
     * units (the message has them green, blue, red, then white, in the same units), the peak in cd/m2 (the message's is
     * in 0.0001 cd/m2) and black in 0.0001 cd/m2. Null when they make no sense: a coordinate above 1 (50000), a peak
     * outside 100 to 10000 nits, black not below the peak or too high for its 16 bits (6.5 nits).
     */
    private fun masteringOf(b: ByteArray, from: Int, length: Int): IntArray? {
        if (length < 24) return null
        val xy = intArrayOf(
            u16(b, from + 8), u16(b, from + 10), u16(b, from), u16(b, from + 2),
            u16(b, from + 4), u16(b, from + 6), u16(b, from + 12), u16(b, from + 14),
        )
        if (xy.any { it > 50_000 }) return null
        val peak = u32(b, from + 16)
        val black = u32(b, from + 20)
        if (peak !in 1_000_000L..100_000_000L || black >= peak || black > 0xFFFF) return null
        return xy + intArrayOf(((peak + 5_000) / 10_000).toInt(), black.toInt())
    }

    /** A content light level message's MaxCLL and MaxFALL (cd/m2); null when cut short or either is above 10000. */
    private fun lightOf(b: ByteArray, from: Int, length: Int): IntArray? {
        if (length < 4) return null
        val levels = intArrayOf(u16(b, from), u16(b, from + 2))
        return levels.takeIf { l -> l.all { it <= 10_000 } }
    }

    private fun u16(b: ByteArray, at: Int): Int = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, at: Int): Long = (u16(b, at).toLong() shl 16) or u16(b, at + 2).toLong()

    /** SEI payload types: mastering_display_colour_volume and content_light_level_info. */
    private const val MASTERING_DISPLAY = 137
    private const val CONTENT_LIGHT_LEVEL = 144

    /**
     * The log's one line on a base layer's brightness data ([hdr10]): from the file's track ([fromTrack], what Media3
     * read of its container's colour), from its SEI ([fromSei], [seiStaticInfo]), or generic; with the peak and light
     * levels [sent] to the decoder.
     */
    fun brightnessLine(sent: ByteArray?, fromTrack: Boolean, fromSei: Boolean): String {
        val from = when {
            fromTrack -> "from the file's track"
            fromSei -> "from the file's SEI"
            else -> "generic"
        }
        val levels = levelsOf(sent) ?: return "HDR10 info $from"
        val none = if (fromTrack || fromSei) "" else " (none in the file's header)"
        return "HDR10 info $from: $levels$none"
    }

    /** "1000 nits, MaxCLL 1452, MaxFALL 400": the peak and light levels in CTA-861.3 static metadata; null when cut short. */
    private fun levelsOf(sent: ByteArray?): String? {
        if (sent == null || sent.size < 25) return null
        // Little-endian, as CTA-861.3 has it.
        fun le16(at: Int) = (sent[at].toInt() and 0xFF) or ((sent[at + 1].toInt() and 0xFF) shl 8)
        return "${le16(17)} nits, MaxCLL ${le16(21)}, MaxFALL ${le16(23)}"
    }

    /** Dolby Vision's single-layer profile with an HDR10, SDR or HLG base (8.1, 8.2, 8.4): streaming's Dolby Vision. */
    const val SINGLE_LAYER_PROFILE = 8

    /**
     * What a Dolby Vision file's base layer is, as its configuration record says (dv_bl_signal_compatibility_id): HDR10
     * (8.1), SDR (8.2), HLG (8.4), or the Blu-ray's HDR10 (profile 7, 8.6). 0 when not known.
     */
    const val SIGNAL_HDR10 = 1
    const val SIGNAL_SDR = 2
    const val SIGNAL_HLG = 4
    const val SIGNAL_BLU_RAY = 6

    /** A Matroska track's record signal, kept on its Format (Format.customData) by the MKV reader ([MkvHeader]). */
    data class Signal(val id: Int)

    /**
     * The base layer's signal from a Dolby Vision configuration record (the body of a dvcC, dvvC or dvwC box, or of a
     * Matroska BlockAddIDExtraData): version 1, a profile from 4 to 10; null for anything else (an HEVC parameter set
     * starts with 00).
     */
    fun signalOf(b: ByteArray): Int? {
        if (b.size < 5 || b[0].toInt() != 1) return null
        val profile = (b[2].toInt() and 0xFF) shr 1
        if (profile !in 4..10) return null
        return (b[4].toInt() and 0xF0) shr 4
    }

    /**
     * The record's signal of a Dolby Vision track as Media3 gives it: the MKV reader's ([Signal]), else the MP4 readers'
     * (the record follows the parameter sets in its initialization data); null without one. Once per format.
     */
    @OptIn(UnstableApi::class)
    fun recordSignal(format: androidx.media3.common.Format): Int? =
        (format.customData as? Signal)?.id ?: format.initializationData.firstNotNullOfOrNull { signalOf(it) }

    /**
     * What a Dolby Vision track's base layer is ([SIGNAL_HDR10], [SIGNAL_SDR], [SIGNAL_HLG]…): its record's, else its
     * transfer, the track's stated colour or its picture's own (the SPS): PQ is HDR10, HLG is HLG, SDR is SDR; 0 when
     * none says. Once per format, never per frame.
     */
    @OptIn(UnstableApi::class)
    fun baseSignal(format: androidx.media3.common.Format): Int {
        recordSignal(format)?.let { return it }
        val unstated = androidx.media3.common.Format.NO_VALUE
        val transfer = format.colorInfo?.colorTransfer?.takeIf { it != unstated }
            ?: spsColour(format.initializationData)?.colorTransfer
        return when (transfer) {
            androidx.media3.common.C.COLOR_TRANSFER_ST2084 -> SIGNAL_HDR10
            androidx.media3.common.C.COLOR_TRANSFER_HLG -> SIGNAL_HLG
            androidx.media3.common.C.COLOR_TRANSFER_SDR -> SIGNAL_SDR
            else -> 0
        }
    }

    /**
     * The colour to give the HEVC decoder for a Dolby Vision profile 8 track it decodes ([decoderMime] video/hevc:
     * Media3's own way back to HEVC for profile 8, on a TV without Dolby Vision): HDR10 colour and static metadata
     * ([hdr10]: the file's own values where it states them, else BT.2020, PQ, limited range and generic metadata, which
     * [hevcHdr10] replaces with the film's own from its SEI where it has them) when the track states PQ without its
     * static metadata, or no colour at all and its picture's own (the SPS in [initData], as Media3's Matroska reader
     * leaves it unread) is PQ or says none. Measured on a Fire TV Stick 4K Max: a profile 8 IMAX copy reached the
     * screen as PQ with no static metadata at all (the TV left to guess the brightness). Null to
     * leave the track's colour as it is: one stating, or whose picture or record ([signal], [recordSignal]) states, HLG
     * (8.4), SDR (8.2) or a colour space other than BT.2020; HDR10 in full; any other format, profile, or decoder.
     */
    @OptIn(UnstableApi::class)
    fun hevcColour(
        sampleMime: String?,
        codecs: String?,
        decoderMime: String?,
        own: androidx.media3.common.ColorInfo?,
        initData: List<ByteArray>? = null,
        signal: Int? = null,
    ): androidx.media3.common.ColorInfo? {
        if (sampleMime != MimeTypes.VIDEO_DOLBY_VISION || decoderMime != MimeTypes.VIDEO_H265) return null
        if (profileOf(codecs) != SINGLE_LAYER_PROFILE) return null
        if (signal == SIGNAL_SDR || signal == SIGNAL_HLG) return null
        val unstated = androidx.media3.common.Format.NO_VALUE
        var space = own?.colorSpace ?: unstated
        var transfer = own?.colorTransfer ?: unstated
        if (transfer == unstated) spsColour(initData)?.let { sps ->
            if (space == unstated) space = sps.colorSpace
            transfer = sps.colorTransfer
        }
        if (space != unstated && space != androidx.media3.common.C.COLOR_SPACE_BT2020) return null
        if (transfer != unstated && transfer != androidx.media3.common.C.COLOR_TRANSFER_ST2084) return null
        return hdr10(own).takeIf { it !== own }
    }

    /** The colour the first SPS in [initData] (Annex B, as Media3 keeps HEVC's parameter sets) states; null without one. */
    @OptIn(UnstableApi::class)
    private fun spsColour(initData: List<ByteArray>?): NalUnitUtil.H265SpsData? {
        for (data in initData ?: return null) {
            val flags = BooleanArray(3)
            var at = NalUnitUtil.findNalUnit(data, 0, data.size, flags)
            while (at + 3 < data.size) {
                val next = NalUnitUtil.findNalUnit(data, at + 3, data.size, flags)
                if (NalUnitUtil.getH265NalUnitType(data, at) == NalUnitUtil.H265_NAL_UNIT_TYPE_SPS) {
                    return runCatching { NalUnitUtil.parseH265SpsNalUnit(data, at + 3, next, null) }.getOrNull()
                }
                at = next
            }
        }
        return null
    }

    /**
     * What a profile 8 track's HEVC decoder is given ([hevcColour]), with its brightness data as a profile 7 base
     * layer's ([hdr10]): the track's own static metadata first, else the film's own from the SEI in its parameter sets
     * ([seiStaticInfo]), else generic; and the log's one line on it ([hevcColourLine]). Null to leave the track's colour
     * as it is. The SEI is read only for such a track, a few hundred bytes when its decoder is set up, never per frame;
     * only the colour the decoder is configured with changes, so no decoder is made again for it.
     */
    @OptIn(UnstableApi::class)
    fun hevcHdr10(format: androidx.media3.common.Format, decoderMime: String?): Hdr10? {
        if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return null
        val colour = hevcColour(format.sampleMimeType, format.codecs, decoderMime, format.colorInfo,
            format.initializationData, recordSignal(format)) ?: return null
        val fromTrack = format.colorInfo?.hdrStaticInfo != null
        val sei = if (fromTrack) null else seiStaticInfo(format.initializationData)
        val given = if (sei == null) colour else colour.buildUpon().setHdrStaticInfo(sei).build()
        return Hdr10(given, hevcColourLine(given.hdrStaticInfo, fromTrack, fromSei = sei != null))
    }

    /** [hevcHdr10]'s colour for the HEVC decoder, and the log's line on where its brightness data came from. */
    class Hdr10(val colour: androidx.media3.common.ColorInfo, val line: String)

    /**
     * The log's one line when a profile 8 track's HEVC decoder is given HDR10 colour ([hevcHdr10]): with the file's own
     * brightness data from its track ([fromTrack]), from its SEI ([fromSei], with the peak and light levels [sent]), or
     * generic.
     */
    fun hevcColourLine(sent: ByteArray?, fromTrack: Boolean, fromSei: Boolean): String {
        val with = when {
            fromTrack -> "the file's own brightness data"
            fromSei -> "brightness data from the file's SEI" + (levelsOf(sent)?.let { ": $it" } ?: "")
            else -> "generic brightness data (the file states none)"
        }
        return "Dolby Vision profile $SINGLE_LAYER_PROFILE on the HEVC decoder: given HDR10 colour with $with"
    }

    /**
     * CTA-861.3 static metadata (type 1, little-endian, as MediaFormat.KEY_HDR_STATIC_INFO): P3-D65 primaries, D65 white,
     * 1000 nits peak, 0.0001 nits black, MaxCLL 1000, MaxFALL 400: the usual 4K Blu-ray master.
     */
    private fun genericStaticInfo(): ByteArray {
        val b = java.nio.ByteBuffer.allocate(25).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put(0)
        // R, G, B (x, y) in 0.00002 units, then the white point.
        for (v in intArrayOf(34000, 16000, 13250, 34500, 7500, 3000, 15635, 16450)) b.putShort(v.toShort())
        b.putShort(1000)  // max mastering luminance, cd/m2
        b.putShort(1)     // min mastering luminance, 0.0001 cd/m2
        b.putShort(1000)  // MaxCLL
        b.putShort(400)   // MaxFALL
        return b.array()
    }

    /**
     * Hides a profile 7 file's Dolby Vision units from the HEVC decoder, in place: every NAL unit of type 62 (the RPU,
     * Dolby Vision's metadata) or 63 (the enhancement layer) becomes reserved type 47, which a decoder skips. Only the
     * type changes: the header's forbidden bit and the layer id's high bit stay as they were.
     *
     * The sample, from its position to its limit, is Annex-B: Media3's Matroska reader turns each NAL unit's length
     * into a 00 00 00 01 start code for every V_MPEGH/ISO/HEVC track (Dolby Vision ones are such tracks), its MP4 and
     * fragmented MP4 readers do the same for every track whose hvcC gives a length size (dvhe and dvh1 tracks carry
     * one), and MPEG-TS is Annex-B already. Emulation prevention keeps 00 00 01 out of a NAL unit's contents, so each
     * one found is a start code.
     *
     * Most of a sample is picture data with no start code in it, so it's searched 8 bytes at a time: 8 bytes with no
     * 00 among them can't hold a start code's two zeros, and none of the 8 positions after the first of them can be its
     * 01. Where there is a 00, as Media3's NalUnitUtil.findNalUnit searches: a byte that's not 00 is no start code's
     * zero, so the 01 isn't there or at the next two bytes (unless it's that 01 itself). Finds exactly what reading
     * every byte would. Nothing is made, and the position and limit stay. Returns how many units were hidden.
     */
    fun hideDolbyVision(data: java.nio.ByteBuffer): Int {
        if (data.isReadOnly) return 0
        val end = data.limit()
        var hidden = 0
        // Where a start code's 01 may be: two bytes into the sample at the earliest, and its NAL unit's header after it.
        var i = data.position() + 2
        while (i < end - 1) {
            if (i + 7 <= end) {
                // The 8 bytes from the one before: a 01 at any of the 8 positions from here needs one of them to be 00.
                val x = data.getLong(i - 1)
                if (((x - ONES) and x.inv() and HIGHS) == 0L) {
                    i += 8
                    continue
                }
            }
            val b = data.get(i).toInt()
            if (b == 0) {
                // A zero of a start code whose 01 is next, or the one after.
                i++
            } else if (b != 1 || data.get(i - 1).toInt() != 0 || data.get(i - 2).toInt() != 0) {
                i += 3
            } else {
                val h = i + 1
                val header = data.get(h).toInt() and 0xFF
                val type = (header shr 1) and 0x3F
                if (type == RPU || type == ENHANCEMENT_LAYER) {
                    data.put(h, ((header and 0x81) or (HIDDEN_TYPE shl 1)).toByte())
                    hidden++
                }
                // The next start code starts after this header byte.
                i = h + 3
            }
        }
        return hidden
    }

    /** Dolby Vision's NAL unit types in an HEVC stream: its RPU (metadata) and its enhancement layer. */
    private const val RPU = 62
    private const val ENHANCEMENT_LAYER = 63

    /**
     * A reserved non-picture NAL unit type, which decoders skip. Like 62 and 63, it may come after a picture's data
     * without starting a new access unit (types 41 to 44 and 48 to 55 would), so the decoder's frames stay as they were.
     */
    const val HIDDEN_TYPE = 47

    /** 0x01 and 0x80 in every byte: (x - [ONES]) and not x has a [HIGHS] bit set exactly when one of x's bytes is 00. */
    private const val ONES = 0x0101010101010101L
    private const val HIGHS = ONES shl 7
}
