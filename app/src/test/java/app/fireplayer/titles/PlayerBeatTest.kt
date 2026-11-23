package app.fireplayer.titles

import app.fireplayer.data.MemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBeatTest {

    @Test
    fun theBeatGoesInItsOwnFileOnlyWhileTheServerIsOn() {
        val beat = MemoryPrefs()
        PlayerBeat.note(beat, on = false, now = 1_000L)
        // The server is off: nothing is written at all (no file change, no sync to flash).
        assertTrue(beat.values.isEmpty())
        PlayerBeat.note(beat, on = true, now = 2_000L)
        assertEquals(1, beat.values.size)
        assertEquals("player_beat", PlayerBeat.FILE)
        // Not the app's settings file, which holds every film's place and is written whole on each change.
        assertFalse(PlayerBeat.FILE == app.fireplayer.data.KeyStore.OLD_FILE)
    }

    @Test
    fun theServerWaitsWhileTheBeatComesAndCarriesOnOnceItStops() {
        val beat = MemoryPrefs()
        // Never heard: not playing as far as the server knows.
        assertFalse(PlayerBeat.heard(beat, 100_000L))
        PlayerBeat.note(beat, on = true, now = 100_000L)
        assertTrue(PlayerBeat.heard(beat, 100_000L + PlayerBeat.EVERY_MS))
        assertTrue(PlayerBeat.heard(beat, 100_000L + PlayerBeat.GONE_MS - 1))
        assertFalse(PlayerBeat.heard(beat, 100_000L + PlayerBeat.GONE_MS))
        // Two beats are missed before the server takes the player as gone.
        assertTrue(PlayerBeat.GONE_MS >= 2 * PlayerBeat.EVERY_MS)
        assertEquals(PlayerBeat.EVERY_MS, TitleService.BEAT_MS)
    }
}
