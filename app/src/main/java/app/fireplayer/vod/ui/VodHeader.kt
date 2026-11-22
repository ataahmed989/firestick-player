package app.fireplayer.vod.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.fireplayer.R
import app.fireplayer.data.Prefs
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Buttons
import app.fireplayer.ui.DrawnSpan
import app.fireplayer.ui.Glass
import app.fireplayer.ui.ImageLoader
import app.fireplayer.vod.Card
import app.fireplayer.vod.Title

/**
 * A page's banner ([VodSizes.HEADER_H]; My List's 186): the title's picture, sharp ([HeaderArt]), in a box at the
 * card's right with its left edge eased into the dark side ([VodSizes.backdropW]), under a separate dark shading
 * ([Shade]: round the words at the top left and down behind the buttons, the rest of the picture left clear), and on
 * it, at the top left, the title as its logo ([HeaderTitle]; its name where it has none), a facts line (12.5 sp: year,
 * genres, rating, length) and two lines of story; at the bottom left the Watch now and Details buttons ([actions]);
 * and, centred near the bottom, dots for the rotation. A fine outline is drawn over the picture ([Glass.edge]).
 *
 * Home's banner has two widths: beside Recent channels, and narrower beside the live picture's 16:9 window
 * ([VodSizes.homeHeaderW]). It changes from one to the other in one motion with nothing laid out on the way
 * ([drawAt]: its surface is drawn to a moving right edge, its picture's box and dots move with it, its words fade out),
 * and is laid out once for the new width at the end ([setNarrow]), its words fading back in ([veilWords]).
 *
 * While idle it goes through the page's feed (Top 10, or Popular's first 10) every few seconds ([HeaderRotation];
 * Settings › Home Header › Time on each title, 8 s by default), the words and the logo fading out and in together
 * and the picture crossfading; while focus is in the page's Continue row it shows the chosen card's title instead (only there: [HeaderFollow]; the page decides): the words at once, the
 * picture half a second later (so a held D-pad decodes nothing). With focus on one of its buttons it goes on turning
 * once no key has been pressed for that long; the buttons act on the title whose words are showing ([shownCard],
 * [HeaderPress]); Up there moves on to the next title at once ([stepUp]). Its words have fixed boxes, so new ones
 * only redraw them. The buttons' keys go to the page ([Listener]).
 */
