package app.fireplayer.vod.data

import app.fireplayer.vod.Kind
import app.fireplayer.vod.Title
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/** Where [IdMap] keeps its answers (vod.db on the TV). Reads may come from any thread; writes come one at a time. */
interface IdStore {
    /** The IMDb id ("" when TMDB had none) and when it was learnt (ms); null when never asked. */
    fun idFor(kind: Kind, tmdbId: Int): Pair<String, Long>?

    fun putIds(kind: Kind, entries: List<Triple<Int, String, Long>>)
}

/** An [IdStore] in memory only, for tests and for a TV whose database can't be opened. */
class MemoryIdStore : IdStore {
    private val map = ConcurrentHashMap<String, Pair<String, Long>>()
    override fun idFor(kind: Kind, tmdbId: Int) = map["${kind.type}|$tmdbId"]
    override fun putIds(kind: Kind, entries: List<Triple<Int, String, Long>>) {
        entries.forEach { (tmdb, imdb, at) -> map["${kind.type}|$tmdb"] = imdb to at }
    }
    val size: Int get() = map.size
}

/**
 * TMDB id → IMDb id, per kind (TMDB's film and TV ids are separate numbers). Hits are kept [HIT_KEPT_MS] (TMDB's
 * 6-month limit on what's kept from it; Cinemeta's pages teach them again), a miss (TMDB had no IMDb id) only
 * [MISS_KEPT_MS], since new titles often get theirs a few days later; older rows leave vod.db ([VodDb]). Cinemeta's
 * pages teach it pairs for nothing ([learn]); TMDB answers fill in the rest. Reads go through a memory copy, which also
 * remembers ids never asked about (no store read each time); writes go to [writer].
 */
class IdMap(private val store: IdStore, private val clock: () -> Long, private val writer: Executor) {

    private val memory = ConcurrentHashMap<String, Pair<String, Long>>()

    fun known(kind: Kind, tmdbId: Int): TmdbTrending.Known {
        val key = "${kind.type}|$tmdbId"
        val entry = memory[key] ?: run {
            val read = runCatching { store.idFor(kind, tmdbId) }.getOrNull() ?: NONE
            // Something learnt meanwhile wins (ConcurrentHashMap's own putIfAbsent: on every API level).
            memory.putIfAbsent(key, read) ?: read
        }
        if (entry === NONE) return TmdbTrending.Known.Unknown
        val (imdb, at) = entry
        val age = clock() - at
        if (imdb.isNotEmpty()) return if (age < HIT_KEPT_MS) TmdbTrending.Known.Hit(imdb) else TmdbTrending.Known.Unknown
        return if (age in 0 until MISS_KEPT_MS) TmdbTrending.Known.Miss else TmdbTrending.Known.Unknown
    }

    fun found(kind: Kind, tmdbId: Int, imdb: String) = put(kind, listOf(tmdbId to imdb))

    fun missed(kind: Kind, tmdbId: Int) = put(kind, listOf(tmdbId to ""))

    /**
     * The pairs Cinemeta's titles carry (`moviedb_id`), after its junk filters. Only new or changed ones are written,
     * and ones past [HIT_KEPT_MS] (learnt again).
     */
    fun learn(titles: List<Title>) {
        val now = clock()
        for (kind in Kind.entries) {
            val pairs = titles.filter { it.kind == kind && it.tmdbId != null && it.id.startsWith("tt") }
                .map { it.tmdbId!! to it.id }
                .filter { (tmdb, imdb) ->
                    val had = memory["${kind.type}|$tmdb"]
                    had == null || had.first != imdb || now - had.second >= HIT_KEPT_MS
                }
            if (pairs.isNotEmpty()) put(kind, pairs)
        }
    }

    private fun put(kind: Kind, pairs: List<Pair<Int, String>>) {
        val at = clock()
        val entries = pairs.map { (tmdb, imdb) -> Triple(tmdb, imdb, at) }
        entries.forEach { (tmdb, imdb, t) -> memory["${kind.type}|$tmdb"] = imdb to t }
        writer.execute { runCatching { store.putIds(kind, entries) } }
    }

    companion object {
        const val MISS_KEPT_MS = 2 * 24 * 3600_000L
        /** A hit is used this long after it was learnt (nothing from TMDB is kept past 6 months). */
        const val HIT_KEPT_MS = 180 * 24 * 3600_000L

        /** In memory only: the store had nothing (asked once, not again until something is learnt). */
        private val NONE = "" to Long.MIN_VALUE
    }
}
