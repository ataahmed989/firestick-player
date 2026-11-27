package app.fireplayer.vod.ui

import app.fireplayer.vod.Card
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Kind
import app.fireplayer.vod.RowSpec
import app.fireplayer.vod.RowStyle
import app.fireplayer.vod.Source
import app.fireplayer.vod.Title
import app.fireplayer.vod.data.Cinemeta
import app.fireplayer.vod.data.DefaultVodRepository
import app.fireplayer.vod.data.DefaultVodRepositoryTest.FakeHttp
import app.fireplayer.vod.data.DefaultVodRepositoryTest.FakeTmdb
import app.fireplayer.vod.data.Fixtures
import app.fireplayer.vod.data.JsonCache
import app.fireplayer.vod.data.MemoryIdStore
import app.fireplayer.vod.data.MemoryLibraryStore
import app.fireplayer.vod.sources.RecordedLibrary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor

/**
 * Home's library rows back from a film (seen on the TV): The Godfather resumed from its Continue card, 7th in the row
 * ("25m left"), played on and left with Back. The row's answer (on Home's return, and again when the player's last save
 * landed) moved the card focus was still on, so it was held until the viewer left the row: Home kept the row as it was
 * at the film's start until FirePlayer was restarted. A library row's answer now always shows, focus going with its title.
 */
class LibraryRowReturnTest {
    private var now = 1_760_000_000_000L
    private val direct = Executor { it.run() }
    private val dir: File = Files.createTempDirectory("vodreturn").toFile()
    private val http = FakeHttp()
    private val repo = DefaultVodRepository(
        http = http,
        tmdb = FakeTmdb(),
        tmdbKey = { "" },
        cache = JsonCache(File(dir, "vod")) { now },
        idStore = MemoryIdStore(),
        libraryStore = MemoryLibraryStore(),
        main = direct,
        io = direct,
        net = direct,
        lookups = direct,
        clock = { now },
    )
    private val recorded = RecordedLibrary(repo.library)
    private val godfather = Title("tt0068646", Kind.MOVIE, "The Godfather")
    private val bb = Title("tt0903747", Kind.SERIES, "Breaking Bad")
    private val continueSpec = RowSpec("continue", "Continue Watching", RowStyle.WIDE, Source.ContinueWatching(null), continueRow = true)

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** The row as Home's page loads it: one more than shown asked for, [RowRules.ROW_CARDS] shown. */
    private fun row(spec: RowSpec = continueSpec): List<Card> {
        var got: List<Card> = emptyList()
        repo.load(spec, 0, RowRules.ROW_CARDS + 1) { cards, _ -> got = cards }
        return got.take(RowRules.ROW_CARDS)
    }

    private fun play(title: Title, positionMs: Long, durationMs: Long) {
        now += 60_000L
        recorded.recordPlayback(title, null, positionMs, durationMs, creditsAtMs = null)
    }

    @Test
    fun backFromAFilmStartedFromItsContinueCardTheRowShowsWhatIsStored() {
        // The Godfather left at 9005 s of 10528 s, six films watched since: 7th in the row, "25m left".
        play(godfather, 9_005_000L, 10_528_000L)
        for (i in 1..6) play(Title("tt000000$i", Kind.MOVIE, "Film $i"), 600_000L, 6_000_000L)
        val atStart = row()
        val at = atStart.indexOfFirst { it.title.id == godfather.id }
        assertEquals(6, at)
        assertEquals("25m left", atStart[at].caption)

        // Glass Home's notice listens all along (VodRepositories); Home's page while it shows, not while the film plays.
        var glassTold = 0
        repo.addLibraryListener { glassTold++ }
        var pageTold = 0
        val page: () -> Unit = { pageTold++ }
        repo.addLibraryListener(page)
        repo.removeLibraryListener(page)
        // Played on, saved now and then, paused at 9429 s: Glass Home told of each save, Home's page of none.
        play(godfather, 9_100_000L, 10_528_000L)
        play(godfather, 9_429_000L, 10_528_000L)
        assertEquals(2, glassTold)
        assertEquals(0, pageTold)
        // Back: Home's page reloads as it starts again, focus still on the card OK was pressed on (the 7th).
        val back = row()
        assertEquals(godfather.id, back.first().title.id)
        assertEquals("18m left", back.first().caption)
        // That answer moves what the viewer is on: before, it was held until they left the row.
        assertFalse(RowRules.keepsFocus(at, atStart, false, back, false))
        assertFalse(RowRules.holdsAnswer(continueSpec.source, at, atStart, false, back, false))
        // Shown at once, focus going with The Godfather, first now.
        assertEquals(0, RowRules.followTitle(at, atStart, false, back, false))

        // The player's last save lands after Home is back (the player's onStop): Home's page and Glass Home are both
        // told, and the row's answer shows too.
        repo.addLibraryListener(page)
        play(godfather, 9_429_000L, 10_528_000L)
        assertEquals(3, glassTold)
        assertEquals(1, pageTold)
        val last = row()
        assertEquals(godfather.id, last.first().title.id)
        assertFalse(RowRules.holdsAnswer(continueSpec.source, 0, back, false, last, false))
    }

