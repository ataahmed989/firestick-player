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
 * [applyDim] puts the preset on screen; [refreshLabels] updates the value under the dimmer's button. An arrow's step
 * rewrites the values in place ([SidePanel.stepper], [SidePanel.liveValue]): the rows stay, and so does the selection.
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
            // Read again after each step: with Auto, display dimness and glass dimness move together.
            stepper(value.label, { SettingsPanels.percent(value.of(dimmer.active())) }) { dir ->
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
        val save = row(SAVE, value = unsaved(changed)) { saveCustom(this, activity, dimmer, applyDim, refreshLabels) }
        save.isSelected = changed
        liveValue {
            val now = dimmer.hasUnsaved()
            if (now != save.isSelected) {
                updateRow(save, SAVE, unsaved(now))
                save.isSelected = now
            }
        }
        if (active.isDefault) {
            message(DEFAULT_NOTE, maxLines = SettingsPanels.NOTE_LINES)
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

    /** Under Default's rows: where else its glass is set, in a line. */
    const val DEFAULT_NOTE = "Default's glass is Settings › Look › Playback glass."

    private const val SAVE = "Save custom"

    private fun unsaved(changed: Boolean) = if (changed) "Unsaved" else null

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
