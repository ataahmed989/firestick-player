package app.fireplayer.live.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import app.fireplayer.R
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.BusyOverlay
import app.fireplayer.ui.BusySigns
import app.fireplayer.ui.Glass
import app.fireplayer.vod.ui.VodSizes

/**
 * Multiview's pictures on the screen: a black board under the pages (the controls, the small window, the channel
 * picker and notices draw over it), each picture its own [SurfaceView] with its channel's name at the bottom left, the
 * buffering symbol ([BusyOverlay], when [BusySigns] says) and a plain message when it can't play; the grid's free cell
 * says "Add a channel". Every picture's corners are round ([LiveWidths.MV_RADIUS]): the board's black is drawn over
 * them, anti-aliased, as the wallpaper's rounded hole does round the guide's miniature (a shape made once per size, no
 * clip per frame). The selected picture (or cell) has the app's white focus ring outside it, round with them.
 *
 * Laid out once per change of layout, never per frame: a picture moving to its new place goes there by translation
 * and scale only (its surface follows its view's transforms from Android 7 on), and is laid out there once at the end,
 * untransformed, as the one live picture does (HomeActivity's move). Nothing here takes focus; Multiview hands it the
 * keys' results.
 */
@SuppressLint("ViewConstructor")
class MultiviewBoard(context: Context, private val t: Tokens) : FrameLayout(context) {

