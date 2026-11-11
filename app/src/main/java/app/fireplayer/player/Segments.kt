package app.fireplayer.player

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where an episode's recap, intro and credits are, in milliseconds of this very file. [afterCredits] is where
 * something worth watching starts again after the credits (a scene, or a preview of the next one), or null when the
 * credits run to the end. [from] says where it came from: "chapters" (the file's own, exact) or "introdb".
 */
class Segments(
    val recap: LongRange?,
    val intro: LongRange?,
    val creditsAt: Long?,
    val afterCredits: Long?,
    val from: String,
) {
    /** The button to show at [position], and where it jumps to, or null. */
    fun skip(position: Long): Skip? {
        fun inside(r: LongRange?) = r != null && position >= r.first && position < r.last - SKIP_TAIL_MS
        return when {
            inside(intro) -> Skip("Skip intro", intro!!.first, intro.last)
            inside(recap) -> Skip("Skip recap", recap!!.first, recap.last)
            creditsAt != null && afterCredits != null && position >= creditsAt && position < afterCredits - SKIP_TAIL_MS ->
                Skip("Skip credits", creditsAt, afterCredits)
            else -> null
        }
    }

    /** When the end card may come up: at the credits, if nothing follows them. */
    val endCardAt: Long? get() = creditsAt?.takeIf { afterCredits == null }

    class Skip(val label: String, val start: Long, val target: Long)

    /** These, with what [other] knows and this doesn't. */
    fun or(other: Segments?): Segments = if (other == null) this else Segments(
        recap ?: other.recap, intro ?: other.intro,
        creditsAt ?: other.creditsAt, if (creditsAt != null) afterCredits else other.afterCredits, from,
    )

    companion object {
        // The button goes once the jump would save less than this.
        private const val SKIP_TAIL_MS = 2_000L
    }
}

/**
 * The file's own chapters, when it has any named like a recap, intro or credits. Read with one plain request for the
 * start of the file (where MKV muxers put chapters), and a range request when the file says they're further on.
 */
object MkvChapters {
    private const val TAG = "FirePlayerSegments"
    private const val HEAD_BYTES = 1 shl 20

    private val INTRO = Regex("""^\s*(intro|opening|opening (credits|titles|theme)|title sequence|main titles|op)\b""", RegexOption.IGNORE_CASE)
    private val RECAP = Regex("""^\s*(recap|previously)\b""", RegexOption.IGNORE_CASE)
    private val CREDITS = Regex("""^\s*(credits|end credits|ending( credits)?|closing( credits)?|end titles|outro|ed)\b""", RegexOption.IGNORE_CASE)

    class Chapter(val startMs: Long, val endMs: Long?, val name: String)

    /** Blocks. Null when the file has no such chapters, isn't an MKV, or can't be read. */
    fun segments(uri: Uri, headers: Map<String, String>, durationMs: Long): Segments? = runCatching {
        val chapters = read(uri, headers) ?: return null
        if (chapters.isNotEmpty()) Log.i(TAG, "Chapters: " + chapters.joinToString { "${it.name}@${it.startMs / 1000}s" })
        classify(chapters, durationMs)
    }.onFailure { Log.w(TAG, "Couldn't read chapters", it) }.getOrNull()

    fun classify(chapters: List<Chapter>, durationMs: Long): Segments? {
        val sorted = chapters.sortedBy { it.startMs }
        fun end(i: Int) = sorted[i].endMs?.takeIf { it > sorted[i].startMs } ?: sorted.getOrNull(i + 1)?.startMs ?: durationMs
        fun range(regex: Regex, before: Long): LongRange? {
            val i = sorted.indexOfFirst { regex.containsMatchIn(it.name) && it.startMs < before }
            return if (i < 0) null else sorted[i].startMs..end(i)
        }
        val recap = range(RECAP, durationMs / 3)
        val intro = range(INTRO, durationMs / 3)
        // The credits: the last chapter so named in the second half.
        val c = sorted.indexOfLast { CREDITS.containsMatchIn(it.name) && it.startMs > durationMs / 2 }
        val creditsAt = if (c < 0) null else sorted[c].startMs
        val creditsEnd = if (c < 0) null else end(c)
        // A chapter after the credits that isn't more credits, and long enough to be something.
        val after = creditsEnd?.takeIf { durationMs - it > AFTER_MIN_MS }
        if (recap == null && intro == null && creditsAt == null) return null
        return Segments(recap, intro, creditsAt, after, "chapters")
    }

    private const val AFTER_MIN_MS = 15_000L

    private fun read(uri: Uri, headers: Map<String, String>): List<Chapter>? {
        val head = get(uri, headers, null, HEAD_BYTES) ?: return null
        val reader = Ebml(head)
        // EBML header, then the Segment.
        if (reader.id() != 0x1A45DFA3L) return null
        reader.skip(reader.size())
        if (reader.id() != 0x18538067L) return null
        reader.size()
        val segmentStart = reader.pos.toLong()
        var chaptersAt = -1L
        while (reader.remaining() > 12) {
            val id = reader.id()
            val size = reader.size()
            when (id) {
                0x114D9B74L -> { // SeekHead
                    val end = reader.pos + size.toInt()
                    if (end > head.size) return null
                    while (reader.pos < end) {
                        val sid = reader.id()
                        val ssize = reader.size()
                        if (sid != 0x4DBBL) { reader.skip(ssize); continue }
                        val seekEnd = reader.pos + ssize.toInt()
                        var what = 0L
                        var where = -1L
                        while (reader.pos < seekEnd) {
                            val eid = reader.id()
                            val esize = reader.size().toInt()
                            when (eid) {
                                0x53ABL -> what = reader.uint(esize)
                                0x53ACL -> where = reader.uint(esize)
                                else -> reader.skip(esize.toLong())
                            }
                        }
                        if (what == 0x1043A770L) chaptersAt = where
                    }
                }
                0x1043A770L -> { // Chapters
                    if (size < 0 || reader.pos + size > head.size) break
                    return chapters(Ebml(head, reader.pos, reader.pos + size.toInt()))
                }
                0x1F43B675L -> break // a Cluster: the pictures start, chapters weren't before them
                else -> {
                    if (size < 0) break
                    reader.skip(size)
                }
            }
        }
        if (chaptersAt < 0) return emptyList()
        // Placed further on (some muxers put them at the end): fetched on their own, if the server does ranges.
        val at = segmentStart + chaptersAt
        val bytes = get(uri, headers, at, 256 * 1024) ?: return emptyList()
        val r = Ebml(bytes)
        if (r.id() != 0x1043A770L) return emptyList()
        val size = r.size()
        return chapters(Ebml(bytes, r.pos, minOf(bytes.size, r.pos + size.toInt())))
    }

    /** The first edition's chapters. */
    private fun chapters(r: Ebml): List<Chapter> {
        val out = ArrayList<Chapter>()
        while (r.remaining() > 2) {
            val id = r.id()
            val size = r.size()
            if (id != 0x45B9L) { r.skip(size); continue } // EditionEntry
            val end = r.pos + size.toInt()
            while (r.pos < end) {
                val aid = r.id()
                val asize = r.size()
                if (aid != 0xB6L) { r.skip(asize); continue } // ChapterAtom
                val atomEnd = r.pos + asize.toInt()
                var start = -1L
                var stop: Long? = null
                var name = ""
                var hidden = false
                while (r.pos < atomEnd) {
                    val eid = r.id()
                    val esize = r.size()
                    when (eid) {
                        0x91L -> start = r.uint(esize.toInt()) / 1_000_000
                        0x92L -> stop = r.uint(esize.toInt()) / 1_000_000
                        0x98L -> hidden = r.uint(esize.toInt()) != 0L
                        0x80L -> { // ChapterDisplay
                            val dEnd = r.pos + esize.toInt()
                            while (r.pos < dEnd) {
                                val did = r.id()
                                val dsize = r.size()
                                if (did == 0x85L && name.isEmpty()) name = r.string(dsize.toInt()) else r.skip(dsize)
                            }
                        }
                        else -> r.skip(esize)
                    }
                }
                if (start >= 0 && !hidden) out += Chapter(start, stop, name)
            }
            return out
        }
        return out
    }

    /** Up to [max] bytes from [from] (the start when null), or null. */
    private fun get(uri: Uri, headers: Map<String, String>, from: Long?, max: Int): ByteArray? {
        val conn = URL(uri.toString()).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 10_000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (from != null) conn.setRequestProperty("Range", "bytes=$from-${from + max - 1}")
        try {
            val code = conn.responseCode
            if (from != null && code != 206) return null
            if (from == null && code != 200 && code != 206) return null
            return conn.inputStream.use { readUpTo(it, max) }
        } finally {
            conn.disconnect()
        }
    }

    private fun readUpTo(input: InputStream, max: Int): ByteArray {
        val out = ByteArray(max)
        var got = 0
        while (got < max) {
            val n = input.read(out, got, max - got)
            if (n < 0) break
            got += n
        }
        return out.copyOf(got)
    }

    /** Reads EBML elements from bytes; running past the end throws, which the callers turn into "no chapters". */
    private class Ebml(val b: ByteArray, var pos: Int = 0, val end: Int = b.size) {
        fun remaining() = end - pos

        private fun vint(keepMarker: Boolean): Long {
            val first = b[pos].toInt() and 0xFF
            if (first == 0) throw IllegalStateException("bad EBML")
            val len = Integer.numberOfLeadingZeros(first) - 23
            var v = if (keepMarker) first.toLong() else (first and (0xFF shr len)).toLong()
            var allOnes = v == (0xFF shr len).toLong()
            for (i in 1 until len) {
                val x = b[pos + i].toInt() and 0xFF
                if (x != 0xFF) allOnes = false
                v = (v shl 8) or x.toLong()
            }
            pos += len
            return if (!keepMarker && allOnes) -1L else v
        }

        fun id() = vint(true)
        /** -1 for "unknown size". */
        fun size() = vint(false)
        fun skip(n: Long) { if (n < 0 || pos + n > end) throw IllegalStateException("past end"); pos += n.toInt() }
        fun uint(n: Int): Long { var v = 0L; repeat(n) { v = (v shl 8) or (b[pos++].toLong() and 0xFF) }; return v }
        fun string(n: Int): String { val s = String(b, pos, n, Charsets.UTF_8).trimEnd('\u0000'); pos += n; return s }
    }
}

/**
 * TheIntroDB (theintrodb.org): times people submitted for a show's intro, recap and credits, by TMDB id and TMDB's
 * season and episode numbers. It doesn't say how long the file was that they were timed on, so they're used only for
 * a file whose length agrees with TMDB's for the episode, and only where they fit this file.
 */
object IntroDb {
    private const val TAG = "FirePlayerSegments"
    private const val BASE = "https://api.theintrodb.org/v4/media"
    private const val KEPT_MS = 7 * 24 * 60 * 60_000L
    private const val MISS_KEPT_MS = 24 * 60 * 60_000L
    // TMDB gives whole minutes, sometimes rounded either way.
    private const val LENGTH_SLACK_MS = 90_000L
    private const val CREDITS_MIN_MS = 10_000L
    private const val CREDITS_MAX_MS = 4 * 60_000L

    /** Blocks. [tmdbMinutes] is TMDB's length for the episode; without it nothing is trusted. */
    fun episode(context: Context, showTmdb: Int, season: Int, episode: Int, tmdbMinutes: Int, durationMs: Long): Segments? {
        if (showTmdb <= 0 || tmdbMinutes <= 0 || durationMs <= 0) return null
        if (Math.abs(durationMs - tmdbMinutes * 60_000L) > LENGTH_SLACK_MS) {
            Log.i(TAG, "Not using TheIntroDB: this file is ${durationMs / 1000}s, TMDB says $tmdbMinutes min")
            return null
        }
        val o = fetch(context, "tmdb_id=$showTmdb&season=$season&episode=$episode&duration_ms=$durationMs") ?: return null
        if (o.optString("type") != "tv") return null
        return fitted(o, durationMs)
    }

    /** The times that fit this file; anything that doesn't is left out. */
    private fun fitted(o: JSONObject, durationMs: Long): Segments? {
        fun first(name: String): Pair<Long?, Long?>? {
            val a = o.optJSONArray(name) ?: return null
            if (a.length() == 0) return null
            val s = a.getJSONObject(0)
            fun ms(k: String) = if (s.isNull(k)) null else s.optLong(k, -1).takeIf { it >= 0 }
            return ms("start_ms") to ms("end_ms")
        }
        fun opening(name: String): LongRange? {
            val (start, end) = first(name) ?: return null
            val from = start ?: 0L
            if (end == null || end <= from || end - from > 5 * 60_000L || end > durationMs / 3) return null
            return from..end
        }
        val recap = opening("recap")
        val intro = opening("intro")
        var creditsAt: Long? = null
        var after: Long? = null
        first("credits")?.let { (start, end) ->
            if (start == null) return@let
            val left = durationMs - start
            if (left !in CREDITS_MIN_MS..CREDITS_MAX_MS * 3) return@let
            // Something after the credits: a scene or a preview. Then the credits needn't be at the very end.
            if (end != null && durationMs - end > 15_000L && end > start) {
                creditsAt = start
                after = end
            } else if (left <= CREDITS_MAX_MS) {
                creditsAt = start
            }
        }
        // A preview of the next episode after the credits counts as something after them too.
        first("preview")?.let { (start, _) -> if (start != null && creditsAt != null && after == null && start > creditsAt!!) after = start }
        if (recap == null && intro == null && creditsAt == null) return null
        return Segments(recap, intro, creditsAt, after, "introdb")
    }

    private fun fetch(context: Context, query: String): JSONObject? {
        val file = File(File(context.cacheDir, "segments").apply { mkdirs() }, "${query.substringBefore("&duration").hashCode().toUInt()}.json")
        if (file.exists()) {
            val age = System.currentTimeMillis() - file.lastModified()
            val text = runCatching { file.readText() }.getOrNull()
            if (text != null) {
                if (text.isEmpty() && age < MISS_KEPT_MS) return null
                if (text.isNotEmpty() && age < KEPT_MS) return runCatching { JSONObject(text) }.getOrNull()
            }
        }
        val conn = URL("$BASE?$query").openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "FirePlayer/1.0 (Linux; Android)")
        conn.setRequestProperty("Accept", "application/json")
        return try {
            when (conn.responseCode) {
                200 -> {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    runCatching { file.writeText(text) }
                    JSONObject(text)
                }
                404 -> { runCatching { file.writeText("") }; null }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "TheIntroDB didn't answer", e)
            null
        } finally {
            conn.disconnect()
        }
    }
}
