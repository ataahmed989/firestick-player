package app.fireplayer.vod.data

import app.fireplayer.vod.Kind
import app.fireplayer.vod.RowSpec
import app.fireplayer.vod.RowStyle
import app.fireplayer.vod.Source

/**
 * The rows of the My List and Search tabs (Home, Movies and TV Shows have [PageRows]). Poster rows like the others, so
 * they draw and move the same way; an empty row is hidden by the page. Keys are stable.
 */
object TabRows {

    /** My List's films, then its series, each the latest added first. */
    fun myList(): List<RowSpec> = listOf(
        RowSpec("my_list.movies", "My List · Movies", RowStyle.POSTER, Source.MyListOf(Kind.MOVIE)),
        RowSpec("my_list.series", "My List · Series", RowStyle.POSTER, Source.MyListOf(Kind.SERIES)),
    )

    /** Cinemeta's films, then its series, found for [query] (blank: nothing yet). A row with no titles is hidden. */
    fun search(query: String): List<RowSpec> = listOf(
        RowSpec("search.movies", "Movies", RowStyle.POSTER, Source.Search(Kind.MOVIE, query.trim())),
        RowSpec("search.series", "TV Shows", RowStyle.POSTER, Source.Search(Kind.SERIES, query.trim())),
    )
}
