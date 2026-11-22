package app.fireplayer.vod.ui

import app.fireplayer.vod.Card
import app.fireplayer.vod.FakeVodRepository
import app.fireplayer.vod.Kind
import app.fireplayer.vod.RowSpec
import app.fireplayer.vod.RowStyle
import app.fireplayer.vod.Source
import app.fireplayer.vod.Title
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GridFeedTest {

    private val source = Source.Catalog(Kind.MOVIE, "top")
    private val spec = RowSpec("movies.popular", "Popular", RowStyle.POSTER, source)

    @Test
    fun opensWithTheRowsCardsThenPagesByFifty() {
        val repo = FakeVodRepository().apply { catalogs[source] = FakeVodRepository.films(120) }
        val seen = ArrayList<Int>()
        val feed = GridFeed(repo, spec, FakeVodRepository.films(15)) { seen += it.size }
        assertEquals(15, feed.cards.size)
        feed.loadNext()
        // Page 0 holds the row's 15 and more: one list, no repeats.
        assertEquals(50, feed.cards.size)
        feed.loadNext()
        feed.loadNext()
        assertEquals(120, feed.cards.size)
        assertEquals(listOf(0, 50, 100), repo.loads.map { it.skip })
        assertTrue(repo.loads.all { it.limit == 50 })
        assertEquals(listOf(50, 100, 120), seen)
        // Page 3 is empty: the end; nothing more is asked.
        feed.loadNext()
        assertTrue(feed.ended)
        feed.loadNext()
        assertEquals(4, repo.loads.size)
    }

    @Test
    fun skipMovesOnByThePageAskedForEvenWhenAPageHeldFewer() {
        // Cinemeta's pages often hold 47-49: the next page still starts at 50.
        val all = FakeVodRepository.films(150)
        val repo = object : app.fireplayer.vod.VodRepository by FakeVodRepository() {
            val skips = ArrayList<Int>()
            override fun load(row: RowSpec, skip: Int, limit: Int, onCards: (List<Card>, Boolean) -> Unit): app.fireplayer.vod.Cancel {
                skips += skip
                onCards(all.drop(skip).take(limit - 2), true)
                return app.fireplayer.vod.Cancel { }
            }
        }
        val feed = GridFeed(repo, spec, emptyList()) { }
        feed.loadNext()
        feed.loadNext()
        assertEquals(listOf(0, 50), repo.skips)
        assertEquals(96, feed.cards.size)
    }

    @Test
    fun repeatsAcrossPagesAreDroppedKeepingTheFirst() {
        val films = FakeVodRepository.films(60)
        // Page 1 starts with two titles already on page 0 (a CDN copy a little behind).
        val list = films.take(50) + films.subList(48, 50) + films.drop(50)
        val repo = FakeVodRepository().apply { catalogs[source] = list }
        val feed = GridFeed(repo, spec, emptyList()) { }
        feed.loadNext()
        feed.loadNext()
        assertEquals(60, feed.cards.size)
        assertEquals(films.map { it.title.id }, feed.cards.map { it.title.id })
    }

    @Test
    fun theSameIdOfAnotherKindIsAnotherTitle() {
        val film = Card(Title("tt8014982", Kind.MOVIE, "The Prince of Arcadia"))
        val show = Card(Title("tt8014982", Kind.SERIES, "The Body"))
        val repo = FakeVodRepository().apply { catalogs[source] = listOf(film, show) }
        val feed = GridFeed(repo, spec, emptyList()) { }
        feed.loadNext()
        assertEquals(2, feed.cards.size)
    }

    @Test
    fun aFreshAnswerReplacesTheCachedOne() {
        val repo = FakeVodRepository().apply {
            cachedFirst = true
            cached[source] = FakeVodRepository.films(10, from = 5000)
            catalogs[source] = FakeVodRepository.films(30)
        }
        val versions = ArrayList<List<String>>()
        val feed = GridFeed(repo, spec, emptyList()) { cards -> versions += cards.map { it.title.id } }
        feed.loadNext()
        assertEquals(2, versions.size)
        assertEquals("tt5000", versions[0].first())
        assertEquals(30, feed.cards.size)
        assertEquals("tt1000", feed.cards.first().title.id)
        assertFalse(feed.loading)
    }

    @Test
    fun anEmptyCachedPageWaitsForTheFreshOne() {
        val repo = FakeVodRepository().apply {
            cachedFirst = true
            manual = true
            catalogs[source] = FakeVodRepository.films(20)
        }
        val feed = GridFeed(repo, spec, FakeVodRepository.films(15)) { }
        feed.loadNext()
        assertTrue(feed.loading)
        repo.answerAll()
        // The empty cached answer didn't end it; the fresh one filled it.
        assertFalse(feed.ended)
        assertEquals(20, feed.cards.size)
    }

    @Test
    fun aFailedFirstPageKeepsTheRowsCards() {
        val repo = FakeVodRepository().apply { offline = true }
        val feed = GridFeed(repo, spec, FakeVodRepository.films(15)) { }
        feed.loadNext()
        assertTrue(feed.ended)
        assertEquals(15, feed.cards.size)
    }

    @Test
    fun aShortLibraryPageIsTheLast() {
        val repo = FakeVodRepository()
        repeat(20) { i -> repo.setInMyList(Title("tt$i", Kind.MOVIE, "T$i"), true) }
        val mine = RowSpec("home.mylist", "My List", RowStyle.POSTER, Source.MyList)
        val feed = GridFeed(repo, mine, emptyList()) { }
        feed.loadNext()
        assertEquals(20, feed.cards.size)
        assertTrue(feed.ended)
        assertEquals(1, repo.loads.size)
    }

    @Test
    fun stopsAtTheCap() {
        val repo = FakeVodRepository().apply { catalogs[source] = FakeVodRepository.films(400) }
        val feed = GridFeed(repo, spec, emptyList(), cap = 120) { }
        repeat(5) { feed.loadNext() }
        assertEquals(120, feed.cards.size)
        assertTrue(feed.ended)
        assertEquals(3, repo.loads.size)
    }

    @Test
    fun removedTitlesLeaveAndClosedFeedsIgnoreLateAnswers() {
        val repo = FakeVodRepository().apply {
            manual = true
            catalogs[source] = FakeVodRepository.films(30)
        }
        var changes = 0
        val feed = GridFeed(repo, spec, FakeVodRepository.films(15)) { changes++ }
        feed.remove("tt1003")
        assertEquals(14, feed.cards.size)
        assertEquals(1, changes)
        feed.loadNext()
        feed.close()
        repo.answerAll()
        assertEquals(14, feed.cards.size)
        assertEquals(1, changes)
    }

    @Test
    fun aCardChangedInPlaceStaysWhereItIs() {
        val repo = FakeVodRepository()
        val first = FakeVodRepository.films(5)
        var changes = 0
        val feed = GridFeed(repo, spec, first) { changes++ }
        feed.replace(first[2].title.id) { it.copy(progress = 0f, caption = "2h left") }
        assertEquals(5, feed.cards.size)
        assertEquals(first[2].title, feed.cards[2].title)
        assertEquals("2h left", feed.cards[2].caption)
        assertEquals(1, changes)
        // Not in the grid: nothing changes.
        feed.replace("tt0") { it.copy(caption = "x") }
        assertEquals(1, changes)
    }
}
