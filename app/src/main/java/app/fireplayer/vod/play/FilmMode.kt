package app.fireplayer.vod.play

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import app.fireplayer.player.FilmRoom
import app.fireplayer.player.GraphicsTrim

/**
 * Film mode memory and background release (design-brief.md §6.6), for the shell (HomeActivity).
 *
 * Before a film or episode starts from Home, Movies or TV Shows ([beforeFilm]): Live TV stops and its decoder is
 * released at once (the film needs it), and a small snapshot of the screen is kept. As soon as the player covers the
 * shell ([onStop]) the rest goes: the pages' pictures, the live picture's glass frames, the guide's listings and logos,
 * and the graphics caches. Returning shows the snapshot at once over the page while the posters come back from the disk
 * cache ([onStart]).
 *
 * In the background for other reasons (the Home button, a link opened from Settings) the same release waits
 * [BACKGROUND_RELEASE_MS] first: coming back sooner finds everything as it was.
 */
class FilmMode(private val activity: Activity, private val hooks: Hooks) {

    interface Hooks {
        /** Live TV stops and its player is released (decoders freed). */
        fun stopLive()

        /** Pictures, glass frames, guide listings and logos leave memory (the shell is covered or in the background). */
        fun release()
    }

    private val main = Handler(Looper.getMainLooper())
    private var snapshot: Bitmap? = null
    private var overlay: ImageView? = null
    /** A film is starting: the release runs as soon as the shell is covered. */
    private var filmStarting = false
    /** The release ran while away: the snapshot shows on return. */
    private var released = false
    /** When the film now starting was asked for (elapsedRealtime), for the log; 0 when none is. */
    private var filmAskedAt = 0L
    private val backgroundRelease = Runnable { releaseNow() }

    /**
     * A film starts from a page: Live TV's decoder is freed, the snapshot is asked for, and [go] runs at once (the
     * player starts). The snapshot is a copy of this window's own picture, which the player's screen coming up over
     * it doesn't change, so the start no longer waits for it (up to [CAPTURE_WAIT_MS] before).
     */
    fun beforeFilm(go: () -> Unit) {
        filmStarting = true
        filmAskedAt = SystemClock.elapsedRealtime()
        hooks.stopLive()
        capture(null)
        go()
    }

    /** The shell paused: a snapshot for coming back, unless a film's start already took one. */
    fun onPause() {
        if (!filmStarting) capture(null)
    }

    /** The shell is covered or in the background: released now for a film, later otherwise. */
    fun onStop() {
        main.removeCallbacks(backgroundRelease)
        if (filmStarting) {
            filmStarting = false
            releaseNow()
        } else {
            main.postDelayed(backgroundRelease, BACKGROUND_RELEASE_MS)
        }
    }

    /** Back: the release that hadn't run yet doesn't; after one, the snapshot covers the page while it fills again. */
    fun onStart(over: FrameLayout) {
        main.removeCallbacks(backgroundRelease)
        main.removeCallbacks(trimStale)
        filmStarting = false
        filmAskedAt = 0L
        val shot = snapshot
        if (released && shot != null) show(over, shot) else dropSnapshot()
        released = false
    }

    fun release() {
        main.removeCallbacks(backgroundRelease)
        main.removeCallbacks(trimStale)
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = null
        dropSnapshot()
    }

    private fun releaseNow() {
        released = true
        hooks.release()
        trimGraphics()
        // The pictures let go of are only free once Android collects them: now, so the film starting has the memory
        // (its buffer is sized by what's free), and a background release really gives it back to the TV.
        FilmRoom.made(activity)
    }

    /**
     * Half-size copy of the window (PixelCopy, Android 8+: glass and hardware pictures included). [then] runs once it's
     * taken, or at once without one (older Android, or the copy failed).
     */
    private fun capture(then: (() -> Unit)?) {
        val decor = activity.window?.decorView
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || decor == null || decor.width <= 0 || decor.height <= 0) {
            then?.invoke()
            return
        }
        val bitmap = try {
            Bitmap.createBitmap(decor.width / 2, decor.height / 2, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            then?.invoke()
            return
        }
        var done = false
        val finish = Runnable {
            if (done) return@Runnable
            done = true
            then?.invoke()
        }
        try {
            PixelCopy.request(activity.window, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    dropSnapshot()
                    snapshot = bitmap
                } else {
                    bitmap.recycle()
                }
                main.removeCallbacks(finish)
                finish.run()
            }, main)
            // A copy that never answers doesn't hold the film back.
            main.postDelayed(finish, CAPTURE_WAIT_MS)
        } catch (e: RuntimeException) {
            Log.w(TAG, "No snapshot: ${e.javaClass.simpleName}")
            bitmap.recycle()
            finish.run()
        }
    }

    private fun show(over: FrameLayout, shot: Bitmap) {
        overlay?.let { over.removeView(it) }
        val v = ImageView(activity).apply {
            setImageBitmap(shot)
            scaleType = ImageView.ScaleType.FIT_XY
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isFocusable = false
        }
        overlay = v
        over.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        v.animate().alpha(0f).setStartDelay(SNAPSHOT_HOLD_MS).setDuration(SNAPSHOT_FADE_MS).withEndAction {
            over.removeView(v)
            if (overlay === v) overlay = null
            v.setImageDrawable(null)
            if (snapshot === shot) dropSnapshot()
        }.start()
    }

    private fun dropSnapshot() {
        val s = snapshot ?: return
        snapshot = null
        if (overlay == null) s.recycle()
    }

    /**
     * The graphics caches the shell's windows hold (textures, glyphs, layers), flushed as Android does when an app's
     * screens are hidden ([GraphicsTrim]).
     */
    private fun trimGraphics() {
        val trimmed = GraphicsTrim.trim()
        // For a film: how long after OK the room was made (the film's buffer is sized again once it's there, FilmRoom).
        val asked = filmAskedAt
        filmAskedAt = 0L
        val after = if (asked > 0L) " ${SystemClock.elapsedRealtime() - asked} ms after OK" else ""
        Log.i(TAG, "Film mode: the pages' pictures and caches let go$after" + if (trimmed) ", graphics trimmed" else "")
        // Where Android only lets go of graphics unused for half a minute, the pages' go once they've been hidden that long.
        main.removeCallbacks(trimStale)
        if (GraphicsTrim.staleOnly) main.postDelayed(trimStale, GraphicsTrim.STALE_MS + 1_000L)
    }

    private val trimStale = Runnable { if (released) GraphicsTrim.trim() }

    companion object {
        /** In the background this long before the shell's memory is released (not at once: a quick return stays as it was). */
        const val BACKGROUND_RELEASE_MS = 90_000L
        /** The longest a film's start waits for the snapshot. */
        const val CAPTURE_WAIT_MS = 150L
        /** The snapshot stays this long on return, then fades as the posters are back. */
        const val SNAPSHOT_HOLD_MS = 700L
        const val SNAPSHOT_FADE_MS = 220L
        private const val TAG = "FirePlayerFilmMode"
    }
}
