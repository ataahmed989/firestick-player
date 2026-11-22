package app.fireplayer.live.ui

import android.content.Context
import android.graphics.RectF
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Glass
import app.fireplayer.ui.RowGroups
import app.fireplayer.ui.Rows
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The small window a held OK opens: in Live TV a channel's in the guide (Favorite, Assign favorites), a group's in the
 * groups column (Move to top, Hide category) and a Multiview picture's; through [app.fireplayer.vod.ui.HoldWindow]
 * Films & Shows' (cards, title pages, Search's history and Live TV results). One look and one behaviour for all, the
 * app's hold window shape ([HoldShape]): a narrow vertical list beside what was held, the full name written on its
 * glass (up to two lines), then one option per line in a card of rows as in the settings windows (an icon before
 * its words where it has one), the first selected, and optionally one more in a card of its own under it (a group's
 * Manage groups). Up and Down move between the options; Up past the first, Down past the last, Left, Right and Back
 * close it (LivePage passes Back on), and the selection goes back where it was.
 *
 * Light, for a slow TV: its few views are made once and reused; it's measured and placed once as it opens (its words
 * and place), then only faded and slid in or out, drawn as one layer while that lasts. It sits in its parent, a
 * FrameLayout over what it was opened from, and is GONE while closed.
 */
class HoldPopup(context: Context, private val t: Tokens) : LinearLayout(context) {

    /**
     * An option: its words, with an [icon] before them where it has one. [selected] shows it blue. [detail]: a quieter
     * second line under its words (a Home service's "Replace first shortcut" names what it replaces).
     */
    class Button(
        val label: String,
        val icon: Int? = null,
        val selected: Boolean = false,
        val detail: String? = null,
        val onClick: () -> Unit,
    )

    private class Slot(val view: LinearLayout) {
        val icon = HoldShape.icon(view)
        var action: (() -> Unit)? = null
    }

    private val rows = Rows(context)
    private val title = HoldShape.title(context, t)
    /** The options' card ([HoldShape.card]). */
    private val card = HoldShape.card(context)
    private val slots = ArrayList<Slot>()
    private var shown = 0
    /** The option of another kind, in its own card under the first; made the first time one is asked for. */
    private var below: Slot? = null
    private var belowCard: LinearLayout? = null
    private var belowShown = false
    /** Some option has an icon: the others' words keep in line with its words. */
    private var iconColumn = false

    /** What it was opened beside and the area it may use (kept for placing it again when an option's words change). */
    private val held = RectF()
    private val area = RectF()
    /** Which side of what was held it is on ([HoldPlace.side]): it slides out of that side. */
    private var side = 0

    var isOpen = false
        private set

    /** Runs as it closes, before it fades: [focusBack] unless something else took the selection meanwhile. */
    var onClose: ((focusBack: Boolean) -> Unit)? = null

    /** False until OK is pressed anew here: the OK that opened it may still be held, and its release does nothing. */
    private var armed = false
    /** Bumped on every open and close: a fade waiting for its first frame starts only if still current. */
    private var serial = 0

    // Anything else taking the selection (the settings window, full screen) closes it, leaving the selection there.
    private val watch = ViewTreeObserver.OnGlobalFocusChangeListener { _, now ->
        if (isOpen && (now == null || !isInside(now))) close(focusBack = false)
    }

    private val closeLater = Runnable { close() }

    init {
        orientation = VERTICAL
        visibility = GONE
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        HoldShape.pad(this, t)
        addView(title)
        addView(card, HoldShape.cardParams(t, after = false))
        applyLook()
    }

    /**
     * The look's surfaces: a popup's glass (or the classic popup) and its options' card with only the selected option
     * lit (again when the look changes).
     */
    fun applyLook() {
        // A window over the page: its glass shows the page behind it, blurred (copied each time it's shown).
        Glass.overlay(this, Glass.Kind.POPUP)
        HoldShape.style(card)
        belowCard?.let { HoldShape.style(it) }
    }

