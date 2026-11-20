package app.fireplayer.playback

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import app.fireplayer.data.Prefs
import app.fireplayer.live.ui.LivePrefs
import app.fireplayer.player.Delays
import app.fireplayer.player.FilmRoom
import app.fireplayer.player.LiveBuffer
import app.fireplayer.player.PausedRoom
import app.fireplayer.player.PlayerFactory

/**
 * The app's in-process live player: one [ExoPlayer] for the live channel, shown on whichever [SurfaceView] the
 * screen hands it (the guide's miniature and full screen are the same surface resized, so neither switch touches
 * the stream). Lives for the process; the screen tells it when it starts and stops ([onScreenStarted] /
 * [onScreenStopped]) so no stream stays open in the background.
 *
 * A channel that fails, or that its provider drops, is tried again by itself for as long as the viewer stays on it
 * ([LiveReconnect]: after about 1, 2, 4 and 8 seconds, then every 30), the last frame staying on screen meanwhile
 * ([Status.reconnecting]); only a picture this TV can't decode gives up with a message. Each recovery is logged, and
 * so is how long each channel took to show ([LiveTiming]); never a link or a name.
 *
 * Everything here runs on the main thread; Media3 does its loading and decoding on its own playback threads.
 * VOD keeps its own player in PlayerActivity, untouched.
 */
@OptIn(UnstableApi::class)
class PlayerHost private constructor(context: Context) {

    /** What the screen shows about live playback. */
    data class Status(
        val key: String?,
        val buffering: Boolean,
        val playing: Boolean,
        /** Shown when the channel can't be played at all (a picture this TV can't decode); null while all is well. */
        val error: String?,
        val video: Format?,
        val audio: Format?,
        val videoSize: VideoSize,
        val captions: List<Track>,
        val audioTracks: List<Track>,
        /** The viewer paused the channel. */
        val paused: Boolean = false,
        /** Playing (or paused) behind the live edge: Go live is offered until it's back at live. */
        val behindLive: Boolean = false,
        /** The channel failed or was dropped and is being tried again (until frames play again). */
        val reconnecting: Boolean = false,
        /** Why, and how often it's tried, once the quick tries are used up (to show while [reconnecting]). */
        val waiting: String? = null,
        /**
         * The provider says the channel isn't there or isn't allowed ([LiveRefusal]): what to say, at once and in
         * place of "Reconnecting...", while it's tried again slowly; null otherwise.
         */
        val refused: String? = null,
    )

    data class Track(val groupIndex: Int, val trackIndex: Int, val label: String, val selected: Boolean)

    interface Listener {
        fun onLiveStatus(status: Status)
        /** The captions to show now (empty when off or between lines). */
        fun onLiveCues(cues: List<Cue>) {}
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<Listener>()
    private var player: ExoPlayer? = null
    private var builtHeaders: Map<String, String>? = null
    /** The Live TV buffer [player] was built with. */
    private var builtBuffer: LiveBuffer? = null
    /** [player]'s buffer rules, for the stall wait, the memory ceiling and the log. */
    private var control: LiveLoadControl? = null
    /** The memory step Fire OS last asked for (see [LiveCushion.stepFor]); back to 0 with each new stream. */
    private var memoryStep = 0
    /** The kind of stream playing (HLS, TS...), for the log. */
    private var kind = "stream"
    /** The player's own estimate of the connection's speed (bits a second; 0 not known yet), for the log. */
    private var bitrateEstimate = 0L
    /** The last playback state seen, to tell a stall (ready, then buffering) from a start. */
    private var lastState = Player.STATE_IDLE
    /** A jump the app asked for (a new stream, Go live, a reconnect) is under way: its buffering isn't a stall. */
    private var jumping = false
    /** When the stall under way began (elapsedRealtime; 0: none). */
    private var stalledAt = 0L
    private val stallWatch = LiveStallWatch()
    private var surface: SurfaceView? = null
    private val reconnect = LiveReconnect()
    private val refusal = LiveRefusal()
    /** Between a failure and frames playing again. */
    private var reconnecting = false
    private var reconnectingSince = 0L
    private var waitReason: String? = null
    private var error: String? = null
    private val timing = LiveTiming()
    /** When the key that asked for the channel being opened was pressed (uptime ms; 0: not known). */
    private var pressedAt = 0L
    /** Captions the viewer turned on stay on from channel to channel (the kind chosen, CC1 or digital, first). */
    private var captionsOn = false
    private var captionsMime: String? = null
    private var cues: List<Cue> = emptyList()
    /** The captions on screen now (for a screen that starts showing them mid-line). */
    val currentCues: List<Cue> get() = cues
    /** Read by the live player's video renderer on the playback thread (see [PlayerFactory.buildLive]). */
    private val delays = Delays()
    /** The channel the audio delay was set on: another channel starts at 0 ms, as each film does. */
    private var delayKey: String? = null

