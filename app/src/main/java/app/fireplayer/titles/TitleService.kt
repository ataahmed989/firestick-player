package app.fireplayer.titles

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import app.fireplayer.R
import app.fireplayer.data.SettingsWriter
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
 * playlist update. Settings' "Check playlists now" wakes it at once ([checkNow]).
 *
 * It never opens the app's settings: what it needs from the app (on or off, the player's beat, the TMDB key) comes in
 * files of its own that it only reads ([TitleFiles]). Turned off, it and its [Starter] are switched off in the app's
 * manifest too, so nothing of it starts at all.
 */
class TitleService : Service() {

    private lateinit var book: TitleBook
    private lateinit var proxy: ProxyServer
    private val lock = Object()
    @Volatile private var playing = false
    @Volatile private var running = true
    // A wake that came while the loop was busy: its next rest is skipped, so the wake isn't lost.
    private var woken = false
    // When Settings last asked to check now (seconds): what failed before then is tried again at once.
    @Volatile private var checkAsked = 0L

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
        when (intent?.action) {
            ACTION_PLAYING -> playing = intent.getBooleanExtra(EXTRA_PLAYING, false)
            ACTION_CHECK -> {
                checkAsked = now()
                Log.i(TAG, "Check asked for in Settings")
            }
        }
        // Whatever the loop is resting for, it looks again; a film on or no key still keep it waiting.
        wake()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        proxy.stop()
        wake()
        super.onDestroy()
    }

    private fun wake() = synchronized(lock) {
        woken = true
        lock.notifyAll()
    }

    private fun sleep(ms: Long) = synchronized(lock) {
        if (running && !woken && ms > 0) lock.wait(ms)
        woken = false
    }

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

    private fun matchLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        // Read again whenever the app writes a new one: the key is saved in the app, which is another process.
        val key = TitleFiles.Key(this)
        val tmdb = Tmdb({ key.get() }, book.tmdbMemory)
        val matcher = TitleMatcher(tmdb)
        val pool = Executors.newFixedThreadPool(ASKING_AT_ONCE) { r ->
            Thread {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }.apply { isDaemon = true }
        }
        var told = false
        var batches = 0
        // After TMDB said too many requests (or no to the key), when it's asked again: a wake doesn't cut this short.
        var slowUntil = 0L
        while (running) {
            val resting = slowUntil - SystemClock.elapsedRealtime()
            if (resting > 0) {
                sleep(resting)
                continue
            }
            if (!tmdb.isConfigured) {
                if (!told) Log.i(TAG, "No TMDB key in FirePlayer's Settings yet; titles wait for one")
                told = true
                // Settings saves the key a moment after it wakes this, so it's looked for again soon.
                sleep(NO_KEY_CHECK_MS)
                continue
            }
            told = false
            // The player has the TV to itself, while it says so: a "stopped" Fire OS refused leaves no beat behind.
            if (playing && System.currentTimeMillis() - TitleFiles.beatAt(this) < PLAYER_GONE_MS) {
                sleep(PLAYING_CHECK_MS)
                continue
            }
            val batch = runCatching { book.waiting(now(), BATCH, retrySince = checkAsked) }.getOrElse { emptyList() }
            // Now and then, and when everything is done: how far each provider is.
            if (batches++ % PROGRESS_EVERY == 0 || batch.isEmpty()) {
                // A playlist taken out of TiviMate: its English names go too.
                runCatching { book.gone(now()) }.getOrNull()?.forEach { server ->
                    Log.i(TAG, "$server hasn't been asked about for a week: its titles are forgotten")
                    runCatching { book.forget(server) }
                }
                logProgress()
            }
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
                        val named = matcher.englishName(w.raw, w.tmdb, w.year, w.kind == TitleBook.Kind.SHOW, w.category)
                        val name = named?.title
                        book.answered(w, name, now(), named?.tmdb ?: 0)
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
            if (slowDown.get()) slowUntil = SystemClock.elapsedRealtime() + SLOW_DOWN_MS
        }
        pool.shutdownNow()
    }

    private fun now() = System.currentTimeMillis() / 1000

    private var lastProgress = ""

    private fun logProgress() {
        val text = runCatching { book.progress() }.getOrElse { emptyList() }
            .joinToString("; ") { "${it.server}: ${it.renamed} renamed, ${it.kept} kept, ${it.waiting} to look up" }
        if (text.isNotEmpty() && text != lastProgress) Log.i(TAG, "Progress: $text")
        lastProgress = text
    }

    /**
     * Starts it at boot and after FirePlayer is updated, before TiviMate needs it; and when Glass Home asks: pressing
     * Home with the TV short of memory has Fire OS force-stop the app in front, and with FirePlayer this goes too.
     * Switched off with the server ([start]), so with titles off it never runs. Here in the server's process it reads
     * only the app's note ([TitleFiles.isOn]); until there is one (the first start after an update), the app is asked
     * to start it or not ([app.fireplayer.data.Settings.syncTitles]).
     */
    class Starter : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!context.resources.getBoolean(R.bool.title_proxy)) return
            when (TitleFiles.isOn(context)) {
                true -> startHere(context)
                false -> Unit
                null -> runCatching {
                    context.contentResolver.call(Uri.parse("content://${context.packageName}.continue"), SYNC, null, null)
                }.onFailure { startHere(context) }
            }
        }
    }

    companion object {
        private const val TAG = "FirePlayerTitles"
        private const val CHANNEL = "titles"
        private const val NOTIFICATION_ID = 8000
        private const val ACTION_PLAYING = "app.fireplayer.titles.PLAYING"
        private const val ACTION_CHECK = "app.fireplayer.titles.CHECK"
        private const val EXTRA_PLAYING = "playing"
        // TMDB allows about 50 requests a second; this stays well under it.
        private const val ASKING_AT_ONCE = 3
        private const val BATCH = 120
        private const val IDLE_MS = 10 * 60_000L
        private const val NO_KEY_CHECK_MS = 30_000L
        private const val PLAYING_CHECK_MS = 60_000L
        private const val SLOW_DOWN_MS = 60_000L
        private const val LOGGED_PER_BATCH = 4
        private const val PROGRESS_EVERY = 10

        /** What the [Starter] asks the app, through its Continue Watching provider, when it has no note yet. */
        const val SYNC = "titles_sync"

        /**
         * From the app ([on]: TiviMate titles as Settings has it, on unless turned off): starts it, or stops it. Its
         * note for the [Starter] is written, and it and its [Starter] are switched on or off, so with titles off nothing
         * of it starts at boot or when Glass Home asks. Done on the settings' writer thread, in the order asked.
         */
        fun start(context: Context, on: Boolean) {
            val app = context.applicationContext
            // A build that may not run it (the side-by-side test build) never starts it.
            val run = on && app.resources.getBoolean(R.bool.title_proxy)
            SettingsWriter.shared.post {
                val intent = Intent(app, TitleService::class.java)
                if (run) {
                    switchOn(app, true)
                    TitleFiles.writeOn(app, true)
                    runCatching {
                        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
                    }.onFailure { Log.w(TAG, "Couldn't start: $it") }
                } else {
                    runCatching { app.stopService(intent) }
                    TitleFiles.writeOn(app, false)
                    switchOn(app, false)
                }
            }
        }

        /** In the server's own process: started as the app last said ([Starter]). */
        internal fun startHere(context: Context) {
            val intent = Intent(context, TitleService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            }.onFailure { Log.w(TAG, "Couldn't start: $it") }
        }

        /** The server and its [Starter] switched on or off in the app's manifest (only when that changes it). */
        private fun switchOn(app: Context, on: Boolean) {
            val pm = app.packageManager
            val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            for (cls in listOf(TitleService::class.java, Starter::class.java)) {
                val component = ComponentName(app, cls)
                runCatching {
                    if (pm.getComponentEnabledSetting(component) != state) {
                        pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                    }
                }
            }
        }

        /**
         * Settings' "Check playlists now": the server stops resting and looks up what's waiting at once, trying again
         * what failed before. It still waits while a film is on, and for a TMDB key. Only when the server is [on].
         */
        fun checkNow(context: Context, on: Boolean) {
            if (!on) return
            runCatching { context.startService(Intent(context, TitleService::class.java).setAction(ACTION_CHECK)) }
                .onFailure { Log.w(TAG, "Couldn't ask for a check: $it") }
        }

        /** How often the player notes that it is on, and how long without a note the server takes it as stopped. */
        const val BEAT_MS = 30_000L
        private const val PLAYER_GONE_MS = 90_000L

        /**
         * The player is on (see PlayerActivity.playingBeat): the beat file's time is now, set off the main thread. Not
         * the settings: they're no longer written all through a film.
         */
        fun playerBeat(context: Context) {
            val app = context.applicationContext
            SettingsWriter.shared.post { TitleFiles.touchBeat(app) }
        }

        /** While FirePlayer plays, TMDB isn't asked: the player has the TV's attention. Only when the server is [on]. */
        fun playing(context: Context, on: Boolean, playing: Boolean) {
            if (!on) return
            runCatching {
                context.startService(
                    Intent(context, TitleService::class.java).setAction(ACTION_PLAYING).putExtra(EXTRA_PLAYING, playing),
                )
            }
        }
    }
}
