package app.fireplayer.history

import app.fireplayer.vod.Card
import app.fireplayer.vod.Episode
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.TitleDetails
import app.fireplayer.vod.Kind
import app.fireplayer.vod.Page
import app.fireplayer.vod.RowSpec
import app.fireplayer.vod.RowStyle
import app.fireplayer.vod.Source
import app.fireplayer.vod.Title
import app.fireplayer.vod.data.Captions
import app.fireplayer.vod.data.Cinemeta
import app.fireplayer.vod.data.DefaultVodRepository
import app.fireplayer.vod.data.DefaultVodRepositoryTest.FakeHttp
import app.fireplayer.vod.data.DefaultVodRepositoryTest.FakeTmdb
import app.fireplayer.vod.data.Fixtures
import app.fireplayer.vod.data.JsonCache
import app.fireplayer.vod.data.MemoryIdStore
import app.fireplayer.vod.data.MemoryLibraryStore
import app.fireplayer.vod.data.PageRows
import app.fireplayer.vod.data.VodLibrary
import app.fireplayer.vod.sources.RecordedLibrary
import app.fireplayer.vod.ui.RowRules
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
 * Glass Home's Continue Watching row is FirePlayer Home's, card for card: the provider's rows ([VodContinue.rows], what
 * [ContinueProvider] answers) against Home's Continue Watching row as its page loads and shows it (its first 15), after
 * every kind of change, with Glass Home told of each change once ([ContinueNotice]). (The videos other apps opened here
 * aren't in it because [ContinueProvider] lists nothing but [VodContinue.rows], which reads only the library; their
 * history is kept, untouched by anything here.) A Remove in either app is a Remove in both, and a title taken out comes
 * back only when it's played again.
 */
class ContinueSyncTest {
    private val min = 60_000L
    private val day = 24 * 3600_000L
    // Breaking Bad's first season has aired, its second not yet (the fixture's episodes: S1 E1-2 in Jan 2008, S2 E1 in
    // March 2009).
    private var now = Instant.parse("2009-01-01T12:00:00Z").toEpochMilli()
    private val direct = Executor { it.run() }
    private val dir: File = Files.createTempDirectory("cwsync").toFile()
    private val store = MemoryLibraryStore()

    private val godfather = Title("tt0068646", Kind.MOVIE, "The Godfather")
    private val spirited = Title("tt0245429", Kind.MOVIE, "Spirited Away")
    private val pulp = Title("tt0110912", Kind.MOVIE, "Pulp Fiction")
    private val angryMen = Title("tt0050083", Kind.MOVIE, "12 Angry Men")
    private val rotk = Title("tt0167260", Kind.MOVIE, "The Lord of the Rings: The Return of the King")
    private val bb = Title("tt0903747", Kind.SERIES, "Breaking Bad")

    private val http = FakeHttp().apply { answers[Cinemeta.metaUrl(Kind.SERIES, bb.id)] = Fixtures.text("cinemeta_meta_series.json") }
    private val continueSpec = RowSpec("home.continue", "Continue Watching", RowStyle.WIDE, Source.ContinueWatching(null), continueRow = true)
    private val moviesSpec = PageRows.rows(Page.MOVIES, 2009).single { it.key == "movies.continue" }
    private val showsSpec = PageRows.rows(Page.SHOWS, 2009).single { it.key == "shows.continue" }

    /** FirePlayer's main thread: what's posted to it (the library's listeners, the notice) waits for [runMain]. */
    private val mainQueue = ArrayList<Runnable>()
    private val main = Executor { mainQueue += it }
    /** Glass Home told (content://…continue/items). */
    private var notices = 0

    private fun newRepo(): DefaultVodRepository = DefaultVodRepository(
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
    ).also { r ->
        // As VodRepositories wires it on the TV: the library's listeners on FirePlayer's main thread, the notice after.
        ContinueNotice.attach(r, { mainQueue += it }) { notices++ }
    }

    private var repo = newRepo()
    private val recorded get() = RecordedLibrary(repo.library)

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun runMain() {
        while (mainQueue.isNotEmpty()) mainQueue.removeAt(0).run()
    }

