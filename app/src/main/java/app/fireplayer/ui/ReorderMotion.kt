package app.fireplayer.ui

import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.os.Build
import android.view.View
import android.view.animation.AnimationUtils
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Glass Home's damped spring (its Spring.java), solved exactly (mass 1) and described the way Apple describes its
 * springs: a [response] (the period, seconds) and a [damping] ratio below 1. Pure, unit-tested.
 */
class DampedSpring(response: Float, damping: Float) {
    private val w = (2 * PI / response).toFloat()
    private val z = damping
    private val wd = w * sqrt(1 - z * z)

    /** Displacement [t] seconds after starting at [x0] with velocity [v0]; the rest is 0. */
    fun x(x0: Float, v0: Float, t: Float): Float {
        val e = exp(-z * w * t)
        val b = (v0 + z * w * x0) / wd
        return e * (x0 * cos(wd * t) + b * sin(wd * t))
    }

    /** Velocity at the same moment. */
    fun v(x0: Float, v0: Float, t: Float): Float {
        val e = exp(-z * w * t)
        val b = (v0 + z * w * x0) / wd
        val c = cos(wd * t)
        val s = sin(wd * t)
        return e * (-z * w * (x0 * c + b * s) + wd * (b * c - x0 * s))
    }

    /** Time until within 0.1 % of rest, ms. */
    val settleMs: Long = ceil(ln(1000.0) / (z * w) * 1000).toLong()

    /** From 0 to 1 over [settleMs], [ms] after it began: the spring from rest to its target (Glass Home's interpolator). */
    fun progress(ms: Long): Float = if (ms >= settleMs) 1f else 1f - x(1f, 0f, max(0L, ms) / 1000f)
}

/**
 * Moving a card along a row whose cards keep fixed places (Home's services), copied from Glass Home's app tiles
 * (TileView's jiggle, ReorderMotion, its springs): the card picked up (carried) lifts to [LIFT_SCALE] above the
 * others and rocks about its centre as icons do in iOS's jiggle mode; the others recede to [RECEDE_SCALE] and hold
 * still. When the carried card passes one, both swap places: each starts from where it is drawn and springs into its
 * new place ([REORDER]; the carried card on the quicker [LIFT], so it leads), keeping its speed when the next step comes
 * before it has settled, so a held arrow glides. Put down, everything settles back on the same springs.
 *
 * Each frame only properties of the existing views change (rotation and translationZ here; the row's scale and
 * translationX through [redraw], which reads [scaleOf] and [offsetOf]); nothing is laid out or allocated. One clock
 * drives it all and stops when everything rests. With the system's animations off, everything is at once and nothing
 * rocks, as in Glass Home.
 */
