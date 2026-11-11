package app.fireplayer.subs

import android.content.Context
import app.fireplayer.data.Prefs
import java.io.File

/**
 * Subtitles downloaded with Find subtitles, kept per video so they're there again when it's opened another time,
 * newest use first. Each is let go [KEEP_DAYS] days after it was last used, file and timing with it.
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
    private const val INDEX = "kept.tsv"

    fun dir(context: Context) = File(context.filesDir, "subs")

    /** This video's subtitles, last used first. */
    @Synchronized
    fun forVideo(context: Context, video: String): List<Saved> =
        read(context).filter { it.video == video && File(dir(context), it.file).isFile }.sortedByDescending { it.lastUsed }

    /** A file just downloaded for [video]: kept, and the one on. */
    @Synchronized
    fun add(context: Context, video: String, file: File, language: String?, release: String, fileId: Long) {
        val now = System.currentTimeMillis()
        val list = read(context).filterNot { it.video == video && (it.file == file.name || it.fileId == fileId) }
            .map { if (it.video == video && it.on) it.copy(on = false) else it }
        write(context, list + Saved(video, file.name, language, clean(release), fileId, now, true))
    }

    /** [fileName] chosen for [video] (or, with null, other subtitles or none): the one on, and the newest use. */
    @Synchronized
    fun use(context: Context, video: String, fileName: String?) {
        val now = System.currentTimeMillis()
        val list = read(context)
        if (list.none { it.video == video }) return
        write(context, list.map { s ->
            when {
                s.video != video -> s
                s.file == fileName -> s.copy(lastUsed = now, on = true)
                else -> if (s.on) s.copy(on = false) else s
            }
        })
    }

    /** Lets go of subtitles unused for [KEEP_DAYS] days, and of files no video keeps. Blocks: run off the main thread. */
    @Synchronized
    fun prune(context: Context) {
        val all = read(context)
        val cutoff = System.currentTimeMillis() - KEEP_MS
        val dir = dir(context)
        val kept = all.filter { it.lastUsed >= cutoff && File(dir, it.file).isFile }
        if (kept.size != all.size) write(context, kept)
        val names = kept.mapTo(HashSet()) { it.file }
        val prefs = Prefs(context)
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name != INDEX && f.name !in names) {
                f.delete()
                prefs.forgetSubtitleTimes(f.name)
            }
        }
    }

    private fun Saved.copy(lastUsed: Long = this.lastUsed, on: Boolean = this.on) =
        Saved(video, file, language, release, fileId, lastUsed, on)

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    private fun read(context: Context): List<Saved> {
        val f = File(dir(context), INDEX)
        if (!f.isFile) return emptyList()
        return runCatching {
            f.readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 7) return@mapNotNull null
                Saved(p[0], p[1], p[2].ifEmpty { null }, p[3], p[4].toLongOrNull() ?: 0L, p[5].toLongOrNull() ?: 0L, p[6] == "1")
            }
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, list: List<Saved>) {
        val dir = dir(context).apply { mkdirs() }
        val tmp = File(dir, "$INDEX.tmp")
        tmp.writeText(list.joinToString("") { s ->
            listOf(clean(s.video), s.file, s.language.orEmpty(), s.release, s.fileId, s.lastUsed, if (s.on) 1 else 0)
                .joinToString("\t") + "\n"
        })
        tmp.renameTo(File(dir, INDEX))
    }
}
