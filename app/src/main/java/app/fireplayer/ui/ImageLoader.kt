package app.fireplayer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import app.fireplayer.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Something a picture is shown in: an image view, or a view that draws its picture itself (a film's card). The loader
 * sets [imageKey] to the picture it's fetching for it; a picture arriving for another key (the target was given
 * something else meanwhile) is never delivered.
 */
interface ImageTarget {
    var imageKey: String?

    /** The picture, or null to clear it. [fromMemory]: it was ready at once (nothing to fade in). Main thread. */
    fun setImage(bitmap: Bitmap?, fromMemory: Boolean)
}

/**
 * Loads pictures (episode stills, posters, backdrops) off the main thread and below the film's own threads.
 *
 * - Memory: decoded pictures for a while (12 MB), keyed by address and size, so one address shown at two sizes (a
 *   wide card and a header) is two entries.
 * - Disk: the downloaded bytes, about 100 MB, oldest out first ([ImageDiskCache]), so rows come back without the
 *   network. [prefetch] puts bytes there ahead of need.
 * - Order: the newest request first (a held D-pad queues many; the cards on screen now go first), one download per
 *   picture however many cards wait for it, and a picture nobody waits for any more is not decoded.
 *
 * Posters and cards are decoded to exactly the size they're drawn at, centre-cropped, in RGB_565. Addresses are never
 * logged (a TMDB address could carry a key) and appear on disk only as hashes.
 */
object ImageLoader {

    private val main = Handler(Looper.getMainLooper())

