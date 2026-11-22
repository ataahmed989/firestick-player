package app.fireplayer.vod.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import app.fireplayer.live.ui.PressHold
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Spring
import app.fireplayer.vod.Card
import app.fireplayer.vod.RowSpec
import app.fireplayer.vod.VodRepository
import kotlin.math.roundToInt

/**
 * "See all": every title of one row's source in a grid of posters ([PosterGrid]) shown in place of the page's rows,
 * loading more pages ([GridFeed]: Cinemeta's skip paging, 50 at a time) as the viewer nears the end. It opens with the
 * row's own cards at once. Up and down glide the grid a row at a time with a Spring; Left from the first column goes
 * to the rail; the page's Back closes it, back to the card it was opened from.
 */
class SeeAllPage(private val ctx: Context, private val t: Tokens, private val repo: VodRepository, private val host: Host) {

    interface Host {
        fun focusRail()
        /** A short OK on a poster. */
        fun onGridPick(card: Card, spec: RowSpec)
        /** OK held on a poster: its small window. */
        fun onGridHold(card: Card, spec: RowSpec, view: CardView)
    }

    /**
     * Placed by the page where its rows show (under the name line; the page clips it there). Its grid isn't clipped to
     * its own edges, so the first column's poster grows and shows its ring. It holds focus itself while it has no
     * posters yet (a genre's first page on its way).
     */
    val root = FrameLayout(ctx).apply {
        visibility = View.GONE
        clipChildren = false
        isFocusable = true
    }
    private val grid = PosterGrid(ctx, t)

    /**
     * The chosen poster's title under it ([PosterLabel]): one for the grid, in a layer that moves with it, gliding from
     * poster to poster (the posters stay put); the grid's row gap keeps its room ([VodSizes.GRID_ROW_GAP]).
     */
    private val label = PosterLabel(ctx, t, VodSizes.GRID_POSTER_W)
    private val labelLayer = FrameLayout(ctx).apply {
        clipChildren = false
        addView(label, FrameLayout.LayoutParams(t.dp(VodSizes.GRID_POSTER_W), t.dp(VodSizes.labelH(VodSizes.GRID_POSTER_W))))
    }
    private val labelTop = t.dp(VodSizes.GRID_TOP) + t.dp(VodSizes.gridLabelTop())
    private val pitchX = t.dpf(VodSizes.gridPitchX())
    /** Focus left the grid's posters altogether (not to the next one): the title fades. */
    private val labelOut = Runnable {
        if (!grid.hasFocus()) label.glideTo(label.translationX, label.translationY, 0f)
    }

    private val main = Handler(Looper.getMainLooper())
    private val spring = Spring(omega = VodSizes.PAGE_OMEGA, settle = VodSizes.PAGE_SETTLE) {
        grid.translationY = -it
        labelLayer.translationY = -it
    }
    private val press = PressHold(ViewConfiguration.getLongPressTimeout().toLong())
    private val pitchY = t.dpf(VodSizes.gridPitchY())

    private var feed: GridFeed? = null
    val spec: RowSpec? get() = feed?.spec
    /** Open (shown, or hidden under a title page opened from it, [cover]). */
    var isOpen = false
        private set

    private var cards: List<Card> = emptyList()
    private var chosen = 0
    private var syncWaitFrom = 0L

