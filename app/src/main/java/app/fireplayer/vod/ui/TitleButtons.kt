package app.fireplayer.vod.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import app.fireplayer.R
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Buttons
import app.fireplayer.ui.Rows
import app.fireplayer.vod.ui.TitleRules.Dir

/**
 * The title page's action column (Design-References/redesign-2026-11-19 and its TUNING-1; design-brief.md §6.4 for
 * what each does), top to bottom: Play / Resume (its words carry the state, a thin progress line inside when it
 * resumes), Play from beginning (only with a resume point), Sources, then the round (+) My List (a blue ✓ while the
 * title is on it) and the round (⋯) side by side, from the column's left edge. The three pills are alike: as wide as
 * the column ([VodSizes.ACTIONS_W], sized from the widest words a state shows), one icon size and one style of words,
 * the icon and words centred together. Play is the main action and reads as one before it has focus (brighter glass,
 * [Buttons.PillSpec.primary]); in "Sources · 18" the count is quieter than the word. Words are never cut short: ones
 * wider than the column have a shorter form ([TitleRules.fitWords]). Buttons a title doesn't have are GONE, so the
 * others close up; Play that can't play yet ("Coming Mar 6") stays, dimmed, without its icon, and passed by. Made from
 * the app's shared buttons ([Buttons]: glass pills and circles, blue on white when focused, growing 1.04 and 1.08).
 *
 * Up and Down move between the rows, Left and Right between the circles, without wrapping ([TitleRules.buttonStep]);
 * a press that leaves the column is the page's ([Listener]: Left to the rail, Right to what's beside the column, Down
 * to what's under it). OK acts on its release, and only for a press that started here.
 */
@SuppressLint("ViewConstructor")
class TitleButtons(context: Context, private val t: Tokens, private val listener: Listener) : LinearLayout(context) {

    enum class Which { PLAY, RESTART, SOURCES, MY_LIST, MORE }

    interface Listener {
        fun onButton(which: Which)
        fun onButtonFocused(which: Which)
        /** Down from the column's last row. */
        fun onButtonsDown(which: Which)
        /** Right out of the column. */
        fun onButtonsRight(which: Which)
        fun onLeftEdge()
    }

    private fun spec(primary: Boolean) = Buttons.PillSpec(
        height = VodSizes.BUTTON_H,
        pad = VodSizes.ACTIONS_PAD,
        text = VodSizes.BUTTON,
        icon = VodSizes.BUTTON_ICON,
        iconGap = VodSizes.BUTTON_ICON_GAP,
        progressH = VodSizes.PILL_PROGRESS_H,
        progressBottom = VodSizes.PILL_PROGRESS_BOTTOM,
        primary = primary,
        progressSide = VodSizes.PILL_PROGRESS_SIDE,
    )

    val play = pill(primary = true)
    private val restart = pill()
    private val sources = pill()
    private val myList = Buttons.RoundButton(context, t, VodSizes.BUTTON_H, VodSizes.CIRCLE_ICON, toggle = true)
    private val more = Buttons.RoundButton(context, t, VodSizes.BUTTON_H, VodSizes.CIRCLE_ICON).apply { set(R.drawable.ic_more, "More") }
    private val circles = LinearLayout(context).apply {
        orientation = HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }

    /** Each button's focusable view, in order. */
    private val order: List<Pair<Which, View>> = listOf(
        Which.PLAY to play, Which.RESTART to restart, Which.SOURCES to sources,
        Which.MY_LIST to myList.button, Which.MORE to more.button,
    )

    /** The button focus was last on (coming back into the column lands on it). */
    var last = Which.PLAY
        private set

    private var armed: View? = null

