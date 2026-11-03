package app.fireplayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.TextView

/**
 * The end-of-episode buttons, drawn the way Netflix draws them: a flat pill, white with dark text when chosen,
 * grey and see-through otherwise. With [counting], the pale button fills with white from the left as the countdown
 * runs to 1, which is when the next episode starts.
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
            refreshColors()
        }

    private var drawn = -1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val radius = 4 * resources.displayMetrics.density

    init {
        setWillNotDraw(false)
        isFocusable = true
        background = null
        refreshColors()
    }

    private fun refreshColors() {
        val light = counting || isFocused
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

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = when {
            isFocused && !counting -> Color.WHITE
            counting -> PALE_COUNTING
            else -> PALE
        }
        canvas.drawRoundRect(rect, radius, radius, paint)
        val progress = if (counting) countdown?.invoke()?.coerceIn(0f, 1f) ?: 0f else 0f
        if (progress > 0f) {
            // The same rounded shape in white, cut off straight where the count has got to.
            paint.color = Color.WHITE
            canvas.save()
            canvas.clipRect(0f, 0f, width * progress, height.toFloat())
            canvas.drawRoundRect(rect, radius, radius, paint)
            canvas.restore()
        }
        super.onDraw(canvas)
        // Next frame too, for as long as the count moves.
        if (counting && countdown != null && progress != drawn && isShown) postInvalidateOnAnimation()
        drawn = progress
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val scale = if (gainFocus) 1.08f else 1f
        animate().scaleX(scale).scaleY(scale).setDuration(120).start()
        refreshColors()
    }

    private companion object {
        const val PALE = 0x66333333
        // Light enough for the dark text, see-through enough to show the white filling it.
        const val PALE_COUNTING = 0xB3A6A6A6.toInt()
        const val DARK = 0xFF141414.toInt()
    }
}