    /**
     * Seen in review: Up from the Continue card "Breaking Bad · S1 E1 · 10m left" to the banner's Watch now, which plays
     * S1 E1 to its end; back on Watch now, the row reads S1 E2 at once, but the banner kept the old card (its line
     * "S1 E1 · 10m left", and Watch now played S1 E1 again from its start) until it turned. The banner following a title
     * of the row now takes the row's card for it; with the title gone from the row (removed in Glass Home meanwhile), it
     * goes back to its feed.
     */
    @Test
    fun theBannerFollowingAContinueTitleTakesItsNewCard() {
        http.answers[Cinemeta.metaUrl(Kind.SERIES, bb.id)] = Fixtures.text("cinemeta_meta_series.json")
        repo.details(bb) { _, _ -> }
        play(godfather, 9_005_000L, 10_528_000L)
        now += 60_000L
        recorded.recordPlayback(bb, EpisodeRef(1, 1), 37 * 60_000L, 47 * 60_000L, creditsAtMs = null)
        val banner = row().first()
        assertEquals(bb.id, banner.title.id)
        assertEquals(1, banner.episode?.episode)
        assertTrue(banner.caption.orEmpty().contains("10m left"))

        // Watch now: S1 E1 played to its end. The row goes on with S1 E2; the banner's card is the row's now.
        now += 60_000L
        recorded.recordPlayback(bb, EpisodeRef(1, 1), 46 * 60_000L, 47 * 60_000L, creditsAtMs = null)
        val back = row()
        val card = RowRules.refollow(banner, back)
        assertEquals(back.first(), card)
        assertEquals(1, card?.episode?.season)
        assertEquals(2, card?.episode?.episode)
        assertFalse(card?.caption.orEmpty().contains("10m left"))

        // A film the banner follows: its new time left.
        val film = back.first { it.title.id == godfather.id }
        play(godfather, 9_429_000L, 10_528_000L)
        assertEquals("18m left", RowRules.refollow(film, row())?.caption)

        // Taken out in Glass Home while FirePlayer was away: no card for it, the banner goes back to its feed.
        repo.removeFromContinueWatching(bb.id)
        assertNull(RowRules.refollow(banner, row()))
        assertNull(RowRules.refollow(banner, emptyList()))
    }

    @Test
    fun noLibraryRowHoldsItsAnswerButOtherRowsStillDo() {
        val old = cards("a", "b", "c")
        val moved = cards("c", "a", "b")
        // Focus on "c", now first.
        assertFalse(RowRules.holdsAnswer(Source.ContinueWatching(null), 2, old, false, moved, false))
        assertFalse(RowRules.holdsAnswer(Source.ContinueWatching(Kind.SERIES), 2, old, false, moved, false))
        assertFalse(RowRules.holdsAnswer(Source.NextEpisodes(), 2, old, false, moved, false))
        assertFalse(RowRules.holdsAnswer(Source.MyList, 2, old, false, moved, false))
        assertFalse(RowRules.holdsAnswer(Source.MyListOf(Kind.MOVIE), 2, old, false, moved, false))
        // A catalog's fresh answer still waits for the viewer to leave the row (what they're on would move).
        val popular = Source.Catalog(Kind.MOVIE, "top")
        assertTrue(RowRules.holdsAnswer(popular, 2, old, false, moved, false))
        assertFalse(RowRules.holdsAnswer(popular, 0, old, false, cards("a", "x"), false))
        assertFalse(RowRules.holdsAnswer(popular, -1, old, false, moved, false))
    }

    @Test
    fun focusFollowsItsTitle() {
        val old = cards("a", "b", "c", "d")
        // Moved: to its new place.
        assertEquals(0, RowRules.followTitle(2, old, false, cards("c", "a", "b", "d"), false))
        assertEquals(3, RowRules.followTitle(0, old, false, cards("x", "b", "c", "a"), false))
        // Where it was: stays.
        assertEquals(1, RowRules.followTitle(1, old, false, cards("x", "b"), false))
        // Gone: the same place, the last card at most.
        assertEquals(1, RowRules.followTitle(1, old, false, cards("a", "c", "d"), false))
        assertEquals(1, RowRules.followTitle(3, old, false, cards("a", "b"), false))
        // A film and a show of the same id are two titles.
        val show = listOf(Card(Title("a", Kind.SERIES, "a"))) + cards("a")
        assertEquals(1, RowRules.followTitle(0, old, false, show, false))
        // On See all (after the 4 cards): See all while there is one, else the last card.
        assertEquals(2, RowRules.followTitle(4, old, true, cards("x", "y"), true))
        assertEquals(1, RowRules.followTitle(4, old, true, cards("x", "y"), false))
        // Nothing chosen, or nothing left.
        assertEquals(-1, RowRules.followTitle(-1, old, false, cards("a"), false))
        assertEquals(-1, RowRules.followTitle(0, old, false, emptyList(), false))
    }

