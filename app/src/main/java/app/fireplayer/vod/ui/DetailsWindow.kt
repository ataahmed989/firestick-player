package app.fireplayer.vod.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import app.fireplayer.R
import app.fireplayer.ui.DetailsLook
import app.fireplayer.ui.ImageLoader
import app.fireplayer.ui.ImageTarget
import app.fireplayer.ui.SidePanel
import app.fireplayer.vod.TitleDetails
import kotlin.math.roundToInt

/**
 * The title page's Details window (⋯ › Details), in the app's wide settings window ([SidePanel]): the same size, place,
 * fonts and tinted frosted glass. The title with its parental-rating badge, and the line under it (its years and
 * length; a show's seasons and episode length), stay at the top; under a hairline the body scrolls: Story, Starring
 * (small round portraits with names, three to a row), Facts, then Production, labels and values in two aligned
 * columns ([DetailsLook]). Only what's known is shown, and a section with nothing is left out ([TitleRules]).
 *
 * Nothing in it can be selected, so nothing lights up: the window keeps focus as it opens and Up and Down glide the
 * body a few lines a press, on and on while held ([SidePanel.scrollsWithArrows]), with a slim bar and "↕ Scroll" while
 * it's longer than the window ([SidePanel.scrollHint]). Back closes it, and the title page puts focus back on ⋯. The
 * portraits are decoded small and let go as the window closes.
 */
internal fun SidePanel.titleDetails(d: TitleDetails) {
    val c = root.context
    val density = c.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).roundToInt()
    fun params(top: Int = 0, bottom: Int = 0, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    // ---- Pinned: the title and its badge, the line under it, a hairline ----
    setTitle(DetailsLook.titled(c, d.title.name, TitleRules.ratingBadge(d)))
    val line = TitleRules.detailsLine(d)
    // Right under the title (pulled any closer, its tops were cut off by the title's box on the TV).
    if (line.isNotEmpty()) pinned(DetailsLook.line(c, line).apply { layoutParams = params() })
    pinned(DetailsLook.hairline(c).apply {
        layoutParams = params(top = if (line.isNotEmpty()) 12 else 2, bottom = 4, height = DetailsLook.hairlinePx(c))
    })

    // ---- The body ----
    // In line with the title's words (the slim bar keeps its own room on the right, [SidePanel.scrollHint]).
    val body = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(8), 0, dp(10))
    }
    fun section(name: String) {
        body.addView(DetailsLook.heading(c, name), params(top = if (body.childCount == 0) 0 else SECTION_GAP_DP, bottom = 6))
    }

    val story = TitleRules.story(d)
    if (story.isNotEmpty()) {
        section("Story")
        body.addView(DetailsLook.body(c, story), params())
    }

    val cast = TitleRules.detailsCast(d)
    if (cast.isNotEmpty()) {
        section("Starring")
        val photos = ArrayList<CastPhoto>(cast.size)
        cast.chunked(COLUMNS).forEachIndexed { r, people ->
            body.addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                for (i in 0 until COLUMNS) {
                    val cell = people.getOrNull(i)?.let { castCell(c, it, dp(PHOTO_DP), photos) } ?: View(c)
                    addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
            }, params(top = if (r == 0) 4 else 12))
        }
        // The loader fetches the newest request first: asked last to first, the first row's portraits come first.
        for (i in photos.indices.reversed()) photos[i].show()
    }

    val facts = TitleRules.detailsFacts(d)
    val production = TitleRules.detailsProduction(d)
    // One label column for both, so every value starts in one line.
    val labelWidth = DetailsLook.labelWidth(c, (facts + production).map { it.first })
    if (facts.isNotEmpty()) {
        section("Facts")
        body.addView(DetailsLook.facts(c, facts, labelWidth), params())
    }
    if (production.isNotEmpty()) {
        // As wide as the pinned hairline and the foot's: from the body's words to the slim bar's room.
        if (body.childCount > 0) body.addView(DetailsLook.hairline(c), params(top = SECTION_GAP_DP, height = DetailsLook.hairlinePx(c)))
        section("Production")
        body.addView(DetailsLook.facts(c, production, labelWidth), params())
    }
    if (body.childCount > 0) view(body, top = 0, bottom = 0)

    scrollsWithArrows()
    scrollHint()
}

/** One of the cast: a small round portrait (its initials on the faint circle when TMDB has none) and the name under it. */
private fun castCell(c: Context, person: TitleRules.Person, photoPx: Int, photos: MutableList<CastPhoto>): View =
    LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val photo = CastPhoto(c, person.picture, TitleRules.initials(person.name), photoPx)
        photos += photo
        addView(photo, LinearLayout.LayoutParams(photoPx, photoPx))
        addView(TextView(c).apply {
            text = person.name
            typeface = ResourcesCompat.getFont(c, R.font.figtree_600)
            setTextColor(ContextCompat.getColor(c, R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, NAME_SP)
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (6 * c.resources.displayMetrics.density).roundToInt()
        })
    }

/**
 * A cast member's portrait in a circle, decoded at its own small size (the face kept: the crop sits high on the
 * portrait). Let go when the window closes: a download not started yet isn't made, and the decoded picture leaves
 * memory, so nothing of the window stays.
 */
private class CastPhoto(context: Context, private val url: String?, private val initials: String, private val sizePx: Int) :
    AppCompatImageView(context), ImageTarget {

    override var imageKey: String? = null

    /** Only for one without a portrait. */
    private val letters: Paint? = if (url == null && initials.isNotEmpty()) Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.figtree_600)
        color = ContextCompat.getColor(context, R.color.text_muted)
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, context.resources.displayMetrics)
    } else null

    init {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x26FFFFFF)
        }
        // The circle's outline clips the picture.
        clipToOutline = true
        // The name under it says who it is.
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun show() {
        if (url != null) ImageLoader.load(url, this, sizePx, sizePx, cropY = FACE_Y)
    }

    override fun setImage(bitmap: Bitmap?, fromMemory: Boolean) {
        setImageBitmap(bitmap)
        if (bitmap != null && !fromMemory) {
            alpha = 0f
            animate().alpha(1f).setDuration(ImageLoader.FADE_MS).start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = letters ?: return
        canvas.drawText(initials, width / 2f, height / 2f - (p.ascent() + p.descent()) / 2f, p)
    }

    override fun onDetachedFromWindow() {
        val key = imageKey
        animate().cancel()
        ImageLoader.cancel(this)
        key?.let { ImageLoader.drop(it) }
        super.onDetachedFromWindow()
    }
}

/** Three portraits to a row. */
private const val COLUMNS = 3
/** A portrait's size: small, about a third of a column. */
private const val PHOTO_DP = 56
/** A name under its portrait. */
private const val NAME_SP = 12f
/** Where a portrait's square is cut from its 2:3 picture, from the top (0) to the bottom (1): faces sit high. */
private const val FACE_Y = 0.22f
/** Above each section after the first. */
private const val SECTION_GAP_DP = 18
