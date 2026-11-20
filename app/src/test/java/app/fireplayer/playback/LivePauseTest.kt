package app.fireplayer.playback

import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which of the player's stops the live screen shows as a pause. */
class LivePauseTest {

    @Test fun anotherAppTakingTheSoundForGoodIsAPause() {
        assertTrue(LivePause.byItself(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS))
    }

    @Test fun theSoundsOutputGoingAwayIsAPause() {
        // The TV or sound bar switched off or over: the player stops itself, and the screen must say so.
        assertTrue(LivePause.byItself(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY))
    }

    @Test fun theViewersOwnPauseAndTheRestAreNot() {
        // The viewer's own Play/Pause is noted where it's pressed; these come from the app or the stream itself.
        assertFalse(LivePause.byItself(Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST))
        assertFalse(LivePause.byItself(Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE))
        assertFalse(LivePause.byItself(Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM))
    }
}
