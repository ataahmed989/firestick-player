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
 * The see-through settings window on the right third of the screen.
 * Each window is drawn by a render function; [refresh] redraws it in place
 * and keeps the focused row and scroll position.
 */
class SidePanel(private val activity: Activity) {

    val root: View = activity.findViewById(R.id.panel)
    private val title: TextView = activity.findViewById(R.id.panel_title)
    private val scroll: CenterScrollView = activity.findViewById(R.id.panel_scroll)
    private val list: LinearLayout = activity.findViewById(R.id.panel_list)
    private val rows = Rows(activity)

    private var renderer: (SidePanel.() -> Unit)? = null
    private var onClose: (() -> Unit)? = null

    val isOpen: Boolean get() = root.visibility == View.VISIBLE

    /** Counts the times a window was opened, so a choice kept "while it's open" can start afresh the next time. */
    var session = 0
        private set

    /** The row last selected, by what it says: the one to come back to after a small window over this one. */
    private var lastKey: Pair<String, Int>? = null

    init {
        // About a third of the screen, as in the design.
        val width = (activity.resources.displayMetrics.widthPixels * 0.31f).toInt()
        root.layoutParams = root.layoutParams.apply { this.width = width }
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            if (now == null || !isOpen) return@addOnGlobalFocusChangeListener
            val rows = focusables()
            val i = rows.indexOf(now)
            if (i >= 0) lastKey = rowKey(rows, i)
        }
    }

    fun open(titleText: String, onClose: (() -> Unit)? = null, render: SidePanel.() -> Unit) {
        this.onClose = onClose
        renderer = render
        session++
        title.text = titleText
        Glass.background(root, Glass.Kind.PANEL)
        root.visibility = View.VISIBLE
        root.alpha = 0f
        root.translationX = rows.dp(24).toFloat()
        // Slides in as one drawn layer, easing out, so a window full of rows doesn't stutter on the way.
        root.animate().alpha(1f).translationX(0f).setDuration(180)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).withLayer().start()
        scroll.scrollTo(0, 0)
        rebuild(focusIndex = 0)
    }

    fun close() {
        if (!isOpen) return
        root.animate().cancel()
        root.visibility = View.GONE
        list.removeAllViews()
        renderer = null
        val callback = onClose
        onClose = null
        callback?.invoke()
        // Hiding the window briefly hands focus to the first row on screen; skip its fade so it doesn't flash.
        activity.window.decorView.jumpDrawablesToCurrentState()
    }

    fun setTitle(text: String) {
        title.text = text
    }

    /**
     * Redraws the window, keeping focus on the same row and the list where it was. The row is found again by what it
     * says, not where it was: a row that appears or goes above it (Undo, say) mustn't move the highlight onto another
     * row, where the next press of OK would land.
     */
    fun refresh() {
        if (!isOpen) return
        val rows = focusables()
        val index = rows.indexOfFirst { it.hasFocus() }
        // A small window (a list of choices) has the selection: the row that opened it is kept by what it says.
        val key = if (index >= 0) rowKey(rows, index) else if (FireDialog.isShowing(activity)) lastKey else null
        rebuild(if (index >= 0) index else 0, key)
    }

    private fun rebuild(focusIndex: Int, key: Pair<String, Int>? = null) {
        // A small window open over this one keeps the selection; the row is only noted, to come back to when it closes.
        val behind = FireDialog.isShowing(activity)
        // Park focus on the window itself while its rows are replaced, so it can't wander off.
        root.isFocusable = !behind
        if (!behind) root.requestFocus()
        val y = scroll.scrollY
        list.removeAllViews()
        renderer?.invoke(this)
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
        }
        root.isFocusable = false
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

    fun focusRow(index: Int) {
        val focusRows = focusables()
        if (index in focusRows.indices) focusRows[index].requestFocus()
    }

    // ---- Building blocks -------------------------------------------------

    /**
     * A section's heading: small caps with a light blue line icon before it ([icon]; by default the one for this
     * heading's name, [headingIcon]). The rows under it form one card ([RowGroups]).
     */
    fun header(text: String, icon: Int? = headingIcon(text)) {
        val line = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // In line with the rows' labels inside their card.
            setPadding(rows.dp(12), 0, 0, 0)
        }
        if (icon != null) {
            line.addView(ImageView(activity).apply {
                setImageResource(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.heading_icon))
            }, LinearLayout.LayoutParams(rows.dp(13), rows.dp(13)).apply { marginEnd = rows.dp(7) })
        }
        line.addView(TextView(activity).apply {
            this.text = text.uppercase()
            typeface = ResourcesCompat.getFont(activity, R.font.figtree_700)
            letterSpacing = 0.1f
            setTextColor(ContextCompat.getColor(activity, R.color.heading))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        list.addView(line, rows.listParams(top = 12, bottom = 4))
    }

    /** A note under the rows; [accent] makes it blue, for something that needs doing, such as a change to save. */
    fun message(text: String, accent: Boolean = false) {
        val tv = TextView(activity).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(activity, if (accent) R.color.accent else R.color.text_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(2f * rows.density, 1f)
        }
        list.addView(tv, rows.listParams(top = 4, bottom = 4))
    }

    /**
     * A tappable row. [checked] shows the blue tick used for the current choice;
     * [value] is shown on the right. A row that isn't [enabled] is dimmed and skipped by the D-pad.
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
            row.alpha = 0.38f
        }
        add(row, groupable = enabled)
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
     * A row with a small arrow button beside it, which shows or hides what belongs to it ([expanded]: pointing
     * down, otherwise right). Right on the D-pad reaches the arrow, Left goes back; up and down pass both.
     * [turned] turns the arrow from where it was, after a press.
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
            val to = if (expanded) 0f else -90f
            rotation = if (turned) (if (expanded) -90f else 0f) else to
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
     * A row that shows or hides what's under it: an arrow pointing down while closed and up while open, and a small
     * [badge] saying how it stands (see [Rows.badge]).
     */
    fun dropdownRow(label: String, badge: String?, style: Rows.Badge, open: Boolean, onClick: () -> Unit): View {
        val row = rows.build(label, null, null)
        if (badge != null) row.addView(rows.badge(badge, style))
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

    /**
     * A row in smaller type, for a list of many (Playlists), with [trailing] after its value, such as [tickPill].
     */
    fun compactRow(label: String, value: String?, trailing: View?, onClick: () -> Unit): View {
        val row = rows.build(label, value, null)
        for (i in 0 until row.childCount) (row.getChildAt(i) as? TextView)?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
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
                alpha = 0.72f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = rows.dp(2) }
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

    /** Something shown in the list that isn't a row, such as a progress bar: the D-pad passes it by. */
    fun view(view: View, top: Int = 2, bottom: Int = 2) {
        list.addView(view, rows.listParams(top, bottom))
    }

    /**
     * A setting with several values: shows the current one and opens a small picker window. [alsoChecked] is ticked
     * there too: what the current value stands for (Auto's own pick).
     */
    fun choice(label: String, options: List<String>, selected: Int, alsoChecked: Int? = null, onPick: (Int) -> Unit): View {
        // Always the value in words, even "Off" (no subtitle language): this row opens a picker, it isn't a switch.
        val row = rows.build(label, options.getOrNull(selected) ?: "", null, asSwitch = false)
        row.setOnClickListener {
            FireDialog.choose(activity, label, options, selected, besidePanel = true, alsoChecked = alsoChecked) { index ->
                if (index != selected) onPick(index)
                refresh()
            }
        }
        add(row)
        return row
    }

    /** A row adjusted with left / right on the D-pad, like "Delay  ‹ 0 ms ›". */
    fun stepper(label: String, value: String, onStep: (direction: Int) -> Unit): View {
        // The value and its arrows in blue, like the other values that can be changed right here.
        val row = rows.build(label, "‹  $value  ›", null, valueColors = rows.stepColors)
        add(row) { keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) {
                false
            } else {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    onStep(if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                    refresh()
                }
                true
            }
        }
        return row
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
        /** The icon beside each heading, by its name; headings not listed have none. */
        private val HEADING_ICONS = mapOf(
            "This film" to R.drawable.ic_head_film,
            "This episode" to R.drawable.ic_head_film,
            "In this video" to R.drawable.ic_head_film,
            "Remote" to R.drawable.ic_head_remote,
            "Look" to R.drawable.ic_head_look,
            "Skipping" to R.drawable.ic_head_skip,
            "Defaults" to R.drawable.ic_head_lang,
            "Playback" to R.drawable.ic_head_play,
            "TiviMate titles" to R.drawable.ic_head_tv,
            "Playlists" to R.drawable.ic_head_list,
            "Subtitle font" to R.drawable.ic_head_font,
            "Online subtitles and TMDB API" to R.drawable.ic_head_subs,
            "Other subtitles" to R.drawable.ic_head_subs,
            "Skip intro and credits" to R.drawable.ic_head_intro,
            "App" to R.drawable.ic_head_app,
            "Customs" to R.drawable.ic_head_star,
            "Downloaded before" to R.drawable.ic_head_download,
            "Search results" to R.drawable.ic_head_search,
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
            onDone: (String) -> Unit,
        ) = FireDialog.prompt(activity, title, initial, password, inputType, hint, onDone)
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