    /**
     * Opens with [name] over [buttons] (and [under], in a card of its own under them), beside [anchor] (the held item,
     * in the parent's coordinates) and inside [area]; the first button is selected.
     */
    fun open(name: CharSequence, buttons: List<Button>, anchor: RectF, area: RectF, under: Button? = null) {
        if (parent !is View || buttons.isEmpty()) return
        serial++
        removeCallbacks(closeLater)
        animate().cancel()
        title.text = name
        title.visibility = if (name.isBlank()) GONE else VISIBLE
        iconColumn = under?.icon != null
        for (b in buttons) if (b.icon != null) iconColumn = true
        shown = buttons.size
        buttons.forEachIndexed { i, b -> fill(slot(i), b) }
        for (i in buttons.size until slots.size) slots[i].view.visibility = GONE
        belowShown = under != null
        if (under != null) fill(belowSlot(), under)
        belowCard?.visibility = if (belowShown) VISIBLE else GONE
        // The options' places in their cards: the cards' corners and the lines between options follow.
        HoldShape.style(card)
        if (belowShown) belowCard?.let { HoldShape.style(it) }
        held.set(anchor)
        this.area.set(area)
        isOpen = true
        armed = false
        visibility = VISIBLE
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        // Measured and placed now, before it's first drawn: one layout, with its new words.
        place()
        // It comes out of the held item's side.
        alpha = 0f
        translationX = -side * t.dpf(Tokens.HOLD_SLIDE_DP)
        slots[0].view.requestFocus()
        viewTreeObserver.addOnGlobalFocusChangeListener(watch)
        // Fades in from the next frame (the first one lays it out and draws it), as one layer while it fades.
        val s = serial
        Choreographer.getInstance().postFrameCallback {
            if (s != serial || !isOpen) return@postFrameCallback
            animate().alpha(1f).translationX(0f).setDuration(Tokens.HOLD_OPEN_MS).setInterpolator(EASE).withLayer().start()
        }
    }

    /**
     * Closes it: the selection goes back first ([focusBack], see [onClose]), so the options fading out never hold it.
     * [fade] false: gone at once (a window opening in its place).
     */
    fun close(fade: Boolean = true, focusBack: Boolean = true) {
        if (!isOpen) return
        isOpen = false
        serial++
        removeCallbacks(closeLater)
        viewTreeObserver.removeOnGlobalFocusChangeListener(watch)
        onClose?.invoke(focusBack)
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        animate().cancel()
        if (!fade || !isShown || alpha <= 0f) {
            visibility = GONE
            return
        }
        animate().alpha(0f).translationX(-side * t.dpf(Tokens.HOLD_SLIDE_DP) / 2f).setDuration(Tokens.HOLD_CLOSE_MS)
            .setInterpolator(EASE).withLayer()
            .withEndAction { if (!isOpen) visibility = GONE }
            .start()
    }

    /** Closes by itself after [ms], so the viewer sees what a press did; moving to another option keeps it open. */
    fun closeAfter(ms: Long) {
        removeCallbacks(closeLater)
        postDelayed(closeLater, ms)
    }

    /** Button [i] shows [b] now (the star after a press), in place; the window fits its new words. */
    fun update(i: Int, b: Button) {
        if (i >= shown) return
        fill(slots[i], b)
        if (isOpen) place()
    }

