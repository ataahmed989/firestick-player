package app.fireplayer.player

/**
 * Notices a copy whose picture this Stick doesn't show, so the next copy can play instead: the film plays (Media3 says
 * so and its time, which its sound drives, moves on) while no frame of its picture reaches the screen.
 *  - [Why.NO_DECODER]: the file has a picture, but none of this TV's video decoders takes it (Media3 then plays the
 *    sound alone), seen for [NO_DECODER_MS] of film;
 *  - [Why.NO_FRAMES]: the video decoder sends no frame to the screen for [NO_FRAMES_MS] of film playing, from its start
 *    or later on (a decoder that stops).
 * A video decoder's error is the third way, which the player has from Media3 at once ([decoderFailed]).
 *
 * Never judged by what the frames show: a dark scene or a black opening is frames like any other. Only the film's own
 * moving counts: time while paused, loading, held by the app (a jump being lined up, a question, the TV switching rate),
 * with the screen not in front or no surface to show on, and the last [END_GUARD_MS] of the film (a file whose picture
 * ends before its sound) count for nothing, nor does a jump in its time. Said once, until [reset] (another copy, a jump,
 * other tracks, a new player).
 *
 * Fed from the main thread five times a second. Pure: no Android, so it can be tested.
 */
class PictureWatch {

    enum class Why { NO_DECODER, NO_FRAMES }

    // Film played (ms) with no new frame shown, and where the film was at the last sample (-1: not counting then).
    private var filmMs = 0L
    private var lastPos = -1L
    // The decoder's count of frames shown at the last sample (-1: no decoder).
    private var lastFrames = -1L
    private var told = false

    /**
     * One look: [playing] Media3's isPlaying; [held] not watched now; [positionMs], [durationMs] (0 or less: not known)
     * the film's; [hasVideo] the file has a picture track (its tracks are known); [decodable] one of its picture tracks
     * is one this TV's decoders take; [framesShown] what the video decoder has sent to the screen since it was set up
     * (-1 with no video decoder). The reason when the picture isn't shown, once; else null.
     */
    fun check(
        playing: Boolean,
        held: Boolean,
        positionMs: Long,
        durationMs: Long,
        hasVideo: Boolean,
        decodable: Boolean,
        framesShown: Long,
    ): Why? {
        if (told) return null
        // A frame shown since the last look: the picture is there. (A new decoder starts its count again from 0.)
        val shown = framesShown > 0 && framesShown != lastFrames
        lastFrames = framesShown
        val nearEnd = durationMs > 0 && positionMs >= durationMs - END_GUARD_MS
        if (!hasVideo || held || !playing || nearEnd) {
            lastPos = -1
            if (!hasVideo || shown) filmMs = 0
            return null
        }
        if (lastPos >= 0) {
            val step = positionMs - lastPos
            if (step in 1..MAX_STEP_MS) filmMs += step
        }
        lastPos = positionMs
        if (shown) {
            filmMs = 0
            return null
        }
        val why = if (decodable) Why.NO_FRAMES else Why.NO_DECODER
        if (filmMs < if (decodable) NO_FRAMES_MS else NO_DECODER_MS) return null
        told = true
        return why
    }

    /** Another copy, a jump, other tracks or a new player: watched afresh. */
    fun reset() {
        filmMs = 0
        lastPos = -1
        lastFrames = -1
        told = false
    }

    companion object {
        /** Film played with its picture in no decoder this TV has, ms: Media3 said so; this only lets the tracks settle. */
        const val NO_DECODER_MS = 2_000L
        /** Film played with no frame shown, ms: far more than a decoder takes to start or to get past a damaged stretch. */
        const val NO_FRAMES_MS = 10_000L
        /** The film's end: a file whose picture stops a little before its sound isn't a copy without a picture. */
        const val END_GUARD_MS = 60_000L
        /** A move in the film's time bigger than this between looks (five a second) is a jump, not film played. */
        const val MAX_STEP_MS = 2_000L

        /**
         * Whether a player error is the video decoder's: Media3's decoder errors (4001-4005: it couldn't be set up or
         * queried, it failed while decoding, the format is beyond it or not one it takes) from the renderer of a picture
         * ([rendererMime], the format the renderer had). The sound's decoder failing is the failure table's (another
         * format, or the sound decoded on the Stick).
         */
        fun decoderFailed(errorCode: Int, rendererMime: String?): Boolean =
            errorCode in 4001..4005 && rendererMime?.startsWith("video/") == true
    }
}
