package app.fireplayer.live.ui

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.format.DateFormat
import android.util.Log
import android.view.KeyEvent
import android.view.View
import app.fireplayer.R
import app.fireplayer.live.Channel
import app.fireplayer.live.ChannelGroup
import app.fireplayer.live.ChannelKey
import app.fireplayer.live.GuideScope
import app.fireplayer.live.LiveDb
import app.fireplayer.live.LiveSync
import app.fireplayer.live.Playlist
import app.fireplayer.live.PlaylistKind
import app.fireplayer.live.XtreamApi
import app.fireplayer.live.XtreamParser
import app.fireplayer.playback.PlayerHost
import app.fireplayer.ui.FireDialog
import app.fireplayer.ui.ListEntry
import app.fireplayer.ui.LongList
import app.fireplayer.ui.Rows
import app.fireplayer.ui.SettingsCategory
import app.fireplayer.ui.SettingsPanels
import app.fireplayer.ui.SidePanel
import app.fireplayer.ui.asSubRow
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Live TV's settings windows, in the shared SidePanel:
 * - Live FirePlayer Settings ([openPlayerSettings], over Live TV: Menu or the Settings button): Playlists & EPG
 *   ([playlistsAndEpg]), then the shared Remote / Look / Defaults / Playback / Statuses / App sections
 *   ([SettingsPanels.renderPlayerLive]).
 * - Manage playlists (Add playlist, Reorder playlists) and Manage EPG sources (each playlist's EPG, Days to keep EPG,
 *   EPG update interval, Update EPG, Clear EPG): windows under Playlists & EPG.
 * - Individual playlist settings (name, EPG sources, Xtream parameters or M3U link, account, Manage groups, updates).
 * - Manage groups (hide or show each group, all, new ones; reorder the shown ones; Assign favorites) and Reorder
 *   playlists: wide windows, long lists ([SidePanel.longList]) so 350 groups open at once.
 * - Assign favorites (a playlist's groups, then a group's channels: OK stars one), also opened from the guide.
 * - The Settings tab's Playlists & EPG and Live TV windows, built from the same pieces.
 * Saved passwords are never shown, nor an M3U link (it often carries the sign-in): only that one is saved.
 */
class LiveSettings(private val activity: Activity, private val panel: SidePanel) {

