package app.fireplayer.live

import android.os.CancellationSignal
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Live TV search: every playlist's channels by name and the guide's programmes (on now or later) by title. Channels of
 * hidden groups, blocked channels and channels of blocked groups are left out (as the guide leaves them out). Runs on
 * [worker], never on the main thread; [find] stops early when [CancellationSignal] is cancelled (the words changed).
 *
 * Reads only, on the database's read connections (write-ahead log): the guide's own reads and the EPG import never wait
 * for it. Programmes are looked up through the programme table's key, one EPG source at a time, now and later only,
 * and each read stops at [READ_CAP] matches.
 */
class LiveSearch(private val db: LiveDb) {

    class ChannelHit(
        val key: ChannelKey,
        val name: String,
        val logo: String?,
        val groupId: String?,
        var favorite: Boolean,
    )

    class ProgrammeHit(val channel: ChannelHit, val title: String, val start: Long, val stop: Long)

    class Result(val query: String, val channels: List<ChannelHit>, val programmes: List<ProgrammeHit>) {
        val isEmpty: Boolean get() = channels.isEmpty() && programmes.isEmpty()
    }

    /**
     * [early], when given, is handed the channels as soon as they are found (a quick, indexed-size read), before the
     * guide's programmes are looked through (a slow read on a large guide): the page shows them at once.
     */
    /**
     * Every channel's name with its key, read once for a visit to Live TV search ([names]): finding a name in memory
     * takes a few milliseconds where the database takes most of a second (a name can match anywhere, so no index
     * helps it). Three short strings a channel; let go with the page.
     */
    class Names(val playlists: Array<String>, val ids: Array<String>, /** In lower case. */ val names: Array<String>)

    /** Reads [Names] (worker thread); null when there are too many channels to hold ([NAMES_CAP]): the database is asked. */
    fun names(): Names? {
        val playlists = ArrayList<String>()
        val ids = ArrayList<String>()
        val names = ArrayList<String>()
        val same = HashMap<String, String>()
        db.readableDatabase.rawQuery("SELECT playlist_id, id, name FROM channel", null).use { c ->
            while (c.moveToNext()) {
                if (names.size >= NAMES_CAP) return null
                val p = c.getString(0)
                playlists += same.getOrPut(p) { p }
                ids += c.getString(1)
                // Lower case once, here: each search then only looks for its own lower-cased words (ten times faster
                // than comparing without regard to case, name by name).
                names += c.getString(2).lowercase()
            }
        }
        return Names(playlists.toTypedArray(), ids.toTypedArray(), names.toTypedArray())
    }

    /** The channels whose name holds [q], found in [names] and read (with the rules of what shows) by their keys. */
    private fun channelsIn(names: Names, q: String, like: String, cancel: CancellationSignal): List<ChannelHit> {
        val byPlaylist = LinkedHashMap<String, ArrayList<String>>()
        var found = 0
        val words = q.lowercase()
        for (i in names.names.indices) {
            if (!names.names[i].contains(words)) continue
            byPlaylist.getOrPut(names.playlists[i]) { ArrayList() } += names.ids[i]
            if (++found >= READ_CAP) break
        }
        if (found == 0) return emptyList()
        val read = db.readableDatabase
        val rows = ArrayList<Triple<Long, Long, ChannelHit>>()
        for ((playlist, ids) in byPlaylist) {
            for (chunk in ids.chunked(IN_CHUNK)) {
                cancel.throwIfCanceled()
                val marks = chunk.joinToString(",") { "?" }
                // The name is checked again here: the list in memory may be older than a playlist update.
                read.rawQuery(
                    CHANNEL_SELECT + "WHERE c.playlist_id = ? AND c.id IN ($marks) AND c.name LIKE ? ESCAPE '\\' AND $VISIBLE",
                    arrayOf(playlist) + chunk.toTypedArray() + like, cancel,
                ).use { c -> while (c.moveToNext()) rows += Triple(c.getLong(7), c.getLong(8), channelAt(c)) }
            }
        }
        rows.sortWith(compareBy({ it.first }, { it.second }))
        return rows.map { it.third }
    }

