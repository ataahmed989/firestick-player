package app.fireplayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import app.fireplayer.R
import kotlin.math.min

/**
 * The rows of a section in a settings window drawn as one card: the rows touch, the card has rounded ends and a
 * light rim, thin lines part the rows, and the selected row is the usual white pill over it. A nested row (an
 * option under the setting that turns it on, [asSubRow]) is indented and marked with a dot on a slightly darker
 * band.
 *
 * The rows stay where they are in the window's list, so the D-pad and the scrolling work as before: each row only
 * gets a background that draws its own slice of the card ([Segment]).
 */
object RowGroups {

    enum class Pos { SINGLE, FIRST, MIDDLE, LAST }

    /** How far a nested row's text moves right, past the dot. */
    const val SUB_INSET_DP = 14

    /**
     * Joins each run of rows in [list] into a card. Called after a window's rows are made; anything that isn't a
     * row (a heading, a note, a bar) ends a card.
     */
    fun apply(list: LinearLayout) {
        val context = list.context
        val density = context.resources.displayMetrics.density
        var i = 0
        while (i < list.childCount) {
            if (!isMember(list.getChildAt(i))) { i++; continue }
            var end = i
            while (end + 1 < list.childCount && isMember(list.getChildAt(end + 1))) end++
            for (k in i..end) {
                val pos = when {
                    i == end -> Pos.SINGLE
                    k == i -> Pos.FIRST
                    k == end -> Pos.LAST
                    else -> Pos.MIDDLE
                }
                val prev = if (k > i) list.getChildAt(k - 1) else null
                style(list.getChildAt(k), pos, density, prev)
                // The line above a row hides while the row above it is selected, so the white pill stands clear.
                if (k < end) {
                    val next = list.getChildAt(k + 1)
                    val row = focusRow(list.getChildAt(k))
                    val old = row.onFocusChangeListener
                    row.setOnFocusChangeListener { v, has -> old?.onFocusChange(v, has); next.invalidate() }
                }
            }
            i = end + 1
        }
    }

    /** The part of a member that takes focus: the row itself, or the row of a row with a button beside it. */
    private fun focusRow(view: View): View =
        if (view.tag == ROW_PAIR && view is ViewGroup && view.childCount > 0) view.getChildAt(0) else view

    private fun isMember(view: View): Boolean = view.getTag(R.id.group_row) == true && view.visibility == View.VISIBLE

    private fun style(view: View, pos: Pos, density: Float, prev: View?) {
        val context = view.context
        val pair = view.tag == ROW_PAIR && view is ViewGroup && view.childCount > 0
        val sub = view.getTag(R.id.sub_row) == true || (pair && (view as ViewGroup).getChildAt(0).getTag(R.id.sub_row) == true)
        if (view.tag == ROW_PAIR && view is ViewGroup && view.childCount > 0) {
            // A row with a small button beside it: the card goes behind both, the row only lights up when selected,
            // and the button keeps its own small glass shape a little inside the card.
            keepPadding(view) { view.background = Segment(context, Glass.on, pos, sub, null, prev) }
            val row = view.getChildAt(0)
            keepPadding(row) { row.background = focusOnly(context) }
            for (c in 1 until view.childCount) {
                val button = view.getChildAt(c)
                (button.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                    val m = (3 * density).toInt()
                    it.topMargin = m
                    it.bottomMargin = m
                    it.rightMargin = m
                    it.marginEnd = m
                    button.layoutParams = it
                }
            }
        } else {
            keepPadding(view) { view.background = Segment(context, Glass.on, pos, sub, focusOnly(context), prev) }
        }
        (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            val edge = (2 * density).toInt()
            it.topMargin = if (pos == Pos.FIRST || pos == Pos.SINGLE) edge else 0
            it.bottomMargin = if (pos == Pos.LAST || pos == Pos.SINGLE) edge else 0
            view.layoutParams = it
        }
    }

    private inline fun keepPadding(view: View, block: () -> Unit) {
        val l = view.paddingLeft
        val t = view.paddingTop
        val r = view.paddingRight
        val b = view.paddingBottom
        block()
        view.setPadding(l, t, r, b)
    }

