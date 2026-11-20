package app.fireplayer.playback

/**
 * The rules of the one live playback session, without Android or Media3 so they can be unit-tested.
 *
 * - One channel plays at a time, in one player, whichever rectangle shows it (guide miniature, full screen, later
 *   the top-right window). Moving between those is a surface change, never a retune.
 * - Tuning the channel that is already playing does nothing (no reconnect, no rebuffer).
 * - When the app's screen stops (Home button, another app, VOD player in front), the stream is stopped so the
 *   provider connection is released; the channel is remembered and comes back when the screen starts again.
 * - Starting a film stops live playback for good (until the viewer tunes again).
 * - Before opening a stream, the provider's connection allowance is asked ([ConnectionBudget]).
 */
class LiveSession(private val engine: Engine, private val budget: ConnectionBudget) {

    /** The player behind the session. */
    interface Engine {
        /** Opens [source] and plays it, replacing whatever played. */
        fun open(source: Source)
        /** Stops playing and lets go of the stream and its connection. */
        fun close()
    }

    /** What a channel plays from. [account] is the provider login it counts against (playlist id), [limit] its allowance. */
    data class Source(val key: String, val url: String, val account: String, val limit: Int, val headers: Map<String, String> = emptyMap())

    enum class State { IDLE, PLAYING, SUSPENDED }

    sealed class Tune {
        object Started : Tune()
        object AlreadyPlaying : Tune()
        /** The provider's allowance is used up by other playback in this app. */
        object NoConnection : Tune()
    }

    var state = State.IDLE
        private set
    var source: Source? = null
        private set

    val playingKey: String? get() = if (state == State.PLAYING) source?.key else null

    fun tune(next: Source): Tune {
        val now = source
        if (state == State.PLAYING && now != null && now.key == next.key && now.url == next.url) return Tune.AlreadyPlaying
        // Our own stream counts against its account only once: release it before asking for the next.
        if (state == State.PLAYING && now != null) budget.release(now.account, LEASE)
        if (!budget.acquire(next.account, next.limit, LEASE)) {
            if (state == State.PLAYING && now != null) {
                // Couldn't move to the new channel: keep the old one, reclaiming its lease.
                budget.acquire(now.account, now.limit, LEASE)
            }
            return Tune.NoConnection
        }
        source = next
        state = State.PLAYING
        engine.open(next)
        return Tune.Started
    }

    /** The screen showing live went away: stop the stream, remember the channel. */
    fun suspend() {
        if (state != State.PLAYING) return
        engine.close()
        source?.let { budget.release(it.account, LEASE) }
        state = State.SUSPENDED
    }

    /** The screen came back: the remembered channel plays again. True when it did. */
    fun resume(): Boolean {
        if (state != State.SUSPENDED) return false
        val s = source ?: return false
        if (!budget.acquire(s.account, s.limit, LEASE)) return false
        state = State.PLAYING
        engine.open(s)
        return true
    }

    /** The viewer closed live playback, or a film started: stop and forget the channel. */
    fun stop() {
        if (state == State.PLAYING) {
            engine.close()
            source?.let { budget.release(it.account, LEASE) }
        }
        state = State.IDLE
        source = null
    }

    private companion object {
        const val LEASE = "live"
    }
}

/**
 * How many streams this app has open per provider account, against the allowance the provider reports
 * (max_connections). Every player in the app asks here before connecting (the live session today; Multiview tiles
 * and source probes later), so the app as a whole never opens more streams than the account allows. An allowance
 * of 0 means unknown, which allows one stream at a time. Other devices on the same account aren't visible here.
 */
class ConnectionBudget {
    private val held = HashMap<String, MutableList<String>>()

    @Synchronized
    fun acquire(account: String, limit: Int, holder: String): Boolean {
        val list = held.getOrPut(account) { ArrayList() }
        val allowed = if (limit <= 0) 1 else limit
        if (list.size >= allowed) return false
        list += holder
        return true
    }

    @Synchronized
    fun release(account: String, holder: String) {
        held[account]?.let { it.remove(holder); if (it.isEmpty()) held.remove(account) }
    }

    @Synchronized
    fun inUse(account: String): Int = held[account]?.size ?: 0

    /** How many more streams [account] allows now (its allowance [limit], 0 meaning one, less those open). */
    @Synchronized
    fun free(account: String, limit: Int): Int = ((if (limit <= 0) 1 else limit) - inUse(account)).coerceAtLeast(0)
}
