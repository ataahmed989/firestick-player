package app.fireplayer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.drawable.Drawable
import android.view.View
import app.fireplayer.R

/**
 * The optional glass look (Settings, Look, Glass look): buttons, chips, rows and windows become clear glass
 * showing the blurred film behind them, with a light rim, while the layout, icons, text and colours stay as
 * they are. The date, time and seek line never get glass. Fire OS can't blur what's behind a view, so the
 * player keeps a small blurred copy of the film ([GlassBackdrop]) that each glass shape ([GlassDrawable]) shows.
 */
object Glass {
    @Volatile var on = false

    /**
     * How much every piece of glass is darkened (Screen dimmer, a custom's glass amount): 0 not at all, 0.8 to a
     * fifth of its light. The film inside and the milky glaze dim; the selected look dims half as much, so it
     * still stands out.
     */
    @Volatile var dim = 0f

    /** The shapes that can be glass. Each replaces one classic background ([classicRes]). */
    enum class Kind { PANEL, POPUP, DIALOG_POPUP, CIRCLE, PILL, CHIP, MORE_ICON, ROW }

    /** The background each shape has with the glass look off. */
    fun classicRes(kind: Kind): Int = when (kind) {
        Kind.PANEL -> R.drawable.bg_panel
        Kind.POPUP, Kind.DIALOG_POPUP -> R.drawable.bg_popup
        Kind.CIRCLE -> R.drawable.bg_circle
        Kind.PILL -> R.drawable.bg_pill
        Kind.CHIP -> R.drawable.bg_chip
        Kind.MORE_ICON -> R.drawable.bg_more_icon
        Kind.ROW -> R.drawable.bg_row
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

    /** A new glass background of this shape, whatever the setting. Rows have no film of their own. */
    fun drawable(context: Context, kind: Kind): Drawable =
        GlassDrawable(context, kind, if (kind == Kind.ROW) null else backdropFor(context))

    /** Whether [context] is the player's (Home's glass is never dimmed: it has no film and no dimmer). */
    fun inPlayer(context: Context): Boolean {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c is GlassBackdrop.Host
            c = c.baseContext
        }
        return false
    }

    /** The blurred film of the player this view belongs to, or null where there's none (Home): glass is then dark. */
    fun backdropFor(context: Context): GlassBackdrop? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return (c as? GlassBackdrop.Host)?.glassBackdrop
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
}
