package app.fireplayer.live.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import app.fireplayer.R
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Glass

/**
 * A channel logo as decoded, with the plate it's drawn on, chosen once as it loads ([LogoPlate.light]): [light] in the
 * classic look, [lightOnGlass] on the glass look's tiles (darker than the classic soft plate, so fewer logos need it).
 */
class Logo(val bitmap: Bitmap, val light: Boolean, val lightOnGlass: Boolean = light)

/**
 * The plate behind every channel logo (guide rows, the details beside the miniature, Home's recent channels), so
 * logos stand out from the dark wallpaper in both looks. Classic: most logos sit on a soft see-through light plate
 * ([SOFT]), on which white and coloured ones stay bright; dark ink on a see-through background (a black or grey
 * wordmark) would still vanish there, so those get a light plate ([LIGHT]). Glass look: every logo sits on the same
 * rounded glass tile ([PatchLook.TILE]: the dark glass of the app's buttons, with its hairline and top highlight), and
 * only dark ink gets that tile frosted light ([GLASS_LIGHT]). Pure, unit-tested.
 */
object LogoPlate {
    /** White at 20%: over the dark wallpaper (or glass) a soft lighter tile; white logos keep their contrast. */
    const val SOFT = 0x33FFFFFF
    /** A light grey at 80%: dark ink reads on it as on paper. */
    const val LIGHT = 0xCCE6E8EB.toInt()

    /**
     * The darkest a logo's ink may be on average and still sit on [SOFT] (relative luminance, 0 black to 1 white).
     * About where dark ink reads equally badly on both plates: darker ink reads better on [LIGHT], lighter ink on [SOFT].
     */
    const val DARK_INK = 0.17f
    /** Logos covering at least this much of their box with their own background bring it with them: [SOFT] just frames them. */
    const val OWN_BACKGROUND = 0.9f

    /**
     * Glass look: the tile behind dark ink, a light frost at 65% (what's behind still tints it, so it's glass, not a
     * grey block); black ink reads on it at about 8 to 1.
     */
    const val GLASS_LIGHT = 0xA6E6E8EB.toInt()
    /**
     * Glass look: the darkest a logo's ink may be on average and still sit on the dark tile. The same as [DARK_INK]:
     * a lower value (0.10) left black wordmarks with a little colour in them unreadable on the dark tile on the TV.
     */
    const val DARK_INK_GLASS = 0.17f
    /** Glass look: a tile's corner radius as a share of its height (6 dp on a guide row's 28 dp tile, 9 dp on the details'). */
    const val TILE_RADIUS = 0.22f
    /** Classic: the plate's corner radius, dp, on a plate up to [CLASSIC_RADIUS_FOR_DP] tall; a larger plate's grows with it. */
    const val CLASSIC_RADIUS_DP = 4f
    const val CLASSIC_RADIUS_FOR_DP = 28f
    /** The room round a logo on a guide row's tile, dp; a larger tile names its own ([PlateDrawer]). */
    const val PAD_X_DP = 3f
    const val PAD_Y_DP = 2f

    fun color(light: Boolean): Int = if (light) LIGHT else SOFT

    /** The corner radius, px, of a tile or plate [h] px tall in the look named ([glass]) at this [density]. */
    fun radius(h: Float, glass: Boolean, density: Float): Float =
        if (glass) h * TILE_RADIUS else CLASSIC_RADIUS_DP * density * maxOf(1f, h / (CLASSIC_RADIUS_FOR_DP * density))

    /**
     * How wide a [LogoBadge] of [height] is for a logo of [logoW] x [logoH] pixels: the logo fills the height left
     * inside [padY] and the plate is as wide as that needs with [padX] each side, never narrower than [minWidth]
     * (a tall or square logo sits in the middle) nor wider than [maxWidth] (a very wide one is fitted to the width
     * instead, and is then less tall). With no logo, [minWidth].
     */
    fun badgeWidth(logoW: Int, logoH: Int, height: Int, padX: Float, padY: Float, minWidth: Int, maxWidth: Int): Int {
        val inner = height - 2 * padY
        if (logoW <= 0 || logoH <= 0 || inner <= 0f) return minWidth
        val wide = Math.ceil((logoW * (inner / logoH) + 2 * padX).toDouble()).toInt()
        return wide.coerceIn(minWidth, maxOf(minWidth, maxWidth))
    }

