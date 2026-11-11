package app.fireplayer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LightingColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.util.LruCache
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One piece of glass (Glass look): the blurred film behind it ([GlassBackdrop]), a milky glaze, a soft inner
 * bevel, a bright rim and a shadow. Selected controls and rows turn glossy white with the blue ring and glow.
 *
 * Everything but the film is drawn once, in software, into a cached bitmap ([GlassBakes]): the hardware canvas
 * of older Fire OS can't blur or cut shapes. Each frame is then one rounded rectangle of film and one bitmap
 * (two of each while fading), so moving focus and scrolling cost no more than the classic look.
 */
class GlassDrawable(context: Context, val style: Glass.Kind, private val backdrop: GlassBackdrop?) : Drawable() {

    private val density = context.resources.displayMetrics.density
    private val focusable = style == Glass.Kind.CIRCLE || style == Glass.Kind.PILL ||
        style == Glass.Kind.MORE_ICON || style == Glass.Kind.ROW

    /** Which film this shape shows. Rows have none: they sit on the glass of their window. */
    private val source: GlassBackdrop.Kind? = when (style) {
        Glass.Kind.PANEL, Glass.Kind.POPUP, Glass.Kind.DIALOG_POPUP -> GlassBackdrop.Kind.PANEL
        Glass.Kind.CIRCLE, Glass.Kind.PILL, Glass.Kind.CHIP, Glass.Kind.MORE_ICON -> GlassBackdrop.Kind.CONTROL
        Glass.Kind.ROW -> null
    }
    private val windowLike = source == GlassBackdrop.Kind.PANEL
    private val filmAlpha = if (windowLike) 1f else CONTROL_FILM_ALPHA
    /** How strong the dark fill is, for crossfades into or out of it. */
    private val fallbackAlpha = if (windowLike) PANEL_FALLBACK_ALPHA else Color.alpha(CONTROL_FALLBACK) / 255f
    private val padPlain = GlassBakes.pad(style, false, density)
    private val padFocused = GlassBakes.pad(style, true, density)
    private val fade = FocusFade()
    private var alphaValue = 255

    private val laidOut = Rect()
    private val shape = RectF()
    private var radius = 0f
    private var bakePlain: Bitmap? = null
    private var bakeFocused: Bitmap? = null
    private val bakePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val filmPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // The newest film and the one before (for crossfades), each with its own shader: a shader's matrix is
    // this shape's position, so it can't be shared between shapes.
    private var film0: Bitmap? = null
    private var shader0: BitmapShader? = null
    private var film1: Bitmap? = null
    private var shader1: BitmapShader? = null
    private val filmMatrix = Matrix()
    private var hostX = Int.MIN_VALUE
    private var hostY = Int.MIN_VALUE
    private var matrixScaleX = 0f
    private var matrixScaleY = 0f
    private var matrixOriginX = 0
    private var matrixOriginY = 0
    private val location = IntArray(2)
    private var registered = false

    init {
        // A FireDialog dims what's behind it by a quarter, so its glass shows the film dimmed the same.
        if (style == Glass.Kind.DIALOG_POPUP) filmPaint.colorFilter = LightingColorFilter(DIALOG_DIM, 0)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        if (b != laidOut) layOut(b)
        val now = SystemClock.uptimeMillis()
        val a = alphaValue / 255f
        if (source != null) drawFilm(canvas, source, a)
        val t = fade.value(now)
        if (t < 1f) drawBake(canvas, focusedLook = false, strength = a * (1f - t))
        if (t > 0f) drawBake(canvas, focusedLook = true, strength = a * t)
        if (fade.running) invalidateSelf()
    }

    private fun layOut(b: Rect) {
        if (b.width() != laidOut.width() || b.height() != laidOut.height()) {
            bakePlain = null
            bakeFocused = null
        }
        laidOut.set(b)
        shape.set(b)
        radius = GlassBakes.radius(style, b.width(), b.height(), density)
        if (windowLike) {
            fallbackPaint.shader = LinearGradient(
                0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
                PANEL_FALLBACK_TOP, PANEL_FALLBACK_BOTTOM, Shader.TileMode.CLAMP,
            )
        } else {
            fallbackPaint.color = CONTROL_FALLBACK
        }
    }

