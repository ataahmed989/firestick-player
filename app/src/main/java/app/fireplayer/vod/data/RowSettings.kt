package app.fireplayer.vod.data

import app.fireplayer.vod.Kind
import app.fireplayer.vod.Page
import app.fireplayer.vod.RowSpec
import app.fireplayer.vod.RowStyle
import app.fireplayer.vod.Source
import java.util.EnumMap
import java.util.concurrent.CopyOnWriteArrayList

/** Where Settings › Rows keeps each page's rows: the app's prefs (in memory in tests). Main thread. */
interface RowLayoutStore {
    fun read(page: Page): String?

    /** Null: the page is back to its default rows. */
    fun write(page: Page, text: String?)

    class Memory : RowLayoutStore {
        val saved = EnumMap<Page, String>(Page::class.java)
        override fun read(page: Page): String? = saved[page]
        override fun write(page: Page, text: String?) {
            if (text == null) saved.remove(page) else saved[page] = text
        }
    }
}

/** A page's row as Settings › Rows lists it: switched on or off, and whether the viewer added it (only those go). */
data class RowEntry(val spec: RowSpec, val on: Boolean, val added: Boolean)

/** A row Settings › Rows offers to add: its key on the page, its label there, and the heading it's listed under. */
data class RowChoice(val key: String, val label: String, val group: String)

/**
 * Settings › Rows' rules, apart from the screen so they can be tested. A page's rows are its default rows
 * ([PageRows]) plus rows the viewer added, in the viewer's order, each on or off. Added rows are the same kinds the
 * pages already have (same [RowSpec], [RowStyle] and source, so they look and move the same), and their key says
 * which: "<page>.add.<what>". Their labels and New in's year are worked out each time, so they stay current.
 *
 * Stored per page as one line per row, in order: "+key" (on) or "-key" (off). Nothing stored: the defaults, all on.
 */
object RowLayouts {

    const val ADDED = ".add."

    /** "home", "movies", "shows": the prefix of the page's row keys. */
    fun prefix(page: Page): String = when (page) {
        Page.HOME -> "home"
        Page.MOVIES -> "movies"
        Page.SHOWS -> "shows"
    }

    /** The kind a tab page is about; null for Home. */
    fun kindOf(page: Page): Kind? = when (page) {
        Page.HOME -> null
        Page.MOVIES -> Kind.MOVIE
        Page.SHOWS -> Kind.SERIES
    }

    fun encode(layout: List<Pair<String, Boolean>>): String =
        layout.joinToString("\n") { (key, on) -> (if (on) "+" else "-") + key }

    fun decode(text: String?): List<Pair<String, Boolean>>? {
        if (text == null) return null
        return text.split('\n').mapNotNull { line ->
            if (line.length < 2) return@mapNotNull null
            when (line[0]) {
                '+' -> line.substring(1) to true
                '-' -> line.substring(1) to false
                else -> null
            }
        }
    }

    /**
     * [page]'s rows from what was [stored]: its order, repeats and rows that no longer exist left out, the same source
     * only once. Default rows missing from it (new in a later version) come back on, after the default row before them.
     */
    fun entries(page: Page, defaults: List<RowSpec>, stored: List<Pair<String, Boolean>>?, newYear: (Kind) -> Int): List<RowEntry> {
        if (stored == null) return defaults.map { RowEntry(it, on = true, added = false) }
        val byKey = defaults.associateBy { it.key }
        val out = ArrayList<RowEntry>()
        val keys = HashSet<String>()
        val sources = HashSet<Source>()
        for ((key, on) in stored) {
            val default = byKey[key]
            val spec = default ?: addedSpec(page, key, newYear) ?: continue
            // A repeat, or a row showing what an earlier one does (that one stays; a default row comes back below).
            if (key in keys || spec.source in sources) continue
            keys += key
            sources += spec.source
            out += RowEntry(spec, on, added = default == null)
        }
        for ((i, spec) in defaults.withIndex()) {
            if (spec.key in keys) continue
            // An added row with the same source makes way: the default row is the one kept.
            out.removeAll { it.added && it.spec.source == spec.source }
            val before = defaults.getOrNull(i - 1)?.key
            val at = if (before == null) 0 else out.indexOfFirst { it.spec.key == before } + 1
            out.add(at.coerceIn(0, out.size), RowEntry(spec, on = true, added = false))
            keys += spec.key
        }
        return out
    }

