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
import app.fireplayer.data.CredentialChecks
import app.fireplayer.data.Dimmer
import app.fireplayer.data.GlassShades
import app.fireplayer.data.KeyStore
import app.fireplayer.data.Prefs
import app.fireplayer.live.ui.PipRules
import app.fireplayer.live.ui.channelButtonsRow
import app.fireplayer.live.ui.liveBufferRow
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
import app.fireplayer.titles.TitleStatus
import app.fireplayer.update.Updater
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
    /** Whether the OpenSubtitles and TMDB keys work (saved results, shared by every window). */
    private val checks = CredentialChecks(prefs)
    /** Settings › Look's tint and dimness of the three glasses (saved; the players' Screen dimmer shares Playback glass's Tint). */
    private val shades = GlassShades(activity)

    // TiviMate titles, for one opening of the window: whether the set-up details are shown (hidden at first), and
    // what "Check playlists now" said.
    private var titlesSession = -1
    private var detailsShown = false
    private var playlistNoteShown = false
    private var arrowTurned = false
    // The same for the playlists' note and for the skip buttons' details.
    private var playlistArrowTurned = false
    private var skipArrowTurned = false
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
    // The window showing them, redrawn when the playlists are first read.
    private var titlesPanel: SidePanel? = null
    private var countsReadAt = 0L
    private var reading = false
    private var statusOnScreen: TitleStatusView? = null
    private var statusViews = 0

    /** Called when the glass look is turned on or off, so the screen behind can restyle its buttons. */
    var onLookChanged: () -> Unit = {}
    /** Which info pods show above the time bar was changed. */
    var onChipsChanged: () -> Unit = {}

    /** Settings › Rows › Service shortcuts was changed: the rail shows them again. */
    var onRailServicesChanged: () -> Unit = {}

    /** Called after a font arrives from the phone (or is removed), so the subtitles can switch to it. */
    var onFontChanged: () -> Unit = {}

    /** Subtitle auto sync was turned on or off. */
    var onAutoSyncChanged: () -> Unit = {}

    /**
     * Subtitle style, opened from Subtitles & audio: [onSubtitleStyleChanged] repaints the subtitles and
     * [onAssStylingChanged] follows "Keep embedded styling" (as [renderSubtitleStyle]'s own callbacks).
     */
    var onSubtitleStyleChanged: () -> Unit = {}
    var onAssStylingChanged: () -> Unit = {}

    /**
     * Whether that Subtitle style window is the one on screen (true as it's drawn, false as Subtitles & audio is drawn
     * again on Back), so a paused film can show example lines that follow each change, as the player's own window does.
     */
    var onStylePreview: (Boolean) -> Unit = {}

    /**
     * Opens Playlists & EPG or Live TV (Live TV's own windows) over the window that's open, from App settings' list;
     * null leaves them out of the list.
     */
    var liveWindows: ((SettingsCategory) -> Unit)? = null

    /**
     * The Search bubble's action ("Search and App Settings", Live TV's settings window): Search opens in the kind the
     * screen calls for; null leaves the bubble out.
     */
    var onSearch: (() -> Unit)? = null

    /** Settings › Picture-in-picture's first row was changed (the screen stops a picture playing when it's now off). */
    var onPipChanged: (() -> Unit)? = null

    /**
     * Look › Reset to defaults was used: it also forgets Default's unsaved display dimness, so a screen dimming its
     * picture with it ([app.fireplayer.data.Dimmer]) applies its dimness again.
     */
    var onShadesReset: () -> Unit = {}

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

    // ---- The windows ------------------------------------------------------------------------------------------------

    /**
     * App settings: every setting the app has, in its categories ([SettingsCategory]), each opening its own window
     * over this one (Back comes back here). From Menu, and from App › App settings in a player's window, so the
     * settings those windows leave out (skip steps, Hold to speed up, TiviMate titles, the subtitle font, keys) are
     * there too. The Settings tab lists the categories itself.
     */
    fun renderCategories(panel: SidePanel): Unit = with(panel) {
        onSearch?.let { searchBubble(it) }
        val live = liveWindows
        // With the icons the Settings tab shows beside them.
        for (category in SettingsCategory.entries) {
            if (!category.isLive) iconRow(category.title, category.icon) { openSub(category.title) { renderCategory(this, category) } }
            else if (live != null) iconRow(category.title, category.icon) { live(category) }
        }
    }

    /**
     * One category's window, built from the sections below. Playlists & EPG and Live TV are Live TV's own windows
     * ([liveWindows]), so nothing is drawn for them here.
     */
    fun renderCategory(panel: SidePanel, category: SettingsCategory): Unit = with(panel) {
        when (category) {
            SettingsCategory.PLAYLISTS_EPG, SettingsCategory.LIVE_TV -> Unit
            SettingsCategory.PICTURE_IN_PICTURE -> pipSection(this)
            SettingsCategory.PLAYBACK -> {
                header("Playback")
                playNextRow(this)
                resumeRow(this)
                matchFrameRateRow(this)
                passthroughRow(this)
                whenBufferingRow(this)
                fasterDownloadsRow(this)
                // Live TV's own setting, here too (user): the same row and saved value as in the Live TV window.
                liveBufferRow(this, activity)
                skipIntroSection(this)
            }
            SettingsCategory.REMOTE -> {
                // Channel ▲▼ belongs to Live TV, but it's a remote setting too: under Menu button opens here as well.
                remoteSection(this) { channelButtonsRow(this, activity) }
                skippingSection(this, full = true)
            }
            SettingsCategory.SUBTITLES_AUDIO -> {
                // Drawn again on Back from Subtitle style: its example lines go.
                onStylePreview(false)
                defaultsSection(this, autoSync = true, resume = false)
                // Its own window over this one, under its own heading (it isn't a default); the preset in use shows
                // beside it.
                header("Subtitle style")
                row("Subtitle style", presets.active().name) {
                    openSub("Subtitle style") {
                        onStylePreview(true)
                        renderSubtitleStyle(
                            this,
                            onStyleChanged = { onSubtitleStyleChanged() },
                            onAssChanged = { onAssStylingChanged() },
                        )
                    }
                }
                fontSection(this)
            }
            SettingsCategory.LOOK -> {
                lookSection(this)
                // App settings' Look only: the players' Look (their menu's settings) leaves the three glasses out.
                if (prefs.glassLook) glassShadesSection(this)
            }
            SettingsCategory.ACCOUNTS -> credentialsSection(this)
            SettingsCategory.ROWS -> app.fireplayer.vod.ui.RowsSettings.render(this, activity, prefs) { onRailServicesChanged() }
            SettingsCategory.HOME_HEADER -> homeHeaderSection(this)
            SettingsCategory.ADDONS -> app.fireplayer.vod.ui.AddonsSettings.render(this, activity, app.fireplayer.vod.Sources.get(activity).addons)
            SettingsCategory.THIS_TV -> app.fireplayer.vod.ui.ThisTvSettings.render(this, app.fireplayer.vod.Sources.get(activity).thisTv)
            SettingsCategory.TITLES -> renderTitles(this)
            SettingsCategory.APP -> appSection(this, allSettings = false)
        }
    }

    /**
     * VOD FirePlayer Settings, after "This episode / This film" (user spec): Remote, Look, Skip intro and credits,
     * Skipping, Defaults, Playback, Statuses, App.
     */
    fun renderPlayerVod(panel: SidePanel): Unit = with(panel) {
        remoteSection(this)
        lookSection(this)
        skipIntroSection(this)
        skippingSection(this, full = false)
        defaultsSection(this, autoSync = true, resume = true)
        playbackSection(this)
        statusesSection(this)
        appSection(this, allSettings = true)
    }

    /**
     * The shared part of Live FirePlayer Settings, after its Playlists & EPG (user spec): Remote ([remoteMore]: the
     * channel changing buttons), Look, Defaults (no auto sync: live channels don't support it), Playback (Live TV
     * buffer; where the VOD window has its Playback), Statuses, App.
     */
    fun renderPlayerLive(panel: SidePanel, remoteMore: SidePanel.() -> Unit): Unit = with(panel) {
        remoteSection(this, remoteMore)
        lookSection(this)
        defaultsSection(this, autoSync = false, resume = false)
        header("Playback")
        liveBufferRow(this, activity)
        statusesSection(this)
        appSection(this, allSettings = true)
    }

    // ---- Sections: each setting is drawn by one function, so it reads and writes one saved value wherever it is ----

    fun remoteSection(panel: SidePanel, more: SidePanel.() -> Unit = {}): Unit = with(panel) {
        header("Remote")
        menuButtonRow(this, activity)
        more()
    }

    /**
     * Settings › Home Header: whether Home's banner shows the Continue Watching title that has focus (on, as Movies' and
     * TV Shows' do) or always goes through its own titles while it shows. Read by Home each time focus moves, so it
     * applies at once. Under it, how long every tab's banner shows each title while idle, taken from its next wait.
     */
    private fun homeHeaderSection(panel: SidePanel): Unit = with(panel) {
        header(SettingsCategory.HOME_HEADER.title, SettingsCategory.HOME_HEADER.icon)
        row(SettingsCategory.HOME_HEADER_MATCH, onOff(prefs.homeHeaderMatchesContinue), twoLines = true) {
            prefs.homeHeaderMatchesContinue = !prefs.homeHeaderMatchesContinue
            refresh()
        }
        val times = app.fireplayer.vod.ui.VodSizes.HEADER_IDLE_SECONDS
        val time = times.indexOf(prefs.headerIdleSec).let { if (it < 0) times.indexOf(app.fireplayer.vod.ui.VodSizes.HEADER_IDLE_DEFAULT_SEC) else it }
        choice(SettingsCategory.HOME_HEADER_TIME, times.map { "$it seconds" }, time) {
            prefs.headerIdleSec = times[it]
        }
        message("Off: Home's banner keeps going through its titles while it shows, wherever focus is. " +
            "Time on each title: how long every banner, on Home, Movies and TV Shows alike, shows a title before the next.")
    }

    /**
     * Settings › Picture-in-picture: whether leaving a playing channel for Home or Search keeps it playing there (top
     * right), and whether it starts with its sound. Read each time Live TV is left, so a change applies from the next
     * time; switched off while the picture plays, it stops now ([onPipChanged]).
     */
    private fun pipSection(panel: SidePanel): Unit = with(panel) {
        header(SettingsCategory.PICTURE_IN_PICTURE.title, SettingsCategory.PICTURE_IN_PICTURE.icon)
        row(PipRules.KEEP_LABEL, onOff(prefs.pipKeep), twoLines = true) {
            prefs.pipKeep = !prefs.pipKeep
            refresh()
            onPipChanged?.invoke()
        }
        row(PipRules.SOUND_LABEL, onOff(prefs.pipSound)) {
            prefs.pipSound = !prefs.pipSound
            refresh()
        }
        message("The channel playing on Live TV carries on in the top right of Home and Search. Select it for its " +
            "sound, Full screen and Close; hold Back to watch it full screen. Movies, TV Shows, My List and Settings " +
            "stop it. Off: leaving Live TV stops the channel.")
    }

    fun lookSection(panel: SidePanel): Unit = with(panel) {
        header("Look")
        // A small list behind an arrow: which pods show above the time bar.
        val chipsOpen = isPartOpen("chips")
        val hidden = prefs.hiddenChips
        val shownCount = CHIPS.count { it.first !in hidden }
        dropdownRow("Info pods", "$shownCount of ${CHIPS.size} shown", Rows.countBadge(shownCount, CHIPS.size), chipsOpen) {
            togglePart("chips")
            refresh()
        }
        if (chipsOpen) {
            CHIPS.forEach { (key, label) ->
                row(label, onOff(key !in hidden)) {
                    prefs.hiddenChips = if (key in hidden) hidden - key else hidden + key
                    onChipsChanged()
                    refresh()
                }.asSubRow()
            }
        }
        row("Glass look", onOff(prefs.glassLook)) {
            prefs.glassLook = !prefs.glassLook
            Glass.on = prefs.glassLook
            onLookChanged()
            Glass.overlay(root, Glass.Kind.PANEL)
            refresh()
        }
        if (prefs.glassLook) {
            // How often the glass copies the film: faster follows it more closely, slower is lighter on older sticks.
            // It applies to every film, 4K and 70 Mb/s and up included.
            val rates = listOf(2, 4, 8)
            choice(
                "Glass refresh",
                rates.map { "$it times a second" },
                rates.indexOf(prefs.glassRefreshPerSec).let { if (it < 0) 1 else it },
            ) {
                prefs.glassRefreshPerSec = rates[it]
            }.asSubRow()
        }
    }

    /**
     * Settings › Look's three glasses, under the look's own rows (App settings only; Glass look), each a dropdown row like
     * Info pods ([GlassShades.Kind]: App glass, Rail, cards & header, Playback glass) saying how it stands
     * ([GlassShades.badge]) and opening to its two rows ([GlassShades.Kind.controls]): Tint, how strong its colour layer
     * is (0% clear frosted glass, 100% solid), then for App glass and Playback glass Dimness, how much the glass is
     * darkened after that (even with no tint), and for Rail, cards & header Charcoal, where its tint's colour sits
     * between the background's own (0%) and charcoal (100%). 0% to 100% in 5% steps with left / right, applied at once
     * and saved ([GlassStyle.Shade]). Only the glass changes, never the words or icons on it. Playback glass is the
     * players' dimness settings' Default preset ([Dimmer]): while a custom is on there, its own glass shows over playback,
     * which Playback glass's rows say. Reset to defaults, last, puts all three back ([GlassShades.reset]). A step or a
     * reset rewrites the values and badges in place ([SidePanel.liveValue]): the rows stay, and so does the selection.
     */
    private fun glassShadesSection(panel: SidePanel): Unit = with(panel) {
        for (kind in GlassShades.Kind.entries) {
            val open = isPartOpen(kind.part)
            val shade = shades[kind]
            val dropdown = dropdownRow(kind.title, GlassShades.badge(kind, shade), shadeBadge(shade), open) {
                togglePart(kind.part)
                refresh()
            }
            liveValue {
                val now = shades[kind]
                setBadge(dropdown, GlassShades.badge(kind, now), shadeBadge(now))
            }
            if (!open) continue
            for (control in kind.controls) {
                stepper(control.label, { percent(control.of(shades[kind])) }) { dir ->
                    shades.step(kind, control, dir)
                    shades.applyTo(activity)
                }.asSubRow()
            }
            if (kind == GlassShades.Kind.PLAYBACK) {
                val preset = Dimmer(activity).active()
                if (!preset.isDefault) message(customOnNote(preset.name), maxLines = NOTE_LINES)
            }
        }
        // Same row as the others; the values change where they are, and the selection stays on it.
        row(RESET_SHADES) {
            shades.reset()
            shades.applyTo(activity)
            onShadesReset()
            updateValues()
        }
        message(SHADES_NOTE, maxLines = NOTE_LINES)
    }

    /** A glass's badge: gray while it lays nothing over the frosted glass, else the lighter blue. */
    private fun shadeBadge(shade: GlassStyle.Shade) = if (GlassShades.isClear(shade)) Rows.Badge.INACTIVE else Rows.Badge.LIGHT

    /**
     * Skipping: [full] adds the step lengths and Hold to speed up (Remote & skipping); the player shows the preview
     * only.
     */
    fun skippingSection(panel: SidePanel, full: Boolean): Unit = with(panel) {
        header("Skipping")
        if (full) {
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
        }
        row("Preview while skipping", onOff(prefs.seekPreviews)) {
            prefs.seekPreviews = !prefs.seekPreviews; refresh()
        }
    }

    fun defaultsSection(panel: SidePanel, autoSync: Boolean, resume: Boolean): Unit = with(panel) {
        header("Defaults")
        // Off first, as Never is in every other list of choices.
        val subLanguages = listOf("") + languages
        choice("Subtitle language", subLanguages.map { languageName(it) }, subLanguages.indexOf(prefs.subtitleLanguage)) {
            prefs.subtitleLanguage = subLanguages[it]
        }
        choice("Audio language", languages.map { languageName(it) }, languages.indexOf(prefs.audioLanguage)) {
            prefs.audioLanguage = languages[it]
        }
        if (autoSync) autoSyncRow(this)
        if (resume) resumeRow(this)
    }

    /** Resume where I left off: under Defaults in the VOD window, under Playback in its category. */
    private fun resumeRow(panel: SidePanel): Unit = with(panel) {
        val resumes = Prefs.Resume.entries
        choice("Resume where I left off", resumes.map { it.label }, resumes.indexOf(prefs.resume)) {
            prefs.resume = resumes[it]
        }
    }

    private fun autoSyncRow(panel: SidePanel): Unit = with(panel) {
        row("Auto sync subtitles", onOff(prefs.autoSync)) {
            prefs.autoSync = !prefs.autoSync
            onAutoSyncChanged()
            refresh()
        }
    }

    fun playbackSection(panel: SidePanel): Unit = with(panel) {
        header("Playback")
        playNextRow(this)
        matchFrameRateRow(this)
        passthroughRow(this)
        fasterDownloadsRow(this)
    }

    // Playback's rows one by one: its category puts Resume where I left off among them.
    private fun playNextRow(panel: SidePanel): Unit = with(panel) {
        row("Play next episode", onOff(prefs.playNextEpisode)) {
            prefs.playNextEpisode = !prefs.playNextEpisode; refresh()
        }
    }

    private fun matchFrameRateRow(panel: SidePanel): Unit = with(panel) {
        row("Match frame rate", onOff(prefs.matchFrameRate)) {
            prefs.matchFrameRate = !prefs.matchFrameRate; refresh()
        }
    }

    private fun passthroughRow(panel: SidePanel): Unit = with(panel) {
        row("Surround passthrough", onOff(prefs.passthrough)) {
            prefs.passthrough = !prefs.passthrough; refresh()
        }
    }

    /**
     * A film read with up to two requests at a time ([app.fireplayer.player.TwoRanges]); Off as it always was. The
     * player reads it as it's built, so it holds from the next film or source started.
     */
    private fun fasterDownloadsRow(panel: SidePanel): Unit = with(panel) {
        row("Faster downloads", onOff(prefs.fasterDownloads)) {
            prefs.fasterDownloads = !prefs.fasterDownloads; refresh()
            // The lists in memory rank again with the line speed measured for that many connections.
            (app.fireplayer.vod.Sources.get(activity) as? app.fireplayer.vod.sources.DefaultSourcesRepository)?.rerank()
        }
        cardNote(FASTER_DOWNLOADS_NOTE)
    }

    /**
     * What a film from the add-ons does when it keeps stopping to load ([app.fireplayer.vod.play.WhenBuffering]):
     * asks once (the answer is kept for that film), switches to the best source this connection keeps up with, or
     * stays on the source chosen. Read by the player each time the stall rules call for a switch.
     */
    private fun whenBufferingRow(panel: SidePanel): Unit = with(panel) {
        val modes = app.fireplayer.vod.play.WhenBuffering.entries
        val now = app.fireplayer.vod.play.WhenBuffering.named(prefs.whenBuffering)
        choice(SettingsCategory.WHEN_BUFFERING, modes.map { it.label }, modes.indexOf(now)) {
            prefs.whenBuffering = modes[it].name
        }
    }

    fun fontSection(panel: SidePanel): Unit = with(panel) {
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
            }.asSubRow()
        }
    }

    /**
     * The OpenSubtitles and TMDB keys (Accounts & services). Each key's badge says whether it works, not just whether
     * it's saved: "Set · not checked", "Working", "Not accepted" ([CredentialChecks]). A saved key is never shown.
     * Auto sync subtitles, once at the top here, is under Subtitles & audio.
     */
    fun credentialsSection(panel: SidePanel): Unit = with(panel) {
        header("Online subtitles and TMDB API")
        val os = checks.status(CredentialChecks.Service.OPENSUBTITLES)
        keyRow("OpenSubtitles API key", KeyStore.Key.OPENSUBTITLES, whereFrom = "From opensubtitles.com › API consumers", badge = badgeFor(os.state)) {
            checks.check(CredentialChecks.Service.OPENSUBTITLES) { if (isOpen) refresh() }
        }
        // Optional: signing in raises how many subtitles can be downloaded a day.
        keyRow("OpenSubtitles username", KeyStore.Key.OPENSUBTITLES_USER, whereFrom = OPTIONAL_HINT) {
            checks.check(CredentialChecks.Service.OPENSUBTITLES) { if (isOpen) refresh() }
        }
        keyRow("OpenSubtitles password", KeyStore.Key.OPENSUBTITLES_PASSWORD, whereFrom = OPTIONAL_HINT, password = true) {
            checks.check(CredentialChecks.Service.OPENSUBTITLES) { if (isOpen) refresh() }
        }
        val tmdb = checks.status(CredentialChecks.Service.TMDB)
        keyRow(
            "TMDB API key",
            KeyStore.Key.TMDB,
            whereFrom = "From themoviedb.org › Settings › API",
            title = "TMDB API key or read access token",
            badge = badgeFor(tmdb.state),
        ) {
            // TiviMate's titles start being looked up with it.
            TitleService.start(activity)
            checks.check(CredentialChecks.Service.TMDB) { if (isOpen) refresh() }
        }
        // What's wrong with a key, after the keys, so their card stays whole.
        problemOf(os)?.let { message("OpenSubtitles: $it.") }
        problemOf(tmdb)?.let { message("TMDB: $it.") }
        if (os.state != CredentialChecks.State.NOT_SET || tmdb.state != CredentialChecks.State.NOT_SET) {
            // While it checks, dimmed and doing nothing, as the Update buttons while they update.
            val row = row("Check the keys now", if (keysChecking()) CHECKING else null) {
                if (keysChecking()) return@row
                for (s in CredentialChecks.Service.entries) checks.check(s) { if (isOpen) refresh() }
                refresh()
            }
            if (keysChecking()) dim(row)
        }
        // TMDB asks apps that use its API to say so.
        message(TMDB_NOTICE)
    }

    private fun keysChecking(): Boolean = CredentialChecks.Service.entries.any { checks.status(it).state == CredentialChecks.State.CHECKING }

    fun skipIntroSection(panel: SidePanel): Unit = with(panel) {
        header("Skip intro and credits")
        val skipDetailsShown = isPartOpen("skipDetails")
        val skipTurned = skipArrowTurned
        skipArrowTurned = false
        rowWithArrow(
            "Skip buttons",
            onOff(prefs.skipIntro),
            expanded = skipDetailsShown,
            turned = skipTurned,
            onClick = { prefs.skipIntro = !prefs.skipIntro; refresh() },
            onArrow = { togglePart("skipDetails"); skipArrowTurned = true; refresh() },
        )
        if (skipDetailsShown) {
            message("Skip intro and Skip recap, Next episode at the credits and Exit after a film's credits. " +
                "Times from the file's chapters, else SkipDB and IntroDB, with a TMDB key.")
        }
        val counts = COUNTDOWNS
        choice("Next episode / Exit countdown", counts.map { if (it == 0) "Until the end" else "$it seconds" }, counts.indexOf(prefs.autoCountdownSec).let { if (it < 0) 1 else it }) {
            prefs.autoCountdownSec = counts[it]
        }
    }

    /**
     * Statuses ▾ (the player windows): OpenSubtitles, TMDB API and the font sent from the phone. Each opens the
     * window where it's managed (Accounts & services, Subtitle font). Opening the list checks keys that haven't been
     * checked yet.
     */
    fun statusesSection(panel: SidePanel): Unit = with(panel) {
        header("Statuses")
        val statusesOpen = isPartOpen("statuses")
        val os = checks.status(CredentialChecks.Service.OPENSUBTITLES)
        val tmdb = checks.status(CredentialChecks.Service.TMDB)
        val states = listOf(os.state, tmdb.state)
        val (summary, style) = when {
            states.any { it == CredentialChecks.State.NOT_ACCEPTED } -> "Needs a look" to Rows.Badge.PROBLEM
            states.any { it == CredentialChecks.State.CHECKING } -> "Checking…" to Rows.Badge.BUSY
            states.all { it == CredentialChecks.State.WORKING } -> "All working" to Rows.Badge.ACTIVE
            states.all { it == CredentialChecks.State.NOT_SET } -> "Not set up" to Rows.Badge.INACTIVE
            else -> states.count { it == CredentialChecks.State.WORKING }.let { n -> "$n of ${states.size} working" to Rows.countBadge(n, states.size) }
        }
        dropdownRow("Services and font", summary, style, statusesOpen) {
            togglePart("statuses")
            if (isPartOpen("statuses")) checks.checkUnchecked { if (isOpen) refresh() }
            refresh()
        }
        if (statusesOpen) {
            for ((service, st) in listOf(CredentialChecks.Service.OPENSUBTITLES to os, CredentialChecks.Service.TMDB to tmdb)) {
                val (text, badge) = badgeFor(st.state)
                badgeRow(service.label, text, badge) {
                    openSub(SettingsCategory.ACCOUNTS.title) { credentialsSection(this) }
                }.asSubRow()
            }
            val font = importedFontName
            // Said as in Subtitle font, the window it opens.
            badgeRow("Font from your phone", activeOrNot(font != null), if (font != null) Rows.Badge.ACTIVE else Rows.Badge.INACTIVE) {
                openSub("Subtitle font") { fontSection(this) }
            }.asSubRow()
        }
    }

    /** App: Check for updates; in a player's window also "App settings" (every setting, in its categories). */
    fun appSection(panel: SidePanel, allSettings: Boolean): Unit = with(panel) {
        header("App")
        val row = row("Check for updates", updateStatus ?: "Version ${updater.installedName}") {
            // Already checking: dimmed, and a second press does nothing.
            if (updateStatus == CHECKING) return@row
            if (!updater.offered) {
                updateStatus = "Not in test builds"; refresh(); return@row
            }
            updateStatus = CHECKING; refresh()
            updater.check(onFailed = { updateStatus = "Couldn't check"; if (isOpen) refresh() }) { release ->
                updateStatus = if (release == null) "Up to date" else null
                if (isOpen) refresh()
                release?.let { updater.offer(it) }
            }
        }
        if (updateStatus == CHECKING) dim(row)
        // The settings these windows leave out stay here (user: preserved, not removed).
        if (allSettings) {
            row(SettingsCategory.LIST_TITLE) { openSub(SettingsCategory.LIST_TITLE) { renderCategories(this) } }
        }
    }

    private fun badgeFor(state: CredentialChecks.State): Pair<String, Rows.Badge> = state.label to when (state) {
        CredentialChecks.State.NOT_SET -> Rows.Badge.INACTIVE
        CredentialChecks.State.NOT_CHECKED -> Rows.Badge.LIGHT
        CredentialChecks.State.CHECKING -> Rows.Badge.BUSY
        CredentialChecks.State.WORKING -> Rows.Badge.ACTIVE
        CredentialChecks.State.NOT_ACCEPTED -> Rows.Badge.PROBLEM
        CredentialChecks.State.UNREACHABLE -> Rows.Badge.INACTIVE
    }

    private fun problemOf(st: CredentialChecks.Status): String? = when (st.state) {
        CredentialChecks.State.NOT_ACCEPTED, CredentialChecks.State.UNREACHABLE -> st.problem
        else -> null
    }

    /**
     * TiviMate's English titles: the switch, with an arrow beside it for the set-up details (hidden each time the
     * window opens); under it, while on, how far the titles are and a button that sets the server to work now.
     */
    private fun renderTitles(panel: SidePanel): Unit = with(panel) {
        titlesPanel = panel
        header("TiviMate titles")
        if (session != titlesSession) {
            titlesSession = session
            detailsShown = false
            playlistNoteShown = false
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
        // The arrow beside it tells what happens to a playlist taken out of TiviMate.
        val noteTurned = playlistArrowTurned
        playlistArrowTurned = false
        rowWithArrow("Check playlists now", checkValue, expanded = playlistNoteShown, turned = noteTurned,
            onClick = {
                if (prefs.keys.isSet(KeyStore.Key.TMDB)) {
                    TitleService.checkNow(activity)
                    if (inPlayer) checkNote = "Starts once the film is closed." else checkValue = "Started"
                }
                refresh()
            },
            onArrow = { playlistNoteShown = !playlistNoteShown; playlistArrowTurned = true; refresh() })
        when {
            !prefs.keys.isSet(KeyStore.Key.TMDB) -> message("Needs a TMDB API key (Accounts & services).")
            checkNote != null -> message(checkNote!!)
        }
        if (playlistNoteShown) message("Removed a playlist from TiviMate? Its English names are deleted here a week after TiviMate last used it, or now from this list.")
        // Each playlist's English names, and a tick once every one of its titles has been checked; one taken out of
        // TiviMate is forgotten by itself after a week, or here now.
        val playlists = titleCounts.orEmpty()
        if (playlists.isNotEmpty()) {
            header("Playlists")
            playlists.forEach { p ->
                val onClick = {
                    FireDialog.choose(activity, p.server, listOf("Delete its English names", "Keep them"), 1, besidePanel = true) { pick ->
                        if (pick != 0) return@choose
                        titleCounts = titleCounts?.filter { it.server != p.server }
                        refresh()
                        reader.execute {
                            runCatching { (book ?: TitleBook(activity.applicationContext).also { book = it }).forget(p.server) }
                        }
                    }
                }
                rowWithPill(p.server, "${TitleStatus.count(p.renamed)} renamed", if (p.waiting == 0) tickPill() else null) { onClick() }
            }
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
                        val first = titleCounts == null
                        if (counts != null) titleCounts = counts
                        if (first && !counts.isNullOrEmpty()) titlesPanel?.refresh()
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
     * A key or sign-in row: "Set" in blue once saved, "Not set" in gray before, the words the keys' own checks use
     * ([badge] in their place, such as the TMDB check's). Its box starts empty, so a saved key is never shown, and
     * says where to get one (or that one is saved); confirmed empty, it leaves a saved key as it is. [onSaved] follows
     * a new one.
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
        val (text, style) = badge ?: (if (set) SET to Rows.Badge.ACTIVE else NOT_SET to Rows.Badge.INACTIVE)
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

        header("Subtitle style")
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

        header("Presets")
        // Saving only makes sense once something has been changed; then it's blue and says so, as Save custom does.
        val unsaved = style.name == SubtitleStyle.CUSTOM_NAME && presets.customIsNew()
        row("Save as preset", if (unsaved) "Unsaved" else null, enabled = unsaved) {
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
        }.isSelected = unsaved
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
    private fun View.indent(): View = asSubRow()

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
        /** Seconds; 0 is "Until the end": the card stays up and fills until the episode really ends. */
        private val COUNTDOWNS = listOf(5, 10, 15, 20, 30, 0)
        const val FILE_POSITION = "As in the file"
        /** The pods above the time bar, by their keys in [Prefs.hiddenChips]. */
        val CHIPS = listOf(
            "quality" to "Resolution", "fps" to "Frame rate", "format" to "Picture format & data rate",
            "sound" to "Sound", "source" to "Source",
        )
        fun onOff(on: Boolean) = if (on) "On" else "Off"

        /** A tint, dimness or charcoal as its row shows it: "55%". */
        fun percent(value: Int) = "$value%"

        /**
         * The most lines a note in Look or the Screen dimmer takes at the window's width: short enough that, under the
         * last row, it shows in full once that row is selected (the list keeps the selected row in its middle).
         */
        const val NOTE_LINES = 2

        /** Settings › Look, under the three glasses: what their rows do, in a line. */
        internal const val SHADES_NOTE = "Tint colours the glass; Dimness and Charcoal darken it."

        /** The last of Look's glass rows: all three glasses back to their defaults ([GlassShades.reset]). */
        const val RESET_SHADES = "Reset to defaults"

        /** Under Playback glass's rows while the players' Screen dimmer has a custom on: whose glass shows over playback. */
        fun customOnNote(name: String) = "Over playback: \"$name\", from the Screen dimmer."

        /** In Faster downloads' card, under it: what it does, in a line. */
        internal const val FASTER_DOWNLOADS_NOTE = "Two connections, if the server allows."

        private const val ACTIVE = "Active"
        private const val INACTIVE = "Inactive"
        private const val SET = "Set"
        private const val NOT_SET = "Not set"
        private const val CHECKING = "Checking…"
        private const val SAVED_HINT = "Saved · type a new one to replace it"
        private const val OPTIONAL_HINT = "Optional · more downloads a day"
        private const val TMDB_NOTICE = "This product uses the TMDB API but is not endorsed or certified by TMDB."
        private const val STATUS_EVERY_MS = 2_500L

        /** A skip step in words, as the other choices of a time are: "5 seconds", "1 minute", "10 minutes". */
        fun formatStep(sec: Int): String = when {
            sec < 60 -> "$sec seconds"
            sec == 60 -> "1 minute"
            sec % 60 == 0 -> "${sec / 60} minutes"
            else -> "${sec / 60} min ${sec % 60} s"
        }

        fun nearest(options: List<Float>, value: Float): Float =
            options.minByOrNull { kotlin.math.abs(it - value) } ?: value
    }
}

/**
 * Remote › Menu button opens: Settings or Subtitle style. One row and one saved value ([Prefs.menuOpensStyle]), first under
 * every Remote header (the player windows, Live FirePlayer Settings, the Settings tab's Live TV and Remote & skipping).
 */
fun menuButtonRow(panel: SidePanel, context: android.content.Context): Unit = with(panel) {
    val prefs = Prefs(context)
    choice("Menu button opens", listOf("Settings", "Subtitle style"), if (prefs.menuOpensStyle) 1 else 0) {
        prefs.menuOpensStyle = it == 1
    }
}
