package app.fireplayer.vod.data

import app.fireplayer.vod.Kind
import app.fireplayer.vod.Title
import app.fireplayer.vod.standInId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleFactsTest {

    /** A show as a service's row makes it from TMDB's list (and My List stored it, added from there). */
    private val fromTmdb = Title(
        id = "tt14452776",
        kind = Kind.SERIES,
        name = "The Bear",
        year = "2022",
        poster = "poster",
        background = "backdrop",
        logo = "logo",
        description = "TMDB's story",
        tmdbId = 136315,
    )

    private val cinemeta = Title(
        id = "tt14452776",
        kind = Kind.SERIES,
        name = "The Bear (Cinemeta)",
        year = "2022–2026",
        poster = "cinemeta poster",
        background = "cinemeta background",
        logo = "cinemeta logo",
        description = "Cinemeta's story",
        imdbRating = "8.5",
        genres = listOf("Comedy", "Drama"),
        runtime = "35 min",
        tmdbId = 136315,
    )

    @Test
    fun aTitleMadeFromTmdbsListsLacksItsFactsOneFromCinemetaHasThem() {
        assertTrue(TitleFacts.lacks(fromTmdb))
        assertTrue(TitleFacts.lacks(fromTmdb.copy(kind = Kind.MOVIE)))
        assertFalse(TitleFacts.lacks(cinemeta))
        // Genres alone, or a rating alone, are facts enough.
        assertFalse(TitleFacts.lacks(fromTmdb.copy(genres = listOf("Drama"))))
        assertFalse(TitleFacts.lacks(fromTmdb.copy(imdbRating = "7.9")))
    }

    @Test
    fun aStandInLacksThemTooButNotAnEpisodeOrALogoCard() {
        assertTrue(TitleFacts.lacks(fromTmdb.copy(id = standInId(Kind.SERIES, 136315))))
        // An episode's id (a Continue card's still) and a service's card: Cinemeta has no meta to give.
        assertFalse(TitleFacts.lacks(fromTmdb.copy(id = "tt14452776:2:5")))
        assertFalse(TitleFacts.lacks(Title("service:hulu", Kind.MOVIE, "Hulu")))
    }

    @Test
    fun completedItKeepsWhatItShowsAndTakesCinemetasFacts() {
        val whole = TitleFacts.complete(fromTmdb, cinemeta)
        // What the banner shows already stays: its id, name, pictures, logo and TMDB's story.
        assertEquals(fromTmdb.id, whole.id)
        assertEquals(Kind.SERIES, whole.kind)
        assertEquals("The Bear", whole.name)
        assertEquals("poster", whole.poster)
        assertEquals("backdrop", whole.background)
        assertEquals("logo", whole.logo)
        assertEquals("TMDB's story", whole.description)
        // Cinemeta's facts: the show's years, genres, rating and length.
        assertEquals("2022–2026", whole.year)
        assertEquals(listOf("Comedy", "Drama"), whole.genres)
        assertEquals("8.5", whole.imdbRating)
        assertEquals("35 min", whole.runtime)
        assertFalse(TitleFacts.lacks(whole))
    }

    @Test
    fun aMissingStoryOrYearComesFromCinemetaAndAZeroRatingIsNone() {
        val whole = TitleFacts.complete(fromTmdb.copy(description = null, tmdbId = null), cinemeta.copy(imdbRating = "0", year = null))
        assertEquals("Cinemeta's story", whole.description)
        assertEquals("2022", whole.year)
        assertEquals(null, whole.imdbRating)
        assertEquals(136315, whole.tmdbId)
    }
}
