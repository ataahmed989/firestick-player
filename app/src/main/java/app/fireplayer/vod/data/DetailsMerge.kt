package app.fireplayer.vod.data

import app.fireplayer.vod.Day
import app.fireplayer.vod.From
import app.fireplayer.vod.Kind
import app.fireplayer.vod.Sourced
import app.fireplayer.vod.Title
import app.fireplayer.vod.TitleDetails

/**
 * A title page's [TitleDetails] put together from what is known (pure): the title the page was opened with, Cinemeta's
 * meta and TMDB's details, either of which may be missing. Cinemeta gives most facts; TMDB's story, cast, crew and
 * creators win when it has them; the age rating is TMDB's only. Missing facts are null or empty, never made up.
 */
object DetailsMerge {

    /**
     * [asked] is the title as the page had it (a row's card); [background] the backdrop chosen once for the visit;
     * [rank] this week's Top 10 place, looked up by the caller.
     */
    fun compose(
        asked: Title,
        meta: CinemetaDetails.Meta?,
        extras: TmdbDetails.Extras?,
        background: String?,
        rank: Int?,
    ): TitleDetails {
        val base = meta?.title ?: asked
        val kind = asked.kind
        val show = kind == Kind.SERIES
        val title = base.copy(
            year = base.year ?: asked.year ?: extras?.releaseDate?.take(4)?.takeIf { it.length == 4 },
            poster = base.poster ?: asked.poster,
            background = background ?: base.background ?: asked.background,
            logo = base.logo ?: asked.logo,
            description = base.description ?: asked.description,
            imdbRating = (base.imdbRating ?: asked.imdbRating)?.takeIf { it != "0" },
            genres = base.genres.ifEmpty { asked.genres }.take(CinemetaDetails.MAX_GENRES),
            runtime = base.runtime ?: asked.runtime,
            tmdbId = base.tmdbId ?: asked.tmdbId ?: extras?.tmdbId,
        )
        val episodes = if (show) meta?.episodes.orEmpty() else emptyList()
        val tmdbDay = extras?.releaseDate?.let(Dates::parse)
        val releasedAt: Long?
        val released: Day?
        if (show) {
            // A show's first regular episode (its Pacific air day); else Cinemeta's or TMDB's day, as written.
            val first = episodes.firstOrNull { it.season > 0 && it.released != null }
            releasedAt = first?.released ?: meta?.releasedAt ?: tmdbDay
            released = first?.day ?: releasedAt?.let { Dates.dayOf(it, Dates.UTC) }
        } else {
            releasedAt = meta?.releasedAt ?: tmdbDay
            released = releasedAt?.let { Dates.dayOf(it, Dates.UTC) }
        }
        fun names(tmdb: List<String>?, cinemeta: List<String>?): Sourced<List<String>>? = when {
            !tmdb.isNullOrEmpty() -> Sourced(tmdb, From.TMDB)
            !cinemeta.isNullOrEmpty() -> Sourced(cinemeta, From.CINEMETA)
            else -> null
        }
        val cast = names(extras?.cast, meta?.cast)
        return TitleDetails(
            title = title,
            releasedAt = releasedAt,
            released = released,
            runtimeMin = TitleDetails.runtimeMinutes(title.runtime) ?: extras?.runtimeMin,
            status = if (show) meta?.status ?: extras?.status?.let(::cinemetaStatus) else null,
            ageRating = extras?.ageRating,
            story = extras?.overview?.let { Sourced(it, From.TMDB) }
                ?: title.description?.let { Sourced(it, From.CINEMETA) },
            cast = cast,
            directors = if (show) names(null, meta?.directors) else names(extras?.directors, meta?.directors),
            writers = if (show) null else names(extras?.writers, meta?.writers),
            creators = if (show) names(extras?.createdBy, meta?.writers) else null,
            awards = meta?.awards,
            oscars = CinemetaDetails.oscars(meta?.awards),
            country = meta?.country ?: extras?.countries?.takeIf { it.isNotEmpty() }?.joinToString(", ") { countryName(it) },
            // Portraits only beside TMDB's own cast, in its order.
            castPictures = if (cast?.from == From.TMDB) extras?.castPictures.orEmpty().map { p -> p?.let(VodArt::tmdbProfile) } else emptyList(),
            language = languageName(extras?.originalLanguage),
            networks = if (show) extras?.networks.orEmpty() else emptyList(),
            studios = if (show) emptyList() else extras?.studios.orEmpty(),
            top10Rank = rank,
            seasons = CinemetaDetails.seasons(episodes),
        )
    }

    /**
     * A language's name in English from its ISO 639 code ("en" → "English"); null for none, TMDB's "xx" (no language),
     * or a code Java doesn't know. TMDB writes Cantonese "cn".
     */
    fun languageName(code: String?): String? {
        val c = code?.trim()?.lowercase(java.util.Locale.ROOT)?.takeIf { it.length in 2..3 && it.all { ch -> ch in 'a'..'z' } }
            ?: return null
        if (c == "xx") return null
        if (c == "cn") return "Cantonese"
        val name = java.util.Locale.forLanguageTag(c).getDisplayLanguage(java.util.Locale.ENGLISH)
        return name.takeIf { it.isNotBlank() && !it.equals(c, ignoreCase = true) }
    }

    /** A country as TMDB names it, the way Cinemeta writes it ("United States of America" → "United States"). */
    fun countryName(tmdb: String): String = if (tmdb == "United States of America") "United States" else tmdb

    /** TMDB's status in Cinemeta's words: "Ended" (also "Canceled") or "Continuing". */
    fun cinemetaStatus(tmdb: String): String = when (tmdb.trim().lowercase()) {
        "ended", "canceled", "cancelled" -> "Ended"
        else -> "Continuing"
    }
}
