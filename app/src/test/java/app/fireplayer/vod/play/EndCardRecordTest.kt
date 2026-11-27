package app.fireplayer.vod.play

import app.fireplayer.history.LibraryContinue
import app.fireplayer.history.VodContinue
import app.fireplayer.history.WatchHistory
import app.fireplayer.vod.Addon
import app.fireplayer.vod.Episode
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Kind
import app.fireplayer.vod.LastRelease
import app.fireplayer.vod.Opened
import app.fireplayer.vod.PlayOrigin
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.ReleaseKey
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.StartAt
import app.fireplayer.vod.Title
import app.fireplayer.vod.WatchedRule
import app.fireplayer.vod.data.Cinemeta
import app.fireplayer.vod.data.DefaultVodRepository
import app.fireplayer.vod.data.DefaultVodRepositoryTest.FakeHttp
import app.fireplayer.vod.data.DefaultVodRepositoryTest.FakeTmdb
import app.fireplayer.vod.data.JsonCache
import app.fireplayer.vod.data.KeptWithCopy
import app.fireplayer.vod.data.MemoryIdStore
import app.fireplayer.vod.data.MemoryLibraryStore
import app.fireplayer.vod.data.RowLayoutStore
import app.fireplayer.vod.sources.RecordedLibrary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.Executor
import app.fireplayer.vod.data.Fixtures as DataFixtures

/**
 * The end card's Exit and Next episode, and the end itself, are recorded once, as the player makes them
 * (PlayerActivity.exitAfterCredits, playNextEpisode, STATE_ENDED, then the save as it leaves or the next episode
 * starts), through [SourcePlayback] into the library, Glass Home's lists read from it: the ✓, Continue Watching, the
 * place it resumes from, the copy it was left on and what Glass Home is told all agree, after a restart too. Exit with
 * a known real scene after the credits still ahead leaves the film where it is (the watched rule's first point).
 */
class EndCardRecordTest {
    private val min = 60_000L
    private var now = Instant.parse("2026-11-20T20:00:00Z").toEpochMilli()
    private val direct = Executor { it.run() }
    private val dir: File = Files.createTempDirectory("endcard").toFile()
    private val store = MemoryLibraryStore()
    private val idStore = MemoryIdStore()
    private val rowStore = RowLayoutStore.Memory()
    private val mainQueue = ArrayList<Runnable>()

    // Guardians of the Galaxy's 7241 s file: SkipDB's credits from 7079.8 s to the end (no Exit-free tail), IntroDB's
    // post-credits scene at 7210 s (it ends where the file does, so it's trusted: Segments.sceneAt).
    private val guardians = Title("tt2015381", Kind.MOVIE, "Guardians of the Galaxy", runtime = "121 min")
    private val filmMs = 7_241_000L
    private val filmCredits = 7_079_800L
    private val filmScene = 7_210_000L
    private val bb = Title("tt0903747", Kind.SERIES, "Breaking Bad")
    private val http = FakeHttp().apply { answers[Cinemeta.metaUrl(Kind.SERIES, bb.id)] = DataFixtures.text("cinemeta_meta_series.json") }

    /** What's kept beside the copy a title was left on (KeptTracks), by title or episode id. */
    private val kept = HashSet<String>()
    private val keptWithCopy = object : KeptWithCopy {
        override fun has(kind: Kind, id: String) = kept.any { it == id || it.startsWith("$id:") }
        override fun forget(kind: Kind, id: String) {
            kept.removeAll { it == id || it.startsWith("$id:") }
        }
    }

    /** FirePlayer as it starts on the TV, from what was stored. */
    private fun newRepo(): DefaultVodRepository = DefaultVodRepository(
        http = http,
        tmdb = FakeTmdb(),
        tmdbKey = { "" },
        cache = JsonCache(File(dir, "vod")) { now },
        idStore = idStore,
        libraryStore = store,
        main = Executor { mainQueue += it },
        io = direct,
        net = direct,
        lookups = direct,
        clock = { now },
        rowStore = rowStore,
        keptWithCopy = keptWithCopy,
    ).also { r -> r.addLibraryListener { changes++ } }

    private var repo = newRepo()
    private val recorded get() = RecordedLibrary(repo.library)

    /** Library changes FirePlayer's screens and Glass Home are told of. */
    private var changes = 0

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun runMain() {
        while (mainQueue.isNotEmpty()) mainQueue.removeAt(0).run()
    }

    private val ticker = FakeTicker()
    private var onPlayNext: () -> Unit = {}
    private var nextPlay: PlayRequest? = null

