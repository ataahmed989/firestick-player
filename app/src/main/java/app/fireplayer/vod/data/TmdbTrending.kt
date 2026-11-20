package app.fireplayer.vod.data

import app.fireplayer.vod.Kind
import app.fireplayer.vod.Title
import org.json.JSONObject

/**
 * TMDB's weekly trending lists made into Top 10 rows, with the fewest TMDB calls. Pure: the calls themselves are
 * [TmdbSource]'s.
 *
 * TMDB's lists have no IMDb ids, and TMDB has no batch lookup. So: one call for the list (20 titles), then the titles
 * are walked in order until 10 have an IMDb id. An id already known (from Cinemeta's pages, which carry each title's
 * TMDB id, or from an earlier answer) costs nothing; each unknown one costs one `external_ids` call, asked only for as
 * many titles as can still be needed. A title without an IMDb id is left out and the ranks close up (1..10, no gaps).
 */
object TmdbTrending {

    /** How long a Top 10 is fresh: TMDB's list moves daily, the brief says 6 h. */
    const val FRESH_MS = 6 * 3600_000L
    const val WANTED = 10

    /** One title from a trending list. [backdrop] is TMDB's path ("/abc.jpg"). */
    data class Candidate(
        val tmdbId: Int,
        val kind: Kind,
        val name: String,
        val year: String?,
        val overview: String?,
        val backdrop: String?,
    )

    /**
     * A trending answer's titles in order. [kind] for the film or TV list; null for `trending/all`, where each item's
     * `media_type` says (people are left out). Adult titles and items without an id or a name are left out too.
     */
    fun parse(text: String, kind: Kind?): List<Candidate> {
        val results = JSONObject(text).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { results.optJSONObject(it) }.mapNotNull { o ->
            val k = when (o.text("media_type")) {
                null -> kind
                "movie" -> Kind.MOVIE
                "tv" -> Kind.SERIES
                else -> null
            } ?: return@mapNotNull null
            if (kind != null && k != kind) return@mapNotNull null
            if (o.optBoolean("adult", false)) return@mapNotNull null
            val id = o.optInt("id", 0).takeIf { it > 0 } ?: return@mapNotNull null
            val show = k == Kind.SERIES
            val name = o.text(if (show) "name" else "title") ?: o.text(if (show) "original_name" else "original_title")
                ?: return@mapNotNull null
            Candidate(
                tmdbId = id,
                kind = k,
                name = name,
                year = o.text(if (show) "first_air_date" else "release_date")?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) },
                overview = o.text("overview"),
                backdrop = o.text("backdrop_path")?.takeIf { it.startsWith("/") },
            )
        }
    }

    /** The IMDb id in an `external_ids` (or film details) answer; null when TMDB has none ("", null, not "tt…"). */
    fun imdbOf(text: String): String? = JSONObject(text).text("imdb_id")?.takeIf { it.startsWith("tt") }

    /** What's known of a candidate's IMDb id. */
    sealed class Known {
        data class Hit(val imdb: String) : Known()
        /** TMDB had none lately. */
        data object Miss : Known()
        data object Unknown : Known()
    }

    /**
     * The candidates to ask TMDB about next: walking in order, the unknown ones up to the point where the hits so far
     * plus these would make [wanted]. Empty when nothing more needs asking (enough hits, or the list ran out).
     */
    fun toAsk(candidates: List<Candidate>, known: (Candidate) -> Known, wanted: Int = WANTED): List<Candidate> {
        var hits = 0
        val ask = ArrayList<Candidate>()
        for (c in candidates) {
            if (hits + ask.size >= wanted) break
            when (known(c)) {
                is Known.Hit -> hits++
                Known.Miss -> Unit
                Known.Unknown -> ask += c
            }
        }
        return ask
    }

    /** The first [wanted] candidates with an IMDb id, in order, each IMDb id once; unknown and missed ones left out. */
    fun ranked(candidates: List<Candidate>, known: (Candidate) -> Known, wanted: Int = WANTED): List<Pair<Candidate, String>> {
        val out = ArrayList<Pair<Candidate, String>>()
        val seen = HashSet<String>()
        for (c in candidates) {
            if (out.size >= wanted) break
            val hit = known(c) as? Known.Hit ?: continue
            if (seen.add(hit.imdb)) out += c to hit.imdb
        }
        return out
    }

    /**
     * The [Title] a Top 10 card shows: Cinemeta's when it's known (its poster, rating, genres, runtime, a series' year
     * range), else one made from TMDB's list. Either way TMDB's backdrop at `w1280` for the header when TMDB has one
     * (about 160 KB, five times less than metahub's), and TMDB's id.
     */
    fun title(c: Candidate, imdb: String, cinemeta: Title?): Title {
        val base = cinemeta?.takeIf { it.id == imdb && it.kind == c.kind } ?: Title(
            id = imdb,
            kind = c.kind,
            name = c.name,
            year = c.year,
            poster = VodArt.poster(imdb),
            background = VodArt.background(imdb),
            logo = VodArt.logo(imdb),
            description = c.overview,
        )
        return base.copy(
            background = c.backdrop?.let(VodArt::tmdbBackdrop) ?: base.background,
            description = base.description ?: c.overview,
            tmdbId = c.tmdbId,
        )
    }

    /** Home's header: films and series taken in turn (film first), [count] in all; the longer list fills in. */
    fun <T> interleave(first: List<T>, second: List<T>, count: Int = WANTED): List<T> {
        val out = ArrayList<T>(count)
        var i = 0
        while (out.size < count && (i < first.size || i < second.size)) {
            if (i < first.size) out += first[i]
            if (out.size < count && i < second.size) out += second[i]
            i++
        }
        return out
    }
}
