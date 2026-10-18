package app.fireplayer.data

import android.content.Context
import android.content.SharedPreferences

/** All user settings, stored in one SharedPreferences file. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("fireplayer", Context.MODE_PRIVATE)

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

    var matchFrameRate: Boolean
        get() = sp.getBoolean("match_frame_rate", true)
        set(v) = sp.edit().putBoolean("match_frame_rate", v).apply()

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

    var openSubtitlesApiKey: String
        get() = sp.getString("os_api_key", "") ?: ""
        set(v) = sp.edit().putString("os_api_key", v.trim()).apply()

    var openSubtitlesUser: String
        get() = sp.getString("os_user", "") ?: ""
        set(v) = sp.edit().putString("os_user", v.trim()).apply()

    var openSubtitlesPassword: String
        get() = sp.getString("os_pass", "") ?: ""
        set(v) = sp.edit().putString("os_pass", v).apply()

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

    // Resume points, keyed by the video's URI.
    fun savedPosition(key: String): Long = sp.getLong("pos:$key", 0L)

    fun savePosition(key: String, positionMs: Long) {
        val editor = sp.edit()
        if (positionMs <= 0L) editor.remove("pos:$key") else editor.putLong("pos:$key", positionMs)
        editor.apply()
    }

    /** Name of the subtitle font sent from the phone, or "" when there isn't one. */
    var customFontName: String
        get() = sp.getString("custom_font", "") ?: ""
        set(v) = sp.edit().putString("custom_font", v).apply()
}
