package app.fireplayer.ui

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
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
    private val focusedBarHeight = 6f * density
    private val thumbRadius = 6.5f * density
    private val focusedThumbRadius = 10f * density
    private val borderWidth = 3f * density
    private val glowRadius = 26f * density
    /** Room the selected thumb needs, so it's never cut off at the ends. The glow may spill past the bar. */
    private val outerRadius = focusedThumbRadius + 2f * density
    private val accent = ContextCompat.getColor(context, R.color.accent)

    /** 0 = resting, 1 = selected; animated so the thumb grows smoothly. */
    private var focusAmount = 0f
    private var focusAnimator: ValueAnimator? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x38, 255, 255, 255) }
    private val bufferPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x66, 255, 255, 255) }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val jumpForwardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent) and 0x00FFFFFF or (0x99 shl 24)
    }
    private val jumpBackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(0x80, 255, 255, 255) }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.focus_fill) }
    // Selected: the same white-with-blue-border look as the selected buttons, with a soft blue glow.
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, glowRadius,
            intArrayOf(accent and 0x00FFFFFF or (0x80 shl 24), accent and 0x00FFFFFF or (0x2E shl 24), accent and 0x00FFFFFF),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, focusedThumbRadius + 4f * density,
            intArrayOf(Color.argb(0x66, 0, 0, 0), Color.argb(0x66, 0, 0, 0), Color.TRANSPARENT),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
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

    private fun trackLeft() = outerRadius
    private fun trackWidth() = (width - 2 * outerRadius).coerceAtLeast(0f)

    private fun fraction(ms: Long): Float = fraction(ms.toFloat())

    private fun fraction(ms: Float): Float =
        if (durationMs <= 0L) 0f else (ms / durationMs).coerceIn(0f, 1f)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        focusAnimator?.cancel()
        focusAnimator = ValueAnimator.ofFloat(focusAmount, if (gainFocus) 1f else 0f).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                focusAmount = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = Math.ceil(((focusedThumbRadius + 3f * density) * 2).toDouble()).toInt()
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        val left = trackLeft()
        val w = trackWidth()
        val cy = height / 2f
        val f = focusAmount
        val bar = barHeight + (focusedBarHeight - barHeight) * f
        val top = cy - bar / 2
        val bottom = cy + bar / 2
        val r = bar / 2

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

        val x = thumbX()
        val radius = thumbRadius + (focusedThumbRadius - thumbRadius) * f
        if (f > 0f) {
            canvas.save()
            canvas.translate(x, cy)
            glowPaint.alpha = (255 * f).toInt()
            canvas.drawCircle(0f, 0f, glowRadius, glowPaint)
            canvas.translate(0f, 1.5f * density * f)
            shadowPaint.alpha = (255 * f).toInt()
            canvas.drawCircle(0f, 0f, radius + 4f * density, shadowPaint)
            canvas.restore()
        }
        canvas.drawCircle(x, cy, radius, thumbPaint)
        if (f > 0f) {
            borderPaint.alpha = (255 * f).toInt()
            canvas.drawCircle(x, cy, radius - borderWidth / 2, borderPaint)
        }
    }
}
