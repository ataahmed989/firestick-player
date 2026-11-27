package app.fireplayer.history

import app.fireplayer.vod.Card
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Kind
import app.fireplayer.vod.Page
import app.fireplayer.vod.RowSpec
import app.fireplayer.vod.RowStyle
import app.fireplayer.vod.Source
import app.fireplayer.vod.StartAt
import app.fireplayer.vod.Title
import app.fireplayer.vod.data.Cinemeta
import app.fireplayer.vod.data.DefaultVodRepository
import app.fireplayer.vod.data.DefaultVodRepositoryTest.FakeHttp
import app.fireplayer.vod.data.DefaultVodRepositoryTest.FakeTmdb
import app.fireplayer.vod.data.Fixtures
import app.fireplayer.vod.data.JsonCache
import app.fireplayer.vod.data.MemoryIdStore
import app.fireplayer.vod.data.MemoryLibraryStore
import app.fireplayer.vod.data.RowLayoutStore
import app.fireplayer.vod.data.RowLayouts
import app.fireplayer.vod.play.VodLaunch
import app.fireplayer.vod.sources.RecordedLibrary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.Executor

/**
 * With a page's Next Episodes row off, its Continue Watching has the caught-up shows' new episodes instead, from their
 * start, placed by their air date among the others' last plays; with it on, as before; never both on one page. Glass
 * Home's row is Home's, and is told when Home's Next Episodes row goes on or off.
 */
class NewEpisodesInContinueTest {
    private val min = 60_000L
    // Breaking Bad's fixture: S1 E1-2 aired in January 2008, S2 E1 on 2009-03-09.
    private var now = Instant.parse("2009-01-01T12:00:00Z").toEpochMilli()
    private val s2e1Aired = Instant.parse("2009-03-09T05:00:00Z").toEpochMilli()
    private val direct = Executor { it.run() }
    private val dir: File = Files.createTempDirectory("cwnew").toFile()
    private val store = MemoryLibraryStore()
    private val rowStore = RowLayoutStore.Memory()

    private val godfather = Title("tt0068646", Kind.MOVIE, "The Godfather")
    private val pulp = Title("tt0110912", Kind.MOVIE, "Pulp Fiction")
    private val bb = Title("tt0903747", Kind.SERIES, "Breaking Bad")

    private val http = FakeHttp().apply { answers[Cinemeta.metaUrl(Kind.SERIES, bb.id)] = Fixtures.text("cinemeta_meta_series.json") }
    private val mainQueue = ArrayList<Runnable>()
    private val main = Executor { mainQueue += it }
    private var notices = 0

    private val repo: DefaultVodRepository = DefaultVodRepository(
        http = http,
        tmdb = FakeTmdb(),
        tmdbKey = { "" },
        cache = JsonCache(File(dir, "vod")) { now },
        idStore = MemoryIdStore(),
        libraryStore = store,
        main = main,
        io = direct,
        net = direct,
        lookups = direct,
        clock = { now },
        rowStore = rowStore,
    ).also { r -> ContinueNotice.attach(r, { mainQueue += it }) { notices++ } }

    private val homeContinue = RowSpec("home.continue", "Continue Watching", RowStyle.WIDE, Source.ContinueWatching(null), continueRow = true)
    private val homeNext = RowSpec("home.next_episodes", "Next Episodes", RowStyle.POSTER, Source.NextEpisodes())
    private val showsContinue = RowSpec("shows.continue", "Continue Watching · Series", RowStyle.WIDE, Source.ContinueWatching(Kind.SERIES), continueRow = true)
    private val showsNext = RowSpec("shows.next_episodes", "Next Episodes", RowStyle.POSTER, Source.NextEpisodes())

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun runMain() {
        while (mainQueue.isNotEmpty()) mainQueue.removeAt(0).run()
    }

    private fun cards(row: RowSpec): List<Card> {
        var got: List<Card> = emptyList()
        repo.load(row, 0, 100) { c, _ -> got = c }
        runMain()
        return got
    }

    private fun line(c: Card) = c.title.name + (c.episode?.let { " S${it.season}E${it.episode}" } ?: "") + (c.caption?.let { " ($it)" } ?: "")

