package app.fireplayer.live.ui

import app.fireplayer.live.GuideScope

/**
 * The channel list over full screen and Multiview ([LiveOverlay]), as rules (pure, unit-tested): which columns show at
 * each step, where Left, Right, Back and OK go, what the groups column lists, where Multiview's ready picture sits and
 * how its search page starts (the user's answers, Design-References/tivimate-2026-11-20/REQUEST.md).
 *
 * Steps, from the picture outwards: [Level.CLOSED], [Level.CHANNELS] (the group's channels, the selection in them; in
 * full screen the focused channel's programmes beside them), [Level.GROUPS] (every playlist with its groups, as the
 * guide's groups column lists them, the selection in them, the focused group's channels beside them).
 */
object OverlayRules {

    enum class Level { CLOSED, CHANNELS, GROUPS }

    /** OK on a channel: it plays (the list stays), the list closes (it was playing already), or it's picked (Multiview). */
    enum class Ok { PLAY, CLOSE, PICK }

    /**
     * Where the list opens: full screen's Left, on the playing channel's group; picking for Multiview, on the groups
     * with that group's channels beside them (the user: "the groups-and-channels list opens on the left").
     */
    fun opening(picking: Boolean): Level = if (picking) Level.GROUPS else Level.CHANNELS

    /** Left: from the picture to the channels, from the channels to the groups; nothing further. */
    fun left(level: Level): Level = if (level == Level.CLOSED) Level.CHANNELS else Level.GROUPS

    /**
     * Right, back towards the picture: from the groups to the channels; from the channels the list closes in full
     * screen, and stays when picking (Back cancels a pick; the pictures to the right aren't somewhere to go).
     */
    fun right(level: Level, picking: Boolean): Level = when (level) {
        Level.GROUPS -> Level.CHANNELS
        Level.CHANNELS -> if (picking) Level.CHANNELS else Level.CLOSED
        Level.CLOSED -> Level.CLOSED
    }

    /** Back closes the whole list, whichever step it's at. */
    @Suppress("UNUSED_PARAMETER")
    fun back(level: Level): Level = Level.CLOSED

    /** OK on a group (or Right on it): its channels, the selection in them. */
    fun okOnGroup(): Level = Level.CHANNELS

    /** OK on a channel: [isPlaying] is it playing now without a fault (a second OK on it closes the list). */
    fun okOnChannel(picking: Boolean, isPlaying: Boolean): Ok = when {
        picking -> Ok.PICK
        isPlaying -> Ok.CLOSE
        else -> Ok.PLAY
    }

    /** The groups column shows at [Level.GROUPS] only. */
    fun showsGroups(level: Level): Boolean = level == Level.GROUPS

    /** The channels column shows whenever the list is open (beside the groups, or alone at the edge). */
    fun showsChannels(level: Level): Boolean = level != Level.CLOSED

    /**
     * The focused channel's programmes and details show beside the channels in full screen only: picking, Multiview's
     * pictures are there.
     */
    fun showsProgrammes(level: Level, picking: Boolean): Boolean = level == Level.CHANNELS && !picking

    /** Where the channels column stands: at the screen's edge, or beside the groups column ([groupsW] wide). */
    fun channelsX(level: Level, groupsW: Float): Float = if (level == Level.GROUPS) groupsW else 0f

    /** What a row of the groups column is: Search, All playlists, a playlist's heading (open or folded), or a list under one. */
    enum class Row { SEARCH, ALL, HEAD_OPEN, HEAD_FOLDED, ENTRY }

    /**
     * A row of the groups column: its words, the list it shows ([scope]; none for Search and a playlist's heading) and,
     * for a heading, its playlist ([playlistId]).
     */
    class Entry(val label: String, val scope: GuideScope?, val row: Row, val playlistId: String? = null) {
        val isSearch: Boolean get() = row == Row.SEARCH
        val isHead: Boolean get() = row == Row.HEAD_OPEN || row == Row.HEAD_FOLDED
    }

    /**
     * The groups column: the guide's own list ([GroupColumn.items]: All playlists, every playlist as a heading with its
     * Favorites, All channels and groups under it while it's open), row for row; picking for Multiview, "Search" at
     * its top.
     */
    fun entries(picking: Boolean, items: List<GroupColumn.Item>): List<Entry> {
        val out = ArrayList<Entry>(items.size + 1)
        if (picking) out.add(Entry(SEARCH, null, Row.SEARCH))
        for (item in items) {
            val label = GroupColumn.labelOf(item)
            out.add(
                when (item) {
                    GroupColumn.Item.All -> Entry(label, GuideScope.AllPlaylists, Row.ALL)
                    is GroupColumn.Item.Head -> Entry(label, null, if (item.expanded) Row.HEAD_OPEN else Row.HEAD_FOLDED, item.playlist.id)
                    is GroupColumn.Item.Entry -> Entry(label, item.scope, Row.ENTRY)
                },
            )
        }
        return out
    }

    /**
     * The row the groups' selection opens on: [scope]'s; else its playlist's heading (the group isn't listed: hidden,
     * or its playlist folded); else the first that isn't Search; -1 when there's none.
     */
    fun rowOf(entries: List<Entry>, scope: GuideScope?): Int {
        if (scope != null) {
            val i = entries.indexOfFirst { it.scope == scope }
            if (i >= 0) return i
            val pl = GroupColumn.playlistOf(scope)
            val h = if (pl == null) -1 else entries.indexOfFirst { it.isHead && it.playlistId == pl }
            if (h >= 0) return h
        }
        return entries.indexOfFirst { !it.isSearch }
    }

