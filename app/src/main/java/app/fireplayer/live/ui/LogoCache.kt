package app.fireplayer.live.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import app.fireplayer.ui.ImageDiskCache
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Channel logos for the guide and live controls: fetched and decoded on background threads, downsampled to the
 * size they're drawn at, kept in a bounded memory cache (6 MB). Each one's plate ([LogoPlate]) is chosen there too,
 * once, never while drawing. A logo that fails isn't asked for again this run. [get] never blocks: it returns what's
 * cached and starts a fetch; [onChange] runs on the main thread when one lands (loaded) or fails.
 *
 * A logo once fetched is also kept on disk as it was decoded ([useDisk], [LogoStore]), so the next time it's wanted
 * (the guide scrolled back, the app opened again) it comes from there in a few milliseconds instead of from its
 * server: every logo used to be downloaded again each time it left the memory cache.
 */
class LogoCache(private val targetPx: Int, var onChange: ((url: String, loaded: Boolean) -> Unit)? = null) {

    private val cache = object : LruCache<String, Logo>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Logo) = value.bitmap.byteCount
    }
    private val pending = HashSet<String>()
    private val failed = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())

    /** Logos still on their way. Main thread only. */
    val pendingCount: Int get() = pending.size

    /** How many logos came from the disk, from their servers, and from nowhere, since this cache was made (for the log). */
    var fromDisk = 0
        private set
    var fromNetwork = 0
        private set
    var failedCount = 0
        private set

    /** Main thread only. */
    fun get(url: String): Logo? {
        cache.get(url)?.let { return it }
        if (url in pending || url in failed) return null
        if (pending.size > 24) return null // a burst of new rows: the rest wait for the next draw
        pending += url
        val store = disk
        if (store == null || !url.startsWith("http")) {
            pool.execute { landed(url, runCatching { fetch(url, null) }.getOrNull(), kept = false) }
            return null
        }
        // The disk first, on its own thread (a server that doesn't answer mustn't hold up the logos already here).
        diskThread.execute {
            val key = LogoStore.key(url, targetPx)
            val bytes = store.read(key)
            val now = System.currentTimeMillis()
            val fresh = bytes?.takeIf { LogoStore.isFresh(it, now) }?.let { decodeKept(it) }
            if (fresh != null) {
                landed(url, fresh, kept = true)
                return@execute
            }
            pool.execute {
                val fetched = runCatching { fetch(url) { png -> store.write(key, LogoStore.pack(System.currentTimeMillis(), png)) } }.getOrNull()
                // Its server didn't give it this time: the copy kept, however old, rather than nothing.
                val old = if (fetched == null && bytes != null) decodeKept(bytes) else null
                landed(url, fetched ?: old, kept = fetched == null && old != null)
            }
        }
        return null
    }

    private fun landed(url: String, logo: Logo?, kept: Boolean) {
        main.post {
            pending -= url
            if (logo != null) {
                cache.put(url, logo)
                if (kept) fromDisk++ else fromNetwork++
            } else {
                failed += url
                failedCount++
            }
            onChange?.invoke(url, logo != null)
        }
    }

    /** True when [url] gives no logo (it failed this run, or isn't a web address). Main thread only. */
    fun missing(url: String): Boolean = url in failed || !url.startsWith("http")

    fun clear() {
        cache.evictAll()
        failed.clear()
    }

    /** A logo as kept on disk ([LogoStore]): already at the size it's drawn at, so only decoded. */
    private fun decodeKept(bytes: ByteArray): Logo? {
        val at = LogoStore.pictureAt(bytes) ?: return null
        val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, at, bytes.size - at) }.getOrNull() ?: return null
        return logoOf(bmp)
    }

    /** [keep], when given, is handed the logo as decoded (a PNG), to keep on disk. */
    private fun fetch(url: String, keep: ((ByteArray) -> Unit)? = null): Logo? {
        if (!url.startsWith("http")) return null
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("User-Agent", "FirePlayer/2.0 (Linux; Android)")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            // A logo deleted from imgur answers with a picture of the words "The image you are requesting does not
            // exist..." (it sends the request on to removed.png, 161 x 81): that's no logo.
            val imgurGone = conn.url.host.orEmpty().endsWith("imgur.com")
            if (imgurGone && conn.url.path.orEmpty().endsWith("/removed.png")) return null
            val bytes = conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            if (imgurGone && bounds.outWidth == 161 && bounds.outHeight == 81) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx / 2) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            if (keep != null) {
                // Kept as decoded (lossless), not as downloaded: small on disk, and quick to show again.
                runCatching {
                    val png = ByteArrayOutputStream(16 * 1024)
                    if (bmp.compress(Bitmap.CompressFormat.PNG, 100, png)) keep(png.toByteArray())
                }
            }
            return logoOf(bmp)
        } finally {
            conn.disconnect()
        }
    }

    /** The logo with its plate in each look ([LogoPlate.light]), from a grid of at most about 64 x 64 of its pixels. */
    private fun logoOf(bmp: Bitmap): Logo {
        if (bmp.width <= 0 || bmp.height <= 0) return Logo(bmp, false)
        val step = maxOf(1, maxOf(bmp.width, bmp.height) / 64)
        val cols = (bmp.width + step - 1) / step
        val rows = (bmp.height + step - 1) / step
        val px = IntArray(cols * rows)
        val line = IntArray(bmp.width)
        var n = 0
        for (r in 0 until rows) {
            bmp.getPixels(line, 0, bmp.width, 0, r * step, bmp.width, 1)
            var x = 0
            while (x < bmp.width) { px[n++] = line[x]; x += step }
        }
        return Logo(bmp, LogoPlate.light(px, n), LogoPlate.light(px, n, LogoPlate.DARK_INK_GLASS))
    }

    companion object {
        private const val MAX_BYTES = 2 * 1024 * 1024
        /** Logos on disk: a decoded logo is a few KB, so this is every logo of a big playlist's most used groups. */
        private const val DISK_BYTES = 24L shl 20
        private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "FirePlayerLogos").apply { priority = Thread.MIN_PRIORITY } }
        private val diskThread = Executors.newSingleThreadExecutor { r -> Thread(r, "FirePlayerLogoDisk").apply { priority = Thread.MIN_PRIORITY } }

        @Volatile private var disk: ImageDiskCache? = null

        /**
         * Logos are kept on disk from now on, in the app's cache folder (Fire OS may empty it; they are fetched again
         * then). For every logo cache of the app; safe to call again.
         */
        fun useDisk(context: Context) {
            if (disk != null) return
            synchronized(this) {
                if (disk == null) disk = ImageDiskCache(File(context.applicationContext.cacheDir, "logos"), DISK_BYTES)
            }
        }
    }
}

