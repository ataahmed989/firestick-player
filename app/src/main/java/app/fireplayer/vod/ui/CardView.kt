package app.fireplayer.vod.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import app.fireplayer.shell.TextStyle
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Glass
import app.fireplayer.ui.ImageLoader
import app.fireplayer.ui.ImageTarget
import app.fireplayer.ui.Rows
import app.fireplayer.vod.Card
import app.fireplayer.vod.data.Services
import app.fireplayer.vod.data.VodArt
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * One card in a row or the See all grid, drawn in [onDraw] (no child views, so a new title or picture only redraws
 * it and nothing is laid out). A poster, a wide Continue Watching card (its name, caption line and slim progress bar
 * on a gradient over its bottom, the picture above it clear), a show's episode card on the title page ([Kind.EPISODE]: a 16:9 still with its progress bar, NEW and ✓
 * badges, and two caption lines under it; dimmed when it hasn't aired), or the row's "See all" card. Until its picture
 * comes it shows the name on a dark gradient. The white ring round the chosen card (its picture) is drawn just outside
 * it, as strong as [focus]; it stays 3 dp however much the card is grown. Its picture is decoded at the grown size
 * (an episode still at its shown size), so it's sharp at both sizes. Every size, colour and text style is
 * [VodSizes]'s or [Tokens]'.
 */