    /** The selected look alone, glass or classic: the card draws the rest. */
    private fun focusOnly(context: Context): Drawable =
        if (Glass.on) {
            (Glass.drawable(context, Glass.Kind.ROW) as GlassDrawable).apply { focusOnly = true }
        } else {
            StateListDrawable().apply {
                setEnterFadeDuration(140)
                setExitFadeDuration(140)
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                    cornerRadius = 9 * context.resources.displayMetrics.density
                    setColor(ContextCompat.getColor(context, R.color.focus_fill))
                })
            }
        }

    /** One row's slice of the card, with the row's own selected look ([inner]) drawn over it. */
    private class Segment(
        context: Context,
        private val glass: Boolean,
        private val pos: Pos,
        private val sub: Boolean,
        private val inner: Drawable?,
        /** The row above in the same card, whose selection hides the line between them. */
        private val above: View?,
    ) : Drawable(), Drawable.Callback {

        private val density = context.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, if (glass) R.color.group_fill_glass else R.color.row_fill)
        }
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.75f * density
            color = ContextCompat.getColor(context, R.color.group_rim_glass)
        }
        private val divider = Paint().apply { color = ContextCompat.getColor(context, R.color.group_divider) }
        private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.group_sub_shade) }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.group_sub_dot) }
        private val shape = Path()
        private val rimClip = RectF()
        private var focused = false
        private var alphaValue = 255

        init {
            inner?.callback = this
        }

        override fun onBoundsChange(bounds: Rect) {
            inner?.bounds = bounds
            val r = if (glass) min(16f * density, min(bounds.width(), bounds.height()) / 2f) else 9f * density
            val top = if (pos == Pos.FIRST || pos == Pos.SINGLE) r else 0f
            val bottom = if (pos == Pos.LAST || pos == Pos.SINGLE) r else 0f
            shape.reset()
            shape.addRoundRect(
                RectF(bounds),
                floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom),
                Path.Direction.CW,
            )
            // The rim runs down the sides of every slice but across only the card's two ends.
            val half = rim.strokeWidth
            rimClip.set(
                bounds.left - half, bounds.top + if (top > 0f) -half else half,
                bounds.right + half, bounds.bottom + if (bottom > 0f) half else -half,
            )
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            canvas.drawPath(shape, fill)
            if (glass) {
                canvas.save()
                canvas.clipRect(rimClip)
                canvas.drawPath(shape, rim)
                canvas.restore()
            }
            if (sub) {
                canvas.drawPath(shape, shade)
                if (!focused) canvas.drawCircle(b.left + 16f * density, b.exactCenterY(), 2f * density, dot)
            }
            if (!focused && above?.hasFocus() != true && (pos == Pos.MIDDLE || pos == Pos.LAST)) {
                val inset = 12f * density
                canvas.drawRect(b.left + inset, b.top.toFloat(), b.right - inset, b.top + density.coerceAtLeast(1f), divider)
            }
            inner?.draw(canvas)
        }

        override fun isStateful(): Boolean = true

        override fun onStateChange(state: IntArray): Boolean {
            val f = state.contains(android.R.attr.state_focused)
            val innerChanged = inner?.setState(state) == true
            if (f == focused && !innerChanged) return false
            focused = f
            invalidateSelf()
            return true
        }

        override fun jumpToCurrentState() {
            inner?.jumpToCurrentState()
        }

        override fun setAlpha(alpha: Int) {
            alphaValue = alpha
            inner?.alpha = alpha
            invalidateSelf()
        }

        override fun getAlpha(): Int = alphaValue

        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getPadding(padding: Rect): Boolean {
            padding.setEmpty()
            return false
        }

        override fun invalidateDrawable(who: Drawable) = invalidateSelf()
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) = scheduleSelf(what, `when`)
        override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)
    }
}

/** Marks a row as nested under the setting above it ([RowGroups]): indented, with a dot. */
fun View.asSubRow(): View = apply {
    if (getTag(R.id.sub_row) == true) return@apply
    setTag(R.id.sub_row, true)
    val target = if (tag == ROW_PAIR && this is ViewGroup && childCount > 0) getChildAt(0) else this
    val inset = (RowGroups.SUB_INSET_DP * resources.displayMetrics.density).toInt()
    target.setPadding(target.paddingLeft + inset, target.paddingTop, target.paddingRight, target.paddingBottom)
}
