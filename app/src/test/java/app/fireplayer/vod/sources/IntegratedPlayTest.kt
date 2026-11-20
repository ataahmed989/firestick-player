package app.fireplayer.vod.sources

import app.fireplayer.vod.Addon
import app.fireplayer.vod.AddonSave
import app.fireplayer.vod.AddonSettings
import app.fireplayer.vod.Card
import app.fireplayer.vod.FakeVodRepository
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.LastRelease
import app.fireplayer.vod.Opened
import app.fireplayer.vod.PlayOrigin
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.ReleaseKey
import app.fireplayer.vod.SecretUrl
import app.fireplayer.vod.SourceList
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.StartAt
import app.fireplayer.vod.ThisTv
import app.fireplayer.vod.TvCapabilities
import app.fireplayer.vod.TvPlan
import app.fireplayer.vod.play.FakeLibrary
import app.fireplayer.vod.play.FakeTicker
import app.fireplayer.vod.play.Fixtures
import app.fireplayer.vod.play.PlayWait
import app.fireplayer.vod.play.SourcePlayback
import app.fireplayer.vod.play.VodLaunch
import app.fireplayer.vod.sources.SourceFixtures.H1
import app.fireplayer.vod.sources.SourceFixtures.H2
import app.fireplayer.vod.sources.SourceFixtures.H3
import app.fireplayer.vod.sources.SourceFixtures.answer
import app.fireplayer.vod.sources.SourceFixtures.mediaFusion
import app.fireplayer.vod.sources.SourceFixtures.torrentio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executor

/**
 * The pages' plays ([PlayWait], [VodLaunch]) and the player's logic ([SourcePlayback]) on the real engine
 * ([DefaultSourcesRepository] and its session), with synthetic ".invalid" add-on answers only.
 */
class IntegratedPlayTest {
    private val film = Fixtures.film
    private val target = SourceTarget(film)

    private val loop = FakeLoop()
    /** The same clock as the engine's (on the device both are elapsedRealtime): add-on deadlines are compared on it. */
    private val ticker = FakeTicker(0L)
    private val direct = Executor { it.run() }
    private val answers = HashMap<Addon, () -> String>()
    private val asked = ArrayList<Addon>()
    private val links = hashMapOf(
        Addon.TORRENTIO to SecretUrl.of(SourceFixtures.TIO)!!,
        Addon.MEDIAFUSION to SecretUrl.of(SourceFixtures.MF)!!,
    )
    private val library = FakeLibrary()
    private val repo = FakeVodRepository()

    private val tv = object : ThisTv {
        override fun capabilities(): TvCapabilities? = null
        override fun checkAgain(onDone: (TvCapabilities) -> Unit) = Unit
        override fun plan() = TvPlan(true, listOf(setOf(Hdr.HDR10, Hdr.HDR10_PLUS), setOf(Hdr.HLG)), 2160, emptySet())
        override fun setPlan(plan: TvPlan) = Unit
        override fun useAutomatic() = Unit
    }

    private val settings = object : AddonSettings {
        override fun isSet(addon: Addon) = links.containsKey(addon)
        override fun summary(addon: Addon): String? = null
        override fun save(addon: Addon, link: String) = AddonSave.SAVED
        override fun remove(addon: Addon) {
            links.remove(addon)
        }
    }

