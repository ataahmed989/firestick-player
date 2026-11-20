package app.fireplayer.vod

/**
 * Which episode of a show comes next, the one rule behind Continue Watching, Next Episodes, the title page's Play
 * ([playTarget]), the banner's Watch now, Glass Home's Continue rows and the player's next episode. Pure, unit-tested.
 *
 * Only regular episodes (season 1 and up) count, in season then number order, gaps in the numbers passed over; Specials
 * (season 0) are never next. Only an episode aired by now is next: a season announced with nothing aired yet, or an
 * episode with no date, doesn't count.
 */
object UpNext {

    /** The regular episodes in order, one per season and number. */
    fun regular(episodes: List<Episode>): List<Episode> = episodes
        .filter { it.season > 0 }
        .distinctBy { it.season to it.episode }
        .sortedWith(compareBy<Episode>({ it.season }, { it.episode }))

    /** The aired regular episodes after [ref], in order. */
    fun later(episodes: List<Episode>, ref: EpisodeRef, now: Long): List<Episode> =
        regular(episodes).filter { it.aired(now) && isAfter(it, ref) }

    /**
     * The episode after [from] (finished): the first aired regular one after it not [watched], so the rest of a season
     * watched out of order is passed over. Null when there's none: nothing aired after it ([from] was the show's last
     * aired episode), or every one that did is watched (the whole show marked watched, say).
     */
    fun after(episodes: List<Episode>, from: EpisodeRef, now: Long, watched: (Episode) -> Boolean): Episode? =
        later(episodes, from, now).firstOrNull { !watched(it) }

    /** [e] comes after [ref]: a later season, or the same season and a higher number. */
    fun isAfter(e: Episode, ref: EpisodeRef): Boolean = e.season > ref.season || (e.season == ref.season && e.episode > ref.episode)

    /** Where a show's Continue Watching record ([from], the episode it was left in) goes on ([of]). */
    sealed class Up {
        /** Not over: its own episode, from where it stopped. */
        data object Own : Up()

        /** Over, and the show's episodes aren't known here yet (or none are listed): kept as it is until they are. */
        data object Unknown : Up()

        /**
         * Over: [episode] is next. [fresh] when it aired after the record was over ([overAt]): a new episode of a show
         * the viewer had caught up on (Next Episodes'); otherwise Continue Watching goes on with it.
         */
        data class Next(val episode: Episode, val fresh: Boolean) : Up()

        /** Over, and nothing after it to watch ([after]): the show is done (until a new episode airs). */
        data object Done : Up()
    }

    /**
     * A show's record, left in [from], [over] (finished, or marked watched since) at [overAt], against its [episodes]
     * (null: not known here yet). [now] epoch ms; [watched] whether an episode counts as watched.
     */
    fun of(
        from: EpisodeRef,
        over: Boolean,
        overAt: Long,
        episodes: List<Episode>?,
        now: Long,
        watched: (Episode) -> Boolean,
    ): Up {
        if (!over) return Up.Own
        if (episodes.isNullOrEmpty()) return Up.Unknown
        val next = after(episodes, from, now, watched) ?: return Up.Done
        return Up.Next(next, fresh = (next.released ?: 0L) > overAt)
    }
}
