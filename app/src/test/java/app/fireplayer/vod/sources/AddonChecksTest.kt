package app.fireplayer.vod.sources

import app.fireplayer.data.CredentialChecks.State
import app.fireplayer.data.MemoryPrefs
import app.fireplayer.vod.AddonSave
import app.fireplayer.vod.AddonSettings
import app.fireplayer.vod.Addon
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.Kind
import app.fireplayer.vod.LastRelease
import app.fireplayer.vod.LookupWhy
import app.fireplayer.vod.PlaybackLibrary
import app.fireplayer.vod.SecretUrl
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.ThisTv
import app.fireplayer.vod.Title
import app.fireplayer.vod.TvCapabilities
import app.fireplayer.vod.TvPlan
import app.fireplayer.vod.sources.AddonCheck.Outcome
import app.fireplayer.vod.sources.SourceFixtures.H1
import app.fireplayer.vod.sources.SourceFixtures.answer
import app.fireplayer.vod.sources.SourceFixtures.torrentio
import app.fireplayer.vod.sources.SourceFixtures.torrentioKeyError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.Executor

/**
 * The add-ons' statuses as kept ([AddonChecks]): what a check finds, what's stored (never the link), a changed link,
 * when Settings opening checks again, and what real use notes for free.
 */
class AddonChecksTest {

    private val loop = FakeLoop()
    private val records = MemoryPrefs()
    private val links = HashMap<Addon, SecretUrl>().apply { put(Addon.TORRENTIO, SecretUrl.of(SourceFixtures.TIO)!!) }
    private var wall = 1_800_000_000_000L
    private var online = true
    private var manifest: () -> String = { MANIFEST }
    private var streams: () -> String = { answer(torrentio(H1)) }
    private val asked = ArrayList<String>()
    /** Checks wait here instead of running at once while [deferred]. */
    private var deferred = false
    private val pending = ArrayList<Runnable>()

    private val checks = AddonChecks(
        records = records,
        links = { links[it] },
        manifest = { _, _ -> asked += "manifest"; manifest() },
        streams = StreamsHttp { _, target, _, _ -> asked += target.key; streams() },
        background = { r -> if (deferred) pending += r else r.run() },
        main = loop,
        wall = { wall },
        online = { online },
    )

    private fun checkNow(addon: Addon = Addon.TORRENTIO): Int {
        var done = 0
        checks.check(addon) { done++ }
        loop.advance()
        return done
    }

    @Test fun aSavedAddOnReadsNotCheckedUntilCheckedThenWorkingWithItsService() {
        assertEquals(State.NOT_SET, checks.status(Addon.MEDIAFUSION).state)
        assertEquals(State.NOT_CHECKED, checks.status(Addon.TORRENTIO).state)
        deferred = true
        checks.check(Addon.TORRENTIO)
        assertEquals(State.CHECKING, checks.status(Addon.TORRENTIO).state)
        assertTrue(checks.checking)
        pending.removeAt(0).run()
        loop.advance()
        val st = checks.status(Addon.TORRENTIO)
        assertEquals(State.WORKING, st.state)
        assertEquals("Real-Debrid", st.service)
        assertEquals(wall, st.checkedAt)
        assertEquals("Real-Debrid · checked 5 min ago", st.detail(wall + 5 * 60_000))
        assertFalse(checks.checking)
        // The manifest, then one well-known film's streams.
        assertEquals(listOf("manifest", "stream/movie/${AddonCheck.TEST_ID}.json"), asked)
    }

    @Test fun onlyAFingerprintIsKeptSoAChangedLinkReadsNotChecked() {
        assertEquals(1, checkNow())
        val stored = records.values.values.joinToString()
        assertFalse(stored, stored.contains("FAKEKEY") || stored.contains("tio.invalid"))
        assertTrue(stored.contains("WORKING"))
        links[Addon.TORRENTIO] = SecretUrl.of("https://tio.invalid/FAKEKEY_OTHER")!!
        assertEquals(State.NOT_CHECKED, checks.status(Addon.TORRENTIO).state)
        links[Addon.TORRENTIO] = SecretUrl.of(SourceFixtures.TIO)!!
        assertEquals(State.WORKING, checks.status(Addon.TORRENTIO).state)
    }

    @Test fun aRefusedKeyOrAnAddOnNotAnsweringIsSaid() {
        streams = { answer(torrentioKeyError()) }
        checkNow()
        assertEquals(State.NOT_ACCEPTED, checks.status(Addon.TORRENTIO).state)
        assertEquals("debrid key refused", checks.status(Addon.TORRENTIO).detail(wall))
        asked.clear()
        manifest = { throw SocketTimeoutException() }
        checkNow()
        assertEquals(State.UNREACHABLE, checks.status(Addon.TORRENTIO).state)
        assertEquals("add-on not answering", checks.status(Addon.TORRENTIO).detail(wall))
        // No manifest, no streams request.
        assertEquals(listOf("manifest"), asked)
    }

