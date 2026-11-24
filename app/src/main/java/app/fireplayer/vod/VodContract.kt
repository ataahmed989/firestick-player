package app.fireplayer.vod

/*
 * The contract between Films & Shows' data (where titles come from, what's in My List) and its pages (Home, Movies,
 * TV Shows). Decisions behind it: design-brief.md §6. Titles come from Cinemeta (rows, details) and TMDB (Top 10);
 * playback sources are a later step and are not part of this file.
 *
 * Step 2 adds the title page (design-brief.md §6.4, layout A): a title's details, a show's seasons and episodes, More
 * Like This, per-episode watched marks, where playback stopped and what Play starts. The player writes where playback
 * stopped in step 3 ([VodRepository.recordProgress]); until then nothing has a position.
 *
 * Threading: every callback here arrives on the main thread. Work happens on background threads owned by the
 * repository; nothing here blocks.
 */

/** Film or series. */
enum class Kind { MOVIE, SERIES }

/**
 * A film or series as FirePlayer shows it. One per IMDb id ("tt…"), whichever row it appears in. [id] and [kind]
 * together identify it: Cinemeta lists a few ids under both kinds, and the two are never merged.
 *
 * The one exception is a More Like This card from TMDB whose IMDb id isn't known yet: its [id] is a stand-in
 * ([standInId]) until [VodRepository.resolve] finds it ([hasImdbId]).
 */
data class Title(
    val id: String,
    val kind: Kind,
    val name: String,
    /** "2024", or "2012–2014" for a series. */
    val year: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    /** IMDb rating as Cinemeta gives it, e.g. "7.5". */
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    /** "2h 6m" or "23 min", as given. */
    val runtime: String? = null,
    val tmdbId: Int? = null,
)

/** Which episode a card is about (Continue Watching, Next Episodes). */
data class EpisodeRef(val season: Int, val episode: Int, val name: String? = null)

/** One card in a row: the title plus what that row adds to it. */
data class Card(
    val title: Title,
    /** 0..1 for Continue Watching and episode cards; null elsewhere. */
    val progress: Float? = null,
    /** The line under the name on wide cards, e.g. "12m left" or "S2 E5 · 12m left". */
    val caption: String? = null,
    /** 1..10 in Top 10 rows. */
    val rank: Int? = null,
    val episode: EpisodeRef? = null,
    /** A badge such as "NEW" (Next Episodes, episode cards). */
    val badge: String? = null,
    /** The card's own picture when it isn't the title's: an episode still ([RowStyle.EPISODE]). */
    val picture: String? = null,
    /** Draws the ✓ watched mark ([RowStyle.EPISODE]). */
    val watched: Boolean = false,
    /** False: drawn dimmed, and OK only says why (an episode not aired yet: "Airs Dec 3"). */
    val playable: Boolean = true,
)

enum class Page { HOME, MOVIES, SHOWS }

/** How a row draws its cards. Sizes are in design-brief.md §6.3. */
enum class RowStyle {
    /** 3:4 posters, 6.5 across. */
    POSTER,
    /** 16:9 cards with name, caption and progress inside (Continue Watching), 4.5 across. */
    WIDE,
    /** Posters with Glass Home's big rank number beside each (Top 10). */
    RANKED,
    /** A row of genre chips; OK opens that genre's "See all". */
    GENRES,
    /** A show's episodes on the title page: 16:9 stills at the WIDE size, two caption lines under each. */
    EPISODE,
    /** A show's season pills on the title page: chips drawn like GENRES, the season shown selected. */
    SEASONS,
    /** Five rounded cards across with a wordmark each and no title line, in fixed slots: Disney+'s studios. */
    LOGO,
    /**
     * Home's streaming services: the [LOGO] cards, a card per service across the content's width in fixed slots
     * ([app.fireplayer.vod.ui.VodSizes.SERVICES_ACROSS]); Disney+'s studios keep five.
     */
    SERVICES,
}

/** Where a row's cards come from. */
sealed class Source {
    /**
     * A Cinemeta catalog: "top" (Popular), "year" (New in a year; [genre] holds the year), "imdbRating" (Featured).
     * On "top" and "imdbRating" [genre] filters by genre (a genre chip's See all is "top" with that genre).
     */
    data class Catalog(val kind: Kind, val catalog: String, val genre: String? = null) : Source()

