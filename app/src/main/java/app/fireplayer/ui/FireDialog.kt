package app.fireplayer.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import app.fireplayer.R

/**
 * Small pop-up windows in the player's style: pick one of several values,
 * answer a question, or type a name.
 */
object FireDialog {

    /**
     * Pops out a list of [options] with a tick on [selected]. With [besidePanel] it sits just
     * left of the settings window; otherwise it's centred.
     */
    fun choose(
        activity: Activity,
        title: String,
        options: List<String>,
        selected: Int,
        besidePanel: Boolean = false,
        onPick: (Int) -> Unit,
    ) {
        if (gone(activity)) return
        val rows = Rows(activity)
        val dialog = newDialog(activity)
        val box = box(activity, title)
        val scroll = CenterScrollView(activity).apply {
            // Capped before the first layout, so the ticked option is centred in the final height.
            maxHeight = (activity.resources.displayMetrics.heightPixels * 0.7f).toInt()
            isVerticalScrollBarEnabled = false
            isVerticalFadingEdgeEnabled = true
            setFadingEdgeLength(rows.dp(20))
        }
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        options.forEachIndexed { i, option ->
            val row = rows.build(option, null, if (i == selected) R.drawable.ic_check else null)
            row.isSelected = i == selected
            row.setOnClickListener {
                dialog.dismiss()
                onPick(i)
            }
            row.setOnKeyListener { v, keyCode, event -> Rows.verticalNav(list, v, keyCode, event) }
            list.addView(row, rows.listParams())
        }
        box.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val screenWidth = activity.resources.displayMetrics.widthPixels
        val width = (screenWidth * 0.22f).toInt().coerceAtLeast(rows.dp(190))
        show(dialog, box, width) { params ->
            if (besidePanel) {
                // Just left of the settings window (31% of the screen plus its margin).
                params.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                params.x = (screenWidth * 0.31f).toInt() + rows.dp(30)
            }
        }
        list.getChildAt(selected.coerceIn(0, options.size - 1))?.requestFocus()
    }

    /**
     * A question with a few answers, centred on screen, like "Resume from 12:34?". [focus] is the
     * answer that starts selected. With no answers it's a plain message that Back closes.
     */
    fun ask(
        activity: Activity,
        title: String,
        answers: List<Pair<String, () -> Unit>>,
        onDismiss: (() -> Unit)? = null,
        focus: Int = 0,
    ): Dialog? {
        if (gone(activity)) return null
        val rows = Rows(activity)
        val dialog = newDialog(activity)
        val box = box(activity, title)
        val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        var answered = false
        answers.forEach { (label, action) ->
            val row = rows.build(label, null, null)
            row.setOnClickListener {
                answered = true
                dialog.dismiss()
                action()
            }
            row.setOnKeyListener { v, keyCode, event -> Rows.verticalNav(list, v, keyCode, event) }
            list.addView(row, rows.listParams())
        }
        if (answers.isNotEmpty()) box.addView(list)
        dialog.setOnDismissListener { if (!answered) onDismiss?.invoke() }
        show(dialog, box, rows.dp(260)) {}
        list.getChildAt(focus.coerceIn(0, (answers.size - 1).coerceAtLeast(0)))?.requestFocus()
        return dialog
    }

    /** Type a value, such as a preset name. */
    fun prompt(
        activity: Activity,
        title: String,
        initial: String,
        password: Boolean = false,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        onDone: (String) -> Unit,
    ) {
        if (gone(activity)) return
        val rows = Rows(activity)
        val dialog = newDialog(activity)
        val box = box(activity, title)
        val keyboard = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else inputType
        val input = EditText(activity).apply {
            setText(initial)
            setSelection(initial.length)
            setSingleLine()
            setBackgroundResource(R.drawable.bg_input)
            setPadding(rows.dp(12), rows.dp(9), rows.dp(12), rows.dp(9))
            setTextColor(ContextCompat.getColor(activity, R.color.text))
            setHintTextColor(ContextCompat.getColor(activity, R.color.text_muted))
            typeface = ResourcesCompat.getFont(activity, R.font.figtree_600)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            imeOptions = EditorInfo.IME_ACTION_DONE
            this.inputType = keyboard
        }
        box.addView(input, rows.listParams(top = 2, bottom = 8))

        val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, onClick: () -> Unit) = rows.base().apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_pill)
            addView(rows.text(label))
            setOnClickListener { onClick() }
        }
        val save = button("Save") {
            dialog.dismiss()
            onDone(input.text.toString())
        }
        val cancel = button("Cancel") { dialog.dismiss() }
        buttons.addView(save, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = rows.dp(6) })
        buttons.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = rows.dp(6) })
        box.addView(buttons)
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                // Done on the keyboard saves, and the keyboard goes away with the window.
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(input.windowToken, 0)
                save.performClick()
                true
            } else {
                false
            }
        }
        show(dialog, box, rows.dp(320)) {}
        input.requestFocus()
    }

    /** A window can't be shown for an activity that is closing or already closed. */
    private fun gone(activity: Activity) = activity.isFinishing || activity.isDestroyed

    private fun newDialog(activity: Activity) = Dialog(activity).apply {
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        setCanceledOnTouchOutside(true)
    }

    private fun box(activity: Activity, title: String): LinearLayout {
        val rows = Rows(activity)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_popup)
            setPadding(rows.dp(14), rows.dp(14), rows.dp(14), rows.dp(12))
            addView(TextView(activity).apply {
                text = title
                typeface = ResourcesCompat.getFont(activity, R.font.figtree_700)
                setTextColor(ContextCompat.getColor(activity, R.color.text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }, rows.listParams(top = 0, bottom = 8))
        }
    }

    private fun show(dialog: Dialog, box: View, width: Int, place: (WindowManager.LayoutParams) -> Unit) {
        dialog.setContentView(box)
        val window = dialog.window ?: return
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.setDimAmount(0.25f)
        val params = window.attributes
        params.width = width
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        place(params)
        window.attributes = params
        dialog.setOnKeyListener { _, keyCode, event ->
            // Swallow the Back key-up too, so it doesn't reach the window underneath.
            keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && !dialog.isShowing
        }
        box.alpha = 0f
        box.scaleX = 0.96f
        box.scaleY = 0.96f
        dialog.show()
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).start()
    }
}
