package app.fireplayer

import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Paint
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import app.fireplayer.data.Dimmer
import app.fireplayer.data.Prefs
import app.fireplayer.history.ContinueWatching
import app.fireplayer.history.WatchHistory
import app.fireplayer.player.BufferWait
import app.fireplayer.player.DecoderCheck
import app.fireplayer.player.Delays
import app.fireplayer.player.DisplaySwitch
import app.fireplayer.player.FpsMeter
import app.fireplayer.player.FrameRateMatcher
import app.fireplayer.player.FreezeWatch
import app.fireplayer.player.IdleCloseDataSource
import app.fireplayer.player.NextEpisode
import app.fireplayer.player.MatchFixes
import app.fireplayer.player.NextPick
import app.fireplayer.player.ShowGuide
import app.fireplayer.player.SpeechTap
import app.fireplayer.player.PlayerFactory
import app.fireplayer.player.ServerSwitch
import app.fireplayer.player.ThreadBeat
import app.fireplayer.player.TitleInfo
import app.fireplayer.player.VsyncFix
import app.fireplayer.player.XtreamAccount
import app.fireplayer.subs.AutoSync
import app.fireplayer.subs.EmbeddedCues
import app.fireplayer.subs.FontImport
import app.fireplayer.subs.MovieHash
import app.fireplayer.subs.SavedSubtitles
import app.fireplayer.subs.OpenSubtitles
import app.fireplayer.subs.SubtitleRanker
import app.fireplayer.subs.Tmdb
import app.fireplayer.titles.TitleBook
import app.fireplayer.titles.TitleProxy
import app.fireplayer.titles.TitleService
import app.fireplayer.subs.PictureText
import app.fireplayer.subs.SubtitlePresets
import app.fireplayer.subs.SubtitleStyler
import app.fireplayer.subs.placedLikePicture
import app.fireplayer.ui.CountdownPill
import app.fireplayer.player.SkipTimes
import app.fireplayer.player.MkvChapters
import app.fireplayer.player.Segments
import app.fireplayer.ui.EpisodesSheet
import app.fireplayer.ui.FireDialog
import app.fireplayer.ui.Glass
import app.fireplayer.ui.GlassBackdrop
import app.fireplayer.ui.SeekBarView
import app.fireplayer.ui.SeekPreview
import app.fireplayer.ui.SettingsPanels
import app.fireplayer.ui.SidePanel
import app.fireplayer.ui.Rows
import io.github.peerless2012.ass.media.AssHandler
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity(), Player.Listener, GlassBackdrop.Host {

    /** One video to play, with any subtitles we were handed or added. */
    private class Entry(
        val uri: Uri,
        val rawTitle: String?,
        val mimeType: String? = null,
        val subtitles: MutableList<MediaItem.SubtitleConfiguration> = mutableListOf(),
        /** The next episode of a show, found with the provider: gets the end-of-episode card. */
        val nextEpisode: Boolean = false,
    ) {
        /** The subtitles the opening app handed over with it, and their names, to hand over again on resuming. */
        val handedSubtitles = mutableListOf<Pair<Uri, String?>>()
        /** The provider's name for the show, for an episode that came named only by its number ("S01E03"). */
        var showName: String? = null
            set(value) {
                field = value
                parsed = null
            }
        /** Its English name, found by FirePlayer's title server (see [lookUpEnglishName]); and whether it was asked for. */
        var english: TitleInfo? = null
            set(value) {
                field = value
                parsed = null
            }
        var englishAsked = false
        /** For an episode through the title server, which one it really is by the provider's numbers (see [NextPick]). */
        var real: NextPick.Real? = null
            set(value) {
                field = value
                parsed = null
            }
        var realTries = 0
        private var parsed: TitleInfo? = null
        val info: TitleInfo
            get() = parsed ?: english ?: TitleInfo.parse(rawTitle, uri).let { base ->
                showName?.takeIf { !base.named }?.let { base.withShow(it) } ?: base
            }.let { base ->
                // Numbered as the provider numbers it, not by its place in TiviMate's list.
                real?.takeIf { base.episode != null }?.let { base.withEpisode(it.season, it.episode) } ?: base
            }.also { parsed = it }
        val key: String get() = uri.toString()
    }

    private class TrackOption(
        val group: Tracks.Group,
        val trackIndex: Int,
        val label: String,
        val external: Boolean,
        val selected: Boolean,
    )

    private class MoreItem(val view: View, val value: TextView, val open: () -> Unit)

    /** What a track picked by hand was like, to find its match among another episode's tracks. */
    private class TrackWish(val language: String?, val label: String?, val roleFlags: Int, val forced: Boolean, val channels: Int)

    private lateinit var prefs: Prefs
    private lateinit var presets: SubtitlePresets
    private lateinit var styler: SubtitleStyler
    private lateinit var pictureText: PictureText
    private lateinit var settingsPanels: SettingsPanels
    private lateinit var panel: SidePanel
    private lateinit var frameRateMatcher: FrameRateMatcher
    private lateinit var openSubtitles: OpenSubtitles
    private lateinit var tmdb: Tmdb
    private lateinit var matchFixes: MatchFixes
    private lateinit var episodesSheet: EpisodesSheet
    // The show playing, with all its episodes paired with TMDB's (see ShowGuide); null until known.
    private var guide: ShowGuide? = null
    // What the provider said about the show the last time it was asked, to pair it again after a fix without asking.
    private var lastFound: NextEpisode.Found? = null
    private var lastFoundUri: Uri? = null
    // Shows whose guide was made fresh while this player runs: the next episode's look-up doesn't make it again.
    private val guidesMade = HashSet<String>()

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    // FirePlayer's title server's names, opened when first needed (on [io]).
    private var titleBook: TitleBook? = null
    private val delays = Delays()
    // Subtitle auto sync: listens to a copy of the sound while it lines downloaded subtitles up (see AutoSync).
    private val speechTap = SpeechTap()
    // The timing of the film's own subtitle tracks as they load: auto sync lines downloaded subtitles up against it.
    private val embeddedCues = EmbeddedCues()
    private lateinit var autoSync: AutoSync
    // The Subtitle delay window is open, so auto sync's news shows in it at once.
    private var delayPanelOpen = false

    private var player: ExoPlayer? = null
    // Keeps the film's frames evenly paced on a TV switched to the film's refresh rate (see VsyncFix).
    private var vsyncFix: VsyncFix? = null
    // The blurred film the glass look shows (see GlassBackdrop): this player's own, made once the views are.
    private var backdrop: GlassBackdrop? = null
    override val glassBackdrop: GlassBackdrop? get() = backdrop
    // A jump was made: the glass copies the picture again once the player is ready at the new spot.
    private var glassAfterSeek = false
    private var assHandler: AssHandler? = null
    private var bufferWait: () -> BufferWait? = { null }

    // Views
    private lateinit var root: ViewGroup
    private lateinit var videoFrame: AspectRatioFrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var frameSubtitles: SubtitleView
    private lateinit var screenSubtitles: SubtitleView
    private lateinit var buffering: ProgressBar
    private lateinit var postPlay: View
    private lateinit var postPlayNext: TextView
    private lateinit var postPlayCredits: CountdownPill
    private lateinit var postPlayGo: CountdownPill
    private lateinit var dimmerView: View
    private lateinit var dimmer: Dimmer
    // The episode whose end card was put away (Watch credits, or Back), so it doesn't come straight back.
    private var postPlayDismissedFor = -1
    // Where the end card's count started, and how long it runs.
    private var postPlayFrom = 0L
    private var postPlayCountMs = POST_PLAY_COUNT_MS
    // What the end card offers: the next episode, or Exit once only a film's (or last episode's) credits remain.
    private var postPlayExit = false
    // Next episode starts by itself when its count ends: the credits surely run to the end.
    private var postPlayAuto = false
    private var postPlayIcon: android.graphics.drawable.Drawable? = null

    // Where this episode's intro and credits are ([Segments]), worked out for [segmentsFor] (entry key and episode).
    private var segments: Segments? = null
    @Volatile private var segmentsFor: String? = null
    // Each look-up's number: an answer from an earlier one for the same episode is dropped.
    @Volatile private var segmentsAsked = 0
    // A newer answer for the same episode, held while a skip button or pod shows (it isn't moved under the viewer).
    private var segmentsWaiting: Segments? = null
    private var segmentsWait = false
    private var segmentsAgainAt = 0L
    private val lookUpSegmentsAgain = Runnable { lookUpSegmentsAgain() }
    // One look-up at a time; one for an episode no longer playing is dropped before it asks anything.
    private val segmentWorker = Executors.newSingleThreadExecutor()
    // A file's own chapters, read once per file, by entry key (null: it has none to use).
    private val chapterSegments = HashMap<String, Segments?>()
    private lateinit var skipButton: CountdownPill
    private lateinit var skipPod: CountdownPill
    // The part whose button came up, and when (uptime): after SKIP_SHOWN_MS untouched it slips away for good, and
    // the part can still be skipped from the pod over the time bar while the controls are up.
    private var skipShownFor = -1L
    private var skipShownAt = 0L
    private var skipFadedFor = -1L
    private var skipStart = -1L
    private var skipTarget = -1L
    // The part whose button was put away with Back, so it doesn't come straight back.
    private var skipDismissed = -1L
    // Where the part whose button was put away (or slipped away) began before auto sync's offsets moved it later:
    // only going back before that brings the button back, not playing on into its new start.
    private var skipDismissedFrom = -1L
    private var skipFadedFrom = -1L
    private lateinit var bufferingInfo: TextView
    private lateinit var scrimTop: View
    private lateinit var scrimBottom: View
    private lateinit var topBar: View
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var clockView: TextView
    private lateinit var bottomBlock: View
    private lateinit var chips: View
    private lateinit var chipResolution: TextView
    private lateinit var chipFps: TextView
    private lateinit var chipAudio: TextView
    private lateinit var chipPortal: TextView
    private lateinit var btnEpisodes: View
    private lateinit var episodesGroup: View
    private lateinit var seekBubble: TextView
    private lateinit var seekPreview: SeekPreview
    private lateinit var seekRow: View
    private lateinit var timeElapsed: TextView
    private lateinit var seekBar: SeekBarView
    private lateinit var timeRemaining: TextView
    private lateinit var buttonsArea: View
    private lateinit var mainRow: View
    private lateinit var moreRow: LinearLayout
    private lateinit var arrowDown: View
    private lateinit var arrowUp: View
    private lateinit var btnPlay: ImageButton
    private lateinit var btnRestart: View
    private lateinit var btnNext: View
    private lateinit var nextGroup: View
    private lateinit var btnSubs: View
    private lateinit var btnAudio: View
    private lateinit var subsLabel: TextView
    private lateinit var audioLabel: TextView
    private lateinit var errorBox: View
    private lateinit var errorHint: TextView
    private lateinit var errorText: TextView
    /** Counts errors shown, so a late answer about the account's screens only changes the error it was asked for. */
    private var errorsShown = 0
    private lateinit var focusSink: View

    private val moreItems = LinkedHashMap<String, MoreItem>()

    // Playlist
    private val entries = ArrayList<Entry>()
    private var startIndex = 0
    // Whether the next episode is looked up with the provider (only when the opening app sent one episode), and the
    // episode it was last looked up for.
    private var findsNextEpisode = false
    private var nextLookedUpFor: String? = null
    // Which look-up is the current one, and whether it hasn't answered yet; leaving the player drops it.
    private var lookupRun = 0
    private var lookupInFlight = false
    // When a look-up was asked for that waits for the film to be well ahead (elapsedRealtime; 0 when none waits).
    private var lookupWantedAt = 0L
    // The episodes played here, by their real numbers: a copy of one of them further down the list isn't played next.
    private val watchedReal = HashSet<NextPick.Real>()
    // Times this video's connection was dropped just after the skip preview connected, and when that last happened.
    private var previewCutOffs = 0
    private var lastPreviewCutOffAt = 0L
    // The sound and subtitles picked by hand, to pick the same kind again in the next episode.
    private val trackWishes = HashMap<Int, TrackWish>()
    private var reapplyWishes = false
    // The film or show whose remembered sound was put back, so it's done once per title.
    private var audioRecalledFor: String? = null
    // The film was paused for the TV to change refresh rate, and goes on by itself afterwards.
    private var pausedForSwitch = false
    // The film stood still while meant to be playing (see FreezeWatch): paused for a moment, and played again by itself.
    private var pausedForFreeze = false
    private val freezeWatch = FreezeWatch { SystemClock.elapsedRealtime() }
    /** Whether the current player's own thread still answers; one per player. */
    private var threadBeat: ThreadBeat? = null
    // The next quiet retry builds a new player instead of loading again (a film that froze).
    private var rebuildOnRetry = false
    // The error shown is for a film that froze and couldn't be started again: OK builds a new player.
    private var frozeOut = false
    // A sleep timer's time left while the player is away; it only counts down on screen.
    private var sleepRemainingMs = 0L
    // The link is of a kind the player can't open at all, so OK can't try again.
    private var cannotOpen = false
    // The most the buffering line has shown during this wait, so it never counts backwards.
    private var shownPercent = 0
    /** Brought back by Android after FirePlayer was closed: closes without playing (see onCreate). */
    private var stale = false
    /** The player is opening a screen of its own (the update's installer): not leaving the film. */
    private var openingOwn = false
    // When the latest jump was made (elapsedRealtime): a reading of the wait from before it is about the old spot.
    private var percentFrom = 0L
    private var startPositionMs = C.TIME_UNSET
    private var savedTrackParams: TrackSelectionParameters? = null
    private var savedPlayWhenReady = true
    private var pendingExternalSubId: String? = null
    /** Downloaded subtitles in the videos' lists: their file in SavedSubtitles.dir, by subtitle id. */
    private val savedFiles = HashMap<String, String>()
    private var externalCounter = 0

    // UI state
    private var controlsShown = false
    private var moreShown = false
    private var completed = false
    /** The item playing before the latest switch, so a finished episode's resume point can be cleared. */
    private var playingIndex = 0
    /** Whether the current player was built with libass ("Keep embedded styling" on). */
    private var builtWithAss = false
    private var matchedFpsForIndex = -1
    // The video the TV wouldn't stay on the film's rate for: not asked again (see rematchFrameRate). And how often it was
    // asked again for the video playing.
    private var refusedForIndex = -1
    private var rematches = 0
    private var displayMode = 0

    // Seeking
    private var seeking = false
        set(value) {
            field = value
            // Time spent lining up a jump isn't time watched.
            countPlaying(player?.isPlaying == true)
        }
    private var seekBaseMs = 0L
    private var seekOffsetMs = 0L
    private var lastNudgeTime = 0L
    private var consumeBackUp = false

    // Sleep timer
    private var sleepEndsAt = 0L
    private var sleepAtEnd = false

    // Frame rate worked out from the frames, for streams that don't say
    private var measuredFps = 0f
    // Only for the chip: by the time it's measured the video is playing, and switching the TV's
    // refresh rate then would blank the picture mid-scene. MKVs get their rate before the first frame.
    private val fpsMeter = FpsMeter { fps ->
        measuredFps = fps
        updateChips()
    }

    // Network hiccups are retried quietly before an error is shown
    private var retries = 0
    private var firstErrorAt = 0L
    // When the viewer started waiting for the film (0 while it plays), and when the latest try started loading.
    private var waitingSince = 0L
    private var roundStartedAt = 0L
    private var retryPending = false
    /** Cancels the wait for the decoder list (see [DecoderCheck]); null when not waiting. */
    private var decoderWait: (() -> Unit)? = null
    /** The decoder service is stuck, so no player is built and the error says to restart the TV. */
    private var decoderStuck = false
    private val retryRunnable = object : Runnable {
        override fun run() {
            // A refresh-rate switch that began after this was scheduled (the next video's) has to settle first.
            val settle = displaySwitchAt + DISPLAY_SETTLE_MS - SystemClock.elapsedRealtime()
            if (settle > 0) {
                handler.postDelayed(this, settle)
                return
            }
            retryPending = false
            roundStartedAt = SystemClock.elapsedRealtime()
            if (rebuildOnRetry) {
                // A new thread, in case the old one is what's stuck.
                rebuildOnRetry = false
                rebuildPlayer()
            } else {
                player?.prepare()
            }
        }
    }
    // Sound or a decoder the device couldn't start, counted apart: these can fail again right after
    // playback resumes, so only half a minute of playing (or another video) gives them back.
    private var deviceRetries = 0
    private var firstDeviceErrorAt = 0L
    private val deviceRetriesBack = Runnable { deviceRetries = 0 }
    // When the TV was last asked to change refresh rate; its sound output drops out while it switches. Passed on to
    // the player's sound, which waits that out (see SwitchTolerantAudioSink).
    private val displaySwitch = DisplaySwitch()
    private var displaySwitchAt = 0L
        set(value) {
            field = value
            displaySwitch.atMs = value
            suspendGlass()
        }

    private var resumeDialogShowing = false
    // Real playing time not yet noted for Continue Watching (see noteWatched), since when it's been playing
    // (elapsedRealtime; 0 while it isn't), and when it was last noted.
    private var playedMs = 0L
    private var playingSince = 0L
    private var watchNotedAt = 0L
    // Opened from Glass Home's Continue Watching row: the link is FirePlayer's own, never handed back in a result.
    private var resumedFromRow = false
    // When the player last went off screen without closing (the TV slept, the screensaver, Home while paused).
    private var stoppedAt = 0L
    // A newer title was handed to this player (onNewIntent): it starts over with that one, the old film never shows.
    private var replaced = false
    private var pendingAudioId: String? = null
    private var notice = false
    private var pendingNotice: String? = null

    // Subtitle style preview
    private var lastTextCues: List<Cue> = emptyList()
    private var lastPictureCues: List<Cue> = emptyList()
    private var stylePreviewing = false

    // What's on screen: picture subtitles that were read as text count as text.
    private var shownTextCues: List<Cue> = emptyList()
    private var shownPictureCues: List<Cue> = emptyList()
    // Pictures being read now that they're due (not read ahead in time), and until when they stay hidden for it.
    private var readingPictures: List<Cue>? = null
    private var readingUntil = 0L
    private val readingTimeout = Runnable { showCues() }

    // Time labels keep a fixed width so the bar never changes length
    private var longTimes: Boolean? = null

    // ---------------------------------------------------------------------
    // Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Brought back by Android after Fire OS closed FirePlayer (its task shown again), or from the recent apps: the
        // film it had is over, and it never starts again by itself. It closes at once, before anything else, so a
        // film playing now is left alone.
        val oldRun = savedInstanceState != null && savedInstanceState.getLong(STATE_PROCESS) != PROCESS_MARK
        if (oldRun || (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            stale = true
            if (isTaskRoot || inPlayerTask()) finishAndRemoveTask() else finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
            return
        }
        // A player left open behind (TiviMate reopened, or Home pressed instead of Back) would be where Back lands
        // after this one, showing the episode watched before. Only the newest stays, so Back goes to TiviMate.
        shown?.get()?.takeIf { it !== this && !it.isFinishing }?.let { old ->
            // Its task is this one's too when this was started into it (players share a task affinity): removing the
            // task would close this player as well, and the film would never open.
            if (old.isTaskRoot && old.taskId != taskId) old.finishAndRemoveTask() else old.finish()
        }
        shown = WeakReference(this)
        // Usually answered in a tenth of a second, before the player is built.
        DecoderCheck.ask()
        setContentView(R.layout.activity_player)
        prefs = Prefs(this)
        dimmer = Dimmer(this)
        presets = SubtitlePresets(prefs)
        styler = SubtitleStyler(this)
        pictureText = PictureText { handler.post { if (readingPictures != null) showCues() } }
        settingsPanels = SettingsPanels(this, prefs, presets, inPlayer = true)
        frameRateMatcher = FrameRateMatcher(this)
        openSubtitles = OpenSubtitles(prefs)
        tmdb = Tmdb(prefs)
        matchFixes = MatchFixes(this)
        // Downloaded subtitles unused for a while go, with what was found for their timing.
        io.execute { SavedSubtitles.prune(applicationContext) }
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, 0)
        autoSync = AutoSync(
            speechTap,
            prefs,
            delayUs = { delays.subtitleUs },
            setDelayUs = { delays.subtitleUs = it; refreshLabels() },
            position = { player?.let { it.currentPosition to it.duration.coerceAtLeast(0L) } ?: (0L to 0L) },
            onChanged = {
                refreshLabels()
                if (delayPanelOpen && panel.isOpen) panel.refresh()
            },
            // The show this episode is of, when TMDB knows it: a bumper's jump found here is tried first in its others.
            showKey = {
                val g = guide
                val entry = currentEntry()
                if (g != null && g.tmdbId > 0 && entry != null && guideEpisode(entry) != null) "tmdb:${g.tmdbId}" else null
            },
            embedded = embeddedCues,
            filmUri = { player?.currentMediaItem?.localConfiguration?.uri?.toString() },
        )
        autoSync.onMappingChanged = { syncMappingChanged() }
        // A line held back for auto sync is shown once it's lined up (or has waited long enough).
        autoSync.onHoldEnded = { showCues() }
        settingsPanels.onAutoSyncChanged = { autoSync.setOn(prefs.autoSync); followSubtitle() }
        settingsPanels.onFontChanged = {
            styler.reloadImportedFont()
            applySubtitleStyle()
        }
        bindViews()
        // Before applyLook: the glass looks for its film here.
        backdrop = GlassBackdrop(this, surfaceView)
        // Another display size moves the picture on screen: the glass's copy of it is out of place.
        videoFrame.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) backdrop?.frameChanged()
        }
        panel = SidePanel(this)
        buildMoreRow()
        applyLook()
        settingsPanels.onLookChanged = { applyLook() }
        settingsPanels.onChipsChanged = { updateChips() }
        displayMode = prefs.displayMode
        applyDim()

        // From Glass Home's Continue Watching row: the title just as its app first opened it, from where it was left.
        val resuming = intent.action == ContinueWatching.ACTION_RESUME
        if (resuming) {
            val resumed = ContinueWatching.resumeIntent(this, intent.getStringExtra(ContinueWatching.EXTRA_ID))
            if (resumed == null) {
                Toast.makeText(this, "This title can't be played from where it was left.", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            setIntent(resumed)
            resumedFromRow = true
        }
        if (!readIntent(intent)) {
            finish()
            return
        }
        // Straight from there, without asking (also from the start, for a show's next episode).
        if (resuming && startPositionMs == C.TIME_UNSET) startPositionMs = 0L
        applySubtitleStyle()
    }

    /**
     * Another title handed to this player while it's still around, rather than to a new one (the app that opened it
     * brought it back to the front with the new link): it starts over with the new title at once, so the film it had
     * never plays again in its place.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (stale || (intent.data == null && intent.action != ContinueWatching.ACTION_RESUME)) return
        replaced = true
        setIntent(intent)
        // The old film is silenced now; the rebuilt player reads the new intent from the start.
        player?.pause()
        recreate()
    }

    override fun onStart() {
        super.onStart()
        if (replaced) return
        // Come back to after a while away (the app that opened it reopened, Back pressed there): the film it had is
        // long over, and it closes before showing, so an old film never pops up. Back sooner, it's there but paused,
        // and never starts again by itself.
        if (stoppedAt > 0) {
            val away = SystemClock.elapsedRealtime() - stoppedAt
            stoppedAt = 0L
            if (away > AWAY_CLOSES_MS) {
                if (isTaskRoot || inPlayerTask()) finishAndRemoveTask() else finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
                return
            }
            savedPlayWhenReady = false
            pausedForSwitch = false
        }
        backdrop?.start()
        if (entries.isNotEmpty() && player == null) {
            // Back from Home with the TV on another refresh rate: it switches again as the window shows, so the film
            // waits for the picture instead of playing into the blank.
            val tvSwitching = frameRateMatcher.switchPending()
            val play = savedPlayWhenReady
            if (tvSwitching) {
                displaySwitchAt = SystemClock.elapsedRealtime()
                savedPlayWhenReady = false
            }
            initPlayer()
            if (tvSwitching && play) {
                pausedForSwitch = true
                handler.postDelayed(resumeAfterSwitch, DISPLAY_SWITCH_PAUSE_MS)
            }
        }
        if (sleepRemainingMs > 0) {
            sleepEndsAt = SystemClock.elapsedRealtime() + sleepRemainingMs
            sleepRemainingMs = 0
        }
        updateOverlay()
        handler.post(clockTick)
        handler.post(postPlayTick)
        // TiviMate's titles aren't looked up on TMDB while a film is on.
        TitleService.playing(this, true)
        handler.post(playingBeat)
        watchNotedAt = SystemClock.elapsedRealtime()
    }

    override fun onStop() {
        super.onStop()
        decoderWait?.invoke()
        decoderWait = null
        handler.removeCallbacks(playingBeat)
        TitleService.playing(this, false)
        commitSeek()
        seekPreview.release()
        notice = false
        retryPending = false
        savePosition()
        // Before the player goes, so no copy is taken of a film being taken down.
        backdrop?.stop()
        releasePlayer()
        pausedForSwitch = false
        pausedForFreeze = false
        freezeWatch.reset()
        if (!isFinishing) stoppedAt = SystemClock.elapsedRealtime()
        if (sleepEndsAt > 0) {
            sleepRemainingMs = (sleepEndsAt - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
            sleepEndsAt = 0
        }
        // A look-up still on its way is dropped with the player; it runs again on return.
        if (lookupInFlight) {
            lookupInFlight = false
            lookupRun++
            nextLookedUpFor = null
        }
        lookupWantedAt = 0L
        handler.removeCallbacksAndMessages(null)
        // That dropped the end of any refresh-rate wait (glassSettled); onStart starts another if the TV switches again.
        backdrop?.suspended = false
    }

    override fun onResume() {
        super.onResume()
        openingOwn = false
    }

    /**
     * Home (or another app opened over it) while the film plays: it ends as with Back, so nothing is left behind to
     * start again later, and the app that opened it gets the position. A paused film stays (the screensaver comes
     * this way too, and a paused film never starts by itself); so does one opening a screen of its own (an update).
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Playing, or only paused for the TV's refresh-rate switch or to get a frozen film going (it plays on after that)
        if (!openingOwn && !isFinishing && (player?.playWhenReady == true || pausedForSwitch || pausedForFreeze)) {
            finishWithResult(animate = false)
        }
    }

    override fun startActivity(intent: Intent, options: Bundle?) {
        openingOwn = true
        try {
            super.startActivity(intent, options)
        } catch (e: RuntimeException) {
            openingOwn = false
            throw e
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_PROCESS, PROCESS_MARK)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (stale) return
        io.execute { titleBook?.close() }
        // One replaced by a newer player leaves the screen's mode to that one.
        if (shown?.get() === this) {
            shown = null
            frameRateMatcher.reset()
        }
        pictureText.release()
        backdrop?.release()
        handler.removeCallbacks(lookUpSegmentsAgain)
        autoSync.release()
        io.shutdownNow()
        segmentWorker.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        FontImport.stopAll()
    }

    // ---------------------------------------------------------------------
    // Intent: TiviMate and other apps launch us with ACTION_VIEW (MX Player-style extras).

    private fun readIntent(intent: Intent): Boolean {
        val data = intent.data ?: return false
        val extras = intent.extras
        val title = extras?.getString("title") ?: extras?.getString("name")

        // Optional playlist, as MX Player's API defines it.
        @Suppress("DEPRECATION")
        val list = extras?.getParcelableArray("video_list")?.mapNotNull { it as? Uri }
        val names = extras?.getStringArray("video_list.name")
        val type = intent.type
        NextEpisode.status = null
        if (!list.isNullOrEmpty()) {
            list.forEachIndexed { i, uri -> entries += Entry(uri, names?.getOrNull(i)) }
            val index = list.indexOf(data)
            if (index >= 0) {
                startIndex = index
                entries[index] = Entry(data, title ?: names?.getOrNull(index), type)
            } else {
                // The video itself isn't in the list it came with; play it first.
                entries.add(0, Entry(data, title, type))
                startIndex = 0
            }
        } else {
            entries += Entry(data, title, type)
            entries.addAll(siblingFiles(data))
            findsNextEpisode = entries.size == 1 && NextEpisode.canFind(data)
            // Named only by its number: the show's name, if this show was found before, goes up at once.
            if (findsNextEpisode && !entries[0].info.named) {
                NextEpisode.rememberedShow(this, data)?.let { entries[0].showName = it }
            }
            // A show played before: its names and episodes as they were then, until the look-up has them fresh.
            if (findsNextEpisode) loadSavedGuide(data)
            NextEpisode.status = if (!findsNextEpisode && entries.size == 1 && NextEpisode.isEpisodeLink(data)) {
                "This episode's link isn't in the usual shape for your provider, so the next one can't be looked up."
            } else null
        }

        // Subtitles handed to us.
        @Suppress("DEPRECATION")
        val subs = extras?.getParcelableArray("subs")?.mapNotNull { it as? Uri }.orEmpty()
        val subNames = extras?.getStringArray("subs.name")
        subs.forEachIndexed { i, uri ->
            val name = subNames?.getOrNull(i)
            entries[startIndex].subtitles += subtitleConfig(uri, name, guessLanguage(name))
            entries[startIndex].handedSubtitles += uri to name
        }
        entries.forEach { it.subtitles += localSubtitles(it.uri) }
        // Subtitles downloaded for these videos before; the one on when the video was left goes on again.
        entries.forEachIndexed { i, e -> attachSaved(e, chooseOn = i == startIndex) }

        // Some apps send the position as an Int, others as a Long.
        @Suppress("DEPRECATION")
        val position = (extras?.get("position") as? Number)?.toLong() ?: 0L
        if (position > 0) startPositionMs = position
        return true
    }

    /** "English", "English (SDH)", "eng" or "en" → "en", so named subtitles sort and label properly. */
    private fun guessLanguage(name: String?): String? {
        val clean = name?.trim()?.lowercase() ?: return null
        if (clean.length in 2..3 && clean.all { it.isLetter() }) return clean
        return Locale.getISOLanguages().firstOrNull { code ->
            val display = Locale(code).getDisplayLanguage(Locale.ENGLISH).lowercase()
            display.length >= 4 && Regex("\\b${Regex.escape(display)}\\b").containsMatchIn(clean)
        }
    }

    /** For local files, the following files in the same folder become "next episode". */
    private fun siblingFiles(uri: Uri): List<Entry> {
        if (uri.scheme != "file") return emptyList()
        val file = File(uri.path ?: return emptyList())
        val dir = file.parentFile ?: return emptyList()
        val videos = dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in VIDEO_EXTENSIONS }
            ?.sortedBy { it.name.lowercase() } ?: return emptyList()
        val index = videos.indexOfFirst { it.name == file.name }
        if (index < 0) return emptyList()
        return videos.drop(index + 1).map { Entry(Uri.fromFile(it), null) }
    }

    /** Subtitle files next to a local video ("Movie.srt", "Movie.en.srt", "Subs/…"). */
    private fun localSubtitles(uri: Uri): List<MediaItem.SubtitleConfiguration> {
        if (uri.scheme != "file") return emptyList()
        val file = File(uri.path ?: return emptyList())
        val base = file.nameWithoutExtension.lowercase()
        val dirs = listOfNotNull(file.parentFile) + listOf("Subs", "Subtitles", "subs", "subtitles")
            .mapNotNull { name -> file.parentFile?.let { File(it, name) }?.takeIf { it.isDirectory } }
        return dirs.flatMap { dir -> dir.listFiles()?.toList().orEmpty() }
            .filter { it.isFile && it.extension.lowercase() in SUBTITLE_EXTENSIONS }
            .filter { it.name.lowercase().startsWith(base) || it.parentFile != file.parentFile }
            .map { sub ->
                val lang = sub.name.lowercase().removePrefix(base).split('.', '_', '-')
                    .firstOrNull { it.length in 2..3 && it != sub.extension.lowercase() }
                subtitleConfig(Uri.fromFile(sub), null, lang)
            }
    }

    private fun subtitleConfig(uri: Uri, label: String?, language: String?): MediaItem.SubtitleConfiguration {
        val ext = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val mime = when (ext) {
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "vtt" -> MimeTypes.TEXT_VTT
            "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        externalCounter++
        return MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setLanguage(language)
            .setLabel(label)
            .setId("$EXT_PREFIX$externalCounter")
            .build()
    }

    /** Puts the subtitles downloaded for this video before in its list, last used first. */
    private fun attachSaved(entry: Entry, chooseOn: Boolean) {
        val dir = SavedSubtitles.dir(this)
        SavedSubtitles.forVideo(this, entry.key).forEach { s ->
            val config = subtitleConfig(Uri.fromFile(File(dir, s.file)), null, s.language)
            val id = config.id ?: return@forEach
            entry.subtitles += config
            savedFiles[id] = s.file
            if (chooseOn && s.on && pendingExternalSubId == null) pendingExternalSubId = id
        }
    }

    /** The downloaded file behind a subtitle track, if it is one. */
    private fun savedFileOf(trackId: String?): String? {
        trackId ?: return null
        return savedFiles.entries.firstOrNull { (id, _) -> trackId == id || trackId.endsWith(":$id") }?.value
    }

    private fun headersFromIntent(): Map<String, String> {
        val arr = intent.getStringArrayExtra("headers") ?: return emptyMap()
        val map = HashMap<String, String>()
        var i = 0
        while (i + 1 < arr.size) {
            map[arr[i]] = arr[i + 1]
            i += 2
        }
        return map
    }

    private fun mediaItem(entry: Entry): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(entry.uri)
            .setMediaId(entry.key)
            .setSubtitleConfigurations(entry.subtitles.toList())
        val path = entry.uri.path?.lowercase().orEmpty()
        // Links without an extension rely on the type the launching app told us.
        val mime = when {
            path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> when (entry.mimeType?.lowercase()) {
                "application/x-mpegurl", "application/vnd.apple.mpegurl", "audio/x-mpegurl" -> MimeTypes.APPLICATION_M3U8
                "application/dash+xml" -> MimeTypes.APPLICATION_MPD
                else -> null
            }
        }
        if (mime != null) builder.setMimeType(mime)
        return builder.build()
    }

    // ---------------------------------------------------------------------
    // Player

    private fun initPlayer() {
        // Every way of building a player comes here: none is built until the decoder list is in, or it would wait on
        // a stuck decoder service forever.
        if (!DecoderCheck.answered) {
            if (decoderWait == null) {
                buffering.visibility = View.VISIBLE
                decoderWait = DecoderCheck.whenAnswered(DECODER_STUCK_MS, ready = {
                    decoderWait = null
                    if (decoderStuck) {
                        decoderStuck = false
                        errorBox.visibility = View.GONE
                    }
                    if (player == null && !isFinishing && !isDestroyed) initPlayer()
                }, stuck = { showDecoderStuck() })
            }
            return
        }
        val vsync = VsyncFix(this)
        val built = PlayerFactory.build(
            context = this,
            delays = delays,
            passthrough = prefs.passthrough,
            useAss = prefs.keepAssStyling,
            assSubtitleView = frameSubtitles,
            headers = headersFromIntent(),
            pictureText = pictureText,
            vsync = vsync,
            displaySwitch = displaySwitch,
            speechTap = speechTap,
            embeddedCues = embeddedCues,
        )
        vsyncFix = vsync.also { it.start() }
        // The TV's mode can change under the film (turned on, reconnected): its rate is asked for again then.
        frameRateMatcher.watch { rematchFrameRate() }
        val p = built.player
        player = p
        threadBeat = ThreadBeat(p.playbackLooper)
        builtWithAss = prefs.keepAssStyling
        assHandler = built.assHandler
        bufferWait = built.bufferWait
        p.setVideoSurfaceView(surfaceView)
        p.addListener(this)
        p.addAnalyticsListener(filmLoadErrors)
        p.addAnalyticsListener(droppedFrames)
        p.setVideoFrameMetadataListener(fpsMeter)
        fpsMeter.reset()
        p.pauseAtEndOfMediaItems = sleepAtEnd
        p.trackSelectionParameters = savedTrackParams ?: defaultTrackParams(p.trackSelectionParameters)

        val items = entries.map { mediaItem(it) }
        val index = startIndex.coerceIn(0, items.size - 1)
        val entry = entries[index]

        var position = startPositionMs
        var askResume = false
        if (position == C.TIME_UNSET) {
            val saved = prefs.savedPosition(entry.key)
            if (saved > 0) when (prefs.resume) {
                Prefs.Resume.ALWAYS -> position = saved
                // Loads from the saved spot while the question is up, so Resume starts at once.
                Prefs.Resume.ASK -> {
                    position = saved
                    askResume = true
                }
                Prefs.Resume.NEVER -> Unit
            }
        }
        playingIndex = index
        deviceRetries = 0
        // A new player starts its quiet retries afresh, and a retry meant for the old one doesn't touch it.
        retries = 0
        firstErrorAt = 0L
        waitingSince = 0L
        retryPending = false
        handler.removeCallbacks(retryRunnable)
        rebuildOnRetry = false
        // A new player behind the freeze message (a setting changed in front of it) answers it as OK would: the film is
        // watched from the first step again, or a second freeze would go unseen.
        if (frozeOut) freezeWatch.reset()
        frozeOut = false
        freezeWatch.jumped()
        cannotOpen = false
        previewCutOffs = 0
        // TIME_UNSET starts VOD at the beginning and live at the live edge.
        try {
            p.setMediaItems(items, index, position)
            p.prepare()
        } catch (e: RuntimeException) {
            // A kind of link the player has no module for (rtsp://) fails here instead of as a playback error.
            updateTitle()
            errorText.text = "FirePlayer can't open this kind of link."
            errorHint.text = "Press Back to return"
            errorBox.visibility = View.VISIBLE
            cannotOpen = true
            updateKeepScreenOn()
            return
        }
        if (askResume) {
            p.playWhenReady = false
            askToResume(prefs.savedPosition(entry.key))
        } else {
            p.playWhenReady = savedPlayWhenReady
        }
        updateTitle()
        updatePlayButton()
        updateKeepScreenOn()
        refreshLabels()
        // A rebuilt player has no subtitles yet; puts the style example back if its window is open.
        showTextCues()
        lineUpNext()
        learnRealEpisodes()
    }

    private fun defaultTrackParams(base: TrackSelectionParameters): TrackSelectionParameters {
        val b = base.buildUpon().setPreferredAudioLanguage(prefs.audioLanguage)
        if (prefs.subtitleLanguage.isEmpty()) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            b.setPreferredTextLanguage(prefs.subtitleLanguage)
        }
        return b.build()
    }

    /** Remembers where we are, so the player can be rebuilt or restored later. */
    private fun captureState() {
        val p = player ?: return
        startIndex = p.currentMediaItemIndex
        startPositionMs = if (p.isCurrentMediaItemLive) C.TIME_UNSET else p.currentPosition
        savedTrackParams = p.trackSelectionParameters
        savedPlayWhenReady = p.playWhenReady
    }

    private fun releasePlayer() {
        val p = player ?: return
        captureState()
        countPlaying(false)
        vsyncFix?.stop()
        vsyncFix = null
        frameRateMatcher.unwatch()
        threadBeat = null
        p.removeListener(this)
        // Its listener is gone, so the glass won't hear that it stopped playing.
        backdrop?.playing = false
        p.release()
        assHandler?.release()
        assHandler = null
        player = null
        // The old player can't clear its last line any more, so do it here.
        lastTextCues = emptyList()
        lastPictureCues = emptyList()
        shownTextCues = emptyList()
        shownPictureCues = emptyList()
        readingPictures = null
        handler.removeCallbacks(readingTimeout)
        screenSubtitles.setCues(emptyList())
        frameSubtitles.setCues(emptyList())
        // The ASS overlay view belongs to the old handler.
        for (i in frameSubtitles.childCount - 1 downTo 0) {
            val child = frameSubtitles.getChildAt(i)
            if (child.javaClass.name.contains("Ass")) frameSubtitles.removeViewAt(i)
        }
    }

    private fun rebuildPlayer() {
        releasePlayer()
        initPlayer()
    }

    /** The decoder service hasn't answered: say so once, instead of a spinner that never ends. */
    private fun showDecoderStuck() {
        if (decoderStuck || isFinishing) return
        decoderStuck = true
        hideBuffering()
        updateTitle()
        errorText.text = "Fire TV's video decoder is stuck, so no app can play video right now."
        errorHint.text = "Restart the Fire TV: Settings > My Fire TV > Restart (or hold Select and Play/Pause for 5 seconds)."
        errorBox.visibility = View.VISIBLE
        hideControls()
        updateKeepScreenOn()
    }

    private fun askToResume(positionMs: Long) {
        resumeDialogShowing = true
        FireDialog.ask(
            this,
            "Resume from ${formatTime(positionMs)}?",
            listOf<Pair<String, () -> Unit>>(
                // The player is already waiting at the saved spot.
                "Resume" to {
                    resumeDialogShowing = false
                    player?.play()
                    updateKeepScreenOn()
                },
                "Start over" to {
                    resumeDialogShowing = false
                    player?.seekTo(0)
                    player?.play()
                    updateKeepScreenOn()
                },
            ),
            // Back means "never mind": carry on from where you were rather than losing the spot.
            onDismiss = {
                resumeDialogShowing = false
                player?.play()
                updateKeepScreenOn()
            },
        )
    }

    private fun savePosition() {
        // Until the question is answered, the old resume point still stands.
        if (resumeDialogShowing) return
        val p = player ?: return
        val entry = entries.getOrNull(p.currentMediaItemIndex) ?: return
        val pos = p.currentPosition
        val dur = p.duration
        val nearEnd = dur != C.TIME_UNSET && dur - pos < 3 * 60_000
        prefs.savePosition(entry.key, if (pos > 30_000 && !nearEnd && !p.isCurrentMediaItemLive) pos else 0L)
        noteWatched(entry, pos, dur, finished = nearEnd, live = p.isCurrentMediaItemLive)
    }

    /**
     * Notes a film or episode for Glass Home's Continue Watching row (see [ContinueWatching]): where it is, how long it
     * really played since it was last noted, and whether it was watched to the end. Live channels never.
     */
    private fun noteWatched(entry: Entry, positionMs: Long, durationMs: Long, finished: Boolean, live: Boolean) {
        val played = takePlayed()
        watchNotedAt = SystemClock.elapsedRealtime()
        if (live) return
        // The episode found with the provider, or the one in the list that really is the next (see [lineUpNext]); otherwise
        // Continue Watching finds it by the title server's numbers itself.
        val next = entries.getOrNull(entries.indexOf(entry) + 1)?.takeIf { n ->
            val r = entry.real
            val nr = n.real
            n.nextEpisode || (r != null && nr != null && nr.series == r.series && nr > r)
        }
        ContinueWatching.watched(
            applicationContext,
            ContinueWatching.Played(
                uri = entry.uri,
                rawTitle = entry.rawTitle,
                type = entry.mimeType,
                headers = intent.getStringArrayExtra("headers")?.toList().orEmpty(),
                subtitles = entry.handedSubtitles.toList(),
                shown = entry.info,
                positionMs = positionMs,
                durationMs = durationMs.takeIf { it != C.TIME_UNSET } ?: 0L,
                playedMs = played,
                finished = finished,
                real = entry.real,
                next = next?.let { it.uri to it.rawTitle },
                nextReal = next?.real,
                match = matchFor(entry),
                nextMatch = next?.let { matchFor(it) },
            ),
        )
    }

    /**
     * What FirePlayer's own TMDB match says [entry] is, for Continue Watching: the show's guide (its TMDB id, name, year,
     * and the episode's TMDB numbers and name when the episode is matched), or a film's Fix match. Null for neither.
     */
    private fun matchFor(entry: Entry): WatchHistory.Match? {
        val g = guide
        val ep = guideEpisode(entry)
        if (g != null && ep != null && g.tmdbId > 0) {
            val m = SHOW_YEAR.matchEntire(g.show)
            val numbered = ep.match != ShowGuide.Match.NONE
            return WatchHistory.Match(
                tmdbId = g.tmdbId,
                title = m?.groupValues?.get(1)?.trim() ?: g.show,
                year = m?.groupValues?.get(2)?.toIntOrNull() ?: 0,
                season = if (numbered) ep.season else 0,
                episode = if (numbered) ep.number else 0,
                episodeTitle = if (numbered) ep.name.ifEmpty { null } else null,
            )
        }
        if (entry.info.episode != null || NextEpisode.isEpisodeLink(entry.uri)) return null
        val portal = NextEpisode.portal(entry.uri) ?: return null
        val id = entry.uri.lastPathSegment?.substringBeforeLast('.') ?: return null
        val f = matchFixes.filmMatch(portal, id) ?: return null
        return WatchHistory.Match(f.tmdbId, f.name, f.year)
    }

    /** After a Fix match: Continue Watching's row for this video takes the new match at once. */
    private fun noteRematch(entry: Entry, episode: Boolean) {
        val m = matchFor(entry) ?: return
        ContinueWatching.rematch(applicationContext, entry.uri, episode, m)
    }

    /** From [postPlayTick]: now and then while it plays, so a crash, a power cut or Fire OS stopping it loses little. */
    private fun noteWhilePlaying() {
        val p = player ?: return
        if (!p.isPlaying || resumeDialogShowing || SystemClock.elapsedRealtime() - watchNotedAt < WATCH_NOTE_EVERY_MS) return
        val entry = entries.getOrNull(p.currentMediaItemIndex) ?: return
        val pos = p.currentPosition
        val dur = p.duration
        // Not in the last minutes ("watched to the end" on leaving): credits playing on after a finish was noted would
        // bring the title back. Leaving or moving on notes it, with the time played meanwhile.
        if (dur != C.TIME_UNSET && dur - pos < 3 * 60_000) return
        // The resume point too: behind Home, Fire OS stops FirePlayer outright when memory is short, without onStop.
        if (pos > 30_000 && !p.isCurrentMediaItemLive) prefs.savePosition(entry.key, pos)
        noteWatched(entry, pos, dur, finished = false, live = p.isCurrentMediaItemLive)
    }

    /** Adds up the time the film really plays: not paused, loading, or while a jump is being lined up. */
    private fun countPlaying(isPlaying: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (playingSince > 0) playedMs += now - playingSince
        playingSince = if (isPlaying && !seeking) now else 0L
    }

    private fun takePlayed(): Long {
        countPlaying(player?.isPlaying == true)
        return playedMs.also { playedMs = 0L }
    }

    /** Lets TiviMate know where we stopped (MX Player result contract). */
    private fun finishWithResult(animate: Boolean = true) {
        val p = player
        val result = Intent("com.mxtech.intent.result.VIEW")
        // Which episode the position is for, once a list has moved on to the next one. Web links only: handing back a
        // file:// link would crash the app on newer Android.
        // Never for a title opened from Continue Watching: its link (with the provider's login) is FirePlayer's alone.
        entries.getOrNull(p?.currentMediaItemIndex ?: 0)?.uri
            ?.takeIf { !resumedFromRow && (it.scheme == "http" || it.scheme == "https") }
            ?.let { result.data = it }
        // A finished video is reported as finished, without a position, as MX Player does.
        if (p != null && !completed) {
            val duration = p.duration.takeIf { it != C.TIME_UNSET }
            result.putExtra("position", p.currentPosition.toInt())
            duration?.let { result.putExtra("duration", it.toInt()) }
            // VLC's names too, for apps that only read those.
            result.putExtra("extra_position", p.currentPosition)
            duration?.let { result.putExtra("extra_duration", it) }
        }
        savePosition()
        // Silence at once; the player itself is released only after TiviMate is back on screen.
        p?.pause()
        result.putExtra("end_by", if (completed) "playback_completion" else "user")
        setResult(RESULT_OK, result)
        // Straight back to the app that opened us, without a lingering empty task. Opened by another app, the whole
        // player task goes: a player left in it from before (Home pressed, then Fire OS closed the app, so the newer
        // one couldn't close it) would otherwise come back and play the old film on Back.
        if (isTaskRoot || inPlayerTask()) finishAndRemoveTask() else finish()
        // Leaving with Home, Fire OS's own animation stays
        if (animate) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, android.R.anim.fade_out)
        }
    }

    /** This player's task was started by a player (opened from another app), not by FirePlayer's own home screen. */
    private fun inPlayerTask(): Boolean = try {
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).appTasks.any { t ->
            val info = t.taskInfo
            val id = if (Build.VERSION.SDK_INT >= 29) info.taskId else @Suppress("DEPRECATION") info.persistentId
            id == taskId && info.baseIntent.component?.className == PlayerActivity::class.java.name
        }
    } catch (e: RuntimeException) {
        false
    }

    // ---------------------------------------------------------------------
    // Player.Listener

    override fun onPlaybackStateChanged(state: Int) {
        handler.removeCallbacks(showBuffering)
        updateKeepScreenOn()
        if (state == Player.STATE_BUFFERING) {
            shownPercent = 0
            handler.postDelayed(showBuffering, 400)
            // A wait that quiet retries carry on with is still the same wait.
            if (retries == 0) waitingSince = SystemClock.elapsedRealtime()
        } else if (!(state == Player.STATE_IDLE && retryPending)) {
            hideBuffering()
        }
        if (state == Player.STATE_READY) {
            if (glassAfterSeek) {
                glassAfterSeek = false
                backdrop?.frameChanged()
            }
            findNextEpisode()
            errorBox.visibility = View.GONE
            // The stopped player loaded again behind the freeze message (a subtitle added): it plays, so it's answered.
            if (frozeOut) {
                frozeOut = false
                freezeWatch.reset()
            }
            waitingSince = 0L
            retries = 0
            retryPending = false
            handler.removeCallbacks(deviceRetriesBack)
            handler.postDelayed(deviceRetriesBack, DEVICE_RETRY_WINDOW_MS / 2)
        }
        if (state == Player.STATE_ENDED && seeking && seekOffsetMs < 0) {
            // The film ended while a jump back in it was being lined up: make that jump instead of closing.
            commitSeek()
            player?.play()
            return
        }
        // An end the jump above has already left behind (the player is loading the new spot).
        if (state == Player.STATE_ENDED && player?.playbackState != Player.STATE_ENDED) {
            updateProgress()
            return
        }
        // Nothing could be opened at all: that isn't the film played to the end.
        if (state == Player.STATE_ENDED && player?.mediaItemCount == 0) {
            updateProgress()
            return
        }
        if (state == Player.STATE_ENDED) {
            completed = true
            prefs.savePosition(entries.getOrNull(player?.currentMediaItemIndex ?: 0)?.key ?: "", 0L)
            finishWithResult()
        }
        updateProgress()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        countPlaying(isPlaying)
        backdrop?.playing = isPlaying
        updatePlayButton()
        updateKeepScreenOn()
        if (isPlaying) scheduleHide() else handler.removeCallbacks(hideControlsRunnable)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (sleepAtEnd && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && seeking && seekOffsetMs < 0) {
            // Same for a jump back right at the end with sleep set for the end of the video: it still stops there later.
            commitSeek()
            player?.play()
            return
        }
        updateKeepScreenOn()
        // Only while still paused there: a jump back made at the very end has already set it playing again.
        if (sleepAtEnd && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && player?.playWhenReady == false) {
            completed = true
            entries.getOrNull(player?.currentMediaItemIndex ?: -1)?.let { prefs.savePosition(it.key, 0L) }
            finishWithResult()
            return
        }
        if (stylePreviewing) showTextCues()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val now = player?.currentMediaItemIndex ?: playingIndex
        if (seeking && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && now != playingIndex && seekOffsetMs < 0) {
            // The episode ended while a jump back in it was being lined up: make that jump instead of moving on.
            val target = seekTarget()
            val previous = playingIndex
            cancelSeek()
            player?.seekTo(previous, target)
            return
        }
        // An episode that played to the end starts from the top next time.
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && now != playingIndex) {
            entries.getOrNull(playingIndex)?.let {
                prefs.savePosition(it.key, 0L)
                noteWatched(it, 0L, C.TIME_UNSET, finished = true, live = false)
            }
        }
        // Any other jump being lined up was for the video that was playing, not this one.
        if (seeking) cancelSeek()
        if (now != playingIndex) {
            pictureText.discardAhead()
            // Another episode: the refresh rate is checked again, and the sound and subtitles picked are matched.
            matchedFpsForIndex = -1
            refusedForIndex = -1
            rematches = 0
            reapplyWishes = trackWishes.isNotEmpty()
        }
        playingIndex = now
        deviceRetries = 0
        freezeWatch.jumped()
        measuredFps = 0f
        fpsMeter.reset()
        lineUpNext()
        updateTitle()
        refreshLabels()
        // Moving on by itself keeps the picture up, so the episode after this one is looked up from here.
        if (player?.playbackState == Player.STATE_READY) findNextEpisode()
    }

    override fun onTracksChanged(tracks: Tracks) {
        // Another sound track (or the same one set up again) gets its own time to start.
        freezeWatch.jumped()
        pendingExternalSubId?.let { id ->
            val option = trackOptions(C.TRACK_TYPE_TEXT).firstOrNull { sameId(it, id) }
            if (option != null) {
                pendingExternalSubId = null
                selectTrack(option)
                // A downloaded one back on screen counts as used.
                savedFiles[id]?.let { name -> currentEntry()?.let { SavedSubtitles.use(this, it.key, name) } }
            }
        }
        // Adding a subtitle reloads the video; stay on the audio track that was playing.
        pendingAudioId?.let { id ->
            val option = trackOptions(C.TRACK_TYPE_AUDIO).firstOrNull { sameId(it, id) }
            if (option != null) {
                pendingAudioId = null
                if (!option.selected) selectTrack(option)
            }
        }
        recallAudio()
        if (reapplyWishes) reapplyChoices()
        followSubtitle()
        refreshLabels()
        updateChips()
        matchFrameRate()
        updatePictureReading()
        // "Keep embedded styling" was switched before an ASS track showed up (still loading, or added later).
        if (stylingNeedsRebuild()) {
            handler.removeCallbacks(rebuildForAss)
            handler.post(rebuildForAss)
        }
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        applyDisplayMode()
        updateChips()
        backdrop?.frameChanged()
    }

    override fun onRenderedFirstFrame() {
        // The first picture of a new player or video, or after a jump: the glass copies it.
        backdrop?.frameChanged()
    }

    override fun onCues(cueGroup: CueGroup) {
        val (pictures, text) = cueGroup.cues.partition { it.bitmap != null }
        lastPictureCues = pictures
        lastTextCues = text
        showCues()
    }

    /** Shows what changed on screen: a new subtitle, or a picture subtitle that was read (or wasn't, in time). */
    private fun showCues() {
        showTextCues()
        // It can have more lines, or (a picture) sit elsewhere: move it clear of the controls at once.
        if (controlsShown || seeking || notice) liftSubtitles(animate = false)
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        // Every jump, the viewer's or FreezeWatch's own: the film's time starts afresh there.
        freezeWatch.jumped()
        // A jump while already waiting is a new wait (the state doesn't change), so the percentage starts again.
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            restartPostPlayCount(newPosition.positionMs)
            shownPercent = 0
            percentFrom = SystemClock.elapsedRealtime()
            glassAfterSeek = true
        }
        // Going back reloads the film from there (nothing behind is kept), so what was lined up to read is stale.
        if (reason == Player.DISCONTINUITY_REASON_SEEK && newPosition.positionMs < oldPosition.positionMs) {
            pictureText.discardAhead()
        }
    }

    /**
     * Draws the current subtitles in the chosen style. While the Subtitle style window is open
     * and the video is paused with nothing on screen, example text shows the style instead.
     */
    private fun showTextCues() {
        readPictures()
        // libass draws its own subtitles; picture subtitles are images. Example text would sit on top of them.
        val otherShowing = lastPictureCues.isNotEmpty() || libassShowing()
        // A new episode's first downloaded lines can wait a moment for auto sync to say where they go.
        val held = shownTextCues.isNotEmpty() && ::autoSync.isInitialized && autoSync.holdsCues()
        val text = if (held) emptyList() else styler.normalize(shownTextCues)
        val preview = stylePreviewing && text.isEmpty() && !otherShowing && player?.playWhenReady != true
        screenSubtitles.setCues(if (preview) styler.sample() else text)
        frameSubtitles.setCues(styler.pictures(shownPictureCues))
    }

    /**
     * Picture subtitles (Blu-ray, DVD) are drawn as text in the chosen style when the file's own styling is off,
     * using their text as read by [PictureText]. Most are read ahead; one that isn't is kept back for a moment
     * while it's read, then shown as it is if that takes too long. Pictures with no text in them stay pictures.
     */
    private fun readPictures() {
        shownTextCues = lastTextCues
        shownPictureCues = lastPictureCues
        val language = selectedFormat(C.TRACK_TYPE_TEXT)?.language
        if (lastPictureCues.isEmpty() || styler.keepEmbedded || !pictureText.canRead(language)) {
            readingPictures = null
            return
        }
        val texts = lastPictureCues.map { cue -> cue.bitmap?.let { pictureText.textOf(it) } }
        if (texts.any { it == null }) {
            val now = SystemClock.uptimeMillis()
            if (readingPictures !== lastPictureCues) {
                readingPictures = lastPictureCues
                readingUntil = now + PICTURE_READ_WAIT_MS
                for (cue in lastPictureCues) cue.bitmap?.let { pictureText.prepare(it, language, soon = true) }
                handler.removeCallbacks(readingTimeout)
                handler.postDelayed(readingTimeout, PICTURE_READ_WAIT_MS)
            }
            // Kept back while it's read; after that it shows as a picture until (and unless) its text arrives.
            if (now < readingUntil) shownPictureCues = emptyList()
            return
        }
        readingPictures = null
        handler.removeCallbacks(readingTimeout)
        // Top to bottom, as they sit on screen.
        val read = lastPictureCues.indices.filter { texts[it]!!.isNotEmpty() }
            .sortedBy { lastPictureCues[it].line.takeIf { line -> line != Cue.DIMEN_UNSET } ?: 0f }
        if (read.isEmpty()) return
        shownTextCues = if (styler.filePosition) {
            // "As in the file": each picture's words go where the picture was (bottom ones still stack at the bottom).
            lastTextCues + read.map { placedLikePicture(lastPictureCues[it], texts[it]!!) }
        } else {
            lastTextCues + Cue.Builder().setText(read.joinToString("\n") { texts[it]!! }).build()
        }
        shownPictureCues = lastPictureCues.filterIndexed { i, _ -> texts[i]!!.isEmpty() }
    }

    private fun libassShowing(): Boolean = assHandler != null && selectedFormat(C.TRACK_TYPE_TEXT)?.let {
        it.sampleMimeType == MimeTypes.TEXT_SSA || it.codecs == MimeTypes.TEXT_SSA
    } == true

    override fun onPlayerError(error: PlaybackException) {
        val p = player ?: return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            p.seekToDefaultPosition()
            p.prepare()
            return
        }
        val now = SystemClock.elapsedRealtime()
        // Sound or a decoder that won't start is usually the TV still switching refresh rate, or the app
        // that opened us not having let go of it yet. The player only waits a fifth of a second for it;
        // starting again a moment later works.
        val device = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED || error.errorCode in 5000..5999
        handler.removeCallbacks(deviceRetriesBack)
        if (device) {
            if (deviceRetries == 0) firstDeviceErrorAt = now
            // Like the network retries, these stop after a while, so a failure that keeps coming back ends in an error.
            if (deviceRetries < MAX_DEVICE_RETRIES && now - firstDeviceErrorAt < DEVICE_RETRY_WINDOW_MS) {
                deviceRetries++
                // Not before a refresh-rate switch has settled, or it fails the same way.
                retryQuietly(maxOf(1_000L * deviceRetries, displaySwitchAt + DISPLAY_SETTLE_MS - now))
                return
            }
        }
        // Dropped connections and slow servers (common right after a jump) are retried quietly,
        // waiting a little longer each time, before an error is shown.
        val status = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        // A missing video won't come back. Other refusals are retried: IPTV servers often answer 403
        // for a few seconds after a jump, while the old connection still counts against the limit.
        // A live channel's server may answer 404 for a moment while its source restarts; that one is tried again.
        val permanent = (status == 404 || status == 410) && !p.isCurrentMediaItemLive
        val network = error.errorCode in 2000..2999 && error.errorCode !in NOT_RETRIED && !permanent
        // The player has already tried a few times by the time an error comes, so the time counts from when the
        // viewer started waiting, not from the error.
        if (retries == 0) {
            firstErrorAt = if (waitingSince > 0) waitingSince else now
            roundStartedAt = firstErrorAt
        }
        // Quiet retries stop after a while, so a server that's down shows an error instead of a spinner forever. One
        // more try is only made if it would end inside that time, guessing it takes as long as the last one did.
        // The first error is always tried again: the wait before it may have been mostly film arriving, slowly.
        val nextRoundEndsAt = now + (now - roundStartedAt)
        if (network && retries < MAX_RETRIES && (retries == 0 || nextRoundEndsAt - firstErrorAt < RETRY_WINDOW_MS)) {
            retries++
            val step = if (status != null && status in 400..499) 3_000L else 1_000L
            retryQuietly(step * retries)
            return
        }
        retries = 0
        deviceRetries = 0
        retryPending = false
        hideBuffering()
        // The error box needs OK, so a settings window can't be left in front of it.
        if (panel.isOpen) panel.close()
        errorText.text = errorMessage(error, status)
        // The number helps if the error has to be reported; the name of the code would be noise here.
        errorHint.text = "Press OK to try again  ·  Error ${error.errorCode}"
        errorBox.visibility = View.VISIBLE
        updateKeepScreenOn()
        hideControls()
        checkScreens(error, status)
    }

    /**
     * When the video wouldn't load because every screen the provider allows is in use, the error says so. The
     * provider is asked once the error is up, when the player's own connection has let go.
     */
    private fun checkScreens(error: PlaybackException, status: Int?) {
        val shown = ++errorsShown
        if (error.errorCode !in 2000..2999 || status == 404 || status == 410) return
        val uri = entries.getOrNull(player?.currentMediaItemIndex ?: startIndex)?.uri ?: return
        if (XtreamAccount.login(uri) == null) return
        val account = XtreamAccount(headersFromIntent())
        thread(name = "FirePlayerScreens", isDaemon = true) {
            val screens = account.screens(uri)?.takeIf { it.full } ?: return@thread
            handler.post {
                if (isFinishing || shown != errorsShown || errorBox.visibility != View.VISIBLE) return@post
                errorText.text = "Your account's screen limit is reached (${screens.inUse} of ${screens.allowed} in use). " +
                    "Stop watching on another device, then try again."
            }
        }
    }

    /** Tries again after [delayMs] with the spinner up instead of an error. */
    private fun retryQuietly(delayMs: Long) {
        retryPending = true
        shownPercent = 0
        handler.removeCallbacks(showBuffering)
        buffering.visibility = View.VISIBLE
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, delayMs)
    }

    /**
     * From [postPlayTick]: a film meant to be playing whose time stands still is started again, step by step (see
     * [FreezeWatch]). With the TV off, Fire OS's sound once stopped taking the film's sound, and the picture stood still
     * for half an hour with nothing said, until the viewer paused and played again.
     */
    private fun checkFreeze() {
        val p = player ?: return
        val now = SystemClock.elapsedRealtime()
        // Held on purpose, or something in front of the film (Alexa, a system window): not watched.
        val held = seeking || pausedForSwitch || pausedForFreeze || retryPending || resumeDialogShowing ||
            errorBox.visibility == View.VISIBLE || completed || isFinishing || !hasWindowFocus()
        val event = freezeWatch.check(
            FreezeWatch.Sample(
                playing = p.isPlaying,
                waiting = p.playWhenReady && p.playbackState == Player.STATE_BUFFERING,
                held = held,
                positionMs = p.currentPosition,
                bufferedMs = p.bufferedPosition,
                durationMs = p.duration,
                live = p.isCurrentMediaItemLive,
                seekable = p.isCurrentMediaItemSeekable,
                switchAtMs = displaySwitchAt,
                threadSilentMs = threadBeat?.silentMs(now) ?: 0L,
            ),
        ) ?: return
        if (event is FreezeWatch.Event.Recovered) {
            val how = when (event.after) {
                FreezeWatch.Step.PAUSE_PLAY -> "pausing and playing"
                FreezeWatch.Step.JUMP -> "jumping to the same spot"
                else -> "a new player"
            }
            android.util.Log.i(TAG, "Film playing again at ${formatTime(event.positionMs, long = true)} after $how " +
                "(stuck ${event.stuckMs / 1000} s)")
            return
        }
        val act = event as FreezeWatch.Event.Act
        val at = formatTime(act.positionMs, long = true)
        val stuck = "${act.stuckMs / 1000} s"
        when (act.step) {
            FreezeWatch.Step.PAUSE_PLAY -> {
                android.util.Log.w(TAG, "Film stuck at $at for $stuck with ${act.aheadMs / 1000} s loaded ahead: pausing " +
                    "and playing again (the sound may have stopped taking it)")
                pausedForFreeze = true
                p.pause()
                handler.removeCallbacks(resumeAfterFreeze)
                handler.postDelayed(resumeAfterFreeze, FREEZE_PAUSE_MS)
            }
            FreezeWatch.Step.JUMP -> {
                android.util.Log.w(TAG, "Still stuck at $at ($stuck): jumping to the same spot, which starts the sound afresh")
                // Exactly a millisecond on: a jump to where the film already is does nothing at all, and one allowed onto a
                // keyframe could land on the very picture it stands on. Forward, so subtitles read ahead are kept.
                p.setSeekParameters(SeekParameters.EXACT)
                p.seekTo(p.currentPosition + 1)
                p.setSeekParameters(PlayerFactory.FILM_SEEK)
            }
            FreezeWatch.Step.NEW_PLAYER -> {
                val why = if (act.threadSilent) "The player hasn't answered for $stuck at $at" else "Still stuck at $at ($stuck)"
                android.util.Log.w(TAG, "$why: building a new player")
                rebuildOnRetry = true
                retryQuietly(0L)
            }
            FreezeWatch.Step.MESSAGE -> {
                android.util.Log.w(TAG, "Still stuck at $at ($stuck) after a new player: showing a message")
                showFrozen()
            }
        }
        logHdmiSound()
    }

    private val resumeAfterFreeze = Runnable {
        if (pausedForFreeze) {
            pausedForFreeze = false
            player?.play()
        }
    }

    /**
     * The film froze and nothing started it again: it's stopped (at once, even if the player's own thread is stuck), which
     * lets go of the sound, the decoders and the provider's connection and lets the TV sleep, and the viewer is told.
     */
    private fun showFrozen() {
        player?.stop()
        frozeOut = true
        retries = 0
        deviceRetries = 0
        retryPending = false
        hideBuffering()
        // The error box needs OK, so a settings window can't be left in front of it.
        if (panel.isOpen) panel.close()
        errorText.text = "The film froze and couldn't be started again. Fire TV's sound may have stopped working " +
            "(it can while the TV is off)."
        errorHint.text = "Press OK to try again  ·  If it keeps freezing, restart the Fire TV"
        errorBox.visibility = View.VISIBLE
        updateKeepScreenOn()
        hideControls()
    }

    /**
     * For the logs: whether Fire OS has the TV's HDMI sound output just now (none while the TV is off). Asked on a thread
     * of its own, as the sound system may be what's stuck.
     */
    private fun logHdmiSound() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val audio = getSystemService(AudioManager::class.java) ?: return
        thread(name = "FirePlayerSoundOutputs", isDaemon = true) {
            val outputs = runCatching { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrNull() ?: return@thread
            val hdmi = outputs.any { it.type == AudioDeviceInfo.TYPE_HDMI }
            android.util.Log.i(TAG, "HDMI sound output while the film was stuck: " + if (hdmi) "there" else "none (the TV may be off)")
        }
    }

    private fun errorMessage(error: PlaybackException, status: Int?): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Can't reach the video. Check your connection."
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> when (status) {
            404, 410 -> "This video isn't on the server any more."
            401, 403 -> "The server refused the video. Your account may be busy on another device."
            else -> "The server refused the video."
        }
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The video file couldn't be found."
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "FirePlayer isn't allowed to open this file."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "This video file looks damaged."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "This kind of video file isn't supported."
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "This video format isn't supported on this device."
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "The sound couldn't start. Try Surround passthrough Off in Settings."
        else -> "The video stopped playing."
    }

    private val showBuffering = Runnable {
        buffering.visibility = View.VISIBLE
        handler.removeCallbacks(updateBufferingInfo)
        handler.post(updateBufferingInfo)
    }

    private fun hideBuffering() {
        buffering.visibility = View.GONE
        bufferingInfo.visibility = View.GONE
        handler.removeCallbacks(updateBufferingInfo)
    }

    /**
     * While the film is loading before it plays, how much of the wait is done, and when the connection brings the film
     * slower than it plays, that this is why the wait is long. Nothing while it's still opening the file or retrying.
     */
    private val updateBufferingInfo = object : Runnable {
        override fun run() {
            if (buffering.visibility != View.VISIBLE) {
                bufferingInfo.visibility = View.GONE
                return
            }
            val wait = bufferWait()
            val current = !retryPending && wait != null && wait.atMs > percentFrom &&
                SystemClock.elapsedRealtime() - wait.atMs < 1_000
            if (current && wait != null) {
                // The target can grow as the connection is measured; the number shown only goes up.
                shownPercent = maxOf(shownPercent, (wait.fraction * 100).toInt().coerceAtMost(99))
                val percent = shownPercent
                bufferingInfo.text = if (wait.slowLine) "$percent%\nConnection slower than this film" else "$percent%"
            }
            bufferingInfo.visibility = if (current) View.VISIBLE else View.GONE
            handler.postDelayed(this, 250)
        }
    }

    /**
     * The server turning the film away just after the preview asked for video, or cutting the film's connection off
     * just as the preview connected, usually means it allows one connection at a time; previews then stop so the film
     * keeps it. Only the film's own link counts (not a subtitle file), and only an answer that means "no" or a
     * connection dropped at that moment (not a hiccup the film retries past).
     */
    // Auto sync waits while the picture drops frames: the film comes first.
    private val droppedFrames = object : AnalyticsListener {
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            autoSync.framesDropped()
        }
    }

    private val filmLoadErrors = object : AnalyticsListener {
        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            val film = entries.getOrNull(playingIndex)?.uri ?: return
            if (loadEventInfo.dataSpec.uri != film) return
            val refused = SeekPreview.isRefusal(error) && seekPreview.requestedWithin(8_000)
            // A server that cuts the older connection off when another opens: a working film connection dropped just
            // as the preview connected. Twice, so one ordinary drop doesn't end previews.
            var cutOff = false
            if (SeekPreview.isCutOff(error) && IdleCloseDataSource.openedWithin(SeekPreview.CUT_OFF_MS)) {
                val now = SystemClock.elapsedRealtime()
                // Twice close together, not two chance drops far apart.
                if (now - lastPreviewCutOffAt > CUT_OFF_PAIR_MS) previewCutOffs = 0
                lastPreviewCutOffAt = now
                cutOff = ++previewCutOffs >= 2
            }
            if (refused || cutOff) seekPreview.serverRefused()
        }
    }

    // ---------------------------------------------------------------------
    // Views

    private fun bindViews() {
        root = findViewById(R.id.root)
        videoFrame = findViewById(R.id.video_frame)
        surfaceView = findViewById(R.id.surface_view)
        frameSubtitles = findViewById(R.id.frame_subtitles)
        screenSubtitles = findViewById(R.id.screen_subtitles)
        buffering = findViewById(R.id.buffering)
        postPlay = findViewById(R.id.post_play)
        postPlayNext = findViewById(R.id.post_play_next)
        postPlayCredits = findViewById(R.id.post_play_credits)
        postPlayGo = findViewById(R.id.post_play_go)
        skipButton = findViewById(R.id.skip_button)
        skipButton.chosen = true
        skipButton.pod = true
        skipButton.setOnClickListener { skipPart() }
        skipPod = findViewById(R.id.skip_pod)
        skipPod.pod = true
        skipPod.setOnClickListener { skipPart() }
        dimmerView = findViewById(R.id.dimmer)
        postPlayGo.countdown = { postPlayProgress() }
        postPlayIcon = postPlayGo.compoundDrawablesRelative[0]
        bufferingInfo = findViewById(R.id.buffering_info)
        scrimTop = findViewById(R.id.scrim_top)
        scrimBottom = findViewById(R.id.scrim_bottom)
        topBar = findViewById(R.id.top_bar)
        titleView = findViewById(R.id.title)
        subtitleView = findViewById(R.id.subtitle)
        clockView = findViewById(R.id.clock)
        bottomBlock = findViewById(R.id.bottom_block)
        chips = findViewById(R.id.chips)
        chipResolution = findViewById(R.id.chip_resolution)
        chipFps = findViewById(R.id.chip_fps)
        chipAudio = findViewById(R.id.chip_audio)
        chipPortal = findViewById(R.id.chip_portal)
        seekBubble = findViewById(R.id.seek_bubble)
        seekPreview = SeekPreview(
            card = findViewById(R.id.preview_card),
            frame = findViewById(R.id.preview_frame),
            texture = findViewById(R.id.preview_texture),
            dim = findViewById(R.id.preview_dim),
            time = findViewById(R.id.preview_time),
            format = { formatTime(it, longTimes == true) },
            create = { allowLoading -> PlayerFactory.buildPreview(this, headersFromIntent(), allowLoading) },
            // Subtitles from where the film is now would sit under a picture of somewhere else; they step aside.
            onShowing = { showing ->
                val alpha = if (showing) 0f else 1f
                screenSubtitles.animate().alpha(alpha).setDuration(150).start()
                frameSubtitles.animate().alpha(alpha).setDuration(150).start()
            },
            // Mid-skip the card shows the "+4:00"; the note waits for the jump.
            onFailed = { note -> if (seeking) pendingNotice = note else showNotice(note, NOTE_MS) },
        )
        seekRow = findViewById(R.id.seek_row)
        timeElapsed = findViewById(R.id.time_elapsed)
        seekBar = findViewById(R.id.seek_bar)
        timeRemaining = findViewById(R.id.time_remaining)
        buttonsArea = findViewById(R.id.buttons_area)
        mainRow = findViewById(R.id.main_row)
        moreRow = findViewById(R.id.more_row)
        arrowDown = findViewById(R.id.arrow_down)
        arrowUp = findViewById(R.id.arrow_up)
        btnPlay = findViewById(R.id.btn_play)
        btnRestart = findViewById(R.id.btn_restart)
        btnNext = findViewById(R.id.btn_next)
        nextGroup = findViewById(R.id.next_group)
        btnEpisodes = findViewById(R.id.btn_episodes)
        episodesGroup = findViewById(R.id.episodes_group)
        btnSubs = findViewById(R.id.btn_subs)
        btnAudio = findViewById(R.id.btn_audio)
        subsLabel = findViewById(R.id.subs_label)
        audioLabel = findViewById(R.id.audio_label)
        errorBox = findViewById(R.id.error_box)
        errorHint = findViewById(R.id.error_hint)
        errorText = findViewById(R.id.error_text)

        // Holds focus while the controls are hidden.
        focusSink = View(this).apply { isFocusable = true }
        root.addView(focusSink, 1, 1)
        focusSink.requestFocus()

        // The design uses the D-pad for frame-precise seeking, so the controls never auto-scroll.
        frameSubtitles.setApplyEmbeddedStyles(true)

        btnPlay.setOnClickListener { togglePlay() }
        btnRestart.setOnClickListener { player?.seekTo(0); player?.play() }
        btnNext.setOnClickListener { lineUpNext(); savePosition(); player?.seekToNextMediaItem(); player?.play() }
        btnSubs.setOnClickListener { openSubtitlesPanel(btnSubs) }
        btnAudio.setOnClickListener { openAudioPanel(btnAudio) }
        btnEpisodes.setOnClickListener { openEpisodes() }
        focusEffect(btnEpisodes, 1.08f, findViewById(R.id.episodes_label))
        episodesSheet = EpisodesSheet(this, root).apply {
            onPlay = { ep -> playEpisode(ep) }
            onClosed = { fadeSubtitles(true); showControls(btnEpisodes) }
        }
        focusEffect(btnPlay, 1.08f)
        focusEffect(btnRestart, 1.08f, findViewById(R.id.restart_label))
        focusEffect(btnNext, 1.08f, findViewById(R.id.next_label))
        focusEffect(btnSubs, 1.04f, findViewById(R.id.subs_name))
        focusEffect(btnAudio, 1.04f, findViewById(R.id.audio_name))
        seekBar.onThumbMoved = { placeBubble() }
        timeRemaining.gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }

    /**
     * Selected buttons grow slightly, so moving around feels smooth rather than jumpy. A round button's [label] fades in
     * under it only while it's selected, and grows with it from the button's middle, so it keeps its 4dp gap below the
     * circle the way the icon row's names do (they grow with their whole item).
     */
    private fun focusEffect(button: View, scale: Float, label: View? = null) {
        button.setOnFocusChangeListener { v, hasFocus ->
            val s = if (hasFocus) scale else 1f
            // Eased, like the rest of the controls, rather than at an even pace.
            v.animate().scaleX(s).scaleY(s).setDuration(140).setInterpolator(FOCUS_EASE).start()
            if (label != null) {
                label.pivotX = v.left + v.width / 2f - label.left
                label.pivotY = v.top + v.height / 2f - label.top
                if (hasFocus) label.visibility = View.VISIBLE
                label.animate().alpha(if (hasFocus) 1f else 0f).scaleX(s).scaleY(s).setDuration(140).setInterpolator(FOCUS_EASE)
                    .withEndAction { if (!v.isFocused) label.visibility = View.INVISIBLE }
                    .start()
            }
        }
    }

    private fun buildMoreRow() {
        fun add(key: String, icon: Int, label: String, open: (View) -> Unit) {
            val v = LayoutInflater.from(this).inflate(R.layout.item_more, moreRow, false)
            v.findViewById<ImageView>(R.id.more_icon).setImageResource(icon)
            v.contentDescription = label
            val value = v.findViewById<TextView>(R.id.more_value)
            v.setOnClickListener { open(v) }
            focusEffect(v, 1.06f)
            moreRow.addView(v)
            moreItems[key] = MoreItem(v, value) { open(v) }
        }
        add("subs", R.drawable.ic_cc, "Subtitles") { openSubtitlesPanel(it) }
        add("subDelay", R.drawable.ic_sub_delay, "Subtitle delay") { openSubtitleDelayPanel(it) }
        add("style", R.drawable.ic_sub_style, "Subtitle style") { openStylePanel(it) }
        add("audio", R.drawable.ic_audio, "Audio") { openAudioPanel(it) }
        add("audioDelay", R.drawable.ic_audio_delay, "Audio delay") { openAudioDelayPanel(it) }
        add("display", R.drawable.ic_display, "Display size") { openDisplayPanel(it) }
        add("dim", R.drawable.ic_dim, "Screen dimmer") { openDimPanel(it) }
        add("sleep", R.drawable.ic_sleep, "Sleep timer") { openSleepPanel(it) }
        add("settings", R.drawable.ic_settings, "Settings") { openSettingsPanel(it) }
    }

    /**
     * Once the last episode in the list is playing, asks the provider in the background which show it belongs to and
     * which episode comes after it, and adds that, so it plays at the end and Next appears. An episode named only by
     * its number gets its show's name on screen too. Nothing touches the network before the picture is up, and the
     * asking (the provider's list of shows can be megabytes) waits until the film is well ahead, see [lookUpWhenFree].
     */
    private fun findNextEpisode() {
        // An episode named only by its number wants its show's name on screen at once; only the next episode can wait.
        if (entries.lastOrNull()?.info?.named == false) {
            startNextEpisodeLookup()
            return
        }
        // A show found before takes two small requests, not the provider's whole list: its names go up at once.
        val last = entries.lastOrNull()
        if (last != null && findsNextEpisode && NextEpisode.rememberedShowId(this, last.uri) != null) {
            startNextEpisodeLookup()
            return
        }
        if (lookupWantedAt == 0L) lookupWantedAt = SystemClock.elapsedRealtime()
    }

    /**
     * From [postPlayTick]: starts the look-up asked for once the film has a minute loaded, or has all it needs, so the
     * download doesn't take the line from a film that has only just started. Not later than a few minutes before the
     * end card, nor after a few minutes of waiting, on a line that never gets that far ahead.
     */
    private fun lookUpWhenFree() {
        val p = player ?: return
        if (lookupWantedAt == 0L || p.playbackState != Player.STATE_READY) return
        val duration = p.duration
        val nearEnd = duration != C.TIME_UNSET && duration - p.currentPosition < maxOf(POST_PLAY_LEAD_MS, 0L) + LOOKUP_BEFORE_END_MS
        val free = p.totalBufferedDuration >= LOOKUP_AHEAD_MS || !p.isLoading || nearEnd ||
            SystemClock.elapsedRealtime() - lookupWantedAt >= LOOKUP_WAIT_MS
        if (!free) return
        lookupWantedAt = 0L
        startNextEpisodeLookup()
    }

    private fun startNextEpisodeLookup() {
        val p = player ?: return
        val last = entries.lastOrNull() ?: return
        // Wanted even with Play next episode off: the show's episodes are listed in Episodes, and named on screen.
        if (!findsNextEpisode || p.currentMediaItemIndex != entries.lastIndex) return
        if (nextLookedUpFor == last.key) return
        nextLookedUpFor = last.key
        val run = ++lookupRun
        lookupInFlight = true
        val finder = NextEpisode(applicationContext, headersFromIntent())
        val rawTitle = last.rawTitle
        val show = last.info.title
        thread(name = "FirePlayerNext", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            // The film shares the line meanwhile: it isn't moved to another server for arriving slower.
            val found = ServerSwitch.alongside { runCatching { finder.find(last.uri, rawTitle, show) }.getOrNull() }
            handler.post {
                // Dropped when the player was left meanwhile (it looks again on return).
                if (run != lookupRun) return@post
                lookupInFlight = false
                val playing = player ?: run { nextLookedUpFor = null; return@post }
                if (found == null) {
                    if (episodesSheet.isOpen && guide == null) {
                        episodesSheet.fail(NextEpisode.status ?: "Couldn't get this show's episodes from your provider.")
                    }
                    return@post
                }
                makeGuide(last.uri, found)
                // The look-up listed the show's episodes through the title server: its English name may be known now.
                if (last.english == null) {
                    last.englishAsked = false
                    handler.postDelayed({ if (!isFinishing) lookUpEnglishName(last) }, EPISODES_NOTED_MS)
                }
                if (!last.info.named) {
                    val before = last.info.searchQuery
                    last.showName = found.showName
                    // A subtitle search still set to the number goes by the show's name now.
                    if (findFor == last.key && findQuery == before) findQuery = last.info.searchQuery
                    updateTitle()
                }
                val next = found.next ?: return@post
                // Still the end of the list (nothing was added meanwhile), and still wanted.
                if (entries.lastOrNull()?.key != last.key || !prefs.playNextEpisode) return@post
                // Its link ends in its own file type, so the type this one came with is left out.
                val entry = Entry(next.uri, next.title, null, nextEpisode = true)
                attachSaved(entry, chooseOn = false)
                entries += entry
                playing.addMediaItem(mediaItem(entry))
                updateTitle()
                learnRealEpisodes()
            }
        }
    }

    /** Checks the end card (and a look-up waiting to start) five times a second, while the player screen is up. */
    private val postPlayTick = object : Runnable {
        override fun run() {
            lookUpWhenFree()
            updateSkip()
            updatePostPlay()
            noteWhilePlaying()
            checkFreeze()
            handler.postDelayed(this, 200)
        }
    }

    /**
     * The end of an episode, Netflix-style: when a show has a next episode, a card comes up where the credits start
     * (only with times for them). Only on the file's own times ([Segments.autoAt]: its chapters say the credits run to
     * the end) does anything happen by itself: Next episode counts [Prefs.autoCountdownSec] of playing and starts the next one, or, with
     * nothing next, "Exit · 10s" counts the same and leaves. On a database's times (they fit by length, evidence but not proof)
     * the card is the same but waits: Next episode fills up until the real end, Exit waits to be pressed.
     * Watch credits / Keep watching put it away, pausing pauses a count, a jump starts it again.
     */
    private fun updatePostPlay() {
        val p = player
        val index = p?.currentMediaItemIndex ?: -1
        val next = entries.getOrNull(index + 1)
        val duration = p?.duration ?: C.TIME_UNSET
        val known = duration != C.TIME_UNSET
        val position = p?.currentPosition ?: 0L
        // Where the credits start, when this file's chapters or TheIntroDB say so; something after them (a scene, a
        // preview) means no card at all: the next episode starts when this one really ends.
        val parts = segments?.takeIf { segmentsFor?.startsWith(entries.getOrNull(index)?.key + "|") == true }
        fun inCredits(at: Long?) = at?.takeIf { known && it >= duration / 2 && it < duration - 5_000 }
        val credits = inCredits(parts?.endCardAt)
        val playsToEnd = parts?.afterCredits != null
        // Next episode when there's one to play; else Exit, but only where the credits surely run to the end (a scene
        // after them, or not knowing, means no Exit).
        val nextOk = next?.nextEpisode == true && prefs.playNextEpisode
        // An episode whose next one is still being looked for (or not looked for yet) may have one: no Exit meanwhile.
        val current = entries.getOrNull(index)
        val nextUnknown = current != null && prefs.playNextEpisode && findsNextEpisode &&
            (current.info.episode != null || NextEpisode.isEpisodeLink(current.uri)) &&
            (lookupInFlight || nextLookedUpFor != current.key)
        val exitAt = if (!nextOk && next == null && !nextUnknown && prefs.skipIntro) inCredits(parts?.exitAt) else null
        val exit = !nextOk && exitAt != null
        // Counting down by itself only on the file's own credits times; a database's get the card, no countdown.
        val sure = inCredits(parts?.autoAt) != null
        val auto = (nextOk || exit) && sure
        val showAt = if (exit) exitAt!! else credits ?: Long.MAX_VALUE
        // Going back out of the credits brings the card back when they come again.
        if (known && showAt != Long.MAX_VALUE && postPlayDismissedFor == index && position < showAt - 5_000) postPlayDismissedFor = -1
        val due = p != null && (nextOk || exit) && !sleepAtEnd && known &&
            duration > 3 * POST_PLAY_LEAD_MS && !p.isCurrentMediaItemLive && position >= showAt &&
            (exit || !playsToEnd) && postPlayDismissedFor != index && !seeking && !controlsShown && !panel.isOpen &&
            !episodesSheet.isOpen && errorBox.visibility != View.VISIBLE && !resumeDialogShowing
        if (!due || p == null || (!exit && next == null)) {
            if (postPlay.visibility == View.VISIBLE) hidePostPlay()
            return
        }
        // Up for the other kind (a next episode turned up, say): it comes back as the right one.
        if (postPlay.visibility == View.VISIBLE && (postPlayExit != exit || postPlayAuto != auto)) hidePostPlay()
        if (postPlay.visibility != View.VISIBLE) {
            postPlayExit = exit
            postPlayAuto = auto
            if (exit) {
                postPlayNext.visibility = View.GONE
                postPlayCredits.text = "Keep watching"
                postPlayGo.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
            } else {
                postPlayNext.visibility = View.VISIBLE
                postPlayNext.text = "Next · " + (guideEpisode(next!!)?.let { e -> e.label + if (e.name.isNotEmpty()) " · ${e.name}" else "" }
                    ?: next.info.subtitle ?: next.info.title)
                postPlayCredits.text = "Watch credits"
                postPlayGo.setCompoundDrawablesRelativeWithIntrinsicBounds(postPlayIcon, null, null, null)
                postPlayGo.text = "Next episode"
            }
            // The count starts with the card. A sure one counts the chosen seconds of playing; Next episode fills up to the real end and
            // never starts it early: the next one plays when this one ends, or when Next episode is pressed.
            postPlayFrom = position
            postPlayCountMs = if (auto) prefs.autoCountdownSec * 1000L else (duration - position).coerceAtLeast(1L)
            countShown = 0f
            countShownAt = 0L
            postPlayGo.counting = auto || !exit
            postPlay.alpha = 0f
            postPlay.visibility = View.VISIBLE
            postPlay.animate().alpha(1f).setDuration(250).start()
            postPlayGo.requestFocus()
        }
        // The fill follows every frame by itself; this only wakes it after a pause.
        postPlayGo.invalidate()
        if (exit) {
            val left = ((postPlayFrom + postPlayCountMs - position + 999) / 1000).coerceAtLeast(0)
            val label = if (postPlayAuto) "Exit · ${left}s" else "Exit"
            if (postPlayGo.text.toString() != label) postPlayGo.text = label
            // Paused, the position stands still and so does the count.
            if (postPlayAuto && position >= postPlayFrom + postPlayCountMs && p.playWhenReady) exitAfterCredits()
        } else if (postPlayAuto && position >= postPlayFrom + postPlayCountMs && p.playWhenReady) {
            playNextEpisode()
        }
    }

    /** Exit, after a film's credits: watched to the end, back to where it was opened from. */
    private fun exitAfterCredits() {
        val p = player ?: return
        hidePostPlay()
        completed = true
        entries.getOrNull(p.currentMediaItemIndex)?.let {
            prefs.savePosition(it.key, 0L)
            noteWatched(it, p.currentPosition, p.duration, finished = true, live = p.isCurrentMediaItemLive)
        }
        finishWithResult()
    }

    /** A jump while the end card is up starts its count again from there. */
    private fun restartPostPlayCount(position: Long) {
        if (postPlay.visibility != View.VISIBLE) return
        val duration = player?.duration ?: return
        postPlayFrom = position
        postPlayCountMs = if (postPlayAuto) prefs.autoCountdownSec * 1000L else (duration - position).coerceAtLeast(1L)
        countShown = 0f
        countShownAt = 0L
    }

    // The count as last drawn, and when: the fill glides on its own clock between the player's position updates.
    private var countShown = 0f
    private var countShownAt = 0L

    /**
     * How far the end card's count has got. While playing, it moves at the film's own pace from where it was last
     * drawn and is pulled gently towards where the player says it is, never backwards: the player's position comes in
     * small uneven steps, which made the fill shudder. Paused, it shows the player's position exactly.
     */
    private fun postPlayProgress(): Float {
        val p = player ?: return 0f
        val actual = ((p.currentPosition - postPlayFrom).toFloat() / postPlayCountMs).coerceIn(0f, 1f)
        val now = SystemClock.uptimeMillis()
        if (!p.isPlaying || countShownAt == 0L || now - countShownAt > 500) {
            countShown = actual
        } else {
            val glided = countShown + (now - countShownAt) * p.playbackParameters.speed / postPlayCountMs
            countShown = maxOf(countShown, glided + (actual - glided) * 0.15f).coerceIn(0f, 1f)
        }
        countShownAt = now
        return countShown
    }

    /**
     * Skip intro, Skip recap or Skip credits (when something follows them), Netflix-style: a small button while that
     * part plays and the controls are down, for [SKIP_SHOWN_MS], then it slips away; OK jumps past it. For as long as
     * the part plays, the controls carry an unselected pod above the time bar, on the right, that does the same.
     * Outside the part, neither shows. Where the parts are comes from [lookUpSegments].
     */
    private fun updateSkip() {
        val p = player
        val entry = currentEntry()
        val duration = p?.duration ?: C.TIME_UNSET
        if (p != null && entry != null && duration != C.TIME_UNSET && duration > 0 && !p.isCurrentMediaItemLive) {
            lookUpSegments(entry, duration)
        }
        if (segmentsWait && skipButton.visibility != View.VISIBLE && skipPod.visibility != View.VISIBLE) takeSegments(segmentsWaiting)
        // Back before a part whose button was put away (watching it again): the button comes back with it.
        if (p != null && skipDismissed >= 0 && p.currentPosition < earliest(skipDismissed, skipDismissedFrom)) {
            skipDismissed = -1L
            skipDismissedFrom = -1L
        }
        if (p != null && skipFadedFor >= 0 && p.currentPosition < earliest(skipFadedFor, skipFadedFrom)) {
            skipFadedFor = -1L
            skipShownFor = -1L
            skipFadedFrom = -1L
        }
        val skip = if (p == null) null else segments?.skip(p.currentPosition)
        val free = skip != null && prefs.skipIntro && !seeking && !panel.isOpen && !episodesSheet.isOpen &&
            postPlay.visibility != View.VISIBLE && errorBox.visibility != View.VISIBLE && !resumeDialogShowing
        // The pod over the time bar: whenever the controls are up inside the part.
        val podShown = free && controlsShown && skip != null && p?.duration?.let { it != C.TIME_UNSET } == true
        if (podShown) {
            if (skipPod.text.toString() != skip!!.label) skipPod.text = skip.label
            if (skipPod.visibility != View.VISIBLE) {
                placeSkipPod()
                skipPod.alpha = 0f
                skipPod.visibility = View.VISIBLE
                skipPod.animate().alpha(1f).setDuration(200).start()
            }
        } else if (skipPod.visibility == View.VISIBLE) {
            if (skipPod.isFocused) seekBar.requestFocus()
            skipPod.visibility = View.GONE
        }
        if (skip != null) { skipStart = skip.start; skipTarget = skip.target }
        val now = SystemClock.uptimeMillis()
        if (skip != null && skipShownFor == skip.start && skipFadedFor != skip.start && now - skipShownAt >= SKIP_SHOWN_MS) {
            skipFadedFor = skip.start
            skipFadedFrom = -1L
            if (skipButton.visibility == View.VISIBLE) {
                // Untouched: it fades and slips down out of the way.
                skipButton.animate().alpha(0f).translationY(24 * resources.displayMetrics.density).setDuration(400)
                    .withEndAction { skipButton.visibility = View.GONE; skipButton.translationY = 0f }.start()
                focusSink.requestFocus()
            }
            return
        }
        val show = free && !controlsShown && skip!!.start != skipDismissed && skip.start != skipFadedFor
        if (!show || skip == null) {
            if (skipButton.visibility == View.VISIBLE && skipFadedFor != skip?.start) skipButton.visibility = View.GONE
            return
        }
        if (skipButton.text.toString() != skip.label) skipButton.text = skip.label
        if (skipButton.visibility != View.VISIBLE) {
            if (skipShownFor != skip.start) { skipShownFor = skip.start; skipShownAt = now }
            skipButton.animate().cancel()
            skipButton.translationY = 0f
            skipButton.alpha = 0f
            skipButton.visibility = View.VISIBLE
            skipButton.animate().alpha(1f).setDuration(250).start()
            skipButton.requestFocus()
        }
    }

    /** Lines the pod up with the chips' row, just above the time bar, at its right end. */
    private fun placeSkipPod() {
        val info = findViewById<View>(R.id.info_layer)
        val parent = skipPod.parent as View
        val a = IntArray(2).also { info.getLocationInWindow(it) }
        val b = IntArray(2).also { parent.getLocationInWindow(it) }
        val lp = skipPod.layoutParams as FrameLayout.LayoutParams
        val bottom = (parent.height - (a[1] - b[1] + info.height)).coerceAtLeast(0)
        if (lp.bottomMargin != bottom) { lp.bottomMargin = bottom; skipPod.layoutParams = lp }
    }

    private fun skipPart() {
        val p = player ?: return
        skipButton.visibility = View.GONE
        if (skipPod.isFocused) seekBar.requestFocus()
        skipPod.visibility = View.GONE
        if (skipTarget > p.currentPosition) p.seekTo(skipTarget)
    }

    /**
     * Finds where this episode's (or film's) recap, intro and credits are: the file's own chapters first (exact for
     * this file), then SkipDB, then IntroDB for what neither says (see [SkipTimes]), by the IMDb id TMDB gives for an
     * episode surely matched to it, or a film. Asked again when the episode, its match or the file's length changes;
     * an answer for anything no longer playing is dropped.
     */
    private fun lookUpSegments(entry: Entry, duration: Long, again: Boolean = false) {
        val g = guide
        val ep = guideEpisode(entry)?.takeIf { g != null && g.tmdbId > 0 && it.match == ShowGuide.Match.SURE }
        val film = ep == null && entry.info.episode == null && !NextEpisode.isEpisodeLink(entry.uri)
        val want = entry.key + "|" + (ep?.let { "${g!!.tmdbId}/${it.season}/${it.number}" } ?: if (film) "film" else "-") +
            "|" + duration / 1000
        if (want == segmentsFor && !again) return
        val fresh = want != segmentsFor
        if (fresh) {
            segmentsFor = want
            segments = null
            segmentsWaiting = null
            segmentsWait = false
            skipDismissed = -1L
            skipDismissedFrom = -1L
            skipFadedFrom = -1L
        }
        if (!prefs.skipIntro) return
        // Auto sync's confirmed offsets for these subtitles on this very file, if any (see [SyncMapping]).
        val sync = if (::autoSync.isInitialized) autoSync.mapping?.takeIf { it.filmKey == entry.key } else null
        val uri = entry.uri
        val key = entry.key
        val known = chapterSegments.containsKey(key)
        val knownChapters = chapterSegments[key]
        // A second, short connection to read the start of the file: only where previews are allowed one too.
        val readChapters = !known && prefs.seekPreviews && uri.scheme?.startsWith("http") == true &&
            uri.lastPathSegment?.endsWith(".mkv", ignoreCase = true) == true
        val online = tmdb.isConfigured && (ep != null || film)
        if (!online && !readChapters && knownChapters == null) return
        // The first look-up is still reading the chapters: asked again once it has.
        if (again && !fresh && readChapters) { handler.postDelayed(lookUpSegmentsAgain, SEGMENTS_AGAIN_MS); return }
        val asked = ++segmentsAsked
        val headers = headersFromIntent()
        val app = applicationContext
        val showId = g?.tmdbId ?: 0
        val fixed = if (film) matchFor(entry)?.tmdbId else null
        if (segmentWorker.isShutdown) return
        segmentWorker.execute {
            if (segmentsFor != want || segmentsAsked != asked) return@execute
            val chapters = if (readChapters) MkvChapters.segments(uri, headers, duration) else knownChapters
            // Only what the chapters don't say is asked for.
            val db = if (!online || (chapters?.intro != null && chapters.creditsAt != null)) null else runCatching {
                if (ep != null) {
                    tmdb.ids(showId, true)?.let { SkipTimes.find(app, it.imdb, ep.season, ep.number, duration, ep.tmdbMin, sync) }
                } else {
                    val id = fixed ?: filmTmdbId(entry)
                    tmdb.ids(id, false)?.let { SkipTimes.find(app, it.imdb, null, null, duration, it.runtime, sync) }
                }
            }.onFailure { android.util.Log.w("FirePlayerSegments", "Look-up failed", it) }.getOrNull()
            val found = chapters?.or(db) ?: db
            handler.post {
                if (readChapters) chapterSegments[key] = chapters
                if (isFinishing || segmentsFor != want || segmentsAsked != asked) return@post
                // Asked again (auto sync's offsets changed): a button or pod on screen stays where it is till it goes.
                if (again && (skipButton.visibility == View.VISIBLE || skipPod.visibility == View.VISIBLE)) {
                    segmentsWaiting = found
                    segmentsWait = true
                } else {
                    takeSegments(found)
                }
                if (found != null) android.util.Log.i("FirePlayerSegments", "From ${found.from}: recap ${found.recap}, " +
                    "intro ${found.intro}, credits ${found.creditsAt}, after ${found.afterCredits}, to end ${found.creditsToEnd}")
            }
        }
    }

    /**
     * Puts a newer answer for the same episode in place. When the part playing only moved (auto sync's offsets were
     * fine-tuned or a break found before it), its button's state moves with it: one that slipped away or was put away
     * stays away, one showing isn't shown afresh.
     */
    private fun takeSegments(found: Segments?) {
        val o = segments
        if (o != null && found != null) {
            fun credits(s: Segments) = s.creditsAt?.let { c -> s.afterCredits?.let { c..it } }
            // The same part (recap, intro, credits) at a new place it still overlaps.
            val moves = listOf(o.recap to found.recap, o.intro to found.intro, credits(o) to credits(found)).mapNotNull { (a, b) ->
                if (a != null && b != null && a.first != b.first && a.first < b.last && b.first < a.last) a to b else null
            }
            fun moved(v: Long) = if (v < 0) v else moves.firstOrNull { it.first.first == v }?.second?.first ?: v
            val dismissed = moved(skipDismissed)
            if (dismissed > skipDismissed && skipDismissed >= 0) skipDismissedFrom = earliest(skipDismissed, skipDismissedFrom)
            val faded = moved(skipFadedFor)
            if (faded > skipFadedFor && skipFadedFor >= 0) skipFadedFrom = earliest(skipFadedFor, skipFadedFrom)
            skipDismissed = dismissed
            skipFadedFor = faded
            skipShownFor = moved(skipShownFor)
            moves.firstOrNull { it.first.first == skipStart }?.let { skipStart = it.second.first; skipTarget = it.second.last }
        }
        segments = found
        segmentsWaiting = null
        segmentsWait = false
    }

    /** [at], or [from] when that's set and earlier. */
    private fun earliest(at: Long, from: Long) = if (from >= 0) minOf(at, from) else at

    /**
     * Auto sync's confirmed offsets changed (more of the film matched, a break found, or none stand any more): the
     * parts are looked up again for this file, at most every [SEGMENTS_AGAIN_MS], at once when the offsets are gone.
     */
    private fun syncMappingChanged() {
        handler.removeCallbacks(lookUpSegmentsAgain)
        val wait = SEGMENTS_AGAIN_MS - (SystemClock.uptimeMillis() - segmentsAgainAt)
        if (autoSync.mapping == null || wait <= 0) lookUpSegmentsAgain() else handler.postDelayed(lookUpSegmentsAgain, wait)
    }

    private fun lookUpSegmentsAgain() {
        val p = player ?: return
        val entry = currentEntry() ?: return
        val duration = p.duration
        if (isFinishing || duration == C.TIME_UNSET || duration <= 0 || p.isCurrentMediaItemLive) return
        segmentsAgainAt = SystemClock.uptimeMillis()
        lookUpSegments(entry, duration, again = true)
    }

    private fun hidePostPlay() {
        postPlay.animate().cancel()
        postPlay.visibility = View.GONE
    }

    private fun dismissPostPlay() {
        postPlayDismissedFor = player?.currentMediaItemIndex ?: -1
        hidePostPlay()
    }

    private fun playNextEpisode() {
        val p = player ?: return
        hidePostPlay()
        // This one was watched to the credits: next time it starts from the top.
        entries.getOrNull(p.currentMediaItemIndex)?.let {
            prefs.savePosition(it.key, 0L)
            noteWatched(it, p.currentPosition, p.duration, finished = true, live = p.isCurrentMediaItemLive)
        }
        lineUpNext()
        p.seekToNextMediaItem()
        p.play()
    }

    /** Play next episode was switched in Settings: put away the episodes found, or look for one now. */
    private fun nextEpisodeSwitched() {
        val p = player ?: return
        if (prefs.playNextEpisode) {
            // Looked up only for the show's name while it was off; this time the next episode is kept.
            nextLookedUpFor = null
            findNextEpisode()
            return
        }
        for (i in entries.lastIndex downTo p.currentMediaItemIndex + 1) {
            if (!entries[i].nextEpisode) break
            entries.removeAt(i)
            p.removeMediaItem(i)
        }
        nextLookedUpFor = null
        hidePostPlay()
        updateTitle()
    }

    private fun updateTitle() {
        val entry = entries.getOrNull(player?.currentMediaItemIndex ?: startIndex) ?: return
        val ep = guideEpisode(entry)
        val info = entry.info
        val line: CharSequence? = when {
            ep != null -> episodeLine(ep.label, ep.name)
            info.subtitle != null -> {
                // The provider's own name for the episode, until TMDB's English one is known (it may be in another
                // language): straight away without a TMDB key, or for links whose show can't be looked up.
                val own = if (tmdb.isConfigured && findsNextEpisode) "" else ShowGuide.episodeName(
                    entry.rawTitle.orEmpty(), setOf(ShowGuide.simple(info.searchQuery)),
                )
                episodeLine(info.subtitle, own)
            }
            else -> null
        }
        titleView.text = ep?.let { guide?.show } ?: filmFix(entry) ?: info.title
        subtitleView.text = line
        subtitleView.visibility = if (line != null) View.VISIBLE else View.GONE
        // Episodes: for a show's episode handed over on its own, whose provider can be asked for the rest.
        val episodes = findsNextEpisode && NextEpisode.canFind(entry.uri)
        if (!episodes && btnEpisodes.hasFocus()) btnPlay.requestFocus()
        episodesGroup.visibility = if (episodes) View.VISIBLE else View.GONE
        val hasNext = player?.hasNextMediaItem() == true
        // Hiding the focused Next button would send focus to the time bar; keep it on the buttons.
        if (!hasNext && btnNext.hasFocus()) btnPlay.requestFocus()
        nextGroup.visibility = if (hasNext) View.VISIBLE else View.GONE
        lookUpEnglishName(entry)
    }

    /**
     * TiviMate hands over the provider's name, which for an episode is often in another language. A film or episode
     * that comes through FirePlayer's title server (see [TitleProxy]) goes on screen under the English name it found:
     * "The Matrix (1999)", or the show's with "S1 E4" under it. An episode is known once its show's episodes were
     * listed, by TiviMate or by the next-episode look-up.
     */
    private fun lookUpEnglishName(entry: Entry) {
        if (entry.englishAsked || entry.english != null) return
        val server = TitleProxy.serverIn(entry.uri) ?: return
        entry.englishAsked = true
        io.execute {
            val found = runCatching { englishName(server, entry.uri) }.getOrNull() ?: return@execute
            handler.post {
                if (isFinishing) return@post
                val before = entry.info.searchQuery
                entry.english = found
                // A subtitle search still set to the old name goes by the English one now.
                if (findFor == entry.key && findQuery == before) findQuery = entry.info.searchQuery
                if (entries.getOrNull(player?.currentMediaItemIndex ?: startIndex) === entry) updateTitle()
            }
        }
    }

    /** Blocks; on [io]. ".../server/movie/USER/PASSWORD/123.mkv" or ".../series/...". */
    private fun englishName(server: String, uri: Uri): TitleInfo? {
        val parts = uri.pathSegments.drop(1)
        if (parts.size != 4) return null
        val id = parts[3].substringBeforeLast('.').toIntOrNull() ?: return null
        val book = book()
        return when (parts[0].lowercase()) {
            "movie" -> book.title(server, TitleBook.Kind.MOVIE, id)?.let { TitleInfo.parse(it, null) }
            "series" -> {
                val episode = book.episode(server, id) ?: return null
                val show = book.title(server, TitleBook.Kind.SHOW, episode.series) ?: return null
                TitleInfo.parse(show, null).withEpisode(episode.season, episode.number)
            }
            else -> null
        }
    }

    /** Blocks; on [io]. */
    private fun book(): TitleBook = titleBook ?: TitleBook(applicationContext).also { titleBook = it }

    /**
     * Which episode each one in the list really is, by the provider's numbers the title server noted when TiviMate
     * listed the show (TiviMate numbers them by their place in its list, copies and all), then lines up the next one
     * that isn't a copy. Episodes that don't come through the title server, or that it hasn't noted, stay unknown.
     */
    private fun learnRealEpisodes() {
        val asking = entries.filter { it.real == null && it.realTries < REAL_TRIES }.mapNotNull { entry ->
            entry.realTries++
            val server = TitleProxy.serverIn(entry.uri)
            val parts = entry.uri.pathSegments.drop(1)
            val id = parts.getOrNull(3)?.substringBeforeLast('.')?.toIntOrNull()
            if (server == null || parts.size != 4 || !parts[0].equals("series", true) || id == null) {
                entry.realTries = REAL_TRIES
                null
            } else Triple(entry, server, id)
        }
        if (asking.isEmpty()) return
        io.execute {
            val found = asking.mapNotNull { (entry, server, id) ->
                val episode = runCatching { book().episode(server, id) }.getOrNull() ?: return@mapNotNull null
                NextPick.Real.of(episode.series, episode.season, episode.number, entry.rawTitle)?.let { entry to it }
            }
            handler.post {
                if (isFinishing) return@post
                // The title server may still be noting the show's episodes TiviMate just listed: asked again in a while.
                if (found.size < asking.size && asking.any { it.first.realTries < REAL_TRIES }) {
                    handler.postDelayed({ if (!isFinishing) learnRealEpisodes() }, REAL_AGAIN_MS)
                }
                if (found.isEmpty()) return@post
                found.forEach { (entry, real) -> entry.real = real }
                lineUpNext()
                updateTitle()
            }
        }
    }

    /**
     * Puts the episode to play after this one right after it in the list (see [NextPick]), so Next, the end card,
     * moving on by itself and Continue Watching all go to the real next episode, not a copy of one already seen; when
     * only such copies are left, the list ends here. A list whose real numbers aren't known stays just as it came.
     */
    private fun lineUpNext() {
        val p = player
        if (p != null && p.mediaItemCount != entries.size) return
        val index = p?.currentMediaItemIndex ?: startIndex
        val now = entries.getOrNull(index) ?: return
        now.real?.let { watchedReal += it }
        val pick = NextPick.after(index, entries.map { it.real }, watchedReal)
        val changed = when {
            pick == index + 1 -> false
            pick == null -> {
                if (index + 1 < entries.size) {
                    p?.removeMediaItems(index + 1, entries.size)
                    entries.subList(index + 1, entries.size).clear()
                    true
                } else false
            }
            else -> {
                p?.moveMediaItem(pick, index + 1)
                entries.add(index + 1, entries.removeAt(pick))
                true
            }
        }
        // The end card names it by its English name and real number, once known.
        entries.getOrNull(index + 1)?.let { lookUpEnglishName(it) }
        if (!changed) return
        // An end card already up for the one that was next comes back for the new one.
        if (postPlay.visibility == View.VISIBLE) hidePostPlay()
        updateTitle()
    }

    /** The screen stays on only while the film plays or loads to play; paused, asking or showing an error, it may sleep. */
    private fun updateKeepScreenOn() {
        val p = player
        root.keepScreenOn = p != null && p.playWhenReady &&
            (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING) &&
            errorBox.visibility != View.VISIBLE && !resumeDialogShowing
    }

    private fun updatePlayButton() {
        val playing = player?.playWhenReady == true && player?.playbackState != Player.STATE_ENDED
        btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun refreshLabels() {
        val subs = trackOptions(C.TRACK_TYPE_TEXT).firstOrNull { it.selected }?.label ?: "Off"
        val audio = trackOptions(C.TRACK_TYPE_AUDIO).firstOrNull { it.selected }?.label ?: "Audio"
        subsLabel.text = subs
        audioLabel.text = audio
        moreItems["subs"]?.value?.text = subs
        moreItems["subDelay"]?.value?.text = formatDelay(delays.subtitleUs)
        moreItems["style"]?.value?.text = presets.active().name
        moreItems["audio"]?.value?.text = audio
        moreItems["audioDelay"]?.value?.text = formatDelay(delays.audioUs)
        moreItems["display"]?.value?.text = DISPLAY_MODES[displayMode]
        moreItems["dim"]?.value?.text = dimmer.label()
        moreItems["sleep"]?.value?.text = sleepLabel()
        moreItems["settings"]?.value?.text = "Settings"
    }

    private fun updateChips() {
        val p = player ?: return
        // Pods turned off in Settings > Look > Info pods stay hidden.
        val hidden = prefs.hiddenChips
        fun pod(chip: TextView, key: String, text: String?) = setChip(chip, text.takeIf { key !in hidden })
        val video = selectedFormat(C.TRACK_TYPE_VIDEO)
        val audio = selectedFormat(C.TRACK_TYPE_AUDIO)
        val w = p.videoSize.width.takeIf { it > 0 } ?: video?.width ?: 0
        val h = p.videoSize.height.takeIf { it > 0 } ?: video?.height ?: 0
        pod(chipResolution, "quality", if (w > 0 && h > 0) "$w×$h" else null)
        val fps = videoFps()
        pod(chipFps, "fps", if (fps > 0) fpsLabel(fps) else null)
        val channels = audio?.channelCount ?: Format.NO_VALUE
        pod(
            chipAudio,
            "sound",
            when (channels) {
                1 -> "Mono"
                2 -> "Stereo"
                6 -> "5.1"
                8 -> "7.1"
                in 3..16 -> "$channels ch"
                else -> null
            },
        )
        // Which provider it plays from: "premium-pro2.com:8789".
        pod(chipPortal, "source", entries.getOrNull(p.currentMediaItemIndex)?.let { NextEpisode.portal(it.uri) })
    }

    private fun setChip(chip: TextView, text: String?) {
        chip.text = text
        chip.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun selectedFormat(type: Int): Format? {
        val groups = player?.currentTracks?.groups ?: return null
        for (g in groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) if (g.isTrackSelected(i)) return g.getTrackFormat(i)
        }
        return null
    }

    /** The frame rate the video says it has, or the one measured from its frames. */
    private fun videoFps(): Float {
        val declared = selectedFormat(C.TRACK_TYPE_VIDEO)?.frameRate ?: Format.NO_VALUE.toFloat()
        return if (declared > 0) declared else measuredFps
    }

    private fun fpsLabel(fps: Float): String {
        val value = FpsMeter.snap(fps)
        val text = if (abs(value - value.roundToInt()) < 0.001f) value.roundToInt().toString()
            else "%.3f".format(Locale.US, value).trimEnd('0')
        return "$text FPS"
    }

    private fun matchFrameRate() {
        val p = player ?: return
        if (!prefs.matchFrameRate || matchedFpsForIndex == p.currentMediaItemIndex) return
        // Only the rate the file declares: a measured one arrives seconds into playback, too late to switch.
        val fps = selectedFormat(C.TRACK_TYPE_VIDEO)?.frameRate ?: Format.NO_VALUE.toFloat()
        if (fps <= 0) return
        matchedFpsForIndex = p.currentMediaItemIndex
        val wasPlaying = p.playWhenReady
        switchFor(frameRateMatcher.match(fps), wasPlaying)
    }

    /** The TV was asked for another mode ([switching]): the film waits for the picture if it was playing. */
    private fun switchFor(switching: Boolean, wasPlaying: Boolean) {
        if (!switching) return
        displaySwitchAt = SystemClock.elapsedRealtime()
        if (!wasPlaying) return
        // The TV blanks for a moment while it switches; don't play through it.
        player?.pause()
        pausedForSwitch = true
        handler.removeCallbacks(resumeAfterSwitch)
        handler.postDelayed(resumeAfterSwitch, DISPLAY_SWITCH_PAUSE_MS)
    }

    /**
     * The TV's mode changed under the film and has settled: turned on, reconnected, or Fire OS putting its own mode back
     * (once the TV came on at 59.94 Hz in the middle of a 23.976 fps film, and stayed there). If the film's rate was
     * matched and the mode no longer suits it, it's asked for again, with the usual pause. A TV that leaves the mode
     * again right after being asked may still be coming on: it's asked again once [REMATCH_AGAIN_MS] have passed, up to
     * [REMATCH_MAX] times for one video.
     */
    private fun rematchFrameRate() {
        val p = player ?: return
        val index = p.currentMediaItemIndex
        val fps = selectedFormat(C.TRACK_TYPE_VIDEO)?.frameRate ?: Format.NO_VALUE.toFloat()
        val now = SystemClock.elapsedRealtime()
        val matchOn = prefs.matchFrameRate
        val decision = FrameRateMatcher.rematch(
            nowMs = now,
            matchOn = matchOn,
            matchedThisFilm = matchedFpsForIndex == index,
            refused = refusedForIndex == index,
            fps = fps,
            suits = !matchOn || fps <= 0 || frameRateMatcher.suits(fps),
            switchAtMs = displaySwitchAt,
            askedAtMs = frameRateMatcher.askedAt,
            settleMs = DISPLAY_SETTLE_MS,
            againMs = REMATCH_AGAIN_MS,
        )
        when (decision) {
            is FrameRateMatcher.Rematch.Wait -> {
                handler.removeCallbacks(rematchLater)
                handler.postDelayed(rematchLater, decision.ms)
            }
            FrameRateMatcher.Rematch.Ask -> {
                // A TV that keeps going back, soon or late, is left alone after a few tries: no pause again and again.
                if (++rematches >= REMATCH_MAX) refusedForIndex = index
                android.util.Log.i(TAG, "The TV went to ${tvHz()} Hz while the film plays (turned on or reconnected): " +
                    "asking for ${FpsMeter.snap(fps)} fps again")
                switchFor(frameRateMatcher.match(fps, again = true), p.playWhenReady)
            }
            FrameRateMatcher.Rematch.Leave -> Unit
        }
    }

    private val rematchLater = Runnable { rematchFrameRate() }

    @Suppress("DEPRECATION")
    private fun tvHz(): String = "%.3f".format(Locale.US, windowManager.defaultDisplay?.refreshRate ?: 0f)

    private val resumeAfterSwitch = Runnable {
        pausedForSwitch = false
        player?.play()
    }

    /**
     * While the TV changes refresh rate the picture is blank and VsyncFix measures again, so the glass takes no copies
     * of the film until it has settled (DISPLAY_SETTLE_MS, which outlasts the film's pause for the switch).
     */
    private fun suspendGlass() {
        val b = backdrop ?: return
        b.suspended = true
        handler.removeCallbacks(glassSettled)
        handler.postDelayed(glassSettled, DISPLAY_SETTLE_MS)
    }

    private val glassSettled = Runnable { backdrop?.suspended = false }

    private val rebuildForAss = Runnable {
        if (player != null && builtWithAss != prefs.keepAssStyling) rebuildPlayer()
    }

    // ---------------------------------------------------------------------
    // Controls

    private fun showControls(focus: View? = btnPlay) {
        controlsShown = true
        // The sink only holds focus while everything is hidden; otherwise D-pad left could land on it.
        focusSink.isFocusable = false
        val target = focus ?: btnPlay
        // Coming back from a window opened from the icon row returns to that row.
        if (target.parent === moreRow) showMoreRow(target, animate = false) else showMainRow(focusRow = false, animate = false)
        updateOverlay()
        updateProgress()
        updateClock()
        target.requestFocus()
        scheduleHide()
    }

    private fun hideControls() {
        controlsShown = false
        moreShown = false
        moreRow.visibility = View.GONE
        mainRow.visibility = View.VISIBLE
        arrowUp.visibility = View.GONE
        arrowDown.visibility = View.VISIBLE
        focusSink.isFocusable = true
        focusSink.requestFocus()
        updateOverlay()
        handler.removeCallbacks(hideControlsRunnable)
    }

    private fun showMoreRow(focus: View = moreItems.values.first().view, animate: Boolean = true) {
        val changed = !moreShown
        moreShown = true
        swapRows(show = moreRow, hide = mainRow, showArrow = arrowUp, hideArrow = arrowDown, up = true, animate = animate && changed)
        focus.requestFocus()
    }

    private fun showMainRow(focusRow: Boolean = true, animate: Boolean = true) {
        val changed = moreShown
        moreShown = false
        swapRows(show = mainRow, hide = moreRow, showArrow = arrowDown, hideArrow = arrowUp, up = false, animate = animate && changed)
        if (focusRow) btnPlay.requestFocus()
    }

    /**
     * Swaps the button row and the icon row under the arrow. Instead of a hard cut, the old row fades out while it
     * drifts the way the D-pad pointed and the new one glides in from the other side, so the change reads as one move.
     * Short enough (220 ms) that a quick second press never waits on it.
     */
    private fun swapRows(show: View, hide: View, showArrow: View, hideArrow: View, up: Boolean, animate: Boolean) {
        val shift = resources.displayMetrics.density * 14f * (if (up) -1 else 1)
        for (v in listOf(show, hide, showArrow, hideArrow)) v.animate().cancel()
        show.visibility = View.VISIBLE
        showArrow.visibility = View.VISIBLE
        if (!animate) {
            for (v in listOf(show, showArrow)) { v.alpha = 1f; v.translationY = 0f }
            for (v in listOf(hide, hideArrow)) { v.visibility = View.GONE; v.alpha = 1f; v.translationY = 0f }
            return
        }
        val ease = DecelerateInterpolator(1.6f)
        if (show.alpha >= 1f) { show.alpha = 0f; show.translationY = -shift }
        if (showArrow.alpha >= 1f) showArrow.alpha = 0f
        show.animate().alpha(1f).translationY(0f).setStartDelay(0).setDuration(220).setInterpolator(ease).withLayer().start()
        showArrow.animate().alpha(1f).setStartDelay(80).setDuration(160).setInterpolator(ease).start()
        hide.animate().alpha(0f).translationY(shift).setStartDelay(0).setDuration(160).setInterpolator(ease).withLayer().withEndAction {
            hide.visibility = View.GONE; hide.alpha = 1f; hide.translationY = 0f
        }.start()
        hideArrow.animate().alpha(0f).setStartDelay(0).setDuration(100).withEndAction {
            hideArrow.visibility = View.GONE; hideArrow.alpha = 1f
        }.start()
    }

    /** Puts the classic or glass backgrounds on the controls (Settings, Look, Glass look). Padding and sizes stay. */
    private fun applyLook() {
        Glass.on = prefs.glassLook
        Glass.background(btnPlay, Glass.Kind.CIRCLE)
        Glass.background(btnRestart, Glass.Kind.CIRCLE)
        Glass.background(btnNext, Glass.Kind.CIRCLE)
        Glass.background(btnEpisodes, Glass.Kind.CIRCLE)
        Glass.background(btnSubs, Glass.Kind.PILL)
        Glass.background(btnAudio, Glass.Kind.PILL)
        for (chip in listOf(chipResolution, chipFps, chipAudio, chipPortal)) Glass.background(chip, Glass.Kind.CHIP)
        // Glass chips cast a shadow below them that their row would cut off; classic chips keep the row as it was.
        (chips as ViewGroup).apply {
            clipChildren = !Glass.on
            clipToPadding = !Glass.on
        }
        for (item in moreItems.values) {
            Glass.background(item.view.findViewById<View>(R.id.more_icon).parent as View, Glass.Kind.MORE_ICON)
        }
        Glass.background(errorBox, Glass.Kind.POPUP)
        // The skip pods: clear glass while unselected, like the other buttons; classic keeps Netflix's flat grey.
        for (pill in listOf(skipButton, skipPod)) {
            if (Glass.on) Glass.background(pill, Glass.Kind.PILL) else pill.background = null
            pill.glass = Glass.on
        }
        // The end card's buttons the same way, round like the other glass buttons; classic keeps Netflix's flat shape.
        for (pill in listOf(postPlayCredits, postPlayGo)) {
            if (Glass.on) Glass.background(pill, Glass.Kind.PILL) else pill.background = null
            pill.glass = Glass.on
            pill.pod = Glass.on
        }
        (findViewById<FrameLayout>(R.id.preview_card)).foreground = ContextCompat.getDrawable(this, Glass.previewEdge)
    }

    /** Which parts of the overlay show: everything, just the time bar while seeking, or nothing. */
    private fun updateOverlay() {
        val bottomVisible = controlsShown || seeking || notice
        fade(scrimTop, controlsShown)
        fade(topBar, controlsShown)
        fade(scrimBottom, bottomVisible)
        fade(bottomBlock, bottomVisible)
        // While seeking without controls, keep the same layout so the bar doesn't move.
        buttonsArea.visibility = if (controlsShown) View.VISIBLE else View.INVISIBLE
        chips.visibility = if (controlsShown && !seeking && !notice) View.VISIBLE else View.INVISIBLE
        seekBubble.visibility = if (seeking || notice) View.VISIBLE else View.INVISIBLE
        handler.removeCallbacks(progressTick)
        if (bottomVisible) handler.post(progressTick)
        liftSubtitles()
    }

    private var screenLift = 0f
    private var frameLift = 0f

    /**
     * Subtitles move above the controls instead of sitting behind them, like Netflix: above the chips when
     * the controls are up, and above the time bar when only it shows and they'd overlap it.
     * Subtitles drawn by libass stay where they are: their signs can be anywhere on the picture.
     */
    private fun liftSubtitles(animate: Boolean = true) {
        val bottomVisible = controlsShown || seeking || notice
        if (bottomVisible && (bottomBlock.height == 0 || root.height == 0)) {
            // Shown for the first time: measure once it's laid out.
            bottomBlock.post { liftSubtitles() }
            return
        }
        var screen = 0f
        var frame = 0f
        if (bottomVisible) {
            val density = resources.displayMetrics.density
            // The lowest subtitles may reach: a little above the chips, or the "+4:00" card in their place.
            val infoTop = if (seekBubble.visibility == View.VISIBLE) minOf(chips.top, seekBubble.top) else chips.top
            val limit = bottomBlock.top + (chips.parent as View).top + infoTop - 14f * density
            // With only the time bar showing, subtitles entirely below it can stay where they are.
            val floor = if (controlsShown) Float.MAX_VALUE else bottomBlock.top + seekRow.bottom + 6f * density
            val height = root.height.toFloat()
            val textBottom = height * (1f - styler.bottomFraction)
            // The rows showing now; a new subtitle is placed again as it appears (see onCues).
            val textSize = styler.textSizeFraction * height
            val textTop = textBottom - (subtitleRows(textSize) * 1.21f + 0.25f) * textSize
            screen = liftFor(textTop, textBottom, limit, floor)
            if (!libassShowing()) frame = pictureLift(limit, floor)
        }
        if (screen != screenLift) {
            screenLift = screen
            screenSubtitles.animate().translationY(-screen).setDuration(if (animate) 200 else 0).start()
        }
        if (frame != frameLift) {
            frameLift = frame
            frameSubtitles.animate().translationY(-frame).setDuration(if (animate) 200 else 0).start()
        }
    }

    /** How many rows the subtitles showing now take on screen, wrapped the way the subtitle view wraps them. */
    private fun subtitleRows(textSize: Float): Int {
        val cues = styler.normalize(shownTextCues)
        if (cues.isEmpty()) return 0
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize = textSize
        paint.typeface = styler.typeface
        // Media3 keeps an eighth of the text size clear on each side.
        val inset = 2 * (textSize * 0.125f + 0.5f).toInt()
        val width = (screenSubtitles.width - screenSubtitles.paddingLeft - screenSubtitles.paddingRight - inset).coerceAtLeast(1)
        var rows = 0
        for (cue in cues) {
            // Lines the file places elsewhere (Position "As in the file") don't stack at the bottom.
            if (cue.line != Cue.DIMEN_UNSET) continue
            val text = cue.text ?: continue
            rows += if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setIncludePad(true).build().lineCount
            } else {
                @Suppress("DEPRECATION")
                StaticLayout(text, paint, width, Layout.Alignment.ALIGN_CENTER, 1f, 0f, true).lineCount
            }
        }
        return rows
    }

    /** How far something spanning [top] to [bottom] on screen must move up to clear the controls between [limit] and [floor]. */
    private fun liftFor(top: Float, bottom: Float, limit: Float, floor: Float): Float =
        if (bottom > limit && top < floor) bottom - limit else 0f

    /**
     * How far picture subtitles must move up to clear the controls, in the picture's own units (Zoom scales it).
     * Only the ones that would collide count, and nothing is pushed off the top of the screen.
     */
    private fun pictureLift(limit: Float, floor: Float): Float {
        val w = videoFrame.width.toFloat()
        val h = videoFrame.height.toFloat()
        if (shownPictureCues.isEmpty() || w == 0f || h == 0f) return 0f
        val scale = videoFrame.scaleY
        val pivot = videoFrame.pivotY
        fun onScreen(y: Float) = videoFrame.top + pivot + (y - pivot) * scale
        var lift = 0f
        var highest = Float.MAX_VALUE
        for (cue in shownPictureCues) {
            val bitmap = cue.bitmap ?: continue
            if (cue.line == Cue.DIMEN_UNSET) continue
            // Placed the way Media3 draws them: a box at a fraction of the picture, its height given or from the image's shape.
            val boxHeight = when {
                cue.bitmapHeight != Cue.DIMEN_UNSET -> cue.bitmapHeight * h
                cue.size != Cue.DIMEN_UNSET && bitmap.width > 0 -> cue.size * w * bitmap.height / bitmap.width
                else -> continue
            }
            val anchor = cue.line * h
            val top = when (cue.lineAnchor) {
                Cue.ANCHOR_TYPE_END -> anchor - boxHeight
                Cue.ANCHOR_TYPE_MIDDLE -> anchor - boxHeight / 2f
                else -> anchor
            }
            val screenTop = onScreen(top)
            highest = minOf(highest, screenTop)
            lift = maxOf(lift, liftFor(screenTop, onScreen(top + boxHeight), limit, floor))
        }
        return lift.coerceAtMost(highest.coerceAtLeast(0f)) / scale
    }

    private fun fade(view: View, show: Boolean) {
        view.animate().cancel()
        if (show) {
            if (view.visibility != View.VISIBLE) {
                view.alpha = 0f
                view.visibility = View.VISIBLE
            }
            view.animate().alpha(1f).setDuration(160).start()
        } else if (view.visibility == View.VISIBLE) {
            view.animate().alpha(0f).setDuration(160).withEndAction { view.visibility = View.GONE }.start()
        }
    }

    private val hideControlsRunnable = Runnable {
        if (player?.isPlaying == true && !panel.isOpen) hideControls()
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 5_000)
    }

    private val progressTick = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 250)
        }
    }

    /**
     * The player is on: noted every half minute for the title server, which pauses TMDB meanwhile. The message
     * saying the player stopped can be refused by Fire OS once FirePlayer is behind another app; a beat that stops
     * coming lets the server carry on regardless (see TitleService.PLAYER_GONE_MS).
     */
    private val playingBeat = object : Runnable {
        override fun run() {
            TitleService.playerBeat(this@PlayerActivity)
            handler.postDelayed(this, TitleService.BEAT_MS)
        }
    }

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            if (sleepEndsAt > 0 && SystemClock.elapsedRealtime() >= sleepEndsAt) {
                sleepEndsAt = 0
                player?.pause()
                finishWithResult()
                return
            }
            if (sleepEndsAt > 0) refreshLabels()
            // Tick on the minute so the clock and sleep countdown change on time.
            val toNextMinute = 60_000 - System.currentTimeMillis() % 60_000
            handler.postDelayed(this, toNextMinute.coerceIn(500, 15_000))
        }
    }

    private fun updateClock() {
        val pattern = if (DateFormat.is24HourFormat(this)) "EEE, MMM d · HH:mm" else "EEE, MMM d · h:mm a"
        clockView.text = java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
    }

    private fun updateProgress() {
        val p = player ?: return
        val duration = p.duration
        val live = p.isCurrentMediaItemLive || duration == C.TIME_UNSET
        val position = if (seeking) seekTarget() else p.currentPosition
        if (live) {
            timeElapsed.text = "LIVE"
            timeRemaining.text = ""
            seekBar.durationMs = 0
            return
        }
        seekBar.durationMs = duration
        seekBar.positionMs = p.currentPosition
        seekBar.bufferedMs = p.bufferedPosition
        seekBar.targetMs = if (seeking) position else null

        val long = duration >= 3_600_000
        fixTimeWidths(long)
        val total = formatTime(duration, long)
        val text = SpannableStringBuilder(formatTime(position, long)).append("  /  ")
        val start = text.length - 4
        text.append(total)
        text.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.text_muted)),
            start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        timeElapsed.text = text
        timeRemaining.text = "−" + formatTime((duration - position).coerceAtLeast(0), long)
        if (seeking || notice) positionBubble()
    }

    /** Sizes the time labels for the longest time they can show, so the bar keeps its length. */
    private fun fixTimeWidths(long: Boolean) {
        if (longTimes == long) return
        longTimes = long
        val paint = timeElapsed.paint
        val widest = "0123456789".maxByOrNull { paint.measureText(it.toString()) } ?: '0'
        val time = (if (long) "0:00:00" else "00:00").replace('0', widest)
        fun width(view: TextView, sample: String) =
            ceil(view.paint.measureText(sample)).toInt() + view.paddingLeft + view.paddingRight + 2
        timeElapsed.minWidth = width(timeElapsed, "$time  /  $time")
        timeRemaining.minWidth = width(timeRemaining, "-$time")
    }

    private fun togglePlay() {
        val p = player ?: return
        handler.removeCallbacks(resumeAfterSwitch)
        pausedForSwitch = false
        handler.removeCallbacks(resumeAfterFreeze)
        pausedForFreeze = false
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        p.playWhenReady = !p.playWhenReady
        updatePlayButton()
    }

    // ---------------------------------------------------------------------
    // Seeking: presses add up into one jump, shown as a small "+4:00" card.

    private fun nudge(stepSec: Int, direction: Int, event: KeyEvent) {
        val p = player ?: return
        if (p.isCurrentMediaItemLive) return
        if (!p.isCurrentMediaItemSeekable) {
            // While a video is still loading, Media3 says "not seekable" until it knows; let the jump through.
            val timeline = p.currentTimeline
            val window = if (timeline.isEmpty) null else timeline.getWindow(p.currentMediaItemIndex, Timeline.Window())
            if (window != null && !window.isPlaceholder) {
                if (event.repeatCount == 0) showNotice("Can't skip in this video")
                return
            }
        }
        val now = event.eventTime
        if (event.repeatCount > 0 && now - lastNudgeTime < 150) return
        lastNudgeTime = now
        if (!seeking) {
            seeking = true
            seekBaseMs = p.currentPosition
            seekOffsetMs = 0
        }
        val held = now - event.downTime
        val multiplier = if (!prefs.holdSpeedUp || event.repeatCount == 0) 1 else when {
            held > 4_000 -> 6
            held > 2_000 -> 3
            held > 1_000 -> 2
            else -> 1
        }
        seekOffsetMs += direction * stepSec * 1000L * multiplier
        // Don't let the offset run past either end (the end may be unknown).
        val maxOffset = if (p.duration == C.TIME_UNSET) Long.MAX_VALUE / 4 else (p.duration - seekBaseMs - 1_000).coerceAtLeast(0)
        seekOffsetMs = seekOffsetMs.coerceIn(-seekBaseMs, maxOffset)
        clearNotice()
        val sign = if (seekOffsetMs < 0) "−" else "+"
        seekBubble.text = sign + formatTime(abs(seekOffsetMs))
        val entry = entries.getOrNull(p.currentMediaItemIndex)
        val previews = prefs.seekPreviews && entry != null && seekPreview.canPreview(entry.key)
        // On the selected time bar there's more time to keep going; OK jumps at once.
        // With the preview on, a little longer, so there's time to see where you'd land.
        val delay = when {
            seekBar.isFocused -> if (previews) 2_000L else 1_500L
            else -> if (previews) 1_400L else 900L
        }
        if (previews && entry != null) {
            // Video only: the preview needs no subtitles.
            val item = mediaItem(entry).buildUpon().setSubtitleConfigurations(emptyList()).build()
            seekPreview.update(entry.key, item, seekTarget(), SystemClock.uptimeMillis() + delay)
        }
        updateOverlay()
        updateProgress()
        handler.removeCallbacks(commitSeekRunnable)
        handler.postDelayed(commitSeekRunnable, delay)
        if (controlsShown) scheduleHide()
    }

    /** A short message in the seek card, such as "Can't skip in this video". */
    private fun showNotice(text: String, durationMs: Long = 1_800) {
        notice = true
        seekBubble.text = text
        updateOverlay()
        positionBubble()
        handler.removeCallbacks(clearNoticeRunnable)
        handler.postDelayed(clearNoticeRunnable, durationMs)
    }

    private fun showPendingNotice() {
        val note = pendingNotice ?: return
        pendingNotice = null
        showNotice(note, NOTE_MS)
    }

    private val clearNoticeRunnable = Runnable { clearNotice() }

    private fun clearNotice() {
        if (!notice) return
        notice = false
        handler.removeCallbacks(clearNoticeRunnable)
        updateOverlay()
    }

    private fun seekTarget(): Long = (seekBaseMs + seekOffsetMs).coerceAtLeast(0)

    private val commitSeekRunnable = Runnable { commitSeek() }

    private fun commitSeek() {
        if (!seeking) return
        // Presses that cancel out: nothing to jump to (a seek to here would still land on an earlier keyframe).
        if (seekOffsetMs == 0L) {
            cancelSeek()
            return
        }
        val p = player
        // Where the preview's picture really is (a keyframe near the target), so the film starts on what was shown.
        val landing = seekPreview.landingFor(seekTarget())
        val target = landing ?: seekTarget()
        // Past what's loaded (ahead, or further back than is kept): the film loads again from there.
        val reloads = p != null && (target > p.bufferedPosition || target < p.currentPosition - KEPT_BEHIND_MS)
        seekPreview.hide(filmReloads = reloads)
        handler.removeCallbacks(commitSeekRunnable)
        // What was lined up to read past what's loaded is stale.
        if (target > (p?.bufferedPosition ?: 0L)) pictureText.discardAhead()
        if (p != null && landing != null) {
            // On that keyframe exactly (the time in ms can be a fraction before it), or right there if it isn't one.
            p.setSeekParameters(SeekParameters(0L, 1_000L))
            p.seekTo(landing)
            p.setSeekParameters(PlayerFactory.FILM_SEEK)
        } else {
            p?.seekTo(target)
        }
        seeking = false
        updateOverlay()
        updateProgress()
        showPendingNotice()
    }

    private fun cancelSeek() {
        seekPreview.hide()
        handler.removeCallbacks(commitSeekRunnable)
        seeking = false
        updateOverlay()
        updateProgress()
        showPendingNotice()
    }

    /** Keeps the "+4:00" card centred over the thumb on the time bar. */
    private fun positionBubble() {
        seekBubble.post { placeBubble() }
    }

    private fun placeBubble() {
        val layer = seekBubble.parent as View
        val x = seekRow.left + seekBar.left + seekBar.thumbX() - seekBubble.width / 2f
        seekBubble.translationX = x.coerceIn(0f, (layer.width - seekBubble.width).toFloat().coerceAtLeast(0f))
        // The preview sits just above the "+4:00" card, over the same spot on the bar.
        val thumbX = bottomBlock.left + seekRow.left + seekBar.left + seekBar.thumbX()
        val bubbleTop = bottomBlock.top + layer.top + seekBubble.top
        val margin = 24f * resources.displayMetrics.density
        seekPreview.place(thumbX, bubbleTop - 8f * resources.displayMetrics.density, margin, root.width - margin)
    }

    // ---------------------------------------------------------------------
    // Keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP) {
            if (code == KeyEvent.KEYCODE_BACK && consumeBackUp) {
                consumeBackUp = false
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        // Holding Back shouldn't close one thing after another.
        if (code == KeyEvent.KEYCODE_BACK && event.repeatCount > 0) return true

        when (code) {
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (!panel.isOpen) nudge(prefs.ffStepSec, 1, event); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (!panel.isOpen) nudge(prefs.ffStepSec, -1, event); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event.repeatCount == 0) {
                    handler.removeCallbacks(resumeAfterSwitch)
                    pausedForSwitch = false
                    handler.removeCallbacks(resumeAfterFreeze)
                    pausedForFreeze = false
                    when (code) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> player?.play()
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> player?.pause()
                        else -> togglePlay()
                    }
                    if (!controlsShown && !panel.isOpen) showControls()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { lineUpNext(); savePosition(); player?.seekToNextMediaItem(); player?.play(); return true }
            // The menu button (three lines) goes straight to Settings or Subtitle style, as chosen in Settings;
            // pressed again, it closes the window.
            KeyEvent.KEYCODE_MENU -> {
                if (event.repeatCount == 0) {
                    if (panel.isOpen) {
                        panel.close()
                    } else {
                        if (seeking) commitSeek()
                        moreItems[if (prefs.menuOpensStyle) "style" else "settings"]?.open?.invoke()
                    }
                }
                return true
            }
        }

        if (episodesSheet.isOpen && !panel.isOpen) {
            if (code == KeyEvent.KEYCODE_BACK) {
                consumeBackUp = true
                episodesSheet.close()
                return true
            }
            val handled = super.dispatchKeyEvent(event)
            // Keep focus inside the sheet.
            if (episodesSheet.isOpen && !episodesSheet.hasFocus()) episodesSheet.focusSomething()
            val arrow = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
            return handled || arrow
        }

        if (panel.isOpen) {
            if (code == KeyEvent.KEYCODE_BACK) {
                consumeBackUp = true
                panel.close()
                return true
            }
            val before = currentFocus
            val handled = super.dispatchKeyEvent(event)
            // Keep focus inside the window.
            if (panel.isOpen && !panel.root.hasFocus()) {
                if (before != null && before.isAttachedToWindow) before.requestFocus() else panel.focusRow(0)
            }
            // Arrows the rows didn't use would otherwise let Android move focus out of the window.
            val arrow = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
            return handled || arrow
        }

        val isOk = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER
        // OK on the error tries again, unless the controls are up and another button is chosen (Settings, Subtitles).
        if (errorBox.visibility == View.VISIBLE && isOk && (!controlsShown || currentFocus === btnPlay)) {
            // Nothing to try again with a link of a kind the player can't open: OK leaves, as Back does.
            if (cannotOpen || decoderStuck) {
                if (event.repeatCount == 0) finishWithResult()
                return true
            }
            if (frozeOut) {
                // A new player on a new thread, in case the old one is what's stuck.
                frozeOut = false
                errorBox.visibility = View.GONE
                freezeWatch.reset()
                rebuildPlayer()
                player?.play()
                return true
            }
            errorBox.visibility = View.GONE
            retries = 0
            deviceRetries = 0
            player?.prepare()
            player?.play()
            return true
        }
        if (isOk && seeking) {
            commitSeek()
            return true
        }
        if (skipButton.visibility == View.VISIBLE && !controlsShown) {
            if (isOk) {
                if (event.repeatCount == 0) skipPart()
                return true
            }
            if (code == KeyEvent.KEYCODE_BACK) {
                consumeBackUp = true
                skipDismissed = skipStart
                skipDismissedFrom = -1L
                skipButton.visibility = View.GONE
                return true
            }
        }
        if (postPlay.visibility == View.VISIBLE) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { postPlayCredits.requestFocus(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { postPlayGo.requestFocus(); return true }
                KeyEvent.KEYCODE_BACK -> {
                    consumeBackUp = true
                    dismissPostPlay()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    // The controls take over; the card comes back when they go.
                    if (event.repeatCount == 0) {
                        hidePostPlay()
                        showControls()
                    }
                    return true
                }
            }
            if (isOk) {
                if (event.repeatCount == 0) {
                    if (postPlayCredits.isFocused) dismissPostPlay() else if (postPlayExit) exitAfterCredits() else playNextEpisode()
                }
                return true
            }
        }

        if (!controlsShown) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { nudge(prefs.dpadStepSec, -1, event); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { nudge(prefs.dpadStepSec, 1, event); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.repeatCount == 0) showControls()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    consumeBackUp = true
                    if (seeking) cancelSeek() else finishWithResult()
                    return true
                }
            }
            if (isOk) {
                if (event.repeatCount == 0) showControls()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        // Controls are showing.
        scheduleHide()
        if (skipPod.isFocused) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> { seekBar.requestFocus(); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
            }
            if (isOk) {
                if (event.repeatCount == 0) skipPart()
                return true
            }
        }
        if (seekBar.isFocused) {
            // The selected time bar: left / right move along it, OK jumps, down goes back to the buttons.
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { nudge(prefs.dpadStepSec, -1, event); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { nudge(prefs.dpadStepSec, 1, event); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { if (skipPod.visibility == View.VISIBLE) skipPod.requestFocus(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { btnPlay.requestFocus(); return true }
            }
            if (isOk) {
                if (event.repeatCount == 0) togglePlay()
                return true
            }
        }
        // The icon row under the arrow wraps around: left from the first goes to the last, right from the last to the
        // first. Only on a fresh press, so holding the D-pad stops at the end instead of spinning round.
        if (moreShown && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            val items = (0 until moreRow.childCount).map { moreRow.getChildAt(it) }.filter { it.visibility == View.VISIBLE }
            val at = items.indexOf(currentFocus)
            val left = code == KeyEvent.KEYCODE_DPAD_LEFT
            if (at >= 0 && items.size > 1 && (left && at == 0 || !left && at == items.size - 1)) {
                if (event.repeatCount == 0) (if (left) items.last() else items.first()).requestFocus()
                return true
            }
        }
        when (code) {
            KeyEvent.KEYCODE_BACK -> {
                consumeBackUp = true
                if (seeking) cancelSeek() else hideControls()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!moreShown) showMoreRow()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                when {
                    moreShown -> showMainRow()
                    player?.let { !it.isCurrentMediaItemLive && it.duration != C.TIME_UNSET } == true -> seekBar.requestFocus()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------------
    // Settings windows

    private fun openPanel(title: String, from: View, onClosed: () -> Unit = {}, render: SidePanel.() -> Unit) {
        hideControls()
        // Subtitles move into the part of the screen the window doesn't cover, instead of running under it.
        screenSubtitles.setPadding(0, 0, panel.root.layoutParams.width + (18 * resources.displayMetrics.density).toInt(), 0)
        panel.open(title, onClose = {
            screenSubtitles.setPadding(0, 0, 0, 0)
            onClosed()
            showControls(from)
        }, render = render)
        focusSink.isFocusable = false
    }

    private fun trackOptions(type: Int): List<TrackOption> {
        val p = player ?: return emptyList()
        val preferred = if (type == C.TRACK_TYPE_TEXT) prefs.subtitleLanguage.ifEmpty { "en" } else prefs.audioLanguage
        val raw = ArrayList<TrackOption>()
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i, true)) continue
                val f = g.getTrackFormat(i)
                raw += TrackOption(g, i, trackLabel(f, type), f.id?.matches(EXT_ID) == true, g.isTrackSelected(i))
            }
        }
        // English (or the chosen default language) first, then the rest in file order.
        val sorted = raw.sortedBy { o ->
            val lang = o.group.getTrackFormat(o.trackIndex).language.orEmpty()
            if (lang.startsWith(preferred)) 0 else 1
        }
        // Make repeated names distinguishable without showing codecs or sources.
        val seen = HashMap<String, Int>()
        return sorted.map { o ->
            val n = (seen[o.label] ?: 0) + 1
            seen[o.label] = n
            if (n == 1) o else TrackOption(o.group, o.trackIndex, "${o.label} $n", o.external, o.selected)
        }
    }

    private fun trackLabel(f: Format, type: Int): String {
        val lang = f.language
        val name = if (lang.isNullOrEmpty() || lang == "und" || lang == C.LANGUAGE_UNDETERMINED) {
            f.label ?: "Unknown"
        } else {
            Locale.forLanguageTag(lang).getDisplayLanguage(Locale.ENGLISH).ifEmpty { lang }
        }
        val extras = ArrayList<String>()
        if (f.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0) extras += "SDH"
        if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) extras += "Forced"
        if (f.roleFlags and C.ROLE_FLAG_COMMENTARY != 0) extras += "Commentary"
        if (type == C.TRACK_TYPE_TEXT && extras.isEmpty()) {
            // Labels like "Signs & Songs" help tell two English tracks apart.
            val label = f.label
            if (label != null && !label.equals(name, true) && label.length <= 24) {
                extras += label
            }
        }
        return if (extras.isEmpty()) name else "$name (${extras.joinToString(", ")})"
    }

    /** Track ids gain a "0:" style prefix when subtitles are added, so ignore just that prefix. */
    private fun sameId(o: TrackOption, id: String): Boolean {
        val trackId = o.group.getTrackFormat(o.trackIndex).id ?: return false
        return trackId == id || trackId.endsWith(":$id") || id.endsWith(":$trackId")
    }

    private fun hasSsaTrack(): Boolean = hasTextTrack { mime -> mime == MimeTypes.TEXT_SSA }

    private fun hasTextTrack(matches: (String?) -> Boolean): Boolean = player?.currentTracks?.groups?.any { g ->
        g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { i ->
            val f = g.getTrackFormat(i)
            matches(f.sampleMimeType) || matches(f.codecs) || matches(f.containerMimeType)
        }
    } == true

    /**
     * Whether "Keep embedded styling" changed in a way the current player can't follow: ASS/SSA switches between
     * libass and plain text, and the picture subtitles being shown are only read as text (ahead of time) by a player
     * without libass.
     */
    private fun stylingNeedsRebuild(): Boolean = prefs.keepAssStyling != builtWithAss &&
        (hasSsaTrack() || (!prefs.keepAssStyling && readablePictureTrack() != null))

    private fun selectTrack(o: TrackOption) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(o.group.type, false)
            .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.trackIndex))
            .build()
    }

    /** A track picked by hand: chosen now, and the same kind is picked again in the next episode. */
    private fun chooseTrack(o: TrackOption) {
        val f = o.group.getTrackFormat(o.trackIndex)
        val type = o.group.type
        trackWishes[type] = TrackWish(
            f.language, f.label, f.roleFlags, f.selectionFlags and C.SELECTION_FLAG_FORCED != 0, f.channelCount,
        )
        selectTrack(o)
        // Downloaded subtitles chosen (or others instead): remembered as the ones on, last used first.
        if (type == C.TRACK_TYPE_TEXT) currentEntry()?.let { SavedSubtitles.use(this, it.key, savedFileOf(f.id)) }
        // The sound picked for a film or show is picked again when it's opened another time.
        if (type == C.TRACK_TYPE_AUDIO) currentEntry()?.let { e -> audioKey(e)?.let { rememberAudio(it, trackWishes[type]!!) } }
        // For as long as the player is open, that language comes first (Settings keeps the default).
        val p = player ?: return
        val language = f.language ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().apply {
            if (type == C.TRACK_TYPE_AUDIO) setPreferredAudioLanguage(language)
            if (type == C.TRACK_TYPE_TEXT) setPreferredTextLanguage(language).setPreferredTextRoleFlags(f.roleFlags)
        }.build()
    }

    /**
     * Another episode's tracks are in: the same kind of sound and subtitles as picked by hand before, by language and
     * then name ("English (Full)" over "English (Signs)"), then kind, then channels.
     */
    private fun reapplyChoices() {
        if (player?.currentTracks?.groups.isNullOrEmpty()) return
        reapplyWishes = false
        for ((type, wish) in trackWishes) {
            val same = trackOptions(type).filter { it.group.getTrackFormat(it.trackIndex).language == wish.language }
            fun format(o: TrackOption) = o.group.getTrackFormat(o.trackIndex)
            // The same kind (forced or full subtitles, commentary or main sound) matters more than the name, which
            // tracks often don't have or share.
            fun kind(o: TrackOption) = format(o).roleFlags == wish.roleFlags &&
                (format(o).selectionFlags and C.SELECTION_FLAG_FORCED != 0) == wish.forced
            fun channels(o: TrackOption) = type != C.TRACK_TYPE_AUDIO || format(o).channelCount == wish.channels
            fun named(o: TrackOption) = wish.label != null && format(o).label == wish.label
            val pick = same.firstOrNull { named(it) && kind(it) && channels(it) }
                ?: same.firstOrNull { named(it) && kind(it) }
                ?: same.firstOrNull { kind(it) && channels(it) }
                ?: same.firstOrNull { kind(it) }
                ?: same.firstOrNull { channels(it) }
                ?: same.firstOrNull()
            if (pick != null && !pick.selected) selectTrack(pick)
        }
    }

    /** One key per film (its link's file on its provider) or show (its name), for the sound last picked for it. */
    private fun audioKey(entry: Entry): String? {
        val episode = entry.info.episode != null || NextEpisode.isEpisodeLink(entry.uri)
        if (episode) {
            val name = entry.info.searchQuery.trim().lowercase(Locale.ROOT)
            return if (name.isEmpty() || name == TitleInfo.NO_NAME.lowercase(Locale.ROOT)) null else "show|$name"
        }
        val file = entry.uri.lastPathSegment ?: return null
        return "film|${NextEpisode.portal(entry.uri) ?: entry.uri.authority.orEmpty()}|$file"
    }

    private fun audioMemory() = getSharedPreferences("audio_choices", MODE_PRIVATE)

    private fun rememberAudio(key: String, wish: TrackWish) {
        val sp = audioMemory()
        val value = listOf(wish.language.orEmpty(), wish.label.orEmpty(), wish.roleFlags, wish.channels, System.currentTimeMillis())
            .joinToString("\u0001")
        val editor = sp.edit().putString(key, value)
        // Only the latest few hundred titles are kept.
        val all = sp.all
        if (all.size > AUDIO_KEPT) {
            all.entries.sortedBy { (it.value as? String)?.substringAfterLast('\u0001')?.toLongOrNull() ?: 0L }
                .take(all.size - AUDIO_KEPT).forEach { editor.remove(it.key) }
        }
        editor.apply()
    }

    /** Once the tracks of a title opened again are in: the sound picked for it last time is picked again. */
    private fun recallAudio() {
        val entry = currentEntry() ?: return
        val key = audioKey(entry) ?: return
        if (key == audioRecalledFor || trackOptions(C.TRACK_TYPE_AUDIO).size < 2) return
        audioRecalledFor = key
        val parts = audioMemory().getString(key, null)?.split('\u0001') ?: return
        if (parts.size < 4 || parts[0].isEmpty()) return
        trackWishes[C.TRACK_TYPE_AUDIO] = TrackWish(
            parts[0], parts[1].ifEmpty { null }, parts[2].toIntOrNull() ?: 0, false, parts[3].toIntOrNull() ?: 0,
        )
        reapplyWishes = true
    }

    private fun subtitlesOff() {
        trackWishes.remove(C.TRACK_TYPE_TEXT)
        currentEntry()?.let { SavedSubtitles.use(this, it.key, null) }
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    private fun openSubtitlesPanel(from: View) = openPanel("Subtitles", from) {
        val options = trackOptions(C.TRACK_TYPE_TEXT)
        val embedded = options.filter { !it.external }
        // Downloaded ones first, last used first; then the rest.
        val order = currentEntry()?.let { e -> SavedSubtitles.forVideo(this@PlayerActivity, e.key).map { it.file } }.orEmpty()
        val other = options.filter { it.external }.sortedBy { o ->
            val at = order.indexOf(savedFileOf(o.group.getTrackFormat(o.trackIndex).id))
            if (at < 0) Int.MAX_VALUE else at
        }
        val anySelected = options.any { it.selected }
        header("In this video")
        if (embedded.isEmpty()) message("No subtitles inside this video.")
        embedded.forEach { o ->
            row(o.label, checked = o.selected) { chooseTrack(o); handler.postDelayed({ refreshLabels(); refresh() }, 150) }
        }
        row("Off", checked = !anySelected) { subtitlesOff(); handler.postDelayed({ refreshLabels(); refresh() }, 150) }
        header("Other subtitles")
        other.forEach { o ->
            // A downloaded one says which release it was made for.
            val release = savedFileOf(o.group.getTrackFormat(o.trackIndex).id)?.let { savedRelease(it) }
            row(o.label, release, checked = o.selected) { chooseTrack(o); handler.postDelayed({ refreshLabels(); refresh() }, 150) }
        }
        row("Search online") { panel.close(); openFindPanel(from) }
        spacer(6)
        stepper("Delay", formatDelay(delays.subtitleUs)) { dir ->
            delays.subtitleUs += dir * 100_000L
            autoSync.handOff(delays.subtitleUs)
            refreshLabels()
        }
    }

    private fun openSubtitleDelayPanel(from: View) {
        delayPanelOpen = true
        openPanel("Subtitle delay", from, onClosed = { delayPanelOpen = false }) {
            message("Left makes subtitles earlier, right makes them later.")
            stepper("Delay", formatDelay(delays.subtitleUs)) { dir ->
                delays.subtitleUs += dir * 100_000L
                autoSync.handOff(delays.subtitleUs)
                refreshLabels()
            }
            renderAutoSync(this)
            row("Reset") {
                delays.subtitleUs = 0
                autoSync.handOff(delays.subtitleUs)
                refreshLabels()
                refresh()
            }
        }
    }

    /**
     * Auto sync in the Subtitle delay window: its state as a badge, like the ones in Settings ("Synced" in blue,
     * "Listening" or "Off" in gray), a line on what it found, and Resync and Undo. Pressing the badge's row turns it
     * on or off for these subtitles.
     */
    private fun renderAutoSync(panel: SidePanel) = with(panel) {
        val downloaded = autoSync.file != null
        val (badge, style) = when {
            !prefs.autoSync -> "Off" to Rows.Badge.INACTIVE
            !downloaded -> "Off" to Rows.Badge.INACTIVE
            else -> when (autoSync.state) {
                AutoSync.State.SYNCED -> "Synced" to Rows.Badge.ACTIVE
                AutoSync.State.LISTENING -> "Listening" to Rows.Badge.BUSY
                AutoSync.State.NOT_SYNCED -> "Not synced" to Rows.Badge.INACTIVE
                AutoSync.State.OFF -> "Off" to Rows.Badge.INACTIVE
            }
        }
        spacer(6)
        badgeRow("Auto sync", badge, style) {
            when {
                !prefs.autoSync -> { prefs.autoSync = true; autoSync.setOn(true); followSubtitle() }
                !downloaded -> {}
                autoSync.state == AutoSync.State.OFF || autoSync.state == AutoSync.State.NOT_SYNCED -> autoSync.resync()
                else -> autoSync.setOn(false)
            }
            refresh()
        }
        val note = when {
            !prefs.autoSync -> "Turned off in Settings. Press to turn it on."
            !downloaded -> "Lines up subtitles found with Search online. These came with the video."
            else -> autoSync.detail
        }
        note?.let { message(it) }
        if (downloaded && prefs.autoSync) {
            row("Resync") { autoSync.resync(); refresh() }
            if (autoSync.undoUs != null) row("Undo auto sync") { autoSync.undo(); refresh() }
            syncWithRow(panel)
        }
    }

    /**
     * "Sync with": what auto sync lines up against, when the film has subtitles of its own. Auto (the best of them,
     * named), each of them with how well it matches the downloaded subtitles, or the speech only. Kept for the show.
     */
    private fun syncWithRow(panel: SidePanel) = with(panel) {
        val tracks = autoSync.tracks
        val chosen = autoSync.syncWith()
        if (tracks.isEmpty() && chosen == null) return@with
        val inUse = tracks.firstOrNull { it.info.key == autoSync.trackInUse }
        val auto = when {
            // Just the track: the row's value has room for little more, and the list says how well each matches.
            inUse != null -> "Auto (${inUse.info.name()})"
            tracks.isNotEmpty() && autoSync.trackInUse == null && autoSync.state != AutoSync.State.LISTENING -> "Auto (speech)"
            else -> "Auto"
        }
        val keys = listOf<String?>(null) + tracks.map { it.info.key } + AutoSync.SPEECH
        val options = listOf(auto) + tracks.map { "${it.info.name()}, ${it.strength.label}" } + "Speech only"
        // A track chosen before that this film doesn't have: shown as Auto.
        val selected = keys.indexOf(chosen).let { if (it < 0) 0 else it }
        choice("Sync with", options, selected) { i -> autoSync.chooseSyncWith(keys[i]); refreshLabels() }
    }

    /** Tells auto sync which subtitles are on: a file from Search online, or anything else (left alone). */
    private fun followSubtitle() {
        if (!::autoSync.isInitialized) return
        val entry = entries.getOrNull(player?.currentMediaItemIndex ?: playingIndex)
        val id = selectedFormat(C.TRACK_TYPE_TEXT)?.id
        val config = if (id == null || entry == null) null else entry.subtitles.firstOrNull { c ->
            val cid = c.id ?: return@firstOrNull false
            id == cid || id.endsWith(":$cid")
        }
        val path = config?.uri?.takeIf { it.scheme == "file" }?.path
        val file = path?.let { File(it) }?.takeIf { it.parentFile == downloadDir() && it.isFile }
        // Which release they were made for (subtitles from the same source start from the last episode's delay).
        val saved = if (file == null || entry == null) null else SavedSubtitles.forVideo(this, entry.key).firstOrNull { it.file == file.name }
        autoSync.follow(file, entry?.key, saved?.release, saved?.language)
    }

    /** Where Search online puts the subtitles it downloads. */
    private fun downloadDir() = SavedSubtitles.dir(this)

    private fun openAudioPanel(from: View) = openPanel("Audio", from) {
        val options = trackOptions(C.TRACK_TYPE_AUDIO)
        if (options.isEmpty()) message("No audio tracks.")
        options.forEach { o ->
            row(o.label, checked = o.selected) { chooseTrack(o); handler.postDelayed({ refreshLabels(); refresh() }, 150) }
        }
        spacer(6)
        stepper("Delay", formatDelay(delays.audioUs)) { dir ->
            delays.audioUs = (delays.audioUs + dir * 50_000L).coerceIn(MIN_AUDIO_DELAY_US, MAX_AUDIO_DELAY_US)
            refreshLabels()
        }
    }

    private fun openAudioDelayPanel(from: View) = openPanel("Audio delay", from) {
        message("Left makes the sound earlier, right makes it later.")
        stepper("Delay", formatDelay(delays.audioUs)) { dir ->
            delays.audioUs = (delays.audioUs + dir * 50_000L).coerceIn(MIN_AUDIO_DELAY_US, MAX_AUDIO_DELAY_US)
            refreshLabels()
        }
        row("Reset") { delays.audioUs = 0; refreshLabels(); refresh() }
    }

    private fun openStylePanel(from: View) {
        // Changes show on the subtitles as they're made; paused, example text stands in.
        stylePreviewing = true
        showTextCues()
        openPanel("Subtitle style", from, onClosed = {
            stylePreviewing = false
            showTextCues()
        }) {
            settingsPanels.renderSubtitleStyle(
                this,
                onStyleChanged = { applySubtitleStyle(); refreshLabels() },
                onAssChanged = {
                    applySubtitleStyle()
                    // Only ASS/SSA switches between libass and plain text; other subtitles just restyle.
                    // Tracks not known yet (still loading) are checked again in onTracksChanged.
                    if (stylingNeedsRebuild()) rebuildPlayer()
                },
            )
        }
    }

    private fun openDisplayPanel(from: View) = openPanel("Display size", from) {
        DISPLAY_MODES.forEachIndexed { i, name ->
            row(name, checked = i == displayMode) {
                displayMode = i
                prefs.displayMode = i
                applyDisplayMode()
                refreshLabels()
                refresh()
            }
        }
    }

    /**
     * Screen dimmer: the customs, then the one on, with its display amount (the film) and glass amount (all glass,
     * Glass look) set with arrows. Custom 1 keeps changes at once, the others when Save custom is pressed. Whatever is
     * picked or saved stays on for every video.
     */
    private fun openDimPanel(from: View) = openPanel("Screen dimmer", from) {
        val active = dimmer.active()
        header("Customs")
        dimmer.customs().forEach { c ->
            val on = c.id == active.id
            row(c.name, value = if (on) null else "${c.display}% · ${c.glass}%", checked = on) {
                dimmer.chooseCustom(c.id)
                applyDim()
                refreshLabels()
                refresh()
            }
        }
        header(active.name)
        stepper("Display dimmer", "${active.display}%") { dir ->
            dimmer.stepDisplay(dir)
            applyDim()
            refreshLabels()
        }
        stepper("Glass dimmer", "${active.glass}%") { dir ->
            dimmer.stepGlass(dir)
            applyDim()
            refreshLabels()
        }
        row("Auto", if (active.auto) "On" else "Off") {
            dimmer.toggleAuto()
            applyDim()
            refresh()
        }
        message(
            when {
                !prefs.glassLook -> "Glass dimmer darkens the glass when Glass look is on (Settings, Look)."
                active.auto -> "Auto keeps the glass as dim as the display."
                else -> "Glass dimmer darkens every glass button and window."
            },
        )
        val changed = dimmer.hasUnsaved()
        if (changed) message("Changed. Press Save custom to keep it.", accent = true)
        spacer(6)
        // Blue, like the current choice, while there's something to save.
        row("Save custom") { saveCustomDim(this) }.isSelected = changed
        if (active.id != Dimmer.FIRST_ID) {
            row("Delete \"${active.name}\"") {
                FireDialog.ask(
                    this@PlayerActivity,
                    "Delete \"${active.name}\"?",
                    listOf<Pair<String, () -> Unit>>(
                        "Delete" to { dimmer.delete(active.id); applyDim(); refreshLabels(); refresh() },
                        "Keep it" to {},
                    ),
                    // Starts on the safe answer, so a double press doesn't delete.
                    focus = 1,
                    fromPanel = true,
                )
            }
        }
    }

    /**
     * Save custom: keeps the changes to the custom on, renames it, or saves what's on as a new custom. Custom 1
     * keeps its changes at once, so for it Save custom is for renaming it or making a new one.
     */
    private fun saveCustomDim(panel: SidePanel) {
        val active = dimmer.active()
        fun askName(title: String, initial: String, except: String?, done: (String) -> Unit) {
            SidePanel.prompt(this, title, initial) { name ->
                val clean = name.trim()
                val problem = dimmer.nameProblem(clean, except)
                if (problem != null) askName(problem, clean, except, done) else done(clean)
            }
        }
        val after = { applyDim(); refreshLabels(); panel.refresh() }
        val answers = ArrayList<Pair<String, () -> Unit>>()
        if (dimmer.hasUnsaved()) answers += "Save \"${active.name}\"" to { dimmer.saveChanges(); after() }
        answers += "Rename \"${active.name}\"" to {
            askName("New name", active.name, active.id) { dimmer.rename(it); after() }
        }
        answers += "Save as a new custom" to {
            askName("Custom name", "", null) { dimmer.saveAsNew(it); after() }
        }
        FireDialog.ask(this, "Save custom", answers, fromPanel = true)
    }

    /**
     * Darkens the film by the display amount, and every piece of glass by the glass amount (Glass look). The
     * glass shows a copy of the film taken from under the dimmer, so it's dimmed on its own.
     */
    private fun applyDim() {
        val amounts = dimmer.amounts()
        dimmerView.alpha = amounts.display / 100f
        dimmerView.visibility = if (amounts.display == 0) View.GONE else View.VISIBLE
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

    private fun openSleepPanel(from: View) = openPanel("Sleep timer", from) {
        row("Off", checked = sleepEndsAt == 0L && !sleepAtEnd) { setSleep(0); refresh() }
        listOf(15, 30, 45, 60, 90, 120).forEach { min ->
            row("$min min") { setSleep(min); refresh() }
        }
        row("End of this video", checked = sleepAtEnd) { setSleep(-1); refresh() }
        if (sleepEndsAt > 0) message("Stops in ${sleepLabel()}.")
    }

    private fun setSleep(minutes: Int) {
        sleepAtEnd = minutes < 0
        player?.pauseAtEndOfMediaItems = sleepAtEnd
        sleepEndsAt = if (minutes > 0) SystemClock.elapsedRealtime() + minutes * 60_000L else 0L
        sleepRemainingMs = 0
        refreshLabels()
    }

    private fun sleepLabel(): String = when {
        sleepAtEnd -> "End"
        sleepEndsAt > 0 -> "${((sleepEndsAt - SystemClock.elapsedRealtime()) / 60_000 + 1).coerceAtLeast(1)} min"
        else -> "Off"
    }

    private fun openSettingsPanel(from: View) {
        val passthrough = prefs.passthrough
        val playNext = prefs.playNextEpisode
        val matchFps = prefs.matchFrameRate
        // Rebuild once the window closes if a playback setting changed.
        settingsOpen = true
        openPanel("Settings", from, onClosed = {
            settingsOpen = false
            if (prefs.passthrough != passthrough) rebuildPlayer()
            if (prefs.playNextEpisode != playNext) nextEpisodeSwitched()
            // Only when the setting itself changed: matching again would pause the film for nothing.
            if (prefs.matchFrameRate != matchFps) {
                if (prefs.matchFrameRate) {
                    matchedFpsForIndex = -1
                    refusedForIndex = -1
                    rematches = 0
                    matchFrameRate()
                } else {
                    frameRateMatcher.reset()
                }
            }
            refreshLabels()
        }) {
            renderThisTitle(from)
            settingsPanels.renderSettings(this)
        }
    }

    // ---------------------------------------------------------------------
    // The show's episodes (ShowGuide): names on screen, the Episodes sheet, This episode in Settings, Fix match

    private fun currentEntry(): Entry? = entries.getOrNull(player?.currentMediaItemIndex ?: startIndex)

    /** This episode in the show's guide, when the guide is this show's. */
    private fun guideEpisode(entry: Entry): ShowGuide.Ep? {
        val g = guide ?: return null
        if (NextEpisode.portal(entry.uri) != g.portal) return null
        return g.episode(NextEpisode.episodeId(entry.uri))
    }

    /** "S2 E3 · Bit by a Dead Bee": the numbers bright, the episode's name beside them. */
    private fun episodeLine(label: String, name: String): CharSequence = SpannableStringBuilder(label).apply {
        setSpan(ForegroundColorSpan(ContextCompat.getColor(this@PlayerActivity, R.color.text)), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (name.isNotEmpty()) append("  ·  ").append(name)
    }

    /** A film's name as put right by hand in Fix match, or null. */
    private fun filmFix(entry: Entry): String? {
        if (entry.info.episode != null || NextEpisode.isEpisodeLink(entry.uri)) return null
        val portal = NextEpisode.portal(entry.uri) ?: return null
        val id = entry.uri.lastPathSegment?.substringBeforeLast('.') ?: return null
        return matchFixes.film(portal, id)
    }

    /** The guide kept from the last time this show played, shown until the look-up makes a fresh one. */
    private fun loadSavedGuide(uri: Uri) {
        val id = NextEpisode.rememberedShowId(this, uri) ?: return
        val key = NextEpisode.showKey(uri, id) ?: return
        val app = applicationContext
        io.execute {
            val saved = ShowGuide.saved(app, key) ?: return@execute
            handler.post {
                if (isFinishing || guide != null || saved.episode(NextEpisode.episodeId(uri)) == null) return@post
                guide = saved
                updateTitle()
            }
        }
    }

    /** The provider answered about the show: pairs its episodes with TMDB's, unless that was done already this time. */
    private fun makeGuide(uri: Uri, found: NextEpisode.Found) {
        lastFound = found
        lastFoundUri = uri
        val key = NextEpisode.showKey(uri, found.seriesId) ?: return
        val have = guide
        if (key in guidesMade && have != null && have.key == key && have.episode(NextEpisode.episodeId(uri)) != null) return
        guidesMade += key
        buildGuide(uri, found, key)
    }

    private fun buildGuide(uri: Uri, found: NextEpisode.Found, key: String, then: (() -> Unit)? = null) {
        val portal = NextEpisode.portal(uri) ?: return
        val app = applicationContext
        thread(name = "FirePlayerGuide", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            val made = runCatching { ShowGuide.build(app, key, portal, found, tmdb, matchFixes) }
                .onFailure { android.util.Log.w("FirePlayerGuide", "Couldn't make the show's guide", it) }
                .getOrNull()
            handler.post {
                if (isFinishing || made == null) return@post
                guide = made
                updateTitle()
                showGuideInSheet()
                if (panel.isOpen && settingsOpen) panel.refresh()
                then?.invoke()
            }
        }
    }

    /** Subtitles would show through the episode cards; they step aside while the sheet is up. */
    private fun fadeSubtitles(shown: Boolean) {
        val alpha = if (shown) 1f else 0f
        screenSubtitles.animate().alpha(alpha).setDuration(150).start()
        frameSubtitles.animate().alpha(alpha).setDuration(150).start()
    }

    private fun openEpisodes() {
        hideControls()
        fadeSubtitles(false)
        episodesSheet.open("Looking up this show's episodes…")
        val entry = currentEntry() ?: return
        if (guideEpisode(entry) != null) {
            showGuideInSheet()
            return
        }
        // Not known yet: asked for now, without waiting for the film to be well ahead.
        lookupWantedAt = 0L
        if (!lookupInFlight) {
            nextLookedUpFor = null
            startNextEpisodeLookup()
        }
        if (!lookupInFlight && guide == null) {
            episodesSheet.fail(NextEpisode.status ?: "Couldn't get this show's episodes from your provider.")
        }
    }

    private fun showGuideInSheet() {
        val g = guide ?: return
        if (!episodesSheet.isOpen) return
        val entry = currentEntry() ?: return
        if (guideEpisode(entry) == null) return
        episodesSheet.show(g, NextEpisode.episodeId(entry.uri)) { ep -> watchedShare(entry, ep) }
    }

    /** How far an episode was watched (0 to 1): the one playing by the player, the others by their resume points. */
    private fun watchedShare(current: Entry, ep: ShowGuide.Ep): Float {
        if (ep.id == NextEpisode.episodeId(current.uri)) {
            val p = player ?: return 0f
            return if (p.duration > 0) p.currentPosition.toFloat() / p.duration else 0f
        }
        val pos = prefs.savedPosition(NextEpisode.episodeUri(current.uri, ep.id, ep.extension).toString())
        val length = ep.runtimeMin * 60_000L
        return if (pos > 0 && length > 0) pos.toFloat() / length else 0f
    }

    /** Plays another episode of the show, chosen in Episodes: from where it was left, or the start. */
    private fun playEpisode(ep: ShowGuide.Ep) {
        val p = player ?: return
        val index = p.currentMediaItemIndex
        val current = entries.getOrNull(index) ?: return
        episodesSheet.close()
        if (ep.id == NextEpisode.episodeId(current.uri)) return
        hideControls()
        savePosition()
        hidePostPlay()
        // Whatever was lined up after this one (the next episode) makes way; the look-up runs again from the new one.
        for (i in entries.lastIndex downTo index + 1) {
            entries.removeAt(i)
            p.removeMediaItem(i)
        }
        val name = guide?.searchName ?: current.info.title
        val raw = String.format(Locale.ROOT, "%s S%02dE%02d %s", name, ep.season, ep.number, ep.name).trim()
        val entry = Entry(NextEpisode.episodeUri(current.uri, ep.id, ep.extension), raw, null, nextEpisode = true)
        attachSaved(entry, chooseOn = false)
        entries += entry
        p.addMediaItem(mediaItem(entry))
        nextLookedUpFor = null
        p.seekTo(entries.lastIndex, prefs.savedPosition(entry.key))
        p.play()
    }

    // Settings, This episode / This film
    private var settingsOpen = false
    private var thisTitleOpen = false
    private var thisTitleSession = -1

    /** The top of Settings: what this film or episode was matched as, and how to put it right. */
    private fun SidePanel.renderThisTitle(from: View) {
        val entry = currentEntry() ?: return
        if (thisTitleSession != session) {
            thisTitleSession = session
            thisTitleOpen = false
        }
        val portal = NextEpisode.portal(entry.uri)
        val episode = findsNextEpisode && NextEpisode.canFind(entry.uri)
        if (episode) {
            header("This episode")
            val g = guide
            val ep = guideEpisode(entry)
            if (g == null || ep == null) {
                row("Looking up this episode…", enabled = false) {}
                return
            }
            val (badge, style) = when {
                !tmdb.isConfigured -> "No TMDB key" to Rows.Badge.INACTIVE
                ep.match == ShowGuide.Match.SURE -> "Matched" to Rows.Badge.ACTIVE
                ep.match == ShowGuide.Match.UNSURE -> "Unsure" to Rows.Badge.INACTIVE
                else -> "Not found" to Rows.Badge.INACTIVE
            }
            dropdownRow("${g.searchName} · ${ep.label}", badge, style, thisTitleOpen) {
                thisTitleOpen = !thisTitleOpen
                refresh()
            }
            if (!thisTitleOpen) return
            val why = when {
                !tmdb.isConfigured -> "Add a TMDB API key below to match episodes with TMDB's names and numbers."
                ep.why != null -> ep.why.orEmpty()
                ep.match == ShowGuide.Match.NONE -> "TMDB doesn't know this show by your provider's name. Fix match finds it."
                ep.name.isNotEmpty() -> "Matched on TMDB: ${ep.label} · ${ep.name}."
                else -> "Matched on TMDB."
            }
            message(why)
            if (ep.match == ShowGuide.Match.UNSURE) row("Looks right") {
                matchFixes.confirm(g.key, ep.id)
                rebuildFromFix()
                refresh()
            }
            if (tmdb.isConfigured && g.tmdbId > 0) row("Show details") { openDetails(from, show = true) }
            if (tmdb.isConfigured) row("Fix match") { panel.close(); openFixShow(from) }
            if (portal != null) row("Source", portal) {}
        } else if (portal != null && entry.info.episode == null) {
            header("This film")
            val fixed = filmFix(entry)
            val (badge, style) = when {
                fixed != null || entry.english != null -> "Matched" to Rows.Badge.ACTIVE
                else -> "Not matched" to Rows.Badge.INACTIVE
            }
            dropdownRow(fixed ?: entry.info.title, badge, style, thisTitleOpen) {
                thisTitleOpen = !thisTitleOpen
                refresh()
            }
            if (!thisTitleOpen) return
            if (tmdb.isConfigured) row("Film details") { openDetails(from, show = false) }
            if (tmdb.isConfigured) row("Fix match") { panel.close(); openFixFilm(from) }
            else message("Add a TMDB API key below to fix this film's name.")
            row("Source", portal) {}
        }
    }

    // Show details / Film details: TMDB's cover and story for the video they were asked for.
    private var aboutFor: String? = null
    private var about: Tmdb.About? = null
    private var aboutStatus: String? = null

    private fun openDetails(from: View, show: Boolean) {
        val entry = currentEntry() ?: return
        if (aboutFor != entry.key || (about == null && aboutStatus != LOADING)) {
            aboutFor = entry.key
            about = null
            aboutStatus = LOADING
            loadAbout(entry, show)
        }
        panel.close()
        openPanel(if (show) "Show details" else "Film details", from) {
            val a = about
            if (a == null) message(aboutStatus ?: LOADING)
            else details(a, if (show) guideEpisode(entry) else null, NextEpisode.portal(entry.uri))
        }
    }

    private fun loadAbout(entry: Entry, show: Boolean) {
        val key = entry.key
        val showId = guide?.tmdbId ?: 0
        val film = if (show) null else matchFor(entry)?.tmdbId
        io.execute {
            val found = runCatching {
                val id = if (show) showId else film ?: filmTmdbId(entry)
                if (id > 0) tmdb.about(id, show) else null
            }
            handler.post {
                if (isFinishing || aboutFor != key) return@post
                about = found.getOrNull()
                aboutStatus = when {
                    found.isFailure -> "Couldn't reach TMDB just now."
                    about == null -> "TMDB has no details for this ${if (show) "show" else "film"}. Fix match finds it."
                    else -> null
                }
                if (panel.isOpen) panel.refresh()
            }
        }
    }

    /** Blocks; on [io]. A film's TMDB id: as the title server matched it, else as TMDB finds its name. 0 for none. */
    private fun filmTmdbId(entry: Entry): Int {
        val server = TitleProxy.serverIn(entry.uri)
        val id = entry.uri.lastPathSegment?.substringBeforeLast('.')?.toIntOrNull()
        if (server != null && id != null) {
            runCatching { book().about(server, TitleBook.Kind.MOVIE, id)?.tmdb }.getOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        val info = entry.info
        val year = YEAR_IN_NAME.find(info.title)?.groupValues?.get(1)?.toIntOrNull()
        return tmdb.find(info.searchQuery, year, false)?.id ?: 0
    }

    /**
     * Show or film details, laid out like Fix match: the cover with the name and a line of facts beside it, then the
     * rating, TMDB's score and the source in two columns, the story, and for a show this episode as a card.
     */
    private fun SidePanel.details(a: Tmdb.About, ep: ShowGuide.Ep?, source: String?) {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun text(value: CharSequence, sp: Float, color: Int, font: Int, top: Int = 0) = TextView(this@PlayerActivity).apply {
            text = value
            setTextColor(ContextCompat.getColor(this@PlayerActivity, color))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp)
            typeface = androidx.core.content.res.ResourcesCompat.getFont(this@PlayerActivity, font)
            setLineSpacing(2f * d, 1f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(top) }
        }
        val facts = listOfNotNull(
            a.year,
            if (a.seasons > 0) "TV show" else "Film",
            a.runtime.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" },
            a.seasons.takeIf { it > 0 }?.let { if (it == 1) "1 season" else "$it seasons" },
        ).joinToString(" · ")
        val side = LinearLayout(this@PlayerActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(a.name, 17f, R.color.text, R.font.figtree_700))
            if (facts.isNotEmpty()) addView(text(facts, 12f, R.color.text_muted, R.font.figtree_500, top = 3))
            if (a.genres.isNotEmpty()) addView(text(a.genres.joinToString(", "), 12f, R.color.text_muted, R.font.figtree_500, top = 1))
            if (a.tagline.isNotEmpty()) addView(text(a.tagline, 12f, R.color.text, R.font.figtree_500, top = 8))
        }
        view(LinearLayout(this@PlayerActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cover(a.poster, 84, 126, 8).apply { contentDescription = a.name }, LinearLayout.LayoutParams(dp(84), dp(126)))
            addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
        }, top = 2, bottom = 8)
        // The age rating as a small outlined badge, as before.
        val rated = a.ageRating.takeIf { it.isNotEmpty() }?.let { r ->
            LinearLayout(this@PlayerActivity).apply {
                addView(text(r, 11f, R.color.text, R.font.figtree_700).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setPadding(dp(6), dp(1), dp(6), dp(1))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 4 * d
                        setStroke(dp(1).coerceAtLeast(1), 0x99FFFFFF.toInt())
                    }
                    contentDescription = "Rated $r"
                })
            }
        }
        facts(listOfNotNull(
            rated?.let { "Rated" to it },
            a.rating.takeIf { it > 0 }?.let { "TMDB" to fact(String.format(Locale.ROOT, "★ %.1f", it)) },
            source?.let { "Source" to fact(it) },
        ))
        // Kept to what fits the window: it has nothing to move focus to, so it isn't scrolled.
        if (a.overview.isNotEmpty()) view(text(a.overview, 13f, R.color.text, R.font.figtree_500).apply {
            maxLines = if (ep != null) 6 else 12
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, top = 2, bottom = 6)
        if (ep != null && (ep.name.isNotEmpty() || ep.overview.isNotEmpty())) {
            header("This episode")
            view(episodeCard(ep.image, ep.label, ep.name,
                listOfNotNull(prettyDate(ep.airDate), ep.runtimeMin.takeIf { it > 0 }?.let { "${it}m" }).joinToString(" · "),
                ep.overview), top = 2, bottom = 4)
        }
    }

    /**
     * An episode as a card: its picture on the left; beside it "S2 E4" in blue and its name in bold, the date and
     * length, and the start of its story. Fix match and Show details use the same card.
     */
    private fun episodeCard(image: String?, label: String, name: String, meta: String, story: String): View {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        fun font(res: Int) = androidx.core.content.res.ResourcesCompat.getFont(this, res)
        val picture = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 8 * d; setColor(0x22FFFFFF) }
            clipToOutline = true
        }
        app.fireplayer.ui.ImageLoader.load(image, picture, dp(240))
        val title = android.text.SpannableStringBuilder(label).apply {
            setSpan(android.text.style.ForegroundColorSpan(ContextCompat.getColor(this@PlayerActivity, R.color.badge_on)), 0, length, 0)
            if (name.isNotEmpty()) {
                append(" · ")
                val start = length
                append(name)
                setSpan(android.text.style.ForegroundColorSpan(ContextCompat.getColor(this@PlayerActivity, R.color.text)), start - 3, length, 0)
            }
        }
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@PlayerActivity).apply {
                text = title
                typeface = font(R.font.figtree_700)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (meta.isNotEmpty()) addView(TextView(this@PlayerActivity).apply {
                text = meta
                typeface = font(R.font.figtree_500)
                setTextColor(ContextCompat.getColor(this@PlayerActivity, R.color.text_muted))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
            if (story.isNotEmpty()) addView(TextView(this@PlayerActivity).apply {
                text = story
                typeface = font(R.font.figtree_500)
                setTextColor(ContextCompat.getColor(this@PlayerActivity, R.color.text))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                setLineSpacing(2f * d, 1f)
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(if (app.fireplayer.ui.Glass.on) 14 else 10).toFloat()
                setColor(ContextCompat.getColor(this@PlayerActivity,
                    if (app.fireplayer.ui.Glass.on) R.color.details_fill_glass else R.color.details_fill))
                if (app.fireplayer.ui.Glass.on) setStroke(dp(1).coerceAtLeast(1), ContextCompat.getColor(this@PlayerActivity, R.color.details_rim_glass))
            }
            setPadding(dp(10), dp(10), dp(12), dp(10))
            addView(picture, LinearLayout.LayoutParams(dp(120), dp(68)))
            addView(side, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
        }
    }

    /** "2009-03-29" as "Mar 29, 2009"; the text as it came when it isn't a date. */
    private fun prettyDate(iso: String): String? {
        if (iso.isBlank()) return null
        return runCatching {
            val d = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)!!
            java.text.SimpleDateFormat("MMM d, yyyy", Locale.US).format(d)
        }.getOrDefault(iso)
    }

    /** Pairs the show again with the fixes just made, from what the provider last said. */
    private fun rebuildFromFix(then: (() -> Unit)? = null) {
        val g = guide ?: return
        val found = lastFound
        val uri = lastFoundUri
        if (found != null && uri != null && NextEpisode.showKey(uri, found.seriesId) == g.key) {
            buildGuide(uri, found, g.key, then)
            return
        }
        // Shown from the copy kept last time: the provider is asked again.
        val entry = currentEntry() ?: return
        val finder = NextEpisode(applicationContext, headersFromIntent())
        thread(name = "FirePlayerNext", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            val again = runCatching { finder.find(entry.uri, entry.rawTitle, entry.info.title) }.getOrNull() ?: return@thread
            handler.post {
                if (isFinishing) return@post
                lastFound = again
                lastFoundUri = entry.uri
                NextEpisode.showKey(entry.uri, again.seriesId)?.let { buildGuide(entry.uri, again, it, then) }
            }
        }
    }

    // Fix match: the TMDB search, the results, then (for a show) which episode this is.
    private var fixQuery = ""
    private var fixHits: List<Tmdb.Hit> = emptyList()
    private var fixStatus: String? = null
    private var fixShow: Tmdb.Hit? = null
    private var fixSeason = 1
    private var fixNumber = 1
    private var fixRest = true
    private val fixSeasons = HashMap<Int, List<Tmdb.TvEpisode>?>()

    private fun openFixFilm(from: View) {
        val entry = currentEntry() ?: return
        fixQuery = entry.english?.searchQuery ?: entry.info.searchQuery
        openFixSearch(from, shows = false)
    }

    private fun openFixShow(from: View) {
        fixQuery = guide?.searchName ?: currentEntry()?.info?.searchQuery.orEmpty()
        openFixSearch(from, shows = true)
    }

    private fun openFixSearch(from: View, shows: Boolean) {
        fixHits = emptyList()
        fixStatus = "Searching…"
        runFixSearch(shows)
        openPanel(if (shows) "Fix match · Find the show" else "Fix match · Find the film", from) {
            searchBox(fixQuery, "TMDB") {
                SidePanel.prompt(this@PlayerActivity, "Search TMDB", fixQuery) {
                    fixQuery = it.trim()
                    runFixSearch(shows)
                    refresh()
                }
            }
            fixStatus?.let { message(it) }
            fixHits.forEach { hit ->
                resultRow(hit.name, listOfNotNull(hit.year, if (hit.show) "TV show" else "Film").joinToString(" · "), null,
                    poster = hit.poster, withPoster = true) {
                    if (hit.show) pickFixShow(hit, from) else saveFixFilm(hit)
                }
            }
            message("Press OK on the search box to type. FirePlayer starts it with the name your provider gave.")
        }
    }

    private fun runFixSearch(shows: Boolean) {
        val query = fixQuery
        if (query.isEmpty()) {
            fixStatus = "Type the name to search for."
            return
        }
        io.execute {
            val hits = runCatching { tmdb.search(query, shows) }
            handler.post {
                if (isFinishing || query != fixQuery) return@post
                fixHits = hits.getOrNull().orEmpty().take(8)
                fixStatus = when {
                    hits.isFailure -> hits.exceptionOrNull()?.message ?: "Couldn't reach TMDB."
                    fixHits.isEmpty() -> "Nothing on TMDB by that name."
                    else -> null
                }
                if (panel.isOpen) panel.refresh()
            }
        }
    }

    private fun saveFixFilm(hit: Tmdb.Hit) {
        val entry = currentEntry() ?: return
        val portal = NextEpisode.portal(entry.uri) ?: return
        val id = entry.uri.lastPathSegment?.substringBeforeLast('.') ?: return
        matchFixes.setFilm(portal, id, hit.label, hit.id, hit.name, hit.year?.toIntOrNull() ?: 0)
        noteRematch(entry, episode = false)
        panel.close()
        updateTitle()
        showNotice("Saved: ${hit.label}", 2_500)
    }

    /** Step two for a show: which of its episodes this is. Starts on where it's paired now. */
    private fun pickFixShow(hit: Tmdb.Hit, from: View) {
        val entry = currentEntry() ?: return
        val ep = guideEpisode(entry)
        fixShow = hit
        fixSeasons.clear()
        fixSeason = ep?.season ?: entry.info.season ?: 1
        fixNumber = ep?.number ?: entry.info.episode ?: 1
        fixRest = true
        loadFixSeason()
        panel.close()
        openPanel("Fix match · Pick the episode", from) {
            val show = fixShow ?: return@openPanel
            facts(listOfNotNull(
                ep?.let { e ->
                    val own = e.providerTitle.takeIf { it.isNotEmpty() }?.let { " · “$it”" }.orEmpty()
                    "Your provider" to fact("S${e.providerSeason} E${e.providerNumber}$own")
                },
                "Show" to fact(show.label),
            ))
            steppers(listOf(
                SidePanel.Step("Season", fixSeason.toString()) {
                    fixSeason = (fixSeason + it).coerceIn(0, 99)
                    fixNumber = 1
                    loadFixSeason()
                },
                SidePanel.Step("Episode", fixNumber.toString()) {
                    val last = fixSeasons[fixSeason]?.maxOfOrNull { e -> e.number } ?: 999
                    fixNumber = (fixNumber + it).coerceIn(1, last.coerceAtLeast(1))
                },
            ))
            val list = fixSeasons[fixSeason]
            when {
                !fixSeasons.containsKey(fixSeason) -> message("Looking up season $fixSeason…")
                list.isNullOrEmpty() -> message("TMDB has no season $fixSeason for this show.")
                else -> {
                    val picked = list.firstOrNull { it.number == fixNumber }
                    if (picked == null) message("TMDB has no episode $fixNumber in season $fixSeason.")
                    else view(episodeCard(picked.still, "S${picked.season} E${picked.number}", picked.name,
                        listOfNotNull(prettyDate(picked.airDate), picked.runtime.takeIf { it > 0 }?.let { "${it}m" }).joinToString(" · "),
                        picked.overview), top = 4, bottom = 6)
                }
            }
            row("Move the rest of the season too", if (fixRest) "On" else "Off") {
                fixRest = !fixRest
                refresh()
            }
            if (ep != null && fixRest) {
                val delta = fixNumber - ep.providerNumber
                val how = when {
                    delta == 0 && fixSeason == ep.providerSeason -> "keep their numbers"
                    delta == 0 -> "move to season $fixSeason"
                    else -> "move ${kotlin.math.abs(delta)} " + (if (delta < 0) "back" else "on") +
                        if (fixSeason != ep.providerSeason) ", into season $fixSeason" else ""
                }
                message("Your provider's later episodes of season ${ep.providerSeason} $how. Next episode and Episodes follow this.")
            }
            buttons("Save match", "Cancel", { saveFixShow() }, { close() })
        }
    }

    private fun loadFixSeason() {
        val show = fixShow ?: return
        val season = fixSeason
        if (fixSeasons.containsKey(season)) return
        io.execute {
            val list = runCatching { tmdb.season(show.id, season) }.getOrNull()
            handler.post {
                if (isFinishing || fixShow !== show) return@post
                fixSeasons[season] = list
                if (panel.isOpen) panel.refresh()
            }
        }
    }

    private fun saveFixShow() {
        val show = fixShow ?: return
        val g = guide ?: return
        val entry = currentEntry() ?: return
        val ep = guideEpisode(entry) ?: return
        matchFixes.place(g.key, show.id, ep.id, ep.providerSeason, ep.providerNumber, fixSeason, fixNumber, fixRest)
        panel.close()
        showNotice("Saving the match…", 2_500)
        rebuildFromFix {
            showNotice("Match saved", 2_000)
            noteRematch(entry, episode = true)
        }
    }

    // Find subtitles (OpenSubtitles)

    private var findResults: List<SubtitleRanker.Ranked> = emptyList()
    // The result loaded last (its file id), so "Try the next one" can load the one after it.
    private var findLoaded = 0L
    // Its subtitle track, to tell whether it's still the one shown.
    private var findLoadedSubId: String? = null
    // The show's TMDB id from its guide, so the search asks for exactly that show.
    private var findTmdb = 0
    private var findStatus: String? = null
    private var findQuery: String? = null
    // The video the search is set up for, and for a show, which episode.
    private var findFor: String? = null
    private var findIsShow = false
    private var findSeason = 1
    private var findEpisode = 1
    // What TMDB made of the name, shown above the results.
    private var findMatch: String? = null

    private fun openFindPanel(from: View) {
        val entry = entries.getOrNull(player?.currentMediaItemIndex ?: 0) ?: return
        if (findFor != entry.key) {
            // Another video (the next episode, say): start again from its own name and number.
            findFor = entry.key
            findQuery = entry.info.searchQuery
            findIsShow = entry.info.episode != null || NextEpisode.isEpisodeLink(entry.uri)
            findSeason = entry.info.season ?: 1
            findEpisode = entry.info.episode ?: 1
            findResults = emptyList()
            findStatus = null
            findMatch = null
            findLoaded = 0L
            findLoadedSubId = null
            findTmdb = 0
            // The show as matched for its guide: TMDB's name, numbers and id.
            val g = guide
            guideEpisode(entry)?.let { e ->
                if (g != null && g.tmdbId > 0) {
                    findQuery = g.searchName
                    findIsShow = true
                    findSeason = e.season
                    findEpisode = e.number
                    findTmdb = g.tmdbId
                }
            }
        }
        openPanel("Find subtitles", from) {
            if (!openSubtitles.isConfigured) {
                message("Add your free OpenSubtitles API key in Settings to search for subtitles.")
                row("Open Settings") { panel.close(); openSettingsPanel(from) }
                return@openPanel
            }
            row("Search for", findQuery) {
                SidePanel.prompt(this@PlayerActivity, "Search for", findQuery.orEmpty()) {
                    findQuery = it
                    refresh()
                }
            }
            if (findIsShow) {
                stepper("Season", findSeason.toString()) { findSeason = (findSeason + it).coerceIn(0, 99) }
                stepper("Episode", findEpisode.toString()) { findEpisode = (findEpisode + it).coerceIn(0, 999) }
            }
            val languages = SEARCH_LANGUAGES
            choice("Language", languages.map { settingsPanels.languageName(it) }, languages.indexOf(searchLanguage())) {
                prefs.subtitleSearchLanguage = languages[it]
            }
            row("Search") { runSearch(entry) }
            openSubtitles.quota?.let { message(it) }
            findMatch?.let { message(it) }
            findStatus?.let { message(it) }
            // After one was loaded: the next best, in one press, for when that one is out of time.
            val loadedAt = findResults.indexOfFirst { it.result.fileId == findLoaded }
            findResults.getOrNull(loadedAt + 1)?.takeIf { loadedAt >= 0 }?.let { next ->
                row("Try the next one", "${next.score}%") { downloadSubtitle(next) }
            }
            val shownId = selectedFormat(C.TRACK_TYPE_TEXT)?.id
            val loadedId = findLoadedSubId
            val inUse = loadedId != null && shownId != null &&
                (shownId == loadedId || shownId.endsWith(":$loadedId") || loadedId.endsWith(":$shownId"))
            // Downloaded for this video before: last used first, ready without searching again.
            val saved = SavedSubtitles.forVideo(this@PlayerActivity, entry.key)
            val shownFile = savedFileOf(shownId)
            if (saved.isNotEmpty()) {
                header("Downloaded before")
                saved.forEach { s ->
                    val on = s.file == shownFile
                    val lang = s.language?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH) }.orEmpty()
                    val notes = listOf(if (on) "In use" else usedAgo(s.lastUsed), lang).filter { it.isNotEmpty() }.joinToString(" · ")
                    resultRow(readableRelease(s.release), notes, null, selected = on) { useSaved(entry, s) }
                }
                if (findResults.isNotEmpty()) header("Search results")
            }
            val savedIds = saved.mapTo(HashSet()) { it.fileId }
            findResults.filter { it.result.fileId !in savedIds }.forEach { ranked ->
                val r = ranked.result
                val lang = Locale.forLanguageTag(r.language).getDisplayLanguage(Locale.ENGLISH)
                val loaded = r.fileId == findLoaded
                // First line: why it should fit. Second: the facts (frame rate, downloads, language).
                val (why, facts) = ranked.notes.partition { n -> HIGHLIGHTS.any { n.startsWith(it) } }
                val first = listOfNotNull(
                    if (loaded) (if (inUse) "In use" else "Loaded") else null,
                    if (ranked.best) "Best match" else null,
                ) + why
                val second = facts + listOfNotNull(lang.takeIf { it.isNotEmpty() })
                val notes = listOf(first, second).filter { it.isNotEmpty() }.joinToString("\n") { it.joinToString(" · ") }
                resultRow(readableRelease(r.release), notes, "${ranked.score}%", selected = loaded && inUse) {
                    downloadSubtitle(ranked)
                }
            }
        }
    }

    /** Puts a subtitle downloaded before back on screen. */
    private fun useSaved(entry: Entry, s: SavedSubtitles.Saved) {
        val id = savedFiles.entries.firstOrNull { it.value == s.file }?.key
        val option = id?.let { i -> trackOptions(C.TRACK_TYPE_TEXT).firstOrNull { sameId(it, i) } }
        if (option != null) {
            chooseTrack(option)
            handler.postDelayed({ refreshLabels(); if (panel.isOpen) panel.refresh() }, 150)
        } else {
            addExternalSubtitle(Uri.fromFile(File(SavedSubtitles.dir(this), s.file)), s.language)
            pendingExternalSubId?.let { savedFiles[it] = s.file }
            SavedSubtitles.use(this, entry.key, s.file)
            panel.close()
        }
    }

    /** "Used today", "Used yesterday", "Used 5 days ago". */
    private fun usedAgo(at: Long): String {
        val days = ((System.currentTimeMillis() - at) / 86_400_000L).toInt()
        return when {
            days <= 0 -> "Used today"
            days == 1 -> "Used yesterday"
            else -> "Used $days days ago"
        }
    }

    /** The release group a downloaded subtitle was made for ("KILLERS"), shown beside it in Subtitles. */
    private fun savedRelease(file: String): String? {
        val entry = currentEntry() ?: return null
        val release = SavedSubtitles.forVideo(this, entry.key).firstOrNull { it.file == file }?.release ?: return null
        val group = release.substringBeforeLast(".srt").substringAfterLast('-').trim()
        return group.takeIf { it.length in 2..14 && it != release } ?: "Downloaded"
    }

    /** "Breaking.Bad.S02E03.720p.BluRay.x264-DEMAND" → "Breaking Bad S02E03 720p BluRay x264-DEMAND", to wrap nicely. */
    private fun readableRelease(name: String): String =
        name.substringBeforeLast(".srt")
            .replace(Regex("(\\d)\\.(\\d)(?!\\d)"), "$1\u0000$2") // 5.1, 2.0 stay as they are
            .replace('_', ' ').replace('.', ' ').replace('\u0000', '.')
            .replace(Regex("\\s+"), " ").trim()

    /** English or Arabic: the last one chosen, else the default subtitle language when it's one of them. */
    private fun searchLanguage(): String =
        prefs.subtitleSearchLanguage.ifEmpty { prefs.subtitleLanguage }.takeIf { it in SEARCH_LANGUAGES } ?: "en"

    private fun runSearch(entry: Entry) {
        val query = findQuery.orEmpty().trim()
        if (query.isEmpty()) {
            findStatus = "Type the name of the film or show to search for."
            panel.refresh()
            return
        }
        val language = searchLanguage()
        val show = findIsShow
        val season = findSeason.takeIf { show }
        val episode = findEpisode.takeIf { show }
        // The year tells a film from its remake, while the name is still the one the video came with.
        val year = if (query == entry.info.searchQuery) {
            YEAR_IN_TITLE.find(entry.info.title)?.groupValues?.get(1)?.toIntOrNull()
        } else null
        findStatus = "Searching…"
        findResults = emptyList()
        findMatch = null
        panel.refresh()
        val guideTmdb = findTmdb.takeIf { it > 0 && show && query == guide?.searchName }
        val hashable = prefs.seekPreviews && entry.uri.scheme?.startsWith("http") == true
        val headers = headersFromIntent()
        // The release the file is: the provider's file name, else the name it came with.
        val release = listOfNotNull(entry.uri.lastPathSegment, entry.rawTitle).firstOrNull { name ->
            name.substringBeforeLast('.').any { !it.isDigit() }
        } ?: entry.rawTitle.orEmpty()
        val fps = videoFps()
        io.execute {
            // With a TMDB key, the search asks for the exact film or show instead of anything with that name.
            // The show as its guide matched it, else asked of TMDB by name.
            val known = guideTmdb?.let { Tmdb.Match(it, query, null) }
            val match = if (known != null) Result.success(known) else if (tmdb.isConfigured) runCatching { tmdb.find(query, year, show) } else null
            val found = match?.getOrNull()
            // The file's fingerprint marks subtitles made for it; read only where a second connection is fine.
            val hash = if (hashable) MovieHash.of(entry.uri, headers) else null
            val result = runCatching {
                SubtitleRanker.rank(
                    openSubtitles.search(query, language, season, episode, found?.id, show, hash),
                    SubtitleRanker.Video(release, fps, season, episode),
                )
            }
            handler.post {
                if (isFinishing || player == null) return@post
                findMatch = when {
                    known != null -> "Matched on TMDB: ${guide?.show ?: query}"
                    match == null -> null
                    found != null -> "Matched on TMDB: ${found.label}"
                    match.isFailure -> "Couldn't ask TMDB (check its key in Settings), so the search went by name."
                    else -> "Not found on TMDB, so the search went by name."
                }
                result.onSuccess {
                    findResults = it.take(30)
                    findStatus = if (it.isNotEmpty()) null else {
                        val what = if (show) "season $season, episode $episode" else "this film"
                        "Nothing found in ${settingsPanels.languageName(language)} for $what. Check the name and numbers."
                    }
                }.onFailure { findStatus = it.message ?: "Search failed." }
                if (panel.isOpen) panel.refresh()
            }
        }
    }

    private fun downloadSubtitle(ranked: SubtitleRanker.Ranked) {
        val r = ranked.result
        findStatus = "Downloading…"
        panel.refresh()
        val dir = downloadDir().apply { mkdirs() }
        val length = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        io.execute {
            val result = runCatching {
                openSubtitles.download(r, dir).also { SubtitleRanker.stretch(it, ranked.stretch) }.let { got ->
                    // Named by its OpenSubtitles file too, so two downloads with one name don't overwrite each other.
                    val named = File(dir, "${r.fileId}-${got.name}")
                    if (got.name.startsWith("${r.fileId}-") || !got.renameTo(named)) got else named
                }
            }
            val last = result.getOrNull()?.let { SubtitleRanker.lastTime(it) } ?: 0L
            handler.post {
                if (isFinishing || player == null) return@post
                result.onSuccess { file ->
                    findStatus = null
                    findLoaded = r.fileId
                    addExternalSubtitle(Uri.fromFile(file), r.language)
                    findLoadedSubId = pendingExternalSubId
                    // Kept for this video, to be there next time.
                    pendingExternalSubId?.let { savedFiles[it] = file.name }
                    currentEntry()?.let { SavedSubtitles.add(this, it.key, file, r.language, r.release, r.fileId) }
                    panel.close()
                    // Made for a longer or shorter cut (extended, or with a recap): the next one may fit better.
                    if (length > 10 * 60_000L && last > 0 && (last > length + 90_000L || last < length * 7 / 10)) {
                        showNotice("These subtitles seem made for a different cut. Try the next one in Find subtitles.", 5_000)
                    }
                }.onFailure {
                    findStatus = it.message ?: "Download failed."
                    if (panel.isOpen) panel.refresh()
                }
            }
        }
    }

    /** Adds a subtitle file to the current video and switches to it. */
    private fun addExternalSubtitle(uri: Uri, language: String?) {
        val p = player ?: return
        val index = p.currentMediaItemIndex
        val entry = entries.getOrNull(index) ?: return
        val config = subtitleConfig(uri, null, language)
        entry.subtitles += config
        pendingExternalSubId = config.id
        pendingAudioId = selectedFormat(C.TRACK_TYPE_AUDIO)?.id
        captureState()
        p.setMediaItems(entries.map { mediaItem(it) }, index, startPositionMs)
        p.prepare()
    }

    // ---------------------------------------------------------------------
    // Subtitle look and display size

    private fun applySubtitleStyle() {
        styler.keepEmbedded = prefs.keepAssStyling
        styler.apply(screenSubtitles, presets.active())
        updatePictureReading()
        showTextCues()
    }

    /** The picture subtitle track that's shown as text, if any: the only one worth reading ahead. */
    private fun readablePictureTrack(): Format? = selectedFormat(C.TRACK_TYPE_TEXT)?.takeIf { f ->
        isPictureFormat(f) && pictureText.canRead(f.language)
    }

    private fun isPictureFormat(f: Format): Boolean =
        listOf(f.sampleMimeType, f.codecs, f.containerMimeType).any { it in PictureText.PICTURE_FORMATS }

    private fun updatePictureReading() {
        if (!::pictureText.isInitialized) return
        pictureText.showTrack(if (styler.keepEmbedded) null else readablePictureTrack()?.id)
    }

    private fun applyDisplayMode() {
        val p = player
        val size = p?.videoSize
        val aspect = if (size == null || size.height == 0) 0f else size.width * size.pixelWidthHeightRatio / size.height
        val scale = videoFrame.scaleX
        videoFrame.scaleX = 1f
        videoFrame.scaleY = 1f
        when (displayMode) {
            0 -> { videoFrame.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; videoFrame.setAspectRatio(aspect) }
            1 -> { videoFrame.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; videoFrame.setAspectRatio(aspect) }
            2 -> {
                videoFrame.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                videoFrame.setAspectRatio(aspect)
                videoFrame.scaleX = 1.15f
                videoFrame.scaleY = 1.15f
            }
            3 -> { videoFrame.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL; videoFrame.setAspectRatio(aspect) }
            4 -> { videoFrame.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; videoFrame.setAspectRatio(16f / 9f) }
            5 -> { videoFrame.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; videoFrame.setAspectRatio(4f / 3f) }
            6 -> { videoFrame.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; videoFrame.setAspectRatio(21f / 9f) }
        }
        // Zoom grows the picture without a new layout, which the layout listener wouldn't see.
        if (videoFrame.scaleX != scale) backdrop?.frameChanged()
    }

    // ---------------------------------------------------------------------

    companion object {
        // The player screen opened last.
        private var shown: WeakReference<PlayerActivity>? = null
        private const val STATE_PROCESS = "fireplayer.process"
        /** Different in each run of the app: a saved state with another mark comes from a run Fire OS has closed. */
        private val PROCESS_MARK = SystemClock.elapsedRealtimeNanos()

        val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "webm", "wmv", "flv", "mpg", "mpeg")
        val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "ttml", "dfxp")
        val DISPLAY_MODES = listOf("Normal", "Fill screen", "Zoom", "Stretch", "16:9", "4:3", "21:9")

        const val EXT_PREFIX = "ext"
        /** Subtitles we added ourselves ("ext3", or "1:ext3" once merged into the video). */
        val EXT_ID = Regex("^(\\d+:)?$EXT_PREFIX\\d+$")

        /** How long before the end of an episode its end card comes up, and how long it counts down. */
        // The languages the subtitle search offers.
        val SEARCH_LANGUAGES = listOf("en", "ar")
        val YEAR_IN_TITLE = Regex("""\(((?:19|20)\d{2})\)""")
        // The title server notes a show's episodes just after listing them.
        const val EPISODES_NOTED_MS = 1_500L
        // How often an episode's real numbers are asked for, and how long before asking again.
        const val REAL_TRIES = 2
        const val REAL_AGAIN_MS = 20_000L
        // How long the film waits for the TV to change refresh rate.
        const val DISPLAY_SWITCH_PAUSE_MS = 2_000L
        /** How long a film that froze is paused before it's played again (the viewer did the same, and it went on). */
        const val FREEZE_PAUSE_MS = 1_000L
        private const val TAG = "FirePlayer"
        /** Away longer than this, a player left behind closes instead of coming back (see onStart). */
        const val AWAY_CLOSES_MS = 30 * 60_000L
        /** How long the decoder list may take before the player says the decoder service is stuck. */
        private const val DECODER_STUCK_MS = 10_000L
        const val POST_PLAY_LEAD_MS = 20_000L
        // How long the skip button waits, untouched, before it slips away.
        const val SKIP_SHOWN_MS = 8_000L
        // Looked up again for auto sync's new offsets at most this often.
        const val SEGMENTS_AGAIN_MS = 15_000L
        private const val AUDIO_KEPT = 300
        private val FOCUS_EASE = DecelerateInterpolator(1.4f)
        private const val LOADING = "Loading from TMDB…"
        private val YEAR_IN_NAME = Regex("""\((\d{4})\)""")
        // "Breaking Bad (2008)": the name and the year.
        private val SHOW_YEAR = Regex("""^(.*?)\s*\((\d{4})\)$""")
        // A next-episode look-up starts with this much of the film loaded, after this long at most, and no later than
        // this long before the end card.
        const val LOOKUP_AHEAD_MS = 60_000L
        const val LOOKUP_WAIT_MS = 3 * 60_000L
        const val LOOKUP_BEFORE_END_MS = 4 * 60_000L
        // The least the film keeps behind where it is (on TVs with little memory); a jump back further loads again.
        const val KEPT_BEHIND_MS = 5_000L
        // Two film drops just after the preview connected, this close together, mean the server cuts one off.
        const val CUT_OFF_PAIR_MS = 60_000L
        const val POST_PLAY_COUNT_MS = 10_000L
        // Find subtitles: the notes that say why a result should fit, shown on their own line.
        private val HIGHLIGHTS = listOf("Made for this exact file", "Same release", "Same source")
        /** How often what's playing is noted for Continue Watching while it plays. */
        const val WATCH_NOTE_EVERY_MS = 60_000L
        /** How long a note about previews stays up. */
        const val NOTE_MS = 4_000L
        /** How long a picture subtitle that wasn't read ahead is kept back while it's read. */
        const val PICTURE_READ_WAIT_MS = 700L
        const val MAX_RETRIES = 4
        const val RETRY_WINDOW_MS = 75_000L
        const val MAX_DEVICE_RETRIES = 3
        const val DEVICE_RETRY_WINDOW_MS = 60_000L
        /** How long after a refresh-rate switch the TV's sound output may still be coming back. */
        const val DISPLAY_SETTLE_MS = 3_000L
        /**
         * The TV leaving the film's mode this soon after it was asked for is asked again only when this is up: a TV coming
         * on can drop its HDMI connection again a few seconds later, and Fire OS puts its own mode back each time.
         */
        const val REMATCH_AGAIN_MS = 10_000L
        /** The most times one video asks for its rate again after the TV changed mode under it. */
        const val REMATCH_MAX = 3
        /** File missing, no permission and cleartext blocked won't fix themselves. */
        val NOT_RETRIED = setOf(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        )
        const val MIN_AUDIO_DELAY_US = -2_000_000L
        const val MAX_AUDIO_DELAY_US = 450_000L

        /** [long] keeps the hours ("0:05:12") so every time on an hour-plus video has the same shape. */
        fun formatTime(ms: Long, long: Boolean = false): String {
            val total = (ms / 1000).coerceAtLeast(0)
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0 || long) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }

        fun formatDelay(us: Long): String {
            val ms = us / 1000
            return if (ms == 0L) "0 ms" else (if (ms > 0) "+" else "−") + "${abs(ms)} ms"
        }
    }
}
