package app.fireplayer.vod.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import app.fireplayer.shell.TextStyle
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.ImageFit
import app.fireplayer.ui.ImageLoader
import app.fireplayer.ui.ImageTarget
import app.fireplayer.vod.Title
import app.fireplayer.vod.data.VodArt

/**
 * The banner's title box: the title's logo, the treatment the title page gives it ([TitleHeader.TitleLogo]: its
 * visible part, standing at the box's left and bottom), or its name in text where it has none ([NameView]). Which one
 * is [HeaderTitleMode]'s rule: never the name first and the logo over it. A fixed box, so a new title only redraws it.
 *
 * A logo is decoded on the image thread, cut to its visible part and kept no larger than the box ([HeaderLogo.trim]),
 * so one in memory is small (under 200 KB) and the box holds one at a time; the decoded pictures' memory, which is
 * bounded and shared, keeps the others while it has room, so a feed going round doesn't decode them again. The next
 * title's is decoded ahead ([warm]) with its picture, so a turn never waits for it. [release] lets them go.
 */
@SuppressLint("ViewConstructor")
internal class HeaderTitle(context: Context, t: Tokens, cardW: Float) : FrameLayout(context) {

    private val main = Handler(Looper.getMainLooper())
    private val logo = TitleHeader.TitleLogo(context)
    private val name = NameView(context, t)
    private val logoW = t.dp(VodSizes.headerLogoW(cardW))
    private val logoH = t.dp(VodSizes.HEADER_TITLE_H)
    private val decodeW = logoW * VodSizes.HEADER_LOGO_DECODE
    private val decodeH = logoH * VodSizes.HEADER_LOGO_DECODE
    private val trim = HeaderLogo.trim(logoW, logoH)

    /** The next title's logo, decoded ahead into memory: nothing is shown from here. */
    private val ahead = object : ImageTarget {
        override var imageKey: String? = null
        override fun setImage(bitmap: Bitmap?, fromMemory: Boolean) = Unit
    }

    private var mode = HeaderTitleMode.NONE
    private var url: String? = null
    private var words = ""
    /** Inside [show]'s own request: a logo that was in memory is dealt with there, not as one arriving. */
    private var asking = false

    init {
        addView(logo, LayoutParams(logoW, LayoutParams.MATCH_PARENT, Gravity.BOTTOM or Gravity.START))
        addView(name, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        logo.visibility = INVISIBLE
        name.visibility = INVISIBLE
        logo.onArrived = { if (!asking) onArrived() }
    }

    /** Shows [title] (null: nothing). The same title again leaves the box as it is, a name it settled on included. */
    fun show(title: Title?) {
        val text = title?.name.orEmpty()
        val u = HeaderLogo.urlOf(title)
        if (title != null && mode != HeaderTitleMode.NONE && text == words && u == url) return
        main.removeCallbacks(giveUp)
        logo.animate().cancel()
        logo.alpha = 1f
        words = text
        // The logo of the title before: one still queued for nobody else isn't fetched (a held D-pad passes many).
        if (u != url) ImageLoader.cancel(logo)
        url = u
        if (title == null) {
            apply(HeaderTitleMode.NONE)
            return
        }
        var ready = false
        var failed = false
        if (u != null) {
            asking = true
            request(u, logo)
            asking = false
            ready = logo.bitmap != null
            // An address that gave no picture lately isn't asked again: the loader hands the request straight back.
            failed = logo.imageKey == null
        }
        apply(HeaderTitleMode.of(u != null, ready, failed))
        if (mode == HeaderTitleMode.WAITING) main.postDelayed(giveUp, VodSizes.HEADER_LOGO_WAIT_MS)
    }

    /** Decodes [title]'s logo ahead (the title the banner turns to next). */
    fun warm(title: Title?) {
        val u = HeaderLogo.urlOf(title) ?: return
        request(u, ahead)
    }

    /** Lets the logos go (the page is hidden or stopped); [show] brings the title's back. */
    fun release() {
        main.removeCallbacks(giveUp)
        logo.animate().cancel()
        logo.alpha = 1f
        ImageLoader.cancel(logo)
        ImageLoader.cancel(ahead)
        url = null
        words = ""
        apply(HeaderTitleMode.NONE)
    }

    private fun request(u: String, target: ImageTarget) {
        ImageLoader.load(u, target, decodeW, decodeH, Bitmap.Config.ARGB_8888, trim, KEY, fit = ImageLoader.Fit.INSIDE)
    }

    private fun onArrived() {
        if (mode != HeaderTitleMode.WAITING) return
        main.removeCallbacks(giveUp)
        apply(mode.arrived())
        // Into the empty box, with the words' own fade.
        logo.alpha = 0f
        logo.animate().alpha(1f).setDuration(VodSizes.HEADER_TEXT_FADE_MS).start()
    }

    private val giveUp = Runnable {
        if (mode != HeaderTitleMode.WAITING) return@Runnable
        // Too late for this showing: it isn't decoded for it (its bytes still reach the disk, for the next).
        ImageLoader.release(logo)
        apply(mode.waited())
    }

    private fun apply(m: HeaderTitleMode) {
        mode = m
        if (m == HeaderTitleMode.NAME) name.set(words)
        logo.visibility = if (m == HeaderTitleMode.LOGO) VISIBLE else INVISIBLE
        name.visibility = if (m == HeaderTitleMode.NAME) VISIBLE else INVISIBLE
        contentDescription = words.takeIf { it.isNotEmpty() }
    }

    /**
     * The name where a title has no logo, drawn here so another name (of another size) is only redrawn: on one line
     * at [VodSizes.HEADER_TITLE_SP], or, too wide for the box at that size, on up to [VodSizes.HEADER_TITLE_LINES] lines
     * of the smaller size ([HeaderName.lines]). It stands on the box's bottom, as a logo does.
     */
    private class NameView(context: Context, t: Tokens) : View(context) {
        private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = t.faceOf(VodSizes.HEADER_TITLE.weight)
            color = t.colorOf(VodSizes.HEADER_TITLE)
            setShadowLayer(t.dpf(VodSizes.HEADER_TEXT_SHADOW), 0f, t.dpf(VodSizes.HEADER_TEXT_SHADOW_DY), VodSizes.HEADER_TEXT_SHADOW_COLOR)
        }
        private val scaled = resources.displayMetrics.scaledDensity
        private var name = ""
        private var lines: List<String> = emptyList()
        private var fittedFor = -1

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun set(text: String) {
            if (text == name) return
            name = text
            fittedFor = -1
            invalidate()
        }

        private fun fit() {
            fittedFor = width
            val room = width.toFloat().coerceAtLeast(1f)
            paint.textSize = VodSizes.HEADER_TITLE_SP * scaled
            val sp = VodSizes.headerTitleSp(paint.measureText(name), room)
            paint.textSize = sp * scaled
            val most = if (sp == VodSizes.HEADER_TITLE_SP) 1 else VodSizes.HEADER_TITLE_LINES
            lines = HeaderName.lines(name, room, most) { paint.measureText(it) }
        }

        override fun onDraw(canvas: Canvas) {
            if (name.isEmpty() || width <= 0) return
            if (fittedFor != width) fit()
            val line = paint.textSize * TextStyle.LINE
            var y = height - paint.descent() - (lines.size - 1) * line
            for (i in lines.indices) {
                canvas.drawText(lines[i], 0f, y, paint)
                y += line
            }
        }
    }

