package app.fireplayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.TextView

/**
 * The end-of-episode buttons, drawn the way Netflix draws them: a flat pill, white with dark text when chosen,
 * grey and see-through otherwise. With [counting], the pale button fills with white from the left as the countdown
 * runs to 1, which is when the next episode starts (or Exit leaves). With the glass look, a counting button stays
 * clear glass and fills with the glossy white of a selected glass button, its words dark over the white and white
 * over the glass, with the blue ring while it's selected.
 */
class CountdownPill @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : TextView(context, attrs) {

    /**
     * Part of the countdown done, 0 to 1, read again on every frame drawn while it keeps moving, so the fill glides
     * instead of jumping a step at a time. [invalidate] wakes it up again after it stood still (paused).
     */
    var countdown: (() -> Float)? = null
        set(value) {
            field = value
            drawn = -1f
            invalidate()
        }

    /** Whether this button shows the countdown. */
    var counting = false
        set(value) {
            field = value
            refreshDrawableState()
            refreshColors()
        }

    /** Fully round ends, a pod, instead of the flat end-card shape. */
    var pod = false
        set(value) {
            field = value
            invalidate()
        }

    /** Always drawn as the chosen button (white, dark text): for a button that is the only thing on screen. */
    var chosen = false
        set(value) {
            field = value
            refreshColors()
        }

    /**
     * With the glass look: unselected, it's a clear glass pod like the other buttons (its background, set by the
     * player), instead of the flat grey; chosen, focused or counting, it's drawn as before.
     */
    var glass = false
        set(value) {
            field = value
            refreshDrawableState()
            invalidate()
        }

    private var drawn = -1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val shape = Path()
    private val filled = Path()
    private val cut = Path()
    private val density = resources.displayMetrics.density
    private val radius = 4 * density
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var glassFill: LinearGradient? = null
    private var glassFillHeight = -1
    private var iconDark: Boolean? = null

    init {
        setWillNotDraw(false)
        isFocusable = true
        background = null
        refreshColors()
    }

