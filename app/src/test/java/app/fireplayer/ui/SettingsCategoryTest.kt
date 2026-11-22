package app.fireplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsCategoryTest {

    @Test fun theCategoriesAreInTheApprovedOrder() {
        assertEquals(
            listOf(
                "Playlists & EPG", "Live TV", "Picture-in-picture", "Playback", "Remote & skipping", "Subtitles & audio", "Look",
                "Accounts & services", "Rows", "Home Header", "Add-ons", "This TV", "TiviMate titles", "App",
            ),
            SettingsCategory.entries.map { it.title },
        )
    }

    @Test fun onlyPlaylistsAndLiveTvAreLiveTvsOwnWindows() {
        assertEquals(
            listOf(SettingsCategory.PLAYLISTS_EPG, SettingsCategory.LIVE_TV),
            SettingsCategory.entries.filter { it.isLive },
        )
    }

    @Test fun everySettingIsInExactlyOneCategory() {
        val all = SettingsCategory.entries.flatMap { it.settings }
        assertEquals(all.groupBy { it }.filterValues { it.size > 1 }.keys.toString(), all.size, all.toSet().size)
        assertTrue(SettingsCategory.entries.all { it.settings.isNotEmpty() })
    }

    @Test fun nothingTheOldAppSettingsWindowHadIsLost() {
        // Every row the one long App settings window showed, and the Settings tab's old rows.
        val before = listOf(
            "Menu button opens", "Info pods", "Glass look", "Glass refresh", "D-pad left / right",
            "Rewind / fast-forward", "Hold to speed up", "Preview while skipping", "Subtitle language", "Audio language",
            "Resume where I left off", "Play next episode", "Match frame rate", "Surround passthrough",
            "English titles in TiviMate", "Check playlists now", "Font from your phone", "Auto sync subtitles",
            "OpenSubtitles API key", "OpenSubtitles username", "OpenSubtitles password", "TMDB API key",
            "Check the keys now", "Skip buttons", "Next episode / Exit countdown", "Check for updates",
            // Settings tab: Playlists, EPG, Live TV and Subtitle style windows.
            "Add playlist", "Update all playlists", "Reorder playlists", "EPG sources", "Past days to keep EPG",
            "Update interval", "Update EPG", "Clear EPG", "Channel ▲▼", "Hide controls after",
            "Full screen after no key presses", "Subtitle style",
        )
        // Renamed since by the user's outline for Playlists and EPG, and rows added since.
        val renamed = mapOf(
            "Update all playlists" to "Update playlists", "Past days to keep EPG" to "Days to keep EPG",
            // Told apart from a playlist's own update interval.
            "Update interval" to "EPG update interval",
        )
        val added = listOf(
            "Manage playlists", "Manage EPG sources", "Live TV buffer",
            // Live TV: whether opening it starts the channel watched last (on, as it always did).
            "Play the last channel on opening",
            // Manage playlists and Update playlists sit under it, as Manage EPG sources does under EPG sources.
            "Playlists",
            // Films & Shows step 3: the add-ons and This TV.
            "Torrentio", "MediaFusion", "Check again", "Best for this TV",
            // The third add-on.
            "StremThru Torz",
            // Films & Shows step 4: Settings › Rows, a window per page.
            "Home", "Movies", "TV Shows",
            // Home Header: whether Home's banner shows the focused Continue Watching title.
            "Match the focused Continue Watching title",
            // ...and how long every tab's banner shows each title while idle.
            "Time on each title",
            // Playback: what a film from the add-ons does when it keeps stopping to load.
            "When a film keeps buffering",
            // Playback: a film read with two requests at a time, Off by default.
            "Faster downloads (2 connections)",
            // Multiview: how many pictures at most, and how 3 or 4 open.
            "Multiview pictures", "Multiview with 3 or 4",
            // Picture-in-picture: the live picture on Home and Search, and whether it starts with its sound.
            "Keep live TV playing on Home and Search", "Start with sound",
            // Settings › Rows: the order of Home's streaming services, and the rail's service shortcuts.
            "Reorder services", "Show streaming services on rail", "First shortcut", "Second shortcut",
            // Look: each glass's Tint and Dimness.
            "App glass", "Playback glass",
        )
        val kept = before.map { renamed[it] ?: it }
        val now = SettingsCategory.entries.flatMap { it.settings }.toSet()
        assertEquals(emptyList<String>(), kept.filter { it !in now })
        assertEquals(emptyList<String>(), now.filter { it !in kept && it !in added })
    }

    @Test fun settingsSitWhereTheUserPutThem() {
        fun where(setting: String) = SettingsCategory.entries.single { setting in it.settings }
        assertEquals(SettingsCategory.PLAYBACK, where("Resume where I left off"))
        assertEquals(SettingsCategory.PLAYBACK, where("Skip buttons"))
        assertEquals(SettingsCategory.PLAYBACK, where(SettingsCategory.WHEN_BUFFERING))
        assertEquals(SettingsCategory.SUBTITLES_AUDIO, where("Auto sync subtitles"))
        assertEquals(SettingsCategory.SUBTITLES_AUDIO, where("Subtitle style"))
        assertEquals(SettingsCategory.REMOTE, where("Hold to speed up"))
        assertEquals(SettingsCategory.ACCOUNTS, where("TMDB API key"))
        assertEquals(SettingsCategory.LOOK, where("Info pods"))
        assertEquals(SettingsCategory.LOOK, where("App glass"))
        assertEquals(SettingsCategory.LOOK, where("Playback glass"))
        assertEquals(SettingsCategory.LIVE_TV, where("Live TV buffer"))
        assertEquals(SettingsCategory.PLAYLISTS_EPG, where("Manage EPG sources"))
        assertEquals(SettingsCategory.ADDONS, where("MediaFusion"))
        assertEquals(SettingsCategory.THIS_TV, where("Best for this TV"))
        assertEquals(SettingsCategory.HOME_HEADER, where(SettingsCategory.HOME_HEADER_MATCH))
        assertEquals(SettingsCategory.HOME_HEADER, where(SettingsCategory.HOME_HEADER_TIME))
        assertEquals(SettingsCategory.PICTURE_IN_PICTURE, where("Keep live TV playing on Home and Search"))
        assertEquals(SettingsCategory.PICTURE_IN_PICTURE, where("Start with sound"))
    }

    /** The Settings tab's sizes: rows 44 dp + 8 dp, the title 34 + 16 dp, 27 dp kept clear above and below. */
    private fun columns(rows: Int, screenDp: Int) =
        SettingsCategory.columnsFor(rows, screenDp, rowDp = 44, gapDp = 8, titleDp = 50, edgeDp = 27)

    @Test fun theSettingsTabStillTakesTwoColumnsWithPictureInPicture() {
        // Fourteen categories and Open a link: two columns, eight and seven, eight to a column at most on the Fire TV.
        assertEquals(15, SettingsCategory.entries.size + 1)
        assertEquals(2, columns(15, 540))
        assertEquals(listOf(8, 7), SettingsCategory.inColumns((1..15).toList(), 2).map { it.size })
    }

    @Test fun tenRowsTakeTwoColumnsOnTheFireTv() {
        // 960 x 540 dp: 10 x 52 = 520 dp of rows, with only 436 dp left under the title.
        assertEquals(2, columns(10, 540))
        // Eight fit one column (8 x 52 = 416 dp); nine don't.
        assertEquals(1, columns(8, 540))
        assertEquals(2, columns(9, 540))
        // A taller screen takes them all in one.
        assertEquals(1, columns(10, 720))
    }

    @Test fun aScreenWithNoRoomStillGivesEachRowAPlace() {
        assertEquals(10, columns(10, 100))
        assertEquals(1, columns(0, 540))
    }

    @Test fun columnsFillTopToBottomThenLeftToRight() {
        val rows = (1..10).toList()
        assertEquals(listOf((1..5).toList(), (6..10).toList()), SettingsCategory.inColumns(rows, 2))
        assertEquals(listOf(rows), SettingsCategory.inColumns(rows, 1))
        // An odd count leaves the shorter column on the right.
        assertEquals(listOf((1..5).toList(), (6..9).toList()), SettingsCategory.inColumns((1..9).toList(), 2))
        assertEquals(emptyList<List<Int>>(), SettingsCategory.inColumns(emptyList<Int>(), 2))
        assertEquals(listOf(listOf(1)), SettingsCategory.inColumns(listOf(1), 0))
    }
}
