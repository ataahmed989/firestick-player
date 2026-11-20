package app.fireplayer.player

import java.util.Locale
import kotlin.math.abs

/**
 * Which of a title's copies has the best picture and sound, and why, from what is known of each ([MediaSpec]).
 *
 * The picture decides first, by how much data it gets: for the same codec, more data for the same picture is a
 * cleaner picture, whatever the frame size says (the frame often includes black bars). Between codecs, newer ones
 * need less data for the same picture: a rule of thumb ([EFFICIENCY]) is used then, and the reason says so. HDR comes
 * first when the screen shows it and the HDR copy isn't starved of data. On a screen without HDR it's the other way
 * round: the Fire TV turns HDR into SDR itself (and its decoder works harder for HDR10+), so an SDR copy comes first
 * when it has [HDR_FLOOR] of the best copy's data, and HDR or 10-bit aren't given as reasons there. Copies close in
 * picture data (within [CLOSE]) are told apart by width, bit depth, then sound. A copy nobody measured is listed but
 * never recommended.
 *
 * The data is weighed against the frame it has to fill ([perPixel], bits for each pixel of each frame): a copy with
 * too little for its frame ([STARVED]; a 4K film under about 6 GB) loses to a smaller-framed copy that has enough and
 * about as much data, since a well-fed 1080p looks cleaner than a starved 4K. And data past what a frame can use
 * ([FULL]) doesn't count: a well-fed 4K copy isn't beaten by a 1080p one for the 1080p's bigger file.
 *
 * Before any of that, a copy that plays: one that keeps stopping to load, or loses frames ([CopyHealth.troubled]), is
 * only recommended when every copy known does the same. Failing to play isn't held against a copy. Then the sound wanted
 * ([CopyLanguage.fit]): a copy known to lack it only when no copy is known to have it. Subtitles in the viewer's
 * language only break a tie.
 */
object CopyRank {

    /** How much more picture a codec gets from each bit than H.264 does: a rough, widely used rule of thumb. */
    val EFFICIENCY = mapOf("H.264" to 1.0, "HEVC" to 1.6, "VP9" to 1.5, "AV1" to 2.0, "MPEG-2" to 0.6, "VC-1" to 0.8)

    /** Picture data within this share of each other counts as the same. */
    const val CLOSE = 0.15

    /**
     * An HDR copy wins on a screen that shows HDR when it has at least this share of the best copy's picture data; on a
     * screen that doesn't, an SDR copy wins over an HDR one the same way.
     */
    const val HDR_FLOOR = 0.7

    /**
     * Bits for each pixel of each frame, as HEVC would need them ([perPixel]): under [STARVED] the picture breaks into
     * blocks when it moves; [GOOD] and up is clean; past [FULL] more data shows no more.
     */
    const val STARVED = 0.040
    const val GOOD = 0.080
    const val FULL = 0.120

    /** A 4K copy whose frame isn't known is starved under this: 6 GB over a two-hour film, in bits a second. */
    const val STARVED_4K_BPS = 6_700_000L

    /** A starved copy loses to one that isn't when that one has at least this share of its data. */
    const val FED_SHARE = 0.6

    private const val HEVC = 1.6
    private const val FILM_FPS = 24.0
    private const val WIDE_4K = 3000

    /** [Copies.groupRank] of "4K" and "4K HDR": what a playlist calls 4K. */
    private const val GROUP_4K = 12

    /**
     * How many bits each pixel of each frame gets from [bits] a second of picture, counted as HEVC would need them (so
     * H.264's count for less, AV1's for more): the figure to hold against [STARVED] and [GOOD]. A film's 24 frames a
     * second when the copy doesn't say. Null when the frame's size or the data rate isn't known.
     */
    fun perPixel(s: MediaSpec, bits: Long = if (s.videoBps > 0) s.videoBps else s.overallBps): Double? {
        if (bits <= 0 || s.width <= 0 || s.height <= 0) return null
        val fps = if (s.fps > 0) s.fps.toDouble() else FILM_FPS
        val efficiency = EFFICIENCY[s.videoCodec] ?: if (s.width >= WIDE_4K) HEVC else 1.0
        return bits * (efficiency / HEVC) / (s.width.toDouble() * s.height * fps)
    }

    /** Too little data for its frame; for a 4K copy whose frame isn't known ([group]: its playlist's word for it), for a 4K film. */
    fun starved(s: MediaSpec, group: Int = 0, bits: Long = if (s.videoBps > 0) s.videoBps else s.overallBps): Boolean {
        perPixel(s, bits)?.let { return it < STARVED }
        val is4k = s.width >= WIDE_4K || (s.width == 0 && group >= GROUP_4K)
        return is4k && bits > 0 && bits * ((EFFICIENCY[s.videoCodec] ?: HEVC) / HEVC) < STARVED_4K_BPS
    }

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

