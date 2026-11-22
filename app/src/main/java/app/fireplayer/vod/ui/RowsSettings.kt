package app.fireplayer.vod.ui

import android.app.Activity
import android.view.KeyEvent
import app.fireplayer.R
import app.fireplayer.data.Prefs
import app.fireplayer.live.ui.Reorder
import app.fireplayer.live.ui.reorderList
import app.fireplayer.ui.FireDialog
import app.fireplayer.ui.ListEntry
import app.fireplayer.ui.LongList
import app.fireplayer.ui.SettingsPanels
import app.fireplayer.ui.SidePanel
import app.fireplayer.vod.Kind
import app.fireplayer.vod.Page
import app.fireplayer.vod.data.DefaultVodRepository
import app.fireplayer.vod.data.RowEntry
import app.fireplayer.vod.data.RowLayouts
import app.fireplayer.vod.data.RowSettings
import app.fireplayer.vod.data.Service
import app.fireplayer.vod.data.Services
import app.fireplayer.vod.data.VodRepositories

/**
 * Settings › Rows: for Home, Movies and TV Shows, which rows show and in what order. Each page's window is laid out
 * like Manage groups: Order (Reorder rows, the same list as Reorder playlists; Add a row; Reset to default), then
 * Hide or show (a switch per row; rows the viewer added have a button to remove them). Every change is stored and the
 * page rebuilds its rows at once ([RowSettings]). Then Streaming Services (the order of Home's service cards, put in
 * order the same way) and Service shortcuts (whether the rail shows them, and the services pinned there).
 */
object RowsSettings {

    /** The Rows window: a row per page, Home's services' order and the rail's service shortcuts ([onRailChanged]). */
    fun render(panel: SidePanel, activity: Activity, prefs: Prefs, onRailChanged: () -> Unit): Unit = with(panel) {
        header("Rows")
        val repo = repository(activity)
        if (repo == null) {
            message("Films & Shows couldn't start, so its rows can't be changed just now.")
        } else {
            for (page in Page.entries) {
                val shown = repo.rowSettings.entries(page).count { it.on }
                row(nameOf(page), if (shown == 1) "1 row" else "$shown rows") {
                    openSub(nameOf(page), wide = true) { renderPage(this, activity, repo, page) }
                }
            }
            message("Choose the rows each page shows and their order. A row with nothing in it yet stays hidden on the page; Top 10 This Week needs a TMDB key.")
            header(STREAMING_SERVICES)
            val count = repo.rowSettings.serviceOrder().size
            row(REORDER_SERVICES, "$count services") {
                openSub(REORDER_SERVICES, wide = true) { renderServiceOrder(this, repo.rowSettings) }
            }
        }
        shortcuts(panel, activity, prefs, onRailChanged)
    }

    // ---- Streaming Services and Service shortcuts --------------------------------------------------------------------

    const val STREAMING_SERVICES = "Streaming Services"
    const val SERVICE_SHORTCUTS = "Service shortcuts"
    private const val REORDER_SERVICES = "Reorder services"
    private const val SHOW_ON_RAIL = "Show streaming services on rail"
    private val SHORTCUT_NAMES = listOf("First shortcut", "Second shortcut")
    private const val MOST_RECENT = "Most recent"

    // Home's services being put in order live while their window is open (by its session), as Reorder rows' do.
    private var serviceReorder: Reorder<Service>? = null
    private var serviceSession = -1

    private fun renderServiceOrder(p: SidePanel, rs: RowSettings) {
        val r = serviceReorder?.takeIf { serviceSession == p.session } ?: Reorder(rs.serviceOrder()).also {
            serviceReorder = it
            serviceSession = p.session
        }
        reorderList(p, r, SERVICES_NOTE, "There are no services.", name = { it.label }, key = { it.key }) { order ->
            rs.setServiceOrder(order)
        }
    }

