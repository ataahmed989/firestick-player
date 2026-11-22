package app.fireplayer

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.ui.SubtitleView
import app.fireplayer.data.Dimmer
import app.fireplayer.data.Prefs
import app.fireplayer.live.ChannelKey
import app.fireplayer.live.LiveSync
import app.fireplayer.live.ui.LivePage
import app.fireplayer.live.ui.PipRules
import app.fireplayer.live.ui.LivePicture
import app.fireplayer.live.ui.LiveSettings
import app.fireplayer.playback.PlayerHost
import app.fireplayer.player.PausedRoom
import app.fireplayer.player.PictureFit
import app.fireplayer.shell.NavRail
import app.fireplayer.shell.Tokens
import app.fireplayer.shell.WallpaperView
import app.fireplayer.subs.SubtitlePresets
import app.fireplayer.subs.SubtitleStyler
import app.fireplayer.titles.TitleService
import app.fireplayer.ui.FireDialog
import app.fireplayer.ui.Glass
import app.fireplayer.ui.PageRow
import app.fireplayer.ui.GlassBackdrop
import app.fireplayer.ui.GlassSource
import app.fireplayer.ui.ImageLoader
import app.fireplayer.ui.Rows
import app.fireplayer.ui.SettingsCategory
import app.fireplayer.ui.SettingsPanels
import app.fireplayer.ui.SidePanel
import app.fireplayer.vod.ui.SearchKind
import app.fireplayer.update.Updater
import app.fireplayer.vod.PlayHandoff
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.play.FilmMode
import app.fireplayer.vod.play.VodLaunch
import app.fireplayer.vod.data.Brand
import app.fireplayer.vod.data.Service
import app.fireplayer.vod.data.Services
import app.fireplayer.vod.ui.VodPage
import app.fireplayer.vod.ui.VodPages

/**
 * What opens from the Fire TV home screen: the app shell. A tab rail (one FirePlayer mark; only tabs that work are
 * shown) and its pages: Home, Movies and TV Shows (films and shows, [VodPages]), Live TV (guide, miniature and
 * full-screen player) and Settings: every setting in its category (Playlists & EPG, Live TV, Playback, Remote &
 * skipping, Subtitles & audio, Add-ons, This TV, …), each category its own window, and Open a link, as the old home
 * menu had. It opens on Home (design-brief.md §6.3); Live TV is one press away on the rail.
 *
 * A film or episode started from Home, Movies or TV Shows runs in film mode first ([FilmMode], §6.6): Live TV stops and
 * releases its decoder, and once the player covers this screen its pictures, glass frames and guide data go; coming
 * back shows a snapshot at once while the posters return from the disk cache. In the background for other reasons the
 * same release waits a while.
 *
 * Both looks share this layout, each over its own background (the user's): glass, the blurred beach wallpaper, with
 * every surface the one dark translucent glass of [app.fireplayer.ui.GlassStyle]; classic, the original dark solid
 * surfaces over the dark fog. A glass surface in the page is the tint over what's behind it; a glass window over the
 * page (the settings window, a hold window, a dialog, the widened rail) shows the page behind it softly blurred, from
 * one small shared picture ([pageGlass]) copied when a window appears and when the page under one changes, never while
 * nothing shows it. Full-screen Live TV's glass shows the live picture, as the film player's does ([glassSource]).
 */
class HomeActivity : AppCompatActivity(), GlassSource.Host, LivePage.Host, VodPage.Host {

    private var still: GlassSource? = null
    /** The live picture, blurred, for the full-screen controls' glass (Android 7+); made when first needed. */
    private var film: GlassBackdrop? = null
    private var filmOn = false
    /** When [play] last handed a film to the player (elapsedRealtime ms); cleared once this screen shows again. */
    private var launchedAt = Long.MIN_VALUE / 2
    override val glassSource: GlassSource? get() = if (filmOn) film ?: still else still
    /** What glass windows over the pages show: the wallpaper and the page behind them, blurred ([PageGlass]). */
    private var behind: app.fireplayer.ui.PageGlass? = null
    override val pageGlass: GlassSource? get() = behind
    private lateinit var shellTokens: Tokens
    override val tokens: Tokens get() = shellTokens

    private lateinit var settingsPanels: SettingsPanels
    private lateinit var panel: SidePanel
    private lateinit var liveSettings: LiveSettings
    private lateinit var root: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var captions: SubtitleView
    private val captionStyler by lazy { SubtitleStyler(this) }
    private lateinit var wallpaper: WallpaperView
    private lateinit var pages: FrameLayout
    private lateinit var rail: NavRail
    private lateinit var live: LivePage
    /** Home, Movies and TV Shows. */
    private lateinit var vodPages: VodPages
    private lateinit var settingsPage: LinearLayout
    private var consumeBackUp = false
    private var lookOn = false
    private var videoTarget: RectF? = null
    private var videoFull = false
    private var videoRadius = 0f
    /** Darkens the full-screen live picture (Screen dimmer), under the controls. */
    private lateinit var liveDimmer: View
    /**
     * The buffering symbol over the live picture, miniature and full screen (under the page, so the guide and the
     * controls draw over it), and when it shows ([app.fireplayer.ui.BusySigns]: after about 300 ms, gone the moment
     * frames play; "Reconnecting…" under it after a few seconds of that).
     */
    private lateinit var busy: app.fireplayer.ui.BusyOverlay
    private val busySigns = app.fireplayer.ui.BusySigns()
    private val busyCheck = Runnable { showBusy(player.status()) }

