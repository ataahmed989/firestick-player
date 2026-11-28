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

    @Test fun `a guide from another host never touches a live channel`() {
        val playing = Playing(stream, "p1", film = false, liveScreen = true)
        assertEquals(Action.GO, SyncPlan.decide(Trigger.MANUAL, "p1", guideElsewhere, provider, playing))
        assertEquals(Action.GO, SyncPlan.decide(Trigger.AUTO, "p1", guideElsewhere, provider, playing))
    }

    @Test fun `no automatic update goes anywhere while a film plays`() {
        val film = Playing(null, null, film = true)
        // An EPG from a third-party address (a user-set EPG, an M3U's url-tvg) waits for the film too.
        assertEquals(Action.DEFER, SyncPlan.decide(Trigger.AUTO, "p1", guideElsewhere, provider, film))
        assertEquals(Action.DEFER, SyncPlan.decide(Trigger.AUTO, "p1", playlistReq, provider, film))
        assertEquals(Action.DEFER, SyncPlan.decide(Trigger.AUTO, "p1", guideElsewhere, provider, Playing(stream, "p1", film = true, liveScreen = true)))
        // The viewer asked for it: it goes.
        assertEquals(Action.GO, SyncPlan.decide(Trigger.MANUAL, "p1", guideElsewhere, provider, film))
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

    @Test fun `a source that keeps failing waits longer each time, never past its own interval`() {
        val h = 3_600_000L
        val half = SyncPlan.RETRY_AFTER_MS
        // Never failed (or it worked since) and one failure: half an hour, as before.
        assertEquals(half, SyncPlan.retryAfterMs(0, 24 * h))
        assertEquals(half, SyncPlan.retryAfterMs(1, 24 * h))
        // Then 1 h, 2 h, 4 h ... up to the EPG's (or playlist's) update interval.
        assertEquals(2 * half, SyncPlan.retryAfterMs(2, 24 * h))
        assertEquals(4 * half, SyncPlan.retryAfterMs(3, 24 * h))
        assertEquals(24 * h, SyncPlan.retryAfterMs(7, 24 * h))
        assertEquals(24 * h, SyncPlan.retryAfterMs(1000, 24 * h))
        assertEquals(6 * h, SyncPlan.retryAfterMs(20, 6 * h))
        // An interval shorter than half an hour still waits half an hour after a failure.
        assertEquals(half, SyncPlan.retryAfterMs(5, 60_000L))
    }

    @Test fun `an EPG that failed at the last start isn't downloaded again at this one`() {
        val h = 3_600_000L
        val start = 100 * h
        // It failed 3 times in a row, the last 90 minutes ago: it waits 2 hours from then.
        assertFalse(SyncPlan.isDue(now = start, lastSuccess = 0, intervalMs = 24 * h, lastTried = start - 90 * 60_000L, failures = 3))
        assertTrue(SyncPlan.isDue(now = start, lastSuccess = 0, intervalMs = 24 * h, lastTried = start - 2 * h, failures = 3))
        // Once it has worked, its interval alone decides.
        assertFalse(SyncPlan.isDue(now = start, lastSuccess = start - h, intervalMs = 24 * h, lastTried = 0, failures = 0))
        assertTrue(SyncPlan.isDue(now = start, lastSuccess = start - 24 * h, intervalMs = 24 * h, lastTried = 0, failures = 0))
    }

    @Test fun `a last try in the future (the clock was put back) holds nothing back`() {
        val h = 3_600_000L
        assertTrue(SyncPlan.isDue(now = 30 * h, lastSuccess = 0, intervalMs = 24 * h, lastTried = 40 * h, failures = 5))
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

    // ---- Work under way when something comes in front ----

    @Test fun `a film coming in front stops automatic work under way, never the viewer's own`() {
        assertTrue(SyncPlan.jobGoesOn(Trigger.AUTO, film = false, requestStopped = false))
        assertFalse(SyncPlan.jobGoesOn(Trigger.AUTO, film = true, requestStopped = false))
        assertFalse(SyncPlan.jobGoesOn(Trigger.AUTO, film = false, requestStopped = true))
        // The Update button (or a playlist just saved) runs to the end, film or not.
        assertTrue(SyncPlan.jobGoesOn(Trigger.MANUAL, film = true, requestStopped = true))
    }

    @Test fun `a request under way stops when it would now wait`() {
        val film = Playing(null, null, film = true)
        // A film: every automatic request, the guide from elsewhere too (tens of MB to read and store).
        assertFalse(SyncPlan.requestGoesOn(Trigger.AUTO, "p1", guideElsewhere, provider, film))
        assertFalse(SyncPlan.requestGoesOn(Trigger.AUTO, "p1", playlistReq, provider, film))
        // The provider's own channel plays, or Live TV is about to tune: its connection is the channel's.
        val ownChannel = Playing(stream, "p1", film = false)
        val tuning = Playing(null, null, film = false, liveScreen = true)
        assertFalse(SyncPlan.requestGoesOn(Trigger.AUTO, "p1", playlistReq, provider, ownChannel))
        assertFalse(SyncPlan.requestGoesOn(Trigger.AUTO, "p1", playlistReq, provider, tuning))
        // Live TV's guide from another host goes on while it plays (live runs for hours; its listings must come).
        assertTrue(SyncPlan.requestGoesOn(Trigger.AUTO, "p1", guideElsewhere, provider, ownChannel))
        assertTrue(SyncPlan.requestGoesOn(Trigger.AUTO, "p1", guideElsewhere, provider, tuning))
        // Another provider's channel never stops it; nothing playing, nothing stops.
        assertTrue(SyncPlan.requestGoesOn(Trigger.AUTO, "p1", playlistReq, provider, Playing("http://other-iptv.net/live/a/b/9.ts", "p9", film = false)))
        assertTrue(SyncPlan.requestGoesOn(Trigger.AUTO, "p1", playlistReq, provider, nothing))
        // The viewer's own update goes on whatever plays.
        assertTrue(SyncPlan.requestGoesOn(Trigger.MANUAL, "p1", playlistReq, provider, Playing(stream, "p1", film = true, liveScreen = true)))
    }

    @Test fun `an automatic update gives the provider's connection back once its request is over`() {
        // An M3U playlist (no allowance known: one connection) whose url-tvg guide is on another host.
        val budget = app.fireplayer.playback.ConnectionBudget()
        val leases = SyncPlan.Leases()
        val m3u = "http://line.example.com:8080/get.php?type=m3u_plus"
        // The playlist request goes to the provider: the update takes its lease, and Live TV can't tune meanwhile.
        assertTrue(SyncPlan.usesProvider(provider, m3u) && !leases.holds("p1"))
        assertTrue(budget.acquire("p1", 0, "update"))
        leases.took("p1")
        assertFalse(budget.acquire("p1", 0, "live"))
        // The playlist is in: the lease goes back before the guide from elsewhere is read (minutes on a Stick).
        for (account in leases.requestDone(Trigger.AUTO)) budget.release(account, "update")
        assertFalse(leases.holds("p1"))
        assertFalse(SyncPlan.usesProvider(provider, guideElsewhere))
        // Live TV tunes one of the playlist's channels while that guide is read; nothing of the update stops it.
        assertTrue(budget.acquire("p1", 0, "live"))
        assertTrue(SyncPlan.requestGoesOn(Trigger.AUTO, "p1", guideElsewhere, provider, Playing(stream, "p1", film = false)))
        assertTrue(leases.end().isEmpty())
        // The next request to the provider takes the lease again, or waits while its channel plays.
        assertEquals(Action.DEFER, SyncPlan.decide(Trigger.AUTO, "p1", playlistReq, provider, Playing(stream, "p1", film = false)))
    }

    @Test fun `the viewer's own update keeps the provider's connection to the end`() {
        val leases = SyncPlan.Leases()
        leases.took("p1")
        // A channel it let go of comes back once, after the whole update, not between its playlist and its guide.
        assertTrue(leases.requestDone(Trigger.MANUAL).isEmpty())
        assertTrue(leases.holds("p1"))
        leases.took("p2")
        assertEquals(listOf("p1", "p2"), leases.end())
        assertFalse(leases.holds("p1") || leases.holds("p2"))
        assertTrue(leases.end().isEmpty())
    }

    @Test fun `failures in a row grow the wait, a put-off try undoes its count, a success clears it`() {
        val h = 3_600_000L
        val now = 30 * h
        val tries = SyncPlan.Tries()
        tries.began("e:p1", now)
        tries.began("e:p1", now + h)
        assertEquals(2, tries.failures("e:p1"))
        // Put off part-way: neither its time nor its failure counts.
        tries.began("e:p1", now + 2 * h)
        tries.putOff("e:p1")
        assertEquals(2, tries.failures("e:p1"))
        assertEquals(now + h, tries.last("e:p1"))
        // Two failures in a row: a 60-minute wait, not 30.
        assertFalse(SyncPlan.isDue(now + h + 45 * 60_000, 0, 24 * h, tries.last("e:p1"), tries.failures("e:p1")))
        assertTrue(SyncPlan.isDue(now + 2 * h, 0, 24 * h, tries.last("e:p1"), tries.failures("e:p1")))
        tries.began("e:p1", now + 3 * h)
        tries.succeeded("e:p1")
        assertEquals(0, tries.failures("e:p1"))
        assertEquals(0L, tries.last("e:p1"))
    }

    @Test fun `a request put off counts as never tried, a failed one keeps its wait`() {
        val h = 3_600_000L
        val now = 30 * h
        val tries = SyncPlan.Tries()
        // Never tried: due.
        assertEquals(0L, tries.last("e:p1"))
        // Begun, then put off part-way for a film: due again at once, no retry wait.
        tries.began("e:p1", now)
        tries.putOff("e:p1")
        assertEquals(0L, tries.last("e:p1"))
        assertTrue(SyncPlan.isDue(now + 60_000, lastSuccess = 0, intervalMs = 24 * h, lastTried = tries.last("e:p1")))
        // Begun and failed: it waits before being asked again.
        tries.began("e:p1", now)
        assertFalse(SyncPlan.isDue(now + 60_000, lastSuccess = 0, intervalMs = 24 * h, lastTried = tries.last("e:p1")))
        // That failure's wait stays when the next try is put off: only the put-off try is undone.
        tries.began("e:p1", now + SyncPlan.RETRY_AFTER_MS)
        tries.putOff("e:p1")
        assertEquals(now, tries.last("e:p1"))
        // A put-off for another key than the one begun last changes nothing (its request had finished).
        tries.began("p:p1", now)
        tries.putOff("e:p2")
        assertEquals(now, tries.last("p:p1"))
        tries.putOff("p:p1")
        tries.putOff("p:p1")
        assertEquals(0L, tries.last("p:p1"))
    }

    @Test fun `a stop mid-guide is told apart from a broken file`() {
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv>")
            repeat(3000) { append("<programme start=\"20261117180000 +0000\" stop=\"20261117190000 +0000\" channel=\"a\"><title>T$it</title></programme>") }
            append("</tv>")
        }.toByteArray()
        // A film comes in front at the Nth read: while the reader looks for the encoding (it wraps what the stream
        // throws as an invalid stream of its own), and half-way through the listings.
        for (stopAt in listOf(1, 3, 40)) {
            var reads = 0
            val counted = CountingInputStream(java.io.ByteArrayInputStream(xml)) { if (++reads >= stopAt) throw SyncPlan.PutOff() }
            var kept = 0
            val thrown = runCatching {
                val parser = org.kxml2.io.KXmlParser()
                parser.setInput(counted, null)
                XmltvReader.read(parser, setOf("a"), 0L, Long.MAX_VALUE) { kept++ }
            }.exceptionOrNull()
            assertTrue("stopped at read $stopAt", thrown != null && counted.putOff)
            assertTrue(kept < 3000)
        }
        // Read through with nothing in front: every programme, no stop.
        val whole = CountingInputStream(java.io.ByteArrayInputStream(xml)) {}
        var all = 0
        val parser = org.kxml2.io.KXmlParser()
        parser.setInput(whole, null)
        XmltvReader.read(parser, setOf("a"), 0L, Long.MAX_VALUE) { all++ }
        assertEquals(3000, all)
        assertFalse(whole.putOff)
        // A download cut off half-way (the connection reset) is a failure, never taken for a stop.
        val cut = object : java.io.InputStream() {
            val inner = java.io.ByteArrayInputStream(xml)
            var left = xml.size / 2
            override fun read(): Int = if (left-- <= 0) throw java.io.IOException("reset") else inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) throw java.io.IOException("reset")
                return inner.read(b, off, minOf(len, left)).also { left -= it }
            }
        }
        val broken = CountingInputStream(cut) {}
        val failed = runCatching {
            val p2 = org.kxml2.io.KXmlParser()
            p2.setInput(broken, null)
            XmltvReader.read(p2, setOf("a"), 0L, Long.MAX_VALUE) {}
        }.exceptionOrNull()
        assertTrue(failed != null && failed !is SyncPlan.PutOff)
        assertFalse(broken.putOff)
    }

    @Test fun `streamed M3U knows its header even without channels`() {
        val parsed = M3uParser.parse(sequenceOf("#EXTM3U url-tvg=\"http://g/x.xml\"", ""))
        assertTrue(parsed.header)
        assertEquals(0, parsed.entries.size)
        assertFalse(M3uParser.parse(sequenceOf("<html>nope</html>")).header)
    }
}
