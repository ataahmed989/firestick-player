package app.fireplayer.live.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.View
import android.view.animation.DecelerateInterpolator
import app.fireplayer.live.Channel
import app.fireplayer.live.ChannelKey
import app.fireplayer.live.Programme
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.GlassStyle
import java.util.Date
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * One column of the channel list over the picture ([LiveOverlay]): a header line, then its rows, drawn by this one view
 * as the guide draws its own (only the rows on screen; nothing made per frame: names and titles are cut to fit once and
 * kept). [Kind.GROUPS]: a playlist's groups, as the guide's groups column lists them. [Kind.CHANNELS]: the guide's
 * channel rows (number, logo, name, the programme on now under it with its progress line; the playing one blue with
 * its ▶). [Kind.PROGRAMMES]: a channel's programmes, start time and title, the one on now blue, under a header of the
 * channel's logo and name.
 *
 * The selection is the white pill (while [active]); Up and Down move it as the guide's moves: the pill glides from row
 * to row and the rows scroll with it in the same motion, a row of room kept each side, a long jump gliding its last
 * few rows only ([GuideCells.topFor], [GuideCells.scrollStart]). Nothing here takes Android's focus: the list hands it
 * the keys' results.
 */
@SuppressLint("ViewConstructor")
internal class OverlayList(context: Context, private val t: Tokens, val kind: Kind) : View(context) {

    enum class Kind { GROUPS, CHANNELS, PROGRAMMES }

    /** A channel's logo with its plate, if it has loaded (never waits for it). */
    var logo: (String) -> Logo? = { null }
    /** A channel's programme on now, by its key, if read (the rows show none until it is). */
    var nowOf: (ChannelKey) -> Programme? = { null }
    var clock: () -> Long = System::currentTimeMillis

    var glass = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** The selection is in this column: its row has the white pill. */
    var active = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** The channel playing: its name and the ▶ after it are blue. */
    var playingKey: ChannelKey? = null
        set(v) { if (field != v) { field = v; names.fill(null); invalidate() } }

    /** A row drawn blue: the group whose channels show beside it, or the programme on now. */
    var mark = -1
        set(v) { if (field != v) { field = v; invalidate() } }

    /** A row drawn semibold (Search, at the groups' top). */
    var bold = -1
        set(v) { if (field != v) { field = v; names.fill(null); invalidate() } }

    /** The row whose name scrolls in the marquee line over it: its own name is left out meanwhile. */
    var marqueeRow = -1
        set(v) { if (field != v) { field = v; invalidate() } }

    // ---- What it shows -------------------------------------------------------------------------------------------

    private var header = ""
    private var headerCut: CharSequence? = null
    private var headChannel: Channel? = null
    private var headSub = ""
    private var headNameCut: CharSequence? = null
    private var headSubCut: CharSequence? = null
    /** Said in the first row's place while there are no rows ("" says nothing). */
    private var emptyText = ""

    private var labels: List<String> = emptyList()
    var channels: List<Channel> = emptyList()
        private set
    private var programmes: List<Programme> = emptyList()
    private var numbers: Array<String> = emptyArray()
    /** Per channel row, its key (made once per list: drawing makes nothing). */
    private var keys: Array<ChannelKey> = emptyArray()
    private var times: Array<String> = emptyArray()
    /** Per row, cut to fit when first drawn and kept: the name, its width, and the programme line. */
    private var names: Array<CharSequence?> = emptyArray()
    private var nameW = FloatArray(0)
    private var subs: Array<CharSequence?> = emptyArray()
    /** The programme each row's programme line was made for ([NONE]: none on now). */
    private var subFor: Array<Any?> = emptyArray()

    val count: Int
        get() = when (kind) {
            Kind.GROUPS -> labels.size
            Kind.CHANNELS -> channels.size
            Kind.PROGRAMMES -> programmes.size
        }

    /** The selected row (-1: none). */
    var focus = -1
        private set

    /** The first whole row shown at rest. */
    var firstRow = 0
        private set

    // ---- Geometry (px) -------------------------------------------------------------------------------------------