    /**
     * TMDB's weekly trending list, first 10, mapped to IMDb ids. Needs the user's TMDB key; empty without it. [kind]
     * null: films and series in turn (ranked 1 to 10 together), a row added in Settings › Rows.
     */
    data class Trending(val kind: Kind?) : Source()

    /** FirePlayer's Continue Watching: films, series, or both ([kind] null). Empty until playback records some. */
    data class ContinueWatching(val kind: Kind?) : Source()

    /** FirePlayer's My List (a saved-title list, independent of Real-Debrid). */
    data object MyList : Source()

    /** My List's films or its series only ([kind]), the latest added first: the My List tab's two rows. */
    data class MyListOf(val kind: Kind) : Source()

    /**
     * Cinemeta's search for [query] among [kind] (the Search tab), in Cinemeta's order; empty for a blank query. Answered
     * once, fresh (kept in memory a few minutes, never on disk).
     */
    data class Search(val kind: Kind, val query: String) : Source()

    /** The next unwatched episode of series the user is following. Empty until playback records some. */
    data class NextEpisodes(val kind: Kind = Kind.SERIES) : Source()

    /** Cinemeta's genre names for [kind] (chips). */
    data class Genres(val kind: Kind) : Source()

    /**
     * Cards the page puts in the row itself: the title page's season pills, episodes and More Like This, from
     * [VodRepository.details] and [VodRepository.moreLikeThis]. [VodRepository.load] answers it with one empty fresh
     * list. [key] tells such rows apart.
     */
    data class Fixed(val key: String) : Source()

    /**
     * TMDB Discover: what TMDB's watch provider [provider] carries with a US subscription, the most popular first;
     * [kind] null: films and series in turn. [companies] (and, for series, [networks] in their place) narrow it to a
     * brand's titles; [genres] and [withoutGenres] (TMDB's genre ids) and [maxRuntime] (minutes: shorts) narrow it
     * further. A row takes the first page only; [full] (a brand's collection) goes on page after page, and so does
     * [paged] (a studio page's row, which takes more as the viewer nears its end and whose titles are whole, as a
     * row's are). [alsoProvider]: a second provider whose titles count too (TMDB's either-or: Prime Video's with-ads
     * tier). Needs the user's TMDB key; empty without it.
     */
    data class Discover(
        val kind: Kind?,
        val provider: Int,
        val companies: String? = null,
        val networks: String? = null,
        val full: Boolean = false,
        val genres: String? = null,
        val withoutGenres: String? = null,
        val maxRuntime: Int? = null,
        val paged: Boolean = false,
        val alsoProvider: Int? = null,
    ) : Source()

    /**
     * Chosen titles in a set order (a saga's episodes, a franchise in release order): those of [titles] that TMDB's
     * watch provider [provider] carries with a US subscription, in that order. Needs the user's TMDB key; empty
     * without it.
     */
    data class Curated(val kind: Kind, val provider: Int, val titles: List<CuratedTitle>) : Source()

    /** The streaming services with a page of their own: a card each (Home's row). */
    data object Services : Source()

    /** Disney+'s brands: a card each, opening that brand's collection. */
    data object Brands : Source()
}

/**
 * One chosen title of a [Source.Curated] list: its TMDB id, with words of its name and the year it came out, which the
 * answer must agree with (an id that turned out to be another title's is left out, never shown as the wrong film).
 */
data class CuratedTitle(val tmdbId: Int, val name: String, val year: Int)

/**
 * One row on a page.
 * [key] is stable (settings, focus memory); [continueRow] marks the row whose focused title the header shows and
 * whose cards go straight to playback.
 */
data class RowSpec(
    val key: String,
    val label: String,
    val style: RowStyle,
    val source: Source,
    val continueRow: Boolean = false,
)

/** Stops a load: its callback won't be called after this. */
fun interface Cancel {
    fun cancel()
}

/** What the page shows while idle in its header: rotated through, about every 8 s. */
data class HeaderFeed(val titles: List<Title>)

// ---- The title page (step 2) ----

/** Where a fact on the title page came from. Details says so ("from Cinemeta", "from TMDB"). */
enum class From { CINEMETA, TMDB }

