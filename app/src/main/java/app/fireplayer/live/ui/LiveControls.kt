package app.fireplayer.live.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import app.fireplayer.R
import app.fireplayer.data.Prefs
import android.view.Gravity
import app.fireplayer.live.Channel
import app.fireplayer.live.ChannelKey
import app.fireplayer.live.LiveSearch
import app.fireplayer.vod.ui.VodSizes
import app.fireplayer.live.Programme
import app.fireplayer.player.FpsMeter
import app.fireplayer.player.MediaSpec
import app.fireplayer.playback.PlayerHost
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Glass
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The full-screen live controls (res/layout/live_controls.xml), built to the VOD player's measurements so both
 * players read as one: the same top block, pods (and the same Settings › Look › Info pods choices), time line, round
 * buttons and pills, and names that appear under a button only while it's selected.
 *
 * Left: TV Guide and Channel History, main controls the size of the VOD player's Play. While the channel is paused or behind live, Play/Pause and Go live come first
 * and push them along. Right: Captions and Favorite. Down swaps them for the extra row under the chevron, as the VOD
 * player's icon row: Multiview, External PiP (dimmed: not built yet), Audio, Audio delay, Display size, Screen
 * dimmer, Sleep timer and Settings, each with its value under it. Up brings the buttons back.
 */
class LiveControls(context: Context, private val t: Tokens) : FrameLayout(context) {

    interface Actions {
        fun onGuide()
        fun onHistory()
        /** Captions: its window, with focus back on [from] (the button) when it closes. */
        fun onCaptions(from: View)
        fun onFavorite()
        fun onPlayPause()
        fun onGoLive()
        /** An extra-row button ([AUDIO], [DISPLAY], …) was pressed; [from] is the button, for focus to come back to. */
        fun onMore(key: String, from: View)
    }

    var actions: Actions? = null

    private val prefs = Prefs(context)

    // Inflated first: the view properties below look into it as they're initialised.
    init {
        LayoutInflater.from(context).inflate(R.layout.live_controls, this, true)
    }
    private val top: View = findViewById(R.id.live_top)
    private val bottom: View = findViewById(R.id.live_bottom)
    private val scrimTop: View = findViewById(R.id.live_scrim_top)
    private val scrimBottom: View = findViewById(R.id.live_scrim_bottom)
    private val logo: LogoBadge = findViewById(R.id.live_logo)
    private val channelName: TextView = findViewById(R.id.live_channel)
    private val title: TextView = findViewById(R.id.live_title)
    private val schedule: TextView = findViewById(R.id.live_schedule)
    private val next: TextView = findViewById(R.id.live_next)
    private val clock: TextView = findViewById(R.id.live_clock)
    private val chipsRow: ViewGroup = findViewById(R.id.live_chips)
    private val chipResolution: TextView = findViewById(R.id.live_chip_resolution)
    private val chipFps: TextView = findViewById(R.id.live_chip_fps)
    private val chipFormat: TextView = findViewById(R.id.live_chip_format)
    private val chipAudio: TextView = findViewById(R.id.live_chip_audio)
    private val chipSource: TextView = findViewById(R.id.live_chip_source)
    private val startTime: TextView = findViewById(R.id.live_start)
    private val endTime: TextView = findViewById(R.id.live_end)
    private val line: ProgrammeLine = findViewById(R.id.live_line)

    private val btnPlay: ImageButton = findViewById(R.id.live_btn_play)
    private val goLiveGroup: View = findViewById(R.id.live_go_live_group)
    private val btnGoLive: ImageButton = findViewById(R.id.live_btn_go_live)
    val guideButton: ImageButton = findViewById(R.id.live_btn_guide)
    private val guideLabel: View = findViewById(R.id.live_guide_label)
    private val btnHistory: ImageButton = findViewById(R.id.live_btn_history)
    private val btnCaptions: View = findViewById(R.id.live_btn_cc)
    private val captionsValue: TextView = findViewById(R.id.live_cc_value)
    private val btnFavorite: ImageButton = findViewById(R.id.live_btn_favorite)
    private val favoriteLabel: TextView = findViewById(R.id.live_favorite_label)
    private val mainRow: View = findViewById(R.id.live_main_row)
    private val moreRow: LinearLayout = findViewById(R.id.live_more_row)
    private val arrowDown: View = findViewById(R.id.live_arrow_down)
    private val arrowUp: View = findViewById(R.id.live_arrow_up)

