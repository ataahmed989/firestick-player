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
 * Whether the TV is to get HDR10+, for the film's video renderer ([PacedVideoRenderer]): when it can't show it, or
 * Settings › This TV › HDR10+ reaches this TV is off ([reaches]), HDR10+ is hidden from the decoder ([Hdr10PlusMask]).
 * Read from the display as each player starts and again whenever the display changes (HDR turned on at the TV, the TV
 * switched or reconnected), so a TV that gains or loses HDR10+ in the middle of a film is followed from its next frame.
 * What a film's own HDR10+ adds to that is [Hdr10PlusWatch]'s. One per player, like [VsyncFix].
 */
class Hdr10PlusGate(private val activity: Activity, private val reaches: () -> Boolean = { true }) {

    /**
     * HDR10+ is hidden from the decoder: the TV doesn't report it, or Settings › This TV › HDR10+ reaches this TV is
     * off ([reaches], read as the player starts and whenever the display changes), where the TV is promised the steady
     * HDR10 inside. Read on the playback thread.
     */
    @Volatile var mask = false
        private set

    /** Why ([Hdr10PlusWatch.PASS], [Hdr10PlusWatch.TV], [Hdr10PlusWatch.SETTING]). Read on the playback thread. */
    @Volatile var why = Hdr10PlusWatch.PASS
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var told = false

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
        val types = hdrTypes()
        val reports = types != null && Hdr10PlusMask.HDR_TYPE_HDR10_PLUS in types
        val now = reason(Hdr10PlusMask.shouldMask(Build.VERSION.SDK_INT, types), reports, reaches())
        if (now != why || !told) {
            told = true
            Log.i(TAG, when (now) {
                Hdr10PlusWatch.TV -> "The TV doesn't report HDR10+: HDR10+ metadata is kept from the decoder"
                Hdr10PlusWatch.SETTING -> "The TV reports HDR10+, and Settings > This TV says it doesn't reach it: HDR10+ " +
                    "metadata is kept from the decoder"
                else -> "The TV reports HDR10+: HDR10+ metadata goes to the decoder"
            })
        }
        why = now
        mask = now != Hdr10PlusWatch.PASS
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

    companion object {
        private const val TAG = "FirePlayer"

        /**
         * Why HDR10+ is hidden for this TV: it doesn't report it ([tvLacks], [Hdr10PlusMask.shouldMask]); else it
         * [reports] HDR10+ and the setting is off ([reaches] false); or it isn't hidden. The setting only counts where
         * the TV reports HDR10+, which is where Settings › This TV shows it: an older Android can't report HDR10+ at
         * all, and a TV with HDR there is given it as before.
         */
        fun reason(tvLacks: Boolean, reports: Boolean, reaches: Boolean): Int = when {
            tvLacks -> Hdr10PlusWatch.TV
            reports && !reaches -> Hdr10PlusWatch.SETTING
            else -> Hdr10PlusWatch.PASS
        }
    }
}
