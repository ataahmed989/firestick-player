package app.fireplayer.ui

import kotlin.math.roundToInt

/**
 * The one glass style of the app's own screens (Home, Movies, TV Shows, My List, Search, Settings, the guide): dark
 * translucent glass. A surface is what's behind it, softly blurred where it's a window over the page ([PageGlass]),
 * under one restrained tint of the wallpaper's own colour (the user's App glass tint and dimness, [Shade]), with a fine
 * hairline round it, a subtle highlight along its top edge and a soft shadow outside. No milky glaze, no bevel, no blue
 * wash: the colour comes from what's behind. Text, icons and the selected (white) look are drawn over it at full
 * strength, never faded with it.
 *
 * A window (the settings window, a hold window, a small dialog) is one frosted pane: what's behind it blurred, its tint
 * and dimness by what it's over ([paneTint]: App glass on pages, Playback glass over playback, none by default), a
 * reflective rim (RIM_*) and a soft shadow; its title is written on the pane, its options a lighter card of rows on it
 * ([RowGroups]).
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
     * The glass wallpaper's own colour (wallpaper_glass.png, its average: 15.5, 24.6, 32.9): the windows' tint is this
     * colour ([Shade]), so glass over the page reads as a frosted continuation of the wallpaper, never as a colour of its
     * own: over the wallpaper a window is the wallpaper frosted, over a picture it calms it towards the wallpaper so white
     * words read, and what's behind still shows its own colours.
     */
    const val WALLPAPER = 0x10191F
    /**
     * The wallpaper's colour with nothing showing through: Classic's banner backing and its picture's eased edge (Glass
     * has the app glass's own, [Shade.solid]), and where glass has no picture to show (the widened rail's fallback).
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

    // ---- The user's tint and dimness (Settings › Look: App glass, Playback glass) -----------------------------------

    /**
     * How a glass is shaded, in percent (0 to [SHADE_MAX], in [SHADE_STEP]s): [tint], the wallpaper's own colour
     * ([WALLPAPER]) laid over the frosted glass at that strength, then [dim], black laid over that. The wallpaper's colour
     * is dark, so more tint also darkens a little: tint calms what shows through towards the wallpaper, dimness darkens
     * all of it. Per colour channel, with t = tint / 100, d = dim / 100, B what shows through and W the wallpaper's colour:
     *
     *     out = ((1 − t)·B + t·W)·(1 − d) = (1 − A)·B + A·C,   A = 1 − (1 − t)(1 − d),   C = W·t(1 − d) / A
     *
     * so the two layers are laid as one colour, [color]: C at alpha A (nothing at all at 0% and 0%; the wallpaper's colour,
     * solid, at 100% tint; black at 100% dimness). [solid] is that glass with only the wallpaper behind it, W·(1 − d)
     * whatever the tint: the colour a banner's picture is eased into, and what backs it. Both are worked out once, here.
     */
    data class Shade(val tint: Int, val dim: Int) {
        val color: Int = shadeColor(tint, dim)
        val solid: Int = over(color, WINDOW_SOLID)
    }

    /** The steps of a tint or a dimness, and the most of either, percent. */
    const val SHADE_STEP = 5
    const val SHADE_MAX = 100

    /**
     * App glass: every glass surface of the app's pages and every window over them (the rail, Recent channels, the
     * banner's card, the settings window, hold windows, pickers, notices). 55% tint and 5% dimness are the look it had as
     * one tint, the wallpaper's colour at 0x94 (58%), within two levels over anything from black to white: that kept
     * 0.420 of what showed through and laid 0.580 of W over it; this keeps (1 − 0.55)(1 − 0.05) = 0.4275 of it and lays
     * 0.55 × 0.95 = 0.5225 of W (stored as #920F171C). Of the 5% steps with both above 0, the nearest by far.
     */
    val APP_SHADE = Shade(55, 5)
    /** Playback glass: windows over full-screen films and Live TV. None of either: the frosted picture as it is. */
    val PLAYBACK_SHADE = Shade(0, 0)

    /** A tint or dimness kept within 0% and [SHADE_MAX]. */
    fun clampShade(percent: Int): Int = percent.coerceIn(0, SHADE_MAX)

    /** [percent] one step up ([direction] 1) or down (-1), within 0% and [SHADE_MAX]. */
    fun stepShade(percent: Int, direction: Int): Int = clampShade(percent + direction.coerceIn(-1, 1) * SHADE_STEP)

    /** [Shade.color]: the one colour (ARGB) laid over the frosted glass for [tint]% and [dim]% (see [Shade]). */
    fun shadeColor(tint: Int, dim: Int): Int {
        val t = clampShade(tint) / 100f
        val d = clampShade(dim) / 100f
        val alpha = ((1f - (1f - t) * (1f - d)) * 255f).roundToInt()
        if (alpha == 0) return CLEAR
        // C against the alpha as it's stored, so what the screen shows is the sum above to the level.
        val k = t * (1f - d) * 255f / alpha
        return (alpha shl 24) or (channel(WALLPAPER, 16, k) shl 16) or (channel(WALLPAPER, 8, k) shl 8) or channel(WALLPAPER, 0, k)
    }

    /** [top] (ARGB) laid over [base] (opaque): the colour that shows, opaque. */
    fun over(top: Int, base: Int): Int {
        val a = top ushr 24
        return (0xFF shl 24) or (mix(top, base, a, 16) shl 16) or (mix(top, base, a, 8) shl 8) or mix(top, base, a, 0)
    }

    private fun channel(rgb: Int, shift: Int, k: Float): Int = (((rgb shr shift) and 0xFF) * k).roundToInt().coerceIn(0, 255)

    private fun mix(top: Int, base: Int, a: Int, shift: Int): Int =
        (((top shr shift) and 0xFF) * a + ((base shr shift) and 0xFF) * (255 - a) + 127) / 255

    // ---- A window's pane (the settings window, hold windows, small dialogs, over the page or over playback) ---------

    /**
     * What a window's glass is over, which is all that changes its pane: the app's own pages (the page behind it,
     * blurred), or full-screen playback (the film player, Live TV full screen: the picture behind it, blurred).
     */
    enum class Over { PAGE, PLAYBACK }

    /**
     * The pane's tint and dimness over what's behind it, as one colour ([Shade.color]): on pages the app glass's
     * ([app]), over playback the playback glass's ([playback]; by default none at all, no colour and no dark: the frosted
     * picture as it is, which the screen dimmer alone darkens, its glass amount).
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
