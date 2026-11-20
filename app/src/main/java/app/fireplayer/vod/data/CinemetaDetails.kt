package app.fireplayer.vod.data

import app.fireplayer.vod.Episode
import app.fireplayer.vod.Kind
import app.fireplayer.vod.Season
import app.fireplayer.vod.Title
import app.fireplayer.vod.TitleDetails
import org.json.JSONArray
import org.json.JSONObject

/**
 * A title's Cinemeta meta (`/meta/{movie|series}/{tt}.json`) read for the title page, tolerant of missing fields, and
 * the trimmed form kept on disk (a show's raw meta is up to 0.65 MB; trimmed about 40%, most shows under 30 KB). Pure:
 * no network, no Android.
 *
 * Episodes come only from here (`videos[]`; a catalog preview holds only the latest 20 or so): sorted with the regular
 * seasons first in order and Specials (season 0) last, each (season, episode) once, gaps kept.
 */
object CinemetaDetails {

    /** How long a meta is fresh (Cinemeta's own answer says 3 h; the brief about 4 h). */
    const val FRESH_MS = 4 * 3600_000L

    /** The cache key of a title's trimmed meta. Kept apart from step 1's bare-title entry under the meta's address. */
    fun key(kind: Kind, id: String) = "cinemeta|details|v1|${kind.type}|$id"

    /**
     * What the title page reads from a meta. [title] is the completed title (genres at most 3, a cut description ended
     * at its last whole word, no "0" rating); [releasedAt] is Cinemeta's `released` (a film's day at midnight UTC, a
     * show's first air day); people lists are as Cinemeta orders them (cast: its top 3; a show's [writers] are its
     * creators); [episodes] are sorted (see the class).
     */
    data class Meta(
        val title: Title,
        val releasedAt: Long? = null,
        val status: String? = null,
        val cast: List<String> = emptyList(),
        val directors: List<String> = emptyList(),
        val writers: List<String> = emptyList(),
        val awards: String? = null,
        val country: String? = null,
        val episodes: List<Episode> = emptyList(),
    )

    /**
     * A meta answer as [Meta]; null for `{}` (asked with the wrong kind), an answer about another kind, or one about
     * another id than [id] (when given).
     */
    fun parse(text: String, kind: Kind, id: String? = null): Meta? {
        val o = JSONObject(text).optJSONObject("meta") ?: return null
        val preview = Cinemeta.parsePreview(o, kind)?.takeIf { it.kind == kind } ?: return null
        if (id != null && preview.id != id) return null
        val title = preview.copy(
            name = clean(preview.name) ?: preview.name,
            description = story(clean(preview.description)),
            imdbRating = preview.imdbRating?.takeIf { it != "0" },
            genres = preview.genres.take(MAX_GENRES),
        )
        val runtimeMin = TitleDetails.runtimeMinutes(title.runtime)
        return Meta(
            title = title,
            releasedAt = Dates.parse(o.text("released")),
            status = o.text("status"),
            cast = people(o.opt("cast")),
            directors = people(o.opt("director")),
            writers = people(o.opt("writer")),
            awards = clean(o.text("awards")),
            country = o.text("country"),
            episodes = if (kind == Kind.SERIES) episodes(title.id, o.optJSONArray("videos"), runtimeMin) else emptyList(),
        )
    }

