package app.fireplayer.history

import app.fireplayer.vod.Episode
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Kind
import app.fireplayer.vod.Title
import app.fireplayer.vod.data.Progress
import java.util.Locale

/**
 * Films & Shows' Continue Watching (the VOD library, vod.db) as rows of Glass Home's Continue Watching
 * ([ContinueProvider]), beside the rows of videos other apps opened ([ContinueWatching]). Pure, for tests.
 *
 * A library row's id is "vod-m-ID" (a film) or "vod-s-ID" (a series), ID being the title's id (IMDb's "tt…") with any
 * character outside letters, digits, '.', ':' and '-' written as "_" and its UTF-8 bytes in hex, so an id is always one
 * plain path segment. The other rows' ids are 16 hex digits, never starting "vod-". An episode of a library show is
 * named by "SEASON:EPISODE". The rows hold what the title is and how far along; never an add-on, a release or a link.
 */
object LibraryContinue {
    private const val PREFIX = "vod-"
    private const val FILM = "vod-m-"
    private const val SHOW = "vod-s-"

    fun isLibraryId(id: String?): Boolean = id != null && id.startsWith(PREFIX)

    fun idOf(kind: Kind, titleId: String): String = (if (kind == Kind.SERIES) SHOW else FILM) + escape(titleId)

    /** The kind and title id a library row's [id] names; null for any other id. */
    fun parse(id: String?): Pair<Kind, String>? {
        if (id == null) return null
        val kind = when {
            id.startsWith(FILM) -> Kind.MOVIE
            id.startsWith(SHOW) -> Kind.SERIES
            else -> return null
        }
        val titleId = unescape(id.substring(FILM.length)) ?: return null
        return if (titleId.isEmpty()) null else kind to titleId
    }

    /**
     * Glass Home's row for a Continue Watching entry: a film, or the series at its episode. Null for a series that names
     * no episode (Glass Home could only resume it on a title page, which it has none of).
     */
    fun row(p: Progress): WatchHistory.Row? {
        val title = p.title
        val ep = p.episode
        if (title.kind == Kind.SERIES && ep == null) return null
        if (title.name.isBlank() || title.id.isEmpty()) return null
        val show = title.kind == Kind.SERIES
        return WatchHistory.Row(
            id = idOf(title.kind, title.id),
            kind = if (show) WatchHistory.Kind.EPISODE else WatchHistory.Kind.MOVIE,
            title = title.name,
            year = title.year?.take(4)?.toIntOrNull() ?: 0,
            season = if (show) ep?.season ?: 0 else 0,
            episode = if (show) ep?.episode ?: 0 else 0,
            episodeTitle = if (show) ep?.name?.takeIf { it.isNotBlank() } else null,
            tmdbId = title.tmdbId ?: 0,
            positionMs = p.positionMs.coerceAtLeast(0),
            durationMs = p.durationMs.coerceAtLeast(0),
            lastWatched = p.at,
            source = null,
            // Its show's episodes come from the title's details (Cinemeta), as on its title page.
            hasEpisodes = show,
        )
    }

    /**
     * Both lists as one, the latest watched first (of two at the same time, the library's first); an id once. A copy
     * another app opened here ([others]) of a title the library has ([played]: every record, in Continue Watching or
     * not) is left out when that title is in the library's Continue Watching ([library]), whenever the copy was played:
     * FirePlayer's Home shows the library's card for it, at its episode and time left, so Glass Home's one card for the
     * title (one per title) is that one too. It's also left out when the library's record was played at or after it: the
     * title has been played here since (and maybe taken out, marked or watched to its end). A copy played after the
     * library's last play stays (watched again elsewhere), unless the title is taken out here: then it goes with it
     * ([copiesOf]).
     */
    fun merge(
        library: List<WatchHistory.Row>,
        others: List<WatchHistory.Row>,
        played: List<Progress> = emptyList(),
    ): List<WatchHistory.Row> {
        val kept = if (others.isEmpty() || (library.isEmpty() && played.isEmpty())) {
            others
        } else {
            val shown = library.map { Same.of(it) }
            val records = played.map { Same.of(it.title) to it.at }
            others.filter { o ->
                val t = Same.of(o)
                shown.none { it.same(t) } && records.none { (r, at) -> at >= o.lastWatched && r.same(t) }
            }
        }
        return (library + kept).distinctBy { it.id }.sortedByDescending { it.lastWatched }
    }