/** A fact and where it came from. */
data class Sourced<out T>(val value: T, val from: From)

/**
 * A calendar day as printed ("Oct 5, 2013", "Airs Dec 3", "Coming Mar 6"); [month] is 1..12. An episode's is the day it
 * first aired in the US (Cinemeta's time read in America/Los_Angeles: the TV's own zone would put many a day late or
 * early); a film's is Cinemeta's release date as written (read in UTC).
 */
data class Day(val year: Int, val month: Int, val day: Int)

/**
 * One episode of a series, from Cinemeta's meta (`videos[]`; never from a catalog preview, which holds only the latest
 * 20 or so). Season 0 holds the specials. Episode numbers can have gaps (specials often do).
 */
data class Episode(
    /** "tt1695360:2:5" (show, season, episode), as Cinemeta writes it: the key of watched marks and positions. */
    val id: String,
    val season: Int,
    val episode: Int,
    /** As Cinemeta names it ("TBA" for many not aired yet); "Episode 5" when it has no name. */
    val name: String,
    /** When it airs or aired (epoch ms); null when unknown, which counts as not aired. */
    val released: Long? = null,
    /** [released]'s day, for "Oct 5, 2013" and "Airs Dec 3"; null when unknown. */
    val day: Day? = null,
    /** Its own summary; null when Cinemeta has none (the header then keeps the show's story). */
    val overview: String? = null,
    /**
     * A 16:9 still (metahub `w500`, which redirects to TMDB's picture); null when none. Some specials and most
     * episodes not aired yet have none (the address answers 404): the card draws its placeholder.
     */
    val still: String? = null,
    /** Its length in minutes, for "23m": the show's usual length (Cinemeta has none per episode); null for specials. */
    val runtimeMin: Int? = null,
) {
    /** The show's IMDb id. */
    val showId: String get() = id.substringBefore(':')

    val isSpecial: Boolean get() = season == 0

    val ref: EpisodeRef get() = EpisodeRef(season, episode, name)

    /** Aired by [now], and so playable. One not aired is dimmed, says "Airs Dec 3" and doesn't play. */
    fun aired(now: Long): Boolean = released != null && released <= now

    /** Aired within the last 7 days ([NEW_MS]): the card's "NEW" chip. */
    fun isNew(now: Long): Boolean = released != null && released <= now && now - released < NEW_MS

    companion object {
        const val NEW_MS = 7 * 24 * 3600_000L

        /** "tt1695360:2:5". */
        fun idOf(showId: String, season: Int, episode: Int) = "$showId:$season:$episode"
    }
}

/**
 * A season pill and its episodes in order. [number] 0 is Specials, always the last season. (The page hides the pills
 * when a show has a single season; a regular season plus Specials makes two.)
 */
data class Season(val number: Int, val episodes: List<Episode>) {
    val isSpecials: Boolean get() = number == 0
}

/**
 * Everything the title page shows about a title: the header (facts line, callout, story, cast line), the buttons, the
 * episodes and the Details window. Cinemeta's meta gives most of it; with a usable TMDB key one TMDB call adds the age
 * rating, TMDB's story, the full cast and crew (and More Like This, [VodRepository.moreLikeThis]). Facts that either
 * could give say which did ([Sourced]). Missing facts are null or empty, never made up.
 */
