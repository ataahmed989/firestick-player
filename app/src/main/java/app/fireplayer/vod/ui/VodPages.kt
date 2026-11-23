package app.fireplayer.vod.ui

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import app.fireplayer.shell.NavRail
import app.fireplayer.vod.VodRepository
import app.fireplayer.vod.data.Brand
import app.fireplayer.vod.data.Service
import app.fireplayer.vod.data.VodRepositories
import java.util.EnumMap

/**
 * The shell's Films & Shows tabs (Search, Home, Movies, TV Shows, My List): a [VodPage] for each, made the first time its tab opens, in
 * the shell's page container. Only the selected one is shown and running; the others are hidden with their timers
 * stopped and pictures let go. The pages get their titles from the app's one repository ([VodRepositories]: Cinemeta,
 * TMDB and the library on this TV).
 */
class VodPages(private val context: Context, private val host: VodPage.Host, private val container: FrameLayout) {

    private val pages = EnumMap<VodTab, VodPage>(VodTab::class.java)
    /** The streaming services' pages, each made the first time it opens: the one shared page, with that service's rows. */
    private val servicePages = EnumMap<Service, VodPage>(Service::class.java)
    /** Disney+'s studio pages (a studio's rows of titles), each made the first time it opens. */
    private val studioPages = EnumMap<Brand, VodPage>(Brand::class.java)
    private val all: List<VodPage> get() = pages.values + servicePages.values + studioPages.values
    private var started = false

    /** The page shown, or null on another tab. */
    var current: VodPage? = null
        private set

    fun isVodTab(tab: NavRail.Tab): Boolean = pageOf(tab) != null || tab.isService

    /**
     * Shows [tab]'s page (hiding the others), or only hides them for a tab that isn't one. On a service's tab, the page
     * of [service]; with [studio], that studio's page in the service's place (the service's stays as it was left, for
     * Back).
     */
    fun show(tab: NavRail.Tab, service: Service? = null, studio: Brand? = null) {
        val p = pageOf(tab)
        val s = service?.takeIf { tab.isService }
        val b = studio?.takeIf { s != null }
        for ((k, page) in pages) if (k != p) page.hide()
        for ((k, page) in servicePages) if (k != s || b != null) page.hide()
        for ((k, page) in studioPages) if (k != b) page.hide()
        val page = when {
            b != null -> studioPages.getOrPut(b) { make(VodTab.HOME, s, b) }
            s != null -> servicePages.getOrPut(s) { make(VodTab.HOME, s, null) }
            p != null -> pages.getOrPut(p) { make(p, null, null) }
            else -> null
        }
        current = page
        page?.show()
    }

    private fun make(tab: VodTab, service: Service?, studio: Brand?) =
        VodPage(context, host, ::repository, tab, service, studio, places.remove(PagePlace.keyOf(tab, service, studio))).also {
            container.addView(it.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (started) it.start()
        }

    /** Where the viewer was on each page let go of while hidden ([releaseHidden]), for the page made again in its place. */
    private val places = HashMap<String, PagePlace>()

    /**
     * The pages out of sight go altogether (a film plays, Live TV is watched a while, the TV is short of memory): their
     * views, rows and glass (1 to 2 MB each, and what Android's collections have to go through every time). Each keeps
     * its place ([PagePlace]): made again the next time its tab opens, from the titles kept in memory and on disk and
     * the pictures on disk, as on a first open, it opens on the row and cards the viewer left it on. The page shown, the
     * Home tab's own page (Back from a service's page and the live picture go there), and under a studio's page its
     * service's page (Back returns there), stay as they are. How many went.
     */
    fun releaseHidden(): Int {
        val shown = current
        var count = 0
        for (map in listOf<MutableMap<*, VodPage>>(pages, servicePages, studioPages)) {
            val it = map.values.iterator()
            while (it.hasNext()) {
                val page = it.next()
                if (!PagePlace.goes(page === shown, page.tab, page.service, page.studio, shown?.service, shown?.studio)) continue
                places[PagePlace.keyOf(page.tab, page.service, page.studio)] = page.place()
                page.release()
                container.removeView(page.root)
                it.remove()
                count++
            }
        }
        return count
    }

    fun start() {
        started = true
        for (page in all) page.start()
    }

    fun stop() {
        started = false
        for (page in all) page.stop()
    }

    fun release() {
        for (page in all) page.release()
    }

    fun applyLook() {
        for (page in all) page.applyLook()
    }

    /** Memory is short: the shown page keeps only its chosen row's pictures (hidden pages hold none). */
    fun trimImages() {
        current?.trimImages()
    }

    /** The shown page's keys (Back for its small window, its title pages and See all). */
    fun dispatchKey(event: KeyEvent): Boolean = current?.dispatchKey(event) ?: false

    /** The live picture leaves every page but [keep] (Home and Search have one; a hidden page holds no focus). */
    fun pipOffExcept(keep: VodPage?) {
        for (page in all) if (page !== keep) page.setPip(false, muted = false)
    }

    /** Whether a title page is open over the shown page. */
    fun hasTitle(): Boolean = current?.hasTitle() == true

    /**
     * The repository, made the first time a page asks. Null if it couldn't be made: the page then shows "Couldn't load
     * titles" with Try again, which asks here again.
     */
    private fun repository(): VodRepository? = try {
        VodRepositories.get(context)
    } catch (e: RuntimeException) {
        Log.w(TAG, "Films & Shows data not started: ${e.javaClass.simpleName}")
        null
    }

    private fun pageOf(tab: NavRail.Tab): VodTab? = when (tab) {
        NavRail.Tab.SEARCH -> VodTab.SEARCH
        NavRail.Tab.HOME -> VodTab.HOME
        NavRail.Tab.MOVIES -> VodTab.MOVIES
        NavRail.Tab.SHOWS -> VodTab.SHOWS
        NavRail.Tab.MY_LIST -> VodTab.MY_LIST
        else -> null
    }

    private companion object {
        const val TAG = "FirePlayerVod"
    }
}