    private val player by lazy { PlayerHost.get(this) }
    /** Film mode memory and background release (§6.6). */
    private lateinit var filmMode: FilmMode
    /** Display size (shared with the VOD player) and the screen dimmer's customs, read as the live picture is laid out. */
    private val videoPrefs by lazy { Prefs(this) }
    private val dimmer by lazy { Dimmer(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        Glass.on = prefs.glassLook
        shellTokens = Tokens(this)
        // Pictures keep on disk across runs (posters, backdrops).
        ImageLoader.init(applicationContext)
        setContentView(R.layout.activity_home)
        root = findViewById(R.id.home_root)
        surface = findViewById(R.id.live_surface)
        captions = findViewById(R.id.live_captions)
        liveDimmer = findViewById(R.id.live_dimmer)
        busy = app.fireplayer.ui.BusyOverlay(this, shellTokens)
        root.addView(busy, root.indexOfChild(liveDimmer) + 1, busy.params())
        pages = findViewById(R.id.page_container)
        // Glass windows over the page show these two layers, blurred: copied when a window appears, and again only
        // when something under one changes (each layer says when).
        val wallpaperHost = findViewById<app.fireplayer.ui.GlassLayer>(R.id.wallpaper_host)
        val pageLayer = findViewById<app.fireplayer.ui.GlassLayer>(R.id.page_container)
        val glass = app.fireplayer.ui.PageGlass(root, listOf(wallpaperHost, pageLayer)) { prefs.glassRefreshPerSec }
        behind = glass
        wallpaperHost.onContentChanged = { glass.contentChanged(it) }
        pageLayer.onContentChanged = { glass.contentChanged(it) }
        settingsPanels = SettingsPanels(this, prefs, SubtitlePresets(prefs))
        panel = SidePanel(this)
        liveSettings = LiveSettings(this, panel)
        // App settings' list opens Playlists & EPG and Live TV from here.
        settingsPanels.liveWindows = { liveSettings.openCategory(it) }
        // The Search bubble (Search and App Settings; Live TV's settings window): Live TV search from Live TV.
        settingsPanels.onSearch = { openSearch(if (rail.selected == NavRail.Tab.LIVE) SearchKind.LIVE else SearchKind.VOD) }
        // Settings › Picture-in-picture switched off while the picture plays: it stops now.
        settingsPanels.onPipChanged = { if (pipOn && !prefs.pipKeep) stopPip(closed = false) }

        wallpaper = WallpaperView(this).apply { glassLook = Glass.on }
        wallpaperHost.addView(wallpaper, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wallpaper.onReady = { glass ->
            still = glass
            // Glass made before the wallpaper was ready showed the dark fill: made again now with the wallpaper.
            if (Glass.on) applyLook()
        }

        rail = NavRail(this, tokens)
        // Always as wide as the widened rail: it draws its window inside that, so widening lays nothing out.
        findViewById<FrameLayout>(R.id.rail_host).addView(rail, FrameLayout.LayoutParams(rail.viewWidth, ViewGroup.LayoutParams.MATCH_PARENT))
        rail.onTab = { tab ->
            // Chosen in the rail: Back from it goes to the rail, as from any tab.
            serviceFrom = null
            val picked = railServices.getOrNull(NavRail.SERVICE_TABS.indexOf(tab))
            if (picked != null) showService(picked) else showTab(tab)
        }
        showRailServices()
        rail.onEnterPage = { focusPage() }
        root.viewTreeObserver.addOnGlobalFocusChangeListener(focusWatcher)

        live = LivePage(this, this)
        pages.addView(live.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Each film page is made the first time its tab opens.
        vodPages = VodPages(this, this, pages)
        settingsPage = buildSettingsPage()
        pages.addView(settingsPage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL).apply { leftMargin = tokens.dp(40) })

        player.attach(surface)
        player.addListener(videoSizeListener)

        lookOn = Glass.on
        clipForLook()
        settingsPanels.onLookChanged = { applyLook() }
        // Settings › Rows › Service shortcuts: the rail follows at once.
        settingsPanels.onRailServicesChanged = { showRailServices() }
        // Subtitle style changed in a settings window: the live captions follow at once, as the film player's do.
        settingsPanels.onSubtitleStyleChanged = { applyCaptionStyle() }
        settingsPanels.onAssStylingChanged = { applyCaptionStyle() }
        // Info pods are one setting for films and live channels: the live controls follow a change at once.
        settingsPanels.onChipsChanged = { live.refreshPods() }
        filmMode = FilmMode(this, object : FilmMode.Hooks {
            override fun stopLive() {
                // The channel stops and its player is released: its decoders are the film's now. Multiview's pictures
                // too, for good (they don't come back after the film), and the live picture on Home or Search.
                live.endMultiview()
                stopPip(closed = false)
                pipResume = false
                live.stop()
                player.stop()
                placeVideo(null, 0f, false)
            }

            override fun release() {
                // The pages' pictures went with onStop; what's left of them in memory, the live picture's glass
                // frames and the guide's listings and logos go too.
                vodPages.trimImages()
                ImageLoader.trim()
                film?.stop()
                behind?.trim()
                live.dropMemory()
                // The glass look's full-screen wallpaper (about 8 MB) too; it loads again on return.
                wallpaper.release()
            }
        })
        // Home is the start page; Live TV is one press away on the rail.
        showTab(NavRail.Tab.HOME)
        root.post { focusPage() }

        // A test build is signed differently from the releases, so a release can't install over it: don't offer one.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) {
            val updater = Updater(this)
            updater.check { release -> release?.let { updater.offer(it) } }
        }
        // Normally running since the TV started; the first time after installing, from here.
        TitleService.start(this)
    }

    override fun onStart() {
        super.onStart()
        // Back from a film (or a while away): the snapshot shows at once while the page fills again.
        filmMode.onStart(root)
        behind?.start()
        wallpaper.restore()
        launchedAt = Long.MIN_VALUE / 2
        // This screen's surface shows live again (another instance may have taken it meanwhile).
        player.attach(surface)
        if (rail.selected == NavRail.Tab.LIVE) live.start()
        // The live picture on Home or Search when the screen stopped comes back with it, its sound as it was.
        if (pipResume) {
            pipResume = false
            val back = PipRules.keepsPicture(pipPageOf(rail.selected)) && Prefs(this).pipKeep && live.keepAgain()
            if (back) showPip(fresh = false, muted = pipResumeMuted) else placeVideo(null, 0f, false)
        }
        armLiveRoom(rail.selected == NavRail.Tab.LIVE, pictureFull)
        vodPages.start()
        // The dimmer may have been changed in the VOD player meanwhile.
        applyLiveDim()
        // Playlists and EPGs not updated within their intervals are updated in the background: at start, and each
        // time the app comes back (it can stay alive for days on a TV).
        LiveSync.get(this).updateDue()
    }

    override fun onStop() {
        armLiveRoom(false, full = false)
        // The live picture's channel stops with the screen (remembered): it comes back with it (onStart).
        pipBackHeld = false
        if (pipOn) {
            pipResume = true
            pipResumeMuted = pipMuted
            stopPip(closed = false)
        }
        live.stop()
        // The film pages' pictures go, and half the decoded ones kept in memory: a film may be starting in the player.
        vodPages.stop()
        ImageLoader.trim(ImageLoader.TRIM_HALF)
        film?.stop()
        behind?.stop()
        root.keepScreenOn = false
        // A film starting: the rest of the memory goes now; otherwise after a while in the background.
        filmMode.onStop()
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        // The rail's motion ends where it was heading: nothing is left half-way or moving unseen.
        if (::rail.isInitialized) rail.finishMotion()
        filmMode.onPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && ::vodPages.isInitialized) {
            vodPages.trimImages()
            ImageLoader.trim()
        }
        // In the background and Fire OS wants memory from the apps there: what would go after a while goes now.
        if (PausedRoom.backgroundAsks(level) && ::filmMode.isInitialized && filmMode.releaseIfAway()) {
            android.util.Log.i("FirePlayer", "Background: memory asked for (level $level), the pages let go now")
        }
    }

    override val videoOwner: Any get() = surface

    override fun overlayOpen(): Boolean = panel.isOpen || FireDialog.isShowing(this)

    override val sidePanel: SidePanel get() = panel

    /** Glass made while the full-screen controls show the live picture is dimmed like the VOD player's glass. */
    override val glassShowsFilm: Boolean get() = filmOn

    override fun pictureChanged() {
        layoutVideo()
        applyLiveDim()
    }

    /** Multiview shows: its pictures (its board, under the pages) take the screen's video in place of the one picture. */
    private var multiviewOn = false

