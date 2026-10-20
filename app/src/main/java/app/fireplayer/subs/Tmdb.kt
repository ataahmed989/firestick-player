package app.fireplayer.subs

import app.fireplayer.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal client for The Movie Database, with the user's own key, so a subtitle search can ask for the exact film or
 * show. All calls block; run them off the main thread.
 */
class Tmdb(private val prefs: Prefs) {

    /** A film or show on TMDB. */
    class Match(val id: Int, val name: String, val year: String?) {
        val label: String get() = if (year != null) "$name ($year)" else name
    }

    val isConfigured: Boolean get() = prefs.tmdbApiKey.isNotEmpty()

    /** The show (or film) called [name], from [year] when given; null when TMDB has nothing by that name. */
    fun find(name: String, year: Int?, show: Boolean): Match? {
        val path = if (show) "search/tv" else "search/movie"
        val yearParam = if (show) "first_air_date_year" else "year"
        // The year can be the provider's guess; without it is the second try.
        val results = year?.let { search(path, name, yearParam to it.toString()) }?.takeIf { it.length() > 0 }
            ?: search(path, name, null)
        val items = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
        if (items.isEmpty()) return null
        // TMDB puts the most popular first; one with exactly this name, in English or its own language, wins.
        val wanted = simple(name)
        val best = items.firstOrNull { item ->
            listOf("title", "original_title", "name", "original_name").any { simple(item.optString(it)) == wanted }
        } ?: items.first()
        val title = best.optString(if (show) "name" else "title").ifEmpty { name }
        val date = best.optString(if (show) "first_air_date" else "release_date")
        return Match(best.optInt("id"), title, date.take(4).takeIf { it.length == 4 })
    }

    /** Null when TMDB takes the key, otherwise what went wrong. */
    fun check(): String? = runCatching { request("authentication", emptyList()) }.exceptionOrNull()
        ?.let { it.message ?: "Couldn't reach TMDB." }

    private fun search(path: String, query: String, extra: Pair<String, String>?): JSONArray {
        val params = listOfNotNull("query" to query, "include_adult" to "false", extra)
        return JSONObject(request(path, params)).optJSONArray("results") ?: JSONArray()
    }

    private fun request(path: String, params: List<Pair<String, String>>): String {
        val key = prefs.tmdbApiKey
        // The long "API Read Access Token" goes in a header; the short "API Key" in the address.
        val token = key.length > 40
        val all = if (token) params else params + ("api_key" to key)
        val query = all.joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }
        val conn = URL("https://api.themoviedb.org/3/$path" + if (query.isEmpty()) "" else "?$query")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Accept", "application/json")
        if (token) conn.setRequestProperty("Authorization", "Bearer $key")
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                if (code == 401) error("TMDB didn't accept this key")
                val message = runCatching { JSONObject(text).optString("status_message") }.getOrNull()
                error(message?.ifEmpty { null } ?: "TMDB error $code")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun simple(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