    private val host = object : SourcePlayback.Host {
        override fun showProgress(text: String) = Unit
        override fun startSource(ready: Opened.Ready, startMs: Long, switching: Boolean) = Unit
        override fun ask(question: String, choices: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) = Unit
        override fun stopWith(message: String) = Unit
        override fun notice(text: String) = Unit
        override fun positionMs() = 0L
        override fun retryAfter(ms: Long) = Unit
        override fun decodeOnDevice() = Unit
        // PlayerActivity's: savePosition(), then the next episode's play.
        override fun playNext(next: PlayRequest) {
            onPlayNext()
            nextPlay = next
        }
    }

    /** A play from the add-ons that has played a while, the copy it plays kept as the one it was left on. */
    private fun play(target: SourceTarget, next: EpisodeRef? = null): SourcePlayback {
        val request = PlayRequest(target, StartAt.At(10 * min), PlayOrigin.CONTINUE_CARD, next = next)
        val session = FakeSession(request)
        val p = SourcePlayback(request, session, recorded, ticker, host)
        p.start()
        session.ready(Fixtures.source(1))
        p.firstFrame()
        ticker.advance(30 * min)
        // As the session keeps it at the first frame, with what's kept beside it.
        recorded.recordRelease(target.title, target.episode, LastRelease(Addon.TORRENTIO, ReleaseKey(Fixtures.hash(1), 0), now))
        kept += RecordedLibrary.idOf(target.title, target.episode)
        runMain()
        changes = 0
        return p
    }

    /**
     * PlayerActivity.exitAfterCredits at [positionMs] for a play from the add-ons, then the save finishWithResult makes
     * as it leaves. True when Exit was the film's end (watched), false when it left as Back does.
     */
    private fun exit(p: SourcePlayback, positionMs: Long, durationMs: Long, credits: Long?, scene: Long?): Boolean {
        now += min
        val end = !WatchedRule.sceneAhead(positionMs, durationMs, credits, scene)
        if (end) p.recordEnd(durationMs, durationMs, credits, scene)
        now += 1_000
        p.record(positionMs, durationMs, credits, scene)
        runMain()
        return end
    }

    private fun restart() {
        repo = newRepo()
        changes = 0
    }

    /** Glass Home's list [name] (items?lists): each row's id, and its episode. */
    private fun list(name: String): List<String> = VodContinue.lists(repo).single { it.first == name }.second
        .map { r -> r.id + (if (r.kind == WatchHistory.Kind.EPISODE) " S${r.season}E${r.episode}" else "") }

    private val filmRow = LibraryContinue.idOf(Kind.MOVIE, "tt2015381")
    private val showRow = LibraryContinue.idOf(Kind.SERIES, "tt0903747")

    @Test
    fun exitWithAKnownSceneStillAheadLeavesTheFilmWhereItIsEverywhere() {
        val p = play(SourceTarget(guardians))
        // The Exit card at the credits, its count run out 10 s later: the scene (7210 s) is still ahead.
        assertFalse(exit(p, 7_090_000, filmMs, filmCredits, filmScene))
        for (round in 1..2) {
            val what = if (round == 1) "as left" else "after a restart"
            // Not watched (no ✓ anywhere, and Mark watched is offered), and no watched mark was ever written.
            assertFalse(what, repo.library.isWatched(guardians.id))
            assertTrue(what, store.watched.isEmpty())
            // In Continue Watching, held there: resumes at the credits, with its bar, and the scene to come.
            assertTrue(what, repo.library.inContinueWatching(guardians.id))
            val last = repo.library.lastPlayed(Kind.MOVIE, guardians.id)!!
            assertTrue(what, last.position.held)
            assertFalse(what, last.finished)
            assertEquals(what, StartAt.At(7_090_000), PlayRules.startAt(last))
            assertEquals(what, "3m left", repo.library.continueWatching(null).single().caption)
            // Glass Home's Home and Movies lists have it as FirePlayer's rows do.
            assertEquals(what, listOf(filmRow), list(VodContinue.HOME))
            assertEquals(what, listOf(filmRow), list(VodContinue.MOVIES))
            // The copy it was left on, and what's kept beside it, still there: Resume plays the same file.
            assertNotNull(what, recorded.lastRelease(guardians, null))
            assertTrue(what, guardians.id in kept)
            restart()
        }
        // One record, at the real place.
        assertEquals(listOf(7_090_000L), store.progress.values.map { it.positionMs })
    }

    @Test
    fun exitWithAKnownSceneStillAheadIsOneChangeForGlassHome() {
        val p = play(SourceTarget(guardians))
        exit(p, 7_090_000, filmMs, filmCredits, filmScene)
        assertEquals(1, changes)
    }

