package app.fireplayer.vod.data

import app.fireplayer.vod.Kind
import app.fireplayer.vod.Title
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Cinemeta, Stremio's metadata add-on (v3): the addresses FirePlayer asks, and its answers read into [Title]s. Pure:
 * no network, no Android.
 *
 * Catalogs come in pages of [PAGE] positions: `skip` is an absolute offset and the next page is `skip + 50`, though a
 * page often holds only 47 to 49 titles (the server drops a few after slicing). Addresses are always written the same
 * way (genre before skip, skip left out at 0) so the server's cache and FirePlayer's line up.
 */
object Cinemeta {

    const val BASE = "https://v3-cinemeta.strem.io"
    const val PAGE = 50

    /** How long a page is fresh when the answer doesn't say ("cacheMaxAge" is 4 h). */
    const val PAGE_FRESH_MS = 4 * 3600_000L
    const val MANIFEST_FRESH_MS = 24 * 3600_000L
    const val META_FRESH_MS = 24 * 3600_000L
    /** How long the Search tab keeps an answer (in memory only). */
    const val SEARCH_FRESH_MS = 5 * 60_000L

    /** Catalog ids: Popular, New (by year), Featured. */
    const val TOP = "top"
    const val YEAR = "year"
    const val FEATURED = "imdbRating"

    /** The manifest's genres (November 2026), for chips when it can't be read. */
    val MOVIE_GENRES = listOf(
        "Action", "Adventure", "Animation", "Biography", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy",
        "History", "Horror", "Mystery", "Romance", "Sci-Fi", "Sport", "Thriller", "War", "Western",
    )
    val SERIES_GENRES = MOVIE_GENRES + listOf("Reality-TV", "Talk-Show", "Game-Show")

    fun manifestUrl() = "$BASE/manifest.json"

    fun metaUrl(kind: Kind, id: String) = "$BASE/meta/${kind.type}/$id.json"

    /**
     * A catalog page: `/catalog/movie/top.json`, `/catalog/movie/top/genre=Action&skip=50.json`,
     * `/catalog/series/year/genre=2026.json`. [genre] is the year for [YEAR]; values are encoded with %20, never +.
     */
    fun catalogUrl(kind: Kind, catalog: String, genre: String?, skip: Int): String {
        val extras = buildList {
            if (!genre.isNullOrBlank()) add("genre=${enc(genre)}")
            if (skip > 0) add("skip=$skip")
        }
        val tail = if (extras.isEmpty()) "" else "/" + extras.joinToString("&")
        return "$BASE/catalog/${kind.type}/${enc(catalog)}$tail.json"
    }

    /**
     * A name search: `/catalog/movie/top/search=Denis%20Villeneuve.json`. It finds the name in any field (cast, writer,
     * title too); More Like This uses it for a film's director when there is no TMDB key.
     */
    fun searchUrl(kind: Kind, query: String) = "$BASE/catalog/${kind.type}/$TOP/search=${enc(query.trim())}.json"

    /**
     * A search answer read into titles of [kind], in order, each id once. No junk filters: search items carry no
     * `year` field, and the caller filters by year itself. [Page.hasMore] is always false.
     */
    fun parseSearch(text: String, kind: Kind): Page {
        val root = JSONObject(text)
        val metas = root.optJSONArray("metas")
        val seen = HashSet<String>()
        val titles = ArrayList<Title>()
        if (metas != null) {
            for (i in 0 until metas.length()) {
                val title = parsePreview(metas.optJSONObject(i) ?: continue, kind) ?: continue
                if (title.kind == kind && seen.add(title.id)) titles += title
            }
        }
        val maxAge = root.optLong("cacheMaxAge", 0L).takeIf { it > 0 }?.let { (it * 1000).coerceIn(MIN_FRESH_MS, MAX_FRESH_MS) }
        return Page(titles, false, maxAge ?: PAGE_FRESH_MS)
    }

    /** One catalog page, filtered (see [parseCatalog]). [maxAgeMs]: how long the server says it stays fresh. */
    class Page(val titles: List<Title>, val hasMore: Boolean, val maxAgeMs: Long)

    /**
     * A catalog answer read into titles of [kind], in order, each id once (the first kept). Dropped:
     * - ids that aren't IMDb's ("tt…"), items of another type, items without a name;
     * - in film catalogs, Cinemeta's cross-wired junk: anything with a year range ("2022–", films never have one), and
     *   items without a `year` field (fetched live) from before last year (a series' IMDb id carrying the data of the
     *   TMDB film with the same number, such as "The Prince of Arcadia (1932)");
     * - in [YEAR] catalogs, titles from another year than [genre] (Cinemeta mixes in a few).
     * [hasMore] false or missing with no titles means the end.
     */
    fun parseCatalog(text: String, kind: Kind, catalog: String, genre: String?, currentYear: Int): Page {
        val root = JSONObject(text)
        val metas = root.optJSONArray("metas")
        val year = if (catalog == YEAR) genre?.trim()?.takeIf { it.isNotEmpty() } else null
        val seen = HashSet<String>()
        val titles = ArrayList<Title>()
        if (metas != null) {
            for (i in 0 until metas.length()) {
                val o = metas.optJSONObject(i) ?: continue
                val title = parsePreview(o, kind) ?: continue
                if (title.kind != kind) continue
                if (kind == Kind.MOVIE && isJunkFilm(o, title, currentYear)) continue
                if (year != null && title.year?.startsWith(year) != true) continue
                if (seen.add(title.id)) titles += title
            }
        }
        val hasMore = if (root.has("hasMore")) root.optBoolean("hasMore") else (metas?.length() ?: 0) > 0
        val maxAge = root.optLong("cacheMaxAge", 0L).takeIf { it > 0 }?.let { (it * 1000).coerceIn(MIN_FRESH_MS, MAX_FRESH_MS) }
        return Page(titles, hasMore, maxAge ?: PAGE_FRESH_MS)
    }