    /**
     * Out of the row (on the banner's Watch now, which follows the Continue card it came up from): the card the row
     * stands at goes with its title too, so Down lands on The Godfather, not on whatever took its place.
     */
    @Test
    fun outOfTheRowTheCardItStandsAtGoesWithItsTitle() {
        play(godfather, 9_005_000L, 10_528_000L)
        for (i in 1..6) play(Title("tt000000$i", Kind.MOVIE, "Film $i"), 600_000L, 6_000_000L)
        val atStart = row()
        val chosen = atStart.indexOfFirst { it.title.id == godfather.id }
        // Watch now from the banner: played on. Focus is on the banner; the row was moved along to The Godfather.
        play(godfather, 9_429_000L, 10_528_000L)
        val back = row()
        val from = RowRules.followFrom(focused = -1, chosen = chosen)
        assertEquals(chosen, from)
        assertEquals(0, RowRules.followTitle(from, atStart, false, back, false))

        // A row at its start, the viewer elsewhere, and another film played (from another row): it stays at its start,
        // that film first, not moved along to keep the card that was first.
        val other = Title("tt0000099", Kind.MOVIE, "Film 99")
        play(other, 600_000L, 6_000_000L)
        val after = row()
        assertEquals(other.id, after.first().title.id)
        assertEquals(-1, RowRules.followFrom(focused = -1, chosen = 0))
        assertEquals(-1, RowRules.followTitle(RowRules.followFrom(-1, 0), back, false, after, false))
        // The viewer on its first card: focus stays on that title, now second.
        assertEquals(1, RowRules.followTitle(RowRules.followFrom(focused = 0, chosen = 0), back, false, after, false))
    }

    /**
     * See all on Continue Watching (a row past 15): a film played from it, or a title taken off in Glass Home, shows in
     * the grid as stored when the page comes back, focus going with its title ([GridFeed.reload], [SeeAllPage]).
     */
    @Test
    fun seeAllOfALibraryRowShowsTheLibraryAsItChanges() {
        for (i in 10..29) play(Title("tt00000$i", Kind.MOVIE, "Film $i"), 600_000L, 6_000_000L)
        val seen = ArrayList<List<Card>>()
        val feed = GridFeed(repo, continueSpec, row()) { seen += it }
        feed.loadNext()
        assertEquals(20, feed.cards.size)
        val tenth = feed.cards[9].title
        // Played from the grid, back: first, with its new time left.
        play(tenth, 1_200_000L, 6_000_000L)
        feed.reload()
        assertEquals(tenth.id, feed.cards.first().title.id)
        assertEquals("1h 20m left", feed.cards.first().caption)
        assertEquals(0, RowRules.followTitle(9, seen[seen.size - 2], false, feed.cards, false))
        assertEquals(20, feed.cards.size)
        // Taken off in Glass Home meanwhile: gone from the grid.
        val gone = feed.cards[3].title
        repo.removeFromContinueWatching(gone.id)
        feed.reload()
        assertEquals(19, feed.cards.size)
        assertTrue(feed.cards.none { it.title.id == gone.id })
        // Nothing changed: nothing redrawn.
        val shown = seen.size
        feed.reload()
        assertEquals(shown, seen.size)
        // Closed: not asked again.
        feed.close()
        repo.removeFromContinueWatching(feed.cards[0].title.id)
        feed.reload()
        assertEquals(19, feed.cards.size)
    }

    @Test
    fun aCatalogsSeeAllIsNotAskedAgain() {
        val source = Source.Catalog(Kind.MOVIE, "top")
        val fake = app.fireplayer.vod.FakeVodRepository().apply { catalogs[source] = app.fireplayer.vod.FakeVodRepository.films(60) }
        val feed = GridFeed(fake, RowSpec("movies.popular", "Popular", RowStyle.POSTER, source), emptyList()) { }
        feed.loadNext()
        val asked = fake.loads.size
        feed.reload()
        assertEquals(asked, fake.loads.size)
    }

    private fun cards(vararg ids: String) = ids.map { Card(Title(it, Kind.MOVIE, it)) }
}
