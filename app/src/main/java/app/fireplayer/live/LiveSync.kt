package app.fireplayer.live

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.os.SystemClock
import android.util.Xml
import app.fireplayer.live.SyncPlan.Trigger
import app.fireplayer.live.ui.LivePrefs
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/**
 * Updates playlists and their EPG, one job at a time on one background thread (the only writer of [LiveDb]).
 * Nothing here runs on the main thread; [listener] hears about progress on the main thread.
 *
 * Xtream Codes playlists are read through player_api.php (account details, live categories, live streams) and
 * their EPG from the server's xmltv.php unless the viewer set another. M3U playlists are read as text; their EPG is
 * the one they name (url-tvg) unless the viewer set another. Logins never appear in logs: only the playlist's name.
 *
 * Every request to a provider goes through [SyncGuard] first: a channel from that provider playing is let go (an
 * update the viewer asked for) or the update waits for a quiet moment (an automatic one), and the update holds one
 * connection lease of its own (an automatic update only while its request to that provider runs). An automatic
 * update already under way when a film starts (or when the provider's own channel wants its connection) stops at its
 * next safe point: a download's next read, before storing what was read, between the steps of clearing old
 * programmes. It is put off, not failed: no status is written, it counts as not tried, and it runs again at the next
 * quiet moment. A failure of any kind, or a stop, leaves the stored channels and guide as they were.
 */
class LiveSync private constructor(context: Context) {

    interface Listener {
        /** A playlist's channels or its EPG changed (or a sync finished with an error status). */
        fun onLiveDataChanged(playlistId: String)
        /** An update has let go of the playing channel for a moment: one plain line to show over the picture. */
        fun onUpdateNotice(text: String) {}
    }