    private val padX = t.dpf(LiveWidths.OV_PAD_X.toFloat())
    private val rowPad = t.dpf(LiveWidths.OV_ROW_PAD_X.toFloat())
    private val headTop = t.dpf(LiveWidths.OV_TOP.toFloat())
    /** One header height for every column: their rules line up side by side. */
    private val headH = t.dpf(LiveWidths.OV_HEAD_H.toFloat())
    private val rowsTop = headTop + headH
    private var rowH = t.dpf(
        when (kind) {
            Kind.GROUPS -> LiveWidths.OV_GROUP_ROW
            Kind.PROGRAMMES -> LiveWidths.OV_PROGRAMME_ROW
            Kind.CHANNELS -> (LiveWidths.SCREEN_H - LiveWidths.OV_TOP * 2 - LiveWidths.OV_HEAD_H) / LiveWidths.OV_CHANNEL_ROWS
        }.toFloat(),
    )
    /** The number column: as wide as the list's longest number needs ([setChannels]), never under [LiveWidths.OV_NUMBER_W]. */
    private var numberW = t.dpf(LiveWidths.OV_NUMBER_W.toFloat())
    private val logoW = t.dpf(LiveWidths.OV_LOGO_W.toFloat())
    private val logoH = t.dpf(LiveWidths.OV_LOGO_H.toFloat())
    private val headLogoW = t.dpf(LiveWidths.OV_HEAD_LOGO_W.toFloat())
    private val headLogoH = t.dpf(LiveWidths.OV_HEAD_LOGO_H.toFloat())
    private val gap = t.dpf(LiveWidths.OV_GAP.toFloat())
    /** The start times' column: as wide as the list's widest time needs ([setProgrammes]), never under [LiveWidths.OV_TIME_W]. */
    private var timeW = t.dpf(LiveWidths.OV_TIME_W.toFloat())
    private val progressH = t.dpf(LiveWidths.OV_PROGRESS_H)
    private val progressGap = t.dpf(LiveWidths.OV_PROGRESS_GAP.toFloat())
    private val pillRadius = t.dpf(LiveWidths.OV_PILL_RADIUS)
    private val pillInset = t.dpf(1.5f)
    private val markerGap = t.dpf(7f)
    private val markerW = t.dpf(8f)
    private val hairline = GlassStyle.hairlinePx(t.density)

    // ---- Paints (made once) --------------------------------------------------------------------------------------