    /**
     * Service shortcuts: the rail's switch, then a row per shortcut picking the service pinned there (or the most
     * recent). The second is pinned only after the first ([Services.pin]); a service is pinned once.
     */
    private fun shortcuts(p: SidePanel, activity: Activity, prefs: Prefs, onRailChanged: () -> Unit) = with(p) {
        header(SERVICE_SHORTCUTS)
        val on = prefs.serviceShortcuts
        row(SHOW_ON_RAIL, SettingsPanels.onOff(on), twoLines = true) {
            prefs.serviceShortcuts = !prefs.serviceShortcuts
            onRailChanged()
            refresh()
        }
        val pins = Services.decode(prefs.pinnedServices)
        val options = listOf(MOST_RECENT) + Service.entries.map { it.label }
        for ((slot, label) in SHORTCUT_NAMES.withIndex()) {
            val pinned = pins.getOrNull(slot)
            val selected = pinned?.let { it.ordinal + 1 } ?: 0
            row(label, pinned?.label ?: MOST_RECENT, enabled = on && slot <= pins.size) {
                FireDialog.choose(activity, label, options, selected, besidePanel = true) { i ->
                    if (i != selected) {
                        prefs.pinnedServices = Services.encode(Services.pin(pins, slot, Service.entries.getOrNull(i - 1)))
                        onRailChanged()
                    }
                    refresh()
                }
            }
        }
        message("Pin up to two services to the rail. A shortcut without a pin shows a service page you opened lately, newest first.")
    }

    fun nameOf(page: Page): String = when (page) {
        Page.HOME -> "Home"
        Page.MOVIES -> "Movies"
        Page.SHOWS -> "TV Shows"
    }

    private fun repository(activity: Activity): DefaultVodRepository? = try {
        VodRepositories.repository(activity)
    } catch (e: RuntimeException) {
        null
    }

    // ---- A page's rows ----------------------------------------------------------------------------------------------

    private const val REORDER = 1
    private const val ADD = 2
    private const val RESET = 3
    private const val FIRST_ROW = 5

    private fun renderPage(p: SidePanel, activity: Activity, repo: DefaultVodRepository, page: Page) {
        val rs = repo.rowSettings
        p.longList(object : LongList {
            private val all: List<RowEntry> get() = rs.entries(page)

            override val size: Int get() = FIRST_ROW + all.size

            override fun entry(index: Int): ListEntry = when (index) {
                0 -> ListEntry("orderHeading", "Order", kind = ListEntry.Kind.HEADING)
                REORDER -> all.size.let { n -> ListEntry("reorder", "Reorder rows", if (n > 1) "$n rows" else "Needs 2 rows", dimmed = n < 2) }
                ADD -> ListEntry("add", "Add a row")
                RESET -> rs.isDefault(page).let { same -> ListEntry("reset", "Reset to default", if (same) "In use" else null, dimmed = same) }
                4 -> ListEntry("hideOrShow", "Hide or show", kind = ListEntry.Kind.HEADING)
                else -> all[index - FIRST_ROW].let { e ->
                    ListEntry(
                        ROW + e.spec.key, e.spec.label, SettingsPanels.onOff(e.on), sub = true,
                        button = if (e.added) R.drawable.ic_close else null, buttonLabel = if (e.added) "Remove" else "",
                    )
                }
            }

            override fun onClick(index: Int) {
                when (index) {
                    REORDER -> if (all.size > 1) p.openSub("Reorder rows", wide = true) { renderReorder(this, rs, page) }
                    ADD -> p.openSub("Add a row", wide = true) { renderAdd(this, repo, page) }
                    RESET -> if (!rs.isDefault(page)) {
                        FireDialog.ask(
                            activity, "Put ${nameOf(page)}'s rows back as they came? Rows you added go.",
                            listOf("Reset" to { rs.reset(page); p.refresh() }, "Keep them" to {}),
                            focus = 1, fromPanel = true,
                        )
                    }
                    in FIRST_ROW until size -> {
                        // The row as it is now (it may have been flipped since it was drawn).
                        val e = all.getOrNull(index - FIRST_ROW) ?: return
                        rs.setOn(page, e.spec.key, !e.on)
                        p.changed(index)
                        p.changed(RESET)
                    }
                }
            }

            override fun onButton(index: Int) {
                val e = all.getOrNull(index - FIRST_ROW)?.takeIf { it.added } ?: return
                rs.remove(page, e.spec.key)
                p.refresh()
            }

            override fun onKey(index: Int, keyCode: Int, event: KeyEvent): Boolean = false
        })
    }

    // ---- Reorder rows -----------------------------------------------------------------------------------------------

    // The list being put in order lives while its window is open (by the window's session), so a redraw keeps it.
    private var reorder: Reorder<RowEntry>? = null
    private var reorderSession = -1

