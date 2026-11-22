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
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.fireplayer.shell.TextStyle
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Glass
import app.fireplayer.ui.GlassStyle
import app.fireplayer.ui.ImageLoader
import app.fireplayer.ui.ImageTarget
import app.fireplayer.ui.RowMotion
import app.fireplayer.ui.Rows
import app.fireplayer.vod.Card
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * A box that cuts what it holds at its own edges: a strip of cards wider than it (beside the title page's action
 * column, where the page's own left edge is too far away to cut them). Its children don't clip, so a chosen card's
 * ring and growth show inside it.
 */
open class ClipBox(context: Context) : FrameLayout(context) {
    private val clip = Rect()

    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clip.set(0, 0, w, h)
        clipBounds = clip
    }
}

/**
 * An episode on a show's title page (Design-References/redesign-2026-11-19, concept 3): a sharp 16:9 still over an
 * information base with "E1 · A Shadow of the Past" (the number in the Episodes blue) and "1h 7m · Sep 1, 2022". Its
 * marks are the episode cards': the progress bar along the still's bottom, NEW at its top right, ✓ at its top left,
 * and one that hasn't aired drawn faint.
 *
 * The base is glass in the page ([GlassStyle]: the window tint over whatever is behind, a hairline round the pod and
 * the highlight along its top; nothing copied or blurred, so it's free in a moving row), or the classic look's solid
 * row surface. The chosen pod has the cards' white ring outside it ([VodSizes.RING]), as strong as [focus] and as thick
 * however much the pod has grown. It draws itself (one view, no children); its still is decoded at the shown size.
 */
@SuppressLint("ViewConstructor")
class EpisodePod(context: Context, private val t: Tokens) : View(context), ImageTarget {

    var card: Card? = null
        private set

    /** How chosen it is: the ring's strength, 0..1 (the hairline fades as it comes). */
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