    /** An extra-row button: the whole item (what takes focus), its round icon (the look's surface) and its value. */
    private class MoreItem(val view: View, val icon: View, val value: TextView)
    private val moreItems = LinkedHashMap<String, MoreItem>()

    /** Info only (after a channel change) versus the full controls. */
    var controlsShown = false
        private set

    /** The extra row is showing in place of the buttons. */
    var moreShown = false
        private set

    /** Play/Pause and Go live are showing (the channel is paused or behind live). */
    var catchUpShown = false
        private set

    private var playlistName = ""
    private var programme: Programme? = null
    /** With no programme on: the stretch of time the line stands for ([ProgrammeSlot]), and the next programme's start. */
    private var slot: ProgrammeSlot.Span? = null
    private var nextStart: Long? = null

    init {
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        visibility = GONE
        // As the VOD player: circles grow 1.08, pills 1.04, and the name fades in under the selected one.
        focusEffect(btnPlay, Tokens.GROW_CIRCLE, null)
        focusEffect(btnGoLive, Tokens.GROW_CIRCLE, findViewById(R.id.live_go_live_label))
        focusEffect(guideButton, Tokens.GROW_CIRCLE, guideLabel)
        focusEffect(btnHistory, Tokens.GROW_CIRCLE, findViewById(R.id.live_history_label))
        focusEffect(btnCaptions, Tokens.GROW_PILL, findViewById(R.id.live_cc_label))
        focusEffect(btnFavorite, Tokens.GROW_CIRCLE, favoriteLabel)
        btnPlay.setOnClickListener { actions?.onPlayPause() }
        btnGoLive.setOnClickListener { actions?.onGoLive() }
        guideButton.setOnClickListener { actions?.onGuide() }
        btnHistory.setOnClickListener { actions?.onHistory() }
        btnCaptions.setOnClickListener { actions?.onCaptions(it) }
        btnFavorite.setOnClickListener { actions?.onFavorite() }
        // Left stops at the first button rather than wandering off the controls; right at the last.
        listOf(btnPlay, btnGoLive, guideButton, btnHistory, btnCaptions, btnFavorite).forEach { if (it.id == View.NO_ID) it.id = generateViewId() }
        buildMoreRow()
        // Each of the two rows that swap holds all its selected button draws (glow, shadow, growth): fading as a whole,
        // a row would otherwise cut them off flat at its edge until the fade ends.
        val button = resources.getDimensionPixelSize(R.dimen.player_main_button)
        Glass.roomForFocus(mainRow, button, Tokens.GROW_CIRCLE, sides = true)
        Glass.roomForFocus(moreRow, button, Tokens.GROW_CIRCLE, sides = false)
        applyLook()
    }

    /**
     * The extra row, built as the VOD player builds its icon row (item_more: a round icon, its value under it, the
     * whole item growing a little when selected). External PiP is dimmed and passed over by the D-pad,
     * as a settings row that can't be used is ([app.fireplayer.ui.SidePanel.row] with enabled = false).
     */
    private fun buildMoreRow() {
        fun add(key: String, icon: Int, name: String, enabled: Boolean = true) {
            val v = LayoutInflater.from(context).inflate(R.layout.item_more, moreRow, false)
            val iconView = v.findViewById<ImageView>(R.id.more_icon)
            iconView.setImageResource(icon)
            v.contentDescription = name
            val value = v.findViewById<TextView>(R.id.more_value)
            value.text = name
            if (enabled) {
                v.setOnClickListener { actions?.onMore(key, v) }
                focusEffect(v, MORE_GROW, null)
            } else {
                v.isFocusable = false
                v.isClickable = false
                v.alpha = DIMMED
            }
            moreRow.addView(v)
            moreItems[key] = MoreItem(v, iconView.parent as View, value)
        }
        add(MULTIVIEW, R.drawable.ic_multiview, "Multiview")
        add(PIP, R.drawable.ic_pip, "External PiP", enabled = false)
        add(AUDIO, R.drawable.ic_audio, "Audio")
        add(AUDIO_DELAY, R.drawable.ic_audio_delay, "Audio delay")
        add(SUB_STYLE, R.drawable.ic_sub_style, "Subtitle style")
        add(DISPLAY, R.drawable.ic_display, "Display size")
        add(DIM, R.drawable.ic_dim, "Screen dimmer")
        add(SLEEP, R.drawable.ic_sleep, "Sleep timer")
        add(SETTINGS, R.drawable.ic_settings, "Settings")
    }