    private fun drawFilm(canvas: Canvas, kind: GlassBackdrop.Kind, a: Float) {
        val bd = backdrop
        if (bd == null) {
            // Home has no film: the dark fill only.
            drawSource(canvas, null, a)
            return
        }
        if (!registered) {
            registered = true
            bd.register(this)
        }
        val top = bd.frame(kind)
        val f = bd.fadeFraction()
        val under = if (f < 1f) bd.previous(kind) else null
        if (top != null || under != null) placeFilm(bd)
        if (f >= 1f || (top == null && under == null)) {
            drawSource(canvas, top, a)
        } else {
            // A crossfade without an offscreen layer: the lower one is drawn weaker, so the sum is a true mix.
            val topAlpha = a * f * (if (top != null) filmAlpha else fallbackAlpha)
            val lower = if (topAlpha >= 1f) 0f else (a * (1f - f) / (1f - topAlpha)).coerceIn(0f, 1f)
            drawSource(canvas, under, lower)
            drawSource(canvas, top, a * f)
            invalidateSelf()
        }
        bd.onGlassDrawn()
    }

    /** Film, or the dark fill when [film] is null, at [strength] of its usual opacity. */
    private fun drawSource(canvas: Canvas, film: Bitmap?, strength: Float) {
        if (strength <= 0f) return
        if (film == null) {
            // The window fill's colours are in its gradient; the control fill is one colour.
            val base = if (windowLike) 255 else Color.alpha(CONTROL_FALLBACK)
            fallbackPaint.alpha = (base * strength).roundToInt()
            canvas.drawRoundRect(shape, radius, radius, fallbackPaint)
        } else {
            filmPaint.shader = shaderFor(film)
            filmPaint.alpha = (255 * filmAlpha * strength).roundToInt()
            canvas.drawRoundRect(shape, radius, radius, filmPaint)
        }
    }

    /**
     * Lines the film up with the screen. The view's layout position is used, not where an animation has moved
     * it: the side panel slides in with translationX, and its drawing isn't redone when the slide ends.
     */
    private fun placeFilm(bd: GlassBackdrop) {
        val host = hostView() ?: return
        var x = 0
        var y = 0
        var v: View = host
        var p = v.parent
        while (p is View) {
            x += v.left - p.scrollX
            y += v.top - p.scrollY
            v = p
            p = v.parent
        }
        // The window's place on screen, which matters for popups.
        v.getLocationOnScreen(location)
        x += location[0]
        y += location[1]
        val sx = bd.screenScale
        val sy = bd.screenScaleY
        val ox = bd.originX
        val oy = bd.originY
        if (x == hostX && y == hostY && sx == matrixScaleX && sy == matrixScaleY && ox == matrixOriginX && oy == matrixOriginY) return
        hostX = x
        hostY = y
        matrixScaleX = sx
        matrixScaleY = sy
        matrixOriginX = ox
        matrixOriginY = oy
        filmMatrix.setScale(sx, sy)
        filmMatrix.postTranslate((ox - x).toFloat(), (oy - y).toFloat())
        shader0?.setLocalMatrix(filmMatrix)
        shader1?.setLocalMatrix(filmMatrix)
    }

    private fun shaderFor(film: Bitmap): BitmapShader {
        val s0 = shader0
        if (film === film0 && s0 != null) return s0
        val s1 = shader1
        if (film === film1 && s1 != null) return s1
        // A new copy of the film (once a second at most): the newest becomes the one it fades from.
        film1 = film0
        shader1 = s0
        val s = BitmapShader(film, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        s.setLocalMatrix(filmMatrix)
        film0 = film
        shader0 = s
        return s
    }

    private fun drawBake(canvas: Canvas, focusedLook: Boolean, strength: Float) {
        if (strength <= 0f) return
        val bake = (if (focusedLook) bakeFocused else bakePlain)
            ?: GlassBakes.glass(style, focusedLook, laidOut.width(), laidOut.height(), density).also {
                if (focusedLook) bakeFocused = it else bakePlain = it
            }
        val pad = if (focusedLook) padFocused else padPlain
        bakePaint.alpha = (255 * strength).roundToInt()
        canvas.drawBitmap(bake, (laidOut.left - pad).toFloat(), (laidOut.top - pad).toFloat(), bakePaint)
    }

    /** The view this is drawn in, through any drawables that hold it. */
    internal fun hostView(): View? {
        var cb: Any? = callback
        while (cb is Drawable) cb = cb.callback
        return cb as? View
    }

    /** Whether this glass can be seen: its view is shown and neither it nor a parent is faded out. */
    internal fun isOnScreen(): Boolean {
        if (alphaValue == 0) return false
        val host = hostView() ?: return false
        if (!host.isShown) return false
        var a = alphaValue / 255f
        var v: View? = host
        while (v != null) {
            a *= v.alpha
            if (a <= 0.01f) return false
            v = v.parent as? View
        }
        return true
    }

    override fun isStateful(): Boolean = focusable

    override fun onStateChange(state: IntArray): Boolean {
        if (!focusable || !fade.set(state.contains(android.R.attr.state_focused))) return false
        invalidateSelf()
        return true
    }

    /** No fade when the selected row is rebuilt (SidePanel.rebuild) or a window closes. */
    override fun jumpToCurrentState() {
        if (fade.jump()) invalidateSelf()
    }

    override fun setAlpha(alpha: Int) {
        if (alphaValue == alpha) return
        alphaValue = alpha
        invalidateSelf()
    }

    override fun getAlpha(): Int = alphaValue

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /** Views keep their own padding. */
    override fun getPadding(padding: Rect): Boolean {
        padding.setEmpty()
        return false
    }
}

/** The 140 ms fade between the plain and selected looks, like the classic selectors' enter and exit fade. */
internal class FocusFade {
    var focused = false
        private set
    private var from = 0f
    private var start = 0L

