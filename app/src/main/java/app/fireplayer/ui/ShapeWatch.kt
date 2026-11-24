package app.fireplayer.ui

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.PixelCopy
import android.view.SurfaceView
import app.fireplayer.player.AspectSample
import app.fireplayer.player.AspectSamples
import app.fireplayer.player.BlackBars

/**
 * Looks at a film's picture shape a few times in its first minutes ([AspectSamples]): from the decoded frame's size,
 * or, for a 16:9 frame, from a tiny copy of the picture (PixelCopy of the film's SurfaceView, [AspectSamples.COPY_WIDTH]
 * x [AspectSamples.COPY_HEIGHT], on a thread of its own, which goes once the looks are done). Eight looks at most per
 * copy, never one a frame. Android 7 and later; none where the picture can't be copied (tunnelled or protected video).
 */
class ShapeWatch(private val surfaceView: SurfaceView) {
    private val main = Handler(Looper.getMainLooper())
    private val plan = AspectSamples()
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var generation = 0
    // A copy is being taken (main thread).
    private var busy = false
    // On the worker only.
    private var bitmap: Bitmap? = null
    private var pixels: IntArray? = null

    /** What the looks at the copy playing found so far. */
    val samples: List<AspectSample> get() = plan.samples

    /** Called as the film plays, [now] elapsedRealtime: whether a look is due ([look] then). */
    fun due(now: Long, playing: Boolean): Boolean = !busy && plan.due(now, playing)

    /**
     * The look due, at [atMs] in the film, of a decoded frame [width] x [height] ([pixelRatio]); [copyable]: its
     * picture may be copied (not tunnelled, not protected). Waits while the frame's size isn't known.
     */
    fun look(width: Int, height: Int, pixelRatio: Float, atMs: Long, copyable: Boolean) {
        val frame = AspectSamples.frameRatio(width, height, pixelRatio)
        if (frame <= 0.0) return
        if (!AspectSamples.needsCopy(frame)) {
            took(generation, frame, null, atMs, fromFrame = true)
            return
        }
        if (!copyable || Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !surfaceView.isAttachedToWindow) {
            took(generation, frame, null, atMs, fromFrame = false)
            return
        }
        val handler = worker ?: run {
            val t = HandlerThread("shape", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
            thread = t
            Handler(t.looper).also { worker = it }
        }
        val gen = generation
        busy = true
        handler.post { copy(gen, frame, atMs, handler) }
    }

    /** Another copy of the film: looked at afresh. */
    fun reset() {
        generation++
        busy = false
        plan.reset()
    }

    fun release() {
        generation++
        busy = false
        stopWorker()
    }

    /** On the worker: PixelCopy waits on the TV's render thread, so never from the main one. */
    private fun copy(gen: Int, frame: Double, atMs: Long, handler: Handler) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val surface = surfaceView.holder.surface
        if (surface == null || !surface.isValid) {
            main.post { took(gen, frame, null, atMs, fromFrame = false) }
            return
        }
        try {
            val w = AspectSamples.COPY_WIDTH
            val h = AspectSamples.COPY_HEIGHT
            val dest = bitmap ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap = it }
            PixelCopy.request(surfaceView, dest, { result ->
                var seen: BlackBars.Look? = null
                if (result == PixelCopy.SUCCESS) {
                    val into = pixels ?: IntArray(w * h).also { pixels = it }
                    dest.getPixels(into, 0, w, 0, 0, w, h)
                    seen = BlackBars.measure(into, w, h)
                }
                main.post { took(gen, frame, seen, atMs, fromFrame = false) }
            }, handler)
        } catch (e: RuntimeException) {
            // The surface went away between the check and the copy.
            main.post { took(gen, frame, null, atMs, fromFrame = false) }
        } catch (e: OutOfMemoryError) {
            main.post { took(gen, frame, null, atMs, fromFrame = false) }
        }
    }

    private fun took(gen: Int, frame: Double, seen: BlackBars.Look?, atMs: Long, fromFrame: Boolean) {
        if (gen != generation) return
        busy = false
        plan.took(if (fromFrame) frame else seen?.let { AspectSamples.ratioOf(it, frame) }, atMs)
        if (plan.done) stopWorker()
    }

    private fun stopWorker() {
        val w = worker ?: return
        // What the copies needed goes with the thread.
        w.post {
            bitmap = null
            pixels = null
        }
        thread?.quitSafely()
        thread = null
        worker = null
    }
}
