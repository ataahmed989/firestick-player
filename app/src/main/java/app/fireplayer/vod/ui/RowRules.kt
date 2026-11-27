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

    /**
     * The cards a row shows of its source's answer, in its order: the first [ROW_CARDS] (See all has the rest). Home's
     * Continue Watching row is its first ones, and so is Glass Home's row (history/VodContinue.rows).
     */
    fun <T> shown(answer: List<T>): List<T> = answer.take(ROW_CARDS)

    /** A row that goes on takes this many more titles at a time ([pages]), up to [ROW_MAX] cards. */
    const val ROW_MORE = 15
    const val ROW_MAX = 60
    /** More are asked for with the viewer this close to the row's last card. */
    const val MORE_AHEAD = 5

    /**
     * Whether a row of [source] goes on: it takes more titles as the viewer nears its end (a studio page's rows: a
     * chosen list, or TMDB Discover page after page).
     */
    fun pages(source: Source): Boolean = source is Source.Curated || (source is Source.Discover && source.paged)

    /** Whether the viewer on card [index] of [size] is near enough to the end for more to be asked for. */
    fun nearEnd(index: Int, size: Int): Boolean = size > 0 && index >= size - MORE_AHEAD

    /**
     * Whether a row's first answer of [total] titles (of the [ROW_CARDS] + 1 asked for) was all its source has: none
     * at all, or a chosen list that came up short (Discover drops titles from a full page, so it can't tell).
     */
    fun endsAt(source: Source, total: Int): Boolean = total <= 0 || (source is Source.Curated && total <= ROW_CARDS)

    /** A row's cards with [more] after them: a title already shown isn't repeated; never past [max]. */
    fun more(shown: List<Card>, more: List<Card>, max: Int = ROW_MAX): List<Card> =
        (shown + more).distinctBy { it.title.kind to it.title.id }.take(max)

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
        // A service's row is its first page only; a studio page's goes on, and ends with See all (its whole grid).
        source is Source.Discover -> source.paged
        // A chosen list shows whole in its row.
        source is Source.Curated -> false
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

    /**
     * Whether an answer for a row of [source] waits until the viewer leaves the row: one that would move what they're on
     * ([keepsFocus]). Never a library row's ([isLibrary]): it's what is stored on this TV, shown as it is at once, the
     * viewer kept on their title ([followTitle]). Held, Home back from a film started from its Continue card (focus
     * still on that card) kept the row as it was at the film's start, until the viewer left the row.
     */
    fun holdsAnswer(source: Source, at: Int, old: List<Card>, oldSeeAll: Boolean, new: List<Card>, newSeeAll: Boolean): Boolean =
        !isLibrary(source) && !keepsFocus(at, old, oldSeeAll, new, newSeeAll)

    /**
     * A library row taking [new] (and See all after them when [newSeeAll]) with the viewer on item [at] of [old]: the
     * item that keeps them on the same title, wherever it is now (back from a film started from its Continue card:
     * first); on See all, See all while there still is one, else the last card; the title gone, the same place (the last
     * card at most, as [focusAfter]). -1 when they aren't in the row, or nothing is left.
     */
    fun followTitle(at: Int, old: List<Card>, oldSeeAll: Boolean, new: List<Card>, newSeeAll: Boolean): Int {
        if (at < 0 || new.isEmpty()) return -1
        if (oldSeeAll && at == old.size) return if (newSeeAll) new.size else new.size - 1
        val title = old.getOrNull(at)?.title
        val i = if (title == null) -1 else new.indexOfFirst { it.title.kind == title.kind && it.title.id == title.id }
        return if (i >= 0) i else at.coerceAtMost(new.size - 1)
    }

    /**
     * The item of a library row whose title stays where the viewer is through an answer ([followTitle]): the one focus
     * is on ([focused]), else the card the row was moved along to ([chosen], the viewer elsewhere on the page); -1 for a
     * row at its start, which stays there (the latest played first).
     */
    fun followFrom(focused: Int, chosen: Int): Int = if (focused >= 0) focused else if (chosen > 0) chosen else -1

    /**
     * The card of [cards] (the Continue row read again) for the title the banner follows ([followed]), at its new
     * episode and time left; else its card in another Continue row on the page ([others]: a Home with an added
     * "Continue Watching · Series" beside its own, which a film the banner follows was never in); null when no Continue
     * row has the title any more (the banner then goes back to its feed).
     */
    fun refollow(followed: Card, cards: List<Card>, others: List<List<Card>> = emptyList()): Card? =
        cardOf(followed, cards) ?: others.firstNotNullOfOrNull { cardOf(followed, it) }

    private fun cardOf(followed: Card, cards: List<Card>): Card? =
        cards.firstOrNull { it.title.kind == followed.title.kind && it.title.id == followed.title.id }

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

    /**
     * A Continue Watching card [shown] after its title's record changed in place ([now]: the card as the row would load
     * it now): its progress, caption and episode from [now], the rest as shown, so the card is redrawn, not rebuilt.
     */
    fun continueChanged(shown: Card, now: Card): Card =
        shown.copy(progress = now.progress, caption = now.caption, episode = now.episode)

    /** "S2 E5 · 12m left · 2014 · Drama · Crime · ★ 8.4 · 49 min": the header's facts line, the parts that exist. */
    fun facts(title: Title, caption: String?): String = listOfNotNull(
        caption?.takeIf { it.isNotBlank() },
        title.year?.takeIf { it.isNotBlank() },
        *title.genres.take(2).toTypedArray(),
        title.imdbRating?.takeIf { it.isNotBlank() }?.let { "★ $it" },
        title.runtime?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
}