    private companion object {
        /** The memory key's suffix for a logo cut to its visible part. */
        const val KEY = "|banner"
    }
}

/** A banner's logo as it's kept in memory. */
internal object HeaderLogo {
    /**
     * The address of [title]'s logo: its own, else metahub's for its IMDb id. A card from the library (Continue
     * Watching, My List) carries no logo address, and without this its banner would always show the name in text.
     * A title metahub has no logo for answers with nothing, and the name shows as before.
     */
    fun urlOf(title: Title?): String? =
        title?.logo?.takeIf { it.isNotEmpty() } ?: title?.id?.takeIf { it.startsWith("tt") }?.let { VodArt.logo(it) }

    /**
     * Cuts a decoded logo to its visible part ([LogoBounds]) and fits that inside [maxW] x [maxH] px, never enlarged
     * (image thread, once per logo): the picture kept is no larger than the box it's shown in.
     */
    fun trim(maxW: Int, maxH: Int): (Bitmap) -> Bitmap = { src ->
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val b = LogoBounds.of(px, w, h)
        val (ow, oh) = ImageFit.inside(b[2] - b[0], b[3] - b[1], maxW, maxH)
        if (ow == w && oh == h) {
            src
        } else {
            val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(src, Rect(b[0], b[1], b[2], b[3]), Rect(0, 0, ow, oh), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            src.recycle()
            out
        }
    }
}

/** How a banner's name is set in its box where the title has no logo (pure, unit-tested). */
internal object HeaderName {
    const val ELLIPSIS = "…"

    /**
     * [name] on at most [maxLines] lines, none wider than [room] by [measure]: broken between words, and what's left
     * over at the last line cut short there with an ellipsis.
     */
    fun lines(name: String, room: Float, maxLines: Int, measure: (String) -> Float): List<String> {
        val words = name.split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<String>(maxLines)
        var i = 0
        while (i < words.size && out.size < maxLines) {
            if (out.size == maxLines - 1) {
                // The last line takes all that's left.
                out += cut(words.subList(i, words.size).joinToString(" "), room, measure)
                break
            }
            var line = words[i++]
            while (i < words.size && measure(line + " " + words[i]) <= room) line += " " + words[i++]
            // One word wider than the box by itself.
            out += cut(line, room, measure)
        }
        return out
    }

    /** [text], or as much of it as fits [room] with an ellipsis after it. */
    fun cut(text: String, room: Float, measure: (String) -> Float): String {
        if (measure(text) <= room) return text
        var n = text.length - 1
        while (n > 0 && measure(text.substring(0, n).trimEnd() + ELLIPSIS) > room) n--
        return text.substring(0, n).trimEnd() + ELLIPSIS
    }
}
