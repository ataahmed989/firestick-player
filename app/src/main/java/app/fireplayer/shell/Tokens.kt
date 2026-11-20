package app.fireplayer.shell

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import app.fireplayer.R

/**
 * The shared look of the app's own screens (shell, guide, live controls, Films & Shows), taken from the original
 * player: Figtree, off-white text, blue #3D8BFF accent, dark panels #0B0E12 and rows #1A1E24 (res/values/colors.xml).
 * Classic and glass use these same sizes; only surface materials differ ([app.fireplayer.ui.Glass]). Films & Shows'
 * own sizes and text styles are in VodSizes, which reads its colours from here.
 *
 * Focus rule (design-layout): a focused item is a white body with blue text and icons; the playing channel stays
 * blue when it isn't focused, so "playing" and "focused" never look the same.
 */
class Tokens(context: Context) {
    val density = context.resources.displayMetrics.density
    fun dp(v: Float): Int = (v * density + 0.5f).toInt()
    fun dp(v: Int): Int = dp(v.toFloat())
    fun dpf(v: Float): Float = v * density

    val accent = ContextCompat.getColor(context, R.color.accent)
    val text = ContextCompat.getColor(context, R.color.text)
    val textMuted = ContextCompat.getColor(context, R.color.text_muted)
    val focusFill = ContextCompat.getColor(context, R.color.focus_fill)
    val rowFill = ContextCompat.getColor(context, R.color.row_fill)
    val panelFill = ContextCompat.getColor(context, R.color.panel_fill)
    val progressTrack = ContextCompat.getColor(context, R.color.progress_track)
    /** Headings in windows (settings, Home's recent channels) and their line icons. */
    val heading = ContextCompat.getColor(context, R.color.heading)
    val headingIcon = ContextCompat.getColor(context, R.color.heading_icon)
    /** A flat surface in the glass look (grouped rows' card; chips and See all cards in a moving row) and its rim. */
    val groupFillGlass = ContextCompat.getColor(context, R.color.group_fill_glass)
    val groupRimGlass = ContextCompat.getColor(context, R.color.group_rim_glass)
    /** A button's progress line (Resume): its track, plain and on the white selected button. */
    val buttonTrack = ContextCompat.getColor(context, R.color.button_track)
    val buttonTrackFocused = ContextCompat.getColor(context, R.color.button_track_focused)

    val regular: Typeface = ResourcesCompat.getFont(context, R.font.figtree_500) ?: Typeface.DEFAULT
    val semibold: Typeface = ResourcesCompat.getFont(context, R.font.figtree_600) ?: Typeface.DEFAULT_BOLD
    val bold: Typeface = ResourcesCompat.getFont(context, R.font.figtree_700) ?: Typeface.DEFAULT_BOLD

    fun label(context: Context, sizeSp: Float, face: Typeface = regular, color: Int = text): TextView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = face
        setTextColor(color)
        includeFontPadding = false
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    /** A one-line label in [style] (as [label] above); a style's line gap is added between its lines. */
    fun label(context: Context, style: TextStyle): TextView = label(context, style.sp, faceOf(style.weight), colorOf(style)).apply {
        if (style.lineGap != 0f) setLineSpacing(dpf(style.lineGap), 1f)
    }

    fun faceOf(weight: TextStyle.Weight): Typeface = when (weight) {
        TextStyle.Weight.REGULAR -> regular
        TextStyle.Weight.SEMIBOLD -> semibold
        TextStyle.Weight.BOLD -> bold
    }

    fun colorOf(style: TextStyle): Int = when (style.ink) {
        TextStyle.Ink.TEXT -> text
        TextStyle.Ink.MUTED -> textMuted
        TextStyle.Ink.ACCENT -> accent
        TextStyle.Ink.OWN -> style.color
    }

