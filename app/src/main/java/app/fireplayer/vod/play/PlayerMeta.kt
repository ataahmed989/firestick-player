package app.fireplayer.vod.play

import app.fireplayer.player.MatchFixes
import app.fireplayer.player.ShowGuide
import app.fireplayer.subs.Tmdb
import app.fireplayer.vod.Episode
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Kind
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.Position
import app.fireplayer.vod.Title
import app.fireplayer.vod.TitleDetails
import app.fireplayer.vod.standInId
import java.util.Locale

/**
 * A title from Films & Shows put right by hand in the player's Fix match: the TMDB film or show it really is, for what
 * the player shows and asks about it ([PlayerMeta.fixed]). The add-ons still find its sources by the title's own id.
 * Kept per title, for every later play of it ([app.fireplayer.player.MatchFixes.vodTitle]).
 */
data class VodFix(val tmdbId: Int, val show: Boolean, val name: String, val year: Int) {
    /** "The Odyssey (1997)", or the name alone without a year. */
    val label: String get() = if (year > 0) "$name ($year)" else name

    /** How it is kept: "1234\t0\t1997\tThe Odyssey" (TMDB id, 1 for a show, year, name). */
    fun toLine(): String = "$tmdbId\t${if (show) 1 else 0}\t$year\t${name.replace('\t', ' ')}"

    companion object {
        /** The fix a kept line holds; null for none, or a line that names no TMDB title. */
        fun fromLine(line: String?): VodFix? {
            val p = line?.split('\t', limit = 4)?.takeIf { it.size == 4 } ?: return null
            val id = p[0].toIntOrNull()?.takeIf { it > 0 } ?: return null
            return VodFix(id, p[1] == "1", p[3], p[2].toIntOrNull() ?: 0)
        }
    }
}

/**
 * What FirePlayer's player shows of a film or episode played from Films & Shows, made from what was handed over with
 * the play ([PlayRequest]: the IMDb id, the TMDB id, the season and episode) and from the details the title page
 * already loaded ([TitleDetails], Cinemeta's with TMDB's where there's a key). Nothing here is matched by name: the ids
 * say which film or episode it is, so the player's "This film" / "This episode" is always matched, its Episodes sheet
 * lists Cinemeta's episodes, and Film details / Show details open at once without asking TMDB again.
 *
 * Pure: no Android, no network.
 */
object PlayerMeta {
    /** Where these plays come from, for "From …" in the Episodes sheet and Source in Settings. */
    const val PORTAL = "Films & Shows"

    /** An episode's id in the show's guide: "2:5". */
    fun episodeId(season: Int, episode: Int): String = "$season:$episode"

    fun episodeId(ref: EpisodeRef): String = episodeId(ref.season, ref.episode)

    /** The id of the episode [request] plays, or null for a film. */
    fun episodeId(request: PlayRequest): String? = request.target.episode?.let { episodeId(it) }

    /** "The Matrix (1999)", "Severance (2022)": the name with its (first) year, as the player shows a title. */
    fun shownName(title: Title): String =
        listOfNotNull(title.name, title.year?.take(4)?.takeIf { y -> y.length == 4 && y.all { it.isDigit() } }?.let { "($it)" }).joinToString(" ")

    /**
     * What the player shows and asks about for [request] once it was put right by hand ([fix]): the same play with
     * TMDB's film or show as its title (by its stand-in id, [standInId], so Skip intro's times are asked by the IMDb id
     * TMDB gives for it), the season and episode kept. [request] itself without a fix. Only for its name, Film details /
     * Show details, Skip intro's times and the subtitles search: the sources, the library and Next episode keep [request].
     * An episode Fix match set to another one ([placed], kept by the title's id: [MatchFixes.vodKey]) is that one here.
     */
    fun fixed(request: PlayRequest, fix: VodFix?, placed: MatchFixes.Show? = null): PlayRequest {
        val ref = request.target.episode
        val at = ref?.let { placed?.place(episodeId(it), it.season, it.episode) }
        val episode = if (at != null && at != (ref.season to ref.episode)) EpisodeRef(at.first, at.second) else ref
        if (fix == null && episode === ref) return request
        val title = if (fix == null) request.target.title else {
            val kind = if (fix.show) Kind.SERIES else Kind.MOVIE
            Title(standInId(kind, fix.tmdbId), kind, fix.name, year = fix.year.takeIf { it > 0 }?.toString(), tmdbId = fix.tmdbId)
        }
        return request.copy(target = request.target.copy(title = title, episode = episode))
    }

