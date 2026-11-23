package app.fireplayer.data

import android.content.Context
import android.content.SharedPreferences
import app.fireplayer.titles.TitleFiles
import app.fireplayer.titles.TitleService

/**
 * The one place the app's settings file ([FILE]) is opened, in the app's own process only and never by another one
 * (the title server runs apart and is told what it needs by files of its own, [TitleFiles]). Every change made through
 * it goes on the disk at once ([SettingsWriter]), so pressing Home straight after, an update, a low-memory kill or
 * turning the TV off can't take it away. The file holds the viewer's settings only: what's noted while watching (resume
 * points, subtitle timings, key checks) is kept in [RECORDS], so the settings file isn't written all through a film.
 *
 * The first open in a run of the app also does, once, what older versions' settings need ([startOnce]); each only
 * adds what isn't there and never changes or removes a value the viewer set.
 */
object Settings {
    /** The settings, under their name since the first version (where the keys were kept too, [KeyStore]). */
    const val FILE = "fireplayer"

    /** What's noted while watching ([isRecord]), apart from the settings. */
    const val RECORDS = "fireplayer_records"

    /** In [RECORDS], once the records the settings file had were copied over ([moveRecords]). */
    internal const val RECORDS_MOVED = "records_moved"

    /** The title server's old note that the player was on: no longer kept (it was rewritten every half minute). */
    private const val OLD_BEAT = "player_beat"

    @Volatile private var settings: Durable? = null

    /** The settings; changes made through it are on the disk at once. */
    fun of(context: Context): SharedPreferences {
        settings?.let { return it }
        synchronized(this) {
            settings?.let { return it }
            val app = context.applicationContext
            val made = Durable(app.getSharedPreferences(FILE, Context.MODE_PRIVATE), SettingsWriter.shared)
            startOnce(app, made)
            settings = made
            return made
        }
    }

    /** What's noted while watching ([RECORDS]). */
    fun records(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(RECORDS, Context.MODE_PRIVATE)

    /** Anything still waiting is put on the disk now (a screen going to the background). */
    fun flush(context: Context) {
        (of(context) as Durable).let { SettingsWriter.shared.flush(it.real) }
    }

    /** For an explicit Save: the settings put on the disk, then [done] told whether they are, on the main thread. */
    fun save(context: Context, done: (Boolean) -> Unit) {
        val real = (of(context) as Durable).real
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        SettingsWriter.shared.flush(real) { ok -> main.post { done(ok) } }
    }

    /**
     * TiviMate titles as the settings have them, told to the title server (on or off, started or stopped): when it
     * asks, the first time it starts after an update ([TitleService.Starter]).
     */
    fun syncTitles(context: Context) {
        TitleService.start(context, of(context).getBoolean(Prefs.TITLES_KEY, true))
    }

    /**
     * Once a run, before the settings are used: the records an older version kept in the settings file move to their
     * own ([moveRecords]); customs from before the Screen dimmer's presets are kept as presets
     * ([Dimmer.convertLegacyOnce]); Playback glass's values from before its new defaults are completed
     * ([GlassShades.movePlaybackDefaultsOnce]); and the title server is given the TMDB key ([TitleFiles.keepKey]). Only
     * in memory here (the writes go on the disk from the writer's thread), so a screen opening isn't held up.
     */
    private fun startOnce(app: Context, made: SharedPreferences) {
        runCatching { moveRecords(made, records(app), SettingsWriter.shared) }
        runCatching { Dimmer(sp = { made }).convertLegacyOnce() }
        runCatching { GlassShades(sp = { made }).movePlaybackDefaultsOnce() }
        val keys = KeyStore({ app.getSharedPreferences(KeyStore.FILE, Context.MODE_PRIVATE) }, { made })
        SettingsWriter.shared.post { TitleFiles.keepKey(app, keys[KeyStore.Key.TMDB]) }
    }

    /** Whether [key] is one of the records noted while watching, kept in [RECORDS] rather than the settings. */
    internal fun isRecord(key: String): Boolean =
        key.startsWith("pos:") || key.startsWith("sync|") || key.startsWith("syncjumps|") ||
            key.startsWith("syncstart|") || key.startsWith("syncwith|") || key.startsWith("subdelay|") ||
            key == CredentialChecks.Service.OPENSUBTITLES.pref || key == CredentialChecks.Service.TMDB.pref

    /**
     * The records an older version kept in [settings] are copied into [records] (each one [records] doesn't have
     * already: a newer one there is never replaced), marked as done in the same change. Only once [records] is on the
     * disk are they taken out of [settings], with the title server's old beat. Run again it does nothing more, and
     * an interrupted move is finished the next time.
     */
    internal fun moveRecords(settings: SharedPreferences, records: SharedPreferences, writer: SettingsWriter) {
        val old = settings.all
        val leftovers = old.keys.filter { isRecord(it) || it == OLD_BEAT }
        if (!records.getBoolean(RECORDS_MOVED, false)) {
            val copy = records.edit()
            for (key in leftovers) {
                if (key == OLD_BEAT || records.contains(key)) continue
                when (val v = old[key]) {
                    is Long -> copy.putLong(key, v)
                    is Int -> copy.putInt(key, v)
                    is String -> copy.putString(key, v)
                    is Boolean -> copy.putBoolean(key, v)
                    is Float -> copy.putFloat(key, v)
                    is Set<*> -> copy.putStringSet(key, v.filterIsInstance<String>().toMutableSet())
                }
            }
            copy.putBoolean(RECORDS_MOVED, true).apply()
        }
        if (leftovers.isEmpty()) return
        writer.flush(records) { onDisk ->
            if (onDisk) settings.edit().apply { leftovers.forEach { remove(it) } }.commit()
        }
    }

    /** The settings file, each change applied and then put on the disk at once by [writer]. */
    internal class Durable(val real: SharedPreferences, private val writer: SettingsWriter) : SharedPreferences by real {

        override fun edit(): SharedPreferences.Editor = Editor(real.edit())

        private inner class Editor(private val e: SharedPreferences.Editor) : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { e.putString(key, value) }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
                apply { e.putStringSet(key, values) }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { e.putInt(key, value) }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { e.putLong(key, value) }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { e.putFloat(key, value) }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { e.putBoolean(key, value) }
            override fun remove(key: String?): SharedPreferences.Editor = apply { e.remove(key) }
            override fun clear(): SharedPreferences.Editor = apply { e.clear() }
            override fun commit(): Boolean = e.commit()
            override fun apply() {
                e.apply()
                writer.flush(real)
            }
        }
    }
}
