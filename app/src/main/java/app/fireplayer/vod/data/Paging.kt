package app.fireplayer.vod.data

import app.fireplayer.vod.Title

/**
 * Maps a request for cards ([skip], limit) onto Cinemeta's pages. A page covers [Cinemeta.PAGE] positions from its base
 * (0, 50, 100…), its titles taken as positions base + 0, base + 1… (a page often holds a few less than 50, so a title
 * can sit a little earlier than its true position; stepping skip by the limit asked for, in multiples of 50, never
 * leaves one out). Titles come back in order, each id once.
 */
object Paging {

    /** The bases of the pages covering positions [skip] until [skip] + [limit]. */
    fun bases(skip: Int, limit: Int): List<Int> {
        if (limit <= 0) return emptyList()
        val from = skip.coerceAtLeast(0)
        val first = from / Cinemeta.PAGE * Cinemeta.PAGE
        val last = (from + limit - 1) / Cinemeta.PAGE * Cinemeta.PAGE
        return (first..last step Cinemeta.PAGE).toList()
    }

    /**
     * The titles at positions [skip] until [skip] + [limit] from [pages] (one per base, null when unknown). Nothing is
     * taken after a page that says it is the last.
     */
    fun slice(bases: List<Int>, pages: List<Cinemeta.Page?>, skip: Int, limit: Int): List<Title> {
        val from = skip.coerceAtLeast(0)
        val until = from.toLong() + limit
        val out = ArrayList<Title>()
        val seen = HashSet<String>()
        for ((i, base) in bases.withIndex()) {
            val page = pages.getOrNull(i) ?: continue
            page.titles.forEachIndexed { k, t ->
                val at = base + k
                if (at >= from && at < until && seen.add(t.id)) out += t
            }
            if (!page.hasMore) break
        }
        return out
    }

    /** Whether [pages] answer for every base: each is stored and fresh, or comes after the last page. */
    fun <T> allFresh(pages: List<T?>, isFresh: (T) -> Boolean, isLast: (T) -> Boolean): Boolean {
        for (p in pages) {
            if (p == null || !isFresh(p)) return false
            if (isLast(p)) return true
        }
        return true
    }
}
