package app.fireplayer.ui

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.PixelCopy
import android.view.SurfaceView
import app.fireplayer.player.BlackBars

/**
 * Looks at the film now and then while Fill screen or Stretch is chosen, to find black bars that are part of its file
 * ([BlackBars]). A small copy of the picture about once a second until that's known, then every few seconds in case
 * the film changes shape; none at all in the other display sizes, while paused, or once it's sure there are no bars.
 * Once the bars are known, half as often while a 4K film plays on a TV short of memory ([heavyVideo]), as for the glass
 * ([GlassLoad]); while they're still looked for it's as quick as ever, so Fill screen and Stretch settle as fast.
 * Android 7 and later (PixelCopy); older TVs keep the film's own shape.
 */
class BarWatch(
    private val surfaceView: SurfaceView,
    private val playing: () -> Boolean,
    private val onChanged: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val bars = BlackBars()
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var generation = 0
    private var released = false
    // Looks asked for this film: with nothing found after [PATIENCE] of them, they come as seldom as with bars found.
    private var looks = 0
    // On the worker only.
    private var bitmap: Bitmap? = null
    private var pixels: IntArray? = null
    @Volatile private var busy = false

    val vertical: Float get() = bars.vertical
    val horizontal: Float get() = bars.horizontal

    /** The film playing is 4K on a TV short of memory ([GlassLoad.heavy]): once the bars are known, looks come half as often. */
    var heavyVideo = false

    /** Fill screen or Stretch is chosen and a film is showing. */
    var wanted = false
        set(value) {
            if (field == value) return
            field = value
            main.removeCallbacks(tick)
            if (value && !released) main.postDelayed(tick, SOON_MS)
        }

    private val tick = Runnable { look() }

    /** Another film: what was found goes. The caller lays the picture out again. */
    fun reset() {
        generation++
        looks = 0
        bars.reset()
        main.removeCallbacks(tick)
        if (wanted && !released) main.postDelayed(tick, SOON_MS)
    }

    fun release() {
        released = true
        main.removeCallbacks(tick)
        thread?.quitSafely()
        thread = null
        worker = null
    }

    private fun look() {
        if (!wanted || released || bars.settled || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        main.postDelayed(tick, gapMs(known = bars.found || looks > PATIENCE, heavy = heavyVideo))
        if (busy || !playing() || !surfaceView.isAttachedToWindow) return
        val handler = worker ?: run {
            val t = HandlerThread("bars", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
            thread = t
            Handler(t.looper).also { worker = it }
        }
        val gen = generation
        looks++
        busy = true
        handler.post { copy(gen, handler) }
    }

    /** On the worker: PixelCopy waits on the TV's render thread, so never from the main one. */
    private fun copy(gen: Int, handler: Handler) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val surface = surfaceView.holder.surface
        if (surface == null || !surface.isValid) {
            busy = false
            return
        }
        try {
            val dest = bitmap ?: Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).also { bitmap = it }
            PixelCopy.request(surfaceView, dest, { result ->
                try {
                    if (result == PixelCopy.SUCCESS) {
                        val into = pixels ?: IntArray(WIDTH * HEIGHT).also { pixels = it }
                        dest.getPixels(into, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                        val seen = BlackBars.measure(into, WIDTH, HEIGHT)
                        main.post { took(gen, seen) }
                    }
                } finally {
                    busy = false
                }
            }, handler)
        } catch (e: RuntimeException) {
            // The surface went away between the check and the copy.
            busy = false
        } catch (e: OutOfMemoryError) {
            busy = false
        }
    }

    private fun took(gen: Int, seen: BlackBars.Look) {
        if (gen != generation || released || !wanted) return
        if (bars.see(seen)) onChanged()
    }

    companion object {
        private const val WIDTH = 160
        private const val HEIGHT = 180
        private const val SOON_MS = 1_000L
        private const val KEEP_MS = 4_000L
        private const val PATIENCE = 90
        /** How many times longer the gaps are with heavy video, once the bars are known. */
        private const val HEAVY_GAP_FACTOR = 2

        /**
         * The wait before the next look: about a second while the bars are looked for (whatever the video: the display
         * size settles as quickly), a few seconds once they're [known] (found, or given up on), twice that with [heavy]
         * video.
         */
        fun gapMs(known: Boolean, heavy: Boolean): Long =
            if (known) KEEP_MS * (if (heavy) HEAVY_GAP_FACTOR else 1) else SOON_MS
    }
}