    init {
        root.addView(grid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = t.dp(VodSizes.PAGE_X)
        })
        root.addView(labelLayer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            leftMargin = t.dp(VodSizes.PAGE_X)
        })
        root.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                host.focusRail()
                true
            } else {
                keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            }
        }
    }

    /** Opens on [spec]'s titles, starting with [first] (the row's own cards) at once. */
    fun open(spec: RowSpec, first: List<Card>) {
        close()
        chosen = 0
        isOpen = true
        root.visibility = View.VISIBLE
        spring.snap(0f)
        val f = GridFeed(repo, spec, first) { sync(force = false) }
        feed = f
        sync(force = true)
        f.loadNext()
    }

    /** Closes it: its loads stop, its posters and pictures go. */
    fun close() {
        feed?.close()
        feed = null
        main.removeCallbacks(syncLater)
        spring.stop()
        press.cancel()
        root.removeCallbacks(labelOut)
        label.show(null)
        label.glideTo(0f, 0f, 0f, now = true)
        for (i in 0 until grid.childCount) (grid.getChildAt(i) as? CardView)?.releaseImage()
        grid.removeAllViews()
        cards = emptyList()
        isOpen = false
        root.visibility = View.GONE
    }

    fun release() = close()

    /** A title page opened from the grid covers it ([on]): hidden, kept as it is; shown again after. */
    fun cover(on: Boolean) {
        if (!isOpen) return
        root.visibility = if (on) View.INVISIBLE else View.VISIBLE
    }

    /** The poster chosen last takes focus (or the grid itself while it's empty). */
    fun takeFocus() {
        if (grid.childCount == 0) {
            root.requestFocus()
            return
        }
        chosen = chosen.coerceIn(0, grid.childCount - 1)
        grid.getChildAt(chosen).requestFocus()
    }

    /** Lets every poster's picture go; with [cancel] (covered by a title page), queued ones aren't fetched. */
    fun releaseImages(cancel: Boolean = false) {
        for (i in 0 until grid.childCount) (grid.getChildAt(i) as? CardView)?.let { if (cancel) it.cancelImage() else it.releaseImage() }
    }

    /** Pictures for the grid rows near the chosen poster; the others let theirs go. */
    fun bindImages() {
        if (!isOpen) return
        val rows = RowMath.gridRows(grid.childCount, grid.columns)
        val range = RowMath.gridBoundRows(RowMath.gridRow(chosen, grid.columns), rows)
        for (i in 0 until grid.childCount) {
            val card = grid.getChildAt(i) as? CardView ?: continue
            if (RowMath.gridRow(i, grid.columns) in range) card.bindImage() else card.releaseImage()
        }
    }

    /** Stops the glide where it's heading (the page is hidden). */
    fun settle() {
        if (spring.moving) spring.snap(spring.target)
    }

    /** A title left the source (taken off My List, out of Continue Watching). */
    fun remove(id: String) {
        feed?.remove(id)
    }

    /** A title's card changed in place (Mark unwatched in Continue Watching: back to 0:00); the grid redraws it. */
    fun replace(id: String, change: (Card) -> Card) {
        feed?.replace(id, change)
    }

    private val syncLater = Runnable { sync(force = false) }

    /** Shows the feed's titles: cards filled in place, new posters added only once the grid stands still. */
    private fun sync(force: Boolean) {
        val f = feed ?: return
        val list = f.cards
        // A glide under way: new posters wait for it to end (at most 300 ms), so nothing is laid out mid-glide.
        if (!force && spring.moving && list.size > grid.childCount) {
            val now = SystemClock.uptimeMillis()
            if (syncWaitFrom == 0L) syncWaitFrom = now
            if (now - syncWaitFrom < SYNC_WAIT_MS) {
                main.removeCallbacks(syncLater)
                main.postDelayed(syncLater, SYNC_RETRY_MS)
                return
            }
        }
        syncWaitFrom = 0L
        cards = list
        // Posters that go: focus moves off them first.
        val focusAt = (0 until grid.childCount).indexOfFirst { grid.getChildAt(it).isFocused }
        if (focusAt >= list.size && list.isNotEmpty()) grid.getChildAt(list.size - 1).requestFocus()
        while (grid.childCount > list.size) {
            (grid.getChildAt(grid.childCount - 1) as CardView).releaseImage()
            grid.removeViewAt(grid.childCount - 1)
        }
        while (grid.childCount < list.size) grid.addView(poster())
        list.forEachIndexed { i, c -> (grid.getChildAt(i) as CardView).bind(c) }
        if (grid.hasFocus()) label.show(list.getOrNull(chosen)?.title?.name)
        if (list.isEmpty() && grid.hasFocus()) root.requestFocus()
        // The first posters came while the grid held focus itself: into them.
        if (root.isFocused && grid.childCount > 0) takeFocus()
        bindImages()
    }

    private fun poster(): CardView = CardView(ctx, t, CardView.Kind.POSTER, VodSizes.GRID_POSTER_W, VodSizes.GRID_POSTER_H).apply {
        isFocusable = true
        isClickable = false
        isLongClickable = false
        setOnFocusChangeListener { v, has ->
            val card = v as CardView
            // Grows about its middle (the gaps leave room), its ring fading in.
            val grow = if (has) 1f + VodSizes.GROW else 1f
            v.animate().scaleX(grow).scaleY(grow).setDuration(Tokens.FADE_MS).start()
            card.animateFocus(has)
            if (!has) {
                press.cancel()
                root.removeCallbacks(labelOut)
                root.post(labelOut)
                return@setOnFocusChangeListener
            }
            chosen = grid.indexOfChild(v)
            val row = RowMath.gridRow(chosen, grid.columns)
            // The title goes to the new poster: its words once, then a glide (Glass Home's focus curve).
            label.show(cards.getOrNull(chosen)?.title?.name)
            val x = (RowMath.gridColumn(chosen, grid.columns) * pitchX).roundToInt().toFloat()
            label.glideTo(x, (labelTop + (row * pitchY).roundToInt()).toFloat(), 1f)
            spring.glideTo(RowMath.gridTarget(row, pitchY))
            bindImages()
            val f = feed ?: return@setOnFocusChangeListener
            if (RowMath.needMore(row, RowMath.gridRows(grid.childCount, grid.columns))) f.loadNext()
        }
        setOnKeyListener { v, keyCode, event -> onKey(v as CardView, keyCode, event) }
    }

    private fun onKey(v: CardView, keyCode: Int, event: KeyEvent): Boolean {
        val i = grid.indexOfChild(v)
        val s = spec
        if (i < 0 || s == null) return false
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            val card = cards.getOrNull(i) ?: return true
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (press.down(event.repeatCount, event.isLongPress, event.eventTime - event.downTime)) host.onGridHold(card, s, v)
                KeyEvent.ACTION_UP -> if (press.up(event.isCanceled)) host.onGridPick(card, s)
            }
            return true
        }
        val dpad = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        if (event.action != KeyEvent.ACTION_DOWN) return dpad
        press.cancel()
        val cols = grid.columns
        val n = grid.childCount
        // One poster per press, also while held; nothing wraps.
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (RowMath.gridColumn(i, cols) == 0) host.focusRail() else grid.getChildAt(i - 1).requestFocus()
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (RowMath.gridColumn(i, cols) < cols - 1 && i + 1 < n) grid.getChildAt(i + 1).requestFocus()
            KeyEvent.KEYCODE_DPAD_DOWN -> RowMath.gridDown(i, n, cols).takeIf { it >= 0 }?.let { grid.getChildAt(it).requestFocus() }
            KeyEvent.KEYCODE_DPAD_UP -> RowMath.gridUp(i, cols).takeIf { it >= 0 }?.let { grid.getChildAt(it).requestFocus() }
            else -> return false
        }
        return true
    }

    private companion object {
        const val SYNC_WAIT_MS = 300L
        const val SYNC_RETRY_MS = 50L
    }
}
