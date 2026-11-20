package app.fireplayer.vod.play

/**
 * How long a film takes to start, step by step, for one line in the log ("FirePlayerDiag: start …") that can be read
 * on the TV: from OK on the page (or the player screen's start, for a video opened by another app) to the player's
 * screen, the link checked, the player built, enough loaded, and the first picture. Each step is marked once, with the
 * time since the start; [line] puts them in the order they came.
 *
 * Only names and milliseconds: never a link or a title. Pure, so it can be tested; times are any clock in ms.
 */
class LaunchLog(private val startedAt: Long) {
    private val names = ArrayList<String>(8)
    private val times = ArrayList<Long>(8)

    /** The line was written: later marks belong to nothing (a jump's first frame, a new source). */
    var done = false
        private set

    /** [name] happened at [now]; only its first time counts. */
    fun mark(name: String, now: Long) {
        if (done || name in names) return
        names += name
        times += (now - startedAt).coerceAtLeast(0L)
    }

    /** Ms from the start to [name], or -1 when it wasn't marked. */
    fun at(name: String): Long = names.indexOf(name).let { if (it < 0) -1L else times[it] }

    /**
     * The line, and nothing more is marked: "start screen +212ms · link +1480ms · player +1731ms · loaded +2904ms ·
     * frame +3102ms"; [note] is added in brackets when not empty.
     */
    fun line(note: String = ""): String {
        done = true
        val steps = names.indices.joinToString(" · ") { "${names[it]} +${times[it]}ms" }
        return "start $steps" + if (note.isEmpty()) "" else " ($note)"
    }

    companion object {
        /** OK was pressed (the start itself, for the page's own log). */
        const val SCREEN = "screen"
        /** The sources were asked for, or the link check begun at OK was joined. */
        const val SOURCES = "sources"
        /** The link was checked: there's something to play. */
        const val LINK = "link"
        /** The player was built and given the film. */
        const val PLAYER = "player"
        /** Enough was loaded to play. */
        const val LOADED = "loaded"
        /** The first picture. */
        const val FRAME = "frame"
    }
}
