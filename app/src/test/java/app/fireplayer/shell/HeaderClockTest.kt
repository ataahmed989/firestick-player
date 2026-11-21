package app.fireplayer.shell

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class HeaderClockTest {
    private val sydney = TimeZone.getTimeZone("Australia/Sydney")
    private lateinit var locale: Locale

    @Before
    fun australian() {
        locale = Locale.getDefault()
        Locale.setDefault(Locale("en", "AU"))
    }

    @After
    fun restore() = Locale.setDefault(locale)

    private fun at(zone: TimeZone, year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(zone, Locale.US).apply {
            clear()
            set(year, month, day, hour, minute)
        }.timeInMillis

    @Test
    fun usStyleWhateverTheSystemLanguage() {
        val now = at(sydney, 2026, Calendar.NOVEMBER, 21, 12, 22)
        assertEquals("Sat, Nov 21 · 12:22 PM", HeaderClock.text(now, hour24 = false, zone = sydney))
    }

    @Test
    fun morningAndSingleDigits() {
        val now = at(sydney, 2026, Calendar.MARCH, 3, 9, 5)
        assertEquals("Tue, Mar 3 · 9:05 AM", HeaderClock.text(now, hour24 = false, zone = sydney))
    }

    @Test
    fun keepsTheTvs24HourClock() {
        val now = at(sydney, 2026, Calendar.NOVEMBER, 21, 14, 5)
        assertEquals("Sat, Nov 21 · 14:05", HeaderClock.text(now, hour24 = true, zone = sydney))
    }

    @Test
    fun followsTheZone() {
        val now = at(sydney, 2026, Calendar.NOVEMBER, 21, 12, 22)
        assertEquals("Fri, Nov 20 · 8:22 PM", HeaderClock.text(now, hour24 = false, zone = TimeZone.getTimeZone("America/New_York")))
    }
}
