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

@OptIn(UnstableApi::class)
object PlayerFactory {

    class Built(val player: ExoPlayer, val assHandler: AssHandler?)

    /**
     * Decoders for the skip preview. Its picture is decoded while the film plays, so 4K-class formats only get a
     * hardware decoder: decoding them on the processor would take it from the film and make it stutter.
     */
    private val PREVIEW_DECODERS = MediaCodecSelector { mimeType, secure, tunneling ->
        val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
        if (mimeType in HEAVY_VIDEO) all.filter { it.hardwareAccelerated } else all
    }

    private val HEAVY_VIDEO = setOf(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_DOLBY_VISION, MimeTypes.VIDEO_VP9, MimeTypes.VIDEO_AV1)

    private fun dataSourceFactory(context: Context, headers: Map<String, String>): DataSource.Factory {
        // IPTV providers often require the User-Agent TiviMate passes along, so it must win over ours.
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent ?: "FirePlayer/1.0 (Linux; Android) ExoPlayer")
            .setAllowCrossProtocolRedirects(true)
            // A server that doesn't answer in 12 s won't answer at 20; failing sooner lets the retries take over.
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
        return DefaultDataSource.Factory(context, httpFactory)
    }

    /**
     * A small video-only player for the skip preview: it loads a second or so around a jump target,
     * shows the nearest keyframe, and never plays sound or subtitles.
     */
    fun buildPreview(context: Context, headers: Map<String, String>): ExoPlayer {
        val extractors = SeekableMkvExtractorsFactory(DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true))
        val upstream = dataSourceFactory(context, headers)
        // Its connection closes as soon as a picture is loaded, so the film can have the server to itself.
        val dataSources = DataSource.Factory { IdleCloseDataSource(upstream.createDataSource(), 400) }
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSources, extractors)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1))
        val loadControl = DefaultLoadControl.Builder()
            // The keyframe and a few frames after it. Every extra second is megabytes of a high-quality
            // film that the film itself is waiting for.
            .setBufferDurationsMs(300, 600, 100, 100)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
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
    ): Built {
        val dataSourceFactory = dataSourceFactory(context, headers)

        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        val baseRenderers = TvRenderersFactory(context, passthrough)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        // As much of the film as memory allows, up to two minutes ahead, topped up all the time. High-quality
        // films fill it by size first; the stall-free stretch then depends on the connection, not a fixed
        // half minute.
        val budget = (Runtime.getRuntime().maxMemory() * 0.6).toLong().coerceIn(64L shl 20, 400L shl 20).toInt()
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
                // No back buffer: it would come out of the same memory, and a high-quality film needs all of it ahead.
                .setBackBuffer(0, false)
                .build(),
            budget,
        )

        val builder = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setHandleAudioBecomingNoisy(true)
            .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            // Keeps Wi-Fi at full speed while playing; left to itself it may save power and fall behind the film.
            .setWakeMode(C.WAKE_MODE_NETWORK)

        // Slow IPTV servers drop connections now and then; keep retrying before giving up.
        val retryPolicy = DefaultLoadErrorHandlingPolicy(5)

        if (!useAss) {
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, SeekableMkvExtractorsFactory(extractors))
                .setLoadErrorHandlingPolicy(retryPolicy)
                .setSubtitleParserFactory(pictureText.readAhead(DefaultSubtitleParserFactory()))
            val player = builder
                .setMediaSourceFactory(mediaSourceFactory)
                .setRenderersFactory(DelayRenderersFactory(baseRenderers, delays))
                .build()
            return Built(player, null)
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
            .setRenderersFactory(DelayRenderersFactory(baseRenderers.withAssSupport(assHandler), delays))
            .build()
        assSubtitleView.withAssSupport(assHandler)
        assHandler.init(player)
        return Built(player, assHandler)
    }
}

/**
 * The film's buffer rules (see [PlayerFactory.build]). After a stall it waits for 4 seconds of video; when stalls
 * keep coming (a film the connection can only just keep up with) it waits for 10, then 20, so playback stops now
 * and then for a little longer instead of every few seconds.
 */
@OptIn(UnstableApi::class)
private class FilmLoadControl(private val inner: DefaultLoadControl, private val budgetBytes: Int) : LoadControl {

    // When recent stalls began (elapsedRealtime), on the playback thread only.
    private val stalls = ArrayDeque<Long>()
    private var stallId = C.TIME_UNSET

    private val window = Timeline.Window()
    private val period = Timeline.Period()

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        // Live channels can't build up a cushion (the stream only comes as fast as it plays), so they keep the usual rule.
        if (!parameters.rebuffering || isLive(parameters)) return inner.shouldStartPlayback(parameters)
        val now = SystemClock.elapsedRealtime()
        if (parameters.lastRebufferRealtimeMs != stallId) {
            stallId = parameters.lastRebufferRealtimeMs
            stalls.addLast(now)
        }
        while (stalls.isNotEmpty() && now - stalls.first() > STALL_MEMORY_MS) stalls.removeFirst()
        val cushionUs = when (stalls.size) {
            0, 1 -> 4_000_000L
            2 -> 10_000_000L
            else -> 20_000_000L
        }
        // Memory full means nothing more can come before playing on.
        return parameters.bufferedDurationUs >= cushionUs || inner.allocator.totalBytesAllocated >= budgetBytes
    }

    private fun isLive(parameters: LoadControl.Parameters): Boolean {
        if (parameters.targetLiveOffsetUs != C.TIME_UNSET) return true
        val timeline = parameters.timeline
        if (timeline.isEmpty) return false
        val windowIndex = timeline.getPeriodByUid(parameters.mediaPeriodId.periodUid, period).windowIndex
        return timeline.getWindow(windowIndex, window).isLive()
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters) = inner.shouldContinueLoading(parameters)

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
    ).map { wrap(it) }.toTypedArray()

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