    val running: Boolean get() = start != 0L

    /** Returns true when the look changes. */
    fun set(focused: Boolean): Boolean {
        if (focused == this.focused) return false
        val now = SystemClock.uptimeMillis()
        from = value(now)
        this.focused = focused
        start = now
        return true
    }

    /** 0 is plain, 1 is selected. */
    fun value(now: Long): Float {
        val target = if (focused) 1f else 0f
        if (start == 0L) return target
        val p = (now - start).toFloat() / FOCUS_FADE_MS
        if (p >= 1f) {
            start = 0L
            return target
        }
        return from + (target - from) * p
    }

    /** Ends a fade at once; true if one was running. */
    fun jump(): Boolean {
        if (start == 0L) return false
        start = 0L
        return true
    }
}

/**
 * Bitmaps of the parts of glass that never change, shared by every shape of the same look and size. Settings
 * rows are rebuilt on every key press, so their glass has to come from here rather than be drawn again.
 */
internal object GlassBakes {

    private const val TOGGLE = -1

    private data class Key(val style: Int, val focused: Boolean, val width: Int, val height: Int, val variant: Int, val density: Float)

    private val cache = object : LruCache<Key, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: Key, value: Bitmap): Int = value.byteCount
    }

    /** About 12 MB, less on a small heap: the film's buffer takes most of it on older TVs. */
    private fun cacheBytes(): Int = min(12L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 16).toInt()

    fun glass(kind: Glass.Kind, focused: Boolean, w: Int, h: Int, density: Float): Bitmap {
        val key = Key(kind.ordinal, focused, w, h, 0, density)
        cache.get(key)?.let { return it }
        val bitmap = Baker(density).glass(kind, focused, w, h, pad(kind, focused, density))
        cache.put(key, bitmap)
        return bitmap
    }

    fun toggle(on: Boolean, rowFocused: Boolean, w: Int, h: Int, density: Float): Bitmap {
        // An on switch looks the same on the selected row.
        val variant = if (on) 1 else if (rowFocused) 2 else 0
        val key = Key(TOGGLE, false, w, h, variant, density)
        cache.get(key)?.let { return it }
        val bitmap = Baker(density).toggle(on, rowFocused && !on, w, h, togglePad(density))
        cache.put(key, bitmap)
        return bitmap
    }

    /** How far shadows and glows reach past the shape: the bitmap is that much bigger on every side. */
    fun pad(kind: Glass.Kind, focused: Boolean, density: Float): Int {
        val dp = when (kind) {
            Glass.Kind.PANEL, Glass.Kind.POPUP, Glass.Kind.DIALOG_POPUP -> PANEL_SHADOW.reach
            Glass.Kind.CHIP -> CHIP_SHADOW.reach
            // The selected row's glow stays inside the row: no room may be added around it.
            Glass.Kind.ROW -> 0f
            Glass.Kind.CIRCLE, Glass.Kind.PILL, Glass.Kind.MORE_ICON ->
                if (focused) max(FOCUS_GLOW.reach, FOCUS_SHADOW.reach) else CONTROL_SHADOW.reach
        }
        return ceil(dp * density).toInt()
    }

    fun togglePad(density: Float): Int = ceil(TOGGLE_GLOW.reach * density).toInt()

    fun radius(kind: Glass.Kind, w: Int, h: Int, density: Float): Float {
        val half = min(w, h) / 2f
        return when (kind) {
            Glass.Kind.PANEL -> min(22f * density, half)
            Glass.Kind.POPUP, Glass.Kind.DIALOG_POPUP -> min(15f * density, half)
            Glass.Kind.CIRCLE, Glass.Kind.MORE_ICON, Glass.Kind.PILL, Glass.Kind.CHIP -> half
            // A pill while one line high; taller rows (search results) keep soft corners instead of round ends.
            Glass.Kind.ROW -> min(18f * density, half)
        }
    }
}