/**
 * How a logo is kept on disk (pure, unit-tested): the time it was fetched, then the picture as decoded (a PNG). Kept
 * per address and size drawn; after [MAX_AGE_MS] it's fetched again (a provider may put a new logo at the same
 * address), the old copy standing in if its server doesn't answer.
 */
object LogoStore {
    const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000
    private const val HEAD = 8

    /** What a logo is kept under: its address and the size it's decoded for (the guide's rows and a search row differ). */
    fun key(url: String, targetPx: Int): String = "$targetPx|$url"

    /** [picture] with the time it was fetched in front. */
    fun pack(fetchedAt: Long, picture: ByteArray): ByteArray {
        val out = ByteArray(HEAD + picture.size)
        for (i in 0 until HEAD) out[i] = (fetchedAt ushr (8 * (HEAD - 1 - i))).toByte()
        System.arraycopy(picture, 0, out, HEAD, picture.size)
        return out
    }

    /** When [kept] was fetched; null when it isn't a kept logo (too short to hold a picture). */
    fun fetchedAt(kept: ByteArray): Long? {
        if (kept.size <= HEAD) return null
        var t = 0L
        for (i in 0 until HEAD) t = (t shl 8) or (kept[i].toLong() and 0xFF)
        return t
    }

    /** Where the picture starts in [kept]; null when there's none. */
    fun pictureAt(kept: ByteArray): Int? = if (kept.size > HEAD) HEAD else null

    /** Whether [kept] is still to be shown without asking its server: fetched within [MAX_AGE_MS] (and not in the future). */
    fun isFresh(kept: ByteArray, now: Long): Boolean {
        val at = fetchedAt(kept) ?: return false
        return at <= now && now - at < MAX_AGE_MS
    }
}
