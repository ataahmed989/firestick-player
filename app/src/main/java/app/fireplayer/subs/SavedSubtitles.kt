package app.fireplayer.subs

import android.content.Context
import app.fireplayer.data.Prefs
import java.io.File

/**
 * Subtitles downloaded with Find subtitles, kept per video so they're there again when it's opened another time,
 * newest use first. Each is let go [KEEP_DAYS] days after it was last used, file and timing with it.
 *
 * The list is a small file beside them ([INDEX]), read once and kept in memory after that (only this object writes
 * it, and only the player's process uses it). Nothing here can take the player down: a list that can't be written
 * (storage full) still holds for the sitting, and one that's there but can't be read is left alone, with every file.
 */
object SavedSubtitles {

    class Saved(
        /** The video's link. */
        val video: String,
        /** The file's name in [dir]. */
        val file: String,
        val language: String?,
        /** The release the subtitles were made for, as OpenSubtitles names it. */
        val release: String,
        val fileId: Long,
        val lastUsed: Long,
        /** On screen when the video was left: chosen again when it's opened. */
        val on: Boolean,
    )

    const val KEEP_DAYS = 30
    private const val KEEP_MS = KEEP_DAYS * 24L * 3600_000L
    internal const val INDEX = "kept.tsv"

    // The list as last read or written, and the folder it's of.
    private var cached: List<Saved>? = null
    private var cachedDir: File? = null

    fun dir(context: Context) = File(context.filesDir, "subs")

    /** This video's subtitles, last used first. */
    fun forVideo(context: Context, video: String): List<Saved> = forVideo(dir(context), video)

    /** A file just downloaded for [video]: kept, and the one on. */
    fun add(context: Context, video: String, file: File, language: String?, release: String, fileId: Long) =
        add(dir(context), video, file, language, release, fileId, System.currentTimeMillis())

    /** [fileName] chosen for [video] (or, with null, other subtitles or none): the one on, and the newest use. */
    fun use(context: Context, video: String, fileName: String?) = use(dir(context), video, fileName, System.currentTimeMillis())

    /**
     * Lets go of subtitles unused for [KEEP_DAYS] days, and of files no video keeps; when anything went, also of
     * whatever timing is still kept for subtitles that are gone ([Prefs.keepSubtitleTimes]). Blocks: run off the main
     * thread.
     */
    fun prune(context: Context) = prune(dir(context), System.currentTimeMillis()) { files, videos ->
        Prefs(context).keepSubtitleTimes(files, videos)
    }

    // The same, on the folder itself (so tests can use one of their own).

    @Synchronized
    internal fun forVideo(dir: File, video: String): List<Saved> =
        read(dir).orEmpty().filter { it.video == video && File(dir, it.file).isFile }.sortedByDescending { it.lastUsed }

    @Synchronized
    internal fun add(dir: File, video: String, file: File, language: String?, release: String, fileId: Long, now: Long) {
        // A list that can't be read isn't replaced by one with only this file in it.
        val list = (read(dir) ?: return).filterNot { it.video == video && (it.file == file.name || it.fileId == fileId) }
            .map { if (it.video == video && it.on) it.copy(on = false) else it }
        write(dir, list + Saved(video, file.name, language, clean(release), fileId, now, true))
    }

    @Synchronized
    internal fun use(dir: File, video: String, fileName: String?, now: Long) {
        val list = read(dir) ?: return
        if (list.none { it.video == video }) return
        write(dir, list.map { s ->
            when {
                s.video != video -> s
                s.file == fileName -> s.copy(lastUsed = now, on = true)
                else -> if (s.on) s.copy(on = false) else s
            }
        })
    }

    /** [keepTimes] is given the files and the videos still kept, when anything was let go. */
    @Synchronized
    internal fun prune(dir: File, now: Long, keepTimes: (files: Set<String>, videos: Set<String>) -> Unit) {
        // A list that's there but can't be read: nothing is let go (every file would look like one no video keeps).
        val all = read(dir) ?: return
        val cutoff = now - KEEP_MS
        val kept = all.filter { it.lastUsed >= cutoff && File(dir, it.file).isFile }
        var changed = kept.size != all.size
        if (changed) write(dir, kept)
        val names = kept.mapTo(HashSet()) { it.file }
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name != INDEX && f.name !in names) {
                f.delete()
                changed = true
            }
        }
        // What was found or set for the timing of subtitles that are gone goes with them, in one sweep.
        if (changed) keepTimes(names, kept.mapTo(HashSet()) { it.video })
    }

    private fun Saved.copy(lastUsed: Long = this.lastUsed, on: Boolean = this.on) =
        Saved(video, file, language, release, fileId, lastUsed, on)

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    /** The list: empty with no list file yet; null when there is one that can't be read (not kept, so tried again). */
    private fun read(dir: File): List<Saved>? {
        cached?.let { if (cachedDir == dir) return it }
        val f = File(dir, INDEX)
        val list = if (!f.exists()) emptyList() else runCatching {
            f.readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 7) return@mapNotNull null
                Saved(p[0], p[1], p[2].ifEmpty { null }, p[3], p[4].toLongOrNull() ?: 0L, p[5].toLongOrNull() ?: 0L, p[6] == "1")
            }
        }.getOrNull() ?: return null
        cached = list
        cachedDir = dir
        return list
    }

    /**
     * Writes the list (to a file beside it, then put in its place). Failing (storage full, say), the list stays as
     * it was on disk and [list] still holds for this sitting; a file whose row never got there is let go by [prune] the
     * next time, as one no video keeps.
     */
    private fun write(dir: File, list: List<Saved>) {
        val tmp = File(dir, "$INDEX.tmp")
        val done = runCatching {
            dir.mkdirs()
            tmp.writeText(list.joinToString("") { s ->
                listOf(clean(s.video), s.file, s.language.orEmpty(), s.release, s.fileId, s.lastUsed, if (s.on) 1 else 0)
                    .joinToString("\t") + "\n"
            })
            tmp.renameTo(File(dir, INDEX))
        }.getOrDefault(false)
        if (!done) runCatching { tmp.delete() }
        cached = list
        cachedDir = dir
    }
}