data class TitleDetails(
    /**
     * The title completed from the meta: name, year ("2024", "2012–2014"), poster, logo, Cinemeta's rating, genres (at
     * most 3), runtime ("167 min") and TMDB id. [Title.background] is the backdrop to decode: TMDB's `w1280` when TMDB's
     * details were already stored when the page asked, else metahub's; it stays the same in every call of one
     * [VodRepository.details], so the backdrop never changes mid-visit.
     */
    val title: Title,
    /** A film's release (a show's first episode), epoch ms; null when unknown. */
    val releasedAt: Long? = null,
    /** [releasedAt]'s day, for "Coming Mar 6". */
    val released: Day? = null,
    /** A film's length or a show's usual episode length, in minutes ("2h 47m"): Cinemeta's, else TMDB's. */
    val runtimeMin: Int? = null,
    /** A show's state as Cinemeta words it, "Ended" or "Continuing" (the facts line shows only "Ended"); null for films. */
    val status: String? = null,
    /** The US age rating ("PG-13", "TV-Y7"), from TMDB only: null without a key, or when TMDB has none (or "NR"). */
    val ageRating: String? = null,
    /**
     * The whole story: TMDB's overview when it has one, else Cinemeta's description (one Cinemeta cut off ends at its
     * last whole word). The header shows 3 lines, Details all of it.
     */
    val story: Sourced<String>? = null,
    /** Cast names in billing order: TMDB's (about 15) or Cinemeta's (3). The cast line uses the first 3, Details all. */
    val cast: Sourced<List<String>>? = null,
    /** A film's directors ("· Director D" on the cast line). */
    val directors: Sourced<List<String>>? = null,
    /** A film's writers (Details). */
    val writers: Sourced<List<String>>? = null,
    /** A show's creators ("· Created by …"): TMDB's `created_by`, else Cinemeta's `writer`. */
    val creators: Sourced<List<String>>? = null,
    /** Cinemeta's awards line as written ("Won 2 Oscars. 126 wins & 375 nominations total"), for Details. */
    val awards: String? = null,
    /**
     * The Oscar callout's words when [awards] starts with them: "Won 2 Oscars", "Nominated for 1 Oscar". Null
     * otherwise: Emmys and BAFTAs never make a callout.
     */
    val oscars: String? = null,
    /** Cinemeta's countries as written ("United States, Canada"), for Details. */
    val country: String? = null,
    /**
     * Its place in this week's Top 10 (1..10) for "#4 in Top 10 this week"; null without a key or outside it. Looked up
     * each time the details are passed on, never stored with them.
     */
    val top10Rank: Int? = null,
    /**
     * A show's seasons: the regular ones in order, then Specials (season 0) when there are any; each one's episodes in
     * order, each once. Empty for a film, and for a show until its meta has been read.
     */
    val seasons: List<Season> = emptyList(),
) {
    /** Every episode, in [seasons]' order (Specials last). */
    val episodes: List<Episode> by lazy { seasons.flatMap { it.episodes } }

    /** "4 seasons": the regular seasons (Specials don't count). */
    val seasonCount: Int get() = seasons.count { it.number > 0 }

    /**
     * Whether a film is out at [now] ([thisYear] is now's year): its release has passed, or, with no date, its year is
     * not after this one (step 3's sources tell the rest). One not out has Play read "Coming Mar 6" ([released]) or
     * "Coming 2027", dimmed and skipped by focus.
     */
    fun isOut(now: Long, thisYear: Int): Boolean {
        releasedAt?.let { return it <= now }
        val year = title.year?.take(4)?.toIntOrNull() ?: return true
        return year <= thisYear
    }

    companion object {
        /**
         * What a row's [title] already says, for the page to draw before the details come (it is never blank): its
         * description counts as Cinemeta's story, its runtime is read into minutes; no episodes.
         */
        fun of(title: Title) = TitleDetails(
            title = title,
            runtimeMin = runtimeMinutes(title.runtime),
            story = title.description?.takeIf { it.isNotBlank() }?.let { Sourced(it, From.CINEMETA) },
        )

        /** Minutes in a runtime as Cinemeta writes it ("167 min", "23 min", "2h 6m", "1h"); null when it has none. */
        fun runtimeMinutes(runtime: String?): Int? {
            if (runtime.isNullOrBlank()) return null
            val h = HOURS.find(runtime)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val m = MINUTES.find(runtime)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return (h * 60 + m).takeIf { it > 0 }
        }

        private val HOURS = Regex("""(\d+)\s*h""")
        private val MINUTES = Regex("""(\d+)\s*m""")
    }
}

/**
 * Where playback of a film or an episode stopped, [at] epoch ms. Written by the player ([VodRepository.recordProgress],
 * step 3). FirePlayer 1.0's rules: a position past 30 s and not in the last 3 minutes resumes ([resumable]); the last 3
 * minutes count as the end ([finished]).
 */
data class Position(val positionMs: Long, val durationMs: Long, val at: Long) {
    /** How far along, 0..1 (the progress bars); null when the length isn't known. */
    val fraction: Float? get() = if (durationMs <= 0) null else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** What's left, for "42m left"; null when the length isn't known. */
    val leftMs: Long? get() = if (durationMs <= 0) null else (durationMs - positionMs).coerceAtLeast(0)

