package app.fireplayer.ui

import android.app.Activity
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import app.fireplayer.R

/**
 * The see-through settings window on the right third of the screen.
 * Each window is drawn by a render function; [refresh] redraws it in place
 * and keeps the focused row.
 */
class SidePanel(private val activity: Activity) {

    val root: View = activity.findViewById(R.id.panel)
    private val title: TextView = activity.findViewById(R.id.panel_title)
    private val scroll: ScrollView = activity.findViewById(R.id.panel_scroll)
    private val list: LinearLayout = activity.findViewById(R.id.panel_list)

    private val density = activity.resources.displayMetrics.density
    private val font600: Typeface? = ResourcesCompat.getFont(activity, R.font.figtree_600)
    private val font700: Typeface? = ResourcesCompat.getFont(activity, R.font.figtree_700)
    private val rowTextColors = ContextCompat.getColorStateList(activity, R.color.sel_text_row)

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
        rebuild(focusIndex = 0)
    }

    fun close() {
        if (!isOpen) return
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

    /** Redraws the window, keeping focus on the same row. */
    fun refresh() {
        val index = focusables().indexOfFirst { it.hasFocus() }
        rebuild(if (index >= 0) index else 0)
    }

    private fun rebuild(focusIndex: Int) {
        list.removeAllViews()
        renderer?.invoke(this)
        val rows = focusables()
        if (rows.isNotEmpty()) {
            val target = rows[focusIndex.coerceIn(0, rows.size - 1)]
            target.post { target.requestFocus() }
        }
    }

    private fun focusables(): List<View> =
        (0 until list.childCount).map { list.getChildAt(it) }.filter { it.isFocusable }

    fun focusRow(index: Int) {
        val rows = focusables()
        if (index in rows.indices) rows[index].requestFocus()
    }

    // ---- Building blocks -------------------------------------------------

    fun header(text: String) {
        val tv = TextView(activity).apply {
            this.text = text.uppercase()
            typeface = font700
            letterSpacing = 0.1f
            setTextColor(ContextCompat.getColor(activity, R.color.text_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        list.addView(tv, lp(top = 9, bottom = 3))
    }

    fun message(text: String) {
        val tv = TextView(activity).apply {
            this.text = text
            setTextColor(ContextCompat.getColor(activity, R.color.text_muted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setLineSpacing(2f * density, 1f)
        }
        list.addView(tv, lp(top = 4, bottom = 4))
    }

    /**
     * A tappable row. [checked] shows the blue tick used for the current choice;
     * [value] is shown on the right.
     */
    fun row(
        label: String,
        value: String? = null,
        checked: Boolean = false,
        onClick: () -> Unit,
    ): View {
        val row = baseRow()
        row.isSelected = checked
        row.addView(rowText(label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (value != null) {
            row.addView(rowText(value).apply { alpha = 0.85f })
        }
        if (checked) {
            val tick = ImageView(activity).apply {
                setImageResource(R.drawable.ic_check)
                duplicateParentState = true
                imageTintList = rowTextColors
            }
            val size = (15 * density).toInt()
            row.addView(tick, LinearLayout.LayoutParams(size, size).apply { marginStart = (8 * density).toInt() })
        }
        row.setOnClickListener { onClick() }
        list.addView(row, lp(top = 2, bottom = 2))
        return row
    }

    /** A row adjusted with left / right on the D-pad, like "Delay  ‹ 0 ms ›". */
    fun stepper(label: String, value: String, onStep: (direction: Int) -> Unit): View {
        val row = baseRow()
        row.addView(rowText(label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(rowText("‹  $value  ›"))
        row.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { onStep(-1); refresh(); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { onStep(1); refresh(); true }
                else -> false
            }
        }
        list.addView(row, lp(top = 2, bottom = 2))
        return row
    }

    fun spacer(dp: Int) {
        list.addView(View(activity), LinearLayout.LayoutParams(1, (dp * density).toInt()))
    }

    private fun baseRow(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        setBackgroundResource(R.drawable.bg_row)
        minimumHeight = (33 * density).toInt()
        val h = (12 * density).toInt()
        setPadding(h, (6 * density).toInt(), h, (6 * density).toInt())
    }

    private fun rowText(text: String) = TextView(activity).apply {
        this.text = text
        typeface = font600
        duplicateParentState = true
        setTextColor(rowTextColors)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun lp(top: Int, bottom: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = (top * density).toInt()
        bottomMargin = (bottom * density).toInt()
    }

    companion object {
        /** A small text-entry dialog; the Fire TV keyboard opens for it. */
        fun prompt(
            activity: Activity,
            title: String,
            initial: String,
            password: Boolean = false,
            onDone: (String) -> Unit,
        ) {
            val input = EditText(activity).apply {
                setText(initial)
                setSelection(initial.length)
                setSingleLine()
                setTextColor(ContextCompat.getColor(activity, R.color.text))
                inputType = if (password) {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                } else {
                    InputType.TYPE_CLASS_TEXT
                }
            }
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            val box = LinearLayout(activity).apply {
                setPadding(pad, pad / 2, pad, 0)
                addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Save") { _, _ -> onDone(input.text.toString()) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }
}
