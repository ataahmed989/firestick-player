package app.fireplayer.ui

import java.io.File
import java.security.MessageDigest

/**
 * Pictures as they were downloaded (posters, backdrops), kept on disk so rows show them again without the network:
 * about [maxBytes] in [dir], one file per picture named by a hash of its address (addresses never appear on disk or in
 * a log). Over the limit, the oldest are deleted (a read makes a file new again, at most once an hour) down to 90%.
 * Called from the image threads only; every method is synchronized.
 */
class ImageDiskCache(private val dir: File, private val maxBytes: Long = MAX_BYTES) {

    /** Bytes on disk; measured on first use. */
    private var total = -1L

    @Synchronized
    fun read(url: String): ByteArray? {
        val f = File(dir, nameOf(url))
        if (!f.isFile) return null
        return try {
            val bytes = f.readBytes()
            val now = System.currentTimeMillis()
            if (now - f.lastModified() > TOUCH_MS) f.setLastModified(now)
            bytes
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun has(url: String): Boolean = File(dir, nameOf(url)).isFile

    @Synchronized
    fun write(url: String, bytes: ByteArray) {
        try {
            measure()
            val name = nameOf(url)
            val f = File(dir, name)
            val old = if (f.isFile) f.length() else 0L
            val tmp = File(dir, "$name.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) {
                tmp.delete()
                return
            }
            total += bytes.size - old
            if (total > maxBytes) trim(keep = name)
        } catch (e: Exception) {
            // A full disk or a removed folder: the picture is simply not kept.
        }
    }

    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        total = 0L
    }

    private fun measure() {
        if (total >= 0) return
        if (!dir.isDirectory) dir.mkdirs()
        var sum = 0L
        dir.listFiles()?.forEach { f ->
            // A write cut short (the app stopped mid-way) leaves its temporary file: gone now.
            if (f.name.endsWith(".tmp")) f.delete() else sum += f.length()
        }
        total = sum
    }

    private fun trim(keep: String) {
        val files = dir.listFiles() ?: return
        val entries = files.filter { it.isFile }.map { DiskTrim.Entry(it.name, it.length(), it.lastModified()) }
        for (victim in DiskTrim.victims(entries, total, maxBytes, setOf(keep))) {
            if (File(dir, victim.name).delete()) total -= victim.size
        }
    }

    companion object {
        const val MAX_BYTES = 100L shl 20
        private const val TOUCH_MS = 60L * 60 * 1000

        fun nameOf(url: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                val v = b.toInt() and 0xFF
                sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
            }
            return sb.toString()
        }

        private val HEX = "0123456789abcdef".toCharArray()
    }
}

/** Which files go when the disk cache is over its limit (pure, unit-tested): the oldest first, down to 90%. */
object DiskTrim {
    data class Entry(val name: String, val size: Long, val modified: Long)

    /**
     * Files to delete from [files] ([total] bytes) so they fit [max]: oldest first until the total is at most [low]
     * of [max]. Files named in [keep] (the one just written) are never chosen. Nothing when already within [max].
     */
    fun victims(files: List<Entry>, total: Long, max: Long, keep: Set<String> = emptySet(), low: Double = LOW): List<Entry> {
        if (total <= max) return emptyList()
        val goal = (max * low).toLong()
        var left = total
        val out = ArrayList<Entry>()
        for (e in files.sortedWith(compareBy<Entry>({ it.modified }, { it.name }))) {
            if (left <= goal) break
            if (e.name in keep) continue
            out += e
            left -= e.size
        }
        return out
    }

    const val LOW = 0.9
}