    private val app = context.applicationContext
    private val db = LiveDb.get(app)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "FirePlayerLiveSync").apply { priority = Thread.MIN_PRIORITY + 1 } }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private val running = java.util.concurrent.atomic.AtomicInteger()
    private val guard: SyncGuard = SyncGuard(
        app as Application, main,
        onQuiet = { updateDue() },
        notice = { text -> for (l in listeners.toList()) l.onUpdateNotice(text) },
    )
    /**
     * When an update last tried each playlist ("p:id") and EPG ("e:id"), and how many tries in a row failed, so a
     * failing one isn't asked again at every turn ([SyncPlan.isDue]); one put off for a film counts as not tried. Kept
     * with the Live TV settings so it holds across starts of the app too: an EPG that couldn't be read was downloaded
     * again at every start. Read on the update thread.
     */
    private val tries by lazy { SyncPlan.Tries(TriesStore(app.getSharedPreferences(LivePrefs.FILE, Context.MODE_PRIVATE))) }

    val busy: Boolean get() = running.get() > 0

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }

    /**
     * Updates one playlist's channels, then its EPG. A playlist without an EPG of its own gets its listings from the
     * others (fallback by exact EPG id), so their EPGs are read again to cover its channels. Asked by the viewer (the
     * playlist's Update row, or the playlist just saved).
     */
    fun update(playlistId: String, epg: Boolean = true) = submit(Trigger.MANUAL, epgOnly = false) {
        val p = db.playlist(playlistId) ?: return@submit
        syncChannels(p)
        notify(p.id)
        if (epg) {
            val fresh = db.playlist(playlistId) ?: return@submit
            val sources = if (epgUrl(fresh) != null) listOf(fresh) else db.playlists().filter { epgUrl(it) != null }
            for (src in sources) { syncEpg(src); notify(src.id) }
            notify(p.id)
        }
    }

    /** Updates every playlist (channels first for all, then every EPG, so fallback EPG sees every channel). */
    fun updateAll() = submit(Trigger.MANUAL, epgOnly = false) {
        val all = db.playlists()
        for (p in all) { syncChannels(p); notify(p.id) }
        for (p in db.playlists()) { syncEpg(p); notify(p.id) }
    }

    /**
     * On app start, on coming back to it, every hour it stays open, and at the first quiet moment after an
     * automatic update had to wait: playlists and EPGs not updated within their intervals are updated now. Never
     * while something from the same provider plays (they wait, [SyncGuard]).
     */
    fun updateDue(at: Long? = null) = submit(Trigger.AUTO, epgOnly = false) {
        // The time the job runs, not when it was queued: a job queued behind another would otherwise see that one's
        // tries as made in the future ([SyncPlan.isDue] lets those go) and ask a failing source again at once.
        val now = at ?: System.currentTimeMillis()
        // What a guide update stopped for a film left behind goes first (almost always nothing: a set in memory).
        db.tidyProgrammes(guard::goesOn)
        val due = db.playlists().filter { isDue(now, "p:" + it.id, it.lastSync, it.updateHours.coerceAtLeast(1) * HOUR_MS) }
        val synced = due.filter { p -> syncChannels(p).also { notify(p.id) } }
        // The due playlists' EPGs, plus every EPG when a due playlist has none of its own (it borrows theirs), plus
        // any EPG older than the EPG update interval (Live FirePlayer Settings) whether or not its playlist is due.
        val all = db.playlists()
        val dueIds = synced.map { it.id }.toSet()
        val needFallback = all.any { it.id in dueIds && epgUrl(it) == null }
        val epgEvery = LivePrefs(app).epgUpdateHours.coerceAtLeast(1) * HOUR_MS
        val epgSeen = db.epgStatuses().associate { it.source to it.lastSync }
        // EPGs follow their own interval (Live FirePlayer Settings): one never loaded, or older than the interval, is
        // read again; a due playlist without an EPG of its own still has the others read for it.
        val sources = all.filter { epgUrl(it) != null && (needFallback || isDue(now, "e:" + it.id, epgSeen[it.id] ?: 0L, epgEvery)) }
        // Past programmes are cleared at most every few hours (it scans every listing): not while a film plays.
        if (now - lastPrune >= PRUNE_EVERY_MS) {
            guard.check()
            db.pruneProgrammes(now - PAST_KEEP_MS)
            lastPrune = now
        }
        for (p in sources) { syncEpg(p); notify(p.id) }
    }

    @Volatile private var lastPrune = 0L

    private fun isDue(now: Long, key: String, lastSuccess: Long, intervalMs: Long) =
        SyncPlan.isDue(now, lastSuccess, intervalMs, tries.last(key), tries.failures(key))

    fun updateEpg(playlistId: String? = null) = submit(Trigger.MANUAL, epgOnly = true) {
        val list = if (playlistId == null) db.playlists() else listOfNotNull(db.playlist(playlistId))
        for (p in list) { syncEpg(p); notify(p.id) }
    }

    fun clearEpg() = submit(Trigger.MANUAL, epgOnly = true) {
        db.clearEpg()
        for (p in db.playlists()) notify(p.id)
    }

    private fun submit(trigger: Trigger, epgOnly: Boolean, job: () -> Unit) {
        running.incrementAndGet()
        worker.execute {
            guard.begin(trigger, epgOnly)
            try {
                job()
            } catch (t: SyncPlan.PutOff) {
                // An automatic update stopped for a quiet moment (said where it stopped part-way): it runs again then.
            } catch (t: Throwable) {
                Log.w(TAG, "Live sync failed: ${t.javaClass.simpleName}")
            } finally {
                // The lease goes back and a channel let go comes back at live, whether the update worked or not.
                guard.end()
                running.decrementAndGet()
                // One last word once nothing is running, so "Updating…" never sticks.
                notify(ANY)
            }
        }
    }

    private fun notify(id: String) = main.post { for (l in listeners.toList()) l.onLiveDataChanged(id) }

    /** A change made outside a sync (group order or visibility, playlist order): screens showing it reload. */
    fun changed(playlistId: String) = notify(playlistId)

    /**
     * Before a request for [p] to [url]: true to go ahead. False when it waits for a quiet moment (nothing is
     * recorded) or the provider's connection is in use (said in the playlist's status when [onRefused] is given).
     * An automatic job stopped meanwhile (a film started) ends here ([SyncPlan.PutOff]): the rest waits as well.
     */
    private fun cleared(p: Playlist, url: String, key: String, onRefused: ((String) -> Unit)? = null): Boolean {
        guard.check()
        if (!guard.clear(p, url)) {
            guard.refusal?.let { onRefused?.invoke(it) }
            return false
        }
        tries.began(key, System.currentTimeMillis())
        return true
    }

    /** [p]'s request for [key] stopped part-way for a quiet moment: as if not tried, nothing written ([what] for the log). */
    private fun putOff(p: Playlist, key: String, what: String) {
        tries.putOff(key)
        Log.i(TAG, "$what for \"${p.name}\": put off part-way until nothing plays")
    }

    // ---- Channels ----------------------------------------------------------------------------------------------

    /** True when the playlist was read and stored. */
    private fun syncChannels(p: Playlist): Boolean = readChannels(p).also { if (it) tries.succeeded("p:" + p.id) }

    private fun readChannels(p: Playlist): Boolean {
        val now = System.currentTimeMillis()
        if (!cleared(p, p.url, "p:" + p.id) { db.setPlaylistStatus(p.id, it, syncedAt = null) }) return false
        try {
            val parsed: ParsedPlaylist = when (p.kind) {
                PlaylistKind.XTREAM -> {
                    val api = XtreamApi(p, guard::check)
                    val account = api.get(null)?.let { XtreamParser.account(it) }
                    if (account == null) {
                        val why = Http.failure()
                        db.setPlaylistStatus(p.id, if (why != null) "Couldn't update: ${SyncPlan.describe(why)}" else "Couldn't sign in: check the server, username and password", syncedAt = null)
                        return false
                    }
                    if (!account.active) {
                        db.setPlaylistStatus(p.id, account.message ?: "The account isn't active", syncedAt = null, expiresAt = account.expiresAt, maxConnections = account.maxConnections)
                        return false
                    }
                    if (!p.includeLive) {
                        val empty = ParsedPlaylist(emptyList(), emptyList())
                        db.applySync(p.id, empty)
                        db.setPlaylistStatus(p.id, "Updated (TV channels turned off)", now, account.expiresAt, account.maxConnections)
                        return true
                    }
                    // Both lists or nothing: a failed or garbled answer is a failed update, never "no channels".
                    val t0 = SystemClock.elapsedRealtime()
                    val cats = api.get("get_live_categories")
                    val streams = if (cats != null) api.get("get_live_streams") else null
                    val t1 = SystemClock.elapsedRealtime()
                    val why = Http.failure()
                    val result = if (cats != null && streams != null) XtreamParser.live(p.id, cats, streams) { id, _ -> api.streamUrl(id) } else null
                    Log.i(TAG, "Playlist \"${p.name}\": lists ${(streams?.length ?: 0) / 1024} KB in ${t1 - t0} ms, read in ${SystemClock.elapsedRealtime() - t1} ms")
                    if (result == null) {
                        val text = if (why != null) "Couldn't load the channel list: ${SyncPlan.describe(why)}" else "Couldn't load the channel list"
                        db.setPlaylistStatus(p.id, text, syncedAt = null, expiresAt = account.expiresAt, maxConnections = account.maxConnections)
                        return false
                    }
                    if (!SyncPlan.mayReplaceChannels(result.channels.size, db.channelCount(p.id))) {
                        db.setPlaylistStatus(p.id, "Not updated: the provider listed no channels", syncedAt = null, expiresAt = account.expiresAt, maxConnections = account.maxConnections)
                        return false
                    }
                    // Read: stored only if nothing came in front meanwhile.
                    guard.check()
                    val t2 = SystemClock.elapsedRealtime()
                    val removed = db.applySync(p.id, result)
                    db.setPlaylistStatus(p.id, "Updated: ${result.channels.size} channels", now, account.expiresAt, account.maxConnections)
                    Log.i(TAG, "Playlist \"${p.name}\": ${result.channels.size} channels, ${result.groups.size} groups, $removed removed; stored in ${SystemClock.elapsedRealtime() - t2} ms")
                    return true
                }
                PlaylistKind.M3U -> {
                    if (!p.includeLive) {
                        db.applySync(p.id, ParsedPlaylist(emptyList(), emptyList()))
                        db.setPlaylistStatus(p.id, "Updated (TV channels turned off)", now)
                        return true
                    }
                    // Read as it arrives, line by line: a large playlist is never held whole in memory.
                    val parsed = Http.lines(p.url, MAX_PLAYLIST_BYTES, guard::check) { M3uParser.parse(it) }
                    if (parsed.entries.isEmpty() && !parsed.header) {
                        db.setPlaylistStatus(p.id, "That link isn't an M3U playlist", syncedAt = null)
                        return false
                    }
                    if (!SyncPlan.mayReplaceChannels(parsed.entries.size, db.channelCount(p.id))) {
                        db.setPlaylistStatus(p.id, "Not updated: the playlist had no channels", syncedAt = null)
                        return false
                    }
                    M3uParser.toPlaylist(p.id, parsed)
                }
            }
            // Stored first, if nothing came in front meanwhile; only then recorded as updated (a failure leaves the last
            // good update's time and EPG link).
            guard.check()
            val removed = db.applySync(p.id, parsed)
            db.setPlaylistStatus(p.id, "Updated: ${parsed.channels.size} channels", now, playlistEpgUrl = parsed.epgUrl ?: "")
            Log.i(TAG, "Playlist \"${p.name}\": ${parsed.channels.size} channels, ${parsed.groups.size} groups, $removed removed")
            return true
        } catch (t: SyncPlan.PutOff) {
            putOff(p, "p:" + p.id, "Playlist")
            throw t
        } catch (t: Throwable) {
            runCatching { db.setPlaylistStatus(p.id, "Couldn't update: ${SyncPlan.describe(t)}", syncedAt = null) }
            Log.w(TAG, "Playlist \"${p.name}\" failed: ${t.javaClass.simpleName}")
            return false
        } finally {
            // An automatic update gives the provider's connection back now, not after the EPGs that follow.
            guard.requestDone()
        }
    }

    // ---- EPG ---------------------------------------------------------------------------------------------------

    private fun syncEpg(p: Playlist) {
        val url = epgUrl(p) ?: return
        val now = System.currentTimeMillis()
        // The refs this source's EPG feeds: its own channels, plus those of playlists with no EPG of their own
        // (fallback by exact XMLTV id; see LiveDb.programmes).
        val withoutEpg = db.playlists().filter { it.id != p.id && epgUrl(it) == null }.map { it.id }
        val wanted = db.epgRefs(listOf(p.id) + withoutEpg)
        if (wanted.isEmpty()) {
            db.setEpgStatus(p.id, url, "No channels with EPG ids", now)
            return
        }
        if (!cleared(p, url, "e:" + p.id) { db.setEpgStatus(p.id, url, it, syncedAt = null) }) return
        // An empty download from the same address never empties the guide (a new address may: it's what it lists). Nor
        // does one stopped part-way, whatever the reader made of the stop: what it read is never the whole guide.
        val before = db.epgStatuses().firstOrNull { it.source == p.id }
        var stream: CountingInputStream? = null
        val accept = { count: Int -> stream?.putOff != true && SyncPlan.mayReplaceGuide(count, before?.programmes ?: 0, before?.url == url) }
        try {
            val conn = Http.open(url)
            val t0 = SystemClock.elapsedRealtime()
            try {
                // Each read is a safe point: stopped there, the guide keeps what it had.
                val counted = CountingInputStream(conn.second, guard::check)
                stream = counted
                val count = counted.use { input ->
                    val parser = Xml.newPullParser()
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                    parser.setInput(input, null)
                    db.replaceProgrammes(p.id, url, now - PAST_KEEP_MS, now, accept, guard::goesOn) { put ->
                        XmltvReader.read(parser, wanted, now - PAST_KEEP_MS, now + FUTURE_KEEP_MS) { item -> put(item.channel, item.programme) }
                    }
                }
                Log.i(TAG, "EPG for \"${p.name}\": $count programmes for ${wanted.size} ids from ${counted.count / 1024} KB in ${SystemClock.elapsedRealtime() - t0} ms")
                tries.succeeded("e:" + p.id)
            } finally {
                conn.first.disconnect()
            }
        } catch (t: Throwable) {
            // Stopped for a quiet moment (the XML reader may have wrapped that): put off, nothing written.
            if (t is SyncPlan.PutOff || stream?.putOff == true) {
                putOff(p, "e:" + p.id, "EPG")
                throw t as? SyncPlan.PutOff ?: SyncPlan.PutOff()
            }
            // The guide keeps what it had, and its last update time.
            runCatching { db.setEpgStatus(p.id, url, "Couldn't update: ${SyncPlan.describe(t)}", syncedAt = null) }
            Log.w(TAG, "EPG for \"${p.name}\" failed: ${t.javaClass.simpleName}")
        } finally {
            guard.requestDone()
        }
    }

    private fun epgUrl(p: Playlist): String? =
        db.epgUrlFor(p) ?: if (p.kind == PlaylistKind.XTREAM) XtreamApi(p).xmltvUrl() else null

    companion object {
        private const val TAG = "FirePlayerLive"
        private const val HOUR_MS = 60L * 60 * 1000
        /** The id sent when a run of jobs finishes (listeners reload whatever they show). */
        const val ANY = "*"
        /** One day of past programmes is kept (design-layout: "Past days to keep EPG", one day). */
        const val PAST_KEEP_MS = 24 * HOUR_MS
        /** How far ahead programmes are kept: provisional, the design leaves the future horizon open. */
        const val FUTURE_KEEP_MS = 3 * 24 * HOUR_MS
        /** Larger than this, an M3U download is a failure (read to here, it would be a cut-off list). */
        private const val MAX_PLAYLIST_BYTES = 256L * 1024 * 1024
        private const val PRUNE_EVERY_MS = 6 * HOUR_MS

        @Volatile private var instance: LiveSync? = null
        fun get(context: Context): LiveSync =
            instance ?: synchronized(this) { instance ?: LiveSync(context).also { instance = it } }
    }
}


