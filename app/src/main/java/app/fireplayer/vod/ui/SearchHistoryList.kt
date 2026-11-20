package app.fireplayer.vod.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.fireplayer.R
import app.fireplayer.live.ui.PressHold
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.CenterScrollView
import app.fireplayer.ui.PageRow

/**
 * The Search tab's history under its bar: the searches made, newest first, each a pod (a Settings tab row, [PageRow],
 * in the bar's glass: [SearchBar.GLASS]) in a list that keeps the chosen row in the middle and glides there
 * ([CenterScrollView]); a short line when there are none yet. OK on a row ([onPick]) searches it again; OK held ([onHold]) opens its small window. Up from the first row goes
 * to the bar ([onUpFromTop]), Left to the rail ([onLeft]).
 */
@SuppressLint("ViewConstructor")
class SearchHistoryList(context: Context, private val t: Tokens) : FrameLayout(context) {

    var onPick: ((query: String) -> Unit)? = null
    var onHold: ((query: String, row: View) -> Unit)? = null
    var onUpFromTop: (() -> Unit)? = null
    var onLeft: (() -> Unit)? = null

    private val scroll = CenterScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(t.dp(VodSizes.SEARCH_GAP))
        clipToPadding = false
    }
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val note = t.label(context, VodSizes.NOTE).apply { text = EMPTY_NOTE }
    private val press = PressHold(ViewConfiguration.getLongPressTimeout().toLong())

    /** The searches shown, newest first. */
    var entries: List<String> = emptyList()
        private set
    private val rows = ArrayList<PageRow>()

    init {
        scroll.addView(list, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(note, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = t.dp(PageRow.PAD_DP)
        })
    }

    /** Shows [searches] (newest first): rows kept and refilled in place, so focus stays on a row that stays. */
    fun show(searches: List<String>) {
        if (searches == entries) return
        val focusedAt = rows.indexOfFirst { it.isFocused }
        // Focus off a row that goes, before it goes (a removed view holding focus would hand it to the rail's first tab):
        // to the new last row, else the bar.
        if (focusedAt >= searches.size) {
            if (searches.isNotEmpty()) rows[searches.size - 1].requestFocus() else onUpFromTop?.invoke()
        }
        entries = searches
        while (rows.size < searches.size) rows += makeRow().also { list.addView(it, rowParams()) }
        while (rows.size > searches.size) {
            val gone = rows.removeAt(rows.size - 1)
            list.removeView(gone)
        }
        searches.forEachIndexed { i, q -> rows[i].text.text = q }
        note.visibility = if (searches.isEmpty()) View.VISIBLE else View.GONE
        if (focusedAt >= 0 && !hasFocus()) focusRow(focusedAt)
    }

    /** Focus to row [i] (clamped); false when there are none. */
    fun focusRow(i: Int): Boolean {
        if (rows.isEmpty()) return false
        return rows[i.coerceIn(0, rows.size - 1)].requestFocus()
    }

    fun indexOf(row: View): Int = rows.indexOf(row)

    fun applyLook() {
        for (row in rows) row.applyLook()
    }

    private fun rowParams() = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = t.dp(PageRow.GAP_DP)
    }

    private fun makeRow(): PageRow = PageRow(context, R.drawable.ic_history, kind = SearchBar.GLASS).apply {
        setOnFocusChangeListener { _, has -> if (!has) press.cancel() }
        setOnKeyListener { v, keyCode, event -> onKey(v as PageRow, keyCode, event) }
    }

    private fun onKey(row: PageRow, keyCode: Int, event: KeyEvent): Boolean {
        val i = rows.indexOf(row)
        if (i < 0) return false
        val query = entries.getOrNull(i) ?: return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> if (press.down(event.repeatCount, event.isLongPress, event.eventTime - event.downTime)) onHold?.invoke(query, row)
                    KeyEvent.ACTION_UP -> if (press.up(event.isCanceled)) onPick?.invoke(query)
                }
                return true
            }
        }
        val dpad = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        if (event.action != KeyEvent.ACTION_DOWN) return dpad
        press.cancel()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (i == 0) onUpFromTop?.invoke() else rows[i - 1].requestFocus()
            // A held key stops at the last row.
            KeyEvent.KEYCODE_DPAD_DOWN -> rows.getOrNull(i + 1)?.requestFocus()
            KeyEvent.KEYCODE_DPAD_LEFT -> onLeft?.invoke()
            KeyEvent.KEYCODE_DPAD_RIGHT -> Unit
            else -> return false
        }
        return true
    }

    companion object {
        const val EMPTY_NOTE = "Your searches show up here"
    }
}
