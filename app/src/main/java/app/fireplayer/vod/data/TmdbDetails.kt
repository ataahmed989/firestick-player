package app.fireplayer.vod.data

import app.fireplayer.vod.Kind
import org.json.JSONArray
import org.json.JSONObject

/**
 * TMDB's details for the title page: one call per title (`movie/{id}` or `tv/{id}`) with the age rating, credits,
 * recommendations and similar titles (and a show's external ids) appended ([append]), read into [Extras], and the
 * trimmed form kept on disk (about 24 h; the cache never keeps anything 14 days, well inside TMDB's 6-month limit).
 * Pure: the call itself is [TmdbSource.details]. Appended parts are read whether or not they carry an `id` of their own.
 */
object TmdbDetails {

    /** How long TMDB's details are fresh (brief: about 24 h). */
    const val FRESH_MS = 24 * 3600_000L
    /** How long "TMDB has no such title" (404) is remembered. */
    const val MISSING_MS = 24 * 3600_000L
    /** How long a `find` by IMDb id is kept (a meta without `moviedb_id`, rare). */
    const val FIND_FRESH_MS = 30 * 24 * 3600_000L
    /** Items kept from each list (TMDB's first page has 20). */
    const val LIST_KEPT = 20
    /** Cast names kept for Details. */
    const val CAST_KEPT = 15
    /** Networks (a show's) or studios (a film's) kept for Details. */
    const val MAKERS_KEPT = 3
    /**
     * The stored form's version, kept in it as "v": 2 since Details' portraits, networks, studios and countries are kept.
     * Details stored before (no "v") still read, without those parts, and count as due for a fetch when a title page
     * opens ([current]).
     */
    const val FORMAT = 2

    /** The parts appended to the one details call. */
    fun append(kind: Kind): String =
        if (kind == Kind.MOVIE) "release_dates,credits,recommendations,similar"
        else "content_ratings,external_ids,credits,recommendations,similar"

    /** TMDB's name for a kind in its addresses. */
    fun type(kind: Kind) = if (kind == Kind.MOVIE) "movie" else "tv"

    /** The cache key of a title's trimmed details; never holds the key. */
    fun key(kind: Kind, tmdbId: Int) = "tmdb|details|v1|${type(kind)}|$tmdbId"

    /** The cache key of a `find` by IMDb id. */
    fun findKey(imdbId: String) = "tmdb|find|v1|$imdbId"

    /** One recommendation or similar title. [date] as TMDB writes it ("2021-09-15"); [poster] and [backdrop] are paths. */
    data class Item(
        val tmdbId: Int,
        val kind: Kind,
        val name: String,
        val date: String? = null,
        val poster: String? = null,
        val backdrop: String? = null,
        val overview: String? = null,
        val votes: Int = 0,
        val adult: Boolean = false,
    ) {
        val year: String? get() = date?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
    }

    /**
     * What the title page takes from TMDB. [ageRating] is the US one (a film's theatrical certification first; "NR"
     * and the like count as none); [runtimeMin] a film's length (a show's usual episode length); [releaseDate] as TMDB
     * writes it; [status] as TMDB words it ("Returning Series", "Ended"…); [backdrop] a path; [cast] in billing order (at
     * most [CAST_KEPT]); [directors] and [writers] a film's (each name once); [createdBy] a show's. The rest, for
     * Details, comes in the same answer: [castPictures] each cast member's portrait path, in [cast]'s order (null for one
     * TMDB has none of; empty when it has none at all); [networks] a show's (as `networks`), [studios] a film's
     * production companies, at most [MAKERS_KEPT] each; [countries] where it was made (`production_countries`).
     */
    data class Extras(
        val tmdbId: Int,
        val kind: Kind,
        val imdbId: String? = null,
        val overview: String? = null,
        val ageRating: String? = null,
        val runtimeMin: Int? = null,
        val releaseDate: String? = null,
        val status: String? = null,
        val backdrop: String? = null,
        val createdBy: List<String> = emptyList(),
        val cast: List<String> = emptyList(),
        val directors: List<String> = emptyList(),
        val writers: List<String> = emptyList(),
        val recommendations: List<Item> = emptyList(),
        val similar: List<Item> = emptyList(),
        /** The language it was made in, ISO 639-1 ("en", "ko"); null when TMDB doesn't say. */
        val originalLanguage: String? = null,
        val castPictures: List<String?> = emptyList(),
        val networks: List<String> = emptyList(),
        val studios: List<String> = emptyList(),
        val countries: List<String> = emptyList(),
        /** The stored form it was read from ([FORMAT] for an answer just parsed; 1 for one stored before "v" was kept). */
        val format: Int = FORMAT,
    )

    /**
     * Whether stored details hold everything a title page shows: none (TMDB had no such title) or ones stored in this
     * [FORMAT]. Older ones are still shown, and fetched again once (the stored copy kept should the fetch fail).
     */
    fun current(e: Extras?): Boolean = e == null || e.format >= FORMAT

