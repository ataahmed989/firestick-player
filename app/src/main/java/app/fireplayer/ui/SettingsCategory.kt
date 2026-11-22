package app.fireplayer.ui

import app.fireplayer.R

/**
 * App settings' categories (user approved), in the order the Settings tab lists them, with the settings each holds by
 * the labels of their rows (the options under a row go with it). Every setting is in exactly one category. The rows
 * are still drawn by one function each ([SettingsPanels.renderCategory]; Playlists & EPG and Live TV by Live TV's own
 * windows), so a setting reads and writes its one saved value wherever it shows. [icon] goes beside the category
 * wherever it's listed (the Settings tab, App settings).
 */
enum class SettingsCategory(val title: String, val icon: Int, val settings: List<String>) {
    PLAYLISTS_EPG(
        "Playlists & EPG",
        R.drawable.ic_playlist_add,
        listOf(
            "Playlists", "Manage playlists", "Add playlist", "Reorder playlists", "Update playlists",
            "EPG sources", "Manage EPG sources", "Days to keep EPG", "EPG update interval", "Update EPG", "Clear EPG",
        ),
    ),
    // Live TV buffer is in Playback's window too (user): the same row, one saved value.
    LIVE_TV(
        "Live TV",
        R.drawable.ic_live,
        listOf(
            "Channel ▲▼", "Hide controls after", "Full screen after no key presses", "Play the last channel on opening", "Live TV buffer",
            "Multiview pictures", "Multiview with 3 or 4",
        ),
    ),
    // The live picture on Home and Search (automatic picture-in-picture): whether leaving Live TV keeps the channel
    // playing there, and whether it starts with its sound.
    PICTURE_IN_PICTURE(
        "Picture-in-picture",
        R.drawable.ic_pip,
        listOf(app.fireplayer.live.ui.PipRules.KEEP_LABEL, app.fireplayer.live.ui.PipRules.SOUND_LABEL),
    ),
    PLAYBACK(
        "Playback",
        R.drawable.ic_head_play,
        listOf(
            "Play next episode", "Resume where I left off", "Match frame rate", "Surround passthrough", "When a film keeps buffering",
            "Faster downloads (2 connections)", "Skip buttons", "Next episode / Exit countdown",
        ),
    ),
    REMOTE(
        "Remote & skipping",
        R.drawable.ic_head_remote,
        listOf(
            "Menu button opens", "D-pad left / right", "Rewind / fast-forward", "Hold to speed up",
            "Preview while skipping",
        ),
    ),
    SUBTITLES_AUDIO(
        "Subtitles & audio",
        R.drawable.ic_subtitles,
        listOf("Subtitle language", "Audio language", "Auto sync subtitles", "Subtitle style", "Font from your phone"),
    ),
    // App glass and Playback glass: each glass's Tint and Dimness (App settings' Look only, not the players' menus).
    LOOK("Look", R.drawable.ic_head_look, listOf("Glass look", "Glass refresh", "Info pods", "App glass", "Playback glass")),
    ACCOUNTS(
        "Accounts & services",
        R.drawable.ic_key,
        listOf(
            "OpenSubtitles API key", "OpenSubtitles username", "OpenSubtitles password", "TMDB API key",
            "Check the keys now",
        ),
    ),
    // Films & Shows' pages: which rows Home, Movies and TV Shows show, in what order (Settings › Rows); the order of
    // Home's streaming services, and the rail's service shortcuts.
    ROWS(
        "Rows",
        R.drawable.ic_head_list,
        listOf("Home", "Movies", "TV Shows", "Reorder services", "Show streaming services on rail", "First shortcut", "Second shortcut"),
    ),
    // Home's banner: whether it shows the focused Continue Watching title or always goes through its own; and how long
    // every tab's banner shows each title while idle.
    HOME_HEADER("Home Header", R.drawable.ic_head_banner, listOf("Match the focused Continue Watching title", "Time on each title")),
    // Films & Shows' sources (design-brief.md §6.1, 6.5): the two add-ons, and how sources are ranked for this TV.
    ADDONS("Add-ons", R.drawable.ic_server, listOf("Torrentio", "MediaFusion", "StremThru Torz")),
    THIS_TV("This TV", R.drawable.ic_display, listOf("Check again", "Best for this TV")),
    TITLES("TiviMate titles", R.drawable.ic_head_tv, listOf("English titles in TiviMate", "Check playlists now")),
    APP("App", R.drawable.ic_head_app, listOf("Check for updates")),
    ;

    /** Drawn by Live TV's own windows (LiveSettings), which only a screen with them can open. */
    val isLive: Boolean get() = this == PLAYLISTS_EPG || this == LIVE_TV

    companion object {
        /** The Settings tab's last row, after the categories: a tool rather than settings. */
        const val OPEN_A_LINK = "Open a link"

        /** Playback: what a film from the add-ons does when it keeps stopping to load. */
        const val WHEN_BUFFERING = "When a film keeps buffering"

        /** Home Header's switch. */
        val HOME_HEADER_MATCH: String get() = HOME_HEADER.settings[0]

        /** Home Header's idle time: how long every tab's banner shows each title. */
        val HOME_HEADER_TIME: String get() = HOME_HEADER.settings[1]

        /** The window listing the categories, with the Search bubble at its top (Menu, and App › Search and App Settings in the player windows). */
        const val LIST_TITLE = "Search and App Settings"

        /**
         * How many columns the Settings tab needs for [rows] rows, each [rowDp] tall plus [gapDp] under it, below a
         * title [titleDp] tall (with its margin), on a screen [screenDp] tall that keeps [edgeDp] clear above and
         * below (the part a TV may cut off): one while they all fit, otherwise as few as do.
         */
        fun columnsFor(rows: Int, screenDp: Int, rowDp: Int, gapDp: Int, titleDp: Int, edgeDp: Int): Int {
            if (rows <= 0) return 1
            val room = screenDp - 2 * edgeDp - titleDp
            val perColumn = (room / (rowDp + gapDp)).coerceAtLeast(1)
            return (rows + perColumn - 1) / perColumn
        }

        /** [items] in [columns] columns, filled top to bottom and then left to right, the first ones the fullest. */
        fun <T> inColumns(items: List<T>, columns: Int): List<List<T>> {
            if (items.isEmpty()) return emptyList()
            val perColumn = (items.size + columns.coerceAtLeast(1) - 1) / columns.coerceAtLeast(1)
            return items.chunked(perColumn)
        }
    }
}
