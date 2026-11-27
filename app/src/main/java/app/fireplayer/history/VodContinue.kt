package app.fireplayer.history

import android.content.Context
import android.util.Log
import app.fireplayer.vod.Card
import app.fireplayer.vod.Episode
import app.fireplayer.vod.Kind
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.Sources
import app.fireplayer.vod.TitleDetails
import app.fireplayer.vod.data.DefaultVodRepository
import app.fireplayer.vod.data.VodRepositories
import app.fireplayer.vod.play.VodLaunch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Films & Shows' Continue Watching for Glass Home ([ContinueProvider]): its rows ([LibraryContinue]), taking one off,
 * a show's episodes, and the play a row starts (Glass Home's RESUME and PLAY_EPISODE), which is a Continue card's on
 * Home: the same file, its add-on asked first, from where it stopped, no title page and no question. Logs say only the
 * kind of a failure, never an add-on, a release or a link.
 */
object VodContinue {
    private const val TAG = "FirePlayerContinue"
    /** The longest Glass Home's Episodes window waits for a show's details (stored ones come at once). */
    private const val DETAILS_WAIT_S = 8L

    /**
     * Glass Home's whole row: the library's rows with [others] (the videos other apps opened here), the latest played
     * first, a library title's copies among [others] left out ([LibraryContinue.merge]); just [others] when the library
     * can't be read.
     */
    fun rows(context: Context, others: List<WatchHistory.Row>): List<WatchHistory.Row> = try {
        rows(VodRepositories.repository(context), others)
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read Films & Shows' Continue Watching: ${e.javaClass.simpleName}")
        others
    }

    /** [repo]'s rows ([rows]) with [others]: what Glass Home's row is made from ([ContinueProvider]). */
    internal fun rows(repo: DefaultVodRepository, others: List<WatchHistory.Row>): List<WatchHistory.Row> =
        LibraryContinue.merge(rows(repo), others, if (others.isEmpty()) emptyList() else repo.library.played())

    /**
     * [repo]'s Continue Watching as Glass Home's rows: read from the library each time (never a copy kept), so they are
     * Home's Continue Watching row as it is now, the same titles in the same order, at the same episode and place.
     */
    internal fun rows(repo: DefaultVodRepository): List<WatchHistory.Row> =
        repo.library.continueEntries().mapNotNull { LibraryContinue.row(it) }

    /** Takes a library row's title out of Continue Watching (its resume point stays); false for no such row. */
    fun remove(context: Context, id: String): Boolean = remove(VodRepositories.repository(context), id)

    /**
     * Glass Home's Remove on library row [id]: the title leaves Continue Watching here as Remove on its card does (a
     * show's row takes the show out, Next Episodes too), until it's played again; FirePlayer's pages and Glass Home are
     * told (the library's listeners). False for no such row.
     */
    internal fun remove(repo: DefaultVodRepository, id: String): Boolean {
        val (_, titleId) = LibraryContinue.parse(id) ?: return false
        if (!repo.inContinueWatching(titleId)) return false
        repo.removeFromContinueWatching(titleId)
        return true
    }

    /**
     * The play of library row [id]: where it stopped, or with [episodeId] another episode of its show (from where that
     * one stopped, else its start). Null when there's no such row, or the episode can't be named. Main thread.
     */
    fun request(context: Context, id: String?, episodeId: String? = null): PlayRequest? {
        val (kind, titleId) = LibraryContinue.parse(id) ?: return null
        val repo = VodRepositories.repository(context)
        val entry = repo.library.continueEntry(kind, titleId) ?: return null
        val episode = if (episodeId == null) entry.episode else {
            if (kind != Kind.SERIES) return null
            LibraryContinue.parseEpisodeId(episodeId) ?: return null
        }
        val library = try {
            Sources.get(context).library
        } catch (e: RuntimeException) {
            null
        }
        // As OK on the Continue card; no page to bring back, so no film mode (the player is alone in its task).
        return VodLaunch.fromContinueCard(Card(title = entry.title, episode = episode), repo, library)
    }

    /**
     * Every aired episode of library row [id]'s show, from its details (stored ones at once, else Cinemeta's, waited
     * for up to [DETAILS_WAIT_S]); empty for no such row or none to be had. Blocks: never on the main thread.
     */
    fun episodes(context: Context, id: String): List<ShowEpisodes.Row> {
        return try {
            val (kind, titleId) = LibraryContinue.parse(id) ?: return emptyList()
            if (kind != Kind.SERIES) return emptyList()
            val repo = VodRepositories.repository(context)
            val entry = repo.library.continueEntry(kind, titleId) ?: return emptyList()
            val got = CountDownLatch(1)
            var details: TitleDetails? = null
            val cancel = repo.details(entry.title) { d, _ ->
                if (d.episodes.isNotEmpty()) {
                    details = d
                    got.countDown()
                }
            }
            try {
                got.await(DETAILS_WAIT_S, TimeUnit.SECONDS)
            } finally {
                cancel.cancel()
            }
            val episodes = details?.episodes ?: return emptyList()
            LibraryContinue.episodeRows(episodes, entry.episode, System.currentTimeMillis()) { e: Episode ->
                repo.positionOf(e)?.positionMs ?: 0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't list the show's episodes: ${e.javaClass.simpleName}")
            emptyList()
        }
    }
}