    /** In the last 3 minutes: watched to the end. */
    val finished: Boolean get() = durationMs > 0 && durationMs - positionMs <= END_MS

    /** Resume and Play from beginning are offered: past 30 s and not [finished]. */
    val resumable: Boolean get() = positionMs > START_MS && !finished

    companion object {
        const val START_MS = 30_000L
        const val END_MS = 3 * 60_000L
    }
}

/**
 * A title's Continue Watching record: where its playback last stopped and, for a series, in which [episode] (null for a
 * film). It stays when the title is taken out of Continue Watching ([hidden]).
 */
data class LastPlayed(
    val episode: EpisodeRef?,
    val position: Position,
    /** Taken out of Continue Watching; the resume point stays. */
    val hidden: Boolean = false,
    /** The film or [episode] was marked watched since [position] was saved: it counts as finished, wherever it stopped. */
    val markedWatched: Boolean = false,
) {
    val finished: Boolean get() = markedWatched || position.finished

    /** Resume (and Play from beginning) are offered: a film's "Resume · 42m left", a show's [PlayAction.RESUME]. */
    val resumable: Boolean get() = !finished && position.resumable
}

/** What Play does on a show's page ([PlayTarget.action]). */
enum class PlayAction {
    /** "Play S1 E1", "Play S2 E6": from the start (a stop under 30 s doesn't resume). */
    PLAY,
    /** "Resume S2 E5 · 12m left": from [PlayTarget.position]; Play from beginning restarts this episode. */
    RESUME,
    /** "Watch again · S1 E1": the last episode watched to the end has nothing aired after it. */
    WATCH_AGAIN,
}

/** The episode Play starts on a show's page and how its button reads ([playTarget]). */
data class PlayTarget(
    val episode: Episode,
    val action: PlayAction,
    /** Where it stopped, for [PlayAction.RESUME] only (the pill's progress line, "12m left"). */
    val position: Position? = null,
    /** Caught up and the next regular episode hasn't aired: "Up to date · S5 E1 airs Dec 3". */
    val upcoming: Episode? = null,
    /** The episode to play aired after the show was last watched: "New episodes". */
    val newEpisodes: Boolean = false,
)

/**
 * The one chip beside the facts line, at most one, chosen in this order: [Top10]; then [Watched] or [WatchedAll],
 * [NewEpisodes], [UpToDate]; then [Oscars]. The words are the page's; these are what they say.
 */
sealed class Callout {
    /** "#4 in Top 10 this week" ([TitleDetails.top10Rank]). */
    data class Top10(val rank: Int) : Callout()

    /** "✓ Watched": a film marked watched. */
    data object Watched : Callout()

    /** "✓ Watched all": every aired regular episode of a show watched ([VodRepository.isEpisodeWatched]). */
    data object WatchedAll : Callout()

    /** "New episodes" ([PlayTarget.newEpisodes]). */
    data object NewEpisodes : Callout()

    /** "Up to date · S5 E1 airs Dec 3" ([PlayTarget.upcoming]). */
    data class UpToDate(val next: Episode) : Callout()

    /** "Won 2 Oscars", "Nominated for 1 Oscar" ([TitleDetails.oscars]). */
    data class Oscars(val text: String) : Callout()
}

/** Whether [Title.id] is the IMDb id; false for a More Like This stand-in ([standInId]) not yet resolved. */
val Title.hasImdbId: Boolean get() = id.startsWith("tt")

/**
 * The id a More Like This card from TMDB carries until [VodRepository.resolve] finds its IMDb id: "tmdb:movie:693134",
 * "tmdb:series:33880". Unique and stable, so rows keep focus by id as usual. Never given to the library.
 */
fun standInId(kind: Kind, tmdbId: Int): String = "tmdb:${if (kind == Kind.MOVIE) "movie" else "series"}:$tmdbId"

