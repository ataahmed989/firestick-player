package app.fireplayer.vod.ui

import app.fireplayer.vod.Page

/**
 * A Films & Shows tab, each a [VodPage] made of the same parts: one of the repository's pages ([page]: Home, Movies, TV
 * Shows), My List or Search. [title] is its name line.
 */
enum class VodTab(val page: Page?, val title: String) {
    HOME(Page.HOME, "Home"),
    MOVIES(Page.MOVIES, "Movies"),
    SHOWS(Page.SHOWS, "TV Shows"),
    /** Its header never slides away and shows the focused title (no rotation); two rows from My List. */
    MY_LIST(null, "My List"),
    /** A search bar and its history in the header's place (no header card); two rows of results. */
    SEARCH(null, "Search"),
}
