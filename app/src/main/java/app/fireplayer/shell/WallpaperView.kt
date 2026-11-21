package app.fireplayer.shell

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.View
import app.fireplayer.R
import app.fireplayer.ui.StillGlass
import java.util.concurrent.Executors

/**
 * The stationary background shared by every tab (independent of the featured title), one per look ([glassLook]),
 * both the user's: glass — the foggy blue beach with its light spiral, blurred; classic — the dark blue-grey fog. It is decoded and scaled once per look,
 * off the main thread; with glass, the same pass builds the blurred pictures glass shows ([StillGlass]), so glass on
 * these screens never copies or blurs anything per frame.
 *
 * Live video shows through a hole ([setHole]): the guide's miniature, or the whole screen in full screen, and every
 * place in between while the picture grows or shrinks. The live picture is a SurfaceView behind the window, so
 * everything drawn here stays outside the hole; its rounded corners are the wallpaper drawn over the corners of the
 * video.
 *
 * The wallpaper around the hole is four plain strips (above, below, left and right of it), and its rounded corners
 * are a thick rounded outline of the picture whose inner edge is the hole's (the rest of it lies over the strips, the
 * same picture): on a Fire TV's graphics that costs far less, every frame the hole moves, than the whole screen drawn
 * through a rounded cut-out, or any shape the graphics would have to draw into a picture of its own first.
 */
class WallpaperView(context: Context) : View(context) {