    /**
     * How much a logo of [logoW] x [logoH] is scaled to sit inside a box of [boxW] x [boxH] whole, its shape kept
     * (never stretched): the smaller of the two fits. 0 when either has no size.
     */
    fun fitScale(logoW: Int, logoH: Int, boxW: Float, boxH: Float): Float {
        if (logoW <= 0 || logoH <= 0 || boxW <= 0f || boxH <= 0f) return 0f
        return minOf(boxW / logoW, boxH / logoH)
    }

    /**
     * Whether a logo needs the light plate ([LIGHT]; with [darkInk] = [DARK_INK_GLASS], the glass look's light tile):
     * from its first [count] pixels ([argb], unpremultiplied, as Bitmap.getPixels gives them), the average luminance of
     * its ink (weighted by how opaque each pixel is), unless it has a background of its own. A fully see-through logo
     * has nothing to show either way: [SOFT].
     */
    fun light(argb: IntArray, count: Int = argb.size, darkInk: Float = DARK_INK): Boolean {
        val n = minOf(count, argb.size)
        var ink = 0.0
        var lum = 0.0
        for (i in 0 until n) {
            val c = argb[i]
            val a = (c ushr 24) / 255.0
            if (a == 0.0) continue
            ink += a
            lum += a * (0.2126 * LINEAR[(c shr 16) and 0xFF] + 0.7152 * LINEAR[(c shr 8) and 0xFF] + 0.0722 * LINEAR[c and 0xFF])
        }
        if (ink <= 0.0) return false
        if (ink / n >= OWN_BACKGROUND) return false
        return lum / ink < darkInk
    }

    /** sRGB channel value to linear light, once for all 256 values. */
    private val LINEAR = DoubleArray(256) { v ->
        val s = v / 255.0
        if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }
}

/**
 * Draws a logo on its plate inside a box: the plate fills the box (every logo gets the same tile, so a column of them
 * lines up), the logo is fitted inside it, its shape kept, with [padXDp] and [padYDp] of room around. Glass look: the
 * rounded glass tile ([GlassPatch], drawn once and stamped); classic: the flat plate. Its paints and rects are made
 * once: drawing allocates nothing (the guide draws one per row, every frame while it moves).
 */
class PlateDrawer(private val density: Float, padXDp: Float = LogoPlate.PAD_X_DP, padYDp: Float = LogoPlate.PAD_Y_DP) {
    private val plate = Paint(Paint.ANTI_ALIAS_FLAG)
    private val image = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val fit = RectF()
    private val padX = padXDp * density
    private val padY = padYDp * density
    private val tile = GlassPatch(density, PatchLook.TILE) { h -> LogoPlate.radius(h.toFloat(), true, density) }
    private val tileLight = GlassPatch(density, PatchLook.TILE_LIGHT) { h -> LogoPlate.radius(h.toFloat(), true, density) }

    /** [logo] on its plate in the box [l], [t], [r], [b]; with no logo yet (on its way), the soft plate alone. */
    fun draw(canvas: Canvas, logo: Logo?, l: Float, t: Float, r: Float, b: Float) {
        if (Glass.on) {
            (if (logo != null && logo.lightOnGlass) tileLight else tile).draw(canvas, l, t, r, b)
        } else {
            box.set(l, t, r, b)
            plate.color = LogoPlate.color(logo != null && logo.light)
            val radius = LogoPlate.radius(b - t, false, density)
            canvas.drawRoundRect(box, radius, radius, plate)
        }
        val bmp = logo?.bitmap ?: return
        if (bmp.width <= 0 || bmp.height <= 0) return
        val s = LogoPlate.fitScale(bmp.width, bmp.height, r - l - 2 * padX, b - t - 2 * padY)
        if (s <= 0f) return
        val bw = bmp.width * s
        val bh = bmp.height * s
        val cx = (l + r) / 2
        val cy = (t + b) / 2
        fit.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
        canvas.drawBitmap(bmp, null, fit, image)
    }
}