/**
 * [LiveSync]'s tries ([SyncPlan.Tries]), stored beside the Live TV settings under keys of their own, which earlier
 * versions never read. Only ever used on the update thread, which writes them itself (commit): nothing is left waiting
 * for the main thread to finish when a screen closes or a film starts.
 */
private class TriesStore(private val sp: SharedPreferences) : SyncPlan.Tries.Store {
    override fun tried(key: String): Long? = if (sp.contains(TRIED + key)) sp.getLong(TRIED + key, 0L) else null

    override fun failed(key: String): Int? = if (sp.contains(FAILED + key)) sp.getInt(FAILED + key, 0) else null

    override fun put(key: String, tried: Long?, failed: Int?) {
        val e = sp.edit()
        if (tried == null) e.remove(TRIED + key) else e.putLong(TRIED + key, tried)
        if (failed == null) e.remove(FAILED + key) else e.putInt(FAILED + key, failed)
        e.commit()
    }

    private companion object {
        const val TRIED = "sync_tried:"
        const val FAILED = "sync_failed:"
    }
}

/**
 * Xtream Codes calls for one playlist, by its server address and login. [check] runs before each read of an answer
 * (an update's safe point: it may throw [SyncPlan.PutOff]).
 */
class XtreamApi(private val p: Playlist, private val check: (() -> Unit)? = null) {
    private val base: String = p.url.trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }

    fun get(action: String?): String? {
        val b = Uri.parse("$base/player_api.php").buildUpon()
            .appendQueryParameter("username", p.username)
            .appendQueryParameter("password", p.password)
        if (action != null) b.appendQueryParameter("action", action)
        return Http.text(b.build().toString(), 64 * 1024 * 1024, check)
    }

    fun xmltvUrl(): String = Uri.parse("$base/xmltv.php").buildUpon()
        .appendQueryParameter("username", p.username)
        .appendQueryParameter("password", p.password)
        .build().toString()

    fun streamUrl(streamId: String): String {
        val ext = if (p.output == "m3u8") "m3u8" else "ts"
        return "$base/live/${Uri.encode(p.username)}/${Uri.encode(p.password)}/$streamId.$ext"
    }
}