    /** A small pop on button [i]'s icon: it springs back from smaller (a transform, nothing laid out). */
    fun pop(i: Int) {
        val icon = slots.getOrNull(i)?.icon ?: return
        icon.animate().cancel()
        icon.scaleX = POP_SCALE
        icon.scaleY = POP_SCALE
        icon.animate().scaleX(1f).scaleY(1f).setDuration(POP_MS).setInterpolator(OVERSHOOT).start()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!isOpen) return super.dispatchKeyEvent(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down && event.repeatCount == 0) armed = true
                return if (armed) super.dispatchKeyEvent(event) else true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down) step(if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1, event.repeatCount > 0)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // Off the list's side: closed (a held key doesn't).
                if (down && event.repeatCount == 0) close()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Up / Down: the next option, or past the first or last one, closed (a held key stops at the ends). */
    private fun step(dir: Int, repeat: Boolean) {
        val count = options()
        var at = -1
        for (i in 0 until count) if (option(i).isFocused) at = i
        val next = at + dir
        if (at >= 0 && next in 0 until count) {
            removeCallbacks(closeLater)
            option(next).requestFocus()
        } else if (!repeat) {
            close()
        }
    }

    /** The options shown, top to bottom: the buttons, then the one in the card under them. */
    private fun options(): Int = shown + if (belowShown && below != null) 1 else 0

    private fun option(i: Int): View = if (i < shown) slots[i].view else below!!.view

    /** Sized to its words ([HoldShape.width]) and put beside what was held ([HoldPlace]), by its layout. */
    private fun place() {
        val host = parent as? View ?: return
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return
        val w = HoldShape.width(this, t, area.width())
        measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(host.height, MeasureSpec.AT_MOST),
        )
        val width = w.toFloat()
        val h = measuredHeight.toFloat()
        val x = HoldPlace.x(held.left, held.right, width, t.dpf(Tokens.HOLD_GAP_DP), area.left, area.right)
        val y = HoldPlace.y(held.centerY(), h, area.top, area.bottom)
        side = HoldPlace.side(x, width, held.left, held.right)
        // Placed by its layout, not a transform: the glass look lines its blurred wallpaper up with where it's laid out.
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.width = w
        lp.leftMargin = x.toInt()
        lp.topMargin = y.toInt()
        layoutParams = lp
    }

    private fun belowSlot(): Slot {
        below?.let { return it }
        val c = HoldShape.card(context)
        addView(c, HoldShape.cardParams(t, after = true))
        belowCard = c
        val s = option()
        c.addView(s.view, HoldShape.optionParams(t))
        below = s
        return s
    }

    private fun isInside(v: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === this) return true
            p = p.parent as? View
        }
        return false
    }

    private fun slot(i: Int): Slot {
        slots.getOrNull(i)?.let { return it }
        val s = option()
        card.addView(s.view, HoldShape.optionParams(t))
        slots += s
        return s
    }

    private fun option(): Slot {
        val s = Slot(HoldShape.option(rows, t))
        s.view.setOnClickListener { if (isOpen) s.action?.invoke() }
        return s
    }

    private fun fill(s: Slot, b: Button) {
        s.action = b.onClick
        s.view.visibility = VISIBLE
        s.view.isSelected = b.selected
        s.view.contentDescription = if (b.detail == null) b.label else "${b.label}, ${b.detail}"
        HoldShape.words(s.view, t, b.label, b.detail)
        if (b.icon != null) {
            s.icon.setImageResource(b.icon)
            s.icon.visibility = VISIBLE
        } else {
            s.icon.visibility = if (iconColumn) INVISIBLE else GONE
        }
    }

    private companion object {
        const val POP_MS = 220L
        const val POP_SCALE = 0.6f
        val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)
        val OVERSHOOT = OvershootInterpolator(3f)
    }
}

/**
 * The app's one hold window shape ([HoldPopup]; a settings window row's, [app.fireplayer.ui.FireDialog.hold]): a narrow
 * vertical list beside what was held, in the settings windows' look: one frosted pane. The name held is written on the
 * pane's glass across its top ([title]; not selectable); the options are a card of rows under it, as a settings
 * window's grouped rows ([card], [style]): one option per line, an icon before its words where it has one, thin lines
 * between them, only the selected one lit (the settings windows' white with its blue ring); an option of another kind
 * is a card of its own under the first. The cards keep the same room of glass from the window's sides and foot and
 * from the title. As wide as its widest line ([width]). Its sizes are in [Tokens] (HOLD_*).
 */
object HoldShape {
    /** The window's room of glass round its title and cards ([Tokens.HOLD_PAD_DP]). */
    fun pad(box: View, t: Tokens) {
        val p = t.dp(Tokens.HOLD_PAD_DP)
        box.setPadding(p, p, p, p)
    }

    /**
     * The title: the name held, written on the glass, up to [Tokens.HOLD_TITLE_LINES] lines and then cut short, a
     * little in from the card's edge ([Tokens.HOLD_TITLE_PAD_X_DP]); the card sits the window's room under it.
     */
    fun title(context: Context, t: Tokens): TextView = t.label(context, Tokens.HOLD_TITLE).apply {
        maxLines = Tokens.HOLD_TITLE_LINES
        val x = t.dp(Tokens.HOLD_TITLE_PAD_X_DP)
        setPadding(x, t.dp(Tokens.HOLD_TITLE_PAD_TOP_DP), x, 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = t.dp(Tokens.HOLD_PAD_DP) }
    }