    override fun addMultiview(board: View) {
        // Under the pages, over the wallpaper and the one picture's buffering symbol: the controls, windows and notices
        // draw over it.
        root.addView(board, root.indexOfChild(busy) + 1, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun setMultiview(on: Boolean) {
        if (multiviewOn == on) return
        multiviewOn = on
        // The one picture hides (its surface, buffering symbol and captions); the wallpaper draws nothing under the
        // board while it shows (it would only be drawn over), and comes back as it closes.
        if (on) placeVideo(null, 0f, false) else wallpaper.setHole(null, 0f)
    }

    override fun setFilmGlass(on: Boolean) {
        filmOn = on && Glass.on && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N
        if (filmOn) {
            val f = film ?: GlassBackdrop(this, surface).also { film = it }
            f.start()
            f.playing = player.status().playing
            f.frameChanged()
        } else {
            film?.stop()
        }
    }

    override fun onDestroy() {
        root.viewTreeObserver.removeOnGlobalFocusChangeListener(focusWatcher)
        root.removeCallbacks(busyCheck)
        player.removeListener(videoSizeListener)
        player.detach(surface)
        live.release()
        vodPages.release()
        liveSettings.release()
        film?.release()
        behind?.stop()
        filmMode.release()
        super.onDestroy()
    }

    /**
     * A film or an episode from Home, Movies or TV Shows: room is made first (film mode), then FirePlayer's player
     * starts with the request (a token in the Intent, never a link).
     */
    override fun play(request: PlayRequest) {
        // One launch at a time: a second OK while the player is on its way (snapshot, activity switch) is ignored.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - launchedAt < LAUNCH_GUARD_MS) return
        launchedAt = now
        filmMode.beforeFilm {
            // The link check starts here, at OK, while this screen makes room and the player's comes up.
            if (!isFinishing && !isDestroyed) startActivity(
                PlayHandoff.intent(this, VodLaunch.pressedAt(VodLaunch.withFilmMode(request), now), app.fireplayer.vod.Sources.get(this), now),
            )
        }
    }

    /**
     * OK on one of Home's recent channels: Live TV opens on it, in the miniature, as the channel watched last does.
     */
    override fun openChannel(key: ChannelKey) {
        if (rail.selected == NavRail.Tab.LIVE) return
        // Remembered first: Live TV's start plays it when nothing is playing.
        live.openChannel(key)
        showTab(NavRail.Tab.LIVE)
        // And in place of a channel still playing from before.
        live.openChannel(key)
        // As when Live TV is entered from the rail: focus on its tab (Home's row is gone), then into the page, at once
        // or when its channels have loaded.
        rail.focusSelected()
        focusPage()
    }

    /** Search opened from a settings window's Search bubble, in [kind], focus on its bar. */
    private fun openSearch(kind: SearchKind) {
        if (panel.isOpen) panel.close()
        showTab(NavRail.Tab.SEARCH)
        vodPages.current?.openSearch(kind)
    }

    /** The player's Search and App Settings window chose Search: the film has ended, Search opens here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SEARCH, false)) openSearch(SearchKind.VOD)
    }

    /** Live TV search: the channel plays full screen ([at] null), or the guide shows it (a later programme). */
    override fun openLiveResult(key: ChannelKey, at: Long?) {
        // Remembered first, so Live TV's start tunes this channel and not the one watched before.
        live.openChannel(key)
        showTab(NavRail.Tab.LIVE)
        rail.focusSelected()
        focusPage()
        live.openFromSearch(key, full = at == null)
    }

    /** Back from Live TV search's starting view. */
    override fun backToLive() {
        showTab(NavRail.Tab.LIVE)
        rail.focusSelected()
        focusPage()
    }

    // ---- The live picture on Home and Search (automatic picture-in-picture, PipRules) -----------------------------
    //
    // The channel playing on Live TV carries on top right on Home and Search: the same player and surface as the
    // guide's miniature and full screen (no second player), its place the page's box ([VodPage.pipSpot]). It moves up
    // and down with the page frame by frame (translation only), and plays on unseen, under the wallpaper, while a title
    // page, See all or the settings window covers its place. Movies, TV Shows, My List and Settings stop it; Live TV
    // takes it back into the miniature.

    /** A channel plays on in the picture on Home or Search. */
    private var pipOn = false
    /** Its sound is off (the strip's Volume; Settings › Picture-in-picture › Start with sound). */
    private var pipMuted = false
    /** The picture was on when the screen stopped: it comes back with the screen, its sound as it was. */
    private var pipResume = false
    private var pipResumeMuted = false
    /** How far the page has moved the picture up from its place (px, 0 at rest). */
    private var pipShift = 0f
    /** The line the picture is cut at as its page takes it up (px on screen; 0: none), [VodPage.pipClipTop]. */
    private var pipClip = 0f
    /** Its place is covered (or not laid out yet): it plays on under the wallpaper. */
    private var pipVeiled = false
    /** A held Back opened the picture full screen: its release does nothing more. */
    private var pipBackHeld = false
    /** The playing channel's failure the picture's page has said (once per failure). */
    private var pipErrorSaid: String? = null
    /** Whether a SurfaceView follows its view's transforms here (Android 7 and later): else the picture waits at rest. */
    private val canFollow = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N
    /**
     * Home's window for the picture is changing width ([VodPage.Host.pipResize]): the picture follows it frame by frame
     * by its transforms, laid out where it was put last, until the window's [pipMoved] at the end.
     */
    private var pipSizing = false
    /** Where the window is drawn this frame (at rest, without the page's shift), and the picture fitted in it. */
    private val sizingSpot = RectF()
    private val sizingPicture = app.fireplayer.live.ui.PictureMotion.Box()

    private fun pipPageOf(tab: NavRail.Tab): PipRules.Page = when (tab) {
        NavRail.Tab.SEARCH -> PipRules.Page.SEARCH
        NavRail.Tab.HOME -> PipRules.Page.HOME
        NavRail.Tab.MOVIES -> PipRules.Page.MOVIES
        NavRail.Tab.SHOWS -> PipRules.Page.TV_SHOWS
        NavRail.Tab.MY_LIST -> PipRules.Page.MY_LIST
        NavRail.Tab.LIVE -> PipRules.Page.LIVE
        NavRail.Tab.SETTINGS -> PipRules.Page.SETTINGS
        // A service's page is Home's layout: the picture stays there the same way.
        NavRail.Tab.SERVICE_1, NavRail.Tab.SERVICE_2, NavRail.Tab.SERVICE_PAGE -> PipRules.Page.HOME
    }

    /**
     * The picture on the page shown now: [fresh] as Live TV is left (its sound as Start with sound says), otherwise
     * moving from the other page (or back with the screen), its sound as it was.
     */
    private fun showPip(fresh: Boolean, muted: Boolean = pipMuted) {
        val page = vodPages.current ?: return
        pipOn = true
        pipMuted = if (fresh) PipRules.mutedAtStart(Prefs(this).pipSound) else muted
        player.setMuted(pipMuted)
        vodPages.pipOffExcept(page)
        page.setPip(true, pipMuted)
        pipWords()
        pipMoved()
    }

    /** The strip's words: the channel playing and what's on it now. */
    private fun pipWords() {
        live.keptInfo { name, programme -> if (pipOn) vodPages.current?.pipInfo(name, programme) }
    }

    /**
     * The picture leaves Home and Search; the channel's stream is left as it is (Live TV takes it, or [stopPip] stops
     * it). Where the page had moved it up, it stays there for a move that follows (full screen grows from it).
     */
    private fun endPip(closed: Boolean = false) {
        if (!pipOn) return
        pipOn = false
        val page = vodPages.current
        // Focus on the strip moves first (Close: the first recent channel on Home), then the boxes go.
        page?.pipOff(closed)
        vodPages.pipOffExcept(null)
        if (pipShift != 0f && laidAt != null && mover == null && canFollow) {
            // The picture is shown shifted: the next move sets off from there.
            motion.picture.set(motion.picture.left, motion.picture.top + pipShift, motion.picture.right, motion.picture.bottom + pipShift)
            motion.hole.set(motion.hole.left, motion.hole.top + pipShift, motion.hole.right, motion.hole.bottom + pipShift)
        }
        pipShift = 0f
        pipSizing = false
        pipVeiled = false
        pipMuted = false
        player.setMuted(false)
    }

    /**
     * The picture's channel stops (Close; another tab; the setting switched off): its stream and connection go, the
     * channel remembered, so Live TV carries on with it as it always did.
     */
    private fun stopPip(closed: Boolean) {
        if (!pipOn) return
        endPip(closed)
        live.letGo()
        placeVideo(null, 0f, false)
    }

    /** The picture full screen on Live TV (its Full screen; Back held on its page), growing from where it is. */
    private fun pipToFull() {
        val key = ChannelKey.parse(player.currentKey)
        if (!pipOn || key == null) return
        if (panel.isOpen) panel.close()
        endPip()
        // Remembered first, as for a channel from Live TV search.
        live.openChannel(key)
        showTab(NavRail.Tab.LIVE)
        rail.focusSelected()
        focusPage()
        live.openKeptFull(key)
    }

    /** Puts the picture in the page's box (its place at rest, moved by the page's shift), or under the wallpaper. */
    override fun pipMoved() {
        if (!pipOn) return
        // Laid out where its window is now: the frames that follow (if it's still changing) start from there.
        pipSizing = false
        val page = vodPages.current
        val spot = page?.pipSpot()
        if (page == null || spot == null) {
            placeVideo(null, 0f, false)
            return
        }
        // The page's box is laid out by its margins, in the pages' frame (right of the rail's strip): known at once,
        // before any layout pass (the frame's margin changes as Live TV is left).
        val frame = pages.layoutParams as ViewGroup.MarginLayoutParams
        spot.offset(frame.marginStart.toFloat(), frame.topMargin.toFloat())
        pipShift = page.pipShift()
        pipClip = page.pipClipTop() + frame.topMargin
        pipVeiled = page.pipCovered() || panel.isOpen
        placeVideo(spot, page.pipRadius(), false)
    }

    override fun pipFollow(dy: Float) {
        if (!pipOn) return
        pipShift = dy
        showPipShift()
    }

    override fun pipResize() {
        if (!pipOn) return
        val page = vodPages.current ?: return
        if (!page.pipSpotNow(sizingSpot)) return
        val frame = pages.layoutParams as ViewGroup.MarginLayoutParams
        sizingSpot.offset(frame.marginStart.toFloat(), frame.topMargin.toFloat())
        pipShift = page.pipShift()
        pipClip = page.pipClipTop() + frame.topMargin
        pipSizing = true
        showPipSizing()
    }

    /**
     * One frame of Home's window changing width: the picture fitted in it where it's drawn now, shown there by the
     * surface's scale and translation from where it's laid out (never resized frame by frame), the wallpaper's hole
     * the window's bounds, both moved by the page's shift. Kept as where the picture is ([motion]), so a move that
     * starts now (full screen) sets off from here. Where a surface can't follow transforms, it waits unseen.
     */
    private fun showPipSizing() {
        if (videoFull || mover != null) return
        val laid = laidAt ?: return
        val s = sizingSpot
        if (!canFollow || !PipRules.pictureShows(pipVeiled, pipShift, canFollow)) {
            wallpaper.setHole(null, 0f)
            busy.away()
            return
        }
        val size = lastSize
        val aspect = if (size.width > 0 && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 0f
        app.fireplayer.live.ui.LivePicture.fit(s.left, s.top, s.width(), s.height(), aspect, sizingPicture)
        motion.picture.set(sizingPicture)
        motion.hole.set(s.left, s.top, s.right, s.bottom)
        endHole.set(s.left, s.top, s.right, s.bottom)
        app.fireplayer.live.ui.PictureMotion.transform(laid, sizingPicture, transformNow)
        surface.pivotX = 0f
        surface.pivotY = 0f
        surface.scaleX = transformNow[0]
        surface.scaleY = transformNow[1]
        surface.translationX = transformNow[2]
        surface.translationY = transformNow[3] + pipShift
        showPipHole(s.left, s.top, s.right, s.bottom)
    }

    /** The picture where the page has moved its box to, by translation only (or unseen; see [PipRules.pictureShows]). */
    private fun showPipShift() {
        if (!pipOn || videoFull || mover != null || laidAt == null) return
        // Its window changing width too: the frame's transforms carry the shift.
        if (pipSizing) return showPipSizing()
        if (canFollow && surface.translationY != pipShift) surface.translationY = pipShift
        if (!PipRules.pictureShows(pipVeiled, pipShift, canFollow) || endHole.width <= 0f) {
            wallpaper.setHole(null, 0f)
            busy.away()
            return
        }
        showPipHole(endHole.left, endHole.top, endHole.right, endHole.bottom)
    }

    /**
     * The wallpaper's hole for the picture's window (at rest: [left], [top], [right], [bottom]) where the page has it
     * now: moved by the page's shift and cut at the viewport's top ([pipClip]), as the banner beside it is, so the
     * picture shows only inside its window all the way up, and not at all once the window is out of sight. The
     * surface itself only moves (translation); the wallpaper covers what's above the line.
     */
    private fun showPipHole(left: Float, top: Float, right: Float, bottom: Float) {
        holeNow.set(left, top + pipShift, right, bottom + pipShift)
        if (holeNow.bottom <= pipClip) {
            wallpaper.setHole(null, 0f)
            busy.away()
            return
        }
        wallpaper.setHole(holeNow, videoRadius, full = false, cutAbove = pipClip)
        // The buffering symbol only while the picture's middle is in sight.
        if (holeNow.centerY() > pipClip) busy.place(holeNow.centerX(), holeNow.centerY(), 0f) else busy.away()
    }

    override fun pipButton(button: PipRules.Button) {
        if (!pipOn) return
        when (button) {
            PipRules.Button.VOLUME -> {
                pipMuted = !pipMuted
                player.setMuted(pipMuted)
                vodPages.current?.pipMuted(pipMuted)
            }
            PipRules.Button.FULL_SCREEN -> pipToFull()
            PipRules.Button.CLOSE -> stopPip(closed = true)
        }
    }

    override fun pipStripShown() = pipWords()

    /** A held Back on the picture's page: full screen ([PipRules.back]); its release then does nothing more. */
    private fun pipBack(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            if (!pipBackHeld) return false
            pipBackHeld = false
            return true
        }
        if (pipBackHeld) return true
        val held = event.repeatCount >= 1
        val page = vodPages.current
        val inFront = page?.pipInFront() == true && !panel.isOpen
        if (PipRules.back(held, inStrip = false, playing = pipOn, pageInFront = inFront) != PipRules.BackAct.FULL_SCREEN) return false
        pipBackHeld = true
        pipToFull()
        return true
    }

    // LivePage.Host: the channel kept playing on Home or Search.
    override fun keptChanged() {
        if (pipOn) pipWords()
    }

    override fun keptNotice(text: String) {
        if (pipOn) vodPages.current?.showNotice(text)
    }

    override fun keptSleep() {
        if (!pipOn) return
        val page = vodPages.current
        stopPip(closed = false)
        player.stop()
        page?.showNotice("Sleep timer: Live TV stopped")
    }

    override fun onRestart() {
        super.onRestart()
        // Service shortcuts may have been changed in the player's settings meanwhile.
        showRailServices()
        if (lookOn == Glass.on) return
        // The look was switched in the player while this screen was behind it.
        applyLook()
        if (panel.isOpen) {
            Glass.overlay(panel.root, Glass.Kind.PANEL)
            panel.refresh()
        }
    }

    // ---- Streaming services ---------------------------------------------------------------------------------------

    /** The service pages visited last, newest first, kept across restarts: they fill the shortcuts not pinned. */
    private var recentServices: List<Service> = emptyList()
    /** What the rail's two service shortcuts under Live TV show, top first (null: none there; empty: switched off). */
    private var railServices: List<Service?> = emptyList()
    /** The service whose page shows on a service tab. */
    private var service: Service? = null
    /** The tab a service's page was opened from by a card: Back on the page goes back there. */
    private var serviceFrom: NavRail.Tab? = null

    /**
     * The rail's service shortcuts as Settings › Rows › Service shortcuts has them ([Services.railSlots]): pinned
     * services in their slots, the ones visited last in the others. A service page shown just now moves to its
     * shortcut's tab, or to the tab of a page with none.
     */
    private fun showRailServices() {
        val prefs = Prefs(this)
        recentServices = Services.decode(prefs.recentServices)
        railServices = Services.railSlots(prefs.serviceShortcuts, Services.decode(prefs.pinnedServices), recentServices)
        rail.setServices(railServices.map { s -> s?.let { NavRail.ServiceTab(it.label, iconOf(it), focusedIconOf(it)) } })
        val s = service
        if (s != null && rail.selected.isService) rail.setSelected(tabOf(s))
    }

    /** The tab [s]'s page shows on: its shortcut's, or the one for a page with no shortcut. */
    private fun tabOf(s: Service): NavRail.Tab =
        NavRail.SERVICE_TABS.getOrNull(railServices.indexOf(s)) ?: NavRail.Tab.SERVICE_PAGE

    private fun iconOf(service: Service): Int = when (service) {
        Service.DISNEY -> R.drawable.ic_service_disney_plus
        Service.HULU -> R.drawable.ic_service_hulu
        Service.NETFLIX -> R.drawable.ic_service_netflix
        Service.HBO_MAX -> R.drawable.ic_service_hbo_max
        Service.APPLE_TV -> R.drawable.ic_service_apple_tv
        Service.PRIME_VIDEO -> R.drawable.ic_service_prime_video
    }

    /** A service's dark rail icon, for the light highlight while its tab holds focus; Hulu and Netflix keep their one icon. */
    private fun focusedIconOf(service: Service): Int? = when (service) {
        Service.DISNEY -> R.drawable.ic_service_disney_plus_focused
        Service.HBO_MAX -> R.drawable.ic_service_hbo_max_focused
        Service.APPLE_TV -> R.drawable.ic_service_apple_tv_focused
        Service.PRIME_VIDEO -> R.drawable.ic_service_prime_video_focused
        Service.HULU, Service.NETFLIX -> null
    }

    /**
     * [s]'s page opens: a visit, so it becomes the service visited last (once in the list; focus on its card is
     * none). It shows on its shortcut's tab, or, with no shortcut on the rail, on the tab of a page with none.
     */
    private fun showService(s: Service) {
        recentServices = Services.visited(recentServices, s)
        Prefs(this).recentServices = Services.encode(recentServices)
        service = s
        showRailServices()
        showTab(tabOf(s))
    }

    /** OK on a service's card (Home's Streaming Services row): its page opens, and Back on it comes back here. */
    override fun openService(service: Service) {
        val from = rail.selected
        showService(service)
        serviceFrom = from.takeIf { !it.isService }
        // As when a tab is entered from the rail: focus on its tab, then into the page, at once or when its rows have come.
        rail.focusSelected()
        focusPage()
    }

    /** A Home service card's small window pinned or unpinned one: the rail's shortcuts follow at once, shown with [show]. */
    override fun pinServices(pins: List<Service>, show: Boolean) {
        val prefs = Prefs(this)
        prefs.pinnedServices = Services.encode(pins)
        if (show != prefs.serviceShortcuts) prefs.serviceShortcuts = show
        showRailServices()
    }

    /** The Disney+ studio whose page shows over the service's, opened from its card there; null: the service's own page. */
    private var studio: Brand? = null

    /** OK on a studio's card (Disney+'s row): its page of rows opens over the service's, and Back comes back to the card. */
    override fun openStudio(brand: Brand) {
        if (!rail.selected.isService || service == null) return
        studio = brand
        vodPages.show(rail.selected, service, brand)
        // The live picture goes with the page shown: into the studio page's own window.
        if (pipOn) showPip(fresh = false)
        rail.focusSelected()
        focusPage()
    }

    /** Back on a studio's page: the service's page again, as it was left, on the studio's card. False otherwise. */
    private fun backFromStudio(): Boolean {
        if (studio == null || !rail.selected.isService) return false
        studio = null
        vodPages.show(rail.selected, service)
        if (pipOn) showPip(fresh = false)
        rail.focusSelected()
        focusPage()
        return true
    }

    /** Back on a service's page opened from a card: the page it was opened from, on that card. False otherwise. */
    private fun backFromService(): Boolean {
        val to = serviceFrom?.takeIf { rail.selected.isService } ?: return false
        serviceFrom = null
        showTab(to)
        rail.focusSelected()
        focusPage()
        return true
    }

    // ---- Tabs -----------------------------------------------------------------------------------------------------

    private fun showTab(tab: NavRail.Tab) {
        val from = rail.selected
        // A tab chosen anew shows its own page, never a studio's left open.
        studio = null
        rail.setSelected(tab)
        // Live TV's rail is a strip attached to the screen's edge; every other tab's a floating window. It moves from
        // one to the other as Live TV is entered or left.
        rail.setAttached(tab == NavRail.Tab.LIVE, animate = true)
        pageInset(tab)
        if (tab != NavRail.Tab.LIVE) setRailShown(true, 0L)
        val isLive = tab == NavRail.Tab.LIVE
        live.root.visibility = if (isLive) View.VISIBLE else View.GONE
        settingsPage.visibility = if (tab == NavRail.Tab.SETTINGS) View.VISIBLE else View.GONE
        // Search, Home, Movies, TV Shows or My List (the others hidden, their pictures let go).
        vodPages.show(tab, service)
        // Live TV in front a while: what the other tabs left in memory goes (LiveRoom).
        armLiveRoom(isLive, full = false)
        if (isLive) {
            // The live picture on Home or Search comes back into the guide's miniature, still playing, with its sound.
            if (pipOn) endPip()
            live.start()
        } else {
            // Multiview lives inside Live TV's full screen: leaving the tab closes it for good.
            live.endMultiview()
            // A playing channel follows to Home and Search, top right (PipRules); other tabs stop it, remembered for
            // Live TV, as leaving it always did.
            val to = pipPageOf(tab)
            val keep = if (from == NavRail.Tab.LIVE) {
                live.leave(PipRules.keepOnLeavingLive(Prefs(this).pipKeep, to, playing = player.currentKey != null, multiview = live.multiviewActive, paused = player.paused))
            } else {
                (pipOn && PipRules.whenGoingTo(to) == PipRules.Move.KEEP).also { if (pipOn && !it) stopPip(closed = false) }
            }
            if (keep) showPip(fresh = from == NavRail.Tab.LIVE) else placeVideo(null, 0f, false)
        }
    }

    /** The picture was full screen when the release below was last set going. */
    private var roomFull = false
    private val liveRoom = Runnable { freeLiveRoom() }
    private val liveRoomStale = Runnable { if (rail.selected == NavRail.Tab.LIVE) app.fireplayer.player.GraphicsTrim.trim() }

    /** Lets go of what's out of sight [LiveRoom.dueMs] after Live TV is in front ([on]) or its picture went [full]. */
    private fun armLiveRoom(on: Boolean, full: Boolean) {
        root.removeCallbacks(liveRoom)
        root.removeCallbacks(liveRoomStale)
        roomFull = on && full
        if (on) root.postDelayed(liveRoom, app.fireplayer.playback.LiveRoom.dueMs(full))
    }

    /**
     * Live TV's memory (measured: 397 MB, 208 MB of it graphics, with a channel selected): the pages' pictures still
     * kept in memory, the page glass's copies while the picture is full screen and no window shows, and the graphics
     * no window draws any more. Nothing on screen goes; it all comes back as it's shown again.
     */
    private fun freeLiveRoom() {
        if (rail.selected != NavRail.Tab.LIVE || isFinishing) return
        val full = pictureFull
        val (pictures, bytes) = ImageLoader.trimLarger(app.fireplayer.playback.LiveRoom::isLarge)
        val glass = full && !overlayOpen() && behind?.trimIfIdle() == true
        // Android's own trim drops the guide's drawing caches too (drawn again next frame): only under the full-screen
        // picture. The other way (only what went unused half a minute) is safe with the guide on screen.
        val trim = app.fireplayer.player.GraphicsTrim
        val graphics = (full || trim.staleOnly) && trim.trim()
        // Where Android only lets go of what went unused for half a minute, it's asked again once that's passed.
        if (graphics && trim.staleOnly) root.postDelayed(liveRoomStale, trim.STALE_MS + 1_000L)
        android.util.Log.i("FirePlayerLive", app.fireplayer.playback.LiveRoom.line(full, pictures, bytes, glass, graphics))
    }

    private fun focusPage() {
        when (rail.selected) {
            NavRail.Tab.LIVE -> live.takeFocus()
            NavRail.Tab.SETTINGS -> settingsRows.firstOrNull()?.requestFocus()
            // Search, Home, Movies, TV Shows, My List.
            else -> vodPages.current?.takeFocus()
        }
    }

    /** The settings window was open over the live picture when last looked (focus goes in and out with it). */
    private var pipUnderPanel = false

    private val focusWatcher = ViewTreeObserver.OnGlobalFocusChangeListener { _, now ->
        val inRail = now != null && isInside(now, rail)
        rail.setExpanded(inRail)
        // The settings window opened over the live picture's page, or closed: it plays on unseen under it meanwhile
        // (the window's glass would show it through its blur).
        if (pipOn && panel.isOpen != pipUnderPanel) {
            pipUnderPanel = panel.isOpen
            pipMoved()
        }
    }

    private fun isInside(v: View, parent: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === parent) return true
            p = p.parent as? View
        }
        return false
    }