    /** A play's save, as the player makes it (now and then, and on leaving). */
    private fun play(title: Title, positionMs: Long, durationMs: Long, episode: EpisodeRef? = null) {
        now += min
        recorded.recordPlayback(title, episode, positionMs, durationMs, creditsAtMs = null)
    }

    /**
     * FirePlayer Home's Continue Watching row (or [spec]'s: the Movies or TV Shows page's) as its page loads and shows
     * it (VodPage: one more than shown asked for, [RowRules.shown]), or with [all] every card its source has (its See
     * all): each card's row id, episode and minutes left.
     */
    private fun firePlayer(all: Boolean = false, spec: RowSpec = continueSpec): List<String> {
        var got: List<Card> = emptyList()
        repo.load(spec, 0, if (all) 100 else RowRules.ROW_CARDS + 1) { cards, _ -> got = cards }
        runMain()
        val shown = if (all) got else RowRules.shown(got)
        return shown.map { c -> line(LibraryContinue.idOf(c.title.kind, c.title.id), c.episode, minutesIn(c.caption)) }
    }

    /** Glass Home's row: the provider's rows in their order, with Glass Home's own time left. */
    private fun glassHome(): List<String> = glassHome(VodContinue.rows(repo))

    /** Glass Home's list [name] of the three the provider answers items?lists with ([VodContinue.lists]). */
    private fun glassHome(name: String): List<String> = glassHome(VodContinue.lists(repo).single { it.first == name }.second)

    private fun glassHome(rows: List<WatchHistory.Row>): List<String> = rows.map { r ->
        val ep = if (r.kind == WatchHistory.Kind.EPISODE) EpisodeRef(r.season, r.episode) else null
        line(r.id, ep, if (r.durationMs > 0) glassMinutes(r.positionMs, r.durationMs) else null)
    }

    private fun line(id: String, ep: EpisodeRef?, minutes: Long?) =
        "$id ${ep?.let { "S${it.season}E${it.episode}" } ?: "film"} ${minutes ?: "-"}"

    /** "S1 E2 · 1h 10m left" → 70; null without a time left. */
    private fun minutesIn(caption: String?): Long? {
        val m = Regex("""(?:(\d+)h)?\s*(?:(\d+)m)? left""").find(caption ?: return null) ?: return null
        return (m.groupValues[1].toLongOrNull() ?: 0L) * 60 + (m.groupValues[2].toLongOrNull() ?: 0L)
    }

    /** Glass Home's minutes left (its ContinueWatching.Item.timeLeft): to the nearest minute (half up), at least one. */
    private fun glassMinutes(positionMs: Long, durationMs: Long): Long =
        maxOf(1L, (maxOf(0L, durationMs - maxOf(0L, positionMs)) + 30_000L) / 60_000L)

    /** After FirePlayer's main thread has run: both rows the same, and Glass Home told [told] more times. */
    private fun assertSame(what: String, told: Int = 1): List<String> {
        val before = notices
        runMain()
        assertEquals("$what: Glass Home told", told, notices - before)
        val rows = firePlayer()
        // Glass Home's row is Home's, whole: nothing more, nothing less.
        assertEquals(what, rows, glassHome())
        assertEquals("$what: a title once", rows.size, rows.map { it.substringBefore(' ') }.distinct().size)
        return rows
    }

    private fun id(t: Title) = LibraryContinue.idOf(t.kind, t.id)

