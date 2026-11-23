package app.fireplayer.vod.ui

import android.app.Activity
import android.text.InputType
import app.fireplayer.data.CredentialChecks
import app.fireplayer.ui.FireDialog
import app.fireplayer.ui.SettingsPanels
import app.fireplayer.ui.SidePanel
import app.fireplayer.vod.Addon
import app.fireplayer.vod.AddonSave
import app.fireplayer.vod.AddonSettings
import app.fireplayer.vod.sources.AddonChecks

/**
 * Settings › Add-ons (design-brief.md §6.1): the Torrentio, MediaFusion and StremThru Torz links, entered on the TV the
 * way a playlist's link is (the TV keyboard, an empty box with "Saved · type a new one to replace it"), changed or
 * removed. A saved link shows only where it points and its last 4 characters ([AddonSettings.summary]); it is stored
 * encrypted, and the text typed is handed straight to [AddonSettings.save], never logged, kept or shown.
 *
 * Each add-on's badge says whether it works, as the API keys' do (Accounts & services): "Set · not checked",
 * "Working", "Not accepted", "Couldn't check" ([AddonChecks]), with a short note after the add-ons ("Torrentio:
 * Real-Debrid · checked 5 min ago."). A link is checked once saved, by Check now, and as this window opens when its
 * last check is a day old.
 */
object AddonsSettings {

    /** The Add-ons window's rows. */
    fun render(panel: SidePanel, activity: Activity, addons: AddonSettings, checks: AddonChecks?): Unit = with(panel) {
        header("Add-ons")
        // Settings opening: an add-on not checked (or used) for a day is checked now, its badge saying so meanwhile.
        checks?.checkStale { if (isOpen) refresh() }
        val statuses = Addon.entries.associateWith { checks?.status(it) }
        for (a in Addon.entries) {
            val open = { openSub(a.label) { renderAddon(this, activity, addons, checks, a) } }
            val st = statuses[a]
            if (st == null) {
                row(a.label, addons.summary(a) ?: NOT_SET) { open() }
            } else {
                val (text, style) = SettingsPanels.badgeFor(st.state)
                badgeRow(a.label, text, style) { open() }
            }
        }
        // What each status says, after the add-ons, so their card stays whole (as the keys' problems).
        val now = System.currentTimeMillis()
        for (a in Addon.entries) statuses[a]?.detail(now)?.let { message("${a.label}: $it.") }
        if (checks != null && Addon.entries.any { addons.isSet(it) }) {
            // While it checks, dimmed and doing nothing, as Check the keys now.
            val busy = checks.checking
            val row = row(CHECK_ALL, if (busy) CredentialChecks.State.CHECKING.label else null) {
                if (checks.checking) return@row
                for (a in Addon.entries) checks.check(a) { if (isOpen) refresh() }
                refresh()
            }
            if (busy) dim(row)
        }
        message("Sources come from these add-ons, with your debrid service (Real-Debrid, TorBox...). Their links carry your debrid key or settings, so FirePlayer keeps them encrypted on this TV and never shows them in full.")
    }

    /** One add-on's window: its link (enter or replace), its status (Check now), and Remove. */
    private fun renderAddon(panel: SidePanel, activity: Activity, addons: AddonSettings, checks: AddonChecks?, a: Addon): Unit = with(panel) {
        header(a.label)
        val set = addons.isSet(a)
        row("Link", addons.summary(a) ?: NOT_SET) {
            // The box starts empty: a saved link is never put back on screen.
            SidePanel.prompt(
                activity, "${a.label} link", "",
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                hint = if (set) SAVED_HINT else hintFor(a),
                // The link carries the debrid key: kept only encrypted, never in the keyboard's memory.
                unlearned = true,
            ) { typed ->
                if (typed.isBlank()) return@prompt
                val result = addons.save(a, typed)
                if (result != AddonSave.SAVED) {
                    FireDialog.ask(activity, problem(a, result), listOf("OK" to {}), fromPanel = true)
                } else {
                    // A new link is checked at once.
                    checks?.check(a) { if (isOpen) refresh() }
                }
                refresh()
            }
        }
        val st = if (set) checks?.status(a) else null
        if (checks != null && st != null) {
            val (text, style) = SettingsPanels.badgeFor(st.state)
            val busy = st.state == CredentialChecks.State.CHECKING
            val row = badgeRow(CHECK_ONE, text, style) {
                if (checks.status(a).state == CredentialChecks.State.CHECKING) return@badgeRow
                checks.check(a) { if (isOpen) refresh() }
                refresh()
            }
            if (busy) dim(row)
        }
        if (set) {
            row("Remove") {
                FireDialog.ask(
                    activity, "Remove ${a.label}'s link? Its sources stop at once.",
                    listOf("Remove" to { addons.remove(a); checks?.forget(a); refresh() }, "Keep it" to {}),
                    focus = 1, fromPanel = true,
                )
            }
        }
        st?.detail(System.currentTimeMillis())?.let { message(it.replaceFirstChar { c -> c.uppercaseChar() } + ".") }
        message(whereFrom(a))
    }

    /** What went wrong saving, in plain words (never the link). */
    fun problem(a: Addon, result: AddonSave): String = when (result) {
        AddonSave.SAVED -> "Saved."
        AddonSave.NOT_A_LINK -> "That isn't a link. ${a.label}'s link starts with https:// or stremio://."
        AddonSave.NOT_THIS_ADDON -> "That link isn't ${a.label}'s. Check you copied the right add-on."
        AddonSave.COULD_NOT_STORE -> "This TV couldn't store the link safely, so it wasn't saved."
    }

    private fun hintFor(a: Addon): String = when (a) {
        Addon.TORRENTIO -> "https://torrentio.strem.fun/…"
        Addon.MEDIAFUSION -> "https://mediafusion…/…"
        Addon.TORZ -> "https://stremthru…/stremio/torz/…/manifest.json"
    }

    private fun whereFrom(a: Addon): String = when (a) {
        Addon.TORRENTIO -> "Torrentio: configure it with your debrid service's key (Real-Debrid, TorBox...) on its website, then copy the link it gives to install it."
        Addon.MEDIAFUSION -> "MediaFusion: set it up with your debrid service (Real-Debrid, TorBox...) on its website (the public ElfHosted one), then copy the link it gives to install it."
        Addon.TORZ -> "StremThru Torz: configure it with your debrid service (Real-Debrid, TorBox...) on its StremThru page, then copy the link it gives to install it."
    }

    const val NOT_SET = "Not set"
    const val SAVED_HINT = "Saved · type a new one to replace it"

    /** The Add-ons window's row that checks every saved add-on, as Accounts & services' Check the keys now. */
    const val CHECK_ALL = "Check the add-ons now"

    /** An add-on's own window: checks it, its badge saying how it stands. */
    const val CHECK_ONE = "Check now"
}
