package app.fireplayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.TextView

/**
 * The end-of-episode buttons, drawn the way Netflix draws them: a flat pill, white with dark text when chosen,
 * grey and see-through otherwise. With [counting], the pale button fills with white from the left as [progress]
 * runs to 1, which is when the next episode starts.
 */
class CountdownPill @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : TextView(context, attrs) {

    /** Part of the countdown done, 0 to 1. */
    var progress = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (v != field) {
                field = v
                invalidate()
            }
        }

    /** Whether this button shows the countdown. */
    var counting = false
        set(value) {
            field = value
            refreshColors()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()
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
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        paint.color = when {
            isFocused && !counting -> Color.WHITE
            counting -> PALE_COUNTING
            else -> PALE
        }
        canvas.drawRect(rect, paint)
        if (counting && progress > 0f) {
            paint.color = Color.WHITE
            canvas.drawRect(0f, 0f, width * progress, height.toFloat(), paint)
        }
        canvas.restore()
        super.onDraw(canvas)
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
