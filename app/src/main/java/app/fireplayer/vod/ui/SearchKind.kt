package app.fireplayer.vod.ui

import android.view.KeyEvent
import app.fireplayer.live.LiveSearchRules

/**
 * The Search page's two kinds, switched by the two pills above its bar: films and shows (Cinemeta, as the page has
 * always searched) and Live TV (every playlist's channels and the guide's programmes). Each keeps its own history
 * ([historyKey], in the history's own file) and its own last words while the page shows.
 */
enum class SearchKind(val label: String, val hint: String, val historyKey: String) {
    VOD("Movies & TV Shows", "Search films and shows", "history"),
    LIVE("Live TV", "Search channels and programmes", "history_live"),
}

/** The Search page's rules, pure (unit-tested). */
object SearchRules {

    /** What Back does on the Search page. */
    enum class Back {
        /** Results or words in the bar: back to the empty bar and the history (focus on the bar). */
        CLEAR,
        /** The starting view of Live TV search: back to the Live TV tab. */
        LIVE_TV,
        /** The starting view of films and shows: the rail (the shell's own Back). */
        RAIL,
    }

    fun back(kind: SearchKind, showsSomething: Boolean): Back = when {
        showsSomething -> Back.CLEAR
        kind == SearchKind.LIVE -> Back.LIVE_TV
        else -> Back.RAIL
    }

    /** Left or Right on a kind's pill: the pill it goes to, or null (none that way). */
    fun pillMove(from: SearchKind, keyCode: Int): SearchKind? {
        val i = from.ordinal + when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> return null
        }
        return SearchKind.entries.getOrNull(i)
    }

    /**
     * Movies & TV Shows search: whether the TV Shows row goes above the Movies row, decided once both have answered.
     * The row whose best title matches the words searched best comes first, by the app's one name ranking
     * ([LiveSearchRules.rank]: the same name, starts with it, a word starts with it, holds it); the same rank: the one
     * whose best title sits earlier in its row; still the same (or neither matches): Movies first.
     */
    fun showsFirst(movies: List<String>, shows: List<String>, query: String): Boolean {
        val m = bestMatch(movies, query)
        val s = bestMatch(shows, query)
        return s < m
    }

    /**
     * A row's best match as one number to compare (lower is better): its best rank first, then that title's place in
     * the row; [Long.MAX_VALUE] when nothing in it matches.
     */
    fun bestMatch(names: List<String>, query: String): Long {
        var best = Long.MAX_VALUE
        names.forEachIndexed { i, name ->
            val r = LiveSearchRules.rank(name, query)
            if (r >= 0) {
                val v = r.toLong() * PLACES + i
                if (v < best) best = v
            }
        }
        return best
    }

    /** More places than any row has (a row asks for 16 at most). */
    private const val PLACES = 1_000_000L

    /**
     * Each kind's last words while the page shows: kept when the other kind is chosen and given back when this one
     * is chosen again; all forgotten when the page hides (Search opens on its starting view).
     */
    class LastWords {
        private val words = HashMap<SearchKind, String>()

        fun keep(kind: SearchKind, text: String) {
            words[kind] = text
        }

        fun of(kind: SearchKind): String = words[kind].orEmpty()

        fun clear() = words.clear()
    }
}
