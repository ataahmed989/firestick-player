package app.fireplayer.history

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log

/**
 * Glass Home's Continue Watching row reads what was watched here (content://app.fireplayer.continue/items), newest
 * first, and removes a title from it (…/items/ID). For a show it lists every episode (…/items/ID/episodes, see
 * [ShowEpisodes]): that read blocks and may ask the provider and TMDB, so Glass Home makes it off its main thread. Only
 * Glass Home and FirePlayer itself are answered; anyone else gets nothing, without an error. The rows say what each
 * title is and how far along, and the provider's address as FirePlayer's Source row shows it ("host:port"); never
 * the login, nor where a title plays from.
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
        val all = if (match == EPISODES) ShowEpisodes.COLUMNS else COLUMNS
        val columns = projection?.filter { it in all }?.toTypedArray()?.takeIf { it.isNotEmpty() } ?: all
        val cursor = MatrixCursor(columns)
        val context = context ?: return cursor
        if (!allowed()) return cursor
        if (match == EPISODES) {
            val episodes = try {
                ContinueWatching.episodes(context, uri.pathSegments[1])
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
        val rows = try {
            ContinueWatching.rows(context)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't read the watch history: ${e.javaClass.simpleName}")
            emptyList()
        }
        for (r in rows) {
            val values = mapOf(
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
            if (ContinueWatching.remove(context, id)) 1 else 0
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

    /** Glass Home, or FirePlayer itself. */
    private fun allowed(): Boolean {
        val caller = runCatching { callingPackage }.getOrNull() ?: return false
        return caller == GLASS_HOME || caller == context?.packageName
    }

    private companion object {
        const val TAG = "FirePlayerContinue"
        const val GLASS_HOME = "app.glasshome.launcher"
        const val ITEMS = 1
        const val ITEM = 2
        const val EPISODES = 3
        val COLUMNS = arrayOf(
            "id", "kind", "title", "year", "season", "episode", "episode_title", "tmdb_id", "position_ms", "duration_ms",
            "last_watched", "source", "has_episodes",
        )
        val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(ContinueWatching.AUTHORITY, "items", ITEMS)
            addURI(ContinueWatching.AUTHORITY, "items/*", ITEM)
            addURI(ContinueWatching.AUTHORITY, "items/*/episodes", EPISODES)
        }
    }
}