    /** This film / This episode's name in the player's Settings: "The Matrix (1999)", "Severance · S2 E5"; the fix's once fixed. */
    fun thisTitleName(request: PlayRequest, fix: VodFix?): String {
        val t = fixed(request, fix).target.title
        val ref = request.target.episode ?: return shownName(t)
        return "${t.name} · S${ref.season} E${ref.episode}"
    }

    /**
     * The note in This film / This episode's dropdown, under Source and Fix match: matched by the IMDb id handed over
     * ([episode]: "S2 E5 · Name", the guide's, when known), or, once put right by hand, as which TMDB title and what goes
     * by it, the sources still found by the id. [placed]: this episode was set by hand to another one ([episode]).
     */
    fun matchNote(request: PlayRequest, fix: VodFix?, episode: String?, placed: Boolean = false): String {
        val whose = if (request.target.episode != null) "the show's" else "its"
        val ref = request.target.episode
        if (placed && ref != null) {
            return "Set by hand to " + (episode ?: "another episode") + (fix?.let { " of ${it.label}" } ?: "") +
                " on TMDB: details, Skip intro and subtitles go by it. Sources still come by the show's IMDb id, as " +
                "S${ref.season} E${ref.episode}."
        }
        if (fix != null) {
            return "Fixed by hand as ${fix.label} on TMDB: details, Skip intro and subtitles go by it. " +
                "Sources still come by $whose IMDb id."
        }
        return "Matched by $whose IMDb id from $PORTAL" + (episode?.takeIf { request.target.episode != null }?.let { ": $it." } ?: ".")
    }

    /** A title's year as a number, 0 when unknown ("2012–2014" is 2012). */
    fun year(title: Title): Int = title.year?.take(4)?.toIntOrNull() ?: 0

    /** How the player names what [request] plays, as a video's title is read: "The Matrix (1999)", "Severance S02E05". */
    fun rawTitle(request: PlayRequest): String {
        val t = request.target.title
        val ep = request.target.episode ?: return shownName(t)
        return String.format(Locale.ROOT, "%s S%02dE%02d", t.name, ep.season, ep.episode)
    }

    /**
     * The show's guide for the player (the on-screen episode name, the Episodes sheet, Next episode's name), from
     * Cinemeta's episodes: those aired by [now], in order, every one matched (their numbers are the show's own). Null
     * for a film, or a show with no aired episode listed.
     *
     * An episode Fix match set to another one ([fix], by the title's id) keeps its own id and numbers as the source's
     * (what it plays and is kept by) and shows the one it really is: TMDB's ([real], that show's episodes by their
     * numbers), else, for the same show, Cinemeta's own; until either is known, only its numbers.
     */
    fun guide(
        details: TitleDetails,
        now: Long,
        fix: MatchFixes.Show? = null,
        real: Map<Pair<Int, Int>, Tmdb.TvEpisode> = emptyMap(),
    ): ShowGuide? {
        val t = details.title
        if (t.kind != Kind.SERIES) return null
        val own = details.episodes.associateBy { it.season to it.episode }
        val sameShow = fix != null && (fix.tmdbId <= 0 || fix.tmdbId == t.tmdbId)
        val episodes = details.episodes.filter { it.aired(now) }
            .sortedWith(compareBy<Episode>({ it.season }, { it.episode }))
            .map { e ->
                val id = episodeId(e.season, e.episode)
                val at = fix?.place(id, e.season, e.episode)
                val tmdb = at?.let { real[it] }
                if (tmdb != null) {
                    return@map ShowGuide.Ep(
                        id, "", e.season, e.episode, "", tmdb.season, tmdb.number, tmdb.name, tmdb.overview,
                        tmdb.still, tmdb.runtime, tmdb.airDate, ShowGuide.Match.SURE, null, tmdb.runtime, rating = tmdb.rating,
                    )
                }
                if (at != null && at != (e.season to e.episode)) {
                    val other = own[at]?.takeIf { sameShow }
                    return@map ShowGuide.Ep(
                        id, "", e.season, e.episode, "", at.first, at.second, other?.let { episodeName(it) }.orEmpty(),
                        other?.overview.orEmpty(), other?.still, other?.runtimeMin ?: 0, other?.let { airDate(it) }.orEmpty(),
                        ShowGuide.Match.SURE, null, other?.runtimeMin ?: 0,
                    )
                }
                ShowGuide.Ep(
                    id = episodeId(e.season, e.episode),
                    extension = "",
                    providerSeason = e.season,
                    providerNumber = e.episode,
                    providerTitle = "",
                    season = e.season,
                    number = e.episode,
                    name = episodeName(e),
                    overview = e.overview.orEmpty(),
                    image = e.still,
                    runtimeMin = e.runtimeMin ?: 0,
                    airDate = airDate(e),
                    match = ShowGuide.Match.SURE,
                    why = null,
                    tmdbMin = e.runtimeMin ?: 0,
                )
            }
        if (episodes.isEmpty()) return null
        return ShowGuide(MatchFixes.vodKey(t.id), t.id, PORTAL, shownName(t), t.name, t.tmdbId ?: 0, episodes)
    }

