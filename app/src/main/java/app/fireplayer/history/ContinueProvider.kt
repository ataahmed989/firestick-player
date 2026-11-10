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
 * first, and removes a title from it (…/items/ID). Only Glass Home and FirePlayer itself are answered; anyone else
 * gets nothing, without an error. The rows say what each title is and how far along; never where it plays from.
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
        val columns = projection?.filter { it in COLUMNS }?.toTypedArray()?.takeIf { it.isNotEmpty() } ?: COLUMNS
        val cursor = MatrixCursor(columns)
        val context = context ?: return cursor
        if (!allowed() || MATCHER.match(uri) != ITEMS) return cursor
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
        val COLUMNS = arrayOf(
            "id", "kind", "title", "year", "season", "episode", "episode_title", "tmdb_id", "position_ms", "duration_ms",
            "last_watched",
        )
        val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(ContinueWatching.AUTHORITY, "items", ITEMS)
            addURI(ContinueWatching.AUTHORITY, "items/*", ITEM)
        }
    }
}
