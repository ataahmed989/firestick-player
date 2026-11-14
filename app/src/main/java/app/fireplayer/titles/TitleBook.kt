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
class TitleBook(context: Context) : SQLiteOpenHelper(context, "titles.db", null, VERSION) {

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

    /** One of a show's episodes, by its id in the provider's links, and its file's type ("mkv") when the provider said. */
    class Episode(val id: Int, val series: Int, val season: Int, val number: Int, val extension: String? = null)

    /** One film or show for Continue Watching: its English name (null while it has none), year and TMDB id (0 if not known). */
    class About(val title: String?, val year: Int, val tmdb: Int)

    /** How far along a provider's titles are, for Settings. */
    class Progress(val server: String, val renamed: Int, val kept: Int, val waiting: Int)

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE item(server TEXT NOT NULL, kind INTEGER NOT NULL, id INTEGER NOT NULL, raw TEXT NOT NULL,
               raw_hash INTEGER NOT NULL, tmdb INTEGER NOT NULL, category TEXT, year INTEGER NOT NULL, title TEXT,
               state INTEGER NOT NULL, checked INTEGER NOT NULL, found INTEGER NOT NULL DEFAULT 0,
               PRIMARY KEY(server, kind, id)) WITHOUT ROWID""",
        )
        db.execSQL("CREATE INDEX item_state ON item(state, checked)")
        db.execSQL(
            """CREATE TABLE category(server TEXT NOT NULL, kind INTEGER NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL,
               PRIMARY KEY(server, kind, id)) WITHOUT ROWID""",
        )
        db.execSQL("CREATE TABLE tmdb(key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID")
        createEpisodes(db)
        createSeen(db)
        createLogins(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Better rules may find what the old ones didn't: titles kept as they were are asked about again.
        if (oldVersion < 2) db.execSQL("UPDATE item SET state = $WAITING, checked = 0 WHERE state = $KEPT")
        if (oldVersion < 3) createEpisodes(db)
        else if (oldVersion < 4) db.execSQL("ALTER TABLE episode ADD COLUMN extension TEXT")
        if (oldVersion < 4) db.execSQL("ALTER TABLE item ADD COLUMN found INTEGER NOT NULL DEFAULT 0")
        // A quality tag in round brackets ("- 2021 (4K HDR)") now comes off, so the year is read: asked about again
        if (oldVersion in 2..4) db.execSQL("UPDATE item SET state = $WAITING, checked = 0 WHERE state = $KEPT")
        if (oldVersion < 7) createLogins(db)
        if (oldVersion < 6) {
            createSeen(db)
            // Providers already listed count as asked about now, so none is taken for gone the day this comes in.
            db.execSQL("INSERT OR IGNORE INTO seen(server, at) SELECT DISTINCT server, ${System.currentTimeMillis() / 1000} FROM item")
        }
    }

    /**
     * Each playlist's login as TiviMate uses it, so a copy of a title on another playlist can be played: kept only here,
     * in FirePlayer's own storage, never logged or shown.
     */
    private fun createLogins(db: SQLiteDatabase) =
        db.execSQL("CREATE TABLE IF NOT EXISTS login(server TEXT PRIMARY KEY, user TEXT NOT NULL, pass TEXT NOT NULL) WITHOUT ROWID")

    fun saveLogin(server: String, user: String, pass: String) {
        writableDatabase.insertWithOnConflict("login", null, ContentValues().apply {
            put("server", server)
            put("user", user)
            put("pass", pass)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** The playlist's login (user, password), or null when TiviMate hasn't used it through FirePlayer yet. */
    fun login(server: String): Pair<String, String>? =
        readableDatabase.rawQuery("SELECT user, pass FROM login WHERE server = ?", arrayOf(server))
            .use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null }

    private fun createSeen(db: SQLiteDatabase) =
        db.execSQL("CREATE TABLE IF NOT EXISTS seen(server TEXT PRIMARY KEY, at INTEGER NOT NULL) WITHOUT ROWID")

    /** TiviMate asked about this provider's lists just now (seconds): its playlist is still there. */
    fun seen(server: String, now: Long) {
        writableDatabase.insertWithOnConflict("seen", null, ContentValues().apply {
            put("server", server)
            put("at", now)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Providers whose playlist seems gone from TiviMate: not asked about for [idleS] while TiviMate has asked about
     * another one within [activeS] (so it's in use, and only that playlist stopped). Seconds.
     */
    fun gone(now: Long, idleS: Long = GONE_AFTER_S, activeS: Long = IN_USE_S): List<String> {
        val all = HashMap<String, Long>()
        readableDatabase.rawQuery("SELECT server, at FROM seen", null).use { c ->
            while (c.moveToNext()) all[c.getString(0)] = c.getLong(1)
        }
        readableDatabase.rawQuery("SELECT DISTINCT server FROM item", null).use { c ->
            while (c.moveToNext()) all.putIfAbsent(c.getString(0), now)
        }
        if (all.values.none { now - it < activeS }) return emptyList()
        return all.filter { now - it.value >= idleS }.keys.toList()
    }

    /** Forgets everything kept for a provider: its English names, categories and episodes. */
    fun forget(server: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (table in listOf("item", "category", "episode", "seen", "login")) db.delete(table, "server = ?", arrayOf(server))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun createEpisodes(db: SQLiteDatabase) = db.execSQL(
        """CREATE TABLE IF NOT EXISTS episode(server TEXT NOT NULL, id INTEGER NOT NULL, series INTEGER NOT NULL,
           season INTEGER NOT NULL, number INTEGER NOT NULL, extension TEXT, PRIMARY KEY(server, id)) WITHOUT ROWID""",
    )

    /** A show's episodes, as TiviMate (or FirePlayer's next-episode look-up) listed them. */
    fun recordEpisodes(server: String, episodes: List<Episode>) {
        if (episodes.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val insert = db.compileStatement(
                "INSERT OR REPLACE INTO episode(server, id, series, season, number, extension) VALUES (?, ?, ?, ?, ?, ?)",
            )
            for (e in episodes) {
                insert.clearBindings()
                insert.bindString(1, server)
                insert.bindLong(2, e.id.toLong())
                insert.bindLong(3, e.series.toLong())
                insert.bindLong(4, e.season.toLong())
                insert.bindLong(5, e.number.toLong())
                e.extension?.let { insert.bindString(6, it) } ?: insert.bindNull(6)
                insert.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** The episode with this id in the provider's links, once its show's episodes were listed. */
    fun episode(server: String, id: Int): Episode? =
        readableDatabase.rawQuery(
            "SELECT series, season, number, extension FROM episode WHERE server = ? AND id = ?",
            arrayOf(server, id.toString()),
        ).use { c ->
            if (c.moveToFirst()) Episode(id, c.getInt(0), c.getInt(1), c.getInt(2), if (c.isNull(3)) null else c.getString(3)) else null
        }

    /** The episode after [e] in its show, by season and number; null when it's the last one listed. */
    fun episodeAfter(server: String, e: Episode): Episode? =
        readableDatabase.rawQuery(
            """SELECT id, season, number, extension FROM episode WHERE server = ? AND series = ?
               AND (season > ? OR (season = ? AND number > ?)) ORDER BY season, number LIMIT 1""",
            arrayOf(server, e.series.toString(), e.season.toString(), e.season.toString(), e.number.toString()),
        ).use { c ->
            if (c.moveToFirst()) Episode(c.getInt(0), e.series, c.getInt(1), c.getInt(2), if (c.isNull(3)) null else c.getString(3))
            else null
        }

    /**
     * What's known of one title for Continue Watching. Its TMDB id is the one its English name was confirmed with;
     * for a title renamed before those were kept, the provider's, which the name was most likely confirmed by.
     */
    fun about(server: String, kind: Kind, id: Int): About? =
        readableDatabase.rawQuery(
            "SELECT title, year, CASE WHEN found > 0 THEN found WHEN state = $RENAMED THEN tmdb ELSE 0 END FROM item WHERE server = ? AND kind = ? AND id = ?",
            arrayOf(server, kind.code.toString(), id.toString()),
        ).use { c -> if (c.moveToFirst()) About(if (c.isNull(0)) null else c.getString(0), c.getInt(1), c.getInt(2)) else null }

    /** One listing of a film or show: the playlist it's on, the provider's id, its name for it and its category's name. */
    class Copy(val server: String, val id: Int, val raw: String, val category: String? = null)

    private val copyColumns = "SELECT i.server, i.id, i.raw, c.name FROM item i LEFT JOIN category c " +
        "ON c.server = i.server AND c.kind = i.kind AND c.id = i.category WHERE i.kind = ? AND "

    /**
     * Every listing of the film or show with TMDB id [tmdb] on every playlist, this one included: providers often list
     * a title several times (4K, 1080p, another language). At most [limit].
     */
    fun copies(kind: Kind, tmdb: Int, limit: Int = 40): List<Copy> {
        if (tmdb <= 0) return emptyList()
        // The number written in: bound as text it would never equal the CASE, which has no type to convert it to.
        return copyQuery("(CASE WHEN i.found > 0 THEN i.found WHEN i.state = $RENAMED THEN i.tmdb ELSE 0 END) = $tmdb LIMIT $limit",
            arrayOf(kind.code.toString()))
    }

    /** Every listing with the English [title] and [year] TMDB gave it, on every playlist: copies whose TMDB id differs or wasn't kept. */
    fun copiesByTitle(kind: Kind, title: String, year: Int, limit: Int = 40): List<Copy> {
        // A name alone could be another film ("Home"): only with its year.
        if (year <= 0) return emptyList()
        return copyQuery("i.title = ? AND i.year = $year LIMIT $limit", arrayOf(kind.code.toString(), title))
    }

    private fun copyQuery(where: String, args: Array<String>): List<Copy> {
        val out = ArrayList<Copy>()
        readableDatabase.rawQuery(copyColumns + where, args).use { c ->
            while (c.moveToNext()) out += Copy(c.getString(0), c.getInt(1), c.getString(2), if (c.isNull(3)) null else c.getString(3))
        }
        return out
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
     * one; of each, shows first, then the newest films (the highest ids). A try that failed before [retrySince] (when
     * Settings asked to check now) is tried again at once.
     */
    fun waiting(now: Long, limit: Int, retrySince: Long = 0): List<Waiting> {
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
                arrayOf(server, maxOf(now - RETRY_FAILED_S, retrySince).toString(), (now - RETRY_KEPT_S).toString()),
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

    /**
     * TMDB's answer for [w]: its English name, or null to keep the provider's, and the TMDB id it was confirmed by.
     * Ignored if the provider renamed it since.
     */
    fun answered(w: Waiting, title: String?, now: Long, tmdb: Int = 0) {
        writableDatabase.execSQL(
            "UPDATE item SET title = ?, state = ?, checked = ?, found = ? WHERE server = ? AND kind = ? AND id = ? AND raw_hash = ?",
            arrayOf(title, if (title != null) RENAMED else KEPT, now, if (title != null) tmdb else 0, w.server, w.kind.code, w.id, w.raw.hashCode()),
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
        // 2: TitleMatcher's better rules; 3: episodes and their shows; 4: episodes' file types, and TMDB ids found.
        // 6: when TiviMate last asked about each provider, so a playlist taken out of TiviMate is forgotten here too.
        const val VERSION = 7
        const val WAITING = 0
        const val RENAMED = 1
        const val KEPT = 2
        // A failed try (TMDB unreachable) is tried again after this; a title TMDB wasn't sure of, after a month.
        const val RETRY_FAILED_S = 10 * 60L
        const val RETRY_KEPT_S = 30 * 24 * 3600L
        // TiviMate refreshes a playlist's lists at least daily while it's used.
        const val GONE_AFTER_S = 7 * 24 * 3600L
        const val IN_USE_S = 2 * 24 * 3600L
    }
}
