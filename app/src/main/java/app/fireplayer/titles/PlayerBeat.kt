package app.fireplayer.titles

import android.content.SharedPreferences

/**
 * The player's "I'm on" note for the title server ([TitleService]), which leaves TMDB alone while a film plays and
 * carries on once the notes stop coming (Fire OS can refuse the player's "stopped" message once FirePlayer is behind
 * another app).
 *
 * It has a small file of its own ([FILE]): written every [EVERY_MS] while a film plays, it used to go into the app's
 * settings file, which Android writes whole (every film's place, the subtitle timings, the rows) and syncs to flash on
 * each change, and which the title server's process then read whole again. Nobody reads it while the title server is
 * off, so it isn't written then.
 */
object PlayerBeat {
    /** The beat's own settings file. */
    const val FILE = "player_beat"
    /** How often the player notes that it is on. */
    const val EVERY_MS = 30_000L
    /** How long without a note the server takes the player as stopped. */
    const val GONE_MS = 90_000L
    private const val AT = "at"

    /** The player is on at [now] (ms): noted in [beat] (the [FILE] settings), only when the title server is [on]. */
    fun note(beat: SharedPreferences, on: Boolean, now: Long) {
        if (!on) return
        beat.edit().putLong(AT, now).apply()
    }

    /** Whether the player said it was on within [GONE_MS] of [now] (ms). */
    fun heard(beat: SharedPreferences, now: Long): Boolean = now - beat.getLong(AT, 0L) < GONE_MS
}
