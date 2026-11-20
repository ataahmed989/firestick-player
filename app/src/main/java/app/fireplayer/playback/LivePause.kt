package app.fireplayer.playback

import androidx.media3.common.Player

/**
 * The pauses a live channel makes by itself, which the screen shows as a pause (Play to carry on, Go live offered):
 * another app took the sound for good (its film or music started), or the sound's way out went away (the TV or sound
 * bar switched off or over to another input, headphones disconnected). The player stops playing in both cases; a
 * pause that showed nowhere left a still picture that said it was playing. A pause the viewer made, and the player's
 * own waits (buffering, a moment's loss of the sound), aren't these. Pure: Media3's reason numbers only.
 */
object LivePause {
    /** Whether a change of play-when-ready for [reason] (Player.PLAY_WHEN_READY_CHANGE_REASON_*) is the channel pausing itself. */
    fun byItself(reason: Int): Boolean =
        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
            reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY
}
