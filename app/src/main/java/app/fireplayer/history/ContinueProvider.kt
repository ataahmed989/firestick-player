package app.fireplayer.history

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import app.fireplayer.data.Settings
import app.fireplayer.titles.TitleService

/**
 * Glass Home's Continue Watching row reads FirePlayer Home's here (content://app.fireplayer.continue/items): the same
 * cards in the same order, at most 15 ([VodContinue.rows]); the videos other apps opened here ([ContinueWatching]) are
 * kept but not listed. Asked with "?lists" (newer Glass Homes: their Movies and TV Shows tabs too), it answers in one
 * go Home's row, then the Movies page's, then the TV Shows page's ([VodContinue.lists]), each row with a "list" column
 * naming its list ("home", "movies", "shows"; a title can be in two). Each is its page's row as that page has it: with
 * the caught-up shows' new episodes where the page's Next Episodes row is off. An older FirePlayer answers it as it
 * answers items, without that column, which is how Glass Home tells. The notice on items covers them all (a page's Next
 * Episodes row switched on or off too). It removes a title from it (…/items/ID). For a show it lists every episode
 * (…/items/ID/episodes, see [ShowEpisodes]): that read blocks and may ask the provider and TMDB, so Glass Home makes it
 * off its main thread. An id of another app's video that Glass Home still has from before (earlier versions listed
 * them) is still answered: its episodes, its Remove, and its play ([ContinueWatching.ACTION_RESUME]). Only Glass Home
 * and FirePlayer itself are answered; anyone else gets nothing, without an error. The rows say what each title is and
 * how far along; never a login, nor where a title plays from.
 */
class ContinueProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val match = MATCHER.match(uri)
        val lists = match == ITEMS && uri.getQueryParameter(LISTS) != null
        val all = if (match == EPISODES) ShowEpisodes.COLUMNS else if (lists) LIST_COLUMNS else COLUMNS
        val columns = projection?.filter { it in all }?.toTypedArray()?.takeIf { it.isNotEmpty() } ?: all
        val cursor = MatrixCursor(columns)
        val context = context ?: return cursor
        if (!allowed()) return cursor
        if (match == EPISODES) {
            val episodes = try {
                val id = uri.pathSegments[1]
                if (LibraryContinue.isLibraryId(id)) VodContinue.episodes(context, id) else ContinueWatching.episodes(context, id)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't list the show's episodes: ${e.javaClass.simpleName}")
                emptyList()
            }
            for (episode in episodes) {
                val values = episode.values()
                cursor.addRow(columns.map { values[it] })
            }
            return cursor
        }
        if (match != ITEMS) return cursor
        // FirePlayer Home's Continue Watching row, card for card; with ?lists, the Movies and TV Shows pages' after it.
        val answer = if (lists) VodContinue.lists(context) else listOf(VodContinue.HOME to VodContinue.rows(context))
        for ((list, rows) in answer) for (r in rows) {
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
            cursor.addRow(columns.map { values[it] })
        }
        cursor.setNotificationUri(context.contentResolver, ContinueWatching.ITEMS)
        return cursor
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val context = context ?: return 0
        if (!allowed() || MATCHER.match(uri) != ITEM) return 0
        val id = uri.lastPathSegment ?: return 0
        return try {
            val removed = if (LibraryContinue.isLibraryId(id)) VodContinue.remove(context, id) else ContinueWatching.remove(context, id)
            if (removed) 1 else 0
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't remove from the watch history: ${e.javaClass.simpleName}")
            0
        }
    }

    override fun getType(uri: Uri): String? = when (MATCHER.match(uri)) {
        ITEMS -> "vnd.android.cursor.dir/vnd.${ContinueWatching.AUTHORITY}.item"
        ITEM -> "vnd.android.cursor.item/vnd.${ContinueWatching.AUTHORITY}.item"
        EPISODES -> "vnd.android.cursor.dir/vnd.${ContinueWatching.AUTHORITY}.episode"
        else -> null
    }

    // Only the player adds to it.
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    /**
     * The title server's starter, in its own process, asking once after an update whether TiviMate titles are on
     * ([app.fireplayer.titles.TitleService.SYNC]): the app starts or stops it from its own settings. FirePlayer only.
     */
    override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle? {
        val own = context ?: return null
        if (method == TitleService.SYNC && runCatching { callingPackage }.getOrNull() == own.packageName) {
            Settings.syncTitles(own)
        }
        return null
    }

    /** Glass Home, or FirePlayer itself. */
    private fun allowed(): Boolean {
        val caller = runCatching { callingPackage }.getOrNull() ?: return false
        return caller == GLASS_HOME || caller == context?.packageName
    }

    private companion object {
        const val TAG = "FirePlayerContinue"
        const val GLASS_HOME = "app.glasshome.launcher"
        /** Glass Home asking for its three lists at once (items?lists=…, any value). */
        const val LISTS = "lists"
        const val ITEMS = 1
        const val ITEM = 2
        const val EPISODES = 3
        val COLUMNS = arrayOf(
            "id", "kind", "title", "year", "season", "episode", "episode_title", "tmdb_id", "position_ms", "duration_ms",
            "last_watched", "source", "has_episodes",
        )
        val LIST_COLUMNS = COLUMNS + "list"
        val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(ContinueWatching.AUTHORITY, "items", ITEMS)
            addURI(ContinueWatching.AUTHORITY, "items/*", ITEM)
            addURI(ContinueWatching.AUTHORITY, "items/*/episodes", EPISODES)
        }
    }
}
