package app.fireplayer

import android.content.Intent
import android.graphics.Paint
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
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import app.fireplayer.data.Prefs
import app.fireplayer.player.Delays
import app.fireplayer.player.FpsMeter
import app.fireplayer.player.FrameRateMatcher
import app.fireplayer.player.PlayerFactory
import app.fireplayer.player.TitleInfo
import app.fireplayer.subs.FontImport
import app.fireplayer.subs.OpenSubtitles
import app.fireplayer.subs.PictureText
import app.fireplayer.subs.SubtitlePresets
import app.fireplayer.subs.SubtitleStyler
import app.fireplayer.ui.FireDialog
import app.fireplayer.ui.SeekBarView
import app.fireplayer.ui.SeekPreview
import app.fireplayer.ui.SettingsPanels
import app.fireplayer.ui.SidePanel
import io.github.peerless2012.ass.media.AssHandler
import java.io.File
import java.io.IOException
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity(), Player.Listener {

    /** One video to play, with any subtitles we were handed or added. */
    private class Entry(
        val uri: Uri,
        val rawTitle: String?,
        val mimeType: String? = null,
        val subtitles: MutableList<MediaItem.SubtitleConfiguration> = mutableListOf(),
    ) {
        val info: TitleInfo by lazy { TitleInfo.parse(rawTitle, uri) }
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

    private lateinit var prefs: Prefs
    private lateinit var presets: SubtitlePresets
    private lateinit var styler: SubtitleStyler
    private lateinit var pictureText: PictureText
    private lateinit var settingsPanels: SettingsPanels
    private lateinit var panel: SidePanel
    private lateinit var frameRateMatcher: FrameRateMatcher
    private lateinit var openSubtitles: OpenSubtitles

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val delays = Delays()

    private var player: ExoPlayer? = null
    private var assHandler: AssHandler? = null

    // Views
    private lateinit var root: ViewGroup
    private lateinit var videoFrame: AspectRatioFrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var frameSubtitles: SubtitleView
    private lateinit var screenSubtitles: SubtitleView
    private lateinit var buffering: ProgressBar
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
    private lateinit var focusSink: View

    private val moreItems = LinkedHashMap<String, MoreItem>()

    // Playlist
    private val entries = ArrayList<Entry>()
    private var startIndex = 0
    private var startPositionMs = C.TIME_UNSET
    private var savedTrackParams: TrackSelectionParameters? = null
    private var savedPlayWhenReady = true
    private var pendingExternalSubId: String? = null
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
    private var displayMode = 0

    // Seeking
    private var seeking = false
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
    private var retryPending = false
    private val retryRunnable = object : Runnable {
        override fun run() {
            // A refresh-rate switch that began after this was scheduled (the next video's) has to settle first.
            val settle = displaySwitchAt + DISPLAY_SETTLE_MS - SystemClock.elapsedRealtime()
            if (settle > 0) {
                handler.postDelayed(this, settle)
                return
            }
            retryPending = false
            player?.prepare()
        }
    }
    // Sound or a decoder the device couldn't start, counted apart: these can fail again right after
    // playback resumes, so only half a minute of playing (or another video) gives them back.
    private var deviceRetries = 0
    private var firstDeviceErrorAt = 0L
    private val deviceRetriesBack = Runnable { deviceRetries = 0 }
    // When the TV was last asked to change refresh rate; its sound output drops out while it switches.
    private var displaySwitchAt = 0L

    private var resumeDialogShowing = false
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
    private val readingTimeout = Runnable { showTextCues() }

    // Time labels keep a fixed width so the bar never changes length
    private var longTimes: Boolean? = null

    // ---------------------------------------------------------------------
    // Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        prefs = Prefs(this)
        presets = SubtitlePresets(prefs)
        styler = SubtitleStyler(this)
        pictureText = PictureText { handler.post { if (readingPictures != null) showTextCues() } }
        settingsPanels = SettingsPanels(this, prefs, presets)
        frameRateMatcher = FrameRateMatcher(this)
        openSubtitles = OpenSubtitles(prefs)
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, 0)
        settingsPanels.onFontChanged = {
            styler.reloadImportedFont()
            applySubtitleStyle()
        }
        bindViews()
        panel = SidePanel(this)
        buildMoreRow()
        displayMode = prefs.displayMode

        if (!readIntent(intent)) {
            finish()
            return
        }
        applySubtitleStyle()
    }

    override fun onStart() {
        super.onStart()
        if (entries.isNotEmpty() && player == null) initPlayer()
        updateOverlay()
        handler.post(clockTick)
    }

    override fun onStop() {
        super.onStop()
        commitSeek()
        seekPreview.release()
        notice = false
        retryPending = false
        savePosition()
        releasePlayer()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        frameRateMatcher.reset()
        pictureText.release()
        io.shutdownNow()
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
        }

        // Subtitles handed to us.
        @Suppress("DEPRECATION")
        val subs = extras?.getParcelableArray("subs")?.mapNotNull { it as? Uri }.orEmpty()
        val subNames = extras?.getStringArray("subs.name")
        subs.forEachIndexed { i, uri ->
            val name = subNames?.getOrNull(i)
            entries[startIndex].subtitles += subtitleConfig(uri, name, guessLanguage(name))
        }
        entries.forEach { it.subtitles += localSubtitles(it.uri) }

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
        val built = PlayerFactory.build(
            context = this,
            delays = delays,
            passthrough = prefs.passthrough,
            useAss = prefs.keepAssStyling,
            assSubtitleView = frameSubtitles,
            headers = headersFromIntent(),
            pictureText = pictureText,
        )
        val p = built.player
        player = p
        builtWithAss = prefs.keepAssStyling
        assHandler = built.assHandler
        p.setVideoSurfaceView(surfaceView)
        p.addListener(this)
        p.addAnalyticsListener(filmLoadErrors)
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
                Prefs.Resume.ASK -> askResume = true
                Prefs.Resume.NEVER -> Unit
            }
        }
        playingIndex = index
        deviceRetries = 0
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
        refreshLabels()
        // A rebuilt player has no subtitles yet; puts the style example back if its window is open.
        showTextCues()
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
        p.removeListener(this)
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

    private fun askToResume(positionMs: Long) {
        resumeDialogShowing = true
        FireDialog.ask(
            this,
            "Resume from ${formatTime(positionMs)}?",
            listOf<Pair<String, () -> Unit>>(
                "Resume" to {
                    resumeDialogShowing = false
                    player?.seekTo(positionMs)
                    player?.play()
                },
                "Start over" to {
                    resumeDialogShowing = false
                    player?.play()
                },
            ),
            // Back means "never mind": carry on from where you were rather than losing the spot.
            onDismiss = {
                resumeDialogShowing = false
                player?.seekTo(positionMs)
                player?.play()
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
    }

    /** Lets TiviMate know where we stopped (MX Player result contract). */
    private fun finishWithResult() {
        val p = player
        val result = Intent("com.mxtech.intent.result.VIEW")
        if (p != null) {
            result.putExtra("position", p.currentPosition.toInt())
            result.putExtra("duration", p.duration.coerceAtLeast(0).toInt())
        }
        savePosition()
        // Silence at once; the player itself is released only after TiviMate is back on screen.
        p?.pause()
        result.putExtra("end_by", if (completed) "playback_completion" else "user")
        setResult(RESULT_OK, result)
        // Straight back to the app that opened us, without a lingering empty task.
        if (isTaskRoot) finishAndRemoveTask() else finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    // ---------------------------------------------------------------------
    // Player.Listener

    override fun onPlaybackStateChanged(state: Int) {
        handler.removeCallbacks(showBuffering)
        if (state == Player.STATE_BUFFERING) {
            handler.postDelayed(showBuffering, 400)
        } else if (!(state == Player.STATE_IDLE && retryPending)) {
            buffering.visibility = View.GONE
        }
        if (state == Player.STATE_READY) {
            errorBox.visibility = View.GONE
            retries = 0
            retryPending = false
            handler.removeCallbacks(deviceRetriesBack)
            handler.postDelayed(deviceRetriesBack, DEVICE_RETRY_WINDOW_MS / 2)
        }
        if (state == Player.STATE_ENDED && seeking && seekOffsetMs < 0) {
            // The film ended while a jump back in it was being lined up: make that jump instead of closing.
            commitSeek()
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
        updatePlayButton()
        root.keepScreenOn = isPlaying
        if (isPlaying) scheduleHide() else handler.removeCallbacks(hideControlsRunnable)
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (sleepAtEnd && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && seeking && seekOffsetMs < 0) {
            // Same for a jump back right at the end with sleep set for the end of the video: it still stops there later.
            commitSeek()
            player?.play()
            return
        }
        if (sleepAtEnd && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
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
            entries.getOrNull(playingIndex)?.let { prefs.savePosition(it.key, 0L) }
        }
        // Any other jump being lined up was for the video that was playing, not this one.
        if (seeking) cancelSeek()
        playingIndex = now
        matchedFpsForIndex = -1
        deviceRetries = 0
        measuredFps = 0f
        fpsMeter.reset()
        updateTitle()
        refreshLabels()
    }

    override fun onTracksChanged(tracks: Tracks) {
        pendingExternalSubId?.let { id ->
            val option = trackOptions(C.TRACK_TYPE_TEXT).firstOrNull { sameId(it, id) }
            if (option != null) {
                pendingExternalSubId = null
                selectTrack(option)
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
        refreshLabels()
        updateChips()
        matchFrameRate()
        // "Keep embedded styling" was switched before an ASS track showed up (still loading, or added later).
        if (stylingNeedsRebuild()) {
            handler.removeCallbacks(rebuildForAss)
            handler.post(rebuildForAss)
        }
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        applyDisplayMode()
        updateChips()
    }

    override fun onCues(cueGroup: CueGroup) {
        val (pictures, text) = cueGroup.cues.partition { it.bitmap != null }
        lastPictureCues = pictures
        lastTextCues = text
        showTextCues()
        // A new subtitle can have more lines, or (a picture) sit elsewhere: move it clear of the controls at once.
        if (controlsShown || seeking || notice) liftSubtitles(animate = false)
    }

    /**
     * Draws the current subtitles in the chosen style. While the Subtitle style window is open
     * and the video is paused with nothing on screen, example text shows the style instead.
     */
    private fun showTextCues() {
        readPictures()
        // libass draws its own subtitles; picture subtitles are images. Example text would sit on top of them.
        val otherShowing = lastPictureCues.isNotEmpty() || libassShowing()
        val preview = stylePreviewing && lastTextCues.isEmpty() && !otherShowing && player?.playWhenReady != true
        screenSubtitles.setCues(if (preview) styler.sample() else styler.normalize(shownTextCues))
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
        if (lastPictureCues.isEmpty() || styler.keepEmbedded ||
            !pictureText.canRead(selectedFormat(C.TRACK_TYPE_TEXT)?.language)
        ) {
            readingPictures = null
            return
        }
        val texts = lastPictureCues.map { cue -> cue.bitmap?.let { pictureText.textOf(it) } }
        if (texts.any { it == null }) {
            val now = SystemClock.uptimeMillis()
            if (readingPictures !== lastPictureCues) {
                readingPictures = lastPictureCues
                readingUntil = now + PICTURE_READ_WAIT_MS
                for (cue in lastPictureCues) cue.bitmap?.let { pictureText.prepare(it, soon = true) }
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
        val text = read.joinToString("\n") { texts[it]!! }
        shownTextCues = lastTextCues + Cue.Builder().setText(text).build()
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
        val permanent = status == 404 || status == 410
        val network = error.errorCode in 2000..2999 && error.errorCode !in NOT_RETRIED && !permanent
        if (retries == 0) firstErrorAt = now
        // Quiet retries stop after a while, so a server that's down shows an error instead of a spinner forever.
        if (network && retries < MAX_RETRIES && now - firstErrorAt < RETRY_WINDOW_MS) {
            retries++
            val step = if (status != null && status in 400..499) 3_000L else 1_000L
            retryQuietly(step * retries)
            return
        }
        retries = 0
        deviceRetries = 0
        retryPending = false
        buffering.visibility = View.GONE
        // The error box needs OK, so a settings window can't be left in front of it.
        if (panel.isOpen) panel.close()
        errorText.text = errorMessage(error, status)
        // The number helps if the error has to be reported; the name of the code would be noise here.
        errorHint.text = "Press OK to try again  ·  Error ${error.errorCode}"
        errorBox.visibility = View.VISIBLE
        hideControls()
    }

    /** Tries again after [delayMs] with the spinner up instead of an error. */
    private fun retryQuietly(delayMs: Long) {
        retryPending = true
        handler.removeCallbacks(showBuffering)
        buffering.visibility = View.VISIBLE
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, delayMs)
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

    private val showBuffering = Runnable { buffering.visibility = View.VISIBLE }

    // The film's connection failing just after the preview asked for video is the server allowing one connection
    // (or cutting the older one off). The film comes first: previews stop.
    private val filmLoadErrors = object : AnalyticsListener {
        override fun onLoadError(
            eventTime: AnalyticsListener.EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean,
        ) {
            if (mediaLoadData.trackType != C.TRACK_TYPE_TEXT && seekPreview.requestedWithin(8_000)) seekPreview.serverRefused()
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
        seekBubble = findViewById(R.id.seek_bubble)
        seekPreview = SeekPreview(
            card = findViewById(R.id.preview_card),
            frame = findViewById(R.id.preview_frame),
            texture = findViewById(R.id.preview_texture),
            dim = findViewById(R.id.preview_dim),
            time = findViewById(R.id.preview_time),
            format = { formatTime(it, longTimes == true) },
            create = { PlayerFactory.buildPreview(this, headersFromIntent()) },
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
        btnNext.setOnClickListener { savePosition(); player?.seekToNextMediaItem() }
        btnSubs.setOnClickListener { openSubtitlesPanel(btnSubs) }
        btnAudio.setOnClickListener { openAudioPanel(btnAudio) }
        focusEffect(btnPlay, 1.08f)
        focusEffect(btnRestart, 1.08f, findViewById(R.id.restart_tooltip))
        focusEffect(btnNext, 1.08f, findViewById(R.id.next_tooltip))
        focusEffect(btnSubs, 1.04f)
        focusEffect(btnAudio, 1.04f)
        seekBar.onThumbMoved = { placeBubble() }
        timeRemaining.gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }

    /** Selected buttons grow slightly, so moving around feels smooth rather than jumpy. */
    private fun focusEffect(button: View, scale: Float, tooltip: View? = null) {
        button.setOnFocusChangeListener { v, hasFocus ->
            val s = if (hasFocus) scale else 1f
            v.animate().scaleX(s).scaleY(s).setDuration(140).start()
            tooltip?.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
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
        add("find", R.drawable.ic_search, "Find subtitles") { openFindPanel(it) }
        add("sleep", R.drawable.ic_sleep, "Sleep timer") { openSleepPanel(it) }
        add("settings", R.drawable.ic_settings, "Settings") { openSettingsPanel(it) }
    }

    private fun updateTitle() {
        val entry = entries.getOrNull(player?.currentMediaItemIndex ?: startIndex) ?: return
        titleView.text = entry.info.title
        subtitleView.text = entry.info.subtitle
        subtitleView.visibility = if (entry.info.subtitle != null) View.VISIBLE else View.GONE
        val hasNext = player?.hasNextMediaItem() == true
        // Hiding the focused Next button would send focus to the time bar; keep it on the buttons.
        if (!hasNext && btnNext.hasFocus()) btnPlay.requestFocus()
        nextGroup.visibility = if (hasNext) View.VISIBLE else View.GONE
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
        moreItems["find"]?.value?.text = "Search"
        moreItems["sleep"]?.value?.text = sleepLabel()
        moreItems["settings"]?.value?.text = "Settings"
    }

    private fun updateChips() {
        val p = player ?: return
        val video = selectedFormat(C.TRACK_TYPE_VIDEO)
        val audio = selectedFormat(C.TRACK_TYPE_AUDIO)
        val w = p.videoSize.width.takeIf { it > 0 } ?: video?.width ?: 0
        val h = p.videoSize.height.takeIf { it > 0 } ?: video?.height ?: 0
        setChip(chipResolution, if (w > 0 && h > 0) "$w×$h" else null)
        val fps = videoFps()
        setChip(chipFps, if (fps > 0) fpsLabel(fps) else null)
        val channels = audio?.channelCount ?: Format.NO_VALUE
        setChip(
            chipAudio,
            when (channels) {
                1 -> "Mono"
                2 -> "Stereo"
                6 -> "5.1"
                8 -> "7.1"
                in 3..16 -> "$channels ch"
                else -> null
            },
        )
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
        val switching = frameRateMatcher.match(fps)
        if (switching) displaySwitchAt = SystemClock.elapsedRealtime()
        if (switching && wasPlaying) {
            // The TV blanks for a moment while it switches; don't play through it.
            p.pause()
            handler.removeCallbacks(resumeAfterSwitch)
            handler.postDelayed(resumeAfterSwitch, 2_000)
        }
    }

    private val resumeAfterSwitch = Runnable { player?.play() }

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
        if (target.parent === moreRow) showMoreRow(target) else showMainRow(focusRow = false)
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

    private fun showMoreRow(focus: View = moreItems.values.first().view) {
        moreShown = true
        mainRow.visibility = View.GONE
        arrowDown.visibility = View.GONE
        moreRow.visibility = View.VISIBLE
        arrowUp.visibility = View.VISIBLE
        focus.requestFocus()
    }

    private fun showMainRow(focusRow: Boolean = true) {
        moreShown = false
        moreRow.visibility = View.GONE
        arrowUp.visibility = View.GONE
        mainRow.visibility = View.VISIBLE
        arrowDown.visibility = View.VISIBLE
        if (focusRow) btnPlay.requestFocus()
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
        seekPreview.hide()
        handler.removeCallbacks(commitSeekRunnable)
        player?.seekTo(seekTarget())
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
                    when (code) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> player?.play()
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> player?.pause()
                        else -> togglePlay()
                    }
                    if (!controlsShown && !panel.isOpen) showControls()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { savePosition(); player?.seekToNextMediaItem(); return true }
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
        if (seekBar.isFocused) {
            // The selected time bar: left / right move along it, OK jumps, down goes back to the buttons.
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { nudge(prefs.dpadStepSec, -1, event); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { nudge(prefs.dpadStepSec, 1, event); return true }
                KeyEvent.KEYCODE_DPAD_UP -> return true
                KeyEvent.KEYCODE_DPAD_DOWN -> { btnPlay.requestFocus(); return true }
            }
            if (isOk) {
                if (event.repeatCount == 0) togglePlay()
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
     * libass and plain text, and picture subtitles are only read as text (ahead of time) by a player without libass.
     */
    private fun stylingNeedsRebuild(): Boolean = prefs.keepAssStyling != builtWithAss &&
        (hasSsaTrack() || (!prefs.keepAssStyling && hasTextTrack { it in PictureText.PICTURE_FORMATS }))

    private fun selectTrack(o: TrackOption) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(o.group.type, false)
            .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.trackIndex))
            .build()
    }

    private fun subtitlesOff() {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    private fun openSubtitlesPanel(from: View) = openPanel("Subtitles", from) {
        val options = trackOptions(C.TRACK_TYPE_TEXT)
        val embedded = options.filter { !it.external }
        val other = options.filter { it.external }
        val anySelected = options.any { it.selected }
        header("In this video")
        if (embedded.isEmpty()) message("No subtitles inside this video.")
        embedded.forEach { o ->
            row(o.label, checked = o.selected) { selectTrack(o); handler.postDelayed({ refreshLabels(); refresh() }, 150) }
        }
        row("Off", checked = !anySelected) { subtitlesOff(); handler.postDelayed({ refreshLabels(); refresh() }, 150) }
        header("Other subtitles")
        other.forEach { o ->
            row(o.label, checked = o.selected) { selectTrack(o); handler.postDelayed({ refreshLabels(); refresh() }, 150) }
        }
        row("Search online") { panel.close(); openFindPanel(from) }
        spacer(6)
        stepper("Delay", formatDelay(delays.subtitleUs)) { dir ->
            delays.subtitleUs += dir * 100_000L
            refreshLabels()
        }
    }

    private fun openSubtitleDelayPanel(from: View) = openPanel("Subtitle delay", from) {
        message("Left makes subtitles earlier, right makes them later.")
        stepper("Delay", formatDelay(delays.subtitleUs)) { dir ->
            delays.subtitleUs += dir * 100_000L
            refreshLabels()
        }
        row("Reset") { delays.subtitleUs = 0; refreshLabels(); refresh() }
    }

    private fun openAudioPanel(from: View) = openPanel("Audio", from) {
        val options = trackOptions(C.TRACK_TYPE_AUDIO)
        if (options.isEmpty()) message("No audio tracks.")
        options.forEach { o ->
            row(o.label, checked = o.selected) { selectTrack(o); handler.postDelayed({ refreshLabels(); refresh() }, 150) }
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
        refreshLabels()
    }

    private fun sleepLabel(): String = when {
        sleepAtEnd -> "End"
        sleepEndsAt > 0 -> "${((sleepEndsAt - SystemClock.elapsedRealtime()) / 60_000 + 1).coerceAtLeast(1)} min"
        else -> "Off"
    }

    private fun openSettingsPanel(from: View) {
        val passthrough = prefs.passthrough
        // Rebuild once the window closes if a playback setting changed.
        openPanel("Settings", from, onClosed = {
            if (prefs.passthrough != passthrough) rebuildPlayer()
            if (prefs.matchFrameRate) { matchedFpsForIndex = -1; matchFrameRate() } else frameRateMatcher.reset()
            refreshLabels()
        }) { settingsPanels.renderSettings(this) }
    }

    // Find subtitles (OpenSubtitles)

    private var findResults: List<OpenSubtitles.Result> = emptyList()
    private var findStatus: String? = null
    private var findQuery: String? = null

    private fun openFindPanel(from: View) {
        val entry = entries.getOrNull(player?.currentMediaItemIndex ?: 0) ?: return
        if (findQuery == null) findQuery = entry.info.searchQuery
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
            row("Search") { runSearch(entry) }
            findStatus?.let { message(it) }
            findResults.forEach { r ->
                val lang = Locale.forLanguageTag(r.language).getDisplayLanguage(Locale.ENGLISH)
                row(r.release, lang, twoLines = true) { downloadSubtitle(r) }
            }
        }
    }

    private fun runSearch(entry: Entry) {
        val query = findQuery.orEmpty()
        val language = prefs.subtitleLanguage.ifEmpty { "en" }
        findStatus = "Searching…"
        findResults = emptyList()
        panel.refresh()
        io.execute {
            val result = runCatching { openSubtitles.search(query, language, entry.info.season, entry.info.episode) }
            handler.post {
                if (isFinishing || player == null) return@post
                result.onSuccess {
                    findResults = it.take(30)
                    findStatus = if (it.isEmpty()) "Nothing found. Try a shorter name." else null
                }.onFailure { findStatus = it.message ?: "Search failed." }
                if (panel.isOpen) panel.refresh()
            }
        }
    }

    private fun downloadSubtitle(r: OpenSubtitles.Result) {
        findStatus = "Downloading…"
        panel.refresh()
        val dir = File(cacheDir, "subs").apply { mkdirs() }
        io.execute {
            val result = runCatching { openSubtitles.download(r, dir) }
            handler.post {
                if (isFinishing || player == null) return@post
                result.onSuccess { file ->
                    findStatus = null
                    addExternalSubtitle(Uri.fromFile(file), r.language)
                    panel.close()
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
        showTextCues()
    }

    private fun applyDisplayMode() {
        val p = player
        val size = p?.videoSize
        val aspect = if (size == null || size.height == 0) 0f else size.width * size.pixelWidthHeightRatio / size.height
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
    }

    // ---------------------------------------------------------------------

    companion object {
        val VIDEO_EXTENSIONS = setOf("mkv", "mp4", "m4v", "avi", "mov", "ts", "m2ts", "webm", "wmv", "flv", "mpg", "mpeg")
        val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "ttml", "dfxp")
        val DISPLAY_MODES = listOf("Normal", "Fill screen", "Zoom", "Stretch", "16:9", "4:3", "21:9")

        const val EXT_PREFIX = "ext"
        /** Subtitles we added ourselves ("ext3", or "1:ext3" once merged into the video). */
        val EXT_ID = Regex("^(\\d+:)?$EXT_PREFIX\\d+$")

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
