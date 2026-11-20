package app.fireplayer.vod.data

import app.fireplayer.vod.Day
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * The dates on the title page, without java.time (minSdk 22, no desugaring). Pure: no Android.
 *
 * - Cinemeta writes instants as ISO 8601 in UTC ("2012-04-15T05:00:00.000Z"); a film's is its release day at midnight
 *   UTC, an episode's roughly the US West-Coast air time. TMDB writes plain days ("2024-02-27").
 * - An episode's [Day] is read in [PACIFIC]: the original US air date (99% of 1,782 episodes checked against TVmaze;
 *   the TV's own zone would put many a day early or late). A film's is read in [UTC], as Cinemeta writes it.
 */
object Dates {

    val UTC: TimeZone = TimeZone.getTimeZone("UTC")
    val PACIFIC: TimeZone = TimeZone.getTimeZone("America/Los_Angeles")

    /**
     * Epoch ms of an ISO 8601 instant or day: "2024-03-01T00:00:00.000Z", "2012-04-15T05:00:00Z",
     * "2026-11-20T08:00:00+01:00", "2024-02-27" (midnight UTC). Null for anything else (blank, "TBA", null).
     */
    fun parse(text: String?): Long? {
        val m = ISO.matchEntire(text?.trim() ?: return null) ?: return null
        val g = m.groupValues
        val year = g[1].toInt()
        val month = g[2].toInt()
        val day = g[3].toInt()
        if (month !in 1..12 || day !in 1..31) return null
        val hour = g[4].toIntOrNull() ?: 0
        val minute = g[5].toIntOrNull() ?: 0
        val second = g[6].toIntOrNull() ?: 0
        if (hour > 23 || minute > 59 || second > 60) return null
        val millis = g[7].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.take(3)?.toInt() ?: 0
        val cal = GregorianCalendar(UTC)
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, second)
        cal.set(Calendar.MILLISECOND, millis)
        var ms = cal.timeInMillis
        val zone = g[8]
        if (zone.isNotEmpty() && zone != "Z" && zone != "z") {
            val sign = if (zone[0] == '-') -1 else 1
            val digits = zone.substring(1).replace(":", "")
            val oh = digits.take(2).toIntOrNull() ?: return null
            val om = digits.drop(2).toIntOrNull() ?: 0
            ms -= sign * (oh * 3600_000L + om * 60_000L)
        }
        return ms
    }

    /** The calendar day of [ms] in [zone]. */
    fun dayOf(ms: Long, zone: TimeZone): Day {
        val cal = GregorianCalendar(zone)
        cal.timeInMillis = ms
        return Day(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    }

    /** A calendar for [dayOf] work in a loop (one allocation per list, not per episode). */
    internal fun calendar(zone: TimeZone): GregorianCalendar = GregorianCalendar(zone)

    internal fun dayOf(ms: Long, cal: GregorianCalendar): Day {
        cal.timeInMillis = ms
        return Day(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    }

    /** "2026-11-18": [ms]'s day in UTC, as TMDB writes its dates (they compare as text). */
    fun isoDay(ms: Long): String {
        val d = dayOf(ms, UTC)
        return String.format(Locale.ROOT, "%04d-%02d-%02d", d.year, d.month, d.day)
    }

    /** [ms]'s year in UTC. */
    fun yearOf(ms: Long): Int = dayOf(ms, UTC).year

    private val ISO = Regex(
        """(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,9}))?)?)?\s*(Z|z|[+-]\d{2}(?::?\d{2})?)?""",
    )
}