    @Test
    fun glassHomesRowIsFirePlayersAfterEveryChange() {
        // Breaking Bad's episodes as its title page stored them.
        repo.details(bb) { _, _ -> }
        runMain()
        notices = 0

        play(rotk, 60 * min, 200 * min)
        play(angryMen, 30 * min, 96 * min)
        play(pulp, 1_000_000L, 9_240_000L)
        play(spirited, 3_000_000L, 7_500_000L)
        play(bb, 20 * min, 47 * min, EpisodeRef(1, 1))
        play(godfather, 9_005_000L, 10_528_000L)
        var rows = assertSame("six played (one notice for them all)")
        assertEquals("${id(godfather)} film 25", rows.first())
        assertEquals(6, rows.size)

        // Played on from FirePlayer's own Continue card: a save now and then, then the last one on leaving.
        play(godfather, 9_100_000L, 10_528_000L)
        assertSame("a save while it plays")
        play(godfather, 9_429_000L, 10_528_000L)
        rows = assertSame("the save on leaving")
        assertEquals("${id(godfather)} film 18", rows.first())

        // Another played: first now.
        play(bb, 30 * min, 47 * min, EpisodeRef(1, 1))
        rows = assertSame("an episode played on")
        assertEquals("${id(bb)} S1E1 17", rows.first())

        // Remove on its card (or its title page) in FirePlayer.
        repo.removeFromContinueWatching(pulp.id)
        rows = assertSame("removed in FirePlayer")
        assertTrue(rows.none { it.startsWith(id(pulp)) })

        // Remove in Glass Home.
        assertTrue(VodContinue.remove(repo, id(spirited)))
        rows = assertSame("removed in Glass Home")
        assertTrue(rows.none { it.startsWith(id(spirited)) })

        // Marked watched: out of both.
        repo.setWatched(godfather, true)
        rows = assertSame("marked watched")
        assertTrue(rows.none { it.startsWith(id(godfather)) })

        // Marked unwatched: in its place in both, back at the start.
        val at = rows.indexOfFirst { it.startsWith(id(angryMen)) }
        repo.setWatched(angryMen, false)
        rows = assertSame("marked unwatched")
        assertEquals("${id(angryMen)} film 96", rows[at])

        // A film watched to its end: out of both.
        play(rotk, 196 * min, 200 * min)
        rows = assertSame("a film finished")
        assertTrue(rows.none { it.startsWith(id(rotk)) })

        // An episode watched to its end: the show goes on with the next in both, from its start.
        play(bb, 46 * min, 47 * min, EpisodeRef(1, 1))
        rows = assertSame("an episode finished")
        assertEquals("${id(bb)} S1E2 -", rows.first())

        // Its next one played a while: where it is, in both.
        play(bb, 12 * min, 48 * min, EpisodeRef(1, 2))
        rows = assertSame("the next episode played")
        assertEquals(listOf("${id(bb)} S1E2 36", "${id(angryMen)} film 96"), rows)
    }

    @Test
    fun glassHomesTimeLeftIsFirePlayersToTheMinute() {
        var left = 0L
        while (left < 10 * 3600_000L) {
            assertEquals("$left ms left", minutesIn(Captions.left(left)), glassMinutes(0L, left))
            left += 7_919L
        }
        // Past the end (a length that came short): as FirePlayer's caption says. (No position is kept below 0.)
        assertEquals(minutesIn(Captions.caption(null, 50 * min, 40 * min)), glassMinutes(50 * min, 40 * min))
    }

    @Test
    fun removedInGlassHomeIsGoneFromFirePlayerAtOnce() {
        play(spirited, 30 * min, 125 * min)
        play(godfather, 9_005_000L, 10_528_000L)
        runMain()
        // Home's page listens while it's in front (VodPage); here it is told as the change is made.
        var pageTold = 0
        repo.addLibraryListener { pageTold++ }
        val told = notices

        assertTrue(VodContinue.remove(repo, id(godfather)))
        runMain()
        assertEquals("Home's rows reload at once", 1, pageTold)
        assertEquals(listOf("${id(spirited)} film 95"), firePlayer())
        // Its title page: no longer in Continue Watching (no Remove action, no card to change).
        assertFalse(repo.inContinueWatching(godfather.id))
        assertNull(repo.continueCard(godfather))
        // Glass Home's own row, asked again on the notice, agrees.
        assertEquals(told + 1, notices)
        assertEquals(firePlayer(), glassHome())

        // Asked again (a second press, a stale card): nothing to do, nobody told.
        assertFalse(VodContinue.remove(repo, id(godfather)))
        assertFalse(VodContinue.remove(repo, "0123456789abcdef"))
        assertFalse(VodContinue.remove(repo, "vod-x-tt1"))
        runMain()
        assertEquals(1, pageTold)
        assertEquals(told + 1, notices)
    }

