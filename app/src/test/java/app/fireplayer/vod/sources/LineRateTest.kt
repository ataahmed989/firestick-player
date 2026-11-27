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
        assertEquals("47.2|1700000000000;=47.2", store.text)
        assertEquals(47.2, line.current()!!, 0.001)
    }

    @Test fun aShortLineIsRememberedForThreeDays() {
        assertEquals(3L * 24 * 60 * 60 * 1000, LineRate.KEEP_MS)
        line.short(72.44)
        assertEquals("72.4|1700000000000;=72.4", store.text)
        assertEquals(72.4, line.current()!!, 0.001)
        now += LineRate.KEEP_MS
        assertEquals(72.4, line.current()!!, 0.001)
        now += 1
        assertNull(line.current())
    }

    @Test fun theFigureUsedIsTheLowestOfTheLastThreeSittingsAndSmallChangesDontMoveIt() {
        line.short(72.0)
        // A faster evening is kept beside it: the figure stays the slower one, so Best doesn't flip.
        assertFalse(line.seen(85.0))
        assertEquals(72.0, line.current()!!, 0.001)
        // A slower one lowers it at once.
        assertTrue(line.seen(47.0))
        assertEquals(47.0, line.current()!!, 0.001)
        // Within 15 % of the newest: the same sitting, nothing written, nothing to rank again...
        now += 60_000
        assertFalse(line.seen(52.0))
        assertEquals("47.0|1700000000000;85.0|1700000000000;72.0|1700000000000;=47.0", store.text)
        // ...unless the newest is an hour old: a reading of its own; the figure used stays.
        now += LineRate.REFRESH_MS
        assertFalse(line.seen(52.0))
        assertEquals(47.0, line.current()!!, 0.001)
        assertTrue(store.text!!.startsWith("52.0|$now;47.0|"))
        // Only the last three count: three faster evenings since, and the slow one no longer does.
        assertFalse(line.seen(90.0))
        assertFalse(line.seen(110.0))
        assertTrue(line.seen(130.0))
        assertEquals(90.0, line.current()!!, 0.001)
        // A stalling film's measurement is always kept.
        line.short(60.0)
        assertEquals(60.0, line.current()!!, 0.001)
        // Nonsense never counts.
        assertFalse(line.seen(Double.NaN))
        assertFalse(line.seen(0.0))
        assertEquals(60.0, line.current()!!, 0.001)
        // Each reading is forgotten after three days.
        now += LineRate.KEEP_MS + 1
        assertNull(line.current())
    }

    @Test fun oneSlowEveningGoesOnceThreeSittingsCameAfterIt() {
        assertTrue(line.seen(100.0))
        // One stall on a poor evening.
        line.short(45.0)
        assertEquals(45.0, line.current()!!, 0.001)
        // Back at 100 the same evening, then later evenings at about 100: each a reading of its own.
        now += 60_000
        assertFalse(line.seen(100.0))
        now += LineRate.REFRESH_MS
        assertFalse(line.seen(98.0))
        assertEquals(45.0, line.current()!!, 0.001)
        now += LineRate.REFRESH_MS
        assertTrue(line.seen(102.0))
        assertEquals(98.0, line.current()!!, 0.001)
        // The lowest moving less than 15 % leaves the figure used, and nothing ranks again.
        now += LineRate.REFRESH_MS
        assertFalse(line.seen(92.0))
        assertEquals(98.0, line.current()!!, 0.001)
        // An older version's readings, without the figure, read as they are.
        store.text = "72.0|$now;80.0|$now"
        assertEquals(72.0, line.current()!!, 0.001)
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

    @Test fun roomIsHalfAgainARemuxsAverageAndAThirdMoreForOthers() {
        // Tonight's line: 47 Mb/s arriving. A 35 Mb/s encode leaves room for busy scenes; a 40 Mb/s one, or a 35 Mb/s remux, doesn't.
        assertTrue(LineRate.room(35.0, 47.0, remux = false))
        assertFalse(LineRate.room(40.0, 47.0, remux = false))
        assertFalse(LineRate.room(35.0, 47.0, remux = true))
        assertTrue(LineRate.room(null, 47.0, remux = true))
        assertTrue(LineRate.room(76.0, null, remux = true))
        assertTrue(LineRate.room(76.0, 0.0, remux = true))
    }

    @Test fun bestForThisTvAimsAtThePreferredBitrateWhateverEarlierFilmsArrivedAt() {
        // Whatever an earlier film was measured to arrive at, the copy nearest the preferred bitrate (60) leads.
        val ranked = SourceRank.rank(
            listOf(Fixtures.source(1, mbps = 26.0), Fixtures.source(2, mbps = 60.0), Fixtures.source(3, mbps = 50.0)),
            RankInput(plan, listOf("en"), { null }, 173, episode = false),
        )
        assertEquals(listOf("k2", "k3", "k1"), ranked.map { it.key })
        assertTrue(ranked.all { it.fit == TvFit.LIKELY && it.warning == null })
    }

    // ---- "Best for this TV"

    private val plan = TvPlan(true, listOf(setOf(Hdr.HDR10, Hdr.HDR10_PLUS), setOf(Hdr.HLG)), 2160, emptySet())

    private fun rank() = SourceRank.rank(
        listOf(Fixtures.source(1, mbps = 26.0), Fixtures.source(2, mbps = 69.0), Fixtures.source(3, mbps = 87.0)),
        RankInput(plan, listOf("en"), { null }, 173, episode = false),
    )

    @Test fun noCopyIsSetAsideOrWarnedForTheConnectionBeforeItPlays() {
        val ranked = rank()
        // 69 is nearest 60 (its 9 above counting 7.2), then 87 (21.6), then 26 (34).
        assertEquals(listOf("k2", "k3", "k1"), ranked.map { it.key })
        assertTrue(ranked.all { it.fit == TvFit.LIKELY && it.warning == null })
        val (fit, warning) = SourceRank.fit(Fixtures.source(1, mbps = null).facts, plan, null)
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
        // A viewer whose preferred bitrate is 100 Mb/s (Settings › Playback): the 94 Mb/s remux is the nearest.
        preferredMbps = { 100.0 },
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

    @Test fun aShortLineMeasuredInPlayLeavesTheBestToThePreferredBitrate() {
        val p = play()
        p.firstFrame()
        // The playing file arrived at 72 Mb/s and kept stalling: kept for the film buffer, the lists stay as they are.
        line.short(72.0)
        assertEquals(72.0, line.current()!!, 0.01)
        engine.rerank()
        loop.advance()
        val list = p.session.list!!
        assertEquals(ReleaseKey(H1, 0), list.best?.release)
        val remux = list.sources.first { it.release == ReleaseKey(H1, 0) }
        assertEquals(TvFit.LIKELY, remux.fit)
        assertNull(remux.warning)
        // In the play, the file's own reading still finds the lighter copy that fits (Ask / Switch).
        assertEquals(ReleaseKey(H2, 0), p.session.lighter(72.0 * LineRate.SUSTAIN_SHARE)?.release)
        // The next play still starts on the copy nearest the preferred bitrate.
        starts.clear()
        play()
        assertEquals(ReleaseKey(H1, 0), starts.single())
    }
}
