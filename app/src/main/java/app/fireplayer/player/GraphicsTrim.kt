package app.fireplayer.player

import android.content.ComponentCallbacks2
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Has Android let go of the graphics memory FirePlayer's windows no longer use: the drawing cache's working pictures
 * (layers that windows and rows slid in on, glyphs, pictures drawn once). On a Fire TV Stick 4K Max, 41 MB of such
 * pictures sat unused in FirePlayer during a 4K film that needed the memory. What's on screen stays; what's needed
 * again is made again as it's drawn (a few milliseconds).
 *
 * Two ways, the first that works:
 * - Android's own trim, as it does itself when an app's screens are hidden. It isn't public and newer Android refuses
 *   it to apps (Fire OS 8 does: seen on the Stick 4K Max), so it's tried once by name.
 * - Making a drawing surface of our own and letting it go again, which has Android drop every graphics resource that
 *   went unused for [STALE_MS] (Glass Home's way, public from Android 10). So this one only helps [STALE_MS] after the
 *   last use: callers ask again that long after their screen went quiet ([staleOnly]).
 *
 * Main thread.
 */
object GraphicsTrim {
    private const val TAG = "FirePlayer"
    /** Android lets go of a graphics resource that went unused this long, when asked the second way. */
    const val STALE_MS = 30_000L
    private var hiddenMissing = false
    private var lastAt = 0L

    /** Only resources unused for [STALE_MS] go (the first way isn't there): ask again that long after the last drawing. */
    val staleOnly: Boolean get() = hiddenMissing

    /**
     * Trims now; [minGapMs] apart at least (0: always), so it can be asked for each time the controls hide. True when
     * a trim was made.
     */
    fun trim(minGapMs: Long = 0L): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (minGapMs > 0L && lastAt != 0L && now - lastAt < minGapMs) return false
        lastAt = now
        if (!hiddenMissing) {
            try {
                val global = Class.forName("android.view.WindowManagerGlobal")
                val instance = global.getMethod("getInstance").invoke(null)
                global.getMethod("trimMemory", Int::class.javaPrimitiveType).invoke(instance, ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
                return true
            } catch (e: Throwable) {
                hiddenMissing = true
                Log.i(TAG, "Android's own graphics trim isn't open to apps here (${e.javaClass.simpleName}): unused graphics go after ${STALE_MS / 1000} s instead")
            }
        }
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            android.graphics.HardwareRenderer().destroy()
            true
        } catch (e: Throwable) {
            false
        }
    }
}
