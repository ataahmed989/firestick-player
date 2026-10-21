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
     * country), or its year is, so it's surely the same title and not just TMDB's nearest guess. [exact]: the very
     * name asked for.
     */
    class Match(val id: Int, val name: String, val year: String?, val close: Boolean = true, val exact: Boolean = true) {
        val label: String get() = if (year != null) "$name ($year)" else name
    }

    val isConfigured: Boolean get() = prefs.tmdbApiKey.isNotEmpty()

    /**
     * The film or show TMDB confirms for a name a provider wrote. Tried as it is first, then without words in
     * brackets, then without a short last word or two that may be a tag the name's clean-up didn't know
     * ("Blank1 Blank2 xyz"). A shortened name only counts when TMDB has a title by exactly that name (and year, when
     * one is known), so it can't wander off to another title. Null when nothing is confirmed; remembered either way
     * (a miss for a few days), so the next time is instant.
     */
    fun verify(name: String, year: Int?, show: Boolean): Match? {
        val key = cacheKey(name, year, show)
        for ((i, candidate) in variants(name).withIndex()) {
            val match = find(candidate, year, show) ?: continue
            val sure = if (i == 0) match.close else match.exact && (year == null || match.year == year.toString())
            if (sure) {
                save(key, match)
                return match
            }
        }
        prefs.saveTmdbMatch(key, "$MISSED\t${System.currentTimeMillis() / 1000}")
        return null
    }

    /** Whether [verify] found nothing for this lately, so it isn't asked again yet. Doesn't block. */
    fun recentlyMissed(name: String, year: Int?, show: Boolean): Boolean {
        val parts = prefs.tmdbMatch(cacheKey(name, year, show))?.split('\t') ?: return false
        if (parts[0] != MISSED) return false
        val at = parts.getOrNull(1)?.toLongOrNull() ?: return false
        return System.currentTimeMillis() / 1000 - at < MISS_KEPT_S
    }

    private fun variants(name: String): List<String> {
        val bare = name.replace(BRACKETS, " ").replace(SPACES, " ").trim()
        val words = bare.split(' ').filter { it.isNotEmpty() }
        val shorter = (1..2).mapNotNull { n ->
            val dropped = words.takeLast(n)
            if (words.size - n < 1 || dropped.any { it.length > MAX_TAG_LENGTH }) null
            else words.dropLast(n).joinToString(" ")
        }
        return (listOf(name, bare) + shorter).filter { it.isNotBlank() }.distinct()
    }

    private fun save(key: String, match: Match) =
        prefs.saveTmdbMatch(key, "${match.id}\t${match.name}\t${match.year.orEmpty()}")

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
        val bare = name.replace(BRACKETS, " ").replace(SPACES, " ").trim()
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
        val match = Match(best.optInt("id"), title, found, close, exact = exact != null)
        if (close) save(cacheKey(name, year, show), match)
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

    /** Only letters and digits, without "the", "a" or "an" in front: "The Walking Dead" and "Walking Dead" match. */
    private fun simple(s: String) =
        s.lowercase().trim().replace(ARTICLE, "").filter { it.isLetterOrDigit() }

    private fun cacheKey(name: String, year: Int?, show: Boolean) =
        "${if (show) "tv" else "movie"}|${simple(name)}|${year ?: ""}"

    private companion object {
        val NAME_FIELDS = listOf("title", "original_title", "name", "original_name")
        val BRACKETS = Regex("""[(\[][^)\]]*[)\]]""")
        val SPACES = Regex("""\s+""")
        val ARTICLE = Regex("""^(the|a|an)\s+""")
        const val MISSED = "-"
        const val MISS_KEPT_S = 3 * 24 * 3600L
        // Words this short at the end of a name may be tags ("xyz", "vf2"); longer ones are taken as part of it.
        const val MAX_TAG_LENGTH = 4
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
