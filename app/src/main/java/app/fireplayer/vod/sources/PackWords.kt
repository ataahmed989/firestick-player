package app.fireplayer.vod.sources

/**
 * What a release's names say of the torrent an add-on's one size is for (MediaFusion's, for an episode): a season pack
 * ([Kind.PACK]: "S01" or "Season 1" with no episode, "Complete", a range of episodes "S01E06-10", "1x01-1x13",
 * "S01E01.to.S01E10", "E01-E08", of seasons "S01-S02", "Season 1-2", "S01.S02.S03"), one episode ([Kind.SINGLE]:
 * "S01E06", "1x06", "Episode 6", anime's "S2 - 05"), or nothing ([Kind.UNSAID]). Each name is read alone (a torrent's
 * "S01" and its file's "S01E06" are a pack's file); a pack in any name is a pack, one episode only when a torrent's own
 * name says so and no name says pack. Pure.
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

    /** Between a range's two ends: "-", "–" or "~" (spaced or not), or "to". */
    private const val TO = """(?:[ ._]?[-–~][ ._]?|[ ._]to[ ._])"""

    /** "S01E06-10", "S01E06-E10", "S01E06 – S01E10", "S01E01.to.S01E10"; across seasons "S01E01-S02E05". */
    private val EPISODE_RANGE = rx("""(?<![a-z0-9])s(\d{1,2})[ ._-]?e(\d{1,3})$TO(?:s(\d{1,2})[ ._-]?)?e?(\d{1,3})(?![a-z0-9])""")
    /** "1x01-1x13", "1x01-13". */
    private val X_RANGE = rx("""(?<![a-z0-9])(\d{1,2})x(\d{2,3})$TO(?:(\d{1,2})x)?(\d{1,3})(?![a-z0-9])""")
    /** "E01-E08". */
    private val E_RANGE = rx("""(?<![a-z0-9])()e(\d{1,3})$TO()e(\d{1,3})(?![a-z0-9])""")
    /** "Episodes 1-8", "Episode 1-8". */
    private val WORD_RANGE = rx("""(?<![a-z])()episodes?[ ._]?(\d{1,3})$TO()(\d{1,3})(?![a-z0-9])""")
    /** Anime's "S2 - 01-12", "S2 - 01 ~ 12". */
    private val DASH_RANGE = rx("""(?<![a-z0-9])s(\d{1,2})[ ._]+-[ ._]+(\d{1,4})$TO()(\d{1,4})(?![a-z0-9])""")
    private val RANGES = listOf(EPISODE_RANGE, X_RANGE, E_RANGE, WORD_RANGE, DASH_RANGE)
    /**
     * "S01-S02", "S01-02", "S01 - S02", "Season 1-2", "Seasons 1 & 2"; never anime's "S3 - 01" (an episode: spaced, and
     * no "S" on its second number).
     */
    private val SEASON_RANGE = rx(
        """(?<![a-z0-9])s\d{1,2}(?:[-–~]s?\d{1,2}|[ ._]?[-–~][ ._]?s\d{1,2}|[ ._]to[ ._]s\d{1,2})(?![a-z0-9])""" +
            """|(?<![a-z])seasons?[ ._]?\d{1,2}[ ._]?(?:[-–~&]|to|and)[ ._]?\d{1,2}(?![a-z0-9])""",
    )
    /** "S01E06", "1x06", "Episode 6", "Ep 6", anime's "S3 - 01". */
    private val ONE_EPISODE = rx(
        """(?<![a-z0-9])s\d{1,2}[ ._-]?e\d{1,3}(?![0-9])|(?<![a-z0-9])\d{1,2}x\d{2,3}(?![a-z0-9])""" +
            """|(?<![a-z])(?:episode|ep)[ ._]?\d{1,3}(?![0-9])|(?<![a-z0-9])s\d{1,2}[ ._]+-[ ._]+\d{2,4}(?![0-9])""",
    )
    /** "S01" not followed by an episode, "Season 1": its number. */
    private val SEASON = rx("""(?<![a-z0-9])s(\d{1,2})(?![0-9])(?![ ._-]?e\d)|(?<![a-z])season[ ._]?(\d{1,2})(?![0-9])""")
    private val COMPLETE = rx("""(?<![a-z])complete(?![a-z])""")

    /**
     * What a torrent's names say, read together: [torrents] its own names (a pack, or one episode), [others] the rest (a
     * file's name, the description's lines), which say pack only: a pack's file names its own episode, and MediaFusion
     * names a file it indexes so, with the pack's size. A pack's first (one with a range's count first), else one
     * episode, else nothing said.
     */
    fun read(torrents: List<String?>, others: List<String?> = emptyList()): Read {
        var single = false
        var pack: Read? = null
        for ((i, n) in (torrents + others).withIndex()) {
            if (n.isNullOrBlank()) continue
            val r = one(n)
            when (r.kind) {
                Kind.PACK -> if (pack == null || (pack.episodes == null && r.episodes != null)) pack = r
                Kind.SINGLE -> if (i < torrents.size) single = true
                Kind.UNSAID -> Unit
            }
        }
        return pack ?: if (single) Read(Kind.SINGLE) else UNSAID
    }

    /** One name read alone. */
    fun one(name: String): Read {
        for (range in RANGES) {
            val m = range.find(name) ?: continue
            val (s1, a, s2, b) = m.destructured
            // Across seasons ("S01E01-S02E05", "1x01-2x05"): a pack of no one season's count.
            if (s1.isNotEmpty() && s2.isNotEmpty() && s1.toIntOrNull() != s2.toIntOrNull()) return Read(Kind.PACK)
            val from = a.toIntOrNull()
            val to = b.toIntOrNull()
            if (from != null && to != null && to > from && to - from < MAX_RANGE) return Read(Kind.PACK, to - from + 1, oneSeason = true)
        }
        if (SEASON_RANGE.containsMatchIn(name)) return Read(Kind.PACK)
        if (ONE_EPISODE.containsMatchIn(name)) return Read(Kind.SINGLE)
        val seasons = SEASON.findAll(name).mapNotNull { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull() }.toSet()
        // "S01", "Season 1": one season's pack; "S01.S02.S03": a run of seasons.
        if (seasons.isNotEmpty()) return Read(Kind.PACK, oneSeason = seasons.size == 1)
        if (COMPLETE.containsMatchIn(name)) return Read(Kind.PACK)
        return UNSAID
    }

    /** A range wider than this isn't one ("S01E06-720p" isn't 715 episodes). */
    private const val MAX_RANGE = 100
}
