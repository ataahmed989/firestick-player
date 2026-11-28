package app.fireplayer.live

import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale
import javax.net.ssl.SSLException

/**
 * The decisions behind playlist and EPG updates, without Android so they can be unit-tested: whether a request may
 * go to a provider while something plays, when an automatic update is due, whether a downloaded list may replace the
 * stored one, and the plain words for a failure. [SyncGuard] and [LiveSync] act on them.
 *
 * IPTV providers usually allow one connection per account: a playlist or EPG download while a channel from the same
 * provider plays can be refused, or can make the provider drop or block the stream.
 */
object SyncPlan {

    /** Who asked for the update: the viewer (an Update row, a playlist saved) or the app (opening, the hourly check). */
    enum class Trigger { MANUAL, AUTO }

    enum class Action {
        /** Nothing from this provider plays: ask it now. */
        GO,
        /** A channel from this provider plays and the viewer asked: let go of it, update, then bring it back. */
        LET_GO,
        /** Something plays and the app asked: the update waits for a quiet moment. */
        DEFER,
    }

    /** What plays now, as far as updates care. */
    data class Playing(
        /** The live channel's stream address and the playlist (account) it counts against; null when none plays. */
        val liveUrl: String?,
        val liveAccount: String?,
        /** The film player is in front (its source isn't known here, so it counts as the provider's). */
        val film: Boolean,
        /**
         * The Live TV screen shows, with no channel playing yet: it tunes one any moment (the last channel is
         * read when it opens), so an automatic update holding the provider's connection would have it refused.
         */
        val liveScreen: Boolean = false,
    )

    /**
     * Before a request for [account]'s playlist or EPG goes to [requestUrl]; [providerUrl] is the playlist's own
     * address. A request to another host (an EPG from elsewhere) never touches a live channel's connection. The film
     * player's source isn't known here, so while it's in front every request to a provider's own host counts as
     * clashing with it; the same goes for a Live TV screen about to tune. And while a film plays, no automatic update
     * goes anywhere: an EPG from elsewhere is still tens of MB to download, unpack and store, which the film's own
     * download, its buffer and the TV's flash shouldn't share. It runs at the next quiet moment instead.
     */
    fun decide(trigger: Trigger, account: String, requestUrl: String, providerUrl: String, playing: Playing): Action {
        val own = usesProvider(providerUrl, requestUrl)
        val live = playing.liveUrl != null && (sameHost(playing.liveUrl, requestUrl) || (own && playing.liveAccount == account))
        return when {
            trigger == Trigger.AUTO && (live || playing.film || (own && playing.liveScreen)) -> Action.DEFER
            live -> Action.LET_GO
            else -> Action.GO
        }
    }

    // ---- Work under way -------------------------------------------------------------------------------------------

    /**
     * A request already under way, looked at again as live playback changes: whether it goes on. The viewer's own
     * always does. An automatic one goes on while it would still be started now ([decide] doesn't say DEFER): the
     * provider's own channel playing, or the Live TV screen about to tune one, stops a request to the provider's host
     * (the channel needs that connection); a channel from another provider doesn't (Live TV runs for hours, and its
     * guide must still be updated meanwhile).
     */
    fun requestGoesOn(trigger: Trigger, account: String, requestUrl: String, providerUrl: String, playing: Playing): Boolean =
        trigger == Trigger.MANUAL || decide(trigger, account, requestUrl, providerUrl, playing) != Action.DEFER

    /**
     * Whether an update goes on at its next safe point (a download's next read, storing what was read, clearing old
     * programmes, its next request). The viewer's own always does. An automatic one stops once a film is in front
     * ([film]: its start needs the memory, the line and the flash) or its request was stopped ([requestStopped],
     * [requestGoesOn]). It is put off ([PutOff]), not failed: as if not tried ([Tries]), it runs again at the next
     * quiet moment, and the guide or channels stored stay as they were.
     */
    fun jobGoesOn(trigger: Trigger, film: Boolean, requestStopped: Boolean): Boolean =
        trigger == Trigger.MANUAL || (!film && !requestStopped)

    /** An automatic update stopped at a safe point for a quiet moment ([jobGoesOn]): not a failure. */
    class PutOff : IOException("put off")

