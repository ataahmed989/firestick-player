package app.fireplayer.vod.sources

import app.fireplayer.data.CredentialChecks.State
import app.fireplayer.vod.Addon
import app.fireplayer.vod.Failure
import app.fireplayer.vod.FailureKind
import app.fireplayer.vod.sources.AddonCheck.Outcome
import app.fireplayer.vod.sources.SourceFixtures.H1
import app.fireplayer.vod.sources.SourceFixtures.H2
import app.fireplayer.vod.sources.SourceFixtures.answer
import app.fireplayer.vod.sources.SourceFixtures.busyRow
import app.fireplayer.vod.sources.SourceFixtures.mediaFusion
import app.fireplayer.vod.sources.SourceFixtures.torrentio
import app.fireplayer.vod.sources.SourceFixtures.torrentioKeyError
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** The add-on status's pure rules: verdicts from answers, the failure table's words, the debrid service, the note. */
class AddonCheckTest {

    private fun check(addon: Addon, json: String) = AddonCheck.fromAnswer(json, StreamParser.parse(addon, json, null))

    // ---- The check's answers ----

    @Test fun aManifestWithAnIdIsAnAddOnsAndAnythingElseIsNot() {
        assertNull(AddonCheck.fromManifest("""{"id":"com.stremio.torrentio.addon","version":"0.0.15","resources":["stream"]}"""))
        assertEquals(Outcome(State.NOT_ACCEPTED, AddonCheck.NOT_AN_ADDON), AddonCheck.fromManifest("<html><body>Not found</body></html>"))
        assertEquals(Outcome(State.NOT_ACCEPTED, AddonCheck.NOT_AN_ADDON), AddonCheck.fromManifest("""{"name":"no id"}"""))
    }

    @Test fun cachedSourcesForTheTestFilmMeanWorkingWithTheirDebridService() {
        assertEquals(Outcome(State.WORKING, service = "Real-Debrid"), check(Addon.TORRENTIO, answer(torrentio(H1, ready = false), torrentio(H2))))
        assertEquals(Outcome(State.WORKING, service = "Real-Debrid"), check(Addon.MEDIAFUSION, answer(mediaFusion(H1))))
    }

    @Test fun aDebridErrorRowIsMappedThroughTheFailureTable() {
        assertEquals(Outcome(State.NOT_ACCEPTED, AddonCheck.KEY_REFUSED, "Real-Debrid"), check(Addon.TORRENTIO, answer(torrentioKeyError())))
        // MediaFusion's notices, by their videos' names.
        val token = JSONObject().put("name", "MediaFusion").put("url", "https://mf.invalid/static/exceptions/invalid_token.mp4")
        assertEquals(State.NOT_ACCEPTED, check(Addon.MEDIAFUSION, answer(token)).state)
        assertEquals(AddonCheck.KEY_REFUSED, check(Addon.MEDIAFUSION, answer(token)).problem)
        val config = JSONObject().put("name", "MediaFusion").put("url", "https://mf.invalid/static/exceptions/invalid_config.mp4")
        assertEquals(Outcome(State.NOT_ACCEPTED, AddonCheck.REDO), check(Addon.MEDIAFUSION, answer(config)))
        val down = JSONObject().put("name", "MediaFusion").put("url", "https://mf.invalid/static/exceptions/debrid_service_down_error.mp4")
        assertEquals(Outcome(State.UNREACHABLE, AddonCheck.DEBRID_DOWN), check(Addon.MEDIAFUSION, answer(down)))
    }

    @Test fun whatKeepsTheCheckFromConfirmingIsSaid() {
        assertEquals(Outcome(State.UNREACHABLE, AddonCheck.ADDON_BUSY), check(Addon.MEDIAFUSION, answer(busyRow())))
        assertEquals(Outcome(State.UNREACHABLE, AddonCheck.NONE_CACHED, "Real-Debrid"), check(Addon.TORRENTIO, answer(torrentio(H1, ready = false))))
        assertEquals(Outcome(State.UNREACHABLE, AddonCheck.NO_RESULTS), check(Addon.TORRENTIO, answer()))
        assertEquals(Outcome(State.UNREACHABLE, AddonCheck.UNREADABLE), check(Addon.TORRENTIO, "<html>oops</html>"))
        // Torrentio without a debrid service: torrents only, nothing this TV can play.
        val torrent = JSONObject().put("name", "Torrentio\n4k").put("title", "Film.2160p").put("infoHash", H1).put("fileIdx", 0)
        assertEquals(Outcome(State.NOT_ACCEPTED, AddonCheck.NO_DEBRID), check(Addon.TORRENTIO, answer(torrent)))
    }