    private val density = resources.displayMetrics.density
    private val pictureDp = t.dpf(VodSizes.POD_PICTURE_H)
    private val ringPx = t.dpf(VodSizes.RING)
    private val hairPx = GlassStyle.hairlinePx(density)
    private val highlightPx = GlassStyle.HIGHLIGHT_DP * density
    private val hairAlpha = GlassStyle.HAIRLINE ushr 24
    private var radius = 0f
    private var pictureH = 0f
    private val box = RectF()

    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val holderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = GlassStyle.HAIRLINE }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = VodSizes.RING_COLOR }
    private val badgePaint = textPaint(VodSizes.BADGE)
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = VodSizes.TRACK_COLOR }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent }
    private val numberPaint = textPaint(VodSizes.POD_TITLE).apply { color = VodSizes.EPISODE_NUMBER }
    private val titlePaint = textPaint(VodSizes.POD_TITLE)
    private val metaPaint = textPaint(VodSizes.POD_META)

    // Its words, cut to its width once per card and size, never while drawing.
    private var numberLine: CharSequence = ""
    private var nameLine: CharSequence = ""
    private var metaLine: CharSequence = ""
    private var textFor = -1

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun textPaint(style: TextStyle) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = t.faceOf(style.weight)
        color = t.colorOf(style)
        textSize = style.sp * resources.displayMetrics.scaledDensity
    }

    /** Shows [card] (an episode's, [TitleRules.episodeCard]): only redrawn; its still is bound separately. */
    fun bind(card: Card) {
        if (card == this.card) return
        val samePicture = card.picture == this.card?.picture
        this.card = card
        val ep = card.episode
        contentDescription = if (ep == null) card.title.name else TitleRules.episodeNumber(ep) + TitleRules.episodeNameAfterNumber(ep)
        textFor = -1
        if (!samePicture && imageKey != null) ImageLoader.release(this)
        invalidate()
    }

    /** Asks for its still, at the shown size; nothing when it's bound already. */
    fun bindImage() {
        val url = card?.picture
        if (url == null) {
            if (imageKey != null) ImageLoader.release(this)
            return
        }
        val w = ceil(t.dpf(VodSizes.POD_W)).toInt()
        val h = ceil(pictureDp).toInt()
        if (imageKey == ImageLoader.keyOf(url, w, h)) return
        ImageLoader.load(url, this, w, h)
    }

    /** Lets its still go (the placeholder shows until it's bound again). */
    fun releaseImage() {
        if (imageKey != null || bitmap != null) ImageLoader.release(this)
    }

    /** As [releaseImage], and the still isn't fetched at all if it's still queued for this pod alone (a page left). */
    fun cancelImage() {
        if (imageKey != null || bitmap != null) ImageLoader.cancel(this)
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

    /** The look changed: its corners and surface are the new look's. */
    fun applyLook() {
        shape()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = shape()

    private fun shape() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        // The shared corners of a small window, in the look that's on.
        radius = Glass.cornerRadius(context, Glass.Kind.POPUP, w, h)
        pictureH = pictureDp.coerceAtMost(h.toFloat())
        holderPaint.shader = LinearGradient(0f, 0f, w.toFloat(), pictureH, VodSizes.HOLDER_TOP, VodSizes.HOLDER_BOTTOM, Shader.TileMode.CLAMP)
        // The highlight follows the top corners and is gone below them.
        highlightPaint.shader = LinearGradient(0f, 0f, 0f, radius.coerceAtLeast(1f), GlassStyle.HIGHLIGHT, GlassStyle.HIGHLIGHT and 0x00FFFFFF, Shader.TileMode.CLAMP)
        textFor = -1
    }

    override fun onDraw(canvas: Canvas) {
        val c = card ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (textFor != width) layOutText(c)
        val glass = Glass.on
        val alpha = if (c.playable) 255 else (255 * Rows.DIM).toInt()
        val f = focus

        // The base: the pod's rounded shape below the still.
        basePaint.color = if (glass) Glass.appShade.color else t.rowFill
        canvas.save()
        canvas.clipRect(0f, pictureH, w, h)
        canvas.drawRoundRect(0f, pictureH - radius, w, h, radius, radius, basePaint)
        canvas.restore()

        // The still (its placeholder until it's there): the pod's rounded shape above the base.
        val bmp = bitmap
        val s = shader
        canvas.save()
        canvas.clipRect(0f, 0f, w, pictureH)
        if (bmp == null || s == null || imageAlpha < 1f) {
            holderPaint.alpha = alpha
            canvas.drawRoundRect(0f, 0f, w, pictureH + radius, radius, radius, holderPaint)
        }
        if (bmp != null && s != null) {
            shaderMatrix.setScale(w / bmp.width.toFloat(), pictureH / bmp.height.toFloat())
            s.setLocalMatrix(shaderMatrix)
            imagePaint.shader = s
            imagePaint.alpha = (alpha * imageAlpha).toInt()
            canvas.drawRoundRect(0f, 0f, w, pictureH + radius, radius, radius, imagePaint)
        }
        canvas.restore()
        c.progress?.takeIf { it > 0f }?.let { drawBar(canvas, it, alpha) }
        c.badge?.let { drawBadge(canvas, it, right = true, alpha = alpha) }
        if (c.watched) drawBadge(canvas, VodSizes.WATCHED_MARK, right = false, alpha = alpha)

        // "E1 · A Shadow of the Past", then "1h 7m · Sep 1, 2022".
        val side = t.dpf(VodSizes.POD_TEXT_SIDE)
        val first = pictureH + t.dpf(VodSizes.POD_TEXT_TOP) - titlePaint.ascent()
        numberPaint.alpha = alpha
        titlePaint.alpha = alpha
        metaPaint.alpha = alpha
        canvas.drawText(numberLine, 0, numberLine.length, side, first, numberPaint)
        val nx = side + numberPaint.measureText(numberLine, 0, numberLine.length)
        canvas.drawText(nameLine, 0, nameLine.length, nx, first, titlePaint)
        val second = first + titlePaint.descent() + t.dpf(VodSizes.POD_LINE_GAP) - metaPaint.ascent()
        canvas.drawText(metaLine, 0, metaLine.length, side, second, metaPaint)

        // As thick on screen whatever the pod's growth: the view's scale applies to what it draws.
        val scale = if (scaleX > 0f) scaleX else 1f
        if (glass && f < 1f) {
            val stroke = hairPx / scale
            val half = stroke / 2f
            box.set(half, half, w - half, h - half)
            hairPaint.strokeWidth = stroke
            hairPaint.alpha = (hairAlpha * (1f - f)).toInt()
            canvas.drawRoundRect(box, radius - half, radius - half, hairPaint)
            highlightPaint.strokeWidth = highlightPx / scale
            highlightPaint.alpha = (255 * (1f - f)).toInt()
            canvas.drawRoundRect(box, radius - half, radius - half, highlightPaint)
        }
        if (f > 0f) {
            val stroke = ringPx / scale
            val half = stroke / 2f
            box.set(-half, -half, w + half, h + half)
            ringPaint.strokeWidth = stroke
            ringPaint.alpha = (255 * f).toInt()
            canvas.drawRoundRect(box, radius + half, radius + half, ringPaint)
        }
    }

    /** The progress bar along the still's bottom (the wide cards' slim bar). */
    private fun drawBar(canvas: Canvas, progress: Float, alpha: Int) {
        val side = t.dpf(VodSizes.POD_TEXT_SIDE)
        val barH = t.dpf(VodSizes.BAR_H)
        val top = pictureH - t.dpf(VodSizes.BAR_BOTTOM) - barH
        val r = t.dpf(VodSizes.BAR_RADIUS)
        val right = width - side
        trackPaint.alpha = alpha * (VodSizes.TRACK_COLOR ushr 24) / 255
        barPaint.alpha = alpha
        canvas.drawRoundRect(side, top, right, top + barH, r, r, trackPaint)
        val p = progress.coerceIn(0f, 1f)
        if (p > 0f) canvas.drawRoundRect(side, top, side + (right - side) * p, top + barH, r, r, barPaint)
    }

    private fun drawBadge(canvas: Canvas, text: String, right: Boolean, alpha: Int) {
        val padX = t.dpf(VodSizes.BADGE_PAD_X)
        val padY = t.dpf(VodSizes.BADGE_PAD_Y)
        val tw = badgePaint.measureText(text)
        val th = badgePaint.descent() - badgePaint.ascent()
        val inset = t.dpf(VodSizes.BADGE_INSET)
        val l = if (right) width - inset - tw - 2 * padX else inset
        val r = t.dpf(VodSizes.BADGE_RADIUS)
        badgeFill.alpha = alpha
        badgePaint.alpha = alpha
        canvas.drawRoundRect(l, inset, l + tw + 2 * padX, inset + th + 2 * padY, r, r, badgeFill)
        canvas.drawText(text, l + padX, inset + padY - badgePaint.ascent(), badgePaint)
    }

    private fun layOutText(c: Card) {
        textFor = width
        val avail = (width - 2 * t.dpf(VodSizes.POD_TEXT_SIDE)).coerceAtLeast(1f)
        val ep = c.episode
        numberLine = ep?.let { TitleRules.episodeNumber(it) }.orEmpty()
        val rest = if (ep == null) c.title.name else TitleRules.episodeNameAfterNumber(ep)
        val restW = (avail - numberPaint.measureText(numberLine, 0, numberLine.length)).coerceAtLeast(1f)
        nameLine = TextUtils.ellipsize(rest, titlePaint, restW, TextUtils.TruncateAt.END)
        metaLine = TextUtils.ellipsize(c.caption.orEmpty(), metaPaint, avail, TextUtils.TruncateAt.END)
    }
}

