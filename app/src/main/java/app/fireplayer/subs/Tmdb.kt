package app.fireplayer.subs

import app.fireplayer.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import kotlin.math.abs

/**
 * Minimal client for The Movie Database, with the user's own key, so a subtitle search can ask for the exact film or
 * show, and TiviMate's lists can show their English names (see [app.fireplayer.titles.TitleMatcher]). What it
 * matched is kept in [memory]. All network calls block; run them off the main thread.
 */
class Tmdb(private val key: () -> String, private val memory: Memory) {

    /** The player's: its key from Settings, matches kept only while it runs. */
    constructor(prefs: Prefs) : this({ prefs.tmdbApiKey }, Memory.inProcess())

    /** Where matches are kept, by what was asked. */
    interface Memory {
        fun get(key: String): String?
        fun put(key: String, value: String)

        companion object {
            fun inProcess(): Memory = object : Memory {
                private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
                override fun get(key: String) = map[key]
                override fun put(key: String, value: String) {
                    map[key] = value
                }
            }
        }
    }

    /** TMDB answered with [code]: 401 for a key it doesn't take, 404 for no such title, 429 for too many requests. */
    class HttpError(val code: Int, message: String) : Exception(message)

    /**
     * A film or show on TMDB. [close]: its name is the one asked for (give or take punctuation and a word like a
     * country), or its year is, so it's surely the same title and not just TMDB's nearest guess. [exact]: the very
     * name asked for.
     */
    class Match(val id: Int, val name: String, val year: String?, val close: Boolean = true, val exact: Boolean = true) {
        val label: String get() = if (year != null) "$name ($year)" else name
    }

    val isConfigured: Boolean get() = key().isNotEmpty()

    /**
     * The film or show TMDB confirms for a name a provider wrote. Tried as it is first (and without words in
     * brackets), then without a short last word or two, or a part joined on with a dash, that may be a tag the
     * name's clean-up didn't know ("Blank1 Blank2 xyz", "Dark-dual"). A shortened name only counts when TMDB has a
     * title by exactly that name (and year, when one is known), and none longer that starts with it: "Dune" isn't
     * "Dune 2", nor "NCIS" "NCIS LA". Null when nothing is confirmed; remembered either way (a miss for a few days),
     * so the next time is instant.
     */
    fun verify(name: String, year: Int?, show: Boolean): Match? {
        val key = cacheKey(name, year, show)
        // The country goes with every try, also once its brackets are left out of the name.
        val country = countryIn(name)
        val whole = lookup(name, year, show, country)
        whole.match?.takeIf { it.close }?.let {
            save(key, it)
            return it
        }
        val full = setOf(simple(name), simple(name.replace(BRACKETS, " ")))
        // Only the year agrees: TMDB found it by a translated name ("Le Roi Lion" for "The Lion King") when one of its
        // translations is this very name. Not for shortened names: "Le Roi" is also "The King" in French.
        whole.match?.takeIf { year != null && it.year == year.toString() && translatedNames(it.id, show).any { n -> n in full } }
            ?.let {
                val sure = Match(it.id, it.name, it.year)
                save(key, sure)
                return sure
            }
        for (candidate in shorterNames(name)) {
            val short = simple(candidate)
            if (whole.names.any { it.length > short.length && it.startsWith(short) }) continue
            val found = lookup(candidate, year, show, country)
            // TMDB has a title by the whole name after all ("Spider-Man" when asked for "Spider"): not this one.
            if (found.names.any { it in full }) continue
            val match = found.match ?: continue
            if (match.exact && (year == null || match.year == year.toString())) {
                save(key, match)
                return match
            }
        }
        memory.put(key, "$MISSED\t${System.currentTimeMillis() / 1000}")
        return null
    }

    /** Whether [verify] found nothing for this lately, so it isn't asked again yet. Doesn't block. */
    fun recentlyMissed(name: String, year: Int?, show: Boolean): Boolean {
        val parts = memory.get(cacheKey(name, year, show))?.split('\t') ?: return false
        if (parts[0] != MISSED) return false
        val at = parts.getOrNull(1)?.toLongOrNull() ?: return false
        return System.currentTimeMillis() / 1000 - at < MISS_KEPT_S
    }

    /** The film's or show's name in every language TMDB has it in, simplified. */
    private fun translatedNames(id: Int, show: Boolean): Set<String> = runCatching {
        val list = JSONObject(request("${if (show) "tv" else "movie"}/$id/translations", emptyList()))
            .optJSONArray("translations") ?: JSONArray()
        (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optJSONObject("data") }
            .map { simple(it.optString(if (show) "name" else "title")) }.filter { it.isNotEmpty() }.toSet()
    }.getOrDefault(emptySet())

