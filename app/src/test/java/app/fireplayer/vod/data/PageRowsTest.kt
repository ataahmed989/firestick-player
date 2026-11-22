package app.fireplayer.vod.data

import app.fireplayer.vod.Kind
import app.fireplayer.vod.Page
import app.fireplayer.vod.RowStyle
import app.fireplayer.vod.Source
import org.junit.Assert.assertEquals
import org.junit.Test

class PageRowsTest {

    @Test
    fun homeRows() {
        val rows = PageRows.rows(Page.HOME, 2026)
        // Streaming Services sits under Continue Watching, a row like any other in Settings › Rows.
        assertEquals(listOf("Continue Watching", "Streaming Services", "My List", "Next Episodes", "Popular Movies", "Popular Shows"), rows.map { it.label })
        assertEquals(listOf(RowStyle.WIDE, RowStyle.SERVICES, RowStyle.POSTER, RowStyle.POSTER, RowStyle.POSTER, RowStyle.POSTER), rows.map { it.style })
        assertEquals(
            listOf(Source.ContinueWatching(null), Source.Services, Source.MyList, Source.NextEpisodes(), Source.Catalog(Kind.MOVIE, "top"), Source.Catalog(Kind.SERIES, "top")),
            rows.map { it.source },
        )
        assertEquals(listOf(true, false, false, false, false, false), rows.map { it.continueRow })
    }

    @Test
    fun moviesRows() {
        val rows = PageRows.rows(Page.MOVIES, 2026)
        assertEquals(
            listOf("Continue Watching · Films", "Top 10 This Week", "Popular", "New in 2026", "Featured", "Genres"),
            rows.map { it.label },
        )
        assertEquals(
            listOf(RowStyle.WIDE, RowStyle.RANKED, RowStyle.POSTER, RowStyle.POSTER, RowStyle.POSTER, RowStyle.GENRES),
            rows.map { it.style },
        )
        assertEquals(
            listOf(
                Source.ContinueWatching(Kind.MOVIE), Source.Trending(Kind.MOVIE), Source.Catalog(Kind.MOVIE, "top"),
                Source.Catalog(Kind.MOVIE, "year", "2026"), Source.Catalog(Kind.MOVIE, "imdbRating"), Source.Genres(Kind.MOVIE),
            ),
            rows.map { it.source },
        )
        assertEquals(listOf(true, false, false, false, false, false), rows.map { it.continueRow })
    }

    @Test
    fun tvShowsRows() {
        val rows = PageRows.rows(Page.SHOWS, 2027)
        assertEquals(
            listOf("Continue Watching · Series", "Next Episodes", "Top 10 This Week", "Popular", "New in 2027", "Featured", "Genres"),
            rows.map { it.label },
        )
        assertEquals(Source.ContinueWatching(Kind.SERIES), rows[0].source)
        assertEquals(Source.Trending(Kind.SERIES), rows[2].source)
        assertEquals(Source.Catalog(Kind.SERIES, "year", "2027"), rows[4].source)
        assertEquals(Source.Genres(Kind.SERIES), rows[6].source)
    }

    @Test
    fun keysAreStableAndUnique() {
        val all = Page.entries.flatMap { PageRows.rows(it, 2026) }
        assertEquals(all.size, all.map { it.key }.toSet().size)
        // The year is in the label, not the key.
        assertEquals(PageRows.rows(Page.MOVIES, 2026).map { it.key }, PageRows.rows(Page.MOVIES, 2027).map { it.key })
    }
}
