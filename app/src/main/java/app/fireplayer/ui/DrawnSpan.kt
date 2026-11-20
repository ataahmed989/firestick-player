package app.fireplayer.ui

import android.graphics.Canvas
import android.graphics.drawable.Drawable

/**
 * A window's surface drawn wider or narrower than its view is laid out, while the window changes width in one motion
 * and nothing is laid out frame by frame (Home's banner and top-right window as the live picture comes and goes; as
 * the rail draws its own moving edge). The glass shapes are baked once per size ([GlassDrawable]), so the drawable
 * keeps its bounds and is drawn twice: its left half with its left edge at the span's left, its right half with its
 * right edge at the span's right, each cut where they meet in the middle. A window's surface is the same all along its
 * middle (the tint, the light from above, the hairline, the top highlight and the shadow under it), so the join
 * doesn't show; the corners, which differ, stay whole at both ends. It covers up to twice the drawable's width
 * ([covers]).
 */
object DrawnSpan {

    /** Further than anything is drawn: the cuts reach the shadow round the shape. */
    private const val FAR = 100_000f

    /** Whether a drawable [laidW] wide drawn [drawnW] wide is whole (each half reaches the middle). */
    fun covers(laidW: Float, drawnW: Float): Boolean = drawnW >= 0f && drawnW <= 2f * laidW

    /** [d] drawn from [left] to [right] (px, in its view's coordinates, as its bounds are); as it is when they're its own. */
    fun draw(canvas: Canvas, d: Drawable, left: Float, right: Float) {
        val b = d.bounds
        if (left == b.left.toFloat() && right == b.right.toFloat()) {
            d.draw(canvas)
            return
        }
        if (right <= left) return
        val middle = (left + right) / 2f
        var saved = canvas.save()
        canvas.clipRect(-FAR, -FAR, middle, FAR)
        canvas.translate(left - b.left, 0f)
        d.draw(canvas)
        canvas.restoreToCount(saved)
        saved = canvas.save()
        canvas.clipRect(middle, -FAR, FAR, FAR)
        canvas.translate(right - b.right, 0f)
        d.draw(canvas)
        canvas.restoreToCount(saved)
    }
}
