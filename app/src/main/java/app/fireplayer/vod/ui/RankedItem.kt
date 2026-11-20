package app.fireplayer.vod.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.widget.FrameLayout
import app.fireplayer.shell.Fonts
import app.fireplayer.shell.Tokens

/**
 * A Top 10 item, Glass Home's ranked design (ShelfPage.drawCard, RANKED): a big solid white Inter ExtraBold number
 * right-aligned in a slot left of the poster, tucked a little behind it, its baseline just above the poster's bottom,
 * "10" narrowed to fit. The number is drawn here before the poster child, so the poster covers its tucked part. The
 * item moves along as a follower; only the poster grows.
 */
@SuppressLint("ViewConstructor")
class RankedItem(context: Context, private val t: Tokens, large: Boolean = false) : FrameLayout(context) {

    val poster = CardView(context, t, CardView.Kind.POSTER, VodSizes.posterW(large), VodSizes.posterH(large))

    private val slot = t.dpf(VodSizes.slot(app.fireplayer.vod.RowStyle.RANKED, large))
    private val posterH = t.dpf(VodSizes.posterH(large))
    private val tuck = t.dpf(VodSizes.RANK_TUCK)
    private val baseSize = posterH * VodSizes.RANK_TEXT
    private val room = posterH * VodSizes.RANK_ROOM + tuck
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = VodSizes.RANK_COLOR
        textAlign = Paint.Align.RIGHT
        letterSpacing = VodSizes.RANK_SPACING
        typeface = Fonts.rank(context, t.bold)
        textSize = baseSize
    }
    private var number = ""

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        addView(poster, LayoutParams(t.dp(VodSizes.posterW(large)), t.dp(VodSizes.posterH(large))).apply { leftMargin = slot.toInt() })
        poster.pivotX = 0f
        poster.pivotY = t.dp(VodSizes.posterH(large)) / 2f
        poster.isDuplicateParentStateEnabled = false
    }

    /** Its rank: the number's size is worked out here, once, not while drawing. */
    fun setRank(rank: Int?) {
        val n = rank?.toString().orEmpty()
        if (n == number) return
        number = n
        paint.textSize = baseSize
        if (n.isNotEmpty()) paint.textSize = RowMath.rankTextSize(baseSize, paint.measureText(n), room)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (number.isEmpty()) return
        val left = poster.left.toFloat()
        canvas.drawText(number, left + tuck, RowMath.rankBaseline(poster.top.toFloat(), posterH), paint)
    }
}
