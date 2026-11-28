package app.fireplayer.vod.ui

import app.fireplayer.vod.From
import app.fireplayer.vod.Kind
import app.fireplayer.vod.Sourced
import app.fireplayer.vod.Title
import app.fireplayer.vod.TitleDetails
import app.fireplayer.vod.data.TitlePageFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the title page's Details window (⋯ › Details) shows, from the shared fixtures: the line under the title for a
 * film and a show, the rating's badge, the cast with their portraits, the facts and the production, each only when
 * known, so a section with nothing is left out.
 */
class DetailsWindowRulesTest {

    private val film = TitleFixtures.details("cinemeta_meta_film.json", "PG-13")
    private val show = TitleFixtures.details("cinemeta_meta_show_ended_specials.json", "TV-Y7")
    private val bare = TitleDetails(Title("tt1", Kind.MOVIE, "Bare"))

    // ---- The pinned lines ----

    @Test
    fun theLineUnderTheTitleIsYearAndLengthForAFilmAndSeasonsAndEpisodeLengthForAShow() {
        assertEquals("2024 • 2h 47m", TitleRules.detailsLine(film))
        assertEquals("2012–2014 • 4 seasons • 23 min per episode", TitleRules.detailsLine(show))
        // Only the parts that exist: no seasons known yet, one season, no length.
        assertEquals("2012–2014 • 23 min per episode", TitleRules.detailsLine(show.copy(seasons = emptyList())))
        assertEquals("2012–2014 • 1 season", TitleRules.detailsLine(show.copy(seasons = show.seasons.filter { it.number <= 1 }, runtimeMin = null)))
        assertEquals("2h 47m", TitleRules.detailsLine(film.copy(title = film.title.copy(year = " "))))
        assertEquals("", TitleRules.detailsLine(bare))
        // A long episode in hours and minutes; none for none.
        assertEquals("48 min per episode", TitleRules.perEpisode(48))
        assertEquals("1h 2m per episode", TitleRules.perEpisode(62))
        assertEquals("1h per episode", TitleRules.perEpisode(60))
        assertNull(TitleRules.perEpisode(0))
        assertNull(TitleRules.perEpisode(null))
    }

    @Test
    fun theBadgeIsTmdbsRatingAndNoneWithoutOne() {
        assertEquals("PG-13", TitleRules.ratingBadge(film))
        assertEquals("TV-Y7", TitleRules.ratingBadge(show))
        assertEquals("TV-14", TitleRules.ratingBadge(bare.copy(ageRating = " TV-14 ")))
        assertNull(TitleRules.ratingBadge(bare))
        assertNull(TitleRules.ratingBadge(bare.copy(ageRating = "  ")))
    }

    // ---- Starring ----

    @Test
    fun theCastKeepsItsPortraitsInOrderAndThreeRowsOfThreeAtMost() {
        val merged = TitlePageFixtures.details("cinemeta_meta_film.json", Kind.MOVIE, "tmdb_movie_details_appended.json")
        val cast = TitleRules.detailsCast(merged)
        assertEquals(listOf("Timothée Chalamet", "Zendaya", "Rebecca Ferguson", "Javier Bardem", "Josh Brolin", "Austin Butler"), cast.map { it.name })
        assertEquals("https://image.tmdb.org/t/p/w185/fixture_c1.jpg", cast[0].picture)
        // TMDB has no portrait of her: her circle shows her initials instead.
        assertNull(cast[2].picture)
        assertEquals("https://image.tmdb.org/t/p/w185/fixture_c6.jpg", cast[5].picture)
        // Cinemeta's cast: names only.
        assertTrue(TitleRules.detailsCast(film).all { it.picture == null })
        assertEquals(3, TitleRules.detailsCast(film).size)
        // At most nine; blank names left out; portraits that don't pair up with the names aren't used.
        val many = bare.copy(cast = Sourced((1..15).map { "Actor $it" } + " ", From.TMDB), castPictures = (1..17).map { "https://p/$it.jpg" })
        val nine = TitleRules.detailsCast(many)
        assertEquals(9, nine.size)
        assertEquals("Actor 9", nine.last().name)
        assertTrue(nine.all { it.picture == null })
        assertTrue(TitleRules.detailsCast(bare).isEmpty())
    }

    @Test
    fun aPortraitlessCircleShowsInitials() {
        assertEquals("RF", TitleRules.initials("Rhenzy Feliz"))
        assertEquals("Z", TitleRules.initials("Zendaya"))
        assertEquals("MD", TitleRules.initials("Michael Dante DiMartino"))
        assertEquals("PB", TitleRules.initials("P.J. Byrne"))
        assertEquals("", TitleRules.initials(" "))
    }

    // ---- Facts ----

