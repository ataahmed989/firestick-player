package app.fireplayer.ui

import kotlin.math.roundToInt

/**
 * The one glass style of the app's own screens (Home, Movies, TV Shows, My List, Search, Settings, the guide): dark
 * translucent glass. A surface is what's behind it, softly blurred where it's a window over the page ([PageGlass]),
 * under one restrained colour layer (the user's glass, [Shade]), with a fine hairline round it, a subtle highlight along
 * its top edge and a soft shadow outside. No milky glaze, no bevel, no blue wash: the colour comes from what's behind.
 * Text, icons and the selected (white) look are drawn over it at full strength, never faded or coloured with it.
 *
 * Three glasses take their layer from Settings › Look ([Shade]): App glass (the windows over the app's pages: the
 * settings window, hold windows, pickers, notices) and Playback glass (the windows over full-screen films and Live TV),
 * each a Tint of the background's own colour and a Dimness that darkens it; and Rail, cards & header (the glass in the
 * pages themselves: the rail, the logo and studio cards, Recent channels, the banner's card and its shading), a Tint
 * whose colour its Charcoal moves from the background's own towards charcoal.
 *
 * A window (the settings window, a hold window, a small dialog) is one frosted pane: what's behind it blurred, its colour
 * layer by what it's over ([paneTint]: App glass on pages, Playback glass over playback), a reflective rim (RIM_*) and a
 * soft shadow; its title is written on the pane, its options a lighter card of rows on it ([RowGroups]).
 *
 * Every value of that style is here, so tuning it is one edit. The shapes are [Glass.Kind]s and are made through
 * [Glass]: [Glass.background] for a surface in the page, [Glass.overlay] for a window over it, [Glass.edge] for an
 * outline alone. A flat surface's makings are a [Surface]. The player's buttons and pills (the film behind them) keep
 * their own look ([GlassDrawable]'s bright film, glaze, bevel and rim), which this doesn't change.
 */
object GlassStyle {

    // ---- The blurred backdrop of windows over the page ([PageGlass]) --------------------------------------------

    /** Screen pixels per pixel of the backdrop, across and down: a 1920 x 1080 screen is blurred as 192 x 108. */
    const val BACKDROP_SCALE = 10
    /** The blur's reach on screen, dp (three box passes of this radius): text behind a window can't be read. */
    const val BLUR_DP = 22f
    /** A newer backdrop fades in over the one before, ms. */
    const val BACKDROP_FADE_MS = 220L
    /** What's under a window has to hold still this long before it's copied again, ms. */
    const val SETTLE_MS = 120L
    /** The most copies a second while what's under a window keeps changing, whatever the Glass refresh setting. */
    const val MAX_REFRESH_PER_SEC = 2

    // ---- Tint ---------------------------------------------------------------------------------------------------

    /**
     * The background's own colour: the glass wallpaper's (wallpaper_glass.png, its average: 15.5, 24.6, 32.9). App glass
     * and Playback glass are tinted with this colour ([Shade]), so over the page a window reads as a frosted continuation
     * of the wallpaper: over the wallpaper it's the wallpaper frosted, over a picture it calms it towards the wallpaper so
     * white words read, and what's behind still shows its own colours.
     */
    const val WALLPAPER = 0x10191F
    /**
     * Charcoal: the neutral dark the glass was tinted with before it took the wallpaper's colour, and the colour of Rail,
     * cards & header's tint at full Charcoal ([Shade.charcoal]): darker than the wallpaper and with none of its blue.
     */
    const val CHARCOAL = 0x0C0E12
    /**
     * The wallpaper's colour with nothing showing through: Classic's banner backing and its picture's eased edge (Glass
     * has the banner card's own, [Shade.solid]), and where glass has no picture to show (the widened rail's fallback).
     */
    const val WINDOW_SOLID = WALLPAPER or 0xFF000000.toInt()
    /** Buttons, pills, circles and chips: a little lighter, so one reads as a surface on the dark wallpaper too. */
    const val CONTROL_TINT = 0x9E1B1F26.toInt()
    /** A faint light from above, fading out by [SHEEN_SHARE] of the way down. */
    const val SHEEN = 0x0DFFFFFF
    const val SHEEN_SHARE = 0.45f
    /** A row on glass (a list's rows, the Settings tab's): barely lighter than its window. */
    const val ROW_FILL = 0x12FFFFFF
    const val ROW_HIGHLIGHT = 0x1FFFFFFF

