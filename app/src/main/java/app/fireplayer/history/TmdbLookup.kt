package app.fireplayer.history

/**
 * TMDB ids for Continue Watching's films and shows that were noted without one: the title server hadn't matched them
 * with TMDB (a name already in English isn't renamed, nor one not checked yet), or never listed them.
 * [ContinueWatching] then asks TMDB itself, on a thread of its own, by the clean name and the year.
 *
 * Each film or show is asked about once while FirePlayer runs, not every minute it plays: what TMDB answered stands,
 * also when it wasn't sure; only one that couldn't be asked (no network, TMDB busy) is asked again, [RETRY_MS] later.
 * Rows without an id are each taken to be looked up once ([toLookUp]); one TMDB couldn't be asked about is given back
 * for a later read ([fill]).
 */
class TmdbLookup(private val clock: () -> Long) {

    // What TMDB said of each film or show (0: not sure of any), and when one it couldn't be asked about may be asked
    // again. Only on the thread that asks.
    private val answers = HashMap<String, Int>()
    private val retryAt = HashMap<String, Long>()
    // Rows already taken to be looked up. From Glass Home's reads and the notes of what was watched, on any thread.
    private val taken = HashSet<String>()

    /**
     * The TMDB id [ask] gives for a film or show ([year] 0 when not known, asked without one): the match TMDB is sure
     * of, or null when it isn't sure of any. 0 when there's none; null when it couldn't be asked, just now or less than
     * [RETRY_MS] ago (a failure never goes further than here). Blocks while [ask] does.
     */
    fun id(name: String, year: Int, show: Boolean, ask: (name: String, year: Int?, show: Boolean) -> Int?): Int? {
        val question = "${if (show) "tv" else "movie"}|$name|$year"
        answers[question]?.let { return it }
        val now = clock()
        retryAt[question]?.let { if (now < it) return null }
        return try {
            (ask(name, year.takeIf { it > 0 }, show)?.takeIf { it > 0 } ?: 0).also {
                answers[question] = it
                retryAt.remove(question)
            }
        } catch (e: Exception) {
            retryAt[question] = now + RETRY_MS
            null
        }
    }

    /**
     * Looks up rows taken ([toLookUp]) in turn: [idOf] gives each one's id as [id] does, and [found] keeps one that
     * has one. Stops at the first TMDB couldn't be asked about, as the rest likely can't be either and would each wait
     * out a timeout. That one and those after it (all not reached, also when something fails) are given back, so a
     * later read takes them again.
     */
    fun fill(rows: List<WatchHistory.Row>, idOf: (WatchHistory.Row) -> Int?, found: (WatchHistory.Row, Int) -> Unit) {
        var done = 0
        try {
            for (row in rows) {
                val id = idOf(row) ?: return
                if (id > 0) found(row, id)
                done++
            }
        } finally {
            lookUpAgain(rows.drop(done))
        }
    }

    /** Of these rows, those without a TMDB id that weren't taken before: each is taken once. */
    fun toLookUp(rows: List<WatchHistory.Row>): List<WatchHistory.Row> =
        synchronized(taken) { rows.filter { it.tmdbId == 0 && taken.add(it.id) } }

    /** Rows that couldn't be looked up after all (no TMDB key yet, TMDB not reached): taken again on a later read. */
    fun lookUpAgain(rows: List<WatchHistory.Row>) {
        synchronized(taken) { taken.removeAll(rows.map { it.id }.toSet()) }
    }

    companion object {
        /** After a failure, as the title server waits to ask TMDB again about a title it couldn't. */
        const val RETRY_MS = 10 * 60_000L
    }
}
