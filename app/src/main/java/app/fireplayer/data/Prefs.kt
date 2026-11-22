package app.fireplayer.data

import android.content.Context
import android.content.SharedPreferences

/** All user settings, stored in one SharedPreferences file; the keys and sign-in in one of their own ([KeyStore]). */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(KeyStore.OLD_FILE, Context.MODE_PRIVATE)

    /** The file itself, for small records kept beside the settings (key check results, [CredentialChecks]). */
    internal val raw: SharedPreferences get() = sp

    init {
        forgetPlayerMatches()
        previewsOffOnce()
    }

    enum class Resume(val label: String) { ASK("Ask"), ALWAYS("Always"), NEVER("Never") }

    /**
     * Settings › Picture-in-picture: leaving a playing channel on Live TV for Home or Search keeps it playing there, top
     * right ([app.fireplayer.live.ui.PipRules]); off, leaving Live TV stops the channel, as it always did.
     */
    var pipKeep: Boolean
        get() = sp.getBoolean("pip_keep", app.fireplayer.live.ui.PipRules.KEEP_DEFAULT)
        set(v) = sp.edit().putBoolean("pip_keep", v).apply()

    /** Settings › Picture-in-picture: the picture starts with its sound (off: muted until Volume is pressed). */
    var pipSound: Boolean
        get() = sp.getBoolean("pip_sound", app.fireplayer.live.ui.PipRules.SOUND_DEFAULT)
        set(v) = sp.edit().putBoolean("pip_sound", v).apply()

    /** The streaming-service pages visited last, newest first ([app.fireplayer.vod.data.Services.encode]): the rail's two. */
    var recentServices: String
        get() = sp.getString("recent_services", "") ?: ""
        set(v) = sp.edit().putString("recent_services", v).apply()

    /** Settings › Rows › Service shortcuts: whether the rail shows its service shortcuts (on unless switched off). */
    var serviceShortcuts: Boolean
        get() = sp.getBoolean("service_shortcuts", true)
        set(v) = sp.edit().putBoolean("service_shortcuts", v).apply()

    /** The services pinned to the rail's shortcuts, top first ([app.fireplayer.vod.data.Services.pin]). */
    var pinnedServices: String
        get() = sp.getString("pinned_services", "") ?: ""
        set(v) = sp.edit().putString("pinned_services", v).apply()

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

    /** A picture of where a jump will land, shown above the time bar while skipping. Off unless turned on. */
    var seekPreviews: Boolean
        get() = sp.getBoolean("seek_previews", app.fireplayer.player.PreviewGuard.DEFAULT_ON)
        set(v) = sp.edit().putBoolean("seek_previews", v).apply()

    /** Finds the episode after the one TiviMate opened, from the provider, and plays it next. */
    var playNextEpisode: Boolean
        get() = sp.getBoolean("play_next_episode", true)
        set(v) = sp.edit().putBoolean("play_next_episode", v).apply()

    /** The info pods above the time bar left out by hand: "quality", "fps", "format", "sound", "source". */
    var hiddenChips: Set<String>
        get() = sp.getStringSet("chips_hidden", emptySet())?.toSet().orEmpty()
        set(v) = sp.edit().putStringSet("chips_hidden", v).apply()

    /** Skip intro / recap / credits buttons, and the end card at the credits, from chapters or TheIntroDB. */
    var skipIntro: Boolean
        get() = sp.getBoolean("skip_intro", true)
        set(v) = sp.edit().putBoolean("skip_intro", v).apply()

    /** How long the Next episode / Exit card counts before it goes on by itself; 0: until the episode's real end. */
    var autoCountdownSec: Int
        get() = sp.getInt("auto_countdown", 10)
        set(v) = sp.edit().putInt("auto_countdown", v).apply()

    /**
     * Settings › Playback › When a film keeps buffering: the name of a [app.fireplayer.vod.play.WhenBuffering]
     * ("ASK" until it's set).
     */
    var whenBuffering: String
        get() = sp.getString("when_buffering", null) ?: "ASK"
        set(v) = sp.edit().putString("when_buffering", v).apply()

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

    /**
     * Settings › Home Header: Home's banner shows the focused Continue Watching title (on), or always goes through its
     * own titles while it shows (off). Movies and TV Shows always match.
     */
    var homeHeaderMatchesContinue: Boolean
        get() = sp.getBoolean("home_header_match_continue", true)
        set(v) = sp.edit().putBoolean("home_header_match_continue", v).apply()

    /**
     * Settings › Home Header › Time on each title: seconds every tab's banner shows a title while idle (one of
     * [app.fireplayer.vod.ui.VodSizes.HEADER_IDLE_SECONDS]).
     */
    var headerIdleSec: Int
        get() = sp.getInt("header_idle_sec", app.fireplayer.vod.ui.VodSizes.HEADER_IDLE_DEFAULT_SEC)
        set(v) = sp.edit().putInt("header_idle_sec", v).apply()

    var passthrough: Boolean
        get() = sp.getBoolean("passthrough", true)
        set(v) = sp.edit().putBoolean("passthrough", v).apply()

    /**
     * "Faster downloads (2 connections)": a film is read with up to two requests at a time
     * ([app.fireplayer.player.TwoRanges]). Off unless the viewer turns it on; read as each film's player is built.
     */
    var fasterDownloads: Boolean
        get() = sp.getBoolean("faster_downloads", false)
        set(v) = sp.edit().putBoolean("faster_downloads", v).apply()

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

    /**
     * Preview while skipping was On by default and is Off now ([app.fireplayer.player.PreviewGuard.DEFAULT_ON]): where it
     * was left On it's turned Off once, so the new default shows ([app.fireplayer.player.PreviewGuard.offOnce]). It's the
     * viewer's to turn back on.
     */
    private fun previewsOffOnce() {
        if (sp.getBoolean(PREVIEWS_OFF_ONCE, false)) return
        val stored = if (sp.contains("seek_previews")) sp.getBoolean("seek_previews", false) else null
        val edit = sp.edit().putBoolean(PREVIEWS_OFF_ONCE, true)
        if (app.fireplayer.player.PreviewGuard.offOnce(stored, done = false)) edit.putBoolean("seek_previews", false)
        edit.apply()
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

    // The one subtitle delay for the whole film (ms) that older versions kept, keyed by the subtitle file's name and the
    // film: still read when that's all there is; nothing writes it any more (see [saveSyncedSegments]).
    fun syncedDelayMs(key: String): Long? = if (sp.contains("sync|$key")) sp.getLong("sync|$key", 0L) else null

    /**
     * Everything auto sync found for a subtitle and film goes: the delays, the track it picked, the talking's
     * fine-tuning of that track, and the track the talking turned down.
     */
    fun forgetSyncedDelay(key: String) = sp.edit().remove("sync|$key").remove("sync|$key|at").remove("sync|$key|track")
        .remove("sync|$key|trim").remove("sync|$key|drop").apply()

    // The delays auto sync found for a subtitle and film, by film position (more than one once the timing changed along
    // the film: an ad break): see OffsetTracker.encode.
    fun syncedSegments(key: String): String? = sp.getString("sync|$key|at", null)

    /**
     * Jumps auto sync found in a show's episodes (the subtitles needing this much more, or less, delay after a bumper
     * or an ad), newest first: tried first after a break in its other episodes.
     */
    fun showJumps(show: String): List<Long> =
        sp.getString("syncjumps|$show", null)?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()

    fun saveShowJumps(show: String, jumps: List<Long>) = sp.edit().putString("syncjumps|$show", jumps.joinToString(",")).apply()

    /** The delays auto sync found, by film position; an older version's one delay for the pair is replaced by them. */
    fun saveSyncedSegments(key: String, text: String) = sp.edit().putString("sync|$key|at", text).remove("sync|$key").apply()

    /**
     * The delay a show's last episode started with (ms), for subtitles from the same source ([source]: the release they
     * were made for, less the episode): a new episode starts from it, checked as it plays.
     */
    fun showStartMs(show: String, source: String): Long? =
        "syncstart|$show|$source".let { if (sp.contains(it)) sp.getLong(it, 0L) else null }

    fun saveShowStart(show: String, source: String, ms: Long) = sp.edit().putLong("syncstart|$show|$source", ms).apply()

    /**
     * What auto sync lines up against for a show (or one film without one), as chosen in the Subtitle delay window: one
     * of the film's own subtitle tracks (its key), "speech", or null for the best of them (auto).
     */
    fun syncWith(scope: String): String? = sp.getString("syncwith|$scope", null)

    fun saveSyncWith(scope: String, choice: String?) =
        sp.edit().apply { if (choice == null) remove("syncwith|$scope") else putString("syncwith|$scope", choice) }.apply()

    /** The film's own subtitle track auto sync picked for a subtitle and film, so it picks the same one next time. */
    fun syncTrack(key: String): String? = sp.getString("sync|$key|track", null)

    fun saveSyncTrack(key: String, track: String) = sp.edit().putString("sync|$key|track", track).apply()

    /**
     * How much the talking fine-tuned the film's own subtitle track [first] by (ms, [second]) for a subtitle and film, so
     * it's put in again next time on the same track.
     */
    fun syncTrim(key: String): Pair<String, Long>? {
        val v = sp.getString("sync|$key|trim", null) ?: return null
        val track = v.substringBeforeLast(':', "").ifEmpty { return null }
        val ms = v.substringAfterLast(':').toLongOrNull() ?: return null
        return track to ms
    }

    fun saveSyncTrim(key: String, track: String, ms: Long) = sp.edit().putString("sync|$key|trim", "$track:$ms").apply()

    /**
     * The film's own subtitle track the talking disagreed with for a subtitle and film (it's timed for another cut):
     * not lined up against the next time either, till the viewer picks it or starts afresh.
     */
    fun droppedSyncTrack(key: String): String? = sp.getString("sync|$key|drop", null)

    /** [track] is turned down for a subtitle and film: no longer the one picked, and its fine-tuning goes with it. */
    fun dropSyncTrack(key: String, track: String) =
        sp.edit().remove("sync|$key|track").remove("sync|$key|trim").putString("sync|$key|drop", track).apply()

    fun forgetDroppedSyncTrack(key: String) = sp.edit().remove("sync|$key|drop").apply()

    /** A delay set by hand for a downloaded subtitle on one video, used again when it's chosen another time. */
    fun handDelayMs(key: String): Long? = if (sp.contains("subdelay|$key")) sp.getLong("subdelay|$key", 0L) else null

    fun saveHandDelay(key: String, ms: Long) = sp.edit().putLong("subdelay|$key", ms).apply()

    fun forgetHandDelay(key: String) = sp.edit().remove("subdelay|$key").apply()

    /**
     * Downloaded subtitles [file]'s timing as found or set for one copy of a film ([from], a film key) kept for another
     * copy of it ([to]) as well: the delays where they were found, and one set by hand. Not which of the film's own tracks
     * they were matched with: the other copy's tracks are its own.
     */
    fun copySubtitleTiming(file: String, from: String, to: String) {
        val all = sp.all
        val e = sp.edit()
        for (suffix in listOf("", "|at")) {
            when (val v = all["sync|$file|$from$suffix"]) {
                is Long -> e.putLong("sync|$file|$to$suffix", v)
                is String -> e.putString("sync|$file|$to$suffix", v)
            }
        }
        (all["subdelay|$file|$from"] as? Long)?.let { e.putLong("subdelay|$file|$to", it) }
        e.apply()
    }

    /**
     * Downloaded subtitle files were let go: what was found or set for the timing of every one no longer kept goes
     * with them, in one sweep ([files]: the names still kept), and a "Sync with" choice made for a single film that has
     * none left ([videos]: the films that still have some). A show's choice, jumps and starting delays stay.
     */
    fun keepSubtitleTimes(files: Set<String>, videos: Set<String>) {
        val gone = staleSubtitleTimes(sp.all.keys, files, videos)
        if (gone.isNotEmpty()) sp.edit().apply { gone.forEach { remove(it) } }.apply()
    }

    /** Name of the subtitle font sent from the phone, or "" when there isn't one. */
    var customFontName: String
        get() = sp.getString("custom_font", "") ?: ""
        set(v) = sp.edit().putString("custom_font", v).apply()

    companion object {
        private const val PLAYER_MATCHES_GONE = 100
        private const val PREVIEWS_OFF_ONCE = "seek_previews_off_once"

        /**
         * Of the settings' [keys], those kept for a downloaded subtitle file not in [files] ("sync|<file>|…" and
         * "subdelay|<file>|…": a file's name never has a | in it), and the "Sync with" choices of single films not in
         * [videos] ("syncwith|film:<video>"). Nothing else: not a show's ("syncjumps|", "syncstart|", "syncwith|tmdb:").
         */
        internal fun staleSubtitleTimes(keys: Collection<String>, files: Set<String>, videos: Set<String>): List<String> =
            keys.filter { k ->
                when {
                    k.startsWith("sync|") -> k.substring(5).substringBefore('|') !in files
                    k.startsWith("subdelay|") -> k.substring(9).substringBefore('|') !in files
                    k.startsWith("syncwith|film:") -> k.substring(14) !in videos
                    else -> false
                }
            }
    }
}