    /** [name] without what may be a tag at its end; never a number, a numeral or "Part" ("Dune 2", "Saw VI"). */
    private fun shorterNames(name: String): List<String> {
        val bare = name.replace(BRACKETS, " ").replace(SPACES, " ").trim()
        val words = bare.split(' ').filter { it.isNotEmpty() }
        val joined = words.lastOrNull()?.let { last ->
            val cut = last.lastIndexOf('-')
            val tag = last.substring(cut + 1)
            if (cut <= 0 || tag.length !in 2..10 || !tag.all { it.isLetter() } || SEQUEL_WORD.matches(tag)) null
            else (words.dropLast(1) + last.substring(0, cut)).joinToString(" ")
        }
        val shorter = (1..2).mapNotNull { n ->
            val dropped = words.takeLast(n)
            if (words.size - n < 1 || dropped.any { it.length > MAX_TAG_LENGTH || SEQUEL_WORD.matches(it) }) null
            else words.dropLast(n).joinToString(" ")
        }
        return (listOfNotNull(joined) + shorter).filter { it.isNotBlank() && it != name && it != bare }.distinct()
    }

    private fun save(key: String, match: Match) =
        memory.put(key, "${match.id}\t${match.name}\t${match.year.orEmpty()}")

    /** A close match found before for the same question, without asking TMDB again. Doesn't block. */
    fun cached(name: String, year: Int?, show: Boolean): Match? {
        val parts = memory.get(cacheKey(name, year, show))?.split('\t') ?: return null
        val id = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val title = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        return Match(id, title, parts.getOrNull(2)?.takeIf { it.isNotEmpty() })
    }

    /**
     * The show (or film) called [name], from [year] when given; null when TMDB has nothing by that name. When nothing
     * is [Match.close], TMDB's nearest guess. A country in brackets ("The Office (UK)") only lets shows from there in.
     */
    fun find(name: String, year: Int?, show: Boolean, country: String? = countryIn(name)): Match? =
        lookup(name, year, show, country).match

    /**
     * A film or show by its TMDB id: its English name and year, and its names simplified (see [simple]), in every
     * language and their other names too when [allNames]. Null when TMDB has no title by that id.
     */
    class Details(val id: Int, val name: String, val year: String?, val names: Set<String>)

    fun details(id: Int, show: Boolean, allNames: Boolean): Details? {
        val key = "$MATCH_RULES|id|${if (show) "tv" else "movie"}|$id|${if (allNames) "all" else "en"}"
        memory.get(key)?.let { saved ->
            if (saved == MISSED) return null
            val parts = saved.split('\t')
            return Details(id, parts[0], parts.getOrNull(1)?.ifEmpty { null }, parts.getOrNull(2)?.split(' ')?.toSet().orEmpty())
        }
        return fetchDetails(id, show, allNames).also { found ->
            memory.put(key, found?.let { "${it.name.replace('\t', ' ')}\t${it.year.orEmpty()}\t${it.names.joinToString(" ")}" } ?: MISSED)
        }
    }

    private fun fetchDetails(id: Int, show: Boolean, allNames: Boolean): Details? {
        val params = listOfNotNull("language" to "en-US", if (allNames) "append_to_response" to "translations,alternative_titles" else null)
        val item = try {
            JSONObject(request("${if (show) "tv" else "movie"}/$id", params))
        } catch (e: HttpError) {
            if (e.code == 404) return null
            throw e
        }
        val name = item.optString(if (show) "name" else "title").ifEmpty { item.optString(if (show) "original_name" else "original_title") }
        if (name.isEmpty()) return null
        val names = NAME_FIELDS.map { simple(item.optString(it)) }.toMutableSet()
        if (allNames) {
            val translations = item.optJSONObject("translations")?.optJSONArray("translations") ?: JSONArray()
            for (i in 0 until translations.length()) {
                translations.optJSONObject(i)?.optJSONObject("data")?.let { names += simple(it.optString(if (show) "name" else "title")) }
            }
            val others = item.optJSONObject("alternative_titles")?.let { it.optJSONArray("titles") ?: it.optJSONArray("results") } ?: JSONArray()
            for (i in 0 until others.length()) others.optJSONObject(i)?.let { names += simple(it.optString("title")) }
        }
        names.remove("")
        val year = item.optString(if (show) "first_air_date" else "release_date").take(4).takeIf { it.length == 4 }
        return Details(id, name, year, names)
    }

    /** Only letters and digits, without "the", "a" or "an" in front, as [Details.names] are written. */
    fun simplify(s: String): String = simple(s)

