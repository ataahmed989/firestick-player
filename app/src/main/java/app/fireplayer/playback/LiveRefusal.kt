package app.fireplayer.playback

/**
 * A channel the provider says isn't there or isn't allowed (HTTP 404, 410, 401, 403 and the other 4xx answers), told
 * apart from one that is only slow or down for a moment (5xx, a network error: those stay "Reconnecting...").
 *
 * Measured on the user's TV: a channel answered 404 seven times in a row and the screen only showed the buffering
 * symbol for minutes. So a second refusal in a row (the first is tried again after a second: a provider that allows
 * one connection can refuse for a moment while it closes the last channel's, and that must not flash a message on
 * every channel change) is said in plain words ([message]) and the channel is then tried again slowly ([waitMs]) for
 * as long as the viewer stays on it. Anything that plays clears it at once ([playing]). Pure: no Android, no Media3.
 */
class LiveRefusal {
    /** The provider's answer being shown (0: none, the channel is only reconnecting or fine). */
    var code = 0
        private set

    /** Refusals in a row (another kind of failure, or frames playing, ends the run). */
    private var streak = 0
    private var played = false

    val shown: Boolean get() = code != 0

    /** Another channel, or this one opened afresh: it hasn't played yet. */
    fun opened() {
        code = 0
        streak = 0
        played = false
    }

    /** Frames are playing: whatever was said is wrong now. */
    fun playing() {
        played = true
        code = 0
        streak = 0
    }

    /**
     * The channel failed with HTTP [httpCode] (0: not an HTTP answer). True when it is now shown as not available.
     */
    fun failed(httpCode: Int): Boolean {
        if (!refuses(httpCode)) {
            streak = 0
            code = 0
            return false
        }
        streak++
        if (streak >= REFUSALS_TO_SAY) code = httpCode
        return shown
    }

    /** How long to wait before asking a refused channel again: one quick try (a provider still closing the last
     *  channel's connection can refuse for a moment), then slowly. */
    fun waitMs(): Long = REFUSED_WAITS_MS[(streak - 1).coerceIn(0, REFUSED_WAITS_MS.size - 1)]

    /** What the overlay says, on two lines (null while nothing is refused). */
    fun message(): String? = if (shown) "$NOT_AVAILABLE\nThe provider answered $code" else null

    companion object {
        const val NOT_AVAILABLE = "This channel isn't available right now"
        /** A channel is only called refused after this many refusals in a row. */
        const val REFUSALS_TO_SAY = 2
        val REFUSED_WAITS_MS = longArrayOf(1_000, 5_000, 15_000, 30_000)

        /**
         * The answers that mean "not there" or "not for you": every 4xx but 408 (the server timed out waiting) and
         * 429 (too many requests), which are worth the quick tries.
         */
        fun refuses(httpCode: Int): Boolean = httpCode in 400..499 && httpCode != 408 && httpCode != 429
    }
}
