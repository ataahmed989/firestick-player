package app.fireplayer.live.ui

import app.fireplayer.ui.SettingsCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live TV's own preference rows are listed under the category whose window draws them. */
class LivePrefsRowTest {

    @Test fun playTheLastChannelOnOpeningIsALiveTvSetting() {
        val where = SettingsCategory.entries.filter { LivePrefs.PLAY_LAST_ON_OPENING in it.settings }
        assertEquals(listOf(SettingsCategory.LIVE_TV), where)
    }

    @Test fun multiviewsRowsAreLiveTvSettings() {
        for (row in listOf(MULTIVIEW_PICTURES, MULTIVIEW_LAYOUT)) {
            assertEquals(listOf(SettingsCategory.LIVE_TV), SettingsCategory.entries.filter { row in it.settings })
        }
    }

    @Test fun itSitsWithLiveTvsPlaybackRowsBeforeTheBuffer() {
        // The window lists it under Playback, just before Live TV buffer: the category names them in that order.
        val rows = SettingsCategory.LIVE_TV.settings
        assertTrue(rows.indexOf(LivePrefs.PLAY_LAST_ON_OPENING) in 0 until rows.indexOf("Live TV buffer"))
    }
}