/**
 * A show's episodes on its title page, beside the action column: a strip of pods ([EpisodePod]) moved like the rows'
 * cards ([RowMotion]: the chosen pod at the strip's left edge, grown [VodSizes.POD_GROW], the ones after it making
 * room), about two and part of a third in view. It cuts the pods at its own left edge ([ClipBox]), a gap's width left
 * of the chosen one, so the pods before it never run under the buttons. It owns its pods, kept and filled in place
 * when new cards come (so focus survives), and remembers the pod chosen in it ([chosen]). Keys on its pods go to the
 * page ([Listener]); nothing here asks Android's focus search.
 */
@SuppressLint("ViewConstructor")
class EpisodeRow(context: Context, private val t: Tokens, private val listener: Listener) : ClipBox(context) {

    interface Listener {
        fun onEpisodeFocused(index: Int)
        /** Focus left the pods altogether. True: the chosen pod shrinks back (false: its small window opened). */
        fun onEpisodesLeft(): Boolean
        fun onEpisodeKey(index: Int, keyCode: Int, event: KeyEvent): Boolean
    }

    /** The pods in order, one for each card. */
    val items = ArrayList<EpisodePod>()
    var cards: List<Card> = emptyList()
        private set

    /** The pod chosen in this row (kept while focus is elsewhere). */
    var chosen = 0

    /** Which pods hold their stills. */
    var boundRange: IntRange = IntRange.EMPTY

    private val podW = t.dp(VodSizes.POD_W)
    private val podH = t.dp(VodSizes.podH())
    private val pitch = t.dpf(VodSizes.podPitch())
    private val extra = t.dp(VodSizes.podExtra())
    private val strip = Strip(context)

    val motion = RowMotion(object : RowMotion.Host {
        override val itemCount: Int get() = items.size
        override fun itemLeft(i: Int): Float = leftOf(i).toFloat()
        override fun place(offset: Float) {
            strip.translationX = -offset
        }

        override fun item(i: Int, share: Float, shift: Float) {
            val v = items[i]
            v.translationX = shift
            val grow = 1f + VodSizes.POD_GROW * share
            v.scaleX = grow
            v.scaleY = grow
            v.focus = share
        }
    })

