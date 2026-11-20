package app.fireplayer.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.fireplayer.R
import kotlin.math.roundToInt

/** One entry of a long window ([SidePanel.longList]): a row, a heading over the rows after it, or a note. */
class ListEntry(
    /** What the entry is, whatever its words say or where it moves to: focus comes back to it by this. */
    val key: String,
    val label: String,
    /** Shown on the right, as [SidePanel.row]'s value ("On" / "Off" is a switch with the glass look). */
    val value: String? = null,
    val kind: Kind = Kind.ROW,
    /** An icon at the row's end (a favorite's star). */
    val icon: Int? = null,
    /** Blue, like the current choice in a list of choices (a favorite). */
    val chosen: Boolean = false,
    /** Its words fainter: still there to press, but set apart (a hidden group). */
    val faded: Boolean = false,
    /**
     * Drawn as a row that can't be used just now ([SidePanel.dim]): still focusable, so focus doesn't jump past it, but
     * its press does nothing (Reorder groups with fewer than two shown).
     */
    val dimmed: Boolean = false,
    /** Nested under the row above it: indented, with a dot ([asSubRow]). */
    val sub: Boolean = false,
    /** A small button beside the row, as [SidePanel.rowWithButton]'s, read out as [buttonLabel]. */
    val button: Int? = null,
    val buttonLabel: String = "",
    /**
     * A second, fainter line under the label (a source's facts in the Sources panel): the row is then two lines, its
     * [value] on the right up to two lines too.
     */
    val note: String? = null,
) {
    enum class Kind { ROW, HEADING, NOTE }
}

/**
 * What a long window lists and what its rows do ([SidePanel.longList]). Entries are read as their rows come on
 * screen, so only those are ever made: a window of 350 groups opens at once and scrolls smoothly. A change to one
 * entry redraws that row alone ([SidePanel.changed]).
 */
interface LongList {
    val size: Int
    fun entry(index: Int): ListEntry
    /** OK on a row. */
    fun onClick(index: Int)
    /** OK on the small button beside a row. */
    fun onButton(index: Int) {}
    /** Sees a row's keys first (a row being moved takes Up and Down); true when used. */
    fun onKey(index: Int, keyCode: Int, event: KeyEvent): Boolean = false
}

/** A long list's arithmetic, apart from the screen so it can be tested. */
object ListMath {

    /** Where a row sits in its card ([RowGroups]), from whether the entries just before and after it are rows too. */
    fun cardPos(rowBefore: Boolean, rowAfter: Boolean): RowGroups.Pos = when {
        !rowBefore && !rowAfter -> RowGroups.Pos.SINGLE
        !rowBefore -> RowGroups.Pos.FIRST
        !rowAfter -> RowGroups.Pos.LAST
        else -> RowGroups.Pos.MIDDLE
    }

    /**
     * The row Down (or Up) goes to from [from], past headings and notes; at either end, round to the other end, as
     * the other windows do, unless the key is [held]: then it stops there. -1 when it stays.
     */
    fun step(from: Int, size: Int, down: Boolean, held: Boolean, isRow: (Int) -> Boolean): Int {
        val dir = if (down) 1 else -1
        var i = from + dir
        while (i in 0 until size) {
            if (isRow(i)) return i
            i += dir
        }
        if (held) return -1
        i = if (down) 0 else size - 1
        while (i in 0 until size && i != from) {
            if (isRow(i)) return i
            i += dir
        }
        return -1
    }

    /** The first row at or after [from], else the last one before it; -1 when there's none. */
    fun rowNear(from: Int, size: Int, isRow: (Int) -> Boolean): Int {
        if (size <= 0) return -1
        val start = from.coerceIn(0, size - 1)
        for (i in start until size) if (isRow(i)) return i
        for (i in start - 1 downTo 0) if (isRow(i)) return i
        return -1
    }

    /**
     * How far to scroll so a row from [top] to [bottom] is in the middle of a list [height] tall, but no further than
     * the list goes: [roomAbove] is how far it can still scroll back towards its start and [roomBelow] on towards its
     * end (null: more than any step, its end isn't even on screen).
     */
    fun centerBy(top: Int, bottom: Int, height: Int, roomAbove: Int?, roomBelow: Int?): Int {
        val dy = (top + bottom) / 2 - height / 2
        return when {
            dy < 0 -> if (roomAbove == null) dy else maxOf(dy, -roomAbove.coerceAtLeast(0))
            dy > 0 -> if (roomBelow == null) dy else minOf(dy, roomBelow.coerceAtLeast(0))
            else -> 0
        }
    }
}

