package app.fireplayer.vod.sources

/**
 * What a release's names say of the torrent an add-on's one size is for (MediaFusion's, for an episode): a season pack
 * ([Kind.PACK]: "S01" or "Season 1" with no episode, "Complete", a range of episodes "S01E06-10", "E01-E08", of seasons
 * "S01-S02", "Season 1-2"), one episode ([Kind.SINGLE]: "S01E06", "1x06", "Episode 6"), or nothing ([Kind.UNSAID]). Each
 * name is read alone (a torrent's "S01" and its file's "S01E06" are a pack's file); a pack in any name is a pack, one
 * episode only when no name says pack. Pure.
 */
object PackWords {

    enum class Kind { PACK, SINGLE, UNSAID }

    /**
     * [episodes]: how many episodes a range names ("E06-10": 5); [oneSeason]: the pack is one season's, so the season's
     * episode count shares its size out (not a run of seasons, nor a "Complete" naming no season).
     */
    class Read(val kind: Kind, val episodes: Int? = null, val oneSeason: Boolean = false)

    val UNSAID = Read(Kind.UNSAID)

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /** "S01E06-10", "S01E06-E10", "S01E06 – S01E10". */
    private val EPISODE_RANGE = rx("""(?<![a-z0-9])s(\d{1,2})[ ._-]?e(\d{1,3})[ ._]?[-–~][ ._]?(?:s\d{1,2}[ ._-]?)?e?(\d{1,3})(?![a-z0-9])""")
    /** "E01-E08". */
    private val E_RANGE = rx("""(?<![a-z0-9])e(\d{1,3})[ ._]?[-–~][ ._]?e(\d{1,3})(?![a-z0-9])""")
    /** "Episodes 1-8", "Episode 1-8". */
    private val WORD_RANGE = rx("""(?<![a-z])episodes?[ ._]?(\d{1,3})[ ._]?[-–~][ ._]?(\d{1,3})(?![a-z0-9])""")
    /** "S01-S02", "S01-02", "Season 1-2", "Seasons 1 & 2". */
    private val SEASON_RANGE = rx("""(?<![a-z0-9])s\d{1,2}[ ._]?[-–~][ ._]?s?\d{1,2}(?![a-z0-9])|(?<![a-z])seasons?[ ._]?\d{1,2}[ ._]?(?:[-–~&]|to|and)[ ._]?\d{1,2}(?![a-z0-9])""")
    /** "S01E06", "1x06", "Episode 6", "Ep 6". */
    private val ONE_EPISODE = rx("""(?<![a-z0-9])s\d{1,2}[ ._-]?e\d{1,3}(?![0-9])|(?<![a-z0-9])\d{1,2}x\d{2,3}(?![a-z0-9])|(?<![a-z])(?:episode|ep)[ ._]?\d{1,3}(?![0-9])""")
    /** "S01" not followed by an episode, "Season 1". */
    private val SEASON = rx("""(?<![a-z0-9])s\d{1,2}(?![0-9])(?![ ._-]?e\d)|(?<![a-z])season[ ._]?\d{1,2}(?![0-9])""")
    private val COMPLETE = rx("""(?<![a-z])complete(?![a-z])""")

    /** [names] read together: a pack's first (one with a range's count first), else one episode, else nothing said. */
    fun read(names: List<String?>): Read {
        var single = false
        var pack: Read? = null
        for (n in names) {
            if (n.isNullOrBlank()) continue
            val r = one(n)
            when (r.kind) {
                Kind.PACK -> if (pack == null || (pack.episodes == null && r.episodes != null)) pack = r
                Kind.SINGLE -> single = true
                Kind.UNSAID -> Unit
            }
        }
        return pack ?: if (single) Read(Kind.SINGLE) else UNSAID
    }

    /** One name read alone. */
    fun one(name: String): Read {
        (EPISODE_RANGE.find(name)?.let { it.groupValues[2] to it.groupValues[3] }
            ?: E_RANGE.find(name)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: WORD_RANGE.find(name)?.let { it.groupValues[1] to it.groupValues[2] })?.let { (a, b) ->
            val from = a.toIntOrNull()
            val to = b.toIntOrNull()
            if (from != null && to != null && to > from && to - from < MAX_RANGE) return Read(Kind.PACK, to - from + 1, oneSeason = true)
        }
        if (SEASON_RANGE.containsMatchIn(name)) return Read(Kind.PACK)
        if (ONE_EPISODE.containsMatchIn(name)) return Read(Kind.SINGLE)
        if (SEASON.containsMatchIn(name)) return Read(Kind.PACK, oneSeason = true)
        if (COMPLETE.containsMatchIn(name)) return Read(Kind.PACK)
        return UNSAID
    }

    /** A range wider than this isn't one ("S01E06-720p" isn't 715 episodes). */
    private const val MAX_RANGE = 100
}
