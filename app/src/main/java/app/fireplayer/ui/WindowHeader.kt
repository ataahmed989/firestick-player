package app.fireplayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import app.fireplayer.R
import kotlin.math.roundToInt

/**
 * A window's header band, behind its title line ([Glass.header]): the settings window's ([SidePanel]) and a hold
 * window's ([app.fireplayer.live.ui.HoldShape]). It spans the window's top, inside its outline, following its top
 * corners ([kind]'s), down to the foot of the title. Glass: a cool blue tint over the window's own glass (which shows
 * what's behind it, blurred), a light across its upper left, a bright reflective upper edge and a fine line at its
 * foot ([GlassStyle] HEADER_*). Classic: one flat colour (window_header) and the line. Not selectable: it's only the
 * title's background.
 *
 * Its shapes and gradients are made when its size changes (the window laid out), never while it's drawn: a frame
 * draws two or three paths and a line.
 */
class WindowHeader(private val context: Context, private val kind: Glass.Kind, val glass: Boolean) : Drawable() {

    private val density = context.resources.displayMetrics.density
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GlassStyle.HEADER_EDGE }
    private val line = Paint().apply {
        color = if (glass) GlassStyle.HEADER_LINE else ContextCompat.getColor(context, R.color.group_divider)
    }
    private val classicFill = ContextCompat.getColor(context, R.color.window_header)
    private val lineAlpha = Color.alpha(line.color)
    private val shape = Path()
    private val lower = Path()
    private val crescent = Path()
    private val area = RectF()
    private val lineRect = RectF()
    private val radii = FloatArray(8)
    private var alphaValue = 255

    init {
        if (!glass) tint.color = classicFill
    }

    override fun onBoundsChange(bounds: Rect) {
        shape.reset()
        crescent.reset()
        val inset = GlassStyle.HEADER_INSET_DP * density
        area.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom.toFloat())
        if (area.width() <= 0f || area.height() <= 0f) return
        // The window's corners (it's at least as tall as its title), less the room inside its outline.
        val r = (Glass.cornerRadius(context, kind, bounds.width(), bounds.width(), glass) - inset).coerceIn(0f, area.height())
        for (i in 0 until 4) radii[i] = r
        for (i in 4 until 8) radii[i] = 0f
        shape.addRoundRect(area, radii, Path.Direction.CW)
        val hair = GlassStyle.hairlinePx(density)
        lineRect.set(area.left, area.bottom - hair, area.right, area.bottom)
        if (!glass) return
        tint.shader = LinearGradient(
            0f, area.top, 0f, area.bottom,
            GlassStyle.HEADER_TINT_TOP, GlassStyle.HEADER_TINT_BOTTOM, Shader.TileMode.CLAMP,
        )
        val reach = area.width() / 2f
        sheen.shader = LinearGradient(
            area.left, area.top, area.left + reach, area.top + reach,
            GlassStyle.HEADER_SHEEN, 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
        // The upper edge follows the corners: the band less itself moved down a little.
        lower.reset()
        lower.addRoundRect(area, radii, Path.Direction.CW)
        lower.offset(0f, GlassStyle.HEADER_EDGE_DP * density)
        if (!crescent.op(shape, lower, Path.Op.DIFFERENCE)) crescent.reset()
    }

    override fun draw(canvas: Canvas) {
        if (shape.isEmpty) return
        canvas.drawPath(shape, tint)
        if (glass) {
            canvas.drawPath(shape, sheen)
            canvas.drawPath(crescent, edge)
        }
        canvas.drawRect(lineRect, line)
    }

    override fun setAlpha(alpha: Int) {
        if (alpha == alphaValue) return
        alphaValue = alpha
        tint.alpha = if (glass) alpha else scaled(Color.alpha(classicFill), alpha)
        sheen.alpha = alpha
        edge.alpha = scaled(Color.alpha(GlassStyle.HEADER_EDGE), alpha)
        line.alpha = scaled(lineAlpha, alpha)
        invalidateSelf()
    }

    private fun scaled(base: Int, alpha: Int): Int = (base * alpha / 255f).roundToInt()

    override fun getAlpha(): Int = alphaValue

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /** The title keeps its own padding. */
    override fun getPadding(padding: Rect): Boolean {
        padding.setEmpty()
        return false
    }
}
