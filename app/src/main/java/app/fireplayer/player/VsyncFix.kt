package app.fireplayer.player

import android.app.Activity
import android.hardware.display.DisplayManager
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
 * display, and sends each frame out at the film's own even pace, which the TV, at the film's rate, shows one for one.
 * When they do (a 60 fps film on a 60 Hz TV), Media3 is left as it is. Should Media3's insides not be where this
 * expects, it does nothing and playback is as before.
 */
@OptIn(UnstableApi::class)
class VsyncFix(private val activity: Activity) {

    private val handler = Handler(Looper.getMainLooper())
    // Media3's frame timing for each video renderer of the player.
    private val helpers = mutableListOf<Any>()
    // The frame callbacks don't come at the TV's rate: Media3's lining up is switched off.
    private var unaligned = false
    private val intervals = LongArray(SAMPLES)
    private var count = 0
    private var lastFrameNs = 0L
    private var running = false

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (lastFrameNs != 0L) intervals[count++] = frameTimeNanos - lastFrameNs
            lastFrameNs = frameTimeNanos
            if (count < SAMPLES) Choreographer.getInstance().postFrameCallback(this) else measured()
        }
    }

    // Media3 reads the rate again whenever playback starts and the display changes, so it's put right again four times
    // a second; a change of mode is measured afresh once the TV has settled on it.
    private val check = object : Runnable {
        override fun run() {
            if (!running) return
            apply()
            handler.postDelayed(this, CHECK_MS)
        }
    }

    private val remeasure = Runnable { measure() }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || !running) return
            handler.removeCallbacks(remeasure)
            handler.postDelayed(remeasure, SETTLE_MS)
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
        activity.getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, handler)
        measure()
        handler.postDelayed(check, CHECK_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(check)
        handler.removeCallbacks(remeasure)
        Choreographer.getInstance().removeFrameCallback(frame)
        activity.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
    }

    private fun measure() {
        if (!running) return
        count = 0
        lastFrameNs = 0L
        Choreographer.getInstance().removeFrameCallback(frame)
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private fun measured() {
        val callbackNs = intervals.sorted()[SAMPLES / 2]
        @Suppress("DEPRECATION")
        val display = activity.windowManager.defaultDisplay ?: return
        // The TV's rate: the mode asked for (what Fire OS reports can be the one before the switch), or else the one reported.
        val wanted = activity.window.attributes.preferredDisplayModeId
        val rate = display.supportedModes.firstOrNull { it.modeId == wanted }?.refreshRate ?: display.refreshRate
        val tvNs = (1e9 / rate).toLong()
        val now = abs(callbackNs - tvNs) > tvNs / 100
        if (now != unaligned) Log.i(TAG, "Frame callbacks every ${callbackNs / 1000} us, the TV refreshes every ${tvNs / 1000} us: " +
            if (now) "frames go out at the film's own pace" else "Media3 lines them up")
        unaligned = now
        apply()
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
    }
}
