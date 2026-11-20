package app.fireplayer.live.ui

import android.app.Activity
import android.content.Context
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.fireplayer.live.Channel
import app.fireplayer.live.ChannelGroup
import app.fireplayer.live.ChannelKey
import app.fireplayer.live.GuideScope
import app.fireplayer.live.LiveDb
import app.fireplayer.live.LiveSync
import app.fireplayer.live.Playlist
import app.fireplayer.live.Programme
import app.fireplayer.live.ScopeLists
import app.fireplayer.playback.LiveSession
import app.fireplayer.playback.PlayerHost
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.FireDialog
import app.fireplayer.ui.Glass
import app.fireplayer.ui.GlassStyle
import app.fireplayer.ui.SidePanel
import java.util.Date
import java.util.concurrent.Executors

/**
 * The Live TV tab: one experience of guide and player (design-layout, "Live TV guide and playback").
 *
 * Guide: playlist/group column, the miniature top left with the selected programme's details beside it, and the
 * compact grid below. The column collapses while the grid has focus. First Center on another channel tunes it in the
 * miniature; Center on the playing channel opens full screen; holding Back opens full screen; so does a period
 * without key presses ([LivePrefs.fullScreenAfterSec]). The miniature and full screen are the same player and
 * surface, so going between them never retunes. Center held on a channel or on a group opens a small window
 * ([HoldPopup]): a channel's star and Assign favorites, a group's Move to top and Hide category.
 *
 * Full screen: with the controls hidden, Up/Down and Channel +/- change channel (or only Channel +/- when
 * "Channel buttons only" is set, and then Up/Down show the controls): Up and Channel + to the next channel down the
 * guide's list, Down and Channel - to the one before. Center shows the controls; Back returns to the guide with its
 * group, channel and time as they were; TV Guide (the button or the remote's Guide key) returns to it on the channel
 * playing, on what's on now ([focusPlaying]). With the controls shown, the D-pad moves round them and
 * Back hides them first; Down swaps the buttons for the extra row ([LiveMore]: audio, display size, dimmer, sleep
 * timer, settings) and Up brings them back, as in the VOD player. The extra row's Multiview button opens
 * [Multiview] with the channel playing as its first picture; Multiview hands the keys back when it closes, leaving
 * full screen on the channel chosen.
 */
class LivePage(private val activity: Activity, private val host: Host) {

    interface Host {
        val tokens: Tokens
        /** Live video at [rect] (root coordinates) with rounded corners, or the whole screen, or nowhere (null). */
        /**
         * Live video at [rect] (root coordinates) with rounded corners, or the whole screen ([full]), or nowhere (null).
         * With [animateMs] > 0 the picture moves and grows (or shrinks) there smoothly over that time (miniature ↔
         * full screen); otherwise it's placed at once.
         */
        fun placeVideo(rect: RectF?, radius: Float, full: Boolean, animateMs: Long = 0L)
        fun setChromeVisible(visible: Boolean)
        /** The tab rail beside the guide: shown (the groups column is open) or slid away (in the grid). */
        fun setRailShown(shown: Boolean, animateMs: Long = 0L)
        fun focusRail()
        fun railHasFocus(): Boolean
        /** The settings window or a dialog is open over the page: its timers wait. */
        fun overlayOpen(): Boolean
        /** What owns the live picture on this screen (its surface): a stale screen's stop is ignored. */
        val videoOwner: Any
        /**
         * Glass shows the blurred live picture (true: full-screen controls are up) or the wallpaper (false). The film
         * is only copied while glass is on screen, so with the controls hidden nothing is captured.
         */
        fun setFilmGlass(on: Boolean)
        fun openPlaylists()
        /**
         * The Live FirePlayer Settings window over this page (Menu, or the Settings button in full screen); [onClosed]
         * runs after it closes (the Settings button: focus back to it, the controls' timer again).
         */
        fun openPlayerSettings(onClosed: (() -> Unit)? = null)
        /**
         * Assign favorites over this page (from the guide): [groupId]'s channels, with [channel] selected, and Back goes
         * to [playlistId]'s groups; with no [groupId], the groups. Focus comes back where it was when it closes.
         */
        fun openAssignFavorites(playlistId: String, groupId: String?, channel: ChannelKey?)
        /** Manage groups over this page (a group's held window): [playlistId]'s groups, [groupId]'s row selected. */
        fun openManageGroups(playlistId: String, groupId: String?)
        /** The settings window on the right, where the full-screen extra row's windows open. */
        val sidePanel: SidePanel
        /** Display size or the screen dimmer changed: the full-screen picture is laid out and dimmed again. */
        fun pictureChanged()
        /** Multiview's board, once: under the pages (over the wallpaper, under the controls, windows and notices). */
        fun addMultiview(board: View)
        /** Multiview shows ([on]): the one live picture and the wallpaper step aside for its pictures; or it closed. */
        fun setMultiview(on: Boolean)
        /**
         * The channel kept playing on Home or Search ([leave] with keepPlaying) changed (the channel buttons): the
         * picture's strip says so.
         */
        fun keptChanged() {}
        /** A line for the page the kept channel plays on (the playlist's connection limit, say). */
        fun keptNotice(text: String) {}
        /** The sleep timer ran out while the channel played on Home or Search: the picture closes. */
        fun keptSleep() {}
    }

    private val t = host.tokens
    private val ctx: Context = activity
    private val db = LiveDb.get(ctx)
    private val sync = LiveSync.get(ctx)
    private val player = PlayerHost.get(ctx)
    private val prefs = LivePrefs(ctx)
    private val main = Handler(Looper.getMainLooper())

    val root = FrameLayout(ctx)
    /** Everything but the full-screen controls: the groups column, and the content (miniature, details, guide). */
    private val guideLayer = FrameLayout(ctx)
    private val sidebarPanel = FrameLayout(ctx)
    /** A hairline at the column's right edge (glass), so the column reads as its own area without a panel. */
    private val columnRule = View(ctx)
    val sidebar = GuideSidebar(ctx, t)
    /** Miniature, details and guide: its left edge slides as the column opens and closes. */
    private val content = FrameLayout(ctx)
    /** Where the miniature goes. With nothing playing it's a panel with a hint; while playing it's see-through. */
    private val videoSlot = t.label(ctx, 14f, color = t.textMuted).apply {
        text = "Press OK on a channel to watch it here"
        gravity = Gravity.CENTER
        maxLines = 2
        setPadding(t.dp(24), 0, t.dp(24), 0)
    }
    private var placedAt: RectF? = null
    /** Back from full screen while the content moves: the shrink waits for the layout that puts the slot in place. */
    private var shrinkPending = false
    private val details = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    /** The channel's logo on its plate, before its name on the details' top line (as in the guide's rows). */
    private val detailLogo = LogoView(ctx)
    private val detailChannel = t.label(ctx, 16f, t.regular, t.text)
    private val detailStar = android.widget.ImageView(ctx)
    private val detailTitle = t.label(ctx, 25f, t.bold)
    private val detailMeta = t.label(ctx, 14.5f, t.regular, t.text)
    private val detailTime = t.label(ctx, 15f)
    private val detailBar = ProgressLine(ctx, t)
    private val detailLeft = t.label(ctx, 15f)
    private val detailDesc = t.label(ctx, 14.5f, color = t.textMuted).apply { maxLines = 3 }
    private val detailNext = t.label(ctx, 14f, color = t.textMuted)
    val guide = GuideView(ctx, t)
    private val controls = LiveControls(ctx, t)
    /** The extra row's windows and the sleep timer. */
    private val more = LiveMore(activity, host.sidePanel, player, controls, object : LiveMore.Callbacks {
        override fun windowClosed(from: View) { backFromWindow(from) }
        override fun pictureChanged() { host.pictureChanged() }
        override fun programmeEnd(): Long? = programmeNow()?.stop
        override fun sleepNow() { stopForSleep() }
    })
    private val empty = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
    private val emptyText = t.label(ctx, 18f, t.semibold).apply { maxLines = 3; gravity = Gravity.CENTER }
    private val emptyButton = t.label(ctx, 16f, t.semibold)
    private val notice = t.label(ctx, 15f).apply { maxLines = 2 }
    /** The small window a held Center opens (a channel in the guide, a group in the column); one at a time. */
    private val popup = HoldPopup(ctx, t)
    /** The channel the small window is for, as it stands (its star follows each press). */
    private var popupChannel: Channel? = null
    /** Where the selection goes when the small window closes: the held cell, or the group's row (or the one in its place). */
    private var popupFocusBack: (() -> Unit)? = null
    /** The Multiview button Multiview was started from, for focus to come back to when its first pick is cancelled. */
    private var multiviewFrom: View? = null

    /** Multiview, inside full screen: its pictures, players, picker and small window are made the first time it opens. */
    private val multiview = Multiview(activity, t, object : Multiview.Callbacks {
        override fun addBoard(board: View) = host.addMultiview(board)

        override fun addOverlay(view: View, params: FrameLayout.LayoutParams) {
            // Over the page, under its notices (which say what a pick couldn't do).
            root.addView(view, root.indexOfChild(notice), params)
        }

        override fun enter() = enterMultiview()
        override fun leave(channel: Channel, playlist: Playlist) = leaveMultiview(channel, playlist)

        override fun entryCancelled() {
            val from = multiviewFrom
            multiviewFrom = null
            if (released || !fullScreen) return
            if (from != null) showControls(from)
        }

        override fun lookUp(key: ChannelKey, then: (Channel, Playlist) -> Unit) {
            bg {
                val ch = db.channel(key) ?: return@bg
                val pl = db.playlist(ch.playlistId) ?: return@bg
                main.post { if (!released) then(ch, pl) }
            }
        }

        override fun notice(text: String) = showNotice(text)

        override fun list(): LiveOverlay = overlay()

        override fun readyPicture(on: Boolean, animate: Boolean) {
            if (released || !fullScreen) return
            val ms = if (animate) GROW_MS else 0L
            if (!on) {
                host.placeVideo(null, 0f, true, ms)
                return
            }
            val screen = root.rootView
            val d = t.density
            val b = OverlayRules.readyPicture(screen.width / d, screen.height / d, LiveWidths.GAP.toFloat())
            host.placeVideo(RectF(b[0] * d, b[1] * d, b[2] * d, b[3] * d), t.dpf(LiveWidths.MINI_RADIUS), false, ms)
        }
    })

    /**
     * The channel list over the picture ([LiveOverlay]): full screen's Left, and Multiview's way to pick a channel.
     * Made the first time it's needed.
     */
    private var overlay: LiveOverlay? = null

    private fun overlay(): LiveOverlay = overlay ?: LiveOverlay(activity, t, overlayIo).also {
        overlay = it
        // Over the page and its controls, under its notices (which say what a pick couldn't do).
        root.addView(it, root.indexOfChild(notice), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** The list's reads, behind the screen on the guide's thread, answered on the main thread. */
    private val overlayIo = object : LiveOverlay.Io {
        override fun logo(url: String): Logo? = logos.get(url)

        override fun groupOf(channel: Channel, then: (GuideScope, List<Channel>) -> Unit) {
            val key = channel.key
            // The playing channel's group as Channel ▲ / ▼ move through it, when it's read and still current.
            val started = zapScope.takeIf { player.currentKey == key.toString() }
            val zl = zapList
            if (started != null && zl != null && zapLists == db.listChanges.get() && zl.holds(key)) {
                then(started, zl)
                return
            }
            val last = prefs.zapGroupOf(key)
            bg {
                for (s in ZapOrder.candidates(started, last, key.playlistId, channel.groupId)) {
                    val (list, _) = runCatching { listFor(s) }.getOrNull() ?: continue
                    if (!list.holds(key)) continue
                    main.post { if (!released) then(s, list) }
                    return@bg
                }
                // Gone from its playlist meanwhile: the playlist's channels, without it.
                val all = GuideScope.AllChannels(key.playlistId)
                val list = runCatching { listFor(all).first }.getOrDefault(emptyList())
                main.post { if (!released) then(all, list) }
            }
        }

        override fun channels(scope: GuideScope, then: (List<Channel>) -> Unit) = bg {
            val list = runCatching { listFor(scope).first }.getOrDefault(emptyList())
            main.post { if (!released) then(list) }
        }

        // The guide's own read for its groups column ([reload]): every playlist, each one's shown groups in order.
        override fun column(then: (List<Playlist>, Map<String, List<ChannelGroup>>) -> Unit) = bg {
            val pls = runCatching { db.playlists() }.getOrDefault(emptyList())
            val groups = runCatching { pls.associate { it.id to db.groups(it.id) } }.getOrDefault(emptyMap())
            main.post { if (!released) then(pls, groups) }
        }

        override fun folds(): GroupColumn.Folds = sidebar.folds

        override fun foldsChanged() = sidebar.foldsChanged()

        override fun programmes(channels: List<Channel>, from: Long, to: Long, then: (Map<ChannelKey, List<Programme>>) -> Unit) = bg {
            val map = runCatching { db.programmes(channels, from, to) }.getOrDefault(emptyMap())
            main.post { if (!released) then(map) }
        }
    }

    /** Full screen's list: OK on a channel plays it (the list stays); on the one playing, the list closes. */
    private val watchList = object : LiveOverlay.Listener {
        override fun onChannel(channel: Channel, scope: GuideScope?) {
            val playing = channel.key.toString() == player.playingKey && player.status().error == null
            when (OverlayRules.okOnChannel(picking = false, isPlaying = playing)) {
                OverlayRules.Ok.CLOSE -> overlay?.close()
                else -> if (tune(channel, from = scope)) {
                    // Back to the guide from here: on this channel.
                    zappedInFull = true
                    refreshControls()
                }
            }
        }

        override fun onClosed() = Unit
    }

    /** Full screen's Left: the channel list over the picture, on the playing channel's group. */
    private fun openList() {
        val key = ChannelKey.parse(player.currentKey) ?: return
        val ch = playingChannel?.takeIf { it.key == key } ?: guide.channelOf(key)
        val pl = ch?.let { c -> playlists.firstOrNull { it.id == c.playlistId } }
        if (ch == null || pl == null) {
            showNotice("The channel is still being read: try again in a moment")
            return
        }
        hideControlsNow()
        overlay().open(ch, pl, picking = false, listener = watchList)
    }

    private val logos = LogoCache(t.dp(76)) { url, loaded ->
        if (loaded) {
            guide.invalidate()
            // The controls show one logo, the playing channel's: only that one landing concerns them (each of the
            // guide's rows' logos used to work the controls' channel and programme out again).
            if (url == controlsLogo) refreshControls()
        }
        // The focused channel's own: its logo shows on the plate waiting for it, or (it failed) the name moves over.
        detailChannelObj?.let { if (it.logo == url) showDetailLogo(it) }
        if (loaded) overlay?.logosChanged()
        logLogos()
    }.also { LogoCache.useDisk(ctx) }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "FirePlayerGuide").apply { priority = Thread.NORM_PRIORITY - 1 } }
    /**
     * The viewer's own changes (favorites, groups) are stored here, not queued behind the guide's reads: the queue the
     * settings windows' stars go through too, so Assign favorites opened from here sees a star set a moment before.
     */
    private val writes get() = LiveDb.writes

