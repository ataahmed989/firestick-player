package app.fireplayer.vod.sources

import android.content.SharedPreferences
import java.util.Locale

/**
 * What this TV's connection was measured to bring while a film kept stopping to load for want of it, kept so "Best
 * for this TV" stops picking files it can't keep up with (see [SourceRank.fit]: such a row says why).
 *
 * Only a measurement goes in, never a guess: [short] is told the download's sustained speed when a film stalled past
 * the limit and its own rate was within [SHORT_SHARE] of that speed. It's forgotten after [KEEP_MS] (the line, the
 * router's place or the debrid service's servers may have changed), and replaced as soon as a film is seen to load
 * [CLEARLY] faster ([seen]). Until then a file needing more than [SHORT_SHARE] of it counts as "likely not" for this TV;
 * the viewer can still pick it in Sources.
 *
 * One line of text in a preference ("72.4|1700000000000"); no links, no titles. Any thread ([store] is a preference).
 */
class LineRate(private val store: Store, private val wall: () -> Long) {

    interface Store {
        fun read(): String?
        fun write(text: String?)
    }

    /** A film kept stalling with the download sustaining [mbps], about what the film needs: remembered from now. */
    fun short(mbps: Double) {
        if (mbps <= 0.0 || mbps.isNaN() || mbps.isInfinite()) return
        store.write(String.format(Locale.US, "%.1f|%d", mbps, wall()))
    }

    /** A film loaded at a sustained [mbps]: a remembered speed clearly under that was the moment's, and is replaced. */
    fun seen(mbps: Double) {
        val kept = current() ?: return
        if (mbps.isNaN() || mbps.isInfinite() || mbps < kept * CLEARLY) return
        store.write(String.format(Locale.US, "%.1f|%d", mbps, wall()))
    }

    /** The speed remembered, in Mb/s; null when there is none, or it's older than [KEEP_MS]. */
    fun current(): Double? {
        val parts = store.read()?.split('|') ?: return null
        val mbps = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val at = parts.getOrNull(1)?.toLongOrNull() ?: return null
        val age = wall() - at
        if (mbps <= 0.0 || age < 0 || age > KEEP_MS) return null
        return mbps
    }

    /** Forgets it. */
    fun forget() = store.write(null)

    companion object {
        /** A file needing more than this share of what the line sustains can't be kept from stalling. */
        const val SHORT_SHARE = 0.9
        /** A file is one "this connection sustains" when it needs no more than this share: room for busy scenes. */
        const val SUSTAIN_SHARE = 0.75
        /** A speed seen this much above the one remembered replaces it. */
        const val CLEARLY = 1.25
        const val KEEP_MS = 14L * 24 * 60 * 60 * 1000

        /** The preference file and key it's kept under ("fireplayer_sources", beside the add-ons' budget). */
        const val FILE = "fireplayer_sources"
        const val KEY = "line_mbps"

        /** Whether a file of [sourceMbps] is more than this line ([lineMbps]) keeps up with; false when either isn't known. */
        fun tooFast(sourceMbps: Double?, lineMbps: Double?): Boolean =
            sourceMbps != null && lineMbps != null && lineMbps > 0.0 && sourceMbps > lineMbps * SHORT_SHARE

        fun of(prefs: SharedPreferences, wall: () -> Long = System::currentTimeMillis): LineRate = LineRate(
            object : Store {
                override fun read(): String? = prefs.getString(KEY, null)
                override fun write(text: String?) {
                    if (text == null) prefs.edit().remove(KEY).apply() else prefs.edit().putString(KEY, text).apply()
                }
            },
            wall,
        )
    }
}
