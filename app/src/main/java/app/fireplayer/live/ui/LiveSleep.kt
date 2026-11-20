package app.fireplayer.live.ui

/**
 * The live sleep timer's arithmetic (unit-tested). Times run on the elapsedRealtime clock, so a clock change on the TV
 * doesn't move them; the end of the programme (a wall-clock time from the guide) is turned into that clock when the
 * timer is set.
 */
object LiveSleep {
    const val OFF = 0
    /** "End of this programme": the live picture stops when the programme on now ends (needs the guide). */
    const val END_OF_PROGRAMME = -1
    /** The same choices as the VOD player's sleep timer. */
    val MINUTES = listOf(15, 30, 45, 60, 90, 120)

    /**
     * When a timer set now for [choice] (minutes, [OFF] or [END_OF_PROGRAMME]) runs out, on the elapsedRealtime clock
     * ([nowElapsed]); 0 for none: Off, or a programme end ([programmeStop], wall clock) that isn't known or has passed.
     */
    fun endsAt(choice: Int, nowElapsed: Long, nowWall: Long, programmeStop: Long?): Long = when {
        choice > 0 -> nowElapsed + choice * 60_000L
        choice == END_OF_PROGRAMME && programmeStop != null && programmeStop > nowWall -> nowElapsed + (programmeStop - nowWall)
        else -> 0L
    }

    /**
     * "Off", or the minutes left as the VOD player shows them ("25 min"), rounded up: "30 min" just after 30 min is
     * set, "1 min" in the last minute.
     */
    fun label(endsAt: Long, nowElapsed: Long): String =
        if (endsAt <= 0L) "Off" else "${minutesLeft(endsAt - nowElapsed)} min"

    private fun minutesLeft(ms: Long): Long = ((ms + 59_999) / 60_000).coerceAtLeast(1)

    /** How long until the label changes or the timer runs out, whichever is sooner (at least a quarter second). */
    fun nextCheckMs(endsAt: Long, nowElapsed: Long): Long {
        val left = endsAt - nowElapsed
        if (left <= 0L) return 0L
        // The label drops a minute when what's left reaches the next whole minute below it.
        val toNextMinute = (left % 60_000).let { if (it == 0L) 60_000L else it }
        return minOf(left, toNextMinute).coerceAtLeast(250L)
    }
}
