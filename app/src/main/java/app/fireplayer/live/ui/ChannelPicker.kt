package app.fireplayer.live.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.fireplayer.live.Channel
import app.fireplayer.live.ChannelKey
import app.fireplayer.live.LiveDb
import app.fireplayer.live.LiveSearch
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Glass
import app.fireplayer.vod.data.SearchHistoryRules
import app.fireplayer.vod.ui.SearchBar
import app.fireplayer.vod.ui.VodSizes

/**
 * Multiview's search page (Search at the top of the channel list, [LiveOverlay]): Live TV search as the Search page has
 * it, the same bar ([SearchBar], the TV's keyboard on OK) and the same results ([LiveSearchView]: channels, then
 * programmes on them), at the same places, over Multiview on a see-through backdrop: the pictures (or the one ready
 * picture) show under it (the user's TiviMate photo 3). With nothing typed, the channel history shows under the bar
 * (Channel History's channels), the selection on it; the bar has to be pressed (OK) to type ([OverlayRules.searchStart]).
 * OK on a channel (or on a programme) picks its channel ([onPick]); Back ([Multiview] passes it on) closes it. Made the
 * first time it's needed; the results are made with its first opening.
 */
@SuppressLint("ViewConstructor")
class ChannelPicker(context: Context, private val t: Tokens) : FrameLayout(context) {

    /** OK on a channel, or on a programme (its channel). */
    var onPick: ((ChannelKey) -> Unit)? = null

    private val title = t.label(context, LiveWidths.MV_PICKER_TITLE_SP, t.semibold).apply { gravity = Gravity.CENTER_VERTICAL }
    private val bar = SearchBar(context, t)
    private val note = t.label(context, VodSizes.NOTE)
    private var results: LiveSearchView? = null
    private val x = t.dp(VodSizes.CONTENT_SCREEN_X)
    private val db = LiveDb.get(context)
    /** The channel history shown while nothing is typed (empty: none, or not read yet). */
    private var history: List<LiveSearch.ChannelHit> = emptyList()
    private var serial = 0

    var isOpen = false
        private set

    init {
        applyScrim()
        visibility = GONE
        clipChildren = false
        clipToPadding = false
        // Where the Search page's kind pills are: what the pick is for.
        addView(title, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, t.dp(VodSizes.SEARCH_KIND_H)).apply {
            leftMargin = x
            topMargin = t.dp(VodSizes.VIEWPORT_TOP)
        })
        // No history here: no trash button beside the bar.
        bar.trash.visibility = GONE
        addView(bar, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = x
            topMargin = t.dp(VodSizes.VIEWPORT_TOP + VodSizes.SEARCH_KIND_H + VodSizes.SEARCH_KIND_GAP)
        })
        addView(note, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = x
            topMargin = t.dp(VodSizes.searchListTop())
        })
        bar.onQuery = { words -> search(words) }
        bar.onSubmit = { words -> search(words) }
        bar.onArrow = { keyCode, _ -> if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) results?.focusFirst() }
    }

    private fun resultsView(): LiveSearchView = results ?: LiveSearchView(context, t, x).also { v ->
        results = v
        addView(v, indexOfChild(note) + 1, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = t.dp(VodSizes.searchListTop())
        })
        v.onUp = { bar.focusBar() }
        // The first tile stays selected (there's no rail here to go to).
        v.onLeft = {}
        v.onPlay = { key -> if (isOpen) onPick?.invoke(key) }
        v.onGuide = { key, _ -> if (isOpen) onPick?.invoke(key) }
        v.onAnswered = { showNote() }
    }

    /**
     * Opens with [heading] ("Add a channel to Multiview"…), the bar selected without the keyboard (OK on it brings
     * it); the channel history comes under it as soon as it's read, and the selection goes to it. The channels in
     * [shown] (Multiview's pictures) are left out of the history: picking one would only say it's there already.
     */
    fun open(heading: String, shown: Set<ChannelKey> = emptySet()) {
        title.text = heading
        bar.setText("")
        results?.clear()
        history = emptyList()
        isOpen = true
        visibility = VISIBLE
        // The channels' names are read now, for the first words typed.
        resultsView().warm()
        showNote()
        bar.focusBar()
        if (OverlayRules.KEYBOARD_ON_OPEN) post { if (isOpen && bar.hasFocusHere()) bar.showKeyboard() }
        val s = ++serial
        LiveSearch.worker.execute {
            val recent = try {
                OverlayRules.history(db.recent(), shown, LivePage.HISTORY_SIZE) { it.key }
            } catch (e: RuntimeException) {
                emptyList()
            }
            post { if (s == serial && isOpen) showHistory(recent) }
        }
    }

    /** The channel history read: under the bar while nothing is typed, the selection on it if it's still on the bar. */
    private fun showHistory(recent: List<Channel>) {
        history = recent.map { LiveSearch.ChannelHit(it.key, it.name, it.logo, it.groupId, it.favorite) }
        if (!OverlayRules.showsHistory(bar.text)) return
        val r = resultsView()
        r.showHistory(history)
        showNote()
        if (OverlayRules.searchStart(bar.text, history.size) == OverlayRules.SearchStart.HISTORY && bar.hasFocusHere()) r.focusFirst()
    }

    /** Closes (the keyboard too); no late answer comes. */
    fun close() {
        if (!isOpen) return
        isOpen = false
        ++serial
        history = emptyList()
        bar.cancel()
        results?.clear()
        // Hidden with the selection in it: Android lets go of the selection (nothing else in full screen takes it).
        visibility = GONE
    }

    fun release() {
        close()
        results?.release()
    }

    fun applyLook() {
        applyScrim()
        bar.applyLook()
        results?.applyLook()
    }

    /** See-through over the pictures; Classic, as dark as it was. */
    private fun applyScrim() = setBackgroundColor(if (Glass.on) LiveWidths.MV_PICKER_SCRIM else LiveWidths.MV_PICKER_SCRIM_CLASSIC)

    private fun search(words: String) {
        if (!isOpen) return
        val clean = SearchHistoryRules.clean(words)
        // Words cleared: the channel history again.
        if (OverlayRules.showsHistory(clean)) resultsView().showHistory(history) else resultsView().search(clean)
        showNote()
    }

    /** Under the bar: what to do, "Searching…", or that nothing was found (the results show in its place). */
    private fun showNote() {
        val r = results
        val text = when {
            r == null || r.query.isEmpty() -> if (history.isNotEmpty()) "" else HINT
            r.hasResults() -> ""
            !r.answered -> VodSizes.SEARCHING_NOTE
            else -> "No channels found for “${r.query}”"
        }
        note.text = text
        note.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    companion object {
        const val HINT = "Press OK on the bar to type a channel's name or a programme"
    }
}
