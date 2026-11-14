package app.fireplayer.player

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * A film's index, kept on the box after it was first read: the stretch near the end of the file that an MKV keeps its
 * index in (an MP4 sometimes too), read every time the film opens before anything can play. From a provider's server
 * a 16 GB film's 3 MB index took 6 s; from here it's at once. A film's kept index is only used while its file is the
 * same size as when it was kept (a size the provider gives on the film's first request each time it opens).
 *
 * One small file per film, named by a hash of its link (the link holds the login, which is never written), the oldest
 * going once they reach [MAX_TOTAL] together. Shared by the film's connections; files are written whole, then renamed.
 */
object IndexCache {

    /** Only the last stretch of a film counts as its index. */
    const val TAIL = 16L * 1024 * 1024
    /** At most this much of one film. */
    const val MAX_ONE = 6L * 1024 * 1024
    /** Less than this isn't worth a file. */
    const val MIN_ONE = 32L * 1024
    /** All of them together. */
    const val MAX_TOTAL = 48L * 1024 * 1024

    @Volatile var dir: File? = null

    /** Whether a read of the film of [size] bytes from [position] is of its index. */
    fun wanted(size: Long, position: Long): Boolean = size > TAIL * 4 && position >= size - TAIL && position < size

    /** The kept index of [link], if it has [position] and the film is still [size] bytes; null otherwise. */
    fun open(link: String, size: Long, position: Long): Reader? {
        if (!wanted(size, position)) return null
        val file = fileFor(link) ?: return null
        if (!file.exists()) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val keptSize = raf.readLong()
                val start = raf.readLong()
                val count = raf.length() - HEADER
                if (keptSize != size || position < start || position >= start + count || count > MAX_ONE) return null
                // All of it at once: the index is read a few bytes at a time, far too often to go to the disk for each.
                val bytes = ByteArray(count.toInt())
                raf.readFully(bytes)
                file.setLastModified(System.currentTimeMillis())
                Reader(bytes, start)
            }
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    /** A kept index: the film's bytes from [start]. */
    class Reader(private val bytes: ByteArray, private val start: Long) {
        val end: Long get() = start + bytes.size

        /** Up to [length] bytes of the film from [position]; 0 once past what's kept. */
        fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= end || position < start) return 0
            val n = minOf(length.toLong(), end - position).toInt()
            System.arraycopy(bytes, (position - start).toInt(), buffer, offset, n)
            return n
        }

        fun close() = Unit
    }

    /** Takes down what comes from [start] of the film at [link] ([size] bytes), and keeps it when [finish]ed. */
    class Writer(private val link: String, private val size: Long, private val start: Long) {
        private val bytes = ByteArrayOutputStream()
        private var full = false

        fun add(buffer: ByteArray, offset: Int, length: Int) {
            if (full) return
            if (bytes.size() + length > MAX_ONE) {
                full = true
                return
            }
            bytes.write(buffer, offset, length)
        }

        /** Keeps what came, unless it was too much (then it isn't only the index) or too little. */
        fun finish() {
            if (full || bytes.size() < MIN_ONE) return
            val target = fileFor(link) ?: return
            try {
                // A kept index that already reaches as far is left alone.
                if (target.exists() && covers(target)) return
                val temp = File(target.parentFile, target.name + ".part")
                DataOutputStream(temp.outputStream().buffered()).use {
                    it.writeLong(size)
                    it.writeLong(start)
                    bytes.writeTo(it)
                }
                if (!temp.renameTo(target)) temp.delete()
                trim(target.parentFile ?: return)
            } catch (_: Exception) {
            }
        }

        private fun covers(file: File): Boolean = try {
            DataInputStream(FileInputStream(file)).use { input ->
                input.readLong() == size && input.readLong() <= start && file.length() - HEADER >= bytes.size()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun fileFor(link: String): File? {
        val base = dir ?: return null
        val folder = File(base, "index")
        if (!folder.isDirectory && !folder.mkdirs()) return null
        val hash = MessageDigest.getInstance("SHA-256").digest(link.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(folder, hash.take(32))
    }

    /** The oldest kept indexes go until the rest fit in [MAX_TOTAL]. */
    private fun trim(folder: File) {
        val files = folder.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (f in files) {
            // One being written right now is renamed (or removed) by its writer.
            if (f.name.endsWith(".part")) continue
            total += f.length()
            if (total > MAX_TOTAL) f.delete()
        }
    }

    private const val HEADER = 16L
}
