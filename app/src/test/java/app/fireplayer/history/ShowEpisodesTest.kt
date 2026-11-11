package app.fireplayer.history

import app.fireplayer.player.ShowGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShowEpisodesTest {

    private fun ep(id: String, season: Int, number: Int, name: String = "Episode $id", image: String? = "still-$id.jpg") =
        ShowGuide.Ep(
            id = id, extension = "mkv", providerSeason = season, providerNumber = number, providerTitle = name,
            season = season, number = number, name = name, overview = "About $id", image = image, runtimeMin = 45,
            airDate = "2024-01-0$number", match = ShowGuide.Match.SURE, why = null,
        )

    @Test
    fun bySeasonThenEpisodeWithSpecialsFirstAndBothCopiesInTheProvidersOrder() {
        val guide = listOf(ep("21", 2, 1), ep("12", 1, 2), ep("11", 1, 1), ep("90", 0, 1), ep("13", 1, 3), ep("31", 1, 3))
        val rows = ShowEpisodes.rows(guide, null, 0) { 0 }
        assertEquals(listOf("90", "11", "12", "13", "31", "21"), rows.map { it.episodeId })
        assertEquals(listOf(0, 1, 1, 1, 1, 2), rows.map { it.season })
        assertEquals(listOf(1, 1, 2, 3, 3, 1), rows.map { it.episode })
    }

    @Test
    fun theRowsOwnEpisodeIsAtTheRowsPlaceAndTheOthersWhereTheyWereLeft() {
        val guide = listOf(ep("11", 1, 1), ep("12", 1, 2), ep("13", 1, 3))
        val saved = mapOf("11" to 2_400_000L, "12" to 99L, "13" to -5L)
        val rows = ShowEpisodes.rows(guide, "12", 600_000) { saved.getValue(it.id) }
        assertEquals(listOf(false, true, false), rows.map { it.current })
        assertEquals(listOf(2_400_000L, 600_000L, 0L), rows.map { it.positionMs })
        assertEquals(listOf(0, 1, 0), rows.map { it.values()["current"] })
        // None is the row's own when it isn't listed.
        assertTrue(ShowEpisodes.rows(guide, "99", 600_000) { 0 }.none { it.current })
    }

    @Test
    fun onlyOneCopyIsTheRowsOwn() {
        val rows = ShowEpisodes.rows(listOf(ep("11", 1, 1), ep("11", 1, 1)), "11", 1_000) { 0 }
        assertEquals(1, rows.count { it.current })
    }

    @Test
    fun whatIsntKnownStaysEmpty() {
        val row = ShowEpisodes.rows(listOf(ep("11", 1, 1, name = "", image = null)), null, 0) { 0 }.single()
        assertNull(row.image)
        assertEquals("", row.name)
        val values = row.values()
        assertNull(values["image"])
        assertEquals("", values["name"])
        assertEquals("About 11", values["overview"])
        assertEquals(45, values["runtime_min"])
        assertEquals("2024-01-01", values["air_date"])
        assertEquals(0L, values["position_ms"])
        assertEquals("11", values["episode_id"])
    }

    @Test
    fun theColumnsAreGlassHomes() {
        val row = ShowEpisodes.rows(listOf(ep("11", 1, 1)), "11", 0) { 0 }.single()
        assertEquals(ShowEpisodes.COLUMNS.toList(), row.values().keys.toList())
        assertEquals(
            listOf("episode_id", "season", "episode", "name", "overview", "image", "runtime_min", "air_date", "position_ms", "current"),
            ShowEpisodes.COLUMNS.toList(),
        )
    }

    @Test
    fun anEpisodePlaysUnderItsShowAndNumbers() {
        assertEquals("Show S01E02 Pilot", ShowEpisodes.title("Show", ep("12", 1, 2, name = "Pilot")))
        assertEquals("Show S00E01", ShowEpisodes.title("Show", ep("90", 0, 1, name = "")))
    }

    @Test
    fun theShowsIdIsTheOneForTheLinksServer() {
        val keys = setOf("n|show", "s|other.example.com:8080|77", "s|tv.example.com:8080|123")
        assertEquals("123", ShowEpisodes.showIdIn(keys, "tv.example.com:8080"))
        assertEquals("77", ShowEpisodes.showIdIn(keys, "other.example.com:8080"))
        assertNull(ShowEpisodes.showIdIn(keys, "tv.example.com:80"))
        assertNull(ShowEpisodes.showIdIn(setOf("n|show"), "tv.example.com:8080"))
        assertFalse(ShowEpisodes.showIdIn(keys, "tv.example.com:8080").orEmpty().contains("|"))
    }
}