@SuppressLint("ViewConstructor")
class VodHeader(
    context: Context,
    private val t: Tokens,
    /** Its width, dp (Home's: beside Recent channels). */
    private val wideW: Float,
    private val heightDp: Float = VodSizes.HEADER_H,
    /** Whether it has the Watch now and Details buttons (My List's follows the chosen poster and has none). */
    private val actions: Boolean = true,
    /** Home's: its width beside the live picture ([setNarrow], [drawAt]); every other banner's never changes. */
    private val narrowW: Float = wideW,
) : FrameLayout(context) {

    /** The width it's laid out at now, dp. */
    private var cardW = wideW

    enum class Button { WATCH, DETAILS }

    interface Listener {
        /** Focus came to the banner's buttons, or left them altogether. */
        fun onHeaderFocus(has: Boolean)
        fun onHeaderKey(button: Button, keyCode: Int, event: KeyEvent): Boolean
        /** The banner has no title left to show while one of its buttons holds focus: focus must move on first. */
        fun onHeaderEmptied()
    }

    var listener: Listener? = null

    private val main = Handler(Looper.getMainLooper())
    private val prefs = Prefs(context)
    /** Its idle time read from Settings at the start of each wait: a change applies from the next one. */
    private val rotation = HeaderRotation(everyMs = { HeaderIdle.ms(prefs.headerIdleSec) })
    private var feed: List<Title> = emptyList()
    /** The feed's logos have been asked for ahead ([fetchLogosAhead]). */
    private var logosAhead = false
    private var following: Card? = null

    /** Whether anything else (a window, the settings window) asks it to hold still; asked at each turn. */
    var blocked: () -> Boolean = { false }

    /**
     * The picture fills the card, or is a box at its right whose left edge is eased away (every page's card now; at
     * both of Home's widths the same box, so the picture isn't decoded again as it changes).
     */
    private val whole = VodSizes.backdropW(wideW) >= wideW
    private val art = HeaderArt(context, HeaderArt.ArtSpec.BANNER).apply { if (!whole) leftFade = VodSizes.BANNER_FADE_LEFT_TO }
    private val shade = Shade(context, t, wideW)

    /**
     * The card's surface (glass or classic) and, over everything, its glass outline: drawn here rather than as the
     * view's background and foreground, so they can be drawn to the moving right edge ([DrawnSpan], [drawAt]).
     */
    private var surface: Drawable? = null
    private var outline: Drawable? = null
    /** How much wider (px; narrower below 0) the card is drawn than it's laid out: 0 at rest. */
    private var drawnShift = 0f
    private val backingNow = RectF()

    /**
     * The card left of the picture's box, in the one colour the picture's left edge is eased into ([HeaderArt.leftFade],
     * [Glass.windowSolid]: the card's glass over the wallpaper): the see-through panel there would meet that colour in a
     * faint line where the box begins. It reaches two corners' width under the box, so it's whole behind the picture's
     * own rounded corners.
     */
    private val backing = RectF()
    private val backingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var backingRadius = 0f

    /** Fades as one without an offscreen layer: its texts don't overlap. */
    private val words = object : LinearLayout(context) {
        override fun hasOverlappingRendering() = false
    }.apply { orientation = LinearLayout.VERTICAL }

    /**
     * The words' own fade while the card changes width ([veilWords]), apart from the rotation's (on [words]), so
     * neither undoes the other. The whole card, so the words move inside it as they always did ([setHiddenTop]).
     */
    private val veil = object : FrameLayout(context) {
        override fun hasOverlappingRendering() = false
    }

    /**
     * The words' fade while the page lifts the card further than they can move clear of the cut ([setHiddenTop]), apart
     * from the other two fades.
     */
    private val cutFade = object : FrameLayout(context) {
        override fun hasOverlappingRendering() = false
    }

    /** The words' column: the title's box, the facts and the story are all this wide. */
    private var textW = t.dp(VodSizes.storyW(cardW))
    /** The logo's box is the same at both of Home's widths ([VodSizes.headerLogoW]): made once. */
    private val title = HeaderTitle(context, t, wideW)
    private val facts = t.label(context, VodSizes.FACTS)
    /** A facts line's width as it's drawn: only whole facts are shown ([VodSizes.headerFacts]). */
    private val factsWidth: (String) -> Float = { facts.paint.measureText(it) }
    private val story = t.label(context, VodSizes.STORY).apply {
        // Always two lines, in a box two lines tall (see init): new words never change its height, so they're only
        // redrawn (a wrapped height would lay the page out again whenever one line became two).
        setLines(VodSizes.HEADER_STORY_LINES)
    }
    private val pillSpec = Buttons.PillSpec(
        height = VodSizes.HEADER_BUTTON_H,
        pad = VodSizes.HEADER_BUTTON_PAD,
        text = VodSizes.HEADER_BUTTON,
        icon = VodSizes.HEADER_BUTTON_ICON,
        iconGap = VodSizes.HEADER_BUTTON_ICON_GAP,
    )
    private val watch = Buttons.ActionPill(context, t, pillSpec).apply { set(WATCH_NOW, R.drawable.ic_play) }
    private val details = Buttons.ActionPill(context, t, pillSpec).apply { set(DETAILS, null) }
    private val buttons = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        visibility = INVISIBLE
    }
    private val dots = Dots(context, t)
    /** What the words show now: the buttons act on it. */
    private var shown: Title? = null
    /** The followed Continue card's line for its facts (its episode), with [shown]. */
    private var shownCaption: String? = null

    init {
        clipChildren = true
        setWillNotDraw(false)
        addView(art)
        addView(shade)
        val shadow = t.dpf(VodSizes.HEADER_TEXT_SHADOW)
        val shadowDy = t.dpf(VodSizes.HEADER_TEXT_SHADOW_DY)
        facts.setShadowLayer(shadow, 0f, shadowDy, VodSizes.HEADER_TEXT_SHADOW_COLOR)
        story.setShadowLayer(shadow, 0f, shadowDy, VodSizes.HEADER_TEXT_SHADOW_COLOR)
        words.addView(title, LinearLayout.LayoutParams(textW, t.dp(VodSizes.HEADER_TITLE_H)))
        words.addView(facts, LinearLayout.LayoutParams(textW, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = t.dp(VodSizes.HEADER_FACTS_GAP) })
        words.addView(story, LinearLayout.LayoutParams(textW, story.lineHeight * VodSizes.HEADER_STORY_LINES).apply { topMargin = t.dp(VodSizes.HEADER_STORY_GAP) })
        // The words at the card's top left, whatever its height; the buttons at its bottom left.
        cutFade.addView(words, LayoutParams(textW, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = t.dp(VodSizes.HEADER_INSET)
            topMargin = t.dp(VodSizes.HEADER_TOP)
        })
        veil.addView(cutFade, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(veil, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (actions) {
            buttons.addView(watch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, t.dp(VodSizes.HEADER_BUTTON_H)))
            buttons.addView(details, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, t.dp(VodSizes.HEADER_BUTTON_H)).apply {
                leftMargin = t.dp(VodSizes.HEADER_BUTTON_GAP)
            })
            // The row reaches the card's left and bottom edges round the pills (as padding, not margins): the card clips
            // each of its parts to that part's own bounds, and a focused pill's growth, glow and shadow lie outside
            // the pill. Inside the row's padding they are drawn whole.
            buttons.clipToPadding = false
            buttons.setPadding(
                t.dp(VodSizes.HEADER_INSET), t.dp(VodSizes.HEADER_BUTTON_ROOM), t.dp(VodSizes.HEADER_INSET), t.dp(VodSizes.HEADER_BUTTONS_BOTTOM),
            )
            addView(buttons, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            })
            for ((which, pill) in listOf(Button.WATCH to watch, Button.DETAILS to details)) {
                Tokens.focusEffect(pill, Tokens.GROW_PILL, null) { has -> onButtonFocus(has) }
                pill.setOnKeyListener { _, keyCode, event ->
                    // Any key here: the wait for the next title starts again.
                    rotation.key(now())
                    schedule()
                    listener?.onHeaderKey(which, keyCode, event) ?: false
                }
            }
        }
        addView(dots, LayoutParams(t.dp(VodSizes.DOTS_W), t.dp(VodSizes.DOTS_H)).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            leftMargin = t.dp(VodSizes.headerDotsX(cardW))
            bottomMargin = t.dp(VodSizes.DOTS_BOTTOM)
        })
        applyLook()
    }

    /**
     * The look's surface: a glass panel with its outline drawn over the picture, or the classic dark one with the
     * picture 1 dp inside its border. The picture and the shading follow the card's corners.
     */
    fun applyLook() {
        surface?.callback = null
        outline?.callback = null
        surface = Glass.surface(context, Glass.Kind.PANEL).also { it.callback = this }
        outline = Glass.edge(context, Glass.Kind.PANEL)?.also { it.callback = this }
        placeParts()
        watch.applyLook()
        details.applyLook()
    }

    /** The picture, the shading and the backing for the width it's laid out at ([cardW]). */
    private fun placeParts() {
        val inset = if (Glass.on) 0 else t.dp(VodSizes.HEADER_ART_INSET)
        val r = Glass.cornerRadius(context, Glass.Kind.PANEL, t.dp(cardW), t.dp(heightDp)) - inset
        art.radius = r
        shade.radius = r
        val h = t.dp(heightDp) - 2 * inset
        val artW = t.dp(VodSizes.backdropW(cardW)) - (if (whole) 2 else 1) * inset
        place(art, artW, h, inset)
        place(shade, t.dp(cardW) - 2 * inset, h, inset)
        backingRadius = r
        if (whole) backing.setEmpty()
        else backing.set(inset.toFloat(), inset.toFloat(), t.dp(cardW) - inset - artW + 2 * r, (inset + h).toFloat())
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        val right = width + drawnShift
        surface?.let { drawSurface(canvas, it, right) }
        super.draw(canvas)
        outline?.let { drawSurface(canvas, it, right) }
    }

    private fun drawSurface(canvas: Canvas, d: Drawable, right: Float) {
        val b = d.bounds
        if (b.left != 0 || b.top != 0 || b.right != width || b.bottom != height) d.setBounds(0, 0, width, height)
        DrawnSpan.draw(canvas, d, 0f, right)
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === surface || who === outline || super.verifyDrawable(who)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (backing.isEmpty) return
        // It ends under the picture's box, so it moves with it.
        backingNow.set(backing)
        backingNow.right += drawnShift
        backingPaint.color = Glass.windowSolid
        if (backingNow.right > backingNow.left) canvas.drawRoundRect(backingNow, backingRadius, backingRadius, backingPaint)
    }

    // ---- Home's two widths ---------------------------------------------------------------------------------------

    /**
     * Drawn [p] of the way from its width beside Recent channels (0) to its width beside the live picture (1),
     * whatever width it's laid out at: the surface's right edge, the picture's box and the backing that move with it,
     * the shading (its oval round the words easing to the new width's) and the dots. Translations and drawing only.
     */
    fun drawAt(p: Float) {
        val shift = VodSizes.mix(t.dp(wideW).toFloat(), t.dp(narrowW).toFloat(), p) - t.dp(cardW)
        val dotsAt = VodSizes.mix(t.dp(VodSizes.headerDotsX(wideW)).toFloat(), t.dp(VodSizes.headerDotsX(narrowW)).toFloat(), p)
        val shadeX = VodSizes.mix(VodSizes.headerShadeX(wideW), VodSizes.headerShadeX(narrowW), p)
        dots.translationX = dotsAt - t.dp(VodSizes.headerDotsX(cardW))
        art.translationX = shift
        shade.drawAs(shift, shadeX / VodSizes.headerShadeX(cardW))
        // Drawn wider than it's laid out, the shading reaches past its own bounds.
        clipChildren = shift <= 0f
        if (drawnShift != shift) {
            drawnShift = shift
            invalidate()
        }
    }

    /** Laid out beside the live picture ([narrow]) or beside Recent channels: once, as a motion between them ends. */
    fun setNarrow(narrow: Boolean) {
        val w = if (narrow) narrowW else wideW
        if (w == cardW) return
        cardW = w
        textW = t.dp(VodSizes.storyW(w))
        for (v in listOf(words, title, facts, story)) {
            val lp = v.layoutParams
            lp.width = textW
            v.layoutParams = lp
        }
        (dots.layoutParams as LayoutParams).let {
            it.leftMargin = t.dp(VodSizes.headerDotsX(w))
            dots.layoutParams = it
        }
        shade.cardW = w
        layoutParams?.let {
            it.width = t.dp(w)
            layoutParams = it
        }
        placeParts()
        // The facts line holds the facts that fit the new column.
        facts.text = factsOf(shown, shownCaption)
    }

    /** The words fade out while the card changes width ([hidden]) and back in once it's laid out for the new one. */
    fun veilWords(hidden: Boolean, animate: Boolean) {
        val to = if (hidden) 0f else 1f
        veil.animate().cancel()
        if (!animate) {
            veil.alpha = to
            return
        }
        if (veil.alpha != to) veil.animate().alpha(to).setDuration(VodSizes.HEADER_TEXT_FADE_MS).start()
    }

    /** [v] at the card's right, [inset] inside its top and right edges; laid out again only when that changes. */
    private fun place(v: View, w: Int, h: Int, inset: Int) {
        val lp = v.layoutParams as LayoutParams
        if (lp.width == w && lp.height == h && lp.topMargin == inset && lp.rightMargin == inset && lp.gravity == (Gravity.END or Gravity.TOP)) return
        lp.width = w
        lp.height = h
        lp.gravity = Gravity.END or Gravity.TOP
        lp.topMargin = inset
        lp.rightMargin = inset
        v.layoutParams = lp
    }

    // ---- The buttons ---------------------------------------------------------------------------------------------

    /**
     * The title whose words the banner shows, as a card (the Continue card it follows, with its episode): what its
     * buttons act on. While it turns, this changes with the words, in the middle of their fade ([showText]).
     */
    val shownCard: Card? get() = following?.takeIf { it.title.id == shown?.id } ?: shown?.let { Card(it) }

    /**
     * The page has moved the card's top [px] out of view (a first row of posters lifts the page a little): the words
     * move down to stay clear of the cut ([VodSizes.headerWordsShift]), and fade out once they can't
     * ([VodSizes.headerWordsAlpha]). A translation and an alpha only; nothing is laid out.
     */
    fun setHiddenTop(px: Float) {
        val y = t.dpf(VodSizes.headerWordsShift(px / t.density))
        if (words.translationY != y) words.translationY = y
        val a = VodSizes.headerWordsAlpha(px / t.density)
        if (cutFade.alpha != a) cutFade.alpha = a
    }

    /** Whether one of the buttons has focus. */
    val hasButtonFocus: Boolean get() = watch.isFocused || details.isFocused

    /** Focus to [button]; false when the banner shows no title (or has no buttons). */
    fun focusButton(button: Button): Boolean {
        if (!actions || shown == null || buttons.visibility != VISIBLE) return false
        return (if (button == Button.WATCH) watch else details).requestFocus()
    }

    private val buttonsLeft = Runnable {
        if (hasButtonFocus) return@Runnable
        rotation.leaveButtons(now())
        schedule()
        listener?.onHeaderFocus(false)
    }

    private fun onButtonFocus(has: Boolean) {
        main.removeCallbacks(buttonsLeft)
        if (!has) {
            // To the other button: that one takes over at once. Elsewhere: the rotation's full wait starts again.
            main.post(buttonsLeft)
            return
        }
        // From one button to the other: the key that did it has started the wait again already.
        if (rotation.onButtons) return
        // It goes on turning here, after a full wait with no key pressed.
        rotation.focusButtons(now())
        schedule()
        listener?.onHeaderFocus(true)
    }

    // ---- The feed and the title followed -----------------------------------------------------------------------------

    /** The page's idle titles (up to 10). The title on screen stays when it's still among them. */
    fun setFeed(titles: List<Title>) {
        val list = titles.take(MAX_FEED)
        if (list.map { it.id } == feed.map { it.id }) {
            feed = list
            return
        }
        val showing = feed.getOrNull(rotation.index)?.id
        feed = list
        logosAhead = false
        fetchLogosAhead()
        rotation.setFeed(list.size, now(), keepIndex = false)
        val keep = list.indexOfFirst { it.id == showing }
        if (keep > 0) rotation.jumpTo(keep, now())
        if (following == null) showFeedTitle(fadeText = false)
        updateDots()
        schedule()
    }

    /** Focus is on [card] in the Continue row: its title shows, the picture after half a second. */
    fun follow(card: Card) {
        following = card
        rotation.follow(now())
        main.removeCallbacks(followArt)
        showText(card.title, card.caption)
        updateDots()
        // No turn while it follows, unless focus is on the buttons.
        schedule()
        main.postDelayed(followArt, FOLLOW_ART_MS)
    }

    /** Focus left the Continue row: back to the feed's title, the wait for the next one starting again. */
    fun unfollow() {
        if (following == null) return
        following = null
        main.removeCallbacks(followArt)
        rotation.unfollow(now())
        showFeedTitle(fadeText = false)
        updateDots()
        schedule()
    }

    fun pause() {
        rotation.pause()
        main.removeCallbacks(turn)
        main.removeCallbacks(followArt)
        // The next picture isn't decoded ahead for a hidden, stopped or closed page.
        main.removeCallbacks(warmNext)
    }

    fun resume() {
        if (!rotation.paused) return
        rotation.resume(now())
        // The picture and the logo let go while hidden come back.
        val c = following
        if (c != null) art.show(c.title.background) else feed.getOrNull(rotation.index)?.let { art.show(it.background) }
        title.show(shown)
        fetchLogosAhead()
        schedule()
    }

    /** Lets the pictures and the logo go (the page is hidden or stopped). */
    fun release() {
        pause()
        art.release()
        title.release()
    }

    /**
     * D-pad Up on one of the buttons ([repeat]: a held key's repeat): the next feed title now, with the usual change,
     * the wait starting again; a Continue card's title shown is left for the feed's next. False when nothing changed
     * (one title or none, or a repeat while the last change is under way).
     */
    fun stepUp(repeat: Boolean): Boolean {
        if (!rotation.step(now(), repeat, followedIsCurrent())) return false
        following = null
        main.removeCallbacks(followArt)
        showFeedTitle(fadeText = true)
        updateDots()
        schedule()
        return true
    }

    private val followArt = Runnable { following?.let { art.show(it.title.background) } }

    /**
     * The feed's logos reach the disk ahead of their turns (bytes only, on the loader's background thread), once per
     * feed and only while the banner shows: a turn then never waits on the network for one.
     */
    private fun fetchLogosAhead() {
        if (logosAhead || rotation.paused) return
        logosAhead = true
        for (i in feed.indices) ImageLoader.prefetch(HeaderLogo.urlOf(feed[i]))
    }

    private val turn = Runnable {
        if (blocked()) {
            // A window over the page: try again a turn later.
            rotation.restart(now())
            schedule()
            return@Runnable
        }
        if (rotation.advanceIfDue(now(), followedIsCurrent())) {
            // With focus on a button it has left the Continue card it was on: the feed goes on.
            following = null
            showFeedTitle(fadeText = true)
            updateDots()
        }
        schedule()
    }

    /** Whether the Continue card followed is the very title the feed is on (a turn from it then goes one further). */
    private fun followedIsCurrent(): Boolean = following?.title?.id.let { it != null && it == feed.getOrNull(rotation.index)?.id }

    private val warmNext = Runnable {
        val next = feed.getOrNull(rotation.nextIndex(followedIsCurrent())) ?: return@Runnable
        art.warm(next.background)
        title.warm(next)
    }

    private fun schedule() {
        main.removeCallbacks(turn)
        main.removeCallbacks(warmNext)
        val wait = rotation.delayFrom(now())
        if (wait < 0) return
        main.postDelayed(turn, wait)
        // The next picture and logo decoded ahead, so the switch never waits on them.
        main.postDelayed(warmNext, (wait - WARM_AHEAD_MS).coerceAtLeast(0))
    }

    private fun showFeedTitle(fadeText: Boolean) {
        val title = feed.getOrNull(rotation.index)
        if (title == null) {
            showText(null, null)
            art.show(null)
            return
        }
        // Off screen (paused), the picture waits: [resume] shows the title's then.
        if (!rotation.paused) art.show(title.background)
        if (!fadeText) {
            showText(title, null)
            return
        }
        words.animate().cancel()
        // The old words fade out, the title changes with nothing showing ([showText]: the logo, the words and what the
        // buttons act on, together), the new ones fade in.
        words.animate().alpha(0f).setDuration(TEXT_FADE_MS).withEndAction {
            // Unless the banner has turned to something else meanwhile (a card followed).
            if (following == null) showText(feed.getOrNull(rotation.index), null)
            words.alpha = 0f
            words.animate().alpha(1f).setDuration(TEXT_FADE_MS).start()
        }.start()
    }

    private fun showText(title: Title?, caption: String?) {
        words.animate().cancel()
        words.alpha = 1f
        shown = title
        shownCaption = caption
        // Off screen (paused) the logo waits, as the picture does: [resume] shows it then.
        if (rotation.paused) this.title.release() else this.title.show(title)
        facts.text = factsOf(title, caption)
        story.text = title?.description.orEmpty()
        // No title, no buttons (and nothing for focus to land on); focus on one moves on before they hide (a hidden
        // view holding focus would hand it anywhere).
        if (actions) {
            if (title == null && hasButtonFocus) listener?.onHeaderEmptied()
            buttons.visibility = if (title == null) INVISIBLE else VISIBLE
        }
    }

    private fun factsOf(title: Title?, caption: String?): String =
        title?.let { VodSizes.headerFacts(RowRules.facts(it, caption), textW.toFloat(), factsWidth) }.orEmpty()

    private fun now() = SystemClock.uptimeMillis()

    private fun updateDots() {
        dots.set(feed.size, rotation.index)
        dots.visibility = if (following == null && feed.size > 1) VISIBLE else INVISIBLE
    }

    /**
     * The banner's dark shading, a layer of its own over the picture (which stays sharp and whole under it), in the
     * card's own colour ([Glass.windowSolid]): an oval of shade round the words at the top left, reaching down the left
     * side behind the buttons, and a second layer rising towards the bottom for the dots ([VodSizes.HEADER_SHADE_ALPHAS],
     * [VodSizes.HEADER_SHADE_BOTTOM_ALPHAS]). Both ease out to nothing and are dithered, so neither ends in a line and
     * the rest of the picture is left clear. Its shaders are made once per size and colour; it never moves.
     */
    private class Shade(context: Context, private val t: Tokens, var cardW: Float) : View(context) {
        var radius = 0f
            set(v) {
                if (field == v) return
                field = v
                invalidate()
            }
        private val round = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
        private val below = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
        private val box = RectF()
        /** The oval's own stretch, and that stretch widened [across] times while the card changes width ([drawAs]). */
        private val oval = Matrix()
        private val ovalNow = Matrix()
        private var across = 1f
        /** How much wider (px; narrower below 0) than its bounds it's drawn. */
        private var shift = 0f
        /** The colour its shaders were made in: made again, once, when the card's tint or charcoal changes (Settings › Look). */
        private var shadeColor = 0

        /**
         * Drawn [shift] px wider than it's laid out (narrower below 0), with its oval round the words [across] times as
         * wide (scaled from the card's left edge, where it's centred): what's drawn changes, nothing is made.
         */
        fun drawAs(shift: Float, across: Float) {
            if (this.shift == shift && this.across == across) return
            this.shift = shift
            if (this.across != across) {
                this.across = across
                ovalNow.set(oval)
                ovalNow.postScale(across, 1f)
                round.shader?.setLocalMatrix(ovalNow)
            }
            box.right = width + shift
            invalidate()
        }

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            box.set(0f, 0f, w + shift, h.toFloat())
            makeShaders(h)
        }

        private fun makeShaders(h: Int) {
            shadeColor = Glass.windowSolid
            val rgb = shadeColor and 0x00FFFFFF
            // The oval: a circle of shade about its centre, stretched down to its reach ([VodSizes.headerShadeAt]).
            val bx = t.dpf(VodSizes.headerShadeX(cardW))
            val by = t.dpf(VodSizes.headerShadeY())
            val cx = VodSizes.HEADER_SHADE_CX * bx
            val cy = VodSizes.HEADER_SHADE_CY * by
            val rx = VodSizes.HEADER_SHADE_RX * bx
            val ry = VodSizes.HEADER_SHADE_RY * by
            oval.setScale(1f, ry / rx, cx, cy)
            ovalNow.set(oval)
            ovalNow.postScale(across, 1f)
            round.shader = RadialGradient(
                cx, cy, rx,
                IntArray(VodSizes.HEADER_SHADE_ALPHAS.size) { (VodSizes.HEADER_SHADE_ALPHAS[it] shl 24) or rgb }, null, Shader.TileMode.CLAMP,
            ).apply { setLocalMatrix(ovalNow) }
            below.shader = LinearGradient(
                0f, h * VodSizes.HEADER_SHADE_BOTTOM_FROM, 0f, h.toFloat(),
                IntArray(VodSizes.HEADER_SHADE_BOTTOM_ALPHAS.size) { (VodSizes.HEADER_SHADE_BOTTOM_ALPHAS[it] shl 24) or rgb }, null, Shader.TileMode.CLAMP,
            )
        }

        override fun onDraw(canvas: Canvas) {
            if (width <= 0) return
            if (shadeColor != Glass.windowSolid) makeShaders(height)
            canvas.drawRoundRect(box, radius, radius, round)
            canvas.drawRoundRect(box, radius, radius, below)
        }
    }

    /** Draws the rotation's dots, centred: the current one a 14 dp white bar, the others 5 dp dots at 35% white. */
    private class Dots(context: Context, private val t: Tokens) : View(context) {
        private var count = 0
        private var index = 0
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()

        fun set(count: Int, index: Int) {
            if (this.count == count && this.index == index) return
            this.count = count
            this.index = index
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (count <= 1) return
            val h = height.toFloat()
            val gap = t.dpf(VodSizes.DOT_GAP)
            val bar = t.dpf(VodSizes.DOT_BAR)
            var x = (width - (bar + (count - 1) * (h + gap))) / 2f
            for (i in 0 until count) {
                val w = if (i == index) bar else h
                paint.color = if (i == index) VodSizes.DOT_ON else VodSizes.DOT_OFF
                r.set(x, 0f, x + w, h)
                canvas.drawRoundRect(r, h / 2, h / 2, paint)
                x += w + gap
            }
        }
    }

    companion object {
        private const val MAX_FEED = 10
        private const val TEXT_FADE_MS = VodSizes.HEADER_TEXT_FADE_MS
        private const val FOLLOW_ART_MS = VodSizes.FOLLOW_ART_MS
        private const val WARM_AHEAD_MS = VodSizes.WARM_AHEAD_MS
        private const val WATCH_NOW = "Watch now"
        private const val DETAILS = "Details"
    }
}
