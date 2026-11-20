package app.fireplayer.live

import app.fireplayer.live.SyncPlan.Action
import app.fireplayer.live.SyncPlan.Playing
import app.fireplayer.live.SyncPlan.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SyncPlanTest {

    private val provider = "http://Line.Example.com:8080"
    private val playlistReq = "http://line.example.com:8080/player_api.php?action=get_live_streams"
    private val guideElsewhere = "https://epg.other.org/guide.xml.gz"
    private val stream = "http://line.example.com:8080/live/u/p/1.ts"
    private val nothing = Playing(null, null, film = false)

    @Test fun `nothing playing - go`() {
        assertEquals(Action.GO, SyncPlan.decide(Trigger.MANUAL, "p1", playlistReq, provider, nothing))
        assertEquals(Action.GO, SyncPlan.decide(Trigger.AUTO, "p1", playlistReq, provider, nothing))
    }

    @Test fun `manual update while the provider's channel plays lets it go`() {
        val playing = Playing(stream, "p1", film = false)
        assertEquals(Action.LET_GO, SyncPlan.decide(Trigger.MANUAL, "p1", playlistReq, provider, playing))
    }

    @Test fun `same host under another playlist still counts as the provider`() {
        val playing = Playing(stream, "p2", film = false)
        assertEquals(Action.LET_GO, SyncPlan.decide(Trigger.MANUAL, "p1", playlistReq, provider, playing))
        assertEquals(Action.DEFER, SyncPlan.decide(Trigger.AUTO, "p1", playlistReq, provider, playing))
    }

    @Test fun `automatic update waits while the provider's channel plays`() {
        val playing = Playing(stream, "p1", film = false)
        assertEquals(Action.DEFER, SyncPlan.decide(Trigger.AUTO, "p1", playlistReq, provider, playing))
    }

    @Test fun `a guide from another host never touches playback`() {
        val playing = Playing(stream, "p1", film = true, liveScreen = true)
        assertEquals(Action.GO, SyncPlan.decide(Trigger.MANUAL, "p1", guideElsewhere, provider, playing))
        assertEquals(Action.GO, SyncPlan.decide(Trigger.AUTO, "p1", guideElsewhere, provider, playing))
    }

    @Test fun `another provider's channel is left alone`() {
        val playing = Playing("http://other-iptv.net/live/a/b/9.ts", "p9", film = false)
        assertEquals(Action.GO, SyncPlan.decide(Trigger.MANUAL, "p1", playlistReq, provider, playing))
        assertEquals(Action.GO, SyncPlan.decide(Trigger.AUTO, "p1", playlistReq, provider, playing))
    }

    @Test fun `automatic update waits while a film plays or live is about to tune`() {
        assertEquals(Action.DEFER, SyncPlan.decide(Trigger.AUTO, "p1", playlistReq, provider, Playing(null, null, film = true)))
        assertEquals(Action.DEFER, SyncPlan.decide(Trigger.AUTO, "p1", playlistReq, provider, Playing(null, null, film = false, liveScreen = true)))
        // The viewer asked: a film can't be playing then, and an idle live screen has nothing to let go.
        assertEquals(Action.GO, SyncPlan.decide(Trigger.MANUAL, "p1", playlistReq, provider, Playing(null, null, film = false, liveScreen = true)))
    }

    @Test fun `hosts compare without case, port or login`() {
        assertEquals("line.example.com", SyncPlan.hostOf("line.example.com:8080"))
        assertEquals("line.example.com", SyncPlan.hostOf("HTTP://user:pw@Line.Example.com/get.php?x=1"))
        assertTrue(SyncPlan.sameHost("http://a.tv:80/x", "https://A.TV/y"))
        assertFalse(SyncPlan.sameHost("http://a.tv/x", "http://b.tv/x"))
        assertFalse(SyncPlan.sameHost("", "http://b.tv/x"))
        assertNull(SyncPlan.hostOf("  "))
    }

    @Test fun `the channel comes back only when it is still the one waiting and the screen shows`() {
        assertTrue(SyncPlan.bringBack("k1", "k1", suspended = true, screenOn = true, leaseTaken = true))
        // The viewer tuned another channel (it plays) or closed live (nothing waits) or left the screen.
        assertFalse(SyncPlan.bringBack("k1", "k2", suspended = false, screenOn = true, leaseTaken = true))
        assertFalse(SyncPlan.bringBack("k1", null, suspended = false, screenOn = true, leaseTaken = true))
        assertFalse(SyncPlan.bringBack("k1", "k1", suspended = true, screenOn = false, leaseTaken = true))
        // A channel whose own comeback the update's lease refused comes back too; with no lease, nothing was ours.
        assertTrue(SyncPlan.bringBack(null, "k3", suspended = true, screenOn = true, leaseTaken = true))
        assertFalse(SyncPlan.bringBack(null, "k3", suspended = true, screenOn = true, leaseTaken = false))
    }

    @Test fun `due when the interval passed and not tried lately`() {
        val h = 3_600_000L
        assertTrue(SyncPlan.isDue(now = 25 * h, lastSuccess = 0, intervalMs = 24 * h, lastTried = 0))
        assertFalse(SyncPlan.isDue(now = 25 * h, lastSuccess = 2 * h, intervalMs = 24 * h, lastTried = 0))
        assertFalse(SyncPlan.isDue(now = 25 * h, lastSuccess = 0, intervalMs = 24 * h, lastTried = 25 * h - 60_000))
        assertTrue(SyncPlan.isDue(now = 25 * h, lastSuccess = 0, intervalMs = 24 * h, lastTried = 25 * h - SyncPlan.RETRY_AFTER_MS))
    }

    @Test fun `an empty download never empties what was stored`() {
        assertFalse(SyncPlan.mayReplaceChannels(0, 120))
        assertTrue(SyncPlan.mayReplaceChannels(0, 0))
        assertTrue(SyncPlan.mayReplaceChannels(80, 120))
        assertFalse(SyncPlan.mayReplaceGuide(0, 5000, sameAddress = true))
        assertTrue(SyncPlan.mayReplaceGuide(0, 5000, sameAddress = false))
        assertTrue(SyncPlan.mayReplaceGuide(10, 5000, sameAddress = true))
    }

    @Test fun `failures in plain words, never the address`() {
        assertEquals("No network, or the server address is wrong", SyncPlan.describe(UnknownHostException("secret.host")))
        assertEquals("The server took too long to answer", SyncPlan.describe(SocketTimeoutException()))
        assertTrue(SyncPlan.describe(SyncPlan.HttpStatus(403)).startsWith("The server refused"))
        assertEquals("The server had a problem (error 503)", SyncPlan.describe(SyncPlan.HttpStatus(503)))
        assertEquals("The file was empty", SyncPlan.describe(SyncPlan.Empty()))
        assertEquals("The file couldn't be read", SyncPlan.describe(IllegalStateException("http://u:p@x")))
    }

    @Test fun `streamed M3U knows its header even without channels`() {
        val parsed = M3uParser.parse(sequenceOf("#EXTM3U url-tvg=\"http://g/x.xml\"", ""))
        assertTrue(parsed.header)
        assertEquals(0, parsed.entries.size)
        assertFalse(M3uParser.parse(sequenceOf("<html>nope</html>")).header)
    }
}
