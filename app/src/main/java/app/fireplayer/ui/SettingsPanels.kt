package app.fireplayer.ui

import android.app.Activity
import app.fireplayer.data.Prefs
import app.fireplayer.subs.SubtitleEdge
import app.fireplayer.subs.SubtitleFont
import app.fireplayer.subs.SubtitlePresets
import app.fireplayer.subs.SubtitleStyle
import app.fireplayer.update.Updater
import java.util.Locale

/** The Settings and Subtitle style windows, shared by the player and the home screen. */
class SettingsPanels(
    private val activity: Activity,
    private val prefs: Prefs,
    private val presets: SubtitlePresets,
) {

    /** Languages offered as defaults; English first. */
    private val languages = listOf("en", "", "ar", "es", "fr", "de", "it", "pt", "hi", "ur", "tr", "ja", "ko", "zh")

    private val updater = Updater(activity)
    private var updateStatus: String? = null

    fun languageName(code: String): String =
        if (code.isEmpty()) "Off" else Locale(code).getDisplayLanguage(Locale.ENGLISH)

    fun renderSettings(panel: SidePanel) = with(panel) {
        header("Skipping")
        row("D-pad left / right", formatStep(prefs.dpadStepSec)) {
            prefs.dpadStepSec = next(listOf(5, 10, 15, 30), prefs.dpadStepSec); refresh()
        }
        row("Rewind / fast-forward", formatStep(prefs.ffStepSec)) {
            prefs.ffStepSec = next(listOf(30, 60, 120, 300, 600), prefs.ffStepSec); refresh()
        }
        row("Hold to speed up", onOff(prefs.holdSpeedUp)) {
            prefs.holdSpeedUp = !prefs.holdSpeedUp; refresh()
        }

        header("Defaults")
        row("Subtitle language", languageName(prefs.subtitleLanguage)) {
            prefs.subtitleLanguage = next(languages, prefs.subtitleLanguage); refresh()
        }
        row("Audio language", languageName(prefs.audioLanguage)) {
            prefs.audioLanguage = next(languages.filter { it.isNotEmpty() }, prefs.audioLanguage); refresh()
        }
        row("Resume where I left off", prefs.resume.label) {
            prefs.resume = next(Prefs.Resume.entries, prefs.resume); refresh()
        }

        header("Playback")
        row("Match frame rate", onOff(prefs.matchFrameRate)) {
            prefs.matchFrameRate = !prefs.matchFrameRate; refresh()
        }
        row("Surround passthrough", onOff(prefs.passthrough)) {
            prefs.passthrough = !prefs.passthrough; refresh()
        }

        header("Online subtitles")
        message("Free key from opensubtitles.com (Profile, API consumers).")
        row("OpenSubtitles API key", if (prefs.openSubtitlesApiKey.isEmpty()) "Add" else "Saved") {
            SidePanel.prompt(activity, "OpenSubtitles API key", prefs.openSubtitlesApiKey) {
                prefs.openSubtitlesApiKey = it; refresh()
            }
        }
        row("OpenSubtitles username", prefs.openSubtitlesUser.ifEmpty { "Optional" }) {
            SidePanel.prompt(activity, "OpenSubtitles username", prefs.openSubtitlesUser) {
                prefs.openSubtitlesUser = it; refresh()
            }
        }
        row("OpenSubtitles password", if (prefs.openSubtitlesPassword.isEmpty()) "Optional" else "Saved") {
            SidePanel.prompt(activity, "OpenSubtitles password", "", password = true) {
                prefs.openSubtitlesPassword = it; refresh()
            }
        }

        header("App")
        row("Check for updates", updateStatus ?: "Version ${updater.installedName}") {
            updateStatus = "Checking…"; refresh()
            updater.check { release ->
                updateStatus = if (release == null) "Up to date" else null
                if (isOpen) refresh()
                release?.let { updater.offer(it) }
            }
        }
    }

    /**
     * [onStyleChanged] repaints the subtitles; [onAssChanged] rebuilds the player
     * when libass is switched on or off.
     */
    fun renderSubtitleStyle(
        panel: SidePanel,
        onStyleChanged: () -> Unit,
        onAssChanged: () -> Unit,
    ) = with(panel) {
        val style = presets.active()
        val names = presets.all().map { it.name }

        row("Preset", style.name) {
            presets.setActive(next(names, style.name)); onStyleChanged(); refresh()
        }
        row("Size", "${(style.textSize / SubtitleStyle.NETFLIX.textSize * 100).toInt()}%") {
            presets.editActive { it.copy(textSize = next(SubtitleStyle.SIZES, nearest(SubtitleStyle.SIZES, it.textSize))) }
            onStyleChanged(); refresh()
        }
        row("Color", SubtitleStyle.colorLabel(style.color)) {
            val colors = SubtitleStyle.COLORS.map { it.first }
            presets.editActive { it.copy(color = next(colors, it.color)) }
            onStyleChanged(); refresh()
        }
        row("Edge", style.edge.label) {
            presets.editActive { it.copy(edge = next(SubtitleEdge.entries, it.edge)) }
            onStyleChanged(); refresh()
        }
        row("Background box", onOff(style.box)) {
            presets.editActive { it.copy(box = !it.box) }
            onStyleChanged(); refresh()
        }
        row("Position", positionLabel(style.bottom)) {
            presets.editActive { it.copy(bottom = next(SubtitleStyle.BOTTOMS, nearest(SubtitleStyle.BOTTOMS, it.bottom))) }
            onStyleChanged(); refresh()
        }
        row("Font", style.font.label) {
            presets.editActive { it.copy(font = next(SubtitleFont.entries, it.font)) }
            onStyleChanged(); refresh()
        }
        row("Keep ASS styling", onOff(prefs.keepAssStyling)) {
            prefs.keepAssStyling = !prefs.keepAssStyling; onAssChanged(); refresh()
        }

        spacer(6)
        row("Save as preset") {
            val suggested = if (presets.isSaved(style.name)) style.name else ""
            SidePanel.prompt(activity, "Preset name", suggested) { name ->
                presets.saveActiveAs(name); onStyleChanged(); refresh()
            }
        }
        if (presets.isSaved(style.name)) {
            row("Delete \"${style.name}\"") {
                presets.delete(style.name); onStyleChanged(); refresh()
            }
        }
        row("Reset to Netflix") {
            presets.setActive(SubtitleStyle.NETFLIX_NAME); onStyleChanged(); refresh()
        }
    }

    private fun positionLabel(bottom: Float): String = when (nearest(SubtitleStyle.BOTTOMS, bottom)) {
        SubtitleStyle.BOTTOMS[0] -> "Lowest"
        SubtitleStyle.BOTTOMS[1] -> "Bottom of screen"
        SubtitleStyle.BOTTOMS[2] -> "A little higher"
        SubtitleStyle.BOTTOMS[3] -> "Higher"
        else -> "Highest"
    }

    companion object {
        fun onOff(on: Boolean) = if (on) "On" else "Off"

        fun formatStep(sec: Int): String =
            if (sec < 60) "$sec s" else "${sec / 60}:${"%02d".format(sec % 60)}"

        fun <T> next(options: List<T>, current: T): T {
            val i = options.indexOf(current)
            return options[(i + 1).mod(options.size)]
        }

        fun nearest(options: List<Float>, value: Float): Float =
            options.minByOrNull { kotlin.math.abs(it - value) } ?: value
    }
}
