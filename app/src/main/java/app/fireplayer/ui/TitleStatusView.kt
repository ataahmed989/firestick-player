package app.fireplayer.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.fireplayer.R
import app.fireplayer.titles.TitleBook
import app.fireplayer.titles.TitleStatus

/**
 * How far TiviMate's titles are, under their switch in Settings: a slim blue bar (renamed and kept as they are, of
 * everything listed). Each playlist's counts are in its own row under Playlists; a line here only says when there are
 * none yet. Not a row: the D-pad passes it by.
 */
class TitleStatusView(context: Context) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private val bar = SlimBar(context)
    private val line = TextView(context).apply {
        setTextColor(ContextCompat.getColor(context, R.color.text_muted))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        setLineSpacing(2f * density, 1f)
    }
    private var shown: CharSequence? = null

    init {
        orientation = VERTICAL
        // In line with the rows' labels.
        val inset = (12 * density).toInt()
        setPadding(inset, 0, inset, 0)
        addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (4 * density).toInt()))
        addView(line, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (5 * density).toInt()
        })
        // Holds its height until the first count arrives.
        line.text = " "
    }

    /** Shows [progress] (null until it's been read), moving the bar there smoothly when [animate]. */
    fun show(progress: List<TitleBook.Progress>?, animate: Boolean) {
        val text: CharSequence? = when {
            progress == null -> " "
            progress.isEmpty() -> "No playlists yet"
            else -> null
        }
        line.visibility = if (text == null) GONE else VISIBLE
        if (text != null && text.toString() != shown?.toString()) {
            shown = text
            line.text = text
        }
        bar.set(if (progress == null) 0f else TitleStatus.fraction(progress), animate && isAttachedToWindow)
    }

    /** A rounded track with a blue fill; the glass look gives the fill a lighter end, like its glowing switches. */
    private class SlimBar(context: Context) : View(context) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, if (Glass.on) R.color.progress_track_glass else R.color.progress_track)
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.accent) }
        private val glass = Glass.on
        private val fillEnd = ContextCompat.getColor(context, R.color.progress_fill_end)
        private val rect = RectF()
        private var fraction = 0f
        private var target = -1f
        private var anim: ValueAnimator? = null

        fun set(value: Float, animate: Boolean) {
            val to = value.coerceIn(0f, 1f)
            if (to == target) return
            target = to
            anim?.cancel()
            if (!animate) {
                fraction = to
                invalidate()
                return
            }
            anim = ValueAnimator.ofFloat(fraction, to).apply {
                duration = 450
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    fraction = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (glass) fill.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, fill.color, fillEnd, Shader.TileMode.CLAMP)
        }

        override fun onDetachedFromWindow() {
            anim?.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val r = h / 2
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, r, r, track)
            if (fraction <= 0f) return
            // Never thinner than its own height, so a start still shows as a dot.
            rect.set(0f, 0f, (w * fraction).coerceAtLeast(h), h)
            canvas.drawRoundRect(rect, r, r, fill)
        }
    }
}
