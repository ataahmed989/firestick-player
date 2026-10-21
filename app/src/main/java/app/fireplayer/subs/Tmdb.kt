package app.fireplayer.subs

import app.fireplayer.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Minimal client for The Movie Database, with the user's own key, so a subtitle search can ask for the exact film or
 * show, and the player can show its proper name and year. All network calls block; run them off the main thread.
 */
class Tmdb(private val prefs: Prefs) {

    init {
        prefs.dropTmdbMatchesBefore(MATCH_RULES)
    }

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
        // The country goes with every try, also once its brackets are left out of the name.
        val country = countryIn(name)
        for ((i, candidate) in variants(name).withIndex()) {
            val match = find(candidate, year, show, country) ?: continue
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

    /**
     * The show (or film) called [name], from [year] when given; null when TMDB has nothing by that name. When nothing
     * is [Match.close], TMDB's nearest guess. A country in brackets ("The Office (UK)") only lets shows from there in.
     */
    fun find(name: String, year: Int?, show: Boolean, country: String? = countryIn(name)): Match? {
        val path = if (show) "search/tv" else "search/movie"
        // A film's "year" also matches its later releases somewhere ("Miracle on 34th Street" from 1947 for 1994).
        val yearParam = if (show) "first_air_date_year" else "primary_release_year"
        val bare = name.replace(BRACKETS, " ").replace(SPACES, " ").trim()
        var guess: Match? = null
        // The year can be the provider's guess, so without it is the next try. Then without words in brackets
        // ("The Office (US)"), which TMDB doesn't have in its names.
        for (query in listOf(name, bare).filter { it.isNotEmpty() }.distinct()) {
            for (y in listOf(year, null).distinct()) {
                val results = search(path, query, y?.let { yearParam to it.toString() })
                val items = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
                val match = pick(items, name, bare, year, show, country) ?: continue
                if (match.close) {
                    save(cacheKey(name, year, show), match)
                    return match
                }
                if (guess == null) guess = match
            }
        }
        return guess
    }

    private fun pick(items: List<JSONObject>, name: String, bare: String, year: Int?, show: Boolean, country: String?): Match? {
        val pool = if (show && country != null) items.filter { it.optJSONArray("origin_country").has(country) } else items
        if (pool.isEmpty()) return null
        val wanted = simple(name)
        val wantedBare = simple(bare)
        fun names(item: JSONObject) = NAME_FIELDS.map { simple(item.optString(it)) }.filter { it.isNotEmpty() }
        fun yearOf(item: JSONObject) = item.optString(if (show) "first_air_date" else "release_date").take(4).toIntOrNull()
        // The provider's year, give or take one; a show's can be a later season's.
        fun fits(item: JSONObject): Boolean {
            if (year == null) return true
            val y = yearOf(item) ?: return false
            return if (show) y <= year + 1 else abs(y - year) <= 1
        }
        // TMDB puts the most popular first; one with exactly this name, in English or its own language, wins, and of
        // those the one from the provider's year, which tells a film from its remake.
        val named = pool.filter { item -> names(item).any { it == wanted || it == wantedBare } }
        val exact = if (year == null) named.firstOrNull() else named.filter(::fits).minByOrNull { abs(yearOf(it)!! - year) }
        val best = exact ?: named.firstOrNull() ?: pool.first()
        val title = best.optString(if (show) "name" else "title").ifEmpty { name }
        val found = yearOf(best)?.toString()
        // Nearly the same name: one starts the other and isn't much shorter ("theofficeus" and "theoffice", not
        // "itfollows" and "it").
        val nearly = fits(best) && names(best).any { n ->
            val (short, long) = if (n.length <= wanted.length) n to wanted else wanted to n
            long.startsWith(short) && short.length * 10 >= long.length * 6
        }
        val close = exact != null || nearly || (year != null && found == year.toString())
        return Match(best.optInt("id"), title, found, close, exact = exact != null)
    }

    private fun JSONArray?.has(value: String): Boolean =
        this != null && (0 until length()).any { optString(it).equals(value, ignoreCase = true) }

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

    /** The country a name in brackets says the show is from ("The Office (UK)"), as TMDB writes it ("GB"). */
    private fun countryIn(name: String): String? =
        COUNTRY.findAll(name).mapNotNull { COUNTRIES[it.groupValues[1].uppercase()] }.lastOrNull()

    private fun cacheKey(name: String, year: Int?, show: Boolean) =
        "${if (show) "tv" else "movie"}|${simple(name)}|${year ?: ""}"

    private companion object {
        val NAME_FIELDS = listOf("title", "original_title", "name", "original_name")
        val BRACKETS = Regex("""[(\[][^)\]]*[)\]]""")
        val SPACES = Regex("""\s+""")
        val ARTICLE = Regex("""^(the|a|an)\s+""")
        val COUNTRY = Regex("""[(\[]\s*([A-Za-z]{2,3})\s*[)\]]""")
        val COUNTRIES = mapOf(
            "UK" to "GB", "GB" to "GB", "US" to "US", "USA" to "US", "AU" to "AU", "AUS" to "AU", "CA" to "CA",
            "CAN" to "CA", "NZ" to "NZ", "IE" to "IE", "IRL" to "IE", "IN" to "IN", "IND" to "IN", "FR" to "FR",
            "DE" to "DE", "GER" to "DE", "ES" to "ES", "ESP" to "ES", "IT" to "IT", "ITA" to "IT", "NL" to "NL",
            "SE" to "SE", "SWE" to "SE", "NO" to "NO", "NOR" to "NO", "DK" to "DK", "BE" to "BE", "BR" to "BR",
            "MX" to "MX", "JP" to "JP", "KR" to "KR", "TR" to "TR",
        )
        // Matches saved under older rules ("The Office (UK)" as the US show) are dropped once and asked again.
        const val MATCH_RULES = 2
        const val MISSED = "-"
        const val MISS_KEPT_S = 3 * 24 * 3600L
        // Words this short at the end of a name may be tags ("xyz", "vf2"); longer ones are taken as part of it.
        const val MAX_TAG_LENGTH = 4
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