    @Test fun aFailedRequestIsSaidByItsKindOnly() {
        assertEquals(Outcome(State.UNREACHABLE, AddonCheck.NO_INTERNET), AddonCheck.fromError(SocketTimeoutException(), online = false))
        assertEquals(Outcome(State.UNREACHABLE, AddonCheck.NOT_ANSWERING), AddonCheck.fromError(SocketTimeoutException(), online = true))
        assertEquals(Outcome(State.UNREACHABLE, AddonCheck.NOT_ANSWERING), AddonCheck.fromError(IOException("https://x.invalid/KEY"), online = true))
        assertEquals(Outcome(State.UNREACHABLE, AddonCheck.ADDON_DOWN), AddonCheck.fromError(StreamsHttp.Status(503), online = true))
        assertEquals(Outcome(State.NOT_ACCEPTED, AddonCheck.NOT_RECOGNISED), AddonCheck.fromError(StreamsHttp.Status(404), online = true))
        assertEquals(Outcome(State.UNREACHABLE, AddonCheck.ADDON_BUSY), AddonCheck.fromError(StreamsHttp.Busy(), online = true))
        assertEquals(Outcome(State.UNREACHABLE, AddonCheck.UNREADABLE), AddonCheck.fromError(IllegalStateException(), online = true))
    }

    @Test fun theFailureTableSaysWhichFailuresAreTheAddOnsOrItsDebridServices() {
        assertEquals(Outcome(State.NOT_ACCEPTED, AddonCheck.KEY_REFUSED), AddonCheck.fromFailure(FailureKind.ACCOUNT))
        assertEquals(Outcome(State.NOT_ACCEPTED, AddonCheck.REDO), AddonCheck.fromFailure(FailureKind.CONFIG))
        assertEquals(Outcome(State.NOT_ACCEPTED, AddonCheck.LIMIT), AddonCheck.fromFailure(FailureKind.LIMITS))
        assertEquals(Outcome(State.UNREACHABLE, AddonCheck.DEBRID_DOWN), AddonCheck.fromFailure(FailureKind.OUTAGE))
        assertEquals(Outcome(State.UNREACHABLE, AddonCheck.DEBRID_BUSY), AddonCheck.fromFailure(FailureKind.RATE_LIMITED))
        assertEquals(Outcome(State.UNREACHABLE, AddonCheck.NOT_ANSWERING), AddonCheck.fromFailure(FailureKind.ADDON_DOWN))
        // One file, this TV or the network: not the add-on's.
        for (k in listOf(FailureKind.FILE, FailureKind.NOT_READY, FailureKind.FORMAT, FailureKind.AUDIO, FailureKind.LINK_EXPIRED,
            FailureKind.JUST_ADDED, FailureKind.SLOW, FailureKind.NO_INTERNET, FailureKind.UNKNOWN)) {
            assertNull(k.name, AddonCheck.fromFailure(k))
        }
    }

    // ---- Real use, for free ----

    @Test fun aLookUpNotesWorkingOrAProblemAndNothingTheTitleExplains() {
        val cached = StreamParser.parse(Addon.TORRENTIO, answer(torrentio(H1)), null)
        assertEquals(Outcome(State.WORKING, service = "Real-Debrid"), AddonCheck.fromLookup(cached, null, online = true))
        val refused = StreamParser.parse(Addon.TORRENTIO, answer(torrentioKeyError()), null)
        assertEquals(Outcome(State.NOT_ACCEPTED, AddonCheck.KEY_REFUSED, "Real-Debrid"), AddonCheck.fromLookup(refused, null, online = true))
        assertEquals(Outcome(State.UNREACHABLE, AddonCheck.NOT_ANSWERING), AddonCheck.fromLookup(null, SocketTimeoutException(), online = true))
        assertEquals(Outcome(State.UNREACHABLE, AddonCheck.ADDON_DOWN), AddonCheck.fromLookup(null, StreamsHttp.Status(502), online = true))
        // An empty or uncached answer, a 404 for one title, a busy add-on, no network: nothing noted.
        assertNull(AddonCheck.fromLookup(StreamParser.parse(Addon.TORRENTIO, answer(), null), null, online = true))
        assertNull(AddonCheck.fromLookup(StreamParser.parse(Addon.TORRENTIO, answer(torrentio(H1, ready = false)), null), null, online = true))
        assertNull(AddonCheck.fromLookup(null, StreamsHttp.Status(404), online = true))
        assertNull(AddonCheck.fromLookup(null, StreamsHttp.Busy(), online = true))
        assertNull(AddonCheck.fromLookup(StreamParser.parse(Addon.MEDIAFUSION, answer(busyRow()), null), null, online = true))
        assertNull(AddonCheck.fromLookup(null, SocketTimeoutException(), online = false))
    }

