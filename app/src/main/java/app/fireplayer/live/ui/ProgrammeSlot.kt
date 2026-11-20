package app.fireplayer.live.ui

/**
 * The stretch of time full screen's line stands for while a channel has no programme now: the half hour the clock is
 * in (9:00 to 9:30), or up to the next programme when one starts before the half hour is over (9:00 to 9:20). Half
 * hours are the viewer's own clock's (a time zone a quarter of an hour off UTC still gets :00 and :30). Pure.
 */
object ProgrammeSlot {
    const val SLOT_MS = 30 * 60_000L

    class Span(val start: Long, val end: Long)

    /**
     * The slot [now] is in. [nextStart] is when the channel's next known programme starts (null: none known);
     * [utcOffsetMs] is the viewer's clock ahead of UTC at [now] (TimeZone.getOffset).
     */
    fun of(now: Long, nextStart: Long?, utcOffsetMs: Int): Span {
        val local = now + utcOffsetMs
        val start = local - Math.floorMod(local, SLOT_MS) - utcOffsetMs
        var end = start + SLOT_MS
        if (nextStart != null && nextStart > now && nextStart < end) end = nextStart
        return Span(start, end)
    }
}
