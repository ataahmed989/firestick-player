package app.fireplayer.player

/**
 * Which video in a show's list plays after the one playing. TiviMate numbers a season's episodes by their place in
 * its list, but some playlists carry an episode twice, so its "S1 E12" may be the same episode as its "S1 E11". The
 * provider's own numbers, which FirePlayer's title server notes for every episode TiviMate lists (see
 * [app.fireplayer.titles.TitleBook]), say what each one really is: the next to play is the real next episode
 * (S1 E6, then S1 E7; the last of a season, then the next season's first), and copies of the one playing or of one
 * already watched are passed over. Where the real numbers aren't known, the list plays in its own order, as always.
 */
object NextPick {

    /** An episode as the provider numbers it: its show (the provider's id for it), season and episode. */
    data class Real(val series: Int, val season: Int, val episode: Int) : Comparable<Real> {
        override fun compareTo(other: Real): Int = compareValuesBy(this, other, { it.season }, { it.episode })

        companion object {
            /** Null for an episode the provider gave no number (noted as 0): nothing can be said of where it goes. */
            fun of(series: Int, season: Int, episode: Int): Real? = if (episode > 0) Real(series, season, episode) else null
        }
    }

    /**
     * The index in [reals] (each video's real episode, null when not known) to play after [current], or null when
     * nothing should: the list ends there, or all that's left are copies of episodes already seen.
     *
     * When the one playing is known, its real next episode is the lowest one after it not yet [watched], among those
     * still to come in the list; the first video still to come that is that one (or isn't known, so may be) plays
     * next. When none after it is known to be later, the list goes on in its order, past copies: of the one playing,
     * of [watched] ones, and of ones the list had before it. Videos of another show count as not known.
     */
    fun after(current: Int, reals: List<Real?>, watched: Collection<Real> = emptyList()): Int? {
        val following = current + 1 until reals.size
        val now = reals.getOrNull(current) ?: return following.firstOrNull()
        fun known(i: Int) = reals[i]?.takeIf { it.series == now.series }
        val next = following.mapNotNull { known(it) }.filter { it > now && it !in watched }.minOrNull()
        if (next != null) return following.firstOrNull { known(it).let { r -> r == null || r == next } }
        val before = (0 until current).mapNotNullTo(HashSet()) { known(it) }
        return following.firstOrNull { known(it).let { r -> r == null || (r != now && r !in watched && r !in before) } }
    }
}
