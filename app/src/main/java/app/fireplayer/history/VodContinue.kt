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
import app.fireplayer.vod.ui.RowRules
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Films & Shows' Continue Watching for Glass Home ([ContinueProvider]): its rows ([LibraryContinue]) for its Home row
 * ([rows]) and its Movies and TV Shows tabs ([lists]), taking one off, a show's episodes, and the play a row starts
 * (Glass Home's RESUME and PLAY_EPISODE), which is a Continue card's on Home: the same file, its add-on asked first,
 * from where it stopped, no title page and no question. Logs say only the kind of a failure, never an add-on, a
 * release or a link.
 */
object VodContinue {
    private const val TAG = "FirePlayerContinue"
    /** The longest Glass Home's Episodes window waits for a show's details (stored ones come at once). */
    private const val DETAILS_WAIT_S = 8L

    /** The names of Glass Home's lists ([lists]), as the provider's "list" column has them. */
    const val HOME = "home"
    const val MOVIES = "movies"
    const val SHOWS = "shows"

    /** Glass Home's Home row ([rows]); empty when the library can't be read. */
    fun rows(context: Context): List<WatchHistory.Row> = try {
        rows(VodRepositories.repository(context))
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read Films & Shows' Continue Watching: ${e.javaClass.simpleName}")
        emptyList()
    }

    /**
     * Glass Home's row: FirePlayer Home's Continue Watching row as it is now, and nothing else (not the videos other
     * apps opened here: their history stays, unlisted). Made as Home's row is: the library's Continue Watching for films
     * and series (Source.ContinueWatching(null), [app.fireplayer.vod.data.VodLibrary.continueEntries]: taken out, marked
     * watched, a show gone on to its next episode), the cards that row shows of it ([RowRules.shown]: the first 15). The
     * same titles in the same order, at the same episode and place; read from the library each time, never a copy kept.
     */
    internal fun rows(repo: DefaultVodRepository): List<WatchHistory.Row> = rows(repo, null)

    /** Glass Home's lists ([lists]); empty when the library can't be read. */
    fun lists(context: Context): List<Pair<String, List<WatchHistory.Row>>> = try {
        lists(VodRepositories.repository(context))
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read Films & Shows' Continue Watching: ${e.javaClass.simpleName}")
        emptyList()
    }

    /**
     * Glass Home's three lists ([ContinueProvider]'s items?lists), by name: Home's row ([rows]), then the Movies page's
     * and the TV Shows page's Continue Watching rows (movies.continue, shows.continue), each made as its row is: the
     * library's Continue Watching of that kind (Source.ContinueWatching(Kind.MOVIE), (Kind.SERIES)), the cards the row
     * shows of it ([RowRules.shown]). Read from the library each time.
     */
    internal fun lists(repo: DefaultVodRepository): List<Pair<String, List<WatchHistory.Row>>> =
        listOf(HOME to rows(repo, null), MOVIES to rows(repo, Kind.MOVIE), SHOWS to rows(repo, Kind.SERIES))

    /** The Continue Watching row of [kind] (null: Home's, films and series) as its page shows it. */
    private fun rows(repo: DefaultVodRepository, kind: Kind?): List<WatchHistory.Row> =
        RowRules.shown(repo.library.continueEntries(kind)).mapNotNull { LibraryContinue.row(it) }

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
