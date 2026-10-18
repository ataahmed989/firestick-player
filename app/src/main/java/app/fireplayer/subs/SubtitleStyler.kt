package app.fireplayer.subs

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import androidx.annotation.OptIn
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import app.fireplayer.R

/** Applies a [SubtitleStyle] to the full-screen subtitle view. */
@OptIn(UnstableApi::class)
class SubtitleStyler(private val context: Context) {

    private val netflixFont: Typeface? = ResourcesCompat.getFont(context, R.font.arimo_500)
    private val figtreeFont: Typeface? = ResourcesCompat.getFont(context, R.font.figtree_600)

    fun apply(view: SubtitleView, style: SubtitleStyle) {
        val typeface = when (style.font) {
            SubtitleFont.NETFLIX -> netflixFont
            SubtitleFont.FIGTREE -> figtreeFont
            SubtitleFont.SYSTEM -> Typeface.DEFAULT
        }
        val edgeType = when (style.edge) {
            SubtitleEdge.DROP_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            SubtitleEdge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            SubtitleEdge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
        }
        view.setStyle(
            CaptionStyleCompat(
                style.color,
                if (style.box) Color.argb(0xB3, 0, 0, 0) else Color.TRANSPARENT,
                Color.TRANSPARENT,
                edgeType,
                Color.BLACK,
                typeface,
            ),
        )
        view.setFractionalTextSize(style.textSize)
        view.setBottomPaddingFraction(style.bottom)
        view.setApplyEmbeddedFontSizes(false)
        // Keeps italics from the file, drops its colours and positions (see [normalize]).
        view.setApplyEmbeddedStyles(true)
    }

    /**
     * Text cues are re-centred at the bottom of the screen, like Netflix,
     * whatever position the subtitle file asked for.
     */
    fun normalize(cue: Cue): Cue = cue.buildUpon()
        .setLine(Cue.DIMEN_UNSET, Cue.LINE_TYPE_FRACTION)
        .setLineAnchor(Cue.TYPE_UNSET)
        .setPosition(Cue.DIMEN_UNSET)
        .setPositionAnchor(Cue.TYPE_UNSET)
        .setSize(Cue.DIMEN_UNSET)
        .setTextSize(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
        .setTextAlignment(Layout.Alignment.ALIGN_CENTER)
        .setVerticalType(Cue.TYPE_UNSET)
        .clearWindowColor()
        .build()
}