    @Test
    fun removedInFirePlayerIsGoneFromGlassHomeAtOnce() {
        play(spirited, 30 * min, 125 * min)
        play(godfather, 9_005_000L, 10_528_000L)
        runMain()
        val told = notices
        repo.removeFromContinueWatching(spirited.id)
        // Glass Home is told once FirePlayer's main thread runs (right after the Remove), and its next ask has it gone.
        assertEquals(told, notices)
        runMain()
        assertEquals(told + 1, notices)
        assertEquals(listOf("${id(godfather)} film 25"), glassHome())
        assertEquals(firePlayer(), glassHome())
    }

    @Test
    fun aRemovedFilmComesBackOnlyWhenPlayedAgain() {
        play(godfather, 9_005_000L, 10_528_000L)
        play(spirited, 30 * min, 125 * min)
        assertSame("both played")
        assertTrue(VodContinue.remove(repo, id(godfather)))
        assertSame("removed in Glass Home")

        // Nothing else brings it back: other plays, marks on other titles, days going by, FirePlayer started again.
        play(pulp, 10 * min, 154 * min)
        repo.setWatched(spirited, true)
        repo.setWatched(spirited, false)
        runMain()
        now += 30 * day
        repo = newRepo()
        val rows = assertSame("a month later, FirePlayer started again", told = 0)
        assertTrue(rows.none { it.startsWith(id(godfather)) })
        assertFalse(repo.inContinueWatching(godfather.id))
        // Its resume point stays (its title page offers Resume).
        assertEquals(9_005_000L, repo.library.progressOf(Kind.MOVIE, godfather.id)?.positionMs)

        // Played again: back in both, first.
        play(godfather, 9_100_000L, 10_528_000L)
        assertEquals("${id(godfather)} film 24", assertSame("played again").first())
    }

    @Test
    fun aShowsRowRemovesTheShowUntilAnyEpisodeIsPlayedAgain() {
        repo.details(bb) { _, _ -> }
        play(bb, 20 * min, 47 * min, EpisodeRef(1, 1))
        play(godfather, 9_005_000L, 10_528_000L)
        assertSame("played")
        // Glass Home's card for the show is its episode's: Remove takes the show out (as Remove on its card here).
        assertTrue(VodContinue.remove(repo, id(bb)))
        var rows = assertSame("the show removed in Glass Home")
        assertEquals(listOf("${id(godfather)} film 25"), rows)
        assertFalse(repo.inContinueWatching(bb.id))

        // Its second season airs: still out of both (and not in Next Episodes): out until it's played again.
        now = Instant.parse("2009-04-01T12:00:00Z").toEpochMilli()
        assertTrue(repo.library.nextEpisodes().isEmpty())
        rows = assertSame("a new episode aired", told = 0)
        assertEquals(listOf("${id(godfather)} film 25"), rows)

        // Any episode played again brings the show back, at that episode, in both.
        play(bb, 5 * min, 48 * min, EpisodeRef(1, 2))
        rows = assertSame("played again")
        assertEquals("${id(bb)} S1E2 43", rows.first())
    }

    /** Glass Home's cards for The Godfather: each row's id and minutes left. */
    private fun godfathers(): List<String> = VodContinue.rows(repo).filter { it.title == "The Godfather" }
        .map { "${it.id} ${glassMinutes(it.positionMs, it.durationMs)}" }

    private val gf = Title("tt0068646", Kind.MOVIE, "The Godfather", year = "1972", tmdbId = 238)
    private val heat = Title("tt0113277", Kind.MOVIE, "Heat", year = "1995", tmdbId = 949)

