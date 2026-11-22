package app.fireplayer.ui

import android.app.Activity
import app.fireplayer.R
import app.fireplayer.data.Dimmer

/**
 * The Screen dimmer window's rows, the players' dimness settings, shared by the VOD player and full-screen Live TV so
 * both show the same presets and keep the same saved values ([Dimmer]): the presets ("Default" first, then the customs
 * added), then the one on, with its Display dimness (the picture), Glass tint and Glass dimness (the Playback glass)
 * set with arrows ([Dimmer.Value]). Default's glass is Settings › Look › Playback glass's, changed there and here alike;
 * a custom's changes show at once and are kept with Save custom. Whatever is picked or saved stays on for every video
 * and channel.
 *
 * [applyDim] puts the preset on screen; [refreshLabels] updates the value under the dimmer's button.
 */
object DimmerWindow {

    fun render(panel: SidePanel, activity: Activity, dimmer: Dimmer, applyDim: () -> Unit, refreshLabels: () -> Unit) = with(panel) {
        val active = dimmer.active()
        header(PRESETS)
        dimmer.presets().forEach { p ->
            val on = p.id == active.id
            row(p.name, value = if (on) null else p.values, checked = on) {
                dimmer.choose(p.id)
                applyDim()
                refreshLabels()
                refresh()
            }
        }
        header(active.name, R.drawable.ic_head_moon)
        for (value in Dimmer.Value.entries) {
            stepper(value.label, SettingsPanels.percent(value.of(active))) { dir ->
                dimmer.step(value, dir)
                applyDim()
                refreshLabels()
            }
        }
        if (!active.isDefault) {
            row("Auto", SettingsPanels.onOff(active.auto)) {
                dimmer.toggleAuto()
                applyDim()
                refresh()
            }
        }
        val changed = dimmer.hasUnsaved()
        spacer(6)
        // Blue, like the current choice, and marked Unsaved while there's something to save.
        row("Save custom", value = if (changed) "Unsaved" else null) { saveCustom(this, activity, dimmer, applyDim, refreshLabels) }.isSelected = changed
        if (active.isDefault) {
            message(DEFAULT_NOTE)
        } else {
            row("Delete \"${active.name}\"") {
                FireDialog.ask(
                    activity,
                    "Delete \"${active.name}\"?",
                    listOf<Pair<String, () -> Unit>>(
                        "Delete" to { dimmer.delete(active.id); applyDim(); refreshLabels(); refresh() },
                        "Keep it" to {},
                    ),
                    // Starts on the safe answer, so a double press doesn't delete.
                    focus = 1,
                    fromPanel = true,
                )
            }
        }
    }

    /** The heading over the presets. */
    const val PRESETS = "Presets"

    /** Under Default's rows: what it is, and how a custom is added. */
    const val DEFAULT_NOTE = "Default doesn't dim the picture. Its glass tint and glass dimness are Settings › Look › " +
        "Playback glass: a change here shows there, and there here. Save custom adds a custom with what's on."

    /**
     * Save custom: keeps the changes to the custom on, renames it, or saves what's on as a new custom. On Default it adds
     * a custom with what's on (Default itself can't be renamed, and its glass is kept at once).
     */
    private fun saveCustom(panel: SidePanel, activity: Activity, dimmer: Dimmer, applyDim: () -> Unit, refreshLabels: () -> Unit) {
        val active = dimmer.active()
        fun askName(title: String, initial: String, except: String?, done: (String) -> Unit) {
            SidePanel.prompt(activity, title, initial) { name ->
                val clean = name.trim()
                val problem = dimmer.nameProblem(clean, except)
                if (problem != null) askName(problem, clean, except, done) else done(clean)
            }
        }
        val after = { applyDim(); refreshLabels(); panel.refresh() }
        val saveAsNew = { askName("Custom name", "", null) { dimmer.saveAsNew(it); after() } }
        if (active.isDefault) {
            saveAsNew()
            return
        }
        val answers = ArrayList<Pair<String, () -> Unit>>()
        if (dimmer.hasUnsaved()) answers += "Save \"${active.name}\"" to { dimmer.saveChanges(); after() }
        answers += "Rename \"${active.name}\"" to {
            askName("New name", active.name, active.id) { dimmer.rename(it); after() }
        }
        answers += "Save as a new custom" to saveAsNew
        FireDialog.ask(activity, "Save custom", answers, fromPanel = true)
    }
}
