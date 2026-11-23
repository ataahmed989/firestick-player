package app.fireplayer.vod.sources

import app.fireplayer.vod.Addon
import app.fireplayer.vod.AddonSave
import app.fireplayer.vod.AddonSettings
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.Opened
import app.fireplayer.vod.PlayOrigin
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.ReleaseKey
import app.fireplayer.vod.SecretUrl
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.StartAt
import app.fireplayer.vod.ThisTv
import app.fireplayer.vod.TvCapabilities
import app.fireplayer.vod.TvFit
import app.fireplayer.vod.TvPlan
import app.fireplayer.vod.play.FakeLibrary
import app.fireplayer.vod.play.FakeTicker
import app.fireplayer.vod.play.Fixtures
import app.fireplayer.vod.play.SourcePlayback
import app.fireplayer.vod.sources.SourceFixtures.H1
import app.fireplayer.vod.sources.SourceFixtures.H2
import app.fireplayer.vod.sources.SourceFixtures.H3
import app.fireplayer.vod.sources.SourceFixtures.answer
import app.fireplayer.vod.sources.SourceFixtures.torrentio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * What this connection was measured to sustain while a film kept stalling ([LineRate]), and what "Best for this TV"
 * and the session make of it: files it can't keep up with rank as "likely not" and say why, and the lighter source a
 * stalling film is switched to is the best one it sustains. Synthetic ".invalid" answers only.
 */
class LineRateTest {

    private var now = 1_700_000_000_000L
    private val store = object : LineRate.Store {
        var text: String? = null
        override fun read() = text
        override fun write(text: String?) {
            this.text = text
        }
    }
    private val line = LineRate(store) { now }

    // ---- The record

    @Test fun everyFilmMeasuredIsRememberedWhateverWasAnsweredAboutItsStalls() {
        assertNull(line.current())
        // The first film measured: kept, and the lists rank again.
        assertTrue(line.seen(47.2))
        assertEquals("47.2|1700000000000", store.text)
        assertEquals(47.2, line.current()!!, 0.001)
    }

    @Test fun aShortLineIsRememberedForThreeDays() {
        assertEquals(3L * 24 * 60 * 60 * 1000, LineRate.KEEP_MS)
        line.short(72.44)
        assertEquals("72.4|1700000000000", store.text)
        assertEquals(72.4, line.current()!!, 0.001)
        now += LineRate.KEEP_MS
        assertEquals(72.4, line.current()!!, 0.001)
        now += 1
        assertNull(line.current())
    }

    @Test fun theNewestMeasurementReplacesItUpOrDown() {
        line.short(72.0)
        // A faster evening lifts it.
        assertTrue(line.seen(85.0))
        assertEquals(85.0, line.current()!!, 0.001)
        // A slower one lowers it: the newest evidence counts.
        assertTrue(line.seen(47.0))
        assertEquals(47.0, line.current()!!, 0.001)
        // Within a twentieth: nothing written, nothing to rank again...
        now += 60_000
        assertFalse(line.seen(48.0))
        assertEquals("47.0|1700000000000", store.text)
        // ...unless what's kept is an hour old: written again (its time is the newest evidence's), still no rerank.
        now += LineRate.REFRESH_MS
        assertFalse(line.seen(48.0))
        assertEquals(48.0, line.current()!!, 0.001)
        // A stalling film's measurement replaces it too.
        line.short(60.0)
        assertEquals(60.0, line.current()!!, 0.001)
        // Nonsense never counts.
        assertFalse(line.seen(Double.NaN))
        assertFalse(line.seen(0.0))
        assertEquals(60.0, line.current()!!, 0.001)
    }

    @Test fun nonsenseIsNeverKeptOrRead() {
        line.short(0.0)
        line.short(-5.0)
        line.short(Double.NaN)
        assertNull(store.text)
        store.text = "abc|def"
        assertNull(line.current())
        store.text = "72.0"
        assertNull(line.current())
        // A clock set back: not trusted.
        store.text = "72.0|${now + 60_000}"
        assertNull(line.current())
        line.short(72.0)
        line.forget()
        assertNull(line.current())
    }

    @Test fun aMeasurementGoesWithTheSpeedTheListsAreRankedBy() {
        // Two requests running, setting on.
        assertTrue(LineRate.forTwo(built = true, settingOn = true, twoOpen = true))
        // The server keeps the film on one request while the setting is on: still where the lists look.
        assertTrue(LineRate.forTwo(built = true, settingOn = true, twoOpen = false))
        // Turned off in the middle of a film: two still run, and their speed stays out of one connection's.
        assertTrue(LineRate.forTwo(built = true, settingOn = false, twoOpen = true))
        // Turned off and on one request since: one connection's.
        assertFalse(LineRate.forTwo(built = true, settingOn = false, twoOpen = false))
        // A player made for one request measures one connection, whatever the setting has become.
        assertFalse(LineRate.forTwo(built = false, settingOn = true, twoOpen = false))
        assertFalse(LineRate.forTwo(built = false, settingOn = false, twoOpen = false))
    }