    private var playlists: List<Playlist> = emptyList()
    /** Group names by (playlist, group), for the details' "Playlist • Group" line. */
    private var groupNames: Map<Pair<String, String>, String> = emptyMap()
    private var scope: GuideScope = prefs.scope ?: GuideScope.AllPlaylists
    private var fullScreen = false
    private var sidebarOpen = true
    private var started = false
    /**
     * The tab was left with its channel playing on in the picture on Home or Search ([leave]): the stream, the channel
     * buttons and the sleep timer carry on while the page is away; nothing of the page itself runs.
     */
    private var keeping = false
    /** The channel plays for this page: the tab shows, or its channel is kept playing elsewhere. */
    private val playsHere: Boolean get() = started || keeping
    private var backLongPressed = false
    private var focusBeforeFull: ChannelKey? = null
    private var zappedInFull = false
    private var detailCell: Cell? = null
    private var detailChannelObj: Channel? = null
    private var loadSerial = 0

    // Geometry (dp on the 960 x 540 dp screen), after the user's concepts: no panels; a large miniature with the details
    // beside it; the guide below, full width in the grid. With the groups column open the tab rail's strip shows at the
    // left, then the column, then the content; in the grid both slide away and the content slides left (one motion).
    // Open: rail 58 + column 224 + gap 12 + miniature 368 + 18 + details 268 + margin 12 = 960 ([LiveWidths]).
    private val railSpace = t.dp(LiveWidths.RAIL)
    private val columnW = t.dp(LiveWidths.COLUMN)
    private val miniW = t.dp(LiveWidths.MINI)
    private val miniH = t.dp(LiveWidths.MINI_H)
    private val gap = t.dp(LiveWidths.GAP)
    /**
     * The details' logo tile ([LiveWidths]: large, the logo fitted inside with its shape kept), and the room before the
     * name when there's one.
     */
    private val detailLogoW = t.dp(LiveWidths.DETAIL_LOGO_W)
    private val detailLogoH = t.dp(LiveWidths.DETAIL_LOGO_H)
    private val detailNameInset = detailLogoW + t.dp(LiveWidths.DETAIL_NAME_GAP)
    private val openInset = railSpace + columnW + gap
    private val closedInset = gap

    /**
     * How far the content sits right of its place in the grid while the column is open. The content is laid out once,
     * at the grid's place, and slides by this much as a whole (a transform: nothing is laid out again frame by frame).
     */
    private val shift = openInset - closedInset
    /** True while the content, column and rail slide: the miniature is already on its way to where the slide ends. */
    private var sliding = false
    /** Bumped by every change of layout or screen: a motion waiting for its first frame starts only if still current. */
    private var motionSerial = 0

    init {
        build()
        wire()
    }

    // ---- Layout ---------------------------------------------------------------------------------------------------