/** A section's heading ([SidePanel.header]): small caps with a light blue line icon before it. */
internal class HeadingLine(context: Context, rows: Rows) : LinearLayout(context) {
    private val iconView = ImageView(context).apply {
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.heading_icon))
    }
    private val textView = TextView(context).apply {
        typeface = ResourcesCompat.getFont(context, R.font.figtree_700)
        letterSpacing = 0.1f
        setTextColor(ContextCompat.getColor(context, R.color.heading))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // In line with the rows' labels inside their card.
        setPadding(rows.dp(12), 0, 0, 0)
        addView(iconView, LayoutParams(rows.dp(13), rows.dp(13)).apply { marginEnd = rows.dp(7) })
        addView(textView, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    fun set(text: String, icon: Int?) {
        textView.text = text.uppercase()
        if (icon != null) iconView.setImageResource(icon)
        iconView.visibility = if (icon != null) View.VISIBLE else View.GONE
    }
}

/** A note under the rows ([SidePanel.message]); [accent] makes it blue. */
internal fun noteText(context: Context, rows: Rows, accent: Boolean = false): TextView = TextView(context).apply {
    setTextColor(ContextCompat.getColor(context, if (accent) R.color.accent else R.color.text_muted))
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    setLineSpacing(2f * rows.density, 1f)
}

/**
 * A long window's rows ([SidePanel.longList]), in place of the window's scrolling list: only the rows on screen exist,
 * shown again for other entries as the list scrolls. They are made by the same [Rows] and drawn as the same cards
 * ([RowGroups]) as the other windows' rows, and the D-pad moves through them the same way. The selected row is kept
 * in the middle, gliding there as [CenterScrollView] does (one motion that keeps its speed while a key is held).
 */
@SuppressLint("ViewConstructor")
internal class LongListView(private val activity: Activity, private val rows: Rows) : RecyclerView(activity) {

    var list: LongList? = null
        private set

    /** The entry whose row (or its button) is selected, or -1. */
    var focused = -1
        private set

    /** A row took focus: its entry's key and place, noted by the window to come back to. */
    var onFocused: ((key: String, index: Int) -> Unit)? = null

    private val manager = Manager(activity)
    private var look = Glass.on

    // Our own glide, in steps of whole pixels: [applied] is how far it has scrolled since it set off.
    private var applied = 0f
    private val glide = Spring(omega = 20f) { v ->
        val step = (v - applied).roundToInt()
        if (step != 0) {
            applied += step
            scrollBy(0, step)
        }
    }

    /** An entry to put focus on once its row is laid out (a window opening, a jump far down the list), else -1. */
    private var pending = -1
    private var pendingBehind = false
    private var tries = 0
    private var landed: ((Boolean) -> Unit)? = null
    /** The next row to take focus jumps to the middle instead of gliding there. */
    private var snapNext = false
    private var listening = false

    private val landing = ViewTreeObserver.OnPreDrawListener {
        land()
        true
    }

    init {
        layoutManager = manager
        // Rows change in place, with no fading copies.
        itemAnimator = null
        setHasFixedSize(true)
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        // The same soft edges as the other windows' list (drawn by the list itself, so it must draw).
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(rows.dp(24))
        setWillNotDraw(false)
        clipToPadding = false
        setPadding(0, rows.dp(2), 0, rows.dp(2))
        // Enough spare rows for a whole window: a window redrawn or opened over another reuses them all.
        recycledViewPool.setMaxRecycledViews(TYPE_ROW, 32)
        recycledViewPool.setMaxRecycledViews(TYPE_PAIR, 32)
        recycledViewPool.setMaxRecycledViews(TYPE_HEADING, 6)
        recycledViewPool.setMaxRecycledViews(TYPE_NOTE, 4)
        adapter = EntryAdapter()
    }

    /**
     * Shows [list]. A window opened or come back to starts on [focusKey]'s entry (else the row nearest [focusIndex])
     * in the middle; one redrawn in place ([keepPlace]) keeps its rows where they were. Focus lands there once the
     * rows are laid out, then [onLanded] runs (true when a row took it, false when there's none). [behind]: a small
     * window over this one has the selection; the row is only noted for it to come back to.
     */
    fun show(list: LongList, focusKey: String?, focusIndex: Int, keepPlace: Boolean, behind: Boolean, onLanded: (Boolean) -> Unit) {
        this.list = list
        focused = -1
        stopGlide()
        if (look != Glass.on) {
            // Made again in the look now chosen (its rows and cards are each look's own).
            look = Glass.on
            recycledViewPool.clear()
            adapter = EntryAdapter()
        } else {
            @Suppress("NotifyDataSetChanged")
            adapter?.notifyDataSetChanged()
        }
        val byKey = focusKey?.let { indexOf(it) } ?: -1
        val target = if (byKey >= 0) byKey else ListMath.rowNear(focusIndex, list.size) { isRow(it) }
        if (target >= 0 && !keepPlace) manager.scrollToPositionWithOffset(target, middleOffset())
        landed = onLanded
        land(target, behind)
    }

    /** Leaves nothing shown (the window closed); the rows wait in the pool for the next long window. */
    fun clear() {
        list = null
        focused = -1
        pending = -1
        landed = null
        stopGlide()
        @Suppress("NotifyDataSetChanged")
        adapter?.notifyDataSetChanged()
    }

    /** [count] entries from [index] changed: their rows are shown again in place (focus stays where it is). */
    fun changed(index: Int, count: Int) {
        val n = list?.size ?: return
        val from = index.coerceAtLeast(0)
        val to = minOf(n, index + count)
        if (to > from) adapter?.notifyItemRangeChanged(from, to - from)
    }

    fun indexOf(key: String): Int {
        val l = list ?: return -1
        for (i in 0 until l.size) if (l.entry(i).key == key) return i
        return -1
    }

    /** The entry selected, or the one focus is on its way to. */
    fun currentIndex(): Int = if (pending >= 0) pending else focused

    fun currentKey(): String? = list?.let { l -> currentIndex().takeIf { it in 0 until l.size }?.let { l.entry(it).key } }

    /** Focus to [index]'s row: gliding there when it's on screen, else jumping there. */
    fun focusEntry(index: Int) {
        val l = list ?: return
        if (index !in 0 until l.size) return
        val holder = findViewHolderForAdapterPosition(index) as? Holder
        val row = holder?.row
        if (pending < 0 && row != null && holder.itemView.isLaidOut) {
            row.requestFocus()
            return
        }
        // Off screen (or not laid out yet): no glide past hundreds of rows; the list jumps there.
        manager.scrollToPositionWithOffset(index, middleOffset())
        land(index, behind = false)
    }

    /** Focus back into the list after it slipped out: the row it was on, else the first one on screen. */
    fun focusSomething() {
        val l = list ?: return
        if (focused in 0 until l.size) return focusEntry(focused)
        val first = manager.findFirstVisibleItemPosition().coerceAtLeast(0)
        val i = ListMath.rowNear(first, l.size) { isRow(it) }
        if (i >= 0) focusEntry(i)
    }

    private fun isRow(i: Int): Boolean = list?.entry(i)?.kind == ListEntry.Kind.ROW

    /** Where a row put [scrollToPositionWithOffset] starts so it's about in the middle (the glide puts it exactly). */
    private fun middleOffset(): Int {
        val h = if (height > 0) height else (resources.displayMetrics.heightPixels * 0.8f).toInt()
        return ((h - rows.dp(37)) / 2).coerceAtLeast(0)
    }

    // ---- Focus landing after a layout ------------------------------------------------------------------------------

    private fun land(index: Int, behind: Boolean) {
        pending = index
        pendingBehind = behind
        tries = 0
        if (!listening) {
            listening = true
            viewTreeObserver.addOnPreDrawListener(landing)
        }
        // A frame is drawn even if nothing else asked for one.
        invalidate()
    }

    /** Just before the frame is drawn (the rows are laid out by then): focus onto the waiting entry's row. */
    private fun land() {
        val i = pending
        val holder = if (i >= 0) findViewHolderForAdapterPosition(i) as? Holder else null
        val row = holder?.row
        if (i >= 0 && row == null && ++tries < 3 && list != null) {
            // Not laid out in this frame yet: the next one.
            invalidate()
            return
        }
        listening = false
        viewTreeObserver.removeOnPreDrawListener(landing)
        pending = -1
        if (row != null) {
            // A small window opened over this one meanwhile keeps the selection too.
            if (pendingBehind || FireDialog.isShowing(activity)) {
                FireDialog.returnFocusTo(activity, row)
            } else {
                snapNext = true
                row.requestFocus()
                snapNext = false
                // Already the selected one: white at once instead of fading in.
                row.jumpDrawablesToCurrentState()
            }
        }
        landed?.invoke(row != null)
        landed = null
    }

    override fun onDetachedFromWindow() {
        stopGlide()
        if (listening) {
            listening = false
            viewTreeObserver.removeOnPreDrawListener(landing)
        }
        super.onDetachedFromWindow()
    }

    // ---- Keeping the selected row in the middle -------------------------------------------------------------------

    private fun stopGlide() {
        glide.stop()
        applied = 0f
    }

    /** Glides (or jumps) so [child], one of the list's rows, sits in the middle, no further than the list goes. */
    fun center(child: View, animate: Boolean) {
        if (height == 0 || !child.isLaidOut) return
        val count = adapter?.itemCount ?: 0
        if (count == 0) return
        val first = manager.findViewByPosition(0)
        val last = manager.findViewByPosition(count - 1)
        val roomAbove = first?.let { paddingTop - (manager.getDecoratedTop(it) - (it.layoutParams as MarginLayoutParams).topMargin) }
        val roomBelow = last?.let { manager.getDecoratedBottom(it) + (it.layoutParams as MarginLayoutParams).bottomMargin - (height - paddingBottom) }
        val dy = ListMath.centerBy(child.top, child.bottom, height, roomAbove, roomBelow)
        if (!animate) {
            stopGlide()
            if (dy != 0) scrollBy(0, dy)
            return
        }
        if (dy == 0 && !glide.moving) return
        // From where it is now: a glide still on its way carries its speed into this one.
        if (!glide.moving) {
            applied = 0f
            glide.snap(0f)
        }
        glide.glideTo(applied + dy)
    }

    private inner class Manager(context: Context) : LinearLayoutManager(context) {
        // We do our own scrolling when a row takes focus.
        override fun requestChildRectangleOnScreen(
            parent: RecyclerView,
            child: View,
            rect: Rect,
            immediate: Boolean,
            focusedChildVisible: Boolean,
        ): Boolean {
            center(child, animate = !immediate && !snapNext)
            return true
        }
    }

    // ---- Rows -----------------------------------------------------------------------------------------------------

    private inner class Holder(item: View, val type: Int) : ViewHolder(item) {
        var row: LinearLayout? = null
        var button: LinearLayout? = null
        var buttonIcon: ImageView? = null
        var heading: HeadingLine? = null
        var note: TextView? = null
    }

    /** The adapter: the entries' rows, made as they come on screen. */
    private inner class EntryAdapter : Adapter<Holder>() {

        override fun getItemCount(): Int = list?.size ?: 0

        override fun getItemViewType(position: Int): Int {
            val e = list?.entry(position) ?: return TYPE_ROW
            return when (e.kind) {
                ListEntry.Kind.HEADING -> TYPE_HEADING
                ListEntry.Kind.NOTE -> TYPE_NOTE
                ListEntry.Kind.ROW -> if (e.button != null) TYPE_PAIR else TYPE_ROW
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = when (viewType) {
            TYPE_HEADING -> {
                val line = HeadingLine(activity, rows)
                line.layoutParams = params(top = 12, bottom = 4)
                Holder(line, viewType).apply { heading = line }
            }
            TYPE_NOTE -> {
                val text = noteText(activity, rows)
                text.layoutParams = params(top = 4, bottom = 4)
                Holder(text, viewType).apply { note = text }
            }
            TYPE_PAIR -> {
                val row = rows.base()
                val icon = ImageView(activity).apply {
                    imageTintList = rows.textColors
                    isDuplicateParentStateEnabled = true
                }
                val button = rows.base().apply {
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, 0)
                    minimumHeight = 0
                    addView(icon, LinearLayout.LayoutParams(rows.dp(15), rows.dp(15)))
                }
                val pair = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    tag = ROW_PAIR
                    // The glass switch's glow reaches past its row.
                    clipChildren = false
                    clipToPadding = false
                    layoutParams = params()
                }
                pair.addView(row, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                pair.addView(button, LinearLayout.LayoutParams(rows.dp(36), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = rows.dp(6) })
                Holder(pair, viewType).also { h ->
                    h.row = row
                    h.button = button
                    h.buttonIcon = icon
                    wire(h, row)
                    wireButton(h, row, button)
                }
            }
            else -> {
                val row = rows.base()
                row.layoutParams = params()
                Holder(row, viewType).also { h ->
                    h.row = row
                    wire(h, row)
                }
            }
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val l = list ?: return
            val e = l.entry(position)
            when (holder.type) {
                TYPE_HEADING -> holder.heading?.set(e.label, SidePanel.headingIcon(e.label))
                TYPE_NOTE -> holder.note?.text = e.label
                else -> bindRow(holder, e, position, l)
            }
        }
    }

    private fun bindRow(h: Holder, e: ListEntry, position: Int, l: LongList) {
        val row = h.row ?: return
        // The row stays (its card, its focus); only what it says is made again.
        row.removeAllViews()
        val label = if (e.note != null) fillTwoLines(row, e) else rows.fill(row, e.label, e.value, e.icon)
        if (e.faded) label.alpha = FADED
        if (e.dimmed) Rows.dim(row)
        row.isSelected = e.chosen
        val inset = if (e.sub) rows.dp(RowGroups.SUB_INSET_DP) else 0
        row.setPadding(rows.dp(12) + inset, rows.dp(6), rows.dp(12), rows.dp(6))
        h.button?.contentDescription = e.buttonLabel
        e.button?.let { h.buttonIcon?.setImageResource(it) }
        val before = position > 0 && isRow(position - 1)
        val after = position < l.size - 1 && isRow(position + 1)
        RowGroups.styleListRow(h.itemView, ListMath.cardPos(before, after), e.sub) {
            val p = h.bindingAdapterPosition
            p > 0 && focused == p - 1
        }
    }

    /**
     * A two-line row ([ListEntry.note]): the label, the note under it (smaller, faint, up to two lines), and the value
     * on the right (up to two lines). The texts take the row's colours (white on the selected row). Returns the label.
     */
    private fun fillTwoLines(row: LinearLayout, e: ListEntry): TextView {
        val label = rows.text(e.label)
        val column = RowLayout(activity, rows.textColors).apply {
            orientation = LinearLayout.VERTICAL
            isDuplicateParentStateEnabled = true
            addView(label)
            addView(rows.text(e.note.orEmpty()).apply {
                isSingleLine = false
                maxLines = 2
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Rows.NOTE_SP)
                alpha = Rows.NOTE_ALPHA
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = rows.dp(Rows.NOTE_GAP_DP) }
            })
        }
        row.addView(column, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        e.value?.let { v ->
            row.addView(rows.text(v).apply {
                isSingleLine = false
                maxLines = 2
                gravity = Gravity.END
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Rows.NOTE_SP)
                alpha = Rows.VALUE_ALPHA
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = rows.dp(Rows.VALUE_GAP_DP) }
            })
        }
        return label
    }

    private fun params(top: Int = 0, bottom: Int = 0) = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = rows.dp(top)
        bottomMargin = rows.dp(bottom)
    }

    private fun wire(h: Holder, row: View) {
        row.setOnClickListener {
            val i = h.bindingAdapterPosition
            if (i != NO_POSITION) list?.onClick(i)
        }
        row.setOnFocusChangeListener { _, has -> focusChanged(h, has) }
        row.setOnKeyListener { _, keyCode, event ->
            val i = h.bindingAdapterPosition
            if (i != NO_POSITION && list?.onKey(i, keyCode, event) == true) return@setOnKeyListener true
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN && i != NO_POSITION) step(i, keyCode, event)
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) h.button?.requestFocus()
                    true
                }
                // Left / right mean nothing on other rows; used up here so focus can't leave the window.
                KeyEvent.KEYCODE_DPAD_LEFT -> true
                else -> false
            }
        }
    }

    /** The small button beside a row: Left goes back to the row, Up and Down go on from the row. */
    private fun wireButton(h: Holder, row: View, button: View) {
        button.setOnClickListener {
            val i = h.bindingAdapterPosition
            if (i != NO_POSITION) list?.onButton(i)
        }
        button.setOnFocusChangeListener { _, has -> focusChanged(h, has) }
        button.setOnKeyListener { _, keyCode, event ->
            val i = h.bindingAdapterPosition
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) row.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> true
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN && i != NO_POSITION) step(i, keyCode, event)
                    true
                }
                else -> false
            }
        }
    }

    private fun step(from: Int, keyCode: Int, event: KeyEvent) {
        val l = list ?: return
        val to = ListMath.step(from, l.size, keyCode == KeyEvent.KEYCODE_DPAD_DOWN, event.repeatCount > 0) { isRow(it) }
        if (to >= 0 && to != from) focusEntry(to)
    }

    private fun focusChanged(h: Holder, has: Boolean) {
        val i = h.bindingAdapterPosition
        if (has) {
            focused = i
            list?.let { l -> if (i in 0 until l.size) onFocused?.invoke(l.entry(i).key, i) }
        } else if (focused == i && !h.itemView.hasFocus()) {
            focused = -1
        }
        // The row under it shows or hides the line between them.
        if (i != NO_POSITION) findViewHolderForAdapterPosition(i + 1)?.itemView?.invalidate()
    }

    private companion object {
        const val TYPE_ROW = 0
        const val TYPE_PAIR = 1
        const val TYPE_HEADING = 2
        const val TYPE_NOTE = 3
        /** A faded entry's words (a hidden group). */
        const val FADED = 0.5f
    }
}
