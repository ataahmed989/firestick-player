package app.fireplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureWatchTest {
    private val watch = PictureWatch()
    private var pos = 600_000L

    /** Five looks a second for [ms] of film, as the player's tick gives them; the first reason said, if any. */
    private fun play(
        ms: Long,
        frames: (Long) -> Long = { -1L },
        playing: Boolean = true,
        held: Boolean = false,
        hasVideo: Boolean = true,
        decodable: Boolean = true,
        durationMs: Long = 7_200_000L,
        moves: Boolean = true,
    ): PictureWatch.Why? {
        var said: PictureWatch.Why? = null
        var t = 0L
        while (t < ms) {
            t += 200
            if (moves) pos += 200
            val why = watch.check(playing, held, pos, durationMs, hasVideo, decodable, frames(t))
            if (said == null) said = why
        }
        return said
    }

    @Test
    fun aPictureNoDecoderTakesIsSaidAfterTwoSecondsOfSound() {
        assertNull(play(1_800, decodable = false))
        assertEquals(PictureWatch.Why.NO_DECODER, play(400, decodable = false))
        // Said once.
        assertNull(play(10_000, decodable = false))
    }

    @Test
    fun framesThatStopWhileTheSoundPlaysAreSaidAfterTenSeconds() {
        // Frames come for a minute, then stop at 1440.
        var shown = 0L
        assertNull(play(60_000, frames = { shown += 5; shown }))
        assertNull(play(9_800, frames = { shown }))
        assertEquals(PictureWatch.Why.NO_FRAMES, play(400, frames = { shown }))
    }

    @Test
    fun framesComingAreAPictureHoweverDarkTheyAre() {
        // What the frames show is never looked at: only that they come (a black opening is frames like any other).
        var shown = 0L
        assertNull(play(120_000, frames = { shown += 5; shown }))
        // One frame now and then (a slow decoder Media3 lets through) is still a picture.
        assertNull(play(120_000, frames = { t -> if (t % 4_000 == 0L) ++shown else shown }))
    }

    @Test
    fun onlyFilmPlayingCounts() {
        // Paused, loading or held by the app (a question, the TV switching rate, no surface): no film played.
        assertNull(play(60_000, decodable = false, playing = false))
        assertNull(play(60_000, decodable = false, held = true))
        // Meant to play but standing still (the freeze watch's case): nothing either.
        assertNull(play(60_000, decodable = false, moves = false))
        // What was counted before a pause still counts.
        assertNull(play(1_000, decodable = false))
        assertNull(play(5_000, decodable = false, playing = false))
        assertEquals(PictureWatch.Why.NO_DECODER, play(1_200, decodable = false))
    }

    @Test
    fun aJumpInTheFilmsTimeIsNotFilmPlayed() {
        assertNull(play(1_000, decodable = false))
        pos += 300_000
        assertNull(play(600, decodable = false))
        assertEquals(PictureWatch.Why.NO_DECODER, play(1_000, decodable = false))
    }

    @Test
    fun aFileWithNoPictureAndTheFilmsEndAreNotWatched() {
        assertNull(play(60_000, hasVideo = false))
        // The last minute: a file whose picture ends a little before its sound.
        pos = 7_200_000L - 59_000L
        assertNull(play(30_000, durationMs = 7_200_000L))
    }

    @Test
    fun aResetWatchesAfresh() {
        assertNull(play(1_000, decodable = false))
        watch.reset()
        assertNull(play(1_800, decodable = false))
        assertEquals(PictureWatch.Why.NO_DECODER, play(400, decodable = false))
        watch.reset()
        // A new decoder counting again from 0: its first frames are the picture.
        var shown = 0L
        assertNull(play(30_000, frames = { shown += 5; shown }))
    }

    @Test
    fun theVideoDecodersErrorsAreTheOnlyPlayerErrorsThatMeanNoPicture() {
        for (code in 4001..4005) assertTrue("$code", PictureWatch.decoderFailed(code, "video/hevc"))
        assertTrue(PictureWatch.decoderFailed(4003, "video/dolby-vision"))
        // The sound's decoder, or no renderer said: the failure table's as before.
        assertFalse(PictureWatch.decoderFailed(4003, "audio/true-hd"))
        assertFalse(PictureWatch.decoderFailed(4003, null))
        assertFalse(PictureWatch.decoderFailed(2001, "video/hevc"))
        assertFalse(PictureWatch.decoderFailed(5001, "video/hevc"))
    }

    @Test
    fun aCopyThatShowedNoPictureIsKeptSoAndForgivenAfterPlayingWithIt() {
        val failed = CopyHealth(watchedMs = 8_000, noPicture = 1)
        assertEquals("showed no picture here", failed.summary())
        assertFalse(failed.troubled)
        val text = CopyHealth.encode(failed)
        assertEquals("8000|0|0|0|0|0|0|1", text)
        assertEquals(failed, CopyHealth.decode(text))
        // A copy that never failed so is kept as before (seven fields), and older strings read with none.
        assertEquals("8000|0|0|0|0|0|0", CopyHealth.encode(CopyHealth(watchedMs = 8_000)))
        assertEquals(0, CopyHealth.decode("600000|2|5000|0|1")!!.noPicture)
        // Another play with no picture adds up; one with its picture for ten minutes clears it.
        assertEquals(2, (failed + CopyHealth(watchedMs = 2_000, noPicture = 1)).noPicture)
        assertEquals(1, (failed + CopyHealth(watchedMs = 5 * 60_000L, renderedFrames = 7_000)).noPicture)
        assertEquals(0, (failed + CopyHealth(watchedMs = 12 * 60_000L, renderedFrames = 17_000)).noPicture)
        // Ten minutes of sound with no frame shown isn't a play with its picture.
        assertEquals(1, (failed + CopyHealth(watchedMs = 12 * 60_000L)).noPicture)
    }
}
