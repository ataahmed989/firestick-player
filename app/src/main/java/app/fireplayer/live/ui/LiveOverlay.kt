package app.fireplayer.live.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.fireplayer.live.Channel
import app.fireplayer.live.ChannelGroup
import app.fireplayer.live.ChannelKey
import app.fireplayer.live.GuideScope
import app.fireplayer.live.Playlist
import app.fireplayer.live.Programme
import app.fireplayer.live.ui.OverlayRules.Level
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Glass
import app.fireplayer.ui.GlassStyle
import app.fireplayer.vod.ui.VodSizes
import java.util.Date

/**
 * The channel list over the picture (the user's TiviMate photos 2 and 4): full screen's Left, and Multiview's way to
 * pick a channel. Its columns ([OverlayList]) are the guide's own rows on see-through glass (solid dark in Classic),
 * the picture playing full size behind them:
 *
 * - Full screen, Left: the playing channel's group (the list Channel ▲ / ▼ moves through), the playing channel
 *   selected; beside it the focused channel's programmes under its logo and name, and the programme on now's details
 *   at the top right. Left again: the playlist's groups, the channels beside them (the programmes go). Right steps back
 *   towards the picture, Back closes it all. OK on a channel plays it (the list stays, for browsing on); OK on the one
 *   playing closes the list. OK on a group: its channels.
 * - Picking for Multiview: the groups and the channels beside them, "Search" at the groups' top ([Listener.onSearch]).
 *
 * The rules are [OverlayRules]. Columns move by translation and alpha only, laid out once. A long group or channel name
 * scrolls a moment after the selection rests on it (one copy moved along, as Live TV search's tiles do; nothing laid
 * out). Its lists and listings are read behind the screen ([Io]); nothing runs while it's closed. It never takes
 * Android's focus: the page hands it the keys ([dispatchKey]).
 */
@SuppressLint("ViewConstructor")
class LiveOverlay(context: Context, private val t: Tokens, private val io: Io) : FrameLayout(context) {

    /** Reads for the list, each answered on the main thread. */
    interface Io {
        fun logo(url: String): Logo?
        /** [channel]'s group: the list it plays in (Channel ▲ / ▼'s), else its own group, else its playlist's channels. */
        fun groupOf(channel: Channel, then: (GuideScope, List<Channel>) -> Unit)
        fun channels(scope: GuideScope, then: (List<Channel>) -> Unit)
        fun groups(playlistId: String, then: (List<ChannelGroup>) -> Unit)
        fun programmes(channels: List<Channel>, from: Long, to: Long, then: (Map<ChannelKey, List<Programme>>) -> Unit)
    }

    interface Listener {
        /** OK on [channel], listed in [scope]. */
        fun onChannel(channel: Channel, scope: GuideScope?)
        /** OK on Search (picking only). */
        fun onSearch() {}
        /** Its keys closed it (Back, or Right from the channels in full screen); not [close]. */
        fun onClosed()
    }

    var isOpen = false
        private set

    /** Open to pick a channel for Multiview (no programmes; Search at the groups' top). */
    var picking = false
        private set

    var level = Level.CLOSED
        private set

    /** The channel playing (or, picking, the picture the pick is for): marked in the list. */
    var playingKey: ChannelKey?
        get() = channelsCol.playingKey
        set(v) { channelsCol.playingKey = v }

    private var listener: Listener? = null
    private var playlist: Playlist? = null
    private var entries: List<OverlayRules.Entry> = emptyList()
    /** The list the channels column shows (null: none yet). */
    private var shownScope: GuideScope? = null
    /** The search page is over the list: it waits, hidden. */
    private var searching = false
    private var serial = 0
    private var listSerial = 0
    private var backDown = false
    private val press = PressHold(ViewConfiguration.getLongPressTimeout().toLong())

    private val groupsW = t.dp(LiveWidths.OV_GROUPS_W)
    private val channelsW = t.dp(LiveWidths.OV_CHANNELS_W)
    private val programmesW = t.dp(LiveWidths.OV_PROGRAMMES_W)

    private val groupsCol = OverlayList(context, t, OverlayList.Kind.GROUPS)
    private val channelsCol = OverlayList(context, t, OverlayList.Kind.CHANNELS)
    private val programmesCol = OverlayList(context, t, OverlayList.Kind.PROGRAMMES)

