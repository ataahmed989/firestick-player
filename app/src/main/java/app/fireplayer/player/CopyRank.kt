package app.fireplayer.player

import java.util.Locale
import kotlin.math.abs

/**
 * Which of a title's copies has the best picture and sound, and why, from what is known of each ([MediaSpec]).
 *
 * The picture decides first, by how much data it gets: for the same codec, more data for the same picture is a
 * cleaner picture, whatever the frame size says (the frame often includes black bars). Between codecs, newer ones
 * need less data for the same picture: a rule of thumb ([EFFICIENCY]) is used then, and the reason says so. HDR comes
 * first when the screen shows it and the HDR copy isn't starved of data. Copies close in picture data (within
 * [CLOSE]) are told apart by width, bit depth, then sound. A copy nobody measured is listed but never recommended.
 */
object CopyRank {

    /** How much more picture a codec gets from each bit than H.264 does: a rough, widely used rule of thumb. */
    val EFFICIENCY = mapOf("H.264" to 1.0, "HEVC" to 1.6, "VP9" to 1.5, "AV1" to 2.0, "MPEG-2" to 0.6, "VC-1" to 0.8)

    /** Picture data within this share of each other counts as the same. */
    const val CLOSE = 0.15

    /** An HDR copy wins on a screen that shows HDR when it has at least this share of the best copy's picture data. */
    const val HDR_FLOOR = 0.7

    class Verdict(
        /** The copy recommended, or null when none can be (nothing measured, or too close to call). */
        val best: Int?,
        /** What it was compared with: the copy playing, or else the next best. */
        val against: Int?,
        /** Why, short and in order of weight: "3.3× the picture data (19.8 vs 6.0 Mb/s)", "5.1 sound instead of stereo". */
        val reasons: List<String>,
        /** What the recommended copy gives up, if anything: "stereo sound instead of 5.1". */
        val tradeOffs: List<String>,
    )

    /** [copies] in the order shown; [current] is the one playing; [hdrScreen]: the TV shows HDR. */
    fun rank(copies: List<MediaSpec>, current: Int, hdrScreen: Boolean): Verdict {
        // The picture's own rate when every measured copy has one, else the whole file's: never one against the other.
        val measured = copies.indices.filter { copies[it].videoBps > 0 || copies[it].overallBps > 0 }
        if (measured.isEmpty()) return Verdict(null, null, emptyList(), emptyList())
        val usePicture = measured.all { copies[it].videoBps > 0 }
        fun bits(i: Int) = if (usePicture) copies[i].videoBps else copies[i].overallBps
        val codecs = measured.mapNotNull { copies[it].videoCodec }.toSet()
        val mixed = codecs.size > 1
        fun effective(i: Int): Double = bits(i) * (if (mixed) EFFICIENCY[copies[i].videoCodec] ?: 1.0 else 1.0)

        val byData = measured.sortedByDescending { effective(it) }
        var best = byData.first()
        // Close in data: width, bit depth and sound decide.
        val close = byData.filter { effective(it) >= effective(best) * (1 - CLOSE) }
        if (close.size > 1) {
            best = close.sortedWith(
                compareByDescending<Int> { copies[it].width }
                    .thenByDescending { copies[it].bitDepth }
                    .thenByDescending { soundRank(copies[it]) }
                    .thenByDescending { copies[it].channels }
                    .thenByDescending { effective(it) },
            ).first()
        }
        if (hdrScreen && !isHdr(copies[best])) {
            measured.filter { isHdr(copies[it]) && effective(it) >= effective(best) * HDR_FLOOR }
                .maxByOrNull { effective(it) }?.let { best = it }
        }
        if (measured.size == 1) {
            return Verdict(best, null, listOf("the only copy with its data rate known"), emptyList())
        }
        val against = if (best != current && current in copies.indices) current
            else byData.firstOrNull { it != best } ?: return Verdict(best, null, emptyList(), emptyList())
        val (reasons, costs) = compare(copies[best], copies[against], bits(best), bits(against), usePicture, mixed)
        return Verdict(best, against, reasons, costs)
    }

