package app.fireplayer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import app.fireplayer.R
import app.fireplayer.shell.TextStyle
import app.fireplayer.shell.Tokens

/**
 * The app's shared controls outside the settings windows: pill buttons, round buttons, info chips and the flat chips
 * of a moving row. Each takes its surface from a [Glass.Kind] (glass, or the classic drawable) and its colours, faces
 * and focus growth from [Tokens]; sizes and text styles come from the caller's look holder (Films & Shows: VodSizes).
 * So a change to a kind, a colour or the focus growth changes every page that shows these. Focused: a white body
 * with blue words and icons (design-layout's focus rule).
 */
object Buttons {

    /**
     * A pill's sizes, dp, and its words' style. [primary]: the page's main action (Play on a title's page), which reads
     * as the main one before it has focus too ([ActionPill.primary]). [progressSide]: how far the progress line stays
     * from the pill's ends (a pill with round ends keeps it in its straight part); unset, the pill's padding.
     */
    class PillSpec(
        val height: Float,
        val pad: Float,
        val text: TextStyle,
        val icon: Float = 0f,
        val iconGap: Float = 0f,
        val progressH: Float = 0f,
        val progressBottom: Float = 0f,
        val primary: Boolean = false,
        val progressSide: Float = -1f,
    )

    // The main action's pill in the glass look, while it isn't focused: the same glass with a faint light inside it and
    // a slightly stronger hairline round it (GlassStyle's own is 0x26 white, half a dp). Focused it is the white body
    // with blue words, as every button; the classic look keeps its one solid surface.
    const val PRIMARY_FILL = 0x1FFFFFFF
    const val PRIMARY_HAIRLINE = 0x57FFFFFF
    const val PRIMARY_HAIRLINE_DP = 0.75f

    /** The quieter part of a button's words (the count in "Sources · 18"): this share of the words' own strength. */
    const val QUIET_ALPHA = 0.6f

    /**
     * [text] with what follows [from] quieter ([QUIET_ALPHA] of the words' own colour, so it stays quieter in the
     * focused blue too). [from] outside the words: [text] as it is.
     */
    fun quietTail(text: String, from: Int): CharSequence {
        if (from <= 0 || from >= text.length) return text
        return SpannableString(text).apply { setSpan(Quiet(QUIET_ALPHA), from, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    /** Draws its part of a line at [share] of the line's own colour, whatever that is now. */
    private class Quiet(private val share: Float) : CharacterStyle(), UpdateAppearance {
        override fun updateDrawState(tp: TextPaint) {
            tp.alpha = (tp.alpha * share).toInt()
        }
    }

    /** The blue-on-white text and icon colours of a focusable button (Live TV's controls; the title page's). */
    fun buttonColors(context: Context): ColorStateList =
        requireNotNull(ContextCompat.getColorStateList(context, R.color.sel_live_button))

    /** An on/off button's: blue while on (selected) too, like Live TV's favourite star. */
    fun toggleColors(context: Context): ColorStateList =
        requireNotNull(ContextCompat.getColorStateList(context, R.color.sel_toggle_button))

    /**
     * A plain pill of words ("Try again"): [Glass.Kind.PILL] (set by [applyLook]), blue words when focused, padded
     * [padX] x [padY] dp. Focusable.
     */
    fun textPill(context: Context, t: Tokens, label: String, style: TextStyle, padX: Float, padY: Float): TextView =
        t.label(context, style).apply {
            text = label
            isFocusable = true
            gravity = Gravity.CENTER
            setPadding(t.dp(padX), t.dp(padY), t.dp(padX), t.dp(padY))
            setTextColor(buttonColors(context))
            Glass.background(this, Glass.Kind.PILL)
        }

    /**
     * A pill button: an optional icon, its words and, for one that resumes, a thin progress line inside its bottom
     * ([progress]). Grows [Tokens.GROW_PILL] when focused ([Tokens.focusEffect]). Its words change in place; the row it
     * sits in lays out again only when they change width. A [primary] one is brighter glass while it isn't focused.
     */
    @SuppressLint("ViewConstructor")
    class ActionPill(context: Context, private val t: Tokens, private val spec: PillSpec) : LinearLayout(context) {
        val icon = ImageView(context).apply {
            imageTintList = buttonColors(context)
            isDuplicateParentStateEnabled = true
            visibility = GONE
        }
        val label: TextView = t.label(context, spec.text).apply {
            setTextColor(buttonColors(context))
            isDuplicateParentStateEnabled = true
        }

        /** 0..1 shows the line (Resume); null hides it. */
        var progress: Float? = null
            set(v) {
                if (field == v) return
                field = v
                invalidate()
            }

        /**
         * The page's main action ([PillSpec.primary]): in the glass look, a faint light inside the pill and a slightly
         * stronger hairline round it while it isn't focused. Off for a main action that can't be pressed just now.
         */
        var primary: Boolean = spec.primary
            set(v) {
                if (field == v) return
                field = v
                invalidate()
            }

        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent }
        private val line = RectF()
        // Made only for a pill that can be the main action.
        private val primaryFill = if (spec.primary) Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PRIMARY_FILL } else null
        private val primaryRim = if (spec.primary) Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = PRIMARY_HAIRLINE
            strokeWidth = t.dpf(PRIMARY_HAIRLINE_DP).coerceAtLeast(1f)
        } else null
        private val body = RectF()

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            // OK is read by the page's key listener (on release): never View's own click.
            isClickable = false
            setWillNotDraw(false)
            val pad = t.dp(spec.pad)
            setPadding(pad, 0, pad, 0)
            if (spec.icon > 0f) {
                addView(icon, LayoutParams(t.dp(spec.icon), t.dp(spec.icon)).apply { rightMargin = t.dp(spec.iconGap) })
            }
            addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            applyLook()
        }