    private val focusWatch = OnFocusChangeListener { v, has ->
        val i = indexOfPod(v)
        if (i < 0) return@OnFocusChangeListener
        if (has) {
            chosen = i
            motion.choose(i)
            listener.onEpisodeFocused(i)
        } else {
            // To the next pod: that one takes over at once. Elsewhere: the pod shrinks back, the row stays put.
            post {
                if (strip.findFocus() == null && listener.onEpisodesLeft()) motion.shrink()
            }
        }
    }

    private val keyWatch = OnKeyListener { v, keyCode, event ->
        val i = indexOfPod(v)
        if (i < 0) false else listener.onEpisodeKey(i, keyCode, event)
    }

    init {
        motion.extra = t.dpf(VodSizes.podExtra())
        // The first pod starts a gap's width inside the box (its ring and growth show), with room above and below.
        addView(strip, LayoutParams(LayoutParams.WRAP_CONTENT, podH).apply {
            leftMargin = t.dp(VodSizes.POD_GAP)
            topMargin = t.dp(VodSizes.POD_ROOM)
        })
        visibility = GONE
    }

    private fun leftOf(i: Int): Int = (i * pitch).roundToInt()

    private fun indexOfPod(v: View): Int {
        for (i in items.indices) if (items[i] === v) return i
        return -1
    }

    /** The index of the pod holding focus, or -1. */
    fun focusedIndex(): Int {
        val f = strip.findFocus() ?: return -1
        return indexOfPod(f)
    }

    /**
     * Shows [cards], filling the pods in place: the same views stay, so a focused one keeps focus. The caller moves
     * focus off pods that go first.
     */
    fun bind(cards: List<Card>) {
        this.cards = cards
        for (i in items.size - 1 downTo cards.size) {
            val v = items.removeAt(i)
            v.releaseImage()
            strip.removeView(v)
        }
        for (i in items.size until cards.size) {
            val v = makePod()
            items += v
            strip.addView(v)
        }
        cards.forEachIndexed { i, c -> items[i].bind(c) }
        chosen = if (items.isEmpty()) 0 else chosen.coerceIn(0, items.size - 1)
        boundRange = IntRange.EMPTY
        motion.apply()
    }

    /** The pod chosen in this row takes focus (back to this row from elsewhere in the page). */
    fun focusChosen(): Boolean {
        if (items.isEmpty()) return false
        chosen = chosen.coerceIn(0, items.size - 1)
        return items[chosen].requestFocus()
    }

    /** Stills for the pods in [range]; the others let theirs go. */
    fun bindImages(range: IntRange) {
        boundRange = range
        for (i in items.indices) if (i in range) items[i].bindImage() else items[i].releaseImage()
    }

    /** Lets every pod's still go; with [cancel] (a page left), stills still queued for them aren't fetched. */
    fun releaseImages(cancel: Boolean = false) {
        boundRange = IntRange.EMPTY
        for (v in items) if (cancel) v.cancelImage() else v.releaseImage()
    }

    fun applyLook() {
        for (v in items) v.applyLook()
    }

    private fun makePod(): EpisodePod = EpisodePod(context, t).apply {
        isFocusable = true
        // OK is read by the page's key listener (a short press, or held for the small window): never View's own click.
        isClickable = false
        isLongClickable = false
        onFocusChangeListener = focusWatch
        setOnKeyListener(keyWatch)
    }

    /**
     * The pods at fixed places (pod i at i x the pitch), laid out once and again only when their number changes. It's
     * wider than the row and never scrolls itself: [motion] moves it with translationX alone. Each pod grows from its
     * left edge about its middle height.
     */
    private inner class Strip(context: Context) : ViewGroup(context) {
        init {
            clipChildren = false
            clipToPadding = false
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cw = MeasureSpec.makeMeasureSpec(podW, MeasureSpec.EXACTLY)
            val ch = MeasureSpec.makeMeasureSpec(podH, MeasureSpec.EXACTLY)
            for (i in 0 until childCount) getChildAt(i).measure(cw, ch)
            // Wider than the screen: the parent's limit doesn't apply.
            val w = if (childCount == 0) 0 else leftOf(childCount - 1) + podW + extra
            setMeasuredDimension(w, podH)
        }

        override fun onLayout(changed: Boolean, l: Int, top: Int, r: Int, b: Int) {
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val left = leftOf(i)
                c.layout(left, 0, left + podW, podH)
                c.pivotX = 0f
                c.pivotY = podH / 2f
            }
        }

        // Nothing above this strip scrolls to show a focused pod: the row's motion places it.
        override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean): Boolean = true

        override fun shouldDelayChildPressedState(): Boolean = false
    }
}