    // ---- Settings tab ---------------------------------------------------------------------------------------------

    /** The Settings tab's rows, column by column: App settings' categories, then Open a link. */
    private val settingsRows = ArrayList<View>()

    /**
     * The Settings tab: a row for each category of App settings ([SettingsCategory]), each opening its own window,
     * and Open a link last. Rows that don't fit one column under the title go on in a second ([settingsColumns]).
     */
    private fun buildSettingsPage(): LinearLayout {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(TextView(this).apply {
            text = "Settings"
            typeface = tokens.bold
            setTextColor(tokens.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = tokens.dp(TITLE_GAP_DP) })
        val rows = SettingsCategory.entries.map { category ->
            SettingsRow(category.title, category.icon) { from -> openCategory(category, from) }
        } + SettingsRow(SettingsCategory.OPEN_A_LINK, R.drawable.ic_link) { openLink() }
        val columns = SettingsCategory.inColumns(rows, settingsColumns(rows.size))
        val side = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // Its own width (the columns'): left to match the page, it would only get the title's width, as the page is
        // as wide as its widest child.
        page.addView(side, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Narrower in two columns, so the second stays clear of the settings window (its left edge is about 644 dp in).
        val width = tokens.dp(if (columns.size > 1) COLUMN_DP else ONE_COLUMN_DP)
        columns.forEachIndexed { i, column ->
            val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            side.addView(list, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) marginStart = tokens.dp(COLUMN_GAP_DP)
            })
            for (row in column) settingsRows += addRow(list, row.label, row.icon, row.onClick)
        }
        return page
    }