        fun set(text: CharSequence, iconRes: Int?) {
            if (label.text.toString() != text.toString()) label.text = text
            if (iconRes != null && spec.icon > 0f) {
                icon.setImageResource(iconRes)
                icon.visibility = VISIBLE
            } else {
                icon.visibility = GONE
            }
            contentDescription = text
        }

        fun applyLook() = Glass.background(this, Glass.Kind.PILL)

        /** Over the pill's glass, under its icon and words: the main action's light and hairline. */
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val fill = primaryFill ?: return
            val rim = primaryRim ?: return
            if (!primary || isFocused || !Glass.on || width <= 0 || height <= 0) return
            body.set(0f, 0f, width.toFloat(), height.toFloat())
            val r = minOf(width, height) / 2f
            canvas.drawRoundRect(body, r, r, fill)
            val half = rim.strokeWidth / 2f
            body.inset(half, half)
            canvas.drawRoundRect(body, r - half, r - half, rim)
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            val p = progress ?: return
            if (spec.progressH <= 0f) return
            val set = spec.progressSide >= 0f
            val left = if (set) t.dpf(spec.progressSide) else paddingLeft.toFloat()
            val right = width - if (set) t.dpf(spec.progressSide) else paddingRight.toFloat()
            if (right <= left) return
            val h = t.dpf(spec.progressH)
            val bottom = height - t.dpf(spec.progressBottom)
            line.set(left, bottom - h, right, bottom)
            val r = h / 2f
            trackPaint.color = if (isFocused) t.buttonTrackFocused else t.buttonTrack
            canvas.drawRoundRect(line, r, r, trackPaint)
            val f = p.coerceIn(0f, 1f)
            if (f > 0f) {
                line.right = line.left + (line.right - line.left) * f
                canvas.drawRoundRect(line, r, r, barPaint)
            }
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            if (progress != null || primary) invalidate()
        }
    }

    /**
     * A round button and its name under it, shown only while it's focused (Live TV's ButtonLabel): [button] is the
     * focusable circle ([Glass.Kind.CIRCLE]), growing [Tokens.GROW_CIRCLE]; [label] hangs below without widening the
     * row. [toggle] buttons stay blue while on ([setOn]).
     */
    @SuppressLint("ViewConstructor")
    class RoundButton(context: Context, t: Tokens, sizeDp: Float, iconDp: Float, toggle: Boolean = false) : LinearLayout(context) {
        val button = ImageView(context).apply {
            isFocusable = true
            isClickable = false
            scaleType = ImageView.ScaleType.FIT_CENTER
            imageTintList = if (toggle) toggleColors(context) else buttonColors(context)
            val pad = t.dp((sizeDp - iconDp) / 2f)
            setPadding(pad, pad, pad, pad)
        }
        val label = TextView(context, null, 0, R.style.ButtonLabel)

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            addView(button, LayoutParams(t.dp(sizeDp), t.dp(sizeDp)))
            // The style's layout values don't apply to a view made in code: its gap and hanging sides, from the same
            // dimens the style reads (res/values/dimens.xml).
            val side = resources.getDimensionPixelSize(R.dimen.button_label_side)
            addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.button_label_gap)
                leftMargin = side
                rightMargin = side
            })
            Tokens.focusEffect(button, Tokens.GROW_CIRCLE, label)
            applyLook()
        }

        fun set(iconRes: Int, name: String) {
            button.setImageResource(iconRes)
            button.contentDescription = name
            if (label.text.toString() != name) label.text = name
        }

        /** A toggle's state: blue (selected) while on. */
        fun setOn(on: Boolean) {
            button.isSelected = on
        }

        fun applyLook() = Glass.background(button, Glass.Kind.CIRCLE)
    }

    /** A small chip of words beside a line of text (the title page's callout): [Glass.Kind.CHIP], not focusable. */
    fun infoChip(context: Context, t: Tokens, style: TextStyle, padX: Float): TextView = t.label(context, style).apply {
        gravity = Gravity.CENTER
        setPadding(t.dp(padX), 0, t.dp(padX), 0)
        Glass.background(this, Glass.Kind.CHIP)
    }

    /**
     * A flat chip for a row that moves (genres, a show's seasons): a flat pill in both looks (glass in a moving strip
     * would show the wrong part of the wallpaper), white with blue words when focused; [flatChipBackground] gives its
     * surface (set again when the look changes).
     */
    fun flatChip(context: Context, t: Tokens, style: TextStyle, padX: Float): TextView = t.label(context, style).apply {
        gravity = Gravity.CENTER
        val pad = t.dp(padX)
        setPadding(pad, 0, pad, 0)
        setTextColor(buttonColors(context))
    }

    /**
     * A flat chip's surface, [radius] px: focused white; selected (the season shown) the accent at [selectedAlpha] with
     * an accent ring [selectedRim] dp wide; otherwise the glass look's faint fill and [rim] dp rim, or the classic row
     * fill.
     */
    fun flatChipBackground(t: Tokens, radius: Float, rim: Float, selectedAlpha: Int, selectedRim: Float): Drawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = radius; setColor(t.focusFill) })
            addState(intArrayOf(android.R.attr.state_selected), GradientDrawable().apply {
                cornerRadius = radius
                setColor(ColorUtils.setAlphaComponent(t.accent, selectedAlpha))
                setStroke(t.dp(selectedRim).coerceAtLeast(2), t.accent)
            })
            addState(intArrayOf(), GradientDrawable().apply {
                cornerRadius = radius
                if (Glass.on) {
                    setColor(t.groupFillGlass)
                    setStroke(t.dp(rim).coerceAtLeast(1), t.groupRimGlass)
                } else {
                    setColor(t.rowFill)
                }
            })
        }

    /** Whether [v] is shown and can take focus now (a button closed up or dimmed is passed by). */
    fun canFocus(v: View): Boolean = v.visibility == View.VISIBLE && v.isFocusable
}
