package app.fireplayer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSessionTest {

    /** Records what the session asked of the player. */
    private class FakeEngine : LiveSession.Engine {
        val log = ArrayList<String>()
        override fun open(source: LiveSession.Source) { log += "open ${source.key}" }
        override fun close() { log += "close" }
    }

    private val engine = FakeEngine()
    private val budget = ConnectionBudget()
    private val session = LiveSession(engine, budget)
    private fun src(key: String, account: String = "acct", limit: Int = 1, url: String = "http://x/$key.ts") = LiveSession.Source(key, url, account, limit)

    @Test fun tuningTheSameChannelAgainDoesNothing() {
        assertSame(LiveSession.Tune.Started, session.tune(src("a")))
        assertSame(LiveSession.Tune.AlreadyPlaying, session.tune(src("a")))
        assertEquals(listOf("open a"), engine.log) // moving between miniature and full screen never retunes
        assertEquals("a", session.playingKey)
    }

    @Test fun zappingOnAOneConnectionAccountReusesItsConnection() {
        session.tune(src("a"))
        assertSame(LiveSession.Tune.Started, session.tune(src("b")))
        assertEquals(1, budget.inUse("acct"))
        assertEquals("b", session.playingKey)
    }

    @Test fun anotherPlayerHoldingTheOnlyConnectionBlocksTuning() {
        assertTrue(budget.acquire("acct", 1, "multiview-tile"))
        assertSame(LiveSession.Tune.NoConnection, session.tune(src("a")))
        assertTrue(engine.log.isEmpty())
        assertNull(session.playingKey)
    }

    @Test fun aFailedZapKeepsTheOldChannelAndItsLease() {
        session.tune(src("a", account = "one"))
        assertTrue(budget.acquire("two", 1, "other"))
        assertSame(LiveSession.Tune.NoConnection, session.tune(src("b", account = "two")))
        assertEquals("a", session.playingKey)
        assertEquals(1, budget.inUse("one"))
    }

    @Test fun stoppingTheScreenReleasesTheStreamAndResumeBringsItBack() {
        session.tune(src("a"))
        session.suspend()
        assertEquals(LiveSession.State.SUSPENDED, session.state)
        assertEquals(0, budget.inUse("acct")) // the provider connection is free while the app is in the background
        assertNull(session.playingKey)
        assertTrue(session.resume())
        assertEquals(listOf("open a", "close", "open a"), engine.log)
        assertEquals(1, budget.inUse("acct"))
        assertFalse(session.resume()) // already playing
    }

    @Test fun stopForgetsTheChannel() {
        session.tune(src("a"))
        session.stop()
        assertEquals(LiveSession.State.IDLE, session.state)
        assertNull(session.source)
        assertFalse(session.resume())
        assertEquals(0, budget.inUse("acct"))
        session.stop() // twice is harmless
        assertEquals(listOf("open a", "close"), engine.log)
    }

    @Test fun suspendWhileIdleDoesNothing() {
        session.suspend()
        assertEquals(LiveSession.State.IDLE, session.state)
        assertTrue(engine.log.isEmpty())
    }

    @Test fun aChangedLinkForTheSameChannelReopens() {
        session.tune(src("a", url = "http://x/a.ts?t=1"))
        assertSame(LiveSession.Tune.Started, session.tune(src("a", url = "http://x/a.ts?t=2")))
        assertEquals(listOf("open a", "open a"), engine.log)
    }

    @Test fun budgetLimits() {
        assertTrue(budget.acquire("x", 0, "a")) // unknown allowance: one at a time
        assertFalse(budget.acquire("x", 0, "b"))
        assertTrue(budget.acquire("y", 2, "a"))
        assertTrue(budget.acquire("y", 2, "b"))
        assertFalse(budget.acquire("y", 2, "c"))
        budget.release("y", "a")
        assertTrue(budget.acquire("y", 2, "c"))
        budget.release("nobody", "z") // releasing what isn't held is harmless
    }
}