    /** What [find] found, and the simple names of everything TMDB answered with on the way. */
    private class Lookup(val match: Match?, val names: Set<String>)

    private class Pick(val match: Match, val sure: Boolean)

    private fun lookup(name: String, year: Int?, show: Boolean, country: String?): Lookup {
        val path = if (show) "search/tv" else "search/movie"
        // A film's "year" also matches its later releases somewhere ("Miracle on 34th Street" from 1947 for 1994).
        val yearParam = if (show) "first_air_date_year" else "primary_release_year"
        val bare = name.replace(BRACKETS, " ").replace(SPACES, " ").trim()
        val seen = mutableSetOf<String>()
        // Saved under this name only when asked about as itself, not with another name's country.
        val own = country == countryIn(name)
        var byYear: Match? = null
        var guess: Match? = null
        // The year can be the provider's guess, so without it is the next try. Then without words in brackets
        // ("The Office (US)"), which TMDB doesn't have in its names.
        for (query in listOf(name, bare).filter { it.isNotEmpty() }.distinct()) {
            for (y in listOf(year, null).distinct()) {
                val results = search(path, query, y?.let { yearParam to it.toString() })
                val items = (0 until results.length()).mapNotNull { results.optJSONObject(it) }
                items.forEach { item -> seen += NAME_FIELDS.map { simple(item.optString(it)) }.filter { it.isNotEmpty() } }
                val pick = pick(items, name, bare, year, show, country) ?: continue
                if (pick.sure) {
                    if (own) save(cacheKey(name, year, show), pick.match)
                    return Lookup(pick.match, seen)
                }
                // Only the year and the start of the name agree: used when no search finds the name itself.
                if (pick.match.close) {
                    if (byYear == null) byYear = pick.match
                } else if (guess == null) {
                    guess = pick.match
                }
            }
        }
        if (own) byYear?.let { save(cacheKey(name, year, show), it) }
        return Lookup(byYear ?: guess, seen)
    }

