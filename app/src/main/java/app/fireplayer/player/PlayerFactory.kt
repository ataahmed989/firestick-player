package app.fireplayer.player

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.common.Format
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.ui.SubtitleView
import app.fireplayer.subs.PictureText
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.AssHandlerConfig
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.kt.withAssSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.render.AssRenderer
import io.github.peerless2012.ass.media.type.AssRenderType

/** Live subtitle and audio offsets, read by the renderers on the playback thread. */
class Delays {
    @Volatile var subtitleUs: Long = 0L
    @Volatile var audioUs: Long = 0L
}

/**
 * How far along the wait before the film plays (or plays on) is, for the spinner. [fraction] is the share already
 * loaded, [slowLine] means the film arrives slower than it plays, and [atMs] (elapsedRealtime) is when this was worked
 * out: one more than a moment old is from an earlier wait.
 */
class BufferWait(val fraction: Float, val slowLine: Boolean, val atMs: Long)

@OptIn(UnstableApi::class)
object PlayerFactory {

    class Built(val player: ExoPlayer, val assHandler: AssHandler?, val bufferWait: () -> BufferWait?)

    /**
     * Jumps land on a keyframe up to 2 s before the time asked for (never after; 2 s before when there's none in
     * between), so less video has to be downloaded and decoded before the picture moves.
     */
    val FILM_SEEK = SeekParameters(2_000_000L, 0L)

    /**
     * Decoders for the skip preview. Its picture is decoded while the film plays, so 4K-class formats only get a
     * hardware decoder: decoding them on the processor would take it from the film and make it stutter.
     */
    private val PREVIEW_DECODERS = MediaCodecSelector { mimeType, secure, tunneling ->
        val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
        if (mimeType in HEAVY_VIDEO) all.filter { it.hardwareAccelerated } else all
    }

    private val HEAVY_VIDEO = setOf(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_DOLBY_VISION, MimeTypes.VIDEO_VP9, MimeTypes.VIDEO_AV1)

