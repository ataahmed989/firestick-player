package app.fireplayer.subs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.LineBackgroundSpan
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

    /** "Keep embedded styling": when on, colours from the subtitle file are kept. */
    var keepEmbedded: Boolean = false

    init {
        reloadImportedFont()
    }

    /** Picks up a font sent from the phone (see [FontImport]). */
    fun reloadImportedFont() {
        val file = FontImport.customFontFile(context)
        importedFont = if (file.exists()) runCatching { Typeface.createFromFile(file) }.getOrNull() else null
    }

    val hasImportedFont: Boolean get() = importedFont != null

    /** How far up from the bottom of the screen the subtitles sit, as a fraction of its height. */
    val bottomFraction: Float get() = style.bottom

    /** The text size, as a fraction of the screen height. */
    val textSizeFraction: Float get() = style.textSize

    /** The typeface subtitles are drawn in. */
    var typeface: Typeface = Typeface.DEFAULT
        private set

    fun apply(view: SubtitleView, style: SubtitleStyle) {
        this.style = style
        val typeface = when (style.font) {
            SubtitleFont.NETFLIX -> importedFont ?: lookAlike
            SubtitleFont.ARIAL -> arialFont
            SubtitleFont.FIGTREE -> figtreeFont
            SubtitleFont.SYSTEM -> Typeface.DEFAULT
        }
        this.typeface = typeface ?: Typeface.DEFAULT
        // The soft shadow is drawn by [SoftShadowSpan] instead: the built-in one is a heavy 2dp block.
        val edgeType = if (style.edge == SubtitleEdge.OUTLINE) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE
        // The background box is drawn by [BoxSpan], which pads it evenly around each line.
        view.setStyle(
            CaptionStyleCompat(
                style.color,
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                edgeType,
                (style.outlineColor and 0x00FFFFFF) or (0xE6 shl 24),
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
        // Unless embedded styling is kept, drop colours, boxes and font faces from the file so the chosen style wins.
        for (span in text.getSpans(0, text.length, Any::class.java)) {
            val colour = span is ForegroundColorSpan || span is BackgroundColorSpan
            if (span is TypefaceSpan || (colour && !keepEmbedded)) text.removeSpan(span)
        }
        if (style.edge == SubtitleEdge.DROP_SHADOW) {
            text.setSpan(SoftShadowSpan(style.shadow), 0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        if (style.box) {
            val color = (style.boxColor and 0x00FFFFFF) or ((style.boxOpacity * 255).toInt() shl 24)
            text.setSpan(
                BoxSpan(color, style.boxRounded, drawWithOutline = style.edge == SubtitleEdge.OUTLINE),
                0, text.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE,
            )
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

    private var recolorSource: Bitmap? = null
    private var recolorColor = 0
    private var recolorResult: Bitmap? = null

    /**
     * Picture subtitles (DVB, PGS, VobSub) are images, so their colour is baked in. Unless embedded
     * styling is kept, they're recoloured to the chosen colour: bright text takes the colour and
     * dark outlines stay dark.
     */
    fun pictures(cues: List<Cue>): List<Cue> {
        if (keepEmbedded || cues.isEmpty()) return cues
        return cues.map { cue ->
            val bitmap = cue.bitmap ?: return@map cue
            cue.buildUpon().setBitmap(recolor(bitmap, style.color)).build()
        }
    }

    private fun recolor(source: Bitmap, color: Int): Bitmap {
        val cached = recolorResult
        if (cached != null && source === recolorSource && color == recolorColor) return cached
        // Brightness times the colour, a little boosted so light grey and yellow text become fully coloured.
        val r = Color.red(color) / 255f * 1.25f
        val g = Color.green(color) / 255f * 1.25f
        val b = Color.blue(color) / 255f * 1.25f
        val matrix = ColorMatrix(
            floatArrayOf(
                0.299f * r, 0.587f * r, 0.114f * r, 0f, 0f,
                0.299f * g, 0.587f * g, 0.114f * g, 0f, 0f,
                0.299f * b, 0.587f * b, 0.114f * b, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val out = runCatching { Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888) }.getOrNull()
            ?: return source
        Canvas(out).drawBitmap(source, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) })
        recolorSource = source
        recolorColor = color
        recolorResult = out
        return out
    }

    /** Example text shown while the Subtitle style window is open and the video is paused. */
    fun sample(): List<Cue> = normalize(
        listOf(Cue.Builder().setText("This is how your subtitles will look.\nChanges show up right away.").build()),
    )

    /** A soft shadow that scales with the text, close to Netflix's subtitles on TV. */
    private class SoftShadowSpan(private val strength: ShadowStrength) : CharacterStyle(), UpdateAppearance {
        override fun updateDrawState(tp: TextPaint) {
            val size = tp.textSize
            tp.setShadowLayer(size * strength.radius, size * strength.offset, size * strength.offset, Color.argb(strength.alpha, 0, 0, 0))
        }
    }

    /**
     * A box behind each line that hugs the text: a little room on the sides and above and below
     * the block, and no gap between lines, so stacked lines read as one shape.
     */
    private class BoxSpan(color: Int, private val rounded: Boolean, private val drawWithOutline: Boolean) : LineBackgroundSpan {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val rect = RectF()
        private val path = Path()
        private val radii = FloatArray(8)

        override fun drawBackground(
            canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int, lineNumber: Int,
        ) {
            // With an outline the text is drawn twice: first the outline (stroked), then the fill.
            // The box goes under the outline, so it is drawn in that first pass only.
            val outlinePass = paint.style != Paint.Style.FILL
            if (outlinePass != drawWithOutline) return
            var lineEnd = end
            while (lineEnd > start && text[lineEnd - 1].isWhitespace()) lineEnd--
            if (lineEnd <= start) return
            val size = paint.textSize
            val width = paint.measureText(text, start, lineEnd)
            val centre = (left + right) / 2f
            val padX = size * 0.20f
            val padY = size * 0.07f
            // The outer edges follow the letters (capital height and descenders), not the font's
            // generous line box; between lines the box stays joined so there are no gaps.
            val first = lineNumber == 0
            val last = end >= text.length
            rect.set(
                centre - width / 2 - padX,
                if (first) baseline - size * 0.78f - padY else top.toFloat(),
                centre + width / 2 + padX,
                if (last) baseline + size * 0.24f + padY else bottom.toFloat(),
            )
            // Only the outer corners are rounded, so stacked lines join cleanly.
            val radius = if (rounded) size * 0.16f else 0f
            val topRadius = if (first) radius else 0f
            val bottomRadius = if (last) radius else 0f
            radii.fill(topRadius, 0, 4)
            radii.fill(bottomRadius, 4, 8)
            path.reset()
            path.addRoundRect(rect, radii, Path.Direction.CW)
            canvas.drawPath(path, fill)
        }
    }
}