    @Test fun tooFastIsOverNineTenthsOfTheLine() {
        assertTrue(LineRate.tooFast(69.0, 72.0))
        assertEquals(false, LineRate.tooFast(64.8, 72.0))
        assertEquals(false, LineRate.tooFast(null, 72.0))
        assertEquals(false, LineRate.tooFast(69.0, null))
        assertEquals(false, LineRate.tooFast(69.0, 0.0))
    }

    @Test fun steadyIsThreeQuartersOfTheLineAtMost() {
        // Tonight's line: 47 Mb/s arriving. A 35 Mb/s copy leaves room for busy scenes; a 40 Mb/s one doesn't.
        assertTrue(LineRate.steady(35.0, 47.0))
        assertFalse(LineRate.steady(40.0, 47.0))
        assertTrue(LineRate.steady(null, 47.0))
        assertTrue(LineRate.steady(76.0, null))
        assertTrue(LineRate.steady(76.0, 0.0))
    }

    @Test fun bestForThisTvPrefersCopiesLeavingTheLineRoom() {
        // 72 Mb/s measured: 60 Mb/s still plays (under nine tenths) but leaves no room; 50 does.
        val ranked = SourceRank.rank(
            listOf(Fixtures.source(1, mbps = 26.0), Fixtures.source(2, mbps = 60.0), Fixtures.source(3, mbps = 50.0)),
            RankInput(plan, listOf("en"), { null }, 173, episode = false, lineMbps = 72.0),
        )
        val keys = ranked.map { it.key }
        assertEquals(TvFit.LIKELY, ranked.first { it.key == "k2" }.fit)
        assertTrue(keys.toString(), keys.indexOf("k3") < keys.indexOf("k2"))
        assertTrue(keys.toString(), keys.indexOf("k1") < keys.indexOf("k2"))
        assertEquals("k2", keys.last())
    }

    // ---- "Best for this TV"

    private val plan = TvPlan(true, listOf(setOf(Hdr.HDR10, Hdr.HDR10_PLUS), setOf(Hdr.HLG)), 2160, emptySet())

    private fun rank(lineMbps: Double?) = SourceRank.rank(
        listOf(Fixtures.source(1, mbps = 26.0), Fixtures.source(2, mbps = 69.0), Fixtures.source(3, mbps = 87.0)),
        RankInput(plan, listOf("en"), { null }, 173, episode = false, lineMbps = lineMbps),
    )

    @Test fun withNothingMeasuredTheRanksAreAsTheyWere() {
        val ranked = rank(null)
        assertTrue(ranked.all { it.fit == TvFit.LIKELY })
        assertEquals(ranked.map { it.key }, SourceRank.rank(ranked, RankInput(plan, listOf("en"), { null }, 173, episode = false)).map { it.key })
        // Over 60 Mb/s still only says "High data rate".
        assertEquals("High data rate", ranked.first { it.key == "k2" }.warning)
    }

    @Test fun filesTheLineCantKeepUpWithRankLastAndSayWhy() {
        val ranked = rank(72.0)
        assertEquals("k1", SourceRank.best(ranked)!!.key)
        assertEquals("k1", ranked.first().key)
        val remux = ranked.first { it.key == "k2" }
        assertEquals(TvFit.LIKELY_NOT, remux.fit)
        assertEquals("Needs 69 Mb/s · this connection brought 72", remux.warning)
        assertEquals(TvFit.LIKELY_NOT, ranked.first { it.key == "k3" }.fit)
        assertEquals(TvFit.LIKELY, ranked.first { it.key == "k1" }.fit)
        // A faster line measured later: the remux is "likely" again.
        assertTrue(rank(160.0).all { it.fit == TvFit.LIKELY })
    }

    @Test fun aFileWhoseRateIsntKnownIsNeverHeldAgainstTheLine() {
        val (fit, warning) = SourceRank.fit(Fixtures.source(1, mbps = null).facts, plan, null, lineMbps = 20.0)
        assertEquals(TvFit.LIKELY, fit)
        assertNull(warning)
    }

    // ---- The engine and its session

    private val film = Fixtures.film
    private val target = SourceTarget(film)
    private val loop = FakeLoop()
    private val ticker = FakeTicker(0L)
    private val direct = Executor { it.run() }
    private val library = FakeLibrary()
    private var measuredLine: Double? = null
    private val links = hashMapOf(Addon.TORRENTIO to SecretUrl.of(SourceFixtures.TIO)!!)

