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
        main.postDelayed(tick, if (bars.found || looks > PATIENCE) KEEP_MS else SOON_MS)
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

    private companion object {
        const val WIDTH = 160
        const val HEIGHT = 180
        const val SOON_MS = 1_000L
        const val KEEP_MS = 4_000L
        const val PATIENCE = 90
    }
}
