package app.fireplayer.live

import android.content.ContentValues
import android.content.Context
import android.database.AbstractWindowedCursor
import android.database.Cursor
import android.database.CursorWindow
import android.os.Build
import android.os.CancellationSignal
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live TV storage (`live.db`): playlists, groups, channels, favorites, recent channels and EPG programmes.
 *
 * Ownership: only the main app process opens this database (the title service in `:titles` never does), through the
 * one [get] instance. Writes happen on [LiveSync]'s single worker thread; the UI reads on its own background
 * loader. WAL lets those reads go on while a sync writes, and one writer means no write contention.
 *
 * A sync rewrites the provider's columns of each channel (name, number, logo, group, link, EPG id) and removes
 * channels and groups the provider no longer lists. The viewer's own data lives apart: favorites in their own table
 * keyed by playlist + channel id, so they survive refreshes and come back if a removed channel returns.
 */
class LiveDb private constructor(context: Context) : SQLiteOpenHelper(context, "live.db", null, VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    /**
     * How often what the guide lists was written (playlists and their order, groups, channels, the viewer's stars and
     * group choices), and how often its listings were (programmes, which import the guide reads). A screen notes them
     * before a read and keeps what it read for as long as they stand: a visit to Live TV with nothing written since
     * reads no channel list or listing again. Each write counts once it's stored (after its transaction), so a count
     * read before a read is never newer than what that read sees. A playlist's update status and account details
     * ([setPlaylistStatus]) are in no channel list and count for neither. This process only (the database has no other).
     */
    val listChanges = AtomicInteger()
    val epgChanges = AtomicInteger()

    override fun onConfigure(db: SQLiteDatabase) {
        // Android turns auto-vacuum on (FULL) by default: every EPG swap then moved pages around to shrink the file,
        // writing hundreds of MB. INCREMENTAL (allowed on an existing database, unlike NONE) leaves freed pages for
        // the next import to reuse.
        db.rawQuery("PRAGMA auto_vacuum = INCREMENTAL", null).use { it.moveToFirst() }
        // The write-ahead log shrinks back after a big import instead of keeping its largest size on disk.
        db.rawQuery("PRAGMA journal_size_limit = ${8 * 1024 * 1024}", null).use { it.moveToFirst() }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE playlist(id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, url TEXT NOT NULL, " +
                "username TEXT NOT NULL DEFAULT '', password TEXT NOT NULL DEFAULT '', epg_url TEXT NOT NULL DEFAULT '', " +
                "output TEXT NOT NULL DEFAULT 'ts', include_live INTEGER NOT NULL DEFAULT 1, include_vod INTEGER NOT NULL DEFAULT 1, " +
                "position INTEGER NOT NULL DEFAULT 0, update_hours INTEGER NOT NULL DEFAULT 24, last_sync INTEGER NOT NULL DEFAULT 0, " +
                "last_status TEXT NOT NULL DEFAULT '', expires_at INTEGER NOT NULL DEFAULT 0, max_connections INTEGER NOT NULL DEFAULT 0, " +
                "playlist_epg_url TEXT NOT NULL DEFAULT '', hide_new_groups INTEGER NOT NULL DEFAULT 0) WITHOUT ROWID",
        )
        // blocked / user_position: no longer used (version 5 kept the viewer's choices here; now in group_pref).
        db.execSQL("CREATE TABLE channel_group(playlist_id TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, position INTEGER NOT NULL, blocked INTEGER NOT NULL DEFAULT 0, user_position INTEGER, PRIMARY KEY(playlist_id, id)) WITHOUT ROWID")
        db.execSQL(
            "CREATE TABLE channel(playlist_id TEXT NOT NULL, id TEXT NOT NULL, number INTEGER NOT NULL, name TEXT NOT NULL, logo TEXT, " +
                "group_id TEXT, url TEXT NOT NULL, epg_ref TEXT, archive_days INTEGER NOT NULL DEFAULT 0, position INTEGER NOT NULL, " +
                "blocked INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(playlist_id, id)) WITHOUT ROWID",
        )
        // The viewer's own choices for groups (Manage groups), kept apart from the provider's list like favorites:
        // a group the provider drops for a while (or "Include TV channels" turned off) keeps them when it comes back.
        // A row means the group has been seen before ("Hide new groups" hides groups without one).
        db.execSQL("CREATE TABLE group_pref(playlist_id TEXT NOT NULL, group_id TEXT NOT NULL, hidden INTEGER NOT NULL DEFAULT 0, user_position INTEGER, PRIMARY KEY(playlist_id, group_id)) WITHOUT ROWID")
        db.execSQL("CREATE INDEX channel_group_idx ON channel(playlist_id, group_id, position)")
        db.execSQL("CREATE INDEX channel_pos_idx ON channel(playlist_id, position)")
        db.execSQL("CREATE TABLE favorite(playlist_id TEXT NOT NULL, channel_id TEXT NOT NULL, added INTEGER NOT NULL, PRIMARY KEY(playlist_id, channel_id)) WITHOUT ROWID")
        db.execSQL("CREATE TABLE recent(playlist_id TEXT NOT NULL, channel_id TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(playlist_id, channel_id)) WITHOUT ROWID")
        // Programmes are kept per EPG source: the playlist whose EPG listed them.
        db.execSQL(
            "CREATE TABLE program(source TEXT NOT NULL, epg_ref TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, " +
                "title TEXT NOT NULL, description TEXT, category TEXT, PRIMARY KEY(source, epg_ref, start)) WITHOUT ROWID",
        )
        // gen: which import of this source the guide reads (its programme rows are stored under [physical]).
        db.execSQL("CREATE TABLE epg_status(source TEXT PRIMARY KEY, url TEXT NOT NULL, last_sync INTEGER NOT NULL, status TEXT NOT NULL, programmes INTEGER NOT NULL, gen INTEGER NOT NULL DEFAULT 0) WITHOUT ROWID")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 was groundwork that no build ever opened (nothing called it), so no viewer has data in it.
        if (oldVersion < 2) {
            for (t in listOf("program", "channel_epg_map", "channel", "channel_group", "playlist", "favorite", "recent", "epg_status")) {
                db.execSQL("DROP TABLE IF EXISTS $t")
            }
            onCreate(db)
            return
        }
        // Version 3: the epg_ref index was never used by a query (every read gives the source), yet every imported
        // programme paid for it.
        if (oldVersion < 3) db.execSQL("DROP INDEX IF EXISTS program_ref_idx")
        // Version 4: a playlist's channels in order without sorting them (32,000 channels took seconds to list).
        if (oldVersion < 4) {
            db.execSQL("CREATE INDEX IF NOT EXISTS channel_pos_idx ON channel(playlist_id, position)")
            // Imports switch generations instead of renaming every row (existing rows are generation 0).
            db.execSQL("ALTER TABLE epg_status ADD COLUMN gen INTEGER NOT NULL DEFAULT 0")
        }
        // Version 5: Manage groups (the viewer's order of groups; new groups hidden if asked).
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE channel_group ADD COLUMN user_position INTEGER")
            db.execSQL("ALTER TABLE playlist ADD COLUMN hide_new_groups INTEGER NOT NULL DEFAULT 0")
        }
        // Version 6: the viewer's group choices move to their own table (version 5 kept them on the provider's rows,
        // which a sync deletes with the group). Every known group gets a row, with what was chosen so far.
        if (oldVersion < 6) {
            db.execSQL("CREATE TABLE IF NOT EXISTS group_pref(playlist_id TEXT NOT NULL, group_id TEXT NOT NULL, hidden INTEGER NOT NULL DEFAULT 0, user_position INTEGER, PRIMARY KEY(playlist_id, group_id)) WITHOUT ROWID")
            db.execSQL("INSERT OR IGNORE INTO group_pref(playlist_id, group_id, hidden, user_position) SELECT playlist_id, id, blocked, user_position FROM channel_group")
        }
    }

    // ---- Playlists --------------------------------------------------------------------------------------------

    fun playlists(): List<Playlist> =
        readableDatabase.rawQuery("SELECT $PLAYLIST_COLS FROM playlist ORDER BY position, name", null).use { c ->
            buildList { while (c.moveToNext()) add(playlistAt(c)) }
        }

    fun playlist(id: String): Playlist? =
        readableDatabase.rawQuery("SELECT $PLAYLIST_COLS FROM playlist WHERE id = ?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) playlistAt(c) else null
        }

    /** Adds or edits a playlist's own settings. Sync results (status, account, the playlist's EPG link) are kept. */
    fun savePlaylist(p: Playlist) {
        val db = writableDatabase
        val v = ContentValues().apply {
            put("name", p.name); put("kind", p.kind.code); put("url", p.url); put("username", p.username)
            put("password", p.password); put("epg_url", p.epgUrl); put("output", p.output)
            put("include_live", if (p.includeLive) 1 else 0); put("include_vod", if (p.includeVod) 1 else 0)
            put("position", p.position); put("update_hours", p.updateHours); put("hide_new_groups", if (p.hideNewGroups) 1 else 0)
        }
        if (db.update("playlist", v, "id = ?", arrayOf(p.id)) == 0) {
            v.put("id", p.id)
            db.insertOrThrow("playlist", null, v)
        }
        listChanges.incrementAndGet()
    }

    fun deletePlaylist(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (t in listOf("channel", "channel_group", "group_pref", "favorite", "recent")) db.delete(t, "playlist_id = ?", arrayOf(id))
            // Every generation of its listings (and an interrupted import's).
            db.delete("program", "source = ? OR source = ? OR (source >= ? AND source < ?)", arrayOf(id, id + STAGING, "$id@", "${id}A"))
            db.delete("epg_status", "source = ?", arrayOf(id))
            db.delete("playlist", "id = ?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        listChanges.incrementAndGet()
        epgChanges.incrementAndGet()
    }

    fun setPlaylistStatus(id: String, status: String, syncedAt: Long?, expiresAt: Long? = null, maxConnections: Int? = null, playlistEpgUrl: String? = null) {
        val v = ContentValues().apply {
            put("last_status", status)
            syncedAt?.let { put("last_sync", it) }
            expiresAt?.let { put("expires_at", it) }
            maxConnections?.let { put("max_connections", it) }
            playlistEpgUrl?.let { put("playlist_epg_url", it) }
        }
        writableDatabase.update("playlist", v, "id = ?", arrayOf(id))
    }

    /** The EPG link a playlist's guide comes from: the viewer's own, else the one the playlist names. */
    fun epgUrlFor(p: Playlist): String? {
        if (p.epgUrl.isNotBlank()) return p.epgUrl
        val named = readableDatabase.rawQuery("SELECT playlist_epg_url FROM playlist WHERE id = ?", arrayOf(p.id)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        return named?.takeIf { it.isNotBlank() }
    }

    // ---- Sync --------------------------------------------------------------------------------------------------

    /**
     * Stores one playlist's channels and groups as just listed by the provider. Provider columns are rewritten;
     * channels and groups no longer listed are removed (their favorites stay in their own table, harmlessly, until
     * the channel returns or the playlist is deleted). Returns how many channels were removed.
     */
    fun applySync(playlistId: String, parsed: ParsedPlaylist): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Deleted while its update was running: nothing to store (no orphan rows).
            val exists = db.rawQuery("SELECT 1 FROM playlist WHERE id = ?", arrayOf(playlistId)).use { it.moveToFirst() }
            if (!exists) return 0
            db.execSQL("CREATE TEMP TABLE IF NOT EXISTS seen_channel(id TEXT PRIMARY KEY)")
            db.execSQL("CREATE TEMP TABLE IF NOT EXISTS seen_group(id TEXT PRIMARY KEY)")
            db.execSQL("DELETE FROM seen_channel")
            db.execSQL("DELETE FROM seen_group")

            // UPDATE then INSERT OR IGNORE, not "ON CONFLICT DO UPDATE": that needs SQLite 3.24, and Fire OS 5
            // (API 22, still supported) ships 3.8.
            val setGroup = db.compileStatement("UPDATE channel_group SET name = ?, position = ? WHERE playlist_id = ? AND id = ?")
            val addGroup = db.compileStatement("INSERT OR IGNORE INTO channel_group(playlist_id, id, name, position) VALUES (?, ?, ?, ?)")
            // "Hide new groups": a group never seen before starts hidden, unless this is the playlist's first update
            // with groups (then every group is new). Groups already known keep what the viewer chose.
            val hideNew = db.rawQuery("SELECT hide_new_groups FROM playlist WHERE id = ?", arrayOf(playlistId)).use { it.moveToFirst() && it.getInt(0) != 0 } &&
                db.rawQuery("SELECT 1 FROM group_pref WHERE playlist_id = ? LIMIT 1", arrayOf(playlistId)).use { it.moveToFirst() }
            val knowGroup = db.compileStatement("INSERT OR IGNORE INTO group_pref(playlist_id, group_id, hidden) VALUES (?, ?, ?)")
            val seenGroup = db.compileStatement("INSERT OR IGNORE INTO seen_group(id) VALUES (?)")
            for (g in parsed.groups) {
                setGroup.clearBindings()
                setGroup.bindString(1, g.name); setGroup.bindLong(2, g.position.toLong()); setGroup.bindString(3, playlistId); setGroup.bindString(4, g.id)
                if (setGroup.executeUpdateDelete() == 0) {
                    addGroup.clearBindings()
                    addGroup.bindString(1, playlistId); addGroup.bindString(2, g.id); addGroup.bindString(3, g.name); addGroup.bindLong(4, g.position.toLong())
                    addGroup.executeInsert()
                }
                knowGroup.clearBindings()
                knowGroup.bindString(1, playlistId); knowGroup.bindString(2, g.id); knowGroup.bindLong(3, if (hideNew) 1 else 0)
                knowGroup.executeInsert()
                seenGroup.clearBindings(); seenGroup.bindString(1, g.id); seenGroup.executeInsert()
            }

            val setChannel = db.compileStatement(
                "UPDATE channel SET number = ?, name = ?, logo = ?, group_id = ?, url = ?, epg_ref = ?, archive_days = ?, position = ? " +
                    "WHERE playlist_id = ? AND id = ?",
            )
            val addChannel = db.compileStatement(
                "INSERT OR IGNORE INTO channel(playlist_id, id, number, name, logo, group_id, url, epg_ref, archive_days, position) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            )
            val seenChannel = db.compileStatement("INSERT OR IGNORE INTO seen_channel(id) VALUES (?)")
            for (c in parsed.channels) {
                setChannel.clearBindings()
                setChannel.bindLong(1, c.number.toLong()); setChannel.bindString(2, c.name); bindOrNull(setChannel, 3, c.logo)
                bindOrNull(setChannel, 4, c.groupId); setChannel.bindString(5, c.url); bindOrNull(setChannel, 6, c.epgRef)
                setChannel.bindLong(7, c.archiveDays.toLong()); setChannel.bindLong(8, c.position.toLong())
                setChannel.bindString(9, playlistId); setChannel.bindString(10, c.id)
                if (setChannel.executeUpdateDelete() == 0) {
                    addChannel.clearBindings()
                    addChannel.bindString(1, playlistId); addChannel.bindString(2, c.id); addChannel.bindLong(3, c.number.toLong())
                    addChannel.bindString(4, c.name); bindOrNull(addChannel, 5, c.logo); bindOrNull(addChannel, 6, c.groupId)
                    addChannel.bindString(7, c.url); bindOrNull(addChannel, 8, c.epgRef); addChannel.bindLong(9, c.archiveDays.toLong())
                    addChannel.bindLong(10, c.position.toLong())
                    addChannel.executeInsert()
                }
                seenChannel.clearBindings(); seenChannel.bindString(1, c.id); seenChannel.executeInsert()
            }

            val removed = db.delete("channel", "playlist_id = ? AND id NOT IN (SELECT id FROM seen_channel)", arrayOf(playlistId))
            db.delete("channel_group", "playlist_id = ? AND id NOT IN (SELECT id FROM seen_group)", arrayOf(playlistId))
            db.delete("recent", "playlist_id = ? AND channel_id NOT IN (SELECT id FROM seen_channel)", arrayOf(playlistId))
            db.setTransactionSuccessful()
            return removed
        } finally {
            db.endTransaction()
            // Counted whether or not it was stored: a count too many only has a list read once more.
            listChanges.incrementAndGet()
        }
    }

    /** The XMLTV ids whose programmes a source's EPG should keep (see [EpgSync]). */
    fun epgRefs(playlistIds: Collection<String>): Set<String> {
        if (playlistIds.isEmpty()) return emptySet()
        val marks = playlistIds.joinToString(",") { "?" }
        return readableDatabase.rawQuery(
            "SELECT DISTINCT epg_ref FROM channel WHERE epg_ref IS NOT NULL AND epg_ref != '' AND playlist_id IN ($marks)",
            playlistIds.toTypedArray(),
        ).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
    }

    /**
     * Replaces everything [source]'s EPG listed with what [fill] inserts. The download is stored as a new generation
     * of the source's rows, in batches (the database is never held for a whole download, so the viewer's own changes
     * don't wait). One tiny transaction then points the guide at it, and the old generation is deleted in small
     * steps. (Renaming every row from a staging name to the real one rewrote the whole table: minutes on a TV.)
     *
     * An automatic update stopped for a film ([goesOn] false between steps, or [SyncPlan.PutOff] from [fill]) leaves
     * the guide reading what it read before; the rows it had stored so far, or the old ones not yet deleted, are
     * deleted later, when nothing plays ([tidyProgrammes]), or at the source's next import.
     */
    fun replaceProgrammes(
        source: String,
        url: String,
        keepFrom: Long,
        now: Long,
        /** Whether a download of this many programmes may replace the stored ones (false: it fails, nothing changes). */
        accept: (Int) -> Boolean = { true },
        /** Whether the deleting goes on (an automatic update stops between steps once a film starts). */
        goesOn: () -> Boolean = { true },
        fill: ((String, Programme) -> Unit) -> Int,
    ): Int {
        val db = writableDatabase
        val oldGen = epgGen(source)
        val newGen = oldGen + 1
        val target = physical(source, newGen)
        // What an interrupted import left behind goes first: every stored name of this source but the one read now.
        // Stopped before it's all gone, nothing is read (the new rows would join what's left).
        if (!deleteLeftovers(source, keep = physical(source, oldGen), goesOn)) {
            untidy += source
            throw SyncPlan.PutOff()
        }
        val insert = db.compileStatement(
            "INSERT OR REPLACE INTO program(source, epg_ref, start, stop, title, description, category) VALUES (?, ?, ?, ?, ?, ?, ?)",
        )
        var inBatch = 0
        var switched = false
        var storingNs = 0L
        val t0 = SystemClock.elapsedRealtime()
        db.beginTransaction()
        try {
            val count = fill { ref, p ->
                if (p.stop <= keepFrom) return@fill
                val s0 = System.nanoTime()
                insert.clearBindings()
                insert.bindString(1, target); insert.bindString(2, ref); insert.bindLong(3, p.start); insert.bindLong(4, p.stop)
                insert.bindString(5, p.title); bindOrNull(insert, 6, p.description); bindOrNull(insert, 7, p.category)
                insert.executeInsert()
                if (++inBatch >= BATCH) {
                    db.setTransactionSuccessful()
                    db.endTransaction()
                    inBatch = 0
                    db.beginTransaction()
                }
                storingNs += System.nanoTime() - s0
            }
            if (!accept(count)) throw SyncPlan.Empty()
            db.setTransactionSuccessful()
            db.endTransaction()
            val t1 = SystemClock.elapsedRealtime()
            val v = ContentValues().apply {
                put("source", source); put("url", url); put("last_sync", now); put("status", "OK"); put("programmes", count); put("gen", newGen)
            }
            db.insertWithOnConflict("epg_status", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            switched = true
            epgChanges.incrementAndGet()
            val t2 = SystemClock.elapsedRealtime()
            // A failure here only leaves rows behind (removed at the next import), never the ones now read; stopped for a
            // film, they go when nothing plays.
            val gone = runCatching { deleteRows(physical(source, oldGen), goesOn) }.getOrDefault(true)
            if (!gone) untidy += source
            Log.i(TAG, "EPG stored: $count programmes; download + read + store ${t1 - t0} ms (storing ${storingNs / 1_000_000} ms), switch ${t2 - t1} ms, old removed in ${SystemClock.elapsedRealtime() - t2} ms")
            return count
        } catch (t: Throwable) {
            // A failed download leaves the old listings as they were; its partial rows go (when nothing plays, if it
            // was stopped for a film: deleting them is work of its own).
            runCatching { if (db.inTransaction()) db.endTransaction() }
            if (!switched) {
                val gone = runCatching { deleteRows(target, goesOn) }.getOrDefault(true)
                if (!gone) untidy += source
            }
            throw t
        } finally {
            if (db.inTransaction()) db.endTransaction()
        }
    }

    /** The name a source's programme rows are stored under in generation [gen] (0: the source itself). */
    private fun physical(source: String, gen: Int) = if (gen == 0) source else "$source@$gen"

    private fun epgGen(source: String): Int =
        readableDatabase.rawQuery("SELECT gen FROM epg_status WHERE source = ?", arrayOf(source)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /**
     * Sources whose rows of another generation than the guide's were left by an automatic update stopped for a film
     * (memory only: after a restart, the source's next import deletes them, as an interrupted one's).
     */
    private val untidy: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Deletes the rows a stopped guide update left behind ([replaceProgrammes]), in small steps while [goesOn] (an
     * automatic update's safe points). Nothing to do, almost always: no database is touched.
     */
    fun tidyProgrammes(goesOn: () -> Boolean) {
        if (untidy.isEmpty()) return
        for (source in untidy.toList()) {
            if (!goesOn()) return
            if (deleteLeftovers(source, keep = physical(source, epgGen(source)), goesOn)) untidy.remove(source)
        }
    }

    /**
     * Deletes every stored name of [source] except [keep]: the generation 0 name, the old staging name, and any
     * "source@n" an interrupted import or switch left. Names are found by stepping through the key (index seeks).
     * False when [goesOn] stopped it first.
     */
    private fun deleteLeftovers(source: String, keep: String, goesOn: () -> Boolean = { true }): Boolean {
        val names = ArrayList<String>()
        if (keep != source) names += source
        names += source + STAGING
        var after = "$source@"
        val upper = "${source}A"
        while (true) {
            val next = readableDatabase.rawQuery(
                "SELECT source FROM program WHERE source > ? AND source < ? ORDER BY source LIMIT 1", arrayOf(after, upper),
            ).use { if (it.moveToFirst()) it.getString(0) else null } ?: break
            if (next != keep) names += next
            after = next
        }
        for (n in names) if (!deleteRows(n, goesOn)) return false
        return true
    }

    /**
     * Deletes one stored source's rows a few channels at a time, each step its own short transaction, while [goesOn].
     * False when it stopped with rows left.
     */
    private fun deleteRows(physicalSource: String, goesOn: () -> Boolean = { true }): Boolean {
        val step = writableDatabase.compileStatement(
            "DELETE FROM program WHERE source = ?1 AND epg_ref IN (SELECT epg_ref FROM program WHERE source = ?1 GROUP BY epg_ref LIMIT 50)",
        )
        return step.use {
            it.bindString(1, physicalSource)
            var more = true
            while (more && goesOn()) more = it.executeUpdateDelete() > 0
            !more
        }
    }

    /**
     * Records how a source's EPG update went. [syncedAt] null (a failure) leaves the last good update's time and
     * address as they were, so the guide's "last updated" only moves on success and the source is tried again.
     */
    fun setEpgStatus(source: String, url: String, status: String, syncedAt: Long?) {
        val v = ContentValues().apply { put("status", status); if (syncedAt != null) { put("url", url); put("last_sync", syncedAt) } }
        if (writableDatabase.update("epg_status", v, "source = ?", arrayOf(source)) == 0) {
            v.put("source", source); v.put("programmes", 0); v.put("url", url); if (syncedAt == null) v.put("last_sync", 0L)
            writableDatabase.insert("epg_status", null, v)
        }
        epgChanges.incrementAndGet()
    }

    class EpgStatus(val source: String, val url: String, val lastSync: Long, val status: String, val programmes: Int)

    fun epgStatuses(): List<EpgStatus> =
        readableDatabase.rawQuery("SELECT source, url, last_sync, status, programmes FROM epg_status", null).use { c ->
            buildList { while (c.moveToNext()) add(EpgStatus(c.getString(0), c.getString(1), c.getLong(2), c.getString(3), c.getInt(4))) }
        }

    fun clearEpg() {
        val db = writableDatabase
        db.delete("program", null, null)
        db.delete("epg_status", null, null)
        epgChanges.incrementAndGet()
    }

    fun pruneProgrammes(keepFrom: Long) {
        // Counted only when something went: the guide shows from about now on, but a row it holds may reach back.
        if (writableDatabase.delete("program", "stop < ?", arrayOf(keepFrom.toString())) > 0) epgChanges.incrementAndGet()
    }

    // ---- Guide reads ---------------------------------------------------------------------------------------------

    /** The groups the guide's column lists: shown ones, in the viewer's order (the provider's where none is set). */
    fun groups(playlistId: String): List<ChannelGroup> = groupsWhere(playlistId, "AND COALESCE(gp.hidden, 0) = 0")

    /** Every group, hidden ones too (Manage groups). */
    fun allGroups(playlistId: String): List<ChannelGroup> = groupsWhere(playlistId, "")

    private fun groupsWhere(playlistId: String, filter: String): List<ChannelGroup> =
        readableDatabase.rawQuery(
            "SELECT cg.id, cg.name, cg.position, COALESCE(gp.hidden, 0) FROM channel_group cg " +
                "LEFT JOIN group_pref gp ON gp.playlist_id = cg.playlist_id AND gp.group_id = cg.id " +
                "WHERE cg.playlist_id = ? $filter ORDER BY gp.user_position IS NULL, gp.user_position, cg.position",
            arrayOf(playlistId),
        ).use { c ->
            buildList { while (c.moveToNext()) add(ChannelGroup(playlistId, c.getString(0), c.getString(1), c.getInt(2), hidden = c.getInt(3) != 0)) }
        }

    fun setGroupHidden(playlistId: String, groupId: String, hidden: Boolean) {
        val db = writableDatabase
        val v = ContentValues().apply { put("hidden", if (hidden) 1 else 0) }
        if (db.update("group_pref", v, "playlist_id = ? AND group_id = ?", arrayOf(playlistId, groupId)) == 0) {
            v.put("playlist_id", playlistId); v.put("group_id", groupId)
            db.insertWithOnConflict("group_pref", null, v, SQLiteDatabase.CONFLICT_IGNORE)
        }
        listChanges.incrementAndGet()
    }

    /** Hide all / Show all: every group of the playlist, including ones the provider isn't listing just now. */
    fun setAllGroupsHidden(playlistId: String, hidden: Boolean) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("INSERT OR IGNORE INTO group_pref(playlist_id, group_id) SELECT playlist_id, id FROM channel_group WHERE playlist_id = ?", arrayOf(playlistId))
            db.update("group_pref", ContentValues().apply { put("hidden", if (hidden) 1 else 0) }, "playlist_id = ?", arrayOf(playlistId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        listChanges.incrementAndGet()
    }

    /** The viewer's order for [groupIds] (the shown groups, top first); groups not listed keep theirs. */
    fun setGroupOrder(playlistId: String, groupIds: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val set = db.compileStatement("UPDATE group_pref SET user_position = ? WHERE playlist_id = ? AND group_id = ?")
            val add = db.compileStatement("INSERT OR IGNORE INTO group_pref(playlist_id, group_id, user_position) VALUES (?, ?, ?)")
            groupIds.forEachIndexed { i, id ->
                set.clearBindings(); set.bindLong(1, i.toLong()); set.bindString(2, playlistId); set.bindString(3, id)
                if (set.executeUpdateDelete() == 0) {
                    add.clearBindings(); add.bindString(1, playlistId); add.bindString(2, id); add.bindLong(3, i.toLong()); add.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        listChanges.incrementAndGet()
    }

    /** Playlists in the viewer's order (top first). */
    fun setPlaylistOrder(ids: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEachIndexed { i, id -> db.update("playlist", ContentValues().apply { put("position", i) }, "id = ?", arrayOf(id)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        listChanges.incrementAndGet()
    }

    /**
     * The channels a guide scope lists, in guide order. Blocked channels are left out. [cancel]: the read is given up
     * when it's cancelled (the list is no longer wanted), with an OperationCanceledException.
     */
    fun channels(scope: GuideScope, cancel: CancellationSignal? = null): List<Channel> {
        // Every playlist: each one's channels in turn, in playlist order (read in index order, never sorted whole).
        if (scope == GuideScope.AllPlaylists) return playlists().flatMap { channels(GuideScope.AllChannels(it.id), cancel) }
        val (sql, args) = when (scope) {
            GuideScope.AllPlaylists -> error("handled above")
            is GuideScope.AllChannels ->
                // Channels of hidden groups are left out (Manage groups).
                (CHANNEL_SELECT + "WHERE c.playlist_id = ? AND $SHOWN ORDER BY c.position") to arrayOf(scope.playlistId, scope.playlistId)
            is GuideScope.Favorites ->
                // From the stars to their channels (CROSS JOIN keeps that order on every SQLite): a lookup by key for
                // each star. Joined from the channels, every channel of the playlist was looked up among the stars
                // (30,000 lookups for a list of 31, checked with EXPLAIN QUERY PLAN).
                ("SELECT $CHANNEL_COLS, 1, c.blocked FROM favorite f CROSS JOIN channel c ON c.playlist_id = f.playlist_id AND c.id = f.channel_id " +
                    "WHERE f.playlist_id = ? AND c.blocked = 0 ORDER BY f.added, c.position") to arrayOf(scope.playlistId)
            is GuideScope.Group ->
                (CHANNEL_SELECT + "WHERE c.playlist_id = ? AND c.group_id = ? AND c.blocked = 0 ORDER BY c.position") to arrayOf(scope.playlistId, scope.groupId)
        }
        return readableDatabase.rawQuery(sql, args, cancel).wholeWindow().use { c -> channelsOf(c, cancel) }
    }

    /**
     * The first screenful of a long list (All channels of a playlist, or of every playlist): the channels around
     * [around] when the list holds it, [before] of them before it and [after] from it on; otherwise (or with none
     * given) the list's first [after]. Read through the position index: a few dozen rows however long the list, in
     * the order and with the channels left out that [channels] gives. Any other scope is read whole.
     */
    fun channelsNear(scope: GuideScope, around: ChannelKey?, before: Int, after: Int): List<Channel> {
        val ids = when (scope) {
            GuideScope.AllPlaylists -> playlists().map { it.id }
            is GuideScope.AllChannels -> listOf(scope.playlistId)
            else -> return channels(scope)
        }
        val db = readableDatabase
        if (around != null && around.playlistId in ids) {
            val id = around.playlistId
            val at = db.rawQuery("SELECT c.position FROM channel c WHERE c.playlist_id = ? AND c.id = ? AND $SHOWN", arrayOf(id, around.channelId, id))
                .use { c -> if (c.moveToFirst()) c.getInt(0) else null }
            if (at != null) {
                val head = db.rawQuery(
                    CHANNEL_SELECT + "WHERE c.playlist_id = ? AND $SHOWN AND c.position < ? ORDER BY c.position DESC LIMIT $before",
                    arrayOf(id, id, at.toString()),
                ).use { c -> channelsOf(c, null) }
                return head.asReversed() + channelsFrom(id, at, after)
            }
        }
        // Not in the list: its start (the first playlist that has channels to show).
        for (id in ids) channelsFrom(id, Int.MIN_VALUE, after).let { if (it.isNotEmpty()) return it }
        return emptyList()
    }

    /** A playlist's All channels from [position] on, [limit] at most. */
    private fun channelsFrom(playlistId: String, position: Int, limit: Int): List<Channel> =
        readableDatabase.rawQuery(
            CHANNEL_SELECT + "WHERE c.playlist_id = ? AND $SHOWN AND c.position >= ? ORDER BY c.position LIMIT $limit",
            arrayOf(playlistId, playlistId, position.toString()),
        ).use { c -> channelsOf(c, null) }

    /**
     * The rows of [c] as channels. A playlist's id and a group's id are kept once for the whole list, not once a row:
     * tens of thousands of copies of the same two texts were megabytes of a big playlist's list.
     */
    private fun channelsOf(c: Cursor, cancel: CancellationSignal?): List<Channel> {
        val same = HashMap<String, String>()
        val out = ArrayList<Channel>(c.count)
        while (c.moveToNext()) {
            // Every few hundred rows: a list no longer wanted stops being read.
            if (cancel != null && (out.size and 0x1FF) == 0) cancel.throwIfCanceled()
            val playlist = c.getString(0).let { same.getOrPut(it) { it } }
            val group = c.getString(5)?.let { same.getOrPut(it) { it } }
            out += Channel(
                playlistId = playlist, id = c.getString(1), number = c.getInt(2), name = c.getString(3),
                logo = c.getString(4), groupId = group, url = c.getString(6), epgRef = c.getString(7),
                archiveDays = c.getInt(8), position = c.getInt(9), favorite = c.getInt(10) != 0, blocked = c.getInt(11) != 0,
            )
        }
        return out
    }

    /**
     * A long list in one pass: Android reads a query's rows into 2 MB windows and runs the query again from the top
     * for each further window, so a 32,000-channel list was read several times over. One big window instead (API 28+).
     */
    private fun Cursor.wholeWindow(): Cursor {
        if (Build.VERSION.SDK_INT >= 28 && this is AbstractWindowedCursor) window = CursorWindow("channels", 24L * 1024 * 1024)
        return this
    }

    fun channel(key: ChannelKey): Channel? =
        readableDatabase.rawQuery(CHANNEL_SELECT + "WHERE c.playlist_id = ? AND c.id = ?", arrayOf(key.playlistId, key.channelId))
            .use { c -> if (c.moveToFirst()) channelAt(c) else null }

    fun channelCount(playlistId: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM channel WHERE playlist_id = ?", arrayOf(playlistId)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /**
     * Programmes for [channels] overlapping [from, to), by channel key. A channel's own playlist's EPG comes first.
     * Only when its own playlist has no EPG at all does another playlist's EPG fill in, and then only for the exact
     * same XMLTV channel id: display names are never used to match (provisional fallback rule, see design-layout.md).
     */
    fun programmes(channels: List<Channel>, from: Long, to: Long): Map<ChannelKey, List<Programme>> {
        val out = HashMap<ChannelKey, List<Programme>>()
        if (channels.isEmpty()) return out
        val withEpg = sourcesWithEpg()
        val sourceOrder = playlists().map { it.id }
        val db = readableDatabase
        for (ch in channels) {
            val ref = ch.epgRef ?: continue
            val sources = withEpg[ch.playlistId]?.let { listOf(it) } ?: sourceOrder.mapNotNull { withEpg[it] }
            for (source in sources) {
                val list = db.rawQuery(
                    "SELECT start, stop, title, description, category FROM program WHERE source = ? AND epg_ref = ? AND stop > ? AND start < ? ORDER BY start",
                    arrayOf(source, ref, from.toString(), to.toString()),
                ).use { c -> buildList { while (c.moveToNext()) add(Programme(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4))) } }
                if (list.isNotEmpty()) {
                    out[ch.key] = list
                    break
                }
            }
        }
        return out
    }

    /** Sources with listings, each with the name its current generation's rows are stored under. */
    internal fun sourcesWithEpg(): Map<String, String> =
        readableDatabase.rawQuery("SELECT source, gen FROM epg_status WHERE programmes > 0", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), physical(c.getString(0), c.getInt(1))) }
        }

    // ---- The viewer's own data ---------------------------------------------------------------------------------

    fun setFavorite(key: ChannelKey, favorite: Boolean, now: Long) {
        try {
            if (favorite) {
                val v = ContentValues().apply { put("playlist_id", key.playlistId); put("channel_id", key.channelId); put("added", now) }
                writableDatabase.insertWithOnConflict("favorite", null, v, SQLiteDatabase.CONFLICT_IGNORE)
            } else {
                writableDatabase.delete("favorite", "playlist_id = ? AND channel_id = ?", arrayOf(key.playlistId, key.channelId))
            }
        } finally {
            // Counted even when it couldn't be stored: the star was shown at once, so a list on screen may not be
            // what's stored, and is read again rather than taken as unchanged.
            listChanges.incrementAndGet()
        }
    }

    /**
     * How many favorites each of a playlist's groups holds (Assign favorites), by group id. Hidden groups count too
     * (their channels stay favorites); channels without a group aren't in any.
     */
    fun favoriteCounts(playlistId: String): Map<String, Int> =
        readableDatabase.rawQuery(
            "SELECT c.group_id, COUNT(*) FROM favorite f JOIN channel c ON c.playlist_id = f.playlist_id AND c.id = f.channel_id " +
                "WHERE f.playlist_id = ? AND c.blocked = 0 AND c.group_id IS NOT NULL GROUP BY c.group_id",
            arrayOf(playlistId),
        ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) } }

    /** Notes a channel as watched; only the last [RECENT_KEEP] are kept. */
    fun noteRecent(key: ChannelKey, now: Long) {
        val db = writableDatabase
        val v = ContentValues().apply { put("playlist_id", key.playlistId); put("channel_id", key.channelId); put("at", now) }
        db.insertWithOnConflict("recent", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL(
            "DELETE FROM recent WHERE (playlist_id || '/' || channel_id) NOT IN " +
                "(SELECT playlist_id || '/' || channel_id FROM recent ORDER BY at DESC LIMIT $RECENT_KEEP)",
        )
    }

    /** The most recently watched channels, newest first. */
    fun recent(): List<Channel> {
        val keys = readableDatabase.rawQuery("SELECT playlist_id, channel_id FROM recent ORDER BY at DESC LIMIT $RECENT_KEEP", null).use { c ->
            buildList { while (c.moveToNext()) add(ChannelKey(c.getString(0), c.getString(1))) }
        }
        return keys.mapNotNull { channel(it) }
    }

    private fun bindOrNull(s: android.database.sqlite.SQLiteStatement, i: Int, v: String?) {
        if (v == null) s.bindNull(i) else s.bindString(i, v)
    }

    private fun playlistAt(c: Cursor) = Playlist(
        id = c.getString(0), name = c.getString(1), kind = PlaylistKind.of(c.getString(2)), url = c.getString(3),
        username = c.getString(4), password = c.getString(5), epgUrl = c.getString(6), output = c.getString(7),
        includeLive = c.getInt(8) != 0, includeVod = c.getInt(9) != 0, position = c.getInt(10), updateHours = c.getInt(11),
        lastSync = c.getLong(12), lastStatus = c.getString(13), expiresAt = c.getLong(14), maxConnections = c.getInt(15),
        hideNewGroups = c.getInt(16) != 0,
    )

    private fun channelAt(c: Cursor) = Channel(
        playlistId = c.getString(0), id = c.getString(1), number = c.getInt(2), name = c.getString(3),
        logo = c.getString(4), groupId = c.getString(5), url = c.getString(6), epgRef = c.getString(7),
        archiveDays = c.getInt(8), position = c.getInt(9), favorite = c.getInt(10) != 0, blocked = c.getInt(11) != 0,
    )

    companion object {
        private const val VERSION = 6
        private const val TAG = "FirePlayerLive"
        /** Channel History lists the last ten besides the one playing, which is the newest here. */
        const val RECENT_KEEP = 11
        private const val STAGING = "#new"
        private const val BATCH = 2_000
        private const val CHANNEL_COLS = "c.playlist_id, c.id, c.number, c.name, c.logo, c.group_id, c.url, c.epg_ref, c.archive_days, c.position"
        /** A channel with its star; each list adds what it holds and its order. */
        private const val CHANNEL_SELECT = "SELECT $CHANNEL_COLS, f.channel_id IS NOT NULL, c.blocked FROM channel c " +
            "LEFT JOIN favorite f ON f.playlist_id = c.playlist_id AND f.channel_id = c.id "
        /** What a playlist's All channels shows: not blocked, nor in a hidden group (Manage groups). Takes the playlist's id. */
        private const val SHOWN = "c.blocked = 0 AND (c.group_id IS NULL OR c.group_id NOT IN " +
            "(SELECT group_id FROM group_pref WHERE playlist_id = ? AND hidden = 1))"
        private const val PLAYLIST_COLS = "id, name, kind, url, username, password, epg_url, output, include_live, include_vod, " +
            "position, update_hours, last_sync, last_status, expires_at, max_connections, hide_new_groups"

        @Volatile private var instance: LiveDb? = null

        /**
         * The viewer's own changes (a star, a group hidden or moved), stored one after another whichever screen made
         * them (the guide, the settings windows): a read queued here after them (Assign favorites opening) sees every
         * one, including those another screen set a moment before. Never shut down: a screen going away mustn't drop
         * what another queued.
         */
        val writes: java.util.concurrent.ExecutorService by lazy {
            java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "FirePlayerLiveWrites") }
        }

        fun get(context: Context): LiveDb =
            instance ?: synchronized(this) { instance ?: LiveDb(context.applicationContext).also { instance = it } }
    }
}
