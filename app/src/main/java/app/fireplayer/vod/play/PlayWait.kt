package app.fireplayer.vod.play

import app.fireplayer.vod.Cancel
import app.fireplayer.vod.LookupWhy
import app.fireplayer.vod.SourceList
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.SourcesRepository

/** Time and timers for the playing logic: the main thread's Handler in the app, a hand-moved clock in tests. */
interface Ticker {
    /** elapsedRealtime, ms. */
    fun now(): Long

    /** Runs [task] after [ms] on the main thread, unless cancelled first. */
    fun after(ms: Long, task: () -> Unit): Cancel
}

/**
 * Stage 1 on the title page (design-brief.md §6.5, r3_title-page §3.6): Play (Resume, an episode's OK) waits for both
 * add-ons, each up to its answer limit from its first ask. While it waits the Play pill says who it waits for, with a
 * thin bar, and the source line becomes the strip; OK again plays the best so far; Back cancels. Both answered (or the
 * limits passed), it plays at once. A list already complete plays without waiting.
 *
 * It asks with [LookupWhy.PLAY], which joins the page's own look-up when one is running (never a second request).
 */
class PlayWait(private val repo: SourcesRepository, private val ticker: Ticker, private val listener: Listener) {

    interface Listener {
        /** Still waiting: the pill's words and bar, and the strip (null before the first answer is known). */
        fun onWaiting(label: String, fraction: Float, strip: String?)

        /** The wait is over with [list]: play it (its best, or the player asks about a source not on Real-Debrid). */
        fun onPlay(target: SourceTarget, list: SourceList)

        /** Over with nothing to play ([message], plain words), or cancelled ([message] null): the page shows itself again. */
        fun onEnded(message: String?)
    }

    private var target: SourceTarget? = null
    private var list: SourceList? = null
    private var lookup: Cancel? = null
    private var tick: Cancel? = null
    private var pressedAt = 0L
    /** Bumped by every new wait and every end, so a late answer for an old one does nothing. */
    private var run = 0

    val isWaiting: Boolean get() = target != null

    /** What is waited for, or null. */
    val waitingFor: SourceTarget? get() = target

    /**
     * Play pressed for [what]. Waiting for it already: OK again, the best so far plays (true). Otherwise a new wait
     * starts (an older one ends), or [what] plays at once when its list is complete.
     */
    fun press(what: SourceTarget): Boolean {
        val now = target
        if (now != null && now.key == what.key) {
            val l = list
            // The best so far: only a source that plays by itself; none yet, the wait goes on.
            if (l != null && l.best != null) finish(l) else listener.onWaiting(label(), fraction(), l?.let { PlayRules.strip(it, ticker.now()) })
            return true
        }
        if (now != null) stop()
        repo.cached(what)?.takeIf { it.complete }?.let { done ->
            // As after a wait: nothing to play is said on the page, without film mode or the player.
            if (PlayRules.playable(done)) listener.onPlay(what, done) else listener.onEnded(PlayRules.sourceLine(done) ?: NOTHING)
            return false
        }
        val mine = ++run
        target = what
        list = null
        pressedAt = ticker.now()
        listener.onWaiting(label(), 0f, null)
        val c = repo.lookUp(what, LookupWhy.PLAY) { l -> if (run == mine) onList(l) }
        if (run == mine) lookup = c else c.cancel()
        if (run == mine) schedule(mine)
        return false
    }

    /** Back while waiting: the wait ends, nothing plays (true); false when nothing was waiting. */
    fun cancel(): Boolean {
        if (target == null) return false
        stop()
        listener.onEnded(null)
        return true
    }

    /** The page went away: the wait ends without a word. */
    fun release() = stop()

    private fun onList(l: SourceList) {
        list = l
        if (PlayRules.waitedOut(l, ticker.now())) finish(l) else update()
    }

    private fun schedule(mine: Int) {
        tick = ticker.after(TICK_MS) {
            if (run != mine) return@after
            val l = list
            if (l != null && PlayRules.waitedOut(l, ticker.now())) {
                finish(l)
                return@after
            }
            update()
            schedule(mine)
        }
    }

    private fun update() {
        val l = list
        listener.onWaiting(label(), fraction(), l?.let { PlayRules.strip(it, ticker.now()) })
    }

    private fun label(): String {
        val l = list ?: return "Waiting for sources · ${((ticker.now() - pressedAt).coerceAtLeast(0) / 1000)} s"
        return PlayRules.waitingLabel(l, ticker.now(), pressedAt)
    }

    private fun fraction(): Float = list?.let { PlayRules.waitFraction(it, ticker.now()) } ?: 0f

    private fun finish(l: SourceList) {
        val what = target ?: return
        stop()
        if (PlayRules.playable(l)) listener.onPlay(what, l) else listener.onEnded(PlayRules.sourceLine(l) ?: NOTHING)
    }

    private fun stop() {
        run++
        target = null
        lookup?.cancel()
        lookup = null
        tick?.cancel()
        tick = null
    }

    companion object {
        /** The pill and strip count once a second. */
        const val TICK_MS = 1_000L
        const val NOTHING = "No sources found"
    }
}
