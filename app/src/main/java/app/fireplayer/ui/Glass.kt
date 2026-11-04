package app.fireplayer.ui

import app.fireplayer.R

/**
 * The optional glass look (Settings, Look, Glass look): buttons, chips, rows and windows become see-through
 * glass with a light rim, while the layout, icons, colours and selected look stay as they are.
 * The date, time and seek line never get glass. Fire OS can't blur the film behind a view cheaply,
 * so the glass is drawn as tinted see-through layers rather than a live blur.
 */
object Glass {
    @Volatile var on = false

    val circle get() = if (on) R.drawable.glass_circle else R.drawable.bg_circle
    val pill get() = if (on) R.drawable.glass_pill else R.drawable.bg_pill
    val chip get() = if (on) R.drawable.glass_chip else R.drawable.bg_chip
    val moreIcon get() = if (on) R.drawable.glass_more_icon else R.drawable.bg_more_icon
    val tray get() = if (on) R.drawable.glass_tray else 0
    val row get() = if (on) R.drawable.glass_row else R.drawable.bg_row
    val panel get() = if (on) R.drawable.glass_panel else R.drawable.bg_panel
    val popup get() = if (on) R.drawable.glass_popup else R.drawable.bg_popup
    val previewEdge get() = if (on) R.drawable.glass_preview_fg else R.drawable.fg_preview
}