/** A soft shadow or glow: its colour, how far down it falls and its blur, in dp. */
private class Shadow(val color: Int, val dy: Float, val sigma: Float) {
    /** Past 2.5 sigma what's left is under 1% and can't be seen. */
    val reach: Float get() = abs(dy) + 2.5f * sigma
}

/**
 * Draws the glass in software. Blurred layers are drawn at up to a quarter size and enlarged smoothly, so even the
 * full-height settings window takes a few milliseconds.
 */
private class Baker(private val density: Float) {

    private fun px(dp: Float) = dp * density

    fun glass(kind: Glass.Kind, focused: Boolean, w: Int, h: Int, pad: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.translate(pad.toFloat(), pad.toFloat())
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val r = GlassBakes.radius(kind, w, h, density)
        when {
            kind == Glass.Kind.ROW && focused -> {
                // As the released selected row: a white body 3dp inside the row, its glow in that margin.
                val margin = px(3f)
                val body = RectF(rect).apply { inset(margin, margin) }
                if (body.width() > 0f && body.height() > 0f) {
                    // Round ends on a one-line row; a taller one (a search result with its cover) keeps the row's
                    // soft corners, so what's inside never pokes out of the white.
                    val br = min(min(body.width(), body.height()) / 2f, r - margin)
                    outside(c, pad, w, h, body, br, ROW_GLOW)
                    focusedBody(c, pad, w, h, body, br)
                }
            }
            kind == Glass.Kind.ROW -> {
                fill(c, rect, r, ROW_FILL)
                crescent(c, rect, r, px(0.75f), ROW_CRESCENT)
            }
            focused -> {
                outside(c, pad, w, h, rect, r, FOCUS_SHADOW, FOCUS_GLOW)
                focusedBody(c, pad, w, h, rect, r)
            }
            else -> clear(c, kind, pad, w, h, rect, r)
        }
        return bitmap
    }

    /** Unselected glass: shadow, glaze, bevel and rim over the film. */
    private fun clear(c: Canvas, kind: Glass.Kind, pad: Int, w: Int, h: Int, rect: RectF, r: Float) {
        val window = kind == Glass.Kind.PANEL || kind == Glass.Kind.POPUP || kind == Glass.Kind.DIALOG_POPUP
        val chip = kind == Glass.Kind.CHIP
        outside(c, pad, w, h, rect, r, if (window) PANEL_SHADOW else if (chip) CHIP_SHADOW else CONTROL_SHADOW)
        // Milky white over the film: plain white at an alpha is the same as Screen with white.
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = if (window) css(160f, rect, PANEL_GLAZE, PANEL_GLAZE_STOPS) else css(160f, rect, CONTROL_GLAZE, CONTROL_GLAZE_STOPS)
        c.drawRoundRect(rect, r, r, paint)
        val band = when {
            window -> px(10f)
            chip -> px(3f)
            else -> (0.15f * min(w, h)).coerceIn(px(4f), px(7f))
        }
        bevel(c, pad, w, h, rect, r, band)
        rim(c, rect, r, if (chip) px(0.75f) else px(1f))
        crescent(c, rect, r, px(0.75f), RIM_CRESCENT)
    }

