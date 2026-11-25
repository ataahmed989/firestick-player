package app.fireplayer.vod.play

/**
 * When what a play measured ranks its title's Sources list again ([app.fireplayer.PlayerActivity]'s rememberQuality):
 * as it's first kept in a play (the picture's rate, measured), and once more if the file's size comes only after that;
 * every play, whatever an earlier play kept of the file (its rate and record may have changed since). Main thread.
 */
class MeasuredRerank {
    private var rate = false
    private var size = false

    /** Another play began (a copy started, or the same again): its figures rank the list afresh. */
    fun newPlay() {
        rate = false
        size = false
    }

    /** This play's figures were just kept, the file's size with them or not yet ([sized]): whether the list ranks again now. */
    fun kept(sized: Boolean): Boolean {
        if (rate && (size || !sized)) return false
        rate = true
        if (sized) size = true
        return true
    }
}