    private val engine = DefaultSourcesRepository(
        addons = settings,
        links = { links[it] },
        thisTv = tv,
        library = library,
        http = StreamsHttp { base, _, _, _ ->
            val addon = if (base.host == "tio.invalid") Addon.TORRENTIO else Addon.MEDIAFUSION
            asked += addon
            answers[addon]?.invoke() ?: throw IOException("no answer set")
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
    )

    /** What the player was told to play: the source's release, where, and whether in place of another. */
    private val starts = ArrayList<Triple<ReleaseKey?, Long, Boolean>>()
    private val readies = ArrayList<Opened.Ready>()
    private val stops = ArrayList<String>()

    private val host = object : SourcePlayback.Host {
        override fun showProgress(text: String) = Unit
        override fun startSource(ready: Opened.Ready, startMs: Long, switching: Boolean) {
            readies += ready
            starts += Triple(ready.source.release, startMs, switching)
        }
        override fun ask(question: String, choices: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) = Unit
        override fun stopWith(message: String) {
            stops += message
        }
        override fun notice(text: String) = Unit
        override fun positionMs() = 0L
        override fun retryAfter(ms: Long) = Unit
        override fun decodeOnDevice() = Unit
        override fun playNext(next: PlayRequest) = Unit
    }

    /** What PlayerActivity does with a handed-over request: the engine's session, driven by [SourcePlayback]. */
    private fun player(request: PlayRequest): SourcePlayback {
        val p = SourcePlayback(request, engine.session(request), library, ticker, host)
        p.start()
        loop.advance()
        return p
    }

    @Test
    fun resumeOnTheTitlePageWaitsForBothAddonsThenPlaysTheLastReleaseFromItsPosition() {
        answers[Addon.TORRENTIO] = { answer(torrentio(H1)) }
        answers[Addon.MEDIAFUSION] = { answer(mediaFusion(H2), mediaFusion(H3, resolution = "1080p")) }
        repo.recordProgress(film, null, 600_000, 7_200_000)
        // The 1080p file played last time: Resume keeps it although a 4K one ranks higher.
        library.recordRelease(film, null, LastRelease(Addon.MEDIAFUSION, ReleaseKey(H3, 0), 1L))

        var handed: PlayRequest? = null
        val events = ArrayList<String>()
        val wait = PlayWait(engine, ticker, object : PlayWait.Listener {
            override fun onWaiting(label: String, fraction: Float, strip: String?) {
                events += "waiting $label"
            }
            override fun onPlay(target: SourceTarget, list: SourceList) {
                val start = app.fireplayer.vod.play.PlayRules.startAt(repo.lastPlayed(film))
                handed = VodLaunch.fromTitlePage(target, start, list, library)
            }
            override fun onEnded(message: String?) {
                events += "ended $message"
            }
        })
        wait.press(target)
        loop.advance()
        val request = handed ?: error("no play: $events")
        assertEquals(setOf(Addon.TORRENTIO, Addon.MEDIAFUSION), asked.toSet())
        assertTrue(request.list!!.complete)
        assertEquals(StartAt.At(600_000), request.start)
        assertEquals(PlayOrigin.TITLE_PAGE, request.origin)
        // The ids subtitles, Skip Intro and history use.
        assertEquals("tt0133093", request.imdbId)
        assertEquals(603, request.tmdbId)

        // The player plays from the list the page waited for: no second request.
        asked.clear()
        val p = player(request)
        assertTrue(asked.isEmpty())
        assertEquals(listOf(Triple(ReleaseKey(H3, 0), 600_000L, false)), starts)
        assertFalse(readies.single().resumedNear)

        // The first frame records the release (hash + file index + add-on, never a link); positions go to the library.
        p.firstFrame()
        assertEquals(LastRelease(Addon.MEDIAFUSION, ReleaseKey(H3, 0), 1_000_000_000L), library.lastRelease(film, null))
        p.record(700_000, 7_200_000, null)
        assertEquals(listOf<Any?>(film.id, null, 700_000L, 7_200_000L, null), library.recorded.last())
        p.close()
    }

    @Test
    fun playFromBeginningStartsAtZeroWithoutAQuestion() {
        answers[Addon.TORRENTIO] = { answer(torrentio(H1)) }
        answers[Addon.MEDIAFUSION] = { answer(mediaFusion(H2)) }
        repo.recordProgress(film, null, 600_000, 7_200_000)
        val request = VodLaunch.fromTitlePage(target, StartAt.Beginning, null, library)
        assertNull(request.preferred)
        player(request)
        assertEquals(0L, starts.single().second)
        assertTrue(stops.isEmpty())
    }

    @Test
    fun aContinueCardPlaysTheSameFileAskingOnlyItsAddon() {
        answers[Addon.TORRENTIO] = { answer(torrentio(H1)) }
        answers[Addon.MEDIAFUSION] = { answer(mediaFusion(H2), mediaFusion(H3, resolution = "1080p")) }
        repo.recordProgress(film, null, 600_000, 7_200_000)
        library.recordRelease(film, null, LastRelease(Addon.MEDIAFUSION, ReleaseKey(H3, 0), 1L))

        val request = VodLaunch.fromContinueCard(Card(film), repo, library)!!
        assertEquals(PlayOrigin.CONTINUE_CARD, request.origin)
        player(request)
        assertEquals(listOf(Addon.MEDIAFUSION), asked)
        assertEquals(listOf(Triple(ReleaseKey(H3, 0), 600_000L, false)), starts)
        assertFalse(readies.single().resumedNear)
        // The link the player opens is held in memory only and never shows in text.
        assertFalse(readies.single().link.toString().contains("FAKECONFIG"))
    }
}
