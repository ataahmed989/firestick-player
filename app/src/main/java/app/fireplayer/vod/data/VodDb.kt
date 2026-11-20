package app.fireplayer.vod.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Kind
import org.json.JSONObject

/**
 * Films & Shows storage (`vod.db`), this TV only: the user's library (My List, watched marks, where playback stopped)
 * and the TMDB → IMDb ids Top 10 has learnt. Opened by the main app process only. Library rows are never deleted:
 * leaving a list flips a flag with its time, so a sync could be added later.
 *
 * Version 2 (the title page) adds `episode_progress`, where playback of each episode stopped. A single episode's watched
 * mark is a `watched` row with the episode's id ("tt…:S:E", kind series), so no other table changes: the upgrade from
 * version 1 only creates the new table, and My List, watched marks and Continue Watching stay as they were.
 *
 * Version 3 (sources and playback) adds `last_release`: the release each film or episode last played from, as info hash
 * + file index + add-on name (never a link). The upgrade only creates that table.
 */
class VodDb(context: Context) : SQLiteOpenHelper(context, "vod.db", null, VERSION), LibraryStore, IdStore {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE my_list(kind TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, at INTEGER NOT NULL, " +
                "in_list INTEGER NOT NULL, PRIMARY KEY(kind, id)) WITHOUT ROWID",
        )
        db.execSQL(
            "CREATE TABLE watched(kind TEXT NOT NULL, id TEXT NOT NULL, at INTEGER NOT NULL, watched INTEGER NOT NULL, " +
                "PRIMARY KEY(kind, id)) WITHOUT ROWID",
        )
        // One row per title: a series' row is about its latest episode.
        db.execSQL(
            "CREATE TABLE progress(kind TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, season INTEGER, " +
                "episode INTEGER, episode_name TEXT, position INTEGER NOT NULL, duration INTEGER NOT NULL, " +
                "at INTEGER NOT NULL, hidden INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(kind, id)) WITHOUT ROWID",
        )
        // imdb is "" for "TMDB had none" (kept a short while, see IdMap).
        db.execSQL(
            "CREATE TABLE tmdb_imdb(kind TEXT NOT NULL, tmdb INTEGER NOT NULL, imdb TEXT NOT NULL, at INTEGER NOT NULL, " +
                "PRIMARY KEY(kind, tmdb)) WITHOUT ROWID",
        )
        db.execSQL(CREATE_EPISODE_PROGRESS)
        db.execSQL(CREATE_LAST_RELEASE)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 1 → 2: where each episode stopped. Nothing else changes.
        if (oldVersion < 2) db.execSQL(CREATE_EPISODE_PROGRESS)
        // 2 → 3: the release each film or episode last played from.
        if (oldVersion < 3) db.execSQL(CREATE_LAST_RELEASE)
    }

    /** Each time it's opened: ids learnt more than [IdMap.HIT_KEPT_MS] ago go (TMDB's 6-month limit). */
    override fun onOpen(db: SQLiteDatabase) {
        if (db.isReadOnly) return
        val before = System.currentTimeMillis() - IdMap.HIT_KEPT_MS
        if (before > 0) runCatching { db.delete("tmdb_imdb", "at < ?", arrayOf(before.toString())) }
    }

    // ---- LibraryStore ----

    override fun load(): LibraryStore.Saved {
        val db = readableDatabase
        val listed = db.rawQuery("SELECT title, at, in_list FROM my_list", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val title = parseTitle(c.getString(0)) ?: continue
                    add(Listed(title, c.getLong(1), c.getInt(2) != 0))
                }
            }
        }
        val watched = db.rawQuery("SELECT kind, id, at, watched FROM watched", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val kind = kindOf(c.getString(0)) ?: continue
                    add(Watched(kind, c.getString(1), c.getLong(2), c.getInt(3) != 0))
                }
            }
        }
        val progress = db.rawQuery(
            "SELECT title, season, episode, episode_name, position, duration, at, hidden FROM progress", null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val title = parseTitle(c.getString(0)) ?: continue
                    val episode = if (c.isNull(1) || c.isNull(2)) null else EpisodeRef(c.getInt(1), c.getInt(2), c.getString(3))
                    add(Progress(title, episode, c.getLong(4), c.getLong(5), c.getLong(6), c.getInt(7) != 0))
                }
            }
        }
        val positions = db.rawQuery("SELECT id, position, duration, at FROM episode_progress", null).use { c ->
            buildList {
                while (c.moveToNext()) add(EpisodePosition(c.getString(0), c.getLong(1), c.getLong(2), c.getLong(3)))
            }
        }
        val releases = db.rawQuery("SELECT kind, id, addon, hash, file_idx, at FROM last_release", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val kind = kindOf(c.getString(0)) ?: continue
                    add(ReleaseRow(kind, c.getString(1), c.getString(2), c.getString(3), c.getInt(4), c.getLong(5)))
                }
            }
        }
        return LibraryStore.Saved(listed, watched, progress, positions, releases)
    }

    override fun saveListed(l: Listed) {
        writableDatabase.insertWithOnConflict("my_list", null, ContentValues().apply {
            put("kind", l.title.kind.type)
            put("id", l.title.id)
            put("title", TitleJson.write(l.title).toString())
            put("at", l.at)
            put("in_list", if (l.inList) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun saveWatched(w: Watched) {
        writableDatabase.insertWithOnConflict("watched", null, watchedValues(w, ContentValues()), SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** One transaction for all of them ("Mark watched up to here" on a long show is hundreds of rows). */
    override fun saveWatchedAll(marks: List<Watched>) {
        if (marks.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues()
            for (w in marks) {
                values.clear()
                db.insertWithOnConflict("watched", null, watchedValues(w, values), SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun watchedValues(w: Watched, values: ContentValues) = values.apply {
        put("kind", w.kind.type)
        put("id", w.id)
        put("at", w.at)
        put("watched", if (w.watched) 1 else 0)
    }

    override fun saveEpisodePosition(p: EpisodePosition) {
        writableDatabase.insertWithOnConflict("episode_progress", null, ContentValues().apply {
            put("id", p.id)
            put("position", p.positionMs)
            put("duration", p.durationMs)
            put("at", p.at)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun saveProgress(p: Progress) {
        writableDatabase.insertWithOnConflict("progress", null, ContentValues().apply {
            put("kind", p.title.kind.type)
            put("id", p.title.id)
            put("title", TitleJson.write(p.title).toString())
            if (p.episode != null) {
                put("season", p.episode.season)
                put("episode", p.episode.episode)
                put("episode_name", p.episode.name)
            } else {
                putNull("season")
                putNull("episode")
                putNull("episode_name")
            }
            put("position", p.positionMs)
            put("duration", p.durationMs)
            put("at", p.at)
            put("hidden", if (p.hidden) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun saveRelease(r: ReleaseRow) {
        writableDatabase.insertWithOnConflict("last_release", null, ContentValues().apply {
            put("kind", r.kind.type)
            put("id", r.id)
            put("addon", r.addon)
            put("hash", r.hash)
            put("file_idx", r.fileIdx)
            put("at", r.at)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    // ---- IdStore ----

    override fun idFor(kind: Kind, tmdbId: Int): Pair<String, Long>? =
        readableDatabase.rawQuery(
            "SELECT imdb, at FROM tmdb_imdb WHERE kind = ? AND tmdb = ?", arrayOf(kind.type, tmdbId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getString(0) to c.getLong(1) else null }

    override fun putIds(kind: Kind, entries: List<Triple<Int, String, Long>>) {
        if (entries.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues()
            for ((tmdb, imdb, at) in entries) {
                values.clear()
                values.put("kind", kind.type)
                values.put("tmdb", tmdb)
                values.put("imdb", imdb)
                values.put("at", at)
                db.insertWithOnConflict("tmdb_imdb", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun parseTitle(json: String?): app.fireplayer.vod.Title? =
        json?.let { runCatching { TitleJson.read(JSONObject(it)) }.getOrNull() }

    private companion object {
        const val VERSION = 3

        /** Where playback of each episode ("tt…:S:E") stopped (version 2). */
        const val CREATE_EPISODE_PROGRESS =
            "CREATE TABLE IF NOT EXISTS episode_progress(id TEXT NOT NULL PRIMARY KEY, position INTEGER NOT NULL, " +
                "duration INTEGER NOT NULL, at INTEGER NOT NULL) WITHOUT ROWID"

        /** The release a film or episode ("tt…" or "tt…:S:E") last played from (version 3): hash + file index, no link. */
        const val CREATE_LAST_RELEASE =
            "CREATE TABLE IF NOT EXISTS last_release(kind TEXT NOT NULL, id TEXT NOT NULL, addon TEXT NOT NULL, " +
                "hash TEXT NOT NULL, file_idx INTEGER NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(kind, id)) WITHOUT ROWID"
    }
}