    private fun glassHome(): List<String> = VodContinue.rows(repo, emptyList()).map { r ->
        r.title + (if (r.kind == WatchHistory.Kind.EPISODE) " S${r.season}E${r.episode}" else "")
    }

    /** Breaking Bad caught up on S1 E2 in January; the films played in March around S2 E1's airing on the 9th. */
    private fun caughtUpThenNewEpisode() {
        repo.details(bb) { _, _ -> }
        runMain()
        now += min
        RecordedLibrary(repo.library).recordPlayback(bb, EpisodeRef(1, 2), 47 * min, 47 * min, null, null)
        now = Instant.parse("2009-03-05T20:00:00Z").toEpochMilli()
        RecordedLibrary(repo.library).recordPlayback(godfather, null, 80 * min, 175 * min, null, null)
        now = Instant.parse("2009-03-11T20:00:00Z").toEpochMilli()
        RecordedLibrary(repo.library).recordPlayback(pulp, null, 50 * min, 154 * min, null, null)
        now = Instant.parse("2009-03-12T20:00:00Z").toEpochMilli()
        runMain()
        notices = 0
    }

    @Test
    fun withNextEpisodesOnTheNewEpisodeIsThereAloneAsBefore() {
        caughtUpThenNewEpisode()
        assertEquals(listOf("Pulp Fiction (1h 44m left)", "The Godfather (1h 35m left)"), cards(homeContinue).map { line(it) })
        assertEquals(listOf("Breaking Bad S2E1 (S2 E1)"), cards(homeNext).map { line(it) })
        assertEquals(listOf("Pulp Fiction", "The Godfather"), glassHome())
        assertFalse(repo.inContinueWatching(bb.id))
        assertNull(repo.continueCard(bb))
    }

    @Test
    fun withHomesNextEpisodesOffHomesContinueWatchingAndGlassHomeHaveIt() {
        caughtUpThenNewEpisode()
        repo.rowSettings.setOn(Page.HOME, homeNext.key, false)
        runMain()
        // Glass Home told once.
        assertEquals(1, notices)
        // By when it aired (the 9th): after Pulp Fiction (the 11th), before The Godfather (the 5th); from its start.
        val home = cards(homeContinue)
        assertEquals(listOf("Pulp Fiction (1h 44m left)", "Breaking Bad S2E1 (S2 E1)", "The Godfather (1h 35m left)"), home.map { line(it) })
        assertNull(home[1].progress)
        assertEquals(listOf("Pulp Fiction", "Breaking Bad S2E1", "The Godfather"), glassHome())
        val row = VodContinue.rows(repo, emptyList())[1]
        assertEquals(s2e1Aired, row.lastWatched)
        assertEquals(0L, row.positionMs)
        // TV Shows keeps its Next Episodes row on: there it's in Next Episodes, not in its Continue Watching. Never both.
        assertTrue(cards(showsContinue).isEmpty())
        assertEquals(listOf("Breaking Bad S2E1 (S2 E1)"), cards(showsNext).map { line(it) })
        // In Continue Watching for the title page's Remove and the card's own changes.
        assertTrue(repo.inContinueWatching(bb.id))
        assertEquals(EpisodeRef(2, 1), repo.continueCard(bb)?.episode?.copy(name = null))
        // Glass Home's play of its row, and OK on the card: S2 E1 from the start.
        val entry = repo.library.continueEntry(Kind.SERIES, bb.id, repo.newInContinue(Page.HOME))!!
        assertEquals(EpisodeRef(2, 1), entry.episode?.copy(name = null))
        val play = VodLaunch.fromContinueCard(home[1], repo, null)!!
        assertEquals(StartAt.Beginning, play.start)
        assertEquals(2, play.target.episode?.season)
        assertEquals(1, play.target.episode?.episode)

        // Back on: as before, and Glass Home told again.
        repo.rowSettings.setOn(Page.HOME, homeNext.key, true)
        runMain()
        assertEquals(2, notices)
        assertEquals(listOf("Pulp Fiction", "The Godfather"), glassHome())
        assertEquals(listOf("Breaking Bad S2E1 (S2 E1)"), cards(homeNext).map { line(it) })
        // A change that leaves every page's Next Episodes as it was: nobody told.
        repo.rowSettings.setOn(Page.HOME, "home.popular_movies", false)
        runMain()
        assertEquals(2, notices)
    }

