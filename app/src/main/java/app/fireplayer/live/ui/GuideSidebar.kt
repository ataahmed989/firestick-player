package app.fireplayer.live.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.fireplayer.R
import app.fireplayer.live.ChannelGroup
import app.fireplayer.live.GuideScope
import app.fireplayer.live.Playlist
import app.fireplayer.live.ui.GroupColumn.Item
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.ListMath

/**
 * The playlist and group column (design-layout): "All playlists", then each playlist, which expands to Favorites,
 * All channels and its own groups. Playlist scope stays visible: a group is always shown under its playlist, and the
 * entry the guide shows and the playlist holding it (the playlist in use) are blue.
 * Moving over an entry shows its channels after a short pause; Center or Right goes into the grid. Down from the last
 * row goes round to the first, Up from the first to the last ([wrapTo]). Center held on a group opens its small window
 * ([Callbacks.onHold]: Move to top, Hide category), whose edits show here at once ([moveGroupToTop], [hideGroup]).
 */
class GuideSidebar(context: Context, private val t: Tokens) : RecyclerView(context) {

    interface Callbacks {
        /** [scope] should be shown; [enter] when the viewer pressed Center/Right to go into the grid. */
        fun onScope(scope: GuideScope, enter: Boolean)
        /** Center held on a group's [row] ([name] in full): its small window. Nothing is picked or previewed. */
        fun onHold(scope: GuideScope.Group, name: String, row: View)
    }

    var callbacks: Callbacks? = null
    private var items: List<Item> = emptyList()
    private var playlists: List<Playlist> = emptyList()
    private var groups: Map<String, List<ChannelGroup>> = emptyMap()
    /** Which playlists are open: the list over the picture's groups column shows the same ([GroupColumn.Folds]). */
    val folds = GroupColumn.Folds()
    var current: GuideScope = GuideScope.AllPlaylists
        private set
    private val main = Handler(Looper.getMainLooper())
    private var pendingScope: GuideScope? = null
    private val showPending = Runnable { pendingScope?.let { if (it != current) pick(it, enter = false) } }
    /** Set while focus is put on a row for the viewer (back from a small window): that row isn't previewed. */
    private var quietFocus = false
    private val manager = Manager(context)
    /** The row a wrap's jump puts focus on once it's laid out ([wrapTo]); -1 when none is on its way. */
    private var landing = -1

    init {
        layoutManager = manager
        adapter = Adapter()
        itemAnimator = null
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        clipToPadding = false
        // The white pill 8 dp from the rail's strip on one side and the column's hairline on the other.
        setPadding(t.dp(8), t.dp(6), t.dp(8), t.dp(10))
        overScrollMode = OVER_SCROLL_NEVER
    }

    fun setData(playlists: List<Playlist>, groups: Map<String, List<ChannelGroup>>, scope: GuideScope) {
        this.playlists = playlists
        this.groups = groups
        current = scope
        // The playlist holding the current scope starts open.
        folds.starting(playlists, scope)
        // An update arriving while the viewer is in this column (a playlist or its guide finished loading) must not
        // move them: the row they're on is found again by its identity.
        val focusedKey = focusedChild?.let { getChildAdapterPosition(it) }?.let { items.getOrNull(it) }?.let { keyOf(it) }
        val old = items
        items = buildItems()
        if (items == old) adapter?.notifyItemRangeChanged(0, items.size) else {
            @Suppress("NotifyDataSetChanged")
            adapter?.notifyDataSetChanged()
        }
        if (focusedKey != null) post {
            if (hasFocus()) return@post
            val i = items.indexOfFirst { keyOf(it) == focusedKey }
            if (i >= 0) findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() else focusCurrent()
        }
    }

    /** Focus on the current scope's entry (coming back from the grid). */
    fun focusCurrent() {
        var i = items.indexOfFirst { scopeOf(it) == current }
        if (i < 0) {
            // Its playlist was folded: open it, so focus lands on the current entry (not "All playlists", whose
            // preview would switch the scope).
            playlistOf(current)?.let { if (folds.open(it)) rebuild() }
            i = items.indexOfFirst { scopeOf(it) == current }.coerceAtLeast(0)
        }
        (layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, height / 3)
        post { findViewHolderForAdapterPosition(i)?.itemView?.requestFocus() ?: getChildAt(0)?.requestFocus() }
    }

