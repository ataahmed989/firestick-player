package app.fireplayer.ui

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.min

/**
 * A value that glides to where it's sent, frame by frame, like a critically damped spring: it keeps its speed when
 * sent somewhere new while still moving (a D-pad held down, presses in quick succession), so the motion flows on
 * instead of stopping and starting again as a fresh animation would. [onValue] gets each new value, on the main
 * thread, just before the frame is drawn.
 *
 * [omega] sets how quick it is (about 4 / omega seconds to settle); [settle] how close counts as there.
 */
class Spring(
    private val omega: Float = 17f,
    private val settle: Float = 0.5f,
    private val onValue: (Float) -> Unit,
) : Choreographer.FrameCallback {

    var value = 0f
        private set
    var target = 0f
        private set
    private var velocity = 0f
    private var running = false
    private var lastNanos = 0L

    /** Straight there, no motion. */
    fun snap(to: Float) {
        stop()
        value = to
        target = to
        onValue(to)
    }

    /** Glides there from wherever it is, at the speed it has. */
    fun glideTo(to: Float) {
        target = to
        if (running) return
        if (abs(to - value) < settle && abs(velocity) < settle) {
            snap(to)
            return
        }
        running = true
        lastNanos = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun stop() {
        if (running) Choreographer.getInstance().removeFrameCallback(this)
        running = false
        velocity = 0f
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        // A late frame moves it on by at most two frames' worth, so it never leaps.
        val dt = if (lastNanos == 0L) FRAME else ((frameTimeNanos - lastNanos) / 1e9f).coerceIn(0.001f, 2 * FRAME)
        lastNanos = frameTimeNanos
        var left = dt
        while (left > 0f) {
            val h = min(left, STEP)
            val accel = omega * omega * (target - value) - 2f * omega * velocity
            velocity += accel * h
            value += velocity * h
            left -= h
        }
        if (abs(target - value) < settle && abs(velocity) < settle * omega) {
            value = target
            velocity = 0f
            running = false
            onValue(value)
            return
        }
        onValue(value)
        Choreographer.getInstance().postFrameCallback(this)
    }

    private companion object {
        const val FRAME = 1f / 60f
        const val STEP = 1f / 240f
    }
}
