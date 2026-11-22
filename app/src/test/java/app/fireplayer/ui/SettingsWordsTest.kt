package app.fireplayer.ui

import org.junit.Assert.assertEquals
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
}