    private fun renderReorder(p: SidePanel, rs: RowSettings, page: Page) {
        val r = reorder?.takeIf { reorderSession == p.session } ?: Reorder(rs.entries(page)).also {
            reorder = it
            reorderSession = p.session
        }
        reorderList(
            p, r, REORDER_NOTE, "This page has no rows.",
            name = { e -> if (e.on) e.spec.label else "${e.spec.label} · Off" },
            key = { it.spec.key },
        ) { order -> rs.setOrder(page, order.map { it.spec.key }) }
    }

    // ---- Add a row --------------------------------------------------------------------------------------------------

    private fun renderAdd(p: SidePanel, repo: DefaultVodRepository, page: Page) {
        val rs = repo.rowSettings
        val tmdb = repo.hasTmdbKey()
        val choices = rs.choices(page, tmdb)
        // Under each heading: its rows, and for films and series a genre row picked from Cinemeta's genres.
        val entries = ArrayList<ListEntry>()
        val actions = ArrayList<(() -> Unit)?>()
        fun add(entry: ListEntry, action: (() -> Unit)?) {
            entries += entry
            actions += action
        }
        for (group in listOf(RowLayouts.groupOf(Kind.MOVIE), RowLayouts.groupOf(Kind.SERIES), RowLayouts.BOTH_GROUP)) {
            val kind = Kind.entries.firstOrNull { RowLayouts.groupOf(it) == group }
            val inGroup = choices.filter { it.group == group }
            if (inGroup.isEmpty() && kind == null) continue
            add(ListEntry("h|$group", group, kind = ListEntry.Kind.HEADING), null)
            for (c in inGroup) add(ListEntry(c.key, c.label)) { addRow(p, rs, page, c.key, back = 1) }
            if (kind != null) {
                add(ListEntry("genre|$group", "A genre", "Action, Comedy…")) {
                    p.openSub("${RowLayouts.groupOf(kind)} genres", wide = true) { renderGenres(this, repo, page, kind) }
                }
            }
        }
        if (!tmdb) add(ListEntry("noKey", "Top 10 This Week needs a TMDB key: add yours in Accounts & services.", kind = ListEntry.Kind.NOTE), null)
        p.longList(object : LongList {
            override val size: Int get() = entries.size
            override fun entry(index: Int): ListEntry = entries[index]
            override fun onClick(index: Int) {
                actions.getOrNull(index)?.invoke()
            }
        })
    }

    /** Cinemeta's genres of [kind], as genre rows the page hasn't got; stored list at once, a newer one when it comes. */
    private fun renderGenres(p: SidePanel, repo: DefaultVodRepository, page: Page, kind: Kind) {
        val names = genres[kind]
        if (names == null) {
            p.message("Loading genres…")
            val session = p.session
            repo.genres(kind) { list ->
                // The stored list first (none before Cinemeta was first read), the fresh one after: shown when the window
                // had none to show yet.
                val shownNone = genres[kind].isNullOrEmpty()
                genres[kind] = list
                if (shownNone && p.isOpen && p.session == session) p.refresh()
            }
            return
        }
        val choices = repo.rowSettings.genreChoices(page, kind, names)
        if (choices.isEmpty()) {
            // None read (offline, or not yet): asked again the next time the window opens.
            if (names.isEmpty()) genres.remove(kind)
            p.message("Every genre is on this page already, or Cinemeta's genres couldn't be read. Check the connection.")
            return
        }
        p.longList(object : LongList {
            override val size: Int get() = choices.size
            override fun entry(index: Int): ListEntry = choices[index].let { ListEntry(it.key, it.label) }
            override fun onClick(index: Int) {
                choices.getOrNull(index)?.let { addRow(p, repo.rowSettings, page, it.key, back = 2) }
            }
        })
    }

    /** Cinemeta's genre names, once read (they rarely change; the page's own Genres row reads them again). */
    private val genres = HashMap<Kind, List<String>>()

    /** Adds the row at the end of the page and goes [back] windows, to the page's rows. */
    private fun addRow(p: SidePanel, rs: RowSettings, page: Page, key: String, back: Int) {
        rs.add(page, key)
        repeat(back) { p.back() }
        p.focusEntry(ROW + key)
    }

    private const val ROW = "row|"
    private const val REORDER_NOTE = "Press OK on a row to pick it up, move it with Up and Down, and press OK again to put it down. " +
        "The button beside each moves it to the top. Rows that are off keep their place."
    private const val SERVICES_NOTE = "Press OK on a service to pick it up, move it with Up and Down, and press OK again to put it down. " +
        "The button beside each moves it to the top. Home's cards follow this order."
}
