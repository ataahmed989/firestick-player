package app.fireplayer.shell

import android.content.Context
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The header's date and time, the one way every page writes it (Home, Movies, TV Shows, My List, Search, Live TV, a
 * service's or studio's page, the players' clocks): US style whatever the TV's language, "Sat, Nov 21 · 12:22 PM",
 * or "Sat, Nov 21 · 14:05" where the TV is set to the 24-hour clock. Made when the minute changes, never per frame.
 * Main thread.
 */
object HeaderClock {
    const val PATTERN_12 = "EEE, MMM d · h:mm a"
    const val PATTERN_24 = "EEE, MMM d · HH:mm"

    private val twelve = SimpleDateFormat(PATTERN_12, Locale.US)
    private val twentyFour = SimpleDateFormat(PATTERN_24, Locale.US)
    private val date = Date()

    /** [now] as the header shows it, by the TV's own 12 or 24-hour setting. */
    fun text(context: Context, now: Long): String = text(now, DateFormat.is24HourFormat(context), TimeZone.getDefault())

    /** [now] in [zone], on the 24-hour clock or not (pure but for its formats, so it's unit-tested). */
    fun text(now: Long, hour24: Boolean, zone: TimeZone): String {
        val format = if (hour24) twentyFour else twelve
        if (format.timeZone != zone) format.timeZone = zone
        date.time = now
        return format.format(date)
    }
}