    /**
     * Focus on [scope]'s row without previewing it: back from its small window, or onto the row that took a hidden
     * group's place. The current entry's if it isn't listed.
     */
    fun focusScope(scope: GuideScope) {
        val i = items.indexOfFirst { scopeOf(it) == scope }
        if (i < 0) { focusCurrent(); return }
        // Found even while a move or removal waits for the next layout (its position as it will be).
        val v = findViewHolderForAdapterPosition(i)?.itemView
        if (v != null) { focusQuietly(v); return }
        // Not on screen yet: a row moving up into a removed one's place shows with the next layout, where it is; one
        // further away is scrolled to.
        if (!hasPendingAdapterUpdates()) (layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, height / 3)
        post { findViewHolderForAdapterPosition(i)?.itemView?.let { focusQuietly(it) } ?: focusCurrent() }
    }

    /**
     * [scope] is what the guide shows now, chosen there (TV Guide in full screen went to the playing channel's group):
     * its entry is marked as the current one, for focus to come back to; nothing is previewed or picked.
     */
    fun select(scope: GuideScope) {
        main.removeCallbacks(showPending)
        if (scope == current) return
        current = scope
        adapter?.notifyItemRangeChanged(0, items.size)
    }

    private fun focusQuietly(v: View) {
        quietFocus = true
        v.requestFocus()
        quietFocus = false
    }

    /**
     * Move to top: [groupId] becomes the first of its playlist's groups, shown at once. Only its row moves (the rows it
     * passes step down one); when its new place is above the rows on screen, the list comes to it with the row staying
     * where it is on screen. False when it was first already, or isn't listed.
     */
    fun moveGroupToTop(playlistId: String, groupId: String): Boolean {
        val list = groups[playlistId] ?: return false
        val moved = GroupEdits.toTop(list, groupId) { it.id }
        if (moved === list) return false
        val key = entryKey(GuideScope.Group(playlistId, groupId))
        val lm = layoutManager as LinearLayoutManager
        val from = items.indexOfFirst { keyOf(it) == key }
        val firstShown = lm.findFirstVisibleItemPosition()
        val rowTop = findViewHolderForAdapterPosition(from)?.itemView?.top
        groups = groups + (playlistId to moved)
        items = buildItems()
        val to = items.indexOfFirst { keyOf(it) == key }
        if (from < 0 || to < 0) { rebuild(); return true }
        adapter?.notifyItemMoved(from, to)
        if (to < firstShown && rowTop != null) lm.scrollToPositionWithOffset(to, rowTop - paddingTop)
        return true
    }

    /**
     * Hide category: [groupId]'s row leaves the column at once. Returns the scope of the row that takes its place (the
     * next group, or the one above when it was the last: another group, or the playlist's All channels), for focus to
     * go to; when the guide was showing the hidden group, it shows that one now. Null when it isn't listed.
     */
    fun hideGroup(playlistId: String, groupId: String): GuideScope? {
        val list = groups[playlistId] ?: return null
        if (list.none { it.id == groupId }) return null
        val hidden = GuideScope.Group(playlistId, groupId)
        val landing = GroupEdits.afterHide(list.map { it.id }, groupId)?.let { GuideScope.Group(playlistId, it) }
            ?: GuideScope.AllChannels(playlistId)
        val pos = items.indexOfFirst { keyOf(it) == entryKey(hidden) }
        groups = groups + (playlistId to list.filter { it.id != groupId })
        items = buildItems()
        if (pos >= 0) adapter?.notifyItemRemoved(pos) else rebuild()
        if (current == hidden) pick(landing, enter = false)
        return landing
    }

    /** A playlist was opened or folded in the list over the picture ([folds]): this column shows it too. */
    fun foldsChanged() {
        if (items != buildItems()) rebuild()
    }