    private val db = LiveDb.get(activity)
    private val sync = LiveSync.get(activity)
    private val prefs = LivePrefs(activity)
    private val io = Executors.newSingleThreadExecutor()
    /**
     * Favorites set in Assign favorites are stored here, never queued behind the settings' reads: the one queue the
     * guide's own stars go through too, so a read after it sees those as well ([LiveDb.writes]).
     */
    private val writes get() = LiveDb.writes
    private var released = false
    /** Questions to the provider (Active connections): never ahead of the settings' own reads and saves. */
    private val net = Executors.newSingleThreadExecutor()
    private val asking = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())
    private var playlists: List<Playlist> = emptyList()
    private var epg: Map<String, LiveDb.EpgStatus> = emptyMap()
    /** Each playlist's groups (hidden ones too), read when its window opens. */
    private val groups = HashMap<String, List<ChannelGroup>>()
    /** "Active connections": the provider's count and allowance, asked when the playlist's window opens. */
    private val connections = HashMap<String, String>()

    /** The panel is told about every sync so statuses update while it's open. */
    private val listener = object : LiveSync.Listener {
        override fun onLiveDataChanged(playlistId: String) {
            // A change this window made itself is already on screen (redrawing 350 groups would take a second).
            if (ownChanges > 0) { ownChanges--; return }
            if (panel.isOpen) reloadThen { panel.refresh() }
        }
    }

    /** Changes made here (group switches and order, playlist order) the guide is told about; this window isn't. */
    private var ownChanges = 0

    private fun changed(playlistId: String) {
        ownChanges++
        sync.changed(playlistId)
    }

    /**
     * The guide is told about changes made here a moment after the last one: ten groups switched or ten channels
     * starred in a row read the guide again once, not ten times (a read can take a second with a big playlist).
     */
    private val toTell = LinkedHashSet<String>()
    private val tell = Runnable {
        val ids = toTell.toList()
        toTell.clear()
        ids.forEach { changed(it) }
    }

    private fun tellGuide(playlistId: String) {
        toTell += playlistId
        main.removeCallbacks(tell)
        main.postDelayed(tell, TELL_MS)
    }

    init {
        sync.addListener(listener)
    }

    fun release() {
        sync.removeListener(listener)
        released = true
        // Changes still waiting to be told go now.
        main.removeCallbacks(tell)
        tell.run()
        io.shutdown()
        // Queued favorites are still stored (the queue is the app's, not this window's).
        net.shutdownNow()
    }

    /** Reads the playlists and EPG statuses (and [groupsOf]'s groups) off the main thread, then [then]. */
    private fun reloadThen(groupsOf: String? = null, then: () -> Unit) {
        // The groups already shown are read again too (an update may have added a playlist's groups meanwhile).
        val ids = (groups.keys + listOfNotNull(groupsOf)).toSet()
        io.execute {
            val list = db.playlists()
            val statuses = db.epgStatuses().associateBy { it.source }
            val g = ids.associateWith { db.allGroups(it) }
            main.post {
                playlists = list
                epg = statuses
                groups.putAll(g)
                then()
            }
        }
    }

    // ---- Live FirePlayer Settings (the contextual window over Live TV) ---------------------------------------------

    /** Live FirePlayer Settings: Playlists & EPG, Remote, Look, Defaults, Playback, Statuses, App (user spec). */
    fun openPlayerSettings(shared: SettingsPanels, onClose: (() -> Unit)? = null) = reloadThen {
        panel.open("Settings", onClose) {
            // Search (Live TV search) first, focused as the window opens.
            shared.onSearch?.let { searchBubble(it) }
            // Named as its category in App settings and the Settings tab.
            header(SettingsCategory.PLAYLISTS_EPG.title)
            playlistsAndEpg(this, headings = false)
            shared.renderPlayerLive(this) { channelButtonsRow(this, activity) }
        }
    }

    /**
     * Playlists & EPG as Live FirePlayer Settings and the Settings tab's Playlists & EPG both show them (user
     * outline): each playlist; Playlists ▾ with Manage playlists and Update playlists under it; then EPG sources ▾
     * with Manage EPG sources under it, both closed each time the window opens. [headings]: Playlists and EPG under headings of their own, in the window
     * that holds only them.
     */
    private fun playlistsAndEpg(p: SidePanel, headings: Boolean): Unit = with(p) {
        if (headings) header("Playlists")
        playlistRows(this)
        if (headings) header("EPG")
        epgRows(this, saysIfNone = headings)
    }


    // ---- Playlists ------------------------------------------------------------------------------------------------

    /** The Playlists window, from Live TV while it has no playlists: under the heading Playlists & EPG gives them. */
    fun openPlaylists(onClose: (() -> Unit)? = null) = reloadThen {
        panel.open("Playlists", onClose) {
            header("Playlists")
            playlistRows(this)
        }
    }

    /**
     * The playlists (each opens its settings), then Playlists ▾ (closed each time the window opens, as EPG sources
     * is, and the same row: [SidePanel.dropdownRow]) saying how many there are, or that they're updating. Open,
     * Manage playlists, and Update playlists with when they last updated, indented under it. With none yet, how to
     * start and Add playlist come first.
     */
    private fun playlistRows(p: SidePanel): Unit = with(p) {
        if (playlists.isEmpty()) {
            message("Add an Xtream Codes account or an M3U playlist link. Channels and the TV guide load from it.")
            // The viewer's first step, right here rather than a window away.
            row("Add playlist") { addPlaylist() }
            managePlaylistsRow(this)
            return@with
        }
        for (pl in playlists) {
            row(pl.name, shortStatus(pl), twoLines = false) { openPlaylist(pl.id) }
        }
        val open = isPartOpen("playlists")
        val count = if (playlists.size == 1) "1 playlist" else "${playlists.size} playlists"
        dropdownRow("Playlists", if (sync.busy) "Updating…" else count, if (sync.busy) Rows.Badge.BUSY else Rows.Badge.LIGHT, open) {
            togglePart("playlists")
            refresh()
        }
        if (open) {
            managePlaylistsRow(this).asSubRow()
            updateRow(this, "Update playlists", playlists.maxOf { it.lastSync }) { sync.updateAll() }.asSubRow()
        }
    }

    private fun managePlaylistsRow(p: SidePanel): View =
        // Two short rows: the usual width (wide windows are for long names).
        p.row("Manage playlists") { p.openSub("Manage playlists") { managePlaylists(this) } }

    /** Manage playlists (user outline): Add playlist and Reorder playlists. */
    private fun managePlaylists(p: SidePanel): Unit = with(p) {
        header("Playlists")
        row("Add playlist") { addPlaylist() }
        reorderPlaylistsRow(this)
    }

    /**
     * Reorder playlists: with fewer than two, dimmed and saying so; it stays focusable (focus doesn't jump past it)
     * and does nothing. Reorder groups does the same.
     */
    private fun reorderPlaylistsRow(p: SidePanel) {
        val ready = playlists.size > 1
        val row = p.row("Reorder playlists", if (ready) null else "Needs 2 playlists") {
            if (playlists.size > 1) p.openSub("Reorder playlists", wide = true) { reorderPlaylists(this) }
        }
        if (!ready) p.dim(row)
    }

    /**
     * An Update button: while anything is updating it's dimmed and does nothing (it stays focusable, so focus
     * doesn't jump), and says "Updating…"; otherwise it says when the last update was.
     */
    private fun updateRow(p: SidePanel, label: String, lastSync: Long, start: () -> Unit): View {
        val busy = sync.busy
        val row = p.row(label, updatedText(lastSync)) {
            if (!sync.busy) { start(); p.refresh() }
        }
        if (busy) p.dim(row)
        return row
    }

    /** "Updating…" while anything updates, else "Updated 5:04 PM" (or "yesterday", "Nov 15"), or "Never updated". */
    private fun updatedText(lastSync: Long): String = when {
        sync.busy -> "Updating…"
        lastSync > 0 -> "Updated ${whenText(lastSync, short = true)}"
        else -> NEVER_UPDATED
    }

    /** "5:04 PM" today, "yesterday 5:04 PM", else "Nov 15, 5:04 PM" ([short]: "yesterday", "Nov 15"). */
    private fun whenText(t: Long, short: Boolean = false): String {
        val time = DateFormat.getTimeFormat(activity).format(Date(t))
        val day = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = t }
        fun sameDay(a: java.util.Calendar, b: java.util.Calendar) = a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
        if (sameDay(day, then)) return time
        day.add(java.util.Calendar.DAY_OF_YEAR, -1)
        if (sameDay(day, then)) return if (short) "yesterday" else "yesterday $time"
        val date = android.text.format.DateUtils.formatDateTime(activity, t, android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_ABBREV_MONTH)
        return if (short) date else "$date, $time"
    }

    private fun shortStatus(p: Playlist): String = when {
        p.lastStatus.isBlank() -> NEVER_UPDATED
        p.lastStatus.startsWith("Updated: ") -> p.lastStatus.removePrefix("Updated: ")
        else -> p.lastStatus
    }

    private fun openPlaylist(id: String) {
        val from = panel.session
        reloadThen(groupsOf = id) {
            // Only into the window it was asked from (it may have closed, or moved on, while the playlist was read).
            if (!panel.isOpen || panel.session != from) return@reloadThen
            val pl = playlists.firstOrNull { it.id == id } ?: return@reloadThen
            panel.openSub(pl.name, wide = true) { renderPlaylist(this, id) }
            askConnections(pl, panel.session)
        }
    }

    /** Asks the provider how many of the account's connections are in use (Xtream only), then shows it. */
    private fun askConnections(pl: Playlist, window: Int) {
        if (pl.kind != PlaylistKind.XTREAM || !asking.add(pl.id)) return
        connections[pl.id] = CHECKING
        net.execute {
            val account = runCatching { XtreamApi(pl).get(null)?.let { XtreamParser.account(it) } }.getOrNull()
            val text = when {
                account == null -> "Couldn't ask the provider"
                account.maxConnections > 0 -> "${account.activeConnections} of ${account.maxConnections}"
                else -> "${account.activeConnections} in use"
            }
            main.post {
                asking -= pl.id
                connections[pl.id] = text
                // Only the playlist's own window shows it.
                if (panel.isOpen && panel.session == window) panel.refresh()
            }
        }
    }

    private fun renderPlaylist(p: SidePanel, id: String) = with(p) {
        val pl = playlists.firstOrNull { it.id == id } ?: run { message("This playlist was deleted."); return@with }

        header("Playlist")
        row("Name", pl.name) { edit("Name", pl.name) { v -> save(pl.copy(name = v.ifBlank { pl.name })) } }
        // How the last update went, read-only, as the account's rows are (when it was is on Update playlist below).
        row("Status", shortStatus(pl)) {}
        // A problem in full under the card: the row's value is shortened.
        if (pl.lastStatus.isNotBlank() && !pl.lastStatus.startsWith("Updated")) message(pl.lastStatus.removeSuffix(".") + ".")

        // This playlist's own EPG comes first; others fill in only by an exact EPG id (automatic or manual fallback is
        // still to be decided: the automatic one stays provisional).
        header("EPG sources")
        val own = epg[pl.id]
        // A link of the viewer's own can carry a sign-in too: only where it points is shown, and its box starts empty.
        val ownName = if (pl.kind == PlaylistKind.XTREAM) "server's" else "playlist's"
        row("Own EPG", if (pl.epgUrl.isBlank()) "The $ownName own" else hostName(pl.epgUrl)) {
            SidePanel.prompt(activity, "EPG link", "", inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                hint = if (pl.epgUrl.isBlank()) "An XMLTV link (blank: the $ownName own)" else "Saved · type a new one to replace it") { v ->
                if (v.isNotBlank()) save(pl.copy(epgUrl = normalized(v)))
            }
        }
        if (pl.epgUrl.isNotBlank()) row("Use the $ownName own EPG") { save(pl.copy(epgUrl = "")) }.asSubRow()
        row("Own EPG status", epgValue(own)) {}.asSubRow()
        val others = playlists.filter { it.id != pl.id && (epg[it.id]?.programmes ?: 0) > 0 }
        // Read-only, at full brightness and focusable, as the other rows that only say how things stand.
        row("Fallback", if (others.isEmpty()) "None" else others.joinToString { it.name }) {}
        message(FALLBACK_NOTE)

        if (pl.kind == PlaylistKind.XTREAM) {
            header("Xtream Codes parameters")
            row("Server", pl.url) {
                edit("Server address", pl.url, uri = true) { v ->
                    val url = normalized(v)
                    // A name made from the old address follows the new one; a name the viewer chose stays.
                    val name = if (pl.name == hostName(pl.url)) hostName(url) else pl.name
                    save(pl.copy(url = url, name = name))
                }
            }
            row("Username", pl.username) { edit("Username", pl.username) { v -> save(pl.copy(username = v.trim())) } }
            // As the sign-in rows in Accounts & services: a badge saying whether one is saved, never the password.
            badgeRow("Password", if (pl.password.isNotEmpty()) "Set" else "Not set", if (pl.password.isNotEmpty()) Rows.Badge.ACTIVE else Rows.Badge.INACTIVE) {
                SidePanel.prompt(activity, "Password", "", password = true) { v -> if (v.isNotEmpty()) save(pl.copy(password = v)) }
            }
            choice("Output format", listOf("MPEG-TS (.ts)", "HLS (.m3u8)"), if (pl.output == "m3u8") 1 else 0) { i ->
                save(pl.copy(output = if (i == 1) "m3u8" else "ts"))
            }
            row("Include TV channels", SettingsPanels.onOff(pl.includeLive)) { save(pl.copy(includeLive = !pl.includeLive)) }
            includeVod(this)

            // Read-only, as the provider reports them.
            header("Account")
            row("Account expires", if (pl.expiresAt > 0) DateFormat.getMediumDateFormat(activity).format(Date(pl.expiresAt)) else "Not reported") {}
            row("Connection allowance", if (pl.maxConnections > 0) "${pl.maxConnections}" else "Not reported") {}
            // While the provider is asked, dimmed and doing nothing, as the other rows that check something.
            val asked = connections[pl.id] == CHECKING
            val active = row("Active connections", connections[pl.id] ?: "—") {
                if (connections[pl.id] == CHECKING) return@row
                askConnections(pl, session)
                refresh()
            }
            if (asked) dim(active)
        } else {
            header("M3U playlist")
            // The link often carries the sign-in: only where it points is shown, and its box starts empty.
            row("Playlist link", hostName(pl.url)) {
                SidePanel.prompt(activity, "Playlist link", "", inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
                    hint = "Saved · type a new one to replace it") { v ->
                    if (v.isBlank()) return@prompt
                    val url = normalized(v)
                    val name = if (pl.name == hostName(pl.url)) hostName(url) else pl.name
                    save(pl.copy(url = url, name = name))
                }
            }
            row("Include TV channels", SettingsPanels.onOff(pl.includeLive)) { save(pl.copy(includeLive = !pl.includeLive)) }
            includeVod(this)
        }

        header("Groups")
        val g = groups[pl.id].orEmpty()
        row("Manage groups", if (g.isEmpty()) "No groups" else "${g.count { !it.hidden }} of ${g.size} shown") {
            openSub("Manage groups", wide = true) { manageGroups(this, pl.id) }
        }

        header("Updates")
        val hours = listOf(6, 12, 24, 48)
        choice("Update interval", hours.map { "Every $it hours" }, hours.indexOf(pl.updateHours).let { if (it < 0) 2 else it }) { i ->
            save(pl.copy(updateHours = hours[i]))
        }
        updateRow(this, "Update playlist", pl.lastSync) { sync.update(pl.id) }
        // Named, as the other Delete rows are. Focus starts on the safe answer, so a double press doesn't delete; Menu
        // closes it with the window.
        row("Delete \"${pl.name}\"") {
            FireDialog.ask(activity, "Delete \"${pl.name}\"? Its channels, favorites and guide go too.", listOf<Pair<String, () -> Unit>>(
                "Delete" to {
                    val window = session
                    // Its channel stops if it's the one playing (or remembered), and isn't brought back later.
                    PlayerHost.peek()?.let { h -> if (ChannelKey.parse(h.currentKey)?.playlistId == pl.id) h.stop() }
                    if (prefs.lastChannel?.playlistId == pl.id) prefs.lastChannel = null
                    io.execute {
                        db.deletePlaylist(pl.id)
                        val list = db.playlists()
                        main.post {
                            playlists = list
                            sync.changed(pl.id)
                            // Back to the list it was opened from (it no longer lists this one), if still on this window.
                            if (panel.isOpen && panel.session == window) { if (panel.hasParent) panel.back() else panel.close() }
                            else if (panel.isOpen) panel.refresh()
                        }
                    }
                },
                "Keep it" to {},
            ), focus = 1, fromPanel = true)
        }
    }

    /** "Include VOD": shown, off, and can't be turned on (not a feature yet: films and shows come from add-ons). */
    private fun includeVod(p: SidePanel) = with(p) {
        row("Include VOD", "Off", enabled = false) {}
        message("Films and series from playlists aren't available yet.")
    }

    private fun edit(title: String, initial: String, uri: Boolean = false, onDone: (String) -> Unit) {
        val type = if (uri) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI else InputType.TYPE_CLASS_TEXT
        SidePanel.prompt(activity, title, initial, inputType = type) { onDone(it) }
    }

    /** Saves a change, then updates the playlist when what it loads from changed. */
    private fun save(p: Playlist) {
        val before = playlists.firstOrNull { it.id == p.id }
        io.execute {
            db.savePlaylist(p)
            val list = db.playlists()
            main.post {
                playlists = list
                // The update starts first, so the window redraws with its Update buttons already dimmed.
                if (before == null || before.url != p.url || before.username != p.username || before.password != p.password ||
                    before.output != p.output || before.includeLive != p.includeLive || before.epgUrl != p.epgUrl
                ) sync.update(p.id) else if (before.name != p.name) changed(p.id)
                panel.refresh()
            }
        }
    }

    private fun addPlaylist() {
        FireDialog.ask(activity, "Add playlist", listOf(
            "Xtream Codes" to { addXtream() },
            "M3U playlist link" to { addM3u() },
        ), fromPanel = true)
    }

    private fun addXtream() {
        edit("Server address (for example http://example.com:8080)", "", uri = true) { server ->
            if (server.isBlank()) return@edit
            edit("Username", "") { user ->
                SidePanel.prompt(activity, "Password", "", password = true) { pass ->
                    val url = normalized(server)
                    create(Playlist(UUID.randomUUID().toString(), hostName(url), PlaylistKind.XTREAM, url, user.trim(), pass, position = playlists.size))
                }
            }
        }
    }

    private fun addM3u() {
        edit("Playlist link", "", uri = true) { link ->
            if (link.isBlank()) return@edit
            val url = normalized(link)
            create(Playlist(UUID.randomUUID().toString(), hostName(url), PlaylistKind.M3U, url, position = playlists.size))
        }
    }

    private fun create(p: Playlist) {
        io.execute {
            db.savePlaylist(p)
            main.post {
                sync.update(p.id)
                openPlaylist(p.id)
            }
        }
    }

    private fun normalized(s: String): String {
        val v = s.trim()
        return if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(v)) v else "http://$v"
    }

    private fun hostName(url: String): String = Uri.parse(url).host?.removePrefix("www.")?.takeIf { it.isNotBlank() } ?: "Playlist"

    // ---- Reorder (playlists and groups) ---------------------------------------------------------------------------

    /** A list the viewer puts in order ([reorderList], shared with Settings › Rows). */
    private fun <T> reorderRows(p: SidePanel, r: Reorder<T>, name: (T) -> String, key: (T) -> String, save: (List<T>) -> Unit) =
        reorderList(p, r, REORDER_NOTE, "Nothing to put in order: every group is hidden. Show some in Manage groups first.", name, key, save)

    private fun reorderPlaylists(p: SidePanel) {
        val r = reorderState(p, "playlists") { Reorder(playlists) }
        reorderRows(p, r, { it.name }, { it.id }) { order ->
            // In memory at once (the windows behind this one show it), each with its new place for later saves (and as
            // last read, should an update have changed its status meanwhile).
            val now = playlists.associateBy { it.id }
            playlists = order.mapIndexed { i, pl -> (now[pl.id] ?: pl).copy(position = i) }
            playlistOrder.submit(order.map { it.id })
        }
    }

    /** Playlist order, stored as it changes (only the newest of quick moves). */
    private val playlistOrder = LatestSave<List<String>>(io) { ids ->
        db.setPlaylistOrder(ids)
        main.post { ids.firstOrNull()?.let { tellGuide(it) } }
    }

    /** Each playlist's group order, stored as it changes (only the newest of quick moves). */
    private val groupOrders = HashMap<String, LatestSave<List<String>>>()

    private fun reorderGroups(p: SidePanel, playlistId: String) {
        val r = reorderState(p, "groups|$playlistId") {
            // Hidden groups aren't listed (user): only the ones the guide shows are put in order.
            Reorder(groups[playlistId].orEmpty().filter { !it.hidden })
        }
        reorderRows(p, r, { it.name }, { it.id }) { order ->
            // The groups in memory take the new order now (hidden ones after); the database follows.
            val ids = order.map { it.id }
            val all = groups[playlistId].orEmpty()
            val listed = ids.toHashSet()
            groups[playlistId] = order + all.filter { it.id !in listed }
            groupOrders.getOrPut(playlistId) {
                LatestSave(io) { saved ->
                    db.setGroupOrder(playlistId, saved)
                    main.post { tellGuide(playlistId) }
                }
            }.submit(ids)
        }
    }

    // A reorder list lives while its window is open (keyed by the window's session), so refreshes keep the order.
    private var reorderKey: String? = null
    private var reorderSession = -1
    private var reorder: Reorder<*>? = null

    @Suppress("UNCHECKED_CAST")
    private fun <T> reorderState(p: SidePanel, key: String, make: () -> Reorder<T>): Reorder<T> {
        if (reorderKey != key || reorderSession != p.session || reorder == null) {
            reorderKey = key
            reorderSession = p.session
            reorder = make()
        }
        return reorder as Reorder<T>
    }

    // ---- Manage groups ----------------------------------------------------------------------------------------------

    /**
     * Manage groups on its own, over the guide (a group's held window): [playlistId]'s window, with focus on [groupId]'s
     * row if given. The playlist and its groups are read first. [onClose] runs when it closes.
     */
    fun openManageGroups(playlistId: String, groupId: String? = null, onClose: (() -> Unit)? = null) = reloadThen(groupsOf = playlistId) {
        panel.open("Manage groups", onClose, wide = true) { manageGroups(this, playlistId) }
        if (groupId != null) panel.focusEntry(GROUP + groupId)
    }

    /**
     * Manage groups (user spec), one wide window per playlist, each part under its heading: Favorites (Assign
     * favorites); Order (Reorder groups, how many are shown); then Hide or show — Hide new groups, Show all, Hide all,
     * and a switch for each group. Hidden groups and their channels leave the guide. A long list (350 groups open at
     * once); a switch flips in place at once and is stored behind it.
     */
    private fun manageGroups(p: SidePanel, playlistId: String): Unit = with(p) {
        if (playlists.none { it.id == playlistId }) { message("This playlist was deleted."); return@with }
        if (groups[playlistId].isNullOrEmpty()) { message("This playlist has no groups yet. They appear after its next update."); return@with }
        longList(GroupSwitches(p, playlistId))
    }

    private inner class GroupSwitches(private val p: SidePanel, private val playlistId: String) : LongList {
        private val all: List<ChannelGroup> get() = groups[playlistId].orEmpty()

        override val size: Int get() = FIRST_GROUP + all.size

        /** Reorder groups needs two shown groups, as Reorder playlists needs two playlists. */
        private val canReorder: Boolean get() = all.count { !it.hidden } > 1

        override fun entry(index: Int): ListEntry = when (index) {
            0 -> ListEntry("favHeading", "Favorites", kind = ListEntry.Kind.HEADING)
            1 -> ListEntry("favorites", "Assign favorites")
            2 -> ListEntry("orderHeading", "Order", kind = ListEntry.Kind.HEADING)
            REORDER -> ListEntry(
                "reorder", "Reorder groups",
                if (canReorder) "${all.count { !it.hidden }} shown" else "Needs 2 shown groups",
                dimmed = !canReorder,
            )
            4 -> ListEntry("hideOrShow", "Hide or show", kind = ListEntry.Kind.HEADING)
            5 -> ListEntry("hideNew", "Hide new groups", SettingsPanels.onOff(playlists.firstOrNull { it.id == playlistId }?.hideNewGroups == true))
            6 -> ListEntry("showAll", "Show all")
            7 -> ListEntry("hideAll", "Hide all")
            else -> all[index - FIRST_GROUP].let { g -> ListEntry(GROUP + g.id, g.name, SettingsPanels.onOff(!g.hidden), sub = true) }
        }

        override fun onClick(index: Int) {
            when (index) {
                1 -> openFavoriteGroups(playlistId)
                // Dimmed with fewer than two shown: it does nothing.
                REORDER -> if (canReorder) p.openSub("Reorder groups", wide = true) { reorderGroups(this, playlistId) }
                5 -> {
                    val pl = playlists.firstOrNull { it.id == playlistId } ?: return
                    val updated = pl.copy(hideNewGroups = !pl.hideNewGroups)
                    // Shown at once; stored behind it.
                    playlists = playlists.map { if (it.id == pl.id) updated else it }
                    p.changed(index)
                    io.execute { db.savePlaylist(updated) }
                }
                6 -> setAllGroups(p, playlistId, hidden = false)
                7 -> setAllGroups(p, playlistId, hidden = true)
                in FIRST_GROUP until size -> {
                    // The group as it is now (this row may have been flipped since it was drawn).
                    val g = all.getOrNull(index - FIRST_GROUP) ?: return
                    val hide = !g.hidden
                    groups[playlistId] = all.map { if (it.id == g.id) it.copy(hidden = hide) else it }
                    p.changed(index)
                    p.changed(REORDER)
                    io.execute {
                        db.setGroupHidden(playlistId, g.id, hide)
                        main.post { tellGuide(playlistId) }
                    }
                }
            }
        }
    }

    /** Show all / Hide all: shown at once in place, stored behind (switches pressed after it are stored after it too). */
    private fun setAllGroups(p: SidePanel, playlistId: String, hidden: Boolean) {
        val all = groups[playlistId].orEmpty()
        groups[playlistId] = all.map { it.copy(hidden = hidden) }
        p.changed(REORDER)
        p.changed(FIRST_GROUP, all.size)
        io.execute {
            db.setAllGroupsHidden(playlistId, hidden)
            main.post { tellGuide(playlistId) }
        }
    }

    // ---- Assign favorites -------------------------------------------------------------------------------------------

    /**
     * Assign favorites on its own, over the guide (user spec). With [groupId] it opens on that group's channels, with
     * focus on [channel] if given, and Back goes to the playlist's groups (on that group); without, on the groups.
     * [onClose] runs when it closes.
     */
    fun openAssignFavorites(playlistId: String, groupId: String? = null, channel: ChannelKey? = null, onClose: (() -> Unit)? = null) {
        reloadThen(groupsOf = playlistId) {
            readFavorites(playlistId, groupId?.takeIf { id -> groups[playlistId].orEmpty().any { it.id == id } }, from = null) {
                panel.open("Assign favorites", onClose, wide = true) { favoriteGroups(this, playlistId) }
                val group = groupId?.let { id -> groups[playlistId].orEmpty().firstOrNull { it.id == id } }
                if (group == null || shownChannels?.groupId != group.id) return@readFavorites
                // The groups underneath, on this group: where Back comes to.
                panel.focusEntry(GROUP + group.id)
                panel.openSub(group.name, wide = true) { favoriteChannels(this, playlistId, group.id) }
                if (channel != null) panel.focusEntry(channel.toString())
            }
        }
    }

    /** Each playlist's favorites per group, read as Assign favorites opens and kept up to date as stars change. */
    private val favoriteCounts = HashMap<String, MutableMap<String, Int>>()

    /** The group whose channels Assign favorites lists, as read when it opened (stars change in it as they're set). */
    private var shownChannels: GroupChannels? = null

    /**
     * Reads [playlistId]'s favorites per group (and [groupId]'s channels), once every star set before is stored, then
     * [then] on the main thread. Asked [from] a window (its session): what was read is kept only while that window is
     * still the one open, so a second press while the first read was on its way can't swap in a list read before the
     * stars set since.
     */
    private fun readFavorites(playlistId: String, groupId: String?, from: Int?, then: () -> Unit) = afterWrites {
        io.execute {
            val counts = db.favoriteCounts(playlistId)
            val channels = groupId?.let { db.channels(GuideScope.Group(playlistId, it)) }
            main.post {
                if (from != null && (!panel.isOpen || panel.session != from)) return@post
                favoriteCounts[playlistId] = counts.toMutableMap()
                if (groupId != null && channels != null) shownChannels = GroupChannels(playlistId, groupId, channels.toMutableList())
                then()
            }
        }
    }

    /** Assign favorites from Manage groups: the playlist's groups, over it. */
    private fun openFavoriteGroups(playlistId: String) {
        val from = panel.session
        // Only into the window it was asked from (it may have closed, or moved on, while they were read).
        readFavorites(playlistId, null, from) {
            panel.openSub("Assign favorites", wide = true) { favoriteGroups(this, playlistId) }
        }
    }

    /**
     * Assign favorites, the groups: the shown ones in the guide's order under a Groups heading, then the hidden ones
     * (dimmed, under their own heading), each with how many favorites it holds. OK opens a group's channels.
     */
    private fun favoriteGroups(p: SidePanel, playlistId: String): Unit = with(p) {
        if (playlists.none { it.id == playlistId }) { message("This playlist was deleted."); return@with }
        val ordered = FavoriteGroups.order(groups[playlistId].orEmpty())
        if (ordered.isEmpty()) { message("This playlist has no groups yet. They appear after its next update."); return@with }
        val shown = ordered.count { !it.hidden }
        // The shown groups under a heading, as the hidden ones that follow are under theirs.
        val layout = FavoriteGroups.Headings(shown, ordered.size)
        longList(object : LongList {
            override val size: Int get() = layout.size

            private fun groupAt(index: Int): ChannelGroup? = layout.groupAt(index)?.let { ordered[it] }

            override fun entry(index: Int): ListEntry {
                val g = groupAt(index) ?: return if (index == layout.shownHeading) {
                    ListEntry("shown", "Groups", kind = ListEntry.Kind.HEADING)
                } else {
                    ListEntry("hidden", "Hidden groups", kind = ListEntry.Kind.HEADING)
                }
                val count = favoriteCounts[playlistId]?.get(g.id) ?: 0
                return ListEntry(GROUP + g.id, g.name, FavoriteGroups.countText(count), faded = g.hidden)
            }

            override fun onClick(index: Int) {
                val g = groupAt(index) ?: return
                openFavoriteChannels(playlistId, g)
            }
        })
    }

    /** A group's channels in Assign favorites, read first (once every star set before is stored). */
    private fun openFavoriteChannels(playlistId: String, g: ChannelGroup) {
        val from = panel.session
        readFavorites(playlistId, g.id, from) {
            panel.openSub(g.name, wide = true) { favoriteChannels(this, playlistId, g.id) }
        }
    }

    /**
     * Assign favorites, a group's channels: number and full name; a favorite is blue with a filled star. OK stars or
     * unstars one at once in place; it's stored behind it, and the guide is told (its rows, the details' star and the
     * Favorites list follow).
     */
    private fun favoriteChannels(p: SidePanel, playlistId: String, groupId: String): Unit = with(p) {
        val shown = shownChannels?.takeIf { it.playlistId == playlistId && it.groupId == groupId }
        if (shown == null || shown.channels.isEmpty()) { message("No channels in this group."); return@with }
        longList(shown)
    }

    private inner class GroupChannels(val playlistId: String, val groupId: String, val channels: MutableList<Channel>) : LongList {
        override val size: Int get() = channels.size

        override fun entry(index: Int): ListEntry = channels[index].let { c ->
            ListEntry(c.key.toString(), "${c.number}  ${c.name}", icon = if (c.favorite) R.drawable.ic_star_filled else R.drawable.ic_star, chosen = c.favorite)
        }

        override fun onClick(index: Int) {
            val c = channels.getOrNull(index) ?: return
            val fav = !c.favorite
            channels[index] = c.copy(favorite = fav)
            favoriteCounts.getOrPut(playlistId) { HashMap() }.let { FavoriteGroups.adjust(it, groupId, fav) }
            panel.changed(index)
            if (released) return
            writes.execute {
                runCatching { db.setFavorite(c.key, fav, System.currentTimeMillis()) }
                    .onFailure { Log.w(TAG, "Favorite not stored: ${it.javaClass.simpleName}") }
                main.post { tellGuide(playlistId) }
            }
        }
    }

    /** [action] on the main thread once every favorite set before is stored (a read after it then sees them all). */
    private fun afterWrites(action: () -> Unit) {
        if (released) return
        writes.execute { main.post { if (!released) action() } }
    }

    // ---- EPG ------------------------------------------------------------------------------------------------------

    /**
     * Playlists & EPG, a category of App settings: the playlists, then the EPG, as in Live FirePlayer Settings. Its own
     * window from the Settings tab ([onClose]); [sub]: over the window that's open (App settings' list), Back returns
     * to it.
     */
    fun openPlaylistsAndEpg(onClose: (() -> Unit)? = null, sub: Boolean = false) {
        val from = panel.session
        reloadThen {
            // Over App settings' list only while it's still the window open (a second press, or Back, meanwhile).
            if (sub && (!panel.isOpen || panel.session != from)) return@reloadThen
            show(SettingsCategory.PLAYLISTS_EPG.title, onClose, sub) { playlistsAndEpg(this, headings = true) }
        }
    }

    /** Live TV's categories of App settings (Playlists & EPG, Live TV) over the window that's open. */
    fun openCategory(category: SettingsCategory) {
        when (category) {
            SettingsCategory.PLAYLISTS_EPG -> openPlaylistsAndEpg(sub = true)
            SettingsCategory.LIVE_TV -> openLiveOptions(sub = true)
            else -> Unit
        }
    }

    private fun show(title: String, onClose: (() -> Unit)?, sub: Boolean, render: SidePanel.() -> Unit) {
        if (sub) panel.openSub(title, render = render) else panel.open(title, onClose, render = render)
    }

    private fun epgValue(s: LiveDb.EpgStatus?): String = when {
        s == null -> NOT_LOADED
        s.status == "OK" -> "${s.programmes} programmes · ${whenText(s.lastSync)}"
        else -> s.status
    }

    /**
     * EPG sources ▾ (closed each time the window opens): how many playlists' EPGs are loaded, its badge marked as a
     * problem when one failed. Open, Manage EPG sources, with when the EPG last updated. Without playlists there's
     * nothing to show ([saysIfNone]: a note says so).
     */
    private fun epgRows(p: SidePanel, saysIfNone: Boolean): Unit = with(p) {
        if (playlists.isEmpty()) { if (saysIfNone) message("No playlists yet."); return@with }
        val epgOpen = isPartOpen("epgSources")
        val loaded = playlists.count { (epg[it.id]?.programmes ?: 0) > 0 }
        val failing = playlists.any { epg[it.id]?.let { s -> s.status != "OK" } == true }
        dropdownRow("EPG sources", "$loaded of ${playlists.size} loaded", Rows.countBadge(loaded, playlists.size, problem = failing), epgOpen) {
            togglePart("epgSources")
            refresh()
        }
        if (epgOpen) {
            row("Manage EPG sources", updatedText(lastEpgUpdate())) {
                openSub("Manage EPG sources", wide = true) { manageEpg(this) }
            }.asSubRow()
        }
    }

    /**
     * Manage EPG sources (user outline): each playlist's EPG with its number of programmes or its status (OK opens that
     * playlist's window), then EPG update interval, Update EPG and Clear EPG, then Days to keep EPG under Storage; the
     * last status and where listings come from at the bottom.
     */
    private fun manageEpg(p: SidePanel): Unit = with(p) {
        header("EPG sources")
        if (playlists.isEmpty()) message("No playlists yet.")
        for (pl in playlists) {
            row(pl.name, epgValue(epg[pl.id])) { openPlaylist(pl.id) }
        }
        header("Updates")
        val hours = listOf(6, 12, 24, 48)
        // Said apart from a playlist's own Update interval (its channels), a different saved value.
        choice("EPG update interval", hours.map { "Every $it hours" }, hours.indexOf(prefs.epgUpdateHours).let { if (it < 0) 2 else it }) { i ->
            prefs.epgUpdateHours = hours[i]
        }
        updateRow(this, "Update EPG", lastEpgUpdate()) { sync.updateEpg() }
        row("Clear EPG") {
            // Focus starts on the safe answer, so a double press doesn't clear; Menu closes it with the window.
            FireDialog.ask(activity, "Clear all programme listings? They load again at the next update.", listOf<Pair<String, () -> Unit>>(
                "Clear" to { sync.clearEpg() },
                "Keep it" to {},
            ), focus = 1, fromPanel = true)
        }
        header("Storage")
        // Provisional: the outline fixes one day of past programmes; whether it's adjustable is still open. Read-only,
        // at full brightness, as the other rows that only say how things stand.
        row("Days to keep EPG", "1 day") {}
        message("Last status: " + lastEpgStatus())
        message(FALLBACK_NOTE)
    }

    /** When the EPG last loaded: the last import that worked (a failed one also records its time); 0 if never. */
    private fun lastEpgUpdate(): Long = epg.values.filter { it.status == "OK" }.maxOfOrNull { it.lastSync } ?: 0L

    /** The most recent EPG result, in words. */
    private fun lastEpgStatus(): String {
        if (sync.busy) return "Updating…"
        val last = epg.values.maxByOrNull { it.lastSync } ?: return "$NOT_LOADED."
        val name = playlists.firstOrNull { it.id == last.source }?.name ?: "EPG"
        return if (last.status == "OK") "$name, ${last.programmes} programmes, ${whenText(last.lastSync)}."
        else "$name: ${last.status.removeSuffix(".")} (${whenText(last.lastSync)})."
    }

    // ---- Live TV (a category of App settings: options kept beside the contextual window) ----------------------------

    /** Its own window from the Settings tab ([onClose]); [sub]: over the window that's open (App settings' list). */
    fun openLiveOptions(onClose: (() -> Unit)? = null, sub: Boolean = false) {
        show(SettingsCategory.LIVE_TV.title, onClose, sub) {
            header("Remote")
            app.fireplayer.ui.menuButtonRow(this, activity)
            channelButtonsRow(this, activity)
            header("Full screen")
            // Never first, as in every list of choices.
            val hide = listOf(0, 4, 6, 10)
            choice("Hide controls after", listOf("Never", "4 seconds", "6 seconds", "10 seconds"), hide.indexOf(prefs.controlsHideSec).let { if (it < 0) hide.indexOf(6) else it }) { i ->
                prefs.controlsHideSec = hide[i]
            }
            header("Guide")
            val secs = listOf(0, 30, 60, 120, 300)
            val labels = listOf("Never", "30 seconds", "1 minute", "2 minutes", "5 minutes")
            choice("Full screen after no key presses", labels, secs.indexOf(prefs.fullScreenAfterSec).let { if (it < 0) secs.indexOf(60) else it }) { i ->
                prefs.fullScreenAfterSec = secs[i]
            }
            header("Playback")
            // Off: Live TV opens on the guide alone until a channel is picked (LivePrefs.playLastOnOpening).
            row(LivePrefs.PLAY_LAST_ON_OPENING, SettingsPanels.onOff(prefs.playLastOnOpening)) {
                prefs.playLastOnOpening = !prefs.playLastOnOpening
                refresh()
            }
            liveBufferRow(this, activity)
            // Multiview (inside full screen): how many pictures at most, and how 3 or 4 open.
            header("Multiview")
            val counts = listOf(2, 3, 4)
            choice(MULTIVIEW_PICTURES, counts.map { "Up to $it" }, counts.indexOf(prefs.multiviewMost).coerceAtLeast(0)) { i ->
                prefs.multiviewMost = counts[i]
            }
            choice(MULTIVIEW_LAYOUT, listOf("In a grid", "One large on top"), if (prefs.multiviewLarge) 1 else 0) { i ->
                prefs.multiviewLarge = i == 1
            }
        }
    }

    private companion object {
        const val TAG = "FirePlayerLive"
        /** How long after the last change here the guide is told about it. */
        const val TELL_MS = 400L
        const val REORDER_NOTE = "Press OK on one to pick it up, move it with Up and Down, and press OK again to put it down. " +
            "The button beside each moves it to the top."
        /** A group's entry key in Manage groups and Assign favorites (focus comes back to it by this). */
        const val GROUP = "g|"
        /** Manage groups' Reorder groups row. */
        const val REORDER = 3
        /**
         * Manage groups: the group switches start after Favorites and Assign favorites, Order and Reorder groups, then
         * Hide or show and its three rows.
         */
        const val FIRST_GROUP = 8
        const val CHECKING = "Checking…"
        const val NEVER_UPDATED = "Never updated"
        const val NOT_LOADED = "Not loaded"
        /** Where a playlist's listings come from: said the same way in its window and in Manage EPG sources. */
        const val FALLBACK_NOTE = "Each playlist's guide comes from its own EPG. A playlist without listings of its own " +
            "borrows other playlists' listings, only for channels with exactly the same EPG id."
    }
}