    private fun build() {
        // Column: playlists and groups, from the top of the screen.
        sidebarPanel.addView(sidebar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sidebarPanel.addView(columnRule, FrameLayout.LayoutParams(t.dp(1), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        guideLayer.addView(sidebarPanel, FrameLayout.LayoutParams(columnW, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            leftMargin = railSpace; topMargin = t.dp(8); bottomMargin = t.dp(8)
        })

        // Content: the miniature and its details on top, the guide under them.
        val topRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        topRow.addView(videoSlot, LinearLayout.LayoutParams(miniW, miniH))
        // The top line: [logo] Channel name … star. Its height is the logo's, logo or not, so the lines under it never
        // move; a channel without a logo has its name moved over to the start by a transform ([showDetailLogo]).
        val head = FrameLayout(ctx)
        detailLogo.pad(LiveWidths.DETAIL_LOGO_PAD_X, LiveWidths.DETAIL_LOGO_PAD_Y)
        head.addView(detailLogo, FrameLayout.LayoutParams(detailLogoW, detailLogoH, Gravity.LEFT or Gravity.CENTER_VERTICAL))
        // Beside the logo, centred on it; a long name takes a second line there. As tall as the line whatever it says,
        // so a new channel's name only redraws it.
        detailChannel.maxLines = 2
        detailChannel.gravity = Gravity.CENTER_VERTICAL
        head.addView(detailChannel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, detailLogoH).apply {
            leftMargin = detailNameInset; rightMargin = t.dp(LiveWidths.DETAIL_STAR)
        })
        detailStar.imageTintList = android.content.res.ColorStateList.valueOf(t.text)
        head.addView(detailStar, FrameLayout.LayoutParams(t.dp(22), t.dp(22), Gravity.END or Gravity.CENTER_VERTICAL))
        details.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, detailLogoH))
        // Full width, one line each: a new channel's text only redraws them (nothing is laid out again as focus moves).
        detailTitle.maxLines = 1
        detailTitle.ellipsize = android.text.TextUtils.TruncateAt.END
        details.addView(detailTitle, wide(top = LiveWidths.DETAIL_TITLE_GAP))
        detailMeta.maxLines = 1
        detailMeta.ellipsize = android.text.TextUtils.TruncateAt.END
        detailMeta.alpha = 0.85f
        details.addView(detailMeta, wide(top = 2))
        val timeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(detailTime)
            // Each has its own space before it: with the bar hidden (a programme not on now) the time and "in …" keep theirs.
            addView(detailBar, LinearLayout.LayoutParams(t.dp(96), t.dp(10)).apply { leftMargin = t.dp(12) })
            addView(detailLeft, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = t.dp(12) })
        }
        details.addView(timeRow, lp(top = 8))
        details.addView(detailDesc, wide(top = 8))
        details.addView(detailNext, wide(top = 6))
        topRow.addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = t.dp(LiveWidths.DETAILS_GAP) })
        content.addView(topRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, miniH))
        content.addView(guide, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { topMargin = miniH + t.dp(10) })
        // Down to the screen's bottom edge: the guide fits exactly its eight whole rows in there ([GuideView.ROWS]).
        guideLayer.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            leftMargin = closedInset; topMargin = t.dp(14); rightMargin = gap
        })
        content.translationX = shift.toFloat()
        root.addView(guideLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Nothing to show yet: a way to add a playlist.
        emptyButton.apply {
            text = "Add a playlist"
            isFocusable = true
            setPadding(t.dp(22), t.dp(10), t.dp(22), t.dp(10))
            setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(t.accent, t.text)))
            Glass.background(this, Glass.Kind.PILL)
            setOnClickListener { host.openPlaylists() }
        }
        empty.addView(emptyText)
        empty.addView(emptyButton, lp(top = 18).apply { gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { leftMargin = railSpace })
        empty.visibility = View.GONE

        notice.setPadding(t.dp(16), t.dp(10), t.dp(16), t.dp(10))
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(notice, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = t.dp(30) })
        notice.visibility = View.GONE
        root.addView(popup, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT))
        popup.onClose = { focusBack ->
            val back = popupFocusBack
            popupFocusBack = null
            popupChannel = null
            if (focusBack) back?.invoke()
            // The viewer was busy in it: the guide's wait for full screen starts again.
            scheduleIdle()
        }
        applyLook()
    }

    // ---- The column and the rail: open (column focused) or in the grid -------------------------------------------

    /**
     * The details' and the guide's right edges: [shift] in from the content's (narrow, so they end at the screen's
     * margin while the content sits [shift] to the right) or none (the grid). A layout pass when they change: once per
     * slide, never during one.
     */
    private fun narrowDetails(on: Boolean) {
        rightMargin(details, if (on) shift else 0)
        // Narrow, the progress bar is shorter, so the time and "… min left" still fit beside it.
        val bar = detailBar.layoutParams as LinearLayout.LayoutParams
        val w = t.dp(if (on) 44 else 96)
        val m = t.dp(if (on) 8 else 12)
        if (bar.width != w) {
            bar.width = w
            bar.leftMargin = m
            detailBar.layoutParams = bar
            val left = detailLeft.layoutParams as LinearLayout.LayoutParams
            left.leftMargin = m
            detailLeft.layoutParams = left
        }
    }
    private fun narrowGuide(on: Boolean) = rightMargin(guide, if (on) shift else 0)

    private fun rightMargin(v: View, px: Int) {
        val lp = v.layoutParams as ViewGroup.MarginLayoutParams
        if (lp.rightMargin == px) return
        lp.rightMargin = px
        v.layoutParams = lp
    }

    /**
     * Column open (with the tab rail) or the grid (both slid away, content full width). One motion: the column and rail
     * slide out to the left as the content slides left, and the miniature moves with it.
     *
     * The content slides as a whole (its translation), so nothing is laid out frame by frame. The details take their
     * open width when the column starts to open and their grid width when it has closed, so their text never runs
     * off the screen; the guide is the grid's width while it moves (running past the screen's edge, which hides it) and
     * ends at the screen's margin once open.
     */
    private fun layoutFor(columnOpen: Boolean, animate: Boolean) {
        val target = if (columnOpen) shift.toFloat() else 0f
        val serial = ++motionSerial
        content.animate().cancel()
        sidebarPanel.animate().cancel()
        sliding = false
        val hidden = -(railSpace + columnW).toFloat()
        if (columnOpen) sidebarPanel.visibility = View.VISIBLE
        if (!animate || !root.isShown) {
            host.setRailShown(columnOpen, 0L)
            content.translationX = target
            narrowDetails(columnOpen)
            narrowGuide(columnOpen)
            // In the grid the channel names get more room.
            guide.setWideColumn(!columnOpen)
            sidebarPanel.translationX = if (columnOpen) 0f else hidden
            sidebarPanel.alpha = if (columnOpen) 1f else 0f
            if (!columnOpen) sidebarPanel.visibility = View.INVISIBLE
            return
        }
        if (columnOpen) narrowDetails(true) else narrowGuide(false)
        sliding = true
        onNextFrame {
            if (serial != motionSerial || released) return@onNextFrame
            slide(columnOpen, target, hidden)
        }
    }

    /**
     * Runs [action] in the next frame, before it's drawn. A motion started there begins with that frame, after the
     * slow one this frame may be (a layout, focus moving, something drawn for the first time): it then plays from its
     * start instead of jumping ahead over the slow frame (on a Fire TV, up to a third of a slide).
     */
    private fun onNextFrame(action: () -> Unit) = android.view.Choreographer.getInstance().postFrameCallback { action() }

    private fun slide(columnOpen: Boolean, target: Float, hidden: Float) {
        host.setRailShown(columnOpen, SLIDE_MS)
        // The miniature heads for where the slot will be when the slide ends, in step with it (same time and easing).
        placeMini(SLIDE_MS, dx = target - content.translationX)
        content.animate().translationX(target).setDuration(SLIDE_MS).setInterpolator(EASE)
            .withEndAction {
                sliding = false
                if (columnOpen) narrowGuide(true) else narrowDetails(false)
                // Exactly in place (a no-op unless something moved the slot meanwhile).
                if (!fullScreen && rectOf(videoSlot) != placedAt) placeMini()
            }
            .start()
        // The column fades as one picture (a layer drawn once), not drawn again into a new layer every frame.
        sidebarPanel.animate().translationX(if (columnOpen) 0f else hidden).alpha(if (columnOpen) 1f else 0f)
            .setDuration(SLIDE_MS).setInterpolator(EASE).withLayer()
            .withEndAction { if (!sidebarOpen) sidebarPanel.visibility = View.INVISIBLE }
            .start()
        // The channel names' column widens in the grid (narrows as the column comes back), in step: drawn, not laid out.
        guide.setWideColumn(!columnOpen, SLIDE_MS, EASE)
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = t.dp(top) }
    private fun wide(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = t.dp(top) }

    /** Panels get the look's surface (glass over the blurred wallpaper, or the original dark panel). */
    fun applyLook() {
        // The guide's glass blurs the wallpaper and is never dimmed: it's made with the film off, even when the look
        // changes while the full-screen controls are up; the controls are then made again over the film.
        val film = filmGlassOn
        if (film) host.setFilmGlass(false)
        // No panels (user's concepts): the column, details and guide sit on the wallpaper.
        sidebarPanel.background = null
        details.background = null
        // Glass: the column's edge is the shared hairline (about a pixel on the TV), as the guide's rules are.
        columnRule.setBackgroundColor(if (Glass.on) GlassStyle.HAIRLINE else 0x14FFFFFF)
        val ruleW = if (Glass.on) GlassStyle.hairlinePx(t.density).toInt().coerceAtLeast(1) else t.dp(1)
        columnRule.layoutParams?.let { if (it.width != ruleW) { it.width = ruleW; columnRule.requestLayout() } }
        Glass.overlay(notice, Glass.Kind.POPUP)
        Glass.background(emptyButton, Glass.Kind.PILL)
        popup.applyLook()
        guide.glass = Glass.on
        // Its tile is the look's (glass tile or classic plate): drawn again.
        detailLogo.invalidate()
        if (film) host.setFilmGlass(true)
        controls.applyLook()
        multiview.applyLook()
        overlay?.applyLook()
        if (placeholder == true) { placeholder = null; showSlotPlaceholder(true) }
    }

    private fun wire() {
        // The slot moves when the column opens or closes (its parent moves, not the slot within it): every layout pass
        // compares where it is on screen with where the video was put.
        root.viewTreeObserver.addOnGlobalLayoutListener {
            // Mid-slide the slot is on its way; the miniature already heads for where it ends.
            if (fullScreen || sliding || !root.isShown) return@addOnGlobalLayoutListener
            if (player.currentKey == null) {
                if (placeholder != true) placeMini()
                return@addOnGlobalLayoutListener
            }
            val r = rectOf(videoSlot) ?: return@addOnGlobalLayoutListener
            if (shrinkPending) {
                // Back from full screen with the content just moved: shrink into where the miniature is now.
                shrinkPending = false
                placeMini(SHRINK_MS)
            } else if (r != placedAt) placeMini()
        }
        guide.callbacks = object : GuideView.Callbacks {
            override fun onFocusMoved(channel: Channel, cell: Cell?) = showDetails(channel, cell)
            override fun onPick(channel: Channel, cell: Cell?) = pick(channel)
            override fun onHold(channel: Channel) = openChannelPopup(channel)
            override fun onLeaveLeft() = openSidebar(focus = true)
            override fun needProgrammes(channels: List<Channel>, onScreen: Int, from: Long, to: Long) = loadProgrammes(channels, onScreen, from, to)
            override fun logo(url: String): Logo? = logos.get(url)
        }
        guide.setOnFocusChangeListener { _, has -> if (has) closeSidebar() }
        sidebar.callbacks = object : GuideSidebar.Callbacks {
            override fun onScope(scope: GuideScope, enter: Boolean) {
                if (scope != this@LivePage.scope) {
                    this@LivePage.scope = scope
                    prefs.scope = scope
                    loadChannels(focus = null, enterGrid = enter)
                } else if (enter) {
                    // The same scope again. Already shown (no load on its way): straight in; reloading it first took
                    // seconds with a big playlist. Otherwise go in once its list has arrived, if it has channels.
                    when {
                        // Its list is on its way: go in when it arrives (a second read would only start over).
                        loadingScope == scope -> pendingEnter = true
                        shownScope == scope && guide.channelList().isNotEmpty() -> guide.requestFocus()
                        else -> loadChannels(focus = if (shownScope == scope) guide.focusedChannel()?.key else null, enterGrid = true)
                    }
                }
            }

            override fun onHold(scope: GuideScope.Group, name: String, row: View) = openGroupPopup(scope, name, row)
        }
        controls.actions = object : LiveControls.Actions {
            override fun onGuide() = toGuide(toPlaying = true)
            override fun onHistory() = showHistory()
            override fun onCaptions(from: View) = chooseCaptions(from)
            override fun onFavorite() { toggleFavorite() }
            override fun onPlayPause() = togglePause()
            override fun onGoLive() = goLive()
            override fun onMore(key: String, from: View) {
                when (key) {
                    LiveControls.SETTINGS -> host.openPlayerSettings { backFromWindow(from) }
                    LiveControls.MULTIVIEW -> startMultiview(from)
                    else -> more.open(key, from)
                }
            }
        }
    }

    // ---- Lifecycle ------------------------------------------------------------------------------------------------

    private val statusListener = object : PlayerHost.Listener {
        override fun onLiveStatus(status: PlayerHost.Status) {
            guide.playingKey = ChannelKey.parse(status.key)
            controls.setStatus(status)
            status.error?.let { showNotice(it) }
            val pausedNow = status.paused
            if (pausedNow != wasPaused) {
                wasPaused = pausedNow
                pauseChanged(pausedNow)
            }
        }
    }

    /** Whether the channel was paused at the last status, to see a pause or resume come. */
    private var wasPaused = false

    /**
     * In full screen, a pause the viewer didn't make (another app took the sound) shows the controls with Play
     * selected, as the viewer's own pause does, rather than leaving a still picture with nothing to say so; carrying
     * on gives the controls their usual time again.
     */
    private fun pauseChanged(paused: Boolean) {
        // Multiview's channel picker over full screen keeps the selection (the controls come back when it closes).
        if (!fullScreen || released || multiview.active) return
        if (paused) {
            if (!controls.controlsShown && !FireDialog.isShowing(activity) && !host.overlayOpen()) {
                showControls()
                controls.focusPlay()
            }
        } else if (controls.controlsShown) {
            bumpControls()
        }
    }

    private val dataListener = object : LiveSync.Listener {
        override fun onLiveDataChanged(playlistId: String) {
            // A change this page made itself (a group moved or hidden) is on screen already: reading everything again
            // would redraw the column a second time and empty the guide's rows while their listings load.
            if (ownChanges > 0) { ownChanges--; return }
            reload(keepFocus = true)
        }

        /** An update let go of the channel for a moment (live/SyncGuard.kt): one plain line over the picture. */
        override fun onUpdateNotice(text: String) { if (started) showNotice(text) }
    }

    /** Changes this page announced ([announce]) that its own listener is still to hear of. */
    private var ownChanges = 0

    /** When the tab last started showing (uptime ms), until its guide is listed: for the log of how long that took. */
    private var openedAt = 0L
    /**
     * The same for its stages, each logged once per opening: the playlists and groups, the first listings and the
     * first logos. A stage that comes long after ([OPENING_MS]: nothing was read at the opening, and this is a later
     * scroll's) says nothing.
     */
    private var openGroupsAt = 0L
    private var openProgrammesAt = 0L
    private var openLogosAt = 0L
    /** The logo cache's counts (kept on disk, fetched, without one) as they stood when the tab opened. */
    private val logosAtOpen = IntArray(3)
    /** The listings last asked for when the tab opened: still the last when its list shows, none were needed. */
    private var askAtOpening: Any? = null

    /** How long after the tab opened at [at] it is now, for a stage's line; null when that's no longer an opening's. */
    private fun sinceOpening(at: Long): Long? =
        if (at == 0L) null else (android.os.SystemClock.uptimeMillis() - at).takeIf { it <= OPENING_MS }

    /** Once per opening, when the first logos asked for have all landed: how long they took and where they came from. */
    private fun logLogos() {
        if (openLogosAt == 0L || logos.pendingCount > 0) return
        sinceOpening(openLogosAt)?.let { ms ->
            Log.i(TAG, "Live TV: logos shown (${logos.fromDisk - logosAtOpen[0]} kept on disk, ${logos.fromNetwork - logosAtOpen[1]} fetched, " +
                "${logos.failedCount - logosAtOpen[2]} without one) $ms ms after opening")
        }
        openLogosAt = 0L
    }

    /** When a key last went down here (KeyEvent.eventTime), for the log of how long a channel took to show. */
    private var keyAt = 0L

    /** The tab is showing (activity started, or switched to this tab). */
    fun start() {
        if (started) return
        started = true
        // A channel kept playing on Home or Search comes back into the miniature, still playing.
        keeping = false
        // Everything is read again below: an announcement heard of while away is no longer to be skipped.
        ownChanges = 0
        player.addListener(statusListener)
        sync.addListener(dataListener)
        // A channel that was playing comes back in the miniature (not full screen).
        openedAt = android.os.SystemClock.uptimeMillis()
        startedAt = openedAt
        openGroupsAt = openedAt
        openProgrammesAt = openedAt
        openLogosAt = openedAt
        askAtOpening = programmeAsk
        logosAtOpen[0] = logos.fromDisk
        logosAtOpen[1] = logos.fromNetwork
        logosAtOpen[2] = logos.failedCount
        // (A channel that was playing when the tab was left comes back whatever the setting says: that's carrying on.)
        // Multiview's pictures come back instead, when it was showing.
        val resume = if (!player.onScreenStarted() && player.currentKey == null && prefs.playLastOnOpening && !multiview.isOpen) prefs.lastChannel else null
        multiview.resume()
        // Built now, while the channel is read, rather than when it arrives.
        if (resume != null) player.warm()
        // The rail and column as they were (the rail shows with the column, or while there's nothing else).
        layoutFor(columnOpen = sidebarOpen || playlists.isEmpty(), animate = false)
        // The playlists and groups are asked for first and the last channel right behind them (two reads by key), so
        // the guide's own list is read before the playing channel's group is (what Channel ▲ / ▼ move through): what
        // is on screen first. Asked for the other way round, a long group list held up the guide's.
        reload(keepFocus = true)
        resume?.let { resumeLast(it) }
        scheduleIdle()
        main.post(clockTick)
        // A sleep timer that waited while the page was away carries on.
        more.resume()
    }

    /**
     * The tab is left for another ([keepPlaying]: its channel plays on in the picture on Home or Search). Full screen
     * is left first, quietly, so the tab comes back to its guide (it used to come back to an empty full screen).
     * True when the channel plays on.
     */
    fun leave(keepPlaying: Boolean): Boolean {
        if (!started) return false
        if (fullScreen) quitFullScreen()
        stop(keepPlaying && player.currentKey != null)
        return keeping
    }

    /**
     * The channel kept playing on Home or Search stops (Close, another tab, the screen stopping): its stream and
     * connection go, the channel remembered, as leaving the tab always did.
     */
    fun letGo() {
        if (!keeping) return
        keeping = false
        dropZap()
        more.pause()
        player.onScreenStopped(host.videoOwner)
    }

    /**
     * The screen is back with the picture on Home or Search, as it was when the screen stopped: its channel plays on
     * again. False when nothing came back (a film started meanwhile, or the connection is taken).
     */
    fun keepAgain(): Boolean {
        if (started || released) return false
        player.onScreenStarted()
        // Playing again, not only remembered (the provider's connection may be taken meanwhile).
        keeping = player.playingKey != null
        if (keeping) more.resume() else player.onScreenStopped(host.videoOwner)
        return keeping
    }

    /** The channel kept playing on Home or Search, for the picture's strip: its name and what's on ([then], main thread). */
    fun keptInfo(then: (name: String, programme: String?) -> Unit) {
        val key = ChannelKey.parse(player.currentKey) ?: return
        val known = playingChannel?.takeIf { it.key == key }
        val on = programmeNow()
        if (known != null && on != null) { then(known.name, on.title); return }
        bg {
            val ch = known ?: db.channel(key) ?: return@bg
            val now = System.currentTimeMillis()
            val programme = runCatching { db.programmes(listOf(ch), now, now + 1)[ch.key]?.firstOrNull { it.isOn(now) } }.getOrNull()
            main.post { if (!released && player.currentKey == key.toString()) then(ch.name, programme?.title) }
        }
    }

    /**
     * Straight to full screen on the channel kept playing (the picture's Full screen, or Back held on its page), the
     * picture growing from where it is; just shown again, before the guide places its miniature. A channel that isn't
     * playing any more opens as from search.
     */
    fun openKeptFull(key: ChannelKey) {
        if (!started) return
        if (player.playingKey == key.toString()) toFull() else openFromSearch(key, full = true)
    }

    /** Full screen left at once, nothing moving (the tab is being left): the guide is as Back would leave it. */
    private fun quitFullScreen() {
        overlay?.close(animate = false)
        ++motionSerial
        dropZap()
        fullScreen = false
        revealKey = 0
        shrinkPending = false
        main.removeCallbacks(hideControls)
        controls.hideAll()
        filmGlass(false)
        host.setChromeVisible(true)
        sidebarOpen = false
        layoutFor(columnOpen = false, animate = false)
        guideLayer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        guideLayer.animate().cancel()
        guideLayer.alpha = 1f
        guideLayer.visibility = View.VISIBLE
    }

    /** The tab is hidden or the activity stopped: the stream is released, the channel remembered. */
    fun stop(keepPlaying: Boolean = false) {
        if (!started) return
        started = false
        keeping = keepPlaying
        // The selection goes back to the held row (or cell) first, quietly: a window gone while it still held focus
        // would hand it to the column's top row, whose preview would then switch the guide.
        popup.close(fade = false)
        backClosedPopup = false
        dropZap()
        // Every Multiview picture's stream, player and connection go too (the pictures come back with the screen).
        multiview.suspend()
        multiviewFrom = null
        overlay?.close(animate = false)
        player.removeListener(statusListener)
        sync.removeListener(dataListener)
        // Kept playing on Home or Search: the stream and its connection stay.
        if (!keepPlaying) player.onScreenStopped(host.videoOwner)
        placedAt = null
        main.removeCallbacks(idleToFull)
        main.removeCallbacks(clockTick)
        main.removeCallbacks(hideControls)
        main.removeCallbacks(hideNotice)
        // The sleep timer carries on with a channel kept playing.
        if (!keepPlaying) more.pause()
        // The controls' timer is gone: don't leave them up for the return.
        if (fullScreen) controls.hideAll()
        filmGlass(false)
        revealKey = 0
        backDownSeen = false
    }

    /**
     * Film mode (design-brief.md §6.6), while the tab is stopped: the guide's listings and the channel logos leave
     * memory; they load again as they show.
     */
    fun dropMemory() {
        if (started) return
        guide.forgetProgrammes()
        logos.clear()
        // The lists kept beside the one the guide shows (they are read again when asked for).
        lists.clear()
    }

    fun release() {
        letGo()
        stop()
        multiview.release()
        overlay?.release()
        more.release()
        released = true
        ++reloadSerial
        ++loadSerial
        listCancel?.cancel()
        listCancel = null
        io.shutdown()
        // Queued favorites are still stored (the queue is the app's, not this page's).
    }

    /** Background work, unless the page is gone (its executor is shut down then). Main thread only. */
    private fun bg(task: () -> Unit) {
        if (!released) io.execute(task)
    }

    private var released = false
    private var loaded = false
    private var focusPending = false

    /** Where focus goes when this tab is entered from the rail (once its data has loaded). */
    fun takeFocus() {
        if (!loaded) { focusPending = true; return }
        focusPending = false
        if (playlists.isEmpty()) { if (empty.visibility == View.VISIBLE) emptyButton.requestFocus(); return }
        if (sidebarOpen) sidebar.focusCurrent() else guide.requestFocus()
    }

    /** Any key while this tab shows: the guide's inactivity timer starts again. */
    fun noteKey() {
        if (started && !fullScreen) scheduleIdle()
    }

    // ---- Data -----------------------------------------------------------------------------------------------------

    private var reloadSerial = 0

    private fun reload(keepFocus: Boolean) {
        val serial = ++reloadSerial
        bg {
            val t0 = android.os.SystemClock.uptimeMillis()
            val pls = db.playlists()
            val groups = pls.associate { it.id to db.groups(it.id) }
            // A guide counts as loading until its first import was attempted (worked or not).
            val withGuide = db.epgStatuses().map { it.source }.toSet()
            val readMs = android.os.SystemClock.uptimeMillis() - t0
            main.post {
                if (serial != reloadSerial || released) return@post
                playlists = pls
                // A first guide import can take minutes on a big provider: until it lands, empty rows say so.
                guide.loadingPlaylists = if (sync.busy) pls.map { it.id }.filter { it !in withGuide }.toSet() else emptySet()
                val first = !loaded
                loaded = true
                showEmpty(pls.isEmpty())
                if (pls.isNotEmpty()) {
                    if (!scopeValid(scope, pls, groups)) scope = GuideScope.AllPlaylists
                    sidebar.setData(pls, groups, scope)
                    groupNames = groups.values.flatten().associate { (it.playlistId to it.id) to it.name }
                    sinceOpening(openGroupsAt)?.let { ms ->
                        Log.i(TAG, "Live TV: groups listed (${pls.size} playlists, ${groupNames.size} groups) $ms ms after opening (read $readMs ms)")
                    }
                    openGroupsAt = 0L
                    loadChannels(focus = if (keepFocus) guide.focusedChannel()?.key ?: prefs.focus else null, enterGrid = false)
                }
                // The playlists changed (or the tab came back): the playing group's order is read again, behind the
                // guide's own list (what's on screen comes first), and only when something was written since.
                invalidateZap()
                // The first load: focus wasn't placed yet (the page had nothing to focus).
                if (focusPending || (first && !fullScreen && root.isShown && activity.currentFocus.let { it == null || !isInPage(it) && !host.railHasFocus() })) takeFocus()
            }
        }
    }

    private fun scopeValid(s: GuideScope, pls: List<Playlist>, groups: Map<String, List<ChannelGroup>>): Boolean = when (s) {
        GuideScope.AllPlaylists -> true
        is GuideScope.AllChannels -> pls.any { it.id == s.playlistId }
        is GuideScope.Favorites -> pls.any { it.id == s.playlistId }
        is GuideScope.Group -> groups[s.playlistId].orEmpty().any { it.id == s.groupId }
    }

    /**
     * The scope whose channels the guide shows (null while it shows none whole: nothing yet, or only a long list's
     * first screenful, [firstScreenOf]), and the one being loaded (null when none is on its way).
     */
    private var shownScope: GuideScope? = null
    private var loadingScope: GuideScope? = null
    /** OK/Right came while [loadingScope]'s list was loading: the grid is entered when it arrives. */
    private var pendingEnter = false
    /** [LiveDb.listChanges] as it stood when the list the guide shows was read: unchanged, that list still holds. */
    private var shownLists = -1
    /** The long list whose first screenful the guide shows while the whole of it is read (else null). */
    private var firstScreenOf: GuideScope? = null
    /** The lists read lately, kept while nothing is written ([ScopeLists]). */
    private val lists = ScopeLists<GuideScope, Channel>()
    /** Stops the guide's list being read now, when another is asked for (null: none is). Main thread. */
    private var listCancel: android.os.CancellationSignal? = null
    /** The counts of list and guide writes the guide's loaded listings go with: either moves, they're forgotten. */
    private var rowsLists = -1
    private var rowsEpg = -1

    /**
     * [s]'s channels, on the background thread: as kept from an earlier read when nothing was written since, else
     * read now (and kept), with the count of list writes they go with. Every playlist's are its playlists' lists one
     * after another, so those are shared with each playlist's own All channels.
     */
    private fun listFor(s: GuideScope, cancel: android.os.CancellationSignal? = null): Pair<List<Channel>, Int> {
        val version = db.listChanges.get()
        lists.get(s, version)?.let { return it to version }
        val list = if (s == GuideScope.AllPlaylists) db.playlists().flatMap { listFor(GuideScope.AllChannels(it.id), cancel).first }
            else db.channels(s, cancel)
        lists.put(s, version, list)
        return list to version
    }

    /**
     * Shows [scope]'s channels. Nothing written since they were last read: the list shown, or one kept from a moment
     * ago, is shown as it is (no read). Otherwise it's read behind the screen; a list still being read for an earlier
     * ask is given up first, and of a long list new to the guide the first screenful shows while the rest is read.
     */
    private fun loadChannels(focus: ChannelKey?, enterGrid: Boolean) {
        val s = scope
        val serial = ++loadSerial
        if (loadingScope != s) pendingEnter = false
        listCancel?.cancel()
        listCancel = null
        val version = db.listChanges.get()
        val kept = if (shownScope == s && shownLists == version) guide.channelList() else lists.get(s, version)
        if (kept != null) {
            showChannels(s, kept, version, focus, enterGrid)
            logListed(kept.size, "kept in memory", kept = true)
            return
        }
        loadingScope = s
        val cancel = android.os.CancellationSignal()
        listCancel = cancel
        // The channel the list will open on, as showChannels picks it.
        val wanted = focus ?: ChannelKey.parse(player.currentKey) ?: prefs.focus
        // A long list the guide doesn't show yet, with the viewer still in the column (or on the rail).
        val firstScreen = (s == GuideScope.AllPlaylists || s is GuideScope.AllChannels) && shownScope != s && firstScreenOf != s && !guide.isFocused
        bg {
            try {
                val t0 = android.os.SystemClock.uptimeMillis()
                if (firstScreen) {
                    val part = db.channelsNear(s, wanted, FIRST_BEFORE, FIRST_AFTER)
                    val partMs = android.os.SystemClock.uptimeMillis() - t0
                    main.post { if (serial == loadSerial && !released && part.isNotEmpty()) showFirstScreen(s, part, wanted, partMs) }
                }
                val t1 = android.os.SystemClock.uptimeMillis()
                val (list, read) = listFor(s, cancel)
                val readMs = android.os.SystemClock.uptimeMillis() - t1
                main.post {
                    if (serial != loadSerial || released) return@post
                    listCancel = null
                    showChannels(s, list, read, focus, enterGrid)
                    logListed(list.size, "read $readMs ms")
                }
            } catch (e: android.os.OperationCanceledException) {
                // Another list was asked for meanwhile: that one is on its way.
            }
        }
    }

    /** The line for opening the guide, once per opening: how long until its list showed, and how it came. */
    private fun logListed(channels: Int, how: String, kept: Boolean = false) {
        if (openedAt == 0L) return
        sinceOpening(openedAt)?.let { ms -> Log.i(TAG, "Live TV: guide listed ($channels channels) $ms ms after opening ($how)") }
        openedAt = 0L
        // The list was the one already showing and it asked for no listings: its rows still had them.
        if (kept && openProgrammesAt != 0L && programmeAsk === askAtOpening) {
            sinceOpening(openProgrammesAt)?.let { ms -> Log.i(TAG, "Live TV: programmes shown (kept in memory) $ms ms after opening") }
            openProgrammesAt = 0L
        }
    }

    /** Listings loaded before the lists or the guide were last written are forgotten; otherwise they stay as loaded. */
    private fun forgetStaleRows() {
        val nowLists = db.listChanges.get()
        val nowEpg = db.epgChanges.get()
        if (rowsLists == nowLists && rowsEpg == nowEpg) return
        rowsLists = nowLists
        rowsEpg = nowEpg
        guide.forgetProgrammes()
    }

    /**
     * The first screenful of [s] (a long list still being read): [part], around [focus]. The guide shows it at once;
     * until the whole list arrives ([showChannels]) it counts as no scope's list: the grid isn't entered, and nothing
     * takes these few rows for the list (Channel ▲ / ▼, the playing channel's group).
     */
    private fun showFirstScreen(s: GuideScope, part: List<Channel>, focus: ChannelKey?, readMs: Long) {
        shownScope = null
        firstScreenOf = s
        forgetStaleRows()
        guide.showFirstScreen(part, focus)
        sinceOpening(openedAt)?.let { ms -> Log.i(TAG, "Live TV: first channels shown (${part.size}) $ms ms after opening (read $readMs ms)") }
    }

    /**
     * [s]'s [list] arrived (read at [version] list writes): the guide shows it, focus on [focus] (else the channel
     * playing, else the last focused). Over its own first screenful, the rows on screen stay where they are.
     */
    private fun showChannels(s: GuideScope, list: List<Channel>, version: Int, focus: ChannelKey?, enterGrid: Boolean) {
        val reload = shownScope == s
        val extend = firstScreenOf == s
        shownScope = s
        shownLists = version
        loadingScope = null
        firstScreenOf = null
        // The playing group's own list, just read: kept for Channel ▲ / ▼ (no read of its own).
        if (s == zapScope && ChannelKey.parse(player.currentKey)?.let { list.holds(it) } == true) {
            zapList = list
            zapLists = version
            zapReading = null
        }
        // Listings read before an update are read again; the others stay (a channel in two lists keeps its row), and
        // the list itself asks for what it's missing.
        forgetStaleRows()
        val target = focus ?: ChannelKey.parse(player.currentKey) ?: prefs.focus
        // Over its own first screenful, opened on the same channel: the whole list comes in under the rows showing.
        // (Another channel is wanted by now, say one that started playing meanwhile: the list opens on that one.)
        if (extend && (target == null || target == guide.focusedChannel()?.key)) guide.extendChannels(list)
        else guide.setChannels(list, target, reload)
        if (list.isEmpty()) {
            showDetailsEmpty(if (s is GuideScope.Favorites) "No favorites yet. Add one with the Favorite button in full screen." else
                if (sync.busy) "Updating the playlist…" else "No channels here.")
        }
        if ((enterGrid || pendingEnter) && list.isNotEmpty()) guide.requestFocus()
        pendingEnter = false
    }

    /** The listings asked for last, until they're read: an older ask still waiting then has nothing left to do. */
    private class ProgrammeAsk(val channels: List<Channel>, val onScreen: Int)
    @Volatile private var programmeAsk: ProgrammeAsk? = null

    /**
     * The guide wants listings for [channels] ([GuideView.Callbacks.needProgrammes]): the rows on screen are read and
     * shown first, the rows around them after. A held Up or Down asks again at every row; only the newest ask is
     * read (it covers what shows now), and one overtaken half-way stops after the rows on screen.
     */
    private fun loadProgrammes(channels: List<Channel>, onScreen: Int, from: Long, to: Long) {
        val ask = ProgrammeAsk(channels, onScreen.coerceIn(0, channels.size))
        programmeAsk = ask
        bg {
            if (programmeAsk !== ask) return@bg
            val t0 = android.os.SystemClock.uptimeMillis()
            // With none on screen (those are loaded), the rows around are the whole of it.
            val first = if (ask.onScreen > 0) ask.channels.subList(0, ask.onScreen) else ask.channels
            val map = db.programmes(first, from, to)
            val readMs = android.os.SystemClock.uptimeMillis() - t0
            main.post {
                if (released) return@post
                guide.putProgrammes(first, map, from, to)
                sinceOpening(openProgrammesAt)?.let { ms ->
                    Log.i(TAG, "Live TV: programmes shown (${first.size} rows, ${map.size} with listings) $ms ms after opening (read $readMs ms)")
                }
                openProgrammesAt = 0L
            }
            if (first.size == ask.channels.size || programmeAsk !== ask) return@bg
            val rest = ask.channels.subList(ask.onScreen, ask.channels.size)
            val more = db.programmes(rest, from, to)
            main.post { if (!released) guide.putProgrammes(rest, more, from, to) }
        }
    }

    /** Whether this list holds [key]'s channel (nothing is made to look). */
    private fun List<Channel>.holds(key: ChannelKey): Boolean = any { it.id == key.channelId && it.playlistId == key.playlistId }

    private fun isInPage(v: View): Boolean {
        var p: View? = v
        while (p != null) { if (p === root) return true; p = p.parent as? View }
        return false
    }

    private fun showEmpty(noPlaylists: Boolean) {
        if (noPlaylists && fullScreen) {
            // The last playlist went (deleted from Settings over full screen): leave full screen for the empty page.
            fullScreen = false
            revealKey = 0
            controls.hideAll()
            filmGlass(false)
            host.setChromeVisible(true)
            guideLayer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            guideLayer.animate().cancel()
            guideLayer.alpha = 1f
            guide.setChannels(emptyList(), null)
            shownScope = null
            firstScreenOf = null
        }
        // The tab rail stays reachable while there's nothing else to go to.
        if (noPlaylists) { sidebarOpen = true; layoutFor(columnOpen = true, animate = false) }
        empty.visibility = if (noPlaylists) View.VISIBLE else View.GONE
        guideLayer.visibility = if (noPlaylists || fullScreen) View.INVISIBLE else View.VISIBLE
        if (noPlaylists) {
            emptyText.text = "Add a playlist to watch Live TV"
            host.placeVideo(null, 0f, false)
        } else if (!fullScreen) {
            placeMini()
        }
    }

    // ---- Guide ----------------------------------------------------------------------------------------------------

    private fun showDetails(channel: Channel, cell: Cell?) {
        detailChannelObj = channel
        detailCell = cell
        prefs.focus = channel.key
        val p = cell?.programme
        val tf = DateFormat.getTimeFormat(ctx)
        // Channel (its logo first), then its title; under it where the channel is: "Playlist • Group" (the concepts).
        showDetailLogo(channel)
        detailChannel.text = channel.name
        detailStar.setImageResource(if (channel.favorite) app.fireplayer.R.drawable.ic_star_filled else app.fireplayer.R.drawable.ic_star)
        detailStar.alpha = if (channel.favorite) 1f else 0.7f
        detailStar.contentDescription = if (channel.favorite) "In your favorites" else "Not in your favorites"
        detailStar.visibility = View.VISIBLE
        val playlistName = playlists.firstOrNull { it.id == channel.playlistId }?.name
        val groupName = channel.groupId?.let { groupNames[channel.playlistId to it] }
        detailMeta.text = listOfNotNull(playlistName, groupName).joinToString("  •  ")
        if (p == null) {
            detailTitle.text = channel.name
            detailTime.text = "No programme information"
            detailBar.visibility = View.GONE
            detailLeft.text = ""
            detailDesc.text = ""
            detailNext.text = ""
            return
        }
        val now = System.currentTimeMillis()
        detailTitle.text = p.title.ifBlank { "Untitled" }
        detailTime.text = "${tf.format(Date(p.start))} – ${tf.format(Date(p.stop))}"
        val onAir = p.isOn(now)
        detailBar.visibility = if (onAir) View.VISIBLE else View.GONE
        detailBar.fraction = ((now - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f)
        detailLeft.text = when {
            onAir -> "${(p.stop - now) / 60_000} min left"
            p.start > now -> "in ${formatDelay(p.start - now)}"
            else -> "Ended"
        }
        detailDesc.text = p.description ?: ""
        // What's next only when there's no story to tell (the guide shows it beside this one anyway).
        val next = if (p.description.isNullOrBlank()) nextAfter(channel, p.stop) else null
        detailNext.text = next?.let { "Next: ${it.title} · ${tf.format(Date(it.start))}" } ?: ""
    }

    private fun showDetailsEmpty(text: String) {
        // Nothing focused: the clock's refresh mustn't bring back the previous scope's channel.
        detailChannelObj = null
        detailCell = null
        showDetailLogo(null)
        detailChannel.text = ""
        detailStar.visibility = View.INVISIBLE
        detailMeta.text = ""
        detailTitle.text = text
        detailTime.text = ""
        detailBar.visibility = View.GONE
        detailLeft.text = ""
        detailDesc.text = ""
        detailNext.text = ""
    }

    /**
     * [channel]'s logo before its name on the details' top line: the guide's, on the same plate; a logo still on its
     * way shows its plate already, so the name doesn't move when it lands. No logo: the name alone at the line's
     * start, moved there by a transform (the line keeps its height and nothing is laid out again as focus moves).
     */
    private fun showDetailLogo(channel: Channel?) {
        val url = channel?.logo
        val logo = url?.let { logos.get(it) }
        val plate = logo != null || (url != null && !logos.missing(url))
        detailLogo.set(logo, plate)
        detailChannel.translationX = if (plate) 0f else -detailNameInset.toFloat()
    }

    private fun formatDelay(ms: Long): String {
        val min = ms / 60_000
        return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
    }

    private fun nextAfter(channel: Channel, at: Long): Programme? = guide.programmesOf(channel).firstOrNull { it.start >= at }

    private fun pick(channel: Channel) {
        // The playing channel goes full screen; one that failed (or anything else) is tuned (again).
        if (channel.key.toString() == player.playingKey && player.status().error == null) {
            toFull()
        } else {
            tune(channel, from = shownScope)
        }
    }

    /**
     * Plays [channel]. [pressedAt] is when the key that asked for it went down (for the log of how long it took to
     * show): the last key here unless it's given, when that was a moment ago.
     */
    private fun tune(channel: Channel, known: Playlist? = null, pressedAt: Long = recentKey(), from: GuideScope? = null): Boolean {
        // Multiview's pictures have the connections while it shows: the one live player waits for it to close.
        if (multiview.isOpen) return false
        // Whatever a held key was heading for is overtaken.
        main.removeCallbacks(tunePending)
        zapPending = null
        val playlist = known ?: playlists.firstOrNull { it.id == channel.playlistId } ?: return false
        val result = player.tune(LiveSession.Source(channel.key.toString(), channel.url, playlist.id, playlist.maxConnections), pressedAt)
        if (result == LiveSession.Tune.NoConnection) {
            showNotice("This playlist's connection limit is reached")
            return false
        }
        prefs.lastChannel = channel.key
        playingChannel = channel
        overlay?.takeIf { it.isOpen && !it.picking }?.playingKey = channel.key
        noteZapGroup(channel, from)
        // A channel change from elsewhere closes a small window still open.
        popup.close()
        bg { db.noteRecent(channel.key, System.currentTimeMillis()) }
        if (started && !fullScreen) placeMini() else if (keeping && !started) host.keptChanged()
        return true
    }

    /**
     * The channel last watched plays again in the miniature when Live TV opens with nothing playing (provisional:
     * like TiviMate's "turn on last channel on app start", on by default here). Its playlist is read here too:
     * the page's own list may not have loaded yet.
     */
    /** The channel playing, kept here: it may not be in the scope the guide shows. */
    private var playingChannel: Channel? = null

    /** The last key's time when it went down within the last couple of seconds, else 0 (nothing was pressed for this). */
    private fun recentKey(): Long = keyAt.takeIf { it > 0 && android.os.SystemClock.uptimeMillis() - it < 2_000 } ?: 0L

    private fun resumeLast(key: ChannelKey) {
        bg {
            val ch = db.channel(key) ?: return@bg
            val pl = db.playlist(ch.playlistId) ?: return@bg
            // Timed from the tab's opening: that's the wait the viewer sees.
            main.post { if (started && player.currentKey == null) tune(ch, pl, pressedAt = openedAtForTune()) }
        }
    }

    /**
     * A channel picked outside this tab (Home's Recent channels): it is the channel watched last from now on, so the
     * tab's start plays it in the miniature; while the tab shows, it plays now unless it's playing already.
     */
    fun openChannel(key: ChannelKey) {
        prefs.lastChannel = key
        if (!started) return
        bg {
            val ch = db.channel(key) ?: return@bg
            val pl = db.playlist(ch.playlistId) ?: return@bg
            main.post { if (started && !released && player.currentKey != key.toString()) tune(ch, pl, pressedAt = openedAtForTune()) }
        }
    }

    /**
     * From Live TV search (the tab shown just before, with [key] remembered through [openChannel]): [key] plays, then
     * full screen once its picture comes ([full]: a channel, or a programme on now), else the guide shows it focused
     * (a programme still to come). Waits for the picture at most 20 s; another channel, or leaving the tab, stops the wait.
     */
    fun openFromSearch(key: ChannelKey, full: Boolean) {
        prefs.lastChannel = key
        if (!started) return
        bg {
            val ch = db.channel(key) ?: return@bg
            val pl = db.playlist(ch.playlistId) ?: return@bg
            main.post {
                if (!started || released) return@post
                if (player.currentKey != key.toString() && !tune(ch, pl, pressedAt = openedAtForTune())) return@post
                if (!full) { focusPlaying(); return@post }
                val until = android.os.SystemClock.uptimeMillis() + 20_000L
                val wait = object : Runnable {
                    override fun run() {
                        if (!started || released || fullScreen || player.currentKey != key.toString()) return
                        if (player.playingKey == key.toString()) toFull()
                        else if (android.os.SystemClock.uptimeMillis() < until) main.postDelayed(this, 150L)
                    }
                }
                wait.run()
            }
        }
    }

    private var startedAt = 0L
    private fun openedAtForTune(): Long = startedAt.takeIf { android.os.SystemClock.uptimeMillis() - it < 10_000 } ?: 0L

    private fun openSidebar(focus: Boolean) {
        val was = sidebarOpen
        sidebarOpen = true
        if (!was || sidebarPanel.visibility != View.VISIBLE) layoutFor(columnOpen = true, animate = true)
        if (focus) sidebar.focusCurrent()
    }

    private fun closeSidebar() {
        if (!sidebarOpen) return
        sidebarOpen = false
        layoutFor(columnOpen = false, animate = true)
    }

    /** The miniature in its slot; with [dx], where the slot will be once the content has slid that much further. */
    private fun placeMini(animateMs: Long = 0L, dx: Float = 0f) {
        // Never from a page that's away (a motion ending late): the picture may be on Home or Search now.
        if (!started) return
        val playing = player.currentKey != null
        if (playlists.isEmpty() || !playing) {
            placedAt = null
            host.placeVideo(null, 0f, false)
            showSlotPlaceholder(true)
            return
        }
        val r = rectOf(videoSlot) ?: return
        r.offset(dx, 0f)
        placedAt = r
        showSlotPlaceholder(false)
        host.placeVideo(r, t.dpf(LiveWidths.MINI_RADIUS), false, animateMs)
    }

    /** Whether the miniature's slot shows its placeholder panel (null: not decided yet, or the look changed). */
    private var placeholder: Boolean? = null

    private fun showSlotPlaceholder(on: Boolean) {
        if (placeholder == on) return
        placeholder = on
        if (on) Glass.background(videoSlot, Glass.Kind.PANEL) else videoSlot.background = null
        videoSlot.setTextColor(if (on) t.textMuted else 0)
    }

    private fun rectOf(v: View): RectF? {
        if (v.width == 0 || v.height == 0) return null
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        val base = IntArray(2)
        (root.rootView).getLocationInWindow(base)
        val x = (loc[0] - base[0]).toFloat()
        val y = (loc[1] - base[1]).toFloat()
        return RectF(x, y, x + v.width, y + v.height)
    }

    // ---- Full screen ----------------------------------------------------------------------------------------------

    fun toFull() {
        if (player.playingKey == null || fullScreen) return
        popup.close(fade = false, focusBack = false)
        fullScreen = true
        revealKey = 0
        focusBeforeFull = guide.focusedChannel()?.key
        zappedInFull = false
        main.removeCallbacks(idleToFull)
        // Paused in the guide: full screen opens with the controls up and Play selected (they stay while paused), so a
        // still picture always says it's paused.
        val paused = player.paused
        // Nothing in the hidden guide may keep or take focus: in full screen the keys come to dispatchKey.
        guideLayer.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        activity.currentFocus?.clearFocus()
        guideLayer.animate().cancel()
        host.setChromeVisible(false)
        placedAt = null
        refreshControls()
        // The channel and programme fade in as the picture nears full screen (not over the guide fading away).
        if (!paused) controls.showInfo(fadeInAfter = GROW_MS / 2)
        // The picture grows from the miniature to the whole screen while the guide fades away under it, from the next
        // frame (see onNextFrame). The guide fades as one picture (a layer drawn once), not drawn again into a new
        // screen-sized layer every frame.
        val serial = ++motionSerial
        onNextFrame {
            if (serial != motionSerial || !fullScreen || released) return@onNextFrame
            guideLayer.animate().alpha(0f).setDuration(GROW_MS * 3 / 4).setInterpolator(EASE).withLayer()
                .withEndAction { if (fullScreen) guideLayer.visibility = View.INVISIBLE }.start()
            host.placeVideo(null, 0f, true, GROW_MS)
        }
        main.removeCallbacks(hideControls)
        if (paused) {
            showControls()
            controls.focusPlay()
        } else {
            main.postDelayed(hideControls, INFO_MS)
        }
    }

    /**
     * Back to the guide from full screen. [toPlaying] (TV Guide): focus on the channel playing, on what's on now
     * ([focusPlaying]); otherwise (Back) where the guide was, unless the channel changed in full screen.
     */
    fun toGuide(toPlaying: Boolean = false) {
        if (!fullScreen) return
        overlay?.close(animate = false)
        dropZap()
        fullScreen = false
        revealKey = 0
        controls.hideAll()
        filmGlass(false)
        host.setChromeVisible(true)
        // Back to the grid: the column and rail stay away; the picture shrinks into the miniature as the guide fades in.
        sidebarOpen = false
        layoutFor(columnOpen = false, animate = false)
        guideLayer.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        guideLayer.animate().cancel()
        guideLayer.visibility = View.VISIBLE
        // The guide fades in and the picture shrinks into the miniature from the next frame (see onNextFrame). The
        // slot's place is known then (the content only slid back, a transform), unless the page hasn't been laid out
        // yet: then the shrink waits for the layout.
        // (Back in full screen by then: that leaves the guide hidden. A slide started meanwhile places the picture
        // again itself.)
        onNextFrame {
            if (fullScreen || released || !started) return@onNextFrame
            guideLayer.animate().alpha(1f).setDuration(SHRINK_MS).setInterpolator(EASE).withLayer().start()
            if (rectOf(videoSlot) == null) shrinkPending = true else placeMini(SHRINK_MS)
        }
        // TV Guide: on the channel playing. Back (or the playing channel is in another list, which takes a moment to
        // read): where the guide was, unless the channel changed in full screen: then to that channel.
        if (!(toPlaying && focusPlaying())) {
            val playing = ChannelKey.parse(player.currentKey)
            if (zappedInFull && playing != null) guide.focusChannel(playing)
            else focusBeforeFull?.let { k -> if (guide.focusedChannel()?.key != k) guide.focusChannel(k) }
        }
        guide.requestFocus()
        scheduleIdle()
    }

    /**
     * The guide's focus on the channel playing, on the programme on air (TV Guide in full screen); true when it's
     * there now. When the list the guide shows hasn't got it (a channel from Channel History, say), the guide shows its
     * group instead, else its playlist's All channels, with it focused there, once read; the groups column marks that
     * entry and stays as it was. A list the viewer asks for meanwhile wins (one asked for before this doesn't).
     */
    private fun focusPlaying(): Boolean {
        val key = ChannelKey.parse(player.currentKey) ?: return false
        if (guide.channelOf(key) != null) { guide.focusChannel(key); return true }
        val known = playingChannel?.takeIf { it.key == key }
        // The groups the column lists (a hidden group isn't one to show).
        val shown = groupNames.keys
        val serial = loadSerial
        // The list Channel ▲ / ▼ moves through first (the one the channel was started from), so the guide shows the
        // same order the channel buttons follow; a hidden group isn't one to show.
        val zapIn = zapScope?.takeIf { it !is GuideScope.Group || (it.playlistId to it.groupId) in shown }
        bg {
            val ch = known ?: db.channel(key) ?: return@bg
            val group = ch.groupId?.takeIf { (ch.playlistId to it) in shown }?.let { GuideScope.Group(ch.playlistId, it) }
            for (s in listOfNotNull(zapIn, group, GuideScope.AllChannels(ch.playlistId)).distinct()) {
                val (list, read) = listFor(s)
                if (!list.holds(key)) continue
                main.post {
                    if (serial != loadSerial || released || fullScreen || player.currentKey != key.toString()) return@post
                    // Any list still on its way is older than this: it's dropped (and no longer read).
                    ++loadSerial
                    listCancel?.cancel()
                    listCancel = null
                    scope = s
                    prefs.scope = s
                    sidebar.select(s)
                    showChannels(s, list, read, key, enterGrid = false)
                    guide.focusChannel(key)
                }
                return@bg
            }
        }
        return false
    }

    private fun refreshControls() {
        // The channel a held key has reached is named at once, before it's opened ([zap]).
        val pending = zapPending
        val key = pending?.key ?: ChannelKey.parse(player.currentKey) ?: return
        // The guide's copy first (fresh favorite state), else the channel as tuned: it may be outside this scope.
        val ch = pending ?: guide.channelOf(key) ?: playingChannel?.takeIf { it.key == key }
        if (ch == null) {
            bg {
                val found = db.channel(key) ?: return@bg
                main.post { if (!released && player.currentKey == key.toString()) { playingChannel = found; refreshControls() } }
            }
            return
        }
        val now = System.currentTimeMillis()
        val progs = guide.programmesOf(ch).ifEmpty { controlsProgrammes.takeIf { controlsProgrammesFor == ch.key }.orEmpty() }
        if (progs.isEmpty() && controlsProgrammesFor != ch.key) {
            // Not in the guide's loaded rows: read its programmes once for the controls.
            controlsProgrammesFor = ch.key
            bg {
                val list = db.programmes(listOf(ch), now - 2 * 60 * 60_000L, now + 6 * 60 * 60_000L)[ch.key].orEmpty()
                main.post { if (!released && controlsProgrammesFor == ch.key) { controlsProgrammes = list; refreshControls() } }
            }
        }
        val cur = progs.firstOrNull { it.isOn(now) }
        val nxt = progs.firstOrNull { it.start >= (cur?.stop ?: now) }
        val plName = playlists.firstOrNull { it.id == ch.playlistId }?.name ?: ""
        controlsLogo = ch.logo
        controls.setChannel(ch, cur, nxt, plName, ch.logo?.let { logos.get(it) }, now)
    }

    /** The logo the controls were last given (or are waiting for). */
    private var controlsLogo: String? = null
    private var controlsProgrammesFor: ChannelKey? = null
    private var controlsProgrammes: List<Programme> = emptyList()

    /** The channel a held Up/Down (or Channel) key has reached: named at once, opened when the key lets up ([zap]). */
    private var zapPending: Channel? = null

    private val tunePending = Runnable {
        val ch = zapPending ?: return@Runnable
        zapPending = null
        if (playsHere && fullScreen == zapPendingFull && tune(ch, from = zapScope)) zapped(ch) else refreshControls()
    }

    /** Whether the held key's [zapPending] was reached in full screen (else in the guide): opened only there. */
    private var zapPendingFull = false

    // ---- The playing channel's group (Channel ▲ / ▼ move within it, TUNING-1.md section 4) ------------------------

    /** The list the playing channel was started from (or last played from), and its channels in the guide's order. */
    private var zapScope: GuideScope? = null
    /** [zapScope]'s channels, read once and kept until the group or a playlist changes; null until read. */
    private var zapList: List<Channel>? = null
    /** [LiveDb.listChanges] as it stood when [zapList] was read. */
    private var zapLists = -1
    /** The channel whose group is being read now (else null), and how far presses meanwhile asked to go. */
    private var zapReading: ChannelKey? = null
    private var zapQueued = 0
    private var zapSerial = 0

    /**
     * [channel] plays, started from the guide's list [from] (null: from somewhere without a list). Its group is that
     * list; else the one it was last played from; else its own group in its playlist.
     */
    private fun noteZapGroup(channel: Channel, from: GuideScope?) {
        val list = zapList
        when {
            // Picked in the guide's list: that list is the group, already read.
            from != null && from == shownScope && guide.channelOf(channel.key) != null -> {
                zapScope = from; zapList = guide.channelList(); zapLists = shownLists; zapReading = null; zapQueued = 0; ++zapSerial
            }
            // Moving within the group already read.
            from != null && from == zapScope && list != null && list.holds(channel.key) -> Unit
            else -> { zapScope = null; zapList = null; resolveZap(channel, from) }
        }
        zapScope?.let { prefs.setZapGroupOf(channel.key, it) }
    }

    /** The group's list may have changed (a playlist read again, a favorite, a group hidden): it is read again. */
    private fun invalidateZap() {
        val ch = playingChannel?.takeIf { it.key.toString() == player.currentKey } ?: return
        // Nothing was written since it was read: it stands.
        if (zapList != null && zapLists == db.listChanges.get()) return
        zapList = null
        resolveZap(ch, zapScope)
    }

    /** Reads [channel]'s group off the key path: the first of [ZapOrder.candidates] that still holds it. */
    private fun resolveZap(channel: Channel, from: GuideScope?) {
        val serial = ++zapSerial
        zapReading = channel.key
        val last = prefs.zapGroupOf(channel.key)
        val key = channel.key
        bg {
            for (s in ZapOrder.candidates(from, last, key.playlistId, channel.groupId)) {
                // The guide's own copy when it has just read the same list.
                val (list, read) = runCatching { listFor(s) }.getOrNull() ?: continue
                if (!list.holds(key)) continue
                main.post {
                    if (serial != zapSerial || released) return@post
                    zapReading = null
                    zapScope = s
                    zapList = list
                    zapLists = read
                    prefs.setZapGroupOf(key, s)
                    // Presses that came while it was read now act, from the channel playing.
                    val q = zapQueued
                    zapQueued = 0
                    if (q != 0 && playsHere && player.currentKey == key.toString()) zap(q)
                }
                return@bg
            }
            main.post { if (serial == zapSerial) { zapReading = null; zapQueued = 0 } }
        }
    }

    /**
     * The next channel down the playing channel's group ([delta] +1) or the one before (-1), in the guide's order,
     * round at the ends. A press opens it at once. A [held] key runs on through the channels: each is named at once,
     * and only the one it stops on is opened (a stream opened for every channel passed would be thrown away again,
     * and providers count connections). Nothing playing: the guide's list, as before.
     */
    private fun zap(delta: Int, held: Boolean = false) {
        val playing = player.currentKey != null
        // Nothing playing: the guide's list, once it's whole (a long list's first screenful isn't the list).
        val list = if (playing) zapList else guide.channelList().takeIf { firstScreenOf == null }
        if (list == null) {
            // The group is still being read (just after a channel or playlist change): the press waits for it.
            if (zapReading != null) zapQueued += delta
            else playingChannel?.takeIf { it.key.toString() == player.currentKey }?.let { zapQueued += delta; resolveZap(it, zapScope) }
            return
        }
        val from = zapPending?.key ?: ChannelKey.parse(player.currentKey)
        val next = ZapOrder.next(list, from, delta) { it.key } ?: return
        Log.i(TAG, "Live TV: channel ${if (delta > 0) "up" else "down"} to ${next.number} in ${zapScope?.javaClass?.simpleName ?: "the guide's list"} (${list.size} channels)")
        main.removeCallbacks(tunePending)
        if (held) {
            zapPending = next
            zapPendingFull = fullScreen
            if (fullScreen) {
                refreshControls()
                showZapInfo()
            }
            main.postDelayed(tunePending, ZAP_SETTLE_MS)
            return
        }
        if (tune(next, from = if (playing) zapScope else shownScope)) zapped(next)
    }

    private fun zapped(next: Channel) {
        // In the guide its focus stays where the viewer is; the playing mark and the miniature follow by themselves.
        if (!fullScreen) return
        zappedInFull = true
        guide.focusChannel(next.key)
        refreshControls()
        // The controls up: they stay, showing the new channel, with their full time again.
        if (controls.controlsShown) bumpControls()
        showZapInfo()
    }

    /**
     * The remote's Channel ▲ / ▼ wherever live TV plays, with Remote › "Channel ▲▼" set to "Channel buttons only":
     * in the guide, in full screen with the controls up, on the rail and under a window, but never while a dialog
     * (the parental PIN, a question) or a text field has the keys. The one entry point: the activity calls it first
     * while the Live TV tab shows, and a live picture on another page can call it the same way. True when used.
     */
    fun channelKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_CHANNEL_UP && code != KeyEvent.KEYCODE_CHANNEL_DOWN) return false
        if (!playsHere || released || !prefs.channelButtonsOnly || player.currentKey == null || multiview.active) return false
        if (FireDialog.isShowing(activity) || activity.currentFocus is android.widget.EditText) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        keyAt = event.eventTime
        scheduleIdle()
        // The controls up stay up while the key is held (a held key's channel is opened only when it lets up).
        if (fullScreen && controls.controlsShown) bumpControls()
        zap(if (code == KeyEvent.KEYCODE_CHANNEL_UP) +1 else -1, event.repeatCount > 0)
        return true
    }

    /** The channel's name and programme at the top for a moment (unless the controls are up anyway). */
    private fun showZapInfo() {
        if (controls.controlsShown || overlay?.isOpen == true) return
        controls.showInfo()
        main.removeCallbacks(hideControls)
        main.postDelayed(hideControls, INFO_MS)
    }

    /** A held key's channel that wasn't opened yet is forgotten (full screen is left, or the tab). */
    private fun dropZap() {
        main.removeCallbacks(tunePending)
        zapPending = null
    }

    /** The hide timer found a dialog or window open over the controls and waited for it. */
    private var waitedForOverlay = false

    private val hideControls: Runnable = Runnable {
        if (!fullScreen) return@Runnable
        // A dialog or the settings window is open over the controls: wait for it rather than pulling focus out.
        if (FireDialog.isShowing(activity) || host.overlayOpen()) {
            waitedForOverlay = true
            main.postDelayed(hideControls, 2_000)
            return@Runnable
        }
        // It has closed since (focus is back on the button it came from): the controls get their full time again.
        if (waitedForOverlay) {
            waitedForOverlay = false
            if (controls.controlsShown) {
                bumpControls()
                return@Runnable
            }
        }
        hideControlsNow()
    }

    private fun hideControlsNow() {
        main.removeCallbacks(hideControls)
        waitedForOverlay = false
        val f = activity.currentFocus
        controls.hideAll()
        filmGlass(false)
        revealKey = 0
        if (f != null && isInside(f, controls)) f.clearFocus()
    }

    private fun isInside(v: View, parent: View): Boolean {
        var p: View? = v
        while (p != null) { if (p === parent) return true; p = p.parent as? View }
        return false
    }

    /** The key that showed the controls, while it's held: its repeats and release don't also move round them. */
    private var revealKey = 0

    private fun showControls(focus: View? = null) {
        overlay?.takeIf { !it.picking }?.close(animate = false)
        refreshControls()
        more.refreshValues()
        filmGlass(true)
        controls.showControls(focus)
        bumpControls()
    }

    private var filmGlassOn = false

    /** Full-screen controls blur the film behind them (glass look); everywhere else glass blurs the wallpaper. */
    private fun filmGlass(on: Boolean) {
        if (filmGlassOn == on) return
        filmGlassOn = on
        host.setFilmGlass(on)
        if (Glass.on) controls.applyLook()
    }

    private fun bumpControls() {
        main.removeCallbacks(hideControls)
        waitedForOverlay = false
        // Paused, the controls stay up (the picture is still and Play is what's wanted next).
        if (player.paused) return
        val s = prefs.controlsHideSec
        if (s > 0) main.postDelayed(hideControls, s * 1000L)
    }

    /** Play/pause (the remote's key or the Play button): the controls show, with Play selected while paused. */
    private fun togglePause() {
        if (!fullScreen) return
        player.togglePause()
        controls.setStatus(player.status())
        if (!controls.controlsShown) showControls() else bumpControls()
        if (player.paused) controls.focusPlay()
    }

    private fun goLive() {
        player.goLive()
        controls.setStatus(player.status())
        bumpControls()
    }

    private fun showHistory() {
        bg {
            val recent = db.recent().filter { it.key.toString() != player.currentKey }.take(HISTORY_SIZE)
            main.post {
                if (recent.isEmpty()) { showNotice("No other channels watched yet"); return@post }
                FireDialog.choose(activity, "Channel History", recent.map { "${it.number}  ${it.name}" }, -1) { i ->
                    recent.getOrNull(i)?.let { ch -> if (tune(ch)) { zappedInFull = true; refreshControls(); more.refreshValues() } }
                }
            }
        }
    }

    /** Captions: a side window like Audio's ([LiveMore.openCaptions]); a channel without any says so. */
    private fun chooseCaptions(from: View) {
        if (player.status().captions.isEmpty()) { showNotice("This channel has no captions"); return }
        more.openCaptions(from)
    }

    /**
     * Stars or unstars [target] (the playing channel when null): the full-screen Favorite button, or the star in a
     * channel's small window ([notice] false there: the star filling is the answer). Returns the new state, or null
     * when there's no such channel.
     */
    private fun toggleFavorite(target: Channel? = null, notice: Boolean = true): Boolean? {
        val key = target?.key ?: ChannelKey.parse(player.currentKey) ?: return null
        val ch = guide.channelOf(key) ?: playingChannel?.takeIf { it.key == key } ?: target ?: return null
        val fav = !ch.favorite
        // Shown at once (the star fills, the guide row and the details follow); stored behind it. The database can be
        // busy for a while with a guide update, which must not hold up the button. Only that row changes in the guide.
        guide.updateChannel(ch.copy(favorite = fav))
        if (key.toString() == player.currentKey) playingChannel = ch.copy(favorite = fav)
        refreshControls()
        if (notice) showNotice(if (fav) "Added to Favorites" else "Removed from Favorites")
        val s = scope
        writes.execute {
            runCatching { db.setFavorite(key, fav, System.currentTimeMillis()) }
                .onFailure { Log.w(TAG, "Favorite not stored: ${it.javaClass.simpleName}") }
            main.post {
                if (released) return@post
                // The playing group may be Favorites, or hold a copy of this channel: read again.
                invalidateZap()
                if (s is GuideScope.Favorites && scope == s || loadingScope != null) {
                    // The Favorites list gains or loses the row; a list read before the write is read again. Focus stays
                    // where it was, or on the row next to a removed one.
                    val rows = guide.channelList()
                    val cur = guide.focusedChannel()?.key
                    val i = rows.indexOfFirst { it.key == cur }
                    val keep = if (cur == key && !fav && scope is GuideScope.Favorites) (rows.getOrNull(i + 1) ?: rows.getOrNull(i - 1))?.key else cur
                    loadChannels(focus = keep, enterGrid = false)
                } else {
                    // Already shown; applied again in case a list arrived in between.
                    guide.channelOf(key)?.let { guide.updateChannel(it.copy(favorite = fav)) }
                    playingChannel = playingChannel?.let { if (it.key == key) it.copy(favorite = fav) else it }
                    refreshControls()
                }
            }
        }
        return fav
    }

    // ---- Small windows (Center held) ------------------------------------------------------------------------------

    /** Center held on [channel] in the guide: its star (selected), its full name and Assign favorites, beside its row. */
    private fun openChannelPopup(channel: Channel) {
        if (fullScreen || popup.isOpen) return
        val row = RectF()
        if (!guide.focusedRowRect(row)) return
        popupChannel = channel
        popupFocusBack = { if (!guide.requestFocus()) takeFocus() }
        // Beside the channel's name, over its programmes, inside the guide.
        val area = inRoot(guide, RectF(0f, 0f, guide.width.toFloat(), guide.height.toFloat()))
        // The guide runs down to the screen's edge: the window keeps a margin from it.
        area.intersect(0f, 0f, root.width.toFloat(), root.height - gap.toFloat())
        popup.open(channel.name, listOf(starButton(channel.favorite), HoldPopup.Button("Assign favorites") { popupAssign() }), inRoot(guide, row), area)
    }

    private fun starButton(favorite: Boolean) = HoldPopup.Button(
        if (favorite) "Remove from Favorites" else "Add to Favorites",
        icon = if (favorite) app.fireplayer.R.drawable.ic_star_filled else app.fireplayer.R.drawable.ic_star,
        selected = favorite,
    ) { popupStar() }

    /** OK on the star: the favorite flips at once (with a small pop), and the window closes by itself a moment later. */
    private fun popupStar() {
        val ch = popupChannel ?: return
        val fav = toggleFavorite(ch, notice = false) ?: return
        popupChannel = ch.copy(favorite = fav)
        popup.update(0, starButton(fav))
        popup.pop(0)
        popup.closeAfter(STAR_CLOSE_MS)
    }

    /** Assign favorites: the window gives way to Assign favorites on this channel's group, the channel selected. */
    private fun popupAssign() {
        val ch = popupChannel ?: return
        // Gone at once (the guide dims under the settings window), focus back on the cell for when that closes.
        popup.close(fade = false)
        host.openAssignFavorites(ch.playlistId, ch.groupId, ch.key)
    }

    /**
     * Center held on a group in the column: its full name, Move to top (selected) and Hide category, beside its row, and
     * Manage groups under them.
     */
    private fun openGroupPopup(scope: GuideScope.Group, name: String, row: View) {
        if (fullScreen || popup.isOpen || row.width == 0) return
        popupFocusBack = { sidebar.focusScope(scope) }
        val m = gap.toFloat()
        val area = RectF(m, m, root.width - m, root.height - m)
        popup.open(name, listOf(
            HoldPopup.Button("Move to top") { moveGroupToTop(scope) },
            HoldPopup.Button("Hide category") { hideGroup(scope) },
        ), inRoot(row, RectF(0f, 0f, row.width.toFloat(), row.height.toFloat())), area,
            under = HoldPopup.Button("Manage groups") { popupManage(scope) })
    }

    /** Manage groups: the window gives way to Manage groups for the group's playlist, that group's row selected. */
    private fun popupManage(scope: GuideScope.Group) {
        popup.close(fade = false)
        host.openManageGroups(scope.playlistId, scope.groupId)
    }

    /**
     * Move to top: the group becomes the first of its playlist's groups (the order Manage groups › Reorder groups
     * keeps), shown in the column at once with focus following it; stored behind.
     */
    private fun moveGroupToTop(scope: GuideScope.Group) {
        val moved = sidebar.moveGroupToTop(scope.playlistId, scope.groupId)
        popup.close()
        if (!moved) return
        writes.execute {
            // The shown groups in the order stored now, this one first (hidden ones keep theirs).
            val stored = runCatching {
                db.setGroupOrder(scope.playlistId, GroupEdits.toTop(db.groups(scope.playlistId).map { it.id }, scope.groupId))
            }.onFailure { Log.w(TAG, "Group order not stored: ${it.javaClass.simpleName}") }.isSuccess
            main.post { if (!released) announce(scope.playlistId, stored) }
        }
    }

    /**
     * Hide category: the group leaves the column at once and focus goes to the row in its place; the guide showing it
     * shows that row's channels instead. Stored behind; Manage groups shows it again.
     */
    private fun hideGroup(scope: GuideScope.Group) {
        val landing = sidebar.hideGroup(scope.playlistId, scope.groupId)
        if (landing != null) {
            popupFocusBack = { sidebar.focusScope(landing) }
            // No longer one of the column's groups: TV Guide mustn't bring it back as the guide's list (focusPlaying).
            // This page skips its own reload for this change; if the change isn't stored, the reload puts it back.
            groupNames = groupNames - (scope.playlistId to scope.groupId)
        }
        popup.close()
        if (landing == null) return
        showNotice("Hidden. Show it again in Manage groups.")
        writes.execute {
            val stored = runCatching { db.setGroupHidden(scope.playlistId, scope.groupId, true) }
                .onFailure { Log.w(TAG, "Group not hidden: ${it.javaClass.simpleName}") }.isSuccess
            main.post {
                if (released) return@post
                announce(scope.playlistId, stored)
                // Lists that held its channels lose them (one read before this was stored may still have them).
                val s = this.scope
                if (stored && (s == GuideScope.AllPlaylists || s == GuideScope.AllChannels(scope.playlistId))) {
                    loadChannels(focus = guide.focusedChannel()?.key, enterGrid = false)
                }
            }
        }
    }

    /**
     * Tells other open views (the settings windows) about a change made here. This page has it on screen already, so
     * its own listener lets it pass, unless it wasn't stored: then the page reads what is.
     */
    private fun announce(playlistId: String, stored: Boolean) {
        if (stored && started) ownChanges++
        // A group moved or hidden: the playing group's order is read again.
        invalidateZap()
        sync.changed(playlistId)
    }

    /** [r], in [v]'s coordinates, in the page's own (where the small window is placed). */
    private fun inRoot(v: View, r: RectF): RectF {
        val a = IntArray(2)
        v.getLocationInWindow(a)
        val b = IntArray(2)
        root.getLocationInWindow(b)
        return RectF(r).apply { offset((a[0] - b[0]).toFloat(), (a[1] - b[1]).toFloat()) }
    }

    /**
     * A window opened from the extra row closed: focus goes back to its button (the controls stayed up while it was
     * open), and the controls' timer starts again, as in the VOD player.
     */
    private fun backFromWindow(from: View) {
        if (released || !fullScreen) return
        if (controls.controlsShown) {
            from.requestFocus()
            more.refreshValues()
            bumpControls()
        } else {
            showControls(from)
        }
    }

    /** The programme on the playing channel now, from the guide (as the controls show it), or null. */
    private fun programmeNow(): Programme? {
        val key = ChannelKey.parse(player.currentKey) ?: return null
        val ch = guide.channelOf(key) ?: playingChannel?.takeIf { it.key == key } ?: return null
        val now = System.currentTimeMillis()
        val progs = guide.programmesOf(ch).ifEmpty { controlsProgrammes.takeIf { controlsProgrammesFor == ch.key }.orEmpty() }
        return progs.firstOrNull { it.isOn(now) }
    }

    /**
     * The sleep timer ran out: live playback stops (so nothing keeps streaming or holds the provider's connection),
     * full screen goes back to the guide, and a note says why.
     */
    private fun stopForSleep() {
        if (released) return
        // Kept playing on Home or Search: the picture there closes, with a word to say why.
        if (keeping && !started) {
            host.keptSleep()
            return
        }
        // Multiview: every picture stops, and full screen goes back to the guide. (Its picker over full screen just
        // closes: the channel playing stops below, as usual.)
        val wasMultiview = multiview.isOpen
        endMultiview()
        if (wasMultiview) {
            showNotice("Sleep timer: Live TV stopped")
            return
        }
        // Nothing playing any more (a film started meanwhile, say): nothing to stop or say.
        if (player.currentKey == null) return
        if (host.sidePanel.isOpen) host.sidePanel.close()
        player.stop()
        if (fullScreen) toGuide() else placeMini()
        showNotice("Sleep timer: Live TV stopped")
    }

    private fun showNotice(text: String) {
        // Kept playing on Home or Search: said on that page (this one is hidden).
        if (keeping && !started) {
            host.keptNotice(text)
            return
        }
        notice.text = text
        notice.visibility = View.VISIBLE
        main.removeCallbacks(hideNotice)
        main.postDelayed(hideNotice, 3_500)
    }

    private val hideNotice = Runnable { notice.visibility = View.GONE }

    // ---- Idle and clock -------------------------------------------------------------------------------------------

    private val idleToFull = Runnable {
        if (!started || fullScreen || !root.isShown) return@Runnable
        // Not while the viewer is in a dialog, the settings window, a small window or on the rail: try again later.
        if (FireDialog.isShowing(activity) || host.overlayOpen() || popup.isOpen || host.railHasFocus()) { scheduleIdle(); return@Runnable }
        // Not onto a channel that couldn't be played.
        if (player.status().error != null) return@Runnable
        // Not onto a paused channel's still picture: tried again later (it may carry on by itself).
        if (player.paused) { scheduleIdle(); return@Runnable }
        toFull()
    }

    private fun scheduleIdle() {
        main.removeCallbacks(idleToFull)
        val s = prefs.fullScreenAfterSec
        if (s > 0 && !fullScreen && started) main.postDelayed(idleToFull, s * 1000L)
    }

    private val clockTick = object : Runnable {
        override fun run() {
            if (fullScreen && controls.visibility == View.VISIBLE && controls.tick(System.currentTimeMillis())) refreshControls()
            detailChannelObj?.let { showDetails(it, guide.focusedCell()) }
            main.postDelayed(this, 15_000)
        }
    }

    // ---- Keys -----------------------------------------------------------------------------------------------------

    /** The activity hands every key here first while this tab shows. True when used. */
    fun dispatchKey(event: KeyEvent): Boolean {
        if (FireDialog.isShowing(activity)) return false
        // Multiview (or its channel picker) has the keys while it shows.
        if (multiview.active) return multiview.dispatchKey(event)
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down) {
            scheduleIdle()
            keyAt = event.eventTime
        }
        val code = event.keyCode

        // The channel list over full screen has the keys while it shows. What it leaves: Play/Pause and the channel
        // buttons act as in full screen; the Guide key closes it on the way to the guide; the rest wait.
        overlay?.let { list ->
            if (list.isOpen && !list.picking && fullScreen) {
                if (list.dispatchKey(event)) return true
                when (code) {
                    KeyEvent.KEYCODE_GUIDE -> list.close(animate = false)
                    KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> Unit
                    in PLAY_PAUSE_KEYS -> Unit
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> return true
                    else -> return false
                }
            }
        }

        if (code == KeyEvent.KEYCODE_BACK) return back(event)

        // Play/Pause in the guide: the miniature's channel pauses or carries on, as in full screen, with a word to say so.
        // Never left unused: Android would hand it to the last app that played something (a paused Stremio film,
        // seen on the Fire TV), which would start playing behind this page.
        if (!fullScreen && code in PLAY_PAUSE_KEYS) {
            if (down && event.repeatCount == 0 && player.playingKey != null && player.status().error == null && pauseWanted(code)) {
                pauseQuietly()
            }
            return true
        }

        // Menu over a small window: it closes first (the selection back where it was), then Settings opens as usual.
        if (code == KeyEvent.KEYCODE_MENU && down && popup.isOpen) popup.close(fade = false)

        if (!fullScreen) {
            // Channel +/- in the guide page through channels.
            return false
        }

        if (code in PLAY_PAUSE_KEYS) {
            if (down && event.repeatCount == 0) {
                if (pauseWanted(code)) togglePause()
                else if (!controls.controlsShown) showControls()
            }
            return true
        }

        // The remote's Guide key: back to the guide on the channel playing, as the TV Guide button (controls up or not).
        if (code == KeyEvent.KEYCODE_GUIDE) {
            if (down && event.repeatCount == 0) toGuide(toPlaying = true)
            return true
        }

        if (controls.controlsShown) {
            // The key that showed the controls doesn't also act on them while it's held (design-layout).
            if (code == revealKey) {
                if (!down) revealKey = 0
                return true
            }
            if (down) bumpControls()
            when (code) {
                // Down from the buttons: the extra row under the chevron, as in the VOD player; Up brings them back.
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (down && !controls.moreShown) controls.showMoreRow()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> if (controls.moreShown) {
                    if (down && event.repeatCount == 0) controls.showMainRow()
                    return true
                }
                // Along the extra row, left from its first button goes round to the last and right from the last to
                // the first.
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (down && controls.wrapMore(code == KeyEvent.KEYCODE_DPAD_LEFT, event.repeatCount == 0)) return true
            }
            return false
        }

        // Full screen, controls hidden.
        if (!down) return code in HANDLED_UP
        val channelButtonsOnly = prefs.channelButtonsOnly
        when (code) {
            // Up (and Channel +) goes on down the guide's list to the next channel, Down (Channel -) back to the one
            // before (the user's choice), round at the ends.
            KeyEvent.KEYCODE_CHANNEL_UP -> zap(+1, event.repeatCount > 0)
            KeyEvent.KEYCODE_CHANNEL_DOWN -> zap(-1, event.repeatCount > 0)
            KeyEvent.KEYCODE_DPAD_UP -> if (channelButtonsOnly) reveal(code, event) else zap(+1, event.repeatCount > 0)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (channelButtonsOnly) reveal(code, event) else zap(-1, event.repeatCount > 0)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> reveal(code, event)
            // Left: the channel list over the picture (the playing channel's group). Right: the channel's name and
            // programme a moment, as before.
            KeyEvent.KEYCODE_DPAD_LEFT -> if (event.repeatCount == 0) openList()
            KeyEvent.KEYCODE_DPAD_RIGHT -> controls.showInfo().also { main.removeCallbacks(hideControls); main.postDelayed(hideControls, INFO_MS) }
            else -> return false
        }
        return true
    }

    /**
     * Play/Pause while a settings window, a small window or the rail has the keys ([dispatchKey] isn't asked then):
     * the channel still pauses or carries on, as the VOD player does, with focus left where it is. True for those
     * keys, used or not (left to Android, they'd go to the last app that played something).
     */
    fun mediaKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code !in PLAY_PAUSE_KEYS) return false
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return true
        if (player.playingKey == null || player.status().error != null || !pauseWanted(code)) return true
        if (fullScreen && !FireDialog.isShowing(activity) && !host.overlayOpen()) togglePause() else pauseQuietly()
        return true
    }

    /** Whether [code] pauses or carries on now (Pause while paused, or Play while playing, does nothing). */
    private fun pauseWanted(code: Int): Boolean {
        val wantsPause = code == KeyEvent.KEYCODE_MEDIA_PAUSE
        val wantsPlay = code == KeyEvent.KEYCODE_MEDIA_PLAY
        return (wantsPause && !player.paused) || (wantsPlay && player.paused) || (!wantsPause && !wantsPlay)
    }

    /** Pauses or carries on with a word to say so, the controls and focus left as they are. */
    private fun pauseQuietly() {
        player.togglePause()
        controls.setStatus(player.status())
        showNotice(if (player.paused) "Paused" else "Playing")
    }

    private fun reveal(code: Int, event: KeyEvent) {
        if (event.repeatCount != 0) return
        revealKey = code
        showControls()
    }

    /** A Back press this page took the DOWN of: only its UP acts (a stray UP after a dialog closed doesn't). */
    private var backDownSeen = false
    /** A Back press that closed a small window: held or released, it does nothing more (no full screen). */
    private var backClosedPopup = false

    private fun back(event: KeyEvent): Boolean {
        if (popup.isOpen || backClosedPopup) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                popup.close()
                backClosedPopup = true
            } else {
                backClosedPopup = false
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            // Held Back in the guide: the playing channel goes full screen (design-layout).
            if (!fullScreen && event.repeatCount >= 1 && !backLongPressed && player.playingKey != null && player.status().error == null) {
                backLongPressed = true
                backDownSeen = false
                toFull()
                return true
            }
            if (event.repeatCount == 0) backDownSeen = fullScreen || guide.hasFocus()
            return backDownSeen || backLongPressed
        }
        // ACTION_UP
        if (backLongPressed) { backLongPressed = false; return true }
        if (!backDownSeen) return false
        backDownSeen = false
        if (fullScreen) {
            if (controls.controlsShown) hideControlsNow() else toGuide()
            return true
        }
        if (guide.hasFocus()) { openSidebar(focus = true); return true }
        return false
    }

    val isFullScreen get() = fullScreen

    /** Multiview (or its channel picker) is showing. */
    val multiviewActive get() = multiview.active

    // ---- Multiview ------------------------------------------------------------------------------------------------

    /**
     * Full screen's Multiview button: the channel playing will be Multiview's first picture; the channel picker opens
     * for the second (Back from it: the controls again, on this button).
     */
    private fun startMultiview(from: View) {
        val key = ChannelKey.parse(player.currentKey) ?: return
        val ch = guide.channelOf(key) ?: playingChannel?.takeIf { it.key == key }
        val pl = ch?.let { c -> playlists.firstOrNull { it.id == c.playlistId } }
        if (ch == null || pl == null) {
            showNotice("The channel is still being read: try Multiview again in a moment")
            return
        }
        multiviewFrom = from
        hideControlsNow()
        multiview.begin(ch, pl)
    }

    /** Multiview opens: the one live player stops (its connection is the first picture's now) and steps aside. */
    private fun enterMultiview() {
        dropZap()
        hideControlsNow()
        multiviewFrom = null
        player.stop()
        host.setMultiview(true)
    }

    /** Multiview closed on [channel]: it plays full screen in the one live player, its name shown a moment. */
    private fun leaveMultiview(channel: Channel, playlist: Playlist) {
        host.setMultiview(false)
        if (released) return
        if (!fullScreen) return
        host.placeVideo(null, 0f, true)
        if (tune(channel, playlist)) {
            // Back from here: the guide on this channel.
            zappedInFull = true
            refreshControls()
            showZapInfo()
        } else {
            toGuide()
        }
    }

    /**
     * Multiview closes with nothing playing after it (the sleep timer ran out, a film is starting): every picture's
     * player and connection go, and full screen goes back to the guide.
     */
    fun endMultiview() {
        if (!multiview.active) return
        val wasOpen = multiview.isOpen
        multiview.end()
        multiviewFrom = null
        if (!wasOpen) return
        host.setMultiview(false)
        if (fullScreen && !released) toGuide()
    }

    /** The Info pods setting changed: the full-screen controls show the pods it now lists. */
    fun refreshPods() = controls.setStatus(player.status())

    companion object {
        private const val TAG = "FirePlayerLive"
        private const val INFO_MS = 4_000L
        /** A held zap key's channel is opened once no repeat has come for this long (repeats come about every 50 ms). */
        private const val ZAP_SETTLE_MS = 250L
        /** Channel History: the last ten channels watched before the one playing (design-layout.md). */
        internal const val HISTORY_SIZE = 10
        /**
         * A long list's first screenful: this many channels before the one it opens on and this many from it on, as
         * many rows as the guide asks listings for around its eight (two screens above, three below).
         */
        private const val FIRST_BEFORE = 16
        private const val FIRST_AFTER = 24
        /** A stage of opening the tab is logged as such only this soon after it. */
        private const val OPENING_MS = 30_000L
        /** Column and rail sliding in and out. */
        private const val SLIDE_MS = 220L
        /** The miniature growing to full screen, and shrinking back. */
        private const val GROW_MS = 280L
        private const val SHRINK_MS = 240L
        /** A channel's small window stays this long after its star was pressed, so the star is seen to change. */
        private const val STAR_CLOSE_MS = 450L
        private val EASE = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
        private val PLAY_PAUSE_KEYS = setOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE)
        private val HANDLED_UP = setOf(
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_GUIDE,
        )
    }
}