    private fun dataSourceFactory(
        context: Context,
        headers: Map<String, String>,
        serverSwitch: ServerSwitch? = null,
        readOnlyMemory: Boolean = false,
    ): DataSource.Factory {
        // IPTV providers often require the User-Agent TiviMate passes along, so it must win over ours.
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent ?: "FirePlayer/1.0 (Linux; Android) ExoPlayer")
            .setAllowCrossProtocolRedirects(true)
            // A server that doesn't answer in 12 s won't answer at 20; failing sooner lets the retries take over. The
            // same for one that goes quiet in the middle of the film: a skip can't get past a read that's waiting.
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(12_000)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
        return DefaultDataSource.Factory(context, RedirectMemoryDataSource.Factory(httpFactory, serverSwitch, readOnlyMemory))
    }

    /**
     * A small video-only player for the skip preview: it loads a second or so around a jump target,
     * shows the nearest keyframe, and never plays sound or subtitles. It loads only while [allowLoading] says so.
     */
    fun buildPreview(context: Context, headers: Map<String, String>, allowLoading: () -> Boolean): ExoPlayer {
        val extractors = SeekableMkvExtractorsFactory(DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true))
        // Where the provider sent the film is the film's to change: the preview being turned away says nothing about it.
        val upstream = dataSourceFactory(context, headers, readOnlyMemory = true)
        // Its connection closes as soon as a picture is loaded, so the film can have the server to itself.
        val dataSources = DataSource.Factory { IdleCloseDataSource(upstream.createDataSource(), 400) }
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSources, extractors)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1))
        val loadControl = GatedLoadControl(
            DefaultLoadControl.Builder()
                // The keyframe and a few frames after it. Every extra second is megabytes of a high-quality
                // film that the film itself is waiting for.
                .setBufferDurationsMs(300, 600, 100, 100)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build(),
            allowLoading,
        )
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(PREVIEW_DECODERS)
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setRenderersFactory(renderers)
            .setLoadControl(loadControl)
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .setMaxVideoSize(1920, 1080)
            .build()
        // Jumping to the nearest keyframe is what makes a preview quick.
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        player.volume = 0f
        player.playWhenReady = false
        return player
    }

    /**
     * Builds a player tuned for streaming on a Fire TV:
     * hardware decoders first, FFmpeg for audio the device can't decode, a deeper
     * buffer for IPTV streams, and libass for styled ASS/SSA subtitles.
     */
    fun build(
        context: Context,
        delays: Delays,
        passthrough: Boolean,
        useAss: Boolean,
        assSubtitleView: SubtitleView,
        headers: Map<String, String>,
        /** Reads picture subtitles as text while they load (see [PictureText]); only used without libass. */
        pictureText: PictureText,
        /** Given the player's video renderers, to keep their frames evenly paced (see [VsyncFix]). */
        vsync: VsyncFix,
    ): Built {
        val serverSwitch = ServerSwitch()
        val dataSourceFactory = dataSourceFactory(context, headers, serverSwitch)

        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        val baseRenderers = TvRenderersFactory(context, passthrough)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        // As much of the film as memory allows, up to two minutes ahead, topped up all the time. High-quality
        // films fill it by size first; the stall-free stretch then depends on the connection, not a fixed
        // half minute.
        val budget = (Runtime.getRuntime().maxMemory() * 0.6).toLong().coerceIn(64L shl 20, 400L shl 20).toInt()
        // What's kept behind comes out of the same memory as what's ahead (for a 4K remux, about 8 MB a second), so
        // TVs with less of it keep less, or the room ahead would shrink to a few seconds.
        val backBufferMs = when {
            budget >= 280 shl 20 -> 15_000
            budget >= 180 shl 20 -> 10_000
            else -> 5_000
        }
        val loadControl = FilmLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 120_000,
                    /* maxBufferMs = */ 120_000,
                    // Start quickly after a skip; after a stall, FilmLoadControl decides.
                    /* bufferForPlaybackMs = */ 1_000,
                    /* bufferForPlaybackAfterRebufferMs = */ 4_000,
                )
                .setTargetBufferBytes(budget)
                .setPrioritizeTimeOverSizeThresholds(false)
                // The last 15 s are kept (less on TVs with little memory), so going back one press (10 s, landing up
                // to 2 s early) plays at once instead of loading again; more would come out of the memory a
                // high-quality film needs ahead. Kept from the keyframe before, without which the picture there
                // couldn't be decoded.
                .setBackBuffer(backBufferMs, /* retainBackBufferFromKeyframe = */ true)
                .build(),
            budget,
            serverSwitch,
        )

        val builder = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setHandleAudioBecomingNoisy(true)
            .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            // Keeps Wi-Fi at full speed while playing; left to itself it may save power and fall behind the film.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekParameters(FILM_SEEK)

        // Slow IPTV servers drop connections now and then; keep retrying before giving up.
        val retryPolicy = FilmRetryPolicy(serverSwitch)

        if (!useAss) {
            val parsers = pictureText.readAhead(DefaultSubtitleParserFactory())
            // Set on the extractors themselves too: the MKV wrapper doesn't pass this setting on to them.
            extractors.setSubtitleParserFactory(parsers)
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, SeekableMkvExtractorsFactory(extractors))
                .setLoadErrorHandlingPolicy(retryPolicy)
                .setSubtitleParserFactory(parsers)
            val player = builder
                .setMediaSourceFactory(mediaSourceFactory)
                .setRenderersFactory(DelayRenderersFactory(baseRenderers, delays, vsync))
                .build()
            return Built(player, null) { loadControl.wait }
        }

        // Same wiring as ass-media's buildWithAssSupport, plus our delay wrapper.
        val assHandler = AssHandler(
            AssRenderType.OVERLAY_OPEN_GL,
            AssHandlerConfig(maxRenderPixels = 1920 * 1080),
        )
        val parserFactory = AssSubtitleParserFactory(assHandler)
        val mediaSourceFactory = DefaultMediaSourceFactory(
            dataSourceFactory,
            SeekableMkvExtractorsFactory(extractors.withAssMkvSupport(parserFactory, assHandler)),
        )
        mediaSourceFactory.setSubtitleParserFactory(parserFactory)
        mediaSourceFactory.setLoadErrorHandlingPolicy(retryPolicy)
        val player = builder
            .setMediaSourceFactory(mediaSourceFactory)
            .setRenderersFactory(DelayRenderersFactory(baseRenderers.withAssSupport(assHandler), delays, vsync))
            .build()
        assSubtitleView.withAssSupport(assHandler)
        assHandler.init(player)
        return Built(player, assHandler) { loadControl.wait }
    }
}

/**
 * The film's load retries. IPTV servers often refuse a reconnect (403 and the like) for a few seconds while the old
 * connection still counts, so a refused load of the film is tried every second instead of at 0, 0, 1, 3, 6 and 10 s,
 * within the same 10 s or so before the error reaches PlayerActivity. A film that isn't there (404, 410) isn't tried
 * again at all, and a server that doesn't answer is tried three times rather than six, each try being a full timeout.
 * Everything else (dropped connections, 400/401, HLS and DASH) keeps Media3's spacing and limit.
 */