    // ---- The user's glass (Settings › Look: App glass, Rail, cards & header, Playback glass) -------------------------

    /**
     * A glass's own layers, in percent (0 to [SHADE_MAX], in [SHADE_STEP]s), laid over the frosted glass (what's behind
     * it, blurred or as it is) and under everything drawn on it: its sheen and rim, its option cards, and the text, icons
     * and focus highlight, which these never change.
     *
     * - [tint] (Tint): how strong a colour layer is, from none at all (0%: the frosted glass as it is) to solid (100%).
     *   Its colour is the background's own ([WALLPAPER]), or [charcoal]% of the way from it to [CHARCOAL]
     *   ([charcoalColor]).
     * - [dim] (Dimness: App glass, Playback glass): black laid over the glass after the tint, that strong. It darkens the
     *   glass whatever the tint, even with none.
     * - [charcoal] (Charcoal: Rail, cards & header): where the tint's colour sits between the background's own (0%) and
     *   charcoal (100%), more neutral and darker. It shows only with some tint.
     *
     * Per colour channel, with B what shows through, T the tint's colour and t, d the tint and the dimness (0 to 1):
     *
     *     out = ((1 − t)·B + t·T)·(1 − d) = (1 − A)·B + A·C,   A = 1 − (1 − t)(1 − d),   C = T·t(1 − d) / A
     *
     * so the two layers are laid as one colour, [color]: C at alpha A, worked out once here ([shadeColor]), and what the
     * screen shows is the two layers to the level. [solid] is that glass with only the wallpaper behind it: the colour a
     * banner's picture is eased into, and what backs it, so they meet the card's see-through glass round them without a
     * line.
     */
    data class Shade(val tint: Int, val dim: Int = 0, val charcoal: Int = 0) {
        val color: Int = shadeColor(tint, dim, charcoal)
        val solid: Int = over(color, WINDOW_SOLID)
    }

    /** The steps of a tint, a dimness or a charcoal, and the most of any, percent. */
    const val SHADE_STEP = 5
    const val SHADE_MAX = 100

    /**
     * App glass: the windows over the app's pages (the settings window, hold windows for titles and channels, pickers,
     * notices). The background's own colour at 58% (0x94, as strong as the glass has always been), then darkened by 15%:
     * the wallpaper frosted, a little deeper (laid as #A40C1318).
     */
    val APP_SHADE = Shade(tint = 58, dim = 15)
    /**
     * Rail, cards & header: the glass in the pages themselves (the rail, the logo and studio cards, Recent channels, the
     * banner's card and its shading, the episode pods). Charcoal at 58%, 0x940C0E12: exactly the look they had before
     * the glass took the wallpaper's colour.
     */
    val RAIL_SHADE = Shade(tint = 58, charcoal = 100)
    /**
     * Playback glass: windows over full-screen films and Live TV. No tint and no darkening: the frosted picture as it is.
     * It's the players' dimness settings' Default preset; a custom there has its own glass tint and glass dimness.
     */
    val PLAYBACK_SHADE = Shade(tint = 0, dim = 0)

    /** A tint, dimness or charcoal kept within 0% and [SHADE_MAX]. */
    fun clampShade(percent: Int): Int = percent.coerceIn(0, SHADE_MAX)

    /**
     * [percent] one step up ([direction] above 0) or down (below 0), within 0% and [SHADE_MAX]: to the next [SHADE_STEP]
     * on that side, or to [stop] on the way (a glass's own default between two steps, 58%), so a value off the steps
     * comes back onto them and the default can always be stepped back to.
     */
    fun stepShade(percent: Int, direction: Int, stop: Int = -1): Int {
        val p = clampShade(percent)
        if (direction == 0) return p
        val next = if (direction > 0) (p / SHADE_STEP + 1) * SHADE_STEP else ((p + SHADE_STEP - 1) / SHADE_STEP - 1) * SHADE_STEP
        val onTheWay = if (direction > 0) stop in (p + 1) until next else stop in (next + 1) until p
        return clampShade(if (onTheWay) stop else next)
    }