    /** A details answer (with [append]'s parts) as [Extras]; null without an id. */
    fun parse(text: String, kind: Kind): Extras? {
        val o = JSONObject(text)
        val id = o.optInt("id", 0).takeIf { it > 0 } ?: return null
        val show = kind == Kind.SERIES
        val credits = o.optJSONObject("credits")
        val crew = objects(credits?.optJSONArray("crew"))
        // Each name once, in billing order, with its portrait.
        val cast = objects(credits?.optJSONArray("cast"))
            .withIndex()
            .sortedWith(compareBy({ it.value.optInt("order", Int.MAX_VALUE) }, { it.index }))
            .mapNotNull { c -> c.value.text("name")?.let { it to c.value.text("profile_path")?.takeIf { p -> p.startsWith("/") } } }
            .distinctBy { it.first }
            .take(CAST_KEPT)
        return Extras(
            tmdbId = id,
            kind = kind,
            imdbId = (if (show) o.optJSONObject("external_ids")?.text("imdb_id") else o.text("imdb_id"))
                ?.takeIf { it.startsWith("tt") },
            overview = o.text("overview"),
            ageRating = ageRating(o, kind),
            runtimeMin = (if (show) o.optJSONArray("episode_run_time")?.optInt(0, 0) else o.optInt("runtime", 0))
                ?.takeIf { it > 0 },
            releaseDate = o.text(if (show) "first_air_date" else "release_date"),
            status = o.text("status"),
            backdrop = o.text("backdrop_path")?.takeIf { it.startsWith("/") },
            createdBy = objects(o.optJSONArray("created_by")).mapNotNull { it.text("name") }.distinct(),
            cast = cast.map { it.first },
            directors = if (show) emptyList() else crew.filter { it.text("job") == "Director" }.mapNotNull { it.text("name") }.distinct(),
            writers = if (show) emptyList() else crew.filter { c -> c.text("job")?.let { it in WRITING_JOBS } == true }.mapNotNull { it.text("name") }.distinct(),
            recommendations = items(o.optJSONObject("recommendations"), kind),
            similar = items(o.optJSONObject("similar"), kind),
            originalLanguage = o.text("original_language")?.lowercase(java.util.Locale.ROOT),
            castPictures = if (cast.any { it.second != null }) cast.map { it.second } else emptyList(),
            networks = if (show) names(o.optJSONArray("networks")).take(MAKERS_KEPT) else emptyList(),
            studios = if (show) emptyList() else names(o.optJSONArray("production_companies")).take(MAKERS_KEPT),
            countries = names(o.optJSONArray("production_countries")),
        )
    }

    /** The `name` of each object in [a], each once, in order. */
    private fun names(a: JSONArray?): List<String> = objects(a).mapNotNull { it.text("name") }.distinct()

    /**
     * The US age rating from what [append] adds: a show's `content_ratings` US entry; a film's `release_dates` US
     * entry, its theatrical (type 3) certification first, else the first one given. None for "NR", "Not Rated",
     * "Unrated" or no US entry.
     */
    fun ageRating(o: JSONObject, kind: Kind): String? {
        val show = kind == Kind.SERIES
        val results = o.optJSONObject(if (show) "content_ratings" else "release_dates")?.optJSONArray("results") ?: return null
        val us = objects(results).firstOrNull { it.text("iso_3166_1") == "US" } ?: return null
        val rating = if (show) {
            us.text("rating")
        } else {
            val rated = objects(us.optJSONArray("release_dates")).filter { it.text("certification") != null }
            (rated.firstOrNull { it.optInt("type") == 3 } ?: rated.firstOrNull())?.text("certification")
        }
        return rating?.takeIf { it.uppercase() !in UNRATED }
    }

    /** The TMDB id a `find/{tt}?external_source=imdb_id` answer gives for [kind]; null when none. */
    fun idFromFind(text: String, kind: Kind): Int? {
        val o = JSONObject(text)
        val list = o.optJSONArray(if (kind == Kind.MOVIE) "movie_results" else "tv_results") ?: return null
        return list.optJSONObject(0)?.optInt("id", 0)?.takeIf { it > 0 }
    }

    // ---- The trimmed form kept on disk ----