    /**
     * Audio delay (full screen's Audio delay, microseconds; above zero the sound comes later), as the film player's.
     * Changes apply while playing; kept while the same channel plays, back to 0 ms on another channel.
     */
    var audioDelayUs: Long
        get() = delays.audioUs
        set(v) { delays.audioUs = v }

    private val engine = object : LiveSession.Engine {
        override fun open(source: LiveSession.Source) = openStream(source)
        override fun close() = closeStream()
    }

    val session = LiveSession(engine, budget)

    init {
        // Fire OS short of memory while a channel plays: the buffer steps down (and gives memory back) rather than the
        // app being killed. Only the levels sent to the app in front count; a stopped screen has closed its stream.
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level > ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) return
                val step = LiveCushion.stepFor(level)
                if (step <= memoryStep) return
                memoryStep = step
                val c = control ?: return
                c.squeeze(step)
                Log.i(TAG, "Live buffer: memory low (level $level), held to ${c.cushion.capBytes() shr 20} MiB, ${c.allocatedBytes shr 20} MiB loaded")
            }
            override fun onConfigurationChanged(newConfig: Configuration) {}
            @Deprecated("Deprecated in Java") override fun onLowMemory() {}
        })
    }

    val playingKey: String? get() = session.playingKey
    val currentKey: String? get() = session.source?.key

    fun addListener(l: Listener) {
        listeners += l
        l.onLiveStatus(status())
        l.onLiveCues(cues)
    }

    fun removeListener(l: Listener) { listeners -= l }

    /**
     * Plays [source] (or keeps playing it when it already is). [pressedAt] is when the key that asked for it was
     * pressed (KeyEvent.eventTime; 0 when there was none), for the log of how long the channel took to show.
     */
    fun tune(source: LiveSession.Source, pressedAt: Long = 0L): LiveSession.Tune {
        this.pressedAt = pressedAt
        var result = session.tune(source)
        if (result == LiveSession.Tune.AlreadyPlaying && streamDead()) {
            // The channel is still ours (its lease is held) but the stream gave up: try it again from the start.
            openStream(source)
            result = LiveSession.Tune.Started
        }
        this.pressedAt = 0L
        publish()
        return result
    }

    /**
     * Live TV is opening and a channel is about to be tuned (the one last watched, once it's been read): the player
     * is built now, while that's read, rather than when the channel arrives. It holds no decoder until a stream is
     * opened, and is let go again if the screen stops, or a film starts, with nothing tuned.
     */
    fun warm() {
        if (player == null && session.state == LiveSession.State.IDLE) playerFor(emptyMap())
    }

    private fun streamDead(): Boolean {
        val p = player ?: return true
        return error != null || p.playbackState == Player.STATE_IDLE
    }

    /** Shows live video on [view] (the guide's surface). Safe to call again with the same view. */
    fun attach(view: SurfaceView) {
        if (surface === view) return
        surface?.let { player?.clearVideoSurfaceView(it) }
        surface = view
        player?.setVideoSurfaceView(view)
    }

    fun detach(view: SurfaceView) {
        if (surface !== view) return
        player?.clearVideoSurfaceView(view)
        surface = null
    }

    fun onScreenStarted(): Boolean {
        screenOn = true
        val resumed = session.resume()
        publish()
        return resumed
    }

    /**
     * The screen showing live stopped. [owner] is that screen's surface: when another screen has attached its own
     * surface since (a new activity instance), this stale stop is ignored rather than stopping the new one's stream.
     */
    fun onScreenStopped(owner: Any? = null) {
        if (owner != null && surface != null && owner !== surface) return
        screenOn = false
        val had = player != null
        session.suspend()
        // A player built ahead ([warm]) that never got a channel.
        if (player != null && session.state != LiveSession.State.PLAYING) closeStream()
        if (had) collect()
        publish()
    }

    /** The viewer closed live playback, or a film is starting. */
    fun stop() {
        val had = player != null
        session.stop()
        if (player != null) closeStream()
        if (had) collect()
        publish()
    }

    /**
     * A channel's player was let go of for good (stopped, not changed for another): its buffer is back with the TV
     * now, not whenever Android next collects. Not from [closeStream] itself, or every channel change would collect.
     */
    private fun collect() = FilmRoom.made(app, forFilm = false)

    // ---- Playlist and EPG updates (live/SyncGuard.kt) ---------------------------------------------------------------

    /** The live screen is started (between [onScreenStarted] and [onScreenStopped]). */
    var screenOn = false
        private set

    /**
     * An update is about to ask this channel's provider: the stream is closed and its connection lease returned, the
     * channel remembered (as when the screen stops). Returns its key, or null when nothing was playing.
     */
    fun letGoForUpdate(): String? {
        if (session.state != LiveSession.State.PLAYING) return null
        val key = session.source?.key
        session.suspend()
        publish()
        return key
    }

    /** The update is over: the remembered channel plays again, from live. True when it did. */
    fun backAfterUpdate(): Boolean {
        val back = session.resume()
        publish()
        return back
    }

    /**
     * The live picture's sound off ([muted]; its Volume on Home and Search), kept for the players built after (another
     * channel's may be a new one); Live TV's own pages always play with it on.
     */
    var muted = false
        private set

    fun setMuted(muted: Boolean) {
        this.muted = muted
        player?.volume = if (muted) 0f else 1f
    }

    /** Paused by the viewer (not by the app going to the background, which closes the stream). */
    var paused = false
        private set

    /** Behind the live edge since a pause, until Go live or a reconnect brings it back. */
    private var behind = false

    /**
     * A channel paused for long was let go of ([parkPaused]): no player, the channel still the one playing and shown
     * as paused. Play or Go live opens it again, at live.
     */
    private var parked = false
    /** When the channel was paused (elapsedRealtime). */
    private var pausedAt = 0L

    /** Pauses or carries on. A paused channel falls behind live; it keeps its place while the buffer lasts. */
    fun togglePause() {
        if (carryOnParked()) return
        val p = player ?: return
        if (session.playingKey == null) return
        paused = !paused
        if (paused) behind = true
        p.playWhenReady = !paused
        watchPause()
        publish()
    }

    /** The paused channel's clock starts or ends, from every place that pauses it or carries on. */
    private fun watchPause() {
        main.removeCallbacks(parkPaused)
        if (!paused || player == null) return
        pausedAt = SystemClock.elapsedRealtime()
        main.postDelayed(parkPaused, PausedRoom.LIVE_MS)
    }

    /**
     * Paused for [PausedRoom.LIVE_MS]: a plain stream can't be held past its buffer (it stopped arriving once that
     * was full), so the buffer (up to 64 MiB), the decoder and the connection were kept for nothing. Its player is let
     * go of; the channel stays the one playing, shown as paused, and Play opens it again at live. A channel that can
     * be wound back (a live HLS/DASH window) keeps its place and is left alone.
     */
    private val parkPaused = Runnable {
        val p = player ?: return@Runnable
        if (!paused || session.playingKey == null) return@Runnable
        val seekable = p.isCurrentMediaItemDynamic && p.isCurrentMediaItemSeekable
        if (!PausedRoom.liveSuspends(SystemClock.elapsedRealtime() - pausedAt, seekable)) return@Runnable
        val held = control?.allocatedBytes ?: 0
        closeStream()
        paused = true
        parked = true
        collect()
        Log.i(TAG, "Live: paused ${PausedRoom.LIVE_MS / 60_000} min, player let go with ${held shr 20} MiB loaded; Play opens the channel at live")
        publish()
    }

    /** Play or Go live on a parked channel: it opens again, at live. True when it was parked. */
    private fun carryOnParked(): Boolean {
        if (!parked) return false
        parked = false
        paused = false
        val source = session.source
        if (source != null && session.playingKey != null) openStream(source)
        publish()
        return true
    }

    /**
     * Back to the live edge: a live HLS/DASH window seeks to its default position; a plain stream (MPEG-TS), which
     * can't seek, is opened again, which is where the provider is now.
     */
    fun goLive() {
        if (carryOnParked()) return
        val p = player ?: return
        val source = session.source ?: return
        if (session.playingKey == null) return
        paused = false
        behind = false
        if (p.isCurrentMediaItemDynamic && p.isCurrentMediaItemSeekable) {
            jumping = true
            p.seekToDefaultPosition()
            p.playWhenReady = true
        } else {
            openStream(source)
        }
        publish()
    }

    /**
     * Live TV buffer was changed (Settings): the channel playing opens again with the new size, one short reload that
     * keeps its sound and captions as picked, its audio delay and its connection. A channel paused, or playing behind
     * live since a pause, keeps its place; like a stopped one, it takes the new size the next time it's opened.
     */
    fun bufferChanged() {
        val source = session.source ?: return
        val p = player ?: return
        if (session.playingKey == null || paused || stillBehind(p)) return
        if (builtBuffer == LivePrefs(app).liveBuffer) return
        // The new player's tracks are new objects: the ones picked now are found again among them (onTracksChanged).
        pickAgainKey = source.key
        pickAgainAudio = selectedFormat(p, C.TRACK_TYPE_AUDIO)
        pickAgainCaption = if (captionsOn) selectedFormat(p, C.TRACK_TYPE_TEXT) else null
        // Straight to the stream, not through the session: the channel's connection is already counted.
        openStream(source)
        publish()
    }

    /** The sound and captions playing before Live TV buffer reopened the channel, to pick again in its new tracks. */
    private var pickAgainKey: String? = null
    private var pickAgainAudio: Format? = null
    private var pickAgainCaption: Format? = null

    private fun selectedFormat(p: ExoPlayer, type: Int): Format? {
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) if (g.isTrackSelected(i)) return g.getTrackFormat(i)
        }
        return null
    }

    /**
     * The reopened channel's tracks: the sound and captions picked before are picked again (the same track, not just
     * the first of its kind). True when captions were picked here. Each is let go once the tracks of its kind are in.
     */
    private fun pickAgain(p: ExoPlayer, tracks: Tracks): Boolean {
        var captions = false
        val b = p.trackSelectionParameters.buildUpon()
        var changed = false
        for (type in intArrayOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT)) {
            val wanted = (if (type == C.TRACK_TYPE_AUDIO) pickAgainAudio else pickAgainCaption) ?: continue
            val groups = tracks.groups.filter { it.type == type }
            if (groups.isEmpty()) continue
            if (type == C.TRACK_TYPE_AUDIO) pickAgainAudio = null else pickAgainCaption = null
            val all = groups.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
            val at = TrackMatch.best(wanted, all.map { (g, i) -> g.getTrackFormat(i) })
            val (group, index) = all.getOrNull(at) ?: continue
            if (group.isTrackSelected(index)) { if (type == C.TRACK_TYPE_TEXT) captions = true; continue }
            if (type == C.TRACK_TYPE_TEXT) {
                b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                captions = true
            }
            b.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            changed = true
        }
        if (changed) p.trackSelectionParameters = b.build()
        return captions
    }

    /** Whether playback is still behind live: a seekable live window says by its offset; a plain stream until reopened. */
    private fun stillBehind(p: ExoPlayer): Boolean {
        if (!behind) return false
        if (!paused && p.isCurrentMediaItemLive && p.isCurrentMediaItemDynamic) {
            val offset = p.currentLiveOffset
            if (offset != C.TIME_UNSET && offset <= LIVE_EDGE_SLACK_MS) behind = false
        }
        return behind
    }

    fun selectCaption(track: Track?) {
        val p = player ?: return
        val b = p.trackSelectionParameters.buildUpon()
        if (track == null) {
            captionsOn = false
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            setCues(emptyList())
        } else {
            val group = p.currentTracks.groups.getOrNull(track.groupIndex) ?: return
            captionsOn = true
            captionsMime = group.getTrackFormat(track.trackIndex).sampleMimeType
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex))
        }
        p.trackSelectionParameters = b.build()
    }

    /** A new channel's tracks: captions that were on are turned on again there (the same kind when it has it). */
    private fun keepCaptions(p: ExoPlayer, tracks: Tracks) {
        if (!captionsOn) return
        val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (text.isEmpty() || text.any { it.isSelected }) return
        val language = Prefs(app).subtitleLanguage
        val pick = text.flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
            .let { all ->
                // The same kind as before; among those, the default subtitle language first.
                val kind = all.filter { (g, i) -> g.getTrackFormat(i).sampleMimeType == captionsMime }.ifEmpty { all }
                kind.firstOrNull { (g, i) -> language.isNotEmpty() && g.getTrackFormat(i).language?.startsWith(language) == true } ?: kind.firstOrNull()
            } ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(pick.first.mediaTrackGroup, pick.second))
            .build()
    }

    private fun setCues(list: List<Cue>) {
        if (list.isEmpty() && cues.isEmpty()) return
        cues = list
        for (l in listeners.toList()) l.onLiveCues(list)
    }

    fun selectAudio(track: Track) {
        val p = player ?: return
        val group = p.currentTracks.groups.getOrNull(track.groupIndex) ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track.trackIndex))
            .build()
    }

    // ---- The stream ----------------------------------------------------------------------------------------------

    private fun openStream(source: LiveSession.Source) {
        main.removeCallbacks(retry)
        // Tracks to pick again are only for the same channel reopened by Live TV buffer.
        if (source.key != pickAgainKey) { pickAgainAudio = null; pickAgainCaption = null }
        reconnect.reset()
        refusal.opened()
        reconnecting = false
        waitReason = null
        error = null
        // A fresh stream starts at live.
        paused = false
        behind = false
        parked = false
        main.removeCallbacks(parkPaused)
        if (source.key != delayKey) {
            delayKey = source.key
            delays.audioUs = 0L
        }
        val before = player
        val p = playerFor(source.headers)
        kind = LiveTiming.kindOf(Uri.parse(source.url).lastPathSegment)
        timing.opened(SystemClock.uptimeMillis(), pressedAt, kind, before === p)
        // A new stream: no stalls counted, the memory ceiling lifted until Fire OS asks again.
        main.removeCallbacks(stallCheck)
        stalledAt = 0L
        jumping = true
        memoryStep = 0
        control?.let { it.newStream(); it.squeeze(0) }
        // The shared Defaults (Settings › Defaults, the same values films use): the sound in the chosen language when
        // the channel has it, and that language's captions first once captions are on.
        val prefs = Prefs(app)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(prefs.audioLanguage.ifEmpty { null })
            .setPreferredTextLanguage(prefs.subtitleLanguage.ifEmpty { null })
            .build()
        setCues(emptyList())
        p.setMediaItem(mediaItem(source))
        p.prepare()
        p.playWhenReady = true
    }

    private fun closeStream() {
        main.removeCallbacks(retry)
        // Released, not just stopped: a stopped player still holds its decoders, which the film player needs.
        player?.let { p ->
            surface?.let { p.clearVideoSurfaceView(it) }
            p.removeListener(playerListener)
            p.release()
        }
        main.removeCallbacks(stallCheck)
        player = null
        control = null
        builtHeaders = null
        builtBuffer = null
        stalledAt = 0L
        jumping = false
        lastState = Player.STATE_IDLE
        timing.clear()
        reconnect.reset()
        refusal.opened()
        reconnecting = false
        waitReason = null
        error = null
        paused = false
        behind = false
        parked = false
        main.removeCallbacks(parkPaused)
        setCues(emptyList())
    }

    /** The live player, built again when a channel needs other headers or the Live TV buffer was changed. */
    private fun playerFor(headers: Map<String, String>): ExoPlayer {
        val buffer = LivePrefs(app).liveBuffer
        val existing = player
        if (existing != null && builtHeaders == headers && builtBuffer == buffer) return existing
        existing?.let { old ->
            // Lets go of the picture first, as closeStream does, so the new player takes the same surface cleanly.
            surface?.let { old.clearVideoSurfaceView(it) }
            old.removeListener(playerListener)
            old.release()
            main.removeCallbacks(stallCheck)
            stalledAt = 0L
        }
        val c = LiveLoadControl(buffer)
        val p = PlayerFactory.buildLive(app, headers, Prefs(app).passthrough, delays, buffer, c)
        control = c
        lastState = Player.STATE_IDLE
        p.addAnalyticsListener(bandwidth)
        // Captions start off; the viewer turns them on from the Captions button (and they then stay on).
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !captionsOn).build()
        p.addListener(playerListener)
        surface?.let { p.setVideoSurfaceView(it) }
        if (muted) p.volume = 0f
        player = p
        builtHeaders = headers
        builtBuffer = buffer
        return p
    }

    private fun mediaItem(source: LiveSession.Source): MediaItem = liveMediaItem(source)

    /** Reconnects after an error or a dropped stream: back to the live edge, prepared again. */
    private val retry = Runnable {
        val p = player ?: return@Runnable
        Log.i(TAG, "Live recovery: reconnecting (try ${reconnect.attempt})")
        // Reconnecting lands at the live edge: whatever was behind has recalibrated itself.
        behind = false
        paused = false
        // A stall that came back by itself while the wait ran: nothing to reconnect.
        if (p.playbackState == Player.STATE_READY && p.playWhenReady) return@Runnable
        jumping = true
        // A stall that got nowhere still holds its dead connection: stopped first, so preparing opens a new one.
        if (p.playbackState == Player.STATE_BUFFERING) p.stop()
        p.seekToDefaultPosition()
        if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) p.prepare()
        p.playWhenReady = true
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()

        override fun onTracksChanged(tracks: Tracks) {
            val p = player ?: return
            if (!tracks.isEmpty) timing.tracks(SystemClock.uptimeMillis())
            // Reopened by Live TV buffer: the captions picked before, rather than the first of their kind.
            if (!pickAgain(p, tracks)) keepCaptions(p, tracks)
        }

        override fun onCues(cueGroup: CueGroup) = setCues(if (captionsOn) cueGroup.cues else emptyList())

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Another app took the sound for good (its film or music started): the channel paused itself, and shows as
            // paused, as if the viewer had paused it. (A moment's loss, Alexa answering, doesn't come here: the player
            // waits and carries on by itself; see onPlaybackSuppressionReasonChanged.)
            // The same when the sound's way out goes (the TV or sound bar switched off or over, headphones
            // disconnected): the player pauses itself then too (setHandleAudioBecomingNoisy). That pause showed
            // nowhere: a still picture that said it was playing, and a first Play/Pause press that did nothing.
            if (!LivePause.byItself(reason)) return
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) Log.i(TAG, "Live: paused itself, the sound's output went away")
            paused = !playWhenReady
            if (paused) behind = true
            watchPause()
            publish()
        }

        override fun onPlayerError(e: PlaybackException) {
            val p = player ?: return
            if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                // Fell out of the live window (a long stall or pause): back to the live edge, silently.
                Log.i(TAG, "Live recovery: behind the live window; ${diagnosis(p)}; back to the live edge")
                jumping = true
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            val reason = when (e.errorCode) {
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The channel isn't available right now"
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Can't reach the channel's server"
                in CANT_DECODE -> "This TV can't play this channel's picture"
                else -> "The channel couldn't be played"
            }
            // A picture this TV can't decode won't get better by asking again: a couple of tries (the decoder may only
            // have been busy), then it says so. Anything else is tried for as long as the viewer stays on the channel.
            if (e.errorCode in CANT_DECODE && reconnect.attempt >= CANT_DECODE_TRIES) {
                Log.i(TAG, "Live recovery: gave up after ${reconnect.attempt} tries (${e.errorCodeName})")
                main.removeCallbacks(retry)
                reconnecting = false
                waitReason = null
                error = reason
                publish()
                return
            }
            // A refusal (404, 403...) is said in plain words and tried again slowly; a server or network error is
            // "Reconnecting..." as before. The status number only: an error's own text can hold the stream's address.
            val code = PlayerFactory.httpCodeOf(e)
            refusal.failed(code)
            // The code's name only: an error's own text can hold the stream's address.
            reconnectAfter(if (code != 0) "${e.errorCodeName} $code" else e.errorCodeName, reason, ended = false)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val now = SystemClock.elapsedRealtime()
            if (!isPlaying) {
                reconnect.notPlaying(now)
                return
            }
            reconnect.playing(now)
            if (refusal.shown) Log.i(TAG, "Live recovery: the channel the provider refused (${refusal.code}) plays")
            refusal.playing()
            if (reconnecting) {
                Log.i(TAG, "Live recovery: playing again after ${now - reconnectingSince} ms (${reconnect.attempt} tries)")
                reconnecting = false
                waitReason = null
            }
        }

        override fun onRenderedFirstFrame() {
            timing.firstFrame(SystemClock.uptimeMillis())?.let { Log.i(TAG, it) }
        }

        override fun onPlaybackSuppressionReasonChanged(reason: Int) {
            // A moment's loss of the sound (Alexa answering): the player holds the picture and carries on from its
            // buffer afterwards, as far behind the broadcast as the answer took. Go live is offered then, as after a
            // pause (a live window that catches up counts as live again by itself, see stillBehind).
            if (reason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS && session.playingKey != null) {
                behind = true
                publish()
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) timing.ready(SystemClock.uptimeMillis())
            noteStall(state)
            // A live channel doesn't end: the provider dropped the stream. Reconnect, almost at once the first time
            // (what was loaded has played out), then with the same back-off as errors.
            if (state != Player.STATE_ENDED || session.playingKey == null) return
            if (player == null) return
            reconnectAfter("stream ended", "The channel stopped", ended = true)
        }
    }

    private val bandwidth = object : AnalyticsListener {
        override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
            this@PlayerHost.bitrateEstimate = bitrateEstimate
        }

        /** How long the picture's decoder took to start, for the channel's timing line (once a channel, never per frame). */
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            timing.decoder(initializationDurationMs)
        }
    }

    /**
     * Ready, then buffering while meant to play, with no jump asked for: a stall. Each gets one log line, and once it
     * plays on, another; the stall wait grows on the same stream ([LiveCushion]) and a stall with nothing arriving is
     * reconnected ([stallCheck]).
     */
    private fun noteStall(state: Int) {
        val p = player ?: return
        val c = control
        val was = lastState
        lastState = state
        val now = SystemClock.elapsedRealtime()
        when (state) {
            Player.STATE_BUFFERING -> if (was == Player.STATE_READY && !jumping && p.playWhenReady && session.playingKey != null && c != null) {
                c.cushion.stalls++
                stalledAt = now
                Log.i(TAG, "Live rebuffer ${c.cushion.stalls}: up to ${c.takePeakMs()} ms was loaded ahead before it; " +
                    "${diagnosis(p)}; ${if (p.isLoading) "loading" else "not loading"}; " +
                    "plays on at ${c.cushion.afterStallNeedUs(0) / 1000} ms loaded")
                stallWatch.started(now, p.totalBufferedDuration)
                main.removeCallbacks(stallCheck)
                main.postDelayed(stallCheck, LiveStallWatch.CHECK_MS)
            }
            Player.STATE_READY -> {
                jumping = false
                main.removeCallbacks(stallCheck)
                if (stalledAt != 0L) {
                    Log.i(TAG, "Live rebuffer over after ${now - stalledAt} ms; ${diagnosis(p)}")
                    stalledAt = 0L
                }
                c?.takePeakMs()
            }
            else -> {
                jumping = false
                main.removeCallbacks(stallCheck)
                stalledAt = 0L
            }
        }
    }

    /** What the log needs to tell one stall from another (never the address). */
    private fun diagnosis(p: ExoPlayer): String {
        val offset = p.currentLiveOffset
        val video = p.videoFormat?.bitrate ?: Format.NO_VALUE
        return "estimate ${bitrateEstimate / 1000} kbps" +
            (if (video != Format.NO_VALUE) ", video ${video / 1000} kbps" else "") +
            ", live offset " + (if (offset == C.TIME_UNSET) "n/a" else "$offset ms") +
            ", $kind" + (if (memoryStep > 0) ", memory step $memoryStep" else "")
    }

    /** While a stall lasts, every few seconds: nothing has arrived for a while, so the connection is opened again. */
    private val stallCheck = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.playbackState != Player.STATE_BUFFERING || !p.playWhenReady || session.playingKey == null) return
            if (!stallWatch.stuck(SystemClock.elapsedRealtime(), p.totalBufferedDuration)) {
                main.postDelayed(this, LiveStallWatch.CHECK_MS)
                return
            }
            Log.i(TAG, "Live recovery: stalled with nothing arriving for ${LiveStallWatch.LIMIT_MS} ms; ${diagnosis(p)}")
            stalledAt = 0L
            reconnectAfter("stall", "The channel stopped", ended = true)
        }
    }

    /**
     * The channel is tried again after [LiveReconnect]'s wait; the screen is told it's waiting ([Status.reconnecting],
     * published with the player's other news) and, once the quick tries are used up, why ([Status.waiting]).
     */
    private fun reconnectAfter(what: String, reason: String, ended: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!reconnecting) {
            reconnecting = true
            reconnectingSince = now
        }
        val quick = reconnect.failed(now, ended)
        // A refused channel: one quick try, then slowly (LiveRefusal.waitMs); its message stands in for the reason.
        val wait = if (refusal.shown) refusal.waitMs() else quick
        if (reconnect.slow) waitReason = "$reason\nTrying again every ${LiveReconnect.SLOW_MS / 1000} seconds"
        Log.i(TAG, "Live recovery: $what; try ${reconnect.attempt} in $wait ms")
        main.removeCallbacks(retry)
        main.postDelayed(retry, wait)
    }

    private fun publish() {
        val s = status()
        for (l in listeners.toList()) l.onLiveStatus(s)
    }

    fun status(): Status {
        val p = player
        val tracks = p?.currentTracks ?: Tracks.EMPTY
        return Status(
            key = session.playingKey,
            buffering = p?.playbackState == Player.STATE_BUFFERING,
            playing = p?.isPlaying == true,
            error = error,
            video = p?.videoFormat,
            audio = p?.audioFormat,
            videoSize = p?.videoSize ?: VideoSize.UNKNOWN,
            captions = tracksOf(tracks, C.TRACK_TYPE_TEXT),
            audioTracks = tracksOf(tracks, C.TRACK_TYPE_AUDIO),
            paused = paused && session.playingKey != null,
            behindLive = p != null && session.playingKey != null && stillBehind(p),
            reconnecting = reconnecting && session.playingKey != null,
            waiting = waitReason,
            refused = if (session.playingKey != null && error == null) refusal.message() else null,
        )
    }

    private fun tracksOf(tracks: Tracks, type: Int): List<Track> = buildList {
        tracks.groups.forEachIndexed { gi, g ->
            if (g.type != type) return@forEachIndexed
            for (ti in 0 until g.length) {
                if (!g.isTrackSupported(ti)) continue
                val f = g.getTrackFormat(ti)
                val label = f.label ?: captionName(f) ?: f.language?.let { java.util.Locale(it).displayLanguage.takeIf { d -> d.isNotBlank() } } ?: "Track ${size + 1}"
                add(Track(gi, ti, label, g.isTrackSelected(ti)))
            }
        }
    }

    /** TV closed captions found in the picture (the extractor drops the labels they were declared with). */
    private fun captionName(f: Format): String? = when (f.sampleMimeType) {
        MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_MP4CEA608 -> "CC" + (if (f.accessibilityChannel in 1..4) f.accessibilityChannel else 1)
        MimeTypes.APPLICATION_CEA708 -> "Digital captions"
        else -> null
    }

    companion object {
        private const val TAG = "FirePlayerLive"
        /** Errors that say this TV can't decode the channel's picture, and how often such a channel is tried again. */
        internal val CANT_DECODE = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        )
        internal const val CANT_DECODE_TRIES = 2
        /** Within this of the live edge counts as live again (the player itself sits a few seconds back). */
        private const val LIVE_EDGE_SLACK_MS = 20_000L

        /** Every stream the app opens counts here, per provider account. */
        val budget = ConnectionBudget()

        /**
         * A channel's stream as Media3 opens it (the live player and Multiview's pictures alike). A live window (HLS/DASH)
         * is never played faster than it arrives to close in on the live edge: Media3's default speeds up to 1.03x until
         * it sits at its target offset, eating the cushion that rides out a late segment. It still slows down (to 0.97x)
         * to back away from the edge after a stall.
         */
        fun liveMediaItem(source: LiveSession.Source): MediaItem {
            val uri = Uri.parse(source.url)
            val path = uri.lastPathSegment?.lowercase() ?: ""
            val b = MediaItem.Builder().setUri(uri).setMediaId(source.key)
                .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setMaxPlaybackSpeed(1f).build())
            when {
                path.endsWith(".m3u8") || path.endsWith(".m3u") -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
                path.endsWith(".mpd") -> b.setMimeType(MimeTypes.APPLICATION_MPD)
            }
            return b.build()
        }

        @Volatile private var instance: PlayerHost? = null

        fun get(context: Context): PlayerHost =
            instance ?: synchronized(this) { instance ?: PlayerHost(context).also { instance = it } }

        /** The host if live playback was ever used in this process. */
        fun peek(): PlayerHost? = instance
    }
}
