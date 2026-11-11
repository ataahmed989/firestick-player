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
        setBackgroundColor(0x66000000)
        isClickable = true
        visibility = View.GONE
    }
    private val sheet = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x00080A0E, 0xD6080A0E.toInt(), 0xF2080A0E.toInt()),
        )
        clipChildren = false
        clipToPadding = false
        setPadding(dp(42), dp(34), dp(42), dp(14))
    }
    private val title = text(22f, font700, color(R.color.text)).apply { text = "Episodes" }
    // Where the episodes come from, as a pod like the player's info pods, beside the seasons.
    private val source = text(10f, font700, color(R.color.text)).apply {
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
        setPadding(dp(4), dp(8), dp(4), dp(6))
    }
    private val cardsScroll = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
        isFillViewport = false
        addView(cardsRow)
    }

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
        // The seasons, and on the same line at the right the source pod: low, just above the episodes.
        val seasonsLine = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(seasonsScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(source, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        }
        sheet.addView(seasonsLine, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        sheet.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        sheet.addView(cardsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        root.addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        host.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Puts the sheet up, saying [waiting] until the episodes are known. */
    fun open(waiting: String) {
        guide = null
        pills.clear()
        seasonsRow.removeAllViews()
        cardsRow.removeAllViews()
        source.text = ""
        source.visibility = View.GONE
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
     * Shows the show's episodes, on the season playing, with the one after [playing] chosen. [progress] says how far
     * each was watched (0 to 1).
     */
    fun show(guide: ShowGuide, playing: String?, progress: (ShowGuide.Ep) -> Float) {
        if (!isOpen) return
        val firstTime = this.guide == null
        this.guide = guide
        playingId = playing
        progressOf = progress
        val count = guide.episodes.size
        Glass.background(source, Glass.Kind.CHIP)
        source.visibility = View.VISIBLE
        source.text = "From ${guide.portal} · $count episode" + if (count == 1) "" else "s"
        status.visibility = View.GONE
        val now = guide.episode(playing)
        if (firstTime || guide.seasons.none { it == season }) season = now?.season ?: guide.seasons.firstOrNull() ?: 0
        buildPills()
        buildCards()
        if (firstTime) {
            val list = guide.inSeason(season)
            val at = list.indexOfFirst { it.id == playing }
            // The one after the episode playing, as Netflix opens on.
            val focus = when {
                at < 0 -> 0
                at + 1 < list.size -> at + 1
                else -> at
            }
            focusCard(focus)
            root.isFocusable = false
        }
    }

    fun close() {
        if (!isOpen) return
        root.animate().cancel()
        root.visibility = View.GONE
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
                    buildCards()
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
            chosen -> 0x473D8BFF
            else -> 0x12FFFFFF
        }
        val rim = when {
            focused -> accent
            chosen -> 0xBF3D8BFF.toInt()
            else -> 0x1FFFFFFF
        }
        // Glass look: a season neither selected nor the one shown is a clear glass pod, like the other buttons.
        if (Glass.on && !focused && !chosen) {
            pill.background = Glass.drawable(activity, Glass.Kind.PILL)
            pill.setTextColor(color(R.color.text))
            return
        }
        pill.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(fill)
            setStroke(dp(1).coerceAtLeast(1), rim)
        }
        pill.setTextColor(if (focused) color(R.color.text_dark) else if (chosen) color(R.color.text) else color(R.color.text_muted))
    }

    private fun playingIndex(): Int = guide?.inSeason(season)?.indexOfFirst { it.id == playingId } ?: -1

    private fun focusCard(index: Int) {
        val n = cardsRow.childCount
        if (n == 0) return
        cardsRow.getChildAt(index.coerceIn(0, n - 1)).requestFocus()
    }

    private fun buildCards() {
        val guide = guide ?: return
        cardsRow.removeAllViews()
        cardsScroll.scrollTo(0, 0)
        guide.inSeason(season).forEach { ep -> cardsRow.addView(card(ep)) }
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

        card.setOnFocusChangeListener { v, has ->
            ring.visibility = if (has) View.VISIBLE else View.INVISIBLE
            thumb.elevation = if (has) dp(6).toFloat() else 0f
            v.animate().translationY(if (has) -dp(3).toFloat() else 0f).setDuration(140)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            if (has) scrollToCard(v)
        }
        card.setOnClickListener { onPlay(ep) }
        card.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (event.action == KeyEvent.ACTION_DOWN) pills.firstOrNull { it.tag == season }?.requestFocus()
                true
            } else keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        }
        return card
    }

    /** Keeps the chosen card a card's width in from the edge, so the next one shows. */
    private fun scrollToCard(card: View) {
        val margin = dp(CARD_W) / 2
        val left = card.left - cardsScroll.scrollX
        val right = card.right - cardsScroll.scrollX
        when {
            right > cardsScroll.width - margin -> cardsScroll.smoothScrollBy(right - (cardsScroll.width - margin), 0)
            left < margin -> cardsScroll.smoothScrollBy(left - margin, 0)
        }
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
        val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    }
}