/** A small rounded progress line (the details panel's programme progress). */
class ProgressLine(context: Context, private val t: Tokens) : View(context) {
    var fraction = 0f
        set(v) { field = v; invalidate() }
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(canvas: android.graphics.Canvas) {
        // A blue bar on a light track (the concepts).
        val h = t.dpf(5f)
        val cy = height / 2f
        r.set(0f, cy - h / 2, width.toFloat(), cy + h / 2)
        p.color = 0x66FFFFFF
        canvas.drawRoundRect(r, h / 2, h / 2, p)
        r.right = (width * fraction).coerceAtLeast(h)
        p.color = t.accent
        canvas.drawRoundRect(r, h / 2, h / 2, p)
    }
}

/** The Live TV tab's own remembered state and its provisional settings (separate file: nothing existing changes). */
class LivePrefs(context: Context) {
    private val sp = context.getSharedPreferences("fireplayer_live", Context.MODE_PRIVATE)

    companion object {
        /** [playLastOnOpening]'s row in Settings › Live TV, as its category lists it. */
        const val PLAY_LAST_ON_OPENING = "Play the last channel on opening"
    }

    var scope: GuideScope?
        get() = GuideScope.decode(sp.getString("scope", null))
        set(v) = sp.edit().putString("scope", v?.encode()).apply()