/** Plain HTTP GETs for playlists and EPG, with gzip handled either way (header or a .gz file). */
object Http {
    private const val UA = "FirePlayer/2.0 (Linux; Android)"

    /** Why the last [text] on this thread came back null (null when the server answered fine). */
    private val lastFailure = ThreadLocal<Throwable?>()

    fun failure(): Throwable? = lastFailure.get()

    /** Opens [url] for reading; throws ([SyncPlan.HttpStatus] for an answer other than 200 OK). */
    fun open(url: String): Pair<HttpURLConnection, InputStream> {
        var conn = URL(url).openConnection() as HttpURLConnection
        var hops = 0
        try {
            while (true) {
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Accept-Encoding", "gzip")
                val code = conn.responseCode
                // HttpURLConnection won't follow http <-> https redirects itself.
                if (code in 301..308 && hops < 5) {
                    val next = conn.getHeaderField("Location") ?: throw SyncPlan.HttpStatus(code)
                    conn.disconnect()
                    conn = URL(URL(url), next).openConnection() as HttpURLConnection
                    hops++
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) throw SyncPlan.HttpStatus(code)
                break
            }
            val raw = BufferedInputStream(conn.inputStream, 64 * 1024)
            val gz = conn.contentEncoding.equals("gzip", true) || isGzip(raw)
            return conn to (if (gz) BufferedInputStream(GZIPInputStream(raw, 64 * 1024), 64 * 1024) else raw)
        } catch (t: Throwable) {
            conn.disconnect()
            throw t
        }
    }