    /** "2013-10-05", as TMDB writes an air date; "" when unknown. */
    private fun airDate(e: Episode): String =
        e.day?.let { String.format(Locale.ROOT, "%04d-%02d-%02d", it.year, it.month, it.day) }.orEmpty()

    /** The episode's own name: "" for Cinemeta's stand-ins ("Episode 5", "TBA"), which say nothing the numbers don't. */
    fun episodeName(e: Episode): String {
        val name = e.name.trim()
        return if (name.equals("TBA", true) || STAND_IN.matches(name)) "" else name
    }

    /**
     * What Skip intro's times are asked for and kept by, for [request]: the title's id with the season and episode, or
     * "film". Made from the ids handed over alone, so it's the same before and after the show's details arrive, and
     * never another episode's.
     */
    fun skipKey(request: PlayRequest): String {
        val ep = request.target.episode ?: return "${request.target.title.id}/film"
        return "${request.target.title.id}/${ep.season}/${ep.episode}"
    }

    /**
     * The length in minutes that Skip intro's times are weighed against ([app.fireplayer.player.SkipRules.runtimeAgrees]):
     * for an episode its own, where the show's [details] list it, else the show's usual one; for a film its own. 0 when
     * unknown (IntroDB's times then go by their other evidence).
     */
    fun runtimeMinutes(request: PlayRequest, details: TitleDetails?): Int {
        val ep = request.target.episode
        val usual = TitleDetails.runtimeMinutes(request.target.title.runtime) ?: 0
        if (ep == null) return details?.runtimeMin?.takeIf { it > 0 } ?: usual
        val own = details?.episodes?.firstOrNull { it.season == ep.season && it.episode == ep.episode }?.runtimeMin
        return own?.takeIf { it > 0 } ?: usual
    }

    /** The episode of [guide] that [request] plays; null for a film or an episode the guide doesn't list. */
    fun episode(guide: ShowGuide?, request: PlayRequest): ShowGuide.Ep? = guide?.episode(episodeId(request))

    /**
     * Whether [episode] is the show's very last: Cinemeta says the show has ended, and lists no regular episode after
     * it (aired or not). The player's end card then offers Exit instead of Next episode, as for a film.
     */
    fun isFinale(details: TitleDetails, episode: EpisodeRef): Boolean {
        if (episode.season <= 0 || details.status?.equals("Ended", ignoreCase = true) != true) return false
        return details.episodes.none { it.season > episode.season || (it.season == episode.season && it.episode > episode.episode) }
    }

    /** The reference a guide episode plays by (season, number and name), for the next play's request. */
    fun refOf(ep: ShowGuide.Ep): EpisodeRef {
        // One Fix match set to another episode still plays (and is kept) by the source's own numbers.
        val own = ep.season == ep.providerSeason && ep.number == ep.providerNumber
        return EpisodeRef(ep.providerSeason, ep.providerNumber, ep.name.takeIf { own && it.isNotEmpty() })
    }

    /** How far an episode was watched, 0 to 1, for its card in the Episodes sheet: 1 once watched to the end. */
    fun watchedShare(position: Position?): Float = when {
        position == null -> 0f
        position.finished -> 1f
        else -> position.fraction ?: 0f
    }

    /**
     * Film details / Show details from what the title page loaded, in the shape the player's window draws
     * ([Tmdb.About]): no second request, and it works without a TMDB key (the facts are then Cinemeta's).
     */
    fun about(details: TitleDetails): Tmdb.About {
        val t = details.title
        val show = t.kind == Kind.SERIES
        return Tmdb.About(
            name = t.name,
            year = t.year?.take(4),
            overview = details.story?.value ?: t.description.orEmpty(),
            tagline = "",
            poster = t.poster,
            genres = t.genres,
            rating = t.imdbRating?.toDoubleOrNull() ?: 0.0,
            runtime = if (show) 0 else details.runtimeMin ?: TitleDetails.runtimeMinutes(t.runtime) ?: 0,
            seasons = if (show) details.seasons.count { it.number > 0 } else 0,
            ageRating = details.ageRating.orEmpty(),
            ratingFrom = "IMDb",
            backdrop = t.background,
        )
    }

    private val STAND_IN = Regex("(?i)^episode\\s+\\d+$")
}