    /** One picture. Made the first time it's needed and reused (at most four ever made). */
    inner class Picture : FrameLayout(context) {
        /** The picture's own area: everything in it is cut at its edges (the ring is drawn outside, by [Picture]). */
        private val content = FrameLayout(context)
        val surface = SurfaceView(context)
        private val name = t.label(context, LiveWidths.MV_NAME_SP, t.semibold).apply {
            setPadding(t.dp(LiveWidths.MV_NAME_PAD_X), t.dp(LiveWidths.MV_NAME_PAD_Y), t.dp(LiveWidths.MV_NAME_PAD_X), t.dp(LiveWidths.MV_NAME_PAD_Y))
            background = GradientDrawable().apply {
                setColor(Tokens.LOGO_BADGE_PLATE)
                cornerRadius = t.dpf(LiveWidths.MV_NAME_RADIUS)
            }
        }
        private val message = t.label(context, LiveWidths.MV_MESSAGE_SP, t.semibold).apply {
            maxLines = 3
            gravity = Gravity.CENTER
            setPadding(t.dp(LiveWidths.MV_MESSAGE_PAD_X), 0, t.dp(LiveWidths.MV_MESSAGE_PAD_X), 0)
            setShadowLayer(t.dpf(LiveWidths.MV_MESSAGE_SHADOW), 0f, 0f, LiveWidths.MV_MESSAGE_SHADOW_COLOR)
            visibility = GONE
        }
        private val busy = BusyOverlay(context, t)
        private val signs = BusySigns()
        private val signsCheck = Runnable { signsDue?.invoke() }
        /** Asks the picture's player again when its signs are next due (set by [showWaiting]'s caller). */
        private var signsDue: (() -> Unit)? = null

        /** Where it's laid out (px, the board's coordinates); empty until first placed. */
        internal val laid = RectF()
        /** Where a move started and where it ends. */
        internal val from = RectF()
        internal val to = RectF()
        internal var moving = false
        /** Showing a channel (else free for the next picture added). */
        internal var inUse = false
        /** The video's shape (0: not known yet): the surface fits it inside the picture, black bars round it. */
        private var aspect = 0f
        /** The four corners outside the picture's rounded shape, in the board's black (made again only for a new size). */
        private val corners = Path().apply { fillType = Path.FillType.EVEN_ODD }

        var ringed = false
            set(v) {
                if (field == v) return
                field = v
                invalidate()
            }

        init {
            setWillNotDraw(false)
            isFocusable = false
            content.addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
            content.addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            content.addView(busy, busy.params())
            content.addView(name, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = t.dp(LiveWidths.MV_NAME_INSET)
                bottomMargin = t.dp(LiveWidths.MV_NAME_INSET)
                rightMargin = t.dp(LiveWidths.MV_NAME_INSET)
            })
            addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        fun setName(text: String) {
            if (name.text.toString() != text) name.text = text
            val v = if (text.isEmpty()) GONE else VISIBLE
            if (name.visibility != v) name.visibility = v
        }

        /** A plain message in place of the picture (null: none). */
        fun setMessage(text: String?) {
            if (text == null) {
                if (message.visibility != GONE) message.visibility = GONE
                return
            }
            if (message.text.toString() != text) message.text = text
            message.visibility = VISIBLE
        }

        /**
         * The buffering symbol as the picture's player says ([waiting], [reconnecting], [refused], the line under it);
         * [again] is called when the signs are next due to change by themselves.
         */
        fun showWaiting(waiting: Boolean, reconnecting: Boolean, refused: Boolean, text: String, again: () -> Unit) {
            signsDue = again
            val next = signs.update(waiting, reconnecting, SystemClock.uptimeMillis(), refused)
            removeCallbacks(signsCheck)
            if (next != BusySigns.NONE) postDelayed(signsCheck, next)
            busy.show(signs.spinner, if (signs.text) text else null)
        }

        /** The video's size is known (or changed): the surface takes its shape inside the picture. */
        fun setVideoShape(width: Int, height: Int, pixelRatio: Float) {
            val a = if (width > 0 && height > 0) width * pixelRatio / height else 0f
            if (a == aspect) return
            aspect = a
            fit()
        }

        /** The surface inside the picture: the video's shape, as large as fits, in the middle; the whole picture before that's known. */
        internal fun fit() {
            val w = laid.width()
            val h = laid.height()
            val lp = surface.layoutParams as LayoutParams
            var sw = LayoutParams.MATCH_PARENT
            var sh = LayoutParams.MATCH_PARENT
            if (aspect > 0f && w > 0f && h > 0f) {
                if (w / h > aspect) { sh = h.toInt(); sw = (h * aspect).toInt() } else { sw = w.toInt(); sh = (w / aspect).toInt() }
            }
            if (lp.width != sw || lp.height != sh) {
                lp.width = sw
                lp.height = sh
                surface.layoutParams = lp
            }
            busy.place(w / 2f, h / 2f, 0f)
        }

        /** Nothing left over from the last channel: no name, message, shape or buffering symbol. */
        fun clear() {
            removeCallbacks(signsCheck)
            signsDue = null
            signs.update(false, false, 0L)
            busy.show(false, null)
            setMessage(null)
            setName("")
            aspect = 0f
            ringed = false
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            corners.reset()
            if (w <= 0 || h <= 0) return
            corners.addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
            corners.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, Path.Direction.CW)
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            // Round corners: the board's black over the picture's (and its surface's) square ones.
            canvas.drawPath(corners, cornerPaint)
            // The ring's inside edge follows them.
            if (ringed) ring(canvas, width.toFloat(), height.toFloat(), radius)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            removeCallbacks(signsCheck)
        }
    }

    /** The grid's free cell: + and "Add a channel" on the guide's empty-miniature panel. */
    private inner class AddCell : LinearLayout(context) {
        var ringed = false
            set(v) {
                if (field == v) return
                field = v
                invalidate()
            }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            setWillNotDraw(false)
            isFocusable = false
            val icon = ImageView(context).apply {
                setImageResource(R.drawable.ic_add)
                imageTintList = android.content.res.ColorStateList.valueOf(t.text)
            }
            addView(icon, LayoutParams(t.dp(LiveWidths.MV_ADD_ICON), t.dp(LiveWidths.MV_ADD_ICON)))
            addView(t.label(context, LiveWidths.MV_ADD_SP, t.semibold).apply { text = ADD_TEXT }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = t.dp(LiveWidths.MV_ADD_GAP)
            })
            applyLook()
        }

        fun applyLook() = Glass.background(this, Glass.Kind.PANEL)

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            if (ringed) ring(canvas, width.toFloat(), height.toFloat(), panelRadius)
        }
    }

    private val pictures = ArrayList<Picture>()
    private val addCell = AddCell()
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = VodSizes.RING_COLOR }
    private val ringPx = t.dpf(VodSizes.RING)
    /** The pictures' corners, and the black drawn over theirs (the board's own). */
    private val radius = t.dpf(LiveWidths.MV_RADIUS)
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    /** The "Add" cell's corners: the panel's (Glass.Kind.PANEL), for its ring to hug. */
    private val panelRadius = resources.getDimension(R.dimen.radius_panel)
    private val ringRect = RectF()
    private var mover: ValueAnimator? = null
    /** The pictures of the move under way (read every frame by index: nothing made per frame). */
    private val movingNow = ArrayList<Picture>()
    private val ease = PathInterpolator(0.2f, 0f, 0f, 1f)

    init {
        setBackgroundColor(Color.BLACK)
        // The selected picture's ring is drawn just outside it.
        clipChildren = false
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        visibility = GONE
        addView(addCell, LayoutParams(1, 1, Gravity.TOP or Gravity.START))
        addCell.visibility = GONE
    }

    /** The app's white focus ring hugging the outside of a [w] x [h] area whose corners are [radius] (the cards' ring). */
    private fun ring(canvas: Canvas, w: Float, h: Float, radius: Float) {
        ringPaint.strokeWidth = ringPx
        val half = ringPx / 2f
        ringRect.set(-half, -half, w + half, h + half)
        canvas.drawRoundRect(ringRect, radius + half, radius + half, ringPaint)
    }

    /** A picture to show a channel on: one made before and free, or a new one (never more than four are made). */
    fun take(): Picture {
        val free = pictures.firstOrNull { it.visibility == GONE && !it.inUse }
        val p = free ?: Picture().also {
            pictures += it
            addView(it, indexOfChild(addCell), LayoutParams(1, 1, Gravity.TOP or Gravity.START))
        }
        p.inUse = true
        p.clear()
        p.laid.setEmpty()
        p.visibility = INVISIBLE
        return p
    }

    /** [p] is free again (its picture removed): hidden, its surface let go of by Android. */
    fun give(p: Picture) {
        p.inUse = false
        p.moving = false
        p.clear()
        p.visibility = GONE
        resetTransform(p)
    }

    /** The ring on picture [index] of [order], or on the "Add" cell when [index] is past them. */
    fun select(order: List<Picture>, index: Int) {
        for ((i, p) in order.withIndex()) p.ringed = i == index
        addCell.ringed = index >= order.size && addCell.visibility == VISIBLE
    }

    /**
     * The pictures in [order] go to [layout] (dp), the "Add" cell too when it has one. [animate]: pictures already on
     * screen move there (translation and scale), new ones appear in place.
     */
    fun place(order: List<Picture>, layout: MultiviewLayout.Layout, animate: Boolean) {
        val d = t.density
        val running = mover
        mover = null
        running?.cancel()
        var anyMoving = false
        val canMove = animate && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
        for ((i, p) in order.withIndex()) {
            val b = layout.tiles.getOrNull(i) ?: continue
            p.to.set(b.left * d, b.top * d, b.right * d, b.bottom * d)
            // Where it shows now (mid-move too): laid out there, moved by its transform.
            p.from.set(p.laid.left + p.translationX, p.laid.top + p.translationY,
                p.laid.left + p.translationX + p.laid.width() * p.scaleX, p.laid.top + p.translationY + p.laid.height() * p.scaleY)
            p.moving = canMove && !p.laid.isEmpty && p.visibility == VISIBLE && !near(p.from, p.to)
            if (p.moving) anyMoving = true else settle(p)
        }
        val add = layout.add
        if (add == null) {
            addCell.visibility = GONE
            addCell.ringed = false
        } else {
            val lp = addCell.layoutParams as LayoutParams
            lp.leftMargin = Math.round(add.left * d)
            lp.topMargin = Math.round(add.top * d)
            lp.width = Math.round(add.width * d)
            lp.height = Math.round(add.height * d)
            addCell.layoutParams = lp
            if (addCell.visibility != VISIBLE) {
                addCell.visibility = VISIBLE
                // It fades in once the pictures have made room (alpha only).
                addCell.alpha = 0f
                addCell.animate().alpha(1f).setStartDelay(if (anyMoving) LiveWidths.MV_MOVE_MS / 2 else 0L)
                    .setDuration(Tokens.FADE_MS).start()
            }
        }
        movingNow.clear()
        if (!anyMoving) return
        for (p in order) {
            if (!p.moving) continue
            p.pivotX = 0f
            p.pivotY = 0f
            movingNow += p
        }
        val move = ValueAnimator.ofFloat(0f, 1f)
        move.duration = LiveWidths.MV_MOVE_MS
        move.interpolator = ease
        move.addUpdateListener(moveFrame)
        move.addListener(moveEnd)
        mover = move
        moveFrames(0f)
        move.start()
    }

    private val moveFrame = ValueAnimator.AnimatorUpdateListener { moveFrames(it.animatedFraction) }
    private val moveEnd = object : AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: Animator) {
            // Cancelled for another move: that one carries on from where this one stood.
            if (mover !== animation) return
            mover = null
            for (i in 0 until movingNow.size) settle(movingNow[i])
            movingNow.clear()
        }
    }

    private fun moveFrames(f: Float) {
        for (i in 0 until movingNow.size) {
            val p = movingNow[i]
            if (p.moving) frame(p, f)
        }
    }

    private fun near(a: RectF, b: RectF): Boolean =
        Math.abs(a.left - b.left) < 0.5f && Math.abs(a.top - b.top) < 0.5f && Math.abs(a.width() - b.width()) < 0.5f && Math.abs(a.height() - b.height()) < 0.5f

    /** One frame of [p]'s move, [f] of the way: its transform from where it's laid out. Nothing made, nothing laid out. */
    private fun frame(p: Picture, f: Float) {
        val l = p.from.left + (p.to.left - p.from.left) * f
        val tp = p.from.top + (p.to.top - p.from.top) * f
        val w = p.from.width() + (p.to.width() - p.from.width()) * f
        val h = p.from.height() + (p.to.height() - p.from.height()) * f
        p.translationX = l - p.laid.left
        p.translationY = tp - p.laid.top
        p.scaleX = if (p.laid.width() > 0f) w / p.laid.width() else 1f
        p.scaleY = if (p.laid.height() > 0f) h / p.laid.height() else 1f
    }

    /** [p] laid out where its move ends, untransformed: its one resize. */
    private fun settle(p: Picture) {
        p.moving = false
        resetTransform(p)
        val lp = p.layoutParams as LayoutParams
        val l = Math.round(p.to.left)
        val tp = Math.round(p.to.top)
        val w = Math.round(p.to.width())
        val h = Math.round(p.to.height())
        if (lp.leftMargin != l || lp.topMargin != tp || lp.width != w || lp.height != h) {
            lp.leftMargin = l
            lp.topMargin = tp
            lp.width = w
            lp.height = h
            p.layoutParams = lp
        }
        p.laid.set(l.toFloat(), tp.toFloat(), (l + w).toFloat(), (tp + h).toFloat())
        p.fit()
        if (p.visibility != VISIBLE) p.visibility = VISIBLE
    }

    private fun resetTransform(p: Picture) {
        p.translationX = 0f
        p.translationY = 0f
        p.scaleX = 1f
        p.scaleY = 1f
    }

    /** Where picture [p] is (px, the board's coordinates; the end of a move under way). */
    fun rectOf(p: Picture, out: RectF) = out.set(p.to)

    /** Any move under way ends where it was heading (the board is hiding). */
    fun finishMotion() {
        val running = mover ?: return
        mover = null
        running.cancel()
        for (p in movingNow) if (p.moving) settle(p)
        movingNow.clear()
    }

    fun applyLook() = addCell.applyLook()

    companion object {
        const val ADD_TEXT = "Add a channel"
    }
}
