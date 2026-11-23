package app.fireplayer.vod.ui

import app.fireplayer.vod.Cancel
import app.fireplayer.vod.Card
import app.fireplayer.vod.RowSpec
import app.fireplayer.vod.VodRepository

/**
 * See all's titles, page by page (pure, unit-tested with a fake repository): it starts with the row's own cards, then
 * asks for page 0, 1, 2… of [spec] ([PAGE] titles each: Cinemeta's skip is a position in its list, so it moves on by
 * the page asked for, not by what a page held). Each page's answer (cached, then fresh) replaces that page's part;
 * repeats are dropped by kind and id, keeping the first. It ends at an empty final page, or at [cap] titles.
 * [onChange] gets the whole list after every change. Main thread (the repository answers there).
 */
internal class GridFeed(
    private val repo: VodRepository,
    val spec: RowSpec,
    first: List<Card>,
    private val cap: Int = CAP,
    private val onChange: (List<Card>) -> Unit,
) {
    private val pages = ArrayList<List<Card>?>().apply { add(first) }
    private val cancels = ArrayList<Cancel>()
    private var closed = false

    /** Waiting for a page's first useful answer. */
    var loading = false
        private set

    /** No more pages. */
    var ended = false
        private set

    var cards: List<Card> = merge()
        private set

    /** Pages asked for so far. */
    var asked = 0
        private set

    /** Asks for the next page, unless one is on its way or the list has ended. */
    fun loadNext() {
        if (closed || loading || ended) return
        val page = asked++
        while (pages.size <= page) pages += null
        loading = true
        val library = RowRules.isLibrary(spec.source)
        cancels += repo.load(spec, skip = page * PAGE, limit = PAGE) { list, fresh ->
            if (closed) return@load
            if (!RowRules.isFinal(spec.source, list.isEmpty(), fresh)) return@load
            loading = false
            if (list.isEmpty()) {
                // The end; what shows stays (a first page that failed keeps the row's own cards).
                ended = true
                return@load
            }
            pages[page] = list
            // The library answers in full: a short page is its last.
            if (library && list.size < PAGE) ended = true
            update()
        }
    }

    /** A title left the source (taken off My List, out of Continue Watching): the grid shows the others. */
    fun remove(id: String) {
        var changed = false
        for (i in pages.indices) {
            val p = pages[i] ?: continue
            if (p.any { it.title.id == id }) {
                pages[i] = p.filter { it.title.id != id }
                changed = true
            }
        }
        if (changed) update()
    }

    /** A title's card changed in place (Mark unwatched: back to 0:00): [change] makes the new one from the one shown. */
    fun replace(id: String, change: (Card) -> Card) {
        var changed = false
        for (i in pages.indices) {
            val p = pages[i] ?: continue
            if (p.none { it.title.id == id }) continue
            pages[i] = p.map { if (it.title.id == id) change(it) else it }
            changed = true
        }
        if (changed) update()
    }

    fun close() {
        closed = true
        for (c in cancels) c.cancel()
        cancels.clear()
    }

    private fun update() {
        val merged = merge()
        if (merged == cards) return
        cards = merged
        onChange(merged)
    }

    private fun merge(): List<Card> {
        val out = ArrayList<Card>()
        val seen = HashSet<String>()
        for (p in pages) for (c in p.orEmpty()) {
            if (out.size >= cap) {
                ended = true
                return out
            }
            if (seen.add("${c.title.kind}:${c.title.id}")) out += c
        }
        return out
    }

    companion object {
        /** Cinemeta's page size. */
        const val PAGE = 50
        /**
         * At most this many titles in one grid: each is a view of its own, kept while the grid is open (300: 50 rows
         * of 6, about a third of the memory and layout work of 1,000 on a 2 GB Stick).
         */
        const val CAP = 300
    }
}
