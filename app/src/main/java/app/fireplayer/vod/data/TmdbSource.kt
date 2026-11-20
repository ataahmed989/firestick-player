package app.fireplayer.vod.data

import app.fireplayer.subs.Tmdb
import app.fireplayer.vod.Kind

/**
 * The TMDB calls Films & Shows makes, with the user's key (subs/[Tmdb] picks the header or the query parameter and
 * never logs either): the Top 10 rows' two, and the title page's details and (rarely) find. All block and throw
 * [Tmdb.HttpError] (401: the key isn't accepted; 429: too many requests) or an IOException.
 */
interface TmdbSource {
    /** `trending/{movie|tv}/week`, page 1, as TMDB sends it. */
    fun trending(kind: Kind): String

    /** `{movie|tv}/{id}/external_ids` as TMDB sends it; null when TMDB has no such title. */
    fun externalIds(kind: Kind, tmdbId: Int): String?

    /**
     * `{movie|tv}/{id}` with [TmdbDetails.append]'s parts appended, in English, as TMDB sends it; null when TMDB has no
     * such title.
     */
    fun details(kind: Kind, tmdbId: Int): String?

    /** `find/{tt}?external_source=imdb_id` as TMDB sends it. */
    fun find(imdbId: String): String

    companion object {
        fun of(tmdb: Tmdb): TmdbSource = object : TmdbSource {
            override fun trending(kind: Kind) = tmdb.trendingThisWeek(kind == Kind.SERIES)
            override fun externalIds(kind: Kind, tmdbId: Int) = tmdb.externalIds(tmdbId, kind == Kind.SERIES)
            override fun details(kind: Kind, tmdbId: Int) =
                tmdb.titleDetails(tmdbId, kind == Kind.SERIES, TmdbDetails.append(kind))
            override fun find(imdbId: String) = tmdb.findByImdb(imdbId)
        }
    }
}
