package app.fireplayer.vod.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.fireplayer.R
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Buttons
import app.fireplayer.ui.Glass
import app.fireplayer.ui.Rows

/**
 * The season dropdown of a show's title page (Design-References/redesign-2026-11-19): a glass pill saying the season
 * that shows ("Season 2 ⌄", [button]) and, on OK, a small glass list of the show's seasons under it ([SeasonList]),
 * Specials last. They replace the row of season pills.
 */
object SeasonDropdown {

    /** The dropdown itself: one of the app's pill buttons (white with blue words when chosen), a chevron after its words. */
    fun button(context: Context, t: Tokens): Buttons.ActionPill {
        val spec = Buttons.PillSpec(height = VodSizes.SEASON_H, pad = VodSizes.SEASON_PAD, text = VodSizes.SEASON)
        return Buttons.ActionPill(context, t, spec).apply {
            val chevron = ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_down)
                imageTintList = Buttons.buttonColors(context)
                isDuplicateParentStateEnabled = true
            }
            val size = t.dp(VodSizes.SEASON_ICON)
            addView(chevron, LinearLayout.LayoutParams(size, size).apply { leftMargin = t.dp(VodSizes.SEASON_ICON_GAP) })
        }
    }
}

/**
 * The season dropdown's list: a small window over the page ([Glass.overlay]: the page behind it, blurred, in the glass
 * look; the classic popup otherwise), a row for each season, the one that shows ticked. Up and Down move between the
 * rows without wrapping; with more seasons than [VodSizes.SEASON_LIST_ROWS] the rows glide inside the window as focus
 * nears its ends. OK picks a season (on its release) and closes the list; Back closes it (the page's Back ladder calls
 * [close]); anything else taking focus closes it too. It holds every key that moves focus while it's open.
 */
@SuppressLint("ViewConstructor")
class SeasonList(context: Context, private val t: Tokens) : FrameLayout(context) {

    private class Slot(val view: LinearLayout, val text: TextView, val check: ImageView)

    private val rows = Rows(context)
    private val window = ClipBox(context)
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val slots = ArrayList<Slot>()
    private val rowH = t.dp(VodSizes.SEASON_LIST_ROW_H)
    private val rowGap = t.dp(VodSizes.SEASON_LIST_ROW_GAP)
    private var count = 0
    /** The first row showing in the window. */
    private var first = 0

    var isOpen = false
        private set

    /** The seasons it shows (the page closes it when they change under it). */
    var labels: List<String> = emptyList()
        private set

    private var onPick: ((Int) -> Unit)? = null
    private var onClose: ((focusBack: Boolean) -> Unit)? = null
    /** False until OK is pressed anew here. */
    private var armed = false

    // Anything else taking focus (the settings window, a notice's page) closes it, leaving focus there.
    private val watch = ViewTreeObserver.OnGlobalFocusChangeListener { _, now ->
        if (isOpen && (now == null || !isInside(now))) close(focusBack = false)
    }

