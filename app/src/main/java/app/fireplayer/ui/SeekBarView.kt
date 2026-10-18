package app.fireplayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import app.fireplayer.R

/**
 * The time bar. Not focusable: seeking is done with the D-pad and the remote's
 * rewind / fast-forward keys, and a pending jump is drawn as its own segment.
 */
class SeekBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val barHeight = 4f * density
    private val thumbRadius = 6.5f * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x38, 255, 255, 255) }
    private val bufferPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x66, 255, 255, 255) }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val jumpForwardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent) and 0x00FFFFFF or (0x99 shl 24)
    }
    private val jumpBackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x80, 255, 255, 255) }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()

    var durationMs: Long = 0L
        set(v) { field = v; invalidate() }
    var positionMs: Long = 0L
        set(v) { field = v; invalidate() }
    var bufferedMs: Long = 0L
        set(v) { field = v; invalidate() }
    /** Where a pending seek will land, or null when not seeking. */
    var targetMs: Long? = null
        set(v) { field = v; invalidate() }

    /** Horizontal centre of the thumb, relative to this view's left edge. */
    fun thumbX(): Float {
        val shown = targetMs ?: positionMs
        return trackLeft() + trackWidth() * fraction(shown)
    }

    private fun trackLeft() = thumbRadius
    private fun trackWidth() = (width - 2 * thumbRadius).coerceAtLeast(0f)

    private fun fraction(ms: Long): Float =
        if (durationMs <= 0L) 0f else (ms.toFloat() / durationMs).coerceIn(0f, 1f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (thumbRadius * 2 + 2 * density).toInt()
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        val left = trackLeft()
        val w = trackWidth()
        val cy = height / 2f
        val top = cy - barHeight / 2
        val bottom = cy + barHeight / 2
        val r = barHeight / 2

        rect.set(left, top, left + w, bottom)
        canvas.drawRoundRect(rect, r, r, trackPaint)

        rect.set(left, top, left + w * fraction(bufferedMs), bottom)
        canvas.drawRoundRect(rect, r, r, bufferPaint)

        val target = targetMs
        val playedEnd = fraction(if (target != null) minOf(target, positionMs) else positionMs)
        rect.set(left, top, left + w * playedEnd, bottom)
        canvas.drawRoundRect(rect, r, r, progressPaint)

        if (target != null && target != positionMs) {
            val a = fraction(minOf(target, positionMs))
            val b = fraction(maxOf(target, positionMs))
            rect.set(left + w * a, top, left + w * b, bottom)
            canvas.drawRoundRect(rect, r, r, if (target > positionMs) jumpForwardPaint else jumpBackPaint)
        }

        canvas.drawCircle(thumbX(), cy, thumbRadius, thumbPaint)
    }
}
