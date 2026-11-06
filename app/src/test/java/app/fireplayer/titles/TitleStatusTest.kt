package app.fireplayer.titles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleStatusTest {

    private fun progress(server: String, renamed: Int, kept: Int, waiting: Int) = TitleBook.Progress(server, renamed, kept, waiting)

    @Test
    fun oneProviderReadsAsOneShortLine() {
        val lines = TitleStatus.lines(listOf(progress("example.tv:80", 32643, 1200, 2879)))
        assertEquals(1, lines.size)
        assertNull(lines[0].provider)
        assertEquals("32,643 renamed · 2,879 to check", lines[0].counts)
    }

    @Test
    fun nothingWaitingIsAllChecked() {
        assertEquals("5 renamed · all checked", TitleStatus.counts(progress("example.tv:80", 5, 3, 0)))
    }

    @Test
    fun severalProvidersEachHaveTheirName() {
        val lines = TitleStatus.lines(listOf(progress("line.example.tv:8080", 10, 0, 1), progress("10.0.0.2:80", 0, 0, 7)))
        assertEquals("example", lines[0].provider)
        assertEquals("10.0.0.2", lines[1].provider)
        assertEquals("0 renamed · 7 to check", lines[1].counts)
    }

    @Test
    fun providersThatWouldReadAlikeKeepTheirWholeAddress() {
        assertEquals(
            listOf("example.tv:80", "example.tv:8080", "other"),
            TitleStatus.shortNames(listOf("example.tv:80", "example.tv:8080", "other.net:80")),
        )
        assertEquals(listOf("localhost"), TitleStatus.shortNames(listOf("localhost:8000")))
    }

    @Test
    fun theBarCountsRenamedAndKeptOfEverything() {
        assertEquals(0f, TitleStatus.fraction(emptyList()), 0f)
        assertEquals(0f, TitleStatus.fraction(listOf(progress("a.tv", 0, 0, 0))), 0f)
        assertEquals(0.75f, TitleStatus.fraction(listOf(progress("a.tv", 2, 1, 1))), 1e-6f)
        // Across providers, by titles, not by provider.
        assertEquals(0.5f, TitleStatus.fraction(listOf(progress("a.tv", 9, 0, 1), progress("b.tv", 0, 1, 9))), 1e-6f)
        assertEquals(1f, TitleStatus.fraction(listOf(progress("a.tv", 30000, 5000, 0))), 0f)
    }
}