    /** The newest job first. */
    private class LifoQueue : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable): Boolean = offerFirst(e)
    }

    private val pool = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, LifoQueue()) { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "FirePlayerImages").apply { isDaemon = true }
    }

    private val cache = object : LruCache<String, Bitmap>(12 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    @Volatile private var disk: ImageDiskCache? = null

    /** Jobs by memory key, with the targets waiting for each. Main thread only. */
    private val jobs = HashMap<String, Job>()

    /**
     * Addresses not to ask for again until a time: a day for one that gave no picture (missing, gone, not a picture),
     * half a minute after a network failure, a timeout or server trouble (an offline TV doesn't ask on every key press).
     */
    private val failed = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Prefetches: bytes to disk only, on one lowest-priority thread, newest first, the oldest dropped past 32. */
    private val ahead = LinkedBlockingDeque<String>()
    @Volatile private var aheadThread: Thread? = null

    /** The disk cache, under the app's cache folder. Safe to call more than once; also done from a target's context. */
    fun init(context: Context) {
        if (disk != null) return
        synchronized(this) {
            if (disk == null) disk = ImageDiskCache(File(context.applicationContext.cacheDir, "images"))
        }
    }

    // ---- Image views (episode stills, covers): as before -------------------------------------------------------

    private class ViewTarget(val view: ImageView) : ImageTarget {
        override var imageKey: String?
            get() = view.getTag(R.id.image_key) as? String
            set(v) = view.setTag(R.id.image_key, v)

        override fun setImage(bitmap: Bitmap?, fromMemory: Boolean) {
            if (bitmap == null || fromMemory) {
                view.setImageBitmap(bitmap)
                return
            }
            view.alpha = 0f
            view.setImageBitmap(bitmap)
            view.animate().alpha(1f).setDuration(FADE_MS).start()
        }
    }

    /** Puts the picture at [url] in [view] when it arrives, shrunk to about [widthPx] wide. */
    fun load(url: String?, view: ImageView, widthPx: Int) {
        init(view.context)
        view.tag = url
        val target = (view.getTag(R.id.image_target) as? ViewTarget) ?: ViewTarget(view).also { view.setTag(R.id.image_target, it) }
        if (url == null) {
            detach(target)
            target.imageKey = null
            view.setImageDrawable(null)
            return
        }
        val key = "$url|w$widthPx"
        start(target, key) { Job(key, url, widthPx, 0, Bitmap.Config.RGB_565, null, 0.5f) }
    }

    // ---- Any target, at an exact size ---------------------------------------------------------------------------

    /** How a picture fills its size: centre-cropped to exactly it, or kept whole inside it (a title's logo). */
    enum class Fit { CROP, INSIDE }

    /**
     * Puts the picture at [url] in [target], [widthPx] x [heightPx] exactly (centre-cropped; [cropY] moves the crop up
     * or down, 0.5 the middle), in [config]. [transform] runs on the image thread once, after the crop (the header's
     * fade); [keySuffix] names it in the memory key. A null [url] clears the target. With [Fit.INSIDE] the picture
     * keeps its shape and is at most that size (its key says so). [diskOnly]: only if its bytes are on disk already
     * (a preview while the real picture loads); otherwise nothing comes and nothing is fetched.
     */
    fun load(
        url: String?,
        target: ImageTarget,
        widthPx: Int,
        heightPx: Int,
        config: Bitmap.Config = Bitmap.Config.RGB_565,
        transform: ((Bitmap) -> Bitmap)? = null,
        keySuffix: String = "",
        cropY: Float = 0.5f,
        fit: Fit = Fit.CROP,
        diskOnly: Boolean = false,
    ) {
        (target as? View)?.let { init(it.context) }
        if (url.isNullOrEmpty() || widthPx <= 0 || heightPx <= 0) {
            release(target)
            return
        }
        val key = keyOf(url, widthPx, heightPx, keySuffix + if (fit == Fit.INSIDE) INSIDE_SUFFIX else "")
        start(target, key) { Job(key, url, widthPx, heightPx, config, transform, cropY, fit == Fit.INSIDE, diskOnly) }
    }

    /**
     * Forgets the decoded picture under [key] (a title page's 2 MB backdrop once the page is gone, so it doesn't push
     * posters out of memory). Views showing it keep it until they let it go. Main thread.
     */
    fun drop(key: String) {
        cache.remove(key)
    }

    /** The memory key of a picture loaded at an exact size. */
    fun keyOf(url: String, widthPx: Int, heightPx: Int, keySuffix: String = ""): String = "$url|${widthPx}x$heightPx$keySuffix"

    /** The picture for [key] if it's decoded already. Main thread. */
    fun cached(key: String): Bitmap? = cache.get(key)

    /**
     * Decodes the picture into memory without a target (the header's next title, so its switch never waits). Main
     * thread; nothing happens when it's decoded or on its way already.
     */
    fun warm(
        url: String?,
        widthPx: Int,
        heightPx: Int,
        config: Bitmap.Config = Bitmap.Config.RGB_565,
        transform: ((Bitmap) -> Bitmap)? = null,
        keySuffix: String = "",
        cropY: Float = 0.5f,
    ) {
        if (url.isNullOrEmpty() || widthPx <= 0 || heightPx <= 0) return
        val key = keyOf(url, widthPx, heightPx, keySuffix)
        if (cache.get(key) != null) return
        val existing = jobs[key]
        if (existing != null) {
            existing.warm = true
            existing.wanted = true
            return
        }
        if (!mayAsk(url)) return
        val job = Job(key, url, widthPx, heightPx, config, transform, cropY).apply { warm = true }
        jobs[key] = job
        pool.execute(job)
    }

    /** The target no longer shows a picture: its wait (if any) ends and it's cleared. Main thread. */
    fun release(target: ImageTarget) {
        detach(target)
        if (target.imageKey != null) {
            target.imageKey = null
            target.setImage(null, true)
        }
    }

    /**
     * As [release], and a picture still queued for nobody else is not fetched at all (a page that has left: its
     * pictures' downloads go, not just their decodes). One already being fetched finishes, to disk. Main thread.
     */
    fun cancel(target: ImageTarget) {
        val key = target.imageKey
        release(target)
        val job = key?.let { jobs[it] } ?: return
        if (job.waiters.isEmpty() && !job.warm && pool.remove(job)) jobs.remove(key)
    }

    /**
     * Downloads [url] to the disk cache ahead of need, without decoding it. Main thread or any; a bounded queue, the
     * newest first.
     */
    fun prefetch(url: String?) {
        if (url.isNullOrEmpty() || !mayAsk(url)) return
        val d = disk ?: return
        ahead.remove(url)
        ahead.offerFirst(url)
        while (ahead.size > AHEAD_MAX) ahead.pollLast()
        if (aheadThread == null) {
            synchronized(this) {
                if (aheadThread == null) {
                    aheadThread = Thread({
                        Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
                        while (true) {
                            val next = try { ahead.takeFirst() } catch (e: InterruptedException) { break }
                            try {
                                if (!d.has(next) && mayAsk(next)) download(next)?.let { d.write(next, it) }
                            } catch (e: Exception) {
                                // A prefetch that fails is simply not there yet.
                            }
                        }
                    }, "FirePlayerPrefetch").apply { isDaemon = true; start() }
                }
            }
        }
    }

    /**
     * A film starts, or the pages leave the screen: the posters queued ahead of need ([prefetch], up to 32) aren't
     * downloaded, and queued pictures nobody waits for any more aren't fetched, so they don't share the line with the
     * film's start. Pictures a target still waits for, and the header's next one, stay queued; a download under way
     * finishes. Main thread.
     */
    fun quiet() {
        ahead.clear()
        if (jobs.isEmpty()) return
        val it = jobs.values.iterator()
        while (it.hasNext()) {
            val job = it.next()
            if (job.waiters.isEmpty() && !job.warm && pool.remove(job)) it.remove()
        }
    }

    /**
     * Frees memory: decoded pictures down to [fraction] of the cache (0 empties it). Pictures on screen are held by
     * their views and stay. Main thread.
     */
    fun trim(fraction: Float = 0f) {
        if (fraction <= 0f) cache.evictAll() else cache.trimToSize((cache.maxSize() * fraction).toInt())
    }

    /**
     * Lets go of the decoded pictures kept in memory that [large] says are too big to keep (the pages' banners, cards
     * and posters while Live TV is in front; see [app.fireplayer.playback.LiveRoom]): how many went, and their bytes.
     * Pictures on screen are held by their views and stay. Main thread; not for every frame (it copies the list).
     */
    fun trimLarger(large: (Int) -> Boolean): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        for ((key, bitmap) in cache.snapshot()) {
            val size = bitmap.byteCount
            if (!large(size)) continue
            cache.remove(key)
            count++
            bytes += size
        }
        return count to bytes
    }

    const val TRIM_HALF = 0.5f
    /** How long a picture that wasn't in memory fades in (image views and cards alike). */
    const val FADE_MS = 160L
    private const val INSIDE_SUFFIX = "|in"

    // ---- Jobs ----------------------------------------------------------------------------------------------------

    private class Job(
        val key: String,
        val url: String,
        val w: Int,
        /** 0: width only (an image view crops it itself). */
        val h: Int,
        val config: Bitmap.Config,
        val transform: ((Bitmap) -> Bitmap)?,
        val cropY: Float,
        /** Kept whole inside w x h ([Fit.INSIDE]) rather than cropped to it. */
        val inside: Boolean = false,
        /** Only from disk: nothing is downloaded. */
        val diskOnly: Boolean = false,
    ) : Runnable {
        /** False once nobody waits: the bytes still go to disk, but nothing is decoded. */
        @Volatile var wanted = true
        /** Decoded for memory only (no target asked). */
        @Volatile var warm = false
        /** Main thread only. */
        val waiters = ArrayList<ImageTarget>(2)

        override fun run() {
            var result: Bitmap? = null
            var skipped = false
            try {
                val bytes = if (diskOnly) disk?.read(url) else bytesFor(url)
                if (bytes != null) {
                    if (!wanted) {
                        skipped = true
                    } else {
                        result = if (inside) decodeInside(bytes, w, h, config) else decode(bytes, w, h, config, cropY, mutable = transform != null)
                        if (result == null) {
                            block(url, FAILED_FOR_MS)
                        } else if (transform != null) {
                            result = transform.invoke(result)
                        }
                    }
                }
            } catch (e: OutOfMemoryError) {
                main.post { trim(TRIM_HALF) }
            } catch (e: Exception) {
                // Network or decode trouble: no picture this time (the address is never logged).
            }
            val bitmap = result
            main.post { finish(this, bitmap, skipped) }
        }
    }

    private inline fun start(target: ImageTarget, key: String, make: () -> Job) {
        val before = target.imageKey
        if (before != key) detach(target)
        target.imageKey = key
        cache.get(key)?.let {
            target.setImage(it, true)
            return
        }
        val running = jobs[key]
        if (running != null) {
            if (target !in running.waiters) running.waiters += target
            running.wanted = true
            if (before != key) target.setImage(null, true)
            return
        }
        if (before != key) target.setImage(null, true)
        val job = make()
        if (!mayAsk(job.url)) {
            // Failed lately: the next bind asks again once the wait is over.
            target.imageKey = null
            return
        }
        job.waiters += target
        jobs[key] = job
        pool.execute(job)
    }

    private fun detach(target: ImageTarget) {
        val key = target.imageKey ?: return
        val job = jobs[key] ?: return
        job.waiters.remove(target)
        if (job.waiters.isEmpty() && !job.warm) job.wanted = false
    }

    private fun finish(job: Job, bitmap: Bitmap?, skipped: Boolean) {
        if (jobs[job.key] === job) jobs.remove(job.key)
        val wanting = job.waiters.filter { it.imageKey == job.key }
        if (bitmap != null) {
            cache.put(job.key, bitmap)
            for (t in wanting) t.setImage(bitmap, false)
            return
        }
        // No picture this time: the targets keep their placeholder and the next bind asks again (a card's bind does
        // nothing while it holds the key).
        if (!skipped) for (t in wanting) t.imageKey = null
        // Not decoded because nobody waited at the time, yet someone asks again now: once more, from the disk.
        if (skipped && (wanting.isNotEmpty() || job.warm)) {
            val again = Job(job.key, job.url, job.w, job.h, job.config, job.transform, job.cropY, job.inside, job.diskOnly)
            again.warm = job.warm
            again.waiters += wanting
            jobs[job.key] = again
            pool.execute(again)
        }
    }

    private fun mayAsk(url: String): Boolean {
        val until = failed[url] ?: return true
        if (System.currentTimeMillis() >= until) {
            failed.remove(url, until)
            return true
        }
        return false
    }

    private fun block(url: String, forMs: Long) {
        failed[url] = System.currentTimeMillis() + forMs
    }

    /** The picture's bytes: from disk, else downloaded (and kept on disk). Image threads. */
    private fun bytesFor(url: String): ByteArray? {
        val d = disk
        d?.read(url)?.let { return it }
        val bytes = download(url) ?: return null
        d?.write(url, bytes)
        return bytes
    }

    private fun download(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "FirePlayer/2.0 (Linux; Android)")
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                // Missing or gone: not asked for again for a day. Anything else (a timeout, too many requests, server
                // trouble) may pass: half a minute.
                block(url, if (code == HttpURLConnection.HTTP_NOT_FOUND || code == HttpURLConnection.HTTP_GONE) FAILED_FOR_MS else RETRY_MS)
                return null
            }
            val type = conn.contentType
            if (type != null && type.startsWith("text/")) {
                block(url, FAILED_FOR_MS)
                return null
            }
            return conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_BYTES) return null
                }
                out.toByteArray()
            }
        } catch (e: java.io.IOException) {
            // No connection, or it timed out or broke off: asked again after a short wait.
            block(url, RETRY_MS)
            return null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Decodes [bytes]: with [h] 0, about [w] wide keeping its shape (an image view crops it); otherwise exactly
     * [w] x [h], the middle of the picture scaled to fill it ([cropY] moves the crop vertically). [mutable]: a transform
     * draws into it next (the backdrop's baked fade), so one decoded at exactly that size comes mutable (no copy).
     */
    private fun decode(bytes: ByteArray, w: Int, h: Int, config: Bitmap.Config, cropY: Float, mutable: Boolean = false): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sw = bounds.outWidth
        val sh = bounds.outHeight
        if (sw <= 0 || sh <= 0) return null
        if (h <= 0) {
            var sample = 1
            while (sw / (sample * 2) >= w) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            })
        }
        val sample = ImageFit.sampleSize(sw, sh, w, h)
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = if (config == Bitmap.Config.ARGB_8888) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
            inMutable = mutable
        }) ?: return null
        if (source.width == w && source.height == h && source.config == config) return source
        val out = Bitmap.createBitmap(w, h, config)
        val scale = max(w / source.width.toFloat(), h / source.height.toFloat())
        val dx = (w - source.width * scale) / 2f
        val dy = (h - source.height * scale) * cropY.coerceIn(0f, 1f)
        val m = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
        Canvas(out).drawBitmap(source, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        source.recycle()
        return out
    }

    /** Decodes [bytes] whole, its shape kept, scaled to fit inside [w] x [h] (never enlarged): a title's logo. */
    private fun decodeInside(bytes: ByteArray, w: Int, h: Int, config: Bitmap.Config): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sw = bounds.outWidth
        val sh = bounds.outHeight
        if (sw <= 0 || sh <= 0) return null
        val (ow, oh) = ImageFit.inside(sw, sh, w, h)
        var sample = 1
        while (sw / (sample * 2) >= ow && sh / (sample * 2) >= oh) sample *= 2
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = config
        }) ?: return null
        if (source.width == ow && source.height == oh && source.config == config) return source
        val out = Bitmap.createBitmap(ow, oh, config)
        val m = Matrix().apply { setScale(ow / source.width.toFloat(), oh / source.height.toFloat()) }
        Canvas(out).drawBitmap(source, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        source.recycle()
        return out
    }

    private const val MAX_BYTES = 4 shl 20
    private const val AHEAD_MAX = 32
    private const val FAILED_FOR_MS = 24L * 60 * 60 * 1000
    private const val RETRY_MS = 30_000L
}

/** How a picture is fitted to a card (pure, unit-tested). */
object ImageFit {
    /**
     * The largest power-of-two shrink while decoding that still leaves the part a [w] x [h] centre-crop uses at
     * least that size, for a [sw] x [sh] picture.
     */
    fun sampleSize(sw: Int, sh: Int, w: Int, h: Int): Int {
        if (sw <= 0 || sh <= 0 || w <= 0 || h <= 0) return 1
        val scale = max(w / sw.toFloat(), h / sh.toFloat())
        val cropW = w / scale
        val cropH = h / scale
        var sample = 1
        while (cropW / (sample * 2) >= w && cropH / (sample * 2) >= h) sample *= 2
        return sample
    }

    /** A [sw] x [sh] picture kept whole inside [w] x [h]: its size, its shape kept, never enlarged (at least 1 px). */
    fun inside(sw: Int, sh: Int, w: Int, h: Int): Pair<Int, Int> {
        if (sw <= 0 || sh <= 0) return 1 to 1
        val scale = minOf(1f, minOf(w / sw.toFloat(), h / sh.toFloat()))
        return (sw * scale).toInt().coerceAtLeast(1) to (sh * scale).toInt().coerceAtLeast(1)
    }
}