    fun write(e: Extras): JSONObject = JSONObject().apply {
        put("v", e.format)
        put("id", e.tmdbId)
        put("type", e.kind.type)
        e.imdbId?.let { put("imdb", it) }
        e.overview?.let { put("overview", it) }
        e.ageRating?.let { put("rating", it) }
        e.runtimeMin?.let { put("runtime", it) }
        e.releaseDate?.let { put("date", it) }
        e.status?.let { put("status", it) }
        e.backdrop?.let { put("backdrop", it) }
        e.originalLanguage?.let { put("lang", it) }
        if (e.createdBy.isNotEmpty()) put("createdBy", JSONArray(e.createdBy))
        if (e.cast.isNotEmpty()) put("cast", JSONArray(e.cast))
        if (e.directors.isNotEmpty()) put("directors", JSONArray(e.directors))
        if (e.writers.isNotEmpty()) put("writers", JSONArray(e.writers))
        // Details' parts, each left out when empty: details stored before they were kept read as having none.
        if (e.castPictures.isNotEmpty()) put("castPics", JSONArray(e.castPictures.map { it ?: "" }))
        if (e.networks.isNotEmpty()) put("networks", JSONArray(e.networks))
        if (e.studios.isNotEmpty()) put("studios", JSONArray(e.studios))
        if (e.countries.isNotEmpty()) put("countries", JSONArray(e.countries))
        put("recommendations", writeItems(e.recommendations))
        put("similar", writeItems(e.similar))
    }

    fun read(o: JSONObject?): Extras? {
        if (o == null) return null
        val id = o.optInt("id", 0).takeIf { it > 0 } ?: return null
        val kind = kindOf(o.text("type")) ?: return null
        val cast = o.optJSONArray("cast").texts()
        return Extras(
            tmdbId = id,
            kind = kind,
            imdbId = o.text("imdb"),
            overview = o.text("overview"),
            ageRating = o.text("rating"),
            runtimeMin = o.optInt("runtime", 0).takeIf { it > 0 },
            releaseDate = o.text("date"),
            status = o.text("status"),
            backdrop = o.text("backdrop"),
            createdBy = o.optJSONArray("createdBy").texts(),
            cast = cast,
            directors = o.optJSONArray("directors").texts(),
            writers = o.optJSONArray("writers").texts(),
            recommendations = readItems(o.optJSONArray("recommendations")),
            similar = readItems(o.optJSONArray("similar")),
            originalLanguage = o.text("lang"),
            castPictures = pictures(o.optJSONArray("castPics"), cast.size),
            networks = o.optJSONArray("networks").texts(),
            studios = o.optJSONArray("studios").texts(),
            countries = o.optJSONArray("countries").texts(),
            format = o.optInt("v", 1),
        )
    }

    /**
     * The stored portraits, one per cast name ("" for none): empty when there are none, or when they don't pair up
     * with the names (a damaged entry shows no portraits rather than the wrong ones).
     */
    private fun pictures(a: JSONArray?, castSize: Int): List<String?> {
        if (a == null || a.length() != castSize) return emptyList()
        val out = (0 until a.length()).map { i -> (a.opt(i) as? String)?.takeIf { it.startsWith("/") } }
        return if (out.any { it != null }) out else emptyList()
    }

    internal fun writeItems(items: List<Item>) = JSONArray().apply {
        for (i in items) {
            put(JSONObject().apply {
                put("id", i.tmdbId)
                put("type", i.kind.type)
                put("name", i.name)
                i.date?.let { put("date", it) }
                i.poster?.let { put("poster", it) }
                i.backdrop?.let { put("backdrop", it) }
                i.overview?.let { put("overview", it) }
                put("votes", i.votes)
                if (i.adult) put("adult", true)
            })
        }
    }

    internal fun readItems(a: JSONArray?): List<Item> = objects(a).mapNotNull { o ->
        Item(
            tmdbId = o.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null,
            kind = kindOf(o.text("type")) ?: return@mapNotNull null,
            name = o.text("name") ?: return@mapNotNull null,
            date = o.text("date"),
            poster = o.text("poster"),
            backdrop = o.text("backdrop"),
            overview = o.text("overview"),
            votes = o.optInt("votes", 0),
            adult = o.optBoolean("adult", false),
        )
    }

    /** A list part's first [LIST_KEPT] items. Each item's kind is its `media_type` (recommendations), else [kind]. */
    internal fun items(part: JSONObject?, kind: Kind): List<Item> =
        objects(part?.optJSONArray("results")).asSequence().mapNotNull { o ->
            val k = when (o.text("media_type")) {
                null -> kind
                "movie" -> Kind.MOVIE
                "tv" -> Kind.SERIES
                else -> return@mapNotNull null
            }
            val show = k == Kind.SERIES
            Item(
                tmdbId = o.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null,
                kind = k,
                name = o.text(if (show) "name" else "title") ?: o.text(if (show) "original_name" else "original_title")
                    ?: return@mapNotNull null,
                date = o.text(if (show) "first_air_date" else "release_date"),
                poster = o.text("poster_path")?.takeIf { it.startsWith("/") },
                backdrop = o.text("backdrop_path")?.takeIf { it.startsWith("/") },
                overview = o.text("overview"),
                votes = o.optInt("vote_count", 0),
                adult = o.optBoolean("adult", false),
            )
        }.take(LIST_KEPT).toList()

    private fun objects(a: JSONArray?): List<JSONObject> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    private val WRITING_JOBS = setOf("Screenplay", "Writer", "Novel", "Story", "Teleplay", "Author", "Original Story")
    private val UNRATED = setOf("NR", "NOT RATED", "UNRATED")
}
