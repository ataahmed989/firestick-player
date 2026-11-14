package app.fireplayer.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What one copy of a film or episode is made of: its picture and sound as the file declares them, and how much data
 * each carries. Every number says where it came from ([How]); what nobody said is left unknown (0 or null), never
 * guessed. No score: copies are compared on what was measured or stated (see [CopyRank]).
 */
data class MediaSpec(
    /** "HEVC", "H.264", "AV1", "VP9", "MPEG-2", "VC-1". */
    val videoCodec: String? = null,
    /** The codec's profile as the file has it: "Main 10", "High". */
    val profile: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Float = 0f,
    /** 8, 10 or 12; 0 when the file doesn't say. */
    val bitDepth: Int = 0,
    /** "Dolby Vision", "HDR10", "HLG", or "SDR" (only when the file says which). */
    val hdr: String? = null,
    /** The picture's own data rate, bits a second. */
    val videoBps: Long = 0,
    val videoHow: How = How.NONE,
    /** "TrueHD", "DD+", "DD", "DTS-HD MA", "DTS-HD", "DTS:X", "DTS", "AC-4", "AAC", "FLAC", "Opus", "PCM", "MP3". */
    val audioCodec: String? = null,
    /** Dolby Atmos, as the stream (E-AC-3 JOC) or its track says. */
    val atmos: Boolean = false,
    val channels: Int = 0,
    val sampleRate: Int = 0,
    val audioBps: Long = 0,
    val audioHow: How = How.NONE,
    /** The whole file's average data rate (picture, sound and the rest), bits a second. */
    val overallBps: Long = 0,
    val sizeBytes: Long = 0,
    /** How much of the film the measured rates were counted over, in seconds (all plays together). */
    val measuredSecs: Long = 0,
) {
    /** Where a number came from. */
    enum class How(val label: String) {
        NONE(""),
        /** Counted by FirePlayer from the film as it loaded. */
        MEASURED("measured"),
        /** Written in the file by the tool that made it (MKV statistics) or by the provider's probe of it. */
        STATED("stated"),
        /** The whole file's rate less its sound's: close, not exact. */
        ESTIMATED("about"),
    }

    /** The picture's pixels a second... */
    private val pixelRate: Double get() = width.toDouble() * height * (if (fps > 0) fps.toDouble() else 24.0)

    /**
     * ...and how many bits of picture each pixel of each frame gets: the same codec at a higher figure has more to
     * draw it with. Only for comparing copies of one codec; 0 when either number is unknown.
     */
    val bitsPerPixel: Double get() = if (videoBps > 0 && width > 0 && height > 0) videoBps / pixelRate else 0.0

    /** "HEVC · 10-bit · HDR10 · 19.8 Mb/s": the compact pod's line, of what is known. */
    fun pictureLine(): String? = listOfNotNull(
        videoCodec,
        bitDepth.takeIf { it > 8 }?.let { "$it-bit" },
        hdr?.takeIf { it != "SDR" },
        videoBps.takeIf { it > 0 }?.let { mbps(it) },
    ).joinToString(" · ").ifEmpty { null }

    /** "TrueHD Atmos 7.1", "DD+ 5.1", "Stereo": the sound pod. */
    fun soundLine(): String? {
        val layout = channelsLabel(channels)
        val name = audioCodec?.let { if (atmos) "$it Atmos" else it }
        return listOfNotNull(name, layout).joinToString(" ").ifEmpty { null }
    }

    companion object {
        /** [spec] as one line of text, to keep between plays ([decode] reads it back). */
        fun encode(spec: MediaSpec): String = listOf(
            spec.videoCodec.orEmpty(), spec.profile.orEmpty(), spec.width, spec.height, spec.fps, spec.bitDepth,
            spec.hdr.orEmpty(), spec.videoBps, spec.videoHow.name, spec.audioCodec.orEmpty(), if (spec.atmos) 1 else 0,
            spec.channels, spec.sampleRate, spec.audioBps, spec.audioHow.name, spec.overallBps, spec.sizeBytes,
            spec.measuredSecs,
        ).joinToString(SEP) { it.toString().replace(SEP, " ") }

        fun decode(text: String): MediaSpec? {
            val f = text.split(SEP)
            if (f.size < 17) return null
            fun s(i: Int) = f[i].ifEmpty { null }
            fun l(i: Int) = f[i].toLongOrNull() ?: 0L
            fun how(i: Int) = runCatching { How.valueOf(f[i]) }.getOrDefault(How.NONE)
            return MediaSpec(
                videoCodec = s(0), profile = s(1), width = l(2).toInt(), height = l(3).toInt(),
                fps = f[4].toFloatOrNull() ?: 0f, bitDepth = l(5).toInt(), hdr = s(6), videoBps = l(7), videoHow = how(8),
                audioCodec = s(9), atmos = f[10] == "1", channels = l(11).toInt(), sampleRate = l(12).toInt(),
                audioBps = l(13), audioHow = how(14), overallBps = l(15), sizeBytes = l(16),
                measuredSecs = if (f.size > 17) l(17) else 0L,
            )
        }

        /**
         * What was measured of a copy before ([kept]) and in this play ([now]), as one: each rate weighted by how much
         * film it was counted over, so a copy's figure firms up every time it's played instead of following one scene.
         */
        fun merged(kept: MediaSpec?, now: MediaSpec): MediaSpec {
            if (kept == null || kept.measuredSecs <= 0 || now.measuredSecs <= 0) return now
            val a = kept.measuredSecs
            val b = now.measuredSecs
            fun mix(x: Long, y: Long) = if (x > 0 && y > 0) (x * a + y * b) / (a + b) else maxOf(x, y)
            return now.copy(
                videoBps = mix(kept.videoBps, now.videoBps),
                audioBps = mix(kept.audioBps, now.audioBps),
                measuredSecs = (a + b).coerceAtMost(MAX_MEASURED_SECS),
            )
        }

        /** Past this much film, older plays count as much as new ones never more. */
        private const val MAX_MEASURED_SECS = 4 * 3600L

        private const val SEP = "|"

        /** "19.8 Mb/s", "850 kb/s". */
        fun mbps(bps: Long): String = when {
            bps >= 10_000_000 -> String.format(Locale.US, "%.0f Mb/s", bps / 1e6)
            bps >= 1_000_000 -> String.format(Locale.US, "%.1f Mb/s", bps / 1e6)
            else -> "${(bps / 1000.0).roundToInt()} kb/s"
        }

        /** "6.57 GB", "870 MB". */
        fun size(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> String.format(Locale.US, "%.2f GB", bytes / 1e9)
            else -> "${(bytes / 1e6).roundToInt()} MB"
        }

        fun channelsLabel(channels: Int): String? = when (channels) {
            1 -> "Mono"
            2 -> "Stereo"
            3 -> "2.1"
            6 -> "5.1"
            7 -> "6.1"
            8 -> "7.1"
            in 4..16 -> "$channels ch"
            else -> null
        }

        /** The picture and sound tracks playing, as the file declares them (no bitrates: those are measured). */
        @OptIn(UnstableApi::class)
        fun of(video: Format?, audio: Format?): MediaSpec {
            val v = video
            val codecs = v?.codecs
            val mime = v?.sampleMimeType
            val color = v?.colorInfo
            val depth = color?.lumaBitdepth?.takeIf { it in 8..16 } ?: bitDepthIn(codecs)
            val dv = mime == MimeTypes.VIDEO_DOLBY_VISION || codecs?.let { DV_CODECS.containsMatchIn(it) } == true
            val hdr = when {
                dv -> "Dolby Vision"
                color?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
                color?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
                color?.colorTransfer == C.COLOR_TRANSFER_SDR -> "SDR"
                else -> null
            }
            val a = audio
            val label = a?.label.orEmpty()
            val audioMime = a?.sampleMimeType
            return MediaSpec(
                videoCodec = videoName(mime, codecs),
                profile = profileIn(codecs),
                width = v?.width?.takeIf { it > 0 } ?: 0,
                height = v?.height?.takeIf { it > 0 } ?: 0,
                fps = v?.frameRate?.takeIf { it > 0 } ?: 0f,
                bitDepth = depth,
                hdr = hdr,
                videoBps = v?.averageBitrate?.takeIf { it > 0 }?.toLong() ?: 0,
                videoHow = if ((v?.averageBitrate ?: 0) > 0) How.STATED else How.NONE,
                audioCodec = audioName(audioMime, a?.codecs, label),
                atmos = audioMime == MimeTypes.AUDIO_E_AC3_JOC || label.contains("atmos", ignoreCase = true),
                channels = a?.channelCount?.takeIf { it > 0 } ?: 0,
                sampleRate = a?.sampleRate?.takeIf { it > 0 } ?: 0,
                audioBps = a?.averageBitrate?.takeIf { it > 0 }?.toLong() ?: a?.bitrate?.takeIf { it > 0 }?.toLong() ?: 0,
                audioHow = if ((a?.averageBitrate ?: 0) > 0 || (a?.bitrate ?: 0) > 0) How.STATED else How.NONE,
            )
        }

        private val DV_CODECS = Regex("""^(dvhe|dvh1|dvav|dva1|dav1)\.""")

        fun videoName(mime: String?, codecs: String?): String? = when {
            mime == MimeTypes.VIDEO_H265 -> "HEVC"
            mime == MimeTypes.VIDEO_H264 -> "H.264"
            mime == MimeTypes.VIDEO_AV1 -> "AV1"
            mime == MimeTypes.VIDEO_VP9 -> "VP9"
            mime == MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
            mime == MimeTypes.VIDEO_VC1 -> "VC-1"
            // Dolby Vision rides on HEVC (dvhe/dvh1), AVC (dvav/dva1) or AV1 (dav1).
            mime == MimeTypes.VIDEO_DOLBY_VISION -> when {
                codecs == null -> null
                codecs.startsWith("dvav") || codecs.startsWith("dva1") -> "H.264"
                codecs.startsWith("dav1") -> "AV1"
                else -> "HEVC"
            }
            else -> null
        }

        /** "Main 10" from "hvc1.2.4.L153.B0", "High" from "avc1.640028". */
        fun profileIn(codecs: String?): String? {
            val c = codecs ?: return null
            val parts = c.split('.')
            return when {
                c.startsWith("hvc1") || c.startsWith("hev1") -> when (parts.getOrNull(1)?.trimStart('A', 'B', 'C')) {
                    "1" -> "Main"
                    "2" -> "Main 10"
                    "4" -> "Range extensions"
                    else -> null
                }
                c.startsWith("avc1") || c.startsWith("avc3") -> when (parts.getOrNull(1)?.take(2)?.uppercase()) {
                    "42" -> "Baseline"
                    "4D" -> "Main"
                    "64" -> "High"
                    "6E" -> "High 10"
                    else -> null
                }
                else -> null
            }
        }

        /** The bit depth a codec string gives away: HEVC Main 10, AVC High 10, AV1's own field. 0 when it doesn't. */
        fun bitDepthIn(codecs: String?): Int {
            val c = codecs ?: return 0
            val parts = c.split('.')
            return when {
                c.startsWith("hvc1") || c.startsWith("hev1") -> when (parts.getOrNull(1)?.trimStart('A', 'B', 'C')) {
                    "1" -> 8
                    "2" -> 10
                    else -> 0
                }
                c.startsWith("av01") -> parts.getOrNull(3)?.toIntOrNull()?.takeIf { it in 8..12 } ?: 0
                c.startsWith("avc1") || c.startsWith("avc3") -> if (parts.getOrNull(1)?.take(2)?.uppercase() == "6E") 10 else 0
                // Dolby Vision profiles 5, 7 and 8 are 10-bit.
                c.startsWith("dvhe") || c.startsWith("dvh1") -> if (parts.getOrNull(1)?.toIntOrNull() in setOf(5, 7, 8)) 10 else 0
                else -> 0
            }
        }

        fun audioName(mime: String?, codecs: String?, label: String): String? {
            val l = label.lowercase(Locale.ROOT)
            return when (mime) {
                MimeTypes.AUDIO_TRUEHD -> "TrueHD"
                MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "DD+"
                MimeTypes.AUDIO_AC3 -> "DD"
                MimeTypes.AUDIO_AC4 -> "AC-4"
                // DTS-HD covers both High Resolution and Master Audio: only a track's name tells them apart.
                MimeTypes.AUDIO_DTS_HD -> if ("ma" in l.split(' ', '-', '.') || "master" in l) "DTS-HD MA" else "DTS-HD"
                MimeTypes.AUDIO_DTS_X -> "DTS:X"
                MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
                MimeTypes.AUDIO_DTS -> "DTS"
                MimeTypes.AUDIO_AAC -> "AAC"
                MimeTypes.AUDIO_FLAC -> "FLAC"
                MimeTypes.AUDIO_OPUS -> "Opus"
                MimeTypes.AUDIO_VORBIS -> "Vorbis"
                MimeTypes.AUDIO_MPEG -> "MP3"
                MimeTypes.AUDIO_RAW -> "PCM"
                null -> null
                else -> codecs?.takeIf { it.isNotEmpty() }?.uppercase(Locale.ROOT)
            }
        }

        /**
         * A copy as the provider's probe of the file describes it (its get_vod_info or episode "info"): [video] and
         * [audio] are its streams' fields, flattened ("codec_name", "tags.BPS", "side_data" for the kinds of side
         * data), [overallKbps] the whole file's rate in kb/s.
         */
        fun fromProvider(video: Map<String, String>, audio: Map<String, String>, overallKbps: Long): MediaSpec {
            fun Map<String, String>.long(vararg keys: String): Long =
                keys.firstNotNullOfOrNull { k -> this[k]?.trim()?.toLongOrNull()?.takeIf { it > 0 } } ?: 0L
            val vCodec = when (video["codec_name"]?.lowercase(Locale.ROOT)) {
                "hevc", "h265" -> "HEVC"
                "h264", "avc" -> "H.264"
                "av1" -> "AV1"
                "vp9" -> "VP9"
                "mpeg2video" -> "MPEG-2"
                "vc1" -> "VC-1"
                else -> null
            }
            val pix = video["pix_fmt"].orEmpty()
            val depth = video.long("bits_per_raw_sample").toInt().takeIf { it in 8..16 }
                ?: when {
                    pix.contains("p12") -> 12
                    pix.contains("p10") -> 10
                    pix.startsWith("yuv") || pix.startsWith("nv12") -> 8
                    else -> 0
                }
            val transfer = video["color_transfer"].orEmpty()
            val side = video["side_data"].orEmpty().lowercase(Locale.ROOT)
            val hdr = when {
                side.contains("dovi") || side.contains("dolby vision") -> "Dolby Vision"
                side.contains("hdr10+") || side.contains("hdr dynamic metadata smpte2094-40") -> "HDR10+"
                transfer == "smpte2084" -> "HDR10"
                transfer == "arib-std-b67" -> "HLG"
                transfer == "bt709" || transfer == "smpte170m" || transfer == "bt470bg" -> "SDR"
                else -> null
            }
            val (aCodec, aAtmos) = providerAudio(audio)
            val audioStated = audio.long("tags.BPS", "tags.BPS-eng", "bit_rate")
            val videoStated = video.long("tags.BPS", "tags.BPS-eng", "bit_rate")
            val overall = overallKbps * 1000
            val (videoBps, videoHow) = when {
                videoStated > 0 -> videoStated to How.STATED
                overall > 0 && audioStated in 1 until overall -> (overall - audioStated) to How.ESTIMATED
                else -> 0L to How.NONE
            }
            return MediaSpec(
                videoCodec = vCodec,
                profile = video["profile"]?.takeIf { it.isNotBlank() },
                width = video.long("width").toInt(),
                height = video.long("height").toInt(),
                fps = rate(video["avg_frame_rate"]) ?: rate(video["r_frame_rate"]) ?: 0f,
                bitDepth = depth,
                hdr = hdr,
                videoBps = videoBps,
                videoHow = videoHow,
                audioCodec = aCodec,
                atmos = aAtmos,
                channels = audio.long("channels").toInt(),
                sampleRate = audio.long("sample_rate").toInt(),
                audioBps = audioStated,
                audioHow = if (audioStated > 0) How.STATED else How.NONE,
                overallBps = overall,
            )
        }

        private fun providerAudio(audio: Map<String, String>): Pair<String?, Boolean> {
            val profile = audio["profile"].orEmpty()
            val title = audio["tags.title"].orEmpty()
            val atmos = profile.contains("atmos", true) || title.contains("atmos", true)
            val name = when (audio["codec_name"]?.lowercase(Locale.ROOT)) {
                "truehd" -> "TrueHD"
                "eac3" -> "DD+"
                "ac3" -> "DD"
                "ac4" -> "AC-4"
                "dts" -> when {
                    profile.contains("MA", false) -> "DTS-HD MA"
                    profile.contains("DTS:X", true) -> "DTS:X"
                    profile.contains("HD", false) -> "DTS-HD"
                    else -> "DTS"
                }
                "aac" -> "AAC"
                "flac" -> "FLAC"
                "opus" -> "Opus"
                "vorbis" -> "Vorbis"
                "mp3" -> "MP3"
                null, "" -> null
                else -> if (audio["codec_name"].orEmpty().startsWith("pcm")) "PCM" else audio["codec_name"]?.uppercase(Locale.ROOT)
            }
            return name to atmos
        }

        /** "24000/1001" → 23.976. */
        private fun rate(text: String?): Float? {
            val t = text?.trim() ?: return null
            val parts = t.split('/')
            val n = parts[0].toDoubleOrNull() ?: return null
            val d = parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0
            if (n <= 0 || d <= 0) return null
            return (n / d).toFloat().takeIf { it in 1f..300f }
        }
    }
}
