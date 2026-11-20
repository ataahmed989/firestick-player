package app.fireplayer.vod.data

import app.fireplayer.vod.Callout
import app.fireplayer.vod.Episode
import app.fireplayer.vod.Kind
import app.fireplayer.vod.PlayTarget
import app.fireplayer.vod.TitleDetails

/**
 * The one chip beside the facts line ([Callout]), at most one, in the brief's order: this week's Top 10 place; then,
 * from the library, "✓ Watched" (a film) or "✓ Watched all" (every aired regular episode of a show), "New episodes",
 * "Up to date · S5 E1 airs Dec 3"; then an Oscar line. Pure.
 */
object Callouts {

    /**
     * [watched]: the film's watched mark ([app.fireplayer.vod.VodRepository.isWatched]); [episodeWatched]: an
     * episode's ([app.fireplayer.vod.VodRepository.isEpisodeWatched]); [target]: the show's
     * [app.fireplayer.vod.playTarget]; [now] epoch ms.
     */
    fun choose(
        details: TitleDetails,
        watched: Boolean,
        episodeWatched: (Episode) -> Boolean,
        target: PlayTarget?,
        now: Long,
    ): Callout? {
        details.top10Rank?.takeIf { it in 1..10 }?.let { return Callout.Top10(it) }
        if (details.title.kind == Kind.MOVIE) {
            if (watched) return Callout.Watched
        } else {
            val aired = details.episodes.filter { it.season > 0 && it.aired(now) }
            if (aired.isNotEmpty() && aired.all(episodeWatched)) return Callout.WatchedAll
            if (target?.newEpisodes == true) return Callout.NewEpisodes
            target?.upcoming?.let { return Callout.UpToDate(it) }
        }
        details.oscars?.let { return Callout.Oscars(it) }
        return null
    }
}

/** Which episodes "Mark watched up to here" marks (pure; see [app.fireplayer.vod.VodRepository.markWatchedUpTo]). */
object EpisodeMarks {

    /**
     * [upTo] and the aired episodes before it: for a regular episode every aired regular one up to it, across seasons,
     * never Specials; for a special the aired specials up to it. Nothing when [upTo] hasn't aired by [now].
     */
    fun upTo(episodes: List<Episode>, upTo: Episode, now: Long): List<Episode> {
        if (!upTo.aired(now)) return emptyList()
        return episodes.filter {
            it.isSpecial == upTo.isSpecial && it.aired(now) &&
                (it.season < upTo.season || (it.season == upTo.season && it.episode <= upTo.episode))
        }
    }
}