    /**
     * A film taken out on its card here, played again, then watched to its end and marked unwatched: Glass Home's row is
     * Home's at each step, the film once, at the library's time left.
     */
    @Test
    fun aFilmRemovedPlayedAgainAndFinishedIsHomesInGlassHome() {
        play(gf, 9_005_000L, 10_528_000L)
        play(heat, 30 * min, 170 * min)
        assertEquals(listOf("${id(heat)} film 140", "${id(gf)} film 25"), assertSame("both played"))
        assertEquals(listOf("${id(gf)} 25"), godfathers())

        // Remove on its card in FirePlayer: out of both.
        repo.removeFromContinueWatching(gf.id)
        assertEquals(listOf("${id(heat)} film 140"), assertSame("removed in FirePlayer"))

        // Played again here: back in both, first.
        play(gf, 9_100_000L, 10_528_000L)
        assertEquals(listOf("${id(gf)} film 24", "${id(heat)} film 140"), assertSame("played again here"))

        // Watched to its end here, then marked unwatched (it was done: kept out of Continue Watching): in neither.
        play(gf, 10_500_000L, 10_528_000L)
        assertSame("watched to its end")
        repo.setWatched(gf, false)
        assertEquals(listOf("${id(heat)} film 140"), assertSame("marked unwatched"))
        assertEquals(emptyList<String>(), godfathers())
    }

    /**
     * Home's row with more than 15 titles: films, a show gone on to its next episode, one marked unwatched, titles taken
     * out, marked watched, watched to their end. Glass Home's row is Home's first 15
     * cards, in Home's order, at the same episode and time left; the 16th and later (Home's See all) aren't in it.
     */
    @Test
    fun glassHomesRowIsHomesFirst15CardForCard() {
        repo.details(bb) { _, _ -> }
        runMain()
        val films = (1..16).map { i -> Title("tt90000%02d".format(i), Kind.MOVIE, "Film $i", year = "2001", tmdbId = 5000 + i) }
        for ((i, f) in films.withIndex()) play(f, (10L + i) * min, 120 * min)
        play(gf, 9_005_000L, 10_528_000L)
        play(heat, 30 * min, 170 * min)
        play(pulp, 10 * min, 154 * min)
        play(rotk, 60 * min, 200 * min)
        play(bb, 20 * min, 47 * min, EpisodeRef(1, 1))
        // Breaking Bad's first episode watched to its end: it goes on with the next, from its start.
        play(bb, 46 * min, 47 * min, EpisodeRef(1, 1))
        // Taken out (here, and in Glass Home), marked watched, watched to its end.
        repo.removeFromContinueWatching(pulp.id)
        assertTrue(VodContinue.remove(repo, id(heat)))
        repo.setWatched(films[15], true)
        play(rotk, 198 * min, 200 * min)
        // Marked unwatched while in Continue Watching: stays, back at its start.
        repo.setWatched(gf, false)
        runMain()

        val all = firePlayer(all = true)
        // In Home's row's source: the show, The Godfather, and the 15 films not marked; Home shows 15, See all the rest.
        assertEquals(17, all.size)
        val home = firePlayer()
        assertEquals(RowRules.ROW_CARDS, home.size)
        assertEquals(all.take(15), home)
        assertEquals(home, glassHome())
        assertEquals("${id(bb)} S1E2 -", home[0])
        assertEquals("${id(gf)} film 175", home[1])
        // The latest played first: films 15 down to 3; films 2 and 1 only in See all.
        assertEquals((15 downTo 3).map { "${id(films[it - 1])} film ${120 - (10 + it - 1)}" }, home.drop(2))
        val shown = glassHome().map { it.substringBefore(' ') }
        assertFalse(id(films[1]) in shown || id(films[0]) in shown)
        // Nothing taken out, marked or done.
        for (t in listOf(pulp, heat, rotk, films[15])) assertFalse(id(t) in shown)
        // Every card of it is the library's (ContinueProvider sends its Episodes, Remove and play there) and answers
        // Glass Home's asks: its play, and (a show's) its Episodes window's, as on Home.
        assertTrue(VodContinue.rows(repo).all { LibraryContinue.isLibraryId(it.id) })
        for (r in VodContinue.rows(repo)) {
            val (kind, titleId) = LibraryContinue.parse(r.id)!!
            assertTrue(r.id, repo.library.continueEntry(kind, titleId) != null)
            assertEquals(r.id, kind == Kind.SERIES, r.hasEpisodes)
        }
    }

