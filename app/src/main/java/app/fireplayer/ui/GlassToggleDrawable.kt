package app.fireplayer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import kotlin.math.roundToInt

/**
 * The switch an on/off setting shows with the Glass look, in place of the words "On" and "Off" (iOS style:
 * a blue glowing track when on, a faint one when off, a white knob on that side). Fills its 34 x 20 dp view.
 *
 * Its view copies the row's state (duplicateParentState): on the white selected row an off track turns dark
 * so it stays visible. The on glow reaches about 8dp past the view, so the row mustn't clip it.
 */
class GlassToggleDrawable(context: Context, private val on: Boolean) : Drawable() {

    private val density = context.resources.displayMetrics.density
    private val pad = GlassBakes.togglePad(density)
    private val fade = FocusFade()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var alphaValue = 255
    private var bakeWidth = -1
    private var bakeHeight = -1
    private var plain: Bitmap? = null
    private var onWhite: Bitmap? = null

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        if (b.width() != bakeWidth || b.height() != bakeHeight) {
            bakeWidth = b.width()
            bakeHeight = b.height()
            plain = null
            onWhite = null
        }
        val a = alphaValue / 255f
        val t = fade.value(SystemClock.uptimeMillis())
        if (t < 1f) drawBake(canvas, b, rowFocused = false, strength = a * (1f - t))
        if (t > 0f) drawBake(canvas, b, rowFocused = true, strength = a * t)
        if (fade.running) invalidateSelf()
    }

    private fun drawBake(canvas: Canvas, b: Rect, rowFocused: Boolean, strength: Float) {
        if (strength <= 0f) return
        val bake = (if (rowFocused) onWhite else plain)
            ?: GlassBakes.toggle(on, rowFocused, b.width(), b.height(), density).also {
                if (rowFocused) onWhite = it else plain = it
            }
        paint.alpha = (255 * strength).roundToInt()
        canvas.drawBitmap(bake, (b.left - pad).toFloat(), (b.top - pad).toFloat(), paint)
    }

    /** Only an off switch looks different on the selected row. */
    override fun isStateful(): Boolean = !on

    override fun onStateChange(state: IntArray): Boolean {
        if (on || !fade.set(state.contains(android.R.attr.state_focused))) return false
        invalidateSelf()
        return true
    }

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
}
