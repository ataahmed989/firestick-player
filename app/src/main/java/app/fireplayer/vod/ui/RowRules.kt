package app.fireplayer.vod.ui

import app.fireplayer.vod.Card
import app.fireplayer.vod.Source
import app.fireplayer.vod.Title

/**
 * What a row does with an answer from the repository (pure, unit-tested). The repository answers twice at most:
 * what's cached (possibly empty), then fresh data if the cache was old or missing; a failure with nothing cached is one
 * empty fresh answer. The library's rows (My List, Continue Watching, Next Episodes) are on this TV, so every answer
 * of theirs is final.
 */
internal object RowRules {

    /** Cards shown in a row; one more is asked for, to know whether there are more ([seeAll]). */
    const val ROW_CARDS = 15

    fun isLibrary(source: Source): Boolean =
        isMyList(source) || source is Source.ContinueWatching || source is Source.NextEpisodes

    /** My List, or its films or series alone (the My List tab's rows). */
    fun isMyList(source: Source): Boolean = source is Source.MyList || source is Source.MyListOf

    /** Whether [title], added to My List, goes into a row of [source]: My List's, or its own kind's. */
    fun myListTakes(source: Source, title: Title): Boolean =
        source is Source.MyList || (source is Source.MyListOf && source.kind == title.kind)

    /** Whether an answer can be acted on: not an empty cached one that fresh data will follow. */
    fun isFinal(source: Source, empty: Boolean, fresh: Boolean): Boolean = !empty || fresh || isLibrary(source)

    /**
     * Whether a row ends with a See all card: Cinemeta catalogs always (they go on and on), Top 10 never (it has only
     * 10), the library's rows and search results only past 15 ([total] is how many came back of the 16 asked for).
     */
    fun seeAll(source: Source, total: Int): Boolean = when {
        total <= 0 -> false
        source is Source.Catalog -> true
        source is Source.Trending -> false
        else -> total > ROW_CARDS
    }

    /**
     * With the viewer on item [at] of a row now showing [old] (and See all after them when [oldSeeAll]): whether
     * showing [new] (and See all when [newSeeAll]) leaves them on the same thing. If not, the new cards wait until
     * they leave the row.
     */
    fun keepsFocus(at: Int, old: List<Card>, oldSeeAll: Boolean, new: List<Card>, newSeeAll: Boolean): Boolean {
        if (at < 0 || new.isEmpty()) return true
        val onSeeAll = oldSeeAll && at == old.size
        if (onSeeAll) return newSeeAll
        return old.getOrNull(at)?.title?.id == new.getOrNull(at)?.title?.id
    }

    /** Where focus goes when a row's cards change under it: -1 stays put, else the item to move to first. */
    fun focusAfter(at: Int, oldSize: Int, oldSeeAll: Boolean, newSize: Int, newSeeAll: Boolean): Int {
        if (at < 0 || newSize == 0) return -1
        val onSeeAll = oldSeeAll && at == oldSize
        return when {
            onSeeAll && !newSeeAll -> newSize - 1
            !onSeeAll && at >= newSize -> newSize - 1
            else -> -1
        }
    }

    /** My List after adding or removing [title]: it goes first when added. */
    fun myListAfter(cards: List<Card>, title: Title, inList: Boolean): List<Card> {
        val others = cards.filter { it.title.id != title.id }
        return if (inList) listOf(Card(title)) + others else others
    }

    /** "S2 E5 · 12m left · 2014 · Drama · Crime · ★ 8.4 · 49 min": the header's facts line, the parts that exist. */
    fun facts(title: Title, caption: String?): String = listOfNotNull(
        caption?.takeIf { it.isNotBlank() },
        title.year?.takeIf { it.isNotBlank() },
        *title.genres.take(2).toTypedArray(),
        title.imdbRating?.takeIf { it.isNotBlank() }?.let { "★ $it" },
        title.runtime?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
}
