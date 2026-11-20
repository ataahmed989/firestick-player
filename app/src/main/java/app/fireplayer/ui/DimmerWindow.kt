package app.fireplayer.ui

import android.app.Activity
import app.fireplayer.R
import app.fireplayer.data.Dimmer

/**
 * The Screen dimmer window's rows, shared by the VOD player and full-screen Live TV so both show the same customs
 * and keep the same saved values ([Dimmer]): the customs, then the one on, with its display amount (the picture) and
 * glass amount (all glass, Glass look) set with arrows. Custom 1 keeps changes at once, the others when Save custom is
 * pressed. Whatever is picked or saved stays on for every video and channel.
 *
 * [applyDim] puts the amounts on screen; [refreshLabels] updates the value under the dimmer's button.
 */
object DimmerWindow {

    fun render(panel: SidePanel, activity: Activity, dimmer: Dimmer, applyDim: () -> Unit, refreshLabels: () -> Unit) = with(panel) {
        val active = dimmer.active()
        header("Customs")
        dimmer.customs().forEach { c ->
            val on = c.id == active.id
            row(c.name, value = if (on) null else "${c.display}% · ${c.glass}%", checked = on) {
                dimmer.chooseCustom(c.id)
                applyDim()
                refreshLabels()
                refresh()
            }
        }
        header(active.name, R.drawable.ic_head_moon)
        stepper("Display dimmer", "${active.display}%") { dir ->
            dimmer.stepDisplay(dir)
            applyDim()
            refreshLabels()
        }
        stepper("Glass dimmer", "${active.glass}%") { dir ->
            dimmer.stepGlass(dir)
            applyDim()
            refreshLabels()
        }
        row("Auto", SettingsPanels.onOff(active.auto)) {
            dimmer.toggleAuto()
            applyDim()
            refresh()
        }
        val changed = dimmer.hasUnsaved()
        spacer(6)
        // Blue, like the current choice, and marked Unsaved while there's something to save.
        row("Save custom", value = if (changed) "Unsaved" else null) { saveCustom(this, activity, dimmer, applyDim, refreshLabels) }.isSelected = changed
        if (active.id != Dimmer.FIRST_ID) {
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

    /**
     * Save custom: keeps the changes to the custom on, renames it, or saves what's on as a new custom. Custom 1
     * keeps its changes at once, so for it Save custom is for renaming it or making a new one.
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
        val answers = ArrayList<Pair<String, () -> Unit>>()
        if (dimmer.hasUnsaved()) answers += "Save \"${active.name}\"" to { dimmer.saveChanges(); after() }
        answers += "Rename \"${active.name}\"" to {
            askName("New name", active.name, active.id) { dimmer.rename(it); after() }
        }
        answers += "Save as a new custom" to {
            askName("Custom name", "", null) { dimmer.saveAsNew(it); after() }
        }
        FireDialog.ask(activity, "Save custom", answers, fromPanel = true)
    }
}
