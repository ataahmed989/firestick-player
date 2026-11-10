package app.fireplayer.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Loads small pictures (episode stills, posters) into image views, off the main thread and below the film's own
 * threads, shrunk to about the size they're shown at and kept in memory for a while. A view reused for another picture
 * meanwhile doesn't get the old one.
 */
object ImageLoader {

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2) { r ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "FirePlayerImages").apply { isDaemon = true }
    }
    private val cache = object : LruCache<String, Bitmap>(12 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Puts the picture at [url] in [view] when it arrives, shrunk to about [widthPx] wide. */
    fun load(url: String?, view: ImageView, widthPx: Int) {
        view.tag = url
        if (url == null) {
            view.setImageDrawable(null)
            return
        }
        cache.get(url)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        pool.execute {
            val bitmap = runCatching { fetch(url, widthPx) }.getOrNull() ?: return@execute
            cache.put(url, bitmap)
            main.post {
                if (view.tag == url) {
                    view.alpha = 0f
                    view.setImageBitmap(bitmap)
                    view.animate().alpha(1f).setDuration(160).start()
                }
            }
        }
    }

    private fun fetch(url: String, widthPx: Int): Bitmap? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "FirePlayer/1.0 (Linux; Android)")
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = conn.inputStream.use { input ->
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
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } finally {
            conn.disconnect()
        }
    }

    private const val MAX_BYTES = 4 shl 20
}
