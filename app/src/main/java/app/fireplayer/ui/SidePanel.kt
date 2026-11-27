package app.fireplayer.ui

import android.app.Activity
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import app.fireplayer.R

/**
 * The see-through settings window on the right third of the screen (or wider: [open]'s wide, for long names): one
 * frosted pane ([Glass.overlay]) with its title written on it and its rows in cards on it ([RowGroups]), the same room
 * of glass round them (side_panel.xml).
 * Each window is drawn by a render function; [refresh] redraws it in place
 * and keeps the focused row and scroll position.
 * A window of hundreds of rows is a long list instead ([longList]): only the rows on screen are made.
 */
class SidePanel(private val activity: Activity) {

    val root: View = activity.findViewById(R.id.panel)
    private val title: TextView = activity.findViewById(R.id.panel_title)
    private val scroll: CenterScrollView = activity.findViewById(R.id.panel_scroll)
    private val list: LinearLayout = activity.findViewById(R.id.panel_list)
    private val rows = Rows(activity)

    // About a third of the screen, as in the design; wide windows (a playlist's groups and channels) show long names.
    private val normalWidth = (activity.resources.displayMetrics.widthPixels * WIDTH_SHARE).toInt()
    private val wideWidth = (activity.resources.displayMetrics.widthPixels * WIDE_WIDTH_SHARE).toInt()
    private var wide = false

    /** The long list shown in place of [scroll] by a window that has one ([longList]); made the first time. */
    private var long: LongListView? = null
    private var longShown = false
    /** What the window being drawn gave [longList]. */
    private var pendingLong: LongList? = null
    /** The long list's entry last selected, by its key, and where it was. */
    private var lastEntry: String? = null
    private var lastEntryIndex = -1

    private var renderer: (SidePanel.() -> Unit)? = null
    private var onClose: (() -> Unit)? = null

    val isOpen: Boolean get() = root.visibility == View.VISIBLE

    /** Counts the times a window was opened, so a choice kept "while it's open" can start afresh the next time. */
    var session = 0
        private set
    private var sessions = 0

    /**
     * Windows a sub-window was opened from ([openSub]): Back returns to them, as wide as they were, to the row it was
     * opened from ([key]; in a long list, its entry: [entry], else the one at [entryIndex]).
     */
    private class Parent(
        val title: String,
        val render: SidePanel.() -> Unit,
        val onClose: (() -> Unit)?,
        val session: Int,
        val key: Pair<String, Int>?,
        val wide: Boolean,
        var entry: String?,
        val entryIndex: Int,
    )
    private val parents = ArrayList<Parent>()

    /** The row last selected, by what it says: the one to come back to after a small window over this one. */
    private var lastKey: Pair<String, Int>? = null

    /**
     * What the window being shown writes again in place after a step ([stepper]) or a reset ([updateValues]): each
     * stepper's value, and whatever else the window gave [liveValue]. Made again with the window's rows.
     */
    private val liveValues = ArrayList<() -> Unit>()

