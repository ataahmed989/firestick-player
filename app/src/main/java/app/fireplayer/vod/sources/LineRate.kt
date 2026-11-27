package app.fireplayer.vod.sources

import android.content.SharedPreferences
import java.util.Locale

/**
 * What this TV's connection was measured to bring a film lately, kept for sizing the film buffer
 * ([app.fireplayer.player.FilmMemory]). "Best for this TV" ([SourceRank]) doesn't weigh it: each file arrives as fast as
 * its own server sends it, and a copy that can't keep up while it plays is answered by Ask / Switch / Keep
 * ([app.fireplayer.vod.play.FilmSupply], which uses the playing file's own reading and [tooFast]).
 *
 * Only a measurement goes in, never a guess: [seen] is told every film's sustained arrival once it's measured over
 * enough seconds, whatever was answered about its stalls, and [short] the same when a film stalled past the limit. The
 * last [KEPT] readings are kept with their times, each forgotten after [KEEP_MS] (the line, the router's place or the
 * debrid service's servers may have changed). A reading within [MOVED] of the newest is the same sitting's until the
 * newest is [REFRESH_MS] old, then it is a reading of its own: so the last readings are the last sittings', and an
 * evening's low one goes once three others came after it. The figure used ([current]) is the lowest of them, a steady
 * one: it moves only when that lowest is [MOVED] or more away from it.
 *
 * One line of text in a preference ("72.4|1700000000000;80.1|1699990000000;=72.4": newest first, then the figure used;
 * an older version's readings without it read as they are, and it skips the figure); no links, no titles. Any thread
 * ([store] is a preference).
 */
class LineRate(private val store: Store, private val wall: () -> Long) {

    interface Store {
        fun read(): String?
        fun write(text: String?)
    }

    private class Reading(val mbps: Double, val at: Long)

    /** A film kept stalling with the download sustaining [mbps], about what the film needs: kept, and used, from now. */
    fun short(mbps: Double) {
        if (!sane(mbps)) return
        val now = (listOf(Reading(mbps, wall())) + readings()).take(KEPT)
        write(now, lowest(now))
    }

    /**
     * A film was seen to arrive at a sustained [mbps]: a reading of its own when it is [MOVED] or more away from the
     * newest, or the newest is [REFRESH_MS] old. True when the figure used ([current]) moved, and the lists should rank
     * again.
     */
    fun seen(mbps: Double): Boolean {
        if (!sane(mbps)) return false
        val kept = readings()
        val newest = kept.firstOrNull()
        if (newest != null && kotlin.math.abs(mbps - newest.mbps) < newest.mbps * MOVED && wall() - newest.at < REFRESH_MS) return false
        val before = current()
        val now = (listOf(Reading(mbps, wall())) + kept).take(KEPT)
        val figure = steady(before, lowest(now))
        write(now, figure)
        return figure != before
    }

    /**
     * The steady figure in Mb/s: the one used so far while the lowest reading of the last [KEPT] within [KEEP_MS] stays
     * within [MOVED] of it, else that lowest; null when there is none.
     */
    fun current(): Double? = steady(figure(), lowest(readings()))

    private fun steady(used: Double?, lowest: Double?): Double? = when {
        lowest == null -> null
        used == null || kotlin.math.abs(lowest - used) >= used * MOVED -> lowest
        else -> used
    }

    private fun lowest(list: List<Reading>): Double? = list.minOfOrNull { it.mbps }

    // The readings that count, newest first.
    private fun readings(): List<Reading> {
        val text = store.read() ?: return emptyList()
        val now = wall()
        return text.split(';').mapNotNull { part ->
            val f = part.split('|')
            val mbps = f.getOrNull(0)?.toDoubleOrNull() ?: return@mapNotNull null
            val at = f.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            val age = now - at
            if (mbps <= 0.0 || age < 0 || age > KEEP_MS) null else Reading(mbps, at)
        }.take(KEPT)
    }

