package app.fireplayer.ui

import app.fireplayer.shell.RailShape
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A service card's logo moving as Glass Home's focused app tile moves its icon (TileView: the front of a tile's two
 * layers, the mark over its back). Focus arrives: [focus] rises to 1 over [FOCUS_MS] on Glass Home's ease, and the tile
 * sways once, [SWAY_CYCLES] swings in [SWAY_MS], each smaller than the last, dying away to its rest tilt (tipped back
 * [REST_TILT_Y]); the logo is drawn its tilt times [MAX_SHIFT_DP] times [focus] times [FRONT_TRAVEL] from its place
 * ([dx], [dy]). Focus goes: the sway stops where it is and [focus] falls back to 0 on the same ease, taking the logo
 * home. Glass Home's light, shine and rim glare are left out, as are its layers' shadows; its back layer (here the
 * card's surface) stays put, as the card keeps its own focus look.
 *
 * Everything goes on from where it is drawn: focus back on a logo not yet home starts its new sway from the tilt it
 * has, that tilt fading out as focus arrives (Glass Home starts it afresh, a small jump), so it never jumps. Pure, its
 * clock passed in, unit-tested; the card drives it a frame at a time ([step]) while [moving], and nothing at rest.
 */
class LogoSway {

    /** How far the logo is drawn from its place, dp (0, 0 at rest unfocused). */
    var dx = 0f
        private set
    var dy = 0f
        private set

    /** How focused, 0..1, on Glass Home's ease. */
    var focus = 0f
        private set

    private var focusFrom = 0f
    private var focusTo = 0f
    private var focusT0 = 0L
    private var focusing = false

    private var tiltX = 0f
    private var tiltY = REST_TILT_Y
    private var swaying = false
    private var swayT0 = 0L
    // The tilt it had when focus came back before it was home, fading out as focus arrives.
    private var carryX = 0f
    private var carryY = 0f

    /** Still on its way somewhere: [step] it each frame. */
    val moving: Boolean get() = focusing || swaying

    /** Focus arrived ([on]) or went, [now] ms (the frame clock). */
    fun focusChanged(on: Boolean, now: Long) {
        val to = if (on) 1f else 0f
        if (to == focusTo && (moving || focus == to)) return
        if (on) {
            // Home already: Glass Home's sway from its start. Not home: from the tilt it has.
            carryX = if (focus == 0f) 0f else tiltX
            carryY = if (focus == 0f) 0f else tiltY - REST_TILT_Y
            swaying = true
            swayT0 = now
        } else {
            // The tilt holds where it is; the logo goes home as focus falls.
            swaying = false
        }
        focusFrom = focus
        focusTo = to
        focusT0 = now
        focusing = focus != to
        step(now)
    }

    /** Moves on to [now] ms; true while still [moving]. */
    fun step(now: Long): Boolean {
        if (focusing) {
            val p = min(1f, max(0L, now - focusT0) / FOCUS_MS.toFloat())
            focus = if (p >= 1f) focusTo else focusFrom + (focusTo - focusFrom) * ease(p)
            if (p >= 1f) focusing = false
        }
        if (swaying) {
            val ms = max(0L, now - swayT0)
            val t = min(1f, ms / SWAY_MS.toFloat())
            val carry = 1f - ease(min(1f, ms / FOCUS_MS.toFloat()))
            tiltX = swayX(t) + carryX * carry
            tiltY = swayY(t) + carryY * carry
            if (t >= 1f) {
                swaying = false
                tiltX = 0f
                tiltY = REST_TILT_Y
            }
        }
        place()
        return moving
    }

    /** Where it's heading, at once (focused or not: [on]): the page hidden, the screen paused, animations off. */
    fun settle(on: Boolean) {
        focus = if (on) 1f else 0f
        focusFrom = focus
        focusTo = focus
        focusing = false
        swaying = false
        tiltX = 0f
        tiltY = REST_TILT_Y
        carryX = 0f
        carryY = 0f
        place()
    }

    private fun place() {
        val k = MAX_SHIFT_DP * FRONT_TRAVEL * focus
        dx = tiltX * k
        dy = tiltY * k
    }

    companion object {
        // Glass Home's numbers (TileView, Ui). Its design px are the 1920 px screen's, half a dp here.

        /** Focus arriving or going, ms (Ui.FOCUS_MS), on [ease]. */
        const val FOCUS_MS = 180L
        /** The sway on focus, from first swing to rest, ms (TileView.SWAY_MS), and its swings (SWAY_CYCLES). */
        const val SWAY_MS = 2400L
        const val SWAY_CYCLES = 1.25f
        /** Its widest swings: across, and up and down about the rest tilt. */
        const val SWAY_X = 0.5f
        const val SWAY_Y = 0.3f
        /** At rest the tile is tipped back a touch (TileView.REST_TILT_Y): focused, the logo stands a little high. */
        const val REST_TILT_Y = -0.25f
        /** How far a layer drifts at full tilt, dp (TileView.MAX_SHIFT, 5 design px). */
        const val MAX_SHIFT_DP = 2.5f
        /** The front layer's share of that drift: (its depth, 1, less 0.3) times 2 (TileView.drawLayers). */
        const val FRONT_TRAVEL = 1.4f

        /**
         * The farthest the logo is ever drawn from its place, dp, either way: the sway's widest (under 1 dp) with room
         * to spare for a tilt carried over. The logo cards keep this much clear of their rim ([LogoSwayTest]).
         */
        const val MAX_REACH_DP = 2f

        /** Glass Home's ease (Ui.EASE, cubic-bezier(0.2, 0, 0, 1)): the rail's own curve, the same numbers. */
        fun ease(t: Float): Float = RailShape.ease(t)

        /** How much of the sway is left at [t] (0..1 of [SWAY_MS]): dies away smoothly, none at the end. */
        fun fade(t: Float): Float {
            val u = 1f - t
            return u * u * u
        }

        /** The sway's tilt across at [t] (0..1 of [SWAY_MS]). */
        fun swayX(t: Float): Float = sin(2 * PI * SWAY_CYCLES * t).toFloat() * SWAY_X * fade(t)

        /** The sway's tilt up and down at [t]: half as quick, about the rest tilt. */
        fun swayY(t: Float): Float = REST_TILT_Y + sin(PI * SWAY_CYCLES * t).toFloat() * SWAY_Y * fade(t)
    }
}
