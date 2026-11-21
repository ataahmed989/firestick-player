package app.fireplayer.vod.ui

import android.app.Activity
import android.content.Context
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.Gravity
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.LinearLayout
import app.fireplayer.R
import app.fireplayer.data.Prefs
import app.fireplayer.live.ChannelKey
import app.fireplayer.live.LiveDb
import app.fireplayer.live.LiveSearch
import app.fireplayer.live.ui.LiveSearchView
import app.fireplayer.live.ui.HoldPopup
import app.fireplayer.live.ui.PipRules
import app.fireplayer.live.ui.PressHold
import app.fireplayer.shell.RailMotion
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.Buttons
import app.fireplayer.ui.FireDialog
import app.fireplayer.ui.Glass
import app.fireplayer.ui.ImageLoader
import app.fireplayer.ui.SidePanel
import app.fireplayer.ui.Spring
import app.fireplayer.vod.Cancel
import app.fireplayer.vod.Card
import app.fireplayer.vod.Kind
import app.fireplayer.vod.Page
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.Sources
import app.fireplayer.vod.play.VodLaunch
import app.fireplayer.vod.RowSpec
import app.fireplayer.vod.RowStyle
import app.fireplayer.vod.Source
import app.fireplayer.vod.Title
import app.fireplayer.vod.VodRepository
import app.fireplayer.vod.hasImdbId
import app.fireplayer.vod.data.Service
import app.fireplayer.vod.data.Services
import app.fireplayer.vod.data.TmdbTrending
import app.fireplayer.vod.data.SearchHistory
import app.fireplayer.vod.data.SearchHistoryRules
import app.fireplayer.vod.data.TabRows
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * One of Films & Shows' pages: Home, Movies or TV Shows (design-brief.md §6.3). Under a name-and-clock line, a header
 * (195 dp; on Home with the reserved top-right window) and the page's rows, from [VodRepository.rows]. Rows that turn
 * out empty are hidden.
 *
 * Moving: Left/Right go card by card along a row, which glides like the Episodes sheet ([TitleRow]); Up/Down go row by
 * row, the page gliding with a Spring: below the first row the header slides away and the chosen row sits just under
 * the name line; back on the first row the header returns. Up from the first row goes to the banner's Watch now and
 * Details buttons ([VodHeader.Listener]), under which the banner goes on turning while no key is pressed; on Home,
 * Right from Details goes into Recent channels ([RecentWindow]).
 * Left from a row's first card goes to the rail. OK on a
 * poster opens its title page ([TitleStack], over the page; Back returns to the poster), on a Continue card says
 * playback comes next (a later step), on See all or a genre opens [SeeAllPage]; OK held on a title opens the small
 * window ([HoldWindow]): + Add to list (or − Remove from list), Mark watched, and on Continue cards Remove.
 *
 * My List ([VodTab.MY_LIST]) is the same page with two rows (its films, its series) under a header that never slides
 * away and shows the focused title (the Continue row's rule: words at once, picture half a second later; no rotation);
 * the rows glide under it. Search ([VodTab.SEARCH]) has no header: its bar and trash button ([SearchBar]) take the
 * header's place, the history ([SearchHistoryList]) under them until results come, then two rows of results (films, TV
 * shows), each only when it has titles. Up from the results goes back to the bar; Back from them goes back to the
 * history (an empty bar), and Back again to the rail.
 *
 * Lean: pictures only for the rows on screen and near the chosen card, let go when the page is hidden or stopped;
 * rows laid out once, then moved by translation and scale alone; cached rows shown at once and refreshed behind.
 */
