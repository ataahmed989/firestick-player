package app.fireplayer.vod.ui

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.fireplayer.live.ui.HoldPopup
import app.fireplayer.live.ui.PressHold
import app.fireplayer.shell.Tokens
import app.fireplayer.ui.ImageLoader
import app.fireplayer.ui.SidePanel
import app.fireplayer.ui.Spring
import app.fireplayer.vod.Cancel
import app.fireplayer.vod.PlayRequest
import app.fireplayer.vod.SourceList
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.Sources
import app.fireplayer.vod.SourcesRepository
import app.fireplayer.vod.StartAt
import app.fireplayer.vod.LookupWhy
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.play.MainTicker
import app.fireplayer.vod.play.PlayOutcomes
import app.fireplayer.vod.play.PlayRules
import app.fireplayer.vod.play.PlayWait
import app.fireplayer.vod.play.VodLaunch
import app.fireplayer.vod.Card
import app.fireplayer.vod.Episode
import app.fireplayer.vod.Kind
import app.fireplayer.vod.PlayTarget
import app.fireplayer.vod.RowSpec
import app.fireplayer.vod.RowStyle
import app.fireplayer.vod.Season
import app.fireplayer.vod.Source
import app.fireplayer.vod.Title
import app.fireplayer.vod.TitleDetails
import app.fireplayer.vod.VodRepository
import app.fireplayer.vod.data.Callouts
import app.fireplayer.vod.data.CinemetaDetails
import app.fireplayer.vod.data.EpisodeMarks
import app.fireplayer.vod.data.TitleMarks
import app.fireplayer.vod.data.VodArt
import app.fireplayer.vod.hasImdbId
import app.fireplayer.vod.playTarget
import app.fireplayer.vod.ui.TitleRules.Dir
import app.fireplayer.vod.ui.TitleRules.Part
import java.util.Calendar

/**
 * One title's page (design-brief.md §6.4; laid out as Design-References/redesign-2026-11-19, concept 3 without its
 * Episodes button), full screen over its tab beside the rail: the backdrop at the top right behind everything
 * ([HeaderArt], never moving); the text block ([TitleHeader]: the large title or logo, the facts and one callout, the
 * story, the source line); under it the stage: the action column on the left ([TitleButtons]: Play / Resume, Play
 * from beginning, Sources, then (+) and (⋯)) and, right of it, a show's season dropdown ([SeasonDropdown]), the
 * "Episodes" heading and its episode pods ([EpisodeRow]), or a film's More Like This. A show's More Like This is
 * under the stage. It draws first from what the poster's [Title] says, then from the repository's details as they
 * come.
 *
 * Moving ([TitleRules.next]): Up and Down inside the column; Right from it to the episodes (a film: More Like This)
 * and Left from their first card back to it; Up from the episodes to the season dropdown, whose list (OK) changes the
 * season; Down to a show's More Like This, for which the page glides up, and back. Left from the column goes to the
 * rail. Hold OK on an episode or a poster opens the shared small window ([HoldWindow]); (⋯) opens it with Mark
 * watched, Remove from Continue Watching and Details (a [SidePanel] window, where the cast is too).
 *
 * Sources and playback (step 3, design-brief.md §6.4-6.5): what Play would start is looked up after 1.5 s on the page
 * (0.5 s with a resume point, or when [soon]), never from the rows; the source line then says which source it would
 * be, and Sources its count. Play, Resume, Play from beginning and an episode's OK wait for both add-ons ([PlayWait]:
 * the pill counts, the source line becomes the strip, OK again plays the best so far, Back cancels), then hand the play
 * to FirePlayer's player ([Host.play]). Sources opens the wide Sources panel ([SourcesPanel]). Back from a play that
 * gave up lands here with the reason in the source line and focus on Sources.
 *
 * Lean: the backdrop is decoded once at half the screen with its fade baked in, stills at their shown size, posters as
 * the rows'; only translations and scales change while anything moves; pictures, loads and timers stop when the page is
 * covered or closed.
 */
