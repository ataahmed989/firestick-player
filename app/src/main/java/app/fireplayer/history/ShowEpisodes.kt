package app.fireplayer.history

import app.fireplayer.player.ShowGuide
import java.util.Locale

/**
 * Every episode of a show in Continue Watching, for Glass Home's Episodes window (content://app.fireplayer.continue/
 * items/ID/episodes, see [ContinueProvider]): the same ones FirePlayer's own Episodes sheet lists, from the show's guide
 * ([ShowGuide]), by season and then episode. Each says what it is and where it was left, never where it plays from:
 * Glass Home hands [Row.episodeId] back to play one ([ContinueWatching.ACTION_PLAY_EPISODE]). Plain values, so the
 * rows can be tested on their own.
 */
object ShowEpisodes {
    /** The columns, in order: what Glass Home reads. */
    val COLUMNS = arrayOf(
        "episode_id", "season", "episode", "name", "overview", "image", "runtime_min", "air_date", "position_ms", "current",
    )

    class Row(
        /** The provider's id for it. */
        val episodeId: String,
        /** As FirePlayer shows it: TMDB's numbers once paired; 0 for Specials. */
        val season: Int,
        val episode: Int,
        /** "" when there's none. */
        val name: String,
        val overview: String,
        /** A picture from it (TMDB's, else the provider's); null when neither has one. */
        val image: String?,
        /** 0 when unknown. */
        val runtimeMin: Int,
        /** Usually "YYYY-MM-DD"; "" when unknown. */
        val airDate: String,
        /** Where it was left in FirePlayer; 0 for none. */
        val positionMs: Long,
        /** The episode the row is at. */
        val current: Boolean,
    ) {
        fun values(): Map<String, Any?> = mapOf(
            "episode_id" to episodeId,
            "season" to season,
            "episode" to episode,
            "name" to name,
            "overview" to overview,
            "image" to image,
            "runtime_min" to runtimeMin,
            "air_date" to airDate,
            "position_ms" to positionMs,
            "current" to if (current) 1 else 0,
        )
    }

    /**
     * The guide's [episodes] by season and then episode, two copies of one in the provider's order. The one with
     * [currentId] (only the first, should it be listed twice) is the row's own, at [currentPositionMs]; the others are
     * where FirePlayer left them ([saved]).
     */
    fun rows(
        episodes: List<ShowGuide.Ep>,
        currentId: String?,
        currentPositionMs: Long,
        saved: (ShowGuide.Ep) -> Long,
    ): List<Row> {
        var currentSeen = false
        return episodes.sortedWith(compareBy<ShowGuide.Ep>({ it.season }, { it.number })).map { ep ->
            val current = !currentSeen && currentId != null && ep.id == currentId
            if (current) currentSeen = true
            Row(
                episodeId = ep.id,
                season = ep.season,
                episode = ep.number,
                name = ep.name,
                overview = ep.overview,
                image = ep.image,
                runtimeMin = ep.runtimeMin.coerceAtLeast(0),
                airDate = ep.airDate,
                positionMs = (if (current) currentPositionMs else saved(ep)).coerceAtLeast(0L),
                current = current,
            )
        }
    }

    /** The name an episode chosen from the show's guide plays under: "Show S01E02 Name", as in FirePlayer's Episodes. */
    fun title(show: String, ep: ShowGuide.Ep): String =
        String.format(Locale.ROOT, "%s S%02dE%02d %s", show, ep.season, ep.number, ep.name).trim()

    /** The provider's id for the show among a row's keys ("s|SERVER|ID"), for the link's [server]; null when it has none. */
    fun showIdIn(keys: Set<String>, server: String): String? {
        val prefix = "${WatchHistory.SHOW_KEY}$server|"
        return keys.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.takeIf { it.isNotEmpty() }
    }
}
