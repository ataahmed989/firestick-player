package app.fireplayer.vod.sources

import app.fireplayer.vod.encodeLinkChars
import app.fireplayer.vod.SecretUrl
import app.fireplayer.vod.SourceTarget
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks an add-on for its streams: GET `{base}/stream/{movie|series}/{id}.json` (Stremio add-on protocol). Blocks: call
 * it on a background thread. The base link holds the user's secret, so neither it nor an exception's message (which
 * can quote it) is ever logged or shown; failures say only what kind they were.
 */
fun interface StreamsHttp {
    @Throws(IOException::class)
    fun get(base: SecretUrl, target: SourceTarget, timeoutMs: Int, cancel: CallHandle): String

    /** The add-on said it is busy (HTTP 429). */
    class Busy : IOException("busy")

    /** Another answer than 200, by code only. */
    class Status(val code: Int) : IOException("HTTP $code")

    companion object {
        /** Answers past this are refused (Torrentio's list for a popular film is about 260 KB inflated). */
        const val MAX_BYTES = 4 shl 20

        /** The full request link: memory only. */
        fun linkFor(base: SecretUrl, target: SourceTarget): String = encodeLinkChars(base.reveal().trimEnd('/') + "/" + target.key)

        /** The app's: HttpURLConnection (Android asks for gzip and inflates it), a size cap, cancellable. */
        val URL_STREAMS = StreamsHttp { base, target, timeoutMs, cancel ->
            val conn = URL(linkFor(base, target)).openConnection() as HttpURLConnection
            cancel.attach(conn)
            try {
                conn.connectTimeout = timeoutMs.coerceAtMost(10_000)
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Accept", "application/json")
                val code = conn.responseCode
                if (code == 429) throw Busy()
                if (code != HttpURLConnection.HTTP_OK) throw Status(code)
                conn.inputStream.use { readCapped(it, MAX_BYTES) }
            } finally {
                cancel.detach()
                conn.disconnect()
            }
        }

        fun readCapped(input: InputStream, max: Int): String {
            val out = ByteArrayOutputStream(64 * 1024)
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > max) throw IOException("answer too large")
            }
            return out.toString("UTF-8")
        }
    }
}