    @Test fun aPlaysLinkCheckNotesTheServiceItStreamsFrom() {
        assertEquals(Outcome(State.WORKING, service = "Real-Debrid"), AddonCheck.fromLinkCheck("42.download.real-debrid.com", null))
        // Streamed through the add-on's own host: Working, the service not told.
        assertEquals(Outcome(State.WORKING), AddonCheck.fromLinkCheck("someone-mediafusion.elfhosted.com", null))
        assertEquals(Outcome(State.NOT_ACCEPTED, AddonCheck.LIMIT), AddonCheck.fromLinkCheck(null, Failure(FailureKind.LIMITS, Addon.TORRENTIO)))
        assertNull(AddonCheck.fromLinkCheck(null, Failure(FailureKind.FILE, Addon.TORRENTIO, "failed_rar")))
    }

    // ---- The debrid service ----

    @Test fun theDebridServiceIsReadFromTheLinksPathOrTheStreamsName() {
        assertEquals("Real-Debrid", Debrids.of(null, SourceFixtures.tioLink(H1, 0)))
        assertEquals("TorBox", Debrids.of(null, "https://torrentio.invalid/resolve/torbox/FAKEKEY/$H1/null/0/f.mkv"))
        assertEquals("TorBox", Debrids.of("⚡️ [TB]\nTorz\n2160p", "https://st.invalid/stremio/torz/FAKE/play/x"))
        assertEquals("TorBox", Debrids.of("MediaFusion | ElfHosted 🧲 TRB ⚡️ 4k", null))
        assertEquals("AllDebrid", Debrids.of("[AD download] Torrentio\n1080p", null))
        assertEquals("Premiumize", Debrids.of("[PM+] Torrentio\n4k", null))
        assertEquals("Real-Debrid", Debrids.of("Torrentio\nRD error", null))
        // Nothing that names one: no guess.
        assertNull(Debrids.of("Torrentio\n4k DV | HDR", "https://tio.invalid/FAKEKEY/stream/x"))
        assertNull(Debrids.of("Film.2021.2160p.WEB-DL.DDP5.1", null))
        assertNull(Debrids.of(null, null))
    }

    @Test fun theDebridServiceIsReadFromTheHostAFilmStreamsFrom() {
        assertEquals("Real-Debrid", Debrids.byHost("42.download.real-debrid.com"))
        assertEquals("TorBox", Debrids.byHost("store-031.weur.tb-cdn.st"))
        assertEquals("Premiumize", Debrids.byHost("premiumize.me"))
        assertNull(Debrids.byHost("notreal-debrid.com"))
        assertNull(Debrids.byHost("someone-mediafusion.elfhosted.com"))
        assertNull(Debrids.byHost(null))
        // The log line names them from the same list.
        assertEquals("tb-cdn.st (TorBox)", SourceLog.site("store-031.weur.tb-cdn.st"))
    }

    // ---- Wording ----

    @Test fun theNoteSaysTheServiceAndWhenOrTheProblem() {
        val now = 10L * 24 * 60 * 60_000
        val min = 60_000L
        assertEquals("Real-Debrid · checked 5 min ago", AddonCheck.detail(State.WORKING, null, "Real-Debrid", now - 5 * min, now))
        assertEquals("checked just now", AddonCheck.detail(State.WORKING, null, null, now - 20_000, now))
        assertEquals("debrid key refused", AddonCheck.detail(State.NOT_ACCEPTED, AddonCheck.KEY_REFUSED, "Real-Debrid", now, now))
        assertEquals("add-on not answering", AddonCheck.detail(State.UNREACHABLE, AddonCheck.NOT_ANSWERING, null, now, now))
        assertNull(AddonCheck.detail(State.NOT_CHECKED, null, null, 0, now))
        assertNull(AddonCheck.detail(State.CHECKING, null, null, 0, now))
        assertNull(AddonCheck.detail(State.NOT_SET, null, null, 0, now))
        assertEquals("checked 3 h ago", AddonCheck.checkedAgo(now - 3 * 60 * min - 5 * min, now))
        assertEquals("checked 1 day ago", AddonCheck.checkedAgo(now - 30 * 60 * min, now))
        assertEquals("checked 3 days ago", AddonCheck.checkedAgo(now - 3 * 24 * 60 * min, now))
        assertNull(AddonCheck.checkedAgo(0, now))
        // The states read as the keys' do.
        assertEquals(listOf("Not set", "Set · not checked", "Checking…", "Working", "Not accepted", "Couldn't check"), State.entries.map { it.label })
    }
}
