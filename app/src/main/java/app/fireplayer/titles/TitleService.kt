package app.fireplayer.titles

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import app.fireplayer.R
import app.fireplayer.subs.Tmdb
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gives TiviMate its films and shows under their English names, as "The Matrix (1999) [4K]", for every playlist whose
 * server address goes through FirePlayer ([TitleProxy]). Runs in its own process, apart from the player, from when
 * the TV starts: TiviMate reaches its providers through it, so it has to be there whenever TiviMate is.
 *
 * Names TMDB has confirmed go into the lists as they pass ([ProxyServer]); new titles are asked about in the
 * background, a few at a time and never while FirePlayer is playing, and have their English names from TiviMate's next
 * playlist update.
 */
class TitleService : Service() {

    private lateinit var book: TitleBook
    private lateinit var proxy: ProxyServer
    private val lock = Object()
    @Volatile private var playing = false
    @Volatile private var running = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        book = TitleBook(this)
        proxy = ProxyServer(book) { wake() }
        if (!proxy.start()) Log.w(TAG, "Port ${TitleProxy.PORT} is taken; TiviMate's lists can't be renamed")
        Thread(::matchLoop, "TitleMatcher").apply { isDaemon = true }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PLAYING) playing = intent.getBooleanExtra(EXTRA_PLAYING, false)
        wake()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        proxy.stop()
        wake()
        super.onDestroy()
    }

    private fun wake() = synchronized(lock) { lock.notifyAll() }

    private fun sleep(ms: Long) = synchronized(lock) { if (running) lock.wait(ms) }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "TiviMate titles", NotificationManager.IMPORTANCE_MIN),
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("TiviMate titles")
            .setContentText("Showing films and shows under their English names")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------------------------------------------------------------------
    // Asking TMDB

    private val prefs get() = getSharedPreferences("fireplayer", Context.MODE_MULTI_PROCESS)

    private fun matchLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        // Read again each time: the key is changed in the app, which is another process.
        val tmdb = Tmdb({ prefs.getString("tmdb_key", "").orEmpty().trim() }, book.tmdbMemory)
        val matcher = TitleMatcher(tmdb)
        val pool = Executors.newFixedThreadPool(ASKING_AT_ONCE) { r ->
            Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }.apply { isDaemon = true }
        }
        var told = false
        while (running) {
            if (!tmdb.isConfigured) {
                if (!told) Log.i(TAG, "No TMDB key in FirePlayer's Settings yet; titles wait for one")
                told = true
                // Settings saves the key a moment after it wakes this, so it's looked for again soon.
                sleep(NO_KEY_CHECK_MS)
                continue
            }
            told = false
            // The player has the TV to itself.
            if (playing) {
                sleep(PLAYING_CHECK_MS)
                continue
            }
            val batch = runCatching { book.waiting(now(), BATCH) }.getOrElse { emptyList() }
            if (batch.isEmpty()) {
                sleep(IDLE_MS)
                continue
            }
            val slowDown = AtomicBoolean(false)
            val renamed = AtomicInteger()
            val kept = AtomicInteger()
            val tasks = batch.map { w ->
                Callable {
                    if (!running || playing || slowDown.get()) return@Callable
                    try {
                        val name = matcher.englishName(w.raw, w.tmdb, w.year, w.kind == TitleBook.Kind.SHOW, w.category)
                        book.answered(w, name, now())
                        // A few of each batch go in the log, to see how it's going.
                        val n = if (name != null) renamed.incrementAndGet() else kept.incrementAndGet()
                        if (n <= LOGGED_PER_BATCH) Log.i(TAG, "\"${w.raw}\" " + if (name != null) "-> \"$name\"" else "kept")
                    } catch (e: Tmdb.HttpError) {
                        // Too many requests, or a key TMDB doesn't take: stop for a while.
                        if (e.code == 429 || e.code == 401) {
                            if (!slowDown.getAndSet(true)) Log.i(TAG, "TMDB said ${e.code}: ${e.message}")
                        }
                        book.failed(w, now())
                    } catch (e: IOException) {
                        if (!slowDown.getAndSet(true)) Log.i(TAG, "Couldn't reach TMDB: $e")
                        book.failed(w, now())
                    } catch (e: Exception) {
                        Log.w(TAG, "Couldn't match \"${w.raw}\": $e")
                        book.answered(w, null, now())
                    }
                }
            }
            runCatching { pool.invokeAll(tasks) }
            Log.i(TAG, "TMDB: ${renamed.get()} renamed, ${kept.get()} kept of ${batch.size}" + if (slowDown.get()) ", slowing down" else "")
            if (slowDown.get()) sleep(SLOW_DOWN_MS)
        }
        pool.shutdownNow()
    }

    private fun now() = System.currentTimeMillis() / 1000

    /** Starts it at boot and after FirePlayer is updated, before TiviMate needs it. */
    class Starter : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = start(context)
    }

    companion object {
        private const val TAG = "FirePlayerTitles"
        private const val CHANNEL = "titles"
        private const val NOTIFICATION_ID = 8000
        private const val ACTION_PLAYING = "app.fireplayer.titles.PLAYING"
        private const val EXTRA_PLAYING = "playing"
        // TMDB allows about 50 requests a second; this stays well under it.
        private const val ASKING_AT_ONCE = 3
        private const val BATCH = 120
        private const val IDLE_MS = 10 * 60_000L
        private const val NO_KEY_CHECK_MS = 30_000L
        private const val PLAYING_CHECK_MS = 60_000L
        private const val SLOW_DOWN_MS = 60_000L
        private const val LOGGED_PER_BATCH = 4

        /** Starts it when it's turned on in Settings (it is unless turned off), stops it otherwise. */
        fun start(context: Context) {
            val on = isOn(context)
            val intent = Intent(context, TitleService::class.java)
            runCatching {
                if (!on) context.stopService(intent)
                else if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
                else context.startService(intent)
            }.onFailure { Log.w(TAG, "Couldn't start: $it") }
        }

        /** While FirePlayer plays, TMDB isn't asked: the player has the TV's attention. */
        fun playing(context: Context, playing: Boolean) {
            if (!isOn(context)) return
            runCatching {
                context.startService(
                    Intent(context, TitleService::class.java).setAction(ACTION_PLAYING).putExtra(EXTRA_PLAYING, playing),
                )
            }
        }

        private fun isOn(context: Context) =
            context.getSharedPreferences("fireplayer", Context.MODE_MULTI_PROCESS).getBoolean("tivimate_titles", true)
    }
}