    @Test fun aLinkChangedDuringItsCheckGetsItsOwnCheckNotTheOldResult() {
        deferred = true
        var done = 0
        checks.check(Addon.TORRENTIO) { done++ }
        links[Addon.TORRENTIO] = SecretUrl.of("https://tio.invalid/FAKEKEY_NEW")!!
        streams = { answer(torrentioKeyError()) }
        checks.check(Addon.TORRENTIO) { done++ }
        assertEquals(2, pending.size)
        // The old link's answer: dropped, the new one still being checked.
        val old = pending.removeAt(0)
        streams = { answer(torrentio(H1)) }
        old.run()
        loop.advance()
        assertEquals(State.CHECKING, checks.status(Addon.TORRENTIO).state)
        assertEquals(0, done)
        streams = { answer(torrentioKeyError()) }
        pending.removeAt(0).run()
        loop.advance()
        assertEquals(State.NOT_ACCEPTED, checks.status(Addon.TORRENTIO).state)
        // The window is told once, when the link it shows has its result.
        assertEquals(1, done)
    }

    @Test fun aWindowOpenedDuringACheckJoinsItAndIsToldWhenItEnds() {
        deferred = true
        // The Settings tab opening starts it, telling no one.
        checks.checkStale()
        var told = 0
        // Add-ons opens meanwhile, and is drawn twice while it waits.
        checks.checkStale { told++ }
        checks.checkStale { told++ }
        assertEquals(1, pending.size)
        pending.removeAt(0).run()
        loop.advance()
        assertEquals(State.WORKING, checks.status(Addon.TORRENTIO).state)
        assertEquals(1, told)
        assertEquals(2, asked.size)
    }

    @Test fun settingsOpeningChecksOnlyWhatIsUncheckedOrADayOld() {
        checks.checkStale()
        loop.advance()
        assertEquals(2, asked.size)
        asked.clear()
        wall += 23 * 60 * 60_000L
        checks.checkStale()
        loop.advance()
        assertTrue(asked.isEmpty())
        wall += 2 * 60 * 60_000L
        checks.checkStale()
        loop.advance()
        assertEquals(2, asked.size)
    }

    @Test fun aCouldntCheckIsCheckedAgainOnceOnlineOrTenMinutesLater() {
        online = false
        manifest = { throw java.io.IOException() }
        checkNow()
        assertEquals("no internet", checks.status(Addon.TORRENTIO).detail(wall))
        // Still offline: not due, however often the window is drawn.
        asked.clear()
        checks.checkStale()
        loop.advance()
        assertTrue(asked.isEmpty())
        // Back online: the next opening checks it again.
        online = true
        manifest = { MANIFEST }
        wall += 60_000
        checks.checkStale()
        loop.advance()
        assertEquals(2, asked.size)
        assertEquals(State.WORKING, checks.status(Addon.TORRENTIO).state)
        // Any other Couldn't check (the add-on busy): again only once it's 10 minutes old, so a redraw doesn't loop.
        manifest = { throw StreamsHttp.Busy() }
        checkNow()
        assertEquals("add-on busy", checks.status(Addon.TORRENTIO).detail(wall))
        asked.clear()
        wall += AddonChecks.RETRY_MS - 1
        checks.checkStale()
        loop.advance()
        assertTrue(asked.isEmpty())
        manifest = { MANIFEST }
        wall += 1
        checks.checkStale()
        loop.advance()
        assertEquals(2, asked.size)
        assertEquals(State.WORKING, checks.status(Addon.TORRENTIO).state)
    }

    @Test fun aListsWorkingDoesNotClearAProblemFoundWhilePlaying() {
        // A play's link was refused: the debrid limit.
        checks.noted(Addon.TORRENTIO, Outcome(State.NOT_ACCEPTED, AddonCheck.LIMIT))
        // The next title's list still shows cached sources: the problem stays.
        val engine = engine { answer(torrentio(H1)) }
        engine.lookUp(SourceTarget(Title("tt0000001", Kind.MOVIE, "Film", runtime = "120 min")), LookupWhy.SOURCES, Addon.TORRENTIO) { }
        loop.advance()
        assertEquals(State.NOT_ACCEPTED, checks.status(Addon.TORRENTIO).state)
        assertEquals("debrid limit reached", checks.status(Addon.TORRENTIO).detail(wall))
        // A play whose link comes back Ready clears it.
        checks.noted(Addon.TORRENTIO, Outcome(State.WORKING, service = "Real-Debrid"))
        assertEquals(State.WORKING, checks.status(Addon.TORRENTIO).state)
        // So does Check now.
        checks.noted(Addon.TORRENTIO, Outcome(State.NOT_ACCEPTED, AddonCheck.KEY_REFUSED))
        checkNow()
        assertEquals(State.WORKING, checks.status(Addon.TORRENTIO).state)
    }

