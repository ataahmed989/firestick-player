package app.fireplayer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import app.fireplayer.R

/**
 * The Settings tab's row, the one row look of the app's own pages (the Settings tab's categories; the Search tab's bar
 * and its history): [ROW_DP] tall and rounded ([Glass.Kind.ROW]: the glass or classic row fill, white when focused), an
 * icon and one line of words, dark on the white focused row and light otherwise ([RowLayout]). [text] is the row's
 * words: a label, or the Search bar's box to type in. A change made here reaches every page that shows one.
 */
@SuppressLint("ViewConstructor")
class PageRow(
    context: Context,
    iconRes: Int?,
    val text: TextView = TextView(context),
    /** The row's glass: [Glass.Kind.ROW], or a cell-glass row for the Search tab ([Glass.Kind.CELL] pods, [Glass.Kind.CELL_BAR] its bar). */
    private val kind: Glass.Kind = Glass.Kind.ROW,
) : RowLayout(context, ContextCompat.getColorStateList(context, R.color.sel_text_row)) {

    val icon: ImageView? = iconRes?.let { res -> ImageView(context).apply { setImageResource(res) } }

    init {
        val density = resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        minimumHeight = dp(ROW_DP)
        setPadding(dp(PAD_DP), 0, dp(PAD_DP), 0)
        applyLook()
        icon?.let { addView(it, LayoutParams(dp(ICON_DP), dp(ICON_DP)).apply { marginEnd = dp(ICON_GAP_DP) }) }
        text.apply {
            typeface = ResourcesCompat.getFont(context, R.font.figtree_600)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(text, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    /** The look changed: the row's fill made again. */
    fun applyLook() = Glass.background(this, kind)

    companion object {
        /** A row's height and the gap under it in a list, dp. */
        const val ROW_DP = 44f
        const val GAP_DP = 8f
        const val PAD_DP = 14f
        const val ICON_DP = 20f
        const val ICON_GAP_DP = 12f
        const val TEXT_SP = 15f
    }
}
