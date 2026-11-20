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
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import app.fireplayer.ui.GlassStyle
import app.fireplayer.ui.ImageLoader
import app.fireplayer.ui.ImageTarget

/**
 * A title's backdrop, as Films & Shows' headers show it: the title's background picture decoded small (half the size
 * it's shown at), its left and bottom fading to clear baked in once on the image thread ([ArtSpec.bake]), so whatever
 * is under it shows through and nothing is blurred or masked per frame. A new title's picture crossfades in over the
 * old one ([ArtSpec.fadeMs]): one view redrawn, nothing laid out.
 *
 * Two uses, one part: the page banner's picture ([ArtSpec.BANNER]: sharp and whole, decoded at the size it's shown,
 * crossfading in 650 ms, Glass Home's hero fade; where its box is narrower than the card its left edge is covered
 * ([leftFade]) so it has no edge, and the banner's dark shading is drawn over it separately, by [VodHeader]) and
 * the title page's full-screen backdrop ([ArtSpec.TITLE]: decoded at 960 x 540 px, drawn over the whole screen
 * ([frame]) and shown first from a small copy ([hold], [preview]) that the real one crossfades over in 140 ms).
 */
@SuppressLint("ViewConstructor")
class HeaderArt(context: Context, private val spec: ArtSpec = ArtSpec.HEADER) : View(context), ImageTarget {

    /**
     * How a backdrop is decoded and shown: [decodeW] x [decodeH] px (0: the view's size, or half of it with [half]),
     * the fade baked into it, the memory key's suffix for that bake, where the crop sits ([cropY]) and the crossfade's
     * length. With [keep] false a picture leaves the decoded pictures' memory once another has faded in over it (a
     * banner's, 2 MB each: the rotation decodes the next one ahead anyway). [opaque]: nothing is baked clear in its
     * pictures, so the one before stays whole under the next while that fades in (the banner never dims between two).
     */
    class ArtSpec(
        val decodeW: Int,
        val decodeH: Int,
        val bake: (Bitmap) -> Bitmap,
        val keySuffix: String,
        val cropY: Float,
        val fadeMs: Long,
        val half: Boolean = true,
        val keep: Boolean = true,
        val opaque: Boolean = false,
    ) {
        companion object {
            /** A page banner's picture: sharp (decoded at the size it's shown) and whole. */
            val BANNER = ArtSpec(
                0, 0, { it }, "|banner", VodSizes.ART_CROP_Y, VodSizes.HERO_FADE_MS, half = false, keep = false, opaque = true,
            )

            /** Step 1's page headers: clear at the left edge to solid over 45% (eased), solid to clear over the bottom 35%. */
            val HEADER = ArtSpec(
                0, 0,
                fade(0f, VodSizes.HEADER_FADE_LEFT_TO, VodSizes.HEADER_FADE_BOTTOM_FROM, 1f),
                "|fade", VodSizes.ART_CROP_Y, VodSizes.HERO_FADE_MS,
            )

            /** The title page's backdrop (design-brief.md §6.4): half the screen's pixels, the title page's fade. */
            val TITLE = ArtSpec(
                VodSizes.TITLE_ART_W, VodSizes.TITLE_ART_H,
                fade(VodSizes.TITLE_FADE_LEFT_FROM, VodSizes.TITLE_FADE_LEFT_TO, VodSizes.TITLE_FADE_BOTTOM_FROM, VodSizes.TITLE_FADE_BOTTOM_TO),
                "|title", VodSizes.ART_CROP_Y, VodSizes.TITLE_ART_FADE_MS,
            )

            /** The title page's small copy, shown until its backdrop comes: the same fade. */
            val TITLE_PREVIEW = ArtSpec(
                VodSizes.TITLE_PREVIEW_W, VodSizes.TITLE_PREVIEW_H, TITLE.bake, "|title", VodSizes.ART_CROP_Y, 0L,
            )

            /**
             * The fade baked into a backdrop (image thread): clear at [leftFrom] of its width to solid at [leftTo]
             * (eased: [VodSizes.FADE_ALPHAS] at [VodSizes.FADE_STOPS]), and solid at [bottomFrom] of its height to clear
             * at [bottomTo].
             */
            fun fade(leftFrom: Float, leftTo: Float, bottomFrom: Float, bottomTo: Float): (Bitmap) -> Bitmap = { src ->
                val out = if (src.isMutable && src.config == Bitmap.Config.ARGB_8888) src else src.copy(Bitmap.Config.ARGB_8888, true).also { src.recycle() }
                val w = out.width.toFloat()
                val h = out.height.toFloat()
                val c = Canvas(out)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
                p.shader = LinearGradient(
                    w * leftFrom, 0f, w * leftTo, 0f,
                    IntArray(VodSizes.FADE_ALPHAS.size) { VodSizes.FADE_ALPHAS[it] shl 24 },
                    VodSizes.FADE_STOPS,
                    Shader.TileMode.CLAMP,
                )
                c.drawRect(0f, 0f, w, h, p)
                p.shader = LinearGradient(0f, h * bottomFrom, 0f, h * bottomTo, 0xFF000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, p)
                out
            }
        }
    }