    /**
     * [Shade.color]: the one colour (ARGB) laid over the frosted glass for [tint]%, [dim]% and [charcoal]% (see [Shade]):
     * nothing at all ([CLEAR]) with neither a tint nor a dimness; black alone at no tint.
     */
    fun shadeColor(tint: Int, dim: Int = 0, charcoal: Int = 0): Int {
        val t = clampShade(tint) / SHADE_MAX.toFloat()
        val d = clampShade(dim) / SHADE_MAX.toFloat()
        val alpha = ((1f - (1f - t) * (1f - d)) * 255f).roundToInt()
        if (alpha == 0) return CLEAR
        // C against the alpha as it's stored, so what the screen shows is the two layers to the level.
        val k = t * (1f - d) * 255f / alpha
        val rgb = charcoalColor(charcoal)
        return (alpha shl 24) or (scale(rgb, 16, k) shl 16) or (scale(rgb, 8, k) shl 8) or scale(rgb, 0, k)
    }

    /** The tint's colour (RGB) at [charcoal]%: that far from the background's own ([WALLPAPER]) to [CHARCOAL]. */
    fun charcoalColor(charcoal: Int): Int {
        val d = clampShade(charcoal) / SHADE_MAX.toFloat()
        return (lerp(WALLPAPER, CHARCOAL, d, 16) shl 16) or (lerp(WALLPAPER, CHARCOAL, d, 8) shl 8) or lerp(WALLPAPER, CHARCOAL, d, 0)
    }

    /** [top] (ARGB) laid over [base] (opaque): the colour that shows, opaque. */
    fun over(top: Int, base: Int): Int {
        val a = top ushr 24
        return (0xFF shl 24) or (mix(top, base, a, 16) shl 16) or (mix(top, base, a, 8) shl 8) or mix(top, base, a, 0)
    }

    private fun lerp(from: Int, to: Int, d: Float, shift: Int): Int {
        val a = (from shr shift) and 0xFF
        val b = (to shr shift) and 0xFF
        return (a + (b - a) * d).roundToInt().coerceIn(0, 255)
    }

    private fun scale(rgb: Int, shift: Int, k: Float): Int = (((rgb shr shift) and 0xFF) * k).roundToInt().coerceIn(0, 255)

    private fun mix(top: Int, base: Int, a: Int, shift: Int): Int =
        (((top shr shift) and 0xFF) * a + ((base shr shift) and 0xFF) * (255 - a) + 127) / 255

    // ---- A window's pane (the settings window, hold windows, small dialogs, over the page or over playback) ---------

    /**
     * What a window's glass is over, which is all that changes its pane: the app's own pages (the page behind it,
     * blurred), or full-screen playback (the film player, Live TV full screen: the picture behind it, blurred).
     */
    enum class Over { PAGE, PLAYBACK }

    /**
     * The pane's colour layer over what's behind it ([Shade.color]): on pages the App glass's ([app]), over playback
     * the Playback glass's ([playback]: the players' dimness preset on, its glass tint and glass dimness; by default none
     * at all, no colour and no dark: the frosted picture as it is).
     */
    fun paneTint(over: Over, app: Shade, playback: Shade): Int = if (over == Over.PLAYBACK) playback.color else app.color

    /** The faint light from above ([SHEEN]) on pages; over playback nothing is laid over the picture. */
    fun paneSheen(over: Over): Int = if (over == Over.PLAYBACK) CLEAR else SHEEN

    /** How much of the film's light a window's blurred picture keeps over playback ([GlassBackdrop]): all of it. */
    const val PLAYBACK_PANE_LIGHT = 1f

    /** Nothing at all. */
    const val CLEAR = 0x00FFFFFF

    /**
     * The pane's reflective rim, all round, the same over pages and playback (the glass reference's edge): bright along
     * the top and round the top corners ([RIM_TOP], its rgba(255,255,255,.42)), fading over [RIM_FADE_DP] to a faint
     * line down the sides ([RIM_SIDE]), and a softer catch of light round the foot ([RIM_FOOT]); [RIM_DP] thick.
     */
    const val RIM_TOP = 0x6BFFFFFF
    const val RIM_SIDE = 0x1FFFFFFF
    const val RIM_FOOT = 0x47FFFFFF
    const val RIM_DP = 1f
    const val RIM_FADE_DP = 40f
    /** The glow just inside the top edge, under the rim (the reference's inset 0 1px 0 #ffffff20), this deep. */
    const val RIM_GLOW = 0x20FFFFFF
    const val RIM_GLOW_DP = 2f

    // ---- Flat surfaces in the page ----------------------------------------------------------------------------------

