package app.fireplayer.vod.ui

import android.app.Activity
import android.text.format.DateFormat
import android.view.KeyEvent
import app.fireplayer.ui.FireDialog
import app.fireplayer.ui.ListEntry
import app.fireplayer.ui.LongList
import app.fireplayer.ui.SidePanel
import app.fireplayer.vod.Attempt
import app.fireplayer.vod.PlaybackSource
import app.fireplayer.vod.SourceList
import app.fireplayer.vod.play.PlayRules
import app.fireplayer.vod.play.SourcesModel
import java.util.Date

/**
 * The Sources window (design-brief.md §6.5, the user's layout of 2026-11): one merged list in the wide settings window,
 * made of the app's shared parts ([SidePanel]'s long list, its two-line rows, headings and notes). The same window opens
 * from a title page's Sources button and from the player (This film › Sources, Picture & sound › Sources). Answers
 * arriving later redraw it in place ([update]); focus stays on its row (rows are keyed). What it lists, and in what
 * order: [SourcesModel].
 *
 * OK on a row plays it ([onPick], from the resume point); a row not cached by the debrid service asks first. Right:
 * Details, where a film's 4K copy can be moved to the IMAX group or back to 4K ([move]). Hold OK: Play from beginning or
 * Details. "Show N" opens a kind in place. Back closes Details, then the window.
 */
class SourcesPanel(private val activity: Activity, private val panel: SidePanel) {

    private var list: SourceList? = null
    private var attempts: List<Attempt> = emptyList()
    private var playingKey: String? = null
    private var smooth = false
    private var next: (() -> PlaybackSource?)? = null
    private var move: ((PlaybackSource, Boolean) -> Unit)? = null
    private var server: () -> String? = { null }
    private var opened = HashSet<SourcesModel.Section>()
    private var items: List<SourcesModel.Item> = emptyList()
    private var onPick: ((PlaybackSource, Boolean) -> Unit)? = null
    private var session = -1
    private var held = false

    /** Whether this panel is the window showing now. */
    val isOpen: Boolean get() = panel.isOpen && panel.session == session

    /**
     * Opens the window titled [title] ("Sources", "Sources · S2 E5 "Peacekeepers"") over the screen, or over the
     * window showing ([sub]: Back returns to it). In the player, [smooth] says whether the copy playing plays without
     * stalling, [server] how fast its server is delivering (its Details' "Server"), and [next] what Next source plays.
     * [move] keeps a film's copy moved to 4K IMAX (true) or to 4K in its Details ([SourcesModel.moveLabel]).
     */
    fun open(
        title: String,
        list: SourceList?,
        attempts: List<Attempt> = emptyList(),
        playingKey: String? = null,
        sub: Boolean = false,
        onClose: (() -> Unit)? = null,
        smooth: Boolean = false,
        server: () -> String? = { null },
        next: (() -> PlaybackSource?)? = null,
        move: ((source: PlaybackSource, imax: Boolean) -> Unit)? = null,
        onPick: (source: PlaybackSource, fromBeginning: Boolean) -> Unit,
    ) {
        this.list = list
        this.attempts = attempts
        this.playingKey = playingKey
        this.smooth = smooth
        this.next = next
        this.move = move
        this.server = server
        this.onPick = onPick
        opened = HashSet()
        held = false
        val render: SidePanel.() -> Unit = { longList(entries()) }
        if (sub && panel.isOpen) panel.openSub(title, wide = true, render = render) else panel.open(title, onClose, wide = true, render = render)
        session = panel.session
    }

    /** A newer list or attempts: the rows redraw in place (focus kept). */
    fun update(list: SourceList?, attempts: List<Attempt> = this.attempts, playingKey: String? = this.playingKey) {
        this.list = list
        this.attempts = attempts
        this.playingKey = playingKey
        if (isOpen && !FireDialog.isShowing(activity)) panel.refresh()
    }