    /**
     * The whole answer as text, or null (why: [failure]); one longer than [maxChars] is a failure, never cut off.
     * [check] runs before each read; a [SyncPlan.PutOff] it throws goes on to the caller.
     */
    fun text(url: String, maxChars: Int, check: (() -> Unit)? = null): String? {
        lastFailure.set(null)
        val (conn, input) = try { open(url) } catch (t: Throwable) { lastFailure.set(t); return null }
        return try {
            input.bufferedReader().use { r ->
                val out = StringBuilder()
                val buf = CharArray(16 * 1024)
                while (true) {
                    check?.invoke()
                    val n = r.read(buf)
                    if (n < 0) break
                    if (out.length + n > maxChars) throw SyncPlan.TooLarge()
                    out.append(buf, 0, n)
                }
                out.toString()
            }
        } catch (t: SyncPlan.PutOff) {
            throw t
        } catch (t: Exception) {
            lastFailure.set(t)
            null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Reads [url] line by line into [read], as it arrives; throws on any failure, or past [maxBytes]. [check] runs
     * before each read (it may throw [SyncPlan.PutOff]).
     */
    fun <T> lines(url: String, maxBytes: Long, check: (() -> Unit)? = null, read: (Sequence<String>) -> T): T {
        val (conn, input) = open(url)
        val counted = CountingInputStream(input, check)
        try {
            return counted.bufferedReader().useLines { lines ->
                read(lines.onEach { if (counted.count > maxBytes) throw SyncPlan.TooLarge() })
            }
        } catch (t: Throwable) {
            // A stop at a safe point stays a stop, whatever the reader made of it.
            if (counted.putOff && t !is SyncPlan.PutOff) throw SyncPlan.PutOff()
            throw t
        } finally {
            conn.disconnect()
        }
    }

    private fun isGzip(input: BufferedInputStream): Boolean {
        input.mark(2)
        val a = input.read()
        val b = input.read()
        input.reset()
        return a == 0x1f && b == 0x8b
    }
}

/**
 * Counts the bytes read through it (how big an EPG download was, for the log). [check] runs before each read: an
 * update's safe point, which may stop it ([SyncPlan.PutOff]).
 */
internal class CountingInputStream(inner: InputStream, private val check: (() -> Unit)? = null) : java.io.FilterInputStream(inner) {
    var count = 0L
        private set

    /**
     * Its [check] stopped it: a put-off, whatever the reader made of it (an XML parser reports what its stream threw
     * while it looks for the encoding as an invalid stream of its own).
     */
    var putOff = false
        private set

    override fun read(): Int {
        safePoint()
        return super.read().also { if (it >= 0) count++ }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        safePoint()
        return super.read(b, off, len).also { if (it > 0) count += it }
    }

    private fun safePoint() {
        val c = check ?: return
        try {
            c()
        } catch (t: SyncPlan.PutOff) {
            putOff = true
            throw t
        }
    }
}
