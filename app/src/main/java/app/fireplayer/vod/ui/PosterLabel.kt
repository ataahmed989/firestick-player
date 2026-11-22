package app.fireplayer.vod.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.text.Layout
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.animation.AnimationUtils
import android.view.animation.PathInterpolator
import android.widget.TextView
import app.fireplayer.shell.Tokens

/**
 * The chosen poster's title under it, as Glass Home shows the focused app's name under its tile (AppAdapter): small,
 * centred, white with a soft shadow. One for a row (or See all's grid), never one per card: its owner sets its words
 * once per focus change ([show]) and moves it under the chosen poster with translations alone. A row moves it with
 * the poster frame by frame (its alpha the poster's growth); a grid, whose posters stay put, glides it from poster to
 * poster on Glass Home's focus curve ([glideTo]). Its box is fixed ([VodSizes.labelH]), so new words never lay
 * anything out; it holds [VodSizes.LABEL_LINES] lines broken at word boundaries ([PosterLabelText]).
 */
@SuppressLint("ViewConstructor")
class PosterLabel(context: Context, t: Tokens, posterW: Float) : TextView(context) {

    /** Lines no wider than the poster (a pixel kept spare, so the drawn line never breaks again). */
    private val lineW = t.dpf(posterW) - 1f
    private val measure: (String) -> Float = { paint.measureText(it) }
    private var title: String? = null

    // The glide (See all's grid): from where it was to where it goes, its alpha with it.
    private var fromX = 0f
    private var fromY = 0f
    private var fromA = 0f
    private var toX = 0f
    private var toY = 0f
    private var toA = 0f
    private var start = 0L
    private var gliding = false
    private val step = Runnable { frame() }

    init {
        val style = VodSizes.labelStyle(posterW)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, style.sp)
        typeface = t.faceOf(style.weight)
        setTextColor(t.colorOf(style))
        setShadowLayer(t.dpf(VodSizes.LABEL_SHADOW), 0f, t.dpf(VodSizes.LABEL_SHADOW_DY), VodSizes.LABEL_SHADOW_COLOR)
        includeFontPadding = false
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        maxLines = VodSizes.LABEL_LINES
        ellipsize = TextUtils.TruncateAt.END
        // Its lines are broken here, at spaces; Android's own breaking would hyphenate on the TV's API (30).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        }
        isFocusable = false
        // The poster itself is read out with its title.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        alpha = 0f
    }

    /** Shows [title] (null or blank: nothing, as for See all). The same title again changes nothing. */
    fun show(title: String?) {
        if (title == this.title) return
        this.title = title
        text = if (title.isNullOrBlank()) "" else PosterLabelText.wrap(title, lineW, VodSizes.LABEL_LINES, measure)
    }

    /**
     * Glides to ([x], [y]) at [alpha] over [VodSizes.LABEL_MS] on Glass Home's curve, or there at once with [now]. A
     * hidden label appears where it's sent: only its alpha glides.
     */
    fun glideTo(x: Float, y: Float, alpha: Float, now: Boolean = false) {
        if (now) {
            gliding = false
            removeCallbacks(step)
            translationX = x
            translationY = y
            this.alpha = alpha
            return
        }
        if (!gliding && this.alpha == 0f) {
            translationX = x
            translationY = y
        }
        fromX = translationX
        fromY = translationY
        fromA = this.alpha
        toX = x
        toY = y
        toA = alpha
        start = AnimationUtils.currentAnimationTimeMillis()
        if (!gliding) {
            gliding = true
            postOnAnimation(step)
        }
    }

    private fun frame() {
        if (!gliding) return
        val p = ((AnimationUtils.currentAnimationTimeMillis() - start).toFloat() / VodSizes.LABEL_MS).coerceIn(0f, 1f)
        val e = EASE.getInterpolation(p)
        translationX = fromX + (toX - fromX) * e
        translationY = fromY + (toY - fromY) * e
        alpha = fromA + (toA - fromA) * e
        if (p < 1f) postOnAnimation(step) else gliding = false
    }

    /** Its words and their shadow fade as one without an offscreen layer. */
    override fun hasOverlappingRendering(): Boolean = false

    private companion object {
        val EASE = PathInterpolator(VodSizes.LABEL_EASE_X1, VodSizes.LABEL_EASE_Y1, VodSizes.LABEL_EASE_X2, VodSizes.LABEL_EASE_Y2)
    }
}

/**
 * The rule for a poster's title under it, without Android types (unit-tested): broken at spaces into lines no wider than
 * the poster, at most a few; a word too wide for a line alone is cut where it stops fitting and goes on in the next.
 * When the title needs more lines, the last one keeps the whole words that fit with an ellipsis after them (a lone word
 * too wide is cut); a separator left before the ellipsis (a colon, a dash) goes.
 */
internal object PosterLabelText {

    const val ELLIPSIS = "…"
    private val SPACES = Regex("\\s+")
    private const val TRAILING = ",:;·–—-"

    /** [title] in at most [lines] lines no wider than [width] by [measure], joined with line breaks. */
    fun wrap(title: String, width: Float, lines: Int, measure: (String) -> Float): String {
        val words = title.trim().split(SPACES).filterTo(ArrayList()) { it.isNotEmpty() }
        if (words.isEmpty() || lines < 1) return ""
        val out = StringBuilder()
        var made = 0
        var i = 0
        // Every line but the last: as many whole words as fit.
        while (i < words.size && made < lines - 1) {
            var line = ""
            while (i < words.size) {
                val next = if (line.isEmpty()) words[i] else line + " " + words[i]
                if (measure(next) <= width) {
                    line = next
                    i++
                } else {
                    if (line.isEmpty()) {
                        // A word too wide for a line alone: the part that fits stays here, the rest goes on.
                        val w = words[i]
                        val cut = fit(w, width, measure).coerceAtLeast(1)
                        line = w.substring(0, cut)
                        if (cut < w.length) words[i] = w.substring(cut) else i++
                    }
                    break
                }
            }
            if (made > 0) out.append('\n')
            out.append(line)
            made++
        }
        if (i >= words.size) return out.toString()
        // The last line: the rest, or as much of it as fits with the ellipsis.
        val rest = words.subList(i, words.size)
        val all = rest.joinToString(" ")
        if (made > 0) out.append('\n')
        out.append(if (measure(all) <= width) all else ellipsized(rest, width, measure))
        return out.toString()
    }

    private fun ellipsized(words: List<String>, width: Float, measure: (String) -> Float): String {
        var line = ""
        for (w in words) {
            val next = if (line.isEmpty()) w else "$line $w"
            if (measure(trimmed(next) + ELLIPSIS) > width) break
            line = next
        }
        if (line.isNotEmpty()) return trimmed(line) + ELLIPSIS
        // Even the first word with the ellipsis is too wide: cut it.
        val w = words.first()
        var n = w.length
        while (n > 0 && measure(w.substring(0, n) + ELLIPSIS) > width) n--
        return w.substring(0, n) + ELLIPSIS
    }

    /** How many of [word]'s first characters fit in [width]. */
    private fun fit(word: String, width: Float, measure: (String) -> Float): Int {
        var n = word.length
        while (n > 0 && measure(word.substring(0, n)) > width) n--
        return n
    }

    private fun trimmed(s: String): String = s.trimEnd { it.isWhitespace() || it in TRAILING }
}