/**
 * What Play starts on a show's page (pure). [episodes] are the show's (any order; Specials are never a target), [last]
 * its Continue Watching record ([VodRepository.lastPlayed]), [now] epoch ms, [watched] whether an episode counts as
 * watched ([VodRepository.isEpisodeWatched]).
 * - No record, or an unfinished one in Specials or for an episode no longer listed: the first aired episode of the
 *   lowest regular season ([PlayAction.PLAY], "Play S1 E1").
 * - The record's episode not finished: it again, resumed when [Position.resumable] ("Resume S2 E5 · 12m left"), else
 *   from the start ("Play S3 E2").
 * - Finished ([LastPlayed.finished]): the episode after it by [UpNext.after], the one Continue Watching goes on with:
 *   the next aired regular episode not watched, across seasons ("Play S2 E1"; [PlayTarget.newEpisodes] when it aired
 *   after the record was saved), passing over any not aired (a future or missing date) on the way. Every one aired
 *   after it watched (a show watched again): the next aired one, in order (not after a special). None aired after it:
 *   caught up, so the first aired episode ([PlayAction.WATCH_AGAIN]) with [PlayTarget.upcoming] the next one listed
 *   that hasn't aired, if any.
 * Null when no regular episode has aired yet.
 */
fun playTarget(episodes: List<Episode>, last: LastPlayed?, now: Long, watched: (Episode) -> Boolean = { false }): PlayTarget? {
    val regular = UpNext.regular(episodes)
    val first = regular.firstOrNull { it.aired(now) } ?: return null
    val ref = last?.episode
    if (last == null || ref == null) return PlayTarget(first, PlayAction.PLAY)
    if (!last.finished) {
        val at = regular.firstOrNull { it.season == ref.season && it.episode == ref.episode }
        return when {
            at == null -> PlayTarget(first, PlayAction.PLAY)
            last.resumable -> PlayTarget(at, PlayAction.RESUME, position = last.position)
            else -> PlayTarget(at, PlayAction.PLAY)
        }
    }
    // Every one aired after it watched: on in order (a show watched again), except after a special.
    val later = UpNext.later(episodes, ref, now)
    val next = later.firstOrNull { !watched(it) } ?: later.firstOrNull()?.takeIf { ref.season > 0 }
        ?: return PlayTarget(first, PlayAction.WATCH_AGAIN, upcoming = regular.firstOrNull { UpNext.isAfter(it, ref) && !it.aired(now) })
    return PlayTarget(next, PlayAction.PLAY, newEpisodes = (next.released ?: 0L) > last.position.at)
}

interface VodRepository {
    /**
     * The rows of [page] in the viewer's order (Settings › Rows; the defaults until they change it), only the ones
     * switched on. Rows whose source turns out empty are hidden by the page.
     */
    fun rows(page: Page): List<RowSpec>

    /** Called on the main thread when Settings › Rows changes a page's rows: each page asks [rows] again. */
    fun addRowsListener(listener: () -> Unit) {}
    fun removeRowsListener(listener: () -> Unit) {}

    /**
     * Home's streaming services put in [order] (a card moved on Home): kept as Settings › Rows › Reorder services keeps
     * it, so both show the same order, across restarts.
     */
    fun setServiceOrder(order: List<app.fireplayer.vod.data.Service>) {}

    /**
     * Cards for [row], up to [limit] starting at [skip]. [onCards] is called first with whatever is cached (fresh =
     * false, possibly empty) and then once more with fresh data (fresh = true) if the cache was old or missing. On a
     * network failure with nothing cached it is called once with an empty list and fresh = true.
     *
     * The last call always has fresh = true: a cache that is still current is passed once, with fresh = true, and with
     * nothing cached there is no first call. For a Catalog, [skip] is a position in Cinemeta's list, which comes in
     * pages of 50 that often hold a few less: step it by the limit asked for (50 in See all), not by the number of
     * cards received, and drop repeats by id. An empty fresh list means the end.
     */
    fun load(row: RowSpec, skip: Int = 0, limit: Int = 15, onCards: (cards: List<Card>, fresh: Boolean) -> Unit): Cancel

    /** Genre names for a GENRES row: the stored (or built-in) list at once, and again if Cinemeta's has changed. */
    fun genres(kind: Kind, onGenres: (List<String>) -> Unit): Cancel

    /**
     * The header's idle rotation for [page]: the page's Top 10 (Home: films and series mixed), or Cinemeta Popular's
     * first 10 without a TMDB key. Called again when a fresher feed arrives (the stored one comes first).
     */
    fun header(page: Page, onFeed: (HeaderFeed) -> Unit): Cancel