    /** After FirePlayer's main thread has run: Glass Home's three lists FirePlayer's three rows, told [told] more times. */
    private fun assertLists(what: String, told: Int = 1): Map<String, List<String>> {
        val before = notices
        runMain()
        assertEquals("$what: Glass Home told", told, notices - before)
        assertEquals(listOf("home", "movies", "shows"), VodContinue.lists(repo).map { it.first })
        // Home's list is what items alone answers (the Glass Homes on the TVs now), and Home's row.
        assertEquals("$what: Home's", firePlayer(), glassHome("home"))
        assertEquals("$what: as items answers", glassHome(), glassHome("home"))
        assertEquals("$what: Movies'", firePlayer(spec = moviesSpec), glassHome("movies"))
        assertEquals("$what: TV Shows'", firePlayer(spec = showsSpec), glassHome("shows"))
        return listOf("home", "movies", "shows").associateWith { glassHome(it) }
    }

    /**
     * Glass Home's Movies and TV Shows tabs (items?lists): the Movies page's and the TV Shows page's Continue Watching
     * rows, card for card (their first 15, in their order, at the same episode and time left), beside Home's row, which
     * stays what items alone answers. A change only a tab's row shows is told too, and a Remove in a tab is one here.
     */
    @Test
    fun glassHomesTabsAreTheMoviesAndTvShowsPagesRows() {
        val films = (1..17).map { i -> Title("tt91000%02d".format(i), Kind.MOVIE, "Film $i", year = "2001", tmdbId = 6000 + i) }
        val shows = (1..17).map { i -> Title("tt92000%02d".format(i), Kind.SERIES, "Show $i", year = "2002", tmdbId = 7000 + i) }
        for (i in films.indices) {
            play(films[i], (10L + i) * min, 120 * min)
            play(shows[i], (5L + i) * min, 45 * min, EpisodeRef(1, i + 1))
        }
        repo.setWatched(films[16], true)
        var lists = assertLists("17 films (the last marked watched) and 17 shows played in turn")
        val home = lists.getValue("home")
        // Home's 15: the newest shows and films together; each page's: its kind's newest 15, older ones Home hasn't.
        assertEquals(RowRules.ROW_CARDS, home.size)
        assertEquals("${id(shows[16])} S1E17 24", home[0])
        assertEquals("${id(films[15])} film 95", home[2])
        assertEquals((15 downTo 1).map { "${id(films[it])} film ${110 - it}" }, lists["movies"])
        assertEquals((16 downTo 2).map { "${id(shows[it])} S1E${it + 1} ${40 - it}" }, lists["shows"])
        assertFalse(home.any { it.startsWith(id(films[1])) || it.startsWith(id(shows[2])) })

        // Taken out of the TV Shows tab (a show Home's row hasn't): told, gone from it, the next one in; Home's as it was.
        assertTrue(VodContinue.remove(repo, id(shows[3])))
        lists = assertLists("a show removed in Glass Home's TV Shows tab")
        assertEquals(home, lists["home"])
        assertEquals((16 downTo 1).filter { it != 3 }.map { id(shows[it]) }, lists.getValue("shows").map { it.substringBefore(' ') })

        // Marked watched here (a film only the Movies page's row shows): told, out of the Movies tab.
        repo.setWatched(films[2], true)
        lists = assertLists("a film marked watched")
        assertEquals(home, lists["home"])
        assertEquals((15 downTo 0).filter { it != 2 }.map { id(films[it]) }, lists.getValue("movies").map { it.substringBefore(' ') })

        // Played again: first in Home's row and its page's.
        play(films[0], 30 * min, 120 * min)
        lists = assertLists("an old film played again")
        assertEquals("${id(films[0])} film 90", lists.getValue("home").first())
        assertEquals("${id(films[0])} film 90", lists.getValue("movies").first())
        assertEquals(home.dropLast(1), lists.getValue("home").drop(1))
    }

