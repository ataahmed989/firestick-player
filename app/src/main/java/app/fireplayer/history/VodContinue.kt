package app.fireplayer.history

import android.content.Context
import android.util.Log
import app.fireplayer.vod.Card
import app.fireplayer.vod.Episode
import app.fireplayer.vod.Kind
import app.fireplayer.vod.Page
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.Sources
import app.fireplayer.vod.TitleDetails
import app.fireplayer.vod.data.DefaultVodRepository
import app.fireplayer.vod.data.Progress
import app.fireplayer.vod.data.VodLibrary
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
    /** The provider's items asked with this query parameter (any value): Glass Home's three lists ([items]). */
    const val LISTS = "lists"
    /** The columns of the provider's items rows, as the Glass Homes on the TVs (1.0.43, 1.0.45) read them. */
    val COLUMNS = arrayOf(
        "id", "kind", "title", "year", "season", "episode", "episode_title", "tmdb_id", "position_ms", "duration_ms",
        "last_watched", "source", "has_episodes",
    )
    /** items?lists' columns: [COLUMNS], then "list", naming the row's list ([HOME], [MOVIES], [SHOWS]). */
    val LIST_COLUMNS = COLUMNS + "list"

    /** The columns of the provider's items answer: [LIST_COLUMNS] asked with ?lists, else [COLUMNS]. */
    fun columns(lists: Boolean): Array<String> = if (lists) LIST_COLUMNS else COLUMNS

    /** The provider's items answer ([items]), read from the library; empty when it can't be read. */
    fun items(context: Context, lists: Boolean, columns: Array<String>): List<Array<Any?>> = try {
        items(VodRepositories.repository(context), lists, columns)
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read Films & Shows' Continue Watching: ${e.javaClass.simpleName}")
        emptyList()
    }

    /**
     * The provider's answer to items: Home's row ([rows]); asked with ?lists ([lists] true), Glass Home's three lists
     * ([lists]), Home's first, each row naming its list. Each row holds the values of [columns] (of [columns] ([lists])),
     * in that order.
     */
    internal fun items(repo: DefaultVodRepository, lists: Boolean, columns: Array<String>): List<Array<Any?>> {
        val answer = if (lists) lists(repo) else listOf(HOME to rows(repo))
        return answer.flatMap { (list, rows) ->
            rows.map { r ->
                val values = mapOf(
                    "list" to list,
                    "id" to r.id,
                    "kind" to r.kind.code,
                    "title" to r.title,
                    "year" to r.year,
                    "season" to r.season,
                    "episode" to r.episode,
                    "episode_title" to r.episodeTitle,
                    "tmdb_id" to r.tmdbId,
                    "position_ms" to r.positionMs,
                    "duration_ms" to r.durationMs,
                    "last_watched" to r.lastWatched,
                    "source" to r.source,
                    "has_episodes" to if (r.hasEpisodes) 1 else 0,
                )
                Array(columns.size) { values[columns[it]] }
            }
        }
    }

    /**
     * Glass Home's row: FirePlayer Home's Continue Watching row as it is now, and nothing else (not the videos other
     * apps opened here: their history stays, unlisted). Made as Home's row is: the library's Continue Watching for films
     * and series (Source.ContinueWatching(null), [app.fireplayer.vod.data.VodLibrary.continueEntries]: taken out,
     * marked watched, a show gone on to its next episode, a caught-up show's new episode while Home's Next Episodes row
     * is off), the cards that row shows of it ([RowRules.shown]: the first 15). The same titles in the same order, at
     * the same episode and place; read from the library each time, never a copy kept.
     */
    internal fun rows(repo: DefaultVodRepository): List<WatchHistory.Row> =
        shown(repo.library.continueEntries(null, withNew = repo.newInContinue(Page.HOME)))

    /**
     * Glass Home's three lists ([ContinueProvider]'s items?lists), by name: Home's row ([rows]), then the Movies page's
     * and the TV Shows page's Continue Watching rows (movies.continue, shows.continue), each made as its row is: the
     * library's Continue Watching of that kind (Source.ContinueWatching(Kind.MOVIE), (Kind.SERIES)), with the
     * caught-up shows' new episodes where that page's Next Episodes row is off ([DefaultVodRepository.newInContinue]),
     * the cards the row shows of it ([RowRules.shown]). Read from the library each time.
     */
    internal fun lists(repo: DefaultVodRepository): List<Pair<String, List<WatchHistory.Row>>> =
        lists(repo.library, homeNew = repo.newInContinue(Page.HOME), showsNew = repo.newInContinue(Page.SHOWS))

    /**
     * [lists] from [library]: Home's with the caught-up shows' new episodes when [homeNew], the TV Shows page's when
     * [showsNew]. All three from one reading of the library's Continue Watching ([VodLibrary.continueLists]), so each
     * show's episodes are read once, at one moment, whether the two pages' Next Episodes rows agree or not: a kind's
     * entries in it are continueEntries(kind)'s, in the same order (each is worked out from its own record and its own
     * show's episodes alone). Films have no new episodes: the Movies list is Home's films either way.
     */
    internal fun lists(library: VodLibrary, homeNew: Boolean, showsNew: Boolean): List<Pair<String, List<WatchHistory.Row>>> {
        val withNew = listOf(homeNew, showsNew).distinct()
        val read = library.continueLists(null, withNew)
        val home = read[withNew.indexOf(homeNew)]
        val shows = read[withNew.indexOf(showsNew)]
        return listOf(
            HOME to shown(home),
            MOVIES to shown(home.filter { it.title.kind == Kind.MOVIE }),
            SHOWS to shown(shows.filter { it.title.kind == Kind.SERIES }),
        )
    }

    /** The cards a Continue Watching row shows of [entries] ([RowRules.shown]), as Glass Home rows. */
    private fun shown(entries: List<Progress>): List<WatchHistory.Row> =
        RowRules.shown(entries).mapNotNull { LibraryContinue.row(it) }

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
