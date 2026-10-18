package app.fireplayer.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import app.fireplayer.data.Prefs
import app.fireplayer.subs.FontImport
import app.fireplayer.subs.ShadowStrength
import app.fireplayer.subs.SubtitleEdge
import app.fireplayer.subs.SubtitleFont
import app.fireplayer.subs.SubtitlePresets
import app.fireplayer.subs.SubtitleStyle
import app.fireplayer.update.Updater
import java.util.Locale
import kotlin.math.roundToInt

/** The Settings and Subtitle style windows, shared by the player and the home screen. */
class SettingsPanels(
    private val activity: Activity,
    private val prefs: Prefs,
    private val presets: SubtitlePresets,
) {

    private val updater = Updater(activity)
    private var updateStatus: String? = null

    /** Called after a font arrives from the phone (or is removed), so the subtitles can switch to it. */
    var onFontChanged: () -> Unit = {}

    /** Languages offered as defaults; English first. */
    private val languages = listOf("en", "ar", "es", "fr", "de", "it", "pt", "hi", "ur", "tr", "ja", "ko", "zh")

    fun languageName(code: String): String =
        if (code.isEmpty()) "Off" else Locale(code).getDisplayLanguage(Locale.ENGLISH)

    private val importedFontName: String?
        get() = prefs.customFontName.takeIf { it.isNotEmpty() && FontImport.customFontFile(activity).exists() }

    fun renderSettings(panel: SidePanel) = with(panel) {
        header("Skipping")
        val dpadSteps = listOf(5, 10, 15, 20, 30, 60)
        choice("D-pad left / right", dpadSteps.map { formatStep(it) }, dpadSteps.indexOf(prefs.dpadStepSec)) {
            prefs.dpadStepSec = dpadSteps[it]
        }
        val ffSteps = listOf(15, 30, 60, 120, 300, 600)
        choice("Rewind / fast-forward", ffSteps.map { formatStep(it) }, ffSteps.indexOf(prefs.ffStepSec)) {
            prefs.ffStepSec = ffSteps[it]
        }
        row("Hold to speed up", onOff(prefs.holdSpeedUp)) {
            prefs.holdSpeedUp = !prefs.holdSpeedUp; refresh()
        }
        row("Preview while skipping", onOff(prefs.seekPreviews)) {
            prefs.seekPreviews = !prefs.seekPreviews; refresh()
        }
        if (prefs.seekPreviews) message("Uses a second connection to the server. Turn it off if your IPTV plan allows only one.")

        header("Defaults")
        val subLanguages = languages + ""
        choice("Subtitle language", subLanguages.map { languageName(it) }, subLanguages.indexOf(prefs.subtitleLanguage)) {
            prefs.subtitleLanguage = subLanguages[it]
        }
        choice("Audio language", languages.map { languageName(it) }, languages.indexOf(prefs.audioLanguage)) {
            prefs.audioLanguage = languages[it]
        }
        val resumes = Prefs.Resume.entries
        choice("Resume where I left off", resumes.map { it.label }, resumes.indexOf(prefs.resume)) {
            prefs.resume = resumes[it]
        }

        header("Playback")
        row("Match frame rate", onOff(prefs.matchFrameRate)) {
            prefs.matchFrameRate = !prefs.matchFrameRate; refresh()
        }
        row("Surround passthrough", onOff(prefs.passthrough)) {
            prefs.passthrough = !prefs.passthrough; refresh()
        }

        header("Subtitle font")
        message(importedFontName?.let { "Using $it from your phone." } ?: "Using the built-in font.")
        row("Send a font from your phone") {
            FontImport(activity, prefs) {
                onFontChanged()
                if (isOpen) refresh()
            }.show()
        }
        if (importedFontName != null) {
            row("Go back to the built-in font") {
                FontImport.customFontFile(activity).delete()
                prefs.customFontName = ""
                onFontChanged()
                refresh()
            }
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
            updater.check(onFailed = { updateStatus = "Couldn't check"; if (isOpen) refresh() }) { release ->
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
        val all = presets.all()

        choice("Preset", all.map { it.name }, all.indexOfFirst { it.name == style.name }) {
            presets.setActive(all[it].name); onStyleChanged()
        }
        val sizes = SubtitleStyle.SIZES
        choice("Size", sizes.map { sizeLabel(it) }, sizes.indexOf(nearest(sizes, style.textSize))) { i ->
            presets.editActive { it.copy(textSize = sizes[i]) }; onStyleChanged()
        }
        val colors = SubtitleStyle.COLORS
        choice("Color", colors.map { it.second }, colors.indexOfFirst { it.first == style.color }) { i ->
            presets.editActive { it.copy(color = colors[i].first) }; onStyleChanged()
        }
        val edges = SubtitleEdge.entries
        choice("Edge", edges.map { it.label }, edges.indexOf(style.edge)) { i ->
            presets.editActive { it.copy(edge = edges[i]) }; onStyleChanged()
        }
        if (style.edge == SubtitleEdge.OUTLINE) {
            val outlineColors = SubtitleStyle.BOX_COLORS
            choice("Outline color", outlineColors.map { it.second }, outlineColors.indexOfFirst { it.first == style.outlineColor }) { i ->
                presets.editActive { it.copy(outlineColor = outlineColors[i].first) }; onStyleChanged()
            }.indent()
        }
        if (style.edge == SubtitleEdge.DROP_SHADOW) {
            val strengths = ShadowStrength.entries
            choice("Shadow strength", strengths.map { it.label }, strengths.indexOf(style.shadow)) { i ->
                presets.editActive { it.copy(shadow = strengths[i]) }; onStyleChanged()
            }.indent()
        }
        row("Background box", onOff(style.box)) {
            presets.editActive { it.copy(box = !it.box) }
            onStyleChanged(); refresh()
        }
        if (style.box) {
            val opacities = SubtitleStyle.BOX_OPACITIES
            choice(
                "Background opacity",
                opacities.map { "${(it * 100).roundToInt()}%" },
                opacities.indexOf(nearest(opacities, style.boxOpacity)),
            ) { i ->
                presets.editActive { it.copy(boxOpacity = opacities[i]) }; onStyleChanged()
            }.indent()
            val boxColors = SubtitleStyle.BOX_COLORS
            choice("Background color", boxColors.map { it.second }, boxColors.indexOfFirst { it.first == style.boxColor }) { i ->
                presets.editActive { it.copy(boxColor = boxColors[i].first) }; onStyleChanged()
            }.indent()
            row("Rounded corners", onOff(style.boxRounded)) {
                presets.editActive { it.copy(boxRounded = !it.boxRounded) }
                onStyleChanged(); refresh()
            }.indent()
        }
        val bottoms = SubtitleStyle.BOTTOMS
        choice("Position", bottoms.map { positionLabel(it) }, bottoms.indexOf(nearest(bottoms, style.bottom))) { i ->
            presets.editActive { it.copy(bottom = bottoms[i]) }; onStyleChanged()
        }
        val fonts = SubtitleFont.entries
        choice("Font", fonts.map { fontLabel(it) }, fonts.indexOf(style.font)) { i ->
            presets.editActive { it.copy(font = fonts[i]) }; onStyleChanged()
        }
        row("Keep embedded styling", onOff(prefs.keepAssStyling)) {
            prefs.keepAssStyling = !prefs.keepAssStyling; onAssChanged(); refresh()
        }
        message(
            if (prefs.keepAssStyling) "Subtitles that come with their own colors and fonts keep them."
            else "Your style is used for every subtitle, whatever the file says.",
        )

        spacer(6)
        // Saving only makes sense once something has been changed.
        row("Save as preset", enabled = style.name == SubtitleStyle.CUSTOM_NAME && presets.customIsNew()) {
            fun askName(title: String) {
                SidePanel.prompt(activity, title, "") { name ->
                    val clean = name.trim()
                    if (clean.isEmpty() || clean.equals(SubtitleStyle.NETFLIX_NAME, ignoreCase = true) ||
                        clean.equals(SubtitleStyle.CUSTOM_NAME, ignoreCase = true)
                    ) {
                        askName("Pick another name")
                        return@prompt
                    }
                    presets.saveActiveAs(clean); onStyleChanged(); refresh()
                    // Saving swaps this row for "Delete"; start back at the top instead of on it.
                    focusRow(0)
                }
            }
            askName("Preset name")
        }
        if (presets.isSaved(style.name)) {
            row("Delete \"${style.name}\"") {
                FireDialog.ask(
                    activity,
                    "Delete \"${style.name}\"?",
                    listOf<Pair<String, () -> Unit>>(
                        "Delete" to { presets.delete(style.name); onStyleChanged(); refresh(); focusRow(0) },
                        "Keep it" to {},
                    ),
                    // Starts on the safe answer, so a double press doesn't delete.
                    focus = 1,
                )
            }
        }
        row("Reset to Netflix", enabled = style.name != SubtitleStyle.NETFLIX_NAME) {
            presets.setActive(SubtitleStyle.NETFLIX_NAME); onStyleChanged(); refresh()
        }
    }

    /** Nests an option under the setting that turns it on. */
    private fun View.indent(): View = apply {
        val inset = (14 * activity.resources.displayMetrics.density).toInt()
        // The left margin too: a start margin alone is applied only after the row is measured,
        // so the row kept its full width, moved right and lost the end of its value.
        (layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.marginStart = inset
            it.leftMargin = inset
            layoutParams = it
        }
    }

    private fun fontLabel(font: SubtitleFont): String =
        if (font == SubtitleFont.NETFLIX) importedFontName ?: font.label else font.label

    private fun sizeLabel(size: Float) = "${(size / SubtitleStyle.NETFLIX.textSize * 100).roundToInt()}%"

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

        fun nearest(options: List<Float>, value: Float): Float =
            options.minByOrNull { kotlin.math.abs(it - value) } ?: value
    }
}
