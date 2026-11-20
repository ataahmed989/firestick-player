package app.fireplayer.ui

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import app.fireplayer.shell.Tokens

/**
 * The buffering symbol over a picture, with a line or two of text under it when there's something to say
 * ("Reconnecting…"). One for a screen: it's put over the picture's middle wherever the picture is ([place]), smaller
 * over a miniature, by translation and scale only, so it can follow a picture that moves. When it shows is decided by
 * [BusySigns]. Never takes focus.
 */
class BusyOverlay(context: Context, t: Tokens) : FrameLayout(context) {
    private val boxW = t.dp(BOX_W_DP)
    private val boxH = t.dp(BOX_H_DP)
    private val spinnerHalf = t.dpf(Tokens.SPINNER_DP) / 2f
    /**
     * The same symbol the film player shows while it buffers (activity_player.xml's): Android's turning ring in the
     * accent colour, [Tokens.SPINNER_DP] across. It only turns while this overlay shows.
     */
    private val spinner = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(t.accent)
        isFocusable = false
    }
    private val label = t.label(context, 13f, t.semibold).apply {
        maxLines = 2
        gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
        setLineSpacing(t.dpf(2f), 1f)
        setShadowLayer(t.dpf(4f), 0f, 0f, 0xCC000000.toInt())
        visibility = INVISIBLE
    }
    private var placed = false
    private var busy = false
    private var cx = 0f
    private var cy = 0f
    private var fullness = 0f

    init {
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        clipChildren = false
        visibility = GONE
        val size = t.dp(Tokens.SPINNER_DP)
        addView(spinner, LayoutParams(size, size, Gravity.CENTER))
        // A fixed box: new text never lays anything out again.
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, t.dp(TEXT_H_DP), Gravity.TOP).apply {
            topMargin = boxH / 2 + size / 2 + t.dp(TEXT_GAP_DP)
        })
    }

    /** Its layout params in a FrameLayout: a fixed box at the top left, moved over the picture by [place]. */
    fun params(): LayoutParams = LayoutParams(boxW, boxH, Gravity.TOP or Gravity.START)

    /**
     * Over the picture whose middle is at ([cx], [cy]) in the parent; [fullness] is how much of full screen the
     * picture is (0 a miniature, 1 full screen, between while it moves).
     */
    fun place(cx: Float, cy: Float, fullness: Float) {
        this.cx = cx
        this.cy = cy
        this.fullness = fullness
        // While it's hidden (nearly always) a moving picture costs it nothing: it's put in place as it shows.
        if (!placed) { placed = true; apply() } else if (visibility == VISIBLE) move()
    }

    private fun move() {
        translationX = cx - boxW / 2f
        translationY = cy - boxH / 2f
        val s = Tokens.SPINNER_MINI_SCALE + (1f - Tokens.SPINNER_MINI_SCALE) * fullness.coerceIn(0f, 1f)
        spinner.scaleX = s
        spinner.scaleY = s
        label.translationY = -(1f - s) * spinnerHalf
    }

    /** There's no picture to be over. */
    fun away() {
        if (!placed) return
        placed = false
        apply()
    }

    /** The symbol turning ([spinning]), with [text] under it (null: none). */
    fun show(spinning: Boolean, text: String?) {
        val line = if (spinning) text else null
        if (!TextUtils.equals(label.text, line ?: "")) label.text = line ?: ""
        label.visibility = if (line == null) INVISIBLE else VISIBLE
        if (busy != spinning) { busy = spinning; apply() }
    }

    private fun apply() {
        val on = placed && busy
        if (on) move()
        visibility = if (on) VISIBLE else GONE
    }

    private companion object {
        const val BOX_W_DP = 340
        const val BOX_H_DP = 148
        const val TEXT_H_DP = 40
        const val TEXT_GAP_DP = 8
    }
}
