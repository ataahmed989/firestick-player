package app.fireplayer.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Room made for a film is given back to the TV at once, and the film is told when it's there.
 *
 * What FirePlayer lets go of for a film (the pages' pictures and caches as the player covers them, the buffer of the
 * film or episode before) is only memory nobody points to any more: Android takes it back the next time it collects,
 * which it does when the app has made enough new things, not when something big was let go of. Until then the TV
 * counts it as in use. A film's buffer is sized by the memory free as its player is built ([FilmMemory.plan]), and
 * that comes first: the player is built as soon as the link is checked (about half a second after OK), the page it
 * was started from lets go of its pictures only once the player's screen covers it. So the buffer was held down for
 * memory that was on its way back.
 *
 * [made] has Android collect now, on a thread of its own at background priority (the app's other threads carry on;
 * they're held for a millisecond or two), and the [film] on screen is told a moment after, when what the pictures held
 * outside the app's own heap has been freed as well, with how much came back ([FilmMemory.roomGained]). Its player
 * then sizes the buffer again ([FilmMemory.afterRoom]).
 *
 * The same goes for a film's buffer squeezed while the TV is short of memory: what it gives back is collected as it
 * comes down to its new size ([FilmMemory.givenBack]), so the TV has it when it needs it.
 *
 * Main thread, but for the collecting itself.
 */
object FilmRoom {
    private const val TAG = "FirePlayer"
    /**
     * After collecting, the pictures' own memory is freed by Android's clean-up thread, a moment later. The free
     * memory is read at once, and again this long after (ms), the film being told at the last: the most seen to
     * have come back counts, so the film's decoder taking its frames between two readings hides only what came after.
     */
    private val READ_AFTER_MS = longArrayOf(0L, 60L, 150L)

    /** The film player on screen, as this needs it. */
    interface Film {
        /** The memory its buffer holds now, in bytes. */
        fun heldBytes(): Long

        /**
         * Memory let go of at [askedAt] (elapsedRealtime) is back with the TV; [gainedBytes] is how much more is free
         * for this film than just before (0 when that can't be said: it wasn't the film on screen then).
         */
        fun roomMade(askedAt: Long, gainedBytes: Long)
    }

    /**
     * The film player on screen: it sets itself as its player is built and takes itself away as the player goes
     * (only itself: the next one may be there already).
     */
    var film: Film? = null

    private val main = Handler(Looper.getMainLooper())

    // One collection at a time, and at most one more waiting (asked for while the first ran: more may have been let
    // go of since); any further ask meanwhile is covered by the one waiting.
    private val worker = ThreadPoolExecutor(
        0, 1, 5L, TimeUnit.SECONDS, LinkedBlockingQueue(1),
        { r -> Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "FirePlayerRoom").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy(),
    )

    /**
     * Something big was just let go of: Android collects it now, and the [film] on screen hears when it's back.
     * [forFilm]: it was let go of to make room for the film (the pages' pictures, the player before), so what comes
     * back is told as gained; not when it's the film's own buffer giving memory back while the TV is short of it
     * (that is no room made: the buffer must not grow for it).
     */
    fun made(context: Context, forFilm: Boolean = true) {
        val app = context.applicationContext
        val askedAt = SystemClock.elapsedRealtime()
        val asker = if (forFilm) film else null
        val before = if (asker != null) freeFor(app, asker) else 0L
        worker.execute {
            try {
                // Not System.gc(): Android's may put it off until finalizers have run.
                Runtime.getRuntime().gc()
            } catch (e: Throwable) {
                Log.w(TAG, "Couldn't collect memory let go of: ${e.javaClass.simpleName}")
            }
            val took = SystemClock.elapsedRealtime() - askedAt
            if (!forFilm) {
                // The film's own memory given back: nothing to tell it.
                Log.i(TAG, "Memory let go of collected in $took ms")
                return@execute
            }
            // On the main thread only.
            var gain = 0L
            for ((i, after) in READ_AFTER_MS.withIndex()) {
                main.postDelayed({
                    val now = film
                    // Another film player than the one asked about (or none then): how much came back for it can't be said.
                    if (asker != null && now === asker) gain = maxOf(gain, FilmMemory.roomGained(before, freeFor(app, asker)))
                    if (i == READ_AFTER_MS.lastIndex) {
                        Log.i(TAG, "Memory let go of collected in $took ms" + if (gain > 0L) ": ${gain shr 20} MiB back for the film" else "")
                        now?.roomMade(askedAt, gain)
                    }
                }, after)
            }
        }
    }

    /** What's free for [f]'s film: the TV's free memory and what its buffer holds itself; 0 when the first isn't known. */
    private fun freeFor(app: Context, f: Film?): Long {
        val free = FilmMemory.freeNow(app)
        return if (free <= 0L) 0L else free + (f?.heldBytes() ?: 0L).coerceAtLeast(0L)
    }
}
