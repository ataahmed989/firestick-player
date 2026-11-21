package app.fireplayer.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The film's network connection read with up to two range requests at a time ([TwoRanges], [TwoRangeStream]); the
 * extractor above still sees one stream of bytes in order.
 *
 * Only a film file's own requests take part: Media3's progressive source asks for a file from a position to its end,
 * with its "Icy-MetaData" header. Anything else (an HLS or DASH playlist or part, a bounded request) goes to [http]
 * exactly as it would without this.
 *
 * [http] is the first request, the app's usual connection; the second is a plain HttpURLConnection to the address the
 * first was answered from ([HttpRangeRequest]), with the same headers.
 */
@OptIn(UnstableApi::class)
class TwoRangeDataSource(
    private val http: HttpDataSource,
    film: TwoRanges,
    private val headers: Map<String, String>,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
) : DataSource {

    private var spec: DataSpec? = null
    private var direct = true
    // Where the link's last fresh request was answered from, after the provider's redirects; its answer's headers.
    private var landed: Uri? = null
    private var landedHeaders: Map<String, List<String>> = emptyMap()

    private val first = object : TwoRanges.Request {
        override fun open(start: Long, length: Long, fresh: Boolean, probe: Boolean): TwoRanges.Answer {
            val s = spec ?: throw IOException("Opened after it was closed")
            var d = s.subrange(start - s.position, if (length < 0L) C.LENGTH_UNSET.toLong() else length)
            if (!fresh) landed?.let { d = d.withUri(it) }
            // From byte 0 Media3 sends no Range header; the server's answer to one is what's being asked.
            if (probe) d = d.withAdditionalHeaders(mapOf("Range" to "bytes=$start-"))
            val n = http.open(d)
            if (fresh) {
                landed = http.uri
                landedHeaders = http.responseHeaders
            }
            var contentRange: String? = null
            for ((name, values) in http.responseHeaders) {
                if (name.equals("Content-Range", ignoreCase = true)) contentRange = values.firstOrNull()
            }
            return TwoRanges.Answer(http.responseCode, contentRange, n)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = http.read(buffer, offset, length)

        override fun close() = http.close()

        override fun statusOf(e: IOException): Int = (e as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: -1
    }

    private val stream = TwoRangeStream(film, first) {
        val s = spec
        val all = HashMap<String, String>(headers)
        if (s != null) all.putAll(s.httpRequestHeaders)
        HttpRangeRequest((landed ?: s?.uri).toString(), all, connectTimeoutMs, readTimeoutMs)
    }

    override fun addTransferListener(transferListener: TransferListener) = http.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        landed = null
        landedHeaders = emptyMap()
        direct = !filmRequest(dataSpec)
        return if (direct) http.open(dataSpec) else stream.open(dataSpec.position, dataSpec.uri.toString())
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        if (direct) http.read(buffer, offset, length) else stream.read(buffer, offset, length)

    // Between two segments the first request has no connection open: the address it last landed on still stands.
    override fun getUri(): Uri? = if (direct) http.uri else landed ?: http.uri

    override fun getResponseHeaders(): Map<String, List<String>> = if (direct) http.responseHeaders else landedHeaders

    override fun close() {
        try {
            if (!direct) stream.close()
        } finally {
            direct = true
            spec = null
            http.close()
        }
    }

    class Factory(
        private val http: HttpDataSource.Factory,
        private val film: TwoRanges,
        /** Every header the first request sends by default, the User-Agent among them: the second sends the same. */
        private val headers: Map<String, String>,
        private val connectTimeoutMs: Int,
        private val readTimeoutMs: Int,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            TwoRangeDataSource(http.createDataSource(), film, headers, connectTimeoutMs, readTimeoutMs)
    }

    companion object {
        /** The header Media3's progressive source (a film in one file) puts on its requests, and nothing else does. */
        private const val PROGRESSIVE_HEADER = "Icy-MetaData"

        /** A film file asked for from a position to its end, over HTTP. */
        fun filmRequest(spec: DataSpec): Boolean {
            val scheme = spec.uri.scheme
            return spec.length == C.LENGTH_UNSET.toLong() &&
                spec.httpMethod == DataSpec.HTTP_METHOD_GET &&
                spec.flags and DataSpec.FLAG_ALLOW_GZIP == 0 &&
                (scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)) &&
                spec.httpRequestHeaders.keys.any { it.equals(PROGRESSIVE_HEADER, ignoreCase = true) }
        }
    }
}

/**
 * The second request: one HTTP range request at a time to [link], over the platform's HttpURLConnection like the
 * first, so a segment read to its end leaves its connection for the next: [close] then only closes the body and the
 * connection stays in the platform's keep-alive pool. (Measured on a Fire TV Stick 4K Max, Fire OS 8: a range read
 * to its last byte, then asked again: 36 ms on the same connection against 435 ms on a new one, with or without
 * disconnect() after the last byte, which is what Media3's own data source does for the first request.) A range not
 * read to its end is never left on a connection to be used again: it is disconnected. [cancel] from another thread
 * disconnects, which ends a read in progress at once. [link] is never logged.
 */
class HttpRangeRequest(
    private val link: String,
    private val headers: Map<String, String>,
    private val connectTimeoutMs: Int,
    private val readTimeoutMs: Int,
) : TwoRanges.Request {

    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var cancelled = false
    private var body: InputStream? = null
    // Of the range asked for, what hasn't been read yet; below 0: not known (asked to the file's end).
    private var left = -1L

    override fun open(start: Long, length: Long, fresh: Boolean, probe: Boolean): TwoRanges.Answer {
        val c = URL(link).openConnection() as? HttpURLConnection ?: throw IOException("Not an HTTP link")
        connection = c
        if (cancelled) {
            c.disconnect()
            throw IOException("Cancelled")
        }
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = readTimeoutMs
        for ((name, value) in headers) c.setRequestProperty(name, value)
        c.setRequestProperty("Range", "bytes=$start-" + if (length < 0L) "" else (start + length - 1).toString())
        // As the first request asks: the bytes as they are in the file.
        c.setRequestProperty("Accept-Encoding", "identity")
        val status = c.responseCode
        val contentRange = c.getHeaderField("Content-Range")
        if (status == HttpURLConnection.HTTP_PARTIAL) {
            body = c.inputStream
            left = length
        }
        return TwoRanges.Answer(status, contentRange, length)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = (body ?: throw IOException("Not open")).read(buffer, offset, length)
        if (n > 0 && left > 0L) left -= n
        return n
    }

    override fun close() {
        val c = connection
        // Every byte of the range has come: the connection is whole and can carry the next segment's request.
        val whole = body != null && left == 0L && !cancelled
        try {
            body?.close()
        } finally {
            body = null
            left = -1L
            connection = null
            if (!whole) c?.disconnect()
        }
    }

    override fun cancel() {
        cancelled = true
        connection?.disconnect()
    }
}
