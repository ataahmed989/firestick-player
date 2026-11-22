package app.fireplayer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The words and badge colours every settings window shares, so the same thing reads the same everywhere. */
class SettingsWordsTest {

    @Test fun countBadgesFollowOneRule() {
        assertEquals(Rows.Badge.ACTIVE, Rows.countBadge(5, 5))
        assertEquals(Rows.Badge.LIGHT, Rows.countBadge(3, 5))
        assertEquals(Rows.Badge.INACTIVE, Rows.countBadge(0, 5))
        // A problem wins, whatever the count.
        assertEquals(Rows.Badge.PROBLEM, Rows.countBadge(5, 5, problem = true))
        assertEquals(Rows.Badge.PROBLEM, Rows.countBadge(0, 2, problem = true))
        // Nothing to count is none, not all.
        assertEquals(Rows.Badge.INACTIVE, Rows.countBadge(0, 0))
    }

    @Test fun skipStepsAreSpelledOut() {
        assertEquals("5 seconds", SettingsPanels.formatStep(5))
        assertEquals("30 seconds", SettingsPanels.formatStep(30))
        assertEquals("1 minute", SettingsPanels.formatStep(60))
        assertEquals("2 minutes", SettingsPanels.formatStep(120))
        assertEquals("10 minutes", SettingsPanels.formatStep(600))
    }

    @Test fun headingsOfTheSettingsWindowsHaveIcons() {
        val headings = listOf(
            "This copy", "Copies", "Hidden groups", "Favorites", "Order", "Groups", "Playlists & EPG", "Subtitle style",
            "Presets", "Storage", "On this channel", "EPG sources", "Updates", "Account", "Guide",
            // Settings › Rows: its window's own heading, and its two sections.
            "Rows", "Streaming Services", "Service shortcuts",
        )
        assertEquals(emptyList<String>(), headings.filter { SidePanel.headingIcon(it) == null })
    }

    @Test fun everyCategoryHasItsOwnIcon() {
        assertEquals(emptyList<SettingsCategory>(), SettingsCategory.entries.filter { it.icon == 0 })
        assertEquals(SettingsCategory.entries.size, SettingsCategory.entries.map { it.icon }.toSet().size)
    }

    @Test fun looksAndTheScreenDimmersNotesAreShortAndShowInFull() {
        // The window's inside width at 960 x 540 dp: 31% of the screen less its 16 dp sides. Notes are 12 sp, about half
        // an em a character at most (measured, these average under 0.45 em).
        val inside = 960 * SidePanel.WIDTH_SHARE - 2 * 16
        val perLine = (inside / (12 * 0.5f)).toInt()
        val notes = listOf(SettingsPanels.SHADES_NOTE, DimmerWindow.DEFAULT_NOTE, SettingsPanels.customOnNote("Late night"))
        for (note in notes) assertTrue(note, note.length <= SettingsPanels.NOTE_LINES * perLine)
        assertEquals(2, SettingsPanels.NOTE_LINES)
        // Faster downloads': one line, in its card (12 dp in from each side).
        val inCard = ((inside - 2 * 12) / (12 * 0.5f)).toInt()
        assertTrue(SettingsPanels.FASTER_DOWNLOADS_NOTE.length <= inCard)
        // Under the last row, a note shows in full once that row is selected: the list keeps the selected row in its
        // middle as far as it can scroll, so what's under the row's middle must fit in half the list. The list: 540 dp
        // less the window's 18 dp margins and 16 dp sides, and its title (17 sp, about 1.3 lines, and 8 dp under it).
        val list = 540 - 2 * 18 - 2 * 16 - (17 * 1.3f + 8)
        val note = SettingsPanels.NOTE_LINES * (12 * 1.3f + 2) + 4 + 4
        // Half a 33 dp row, its card's 2 dp edge, the note, the list's 2 dp foot.
        val underRowMiddle = 33 / 2f + 2 + note + 2
        assertTrue("$underRowMiddle dp under the row's middle, ${list / 2} dp to show it", underRowMiddle <= list / 2)
        assertEquals("Reset to defaults", SettingsPanels.RESET_SHADES)
    }
}