    /** The value shown under an extra-row button. */
    fun setMoreValue(key: String, text: String) {
        moreItems[key]?.value?.let { if (it.text.toString() != text) it.text = text }
    }

    /** The app's one focus growth ([Tokens.focusEffect]), shared with the title page's buttons. */
    private fun focusEffect(button: View, scale: Float, label: View?) = Tokens.focusEffect(button, scale, label)

    /** The look's surfaces, exactly as the VOD player gives its own (Glass.background keeps sizes). */
    fun applyLook() {
        for (b in listOf(btnPlay, btnGoLive, guideButton, btnHistory, btnFavorite)) Glass.background(b, Glass.Kind.CIRCLE)
        Glass.background(btnCaptions, Glass.Kind.PILL)
        // The extra row's round icons, as the VOD player's icon row.
        for (item in moreItems.values) Glass.background(item.icon, Glass.Kind.MORE_ICON)
        for (chip in listOf(chipResolution, chipFps, chipFormat, chipAudio, chipSource)) Glass.background(chip, Glass.Kind.CHIP)
        chipsRow.clipChildren = !Glass.on
        (chipsRow as? ViewGroup)?.clipToPadding = !Glass.on
    }

    // ---- Content --------------------------------------------------------------------------------------------------

    fun setChannel(channel: Channel, now: Programme?, nextUp: Programme?, playlistName: String, channelLogo: Logo?, clockNow: Long) {
        val tf = DateFormat.getTimeFormat(context)
        channelName.text = channel.name
        // Large, on a plate of its own beside the channel's lines (a dark logo shows over a dark picture too).
        logo.set(channelLogo)
        logo.visibility = if (channelLogo != null) VISIBLE else GONE
        title.text = now?.title?.takeIf { it.isNotBlank() } ?: channel.name
        schedule.text = if (now != null) "${tf.format(Date(now.start))} – ${tf.format(Date(now.stop))}" else ""
        schedule.visibility = if (now != null) VISIBLE else GONE
        next.text = nextUp?.let { "Next: ${it.title} · ${tf.format(Date(it.start))}" } ?: ""
        next.visibility = if (nextUp != null) VISIBLE else GONE
        btnFavorite.setImageResource(if (channel.favorite) R.drawable.ic_star_filled else R.drawable.ic_star)
        val favText = if (channel.favorite) "Remove from Favorites" else "Add to Favorites"
        btnFavorite.contentDescription = favText
        this.playlistName = playlistName
        setProgramme(now, nextUp?.start, clockNow)
        tick(clockNow)
    }

    /**
     * The line: [p]'s progress, or with no programme on the whole line dulled, standing for the half hour the clock
     * is in (or up to [nextStart] when the next programme starts sooner), and saying so beside it.
     */
    private fun setProgramme(p: Programme?, nextStart: Long?, now: Long) {
        programme = p
        this.nextStart = nextStart
        line.dulled = p == null
        if (p != null) {
            slot = null
            startTime.text = DateFormat.getTimeFormat(context).format(Date(p.start))
            updateEnd(p, now)
        } else {
            showSlot(now)
        }
    }

    private fun showSlot(now: Long) {
        val s = ProgrammeSlot.of(now, nextStart, TimeZone.getDefault().getOffset(now))
        slot = s
        val tf = DateFormat.getTimeFormat(context)
        startTime.text = tf.format(Date(s.start))
        endTime.text = tf.format(Date(s.end)) + " · " + NO_PROGRAMME
    }

