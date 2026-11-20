package app.fireplayer.ui

/**
 * The one glass style of the app's own screens (Home, Movies, TV Shows, My List, Search, Settings, the guide): dark
 * translucent glass. A surface is what's behind it, softly blurred where it's a window over the page ([PageGlass]),
 * under one restrained dark tint, with a fine hairline round it, a subtle highlight along its top edge and a soft
 * shadow outside. No milky glaze, no bevel, no blue wash: the colour comes from what's behind. Text, icons and the
 * selected (white) look are drawn over it at full strength, never faded with it.
 *
 * Every value of that style is here, so tuning it is one edit. The shapes are [Glass.Kind]s and are made through
 * [Glass]: [Glass.background] for a surface in the page, [Glass.overlay] for a window over it, [Glass.edge] for an
 * outline alone. A flat surface's makings are a [Surface]. The player's glass (the film behind it) and full-screen Live TV's keep their own look
 * ([GlassDrawable]'s bright film, glaze, bevel and rim), which this doesn't change.
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

    /** Windows: the settings window, hold windows, small dialogs, the rail, Recent channels, the banner's card. */
    const val WINDOW_TINT = 0x940C0E12.toInt()
    /** The windows' colour with nothing showing through: what a banner's picture is eased into, and what backs it. */
    const val WINDOW_SOLID = WINDOW_TINT or 0xFF000000.toInt()
    /** Buttons, pills, circles and chips: a little lighter, so one reads as a surface on the dark wallpaper too. */
    const val CONTROL_TINT = 0x9E1B1F26.toInt()
    /** A faint light from above, fading out by [SHEEN_SHARE] of the way down. */
    const val SHEEN = 0x0DFFFFFF
    const val SHEEN_SHARE = 0.45f
    /** A row on glass (a list's rows, the Settings tab's): barely lighter than its window. */
    const val ROW_FILL = 0x12FFFFFF
    const val ROW_HIGHLIGHT = 0x1FFFFFFF

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