    /**
     * What a flat piece of glass in the page is made of: [coats] of [fill] over whatever is behind it, the faint light
     * from above ([sheen]) and the outline ([edge]: the hairline all round and [highlight] along the top). Drawn by
     * [GlassBakes.surface], whether it's stamped many times a frame (the guide's cells and logo tiles) or is a view's
     * background ([Glass.Kind.CELL]).
     */
    data class Surface(
        val fill: Int,
        val coats: Int = 1,
        val sheen: Boolean = false,
        val edge: Boolean = true,
        val highlight: Int = HIGHLIGHT,
    )

    /**
     * The cell glass: a programme cell of the guide, and the Search tab's bar and history pods ([Glass.Kind.CELL]).
     * Barely lighter than the page behind it, a hairline round it and a gentle highlight along its top. Named here
     * once: tuning it changes the guide's cells and the Search tab's pods together.
     */
    val CELL = Surface(ROW_FILL, highlight = ROW_HIGHLIGHT)

    // ---- Borders ------------------------------------------------------------------------------------------------

    /** The hairline round every glass surface: about a pixel on the TV, muted. */
    const val HAIRLINE = 0x26FFFFFF
    const val HAIRLINE_DP = 0.5f
    /** The highlight along a surface's top edge, following its corners. */
    const val HIGHLIGHT = 0x40FFFFFF
    const val HIGHLIGHT_DP = 0.75f
    /** The very subtle edge of a card that isn't chosen (the chosen one has VodSizes.RING, white, outside it). */
    const val CARD_EDGE = 0x1FFFFFFF
    const val CARD_EDGE_DP = 0.5f

    // ---- Depth: soft shadows outside the shape (colour, how far down, blur; dp) -------------------------------------

    /** A window's, over pages and playback alike (and the rail's and the banner card's, in the page). */
    const val WINDOW_SHADOW = 0x4D000000
    const val WINDOW_SHADOW_DY = 5f
    const val WINDOW_SHADOW_BLUR = 10f
    const val CONTROL_SHADOW = 0x33000000
    const val CONTROL_SHADOW_DY = 2f
    const val CONTROL_SHADOW_BLUR = 4f
    const val CHIP_SHADOW = 0x26000000
    const val CHIP_SHADOW_DY = 1f
    const val CHIP_SHADOW_BLUR = 2.5f

    // ---- Corner radii, dp (never more than half the shorter side; pills, circles and chips are round) -------------

    /** The settings window, Recent channels, the banner's card. */
    const val RADIUS_WINDOW = 22f
    /** Hold windows, small dialogs, notices, the rail's window. */
    const val RADIUS_POPUP = 15f
    /** A row: a pill while one line high, soft corners when taller. */
    const val RADIUS_ROW = 18f
    /**
     * The cell glass's softer corners: the guide's programme cells (GuideView's glass radius, 8 dp) and the Search
     * tab's bar ([Glass.Kind.CELL_BAR]). The history pods under the bar keep fully round ends ([Glass.Kind.CELL]).
     */
    const val RADIUS_CELL = 8f

    /** The hairline's width, px: [HAIRLINE_DP], a whole pixel at least. */
    fun hairlinePx(density: Float): Float = maxOf(HAIRLINE_DP * density, 1f)

    /** A card's unchosen edge, px: a whole pixel at least. */
    fun cardEdgePx(density: Float): Float = maxOf(CARD_EDGE_DP * density, 1f)

    /** The blur's radius in backdrop pixels, for a backdrop [scale] screen pixels to one of its own. */
    fun blurRadius(density: Float, scale: Float): Int = (BLUR_DP * density / scale).toInt().coerceAtLeast(1)

    /** The backdrop's size along a screen side of [screen] px: one pixel per [BACKDROP_SCALE], rounded up. */
    fun backdropSize(screen: Int): Int = ((screen + BACKDROP_SCALE - 1) / BACKDROP_SCALE).coerceAtLeast(1)

    /**
     * When what's under a window is copied next, uptime ms: once it has held still for [SETTLE_MS] after the change
     * at [now], and no sooner after the last copy ([capturedAt]) than the Glass refresh setting ([perSec], copies a
     * second, [MAX_REFRESH_PER_SEC] at most) allows. So a page that keeps moving under a window is copied a couple of
     * times a second at most, and one that's still isn't copied at all.
     */
    fun nextCaptureAt(now: Long, capturedAt: Long, perSec: Int): Long {
        val interval = 1_000L / perSec.coerceIn(1, MAX_REFRESH_PER_SEC)
        return maxOf(now + SETTLE_MS, capturedAt + interval)
    }
}
