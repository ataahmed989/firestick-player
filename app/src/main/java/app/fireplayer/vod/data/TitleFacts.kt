package app.fireplayer.vod.data

import app.fireplayer.vod.Title

/**
 * The facts a banner shows under a title's logo (a series' years, genres, rating, length, story), for a title made
 * from TMDB's lists (pure). Those titles (a service's rows, More Like This, and My List entries added from them) carry
 * only their year and TMDB's story: Cinemeta's facts were joined for Top 10's titles and on the title page alone, so a
 * show added from a service's page showed only its year in My List's banner. The banner now completes them from
 * Cinemeta's meta ([app.fireplayer.vod.VodRepository.headerTitle]), whichever page shows them.
 */
object TitleFacts {

    /**
     * Whether [title] lacks the facts Cinemeta gives (no genres and no rating: one made from TMDB's lists), and is a
     * title Cinemeta could know: an IMDb id, or a TMDB stand-in ([app.fireplayer.vod.standInId]); not an episode's id.
     */
    fun lacks(title: Title): Boolean {
        val id = title.id
        val known = (id.startsWith("tt") && ':' !in id) || id.startsWith(STAND_IN)
        return known && title.genres.isEmpty() && title.imdbRating.isNullOrBlank()
    }

    /**
     * [title] with Cinemeta's facts from [meta] where it has none: the years (Cinemeta's, a series' "2021–"), genres,
     * rating and length, and the story when it has none. Its id, kind, name, pictures and logo stay, so nothing it
     * shows already changes but the facts line.
     */
    fun complete(title: Title, meta: Title): Title = title.copy(
        year = meta.year?.takeIf { it.isNotBlank() } ?: title.year,
        description = title.description?.takeIf { it.isNotBlank() } ?: meta.description,
        imdbRating = title.imdbRating?.takeIf { it.isNotBlank() } ?: meta.imdbRating?.takeIf { it.isNotBlank() && it != "0" },
        genres = title.genres.ifEmpty { meta.genres },
        runtime = title.runtime?.takeIf { it.isNotBlank() } ?: meta.runtime,
        tmdbId = title.tmdbId ?: meta.tmdbId,
    )

    private const val STAND_IN = "tmdb:"
}