    /** A card of options: they touch, full width ([optionParams]); [style] dresses them as its slices. */
    fun card(context: Context): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** A card's place in the window: full width; one [after] another, the window's room above it. */
    fun cardParams(t: Tokens, after: Boolean) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            if (after) topMargin = t.dp(Tokens.HOLD_PAD_DP)
        }

    /**
     * Dresses the options shown in [card] as its slices ([RowGroups.styleCardRow]), by where each is now: the card's
     * fill and rim, thin lines between the options (hidden beside the selected one), the selected option the settings
     * windows' white with its blue ring; classic, the solid row and white. Again whenever which options show, or the
     * look, changes.
     */
    fun style(card: ViewGroup) {
        var count = 0
        for (i in 0 until card.childCount) if (card.getChildAt(i).visibility != View.GONE) count++
        var at = 0
        for (i in 0 until card.childCount) {
            val option = card.getChildAt(i)
            if (option.visibility == View.GONE) continue
            RowGroups.styleCardRow(option, RowGroups.posOf(at++, count), sub = false) { shownBeside(option, -1)?.isFocused == true }
        }
    }

    /** The option shown next to [option] in its card, before it ([dir] -1) or after it (1). */
    private fun shownBeside(option: View, dir: Int): View? {
        val card = option.parent as? ViewGroup ?: return null
        var i = card.indexOfChild(option) + dir
        while (i in 0 until card.childCount) {
            val v = card.getChildAt(i)
            if (v.visibility != View.GONE) return v
            i += dir
        }
        return null
    }

    /** An option: an icon (hidden until it has one, [icon]) and its words ([words]), lit only while selected. */
    fun option(rows: Rows, t: Tokens): LinearLayout = rows.base().apply {
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        minimumHeight = 0
        val x = t.dp(Tokens.HOLD_ROW_PAD_X_DP)
        setPadding(x, 0, x, 0)
        // The line under it hides while it's selected (as in a settings card): the option below draws that line.
        setOnFocusChangeListener { v, _ -> shownBeside(v, 1)?.invalidate() }
        val size = t.dp(Tokens.HOLD_ICON_DP)
        addView(ImageView(context).apply {
            imageTintList = rows.textColors
            visibility = View.GONE
        }, LinearLayout.LayoutParams(size, size).apply { marginEnd = t.dp(Tokens.HOLD_ICON_GAP_DP) })
        addView(rows.text(""), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun icon(option: LinearLayout): ImageView = option.getChildAt(0) as ImageView

    fun words(option: LinearLayout): TextView = option.getChildAt(1) as TextView

    /**
     * An option's words: [label] on its one line, or with [detail] under it, smaller and quieter
     * ([Tokens.HOLD_DETAIL_SP], [Tokens.HOLD_DETAIL_ALPHA]), the option then [Tokens.HOLD_ROW_TALL_H_DP] tall.
     */
    fun words(option: LinearLayout, t: Tokens, label: String, detail: String?) {
        val words = words(option)
        if (detail == null) {
            words.isSingleLine = true
            words.text = label
        } else {
            words.isSingleLine = false
            words.maxLines = 2
            val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, Tokens.HOLD_DETAIL_SP, option.resources.displayMetrics)
            words.text = SpannableStringBuilder(label).append('\n')
                .append(detail, QuietSpan(size, Tokens.HOLD_DETAIL_ALPHA), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val h = t.dp(if (detail == null) Tokens.HOLD_ROW_H_DP else Tokens.HOLD_ROW_TALL_H_DP)
        val lp = option.layoutParams ?: return
        if (lp.height != h) {
            lp.height = h
            option.layoutParams = lp
        }
    }

    /** A second line's words: [sizePx], and quieter than the option's in whichever colour they are (lit or not). */
    private class QuietSpan(private val sizePx: Float, private val strength: Float) : MetricAffectingSpan() {
        override fun updateMeasureState(paint: TextPaint) {
            paint.textSize = sizePx
        }

        override fun updateDrawState(paint: TextPaint) {
            paint.textSize = sizePx
            paint.alpha = (paint.alpha * strength).roundToInt()
        }
    }

    /** An option's place in its card: full width, this tall, touching the options beside it. */
    fun optionParams(t: Tokens) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, t.dp(Tokens.HOLD_ROW_H_DP))

    /**
     * How wide [box] (a window of this shape, its words set) is: its widest line as it would be on one line (with its
     * margins), and its room, within
     * [Tokens.HOLD_MIN_W_DP]..[Tokens.HOLD_MAX_W_DP] and [max] px (the area). Measures its lines once.
     */
    fun width(box: ViewGroup, t: Tokens, max: Float): Int {
        val free = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        var widest = 0
        for (i in 0 until box.childCount) {
            val c = box.getChildAt(i)
            if (c.visibility == View.GONE) continue
            c.measure(free, free)
            val lp = c.layoutParams as? ViewGroup.MarginLayoutParams
            val w = c.measuredWidth + (lp?.leftMargin ?: 0) + (lp?.rightMargin ?: 0)
            if (w > widest) widest = w
        }
        val w = HoldSize.width(
            widest.toFloat(),
            (box.paddingLeft + box.paddingRight).toFloat(),
            t.dpf(Tokens.HOLD_MIN_W_DP),
            minOf(t.dpf(Tokens.HOLD_MAX_W_DP), max),
        )
        return ceil(w).toInt()
    }
}

/** How big a hold window is (pure, unit-tested). */
object HoldSize {
    /**
     * Its width: its widest line [content] and its [padding], no wider (no empty room beside its words), at least [min]
     * (a short list still reads as a column), at most [max] (a longer title takes a second line); [max] wins over [min]
     * (a narrow area).
     */
    fun width(content: Float, padding: Float, min: Float, max: Float): Float =
        (content + padding).coerceAtLeast(min).coerceAtMost(max.coerceAtLeast(0f))
}

/** Where a hold window goes (pure, unit-tested): beside what was held, kept inside the area it may use. */
object HoldPlace {
    /**
     * Its left edge: [gap] right of the anchor; where it doesn't fit there (an item near the area's right edge), [gap]
     * left of it; where neither side has room (an item as wide as the area), as far right as fits, over the anchor, and
     * never past the area's left edge.
     */
    fun x(anchorLeft: Float, anchorRight: Float, width: Float, gap: Float, left: Float, right: Float): Float {
        val after = anchorRight + gap
        if (after >= left && after + width <= right) return after
        val before = anchorLeft - gap - width
        if (before >= left && before + width <= right) return before
        return minOf(after, right - width).coerceAtLeast(left)
    }

    /** Its top: centred on the anchor, moved to stay between [top] and [bottom]. */
    fun y(anchorCenterY: Float, height: Float, top: Float, bottom: Float): Float =
        minOf(anchorCenterY - height / 2, bottom - height).coerceAtLeast(top)

    /** Which side of the anchor a window at [x] is: 1 right of it, -1 left of it, 0 over it (it slides out of that side). */
    fun side(x: Float, width: Float, anchorLeft: Float, anchorRight: Float): Int = when {
        x >= anchorRight -> 1
        x + width <= anchorLeft -> -1
        else -> 0
    }
}


/**
 * OK in the guide (pure, unit-tested): a short press acts on its release; held past [holdMs] (or once the system
 * calls it a long press, its first repeat) it's a hold, reported once, and the release after a hold does nothing.
 * A repeat or release of a press that started elsewhere (focus came here while it was held) is not ours.
 */
class PressHold(private val holdMs: Long) {
    private var pressed = false
    private var held = false

    /** A DOWN or one of its repeats. True once, when the press becomes a hold. */
    fun down(repeatCount: Int, longPress: Boolean, heldForMs: Long): Boolean {
        if (repeatCount == 0) {
            pressed = true
            held = false
            return false
        }
        if (!pressed || held) return false
        if (longPress || heldForMs >= holdMs) {
            held = true
            return true
        }
        return false
    }

    /** The release: true when it ends a short press that started here (pick). */
    fun up(canceled: Boolean): Boolean {
        val pick = pressed && !held && !canceled
        cancel()
        return pick
    }

    /** Focus left, or another key came in between: the release that follows does nothing. */
    fun cancel() {
        pressed = false
        held = false
    }
}

/** The groups column's edits from a group's small window (Move to top, Hide category), kept apart to be tested. */
object GroupEdits {
    /** [list] with [id]'s entry first and the others in their order; the same list when it's first already, or absent. */
    fun <T> toTop(list: List<T>, id: String, idOf: (T) -> String): List<T> {
        val i = list.indexOfFirst { idOf(it) == id }
        if (i <= 0) return list
        return ArrayList<T>(list.size).apply {
            add(list[i])
            for (j in list.indices) if (j != i) add(list[j])
        }
    }

    fun toTop(ids: List<String>, id: String): List<String> = toTop(ids, id) { it }

    /**
     * Where the selection goes when [id] leaves [ids] (its playlist's shown groups, in order): the group that takes
     * its place, or the one above when it was the last; null when it was the only one (or isn't there).
     */
    fun afterHide(ids: List<String>, id: String): String? {
        val i = ids.indexOf(id)
        if (i < 0) return null
        return ids.getOrNull(i + 1) ?: ids.getOrNull(i - 1)
    }
}