    var focus: ChannelKey?
        get() = ChannelKey.parse(sp.getString("focus", null))
        set(v) = sp.edit().putString("focus", v?.toString()).apply()

    var lastChannel: ChannelKey?
        get() = ChannelKey.parse(sp.getString("last_channel", null))
        set(v) = sp.edit().putString("last_channel", v?.toString()).apply()

    /**
     * The list (group) [key] was last played from, for Channel ▲ / ▼: kept per channel, so the last channel's group
     * holds after a restart and a channel started from Home's Recent channels goes on in the group it was played from.
     */
    fun zapGroupOf(key: ChannelKey): GuideScope? = GuideScope.decode(sp.getString("zap_group:$key", null))

    fun setZapGroupOf(key: ChannelKey, scope: GuideScope) {
        val v = scope.encode()
        if (sp.getString("zap_group:$key", null) != v) sp.edit().putString("zap_group:$key", v).apply()
    }

    /** Remote › "Channel ▲▼": false = D-pad + channel buttons (default), true = Channel buttons only. */
    var channelButtonsOnly: Boolean
        get() = sp.getBoolean("channel_buttons_only", false)
        set(v) = sp.edit().putBoolean("channel_buttons_only", v).apply()

    /** Seconds the full-screen controls stay without a key press; 0 = until Back ("Panels timeout"). Default 6 s. */
    var controlsHideSec: Int
        get() = sp.getInt("controls_hide_s", 6)
        set(v) = sp.edit().putInt("controls_hide_s", v).apply()

