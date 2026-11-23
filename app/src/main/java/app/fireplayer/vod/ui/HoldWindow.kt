package app.fireplayer.vod.ui


import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.fireplayer.R
import app.fireplayer.live.ui.HoldPopup
import app.fireplayer.shell.Tokens
import app.fireplayer.vod.Card
import app.fireplayer.vod.Title
import app.fireplayer.vod.VodRepository
import app.fireplayer.vod.data.TitleMarks

/**
 * Films & Shows' small window (hold OK), one per tab page, used by its rows, its See all grid and its title pages: Live
 * TV's [HoldPopup] (one look and one behaviour), beside what was held. A poster's window ([openPoster]) has "+ Add to
 * list" (or "− Remove from list", chosen first), Mark watched and/or unwatched and, on Continue cards, Remove; the title
 * page opens it with its own buttons ([open]: an episode's marks, the (⋯) window). When it closes, focus goes back to
 * what was held (or, if a change took that away, its row's chosen card, else the page's own place).
 */
class HoldWindow(private val t: Tokens, private val root: ViewGroup, private val host: Host) {

    interface Host {
        val repo: VodRepository?
        fun showNotice(text: String)
        /** The viewer added [title] to My List or took it off, here: rows showing My List change at once. */
        fun onListChanged(title: Title, inList: Boolean)
        /** [id] left Continue Watching (Remove, Mark watched): its cards go from the continue rows now. */
        fun onLeftContinue(id: String)
        /** [title]'s Continue Watching card changed (Mark unwatched: back to 0:00): changed in place in its rows now. */
        fun onContinueChanged(title: Title)
        /** Focus back when what was held is gone: the page's own place. */
        fun takeFocus()
        /** The window opened or closed (the header's rotation holds still while it's open). */
        fun onHoldChanged()
    }

    val popup = HoldPopup(root.context, t)

    /** What the open window was opened from: the view focus goes back to, its row, and the row's listener. */
    var heldView: View? = null
        private set
    var heldRow: TitleRow? = null
        private set
    private var heldListener: TitleRow.Listener? = null
    private var fallback: (() -> Unit)? = null

    val isOpen: Boolean get() = popup.isOpen

