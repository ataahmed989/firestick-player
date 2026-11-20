package app.fireplayer.live.ui

import app.fireplayer.player.PictureFit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the live picture's surface goes (HomeActivity lays the SurfaceView out with it). Pure arithmetic, so it's
 * unit-tested.
 *
 * The guide's miniature always fits the picture's shape inside its slot. Full screen follows Display size, the same
 * saved choice and the same meaning as in the VOD player ([PictureFit]: Normal, Fill screen, Zoom, Stretch, 16:9, 4:3,
 * 21:9), centred on the screen; Fill screen and Zoom make the surface bigger than the screen, so it starts above and
 * left of it (negative margins) and the screen crops it. Unlike the VOD player, black bars inside the stream itself
 * aren't measured for live channels: Fill screen and Stretch go by the whole frame.
 */
object LivePicture {

    /** The surface's place in the target's coordinates: left and top may be negative, the size bigger than the screen. */
    data class Placement(val left: Int, val top: Int, val width: Int, val height: Int) {
        val right get() = left + width
        val bottom get() = top + height
    }

    /**
     * How far a picture's shape may be from its slot's and still fill it exactly ([place]'s [fill]): 2%, too little to
     * see as a stretch, and more than the rounding of a 16:9 slot to whole dp and pixels (Home's live picture's window
     * is 405 x 228 dp, 0.1% from 16:9) or a 16:9 channel sent as 1920 x 1088 (0.7%). So no hairline of bars shows.
     */
    const val FILL_WITHIN = 0.02f

    /** Whether a picture of [aspect] (0 or less: unknown, 16:9) is within [FILL_WITHIN] of a slot [width] x [height]. */
    fun fills(aspect: Float, width: Float, height: Float): Boolean {
        if (width <= 0f || height <= 0f) return false
        val a = if (aspect > 0f) aspect else 16f / 9f
        return abs(a / (width / height) - 1f) <= FILL_WITHIN
    }

    /**
     * The surface for a picture of [aspect] (width / height, pixel shape included; 0 or less while unknown) in the
     * target [left], [top], [width] x [height]: the whole screen when [full] (laid out for Display size [mode]), else
     * the miniature's slot (always fitted, 16:9 assumed while the shape is unknown; with [fill], a picture of the
     * slot's own shape ([fills]) takes the whole slot, so it shows no bars: the live picture on Home and Search).
     */
    fun place(left: Float, top: Float, width: Float, height: Float, aspect: Float, full: Boolean, mode: Int, fill: Boolean = false): Placement {
        if (full) {
            val fit = PictureFit.size(mode, if (aspect > 0f) aspect else 0f, width.roundToInt(), height.roundToInt())
            if (fit != null) {
                val (w, h) = fit
                return Placement(left.roundToInt() + (width.roundToInt() - w) / 2, top.roundToInt() + (height.roundToInt() - h) / 2, w, h)
            }
        } else if (fill && fills(aspect, width, height)) {
            return Placement(left.roundToInt(), top.roundToInt(), width.roundToInt(), height.roundToInt())
        }
        val a = if (aspect > 0f) aspect else 16f / 9f
        var w = width
        var h = w / a
        if (h > height) {
            h = height
            w = h * a
        }
        return Placement((left + (width - w) / 2).toInt(), (top + (height - h) / 2).toInt(), w.toInt(), h.toInt())
    }

    /**
     * The miniature's fitted picture as [place] has it, in fractions and into [out] (nothing is made): where the
     * picture is drawn, frame by frame, while its slot changes size (Home's live picture's window widening or
     * narrowing). Never filled: a slot that comes near the picture's shape on the way would make it jump.
     */
    fun fit(left: Float, top: Float, width: Float, height: Float, aspect: Float, out: PictureMotion.Box): PictureMotion.Box {
        val a = if (aspect > 0f) aspect else 16f / 9f
        var w = width
        var h = w / a
        if (h > height) {
            h = height
            w = h * a
        }
        val l = left + (width - w) / 2
        val t = top + (height - h) / 2
        return out.set(l, t, l + w, t + h)
    }

    /**
     * The part of [p] on a screen of [screenW] x [screenH] (captions go there, so they stay readable when the picture
     * is bigger than the screen); [p] itself when it's all on screen.
     */
    fun onScreen(p: Placement, screenW: Int, screenH: Int): Placement {
        val l = max(p.left, 0)
        val t = max(p.top, 0)
        val r = min(p.right, screenW)
        val b = min(p.bottom, screenH)
        if (r <= l || b <= t) return p
        return Placement(l, t, r - l, b - t)
    }
}