    /** Seconds without a key press before the guide's miniature goes full screen; 0 = never. Default 1 minute (user). */
    var fullScreenAfterSec: Int
        get() = sp.getInt("full_after_s", 60)
        set(v) = sp.edit().putInt("full_after_s", v).apply()

    /** Live FirePlayer Settings › Update interval: how often the EPGs are read again (hours; default 24). */
    var epgUpdateHours: Int
        get() = sp.getInt("epg_update_h", 24)
        set(v) = sp.edit().putInt("epg_update_h", v).apply()

    /**
     * Play the last channel on opening (Settings › Live TV): opening Live TV with nothing playing starts the channel
     * watched last in the small picture. On by default, as it always did; off, it opens on the guide alone (no
     * stream, no sound, the provider's connection left free) until a channel is picked.
     */
    var playLastOnOpening: Boolean
        get() = sp.getBoolean("play_last_on_opening", true)
        set(v) = sp.edit().putBoolean("play_last_on_opening", v).apply()

    /** Live TV buffer: how much of a channel is loaded ahead. Default Medium, the buffer live TV always had. */
    var liveBuffer: app.fireplayer.player.LiveBuffer
        get() = app.fireplayer.player.LiveBuffer.named(sp.getString("live_buffer", null))
        set(v) = sp.edit().putString("live_buffer", v.name).apply()

    /**
     * Settings › Live TV › Multiview pictures: the most shown at once, 2 to 4 (default 4, the user's design). Fewer
     * suits a TV whose video decoders run out (a picture that can't get one says so).
     */
    var multiviewMost: Int
        get() = MultiviewRules.most(sp.getInt("multiview_most", MultiviewRules.MOST))
        set(v) = sp.edit().putInt("multiview_most", MultiviewRules.most(v)).apply()

    /**
     * Settings › Live TV › Multiview with 3 or 4: in a 2 x 2 grid (false, default, the user's design) or with the
     * newest picture large on top and the others in a row under it (true). OK on a picture switches either way.
     */
    var multiviewLarge: Boolean
        get() = sp.getBoolean("multiview_large", false)
        set(v) = sp.edit().putBoolean("multiview_large", v).apply()
}
