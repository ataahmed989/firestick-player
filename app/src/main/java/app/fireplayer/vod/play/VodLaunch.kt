package app.fireplayer.vod.play

import app.fireplayer.vod.Card
import app.fireplayer.vod.Episode
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Kind
import app.fireplayer.vod.PlayOrigin
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.PlaybackLibrary
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.SourceList
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.StartAt
import app.fireplayer.vod.Title
import app.fireplayer.vod.VodRepository

/**
 * The plays the pages start (design-brief.md §6.4), as [PlayRequest]s for FirePlayer's player: the title page's Play,
 * Resume, Play from beginning and an episode's OK (after the wait), a Sources row, and OK on a Continue card, which goes
 * straight to playback on the same file (its add-on asked first, no title page, no question). Pure; the shell then makes
 * room (film mode) and hands the request over ([app.fireplayer.vod.PlayHandoff]).
 */
object VodLaunch {

    /** The title page's play of [target] once the wait is over ([list]), or a Sources row ([chosen]). */
    fun fromTitlePage(
        target: SourceTarget,
        start: StartAt,
        list: SourceList?,
        library: PlaybackLibrary?,
        chosen: PlaybackSource? = null,
        next: EpisodeRef? = null,
    ): PlayRequest = PlayRequest(
        target = target,
        start = start,
        origin = if (chosen != null) PlayOrigin.SOURCES_PANEL else PlayOrigin.TITLE_PAGE,
        list = list,
        chosen = chosen,
        // Resume keeps the exact timeline: the release used last, when it's offered again.
        preferred = if (start is StartAt.At) library?.lastRelease(target.title, target.episode) else null,
        next = next,
    )

    /**
     * OK on a Continue Watching card: the film, or the series' episode on the card, from where it stopped, the release
     * it played from asked from its add-on first (from its beginning: the ranking's pick, as any fresh start). A card
     * naming no episode of a series (the banner's Watch now on any poster) plays the one its show goes on with
     * ([VodRepository.upNext]: the next after one finished, across seasons). Null when there's none.
     */
    fun fromContinueCard(card: Card, repo: VodRepository, library: PlaybackLibrary?): PlayRequest? {
        val title = card.title
        val ep: EpisodeRef? = if (title.kind == Kind.SERIES) card.episode ?: repo.upNext(title) ?: return null else null
        val start = if (ep == null) {
            PlayRules.startAt(repo.lastPlayed(title))
        } else {
            val last = repo.lastPlayed(title)
            val le = last?.episode
            val position = if (last != null && le != null && le.season == ep.season && le.episode == ep.episode) {
                last.position
            } else {
                repo.positionOf(Episode(Episode.idOf(title.id, ep.season, ep.episode), ep.season, ep.episode, ep.name ?: ""))
            }
            PlayRules.startAt(position, fromBeginning = false)
        }
        val target = SourceTarget(title, ep)
        return PlayRequest(
            target = target,
            start = start,
            origin = PlayOrigin.CONTINUE_CARD,
            preferred = if (start is StartAt.At) library?.lastRelease(title, ep) else null,
        )
    }

    /** Film mode applies to plays started from Home, Movies or TV Shows (every page play does). */
    fun withFilmMode(request: PlayRequest): PlayRequest = request.copy(filmMode = true)

    /** [request] with when OK was pressed for it ([now], elapsedRealtime ms), for the start's timing in the log. */
    fun pressedAt(request: PlayRequest, now: Long): PlayRequest = request.copy(pressedAt = now)

    /** A title's target: the film, or [episode] of the show. */
    fun target(title: Title, episode: EpisodeRef?): SourceTarget = SourceTarget(title, if (title.kind == Kind.SERIES) episode else null)
}
