package app.fireplayer.data

import android.content.Context
import android.content.SharedPreferences
import app.fireplayer.titles.TitleFiles

/**
 * The user's own keys and sign-in (TMDB, OpenSubtitles), in a file of their own ([FILE]), apart from the settings
 * that change all the time. Each is written at once (commit, not apply), and a blank value never replaces a saved
 * one, so a box confirmed empty by mistake can't take a key away.
 *
 * Older versions kept them with the other settings ([Settings.FILE]): what only that file has is copied over, and stays
 * there too, so an older version installed over this one still finds them. A key this file lacks is read from there.
 *
 * Values are never logged or shown; Settings only says whether each is set.
 */
class KeyStore(
    private val keys: () -> SharedPreferences,
    private val old: () -> SharedPreferences,
    /** Told of each key saved (the title server is given its own copy of the TMDB key). */
    private val onSaved: (Key, String) -> Unit = { _, _ -> },
) {

    /** Each key, by its name in both files. A password keeps its spaces; the rest are trimmed. */
    enum class Key(val pref: String, val trimmed: Boolean = true) {
        TMDB("tmdb_key"),
        OPENSUBTITLES("os_api_key"),
        OPENSUBTITLES_USER("os_user"),
        OPENSUBTITLES_PASSWORD("os_pass", trimmed = false),
    }

    operator fun get(key: Key): String =
        keys().getString(key.pref, null)?.takeIf { it.isNotBlank() }
            ?: old().getString(key.pref, null)?.takeIf { it.isNotBlank() }
            ?: ""

    fun isSet(key: Key): Boolean = get(key).isNotEmpty()

    /**
     * Saves [value] for [key]. A blank one is ignored and false returned: a key is never replaced by nothing.
     * Written to the old file too, for an older version installed over this one.
     */
    fun save(key: Key, value: String): Boolean {
        val clean = if (key.trimmed) value.trim() else value
        if (clean.isBlank()) return false
        val saved = keys().edit().putString(key.pref, clean).commit()
        old().edit().putString(key.pref, clean).commit()
        onSaved(key, clean)
        return saved
    }

    /** Copies the keys only the old file has into the new one (once: after that the new one has them). */
    fun migrate() {
        val newFile = keys()
        val oldFile = old()
        val copy = Key.entries.mapNotNull { key ->
            if (!newFile.getString(key.pref, null).isNullOrBlank()) return@mapNotNull null
            oldFile.getString(key.pref, null)?.takeIf { it.isNotBlank() }?.let { key to it }
        }
        if (copy.isEmpty()) return
        val edit = newFile.edit()
        copy.forEach { (key, value) -> edit.putString(key.pref, value) }
        edit.commit()
    }

    companion object {
        const val FILE = "fireplayer_keys"
        /** Where older versions kept them, with the other settings ([Prefs], [Settings.FILE]). */
        const val OLD_FILE = Settings.FILE

        /**
         * The app's own, copied over from the old file the first time. The title server, in its own process, never
         * opens either file: it's given its copy of the TMDB key as it's saved
         * ([app.fireplayer.titles.TitleFiles.keepKey]).
         */
        fun of(context: Context): KeyStore {
            val app = context.applicationContext
            val keys = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val old = Settings.of(app)
            val tellTitles = { key: Key, value: String ->
                if (key == Key.TMDB) SettingsWriter.shared.post { TitleFiles.keepKey(app, value) }
            }
            return KeyStore({ keys }, { old }, tellTitles).apply { migrate() }
        }
    }
}