    /** The glossy white of a selected control or row, with its inner shade, top highlight and blue ring. */
    private fun focusedBody(c: Canvas, pad: Int, w: Int, h: Int, body: RectF, r: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = css(160f, body, FOCUS_BODY, FOCUS_BODY_STOPS)
        c.drawRoundRect(body, r, r, paint)
        val ring = px(2f)
        // Both inside the ring, or the ring would hide them.
        val inner = RectF(body).apply { inset(ring, ring) }
        val ir = max(r - ring, 0f)
        if (inner.width() > 0f && inner.height() > 0f) {
            val lifted = RectF(inner).apply { offset(0f, -px(1.5f)) }
            innerLayer(c, pad, w, h, inner, ir, intArrayOf(FOCUS_SHADE), lifted, ir, px(2f), reach = 0f)
            crescent(c, inner, ir, px(1f), FOCUS_HIGHLIGHT)
        }
        stroke(c, body, r, ring, ACCENT)
    }

    /**
     * Shadows and glows outside the shape only: cut out of it, so a see-through body never shows its own shadow.
     */
    private fun outside(c: Canvas, pad: Int, w: Int, h: Int, rect: RectF, r: Float, vararg layers: Shadow) {
        c.save()
        clipOut(c, inside(rect, r, 0f))
        for (layer in layers) blurred(c, pad, w, h, rect, r, px(layer.dy), px(layer.sigma), layer.color)
        val cut = Paint(Paint.ANTI_ALIAS_FLAG)
        cut.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        c.drawRoundRect(rect, r, r, cut)
        c.restore()
    }