    init {
        visibility = GONE
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        val pad = t.dp(VodSizes.SEASON_LIST_PAD)
        setPadding(pad, pad, pad, pad)
        window.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, rowH))
        addView(window, LayoutParams(t.dp(VodSizes.SEASON_LIST_W), rowH))
        applyLook()
    }

    /** The look's surfaces: a small window's glass over the page (or the classic popup), its rows the app's list rows. */
    fun applyLook() {
        Glass.overlay(this, Glass.Kind.POPUP)
        for (s in slots) Glass.background(s.view, Glass.Kind.ROW)
    }

    /**
     * Opens with [names] under [anchor] (the dropdown, in the parent's coordinates), kept inside [area]; row
     * [selected] (the season that shows) is ticked and takes focus. [onPick]: OK on a row, once the list has closed.
     * [onClose]: as it closes, to put focus back ([focusBack] false when something else took it).
     */
    fun open(names: List<String>, selected: Int, anchor: RectF, area: RectF, onClose: (focusBack: Boolean) -> Unit, onPick: (Int) -> Unit) {
        if (names.isEmpty() || parent !is View) return
        animate().cancel()
        labels = names
        count = names.size
        this.onPick = onPick
        this.onClose = onClose
        while (slots.size < count) slots += makeSlot()
        val at = selected.coerceIn(0, count - 1)
        for (i in slots.indices) {
            val s = slots[i]
            if (i < count) {
                if (s.text.text.toString() != names[i]) s.text.text = names[i]
                s.view.contentDescription = names[i]
                s.check.visibility = if (i == at) VISIBLE else INVISIBLE
                s.view.visibility = VISIBLE
            } else {
                s.view.visibility = GONE
            }
        }
        val shown = minOf(count, VodSizes.SEASON_LIST_ROWS)
        window.layoutParams = window.layoutParams.apply { height = shown * rowH + (shown - 1) * rowGap }
        column.layoutParams = column.layoutParams.apply { height = count * rowH + (count - 1) * rowGap }
        // The season that shows starts in the middle of the rows in view.
        first = VodSizes.seasonListFirst(at, at - VodSizes.SEASON_LIST_ROWS / 2, count)
        column.animate().cancel()
        column.translationY = -first * (rowH + rowGap).toFloat()
        // Placed by its layout, not a transform: its glass lines the page behind it up with where it's laid out.
        val w = t.dpf(VodSizes.SEASON_LIST_W + 2 * VodSizes.SEASON_LIST_PAD)
        val h = t.dpf(VodSizes.seasonListH(count))
        val x = anchor.left.coerceIn(area.left, maxOf(area.left, area.right - w))
        val under = anchor.bottom + t.dpf(VodSizes.SEASON_LIST_GAP)
        val y = if (under + h <= area.bottom) under else maxOf(area.top, area.bottom - h)
        (layoutParams as? LayoutParams)?.let { lp ->
            lp.gravity = Gravity.TOP or Gravity.LEFT
            lp.leftMargin = x.toInt()
            lp.topMargin = y.toInt()
            layoutParams = lp
        }
        isOpen = true
        armed = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        alpha = 0f
        visibility = VISIBLE
        slots[at].view.requestFocus()
        viewTreeObserver.addOnGlobalFocusChangeListener(watch)
        animate().alpha(1f).setDuration(VodSizes.SEASON_LIST_MS).start()
    }

    /**
     * Closes it: focus goes back first ([focusBack], see [open]), so the rows fading out never hold it. [fade] false:
     * gone at once.
     */
    fun close(fade: Boolean = true, focusBack: Boolean = true) {
        if (!isOpen) return
        isOpen = false
        viewTreeObserver.removeOnGlobalFocusChangeListener(watch)
        val then = onClose
        onClose = null
        onPick = null
        then?.invoke(focusBack)
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        animate().cancel()
        if (!fade || !isShown) {
            visibility = GONE
            return
        }
        animate().alpha(0f).setDuration(VodSizes.SEASON_LIST_MS).withEndAction { if (!isOpen) visibility = GONE }.start()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isOpen) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down) {
                    if (event.repeatCount == 0) armed = true
                } else if (event.action == KeyEvent.ACTION_UP && armed) {
                    armed = false
                    val i = focusedRow()
                    if (!event.isCanceled && i >= 0) pick(i)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down) {
                    armed = false
                    val next = focusedRow() + if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                    if (next in 0 until count) slots[next].view.requestFocus()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun pick(i: Int) {
        val then = onPick
        close()
        then?.invoke(i)
    }

    private fun focusedRow(): Int = (0 until count).firstOrNull { slots[it].view.isFocused } ?: -1

    /** Row [i] took focus: the rows glide just far enough for it to show. */
    private fun reveal(i: Int) {
        val f = VodSizes.seasonListFirst(i, first, count)
        if (f == first) return
        first = f
        column.animate().translationY(-f * (rowH + rowGap).toFloat()).setDuration(VodSizes.SEASON_LIST_MS).start()
    }

    private fun makeSlot(): Slot {
        // OK is read here ([dispatchKeyEvent], on its release): never View's own click.
        val view = rows.base().apply { isClickable = false }
        val text = rows.text("")
        val check = rows.icon(R.drawable.ic_check, CHECK_DP)
        view.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        view.addView(check)
        val index = slots.size
        column.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, rowH).apply { if (index > 0) topMargin = rowGap })
        view.setOnFocusChangeListener { _, has -> if (has && isOpen) reveal(index) }
        return Slot(view, text, check)
    }

    private fun isInside(v: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === this) return true
            p = p.parent as? View
        }
        return false
    }

    private companion object {
        const val CHECK_DP = 14
    }
}
