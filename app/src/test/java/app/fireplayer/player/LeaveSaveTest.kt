package app.fireplayer.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The player screen's saves of where the film is, on leaving and as it stops ([LeaveSave]): Back writes once (the
 * library once, Glass Home told once), and a jump being lined up as Home is pressed is kept where it lands, as 1.0.366
 * kept it. Found in review of build 367: the save-once guard dropped the jump, and Continue Watching in both homes
 * resumed 6 minutes early.
 */
class LeaveSaveTest {

    /** PlayerActivity's steps, with what the library is written each time ([written], in ms). */
    private class Screen {
        val save = LeaveSave()
        var positionMs = 20 * 60_000L
        private var linedUpMs: Long? = null
        val written = ArrayList<Long>()

        /** Right or Left lines a jump up; it lands 0.9 to 2 s later ([land]), or in onStop. */
        fun lineUp(toMs: Long) {
            linedUpMs = toMs
        }

        /** commitSeek: the jump lands (on the stopped player too, as Media3 has it). */
        fun land() {
            val to = linedUpMs ?: return
            linedUpMs = null
            positionMs = to
            save.moved()
        }

        /** Back while a jump is lined up: it's let go of (cancelSeek). */
        fun cancel() {
            linedUpMs = null
        }

        /** finishWithResult: Back, Home, the sleep timer, the film's end. */
        fun leave() {
            written += positionMs
            save.left()
        }

        /** onStop: a lined-up jump lands, then the place is saved unless leaving saved it already. */
        fun stop() {
            land()
            if (save.saveOnStop()) written += positionMs
        }
    }

    @Test
    fun backWritesThePlaceOnce() {
        val s = Screen()
        s.leave()
        s.stop()
        assertEquals(listOf(20 * 60_000L), s.written)
        // Back with a jump lined up lets go of the jump first; Back again leaves: once, where it plays.
        val t = Screen()
        t.lineUp(26 * 60_000L)
        t.cancel()
        t.leave()
        t.stop()
        assertEquals(listOf(20 * 60_000L), t.written)
        // A jump that landed while it played is where leaving saves: once.
        val u = Screen()
        u.lineUp(26 * 60_000L)
        u.land()
        u.leave()
        u.stop()
        assertEquals(listOf(26 * 60_000L), u.written)
    }

    @Test
    fun homeWhileAJumpIsLinedUpKeepsWhereItLands() {
        // 20:00, Right lines up 26:00, Home within the second: leaving saves 20:00, onStop lands the jump and saves it.
        val s = Screen()
        s.lineUp(26 * 60_000L)
        s.leave()
        s.stop()
        assertEquals(26 * 60_000L, s.written.last())
        assertEquals(listOf(20 * 60_000L, 26 * 60_000L), s.written)
        // The jump's own timer landing it between leaving and onStop: the same.
        val t = Screen()
        t.lineUp(26 * 60_000L)
        t.leave()
        t.land()
        t.stop()
        assertEquals(listOf(20 * 60_000L, 26 * 60_000L), t.written)
    }

    @Test
    fun stoppingWithoutLeavingSavesEveryTime() {
        // Paused, then Home, the screensaver, the TV asleep: the screen stops without leaving, and saves.
        val s = Screen()
        s.stop()
        s.positionMs = 21 * 60_000L
        s.stop()
        assertEquals(listOf(20 * 60_000L, 21 * 60_000L), s.written)
        // Then leaving with Back: once more, not twice.
        s.leave()
        s.stop()
        assertEquals(listOf(20 * 60_000L, 21 * 60_000L, 21 * 60_000L), s.written)
    }
}