    @Test fun realUseNotesTheStatusWithoutAsking() {
        checks.noted(Addon.TORRENTIO, Outcome(State.WORKING, service = "TorBox"))
        assertEquals(State.WORKING, checks.status(Addon.TORRENTIO).state)
        assertEquals("TorBox", checks.status(Addon.TORRENTIO).service)
        val at = checks.status(Addon.TORRENTIO).checkedAt
        // The same again soon isn't written again; later it is (its time moves on).
        wall += 60_000
        checks.noted(Addon.TORRENTIO, Outcome(State.WORKING))
        assertEquals(at, checks.status(Addon.TORRENTIO).checkedAt)
        wall += AddonChecks.NOTE_AGAIN_MS
        checks.noted(Addon.TORRENTIO, Outcome(State.WORKING))
        assertEquals(wall, checks.status(Addon.TORRENTIO).checkedAt)
        // The service told before is kept when this use didn't tell it.
        assertEquals("TorBox", checks.status(Addon.TORRENTIO).service)
        // A problem is kept at once.
        checks.noted(Addon.TORRENTIO, Outcome(State.NOT_ACCEPTED, AddonCheck.LIMIT))
        assertEquals(State.NOT_ACCEPTED, checks.status(Addon.TORRENTIO).state)
        assertEquals("debrid limit reached", checks.status(Addon.TORRENTIO).detail(wall))
        // An add-on without a link notes nothing.
        checks.noted(Addon.MEDIAFUSION, Outcome(State.WORKING))
        assertEquals(State.NOT_SET, checks.status(Addon.MEDIAFUSION).state)
        assertTrue(asked.isEmpty())
    }

    @Test fun aRemovedLinkTakesItsResultWithIt() {
        checkNow()
        checks.forget(Addon.TORRENTIO)
        assertNull(records.getString(AddonChecks.key(Addon.TORRENTIO), null))
        assertEquals(State.NOT_CHECKED, checks.status(Addon.TORRENTIO).state)
    }

    @Test fun aLookUpInTheEngineNotesTheStatusForFree() {
        var answerWith = answer(torrentio(H1))
        val engine = engine { answerWith }
        val target = SourceTarget(Title("tt0000001", Kind.MOVIE, "Film", runtime = "120 min"))
        engine.lookUp(target, LookupWhy.SOURCES, Addon.TORRENTIO) { }
        loop.advance()
        assertEquals(State.WORKING, checks.status(Addon.TORRENTIO).state)
        assertEquals("Real-Debrid", checks.status(Addon.TORRENTIO).service)
        answerWith = answer(torrentioKeyError())
        engine.forget()
        engine.lookUp(SourceTarget(Title("tt0000003", Kind.MOVIE, "Other")), LookupWhy.SOURCES, Addon.TORRENTIO) { }
        loop.advance()
        assertEquals(State.NOT_ACCEPTED, checks.status(Addon.TORRENTIO).state)
        // No check was made for it.
        assertTrue(asked.none { it == "manifest" })
    }

    private fun engine(answer: () -> String): DefaultSourcesRepository {
        val direct = Executor { it.run() }
        return DefaultSourcesRepository(
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
                override fun plan() = TvPlan(true, listOf(setOf(Hdr.HDR10)), 2160, emptySet())
                override fun setPlan(plan: TvPlan) = Unit
                override fun useAutomatic() = Unit
            },
            library = object : PlaybackLibrary {
                override fun lastRelease(title: Title, episode: EpisodeRef?): LastRelease? = null
                override fun recordRelease(title: Title, episode: EpisodeRef?, release: LastRelease) = Unit
                override fun recordPlayback(title: Title, episode: EpisodeRef?, positionMs: Long, durationMs: Long, creditsAtMs: Long?, sceneAtMs: Long?) = Unit
            },
            http = StreamsHttp { _, _, _, _ -> answer() },
            linkCheck = LinkCheck({ _, _, _ -> LinkCheck.Answer(302, "https://cdn.invalid/f.mkv", null) }, { true }, { loop.now }),
            budget = Budget(object : Budget.Store {
                override fun read(): String? = null
                override fun write(text: String) = Unit
            }) { wall },
            measured = { null },
            language = { "en" },
            main = loop,
            io = direct,
            work = direct,
            elapsed = { loop.now },
            wall = { wall },
            online = { true },
            checks = checks,
        )
    }

    private companion object {
        const val MANIFEST = """{"id":"com.stremio.torrentio.addon","version":"0.0.15","name":"Torrentio","resources":["stream"]}"""
    }
}
