package app.fireplayer.subs

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.text.style.UpdateAppearance
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

    private val lookAlike: Typeface? = ResourcesCompat.getFont(context, R.font.inter_600)
    private val arialFont: Typeface? = ResourcesCompat.getFont(context, R.font.arimo_500)
    private val figtreeFont: Typeface? = ResourcesCompat.getFont(context, R.font.figtree_600)
    private var importedFont: Typeface? = null
    private var style: SubtitleStyle = SubtitleStyle.NETFLIX

    init {
        reloadImportedFont()
    }

    /** Picks up a font sent from the phone (see [FontImport]). */
    fun reloadImportedFont() {
        val file = FontImport.customFontFile(context)
        importedFont = if (file.exists()) runCatching { Typeface.createFromFile(file) }.getOrNull() else null
    }

    val hasImportedFont: Boolean get() = importedFont != null

    fun apply(view: SubtitleView, style: SubtitleStyle) {
        this.style = style
        val typeface = when (style.font) {
            SubtitleFont.NETFLIX -> importedFont ?: lookAlike
            SubtitleFont.ARIAL -> arialFont
            SubtitleFont.FIGTREE -> figtreeFont
            SubtitleFont.SYSTEM -> Typeface.DEFAULT
        }
        // The soft shadow is drawn by [SoftShadowSpan] instead: the built-in one is a heavy 2dp block.
        val edgeType = if (style.edge == SubtitleEdge.OUTLINE) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE
        view.setStyle(
            CaptionStyleCompat(
                style.color,
                if (style.box) Color.argb(0xB3, 0, 0, 0) else Color.TRANSPARENT,
                Color.TRANSPARENT,
                edgeType,
                Color.argb(0xE6, 0, 0, 0),
                typeface,
            ),
        )
        view.setFractionalTextSize(style.textSize)
        view.setBottomPaddingFraction(style.bottom)
        view.setApplyEmbeddedFontSizes(false)
        // Keeps italics and bold from the file; colours, boxes and positions are removed in [normalize].
        view.setApplyEmbeddedStyles(true)
    }

    /**
     * Turns whatever text cues are showing into one cue, centred at the bottom of the screen like Netflix,
     * so overlapping lines stack instead of drawing over each other.
     */
    fun normalize(cues: List<Cue>): List<Cue> {
        if (cues.isEmpty()) return emptyList()
        val text = SpannableStringBuilder()
        cues.forEachIndexed { i, cue ->
            if (i > 0) text.append('\n')
            text.append(cue.text ?: "")
        }
        // Drop colours, boxes and font faces from the file so the chosen style wins.
        for (span in text.getSpans(0, text.length, Any::class.java)) {
            if (span is ForegroundColorSpan || span is BackgroundColorSpan || span is TypefaceSpan) text.removeSpan(span)
        }
        if (style.edge == SubtitleEdge.DROP_SHADOW) {
            text.setSpan(SoftShadowSpan(), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        val cue = cues[0].buildUpon()
            .setText(text)
            .setLine(Cue.DIMEN_UNSET, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.TYPE_UNSET)
            .setPosition(Cue.DIMEN_UNSET)
            .setPositionAnchor(Cue.TYPE_UNSET)
            .setSize(Cue.DIMEN_UNSET)
            .setTextSize(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
            .setTextAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMultiRowAlignment(null)
            .setVerticalType(Cue.TYPE_UNSET)
            .clearWindowColor()
            .build()
        return listOf(cue)
    }

    /** Example text shown while the Subtitle style window is open and the video is paused. */
    fun sample(): List<Cue> = normalize(
        listOf(Cue.Builder().setText("This is how your subtitles will look.\nChanges show up right away.").build()),
    )

    /** A soft shadow that scales with the text, close to Netflix's subtitles on TV. */
    private class SoftShadowSpan : CharacterStyle(), UpdateAppearance {
        override fun updateDrawState(tp: TextPaint) {
            val size = tp.textSize
            tp.setShadowLayer(size * 0.07f, size * 0.03f, size * 0.03f, Color.argb(0xCC, 0, 0, 0))
        }
    }
}