    private fun entries(): LongList {
        items = SourcesModel.items(list, android.os.SystemClock.elapsedRealtime(), opened, attempts, playingKey, smooth, next, ::time, ::day,
            VodSizes.SOURCES_LINE_CHARS, VodSizes.SOURCES_NOTE_CHARS)
        val shown = items
        return object : LongList {
            override val size: Int get() = shown.size

            override fun entry(index: Int): ListEntry {
                val it = shown[index]
                return when (it.kind) {
                    SourcesModel.Kind.NOTE -> ListEntry(it.key, it.label, kind = ListEntry.Kind.NOTE)
                    SourcesModel.Kind.HEADING -> ListEntry(it.key, it.label, kind = ListEntry.Kind.HEADING)
                    SourcesModel.Kind.SHOW_MORE -> ListEntry(it.key, it.label, faded = true)
                    SourcesModel.Kind.SOURCE -> ListEntry(it.key, it.label, it.value, chosen = it.chosen, note = it.note ?: "")
                }
            }

            override fun onClick(index: Int) {
                if (held) return
                val item = shown.getOrNull(index) ?: return
                when (item.kind) {
                    SourcesModel.Kind.SHOW_MORE -> item.section?.let { g ->
                        opened += g
                        panel.refresh()
                        // Focus on the first row it brought, where "Show N" was.
                        panel.focusEntry(index.coerceAtMost(items.size - 1))
                    }
                    SourcesModel.Kind.SOURCE -> item.source?.let { play(it, beginning = false) }
                    else -> Unit
                }
            }

            override fun onKey(index: Int, keyCode: Int, event: KeyEvent): Boolean {
                // Right on a copy: its Details (what its row leaves out).
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    val item = shown.getOrNull(index) ?: return false
                    val s = item.source ?: return false
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && !held) details(s, item.section)
                    return true
                }
                val ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                if (!ok) return false
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) held = false
                if (event.action == KeyEvent.ACTION_DOWN && (event.repeatCount > 0 || event.isLongPress)) {
                    if (!held) {
                        held = true
                        shown.getOrNull(index)?.let { item -> item.source?.let { holdMenu(it, item.section) } }
                    }
                    return true
                }
                if (event.action == KeyEvent.ACTION_UP && held) {
                    held = false
                    return true
                }
                return false
            }
        }
    }

    /** OK on a row: it plays; one not cached by the debrid service asks first (it would use a download slot). */
    private fun play(s: PlaybackSource, beginning: Boolean) {
        if (!SourcesModel.asksFirst(s)) {
            onPick?.invoke(s, beginning)
            return
        }
        FireDialog.ask(
            activity,
            "Your debrid service (Real-Debrid, TorBox...) has to download this file first. It can take a while and uses a download slot.",
            listOf(
                "Ask the debrid service to fetch it" to { onPick?.invoke(s, beginning) },
                "Not now" to {},
            ),
            focus = 1,
            fromPanel = true,
        )
    }

    /** Hold OK on a row: Play from beginning, Details, in the hold window beside the row (it's selected). */
    private fun holdMenu(s: PlaybackSource, section: SourcesModel.Section?) {
        FireDialog.hold(
            activity,
            // The row's own main line, so the window names the copy as the list did.
            mainLine(s),
            listOf(
                "Play from beginning" to { play(s, beginning = true) },
                "Details" to { details(s, section) },
            ),
            fromPanel = true,
        )
    }

    private fun mainLine(s: PlaybackSource): String {
        val l = list
        val film = l == null || SourcesModel.film(l)
        return PlayRules.mainLine(s, film = film, plan = l?.plan).ifEmpty { s.offers.firstOrNull()?.text?.take(60).orEmpty() }
    }

    /**
     * Details: what the row leaves out, and each add-on's own words (never a link), over the window titled as the row
     * names the copy; Back returns to it. A film's 4K copy has one more row, Move to IMAX group (or to 4K group): kept at
     * once ([move]), the list ranks again, and Back finds the row from a kind ([section]) under its new one, opened.
     */
    private fun details(s: PlaybackSource, section: SourcesModel.Section?) {
        val serverNow = if (s.key == playingKey) server() else null
        var copy = s
        panel.openSub(mainLine(s), wide = true) {
            facts(SourcesModel.details(copy, list?.plan, serverNow, ::day).map { (k, v) -> k to fact(v) })
            val l = list ?: return@openSub
            val keep = move ?: return@openSub
            val film = SourcesModel.film(l)
            val label = SourcesModel.moveLabel(copy, film) ?: return@openSub
            row(label) {
                val imax = label == SourcesModel.TO_IMAX
                copy = copy.copy(imaxMove = imax)
                keep(copy, imax)
                if (section != null) {
                    val to = SourcesModel.sectionOf(copy, film)
                    opened += to
                    backTo(SourcesModel.rowKey(to, copy))
                }
                setTitle(mainLine(copy))
                refresh()
            }
        }
    }

    private fun time(at: Long): String = DateFormat.getTimeFormat(activity).format(Date(at))

    private fun day(at: Long): String = DateFormat.format("MMM d", at).toString()
}
