package app.fireplayer.live.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.OperationCanceledException
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.fireplayer.live.ChannelKey
import app.fireplayer.live.LiveDb
import app.fireplayer.live.LiveSearch
import app.fireplayer.live.LiveSearchRules
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Glass
import app.fireplayer.ui.KEEP_COLORS
import app.fireplayer.ui.PageRow
import app.fireplayer.ui.Spring
import app.fireplayer.vod.ui.VodSizes

/**
 * Live TV search's results on the Search page, under its bar: "Channels", a row of the guide's logo plates
 * ([LogoView]) with each name beneath (the focused tile at the row's left edge, as on the app's other rows; a name too
 * long for its tile starts scrolling [VodSizes.LIVE_MARQUEE_DELAY_MS] after focus comes to it), then "Programmes", a
 * list of rows in the programme cells' glass (the app's row: [PageRow], white when focused) with the channel's logo,
 * the title, "channel · start–end" and a LIVE mark while on now. A section shows only when it has results.
 *
 * [search] asks on [LiveSearch.worker] (the page's bar already waits for typing to pause); words that change cancel
 * the older search. Views are made once and kept (at most [LiveSearch.MAX_CHANNELS] tiles and
 * [LiveSearch.MAX_PROGRAMMES] rows); new results only bind them. Moving through them is translation only.
 */
@SuppressLint("ViewConstructor")
class LiveSearchView(context: Context, private val t: Tokens, private val x: Int) : FrameLayout(context) {

    /** Up from the channels (or the programmes with no channels): the bar. */
    var onUp: (() -> Unit)? = null
    /** Left from the first tile or from a row: the rail. */
    var onLeft: (() -> Unit)? = null
    /** OK on a channel, or on a programme on now: it plays full screen on Live TV. */
    var onPlay: ((ChannelKey) -> Unit)? = null
    /** OK on a programme still to come: the guide on its channel ([at]: its start). */
    var onGuide: ((ChannelKey, Long) -> Unit)? = null
    /** OK held on a channel: its small window (full name, the guide's channel options). */
    var onHold: ((LiveSearch.ChannelHit, View) -> Unit)? = null
    /** An answer came (or the words were cleared): the page shows the history or these, says if nothing was found. */
    var onAnswered: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val db = LiveDb.get(context)
    private val logos = LogoCache(t.dp(VodSizes.LIVE_LOGO_PX)) { _, _ -> refreshLogos() }
    private val press = PressHold(ViewConfiguration.getLongPressTimeout().toLong())

    /** The words the results are for ("": none). */
    var query = ""
        private set
    /** Whether the answer for [query] is in. */
    var answered = true
        private set
    private var result: LiveSearch.Result? = null
    private var serial = 0
    private var cancel: CancellationSignal? = null
    private var released = false
    /** The channels' names, read once for this visit ([warm]); written on the search thread, which also reads it. */
    @Volatile private var names: LiveSearch.Names? = null
    private var namesAsked = false

    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    private val channelsHead = head(CHANNELS_HEAD)
    private val strip = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
    private val tilesLine = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
    private val programmesHead = head("Programmes")
    /** Under "Programmes" while the channels show and the guide is still being read (it takes a few seconds). */
    private val guideNote = t.label(context, VodSizes.NOTE).apply { text = VodSizes.SEARCHING_NOTE; visibility = View.GONE }
    private var guidePending = false
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val tiles = ArrayList<Tile>()
    private val rows = ArrayList<Row>()
    /** The tile chosen last (focus comes back to it from the programmes). */
    private var chosenTile = 0
    private val tileStep = t.dp(VodSizes.LIVE_TILE_W + VodSizes.LIVE_TILE_GAP)
    private val tileSpring = Spring { tilesLine.translationX = -it }
    private val pageSpring = Spring { content.translationY = -it }

