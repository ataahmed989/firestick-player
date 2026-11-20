package app.fireplayer.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The contract's pure rules. Links here are placeholders on the reserved ".invalid" domain, never real ones. */
class SourcesContractTest {
    private val film = Title("tt0133093", Kind.MOVIE, "Film")
    private val show = Title("tt1695360", Kind.SERIES, "Show")

    @Test
    fun secretUrlShowsOnlyItsHost() {
        val url = SecretUrl.of("https://addon.invalid/SECRETPART/stream/movie/tt0133093.json")!!
        assertEquals("addon.invalid", url.host)
        assertFalse(url.toString().contains("SECRETPART"))
        assertFalse(Offer(Addon.TORRENTIO, Readiness.UNKNOWN, "name", link = url).toString().contains("SECRETPART"))
        assertNull(SecretUrl.of("magnet:?xt=urn:btih:abc"))
        assertNull(SecretUrl.of("  "))
    }

    @Test
    fun redactLinksRemovesEveryLink() {
        val text = redactLinks("Failed https://addon.invalid/KEY/x.json and stremio://addon.invalid/KEY/manifest.json!")
        assertFalse(text.contains("KEY"))
        assertEquals("Failed <link> and <link>", text)
        assertEquals("", redactLinks(null))
    }

    @Test
    fun releaseKeyRoundTripsAndRejectsJunk() {
        val hash = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"
        val key = ReleaseKey.of(hash, 3)!!
        assertEquals(hash.lowercase(), key.infoHash)
        assertEquals("rd|${hash.lowercase()}|3", key.storeKey)
        assertEquals(key, ReleaseKey.parse(key.storeKey))
        assertNull(ReleaseKey.of(hash, -1)!!.fileIdx)
        assertEquals(ReleaseKey.of(hash, null), ReleaseKey.parse("rd|$hash|-1"))
        assertNull(ReleaseKey.of("xyz", 0))
        assertNull(ReleaseKey.parse("https://addon.invalid/x"))
    }

    @Test
    fun targetsUseStremioIds() {
        assertEquals("stream/movie/tt0133093.json", SourceTarget(film).key)
        val ep = SourceTarget(show, EpisodeRef(2, 5))
        assertEquals("series", ep.streamType)
        assertEquals("tt1695360:2:5", ep.streamId)
        assertEquals("stream/series/tt1695360:2:5.json", ep.key)
    }

    @Test
    fun firstLoadLimitFollowsDataRate() {
        assertEquals(30_000L, firstLoadLimitMs(12.0))
        assertEquals(45_000L, firstLoadLimitMs(20.0))
        assertEquals(45_000L, firstLoadLimitMs(50.0))
        assertEquals(60_000L, firstLoadLimitMs(79.0))
        assertEquals(45_000L, firstLoadLimitMs(null))
    }

    @Test
    fun watchedRule() {
        val hour = 3_600_000L
        assertFalse(countsAsWatched(hour, 2 * hour, null))
        assertTrue(countsAsWatched(108 * 60_000L, 2 * hour, null)) // 90 %
        assertTrue(countsAsWatched(100 * 60_000L, 2 * hour, 99 * 60_000L)) // into the credits
        assertTrue(countsAsWatched(1_326_000L, 1_500_000L, null)) // 88 %, but in the last 3 minutes
        assertFalse(countsAsWatched(10, 0, null))
    }

    @Test
    fun listIsCompleteWhenEveryAddonIsFinal() {
        val asking = SourceList(
            SourceTarget(film),
            mapOf(Addon.TORRENTIO to AddonState.Answered(3), Addon.MEDIAFUSION to AddonState.Asking(0, 25_000)),
            emptyList(),
            null,
        )
        assertFalse(asking.complete)
        assertEquals(listOf(Addon.MEDIAFUSION), asking.stillLooking)
        assertTrue(asking.copy(states = asking.states + (Addon.MEDIAFUSION to AddonState.Busy(0))).complete)
    }

    @Test
    fun handoffKeepsOnlyTheNewest() {
        val tokens = (1..PlayHandoff.KEEP + 1).map { PlayHandoff.put(PlayRequest(SourceTarget(film), StartAt.Beginning, PlayOrigin.TITLE_PAGE)) }
        assertNull(PlayHandoff.get(tokens.first()))
        val last = tokens.last()
        assertEquals(StartAt.Beginning, PlayHandoff.get(last)!!.start)
        PlayHandoff.done(last)
        assertNull(PlayHandoff.get(last))
    }
}
