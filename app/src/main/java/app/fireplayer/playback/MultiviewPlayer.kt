package app.fireplayer.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.fireplayer.data.Prefs
import app.fireplayer.live.ui.MultiviewRules
import app.fireplayer.player.LiveBuffer
import app.fireplayer.player.PlayerFactory

/**
 * One Multiview picture's player: its own [ExoPlayer] on its own [SurfaceView], built for a small picture. The Small
 * Live TV buffer, held to Multiview's memory step from the start ([LiveLoadControl.squeeze]); no audio focus of its
 * own and the sound always decoded (never passed through to the TV), so a picture not selected is truly silent at
 * volume 0; captions off; the video size capped to the picture's part ([MultiviewRules.maxVideo]).
 *
 * A channel that fails or is dropped is tried again by itself, as the one live player does ([LiveReconnect],
 * [LiveRefusal], a stall getting nowhere); only a picture the TV can't decode (its decoders all in use) gives up, with
 * a plain message ([error]), and lets go of its player so the other pictures keep the memory. The one live player
 * ([PlayerHost]) is left as it is: this one only exists while Multiview shows.
 *
 * Main thread only. Logs say what happened to "a picture", never a link or a name.
 */
@OptIn(UnstableApi::class)
class MultiviewPlayer(context: Context, private val onChange: () -> Unit) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var builtHeaders: Map<String, String>? = null
    private var control: LiveLoadControl? = null
    private var surface: SurfaceView? = null

    /** The channel this picture plays (null: none, or closed). */
    var source: LiveSession.Source? = null
        private set

    private val reconnect = LiveReconnect()
    private val refusal = LiveRefusal()
    private val stallWatch = LiveStallWatch()
    private var lastState = Player.STATE_IDLE
    /** A jump the picture asked for (a new stream, a reconnect) is under way: its buffering isn't a stall. */
    private var jumping = false
    /** Between a failure and frames playing again. */
    var reconnecting = false
        private set
    private var waitReason: String? = null

    /** The picture gave up: what it says instead (null while all is well). */
    var error: String? = null
        private set

    private var volume = 0f
    private var memoryStep = 1
    private var maxW = 1280
    private var maxH = 720

    val playing: Boolean get() = player?.isPlaying == true
    val videoSize: VideoSize get() = player?.videoSize ?: VideoSize.UNKNOWN

    /** Waiting with nothing playing: the buffering symbol's question. */
    val waiting: Boolean
        get() {
            val p = player ?: return source != null && error == null && reconnecting
            return source != null && error == null && !p.isPlaying &&
                (p.playbackState == Player.STATE_BUFFERING || reconnecting || refusal.shown)
        }

    /** Under the buffering symbol once due: the provider's refusal, the slow tries' reason, or reconnecting. */
    val waitText: String get() = refusal.message() ?: waitReason ?: "Reconnecting…"
    val refused: Boolean get() = refusal.shown

    /** Plays [source] on [view], in place of whatever this picture played. */
    fun open(source: LiveSession.Source, view: SurfaceView) {
        this.source = source
        attach(view)
        main.removeCallbacks(retry)
        main.removeCallbacks(stallCheck)
        reconnect.reset()
        refusal.opened()
        reconnecting = false
        waitReason = null
        error = null
        val p = try {
            playerFor(source.headers)
        } catch (e: RuntimeException) {
            // The class only: a failure's own text can hold the stream's address.
            Log.w(TAG, "Multiview: a picture's player couldn't be made (${e.javaClass.simpleName})")
            error = MultiviewRules.CANT_PLAY
            onChange()
            return
        }
        jumping = true
        lastState = Player.STATE_IDLE
        control?.let { it.newStream(); it.squeeze(memoryStep) }
        val prefs = Prefs(app)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(prefs.audioLanguage.ifEmpty { null })
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .setMaxVideoSize(maxW, maxH)
            .build()
        p.setMediaItem(PlayerHost.liveMediaItem(source))
        p.prepare()
        p.playWhenReady = true
        onChange()
    }

    /** Shows the picture on [view] (a picture keeps its view; set again after the view was made anew). */
    fun attach(view: SurfaceView) {
        if (surface === view) return
        surface?.let { player?.clearVideoSurfaceView(it) }
        surface = view
        player?.setVideoSurfaceView(view)
    }

    /** The stream stops and the player is let go of (decoders and buffers back); the channel is forgotten. */
    fun close() {
        main.removeCallbacks(retry)
        main.removeCallbacks(stallCheck)
        releasePlayer()
        source = null
        error = null
        reconnecting = false
        waitReason = null
        reconnect.reset()
        refusal.opened()
    }

    /** [close], and the view is let go of too: the picture is gone. */
    fun release() {
        close()
        surface = null
    }

    /** Heard (1) or silent (0): only the selected picture is heard. */
    fun setVolume(v: Float) {
        if (volume == v) return
        volume = v
        player?.volume = v
    }

    /** How far the buffer is held ([MultiviewRules.memoryStep]); applied from the start and whenever it changes. */
    fun setMemoryStep(step: Int) {
        if (memoryStep == step) return
        memoryStep = step
        control?.squeeze(step)
    }

    /** The largest video this picture asks for (its part in the layout); a channel with several sizes plays the one that fits. */
    fun limitVideo(size: IntArray) {
        if (maxW == size[0] && maxH == size[1]) return
        maxW = size[0]
        maxH = size[1]
        player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setMaxVideoSize(maxW, maxH).build() }
    }

    private fun playerFor(headers: Map<String, String>): ExoPlayer {
        player?.let { if (builtHeaders == headers) return it }
        releasePlayer()
        val c = LiveLoadControl(LiveBuffer.SMALL)
        c.squeeze(memoryStep)
        val p = PlayerFactory.buildLive(app, headers, passthrough = false, delays = null, buffer = LiveBuffer.SMALL, liveLoadControl = c, audioFocus = false)
        p.volume = volume
        p.addListener(listener)
        surface?.let { p.setVideoSurfaceView(it) }
        player = p
        control = c
        builtHeaders = headers
        return p
    }

    private fun releasePlayer() {
        main.removeCallbacks(stallCheck)
        // Released, not just stopped: a stopped player still holds its decoders, which the other pictures may need.
        player?.let { p ->
            surface?.let { p.clearVideoSurfaceView(it) }
            p.removeListener(listener)
            p.release()
        }
        player = null
        control = null
        builtHeaders = null
        lastState = Player.STATE_IDLE
        jumping = false
    }

    /** Tries again after a failure or a dropped stream: back to the live edge, prepared again. */
    private val retry = Runnable {
        val p = player ?: return@Runnable
        Log.i(TAG, "Multiview recovery: a picture reconnects (try ${reconnect.attempt})")
        if (p.playbackState == Player.STATE_READY && p.playWhenReady) return@Runnable
        jumping = true
        if (p.playbackState == Player.STATE_BUFFERING) p.stop()
        p.seekToDefaultPosition()
        if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) p.prepare()
        p.playWhenReady = true
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = onChange()

        override fun onPlayerError(e: PlaybackException) {
            val p = player ?: return
            if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                jumping = true
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            // The TV's decoders are all in use (or it can't play this picture): a couple of tries (one may only have
            // been busy a moment), then it says so, and lets go of its player.
            if (e.errorCode in PlayerHost.CANT_DECODE && reconnect.attempt >= PlayerHost.CANT_DECODE_TRIES) {
                Log.i(TAG, "Multiview: a picture gave up, no decoder for it (${e.errorCodeName})")
                main.removeCallbacks(retry)
                reconnecting = false
                waitReason = null
                releasePlayer()
                error = MultiviewRules.NO_DECODER
                onChange()
                return
            }
            val reason = when (e.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Can't reach the channel's server"
                else -> "The channel couldn't be played"
            }
            // The status number and the code's name only: an error's own text can hold the stream's address.
            val code = PlayerFactory.httpCodeOf(e)
            refusal.failed(code)
            reconnectAfter(if (code != 0) "${e.errorCodeName} $code" else e.errorCodeName, reason, ended = false)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val now = SystemClock.elapsedRealtime()
            if (!isPlaying) {
                reconnect.notPlaying(now)
                return
            }
            reconnect.playing(now)
            refusal.playing()
            reconnecting = false
            waitReason = null
        }

        override fun onPlaybackStateChanged(state: Int) {
            noteStall(state)
            if (state == Player.STATE_ENDED && source != null && player != null) reconnectAfter("stream ended", "The channel stopped", ended = true)
        }
    }

    /** Ready, then buffering while meant to play, with no jump asked for: a stall, watched every few seconds. */
    private fun noteStall(state: Int) {
        val p = player ?: return
        val was = lastState
        lastState = state
        when (state) {
            Player.STATE_BUFFERING -> if (was == Player.STATE_READY && !jumping && p.playWhenReady) {
                control?.let { it.cushion.stalls++ }
                stallWatch.started(SystemClock.elapsedRealtime(), p.totalBufferedDuration)
                main.removeCallbacks(stallCheck)
                main.postDelayed(stallCheck, LiveStallWatch.CHECK_MS)
            }
            else -> {
                jumping = false
                main.removeCallbacks(stallCheck)
            }
        }
    }

    /** While a stall lasts: nothing arriving for a while, so the connection is opened again. */
    private val stallCheck = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (p.playbackState != Player.STATE_BUFFERING || !p.playWhenReady || source == null) return
            if (!stallWatch.stuck(SystemClock.elapsedRealtime(), p.totalBufferedDuration)) {
                main.postDelayed(this, LiveStallWatch.CHECK_MS)
                return
            }
            reconnectAfter("stall", "The channel stopped", ended = true)
        }
    }

    private fun reconnectAfter(what: String, reason: String, ended: Boolean) {
        val now = SystemClock.elapsedRealtime()
        reconnecting = true
        val quick = reconnect.failed(now, ended)
        // A refused channel: one quick try, then slowly; its message stands in for the reason.
        val wait = if (refusal.shown) refusal.waitMs() else quick
        if (reconnect.slow) waitReason = "$reason\nTrying again every ${LiveReconnect.SLOW_MS / 1000} seconds"
        Log.i(TAG, "Multiview recovery: a picture's $what; try ${reconnect.attempt} in $wait ms")
        main.removeCallbacks(retry)
        main.postDelayed(retry, wait)
        onChange()
    }

    private companion object {
        const val TAG = "FirePlayerLive"
    }
}