    /** A blurred copy of the shape, moved down by [dy], drawn small and enlarged. */
    private fun blurred(c: Canvas, pad: Int, w: Int, h: Int, rect: RectF, r: Float, dy: Float, sigma: Float, color: Int) {
        val q = scaleFor(sigma)
        val small = smallBitmap(w, h, pad, q)
        val shape = scaled(rect, pad, q).apply { offset(0f, dy / q) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        paint.maskFilter = blurFilter(sigma / q)
        Canvas(small).drawRoundRect(shape, r / q, r / q, paint)
        val p = -pad.toFloat()
        c.drawBitmap(small, null, RectF(p, p, p + small.width * q, p + small.height * q), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /** A glow along the inside edge, brightest at the top and right, fading in over [band]. */
    private fun bevel(c: Canvas, pad: Int, w: Int, h: Int, rect: RectF, r: Float, band: Float) {
        val e = band * 0.3f
        val cut = RectF(rect).apply { inset(e, e) }
        innerLayer(c, pad, w, h, rect, r, BEVEL, cut, max(r - e, 0f), band * 0.3f, reach = band)
    }

    /**
     * Something soft inside the shape: [colors] (one, or a gradient towards the top right) less a blurred [cut]
     * shape. Made small when the blur is wide, then drawn back through the sharp shape so the outline stays crisp.
     * Nothing is drawn deeper than [reach] in from the edge (0: everywhere).
     */
    private fun innerLayer(
        c: Canvas, pad: Int, w: Int, h: Int, rect: RectF, r: Float,
        colors: IntArray, cut: RectF, cutRadius: Float, sigma: Float, reach: Float,
    ) {
        val q = scaleFor(sigma)
        val small = smallBitmap(w, h, pad, q)
        val sc = Canvas(small)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        if (colors.size > 1) fill.shader = css(45f, scaled(rect, pad, q), colors, null) else fill.color = colors[0]
        sc.drawPaint(fill)
        if (cut.width() > 0f && cut.height() > 0f) {
            val erase = Paint(Paint.ANTI_ALIAS_FLAG)
            erase.maskFilter = blurFilter(sigma / q)
            erase.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            sc.drawRoundRect(scaled(cut, pad, q), cutRadius / q, cutRadius / q, erase)
        }
        val m = Matrix()
        m.setScale(q, q)
        m.postTranslate(-pad.toFloat(), -pad.toFloat())
        val shader = BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(m)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.shader = shader
        c.save()
        if (reach > 0f) clipOut(c, inside(rect, r, reach))
        c.drawRoundRect(rect, r, r, paint)
        c.restore()
    }

    /** The bright edge: light at the top left, faint down the sides, a small catch at the bottom right. */
    private fun rim(c: Canvas, rect: RectF, r: Float, width: Float) {
        val half = width / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.shader = css(140f, rect, RIM, RIM_STOPS)
        c.drawRoundRect(RectF(rect).apply { inset(half, half) }, max(r - half, 0f), max(r - half, 0f), paint)
    }

    private fun stroke(c: Canvas, rect: RectF, r: Float, width: Float, color: Int) {
        val half = width / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.color = color
        c.drawRoundRect(RectF(rect).apply { inset(half, half) }, max(r - half, 0f), max(r - half, 0f), paint)
    }

    private fun fill(c: Canvas, rect: RectF, r: Float, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        c.drawRoundRect(rect, r, r, paint)
    }

    /** A thin line along the top inside edge that follows the curve: the shape less itself moved down by [d]. */
    private fun crescent(c: Canvas, rect: RectF, r: Float, d: Float, color: Int) {
        val line = Path()
        line.addRoundRect(rect, r, r, Path.Direction.CW)
        val lower = Path()
        lower.addRoundRect(RectF(rect).apply { offset(0f, d) }, r, r, Path.Direction.CW)
        // If the shapes can't be subtracted, no line rather than the whole shape filled.
        if (!line.op(lower, Path.Op.DIFFERENCE)) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        c.drawPath(line, paint)
    }

    /** The settings switch: a blue track with a glow when on, a faint one when off; the knob on that side. */
    fun toggle(on: Boolean, rowFocused: Boolean, w: Int, h: Int, pad: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        c.translate(pad.toFloat(), pad.toFloat())
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val r = min(w, h) / 2f
        if (on) {
            outside(c, pad, w, h, rect, r, TOGGLE_GLOW)
            val track = Paint(Paint.ANTI_ALIAS_FLAG)
            track.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), TOGGLE_ON, TOGGLE_ON_STOPS, Shader.TileMode.CLAMP)
            c.drawRoundRect(rect, r, r, track)
            crescent(c, rect, r, px(1f), TOGGLE_ON_SHADE)
        } else {
            // On the white selected row a white track would vanish: it turns dark navy there.
            fill(c, rect, r, if (rowFocused) TOGGLE_OFF_ON_WHITE else TOGGLE_OFF)
            if (!rowFocused) crescent(c, rect, r, px(0.5f), TOGGLE_OFF_CRESCENT)
            stroke(c, rect, r, px(0.5f), if (rowFocused) TOGGLE_OFF_ON_WHITE else TOGGLE_OFF_RIM)
        }
        val inset = px(2f)
        val d = max(h - 2 * inset, 1f)
        val left = if (on) w - inset - d else inset
        val knob = RectF(left, (h - d) / 2f, left + d, (h + d) / 2f)
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
        shadow.color = KNOB_SHADOW
        shadow.maskFilter = blurFilter(px(1f))
        c.drawOval(RectF(knob).apply { offset(0f, px(1f)) }, shadow)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = RadialGradient(knob.left + 0.35f * d, knob.top + 0.3f * d, 0.7f * d, KNOB_LIGHT, KNOB_EDGE, Shader.TileMode.CLAMP)
        c.drawOval(knob, paint)
        return bitmap
    }

    /** How much smaller a blur of [sigma] px can be drawn and still enlarge smoothly. */
    private fun scaleFor(sigma: Float): Float = (sigma / 2.5f).toInt().coerceIn(1, 4).toFloat()

    private fun smallBitmap(w: Int, h: Int, pad: Int, q: Float): Bitmap = Bitmap.createBitmap(
        max(ceil((w + 2 * pad) / q).toInt(), 1),
        max(ceil((h + 2 * pad) / q).toInt(), 1),
        Bitmap.Config.ARGB_8888,
    )

    private fun scaled(rect: RectF, pad: Int, q: Float) =
        RectF((rect.left + pad) / q, (rect.top + pad) / q, (rect.right + pad) / q, (rect.bottom + pad) / q)

    /** BlurMaskFilter takes a radius, not a sigma; and a radius of 0 is refused. */
    private fun blurFilter(sigma: Float) = BlurMaskFilter(max((sigma - 0.5f) / 0.57735f, 0.1f), BlurMaskFilter.Blur.NORMAL)

    /**
     * A plain rectangle inside the shape where everything is at least [reach] in from the edge: drawing there can
     * be skipped, which keeps the tall settings window quick.
     */
    private fun inside(rect: RectF, r: Float, reach: Float): RectF {
        val k = (if (reach >= r) reach else r - (r - reach) / SQRT2) + 1f
        return RectF(rect).apply { inset(k, k) }
    }

    private fun clipOut(c: Canvas, rect: RectF) {
        if (rect.width() <= 0f || rect.height() <= 0f) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.clipOutRect(rect)
        } else {
            @Suppress("DEPRECATION") c.clipRect(rect, Region.Op.DIFFERENCE)
        }
    }

