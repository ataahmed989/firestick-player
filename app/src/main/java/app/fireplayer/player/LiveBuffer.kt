package app.fireplayer.player

/**
 * Live TV buffer (Settings): how much of a live channel the player loads ahead, in milliseconds as Media3's
 * DefaultLoadControl takes them. Small starts a channel sooner and holds less in memory, but a shaky stream stalls it
 * more easily; Large rides out a shaky stream, but starts a little later and holds more. The Fire TV is short on
 * memory, so even Large stays modest. Medium is the buffer live TV always had.
 */
enum class LiveBuffer(
    val label: String,
    /** Loading goes on until at least this much is ahead... */
    val minMs: Int,
    /** ...and stops at this much. */
    val maxMs: Int,
    /** A channel starts once this much is loaded... */
    val startMs: Int,
    /** ...and carries on after a stall once this much is. */
    val afterStallMs: Int,
) {
    SMALL("Small", 5_000, 10_000, 1_000, 2_000),
    MEDIUM("Medium", 15_000, 30_000, 1_500, 3_000),
    LARGE("Large", 30_000, 45_000, 2_500, 5_000),
    ;

    companion object {
        /** The size saved under [name]; Medium when none is saved yet (or the name isn't known). */
        fun named(name: String?): LiveBuffer = entries.firstOrNull { it.name == name } ?: MEDIUM
    }
}
