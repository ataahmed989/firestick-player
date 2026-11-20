package app.fireplayer.player

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display

/**
 * Whether the TV can show HDR10+, for the film's video renderer ([PacedVideoRenderer]): when it can't, HDR10+ is
 * hidden from the decoder ([Hdr10PlusMask]). Read from the display as each player starts and again whenever the display
 * changes (HDR turned on at the TV, the TV switched or reconnected), so a TV that gains or loses HDR10+ in the middle of
 * a film is followed from its next frame. One per player, like [VsyncFix].
 */
class Hdr10PlusGate(private val activity: Activity) {

    /** HDR10+ is hidden from the decoder: the TV doesn't report it. Read on the playback thread. */
    @Volatile var mask = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY && running) refresh()
        }

        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    /** As the player is built, before its first frame reaches the decoder. Main thread. */
    fun start() {
        if (!running) {
            running = true
            displays()?.registerDisplayListener(displayListener, handler)
        }
        refresh()
    }

    /** As the player is released. Main thread. */
    fun stop() {
        if (!running) return
        running = false
        displays()?.unregisterDisplayListener(displayListener)
    }

    private fun refresh() {
        val now = Hdr10PlusMask.shouldMask(Build.VERSION.SDK_INT, hdrTypes())
        if (now != mask) {
            Log.i(TAG, if (now) "The TV doesn't report HDR10+: HDR10+ metadata is kept from the decoder" else
                "The TV reports HDR10+: HDR10+ metadata goes to the decoder")
        }
        mask = now
    }

    /** The HDR types the TV reports (Android 7 and later), or null when they can't be read. */
    private fun hdrTypes(): IntArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        return try {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay?.hdrCapabilities?.supportedHdrTypes
        } catch (e: RuntimeException) {
            null
        }
    }

    // By name: looking it up by class needs Android 6.
    private fun displays() = activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    private companion object {
        const val TAG = "FirePlayer"
    }
}
