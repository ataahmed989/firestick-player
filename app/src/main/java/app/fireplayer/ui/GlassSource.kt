package app.fireplayer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * What a piece of glass ([GlassDrawable]) shows through it: a small blurred picture lined up with the screen.
 * The player's is its film ([GlassBackdrop]); the app's own screens (guide, Home) show the stationary wallpaper
 * ([StillGlass]), blurred once and never copied again.
 */
interface GlassSource {
    /** The blurred picture to show now for [kind], or null for the dark fill. */
    fun frame(kind: GlassBackdrop.Kind): Bitmap?
    /** During a fade, what the new picture fades in over; null means the dark fill. */
    fun previous(kind: GlassBackdrop.Kind): Bitmap?
    /** How far the fade to the newest picture is, from 0 to 1 (done). */
    fun fadeFraction(): Float
    /** True while the glass holds still for D-pad presses. */
    val navigating: Boolean
    /** Screen pixels per pixel of the blurred picture, across and down. */
    val screenScale: Float
    val screenScaleY: Float
    /** Where the picture's top-left corner is on screen. */
    val originX: Int
    val originY: Int
    /** Glass to repaint when a new picture arrives. */
    fun register(drawable: GlassDrawable)
    /** Called by each glass shape as it draws where it can be seen ([GlassDrawable.isOnScreen]). */
    fun onGlassDrawn()

    /**
     * Called by a glass shape that drew where it can't be seen (faded out, or not shown yet as it fades in): nothing is
     * copied for it, but it's looked at again shortly in case it has come into view ([GlassBackdrop]).
     */
    fun onGlassHidden() {}

    /**
     * Called by a glass shape that drew part-way through a fade to a newer picture, where it can be seen: it has to be
     * drawn again as the fade goes on. As it stands, at once (the next frame).
     */
    fun onFadeDrawn(drawable: GlassDrawable) = drawable.invalidateSelf()

    /**
     * A window showing this picture appeared (it was made, became visible, or shows its backdrop again): a source that
     * copies what's behind on demand ([PageGlass]) copies it before the window is next drawn.
     */
    fun onGlassAppeared() {}

    /** An activity whose glass shows something other than a film (the app's own screens). */
    interface Host {
        val glassSource: GlassSource?

        /**
         * What windows over this screen's page show through their glass ([Glass.overlay]): the page itself, blurred
         * ([PageGlass]); null where there's none (their glass is then dark).
         */
        val pageGlass: GlassSource? get() = null

        /**
         * Its glass shows a live picture just now (Home's full-screen live controls): glass made meanwhile is dimmed
         * by the screen dimmer preset's glass dimness, as the VOD player's is ([Glass.inPlayer]).
         */
        val glassShowsFilm: Boolean get() = false
    }
}

/**
 * Glass over the stationary wallpaper: the wallpaper is blurred once, at a small size, on a background thread by
 * the caller, then every glass shape draws from that one cached picture. No copies, no fades, no timers: glass over
 * a still background costs nothing per frame beyond drawing the shape.
 *
 * [panel] is the soft, dim picture for windows and [control] the brighter one for buttons; both cover the whole
 * screen at [scale] screen pixels per picture pixel.
 */
class StillGlass(private val panel: Bitmap, private val control: Bitmap, scale: Float) : GlassSource {
    override val navigating = false
    override val screenScale = scale
    override val screenScaleY = scale
    override val originX = 0
    override val originY = 0

    override fun frame(kind: GlassBackdrop.Kind): Bitmap = if (kind == GlassBackdrop.Kind.PANEL) panel else control
    override fun previous(kind: GlassBackdrop.Kind): Bitmap? = null
    override fun fadeFraction() = 1f
    override fun register(drawable: GlassDrawable) {}
    override fun onGlassDrawn() {}

    companion object {
        /**
         * Builds the two blurred pictures from [wallpaper] (already drawn at screen size [width] x [height]).
         * Runs on a background thread: the blur is a few box passes over a picture an eighth of the screen.
         */
        fun from(wallpaper: Bitmap, width: Int, height: Int, density: Float): StillGlass {
            val scale = 8f
            val w = (width / scale).toInt().coerceAtLeast(8)
            val h = (height / scale).toInt().coerceAtLeast(8)
            val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(small).drawBitmap(wallpaper, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
            val panelRadius = (20f * density / scale).toInt().coerceAtLeast(1)
            val controlRadius = (11f * density / scale).toInt().coerceAtLeast(1)
            val panel = blurred(small, panelRadius, 0.78f)
            val control = blurred(small, controlRadius, 1.0f)
            small.recycle()
            return StillGlass(panel, control, scale)
        }

        /** Three box-blur passes (close to a Gaussian), then scaled by [light]. */
        internal fun blurred(src: Bitmap, radius: Int, light: Float): Bitmap {
            val w = src.width
            val h = src.height
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            val out = boxBlur3(px, w, h, radius, light)
            return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        }

        /** Pure pixel math (unit-tested): three separable box passes, then each channel times [light]. */
        internal fun boxBlur3(src: IntArray, w: Int, h: Int, radius: Int, light: Float): IntArray {
            val a = src.copyOf()
            boxBlur3InPlace(a, IntArray(a.size), w, h, radius)
            if (light != 1f) {
                for (i in a.indices) {
                    val c = a[i]
                    val r = (((c shr 16) and 0xFF) * light).toInt().coerceIn(0, 255)
                    val g = (((c shr 8) and 0xFF) * light).toInt().coerceIn(0, 255)
                    val bl = ((c and 0xFF) * light).toInt().coerceIn(0, 255)
                    a[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                }
            }
            return a
        }

        /**
         * The three box passes with the caller's own arrays, so nothing is made: [a] ([w] x [h] pixels) is the picture
         * and holds the result, [b] (as long) is working room. The result is opaque.
         */
        internal fun boxBlur3InPlace(a: IntArray, b: IntArray, w: Int, h: Int, radius: Int) {
            repeat(3) {
                boxPass(a, b, w, h, radius, horizontal = true)
                boxPass(b, a, w, h, radius, horizontal = false)
            }
        }

        private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val lines = if (horizontal) h else w
            val len = if (horizontal) w else h
            val window = 2 * r + 1
            // Dividing by the window as one multiplication and a shift: exact for sums of this size (255 x window),
            // and several times quicker than three divisions a pixel on the Stick.
            val recip = ((1L shl 24) + window - 1) / window
            for (line in 0 until lines) {
                var sr = 0; var sg = 0; var sb = 0
                fun at(i: Int): Int {
                    val k = i.coerceIn(0, len - 1)
                    return if (horizontal) src[line * w + k] else src[k * w + line]
                }
                for (i in -r..r) {
                    val c = at(i)
                    sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF
                }
                for (i in 0 until len) {
                    val idx = if (horizontal) line * w + i else i * w + line
                    dst[idx] = (0xFF shl 24) or (((sr * recip) ushr 24).toInt() shl 16) or
                        (((sg * recip) ushr 24).toInt() shl 8) or ((sb * recip) ushr 24).toInt()
                    val add = at(i + r + 1)
                    val drop = at(i - r)
                    sr += ((add shr 16) and 0xFF) - ((drop shr 16) and 0xFF)
                    sg += ((add shr 8) and 0xFF) - ((drop shr 8) and 0xFF)
                    sb += (add and 0xFF) - (drop and 0xFF)
                }
            }
        }
    }
}
