package app.fireplayer.live.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.TextView
import app.fireplayer.shell.Tokens
import app.fireplayer.vod.ui.VodSizes

/**
 * A name too long for its place, scrolling there (Live TV search's marquee, the list over the picture's and the
 * guide's: one style, one timing, [VodSizes] LIVE_MARQUEE_*): a window over the name's place with one copy of the
 * whole name inside, moved along to its end, held, put back, again while it's wanted. Only the copy's translation
 * moves: nothing is laid out or drawn again per frame (the window is sized once per column width).
 *
 * Its parent adds it with [params] and draws the name's own row without the name while [running].
 */
@SuppressLint("ViewConstructor")
internal class NameMarquee(context: Context, private val t: Tokens) : FrameLayout(context) {
    private val text = TextView(context).apply {
        isSingleLine = true
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
    }
    private var travel = 0f

    var running = false
        private set

    private val again: Runnable = Runnable {
        if (!running) return@Runnable
        text.translationX = 0f
        postDelayed(run, VodSizes.LIVE_MARQUEE_DELAY_MS)
    }
    private val end: Runnable = Runnable { postDelayed(again, VodSizes.LIVE_MARQUEE_HOLD_MS) }
    private val run: Runnable = Runnable {
        if (!running) return@Runnable
        text.animate().translationX(-travel).setStartDelay(0)
            .setDuration(LiveSearchView.Marquee.durationMs(travel, t.dpf(VodSizes.LIVE_MARQUEE_DP_S)))
            .setInterpolator(null).withEndAction(end).start()
    }

    init {
        outlineProvider = ViewOutlineProvider.BOUNDS
        clipToOutline = true
        visibility = INVISIBLE
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(text, LayoutParams(t.dp(TEXT_DP), ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** How its parent (a FrameLayout) holds it: at its top left, moved to the name's place by translation. */
    fun params() = LayoutParams(1, 1, Gravity.TOP or Gravity.START)

    /**
     * [name] in the text of [sizePx], [face] and [color], scrolling in the room it has: [w] by [h] px at ([x], [y]) in
     * the parent. False (and nothing shows) when it fits there.
     */
    fun start(name: String, sizePx: Float, face: Typeface, color: Int, x: Float, y: Float, w: Float, h: Float): Boolean {
        stop()
        text.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        text.typeface = face
        text.setTextColor(color)
        travel = LiveSearchView.Marquee.distance(text.paint.measureText(name), w)
        if (travel <= 0f) return false
        val wi = w.toInt()
        val hi = kotlin.math.ceil(h).toInt()
        val lp = layoutParams
        // Sized once per column width (its first long name); after that only moved.
        if (lp.width != wi || lp.height != hi) {
            lp.width = wi
            lp.height = hi
            layoutParams = lp
        }
        translationX = x
        translationY = y
        text.text = name
        text.translationX = 0f
        visibility = VISIBLE
        running = true
        run.run()
        return true
    }

    /** The name back at its start, the window gone. */
    fun stop() {
        removeCallbacks(again)
        removeCallbacks(run)
        text.animate().cancel()
        text.translationX = 0f
        running = false
        if (visibility != INVISIBLE) visibility = INVISIBLE
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private companion object {
        /** The scrolling copy's width: longer than any name. */
        const val TEXT_DP = 1600
    }
}
