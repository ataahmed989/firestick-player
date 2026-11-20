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
     * address. A request to another host (an EPG from elsewhere) never touches playback. The film player's source
     * isn't known here, so while it's in front every request to a provider's own host counts as clashing with it;
     * the same goes for a Live TV screen about to tune.
     */
    fun decide(trigger: Trigger, account: String, requestUrl: String, providerUrl: String, playing: Playing): Action {
        val own = usesProvider(providerUrl, requestUrl)
        val live = playing.liveUrl != null && (sameHost(playing.liveUrl, requestUrl) || (own && playing.liveAccount == account))
        return when {
            trigger == Trigger.AUTO && (live || (own && (playing.film || playing.liveScreen))) -> Action.DEFER
            live -> Action.LET_GO
            else -> Action.GO
        }
    }

    /** Whether a request goes to the provider's own host (it then takes the update's connection lease). */
    fun usesProvider(providerUrl: String, requestUrl: String): Boolean = sameHost(providerUrl, requestUrl)

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

    /** After a failed or deferred automatic try, the next automatic try waits at least this long. */
    const val RETRY_AFTER_MS = 30L * 60 * 1000

    /** How often the app looks for updates that came due while it stayed open. */
    const val CHECK_EVERY_MS = 60L * 60 * 1000

    /**
     * Whether an automatic update is due: never done, or [lastSuccess] older than [intervalMs]; and not tried (and
     * failed) within [RETRY_AFTER_MS], so a broken source isn't asked again at every return to the app.
     */
    fun isDue(now: Long, lastSuccess: Long, intervalMs: Long, lastTried: Long): Boolean =
        now - lastSuccess >= intervalMs && now - lastTried >= RETRY_AFTER_MS

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
