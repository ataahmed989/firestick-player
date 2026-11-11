package app.fireplayer.player

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import java.lang.reflect.Field
import kotlin.math.abs

/**
 * Keeps the film's frames evenly paced on a TV switched to the film's refresh rate.
 *
 * Media3 lines each frame up with the TV's next refresh, timing the refreshes from the app's frame callbacks
 * (Choreographer). After [FrameRateMatcher] switches the TV to 23.976 Hz, say, Fire OS goes on delivering those at
 * 60 Hz: they only meet the TV's refreshes every few frames, frames get lined up with refreshes that aren't there, and
 * about one frame in ten is shown twice while the next is never shown (measured on a Fire TV Stick 4K Plus). When the
 * callbacks don't come at the TV's rate, Media3 is told it doesn't know the refresh rate, as it would be without a
 * display, and [PacedVideoRenderer] lines the frames up with the TV's refreshes instead, timed from the screen itself.
 * When they do (a 60 fps film on a 60 Hz TV), Media3 is left as it is. Should Media3's insides not be where this
 * expects, it does nothing and playback is as before.
 *
 * The TV's rate is the mode showing, never the one asked for: once the TV was turned on in the middle of a film, Fire OS
 * put it on 59.94 Hz while frames went on being paced for the 23.976 Hz it had before, and one in five came early or late
 * (measured on a Fire TV Stick 4K Plus). So whenever the mode changes, Media3 lines the frames up again, with its own
 * timing for the new mode, until it has been measured afresh.
 */
@OptIn(UnstableApi::class)
class VsyncFix(private val activity: Activity) {

    private val handler = Handler(Looper.getMainLooper())
    // Media3's frame timing for each video renderer of the player.
    private val helpers = mutableListOf<Any>()
    /** The frame callbacks don't come at the TV's rate: Media3's lining up is switched off ([PacedVideoRenderer]'s takes over). */
    @Volatile var unaligned = false
        private set

    /** How long the TV's refreshes are, while [unaligned]. */
    @Volatile var tvPeriodNs = 0L
        private set