    /** [copies] in the order shown; [current] is the one playing; [hdrScreen]: the TV shows HDR; [health]: how each has played. */
    fun rank(
        copies: List<MediaSpec>,
        current: Int,
        hdrScreen: Boolean,
        health: List<CopyHealth?> = emptyList(),
        /** Per copy, [CopyLanguage.fit] for the sound wanted, and [fitName] the language a fit level stands for. */
        fits: List<Int> = emptyList(),
        fitName: (Int) -> String? = { null },
        /** Per copy, whether it has subtitles in the viewer's language. */
        subs: List<Boolean> = emptyList(),
        /** Per copy, the rank of the quality group its playlist puts it in ([Copies.groupRank]): tips a close call. */
        groups: List<Int> = emptyList(),
    ): Verdict {
        fun troubled(i: Int) = health.getOrNull(i)?.troubled == true
        fun fit(i: Int) = fits.getOrNull(i) ?: 1
        fun sub(i: Int) = subs.getOrNull(i) == true
        // The picture's own rate when every copy known has one (an estimate from the whole file's rate doesn't count), else
        // the whole file's for all of them: never one against the other. A copy with neither isn't in the running.
        fun picture(s: MediaSpec) = s.videoBps > 0 && s.videoHow != MediaSpec.How.ESTIMATED
        fun whole(s: MediaSpec) = s.overallBps.takeIf { it > 0 }
            ?: (s.videoBps + s.audioBps).takeIf { s.videoBps > 0 && s.videoHow != MediaSpec.How.ESTIMATED } ?: 0L
        val known = copies.indices.filter { picture(copies[it]) || whole(copies[it]) > 0 }
        if (known.isEmpty()) return Verdict(null, null, emptyList(), emptyList())
        val usePicture = known.all { picture(copies[it]) }
        val all = if (usePicture) known else known.filter { whole(copies[it]) > 0 }
        // Copies that play come first: the others only when nothing else is known. Then the sound wanted.
        val playing = all.filterNot { troubled(it) }.ifEmpty { all }
        val bestFit = playing.maxOf { fit(it) }
        val fitting = playing.filter { fit(it) == bestFit }
        fun bits(i: Int) = if (usePicture) copies[i].videoBps else whole(copies[i])
        val codecs = fitting.mapNotNull { copies[it].videoCodec }.toSet()
        val mixed = codecs.size > 1
        fun raw(i: Int): Double = bits(i) * (if (mixed) EFFICIENCY[copies[i].videoCodec] ?: 1.0 else 1.0)
        // Too little data for its frame: out when a copy that has enough has about as much data (or more).
        fun starved(i: Int) = starved(copies[i], groups.getOrNull(i) ?: 0, bits(i))
        val fed = fitting.filterNot { starved(it) }
        val measured = fitting.filter { s -> !starved(s) || fed.none { raw(it) >= raw(s) * FED_SHARE } }
        // Data past what the frame can use shows no more, so it doesn't count.
        fun effective(i: Int): Double {
            val each = perPixel(copies[i], bits(i)) ?: return raw(i)
            return if (each > FULL) raw(i) * FULL / each else raw(i)
        }

        val byData = measured.sortedWith(compareByDescending<Int> { effective(it) }.thenByDescending { raw(it) })
        var best = byData.first()
        // Close in data: width, bit depth and sound decide.
        val close = byData.filter { effective(it) >= effective(best) * (1 - CLOSE) }
        if (close.size > 1) {
            best = close.sortedWith(
                compareByDescending<Int> { copies[it].width }
                    .thenByDescending { groups.getOrNull(it) ?: 0 }
                    .thenByDescending { copies[it].bitDepth }
                    .thenByDescending { soundRank(copies[it]) }
                    .thenByDescending { copies[it].channels }
                    .thenByDescending { sub(it) }
                    .thenByDescending { effective(it) }
                    .thenByDescending { raw(it) },
            ).first()
        }
        if (hdrScreen && !isHdr(copies[best])) {
            measured.filter { isHdr(copies[it]) && effective(it) >= effective(best) * HDR_FLOOR }
                .maxByOrNull { effective(it) }?.let { best = it }
        }
        // The mirror of that on a screen without HDR: the Fire TV would turn the HDR copy into SDR. Only a copy known to
        // be SDR: one whose file doesn't say may be HDR too.
        if (!hdrScreen && isHdr(copies[best])) {
            measured.filter { copies[it].hdr == "SDR" && effective(it) >= effective(best) * HDR_FLOOR }
                .maxByOrNull { effective(it) }?.let { best = it }
        }
        val others = all.sortedByDescending { effective(it) }
        val against = if (best != current && current in copies.indices) current
            else others.firstOrNull { it != best }
        if (against == null) {
            return Verdict(best, null, listOfNotNull("the only copy with its data rate known", health.getOrNull(best)?.summary()), emptyList())
        }
        val (reasons, costs) = compare(copies[best], copies[against], bits(best), bits(against), usePicture, mixed, hdrScreen)
        // The frame each has to fill: said when it decided, ahead of the data's own figures unless those already say it.
        val lessData = raw(best) < raw(against) * (1 + CLOSE)
        val framed = when {
            starved(against) && !starved(best) -> starvedNote(copies[against], bits(against))
            lessData && effective(best) > effective(against) -> fullNote(copies[against], bits(against))
            else -> null
        }
        if (framed != null) reasons.add(if (lessData) 0 else minOf(1, reasons.size), framed)
        if (starved(best)) costs.add(0, "little data for its frame" + (perPixel(copies[best], bits(best))?.let { " (${each(it)})" } ?: ""))
        // How they played outweighs what they're made of, so it's said first.
        val mine = health.getOrNull(best)
        val theirs = health.getOrNull(against)
        val played = ArrayList<String>()
        if (troubled(against) && !troubled(best)) played += "the other ${theirs?.summary()}"
        // Then the sound: the language wanted, where the other doesn't have it (or may not).
        if (fit(best) > fit(against)) fitName(fit(best))?.let { played += "$it sound" }
        if (fit(best) < fit(against)) fitName(fit(against))?.let { costs.add(0, "may not have $it sound") }
        if (mine != null && !mine.troubled) mine.summary()?.takeIf { it.startsWith("smooth") }?.let { played += it }
        if (sub(best) && !sub(against) && subs.isNotEmpty()) reasons += "subtitles in your language"
        // Not known to have them (a copy not played yet only says what its picture and sound are)
        if (!sub(best) && sub(against) && subs.isNotEmpty()) costs += "may not have subtitles in your language"
        if (troubled(best)) mine?.summary()?.let { costs.add(0, it) }
        else if ((mine?.skips ?: 0) > 0) costs += "it skips damaged parts"
        return Verdict(best, against, played + reasons, costs)
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
        hdrScreen: Boolean,
    ): Pair<MutableList<String>, MutableList<String>> {
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
        if (hdrScreen) {
            // Only what's known on both sides: a file that doesn't say isn't taken for SDR.
            if (isHdr(a) && b.hdr == "SDR") reasons += "${a.hdr} instead of SDR"
            if (isHdr(a) && b.hdr == null) reasons += "${a.hdr} (the other doesn't say)"
            if (a.hdr == "SDR" && isHdr(b)) costs += "SDR instead of ${b.hdr}"
            if (a.hdr == null && isHdr(b)) costs += "no HDR stated, where the other is ${b.hdr}"
            if (a.bitDepth > b.bitDepth && b.bitDepth > 0) reasons += "${a.bitDepth}-bit instead of ${b.bitDepth}-bit"
            if (a.bitDepth in 1 until b.bitDepth) costs += "${a.bitDepth}-bit instead of ${b.bitDepth}-bit"
        } else {
            // The TV reports no HDR: HDR (and the 10 bits it comes in) is turned into SDR on the Fire TV, so neither is a
            // reason to pick a copy. What's said instead is which one the TV shows as it was made.
            if (a.hdr == "SDR" && isHdr(b)) reasons += "SDR, as this TV shows it (the other's ${b.hdr} is turned into SDR)"
            if (isHdr(a) && !isHdr(b)) costs += "${a.hdr} shown as SDR on this TV"
        }
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

    /** "0.031 bits a pixel". */
    fun each(perPixel: Double): String = String.format(Locale.US, "%.3f bits a pixel", perPixel)

    private fun frame(s: MediaSpec) = if (s.width >= WIDE_4K) "4K" else if (s.width > 0) "${s.width}-wide" else "4K"

    /** "the other's 4K picture is starved of data (6.0 Mb/s, 0.030 bits a pixel)". */
    private fun starvedNote(s: MediaSpec, bits: Long): String =
        "the other's ${frame(s)} picture is starved of data (" +
            listOfNotNull(MediaSpec.mbps(bits), perPixel(s, bits)?.let { each(it) }).joinToString(", ") + ")"

    /** "the other's 1920-wide picture can't use all its data". */
    private fun fullNote(s: MediaSpec, bits: Long): String? =
        perPixel(s, bits)?.takeIf { it > FULL }?.let { "the other's ${frame(s)} picture has more data than it can show (${each(it)})" }

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