    /**
     * Counting on glass, the glass under the fill stays clear (its own selected white would hide the fill); the blue
     * ring drawn here shows it's selected.
     */
    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        val state = super.onCreateDrawableState(extraSpace)
        if (!(glass && counting)) return state
        return state.filter { it != android.R.attr.state_focused }.toIntArray()
    }

    private fun refreshColors() {
        iconDark = null
        val light = (counting && !glass) || isFocused || chosen
        setTextColor(if (light) DARK else Color.WHITE)
        compoundDrawablesRelative.forEach { it?.mutate()?.setTint(currentTextColor) }
        invalidate()
    }

    override fun setCompoundDrawablesRelative(
        start: android.graphics.drawable.Drawable?,
        top: android.graphics.drawable.Drawable?,
        end: android.graphics.drawable.Drawable?,
        bottom: android.graphics.drawable.Drawable?,
    ) {
        super.setCompoundDrawablesRelative(start, top, end, bottom)
        start?.mutate()?.setTint(currentTextColor)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerLoneIcon()
    }

    /**
     * A button that is only an icon sits its icon in the middle of the pill, rather than at the left edge where a
     * label would follow it. Only the drawn position moves; the measured size stays the icon's. With words (Next
     * episode has them), the icon stays in front of them.
     */
    private fun centerLoneIcon() {
        if (!text.isNullOrEmpty()) return
        val icon = compoundDrawablesRelative[0] ?: return
        val iconWidth = icon.bounds.width()
        val room = width - paddingStart - paddingEnd
        if (iconWidth <= 0 || room <= iconWidth) return
        val left = (room - iconWidth) / 2
        icon.setBounds(left, icon.bounds.top, left + iconWidth, icon.bounds.bottom)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (glass && counting) {
            drawGlassCount(canvas)
            return
        }
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val corner = if (pod) height / 2f else radius
        paint.color = when {
            (isFocused || chosen) && !counting -> Color.WHITE
            counting -> PALE_COUNTING
            else -> PALE
        }
        val plain = !isFocused && !chosen && !counting
        // On glass, the glass itself shows plain or selected (glossy white, blue ring), the same on every button.
        if (!(glass && (plain || isFocused))) canvas.drawRoundRect(rect, corner, corner, paint)
        val progress = if (counting) countdown?.invoke()?.coerceIn(0f, 1f) ?: 0f else 0f
        if (progress > 0f) {
            // The same rounded shape in white, cut off straight where the count has got to. Drawn as one smoothed
            // shape rather than clipped to whole pixels, so its edge glides between pixels instead of stepping.
            paint.color = Color.WHITE
            shape.reset()
            shape.addRoundRect(rect, corner, corner, Path.Direction.CW)
            cut.reset()
            cut.addRect(0f, 0f, width * progress, height.toFloat(), Path.Direction.CW)
            filled.op(shape, cut, Path.Op.INTERSECT)
            canvas.drawPath(filled, paint)
        }
        super.onDraw(canvas)
        // Next frame too, for as long as the count moves.
        if (counting && countdown != null && progress != drawn && isShown) postInvalidateOnAnimation()
        drawn = progress
    }

    /** Counting on glass: the clear glass (the background), the glossy white fill, the ring, then the two-tone words. */
    private fun drawGlassCount(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val corner = if (pod) height / 2f else radius
        val progress = countdown?.invoke()?.coerceIn(0f, 1f) ?: 0f
        val edge = width * progress
        if (progress > 0f) {
            if (glassFill == null || glassFillHeight != height) {
                glassFillHeight = height
                glassFill = LinearGradient(0f, 0f, 0f, height.toFloat(), GLOSS, GLOSS_STOPS, Shader.TileMode.CLAMP)
            }
            paint.shader = glassFill
            shape.reset()
            shape.addRoundRect(rect, corner, corner, Path.Direction.CW)
            cut.reset()
            cut.addRect(0f, 0f, edge, height.toFloat(), Path.Direction.CW)
            filled.op(shape, cut, Path.Op.INTERSECT)
            canvas.drawPath(filled, paint)
            paint.shader = null
        }
        if (isFocused || chosen) {
            // The blue ring and its soft glow, as on the other selected glass buttons.
            val ring = 2 * density
            ringPaint.strokeWidth = 4 * density
            ringPaint.color = GLOW
            rect.set(-ring, -ring, width + ring, height + ring)
            canvas.drawRoundRect(rect, corner + ring, corner + ring, ringPaint)
            ringPaint.strokeWidth = ring
            ringPaint.color = ACCENT
            rect.set(ring / 2, ring / 2, width - ring / 2, height - ring / 2)
            canvas.drawRoundRect(rect, corner - ring / 2, corner - ring / 2, ringPaint)
        }
        // The icon turns dark once the white has passed its middle.
        val icon = compoundDrawablesRelative[0]
        if (icon != null) {
            val dark = edge >= paddingStart + icon.bounds.exactCenterX()
            if (iconDark != dark) {
                iconDark = dark
                icon.mutate().setTint(if (dark) DARK else Color.WHITE)
            }
        }
        // The words dark over the white, white over the glass, the change exactly at the fill's edge.
        val words = getPaint()
        val at = edge - compoundPaddingLeft
        words.shader = LinearGradient(at - 0.5f, 0f, at + 0.5f, 0f, DARK, Color.WHITE, Shader.TileMode.CLAMP)
        super.onDraw(canvas)
        words.shader = null
        if (countdown != null && progress != drawn && isShown) postInvalidateOnAnimation()
        drawn = progress
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val scale = if (gainFocus) 1.08f else 1f
        animate().scaleX(scale).scaleY(scale).setDuration(120).setInterpolator(EASE).start()
        refreshColors()
    }

    private companion object {
        val EASE = android.view.animation.DecelerateInterpolator()
        const val PALE = 0x66333333
        // Light enough for the dark text, see-through enough to show the white filling it.
        const val PALE_COUNTING = 0xB3A6A6A6.toInt()
        const val DARK = 0xFF141414.toInt()
        const val ACCENT = 0xFF3D8BFF.toInt()
        const val GLOW = 0x403D8BFF
        // The glossy white of a selected glass button, top to bottom.
        val GLOSS = intArrayOf(0xFAFFFFFF.toInt(), 0xF0F2F5FF.toInt(), 0xE6DCE6FF.toInt())
        val GLOSS_STOPS = floatArrayOf(0f, 0.55f, 1f)
    }
}
