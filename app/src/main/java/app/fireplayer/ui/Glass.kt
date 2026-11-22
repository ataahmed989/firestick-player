package app.fireplayer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import app.fireplayer.R
import kotlin.math.ceil

/**
 * The optional glass look (Settings, Look, Glass look): buttons, chips, rows and windows become clear glass
 * showing the blurred film behind them, with a light rim, while the layout, icons, text and colours stay as
 * they are. The date, time and seek line never get glass. Fire OS can't blur what's behind a view, so the
 * player keeps a small blurred copy of the film ([GlassBackdrop]) that each glass shape ([GlassDrawable]) shows.
 *
 * On the app's own screens (Home, Movies, TV Shows, My List, Search, Settings, the guide) glass is the one dark
 * translucent style of [GlassStyle], made here three ways: [background] for a surface in the page (a button, a
 * chip, a row, Recent channels, the banner's card), [overlay] for a window over the page (it shows the page behind
 * it, blurred: [PageGlass]) and [edge] for an outline alone, over a picture.
 */
object Glass {
    @Volatile var on = false

    /**
     * How much every piece of glass is darkened (Screen dimmer, a custom's glass amount): 0 not at all, 0.8 to a
     * fifth of its light. The film inside and the milky glaze dim; the selected look dims half as much, so it
     * still stands out. A window's colour layer over playback ([playbackShade]) is darkened with it.
     */
    @Volatile var dim = 0f

    /**
     * The user's three glasses' layers (Settings › Look, saved by [app.fireplayer.data.GlassShades], [GlassStyle.Shade]):
     * App glass on the windows over the app's pages and Playback glass on the windows over full-screen playback, each a
     * tint and a dimness; Rail, cards & header on the glass in the pages themselves, a tint and its charcoal. Set by
     * [useShades]; each piece of glass reads its own as it's drawn, under whatever is drawn on it.
     */
    @Volatile var appShade: GlassStyle.Shade = GlassStyle.APP_SHADE
        private set
    @Volatile var railShade: GlassStyle.Shade = GlassStyle.RAIL_SHADE
        private set
    @Volatile var playbackShade: GlassStyle.Shade = GlassStyle.PLAYBACK_SHADE
        private set

    /** Counts the changes to the three: glass drawn before one draws its colour layer again, once. */
    @Volatile var shadeChanges = 0
        private set

    /**
     * Puts these on the glass: true when that changed them, and glass on screen is then to be drawn again ([redraw]).
     * The drawings made with a colour no longer on go ([GlassBakes.dropStale]); each is made again once, when next needed.
     */
    fun useShades(app: GlassStyle.Shade, rail: GlassStyle.Shade, playback: GlassStyle.Shade): Boolean {
        if (app == appShade && rail == railShade && playback == playbackShade) return false
        appShade = app
        railShade = rail
        playbackShade = playback
        shadeChanges++
        GlassBakes.dropStale()
        return true
    }

    /**
     * The colour a banner's picture is eased into and what backs it, in the look on: the banner card's glass (Rail,
     * cards & header) with only the wallpaper behind it ([GlassStyle.Shade.solid]), or Classic's own
     * ([GlassStyle.WINDOW_SOLID]).
     */
    val windowSolid: Int get() = if (on) railShade.solid else GlassStyle.WINDOW_SOLID

    /** [view] and everything in it drawn again: each piece of glass then reads the shades (or the dimmer) as it draws. */
    fun redraw(view: View) {
        view.invalidate()
        if (view is ViewGroup) for (i in 0 until view.childCount) redraw(view.getChildAt(i))
    }

    /**
     * The shapes that can be glass. Each replaces one classic background ([classicRes]). [CELL] and [CELL_BAR] are
     * rows in the cell glass ([GlassStyle.CELL], the one material of the guide's programme cells), told apart by their
     * corners only: [CELL] a pod with round ends (the Search tab's history), [CELL_BAR] a bar with the programme cells'
     * softer corners ([GlassStyle.RADIUS_CELL]: the Search tab's bar). Selected they're the white row, as [ROW];
     * classic, the solid row.
     */
    enum class Kind { PANEL, POPUP, DIALOG_POPUP, CIRCLE, PILL, CHIP, MORE_ICON, ROW, CELL, CELL_BAR }

    /** A row of any glass: it sits on its page or window and has no film of its own. */
    internal fun isRow(kind: Kind): Boolean = kind == Kind.ROW || kind == Kind.CELL || kind == Kind.CELL_BAR