    /**
     * A gradient at a CSS angle (0 = towards the top, 90 = towards the right) that spans the rectangle the way
     * CSS does, so the colours match the design.
     */
    private fun css(angle: Float, rect: RectF, colors: IntArray, stops: FloatArray?): LinearGradient {
        val rad = Math.toRadians(angle.toDouble())
        val dx = sin(rad).toFloat()
        val dy = (-cos(rad)).toFloat()
        val half = (rect.width() * abs(dx) + rect.height() * abs(dy)) / 2f
        val cx = rect.centerX()
        val cy = rect.centerY()
        return LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half, colors, stops, Shader.TileMode.CLAMP)
    }
}

// ---- The material (dp; colours ARGB) -------------------------------------

private const val FOCUS_FADE_MS = 140f
private const val CONTROL_FILM_ALPHA = 0.92f
private val SQRT2 = sqrt(2f)

/** Where there's no film (copy failed, older TV, Home): dark enough that nothing behind can be read. */
private val PANEL_FALLBACK_TOP = 0xE01A1D24.toInt()
private val PANEL_FALLBACK_BOTTOM = 0xE80C0E12.toInt()
private const val PANEL_FALLBACK_ALPHA = 0.89f
private val CONTROL_FALLBACK = 0x40000000
private val DIALOG_DIM = 0xBFBFBF

private val PANEL_SHADOW = Shadow(0x59000000, 6f, 12f)
private val CONTROL_SHADOW = Shadow(0x4D000000, 4f, 6f)
private val CHIP_SHADOW = Shadow(0x33000000, 2f, 3f)
// Small and faint: the controls sit just under the time, which a big blue haze would cover.
private val FOCUS_GLOW = Shadow(0x663D8BFF, 0f, 5f)
private val FOCUS_SHADOW = Shadow(0x59000000, 5f, 7f)
private val ROW_GLOW = Shadow(0x593D8BFF, 0f, 2f)
private val TOGGLE_GLOW = Shadow(0x803D8BFF.toInt(), 0f, 3f)

private val PANEL_GLAZE = intArrayOf(0x29FFFFFF, 0x0DFFFFFF, 0x14FFFFFF)
private val PANEL_GLAZE_STOPS = floatArrayOf(0f, 0.5f, 1f)
private val CONTROL_GLAZE = intArrayOf(0x33FFFFFF, 0x0FFFFFFF, 0x1AFFFFFF)
private val CONTROL_GLAZE_STOPS = floatArrayOf(0f, 0.45f, 1f)
/** Bottom left to top right. */
private val BEVEL = intArrayOf(0x12FFFFFF, 0x26FFFFFF)
private val RIM = intArrayOf(0xA6FFFFFF.toInt(), 0x40FFFFFF, 0x1AFFFFFF, 0x1AFFFFFF, 0x66FFFFFF)
private val RIM_STOPS = floatArrayOf(0f, 0.3f, 0.5f, 0.85f, 1f)
private val RIM_CRESCENT = 0x73FFFFFF

private val ROW_FILL = 0x12FFFFFF
private val ROW_CRESCENT = 0x1FFFFFFF

private val FOCUS_BODY = intArrayOf(0xFAFFFFFF.toInt(), 0xF0F2F5FF.toInt(), 0xE6DCE6FF.toInt())
private val FOCUS_BODY_STOPS = floatArrayOf(0f, 0.55f, 1f)
private val FOCUS_HIGHLIGHT = 0xFFFFFFFF.toInt()
private val FOCUS_SHADE = 0x333D8BFF
private val ACCENT = 0xFF3D8BFF.toInt()

private val TOGGLE_ON = intArrayOf(0xFF5C9DFF.toInt(), 0xFF3D8BFF.toInt(), 0xFF2F78EE.toInt())
private val TOGGLE_ON_STOPS = floatArrayOf(0f, 0.5f, 1f)
private val TOGGLE_ON_SHADE = 0x40000000
private val TOGGLE_OFF = 0x29FFFFFF
private val TOGGLE_OFF_CRESCENT = 0x33FFFFFF
private val TOGGLE_OFF_RIM = 0x1FFFFFFF
private val TOGGLE_OFF_ON_WHITE = 0x330D1630
private val KNOB_SHADOW = 0x4D000000
private val KNOB_LIGHT = 0xFFFFFFFF.toInt()
private val KNOB_EDGE = 0xFFE8EEFC.toInt()
