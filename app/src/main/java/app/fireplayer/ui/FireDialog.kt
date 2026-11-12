package app.fireplayer.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.FocusFinder
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import app.fireplayer.R

/**
 * Small pop-up windows in the player's style: pick one of several values,
 * answer a question, or type a name.
 *
 * They are drawn inside the screen's own window ([Popup]), not as windows of their own: on Fire TV a second window
 * with a dimmed backdrop made the TV redraw the whole screen another way, and the settings window and subtitles came
 * out shifted sideways, their far end showing at the other edge of the screen.
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
        /** Another option ticked in blue as well: the one the chosen option stands for (Auto's pick). */
        alsoChecked: Int? = null,
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
            // A long option (a film's subtitle track and how well it matches) takes a second line instead of being cut off.
            val ticked = i == selected || i == alsoChecked
            val row = rows.build(option, null, if (ticked) R.drawable.ic_check else null, labelLines = 2)
            row.isSelected = ticked
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
        show(activity, dialog, box, width, menuPassesOn = true) { params ->
            if (besidePanel) {
                // Just left of the settings window (31% of the screen plus its margin).
                params.gravity = Gravity.END or Gravity.CENTER_VERTICAL
                params.marginEnd = (screenWidth * 0.31f).toInt() + rows.dp(30)
                params.rightMargin = params.marginEnd
            }
        }
        list.getChildAt(selected.coerceIn(0, options.size - 1))?.requestFocus()
    }

    /**
     * A question with a few answers, centred on screen, like "Resume from 12:34?". [focus] is the
     * answer that starts selected. With no answers it's a plain message that Back closes;
     * [busy] adds a moving bar under it, for work in progress such as a download.
     */
    fun ask(
        activity: Activity,
        title: String,
        answers: List<Pair<String, () -> Unit>>,
        onDismiss: (() -> Unit)? = null,
        focus: Int = 0,
        busy: Boolean = false,
        /** Opened from a settings window: the menu button closes both (see [show]). */
        fromPanel: Boolean = false,
    ): Popup? {
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
        if (busy) {
            val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.accent))
            }
            box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rows.dp(4)).apply {
                bottomMargin = rows.dp(4)
            })
        }
        if (answers.isNotEmpty()) {
            box.addView(list)
        } else if (!busy) {
            // A plain message: no gap left under the title for answers that aren't there.
            (box.getChildAt(0).layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = rows.dp(2)
        }
        dialog.onDismiss = { if (!answered) onDismiss?.invoke() }
        show(activity, dialog, box, rows.dp(260), menuPassesOn = fromPanel) {}
        list.getChildAt(focus.coerceIn(0, (answers.size - 1).coerceAtLeast(0)))?.requestFocus()
        return dialog
    }

    /** Type a value, such as a preset name. [hint] shows in the empty box, as where to find a key. */
    fun prompt(
        activity: Activity,
        title: String,
        initial: String,
        password: Boolean = false,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        hint: String? = null,
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
            if (hint != null) this.hint = hint
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
            // Glass: the same pill in the rows' material, on the window's own glass.
            Glass.background(this, Glass.Kind.ROW, R.drawable.bg_pill)
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
        show(activity, dialog, box, rows.dp(320), menuPassesOn = true) {}
        input.requestFocus()
    }

    /** A window can't be shown for an activity that is closing or already closed. */
    private fun gone(activity: Activity) = activity.isFinishing || activity.isDestroyed

    private fun newDialog(activity: Activity) = Popup(activity)

    private fun box(activity: Activity, title: String): LinearLayout {
        val rows = Rows(activity)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            // Glass shows the film dimmed, as the window dims what's behind it.
            Glass.background(this, Glass.Kind.DIALOG_POPUP)
            setPadding(rows.dp(14), rows.dp(14), rows.dp(14), rows.dp(12))
            addView(TextView(activity).apply {
                text = title
                typeface = ResourcesCompat.getFont(activity, R.font.figtree_700)
                setTextColor(ContextCompat.getColor(activity, R.color.text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }, rows.listParams(top = 0, bottom = 8))
        }
    }

    /**
     * Shows [box] as a small window over [activity]'s screen, [width] pixels wide and centred unless [place] moves it.
     * [menuPassesOn]: a window opened from Settings (a list of choices, a box to type in), or a link typed in on the
     * home screen, which the menu button (three lines) closes along with the settings window. The resume question and
     * the update's windows keep the menu button to themselves.
     */
    fun show(
        activity: Activity,
        dialog: Popup,
        box: View,
        width: Int,
        menuPassesOn: Boolean,
        place: (FrameLayout.LayoutParams) -> Unit,
    ) {
        dialog.menuPassesOn = menuPassesOn
        dialog.show(box, width, place)
    }

    /** A window of its own content ([box]), as the font sender's, shown like the others. */
    fun custom(activity: Activity, box: View, width: Int, menuPassesOn: Boolean): Popup? {
        if (gone(activity)) return null
        return Popup(activity).also { show(activity, it, box, width, menuPassesOn) {} }
    }

    /** The small windows open over each screen, the newest last. */
    private val open = java.util.WeakHashMap<Activity, MutableList<Popup>>()

    /**
     * Where the selection goes when the small window open over [activity]'s screen closes: a row of a settings window
     * that was redrawn behind it, so the row that opened it is a new one.
     */
    fun returnFocusTo(activity: Activity, view: View) {
        open[activity]?.lastOrNull()?.returnTo = view
    }

    /** Whether a small window is open over [activity]'s screen. */
    fun isShowing(activity: Activity): Boolean = open[activity]?.isNotEmpty() == true

    /**
     * Gives a key to the small window open over [activity]'s screen, if there is one. Each screen calls this first
     * thing in its dispatchKeyEvent; true means the key was the window's. Play, pause and the other media keys still
     * reach the film.
     */
    fun dispatch(activity: Activity, event: KeyEvent): Boolean {
        val top = open[activity]?.lastOrNull() ?: return false
        return top.dispatch(event)
    }

    /**
     * A small window drawn inside the screen's own window: a faint shade over everything, the window's box on it.
     * While it's open, keys go to it ([dispatch]) and the selection can't leave it.
     */
    class Popup internal constructor(private val activity: Activity) {
        private val host: ViewGroup? = activity.findViewById(android.R.id.content)
        private val shade = FrameLayout(activity).apply {
            setBackgroundColor(SHADE)
            // Takes touches, so nothing under it is pressed by mistake.
            isClickable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        var onDismiss: (() -> Unit)? = null
        internal var menuPassesOn = false
        var isShowing = false
            private set
        private var before: View? = null
        internal var returnTo: View?
            get() = before
            set(v) { before = v }
        private var backDown = false
        private var lastInside: View? = null

        // Anything else on screen that takes the selection while the window is open (a window being redrawn behind
        // it, a button appearing) hands it straight back.
        private val keepFocus = ViewTreeObserver.OnGlobalFocusChangeListener { _, now ->
            if (!isShowing) return@OnGlobalFocusChangeListener
            if (now != null && isInside(now)) {
                lastInside = now
            } else {
                shade.post { if (isShowing && !shade.hasFocus()) focusInside() }
            }
        }

        private fun isInside(view: View): Boolean {
            var v: View? = view
            while (v != null) {
                if (v === shade) return true
                v = v.parent as? View
            }
            return false
        }

        private fun focusInside() {
            val target = lastInside?.takeIf { it.isAttachedToWindow && it.isShown && isInside(it) }
                ?: FocusFinder.getInstance().findNextFocus(shade, null, View.FOCUS_DOWN)
            if (target != null) {
                target.requestFocus()
            } else {
                // A message with nothing to press: the shade itself holds the selection, so no key reaches the
                // screen under it.
                shade.isFocusable = true
                shade.requestFocus()
            }
        }

        internal fun show(box: View, width: Int, place: (FrameLayout.LayoutParams) -> Unit) {
            val parent = host ?: return
            if (isShowing) return
            before = activity.currentFocus
            val params = FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            place(params)
            shade.addView(box, params)
            parent.addView(shade, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            isShowing = true
            open.getOrPut(activity) { mutableListOf() }.add(this)
            shade.viewTreeObserver.addOnGlobalFocusChangeListener(keepFocus)
            shade.alpha = 0f
            shade.animate().alpha(1f).setDuration(140).start()
            box.scaleX = 0.96f
            box.scaleY = 0.96f
            box.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            // The caller puts the selection on its row next; until then, the first one inside.
            focusInside()
        }

        fun dismiss() {
            if (!isShowing) return
            isShowing = false
            open[activity]?.remove(this)
            shade.viewTreeObserver.takeIf { it.isAlive }?.removeOnGlobalFocusChangeListener(keepFocus)
            shade.animate().cancel()
            (shade.parent as? ViewGroup)?.removeView(shade)
            // Back on what was selected before (the row that opened it), if it's still there.
            val back = before
            before = null
            if (back != null && back.isAttachedToWindow && back.isShown) back.requestFocus()
            onDismiss?.invoke()
        }

        fun cancel() = dismiss()

        internal fun dispatch(event: KeyEvent): Boolean {
            val code = event.keyCode
            when (code) {
                KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (event.repeatCount == 0) {
                            backDown = true
                            cancel()
                        }
                    } else if (event.action == KeyEvent.ACTION_UP) {
                        backDown = false
                    }
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    if (!menuPassesOn) return true
                    // Closes this window, and the press goes on to the screen, which closes its settings window.
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) cancel()
                    return false
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
                -> return false
            }
            if (!shade.hasFocus()) focusInside()
            if (activity.window.superDispatchKeyEvent(event)) return true
            // Moving the selection, kept inside this window.
            if (event.action == KeyEvent.ACTION_DOWN) {
                val direction = when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
                    KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
                    KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
                    KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
                    else -> null
                }
                val from = shade.findFocus()
                if (direction != null && from != null) FocusFinder.getInstance().findNextFocus(shade, from, direction)?.requestFocus()
            }
            return true
        }
    }

    /** The shade over the screen while a small window is open (a quarter dark, as before). */
    private const val SHADE = 0x40000000
}
