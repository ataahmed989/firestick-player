package app.fireplayer.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import app.fireplayer.R
import app.fireplayer.player.ShowGuide

/**
 * The show's other episodes, Netflix-style: a sheet over the lower part of the screen (the film plays on, dimmed, behind
 * it) with the seasons across the top and one row of episode cards. Only episodes the provider has are listed, so each
 * card plays the right file. Up and down move between the seasons and the cards; Back closes it.
 */
class EpisodesSheet(private val activity: Activity, private val host: ViewGroup) {

    /** Called with the episode chosen. */
    var onPlay: (ShowGuide.Ep) -> Unit = {}

    /** Called after the sheet is put away. */
    var onClosed: () -> Unit = {}

    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private fun color(res: Int) = ContextCompat.getColor(activity, res)
    private val font500: Typeface? = ResourcesCompat.getFont(activity, R.font.figtree_500)
    private val font600: Typeface? = ResourcesCompat.getFont(activity, R.font.figtree_600)
    private val font700: Typeface? = ResourcesCompat.getFont(activity, R.font.figtree_700)
    private val accent = color(R.color.accent)

    private val root = FrameLayout(activity).apply {
        setBackgroundColor(SHADE)
        isClickable = true
        visibility = View.GONE
    }
    private val sheet = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, SHEET_SHADE)
        clipChildren = false
        clipToPadding = false
        setPadding(dp(42), dp(34), dp(42), dp(14))
    }
    private val title = text(22f, font700, color(R.color.text)).apply { text = "Episodes" }
    // Where the episodes come from, as a pod like the player's info pods.
    private val source = text(10f, font700, color(R.color.text)).apply {
        setPadding(dp(6), dp(2), dp(6), dp(2))
    }
    private val count = text(10f, font700, color(R.color.text)).apply {
        setPadding(dp(6), dp(2), dp(6), dp(2))
    }
    private val seasonsRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        setPadding(dp(2), dp(4), dp(2), dp(4))
    }
    private val seasonsScroll = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
        addView(seasonsRow)
    }
    private val status = text(13f, font500, color(R.color.text_muted))
    private val cardsRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        // Room below for the chosen card, which grows.
        setPadding(dp(4), dp(8), dp(4), dp(20))
    }
    // Only lays the row out wider than the screen: it never scrolls itself. The row is moved by [motion] alone, the
    // chosen card kept at the left edge like Glass Home's rows.
    private val cardsScroll = object : HorizontalScrollView(activity) {
        override fun computeScrollDeltaToGetChildRectOnScreen(rect: android.graphics.Rect?): Int = 0
    }.apply {
        isHorizontalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
        isFillViewport = false
        isSmoothScrollingEnabled = false
        addView(cardsRow)
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyMotion() }
    }

    /*
     * The row's motion, from two springs: where it is, as a card number (between two while it moves), and how far the
     * chosen card is grown (0 while the season pills are chosen). Every card's size and place and the row's offset
     * follow from those two each frame, so the row slides, the new card grows and the old one shrinks as one fluid
     * movement that carries on smoothly from press to press.
     */
    private val at = Spring(omega = 14f, settle = 0.001f) { applyMotion() }
    private val grown = Spring(omega = 16f, settle = 0.001f) { applyMotion() }

    private var guide: ShowGuide? = null
    private var playingId: String? = null
    private var season = 0
    private var progressOf: (ShowGuide.Ep) -> Float = { 0f }
    private val pills = ArrayList<TextView>()

    val isOpen: Boolean get() = root.visibility == View.VISIBLE

    init {
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        sheet.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sheet.addView(seasonsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        sheet.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        // Where the episodes come from and how many, as pods just over the covers, like the info pods over the time bar.
        val pods = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, 0, 0)
            addView(source, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(5) })
        }
        sheet.addView(pods, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        sheet.addView(cardsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 0 })
        root.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        host.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // The pods' film follows the shade where they sit, which is only known once they're laid out.
        sheet.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> dimGlass() }
    }

    /**
     * Glass look: the film in each glass pod is darkened as much as the shade around it (the sheet's dark wash and
     * its gradient at that height), so the pods sit in the dimmed picture instead of glowing out of it. This is
     * the sheet's own shade, whatever the Screen dimmer is set to; the dimmer's glass amount still applies on top.
     */
    private fun dimGlass() {
        if (!Glass.on) return
        (listOf(source, count) + pills).forEach { dimGlass(it) }
    }

    private fun dimGlass(view: View) {
        val glass = view.background as? GlassDrawable ?: return
        val h = sheet.height
        if (h <= 0) return
        // The view's middle, down the sheet (layout positions: the sheet slides in with translationY).
        var y = view.top + view.height / 2f
        var p = view.parent as? View
        while (p != null && p !== sheet) {
            y += p.top - p.scrollY
            p = p.parent as? View
        }
        val t = (y / h).coerceIn(0f, 1f)
        // The gradient's darkness at t: 0 at the top, the middle stop at half, the last at the bottom.
        val a0 = Color.alpha(SHEET_SHADE[0]) / 255f
        val a1 = Color.alpha(SHEET_SHADE[1]) / 255f
        val a2 = Color.alpha(SHEET_SHADE[2]) / 255f
        val g = if (t < 0.5f) a0 + (a1 - a0) * (t / 0.5f) else a1 + (a2 - a1) * ((t - 0.5f) / 0.5f)
        val light = (1f - Color.alpha(SHADE) / 255f) * (1f - g)
        glass.backdropDim = 1f - light
    }

    /** Puts the sheet up, saying [waiting] until the episodes are known. */
    fun open(waiting: String) {
        guide = null
        pills.clear()
        seasonsRow.removeAllViews()
        cardsRow.removeAllViews()
        source.text = ""
        source.visibility = View.GONE
        count.visibility = View.GONE
        status.text = waiting
        status.visibility = View.VISIBLE
        root.visibility = View.VISIBLE
        root.alpha = 0f
        sheet.translationY = dp(30).toFloat()
        // Drawn into a layer while they move: one copy slides instead of every card being drawn again each frame.
        root.animate().alpha(1f).setDuration(180).withLayer().start()
        sheet.animate().translationY(0f).setDuration(240).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).withLayer().start()
        // Holds focus until the cards come.
        root.isFocusable = true
        root.requestFocus()
    }

    /** Something went wrong: says so in place of the cards. */
    fun fail(text: String) {
        if (!isOpen) return
        status.text = text
        status.visibility = View.VISIBLE
    }

    /**
     * Shows the show's episodes, on the season playing, with the one [playing] chosen. [progress] says how far
     * each was watched (0 to 1).
     */
    fun show(guide: ShowGuide, playing: String?, progress: (ShowGuide.Ep) -> Float) {
        if (!isOpen) return
        val firstTime = this.guide == null
        this.guide = guide
        playingId = playing
        progressOf = progress
        val episodes = guide.episodes.size
        // Two pods, like the player's info pods: where they come from, and how many there are.
        for (pod in listOf(source, count)) {
            Glass.background(pod, Glass.Kind.CHIP)
            pod.visibility = View.VISIBLE
        }
        dimGlass()
        source.text = "From ${guide.portal}"
        count.text = "$episodes episode" + if (episodes == 1) "" else "s"
        status.visibility = View.GONE
        val now = guide.episode(playing)
        if (firstTime || guide.seasons.none { it == season }) season = now?.season ?: guide.seasons.firstOrNull() ?: 0
        // Shown again with a newer guide: the card chosen stays chosen, where it was.
        val chosenCard = cardsRow.findFocus()?.let { f -> (0 until cardsRow.childCount).firstOrNull { cardsRow.getChildAt(it) === f || cardsRow.getChildAt(it).findFocus() === f } }
        buildPills()
        buildCards()
        if (firstTime) {
            val list = guide.inSeason(season)
            val playingAt = list.indexOfFirst { it.id == playing }
            // The episode playing (Ahmed's choice over Netflix's "the one after").
            focusCard(if (playingAt < 0) 0 else playingAt, jump = true)
            root.isFocusable = false
        } else if (chosenCard != null) {
            focusCard(chosenCard, jump = true)
        }
    }

    fun close() {
        if (!isOpen) return
        root.animate().cancel()
        root.visibility = View.GONE
        root.removeCallbacks(buildLater)
        buildPending = false
        at.stop()
        grown.stop()
        cardsRow.removeAllViews()
        seasonsRow.removeAllViews()
        pills.clear()
        guide = null
        onClosed()
    }

    /** Keeps the D-pad inside the sheet: true when focus is somewhere in it. */
    fun hasFocus(): Boolean = root.hasFocus()

    fun focusSomething() {
        val card = (0 until cardsRow.childCount).map { cardsRow.getChildAt(it) }.firstOrNull()
        (card ?: pills.firstOrNull() ?: root).requestFocus()
    }

    private fun buildPills() {
        val guide = guide ?: return
        pills.clear()
        seasonsRow.removeAllViews()
        val seasons = guide.seasons
        // One season only: nothing to choose.
        seasonsScroll.visibility = if (seasons.size > 1) View.VISIBLE else View.GONE
        seasons.forEach { s ->
            val pill = text(13f, font600, color(R.color.text)).apply {
                text = if (s == 0) "Specials" else "Season $s"
                isFocusable = true
                isClickable = true
                gravity = Gravity.CENTER
                setPadding(dp(13), dp(5), dp(13), dp(5))
                tag = s
            }
            stylePill(pill, focused = false)
            pill.setOnFocusChangeListener { v, has ->
                stylePill(v as TextView, has)
                if (has && season != s) {
                    season = s
                    pills.forEach { stylePill(it, it.isFocused) }
                    buildCardsSoon()
                }
            }
            pill.setOnClickListener { focusCard(0) }
            pill.setOnKeyListener { v, keyCode, event ->
                val down = event.action == KeyEvent.ACTION_DOWN
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (down) focusCard(playingIndex().coerceAtLeast(0))
                        true
                    }
                    // Explicit, so the remote always steps season to season.
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (down) {
                            val at = pills.indexOf(v) + if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                            pills.getOrNull(at)?.let { next ->
                                next.requestFocus()
                                seasonsScroll.smoothScrollTo((next.left - dp(40)).coerceAtLeast(0), 0)
                            }
                        }
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> true
                    else -> false
                }
            }
            pills += pill
            seasonsRow.addView(pill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        }
    }

    private fun stylePill(pill: TextView, focused: Boolean) {
        val chosen = pill.tag == season
        val fill = when {
            focused -> color(R.color.focus_fill)
            chosen -> 0x663D8BFF
            else -> 0x12FFFFFF
        }
        val rim = when {
            focused -> accent
            chosen -> ACCENT_RIM
            else -> 0x1FFFFFFF
        }
        // Glass look: a season neither selected nor the one shown is a clear glass pod, like the other buttons.
        if (Glass.on && !focused && !chosen) {
            pill.background = Glass.drawable(activity, Glass.Kind.PILL)
            pill.setTextColor(color(R.color.text))
            dimGlass(pill)
            return
        }
        pill.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(fill)
            // The season shown has a stronger ring, so it stands out from the others.
            setStroke(if (chosen && !focused) (1.5f * density).toInt().coerceAtLeast(2) else dp(1).coerceAtLeast(1), rim)
        }
        pill.setTextColor(if (focused) color(R.color.text_dark) else if (chosen) color(R.color.text) else color(R.color.text_muted))
    }

    private fun playingIndex(): Int = guide?.inSeason(season)?.indexOfFirst { it.id == playingId } ?: -1

    /** Chooses card [index]: straight there with [jump] (opening the sheet), else gliding from where the row is. */
    private fun focusCard(index: Int, jump: Boolean = false) {
        flushCards()
        val n = cardsRow.childCount
        if (n == 0) return
        val i = index.coerceIn(0, n - 1)
        if (jump) {
            // The one playing at the left, grown, never out of sight; placed again once the cards are laid out.
            at.snap(i.toFloat())
            grown.snap(1f)
            afterLayout { applyMotion() }
        }
        cardsRow.getChildAt(i).requestFocus()
    }

    /** Sizes and places every card, and the row, for where the springs are now. */
    private fun applyMotion() {
        val n = cardsRow.childCount
        if (n == 0) return
        val f = at.value.coerceIn(0f, (n - 1).toFloat())
        val g = grown.value.coerceIn(0f, 1f)
        val i0 = kotlin.math.floor(f).toInt()
        val frac = f - i0
        val l0 = cardsRow.getChildAt(i0).left
        val l1 = cardsRow.getChildAt(minOf(i0 + 1, n - 1)).left
        // Float offsets, not scrollTo's whole pixels: the slide is drawn between pixels too.
        cardsRow.translationX = -(l0 + (l1 - l0) * frac - cardsRow.paddingLeft)
        val extra = dp(CARD_W) * GROW
        for (i in 0 until n) {
            val card = cardsRow.getChildAt(i)
            val share = (1f - kotlin.math.abs(i - f)).coerceIn(0f, 1f) * g
            val s = 1f + GROW * share
            card.scaleX = s
            card.scaleY = s
            // Cards after the grown one make room for it.
            card.translationX = extra * g * (i - f).coerceIn(0f, 1f)
            (card.tag as? View)?.alpha = share
        }
    }

    // Moving across the season pills: the cards are made once it rests on one, not for each pill passed.
    private val buildLater = Runnable { buildCards() }
    private var buildPending = false

    private fun buildCardsSoon() {
        buildPending = true
        root.removeCallbacks(buildLater)
        root.postDelayed(buildLater, SEASON_SETTLE_MS)
    }

    private fun flushCards() {
        if (!buildPending) return
        root.removeCallbacks(buildLater)
        buildCards()
    }

    /** Runs [action] once the row is laid out again, just before it's drawn. */
    private fun afterLayout(action: () -> Unit) {
        val observer = cardsScroll.viewTreeObserver
        observer.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (cardsScroll.viewTreeObserver.isAlive) cardsScroll.viewTreeObserver.removeOnPreDrawListener(this)
                action()
                return true
            }
        })
    }

    private fun buildCards() {
        buildPending = false
        val guide = guide ?: return
        cardsRow.removeAllViews()
        cardsScroll.scrollTo(0, 0)
        at.snap(0f)
        grown.snap(0f)
        guide.inSeason(season).forEach { ep -> cardsRow.addView(card(ep)) }
        afterLayout { applyMotion() }
    }

    private fun card(ep: ShowGuide.Ep): View {
        val width = dp(CARD_W)
        val thumbH = width * 9 / 16
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(15) }
        }
        val radius = dp(10).toFloat()
        val thumb = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = radius
                colors = intArrayOf(0xFF26313F.toInt(), 0xFF141A22.toInt())
                orientation = GradientDrawable.Orientation.TL_BR
            }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
            clipToOutline = true
        }
        val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        thumb.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ImageLoader.load(ep.image, image, width)
        // How far it was watched, along the bottom.
        val progress = progressOf(ep).coerceIn(0f, 1f)
        if (progress > 0.01f) {
            val bar = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(0x40FFFFFF)
                addView(View(activity).apply { setBackgroundColor(accent) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, progress))
                addView(View(activity), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f - progress))
            }
            thumb.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.BOTTOM))
        }
        val badge = when {
            ep.id == playingId -> badge("Playing", Color.WHITE, accent)
            ep.match == ShowGuide.Match.UNSURE -> badge("Unsure", 0xFFC3C8CF.toInt(), 0xBF282C34.toInt())
            else -> null
        }
        badge?.let {
            thumb.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(6)
                marginEnd = dp(6)
            })
        }
        // The blue ring of the chosen card, drawn over the picture.
        val ring = View(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = radius
                setStroke(dp(3), accent)
            }
            visibility = View.INVISIBLE
        }
        thumb.addView(ring, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        card.addView(thumb, LinearLayout.LayoutParams(width, thumbH))

        val name = text(13.5f, font600, color(R.color.text)).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            val number = "E${ep.number}"
            text = SpannableStringBuilder(number).apply {
                setSpan(ForegroundColorSpan(0xFF8FB8FF.toInt()), 0, number.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (ep.name.isNotEmpty()) append("  ").append(ep.name)
            }
        }
        card.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(9) })
        val meta = listOfNotNull(ep.runtimeMin.takeIf { it > 0 }?.let { "${it}m" }, airDate(ep.airDate)).joinToString(" · ")
        if (meta.isNotEmpty()) {
            card.addView(text(10.5f, font500, 0xFF9BA1A8.toInt()).apply { text = meta; isSingleLine = true },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        }
        if (ep.overview.isNotEmpty()) {
            card.addView(text(10.5f, font500, color(R.color.text_muted)).apply {
                text = ep.overview
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(dp(1).toFloat(), 1f)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }

        // Grows from its left edge and the middle of its picture; the ring fades in as it grows (see applyMotion).
        card.pivotX = 0f
        card.pivotY = thumbH / 2f
        ring.visibility = View.VISIBLE
        ring.alpha = 0f
        card.tag = ring
        card.setOnFocusChangeListener { v, has ->
            if (has) {
                at.glideTo(cardsRow.indexOfChild(v).toFloat())
                grown.glideTo(1f)
            } else {
                // Gone up to the seasons: the card shrinks back; to the next card, that one takes over at once.
                v.post { if (isOpen && cardsRow.findFocus() == null) grown.glideTo(0f) }
            }
        }
        card.setOnClickListener { onPlay(ep) }
        card.setOnKeyListener { v, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                // One card at a time, also while held; the row glides behind the chosen one.
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val at = cardsRow.indexOfChild(v) + if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                    cardsRow.getChildAt(at)?.requestFocus()
                }
                true
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (event.action == KeyEvent.ACTION_DOWN) pills.firstOrNull { it.tag == season }?.requestFocus()
                true
            } else keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        }
        return card
    }

    private fun badge(label: String, textColor: Int, fill: Int) = text(10f, font700, textColor).apply {
        text = label
        setPadding(dp(7), dp(2), dp(7), dp(2))
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(fill)
            if (fill != accent) setStroke(dp(1).coerceAtLeast(1), 0xFF8A9099.toInt())
        }
    }

    private fun text(sp: Float, font: Typeface?, color: Int) = TextView(activity).apply {
        typeface = font
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        includeFontPadding = false
    }

    /** "2009-03-29" → "Mar 29, 2009"; anything else as it came. */
    private fun airDate(date: String): String? {
        if (date.isBlank()) return null
        val parts = date.take(10).split('-')
        val month = parts.getOrNull(1)?.toIntOrNull()
        if (parts.size != 3 || month == null || month !in 1..12) return date
        return "${MONTHS[month - 1]} ${parts[2].toIntOrNull() ?: parts[2]}, ${parts[0]}"
    }

    private companion object {
        const val CARD_W = 210
        // How much bigger the chosen card is.
        private const val GROW = 0.12f
        private const val SEASON_SETTLE_MS = 160L
        val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        /** The dark wash over the whole screen while the sheet is up. */
        const val SHADE = 0x66000000
        /** The ring of the season shown: the accent blue, solid. */
        const val ACCENT_RIM = 0xFF3D8BFF.toInt()
        /** The sheet's own gradient, from clear at its top to nearly black at the bottom. */
        val SHEET_SHADE = intArrayOf(0x00080A0E, 0xD6080A0E.toInt(), 0xF2080A0E.toInt())
    }
}
