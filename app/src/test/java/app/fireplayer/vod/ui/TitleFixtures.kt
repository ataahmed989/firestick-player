package app.fireplayer.vod.ui

import app.fireplayer.vod.Day
import app.fireplayer.vod.Episode
import app.fireplayer.vod.From
import app.fireplayer.vod.Kind
import app.fireplayer.vod.Season
import app.fireplayer.vod.Sourced
import app.fireplayer.vod.Title
import app.fireplayer.vod.TitleDetails
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The shared title-page fixtures (test/resources/vod, recorded 2026-11-18 for step 2) read into the contract's
 * [TitleDetails], as the data side gives them (vod2_api.md §1-2), so the page's words can be checked against
 * expected_title_page.json. A small reader for tests only: the real parsing is the repository's.
 */
object TitleFixtures {

    /** The fixtures' "now": 2026-11-18T22:40Z. */
    val NOW: Long = Instant.parse("2026-11-18T22:40:00Z").toEpochMilli()
    const val THIS_YEAR = 2026

    private val PACIFIC = ZoneId.of("America/Los_Angeles")
    private val OSCARS = Regex("""^(Won|Nominated for) (\d+) (Oscars?)(?![A-Za-z])""")

    fun text(name: String): String =
        requireNotNull(TitleFixtures::class.java.getResource("/vod/$name")) { "missing fixture $name" }.readText()

    fun json(name: String) = JSONObject(text(name))

    fun expected(): JSONObject = json("expected_title_page.json")

    /** [name]'s meta as the page's details; [ageRating] (TMDB's, when the case has a key). */
    fun details(name: String, ageRating: String? = null): TitleDetails {
        val m = json(name).getJSONObject("meta")
        val kind = if (m.getString("type") == "series") Kind.SERIES else Kind.MOVIE
        val id = m.getString("id")
        val genres = strings(m.optJSONArray("genres")).ifEmpty { strings(m.optJSONArray("genre")) }.take(3)
        val runtime = m.optString("runtime").takeIf { it.isNotBlank() }
        val title = Title(
            id = id,
            kind = kind,
            name = m.getString("name"),
            year = m.optString("releaseInfo").takeIf { it.isNotBlank() },
            logo = m.optString("logo").takeIf { it.isNotBlank() },
            background = m.optString("background").takeIf { it.isNotBlank() },
            description = m.optString("description").takeIf { it.isNotBlank() },
            imdbRating = m.optString("imdbRating").takeIf { it.isNotBlank() },
            genres = genres,
            runtime = runtime,
        )
        val released = if (m.isNull("released")) null else m.optString("released").takeIf { it.isNotBlank() }?.let { Instant.parse(it) }
        val runtimeMin = TitleDetails.runtimeMinutes(runtime)
        val awards = m.optString("awards").takeIf { it.isNotBlank() && !m.isNull("awards") }
        val writers = strings(m.optJSONArray("writer"))
        return TitleDetails(
            title = title,
            releasedAt = released?.toEpochMilli(),
            released = released?.atZone(ZoneOffset.UTC)?.let { Day(it.year, it.monthValue, it.dayOfMonth) },
            runtimeMin = runtimeMin,
            status = m.optString("status").takeIf { it.isNotBlank() && !m.isNull("status") },
            ageRating = ageRating,
            story = title.description?.let { Sourced(it, From.CINEMETA) },
            cast = strings(m.optJSONArray("cast")).takeIf { it.isNotEmpty() }?.let { Sourced(it, From.CINEMETA) },
            directors = if (kind == Kind.MOVIE) strings(m.optJSONArray("director")).takeIf { it.isNotEmpty() }?.let { Sourced(it, From.CINEMETA) } else null,
            writers = if (kind == Kind.MOVIE) writers.takeIf { it.isNotEmpty() }?.let { Sourced(it, From.CINEMETA) } else null,
            creators = if (kind == Kind.SERIES) writers.takeIf { it.isNotEmpty() }?.let { Sourced(it, From.CINEMETA) } else null,
            awards = awards,
            oscars = awards?.let { OSCARS.find(it)?.value },
            country = m.optString("country").takeIf { it.isNotBlank() },
            seasons = if (kind == Kind.SERIES) seasons(id, m.optJSONArray("videos"), runtimeMin) else emptyList(),
        )
    }

    private fun seasons(showId: String, videos: JSONArray?, runtimeMin: Int?): List<Season> {
        if (videos == null) return emptyList()
        val eps = LinkedHashMap<Pair<Int, Int>, Episode>()
        for (i in 0 until videos.length()) {
            val v = videos.getJSONObject(i)
            if (!v.has("season")) continue
            val s = v.getInt("season")
            val e = if (v.has("episode")) v.getInt("episode") else v.optInt("number")
            val at = (v.optString("released").takeIf { it.isNotBlank() } ?: v.optString("firstAired").takeIf { it.isNotBlank() })
                ?.let { Instant.parse(it) }
            val day = at?.atZone(PACIFIC)?.let { Day(it.year, it.monthValue, it.dayOfMonth) }
            val name = v.optString("name").takeIf { it.isNotBlank() } ?: v.optString("title").takeIf { it.isNotBlank() } ?: "Episode $e"
            val overview = v.optString("overview").takeIf { it.isNotBlank() } ?: v.optString("description").takeIf { it.isNotBlank() }
            eps.putIfAbsent(s to e, Episode(
                id = Episode.idOf(showId, s, e),
                season = s,
                episode = e,
                name = name,
                released = at?.toEpochMilli(),
                day = day,
                overview = overview,
                still = v.optString("thumbnail").takeIf { it.isNotBlank() }?.replace("/w780.jpg", "/w500.jpg"),
                runtimeMin = if (s == 0) null else runtimeMin,
            ))
        }
        return eps.values.groupBy { it.season }
            .map { (n, list) -> Season(n, list.sortedBy { it.episode }) }
            .sortedWith(compareBy({ it.number == 0 }, { it.number }))
    }

    private fun strings(a: JSONArray?): List<String> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
}