    private class SettingsRow(val label: String, val icon: Int, val onClick: (View) -> Unit)

    /**
     * Columns for the Settings tab's [rows], from addRow's sizes: each row 44 dp and 8 dp under it, the title (26 sp,
     * about 34 dp) and its 16 dp gap, and 27 dp kept clear above and below (the 5% a TV may cut off). On the Fire TV's
     * 960 x 540 dp screen ten rows need 50 + 520 dp, more than its 540: two columns of five, so nothing has to scroll.
     */
    private fun settingsColumns(rows: Int): Int {
        val metrics = resources.displayMetrics
        return SettingsCategory.columnsFor(
            rows, screenDp = (metrics.heightPixels / metrics.density).toInt(), rowDp = ROW_DP, gapDp = ROW_GAP_DP,
            titleDp = TITLE_DP + TITLE_GAP_DP, edgeDp = SAFE_EDGE_DP,
        )
    }

    /** A category's own window; the page dims while it's open and focus comes back to [from] when it closes. */
    private fun openCategory(category: SettingsCategory, from: View) {
        when (category) {
            SettingsCategory.PLAYLISTS_EPG -> liveSettings.openPlaylistsAndEpg(onClose = { restore(from) }).also { stepBack(settingsPage) }
            SettingsCategory.LIVE_TV -> liveSettings.openLiveOptions(onClose = { restore(from) }).also { stepBack(settingsPage) }
            else -> openPanel(category.title, from) { settingsPanels.renderCategory(this, category) }
        }
    }

    /**
     * [page] steps back behind a window opened over it. Classic: it fades to about a third (the classic window is
     * partly see-through), as one layer, drawn once rather than each frame and dropped when the fade ends. Glass: it
     * stays as it is, since the window's glass shows that very page, blurred, under its own tint; fading it too would
     * darken the glass twice.
     */
    private fun stepBack(page: View, layer: Boolean = true) {
        if (Glass.on) return
        val fade = page.animate().alpha(0.35f).setDuration(160)
        if (layer) fade.withLayer()
        fade.start()
    }

