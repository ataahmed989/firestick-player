package app.fireplayer.vod.data

import app.fireplayer.vod.Kind
import app.fireplayer.vod.Title
import app.fireplayer.vod.data.TmdbTrending.Known
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executor

/** The TMDB → IMDb id map: ids never asked about are read from the store once; learnt ids are used 6 months at most. */
class IdMapTest {

    private var now = 1_000L * 24 * 3600_000L
    private val direct = Executor { it.run() }

    private class CountingStore : IdStore {
        val inner = MemoryIdStore()
        var reads = 0
        override fun idFor(kind: Kind, tmdbId: Int): Pair<String, Long>? {
            reads++
            return inner.idFor(kind, tmdbId)
        }
        override fun putIds(kind: Kind, entries: List<Triple<Int, String, Long>>) = inner.putIds(kind, entries)
    }

    @Test
    fun anIdNeverAskedAboutIsReadFromTheStoreOnce() {
        val store = CountingStore()
        val map = IdMap(store, { now }, direct)
        assertEquals(Known.Unknown, map.known(Kind.MOVIE, 7))
        assertEquals(Known.Unknown, map.known(Kind.MOVIE, 7))
        assertEquals(1, store.reads)
        // Learnt later (a TMDB answer, a Cinemeta page): known at once.
        map.found(Kind.MOVIE, 7, "tt0000007")
        assertEquals(Known.Hit("tt0000007"), map.known(Kind.MOVIE, 7))
        assertEquals(Known.Unknown, map.known(Kind.SERIES, 8))
        map.learn(listOf(Title("tt0000008", Kind.SERIES, "Eight", tmdbId = 8)))
        assertEquals(Known.Hit("tt0000008"), map.known(Kind.SERIES, 8))
        assertEquals(2, store.reads)
    }

    @Test
    fun learntIdsAreUsedSixMonthsAtMost() {
        val store = MemoryIdStore()
        val map = IdMap(store, { now }, direct)
        map.found(Kind.MOVIE, 9, "tt0000009")
        now += IdMap.HIT_KEPT_MS - 1
        assertEquals(Known.Hit("tt0000009"), map.known(Kind.MOVIE, 9))
        now += 1
        assertEquals(Known.Unknown, map.known(Kind.MOVIE, 9))
        assertEquals(Known.Unknown, IdMap(store, { now }, direct).known(Kind.MOVIE, 9))
        // Cinemeta still teaching it: learnt again, current.
        map.learn(listOf(Title("tt0000009", Kind.MOVIE, "Nine", tmdbId = 9)))
        assertEquals(Known.Hit("tt0000009"), map.known(Kind.MOVIE, 9))
        assertEquals(Known.Hit("tt0000009"), IdMap(store, { now }, direct).known(Kind.MOVIE, 9))
    }
}
