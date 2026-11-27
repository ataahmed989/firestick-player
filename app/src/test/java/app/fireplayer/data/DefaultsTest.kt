package app.fireplayer.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings whose default is On: what an untouched install gets, and a viewer's own choice kept. */
class DefaultsTest {

    @Test fun hdr10PlusReachesThisTvIsOnUntilTheViewerTurnsItOff() {
        // Never set: On, so a TV that reports HDR10+ gets it.
        assertTrue(Prefs.hdr10PlusReachesIn(MemoryPrefs()))
        assertTrue(Prefs.hdr10PlusReachesIn(null))
        // Turned off deliberately: kept off; turned on: on.
        assertFalse(Prefs.hdr10PlusReachesIn(MemoryPrefs(Prefs.HDR10_PLUS_KEY to false)))
        assertTrue(Prefs.hdr10PlusReachesIn(MemoryPrefs(Prefs.HDR10_PLUS_KEY to true)))
    }

    @Test fun fasterDownloadsIsOnUntilTheViewerTurnsItOff() {
        assertTrue(Prefs.fasterDownloadsIn(MemoryPrefs()))
        assertFalse(Prefs.fasterDownloadsIn(MemoryPrefs("faster_downloads" to false)))
        assertTrue(Prefs.fasterDownloadsIn(MemoryPrefs("faster_downloads" to true)))
    }
}