    @Test
    fun exitWithNoSceneKnownIsTheFilmsEndRecordedOnce() {
        val p = play(SourceTarget(guardians))
        // SkipDB's credits to the end, no scene known: Exit is the end, as always; leaving records nothing after it.
        assertTrue(exit(p, 7_090_000, filmMs, filmCredits, null))
        assertEquals(1, changes)
        for (round in 1..2) {
            assertTrue(repo.library.isWatched(guardians.id))
            assertFalse(repo.library.inContinueWatching(guardians.id))
            assertTrue(repo.library.lastPlayed(Kind.MOVIE, guardians.id)!!.finished)
            assertEquals(emptyList<String>(), list(VodContinue.HOME))
            assertEquals(emptyList<String>(), list(VodContinue.MOVIES))
            // Left Continue Watching: the copy it was left on is forgotten, as always.
            assertNull(recorded.lastRelease(guardians, null))
            assertFalse(guardians.id in kept)
            restart()
        }
        assertEquals(listOf(filmMs), store.progress.values.map { it.positionMs })
    }

    @Test
    fun exitInTheSceneOrWithItBehindIsTheEnd() {
        // The Exit card from the countdown's seconds before the end (a scene with its own credits, Segments.afterCredits):
        // the scene began, so Exit is the end.
        val p = play(SourceTarget(guardians))
        assertTrue(exit(p, 7_231_000, filmMs, filmCredits, filmScene))
        assertTrue(repo.library.isWatched(guardians.id))
        assertFalse(repo.library.inContinueWatching(guardians.id))
        assertEquals(emptyList<String>(), list(VodContinue.HOME))
    }

    @Test
    fun playedToItsEndIsRecordedOnce() {
        val p = play(SourceTarget(guardians))
        // STATE_ENDED: the end, then finishWithResult's save at the same place.
        now += min
        p.recordEnd(filmMs, filmMs, filmCredits, filmScene)
        p.record(filmMs, filmMs, filmCredits, filmScene)
        runMain()
        assertEquals(1, changes)
        assertTrue(repo.library.isWatched(guardians.id))
        assertFalse(repo.library.inContinueWatching(guardians.id))
    }

    /** Breaking Bad's details stored (S1 E1-7 aired in 2008); E1 playing, E2 next. */
    private fun bbPlaying(next: EpisodeRef? = EpisodeRef(1, 2)): SourcePlayback {
        repo.details(bb) { _, _ -> }
        runMain()
        return play(SourceTarget(bb, EpisodeRef(1, 1)), next = next)
    }

    private fun ep(n: Int) = Episode(Episode.idOf(bb.id, 1, n), 1, n, "", released = Instant.parse("2008-01-20T00:00:00Z").toEpochMilli())

    @Test
    fun nextEpisodeWithASceneAheadCountsTheEpisodeWatchedOnceAndTheShowGoesOn() {
        val p = bbPlaying()
        val epMs = 58 * min
        val credits = 57 * min
        val scene = 57 * min + 45_000
        // PlayerActivity.playNextEpisode at the credits: the episode's end, then the next one's play, whose save
        // (host.playNext's savePosition) records where the player stands.
        val at = 57 * min + 5_000
        onPlayNext = { now += 1_000; p.record(at, epMs, credits, scene) }
        now += min
        p.recordEnd(maxOf(at, credits, scene).coerceAtMost(epMs), epMs, credits, scene)
        assertTrue(p.ended(playNext = true))
        runMain()
        assertEquals(1, changes)
        assertEquals(EpisodeRef(1, 2), nextPlay!!.target.episode)
        assertEquals(StartAt.Beginning, nextPlay!!.start)
        for (round in 1..2) {
            val what = if (round == 1) "as left" else "after a restart"
            // ✓ on E1, and the show goes on with E2 everywhere: never E1 held near its end.
            assertTrue(what, repo.library.isEpisodeWatched(ep(1)))
            assertFalse(what, repo.library.positionOf(ep(1))!!.held)
            val card = repo.library.continueWatching(null).single()
            assertEquals(what, EpisodeRef(1, 2), card.episode?.copy(name = null))
            assertEquals(what, listOf("$showRow S1E2"), list(VodContinue.HOME))
            assertEquals(what, listOf("$showRow S1E2"), list(VodContinue.SHOWS))
            restart()
        }
    }

    @Test
    fun aJumpAfterTheEndRecordsThePlaceAgain() {
        // Next episode pressed, but there's no next to play after all: the episode plays on.
        val p = bbPlaying(next = null)
        val epMs = 58 * min
        now += min
        p.recordEnd(57 * min, epMs, 57 * min, null)
        assertFalse(p.ended(playNext = true))
        // A save while it plays on records nothing after the end...
        now += min
        p.record(57 * min + 20_000, epMs, 57 * min, null)
        assertEquals(epMs - min, repo.library.positionOf(ep(1))!!.positionMs)
        // ...until the viewer jumps: where it lands is the place again.
        p.jumped()
        now += min
        p.record(20 * min, epMs, 57 * min, null)
        assertEquals(20 * min, repo.library.positionOf(ep(1))!!.positionMs)
    }
}