    /** What to store for [entries]. */
    fun layoutOf(entries: List<RowEntry>): List<Pair<String, Boolean>> = entries.map { it.spec.key to it.on }

    /** The row an added key stands for on [page], or null when it isn't one. */
    fun addedSpec(page: Page, key: String, newYear: (Kind) -> Int): RowSpec? {
        val head = prefix(page) + ADDED
        if (!key.startsWith(head)) return null
        val what = key.substring(head.length)
        val parts = what.split('.', limit = 3)
        val kind = parts.getOrNull(1)?.let(::kindNamed)
        val both = parts.getOrNull(1) == BOTH
        fun row(label: String, style: RowStyle, source: Source, continueRow: Boolean = false) =
            RowSpec(key, label, style, source, continueRow)
        return when (parts[0]) {
            "my_list" -> if (parts.size == 1) row("My List", RowStyle.POSTER, Source.MyList) else null
            "next_episodes" -> if (parts.size == 1) row("Next Episodes", RowStyle.POSTER, Source.NextEpisodes()) else null
            "popular" -> kind?.takeIf { parts.size == 2 }?.let { row("Popular" + suffix(page, it), RowStyle.POSTER, Source.Catalog(it, Cinemeta.TOP)) }
            "featured" -> kind?.takeIf { parts.size == 2 }?.let { row("Featured" + suffix(page, it), RowStyle.POSTER, Source.Catalog(it, Cinemeta.FEATURED)) }
            "new" -> kind?.takeIf { parts.size == 2 }?.let {
                val year = newYear(it)
                row("New in $year" + suffix(page, it), RowStyle.POSTER, Source.Catalog(it, Cinemeta.YEAR, year.toString()))
            }
            "genres" -> kind?.takeIf { parts.size == 2 }?.let { row("Genres" + suffix(page, it), RowStyle.GENRES, Source.Genres(it)) }
            "genre" -> {
                val genre = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
                if (kind == null || genre == null) null
                else row(genre + suffix(page, kind), RowStyle.POSTER, Source.Catalog(kind, Cinemeta.TOP, genre))
            }
            "top10" -> if (parts.size != 2 || (kind == null && !both)) null
                else row("Top 10 This Week" + suffix(page, kind), RowStyle.RANKED, Source.Trending(kind))
            "continue" -> if (parts.size != 2 || (kind == null && !both)) null
                else row("Continue Watching" + suffix(page, kind), RowStyle.WIDE, Source.ContinueWatching(kind), continueRow = true)
            else -> null
        }
    }

    /** The key of an added row: [what] is "popular.movie", "genre.series.Drama", "top10.both", "my_list"... */
    fun addedKey(page: Page, what: String): String = prefix(page) + ADDED + what

    /**
     * The rows [page] can add now (not [entries]' sources again), under their headings: films, series, then both.
     * Top 10 This Week only with a TMDB key. Genre rows are picked from Cinemeta's list ([genreChoices]).
     */
    fun choices(page: Page, entries: List<RowEntry>, hasTmdbKey: Boolean, newYear: (Kind) -> Int): List<RowChoice> {
        val have = entries.map { it.spec.source }.toHashSet()
        val out = ArrayList<RowChoice>()
        fun offer(what: String, group: String) {
            val spec = addedSpec(page, addedKey(page, what), newYear) ?: return
            if (spec.source !in have) out += RowChoice(spec.key, spec.label, group)
        }
        for (kind in Kind.entries) {
            val k = nameOf(kind)
            val group = groupOf(kind)
            offer("popular.$k", group)
            offer("new.$k", group)
            offer("featured.$k", group)
            if (hasTmdbKey) offer("top10.$k", group)
            offer("continue.$k", group)
            if (kind == Kind.SERIES) offer("next_episodes", group)
            offer("genres.$k", group)
        }
        if (hasTmdbKey) offer("top10.$BOTH", BOTH_GROUP)
        offer("continue.$BOTH", BOTH_GROUP)
        offer("my_list", BOTH_GROUP)
        return out
    }

