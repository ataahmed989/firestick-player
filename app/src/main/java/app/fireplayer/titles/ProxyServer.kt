package app.fireplayer.titles

import android.net.Uri
import android.util.Log
import app.fireplayer.titles.TitleBook.Kind
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Stands between TiviMate and a provider, on this TV only (127.0.0.1:[TitleProxy.PORT]). TiviMate's server address
 * names the provider after it ("http://127.0.0.1:8000/proxpanel.fans:80"), so any provider works without setup.
 *
 * Only the lists of films and shows (and their details) are changed, and only their names: the rest of every answer
 * goes through byte for byte. Videos, live channels and catch-up never pass through here: TiviMate is sent straight
 * to the provider for them, so playback is exactly as before. The TV guide and anything else the provider answers
 * passes through untouched.
 */
class ProxyServer(
    private val book: TitleBook,
    /** Called after a list brought titles TMDB hasn't been asked about. */
    private val onNewTitles: () -> Unit,
) {
    @Volatile private var socket: ServerSocket? = null
    private val workers: ExecutorService = ThreadPoolExecutor(
        2, MAX_CONNECTIONS, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { r -> Thread(r, "TitleProxy").apply { isDaemon = true } },
        ThreadPoolExecutor.CallerRunsPolicy(),
    )
    // Writes what a list brought to the book after TiviMate has it, one list at a time.
    private val recorder: ExecutorService = ThreadPoolExecutor(
        1, 1, 0, TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue(),
        { r -> Thread(r, "TitleRecorder").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } },
    )

    /** Listens until [stop]; false when the port is taken. */
    fun start(): Boolean {
        val server = runCatching {
            ServerSocket(TitleProxy.PORT, 50, InetAddress.getByName("127.0.0.1")).apply { reuseAddress = true }
        }.onFailure { Log.w(TAG, "Can't listen on ${TitleProxy.PORT}: $it") }.getOrNull() ?: return false
        socket = server
        Thread({
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: SocketException) {
                    break
                }
                workers.execute { serve(client) }
            }
        }, "TitleProxyAccept").apply { isDaemon = true }.start()
        return true
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private class Request(val method: String, val target: String, val headers: List<Pair<String, String>>)

    private fun serve(client: Socket) {
        try {
            handle(client)
        } finally {
            runCatching { client.close() }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 30_000
        val input = client.getInputStream()
        val output = BufferedOutputStream(client.getOutputStream(), 64 * 1024)
        val request = readRequest(input) ?: return
        val path = request.target.substringBefore('?')
        val query = request.target.substringAfter('?', "")
        val parts = path.trimStart('/').split('/', limit = 2)
        val server = parts[0]
        if (!TitleProxy.isServer(server)) {
            respondText(output, 404, "In TiviMate, use ${TitleProxy.addressFor("your.provider:port")} as the server address.")
            return
        }
        val rest = "/" + parts.getOrElse(1) { "" }
        val upstream = "http://$server$rest" + if (query.isNotEmpty()) "?$query" else ""
        try {
            when {
                rest.endsWith("/player_api.php") -> api(request, server, upstream, query, output)
                // Catch-up is a video; other .php answers (the TV guide, a playlist) and the bare address pass through.
                rest.endsWith(".php") && !rest.endsWith("/timeshift.php") -> pass(request, upstream, output)
                rest == "/" -> pass(request, upstream, output)
                // Films, episodes, live channels: straight to the provider.
                else -> respond(output, 302, "Found", listOf("Location" to upstream), null)
            }
        } catch (e: Exception) {
            Log.i(TAG, "${request.method} $server$rest${ACTION.find(query)?.let { " (${it.groupValues[1]})" }.orEmpty()} failed: $e")
            runCatching { respondText(output, 502, "The provider couldn't be reached.") }
        }
    }

    // ---------------------------------------------------------------------
    // The provider's API

    private fun api(request: Request, server: String, upstream: String, query: String, output: OutputStream) {
        val params = Uri.parse("http://x/?$query")
        val action = params.getQueryParameter("action")
        val started = System.currentTimeMillis()
        val conn = open(request, upstream)
        try {
            val code = conn.responseCode
            val body = body(conn)
            if (code != 200 || body == null) {
                Log.i(TAG, "$server ${action ?: "account"}: the provider answered $code")
                relay(conn, code, body, output)
                return
            }
            when (action) {
                "get_vod_streams", "get_series" -> {
                    val (kind, idKey) = if (action == "get_series") Kind.SHOW to "series_id" else Kind.MOVIE to "stream_id"
                    val (listed, renamed) = list(server, kind, idKey, conn, body, output)
                    Log.i(TAG, "$server $action: $listed listed, $renamed renamed, ${System.currentTimeMillis() - started} ms")
                }
                "get_vod_categories" -> categories(server, Kind.MOVIE, conn, body, output)
                "get_series_categories" -> categories(server, Kind.SHOW, conn, body, output)
                "get_vod_info" -> info(server, Kind.MOVIE, params.getQueryParameter("vod_id"), conn, body, output)
                "get_series_info" -> info(server, Kind.SHOW, params.getQueryParameter("series_id"), conn, body, output)
                else -> {
                    relay(conn, code, body, output)
                    Log.i(TAG, "$server ${action ?: "account"}: ${System.currentTimeMillis() - started} ms")
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** A list of films or shows, renamed as it streams through. */
    /** How many titles it listed, and how many of them went out renamed. */
    private fun list(
        server: String,
        kind: Kind,
        idKey: String,
        conn: HttpURLConnection,
        body: InputStream,
        output: OutputStream,
    ): Pair<Int, Int> {
        // TiviMate hears back at once, while what's known is read.
        respond(output, 200, "OK", contentType(conn), null)
        val known = book.known(server, kind)
        val fresh = ArrayList<TitleBook.Listed>()
        var listed = 0
        var renamed = 0
        JsonList(body, output).copy { item ->
            val id = item.number(idKey)
            val raw = item.text("name") ?: return@copy null
            listed++
            val k = known[id]
            if (k != null && k.rawHash == raw.hashCode()) {
                if (k.title != null) renamed++
                return@copy k.title
            }
            val year = (item.text("year") ?: item.text("release_date") ?: item.text("releaseDate"))
                ?.let { YEAR.find(it)?.value?.toIntOrNull() } ?: 0
            val tmdb = item.number("tmdb").takeIf { it > 0 } ?: item.number("tmdb_id")
            fresh += TitleBook.Listed(id, raw, tmdb, item.text("category_id"), year)
            null
        }
        output.flush()
        if (fresh.isNotEmpty()) {
            Log.i(TAG, "$server: ${fresh.size} new ${kind.name.lowercase()} titles")
            recorder.execute {
                runCatching { book.record(server, kind, fresh) }.onFailure { Log.w(TAG, "Couldn't record titles: $it") }
                onNewTitles()
            }
        }
        return listed to renamed
    }

    /** Category names pass through; they're noted for the quality they may say ("4K Movies"). */
    private fun categories(server: String, kind: Kind, conn: HttpURLConnection, body: InputStream, output: OutputStream) {
        val bytes = body.readBytes()
        respond(output, 200, "OK", contentType(conn), bytes)
        recorder.execute {
            runCatching {
                val list = JSONArray(String(bytes, Charsets.UTF_8))
                val names = HashMap<String, String>()
                for (i in 0 until list.length()) {
                    val c = list.optJSONObject(i) ?: continue
                    names[c.optString("category_id")] = c.optString("category_name")
                }
                book.saveCategories(server, kind, names)
            }
        }
    }

    /**
     * One film's or show's details: its name as in the list. A show's episodes are noted with it, so FirePlayer can
     * show an episode under the show's English name.
     */
    private fun info(server: String, kind: Kind, id: String?, conn: HttpURLConnection, body: InputStream, output: OutputStream) {
        val bytes = body.readBytes()
        val number = id?.toIntOrNull()
        val title = number?.let { book.title(server, kind, it) }
        val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
        val renamed = if (title != null && json != null) {
            runCatching {
                json.optJSONObject("info")?.let { info -> if (info.has("name")) info.put("name", title) }
                json.optJSONObject("movie_data")?.let { data -> if (data.has("name")) data.put("name", title) }
                json.toString().toByteArray(Charsets.UTF_8)
            }.getOrNull()
        } else null
        respond(output, 200, "OK", contentType(conn), renamed ?: bytes)
        if (kind == Kind.SHOW && number != null && json != null) {
            recorder.execute {
                runCatching { book.recordEpisodes(server, episodesIn(json, number)) }
                    .onFailure { Log.w(TAG, "Couldn't note episodes: $it") }
            }
        }
    }

    /** The episodes in get_series_info: by season, as an object ({"1": [...]}) or a list of lists. */
    private fun episodesIn(json: JSONObject, series: Int): List<TitleBook.Episode> {
        val seasons = when (val e = json.opt("episodes")) {
            is JSONObject -> e.keys().asSequence().mapNotNull { e.optJSONArray(it) }.toList()
            is JSONArray -> (0 until e.length()).mapNotNull { e.optJSONArray(it) }
            else -> emptyList()
        }
        return seasons.flatMap { list ->
            (0 until list.length()).mapNotNull { i ->
                val ep = list.optJSONObject(i) ?: return@mapNotNull null
                val id = ep.optString("id").toIntOrNull() ?: return@mapNotNull null
                TitleBook.Episode(id, series, ep.optString("season").toIntOrNull() ?: 0, ep.optString("episode_num").toIntOrNull() ?: 0)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Passing through

    private fun pass(request: Request, upstream: String, output: OutputStream) {
        val conn = open(request, upstream)
        try {
            relay(conn, conn.responseCode, body(conn), output)
        } finally {
            conn.disconnect()
        }
    }

    private fun relay(conn: HttpURLConnection, code: Int, body: InputStream?, output: OutputStream) {
        val headers = contentType(conn) + listOfNotNull(conn.getHeaderField("Content-Disposition")?.let { "Content-Disposition" to it })
        respond(output, code, conn.responseMessage ?: "", headers, null)
        body?.use { it.copyTo(output, 64 * 1024) }
        output.flush()
    }

    /** Asks the provider just as TiviMate asked us: the same app name and headers. */
    private fun open(request: Request, url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.requestMethod = if (request.method == "HEAD") "HEAD" else "GET"
        for ((k, v) in request.headers) if (k.lowercase() !in HOP_HEADERS) conn.addRequestProperty(k, v)
        conn.setRequestProperty("Accept-Encoding", "gzip")
        return conn
    }

    private fun body(conn: HttpURLConnection): InputStream? {
        val stream = (if (conn.responseCode < 400) conn.inputStream else conn.errorStream) ?: return null
        return if (conn.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(stream, 64 * 1024) else stream
    }

    private fun contentType(conn: HttpURLConnection): List<Pair<String, String>> =
        listOfNotNull(conn.contentType?.let { "Content-Type" to it })

    // ---------------------------------------------------------------------
    // HTTP, the little TiviMate needs: one request per connection.

    private fun readRequest(input: InputStream): Request? {
        val first = readLine(input) ?: return null
        val words = first.split(' ')
        if (words.size < 2) return null
        val headers = ArrayList<Pair<String, String>>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            if (headers.size > 100) return null
            val colon = line.indexOf(':')
            if (colon > 0) headers += line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }
        return Request(words[0], words[1], headers)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 16 * 1024) return null
            sb.append(c.toChar())
        }
    }

    private fun respondText(output: OutputStream, code: Int, text: String) =
        respond(output, code, if (code == 404) "Not Found" else "Bad Gateway", listOf("Content-Type" to "text/plain; charset=utf-8"),
            text.toByteArray())

    /** The status and headers, then [body] when given (otherwise the caller streams it, until the connection closes). */
    private fun respond(output: OutputStream, code: Int, reason: String, headers: List<Pair<String, String>>, body: ByteArray?) {
        val head = StringBuilder("HTTP/1.1 $code $reason\r\n")
        headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
        if (body != null || code == 302) head.append("Content-Length: ").append(body?.size ?: 0).append("\r\n")
        head.append("Connection: close\r\n\r\n")
        output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        body?.let { output.write(it) }
        output.flush()
    }

    private companion object {
        const val TAG = "FirePlayerTitles"
        const val MAX_CONNECTIONS = 16
        val YEAR = Regex("""(19|20)\d{2}""")
        // Only the action goes in the log, never the login around it.
        val ACTION = Regex("""(?:^|&)action=([A-Za-z_]+)""")
        val HOP_HEADERS = setOf("host", "connection", "accept-encoding", "content-length", "transfer-encoding", "keep-alive")
    }
}