    @Test
    fun aFilmsFactsAreOnlyWhatsKnownInOrder() {
        val facts = TitleRules.detailsFacts(film.copy(language = "English"))
        assertEquals(listOf("Released", "Genres", "IMDb", "Countries", "Language", "Awards"), facts.map { it.first })
        val m = facts.toMap()
        assertEquals("Mar 1, 2024", m["Released"])
        assertEquals("Action, Adventure, Drama", m["Genres"])
        assertEquals("★ 8.4", m["IMDb"])
        assertEquals("United States, Canada, United Arab Emirates, Hungary, Italy, New Zealand, Jordan", m["Countries"])
        assertEquals("English", m["Language"])
        assertEquals("Won 2 Oscars • 126 wins & 375 nominations", m["Awards"])
        // What the lines above say isn't said again: no year, length or rating here.
        assertFalse(m.containsKey("Year") || m.containsKey("Length") || m.containsKey("Rated") || m.containsKey("Status"))
        // A film missing most facts shows just the one it has, under the singular label.
        val sparse = TitleFixtures.details("cinemeta_meta_film_missing_fields.json")
        assertEquals(listOf("Country" to "France"), TitleRules.detailsFacts(sparse))
        assertTrue(TitleRules.detailsFacts(bare).isEmpty())
    }

    @Test
    fun aShowsFactsSayWhenItFirstAiredAndHowItStands() {
        val facts = TitleRules.detailsFacts(show)
        assertEquals(listOf("First aired", "Genres", "Status", "IMDb", "Countries", "Awards"), facts.map { it.first })
        val m = facts.toMap()
        assertEquals("Ended", m["Status"])
        assertEquals("27 wins & 42 nominations", m["Awards"])
        // No language known: no line for it.
        assertFalse(m.containsKey("Language"))
    }

    @Test
    fun awardsAreTidiedNeverMadeUp() {
        assertEquals("Won 2 Oscars • 126 wins & 375 nominations", TitleRules.awardsText("Won 2 Oscars. 126 wins & 375 nominations total"))
        assertEquals("4 wins & 5 nominations", TitleRules.awardsText("4 wins & 5 nominations"))
        assertEquals("Nominated for 1 Primetime Emmy • 4 wins & 12 nominations", TitleRules.awardsText("Nominated for 1 Primetime Emmy. 4 wins & 12 nominations total."))
        assertNull(TitleRules.awardsText(" "))
        assertNull(TitleRules.detailsFacts(bare.copy(awards = " . ")).toMap()["Awards"])
    }

    // ---- Production ----

    @Test
    fun aFilmsProductionIsItsDirectorsWritersAndStudios() {
        val merged = TitlePageFixtures.details("cinemeta_meta_film.json", Kind.MOVIE, "tmdb_movie_details_appended.json")
        assertEquals(
            listOf(
                "Director" to "Denis Villeneuve",
                "Writers" to "Denis Villeneuve, Jon Spaihts, Frank Herbert",
                "Studio" to "Legendary Pictures",
            ),
            TitleRules.detailsProduction(merged),
        )
        // Plurals by count, at most four names a line; nothing known, no section.
        val crew = bare.copy(
            directors = Sourced(listOf("A", "B"), From.TMDB),
            writers = Sourced(listOf("W1", "W2", "W3", "W4", "W5"), From.TMDB),
            studios = listOf("S1", "S2"),
        )
        assertEquals(
            listOf("Directors" to "A, B", "Writers" to "W1, W2, W3, W4", "Studios" to "S1, S2"),
            TitleRules.detailsProduction(crew),
        )
        assertTrue(TitleRules.detailsProduction(bare).isEmpty())
        // A film's line never says "Creator" or "network".
        assertTrue(TitleRules.detailsProduction(crew.copy(creators = Sourced(listOf("C"), From.TMDB), networks = listOf("N"))).none { it.first.startsWith("Creator") || it.first.contains("network") })
    }

    @Test
    fun aShowsProductionIsItsCreatorsAndNetwork() {
        val merged = TitlePageFixtures.details("cinemeta_meta_show_ended_specials.json", Kind.SERIES, "tmdb_tv_details_appended.json")
        assertEquals(
            listOf("Creators" to "Michael Dante DiMartino, Bryan Konietzko", "Original network" to "Nickelodeon"),
            TitleRules.detailsProduction(merged),
        )
        val two = merged.copy(networks = listOf("Hulu", "Disney+"), creators = Sourced(listOf("Josh Schwartz"), From.TMDB))
        assertEquals(listOf("Creator" to "Josh Schwartz", "Original networks" to "Hulu, Disney+"), TitleRules.detailsProduction(two))
        // Cinemeta's show alone: its writers as creators, no network.
        assertEquals(listOf("Creators" to "Michael Dante DiMartino, Bryan Konietzko"), TitleRules.detailsProduction(show))
    }
}
