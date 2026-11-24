package app.fireplayer.vod.ui

import android.text.format.DateFormat
import app.fireplayer.ui.SettingsPanels
import app.fireplayer.ui.SidePanel
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.SoundFormat
import app.fireplayer.vod.ThisTv
import app.fireplayer.vod.play.TvPlanRules

/**
 * Settings › This TV (design-brief.md §6.5, "Best source per TV"): what this Stick read from its TV (HDR kinds including
 * Dolby Vision, the largest picture, the sound it can pass through), Check again, and how "Best for this TV" ranks:
 * Automatic (from the reading) or a custom plan (HDR order, top resolution, sound formats, an optional data-rate limit).
 * Made of the settings window's shared rows.
 */
object ThisTvSettings {

    private var checking = false

    fun render(panel: SidePanel, tv: ThisTv): Unit = with(panel) {
        val caps = tv.capabilities()
        header("This TV")
        row("HDR", TvPlanRules.hdrReport(caps)) {}
        row("Largest picture", TvPlanRules.pictureReport(caps)) {}
        row("Sound passed through", TvPlanRules.soundReport(caps?.passthrough)) {}
        caps?.let { c -> row("Checked", DateFormat.format("MMM d, HH:mm", c.checkedAt).toString()) {} }
        val check = row("Check again", if (checking) "Checking…" else null) {
            if (checking) return@row
            checking = true
            refresh()
            tv.checkAgain {
                checking = false
                if (isOpen) refresh()
            }
        }
        if (checking) dim(check)
        message("HDR needs to be on for this TV's HDMI input (on Samsung TVs: Input Signal Plus). Check again after changing it.")

        val plan = tv.plan()
        header("Best for this TV")
        choice("Plan", listOf("Automatic", "Custom"), if (plan.automatic) 0 else 1) { i ->
            if (i == 0) tv.useAutomatic() else tv.setPlan(plan.copy(automatic = false))
        }
        row("IMAX first: always", SettingsPanels.onOff(tv.imaxAlways())) {
            tv.setImaxAlways(!tv.imaxAlways())
            refresh()
        }
        message(TvPlanRules.IMAX_NOTE)
        if (caps?.hdr?.contains(Hdr.HDR10_PLUS) == true) {
            row("HDR10+ reaches this TV", SettingsPanels.onOff(tv.hdr10PlusReaches())) {
                tv.setHdr10PlusReaches(!tv.hdr10PlusReaches())
                refresh()
            }
            message(TvPlanRules.HDR10_PLUS_NOTE)
        }
        if (plan.automatic) {
            row("HDR order", TvPlanRules.hdrWords(plan.hdrOrder)) {}
            message(TvPlanRules.dolbyVisionNote(plan))
            return@with
        }
        val orders = TvPlanRules.HDR_ORDERS
        val hdrAt = TvPlanRules.hdrIndex(plan)
        choice("HDR order", orders.map { it.label }, hdrAt.coerceAtLeast(0), valueText = TvPlanRules.hdrWords(plan.hdrOrder)) { i ->
            tv.setPlan(plan.copy(automatic = false, hdrOrder = orders[i].tiers))
        }
        val heights = TvPlanRules.TOP_HEIGHTS
        choice("Top resolution", heights.map { it.second }, heights.indexOfFirst { it.first == plan.topHeight }.coerceAtLeast(0)) { i ->
            tv.setPlan(plan.copy(automatic = false, topHeight = heights[i].first))
        }
        val rates = TvPlanRules.MAX_MBPS
        choice("Data-rate limit", rates.map { TvPlanRules.mbpsLabel(it) }, rates.indexOf(plan.maxMbps).coerceAtLeast(0)) { i ->
            tv.setPlan(plan.copy(automatic = false, maxMbps = rates[i]))
        }
        header("Sound formats")
        for (f in SoundFormat.entries) {
            row(f.label, SettingsPanels.onOff(f in plan.sound)) {
                tv.setPlan(TvPlanRules.toggleSound(tv.plan(), f))
                refresh()
            }
        }
        message("Sound formats your TV or receiver takes as they are. Others are decoded on the Stick.")
    }
}