    /**
     * The ids of the rows of [others] that are copies of [titles] (taken out of Continue Watching here, or marked
     * watched): they go with them, as Glass Home's Remove takes every copy of a title.
     */
    fun copiesOf(titles: List<Title>, others: List<WatchHistory.Row>): List<String> {
        if (titles.isEmpty()) return emptyList()
        val gone = titles.map { Same.of(it) }
        return others.filter { o -> Same.of(o).let { t -> gone.any { it.same(t) } } }.map { it.id }
    }

    /**
     * A title as Glass Home tells copies apart (its titleKey): its kind, and its TMDB id, else its name (letters and
     * digits, lower case) and year. Two are one title when both have a TMDB id and it's the same, or, one without, when
     * their names and years are.
     */
    private class Same(val show: Boolean, val tmdbId: Int, val name: String, val year: Int) {
        fun same(o: Same): Boolean = show == o.show &&
            if (tmdbId > 0 && o.tmdbId > 0) tmdbId == o.tmdbId else name.isNotEmpty() && name == o.name && year == o.year

        companion object {
            private val NOT_NAME = Regex("[^\\p{L}\\p{N}]")

            fun of(r: WatchHistory.Row) = Same(r.kind == WatchHistory.Kind.EPISODE, r.tmdbId, nameOf(r.title), r.year)

            fun of(t: Title) =
                Same(t.kind == Kind.SERIES, t.tmdbId ?: 0, nameOf(t.name), t.year?.take(4)?.toIntOrNull() ?: 0)

            private fun nameOf(name: String?): String = name.orEmpty().lowercase(Locale.ROOT).replace(NOT_NAME, "")
        }
    }

    fun episodeId(season: Int, episode: Int): String = "$season:$episode"

    /** The season and number an [episodeId] names; null for one of another shape. */
    fun parseEpisodeId(id: String?): EpisodeRef? {
        val parts = id?.split(':') ?: return null
        if (parts.size != 2) return null
        val season = parts[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val episode = parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: return null
        return EpisodeRef(season, episode)
    }

    /**
     * A library show's episodes for Glass Home's Episodes window, by season then number: those aired by [now] (the
     * others can't play), and the entry's own ([current]) whatever its date. [positionOf] is where one was left (0: none).
     */
    fun episodeRows(
        episodes: List<Episode>,
        current: EpisodeRef?,
        now: Long,
        positionOf: (Episode) -> Long,
    ): List<ShowEpisodes.Row> = episodes
        .filter { it.aired(now) || (current != null && it.season == current.season && it.episode == current.episode) }
        .distinctBy { it.season to it.episode }
        .sortedWith(compareBy({ it.season }, { it.episode }))
        .map { e ->
            val own = current != null && e.season == current.season && e.episode == current.episode
            ShowEpisodes.Row(
                episodeId = episodeId(e.season, e.episode),
                season = e.season,
                episode = e.episode,
                name = e.name,
                overview = e.overview.orEmpty(),
                image = e.still,
                runtimeMin = e.runtimeMin ?: 0,
                airDate = e.day?.let { String.format(java.util.Locale.ROOT, "%04d-%02d-%02d", it.year, it.month, it.day) }.orEmpty(),
                positionMs = positionOf(e).coerceAtLeast(0),
                current = own,
            )
        }

    private fun safe(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == ':' || c == '-'

    internal fun escape(s: String): String = buildString {
        for (c in s) {
            if (safe(c)) append(c) else for (b in c.toString().toByteArray(Charsets.UTF_8)) {
                append('_').append(String.format(java.util.Locale.ROOT, "%02x", b.toInt() and 0xff))
            }
        }
    }

    internal fun unescape(s: String): String? {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '_') {
                if (i + 3 > s.length) return null
                val b = s.substring(i + 1, i + 3).toIntOrNull(16) ?: return null
                bytes.write(b)
                i += 3
            } else {
                if (!safe(c)) return null
                bytes.write(c.code)
                i++
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
