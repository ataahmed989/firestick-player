package app.fireplayer.vod.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches a text answer (Cinemeta's JSON). Blocks: call it on a background thread. Throws [HttpStatus] for an answer
 * other than 200 and an IOException when the server can't be reached. Never logs the address.
 */
fun interface Http {
    @Throws(IOException::class)
    fun get(url: String): String
}

/** The server answered with [code] instead of 200. */
class HttpStatus(val code: Int) : IOException("HTTP $code")

/**
 * The app's [Http]: HttpURLConnection. Redirects are followed (Cinemeta's catalogs answer 307 to another https host),
 * and Accept-Encoding is left alone so Android asks for gzip and inflates it itself.
 */
object UrlHttp : Http {
    override fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw HttpStatus(code)
            return conn.inputStream.use { readCapped(it, MAX_BYTES) }
        } finally {
            conn.disconnect()
        }
    }

    /** The whole of [input] as UTF-8, or an IOException past [max] bytes (a series page is about 0.7 MB). */
    internal fun readCapped(input: InputStream, max: Int): String {
        val out = ByteArrayOutputStream(64 * 1024)
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > max) throw IOException("Answer too large")
        }
        return out.toString("UTF-8")
    }

    private const val MAX_BYTES = 8 shl 20
}
