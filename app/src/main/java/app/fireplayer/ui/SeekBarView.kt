package app.fireplayer.ui

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import app.fireplayer.R

/**
 * The time bar. Seeking is done with the D-pad (when the bar is selected, or while the
 * controls are hidden) and the remote's rewind / fast-forward keys. A pending jump is drawn
 * as its own segment, and the thumb glides to each new target.
 */
class SeekBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val barHeight = 4f * density
    private val thumbRadius = 6.5f * density
    private val focusedThumbRadius = 8.5f * density

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
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val rect = RectF()

    var durationMs: Long = 0L
        set(v) { field = v; invalidate() }
    var positionMs: Long = 0L
        set(v) { field = v; invalidate() }
    var bufferedMs: Long = 0L
        set(v) { field = v; invalidate() }
    /** Called on every frame while the thumb glides, so the "+4:00" card can follow it. */
    var onThumbMoved: (() -> Unit)? = null

    // The target as drawn; it glides towards [targetMs].
    private var shownTargetMs: Float? = null
    private var animator: ValueAnimator? = null

    /** Where a pending seek will land, or null when not seeking. */
    var targetMs: Long? = null
        set(v) {
            if (v == field) return
            field = v
            animator?.cancel()
            if (v == null) {
                shownTargetMs = null
                invalidate()
                return
            }
            val from = shownTargetMs ?: positionMs.toFloat()
            animator = ValueAnimator.ofFloat(from, v.toFloat()).apply {
                duration = 160
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    shownTargetMs = it.animatedValue as Float
                    invalidate()
                    onThumbMoved?.invoke()
                }
                start()
            }
        }

    init {
        isFocusable = true
    }

    /** Horizontal centre of the thumb, relative to this view's left edge. */
    fun thumbX(): Float {
        val shown = shownTargetMs ?: positionMs.toFloat()
        return trackLeft() + trackWidth() * fraction(shown)
    }

    private fun trackLeft() = focusedThumbRadius
    private fun trackWidth() = (width - 2 * focusedThumbRadius).coerceAtLeast(0f)

    private fun fraction(ms: Long): Float = fraction(ms.toFloat())

    private fun fraction(ms: Float): Float =
        if (durationMs <= 0L) 0f else (ms / durationMs).coerceIn(0f, 1f)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (focusedThumbRadius * 2 + 6 * density).toInt()
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

        val target = shownTargetMs
        val position = positionMs.toFloat()
        val playedEnd = fraction(if (target != null) minOf(target, position) else position)
        rect.set(left, top, left + w * playedEnd, bottom)
        canvas.drawRoundRect(rect, r, r, progressPaint)

        if (target != null && target != position) {
            val a = fraction(minOf(target, position))
            val b = fraction(maxOf(target, position))
            rect.set(left + w * a, top, left + w * b, bottom)
            canvas.drawRoundRect(rect, r, r, if (target > position) jumpForwardPaint else jumpBackPaint)
        }

        if (isFocused) {
            canvas.drawCircle(thumbX(), cy, focusedThumbRadius, thumbPaint)
            canvas.drawCircle(thumbX(), cy, focusedThumbRadius + 2.5f * density, ringPaint)
        } else {
            canvas.drawCircle(thumbX(), cy, thumbRadius, thumbPaint)
        }
    }
}