    /** See [parseCatalog]: a film catalog item that is really a series, or a series' id with another film's data. */
    internal fun isJunkFilm(o: JSONObject, title: Title, currentYear: Int): Boolean {
        val released = title.year ?: return false
        if (released.contains('–') || HYPHEN_RANGE.matches(released)) return true
        if (!o.has("year")) {
            val y = released.take(4).toIntOrNull()
            if (y != null && y < currentYear - 1) return true
        }
        return false
    }

    /**
     * A catalog item (or a meta's "meta" object) as a [Title], tolerant of missing fields: the year from `releaseInfo`
     * (else `year`), a blank rating as none, `genres` (else the older `genre`), the TMDB id from `moviedb_id`. Pictures
     * missing from the answer are metahub's addresses for the id. Its kind is the item's `type`, else [fallback]. Null
     * without an IMDb id or a name.
     */
    fun parsePreview(o: JSONObject, fallback: Kind): Title? {
        val id = (o.text("id") ?: o.text("imdb_id"))?.takeIf { it.startsWith("tt") } ?: return null
        val name = o.text("name") ?: return null
        val kind = if (o.has("type")) kindOf(o.text("type")) ?: return null else fallback
        val genres = o.optJSONArray("genres").texts().ifEmpty { o.optJSONArray("genre").texts() }
        val tmdb = when (val v = o.opt("moviedb_id")) {
            is Number -> v.toInt()
            is String -> v.trim().toIntOrNull()
            else -> null
        }?.takeIf { it > 0 }
        return Title(
            id = id,
            kind = kind,
            name = name,
            year = o.text("releaseInfo") ?: o.text("year"),
            poster = VodArt.normalized(o.text("poster")) ?: VodArt.poster(id),
            background = VodArt.normalized(o.text("background")) ?: VodArt.background(id),
            logo = VodArt.normalized(o.text("logo")) ?: VodArt.logo(id),
            description = o.text("description"),
            imdbRating = o.text("imdbRating"),
            genres = genres,
            runtime = o.text("runtime"),
            tmdbId = tmdb,
        )
    }

    /** A `/meta/{type}/{id}.json` answer; null for `{}` (asked with the wrong type) or a title of another kind. */
    fun parseMeta(text: String, kind: Kind): Title? {
        val meta = JSONObject(text).optJSONObject("meta") ?: return null
        return parsePreview(meta, kind)?.takeIf { it.kind == kind }
    }

    /** What FirePlayer reads from the manifest: genre names (chips) and the years "New" has, newest first, per kind. */
    class Manifest(val genres: Map<Kind, List<String>>, val years: Map<Kind, List<String>>)

    /** The `top` catalog's genre options and the `year` catalog's years, per kind. Missing parts are left out. */
    fun parseManifest(text: String): Manifest {
        val catalogs = JSONObject(text).optJSONArray("catalogs")
        val genres = HashMap<Kind, List<String>>()
        val years = HashMap<Kind, List<String>>()
        if (catalogs != null) {
            for (i in 0 until catalogs.length()) {
                val c = catalogs.optJSONObject(i) ?: continue
                val kind = kindOf(c.text("type")) ?: continue
                val extra = c.optJSONArray("extra") ?: continue
                val options = (0 until extra.length()).mapNotNull { extra.optJSONObject(it) }
                    .firstOrNull { it.text("name") == "genre" }?.optJSONArray("options").texts()
                if (options.isEmpty()) continue
                when (c.text("id")) {
                    TOP -> genres[kind] = options
                    YEAR -> years[kind] = options.filter { it.length == 4 && it.all(Char::isDigit) }
                }
            }
        }
        return Manifest(genres, years)
    }

    /** The genres for chips: the manifest's, else the built-in lists. */
    fun genres(manifest: Manifest?, kind: Kind): List<String> =
        manifest?.genres?.get(kind)?.takeIf { it.isNotEmpty() } ?: if (kind == Kind.MOVIE) MOVIE_GENRES else SERIES_GENRES

    /**
     * The year for "New in <year>": this calendar year when the manifest lists it (or nothing is known), otherwise the
     * newest year it lists (on 1 January Cinemeta may not have the new year yet).
     */
    fun newYear(years: List<String>?, currentYear: Int): Int {
        val listed = years.orEmpty().mapNotNull { it.toIntOrNull() }
        if (listed.isEmpty() || currentYear in listed) return currentYear
        return listed.filter { it <= currentYear }.maxOrNull() ?: listed.max()
    }

    // "2022-" or "2008-2013" written with a hyphen instead of Cinemeta's en dash.
    private val HYPHEN_RANGE = Regex("""\d{4}\s*-\s*(\d{4})?""")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private const val MIN_FRESH_MS = 10 * 60_000L
    private const val MAX_FRESH_MS = 24 * 3600_000L
}