    /**
     * [title] as a banner shows it, its facts completed from Cinemeta ([app.fireplayer.vod.data.TitleFacts]) when it
     * lacks them: one made from TMDB's lists (a service's rows, More Like This, My List entries added from them) has
     * only its year and TMDB's story. [onTitle] is called once, on the main thread, only when something was added:
     * from what's known here at once, else after one Cinemeta call (kept a day; a stand-in's IMDb id from TMDB first).
     * Never for a title that has its facts.
     */
    fun headerTitle(title: Title, onTitle: (Title) -> Unit): Cancel = Cancel {}

    /** Whether a TMDB key is set (Top 10, age ratings, cast photos, More Like This); a key TMDB refused counts as none. */
    fun hasTmdbKey(): Boolean

    // ---- The user's library (stored on this TV only; no syncing) ----

    fun inMyList(id: String): Boolean
    fun setInMyList(title: Title, inList: Boolean)
    fun isWatched(id: String): Boolean

    /**
     * Mark watched (a film leaves Continue Watching; a show's mark covers the episodes aired by then), or Mark
     * unwatched: the film's or the whole show's watched marks, its history and where playback of it (each episode)
     * stopped are cleared. In Continue Watching it stays there, in its place, at 0:00 of the film or of the show's first
     * episode; one not in it isn't put in it. Either way in memory at once (ask again straight after), one write off
     * the main thread, one listener call; nothing to change: neither.
     */
    fun setWatched(title: Title, watched: Boolean)

    /**
     * Whether [title] has a history Mark unwatched ([setWatched]) clears: a watched mark (a show's, or any of its
     * episodes'), or where playback of it stopped (a show's: of any episode). Main thread, from memory.
     */
    fun hasHistory(title: Title): Boolean = isWatched(title.id) || (lastPlayed(title)?.position?.positionMs ?: 0L) > 0L

    /** Takes a title out of Continue Watching (its resume point stays). */
    fun removeFromContinueWatching(id: String)

    /** Called on the main thread whenever My List, watched marks or Continue Watching change, so rows can refresh. */
    fun addLibraryListener(listener: () -> Unit)
    fun removeLibraryListener(listener: () -> Unit)

    /**
     * Goes up with every library change, at once (before its listener call, which is posted): a page that redrew
     * itself straight after its own change needn't again when that call comes.
     */
    fun libraryVersion(): Long

    /**
     * [title]'s Continue Watching card as its row would load it now; null when it isn't in Continue Watching. A row
     * changes that one card in place with it (Mark unwatched: back to 0:00), with no reload. Main thread, from memory.
     */
    fun continueCard(title: Title): Card?

    // ---- The title page (step 2) ----
    // Same conventions as [load]: callbacks on the main thread, none after Cancel; stored answers at once, fresh ones
    // fetched in the background. Network on the repository's threads; no address holding the TMDB key is ever logged.

    /**
     * The details of [title] (it must carry its IMDb id: resolve a More Like This stand-in first). [onDetails] gets the
     * stored details at once (fresh = false) when there are any, then again each time a part arrives: Cinemeta's meta
     * (kept about 4 h), then, with a usable TMDB key, TMDB's details (one call with the age rating, story, credits and
     * More Like This appended; kept about 24 h, never past TMDB's 6-month limit). The last call has fresh = true;
     * details still current are passed once, with fresh = true.
     *
     * Each call carries everything known so far, never less than the call before: a part that can't be fetched keeps
     * its stored copy, and with nothing stored and Cinemeta failing, the one call is [TitleDetails.of] ([title] alone,
     * fresh = true). [TitleDetails.title]'s background is the same in every call.
     */
    fun details(title: Title, onDetails: (details: TitleDetails, fresh: Boolean) -> Unit): Cancel

    /**
     * The language [title] was made in (ISO 639-1, "en", "ko"), from TMDB's details already stored on this TV (never
     * fetched here: any thread, quick); null when none are stored or they don't say. Sources rank its sound first.
     */
    fun originalLanguage(title: Title): String? = null

