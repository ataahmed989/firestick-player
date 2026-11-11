package app.fireplayer.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Whether the player's own thread still answers. One note at a time is sent to it; while one is unanswered, how long
 * it has waited is how long the thread has been silent. A thread blocked in a call into Fire OS's stuck sound system
 * takes every pause and jump sent to it with it, so only a new player can help (see [FreezeWatch]). One per player, so
 * an answer from an old player's thread can't vouch for a new one.
 */
class ThreadBeat(looper: Looper) {

    private val handler = Handler(looper)
    @Volatile private var answeredAt = 0L
    private var sentAt = 0L
    private val answer = Runnable { answeredAt = SystemClock.elapsedRealtime() }

    /** How long the note sent last has gone unanswered at [now] (elapsedRealtime); 0 if answered. Main thread. */
    fun silentMs(now: Long): Long {
        if (answeredAt >= sentAt) {
            sentAt = now
            handler.post(answer)
            return 0L
        }
        return now - sentAt
    }
}
