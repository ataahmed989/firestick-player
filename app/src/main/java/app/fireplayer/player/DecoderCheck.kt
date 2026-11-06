package app.fireplayer.player

import android.media.MediaCodecList
import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/**
 * Asks Android for its list of decoders once per process, on a thread of its own, before any player is built.
 *
 * Now and then a Fire TV starts up with its decoder service stuck (on the Fire TV Stick 4K Plus, MediaTek's
 * decoder code deadlocks while the list is first built). Then asking for the list never returns, in every app, until
 * the TV restarts: a player built anyway would spin forever, holding a stream connection open. Asking here first
 * lets the player say so instead. If the service is stuck, this thread waits forever holding the list's lock, so
 * nothing on the main thread may ever ask for the list itself.
 */
object DecoderCheck {

    /** Whether the list came back, so players can be built. */
    @Volatile var answered = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val waiting = ArrayList<() -> Unit>()
    private var asked = false

    /** Starts asking, if nobody has yet. Main thread. */
    fun ask() {
        if (asked) return
        asked = true
        thread(name = "FirePlayerDecoderCheck", isDaemon = true) {
            runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }
            main.post {
                answered = true
                val ready = waiting.toList()
                waiting.clear()
                ready.forEach { it() }
            }
        }
    }

    /**
     * Runs [ready] once the list is in (at once if it already is), and [stuck] if it isn't in after [stuckAfterMs]
     * ([ready] still runs if it comes later). Returns a function that cancels both. Main thread.
     */
    fun whenAnswered(stuckAfterMs: Long, ready: () -> Unit, stuck: () -> Unit): () -> Unit {
        if (answered) {
            ready()
            return {}
        }
        ask()
        waiting += ready
        val late = Runnable { if (!answered) stuck() }
        main.postDelayed(late, stuckAfterMs)
        return {
            waiting.remove(ready)
            main.removeCallbacks(late)
        }
    }
}