    /**
     * When automatic updates last tried each playlist ("p:id") and EPG ("e:id"), and how many tries in a row failed, so
     * a failing one isn't asked at every turn ([isDue]). A try counts as failed until [succeeded] says otherwise (the app
     * may stop meanwhile). One put off part-way ([putOff]) counts as never made: it is due again at the next quiet moment,
     * with no retry wait, and an earlier failure's wait stays. Kept in [store] (beside the Live TV settings, so a broken
     * EPG isn't downloaded again at every start of the app), in memory when none is given. The update thread only.
     */
    class Tries(private val store: Store = Memory()) {
        /** Where the tries are kept: the last try's time and the failures in a row, by key; null for none. */
        interface Store {
            fun tried(key: String): Long?
            fun failed(key: String): Int?
            fun put(key: String, tried: Long?, failed: Int?)
        }

        private class Memory : Store {
            private val tried = HashMap<String, Long>()
            private val failed = HashMap<String, Int>()
            override fun tried(key: String) = tried[key]
            override fun failed(key: String) = failed[key]
            override fun put(key: String, tried: Long?, failed: Int?) {
                if (tried == null) this.tried.remove(key) else this.tried[key] = tried
                if (failed == null) this.failed.remove(key) else this.failed[key] = failed
            }
        }

        private var open: String? = null
        private var before: Pair<Long?, Int?>? = null

        /** When [key] was last tried (0: never). */
        fun last(key: String): Long = store.tried(key) ?: 0L

        /** How many tries of [key] in a row failed. */
        fun failures(key: String): Int = store.failed(key) ?: 0

        /** A request for [key] goes ahead at [now]: it counts as failed until [succeeded]. */
        fun began(key: String, now: Long) {
            open = key
            before = store.tried(key) to store.failed(key)
            store.put(key, now, failures(key) + 1)
        }

        /** [key] worked: nothing is held back for it any more (its own update interval decides). */
        fun succeeded(key: String) {
            if (open == key) open = null
            if (store.tried(key) != null || store.failed(key) != null) store.put(key, null, null)
        }

        /** The request for [key] begun last was put off before it finished: as if it hadn't been tried. */
        fun putOff(key: String) {
            if (open != key) return
            val b = before
            store.put(key, b?.first, b?.second)
            open = null
            before = null
        }
    }

    /** Whether a request goes to the provider's own host (it then takes the update's connection lease). */
    fun usesProvider(providerUrl: String, requestUrl: String): Boolean = sameHost(providerUrl, requestUrl)

    /**
     * The provider connections one update job holds in the shared budget, one lease per playlist (account) at most: a
     * request to the provider's own host, or one that let go of its channel, takes it before it goes. An automatic
     * job gives it back as soon as that request is over ([requestDone]): what comes next (an EPG from another host,
     * clearing old programmes) doesn't use the provider's connection, and Live TV may tune one of its channels
     * meanwhile; the next request to that provider takes it again (or waits). The viewer's own update keeps it to the
     * [end], so a channel it let go of comes back once, after the whole update. The update thread only.
     */
    class Leases {
        private val held = LinkedHashSet<String>()

        /** Whether the job holds [account]'s lease now. */
        fun holds(account: String): Boolean = account in held

        /** The job took [account]'s lease. */
        fun took(account: String) { held += account }

        /** A request is over (done, failed or put off): the leases to give back now. */
        fun requestDone(trigger: Trigger): List<String> = if (trigger == Trigger.AUTO) end() else emptyList()

        /** The job is over: every lease it still holds, to give back. */
        fun end(): List<String> = held.toList().also { held.clear() }
    }

    fun sameHost(a: String?, b: String?): Boolean {
        val ha = hostOf(a) ?: return false
        return ha == hostOf(b)
    }

    /** The host of an address (case-insensitive; "host" alone is read as http://host), or null. */
    fun hostOf(url: String?): String? {
        val t = url?.trim().orEmpty()
        if (t.isEmpty()) return null
        val withScheme = if ("://" in t) t else "http://$t"
        val host = runCatching { URI(withScheme).host }.getOrNull()
            ?: withScheme.substringAfter("://").substringBefore('/').substringBefore('?').substringAfterLast('@').substringBefore(':')
        return host.lowercase(Locale.ROOT).trim('[', ']').takeIf { it.isNotEmpty() }
    }

    /**
     * After an update: the channel waiting in the player comes back (at live) when it is the one the update let go
     * of ([heldKey]), or one whose own comeback the update's lease refused meanwhile ([leaseTaken]); and only while
     * the live screen still shows. A channel the viewer tuned or closed meanwhile is left as it is (their choice wins).
     */
    fun bringBack(heldKey: String?, waitingKey: String?, suspended: Boolean, screenOn: Boolean, leaseTaken: Boolean): Boolean =
        suspended && screenOn && waitingKey != null && (waitingKey == heldKey || leaseTaken)

