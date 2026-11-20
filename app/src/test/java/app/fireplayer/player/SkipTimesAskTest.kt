package app.fireplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** What Skip intro's database is asked: by IMDb id, season and episode numbers and the file's length, never a name. */
class SkipTimesAskTest {

    @Test fun anEpisodeIsAskedByItsShowsIdAndItsOwnNumbers() {
        val url = SkipTimes.skipDbUrl("tt1695360", "&season=2&episode=5", 1_385_400L)
        assertEquals("https://api.skipdb.tv/api/segments?imdb_id=tt1695360&season=2&episode=5&duration=1385&adjust=conservative", url)
    }

    @Test fun aFilmIsAskedByItsIdAlone() {
        assertEquals(
            "https://api.skipdb.tv/api/segments?imdb_id=tt0133093&duration=8160&adjust=conservative",
            SkipTimes.skipDbUrl("tt0133093", "", 8_160_000L),
        )
    }

    @Test fun theSameFileAsksTheSameAddressSoAnAnswerFetchedAheadIsFound() {
        // warm() and find() go through this one address: the answer fetched while the sources were found is the one read.
        val ahead = SkipTimes.skipDbUrl("tt1695360", "&season=2&episode=5", 1_385_400L)
        val atStart = SkipTimes.skipDbUrl("tt1695360", "&season=2&episode=5", 1_385_449L)
        assertEquals(ahead, atStart)
        // Another episode, or another cut of this one, is another question: no wrong-episode answer.
        assertNotEquals(ahead, SkipTimes.skipDbUrl("tt1695360", "&season=2&episode=6", 1_385_400L))
        assertNotEquals(ahead, SkipTimes.skipDbUrl("tt1695360", "&season=2&episode=5", 1_500_000L))
    }
}
