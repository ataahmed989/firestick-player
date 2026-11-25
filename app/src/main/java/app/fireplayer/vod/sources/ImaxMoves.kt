package app.fireplayer.vod.sources

import android.content.SharedPreferences
import app.fireplayer.vod.PlaybackSource

/**
 * The viewer's moves of a film's 4K copies between the Sources window's 4K IMAX and 4K groups (a copy's Details), kept in
 * the records ([app.fireplayer.data.Settings.records]) by film and file, never a link: "imax|tt0816692|rd|<hash>|<idx>"
 * is "1" (moved to 4K IMAX) or "0" (moved to 4K), whatever the copy's name says. [flush] puts a move on the disk at once.
 */
class ImaxMoves(private val records: SharedPreferences, private val flush: (SharedPreferences) -> Unit = {}) {

    /** A film's ([titleId]) moves, by [app.fireplayer.vod.ReleaseKey.storeKey], for [RankInput.imaxMove]. Any thread. */
    fun of(titleId: String): (String) -> Boolean? = { storeKey -> decode(records.getString(key(titleId, storeKey), null)) }

    /** [s], a copy of the film [titleId], moved to 4K IMAX ([imax]) or to 4K: kept for each file its row holds. */
    fun save(titleId: String, s: PlaybackSource, imax: Boolean) {
        val files = listOfNotNull(s.release) + s.offers.mapNotNull { it.release }
        if (files.isEmpty()) return
        val edit = records.edit()
        for (r in files.distinct()) edit.putString(key(titleId, r.storeKey), encode(imax))
        edit.apply()
        flush(records)
    }

    companion object {
        fun key(titleId: String, storeKey: String): String = "imax|$titleId|$storeKey"

        fun encode(imax: Boolean): String = if (imax) "1" else "0"

        fun decode(text: String?): Boolean? = when (text) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }
}