    /** The background each shape has with the glass look off. */
    fun classicRes(kind: Kind): Int = when (kind) {
        Kind.PANEL -> R.drawable.bg_panel
        Kind.POPUP, Kind.DIALOG_POPUP -> R.drawable.bg_popup
        Kind.CIRCLE -> R.drawable.bg_circle
        Kind.PILL -> R.drawable.bg_pill
        Kind.CHIP -> R.drawable.bg_chip
        Kind.MORE_ICON -> R.drawable.bg_more_icon
        Kind.ROW, Kind.CELL, Kind.CELL_BAR -> R.drawable.bg_row
    }

    /**
     * Glass for [view] when the look is on, otherwise its classic background ([classic], which a shape that
     * looks different when classic, such as the pill buttons in a popup, can name). The view keeps its padding.
     */
    fun background(view: View, kind: Kind, classic: Int = classicRes(kind)) {
        val l = view.paddingLeft
        val t = view.paddingTop
        val r = view.paddingRight
        val b = view.paddingBottom
        if (on) view.background = drawable(view.context, kind) else view.setBackgroundResource(classic)
        view.setPadding(l, t, r, b)
    }

    /**
     * Room inside [row] for all that a selected round button in it draws past its own edge: its glow and shadow
     * ([GlassBakes.pad]) and its growth ([grow], of a button [buttonPx] across). A row that fades as a whole is drawn
     * into a picture exactly its own size, which would cut those off flat at its edge for as long as it fades. The
     * row grows by that room at its top (and, with [sides], at both ends) and its padding grows by the same, so
     * nothing in it moves. Once per row, when it's built; the same in both looks.
     */
    fun roomForFocus(row: View, buttonPx: Int, grow: Float, sides: Boolean) {
        val lp = row.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val density = row.resources.displayMetrics.density
        val room = GlassBakes.pad(Kind.CIRCLE, focused = true, density = density) + ceil(buttonPx * (grow - 1f) / 2f).toInt()
        val side = if (sides) room else 0
        lp.topMargin -= room
        lp.leftMargin -= side
        lp.rightMargin -= side
        row.layoutParams = lp
        row.setPadding(row.paddingLeft + side, row.paddingTop + room, row.paddingRight + side, row.paddingBottom)
    }

    /**
     * The surface [background] would give, for a view that draws it itself ([DrawnSpan]: a window that changes width
     * in one motion): glass when the look is on, otherwise the classic one ([classic]).
     */
    fun surface(context: Context, kind: Kind, classic: Int = classicRes(kind)): Drawable =
        if (on) drawable(context, kind) else requireNotNull(context.getDrawable(classic))

    /**
     * The corner radius, px, a [kind] surface [w] x [h] px has in the look now on ([glass]): the glass shape's own
     * ([GlassBakes.radius]) or the classic drawable's (res/drawable/bg_*.xml, whose corners are the radius_* dimens read
     * here: panels and popups 15 dp, pills 20 dp, chips 5 dp, rows 9 dp; circles round; never more than half the
     * shorter side). For something drawn to follow a surface's corners (a header's picture inside its card).
     */
    fun cornerRadius(context: Context, kind: Kind, w: Int, h: Int, glass: Boolean = on): Float {
        val res = context.resources
        if (glass) return GlassBakes.radius(kind, w, h, res.displayMetrics.density)
        val half = minOf(w, h) / 2f
        val classic = when (kind) {
            Kind.PANEL -> R.dimen.radius_panel
            Kind.POPUP, Kind.DIALOG_POPUP -> R.dimen.radius_popup
            Kind.PILL -> R.dimen.radius_pill
            Kind.CHIP -> R.dimen.radius_chip
            Kind.ROW, Kind.CELL, Kind.CELL_BAR -> R.dimen.radius_row
            Kind.CIRCLE, Kind.MORE_ICON -> return half
        }
        return minOf(res.getDimension(classic), half)
    }

    /**
     * A new glass background of this shape, whatever the setting. In the player (and over full-screen Live TV) it
     * shows the blurred film; rows have no film of their own. On the app's own screens it's a surface in the page in
     * the one browsing style ([GlassStyle]): the tint, hairline, highlight and shadow over whatever is behind it, as
     * it is (the wallpaper, a picture), with nothing copied or blurred for it.
     */
    fun drawable(context: Context, kind: Kind): Drawable =
        if (inPlayer(context)) GlassDrawable(context, kind, if (isRow(kind)) null else backdropFor(context))
        else GlassDrawable(context, kind, null, calm = true)

