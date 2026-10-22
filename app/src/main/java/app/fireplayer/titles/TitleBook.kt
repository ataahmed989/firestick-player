package app.fireplayer.titles

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.fireplayer.subs.Tmdb

/**
 * Every film and show TiviMate has listed through FirePlayer, by provider: the provider's name for it, and the English
 * name it gets in TiviMate once TMDB confirms it ([TitleMatcher]). Also TMDB's answers, so a title listed by
 * several providers is asked about once. Opened by the proxy and, to show progress in Settings, by the app.
 */
class TitleBook(context: Context) : SQLiteOpenHelper(context, "titles.db", null, MATCH_RULES) {

    enum class Kind(val code: Int) { MOVIE(0), SHOW(1) }

    /** What's known of one listed title: [rawHash] tells whether the provider renamed it since. */
    class Known(val rawHash: Int, val title: String?)

    /** A title as the provider lists it. */
    class Listed(val id: Int, val raw: String, val tmdb: Int, val category: String?, val year: Int)

    /** A title waiting for TMDB, with its category's name, for the quality tag. */
    class Waiting(
        val server: String,
        val kind: Kind,
        val id: Int,
        val raw: String,
        val tmdb: Int,
        val category: String?,
        val year: Int,
    )

    /** How far along a provider's titles are, for Settings. */
    class Progress(val server: String, val renamed: Int, val kept: Int, val waiting: Int)

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE item(server TEXT NOT NULL, kind INTEGER NOT NULL, id INTEGER NOT NULL, raw TEXT NOT NULL,
               raw_hash INTEGER NOT NULL, tmdb INTEGER NOT NULL, category TEXT, year INTEGER NOT NULL, title TEXT,
               state INTEGER NOT NULL, checked INTEGER NOT NULL, PRIMARY KEY(server, kind, id)) WITHOUT ROWID""",
        )
        db.execSQL("CREATE INDEX item_state ON item(state, checked)")
        db.execSQL(
            """CREATE TABLE category(server TEXT NOT NULL, kind INTEGER NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL,
               PRIMARY KEY(server, kind, id)) WITHOUT ROWID""",
        )
        db.execSQL("CREATE TABLE tmdb(key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID")
    }

    // Better rules may find what the old ones didn't: titles kept as they were are asked about again.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("UPDATE item SET state = $WAITING, checked = 0 WHERE state = $KEPT")
    }

    /** Everything known of a provider's films or shows, by id. Read once per list TiviMate asks for. */
    fun known(server: String, kind: Kind): Map<Int, Known> {
        val map = HashMap<Int, Known>()
        readableDatabase.rawQuery(
            "SELECT id, raw_hash, title FROM item WHERE server = ? AND kind = ?",
            arrayOf(server, kind.code.toString()),
        ).use { c ->
            while (c.moveToNext()) map[c.getInt(0)] = Known(c.getInt(1), if (c.isNull(2)) null else c.getString(2))
        }
        return map
    }

    /** The English name of one title, or null while it has none. */
    fun title(server: String, kind: Kind, id: Int): String? =
        readableDatabase.rawQuery(
            "SELECT title FROM item WHERE server = ? AND kind = ? AND id = ?",
            arrayOf(server, kind.code.toString(), id.toString()),
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }

    /** Titles new to the list, or renamed by the provider: they wait for TMDB again. */
    fun record(server: String, kind: Kind, titles: List<Listed>) {
        if (titles.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val insert = db.compileStatement(
                """INSERT OR REPLACE INTO item(server, kind, id, raw, raw_hash, tmdb, category, year, title, state, checked)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, $WAITING, 0)""",
            )
            for (t in titles) {
                insert.clearBindings()
                insert.bindString(1, server)
                insert.bindLong(2, kind.code.toLong())
                insert.bindLong(3, t.id.toLong())
                insert.bindString(4, t.raw)
                insert.bindLong(5, t.raw.hashCode().toLong())
                insert.bindLong(6, t.tmdb.toLong())
                t.category?.let { insert.bindString(7, it) } ?: insert.bindNull(7)
                insert.bindLong(8, t.year.toLong())
                insert.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** A provider's category names ("4K Movies"), which may say the quality of everything in them. */
    fun saveCategories(server: String, kind: Kind, names: Map<String, String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((id, name) in names) {
                db.insertWithOnConflict("category", null, ContentValues().apply {
                    put("server", server)
                    put("kind", kind.code)
                    put("id", id)
                    put("name", name)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Titles to ask TMDB about: new ones, ones whose last try failed a while ago, and, now and then, ones TMDB
     * wasn't sure of (it learns new titles). Every provider in turn, so a small playlist never waits behind a big
     * one; of each, shows first, then the newest films (the highest ids).
     */
    fun waiting(now: Long, limit: Int): List<Waiting> {
        val db = readableDatabase
        val servers = db.rawQuery("SELECT DISTINCT server FROM item", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        if (servers.isEmpty()) return emptyList()
        val each = (limit / servers.size).coerceAtLeast(1)
        val lists = servers.map { server ->
            val list = ArrayList<Waiting>(each)
            db.rawQuery(
                """SELECT i.server, i.kind, i.id, i.raw, i.tmdb, c.name, i.year FROM item i
                   LEFT JOIN category c ON c.server = i.server AND c.kind = i.kind AND c.id = i.category
                   WHERE i.server = ? AND ((i.state = $WAITING AND i.checked < ?) OR (i.state = $KEPT AND i.checked < ?))
                   ORDER BY i.kind DESC, i.id DESC LIMIT $each""",
                arrayOf(server, (now - RETRY_FAILED_S).toString(), (now - RETRY_KEPT_S).toString()),
            ).use { c ->
                while (c.moveToNext()) {
                    list += Waiting(
                        c.getString(0), if (c.getInt(1) == Kind.SHOW.code) Kind.SHOW else Kind.MOVIE, c.getInt(2), c.getString(3),
                        c.getInt(4), if (c.isNull(5)) null else c.getString(5), c.getInt(6),
                    )
                }
            }
            list
        }
        // Taking turns: one of each provider's, then the next of each.
        return (0 until each).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
    }

    /** TMDB's answer for [w]: its English name, or null to keep the provider's. Ignored if the provider renamed it since. */
    fun answered(w: Waiting, title: String?, now: Long) {
        writableDatabase.execSQL(
            "UPDATE item SET title = ?, state = ?, checked = ? WHERE server = ? AND kind = ? AND id = ? AND raw_hash = ?",
            arrayOf(title, if (title != null) RENAMED else KEPT, now, w.server, w.kind.code, w.id, w.raw.hashCode()),
        )
    }

    /** TMDB couldn't be asked: try again later. */
    fun failed(w: Waiting, now: Long) {
        writableDatabase.execSQL(
            "UPDATE item SET checked = ? WHERE server = ? AND kind = ? AND id = ?",
            arrayOf(now, w.server, w.kind.code, w.id),
        )
    }

    fun progress(): List<Progress> {
        val list = ArrayList<Progress>()
        readableDatabase.rawQuery(
            """SELECT server, SUM(state = $RENAMED), SUM(state = $KEPT), SUM(state = $WAITING) FROM item
               GROUP BY server ORDER BY server""",
            null,
        ).use { c -> while (c.moveToNext()) list += Progress(c.getString(0), c.getInt(1), c.getInt(2), c.getInt(3)) }
        return list
    }

    /** TMDB's answers, kept here (see [Tmdb.Memory]). */
    val tmdbMemory: Tmdb.Memory = object : Tmdb.Memory {
        override fun get(key: String): String? =
            readableDatabase.rawQuery("SELECT value FROM tmdb WHERE key = ?", arrayOf(key))
                .use { c -> if (c.moveToFirst()) c.getString(0) else null }

        override fun put(key: String, value: String) {
            writableDatabase.insertWithOnConflict("tmdb", null, ContentValues().apply {
                put("key", key)
                put("value", value)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private companion object {
        // The version of TitleMatcher's rules; raised when they change.
        const val MATCH_RULES = 2
        const val WAITING = 0
        const val RENAMED = 1
        const val KEPT = 2
        // A failed try (TMDB unreachable) is tried again after this; a title TMDB wasn't sure of, after a month.
        const val RETRY_FAILED_S = 10 * 60L
        const val RETRY_KEPT_S = 30 * 24 * 3600L
    }
}