class ReorderMotion(
    private val density: Float,
    /** The chosen card's size in its row (its growth): the carried one is drawn at [LIFT_SCALE] whatever that is. */
    private val chosenScale: Float,
    /** Something here moved: the row puts its cards where they are now, reading [scaleOf] and [offsetOf]. */
    private val redraw: () -> Unit,
) : TimeAnimator.TimeListener {

    private val ticker = TimeAnimator().apply { setTimeListener(this@ReorderMotion) }

    // The row's cards while one is carried or they settle (index: the card's track, not its place), made once a row.
    private var views = arrayOfNulls<View>(0)
    private var n = 0
    private var role = IntArray(0)
    // Its glide into a new place: translationX offset, its spring, start, speed and when it began.
    private var offset = FloatArray(0)
    private var gliding = BooleanArray(0)
    private var glideSpring = arrayOfNulls<DampedSpring>(0)
    private var x0 = FloatArray(0)
    private var v0 = FloatArray(0)
    private var t0 = LongArray(0)
    // Its change of role (lifted, receded, back): scale (a share of the row's own), translationZ and, put down, the rock's angle to 0.
    private var scale = FloatArray(0)
    private var roleMoving = BooleanArray(0)
    private var roleSpring = arrayOfNulls<DampedSpring>(0)
    private var scaleFrom = FloatArray(0)
    private var scaleTo = FloatArray(0)
    private var zFrom = FloatArray(0)
    private var zTo = FloatArray(0)
    private var rotFrom = FloatArray(0)
    private var roleT0 = LongArray(0)

    private var carried: View? = null
    private var jiggleT0 = 0L
    private var animated = true

    /** A card is being carried. */
    var carrying = false
        private set

    /** Carried, or still settling after it was put down: the row reads [scaleOf] and [offsetOf]. */
    val active: Boolean get() = n > 0

    /** Picks [card] (one of [items], in their places) up: it lifts and jiggles, the others recede a little. */
    fun lift(items: List<View>, card: View) {
        finishNow()
        animated = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()
        grow(items.size)
        n = items.size
        for (i in 0 until n) {
            views[i] = items[i]
            role[i] = NONE
            offset[i] = 0f
            gliding[i] = false
            scale[i] = 1f
            roleMoving[i] = false
        }
        carried = card
        carrying = true
        val now = clock()
        jiggleT0 = now
        for (i in 0 until n) setRole(i, if (views[i] === card) LIFTED else RECEDED, now)
        redraw()
        run()
    }

    /** [card]'s place moved [dx] px: it starts from where it is drawn now and springs into its new place, keeping its speed. */
    fun shifted(card: View, dx: Float) = retarget(indexOf(card), dx, 0f)

    /** A fresh press the carried card can't follow ([dir] -1 left, 1 right): it bumps against the end and springs back. */
    fun nudge(dir: Int) {
        if (!animated) return
        retarget(indexOf(carried), 0f, dir * NUDGE_DP_S * density)
    }

    /** Puts the carried card down where it is: everything settles back ([animate] false: at once). */
    fun drop(animate: Boolean = true) {
        if (!carrying) return
        carrying = false
        if (!animate || !animated) {
            finishNow()
            return
        }
        val now = clock()
        for (i in 0 until n) setRole(i, NONE, now)
        run()
    }

    /** Every card in its place and at rest, at once. */
    fun finishNow() {
        carrying = false
        if (ticker.isStarted) ticker.end()
        if (n == 0) return
        for (i in 0 until n) {
            views[i]?.let {
                it.rotation = 0f
                it.translationZ = 0f
            }
            views[i] = null
        }
        n = 0
        carried = null
        redraw()
    }

    /** [v]'s size as a share of the size the row gives it (1 at rest). */
    fun scaleOf(v: View): Float {
        val i = indexOf(v)
        return if (i < 0) 1f else scale[i]
    }

    /** How far [v] is drawn from its place, px (0 at rest). */
    fun offsetOf(v: View): Float {
        val i = indexOf(v)
        return if (i < 0) 0f else offset[i]
    }

    override fun onTimeUpdate(animation: TimeAnimator, totalTime: Long, deltaTime: Long) {
        val now = clock()
        var busy = carrying && animated
        val still = max(STILL_MIN_PX, STILL_DP * density)
        val stillSpeed = STILL_SPEED_DP_S * density
        for (i in 0 until n) {
            val v = views[i] ?: continue
            if (gliding[i]) {
                val s = glideSpring[i]!!
                val dt = max(0L, now - t0[i]) / 1000f   // never before it began: the spring would run backwards
                var x = s.x(x0[i], v0[i], dt)
                if (abs(x) < still && abs(s.v(x0[i], v0[i], dt)) < stillSpeed) {
                    x = 0f
                    gliding[i] = false
                } else {
                    busy = true
                }
                offset[i] = x
            }
            if (roleMoving[i]) {
                val s = roleSpring[i]!!
                val ms = now - roleT0[i]
                val p = s.progress(ms)
                scale[i] = scaleFrom[i] + (scaleTo[i] - scaleFrom[i]) * p
                v.translationZ = zFrom[i] + (zTo[i] - zFrom[i]) * p
                if (role[i] != LIFTED) v.rotation = rotFrom[i] * (1f - p)
                if (ms >= s.settleMs) roleMoving[i] = false else busy = true
            }
            if (role[i] == LIFTED && carrying) v.rotation = jiggleAngle(now - jiggleT0)
        }
        redraw()
        if (!busy) {
            ticker.end()
            if (!carrying) finishNow()
        }
    }

    /** Lifted, receded or back ([NONE]): scale, lift and (put down) the rock's angle go there on its spring. */
    private fun setRole(i: Int, r: Int, now: Long) {
        val from = role[i]
        if (from == r) return
        role[i] = r
        val v = views[i] ?: return
        val to = when (r) {
            LIFTED -> LIFT_SCALE / chosenScale
            RECEDED -> RECEDE_SCALE
            else -> 1f
        }
        val z = if (r == LIFTED) LIFT_Z_DP * density else 0f
        if (!animated) {
            scale[i] = to
            v.translationZ = z
            if (r != LIFTED) v.rotation = 0f
            roleMoving[i] = false
            return
        }
        roleSpring[i] = if (r == LIFTED || from == LIFTED) LIFT else REORDER
        scaleFrom[i] = scale[i]
        scaleTo[i] = to
        zFrom[i] = v.translationZ
        zTo[i] = z
        rotFrom[i] = v.rotation
        roleT0[i] = now
        roleMoving[i] = true
    }

    /** Jumps track [i] by [dx] and [dv], keeping where it is drawn this instant and how fast it is going. */
    private fun retarget(i: Int, dx: Float, dv: Float) {
        if (i < 0) return
        if (!animated) {
            offset[i] = 0f
            gliding[i] = false
            return
        }
        val now = clock()
        var x = offset[i]
        var vx = 0f
        if (gliding[i]) {
            val s = glideSpring[i]!!
            val dt = max(0L, now - t0[i]) / 1000f
            x = s.x(x0[i], v0[i], dt)
            vx = s.v(x0[i], v0[i], dt)
        }
        glideSpring[i] = if (dv != 0f || views[i] === carried) LIFT else REORDER
        x0[i] = x + dx
        v0[i] = vx + dv
        t0[i] = now
        gliding[i] = true
        offset[i] = x0[i]
        run()
    }

    private fun run() {
        if (animated && n > 0 && !ticker.isStarted) ticker.start()
    }

    private fun indexOf(v: View?): Int {
        if (v == null) return -1
        for (i in 0 until n) if (views[i] === v) return i
        return -1
    }

    /** Room for [size] cards (once a row: its cards don't change while one is carried). */
    private fun grow(size: Int) {
        if (views.size >= size) return
        views = arrayOfNulls(size)
        role = IntArray(size)
        offset = FloatArray(size)
        gliding = BooleanArray(size)
        glideSpring = arrayOfNulls(size)
        x0 = FloatArray(size)
        v0 = FloatArray(size)
        t0 = LongArray(size)
        scale = FloatArray(size)
        roleMoving = BooleanArray(size)
        roleSpring = arrayOfNulls(size)
        scaleFrom = FloatArray(size)
        scaleTo = FloatArray(size)
        zFrom = FloatArray(size)
        zTo = FloatArray(size)
        rotFrom = FloatArray(size)
        roleT0 = LongArray(size)
    }

    private fun clock(): Long = AnimationUtils.currentAnimationTimeMillis()

    companion object {
        private const val NONE = 0
        private const val LIFTED = 1
        private const val RECEDED = 2

        // Glass Home's numbers (TileView, Ui, Spring, ReorderMotion, HomeActivity). Its design px are the 1920 px
        // screen's, half a dp here.

        /** The rock: this many degrees either way, one full wobble this long, easing in over the first wobble. */
        const val JIGGLE_DEG = 1.6f
        const val JIGGLE_MS = 260L
        /** The carried card's size, and the others' while it is carried (the row at rest: 1). */
        const val LIFT_SCALE = 1.14f
        const val RECEDE_SCALE = 0.95f
        /** How far the carried card lifts above the others (30 design px): it draws over the ones it passes. */
        const val LIFT_Z_DP = 15f
        /** A press it can't follow pushes it this fast toward the end (520 design px a second), and it springs back. */
        const val NUDGE_DP_S = 260f
        /** At rest when nearer than this (0.35 design px, at least 0.25 px) and slower than 4 px a second. */
        const val STILL_DP = 0.175f
        const val STILL_MIN_PX = 0.25f
        const val STILL_SPEED_DP_S = 2f
        /** A held arrow carries it one place on, after this long, then one place each this long. */
        const val STEP_DELAY_MS = 300L
        const val STEP_MS = 110L

        /** Cards sliding aside for the carried one, as icons do on an iPhone's home screen: unhurried, no bounce to speak of. */
        val REORDER = DampedSpring(0.48f, 0.92f)
        /** The carried card (and lifting, and putting it down): a touch quicker than the others, so it leads them. */
        val LIFT = DampedSpring(0.42f, 0.94f)

        /** The rock's angle [ms] after it began: a quick rock about the centre, easing in over the first wobble. */
        fun jiggleAngle(ms: Long): Float {
            val ramp = min(1f, ms / JIGGLE_MS.toFloat())
            return JIGGLE_DEG * ramp * sin(2 * PI * ms / JIGGLE_MS).toFloat()
        }
    }
}