    // The focused programme's details (photo 4's top right): one line each, fixed sizes, so new words only redraw them.
    private val details = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val detailTitle = t.label(context, LiveWidths.OV_DETAILS_TITLE_SP, t.semibold).apply {
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }
    private val detailTime = t.label(context, LiveWidths.OV_DETAILS_SP).apply { isSingleLine = true }
    private val detailBar = ProgressLine(context, t)
    private val detailLeft = t.label(context, LiveWidths.OV_DETAILS_SP, color = t.textMuted).apply { isSingleLine = true }
    private val detailDesc = t.label(context, LiveWidths.OV_DETAILS_SP, color = t.textMuted).apply {
        setLines(LiveWidths.OV_DETAILS_LINES)
        ellipsize = TextUtils.TruncateAt.END
    }
    private val timeFormat = DateFormat.getTimeFormat(context)

    /** The programme read for each row's channel (on now when read; looked at again by [tick]). */
    private val nowMap = HashMap<ChannelKey, Programme>()
    private val nowAsked = HashSet<ChannelKey>()
    /** Programme lists of channels focused lately. */
    private val kept = object : LinkedHashMap<ChannelKey, List<Programme>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ChannelKey, List<Programme>>?): Boolean = size > LiveWidths.OV_KEPT_LISTS
    }

    private val ease = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val box = RectF()

    /** The window the whole name moves in, over the selected row's name; the copy inside it is as wide as any name. */
    private val marqueeText = TextView(context).apply {
        isSingleLine = true
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setTextColor(t.accent)
    }
    private val marqueeLine = FrameLayout(context).apply {
        outlineProvider = ViewOutlineProvider.BOUNDS
        clipToOutline = true
        visibility = INVISIBLE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(marqueeText, LayoutParams(t.dp(MARQUEE_TEXT_DP), ViewGroup.LayoutParams.MATCH_PARENT))
    }
    private var marqueeCol: OverlayList? = null
    private var travel = 0f
    private val marqueeStart: Runnable = Runnable { startMarquee() }
    private val marqueeAgain: Runnable = Runnable {
        if (marqueeCol == null) return@Runnable
        marqueeText.translationX = 0f
        postDelayed(marqueeRun, VodSizes.LIVE_MARQUEE_DELAY_MS)
    }
    private val marqueeEnd: Runnable = Runnable { postDelayed(marqueeAgain, VodSizes.LIVE_MARQUEE_HOLD_MS) }
    private val marqueeRun: Runnable = Runnable {
        if (marqueeCol == null) return@Runnable
        marqueeText.animate().translationX(-travel).setStartDelay(0)
            .setDuration(LiveSearchView.Marquee.durationMs(travel, t.dpf(VodSizes.LIVE_MARQUEE_DP_S)))
            .setInterpolator(null).withEndAction(marqueeEnd).start()
    }

    init {
        visibility = GONE
        clipChildren = true
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val logo: (String) -> Logo? = { io.logo(it) }
        for (c in listOf(groupsCol, channelsCol, programmesCol)) c.logo = logo
        channelsCol.nowOf = { nowMap[it] }
        addView(programmesCol, LayoutParams(programmesW, ViewGroup.LayoutParams.MATCH_PARENT).apply { leftMargin = channelsW })
        addView(channelsCol, LayoutParams(channelsW, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(groupsCol, LayoutParams(groupsW, ViewGroup.LayoutParams.MATCH_PARENT))
        buildDetails()
        addView(details, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = channelsW + programmesW + t.dp(LiveWidths.OV_DETAILS_GAP)
            rightMargin = t.dp(LiveWidths.GAP)
            topMargin = t.dp(LiveWidths.OV_DETAILS_GAP)
        })
        addView(marqueeLine, LayoutParams(1, 1, Gravity.TOP or Gravity.START))
        applyLook()
    }

    private fun buildDetails() {
        val pad = t.dp(LiveWidths.OV_DETAILS_PAD)
        details.setPadding(pad, pad, pad, pad)
        details.addView(detailTitle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(detailTime, LinearLayout.LayoutParams(t.dp(LiveWidths.OV_DETAILS_TIME_W), ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(detailBar, LinearLayout.LayoutParams(t.dp(LiveWidths.OV_DETAILS_BAR_W), t.dp(LiveWidths.OV_DETAILS_BAR_H)))
            addView(detailLeft, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = t.dp(LiveWidths.OV_GAP) })
        }
        details.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = t.dp(LiveWidths.OV_DETAILS_LINE_GAP)
        })
        details.addView(detailDesc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = t.dp(LiveWidths.OV_DETAILS_LINE_GAP)
        })
    }

    /** The look: see-through glass over the picture, or solid dark (Classic). */
    fun applyLook() {
        val glass = Glass.on
        groupsCol.glass = glass
        channelsCol.glass = glass
        programmesCol.glass = glass
        groupsCol.setBackgroundColor(if (glass) LiveWidths.OV_TINT_GROUPS else LiveWidths.OV_CLASSIC_GROUPS)
        channelsCol.setBackgroundColor(if (glass) LiveWidths.OV_TINT_CHANNELS else LiveWidths.OV_CLASSIC_CHANNELS)
        programmesCol.setBackgroundColor(if (glass) LiveWidths.OV_TINT_PROGRAMMES else LiveWidths.OV_CLASSIC_PROGRAMMES)
        details.background = GradientDrawable().apply {
            setColor(if (glass) LiveWidths.OV_DETAILS_TINT else LiveWidths.OV_DETAILS_CLASSIC)
            cornerRadius = t.dpf(LiveWidths.OV_DETAILS_RADIUS)
            if (glass) setStroke(GlassStyle.hairlinePx(t.density).toInt().coerceAtLeast(1), GlassStyle.HAIRLINE)
        }
    }

    /** A logo landed: the rows showing it draw it. */
    fun logosChanged() {
        if (!isOpen) return
        channelsCol.invalidate()
        programmesCol.invalidate()
    }

    // ---- Opening and closing ---------------------------------------------------------------------------------------

    /**
     * Opens on [channel]'s group in [playlist] ([picking]: for Multiview, the groups first, Search at their top),
     * [channel] selected and marked.
     */
    fun open(channel: Channel, playlist: Playlist, picking: Boolean, listener: Listener) {
        if (isOpen) close(animate = false)
        val s = ++serial
        ++listSerial
        isOpen = true
        searching = false
        backDown = false
        press.cancel()
        this.listener = listener
        this.playlist = playlist
        this.picking = picking
        level = OverlayRules.opening(picking)
        shownScope = null
        nowMap.clear()
        nowAsked.clear()
        playingKey = channel.key
        entries = OverlayRules.entries(picking, playlist.id, emptyList())
        groupsCol.bold = if (picking) 0 else -1
        groupsCol.setHeader(playlist.name)
        groupsCol.setGroups(entries.map { it.label }, OverlayRules.rowOf(entries, null))
        groupsCol.mark = -1
        channelsCol.setHeader(playlist.name)
        channelsCol.setEmptyText(LOADING)
        channelsCol.setChannels(emptyList(), -1)
        programmesCol.setHeadChannel(channel, playlist.name)
        programmesCol.setEmptyText("")
        programmesCol.setProgrammes(emptyList(), -1)
        showDetails()
        io.groups(playlist.id) { groups ->
            if (s != serial) return@groups
            entries = OverlayRules.entries(picking, playlist.id, groups)
            // The groups' rows are made again: a name scrolling over one of them goes (it starts again on the new row).
            val scrolling = marqueeCol === groupsCol
            if (scrolling) stopMarquee()
            groupsCol.setGroups(entries.map { it.label }, OverlayRules.rowOf(entries, shownScope))
            groupsCol.mark = shownScope?.let { sc -> entries.indexOfFirst { it.scope == sc } } ?: -1
            shownScope?.let { channelsCol.setHeader(headerOf(it)) }
            if (scrolling || level == Level.GROUPS) scheduleMarquee()
        }
        val ls = listSerial
        io.groupOf(channel) { scope, list ->
            // A group picked meanwhile wins.
            if (s != serial || ls != listSerial || shownScope != null) return@groupOf
            // The groups' selection moves to it below: a group's name scrolling goes (showList asks again).
            if (marqueeCol === groupsCol) stopMarquee()
            showList(scope, list, channel.key)
            groupsCol.place(OverlayRules.rowOf(entries, scope))
        }
        visibility = VISIBLE
        layoutLevel(animate = false)
        animate().cancel()
        alpha = 0f
        translationX = -t.dpf(LiveWidths.OV_ENTER_DX.toFloat())
        animate().alpha(1f).translationX(0f).setDuration(LiveWidths.OV_SLIDE_MS).setInterpolator(ease).withLayer().withEndAction(null).start()
        removeCallbacks(tick)
        postDelayed(tick, LiveWidths.OV_TICK_MS)
        // The group may have been answered at once (the list Channel ▲ / ▼ move through): layoutLevel has just stopped
        // the scroll it asked for, so the selected long name is asked to scroll again here.
        scheduleMarquee()
    }

    /** Closes (fading away with [animate]); nothing more is read or answered. */
    fun close(animate: Boolean = true) {
        if (!isOpen) {
            // Still fading away: gone at once.
            if (!animate && visibility != GONE) {
                animate().cancel()
                hideNow.run()
            }
            return
        }
        isOpen = false
        searching = false
        level = Level.CLOSED
        ++serial
        ++listSerial
        listener = null
        backDown = false
        press.cancel()
        stopMarquee()
        removeCallbacks(preview)
        removeCallbacks(readProgrammes)
        removeCallbacks(tick)
        groupsCol.settle()
        channelsCol.settle()
        animate().cancel()
        if (animate && isShown) {
            animate().alpha(0f).translationX(-t.dpf(LiveWidths.OV_ENTER_DX.toFloat())).setDuration(LiveWidths.OV_SLIDE_MS)
                .setInterpolator(ease).withLayer().withEndAction(hideNow).start()
        } else {
            hideNow.run()
        }
    }

    /** Gone, and what it held let go of (a list of thousands of channels, the listings). */
    private val hideNow = Runnable {
        if (isOpen) return@Runnable
        visibility = GONE
        alpha = 1f
        translationX = 0f
        channelsCol.setChannels(emptyList(), -1)
        programmesCol.setProgrammes(emptyList(), -1)
        nowMap.clear()
        nowAsked.clear()
        kept.clear()
        shownScope = null
    }

    /** Its keys closed it: the one who opened it hears of it. */
    private fun closeByKey() {
        val l = listener
        close()
        l?.onClosed()
    }

    /** The search page opens over it: it waits, hidden, as it is. */
    fun hideForSearch() {
        if (!isOpen) return
        searching = true
        press.cancel()
        backDown = false
        stopMarquee()
        removeCallbacks(preview)
        removeCallbacks(readProgrammes)
        visibility = INVISIBLE
    }

    /** Back from the search page: the list as it was. */
    fun showAfterSearch() {
        if (!isOpen || !searching) return
        searching = false
        visibility = VISIBLE
        scheduleMarquee()
    }

    fun release() {
        close(animate = false)
        removeCallbacks(tick)
    }

    // ---- Keys ------------------------------------------------------------------------------------------------------

    /** Every key while it's open; true when used. Keys it has no use for are left to the page. */
    fun dispatchKey(event: KeyEvent): Boolean {
        if (!isOpen || searching) return false
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        when (code) {
            KeyEvent.KEYCODE_BACK -> {
                if (down) {
                    if (event.repeatCount == 0) backDown = true
                } else if (backDown) {
                    backDown = false
                    closeByKey()
                }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (down) {
                press.cancel()
                move(code == KeyEvent.KEYCODE_DPAD_DOWN, event.repeatCount > 0)
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) {
                press.cancel()
                if (event.repeatCount == 0) left()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) {
                press.cancel()
                if (event.repeatCount == 0) right()
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> when (event.action) {
                // Held, it does nothing (a short press acts on its release).
                KeyEvent.ACTION_DOWN -> press.down(event.repeatCount, event.isLongPress, event.eventTime - event.downTime)
                KeyEvent.ACTION_UP -> if (press.up(event.isCanceled)) ok()
            }
            else -> return false
        }
        return true
    }

    private fun activeCol(): OverlayList = if (level == Level.GROUPS) groupsCol else channelsCol

    private fun move(down: Boolean, held: Boolean) {
        val col = activeCol()
        val to = OverlayRules.step(col.focus, col.count, down, held)
        if (to < 0 || to == col.focus) return
        stopMarquee()
        col.moveTo(to, held)
        if (level == Level.GROUPS) {
            removeCallbacks(preview)
            postDelayed(preview, LiveWidths.OV_PREVIEW_MS)
        } else {
            askNow()
            channelFocused()
        }
        scheduleMarquee()
    }

    private fun left() {
        val next = OverlayRules.left(level)
        if (next == level) return
        groupsCol.place(OverlayRules.rowOf(entries, shownScope))
        setLevel(next)
    }

    private fun right() {
        when (OverlayRules.right(level, picking)) {
            Level.CLOSED -> closeByKey()
            Level.CHANNELS -> if (level == Level.GROUPS) {
                val e = entries.getOrNull(groupsCol.focus)
                enter(e?.scope ?: shownScope)
            }
            Level.GROUPS -> Unit
        }
    }

    private fun ok() {
        if (level == Level.GROUPS) {
            val e = entries.getOrNull(groupsCol.focus) ?: return
            if (e.isSearch) listener?.onSearch() else enter(e.scope)
            return
        }
        val ch = channelsCol.channels.getOrNull(channelsCol.focus) ?: return
        listener?.onChannel(ch, shownScope)
    }

    /** [scope]'s channels, the selection in them ([OverlayRules.okOnGroup]). */
    private fun enter(scope: GuideScope?) {
        removeCallbacks(preview)
        if (scope != null && scope != shownScope) load(scope)
        setLevel(OverlayRules.okOnGroup())
    }

    private fun setLevel(next: Level) {
        if (next == level) return
        level = next
        layoutLevel(animate = true)
        if (level == Level.CHANNELS) {
            askNow()
            channelFocused()
        } else {
            removeCallbacks(readProgrammes)
        }
        scheduleMarquee()
    }

    /** The columns where [level] puts them: the groups at the edge or away, the channels beside them, the programmes. */
    private fun layoutLevel(animate: Boolean) {
        stopMarquee()
        val gw = groupsW.toFloat()
        val showGroups = OverlayRules.showsGroups(level)
        val showProgrammes = OverlayRules.showsProgrammes(level, picking)
        groupsCol.active = level == Level.GROUPS
        channelsCol.active = level == Level.CHANNELS
        slide(groupsCol, if (showGroups) 0f else -gw, showGroups, animate, hideGroups)
        slide(channelsCol, OverlayRules.channelsX(level, gw), true, animate, null)
        slide(programmesCol, if (showProgrammes) 0f else gw, showProgrammes, animate, hideProgrammes)
        slide(details, if (showProgrammes) 0f else gw, showProgrammes, animate, hideDetails)
    }

    private val hideGroups = Runnable { if (!OverlayRules.showsGroups(level)) groupsCol.visibility = INVISIBLE }
    private val hideProgrammes = Runnable { if (!OverlayRules.showsProgrammes(level, picking)) programmesCol.visibility = INVISIBLE }
    private val hideDetails = Runnable { if (!OverlayRules.showsProgrammes(level, picking)) details.visibility = INVISIBLE }

    /** [v] to [x] (translation), shown or fading away ([hide] once it's gone); at once unless [animate]. */
    private fun slide(v: View, x: Float, shown: Boolean, animate: Boolean, hide: Runnable?) {
        v.animate().cancel()
        val alpha = if (shown) 1f else 0f
        if (!animate) {
            v.translationX = x
            v.alpha = alpha
            v.visibility = if (shown) VISIBLE else INVISIBLE
            return
        }
        if (shown && v.visibility != VISIBLE) {
            v.alpha = 0f
            v.visibility = VISIBLE
        }
        val a = v.animate().translationX(x).alpha(alpha).setDuration(LiveWidths.OV_SLIDE_MS).setInterpolator(ease)
        if (v.alpha != alpha) a.withLayer()
        a.withEndAction(if (shown) null else hide)
        a.start()
    }

    // ---- What the columns show -------------------------------------------------------------------------------------

    /** After the selection rests on a group: its channels beside it, as the guide's groups column previews them. */
    private val preview = Runnable {
        if (!isOpen || searching || level != Level.GROUPS) return@Runnable
        entries.getOrNull(groupsCol.focus)?.scope?.let { if (it != shownScope) load(it) }
    }

    private fun load(scope: GuideScope) {
        val s = ++listSerial
        groupsCol.mark = entries.indexOfFirst { it.scope == scope }
        io.channels(scope) { list ->
            if (s != listSerial || !isOpen) return@channels
            showList(scope, list, playingKey)
        }
    }

    private fun showList(scope: GuideScope, list: List<Channel>, focus: ChannelKey?) {
        // The channel rows are made again: a name scrolling over the old one goes (scheduled again below). A group's
        // name scrolling beside them carries on undisturbed.
        val restart = marqueeCol !== groupsCol
        if (marqueeCol === channelsCol) stopMarquee()
        shownScope = scope
        channelsCol.setHeader(headerOf(scope))
        channelsCol.setEmptyText(if (scope is GuideScope.Favorites) NO_FAVORITES else NO_CHANNELS)
        channelsCol.setChannels(list, OverlayRules.focusIn(list, focus) { it.key })
        groupsCol.mark = entries.indexOfFirst { it.scope == scope }
        askNow()
        if (level == Level.CHANNELS) channelFocused()
        if (restart) scheduleMarquee()
    }

    /** "Playlist · group", as the column's header. */
    private fun headerOf(scope: GuideScope): String {
        val pl = playlist?.name.orEmpty()
        val label = when (scope) {
            GuideScope.AllPlaylists -> ALL_PLAYLISTS
            else -> entries.firstOrNull { it.scope == scope }?.label
        } ?: return pl
        return if (pl.isEmpty()) label else "$pl · $label"
    }

    /** The programme on now for the rows on screen and those round them, read once per channel (and when it ends). */
    private fun askNow() {
        val list = channelsCol.channels
        if (list.isEmpty()) return
        val vis = channelsCol.visibleRows()
        val from = (channelsCol.firstRow - LiveWidths.OV_NOW_AROUND).coerceAtLeast(0)
        val to = minOf(list.size, channelsCol.firstRow + vis + LiveWidths.OV_NOW_AROUND)
        var need: ArrayList<Channel>? = null
        for (i in from until to) {
            val ch = list[i]
            if (nowAsked.add(ch.key)) (need ?: ArrayList<Channel>().also { need = it }).add(ch)
        }
        val asked = need ?: return
        val s = serial
        val now = System.currentTimeMillis()
        io.programmes(asked, now, now + 1) { map ->
            if (s != serial) return@programmes
            for (ch in asked) map[ch.key]?.firstOrNull { it.isOn(now) }?.let { nowMap[ch.key] = it }
            channelsCol.programmesChanged()
            if (level == Level.CHANNELS) showDetails()
        }
    }

    private fun focusedChannel(): Channel? = channelsCol.channels.getOrNull(channelsCol.focus)

    /** The selection rests on a channel: its header and details now, its programmes once it has rested a moment. */
    private fun channelFocused() {
        removeCallbacks(readProgrammes)
        if (picking) return
        val ch = focusedChannel()
        programmesCol.setHeadChannel(ch, playlist?.name.orEmpty())
        val list = ch?.let { kept[it.key] }
        if (list != null) {
            showProgrammes(list)
        } else {
            programmesCol.setEmptyText("")
            programmesCol.setProgrammes(emptyList(), -1)
            if (ch != null) postDelayed(readProgrammes, LiveWidths.OV_PROGRAMMES_AFTER_MS)
        }
        showDetails()
    }

    private val readProgrammes = Runnable {
        if (!isOpen || picking || searching || level != Level.CHANNELS) return@Runnable
        val ch = focusedChannel() ?: return@Runnable
        val s = serial
        val now = System.currentTimeMillis()
        io.programmes(listOf(ch), now - LiveWidths.OV_PROGRAMMES_BEFORE_MS, now + LiveWidths.OV_PROGRAMMES_AHEAD_MS) { map ->
            if (s != serial) return@programmes
            val list = map[ch.key].orEmpty()
            kept[ch.key] = list
            if (focusedChannel()?.key == ch.key && level == Level.CHANNELS) {
                showProgrammes(list)
                showDetails()
            }
        }
    }

    private fun showProgrammes(list: List<Programme>) {
        val now = System.currentTimeMillis()
        programmesCol.setEmptyText(if (list.isEmpty()) NO_INFO else "")
        programmesCol.setProgrammes(list, OverlayRules.onNow(list.size, now, { list[it].start }, { list[it].stop }))
    }

    /** The focused channel's programme on now, at the top right: title, times, progress, minutes left, description. */
    private fun showDetails() {
        if (picking) return
        val ch = focusedChannel()
        val now = System.currentTimeMillis()
        val p = ch?.let { c -> nowMap[c.key]?.takeIf { it.isOn(now) } ?: kept[c.key]?.firstOrNull { it.isOn(now) } }
        if (p == null) {
            setText(detailTitle, if (ch == null) "" else NO_INFO)
            setText(detailTime, "")
            setText(detailLeft, "")
            setText(detailDesc, "")
            detailBar.alpha = 0f
            return
        }
        setText(detailTitle, p.title.ifEmpty { ch.name })
        setText(detailTime, "${timeFormat.format(Date(p.start))} – ${timeFormat.format(Date(p.stop))}")
        detailBar.alpha = 1f
        detailBar.fraction = OverlayRules.progress(p.start, p.stop, now)
        setText(detailLeft, "${OverlayRules.minutesLeft(p.stop, now)} min")
        setText(detailDesc, p.description.orEmpty())
    }

    private fun setText(v: TextView, text: String) {
        if (v.text.toString() != text) v.text = text
    }

    /** Every [LiveWidths.OV_TICK_MS] while open: programmes that ended are read again, the progress lines move on. */
    private val tick = object : Runnable {
        override fun run() {
            if (!isOpen) return
            postDelayed(this, LiveWidths.OV_TICK_MS)
            if (searching) return
            val now = System.currentTimeMillis()
            val ended = nowMap.entries.filter { it.value.stop <= now }.map { it.key }
            for (k in ended) {
                nowMap.remove(k)
                nowAsked.remove(k)
            }
            askNow()
            channelsCol.programmesChanged()
            if (level == Level.CHANNELS && !picking) {
                focusedChannel()?.let { ch -> kept[ch.key]?.let { showProgrammes(it) } }
                showDetails()
            }
        }
    }

    // ---- A long name scrolling (Live TV search's marquee: one copy moved along, nothing laid out) ------------------

    private fun scheduleMarquee() {
        removeCallbacks(marqueeStart)
        if (isOpen && !searching) postDelayed(marqueeStart, VodSizes.LIVE_MARQUEE_DELAY_MS)
    }

    /** The selected name, when it doesn't fit: one pass to its end, a pause, back to its start, again while selected. */
    private fun startMarquee() {
        stopMarquee()
        if (!isOpen || searching) return
        val col = activeCol()
        val row = col.focus
        if (row < 0 || !col.active || col.kind == OverlayList.Kind.PROGRAMMES) return
        val name = col.fullName(row)
        marqueeText.setTextSize(TypedValue.COMPLEX_UNIT_PX, col.namePaint.textSize)
        marqueeText.typeface = col.nameFace(row)
        col.nameBox(row, box)
        travel = LiveSearchView.Marquee.distance(marqueeText.paint.measureText(name), box.width())
        if (travel <= 0f) return
        val w = box.width().toInt()
        val h = kotlin.math.ceil(box.height()).toInt()
        val lp = marqueeLine.layoutParams
        // Sized once per column width (its first long name); after that only moved.
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            marqueeLine.layoutParams = lp
        }
        marqueeLine.translationX = col.left + col.translationX + box.left
        marqueeLine.translationY = col.top + col.translationY + box.top
        marqueeText.text = name
        marqueeText.translationX = 0f
        marqueeLine.visibility = VISIBLE
        col.marqueeRow = row
        marqueeCol = col
        marqueeRun.run()
    }

    private fun stopMarquee() {
        removeCallbacks(marqueeStart)
        removeCallbacks(marqueeAgain)
        removeCallbacks(marqueeRun)
        marqueeText.animate().cancel()
        marqueeText.translationX = 0f
        marqueeCol?.marqueeRow = -1
        marqueeCol = null
        if (marqueeLine.visibility != INVISIBLE) marqueeLine.visibility = INVISIBLE
    }

    override fun onDetachedFromWindow() {
        stopMarquee()
        removeCallbacks(preview)
        removeCallbacks(readProgrammes)
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private companion object {
        /** The scrolling copy's width: longer than any name. */
        const val MARQUEE_TEXT_DP = 1600
        const val LOADING = "Loading…"
        const val NO_CHANNELS = "No channels"
        const val NO_FAVORITES = "No favorites in this playlist"
        const val NO_INFO = "No information"
        const val ALL_PLAYLISTS = "All playlists"
    }
}