    init {
        root.layoutParams = root.layoutParams.apply { this.width = normalWidth }
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            if (now == null || !isOpen) return@addOnGlobalFocusChangeListener
            val rows = focusables()
            val i = rows.indexOf(now)
            if (i >= 0) lastKey = rowKey(rows, i)
        }
        // While the window holds the selection itself (its rows being replaced, a long list's row not laid out yet),
        // the arrows are used up here, so focus can't wander out of it. A window that reads with Up and Down
        // ([scrollsWithArrows]) and has nothing to select glides its text on instead.
        root.setOnKeyListener { _, keyCode, event ->
            val vertical = keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
            if (vertical && arrowScroll && !longShown && event.action == KeyEvent.ACTION_DOWN && focusables().isEmpty()) {
                // A press moves a few lines; held, the repeats keep it gliding at an even, readable pace.
                val step = rows.dp(if (event.repeatCount > 0) ARROW_HOLD_DP else ARROW_SCROLL_DP)
                scroll.glideBy(if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) step else -step)
            }
            vertical || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        }
    }

    /** Set by the window being drawn ([scrollsWithArrows]); every window starts without it. */
    private var arrowScroll = false

    /**
     * This window is read rather than used (an episode's whole story): with nothing in it to select, Up and Down glide
     * its text on by a few lines, so all of it can be read however long it is.
     */
    fun scrollsWithArrows() {
        arrowScroll = true
    }

    /** Views pinned under the window's title, outside its scrolling list ([pinned]); made the first time one asks. */
    private var pinnedBox: LinearLayout? = null
    /** The "↕ Scroll" foot under the list ([scrollHint]); made the first time a window asks for it. */
    private var foot: View? = null
    private var hint = false

    /**
     * [view] stays under the window's title while its list scrolls (a title's year and length, a hairline), for a window
     * read with the arrows ([scrollHint]). Given again each time the window is drawn; other windows have none.
     */
    fun pinned(view: View) {
        val box = pinnedBox ?: LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val panel = root as ViewGroup
            // In line with the title's words at the start and with the list's at the end, short of the slim bar's room
            // ([scrollHint]): a hairline here spans the same as one in the list and the foot's.
            panel.addView(this, panel.indexOfChild(title) + 1, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = rows.dp(TITLE_INSET_DP)
                marginEnd = rows.dp(BAR_ROOM_DP)
            })
        }.also { pinnedBox = it }
        box.addView(view)
        box.visibility = View.VISIBLE
    }

    /**
     * A window read with Up and Down ([scrollsWithArrows]) shows that it goes on: a slim bar at the list's right edge and
     * "↕ Scroll" under it, only while the list is longer than the window.
     */
    fun scrollHint() {
        hint = true
        // Room at the list's right for the bar, so it never lies over what the window shows.
        list.setPaddingRelative(list.paddingStart, list.paddingTop, rows.dp(BAR_ROOM_DP), list.paddingBottom)
        if (foot == null) {
            foot = scrollFoot().also { f ->
                val panel = root as ViewGroup
                panel.addView(f, panel.indexOfChild(scroll) + 1)
            }
            scroll.onOverflow = { over -> foot?.let { f -> f.visibility = if (hint && over) View.VISIBLE else View.GONE } }
        }
        scroll.slimBar = true
    }

    /**
     * "↕ Scroll", centred under a hairline. The hairline spans what the pinned one and a hairline in the list do: from
     * the title's words to the slim bar's room.
     */
    private fun scrollFoot(): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = rows.dp(6)
        }
        addView(DetailsLook.hairline(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, DetailsLook.hairlinePx(activity)).apply {
                marginStart = rows.dp(TITLE_INSET_DP)
                marginEnd = rows.dp(BAR_ROOM_DP)
            }
        })
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, rows.dp(8), 0, 0)
            val muted = ContextCompat.getColor(activity, R.color.text_muted)
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_scroll_hint)
                imageTintList = android.content.res.ColorStateList.valueOf(muted)
            }, LinearLayout.LayoutParams(rows.dp(14), rows.dp(14)).apply { marginEnd = rows.dp(5) })
            addView(TextView(activity).apply {
                text = "Scroll"
                typeface = ResourcesCompat.getFont(activity, R.font.figtree_500)
                setTextColor(muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, DetailsLook.LINE_SP)
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /**
     * Takes away what the last window pinned or showed under its list: each window gives its own again. [closing]: the
     * window is closing, and the views made for them go too, so nothing of it stays.
     */
    private fun clearPinned(closing: Boolean = false) {
        pinnedBox?.let {
            it.removeAllViews()
            it.visibility = View.GONE
        }
        hint = false
        scroll.slimBar = false
        foot?.visibility = View.GONE
        if (list.paddingEnd != 0) list.setPaddingRelative(list.paddingStart, list.paddingTop, 0, list.paddingBottom)
        if (closing) {
            val panel = root as ViewGroup
            pinnedBox?.let { panel.removeView(it) }
            foot?.let { panel.removeView(it) }
            pinnedBox = null
            foot = null
            scroll.onOverflow = null
        }
    }

    /**
     * Told the window's width each time a window takes its place (opened, opened over another, come back to), so what
     * the window covers (the player's subtitles) can move aside by as much.
     */
    var onWidthChanged: ((Int) -> Unit)? = null

    /** Wide (about 44% of the screen) or the usual third. Set once as a window opens, never while it moves. */
    private fun setWide(on: Boolean) {
        wide = on
        // Wide windows are for long names and values: their values may grow wider before they're shortened.
        rows.valueMaxDp = if (on) Rows.WIDE_VALUE_MAX_DP else Rows.VALUE_MAX_DP
        val width = if (on) wideWidth else normalWidth
        if (root.layoutParams.width != width) root.layoutParams = root.layoutParams.apply { this.width = width }
        onWidthChanged?.invoke(width)
    }

    /** Opens a window; [wide] for one whose rows have long names (a playlist's groups or channels). */
    fun open(titleText: String, onClose: (() -> Unit)? = null, wide: Boolean = false, render: SidePanel.() -> Unit) {
        parents.clear()
        this.onClose = onClose
        renderer = render
        session = ++sessions
        title.text = titleText
        setWide(wide)
        // A window over the screen: in the player its glass shows the film; over the app's own pages, the page behind
        // it, blurred (copied now, before the window is first drawn).
        Glass.overlay(root, Glass.Kind.PANEL)
        // The film behind the glass is copied now, so it's there as the window slides in.
        (activity as? GlassBackdrop.Host)?.glassBackdrop?.wake()
        root.visibility = View.VISIBLE
        root.alpha = 0f
        root.translationX = rows.dp(24).toFloat()
        // Slides in as one drawn layer, easing out, so a window full of rows doesn't stutter on the way.
        root.animate().alpha(1f).translationX(0f).setDuration(180)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).withLayer().start()
        scroll.scrollTo(0, 0)
        rebuild(focusIndex = 0)
    }

    /**
     * A window opened from a row of this one (a playlist from the list, a status's settings): Back ([back]) comes back
     * to this window as it was, on that row. Closing ([close]) closes them all. [focusIndex]: the row it opens on (a
     * list's current choice), the first by default.
     */
    fun openSub(titleText: String, wide: Boolean = false, focusIndex: Int = 0, render: SidePanel.() -> Unit) {
        // Only from an open window (a late answer mustn't bring a closed one back without its close callback).
        val current = renderer
        if (!isOpen || current == null) return
        val rows = focusables()
        val i = rows.indexOfFirst { it.hasFocus() }
        // A long list's row is noted by its entry (the one selected, or the one focus is on its way to).
        val shownList = long?.takeIf { longShown }
        val entry = shownList?.let { it.currentKey() ?: lastEntry }
        val entryIndex = shownList?.let { l -> l.currentIndex().takeIf { it >= 0 } ?: lastEntryIndex } ?: -1
        parents += Parent(
            title.text.toString(), current, onClose, session, if (i >= 0) rowKey(rows, i) else lastKey, this.wide,
            entry, entryIndex,
        )
        onClose = null
        renderer = render
        session = ++sessions
        title.text = titleText
        setWide(wide)
        scroll.scrollTo(0, 0)
        rebuild(focusIndex = focusIndex)
    }

    // Which parts of which window are open (Info pods ▾, Statuses ▾…), by the window's session: each opening starts
    // closed, and coming Back to a window finds its parts as they were.
    private val openParts = HashMap<String, MutableSet<Int>>()

    /** Whether [part] of this window is open. */
    fun isPartOpen(part: String): Boolean = openParts[part]?.contains(session) == true

    /** Opens or closes [part] of this window. */
    fun togglePart(part: String) {
        val set = openParts.getOrPut(part) { HashSet() }
        if (!set.remove(session)) set += session
    }

    /** Back from this window lands on the long list's entry [key] underneath (the row it was opened from moved). */
    fun backTo(key: String) {
        parents.lastOrNull()?.entry = key
    }

    /** Whether Back returns to a window under this one rather than closing. */
    val hasParent: Boolean get() = isOpen && parents.isNotEmpty()

    /** Back: to the window this one was opened from ([openSub]), else closes. */
    fun back() {
        val parent = parents.removeLastOrNull() ?: return close()
        renderer = parent.render
        onClose = parent.onClose
        session = parent.session
        title.text = parent.title
        setWide(parent.wide)
        scroll.scrollTo(0, 0)
        rebuild(focusIndex = parent.entryIndex.coerceAtLeast(0), key = parent.key, entry = parent.entry)
    }

    fun close() {
        if (!isOpen) return
        // Closed from a sub-window: what the first window was opened with still runs (its page, its focus).
        parents.firstOrNull()?.let { onClose = it.onClose }
        parents.clear()
        root.animate().cancel()
        root.visibility = View.GONE
        list.removeAllViews()
        clearPinned(closing = true)
        if (longShown) hideLong()
        lastEntry = null
        lastEntryIndex = -1
        renderer = null
        val callback = onClose
        onClose = null
        callback?.invoke()
        // Hiding the window briefly hands focus to the first row on screen; skip its fade so it doesn't flash.
        activity.window.decorView.jumpDrawablesToCurrentState()
    }

    /** The window's title; it may carry a badge after it (the Details window's parental rating, [DetailsLook.titled]). */
    fun setTitle(text: CharSequence) {
        title.text = text
    }

    /**
     * Redraws the window, keeping focus on the same row and the list where it was. The row is found again by what it
     * says, not where it was: a row that appears or goes above it (Undo, say) mustn't move the highlight onto another
     * row, where the next press of OK would land.
     */
    fun refresh() {
        if (!isOpen) return
        long?.takeIf { longShown }?.let { l ->
            // A long list: the same entry, found by its key; its rows stay where they are.
            val dialog = FireDialog.isShowing(activity)
            val entry = if (dialog) lastEntry else l.currentKey() ?: lastEntry
            val index = if (dialog) lastEntryIndex else l.currentIndex().takeIf { it >= 0 } ?: lastEntryIndex
            rebuild(index.coerceAtLeast(0), entry = entry, keepPlace = true)
            return
        }
        val rows = focusables()
        val index = rows.indexOfFirst { it.hasFocus() }
        // A small window (a list of choices) has the selection: the row that opened it is kept by what it says.
        val key = if (index >= 0) rowKey(rows, index) else if (FireDialog.isShowing(activity)) lastKey else null
        rebuild(if (index >= 0) index else 0, key)
    }

    private fun rebuild(focusIndex: Int, key: Pair<String, Int>? = null, entry: String? = null, keepPlace: Boolean = false) {
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            rebuildRows(focusIndex, key, entry, keepPlace)
        } finally {
            // Long windows are where a redraw costs; noted so it can be measured on the TV.
            val ms = android.os.SystemClock.elapsedRealtime() - started
            if (ms > 60) android.util.Log.i("FirePlayerPanel", "Window redrawn: ${if (longShown) "a long list of ${long?.list?.size}" else "${list.childCount} rows"} in $ms ms")
        }
    }

    private fun rebuildRows(focusIndex: Int, key: Pair<String, Int>?, entry: String?, keepPlace: Boolean) {
        // A small window open over this one keeps the selection; the row is only noted, to come back to when it closes.
        val behind = FireDialog.isShowing(activity)
        // Park focus on the window itself while its rows are replaced, so it can't wander off.
        root.isFocusable = !behind
        if (!behind) root.requestFocus()
        val y = scroll.scrollY
        list.removeAllViews()
        liveValues.clear()
        pendingLong = null
        arrowScroll = false
        clearPinned()
        renderer?.invoke(this)
        val entries = pendingLong
        pendingLong = null
        if (entries != null) {
            showLong(entries, entry, focusIndex, keepPlace, behind)
            return
        }
        if (longShown) hideLong()
        RowGroups.apply(list)
        scroll.scrollTo(0, y)
        val focusRows = focusables()
        if (focusRows.isNotEmpty()) {
            val same = key?.let { k -> focusRows.indices.firstOrNull { rowKey(focusRows, it) == k } }
            val row = focusRows[(same ?: focusIndex).coerceIn(0, focusRows.size - 1)]
            if (behind) {
                FireDialog.returnFocusTo(activity, row)
                root.isFocusable = false
                return
            }
            row.requestFocus()
            // The new row is already the selected one: show it white at once instead of fading in from dark.
            row.jumpDrawablesToCurrentState()
            root.isFocusable = false
        }
        // A window with nothing to press (only a note) keeps the selection itself, so it can't wander out to the page
        // under it; its arrows are used up and Back still goes back.
    }

    /**
     * The window's rows as a long list ([longList]), in place of the scrolling list. Focus stays parked on the window
     * until the row it goes to is laid out (in the coming frame, before it's drawn), then lands there.
     */
    private fun showLong(entries: LongList, entry: String?, focusIndex: Int, keepPlace: Boolean, behind: Boolean) {
        val view = long ?: LongListView(activity, rows).also { v ->
            long = v
            v.visibility = View.GONE
            v.onFocused = { key, index ->
                lastEntry = key
                lastEntryIndex = index
            }
            // In the list's place, above anything under it.
            val panel = root as ViewGroup
            panel.addView(v, panel.indexOfChild(scroll) + 1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        if (!longShown) {
            longShown = true
            scroll.visibility = View.GONE
            view.visibility = View.VISIBLE
        }
        // With no row to go to, the window keeps the selection itself (see rebuildRows).
        view.show(entries, entry, focusIndex, keepPlace, behind) { took -> if (took) root.isFocusable = false }
    }

    private fun hideLong() {
        longShown = false
        long?.let {
            it.clear()
            it.visibility = View.GONE
        }
        scroll.visibility = View.VISIBLE
    }

    /** A row by what it says (its label) and which of the rows saying that it is. */
    private fun rowKey(rows: List<View>, index: Int): Pair<String, Int> {
        val label = labelOf(rows[index])
        return label to (0 until index).count { labelOf(rows[it]) == label }
    }

    /** The first words in a row: its label. */
    private fun labelOf(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).asSequence().map { labelOf(view.getChildAt(it)) }.firstOrNull { it.isNotEmpty() } ?: ""
        else -> ""
    }

    /** The rows the D-pad can reach, in order; a row with a button beside it gives both. */
    private fun focusables(): List<View> =
        (0 until list.childCount).map { list.getChildAt(it) }.flatMap { child ->
            if (child.tag == ROW_PAIR && child is ViewGroup) (0 until child.childCount).map { child.getChildAt(it) }
            else listOf(child)
        }.filter { it.isFocusable }

    /**
     * Rewrites one row's words in place ([label], and [value] as [row] would show it: a switch for On/Off), keeping
     * the row itself (its card, focus and what it does). For long lists, where redrawing the whole window takes a
     * second on a TV (a playlist's 350 groups).
     */
    fun updateRow(row: View, label: String, value: String?) {
        val target = row as? LinearLayout ?: return
        target.removeAllViews()
        rows.fill(target, label, value, null)
        target.refreshDrawableState()
    }

    // ---- Long lists ------------------------------------------------------------------------------------------------

    /**
     * This window's rows are [entries], a long list (a playlist's groups, a group's channels): only the rows on screen
     * are made, so it opens at once and scrolls smoothly however many there are. They look and move like the other
     * windows' rows. The window is the list alone: a few fixed rows at the top are simply its first entries.
     */
    fun longList(entries: LongList) {
        pendingLong = entries
    }

    /** [count] of the long list's entries from [index] changed: their rows are shown again in place. */
    fun changed(index: Int, count: Int = 1) {
        long?.takeIf { longShown }?.changed(index, count)
    }

    /** Moves focus to the long list's entry at [index] (gliding there, or jumping when it's far off). */
    fun focusEntry(index: Int) {
        long?.takeIf { longShown }?.focusEntry(index)
    }

    /** Moves focus to the long list's entry with [key], if it has one. */
    fun focusEntry(key: String) {
        val l = long?.takeIf { longShown } ?: return
        val i = l.indexOf(key)
        if (i >= 0) l.focusEntry(i)
    }

    /** The long list's entry selected (or about to be), or -1. */
    val focusedEntry: Int get() = long?.takeIf { longShown }?.currentIndex() ?: -1

    fun focusRow(index: Int) {
        long?.takeIf { longShown }?.let { it.focusSomething(); return }
        val focusRows = focusables()
        if (index in focusRows.indices) focusRows[index].requestFocus()
    }

    // ---- Building blocks -------------------------------------------------

    /**
     * A section's heading: small caps with a light blue line icon before it ([icon]; by default the one for this
     * heading's name, [headingIcon]). The rows under it form one card ([RowGroups]).
     */
    fun header(text: String, icon: Int? = headingIcon(text)) {
        list.addView(HeadingLine(activity, rows).apply { set(text, icon) }, rows.listParams(top = 12, bottom = 4))
    }

    /**
     * A note under the rows; [accent] makes it blue, for something that needs doing, such as a change to save. [maxLines]
     * keeps a note with a name in it (one the viewer typed) as short as it was written to be.
     */
    fun message(text: String, accent: Boolean = false, maxLines: Int = Int.MAX_VALUE) {
        list.addView(noteText(activity, rows, accent).apply {
            this.text = text
            if (maxLines != Int.MAX_VALUE) {
                this.maxLines = maxLines
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        }, rows.listParams(top = 4, bottom = 4))
    }

    /**
     * A short note in the card of the rows above it, in a row's place (what the setting above does, in a line): the
     * D-pad passes it by, as it does a note under the rows ([message]).
     */
    fun cardNote(text: String) {
        val row = rows.base().apply {
            isFocusable = false
            isClickable = false
        }
        row.addView(noteText(activity, rows).apply {
            this.text = text
            // The note's own muted colour, not the row's text colour.
            tag = KEEP_COLORS
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        add(row)
    }

    /**
     * A tappable row. [checked] shows the blue tick used for the current choice;
     * [value] is shown on the right. A row that isn't [enabled] (not available here) is dimmed ([dim]) and skipped by
     * the D-pad; it stays in its card.
     */
    fun row(
        label: String,
        value: String? = null,
        checked: Boolean = false,
        enabled: Boolean = true,
        twoLines: Boolean = false,
        onClick: () -> Unit,
    ): View {
        val row = rows.build(label, value, if (checked) R.drawable.ic_check else null, if (twoLines) 2 else 1)
        row.isSelected = checked
        if (enabled) {
            row.setOnClickListener { onClick() }
        } else {
            row.isFocusable = false
            row.isClickable = false
            dim(row)
        }
        add(row)
        return row
    }

    /**
     * Draws [row] faint, the one look for a row that can't be used just now (not available, or busy checking). Only
     * what it shows fades: its slice of the card stays, so the card has no pale band in it.
     */
    fun dim(row: View) {
        (row as? ViewGroup)?.let { Rows.dim(it) }
    }

    /** A row with an icon before its label, as the Settings tab lists the categories ([SettingsCategory.icon]). */
    fun iconRow(label: String, icon: Int, onClick: () -> Unit): View {
        val row = rows.build(label, null, null)
        row.addView(rows.icon(icon, 16).apply {
            (layoutParams as LinearLayout.LayoutParams).apply { marginStart = 0; marginEnd = rows.dp(10) }
        }, 0)
        row.setOnClickListener { onClick() }
        add(row)
        return row
    }

    /**
     * The Search bubble at the top of "Search and App Settings" and of Live TV's settings window: the window's row with
     * the search icon at its left and "Search" in its middle. Put first, it has focus when the window opens.
     */
    fun searchBubble(onClick: () -> Unit): View {
        val row = rows.build("Search", null, null)
        val iconDp = 16
        row.addView(rows.icon(R.drawable.ic_search, iconDp).apply {
            (layoutParams as LinearLayout.LayoutParams).apply { marginStart = 0; marginEnd = rows.dp(10) }
        }, 0)
        for (i in 0 until row.childCount) (row.getChildAt(i) as? TextView)?.gravity = Gravity.CENTER
        // As wide as the icon and its gap, so the word sits in the row's middle.
        row.addView(View(row.context), LinearLayout.LayoutParams(rows.dp(iconDp + 10), 1))
        row.setOnClickListener { onClick() }
        add(row)
        return row
    }

    /**
     * A row whose value is a small badge saying how the setting stands: "Active" in blue, "Inactive" in faded gray
     * ([Rows.badge]).
     */
    fun badgeRow(label: String, badge: String, style: Rows.Badge, onClick: () -> Unit): View {
        val row = rows.build(label, null, null)
        row.addView(rows.badge(badge, style))
        row.setOnClickListener { onClick() }
        add(row)
        return row
    }

    /**
     * A row with a small arrow button beside it, which shows or hides what belongs to it: pointing down while closed
     * and up while [expanded], as [dropdownRow]'s. Right on the D-pad reaches the arrow, Left goes back; up and down
     * pass both. [turned] turns the arrow from where it was, after a press.
     */
    fun rowWithArrow(
        label: String,
        value: String?,
        expanded: Boolean,
        turned: Boolean,
        onClick: () -> Unit,
        onArrow: () -> Unit,
    ): View {
        val row = rows.build(label, value, null)
        row.setOnClickListener { onClick() }
        val chevron = ImageView(activity).apply {
            setImageResource(R.drawable.ic_chevron_down)
            imageTintList = rows.textColors
            val to = if (expanded) 180f else 0f
            rotation = if (turned) (if (expanded) 0f else 180f) else to
            if (turned) animate().rotation(to).setDuration(180).start()
        }
        val arrow = rows.base().apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            minimumHeight = 0
            contentDescription = if (expanded) "Hide details" else "Show details"
            addView(chevron, LinearLayout.LayoutParams(rows.dp(15), rows.dp(15)))
            setOnClickListener { onArrow() }
        }
        val pair = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            tag = ROW_PAIR
            // The glass switch's glow reaches past its row.
            clipChildren = false
            clipToPadding = false
        }
        pair.setTag(R.id.group_row, true)
        pair.addView(row, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pair.addView(arrow, LinearLayout.LayoutParams(rows.dp(36), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            marginStart = rows.dp(6)
        })
        row.setOnKeyListener { _, keyCode, event ->
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) arrow.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> true
                else -> Rows.verticalNav(list, row, keyCode, event)
            }
        }
        arrow.setOnKeyListener { _, keyCode, event ->
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) row.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> true
                // Up and down go on from the row, as if from it.
                else -> Rows.verticalNav(list, row, keyCode, event)
            }
        }
        list.addView(pair, rows.listParams())
        return row
    }

    /**
     * A row with a small icon button beside it ([icon], read out as [buttonLabel]): Right reaches the button, Left
     * goes back, Up and Down pass both. [keys] sees the row's keys first (a row being moved takes Up and Down).
     */
    fun rowWithButton(
        label: String,
        value: String?,
        icon: Int,
        buttonLabel: String,
        onClick: () -> Unit,
        onButton: () -> Unit,
        keys: ((Int, KeyEvent) -> Boolean)? = null,
    ): View {
        val row = rows.build(label, value, null)
        row.setOnClickListener { onClick() }
        val button = rows.base().apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            minimumHeight = 0
            contentDescription = buttonLabel
            addView(ImageView(activity).apply {
                setImageResource(icon)
                imageTintList = rows.textColors
                isDuplicateParentStateEnabled = true
            }, LinearLayout.LayoutParams(rows.dp(15), rows.dp(15)))
            setOnClickListener { onButton() }
        }
        val pair = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            tag = ROW_PAIR
            clipChildren = false
            clipToPadding = false
        }
        pair.setTag(R.id.group_row, true)
        pair.addView(row, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pair.addView(button, LinearLayout.LayoutParams(rows.dp(36), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = rows.dp(6) })
        row.setOnKeyListener { _, keyCode, event ->
            if (keys?.invoke(keyCode, event) == true) return@setOnKeyListener true
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_DOWN) button.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_LEFT -> true
                else -> Rows.verticalNav(list, row, keyCode, event)
            }
        }
        button.setOnKeyListener { _, keyCode, event ->
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.action == KeyEvent.ACTION_DOWN) row.requestFocus(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> true
                else -> Rows.verticalNav(list, row, keyCode, event)
            }
        }
        list.addView(pair, rows.listParams())
        return row
    }

    /**
     * A row that shows or hides what's under it: an arrow pointing down while closed and up while open, and a small
     * [badge] saying how it stands (see [Rows.badge]).
     */
    fun dropdownRow(label: String, badge: String?, style: Rows.Badge, open: Boolean, onClick: () -> Unit): View {
        val row = rows.build(label, null, null)
        if (badge != null) row.addView(rows.badge(badge, style).apply { setTag(R.id.row_badge, style) })
        row.addView(ImageView(activity).apply {
            setImageResource(R.drawable.ic_chevron_down)
            imageTintList = rows.textColors
            rotation = if (open) 180f else 0f
            layoutParams = LinearLayout.LayoutParams(rows.dp(15), rows.dp(15)).apply { marginStart = rows.dp(8) }
        })
        row.setOnClickListener { onClick() }
        add(row)
        return row
    }

    /**
     * Rewrites a [dropdownRow]'s badge in place ([badge], drawn as [style]), keeping the row, its focus and its arrow:
     * for a badge a step changes ([liveValue]). Only a badge whose look changes is made again.
     */
    fun setBadge(row: View, badge: String, style: Rows.Badge) {
        val target = row as? ViewGroup ?: return
        for (i in 0 until target.childCount) {
            val old = target.getChildAt(i) as? TextView ?: continue
            if (old.getTag(R.id.row_badge) == null) continue
            if (old.getTag(R.id.row_badge) == style) {
                if (!android.text.TextUtils.equals(old.text, badge)) old.text = badge
            } else {
                val params = old.layoutParams
                target.removeViewAt(i)
                target.addView(rows.badge(badge, style).apply { setTag(R.id.row_badge, style) }, i, params)
                target.refreshDrawableState()
            }
            return
        }
    }

    /**
     * A search result: its name (up to two lines), a line of small notes under it, and a value on the right (a match
     * score). The notes follow the row's colours, dark on the white selected row.
     */
    fun resultRow(
        title: String,
        notes: String,
        value: String?,
        selected: Boolean = false,
        poster: String? = null,
        withPoster: Boolean = false,
        onClick: () -> Unit,
    ): View {
        val row = rows.base()
        // Blue while it's the one in use, like the current choice elsewhere.
        row.isSelected = selected
        row.setPadding(rows.dp(if (withPoster) 10 else 16), rows.dp(if (withPoster) 10 else 10), rows.dp(16), rows.dp(if (withPoster) 10 else 10))
        // A TMDB result: its cover on the left, its corners following the row's own, so it stays inside the selected one.
        if (withPoster) row.addView(cover(poster, 36, 54, 8), LinearLayout.LayoutParams(rows.dp(36), rows.dp(54)).apply {
            marginEnd = rows.dp(12)
        })
        val column = RowLayout(activity, rows.textColors).apply {
            orientation = LinearLayout.VERTICAL
            // Takes the row's state, so its texts are recoloured with it.
            isDuplicateParentStateEnabled = true
            addView(rows.text(title).apply {
                isSingleLine = false
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setLineSpacing(1f * rows.density, 1f)
            })
            // One line per kind of note ("Best match · Same release", then "23.976 fps · 12k downloads · English").
            notes.split('\n').filter { it.isNotBlank() }.forEachIndexed { i, line ->
                addView(FactsText(activity, line.split(" · ")).apply {
                    typeface = ResourcesCompat.getFont(activity, R.font.figtree_600)
                    setTextColor(rows.textColors)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    alpha = if (i == 0) 0.9f else 0.72f
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                    setLineSpacing(1f * rows.density, 1f)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = rows.dp(if (i == 0) 3 else 1) }
                })
            }
        }
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // The scores line up on the right, whatever their width.
        if (value != null) row.addView(rows.text(value).apply {
            typeface = ResourcesCompat.getFont(activity, R.font.figtree_700)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = android.view.Gravity.END
            minWidth = rows.dp(40)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = rows.dp(12) }
        })
        row.setOnClickListener { onClick() }
        add(row, groupable = false)
        return row
    }

    /** A row with [trailing] after its value, such as [tickPill]; the same size as the other rows. */
    fun rowWithPill(label: String, value: String?, trailing: View?, onClick: () -> Unit): View {
        val row = rows.build(label, value, null)
        if (trailing != null) row.addView(trailing)
        row.setOnClickListener { onClick() }
        add(row)
        return row
    }

    /** A small blue pill with a white tick, as the "Active" pills: all of it done. */
    fun tickPill(): TextView = rows.badge("✓", Rows.Badge.ACTIVE).apply {
        minWidth = rows.dp(26)
        contentDescription = "All checked"
    }

    /**
     * A row that does one thing, with what it does in a pill on the right: blue ([Rows.Badge.ACTIVE]) when it's worth
     * doing, gray when there's nothing to do. [note] is a second, smaller line under the label.
     */
    fun actionRow(label: String, note: String?, badge: String, style: Rows.Badge, onClick: () -> Unit): View {
        val row = rows.base()
        // Selected, it doesn't turn white, so its blue or gray pill and its words stay as they are: with the glass look
        // it stays glass, dimmed, with a blue outline; without it, a dark fill with a blue outline.
        val d = rows.density
        val focused = intArrayOf(android.R.attr.state_focused)
        val outline = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 9 * d
            setColor(ContextCompat.getColor(activity, if (Glass.on) R.color.action_focus_dim else R.color.action_focus_fill))
            setStroke(rows.dp(2), ContextCompat.getColor(activity, R.color.accent))
        }
        val plain = row.background
        row.background = if (Glass.on && plain != null) {
            android.graphics.drawable.LayerDrawable(arrayOf(
                Unfocused(plain),
                android.graphics.drawable.StateListDrawable().apply {
                    setEnterFadeDuration(140)
                    setExitFadeDuration(140)
                    addState(focused, outline)
                    addState(intArrayOf(), android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                },
            ))
        } else {
            android.graphics.drawable.StateListDrawable().apply {
                setEnterFadeDuration(140)
                setExitFadeDuration(140)
                addState(focused, outline)
                plain?.let { addState(intArrayOf(), it) }
            }
        }
        val white = ContextCompat.getColor(activity, R.color.text)
        val column = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(rows.text(label).apply { tag = KEEP_COLORS; setTextColor(white) })
            if (!note.isNullOrEmpty()) addView(rows.text(note).apply {
                tag = KEEP_COLORS
                setTextColor(white)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                alpha = 0.75f
                isSingleLine = false
                maxLines = 2
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = rows.dp(2) }
            })
        }
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Its own colors, not the ones it would take on a white selected row.
        row.addView(rows.badge(badge, style).apply { isDuplicateParentStateEnabled = false })
        row.setOnClickListener { onClick() }
        add(row, groupable = false)
        return row
    }

    /**
     * One of a title's copies: its name, a line of what it is, and on the right its data rate under a "Best" pill when
     * it's the one recommended. Blue while it's the one playing.
     */
    fun copyRow(title: String, notes: String, rate: String?, best: Boolean, playing: Boolean, onClick: () -> Unit): View {
        val row = rows.base()
        row.isSelected = playing
        row.setPadding(rows.dp(16), rows.dp(9), rows.dp(14), rows.dp(9))
        val column = RowLayout(activity, rows.textColors).apply {
            orientation = LinearLayout.VERTICAL
            isDuplicateParentStateEnabled = true
            addView(rows.text(title).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f) })
            if (notes.isNotEmpty()) addView(FactsText(activity, notes.split(" · ")).apply {
                typeface = ResourcesCompat.getFont(activity, R.font.figtree_600)
                setTextColor(rows.textColors)
                alpha = Rows.NOTE_ALPHA
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Rows.NOTE_SP)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = rows.dp(Rows.NOTE_GAP_DP) }
            })
        }
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val side = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            isDuplicateParentStateEnabled = true
            if (best) addView(rows.badge("Best", Rows.Badge.ACTIVE).apply {
                minWidth = 0
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            if (rate != null) addView(rows.text(rate).apply {
                typeface = ResourcesCompat.getFont(activity, R.font.figtree_700)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { if (best) topMargin = rows.dp(4) }
            })
        }
        row.addView(side, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = rows.dp(10) })
        row.setOnClickListener { onClick() }
        add(row, groupable = false)
        return row
    }

    /** [inner] as it looks unselected, whatever the row's state: the glass under [actionRow]'s dimmed outline. */
    private class Unfocused(private val inner: android.graphics.drawable.Drawable) : android.graphics.drawable.Drawable() {
        override fun draw(canvas: android.graphics.Canvas) = inner.draw(canvas)
        override fun onBoundsChange(bounds: android.graphics.Rect) { inner.bounds = bounds }
        override fun setAlpha(alpha: Int) { inner.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { inner.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        override fun isStateful(): Boolean = false
        init {
            // It redraws as the glass behind it changes.
            inner.callback = object : Callback {
                override fun invalidateDrawable(who: android.graphics.drawable.Drawable) = invalidateSelf()
                override fun scheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable, `when`: Long) = scheduleSelf(what, `when`)
                override fun unscheduleDrawable(who: android.graphics.drawable.Drawable, what: Runnable) = unscheduleSelf(what)
            }
        }
    }

    /** A picture from TMDB, [w] by [h] dp with [corner] dp corners, on a faint fill while it loads (or has none). */
    fun cover(url: String?, w: Int, h: Int, corner: Int): ImageView = CoverImage(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = rows.dp(corner).toFloat()
            setColor(0x22FFFFFF)
        }
        clipToOutline = true
        isDuplicateParentStateEnabled = false
        ImageLoader.load(url, this, rows.dp(w * 2))
    }

    /** A search box: a magnifier, what's searched for, and a small [source] tag on the right. OK opens the keyboard. */
    fun searchBox(query: String, source: String, onClick: () -> Unit): View {
        val row = rows.base()
        row.minimumHeight = rows.dp(38)
        row.setPadding(rows.dp(14), rows.dp(7), rows.dp(14), rows.dp(7))
        row.addView(rows.icon(R.drawable.ic_search, 15).apply {
            (layoutParams as LinearLayout.LayoutParams).apply { marginStart = 0; marginEnd = rows.dp(10) }
        })
        row.addView(rows.text(query.ifEmpty { "Type a name" }).apply { if (query.isEmpty()) alpha = 0.6f },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(rows.text(source).apply {
            typeface = ResourcesCompat.getFont(activity, R.font.figtree_700)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            letterSpacing = 0.04f
            alpha = 0.6f
        })
        row.setOnClickListener { onClick() }
        add(row, groupable = false)
        return row
    }

    /** Facts in two columns, a muted name on the left and its value beside it, like "Show   Breaking Bad (2008)". */
    fun facts(pairs: List<Pair<String, View>>) {
        if (pairs.isEmpty()) return
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        pairs.forEachIndexed { i, (name, value) ->
            box.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(activity).apply {
                    text = name
                    typeface = ResourcesCompat.getFont(activity, R.font.figtree_500)
                    setTextColor(ContextCompat.getColor(activity, R.color.text_muted))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                }, LinearLayout.LayoutParams(rows.dp(92), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(value, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) topMargin = rows.dp(4)
            })
        }
        list.addView(box, rows.listParams(top = 2, bottom = 6))
    }

    /** A fact's value as plain text, for [facts]. */
    fun fact(value: String): TextView = TextView(activity).apply {
        text = value
        typeface = ResourcesCompat.getFont(activity, R.font.figtree_600)
        setTextColor(ContextCompat.getColor(activity, R.color.text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /** One number set with round ‹ and › buttons, for [steppers]. */
    class Step(val label: String, val value: String, val onStep: (direction: Int) -> Unit)

    /**
     * Numbers side by side, each under a small caps name, set with round ‹ and › buttons ("SEASON ‹ 2 ›  EPISODE ‹ 4 ›").
     * Left and right move between the buttons, OK presses one.
     */
    fun steppers(steps: List<Step>) {
        val group = 104
        val gap = 28
        list.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            steps.forEach { s ->
                addView(TextView(activity).apply {
                    text = s.label.uppercase()
                    typeface = ResourcesCompat.getFont(activity, R.font.figtree_700)
                    letterSpacing = 0.1f
                    setTextColor(ContextCompat.getColor(activity, R.color.text_muted))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                }, LinearLayout.LayoutParams(rows.dp(group + gap), ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }, rows.listParams(top = 8, bottom = 3))
        val pair = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = ROW_PAIR
            clipChildren = false
            clipToPadding = false
        }
        fun button(sign: String, onClick: () -> Unit) = rows.base().apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 0)
            minimumHeight = 0
            contentDescription = if (sign == "‹") "Less" else "More"
            addView(rows.text(sign).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); gravity = Gravity.CENTER })
            setOnClickListener { onClick(); refresh() }
        }
        steps.forEachIndexed { i, s ->
            if (i > 0) pair.addView(View(activity), LinearLayout.LayoutParams(rows.dp(gap), 1))
            pair.addView(button("‹") { s.onStep(-1) }, LinearLayout.LayoutParams(rows.dp(30), rows.dp(30)))
            pair.addView(TextView(activity).apply {
                text = s.value
                gravity = Gravity.CENTER
                typeface = ResourcesCompat.getFont(activity, R.font.figtree_700)
                setTextColor(ContextCompat.getColor(activity, R.color.step_value))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            }, LinearLayout.LayoutParams(rows.dp(44), ViewGroup.LayoutParams.WRAP_CONTENT))
            pair.addView(button("›") { s.onStep(1) }, LinearLayout.LayoutParams(rows.dp(30), rows.dp(30)))
        }
        sideBySide(pair)
        list.addView(pair, rows.listParams(top = 0, bottom = 6))
    }

    /** Two buttons side by side, the first the one that does it ("Save match", then "Cancel"). */
    fun buttons(first: String, second: String, onFirst: () -> Unit, onSecond: () -> Unit) {
        val pair = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            tag = ROW_PAIR
            clipChildren = false
            clipToPadding = false
        }
        listOf(first to onFirst, second to onSecond).forEachIndexed { i, (label, action) ->
            pair.addView(rows.base().apply {
                gravity = Gravity.CENTER
                minimumHeight = rows.dp(40)
                addView(rows.text(label).apply { gravity = Gravity.CENTER })
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = rows.dp(10) })
        }
        sideBySide(pair)
        list.addView(pair, rows.listParams(top = 8, bottom = 2))
    }

    /** Left and right move between [pair]'s buttons; up and down go on from it as one row. */
    private fun sideBySide(pair: ViewGroup) {
        val buttons = (0 until pair.childCount).map { pair.getChildAt(it) }.filter { it.isFocusable }
        buttons.forEachIndexed { i, b ->
            b.setOnKeyListener { _, keyCode, event ->
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            buttons.getOrNull(i + if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)?.requestFocus()
                        }
                        true
                    }
                    else -> Rows.verticalNav(list, pair.getChildAt(0), keyCode, event)
                }
            }
        }
    }

    /**
     * A block of text in a row's look (a title's whole story, its full cast), as many lines as it takes. It can take
     * the selection, so a long window scrolls through it with Up and Down; OK does nothing.
     */
    fun textBlock(text: String): View {
        val row = rows.base()
        row.addView(rows.text(text).apply {
            isSingleLine = false
            maxLines = Int.MAX_VALUE
            ellipsize = null
            setLineSpacing(rows.dp(2).toFloat(), 1f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        add(row)
        return row
    }

    /** Something shown in the list that isn't a row, such as a progress bar: the D-pad passes it by. */
    fun view(view: View, top: Int = 2, bottom: Int = 2) {
        list.addView(view, rows.listParams(top, bottom))
    }

    /**
     * A card that can be selected and opened (an episode in Show details): [card] keeps its own surface and takes the
     * rows' selected look over it, the same highlight as every row; OK runs [onClick]. Its words turn dark with it when
     * they follow its state ([RowLayout], with isDuplicateParentStateEnabled).
     */
    fun cardRow(card: View, top: Int = 2, bottom: Int = 2, onClick: () -> Unit): View {
        val l = card.paddingLeft
        val t = card.paddingTop
        val r = card.paddingRight
        val b = card.paddingBottom
        val focus = RowGroups.focusOnly(activity)
        card.background = card.background?.let { android.graphics.drawable.LayerDrawable(arrayOf(it, focus)) } ?: focus
        card.setPadding(l, t, r, b)
        card.isFocusable = true
        card.isClickable = true
        card.setOnClickListener { onClick() }
        card.setOnKeyListener { v, keyCode, event ->
            val vertical = keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
            // The window's one card (Show details): Up and Down glide its text instead, so what's above it can be read.
            if (vertical && focusables().size < 2) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val step = rows.dp(ARROW_SCROLL_DP)
                    scroll.glideBy(if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) step else -step)
                }
                true
            } else {
                Rows.verticalNav(list, v, keyCode, event) ||
                    keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            }
        }
        list.addView(card, rows.listParams(top, bottom))
        return card
    }

    /**
     * A setting with several values: shows the current one and opens a small picker window. [alsoChecked] is ticked
     * there too: what the current value stands for (Auto's own pick). [valueText] is the value as the row shows it,
     * shorter than its name in the picker when that wouldn't fit the row.
     */
    fun choice(
        label: String,
        options: List<String>,
        selected: Int,
        alsoChecked: Int? = null,
        valueText: String? = null,
        onPick: (Int) -> Unit,
    ): View {
        // Always the value in words, even "Off" (no subtitle language): this row opens a picker, it isn't a switch.
        val row = rows.build(label, valueText ?: options.getOrNull(selected) ?: "", null, asSwitch = false)
        row.setOnClickListener {
            FireDialog.choose(activity, label, options, selected, besidePanel = true, alsoChecked = alsoChecked) { index ->
                if (index != selected) onPick(index)
                refresh()
            }
        }
        add(row)
        return row
    }

    /**
     * A row adjusted with left / right on the D-pad, like "Delay  ‹ 0 ms ›": [onStep] changes it, then its [value] (and
     * whatever else the window shows live, [liveValue]) is written again in place. The window isn't drawn again, so the
     * row keeps the selection however long an arrow is held: a row made anew isn't laid out until the next frame, and
     * the repeats that come before it would miss it and let Android move focus out of the window. Left and right are
     * always used up here, at either end too (each value stops at its ends). [redrawOnRelease]: the window is drawn
     * again once the arrow is let go, for rows the change adds or takes away (Auto sync's, after a subtitle delay).
     */
    fun stepper(label: String, value: () -> String, redrawOnRelease: Boolean = false, onStep: (direction: Int) -> Unit): View {
        // The value and its arrows in blue, like the other values that can be changed right here.
        val row = rows.build(label, stepText(value()), null, valueColors = rows.stepColors)
        val shown = row.getChildAt(row.childCount - 1) as TextView
        liveValues += {
            val now = stepText(value())
            if (!android.text.TextUtils.equals(shown.text, now)) shown.text = now
        }
        add(row) { keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
                false
            } else {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    onStep(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                    updateValues()
                } else if (event.action == KeyEvent.ACTION_UP && redrawOnRelease) {
                    refresh()
                }
                true
            }
        }
        return row
    }

    private fun stepText(value: String) = "‹  $value  ›"

    /**
     * [update] writes again, in place, something this window shows that a step ([stepper]) or a reset can change: a
     * dropdown's badge ([setBadge]), another row's value ([updateRow]). Kept until the window is drawn again.
     */
    fun liveValue(update: () -> Unit) {
        liveValues += update
    }

    /** Every value this window shows live ([stepper], [liveValue]) written again in place: no row is made again. */
    fun updateValues() {
        for (i in liveValues.indices) liveValues[i]()
    }

    fun spacer(dp: Int) {
        list.addView(View(activity), LinearLayout.LayoutParams(1, rows.dp(dp)))
    }

    /** Adds a row to the list; a [groupable] one joins the rows around it in one card ([RowGroups]). */
    private fun add(row: View, groupable: Boolean = true, extraKeys: ((Int, KeyEvent) -> Boolean)? = null) {
        if (groupable) row.setTag(R.id.group_row, true)
        row.setOnKeyListener { v, keyCode, event ->
            extraKeys?.invoke(keyCode, event) == true || Rows.verticalNav(list, v, keyCode, event) ||
                // Left / right mean nothing on other rows; used up here so focus can't leave the window.
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        }
        list.addView(row, rows.listParams())
    }

    companion object {
        /** The window's width, as a share of the screen's: the usual third, and a wide window's ([open]'s wide). */
        const val WIDTH_SHARE = 0.31f
        const val WIDE_WIDTH_SHARE = 0.44f

        /** How far one press of Up or Down moves a window read with the arrows ([scrollsWithArrows]): about three lines. */
        const val ARROW_SCROLL_DP = 60

        /** How far each repeat of a held Up or Down moves it on: a steady glide that can still be read. */
        const val ARROW_HOLD_DP = 30

        /** The title's words sit this far in from the window's content (side_panel.xml's padding on the title). */
        private const val TITLE_INSET_DP = 4

        /** Room for the slim bar right of a window's list while it shows one ([scrollHint]). */
        private const val BAR_ROOM_DP = 14

        /** The icon beside each heading, by its name; headings not listed have none. */
        private val HEADING_ICONS = mapOf(
            "This film" to R.drawable.ic_head_film,
            "This episode" to R.drawable.ic_head_film,
            "In this video" to R.drawable.ic_head_film,
            "On this channel" to R.drawable.ic_head_tv,
            "This copy" to R.drawable.ic_head_film,
            "Copies" to R.drawable.ic_head_list,
            "Remote" to R.drawable.ic_head_remote,
            "Look" to R.drawable.ic_head_look,
            "Skipping" to R.drawable.ic_head_skip,
            "Defaults" to R.drawable.ic_head_lang,
            "Playback" to R.drawable.ic_head_play,
            "TiviMate titles" to R.drawable.ic_head_tv,
            "Playlists" to R.drawable.ic_head_list,
            "Subtitle font" to R.drawable.ic_head_font,
            "Online subtitles and TMDB API" to R.drawable.ic_head_subs,
            "Subtitle style" to R.drawable.ic_sub_style,
            "Presets" to R.drawable.ic_head_star,
            "Other subtitles" to R.drawable.ic_head_subs,
            "Skip intro and credits" to R.drawable.ic_head_intro,
            "App" to R.drawable.ic_head_app,
            "Statuses" to R.drawable.ic_head_app,
            // As the Settings tab shows the category.
            "Playlists & EPG" to R.drawable.ic_playlist_add,
            "Rows" to R.drawable.ic_head_list,
            // Settings › Rows: Home's services' order, and the rail's shortcuts to them.
            "Streaming Services" to R.drawable.ic_head_services,
            "Service shortcuts" to R.drawable.ic_head_pin,
            "Playlist" to R.drawable.ic_head_list,
            "Account" to R.drawable.ic_key,
            "Xtream Codes parameters" to R.drawable.ic_head_tv,
            "M3U playlist" to R.drawable.ic_head_tv,
            "EPG sources" to R.drawable.ic_epg,
            "EPG" to R.drawable.ic_epg,
            "Groups" to R.drawable.ic_head_list,
            "Hide or show" to R.drawable.ic_head_look,
            "Hidden groups" to R.drawable.ic_head_look,
            "Favorites" to R.drawable.ic_head_star,
            "Order" to R.drawable.ic_head_list,
            "Updates" to R.drawable.ic_refresh,
            "Storage" to R.drawable.ic_head_download,
            // The Settings tab's Live TV window, beside its Remote and Playback.
            "Full screen" to R.drawable.ic_fullscreen,
            "Multiview" to R.drawable.ic_multiview,
            // A line icon like the others (the rail's filled guide glyph looks heavier at this size).
            "Guide" to R.drawable.ic_epg,
            "Customs" to R.drawable.ic_head_star,
            "Downloaded before" to R.drawable.ic_head_download,
            "Search results" to R.drawable.ic_head_search,
            // Films & Shows' sources: Settings › Add-ons and This TV, as the Settings tab shows them.
            "Add-ons" to R.drawable.ic_server,
            "Torrentio" to R.drawable.ic_server,
            "MediaFusion" to R.drawable.ic_server,
            "StremThru Torz" to R.drawable.ic_server,
            "This TV" to R.drawable.ic_display,
            "Best for this TV" to R.drawable.ic_head_star,
            // The Sources window's top: the copy playing, the Best, or what Next source plays.
            "Best so far" to R.drawable.ic_head_star,
            "Playing" to R.drawable.ic_head_play,
            "Next source" to R.drawable.ic_head_skip,
            "Sound formats" to R.drawable.ic_audio,
        )

        fun headingIcon(name: String): Int? = HEADING_ICONS[name]

        /** A small text-entry window; the Fire TV keyboard opens for it. [hint] shows in the empty box. */
        fun prompt(
            activity: Activity,
            title: String,
            initial: String,
            password: Boolean = false,
            inputType: Int = InputType.TYPE_CLASS_TEXT,
            hint: String? = null,
            /** A secret typed in the open (an add-on link with a key): the keyboard neither suggests nor learns it. */
            unlearned: Boolean = false,
            onDone: (String) -> Unit,
        ) = FireDialog.prompt(activity, title, initial, password, inputType, hint, unlearned, onDone)
    }
}

/**
 * Notes such as "HDTV · 23.976 FPS · 165k downloads · English": wrapped between the parts, never inside one,
 * so no line ends on a dangling dot.
 */
private class FactsText(context: android.content.Context, private val parts: List<String>) : android.widget.TextView(context) {
    private var laidFor = -1

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        if (width > 0 && width != laidFor) {
            laidFor = width
            val lines = ArrayList<String>()
            var line = ""
            parts.map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
                val joined = if (line.isEmpty()) part else "$line · $part"
                if (line.isEmpty() || paint.measureText(joined) <= width) line = joined
                else { lines += line; line = part }
            }
            if (line.isNotEmpty()) lines += line
            text = lines.joinToString("\n")
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
