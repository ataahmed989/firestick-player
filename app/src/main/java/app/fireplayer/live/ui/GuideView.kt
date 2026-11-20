package app.fireplayer.live.ui

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.LongSparseArray
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.animation.DecelerateInterpolator
import app.fireplayer.live.Channel
import app.fireplayer.live.ChannelKey
import app.fireplayer.live.Programme
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.GlassStyle
import app.fireplayer.ui.ListMath
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The compact TV guide grid: channel numbers, logos and names on the left, programmes as duration-proportional
 * cells to the right, a time header and a now line. One custom View draws only the rows and minutes on screen, so
 * thousands of channels cost no more than ten; programmes are asked for a few screens around what is visible
 * ([Callbacks.needProgrammes]) and arrive from a background loader.
 *
 * It draws no panel of its own: the rows, cells and header sit straight on the page's wallpaper. Classic: dark rounded
 * cells. Glass: each cell is a piece of the shared browsing glass ([GlassStyle], [GlassPatch]): barely lighter than
 * the wallpaper that shows through it (the page's one soft backdrop: no cell copies or blurs anything of its own), a
 * one-pixel hairline, a gentle highlight along its top edge. The titles, logos, the white focus highlight and the now
 * line are drawn over that at full strength.
 *
 * The D-pad moves a focus cell: up/down keep the same time column (and go round from the last channel to the first and
 * back on a fresh press), left/right step programme by programme, and the time window scrolls in half-hour steps.
 * Exactly [ROWS] whole rows fill the height under the time header, down to the bottom edge (their height follows from
 * the space, as TiviMate shows eight under its miniature). The white highlight glides from cell to cell, in step
 * with the rows and the time window sliding (one animator drives all three, so they never drift apart). Left from the
 * programme on air leaves the grid ([Callbacks.onLeaveLeft]), which shows the playlist/group column again. Nothing
 * here starts playback: [Callbacks.onPick] (Center, on release) decides; Center held opens the channel's small window
 * instead ([Callbacks.onHold]).
 */
class GuideView(context: Context, private val t: Tokens) : View(context) {

    interface Callbacks {
        fun onFocusMoved(channel: Channel, cell: Cell?)
        fun onPick(channel: Channel, cell: Cell?)
        /** Center held on [channel]: its small window (Favorite, Assign favorites). The release after it does nothing. */
        fun onHold(channel: Channel)
        fun onLeaveLeft()
        /**
         * Listings are wanted for [channels] over [from, to). The first [onScreen] of them are rows showing now, to be
         * read (and handed to [putProgrammes]) first; the rest are the rows around them.
         */
        fun needProgrammes(channels: List<Channel>, onScreen: Int, from: Long, to: Long)
        /** [url]'s logo with its plate, if it has loaded (never waits for it). */
        fun logo(url: String): Logo?
    }

    var callbacks: Callbacks? = null
    var clock: () -> Long = System::currentTimeMillis

    /** The look: glass draws cells as pieces of the shared glass; classic as dark rounded cells. Same geometry either way. */
    var glass = false
        set(v) { field = v; invalidate() }

    /** The channel playing (in the miniature or full screen): its name and the ▶ after it are blue. */
    var playingKey: ChannelKey? = null
        // Set with every word from the player (its buffer filling, a caption line): drawn again only when it changes.
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Playlists whose guide is still being loaded for the first time: their empty rows say so. */
    var loadingPlaylists: Set<String> = emptySet()
        set(v) { if (field != v) { field = v; for (r in rows.values) r.cells.forEach { it.cachedText = null }; invalidate() } }

    private fun noInfo(ch: Channel) = if (ch.playlistId in loadingPlaylists) "Loading the guide…" else "No information"

    // ---- Geometry (dp; TiviMate's proportions, larger and calmer than before (user); about 2 hours across the grid) ---
    /** The time header is at least this tall; it also takes the few pixels the whole rows leave ([layRows]). */
    private val headerMin = t.dpf(HEADER_MIN_DP)
    private var headerH = headerMin
    /** Each channel row: [ROWS] of them fill the height under the header ([layRows]), 35 dp under the miniature. */
    private var rowH = t.dpf(35f)
    /** Between cells, both ways: cells are 32 dp tall in 35 dp rows. */
    private val gap = t.dpf(3f)
    private val radiusClassic = t.dpf(7f)
    private val radiusGlass = t.dpf(GlassStyle.RADIUS_CELL)
    private val hairline = t.dpf(0.5f)
    private val numberLead = t.dpf(4f)
    private val numberMin = t.dpf(22f)
    /** Where channel numbers end (right-aligned): wider than [numberMin] for long provider numbers (29342). */
    private var numberRight = numberMin
    private val logoGap = t.dpf(8f)
    /** The logo box (and its plate), grown with the rows and the names: 7 dp between one row's logo and the next. */
    private val logoW = t.dpf(50f)
    private val logoH = t.dpf(28f)
    private val nameGap = t.dpf(9f)
    private val nameEnd = t.dpf(4f)
    private val markerGap = t.dpf(7f)
    private val markerW = t.dpf(8f)
    /**
     * The channel column ([LiveWidths]). Beside the groups column it's 288 dp: a name as long as "USA Cinemax Action
     * West" (15.5 sp) still fits beside a three-digit number and its logo, and the programmes keep about 80 minutes. In
     * the grid (the groups column and the tab rail slid away) it's 344 dp, so long names show more ([setWideColumn]).
     */
    private val channelNarrow = t.dpf(LiveWidths.CHANNELS_OPEN.toFloat())
    private val channelWide = t.dpf(LiveWidths.CHANNELS_GRID.toFloat())
    /** The channel column as drawn now: the programme cells start here. Equal to [channelTo] unless it's moving. */
    private var channelW = channelNarrow
    /** The width the channel column is going to; names are ellipsized for it. */
    private var channelTo = channelNarrow
    private var channelFrom = channelNarrow
    private val pxPerMin = t.dpf(LiveWidths.DP_PER_MIN)
    private val textInset = t.dpf(9f)

    /** The channel column's width in pixels: the programme cells start here (for aligning things beside the guide). */
    val channelColumnWidth: Int get() = channelTo.roundToInt()

    // ---- State ----------------------------------------------------------------------------------------------------
    private var channels: List<Channel> = emptyList()
    /** Per row of [channels]: the key, the number as text, and the name as last ellipsized (drawing allocates nothing). */
    private var keys: Array<ChannelKey> = emptyArray()
    private var numbers: Array<String> = emptyArray()
    private var names: Array<CharSequence?> = emptyArray()
    private var nameWidths = FloatArray(0)
    private var namePlaying = BooleanArray(0)
    private val rows = HashMap<ChannelKey, RowData>()
    private var focusRow = 0
    private var cursor = 0L
    private var windowStart = 0L
    private var scrollY = 0f
    /** Where the rows are scrolling to (equal to [scrollY] at rest). */
    private var scrollTarget = 0f
    /** The time window drawn this many pixels to the right of [windowStart] while it slides; 0 at rest. */
    private var windowX = 0f
    private var requested: String? = null

    private class RowData(val from: Long, val to: Long, val cells: List<Cell>)

    // ---- Motion ---------------------------------------------------------------------------------------------------
    /** The focus highlight between cells (content coordinates: x as at rest, y = screen y + [scrollY]). */
    private val glide = FocusGlide()
    /** The cell the glide left, so its title turns back from blue as the highlight leaves it. */
    private var glideFromRow = -1
    private var glideFromCell: Cell? = null
    private var motionFromY = 0f
    private var motionFromX = 0f
    private val ease = DecelerateInterpolator(1.5f)
    private val quickEase = DecelerateInterpolator(1f)
    /** One animator for every move: rows scrolling, the time window sliding and the highlight gliding, in step. */
    private val motion: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        addUpdateListener { a ->
            val f = a.animatedFraction
            scrollY = motionFromY + (scrollTarget - motionFromY) * f
            windowX = motionFromX * (1f - f)
            glide.fraction = f
            invalidate()
        }
    }
    /** The channel column widening or narrowing with the groups column's slide: only drawn again, never laid out. */
    private val columnMotion: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        addUpdateListener { a -> columnAt(channelFrom + (channelTo - channelFrom) * a.animatedFraction) }
    }

    /** OK: picks on release, or opens the channel's small window when held (see [PressHold]). */
    private val press = PressHold(ViewConfiguration.getLongPressTimeout().toLong())

    // ---- Paints ---------------------------------------------------------------------------------------------------
    // Sizes (sp) after TiviMate's: names and titles about 16 sp in its 39 dp rows, a little under that in our 35 dp.
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = t.regular; textSize = t.dpf(15f) * fontScale() }
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = t.regular; textSize = t.dpf(15.5f) * fontScale() }
    private val numberPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = t.regular; textSize = t.dpf(14.5f) * fontScale(); color = t.text; textAlign = Paint.Align.RIGHT }
    private val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = t.regular; textSize = t.dpf(14f) * fontScale(); color = t.text }
    private val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = t.semibold; textSize = t.dpf(14f) * fontScale(); color = t.accent }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    /**
     * Glass look: a programme cell, the cell the focus rests on while the column has it, and a row still loading.
     * Each is drawn once for the rows' height and stamped per cell ([GlassPatch]): nothing per frame but the stamps.
     */
    private val cellGlass = GlassPatch(t.density, PatchLook.CELL) { radiusGlass }
    private val cellSelectedGlass = GlassPatch(t.density, PatchLook.CELL_SELECTED) { radiusGlass }
    private val cellEmptyGlass = GlassPatch(t.density, PatchLook.CELL_EMPTY) { radiusGlass }
    /** Glass look: the header's rule and the lines between channel rows are the shared hairline, a pixel on the TV. */
    private val glassHairline = GlassStyle.hairlinePx(t.density)
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.accent; strokeWidth = t.dpf(2f) }
    /** Channel logos on their plates (paints made once). */
    private val plates = PlateDrawer(t.density)
    private val marker = Path()
    private val rect = RectF()
    private val focusRect = RectF()
    private val highlight = RectF()
    private val timeFormat = DateFormat.getTimeFormat(context)
    private val datePattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd")

    // Header labels, made once per minute / per half hour rather than every frame.
    private var dateText = ""
    private var dateMinute = Long.MIN_VALUE
    private val slotLabels = LongSparseArray<String>()

    // Set while drawing the rows: where the focused (and the glide's previous) title went, for the blue copy on top.
    private var drawWs = 0L
    private var rowsBottom = 0f
    private var overTo: CharSequence? = null
    private var overToX = 0f
    private var overToY = 0f
    private var overFrom: CharSequence? = null
    private var overFromX = 0f
    private var overFromY = 0f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        val now = System.currentTimeMillis()
        windowStart = GuideCells.slotStart(now)
        cursor = now
    }

    private fun fontScale() = resources.configuration.fontScale.coerceIn(0.85f, 1.3f)

    /** The rows' per-channel text, made again for a new list: numbers, and names ellipsized lazily as they show. */
    private fun indexChannels() {
        val n = channels.size
        keys = Array(n) { channels[it].key }
        numbers = Array(n) { channels[it].number.toString() }
        names = arrayOfNulls(n)
        nameWidths = FloatArray(n)
        namePlaying = BooleanArray(n)
        // Five-digit provider numbers (29342) are wider than the usual column: it grows, the logo and name move over.
        val digits = numbers.maxOfOrNull { it.length } ?: 1
        numberRight = maxOf(numberMin, numberLead + numberPaint.measureText("8".repeat(digits)))
    }

    // ---- Data -----------------------------------------------------------------------------------------------------

    /**
     * Shows [list]; focus goes to [focus] (or stays on the same channel, or the first). [reload]: the same list read
     * again (a playlist update), where a focused channel that has gone leaves focus on the row nearest its place
     * rather than sending it back to the top.
     */
    fun setChannels(list: List<Channel>, focus: ChannelKey?, reload: Boolean = false) {
        stopMotion()
        val keep = focus ?: channels.getOrNull(focusRow)?.key
        firstScreen = false
        // The very list already shown (nothing was written since it was read) keeps its rows' text as made.
        if (list !== channels) {
            channels = list
            indexChannels()
        }
        focusRow = GuideFocus.rowAfter(rowOf(keep), focusRow, list.size, reload)
        // The time window catches up with now (it may have been opened a while ago), unless the viewer had moved
        // ahead into later programmes.
        val now = clock()
        if (cursor < now) cursor = now
        if (windowStart < GuideCells.slotStart(now)) windowStart = GuideCells.slotStart(now)
        jumpScroll(targetScroll(focusRow) * rowH)
        requested = null
        ensureLoaded()
        notifyFocus()
        invalidate()
    }

    /** [key]'s row (-1: not listed), found in the rows' keys as indexed: nothing is made to look. */
    private fun rowOf(key: ChannelKey?): Int = if (key == null) -1 else keys.indexOf(key)

    /**
     * Only a long list's first screenful is showing (the rows around its focus, while the whole list is read): set by
     * [showFirstScreen], over with [extendChannels] or any other list. Its ends aren't the list's ends, so nothing
     * goes round them.
     */
    private var firstScreen = false

    /**
     * The first screenful of a long list still being read: [part], the rows around [focus] (or the list's first).
     * They show at once, with their listings; [extendChannels] puts the whole list under them when it arrives.
     */
    fun showFirstScreen(part: List<Channel>, focus: ChannelKey?) {
        setChannels(part, focus)
        firstScreen = true
    }

    /**
     * The whole [list] has arrived where its first screenful was showing ([showFirstScreen]): the rows on screen stay
     * exactly where they are (the focused channel on the same row of the screen), their listings as loaded.
     */
    fun extendChannels(list: List<Channel>) {
        stopMotion()
        val key = channels.getOrNull(focusRow)?.key
        val oldTop = (scrollTarget / rowH).roundToInt()
        val oldFocus = focusRow
        firstScreen = false
        channels = list
        indexChannels()
        val i = rowOf(key)
        if (i >= 0) {
            focusRow = i
            jumpScroll(GuideCells.topKeeping(oldTop, oldFocus, i, visibleRows(), list.size) * rowH)
        } else {
            // Its channel went meanwhile (a playlist update): the list's start, as for any new list.
            focusRow = 0
            jumpScroll(0f)
            jumpScroll(targetScroll(focusRow) * rowH)
        }
        requested = null
        ensureLoaded()
        notifyFocus()
        invalidate()
    }

    /** Programmes arrived for [from, to). Rows not in the map have no listings in that range. */
    fun putProgrammes(channelsLoaded: List<Channel>, map: Map<ChannelKey, List<Programme>>, from: Long, to: Long) {
        for (c in channelsLoaded) rows[c.key] = RowData(from, to, GuideCells.cells(map[c.key].orEmpty(), from, to))
        // Bounded: rows far from the focus are dropped (they load again if scrolled back to).
        if (rows.size > MAX_ROWS) {
            val keep = channels.subList((focusRow - MAX_ROWS / 3).coerceAtLeast(0), (focusRow + MAX_ROWS / 3).coerceAtMost(channels.size)).map { it.key }.toHashSet()
            rows.keys.retainAll(keep)
        }
        // A cursor on a gap that's now a programme: the details follow.
        notifyFocus()
        invalidate()
    }

    /** Forgets every listing to free memory (film mode), without loading any again now: they load as rows show. */
    fun forgetProgrammes() {
        rows.clear()
        requested = null
    }

    /** Forgets every listing (new EPG): rows load again as they show. */
    fun clearProgrammes() {
        rows.clear()
        requested = null
        ensureLoaded()
        invalidate()
    }

    fun focusedChannel(): Channel? = channels.getOrNull(focusRow)

    fun focusedCell(): Cell? = focusedChannel()?.let { cellsOf(it) }?.let { cells -> cells.getOrNull(GuideCells.at(cells, cursor)) }

    fun channelList(): List<Channel> = channels

    /** [key]'s channel as the list shown holds it (null: not in it). */
    fun channelOf(key: ChannelKey): Channel? = channels.getOrNull(rowOf(key))

    /** The loaded programmes of [ch], in order (empty when not loaded or none listed). */
    fun programmesOf(ch: Channel): List<Programme> = rows[ch.key]?.cells?.mapNotNull { it.programme }.orEmpty()

    /** The same scope re-read (a favorite changed): focus stays on its channel, or the nearest row. */
    fun updateChannels(list: List<Channel>) {
        stopMotion()
        val key = focusedChannel()?.key
        channels = list
        indexChannels()
        val i = list.indexOfFirst { it.key == key }
        focusRow = if (i >= 0) i else focusRow.coerceAtMost((list.size - 1).coerceAtLeast(0))
        jumpScroll(targetScroll(focusRow) * rowH)
        notifyFocus()
        invalidate()
    }

    /**
     * One channel's details changed (its favorite): its row takes the new copy in place. Focus, scroll and the rows'
     * text stay as they are; the details follow when it's the focused one.
     */
    fun updateChannel(ch: Channel) {
        val i = keys.indexOf(ch.key)
        if (i < 0 || channels[i] == ch) return
        if (channels[i].name != ch.name) { names[i] = null; invalidate() }
        channels = ArrayList(channels).also { it[i] = ch }
        if (i == focusRow) notifyFocus()
    }

    /**
     * The focused row's channel column (number, logo and name) where it rests, in this view's coordinates: what the
     * channel's small window opens beside. False when no row is focused.
     */
    fun focusedRowRect(out: RectF): Boolean {
        if (focusRow !in channels.indices || height == 0) return false
        val top = headerH + focusRow * rowH - scrollTarget
        out.set(0f, top, channelTo, top + rowH)
        return true
    }

    /**
     * The channel column wide (the grid, with the groups column and tab rail slid away) or as beside the groups column.
     * With [animateMs] it widens or narrows in step with the column's slide (same time, [easing]), drawn again at each
     * width: nothing is laid out. Names are ellipsized once for the width it's going to; while it widens they show as
     * far as it has got.
     */
    fun setWideColumn(wide: Boolean, animateMs: Long = 0L, easing: TimeInterpolator? = null) {
        val to = if (wide) channelWide else channelNarrow
        if (to != channelTo) {
            channelTo = to
            names.fill(null)
        }
        columnMotion.cancel()
        if (channelW == to) return
        if (animateMs <= 0L || !isShown) {
            columnAt(to)
            return
        }
        channelFrom = channelW
        columnMotion.duration = animateMs
        columnMotion.interpolator = easing ?: quickEase
        columnMotion.start()
    }

    /** The channel column drawn [w] wide; the glide's start moves with the cells it was drawn over. */
    private fun columnAt(w: Float) {
        glide.shiftX(w - channelW)
        channelW = w
        invalidate()
    }

    /** Moves focus to [key] (after a channel change in full screen, the guide shows where you are). */
    fun focusChannel(key: ChannelKey) {
        val i = rowOf(key)
        if (i < 0) return
        val now = clock()
        cursor = now
        windowStart = GuideCells.slotStart(now)
        moveTo(i, 0L)
    }

    // ---- Keys -----------------------------------------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (channels.isEmpty()) {
            // Nothing listed here (an empty group, no favorites yet): Left goes back to the groups column.
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { callbacks?.onLeaveLeft(); return true }
            return super.onKeyDown(keyCode, event)
        }
        // Another key between an OK's press and release: that release no longer picks.
        if (!isOk(keyCode)) press.cancel()
        // A held key repeats every ~50 ms: each move is a short glide, so the highlight keeps up without jumping.
        val d = if (event.repeatCount > 0) REPEAT_MS else MOVE_MS
        when (keyCode) {
            // Down from the last channel goes round to the first, Up from the first to the last; held, they stop at
            // the end (holding Down doesn't go round and round).
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val down = keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                val to = ListMath.step(focusRow, channels.size, down, event.repeatCount > 0) { true }
                // A first screenful's ends aren't the list's: focus waits there for the whole list, it doesn't go round.
                if (to >= 0 && !(firstScreen && GuideCells.wentRound(focusRow, to, down))) moveTo(to, d)
            }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> moveTo((focusRow - visibleRows()).coerceAtLeast(0), d)
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> moveTo((focusRow + visibleRows()).coerceAtMost(channels.size - 1), d)
            KeyEvent.KEYCODE_DPAD_RIGHT -> step(+1, d)
            KeyEvent.KEYCODE_DPAD_LEFT -> step(-1, d)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // A short press picks when it's released (onKeyUp); held, it opens the channel's small window, once.
                if (press.down(event.repeatCount, event.isLongPress, event.eventTime - event.downTime)) {
                    focusedChannel()?.let { callbacks?.onHold(it) }
                }
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isOk(keyCode) && channels.isNotEmpty()) {
            if (press.up(event.isCanceled)) focusedChannel()?.let { callbacks?.onPick(it, focusedCell()) }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun isOk(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun step(dir: Int, duration: Long) {
        val ch = focusedChannel() ?: return
        val cells = cellsOf(ch) ?: return
        val i = GuideCells.at(cells, cursor)
        val now = clock()
        val next: Long
        if (dir < 0) {
            val cell = cells.getOrNull(i)
            // From what's on now (or earlier), Left leaves the grid for the groups column.
            if (cell == null || cell.start <= now) {
                callbacks?.onLeaveLeft()
                return
            }
            if (i == 0) {
                // A later stretch is loaded but not back to now: load it rather than leave the grid.
                requested = null
                ensureLoaded(force = true)
                return
            }
            next = GuideCells.cursorFor(cells[i - 1], now)
        } else {
            val cell = cells.getOrNull(i + 1)
            if (cell == null) {
                // The end of what's loaded: ask for the hours after it; the next Right carries on.
                requested = null
                ensureLoaded(force = true, until = (rows[ch.key]?.to ?: windowStart) + windowMs() + 4 * 60 * 60_000L)
                return
            }
            next = GuideCells.cursorFor(cell, now)
        }
        beginGlide()
        cursor = next
        var fromX = windowX
        val cell = focusedCell()
        if (cell != null) {
            val target = GuideCells.windowFor(cell, windowStart, windowMs(), GuideCells.slotStart(now))
            if (target != windowStart) {
                // Drawn from where it is (shifted right by the step), sliding to the new start; the glide's start
                // moves with the content it was drawn over.
                val shift = (target - windowStart) / 60_000f * pxPerMin
                windowStart = target
                fromX += shift
                glide.shiftX(-shift)
            }
        }
        startMotion(scrollTarget, fromX, duration)
        ensureLoaded()
        notifyFocus()
        invalidate()
    }

    /** Focus to [row]; [duration] 0 jumps there (no scroll, no glide). */
    private fun moveTo(row: Int, duration: Long) {
        val animate = duration > 0L && height > 0
        if (animate) beginGlide()
        focusRow = row
        val to = targetScroll(row) * rowH
        if (animate) {
            // Further than a screen (round from the last channel to the first): the rows jump to a few rows short and
            // glide the rest. The highlight's start moves with the jump, so it glides on from where it is on screen.
            val from = GuideCells.scrollStart(scrollY, to, visibleRows() * rowH, LONG_GLIDE_ROWS * rowH)
            if (from != scrollY) {
                glide.shiftY(from - scrollY)
                scrollY = from
            }
        }
        startMotion(to, windowX, if (animate) duration else 0L)
        ensureLoaded()
        notifyFocus()
        invalidate()
    }

    /** First visible row that keeps the focus row one row inside the edges where it can ([GuideCells.topFor]). */
    private fun targetScroll(row: Int): Int =
        GuideCells.topFor(row, (scrollTarget / rowH).roundToInt(), visibleRows(), channels.size)

    /** The highlight starts gliding from where it's drawn now (called before the focus changes). */
    private fun beginGlide() {
        glideFromRow = -1
        glideFromCell = null
        if (!isFocused || !focusTarget(focusRect)) { glide.clear(); return }
        glide.update(focusRect.left, focusRect.top, focusRect.right, focusRect.bottom)
        glide.begin(glide.left, glide.top, glide.right, glide.bottom)
        glideFromRow = focusRow
        glideFromCell = focusedCell()
    }

    /**
     * Scrolls the rows to [toScroll] and slides the time window from [fromWindowX] to rest, the highlight gliding
     * with them; [duration] 0 puts everything in place at once.
     */
    private fun startMotion(toScroll: Float, fromWindowX: Float, duration: Long) {
        motion.cancel()
        scrollTarget = toScroll
        if (duration <= 0L || height == 0) {
            scrollY = toScroll
            windowX = 0f
            glide.clear()
            invalidate()
            return
        }
        motionFromY = scrollY
        motionFromX = fromWindowX
        windowX = fromWindowX
        motion.duration = duration
        motion.interpolator = if (duration < MOVE_MS) quickEase else ease
        motion.start()
    }

    /** Everything to rest where it was going. */
    private fun stopMotion() {
        motion.cancel()
        scrollY = scrollTarget
        windowX = 0f
        glide.clear()
    }

    private fun jumpScroll(y: Float) {
        scrollY = y
        scrollTarget = y
    }

    /** Whole rows on screen: [ROWS] once laid out. */
    private fun visibleRows(): Int = GuideCells.wholeRows(height.toFloat(), headerH, rowH)

    /** The time window's length once the channel column has settled (what loading and stepping plan for). */
    private fun windowMs(): Long = (((width - channelTo) / pxPerMin) * 60_000).toLong().coerceAtLeast(GuideCells.SLOT_MS)

    private fun cellsOf(ch: Channel): List<Cell>? = rows[ch.key]?.cells

    /** The focused cell's rect at rest, in content coordinates; false when it has none (row not loaded, off screen). */
    private fun focusTarget(out: RectF): Boolean {
        if (focusRow !in keys.indices) return false
        val cells = rows[keys[focusRow]]?.cells ?: return false
        val cell = cells.getOrNull(GuideCells.at(cells, cursor)) ?: return false
        val x0 = GuideCells.cellLeft(cell.start, windowStart, channelW, pxPerMin, gap)
        val x1 = GuideCells.cellRight(cell.stop, windowStart, channelW, width.toFloat(), pxPerMin, gap)
        if (x1 - x0 < 1f) return false
        val top = headerH + focusRow * rowH
        out.set(x0, top + gap / 2, x1, top + rowH - gap / 2)
        return true
    }

    private fun notifyFocus() {
        val ch = focusedChannel() ?: return
        val cell = focusedCell()
        callbacks?.onFocusMoved(ch, cell)
        val p = cell?.programme
        contentDescription = buildString {
            append(ch.number).append(' ').append(ch.name).append(". ")
            if (p != null) append(p.title).append(", ").append(timeFormat.format(Date(p.start))).append(" to ").append(timeFormat.format(Date(p.stop)))
            else append(noInfo(ch))
        }
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (am?.isEnabled == true && isFocused) sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
    }

    /**
     * Asks for listings around what's visible: two screens of rows each way, from before now to the window plus a
     * few hours ([until] reaches further). Every loaded row covers now, so its first cell is never in the future.
     */
    private fun ensureLoaded(force: Boolean = false, until: Long = 0L) {
        if (channels.isEmpty() || height == 0) return
        val visible = visibleRows()
        // Where the rows are going, not where a scroll in progress has got to.
        val top = (scrollTarget / rowH).toInt().coerceIn(0, channels.size)
        val from = (top - visible * 2).coerceAtLeast(0)
        val to = (top + visible * 3).coerceAtMost(channels.size)
        if (from >= to) return
        val base = minOf(windowStart, GuideCells.slotStart(clock()))
        val tFrom = base - GuideCells.SLOT_MS * 2
        val tTo = maxOf(windowStart + windowMs() + 4 * 60 * 60_000L, until)
        val windowEnd = windowStart + windowMs()
        // The rows showing (one more while they scroll) are asked for first, the rows around them after.
        val shownTo = (top + visible + 1).coerceAtMost(to)
        val shown = ArrayList<Channel>()
        val around = ArrayList<Channel>()
        var first: ChannelKey? = null
        for (i in from until to) {
            val row = rows[keys[i]]
            if (row != null && row.from <= base && row.to >= windowEnd && !(force && row.to < tTo)) continue
            if (first == null) first = keys[i]
            (if (i in top until shownTo) shown else around) += channels[i]
        }
        if (first == null) return
        val key = "$first|${shown.size + around.size}|$tFrom"
        if (key == requested) return
        requested = key
        callbacks?.needProgrammes(shown + around, shown.size, tFrom, tTo)
    }

    /** After the width stops changing: listings for the wider window, if any are missing (usually none). */
    private val widthSettled = Runnable { ensureLoaded() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw > 0 && h == oldh) {
            // Only the width changed (the groups column opened or closed). The rows, the scroll and glide in progress
            // and the request de-duplication stay as they are (cells are worked out from the width as they're
            // drawn); listings are checked once, after it settles.
            removeCallbacks(widthSettled)
            postDelayed(widthSettled, WIDTH_SETTLE_MS)
            return
        }
        // The height changed (laid out the first time): the rows' height follows it. The rows keep their place, counted
        // in rows, and focus stays on a whole row.
        val top = (scrollTarget / rowH).roundToInt()
        stopMotion()
        layRows(h)
        jumpScroll(top * rowH)
        jumpScroll(targetScroll(focusRow) * rowH)
        requested = null
        ensureLoaded()
    }

    /**
     * [ROWS] whole rows under the time header, filling [h] pixels down to the bottom edge: the rows' height is the space
     * under the header divided by [ROWS] (whole pixels, for crisp hairlines and text), the header takes the few pixels
     * left. Worked out only when the height changes, never while drawing.
     */
    private fun layRows(h: Int) {
        if (h <= 0) return
        rowH = GuideCells.rowHeight(h.toFloat(), headerMin, ROWS)
        headerH = GuideCells.headerHeight(h.toFloat(), headerMin, ROWS, rowH)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        // The highlight shows (or goes) on its cell at once; only moves within the grid glide.
        glide.clear()
        // An OK pressed here and released elsewhere (or the other way round) is no pick.
        press.cancel()
        if (gainFocus) {
            // Back in the grid: what's on now, on the same channel.
            val now = clock()
            if (cursor < now) cursor = now
            notifyFocus()
        }
        invalidate()
    }

    // ---- Drawing --------------------------------------------------------------------------------------------------

    private fun xOf(time: Long) = GuideCells.xOf(time, drawWs, channelW, pxPerMin)

    private fun cellRadius() = if (glass) radiusGlass else radiusClassic

    override fun onDraw(canvas: Canvas) {
        val now = clock()
        val w = width.toFloat()
        drawWs = windowStart - (windowX / pxPerMin * 60_000).toLong()
        val wEnd = drawWs + ((w - channelW) / pxPerMin * 60_000).toLong()
        // The rows end on the bottom edge: [ROWS] whole ones at rest (a row more shows in part while they scroll).
        rowsBottom = height.toFloat()

        drawHeader(canvas, now, wEnd)

        overTo = null
        overFrom = null
        val rowsEnd = minOf(rowsBottom, headerH + channels.size * rowH - scrollY)
        canvas.save()
        canvas.clipRect(0f, headerH, w, rowsBottom)
        if (channels.isNotEmpty()) {
            val firstRow = (scrollY / rowH).toInt().coerceAtLeast(0)
            // The last row with any of it showing: at rest none starts on the bottom edge, so none is drawn there unseen.
            val lastRow = (ceil((scrollY + rowsBottom - headerH) / rowH).toInt() - 1).coerceAtMost(channels.size - 1)
            for (r in firstRow..lastRow) {
                val top = headerH + r * rowH - scrollY
                drawChannel(canvas, r, top)
                canvas.save()
                canvas.clipRect(channelW, top, w, top + rowH)
                drawCells(canvas, r, top, now, wEnd)
                canvas.restore()
            }
            if (isFocused) drawFocus(canvas)
        }
        // The now line over the cells and the highlight, down to the last row.
        val nx = xOf(now)
        val showNow = nx >= channelW && nx <= w && rowsEnd > headerH
        if (showNow) canvas.drawLine(nx, headerH, nx, rowsEnd, nowPaint)
        canvas.restore()
        // The dot sits centred on the header's rule (the horizontal time line), the now line running down from it.
        if (showNow) canvas.drawCircle(nx, headerH - t.dpf(0.5f), t.dpf(3.5f), nowPaint)
    }

    private fun drawHeader(canvas: Canvas, now: Long, wEnd: Long) {
        val base = headerH - t.dpf(9f)
        canvas.drawText(dateLabel(now), numberLead, base, datePaint)
        var slot = GuideCells.slotStart(drawWs)
        canvas.save()
        // Labels slide with the time window and pass under the channel column's edge rather than popping.
        canvas.clipRect(channelW, 0f, width.toFloat(), headerH)
        while (slot < wEnd) {
            canvas.drawText(slotLabel(slot), xOf(slot) + t.dpf(4f), base, headerPaint)
            slot += GuideCells.SLOT_MS
        }
        canvas.restore()
        fill.color = if (glass) GlassStyle.HAIRLINE else CLASSIC_RULE
        canvas.drawRect(0f, headerH - if (glass) glassHairline else t.dpf(1f), width.toFloat(), headerH, fill)
    }

    /** "Tue, Nov 17 · 5:28 PM", made again when the minute changes. */
    private fun dateLabel(now: Long): String {
        val minute = now / 60_000
        if (minute != dateMinute) {
            dateMinute = minute
            dateText = DateFormat.format(datePattern, now).toString() + " · " + timeFormat.format(Date(now))
        }
        return dateText
    }

    private fun slotLabel(slot: Long): String {
        slotLabels.get(slot)?.let { return it }
        if (slotLabels.size() > 64) slotLabels.clear()
        return timeFormat.format(Date(slot)).also { slotLabels.put(slot, it) }
    }

    private fun drawChannel(canvas: Canvas, r: Int, top: Float) {
        val ch = channels[r]
        val playing = keys[r] == playingKey
        val cy = top + rowH / 2
        // A hairline between channel rows; no fill behind them.
        fill.color = if (glass) GlassStyle.HAIRLINE else CLASSIC_DIVIDER
        canvas.drawRect(0f, top + rowH - if (glass) glassHairline else hairline, channelW - gap / 2, top + rowH, fill)

        canvas.drawText(numbers[r], numberRight, baseline(cy, numberPaint), numberPaint)

        val lx = numberRight + logoGap
        val url = ch.logo
        val logo = if (url != null) callbacks?.logo(url) else null
        if (logo != null || glass) {
            // On its plate, so dark logos show on the dark wallpaper too (the same plate as the details'). Glass: every
            // row has the same tile, with a logo or (none, or still on its way) without.
            plates.draw(canvas, logo, lx, cy - logoH / 2, lx + logoW, cy + logoH / 2)
        } else {
            fill.color = LOGO_EMPTY
            rect.set(lx, cy - logoH / 2, lx + logoW, cy + logoH / 2)
            canvas.drawRoundRect(rect, t.dpf(4f), t.dpf(4f), fill)
        }

        val nx = lx + logoW + nameGap
        namePaint.color = if (playing) t.accent else t.text
        val name = nameOf(r, nx, playing)
        // While the column widens, the names (ellipsized for its new width) show as far as it has got.
        val cut = channelW < channelTo
        if (cut) {
            canvas.save()
            canvas.clipRect(nx, top, channelW - nameEnd, top + rowH)
        }
        canvas.drawText(name, 0, name.length, nx, baseline(cy, namePaint), namePaint)
        if (playing) {
            // The channel playing: a small blue ▶ just after its name.
            val mx = nx + nameWidths[r] + markerGap
            val s = t.dpf(5f)
            marker.reset()
            marker.moveTo(mx, cy - s); marker.lineTo(mx + s * 1.6f, cy); marker.lineTo(mx, cy + s); marker.close()
            fill.color = t.accent
            canvas.drawPath(marker, fill)
        }
        if (cut) canvas.restore()
    }

    /**
     * Row [r]'s name ellipsized to the column (less room when the ▶ follows it), kept until the list or the column's
     * width changes.
     */
    private fun nameOf(r: Int, nx: Float, playing: Boolean): CharSequence {
        names[r]?.let { if (namePlaying[r] == playing) return it }
        val avail = channelTo - nameEnd - nx - if (playing) markerGap + markerW else 0f
        val s = TextUtils.ellipsize(channels[r].name, namePaint, avail.coerceAtLeast(0f), TextUtils.TruncateAt.END)
        names[r] = s
        namePlaying[r] = playing
        nameWidths[r] = namePaint.measureText(s, 0, s.length)
        return s
    }

    private fun drawCells(canvas: Canvas, r: Int, top: Float, now: Long, wEnd: Long) {
        val w = width.toFloat()
        val cy = top + rowH / 2
        val radius = cellRadius()
        val row = rows[keys[r]]
        if (row == null) {
            // Not loaded yet: one faint bar, no text.
            if (glass) {
                cellEmptyGlass.draw(canvas, channelW + gap / 2, top + gap / 2, w - gap / 2, top + rowH - gap / 2)
                return
            }
            fill.color = CLASSIC_EMPTY
            rect.set(channelW + gap / 2, top + gap / 2, w - gap / 2, top + rowH - gap / 2)
            canvas.drawRoundRect(rect, radius, radius, fill)
            return
        }
        val ch = channels[r]
        val cells = row.cells
        val focusIndex = if (r == focusRow) GuideCells.at(cells, cursor) else -1
        val gridFocused = isFocused
        for (i in cells.indices) {
            val cell = cells[i]
            if (cell.stop <= drawWs || cell.start >= wEnd) continue
            val x0 = GuideCells.cellLeft(cell.start, drawWs, channelW, pxPerMin, gap)
            val x1 = GuideCells.cellRight(cell.stop, drawWs, channelW, w, pxPerMin, gap)
            if (x1 - x0 < 1f) continue
            val isFocus = i == focusIndex
            // The focus cell while the column has the focus: a shade lighter, its title semibold (no white).
            val selected = isFocus && !gridFocused
            if (glass) {
                // On whole pixels (the patch's own rule), so every cell's hairline is the same crisp pixel.
                (if (selected) cellSelectedGlass else cellGlass).draw(canvas, x0, top + gap / 2, x1, top + rowH - gap / 2)
            } else {
                rect.set(x0, top + gap / 2, x1, top + rowH - gap / 2)
                fill.color = if (selected) CLASSIC_SELECTED else CLASSIC_CELL
                canvas.drawRoundRect(rect, radius, radius, fill)
            }
            val avail = (x1 - x0 - 2 * textInset).toInt()
            if (avail > t.dpf(12f)) {
                textPaint.typeface = if (isFocus) t.semibold else t.regular
                textPaint.color = if (cell.programme == null || cell.stop <= now) TEXT_DIM else t.text
                val text = titleOf(cell, ch, avail, isFocus)
                val tx = x0 + textInset
                val ty = baseline(cy, textPaint)
                canvas.drawText(text, 0, text.length, tx, ty, textPaint)
                // Under the white highlight these are drawn again in blue (drawFocus).
                if (isFocus && gridFocused) { overTo = text; overToX = tx; overToY = ty }
                if (!isFocus && r == glideFromRow && cell === glideFromCell) { overFrom = text; overFromX = tx; overFromY = ty }
            }
        }
    }

    private fun titleOf(cell: Cell, ch: Channel, avail: Int, bold: Boolean): CharSequence {
        val cached = cell.cachedText
        if (cached != null && cell.cachedWidth == avail && cell.cachedBold == bold) return cached
        val title = cell.programme?.title?.takeIf { it.isNotEmpty() } ?: if (cell.programme == null) noInfo(ch) else "Untitled"
        return TextUtils.ellipsize(title, textPaint, avail.toFloat(), TextUtils.TruncateAt.END).also {
            cell.cachedText = it
            cell.cachedWidth = avail
            cell.cachedBold = bold
        }
    }

    /**
     * The white highlight, gliding from the cell it left to the focused one; the titles under it are drawn again in
     * blue, clipped to it, so the text colours follow the highlight as it moves.
     */
    private fun drawFocus(canvas: Canvas) {
        if (!focusTarget(focusRect)) return
        glide.update(focusRect.left, focusRect.top, focusRect.right, focusRect.bottom)
        // Content to screen; kept within the cells' area so its rounded ends show while the time window slides.
        highlight.set(
            maxOf(glide.left + windowX, channelW + gap / 2), glide.top - scrollY,
            minOf(glide.right + windowX, width - gap / 2), glide.bottom - scrollY,
        )
        if (highlight.width() < 1f) return
        if (glass && !glide.moving && windowX == 0f) {
            // At rest it covers its cell exactly: on the same whole pixels as the glass under it.
            val top = PatchMath.snap(highlight.top)
            highlight.set(
                PatchMath.snap(highlight.left).toFloat(), top.toFloat(),
                PatchMath.snap(highlight.right).toFloat(), (top + PatchMath.snap(highlight.height())).toFloat(),
            )
        }
        val radius = cellRadius()
        canvas.save()
        canvas.clipRect(channelW, headerH, width.toFloat(), rowsBottom)
        fill.color = t.focusFill
        canvas.drawRoundRect(highlight, radius, radius, fill)
        canvas.clipRect(highlight)
        textPaint.color = t.accent
        val from = overFrom
        if (from != null && glide.moving) {
            textPaint.typeface = t.regular
            canvas.drawText(from, 0, from.length, overFromX, overFromY, textPaint)
        }
        val to = overTo
        if (to != null) {
            textPaint.typeface = t.semibold
            canvas.drawText(to, 0, to.length, overToX, overToY, textPaint)
        }
        canvas.restore()
    }

    private fun baseline(cy: Float, p: Paint): Float = cy - (p.descent() + p.ascent()) / 2

    // ---- Time -----------------------------------------------------------------------------------------------------

    private val tick = object : Runnable {
        override fun run() {
            val now = clock()
            // The programme on air ended under the focus: focus moves on with it.
            if (cursor < now && focusedCell()?.contains(now) == false && focusedCell()?.stop?.let { it <= now } == true) {
                cursor = now
                notifyFocus()
            }
            if (GuideCells.slotStart(now) > windowStart) {
                windowStart = GuideCells.slotStart(now)
                if (cursor < now) cursor = now
                glide.clear()
                requested = null
                ensureLoaded()
                notifyFocus()
            }
            invalidate()
            postDelayed(this, 30_000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postDelayed(tick, 30_000)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        removeCallbacks(widthSettled)
        stopMotion()
        columnMotion.cancel()
        if (channelW != channelTo) columnAt(channelTo)
        press.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        /** Channel rows on screen, whole, under the time header (user: eight under the miniature, as TiviMate). */
        const val ROWS = 8
        /** The time header's least height (dp): the date and times at 14 sp over its rule. */
        const val HEADER_MIN_DP = 28f
        const val MAX_ROWS = 600
        /** A move: rows scrolling, the window sliding and the highlight gliding, decelerating. */
        const val MOVE_MS = 150L
        /** A move while a key is held (repeats): short, so the highlight keeps up with the focus. */
        const val REPEAT_MS = 80L
        /** A move further than a screen glides only this many rows (the rest is a jump). */
        const val LONG_GLIDE_ROWS = 3
        /** The width is taken as settled once it hasn't changed for this long. */
        const val WIDTH_SETTLE_MS = 120L

        // Classic: dark rounded cells a little lighter than the wallpaper.
        const val CLASSIC_CELL = 0xFF252A31.toInt()
        const val CLASSIC_SELECTED = 0xFF30363F.toInt()
        const val CLASSIC_EMPTY = 0x99252A31.toInt()
        const val CLASSIC_DIVIDER = 0x14FFFFFF
        const val CLASSIC_RULE = 0x2EFFFFFF
        // Glass: every value is the shared style's ([GlassStyle], through [PatchLook] and the hairline).
        /** Classic: where a channel without a logo would have one. */
        const val LOGO_EMPTY = 0x1FFFFFFF
        /** Past programmes and gaps ("No information"). */
        const val TEXT_DIM = 0x99F4F2EE.toInt()
    }
}

/** Where the guide's focus lands when its list is shown again. Pure, for tests. */
object GuideFocus {
    /**
     * [found]: the wanted channel's row in the new list (-1: not in it). A [reload] of the same list keeps the row the
     * focus was on ([oldRow], within the new [size]); a new list starts at the top.
     */
    fun rowAfter(found: Int, oldRow: Int, size: Int, reload: Boolean): Int = when {
        found >= 0 -> found
        reload && size > 0 -> oldRow.coerceIn(0, size - 1)
        else -> 0
    }
}