/** Where cards go as one is carried along a row (pure, unit-tested). */
object ReorderMath {
    /** Where the carried card at [from] goes for an arrow ([dir] -1 or 1) in a row of [count]: [from] at either end. */
    fun step(from: Int, dir: Int, count: Int): Int {
        val to = from + dir
        return if (from in 0 until count && to in 0 until count) to else from
    }

    /** [list] with the item at [from] moved to [to], the ones between moving along one place. */
    fun <T> moved(list: List<T>, from: Int, to: Int): List<T> {
        if (from == to || from !in list.indices || to !in list.indices) return list
        return ArrayList(list).apply { add(to, removeAt(from)) }
    }

    /** The place of the item at [i] once the item at [from] has moved to [to]. */
    fun placeAfter(i: Int, from: Int, to: Int): Int = when {
        i == from -> to
        from < to && i in from + 1..to -> i - 1
        to < from && i in to until from -> i + 1
        else -> i
    }

    /**
     * Whether an arrow carries it a place on (Glass Home's acceptStep): a fresh press always; held, once it has been
     * down [ReorderMotion.STEP_DELAY_MS], one place each [ReorderMotion.STEP_MS] since the [last] step.
     */
    fun acceptsStep(repeat: Boolean, time: Long, downTime: Long, last: Long): Boolean =
        !repeat || (time - downTime >= ReorderMotion.STEP_DELAY_MS && time - last >= ReorderMotion.STEP_MS)
}