@SuppressLint("ViewConstructor")
class CardView(
    context: Context,
    private val t: Tokens,
    val kind: Kind,
    /** Its picture's size, dp, not grown: the title page's cards' unless given (the browse pages', See all's). */
    private val pictureW: Float = if (kind == Kind.WIDE || kind == Kind.EPISODE) VodSizes.WIDE_W else VodSizes.POSTER_W,
    private val pictureH: Float = if (kind == Kind.WIDE || kind == Kind.EPISODE) VodSizes.WIDE_H else VodSizes.POSTER_H,
) : View(context), ImageTarget {

    enum class Kind {
        POSTER, WIDE, EPISODE, SEE_ALL,
        /** A streaming service's or brand's card: its wordmark whole in the middle of a dark card, its name until it comes. */
        LOGO,
    }

    var card: Card? = null
        private set

    /** How chosen it is: the ring's strength (and the See all card's white), 0..1. */
    var focus = 0f
        set(v) {
            val c = v.coerceIn(0f, 1f)
            if (field == c) return
            field = c
            invalidate()
        }

    override var imageKey: String? = null

    private var bitmap: Bitmap? = null
    private var shader: BitmapShader? = null
    private val shaderMatrix = Matrix()
    private var imageAlpha = 1f
    private var fader: ValueAnimator? = null

    private val radius = t.dpf(VodSizes.CARD_RADIUS)
    private val ringPx = t.dpf(VodSizes.RING)
    /** The picture's box: the whole card, or an episode's still above its captions. */
    private val box = RectF()
    private val ringBox = RectF()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    /** A logo card's wordmark and where it's drawn: kept whole, in the card's middle. */
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val logoBox = RectF()
    private val holderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = VodSizes.RING_COLOR }
    /** The glass look's very subtle edge of a card that isn't chosen ([app.fireplayer.ui.GlassStyle]). */
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = app.fireplayer.ui.GlassStyle.CARD_EDGE }
    private val edgePx = app.fireplayer.ui.GlassStyle.cardEdgePx(resources.displayMetrics.density)
    private val edgeAlpha = app.fireplayer.ui.GlassStyle.CARD_EDGE ushr 24
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = t.dpf(VodSizes.FLAT_RIM) }
    private val namePaint = textPaint(VodSizes.CARD_NAME)
    private val captionPaint = textPaint(VodSizes.CARD_CAPTION)
    private val holderTextPaint = textPaint(if (kind == Kind.LOGO) VodSizes.LOGO_NAME else VodSizes.CARD_HOLDER)
    private val seeAllPaint = textPaint(VodSizes.SEE_ALL_STYLE).apply { textAlign = Paint.Align.CENTER }
    private val badgePaint = textPaint(VodSizes.BADGE)
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = VodSizes.TRACK_COLOR }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent }
    // An episode's two lines: "E5 · Peacekeepers" (the number blue), "23m · Oct 5, 2013".
    private val numberPaint = textPaint(VodSizes.EPISODE_TITLE).apply { color = VodSizes.EPISODE_NUMBER }
    private val episodePaint = textPaint(VodSizes.EPISODE_TITLE)
    private val metaPaint = textPaint(VodSizes.EPISODE_META)

    // Text laid out once per title and size, never while drawing.
    private var holderLayout: Layout? = null
    private var nameLine: CharSequence = ""
    private var captionLine: CharSequence = ""
    private var numberLine: CharSequence = ""
    private var textFor = -1

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun textPaint(style: TextStyle) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = t.faceOf(style.weight)
        color = t.colorOf(style)
        textSize = style.sp * resources.displayMetrics.scaledDensity
    }

    /** The width and height its picture is decoded at, px: the grown card's (an episode's still: as shown). */
    val decodeW: Int get() = decodeSize().first
    val decodeH: Int get() = decodeSize().second

    private fun decodeSize(): Pair<Int, Int> {
        if (kind == Kind.EPISODE) return ceil(t.dpf(pictureW)).toInt() to ceil(t.dpf(pictureH)).toInt()
        if (kind == Kind.LOGO) {
            return ceil(t.dpf(pictureW) * VodSizes.LOGO_FILL_W * (1f + VodSizes.LOGO_GROW)).toInt() to
                ceil(t.dpf(pictureH) * VodSizes.LOGO_FILL_H * (1f + VodSizes.LOGO_GROW)).toInt()
        }
        val w = ceil(t.dpf(pictureW) * (1f + VodSizes.GROW)).toInt()
        val h = ceil(t.dpf(pictureH) * (1f + VodSizes.GROW)).toInt()
        // A poster never decoded wider than its source says it is (TMDB's w185, metahub's small poster): no larger
        // bitmap for no more detail.
        val source = card?.let { pictureOf(it) }?.let { sourceWidth(it) } ?: return w to h
        if (kind != Kind.POSTER || source >= w) return w to h
        return source to (h * source / w.toFloat()).roundToInt()
    }

    /** Shows [card]: only redrawn (its picture is bound separately, [bindImage]). */
    fun bind(card: Card) {
        if (card == this.card) return
        val samePicture = pictureOf(card) == this.card?.let { pictureOf(it) }
        this.card = card
        contentDescription = card.title.name
        textFor = -1
        if (!samePicture && imageKey != null) ImageLoader.release(this)
        if (kind == Kind.LOGO) logoPaint.colorFilter = if (Services.isMono(card.title.poster)) LOGO_WHITE else null
        invalidate()
    }

    /** The See all card. */
    fun bindSeeAll() {
        card = null
        contentDescription = "See all"
        invalidate()
    }

    /** Asks for its picture, at the grown size; nothing when it's bound already. */
    fun bindImage() {
        if (kind == Kind.SEE_ALL) return
        val c = card ?: return
        val url = pictureOf(c)
        if (url == null) {
            if (imageKey != null) ImageLoader.release(this)
            return
        }
        val (w, h) = decodeSize()
        if (kind == Kind.LOGO) {
            // Whole, with its transparency (a wordmark is never cropped).
            if (imageKey == null) ImageLoader.load(url, this, w, h, config = Bitmap.Config.ARGB_8888, fit = ImageLoader.Fit.INSIDE)
            return
        }
        val key = ImageLoader.keyOf(url, w, h)
        if (imageKey == key) return
        ImageLoader.load(url, this, w, h)
    }

    /** Lets its picture go (the card shows its name until it's bound again). */
    fun releaseImage() {
        if (imageKey != null || bitmap != null) ImageLoader.release(this)
    }

    /** As [releaseImage], and its picture isn't fetched at all if it's still queued for this card alone (a page left). */
    fun cancelImage() {
        if (imageKey != null || bitmap != null) ImageLoader.cancel(this)
    }

    private fun pictureOf(c: Card): String? = when (kind) {
        Kind.WIDE -> wideArt(c)
        Kind.EPISODE -> c.picture
        else -> c.title.poster
    }

    override fun setImage(bitmap: Bitmap?, fromMemory: Boolean) {
        fader?.cancel()
        fader = null
        this.bitmap = bitmap
        shader = bitmap?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        if (bitmap != null && !fromMemory) {
            imageAlpha = 0f
            fader = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = ImageLoader.FADE_MS
                addUpdateListener { imageAlpha = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            imageAlpha = 1f
        }
        invalidate()
    }

    /** The grid's chosen poster: its ring fades in or out (the poster grows separately). */
    fun animateFocus(on: Boolean) {
        focusFade?.cancel()
        focusFade = null
        val from = focus
        val to = if (on) 1f else 0f
        if (from == to) return
        focusFade = ValueAnimator.ofFloat(from, to).apply {
            duration = Tokens.FADE_MS
            addUpdateListener { focus = it.animatedValue as Float }
            start()
        }
    }

    private var focusFade: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val ph = if (kind == Kind.EPISODE) t.dpf(pictureH).coerceAtMost(h.toFloat()) else h.toFloat()
        box.set(0f, 0f, w.toFloat(), ph)
        holderPaint.shader = LinearGradient(0f, 0f, w.toFloat(), ph, VodSizes.HOLDER_TOP, VodSizes.HOLDER_BOTTOM, Shader.TileMode.CLAMP)
        // The gradient behind a wide card's words: clear above, easing to dark at the bottom (no visible start).
        val stops = VodSizes.WIDE_SCRIM_STOPS
        val from = stops.first()
        scrimPaint.shader = LinearGradient(
            0f, ph * from, 0f, ph,
            IntArray(stops.size) { VodSizes.WIDE_SCRIM_ALPHAS[it] shl 24 },
            FloatArray(stops.size) { ((stops[it] - from) / (1f - from)).coerceIn(0f, 1f) },
            Shader.TileMode.CLAMP,
        )
        textFor = -1
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        if (w <= 0) return
        when (kind) {
            Kind.SEE_ALL -> drawSeeAll(canvas)
            Kind.EPISODE -> drawEpisode(canvas)
            Kind.LOGO -> drawLogo(canvas)
            else -> drawCard(canvas)
        }
        drawEdge(canvas)
        drawRing(canvas)
    }

    /**
     * Glass look: a hairline just inside the picture's edge, so a dark picture still reads as a card on the dark
     * wallpaper. It fades out as the white ring of the chosen card fades in, and stays a hairline whatever the card's
     * growth. (See all has its own rim.)
     */
    private fun drawEdge(canvas: Canvas) {
        if (!Glass.on || kind == Kind.SEE_ALL) return
        val f = focus
        if (f >= 1f) return
        val scale = if (scaleX > 0f) scaleX else 1f
        val stroke = edgePx / scale
        edgePaint.strokeWidth = stroke
        edgePaint.alpha = (edgeAlpha * (1f - f)).toInt()
        val half = stroke / 2f
        ringBox.set(box.left + half, box.top + half, box.right - half, box.bottom - half)
        canvas.drawRoundRect(ringBox, radius - half, radius - half, edgePaint)
    }

    private fun drawCard(canvas: Canvas) {
        val c = card
        val bmp = bitmap
        val s = shader
        if (textFor != width) layOutText(c)
        if (bmp == null || imageAlpha < 1f) {
            canvas.drawRoundRect(box, radius, radius, holderPaint)
            if (kind == Kind.POSTER) holderLayout?.let { l ->
                canvas.save()
                canvas.translate((width - l.width) / 2f, (height - l.height) / 2f)
                l.draw(canvas)
                canvas.restore()
            }
        }
        if (bmp != null && s != null) drawPicture(canvas, bmp, s, 255)
        if (kind == Kind.WIDE && c != null) drawWideText(canvas, c)
        c?.badge?.let { drawBadge(canvas, it, right = true, alpha = 255) }
    }

    /** A logo card: the dark card, and its wordmark kept whole in the middle (its name until it has come). */
    private fun drawLogo(canvas: Canvas) {
        if (textFor != width) layOutText(card)
        canvas.drawRoundRect(box, radius, radius, holderPaint)
        val bmp = bitmap
        if (bmp == null || imageAlpha < 1f) holderLayout?.let { l ->
            canvas.save()
            canvas.translate((width - l.width) / 2f, (height - l.height) / 2f)
            l.draw(canvas)
            canvas.restore()
        }
        if (bmp == null) return
        val k = minOf(box.width() * VodSizes.LOGO_FILL_W / bmp.width, box.height() * VodSizes.LOGO_FILL_H / bmp.height)
        val w = bmp.width * k
        val h = bmp.height * k
        logoBox.set((box.width() - w) / 2f, (box.height() - h) / 2f, (box.width() + w) / 2f, (box.height() + h) / 2f)
        logoPaint.alpha = (255 * imageAlpha).toInt()
        canvas.drawBitmap(bmp, null, logoBox, logoPaint)
    }

    private fun drawPicture(canvas: Canvas, bmp: Bitmap, s: BitmapShader, alpha: Int) {
        shaderMatrix.setScale(box.width() / bmp.width.toFloat(), box.height() / bmp.height.toFloat())
        s.setLocalMatrix(shaderMatrix)
        imagePaint.shader = s
        imagePaint.alpha = (alpha * imageAlpha).toInt()
        canvas.drawRoundRect(box, radius, radius, imagePaint)
    }

    /** A wide card's bottom: a gradient under the name, the caption line and the progress bar; the picture above is clear. */
    private fun drawWideText(canvas: Canvas, c: Card) {
        canvas.drawRoundRect(box, radius, radius, scrimPaint)
        val side = t.dpf(VodSizes.WIDE_TEXT_SIDE)
        val progress = c.progress
        val bottom = height - if (progress != null) t.dpf(VodSizes.CARD_BOTTOM_BAR) else t.dpf(VodSizes.CARD_BOTTOM)
        val nameBaseline = if (captionLine.isNotEmpty()) {
            val captionBaseline = bottom - captionPaint.descent()
            canvas.drawText(captionLine, 0, captionLine.length, side, captionBaseline, captionPaint)
            // The name just above, 2 dp clear of the caption's tallest letters.
            captionBaseline + captionPaint.ascent() - t.dpf(VodSizes.CARD_LINE_GAP) - namePaint.descent()
        } else {
            bottom - namePaint.descent()
        }
        canvas.drawText(nameLine, 0, nameLine.length, side, nameBaseline, namePaint)
        if (progress != null) drawBar(canvas, progress, 255, side, t.dpf(VodSizes.WIDE_BAR_BOTTOM))
    }

    /** The slim progress bar along a picture's bottom (wide cards, episode stills): [side] from its edges, [bottom] up. */
    private fun drawBar(
        canvas: Canvas,
        progress: Float,
        alpha: Int,
        side: Float = t.dpf(VodSizes.CARD_TEXT_SIDE),
        bottom: Float = t.dpf(VodSizes.BAR_BOTTOM),
    ) {
        val barH = t.dpf(VodSizes.BAR_H)
        val top = box.bottom - bottom - barH
        val r = t.dpf(VodSizes.BAR_RADIUS)
        val right = width - side
        trackPaint.alpha = alpha * (VodSizes.TRACK_COLOR ushr 24) / 255
        barPaint.alpha = alpha
        canvas.drawRoundRect(side, top, right, top + barH, r, r, trackPaint)
        val p = progress.coerceIn(0f, 1f)
        if (p > 0f) canvas.drawRoundRect(side, top, side + (right - side) * p, top + barH, r, r, barPaint)
    }

    /**
     * An episode card: the still (or its placeholder), the progress bar, NEW at its top right and ✓ at its top left,
     * then "E5 · Peacekeepers" and "23m · Oct 5, 2013" under it. One that hasn't aired is drawn faint (its ring isn't).
     */
    private fun drawEpisode(canvas: Canvas) {
        val c = card ?: return
        val bmp = bitmap
        val s = shader
        if (textFor != width) layOutText(c)
        val alpha = if (c.playable) 255 else (255 * Rows.DIM).toInt()
        if (bmp == null || imageAlpha < 1f) {
            holderPaint.alpha = alpha
            canvas.drawRoundRect(box, radius, radius, holderPaint)
        }
        if (bmp != null && s != null) drawPicture(canvas, bmp, s, alpha)
        c.progress?.takeIf { it > 0f }?.let { drawBar(canvas, it, alpha) }
        c.badge?.let { drawBadge(canvas, it, right = true, alpha = alpha) }
        if (c.watched) drawBadge(canvas, VodSizes.WATCHED_MARK, right = false, alpha = alpha)
        // The captions under the still.
        val side = 0f
        val first = box.bottom + t.dpf(VodSizes.EPISODE_CAPTION_GAP) - episodePaint.ascent()
        numberPaint.alpha = alpha
        episodePaint.alpha = alpha
        metaPaint.alpha = alpha
        canvas.drawText(numberLine, 0, numberLine.length, side, first, numberPaint)
        val nx = side + numberPaint.measureText(numberLine, 0, numberLine.length)
        canvas.drawText(nameLine, 0, nameLine.length, nx, first, episodePaint)
        val second = first + episodePaint.descent() + t.dpf(VodSizes.EPISODE_LINE_GAP) - metaPaint.ascent()
        canvas.drawText(captionLine, 0, captionLine.length, side, second, metaPaint)
    }

    private fun drawBadge(canvas: Canvas, text: String, right: Boolean, alpha: Int) {
        val padX = t.dpf(VodSizes.BADGE_PAD_X)
        val padY = t.dpf(VodSizes.BADGE_PAD_Y)
        val tw = badgePaint.measureText(text)
        val th = badgePaint.descent() - badgePaint.ascent()
        val inset = t.dpf(VodSizes.BADGE_INSET)
        val l = if (right) width - inset - tw - 2 * padX else inset
        val top = inset
        val r = t.dpf(VodSizes.BADGE_RADIUS)
        badgeFill.alpha = alpha
        badgePaint.alpha = alpha
        canvas.drawRoundRect(l, top, l + tw + 2 * padX, top + th + 2 * padY, r, r, badgeFill)
        canvas.drawText(text, l + padX, top + padY - badgePaint.ascent(), badgePaint)
    }

    /**
     * See all: a flat fill in both looks (glass in a moving strip would show the wrong part of the wallpaper), white
     * with blue words when chosen.
     */
    private fun drawSeeAll(canvas: Canvas) {
        val f = focus
        val plain = if (Glass.on) t.groupFillGlass else t.rowFill
        fillPaint.color = blend(plain, t.focusFill, f)
        canvas.drawRoundRect(box, radius, radius, fillPaint)
        if (Glass.on && f < 1f) {
            rimPaint.color = t.groupRimGlass
            rimPaint.alpha = (alphaOf(t.groupRimGlass) * (1f - f)).toInt()
            val half = rimPaint.strokeWidth / 2
            ringBox.set(half, half, width - half, height - half)
            canvas.drawRoundRect(ringBox, radius, radius, rimPaint)
        }
        seeAllPaint.color = blend(t.text, t.accent, f)
        val y = height / 2f - (seeAllPaint.descent() + seeAllPaint.ascent()) / 2f
        canvas.drawText(VodSizes.SEE_ALL, width / 2f, y, seeAllPaint)
    }

    private fun drawRing(canvas: Canvas) {
        val f = focus
        if (f <= 0f) return
        // As thick on screen whatever the card's growth: the view's scale applies to what it draws.
        val scale = if (scaleX > 0f) scaleX else 1f
        val stroke = ringPx / scale
        ringPaint.strokeWidth = stroke
        ringPaint.alpha = (255 * f).toInt()
        val half = stroke / 2f
        ringBox.set(box.left - half, box.top - half, box.right + half, box.bottom + half)
        canvas.drawRoundRect(ringBox, radius + half, radius + half, ringPaint)
    }

    private fun layOutText(c: Card?) {
        textFor = width
        val name = c?.title?.name.orEmpty()
        val avail = (width - 2 * t.dpf(if (kind == Kind.WIDE) VodSizes.WIDE_TEXT_SIDE else VodSizes.CARD_TEXT_SIDE)).coerceAtLeast(1f)
        when (kind) {
            Kind.WIDE -> {
                nameLine = TextUtils.ellipsize(name, namePaint, avail, TextUtils.TruncateAt.END)
                captionLine = TextUtils.ellipsize(c?.caption.orEmpty(), captionPaint, avail, TextUtils.TruncateAt.END)
                holderLayout = null
            }
            Kind.EPISODE -> {
                // "E5" in blue, then " · Peacekeepers"; the line under it as the row worded it.
                val ep = c?.episode
                numberLine = ep?.let { TitleRules.episodeNumber(it) }.orEmpty()
                val w = width.toFloat()
                val rest = ep?.let { TitleRules.episodeNameAfterNumber(it) }.orEmpty()
                val restW = (w - numberPaint.measureText(numberLine, 0, numberLine.length)).coerceAtLeast(1f)
                nameLine = TextUtils.ellipsize(rest, episodePaint, restW, TextUtils.TruncateAt.END)
                captionLine = TextUtils.ellipsize(c?.caption.orEmpty(), metaPaint, w.coerceAtLeast(1f), TextUtils.TruncateAt.END)
                holderLayout = null
            }
            else -> holderLayout = if (name.isEmpty()) null else makeLayout(name, avail.toInt())
        }
    }

    @Suppress("DEPRECATION")
    private fun makeLayout(text: String, w: Int): Layout =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, holderTextPaint, w)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(VodSizes.HOLDER_LINES)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .build()
        } else {
            StaticLayout(text, holderTextPaint, w, Layout.Alignment.ALIGN_CENTER, 1f, 0f, false)
        }

    private companion object {
        fun alphaOf(c: Int) = c ushr 24

        /** One-colour wordmarks drawn white ([VodSizes.LOGO_MONO]). */
        val LOGO_WHITE = android.graphics.PorterDuffColorFilter(VodSizes.LOGO_MONO, android.graphics.PorterDuff.Mode.SRC_IN)

        /** TMDB's picture width in its address ("…/t/p/w185/…"); null for any other. */
        private val TMDB_WIDTH = Regex("""image\.tmdb\.org/t/p/w(\d+)/""")

        /** Metahub's small poster ("…/poster/small/tt…/img"): 300 px wide. */
        private const val METAHUB_SMALL_POSTER = "metahub.space/poster/small/"
        private const val METAHUB_SMALL_POSTER_W = 300

        fun sourceWidth(url: String): Int? =
            if (url.contains(METAHUB_SMALL_POSTER)) METAHUB_SMALL_POSTER_W else TMDB_WIDTH.find(url)?.groupValues?.get(1)?.toIntOrNull()

        /** [a] towards [b] by [f], channel by channel. */
        fun blend(a: Int, b: Int, f: Float): Int {
            if (f <= 0f) return a
            if (f >= 1f) return b
            fun ch(shift: Int): Int {
                val x = (a ushr shift) and 0xFF
                val y = (b ushr shift) and 0xFF
                return (x + (y - x) * f).toInt() and 0xFF
            }
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}

/**
 * A wide card's picture: the title's backdrop at the wide cards' size (metahub `background/small` or TMDB `w780`, about
 * a third of the header's), else its poster.
 */
internal fun wideArt(c: Card): String? = VodArt.backgroundAt(c.title.background, VodArt.Size.SMALL) ?: c.title.poster
