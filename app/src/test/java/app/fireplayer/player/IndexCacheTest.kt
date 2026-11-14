package app.fireplayer.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class IndexCacheTest {

    private lateinit var dir: File
    private val size = 1_000L * 1024 * 1024
    private val link = "http://example.tv/movie/u/p/1.mkv"

    @Before fun setUp() {
        dir = Files.createTempDirectory("index").toFile()
        IndexCache.dir = dir
    }

    @After fun tearDown() {
        dir.deleteRecursively()
        IndexCache.dir = null
    }

    private fun keep(start: Long, bytes: ByteArray, fileSize: Long = size) {
        val w = IndexCache.Writer(link, fileSize, start)
        w.add(bytes, 0, bytes.size)
        w.finish()
    }

    @Test fun keptIndexIsReadBackFromWhereItWasAsked() {
        val start = size - 3_000_000
        val bytes = ByteArray(100_000) { (it % 251).toByte() }
        keep(start, bytes)
        val r = IndexCache.open(link, size, start + 10)
        assertNotNull(r)
        r!!
        val out = ByteArray(50)
        assertEquals(50, r.read(start + 10, out, 0, 50))
        assertEquals(bytes[10], out[0])
        assertEquals(bytes[59], out[49])
        // Past what was kept: nothing more from here.
        assertEquals(0, r.read(start + bytes.size, out, 0, 50))
        r.close()
    }

    @Test fun anotherSizeOrPlaceIsNotServed() {
        val start = size - 3_000_000
        keep(start, ByteArray(100_000))
        assertNull(IndexCache.open(link, size + 1, start))
        assertNull(IndexCache.open(link, size, start - 1))
        assertNull(IndexCache.open(link, size, start + 100_000))
        assertNull(IndexCache.open("http://example.tv/movie/u/p/2.mkv", size, start))
    }

    @Test fun onlyTheEndOfABigFileCounts() {
        assertEquals(false, IndexCache.wanted(size, 0))
        assertEquals(false, IndexCache.wanted(size, size / 2))
        assertEquals(true, IndexCache.wanted(size, size - 1_000_000))
        assertEquals(false, IndexCache.wanted(10L * 1024 * 1024, 9L * 1024 * 1024))
    }

    @Test fun tooMuchOrTooLittleIsNotKept() {
        val start = size - 10_000_000
        keep(start, ByteArray(1_000))
        assertNull(IndexCache.open(link, size, start))
        val w = IndexCache.Writer(link, size, start)
        val chunk = ByteArray(1024 * 1024)
        repeat(7) { w.add(chunk, 0, chunk.size) }
        w.finish()
        assertNull(IndexCache.open(link, size, start))
    }
}