    /**
     * Glass Home's three lists come from one reading of the library's Continue Watching: a show gone on to its next
     * episode has its episodes read once per answer, not once per list (seen in review: three readings, each a read from
     * disk once more such shows were played than the details kept in memory), and each kind's list is still its page's,
     * continueEntries(kind) as the row shows it.
     */
    @Test
    fun glassHomesListsReadEachShowsEpisodesOnce() {
        val reads = HashMap<String, Int>()
        fun episodes(show: Title) = (1..3).map { e ->
            Episode(Episode.idOf(show.id, 1, e), 1, e, "E$e", released = now - 400 * day + e * day)
        }
        val library = VodLibrary(MemoryLibraryStore(), { now }, direct, { t -> reads[t.id] = (reads[t.id] ?: 0) + 1; episodes(t) }) {}
        val shows = (1..8).map { i -> Title("tt93000%02d".format(i), Kind.SERIES, "Show $i", year = "2002", tmdbId = 8000 + i) }
        val films = (1..8).map { i -> Title("tt94000%02d".format(i), Kind.MOVIE, "Film $i", year = "2001", tmdbId = 9000 + i) }
        for (i in shows.indices) {
            now += min
            library.recordProgress(shows[i], EpisodeRef(1, 1), 44 * min, 45 * min)   // over: on to E2
            now += min
            library.recordProgress(films[i], null, 10 * min, 100 * min)
        }
        reads.clear()
        val lists = VodContinue.lists(library, homeNew = false, showsNew = false).toMap()
        assertEquals("each show's episodes read once", shows.associate { it.id to 1 }, reads)
        // Home's and the TV Shows page's Next Episodes rows set differently: still one reading.
        reads.clear()
        assertEquals(lists, VodContinue.lists(library, homeNew = true, showsNew = false).toMap())
        assertEquals("each show's episodes read once, the pages' rows differing", shows.associate { it.id to 1 }, reads)
        assertEquals(listOf("home", "movies", "shows"), VodContinue.lists(library, homeNew = false, showsNew = true).map { it.first })
        fun page(kind: Kind?) = RowRules.shown(library.continueEntries(kind)).mapNotNull { LibraryContinue.row(it) }
        assertEquals(page(null), lists["home"])
        assertEquals(page(Kind.MOVIE), lists["movies"])
        assertEquals(page(Kind.SERIES), lists["shows"])
        assertEquals(shows.reversed().map { id(it) }, lists.getValue("shows").map { it.id })
        assertTrue(lists.getValue("shows").all { it.season == 1 && it.episode == 2 && it.positionMs == 0L })
        assertEquals(films.reversed().map { id(it) }, lists.getValue("movies").map { it.id })
    }

    /**
     * The provider's items answer as Glass Home reads it ([VodContinue.items], what [ContinueProvider] answers): items
     * alone has the columns the Glass Homes on the TVs read, and nothing more, and is Home's row; asked with ?lists
     * ([VodContinue.LISTS]), the same columns and "list" after them, naming each row's list: Home's rows, then the Movies
     * page's, then the TV Shows page's. A newer Glass Home that found no "list" column would take the tabs from Home's.
     */
    @Test
    fun theProvidersAnswerIsWhatGlassHomeReads() {
        val films = (1..9).map { i -> Title("tt95000%02d".format(i), Kind.MOVIE, "Film $i", year = "2001", tmdbId = 6100 + i) }
        val shows = (1..9).map { i -> Title("tt96000%02d".format(i), Kind.SERIES, "Show $i", year = "2002", tmdbId = 7100 + i) }
        for (i in films.indices) {
            play(films[i], (10L + i) * min, 120 * min)
            play(shows[i], (5L + i) * min, 45 * min, EpisodeRef(2, i + 1))
        }
        runMain()
        val columns = listOf(
            "id", "kind", "title", "year", "season", "episode", "episode_title", "tmdb_id", "position_ms", "duration_ms",
            "last_watched", "source", "has_episodes",
        )
        assertEquals("lists", VodContinue.LISTS)
        assertEquals(columns, VodContinue.columns(false).toList())
        assertEquals(columns + "list", VodContinue.columns(true).toList())

        // items alone: Home's row, card for card, in the columns the Glass Homes on the TVs read
        val plain = VodContinue.items(repo, false, VodContinue.columns(false))
        assertEquals(firePlayer().map { it.substringBefore(' ') }, plain.map { it[0] })
        assertTrue(plain.all { it.size == columns.size })
        val last = films.last()
        assertEquals(
            listOf<Any?>(id(shows.last()), "episode", "Show 9", 2002, 2, 9, null, 7109, 13 * min, 45 * min, now, null, 1),
            plain[0].toList(),
        )
        assertEquals(
            listOf<Any?>(id(last), "movie", "Film 9", 2001, 0, 0, null, 6109, 18 * min, 120 * min, now - min, null, 0),
            plain[1].toList(),
        )

        // items?lists: the same rows with their list, Home's, then the Movies page's, then the TV Shows page's
        val all = VodContinue.items(repo, true, VodContinue.columns(true))
        val list = columns.size
        assertEquals(
            List(plain.size) { "home" } + List(films.size) { "movies" } + List(shows.size) { "shows" },
            all.map { it[list] },
        )
        assertEquals(plain.map { it.toList() }, all.filter { it[list] == "home" }.map { it.toList().dropLast(1) })
        assertEquals(firePlayer(spec = moviesSpec).map { it.substringBefore(' ') }, all.filter { it[list] == "movies" }.map { it[0] })
        assertEquals(firePlayer(spec = showsSpec).map { it.substringBefore(' ') }, all.filter { it[list] == "shows" }.map { it[0] })

        // Asked for some columns (a projection): those, in its order
        assertEquals(listOf<Any?>("home", id(shows.last())), VodContinue.items(repo, true, arrayOf("list", "id"))[0].toList())
    }

