package app.fireplayer.ui

import android.app.Activity
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
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

    init {
        // About a third of the screen, as in the design.
        val width = (activity.resources.displayMetrics.widthPixels * 0.31f).toInt()
        root.layoutParams = root.layoutParams.apply { this.width = width }
    }

    fun open(titleText: String, onClose: (() -> Unit)? = null, render: SidePanel.() -> Unit) {
        this.onClose = onClose
        renderer = render
        title.text = titleText
        root.visibility = View.VISIBLE
        root.alpha = 0f
        root.translationX = rows.dp(24).toFloat()
        root.animate().alpha(1f).translationX(0f).setDuration(160).start()
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
    }

    fun setTitle(text: String) {
        title.text = text
    }

    /** Redraws the window, keeping focus on the same row and the list where it was. */
    fun refresh() {
        if (!isOpen) return
        val index = focusables().indexOfFirst { it.hasFocus() }
        rebuild(if (index >= 0) index else 0)
    }

    private fun rebuild(focusIndex: Int) {
        // Park focus on the window itself while its rows are replaced, so it can't wander off.
        root.isFocusable = true
        root.requestFocus()
        val y = scroll.scrollY
        list.removeAllViews()
        renderer?.invoke(this)
        scroll.scrollTo(0, y)
        val focusRows = focusables()
        if (focusRows.isNotEmpty()) focusRows[focusIndex.coerceIn(0, focusRows.size - 1)].requestFocus()
        root.isFocusable = false
    }

    private fun focusables(): List<View> =
        (0 until list.childCount).map { list.getChildAt(it) }.filter { it.isFocusable }

    fun focusRow(index: Int) {
        val focusRows = focusables()
        if (index in focusRows.indices) focusRows[index].requestFocus()
    }

    // ---- Building blocks -------------------------------------------------

    fun header(text: String) {
        val tv = TextView(activity).apply {
            this.text = text.uppercase()
            typeface = ResourcesCompat.getFont(activity, R.font.figtree_700)
            letterSpacing = 0.1f
            setTextColor(ContextCompat.getColor(activity, R.color.text_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        list.addView(tv, rows.listParams(top = 9, bottom = 3))
    }

    fun message(text: String) {
        val tv = TextView(activity).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(activity, R.color.text_muted))
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
        onClick: () -> Unit,
    ): View {
        val row = rows.build(label, value, if (checked) R.drawable.ic_check else null)
        row.isSelected = checked
        if (enabled) {
            row.setOnClickListener { onClick() }
        } else {
            row.isFocusable = false
            row.isClickable = false
            row.alpha = 0.38f
        }
        add(row)
        return row
    }

    /** A setting with several values: shows the current one and opens a small picker window. */
    fun choice(label: String, options: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val value = options.getOrNull(selected) ?: ""
        return row(label, value) {
            FireDialog.choose(activity, label, options, selected, besidePanel = true) { index ->
                onPick(index)
                refresh()
            }
        }
    }

    /** A row adjusted with left / right on the D-pad, like "Delay  ‹ 0 ms ›". */
    fun stepper(label: String, value: String, onStep: (direction: Int) -> Unit): View {
        val row = rows.build(label, "‹  $value  ›", null)
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

    private fun add(row: View, extraKeys: ((Int, KeyEvent) -> Boolean)? = null) {
        row.setOnKeyListener { v, keyCode, event ->
            extraKeys?.invoke(keyCode, event) == true || Rows.verticalNav(list, v, keyCode, event)
        }
        list.addView(row, rows.listParams())
    }

    companion object {
        /** A small text-entry window; the Fire TV keyboard opens for it. */
        fun prompt(
            activity: Activity,
            title: String,
            initial: String,
            password: Boolean = false,
            onDone: (String) -> Unit,
        ) = FireDialog.prompt(activity, title, initial, password, onDone)
    }
}
