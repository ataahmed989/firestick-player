package app.fireplayer.vod.data

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small cache of JSON answers on disk (`cache/vod`), so rows show at once when FirePlayer opens and are refreshed in
 * the background. Each entry is one file named by a hash of its key, written whole to a temporary file and renamed, so
 * a reader never sees half of one. Entries keep when they were stored and for how long they count as fresh; an old
 * entry is still read (shown while the fresh answer is fetched). Keys never hold a TMDB key.
 *
 * Plain java.io: works the same in JVM tests. Any thread; nothing is logged.
 */
class JsonCache(private val dir: File, private val clock: () -> Long) {

    private val writes = AtomicInteger()

    /** A stored answer: [data], stored at [at] (ms), fresh for [freshMs]. */
    class Entry(val at: Long, val freshMs: Long, val data: JSONObject) {
        /** Still fresh at [now]; never when the clock has gone back past [at]. */
        fun isFresh(now: Long) = now >= at && now - at < freshMs
    }

    fun read(key: String): Entry? {
        val file = fileFor(key)
        if (!file.isFile) return null
        return try {
            val o = JSONObject(file.readText(Charsets.UTF_8))
            if (o.optString("key") != key) return null
            val data = o.optJSONObject("data") ?: return null
            Entry(o.optLong("at"), o.optLong("fresh"), data)
        } catch (t: Throwable) {
            // Damaged (a full disk, an older format): as if never stored.
            file.delete()
            null
        }
    }

    fun write(key: String, data: JSONObject, freshMs: Long, at: Long = clock()) {
        try {
            if (!dir.isDirectory) dir.mkdirs()
            val text = JSONObject().put("key", key).put("at", at).put("fresh", freshMs).put("data", data).toString()
            val tmp = File.createTempFile("entry", ".tmp", dir)
            try {
                tmp.writeText(text, Charsets.UTF_8)
                if (!tmp.renameTo(fileFor(key))) tmp.delete()
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        } catch (t: Throwable) {
            // A cache: losing an entry only means asking the server again.
        }
        // Kept small while the process lives (days on a TV), not only when it starts: every so many writes (all on
        // background threads), the folder is trimmed.
        if (writes.incrementAndGet() % TRIM_EVERY == 0) runCatching { trim() }
    }

    fun remove(key: String) {
        fileFor(key).delete()
    }

    /**
     * Keeps the folder small: entries older than [maxAgeMs] go, then the oldest until the rest fit in [keepBytes]
     * (only when the total is over [maxBytes]). Leftover temporary files go too.
     */
    fun trim(maxBytes: Long = MAX_BYTES, keepBytes: Long = KEEP_BYTES, maxAgeMs: Long = MAX_AGE_MS) {
        val files = dir.listFiles() ?: return
        val now = clock()
        files.filter { it.name.endsWith(".tmp") && now - it.lastModified() > 60_000 }.forEach { it.delete() }
        val entries = files.filter { it.name.endsWith(".json") }.map { Facts(it.name, it.length(), it.lastModified()) }
        for (name in victims(entries, now, maxBytes, keepBytes, maxAgeMs)) File(dir, name).delete()
    }

    private fun fileFor(key: String) = File(dir, fileName(key))

    /** A cached file: its name, size and when it was written. */
    data class Facts(val name: String, val bytes: Long, val modified: Long)

    companion object {
        const val MAX_BYTES = 8L shl 20
        const val KEEP_BYTES = 6L shl 20
        const val MAX_AGE_MS = 14 * 24 * 3600_000L
        /** Writes between two trims (a Cinemeta page is about 45 KB: 64 of them about 3 MB). */
        const val TRIM_EVERY = 64

        fun fileName(key: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) } + ".json"
        }

        /** Which files [trim] deletes: too old ones, then the oldest of the rest while over budget. */
        fun victims(files: List<Facts>, now: Long, maxBytes: Long, keepBytes: Long, maxAgeMs: Long): List<String> {
            val (old, rest) = files.partition { now - it.modified > maxAgeMs }
            val out = old.map { it.name }.toMutableList()
            var total = rest.sumOf { it.bytes }
            if (total <= maxBytes) return out
            for (f in rest.sortedBy { it.modified }) {
                if (total <= keepBytes) break
                out += f.name
                total -= f.bytes
            }
            return out
        }
    }
}
