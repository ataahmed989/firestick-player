package app.fireplayer.vod.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Buttons
import app.fireplayer.ui.RowMotion
import app.fireplayer.vod.Card
import app.fireplayer.vod.Cancel
import app.fireplayer.vod.RowSpec
import app.fireplayer.vod.RowStyle

/**
 * One row of a Films & Shows page or a title page: its title (16 sp; none with [header] false) and its strip of cards
 * ([CardStrip]), moved like the Episodes sheet's ([RowMotion]): the chosen card at the left edge (page x 36, screen
 * x 94), grown, with a sliver of the one before it showing (the page clips at its left edge). It owns its items, kept
 * and filled in place when new cards come (so focus survives), and remembers the card chosen in it ([chosen]). Keys
 * on its items go to the page ([Listener]); nothing here asks Android's focus search.
 *
 * Its items: posters, wide cards, Top 10 posters, a show's episode cards ([RowStyle.EPISODE]), or chips (genres; a
 * show's season pills, [RowStyle.SEASONS], one of them shown selected, [markSelected]).
 */
@SuppressLint("ViewConstructor")
class TitleRow(
    context: Context,
    private val t: Tokens,
    /** What the row shows. The Search tab's rows change it for each query ([RowSpec.source]; never the style). */
    spec: RowSpec,
    private val listener: Listener,
    /** Whether the row shows its title above the cards. */
    val header: Boolean = true,
    /** The room under the row, dp. */
    gapBelow: Float = VodSizes.ROW_GAP,
    /** The browse pages' larger cards ([VodSizes.BROWSE_POSTER_W], [VodSizes.BROWSE_WIDE_W]); a title page's rows keep theirs. */
    val large: Boolean = false,
) : FrameLayout(context) {

    interface Listener {
        fun onItemFocused(row: TitleRow, index: Int)
        /** Focus left this row's items altogether. True: the chosen card shrinks back (false: the small window opened). */
        fun onRowLeft(row: TitleRow): Boolean
        fun onItemKey(row: TitleRow, index: Int, keyCode: Int, event: KeyEvent): Boolean
    }

    var spec: RowSpec = spec
        set(v) {
            require(v.style == field.style) { "A row keeps its style" }
            field = v
        }

    val style: RowStyle get() = spec.style
    val strip = CardStrip(context, t, spec.style, large)
    private val title = t.label(context, VodSizes.ROW_TITLE_STYLE).apply { text = spec.label }

    /** The items in order: a card each, then See all when there is one; chips in a GENRES or SEASONS row. */
    val items = ArrayList<View>()
    var cards: List<Card> = emptyList()
        private set
    /** The chips' words (genres, seasons). */
    var genres: List<String> = emptyList()
        private set
    var hasSeeAll = false
        private set

    /** The item chosen in this row (kept while focus is elsewhere). */
    var chosen = 0

    /**
     * How far the strip may move left, px. Unlimited for cards (each chosen one goes to the left edge); a row of season
     * pills moves only as far as its last pill needs ([clampToView]).
     */
    var maxOffset = Float.MAX_VALUE

    // The page's bookkeeping for this row's data.
    /** Bumped for each load: older answers are dropped. */
    var generation = 0
    var cancel: Cancel? = null
    /** Answered at least once with something to go by (cards, or a final "none"). */
    var answered = false
    /** Cards that came while the viewer was in this row and would have moved what they're on: shown when they leave. */
    var pending: Pair<List<Card>, Boolean>? = null
    /** Whether its pictures are bound, and which cards. */
    var boundRange: IntRange = IntRange.EMPTY

    val motion = RowMotion(object : RowMotion.Host {
        override val itemCount: Int get() = items.size
        override fun itemLeft(i: Int): Float = strip.itemLeft(i)
        override fun place(offset: Float) {
            strip.translationX = -minOf(offset, maxOffset)
        }

        override fun item(i: Int, share: Float, shift: Float) {
            val v = items[i]
            v.translationX = shift
            val grow = 1f + VodSizes.grow(style) * share
            val g = if (v is RankedItem) v.poster else v
            g.scaleX = grow
            g.scaleY = grow
            when (v) {
                is CardView -> v.focus = share
                is RankedItem -> v.poster.focus = share
            }
        }
    })

    private val stripTop = if (header) t.dp(VodSizes.stripTop()) else 0
    private val pageX = t.dp(VodSizes.PAGE_X)
    private val band = t.dp(VodSizes.bandH(spec.style, header, gapBelow, large))

    private val focusWatch = OnFocusChangeListener { v, has ->
        val i = items.indexOf(v)
        if (i < 0) return@OnFocusChangeListener
        if (has) {
            chosen = i
            motion.choose(i)
            listener.onItemFocused(this, i)
        } else {
            // To the next card: that one takes over at once. Elsewhere: the card shrinks back, the row stays put.
            post {
                if (strip.findFocus() == null && listener.onRowLeft(this)) motion.shrink()
            }
        }
    }

    private val keyWatch = OnKeyListener { v, keyCode, event ->
        val i = items.indexOf(v)
        if (i < 0) false else listener.onItemKey(this, i, keyCode, event)
    }

    init {
        motion.extra = t.dpf(VodSizes.extra(spec.style, large))
        // The chosen card's ring (and a rank number's edge) draw just outside the strip; the page clips at its edges.
        clipChildren = false
        clipToPadding = false
        if (header) {
            addView(title, LayoutParams(t.dp(VodSizes.tabHeaderW()), LayoutParams.WRAP_CONTENT).apply { leftMargin = pageX })
        }
        addView(strip, LayoutParams(LayoutParams.WRAP_CONTENT, t.dp(VodSizes.stripH(spec.style, large))).apply {
            leftMargin = pageX
            topMargin = stripTop
        })
        // Chips are laid out at their own widths: their places are known once laid out.
        if (VodSizes.isChips(spec.style)) strip.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> motion.apply() }
        visibility = GONE
    }

    /** The band's height: title, cards and the gap under them. */
    val bandHeight: Int get() = band

    /** What the row needs to show whole when chosen, px: its title, a grown card and its ring ([VodSizes.rowShownH]). */
    val shownHeight: Int = t.dp(VodSizes.rowShownH(spec.style, header, large))

    /** The index of the item holding focus, or -1. */
    fun focusedIndex(): Int {
        val f = strip.findFocus() ?: return -1
        return items.indexOf(f)
    }

    /**
     * Shows [cards] (and a See all card after them with [seeAll]), filling the items in place: the same views stay, so
     * a focused one keeps focus. The caller moves focus off items that go first.
     */
    fun bind(cards: List<Card>, seeAll: Boolean) {
        this.cards = cards
        val oldCards = items.size - if (hasSeeAll) 1 else 0
        // Cards that go (from the end), never the See all card.
        for (i in oldCards - 1 downTo cards.size) {
            val v = items.removeAt(i)
            (v as? CardView)?.releaseImage()
            (v as? RankedItem)?.poster?.releaseImage()
            strip.removeView(v)
        }
        // New cards, before the See all card.
        for (i in oldCards until cards.size) {
            val v = makeItem(seeAll = false)
            items.add(i, v)
            strip.addView(v, i)
        }
        if (seeAll && !hasSeeAll) {
            val v = makeItem(seeAll = true)
            items += v
            strip.addView(v)
        } else if (!seeAll && hasSeeAll) {
            val v = items.removeAt(items.size - 1)
            strip.removeView(v)
        }
        hasSeeAll = seeAll
        cards.forEachIndexed { i, c ->
            when (val v = items[i]) {
                is RankedItem -> {
                    v.poster.bind(c)
                    v.setRank(c.rank ?: (i + 1))
                    v.contentDescription = c.title.name
                }
                is CardView -> v.bind(c)
            }
        }
        if (seeAll) (items.last() as CardView).bindSeeAll()
        if (items.isEmpty()) chosen = 0 else chosen = chosen.coerceIn(0, items.size - 1)
        boundRange = IntRange.EMPTY
        motion.apply()
    }

    /** Shows genre chips: OK on one opens that genre's See all. */
    fun bindGenres(names: List<String>) = bindChips(names)

    /**
     * Shows chips with [names] (genres, seasons), filled in place. Focus on a chip that goes moves first to the last
     * one that stays (a focused view taken out would hand focus anywhere); with none left, the caller moves it.
     */
    fun bindChips(names: List<String>) {
        genres = names
        val f = RowRules.focusAfter(focusedIndex(), items.size, false, names.size, false)
        if (f >= 0) items.getOrNull(f)?.requestFocus()
        while (items.size > names.size) {
            val v = items.removeAt(items.size - 1)
            strip.removeView(v)
        }
        while (items.size < names.size) {
            val v = makeItem(seeAll = false)
            items += v
            strip.addView(v)
        }
        names.forEachIndexed { i, n ->
            val chip = items[i] as TextView
            if (chip.text.toString() != n) chip.text = n
        }
        chosen = if (items.isEmpty()) 0 else chosen.coerceIn(0, items.size - 1)
        motion.apply()
    }

    /** Item [i] shows as selected (the season shown); the others not. */
    fun markSelected(i: Int) {
        items.forEachIndexed { j, v -> v.isSelected = j == i }
    }

    /**
     * A chip row that fits needn't move: the strip moves left at most as far as its last chip needs to show within
     * [viewW] px (from the content's left edge). Called once the chips are laid out.
     */
    fun clampToView(viewW: Int) {
        val reach = strip.contentWidth + t.dp(VodSizes.extra(spec.style, large))
        maxOffset = (reach - viewW).coerceAtLeast(0).toFloat()
        motion.apply()
    }

    /** The item chosen in this row takes focus (back to this row from another, or into the page). */
    fun focusChosen(): Boolean {
        if (items.isEmpty()) return false
        chosen = chosen.coerceIn(0, items.size - 1)
        return items[chosen].requestFocus()
    }

    /** Pictures for the cards in [range]; the others let theirs go. */
    fun bindImages(range: IntRange) {
        boundRange = range
        for (i in items.indices) {
            val card = cardViewAt(i) ?: continue
            if (i in range) card.bindImage() else card.releaseImage()
        }
    }

    /** Lets every card's picture go; with [cancel] (a page left), pictures still queued for them aren't fetched. */
    fun releaseImages(cancel: Boolean = false) {
        boundRange = IntRange.EMPTY
        for (i in items.indices) cardViewAt(i)?.let { if (cancel) it.cancelImage() else it.releaseImage() }
    }

    /** The card view drawn for item [i] (a Top 10 item's poster), or null for a chip. */
    fun cardViewAt(i: Int): CardView? = when (val v = items.getOrNull(i)) {
        is RankedItem -> v.poster
        is CardView -> v
        else -> null
    }

    /** Whether item [i] is the See all card. */
    fun isSeeAll(i: Int): Boolean = hasSeeAll && i == items.size - 1

    /** Redraws what reads the look as it draws (See all cards, chips). */
    fun applyLook() {
        for (v in items) {
            if (v is TextView) v.background = chipBackground() else v.invalidate()
        }
    }

    private fun makeItem(seeAll: Boolean): View {
        val v: View = when {
            VodSizes.isChips(spec.style) -> Buttons.flatChip(context, t, VodSizes.CHIP, VodSizes.CHIP_PAD).apply { background = chipBackground() }
            seeAll -> CardView(context, t, CardView.Kind.SEE_ALL)
            spec.style == RowStyle.RANKED -> RankedItem(context, t, large)
            spec.style == RowStyle.WIDE -> CardView(context, t, CardView.Kind.WIDE, VodSizes.cardW(RowStyle.WIDE, large), VodSizes.cardH(RowStyle.WIDE, large))
            spec.style == RowStyle.EPISODE -> CardView(context, t, CardView.Kind.EPISODE)
            else -> CardView(context, t, CardView.Kind.POSTER, VodSizes.posterW(large), VodSizes.posterH(large))
        }
        v.isFocusable = true
        // OK is read by the row's key listener (a short press, or held for the small window): never View's own click.
        v.isClickable = false
        v.isLongClickable = false
        v.onFocusChangeListener = focusWatch
        v.setOnKeyListener(keyWatch)
        return v
    }

    /** A chip's surface: flat in both looks (glass in a moving strip would show the wrong wallpaper). */
    private fun chipBackground() = Buttons.flatChipBackground(
        t, t.dpf(VodSizes.CHIP_H / 2f), VodSizes.FLAT_RIM, VodSizes.CHIP_SELECTED_ALPHA, VodSizes.CHIP_SELECTED_RIM,
    )
}
