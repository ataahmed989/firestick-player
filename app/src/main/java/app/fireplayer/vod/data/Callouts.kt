package app.fireplayer.vod.data

import app.fireplayer.vod.Callout
import app.fireplayer.vod.Episode
import app.fireplayer.vod.EpisodeRef
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

/**
 * Which episodes "Mark watched up to here" and "Mark unwatched up to here" take (see
 * [app.fireplayer.vod.VodRepository.markWatchedUpTo]), and which marks an episode's hold window offers. Pure.
 */
object EpisodeMarks {

    /** A show's first episode when its episodes aren't known here (Mark unwatched on the whole show). */
    val FIRST = EpisodeRef(1, 1)

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

    /** A show's first episode (Mark unwatched on the whole show starts it there): its first regular one; null: none known. */
    fun first(episodes: List<Episode>?): Episode? {
        val list = episodes.orEmpty()
        val order = compareBy<Episode>({ it.season }, { it.episode })
        return list.filter { !it.isSpecial }.minWithOrNull(order) ?: list.minWithOrNull(order)
    }

    /** The marks an episode's hold window offers ([offer]); each is there only when it would change something. */
    data class Offer(val watched: Boolean, val unwatched: Boolean, val watchedUpTo: Boolean, val unwatchedUpTo: Boolean)

    /**
     * What [ep]'s hold window offers, of [episodes] at [now]: Mark watched when it isn't; Mark unwatched when it is, or
     * has been played some way ([played]); "up to here" only when an episode before it ([upTo]) would change too, so
     * never on a show's first episode (the one-episode option does the same).
     */
    fun offer(
        episodes: List<Episode>,
        ep: Episode,
        now: Long,
        watched: (Episode) -> Boolean,
        played: (Episode) -> Boolean,
    ): Offer {
        val isWatched = watched(ep)
        val before = upTo(episodes, ep, now).filter { it.id != ep.id }
        return Offer(
            watched = !isWatched,
            unwatched = isWatched || played(ep),
            watchedUpTo = before.any { !watched(it) },
            unwatchedUpTo = before.any { watched(it) || played(it) },
        )
    }
}