@OptIn(UnstableApi::class)
private class FilmRetryPolicy(private val serverSwitch: ServerSwitch) : DefaultLoadErrorHandlingPolicy(RETRIES) {
    // Whether the latest load error was a refusal of the film, or a server not answering. On the playback thread.
    @Volatile private var refused = false
    @Volatile private var unanswered = false

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val delay = super.getRetryDelayMsFor(loadErrorInfo)
        val data = loadErrorInfo.mediaLoadData
        // A progressive film's loads (the only ones that look at the limit again on every check) carry no track type
        // or format.
        val progressive = data.dataType == C.DATA_TYPE_MEDIA && data.trackType == C.TRACK_TYPE_UNKNOWN && data.trackFormat == null
        val error = loadErrorInfo.exception
        val code = (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        refused = progressive && delay != C.TIME_UNSET && code != null && code !in PERMANENT
        unanswered = progressive && error is HttpDataSource.HttpDataSourceException &&
            error.type == HttpDataSource.HttpDataSourceException.TYPE_OPEN && error.cause is java.net.SocketTimeoutException
        if (progressive) serverSwitch.loadFailed(refused)
        // Gone from the server: the error shows at once instead of after 10 s of asking again. Not for a live channel
        // (no known end), whose server may answer 404 for a moment while its source restarts.
        if (progressive && (code == 404 || code == 410) && data.mediaEndTimeMs != C.TIME_UNSET) return C.TIME_UNSET
        return if (refused && loadErrorInfo.errorCount <= REFUSED_RETRIES) minOf(delay, 1_000L) else delay
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = when {
        refused -> REFUSED_RETRIES
        unanswered -> UNANSWERED_RETRIES
        else -> RETRIES
    }

    private companion object {
        const val RETRIES = 5
        // Tries at 0, 0, 1, 2 ... 10 s: the 12th failure reaches PlayerActivity at about 10 s, as the 6th did before.
        const val REFUSED_RETRIES = 11
        const val UNANSWERED_RETRIES = 2
        val PERMANENT = setOf(400, 401, 404, 410)
    }
}

/**
 * The skip preview's buffer rules, which load nothing while [allowLoading] says no. Every method is passed on by hand:
 * Kotlin's `by` skips the interface's Java default methods, which then throw.
 */
@OptIn(UnstableApi::class)
private class GatedLoadControl(private val inner: LoadControl, private val allowLoading: () -> Boolean) : LoadControl {
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        allowLoading() && inner.shouldContinueLoading(parameters)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters) = inner.shouldStartPlayback(parameters)

    override fun shouldContinuePreloading(timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long) =
        inner.shouldContinuePreloading(timeline, mediaPeriodId, bufferedDurationUs)

    override fun onPrepared(playerId: PlayerId) = inner.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection?>,
    ) = inner.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = inner.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = inner.onReleased(playerId)

    override fun getAllocator(): Allocator = inner.allocator

    override fun getBackBufferDurationUs(playerId: PlayerId) = inner.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId) = inner.retainBackBufferFromKeyframe(playerId)
}

/**
 * The film's buffer rules (see [PlayerFactory.build]).
 *
 * It measures how fast the film arrives, in seconds of film per second. When the connection brings it faster than it
 * plays, playback starts (or restarts) after a second or a few. When it only just keeps up, or falls behind (a
 * high-quality film on a slower line), playback waits for enough to then play a minute without stopping, instead of
 * starting at once and stopping again every few seconds. Stalls that keep coming also make the next wait longer
 * (4, 10, then 20 seconds).
 */