    companion object {
        /** Animation lengths, from the original player: 140 ms state fades, 180 ms panel slides. */
        const val FADE_MS = 140L
        const val SLIDE_MS = 180L
        /** Focus growth (original player's focusEffect): circles 1.08, pills 1.04. */
        const val GROW_CIRCLE = 1.08f
        const val GROW_PILL = 1.04f

        // ---- Hold windows ---------------------------------------------------------------------------------------
        // A held OK's small window, one shape everywhere (app.fireplayer.live.ui.HoldShape: HoldPopup in Live TV and
        // Films & Shows, FireDialog.hold for a settings window's row): a narrow vertical list beside what was held, as
        // wide as its widest line. Glass, or classic's solid popup; only the selected option is lit.

        /** Its room round the list: the selected option's white sits this far inside the window's edge. */
        const val HOLD_PAD_DP = 6f
        /** Its width: its widest line (the title's or an option's) and its room, within these (and the area). */
        const val HOLD_MIN_W_DP = 150f
        const val HOLD_MAX_W_DP = 240f
        /** An option: this tall, its icon and words this far in from the row's ends, the options this far apart. */
        const val HOLD_ROW_H_DP = 32f
        const val HOLD_ROW_PAD_X_DP = 12f
        const val HOLD_ROW_GAP_DP = 2f
        /** An option's icon (the guide's star) and the room between it and its words. */
        const val HOLD_ICON_DP = 16f
        const val HOLD_ICON_GAP_DP = 10f
        /** The title line: the name held, quieter than the options, in line with their words, up to two lines. */
        val HOLD_TITLE = TextStyle(13f, TextStyle.Weight.BOLD, TextStyle.Ink.MUTED, lineGap = 1f)
        const val HOLD_TITLE_LINES = 2
        const val HOLD_TITLE_PAD_TOP_DP = 6f
        const val HOLD_TITLE_PAD_BOTTOM_DP = 5f
        /** The thin line before an option of another kind (a group's Manage groups), with this room above and below. */
        const val HOLD_LINE_GAP_DP = 3f
        /** Beside what was held, this far from it; a settings row's window keeps this far inside the screen. */
        const val HOLD_GAP_DP = 8f
        const val HOLD_EDGE_DP = 12f
        /** It comes out of the held item's side this far as it fades in, and goes back half that as it fades out. */
        const val HOLD_SLIDE_DP = 8f
        const val HOLD_OPEN_MS = 140L
        const val HOLD_CLOSE_MS = 100L

        // ---- Players --------------------------------------------------------------------------------------------
        // Sizes a layout reads are in res/values/dimens.xml (player_main_button, player_pill_min_width,
        // logo_badge_height); what code draws is here.

        /**
         * The buffering symbol over a live picture ([app.fireplayer.ui.BusyOverlay]): the film player's own turning
         * ring, the same size as there (activity_player.xml's 40 dp) over a full-screen picture.
         */
        const val SPINNER_DP = 40f
        /** How much of that size it has over a miniature. */
        const val SPINNER_MINI_SCALE = 0.68f

        /** Live TV's line with no programme to show: the programme line's blue, the whole way, at this strength. */
        const val NO_PROGRAMME_LINE_ALPHA = 0.45f

        /**
         * A channel logo on its own plate ([app.fireplayer.live.ui.LogoBadge]): the plate is as wide as the fitted logo
         * needs (within these), the logo sits inside with this room around it. Most logos get the neutral dark plate
         * (white and coloured ink stay bright on it, over any picture); dark ink gets the light one
         * ([app.fireplayer.live.ui.LogoPlate.light] decides).
         */
        const val LOGO_BADGE_PAD_X_DP = 9f
        const val LOGO_BADGE_PAD_Y_DP = 6f
        const val LOGO_BADGE_MIN_WIDTH_DP = 46f
        const val LOGO_BADGE_MAX_WIDTH_DP = 104f
        const val LOGO_BADGE_RADIUS_DP = 9f
        const val LOGO_BADGE_PLATE = 0xD92A2F36.toInt()
        const val LOGO_BADGE_PLATE_LIGHT = 0xEBE6E8EB.toInt()
        const val LOGO_BADGE_RIM = 0x33FFFFFF

        private val FOCUS_EASE = DecelerateInterpolator(1.4f)

        /**
         * The app's focus growth (the original player's focusEffect): [button] grows to [scale] while it has focus,
         * eased over [FADE_MS]; [label] (a round button's name under it) fades in and grows with it, and hides again
         * when focus leaves. Live TV's controls and the title page's buttons use it. [also] runs on each change.
         */
        fun focusEffect(button: View, scale: Float, label: View?, also: ((has: Boolean) -> Unit)? = null) {
            button.setOnFocusChangeListener { v, hasFocus ->
                val s = if (hasFocus) scale else 1f
                v.animate().scaleX(s).scaleY(s).setDuration(FADE_MS).setInterpolator(FOCUS_EASE).start()
                if (label != null) {
                    label.pivotX = v.left + v.width / 2f - label.left
                    label.pivotY = v.top + v.height / 2f - label.top
                    if (hasFocus) label.visibility = View.VISIBLE
                    label.animate().alpha(if (hasFocus) 1f else 0f).scaleX(s).scaleY(s).setDuration(FADE_MS).setInterpolator(FOCUS_EASE)
                        .withEndAction { if (!v.isFocused) label.visibility = View.INVISIBLE }
                        .start()
                }
                also?.invoke(hasFocus)
            }
        }

        /**
         * [button] has just taken focus inside a row that is itself fading in (the player's rows swapping): its
         * selected look is put at its end at once ([scale], its surface's selected state, [label] shown), so only the
         * row's own motion is seen, not the button changing inside it. A one-off at the key press; moving along a row
         * keeps [focusEffect]'s motion.
         */
        fun settleFocus(button: View, scale: Float, label: View?) {
            button.animate().cancel()
            button.scaleX = scale
            button.scaleY = scale
            button.jumpDrawablesToCurrentState()
            if (label != null) {
                label.animate().cancel()
                label.visibility = View.VISIBLE
                label.alpha = 1f
                label.scaleX = scale
                label.scaleY = scale
            }
        }
    }
}