    fun find(query: String, now: Long, cancel: CancellationSignal, early: ((Result) -> Unit)? = null, names: Names? = null): Result {
        val q = query.trim()
        if (q.isEmpty()) return Result(query, emptyList(), emptyList())
        val like = LiveSearchRules.likePattern(q)
        val read = db.readableDatabase
        val t0 = android.os.SystemClock.elapsedRealtime()
        val channels = if (names != null) channelsIn(names, q, like, cancel) else read.rawQuery(
            CHANNEL_SELECT + "WHERE c.name LIKE ? ESCAPE '\\' AND $VISIBLE ORDER BY p.position, c.position LIMIT $READ_CAP",
            arrayOf(like), cancel,
        ).use { c -> buildList { while (c.moveToNext()) add(channelAt(c)) } }
        val orderedChannels = LiveSearchRules.orderChannels(channels, q) { it.name }.take(MAX_CHANNELS)
        if (orderedChannels.isNotEmpty()) early?.invoke(Result(query, orderedChannels, emptyList()))
        val t1 = android.os.SystemClock.elapsedRealtime()

        // Programmes: per source (the playlist whose EPG listed them), the matches still to come.
        val sources = db.sourcesWithEpg()
        val found = ArrayList<Triple<String, String, Programme>>()
        for ((source, physical) in sources) {
            cancel.throwIfCanceled()
            read.rawQuery(
                "SELECT epg_ref, start, stop, title FROM program WHERE source = ? AND stop > ? AND title LIKE ? ESCAPE '\\' LIMIT $READ_CAP",
                arrayOf(physical, now.toString(), like), cancel,
            ).use { c -> while (c.moveToNext()) found += Triple(source, c.getString(0), Programme(c.getLong(1), c.getLong(2), c.getString(3), null, null)) }
        }
        val programmes = ArrayList<ProgrammeHit>()
        if (found.isNotEmpty()) {
            val refs = found.mapTo(LinkedHashSet()) { it.second }.toList()
            val byRef = HashMap<String, MutableList<Pair<ChannelHit, String>>>()
            for (chunk in refs.chunked(IN_CHUNK)) {
                cancel.throwIfCanceled()
                val marks = chunk.joinToString(",") { "?" }
                read.rawQuery(
                    CHANNEL_SELECT + "WHERE c.epg_ref IN ($marks) AND $VISIBLE ORDER BY p.position, c.position",
                    chunk.toTypedArray(), cancel,
                ).use { c -> while (c.moveToNext()) byRef.getOrPut(c.getString(5)) { ArrayList() } += channelAt(c) to c.getString(0) }
            }
            for ((source, ref, p) in found) {
                for ((ch, playlistId) in byRef[ref].orEmpty()) {
                    // A channel's listings come from its own playlist's EPG, or any EPG when its playlist has none.
                    if (LiveSearchRules.listsFor(playlistId, source, playlistId in sources)) {
                        programmes += ProgrammeHit(ch, p.title, p.start, p.stop)
                    }
                }
            }
        }
        // How long each half took, for tuning on the TV (never the words searched).
        android.util.Log.i("FirePlayerLive", "Live search: ${channels.size} channels in ${t1 - t0} ms, ${programmes.size} programmes in ${android.os.SystemClock.elapsedRealtime() - t1} ms")
        return Result(
            query,
            orderedChannels,
            LiveSearchRules.orderProgrammes(LiveSearchRules.distinct(programmes), q, now).take(MAX_PROGRAMMES),
        )
    }

    private fun channelAt(c: android.database.Cursor) = ChannelHit(
        key = ChannelKey(c.getString(0), c.getString(1)),
        name = c.getString(2),
        logo = if (c.isNull(3)) null else c.getString(3),
        groupId = if (c.isNull(4)) null else c.getString(4),
        favorite = c.getInt(6) != 0,
    )