    private fun rebuild() {
        items = buildItems()
        @Suppress("NotifyDataSetChanged")
        adapter?.notifyDataSetChanged()
    }

    private fun buildItems(): List<Item> = GroupColumn.items(playlists, groups, folds)

    /** A row's identity across updates (RecyclerView keeps the focused row by it). */
    private fun keyOf(item: Item): String = when (item) {
        Item.All -> "all"
        is Item.Head -> "h|" + item.playlist.id
        is Item.Entry -> entryKey(item.scope)
    }

    private fun entryKey(scope: GuideScope) = "e|" + scope.encode()

    private val ids = HashMap<String, Long>()
    private fun idOf(item: Item): Long = ids.getOrPut(keyOf(item)) { ids.size + 1L }

    private fun scopeOf(item: Item): GuideScope? = GroupColumn.scopeOf(item)

    private fun playlistOf(scope: GuideScope): String? = GroupColumn.playlistOf(scope)

    private fun pick(scope: GuideScope, enter: Boolean) {
        main.removeCallbacks(showPending)
        current = scope
        adapter?.notifyItemRangeChanged(0, items.size)
        callbacks?.onScope(scope, enter)
    }

    /**
     * Opens or folds a playlist. Only its rows come or go, so the playlist row keeps focus (the whole list rebuilt
     * dropped focus for a moment; it landed in the guide, which closed this column).
     */
    private fun toggle(head: Item.Head) {
        val id = head.playlist.id
        val pos = items.indexOfFirst { it is Item.Head && it.playlist.id == id }
        if (pos < 0) return
        val before = items.size
        folds.toggle(id)
        items = buildItems()
        val delta = items.size - before
        adapter?.notifyItemChanged(pos)
        if (delta < 0) adapter?.notifyItemRangeRemoved(pos + 1, -delta)
        if (delta > 0) adapter?.notifyItemRangeInserted(pos + 1, delta)
        post { if (!hasFocus()) findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus() }
    }

    /**
     * Down from the last row ([down]) or Up from the first: focus goes round to [target], the first or last row. On
     * screen (a short list) focus just moves there; the list glides it fully into view if need be, as for any row.
     *
     * Further away, the list doesn't glide past every row between: it jumps to [WRAP_ROWS] rows short of that end, with
     * [target] laid out just past the edge, and focus goes onto it in the same frame; the list then glides it into
     * place, the highlight on it all the way. For that one layout focus waits on the list itself: the row it was on
     * goes with the jump, and focus mustn't fall out of the column meanwhile (into the guide, which would close it).
     */
    private fun wrapTo(target: Int, down: Boolean) {
        if (landing >= 0 || target !in items.indices) return
        val near = findViewHolderForAdapterPosition(target)?.itemView
        if (near != null && !hasPendingAdapterUpdates()) {
            near.requestFocus()
            return
        }
        val row = (focusedChild?.height ?: t.dp(ROW_DP)) + t.dp(1)
        val run = row * WRAP_ROWS
        // Focus can't be kept through the jump: it stays at the end rather than fall out of the column.
        if (!holdFocus()) { releaseFocus(); return }
        // Laid out past the edge only during this jump: rows beyond the edges aren't laid out otherwise.
        manager.extra = run + row
        val inner = height - paddingTop - paddingBottom
        manager.scrollToPositionWithOffset(target, if (down) -run else inner - row + run)
        landing = target
        viewTreeObserver.addOnPreDrawListener(land)
    }