    @Test
    fun offOnEveryPageTheShowsPageHasItToo() {
        caughtUpThenNewEpisode()
        repo.rowSettings.setOn(Page.HOME, homeNext.key, false)
        repo.rowSettings.setOn(Page.SHOWS, showsNext.key, false)
        runMain()
        assertEquals(listOf("Breaking Bad S2E1 (S2 E1)"), cards(showsContinue).map { line(it) })
        assertEquals(3, cards(homeContinue).size)
    }

    @Test
    fun removeTakesTheNewEpisodeOutOfBothUntilPlayed() {
        caughtUpThenNewEpisode()
        repo.rowSettings.setOn(Page.HOME, homeNext.key, false)
        runMain()
        // Glass Home's Remove on its row.
        assertTrue(VodContinue.remove(repo, LibraryContinue.idOf(Kind.SERIES, bb.id)))
        runMain()
        assertEquals(listOf("Pulp Fiction", "The Godfather"), glassHome())
        assertEquals(2, cards(homeContinue).size)
        // Out of Next Episodes too (TV Shows', still on).
        assertTrue(cards(showsNext).isEmpty())
        // Played: back, at that episode.
        now += min
        RecordedLibrary(repo.library).recordPlayback(bb, EpisodeRef(2, 1), 10 * min, 47 * min, null, null)
        assertEquals("Breaking Bad S2E1 (S2 E1 · 37m left)", line(cards(homeContinue).first()))
    }

    @Test
    fun markUnwatchedKeepsItWhereItWas() {
        caughtUpThenNewEpisode()
        repo.rowSettings.setOn(Page.HOME, homeNext.key, false)
        runMain()
        // In Home's Continue Watching: stays there, back at the start of the show.
        repo.setWatched(bb, false)
        val card = cards(homeContinue).first { it.title.id == bb.id }
        assertEquals(EpisodeRef(1, 1), card.episode?.copy(name = null))
        assertTrue((card.progress ?: 0f) == 0f)
    }

    @Test
    fun markUnwatchedWithNextEpisodesOnKeepsItOutAsBefore() {
        caughtUpThenNewEpisode()
        repo.setWatched(bb, false)
        assertTrue(cards(homeContinue).none { it.title.id == bb.id })
        assertFalse(repo.inContinueWatching(bb.id))
    }

    @Test
    fun whichPagesTakeTheNewEpisodes() {
        val settings = repo.rowSettings
        for (page in Page.entries) assertFalse(page.name, settings.newInContinue(page))
        settings.setOn(Page.HOME, homeNext.key, false)
        assertTrue(settings.newInContinue(Page.HOME))
        assertFalse(settings.newInContinue(Page.SHOWS))
        // Home's Continue Watching off as well: nowhere there to take them.
        settings.setOn(Page.HOME, homeContinue.key, false)
        assertFalse(settings.newInContinue(Page.HOME))
        // Movies has no Next Episodes row, but its Continue Watching is films only.
        assertFalse(settings.newInContinue(Page.MOVIES))
        // An added Continue Watching · Series on Movies, with no Next Episodes row there: it takes them.
        assertTrue(settings.add(Page.MOVIES, RowLayouts.addedKey(Page.MOVIES, "continue.series")))
        assertTrue(settings.newInContinue(Page.MOVIES))
        // And an added Next Episodes row there: back to that row.
        assertTrue(settings.add(Page.MOVIES, RowLayouts.addedKey(Page.MOVIES, "next_episodes")))
        assertFalse(settings.newInContinue(Page.MOVIES))
        // A row's page by its key.
        assertEquals(Page.HOME, RowLayouts.pageOf("home.continue"))
        assertEquals(Page.MOVIES, RowLayouts.pageOf("movies.add.continue.series"))
        assertNull(RowLayouts.pageOf("continue"))
    }
}
