package app.fireplayer.vod.data

import app.fireplayer.vod.Kind
import app.fireplayer.vod.Page
import app.fireplayer.vod.RowSpec
import app.fireplayer.vod.RowStyle
import app.fireplayer.vod.Source

/**
 * The default rows of Home, Movies and TV Shows, in order (design-brief.md §6.3); Settings › Rows lets the viewer
 * switch, reorder and add to them ([RowSettings]).
 * Rows whose source is empty are hidden by the page (Continue Watching and Next Episodes until playback records some,
 * My List until a title is added, Top 10 without a TMDB key). Keys are stable, whatever the year in a label.
 */
object PageRows {

    fun rows(page: Page, newYear: Int): List<RowSpec> = when (page) {
        Page.HOME -> listOf(
            RowSpec("home.continue", "Continue Watching", RowStyle.WIDE, Source.ContinueWatching(null), continueRow = true),
            Services.homeRow(),
            RowSpec("home.my_list", "My List", RowStyle.POSTER, Source.MyList),
            RowSpec("home.next_episodes", "Next Episodes", RowStyle.POSTER, Source.NextEpisodes()),
            RowSpec("home.popular_movies", "Popular Movies", RowStyle.POSTER, Source.Catalog(Kind.MOVIE, Cinemeta.TOP)),
            RowSpec("home.popular_shows", "Popular Shows", RowStyle.POSTER, Source.Catalog(Kind.SERIES, Cinemeta.TOP)),
        )
        Page.MOVIES -> listOf(
            RowSpec("movies.continue", "Continue Watching · Films", RowStyle.WIDE, Source.ContinueWatching(Kind.MOVIE), continueRow = true),
        ) + browse("movies", Kind.MOVIE, newYear)
        Page.SHOWS -> listOf(
            RowSpec("shows.continue", "Continue Watching · Series", RowStyle.WIDE, Source.ContinueWatching(Kind.SERIES), continueRow = true),
            RowSpec("shows.next_episodes", "Next Episodes", RowStyle.POSTER, Source.NextEpisodes()),
        ) + browse("shows", Kind.SERIES, newYear)
    }

    /** Movies' and TV Shows' rows after their continue rows. */
    private fun browse(page: String, kind: Kind, year: Int) = listOf(
        RowSpec("$page.top10", "Top 10 This Week", RowStyle.RANKED, Source.Trending(kind)),
        RowSpec("$page.popular", "Popular", RowStyle.POSTER, Source.Catalog(kind, Cinemeta.TOP)),
        RowSpec("$page.new", "New in $year", RowStyle.POSTER, Source.Catalog(kind, Cinemeta.YEAR, year.toString())),
        RowSpec("$page.featured", "Featured", RowStyle.POSTER, Source.Catalog(kind, Cinemeta.FEATURED)),
        RowSpec("$page.genres", "Genres", RowStyle.GENRES, Source.Genres(kind)),
    )
}
