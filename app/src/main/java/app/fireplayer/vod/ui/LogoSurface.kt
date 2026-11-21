package app.fireplayer.vod.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.ceil
import kotlin.math.max

/**
 * The logo cards' one surface (Home's streaming services and Disney+'s studios alike): a rounded card going from
 * slate blue down to charcoal, a faint broad reflection over its upper left, a fine cool-grey rim that fades toward
 * the bottom and a soft close shadow just outside it. Its look is [VodSizes]' (LOGO_*). It's drawn once per card size,
 * at the chosen card's size so that one is sharp, and every card of that size draws the same picture: nothing is
 * built while drawing. Main thread.
 */
internal class LogoSurface private constructor(private val bitmap: Bitmap, private val w: Int, private val h: Int, pad: Float) {

    /** Where the picture goes for a card at 0, 0: the card and the shadow's room round it. */
    private val box = RectF(-pad, -pad, w + pad, h + pad)

    fun draw(canvas: Canvas, paint: Paint) = canvas.drawBitmap(bitmap, null, box, paint)

    companion object {
        private var last: LogoSurface? = null

        /** The surface of a card [w] x [h] px on a screen of [density]. */
        fun of(w: Int, h: Int, density: Float): LogoSurface {
            last?.let { if (it.w == w && it.h == h) return it }
            return make(w, h, density).also { last = it }
        }

        private fun make(w: Int, h: Int, density: Float): LogoSurface {
            val pad = ceil(VodSizes.logoShadowReach() * density)
            val k = 1f + VodSizes.LOGO_GROW
            val bitmap = Bitmap.createBitmap(ceil((w + 2 * pad) * k).toInt(), ceil((h + 2 * pad) * k).toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(bitmap)
            c.scale(k, k)
            c.translate(pad, pad)
            val r = h * VodSizes.LOGO_RADIUS_SHARE
            val card = RectF(0f, 0f, w.toFloat(), h.toFloat())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // The shadow: the card's shape, a little lower, blurred.
            paint.color = VodSizes.LOGO_SHADOW
            paint.maskFilter = BlurMaskFilter(VodSizes.LOGO_SHADOW_BLUR * density, BlurMaskFilter.Blur.NORMAL)
            val dy = VodSizes.LOGO_SHADOW_DY * density
            card.offset(0f, dy)
            c.drawRoundRect(card, r, r, paint)
            card.offset(0f, -dy)
            paint.maskFilter = null

            paint.color = 0xFF000000.toInt()
            paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), VodSizes.LOGO_FILL_TOP, VodSizes.LOGO_FILL_BOTTOM, Shader.TileMode.CLAMP)
            c.drawRoundRect(card, r, r, paint)

            // The reflection: light from the upper left corner, gone well before the card's middle.
            paint.shader = RadialGradient(0f, 0f, w * VodSizes.LOGO_SHEEN_REACH, VodSizes.LOGO_SHEEN, 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawRoundRect(card, r, r, paint)

            // The rim, just inside the edge: a physical pixel at least, at the chosen card's size.
            val stroke = max(VodSizes.LOGO_RIM * density, 1f / k)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), VodSizes.LOGO_RIM_TOP, VodSizes.LOGO_RIM_BOTTOM, Shader.TileMode.CLAMP)
            card.inset(stroke / 2f, stroke / 2f)
            c.drawRoundRect(card, r - stroke / 2f, r - stroke / 2f, paint)
            return LogoSurface(bitmap, w, h, pad)
        }
    }
}
