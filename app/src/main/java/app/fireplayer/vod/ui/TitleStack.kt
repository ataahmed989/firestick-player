package app.fireplayer.vod.ui

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.SidePanel
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.Title
import app.fireplayer.vod.VodRepository

/**
 * The title pages open over one tab page ([VodPage]): the first opened from a poster in its rows or See all, each
 * further one from a poster in More Like This, stacked. Back goes to the page under it, and from the first back to the
 * poster it came from. At most [ALIVE] pages are kept built (the top one shown, the others paused with their
 * pictures let go); older ones are made again from the repository's stored details when Back returns to them, focus
 * where it was. A tab switch closes them all.
 *
 * Back here (VodPage passes it on after its small window): a show's season list closes; a wait for the add-ons ends;
 * with focus in a row of the page (or on its season dropdown), back to its buttons; from the buttons, the page closes.
 * A held Back acts once, on its release.
 */
class TitleStack(private val ctx: Context, private val t: Tokens, private val host: Host, private val container: FrameLayout) {

    interface Host {
        val repo: VodRepository?
        val hold: HoldWindow
        val sidePanel: SidePanel
        fun showNotice(text: String)
        fun focusRail()
        /**
         * The last page is about to close: the tab's own content shows again and, with [focusBack], focus goes back to
         * the poster the page was opened from (while the page still holds it).
         */
        fun restoreOrigin(focusBack: Boolean)
        /** The last page has gone. */
        fun onStackClosed()
        /** A film or an episode starts in the player, from a title page. */
        fun play(request: PlayRequest)
    }

    private class Entry(val title: Title, val soon: Boolean = false) {
        var page: TitlePage? = null
        var saved: TitlePage.Saved? = null
    }

    private val trail = TitleTrail<Entry>(ALIVE, KEPT)
    private var backDown = false
    private var running = true

    val isOpen: Boolean get() = !trail.isEmpty

    private val pageHost = object : TitlePage.Host {
        override val repo: VodRepository? get() = host.repo
        override val hold: HoldWindow get() = host.hold
        override val sidePanel: SidePanel get() = host.sidePanel
        override fun showNotice(text: String) = host.showNotice(text)
        override fun focusRail() = host.focusRail()
        override fun openNext(title: Title) = push(title)
        override fun play(request: PlayRequest) = host.play(request)
    }

    /** Opens [title]'s page: the first over the tab, or on top of the one showing. Focus goes into it. */
    fun open(title: Title, soon: Boolean = false) {
        if (isOpen) push(title) else first(title, soon)
    }

    private fun first(title: Title, soon: Boolean = false) {
        backDown = false
        container.visibility = View.VISIBLE
        val e = Entry(title, soon)
        e.page = build(title, soon)
        trail.push(e)
        e.page?.takeFocus()
    }

    private fun push(title: Title) {
        val top = trail.top ?: return first(title)
        top.saved = top.page?.saveFocus()
        val e = Entry(title)
        val page = build(title)
        e.page = page
        // Focus into the new page before the one under it is hidden (a hidden view holding focus hands it anywhere).
        page.takeFocus()
        top.page?.pause(hide = true)
        for (old in trail.push(e)) releasePage(old)
    }

    /** Back from the top page's buttons: the page under it, or the tab's poster. */
    fun pop() {
        if (trail.size <= 1) {
            closeAll(focusBack = true)
            return
        }
        val leaving = trail.pop() ?: return
        val back = trail.top ?: return
        val page = back.page ?: build(back.title, back.soon).also { back.page = it }
        page.resume(show = true)
        page.restoreFocus(back.saved)
        back.saved = null
        releasePage(leaving)
    }

    /** Closes every page: focus back on the poster first ([focusBack]), then the pages and their pictures go. */
    fun closeAll(focusBack: Boolean) {
        if (!isOpen) return
        backDown = false
        host.restoreOrigin(focusBack)
        for (e in trail.clear()) releasePage(e)
        container.removeAllViews()
        container.visibility = View.GONE
        host.onStackClosed()
    }

    /** Back on a title page (see the class's note); every Back is used up while a page is open. */
    fun dispatchKey(event: KeyEvent): Boolean {
        if (!isOpen || event.keyCode != KeyEvent.KEYCODE_BACK) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) backDown = true
            KeyEvent.ACTION_UP -> if (backDown) {
                backDown = false
                val page = trail.top?.page
                if (page != null && page.closeSeasonList()) Unit
                else if (page != null && page.cancelWait()) Unit
                else if (page != null && page.focusInRows()) page.backToButtons() else pop()
            }
        }
        return true
    }

    /** Into the top page (from the rail): where focus was in it. */
    fun takeFocus() {
        trail.top?.page?.takeFocusBack()
    }

    /** The screen stopped or the tab hid: the top page's loads, timers and pictures stop. */
    fun pause() {
        running = false
        trail.top?.page?.pause(hide = false)
    }

    fun resume() {
        running = true
        trail.top?.page?.resume(show = false)
    }

    fun release() {
        for (e in trail.clear()) releasePage(e)
        container.removeAllViews()
        container.visibility = View.GONE
    }

    fun applyLook() {
        for (e in trail.entries) e.page?.applyLook()
    }

    /** Memory is short: the top page keeps only its backdrop and the row focus is in. */
    fun trim() {
        trail.top?.page?.trim()
    }

    /** My List, watched marks or Continue Watching changed: the top page shows it (the others when they're back). */
    fun onLibraryChanged() {
        trail.top?.page?.onLibraryChanged()
    }

    private fun build(title: Title, soon: Boolean = false): TitlePage {
        val page = TitlePage(ctx, t, pageHost, title, soon)
        container.addView(page.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        page.start()
        if (!running) page.pause(hide = false)
        return page
    }

    private fun releasePage(e: Entry) {
        val page = e.page ?: return
        e.page = null
        page.release()
        container.removeView(page.root)
    }

    companion object {
        /** Pages kept built: the one shown and two under it. */
        const val ALIVE = 3
        /** Pages Back can return to (the older ones are forgotten; the last Back still lands on the tab's poster). */
        const val KEPT = 30
    }
}

/**
 * The title pages' trail (pure, unit-tested): entries in the order they were opened, the top one last. Only the top
 * [alive] keep their pages built; at most [kept] are remembered.
 */
internal class TitleTrail<E>(private val alive: Int, private val kept: Int) {
    val entries = ArrayList<E>()

    val size: Int get() = entries.size
    val isEmpty: Boolean get() = entries.isEmpty()
    val top: E? get() = entries.lastOrNull()

    /** Adds [e] on top. Returns the entries whose pages go now: the one leaving the alive window, and any forgotten. */
    fun push(e: E): List<E> {
        entries += e
        val out = ArrayList<E>(2)
        while (entries.size > kept) out += entries.removeAt(0)
        entries.getOrNull(entries.size - 1 - alive)?.let { out += it }
        return out
    }

    /** Takes the top entry off. */
    fun pop(): E? = if (entries.isEmpty()) null else entries.removeAt(entries.size - 1)

    /** Whether [e] is among the top [alive] (its page is kept built). */
    fun isAlive(e: E): Boolean {
        val i = entries.lastIndexOf(e)
        return i >= 0 && i >= entries.size - alive
    }

    /** Forgets every entry; returns them, top first. */
    fun clear(): List<E> {
        val out = entries.reversed()
        entries.clear()
        return out
    }
}
