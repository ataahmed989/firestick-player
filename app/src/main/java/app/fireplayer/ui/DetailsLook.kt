package app.fireplayer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ReplacementSpan
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import app.fireplayer.R
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The look the two Details windows share, so they read alike: the title page's ⋯ › Details and the player's Film /
 * Show details (and its Episode details). The title with its parental-rating badge beside it ([titled]), the line of
 * facts under it ([line]), section headings in letter-spaced caps ([heading], as every window's headings), the story's
 * text ([body]), facts as a muted label and its value in two aligned columns ([facts]) and the hairlines between parts
 * ([hairline]). Views are made once as a window is drawn; nothing here runs per frame.
 */
object DetailsLook {

    /** Between the parts of the line under a title: "2009 • 2h 42m". */
    const val SEP = " • "

    /** A title: as the windows' own title (side_panel.xml), figtree 700. */
    const val TITLE_SP = 17f
    /** The line under a title and the facts' labels, figtree 500, muted. */
    const val LINE_SP = 12f
    /** The story, figtree 500, with [LINE_GAP_DP] between its lines. */
    const val BODY_SP = 13f
    const val LINE_GAP_DP = 2
    /** A fact's value, figtree 600 ([SidePanel.fact]'s size). */
    const val VALUE_SP = 12.5f
    /** A section's heading: figtree 700 caps, letter-spaced. */
    const val HEADING_SP = 10f
    const val HEADING_SPACING = 0.1f

    /** The parental-rating badge: figtree 700 in an outlined box with round corners, this far from the title. */
    const val BADGE_SP = 11f
    const val BADGE_GAP_DP = 10
    const val BADGE_PAD_H_DP = 6
    const val BADGE_PAD_V_DP = 3.5f
    const val BADGE_CORNER_DP = 4f
    const val BADGE_STROKE_DP = 1f
    const val BADGE_STROKE = 0xB3FFFFFF.toInt()

    /** The hairlines between a window's parts. */
    const val HAIRLINE = 0x33FFFFFF

    /** Between a fact's label column and its value; the label column's narrowest and widest. */
    const val LABEL_GAP_DP = 16
    const val LABEL_MIN_DP = 64
    const val LABEL_MAX_DP = 150

    private fun dp(context: Context, v: Float): Float = v * context.resources.displayMetrics.density

    private fun sp(context: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, context.resources.displayMetrics)

    // ---- The title and its badge ------------------------------------------------------------------------------------

    /**
     * [name] with the parental [rating] badge after it ("Runaways [TV-14]"), or [name] alone without one. The badge
     * stays with the title's last word when the title wraps, and is read out as its words.
     */
    fun titled(context: Context, name: String, rating: String?): CharSequence {
        val badge = rating?.trim()?.takeIf { it.isNotEmpty() } ?: return name
        // A narrow no-break space and no-break hyphens: the badge never parts from the title, nor breaks inside.
        val shown = " " + badge.replace('-', '‑')
        return SpannableStringBuilder(name).apply {
            val start = length
            append(shown)
            setSpan(RatingBadge(context, badge), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** A title's own look ([TITLE_SP], figtree 700, the text colour), as the window's title is drawn. */
    fun styleTitle(view: TextView) {
        view.typeface = ResourcesCompat.getFont(view.context, R.font.figtree_700)
        view.setTextColor(ContextCompat.getColor(view.context, R.color.text))
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
    }

    /**
     * The parental rating drawn as a small outlined badge in a title's line, centred on its letters. Its box is the
     * badge's letters' own height plus its padding, whatever the title's size.
     */
    private class RatingBadge(context: Context, private val label: String) : ReplacementSpan() {
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = ResourcesCompat.getFont(context, R.font.figtree_700)
            textSize = sp(context, BADGE_SP)
            color = ContextCompat.getColor(context, R.color.text)
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, dp(context, BADGE_STROKE_DP))
            color = BADGE_STROKE
        }
        private val gap = dp(context, BADGE_GAP_DP.toFloat())
        private val padH = dp(context, BADGE_PAD_H_DP.toFloat())
        private val padV = dp(context, BADGE_PAD_V_DP)
        private val corner = dp(context, BADGE_CORNER_DP)
        private val labelWidth = text.measureText(label)
        private val glyphs = Rect().also { text.getTextBounds(label, 0, label.length, it) }
        private val box = RectF()

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            // The line keeps the title's own height.
            if (fm != null) paint.getFontMetricsInt(fm)
            return ceil(gap + padH * 2 + labelWidth + stroke.strokeWidth).toInt()
        }

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            // The middle of the title's letters on this line.
            val middle = y + (paint.ascent() + paint.descent()) / 2f
            val half = stroke.strokeWidth / 2f
            val h = glyphs.height() / 2f + padV
            box.set(x + gap + half, middle - h, x + gap + padH * 2 + labelWidth - half, middle + h)
            canvas.drawRoundRect(box, corner, corner, stroke)
            canvas.drawText(label, x + gap + padH, middle - (glyphs.top + glyphs.bottom) / 2f, this.text)
        }
    }

    // ---- Lines of text ----------------------------------------------------------------------------------------------

    /** The line of facts under a title ("2017–2019 • 3 seasons • 48 min per episode"): [LINE_SP], muted. */
    fun line(context: Context, value: CharSequence): TextView = TextView(context).apply {
        text = value
        typeface = ResourcesCompat.getFont(context, R.font.figtree_500)
        setTextColor(ContextCompat.getColor(context, R.color.text_muted))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, LINE_SP)
        setLineSpacing(dp(context, LINE_GAP_DP.toFloat()), 1f)
    }

    /** The story's text, all of it: [BODY_SP], the text colour. */
    fun body(context: Context, value: CharSequence): TextView = TextView(context).apply {
        text = value
        typeface = ResourcesCompat.getFont(context, R.font.figtree_500)
        setTextColor(ContextCompat.getColor(context, R.color.text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, BODY_SP)
        setLineSpacing(dp(context, LINE_GAP_DP.toFloat()), 1f)
    }

    /** A section heading's words: small letter-spaced caps, as every window's headings ([HeadingLine]). */
    fun styleHeading(view: TextView) {
        view.typeface = ResourcesCompat.getFont(view.context, R.font.figtree_700)
        view.letterSpacing = HEADING_SPACING
        view.setTextColor(ContextCompat.getColor(view.context, R.color.heading))
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, HEADING_SP)
    }

    /** A section's heading ("STORY", "FACTS"), with no icon. */
    fun heading(context: Context, name: String): TextView = TextView(context).apply {
        styleHeading(this)
        text = name.uppercase()
        isSingleLine = true
    }

    /** A hairline across, between a window's parts. */
    fun hairline(context: Context): View = View(context).apply {
        setBackgroundColor(HAIRLINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hairlinePx(context))
    }

    fun hairlinePx(context: Context): Int = max(1, dp(context, 0.5f).roundToInt())

    // ---- Facts ------------------------------------------------------------------------------------------------------

    private fun labelPaint(context: Context) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.figtree_500)
        textSize = sp(context, LINE_SP)
    }

    /**
     * The label column's width for [labels]: the widest one and [LABEL_GAP_DP] after it, within [LABEL_MIN_DP] and
     * [LABEL_MAX_DP]. Given to every table of a window, so all its values start in one line.
     */
    fun labelWidth(context: Context, labels: Collection<String>): Int {
        val paint = labelPaint(context)
        val widest = labels.maxOfOrNull { paint.measureText(it) } ?: 0f
        val px = ceil(widest).toInt() + 1 + dp(context, LABEL_GAP_DP.toFloat()).roundToInt()
        return px.coerceIn(dp(context, LABEL_MIN_DP.toFloat()).roundToInt(), dp(context, LABEL_MAX_DP.toFloat()).roundToInt())
    }

    /**
     * Facts as two columns: each muted label in a column [labelWidthPx] wide ([labelWidth]), its value beside it on
     * the same baseline, wrapping to [valueLines] lines.
     */
    fun facts(context: Context, rows: List<Pair<String, String>>, labelWidthPx: Int, valueLines: Int = 3): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val muted = ContextCompat.getColor(context, R.color.text_muted)
            val white = ContextCompat.getColor(context, R.color.text)
            val labelFont = ResourcesCompat.getFont(context, R.font.figtree_500)
            val valueFont = ResourcesCompat.getFont(context, R.font.figtree_600)
            rows.forEachIndexed { i, (label, value) ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    // The label and its value's first line on one baseline (LinearLayout's own baseline alignment).
                    isBaselineAligned = true
                    addView(TextView(context).apply {
                        text = label
                        typeface = labelFont
                        setTextColor(muted)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, LINE_SP)
                        maxLines = 2
                    }, LinearLayout.LayoutParams(labelWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(TextView(context).apply {
                        text = value
                        typeface = valueFont
                        setTextColor(white)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, VALUE_SP)
                        setLineSpacing(dp(context, 1f), 1f)
                        maxLines = valueLines
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) topMargin = dp(context, ROW_GAP_DP).roundToInt()
                })
            }
        }

    /** Between two facts. */
    private const val ROW_GAP_DP = 5f
}
