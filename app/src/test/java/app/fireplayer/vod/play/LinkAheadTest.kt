package app.fireplayer.vod.play

import app.fireplayer.vod.Cancel
import app.fireplayer.vod.PlayOrigin
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.StartAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A rest on what OK would play ([LinkAhead]): a second's rest, then its play's link is checked ahead; moving on drops it. */
class LinkAheadTest {
    private val ticker = FakeTicker()
    private val prepared = ArrayList<PlayRequest>()
    private var dropped = 0
    private var made = 0
    private val ahead = LinkAhead(ticker) { r ->
        prepared += r
        Cancel { dropped++ }
    }
    private val request = PlayRequest(SourceTarget(Fixtures.film), StartAt.At(60_000), PlayOrigin.CONTINUE_CARD)

    private fun make(): PlayRequest {
        made++
        return request
    }

    @Test
    fun aSecondsRestPreparesOnceAndMovingOnDropsIt() {
        ahead.rest("a") { make() }
        ticker.advance(999)
        assertTrue(prepared.isEmpty())
        // Nothing is worked out while focus moves: the play is made only once the rest is over.
        assertEquals(0, made)
        ticker.advance(1)
        assertEquals(listOf(request), prepared)
        // The same card again (focus told twice) changes nothing.
        ahead.rest("a") { make() }
        ticker.advance(5_000)
        assertEquals(1, prepared.size)
        assertEquals(0, dropped)
        ahead.leave()
        assertEquals(1, dropped)
    }

    @Test
    fun movingOnBeforeTheRestAsksNothing() {
        ahead.rest("a") { make() }
        ticker.advance(600)
        ahead.rest("b") { make() }
        ticker.advance(600)
        ahead.leave()
        ticker.advance(5_000)
        assertTrue(prepared.isEmpty())
        assertEquals(0, made)
        assertEquals(0, ticker.pending)
    }

    @Test
    fun aPlayNotKnownYetIsAskedForAgainWhenItIs() {
        var known = false
        ahead.rest("play") { if (known) request else null }
        ahead.ready()
        ticker.advance(1_000)
        assertTrue(prepared.isEmpty())
        known = true
        ahead.ready()
        assertEquals(1, prepared.size)
        // Once only.
        ahead.ready()
        assertEquals(1, prepared.size)
    }

    @Test
    fun anotherCardDropsTheOneBefore() {
        ahead.rest("a") { make() }
        ticker.advance(1_000)
        ahead.rest("b") { make() }
        assertEquals(1, dropped)
        ticker.advance(1_000)
        assertEquals(2, prepared.size)
    }
}
