package app.fireplayer.subs

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Reads picture subtitles (Blu-ray PGS, DVD VobSub, DVB) as text, so they can be drawn in the chosen style like
 * any other subtitle when the file's own styling is off.
 *
 * Pictures are read ahead, as the film loads ([readAhead] wraps the subtitle parser), on one low-priority thread,
 * so a subtitle's text is usually ready before it's due. The picture that comes back at display time is a copy of
 * the one that was loaded, so the two are matched by their pixels. Only languages written in the Latin alphabet
 * are read; others stay pictures.
 */
@OptIn(UnstableApi::class)
class PictureText(private val onRead: () -> Unit) {

    private class Job(
        val key: Long,
        val nv21: ByteArray,
        val width: Int,
        val height: Int,
        // Read ahead from this track (format id); null when asked for at display time.
        val track: String?,
        // The track's language is unknown, so a read only counts when it's confident (see [read]).
        val strict: Boolean,
    ) {
        // On screen now, so read first.
        var due = false
    }

    // Results by picture, the most recent few hundred. "" means the picture holds no text.
    private val results = object : LinkedHashMap<Long, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>?) = size > 400
    }
    private val pending = HashSet<Long>()
    private val jobs = LinkedBlockingDeque<Job>()
    private val keys = WeakHashMap<Bitmap, Long>()
    @Volatile private var broken = false
    // The picture track being shown as text: the only one read ahead. Null: none.
    @Volatile private var shownTrack: String? = null
    @Volatile private var released = false
    private var recognizer: TextRecognizer? = null
    // Recognition itself runs here. Playback runs at a far higher priority, so it still comes first, but a
    // background priority could starve this behind the film's audio decoding and make subtitles late.
    private val recognition = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FirePlayerOcrRun").apply { isDaemon = true }
    }

    private val worker = Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        while (!released) {
            val job = try {
                jobs.take()
            } catch (_: InterruptedException) {
                break
            }
            val started = SystemClock.elapsedRealtime()
            val text = read(job)
            Log.d(TAG, "Read a picture in ${SystemClock.elapsedRealtime() - started} ms, ${jobs.size} waiting")
            synchronized(this) {
                pending.remove(job.key)
                if (text != null) results[job.key] = text
            }
            if (text != null) onRead()
        }
        recognizer?.close()
        recognition.shutdown()
    }, "FirePlayerOcr").apply {
        isDaemon = true
        start()
    }

    /** Whether pictures in [language] can be read. */
    fun canRead(language: String?): Boolean = !broken && !released && readable(language)

    /**
     * Sets the picture track (its format id) being shown as text, or null for none. Other tracks in the file are
     * loaded too, but only this one is read ahead; what was waiting from another track is dropped.
     */
    fun showTrack(id: String?) {
        shownTrack = id
        dropQueued { !shows(it.track) }
    }

    /** Drops what was waiting to be read ahead: after a jump the film loads again from somewhere else. */
    fun discardAhead() = dropQueued { true }

    /**
     * Starts reading [bitmap] (a subtitle in [language]) unless it has been read or is on its way. [soon]: it's due
     * now, so it goes first.
     */
    fun prepare(bitmap: Bitmap, language: String?, soon: Boolean = false, track: String? = null) {
        if (broken || released) return
        val key = keyOf(bitmap)
        synchronized(this) {
            if (results.containsKey(key)) return
            if (!pending.add(key)) {
                if (!soon) return
                // Read ahead but not reached yet. Pictures are queued in the order they play, so the ones before
                // it have gone by: drop those, and this one is next. (Not found: it's being read now, or already next.)
                val passed = mutableListOf<Job>()
                var target: Job? = null
                for (job in jobs) {
                    if (job.due) continue
                    if (job.key == key) {
                        target = job
                        break
                    }
                    passed.add(job)
                }
                if (target != null && jobs.remove(target)) {
                    for (job in passed) if (jobs.remove(job)) pending.remove(job.key)
                    target.due = true
                    jobs.offerFirst(target)
                }
                return
            } else if (!soon && pending.size > MAX_QUEUED) {
                // Far ahead of playback after a jump: read it when it's due instead.
                pending.remove(key)
                return
            }
        }
        val job = toJob(key, bitmap, track, unknown(language))
        if (soon) {
            job.due = true
            jobs.offerFirst(job)
        } else {
            jobs.offerLast(job)
        }
    }

    /** The text of [bitmap]: null while it hasn't been read, "" when it holds none. */
    fun textOf(bitmap: Bitmap): String? {
        val key = keyOf(bitmap)
        return synchronized(this) { results[key] }
    }

    fun release() {
        released = true
        jobs.clear()
        worker.interrupt()
    }

    /** Wraps [base] so picture subtitles are sent to be read as soon as they're loaded. */
    fun readAhead(base: SubtitleParser.Factory): SubtitleParser.Factory = object : SubtitleParser.Factory {
        override fun supportsFormat(format: Format) = base.supportsFormat(format)

        override fun getCueReplacementBehavior(format: Format) = base.getCueReplacementBehavior(format)

        override fun create(format: Format): SubtitleParser {
            val parser = base.create(format)
            if (format.sampleMimeType !in PICTURE_FORMATS || !readable(format.language)) return parser
            val track = format.id
            return object : SubtitleParser {
                override fun parse(
                    data: ByteArray,
                    offset: Int,
                    length: Int,
                    outputOptions: SubtitleParser.OutputOptions,
                    output: Consumer<CuesWithTiming>,
                ) {
                    parser.parse(data, offset, length, outputOptions) { cues ->
                        if (shows(track)) for (cue in cues.cues) cue.bitmap?.let { prepare(it, format.language, track = track) }
                        output.accept(cues)
                    }
                }

                override fun reset() = parser.reset()

                override fun getCueReplacementBehavior() = parser.cueReplacementBehavior
            }
        }
    }

    private fun shows(track: String?): Boolean {
        val shown = shownTrack ?: return false
        // Ids of the film's tracks gain a "1:" prefix once subtitle files are added alongside.
        return track != null && (shown == track || shown.endsWith(":$track"))
    }

    private fun dropQueued(drop: (Job) -> Boolean) = synchronized(this) {
        val queued = jobs.iterator()
        while (queued.hasNext()) {
            val job = queued.next()
            if (!job.due && drop(job)) {
                queued.remove()
                pending.remove(job.key)
            }
        }
    }

    // ---------------------------------------------------------------------

    /**
     * Identifies a picture by its pixels. The copy shown went through PNG and back, which keeps opaque colours and
     * alpha exactly but can shift the colour of half-transparent edges, so those only count as "edge".
     */
    private fun keyOf(bitmap: Bitmap): Long {
        synchronized(keys) { keys[bitmap]?.let { return it } }
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var hash = -0x340d631b7bdddcdbL // FNV-1a
        fun mix(value: Int) {
            hash = (hash xor value.toLong()) * 0x100000001b3L
        }
        mix(w)
        mix(h)
        for (p in pixels) {
            val a = p ushr 24
            mix(
                when {
                    a == 255 -> p and 0xFFFFFF
                    a >= 128 -> 0x1000000
                    else -> 0x2000000
                },
            )
        }
        synchronized(keys) { keys[bitmap] = hash }
        return hash
    }

    /**
     * Turns the picture into dark text on white, the way text recognition reads best: bright letters become dark,
     * and their dark outline and the see-through background both become white. Two-line Blu-ray pictures are
     * halved, which still leaves letters about 30 pixels tall and makes reading four times quicker; tiny ones are
     * doubled.
     */
    private fun toJob(key: Long, bitmap: Bitmap, track: String?, strict: Boolean): Job {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        // Dark on white, before resizing: 255 where nothing is drawn.
        val grey = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = p ushr 24
            grey[i] = if (a == 0) 255 else {
                val lum = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
                255 - lum * a / 255
            }
        }
        val halve = h > LARGE_HEIGHT
        val double = h < SMALL_HEIGHT
        val sw = if (halve) w / 2 else if (double) w * 2 else w
        val sh = if (halve) h / 2 else if (double) h * 2 else h
        val outW = (sw + 2 * PAD + 1) and 1.inv()
        val outH = (sh + 2 * PAD + 1) and 1.inv()
        // NV21: a grey plane, then colour at half resolution (left neutral).
        val nv21 = ByteArray(outW * outH * 3 / 2)
        java.util.Arrays.fill(nv21, 0, outW * outH, 0xFF.toByte())
        java.util.Arrays.fill(nv21, outW * outH, nv21.size, 0x80.toByte())
        for (y in 0 until sh) {
            val row = (PAD + y) * outW + PAD
            for (x in 0 until sw) {
                val value = when {
                    halve -> {
                        val i = 2 * y * w + 2 * x
                        (grey[i] + grey[i + 1] + grey[i + w] + grey[i + w + 1]) / 4
                    }
                    double -> grey[(y / 2) * w + x / 2]
                    else -> grey[y * w + x]
                }
                nv21[row + x] = value.toByte()
            }
        }
        return Job(key, nv21, outW, outH, track, strict)
    }

    /** Text read from the job, "" for none, or null when reading failed and should be tried again later. */
    private fun read(job: Job): String? {
        if (broken) return null
        return try {
            val client = recognizer
                ?: TextRecognition.getClient(TextRecognizerOptions.Builder().setExecutor(recognition).build()).also { recognizer = it }
            val image = InputImage.fromByteArray(job.nv21, job.width, job.height, 0, InputImage.IMAGE_FORMAT_NV21)
            // Generous: a slow read still beats none, and it's kept for when the subtitle comes round again.
            val result = Tasks.await(client.process(image), 30, TimeUnit.SECONDS)
            val found = result.textBlocks.flatMap { it.lines }
            // A track of unknown language may be Cyrillic or Chinese, which this recogniser turns into look-alike
            // letters: only a confident read replaces the picture.
            if (job.strict && found.any { it.confidence < MIN_CONFIDENCE }) return ""
            val lines = found.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                box to line.text
            }
            assemble(lines)
        } catch (e: InterruptedException) {
            null
        } catch (e: java.util.concurrent.TimeoutException) {
            // Busy (or still loading the model the first time): this one is read again when it's due.
            null
        } catch (e: Throwable) {
            // No recogniser on this device (or it failed to load): pictures stay pictures.
            Log.w(TAG, "Text recognition unavailable", e)
            broken = true
            null
        }
    }

    /** Puts the recognised lines back in reading order: top to bottom, and pieces of one row left to right. */
    private fun assemble(lines: List<Pair<Rect, String>>): String {
        if (lines.isEmpty()) return ""
        val rows = mutableListOf<MutableList<Pair<Rect, String>>>()
        for (line in lines.sortedBy { it.first.centerY() }) {
            val row = rows.lastOrNull()
            val previous = row?.last()?.first
            if (row != null && previous != null && kotlin.math.abs(line.first.centerY() - previous.centerY()) < previous.height() / 2) {
                row.add(line)
            } else {
                rows.add(mutableListOf(line))
            }
        }
        return rows.joinToString("\n") { row ->
            row.sortedBy { it.first.left }.joinToString(" ") { clean(it.second) }
        }.trim()
    }

    private fun clean(text: String): String = text
        // A capital I in subtitle fonts often comes back as a bar.
        .replace('|', 'I')
        .replace(Regex("\\s+"), " ")
        .trim()

    companion object {
        private const val TAG = "FirePlayerOcr"
        private const val MAX_QUEUED = 30
        // Blu-ray pictures of two lines are about 140 pixels tall, DVD ones about 80.
        private const val LARGE_HEIGHT = 120
        private const val SMALL_HEIGHT = 50
        private const val PAD = 16
        private const val MIN_CONFIDENCE = 0.7f

        val PICTURE_FORMATS = setOf(MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_VOBSUB, MimeTypes.APPLICATION_DVBSUBS)

        /** Languages written in other alphabets, which the Latin text recogniser can't read. */
        private val OTHER_SCRIPTS = setOf(
            "ar", "fa", "ur", "ps", "ku", "he", "yi", "ru", "uk", "be", "bg", "mk", "kk", "ky", "tg", "mn", "el",
            "hy", "ka", "zh", "ja", "ko", "hi", "mr", "ne", "bn", "pa", "gu", "or", "ta", "te", "kn", "ml", "si",
            "th", "lo", "km", "my", "am", "ti", "dv", "sr", "tt", "ba", "cv", "os", "ce", "sah", "ab", "av", "ug",
            "sd", "ks", "bo", "dz", "iu",
        )

        private fun unknown(language: String?): Boolean =
            language == null || language.lowercase() in setOf("und", "mul", "mis", "zxx")

        fun readable(language: String?): Boolean {
            val code = language?.lowercase()?.substringBefore('-')?.substringBefore('_') ?: return true
            return code !in OTHER_SCRIPTS
        }
    }
}
