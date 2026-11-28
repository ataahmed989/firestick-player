package app.fireplayer.subs

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Test

class PictureTextTest {

    private fun readingThreads() = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("FirePlayerOcr") }

    @Test fun `no reading thread runs before a picture comes to be read`() {
        val before = readingThreads().size
        // A player opens, a film starts and plays, its tracks are chosen, it's left: no picture subtitle came.
        val text = PictureText(ContextWrapper(null)) {}
        text.holdFor(5_000)
        text.go()
        text.showTrack(null)
        text.discardAhead()
        text.trimIdle()
        assertEquals(before, readingThreads().size)
        text.release()
        assertEquals(before, readingThreads().size)
    }
}