    override var imageKey: String? = null

    /** The card's corner radius, so the picture follows the card's rounded right edge. */
    var radius = 0f
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }

    /**
     * Where the picture is drawn, in this view's coordinates; null: the view itself. The title page draws its
     * backdrop over the whole screen, so the part under the rail's strip lies left of the view.
     */
    var frame: RectF? = null
        set(v) {
            field = v
            invalidate()
        }

    /**
     * The share of the picture's width, from its left edge, over which it's covered by the card's own dark colour
     * (solid at the edge, easing to nothing): a banner's picture box that starts inside its card has no edge there.
     * Drawn over the picture, which stays whole; 0 for none.
     */
    var leftFade = 0f
        set(v) {
            if (field == v) return
            field = v
            makeCover()
            invalidate()
        }
    private val coverPaint = Paint()

    private var shown: Bitmap? = null
    private var shownShader: BitmapShader? = null
    /** The picture shown is a stand-in of another shape (drawn to cover, not stretched). */
    private var shownCover = false
    private var before: Bitmap? = null
    private var beforeShader: BitmapShader? = null
    private var beforeCover = false
    /** The memory keys of the picture shown and the one it's fading in over. */
    private var shownKey: String? = null
    private var beforeKey: String? = null
    private var fade = 1f
    private var fader: ValueAnimator? = null
    private val box = RectF()
    private val m = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var wantedUrl: String? = null

    /** The small copy's own target: what it brings shows only until the real picture is there. */
    private val previewTarget = object : ImageTarget {
        override var imageKey: String? = null
        override fun setImage(bitmap: Bitmap?, fromMemory: Boolean) {
            if (bitmap != null && shown == null) hold(bitmap, cover = false)
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** The decode size: the spec's, or the shown size (half of it for a spec that says so). */
    private val decodeW: Int get() = if (spec.decodeW > 0) spec.decodeW else (if (spec.half) width / 2 else width).coerceAtLeast(1)
    private val decodeH: Int get() = if (spec.decodeH > 0) spec.decodeH else (if (spec.half) height / 2 else height).coerceAtLeast(1)

    /** The picture asked for last ([show]), shown or on its way. */
    val wanted: String? get() = wantedUrl

    /** The memory key [url]'s picture has here (to find it decoded already, or drop it). */
    fun keyFor(url: String): String = ImageLoader.keyOf(url, decodeW, decodeH, spec.keySuffix)

    /** Shows [url]'s picture (crossfading from what shows now), or nothing for null. */
    fun show(url: String?) {
        wantedUrl = url
        if (width <= 0 || height <= 0) return
        if (url == null) {
            ImageLoader.release(this)
            return
        }
        ImageLoader.load(url, this, decodeW, decodeH, Bitmap.Config.ARGB_8888, spec.bake, spec.keySuffix, spec.cropY)
    }

    /** Decodes [url]'s picture ahead (the next title), so its switch never waits. */
    fun warm(url: String?) {
        if (width <= 0 || height <= 0) return
        ImageLoader.warm(url, decodeW, decodeH, Bitmap.Config.ARGB_8888, spec.bake, spec.keySuffix, spec.cropY)
    }

    /**
     * Shows [bitmap] at once, until the real picture comes and crossfades over it: a smaller copy already in memory.
     * [cover]: it has another shape (a page header's crop), so it fills the frame, cut to fit, rather than stretched.
     */
    fun hold(bitmap: Bitmap, cover: Boolean) {
        if (shown != null) return
        shown = bitmap
        shownShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shownCover = cover
        fade = 1f
        invalidate()
    }

    /** A small copy of the picture ([ArtSpec.titlePreview]) from disk only, shown until the real one comes. */
    fun preview(url: String?, preview: ArtSpec) {
        if (url == null || shown != null) return
        ImageLoader.load(
            url, previewTarget, preview.decodeW, preview.decodeH, Bitmap.Config.ARGB_8888, preview.bake,
            preview.keySuffix, preview.cropY, diskOnly = true,
        )
    }

    /** Lets both pictures go (the page is hidden); with [cancel] (a title page leaving) their queued downloads go too. */
    fun release(cancel: Boolean = false) {
        fader?.cancel()
        if (cancel) {
            ImageLoader.cancel(this)
            ImageLoader.cancel(previewTarget)
        } else {
            ImageLoader.release(this)
            ImageLoader.release(previewTarget)
        }
        shown = null; shownShader = null
        before = null; beforeShader = null
        shownKey = null; beforeKey = null
        invalidate()
    }

    private fun makeCover() {
        val w = (frame ?: box).width() * leftFade
        if (w <= 0f) {
            coverPaint.shader = null
            return
        }
        val rgb = GlassStyle.WINDOW_SOLID and 0x00FFFFFF
        val n = VodSizes.FADE_ALPHAS.size
        val left = (frame ?: box).left
        // The baked fades' easing, the other way round: solid at the edge, clear at the end.
        coverPaint.shader = LinearGradient(
            left, 0f, left + w, 0f,
            IntArray(n) { ((0xFF - VodSizes.FADE_ALPHAS[it]) shl 24) or rgb }, VodSizes.FADE_STOPS, Shader.TileMode.CLAMP,
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        box.set(0f, 0f, w.toFloat(), h.toFloat())
        makeCover()
        if (w > 0 && h > 0 && wantedUrl != null && (imageKey == null || (spec.decodeW <= 0 && oldw > 0 && (w != oldw || h != oldh)))) show(wantedUrl)
    }

    override fun setImage(bitmap: Bitmap?, fromMemory: Boolean) {
        // Cleared while the next one is fetched: the old picture stays until the new one crossfades over it.
        if (bitmap == null) {
            if (imageKey == null) {
                fader?.cancel()
                shown = null; shownShader = null
                before = null; beforeShader = null
                shownKey = null; beforeKey = null
                invalidate()
            }
            return
        }
        if (bitmap === shown) return
        // The small copy has done its part.
        ImageLoader.release(previewTarget)
        fader?.cancel()
        // A fade cut short: the picture it was fading from is replaced already.
        forget(beforeKey)
        before = shown
        beforeShader = shownShader
        beforeCover = shownCover
        beforeKey = shownKey
        shown = bitmap
        shownKey = imageKey
        shownShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shownCover = false
        if (before == null) {
            beforeKey = null
            fade = 1f
            invalidate()
            return
        }
        fade = 0f
        fader = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = spec.fadeMs
            addUpdateListener {
                fade = it.animatedValue as Float
                if (fade >= 1f) {
                    before = null
                    beforeShader = null
                    forget(beforeKey)
                    beforeKey = null
                }
                invalidate()
            }
            start()
        }
    }

    /** A picture replaced leaves the decoded pictures' memory ([ArtSpec.keep] false), unless it's the one shown again. */
    private fun forget(key: String?) {
        if (spec.keep || key == null || key == shownKey || key == imageKey) return
        ImageLoader.drop(key)
    }

    override fun onDraw(canvas: Canvas) {
        val b = before
        // Opaque pictures: the one before stays whole under the next (an exact crossfade); others fade out as it comes.
        if (b != null && fade < 1f) draw(canvas, b, beforeShader, if (spec.opaque) 1f else 1f - fade, beforeCover)
        shown?.let { draw(canvas, it, shownShader, fade, shownCover) }
        // With or without a picture: the card's dark side (the banner's backing) always eases out the same way.
        if (leftFade > 0f && coverPaint.shader != null) {
            val f = frame ?: box
            canvas.drawRect(f.left, f.top, f.left + f.width() * leftFade, f.bottom, coverPaint)
        }
    }

    private fun draw(canvas: Canvas, bmp: Bitmap, shader: BitmapShader?, alpha: Float, cover: Boolean) {
        shader ?: return
        val f = frame ?: box
        val sx = f.width() / bmp.width.toFloat()
        val sy = f.height() / bmp.height.toFloat()
        if (cover) {
            // Cut to fill the frame, its crop placed as the real picture's is.
            val s = maxOf(sx, sy)
            m.setScale(s, s)
            m.postTranslate(f.left + (f.width() - bmp.width * s) / 2f, f.top + (f.height() - bmp.height * s) * spec.cropY)
        } else {
            m.setScale(sx, sy)
            m.postTranslate(f.left, f.top)
        }
        shader.setLocalMatrix(m)
        paint.shader = shader
        paint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(f, radius, radius, paint)
    }
}
