package app.fireplayer.live.ui

import app.fireplayer.live.ChannelGroup
import java.util.concurrent.Executor

/**
 * A list the viewer puts in order (Reorder groups, Reorder playlists): OK picks an entry up (it says "Moving"),
 * Up and Down carry it, OK puts it down; Move to top sends one straight to the top. Kept apart from the window so
 * it can be tested.
 */
class Reorder<T>(items: List<T>) {
    val items: MutableList<T> = items.toMutableList()

    /** The entry picked up, or -1. */
    var moving = -1
        private set

    /** OK on entry [i]: picks it up, or puts it down if it's the one moving. Returns the one moving before (-1: none). */
    fun press(i: Int): Int {
        val before = moving
        moving = if (moving == i) -1 else i
        return before
    }

    /** Carries the entry being moved [step] places (Up -1, Down +1). Returns where it is now; -1 when it can't go. */
    fun carry(step: Int): Int {
        val from = moving
        val to = from + step
        if (from !in items.indices || to !in items.indices || step == 0) return -1
        items.add(to, items.removeAt(from))
        moving = to
        return to
    }

    /** Sends entry [i] to the top, putting down whatever was moving. Returns the one moving before (-1: none). */
    fun toTop(i: Int): Int {
        val before = moving
        moving = -1
        if (i in 1 until items.size) items.add(0, items.removeAt(i))
        return before
    }

    /** What entry [i]'s row says on the right: "Moving" while it's carried, else its place. */
    fun value(i: Int): String = if (moving == i) "Moving" else "${i + 1}"
}

/** Assign favorites' arithmetic: which groups it lists, in which order, and how many favorites each holds. */
object FavoriteGroups {

    /**
     * The groups as Assign favorites lists them: the shown ones in the guide's order, then the hidden ones (their
     * channels can still be favorites, and the Favorites list shows them).
     */
    fun order(groups: List<ChannelGroup>): List<ChannelGroup> = groups.filter { !it.hidden } + groups.filter { it.hidden }

    /** A group's row value: how many favorites it holds; nothing while it holds none, so the ones that do stand out. */
    fun countText(n: Int): String? = when {
        n <= 0 -> null
        n == 1 -> "1 favorite"
        else -> "$n favorites"
    }

    /**
     * Where Assign favorites puts its headings among the groups ([order]): Groups over the [shown] ones, Hidden groups
     * over the rest of the [total], each only when it has groups under it.
     */
    class Headings(shown: Int, total: Int) {
        /** The Groups heading's place, or -1. */
        val shownHeading = if (shown > 0) 0 else -1
        /** The Hidden groups heading's place, or -1. */
        val hiddenHeading = if (shown < total) shown + (if (shown > 0) 1 else 0) else -1
        /** Entries: the groups and their headings. */
        val size = total + (if (shownHeading >= 0) 1 else 0) + (if (hiddenHeading >= 0) 1 else 0)

        /** The group at entry [index], as its place in [order], or null for a heading (or nothing there). */
        fun groupAt(index: Int): Int? {
            if (index !in 0 until size || index == shownHeading || index == hiddenHeading) return null
            val before = (if (shownHeading in 0 until index) 1 else 0) + (if (hiddenHeading in 0 until index) 1 else 0)
            return index - before
        }
    }

    /** [counts] after one of [groupId]'s channels was starred ([favorite]) or not. */
    fun adjust(counts: MutableMap<String, Int>, groupId: String?, favorite: Boolean) {
        if (groupId == null) return
        val n = (counts[groupId] ?: 0) + if (favorite) 1 else -1
        if (n > 0) counts[groupId] = n else counts.remove(groupId)
    }
}

/**
 * Stores only the newest of values that come quickly one after another: while one is waiting to be stored, a newer
 * one takes its place. A group carried 30 places with the D-pad held stores its order a few times, not 30, yet every
 * move is stored as it happens. [executor] must run one task at a time (so the newest is always stored last).
 */
class LatestSave<T : Any>(private val executor: Executor, private val store: (T) -> Unit) {
    private val lock = Any()
    private var waiting: T? = null
    private var queued = false

    fun submit(value: T) {
        synchronized(lock) {
            waiting = value
            if (queued) return
            queued = true
        }
        executor.execute {
            val next = synchronized(lock) {
                queued = false
                waiting.also { waiting = null }
            }
            if (next != null) store(next)
        }
    }
}