    /** After the jump's layout, before it's drawn: focus onto the row it was for, and the list no longer holds it. */
    private val land = object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(this)
            val i = landing
            landing = -1
            manager.extra = 0
            // A row first, then the list lets go of focus (the other way round, focus would leave the column).
            (findViewHolderForAdapterPosition(i)?.itemView ?: getChildAt(0))?.requestFocus()
            releaseFocus()
            return true
        }
    }

    /** Focus on the list itself, not one of its rows: where a wrap's jump keeps it while the rows change. */
    private fun holdFocus(): Boolean {
        descendantFocusability = FOCUS_BEFORE_DESCENDANTS
        isFocusableInTouchMode = true
        isFocusable = true
        return requestFocus()
    }

    private fun releaseFocus() {
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        isFocusable = false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // A wrap's jump is landing (one frame): its keys wait rather than move focus off the list.
        if (landing >= 0) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(showPending)
        if (landing >= 0) {
            viewTreeObserver.removeOnPreDrawListener(land)
            landing = -1
            manager.extra = 0
            releaseFocus()
        }
        super.onDetachedFromWindow()
    }

    /** The column's layout manager; while a wrap's jump is laid out it also lays out [extra] pixels past each edge. */
    private class Manager(context: Context) : LinearLayoutManager(context) {
        var extra = 0

        override fun calculateExtraLayoutSpace(state: State, extraLayoutSpace: IntArray) {
            super.calculateExtraLayoutSpace(state, extraLayoutSpace)
            if (extra > 0) {
                extraLayoutSpace[0] = maxOf(extraLayoutSpace[0], extra)
                extraLayoutSpace[1] = maxOf(extraLayoutSpace[1], extra)
            }
        }
    }

    private inner class Holder(val row: LinearLayout, val label: TextView, val chevron: ImageView) : ViewHolder(row) {
        /** The playlist whose heading this row last showed (null: another kind of row): its arrow turns, others are set. */
        var head: String? = null
    }

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        init { setHasStableIds(true) }

        override fun getItemCount() = items.size

        override fun getItemId(position: Int): Long = idOf(items[position])

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = parent.context
            val colors = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_selected), intArrayOf()),
                intArrayOf(t.accent, t.accent, t.text),
            )
            val label = t.label(ctx, ENTRY_SP).apply {
                setTextColor(colors)
                isDuplicateParentStateEnabled = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            // A playlist's arrow sits before its name (the concepts): pointing right while folded, down while open.
            val chevron = ImageView(ctx).apply {
                setImageResource(R.drawable.ic_chevron_down)
                imageTintList = colors
                isDuplicateParentStateEnabled = true
                layoutParams = LinearLayout.LayoutParams(t.dp(CHEVRON_DP), t.dp(CHEVRON_DP)).apply { rightMargin = t.dp(CHEVRON_GAP_DP) }
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // TiviMate's calm spacing: a row every 39 dp (38 and the 1 dp between), the white pill as tall.
                minimumHeight = t.dp(ROW_DP)
                isFocusable = true
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { setColor(t.focusFill); cornerRadius = t.dpf(11f) })
                    setEnterFadeDuration(Tokens.FADE_MS.toInt())
                    setExitFadeDuration(Tokens.FADE_MS.toInt())
                }
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = t.dp(1) }
                addView(chevron)
                addView(label)
            }
            return Holder(row, label, chevron)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val item = items[position]
            // Playlist names a little larger than their groups (user); groups line up under the playlist's name.
            val indent = when (item) {
                Item.All -> t.dp(12)
                is Item.Head -> t.dp(HEAD_INDENT_DP)
                is Item.Entry -> t.dp(HEAD_INDENT_DP + CHEVRON_DP + CHEVRON_GAP_DP)
            }
            h.row.setPadding(indent, 0, t.dp(10), 0)
            h.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (item is Item.Entry) ENTRY_SP else HEAD_SP)
            h.label.typeface = if (item is Item.Entry) t.regular else t.semibold
            h.label.text = GroupColumn.labelOf(item)
            h.chevron.visibility = if (item is Item.Head) View.VISIBLE else View.GONE
            // The same playlist's heading bound again because it opened or folded: its arrow turns there (from where it
            // is, when pressed again mid-turn); any other row's is simply set.
            h.chevron.animate().cancel()
            if (item is Item.Head) {
                val turn = if (item.expanded) 0f else -90f
                if (h.head == item.playlist.id && h.chevron.rotation != turn && isShown) {
                    h.chevron.animate().rotation(turn).setDuration(LiveWidths.CHEVRON_MS).setInterpolator(TURN).start()
                } else h.chevron.rotation = turn
                h.head = item.playlist.id
            } else h.head = null
            // Blue: the entry the guide shows, and the playlist in use, the one holding it (user). With All playlists in
            // use, its own row. Rebound whenever the current entry changes, so the blue follows it.
            h.row.isSelected = when (item) {
                is Item.Head -> playlistOf(current) == item.playlist.id
                else -> scopeOf(item) == current
            }
            h.row.contentDescription = when (item) {
                is Item.Head -> "${item.playlist.name}, playlist, ${if (item.expanded) "expanded" else "collapsed"}"
                else -> h.label.text
            }
            h.row.setOnClickListener {
                when (item) {
                    is Item.Head -> toggle(item)
                    else -> scopeOf(item)?.let { pick(it, enter = true) }
                }
            }
            h.row.setOnFocusChangeListener { _, has ->
                // A press that began on this row ends with it: its release elsewhere picks nothing.
                if (!has) { press.cancel(); return@setOnFocusChangeListener }
                main.removeCallbacks(showPending)
                pendingScope = scopeOf(item)
                if (pendingScope != null && !quietFocus) main.postDelayed(showPending, 350)
            }
            // Center held on a group: its small window, instead of picking it on release or previewing it. The press is
            // read here, as the guide reads it ([PressHold]: the remote's repeats while held), not by Android's
            // long-click timer, which the guide doesn't use and which a quick hold could miss.
            val entry = item as? Item.Entry
            val group = entry?.scope as? GuideScope.Group
            h.row.setOnLongClickListener(null)
            h.row.isLongClickable = false
            h.row.setOnKeyListener { row, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    main.removeCallbacks(showPending)
                    when (event.action) {
                        KeyEvent.ACTION_DOWN -> {
                            if (group == null) {
                                // Only a group has a small window: on any other row (All channels, a playlist) a long
                                // press is still a press, picked or folded on its release.
                                if (event.repeatCount == 0) press.down(0, false, 0L)
                            } else if (press.down(event.repeatCount, event.isLongPress, event.eventTime - event.downTime)) {
                                callbacks?.onHold(group, entry!!.label, row)
                            }
                        }
                        // A short press: the row's own action (pick the scope, or fold/unfold a playlist).
                        KeyEvent.ACTION_UP -> if (press.up(event.isCanceled)) row.performClick()
                    }
                    return@setOnKeyListener true
                }
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                // Any other key between a press and its release: that release picks nothing.
                press.cancel()
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val s = scopeOf(item)
                        if (item is Item.Head && !item.expanded) { toggle(item); true }
                        else { pick(s ?: current, enter = true); true }
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (item is Item.Head && item.expanded) { toggle(item); true } else false
                    }
                    // Past the last row to the first and back (a fresh press; held, it stops at the end).
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> {
                        val down = keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                        val pos = h.bindingAdapterPosition
                        val to = if (pos == RecyclerView.NO_POSITION) -1 else ListMath.step(pos, items.size, down, event.repeatCount > 0) { true }
                        when {
                            pos == RecyclerView.NO_POSITION -> false
                            // Within the list: the usual move to the row beside it.
                            to == pos + (if (down) 1 else -1) -> false
                            // At the end: round to the other end, or (held, or a one-row list) stay.
                            else -> { if (to >= 0) wrapTo(to, down); true }
                        }
                    }
                    else -> false
                }
            }
        }
    }

    /** Center on a row: picks on release, or (on a group) opens its small window when held. One key at a time. */
    private val press = PressHold(android.view.ViewConfiguration.getLongPressTimeout().toLong())

    private companion object {
        val TURN = android.view.animation.DecelerateInterpolator(1.5f)
        /** A wrap to a row far away glides only this many rows (the rest is a jump). */
        const val WRAP_ROWS = 3

        // Sizes after TiviMate's column (user: larger and calmer): groups at 16 sp, playlist names a step larger.
        const val ENTRY_SP = 16f
        const val HEAD_SP = 17.5f
        const val ROW_DP = 38
        const val CHEVRON_DP = 20
        const val CHEVRON_GAP_DP = 8
        /** A playlist's chevron from the row's start; its groups' names start under the playlist's name. */
        const val HEAD_INDENT_DP = 6
    }
}
