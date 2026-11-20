package app.fireplayer.vod.data

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JsonCacheTest {

    private val dir: File = Files.createTempDirectory("vodcache").toFile()
    private var now = 1_000_000_000L
    private val cache = JsonCache(File(dir, "vod"), { now })

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun anEntryIsFreshForItsTimeThenOldButStillRead() {
        cache.write("https://v3-cinemeta.strem.io/catalog/movie/top.json", JSONObject().put("n", 1), 4 * 3600_000L)
        val fresh = cache.read("https://v3-cinemeta.strem.io/catalog/movie/top.json")!!
        assertEquals(1, fresh.data.getInt("n"))
        assertTrue(fresh.isFresh(now))
        now += 4 * 3600_000L - 1
        assertTrue(cache.read("https://v3-cinemeta.strem.io/catalog/movie/top.json")!!.isFresh(now))
        now += 1
        val old = cache.read("https://v3-cinemeta.strem.io/catalog/movie/top.json")!!
        assertFalse(old.isFresh(now))
        assertEquals(1, old.data.getInt("n"))
        // A clock set back doesn't make it fresh for ever.
        assertFalse(old.isFresh(old.at - 1))
    }

    @Test
    fun keysAreKeptApartAndReplaced() {
        cache.write("a", JSONObject().put("v", "a1"), 1000)
        cache.write("b", JSONObject().put("v", "b1"), 1000)
        cache.write("a", JSONObject().put("v", "a2"), 1000)
        assertEquals("a2", cache.read("a")!!.data.getString("v"))
        assertEquals("b1", cache.read("b")!!.data.getString("v"))
        assertNull(cache.read("c"))
        cache.remove("a")
        assertNull(cache.read("a"))
        // No temporary files left behind; names don't show the key.
        val names = File(dir, "vod").list()!!.toList()
        assertEquals(listOf(JsonCache.fileName("b")), names)
        assertTrue(names.single().matches(Regex("[0-9a-f]{40}\\.json")))
    }

    @Test
    fun aDamagedFileIsAsIfNeverStored() {
        cache.write("a", JSONObject().put("v", 1), 1000)
        File(File(dir, "vod"), JsonCache.fileName("a")).writeText("{not json")
        assertNull(cache.read("a"))
        assertFalse(File(File(dir, "vod"), JsonCache.fileName("a")).exists())
    }

    @Test
    fun theFolderIsTrimmedEverySoManyWrites() {
        now = System.currentTimeMillis()
        cache.write("old", JSONObject(), 1000)
        File(File(dir, "vod"), JsonCache.fileName("old")).setLastModified(now - 20 * 24 * 3600_000L)
        repeat(JsonCache.TRIM_EVERY - 2) { cache.write("k$it", JSONObject(), 1000) }
        assertTrue(File(File(dir, "vod"), JsonCache.fileName("old")).exists())
        // The next write trims: the entry too old goes, the others stay.
        cache.write("last", JSONObject(), 1000)
        assertFalse(File(File(dir, "vod"), JsonCache.fileName("old")).exists())
        assertEquals(JsonCache.TRIM_EVERY - 1, File(dir, "vod").list()!!.size)
    }

    @Test
    fun trimmingDropsOldThenOldestOverBudget() {
        val day = 24 * 3600_000L
        val files = listOf(
            JsonCache.Facts("old", 100, now - 20 * day),
            JsonCache.Facts("a", 400, now - 3 * day),
            JsonCache.Facts("b", 400, now - 2 * day),
            JsonCache.Facts("c", 400, now - 1 * day),
        )
        // Under budget: only the too old one.
        assertEquals(listOf("old"), JsonCache.victims(files, now, 2000, 1000, 14 * day))
        // Over 1000: the oldest go until 600 is left.
        assertEquals(listOf("old", "a", "b"), JsonCache.victims(files, now, 1000, 600, 14 * day))
        // The real folder.
        cache.write("x", JSONObject(), 1000)
        cache.trim(maxBytes = 0, keepBytes = 0)
        assertNull(cache.read("x"))
    }
}