class VodPage(
    private val ctx: Context,
    private val host: Host,
    private val repoOf: () -> VodRepository?,
    val tab: VodTab,
    /**
     * A streaming service's page: Home's layout (the banner, Channel history) with the service's wordmark for a name
     * and its own rows ([Services.rows]); null on the tabs' pages.
     */
    val service: Service? = null,
) : TitleRow.Listener, SeeAllPage.Host, HoldWindow.Host, TitleStack.Host, VodHeader.Listener {

    /** What the shell gives a page: the same as Live TV's (HomeActivity implements both). */
    interface Host {
        val tokens: Tokens
        /** The settings window, which also shows a title's Details. */
        val sidePanel: SidePanel
        fun focusRail()
        fun railHasFocus(): Boolean
        /** The settings window or a dialog is open over the page: its header holds still. */
        fun overlayOpen(): Boolean
        /** A film or an episode starts in FirePlayer's player: the shell makes room first (film mode, §6.6). */
        fun play(request: PlayRequest)
        /** OK on one of Home's recent channels: Live TV opens on it. */
        fun openChannel(key: ChannelKey) {}
        /** OK on one of Home's streaming-service cards: that service's page opens. */
        fun openService(service: Service) {}
        /** Live TV search: [key] plays full screen on Live TV ([at] null), or the guide shows it ([at]: a later programme's start). */
        fun openLiveResult(key: ChannelKey, at: Long?) {}
        /** Back from Live TV search's starting view: the Live TV tab. */
        fun backToLive() {}
        /** Assign favorites on a channel's group, the channel selected (a channel's small window in Live TV search). */
        fun openAssignFavorites(playlistId: String, groupId: String?, channel: ChannelKey?) {}
        /** OK on one of the live picture's buttons (Volume, Full screen, Close; [PipRules]). */
        fun pipButton(button: PipRules.Button) {}
        /** The live picture's box was laid out, covered or uncovered: the picture is put there again ([pipSpot]). */
        fun pipMoved() {}
        /**
         * One frame of Home's live picture's window changing width ([pipSpotNow] has where it's drawn now): the picture
         * follows it by transforms only; [pipMoved] puts it there for good at the end.
         */
        fun pipResize() {}
        /** The page glides: the live picture moves with its box by [dy] px (0 at rest, less while the rows are up). */
        fun pipFollow(dy: Float) {}
        /** The live picture's strip is showing (focus came to it): what's on is read again for it. */
        fun pipStripShown() {}
    }

    private val t = host.tokens
    private val main = Handler(Looper.getMainLooper())
    /** The repository's page (Home, Movies, TV Shows); null on My List and Search. */
    private val page: Page? = tab.page
    private val myList = tab == VodTab.MY_LIST
    private val search = tab == VodTab.SEARCH
    private var repoRef: VodRepository? = repoOf()
    override val repo: VodRepository? get() = repoRef

    val root = FrameLayout(ctx)
    private val nameLine = LinearLayout(ctx)
    private val nameText = t.label(ctx, VodSizes.PAGE_NAME)
    private val clockText = t.label(ctx, VodSizes.CLOCK)
    /** Under the name line; clips, so nothing shows above it. */
    private val viewport = FrameLayout(ctx)
    private val column = if (search) PageColumn(ctx, t, VodSizes.searchTopH(), VodSizes.SEARCH_GAP) else PageColumn(ctx, t)
    private val headerBand = FrameLayout(ctx)
    private val headerW = if (page == Page.HOME) VodSizes.homeHeaderW() else VodSizes.tabHeaderW()
    /** My List's banner holds still above its rows: shorter, and without buttons ([VodSizes.MY_LIST_HEADER_H]). */
    private val headerH = if (myList) VodSizes.MY_LIST_HEADER_H else VodSizes.HEADER_H
    /** Home's narrows beside the live picture's 16:9 window ([VodSizes.homeHeaderW] (pip), [topRight]). */
    private val header = VodHeader(
        ctx, t, headerW, headerH, actions = !myList,
        narrowW = if (page == Page.HOME) VodSizes.homeHeaderW(pip = true) else headerW,
    )
    /** OK on one of the banner's buttons: its release acts on the title that showed when it went down. */
    private val headerPress = HeaderPress<VodHeader.Button, Card>()
    private val window: RecentWindow? = if (page == Page.HOME) RecentWindow(ctx, t) else null
    /** Search: the bar and the trash button in the header's place, the history under them, kept on this TV. */
    private val searchBar: SearchBar? = if (search) SearchBar(ctx, t) else null
    private val historyList: SearchHistoryList? = if (search) SearchHistoryList(ctx, t) else null
    /** Search: the kind searched now (the pills above the bar), and each kind's own history and last words. */
    private var kind = SearchKind.VOD
    private val pills: SearchKindPills? = if (search) SearchKindPills(ctx, t) else null
    private val histories: Map<SearchKind, SearchHistory> =
        if (search) SearchKind.entries.associateWith { SearchHistory.of(ctx, it.historyKey) } else emptyMap()
    private val history: SearchHistory? get() = histories[kind]
    private val lastWords = SearchRules.LastWords()
    /** Search > Live TV: its results under the bar, made the first time that kind is chosen. */
    private var liveResults: LiveSearchView? = null
    /** Search: "Searching..." in the history's place, from a search being asked for until its first answer. */
    private val searchingNote: TextView? = if (search) t.label(ctx, VodSizes.NOTE).apply {
        text = VodSizes.SEARCHING_NOTE
        visibility = View.GONE
    } else null
    private var searchBack = SearchRules.Back.RAIL
    /** Search: the words the result rows are for ("": none, the history shows). */
    private var query = ""
    /** Search: the words a "nothing found" notice was shown for. */
    private var noticeFor: String? = null
    private var searchBackDown = false
    /** My List: the title the header shows. */
    private var headerId: String? = null
    private val rows = ArrayList<TitleRow>()
    private var rowsBuilt = false
    private var seeAll: SeeAllPage? = null
    /** The row and item See all was opened from. */
    private var seeAllFrom: Pair<TitleRow, Int>? = null
    /** Search: each row's answer for the words now, until both have come ([holdSearchAnswer]). */
    private val heldAnswers = HashMap<TitleRow, Pair<List<Card>, Boolean>>()
    /** Search: the rows' order for the words searched now has been decided (answers that come later don't move them). */
    private var searchOrderSettled = false
    private val prefs by lazy { Prefs(ctx) }

    private val empty = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
    private val emptyText = t.label(ctx, VodSizes.EMPTY).apply { maxLines = 3; gravity = Gravity.CENTER }
    private val emptyButton = Buttons.textPill(ctx, t, "Try again", VodSizes.TRY_AGAIN, VodSizes.TRY_PAD_X, VodSizes.TRY_PAD_Y)
    private val notice = t.label(ctx, VodSizes.NOTICE).apply { maxLines = 2 }
    /** The title pages opened over this page (above its rows, See all and the message; under the notice and window). */
    private val titleHost = FrameLayout(ctx).apply { visibility = View.GONE }
    override val hold = HoldWindow(t, root, this)
    private val titles = TitleStack(ctx, t, this, titleHost)
    private val press = PressHold(ViewConfiguration.getLongPressTimeout().toLong())
    /** The column's upward offset, px: 0 shows the header. */
    private val pageSpring = Spring(omega = VodSizes.PAGE_OMEGA, settle = VodSizes.PAGE_SETTLE) {
        column.translationY = -it
        // The banner's words keep clear of its top while the page hides a little of it (My List's holds still).
        if (!myList && !search) header.setHiddenTop(it)
        // The live picture goes up and down with the banner (or the bar), frame by frame (translations only).
        if (pipOn && shown) {
            pipBox?.translationY = -it
            host.pipFollow(-it)
        }
    }

    /**
     * The live picture's box ([PipRules]): on Home in Recent channels' place, 16:9 (wider than Recent channels: the
     * banner narrows beside it, [topRight]), on Search in the top right beside the page's name; shown while a channel
     * plays on in it ([setPip]). The picture is the shell's (behind the window).
     */
    private val pipBox: PipBox? = if (page == Page.HOME || search) PipBox(ctx, t) else null
    /** The live picture is on this page. */
    private var pipOn = false
    /** Where focus was when it came to the picture's strip: Back (and Left from Volume) go back there. */
    private var pipFrom: View? = null
    private var pipBackDown = false

    /**
     * Home's top right: 0 the banner at its full width beside Recent channels, 1 the narrower banner beside the live
     * picture's 16:9 window. Between the two while they change places in one motion ([RailMotion]: the rail's time and
     * curve), drawn frame by frame with nothing laid out ([drawTopRight]) and laid out once at its end
     * ([settleTopRight]); aimed the other way part-way, it turns back from where it is.
     */
    private val topRight = RailMotion(0f)
    private var topRightTicking = false
    private var topRightFirst = false
    private var topRightLastNanos = 0L
    private val topRightFrame = Choreographer.FrameCallback { onTopRightFrame(it) }

    /** Where the open title pages were opened from: focus goes back there when the last one closes. */
    private sealed class Origin {
        abstract val id: String
        data class InRow(val row: TitleRow, val index: Int, override val id: String) : Origin()
        data class InGrid(override val id: String) : Origin()
        /** One of the banner's buttons. */
        data class InHeader(val button: VodHeader.Button, override val id: String) : Origin()
    }
    private var origin: Origin? = null
    /** A title page covers the page: its own content is hidden (and draws nothing) meanwhile. */
    private var covered = false

    private var shown = false
    private var started = false
    private var resumed = false
    private var released = false
    private var lastLoad = 0L
    /** Focus was asked for before any row showed: it comes in with the first row. */
    private var focusPending = false
    /** What held focus when it was asked for (the rail's tab): focus moved on from it, the wait ends. */
    private var pendingFrom: View? = null
    /** The row focus was last in (focus memory for the rail, windows and tab switches). */
    private var lastRow: TitleRow? = null
    /** The row holding focus now, or null. */
    private var focusedRow: TitleRow? = null
    /** The row the page's offset is anchored to, and its top then: rows appearing above it don't move it on screen. */
    private var anchorRow: TitleRow? = null
    private var anchorTop = -1
    private var headerCancel: Cancel? = null
    private var backClosedPopup = false
    private var seeAllBackDown = false
    private val libraryListener: () -> Unit = { onLibraryChanged() }
    private val rowsListener: () -> Unit = { applyRows() }

    private val pageName = service?.label ?: tab.title
    /** A service page's wordmark in the name's place (the name shows until it comes, and over See all). */
    private val nameLogo: android.widget.ImageView? = if (service == null) null else android.widget.ImageView(ctx).apply {
        adjustViewBounds = true
        baselineAlignBottom = true
        visibility = View.GONE
        if (service.mono) setColorFilter(VodSizes.LOGO_MONO)
    }
    private var nameLogoOn = false
    private val nameLogoTarget = object : app.fireplayer.ui.ImageTarget {
        override var imageKey: String? = null
        override fun setImage(bitmap: android.graphics.Bitmap?, fromMemory: Boolean) {
            nameLogo?.setImageBitmap(bitmap)
            nameLogoOn = bitmap != null
            updateName()
        }
    }
    /** A collection's title being looked up before it opens ([resolveThen]). */
    private var resolveCancel: Cancel? = null

    init {
        root.visibility = View.GONE
        root.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        val pageX = t.dp(VodSizes.PAGE_X)

        // The name line: the page's name, then the date and time.
        nameLine.orientation = LinearLayout.HORIZONTAL
        nameLine.isBaselineAligned = true
        nameText.text = pageName
        nameLogo?.let { nameLine.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, t.dp(VodSizes.NAME_LOGO_H))) }
        nameLine.addView(nameText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Fixed width: the clock's new minute only redraws it.
        nameLine.addView(clockText, LinearLayout.LayoutParams(t.dp(VodSizes.CLOCK_W), ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = t.dp(VodSizes.CLOCK_GAP) })
        root.addView(nameLine, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = pageX
            topMargin = t.dp(VodSizes.NAME_TOP)
        })

        // The header band: the header card, and on Home the window beside it.
        headerBand.clipChildren = false
        // Home's reaches out towards the rail, its words in line with the rows' titles ([VodSizes.HOME_HEADER_X]).
        val headerX = if (page == Page.HOME) t.dp(VodSizes.HOME_HEADER_X) else pageX
        headerBand.addView(header, FrameLayout.LayoutParams(t.dp(headerW), t.dp(headerH)).apply { leftMargin = headerX })
        window?.let {
            headerBand.addView(it, FrameLayout.LayoutParams(t.dp(VodSizes.WINDOW_W), t.dp(VodSizes.HEADER_H)).apply {
                leftMargin = t.dp(VodSizes.windowX())
            })
            // Left: back to the banner's Details; Down past its last channel: the first row; OK: Live TV on it.
            it.onLeft = { if (!header.focusButton(VodHeader.Button.DETAILS)) firstRowWithItems()?.focusChosen() }
            it.onDown = { firstRowWithItems()?.focusChosen() }
            it.onPick = { channel -> host.openChannel(channel.key) }
        }
        header.blocked = { host.overlayOpen() || hold.isOpen }
        header.listener = this
        when {
            // My List: the header holds still above the rows, which glide under it.
            myList -> {
                // Shown with the first title (updateListHeader).
                headerBand.visibility = View.INVISIBLE
                root.addView(headerBand, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, t.dp(headerH)).apply {
                    topMargin = t.dp(VodSizes.VIEWPORT_TOP)
                })
            }
            // Search: the kind pills on a line above the bar.
            search -> searchBar?.let { bar ->
                val top = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    clipChildren = false
                    clipToPadding = false
                    setPadding(pageX, 0, 0, 0)
                }
                top.addView(pills, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, t.dp(VodSizes.SEARCH_KIND_H)))
                top.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = t.dp(VodSizes.SEARCH_KIND_GAP)
                })
                column.header = top
            }
            else -> column.header = headerBand
        }
        column.onRowsMoved = { onRowsMoved() }
        viewport.clipChildren = true
        viewport.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(viewport, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = t.dp(if (myList) VodSizes.fixedRowsTop() else VodSizes.VIEWPORT_TOP)
        })
        // The live picture's box, over the rows (which never reach it) and under the page's windows; the picture's
        // place is its margins ([pipSpot]).
        pipBox?.let { box ->
            box.visibility = View.INVISIBLE
            // Home's laid out where the picture stays (its 16:9 window); drawn narrower as it widens there.
            val w = if (search) VodSizes.SEARCH_PIP_W else VodSizes.windowW(pip = true)
            val h = if (search) VodSizes.SEARCH_PIP_H else VodSizes.HEADER_H
            root.addView(box, FrameLayout.LayoutParams(t.dp(w), t.dp(h)).apply {
                leftMargin = t.dp(if (search) VodSizes.searchPipX() else VodSizes.windowX(pip = true))
                topMargin = t.dp(if (search) VodSizes.SEARCH_PIP_TOP else VodSizes.VIEWPORT_TOP)
            })
            box.onButton = { host.pipButton(it) }
            box.onOut = { pipOut(it) }
            box.onStripShown = { host.pipStripShown() }
        }
        historyList?.let { list ->
            root.addView(list, FrameLayout.LayoutParams(t.dp(VodSizes.searchBarW()), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                leftMargin = pageX
                topMargin = t.dp(VodSizes.searchListTop())
                bottomMargin = t.dp(VodSizes.SEARCH_LIST_BOTTOM)
            })
            searchingNote?.let { note ->
                root.addView(note, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = pageX
                    topMargin = t.dp(VodSizes.searchListTop())
                })
            }
            column.roomUnderHeader = t.dp(VodSizes.SEARCH_UNDER_BAR)
            list.show(history?.entries.orEmpty())
        }
        searchBar?.let { bar ->
            bar.onQuery = { words -> searchKind(words) }
            bar.onSubmit = { words ->
                searchKind(words)
                rememberSearch()
            }
            bar.onArrow = { keyCode, fromTrash -> onBarArrow(keyCode, fromTrash) }
            bar.onTrash = { confirmClearHistory() }
            bar.onFocused = { onBarFocused() }
        }
        historyList?.apply {
            onPick = { words -> onHistoryPick(words) }
            onHold = { words, row -> onHistoryHold(words, row) }
            onUpFromTop = { searchBar?.focusBar() }
            onLeft = { host.focusRail() }
        }
        pills?.apply {
            onPick = { k -> switchKind(k) }
            onDown = { searchBar?.focusBar() }
            onLeft = { host.focusRail() }
        }

        // Nothing at all to show: a way to try again.
        emptyText.text = if (myList) MY_LIST_EMPTY else LOAD_FAILED
        // My List with nothing in it: only the line (there's nothing to try again).
        if (myList) emptyButton.visibility = View.GONE
        emptyButton.apply {
            setOnClickListener { retry() }
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { host.focusRail(); true }
                else keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            }
        }
        empty.addView(emptyText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        empty.addView(emptyButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = t.dp(VodSizes.TRY_GAP)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        empty.visibility = View.GONE
        root.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = t.dp(VodSizes.VIEWPORT_TOP)
        })

        root.addView(titleHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        notice.setPadding(t.dp(VodSizes.NOTICE_PAD_X), t.dp(VodSizes.NOTICE_PAD_Y), t.dp(VodSizes.NOTICE_PAD_X), t.dp(VodSizes.NOTICE_PAD_Y))
        notice.visibility = View.GONE
        root.addView(notice, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = t.dp(VodSizes.NOTICE_BOTTOM)
        })
        hold.attach()
        buildRows()
        applyLook()
        if (service != null) ImageLoader.init(ctx)
        if (service != null) ImageLoader.load(
            Services.logoUrl(service.logo), nameLogoTarget, t.dp(VodSizes.NAME_LOGO_W), t.dp(VodSizes.NAME_LOGO_H),
            config = android.graphics.Bitmap.Config.ARGB_8888, fit = ImageLoader.Fit.INSIDE,
        )
    }

    /** The name line shows the service's wordmark once it has come, the page's name otherwise (and over See all). */
    private fun updateName() {
        val logo = nameLogo ?: return
        val on = nameLogoOn && seeAll?.isOpen != true
        logo.visibility = if (on) View.VISIBLE else View.GONE
        nameText.visibility = if (on) View.GONE else View.VISIBLE
    }

    // ---- Lifecycle --------------------------------------------------------------------------------------------------

    /** The page's tab is selected: shown, and running while the screen is started. */
    fun show() {
        if (released) return
        shown = true
        root.visibility = View.VISIBLE
        resume()
    }

    /** Another tab: hidden; the title pages, See all and the small window close, its pictures go. */
    fun hide() {
        shown = false
        focusPending = false
        pendingFrom = null
        // A Back held to full screen went with its release unseen here.
        pipBackDown = false
        searchBackDown = false
        resolveCancel?.cancel()
        resolveCancel = null
        if (hold.isOpen) hold.close(fade = false, focusBack = false)
        titles.closeAll(focusBack = false)
        closeSeeAll(focusBack = false)
        // Search opens on its history again, an empty bar, whenever its tab is chosen anew.
        if (search) {
            resetSearch(focus = false)
            lastWords.clear()
            if (kind != SearchKind.VOD) switchKind(SearchKind.VOD)
        }
        pause()
        root.visibility = View.GONE
    }

    /** The screen started (onStart). */
    fun start() {
        started = true
        resume()
    }

    /** The screen stopped (onStop): timers stop, pictures go. */
    fun stop() {
        started = false
        pause()
    }

    fun release() {
        pause()
        stopTopRightFrames()
        released = true
        repo?.removeRowsListener(rowsListener)
        for (row in rows) row.cancel?.cancel()
        headerCancel?.cancel()
        resolveCancel?.cancel()
        ImageLoader.release(nameLogoTarget)
        window?.release()
        searchBar?.cancel()
        liveResults?.release()
        titles.release()
        seeAll?.release()
        if (hold.isOpen) hold.close(fade = false, focusBack = false)
        main.removeCallbacksAndMessages(null)
    }

    private fun resume() {
        if (!shown || !started || resumed || released) return
        resumed = true
        repo?.addLibraryListener(libraryListener)
        tickClock()
        window?.refresh()
        val now = SystemClock.elapsedRealtime()
        if (lastLoad == 0L || now - lastLoad > RELOAD_MS) loadAll() else reloadLibrary()
        updateHeaderPause()
        // The stack runs again even with no title page open: one opened later must start (it used to stay paused
        // after a film started from Continue Watching, so its details, sources and Play were dead).
        titles.resume()
        if (titles.isOpen) {
            // Behind the title page, only the poster it was opened from keeps its picture (for coming back).
            originRow()?.let { row -> row.bindImages(row.chosen..row.chosen) }
            return
        }
        updateImages()
        if (seeAll?.isOpen == true) seeAll?.bindImages()
    }

    private fun pause() {
        // Out of sight, Home's top right is where it was heading, at once.
        finishTopRight()
        if (!resumed) return
        resumed = false
        repo?.removeLibraryListener(libraryListener)
        main.removeCallbacks(clockTick)
        header.release()
        titles.pause()
        if (pageSpring.moving) pageSpring.snap(pageSpring.target)
        for (row in rows) {
            row.motion.settle()
            row.releaseImages()
        }
        seeAll?.settle()
        seeAll?.releaseImages()
    }

    /** Memory is short: pictures of rows other than the chosen one go (all of them under a title page). */
    fun trimImages() {
        if (titles.isOpen) {
            for (row in rows) row.releaseImages()
            seeAll?.releaseImages()
            titles.trim()
            return
        }
        val keep = focusedRow ?: lastRow
        for (row in rows) if (row !== keep) row.releaseImages()
    }

    /** The look changed: surfaces made again, cards redrawn. */
    fun applyLook() {
        header.applyLook()
        window?.applyLook()
        pipBox?.applyLook()
        searchBar?.applyLook()
        historyList?.applyLook()
        pills?.applyLook()
        liveResults?.applyLook()
        Glass.overlay(notice, Glass.Kind.POPUP)
        Glass.background(emptyButton, Glass.Kind.PILL)
        hold.applyLook()
        for (row in rows) row.applyLook()
        titles.applyLook()
    }

    /** Whether a title page is open over the page. */
    fun hasTitle(): Boolean = titles.isOpen

    // ---- Focus ------------------------------------------------------------------------------------------------------

    /**
     * Into the page (from the rail): the title page open over it, else the card chosen last, in See all when it's
     * open; else the first row's first.
     */
    override fun takeFocus() {
        if (!shown) return
        if (titles.isOpen) {
            titles.takeFocus()
            return
        }
        val sa = seeAll
        if (sa != null && sa.isOpen) {
            sa.takeFocus()
            return
        }
        if (empty.visibility == View.VISIBLE) {
            emptyButton.requestFocus()
            return
        }
        val bar = searchBar
        if (bar != null) {
            // Search: the results row chosen last while they show, else the bar.
            val row = lastRow?.takeIf { it.visibility == View.VISIBLE && it.items.isNotEmpty() }
            if (row == null || !row.focusChosen()) bar.focusBar()
            return
        }
        val row = lastRow?.takeIf { it.visibility == View.VISIBLE && it.items.isNotEmpty() } ?: firstReadyRow()
        if (row == null || !row.focusChosen()) waitForRows()
    }

    /** Focus asked for before the page's first row is in: it goes there when it comes, unless the viewer moves on. */
    private fun waitForRows() {
        focusPending = true
        pendingFrom = root.rootView.findFocus()
    }

    /**
     * Whether focus asked for while loading is still wanted: not once the viewer has moved on from where it was (to
     * another tab in the rail). Where it was may have gone meanwhile (a window that closed): then it still is.
     */
    private fun stillPending(): Boolean {
        if (!focusPending) return false
        val from = pendingFrom
        val now = root.rootView.findFocus()
        if (from != null && now != null && now !== from && from.isShown) {
            focusPending = false
            pendingFrom = null
        }
        return focusPending
    }

    /**
     * The page's first row with titles, once every row above it has answered (a row answering later would come in
     * above it, and the page would slide its header away); null while it waits.
     */
    private fun firstReadyRow(): TitleRow? =
        rows.firstOrNull { !it.answered || it.visibility == View.VISIBLE }
            ?.takeIf { it.visibility == View.VISIBLE && it.items.isNotEmpty() }

    /** The page's Back: closes the small window, then the title pages ([TitleStack]), then See all. False when it's the shell's (to the rail). */
    fun dispatchKey(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return false
        // The live picture's strip: Back goes back to where focus was on the page (held, the shell opens full screen).
        val inStrip = pipBox?.hasFocus() == true
        if (pipBackDown || PipRules.back(held = false, inStrip = inStrip, playing = pipOn, pageInFront = true) == PipRules.BackAct.RETURN_FOCUS) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                pipBackDown = true
            } else if (event.action == KeyEvent.ACTION_UP && pipBackDown) {
                pipBackDown = false
                if (!event.isCanceled) focusBackFromPip()
            }
            return true
        }
        if (hold.isOpen || backClosedPopup) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                hold.close()
                backClosedPopup = true
            } else if (event.action == KeyEvent.ACTION_UP) {
                backClosedPopup = false
            }
            return true
        }
        if (titles.dispatchKey(event)) return true
        if (seeAll?.isOpen == true) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                seeAllBackDown = true
            } else if (event.action == KeyEvent.ACTION_UP && seeAllBackDown) {
                seeAllBackDown = false
                closeSeeAll(focusBack = true)
            }
            return true
        }
        seeAllBackDown = false
        // Search: Back from the results (or words in the bar) goes back to the history, an empty bar; from there, to
        // the rail (the shell's).
        // Live TV search's starting view: back to Live TV.
        val back = if (!search) SearchRules.Back.RAIL else if (searchBackDown) searchBack else SearchRules.back(kind, showsSearch())
        if (back != SearchRules.Back.RAIL) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                searchBackDown = true
                searchBack = back
            } else if (event.action == KeyEvent.ACTION_UP && searchBackDown) {
                searchBackDown = false
                if (back == SearchRules.Back.CLEAR) resetSearch(focus = true) else host.backToLive()
            }
            return true
        }
        return false
    }

    private fun visibleRows(): List<TitleRow> = rows.filter { it.visibility == View.VISIBLE }

    /**
     * Where the column goes with [row] chosen: 0 on the first row (the header shows), else that row's top. A first row
     * of posters is too tall to show whole under the banner: the column then moves up just as far as it needs. Never
     * past the last row sitting at the bottom of the screen (the row above it stays in view).
     */
    private fun targetFor(row: TitleRow?): Float {
        if (row == null) return 0f
        val vis = visibleRows()
        val k = vis.indexOf(row)
        if (k < 0) return pageSpring.target
        val viewH = if (myList) VodSizes.SCREEN_H - VodSizes.fixedRowsTop() else VodSizes.VIEWPORT_H
        val last = vis.last()
        val end = column.topOf(last).coerceAtLeast(0) + last.shownHeight + t.dpf(VodSizes.ROW_GAP)
        return RowMath.pageTarget(k, 0, row.shownHeight.toFloat(), t.dpf(viewH), end) { column.topOf(vis[it]).coerceAtLeast(0).toFloat() }
    }

    /** The first row shown that has something to focus (where Down from the banner or Recent channels goes). */
    private fun firstRowWithItems(): TitleRow? = visibleRows().firstOrNull()?.takeIf { it.items.isNotEmpty() }

    /** Whether the banner shows (not slid away with the rows, nor hidden). */
    private fun headerShows(): Boolean =
        if (myList) headerBand.visibility == View.VISIBLE else !search && pageSpring.target < t.dpf(VodSizes.HEADER_H)

    override fun onItemFocused(row: TitleRow, index: Int) {
        focusPending = false
        pendingFrom = null
        press.cancel()
        if (focusedRow !== row || anchorRow !== row) {
            anchorRow = row
            anchorTop = column.topOf(row)
        }
        focusedRow = row
        lastRow = row
        pageSpring.glideTo(targetFor(row))
        val card = if (row.isSeeAll(index)) null else row.cards.getOrNull(index)
        if (card != null && (myList || followsContinue(row))) followCard(card) else if (!myList) header.unfollow()
        updateHeaderPause()
        updateImages()
    }

    override fun onRowLeft(row: TitleRow): Boolean {
        // Into a title page opened from it: the card stays grown, the row as it was, until the viewer comes back.
        if (titles.isOpen && originRow() === row) return false
        // Into its small window: the card stays grown and the header on its title until the window closes.
        if (hold.isOpen && hold.heldRow === row) return false
        if (focusedRow === row) focusedRow = null
        press.cancel()
        // New cards held back while the viewer was in the row.
        row.pending?.let { (cards, seeAll) -> showCards(row, cards, seeAll) }
        // Anywhere but another Continue card (the banner's buttons too): the banner goes back to its feed.
        // Up to the banner's own buttons keeps the card's title, so Watch now and Details act on the title the viewer
        // came from; the banner idles on from it after the usual wait. Anywhere else, it goes back to its feed at once.
        if (row.spec.continueRow && focusedRow?.spec?.continueRow != true && !header.hasButtonFocus) header.unfollow()
        return true
    }

    override fun onItemKey(row: TitleRow, index: Int, keyCode: Int, event: KeyEvent): Boolean {
        if (isOk(keyCode)) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (press.down(event.repeatCount, event.isLongPress, event.eventTime - event.downTime)) onHold(row, index)
                KeyEvent.ACTION_UP -> if (press.up(event.isCanceled)) onPick(row, index)
            }
            return true
        }
        val dpad = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        if (event.action != KeyEvent.ACTION_DOWN) return dpad
        // Any other key between a press and its release: that release does nothing.
        press.cancel()
        when (keyCode) {
            // One card per press, also while held: the row glides behind.
            KeyEvent.KEYCODE_DPAD_RIGHT -> row.items.getOrNull(index + 1)?.requestFocus()
            KeyEvent.KEYCODE_DPAD_LEFT -> if (index == 0) host.focusRail() else row.items.getOrNull(index - 1)?.requestFocus()
            KeyEvent.KEYCODE_DPAD_DOWN -> moveRow(row, 1)
            KeyEvent.KEYCODE_DPAD_UP -> moveRow(row, -1)
            else -> return false
        }
        return true
    }

    /** Up or Down: the row above or below (shown rows only; no wrapping, a held key stops at the end). */
    private fun moveRow(row: TitleRow, dir: Int) {
        val vis = visibleRows()
        val k = vis.indexOf(row)
        val to = vis.getOrNull(k + dir)
        when {
            to != null -> to.focusChosen()
            dir > 0 -> Unit
            // Search: Up from the first row of results, back to the bar (the results stay).
            searchBar != null -> searchBar.focusBar()
            // Up from the first row: the banner's Watch now.
            k == 0 && headerShows() -> header.focusButton(VodHeader.Button.WATCH)
        }
    }

    // ---- The banner's buttons (VodHeader.Listener) -------------------------------------------------------------------

    override fun onHeaderFocus(has: Boolean) {
        if (has) {
            focusPending = false
            pendingFrom = null
            press.cancel()
            // The banner whole (a first row of posters had moved it up a little); rows that come now leave it there.
            anchorRow = null
            anchorTop = -1
            if (!myList) pageSpring.glideTo(0f)
            return
        }
        headerPress.cancel()
        // Focus left the buttons: the banner goes back to its feed unless it went back into the Continue row (or into
        // the title page Details opened, which comes back to the button).
        if (!titles.isOpen && focusedRow?.spec?.continueRow != true) header.unfollow()
        updateHeaderPause()
    }

    override fun onHeaderEmptied() {
        if (firstRowWithItems()?.focusChosen() != true) focusFallback()
    }

    /**
     * A key on Watch now or Details: Left and Right between them (Left from Watch now to the rail; on Home, Right from
     * Details to Recent channels), Down back to the first row, OK (on its release) plays or opens the title whose words
     * showed when it went down ([HeaderPress]: the banner goes on turning under the buttons).
     */
    override fun onHeaderKey(button: VodHeader.Button, keyCode: Int, event: KeyEvent): Boolean {
        if (isOk(keyCode)) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> headerPress.down(button, header.shownCard, event.repeatCount)
                KeyEvent.ACTION_UP -> headerPress.up(button, event.isCanceled)?.let { onHeaderPick(button, it) }
            }
            return true
        }
        val dpad = keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
        if (event.action != KeyEvent.ACTION_DOWN) return dpad
        headerPress.cancel()
        val watch = button == VodHeader.Button.WATCH
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (watch) host.focusRail() else header.focusButton(VodHeader.Button.WATCH)
            // Right from Details: the live picture when it plays here (its strip, on Volume), else Recent channels
            // (where Home's top right was going, at once: focus lands on it as it will stay).
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (watch) {
                header.focusButton(VodHeader.Button.DETAILS)
            } else {
                finishTopRight()
                if (!focusPip()) window?.takeFocus()
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> firstRowWithItems()?.focusChosen()
            // The banner moves on to its next title (a held Up: one per change, never mid-change).
            KeyEvent.KEYCODE_DPAD_UP -> header.stepUp(repeat = event.repeatCount > 0)
            else -> return false
        }
        return true
    }

    /**
     * Watch now: [card]'s title plays as from a Continue card (a film, or the episode its series goes on with: the one
     * played last, from where it stopped, or the next after it once finished, across seasons; a series never played,
     * or with nothing left to watch, opens its title page, which knows its episodes). Details: its title page.
     */
    private fun onHeaderPick(button: VodHeader.Button, card: Card) {
        val from = Origin.InHeader(button, card.title.id)
        if (button == VodHeader.Button.WATCH) playContinue(card, from) else openTitle(card, from)
    }

    private fun isOk(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun onPick(row: TitleRow, index: Int) {
        when {
            row.style == RowStyle.GENRES -> row.genres.getOrNull(index)?.let { openSeeAll(row, index, genreSpec(row.spec, it)) }
            // A service's card opens its page; a Disney+ brand's, its collection (See all's grid).
            row.style == RowStyle.LOGO -> row.cards.getOrNull(index)?.title?.id?.let { id ->
                Services.serviceOf(id)?.let { host.openService(it) }
                Services.brandOf(id)?.let { openSeeAll(row, index, Services.collection(it)) }
            }
            row.isSeeAll(index) -> openSeeAll(row, index, row.spec)
            row.spec.continueRow -> row.cards.getOrNull(index)?.let { playContinue(it, Origin.InRow(row, index, it.title.id)) }
            else -> row.cards.getOrNull(index)?.let { openTitle(it, Origin.InRow(row, index, it.title.id), soon = row.spec.source is Source.NextEpisodes) }
        }
    }

    private fun onHold(row: TitleRow, index: Int) {
        if (row.style == RowStyle.GENRES || row.style == RowStyle.LOGO || row.isSeeAll(index)) return
        val card = row.cards.getOrNull(index)?.takeIf { it.title.hasImdbId } ?: return
        val anchor = row.cardViewAt(index) ?: return
        // A Continue card's window also opens its title page (its sources looked up after 0.5 s).
        val page: (() -> Unit)? = if (row.spec.continueRow) {
            { openTitle(card, Origin.InRow(row, index, card.title.id), soon = true) }
        } else null
        hold.openPoster(card, row.spec.continueRow, anchor, row.items[index], hold.area(VodSizes.VIEWPORT_TOP), row, this, onTitlePage = page)
    }

    private fun genreSpec(of: RowSpec, genre: String): RowSpec {
        val kind = (of.source as? Source.Genres)?.kind ?: if (page == Page.SHOWS) Kind.SERIES else Kind.MOVIE
        return RowSpec(key = "${of.key}:$genre", label = genre, style = RowStyle.POSTER, source = Source.Catalog(kind, "top", genre = genre))
    }

    // ---- The small window (HoldWindow.Host) --------------------------------------------------------------------------

    /** + Add to list / − Remove from list: the My List rows (and its See all) change at once. */
    override fun onListChanged(title: Title, inList: Boolean) {
        for (row in rows) {
            val source = row.spec.source
            if (!RowRules.isMyList(source)) continue
            // My List's films or series alone: only a title of its kind comes in.
            if (inList && !RowRules.myListTakes(source, title)) continue
            if (!inList && row.cards.none { it.title.id == title.id }) continue
            val cards = RowRules.myListAfter(row.cards, title, inList)
            showCards(row, cards.take(ROW_CARDS), row.hasSeeAll || cards.size > ROW_CARDS, mine = true)
        }
        val sa = seeAll
        if (!inList && sa != null && sa.isOpen && sa.spec?.source?.let { RowRules.isMyList(it) } == true) sa.remove(title.id)
    }

    /** [id] has left Continue Watching: its cards go from the continue rows (and their See all) now. */
    override fun onLeftContinue(id: String) {
        for (row in rows) {
            if (!row.spec.continueRow && row.spec.source !is Source.ContinueWatching) continue
            if (row.cards.none { it.title.id == id }) continue
            showCards(row, row.cards.filter { it.title.id != id }, row.hasSeeAll, mine = true)
        }
        val sa = seeAll
        if (sa != null && sa.isOpen && sa.spec?.continueRow == true) sa.remove(id)
    }

    override fun onHoldChanged() = updateHeaderPause()

    // ---- Title pages (TitleStack.Host) -------------------------------------------------------------------------------

    private fun originRow(): TitleRow? = (origin as? Origin.InRow)?.row

    /**
     * Opens [card]'s title page over the page. Focus goes into it first; only then is the page's own content hidden
     * (a hidden view holding focus would hand it to the rail's first tab). The row it came from keeps its card grown.
     */
    /**
     * OK on a Continue card (§6.4): straight to playback, on the same file (its add-on asked first), from where it
     * stopped; no title page, no question. A series card that names no episode opens its title page instead.
     */
    private fun playContinue(card: Card, from: Origin) {
        val r = repo ?: return
        val library = try {
            Sources.get(ctx).library
        } catch (e: RuntimeException) {
            null
        }
        val request = VodLaunch.fromContinueCard(card, r, library)
        if (request == null) openTitle(card, from, soon = true) else host.play(request)
    }

    private fun openTitle(card: Card, from: Origin, soon: Boolean = false) {
        if (repo == null) return
        if (!card.title.hasImdbId) return resolveThen(card) { openTitle(it, from, soon) }
        // Search: a result opened keeps its search in the history.
        if (search) rememberSearch()
        press.cancel()
        if (hold.isOpen) hold.close(fade = false, focusBack = false)
        if (pageSpring.moving) pageSpring.snap(pageSpring.target)
        for (row in rows) row.motion.settle()
        seeAll?.settle()
        origin = from
        titles.open(card.title, soon)
        cover(true)
        updateHeaderPause()
        // Only the title page's pictures, and the poster it came from (for coming back): the rows' and See all's go,
        // their queued downloads too, and are bound again when the last title page closes (onStackClosed).
        val keep = originRow()
        for (row in rows) if (row === keep) row.bindImages(row.chosen..row.chosen) else row.releaseImages(cancel = true)
        seeAll?.releaseImages(cancel = true)
    }

    /** Hides the page's own content under a title page, or shows it again. */
    /**
     * A collection's card whose IMDb id isn't known yet (a stand-in): [then] gets the card with its title found, while
     * focus is still where it was; "Couldn't load this title" when TMDB has no IMDb id for it.
     */
    private fun resolveThen(card: Card, then: (Card) -> Unit) {
        val r = repo ?: return
        val at = root.findFocus()
        resolveCancel?.cancel()
        resolveCancel = r.resolve(card.title) { found ->
            resolveCancel = null
            if (released || !resumed || titles.isOpen || hold.isOpen || root.findFocus() !== at) return@resolve
            if (found == null || !found.hasImdbId) showNotice(VodSizes.COULDNT_OPEN) else then(card.copy(title = found))
        }
    }

    private fun cover(on: Boolean) {
        covered = on
        val v = if (on) View.INVISIBLE else View.VISIBLE
        nameLine.visibility = v
        viewport.visibility = if (on || seeAll?.isOpen == true) View.INVISIBLE else View.VISIBLE
        seeAll?.cover(on)
        if (empty.visibility != View.GONE) empty.visibility = v
        updateListHeader()
        updateSearchView()
        // A title page covers the live picture's place: it plays on unseen until the page is back.
        syncPip()
    }

    override fun restoreOrigin(focusBack: Boolean) {
        val from = origin
        cover(false)
        if (!focusBack) return
        when (from) {
            is Origin.InGrid -> seeAll?.takeIf { it.isOpen }?.let {
                it.takeFocus()
                return
            }
            is Origin.InHeader -> if (headerShows() && header.focusButton(from.button)) return
            is Origin.InRow -> {
                val row = from.row
                if (row.visibility == View.VISIBLE && row.items.isNotEmpty()) {
                    // By its title (a My List change made on the page may have moved it), else where it was.
                    val i = row.cards.indexOfFirst { it.title.id == from.id }.takeIf { it >= 0 } ?: from.index
                    row.chosen = i.coerceIn(0, row.items.size - 1)
                    if (row.focusChosen()) return
                }
            }
            null -> Unit
        }
        // Gone: the row chosen last, else the first, else the message or the rail.
        val target = lastRow?.takeIf { it.visibility == View.VISIBLE && it.items.isNotEmpty() } ?: visibleRows().firstOrNull()
        if (target == null || !target.focusChosen()) focusFallback()
    }

    /**
     * No row to go to: Try again when the page shows it (My List's empty line has none), Search's bar, else the rail.
     * Never left on a view that's closing or hiding (it would hand focus to the rail's first tab).
     */
    private fun focusFallback() {
        if (empty.visibility == View.VISIBLE && emptyButton.visibility == View.VISIBLE && emptyButton.requestFocus()) return
        if (searchBar?.focusBar() == true) return
        host.focusRail()
    }

    override fun play(request: PlayRequest) = host.play(request)

    override fun onStackClosed() {
        val row = originRow()
        origin = null
        // Focus didn't come back to the row (a tab switch): it's left as if focus had gone, its card no longer grown.
        if (row != null && row.focusedIndex() < 0 && onRowLeft(row)) row.motion.snap(row.chosen, isGrown = false)
        updateHeaderPause()
        if (seeAll?.isOpen == true) seeAll?.bindImages() else updateImages()
    }

    override val sidePanel: SidePanel get() = host.sidePanel

    // ---- See all ----------------------------------------------------------------------------------------------------

    private fun openSeeAll(row: TitleRow, index: Int, spec: RowSpec) {
        val r = repo ?: return
        val sa = seeAll ?: SeeAllPage(ctx, t, r, this).also {
            seeAll = it
            root.addView(it.root, root.indexOfChild(viewport) + 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                topMargin = t.dp(VodSizes.VIEWPORT_TOP)
            })
        }
        seeAllFrom = row to index
        sa.open(spec, if (spec === row.spec) row.cards else emptyList())
        // Focus into the grid before the rows hide (a hidden row holding focus would hand it anywhere).
        sa.takeFocus()
        if (pageSpring.moving) pageSpring.snap(pageSpring.target)
        viewport.visibility = View.INVISIBLE
        // "Popular · Movies"; My List's rows name it already ("My List · Movies").
        nameText.text = if (spec.label.startsWith(pageName)) spec.label else "${spec.label} · $pageName"
        updateName()
        updateListHeader()
        updateHeaderPause()
        // Only the grid's pictures (and the row it came from, for coming back).
        for (other in rows) if (other !== row) other.releaseImages()
        // The grid covers the live picture's place: it plays on unseen until the grid closes.
        syncPip()
    }

    private fun closeSeeAll(focusBack: Boolean) {
        val sa = seeAll ?: return
        if (!sa.isOpen) return
        val from = seeAllFrom
        seeAllFrom = null
        viewport.visibility = if (covered) View.INVISIBLE else View.VISIBLE
        if (focusBack) {
            // Back on the card it was opened from, else the row chosen last, else the first: while the grid still
            // holds focus (closed first, it would hand focus to the rail's first tab).
            val origin = from?.first?.takeIf { it.visibility == View.VISIBLE && it.items.isNotEmpty() }
            if (origin != null && from != null) origin.chosen = from.second.coerceIn(0, origin.items.size - 1)
            val target = origin
                ?: lastRow?.takeIf { it.visibility == View.VISIBLE && it.items.isNotEmpty() }
                ?: visibleRows().firstOrNull()
            if (target == null || !target.focusChosen()) focusFallback()
        }
        sa.close()
        nameText.text = pageName
        updateName()
        updateListHeader()
        updateHeaderPause()
        updateImages()
        syncPip()
    }

    override fun focusRail() = host.focusRail()

    override fun onGridPick(card: Card, spec: RowSpec) {
        if (spec.continueRow) playContinue(card, Origin.InGrid(card.title.id))
        else openTitle(card, Origin.InGrid(card.title.id), soon = spec.source is Source.NextEpisodes)
    }

    override fun onGridHold(card: Card, spec: RowSpec, view: CardView) {
        if (!card.title.hasImdbId) return resolveThen(card) { onGridHold(it, spec, view) }
        hold.openPoster(card, spec.continueRow, view, view, hold.area(VodSizes.VIEWPORT_TOP))
    }

    // ---- Loading ----------------------------------------------------------------------------------------------------

    private fun buildRows() {
        if (rowsBuilt) return
        val r = repo ?: repoOf().also { repoRef = it } ?: return
        rowsBuilt = true
        for (spec in rowSpecs(r)) {
            val row = TitleRow(ctx, t, spec, this, header = spec.style != RowStyle.LOGO, large = true)
            rows += row
            // Search's rows wait for words.
            if (search) {
                row.answered = true
                row.visibility = View.GONE
            }
            column.addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, row.bandHeight))
        }
        // Settings › Rows changes Home's, Movies' and TV Shows' rows (My List's and Search's are fixed).
        if (page != null && service == null) r.addRowsListener(rowsListener)
        if (resumed) r.addLibraryListener(libraryListener)
    }

    /**
     * Settings › Rows changed the page's rows: rows that stay keep their cards and place in the viewer's memory, new
     * ones are made and loaded, rows taken off go (focus in one moves to the row now in its place, else the rail).
     */
    private fun applyRows() {
        val p = page ?: return
        if (released || !rowsBuilt || service != null) return
        val r = repo ?: return
        val specs = r.rows(p)
        if (specs == rows.map { it.spec }) return
        val old = rows.associateBy { it.spec }
        val made = ArrayList<TitleRow>()
        val next = specs.map { spec ->
            old[spec] ?: TitleRow(ctx, t, spec, this, header = spec.style != RowStyle.LOGO, large = true).also { made += it }
        }
        val gone = rows.filter { row -> next.none { it === row } }
        // Focus off a row that goes, before it goes: to the next row shown that stays, else the one before, else the rail.
        var focused = rows.firstOrNull { it.hasFocus() }
        if (focused != null && gone.any { it === focused }) {
            val vis = visibleRows()
            val k = vis.indexOf(focused)
            val stays = { row: TitleRow -> next.any { it === row } && row.items.isNotEmpty() }
            val to = vis.drop(k + 1).firstOrNull(stays) ?: vis.take(k.coerceAtLeast(0)).lastOrNull(stays)
            if (to == null || !to.focusChosen()) host.focusRail()
            focused = to
        }
        for (row in gone) {
            row.cancel?.cancel()
            row.releaseImages(cancel = true)
            // Not VISIBLE: whatever remembers it (a title page or See all opened from it) finds it gone.
            row.visibility = View.GONE
            column.removeView(row)
            if (lastRow === row) lastRow = null
            if (focusedRow === row) focusedRow = null
            if (anchorRow === row) anchorRow = null
            if (seeAllFrom?.first === row) seeAllFrom = null
        }
        rows.clear()
        rows += next
        // In the new order, after the header band.
        next.forEachIndexed { i, row ->
            if (column.indexOfChild(row) == i + 1) return@forEachIndexed
            if (row.parent != null) column.removeView(row)
            column.addView(row, i + 1, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, row.bandHeight))
        }
        if (lastLoad != 0L) for (row in made) load(row)
        // Moved in the column (taken off and put back): focus back on its card.
        if (focused != null && !focused.hasFocus()) focused.focusChosen()
        checkEmpty()
        updateImages()
    }

    private fun retry() {
        buildRows()
        loadAll()
        checkEmpty()
    }

    private fun loadAll() {
        val r = repo
        if (r == null) {
            checkEmpty()
            return
        }
        lastLoad = SystemClock.elapsedRealtime()
        headerCancel?.cancel()
        // My List's header only follows the focused title; Search has none.
        // A service page's follows its own rows' titles ([updateServiceFeed]).
        headerCancel = page?.takeIf { service == null }?.let { p -> r.header(p) { feed -> if (!released) header.setFeed(feed.titles) } }
        // Search: every row waits for its new answer before any shows ([holdSearchAnswer]).
        heldAnswers.clear()
        searchOrderSettled = false
        main.removeCallbacks(showHeldAnswers)
        if (!search || query.isNotEmpty()) for (row in rows) row.answered = false
        for (row in rows) {
            if (search && query.isEmpty()) continue
            load(row)
        }
        checkEmpty()
    }

    /** My List, Continue Watching and Next Episodes again (the library changed, or the page came back). */
    private fun reloadLibrary() {
        for (row in rows) if (RowRules.isLibrary(row.spec.source)) load(row)
    }

    private fun onLibraryChanged() {
        if (released || !resumed) return
        reloadLibrary()
        titles.onLibraryChanged()
    }

    private fun load(row: TitleRow) {
        val r = repo ?: return
        row.cancel?.cancel()
        val gen = ++row.generation
        row.cancel = if (row.style == RowStyle.GENRES) {
            val kind = (row.spec.source as? Source.Genres)?.kind ?: if (page == Page.SHOWS) Kind.SERIES else Kind.MOVIE
            r.genres(kind) { names ->
                if (released || gen != row.generation) return@genres
                row.answered = true
                showGenres(row, names)
            }
        } else {
            // One more than shown: whether there are more than 15 (a library row's See all).
            r.load(row.spec, 0, ROW_CARDS + 1) { cards, fresh ->
                if (released || gen != row.generation) return@load
                onCards(row, cards, fresh)
            }
        }
    }

    private fun onCards(row: TitleRow, cards: List<Card>, fresh: Boolean) {
        // An empty cached answer: the fresh one follows (a library row's answer is always final).
        if (!RowRules.isFinal(row.spec.source, cards.isEmpty(), fresh)) return
        row.answered = true
        val shownCards = cards.take(ROW_CARDS)
        val seeAll = RowRules.seeAll(row.spec.source, cards.size)
        if (search) {
            holdSearchAnswer(row, shownCards, seeAll)
            return
        }
        applyAnswer(row, shownCards, seeAll)
        if (service != null) updateServiceFeed()
    }

    /**
     * A service page's banner goes through its rows' titles, films and series in turn, as Home's does through its
     * lists: once every row has answered, and only titles whose IMDb id is known (the banner's buttons need it).
     */
    private fun updateServiceFeed() {
        val lists = rows.filter { it.spec.source is Source.Discover }
        if (lists.any { !it.answered }) return
        val titles = lists.map { row -> row.cards.map { it.title }.filter { it.hasImdbId } }
        header.setFeed(TmdbTrending.interleave(titles.getOrNull(0).orEmpty(), titles.getOrNull(1).orEmpty()))
    }

    /** [cards] into [row], unless the viewer is in it and they would move what they're on: then when they leave. */
    private fun applyAnswer(row: TitleRow, shownCards: List<Card>, seeAll: Boolean) {
        // The viewer is in this row and the new cards would move what they're on: held until they leave it.
        if (!RowRules.keepsFocus(row.focusedIndex(), row.cards, row.hasSeeAll, shownCards, seeAll)) {
            row.pending = shownCards to seeAll
            return
        }
        showCards(row, shownCards, seeAll)
    }

    /**
     * Search: the Movies and TV Shows rows' answers are held until both have come; then the rows take their order for
     * these words ([orderSearchRows]) and both show together.
     */
    private fun holdSearchAnswer(row: TitleRow, cards: List<Card>, seeAll: Boolean) {
        // The order for these words is settled already (the other kind was too slow to wait for): shown as it comes.
        if (searchOrderSettled) {
            applyAnswer(row, cards, seeAll)
            return
        }
        heldAnswers[row] = cards to seeAll
        main.removeCallbacks(showHeldAnswers)
        if (rows.all { it.answered && heldAnswers.containsKey(it) }) showHeldAnswers.run()
        // One kind answered: the other gets a moment, no more (a slow or failing request must not hold these back).
        else main.postDelayed(showHeldAnswers, VodSizes.SEARCH_OTHER_KIND_WAIT_MS)
    }

    /** Search: the answers held so far take their order and show; a later one is then shown as it comes. */
    private val showHeldAnswers = Runnable {
        if (heldAnswers.isEmpty()) return@Runnable
        searchOrderSettled = true
        orderSearchRows()
        // In the rows' order now: the first shown takes focus asked for, as before.
        for (r in ArrayList(rows)) {
            val (c, s) = heldAnswers.remove(r) ?: continue
            applyAnswer(r, c, s)
        }
        heldAnswers.clear()
    }

    /**
     * Search: the row holding the best match for the words first ([SearchRules.showsFirst]: a show, TV Shows above
     * Movies; a film, Movies above). Decided once per answer, as both rows change together; never while the viewer
     * is in one of them (what they're on would jump).
     */
    private fun orderSearchRows() {
        if (rows.size != 2 || rows.any { it.hasFocus() }) return
        val showsRow = rows.firstOrNull { (it.spec.source as? Source.Search)?.kind == Kind.SERIES } ?: return
        val moviesRow = rows.firstOrNull { it !== showsRow } ?: return
        val names = { r: TitleRow -> heldAnswers[r]?.first.orEmpty().map { it.title.name } }
        val first = if (SearchRules.showsFirst(names(moviesRow), names(showsRow), query)) showsRow else moviesRow
        if (rows[0] === first) return
        rows.reverse()
        // Focus is on the bar: the page stays where it is (at the bar), not following a row it was in before.
        anchorRow = null
        anchorTop = -1
        // In the new order, after the bar (the column's first child).
        rows.forEachIndexed { i, r ->
            if (column.indexOfChild(r) == i + 1) return@forEachIndexed
            column.removeView(r)
            column.addView(r, i + 1, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, r.bandHeight))
        }
    }

    /**
     * Puts [cards] in [row] (in place), shows or hides the row, and keeps focus on something that stays. [mine]: the
     * viewer's own change (applied at once even in the row they're in).
     */
    private fun showCards(row: TitleRow, cards: List<Card>, seeAll: Boolean, mine: Boolean = false) {
        row.pending = null
        val same = !mine && row.visibility == View.VISIBLE && row.hasSeeAll == seeAll &&
            row.cards.size == cards.size && row.cards.indices.all { row.cards[it] == cards[it] }
        if (same) return
        if (cards.isEmpty()) {
            hideRow(row)
            checkEmpty()
            // A row above answering empty: focus asked for may go to one below it now.
            takePendingFocus()
            return
        }
        // Focus off items that go, before they go.
        val target = RowRules.focusAfter(row.focusedIndex(), row.cards.size, row.hasSeeAll, cards.size, seeAll)
        if (target >= 0) row.items.getOrNull(target)?.requestFocus()
        row.bind(cards, seeAll)
        if (row.visibility != View.VISIBLE) {
            row.chosen = 0
            row.motion.snap(0, isGrown = false)
            row.visibility = View.VISIBLE
        }
        // In the Continue row, a card taken away: the header follows the card now chosen.
        val at = row.focusedIndex()
        if (row.spec.continueRow && at >= 0) row.cards.getOrNull(at)?.takeIf { followsContinue(row) }?.let { header.follow(it) } ?: header.unfollow()
        if (myList && at >= 0) row.cards.getOrNull(at)?.let { followCard(it) }
        checkEmpty()
        updateImages()
        takePendingFocus()
    }

    private fun showGenres(row: TitleRow, names: List<String>) {
        if (names.isEmpty()) {
            hideRow(row)
            checkEmpty()
            takePendingFocus()
            return
        }
        if (row.genres == names && row.visibility == View.VISIBLE) return
        row.bindGenres(names)
        if (row.visibility != View.VISIBLE) {
            row.chosen = 0
            row.motion.snap(0, isGrown = false)
            row.visibility = View.VISIBLE
        }
        checkEmpty()
        takePendingFocus()
    }

    /** A row with nothing in it goes; focus in it moves to the next row shown, else the one before, else the rail. */
    private fun hideRow(row: TitleRow) {
        if (row.visibility != View.VISIBLE) return
        if (row.focusedIndex() >= 0) {
            val vis = visibleRows()
            val k = vis.indexOf(row)
            val to = vis.getOrNull(k + 1) ?: vis.getOrNull(k - 1)
            if (to == null || !to.focusChosen()) focusFallback()
        }
        if (lastRow === row) lastRow = null
        if (anchorRow === row) anchorRow = null
        row.releaseImages()
        row.visibility = View.GONE
        row.bind(emptyList(), seeAll = false)
    }

    /**
     * A row came or went: focus asked for meanwhile goes into the page's first row, once every row above it has
     * answered (not into whichever row answered first).
     */
    private fun takePendingFocus() {
        if (!shown || !resumed || titles.isOpen) return
        // Asked for while loading (and the viewer stayed on the rail's tab), or nothing has focus at all: never taken
        // from the rail otherwise.
        if (!stillPending() && root.rootView.findFocus() != null) return
        val row = firstReadyRow() ?: return
        if (row.focusChosen()) {
            focusPending = false
            pendingFrom = null
        }
    }

    private fun checkEmpty() {
        // Search never says "couldn't load": its history shows instead of results.
        if (search) {
            empty.visibility = View.GONE
            updateSearchView()
            return
        }
        // Focus in the page's own content (not in a title page over it).
        val inPage = viewport.hasFocus() || empty.hasFocus() || seeAll?.root?.hasFocus() == true
        // Genre chips alone are no page (offline, each would open an empty grid): they don't count, and hide with the
        // rest until the next load brings them back with the titles.
        // Nor do logo cards beside rows of titles that didn't come (each would open a page with nothing on it).
        val titleRows = rows.any { it.style != RowStyle.LOGO }
        val filler = { r: TitleRow -> r.style == RowStyle.GENRES || (r.style == RowStyle.LOGO && titleRows) }
        val nothing = repo == null || (rows.all { it.answered } && rows.none { it.visibility == View.VISIBLE && !filler(it) })
        if (nothing) for (r in rows) if (filler(r)) hideRow(r)
        // Every row switched off in Settings › Rows: say so (there's nothing to try again).
        if (!myList) {
            val none = rows.isEmpty()
            emptyText.text = if (none) NO_ROWS else LOAD_FAILED
            emptyButton.visibility = if (none) View.GONE else View.VISIBLE
        }
        val was = empty.visibility != View.GONE
        if (!nothing && was && empty.hasFocus()) {
            // Try again (or a reload): focus moves before the message hides (a hidden view holding focus would hand it
            // to the rail's first tab). With no row in yet, it waits on this page's tab: the first row takes it, or Try
            // again if nothing comes.
            val r = firstReadyRow()
            if (r == null || !r.focusChosen()) {
                host.focusRail()
                waitForRows()
            }
        }
        // Under a title page the message waits, unseen, until the page shows again.
        empty.visibility = if (!nothing) View.GONE else if (covered) View.INVISIBLE else View.VISIBLE
        if (nothing && !was && !covered && (stillPending() || inPage)) {
            focusPending = false
            pendingFrom = null
            emptyButton.requestFocus()
        }
        updateListHeader()
    }

    // ---- Layout changes, header and pictures ---------------------------------------------------------------------

    /** Rows appeared, went or changed height: the chosen row stays where it is on screen, then glides to its place. */
    private fun onRowsMoved() {
        val row = anchorRow?.takeIf { it.visibility == View.VISIBLE }
        if (row == null) {
            if (visibleRows().isNotEmpty() && focusedRow == null) pageSpring.snap(0f)
            updateHeaderPause()
            updateImages()
            return
        }
        val top = column.topOf(row)
        if (top < 0) return
        val shift = if (anchorTop >= 0) top - anchorTop else 0
        anchorTop = top
        if (shift != 0) pageSpring.snap(pageSpring.value + shift)
        pageSpring.glideTo(targetFor(row))
        updateHeaderPause()
        updateImages()
    }

    /**
     * Whether the banner shows the title of the focused card in [row] ([HeaderFollow]): a Continue Watching row's, and on
     * Home only while Settings › Home Header lets it (read each time, so a change applies at once).
     */
    private fun followsContinue(row: TitleRow): Boolean =
        HeaderFollow.follows(row.spec.continueRow, page == Page.HOME, page != Page.HOME || prefs.homeHeaderMatchesContinue)

    /** The header rotates only while it shows and nothing is open over the page. */
    private fun updateHeaderPause() {
        val shows = headerShows()
        val active = resumed && !released && shows && !hold.isOpen && seeAll?.isOpen != true && !titles.isOpen
        if (active) header.resume() else header.pause()
    }

    /**
     * Pictures for the rows that show (where the page is heading) and the one above the chosen row, for the cards
     * near each row's chosen one; rows further away let theirs go. The next few posters are fetched ahead to disk.
     * Under a title page nothing changes (the row it came from keeps its pictures).
     */
    private fun updateImages() {
        if (!resumed || titles.isOpen) return
        val vis = visibleRows()
        if (vis.isEmpty()) return
        val sa = seeAll
        if (sa != null && sa.isOpen) return
        val focus = focusedRow ?: lastRow
        val k = vis.indexOf(focus).coerceAtLeast(0)
        val offset = pageSpring.target
        val tops = FloatArray(vis.size) { column.topOf(vis[it]).toFloat() }
        val heights = FloatArray(vis.size) { vis[it].bandHeight.toFloat() }
        val viewH = if (myList) VodSizes.SCREEN_H - VodSizes.fixedRowsTop() else VodSizes.VIEWPORT_H
        val bound = RowMath.boundRows(k, offset, t.dpf(viewH), tops, heights)
        vis.forEachIndexed { j, row ->
            when {
                // Not laid out yet: bound once the column's layout has placed it.
                tops[j] < 0f -> Unit
                j in bound -> {
                    val range = RowMath.boundCards(row.chosen, row.items.size, after = VodSizes.boundAfter(row.style, row.large))
                    if (range != row.boundRange) row.bindImages(range)
                }
                abs(j - k) > 2 -> if (row.boundRange != IntRange.EMPTY) row.releaseImages()
            }
        }
        // Ahead, to disk only: the two cards after the chosen row's window and the first of the row after next.
        vis.getOrNull(k)?.let { row ->
            val end = RowMath.boundCards(row.chosen, row.items.size, after = VodSizes.boundAfter(row.style, row.large)).last
            for (i in end + 1..end + 2) row.cards.getOrNull(i)?.let { ImageLoader.prefetch(pictureOf(row, it)) }
        }
        vis.getOrNull(k + 2)?.let { row -> row.cards.take(8).forEach { ImageLoader.prefetch(pictureOf(row, it)) } }
    }

    private fun pictureOf(row: TitleRow, card: Card): String? =
        if (row.style == RowStyle.WIDE) wideArt(card) else card.title.poster

    // ---- Clock and notices -------------------------------------------------------------------------------------------

    private val clockTick = Runnable { tickClock() }

    private fun tickClock() {
        val now = System.currentTimeMillis()
        val date = DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd"), now)
        clockText.text = "$date · ${DateFormat.getTimeFormat(ctx).format(Date(now))}"
        main.removeCallbacks(clockTick)
        // On the next minute.
        main.postDelayed(clockTick, 60_000L - now % 60_000L + 50L)
    }

    /** The page's one notice ("Playback comes next"), over everything on it, title pages included. */
    override fun showNotice(text: String) {
        notice.text = text
        notice.visibility = View.VISIBLE
        main.removeCallbacks(hideNotice)
        main.postDelayed(hideNotice, VodSizes.NOTICE_MS)
    }

    private val hideNotice = Runnable { notice.visibility = View.GONE }

    // ---- My List's header ---------------------------------------------------------------------------------------

    /** My List: the header shows [card]'s title (the words at once, the picture half a second later). */
    private fun followCard(card: Card) {
        if (myList && headerId == card.title.id) return
        headerId = card.title.id
        header.follow(card)
    }

    /**
     * My List: the header shows the focused title, else the chosen one of the row chosen last (or of the first row);
     * with nothing in the list (or See all or a title page over the page) it's hidden.
     */
    private fun updateListHeader() {
        if (!myList) return
        val row = (focusedRow ?: lastRow)?.takeIf { it.visibility == View.VISIBLE && it.cards.isNotEmpty() }
            ?: visibleRows().firstOrNull { it.cards.isNotEmpty() }
        val card = row?.let { it.cards.getOrNull(it.chosen.coerceIn(0, it.cards.size - 1)) }
        if (card == null) {
            headerId = null
            header.unfollow()
        } else if (focusedRow == null) {
            // Focus in a row: the header is on its card already (onItemFocused).
            followCard(card)
        }
        headerBand.visibility = if (card == null || covered || seeAll?.isOpen == true) View.INVISIBLE else View.VISIBLE
        updateHeaderPause()
    }

    // ---- Search ---------------------------------------------------------------------------------------------------

    private fun rowSpecs(r: VodRepository): List<RowSpec> = when {
        service != null -> Services.rows(service)
        else -> tabRowSpecs(r)
    }

    private fun tabRowSpecs(r: VodRepository): List<RowSpec> = when (tab) {
        VodTab.MY_LIST -> TabRows.myList()
        VodTab.SEARCH -> TabRows.search("")
        else -> r.rows(page ?: Page.HOME)
    }

    /** Search: results show, or words are in the bar (Back clears them). */
    private fun showsSearch(): Boolean =
        searchBar?.text?.isNotBlank() == true || if (kind == SearchKind.LIVE) {
            liveResults?.let { it.query.isNotEmpty() || it.hasResults() } == true
        } else {
            query.isNotEmpty() || visibleRows().isNotEmpty()
        }

    /** Search: [words] searched in the kind chosen. */
    private fun searchKind(words: String) {
        if (kind == SearchKind.LIVE) liveView().search(SearchHistoryRules.clean(words)) else search(words)
        // At once, before any answer: the history gives way to "Searching...", so a search never looks stuck.
        updateSearchView()
    }

    /** Search > Live TV's results, made the first time they're needed (above the history, under the page's windows). */
    private fun liveView(): LiveSearchView = liveResults ?: LiveSearchView(ctx, t, t.dp(VodSizes.PAGE_X)).also { v ->
        liveResults = v
        val at = historyList?.let { root.indexOfChild(it) + 1 } ?: root.childCount
        root.addView(v, at, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = t.dp(VodSizes.searchResultsTop(pipOn))
        })
        v.onUp = { searchBar?.focusBar() }
        v.onLeft = { host.focusRail() }
        v.onPlay = { key ->
            rememberSearch()
            host.openLiveResult(key, null)
        }
        v.onGuide = { key, at ->
            rememberSearch()
            host.openLiveResult(key, at)
        }
        v.onHold = { hit, anchor -> openChannelHold(hit, anchor) }
        v.onAnswered = { updateSearchView() }
    }

    /**
     * Search: the other kind chosen on its pill. This kind's words are kept (and come back when it's chosen again), its
     * results go; the bar takes the other kind's hint, last words and results, and the history shows that kind's.
     */
    private fun switchKind(to: SearchKind) {
        val bar = searchBar ?: return
        if (to == kind) return
        lastWords.keep(kind, bar.text)
        bar.cancel()
        if (kind == SearchKind.LIVE) liveResults?.clear() else search("")
        kind = to
        if (to == SearchKind.LIVE) liveView().warm()
        pills?.show(to)
        bar.setKind(to)
        val words = lastWords.of(to)
        bar.setText(words)
        historyList?.show(history?.entries.orEmpty())
        noticeFor = null
        if (words.isNotBlank()) searchKind(words)
        updateSearchView()
    }

    /** Search: opened in [to] (a settings window's Search bubble: Live TV search from Live TV), focus on the bar. */
    fun openSearch(to: SearchKind) {
        if (!search) return
        switchKind(to)
        searchBar?.focusBar()
    }

    /** Search > Live TV: OK held on a channel: its full name, the guide's star and Assign favorites. */
    private fun openChannelHold(hit: LiveSearch.ChannelHit, anchor: View) {
        val db = LiveDb.get(ctx)
        val star = HoldPopup.Button(
            if (hit.favorite) "Remove from Favorites" else "Add to Favorites",
            icon = if (hit.favorite) R.drawable.ic_star_filled else R.drawable.ic_star,
            selected = hit.favorite,
        ) {
            val fav = !hit.favorite
            hit.favorite = fav
            val now = System.currentTimeMillis()
            LiveDb.writes.execute { db.setFavorite(hit.key, fav, now) }
            hold.close()
            showNotice(if (fav) "Added to Favorites" else "Removed from Favorites")
        }
        val assign = HoldPopup.Button("Assign favorites") {
            hold.close(fade = false)
            host.openAssignFavorites(hit.key.playlistId, hit.groupId, hit.key)
        }
        hold.open(hit.name, listOf(star, assign), anchor, anchor, hold.area(VodSizes.VIEWPORT_TOP), otherwise = { searchBar?.focusBar() })
    }

    /**
     * Search: the result rows for [words], asked now (blank: none, the history shows). The rows' older answers are
     * cancelled; each row shows only when it has titles.
     */
    private fun search(words: String) {
        val q = SearchHistoryRules.clean(words)
        if (q == query) return
        query = q
        noticeFor = null
        heldAnswers.clear()
        searchOrderSettled = false
        main.removeCallbacks(showHeldAnswers)
        val specs = TabRows.search(q)
        rows.forEachIndexed { i, row ->
            row.cancel?.cancel()
            row.cancel = null
            row.generation++
            row.pending = null
            // By its key: the two rows may have changed places for the last words ([orderSearchRows]).
            (specs.firstOrNull { it.key == row.spec.key } ?: specs.getOrNull(i))?.let { row.spec = it }
            // A new search starts at its first card.
            row.chosen = 0
            if (row.items.isNotEmpty()) row.motion.snap(0, isGrown = false)
            row.answered = q.isEmpty()
        }
        // Every row waits for its new answer before any answers (else "nothing found" could come too soon).
        for (row in rows) if (q.isEmpty()) hideRow(row) else load(row)
        checkEmpty()
    }

    /** Search: the words searched now go first in the history (submitted, or a result opened). */
    private fun rememberSearch() {
        val h = history ?: return
        val q = if (kind == SearchKind.LIVE) liveResults?.query.orEmpty() else query
        if (q.isEmpty()) return
        h.add(q)
        historyList?.show(h.entries)
    }

    /**
     * Search: back to the history view: an empty bar, no results, the history; focus on the bar ([focus]; before the
     * rows go, so it never falls to the rail).
     */
    private fun resetSearch(focus: Boolean) {
        val bar = searchBar ?: return
        if (focus) bar.focusBar()
        bar.cancel()
        bar.setText("")
        search("")
        liveResults?.clear()
        if (focus) pageSpring.glideTo(0f) else pageSpring.snap(0f)
        updateSearchView()
    }

    /** Search: the history shows while no results do (and nothing covers the page); "nothing found" once per search. */
    private fun updateSearchView() {
        val list = historyList ?: return
        val live = liveResults?.takeIf { kind == SearchKind.LIVE }
        val results = visibleRows().isNotEmpty() || live?.hasResults() == true
        val q = live?.query ?: query
        val answered = live?.answered ?: rows.all { it.answered }
        // Asked for and not answered yet, with nothing to show meanwhile.
        val searching = q.isNotEmpty() && !results && !answered
        // Focus off the list before it hides (a hidden view holding focus would hand it to the rail's first tab).
        if ((results || searching) && list.hasFocus()) searchBar?.focusBar()
        list.visibility = when {
            results -> View.GONE
            covered || searching -> View.INVISIBLE
            else -> View.VISIBLE
        }
        searchingNote?.visibility = if (searching && !covered) View.VISIBLE else View.GONE
        if (q.isNotEmpty() && !results && answered && noticeFor != q && shown) {
            noticeFor = q
            showNotice("Nothing found for \u201C$q\u201D")
        }
    }

    private fun onBarFocused() {
        focusPending = false
        pendingFrom = null
        press.cancel()
        // Left to the rail from here and Right again comes back here, not to the results row chosen before.
        lastRow = null
        pageSpring.glideTo(0f)
        updateImages()
    }

    /** Search: an arrow on the bar or the trash button. Down goes into the results, else the history. */
    private fun onBarArrow(keyCode: Int, fromTrash: Boolean) {
        val bar = searchBar ?: return
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (fromTrash) bar.focusBar() else host.focusRail()
            // Right from the bin: the live picture when it plays here (its strip, on Volume).
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (!fromTrash) bar.focusTrash() else focusPip()
            KeyEvent.KEYCODE_DPAD_UP -> pills?.focus(kind)
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (kind == SearchKind.LIVE) {
                    if (liveResults?.focusFirst() != true) historyList?.focusRow(0)
                    return
                }
                val row = visibleRows().firstOrNull { it.items.isNotEmpty() }
                if (row == null || !row.focusChosen()) historyList?.focusRow(0)
            }
        }
    }

    /** Search: a history row picked: searched at once, the words in the bar and focus there (to change them). */
    private fun onHistoryPick(words: String) {
        val bar = searchBar ?: return
        bar.setText(words)
        bar.focusBar()
        searchKind(words)
        rememberSearch()
    }

    /** Search: OK held on a history row: its small window, with Remove. */
    private fun onHistoryHold(words: String, row: View) {
        val list = historyList ?: return
        val at = list.indexOf(row)
        val remove = HoldPopup.Button("Remove") {
            history?.let {
                it.remove(words)
                list.show(it.entries)
            }
            // Focus back on the row held (now the next search), else the last one left, else the bar.
            hold.close()
        }
        hold.open(words, listOf(remove), row, row, hold.area(VodSizes.VIEWPORT_TOP), otherwise = {
            if (!list.focusRow(at)) searchBar?.focusBar()
        })
    }

    /** Search: the trash button asks first (the app's question window), then the history goes. */
    private fun confirmClearHistory() {
        val activity = ctx as? Activity ?: return
        if (history?.entries.isNullOrEmpty()) {
            showNotice(NO_HISTORY)
            return
        }
        FireDialog.ask(
            activity,
            "Clear search history?",
            listOf<Pair<String, () -> Unit>>(
                "Clear" to {
                    history?.clear()
                    historyList?.show(emptyList())
                },
                "Cancel" to {},
            ),
            focus = 1,
        )
    }

    // ---- The live picture (automatic picture-in-picture, PipRules) -------------------------------------------------

    /**
     * The live picture comes to this page ([on]: a channel plays on in it) or goes; [muted]: its sound is off. On Home it
     * takes Recent channels' place; on Search the bar and the history shorten to clear it. Going, focus on its strip
     * goes back to where it came from first ([pipOff]).
     */
    fun setPip(on: Boolean, muted: Boolean) {
        val box = pipBox ?: return
        box.setMuted(muted)
        if (!on) return pipOff(closed = false)
        if (pipOn) return syncPip()
        pipOn = true
        // Recent channels makes way (focus in it first goes to the banner's Details: a hidden row can't keep it).
        window?.let { w ->
            if (w.hasFocus() && !header.focusButton(VodHeader.Button.DETAILS)) firstRowWithItems()?.focusChosen()
            w.visibility = View.INVISIBLE
        }
        narrowSearch(true)
        box.translationY = -pageSpring.value
        // Home: the picture's window widens from Recent channels' as the banner narrows (drawn from there first, so
        // the picture is put there below).
        shapeTopRight(pip = true)
        syncPip()
    }

    /**
     * The live picture leaves this page: Recent channels (Home) or the full bar (Search) come back. Focus on its strip
     * moves first, never left to fall anywhere: [closed] (its Close) as [PipRules.focusAfterClose] says (Home: the first
     * recent channel), otherwise back to where it came from.
     */
    fun pipOff(closed: Boolean) {
        val box = pipBox ?: return
        if (!pipOn) return
        pipOn = false
        pipBackDown = false
        val inStrip = box.hasFocus()
        val w = window
        w?.visibility = View.VISIBLE
        narrowSearch(false)
        val page = if (search) PipRules.Page.SEARCH else PipRules.Page.HOME
        if (closed && w != null && PipRules.focusAfterClose(page) == PipRules.AfterClose.FIRST_RECENT) {
            // With no channel listed yet, focus waits on the banner's Details and comes to the first once read.
            w.focusFirst { focusBackFromPip() }
        } else if (inStrip) {
            focusBackFromPip()
        }
        pipFrom = null
        box.hideStrip()
        box.visibility = View.INVISIBLE
        box.translationY = 0f
        // Home: Recent channels narrows back from the picture's window as the banner widens, then its channels show.
        shapeTopRight(pip = false)
    }

    /** The strip's Volume shows the picture's sound off ([muted]) or on. */
    fun pipMuted(muted: Boolean) {
        pipBox?.setMuted(muted)
    }

    /** The channel's [name] and what's on it now on the picture's strip. */
    fun pipInfo(name: String, programme: String?) {
        pipBox?.setInfo(name, programme)
    }

    /**
     * Where the live picture goes: its box with the page at rest (the shell moves it with the page itself, [pipShift]),
     * in this page's coordinates, from the box's own layout (known before any layout pass); null while it isn't here.
     */
    fun pipSpot(): RectF? = RectF().takeIf { pipSpotNow(it) }

    /**
     * [pipSpot] into [out] (nothing made: it's asked each frame while Home's window changes width, where it's drawn
     * then); false while the picture isn't here.
     */
    fun pipSpotNow(out: RectF): Boolean {
        val box = pipBox ?: return false
        if (!pipOn) return false
        val lp = box.layoutParams as FrameLayout.LayoutParams
        val left = if (window != null) windowLeftPx(topRight.value) else lp.leftMargin.toFloat()
        out.set(left, lp.topMargin.toFloat(), (lp.leftMargin + lp.width).toFloat(), (lp.topMargin + lp.height).toFloat())
        return true
    }

    /** The picture's corners: the box's (Recent channels' window shape). */
    fun pipRadius(): Float = pipBox?.let {
        val lp = it.layoutParams
        Glass.cornerRadius(ctx, Glass.Kind.PANEL, lp.width, lp.height)
    } ?: 0f

    /** How far the page has moved the picture's box from its place now (px, 0 at rest, less while the rows are up). */
    fun pipShift(): Float = if (pipOn) -pageSpring.value else 0f

    /** Something covers the picture's place (a title page, See all), or the page is hidden: it plays on unseen. */
    fun pipCovered(): Boolean = !shown || covered || seeAll?.isOpen == true

    /**
     * Whether a held Back here is the picture's (full screen): the page itself is in front, with no small window, title
     * page or See all over it, and the press didn't just close one.
     */
    fun pipInFront(): Boolean = pipOn && shown && !covered && seeAll?.isOpen != true && !hold.isOpen && !backClosedPopup

    /** Focus to the picture's strip, on Volume (Right from the banner's Details, or from Search's bin). */
    private fun focusPip(): Boolean {
        val box = pipBox ?: return false
        // Its window at its full width first: the strip shows across it.
        if (pipOn) finishTopRight()
        if (!pipOn || box.visibility != View.VISIBLE) return false
        val from = root.findFocus()
        if (!box.takeFocus()) return false
        if (from != null && !box.owns(from)) pipFrom = from
        return true
    }

    /** An arrow out of the strip: Left back to where focus came from, Down into the page under the picture. */
    private fun pipOut(step: PipRules.Step) {
        when (step) {
            PipRules.Step.OutLeft -> focusBackFromPip()
            PipRules.Step.OutDown -> if (search) onBarArrow(KeyEvent.KEYCODE_DPAD_DOWN, fromTrash = true) else firstRowWithItems()?.focusChosen()
            else -> Unit
        }
    }

    /** Back to where focus was before the strip; else the banner's Details (Home) or the bin (Search). */
    private fun focusBackFromPip() {
        val from = pipFrom?.takeIf { it.isShown && it.isFocusable }
        if (from != null && from.requestFocus()) return
        when {
            searchBar != null -> if (!searchBar.focusTrash()) searchBar.focusBar()
            header.focusButton(VodHeader.Button.DETAILS) -> Unit
            else -> if (firstRowWithItems()?.focusChosen() != true) focusFallback()
        }
    }

    /** The box shows while the picture is on this page and nothing covers its place; the shell is told either way. */
    private fun syncPip() {
        val box = pipBox ?: return
        if (!pipOn) return
        val show = !pipCovered()
        if (!show && box.hasFocus()) focusBackFromPip()
        if (!show) box.hideStrip()
        box.visibility = if (show) View.VISIBLE else View.INVISIBLE
        host.pipMoved()
    }

    // ---- Home's top right: Recent channels, or the live picture's 16:9 window ([topRight]) ------------------------

    /**
     * Home's top right heads for the live picture's layout ([pip]) or Recent channels': in one motion while the page is
     * on screen, otherwise there at once. The banner's words fade out meanwhile; Recent channels' contents wait for
     * its own width.
     */
    private fun shapeTopRight(pip: Boolean) {
        val w = window ?: return
        val to = if (pip) 1f else 0f
        if (!canMoveTopRight()) {
            stopTopRightFrames()
            topRight.snap(to)
            settleTopRight(animate = false)
            return
        }
        topRight.aim(to)
        if (!topRight.running) {
            // Already there (or never left).
            stopTopRightFrames()
            settleTopRight(animate = true)
            return
        }
        header.veilWords(true, animate = true)
        if (!pip) w.showContents(false, animate = false)
        // Where everything stands now, this frame (the picture is put in its window by the caller).
        drawTopRight(follow = false)
        if (topRightTicking) return
        topRightTicking = true
        topRightFirst = true
        Choreographer.getInstance().postFrameCallback(topRightFrame)
    }

    /** Whether Home's top right can move now: the page on screen and laid out, the TV's animations on. */
    private fun canMoveTopRight(): Boolean =
        shown && started && !released && root.isShown && root.isLaidOut && root.windowVisibility == View.VISIBLE &&
            (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O || android.animation.ValueAnimator.areAnimatorsEnabled())

    private fun onTopRightFrame(nanos: Long) {
        if (!topRightTicking) return
        // A frame at a time, never more than two frames' worth at once (as the rail: after a slow frame it carries on).
        val dt = if (topRightFirst) app.fireplayer.shell.RailShape.FRAME_MS
        else app.fireplayer.shell.RailShape.stepMs((nanos - topRightLastNanos) / 1_000_000f)
        topRightFirst = false
        topRightLastNanos = nanos
        if (!canMoveTopRight()) return finishTopRight()
        topRight.step(dt)
        if (topRight.running) {
            drawTopRight(follow = true)
            Choreographer.getInstance().postFrameCallback(topRightFrame)
        } else {
            topRightTicking = false
            settleTopRight(animate = true)
        }
    }

    private fun stopTopRightFrames() {
        if (!topRightTicking) return
        topRightTicking = false
        Choreographer.getInstance().removeFrameCallback(topRightFrame)
    }

    /** A motion of Home's top right under way ends where it was heading, at once (focus is going there, or it's hidden). */
    private fun finishTopRight() {
        if (window == null || (!topRightTicking && !topRight.running)) return
        stopTopRightFrames()
        topRight.snap(topRight.target)
        settleTopRight(animate = false)
    }

    /** The window's left edge [p] of the way from Recent channels' (0) to the picture's (1), px in the page. */
    private fun windowLeftPx(p: Float): Float =
        VodSizes.mix(t.dp(VodSizes.windowX(pip = false)).toFloat(), t.dp(VodSizes.windowX(pip = true)).toFloat(), p)

    /**
     * Home's top right drawn where the motion has it now: the banner's right edge, and the window's left edge drawn on
     * Recent channels (reaching left of it) and on the picture's box (starting right of its left edge); with [follow],
     * the picture told to follow ([Host.pipResize]). Drawing only.
     */
    private fun drawTopRight(follow: Boolean) {
        val w = window ?: return
        val p = topRight.value
        header.drawAt(p)
        val left = windowLeftPx(p)
        w.drawFrom(left - t.dp(VodSizes.windowX(pip = false)))
        pipBox?.drawFrom(left - t.dp(VodSizes.windowX(pip = true)))
        if (follow && pipOn) host.pipResize()
    }

    /**
     * The motion's end (or there at once): the banner laid out for its new width, everything drawn as laid out, the
     * words back, Recent channels' contents in ([animate]: fading), and the picture put in its window for good.
     */
    private fun settleTopRight(animate: Boolean) {
        val w = window ?: return
        val pip = topRight.target == 1f
        header.setNarrow(pip)
        drawTopRight(follow = false)
        header.veilWords(false, animate)
        if (!pip) w.showContents(true, animate)
        // Only this page's picture, while it's the one on screen.
        if (pipOn && shown) host.pipMoved()
    }

    /**
     * Search: the bar and the history shortened to clear the picture in the top right ([pip]), and the results (the
     * rows, Live TV's) starting under it ([VodSizes.searchResultsTop]); or all as they were again.
     */
    private fun narrowSearch(pip: Boolean) {
        if (!search) return
        searchBar?.setNarrow(pip)
        historyList?.let { list ->
            val lp = list.layoutParams ?: return@let
            val w = t.dp(VodSizes.searchBarW(pip))
            if (lp.width != w) {
                lp.width = w
                list.layoutParams = lp
            }
        }
        column.headerExtra = t.dp(VodSizes.searchResultsTop(pip)) - t.dp(VodSizes.searchListTop())
        liveResults?.let { v ->
            val lp = v.layoutParams as? FrameLayout.LayoutParams ?: return@let
            val top = t.dp(VodSizes.searchResultsTop(pip))
            if (lp.topMargin != top) {
                lp.topMargin = top
                v.layoutParams = lp
            }
        }
    }

    companion object {
        /** Cards in a row (then See all). */
        const val ROW_CARDS = RowRules.ROW_CARDS
        /** My List with nothing in it. */
        const val MY_LIST_EMPTY = "Titles you add with + show up here"
        const val NO_HISTORY = "No searches to clear"
        const val LOAD_FAILED = "Couldn't load titles. Check the connection."
        /** Home, Movies or TV Shows with every row switched off. */
        const val NO_ROWS = "Every row on this page is off. Switch some on in Settings › Rows."
        /** Rows are asked again when the page comes back after this long (the repository answers from its cache). */
        private const val RELOAD_MS = 10L * 60 * 1000
    }
}