    private val engine = DefaultSourcesRepository(
        addons = object : AddonSettings {
            override fun isSet(addon: Addon) = links.containsKey(addon)
            override fun summary(addon: Addon): String? = null
            override fun save(addon: Addon, link: String) = AddonSave.SAVED
            override fun remove(addon: Addon) = Unit
        },
        links = { links[it] },
        thisTv = object : ThisTv {
            override fun capabilities(): TvCapabilities? = null
            override fun checkAgain(onDone: (TvCapabilities) -> Unit) = Unit
            override fun plan() = plan
            override fun setPlan(plan: TvPlan) = Unit
            override fun useAutomatic() = Unit
        },
        library = library,
        http = StreamsHttp { _, _, _, _ ->
            // The film runs 136 min: 89.1 GB (as the add-ons count them) is about 94 Mb/s, 44 GB about 46, 25.5 GB about 27.
            answer(
                torrentio(H1, size = "89.1 GB", quality = "4k HDR", name = "Film.Name.1999.2160p.UHD.BluRay.REMUX.HDR.HEVC.TrueHD.7.1-GRP"),
                torrentio(H2, size = "44 GB", quality = "4k HDR", name = "Film.Name.1999.2160p.UHD.BluRay.HDR.x265.DTS-HD.MA.5.1-GRP"),
                torrentio(H3, size = "25.5 GB", quality = "4k HDR", name = "Film.Name.1999.2160p.BluRay.HDR.x265.DDP5.1-GRP"),
            )
        },
        linkCheck = LinkCheck(
            { url, _, _ ->
                val hash = Regex("[0-9a-f]{40}").find(url)?.value ?: error("no hash")
                LinkCheck.Answer(302, "https://cdn.invalid/d/$hash/f.mkv", null)
            },
            { true },
            { loop.now },
        ),
        budget = Budget(object : Budget.Store {
            var text: String? = null
            override fun read() = text
            override fun write(text: String) {
                this.text = text
            }
        }) { 1_000_000_000L },
        measured = { null },
        language = { "en" },
        main = loop,
        io = direct,
        work = direct,
        elapsed = { loop.now },
        wall = { 1_000_000_000L },
        online = { true },
        lineMbps = { measuredLine },
    )

    private val starts = ArrayList<ReleaseKey?>()

    private val host = object : SourcePlayback.Host {
        override fun showProgress(text: String) = Unit
        override fun startSource(ready: Opened.Ready, startMs: Long, switching: Boolean) {
            starts += ready.source.release
        }
        override fun ask(question: String, choices: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) = Unit
        override fun stopWith(message: String) = Unit
        override fun notice(text: String) = Unit
        override fun positionMs() = 0L
        override fun retryAfter(ms: Long) = Unit
        override fun decodeOnDevice() = Unit
        override fun playNext(next: PlayRequest) = Unit
    }

    private fun play(): SourcePlayback {
        val request = PlayRequest(target, StartAt.Beginning, PlayOrigin.TITLE_PAGE)
        val p = SourcePlayback(request, engine.session(request), library, ticker, host)
        p.start()
        loop.advance()
        return p
    }

    @Test fun theLighterSourceIsTheBestOneTheLineSustains() {
        val p = play()
        // Nothing measured yet: the remux is "Best for this TV".
        assertEquals(ReleaseKey(H1, 0), starts.single())
        p.firstFrame()
        val session = p.session
        // The line sustains 72 Mb/s: at most 54 Mb/s. The 46 Mb/s encode is the best that fits, not the smallest.
        assertEquals(ReleaseKey(H2, 0), session.lighter(54.0)?.release)
        assertEquals(ReleaseKey(H3, 0), session.lighter(30.0)?.release)
        assertNull(session.lighter(10.0))
        // Never the one playing, however much is allowed.
        assertEquals(ReleaseKey(H2, 0), session.lighter(500.0)?.release)
    }

    @Test fun onceTheLineIsMeasuredShortTheListSaysSoAndTheBestChanges() {
        val p = play()
        p.firstFrame()
        measuredLine = 72.0
        engine.rerank()
        loop.advance()
        val list = p.session.list!!
        assertEquals(ReleaseKey(H2, 0), list.best?.release)
        val remux = list.sources.first { it.release == ReleaseKey(H1, 0) }
        assertEquals(TvFit.LIKELY_NOT, remux.fit)
        assertTrue(remux.warning!!.startsWith("Needs 9") && remux.warning!!.endsWith("this connection brought 72"))
        // The next play of this film starts on the source the line sustains.
        starts.clear()
        play()
        assertEquals(ReleaseKey(H2, 0), starts.single())
    }
}
