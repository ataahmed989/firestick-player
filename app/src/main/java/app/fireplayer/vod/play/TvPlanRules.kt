package app.fireplayer.vod.play

import app.fireplayer.vod.Hdr
import app.fireplayer.vod.SoundFormat
import app.fireplayer.vod.TvCapabilities
import app.fireplayer.vod.TvPlan

/**
 * Settings › This TV's words and choices (design-brief.md §6.5, "Best source per TV"), pure: what the TV reported, and
 * the custom plan's HDR orders, top resolutions and data-rate limits.
 */
object TvPlanRules {

    /** An HDR order the custom plan offers: its words and its tiers (each tier equal), best first. */
    data class HdrOrder(val label: String, val tiers: List<Set<Hdr>>)

    val HDR_ORDERS = listOf(
        HdrOrder("HDR10 and HDR10+ first, then HLG", listOf(setOf(Hdr.HDR10, Hdr.HDR10_PLUS), setOf(Hdr.HLG))),
        HdrOrder("HDR10+ first, then HDR10, then HLG", listOf(setOf(Hdr.HDR10_PLUS), setOf(Hdr.HDR10), setOf(Hdr.HLG))),
        HdrOrder(
            "Dolby Vision first, then HDR10+, HDR10, HLG",
            listOf(setOf(Hdr.DOLBY_VISION), setOf(Hdr.HDR10_PLUS), setOf(Hdr.HDR10), setOf(Hdr.HLG)),
        ),
        HdrOrder("SDR first (HDR is off on this TV)", emptyList()),
    )

    val TOP_HEIGHTS = listOf(2160 to "4K", 1080 to "1080p", 720 to "720p")

    /** Data-rate limits offered (Mb/s); null is none. Never a file size (§6.5). */
    val MAX_MBPS = listOf<Int?>(null, 20, 40, 60, 80)

    fun mbpsLabel(v: Int?): String = if (v == null) "No limit" else "$v Mb/s"

    /** The index of [plan]'s HDR order among [HDR_ORDERS]; -1 when it's another (an automatic plan's). */
    fun hdrIndex(plan: TvPlan): Int = HDR_ORDERS.indexOfFirst { it.tiers == plan.hdrOrder }

    /** A plan's HDR order in words: "HDR10 = HDR10+, then HLG"; "SDR first" when it has none. */
    fun hdrWords(tiers: List<Set<Hdr>>): String =
        if (tiers.isEmpty()) "SDR first" else tiers.joinToString(", then ") { tier -> Hdr.entries.filter { it in tier }.joinToString(" = ") { name(it) } }

    /** An HDR kind's full name, as TVs say it ("HDR10", not the badge's "HDR"). */
    fun name(h: Hdr): String = when (h) {
        Hdr.DOLBY_VISION -> "Dolby Vision"
        Hdr.HDR10_PLUS -> "HDR10+"
        Hdr.HDR10 -> "HDR10"
        Hdr.HLG -> "HLG"
    }

    /** "HDR10, HDR10+ and HLG" or "None reported (SDR, or HDR off on this input)". */
    fun hdrReport(c: TvCapabilities?): String {
        if (c == null) return "Not checked yet"
        val kinds = Hdr.entries.filter { it in c.hdr }.map { name(it) }
        return if (kinds.isEmpty()) "None reported (SDR, or HDR off on this input)" else words(kinds)
    }

    /** "3840×2160 (4K)". */
    fun pictureReport(c: TvCapabilities?): String {
        if (c == null) return "Not checked yet"
        val name = when {
            c.maxHeight >= 2160 -> " (4K)"
            c.maxHeight >= 1080 -> " (1080p)"
            c.maxHeight >= 720 -> " (720p)"
            else -> ""
        }
        return "${c.maxWidth}×${c.maxHeight}$name"
    }

    /** Under Settings › This TV › IMAX first: always. */
    const val IMAX_NOTE = "Off: an IMAX copy goes first when it is as good as the best in picture, HDR and source. " +
        "On: a 4K IMAX copy this TV plays is always Best."

    /** Settings › This TV › HDR10+ reaches this TV ([app.fireplayer.vod.ThisTv.hdr10PlusReaches]). */
    const val HDR10_PLUS_NOTE = "On: HDR10+ copies send their HDR10+ to this TV and are preferred. Off: the TV gets " +
        "their steady HDR10 (turn it off if the picture flashes or pumps). HDR10+ added to a Dolby Vision copy is never sent."

    /**
     * What Automatic does with Dolby Vision on this TV ([TvPlan.dv7AsHdr10]): on a TV without Dolby Vision a file counts
     * as the HDR10 or HDR10+ picture inside it.
     */
    fun dolbyVisionNote(plan: TvPlan): String {
        val tvDv = plan.hdrOrder.any { Hdr.DOLBY_VISION in it }
        val seven = when (plan.dv7AsHdr10) {
            true -> "profile 7 plays as its HDR10 picture on this Stick"
            false -> "profile 7 goes to this Stick's Dolby Vision decoder"
            null -> "profile 7 as this Stick plays it (checked with Check again)"
        }
        return if (tvDv) "Automatic follows what the TV reports: Dolby Vision first; $seven."
        else "Automatic follows what the TV reports. Without Dolby Vision on this TV, a Dolby Vision file counts as the HDR10 or " +
            "HDR10+ picture inside it: profile 8 always, $seven. Profile 5 comes last."
    }

    /** "Dolby Digital, Dolby Digital Plus" or "PCM and AAC only". */
    fun soundReport(formats: Set<SoundFormat>?): String {
        if (formats == null) return "Not checked yet"
        val names = SoundFormat.entries.filter { it in formats }.map { it.label }
        return if (names.isEmpty()) "PCM and AAC only" else words(names)
    }

    /** [plan] with [f] taken in or out of its sound formats. */
    fun toggleSound(plan: TvPlan, f: SoundFormat): TvPlan =
        plan.copy(automatic = false, sound = if (f in plan.sound) plan.sound - f else plan.sound + f)

    private fun words(list: List<String>): String = when (list.size) {
        0 -> ""
        1 -> list[0]
        else -> list.dropLast(1).joinToString(", ") + " and " + list.last()
    }
}