    private var picture: Bitmap? = null
        set(v) {
            field = v
            // The hole's corners are filled with the picture, in place (it's the screen's size, drawn at 0, 0).
            cornerPaint.shader = v?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        }
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val black = Paint().apply { color = Color.BLACK }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { style = Paint.Style.STROKE }
    private val blackRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK }
    private val holeRect = RectF()
    private var holeRadius = 0f
    /** The hole shows only below this line (0: all of it). */
    private var holeCut = 0f
    /** The corners' outline: its rectangle, corner radius and width (none while the hole's corners are square). */
    private val ring = RectF()
    private var ringRadius = 0f
    private var ringWidth = 0f
    private var fullHole = false
    private val band = Rect()

    /** Called once the glass look's wallpaper and its glass are ready (main thread). */
    var onReady: ((StillGlass) -> Unit)? = null

    /** Which background: the glass look's (true) or the classic look's (false). */
    var glassLook: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            // The other picture loads; the one on screen stays until it's ready, so nothing flashes black.
            if (width > 0 && height > 0) load(width, height)
        }

    private var loadSerial = 0

    init {
        // No background: a background ignores the hole and would cover the video.
        setWillNotDraw(false)
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * Where live video shows: [rect] in this view's coordinates; null for none; [full] for the whole view. Called every
     * frame while the picture moves (the hole follows it, its corners rounding off or back), so it makes nothing new,
     * and the same hole again draws nothing again. [cutAbove]: the hole only shows below that line (y, 0: all of it),
     * cut square there (the live picture's window part-way under the top of its page's rows).
     */
    fun setHole(rect: RectF?, radius: Float, full: Boolean = false, cutAbove: Float = 0f) {
        val same = full == fullHole && radius == holeRadius && cutAbove == holeCut &&
            (if (rect == null || rect.isEmpty) holeRect.isEmpty else rect == holeRect)
        if (same) return
        fullHole = full
        holeCut = cutAbove
        if (rect == null) holeRect.setEmpty() else holeRect.set(rect)
        holeRadius = radius
        // An outline as wide as the corners' radius (a pixel at least) reaches past each corner of the hole's box.
        val k = if (holeRect.isEmpty) 0f else minOf(radius, holeRect.width() / 2, holeRect.height() / 2)
        ringWidth = if (k > 0f) maxOf(k, 2f) else 0f
        if (ringWidth > 0f) {
            val half = ringWidth / 2
            ring.set(holeRect.left - half, holeRect.top - half, holeRect.right + half, holeRect.bottom + half)
            ringRadius = k + half
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0 && picture == null) load(w, h)
    }

    /** Film mode (design-brief §6.6): the screen-sized picture leaves memory while the screen is covered; [restore]. */
    fun release() {
        // A load under way doesn't bring it back.
        loadSerial++
        loading = false
        picture = null
    }

    /**
     * Back on screen: the picture loads again, unless a load is under way. The glass made from it is still in use, so
     * it is made again only when a load that would have made it was dropped.
     */
    fun restore() {
        if (picture == null && !loading && width > 0 && height > 0) load(width, height, glassToo = glassOwed)
    }

    /** A load under way (its answer not in yet). */
    private var loading = false
    /** A load that was to make the glass didn't deliver it (dropped by [release]). */
    private var glassOwed = false

    private fun load(w: Int, h: Int, glassToo: Boolean = true) {
        val res = resources
        val density = res.displayMetrics.density
        val glassLook = glassLook
        val serial = ++loadSerial
        loading = true
        if (glassToo) glassOwed = true
        worker.execute {
            val decoded = BitmapFactory.decodeResource(res, if (glassLook) R.drawable.wallpaper_glass else R.drawable.wallpaper_classic)
            if (decoded == null) {
                main.post { if (serial == loadSerial) loading = false }
                return@execute
            }
            // Centre-cropped to the screen once, so drawing is a plain copy.
            val scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val sx = w.toFloat() / decoded.width
            val sy = h.toFloat() / decoded.height
            val s = maxOf(sx, sy)
            val dw = decoded.width * s
            val dh = decoded.height * s
            Canvas(scaled).drawBitmap(decoded, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            decoded.recycle()
            // Only the glass look shows blurred wallpaper.
            val glass = if (glassLook && glassToo) StillGlass.from(scaled, w, h, density) else null
            main.post {
                if (serial != loadSerial) return@post
                loading = false
                if (glassToo) glassOwed = false
                picture = scaled
                invalidate()
                glass?.let { onReady?.invoke(it) }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (fullHole) return
        val bmp = picture
        val w = width
        val h = height
        if (holeRect.isEmpty) {
            if (bmp == null) canvas.drawColor(Color.BLACK) else canvas.drawBitmap(bmp, 0f, 0f, paint)
            return
        }
        val l = Math.round(holeRect.left).coerceIn(0, w)
        val cut = Math.round(holeCut).coerceIn(0, h)
        val t = Math.round(holeRect.top).coerceIn(cut, h)
        val r = Math.round(holeRect.right).coerceIn(l, w)
        val b = Math.round(holeRect.bottom).coerceIn(t, h)
        strip(canvas, bmp, 0, 0, w, t)
        strip(canvas, bmp, 0, b, w, h)
        strip(canvas, bmp, 0, t, l, b)
        strip(canvas, bmp, r, t, w, b)
        if (ringWidth > 0f) {
            val p = if (bmp == null) blackRing else cornerPaint
            p.strokeWidth = ringWidth
            // Cut at the line: the corners above it are under the strip already, and the hole's edge there is square.
            val cutting = cut > 0 && ring.top < cut
            if (cutting) {
                canvas.save()
                canvas.clipRect(0, cut, w, h)
            }
            canvas.drawRoundRect(ring, ringRadius, ringRadius, p)
            if (cutting) canvas.restore()
        }
    }

    /** The wallpaper over one strip (the picture is the screen's size, so it's the same rectangle of it). */
    private fun strip(canvas: Canvas, bmp: Bitmap?, left: Int, top: Int, right: Int, bottom: Int) {
        if (right <= left || bottom <= top) return
        band.set(left, top, right, bottom)
        if (bmp == null) canvas.drawRect(band, black) else canvas.drawBitmap(bmp, band, band, paint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        main.removeCallbacksAndMessages(null)
    }

    private val main = Handler(Looper.getMainLooper())

    private companion object {
        val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "FirePlayerWallpaper").apply { priority = Thread.MIN_PRIORITY } }
    }
}
