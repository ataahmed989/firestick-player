package app.fireplayer.ui

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import app.fireplayer.R
import app.fireplayer.data.KeyStore
import app.fireplayer.data.Prefs
import app.fireplayer.player.NextEpisode
import app.fireplayer.subs.FontImport
import app.fireplayer.subs.ShadowStrength
import app.fireplayer.subs.SubtitleEdge
import app.fireplayer.subs.SubtitleFont
import app.fireplayer.subs.SubtitlePresets
import app.fireplayer.subs.SubtitleStyle
import app.fireplayer.subs.Tmdb
import app.fireplayer.titles.TitleBook
import app.fireplayer.titles.TitleProxy
import app.fireplayer.titles.TitleService
import app.fireplayer.update.Updater
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * The Settings and Subtitle style windows, shared by the player and the home screen. [inPlayer]: opened over a film,
 * while which TiviMate's titles wait.
 */
class SettingsPanels(
    private val activity: Activity,
    private val prefs: Prefs,
    private val presets: SubtitlePresets,
    private val inPlayer: Boolean = false,
) {

    private val updater = Updater(activity)
    private var updateStatus: String? = null
    // How the last TMDB key check went: the row's value, and what TMDB said when it said no.
    private var tmdbStatus: String? = null
    private var tmdbProblem: String? = null

    // TiviMate titles, for one opening of the window: whether the set-up details are shown (hidden at first), and
    // what "Check playlists now" said.
    private var titlesSession = -1
    private var detailsShown = false
    private var arrowTurned = false
    private var checkValue: String? = null
    private var checkNote: String? = null

    // TiviMate titles' counts, read off the main thread every few seconds while they're on screen. The book is opened
    // and closed on the reader's thread only.
    private val main = Handler(Looper.getMainLooper())
    private val reader: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r ->
            // Below the film's own threads, when Settings is open over one.
            Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "FirePlayerTitles")
                .apply { isDaemon = true }
        }
    }
    private var book: TitleBook? = null
    private var titleCounts: List<TitleBook.Progress>? = null
    private var countsReadAt = 0L
    private var reading = false
    private var statusOnScreen: TitleStatusView? = null
    private var statusViews = 0

    /** Called when the glass look is turned on or off, so the screen behind can restyle its buttons. */
    var onLookChanged: () -> Unit = {}

    /** Called after a font arrives from the phone (or is removed), so the subtitles can switch to it. */
    var onFontChanged: () -> Unit = {}

    /** Languages offered as defaults; English first. */
    private val languages = listOf("en", "ar", "es", "fr", "de", "it", "pt", "hi", "ur", "tr", "ja", "ko", "zh")

    private val density = activity.resources.displayMetrics.density

    private fun dp(value: Int) = (value * density).toInt()

    private fun color(res: Int) = ContextCompat.getColor(activity, res)

    fun languageName(code: String): String =
        if (code.isEmpty()) "Off" else Locale(code).getDisplayLanguage(Locale.ENGLISH)

    /** The font sent from the phone, by its name, or null while the built-in one is used. */
    private val importedFontName: String?
        get() = if (FontImport.customFontFile(activity).exists()) prefs.customFontName.ifEmpty { "Font from your phone" } else null

    fun renderSettings(panel: SidePanel) = with(panel) {
        header("Remote")
        choice("Menu button opens", listOf("Settings", "Subtitle style"), if (prefs.menuOpensStyle) 1 else 0) {
            prefs.menuOpensStyle = it == 1
        }

        header("Look")
        row("Glass look", onOff(prefs.glassLook)) {
            prefs.glassLook = !prefs.glassLook
            Glass.on = prefs.glassLook
            onLookChanged()
            Glass.background(root, Glass.Kind.PANEL)
            refresh()
        }
        if (prefs.glassLook) {
            // How often the glass copies the film: faster follows it more closely, slower is lighter on older sticks.
            val rates = listOf(2, 4, 8)
            choice("Glass refresh", rates.map { "$it times a second" }, rates.indexOf(prefs.glassRefreshPerSec).let { if (it < 0) 1 else it }) {
                prefs.glassRefreshPerSec = rates[it]
            }
        }

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
        if (prefs.seekPreviews) message("Uses a second connection to the server.")

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
        row("Play next episode", onOff(prefs.playNextEpisode)) {
            prefs.playNextEpisode = !prefs.playNextEpisode; refresh()
        }
        if (prefs.playNextEpisode) NextEpisode.status?.let { message(it) }
        row("Match frame rate", onOff(prefs.matchFrameRate)) {
            prefs.matchFrameRate = !prefs.matchFrameRate; refresh()
        }
        row("Surround passthrough", onOff(prefs.passthrough)) {
            prefs.passthrough = !prefs.passthrough; refresh()
        }

        renderTitles(this)

        header("Subtitle font")
        val fontSet = importedFontName != null
        badgeRow("Font from your phone", activeOrNot(fontSet), if (fontSet) Rows.Badge.ACTIVE else Rows.Badge.INACTIVE) {
            FontImport(activity, prefs) {
                onFontChanged()
                if (isOpen) refresh()
            }.show()
        }
        if (fontSet) {
            row("Use the built-in font") {
                FontImport.customFontFile(activity).delete()
                prefs.customFontName = ""
                onFontChanged()
                refresh()
            }
        }

        header("Online subtitles and TMDB API")
        keyRow("OpenSubtitles API key", KeyStore.Key.OPENSUBTITLES, whereFrom = "From opensubtitles.com › API consumers")
        // Optional: signing in raises how many subtitles can be downloaded a day.
        keyRow("OpenSubtitles username", KeyStore.Key.OPENSUBTITLES_USER, whereFrom = OPTIONAL_HINT)
        keyRow("OpenSubtitles password", KeyStore.Key.OPENSUBTITLES_PASSWORD, whereFrom = OPTIONAL_HINT, password = true)
        val tmdbBadge = when (tmdbStatus) {
            CHECKING -> CHECKING to Rows.Badge.BUSY
            NOT_ACCEPTED -> NOT_ACCEPTED to Rows.Badge.PROBLEM
            else -> activeOrNot(prefs.keys.isSet(KeyStore.Key.TMDB)).let { it to if (it == ACTIVE) Rows.Badge.ACTIVE else Rows.Badge.INACTIVE }
        }
        keyRow(
            "TMDB API key",
            KeyStore.Key.TMDB,
            whereFrom = "From themoviedb.org › Settings › API",
            title = "TMDB API key or read access token",
            badge = tmdbBadge,
        ) {
            // TiviMate's titles start being looked up with it.
            TitleService.start(activity)
            tmdbProblem = null
            tmdbStatus = CHECKING
            thread(name = "FirePlayerTmdb", isDaemon = true) {
                val problem = Tmdb(prefs).check()
                activity.runOnUiThread {
                    tmdbStatus = if (problem == null) ACTIVE else NOT_ACCEPTED
                    tmdbProblem = problem
                    if (isOpen) refresh()
                }
            }
        }
        tmdbProblem?.let { message("$it.") }
        // TMDB asks apps that use its API to say so.
        view(TextView(activity).apply {
            text = TMDB_NOTICE
            setTextColor(color(R.color.text_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setLineSpacing(2f * density, 1f)
            alpha = 0.8f
        }, top = 3, bottom = 4)

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
     * TiviMate's English titles: the switch, with an arrow beside it for the set-up details (hidden each time the
     * window opens); under it, while on, how far the titles are and a button that sets the server to work now.
     */
    private fun renderTitles(panel: SidePanel) = with(panel) {
        header("TiviMate titles")
        if (session != titlesSession) {
            titlesSession = session
            detailsShown = false
            checkValue = null
            checkNote = null
        }
        val turned = arrowTurned
        arrowTurned = false
        rowWithArrow(
            "English titles in TiviMate",
            onOff(prefs.tivimateTitles),
            expanded = detailsShown,
            turned = turned,
            onClick = {
                prefs.tivimateTitles = !prefs.tivimateTitles
                TitleService.start(activity)
                checkValue = null
                checkNote = null
                refresh()
            },
            onArrow = {
                detailsShown = !detailsShown
                arrowTurned = true
                refresh()
            },
        )
        if (prefs.tivimateTitles) view(titleStatus(), top = 6, bottom = 4)
        if (detailsShown) view(titleDetails(), top = 4, bottom = 4)
        if (!prefs.tivimateTitles) return@with
        row("Check playlists now", checkValue) {
            if (prefs.keys.isSet(KeyStore.Key.TMDB)) {
                TitleService.checkNow(activity)
                if (inPlayer) checkNote = "Starts once the film is closed." else checkValue = "Started"
            }
            refresh()
        }
        when {
            !prefs.keys.isSet(KeyStore.Key.TMDB) -> message("Needs a TMDB API key (Online subtitles and TMDB API).")
            checkNote != null -> message(checkNote!!)
        }
    }

    /** The bar and counts under TiviMate's switch, kept up to date every few seconds while on screen. */
    private fun titleStatus(): View {
        val view = TitleStatusView(activity)
        view.show(titleCounts, animate = false)
        val tick = object : Runnable {
            override fun run() {
                if (!view.isAttachedToWindow) return
                main.postDelayed(this, STATUS_EVERY_MS)
                if (reading) return
                reading = true
                reader.execute {
                    val counts = runCatching {
                        (book ?: TitleBook(activity.applicationContext).also { book = it }).progress()
                    }.getOrNull()
                    main.post {
                        reading = false
                        countsReadAt = SystemClock.uptimeMillis()
                        if (counts != null) titleCounts = counts
                        statusOnScreen?.show(titleCounts, animate = true)
                    }
                }
            }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                statusViews++
                statusOnScreen = view
                // Counts read a moment ago (the window was only redrawn) are shown until they're due again.
                val due = if (titleCounts == null) 0L
                else (countsReadAt + STATUS_EVERY_MS - SystemClock.uptimeMillis()).coerceIn(0L, STATUS_EVERY_MS)
                main.postDelayed(tick, due)
            }

            override fun onViewDetachedFromWindow(v: View) {
                main.removeCallbacks(tick)
                if (statusOnScreen === view) statusOnScreen = null
                if (--statusViews == 0) {
                    reader.execute {
                        runCatching { book?.close() }
                        book = null
                    }
                }
            }
        })
        return view
    }

    /** What TiviMate needs to be set up: the server address to give each playlist. */
    private fun titleDetails(): View {
        val muted = color(R.color.text_muted)
        fun line(value: CharSequence, sp: Float, color: Int, font: Int? = null) = TextView(activity).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setLineSpacing(2f * density, 1f)
            font?.let { typeface = ResourcesCompat.getFont(activity, it) }
        }
        val address = SpannableStringBuilder("http://127.0.0.1:${TitleProxy.PORT}/").apply {
            val start = length
            append("your-server:port")
            setSpan(ForegroundColorSpan(muted), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        fun params(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(if (Glass.on) 14 else 9).toFloat()
                setColor(color(if (Glass.on) R.color.details_fill_glass else R.color.details_fill))
                if (Glass.on) setStroke(dp(1).coerceAtLeast(1), color(R.color.details_rim_glass))
            }
            setPadding(dp(12), dp(9), dp(12), dp(10))
            addView(line("Server address for each TiviMate playlist:", 11.5f, muted), params(0))
            addView(line(address, 13f, color(R.color.text), R.font.figtree_700), params(3))
        }
    }

    /**
     * A key or sign-in row: "Active" in blue once saved, "Inactive" in gray before ([badge] in its place, such as
     * the TMDB check's). Its box starts empty, so a saved key is never shown, and says where to get one (or that one
     * is saved); confirmed empty, it leaves a saved key as it is. [onSaved] follows a new one.
     */
    private fun SidePanel.keyRow(
        label: String,
        key: KeyStore.Key,
        whereFrom: String,
        password: Boolean = false,
        title: String = label,
        badge: Pair<String, Rows.Badge>? = null,
        onSaved: () -> Unit = {},
    ) {
        val set = prefs.keys.isSet(key)
        val (text, style) = badge ?: (activeOrNot(set) to if (set) Rows.Badge.ACTIVE else Rows.Badge.INACTIVE)
        badgeRow(label, text, style) {
            SidePanel.prompt(activity, title, "", password = password, hint = if (set) SAVED_HINT else whereFrom) { typed ->
                if (prefs.keys.save(key, typed)) onSaved()
                refresh()
            }
        }
    }

    private fun activeOrNot(set: Boolean) = if (set) ACTIVE else INACTIVE

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
        // The last choice keeps the file's own placement for lines it puts elsewhere ("As in the file").
        val positionNow = if (style.filePosition) bottoms.size else bottoms.indexOf(nearest(bottoms, style.bottom))
        choice("Position", bottoms.map { positionLabel(it) } + FILE_POSITION, positionNow) { i ->
            presets.editActive { if (i == bottoms.size) it.copy(filePosition = true) else it.copy(bottom = bottoms[i], filePosition = false) }
            onStyleChanged()
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
                    fromPanel = true,
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
        const val FILE_POSITION = "As in the file"
        fun onOff(on: Boolean) = if (on) "On" else "Off"

        private const val ACTIVE = "Active"
        private const val INACTIVE = "Inactive"
        private const val CHECKING = "Checking…"
        private const val NOT_ACCEPTED = "Not accepted"
        private const val SAVED_HINT = "Saved · type a new one to replace it"
        private const val OPTIONAL_HINT = "Optional · more downloads a day"
        private const val TMDB_NOTICE = "This product uses the TMDB API but is not endorsed or certified by TMDB."
        private const val STATUS_EVERY_MS = 2_500L

        fun formatStep(sec: Int): String =
            if (sec < 60) "$sec s" else "${sec / 60}:${"%02d".format(sec % 60)}"

        fun nearest(options: List<Float>, value: Float): Float =
            options.minByOrNull { kotlin.math.abs(it - value) } ?: value
    }
}
