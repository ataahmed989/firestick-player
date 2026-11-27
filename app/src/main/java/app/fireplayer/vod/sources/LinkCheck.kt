package app.fireplayer.vod.sources

import app.fireplayer.vod.Addon
import app.fireplayer.vod.Failure
import app.fireplayer.vod.FailureKind
import app.fireplayer.vod.SecretUrl
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import app.fireplayer.vod.encodeLinkChars
import app.fireplayer.vod.parseLink
import java.net.URL
import java.net.UnknownHostException
import java.util.Locale

/**
 * Stage 2 (design-brief §6.5, rev_source-failures §4A): opens an add-on's play link itself with redirects off, within
 * [LIMIT_MS], and reads where it points. A notice (Torrentio's /videos/, MediaFusion's /static/exceptions/, StremThru's
 * /v0/store/_/static/, an ElfHosted slate) is a failure by the table; anything else is the film's link, handed to the
 * player. The film itself is never downloaded here. Runs on a background thread; never logs a link.
 */
class LinkCheck(private val hop: Hop, private val online: () -> Boolean, private val elapsed: () -> Long) {

    /** One request without following redirects. Throws IOException when it can't be made. */
    fun interface Hop {
        @Throws(IOException::class)
        fun get(url: String, timeoutMs: Int, cancel: CallHandle): Answer
    }

    class Answer(val code: Int, val location: String?, val contentType: String?)

    sealed class Result {
        class Ready(val link: SecretUrl) : Result()
        class Failed(val failure: Failure) : Result()
    }

    /**
     * Checks [link] from [addon]. [confirmedReady]: FirePlayer played this release before; without it a timeout counts as
     * "possibly being fetched" (the debrid service may have started a download).
     */
    fun check(addon: Addon, link: SecretUrl, confirmedReady: Boolean, cancel: CallHandle = CallHandle()): Result {
        val start = elapsed()
        var url = link.reveal()
        val home = host(url)
        repeat(MAX_HOPS) {
            // Stopped (the play or the rest it was for went) while it waited for a thread, or between hops: nothing more
            // is sent. A connection not yet made isn't stopped by a disconnect, so this is asked before each one.
            if (cancel.cancelled) return Result.Failed(Failure(FailureKind.UNKNOWN, addon, "stopped"))
            val left = LIMIT_MS - (elapsed() - start)
            if (left <= 0) return timedOut(addon, confirmedReady)
            val answer = try {
                hop.get(url, left.toInt(), cancel)
            } catch (e: SocketTimeoutException) {
                return timedOut(addon, confirmedReady)
            } catch (e: IOException) {
                return Result.Failed(unreachable(addon, e))
            }
            when (answer.code) {
                in 300..399 -> {
                    val next = answer.location?.let { resolve(url, it) }
                        ?: return Result.Failed(Failure(FailureKind.UNKNOWN, addon, "redirect without a target"))
                    Notices.classify(next, addon)?.let { return Result.Failed(it) }
                    val nextHost = host(next)
                    // Only the add-on's own hops are followed; any other host is the film's link (the debrid service's,
                    // Real-Debrid's, TorBox's…, whichever the add-on uses).
                    if (nextHost != null && nextHost != home) {
                        return SecretUrl.of(next)?.let { Result.Ready(it) }
                            ?: Result.Failed(Failure(FailureKind.UNKNOWN, addon, "not a web link"))
                    }
                    url = next
                }
                in 200..299 -> {
                    val type = answer.contentType?.lowercase(Locale.ROOT).orEmpty()
                    if (type.startsWith("text/") || type.contains("json") || type.contains("html")) {
                        return Result.Failed(Failure(FailureKind.UNKNOWN, addon, "answered with a page, not a film"))
                    }
                    return SecretUrl.of(url)?.let { Result.Ready(it) }
                        ?: Result.Failed(Failure(FailureKind.UNKNOWN, addon, "not a web link"))
                }
                else -> return Result.Failed(status(answer.code, addon))
            }
        }
        return Result.Failed(Failure(FailureKind.UNKNOWN, addon, "too many redirects"))
    }

    private fun timedOut(addon: Addon, confirmedReady: Boolean): Result.Failed = Result.Failed(
        if (confirmedReady) Failure(FailureKind.SLOW, addon, "no answer in 20 s")
        else Failure(FailureKind.NOT_READY, addon, "no answer in 20 s: the debrid service may be fetching it"),
    )

    private fun unreachable(addon: Addon, e: IOException): Failure = when {
        !online() -> Failure(FailureKind.NO_INTERNET, addon, "no network")
        e is UnknownHostException || e is ConnectException -> Failure(FailureKind.ADDON_DOWN, addon, "couldn't reach ${addon.label}")
        else -> Failure(FailureKind.UNKNOWN, addon, e.javaClass.simpleName)
    }

    private fun status(code: Int, addon: Addon): Failure = when (code) {
        401, 403 -> Failure(FailureKind.LINK_EXPIRED, addon, "HTTP $code")
        429 -> Failure(FailureKind.RATE_LIMITED, addon, "HTTP 429")
        404, 410 -> Failure(FailureKind.LINK_EXPIRED, addon, "HTTP $code")
        451 -> Failure(FailureKind.FILE, addon, "HTTP 451")
        in 500..599 -> Failure(FailureKind.ADDON_DOWN, addon, "HTTP $code")
        else -> Failure(FailureKind.UNKNOWN, addon, "HTTP $code")
    }

    private fun host(url: String): String? = parseLink(url)?.host?.lowercase(Locale.ROOT)

    private fun resolve(base: String, location: String): String? =
        runCatching { parseLink(base)?.resolve(encodeLinkChars(location))?.toString() }.getOrNull()

    companion object {
        const val LIMIT_MS = 20_000L
        const val MAX_HOPS = 5

        /** The app's [Hop]: HttpURLConnection, GET, redirects off; the body is never read. */
        val URL_HOP = Hop { url, timeoutMs, cancel ->
            val conn = URL(url).openConnection() as HttpURLConnection
            cancel.attach(conn)
            try {
                // Stopped as it was made: a disconnect before connecting doesn't stop the request, so none is sent.
                if (cancel.cancelled) throw IOException("stopped")
                conn.instanceFollowRedirects = false
                conn.connectTimeout = timeoutMs.coerceAtMost(10_000)
                conn.readTimeout = timeoutMs
                conn.setRequestProperty("Accept", "*/*")
                val code = conn.responseCode
                Answer(code, conn.getHeaderField("Location"), conn.contentType)
            } finally {
                cancel.detach()
                conn.disconnect()
            }
        }
    }
}

/** Lets another thread stop a request: [cancel] disconnects it (its thread then gets an IOException). */
class CallHandle {
    @Volatile var cancelled = false
        private set
    @Volatile private var conn: HttpURLConnection? = null

    fun attach(c: HttpURLConnection) {
        conn = c
        if (cancelled) runCatching { c.disconnect() }
    }

    fun detach() {
        conn = null
    }

    fun cancel() {
        cancelled = true
        conn?.let { runCatching { it.disconnect() } }
    }
}
