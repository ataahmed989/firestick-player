package app.fireplayer.ui

/**
 * When a picture that's waiting says so: the buffering symbol only once the wait has lasted about 300 ms (a quick
 * channel change never flashes it), a line of text ("Reconnecting…") only once a reconnect has lasted a few seconds,
 * and both gone the moment frames play. A channel the provider refused ([refused]) has its line at once: there's
 * nothing to wait out before saying why. Pure: the clock is passed in (any steady clock, ms).
 */
class BusySigns(
    private val spinnerAfterMs: Long = SPINNER_AFTER_MS,
    private val textAfterMs: Long = TEXT_AFTER_MS,
) {
    var spinner = false
        private set
    var text = false
        private set

    private var busySince = NONE
    private var reconnectSince = NONE

    /**
     * The picture is [busy] (loading with nothing playing) and maybe [reconnecting] or [refused] at [now]. Returns how
     * long until the signs next change by themselves (ask again then), or [NONE] when nothing is due.
     */
    fun update(busy: Boolean, reconnecting: Boolean, now: Long, refused: Boolean = false): Long {
        if (!busy) {
            busySince = NONE
            reconnectSince = NONE
            spinner = false
            text = false
            return NONE
        }
        if (busySince == NONE) busySince = now
        if (!reconnecting) reconnectSince = NONE else if (reconnectSince == NONE) reconnectSince = now
        spinner = now - busySince >= spinnerAfterMs
        text = refused || (reconnectSince != NONE && now - reconnectSince >= textAfterMs)
        var next = NONE
        if (!spinner) next = busySince + spinnerAfterMs - now
        if (reconnectSince != NONE && !text) {
            val due = reconnectSince + textAfterMs - now
            if (next == NONE || due < next) next = due
        }
        return next
    }

    companion object {
        const val NONE = -1L
        const val SPINNER_AFTER_MS = 300L
        const val TEXT_AFTER_MS = 3_000L
    }
}
