package app.fireplayer.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The list of downloaded subtitles ([SavedSubtitles]), on folders made for the test: kept per video, cleared out after
 * a month unused with the timing of what went, and never taking the player down or everything with it when the list
 * can't be written or read.
 */
class SavedSubtitlesTest {

    private val day = 24 * 3600_000L
    private val now = 400 * day

    // Made-up links: only ever compared.
    private val film = "http://provider.example/movie/1.mkv"
    private val other = "http://provider.example/movie/2.mkv"

    private fun folder(): File = Files.createTempDirectory("subs").toFile().apply { deleteOnExit() }

    private fun subtitle(dir: File, name: String) = File(dir, name).apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nHello\n") }

    private fun index(dir: File) = File(dir, SavedSubtitles.INDEX)

    /** What [SavedSubtitles.prune] passes on, or null when it didn't. */
    private class Swept {
        var files: Set<String>? = null
        var videos: Set<String>? = null
        var times = 0
        val keep: (Set<String>, Set<String>) -> Unit = { f, v -> files = f; videos = v; times++ }
    }

    @Test
    fun keptPerVideoLastUsedFirstAndTheOneOn() {
        val dir = folder()
        val a = subtitle(dir, "11-a.srt")
        val b = subtitle(dir, "12-b.srt")
        val c = subtitle(dir, "13-c.srt")
        SavedSubtitles.add(dir, film, a, "en", "Film.2019.1080p\tWEB", 11, now)
        SavedSubtitles.add(dir, film, b, null, "Film.2019.720p", 12, now + 1)
        SavedSubtitles.add(dir, other, c, "ar", "Other", 13, now + 2)
        var mine = SavedSubtitles.forVideo(dir, film)
        assertEquals(listOf("12-b.srt", "11-a.srt"), mine.map { it.file })
        // The newest is the one on; a tab in a release doesn't break the list.
        assertEquals(listOf(true, false), mine.map { it.on })
        assertEquals("Film.2019.1080p WEB", mine[1].release)
        assertEquals("en", mine[1].language)
        assertNull(mine[0].language)
        SavedSubtitles.use(dir, film, "11-a.srt", now + 10)
        mine = SavedSubtitles.forVideo(dir, film)
        assertEquals(listOf("11-a.srt", "12-b.srt"), mine.map { it.file })
        assertEquals(listOf(true, false), mine.map { it.on })
        // Other subtitles or none chosen: none on, nothing else changed; a video with none kept writes nothing.
        SavedSubtitles.use(dir, film, null, now + 20)
        assertEquals(listOf(false, false), SavedSubtitles.forVideo(dir, film).map { it.on })
        assertEquals(listOf("13-c.srt"), SavedSubtitles.forVideo(dir, other).map { it.file })
        // The same download again takes the old row's place.
        SavedSubtitles.add(dir, film, a, "en", "Film.2019.1080p.WEB", 11, now + 30)
        assertEquals(listOf("11-a.srt", "12-b.srt"), SavedSubtitles.forVideo(dir, film).map { it.file })
        // On disk as in memory: seven fields a row, three rows.
        val rows = index(dir).readLines()
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.split('\t').size == 7 })
        assertFalse(File(dir, SavedSubtitles.INDEX + ".tmp").exists())
        // A file gone from the folder isn't offered.
        b.delete()
        assertEquals(listOf("11-a.srt"), SavedSubtitles.forVideo(dir, film).map { it.file })
    }

    @Test
    fun aFileIsFoundByItsNameWhicheverVideoKeptItWhileItIsKept() {
        val dir = folder()
        val a = subtitle(dir, "11-a.srt")
        val c = subtitle(dir, "13-c.srt")
        SavedSubtitles.add(dir, film, a, "en", "Film.2019.1080p", 11, now)
        SavedSubtitles.add(dir, other, a, "en", "Film.2019.1080p", 11, now + 5)
        SavedSubtitles.add(dir, other, c, "ar", "Other", 13, now + 6)
        // Kept for two copies: the latest used, with what's known of it (for a resume on a third copy).
        val found = SavedSubtitles.find(dir, "11-a.srt")!!
        assertEquals(other, found.video)
        assertEquals("en", found.language)
        assertEquals("Film.2019.1080p", found.release)
        assertEquals(11L, found.fileId)
        assertEquals("ar", SavedSubtitles.find(dir, "13-c.srt")?.language)
        // Never downloaded, or its file gone: not found.
        assertNull(SavedSubtitles.find(dir, "99-x.srt"))
        c.delete()
        assertNull(SavedSubtitles.find(dir, "13-c.srt"))
    }

    @Test
    fun aFileKeptForOneCopyIsKeptForAnotherOnceAndOnlyWhileItIsKept() {
        val dir = folder()
        val a = subtitle(dir, "11-a.srt")
        val third = "http://provider.example/movie/3.mkv"
        SavedSubtitles.add(dir, film, a, "en", "Film.2019.1080p", 11, now)
        // A resume on another copy: kept for it too, with what's known of it, and the one on there.
        assertTrue(SavedSubtitles.alsoFor(dir, other, "11-a.srt", now + 5))
        val there = SavedSubtitles.forVideo(dir, other).single()
        assertEquals("11-a.srt", there.file)
        assertEquals("en", there.language)
        assertEquals("Film.2019.1080p", there.release)
        assertEquals(11L, there.fileId)
        assertTrue(there.on)
        // Already kept for it (its own copy, or added just before): nothing to do, so its timing isn't copied over again.
        assertFalse(SavedSubtitles.alsoFor(dir, other, "11-a.srt", now + 6))
        assertFalse(SavedSubtitles.alsoFor(dir, film, "11-a.srt", now + 6))
        // A copy switched to before the first showed it: in its list (carried over) but not kept for it, so kept now.
        assertTrue(SavedSubtitles.alsoFor(dir, third, "11-a.srt", now + 7))
        assertEquals(listOf("11-a.srt"), SavedSubtitles.forVideo(dir, third).map { it.file })
        // Gone (or never downloaded): nothing.
        assertFalse(SavedSubtitles.alsoFor(dir, "http://provider.example/movie/4.mkv", "99-x.srt", now + 8))
        a.delete()
        assertFalse(SavedSubtitles.alsoFor(dir, "http://provider.example/movie/5.mkv", "11-a.srt", now + 9))
    }

    @Test
    fun theListIsReadOnce() {
        val dir = folder()
        val a = subtitle(dir, "11-a.srt")
        SavedSubtitles.add(dir, film, a, "en", "Film", 11, now)
        assertEquals(1, SavedSubtitles.forVideo(dir, film).size)
        // Kept in memory after that: the file isn't gone back to (nothing else writes it).
        index(dir).writeText("")
        assertEquals(1, SavedSubtitles.forVideo(dir, film).size)
        SavedSubtitles.use(dir, film, "11-a.srt", now + 5)
        assertEquals(1, index(dir).readLines().size)
    }

    @Test
    fun unusedForAMonthGoesWithItsFileAndTheTimingIsSweptOnce() {
        val dir = folder()
        val old = subtitle(dir, "11-old.srt")
        val fresh = subtitle(dir, "12-fresh.srt")
        val mine = subtitle(dir, "13-other.srt")
        SavedSubtitles.add(dir, film, old, "en", "Old", 11, now - 31 * day)
        SavedSubtitles.add(dir, film, fresh, "en", "Fresh", 12, now - 29 * day)
        SavedSubtitles.add(dir, other, mine, "en", "Other", 13, now - 40 * day)
        // Never listed (its row never reached the list), and a list half written by a run that was cut short.
        val stray = subtitle(dir, "99-stray.srt")
        val half = File(dir, SavedSubtitles.INDEX + ".tmp").apply { writeText("half") }
        val swept = Swept()
        SavedSubtitles.prune(dir, now, swept.keep)
        assertFalse(old.exists())
        assertFalse(mine.exists())
        assertFalse(stray.exists())
        assertFalse(half.exists())
        assertTrue(fresh.exists())
        assertEquals(listOf("12-fresh.srt"), SavedSubtitles.forVideo(dir, film).map { it.file })
        assertEquals(emptyList<String>(), SavedSubtitles.forVideo(dir, other).map { it.file })
        assertEquals(1, index(dir).readLines().size)
        // One sweep, told what's still kept: the files, and the videos that have some.
        assertEquals(1, swept.times)
        assertEquals(setOf("12-fresh.srt"), swept.files)
        assertEquals(setOf(film), swept.videos)
        // Nothing to let go the next time: nothing swept (the settings aren't even read).
        val again = Swept()
        SavedSubtitles.prune(dir, now, again.keep)
        assertEquals(0, again.times)
        assertTrue(fresh.exists())
    }

    @Test
    fun aListThatCantBeReadLosesNothing() {
        val dir = folder()
        val a = subtitle(dir, "11-a.srt")
        val b = subtitle(dir, "12-b.srt")
        // Something there that isn't a list that can be read (here: a folder in its place).
        index(dir).mkdirs()
        val swept = Swept()
        SavedSubtitles.prune(dir, now, swept.keep)
        assertTrue(a.exists())
        assertTrue(b.exists())
        assertEquals(0, swept.times)
        // Nothing is offered, and nothing written over it; none of it throws.
        assertEquals(emptyList<String>(), SavedSubtitles.forVideo(dir, film).map { it.file })
        SavedSubtitles.add(dir, film, a, "en", "Film", 11, now)
        SavedSubtitles.use(dir, film, "11-a.srt", now)
        assertTrue(index(dir).isDirectory)
        assertFalse(File(dir, SavedSubtitles.INDEX + ".tmp").exists())
        assertTrue(a.exists())
    }

    @Test
    fun aListThatCantBeWrittenStillHoldsForTheSitting() {
        val dir = folder()
        val a = subtitle(dir, "11-a.srt")
        val b = subtitle(dir, "12-b.srt")
        SavedSubtitles.add(dir, film, a, "en", "Film", 11, now)
        // Writing fails from here on (as with the storage full; here the file written first can't be made).
        File(dir, SavedSubtitles.INDEX + ".tmp").mkdirs()
        File(dir, SavedSubtitles.INDEX + ".tmp/in-the-way").writeText("x")
        SavedSubtitles.add(dir, film, b, "en", "Film.b", 12, now + 1)
        SavedSubtitles.use(dir, film, "11-a.srt", now + 2)
        // No exception, the sitting goes by what was meant, and the list on disk is as it was.
        val mine = SavedSubtitles.forVideo(dir, film)
        assertEquals(listOf("11-a.srt", "12-b.srt"), mine.map { it.file })
        assertEquals(listOf(true, false), mine.map { it.on })
        assertEquals(1, index(dir).readLines().size)
    }

    @Test
    fun withNoListYetNothingIsLetGoButWhatNoVideoKeeps() {
        val dir = folder()
        // No folder at all: nothing happens.
        val none = File(dir, "missing")
        val swept = Swept()
        SavedSubtitles.prune(none, now, swept.keep)
        assertEquals(0, swept.times)
        assertFalse(none.exists())
        assertEquals(emptyList<String>(), SavedSubtitles.forVideo(none, film).map { it.file })
    }
}
