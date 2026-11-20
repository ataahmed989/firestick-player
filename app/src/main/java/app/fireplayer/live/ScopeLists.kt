package app.fireplayer.live

/**
 * The guide's channel lists kept in memory while the Live TV page lives, so a list already read isn't read again:
 * going back to a group looked at a moment ago, the playing channel's group for Channel ▲ / ▼ when the guide shows the
 * same one, coming back to the tab. Each list is kept with the count of list writes it was read at ([version],
 * [LiveDb.listChanges]); asked for at another count, nothing kept is any good and it all goes.
 *
 * Bounded, because a big playlist's list is megabytes: the last [smallKept] lists of up to [smallMax] channels (fewer
 * when they hold more than [smallTotal] between them), and the last one longer than that. Pure (no Android), any thread.
 */
class ScopeLists<K : Any, T>(
    private val smallMax: Int = SMALL_MAX,
    private val smallKept: Int = SMALL_KEPT,
    private val smallTotal: Int = SMALL_TOTAL,
) {
    private var version = 0
    /** Lists of up to [smallMax] entries, the one used last at the end. */
    private val small = LinkedHashMap<K, List<T>>(16, 0.75f, true)
    private var bigKey: K? = null
    private var big: List<T>? = null

    /** [key]'s list as read at [version], or null when it isn't kept (or was read at another count). */
    @Synchronized
    fun get(key: K, version: Int): List<T>? {
        if (version != this.version) return null
        return if (key == bigKey) big else small[key]
    }

    /** Keeps [list], read at [version], for [key]. */
    @Synchronized
    fun put(key: K, version: Int, list: List<T>) {
        if (version != this.version) {
            clear()
            this.version = version
        }
        if (key == bigKey) { bigKey = null; big = null }
        small.remove(key)
        if (list.size > smallMax) {
            bigKey = key
            big = list
            return
        }
        small[key] = list
        // The ones used longest ago go first; never the one just kept (the last).
        var total = small.values.sumOf { it.size }
        val oldest = small.entries.iterator()
        while ((small.size > smallKept || total > smallTotal) && small.size > 1) {
            total -= oldest.next().value.size
            oldest.remove()
        }
    }

    @Synchronized
    fun clear() {
        small.clear()
        bigKey = null
        big = null
    }

    /** How many lists are kept (for tests). */
    @get:Synchronized
    val size: Int get() = small.size + if (big != null) 1 else 0

    companion object {
        /** A group or a favorites list; longer than this is a playlist's whole list, of which one is kept. */
        const val SMALL_MAX = 2_000
        const val SMALL_KEPT = 8
        const val SMALL_TOTAL = 6_000
    }
}
