package app.fireplayer.subs

import app.fireplayer.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal client for The Movie Database, with the user's own key, so a subtitle search can ask for the exact film or
 * show, and the player can show its proper name and year. All network calls block; run them off the main thread.
 */
class Tmdb(private val prefs: Prefs) {

    /**
     * A film or show on TMDB. [close]: its name is the one asked for (give or take punctuation and a word like a
     * country), or its year is, so it's surely the same title and not just TMDB's nearest guess.
     */
    class Match(val id: Int, val name: String, val year: String?, val close: Boolean = true) {
        val label: String get() = if (year != null) "$name ($year)" else name
    }

    val isConfigured: Boolean get() = prefs.tmdbApiKey.isNotEmpty()

    /** A close match found before for the same question, without asking TMDB again. Doesn't block. */
    fun cached(name: String, year: Int?, show: Boolean): Match? {
        val parts = prefs.tmdbMatch(cacheKey(name, year, show))?.split('\t') ?: return null
        val id = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val title = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        return Match(id, title, parts.getOrNull(2)?.takeIf { it.isNotEmpty() })
    }

    /** The show (or film) called [name], from [year] when given; null when TMDB has nothing by that name. */
    fun find(name: String, year: Int?, show: Boolean): Match? {
        val path = if (show) "search/tv" else "search/movie"
        val yearParam = if (show) "first_air_date_year" else "year"
        // The year can be the provider's guess; without it is the second try. Then without words in brackets
        // ("The Office (US)"), which TMDB doesn't have in its names.
        val bare = name.replace(BRACKETS, " ").replace(Regex("""\s+"""), " ").trim()
        val results = year?.let { search(path, name, yearParam to it.toString()) }?.takeIf { it.length() > 0 }
            ?: search(path, name, null).takeIf { it.length() > 0 }
            ?: bare.takeIf { it.isNotEmpty() && it != name }?.let { search(path, it, null) }
            ?: return null
        val items = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
        if (items.isEmpty()) return null
        // TMDB puts the most popular first; one with exactly this name, in English or its own language, wins.
        val wanted = simple(name)
        fun names(item: JSONObject) = NAME_FIELDS.map { simple(item.optString(it)) }.filter { it.isNotEmpty() }
        val exact = items.firstOrNull { item -> names(item).any { it == wanted || it == simple(bare) } }
        val best = exact ?: items.first()
        val title = best.optString(if (show) "name" else "title").ifEmpty { name }
        val date = best.optString(if (show) "first_air_date" else "release_date")
        val found = date.take(4).takeIf { it.length == 4 }
        // Nearly the same name: one starts the other and isn't much shorter ("theofficeus" and "theoffice", not
        // "itfollows" and "it").
        val nearly = names(best).any { n ->
            val (short, long) = if (n.length <= wanted.length) n to wanted else wanted to n
            long.startsWith(short) && short.length * 10 >= long.length * 6
        }
        val close = exact != null || nearly || (year != null && found == year.toString())
        val match = Match(best.optInt("id"), title, found, close)
        if (close) prefs.saveTmdbMatch(cacheKey(name, year, show), "${match.id}\t${match.name}\t${match.year.orEmpty()}")
        return match
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

    private fun cacheKey(name: String, year: Int?, show: Boolean) =
        "${if (show) "tv" else "movie"}|${simple(name)}|${year ?: ""}"

    private companion object {
        val NAME_FIELDS = listOf("title", "original_title", "name", "original_name")
        val BRACKETS = Regex("""[(\[][^)\]]*[)\]]""")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
