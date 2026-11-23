package app.fireplayer.titles

import android.content.Context
import java.io.File

/**
 * What the title server, in a process of its own, needs to know from the app, as small files of their own (in the
 * app's no-backup folder): whether it's on ([ON]), when the player last said it's playing ([BEAT]: the file's time) and
 * the TMDB key ([KEY]). The app writes them, each by a whole new file put in place; the title server only reads them.
 * It never opens the app's settings: in another process that can put an older copy of them back over a change being
 * saved.
 */
internal object TitleFiles {
    private const val DIR = "titles"
    private const val ON = "on"
    private const val BEAT = "beat"
    private const val KEY = "tmdb"

    private fun dir(context: Context) = File(context.noBackupFilesDir, DIR)

    // ---- The title server: reads only ----

    /** On or off as the app last said; null when it hasn't said yet (the first start after an update). */
    fun isOn(context: Context): Boolean? = when (read(File(dir(context), ON))) {
        "1" -> true
        "0" -> false
        else -> null
    }

    /** When the player last noted it's playing (ms), 0 if never. */
    fun beatAt(context: Context): Long = File(dir(context), BEAT).lastModified()

    /** The TMDB key, read again only when the app has written a new one. */
    class Key(context: Context) {
        private val file = File(dir(context), KEY)
        private var readAt = Long.MIN_VALUE
        private var key = ""

        fun get(): String {
            val at = file.lastModified()
            if (at != readAt) {
                key = read(file) ?: ""
                readAt = at
            }
            return key
        }
    }

    // ---- The app ----

    fun writeOn(context: Context, on: Boolean) {
        val text = if (on) "1" else "0"
        val file = File(dir(context), ON)
        if (read(file) != text) write(file, text)
    }

    /** The player is on: the beat file's time is now. */
    fun touchBeat(context: Context) {
        val file = File(dir(context), BEAT)
        val now = System.currentTimeMillis()
        if (file.setLastModified(now)) return
        runCatching {
            file.parentFile?.mkdirs()
            file.createNewFile()
            file.setLastModified(now)
        }
    }

    /** The title server's copy of the TMDB key, when it differs (a blank one never replaces a key). */
    fun keepKey(context: Context, key: String) {
        if (key.isBlank()) return
        val file = File(dir(context), KEY)
        if (read(file) != key) write(file, key)
    }

    private fun read(file: File): String? = runCatching { file.readText().trim() }.getOrNull()

    /** A whole new file, put in place in one step: a reader sees the old one or the new one, never half of it. */
    private fun write(file: File, text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".new")
            java.io.FileOutputStream(temp).use { out ->
                out.write(text.toByteArray())
                out.fd.sync()
            }
            if (!temp.renameTo(file)) temp.delete()
        }
    }
}