    init {
        clipChildren = true
        content.setPadding(x, 0, 0, 0)
        content.addView(channelsHead, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, t.dp(VodSizes.LIVE_HEAD_H)))
        strip.addView(tilesLine, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(strip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, t.dp(VodSizes.LIVE_TILE_H + VodSizes.LIVE_NAME_TOP + VodSizes.LIVE_NAME_H),
        ).apply { topMargin = t.dp(VodSizes.LIVE_ROW_GAP) })
        content.addView(programmesHead, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, t.dp(VodSizes.LIVE_HEAD_H)).apply {
            topMargin = t.dp(VodSizes.LIVE_SECTION_GAP)
        })
        content.addView(guideNote, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = t.dp(VodSizes.LIVE_ROW_GAP)
        })
        content.addView(list, LinearLayout.LayoutParams(t.dp(VodSizes.tabHeaderW()), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = t.dp(VodSizes.LIVE_ROW_GAP)
        })
        addView(content, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        show(null)
    }

    /**
     * The results are measured at their full height, as a scrolling list is: they are usually taller than the page,
     * and measured to fit it the rows past its bottom were given no room (the list was cut short and moving down it
     * scrolled everything away).
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        content.measure(
            MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
    }

    private fun head(words: String) = t.label(context, VodSizes.LIVE_HEAD).apply {
        text = words
        gravity = Gravity.CENTER_VERTICAL
    }

    // ---- Searching ------------------------------------------------------------------------------------------------

    /** The results for [words], asked now (blank: none). An older search still running is cancelled. */
    /** Reads the channels' names ahead (Live TV search chosen): the first search then finds channels at once. */
    fun warm() {
        if (namesAsked || released) return
        namesAsked = true
        LiveSearch.worker.execute {
            names = try {
                LiveSearch(db).names()
            } catch (e: RuntimeException) {
                null
            }
        }
    }

    fun search(words: String) {
        val q = words.trim()
        if (q == query && (answered || q.isNotEmpty())) return
        query = q
        if (q.isNotEmpty()) setHead(CHANNELS_HEAD)
        val my = ++serial
        cancel?.cancel()
        cancel = null
        guidePending = false
        if (q.isEmpty()) {
            answered = true
            show(null)
            onAnswered?.invoke()
            return
        }
        answered = false
        warm()
        val signal = CancellationSignal().also { cancel = it }
        val now = System.currentTimeMillis()
        LiveSearch.worker.execute {
            if (signal.isCanceled) return@execute
            val r = try {
                // The channels show as soon as they're found; the programmes join them when the guide has been read.
                LiveSearch(db).find(q, now, signal, { first ->
                    main.post {
                        if (my != serial || released || answered) return@post
                        guidePending = true
                        show(first)
                        // The page hears of it too (its "Searching..." gives way to these).
                        onAnswered?.invoke()
                    }
                }, names)
            } catch (e: OperationCanceledException) {
                return@execute
            } catch (e: RuntimeException) {
                // A database being replaced by an update, say: nothing found this time (no words or addresses logged).
                Log.w(TAG, "Live search failed: ${e.javaClass.simpleName}")
                LiveSearch.Result(q, emptyList(), emptyList())
            }
            main.post {
                if (my != serial || released) return@post
                answered = true
                guidePending = false
                show(r)
                onAnswered?.invoke()
            }
        }
    }

    /**
     * The channels watched last ([hits]), under "Channel History", while nothing is typed (the search page over
     * Multiview, [OverlayRules.searchStart]): the same tiles as found channels, picked the same way.
     */
    fun showHistory(hits: List<LiveSearch.ChannelHit>) {
        query = ""
        ++serial
        cancel?.cancel()
        cancel = null
        guidePending = false
        answered = true
        setHead(HISTORY_HEAD)
        show(if (hits.isEmpty()) null else LiveSearch.Result("", hits, emptyList()))
    }

    private fun setHead(words: String) {
        if (channelsHead.text.toString() != words) channelsHead.text = words
    }

    /** Whether any result shows. */
    fun hasResults(): Boolean = result?.isEmpty == false

    /** No more answers (the page hid or went); the channel history shown in their place goes too. */
    fun clear() {
        if (query.isEmpty() && result != null) show(null) else search("")
    }

    fun release() {
        released = true
        cancel?.cancel()
        main.removeCallbacksAndMessages(null)
        for (tile in tiles) tile.stopMarquee()
        tileSpring.stop()
        pageSpring.stop()
        logos.clear()
        names = null
    }

    private fun show(r: LiveSearch.Result?) {
        val hadFocus = hasFocus()
        // The programmes joining channels already shown (the same search's second answer): the viewer may be among
        // the tiles by now, so the chosen tile, the row's place and the focus stay as they are.
        val joining = r != null && result?.query == r.query && result?.programmes.isNullOrEmpty() &&
            result?.channels?.map { it.key } == r.channels.map { it.key } && r.channels.isNotEmpty()
        result = r
        val channels = r?.channels.orEmpty()
        val programmes = r?.programmes.orEmpty()
        // Each tile at its own fixed width: left to wrap, the ones past the screen's edge are given no room at all and
        // pile up on one another when the row moves along.
        while (tiles.size < channels.size) Tile().also {
            tiles += it
            tilesLine.addView(it, LinearLayout.LayoutParams(t.dp(VodSizes.LIVE_TILE_W), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        while (rows.size < programmes.size) Row().also { rows += it; list.addView(it.view) }
        tiles.forEachIndexed { i, tile -> tile.bind(channels.getOrNull(i)) }
        rows.forEachIndexed { i, row -> row.bind(programmes.getOrNull(i)) }
        val hasChannels = channels.isNotEmpty()
        val hasProgrammes = programmes.isNotEmpty()
        channelsHead.visibility = if (hasChannels) View.VISIBLE else View.GONE
        strip.visibility = if (hasChannels) View.VISIBLE else View.GONE
        // The guide still being read: its heading already there, "Searching..." in the list's place.
        val waiting = guidePending && hasChannels && !hasProgrammes
        programmesHead.visibility = if (hasProgrammes || waiting) View.VISIBLE else View.GONE
        guideNote.visibility = if (waiting) View.VISIBLE else View.GONE
        list.visibility = if (hasProgrammes) View.VISIBLE else View.GONE
        (programmesHead.layoutParams as LinearLayout.LayoutParams).topMargin = if (hasChannels) t.dp(VodSizes.LIVE_SECTION_GAP) else 0
        visibility = if (r == null || r.isEmpty) View.GONE else View.VISIBLE
        if (joining) return
        chosenTile = 0
        tileSpring.snap(0f)
        pageSpring.snap(0f)
        // New results under focus: the first of them keeps it; none left, the bar has it.
        if (hadFocus && !focusFirst()) onUp?.invoke()
    }

    // ---- Focus ----------------------------------------------------------------------------------------------------

    /** Down from the bar: the chosen channel, else the first programme. */
    fun focusFirst(): Boolean {
        if (!hasResults()) return false
        return focusTile(chosenTile) || focusRow(0)
    }

    private fun shownTiles(): Int = result?.channels?.size ?: 0
    private fun shownRows(): Int = result?.programmes?.size ?: 0

    private fun focusTile(i: Int): Boolean {
        if (i !in 0 until shownTiles()) return false
        return tiles[i].plate.requestFocus()
    }

    private fun focusRow(i: Int): Boolean {
        if (i !in 0 until shownRows()) return false
        return rows[i].view.requestFocus()
    }

    private fun onTileFocused(i: Int) {
        press.cancel()
        chosenTile = i
        tileSpring.glideTo((i * tileStep).toFloat())
        pageSpring.glideTo(0f)
    }

    private fun onRowFocused(row: Row) {
        press.cancel()
        // The focused row in view: the list moves up just as far as it needs.
        val bottom = list.top + row.view.bottom + t.dp(VodSizes.LIVE_ROW_GAP)
        pageSpring.glideTo(maxOf(0, bottom - height).toFloat())
    }

    private fun ok(event: KeyEvent, onPick: () -> Unit, onHold: (() -> Unit)?): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN ->
                if (press.down(event.repeatCount, event.isLongPress, event.eventTime - event.downTime)) onHold?.invoke()
            KeyEvent.ACTION_UP -> if (press.up(event.isCanceled)) onPick()
        }
        return true
    }

    private fun isOk(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    /** Logos arrive one by one, many in a burst: the tiles and rows are gone through once a frame, not once a logo. */
    private var logosDue = false
    private val logosNow = Runnable {
        logosDue = false
        for (tile in tiles) tile.refreshLogo()
        for (row in rows) row.refreshLogo()
    }

    private fun refreshLogos() {
        if (logosDue || released) return
        logosDue = true
        main.post(logosNow)
    }

    fun applyLook() {
        for (row in rows) row.applyLook()
        for (tile in tiles) tile.plate.invalidate()
    }

    // ---- A channel's tile -----------------------------------------------------------------------------------------

    /** The logo plate (focusable; a white ring while focused) and the name under it, which scrolls when too long. */
    private inner class Tile : LinearLayout(context) {
        val plate = object : FrameLayout(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = VodSizes.RING_COLOR }
            private val shape = RectF()

            override fun dispatchDraw(canvas: Canvas) {
                super.dispatchDraw(canvas)
                if (!isFocused) return
                val ring = t.dpf(VodSizes.RECENT_FOCUS_RING)
                paint.strokeWidth = ring
                shape.set(ring / 2f, ring / 2f, width - ring / 2f, height - ring / 2f)
                val r = t.dpf(VodSizes.LIVE_TILE_RADIUS)
                canvas.drawRoundRect(shape, r, r, paint)
            }
        }
        val logo = LogoView(context).apply { pad(VodSizes.LIVE_TILE_PAD, VodSizes.LIVE_TILE_PAD) }
        /** The name's window: a name wider than the tile (while it scrolls) is cut at the tile's edges. */
        private val nameBox = FrameLayout(context).apply {
            outlineProvider = ViewOutlineProvider.BOUNDS
            clipToOutline = true
        }
        val name: TextView = t.label(context, VodSizes.LIVE_NAME).apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        /**
         * The whole name on one line, as wide as its words (sized once, when the tile is given its channel): the copy
         * that scrolls. Showing it and moving it lays nothing out, so moving along the row never re-measures the page.
         */
        private val full: TextView = t.label(context, VodSizes.LIVE_NAME).apply {
            isSingleLine = true
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.INVISIBLE
        }
        /** How far the whole name has to travel to show its end (0: it fits, nothing scrolls). */
        private var travel = 0f
        /** In the tile, in place of a logo that isn't there (none listed, or none arrived): the channel's name. */
        private val standIn: TextView = t.label(context, VodSizes.LIVE_NAME).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            // Quiet by its colour, not the view's alpha (which would draw it through a picture of its own each frame).
            setTextColor((currentTextColor and 0x00FFFFFF) or ((255 * VodSizes.LIVE_STAND_IN_ALPHA).toInt() shl 24))
            tag = KEEP_COLORS
            visibility = View.INVISIBLE
            val pad = t.dp(VodSizes.LIVE_TILE_PAD)
            setPadding(pad, 0, pad, 0)
        }
        var hit: LiveSearch.ChannelHit? = null
        private val boxW = t.dp(VodSizes.LIVE_TILE_W)
        private val marquee = Runnable { startMarquee() }
        private val showStandIn = Runnable {
            val h = hit ?: return@Runnable
            if (h.logo?.let { logos.get(it) } != null) return@Runnable
            standIn.text = h.name
            standIn.visibility = View.VISIBLE
        }

        init {
            orientation = VERTICAL
            clipChildren = false
            plate.isFocusable = true
            plate.setWillNotDraw(false)
            plate.addView(logo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            plate.addView(standIn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(plate, LayoutParams(boxW, t.dp(VodSizes.LIVE_TILE_H)))
            nameBox.addView(name, FrameLayout.LayoutParams(boxW, ViewGroup.LayoutParams.MATCH_PARENT))
            nameBox.addView(full, FrameLayout.LayoutParams(boxW, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(nameBox, LayoutParams(boxW, t.dp(VodSizes.LIVE_NAME_H)).apply { topMargin = t.dp(VodSizes.LIVE_NAME_TOP) })
            plate.setOnFocusChangeListener { _, has ->
                plate.invalidate()
                if (has) {
                    onTileFocused(tiles.indexOf(this))
                    main.postDelayed(marquee, VodSizes.LIVE_MARQUEE_DELAY_MS)
                } else {
                    stopMarquee()
                }
            }
            plate.setOnKeyListener { _, keyCode, event -> key(keyCode, event) }
        }

        fun bind(h: LiveSearch.ChannelHit?) {
            stopMarquee()
            main.removeCallbacks(showStandIn)
            standIn.visibility = View.INVISIBLE
            hit = h
            visibility = if (h == null) View.GONE else View.VISIBLE
            val lp = layoutParams as? LinearLayout.LayoutParams
            if (lp != null && lp.rightMargin != t.dp(VodSizes.LIVE_TILE_GAP)) {
                lp.rightMargin = t.dp(VodSizes.LIVE_TILE_GAP)
                layoutParams = lp
            }
            if (h == null) return
            if (name.text.toString() != h.name) {
                name.text = h.name
                full.text = h.name
                val textW = full.paint.measureText(h.name)
                travel = Marquee.distance(textW, boxW.toFloat())
                val w = maxOf(boxW, kotlin.math.ceil(textW).toInt())
                if (full.layoutParams.width != w) {
                    full.layoutParams.width = w
                    full.requestLayout()
                }
            }
            refreshLogo()
            main.postDelayed(showStandIn, VodSizes.LIVE_STAND_IN_AFTER_MS)
        }

        fun refreshLogo() {
            val url = hit?.logo
            val l = url?.let { logos.get(it) }
            logo.set(l, plate = true)
            if (l != null) {
                main.removeCallbacks(showStandIn)
                standIn.visibility = View.INVISIBLE
            }
        }

        private fun key(keyCode: Int, event: KeyEvent): Boolean {
            if (isOk(keyCode)) return ok(event, { hit?.let { onPlay?.invoke(it.key) } }, { hit?.let { onHold?.invoke(it, plate) } })
            if (event.action != KeyEvent.ACTION_DOWN) return keyCode in ARROWS
            val i = tiles.indexOf(this)
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (!focusTile(i - 1)) onLeft?.invoke()
                KeyEvent.KEYCODE_DPAD_RIGHT -> focusTile(i + 1)
                KeyEvent.KEYCODE_DPAD_UP -> onUp?.invoke()
                KeyEvent.KEYCODE_DPAD_DOWN -> focusRow(0)
                else -> return false
            }
            return true
        }

        /** The whole name, when it doesn't fit: one pass to its end, a pause, back to its start, again while focused. */
        private fun startMarquee() {
            if (!plate.isFocused || travel <= 0f) return
            name.visibility = View.INVISIBLE
            full.translationX = 0f
            full.visibility = View.VISIBLE
            full.animate().translationX(-travel).setStartDelay(0)
                .setDuration(Marquee.durationMs(travel, t.dpf(VodSizes.LIVE_MARQUEE_DP_S)))
                .setInterpolator(null)
                .withEndAction {
                    main.postDelayed({
                        if (!plate.isFocused) return@postDelayed
                        full.translationX = 0f
                        main.postDelayed(marquee, VodSizes.LIVE_MARQUEE_DELAY_MS)
                    }, VodSizes.LIVE_MARQUEE_HOLD_MS)
                }.start()
        }

        fun stopMarquee() {
            main.removeCallbacks(marquee)
            full.animate().cancel()
            full.translationX = 0f
            if (full.visibility == View.VISIBLE) {
                full.visibility = View.INVISIBLE
                name.visibility = View.VISIBLE
            }
        }
    }

    // ---- A programme's row ----------------------------------------------------------------------------------------

    /** The app's row in the programme cells' glass: the channel's logo, the title over "channel · start–end", LIVE. */
    private inner class Row {
        val view = PageRow(context, null, TextView(context), Glass.Kind.CELL_BAR)
        private val text = view.text
        val logo = LogoView(context)
        val mark: TextView = t.label(context, VodSizes.LIVE_MARK).apply {
            text = "LIVE"
            tag = KEEP_COLORS
            gravity = Gravity.CENTER
            setPadding(t.dp(VodSizes.LIVE_MARK_PAD_X), 0, t.dp(VodSizes.LIVE_MARK_PAD_X), 0)
            background = GradientDrawable().apply {
                cornerRadius = t.dpf(VodSizes.LIVE_MARK_H / 2f)
                setColor(t.accent)
            }
        }
        var hit: LiveSearch.ProgrammeHit? = null

        init {
            view.minimumHeight = t.dp(VodSizes.LIVE_ROW_H)
            text.isSingleLine = false
            text.maxLines = 2
            text.ellipsize = TextUtils.TruncateAt.END
            view.addView(logo, 0, LinearLayout.LayoutParams(t.dp(VodSizes.LIVE_ROW_LOGO_W), t.dp(VodSizes.LIVE_ROW_LOGO_H)).apply {
                marginEnd = t.dp(VodSizes.LIVE_ROW_LOGO_GAP)
            })
            view.addView(mark, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, t.dp(VodSizes.LIVE_MARK_H)))
            view.setOnFocusChangeListener { _, has -> if (has) onRowFocused(this) }
            view.setOnKeyListener { _, keyCode, event -> key(keyCode, event) }
        }

        fun applyLook() = view.applyLook()

        fun bind(h: LiveSearch.ProgrammeHit?) {
            hit = h
            view.visibility = if (h == null) View.GONE else View.VISIBLE
            val lp = view.layoutParams as? LinearLayout.LayoutParams
            if (lp != null && lp.height != t.dp(VodSizes.LIVE_ROW_H)) {
                lp.height = t.dp(VodSizes.LIVE_ROW_H)
                lp.bottomMargin = t.dp(VodSizes.LIVE_ROW_GAP)
                view.layoutParams = lp
            }
            if (h == null) return
            val fmt = android.text.format.DateFormat.getTimeFormat(context)
            val line = "${h.channel.name} · ${fmt.format(java.util.Date(h.start))}–${fmt.format(java.util.Date(h.stop))}"
            text.text = SpannableStringBuilder(h.title).append('\n').append(line).apply {
                setSpan(RelativeSizeSpan(SUB_SIZE), length - line.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            mark.visibility = if (LiveSearchRules.onNow(h.start, h.stop, System.currentTimeMillis())) View.VISIBLE else View.GONE
            refreshLogo()
        }

        fun refreshLogo() {
            logo.set(hit?.channel?.logo?.let { logos.get(it) }, plate = true)
        }

        private fun key(keyCode: Int, event: KeyEvent): Boolean {
            if (isOk(keyCode)) return ok(event, {
                hit?.let {
                    // On now (or begun while the results showed): it plays; still to come: the guide.
                    if (it.start <= System.currentTimeMillis()) {
                        onPlay?.invoke(it.channel.key)
                    } else {
                        onGuide?.invoke(it.channel.key, it.start)
                    }
                }
            }, null)
            if (event.action != KeyEvent.ACTION_DOWN) return keyCode in ARROWS
            val i = rows.indexOf(this)
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> if (!focusRow(i - 1) && !focusTile(chosenTile)) onUp?.invoke()
                KeyEvent.KEYCODE_DPAD_DOWN -> focusRow(i + 1)
                KeyEvent.KEYCODE_DPAD_LEFT -> onLeft?.invoke()
                KeyEvent.KEYCODE_DPAD_RIGHT -> Unit
                else -> return false
            }
            return true
        }
    }

    /** A long channel name's scrolling, pure (unit-tested). */
    object Marquee {
        /** How far the name moves to show its end: 0 when it fits. */
        fun distance(textW: Float, boxW: Float): Float = (textW - boxW).coerceAtLeast(0f)

        /** How long that takes at [pxPerS], ms (never under a quarter of a second). */
        fun durationMs(distance: Float, pxPerS: Float): Long =
            if (pxPerS <= 0f) MIN_MS else maxOf(MIN_MS, (distance / pxPerS * 1000f).toLong())

        /** Whether the name should be scrolling [sinceFocusMs] after focus came to it. */
        fun scrolls(textW: Float, boxW: Float, sinceFocusMs: Long): Boolean =
            distance(textW, boxW) > 0f && sinceFocusMs >= VodSizes.LIVE_MARQUEE_DELAY_MS

        private const val MIN_MS = 250L
    }

    private companion object {
        const val TAG = "FirePlayerLive"
        const val SUB_SIZE = 0.82f
        const val CHANNELS_HEAD = "Channels"
        const val HISTORY_HEAD = "Channel History"
        val ARROWS = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
    }
}