class TitlePage(
    private val ctx: Context,
    private val t: Tokens,
    private val host: Host,
    private val opened: Title,
    /** Opened from Next Episodes or a Continue card's small window: sources are looked up after 0.5 s. */
    private val soon: Boolean = false,
) : TitleRow.Listener, TitleButtons.Listener {

    interface Host {
        val repo: VodRepository?
        val hold: HoldWindow
        val sidePanel: SidePanel
        fun showNotice(text: String)
        fun focusRail()
        /** OK on a More Like This poster (its IMDb id known): that title's page, on top of this one. */
        fun openNext(title: Title)
        /** A film or an episode starts in FirePlayer's player (the shell makes room first: film mode). */
        fun play(request: PlayRequest)
    }

    /** Where focus was in the page, to come back to it. */
    internal data class Saved(
        val part: Part,
        val button: TitleButtons.Which,
        val season: Int?,
        val episodeId: String?,
        val episodeIndex: Int,
        val moreId: String?,
        val moreIndex: Int,
    )

    private val isShow = opened.kind == Kind.SERIES
    private val main = Handler(Looper.getMainLooper())

    /** Page-sized; clips at the page's left edge (a show's More Like This runs to it, as the tabs' rows do). */
    val root = FrameLayout(ctx).apply { clipChildren = true }
    private val backdrop = HeaderArt(ctx, HeaderArt.ArtSpec.TITLE)
    private val column = PageColumn(ctx, t, VodSizes.TITLE_HEADER_H, VodSizes.TITLE_HEADER_GAP)
    private val header = TitleHeader(ctx, t)
    /** Under the text block: the action column and what's right of it. */
    private val stage = FrameLayout(ctx).apply {
        clipChildren = false
        clipToPadding = false
    }
    private val buttons = TitleButtons(ctx, t, this)
    private val seasonButton = SeasonDropdown.button(ctx, t)
    private val heading = t.label(ctx, VodSizes.EPISODES_HEADING).apply { text = "Episodes" }
    /** The season dropdown and the heading beside it, above the pods. */
    private val headRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        visibility = View.GONE
    }
    private val seasonList = SeasonList(ctx, t)
    private val episodes: EpisodeRow = EpisodeRow(ctx, t, podListener())
    private val more = TitleRow(ctx, t, spec("more", "More Like This", RowStyle.POSTER), this)
    /** The column's upward offset, px: a show's page glides up for More Like This. */
    private val glide = Spring(VodSizes.PAGE_OMEGA, VodSizes.PAGE_SETTLE) { column.translationY = -it }
    private val press = PressHold(ViewConfiguration.getLongPressTimeout().toLong())

    /** What the poster says, its description read as the details read Cinemeta's (entities, a cut word ended). */
    private var details = TitleDetails.of(opened.copy(description = CinemetaDetails.story(CinemetaDetails.clean(opened.description))))
    private var seasons: List<Season> = emptyList()
    /** The season the dropdown says, and the one whose pods show. */
    private var shownSeason: Int? = null
    private var cardsSeason: Int? = null
    /** The Play target the pods' row last started at (it moves on with the target while focus is elsewhere). */
    private var rowTarget: String? = null
    private var seasonArmed = false
    private var shownEpisodes: List<Episode> = emptyList()
    /** The shown season's cards, all of them: the row holds the first ones only ([VodSizes.episodeWindow]). */
    private var seasonCards: List<Card> = emptyList()
    private var target: PlayTarget? = null
    private var pendingMore: List<Card>? = null
    /** Where focus is (or was last) in the page. */
    private var part = Part.BUTTONS
    /** Focus to bring back once the rows it was in have their cards again (a page made again). */
    private var restore: Saved? = null
    private var restoreHolder: View? = null

    private var detailsCancel: Cancel? = null
    private var moreCancel: Cancel? = null
    private var resolveCancel: Cancel? = null
    /** The More Like This poster a pending [resolve] is for, or -1: it acts only while focus is still on it. */
    private var resolving = -1
    private var detailsFresh = false
    private var moreFresh = false
    private var paused = false
    private var released = false
    /** The library as last shown ([VodRepository.libraryVersion]): its listener call for a change shown already is skipped. */
    private var shownLibrary = -1L

    /** The title box: waiting (at most [VodSizes.LOGO_WAIT_MS]) for the logo, then the logo or the name for the visit. */
    private enum class TitleMode { WAITING, LOGO, NAME }
    private var mode = TitleMode.WAITING
    private var logoUrl: String? = null
    private var backdropUrl: String? = null

    // ---- Sources (step 3) ----
    private val sources: SourcesRepository? = try {
        Sources.get(ctx)
    } catch (e: RuntimeException) {
        null
    }
    /** The page's look-up of what Play would start, and that target's key. */
    private var dwell: Cancel? = null
    private var dwellKey: String? = null
    /** What the add-ons said so far for [dwellKey]. */
    private var shownList: SourceList? = null
    /** Play waiting for the add-ons; and where it was pressed (Play, or an episode card). */
    private val wait: PlayWait? = sources?.let { PlayWait(it, MainTicker, waitListener()) }
    private var waitFromButtons = true
    private var waitStart: StartAt = StartAt.Beginning
    private var waitNext: EpisodeRef? = null
    /** The strip while waiting; the reason the last play stopped (shown until the page is left or plays again). */
    private var strip: String? = null
    private var stopped: String? = null
    /** The film or episode handed to the player last, to hear on return why it stopped (if it did). */
    private var handedOff: SourceTarget? = null
    private var sourcesPanel: SourcesPanel? = null
    private var panelLookup: Cancel? = null

    init {
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val fill = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(backdrop, FrameLayout.LayoutParams(fill, fill))
        column.header = header
        column.onRowsMoved = { onRowsMoved() }
        val rowTop = t.dp(VodSizes.STAGE_ROW_TOP)
        val gap = t.dp(VodSizes.POD_GAP)
        val right = t.dp(VodSizes.stageX())
        stage.addView(buttons, FrameLayout.LayoutParams(t.dp(VodSizes.ACTIONS_W), wrap).apply {
            leftMargin = t.dp(VodSizes.titleTextX())
            topMargin = rowTop
        })
        if (isShow) {
            // The pods' box starts a gap's width left of the first pod and holds the room above and below them.
            stage.addView(episodes, FrameLayout.LayoutParams(fill, t.dp(VodSizes.podsBandH())).apply {
                leftMargin = right - gap
                topMargin = rowTop - t.dp(VodSizes.POD_ROOM)
            })
            val seasonH = t.dp(VodSizes.SEASON_H)
            headRow.addView(seasonButton, LinearLayout.LayoutParams(wrap, seasonH).apply { rightMargin = t.dp(VodSizes.SEASON_HEADING_GAP) })
            headRow.addView(heading, LinearLayout.LayoutParams(wrap, wrap))
            stage.addView(headRow, FrameLayout.LayoutParams(wrap, seasonH).apply {
                leftMargin = right
                topMargin = t.dp(VodSizes.SEASON_TOP)
            })
            seasonButton.visibility = View.GONE
            Tokens.focusEffect(seasonButton, Tokens.GROW_PILL, null) { has -> if (has) onSeasonFocused() }
            seasonButton.setOnKeyListener { _, keyCode, event -> onSeasonKey(keyCode, event) }
            column.addView(stage, ViewGroup.LayoutParams(fill, t.dp(VodSizes.showStageH())))
            column.addView(more, ViewGroup.LayoutParams(fill, more.bandHeight))
        } else {
            // A film's More Like This sits beside the column: its strip starts where a show's pods do, cut a gap's
            // width left of the first poster (the row itself starts its strip at the page's content x).
            val box = ClipBox(ctx)
            box.addView(more, FrameLayout.LayoutParams(fill, more.bandHeight).apply { leftMargin = gap - t.dp(VodSizes.PAGE_X) })
            stage.addView(box, FrameLayout.LayoutParams(fill, more.bandHeight).apply {
                leftMargin = right - gap
                topMargin = t.dp(VodSizes.filmMoreTop())
            })
            column.addView(stage, ViewGroup.LayoutParams(fill, t.dp(VodSizes.filmStageH())))
        }
        root.addView(column, FrameLayout.LayoutParams(fill, wrap))
        // The season list opens over the page and never moves with it. Its layer is the page's size and doesn't clip,
        // so the list's soft shadow shows (the page itself clips each of its children at that child's edges).
        val over = FrameLayout(ctx).apply {
            clipChildren = false
            clipToPadding = false
        }
        over.addView(seasonList, FrameLayout.LayoutParams(wrap, wrap))
        root.addView(over, FrameLayout.LayoutParams(fill, fill))
        // The backdrop covers the whole screen: its part under the rail's strip lies left of the page.
        backdrop.addOnLayoutChangeListener { v, l, tp, r, b, _, _, _, _ ->
            backdrop.frame = RectF(-t.dpf(VodSizes.RAIL), 0f, (r - l).toFloat(), (b - tp).toFloat())
            if (v.width > 0) v.invalidate()
        }
        header.logo.onArrived = { onLogo() }
    }

    private fun spec(key: String, label: String, style: RowStyle) = RowSpec("title.$key", label, style, Source.Fixed("title.$key"))

    // ---- Lifecycle --------------------------------------------------------------------------------------------------

    /** Shown for the first time: what the poster says at once, then the details, More Like This and pictures. */
    fun start() {
        refreshAll()
        startLoads()
        startArt(opened.background)
        requestLogo(opened.logo)
        main.postDelayed(decideTitle, VodSizes.LOGO_WAIT_MS)
    }

    /** Covered by another page ([hide]), or the screen stopped: loads, timers and pictures stop. */
    fun pause(hide: Boolean) {
        // The season list never outlives what's under it: closed, focus back on the dropdown (unless the page is
        // being covered or closed: whatever does that places focus itself).
        seasonList.close(fade = false, focusBack = !hide)
        if (hide) root.visibility = View.GONE
        if (paused) return
        paused = true
        stopLoads()
        stopSources()
        main.removeCallbacksAndMessages(null)
        // The moment for the logo has passed.
        if (mode == TitleMode.WAITING) decide(TitleMode.NAME)
        if (glide.moving) glide.snap(glide.target)
        // Pictures go, and the ones still queued for this page aren't fetched.
        episodes.motion.settle()
        episodes.releaseImages(cancel = true)
        more.motion.settle()
        more.releaseImages(cancel = true)
        press.cancel()
        backdrop.release(cancel = true)
        ImageLoader.cancel(header.logo)
    }

    /** Shown again ([show]: back on top of the stack), or the screen started: pictures back, loads finished. */
    fun resume(show: Boolean) {
        if (show) root.visibility = View.VISIBLE
        if (!paused || released) return
        paused = false
        if (!detailsFresh || !moreFresh) startLoads()
        backdropUrl?.let { backdrop.show(it) }
        if (mode == TitleMode.LOGO) logoUrl?.let { loadLogo(it) }
        // Back from the player: a play that gave up says why, and focus waits on Sources.
        handedOff?.let { PlayOutcomes.take(it) }?.let { why ->
            stopped = why
            buttons.focus(TitleButtons.Which.SOURCES)
        }
        handedOff = null
        refreshAll()
        updateImages()
    }

    /** Gone for good: loads stop, pictures go, and the big ones leave memory too. */
    fun release() {
        if (released) return
        pause(hide = true)
        released = true
        glide.stop()
        backdropUrl?.let { url ->
            ImageLoader.drop(backdrop.keyFor(url))
            VodArt.backgroundAt(url, VodArt.Size.SMALL)?.let { small ->
                ImageLoader.drop(ImageLoader.keyOf(small, VodSizes.TITLE_PREVIEW_W, VodSizes.TITLE_PREVIEW_H, HeaderArt.ArtSpec.TITLE_PREVIEW.keySuffix))
            }
        }
        logoUrl?.let { ImageLoader.drop(logoKey(it)) }
    }

    fun applyLook() {
        header.applyLook()
        buttons.applyLook()
        seasonButton.applyLook()
        seasonList.applyLook()
        episodes.applyLook()
        more.applyLook()
    }

    /** Memory is short: only the backdrop and the row focus is in keep their pictures. */
    fun trim() {
        if (part != Part.EPISODES) episodes.releaseImages()
        if (part != Part.MORE) more.releaseImages()
    }

    /** A library change: shown, unless this page showed it already (its own mark, redrawn at once by [marked]). */
    fun onLibraryChanged() {
        if (paused || released) return
        if (host.repo?.libraryVersion() == shownLibrary) return
        refreshAll()
    }

    /**
     * The viewer's own mark on this page: its ✓ marks, progress bars, chip and Play shown at once, in place (the
     * library is in memory already), not when the listener's call comes; that call then finds nothing new.
     */
    private fun marked(notice: String) {
        if (!paused && !released) refreshAll()
        host.showNotice(notice)
    }

    private fun startLoads() {
        val r = host.repo ?: return
        stopLoads()
        if (!detailsFresh) {
            detailsCancel = r.details(opened) { d, fresh -> if (!released) onDetails(d, fresh) }
        }
        if (!moreFresh) {
            moreCancel = r.moreLikeThis(opened) { cards, fresh -> if (!released) onMore(cards, fresh) }
        }
    }

    private fun stopLoads() {
        detailsCancel?.cancel()
        detailsCancel = null
        moreCancel?.cancel()
        moreCancel = null
        cancelResolve()
    }

    private fun cancelResolve() {
        resolveCancel?.cancel()
        resolveCancel = null
        resolving = -1
    }

    // ---- Data -------------------------------------------------------------------------------------------------------

    private fun onDetails(d: TitleDetails, fresh: Boolean) {
        // The page is about the title it was opened for: its id and kind stay.
        details = if (d.title.id == opened.id && d.title.kind == opened.kind) d else d.copy(title = d.title.copy(id = opened.id, kind = opened.kind))
        if (fresh) detailsFresh = true
        startArt(details.title.background)
        if (mode == TitleMode.WAITING) {
            requestLogo(details.title.logo)
            // The last answer has no logo: no need to wait for one.
            if (fresh && logoUrl == null) decide(TitleMode.NAME)
        } else if (logoUrl == null) {
            // Known too late for this visit: fetched to disk, so the next visit can show it.
            details.title.logo?.let {
                logoUrl = it
                ImageLoader.prefetch(it)
            }
        }
        seasons = TitleRules.seasonOrder(details.seasons)
        refreshAll()
        applyRestore()
    }

    private fun onMore(cards: List<Card>, fresh: Boolean) {
        if (fresh) moreFresh = true
        val list = cards.filter { it.title.id != opened.id }.distinctBy { it.title.id }.take(VodSizes.MLT_CARDS)
        // An empty stored answer: the fresh one follows.
        if (list.isEmpty() && !fresh) return
        // The viewer is in the row and the new cards would move what they're on: held until they leave it.
        if (!RowRules.keepsFocus(more.focusedIndex(), more.cards, false, list, false)) {
            pendingMore = list
            return
        }
        showMore(list)
        applyRestore()
    }

    private fun showMore(list: List<Card>) {
        pendingMore = null
        if (list.isEmpty()) {
            if (more.focusedIndex() >= 0) focusPart(TitleRules.next(Part.MORE, Dir.UP, parts(), isShow) ?: Part.BUTTONS)
            more.releaseImages()
            more.visibility = View.GONE
            more.bind(emptyList(), false)
            return
        }
        val f = RowRules.focusAfter(more.focusedIndex(), more.cards.size, false, list.size, false)
        if (f >= 0) more.items.getOrNull(f)?.requestFocus()
        more.bind(list, false)
        if (more.visibility != View.VISIBLE) {
            more.chosen = 0
            more.motion.snap(0, isGrown = false)
            more.visibility = View.VISIBLE
        }
        updateImages()
    }

    /** Everything that follows the details and the library: the buttons, the header's words, the seasons and cards. */
    private fun refreshAll() {
        val r = host.repo
        shownLibrary = r?.libraryVersion() ?: -1L
        val now = System.currentTimeMillis()
        val year = thisYear()
        val last = r?.lastPlayed(opened)
        val play: TitleRules.Play
        var filmWatched = false
        val out: Boolean
        if (isShow) {
            // The same next episode as the show's Continue card ([app.fireplayer.vod.UpNext]).
            target = playTarget(details.episodes, last, now) { r?.isEpisodeWatched(it) == true }
            play = TitleRules.showPlay(details, target, year)
            out = details.episodes.isEmpty() || target != null
        } else {
            target = null
            play = TitleRules.filmPlay(details, last, now, year)
            filmWatched = r?.isWatched(opened.id) == true
            out = play.enabled
        }
        buttons.setPlay(play)
        buttons.setRestart(play.restart)
        buttons.setSources(out)
        buttons.setMyList(r?.inMyList(opened.id) == true)
        // The repository's rule for the one chip (the same one its tests check against the research's fixtures).
        val callout = Callouts.choose(details, filmWatched, { r?.isEpisodeWatched(it) == true }, target, now)
        // TMDB's overview once it's there, else Cinemeta's (a story shown is never blanked); new words fade in.
        val story = TitleRules.story(details).ifEmpty { header.storyShown }
        header.show(TitleRules.facts(details), callout?.let { TitleRules.calloutText(it, year) }, story)
        if (mode == TitleMode.NAME) header.showName(details.title.name)
        if (isShow) refreshSeasons()
        lookUpSources(play.enabled, play.restart)
        showSourceLine()
    }

    // ---- Sources and playing ----------------------------------------------------------------------------------------

    /** What Play would start: the film, or the show's Play target episode; null when it can't play now. */
    private fun playTargetNow(): SourceTarget? {
        if (isShow) {
            val ep = target?.episode ?: return null
            return SourceTarget(details.title, ep.ref)
        }
        return SourceTarget(details.title, null)
    }

    /**
     * The page's look-up of what Play would start (§6.4): after 1.5 s, 0.5 s with a resume point ([resumable]) or when
     * opened [soon]; again only when the target changes (a show's episodes known, an episode watched).
     */
    private fun lookUpSources(enabled: Boolean, resumable: Boolean) {
        val s = sources ?: return
        if (paused || released) return
        val tgt = if (enabled) playTargetNow() else null
        val key = tgt?.key
        if (key == dwellKey) return
        dwell?.cancel()
        dwell = null
        dwellKey = key
        shownList = null
        buttons.setSourcesLabel(PlayRules.sourcesLabel(null))
        if (tgt == null) return
        dwell = s.lookUp(tgt, PlayRules.pageLookup(resumable, soon)) { list -> onSources(tgt, list) }
    }

    private fun onSources(tgt: SourceTarget, list: SourceList) {
        if (released || tgt.key != dwellKey) return
        shownList = list
        buttons.setSourcesLabel(PlayRules.sourcesLabel(list))
        showSourceLine()
    }

    /** The source line: the strip while Play waits, why the last play stopped, else which source Play would start. */
    private fun showSourceLine() {
        header.setSourceLine(strip ?: stopped ?: PlayRules.sourceLine(shownList))
    }

    private fun stopSources() {
        dwell?.cancel()
        dwell = null
        dwellKey = null
        panelLookup?.cancel()
        panelLookup = null
        if (wait?.isWaiting == true) {
            wait.release()
            endWait()
        }
    }

    /**
     * Plays [tgt] from [start] once the add-ons have answered ([PlayWait]); pressed again for the same target while it
     * waits, the best so far plays. [fromButtons] when pressed on Play (its pill counts), else an episode card.
     */
    private fun play(tgt: SourceTarget, start: StartAt, fromButtons: Boolean) {
        val w = wait
        if (w == null) {
            host.showNotice(NO_SOURCES)
            return
        }
        if (w.waitingFor?.key != tgt.key) {
            // Waiting for something else (Play, then another episode's OK): that wait ends first, its pill too.
            if (w.isWaiting) {
                w.release()
                endWait()
            }
            waitStart = start
            waitFromButtons = fromButtons
            waitNext = if (isShow) tgt.episode?.let { PlayRules.nextEpisode(details.episodes, it, System.currentTimeMillis())?.ref } else null
        }
        stopped = null
        w.press(tgt)
    }

    private fun waitListener() = object : PlayWait.Listener {
        override fun onWaiting(label: String, fraction: Float, strip: String?) {
            if (released) return
            if (waitFromButtons) buttons.setWaiting(label, fraction)
            this@TitlePage.strip = strip ?: label
            showSourceLine()
        }

        override fun onPlay(target: SourceTarget, list: SourceList) {
            endWait()
            if (released || paused) return
            hand(VodLaunch.fromTitlePage(target, waitStart, list, sources?.library, next = waitNext))
        }

        override fun onEnded(message: String?) {
            endWait()
            if (message != null) {
                stopped = message
                showSourceLine()
                host.showNotice(message)
            }
        }
    }

    private fun endWait() {
        strip = null
        buttons.setWaiting(null)
        showSourceLine()
    }

    /** Back on the page: a wait in progress ends (true); nothing else happens then. */
    fun cancelWait(): Boolean = wait?.cancel() == true

    private fun hand(request: PlayRequest) {
        handedOff = request.target
        host.play(request)
    }

    /** Play, Resume or Play from beginning ([fromBeginning]) on the buttons. */
    private fun playButton(fromBeginning: Boolean) {
        val tgt = playTargetNow() ?: return
        play(tgt, if (fromBeginning) StartAt.Beginning else currentStart(), fromButtons = true)
    }

    /** OK on an aired episode card: that episode, from where it stopped if it can resume. */
    private fun playEpisode(ep: Episode) {
        val tgt = SourceTarget(details.title, ep.ref)
        play(tgt, PlayRules.startAt(host.repo?.positionOf(ep), fromBeginning = false), fromButtons = false)
    }

    /** The Sources panel for what Play would start (a show: its episode, named in the title). */
    private fun openSources() {
        val s = sources ?: return
        val tgt = playTargetNow() ?: return
        val activity = ctx as? android.app.Activity ?: return
        val panel = sourcesPanel ?: SourcesPanel(activity, host.sidePanel).also { sourcesPanel = it }
        val ep = tgt.episode
        val title = if (ep == null) "Sources" else {
            val name = target?.episode?.name?.takeIf { it.isNotBlank() }
            "Sources · ${TitleRules.episodeCode(ep)}" + (name?.let { " \"$it\"" } ?: "")
        }
        val back = buttons.viewOf(TitleButtons.Which.SOURCES)
        panelLookup?.cancel()
        panel.open(title, s.cached(tgt) ?: shownList, onClose = {
            panelLookup?.cancel()
            panelLookup = null
            if (!released && back.isShown) back.requestFocus() else if (!released) takeFocusBack()
        }) { source, fromBeginning ->
            host.sidePanel.close()
            val start = if (fromBeginning) StartAt.Beginning else currentStart()
            stopped = null
            hand(VodLaunch.fromTitlePage(tgt, start, s.cached(tgt) ?: shownList, s.library, chosen = source,
                next = ep?.let { PlayRules.nextEpisode(details.episodes, it, System.currentTimeMillis())?.ref }))
        }
        // Asked now (never held back by a budget), joining the page's look-up when it's running.
        panelLookup = s.lookUp(tgt, LookupWhy.SOURCES) { list ->
            if (tgt.key == dwellKey) onSources(tgt, list)
            panel.update(list)
        }
    }

    /** Where Play would start now: the resume point, else the beginning. */
    private fun currentStart(): StartAt = if (isShow) {
        val pt = target
        if (pt != null && pt.action == app.fireplayer.vod.PlayAction.RESUME) PlayRules.startAt(pt.position, false) else StartAt.Beginning
    } else {
        PlayRules.startAt(host.repo?.lastPlayed(opened))
    }

    private fun refreshSeasons() {
        val labels = seasons.map { TitleRules.seasonLabel(it.number) }
        // The list shows other seasons than there are now: closed (focus back on the dropdown).
        if (seasonList.isOpen && seasonList.labels != labels) seasonList.close()
        if (TitleRules.showsSeasonPicker(seasons)) {
            seasonButton.visibility = View.VISIBLE
        } else if (seasonButton.visibility == View.VISIBLE) {
            if (seasonButton.isFocused) buttons.focusLast()
            seasonButton.visibility = View.GONE
        }
        if (shownSeason == null || seasons.none { it.number == shownSeason }) {
            // The page opens on the Play target's season.
            shownSeason = TitleRules.openingSeason(seasons, target?.episode)
        }
        shownSeason?.let { seasonButton.set(TitleRules.seasonLabel(it), null) }
        showSeasonCards()
    }

    private fun seasonIndex(n: Int?): Int = seasons.indexOfFirst { it.number == n }

    /** The shown season's episode pods, filled in place (a new season starts at its first pod). */
    private fun showSeasonCards() {
        val r = host.repo
        val season = shownSeason
        val eps = seasons.firstOrNull { it.number == season }?.episodes.orEmpty()
        if (eps.isEmpty()) {
            if (episodes.visibility == View.VISIBLE) {
                if (episodes.focusedIndex() >= 0) buttons.focusLast()
                episodes.releaseImages()
                episodes.visibility = View.GONE
                episodes.bind(emptyList())
            }
            // The dropdown stays while there are seasons to choose; the heading goes with the pods.
            heading.visibility = View.GONE
            headRow.visibility = if (seasonButton.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            shownEpisodes = emptyList()
            seasonCards = emptyList()
            cardsSeason = null
            return
        }
        val now = System.currentTimeMillis()
        val year = thisYear()
        val cards = eps.map { ep ->
            TitleRules.episodeCard(details.title, ep, r?.isEpisodeWatched(ep) == true, r?.positionOf(ep), now, year)
        }
        val newSeason = cardsSeason != season
        val targetId = target?.episode?.id
        shownEpisodes = eps
        seasonCards = cards
        cardsSeason = season
        // A long season (a daily show's 200) is held a window at a time: the first pods, more as focus nears the end.
        val n: Int
        var place = false
        if (newSeason) {
            val inRow = episodes.focusedIndex() >= 0
            if (inRow) {
                // Focus on a pod that stays (the first) before any go.
                episodes.items.getOrNull(0)?.requestFocus()
                episodes.chosen = 0
            } else {
                // At rest the row starts at the Play target when it's in this season (Right then only grows it), else E1.
                episodes.chosen = TitleRules.focusIndex(eps, target?.episode)
            }
            n = VodSizes.episodeWindow(cards.size, episodes.chosen, 0)
            // The old season's pictures, and their downloads still queued, go.
            episodes.releaseImages(cancel = true)
            episodes.motion.snap(episodes.chosen, isGrown = inRow)
        } else {
            // At rest the row moves on with the Play target (an episode watched: the next one comes first).
            if (targetId != rowTarget && part != Part.EPISODES && episodes.focusedIndex() < 0 && !host.hold.isOpen) {
                val i = eps.indexOfFirst { it.id == targetId }
                if (i >= 0 && i != episodes.chosen) {
                    episodes.chosen = i
                    place = true
                }
            }
            n = VodSizes.episodeWindow(cards.size, episodes.chosen, episodes.items.size)
            val f = RowRules.focusAfter(episodes.focusedIndex(), episodes.cards.size, false, n, false)
            if (f >= 0) episodes.items.getOrNull(f)?.requestFocus()
        }
        rowTarget = targetId
        // Bound with the row's place kept (binding keeps the chosen pod inside what it holds).
        val at = episodes.chosen
        episodes.bind(cards.subList(0, n))
        if (place) {
            episodes.chosen = at.coerceIn(0, n - 1)
            episodes.motion.snap(episodes.chosen, isGrown = false)
        }
        if (episodes.visibility != View.VISIBLE) {
            episodes.motion.snap(episodes.chosen, isGrown = false)
            episodes.visibility = View.VISIBLE
        }
        heading.visibility = View.VISIBLE
        headRow.visibility = View.VISIBLE
        // The header follows the chosen pod's new words.
        if (episodes.focusedIndex() >= 0) main.post(follow)
        updateImages()
    }

    // ---- Art and the title box -------------------------------------------------------------------------------------

    /**
     * The backdrop, decided once ([url] the first known): never swapped mid-visit. Shown at once from memory when it's
     * there; else a smaller copy first (this page's preview, or the page header's crop of it), crossfaded over.
     */
    private fun startArt(url: String?) {
        if (backdropUrl != null || url == null) return
        backdropUrl = url
        if (ImageLoader.cached(backdrop.keyFor(url)) == null) {
            val small = VodArt.backgroundAt(url, VodArt.Size.SMALL)
            val preview = HeaderArt.ArtSpec.TITLE_PREVIEW
            val held = small?.let { ImageLoader.cached(ImageLoader.keyOf(it, preview.decodeW, preview.decodeH, preview.keySuffix)) }
            if (held != null) {
                backdrop.hold(held, cover = false)
            } else {
                headerCrop(url)?.let { backdrop.hold(it, cover = true) } ?: backdrop.preview(small, preview)
            }
        }
        backdrop.show(url)
    }

    /** The bitmap a page header (Home's, or Movies' and TV Shows') already decoded for [url], if it's in memory. */
    private fun headerCrop(url: String) = listOf(VodSizes.homeHeaderW(), VodSizes.tabHeaderW()).firstNotNullOfOrNull { cardW ->
        val inset = t.dp(VodSizes.HEADER_ART_INSET)
        val w = (t.dp(VodSizes.backdropW(cardW)) - inset) / 2
        val h = (t.dp(VodSizes.HEADER_H) - 2 * inset) / 2
        ImageLoader.cached(ImageLoader.keyOf(url, w.coerceAtLeast(1), h.coerceAtLeast(1), HeaderArt.ArtSpec.HEADER.keySuffix))
    }

    private fun logoKey(url: String) =
        ImageLoader.keyOf(url, VodSizes.LOGO_DECODE_W, VodSizes.LOGO_DECODE_H, "|in")

    /** The logo, if the title has one, while the title box still waits: used at once when it's in memory. */
    private fun requestLogo(url: String?) {
        if (mode != TitleMode.WAITING || url == null || logoUrl != null) return
        logoUrl = url
        loadLogo(url)
    }

    private fun loadLogo(url: String) {
        ImageLoader.load(
            url, header.logo, VodSizes.LOGO_DECODE_W, VodSizes.LOGO_DECODE_H, android.graphics.Bitmap.Config.ARGB_8888,
            fit = ImageLoader.Fit.INSIDE,
        )
    }

    private fun onLogo() {
        if (mode == TitleMode.WAITING) decide(TitleMode.LOGO)
    }

    private val decideTitle = Runnable {
        if (mode == TitleMode.WAITING) decide(TitleMode.NAME)
    }

    /**
     * The title box's choice for this visit: the logo, or the name. A logo still on its way then isn't decoded, but its
     * bytes still reach the disk, for the next visit.
     */
    private fun decide(m: TitleMode) {
        mode = m
        if (m == TitleMode.LOGO) {
            header.showLogo(details.title.name)
        } else {
            ImageLoader.release(header.logo)
            header.showName(details.title.name)
        }
    }

    // ---- Focus ------------------------------------------------------------------------------------------------------

    /** Into the page as it opens: Play, or the first button that can take focus. */
    fun takeFocus() {
        part = Part.BUTTONS
        if (!buttons.focusFirst()) root.requestFocus()
    }

    /** Back into the page (from the rail): where focus was. */
    fun takeFocusBack() {
        val ok = when (part) {
            Part.BUTTONS -> buttons.focusLast()
            Part.SEASON -> seasonButton.isShown && seasonButton.requestFocus()
            Part.EPISODES -> episodes.visibility == View.VISIBLE && episodes.focusChosen()
            Part.MORE -> more.visibility == View.VISIBLE && more.focusChosen()
        }
        if (!ok) takeFocus()
    }

    /** Whether focus is right of the action column or under it (Back then goes to the buttons). */
    fun focusInRows(): Boolean = seasonButton.isFocused || episodes.focusedIndex() >= 0 || more.focusedIndex() >= 0

    /** Back with the season list open: it closes (true), focus back on the dropdown; nothing else happens then. */
    fun closeSeasonList(): Boolean {
        if (!seasonList.isOpen) return false
        seasonList.close()
        return true
    }

    /** Back from a row: focus on Play (or the first button), the page at its top. */
    fun backToButtons() {
        buttons.focusFirst()
        glide.glideTo(0f)
    }

    internal fun saveFocus(): Saved = Saved(
        part = part,
        button = buttons.last,
        season = shownSeason,
        episodeId = episodes.cards.getOrNull(episodes.chosen)?.title?.id,
        episodeIndex = episodes.chosen,
        moreId = more.cards.getOrNull(more.chosen)?.title?.id,
        moreIndex = more.chosen,
    )

    /** Focus where [saved] says (a page come back to); rows not filled yet take it when their cards come. */
    internal fun restoreFocus(saved: Saved?) {
        if (saved == null) {
            takeFocus()
            return
        }
        if (!tryRestore(saved)) {
            buttons.focus(saved.button)
            restore = saved
            restoreHolder = root.findFocus()
        }
        // A row kept grown while the page was covered, and focus isn't back in it: its card shrinks.
        if (episodes.focusedIndex() < 0) episodes.motion.shrink()
        if (more.focusedIndex() < 0) more.motion.shrink()
    }

    private fun applyRestore() {
        val s = restore ?: return
        // Only while focus is still where it was put to wait.
        if (root.findFocus() !== restoreHolder) {
            restore = null
            return
        }
        if (tryRestore(s)) restore = null
    }

    private fun tryRestore(s: Saved): Boolean = when (s.part) {
        Part.BUTTONS -> buttons.focus(s.button)
        Part.SEASON -> {
            if (seasonButton.visibility != View.VISIBLE || seasonIndex(s.season) < 0) false else {
                // The season it was on shows again.
                s.season?.let { applySeason(it) }
                seasonButton.requestFocus()
            }
        }
        Part.EPISODES -> {
            if (s.season != null && s.season != shownSeason && seasonIndex(s.season) >= 0) applySeason(s.season)
            val i = seasonCards.indexOfFirst { it.title.id == s.episodeId }.takeIf { it >= 0 } ?: s.episodeIndex
            if (episodes.visibility != View.VISIBLE || episodes.items.isEmpty()) false else {
                holdEpisodes(i)
                episodes.chosen = i.coerceIn(0, episodes.items.size - 1)
                episodes.motion.snap(episodes.chosen, isGrown = true)
                episodes.focusChosen()
            }
        }
        Part.MORE -> {
            val i = more.cards.indexOfFirst { it.title.id == s.moreId }.takeIf { it >= 0 } ?: s.moreIndex
            if (more.visibility != View.VISIBLE || more.items.isEmpty()) false else {
                more.chosen = i.coerceIn(0, more.items.size - 1)
                more.motion.snap(more.chosen, isGrown = true)
                val ok = more.focusChosen()
                // Straight to where it was, the page glided up.
                if (ok && isShow) column.post { glide.snap(glideTarget()) }
                ok
            }
        }
    }

    private fun parts() = TitleRules.Parts(
        season = seasonButton.visibility == View.VISIBLE && headRow.visibility == View.VISIBLE,
        episodes = episodes.visibility == View.VISIBLE && episodes.items.isNotEmpty(),
        more = more.visibility == View.VISIBLE && more.items.isNotEmpty(),
    )

    /** A D-pad press at the edge of [from]: to the rail, or to the part the page's map says ([TitleRules.next]). */
    private fun move(from: Part, dir: Dir) {
        if (TitleRules.toRail(from, dir, isShow)) {
            host.focusRail()
            return
        }
        TitleRules.next(from, dir, parts(), isShow)?.let { focusPart(it) }
    }

    /**
     * Focus into [to]: the column on the button it was last on, a row on the card chosen in it (the pods: the Play
     * target's when its season shows and nothing else was chosen since, [showSeasonCards]).
     */
    private fun focusPart(to: Part) {
        when (to) {
            Part.BUTTONS -> buttons.focusLast()
            Part.SEASON -> seasonButton.requestFocus()
            Part.EPISODES -> episodes.focusChosen()
            Part.MORE -> more.focusChosen()
        }
    }

    /** The row holds the shown season's pods up to [i] and some way past it (a long season's window grows). */
    private fun holdEpisodes(i: Int) {
        val n = VodSizes.episodeWindow(seasonCards.size, i, episodes.items.size)
        if (n > episodes.items.size) episodes.bind(seasonCards.subList(0, n))
    }

    private fun dirOf(keyCode: Int): Dir? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> Dir.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Dir.RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> Dir.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Dir.DOWN
        else -> null
    }

    // ---- Buttons ----------------------------------------------------------------------------------------------------

    override fun onButton(which: TitleButtons.Which) {
        when (which) {
            TitleButtons.Which.PLAY -> playButton(fromBeginning = false)
            TitleButtons.Which.RESTART -> playButton(fromBeginning = true)
            TitleButtons.Which.SOURCES -> openSources()
            TitleButtons.Which.MY_LIST -> {
                val r = host.repo ?: return
                val inList = !r.inMyList(opened.id)
                host.hold.setInList(details.title, inList)
                buttons.setMyList(inList)
            }
            TitleButtons.Which.MORE -> openMoreWindow()
        }
    }

    override fun onButtonFocused(which: TitleButtons.Which) {
        part = Part.BUTTONS
        offTheRows()
    }

    /** Focus is on the column or the dropdown: the header says the title's own words, the page is at its top. */
    private fun offTheRows() {
        press.cancel()
        cancelResolve()
        main.removeCallbacks(follow)
        header.unfollow()
        // A pod kept grown for its small window, which closed with focus elsewhere: it shrinks back.
        if (!host.hold.isOpen && episodes.focusedIndex() < 0) episodes.motion.shrink()
        glide.glideTo(0f)
        updateImages()
    }

    override fun onButtonsDown(which: TitleButtons.Which) = move(Part.BUTTONS, Dir.DOWN)

    override fun onButtonsRight(which: TitleButtons.Which) = move(Part.BUTTONS, Dir.RIGHT)

    override fun onLeftEdge() = move(Part.BUTTONS, Dir.LEFT)

    // ---- Rows -------------------------------------------------------------------------------------------------------

    /** More Like This (the page's one [TitleRow]). */
    override fun onItemFocused(row: TitleRow, index: Int) {
        press.cancel()
        // A poster being looked up acts only while focus stays on it.
        if (index != resolving) cancelResolve()
        part = Part.MORE
        main.removeCallbacks(follow)
        header.unfollow()
        if (isShow) glide.glideTo(glideTarget())
        updateImages()
    }

    override fun onRowLeft(row: TitleRow): Boolean {
        // Covered by the page opened from it (or gone): the row stays as it was, its card grown, for coming back.
        if (paused || released) return false
        // Into its small window: the card stays grown until the window closes.
        if (host.hold.isOpen && host.hold.heldRow === row) return false
        press.cancel()
        cancelResolve()
        pendingMore?.let { showMore(it) }
        return true
    }

    override fun onItemKey(row: TitleRow, index: Int, keyCode: Int, event: KeyEvent): Boolean =
        itemKey(Part.MORE, index, row.items, keyCode, event)

    /** The episode pods. */
    private fun podListener(): EpisodeRow.Listener = object : EpisodeRow.Listener {
        override fun onEpisodeFocused(index: Int) {
            press.cancel()
            cancelResolve()
            part = Part.EPISODES
            holdEpisodes(index)
            // The header says what the pod is once focus rests on it.
            main.removeCallbacks(follow)
            main.postDelayed(follow, VodSizes.SETTLE_MS)
            glide.glideTo(0f)
            updateImages()
        }

        override fun onEpisodesLeft(): Boolean {
            // Covered by another page (or gone): the row stays as it was, its pod grown, for coming back.
            if (paused || released) return false
            // Into its small window: the pod stays grown (and the header on its episode) until the window closes.
            if (host.hold.isOpen && host.hold.heldView is EpisodePod) return false
            press.cancel()
            main.removeCallbacks(follow)
            header.unfollow()
            return true
        }

        override fun onEpisodeKey(index: Int, keyCode: Int, event: KeyEvent): Boolean =
            itemKey(Part.EPISODES, index, episodes.items, keyCode, event)
    }

    /** A key on card [index] of [p]'s row (the pods, or More Like This), whose cards are [items]. */
    private fun itemKey(p: Part, index: Int, items: List<View>, keyCode: Int, event: KeyEvent): Boolean {
        if (isOk(keyCode)) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (press.down(event.repeatCount, event.isLongPress, event.eventTime - event.downTime)) onHold(p, index)
                KeyEvent.ACTION_UP -> if (press.up(event.isCanceled)) onPick(p, index)
            }
            return true
        }
        val dir = dirOf(keyCode) ?: return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        press.cancel()
        when {
            // One card per press, also while held: the row glides behind; nothing wraps.
            dir == Dir.RIGHT -> items.getOrNull(index + 1)?.requestFocus()
            dir == Dir.LEFT && index > 0 -> items.getOrNull(index - 1)?.requestFocus()
            // A held Left stops on the first card; a new press goes on to the column.
            dir == Dir.LEFT && event.repeatCount > 0 && !TitleRules.toRail(p, dir, isShow) -> Unit
            else -> move(p, dir)
        }
        return true
    }

    private fun isOk(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun onPick(p: Part, index: Int) {
        when (p) {
            Part.EPISODES -> {
                val ep = shownEpisodes.getOrNull(index) ?: return
                if (ep.aired(System.currentTimeMillis())) playEpisode(ep) else host.showNotice(TitleRules.airs(ep, thisYear()))
            }
            Part.MORE -> {
                val card = more.cards.getOrNull(index) ?: return
                resolve(card.title, index) { host.openNext(it) }
            }
            else -> Unit
        }
    }

    private fun onHold(p: Part, index: Int) {
        val r = host.repo ?: return
        val hold = host.hold
        when (p) {
            Part.EPISODES -> {
                val ep = shownEpisodes.getOrNull(index) ?: return
                // Nothing to mark on one that hasn't aired.
                if (!ep.aired(System.currentTimeMillis())) return
                val pod = episodes.items.getOrNull(index) ?: return
                // Only what would change something: no "up to here" on a first episode, nor when it'd change only this one.
                val offer = EpisodeMarks.offer(details.episodes, ep, System.currentTimeMillis(), { r.isEpisodeWatched(it) }) {
                    (r.positionOf(it)?.positionMs ?: 0L) > 0L
                }
                val d = details
                val actions = ArrayList<HoldPopup.Button>(4)
                if (offer.watched) actions += HoldPopup.Button("Mark watched", HoldWindow.watchedIcon(false)) {
                    hold.close()
                    r.setEpisodesWatched(listOf(ep), true)
                    marked(HoldWindow.MARKED)
                }
                if (offer.unwatched) actions += HoldPopup.Button("Mark unwatched", HoldWindow.watchedIcon(true)) {
                    hold.close()
                    r.setEpisodesWatched(listOf(ep), false)
                    marked(HoldWindow.UNMARKED)
                }
                if (offer.watchedUpTo) actions += HoldPopup.Button("Mark watched up to here", HoldWindow.watchedIcon(false)) {
                    hold.close()
                    r.markWatchedUpTo(d, ep)
                    marked(HoldWindow.MARKED)
                }
                if (offer.unwatchedUpTo) actions += HoldPopup.Button("Mark unwatched up to here", HoldWindow.watchedIcon(true)) {
                    hold.close()
                    r.markUnwatchedUpTo(d, ep)
                    marked(HoldWindow.UNMARKED)
                }
                val name = listOfNotNull(TitleRules.episodeCode(ep.ref), ep.name.takeIf { it.isNotBlank() }).joinToString(" · ")
                // The pod stays grown while its window is open ([EpisodeRow.Listener.onEpisodesLeft]).
                hold.open(name, actions, pod, pod, hold.area(VodSizes.HOLD_INSET)) { takeFocusBack() }
            }
            Part.MORE -> {
                val card = more.cards.getOrNull(index) ?: return
                resolve(card.title, index) { found ->
                    val anchor = more.cardViewAt(index) ?: return@resolve
                    if (!anchor.isShown) return@resolve
                    hold.openPoster(card.copy(title = found), false, anchor, more.items[index], hold.area(VodSizes.HOLD_INSET), more, this) { takeFocusBack() }
                }
            }
            else -> Unit
        }
    }

    /**
     * [title] (More Like This poster [index]) with its IMDb id: at once, or once the repository finds it ("Couldn't
     * load this title" if it can't). Found later, it acts only while focus is still on that poster with no window open;
     * focus moving off it cancels the lookup ([cancelResolve]).
     */
    private fun resolve(title: Title, index: Int, then: (Title) -> Unit) {
        if (title.hasImdbId) {
            then(title)
            return
        }
        val r = host.repo ?: return
        cancelResolve()
        resolving = index
        resolveCancel = r.resolve(title) { found ->
            resolveCancel = null
            resolving = -1
            if (released || paused) return@resolve
            if (more.focusedIndex() != index || host.hold.isOpen || host.sidePanel.isOpen) return@resolve
            if (found == null || !found.hasImdbId) host.showNotice(COULDNT_OPEN) else then(found)
        }
    }

    // ---- The season dropdown, the header following an episode ------------------------------------------------------

    private fun onSeasonFocused() {
        part = Part.SEASON
        offTheRows()
    }

    /** The dropdown's keys: OK (on its release, for a press that started here) opens its list; the D-pad leaves it. */
    private fun onSeasonKey(keyCode: Int, event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (isOk(keyCode)) {
            if (down && event.repeatCount == 0) seasonArmed = true
            if (event.action == KeyEvent.ACTION_UP) {
                val mine = seasonArmed && !event.isCanceled
                seasonArmed = false
                if (mine) openSeasonList()
            }
            return true
        }
        val dir = dirOf(keyCode) ?: return false
        if (down) {
            seasonArmed = false
            move(Part.SEASON, dir)
        }
        return true
    }

    /** The show's seasons in a small window under the dropdown: OK on one shows its episodes. */
    private fun openSeasonList() {
        if (seasons.isEmpty() || root.width <= 0 || paused || released) return
        // Placed where the dropdown is laid out: the page at its top first.
        if (glide.moving || glide.value != 0f) glide.snap(0f)
        val at = Rect(0, 0, seasonButton.width, seasonButton.height)
        root.offsetDescendantRectToMyCoords(seasonButton, at)
        val inset = t.dpf(VodSizes.SEASON_LIST_INSET)
        seasonList.open(
            seasons.map { TitleRules.seasonLabel(it.number) }, seasonIndex(shownSeason), RectF(at),
            RectF(inset, inset, root.width - inset, root.height - inset),
            onClose = { focusBack ->
                if (focusBack && !released) {
                    if (seasonButton.isShown) seasonButton.requestFocus() else takeFocusBack()
                }
            },
        ) { i -> seasons.getOrNull(i)?.let { applySeason(it.number) } }
    }

    /** Shows season [n]'s pods (rebuilt in place) and says it on the dropdown. */
    private fun applySeason(n: Int) {
        if (n == shownSeason && cardsSeason == n) return
        shownSeason = n
        seasonButton.set(TitleRules.seasonLabel(n), null)
        showSeasonCards()
    }

    /** Focus has rested on an episode card: the header says what it is. */
    private val follow = Runnable {
        val i = episodes.focusedIndex()
        val ep = shownEpisodes.getOrNull(i) ?: return@Runnable
        val now = System.currentTimeMillis()
        val facts = TitleRules.episodeFacts(ep, host.repo?.positionOf(ep), now, thisYear())
        val overview = ep.overview?.takeIf { it.isNotBlank() } ?: header.storyShown
        header.follow(facts, overview)
    }

    // ---- The (⋯) window and Details ---------------------------------------------------------------------------------

    private fun openMoreWindow() {
        val r = host.repo ?: return
        cancelResolve()
        val hold = host.hold
        val title = details.title
        val now = System.currentTimeMillis()
        val inContinue = r.inContinueWatching(opened.id)
        // A show reads as its episodes' ✓ marks do (the show's own mark covers only the episodes aired when it was
        // set): with episodes aired since, it offers Mark watched again. Before its episodes are known, its own mark
        // (as a poster's window reads it). Part watched: both, as an episode's window ([TitleMarks.offer]).
        val watched = if (isShow && details.episodes.isNotEmpty()) {
            TitleRules.allWatched(details.episodes, now) { r.isEpisodeWatched(it) }
        } else {
            TitleMarks.watched(title.kind, r.isWatched(opened.id), r.lastPlayed(opened), inContinue)
        }
        val offer = TitleMarks.offer(watched, r.hasHistory(opened))
        val actions = ArrayList<HoldPopup.Button>(4)
        if (offer.watched) actions += HoldPopup.Button("Mark watched", HoldWindow.watchedIcon(false)) { mark(title, true) }
        if (offer.unwatched) actions += HoldPopup.Button("Mark unwatched", HoldWindow.watchedIcon(true)) { mark(title, false) }
        if (inContinue) {
            actions += HoldPopup.Button("Remove from Continue Watching", HoldWindow.REMOVE_ICON) { hold.removeFromContinue(title) }
        }
        actions += HoldPopup.Button("Details", HoldWindow.DETAILS_ICON) { openDetails() }
        val anchor = buttons.moreButton
        hold.open(title.name, actions, anchor, anchor, hold.area(VodSizes.HOLD_INSET)) { takeFocusBack() }
    }

    /** The (⋯) window's Mark watched or unwatched on the whole film or show ([title]). */
    private fun mark(title: Title, watched: Boolean) {
        host.hold.markWatched(title, watched)
        // The page's ✓ marks, bars, chip and Play at once (the notice is the window's).
        if (!paused && !released) refreshAll()
    }

    /** Details: the whole story, the facts, all the cast and crew, and where each came from (a wide settings window). */
    private fun openDetails() {
        val back = buttons.moreButton
        host.hold.close(fade = false, focusBack = false)
        val d = details
        host.sidePanel.open(d.title.name, onClose = {
            if (!released && back.isShown) back.requestFocus() else if (!released) takeFocusBack()
        }, wide = true) { titleDetails(d) }
    }

    // ---- The glide and pictures -------------------------------------------------------------------------------------

    /**
     * How far a show's page glides up for More Like This (its band ends on screen; the season dropdown's row stays just
     * in view, the text block above it gone).
     */
    private fun glideTarget(): Float {
        val top = column.topOf(more)
        if (!isShow || top < 0 || root.height <= 0) return 0f
        val moreBottom = (top + more.bandHeight).toFloat()
        val rowTop = stage.top + t.dpf(VodSizes.SEASON_TOP)
        return VodSizes.glide(moreBottom, rowTop, root.height.toFloat(), t.dpf(VodSizes.GLIDE_KEEP_TOP))
    }

    /** Rows came or went (laid out again): a page glided for More Like This stays on it, wherever it now is. */
    private fun onRowsMoved() {
        if (isShow) {
            val want = if (TitleRules.glided(part, isShow) && more.focusedIndex() >= 0) glideTarget() else 0f
            if (want != glide.target) glide.snap(want)
        }
        updateImages()
    }

    /**
     * Pictures for the cards near each row's chosen one, for the rows that show where the page is heading (More Like
     * This on a show: once its posters are in view); the others let theirs go.
     */
    private fun updateImages() {
        if (paused || released) return
        if (episodes.visibility == View.VISIBLE) {
            val after = if (part == Part.MORE) VodSizes.POD_BIND_AFTER - 1 else VodSizes.POD_BIND_AFTER
            val range = RowMath.boundCards(episodes.chosen, episodes.items.size, 1, after)
            if (range != episodes.boundRange) episodes.bindImages(range)
        }
        if (more.visibility == View.VISIBLE) {
            val top = column.topOf(more)
            val postersTop = top + t.dp(VodSizes.stripTop() + VodSizes.cardTop(RowStyle.POSTER)) - glide.target
            val inView = !isShow || (top >= 0 && root.height > 0 && postersTop < root.height)
            if (inView) {
                val after = if (isShow) VodSizes.POSTER_BIND_AFTER - 1 else VodSizes.POSTER_BIND_AFTER
                val range = RowMath.boundCards(more.chosen, more.items.size, 1, after)
                if (range != more.boundRange) more.bindImages(range)
            } else if (more.boundRange != IntRange.EMPTY) {
                more.releaseImages()
            }
        }
    }

    private fun thisYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

    private companion object {
        const val NO_SOURCES = "Sources aren't available just now"
        const val COULDNT_OPEN = "Couldn't load this title"
    }
}
