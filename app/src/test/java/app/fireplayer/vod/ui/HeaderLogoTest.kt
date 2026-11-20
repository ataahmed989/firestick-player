package app.fireplayer.vod.ui

import app.fireplayer.vod.Kind
import app.fireplayer.vod.Title
import app.fireplayer.vod.data.VodArt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeaderLogoTest {
    private fun title(id: String, logo: String?) = Title(id = id, kind = Kind.MOVIE, name = "A film", logo = logo)

    @Test
    fun aTitlesOwnLogoIsUsed() {
        assertEquals("https://example.org/l.png", HeaderLogo.urlOf(title("tt0000001", "https://example.org/l.png")))
    }

    @Test
    fun aLibraryCardWithoutOneGetsItsImdbIdsLogo() {
        assertEquals(VodArt.logo("tt0000001"), HeaderLogo.urlOf(title("tt0000001", null)))
        assertEquals(VodArt.logo("tt0000001"), HeaderLogo.urlOf(title("tt0000001", "")))
    }

    @Test
    fun aTitleWithNoImdbIdHasNone() {
        assertNull(HeaderLogo.urlOf(title("tmdb:123", null)))
        assertNull(HeaderLogo.urlOf(null))
    }
}
