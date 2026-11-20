package app.fireplayer.vod.data

import app.fireplayer.vod.Kind
import app.fireplayer.vod.Title
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PagingAndArtTest {

    private fun page(from: Int, count: Int, hasMore: Boolean = true) =
        Cinemeta.Page((from until from + count).map { Title("tt$it", Kind.MOVIE, "T$it") }, hasMore, 1)

    @Test
    fun requestsMapOntoPagesOfFifty() {
        assertEquals(listOf(0), Paging.bases(0, 16))
        assertEquals(listOf(0), Paging.bases(0, 50))
        assertEquals(listOf(50), Paging.bases(50, 50))
        assertEquals(listOf(0, 50), Paging.bases(47, 50))
        assertEquals(listOf(100, 150), Paging.bases(149, 2))
        assertTrue(Paging.bases(0, 0).isEmpty())
    }

    @Test
    fun aSliceTakesItsPositionsEachIdOnce() {
        val p0 = page(0, 48)
        val p50 = page(50, 47)
        assertEquals((0 until 16).map { "tt$it" }, Paging.slice(listOf(0), listOf(p0), 0, 16).map { it.id })
        // Stepping by the limit asked: the next page starts with its first title.
        assertEquals("tt50", Paging.slice(listOf(50), listOf(p50), 50, 50).first().id)
        // A request across two pages; a repeat across them is dropped.
        val repeat = Cinemeta.Page(listOf(Title("tt47", Kind.MOVIE, "T47")) + p50.titles, true, 1)
        val across = Paging.slice(listOf(0, 50), listOf(p0, repeat), 45, 10).map { it.id }
        assertEquals(listOf("tt45", "tt46", "tt47", "tt50", "tt51", "tt52", "tt53"), across)
    }

    @Test
    fun nothingAfterTheLastPage() {
        val last = page(0, 4, hasMore = false)
        assertEquals(4, Paging.slice(listOf(0, 50), listOf(last, page(50, 10)), 0, 100).size)
        assertTrue(Paging.slice(listOf(50), listOf(Cinemeta.Page(emptyList(), false, 1)), 50, 50).isEmpty())
        // Fresh pages up to the last one answer for every base.
        assertTrue(Paging.allFresh(listOf(last, null), { true }, { !it.hasMore }))
        assertFalse(Paging.allFresh(listOf(page(0, 50), null), { true }, { !it.hasMore }))
        assertFalse(Paging.allFresh(listOf(page(0, 50)), { false }, { !it.hasMore }))
    }

    @Test
    fun backgroundsAtTheSizeAskedFor() {
        val medium = "https://images.metahub.space/background/medium/tt1/img"
        assertEquals("https://images.metahub.space/background/small/tt1/img", VodArt.backgroundAt(medium, VodArt.Size.SMALL))
        assertEquals(medium, VodArt.backgroundAt(medium, VodArt.Size.MEDIUM))
        assertEquals(
            "https://live.metahub.space/background/small/tt2/img",
            VodArt.backgroundAt("https://live.metahub.space/background/medium/tt2/img", VodArt.Size.SMALL),
        )
        assertEquals("https://image.tmdb.org/t/p/w780/b.jpg", VodArt.backgroundAt("https://image.tmdb.org/t/p/w1280/b.jpg", VodArt.Size.SMALL))
        assertEquals("https://example.com/x.jpg", VodArt.backgroundAt("https://example.com/x.jpg", VodArt.Size.SMALL))
        assertNull(VodArt.backgroundAt(null, VodArt.Size.SMALL))
    }

    @Test
    fun metahubOnlyGetsSizesItKnows() {
        // Any other size name gets the 1.25 MB original.
        assertEquals("https://images.metahub.space/poster/small/tt1/img", VodArt.normalized("https://images.metahub.space/poster/medium/tt1/img"))
        assertEquals("https://images.metahub.space/background/medium/tt1/img", VodArt.normalized("https://images.metahub.space/background/original/tt1/img"))
        assertEquals("https://live.metahub.space/logo/medium/tt1/img", VodArt.normalized("https://live.metahub.space/logo/medium/tt1/img"))
        assertEquals("https://example.com/p.jpg", VodArt.normalized("https://example.com/p.jpg"))
    }
}