    /** How long after the stream is let go before the provider is asked: the player closes its connection on its own thread. */
    const val SETTLE_MS = 1_500L

    /** The line shown over the picture while the update holds the channel. */
    fun holdNotice(epgOnly: Boolean): String =
        if (epgOnly) "Updating the EPG - the channel will be back in a moment"
        else "Updating playlists - the channel will be back in a moment"

    // ---- Automatic updates ----------------------------------------------------------------------------------------

    /** After a failed automatic try, the next automatic try waits at least this long (one put off doesn't count). */
    const val RETRY_AFTER_MS = 30L * 60 * 1000

    /** How often the app looks for updates that came due while it stayed open. */
    const val CHECK_EVERY_MS = 60L * 60 * 1000

    /**
     * Whether an automatic update is due: never done, or [lastSuccess] older than [intervalMs]; and not tried (and
     * failed) lately ([retryAfterMs], after [failures] failed tries in a row), so a broken source isn't asked again at
     * every start of or return to the app. A last try that seems to be in the future (the TV's clock was put back) holds
     * nothing back.
     */
    fun isDue(now: Long, lastSuccess: Long, intervalMs: Long, lastTried: Long, failures: Int = 1): Boolean {
        val sinceTried = now - lastTried
        return now - lastSuccess >= intervalMs && (sinceTried < 0 || sinceTried >= retryAfterMs(failures, intervalMs))
    }

    /**
     * How long after a failed automatic try the next one waits, with [failures] failed tries in a row: [RETRY_AFTER_MS]
     * after one, twice that after two, and so on, but never longer than the source's own update [intervalMs]. An EPG the
     * server keeps sending broken is then downloaded a few times a day at most, not at every start of the app; the
     * viewer's own Update always goes at once.
     */
    fun retryAfterMs(failures: Int, intervalMs: Long): Long {
        if (failures <= 1) return RETRY_AFTER_MS
        val grown = RETRY_AFTER_MS shl (failures - 1).coerceAtMost(MAX_DOUBLINGS)
        return grown.coerceAtMost(maxOf(intervalMs, RETRY_AFTER_MS))
    }

    /** Past this many doublings the wait is longer than any update interval anyway (30 min x 2^8 = 128 h). */
    private const val MAX_DOUBLINGS = 8

    // ---- What a download may replace --------------------------------------------------------------------------------

    /**
     * Whether a freshly read channel list may replace the stored one. An empty list never replaces channels the
     * playlist had: an empty or garbled answer is a failed update, never "no channels".
     */
    fun mayReplaceChannels(newCount: Int, storedCount: Int): Boolean = newCount > 0 || storedCount == 0

    /**
     * Whether an EPG download with [count] programmes may replace what the same address gave before ([sameAddress]);
     * an empty one from the same address is a failure. A new address may leave the guide empty (it's what it lists).
     */
    fun mayReplaceGuide(count: Int, storedCount: Int, sameAddress: Boolean): Boolean = count > 0 || storedCount == 0 || !sameAddress

    // ---- Failures in plain words ---------------------------------------------------------------------------------

    /** A download refused by the server with this HTTP status. */
    class HttpStatus(val code: Int) : IOException("HTTP $code")

    /** The answer was larger than a playlist can be (read up to the limit, it would be a cut-off list). */
    class TooLarge : IOException("too large")

    /** The answer wasn't empty, but had nothing in it (no channels, no programmes). */
    class Empty : IOException("empty")

    /** Why a download failed, in plain words (never the address or the login). */
    fun describe(t: Throwable?): String = when (t) {
        null -> "Couldn't reach the server"
        is HttpStatus -> when (t.code) {
            401, 403 -> "The server refused the request (check the login, or too many connections)"
            404 -> "The server didn't find it (check the address)"
            in 500..599 -> "The server had a problem (error ${t.code})"
            else -> "The server answered with an error (${t.code})"
        }
        is TooLarge -> "The file is too large"
        is Empty -> "The file was empty"
        is UnknownHostException -> "No network, or the server address is wrong"
        is ConnectException -> "Couldn't connect to the server"
        is SocketTimeoutException -> "The server took too long to answer"
        is SSLException -> "A secure connection couldn't be made"
        is FileNotFoundException -> "The server didn't find it (check the address)"
        is IOException -> "The download was cut off"
        else -> "The file couldn't be read"
    }
}