@OptIn(UnstableApi::class)
private class FilmLoadControl(
    private val inner: DefaultLoadControl,
    private val budgetBytes: Int,
    private val serverSwitch: ServerSwitch,
) : LoadControl {

    // When recent stalls began (elapsedRealtime), on the playback thread only.
    private val stalls = ArrayDeque<Long>()
    private var stallId = C.TIME_UNSET

    private val window = Timeline.Window()
    private val period = Timeline.Period()

    // How fast the film arrives: seconds of film loaded during loadingMs of loading, since the last jump. On the
    // playback thread only.
    private var loadedUs = 0L
    private var loadingMs = 0L
    private var lastAtMs = 0L
    private var lastEndUs = C.TIME_UNSET
    private var lastPositionUs = C.TIME_UNSET
    private var lastPeriod: Any? = null
    private var loading = false
    // Film has started arriving since the last jump; the wait for the connection before that doesn't count.
    private var arriving = false
    // ServerSwitch.disturbances when last measured.
    private var disturbancesSeen = 0

    /** The latest wait before playing, read by the spinner on the main thread. */
    @Volatile var wait: BufferWait? = null
        private set

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        // Live channels can't build up a cushion (the stream only comes as fast as it plays), so they keep the usual rule.
        if (isLive(parameters)) return inner.shouldStartPlayback(parameters)
        // All of this episode is loaded, and what's loading is the next one (queued after it): nothing more of this
        // one can come, so waiting on would only wait on the next episode's server.
        if (loadedToEnd(parameters)) return true
        measure(parameters, waiting = true)
        // Memory full means nothing more can come before playing on.
        if (inner.allocator.totalBytesAllocated >= budgetBytes) return true
        val buffered = parameters.bufferedDurationUs
        val rate = fillRate()
        val now = SystemClock.elapsedRealtime()
        val neededUs = if (!parameters.rebuffering) {
            // Starting, or after a jump: a second's worth, once it's clear how fast the rest is coming (or plenty).
            if (rate == null) 10_000_000L else maxOf(START_US, cushionFor(rate))
        } else {
            if (parameters.lastRebufferRealtimeMs != stallId) {
                stallId = parameters.lastRebufferRealtimeMs
                stalls.addLast(now)
            }
            while (stalls.isNotEmpty() && now - stalls.first() > STALL_MEMORY_MS) stalls.removeFirst()
            val afterStallUs = when (stalls.size) {
                0, 1 -> 4_000_000L
                2 -> 10_000_000L
                else -> 20_000_000L
            }
            maxOf(afterStallUs, rate?.let { cushionFor(it) } ?: 0L)
        }
        // Whichever comes first ends the wait: enough film, or full memory.
        val fraction = maxOf(buffered.toFloat() / neededUs, inner.allocator.totalBytesAllocated.toFloat() / budgetBytes)
        wait = BufferWait(fraction.coerceIn(0f, 1f), rate != null && rate < 1f, now)
        return buffered >= neededUs
    }

    private fun loadedToEnd(parameters: LoadControl.Parameters): Boolean {
        // The time is counted from the start of the item being loaded, so it's before zero when that's a later one.
        if (parameters.playbackPositionUs < 0) return true
        val timeline = parameters.timeline
        val index = timeline.getIndexOfPeriod(parameters.mediaPeriodId.periodUid)
        if (index == C.INDEX_UNSET) return false
        val durationUs = timeline.getPeriod(index, period).durationUs
        return durationUs != C.TIME_UNSET && parameters.playbackPositionUs + parameters.bufferedDurationUs >= durationUs - 200_000
    }

    /** Seconds of film arriving per second of loading, once there's enough to tell; null before that. */
    private fun fillRate(): Float? = if (loadingMs < MEASURE_MS) null else loadedUs / 1000f / loadingMs

    /** How much to have before playing so it then plays [SMOOTH_US] without stopping, at [rate]. */
    private fun cushionFor(rate: Float): Long = when {
        rate >= 1.3f -> 0L
        // Only just keeping up: room for the connection's ups and downs.
        rate >= 1f -> 8_000_000L
        // Falling behind by (1 - rate) every second.
        else -> (SMOOTH_US * (1f - rate)).toLong().coerceAtMost(MAX_CUSHION_US)
    }

    /**
     * Follows how far the loaded film reaches, counting only time spent loading, and starts over after a jump.
     * [waiting]: playback is held up waiting for the film.
     */
    private fun measure(parameters: LoadControl.Parameters, waiting: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val positionUs = parameters.playbackPositionUs
        val endUs = positionUs + parameters.bufferedDurationUs
        val periodUid = parameters.mediaPeriodId.periodUid
        val elapsedMs = now - lastAtMs
        // A jump within what's loaded moves the position but not the end of what's loaded; one past it moves both.
        val moved = kotlin.math.abs(positionUs - lastPositionUs) > elapsedMs * 2_000 + 1_000_000
        val jumped = lastEndUs == C.TIME_UNSET || periodUid != lastPeriod || endUs < lastEndUs - 100_000 ||
            (moved && kotlin.math.abs(endUs - lastEndUs) > 2_000_000)
        // Held up by something other than the server's speed (a failed load and the wait to try again, or another
        // download on the same line): that stretch says nothing about the server, so it's measured afresh.
        val disturbances = ServerSwitch.disturbances.get()
        val disturbed = disturbances != disturbancesSeen
        disturbancesSeen = disturbances
        if (jumped || disturbed) {
            loadedUs = 0
            loadingMs = 0
            arriving = false
        } else if (!arriving) {
            arriving = endUs > lastEndUs
        } else if (loading) {
            loadedUs += endUs - lastEndUs
            loadingMs += elapsedMs
            // Mostly the last half minute or so, so it follows the connection as it changes.
            if (loadingMs > RATE_MEMORY_MS) {
                loadedUs /= 2
                loadingMs /= 2
            }
            // Arriving slower than it plays, for long enough to be sure: the provider may have sent us to a busy
            // server, so ask it for another. A live channel never arrives faster than it plays, so it never counts.
            // While playing, only with enough loaded to cover the reconnect, which some servers refuse for a moment.
            if (loadingMs >= SWITCH_AFTER_MS && loadedUs < loadingMs * 1000 && !isLive(parameters) &&
                (waiting || parameters.bufferedDurationUs >= MOVE_COVER_US) && serverSwitch.request()
            ) {
                // The new server is measured afresh.
                loadedUs = 0
                loadingMs = 0
                arriving = false
            }
        }
        lastAtMs = now
        lastEndUs = endUs
        lastPositionUs = positionUs
        lastPeriod = periodUid
    }

    private fun isLive(parameters: LoadControl.Parameters): Boolean {
        if (parameters.targetLiveOffsetUs != C.TIME_UNSET) return true
        val timeline = parameters.timeline
        if (timeline.isEmpty) return false
        val windowIndex = timeline.getPeriodByUid(parameters.mediaPeriodId.periodUid, period).windowIndex
        return timeline.getWindow(windowIndex, window).isLive()
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        measure(parameters, waiting = false)
        return inner.shouldContinueLoading(parameters).also { loading = it }
    }

    override fun shouldContinuePreloading(timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long) =
        inner.shouldContinuePreloading(timeline, mediaPeriodId, bufferedDurationUs)

    override fun onPrepared(playerId: PlayerId) = inner.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection?>,
    ) = inner.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = inner.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = inner.onReleased(playerId)

    override fun getAllocator(): Allocator = inner.allocator

    override fun getBackBufferDurationUs(playerId: PlayerId) = inner.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId) = inner.retainBackBufferFromKeyframe(playerId)

    private companion object {
        const val STALL_MEMORY_MS = 5 * 60_000L
        const val START_US = 1_000_000L
        // Loading time needed before the speed is trusted.
        const val MEASURE_MS = 700L
        const val RATE_MEMORY_MS = 30_000L
        const val SMOOTH_US = 60_000_000L
        const val MAX_CUSHION_US = 30_000_000L
        const val SWITCH_AFTER_MS = 10_000L
        const val MOVE_COVER_US = 15_000_000L
    }
}

