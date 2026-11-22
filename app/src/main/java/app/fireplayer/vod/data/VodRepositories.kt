package app.fireplayer.vod.data

import app.fireplayer.history.ContinueWatching
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.Process
import app.fireplayer.data.KeyStore
import app.fireplayer.subs.Tmdb
import app.fireplayer.vod.Page
import app.fireplayer.vod.VodRepository
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * The app's one [VodRepository] (main process only): `VodRepositories.get(context)` from any page. Its threads are
 * daemons below the UI's priority and live as long as the process; its database is `vod.db`, its cache `cache/vod`.
 */
object VodRepositories {

    @Volatile private var instance: DefaultVodRepository? = null

    fun get(context: Context): VodRepository = repository(context)

    /** The same, as its class: for later steps that need [DefaultVodRepository.library] (playback progress). */
    fun repository(context: Context): DefaultVodRepository =
        instance ?: synchronized(this) { instance ?: create(context.applicationContext).also { instance = it } }

    private fun create(app: Context): DefaultVodRepository {
        val keys = KeyStore.of(app)
        val db = VodDb(app)
        val handler = Handler(Looper.getMainLooper())
        val main = Executor { handler.post(it) }
        return DefaultVodRepository(
            http = UrlHttp,
            tmdb = TmdbSource.of(Tmdb({ keys[KeyStore.Key.TMDB] }, Tmdb.Memory.inProcess())),
            tmdbKey = { keys[KeyStore.Key.TMDB] },
            cache = JsonCache(File(app.cacheDir, "vod"), System::currentTimeMillis),
            idStore = db,
            libraryStore = db,
            main = main,
            // Cached rows come from here: a little below the UI, above the fetching threads.
            io = Executors.newSingleThreadExecutor(threads("FirePlayerVodDisk", Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_LESS_FAVORABLE)),
            net = Executors.newFixedThreadPool(3, threads("FirePlayerVodNet", Process.THREAD_PRIORITY_BACKGROUND)),
            lookups = Executors.newFixedThreadPool(3, threads("FirePlayerVodLookups", Process.THREAD_PRIORITY_BACKGROUND)),
            rowStore = PrefsRowStore(app.getSharedPreferences(KeyStore.OLD_FILE, Context.MODE_PRIVATE)),
        ).also { repo ->
            // Glass Home's Continue Watching row shows the library's too (history/ContinueProvider): it's told of a change.
            repo.addLibraryListener {
                runCatching { app.contentResolver.notifyChange(ContinueWatching.ITEMS, null) }
            }
        }
    }

    /** Settings › Rows' choices in the app's settings file, a key per page. */
    private class PrefsRowStore(private val sp: SharedPreferences) : RowLayoutStore {
        override fun read(page: Page): String? = sp.getString(key(page), null)
        override fun write(page: Page, text: String?) {
            sp.edit().apply { if (text == null) remove(key(page)) else putString(key(page), text) }.apply()
        }

        override fun readServices(): String? = sp.getString(SERVICES, null)
        override fun writeServices(text: String?) {
            sp.edit().apply { if (text == null) remove(SERVICES) else putString(SERVICES, text) }.apply()
        }

        private fun key(page: Page) = "vod_rows_" + RowLayouts.prefix(page)

        private companion object {
            const val SERVICES = "vod_services_order"
        }
    }

    /** Daemon threads below the UI's priority, so rows never compete with the UI or a playing film. */
    internal fun threads(name: String, priority: Int) = java.util.concurrent.ThreadFactory { r ->
        Thread({
            Process.setThreadPriority(priority)
            r.run()
        }, name).apply { isDaemon = true }
    }
}
