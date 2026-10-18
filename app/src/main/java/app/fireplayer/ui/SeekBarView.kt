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
    private val thumbRadius = 5f * density
    private val focusedThumbRadius = 8.5f * density
    private val haloWidth = 5f * density
    /** Room the selected thumb and its halo need, so they're never cut off at the ends. */
    private val outerRadius = focusedThumbRadius + haloWidth
    private val accent = ContextCompat.getColor(context, R.color.accent)

    /** 0 = resting, 1 = selected; animated so the bar brightens and the thumb grows smoothly. */
    private var focusAmount = 0f
    private var focusAnimator: ValueAnimator? = null

    // Resting, the bar is quieter so it doesn't pull the eye from the film; selected, it comes up to full strength.
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bufferPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val jumpForwardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val jumpBackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    // Selected: a soft white ring around the knob, like light around a pressed button.
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(Color.argb(0x73, 0, 0, 0), Color.argb(0x40, 0, 0, 0), Color.TRANSPARENT),
            floatArrayOf(0f, 0.6f, 1f),
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
        val h = Math.ceil(((outerRadius + 1f * density) * 2).toDouble()).toInt()
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), h)
    }

    private fun lerp(a: Int, b: Int, f: Float) = (a + (b - a) * f).toInt()

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    override fun onDraw(canvas: Canvas) {
        val left = trackLeft()
        val w = trackWidth()
        val cy = height / 2f
        val f = focusAmount
        val bar = barHeight + (focusedBarHeight - barHeight) * f
        val top = cy - bar / 2
        val bottom = cy + bar / 2
        val r = bar / 2

        trackPaint.color = withAlpha(Color.WHITE, lerp(0x2E, 0x40, f))
        bufferPaint.color = withAlpha(Color.WHITE, lerp(0x47, 0x6B, f))
        progressPaint.color = withAlpha(accent, lerp(0xB8, 0xFF, f))
        jumpForwardPaint.color = withAlpha(accent, lerp(0x70, 0x99, f))
        jumpBackPaint.color = withAlpha(Color.WHITE, lerp(0x60, 0x80, f))
        thumbPaint.color = withAlpha(Color.WHITE, lerp(0xD9, 0xFF, f))

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
        // A soft shadow just below the knob, so it sits on the bar instead of looking pasted on.
        canvas.save()
        canvas.translate(x, cy + (0.5f + f) * density)
        val shadow = radius + (2.5f + 2f * f) * density
        canvas.scale(shadow, shadow)
        canvas.drawCircle(0f, 0f, 1f, shadowPaint)
        canvas.restore()
        if (f > 0f) {
            haloPaint.alpha = (0x2E * f).toInt()
            canvas.drawCircle(x, cy, radius + haloWidth * f, haloPaint)
        }
        canvas.drawCircle(x, cy, radius, thumbPaint)
    }
}
