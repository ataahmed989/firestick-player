package app.fireplayer.vod.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.ceil
import kotlin.math.max

/**
 * The logo cards' one surface (Home's streaming services and Disney+'s studios alike): a rounded card of the rail's
 * window for the look that's on ([VodSizes.logoFill]: on Glass the windows' dark tint with the wallpaper showing
 * through it, as the rail at rest; on Classic the rail's solid colour), and over that, the same on both looks, a faint
 * broad reflection over its upper left, a fine light rim that fades toward the bottom and a soft close shadow just
 * outside it (outside only, so the glass isn't darkened by it). Its look is [VodSizes]' (LOGO_*). It's drawn once per
 * card size, look and fill (Rail, cards & header's tint and charcoal, Settings › Look), at the chosen card's size so that one is
 * sharp, and every card of that size draws the same picture: nothing is built, copied or blurred while drawing. Main
 * thread.
 */
internal class LogoSurface private constructor(
    private val bitmap: Bitmap, private val w: Int, private val h: Int, pad: Float, val glass: Boolean, private val fill: Int,
) {

    /** Where the picture goes for a card at 0, 0: the card and the shadow's room round it. */
    private val box = RectF(-pad, -pad, w + pad, h + pad)

    fun draw(canvas: Canvas, paint: Paint) = canvas.drawBitmap(bitmap, null, box, paint)

    /** Whether it's the surface of the look [glass] as that look is now (its fill: Rail, cards & header's colour layer). */
    fun isFor(glass: Boolean): Boolean = this.glass == glass && fill == VodSizes.logoFill(glass)

    companion object {
        private var last: LogoSurface? = null

        /** The surface of a card [w] x [h] px on a screen of [density], in the Glass look ([glass]) or the Classic. */
        fun of(w: Int, h: Int, density: Float, glass: Boolean): LogoSurface {
            last?.let { if (it.w == w && it.h == h && it.isFor(glass)) return it }
            return make(w, h, density, glass).also { last = it }
        }

        private fun make(w: Int, h: Int, density: Float, glass: Boolean): LogoSurface {
            val pad = ceil(VodSizes.logoShadowReach() * density)
            val k = 1f + VodSizes.LOGO_GROW
            val bitmap = Bitmap.createBitmap(ceil((w + 2 * pad) * k).toInt(), ceil((h + 2 * pad) * k).toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(bitmap)
            c.scale(k, k)
            c.translate(pad, pad)
            val r = h * VodSizes.LOGO_RADIUS_SHARE
            val card = RectF(0f, 0f, w.toFloat(), h.toFloat())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // The shadow: the card's shape, a little lower, blurred, and none of it under the card itself.
            paint.color = VodSizes.LOGO_SHADOW
            paint.maskFilter = BlurMaskFilter(VodSizes.LOGO_SHADOW_BLUR * density, BlurMaskFilter.Blur.NORMAL)
            val dy = VodSizes.LOGO_SHADOW_DY * density
            card.offset(0f, dy)
            c.drawRoundRect(card, r, r, paint)
            card.offset(0f, -dy)
            paint.maskFilter = null
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            c.drawRoundRect(card, r, r, paint)
            paint.xfermode = null

            val fill = VodSizes.logoFill(glass)
            paint.color = fill
            c.drawRoundRect(card, r, r, paint)

            // The reflection: light from the upper left corner, gone well before the card's middle.
            paint.color = 0xFF000000.toInt()
            paint.shader = RadialGradient(0f, 0f, w * VodSizes.LOGO_SHEEN_REACH, VodSizes.LOGO_SHEEN, 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawRoundRect(card, r, r, paint)

            // The rim, just inside the edge: a physical pixel at least, at the chosen card's size.
            val stroke = max(VodSizes.LOGO_RIM * density, 1f / k)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), VodSizes.LOGO_RIM_TOP, VodSizes.LOGO_RIM_BOTTOM, Shader.TileMode.CLAMP)
            card.inset(stroke / 2f, stroke / 2f)
            c.drawRoundRect(card, r - stroke / 2f, r - stroke / 2f, paint)
            return LogoSurface(bitmap, w, h, pad, glass, fill)
        }
    }
}
