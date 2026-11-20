package app.fireplayer.vod.data

import app.fireplayer.vod.Day
import app.fireplayer.vod.Episode
import app.fireplayer.vod.Kind
import app.fireplayer.vod.PlayAction
import app.fireplayer.vod.PlayTarget
import app.fireplayer.vod.Title
import app.fireplayer.vod.TitleDetails
import org.json.JSONObject
import java.time.Instant

/**
 * The title page's fixtures (test/resources/vod, recorded 2026-11-18; TMDB's are synthetic in TMDB's documented shape)
 * and expected_title_page.json, written by the research's reference implementation at [NOW]. The words below are only
 * how the tests compare with that file; the page's own words are its TitleRules.
 */
object TitlePageFixtures {

    val NOW: Long = Instant.parse("2026-11-18T22:40:00Z").toEpochMilli()

    val expected: JSONObject by lazy { Fixtures.json("expected_title_page.json") }

    fun meta(name: String, kind: Kind): CinemetaDetails.Meta =
        requireNotNull(CinemetaDetails.parse(Fixtures.text(name), kind)) { "no meta in $name" }

    fun tmdb(name: String, kind: Kind): TmdbDetails.Extras =
        requireNotNull(TmdbDetails.parse(Fixtures.text(name), kind)) { "no details in $name" }

    /** The details a page would show from these fixtures (opened from the meta's own title). */
    fun details(metaName: String, kind: Kind, tmdbName: String? = null, rank: Int? = null): TitleDetails {
        val meta = meta(metaName, kind)
        val asked = Title(meta.title.id, kind, meta.title.name)
        return DetailsMerge.compose(asked, meta, tmdbName?.let { tmdb(it, kind) }, null, rank)
    }

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun label(d: Day, withYear: Boolean) = "${MONTHS[d.month - 1]} ${d.day}" + if (withYear) ", ${d.year}" else ""

    fun runtime(minutes: Int?): String? {
        if (minutes == null || minutes <= 0) return null
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }

    /** The facts line as the research's reference writes it. */
    fun facts(d: TitleDetails): String {
        val t = d.title
        val parts = ArrayList<String?>()
        parts += t.year
        if (t.kind == Kind.SERIES) {
            val n = d.seasonCount
            parts += if (n > 0) "$n season${if (n != 1) "s" else ""}" else null
            parts += if (d.status == "Ended") "Ended" else null
        } else {
            parts += runtime(d.runtimeMin)
        }
        parts += t.imdbRating?.let { "★ $it" }
        parts += d.ageRating
        parts += t.genres.takeIf { it.isNotEmpty() }?.joinToString(", ")
        return parts.filterNotNull().joinToString(" · ")
    }

    fun castLine(d: TitleDetails): String? {
        val head = d.cast?.value?.take(3)?.takeIf { it.isNotEmpty() }?.let { "Starring " + it.joinToString(", ") }
        val tail = if (d.title.kind == Kind.SERIES) {
            d.creators?.value?.take(2)?.let { "Created by " + it.joinToString(", ") }
        } else {
            d.directors?.value?.take(2)?.let { "Director " + it.joinToString(", ") }
        }
        return listOfNotNull(head, tail).joinToString(" · ").ifEmpty { null }
    }

    fun coming(d: TitleDetails, now: Long = NOW): String? {
        if (d.isOut(now, 2026)) return null
        val day = d.released ?: return "Coming ${d.title.year?.take(4)}"
        return "Coming " + label(day, day.year != 2026)
    }

    fun pills(d: TitleDetails): List<String> =
        d.seasons.map { if (it.isSpecials) "Specials" else "Season ${it.number}" }.takeIf { it.size > 1 }.orEmpty()

    fun cardLines(e: Episode, now: Long = NOW): List<String?> {
        val line1 = "E${e.episode} · ${e.name}"
        val day = e.day ?: return listOf(line1, null)
        if (!e.aired(now)) return listOf(line1, "Airs " + label(day, day.year != 2026))
        return listOf(line1, listOfNotNull(runtime(e.runtimeMin), label(day, true)).joinToString(" · "))
    }

    fun playLabel(t: PlayTarget): String {
        val e = t.episode
        return when (t.action) {
            PlayAction.PLAY -> "Play S${e.season} E${e.episode}"
            PlayAction.RESUME -> "Resume S${e.season} E${e.episode} · " + Captions.left(t.position!!.leftMs!!)
            PlayAction.WATCH_AGAIN -> "Watch again · S${e.season} E${e.episode}"
        }
    }

    fun upToDate(t: PlayTarget): String? = t.upcoming?.let { u ->
        "Up to date · S${u.season} E${u.episode} airs " + label(u.day!!, u.day!!.year != 2026)
    }

    fun ref(e: Episode) = "S${e.season} E${e.episode}"
}
