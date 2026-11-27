package app.fireplayer.history

import app.fireplayer.vod.Card
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.TitleDetails
import app.fireplayer.vod.Kind
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
 * every kind of change, with Glass Home told of each change once ([ContinueNotice]); the videos other apps opened here
 * are never in it. A Remove in either app is a Remove in both, and a title taken out comes back only when it's played
 * again.
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

    /** FirePlayer's main thread: what's posted to it (the library's listeners, the notice) waits for [runMain]. */
    private val mainQueue = ArrayList<Runnable>()
    private val main = Executor { mainQueue += it }
    /** Glass Home told (content://…continue/items). */
    private var notices = 0
    /**
     * The videos other apps (TiviMate) opened in FirePlayer's player, as ContinueWatching keeps them (never in Glass
     * Home's row now); the library's Remove and Mark watched take a title's copies away from them as
     * ContinueWatching.removeCopies does.
     */
    private val tivimate = ArrayList<WatchHistory.Row>()

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
        continueGone = { titles ->
            val gone = LibraryContinue.copiesOf(titles, tivimate)
            tivimate.removeAll { it.id in gone }
        },
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
     * FirePlayer Home's Continue Watching row as its page loads and shows it (VodPage: one more than shown asked for,
     * [RowRules.shown]), or with [all] every card its source has (its See all): each card's row id, episode and minutes
     * left.
     */
    private fun firePlayer(all: Boolean = false): List<String> {
        var got: List<Card> = emptyList()
        repo.load(continueSpec, 0, if (all) 100 else RowRules.ROW_CARDS + 1) { cards, _ -> got = cards }
        runMain()
        val shown = if (all) got else RowRules.shown(got)
        return shown.map { c -> line(LibraryContinue.idOf(c.title.kind, c.title.id), c.episode, minutesIn(c.caption)) }
    }

    /** Glass Home's row: the provider's rows in their order, with Glass Home's own time left. */
    private fun glassHome(): List<String> = VodContinue.rows(repo).map { r ->
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

    /** Glass Home's minutes left (its ContinueWatching.Item.timeLeft): to the nearest minute, at least one. */
    private fun glassMinutes(positionMs: Long, durationMs: Long): Long =
        maxOf(1L, Math.round(maxOf(0L, durationMs - maxOf(0L, positionMs)) / 60000.0))

    /** After FirePlayer's main thread has run: both rows the same, and Glass Home told [told] more times. */
    private fun assertSame(what: String, told: Int = 1): List<String> {
        val before = notices
        runMain()
        assertEquals("$what: Glass Home told", told, notices - before)
        val rows = firePlayer()
        // Glass Home's row is Home's, whole: nothing more (no video another app opened here), nothing less.
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

    /** A video another app (TiviMate) opened here, as Glass Home's row has it. */
    private fun other(id: String, name: String, year: Int, tmdbId: Int, positionMs: Long, durationMs: Long, at: Long) =
        WatchHistory.Row(id, WatchHistory.Kind.MOVIE, name, year, 0, 0, null, tmdbId, positionMs, durationMs, at, "host:8080")

    /** Glass Home's cards for The Godfather: each row's id and minutes left. */
    private fun godfathers(): List<String> = VodContinue.rows(repo).filter { it.title == "The Godfather" }
        .map { "${it.id} ${glassMinutes(it.positionMs, it.durationMs)}" }

    private val gf = Title("tt0068646", Kind.MOVIE, "The Godfather", year = "1972", tmdbId = 238)
    private val heat = Title("tt0113277", Kind.MOVIE, "Heat", year = "1995", tmdbId = 949)

    /**
     * The user's choice: Glass Home shows only what FirePlayer's Home shows. A copy of a library title TiviMate opened in
     * FirePlayer's player, played after the library's, and a video only TiviMate played: in neither row, whenever they
     * were played; Remove in either app still takes the title's copies from the history (ContinueWatching.removeCopies).
     */
    @Test
    fun videosOtherAppsOpenedAreNeverInGlassHomesRow() {
        play(gf, 9_005_000L, 10_528_000L)
        play(heat, 30 * min, 170 * min)
        now += min
        tivimate += other("00000000000000a1", "The Godfather", 1972, 238, 10 * min, 175 * min, now)
        tivimate += other("00000000000000a2", "Pulp Fiction", 1994, 680, 40 * min, 154 * min, now)
        runMain()
        // Home's two cards, in Home's order, The Godfather at the library's 25m left; TiviMate's videos nowhere.
        assertEquals(listOf("${id(heat)} film 140", "${id(gf)} film 25"), assertSame("TiviMate played since", told = 0))
        assertEquals(listOf("${id(gf)} 25"), godfathers())

        // Remove on its card in FirePlayer: out of both; TiviMate's copy goes from the history with it.
        repo.removeFromContinueWatching(gf.id)
        assertEquals(listOf("${id(heat)} film 140"), assertSame("removed in FirePlayer"))
        assertTrue(tivimate.none { it.title == "The Godfather" })

        // Played again in TiviMate after that: still only Home's cards.
        now += min
        tivimate += other("00000000000000a3", "The Godfather", 1972, 238, 12 * min, 175 * min, now)
        assertEquals(listOf("${id(heat)} film 140"), assertSame("played again in TiviMate", told = 0))

        // Played again here: back in both, the library's card, first.
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
     * out, marked watched, watched to their end, and videos TiviMate opened since. Glass Home's row is Home's first 15
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
        // TiviMate opened three videos since, one a copy of a film in the row.
        now += min
        tivimate += other("00000000000000c1", "Film 3", 2001, 5003, 5 * min, 120 * min, now)
        tivimate += other("00000000000000c2", "Some Other Film", 2010, 777, 5 * min, 100 * min, now)
        tivimate += other("00000000000000c3", "Another", 2012, 0, 50 * min, 100 * min, now)
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
        // Nothing taken out, marked or done, and no video TiviMate opened.
        for (t in listOf(pulp, heat, rotk, films[15])) assertFalse(id(t) in shown)
        assertTrue(VodContinue.rows(repo).all { LibraryContinue.isLibraryId(it.id) })
        // Every card of it answers Glass Home's asks: its play, and (a show's) its Episodes window's, as on Home.
        for (r in VodContinue.rows(repo)) {
            val (kind, titleId) = LibraryContinue.parse(r.id)!!
            assertTrue(r.id, repo.library.continueEntry(kind, titleId) != null)
            assertEquals(r.id, kind == Kind.SERIES, r.hasEpisodes)
        }
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
