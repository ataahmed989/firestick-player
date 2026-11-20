package app.fireplayer.vod.ui

/**
 * When the page header moves on to its next title (pure, unit-tested; the clock is passed in). While idle it goes
 * through the page's feed (Top 10, or Popular's first 10) every [everyMs], round and round. [everyMs] is asked at the
 * start of each wait (Settings › Home Header › Time on each title, [HeaderIdle]): a new time applies from the next
 * one. It holds still while paused (the page hidden, the header scrolled away, a window open) and while it follows the chosen title of the
 * page's Continue row; when either ends, the full wait starts again.
 *
 * With focus on one of the banner's buttons it doesn't stop (tuning round 1): once [everyMs] has passed with no key
 * pressed it moves on, and keeps going. Every key on the buttons starts the wait again ([key]), as do focus arriving
 * there and leaving ([focusButtons], [leaveButtons]). A Continue card's title it was following there is left the same
 * way: the feed goes on from where it was.
 *
 * D-pad Up on the buttons moves on at once ([step]), the same change as a turn of its own, the full wait starting
 * again from it. A held Up moves one title per repeat, but no repeat while a change is under way ([changeMs]).
 */
class HeaderRotation(
    private val everyMs: () -> Long = { EVERY_MS },
    private val changeMs: Long = VodSizes.HEADER_CHANGE_MS,
) {

    /** Titles in the feed. */
    var count = 0
        private set

    /** The feed's title shown while rotating. */
    var index = 0
        private set

    var paused = true
        private set

    var following = false
        private set

    /** Focus is on one of the banner's buttons. */
    var onButtons = false
        private set

    private var nextAt = NEVER

    /** Until when the last change (a turn, or Up) is under way. */
    private var changingUntil = Long.MIN_VALUE

    /**
     * Moving on by itself: a feed of two or more, not paused, and not following a Continue card, unless focus is on
     * the banner's buttons (from there it leaves the card's title too).
     */
    val running: Boolean get() = !paused && count > 1 && (!following || onButtons)

    /** A new feed of [count] titles. The title shown stays when it still exists; the wait starts again. */
    fun setFeed(count: Int, now: Long, keepIndex: Boolean = true) {
        this.count = count.coerceAtLeast(0)
        if (!keepIndex || index >= this.count) index = 0
        reschedule(now)
    }

    fun pause() {
        paused = true
        nextAt = NEVER
    }

    fun resume(now: Long) {
        paused = false
        reschedule(now)
    }

    /** Focus is in the page's Continue row: the header shows that title instead. */
    fun follow(now: Long) {
        following = true
        reschedule(now)
    }

    /** Focus left the Continue row: back to the feed's title, the wait starting again. */
    fun unfollow(now: Long) {
        following = false
        reschedule(now)
    }

    /** Focus came to the banner's buttons: the full wait starts again. */
    fun focusButtons(now: Long) {
        onButtons = true
        reschedule(now)
    }

    /** Focus left the banner's buttons: the full wait starts again (none while a Continue card is followed). */
    fun leaveButtons(now: Long) {
        if (!onButtons) return
        onButtons = false
        reschedule(now)
    }

    /** A key was pressed on one of the banner's buttons: the full wait starts again. */
    fun key(now: Long) {
        if (onButtons) reschedule(now)
    }

    /** Shows feed title [i] now; the full wait starts again. */
    fun jumpTo(i: Int, now: Long) {
        if (count <= 0) return
        index = i.coerceIn(0, count - 1)
        reschedule(now)
    }

    /** The full wait starts again (the turn came while a window was open over the page). */
    fun restart(now: Long) = reschedule(now)

    /** Milliseconds until the next title, or -1 when it isn't moving on by itself. */
    fun delayFrom(now: Long): Long = if (!running || nextAt == NEVER) -1 else (nextAt - now).coerceAtLeast(0)

    /**
     * Moves on when it's time: true when the header must now show the feed's title [index]. From a Continue card it
     * was following (focus on the buttons) that is the feed's title it left for the card, the following over; unless
     * that is the card's own title ([followedIsCurrent]), then the one after it, so it always changes.
     */
    fun advanceIfDue(now: Long, followedIsCurrent: Boolean = false): Boolean {
        if (!running || now < nextAt) return false
        index = nextIndex(followedIsCurrent)
        following = false
        changingUntil = now + changeMs
        nextAt = now + everyMs()
        return true
    }

    /**
     * D-pad Up on one of the banner's buttons ([repeat]: a held key's repeat): true when the header must now show the
     * feed's title [index], as [advanceIfDue] would (from a Continue card it was following, the feed's next title), the
     * full wait starting again. Nothing with one title or none, off the buttons, while paused, or for a repeat while
     * the last change is still under way.
     */
    fun step(now: Long, repeat: Boolean, followedIsCurrent: Boolean = false): Boolean {
        if (paused || !onButtons || count <= 1) return false
        if (repeat && now < changingUntil) return false
        index = nextIndex(followedIsCurrent)
        following = false
        changingUntil = now + changeMs
        reschedule(now)
        return true
    }

    /** The title [advanceIfDue] would show next (to fetch its picture and logo ahead), or -1. */
    fun nextIndex(followedIsCurrent: Boolean = false): Int = when {
        count <= 1 -> -1
        following && !followedIsCurrent -> index
        else -> (index + 1) % count
    }

    private fun reschedule(now: Long) {
        nextAt = if (running) now + everyMs() else NEVER
    }

    companion object {
        /** Glass Home's hero: a new title every 8 s (Time on each title's default). */
        const val EVERY_MS = VodSizes.HEADER_IDLE_DEFAULT_SEC * 1_000L
        private const val NEVER = Long.MAX_VALUE
    }
}