    /**
     * A show's `videos` as episodes: season (required; 0 = Specials), `episode` (else `number`), `name` (else `title`,
     * else "Episode N"), `released` (else `firstAired`; read in US Pacific time for its [Episode.day]), `overview`
     * (else `description`), the still at `w500`, and [runtimeMin] (the show's usual length) except on specials.
     */
    fun episodes(showId: String, videos: JSONArray?, runtimeMin: Int?): List<Episode> {
        if (videos == null) return emptyList()
        val seen = HashSet<Long>()
        val cal = Dates.calendar(Dates.PACIFIC)
        val out = ArrayList<Episode>(videos.length())
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            val season = v.int("season") ?: continue
            val number = v.int("episode") ?: v.int("number") ?: continue
            if (season < 0 || number < 0) continue
            if (!seen.add((season.toLong() shl 32) or number.toLong())) continue
            val released = Dates.parse(v.text("released") ?: v.text("firstAired"))
            out += Episode(
                id = Episode.idOf(showId, season, number),
                season = season,
                episode = number,
                name = clean(v.text("name") ?: v.text("title")) ?: "Episode $number",
                released = released,
                day = released?.let { Dates.dayOf(it, cal) },
                overview = clean(v.text("overview") ?: v.text("description")),
                still = still(v.text("thumbnail")),
                runtimeMin = if (season == 0) null else runtimeMin,
            )
        }
        return sorted(out)
    }

    /** Regular seasons in order, then Specials; episodes in order within each. */
    fun sorted(episodes: List<Episode>): List<Episode> =
        episodes.sortedWith(compareBy<Episode>({ it.season == 0 }, { it.season }, { it.episode }))

    /** [episodes] (sorted) grouped into seasons, in the same order: regular ones, then Specials. */
    fun seasons(episodes: List<Episode>): List<Season> {
        if (episodes.isEmpty()) return emptyList()
        val out = ArrayList<Season>()
        var start = 0
        for (i in 1..episodes.size) {
            if (i == episodes.size || episodes[i].season != episodes[start].season) {
                out += Season(episodes[start].season, episodes.subList(start, i).toList())
                start = i
            }
        }
        return out
    }

    /**
     * An episode still at `w500` (500 x 281, the smallest that covers the grown card): metahub's address with its size
     * swapped (it redirects to TMDB's picture). Other addresses as they are; blank is none.
     */
    fun still(url: String?): String? {
        val u = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val m = METAHUB_STILL.matchEntire(u) ?: return u
        return "${m.groupValues[1]}$STILL_SIZE.jpg"
    }

    /** The still Cinemeta gives every episode, at [STILL_SIZE]; stored as a flag rather than the address. */
    private fun standardStill(showId: String, season: Int, episode: Int) =
        "https://episodes.metahub.space/$showId/$season/$episode/$STILL_SIZE.jpg"

    /**
     * Cinemeta's description for the story: about 9% are cut at 230 characters plus "..." ("…prevent a terrible
     * fu..."); such a one ends at its last whole word instead, with "…". Others as they are; blank is none.
     */
    fun story(text: String?): String? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!t.endsWith("...") || t.length !in CUT_LENGTHS) return t
        val body = t.dropLast(3)
        val whole = body.substringBeforeLast(' ', "").trimEnd(' ', ',', ';', ':', '-', '—', '–')
        if (whole.length < body.length / 2) return t
        return "$whole…"
    }

    /** "Won 2 Oscars", "Nominated for 1 Oscar" when [awards] starts with them; null otherwise (Emmys, BAFTAs…). */
    fun oscars(awards: String?): String? {
        val m = OSCARS.find(awards?.trim() ?: return null) ?: return null
        return "${m.groupValues[1]} ${m.groupValues[2]} ${m.groupValues[3]}"
    }

    // ---- The trimmed form kept on disk ----

    fun write(m: Meta): JSONObject = JSONObject().apply {
        put("title", TitleJson.write(m.title))
        m.releasedAt?.let { put("released", it) }
        m.status?.let { put("status", it) }
        if (m.cast.isNotEmpty()) put("cast", JSONArray(m.cast))
        if (m.directors.isNotEmpty()) put("director", JSONArray(m.directors))
        if (m.writers.isNotEmpty()) put("writer", JSONArray(m.writers))
        m.awards?.let { put("awards", it) }
        m.country?.let { put("country", it) }
        if (m.episodes.isNotEmpty()) {
            val showId = m.title.id
            put("eps", JSONArray().apply {
                for (e in m.episodes) {
                    put(JSONObject().apply {
                        put("s", e.season)
                        put("e", e.episode)
                        put("n", e.name)
                        e.released?.let { put("r", it) }
                        e.overview?.let { put("o", it) }
                        when (e.still) {
                            null -> Unit
                            standardStill(showId, e.season, e.episode) -> put("t", 1)
                            else -> put("t", e.still)
                        }
                    })
                }
            })
        }
    }

    /** [write]'s form read back; null when damaged. */
    fun read(o: JSONObject?): Meta? {
        if (o == null) return null
        val title = TitleJson.read(o.optJSONObject("title")) ?: return null
        val runtimeMin = TitleDetails.runtimeMinutes(title.runtime)
        val eps = o.optJSONArray("eps")
        val episodes = if (eps == null) emptyList() else {
            val cal = Dates.calendar(Dates.PACIFIC)
            (0 until eps.length()).mapNotNull { i ->
                val e = eps.optJSONObject(i) ?: return@mapNotNull null
                val season = e.int("s") ?: return@mapNotNull null
                val number = e.int("e") ?: return@mapNotNull null
                val released = if (e.has("r")) e.optLong("r") else null
                Episode(
                    id = Episode.idOf(title.id, season, number),
                    season = season,
                    episode = number,
                    name = e.text("n") ?: "Episode $number",
                    released = released,
                    day = released?.let { Dates.dayOf(it, cal) },
                    overview = e.text("o"),
                    still = when (val t = e.opt("t")) {
                        is Number -> standardStill(title.id, season, number)
                        is String -> t
                        else -> null
                    },
                    runtimeMin = if (season == 0) null else runtimeMin,
                )
            }
        }
        return Meta(
            title = title,
            releasedAt = if (o.has("released")) o.optLong("released") else null,
            status = o.text("status"),
            cast = o.optJSONArray("cast").texts(),
            directors = o.optJSONArray("director").texts(),
            writers = o.optJSONArray("writer").texts(),
            awards = o.text("awards"),
            country = o.text("country"),
            episodes = episodes,
        )
    }

    // ---- Helpers ----

    /** Names from an array (or a lone string), cleaned, each once, in order. */
    private fun people(v: Any?): List<String> {
        val raw = when (v) {
            is JSONArray -> v.texts()
            is String -> listOfNotNull(v.trim().takeIf { it.isNotEmpty() })
            else -> emptyList()
        }
        return raw.mapNotNull { clean(it) }.distinct()
    }

    /**
     * Text with the HTML entities Cinemeta sometimes leaves in ("Leah Sava&apos; Jeffries") read; blank is none.
     */
    internal fun clean(s: String?): String? {
        val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!t.contains('&')) return t
        return t.replace("&apos;", "'").replace("&#39;", "'").replace("&quot;", "\"").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&")
    }

    /** An int field given as a number or a numeric string. */
    private fun JSONObject.int(key: String): Int? = when (val v = opt(key)) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    const val MAX_GENRES = 3
    const val STILL_SIZE = "w500"
    // Cinemeta's cut: 230 characters plus "..." (233), give or take how a character is counted.
    private val CUT_LENGTHS = 228..236
    private val METAHUB_STILL = Regex("""(https?://episodes\.metahub\.space/.+/)w\d+\.jpg""")
    private val OSCARS = Regex("""^(Won|Nominated for) (\d+) (Oscars?)(?![A-Za-z])""")
}