    /**
     * Up to 15 POSTER cards like [title], never [title] itself, one per IMDb id. With a usable key: TMDB's
     * recommendations, topped up from its similar titles (those without a poster or with very few votes left out),
     * from the same TMDB call as [details]. Without a key, or when TMDB fails: Cinemeta's, for a film its director's
     * films, then for both the main genre's Popular ranked by genres shared.
     *
     * A TMDB card whose IMDb id isn't known here (Cinemeta's ids, earlier answers) carries a stand-in id ([standInId])
     * and its [Title.tmdbId], kind, name, year, poster and background: [resolve] it before opening it or holding OK
     * on it. [onCards] as in [load]: stored first (fresh = false), then fresh; the last call has fresh = true; with
     * nothing stored and nothing found, one empty list (fresh = true).
     */
    fun moreLikeThis(title: Title, onCards: (cards: List<Card>, fresh: Boolean) -> Unit): Cancel

    /**
     * The title behind a More Like This card, with its IMDb id. At once when [title] already has one ([hasImdbId]) or
     * it's known here; otherwise from that title's TMDB details, the call its page makes anyway (no extra request).
     * [onTitle] is called once: null when TMDB has no IMDb id for it or can't be reached (the page says it couldn't
     * open it).
     */
    fun resolve(title: Title, onTitle: (Title?) -> Unit): Cancel

    // ---- Library: episodes, Continue Watching, where playback stopped (main thread, from memory) ----

    /**
     * Whether [episode] counts as watched: the newest of its own mark and the whole show's mark ([setWatched] on the
     * show) decides; the show's mark covers only episodes that had aired when it was set (newer ones are new).
     */
    fun isEpisodeWatched(episode: Episode): Boolean

    /**
     * Marks [episodes] (of one show) watched, or not watched: their marks and where playback of each stopped are
     * cleared, and the show, if in Continue Watching, stays there at 0:00 of the last of [episodes] (the one the action
     * was used on); one not in it isn't put in it. Other episodes are left as they are. One write, one listener call.
     */
    fun setEpisodesWatched(episodes: List<Episode>, watched: Boolean)

    /**
     * "Mark watched up to here": [upTo] and every aired episode before it, in one write and one listener call. For a
     * regular episode that is every aired regular episode up to it, across seasons, never Specials; for a special, the
     * aired specials up to it. Nothing for an episode not aired yet.
     */
    fun markWatchedUpTo(details: TitleDetails, upTo: Episode)

    /**
     * "Mark unwatched up to here": the same episodes as [markWatchedUpTo] marked not watched as [setEpisodesWatched]
     * does (their history and positions cleared; Continue Watching, if the show is in it, at 0:00 of [upTo]), in one
     * write and one listener call. Episodes after [upTo] are left as they are. Nothing for an episode not aired yet.
     */
    fun markUnwatchedUpTo(details: TitleDetails, upTo: Episode)

    /**
     * Whether [id] is in Continue Watching now: played, not taken out and not marked watched since. The (⋯) window
     * offers "Remove from Continue Watching" only then.
     */
    fun inContinueWatching(id: String): Boolean

    /**
     * [title]'s Continue Watching record (a film: where it stopped; a series: its latest episode and where that
     * stopped), also after it was taken out of Continue Watching; null when never played. The film's Resume and a
     * show's [playTarget] read it. Null for everything until step 3 records playback.
     */
    fun lastPlayed(title: Title): LastPlayed?

    /**
     * The episode [title]'s show goes on with, as its Continue card plays it (the banner's Watch now): the record's own
     * until it's over, then the next one ([UpNext]); null for a film, a show never played or one with nothing left to
     * watch (its title page then knows what Play does).
     */
    fun upNext(title: Title): EpisodeRef? = if (title.kind == Kind.SERIES) lastPlayed(title)?.episode else null

    /** Where playback of [episode] stopped (its card's progress bar, "12m left"); null when never played. */
    fun positionOf(episode: Episode): Position?

    /**
     * Where playback stopped, from the player (step 3; nothing calls it in step 2). Any thread. [title] goes to the
     * front of Continue Watching (back in it if it was taken out) with a new [LastPlayed]; for a series [episode]'s own
     * [positionOf] is kept too. A [Position.finished] position also marks the film or the episode watched, so the ✓
     * marks and Play agree. Listeners are called.
     */
    fun recordProgress(title: Title, episode: EpisodeRef?, positionMs: Long, durationMs: Long)
}
