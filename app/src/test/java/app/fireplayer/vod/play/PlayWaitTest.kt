package app.fireplayer.vod.play

import app.fireplayer.vod.Addon
import app.fireplayer.vod.AddonState
import app.fireplayer.vod.LookupWhy
import app.fireplayer.vod.SourceList
import app.fireplayer.vod.SourceTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayWaitTest {
    private val repo = FakeSourcesRepository()
    private val ticker = FakeTicker()
    private val target = SourceTarget(Fixtures.film)
    private val played = ArrayList<SourceList>()
    private val ended = ArrayList<String?>()
    private val labels = ArrayList<String>()
    private var strip: String? = null

    private val wait = PlayWait(repo, ticker, object : PlayWait.Listener {
        override fun onWaiting(label: String, fraction: Float, strip: String?) {
            labels += label
            this@PlayWaitTest.strip = strip
        }
        override fun onPlay(target: SourceTarget, list: SourceList) { played += list }
        override fun onEnded(message: String?) { ended += message }
    })

    private fun asking(sources: Int, mfSince: Long = ticker.time): SourceList {
        val s = (1..sources).map { Fixtures.source(it) }
        return Fixtures.list(target, s, states = mapOf(
            Addon.TORRENTIO to AddonState.Answered(sources),
            Addon.MEDIAFUSION to AddonState.Asking(mfSince, mfSince + Addon.MEDIAFUSION.answerLimitMs),
        ))
    }

    @Test
    fun aCompleteListPlaysAtOnceWithoutAsking() {
        repo.kept[target.key] = Fixtures.list(target, listOf(Fixtures.source(1)))
        wait.press(target)
        assertEquals(1, played.size)
        assertTrue(repo.asked.isEmpty())
        assertFalse(wait.isWaiting)
    }

    @Test
    fun itWaitsForBothAddonsThenPlays() {
        wait.press(target)
        assertEquals(listOf(target to LookupWhy.PLAY), repo.asked)
        assertTrue(wait.isWaiting)
        repo.answer(asking(2))
        assertTrue(played.isEmpty())
        assertTrue(strip!!.contains("MediaFusion still looking"))
        ticker.advance(3_000)
        assertEquals("Waiting for MediaFusion · 3 s", labels.last())
        repo.answer(Fixtures.list(target, listOf(Fixtures.source(1), Fixtures.source(2))))
        assertEquals(1, played.size)
        assertFalse(wait.isWaiting)
        assertEquals(0, ticker.pending)
    }

    @Test
    fun okAgainPlaysTheBestSoFar() {
        wait.press(target)
        repo.answer(asking(1))
        assertTrue(wait.press(target))
        assertEquals(1, played.size)
        assertEquals("k1", played[0].best?.key)
    }

    @Test
    fun aCompleteListWithNothingSaysSoWithoutThePlayer() {
        repo.kept[target.key] = Fixtures.list(target, emptyList())
        wait.press(target)
        assertTrue(played.isEmpty())
        assertEquals(listOf<String?>("No sources found"), ended)
        assertFalse(wait.isWaiting)
    }

    @Test
    fun okAgainWithOnlySourcesNotOnRealDebridKeepsWaiting() {
        wait.press(target)
        val nr = Fixtures.source(1, readiness = app.fireplayer.vod.Readiness.NOT_READY, group = app.fireplayer.vod.SourceGroup.NOT_ON_RD)
        repo.answer(Fixtures.list(target, listOf(nr), states = mapOf(
            Addon.TORRENTIO to AddonState.Answered(1),
            Addon.MEDIAFUSION to AddonState.Asking(ticker.time, ticker.time + Addon.MEDIAFUSION.answerLimitMs),
        )))
        assertTrue(wait.press(target))
        assertTrue(played.isEmpty())
        assertTrue(wait.isWaiting)
    }

    @Test
    fun okAgainWithNothingYetKeepsWaiting() {
        wait.press(target)
        assertTrue(wait.press(target))
        assertTrue(played.isEmpty())
        assertTrue(wait.isWaiting)
    }

    @Test
    fun backCancelsAndALateAnswerDoesNothing() {
        wait.press(target)
        assertTrue(wait.cancel())
        assertEquals(listOf<String?>(null), ended)
        assertEquals(listOf(target), repo.cancelled)
        repo.answer(Fixtures.list(target, listOf(Fixtures.source(1))))
        ticker.advance(60_000)
        assertTrue(played.isEmpty())
        assertFalse(wait.cancel())
    }

    @Test
    fun aStuckAnswerDoesntHoldPlayForever() {
        wait.press(target)
        repo.answer(asking(1, mfSince = ticker.time))
        ticker.advance(Addon.MEDIAFUSION.answerLimitMs + PlayRules.GRACE_MS + PlayWait.TICK_MS)
        assertEquals(1, played.size)
    }

    @Test
    fun nothingFoundSaysSoAndPlaysNothing() {
        wait.press(target)
        repo.answer(Fixtures.list(target, emptyList()))
        assertTrue(played.isEmpty())
        assertEquals(listOf<String?>("No sources found"), ended)
    }

    @Test
    fun anotherTargetEndsTheFirstWait() {
        val episode = SourceTarget(Fixtures.show, app.fireplayer.vod.EpisodeRef(1, 2))
        wait.press(target)
        wait.press(episode)
        assertEquals(episode, wait.waitingFor)
        repo.answer(Fixtures.list(target, listOf(Fixtures.source(1))))
        assertTrue(played.isEmpty())
        repo.answer(Fixtures.list(episode, listOf(Fixtures.source(2))))
        assertEquals(episode, played.single().target)
        assertNull(wait.waitingFor)
    }
}