/**
 * A channel logo on its plate, for lines laid out once (the details' top line, Home's recent channels): a new logo
 * only redraws it, nothing is laid out again (an ImageView lays out again whenever its picture's size changes).
 */
class LogoView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var drawer = PlateDrawer(resources.displayMetrics.density)
    private var logo: Logo? = null
    private var plate = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** The room round the logo inside its plate, dp, for a plate larger than a guide row's (the details' logo). */
    fun pad(xDp: Float, yDp: Float) {
        drawer = PlateDrawer(resources.displayMetrics.density, xDp, yDp)
        invalidate()
    }

    /** [logo] on its plate; [plate] without a logo shows the plate alone (a logo on its way); neither draws nothing. */
    fun set(logo: Logo?, plate: Boolean = logo != null) {
        val show = plate || logo != null
        if (logo === this.logo && show == this.plate) return
        this.logo = logo
        this.plate = show
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!plate) return
        drawer.draw(canvas, logo, 0f, 0f, width.toFloat(), height.toFloat())
    }
}

/**
 * A channel's logo shown large, on a plate of its own that's sized to it (full screen's channel line; the guide can
 * use it too): a neutral dark plate with a fine rim, on which white and coloured logos stay bright over any picture,
 * or a light one for dark ink ([Logo.light]). The logo is fitted inside with room around it and keeps its shape. Its
 * height is the layout's (res/values/dimens.xml logo_badge_height); its width follows the logo
 * ([LogoPlate.badgeWidth]); the rest of its look is in [Tokens] (LOGO_BADGE_*). A new logo of the same shape only
 * redraws it.
 */
class LogoBadge @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val padX = Tokens.LOGO_BADGE_PAD_X_DP * density
    private val padY = Tokens.LOGO_BADGE_PAD_Y_DP * density
    private val minW = (Tokens.LOGO_BADGE_MIN_WIDTH_DP * density + 0.5f).toInt()
    private val maxW = (Tokens.LOGO_BADGE_MAX_WIDTH_DP * density + 0.5f).toInt()
    private val radius = Tokens.LOGO_BADGE_RADIUS_DP * density
    private val defaultH = resources.getDimensionPixelSize(R.dimen.logo_badge_height)
    private val plate = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = density; color = Tokens.LOGO_BADGE_RIM
    }
    private val image = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val fit = RectF()
    private var logo: Logo? = null

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun set(logo: Logo?) {
        if (logo === this.logo) return
        val before = widthFor(this.logo, height)
        this.logo = logo
        // Laid out again only when the plate's width changes (another channel's logo of another shape).
        if (height == 0 || widthFor(logo, height) != before) requestLayout()
        invalidate()
    }

    private fun widthFor(logo: Logo?, h: Int): Int =
        LogoPlate.badgeWidth(logo?.bitmap?.width ?: 0, logo?.bitmap?.height ?: 0, h, padX, padY, minW, maxW)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = resolveSize(defaultH, heightMeasureSpec)
        setMeasuredDimension(resolveSize(widthFor(logo, h), widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        val l = logo ?: return
        val half = rim.strokeWidth / 2
        box.set(half, half, width - half, height - half)
        plate.color = if (l.light) Tokens.LOGO_BADGE_PLATE_LIGHT else Tokens.LOGO_BADGE_PLATE
        canvas.drawRoundRect(box, radius, radius, plate)
        if (!l.light) canvas.drawRoundRect(box, radius, radius, rim)
        val bmp = l.bitmap
        val s = LogoPlate.fitScale(bmp.width, bmp.height, width - 2 * padX, height - 2 * padY)
        if (s <= 0f) return
        val bw = bmp.width * s
        val bh = bmp.height * s
        fit.set((width - bw) / 2, (height - bh) / 2, (width + bw) / 2, (height + bh) / 2)
        canvas.drawBitmap(bmp, null, fit, image)
    }
}
