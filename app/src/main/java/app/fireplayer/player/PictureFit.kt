package app.fireplayer.player

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * How big the film is laid out for each Display size choice, in screen pixels (it's centred, and may be bigger than
 * the screen). Worked out here and given to the layout as it is, so nothing rests on the TV scaling a video surface.
 *
 * Fill screen and Stretch go by the picture itself: black bars that are part of the file ([barV] of the height at the
 * top and at the bottom, [barH] of the width at each side, see [BlackBars]) are left off the screen.
 */
object PictureFit {
    const val NORMAL = 0
    const val FILL = 1
    const val ZOOM = 2
    const val STRETCH = 3
    const val WIDE_16_9 = 4
    const val TV_4_3 = 5
    const val CINEMA_21_9 = 6

    const val ZOOM_BY = 1.15f
    // A shape this near the screen's is shown full screen (1920x1088 files and the like).
    private const val NEAR = 0.01f

    /** Width and height for [mode]; null while the screen's size isn't known. [aspect] is 0 while the film's isn't. */
    fun size(mode: Int, aspect: Float, screenW: Int, screenH: Int, barV: Float = 0f, barH: Float = 0f): Pair<Int, Int>? {
        if (screenW <= 0 || screenH <= 0) return null
        val sw = screenW.toFloat()
        val sh = screenH.toFloat()
        val screen = sw / sh
        fun near(a: Float) = a <= 0f || abs(a / screen - 1f) <= NEAR
        // Whole shape inside the screen.
        fun inside(a: Float): Pair<Float, Float> = when {
            near(a) -> sw to sh
            a > screen -> sw to sw / a
            else -> sh * a to sh
        }
        val keepW = 1f - 2f * barH.coerceIn(0f, 0.45f)
        val keepH = 1f - 2f * barV.coerceIn(0f, 0.45f)
        val (w, h) = when (mode) {
            FILL -> {
                // The picture without its bars covers the screen, keeping its shape; what doesn't fit goes off the sides.
                val a = if (aspect <= 0f) 0f else aspect * keepW / keepH
                val (pw, ph) = when {
                    near(a) -> sw to sh
                    a > screen -> sh * a to sh
                    else -> sw to sw / a
                }
                pw / keepW to ph / keepH
            }
            ZOOM -> inside(aspect).let { it.first * ZOOM_BY to it.second * ZOOM_BY }
            STRETCH -> sw / keepW to sh / keepH
            WIDE_16_9 -> inside(16f / 9f)
            TV_4_3 -> inside(4f / 3f)
            CINEMA_21_9 -> inside(21f / 9f)
            else -> inside(aspect)
        }
        return w.roundToInt().coerceAtLeast(1) to h.roundToInt().coerceAtLeast(1)
    }

    /** Whether [mode] goes by the picture's own black bars. */
    fun usesBars(mode: Int) = mode == FILL || mode == STRETCH
}