    /** Puts the window in the page, above everything else in it (the page adds it last). */
    fun attach() {
        root.addView(popup, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT))
    }

    init {
        popup.onClose = { focusBack ->
            val back = heldView
            val row = heldRow
            val listener = heldListener
            val otherwise = fallback
            heldView = null
            heldRow = null
            heldListener = null
            fallback = null
            when {
                // Back on the card held (or, if a change took it away, the row's chosen card, else the page's).
                focusBack && back != null && back.isShown -> back.requestFocus()
                focusBack && row != null && row.visibility == View.VISIBLE && row.items.isNotEmpty() -> row.focusChosen()
                focusBack -> otherwise?.invoke() ?: host.takeFocus()
                // Something else took focus (the settings window): the held card shrinks back.
                row != null && row.focusedIndex() < 0 -> if (listener?.onRowLeft(row) == true) row.motion.shrink()
            }
            host.onHoldChanged()
        }
    }

    fun applyLook() = popup.applyLook()

    /**
     * Opens with [name] over [buttons], beside [anchor] (the grown card, or a button); [focusBack] gets focus back when
     * it closes, else [otherwise] (the page's place). [row]: the row it was opened in (null in See all and for a
     * button), whose card stays grown meanwhile ([listener] decides when it closes). [area]: where it may go, in the
     * page's coordinates.
     */
    fun open(
        name: CharSequence,
        buttons: List<HoldPopup.Button>,
        anchor: View,
        focusBack: View,
        area: RectF,
        row: TitleRow? = null,
        listener: TitleRow.Listener? = null,
        otherwise: (() -> Unit)? = null,
    ) {
        if (buttons.isEmpty()) return
        heldView = focusBack
        heldRow = row
        heldListener = listener
        fallback = otherwise
        popup.open(name, buttons, inRoot(anchor), area)
        host.onHoldChanged()
    }

    /**
     * A poster's (or a wide card's) window: + Add to list / − Remove from list, Mark watched and Mark unwatched (each
     * when it would change something: [TitleMarks.offer]) and, on Continue, Remove.
     */
    fun openPoster(
        card: Card,
        continueRow: Boolean,
        anchor: View,
        focusBack: View,
        area: RectF,
        row: TitleRow? = null,
        listener: TitleRow.Listener? = null,
        otherwise: (() -> Unit)? = null,
        /** A Continue card's window: "Title page" opens it (OK on the card plays straight away). */
        onTitlePage: (() -> Unit)? = null,
    ) {
        val r = host.repo ?: return
        val title = card.title
        val buttons = ArrayList<HoldPopup.Button>(5)
        if (onTitlePage != null) buttons += HoldPopup.Button("Title page", DETAILS_ICON) {
            close(fade = false, focusBack = false)
            onTitlePage()
        }
        buttons += listButton(title, r.inMyList(title.id))
        // From memory: a show played since its mark (or on its Continue card) isn't fully watched; part watched, both.
        val watched = TitleMarks.watched(title.kind, r.isWatched(title.id), r.lastPlayed(title), continueRow)
        val offer = TitleMarks.offer(watched, r.hasHistory(title))
        if (offer.watched) buttons += HoldPopup.Button("Mark watched", watchedIcon(false)) { markWatched(title, true) }
        if (offer.unwatched) buttons += HoldPopup.Button("Mark unwatched", watchedIcon(true)) { markWatched(title, false) }
        if (continueRow) buttons += HoldPopup.Button("Remove", REMOVE_ICON) { removeFromContinue(title) }
        open(title.name, buttons, anchor, focusBack, area, row, listener, otherwise)
    }

    /** Closes it ([HoldPopup.close]). */
    fun close(fade: Boolean = true, focusBack: Boolean = true) = popup.close(fade, focusBack)

    private fun listButton(title: Title, inList: Boolean) =
        // The sign is an icon now (the narrow list's options all have one place for it), as on the title page.
        HoldPopup.Button(
            if (inList) "Remove from list" else "Add to list",
            if (inList) R.drawable.ic_check else R.drawable.ic_add,
        ) { toggleList(title) }

    /** + Add to list / − Remove from list: changed at once (the rows too), and the window closes a moment later. */
    private fun toggleList(title: Title) {
        val r = host.repo ?: return
        val inList = !r.inMyList(title.id)
        setInList(title, inList)
        popup.update(0, listButton(title, inList))
        popup.closeAfter(VodSizes.LIST_CLOSE_MS)
    }

    /** Puts [title] on My List or takes it off (the window's button, the title page's (+)): the rows follow at once. */
    fun setInList(title: Title, inList: Boolean) {
        val r = host.repo ?: return
        r.setInMyList(title, inList)
        host.onListChanged(title, inList)
    }

    /**
     * Marks [title] (a show: the whole show) watched or not, closing the window. Mark watched also takes the title out
     * of Continue Watching (until it's played again): at once, like Remove. Mark unwatched clears its history; in
     * Continue Watching its card stays, back at 0:00, at once.
     */
    fun markWatched(title: Title, watched: Boolean) {
        val r = host.repo ?: return
        popup.close()
        r.setWatched(title, watched)
        if (watched) host.onLeftContinue(title.id) else host.onContinueChanged(title)
        host.showNotice(if (watched) MARKED else UNMARKED)
    }

    /** Remove: out of Continue Watching (its resume point stays), at once. */
    fun removeFromContinue(title: Title) {
        val r = host.repo ?: return
        popup.close()
        r.removeFromContinueWatching(title.id)
        host.onLeftContinue(title.id)
    }

    /** The page's area a window may use: inside the page by [VodSizes.HOLD_INSET], from [topDp] down. */
    fun area(topDp: Float): RectF {
        val inset = t.dpf(VodSizes.HOLD_INSET)
        return RectF(inset, t.dpf(topDp), root.width - inset, root.height - inset)
    }

    /** [v]'s rectangle as drawn (grown, moved), in the page's coordinates. */
    fun inRoot(v: View): RectF {
        val a = IntArray(2)
        v.getLocationInWindow(a)
        val b = IntArray(2)
        root.getLocationInWindow(b)
        val left = (a[0] - b[0]).toFloat()
        val top = (a[1] - b[1]).toFloat()
        return RectF(left, top, left + v.width * v.scaleX, top + v.height * v.scaleY)
    }

    companion object {
        const val MARKED = "Marked as watched"
        const val UNMARKED = "Marked as not watched"

        // The options' icons, the same wherever a hold window offers them (the list's sign is [listButton]'s).
        /** Title page, Details: an "i". */
        val DETAILS_ICON = R.drawable.ic_info
        /** Remove (from Continue Watching): a cross, as Settings › Rows' Remove. */
        val REMOVE_ICON = R.drawable.ic_close

        /** Mark watched (a tick), or for one [watched] already, Mark unwatched (back to the start). */
        fun watchedIcon(watched: Boolean): Int = if (watched) R.drawable.ic_restart else R.drawable.ic_check
    }
}