/**
 * Lets the film's buffer rules ask its connection to move to another of the provider's servers, when the film keeps
 * arriving slower than it plays. At most once a minute, and three times in all, so a provider whose servers are all
 * slow isn't asked forever; never while the app downloads something else on the same line ([alongside]), and not
 * again once a move's reconnect was refused.
 */
class ServerSwitch {
    /** Whether the film came through a redirect, so the provider has other servers it could send us to instead. */
    @Volatile var possible = false
    private val wanted = java.util.concurrent.atomic.AtomicBoolean(false)
    // On the playback thread.
    private var count = 0
    private var lastAt = 0L

    /** On the playback thread. False when it's too soon after the last move, or there have been enough. */
    fun request(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!possible || downloadsAlongside.get() > 0 || count >= MAX_SWITCHES || (count > 0 && now - lastAt < GAP_MS)) {
            return false
        }
        count++
        lastAt = now
        wanted.set(true)
        return true
    }

    /** On the loading thread: whether a move was asked for (only one connection gets to act on it). */
    fun take(): Boolean = wanted.getAndSet(false)

    /**
     * On the playback thread: a load of the film failed ([refused]: the server said no). Refused just after a move,
     * the provider's servers still count the old connection, so the film stays where it is from then on.
     */
    fun loadFailed(refused: Boolean) {
        disturbances.incrementAndGet()
        if (refused && count > 0 && SystemClock.elapsedRealtime() - lastAt < REFUSED_AFTER_MOVE_MS) count = MAX_SWITCHES
    }

    companion object {
        private const val MAX_SWITCHES = 3
        private const val GAP_MS = 60_000L
        private const val REFUSED_AFTER_MOVE_MS = 10_000L
        private val downloadsAlongside = java.util.concurrent.atomic.AtomicInteger()

        /** Changes whenever the film was held up by something other than its server's speed. */
        val disturbances = java.util.concurrent.atomic.AtomicInteger()

        /**
         * Runs [block], a download sharing the film's line: the film arriving slower meanwhile is that download's
         * doing, not the server's, so no move is asked for, and its speed is measured afresh afterwards.
         */
        fun <T> alongside(block: () -> T): T {
            downloadsAlongside.incrementAndGet()
            disturbances.incrementAndGet()
            try {
                return block()
            } finally {
                downloadsAlongside.decrementAndGet()
                disturbances.incrementAndGet()
            }
        }
    }
}

