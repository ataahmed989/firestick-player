package app.fireplayer.player

/**
 * Whether the player's screen saves where the film is as it stops (PlayerActivity.onStop). Leaving with Back, Home, the
 * sleep timer or the film's end saves already ([left]), and the player is stopped after: saved again, the library wrote
 * the same place a second time and Glass Home was told twice. Unless a jump landed after that save ([moved]): one being
 * lined up as Home was pressed or the sleep timer ran out lands only then (in onStop, or by its own timer just before),
 * and its place is the one kept, as 1.0.366 kept it. One thread (the player screen's).
 */
class LeaveSave {
    private var saved = false

    /** Leaving saved where the film is. */
    fun left() {
        saved = true
    }

    /** A jump landed: what leaving saved is no longer where the film is. */
    fun moved() {
        saved = false
    }

    /** The screen stops: whether it saves the place now. Ready for the screen's next start either way. */
    fun saveOnStop(): Boolean {
        val save = !saved
        saved = false
        return save
    }
}
