package app.fireplayer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.ViewParent
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import java.util.WeakHashMap

/**
 * What a window over the page shows through its glass ([Glass.overlay]): one small blurred picture of what is really
 * behind it, shared by every such window. The page's own layers ([layers]: the wallpaper and the pages, never the
 * windows or the rail above them) are drawn into a picture a tenth of the screen's size, which is blurred once and
 * kept; each window then draws its part of it ([GlassDrawable]) under the style's tint ([GlassStyle]).
 *
 * It works only for glass on screen, and only when there is something new to show:
 *  - a window appearing ([onGlassAppeared]) is copied for just before it's first drawn, so it opens as true glass;
 *  - while one shows, a change under it ([contentChanged], told by the [GlassLayer]s) is copied once it has settled,
 *    a couple of times a second at most ([GlassStyle.nextCaptureAt]), and fades in;
 *  - with no window showing, or nothing changing under one, nothing is drawn, copied or blurred, and no timer runs.
 *
 * A window that lies inside the page (a hold window) leaves itself out of the picture ([capturing]), so glass never
 * blurs itself. Everything here runs on the main thread: a copy is one small software drawing and a blur of some
 * twenty thousand pixels.
 */
class PageGlass(
    private val root: View,
    private val layers: List<View>,
    /** The Glass refresh setting, copies a second. */
    private val refreshPerSec: () -> Int,
) : GlassSource {

    private val handler = Handler(Looper.getMainLooper())
    private val density = root.resources.displayMetrics.density
    private val drawables = WeakHashMap<GlassDrawable, Boolean>()
    private val location = IntArray(2)

    /** The blurred picture windows show, and the one it fades in over. */
    private var shown: Bitmap? = null
    private var before: Bitmap? = null
    /** What the layers are drawn into, before the blur. */
    private var scratch: Bitmap? = null
    private var scratchCanvas: Canvas? = null
    private var pixels = IntArray(0)
    private var work = IntArray(0)

    private var fadeStart = 0L
    private var capturedAt = 0L
    /** A window may be on screen: changes under it count. */
    private var active = false
    /** A copy is owed before the next drawing (a window appeared). */
    private var wanted = false
    /** The page changed while no window showed: the picture kept is out of date. */
    private var stale = false
    private var armed = false
    private var pending = false
    private var running = true
    /** The page couldn't be drawn into a picture: windows keep their dark fill. */
    private var failed = false
    /** No change under the windows is copied before this uptime ([holdUntil]). */
    private var heldUntil = 0L

    override val navigating = false
    override var screenScale = GlassStyle.BACKDROP_SCALE.toFloat()
        private set
    override var screenScaleY = GlassStyle.BACKDROP_SCALE.toFloat()
        private set
    override var originX = 0
        private set
    override var originY = 0
        private set

    override fun frame(kind: GlassBackdrop.Kind): Bitmap? = shown

    override fun previous(kind: GlassBackdrop.Kind): Bitmap? = if (fadeFraction() < 1f) before else null

    override fun fadeFraction(): Float {
        if (fadeStart == 0L || before == null) return 1f
        val f = (SystemClock.uptimeMillis() - fadeStart) / GlassStyle.BACKDROP_FADE_MS.toFloat()
        if (f < 1f) return f.coerceAtLeast(0f)
        // The fade is over: the older picture goes.
        fadeStart = 0L
        before = null
        return 1f
    }

    override fun register(drawable: GlassDrawable) {
        drawables[drawable] = true
    }

    /** A window over the page appeared (or shows its backdrop again): copied before it's next drawn. */
    override fun onGlassAppeared() {
        if (!running) return
        active = true
        wanted = true
        arm()
    }

    override fun onGlassDrawn() {
        active = true
        // Drawn with nothing new to show (its copy was dropped, or it wasn't on screen when one was due): owed now.
        if ((wanted || stale || shown == null) && !failed && !armed && running) {
            wanted = true
            arm()
            redraw()
        }
    }

    /**
     * What's under the windows changed: [target], something in a [GlassLayer], is to be drawn again (null: something
     * there). One check while no window shows. A window that lies inside the page (a hold window) redrawing itself, or
     * something it holds, isn't a change under it: it's left out of the picture anyway.
     */
    fun contentChanged(target: View? = null) {
        if (!active) {
            stale = true
            return
        }
        if (capturing || pending || !running) return
        if (target != null && inWindow(target)) return
        pending = true
        val now = SystemClock.uptimeMillis()
        handler.postAtTime(refresh, GlassStyle.nextCaptureAt(now, capturedAt, refreshPerSec()))
    }

    /** The screen is shown again ([stop] undone). */
    fun start() {
        running = true
    }

    /** The screen is covered or gone: nothing more is copied, and nothing is left waiting. */
    fun stop() {
        running = false
        handler.removeCallbacks(refresh)
        pending = false
        active = false
        stale = true
        disarm()
    }

    /** [trim], unless a window may be on screen (its picture stays): true when the pictures went. */
    fun trimIfIdle(): Boolean {
        if (active) return false
        trim()
        return true
    }

    /** Lets the pictures go (film mode, low memory); the next window is copied afresh. */
    fun trim() {
        shown = null
        before = null
        scratch = null
        scratchCanvas = null
        pixels = IntArray(0)
        work = IntArray(0)
        fadeStart = 0L
        for (d in drawables.keys) d.forgetFilm()
    }

    /**
     * Glass showing this picture is in motion until [uptimeMs] (the tab rail widening): a change under it isn't copied
     * before then, so no copy lands in the middle of the motion; it is copied once that time has come. A window
     * appearing is still copied for at once ([onGlassAppeared]).
     */
    fun holdUntil(uptimeMs: Long) {
        if (uptimeMs > heldUntil) heldUntil = uptimeMs
    }

    private val refresh: Runnable = Runnable {
        pending = false
        if (!running) return@Runnable
        if (SystemClock.uptimeMillis() < heldUntil) {
            // Glass over the page is in motion: looked at again once that has ended.
            pending = true
            handler.postAtTime(refresh, heldUntil)
            return@Runnable
        }
        if (!anySeen()) {
            // The last window went: what changes under nothing isn't copied, only remembered as changed.
            active = false
            stale = true
            return@Runnable
        }
        if (capture(fade = true)) redraw()
    }

    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        disarm()
        if (wanted && running && anyShown()) {
            wanted = false
            // A window just opened: it starts on what's behind it now, not fading from what the last one showed.
            capture(fade = false)
        }
        true
    }

    private fun arm() {
        if (armed) return
        val observer = root.viewTreeObserver
        if (!observer.isAlive) return
        armed = true
        observer.addOnPreDrawListener(preDraw)
    }

    private fun disarm() {
        if (!armed) return
        armed = false
        val observer = root.viewTreeObserver
        if (observer.isAlive) observer.removeOnPreDrawListener(preDraw)
    }

    /** Whether a window showing the backdrop is shown (it may still be fading in). */
    private fun anyShown(): Boolean {
        for (d in drawables.keys) if (d.wantsBackdrop()) return true
        return false
    }

    /** Whether one can be seen just now. */
    private fun anySeen(): Boolean {
        for (d in drawables.keys) if (d.wantsBackdrop() && d.isOnScreen()) return true
        return false
    }

    /** Whether [view] is a window showing the backdrop, or inside one. */
    private fun inWindow(view: View): Boolean {
        for (d in drawables.keys) {
            if (!d.wantsBackdrop()) continue
            val window = d.hostView() ?: continue
            var v: View? = view
            while (v != null) {
                if (v === window) return true
                v = v.parent as? View
            }
        }
        return false
    }

    private fun redraw() {
        for (d in drawables.keys) if (d.wantsBackdrop()) d.invalidateSelf()
    }

    /** Draws the layers small, blurs them and shows the result; false when it couldn't. */
    private fun capture(fade: Boolean): Boolean {
        val rw = root.width
        val rh = root.height
        if (rw <= 0 || rh <= 0 || failed) return false
        val w = GlassStyle.backdropSize(rw)
        val h = GlassStyle.backdropSize(rh)
        var target = scratch
        if (target == null || target.width != w || target.height != h) {
            trim()
            target = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            scratch = target
            scratchCanvas = Canvas(target)
            pixels = IntArray(w * h)
            work = IntArray(w * h)
        }
        val canvas = scratchCanvas ?: return false
        val sx = w / rw.toFloat()
        val sy = h / rh.toFloat()
        target.eraseColor(Color.BLACK)
        val count = canvas.save()
        capturing = true
        try {
            canvas.scale(sx, sy)
            for (layer in layers) {
                if (layer.visibility != View.VISIBLE || layer.width <= 0 || layer.height <= 0) continue
                val inner = canvas.save()
                canvas.translate(offsetX(layer), offsetY(layer))
                layer.draw(canvas)
                canvas.restoreToCount(inner)
            }
        } catch (e: RuntimeException) {
            // Something in the page can't be drawn into a picture (one kept on the graphics chip, say): the windows
            // keep their dark fill from here on, as they had before the first copy.
            failed = true
            shown = null
            before = null
            return false
        } finally {
            capturing = false
            canvas.restoreToCount(count)
        }
        target.getPixels(pixels, 0, w, 0, 0, w, h)
        StillGlass.boxBlur3InPlace(pixels, work, w, h, GlassStyle.blurRadius(density, 1f / sx))
        // A new small picture each time (some 80 KB): one a window has drawn is never written to again.
        val next = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val old = shown
        if (fade && old != null) {
            before = old
            fadeStart = SystemClock.uptimeMillis()
        } else {
            before = null
            fadeStart = 0L
        }
        shown = next
        stale = false
        screenScale = 1f / sx
        screenScaleY = 1f / sy
        root.getLocationOnScreen(location)
        originX = location[0]
        originY = location[1]
        capturedAt = SystemClock.uptimeMillis()
        return true
    }

    /** Where [layer] is inside [root], px (it, or a parent on the way, may be moved by an animation). */
    private fun offsetX(layer: View): Float {
        var x = 0f
        var v: View? = layer
        while (v != null && v !== root) {
            x += v.left + v.translationX
            v = v.parent as? View
        }
        return x
    }

    private fun offsetY(layer: View): Float {
        var y = 0f
        var v: View? = layer
        while (v != null && v !== root) {
            y += v.top + v.translationY
            v = v.parent as? View
        }
        return y
    }

    companion object {
        /**
         * True while the page is being drawn into the backdrop's picture (main thread): a window over the page draws
         * nothing of itself then ([GlassDrawable]), and changes made by that drawing don't count as the page changing.
         */
        @JvmField var capturing = false
    }
}

/**
 * A layer of the screen that lies under the glass windows (the wallpaper's, the pages'): it tells [onContentChanged]
 * whenever something in it is to be drawn again, which is how the windows' backdrop knows the page changed
 * ([PageGlass.contentChanged]). The call is one check while no window shows.
 */
class GlassLayer @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    /** Called with what is to be drawn again, when that's known. */
    var onContentChanged: ((target: View?) -> Unit)? = null

    /** Android 8 and later: every redrawing and every move, fade or resize of something inside comes through here. */
    override fun onDescendantInvalidated(child: View, target: View) {
        super.onDescendantInvalidated(child, target)
        onContentChanged?.invoke(target)
    }

    /** Before Android 8. */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun invalidateChildInParent(location: IntArray?, dirty: Rect?): ViewParent? {
        onContentChanged?.invoke(null)
        return super.invalidateChildInParent(location, dirty)
    }
}