/** Lets the user turn surround passthrough off, so FFmpeg decodes to stereo/PCM instead. */
@OptIn(UnstableApi::class)
private class TvRenderersFactory(
    context: Context,
    private val passthrough: Boolean,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        if (passthrough) {
            return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
        }
        // Without a context the sink uses these fixed capabilities instead of what the TV reports,
        // so surround formats get decoded to PCM rather than passed through.
        @Suppress("DEPRECATION")
        return DefaultAudioSink.Builder()
            .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }
}

/**
 * Wraps renderers so delays can change while playing:
 * subtitles see a shifted clock, and video is shifted to move audio relative to it.
 */
@OptIn(UnstableApi::class)
private class DelayRenderersFactory(
    private val inner: RenderersFactory,
    private val delays: Delays,
    private val vsync: VsyncFix,
) : RenderersFactory {

    override fun createRenderers(
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textRendererOutput: TextOutput,
        metadataRendererOutput: MetadataOutput,
    ): Array<Renderer> = inner.createRenderers(
        eventHandler,
        videoRendererEventListener,
        audioRendererEventListener,
        textRendererOutput,
        metadataRendererOutput,
    ).map { vsync.add(it); wrap(it) }.toTypedArray()

    private fun wrap(renderer: Renderer): Renderer = when {
        renderer is AssRenderer -> AssOffsetRenderer(renderer, delays)
        renderer.trackType == C.TRACK_TYPE_TEXT ->
            OffsetRenderer(renderer) { -delays.subtitleUs }
        renderer.trackType == C.TRACK_TYPE_VIDEO ->
            OffsetRenderer(renderer) { delays.audioUs }
        else -> renderer
    }
}

@OptIn(UnstableApi::class)
private class OffsetRenderer(
    renderer: Renderer,
    private val offsetUs: () -> Long,
) : ForwardingRenderer(renderer) {
    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        super.render(positionUs + offsetUs(), elapsedRealtimeUs)
    }
}

/**
 * libass reads the clock assuming the first item's renderer offset. When the next file in a
 * playlist starts, the offset changes, so translate the position back to that first offset.
 */
@OptIn(UnstableApi::class)
private class AssOffsetRenderer(
    renderer: Renderer,
    private val delays: Delays,
) : ForwardingRenderer(renderer) {

    private var offsetUs = INITIAL_OFFSET_US
    private var pendingOffsetUs = C.TIME_UNSET
    private var pendingStartUs = 0L

    override fun enable(
        configuration: RendererConfiguration,
        formats: Array<out Format>,
        stream: SampleStream,
        positionUs: Long,
        joining: Boolean,
        mayRenderStartOfStream: Boolean,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        this.offsetUs = offsetUs
        pendingOffsetUs = C.TIME_UNSET
        super.enable(configuration, formats, stream, positionUs, joining, mayRenderStartOfStream, startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun replaceStream(
        formats: Array<out Format>,
        stream: SampleStream,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        pendingOffsetUs = offsetUs
        pendingStartUs = startPositionUs
        super.replaceStream(formats, stream, startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        if (pendingOffsetUs != C.TIME_UNSET && positionUs >= pendingStartUs) {
            offsetUs = pendingOffsetUs
            pendingOffsetUs = C.TIME_UNSET
        }
        super.render(positionUs - offsetUs + INITIAL_OFFSET_US - delays.subtitleUs, elapsedRealtimeUs)
    }

    private companion object {
        const val INITIAL_OFFSET_US = 1_000_000_000_000L
    }
}
