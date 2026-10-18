package app.fireplayer.player

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
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
import androidx.media3.ui.SubtitleView
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
    ): Built {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("FirePlayer/1.0 (Linux; Android) ExoPlayer")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(headers)
        val dataSourceFactory: DataSource.Factory = DefaultDataSource.Factory(context, httpFactory)

        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        val baseRenderers = TvRenderersFactory(context, passthrough)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 4_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val builder = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setHandleAudioBecomingNoisy(true)
            .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)

        if (!useAss) {
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractors)
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
            extractors.withAssMkvSupport(parserFactory, assHandler),
        )
        mediaSourceFactory.setSubtitleParserFactory(parserFactory)
        val player = builder
            .setMediaSourceFactory(mediaSourceFactory)
            .setRenderersFactory(DelayRenderersFactory(baseRenderers.withAssSupport(assHandler), delays))
            .build()
        assSubtitleView.withAssSupport(assHandler)
        assHandler.init(player)
        return Built(player, assHandler)
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
        @Suppress("DEPRECATION")
        return DefaultAudioSink.Builder(context)
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
        renderer is AssRenderer || renderer.trackType == C.TRACK_TYPE_TEXT ->
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