/** Settings › Home Header › Time on each title (pure, unit-tested): the banner's idle wait for a saved choice. */
object HeaderIdle {
    /** [seconds] as milliseconds; anything not among [VodSizes.HEADER_IDLE_SECONDS] is the default, 8 s. */
    fun ms(seconds: Int): Long =
        (if (seconds in VodSizes.HEADER_IDLE_SECONDS) seconds else VodSizes.HEADER_IDLE_DEFAULT_SEC) * 1_000L
}

/**
 * Whether a page's banner shows the focused card's title rather than going through its feed (pure, unit-tested). It
 * idles (goes through its feed) whenever focus is not on a Continue Watching card: on the other rows, on its own
 * buttons, in the rail, in Recent channels. Only a Continue card with focus ([continueCard]; never its See all) is
 * shown, and on Home only while Settings › Home Header lets it ([matchOnHome]); Movies and TV Shows always match. My
 * List's banner, which has no feed, shows whichever of its cards has focus.
 */
object HeaderFollow {
    fun follows(continueCard: Boolean, home: Boolean, matchOnHome: Boolean): Boolean =
        continueCard && (!home || matchOnHome)
}

/**
 * OK on one of the banner's buttons (pure, unit-tested). The buttons act on the title whose words are showing. While
 * the banner turns, the old words fade out, change, and the new ones fade in: the change, in the middle of the fade
 * with nothing showing, is the moment the title changes. A press belongs to the title showing when OK went down: its
 * release acts on that one, even if the words have changed by then (the turn was already under way), and on nothing
 * if another key came between, the release is on the other button, or no title was showing.
 */
class HeaderPress<B : Any, T : Any> {
    private var button: B? = null
    private var title: T? = null

    /** OK went down on [button] with [showing] in the words (null: none). Its repeats change nothing. */
    fun down(button: B, showing: T?, repeat: Int) {
        if (repeat != 0) return
        this.button = button
        title = showing
    }

    /** OK came up on [button]: what to act on, or null. */
    fun up(button: B, canceled: Boolean): T? {
        val on = title.takeIf { this.button == button && !canceled }
        cancel()
        return on
    }

    /** Another key, or focus gone from the buttons: the press is over. */
    fun cancel() {
        button = null
        title = null
    }
}

/**
 * What the banner's title box shows (pure, unit-tested): the title's logo, or its name where it has none. Never the
 * name first and then the logo over it: a logo that isn't in memory is waited for with the box empty
 * ([VodSizes.HEADER_LOGO_WAIT_MS]); only when it hasn't come by then does the name show, and then it stays for as long
 * as that title does.
 */
enum class HeaderTitleMode {
    /** No title. */
    NONE,
    /** The logo is on its way: the box is empty. */
    WAITING,
    LOGO,
    NAME;

    /** The logo arrived: shown if the box was waiting for it; a name already showing stays. */
    fun arrived(): HeaderTitleMode = if (this == WAITING) LOGO else this

    /** The wait is over: the name, if the box was still empty. */
    fun waited(): HeaderTitleMode = if (this == WAITING) NAME else this

    companion object {
        /**
         * A title is shown: its logo at once when it's [ready] (in memory); its name at once when it has no logo
         * ([hasLogo] false) or the logo [failed] lately; otherwise the box waits.
         */
        fun of(hasLogo: Boolean, ready: Boolean, failed: Boolean): HeaderTitleMode = when {
            !hasLogo -> NAME
            ready -> LOGO
            failed -> NAME
            else -> WAITING
        }
    }
}
