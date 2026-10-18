package app.fireplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import app.fireplayer.data.Prefs
import app.fireplayer.player.Delays
import app.fireplayer.player.FrameRateMatcher
import app.fireplayer.player.PlayerFactory
import app.fireplayer.player.TitleInfo
import app.fireplayer.subs.OpenSubtitles
import app.fireplayer.subs.SubtitlePresets
import app.fireplayer.subs.SubtitleStyler
import app.fireplayer.ui.SeekBarView
import app.fireplayer.ui.SettingsPanels
import app.fireplayer.ui.SidePanel
import io.github.peerless2012.ass.media.AssHandler
import java.io.File
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity(), Player.Listener {

    /** One video to play, with any subtitles we were handed or added. */
    private class Entry(
        val uri: Uri,
        val rawTitle: String?,
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
    private var matchedFpsForIndex = -1
    private var displayMode = 0
    private var speed = 1f

    // Seeking
    private var seeking = false
    private var seekBaseMs = 0L
    private var seekOffsetMs = 0L
    private var lastNudgeTime = 0L
    private var consumeBackUp = false

    // Sleep timer
    private var sleepEndsAt = 0L
    private var sleepAtEnd = false

    // ---------------------------------------------------------------------
    // Lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        prefs = Prefs(this)
        presets = SubtitlePresets(prefs)
        styler = SubtitleStyler(this)
        settingsPanels = SettingsPanels(this, prefs, presets)
        frameRateMatcher = FrameRateMatcher(this)
        openSubtitles = OpenSubtitles(prefs)
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
        handler.post(clockTick)
    }

    override fun onStop() {
        super.onStop()
        savePosition()
        releasePlayer()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        super.onDestroy()
        frameRateMatcher.reset()
        io.shutdownNow()
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
        if (!list.isNullOrEmpty()) {
            list.forEachIndexed { i, uri -> entries += Entry(uri, names?.getOrNull(i)) }
            startIndex = list.indexOf(data).coerceAtLeast(0)
            if (title != null && list.indexOf(data) >= 0) {
                entries[startIndex] = Entry(data, title)
            }
        } else {
            entries += Entry(data, title)
            entries.addAll(siblingFiles(data))
        }

        // Subtitles handed to us.
        @Suppress("DEPRECATION")
        val subs = extras?.getParcelableArray("subs")?.mapNotNull { it as? Uri }.orEmpty()
        val subNames = extras?.getStringArray("subs.name")
        subs.forEachIndexed { i, uri ->
            entries[startIndex].subtitles += subtitleConfig(uri, subNames?.getOrNull(i), null)
        }
        entries.forEach { it.subtitles += localSubtitles(it.uri) }

        val position = extras?.getInt("position", 0) ?: 0
        if (position > 0) startPositionMs = position.toLong()
        return true
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
            .setId("ext$externalCounter")
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
        if (path.endsWith(".m3u8")) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (path.endsWith(".mpd")) builder.setMimeType(MimeTypes.APPLICATION_MPD)
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
        )
        val p = built.player
        player = p
        assHandler = built.assHandler
        p.setVideoSurfaceView(surfaceView)
        p.addListener(this)
        p.setPlaybackSpeed(speed)
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
        p.setMediaItems(items, index, if (position == C.TIME_UNSET) 0L else position)
        p.prepare()
        if (askResume) {
            p.playWhenReady = false
            askToResume(prefs.savedPosition(entry.key))
        } else {
            p.playWhenReady = savedPlayWhenReady
        }
        updateTitle()
        updatePlayButton()
        refreshLabels()
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
        startPositionMs = p.currentPosition
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
        var chosen = false
        AlertDialog.Builder(this)
            .setMessage("Resume from ${formatTime(positionMs)}?")
            .setPositiveButton("Resume") { _, _ ->
                chosen = true
                player?.seekTo(positionMs)
                player?.play()
            }
            .setNegativeButton("Start over") { _, _ ->
                chosen = true
                player?.play()
            }
            .setOnDismissListener { if (!chosen) player?.play() }
            .show()
    }

    private fun savePosition() {
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
        result.putExtra("end_by", if (completed) "playback_completion" else "user")
        setResult(RESULT_OK, result)
        savePosition()
        finish()
    }

    // ---------------------------------------------------------------------
    // Player.Listener

    override fun onPlaybackStateChanged(state: Int) {
        handler.removeCallbacks(showBuffering)
        if (state == Player.STATE_BUFFERING) {
            handler.postDelayed(showBuffering, 400)
        } else {
            buffering.visibility = View.GONE
        }
        if (state == Player.STATE_READY) errorBox.visibility = View.GONE
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
        if (sleepAtEnd && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
            finishWithResult()
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        matchedFpsForIndex = -1
        updateTitle()
        refreshLabels()
    }

    override fun onTracksChanged(tracks: Tracks) {
        pendingExternalSubId?.let { id ->
            val option = trackOptions(C.TRACK_TYPE_TEXT).firstOrNull {
                it.group.getTrackFormat(it.trackIndex).id?.contains(id) == true
            }
            if (option != null) {
                pendingExternalSubId = null
                selectTrack(option)
            }
        }
        refreshLabels()
        updateChips()
        matchFrameRate()
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        applyDisplayMode()
        updateChips()
    }

    override fun onCues(cueGroup: CueGroup) {
        val (pictures, text) = cueGroup.cues.partition { it.bitmap != null }
        frameSubtitles.setCues(pictures)
        screenSubtitles.setCues(text.map { styler.normalize(it) })
    }

    override fun onPlayerError(error: PlaybackException) {
        val p = player ?: return
        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            p.seekToDefaultPosition()
            p.prepare()
            return
        }
        buffering.visibility = View.GONE
        errorText.text = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Can't reach the video. Check your connection."
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused the video."
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This video format isn't supported on this device."
            else -> "Playback stopped: ${error.errorCodeName}"
        }
        errorBox.visibility = View.VISIBLE
        hideControls()
    }

    private val showBuffering = Runnable { buffering.visibility = View.VISIBLE }

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
        errorText = findViewById(R.id.error_text)

        // Holds focus while the controls are hidden.
        focusSink = View(this).apply { isFocusable = true }
        root.addView(focusSink, 1, 1)
        focusSink.requestFocus()

        // The design uses the D-pad for frame-precise seeking, so the controls never auto-scroll.
        frameSubtitles.setApplyEmbeddedStyles(true)

        btnPlay.setOnClickListener { togglePlay() }
        btnRestart.setOnClickListener { player?.seekTo(0); player?.play() }
        btnNext.setOnClickListener { player?.seekToNextMediaItem() }
        btnSubs.setOnClickListener { openSubtitlesPanel(btnSubs) }
        btnAudio.setOnClickListener { openAudioPanel(btnAudio) }
        tooltipOnFocus(btnRestart, findViewById(R.id.restart_tooltip))
        tooltipOnFocus(btnNext, findViewById(R.id.next_tooltip))
    }

    private fun tooltipOnFocus(button: View, tooltip: View) {
        button.setOnFocusChangeListener { _, hasFocus ->
            tooltip.visibility = if (hasFocus) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun buildMoreRow() {
        fun add(key: String, icon: Int, label: String, open: (View) -> Unit) {
            val v = LayoutInflater.from(this).inflate(R.layout.item_more, moreRow, false)
            v.findViewById<ImageView>(R.id.more_icon).setImageResource(icon)
            v.contentDescription = label
            val value = v.findViewById<TextView>(R.id.more_value)
            v.setOnClickListener { open(v) }
            moreRow.addView(v)
            moreItems[key] = MoreItem(v, value) { open(v) }
        }
        add("subs", R.drawable.ic_cc, "Subtitles") { openSubtitlesPanel(it) }
        add("subDelay", R.drawable.ic_sub_delay, "Subtitle delay") { openSubtitleDelayPanel(it) }
        add("style", R.drawable.ic_sub_style, "Subtitle style") { openStylePanel(it) }
        add("audio", R.drawable.ic_audio, "Audio") { openAudioPanel(it) }
        add("audioDelay", R.drawable.ic_audio_delay, "Audio delay") { openAudioDelayPanel(it) }
        add("display", R.drawable.ic_display, "Display size") { openDisplayPanel(it) }
        add("speed", R.drawable.ic_speed, "Speed") { openSpeedPanel(it) }
        add("find", R.drawable.ic_search, "Find subtitles") { openFindPanel(it) }
        add("sleep", R.drawable.ic_sleep, "Sleep timer") { openSleepPanel(it) }
        add("settings", R.drawable.ic_settings, "Settings") { openSettingsPanel(it) }
    }

    private fun updateTitle() {
        val entry = entries.getOrNull(player?.currentMediaItemIndex ?: startIndex) ?: return
        titleView.text = entry.info.title
        subtitleView.text = entry.info.subtitle
        subtitleView.visibility = if (entry.info.subtitle != null) View.VISIBLE else View.GONE
        nextGroup.visibility = if (player?.hasNextMediaItem() == true) View.VISIBLE else View.GONE
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
        moreItems["speed"]?.value?.text = formatSpeed(speed)
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
        val fps = video?.frameRate ?: Format.NO_VALUE.toFloat()
        setChip(chipFps, if (fps > 0) "${fps.roundToInt()} FPS" else null)
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

    private fun matchFrameRate() {
        val p = player ?: return
        if (!prefs.matchFrameRate || matchedFpsForIndex == p.currentMediaItemIndex) return
        val fps = selectedFormat(C.TRACK_TYPE_VIDEO)?.frameRate ?: return
        if (fps <= 0) return
        matchedFpsForIndex = p.currentMediaItemIndex
        val wasPlaying = p.playWhenReady
        if (frameRateMatcher.match(fps) && wasPlaying) {
            // The TV blanks for a moment while it switches; don't play through it.
            p.pause()
            handler.postDelayed({ player?.play() }, 2_000)
        }
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
        val bottomVisible = controlsShown || seeking
        scrimTop.visibility = if (controlsShown) View.VISIBLE else View.GONE
        topBar.visibility = if (controlsShown) View.VISIBLE else View.GONE
        scrimBottom.visibility = if (bottomVisible) View.VISIBLE else View.GONE
        bottomBlock.visibility = if (bottomVisible) View.VISIBLE else View.GONE
        // While seeking without controls, keep the same layout so the bar doesn't move.
        buttonsArea.visibility = if (controlsShown) View.VISIBLE else View.INVISIBLE
        chips.visibility = if (controlsShown && !seeking) View.VISIBLE else View.INVISIBLE
        seekBubble.visibility = if (seeking) View.VISIBLE else View.INVISIBLE
        handler.removeCallbacks(progressTick)
        if (bottomVisible) handler.post(progressTick)
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
            handler.postDelayed(this, 500)
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
            handler.postDelayed(this, 15_000)
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

        val total = formatTime(duration)
        val text = SpannableStringBuilder(formatTime(position)).append("  /  ")
        val start = text.length - 4
        text.append(total)
        text.setSpan(
            ForegroundColorSpan(ContextCompat.getColor(this, R.color.text_muted)),
            start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        timeElapsed.text = text
        timeRemaining.text = "-" + formatTime((duration - position).coerceAtLeast(0))
        if (seeking) positionBubble()
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        p.playWhenReady = !p.playWhenReady
        updatePlayButton()
    }

    // ---------------------------------------------------------------------
    // Seeking: presses add up into one jump, shown as a small "+4:00" card.

    private fun nudge(stepSec: Int, direction: Int, event: KeyEvent) {
        val p = player ?: return
        if (p.isCurrentMediaItemLive || p.duration == C.TIME_UNSET) return
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
        // Don't let the offset run past either end.
        seekOffsetMs = seekOffsetMs.coerceIn(-seekBaseMs, (p.duration - seekBaseMs - 1_000).coerceAtLeast(0))
        val sign = if (seekOffsetMs < 0) "−" else "+"
        seekBubble.text = sign + formatTime(abs(seekOffsetMs))
        updateOverlay()
        updateProgress()
        handler.removeCallbacks(commitSeekRunnable)
        handler.postDelayed(commitSeekRunnable, 900)
        if (controlsShown) scheduleHide()
    }

    private fun seekTarget(): Long = (seekBaseMs + seekOffsetMs).coerceAtLeast(0)

    private val commitSeekRunnable = Runnable { commitSeek() }

    private fun commitSeek() {
        if (!seeking) return
        handler.removeCallbacks(commitSeekRunnable)
        player?.seekTo(seekTarget())
        seeking = false
        updateOverlay()
        updateProgress()
    }

    private fun cancelSeek() {
        handler.removeCallbacks(commitSeekRunnable)
        seeking = false
        updateOverlay()
        updateProgress()
    }

    /** Keeps the "+4:00" card centred over the thumb on the time bar. */
    private fun positionBubble() {
        seekBubble.post {
            val layer = seekBubble.parent as View
            val x = seekRow.left + seekBar.left + seekBar.thumbX() - seekBubble.width / 2f
            seekBubble.translationX = x.coerceIn(0f, (layer.width - seekBubble.width).toFloat().coerceAtLeast(0f))
        }
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

        when (code) {
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (!panel.isOpen) nudge(prefs.ffStepSec, 1, event); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (!panel.isOpen) nudge(prefs.ffStepSec, -1, event); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event.repeatCount == 0) {
                    when (code) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> player?.play()
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> player?.pause()
                        else -> togglePlay()
                    }
                    if (!controlsShown && !panel.isOpen) showControls()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { player?.seekToNextMediaItem(); return true }
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
            return handled
        }

        val isOk = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (errorBox.visibility == View.VISIBLE && isOk) {
            errorBox.visibility = View.GONE
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
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> {
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
        when (code) {
            KeyEvent.KEYCODE_BACK -> {
                consumeBackUp = true
                hideControls()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (!moreShown) showMoreRow()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (moreShown) showMainRow()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------------
    // Settings windows

    private fun openPanel(title: String, from: View, render: SidePanel.() -> Unit) {
        hideControls()
        panel.open(title, onClose = { showControls(from) }, render = render)
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
                raw += TrackOption(g, i, trackLabel(f, type), f.id?.contains("ext") == true, g.isTrackSelected(i))
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
            if (label != null && !label.equals(name, true) && label.length <= 24 && !label.contains("ext")) {
                extras += label
            }
        }
        return if (extras.isEmpty()) name else "$name (${extras.joinToString(", ")})"
    }

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
            delays.audioUs += dir * 50_000L
            refreshLabels()
        }
    }

    private fun openAudioDelayPanel(from: View) = openPanel("Audio delay", from) {
        message("Left makes the sound earlier, right makes it later.")
        stepper("Delay", formatDelay(delays.audioUs)) { dir ->
            delays.audioUs += dir * 50_000L
            refreshLabels()
        }
        row("Reset") { delays.audioUs = 0; refreshLabels(); refresh() }
    }

    private fun openStylePanel(from: View) = openPanel("Subtitle style", from) {
        settingsPanels.renderSubtitleStyle(
            this,
            onStyleChanged = { applySubtitleStyle(); refreshLabels() },
            onAssChanged = { rebuildPlayer() },
        )
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

    private fun openSpeedPanel(from: View) = openPanel("Speed", from) {
        SPEEDS.forEach { s ->
            row(formatSpeed(s), checked = s == speed) {
                speed = s
                player?.setPlaybackSpeed(s)
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
        hideControls()
        // Rebuild once the window closes if a playback setting changed.
        panel.open("Settings", onClose = {
            if (prefs.passthrough != passthrough) rebuildPlayer()
            if (prefs.matchFrameRate) { matchedFpsForIndex = -1; matchFrameRate() } else frameRateMatcher.reset()
            refreshLabels()
            showControls(from)
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
                row(r.release, lang) { downloadSubtitle(r) }
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
        captureState()
        p.setMediaItems(entries.map { mediaItem(it) }, index, startPositionMs)
        p.prepare()
    }

    // ---------------------------------------------------------------------
    // Subtitle look and display size

    private fun applySubtitleStyle() {
        styler.apply(screenSubtitles, presets.active())
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
        val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

        fun formatTime(ms: Long): String {
            val total = (ms / 1000).coerceAtLeast(0)
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }

        fun formatDelay(us: Long): String {
            val ms = us / 1000
            return if (ms == 0L) "0 ms" else (if (ms > 0) "+" else "−") + "${abs(ms)} ms"
        }

        fun formatSpeed(s: Float): String =
            (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()) + "×"
    }
}