    private fun isHdr(s: MediaSpec) = s.hdr != null && s.hdr != "SDR"

    /** Lossless sound over the best lossy, over the usual lossy, over plain stereo codecs. */
    fun soundRank(s: MediaSpec): Int = when (s.audioCodec) {
        "TrueHD", "DTS-HD MA", "DTS:X", "FLAC", "PCM" -> 4
        "DD+" -> if (s.atmos) 3 else 2
        "DTS-HD", "DTS", "DD", "AC-4" -> 2
        null -> 0
        else -> 1
    }

    private fun compare(
        a: MediaSpec,
        b: MediaSpec,
        aBits: Long,
        bBits: Long,
        picture: Boolean,
        mixed: Boolean,
    ): Pair<List<String>, List<String>> {
        val reasons = ArrayList<String>()
        val costs = ArrayList<String>()
        val what = if (picture) "picture data" else "data (whole file)"
        if (aBits > 0 && bBits > 0) {
            val ratio = aBits.toDouble() / bBits
            val numbers = "${MediaSpec.mbps(aBits)} vs ${MediaSpec.mbps(bBits)}"
            if (mixed && a.videoCodec != b.videoCodec) {
                reasons += "${a.videoCodec} at ${MediaSpec.mbps(aBits)} vs ${b.videoCodec} at ${MediaSpec.mbps(bBits)}" +
                    (codecNote(a.videoCodec, b.videoCodec)?.let { " ($it)" } ?: "")
            } else if (ratio >= 1 + CLOSE) {
                reasons += "${more(ratio)} $what ($numbers)"
            } else if (ratio <= 1 - CLOSE) {
                costs += "less $what ($numbers)"
            } else {
                reasons += "about the same $what ($numbers)"
            }
        }
        // Only what's known on both sides: a file that doesn't say isn't taken for SDR.
        if (isHdr(a) && b.hdr == "SDR") reasons += "${a.hdr} instead of SDR"
        if (isHdr(a) && b.hdr == null) reasons += "${a.hdr} (the other doesn't say)"
        if (a.hdr == "SDR" && isHdr(b)) costs += "SDR instead of ${b.hdr}"
        if (a.hdr == null && isHdr(b)) costs += "no HDR stated, where the other is ${b.hdr}"
        if (a.bitDepth > b.bitDepth && b.bitDepth > 0) reasons += "${a.bitDepth}-bit instead of ${b.bitDepth}-bit"
        if (a.bitDepth in 1 until b.bitDepth) costs += "${a.bitDepth}-bit instead of ${b.bitDepth}-bit"
        if (a.width > 0 && b.width > 0 && abs(a.width - b.width) >= 320) {
            if (a.width > b.width) reasons += "${a.width} wide instead of ${b.width}" else costs += "${a.width} wide instead of ${b.width}"
        }
        val aSound = a.soundLine()
        val bSound = b.soundLine()
        val sound = compareValues(soundRank(a) * 100 + a.channels, soundRank(b) * 100 + b.channels)
        if (aSound != null && bSound != null && aSound != bSound) {
            if (sound > 0) reasons += "$aSound sound instead of $bSound" else if (sound < 0) costs += "$aSound sound instead of $bSound"
        }
        return reasons to costs
    }

    private fun codecNote(a: String?, b: String?): String? {
        val ea = EFFICIENCY[a] ?: return null
        val eb = EFFICIENCY[b] ?: return null
        if (ea == eb) return null
        val pct = ((1 - eb / ea) * 100).toInt()
        return if (pct > 0) "$a needs roughly $pct% less data than $b for the same picture"
            else "$b needs roughly ${-((1 - ea / eb) * 100).toInt()}% less data than $a for the same picture"
    }

    /** "3.3× the", "40% more". */
    private fun more(ratio: Double): String =
        if (ratio >= 2) String.format(Locale.US, "%.1f× the", ratio) else "${((ratio - 1) * 100).toInt()}% more"
}