    companion object {
        /** Results kept: channels in the row, programmes in the list. */
        const val MAX_CHANNELS = 30
        const val MAX_PROGRAMMES = 30
        /** Matches read from one query at most (before ranking). */
        private const val READ_CAP = 400
        private const val IN_CHUNK = 500
        /** The most channel names held in memory for a search; with more, the database is asked each time. */
        private const val NAMES_CAP = 200_000

        private const val CHANNEL_SELECT =
            "SELECT c.playlist_id, c.id, c.name, c.logo, c.group_id, c.epg_ref, f.channel_id IS NOT NULL, p.position, c.position FROM channel c " +
                "JOIN playlist p ON p.id = c.playlist_id " +
                "LEFT JOIN favorite f ON f.playlist_id = c.playlist_id AND f.channel_id = c.id "

        /** Not blocked, nor in a hidden or blocked group (parental rules; Manage groups). */
        private const val VISIBLE =
            "c.blocked = 0 AND (c.group_id IS NULL OR (" +
                "NOT EXISTS (SELECT 1 FROM group_pref gp WHERE gp.playlist_id = c.playlist_id AND gp.group_id = c.group_id AND gp.hidden = 1) " +
                "AND NOT EXISTS (SELECT 1 FROM channel_group g WHERE g.playlist_id = c.playlist_id AND g.id = c.group_id AND g.blocked = 1)))"

        /** One thread for every Live TV search (a new one waits for the cancelled one to stop). Never shut down. */
        val worker: ExecutorService by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "FirePlayerLiveSearch") } }
    }
}

/** Live TV search's matching and order, pure (unit-tested). */
object LiveSearchRules {

    /** How well [name] matches [query]: 0 the same, 1 starts with it, 2 a word starts with it, 3 holds it, -1 not. */
    fun rank(name: String, query: String): Int {
        val n = name.lowercase()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return -1
        return when {
            n == q -> 0
            n.startsWith(q) -> 1
            wordStarts(n, q) -> 2
            n.contains(q) -> 3
            else -> -1
        }
    }

    private fun wordStarts(n: String, q: String): Boolean {
        var i = n.indexOf(q, 1)
        while (i > 0) {
            if (!n[i - 1].isLetterOrDigit()) return true
            i = n.indexOf(q, i + 1)
        }
        return false
    }

    /** [query] for SQL's LIKE, anywhere in the text: its own % _ and \ taken as they are. */
    fun likePattern(query: String): String {
        val b = StringBuilder("%")
        for (ch in query.trim()) {
            if (ch == '%' || ch == '_' || ch == '\\') b.append('\\')
            b.append(ch)
        }
        return b.append('%').toString()
    }

    /** Best match first; the same rank keeps the order given (playlist order, then the guide's). */
    fun <T> orderChannels(list: List<T>, query: String, nameOf: (T) -> String): List<T> =
        list.map { it to rank(nameOf(it), query) }.filter { it.second >= 0 }.sortedBy { it.second }.map { it.first }

    fun onNow(start: Long, stop: Long, now: Long): Boolean = now in start until stop

    /** On now first, then the best title match, then the soonest; the same keeps the order given. */
    fun orderProgrammes(list: List<LiveSearch.ProgrammeHit>, query: String, now: Long): List<LiveSearch.ProgrammeHit> =
        order(list, query, now, { it.title }, { it.start }, { it.stop })

    fun <T> order(list: List<T>, query: String, now: Long, titleOf: (T) -> String, startOf: (T) -> Long, stopOf: (T) -> Long): List<T> =
        list.map { Triple(it, rank(titleOf(it), query), onNow(startOf(it), stopOf(it), now)) }
            .filter { it.second >= 0 }
            .sortedWith(compareBy<Triple<T, Int, Boolean>>({ !it.third }, { it.second }, { startOf(it.first) }))
            .map { it.first }

    /** One programme per channel and start (two EPG sources may list the same one). */
    fun distinct(list: List<LiveSearch.ProgrammeHit>): List<LiveSearch.ProgrammeHit> {
        val seen = HashSet<String>()
        return list.filter { seen.add("${it.channel.key}|${it.start}") }
    }

    /** Whether a channel of [playlistId] takes its listings from [source]: its own EPG, or any when it has none. */
    fun listsFor(playlistId: String, source: String, playlistHasEpg: Boolean): Boolean =
        playlistId == source || !playlistHasEpg
}