    /** Counts the modes measured in turn: when it changes, the TV has been on another mode since (see [FramePacer]). */
    @Volatile var modes = 0
        private set
    private var lastModeId = Int.MIN_VALUE
    private val intervals = LongArray(SAMPLES)
    private var count = 0
    private var lastFrameNs = 0L
    private var running = false
    // The mode showing when the latest measuring began (-1: not known); another showing means it's measured again.
    private var measuredModeId = -1
    private var measuring = false
    private var remeasurePending = false

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastFrameNs != 0L) intervals[count++] = frameTimeNanos - lastFrameNs
            lastFrameNs = frameTimeNanos
            if (count < SAMPLES) Choreographer.getInstance().postFrameCallback(this) else measured()
        }
    }

    // Media3 reads the rate again whenever playback starts and the display changes, so it's put right again four times
    // a second; a change of mode is measured afresh once the TV has settled on it. The mode is looked at here too, in
    // case Fire OS reports it late or the display's own news of it never came.
    private val check = object : Runnable {
        override fun run() {
            if (!running) return
            if (!measuring) showing()?.let { (modeId, rate) -> if (modeId != measuredModeId) handOver(rate) }
            apply()
            handler.postDelayed(this, CHECK_MS)
        }
    }

    private val remeasure = Runnable {
        remeasurePending = false
        measure()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || !running) return
            handOver(showing()?.second ?: return)
        }

        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    /** One of the player's renderers, as it's made: video renderers have their frame timing looked after. */
    fun add(renderer: Renderer) {
        if (renderer !is MediaCodecVideoRenderer) return
        try {
            val control = CONTROL?.get(renderer) ?: return
            val helper = HELPER?.get(control) ?: return
            helpers += helper
        } catch (e: ReflectiveOperationException) {
            Log.w(TAG, "Media3's frame timing isn't where expected; left as it is", e)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Media3's frame timing isn't where expected; left as it is", e)
        }
    }

    fun start() {
        if (running || helpers.isEmpty() || DURATION == null) return
        running = true
        displays()?.registerDisplayListener(displayListener, handler)
        measure()
        handler.postDelayed(check, CHECK_MS)
    }

    fun stop() {
        running = false
        measuring = false
        remeasurePending = false
        handler.removeCallbacks(check)
        handler.removeCallbacks(remeasure)
        Choreographer.getInstance().removeFrameCallback(frame)
        displays()?.unregisterDisplayListener(displayListener)
    }

    // By name: looking it up by class needs Android 6.
    private fun displays() = activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    private fun measure() {
        if (!running) return
        measuring = true
        measuredModeId = showing()?.first ?: -1
        count = 0
        lastFrameNs = 0L
        Choreographer.getInstance().removeFrameCallback(frame)
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun measured() {
        measuring = false
        val callbackNs = intervals.sorted()[SAMPLES / 2]
        // The TV's rate: the mode showing. One asked for may not be (the TV off or reconnecting, Fire OS's own mode put
        // back); should Fire OS report a switch late, the check above measures again when it does.
        val rate = showing()?.second ?: return
        val tvNs = TvRefresh.periodNs(rate)
        val now = TvRefresh.unaligned(callbackNs, tvNs)
        if (now != unaligned || tvNs != tvPeriodNs) {
            Log.i(TAG, "Frame callbacks every ${callbackNs / 1000} us, the TV refreshes every ${tvNs / 1000} us " +
                "(mode $measuredModeId): " + if (now) "frames are lined up with the TV's refreshes here" else "Media3 lines them up")
        }
        val was = unaligned
        if (measuredModeId != lastModeId) {
            lastModeId = measuredModeId
            modes++
        }
        tvPeriodNs = tvNs
        unaligned = now
        if (was && !now) restoreMedia3(rate)
        apply()
    }

    /** The mode showing and its rate (no mode before Android 6, where only the rate is known). */
    private fun showing(): Pair<Int, Float>? {
        @Suppress("DEPRECATION")
        val display = activity.windowManager.defaultDisplay ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return -1 to display.refreshRate
        val mode = display.mode
        return mode.modeId to mode.refreshRate
    }

    /**
     * The TV's mode changed (or the screen disagrees with the measuring): Media3 lines the frames up again, with its own
     * timing for the mode showing at [rate], until it's measured again once the TV has settled. Main thread.
     */
    private fun handOver(rate: Float) {
        if (!running) return
        if (unaligned) {
            unaligned = false
            restoreMedia3(rate)
            Log.i(TAG, "Media3 lines frames up until the TV's refreshes are measured again")
        }
        // Not put off by more news of the change: the check above catches a mode that changes again later.
        if (!remeasurePending) {
            remeasurePending = true
            handler.postDelayed(remeasure, SETTLE_MS)
        }
    }

    /**
     * From [PacedVideoRenderer], on the playback thread: frames reach the screen between the refreshes measured, so
     * what was measured is wrong for the screen as it is now.
     */
    fun screenDisagrees() {
        handler.post { showing()?.let { handOver(it.second) } }
    }

    /** Media3's own timing for a TV at [rate], where this put "not known" in its place. */
    private fun restoreMedia3(rate: Float) {
        val duration = DURATION ?: return
        val (durationNs, offsetNs) = TvRefresh.media3Vsync(rate)
        for (helper in helpers) {
            try {
                if (duration.getLong(helper) != C.TIME_UNSET) continue
                duration.setLong(helper, durationNs)
                OFFSET?.setLong(helper, offsetNs)
            } catch (e: ReflectiveOperationException) {
                Log.w(TAG, "Couldn't change Media3's frame timing", e)
                helpers.clear()
                return
            }
        }
    }

    private fun apply() {
        if (!unaligned) return
        val duration = DURATION ?: return
        for (helper in helpers) {
            try {
                if (duration.getLong(helper) != C.TIME_UNSET) duration.setLong(helper, C.TIME_UNSET)
            } catch (e: ReflectiveOperationException) {
                Log.w(TAG, "Couldn't change Media3's frame timing", e)
                helpers.clear()
                return
            }
        }
    }

    private companion object {
        const val TAG = "FirePlayer"
        const val SAMPLES = 31
        const val CHECK_MS = 250L
        const val SETTLE_MS = 1_500L

        private fun field(owner: Class<*>, name: String): Field? = try {
            owner.getDeclaredField(name).apply { isAccessible = true }
        } catch (e: ReflectiveOperationException) {
            null
        } catch (e: RuntimeException) {
            null
        }

        private val HELPER_CLASS: Class<*>? = try {
            Class.forName("androidx.media3.exoplayer.video.VideoFrameReleaseHelper")
        } catch (e: ClassNotFoundException) {
            null
        }
        val CONTROL: Field? = field(MediaCodecVideoRenderer::class.java, "videoFrameReleaseControl")
        val HELPER: Field? = try {
            Class.forName("androidx.media3.exoplayer.video.VideoFrameReleaseControl").let { field(it, "frameReleaseHelper") }
        } catch (e: ClassNotFoundException) {
            null
        }
        val DURATION: Field? = HELPER_CLASS?.let { field(it, "vsyncDurationNs") }
        val OFFSET: Field? = HELPER_CLASS?.let { field(it, "vsyncOffsetNs") }
    }
}

/** [VsyncFix]'s arithmetic, apart so it can be tested. */
object TvRefresh {
    /** How long one of the TV's refreshes is at [rate]. */
    fun periodNs(rate: Float): Long = (1e9 / rate).toLong()

    /** Whether frame callbacks every [callbackNs] miss the TV's refreshes every [tvNs] (more than 1% apart). */
    fun unaligned(callbackNs: Long, tvNs: Long): Boolean = abs(callbackNs - tvNs) > tvNs / 100

    /** Media3's own refresh length and offset for a TV at [rate], as it works them out from the display. */
    fun media3Vsync(rate: Float): Pair<Long, Long> {
        val durationNs = (1e9 / rate.toDouble()).toLong()
        return durationNs to durationNs * 80 / 100
    }
}