    /** A genre row for each of Cinemeta's [genres] of [kind] that [page] hasn't got. */
    fun genreChoices(page: Page, kind: Kind, genres: List<String>, entries: List<RowEntry>, newYear: (Kind) -> Int): List<RowChoice> {
        val have = entries.map { it.spec.source }.toHashSet()
        return genres.distinct().filter { it.isNotBlank() && '\n' !in it }.mapNotNull { genre ->
            val spec = addedSpec(page, addedKey(page, "genre.${nameOf(kind)}.$genre"), newYear) ?: return@mapNotNull null
            if (spec.source in have) null else RowChoice(spec.key, spec.label, groupOf(kind))
        }
    }

    /** " · Movies" after an added row's name, unless the page is that kind's own tab ("Both" on a tab page). */
    fun suffix(page: Page, kind: Kind?): String {
        val pageKind = kindOf(page)
        return when {
            kind == null -> if (pageKind == null) "" else " · Movies & Series"
            kind == pageKind -> ""
            else -> " · " + groupOf(kind)
        }
    }

    fun groupOf(kind: Kind): String = if (kind == Kind.MOVIE) "Movies" else "Series"

    fun nameOf(kind: Kind): String = if (kind == Kind.MOVIE) "movie" else "series"

    private fun kindNamed(name: String): Kind? = Kind.entries.firstOrNull { nameOf(it) == name }

    const val BOTH = "both"
    const val BOTH_GROUP = "Movies & Series"
}

/**
 * Settings › Rows for the pages: each page's rows as the viewer set them, stored as they change ([RowLayoutStore]) and
 * told to the pages ([addListener]) so they rebuild their rows. Main thread.
 */
class RowSettings(private val store: RowLayoutStore, private val newYear: (Kind) -> Int) {

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val read = EnumMap<Page, List<Pair<String, Boolean>>?>(Page::class.java)

    fun defaults(page: Page): List<RowSpec> = PageRows.rows(page, newYear(RowLayouts.kindOf(page) ?: Kind.MOVIE))

    /** Every row of [page] in order, on or off. */
    fun entries(page: Page): List<RowEntry> = RowLayouts.entries(page, defaults(page), stored(page), newYear)

    /** The rows [page] shows, in order. */
    fun rows(page: Page): List<RowSpec> = entries(page).filter { it.on }.map { it.spec }

    /** Whether [page] has its default rows (Reset to default has nothing to do). */
    fun isDefault(page: Page): Boolean =
        RowLayouts.layoutOf(entries(page)) == defaults(page).map { it.key to true }

    fun setOn(page: Page, key: String, on: Boolean) =
        save(page, entries(page).map { if (it.spec.key == key) it.copy(on = on) else it })

    /** The rows in the order of [keys] (any missing keep their place after them). */
    fun setOrder(page: Page, keys: List<String>) {
        val all = entries(page)
        val byKey = all.associateBy { it.spec.key }
        val ordered = keys.distinct().mapNotNull { byKey[it] }
        save(page, ordered + all.filter { it !in ordered })
    }

    /** Adds the row [key] stands for at the end of [page], on. False when it isn't one or the page has it already. */
    fun add(page: Page, key: String): Boolean {
        val spec = RowLayouts.addedSpec(page, key, newYear) ?: return false
        val all = entries(page)
        if (all.any { it.spec.key == key || it.spec.source == spec.source }) return false
        save(page, all + RowEntry(spec, on = true, added = true))
        return true
    }

    /** Takes an added row off [page] (default rows are only switched off). */
    fun remove(page: Page, key: String) {
        val all = entries(page)
        if (all.none { it.spec.key == key && it.added }) return
        save(page, all.filter { it.spec.key != key })
    }

    fun reset(page: Page) {
        read[page] = null
        store.write(page, null)
        changed()
    }

    fun choices(page: Page, hasTmdbKey: Boolean): List<RowChoice> =
        RowLayouts.choices(page, entries(page), hasTmdbKey, newYear)

    fun genreChoices(page: Page, kind: Kind, genres: List<String>): List<RowChoice> =
        RowLayouts.genreChoices(page, kind, genres, entries(page), newYear)

    /** Once per listener, however often it's added. */
    fun addListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun stored(page: Page): List<Pair<String, Boolean>>? {
        if (read.containsKey(page)) return read[page]
        return RowLayouts.decode(store.read(page)).also { read[page] = it }
    }

    private fun save(page: Page, entries: List<RowEntry>) {
        val layout = RowLayouts.layoutOf(entries)
        if (layout == stored(page)) return
        read[page] = layout
        store.write(page, RowLayouts.encode(layout))
        changed()
    }

    private fun changed() = listeners.forEach { it() }
}
