package app.fireplayer.live

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.fireplayer.PlayerActivity
import app.fireplayer.playback.LiveSession
import app.fireplayer.playback.PlayerHost
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps playlist and EPG updates from clashing with playback ([SyncPlan] decides, this acts):
 * - Before each request to a provider, [clear] lets go of a live channel from that provider when the viewer asked
 *   for the update (the picture stays, one line says why) or, for an automatic update, says to wait.
 * - The update takes one connection lease of its own from the same [PlayerHost.budget] as playback, so the app never
 *   opens more connections to a provider than the account allows.
 * - [end] returns the lease and brings the channel back at live; automatic updates that waited run again at the
 *   next quiet moment (nothing playing), and every hour the app stays open updates that came due are looked for.
 *
 * [clear] and [end] run on the update thread (LiveSync's worker); every look at playback happens on the main thread.
 */
class SyncGuard(
    private val app: Application,
    private val main: Handler,
    /** Looks for due automatic updates again (LiveSync.updateDue). */
    private val onQuiet: () -> Unit,
    /** Shows the hold line on the live screen (main thread). */
    private val notice: (String) -> Unit,
) {
    // ---- Update thread state (one job at a time) ----
    private var trigger = SyncPlan.Trigger.AUTO
    private var epgOnly = false
    private var heldKey: String? = null
    private val leases = LinkedHashSet<String>()
    /** Something was put off for a quiet moment in this job. */
    var deferred = false
        private set
    /** Why the last [clear] said no (shown as the playlist's status when it wasn't a deferral). */
    var refusal: String? = null
        private set

    // ---- Main thread state ----
    private var films = 0
    private var started = 0
    private var watching = false
    private var holding = false

    init {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                if (activity is PlayerActivity) films++
            }
            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (activity is PlayerActivity) { films = (films - 1).coerceAtLeast(0); checkQuiet() }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** A job starts (update thread). */
    fun begin(trigger: SyncPlan.Trigger, epgOnly: Boolean) {
        this.trigger = trigger
        this.epgOnly = epgOnly
        heldKey = null
        leases.clear()
        deferred = false
        refusal = null
    }

    /**
     * Before a request for [p]'s playlist or EPG to [requestUrl] (update thread). True: go ahead. False: skip it,
     * either put off ([deferred]) or refused ([refusal], the connection is in use).
     */
    fun clear(p: Playlist, requestUrl: String): Boolean {
        refusal = null
        val auto = trigger == SyncPlan.Trigger.AUTO
        val result = onMain(if (auto) Step.DEFER else Step.GO) {
            val host = PlayerHost.peek()
            val src: LiveSession.Source? = host?.session?.takeIf { it.state == LiveSession.State.PLAYING }?.source
            val action = SyncPlan.decide(trigger, p.id, requestUrl, p.url, SyncPlan.Playing(src?.url, src?.account, films > 0, host?.screenOn == true))
            if (action == SyncPlan.Action.DEFER) return@onMain Step.DEFER
            var letGo = false
            if (action == SyncPlan.Action.LET_GO && host != null) {
                val key = host.letGoForUpdate()
                if (key != null) {
                    if (heldKey == null) heldKey = key
                    letGo = true
                    startHolding()
                }
            }
            if ((letGo || SyncPlan.usesProvider(p.url, requestUrl)) && p.id !in leases) {
                if (!PlayerHost.budget.acquire(p.id, p.maxConnections, LEASE)) return@onMain Step.BUSY
                leases += p.id
            }
            if (letGo) Step.SETTLE else Step.GO
        }
        return when (result) {
            Step.GO -> true
            Step.SETTLE -> {
                // The player closes its connection on its own threads after release: give the provider a moment.
                SystemClock.sleep(SyncPlan.SETTLE_MS)
                true
            }
            Step.DEFER -> { deferred = true; false }
            Step.BUSY -> { refusal = "Not updated: the provider's connection is in use"; false }
        }
    }

    /** The job is over, done or failed (update thread): the lease goes back and the channel comes back. */
    fun end() {
        val held = heldKey
        val taken = leases.toList()
        val waitForQuiet = deferred
        heldKey = null
        leases.clear()
        main.post {
            for (account in taken) PlayerHost.budget.release(account, LEASE)
            stopHolding()
            val host = PlayerHost.peek()
            if (host != null) {
                val s = host.session
                if (SyncPlan.bringBack(held, s.source?.key, s.state == LiveSession.State.SUSPENDED, host.screenOn, taken.isNotEmpty())) {
                    host.backAfterUpdate()
                }
            }
            if (waitForQuiet) watchQuiet()
        }
    }

    // ---- The hold line ----

    private fun startHolding() {
        if (holding) return
        holding = true
        main.post(holdLine)
    }

    private fun stopHolding() {
        holding = false
        main.removeCallbacks(holdLine)
    }

    /** The line is shown again every few seconds (the notice hides itself), for as long as the hold lasts. */
    private val holdLine = object : Runnable {
        override fun run() {
            if (!holding) return
            notice(SyncPlan.holdNotice(epgOnly))
            main.postDelayed(this, 3_000)
        }
    }

    // ---- Waiting for a quiet moment ----

    private val statusListener = object : PlayerHost.Listener {
        // Not acted on inside the host's own call: it may be going through its listeners.
        override fun onLiveStatus(status: PlayerHost.Status) { main.post { checkQuiet() } }
    }

    private fun watchQuiet() {
        if (watching) return
        watching = true
        PlayerHost.peek()?.addListener(statusListener)
        main.post { checkQuiet() }
    }

    private fun checkQuiet() {
        if (!watching) return
        val host = PlayerHost.peek()
        val livePlaying = host != null && (host.session.state == LiveSession.State.PLAYING || host.screenOn)
        if (livePlaying || films > 0) return
        watching = false
        host?.removeListener(statusListener)
        onQuiet()
    }

    private val hourly = object : Runnable {
        override fun run() {
            // While a film plays nothing automatic goes ([SyncPlan.decide]): the look waits for the next quiet moment
            // rather than starting a job that could only put everything off.
            if (started > 0) { if (films > 0) watchQuiet() else onQuiet() }
            main.postDelayed(this, SyncPlan.CHECK_EVERY_MS)
        }
    }

    init {
        main.postDelayed(hourly, SyncPlan.CHECK_EVERY_MS)
    }

    // ---- Main thread calls from the update thread ----

    private enum class Step { GO, SETTLE, DEFER, BUSY }

    /** Runs [block] on the main thread and waits for it (never called on the main thread itself). */
    private fun <T> onMain(fallback: T, block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        val gaveUp = AtomicBoolean(false)
        var out = fallback
        main.post {
            if (!gaveUp.get()) {
                try { out = block() } finally { latch.countDown() }
            }
        }
        if (latch.await(MAIN_WAIT_S, TimeUnit.SECONDS)) return out
        gaveUp.set(true)
        // It may have started just now: wait for it rather than guess.
        return if (latch.await(MAIN_WAIT_S, TimeUnit.SECONDS)) out else fallback
    }

    private companion object {
        /** The update's lease holder name in the connection budget (live playback holds "live"). */
        const val LEASE = "update"
        const val MAIN_WAIT_S = 5L
    }
}
