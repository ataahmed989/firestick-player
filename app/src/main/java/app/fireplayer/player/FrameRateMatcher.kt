package app.fireplayer.player

import android.app.Activity
import android.os.Build
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Switches the TV's refresh rate to suit the video (e.g. 23.976 fps -> 24 Hz)
 * so camera pans don't judder. Fire TV honours preferredDisplayModeId.
 */
class FrameRateMatcher(private val activity: Activity) {

    private var switched = false

    /** Returns true when the TV is about to switch modes (the picture blanks briefly). */
    fun match(fps: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || fps <= 0f) return false
        @Suppress("DEPRECATION")
        val display = activity.windowManager.defaultDisplay ?: return false
        val current = display.mode
        val best = pickMode(display.supportedModes, current, fps) ?: return false
        if (best.modeId == current.modeId) return false
        val params = activity.window.attributes
        // Asked for already: the TV is switching, or won't. Asking again would only pause the film again.
        if (params.preferredDisplayModeId == best.modeId) return false
        params.preferredDisplayModeId = best.modeId
        activity.window.attributes = params
        switched = true
        return true
    }

    /** Whether a mode this window asked for isn't showing: coming back to the player, the TV switches to it again. */
    fun switchPending(): Boolean {
        if (!switched || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val wanted = activity.window.attributes.preferredDisplayModeId
        @Suppress("DEPRECATION")
        val display = activity.windowManager.defaultDisplay ?: return false
        return wanted != 0 && display.mode.modeId != wanted
    }

    fun reset() {
        if (!switched || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val params = activity.window.attributes
        params.preferredDisplayModeId = 0
        activity.window.attributes = params
        switched = false
    }

    private fun pickMode(modes: Array<Display.Mode>, current: Display.Mode, fps: Float): Display.Mode? {
        // Already showing the rate or an exact multiple of it: switching would only blank the screen.
        for (multiple in 1..3) {
            if (abs(current.refreshRate - fps * multiple) < 0.01f) return current
        }
        val sameSize = modes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        // The closest rate (23.976 over 24 when the TV offers both), then a whole multiple (48, 60 for 30, 50 for 25).
        for (multiple in 1..3) {
            val target = fps * multiple
            sameSize.filter { rateMatches(it.refreshRate, target) }
                .minByOrNull { abs(it.refreshRate - target) }
                ?.let { return it }
        }
        return null
    }

    private fun rateMatches(refresh: Float, target: Float): Boolean {
        // 23.976 and 24 are treated as the same family; TVs often list only one.
        return abs(refresh - target) < 0.05f || refresh.roundToInt() == target.roundToInt() &&
            abs(refresh - target) < 0.1f
    }
}