    /** [page] comes forward again as the window over it closes (nothing to do if it never stepped back). */
    private fun stepForward(page: View, layer: Boolean = true) {
        page.animate().cancel()
        if (page.alpha == 1f) return
        val fade = page.animate().alpha(1f).setDuration(160)
        // Glass (switched on while the page was stepped back): no layer, as the closing window's glass may still copy
        // the page, and a page held as a layer would be drawn full size for that copy.
        if (layer && !Glass.on) fade.withLayer()
        fade.start()
    }

    private fun restore(from: View) {
        stepForward(settingsPage)
        // The row it was opened from, if it's on screen (Menu can open Settings over the Live TV tab).
        if (from.isShown) from.requestFocus() else focusPage()
    }

    private fun openPanel(title: String, from: View, render: SidePanel.() -> Unit) {
        // The menu steps back while a window is open, so the window stands out.
        stepBack(settingsPage)
        panel.open(title, onClose = { restore(from) }, render = render)
    }

    /** From the Live TV tab with no playlists: the Playlists window. */
    override fun openPlaylists() {
        liveSettings.openPlaylists(onClose = { live.takeFocus() })
    }

    /** Live FirePlayer Settings over the Live TV tab; focus returns where it was when it closes. */
    override fun openPlayerSettings(onClosed: (() -> Unit)?) {
        val back = currentFocus
        // The guide steps back while the window is open (the classic window is see-through), as the Settings tab does.
        stepBack(live.root, layer = false)
        liveSettings.openPlayerSettings(settingsPanels, onClose = {
            stepForward(live.root, layer = false)
            if (onClosed != null) onClosed() else if (back != null && back.isShown) back.requestFocus() else focusPage()
        })
    }

    /**
     * Subtitle style over the full-screen live picture, the window the film player opens: each change shows on the
     * captions at once (example text stands in while none are on screen).
     */
    override fun openSubtitleStyle(onClosed: () -> Unit) {
        captionPreview = true
        showCues(player.currentCues)
        panel.open("Subtitle style", onClose = {
            captionPreview = false
            showCues(player.currentCues)
            onClosed()
        }) {
            settingsPanels.renderSubtitleStyle(this, onStyleChanged = { applyCaptionStyle() }, onAssChanged = { applyCaptionStyle() })
        }
    }

    /** Subtitle style's window is open over the picture: with no captions on screen, example text shows the style. */
    private var captionPreview = false

    /** The live captions in the Subtitle style as it is now (it was changed), those on screen with them. */
    private fun applyCaptionStyle() {
        captionStyler.apply(captions, SubtitlePresets(Prefs(this)).active())
        showCues(player.currentCues)
    }

    /** Assign favorites over the Live TV tab (from the guide); focus returns where it was when it closes. */
    override fun openAssignFavorites(playlistId: String, groupId: String?, channel: ChannelKey?) {
        // A window already open closes first, so its own close runs (its focus, the guide brightening again).
        if (panel.isOpen) panel.close()
        val back = currentFocus
        // The guide steps back while the window is open, as for Live FirePlayer Settings: one fade, drawn as one layer.
        stepBack(live.root)
        liveSettings.openAssignFavorites(playlistId, groupId, channel, onClose = {
            stepForward(live.root)
            // Back where it was. A popup it came from is gone by now, and a row of the groups column may show another
            // group after the guide read its data again: then the page puts focus back on its current entry.
            if (back != null && back.isShown && !isInside(back, live.sidebar)) back.requestFocus() else focusPage()
        })
    }

    /** Manage groups over the Live TV tab (a group's held window); focus returns to the page when it closes. */
    override fun openManageGroups(playlistId: String, groupId: String?) {
        if (panel.isOpen) panel.close()
        val back = currentFocus
        stepBack(live.root)
        liveSettings.openManageGroups(playlistId, groupId, onClose = {
            stepForward(live.root)
            // The column's rows may show other groups after the edits made there (hidden, moved): then the page puts
            // focus back on its current entry.
            if (back != null && back.isShown && !isInside(back, live.sidebar)) back.requestFocus() else focusPage()
        })
    }