    /**
     * Seen in review: a show marked unwatched (it stays in Continue Watching, at S1 E1 0:00), then removed (in FirePlayer
     * or Glass Home), came back in both after Mark watched on one of its episodes, without being played. A show taken
     * out stays out until an episode of it is played; one Mark unwatched kept out still comes back on Mark watched.
     */
    @Test
    fun aRemovedShowStaysOutWhenItsEpisodesAreMarkedWatched() {
        var details: TitleDetails? = null
        repo.details(bb) { d, _ -> details = d }
        runMain()
        val episodes = details!!.episodes
        val s1e1 = episodes.first { it.season == 1 && it.episode == 1 }
        play(bb, 20 * min, 47 * min, EpisodeRef(1, 1))
        play(godfather, 9_005_000L, 10_528_000L)
        assertSame("played")

        repo.setWatched(bb, false)
        var rows = assertSame("marked unwatched: in its place, at its start")
        assertEquals("${id(bb)} S1E1 47", rows[1])
        assertTrue(VodContinue.remove(repo, id(bb)))
        rows = assertSame("removed in Glass Home")
        assertEquals(listOf("${id(godfather)} film 25"), rows)

        repo.markWatchedUpTo(details!!, s1e1)
        rows = assertSame("marked watched up to S1 E1: still out")
        assertEquals(listOf("${id(godfather)} film 25"), rows)
        repo.setWatched(bb, true)
        repo.setWatched(bb, false)
        rows = assertSame("marked watched, then unwatched: still out", told = 1)
        assertEquals(listOf("${id(godfather)} film 25"), rows)

        // Played: back in both.
        play(bb, 5 * min, 47 * min, EpisodeRef(1, 1))
        rows = assertSame("played again")
        assertEquals("${id(bb)} S1E1 42", rows.first())
    }

    @Test
    fun aBurstOfChangesIsOneNotice() {
        val queue = ArrayList<Runnable>()
        var told = 0
        val notice = ContinueNotice({ queue += it }) { told++ }
        notice.changed()
        notice.changed()
        notice.changed()
        assertEquals("one notice posted for the burst", 1, queue.size)
        queue.removeAt(0).run()
        assertEquals(1, told)
        // A change after it went: the next notice.
        notice.changed()
        assertEquals(1, queue.size)
        queue.removeAt(0).run()
        assertEquals(2, told)
        // FirePlayer being stopped as it tells: let go of, and the next change tells again.
        var tries = 0
        val failing = ContinueNotice({ it.run() }) {
            tries++
            throw SecurityException("stopping")
        }
        failing.changed()
        failing.changed()
        assertEquals(2, tries)
    }
}
