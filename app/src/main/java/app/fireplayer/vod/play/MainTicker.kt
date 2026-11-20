package app.fireplayer.vod.play

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.fireplayer.vod.Cancel

/** [Ticker] on the main thread: elapsedRealtime and the main Handler. */
object MainTicker : Ticker {
    private val main = Handler(Looper.getMainLooper())

    override fun now(): Long = SystemClock.elapsedRealtime()

    override fun after(ms: Long, task: () -> Unit): Cancel {
        val r = Runnable { task() }
        main.postDelayed(r, ms)
        return Cancel { main.removeCallbacks(r) }
    }
}
