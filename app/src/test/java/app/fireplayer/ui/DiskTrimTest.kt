package app.fireplayer.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DiskTrimTest {

    private fun e(name: String, size: Long, modified: Long) = DiskTrim.Entry(name, size, modified)

    @Test
    fun nothingGoesWithinTheLimit() {
        val files = listOf(e("a", 40, 1), e("b", 60, 2))
        assertEquals(emptyList<DiskTrim.Entry>(), DiskTrim.victims(files, 100, 100))
    }

    @Test
    fun oldestGoFirstDownToNinetyPercent() {
        val files = listOf(e("new", 30, 50), e("old", 30, 10), e("mid", 30, 20), e("newest", 30, 60))
        // 120 bytes over a 100 limit: down to 90 or less, the oldest first.
        val gone = DiskTrim.victims(files, 120, 100)
        assertEquals(listOf("old"), gone.map { it.name })
        val more = DiskTrim.victims(files, 120, 70)
        // Down to 63: "old" (90 left) then "mid" (60 left).
        assertEquals(listOf("old", "mid"), more.map { it.name })
    }

    @Test
    fun theFileInUseStays() {
        val files = listOf(e("justWritten", 80, 1), e("other", 40, 5))
        val gone = DiskTrim.victims(files, 120, 100, keep = setOf("justWritten"))
        assertEquals(listOf("other"), gone.map { it.name })
    }

    @Test
    fun cacheKeepsBytesUnderHashedNamesAndTrimsTheOldest() {
        val dir = Files.createTempDirectory("imgcache").toFile()
        try {
            val cache = ImageDiskCache(dir, maxBytes = 1000)
            val url = "https://image.tmdb.org/t/p/w342/abc.jpg"
            cache.write(url, ByteArray(300) { 1 })
            assertArrayEquals(ByteArray(300) { 1 }, cache.read(url))
            // Never the address on disk: a hash.
            val names = dir.listFiles()!!.map { it.name }
            assertEquals(listOf(ImageDiskCache.nameOf(url)), names)
            assertFalse(names.single().contains("tmdb"))
            assertEquals(40, names.single().length)
            // Older files go when the total passes the limit.
            File(dir, ImageDiskCache.nameOf(url)).setLastModified(1_000L)
            cache.write("https://b", ByteArray(400))
            File(dir, ImageDiskCache.nameOf("https://b")).setLastModified(2_000L)
            cache.write("https://c", ByteArray(400))
            assertTrue(cache.has("https://c"))
            assertNull(cache.read(url))
            assertTrue(cache.has("https://b"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aWriteCutShortIsClearedAtTheNextStart() {
        val dir = Files.createTempDirectory("imgcache").toFile()
        try {
            File(dir, "abc.tmp").writeBytes(ByteArray(10))
            ImageDiskCache(dir, maxBytes = 1000).write("https://x", ByteArray(5))
            assertFalse(File(dir, "abc.tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