    private fun fontScale() = resources.configuration.fontScale.coerceIn(0.85f, 1.3f)
    private fun paint(sp: Float, face: Typeface) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; textSize = t.dpf(sp) * fontScale() }

    /** The rows' names (groups, channels, programme titles): the marquee line copies its size and face. */
    val namePaint: TextPaint = paint(
        when (kind) {
            Kind.GROUPS -> LiveWidths.OV_GROUP_SP
            Kind.CHANNELS -> LiveWidths.OV_NAME_SP
            Kind.PROGRAMMES -> LiveWidths.OV_PROGRAMME_SP
        },
        t.regular,
    )
    private val subPaint = paint(LiveWidths.OV_SUB_SP, t.regular)
    private val headPaint = paint(LiveWidths.OV_HEAD_SP, t.semibold)
    private val headSubPaint = paint(LiveWidths.OV_SUB_SP, t.regular).apply { color = t.textMuted }
    private val numberPaint = paint(LiveWidths.OV_SUB_SP, t.regular).apply { textAlign = Paint.Align.CENTER }
    /** The widest digit in the numbers' face (measured once). */
    private val digitW = FloatArray(10).also { numberPaint.getTextWidths("0123456789", it) }.max()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val plates = PlateDrawer(t.density)
    private val headPlates = PlateDrawer(t.density, LiveWidths.DETAIL_LOGO_PAD_X, LiveWidths.DETAIL_LOGO_PAD_Y)
    private val marker = Path()
    private val rect = RectF()
    private val timeFormat = DateFormat.getTimeFormat(context)

    // ---- Motion: the rows scrolling and the pill gliding, one animator for both (as the guide's) --------------------

    private var scrollY = 0f
    private var scrollFrom = 0f
    private var scrollTo = 0f
    /** The pill's row, between two rows while it glides. */
    private var pill = 0f
    private var pillFrom = 0f
    private val ease = DecelerateInterpolator(1.5f)
    private val quickEase = DecelerateInterpolator(1f)
    private val motion: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        addUpdateListener { a ->
            val f = a.animatedFraction
            scrollY = scrollFrom + (scrollTo - scrollFrom) * f
            pill = pillFrom + (focus - pillFrom) * f
            invalidate()
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // ---- Setting what it shows -------------------------------------------------------------------------------------

    /** The header line (groups: the playlist; channels: the playlist and the group). */
    fun setHeader(text: String) {
        if (header == text) return
        header = text
        headerCut = null
        invalidate()
    }

    /** The programme column's header: [channel]'s logo, number and name over [sub] (its playlist). */
    fun setHeadChannel(channel: Channel?, sub: String) {
        if (headChannel?.key == channel?.key && headChannel?.name == channel?.name && headSub == sub) return
        headChannel = channel
        headSub = sub
        headNameCut = null
        headSubCut = null
        invalidate()
    }

    fun setEmptyText(text: String) {
        if (emptyText == text) return
        emptyText = text
        invalidate()
    }

    fun setGroups(list: List<String>, select: Int) {
        labels = list
        resetRows(list.size)
        place(select)
    }

    fun setChannels(list: List<Channel>, select: Int) {
        channels = list
        numbers = Array(list.size) { list[it].number.toString() }
        keys = Array(list.size) { list[it].key }
        // The number column holds the list's longest number (five digits and more), so none reaches out of its row.
        var longest = 0
        for (n in numbers) if (n.length > longest) longest = n.length
        numberW = maxOf(t.dpf(LiveWidths.OV_NUMBER_W.toFloat()), ceil(digitW * longest))
        resetRows(list.size)
        place(select)
    }

    /** [list] in time order; [onNow] (blue) is shown second from the top, the one before it above. */
    fun setProgrammes(list: List<Programme>, onNow: Int) {
        programmes = list
        times = Array(list.size) { timeFormat.format(Date(list[it].start)) }
        // The titles start after the widest time (a larger system font's "12:07 am" too), a gap between them.
        var widest = 0f
        for (time in times) widest = maxOf(widest, subPaint.measureText(time))
        timeW = maxOf(t.dpf(LiveWidths.OV_TIME_W.toFloat()), ceil(widest) + gap)
        resetRows(list.size)
        mark = onNow
        place(-1, first = (onNow - 1).coerceAtLeast(0))
    }

    private fun resetRows(n: Int) {
        names = arrayOfNulls(n)
        nameW = FloatArray(n)
        subs = arrayOfNulls(n)
        subFor = arrayOfNulls(n)
        marqueeRow = -1
        invalidate()
    }

    /** The programme lines are made again as they're drawn (the programmes on now were read, or one ended). */
    fun programmesChanged() {
        subFor.fill(null)
        invalidate()
    }

    /** Selects [row] at once, a third of the way down the rows (or [first] on top); no motion. */
    fun place(row: Int, first: Int = -1) {
        motion.cancel()
        focus = if (row in 0 until count) row else -1
        val vis = visibleRows()
        val maxTop = (count - vis).coerceAtLeast(0)
        firstRow = when {
            first >= 0 -> first
            focus >= 0 -> focus - vis / 3
            else -> 0
        }.coerceIn(0, maxTop)
        scrollY = firstRow * rowH
        pill = focus.toFloat()
        invalidate()
    }

    /**
     * The selection to [row]: the pill glides there and the rows scroll just enough to keep a row of room round it, in
     * one motion ([held]: a held key's quicker one). A move further than a screen jumps and glides its last rows.
     */
    fun moveTo(row: Int, held: Boolean) {
        if (row !in 0 until count || row == focus) return
        if (height == 0 || !isShown) { place(row); return }
        val vis = visibleRows()
        val newTop = GuideCells.topFor(row, firstRow, vis, count)
        val to = newTop * rowH
        val glideRows = LiveWidths.OV_LONG_GLIDE_ROWS
        motion.cancel()
        scrollFrom = GuideCells.scrollStart(scrollY, to, vis * rowH, glideRows * rowH)
        scrollTo = to
        pillFrom = if (abs(row - pill) > vis) (row + if (row < pill) glideRows else -glideRows).toFloat() else pill
        focus = row
        firstRow = newTop
        scrollY = scrollFrom
        pill = pillFrom
        motion.duration = if (held) LiveWidths.OV_REPEAT_MS else LiveWidths.OV_MOVE_MS
        motion.interpolator = if (held) quickEase else ease
        motion.start()
    }

    /** Any motion ends where it was going (the list is hiding). */
    fun settle() {
        if (!motion.isRunning) return
        motion.cancel()
        scrollY = scrollTo
        pill = focus.toFloat()
        invalidate()
    }

    /** Whole rows under the header. */
    fun visibleRows(): Int {
        val h = if (height > 0) height.toFloat() else t.dpf(LiveWidths.SCREEN_H.toFloat())
        return ((h - rowsTop) / rowH).toInt().coerceAtLeast(1)
    }

    // ---- The marquee line's place ----------------------------------------------------------------------------------

    /** Row [row]'s whole name (a group's, or a channel's). */
    fun fullName(row: Int): String = when (kind) {
        Kind.GROUPS -> labels.getOrNull(row) ?: ""
        Kind.CHANNELS -> channels.getOrNull(row)?.name ?: ""
        Kind.PROGRAMMES -> ""
    }

    fun nameFace(row: Int): Typeface = if (row == bold) t.semibold else t.regular

    /** Where row [row]'s name goes (this view's coordinates, at rest): the room it has, one line tall. */
    fun nameBox(row: Int, out: RectF) {
        val rTop = rowsTop + row * rowH - scrollY
        val x = nameX()
        val cy = if (kind == Kind.CHANNELS) rTop + rowH * LiveWidths.OV_NAME_AT else rTop + rowH / 2
        val half = namePaint.fontSpacing / 2
        out.set(x, cy - half, x + nameRoomOf(row), cy + half)
    }

    /** The room row [row]'s name has: on the playing channel's, less the ▶ after it. */
    private fun nameRoomOf(row: Int): Float =
        if (kind == Kind.CHANNELS && keys.getOrNull(row) == playingKey) (nameRoom() - markerGap - markerW).coerceAtLeast(0f) else nameRoom()

    private fun rowX(): Float = padX + rowPad

    private fun nameX(): Float = if (kind == Kind.CHANNELS) rowX() + numberW + gap + logoW + gap else rowX()

    private fun nameRoom(): Float = (width - padX - rowPad - nameX()).coerceAtLeast(0f)

    // ---- Drawing ---------------------------------------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (kind == Kind.CHANNELS && h > 0) rowH = floor((h - rowsTop - headTop) / LiveWidths.OV_CHANNEL_ROWS).coerceAtLeast(1f)
        names.fill(null)
        subFor.fill(null)
        headerCut = null
        headNameCut = null
        headSubCut = null
        val vis = visibleRows()
        firstRow = firstRow.coerceIn(0, (count - vis).coerceAtLeast(0))
        scrollY = firstRow * rowH
        pill = focus.toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        drawHeader(canvas)
        val w = width.toFloat()
        canvas.save()
        // Whole rows only: no cut row in the margin under the last one, and rows gliding in appear from a clean line.
        val bottom = rowsTop + visibleRows() * rowH
        canvas.clipRect(0f, rowsTop, w, bottom)
        val n = count
        if (n == 0) {
            if (emptyText.isNotEmpty()) {
                subPaint.color = LiveWidths.OV_TEXT_DIM
                canvas.drawText(emptyText, rowX(), baseline(rowsTop + rowH / 2, subPaint), subPaint)
            }
        } else {
            if (active && focus >= 0) {
                val pt = rowsTop + pill * rowH - scrollY
                rect.set(padX, pt + pillInset, w - padX, pt + rowH - pillInset)
                fill.color = t.focusFill
                canvas.drawRoundRect(rect, pillRadius, pillRadius, fill)
            }
            val first = (scrollY / rowH).toInt().coerceAtLeast(0)
            val last = (ceil((scrollY + bottom - rowsTop) / rowH).toInt() - 1).coerceAtMost(n - 1)
            val now = clock()
            for (r in first..last) {
                val top = rowsTop + r * rowH - scrollY
                when (kind) {
                    Kind.GROUPS -> drawGroup(canvas, r, top)
                    Kind.CHANNELS -> drawChannel(canvas, r, top, now)
                    Kind.PROGRAMMES -> drawProgramme(canvas, r, top, now)
                }
            }
        }
        canvas.restore()
    }

    private fun drawHeader(canvas: Canvas) {
        val w = width.toFloat()
        val cy = headTop + headH / 2
        val x = rowX()
        if (kind == Kind.PROGRAMMES) {
            val ch = headChannel
            if (ch != null) {
                val l = ch.logo?.let(logo)
                headPlates.draw(canvas, l, x, cy - headLogoH / 2, x + headLogoW, cy + headLogoH / 2)
                val tx = x + headLogoW + gap
                val room = (w - padX - rowPad - tx).coerceAtLeast(0f)
                headPaint.color = t.text
                val name = headNameCut ?: TextUtils.ellipsize("${ch.number}  ${ch.name}", headPaint, room, TextUtils.TruncateAt.END).also { headNameCut = it }
                canvas.drawText(name, 0, name.length, tx, baseline(cy - headH * 0.19f, headPaint), headPaint)
                val sub = headSubCut ?: TextUtils.ellipsize(headSub, headSubPaint, room, TextUtils.TruncateAt.END).also { headSubCut = it }
                canvas.drawText(sub, 0, sub.length, tx, baseline(cy + headH * 0.21f, headSubPaint), headSubPaint)
            }
        } else if (header.isNotEmpty()) {
            headPaint.color = t.text
            val cut = headerCut ?: TextUtils.ellipsize(header, headPaint, (w - 2 * x).coerceAtLeast(0f), TextUtils.TruncateAt.END).also { headerCut = it }
            canvas.drawText(cut, 0, cut.length, x, baseline(cy, headPaint), headPaint)
        }
        fill.color = if (glass) GlassStyle.HAIRLINE else CLASSIC_RULE
        canvas.drawRect(padX, rowsTop - hairline, w - padX, rowsTop, fill)
    }

    private fun drawGroup(canvas: Canvas, r: Int, top: Float) {
        if (r == marqueeRow) return
        val onPill = active && r == focus
        namePaint.typeface = nameFace(r)
        namePaint.color = if (onPill || r == mark) t.accent else t.text
        val name = names[r] ?: TextUtils.ellipsize(labels[r], namePaint, nameRoom(), TextUtils.TruncateAt.END).also { names[r] = it }
        canvas.drawText(name, 0, name.length, rowX(), baseline(top + rowH / 2, namePaint), namePaint)
    }

    private fun drawChannel(canvas: Canvas, r: Int, top: Float, now: Long) {
        val ch = channels[r]
        val onPill = active && r == focus
        val key = keys[r]
        val playing = key == playingKey
        val nameCy = top + rowH * LiveWidths.OV_NAME_AT
        val subCy = top + rowH * LiveWidths.OV_SUB_AT
        val x = rowX()
        // The number and the logo: centred in the row (and so in the white pill), the number in its own column.
        val lcy = top + rowH / 2
        numberPaint.color = if (onPill) t.accent else t.text
        canvas.drawText(numbers[r], x + numberW / 2, baseline(lcy, numberPaint), numberPaint)

        val lx = x + numberW + gap
        val l = ch.logo?.let(logo)
        if (l != null || glass) {
            plates.draw(canvas, l, lx, lcy - logoH / 2, lx + logoW, lcy + logoH / 2)
        } else {
            fill.color = LOGO_EMPTY
            rect.set(lx, lcy - logoH / 2, lx + logoW, lcy + logoH / 2)
            canvas.drawRoundRect(rect, t.dpf(4f), t.dpf(4f), fill)
        }

        val nx = nameX()
        val room = nameRoom()
        // The name's own room (the ▶ after the playing one's), as the scrolling copy over it has ([nameBox]).
        val ownRoom = nameRoomOf(r)
        var nameEnd = nx + ownRoom
        if (r != marqueeRow) {
            namePaint.typeface = t.regular
            namePaint.color = if (onPill || playing) t.accent else t.text
            val name = names[r] ?: TextUtils.ellipsize(ch.name, namePaint, ownRoom, TextUtils.TruncateAt.END)
                .also { names[r] = it; nameW[r] = namePaint.measureText(it, 0, it.length) }
            canvas.drawText(name, 0, name.length, nx, baseline(nameCy, namePaint), namePaint)
            nameEnd = nx + nameW[r]
        }
        // The ▶ after the playing channel's name (after the room its name scrolls in, while it scrolls).
        if (playing) {
            val mx = nameEnd + markerGap
            val s = t.dpf(5f)
            marker.reset()
            marker.moveTo(mx, nameCy - s); marker.lineTo(mx + s * 1.6f, nameCy); marker.lineTo(mx, nameCy + s); marker.close()
            fill.color = t.accent
            canvas.drawPath(marker, fill)
        }

        val p = nowOf(key)?.takeIf { it.isOn(now) }
        val madeFor: Any = p ?: NONE
        if (subFor[r] !== madeFor) {
            subFor[r] = madeFor
            val words = p?.title?.takeIf { it.isNotEmpty() } ?: if (p == null) NO_INFO else UNTITLED
            subs[r] = TextUtils.ellipsize(words, subPaint, room, TextUtils.TruncateAt.END)
        }
        subPaint.color = when {
            onPill -> t.accent
            p == null -> LiveWidths.OV_TEXT_DIM
            else -> t.textMuted
        }
        val sub = subs[r] ?: ""
        canvas.drawText(sub, 0, sub.length, nx, baseline(subCy, subPaint), subPaint)

        if (p != null) {
            val py = subCy + subPaint.textSize / 2 + progressGap
            rect.set(nx, py, nx + room, py + progressH)
            fill.color = if (onPill) LiveWidths.OV_TRACK_ON_PILL else LiveWidths.OV_TRACK
            canvas.drawRoundRect(rect, progressH / 2, progressH / 2, fill)
            rect.right = nx + maxOf(room * OverlayRules.progress(p.start, p.stop, now), progressH)
            fill.color = t.accent
            canvas.drawRoundRect(rect, progressH / 2, progressH / 2, fill)
        }
    }

    private fun drawProgramme(canvas: Canvas, r: Int, top: Float, now: Long) {
        val p = programmes[r]
        val cy = top + rowH / 2
        val x = rowX()
        val past = p.stop <= now
        val color = when {
            r == mark -> t.accent
            past -> LiveWidths.OV_TEXT_DIM
            else -> t.text
        }
        subPaint.color = if (r == mark) t.accent else if (past) LiveWidths.OV_TEXT_DIM else t.textMuted
        canvas.drawText(times[r], x, baseline(cy, subPaint), subPaint)
        namePaint.typeface = if (r == mark) t.semibold else t.regular
        namePaint.color = color
        val tx = x + timeW
        val room = (width - padX - rowPad - tx).coerceAtLeast(0f)
        // Cut once, in the face it's drawn in (the one on now is semibold).
        val title = names[r] ?: TextUtils.ellipsize(p.title.ifEmpty { UNTITLED }, namePaint, room, TextUtils.TruncateAt.END).also { names[r] = it }
        canvas.drawText(title, 0, title.length, tx, baseline(cy, namePaint), namePaint)
    }

    private fun baseline(cy: Float, p: Paint): Float = cy - (p.descent() + p.ascent()) / 2

    override fun onDetachedFromWindow() {
        motion.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val NO_INFO = "No information"
        const val UNTITLED = "Untitled"
        /** Classic: the rule under a header (the guide's). */
        const val CLASSIC_RULE = 0x2EFFFFFF
        /** Classic: where a channel without a logo would have one (the guide's). */
        const val LOGO_EMPTY = 0x1FFFFFFF
        /** A programme line made for no programme on now. */
        val NONE = Any()
    }
}