    private fun openLink() {
        SidePanel.prompt(this, "Video link", "", inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) { text ->
            var link = text.trim()
            if (link.isEmpty()) return@prompt
            // "example.com/movie.mkv" typed without http:// still opens.
            if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(link)) link = "http://$link"
            val scheme = Uri.parse(link).scheme?.lowercase()
            if (scheme !in PLAYABLE_SCHEMES) {
                FireDialog.ask(this, "FirePlayer can't open $scheme:// links.", listOf<Pair<String, () -> Unit>>("OK" to {}))
                return@prompt
            }
            // A film starts: live playback stops (design-layout).
            player.stop()
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link), this, PlayerActivity::class.java)
            startActivity(intent)
        }
    }

    private fun addRow(list: LinearLayout, label: String, icon: Int, onClick: (View) -> Unit): View {
        val density = resources.displayMetrics.density
        val row = PageRow(this, icon).apply { text.text = label }
        row.setOnClickListener { onClick(it) }
        // Up from the top row of a column goes to its bottom one and the other way round, like the settings lists;
        // left and right go across, to the row beside it (or the rail).
        row.setOnKeyListener { v, keyCode, event -> Rows.verticalNav(list, v, keyCode, event) }
        list.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = (ROW_GAP_DP * density).toInt() },
        )
        return row
    }

    // ---- Look -----------------------------------------------------------------------------------------------------

    /** Gives the shell the look Settings now has (the settings window redraws its own). */
    private fun applyLook() {
        lookOn = Glass.on
        // Each look has its own background.
        wallpaper.glassLook = Glass.on
        // The rail's and Settings tab's glass blur the wallpaper (never the film, never dimmed), even when the look
        // changes over full screen; the live page re-makes its controls over the film itself.
        val film = filmOn
        filmOn = false
        for (row in settingsRows) (row as? PageRow)?.applyLook()
        rail.applyLook()
        filmOn = film && Glass.on
        live.applyLook()
        vodPages.applyLook()
        root.jumpDrawablesToCurrentState()
        clipForLook()
        // A page that stepped back behind a classic window comes forward if the look is switched to glass meanwhile:
        // the glass window shows it blurred, never faded as well (stepBack).
        if (Glass.on && ::settingsPage.isInitialized) {
            settingsPage.animate().cancel()
            settingsPage.alpha = 1f
            live.root.animate().cancel()
            live.root.alpha = 1f
        }
    }

    /** The glass settings window's shadow falls outside it, which this screen would otherwise cut off. */
    private fun clipForLook() {
        (panel.root.parent as? ViewGroup)?.clipChildren = !Glass.on
    }

    // ---- Live video placement (LivePage.Host) ---------------------------------------------------------------------

    override fun placeVideo(rect: RectF?, radius: Float, full: Boolean, animateMs: Long) {
        videoTarget = rect?.let { RectF(it) }
        // Captions follow the Subtitle style setting, read again each time full screen opens.
        if (full && !videoFull) captionStyler.apply(captions, SubtitlePresets(Prefs(this)).active())
        if (!full) captionPreview = false
        videoFull = full
        videoRadius = radius
        layoutVideo(animateMs)
        applyLiveDim()
    }

    /*
     * Moving smoothly (miniature <-> full screen, Android 7 and later, where a SurfaceView follows its view
     * transforms). The surface keeps its layout while the picture moves, and its transforms (scale and translation
     * from its top left) show it on the way, so it's never resized frame by frame; the wallpaper's hole follows it,
     * its corners rounding off or back, and full screen's dimmer fades with it. At the end the surface is laid out
     * where the picture stopped, untransformed: its one resize, made once the picture is exactly there. (Resized at
     * the start instead, the surface would flash at the wrong size for a frame: Fire OS sets a resized surface's
     * place without its transform and only puts the transform back on the next frame.) Captions, full screen's
     * dimmer and Display size's full-screen picture are the end's: they apply when it's reached.
     */
    private val motion = app.fireplayer.live.ui.PictureMotion()
    /** Where the surface is laid out; null while hidden. While the picture moves, its transforms show it elsewhere. */
    private var laidAt: LivePicture.Placement? = null
    private var mover: android.animation.ValueAnimator? = null
    /** Full screen as it shows: reached once a move there has ended. Captions and the dimmer wait for it. */
    private var pictureFull = false
    private val endHole = app.fireplayer.live.ui.PictureMotion.Box()
    private val holeNow = RectF()
    private val transformNow = FloatArray(4)
    /** Screen dimmer's display amount (0 to 1) as last read; it shows as much of it as of full screen. */
    private var dimDisplay = 0f
    /** Fast out, slow in: the picture sets off briskly and settles gently into place. */
    private val moveEasing by lazy { android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f) }
    private val moveFrame = android.animation.ValueAnimator.AnimatorUpdateListener { showMove(it.animatedFraction) }
    private val moveEnd = object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) {
            // A move cancelled for another one: that one carries on from where this one stood.
            if (mover !== animation) return
            mover = null
            layoutVideo()
        }
    }

    /**
     * The surface fits the video's shape inside its target (black bars come from the window behind it). Full screen
     * follows Display size, the VOD player's saved choice ([LivePicture]); a picture bigger than the screen (Fill
     * screen, Zoom) starts above and left of it and the screen crops it.
     *
     * With [animateMs] the picture moves there from where it is over that time (see [motion]). Placed again while
     * it moves (a layout pass, the picture's shape now known), it heads for the new place in the time left.
     */
    private fun layoutVideo(animateMs: Long = 0L) {
        val target = when {
            videoFull -> RectF(0f, 0f, root.width.toFloat(), root.height.toFloat())
            else -> videoTarget
        }
        if (target == null || target.isEmpty) {
            hideVideo()
            return
        }
        val size = player.status().videoSize
        val aspect = if (size.width > 0 && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 0f
        val mode = if (videoFull) videoPrefs.displayMode else PictureFit.NORMAL
        // The live picture on Home and Search fills its 16:9 window when the channel is 16:9 (no hairline of bars).
        val at = LivePicture.place(target.left, target.top, target.width(), target.height(), aspect, videoFull, mode, fill = pipOn && !videoFull)
        val radius = if (videoFull) 0f else videoRadius
        endHole.set(target.left, target.top, target.right, target.bottom)
        val running = mover
        // Already on its way there: it carries on undisturbed.
        if (running != null && motion.endsAt(at, endHole, radius, videoFull)) return
        val ms = when {
            animateMs > 0 -> animateMs
            running != null -> maxOf(running.duration - running.currentPlayTime, app.fireplayer.live.ui.PictureMotion.MIN_RETARGET_MS)
            else -> 0L
        }
        val canMove = ms > 0 && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N &&
            laidAt != null && surface.visibility == View.VISIBLE && !motion.isAt(at, endHole, radius, videoFull)
        if (canMove) {
            startMove(at, radius, ms)
        } else {
            stopMove()
            settleVideo(at, target, radius)
        }
    }

    private fun hideVideo() {
        stopMove()
        motion.clear()
        laidAt = null
        pictureFull = false
        wallpaper.setHole(null, 0f, full = multiviewOn)
        busy.away()
        resetTransform()
        if (surface.visibility != View.INVISIBLE) {
            val lp = surface.layoutParams as FrameLayout.LayoutParams
            lp.width = 1; lp.height = 1; lp.leftMargin = 0; lp.topMargin = 0
            surface.layoutParams = lp
            surface.visibility = View.INVISIBLE
        }
        captions.setCues(emptyList())
        captions.visibility = View.GONE
        showDimmer()
    }

    /** The picture at [at] at once (or at the end of a move): laid out there, untransformed, the hole around [target]. */
    private fun settleVideo(at: LivePicture.Placement, target: RectF, radius: Float) {
        motion.settle(at, endHole, radius, videoFull)
        resetTransform()
        val moved = laidAt != at
        if (moved || surface.visibility != View.VISIBLE) {
            val lp = surface.layoutParams as FrameLayout.LayoutParams
            lp.width = at.width
            lp.height = at.height
            lp.leftMargin = at.left
            lp.topMargin = at.top
            lp.gravity = Gravity.TOP or Gravity.START
            surface.layoutParams = lp
        }
        laidAt = at
        surface.visibility = View.VISIBLE
        wallpaper.setHole(target, radius, full = videoFull)
        busy.place(target.centerX(), target.centerY(), if (videoFull) 1f else 0f)
        // The live picture on Home or Search: where the page has moved its box, or unseen while it's covered.
        if (pipOn && !videoFull) showPipShift()
        pictureFull = videoFull
        // Full screen a moment: what's out of sight lets go of its memory (LiveRoom); back in the guide, the tab's wait.
        if (videoFull != roomFull && rail.selected == NavRail.Tab.LIVE) armLiveRoom(true, videoFull)
        // The full-screen controls' glass shows a copy of the picture: one of it where it is now.
        if (moved && filmOn) film?.frameChanged()
        // Captions only over the full-screen picture (in the miniature they'd be too small to read), and only over
        // the part of it on screen.
        if (videoFull) {
            val shown = LivePicture.onScreen(at, root.width, root.height)
            captions.layoutParams = FrameLayout.LayoutParams(shown.width, shown.height).apply {
                leftMargin = shown.left; topMargin = shown.top; gravity = Gravity.TOP or Gravity.START
            }
            captions.visibility = View.VISIBLE
            // What's on screen already, not just from the next line on.
            showCues(player.currentCues)
        } else {
            captions.setCues(emptyList())
            captions.visibility = View.GONE
        }
        showDimmer()
    }

    /** The picture sets off from where it is now (mid-move too) towards [at], arriving in [ms]. */
    private fun startMove(at: LivePicture.Placement, radius: Float, ms: Long) {
        stopMove()
        motion.start(at, endHole, radius, videoFull)
        // Captions come back at full screen's end; none on the way.
        pictureFull = false
        captions.setCues(emptyList())
        captions.visibility = View.GONE
        surface.pivotX = 0f
        surface.pivotY = 0f
        val move = android.animation.ValueAnimator.ofFloat(0f, 1f)
        move.duration = ms
        move.interpolator = moveEasing
        move.addUpdateListener(moveFrame)
        move.addListener(moveEnd)
        mover = move
        // Where it starts, in this very frame (no frame shows anything else).
        showMove(0f)
        move.start()
    }

    private fun stopMove() {
        val move = mover ?: return
        mover = null
        move.cancel()
    }

    /** One frame of a move, [f] of the way (eased): the surface's transform, the wallpaper's hole and the dimmer. */
    private fun showMove(f: Float) {
        val laid = laidAt ?: return
        motion.at(f)
        app.fireplayer.live.ui.PictureMotion.transform(laid, motion.picture, transformNow)
        surface.scaleX = transformNow[0]
        surface.scaleY = transformNow[1]
        surface.translationX = transformNow[2]
        surface.translationY = transformNow[3]
        val hole = motion.hole
        holeNow.set(hole.left, hole.top, hole.right, hole.bottom)
        // Not full screen until the end: the wallpaper shows around the picture all the way.
        wallpaper.setHole(holeNow, motion.radius, full = false)
        // The buffering symbol stays on the picture's middle and grows with it (translation and scale only).
        busy.place(holeNow.centerX(), holeNow.centerY(), motion.fullness)
        showDimmer()
    }

    private fun resetTransform() {
        surface.scaleX = 1f
        surface.scaleY = 1f
        surface.translationX = 0f
        surface.translationY = 0f
    }

    /** Full screen's dimmer, as much of it as of full screen shows (all of it there, fading in and out on the way). */
    private fun showDimmer() {
        val alpha = dimDisplay * motion.fullness
        liveDimmer.alpha = alpha
        liveDimmer.visibility = if (alpha > 0f) View.VISIBLE else View.GONE
    }

    /**
     * Screen dimmer, as the VOD player's applyDim: the full-screen live picture and its captions darken by the
     * display amount (never the miniature, the controls or the windows), and glass by the glass amount. Only glass
     * showing the live picture is dimmed ([glassShowsFilm]); the guide's glass over the wallpaper never is.
     */
    private fun applyLiveDim() {
        val amounts = dimmer.amounts()
        dimDisplay = amounts.display / 100f
        showDimmer()
        val glass = amounts.glass / 100f
        if (Glass.dim != glass) {
            Glass.dim = glass
            // Every glass shape reads it as it draws: draw them all again.
            redrawAll(window.decorView)
        }
    }

    private fun redrawAll(view: View) {
        view.invalidate()
        if (view is ViewGroup) for (i in 0 until view.childCount) redrawAll(view.getChildAt(i))
    }

    private var lastSize: VideoSize = VideoSize.UNKNOWN
    private val videoSizeListener = object : PlayerHost.Listener {
        override fun onLiveStatus(status: PlayerHost.Status) {
            // The screen stays on while a channel plays (the Fire TV screensaver would cover it), as films do; paused, it
            // may sleep, as a paused film's does.
            // A channel that's down to a try every half minute lets the screen sleep too (it would otherwise hold a
            // still picture on the TV for good).
            root.keepScreenOn = status.key != null && status.error == null && !status.paused && status.waiting == null &&
                status.refused == null
            film?.playing = status.playing
            showBusy(status)
            // The live picture on Home or Search can't play its channel (a picture this TV can't decode, say): the
            // page says so once (Live TV's own page, which would, is away).
            val error = status.error
            if (error == null) pipErrorSaid = null
            else if (pipOn && error != pipErrorSaid) {
                pipErrorSaid = error
                vodPages.current?.showNotice(error)
            }
            if (status.videoSize != lastSize) {
                lastSize = status.videoSize
                layoutVideo()
            }
        }

        override fun onLiveCues(cues: List<Cue>) = showCues(cues)
    }

    /** The buffering symbol and its text as [status] and the time so far say; asks itself again when they're next due. */
    private fun showBusy(status: PlayerHost.Status) {
        val refused = status.refused
        val waiting = status.key != null && status.error == null && !status.paused && !status.playing &&
            (status.buffering || status.reconnecting || refused != null)
        val next = busySigns.update(waiting, status.reconnecting, android.os.SystemClock.uptimeMillis(), refused = refused != null)
        root.removeCallbacks(busyCheck)
        if (next != app.fireplayer.ui.BusySigns.NONE) root.postDelayed(busyCheck, next)
        // A channel the provider refused says so in place of "Reconnecting…" (PlayerHost.Status.refused).
        busy.show(busySigns.spinner, if (busySigns.text) refused ?: status.waiting ?: "Reconnecting…" else null)
    }

    /**
     * Captions over the full-screen picture: text ones in the Subtitle style, picture ones (DVB subtitles) as they
     * come (they have no text to restyle).
     */
    private fun showCues(cues: List<Cue>) {
        if (!pictureFull) { captions.setCues(emptyList()); return }
        val (pictures, text) = cues.partition { it.bitmap != null }
        val shown = captionStyler.normalize(text) + pictures
        captions.setCues(if (shown.isEmpty() && captionPreview) captionStyler.sample() else shown)
    }

    override fun setChromeVisible(visible: Boolean) {
        // Full screen hides the rail; back in the guide, Live TV shows or slides it itself (setRailShown).
        findViewById<View>(R.id.rail_host).visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * Live TV lays out its own left side (the rail slides over it, the column and content move), so its page starts at
     * the screen's edge; other tabs start right of the rail's strip.
     */
    private fun pageInset(tab: NavRail.Tab) {
        (pages.layoutParams as ViewGroup.MarginLayoutParams).let { lp ->
            lp.marginStart = if (tab == NavRail.Tab.LIVE) 0 else rail.collapsedWidth
            pages.layoutParams = lp
        }
    }

    override fun setRailShown(shown: Boolean, animateMs: Long) {
        val host = findViewById<View>(R.id.rail_host)
        host.animate().cancel()
        if (shown) host.visibility = View.VISIBLE
        // By the strip's width (the rail's view is always the widened rail's width).
        val to = if (shown) 0f else -rail.collapsedWidth.toFloat()
        if (animateMs <= 0) {
            host.translationX = to
            if (!shown) host.visibility = View.INVISIBLE
            return
        }
        // The same easing as the column and the content sliding with it (one motion).
        host.animate().translationX(to).setDuration(animateMs).setInterpolator(moveEasing)
            .withEndAction { if (!shown) host.visibility = View.INVISIBLE }.start()
    }

    override fun focusRail() = rail.focusSelected()

    override fun railHasFocus(): Boolean = rail.hasFocus() || panel.isOpen

    // ---- Keys -----------------------------------------------------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isBack = event.keyCode == KeyEvent.KEYCODE_BACK
        if (!isBack && event.action == KeyEvent.ACTION_DOWN) consumeBackUp = false
        // A Back that closes a dialog: its release (and repeats) must not act again on what's underneath.
        if (isBack && event.action == KeyEvent.ACTION_DOWN && FireDialog.isShowing(this)) consumeBackUp = true
        // A small window (a list of choices, a question) open over the screen takes the keys first.
        if (FireDialog.dispatch(this, event)) return true
        if (isBack && consumeBackUp) {
            if (event.action == KeyEvent.ACTION_UP) consumeBackUp = false
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && rail.selected == NavRail.Tab.LIVE) live.noteKey()
        // Channel ▲ / ▼ change the playing channel wherever it plays ("Channel buttons only"; a dialog keeps them): on
        // Live TV, and in the live picture on Home and Search.
        if ((rail.selected == NavRail.Tab.LIVE || pipOn) && live.channelKey(event)) return true
        // Back held on the live picture's page (or its rail): the picture full screen on Live TV (its release too).
        if (isBack && (pipOn || pipBackHeld) && pipBack(event)) return true
        // Live TV (full screen, guide gestures) next, unless the settings window is open over it.
        if (!panel.isOpen && rail.selected == NavRail.Tab.LIVE && !rail.hasFocus() && live.dispatchKey(event)) return true
        // A film page's own Back (its small window, See all) before the shell's.
        if (!panel.isOpen && vodPages.isVodTab(rail.selected) && !rail.hasFocus() && vodPages.dispatchKey(event)) return true
        // Play/Pause still pauses the channel with a window, a small window or the rail in front, as the VOD player does.
        if (rail.selected == NavRail.Tab.LIVE && live.mediaKey(event)) return true
        // Media buttons this screen didn't use stay here. Left to Android, they go to the last app that played
        // something: on the Fire TV, Play/Pause over this screen started a paused Stremio film playing behind it.
        if (event.keyCode in MEDIA_KEYS) return true
        // The menu button (three lines) opens Settings, or Subtitle style as chosen in Settings, as in the player;
        // pressed again, it closes the window.
        if (event.keyCode == KeyEvent.KEYCODE_MENU && ::settingsPage.isInitialized) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (panel.isOpen) panel.close() else openMenuWindow()
            }
            return true
        }
        if (isBack) {
            if (event.action == KeyEvent.ACTION_DOWN && panel.isOpen) {
                consumeBackUp = true
                // A window opened from another (a playlist from the list) goes back to it.
                panel.back()
                return true
            }
            // Back from a page goes to the rail; Back on the rail leaves the app.
            if (!panel.isOpen && !rail.hasFocus()) {
                if (event.action == KeyEvent.ACTION_UP && !backFromStudio() && !backFromService()) rail.focusSelected()
                return true
            }
            // On the rail over a title page: back into the page (its place kept), never out of the app.
            if (!panel.isOpen && rail.hasFocus() && vodPages.isVodTab(rail.selected) && vodPages.hasTitle()) {
                if (event.action == KeyEvent.ACTION_UP) focusPage()
                return true
            }
        }
        if (!panel.isOpen) return super.dispatchKeyEvent(event)
        val before = currentFocus
        val handled = super.dispatchKeyEvent(event)
        // Keep focus inside the window while it's open.
        if (panel.isOpen && !panel.root.hasFocus()) {
            if (before != null && before.isAttachedToWindow) before.requestFocus() else panel.focusRow(0)
        }
        return handled
    }

    /**
     * Menu: Settings (App settings' categories; Live FirePlayer Settings over Live TV) or Subtitle style, as chosen,
     * over whichever tab shows. Focus returns to where it was when the window closes; over the Settings tab the page
     * dims behind it, as when a row opens a window.
     */
    private fun openMenuWindow() {
        val style = Prefs(this).menuOpensStyle
        // On the Live TV tab, Settings means Live FirePlayer Settings (user spec).
        if (!style && rail.selected == NavRail.Tab.LIVE) return openPlayerSettings()
        val title = if (style) "Subtitle style" else SettingsCategory.LIST_TITLE
        val render: SidePanel.() -> Unit = {
            if (style) settingsPanels.renderSubtitleStyle(this, onStyleChanged = { applyCaptionStyle() }, onAssChanged = { applyCaptionStyle() })
            else settingsPanels.renderCategories(this)
        }
        val back = currentFocus
        if (rail.selected == NavRail.Tab.SETTINGS) return openPanel(title, back ?: settingsRows.first(), render)
        panel.open(title, { if (back != null && back.isShown) back.requestFocus() else focusPage() }, render = render)
    }

    companion object {
        /** On the intent that brings this screen back from the player: open Search (films and shows). */
        const val EXTRA_SEARCH = "app.fireplayer.extra.OPEN_SEARCH"
        /** Link types the player can open; others (rtsp://, rtmp://) would need extra modules. */
        private val PLAYABLE_SCHEMES = setOf("http", "https", "file", "content")
        /** A second play this soon after one is a repeated OK while the player opens, not a new play. */
        private const val LAUNCH_GUARD_MS = 3_000L
        /** The remote's (and Alexa's) media buttons. */
        private val MEDIA_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        )

        // The Settings tab's sizes, in dp (see settingsColumns).
        private const val ROW_DP = PageRow.ROW_DP.toInt()
        private const val ROW_GAP_DP = PageRow.GAP_DP.toInt()
        private const val TITLE_DP = 34
        private const val TITLE_GAP_DP = 16
        private const val SAFE_EDGE_DP = 27
        private const val COLUMN_DP = 240
        private const val ONE_COLUMN_DP = 380
        private const val COLUMN_GAP_DP = 16
    }
}