    /** The figure used, as last written; null when none was. */
    private fun figure(): Double? =
        store.read()?.substringAfterLast(';', "")?.takeIf { it.startsWith('=') }?.substring(1)?.toDoubleOrNull()?.takeIf { it > 0.0 }

    private fun write(list: List<Reading>, figure: Double?) = store.write(
        list.take(KEPT).joinToString(";") { String.format(Locale.US, "%.1f|%d", it.mbps, it.at) } +
            (figure?.let { String.format(Locale.US, ";=%.1f", it) } ?: ""),
    )

    private fun sane(mbps: Double) = mbps > 0.0 && !mbps.isNaN() && !mbps.isInfinite()

    /** Forgets it. */
    fun forget() = store.write(null)

    companion object {
        /** A file needing more than this share of what the line sustains can't be kept from stalling. */
        const val SHORT_SHARE = 0.9
        /** The share of the line a lighter source a stalling film switches to may need ([app.fireplayer.vod.play.FilmSupply]). */
        const val SUSTAIN_SHARE = 0.75
        /** A remux's busiest scenes need this much more than its average: room it needs on the line. */
        const val REMUX_ROOM = 1.5
        /** Any other copy's. */
        const val ROOM = 1.3
        /** A reading this close to the newest (a share of it) is the same sitting's; the figure used moves no less. */
        const val MOVED = 0.15
        /** ...until the newest is this old: then it is a reading of its own. */
        const val REFRESH_MS = 60L * 60 * 1000
        const val KEEP_MS = 3L * 24 * 60 * 60 * 1000
        /** Readings kept: the figure used is the lowest of them. */
        const val KEPT = 3

        /** The preference file and key it's kept under ("fireplayer_sources", beside the add-ons' budget). */
        const val FILE = "fireplayer_sources"
        const val KEY = "line_mbps"
        /** The same over two connections ("Faster downloads"): a line that keeps two going brings more than one. */
        const val KEY_TWO = "line_mbps_2"

        /** Whether a file of [sourceMbps] is more than this line ([lineMbps]) keeps up with; false when either isn't known. */
        fun tooFast(sourceMbps: Double?, lineMbps: Double?): Boolean =
            sourceMbps != null && lineMbps != null && lineMbps > 0.0 && sourceMbps > lineMbps * SHORT_SHARE

        /**
         * Whether this line ([lineMbps]) leaves a file of [sourceMbps] room for its busy scenes: [REMUX_ROOM] times its
         * average for a [remux], [ROOM] for any other. True when either isn't known.
         */
        fun room(sourceMbps: Double?, lineMbps: Double?, remux: Boolean): Boolean =
            sourceMbps == null || lineMbps == null || lineMbps <= 0.0 || lineMbps >= sourceMbps * (if (remux) REMUX_ROOM else ROOM)

        /**
         * Whether a film's measurement belongs with the two-connection speed. [built]: its player was made with
         * "Faster downloads" on; [settingOn]: the setting as it is now; [twoOpen]: two requests are open right now.
         * With the setting on the lists are ranked by the two-connection speed, so a film the server keeps on one
         * request is measured there too (it is what this TV gets with the setting on, and a faster film replaces it);
         * turned off in the middle of a film, the two requests still open keep their speed out of the one-connection one.
         */
        fun forTwo(built: Boolean, settingOn: Boolean, twoOpen: Boolean): Boolean = built && (settingOn || twoOpen)

        /** The speed kept for one connection, or for two when [two] (each measured with that many, never mixed). */
        fun of(prefs: SharedPreferences, two: Boolean = false, wall: () -> Long = System::currentTimeMillis): LineRate = LineRate(
            object : Store {
                private val key = if (two) KEY_TWO else KEY
                override fun read(): String? = prefs.getString(key, null)
                override fun write(text: String?) {
                    if (text == null) prefs.edit().remove(key).apply() else prefs.edit().putString(key, text).apply()
                }
            },
            wall,
        )
    }
}
