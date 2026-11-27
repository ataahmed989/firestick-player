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
 * - An automatic update already under way stops at its next safe point ([goesOn], [check]) once a film is in
 *   front, or once its request would now wait (the provider's own channel, or Live TV about to tune one, wants the
 *   connection): put off, not failed ([SyncPlan.jobGoesOn]).
 * - [end] returns the lease and brings the channel back at live; automatic updates that waited run again at the
 *   next quiet moment (nothing playing), and every hour the app stays open updates that came due are looked for.
 *
 * [clear], [goesOn], [check] and [end] run on the update thread (LiveSync's worker); every look at playback happens on
 * the main thread.
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
    @Volatile private var trigger = SyncPlan.Trigger.AUTO
    private var epgOnly = false
    private var heldKey: String? = null
    private val leases = LinkedHashSet<String>()
    /** Something was put off for a quiet moment in this job. */
    var deferred = false
        private set
    /** Why the last [clear] said no (shown as the playlist's status when it wasn't a deferral). */
    var refusal: String? = null
        private set
    /** The job under way, a new token for each [begin]: a stop asked for ([stopped]) is for that job alone. */
    @Volatile private var job = Any()
    /** The automatic job whose request under way would now wait ([lookAgain]): it stops at its next safe point. */
    @Volatile private var stopped: Any? = null

    // ---- Main thread state ----
    private var films = 0
    /** A film is in front ([films] above 0), read at the update's safe points. */
    @Volatile private var filmOn = false
    private var started = 0
    private var watching = false
    private var holding = false
    /** The automatic request under way, looked at again as live playback changes ([lookAgain]); null otherwise. */
    private var underWay: UnderWay? = null
    /** The live player whose status is followed (while waiting for a quiet moment or an automatic request runs). */
    private var listening: PlayerHost? = null

    private class UnderWay(val job: Any, val account: String, val url: String, val provider: String)

    init {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
                if (activity is PlayerActivity) {
                    films++
                    // An automatic update under way stops at its next safe point ([goesOn]).
                    filmOn = true
                }
            }
            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (activity is PlayerActivity) {
                    films = (films - 1).coerceAtLeast(0)
                    filmOn = films > 0
                    checkQuiet()
                }
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
        job = Any()
        this.trigger = trigger
        this.epgOnly = epgOnly
        heldKey = null
        leases.clear()
        deferred = false
        refusal = null
    }

    /**
     * Whether the job goes on at this safe point (update thread): the viewer's own always does; an automatic one not
     * once a film is in front or its request was stopped ([SyncPlan.jobGoesOn]). False marks it put off: it runs again
     * at the next quiet moment.
     */
    fun goesOn(): Boolean {
        if (SyncPlan.jobGoesOn(trigger, filmOn, stopped === job)) return true
        deferred = true
        return false
    }

    /** As [goesOn], stopping the job with [SyncPlan.PutOff]: at a download's next read, before storing what was read. */
    fun check() {
        if (!goesOn()) throw SyncPlan.PutOff()
    }

    /**
     * Before a request for [p]'s playlist or EPG to [requestUrl] (update thread). True: go ahead. False: skip it,
     * either put off ([deferred]) or refused ([refusal], the connection is in use).
     */
    fun clear(p: Playlist, requestUrl: String): Boolean {
        refusal = null
        val auto = trigger == SyncPlan.Trigger.AUTO
        val token = job
        val result = onMain(if (auto) Step.DEFER else Step.GO) {
            val host = PlayerHost.peek()
            val action = SyncPlan.decide(trigger, p.id, requestUrl, p.url, playingNow(host))
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
            // An automatic request is looked at again as live playback changes, until the job ends.
            if (auto) {
                underWay = UnderWay(token, p.id, requestUrl, p.url)
                listen()
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
            underWay = null
            if (waitForQuiet) watchQuiet()
            listen()
        }
    }

    /** What plays now, as far as updates care (main thread). */
    private fun playingNow(host: PlayerHost? = PlayerHost.peek()): SyncPlan.Playing {
        val src: LiveSession.Source? = host?.session?.takeIf { it.state == LiveSession.State.PLAYING }?.source
        return SyncPlan.Playing(src?.url, src?.account, films > 0, host?.screenOn == true)
    }

    /**
     * Live playback changed: an automatic request under way that would now wait (the provider's own channel plays,
     * or Live TV is about to tune one: [SyncPlan.requestGoesOn]) stops at its next safe point, so its connection is
     * the channel's in a moment rather than after the whole download. Main thread.
     */
    private fun lookAgain() {
        val w = underWay ?: return
        if (stopped === w.job) return
        if (!SyncPlan.requestGoesOn(SyncPlan.Trigger.AUTO, w.account, w.url, w.provider, playingNow())) stopped = w.job
    }

    /** The live player's status is followed while it's needed: waiting for a quiet moment, or an automatic request under way. */
    private fun listen() {
        val host = if (watching || underWay != null) PlayerHost.peek() else null
        if (host === listening) return
        listening?.removeListener(statusListener)
        listening = host
        host?.addListener(statusListener)
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
        override fun onLiveStatus(status: PlayerHost.Status) {
            main.post {
                lookAgain()
                checkQuiet()
            }
        }
    }

    private fun watchQuiet() {
        if (watching) return
        watching = true
        listen()
        main.post { checkQuiet() }
    }

    private fun checkQuiet() {
        if (!watching) return
        val host = PlayerHost.peek()
        val livePlaying = host != null && (host.session.state == LiveSession.State.PLAYING || host.screenOn)
        if (livePlaying || films > 0) return
        watching = false
        listen()
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