    init {
        orientation = VERTICAL
        // Glass pads, a grown pill and the circles' names draw outside the views.
        clipChildren = false
        clipToPadding = false
        val h = t.dp(VodSizes.BUTTON_H)
        val gap = t.dp(VodSizes.BUTTON_GAP)
        listOf<View>(play, restart, sources).forEachIndexed { i, v ->
            addView(v, LayoutParams(LayoutParams.MATCH_PARENT, h).apply { if (i > 0) topMargin = gap })
        }
        circles.addView(myList, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        circles.addView(more, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = gap })
        addView(circles, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = gap })
        for ((which, v) in order) {
            if (v is Buttons.ActionPill) Tokens.focusEffect(v, Tokens.GROW_PILL, null) { has -> if (has) focused(which) }
            else (v.parent as? Buttons.RoundButton)?.let { rb ->
                Tokens.focusEffect(v, Tokens.GROW_CIRCLE, rb.label) { has -> if (has) focused(which) }
            }
            v.setOnKeyListener { _, keyCode, event -> onKey(which, v, keyCode, event) }
        }
        setMyList(false)
        words(play, "Play", R.drawable.ic_play)
        words(restart, "Play from beginning", R.drawable.ic_restart)
        words(sources, "Sources", R.drawable.ic_sources)
    }

    /** A pill of the column: its icon and words centred together, the words on one line. */
    private fun pill(primary: Boolean = false) = Buttons.ActionPill(context, t, spec(primary)).apply {
        gravity = Gravity.CENTER
        label.maxLines = 1
        // Never reached by a state's words ([TitleRules.fitWords]); a line that still can't fit ends in "…", not cut.
        label.ellipsize = TextUtils.TruncateAt.END
    }

    /**
     * [pill]'s words and icon. The words are [full] when the column has room for them at the buttons' size, else
     * [compact] ([TitleRules.fitWords]; measured here, and the size changes only for words too wide in both forms).
     * What follows [quietFrom] in them is quieter (the count after "Sources"); [says] is read out.
     */
    private fun words(pill: Buttons.ActionPill, full: String, iconRes: Int?, compact: String = full, says: String = full, quietFrom: Int = -1) {
        val paint = pill.label.paint
        val now = paint.textSize
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, VodSizes.BUTTON.sp, resources.displayMetrics)
        val fit = TitleRules.fitWords(full, compact, t.dpf(VodSizes.pillTextW(iconRes != null))) { paint.measureText(it) }
        paint.textSize = now
        pill.set(if (fit.words == full) Buttons.quietTail(full, quietFrom) else fit.words, iconRes)
        pill.contentDescription = says
        pill.label.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (fit.small) VodSizes.BUTTON_LONG.sp else VodSizes.BUTTON.sp)
    }

    private fun focused(which: Which) {
        last = which
        listener.onButtonFocused(which)
    }

    /** Play as the title's state says it (kept while the pill shows a wait, [setWaiting]). */
    private var playState = TitleRules.Play("Play")
    private var waiting = false

    /**
     * Play's words, progress line and whether it can play now. One that can't ("Coming Mar 6") is dimmed and passed by,
     * and is neither the main action nor marked with the play icon.
     */
    internal fun setPlay(p: TitleRules.Play) {
        playState = p
        if (waiting) {
            play.isFocusable = p.enabled
            return
        }
        words(play, p.label, if (p.enabled) R.drawable.ic_play else null, compact = p.compact, says = p.says)
        play.progress = p.progress
        play.primary = p.enabled
        play.alpha = if (p.enabled) 1f else Rows.DIM
        if (!p.enabled && play.isFocused) focusFirst(skip = play)
        play.isFocusable = p.enabled
    }

    /**
     * Play waiting for the add-ons (design-brief.md §6.5, stage 1): "Waiting · 9 s" with a thin bar toward their answer
     * limit ([label] is the wait's own words, "Waiting for MediaFusion · 9 s": read out here, shown whole on the
     * page's source line); null [label] puts Play back as the title's state says.
     */
    fun setWaiting(label: String?, fraction: Float = 0f) {
        if (label == null) {
            if (!waiting) return
            waiting = false
            setPlay(playState)
            return
        }
        waiting = true
        words(play, TitleRules.waitWords(label), null, says = label)
        play.progress = fraction
    }

    /** The Sources button's words: "Sources", then "Sources · 40" once an add-on has answered (the count quieter). */
    fun setSourcesLabel(text: String) {
        if (sourcesText == text) return
        sourcesText = text
        words(sources, text, R.drawable.ic_sources, compact = "Sources", quietFrom = TitleRules.quietFrom(text))
    }

    private var sourcesText = "Sources"

    /** Play from beginning: only with a saved position. */
    fun setRestart(shown: Boolean) = show(restart, Which.RESTART, shown)

    /** Sources: not for a film that isn't out, nor a show with nothing aired yet. */
    fun setSources(shown: Boolean) = show(sources, Which.SOURCES, shown)

    /** My List: (+), or a blue ✓ while the title is on it. */
    fun setMyList(on: Boolean) {
        myList.set(if (on) R.drawable.ic_check else R.drawable.ic_add, if (on) "In My List" else "My List")
        myList.setOn(on)
    }

    /** The (⋯) button: the small window opens beside it. */
    val moreButton: View get() = more.button

    private fun show(v: View, which: Which, shown: Boolean) {
        val vis = if (shown) VISIBLE else GONE
        if (v.visibility == vis) return
        // A button that goes while it has focus hands it to Play (or the first that can take it) first.
        if (!shown && v.hasFocus()) {
            last = Which.PLAY
            focusFirst(skip = v)
        }
        v.visibility = vis
        if (!shown && last == which) last = Which.PLAY
    }

    /** The button focus was last on, else the first that can take it. */
    fun focusLast(): Boolean {
        val v = viewOf(last)
        if (Buttons.canFocus(v) && v.requestFocus()) return true
        return focusFirst()
    }

    /** Play, or the first button that can take focus (a film not out yet: (+)); never [skip]. */
    fun focusFirst(skip: View? = null): Boolean {
        for ((_, v) in order) {
            if (v === skip) continue
            if (Buttons.canFocus(v) && v.requestFocus()) return true
        }
        return false
    }

    /** Focus on [which] if it can take it; otherwise as [focusLast]. */
    fun focus(which: Which): Boolean {
        last = which
        return focusLast()
    }

    /** The button holding focus, or null. */
    fun focusedButton(): Which? = order.firstOrNull { it.second.isFocused }?.first

    fun viewOf(which: Which): View = order.first { it.first == which }.second

    fun applyLook() {
        play.applyLook()
        restart.applyLook()
        sources.applyLook()
        myList.applyLook()
        more.applyLook()
    }

    private fun onKey(which: Which, v: View, keyCode: Int, event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val dir = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // On release, and only for a press that started here (not the OK that opened the page).
                if (down && event.repeatCount == 0) armed = v
                if (!down && event.action == KeyEvent.ACTION_UP) {
                    val mine = armed === v && !event.isCanceled
                    armed = null
                    if (mine) listener.onButton(which)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> Dir.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Dir.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> Dir.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Dir.DOWN
            else -> return false
        }
        if (!down) return true
        armed = null
        val next = TitleRules.buttonStep(which, dir) { Buttons.canFocus(viewOf(it)) }
        when {
            next != null -> viewOf(next).requestFocus()
            dir == Dir.LEFT -> listener.onLeftEdge()
            dir == Dir.RIGHT -> listener.onButtonsRight(which)
            dir == Dir.DOWN -> listener.onButtonsDown(which)
        }
        return true
    }
}