    /**
     * Glass for a window over the page (the settings window, a hold window, a small dialog, a dropdown's list, a
     * notice) when the look is on, otherwise its classic background: as [background], but on the app's own screens
     * the window shows what is really behind it, softly blurred ([PageGlass]: one shared small picture, copied when
     * the window appears and when the page under it changes). A window that is shown again later by making its view
     * visible is copied for again by itself; one shown some other way calls [appeared].
     */
    fun overlay(view: View, kind: Kind, classic: Int = classicRes(kind)) {
        val l = view.paddingLeft
        val t = view.paddingTop
        val r = view.paddingRight
        val b = view.paddingBottom
        if (on) view.background = overlayDrawable(view.context, kind) else view.setBackgroundResource(classic)
        view.setPadding(l, t, r, b)
    }

    /** The glass of [overlay], whatever the setting. */
    fun overlayDrawable(context: Context, kind: Kind): GlassDrawable {
        if (inPlayer(context)) return GlassDrawable(context, kind, if (isRow(kind)) null else backdropFor(context))
        val behind = pageGlassFor(context)
        val glass = GlassDrawable(context, kind, behind, calm = true, overlay = true)
        behind?.register(glass)
        behind?.onGlassAppeared()
        return glass
    }

    /** A window over the page ([overlay]) is shown again without its view having been hidden: copied for afresh. */
    fun appeared(view: View) {
        if (on && !inPlayer(view.context)) pageGlassFor(view.context)?.onGlassAppeared()
    }

    /**
     * The glass outline alone (the hairline and the top highlight of [kind]'s shape, nothing inside), for drawing over
     * what a surface holds: a banner's or a card's picture. Null with the look off: classic surfaces have their own.
     */
    fun edge(context: Context, kind: Kind): Drawable? =
        if (on) GlassDrawable(context, kind, null, calm = true, edgeOnly = true) else null

    /** [edge] as [view]'s foreground (over its children), or none with the look off. */
    fun edge(view: View, kind: Kind) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M || view is android.widget.FrameLayout) {
            view.foreground = edge(view.context, kind)
        }
    }

    private fun pageGlassFor(context: Context): GlassSource? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return (c as? GlassSource.Host)?.pageGlass
            c = c.baseContext
        }
        return null
    }

    /**
     * Whether [context] is the player's, or a screen whose glass shows a live picture just now (Home's full-screen
     * live controls, [GlassSource.Host.glassShowsFilm]): glass made there is dimmed by the screen dimmer. Home's glass
     * over the wallpaper never is.
     */
    fun inPlayer(context: Context): Boolean {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c is GlassBackdrop.Host || (c as? GlassSource.Host)?.glassShowsFilm == true
            c = c.baseContext
        }
        return false
    }

    /**
     * The blurred film of the player this view belongs to, the blurred wallpaper of the app's own screens
     * ([GlassSource.Host]), or null where there's neither: glass is then dark.
     */
    fun backdropFor(context: Context): GlassSource? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return (c as? GlassBackdrop.Host)?.glassBackdrop ?: (c as? GlassSource.Host)?.glassSource
            c = c.baseContext
        }
        return null
    }

    /** The switch shown for an on/off setting in place of the word. */
    fun toggle(context: Context, on: Boolean): Drawable = GlassToggleDrawable(context, on)

    // The first glass look's resources, until every caller uses the drawables above.
    val circle get() = if (on) R.drawable.glass_circle else R.drawable.bg_circle
    val pill get() = if (on) R.drawable.glass_pill else R.drawable.bg_pill
    val chip get() = if (on) R.drawable.glass_chip else R.drawable.bg_chip
    val moreIcon get() = if (on) R.drawable.glass_more_icon else R.drawable.bg_more_icon
    val row get() = if (on) R.drawable.glass_row else R.drawable.bg_row
    val panel get() = if (on) R.drawable.glass_panel else R.drawable.bg_panel
    val popup get() = if (on) R.drawable.glass_popup else R.drawable.bg_popup
    val previewEdge get() = if (on) R.drawable.glass_preview_fg else R.drawable.fg_preview
    /** The skip preview's note ("Preview not available"): the card's face with [previewEdge]. */
    val previewNote get() = if (on) R.drawable.glass_preview_note else R.drawable.bg_preview_note
}