    /** The row drawn blue: [scope]'s own, the group whose channels show (-1: not listed). */
    fun markOf(entries: List<Entry>, scope: GuideScope?): Int = if (scope == null) -1 else entries.indexOfFirst { it.scope == scope }

    /** The playlist's heading drawn blue with it: the one holding [scope] (the playlist in use, as the guide marks it). */
    fun headMarkOf(entries: List<Entry>, scope: GuideScope?): Int {
        val pl = scope?.let { GroupColumn.playlistOf(it) } ?: return -1
        return entries.indexOfFirst { it.isHead && it.playlistId == pl }
    }

    /** What OK does on a row of the groups: the search page, the playlist opens or folds, or the row's channels. */
    enum class GroupOk { SEARCH, FOLD, ENTER }

    fun okOnGroupRow(entry: Entry): GroupOk = when {
        entry.isSearch -> GroupOk.SEARCH
        entry.isHead -> GroupOk.FOLD
        else -> GroupOk.ENTER
    }

    /**
     * Right on a row of the groups, as in the guide's column: a folded playlist opens (the selection stays on it);
     * from any other row, to the channels.
     */
    fun rightFolds(entry: Entry?): Boolean = entry?.row == Row.HEAD_FOLDED

    /** Left on a row of the groups, as in the guide's column: an open playlist folds; else nothing. */
    fun leftFolds(entry: Entry?): Boolean = entry?.row == Row.HEAD_OPEN

    /** The groups column's heading: an only playlist's name, else "Playlists" (it lists them all). */
    fun groupsHeader(names: List<String>): String = names.singleOrNull() ?: PLAYLISTS

    /** The channel row a list opens on: [wanted]'s, else the first; -1 for an empty list. */
    fun <T, K> focusIn(list: List<T>, wanted: K?, keyOf: (T) -> K): Int {
        if (list.isEmpty()) return -1
        if (wanted == null) return 0
        val i = list.indexOfFirst { keyOf(it) == wanted }
        return if (i >= 0) i else 0
    }

    /**
     * The row Up (or Down) goes to from [from] of [size]: the one beside it; round at the ends on a fresh press, held
     * it stops there (the guide's rule). [from] itself when it stays.
     */
    fun step(from: Int, size: Int, down: Boolean, held: Boolean): Int {
        if (size <= 0) return -1
        val to = from + if (down) 1 else -1
        return when {
            to in 0 until size -> to
            held || size == 1 -> from
            down -> 0
            else -> size - 1
        }
    }

    /** How much of a programme from [start] to [stop] has gone at [now], 0 to 1. */
    fun progress(start: Long, stop: Long, now: Long): Float =
        if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)

    /** The minutes left of a programme ending at [stop] at [now] (rounded up; never under 0). */
    fun minutesLeft(stop: Long, now: Long): Long = if (stop <= now) 0 else (stop - now + 59_999) / 60_000

    /** The programme on at [now] among [count] in time order ([startAt], [stopAt]); else the next one; -1 when all ended. */
    fun onNow(count: Int, now: Long, startAt: (Int) -> Long, stopAt: (Int) -> Long): Int {
        for (i in 0 until count) if (now < stopAt(i) && startAt(i) < stopAt(i)) return i
        return -1
    }

    // ---- Multiview's start ------------------------------------------------------------------------------------------

    /**
     * Where the playing picture goes when Multiview starts (ready for a second): the miniature's size at the screen's
     * top right, [gap] in from its edges (dp, a [screenW] x [screenH] screen). Left, top, right, bottom.
     */
    fun readyPicture(screenW: Float, screenH: Float, gap: Float): FloatArray {
        val w = minOf(LiveWidths.MINI.toFloat(), screenW - 2 * gap)
        val h = minOf(LiveWidths.MINI_H.toFloat(), screenH - 2 * gap)
        return floatArrayOf(screenW - gap - w, gap, screenW - gap, gap + h)
    }

    /** Where the selection starts on the search page: the channel history under the bar, else the bar. */
    enum class SearchStart { HISTORY, BAR }

    /**
     * The search page with nothing typed shows the channel history under the bar, the selection on it; the bar has to
     * be pressed (OK) to type. With no history (or words already there), the bar, still without the keyboard.
     */
    fun searchStart(words: String, history: Int): SearchStart =
        if (words.isBlank() && history > 0) SearchStart.HISTORY else SearchStart.BAR

    /**
     * The channel history under the search page's bar: the channels watched last ([recent], newest first) without
     * those on screen already ([shown]: Multiview's pictures, or the one playing at its start), at most [size].
     */
    fun <T, K> history(recent: List<T>, shown: Set<K>, size: Int, keyOf: (T) -> K): List<T> {
        val out = ArrayList<T>(minOf(size, recent.size).coerceAtLeast(0))
        for (c in recent) {
            if (out.size >= size) break
            if (keyOf(c) !in shown) out.add(c)
        }
        return out
    }

    /** Whether the history shows under the bar for these words (none typed). */
    fun showsHistory(words: String): Boolean = words.isBlank()

    /** The keyboard never comes up by itself: OK on the bar brings it. */
    const val KEYBOARD_ON_OPEN = false

    const val SEARCH = "Search"
    const val PLAYLISTS = "Playlists"
}
