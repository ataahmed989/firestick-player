package app.fireplayer.data

import android.content.Context
import android.content.SharedPreferences

/** All user settings, stored in one SharedPreferences file; the keys and sign-in in one of their own ([KeyStore]). */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(KeyStore.OLD_FILE, Context.MODE_PRIVATE)

    init {
        forgetPlayerMatches()
    }

    enum class Resume(val label: String) { ASK("Ask"), ALWAYS("Always"), NEVER("Never") }

    var dpadStepSec: Int
        get() = sp.getInt("dpad_step", 10)
        set(v) = sp.edit().putInt("dpad_step", v).apply()

    var ffStepSec: Int
        get() = sp.getInt("ff_step", 60)
        set(v) = sp.edit().putInt("ff_step", v).apply()

    var holdSpeedUp: Boolean
        get() = sp.getBoolean("hold_speed_up", true)
        set(v) = sp.edit().putBoolean("hold_speed_up", v).apply()

    /** What the remote's menu button (three lines) opens during playback. */
    var menuOpensStyle: Boolean
        get() = sp.getBoolean("menu_opens_style", false)
        set(v) = sp.edit().putBoolean("menu_opens_style", v).apply()

    /** ISO 639-1 code, or "" for off. */
    var subtitleLanguage: String
        get() = sp.getString("sub_lang", "en") ?: "en"
        set(v) = sp.edit().putString("sub_lang", v).apply()

    var audioLanguage: String
        get() = sp.getString("audio_lang", "en") ?: "en"
        set(v) = sp.edit().putString("audio_lang", v).apply()

    var resume: Resume
        get() = runCatching { Resume.valueOf(sp.getString("resume", Resume.ASK.name)!!) }
            .getOrDefault(Resume.ASK)
        set(v) = sp.edit().putString("resume", v.name).apply()

    /** A picture of where a jump will land, shown above the time bar while skipping. */
    var seekPreviews: Boolean
        get() = sp.getBoolean("seek_previews", true)
        set(v) = sp.edit().putBoolean("seek_previews", v).apply()

    /** Finds the episode after the one TiviMate opened, from the provider, and plays it next. */
    var playNextEpisode: Boolean
        get() = sp.getBoolean("play_next_episode", true)
        set(v) = sp.edit().putBoolean("play_next_episode", v).apply()

    /** The info pods above the time bar left out by hand: "quality", "fps", "sound", "source". */
    var hiddenChips: Set<String>
        get() = sp.getStringSet("chips_hidden", emptySet())?.toSet().orEmpty()
        set(v) = sp.edit().putStringSet("chips_hidden", v).apply()

    /** Skip intro / recap / credits buttons, and the end card at the credits, from chapters or TheIntroDB. */
    var skipIntro: Boolean
        get() = sp.getBoolean("skip_intro", true)
        set(v) = sp.edit().putBoolean("skip_intro", v).apply()

    /** How long the Next episode / Exit card counts before it goes on by itself. */
    var autoCountdownSec: Int
        get() = sp.getInt("auto_countdown", 10)
        set(v) = sp.edit().putInt("auto_countdown", v).apply()

    var matchFrameRate: Boolean
        get() = sp.getBoolean("match_frame_rate", true)
        set(v) = sp.edit().putBoolean("match_frame_rate", v).apply()

    /** The optional see-through glass look for the controls and windows; off keeps the classic look. */
    var glassLook: Boolean
        get() = sp.getBoolean("glass_look", false)
        set(v) = sp.edit().putBoolean("glass_look", v).apply()

    /** How many times a second the glass copies the playing film (Settings, Look, Glass refresh): 2, 4 or 8. */
    var glassRefreshPerSec: Int
        get() = sp.getInt("glass_refresh", 4)
        set(v) = sp.edit().putInt("glass_refresh", v).apply()

    var passthrough: Boolean
        get() = sp.getBoolean("passthrough", true)
        set(v) = sp.edit().putBoolean("passthrough", v).apply()

    /**
     * "Keep embedded styling": ASS/SSA is drawn with libass (positions, fonts, effects) and other
     * subtitles keep their own colours. Off, the chosen preset always wins.
     */
    var keepAssStyling: Boolean
        get() = sp.getBoolean("keep_ass", true)
        set(v) = sp.edit().putBoolean("keep_ass", v).apply()

    /** The user's keys and sign-in, kept in a file of their own and saved with [KeyStore.save]. */
    val keys: KeyStore = KeyStore.of(context)

    val openSubtitlesApiKey: String get() = keys[KeyStore.Key.OPENSUBTITLES]

    val openSubtitlesUser: String get() = keys[KeyStore.Key.OPENSUBTITLES_USER]

    val openSubtitlesPassword: String get() = keys[KeyStore.Key.OPENSUBTITLES_PASSWORD]

    /** The user's own TMDB key (the short API key or the long read access token), "" for none. */
    val tmdbApiKey: String get() = keys[KeyStore.Key.TMDB]

    /**
     * The player used to rename films with TMDB and kept every match here. TiviMate now gets the names already right
     * (see [app.fireplayer.titles.TitleService]), so they're cleared out once, which keeps this file small to read.
     */
    private fun forgetPlayerMatches() {
        if (sp.getInt("tmdb_rules", 1) >= PLAYER_MATCHES_GONE) return
        val edit = sp.edit()
        sp.all.keys.filter { it.startsWith("tmdb|") }.forEach { edit.remove(it) }
        edit.putInt("tmdb_rules", PLAYER_MATCHES_GONE).apply()
    }

    /** Whether TiviMate's films and shows are renamed with TMDB (see [app.fireplayer.titles.TitleService]). */
    var tivimateTitles: Boolean
        get() = sp.getBoolean("tivimate_titles", true)
        set(v) = sp.edit().putBoolean("tivimate_titles", v).apply()

    /** The language Find subtitles searches in; "" until one is picked, then the default subtitle language is used. */
    var subtitleSearchLanguage: String
        get() = sp.getString("sub_search_lang", "") ?: ""
        set(v) = sp.edit().putString("sub_search_lang", v).apply()

    // Subtitle style presets, kept as JSON (see SubtitleStyle).
    var subtitlePresetsJson: String
        get() = sp.getString("sub_presets", "[]") ?: "[]"
        set(v) = sp.edit().putString("sub_presets", v).apply()

    var activeSubtitlePreset: String
        get() = sp.getString("sub_active", "Netflix") ?: "Netflix"
        set(v) = sp.edit().putString("sub_active", v).apply()

    var customSubtitleStyleJson: String
        get() = sp.getString("sub_custom", "") ?: ""
        set(v) = sp.edit().putString("sub_custom", v).apply()

    var displayMode: Int
        get() = sp.getInt("display_mode", 0)
        set(v) = sp.edit().putInt("display_mode", v).apply()

    // The screen dimmer's choices are kept by Dimmer.

    // Resume points, keyed by the video's URI.
    fun savedPosition(key: String): Long = sp.getLong("pos:$key", 0L)

    fun savePosition(key: String, positionMs: Long) {
        val editor = sp.edit()
        if (positionMs <= 0L) editor.remove("pos:$key") else editor.putLong("pos:$key", positionMs)
        editor.apply()
    }

    /** Downloaded subtitles are lined up with the film's dialogue by themselves (see [app.fireplayer.subs.AutoSync]). */
    var autoSync: Boolean
        get() = sp.getBoolean("auto_sync", true)
        set(v) = sp.edit().putBoolean("auto_sync", v).apply()

    // The subtitle delay auto sync found (ms), keyed by the subtitle file's name and the film.
    fun syncedDelayMs(key: String): Long? = if (sp.contains("sync|$key")) sp.getLong("sync|$key", 0L) else null

    fun saveSyncedDelay(key: String, ms: Long) = sp.edit().putLong("sync|$key", ms).apply()

    fun forgetSyncedDelay(key: String) = sp.edit().remove("sync|$key").remove("sync|$key|at").apply()

    // ...and by film position once the timing changed along the film (an ad break): see OffsetTracker.encode.
    fun syncedSegments(key: String): String? = sp.getString("sync|$key|at", null)

    fun saveSyncedSegments(key: String, text: String) = sp.edit().putString("sync|$key|at", text).apply()

    /** A delay set by hand for a downloaded subtitle on one video, used again when it's chosen another time. */
    fun handDelayMs(key: String): Long? = if (sp.contains("subdelay|$key")) sp.getLong("subdelay|$key", 0L) else null

    fun saveHandDelay(key: String, ms: Long) = sp.edit().putLong("subdelay|$key", ms).apply()

    fun forgetHandDelay(key: String) = sp.edit().remove("subdelay|$key").apply()

    /** A downloaded subtitle file was let go: what was found or set for its timing goes with it. */
    fun forgetSubtitleTimes(fileName: String) {
        val gone = sp.all.keys.filter { it.startsWith("sync|$fileName|") || it.startsWith("subdelay|$fileName|") }
        if (gone.isNotEmpty()) sp.edit().apply { gone.forEach { remove(it) } }.apply()
    }

    /** Name of the subtitle font sent from the phone, or "" when there isn't one. */
    var customFontName: String
        get() = sp.getString("custom_font", "") ?: ""
        set(v) = sp.edit().putString("custom_font", v).apply()

    private companion object {
        const val PLAYER_MATCHES_GONE = 100
    }
}
