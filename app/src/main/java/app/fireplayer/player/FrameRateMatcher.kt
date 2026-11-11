package app.fireplayer.player

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import androidx.annotation.RequiresApi
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Switches the TV's refresh rate to suit the video (e.g. 23.976 fps -> 24 Hz)
 * so camera pans don't judder. Fire TV honours preferredDisplayModeId.
 *
 * The TV's mode can change under a film too: turned on, reconnected, or Fire OS putting its own mode back. [watch]
 * says when that has settled, so the player can ask again ([suits], [match] with again).
 */
class FrameRateMatcher(private val activity: Activity) {

    private val handler = Handler(Looper.getMainLooper())
    private var switched = false

    /** When this window last asked the TV to switch (elapsedRealtime; 0 = never). */
    var askedAt = 0L
        private set

    // A request withdrawn to be made again (see match), and the mode it asks for.
    private var reaskId = 0
    private val reask = Runnable { ask(reaskId) }

    private var onSettled: (() -> Unit)? = null
    private var burstAt = 0L
    private val settled = Runnable {
        burstAt = 0L
        onSettled?.invoke()
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY || onSettled == null) return
            // Once it has been quiet for a moment, but not waiting forever on a TV that keeps reporting changes.
            val now = SystemClock.elapsedRealtime()
            if (burstAt == 0L) burstAt = now
            handler.removeCallbacks(settled)
            handler.postDelayed(settled, minOf(SETTLED_MS, burstAt + SETTLED_CAP_MS - now).coerceAtLeast(0L))
        }

        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    /**
     * Returns true when the TV is about to switch modes (the picture blanks briefly). [again]: the mode was asked for
     * before, but something else is showing now (Fire OS put its own back when the TV came on): asked for afresh.
     */
    fun match(fps: Float, again: Boolean = false): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || fps <= 0f) return false
        @Suppress("DEPRECATION")
        val display = activity.windowManager.defaultDisplay ?: return false
        val current = display.mode
        val best = pickMode(display.supportedModes.map { it.info() }, current.info(), fps) ?: return false
        val params = activity.window.attributes
        if (best.id == current.modeId) {
            // Showing it already: asked for all the same (no switch, no pause), so Android keeps the TV on it, and coming
            // back to the player after Fire OS changed it switches back.
            if (params.preferredDisplayModeId != current.modeId) ask(current.modeId)
            switched = true
            return false
        }
        if (params.preferredDisplayModeId == best.id) {
            // Asked for already: the TV is switching, or won't. Asking again would only pause the film again.
            if (!again) return false
            // Asking for the same mode again changes nothing, so the request is taken back and made again a moment later.
            ask(0)
            reaskId = best.id
            handler.removeCallbacks(reask)
            handler.postDelayed(reask, REASK_MS)
        } else {
            ask(best.id)
        }
        switched = true
        askedAt = SystemClock.elapsedRealtime()
        return true
    }

    private fun ask(modeId: Int) {
        val params = activity.window.attributes
        params.preferredDisplayModeId = modeId
        activity.window.attributes = params
    }

    /** Whether the mode showing suits a film of [fps], or nothing better can be asked for. */
    fun suits(fps: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        @Suppress("DEPRECATION")
        val display = activity.windowManager.defaultDisplay ?: return true
        return suits(display.supportedModes.map { it.info() }, display.mode.info(), fps)
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
        handler.removeCallbacks(reask)
        if (!switched || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        ask(0)
        switched = false
    }

    /** Calls [onSettled] once the TV's mode has changed and settled (2 s quiet, 6 s at most), until [unwatch]. Main thread. */
    fun watch(onSettled: () -> Unit) {
        unwatch()
        // Without display modes (before Android 6) there's nothing to ask for.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        this.onSettled = onSettled
        displays()?.registerDisplayListener(displayListener, handler)
    }

    fun unwatch() {
        if (onSettled == null) return
        onSettled = null
        burstAt = 0L
        handler.removeCallbacks(settled)
        displays()?.unregisterDisplayListener(displayListener)
    }

    private fun displays() = activity.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    /** A display mode, as far as matching goes. */
    class ModeInfo(val id: Int, val width: Int, val height: Int, val rate: Float)

    /** What to do when the TV's mode changed under a film (see [rematch]). */
    sealed class Rematch {
        /** Nothing: the mode suits, matching is off, or this film is done asking. */
        object Leave : Rematch()

        /** The player's own switch is still settling: look again in [ms]. */
        class Wait(val ms: Long) : Rematch()

        /** The TV left the mode right after it was asked for: it won't keep it, so this film stops asking. */
        object Refused : Rematch()

        /** Ask for the film's mode again. */
        object Ask : Rematch()
    }

    companion object {
        // How long the TV's mode must be quiet before it counts as settled, and the longest wait for that.
        const val SETTLED_MS = 2_000L
        const val SETTLED_CAP_MS = 6_000L
        // Between taking a request back and making it again, so the first reaches Android before the second.
        const val REASK_MS = 200L

        @RequiresApi(Build.VERSION_CODES.M)
        private fun Display.Mode.info() = ModeInfo(modeId, physicalWidth, physicalHeight, refreshRate)

        /**
         * After the TV's mode changed under a film: [matchedThisFilm] means its rate was matched already (or found
         * suiting), [refused] that the TV wouldn't keep it before. [switchAtMs] is when the player last had the TV switch,
         * [askedAtMs] when this window last asked for a mode.
         */
        fun rematch(
            nowMs: Long,
            matchOn: Boolean,
            matchedThisFilm: Boolean,
            refused: Boolean,
            fps: Float,
            suits: Boolean,
            switchAtMs: Long,
            askedAtMs: Long,
            settleMs: Long,
            refusedMs: Long,
        ): Rematch {
            if (!matchOn || !matchedThisFilm || refused || fps <= 0f || suits) return Rematch.Leave
            val settling = switchAtMs + settleMs - nowMs
            if (switchAtMs > 0 && settling > 0) return Rematch.Wait(settling)
            if (askedAtMs > 0 && nowMs - askedAtMs < refusedMs) return Rematch.Refused
            return Rematch.Ask
        }

        /** Whether [current] suits a film of [fps], or nothing better can be asked for. */
        fun suits(modes: List<ModeInfo>, current: ModeInfo, fps: Float): Boolean =
            pickMode(modes, current, fps)?.let { it.id == current.id } ?: true

        /** The mode to ask for: [current] if it suits [fps] already, null if none does. */
        fun pickMode(modes: List<ModeInfo>, current: ModeInfo, fps: Float): ModeInfo? {
            // Already showing the rate or an exact multiple of it: switching would only blank the screen.
            for (multiple in 1..3) {
                if (abs(current.rate - fps * multiple) < 0.01f) return current
            }
            val sameSize = modes.filter { it.width == current.width && it.height == current.height }
            // The closest rate (23.976 over 24 when the TV offers both), then a whole multiple (48, 60 for 30, 50 for 25).
            for (multiple in 1..3) {
                val target = fps * multiple
                sameSize.filter { rateMatches(it.rate, target) }
                    .minByOrNull { abs(it.rate - target) }
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
}
