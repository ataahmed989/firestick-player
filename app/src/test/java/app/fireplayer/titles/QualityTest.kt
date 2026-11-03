package app.fireplayer.titles

import app.fireplayer.player.TitleInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class QualityTest {

    @Test
    fun tagsFromNamesThenCategories() {
        assertEquals("4K", Quality.of("|EN| Dune 2160p", null))
        assertEquals("4K HDR", Quality.of("Dune 4K HDR", null))
        assertEquals("1080p", Quality.of("Boxing 2025 Inoue vs Picasso 1080pEN60fps DAZN", null))
        assertEquals("720p", Quality.of("UFC Freedom 250 720p WEB", null))
        assertEquals("4K", Quality.of("Dune", "|EN| 4K MOVIES"))
        assertEquals("HD", Quality.of("Dune", "|EN| HD Movies"))
        assertEquals(null, Quality.of("Shadow of the Hd", "Action"))
        assertEquals(null, Quality.of("Def Comedy Jam 25 - 2017", "Comedy"))
    }

    @Test
    fun namesWithTagsAndDashYearsReadBack() {
        val renamed = TitleInfo.parse("The Matrix (1999) [4K HDR]", null)
        assertEquals("The Matrix (1999)", renamed.title)
        assertEquals("The Matrix", renamed.searchQuery)
        val dashed = TitleInfo.parse("Gangs of Wasseypur - Part 1 - 2012", null)
        assertEquals("Gangs of Wasseypur - Part 1 (2012)", dashed.title)
        assertEquals("K.O. (2025)", TitleInfo.parse("K.O. - 2025", null).title)
    }
}
