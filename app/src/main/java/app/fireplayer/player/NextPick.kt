package app.fireplayer.player

/**
 * Which video in a show's list plays after the one playing. TiviMate numbers a season's episodes by their place in
 * its list, but some playlists carry an episode twice, so its "S1 E12" may be the same episode as its "S1 E11". The
 * provider's own numbers, which FirePlayer's title server notes for every episode TiviMate lists (see
 * [app.fireplayer.titles.TitleBook]), say what each one really is: the list plays on in its own order, as always, but
 * copies of the one playing, of one already watched and of one the list had before are passed over (S1 E6, its copy,
 * then S1 E7 plays S1 E7). Where the real numbers aren't known, nothing is passed over.
 */
object NextPick {

    /**
     * An episode as the provider numbers it: its show (the provider's id for it), season and episode, and the part
     * its name gives ("Part 2"; 0 when none), for a two-part episode the provider numbered the same twice.
     */
    data class Real(val series: Int, val season: Int, val episode: Int, val part: Int = 0) : Comparable<Real> {
        override fun compareTo(other: Real): Int = compareValuesBy(this, other, { it.season }, { it.episode }, { it.part })

        companion object {
            private val PART = Regex("""(?i)\b(?:part|pt\.?|teil|parte|partie)\s*[-.:]?\s*(\d{1,2}|i{1,3}|iv|v|one|two|three|four|five)\b""")
            private val PART_WORDS = listOf("one", "two", "three", "four", "five")
            private val PART_ROMAN = listOf("i", "ii", "iii", "iv", "v")

            /**
             * Null for an episode the provider gave no number (noted as 0): nothing can be said of where it goes.
             * [name] is the episode's name, for its part.
             */
            fun of(series: Int, season: Int, episode: Int, name: String? = null): Real? =
                if (episode > 0) Real(series, season, episode, partIn(name)) else null

            private fun partIn(name: String?): Int {
                val p = PART.findAll(name ?: return 0).lastOrNull()?.groupValues?.get(1)?.lowercase() ?: return 0
                return p.toIntOrNull() ?: (PART_WORDS.indexOf(p).takeIf { it >= 0 } ?: PART_ROMAN.indexOf(p)) + 1
            }
        }
    }

    /**
     * The index in [reals] (each video's real episode, null when not known) to play after [current], or null when
     * nothing should: the list ends there, or all that's left are copies of episodes already seen.
     *
     * The first video still to come in the list that isn't a copy: of the one playing, of a [watched] one, or of one
     * the list had before it. One not known may be anything, so it plays. Videos of another show count as not known,
     * and so does the whole list when all of the show's videos in it carry the same numbers (they then say nothing).
     */
    fun after(current: Int, reals: List<Real?>, watched: Collection<Real> = emptyList()): Int? {
        val following = current + 1 until reals.size
        val now = reals.getOrNull(current) ?: return following.firstOrNull()
        val show = reals.filter { it?.series == now.series }
        if (show.size > 1 && show.all { it == now }) return following.firstOrNull()
        fun known(i: Int) = reals[i]?.takeIf { it.series == now.series }
        val seen = (0..current).mapNotNullTo(HashSet()) { known(it) }
        return following.firstOrNull { known(it).let { r -> r == null || (r !in seen && r !in watched) } }
    }
}
