package app.fireplayer.titles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleBookNamesTest {

    @Test
    fun copiesAreFoundByNameAndYearWhateverTheQualityTag() {
        assertEquals("The Matrix (1999)", TitleBook.nameAndYear("The Matrix (1999) [4K HDR]"))
        assertEquals("The Matrix (1999)", TitleBook.nameAndYear("The Matrix (1999) [1080p]"))
        assertEquals("The Matrix (1999)", TitleBook.nameAndYear("The Matrix (1999)"))
    }

    @Test
    fun aNameWithoutItsYearCouldBeAnotherFilm() {
        assertNull(TitleBook.nameAndYear("Home [4K]"))
        assertNull(TitleBook.nameAndYear("Home"))
    }
}