    private fun pick(items: List<JSONObject>, name: String, bare: String, year: Int?, show: Boolean, country: String?): Pick? {
        val pool = if (show && country != null) items.filter { it.optJSONArray("origin_country").has(country) } else items
        if (pool.isEmpty()) return null
        val wanted = simple(name)
        val wantedBare = simple(bare)
        val asWritten = setOf(plain(name), plain(bare))
        fun names(item: JSONObject) = NAME_FIELDS.map { simple(item.optString(it)) }.filter { it.isNotEmpty() }
        fun yearOf(item: JSONObject) = item.optString(if (show) "first_air_date" else "release_date").take(4).toIntOrNull()
        // The provider's year, give or take one; a show's can be a later season's.
        fun fits(item: JSONObject): Boolean {
            if (year == null) return true
            val y = yearOf(item) ?: return false
            return if (show) y <= year + 1 else abs(y - year) <= 1
        }
        fun choose(list: List<JSONObject>) =
            if (year == null) list.firstOrNull() else list.filter(::fits).minByOrNull { abs(yearOf(it)!! - year) }
        // Nearly the same name: one starts the other and isn't much shorter ("theofficeus" and "theoffice", not
        // "itfollows" and "it"), and isn't the other with a number on ("deadpool3"). TMDB's shorter name doesn't count
        // when another of its titles goes on the way the provider's does ("NCIS" for "NCIS LA", with "NCIS: Los
        // Angeles" there too).
        fun nearly(item: JSONObject) = fits(item) && names(item).any { n ->
            val (short, long) = if (n.length <= wanted.length) n to wanted else wanted to n
            long.startsWith(short) && short.length * 10 >= long.length * 6 &&
                !SEQUEL_WORD.matches(long.substring(short.length)) &&
                !(n.length < wanted.length && pool.any { other ->
                    names(other).any { m -> m.length > n.length && m.startsWith(n) && m.commonPrefixWith(wanted).length > n.length }
                })
        }
        // A sequel the provider numbered and TMDB named, from the provider's year: "Dune 2" and "Dune: Part Two".
        val words = bare.split(' ').filter { it.isNotEmpty() }
        val stem = if (words.size > 1 && SEQUEL_WORD.matches(words.last())) simple(words.dropLast(1).joinToString(" ")) else ""
        fun sameStart(item: JSONObject) = stem.isNotEmpty() && year != null && yearOf(item) == year &&
            names(item).any { it.length > stem.length && it.startsWith(stem) }
        // TMDB puts the most popular first; one with exactly this name, in English or its own language, wins: with
        // "the" or without it as the provider wrote it first ("Batman" isn't "The Batman"), and of those the one from
        // the provider's year, which tells a film from its remake. An "&" the provider left out counts too ("Love
        // Death Robots").
        val loose = setOf(simple(name.replace("&", " ")), simple(bare.replace("&", " ")))
        val named = pool.filter { item ->
            names(item).any { it == wanted || it == wantedBare } ||
                NAME_FIELDS.any { simple(item.optString(it).replace("&", " ")) in loose }
        }
        val written = named.filter { item -> NAME_FIELDS.any { plain(item.optString(it)) in asWritten } }
        // Without a year or "the", a title by just that name only wins when it's also TMDB's most popular by it:
        // "Godfather" is likely "The Godfather", not 2022's "GodFather", so neither is sure.
        val unsure = year == null && plain(name) == wanted && written.isNotEmpty() && named.first() !in written
        val exact = if (unsure) null else choose(written) ?: choose(named)
        val near = if (exact == null && !unsure) pool.firstOrNull(::nearly) else null
        val linked = if (exact == null && near == null) pool.firstOrNull(::sameStart) else null
        val best = exact ?: near ?: linked ?: named.firstOrNull() ?: pool.first()
        val title = best.optString(if (show) "name" else "title").ifEmpty { name }
        val found = yearOf(best)?.toString()
        val match = Match(best.optInt("id"), title, found, close = exact != null || near != null || linked != null,
            exact = exact != null)
        return Pick(match, sure = exact != null || near != null)
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
        val key = key()
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
                if (code == 401) throw HttpError(code, "TMDB didn't accept this key")
                val message = runCatching { JSONObject(text).optString("status_message") }.getOrNull()
                throw HttpError(code, message?.ifEmpty { null } ?: "TMDB error $code")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** Only letters and digits, without "the", "a" or "an" in front: "The Walking Dead" and "Walking Dead" match. */
    private fun simple(s: String) = plain(s.lowercase().trim().replace(ARTICLE, ""))

    /** Only letters and digits, without accents and with "&" as "and": "Pokémon", "Law & Order". */
    private fun plain(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")
        .replace("&", "and").filter { it.isLetterOrDigit() }

    /** The country a name in brackets says the show is from ("The Office (UK)"), as TMDB writes it ("GB"). */
    private fun countryIn(name: String): String? =
        COUNTRY.findAll(name).mapNotNull { COUNTRIES[it.groupValues[1].uppercase()] }.lastOrNull()

    // With "the" as written, so "Batman" and "The Batman" are remembered apart; and under these rules only.
    private fun cacheKey(name: String, year: Int?, show: Boolean) =
        "$MATCH_RULES|${if (show) "tv" else "movie"}|${plain(name)}|${year ?: ""}"

    private companion object {
        val NAME_FIELDS = listOf("title", "original_title", "name", "original_name")
        val BRACKETS = Regex("""[(\[][^)\]]*[)\]]""")
        val SPACES = Regex("""\s+""")
        val ARTICLE = Regex("""^(the|a|an)\s+""")
        val MARKS = Regex("""\p{M}+""")
        // A number, a numeral or "Part" at the end makes another title: "Dune 2", "Saw VI".
        val SEQUEL_WORD = Regex("""(?i)\d+|part|[ivx]{1,4}""")
        val COUNTRY = Regex("""[(\[]\s*([A-Za-z]{2,3})\s*[)\]]""")
        val COUNTRIES = mapOf(
            "UK" to "GB", "GB" to "GB", "US" to "US", "USA" to "US", "AU" to "AU", "AUS" to "AU", "CA" to "CA",
            "CAN" to "CA", "NZ" to "NZ", "IE" to "IE", "IRL" to "IE", "IN" to "IN", "IND" to "IN", "FR" to "FR",
            "DE" to "DE", "GER" to "DE", "ES" to "ES", "ESP" to "ES", "IT" to "IT", "ITA" to "IT", "NL" to "NL",
            "SE" to "SE", "SWE" to "SE", "NO" to "NO", "NOR" to "NO", "DK" to "DK", "BE" to "BE", "BR" to "BR",
            "MX" to "MX", "JP" to "JP", "KR" to "KR", "TR" to "TR", "TW" to "TW", "HK" to "HK", "CH" to "CH",
            "AT" to "AT", "AL" to "AL", "LA" to "LA",
        )
        // Matches saved under older rules ("The Office (UK)" as the US show) aren't used; they're asked again.
        const val MATCH_RULES = 4
        const val MISSED = "-"
        const val MISS_KEPT_S = 3 * 24 * 3600L
        // Words this short at the end of a name may be tags ("xyz", "vf2"); longer ones are taken as part of it.
        const val MAX_TAG_LENGTH = 4
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
