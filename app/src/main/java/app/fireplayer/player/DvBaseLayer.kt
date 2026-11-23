package app.fireplayer.player

import androidx.media3.common.MimeTypes

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
     * limited range, generic static metadata), which is what a profile 7 base layer always is. Without it the decoder
     * still outputs PQ but the TV is never switched to HDR, and on the 4K Max the video plane then stays black.
     */
    fun hdr10(own: androidx.media3.common.ColorInfo?): androidx.media3.common.ColorInfo {
        if (own != null && own.colorTransfer == androidx.media3.common.C.COLOR_TRANSFER_ST2084 && own.hdrStaticInfo != null) {
            return own
        }
        return androidx.media3.common.ColorInfo.Builder()
            .setColorSpace(androidx.media3.common.C.COLOR_SPACE_BT2020)
            .setColorRange(own?.colorRange?.takeIf { it != androidx.media3.common.Format.NO_VALUE } ?: androidx.media3.common.C.COLOR_RANGE_LIMITED)
            .setColorTransfer(androidx.media3.common.C.COLOR_TRANSFER_ST2084)
            .setHdrStaticInfo(own?.hdrStaticInfo ?: genericStaticInfo())
            .build()
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
}
