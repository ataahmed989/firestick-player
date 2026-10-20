package app.fireplayer.player

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds the episode after the one playing. TiviMate hands over one episode at a time, but its link
 * (http://host:port/series/USER/PASSWORD/EPISODE.mkv) carries the provider's address and login, so the provider's own
 * catalogue can be asked: which show holds this episode, and what comes after it.
 *
 * The provider can't be asked which show an episode belongs to, so the show is looked up by name in its list of
 * shows and then confirmed by finding this very episode in it. Once confirmed, every episode of the show is
 * remembered, so later episodes take one small request. Runs on a background thread, after the film has started,
 * and only reads the provider's lists, never video.
 */
class NextEpisode(context: Context, private val headers: Map<String, String>) {

    class Found(val uri: Uri, val title: String)

    private class Link(val scheme: String, val authority: String, val user: String, val password: String, val episodeId: String) {
        val base get() = "$scheme://$authority"
    }

    private class Episode(val id: String, val extension: String, val season: Int, val number: Int, val title: String)

    private val prefs = context.getSharedPreferences("next_episode", Context.MODE_PRIVATE)
    private val cacheDir = context.cacheDir

    /** Null when the link isn't a provider's episode link, or the next one can't be found (or there is none). */
    fun find(uri: Uri, show: String): Found? {
        val link = parse(uri) ?: return null
        val known = prefs.getString(seriesKey(link, link.episodeId), null)
        if (known != null) {
            val episodes = episodes(link, known)
            if (episodes != null && episodes.any { it.id == link.episodeId }) return after(link, show, episodes)
        }
        val wanted = normalize(show)
        if (wanted.isEmpty()) return null
        for (fresh in listOf(false, true)) {
            val shows = shows(link, fresh) ?: return null
            for (seriesId in candidates(shows, wanted)) {
                val episodes = episodes(link, seriesId) ?: continue
                if (episodes.none { it.id == link.episodeId }) continue
                remember(link, seriesId, episodes)
                return after(link, show, episodes)
            }
            // An old copy of the list may not have this show yet; a fresh one is fetched once.
        }
        Log.i(TAG, "No show named \"$show\" holds episode ${link.episodeId}")
        return null
    }

    private fun after(link: Link, show: String, episodes: List<Episode>): Found? {
        val next = episodes.getOrNull(episodes.indexOfFirst { it.id == link.episodeId } + 1) ?: return null
        val uri = Uri.Builder().scheme(link.scheme).encodedAuthority(link.authority)
            .appendPath("series").appendPath(link.user).appendPath(link.password)
            .appendPath("${next.id}.${next.extension}")
            .build()
        // Names usually read "Show - S01E03 - Title"; otherwise one is made up in that shape.
        val named = TitleInfo.parse(next.title, null).episode != null
        val title = if (named) next.title else "$show S%02dE%02d %s".format(next.season, next.number, next.title).trim()
        return Found(uri, title)
    }

    // ---------------------------------------------------------------------
    // The provider's lists

    /** Every episode of a show in watching order, or null when the provider doesn't answer. */
    private fun episodes(link: Link, seriesId: String): List<Episode>? {
        val text = get(api(link, "get_series_info", "series_id" to seriesId), MAX_INFO_BYTES)?.toString(Charsets.UTF_8)
            ?: return null
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val seasons = mutableListOf<Pair<Int, Any?>>()
        // Seasons come keyed by number, or (when numbered from 0) as a plain list.
        when (val all = root.opt("episodes")) {
            is JSONObject -> all.keys().forEach { key -> seasons += (key.toIntOrNull() ?: 0) to all.opt(key) }
            is JSONArray -> for (i in 0 until all.length()) seasons += i to all.opt(i)
        }
        val fallbackExtension = "mkv"
        return seasons.sortedBy { it.first }.flatMap { (season, list) ->
            // A season with one episode can come as that episode on its own.
            val items = when (list) {
                is JSONArray -> (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                is JSONObject -> listOf(list)
                else -> emptyList()
            }
            items.mapIndexedNotNull { i, e ->
                val id = e.optString("id").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
                Episode(
                    id = id,
                    extension = e.optString("container_extension").ifEmpty { fallbackExtension },
                    season = e.optString("season").toIntOrNull() ?: season,
                    number = e.optString("episode_num").toIntOrNull() ?: (i + 1),
                    title = e.optString("title"),
                )
            }
        }
    }

    /** The provider's shows as (id, name), from a copy kept for a day unless [fresh]. */
    private fun shows(link: Link, fresh: Boolean): List<Pair<String, String>>? {
        val file = File(cacheDir, "shows-${(link.base + "|" + link.user).hashCode().toUInt()}.tsv")
        val young = file.exists() && System.currentTimeMillis() - file.lastModified() < SHOWS_FOR_MS
        if (!fresh && young) {
            readShows(file)?.let { return it }
        }
        // A copy just fetched won't be any different.
        if (fresh && young && file.lastModified() > startedAt) return null
        val shows = fetchShows(link) ?: return null
        runCatching { file.writeText(shows.joinToString("\n") { "${it.first}\t${it.second}" }) }
        return shows
    }

    private fun readShows(file: File): List<Pair<String, String>>? = runCatching {
        file.readLines().mapNotNull { line -> line.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
    }.getOrNull()

    /** The list can run to tens of megabytes, so only the id and name of each show are kept as it's read. */
    private fun fetchShows(link: Link): List<Pair<String, String>>? = open(api(link, "get_series"))?.use { stream ->
        runCatching {
            val reader = JsonReader(stream.reader(Charsets.UTF_8))
            // A login the provider turns down comes back as an object instead of a list.
            if (reader.peek() != JsonToken.BEGIN_ARRAY) return@runCatching null
            val shows = mutableListOf<Pair<String, String>>()
            reader.beginArray()
            while (reader.hasNext()) {
                var id: String? = null
                var name: String? = null
                var title: String? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    val token = reader.peek()
                    when {
                        token != JsonToken.STRING && token != JsonToken.NUMBER -> reader.skipValue()
                        key == "series_id" -> id = reader.nextString()
                        key == "name" -> name = reader.nextString()
                        key == "title" -> title = reader.nextString()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                val shown = title?.takeIf { it.isNotBlank() } ?: name
                if (id != null && shown != null) shows += id to shown.replace('\t', ' ').replace('\n', ' ')
            }
            shows.distinctBy { it.first }
        }.getOrNull()
    }

    /** The shows whose names match, best first: the same name, then one name inside the other. */
    private fun candidates(shows: List<Pair<String, String>>, wanted: String): List<String> {
        val scored = shows.mapNotNull { (id, name) ->
            val have = normalize(name)
            when {
                have.isEmpty() -> null
                have == wanted -> id to 0
                have.length >= 3 && (have in wanted || wanted in have) -> id to 1
                else -> null
            }
        }
        return scored.sortedBy { it.second }.map { it.first }.distinct().take(MAX_CANDIDATES)
    }

    private fun remember(link: Link, seriesId: String, episodes: List<Episode>) {
        // Kept small: a few shows' worth.
        if (prefs.all.size > MAX_REMEMBERED) prefs.edit().clear().apply()
        val edit = prefs.edit()
        episodes.forEach { edit.putString(seriesKey(link, it.id), seriesId) }
        edit.apply()
    }

    // ---------------------------------------------------------------------
    // Requests

    private fun api(link: Link, action: String, vararg params: Pair<String, String>): String {
        val builder = Uri.parse(link.base).buildUpon().appendPath("player_api.php")
            .appendQueryParameter("username", link.user)
            .appendQueryParameter("password", link.password)
            .appendQueryParameter("action", action)
        params.forEach { builder.appendQueryParameter(it.first, it.second) }
        return builder.build().toString()
    }

    private fun get(url: String, maxBytes: Int): ByteArray? = open(url)?.use { stream ->
        runCatching {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > maxBytes) return@runCatching null
            }
            out.toByteArray()
        }.getOrNull()
    }

    private fun open(url: String): InputStream? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        // The provider may turn away an app it doesn't know, so it hears from the same app as the video.
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        conn.setRequestProperty("User-Agent", userAgent ?: "FirePlayer/1.0 (Linux; Android)")
        headers.filterKeys { !it.equals("User-Agent", true) }.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            return@runCatching null
        }
        conn.inputStream
    }.onFailure { Log.i(TAG, "Couldn't ask the provider: $it") }.getOrNull()

    private fun seriesKey(link: Link, episodeId: String) = "${link.base}|${link.user}|$episodeId"

    companion object {
        private const val TAG = "FirePlayerNext"
        private const val SHOWS_FOR_MS = 24 * 60 * 60_000L
        private const val MAX_INFO_BYTES = 8 shl 20
        private const val MAX_CANDIDATES = 3
        private const val MAX_REMEMBERED = 5_000
        private val startedAt = System.currentTimeMillis()

        /** Whether [uri] is a provider's episode link the next episode can be found from. */
        fun canFind(uri: Uri): Boolean = parse(uri) != null

        private fun parse(uri: Uri): Link? {
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            val authority = uri.encodedAuthority ?: return null
            val parts = uri.pathSegments
            if (parts.size != 4 || !parts[0].equals("series", true)) return null
            val episodeId = parts[3].substringBeforeLast('.').takeIf { id -> id.isNotEmpty() && id.all { it.isDigit() } }
                ?: return null
            return Link(scheme, authority, parts[1], parts[2], episodeId)
        }

        /** Lower case letters and digits only, without the provider's tags or the year, so names compare. */
        fun normalize(name: String): String =
            TitleInfo.parse(name, null).searchQuery.lowercase().filter { it.isLetterOrDigit() }
    }
}