    private fun updateEnd(p: Programme, now: Long) {
        val left = ((p.stop - now) / 60_000).coerceAtLeast(0)
        endTime.text = "${DateFormat.getTimeFormat(context).format(Date(p.stop))} · $left min left"
        line.fraction = ((now - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f)
    }

    /**
     * Clock and tracker, every few seconds while shown. True when what the line stands for is over (the programme
     * ended, or the half hour with none did): the page then looks up what's on now.
     */
    fun tick(now: Long): Boolean {
        clock.text = DateFormat.format("EEE, MMM d", now).toString() + " · " + DateFormat.getTimeFormat(context).format(Date(now))
        val p = programme
        if (p != null) {
            updateEnd(p, now)
            return now >= p.stop
        }
        val s = slot ?: return false
        if (now < s.end) return false
        showSlot(now)
        return true
    }

    /** What [setStatus] last showed: the status, the pods turned off and the playlist's name. */
    private var shownStatus: PlayerHost.Status? = null
    private var shownHidden: Set<String>? = null
    private var shownSource: String? = null

    /** Pods as the VOD player writes them, each hidden when turned off in Settings › Look › Info pods. */
    @OptIn(UnstableApi::class)
    fun setStatus(s: PlayerHost.Status) {
        val hidden = prefs.hiddenChips
        // The player says its status with every bit of its own news (a live window read again every few seconds, a
        // caption line): the same status, pods and playlist as last time have nothing to set again (each pod's text
        // set again asked for the whole screen to be laid out again).
        if (s == shownStatus && hidden == shownHidden && playlistName == shownSource) return
        shownStatus = s
        shownHidden = HashSet(hidden)
        shownSource = playlistName
        fun pod(chip: TextView, key: String, text: String?) {
            val shown = text?.takeIf { key !in hidden }
            chip.text = shown
            chip.visibility = if (shown == null) GONE else VISIBLE
        }
        val v = s.video
        val w = s.videoSize.width.takeIf { it > 0 } ?: v?.width ?: 0
        val h = s.videoSize.height.takeIf { it > 0 } ?: v?.height ?: 0
        pod(chipResolution, "quality", if (w > 0 && h > 0) "$w×$h" else null)
        val fps = v?.frameRate ?: Format.NO_VALUE.toFloat()
        pod(chipFps, "fps", if (fps > 0) fpsLabel(fps) else null)
        val spec = if (v != null || s.audio != null) MediaSpec.of(v, s.audio) else null
        pod(chipFormat, "format", spec?.pictureLine())
        pod(chipAudio, "sound", spec?.soundLine() ?: MediaSpec.channelsLabel(s.audio?.channelCount ?: Format.NO_VALUE))
        pod(chipSource, "source", playlistName.takeIf { it.isNotBlank() })
        val cc = s.captions.firstOrNull { it.selected }
        captionsValue.text = cc?.label ?: "Off"
        // As the VOD player's Audio item: the track playing, or just "Audio".
        setMoreValue(AUDIO, s.audioTracks.firstOrNull { it.selected }?.label ?: "Audio")
        setCatchUp(s.paused, s.behindLive)
    }

    private fun fpsLabel(fps: Float): String {
        val value = FpsMeter.snap(fps)
        val text = if (abs(value - value.roundToInt()) < 0.001f) value.roundToInt().toString()
            else "%.3f".format(Locale.US, value).trimEnd('0')
        return "$text FPS"
    }

    /**
     * Play/Pause and Go live while the channel is paused or behind live (they take the start of the row and move
     * TV Guide and Channel History along); gone once it's back at live.
     */
    private fun setCatchUp(paused: Boolean, behind: Boolean) {
        btnPlay.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        btnPlay.contentDescription = if (paused) "Play" else "Pause"
        val show = paused || behind
        if (show == catchUpShown) return
        catchUpShown = show
        val hadFocus = btnPlay.hasFocus() || btnGoLive.hasFocus()
        btnPlay.visibility = if (show) VISIBLE else GONE
        goLiveGroup.visibility = if (show) VISIBLE else GONE
        // Focus that was on a button that just went moves to TV Guide.
        if (!show && hadFocus && controlsShown) guideButton.requestFocus()
    }

    // ---- Visibility -----------------------------------------------------------------------------------------------

    /**
     * The full controls; focus on Play/Pause when it shows (paused), else on TV Guide, unless [focus] is given. A
     * [focus] in the extra row (a window opened from it closed) comes back to that row, as in the VOD player.
     */
    fun showControls(focus: View? = null) {
        dropHistory()
        animate().cancel()
        visibility = VISIBLE
        controlsShown = true
        top.visibility = VISIBLE
        bottom.visibility = VISIBLE
        scrimTop.visibility = VISIBLE
        scrimBottom.visibility = VISIBLE
        alpha = 1f
        val target = focus ?: firstButton()
        if (target.parent === moreRow) showMoreRow(target, animate = false) else showMainRow(focusRow = false, animate = false)
        target.requestFocus()
    }

    fun focusPlay() {
        if (catchUpShown && !moreShown) btnPlay.requestFocus()
    }

    /**
     * Only the channel and programme at the top (after a channel change): nothing takes focus. With [fadeInAfter], they
     * fade in after that many ms (the picture growing to full screen first) instead of showing at once.
     */
    fun showInfo(fadeInAfter: Long = -1L) {
        dropHistory()
        animate().cancel()
        if (fadeInAfter >= 0 && visibility != VISIBLE) {
            alpha = 0f
            animate().alpha(1f).setStartDelay(fadeInAfter).setDuration(Tokens.FADE_MS).withLayer().start()
        } else {
            alpha = 1f
        }
        visibility = VISIBLE
        controlsShown = false
        top.visibility = VISIBLE
        scrimTop.visibility = VISIBLE
        bottom.visibility = GONE
        scrimBottom.visibility = GONE
        // The buttons are what shows first next time, as the VOD player's controls do.
        showMainRow(focusRow = false, animate = false)
    }

    fun hideAll() {
        dropHistory()
        animate().cancel()
        alpha = 1f
        visibility = GONE
        controlsShown = false
        showMainRow(focusRow = false, animate = false)
    }

    // ---- Channel History: a row of the channels watched last, where the buttons were ----------------------------------

    /** OK on a channel of the history row. */
    var onHistoryPick: ((ChannelKey) -> Unit)? = null

    /** The history row is showing in place of the controls. */
    var historyShown = false
        private set

    /** The row has channels to move through (else it only says there are none). */
    val historyHasChannels: Boolean get() = historyRow?.hasResults() == true

    private var historyBox: FrameLayout? = null
    private var historyRow: LiveSearchView? = null
    private var historyNote: TextView? = null

    /** Made the first time Channel History is pressed: Live TV search's own row of tiles, over the buttons' gradient. */
    private fun historyBox(): FrameLayout {
        historyBox?.let { return it }
        val at = bottom.layoutParams as MarginLayoutParams
        val box = FrameLayout(context).apply {
            setBackgroundResource(R.drawable.scrim_bottom)
            clipChildren = false
            visibility = GONE
        }
        val row = LiveSearchView(context, t, at.marginStart).apply {
            onPlay = { key -> onHistoryPick?.invoke(key) }
        }
        val note = t.label(context, VodSizes.NOTE).apply {
            text = NO_HISTORY
            isFocusable = true
            visibility = GONE
        }
        box.addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = at.bottomMargin
        })
        box.addView(note, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            marginStart = at.marginStart
            bottomMargin = at.bottomMargin + t.dp(LiveWidths.HISTORY_NOTE_UP)
        })
        addView(box, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, scrimBottom.layoutParams.height, Gravity.BOTTOM))
        historyBox = box
        historyRow = row
        historyNote = note
        return box
    }

    /**
     * The channels watched last ([hits], the latest first) in place of the controls: logo tiles with each name under,
     * over the bottom gradient, the first one selected. None: a line saying so. It glides in ([LiveWidths.HISTORY_IN_MS]).
     */
    fun showHistory(hits: List<LiveSearch.ChannelHit>) {
        val box = historyBox()
        val row = historyRow ?: return
        val note = historyNote ?: return
        animate().cancel()
        alpha = 1f
        visibility = VISIBLE
        controlsShown = false
        historyShown = true
        showMainRow(focusRow = false, animate = false)
        row.showHistory(hits)
        note.visibility = if (hits.isEmpty()) VISIBLE else GONE
        box.animate().cancel()
        box.descendantFocusability = FOCUS_AFTER_DESCENDANTS
        box.visibility = VISIBLE
        box.alpha = 0f
        box.translationY = t.dpf(LiveWidths.HISTORY_SHIFT)
        // Focus first, then the controls go: it never has to look for somewhere else to be.
        focusHistory()
        top.visibility = GONE
        bottom.visibility = GONE
        scrimTop.visibility = GONE
        scrimBottom.visibility = GONE
        box.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(LiveWidths.HISTORY_IN_MS)
            .setInterpolator(HISTORY_EASE).withLayer().start()
        // A row made just now takes the selection once it has been laid out.
        post { if (historyShown && !box.hasFocus()) focusHistory() }
    }

    private fun focusHistory() {
        if (historyRow?.focusFirst() != true) historyNote?.requestFocus()
    }

    /** The history row glides away ([LiveWidths.HISTORY_OUT_MS]), leaving the plain picture (or what was shown since). */
    fun closeHistory() {
        if (!historyShown) return
        historyShown = false
        val box = historyBox ?: return
        // Nothing in a row on its way out takes the selection back.
        box.descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        box.animate().cancel()
        box.animate().alpha(0f).translationY(t.dpf(LiveWidths.HISTORY_SHIFT)).setStartDelay(0).setDuration(LiveWidths.HISTORY_OUT_MS)
            .setInterpolator(HISTORY_EASE).withLayer().withEndAction {
                box.visibility = GONE
                // Nothing else came up meanwhile (the channel's lines after a pick, the controls): all of it is away.
                if (!historyShown && !controlsShown && top.visibility != VISIBLE) visibility = GONE
            }.start()
    }

    /** The history row gone at once (the controls or the channel's lines show, or full screen is left). */
    private fun dropHistory() {
        if (!historyShown) return
        historyShown = false
        historyBox?.let {
            it.animate().cancel()
            it.descendantFocusability = FOCUS_BLOCK_DESCENDANTS
            it.visibility = GONE
        }
    }

    /** The page is going: the row's searches and logos with it. */
    fun release() {
        historyRow?.release()
    }

    // ---- The extra row (VOD player's showMoreRow / showMainRow / swapRows) -----------------------------------------

    /** The row's first button: Play/Pause while it shows, else TV Guide. */
    private fun firstButton(): View = if (catchUpShown) btnPlay else guideButton

    /** The extra row in place of the buttons; focus on [focus], else on its first button that can be used. */
    fun showMoreRow(focus: View? = null, animate: Boolean = true) {
        val changed = !moreShown
        moreShown = true
        swapRows(show = moreRow, hide = mainRow, showArrow = arrowUp, hideArrow = arrowDown, up = true, animate = animate && changed)
        val target = focus ?: moreItems.values.firstOrNull { it.view.isFocusable }?.view ?: return
        target.requestFocus()
        // The row glides in with its button already selected (no second change inside the fade).
        if (animate && changed && target.isFocused) Tokens.settleFocus(target, MORE_GROW, null)
    }

    /** The buttons again; [focusRow] puts focus on the first of them. */
    fun showMainRow(focusRow: Boolean = true, animate: Boolean = true) {
        val changed = moreShown
        moreShown = false
        swapRows(show = mainRow, hide = moreRow, showArrow = arrowDown, hideArrow = arrowUp, up = false, animate = animate && changed)
        if (!focusRow) return
        val first = firstButton()
        first.requestFocus()
        // The row glides in with its button already selected: white, grown and named, not turning so on the way.
        if (animate && changed && first.isFocused) Tokens.settleFocus(first, Tokens.GROW_CIRCLE, if (first === guideButton) guideLabel else null)
    }

    /**
     * Left from the extra row's first usable button goes to its last, right from the last to the first (the VOD
     * player's icon row). Only on a [fresh] press, so holding the D-pad stops at the end instead of spinning round.
     * True when the key was used here.
     */
    fun wrapMore(left: Boolean, fresh: Boolean): Boolean {
        if (!moreShown) return false
        val items = moreItems.values.map { it.view }.filter { it.visibility == VISIBLE && it.isFocusable }
        val at = items.indexOfFirst { it.isFocused }
        if (at < 0 || items.size < 2) return false
        if (!(left && at == 0 || !left && at == items.size - 1)) return false
        if (fresh) (if (left) items.last() else items.first()).requestFocus()
        return true
    }

    /**
     * Swaps the buttons and the extra row under the chevron as the VOD player does: the old row fades out drifting
     * the way the D-pad pointed while the new one glides in from the other side, in 220 ms.
     */
    private fun swapRows(show: View, hide: View, showArrow: View, hideArrow: View, up: Boolean, animate: Boolean) {
        val shift = resources.displayMetrics.density * 14f * (if (up) -1 else 1)
        for (v in listOf(show, hide, showArrow, hideArrow)) v.animate().cancel()
        show.visibility = VISIBLE
        showArrow.visibility = VISIBLE
        if (!animate) {
            for (v in listOf(show, showArrow)) { v.alpha = 1f; v.translationY = 0f }
            for (v in listOf(hide, hideArrow)) { v.visibility = GONE; v.alpha = 1f; v.translationY = 0f }
            return
        }
        val ease = DecelerateInterpolator(1.6f)
        if (show.alpha >= 1f) { show.alpha = 0f; show.translationY = -shift }
        if (showArrow.alpha >= 1f) showArrow.alpha = 0f
        show.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(220).setInterpolator(ease).withLayer().start()
        showArrow.animate().alpha(1f).setStartDelay(80).setDuration(160).setInterpolator(ease).start()
        hide.animate().alpha(0f).translationY(shift).setStartDelay(0).setDuration(160).setInterpolator(ease).withLayer().withEndAction {
            hide.visibility = GONE; hide.alpha = 1f; hide.translationY = 0f
        }.start()
        hideArrow.animate().alpha(0f).setStartDelay(0).setDuration(100).withEndAction {
            hideArrow.visibility = GONE; hideArrow.alpha = 1f
        }.start()
    }

    companion object {
        /** The VOD player's icon-row items grow this much when selected. */
        private const val MORE_GROW = 1.06f
        /** A button that can't be used yet, as a disabled settings row. */
        private const val DIMMED = 0.38f
        /** Beside the line while the channel has no programme on. */
        const val NO_PROGRAMME = "No programme information"
        /** In the history row's place when no other channel has been watched. */
        const val NO_HISTORY = "No other channels watched yet"
        private val HISTORY_EASE = DecelerateInterpolator(1.6f)

        // The extra row's buttons, in order.
        const val MULTIVIEW = "multiview"
        const val PIP = "pip"
        const val AUDIO = "audio"
        const val AUDIO_DELAY = "audioDelay"
        const val SUB_STYLE = "subStyle"
        const val DISPLAY = "display"
        const val DIM = "dim"
        const val SLEEP = "sleep"
        const val SETTINGS = "settings"
    }
}

/**
 * The thin blue programme progress line (VOD time line's 4dp track): drawn only, never focused or seekable. With no
 * programme to show the progress of it's [dulled]: the same blue the whole way, at [Tokens.NO_PROGRAMME_LINE_ALPHA].
 */
class ProgrammeLine @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var fraction = 0f
        set(v) { if (field == v) return; field = v; invalidate() }
    var dulled = false
        set(v) { if (field == v) return; field = v; invalidate() }
    private val density = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val accent = ContextCompat.getColor(context, R.color.accent)

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val h = 4f * density
        val cy = height / 2f
        r.set(0f, cy - h / 2, width.toFloat(), cy + h / 2)
        if (dulled) {
            p.color = accent
            p.alpha = (255 * Tokens.NO_PROGRAMME_LINE_ALPHA).toInt()
            canvas.drawRoundRect(r, h, h, p)
            return
        }
        p.color = 0x4DFFFFFF
        canvas.drawRoundRect(r, h, h, p)
        r.right = width * fraction
        p.color = accent
        canvas.drawRoundRect(r, h, h, p)
    }
}
