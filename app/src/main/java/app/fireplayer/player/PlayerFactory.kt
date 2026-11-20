package app.fireplayer.player

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.util.Log
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
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
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
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.ui.SubtitleView
import app.fireplayer.subs.EmbeddedCues
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
    /** Subtitle files added to the film, by their track id: the file's path and its type ([TextOffsetRenderer]). */
    @Volatile var textFiles: Map<String, Pair<String, String>> = emptyMap()
}

/**
 * How far along the wait before the film plays (or plays on) is, for the spinner. [fraction] is the share already
 * loaded, [slowLine] means the film arrives slower than it plays, and [atMs] (elapsedRealtime) is when this was worked
 * out: one more than a moment old is from an earlier wait.
 */
class BufferWait(val fraction: Float, val slowLine: Boolean, val atMs: Long)

@OptIn(UnstableApi::class)
object PlayerFactory {

    class Built(
        val player: ExoPlayer,
        val assHandler: AssHandler?,
        val bufferWait: () -> BufferWait?,
        /** The memory the film's loaded part holds now, in bytes (for the FirePlayerDiag log). */
        val filmBytes: () -> Long,
        private val squeezer: (Int) -> Int,
        /** libass's overlay over the film, put up the first time a styled track is chosen; null without libass. */
        val assOverlay: AssOverlay?,
        /** How long a stretch of the film is kept behind where it plays now, in ms (see [BackBuffer]). */
        val keptBehindMs: () -> Long = { 0L },
        /** What the film's buffer fills to now, in bytes: its budget, or less while squeezed. */
        val fillBytes: () -> Long = { 0L },
        private val roomer: (Long, Long) -> Int = { _, _ -> 0 },
        /** The film's budget now, in bytes (what it fills to when not squeezed); 0 when not known. */
        val budgetBytes: () -> Int = { 0 },
        /** How the film is arriving and stalling, for the log and the supply check ([FilmFlow]); null for none. */
        val flow: () -> FilmFlow? = { null },
    ) {
        /**
         * Squeezes the film's buffer while the TV is short of memory: [step] 0 is the whole budget, 1 three quarters,
         * 2 half (see [FilmMemory.capFor]). Gives back what the buffer now fills to, in bytes.
         */
        fun squeeze(step: Int): Int = squeezer(step)

        /**
         * Room has been made for the film since its player was built ([FilmRoom]): [freeBytes] are free now, and
         * [gainedBytes] were seen to come back (0: not known). A buffer held down by the memory free at its start
         * gets what that memory allows now, up to its tier's ([FilmMemory.afterRoom]). Gives back the new budget in
         * bytes, or 0 when it stays as it is.
         */
        fun roomMade(freeBytes: Long, gainedBytes: Long): Int = roomer(freeBytes, gainedBytes)
    }

    /**
     * Jumps land on a keyframe up to 2 s before the time asked for (never after; 2 s before when there's none in
     * between), so less video has to be downloaded and decoded before the picture moves.
     */
    val FILM_SEEK = SeekParameters(2_000_000L, 0L)

    /** The biggest picture the skip preview asks for; on a TV short of memory, the biggest it decodes at all. */
    const val PREVIEW_MAX_WIDTH = 1920
    const val PREVIEW_MAX_HEIGHT = 1080

    /**
     * Decoders for the skip preview. Its picture is decoded while the film plays, so 4K-class formats only get a
     * hardware decoder: decoding them on the processor would take it from the film and make it stutter.
     */
    private val PREVIEW_DECODERS = MediaCodecSelector { mimeType, secure, tunneling ->
        val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
        if (mimeType in HEAVY_VIDEO) all.filter { it.hardwareAccelerated } else all
    }

    /** Closed captions a live .ts stream may carry in its video: CC1 first, then digital caption service 1. */
    private val LIVE_CAPTION_FORMATS = listOf(
        Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA608).setAccessibilityChannel(1).build(),
        Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_CEA708).setAccessibilityChannel(1).build(),
    )

    private val HEAVY_VIDEO = setOf(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_DOLBY_VISION, MimeTypes.VIDEO_VP9, MimeTypes.VIDEO_AV1)

    private const val FILM_USER_AGENT = "FirePlayer/1.0 (Linux; Android) ExoPlayer"
    private const val CONNECT_TIMEOUT_MS = 12_000

    private fun dataSourceFactory(
        context: Context,
        headers: Map<String, String>,
        serverSwitch: ServerSwitch? = null,
        readOnlyMemory: Boolean = false,
        meter: ByteMeter? = null,
        readTimeoutMs: Int = 12_000,
        twoRanges: TwoRanges? = null,
    ): DataSource.Factory {
        // IPTV providers often require the User-Agent TiviMate passes along, so it must win over ours.
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent ?: FILM_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            // A server that doesn't answer in 12 s won't answer at 20; failing sooner lets the retries take over. The
            // same for one that goes quiet in the middle of the film: a skip can't get past a read that's waiting.
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(readTimeoutMs)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
        // The film's bytes are counted as they come, for the download speed in the log (DownloadRate), and its reads
        // timed, for the speed while really loading (LoaderRate).
        if (meter != null) httpFactory.setTransferListener(meter)
        // With "Faster downloads" on, a film file is read with up to two range requests at a time (TwoRanges); the
        // timing goes around both, so the loader's speed is what the two bring together.
        val film: DataSource.Factory = if (twoRanges == null) httpFactory else {
            if (meter != null) twoRanges.onHelperBytes = meter::add
            val all = headers.filterKeys { !it.equals("User-Agent", true) } +
                ("User-Agent" to (userAgent ?: FILM_USER_AGENT))
            TwoRangeDataSource.Factory(httpFactory, twoRanges, all, CONNECT_TIMEOUT_MS, readTimeoutMs)
        }
        val network: DataSource.Factory = if (meter != null) LoaderRate.Timed.Factory(film, meter.loader) else film
        return DefaultDataSource.Factory(context, RedirectMemoryDataSource.Factory(network, serverSwitch, readOnlyMemory))
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
        val trackSelector = DefaultTrackSelector(context)
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .setMaxVideoSize(PREVIEW_MAX_WIDTH, PREVIEW_MAX_HEIGHT)
                // A film with no picture that small is still previewed at its own size, except on a TV short of memory
                // (under 2.6 GiB): a second 4K decoder there, and its 25 MB pictures beside the film's, take what the
                // film needs. A 4K-only film gets no preview on those (SeekPreview says so once) and skips as without.
                .setExceedVideoConstraintsIfNecessary(!FilmMemory.tier(context).short)
                .build(),
        )
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setRenderersFactory(renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build()
        // Jumping to the nearest keyframe is what makes a preview quick.
        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        player.volume = 0f
        player.playWhenReady = false
        return player
    }

    /**
     * Films and live channels claim the sound while they play (Android's audio focus), so another app's film or music
     * (Stremio, Netflix, Spotify) pauses instead of carrying on underneath, and Alexa answering pauses or lowers them for
     * a moment. Seen on the Fire TV: a Stremio film went on playing behind the live miniature, which never claimed it.
     */
    private val MEDIA_AUDIO = androidx.media3.common.AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .build()

    /**
     * A player for live channels (Live TV guide miniature and full screen share this one player). Same decoders and
     * sound handling as films (hardware first, FFmpeg for sound the TV can't decode, passthrough as set), but a short
     * live buffer so a channel starts in a second or two, and plain HTTP: live links are often tokenised, so a
     * remembered redirect ([RedirectMemoryDataSource]) could replay an expired token. No libass, no frame-rate or
     * vsync machinery here: those belong to the full-screen film player.
     *
     * [delays]: the audio delay (Audio delay in full screen) works as the film player's does, by giving the video
     * renderer a shifted clock ([OffsetRenderer]); subtitle delays don't apply to live captions. [buffer]: how much is
     * loaded ahead (Live TV buffer).
     */
    fun buildLive(
        context: Context,
        headers: Map<String, String>,
        passthrough: Boolean,
        delays: Delays? = null,
        buffer: LiveBuffer = LiveBuffer.MEDIUM,
        /** The live session's own buffer rules (PlayerHost's LiveLoadControl); [buffer] through Media3's alone when null. */
        liveLoadControl: LoadControl? = null,
        /**
         * The player asks for the TV's sound itself, and pauses when the sound's way out goes (the one live player).
         * Multiview's pictures don't: several players asking would take the sound from one another (the loser pauses),
         * so Multiview holds it once for all of them and only the selected picture is heard.
         */
        audioFocus: Boolean = true,
    ): ExoPlayer {
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent ?: "FirePlayer/2.0 (Linux; Android) ExoPlayer")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(10_000)
            .setDefaultRequestProperties(headers.filterKeys { !it.equals("User-Agent", true) })
        // TV closed captions ride inside the video (CEA-608 "CC1", and CEA-708 on digital channels). Media3 only looks
        // for them in a .ts stream that declares them, which IPTV streams rarely do; these say "look anyway".
        // (HLS streams are already looked at this way by Media3's HLS reader.)
        val extractors = DefaultExtractorsFactory().setTsSubtitleFormats(LIVE_CAPTION_FORMATS)
        val mediaSources = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http), extractors)
            .setLoadErrorHandlingPolicy(LiveErrorPolicy)
        val loadControl = liveLoadControl ?: DefaultLoadControl.Builder()
            // Medium starts after 1.5 s of picture (3 s after a stall) and keeps up to 30 s ahead.
            .setBufferDurationsMs(buffer.minMs, buffer.maxMs, buffer.startMs, buffer.afterStallMs)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val renderers = LiveRenderersFactory(context, passthrough)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSources)
            .setRenderersFactory(if (delays == null) renderers else LiveDelayRenderersFactory(renderers, delays))
            .setLoadControl(loadControl)
            .setAudioAttributes(MEDIA_AUDIO, /* handleAudioFocus = */ audioFocus)
            .setHandleAudioBecomingNoisy(audioFocus)
            .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    /**
     * The live player's own load retries: Media3's three, except for an answer that says the stream isn't there or
     * isn't allowed ([app.fireplayer.playback.LiveRefusal.refuses]: 404, 403...). Asking such a server again at once
     * only made each failed try four requests long; the live host says why and tries again slowly itself.
     */
    private object LiveErrorPolicy : DefaultLoadErrorHandlingPolicy(3) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
            if (app.fireplayer.playback.LiveRefusal.refuses(httpCodeOf(loadErrorInfo.exception))) C.TIME_UNSET
            else super.getRetryDelayMsFor(loadErrorInfo)
    }

    /** The HTTP status a failure carries somewhere in its causes (0: none). Only the number: never the address. */
    fun httpCodeOf(e: Throwable?): Int {
        var c = e
        var depth = 0
        while (c != null && depth++ < 8) {
            if (c is HttpDataSource.InvalidResponseCodeException) return c.responseCode
            c = c.cause
        }
        return 0
    }

    /**
     * Builds a player tuned for streaming on a Fire TV:
     * hardware decoders first, FFmpeg for audio the device can't decode, a deeper
     * buffer for IPTV streams, and libass for styled ASS/SSA subtitles.
     */
    private fun tapped(factory: ExtractorsFactory, embeddedCues: EmbeddedCues?): ExtractorsFactory =
        embeddedCues?.wrap(factory) ?: factory

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
        /** Whether the TV takes HDR10+, which the film's video renderer keeps from the decoder when it doesn't. */
        hdr10Plus: Hdr10PlusGate,
        /** When the TV last switched refresh rate, which passthrough sound waits out (see [SwitchTolerantAudioSink]). */
        displaySwitch: DisplaySwitch,
        /** A copy of the sound for subtitle auto sync, taken only while it listens (see [SpeechTap]). */
        speechTap: SpeechTap,
        /** Where the timing of the film's own subtitle tracks goes as they load, for auto sync (see [EmbeddedCues]). */
        embeddedCues: EmbeddedCues? = null,
        /** Where damaged stretches of a film the player went past are noted, so the viewer can be told. */
        damageSkips: DamageSkips? = null,
        /** Where the video renderer counts the frames lost that Media3 doesn't (see [PacedVideoRenderer]). */
        frameCounts: FrameCounts = FrameCounts(),
        /** Counts the film's bytes as they come from the network (see [DownloadRate]). */
        byteMeter: ByteMeter? = null,
        /** The film's link can be had afresh from its add-on (see [FreshLinks]): a refused one isn't asked again. */
        freshLinks: Boolean = false,
        /**
         * The budget of the player this one takes the place of in the same moment (see [FilmMemory.plan]); 0 for a
         * player that follows none.
         */
        keepBudgetBytes: Int = 0,
        /** "Faster downloads (2 connections)" is on: this player's films may be read with two requests ([TwoRanges]). */
        twoRanges: TwoRanges? = null,
    ): Built {
        IndexCache.dir = context.cacheDir
        val serverSwitch = ServerSwitch()
        val dataSourceFactory = dataSourceFactory(
            context, headers, serverSwitch, meter = byteMeter,
            readTimeoutMs = if (freshLinks) FreshLinks.READ_TIMEOUT_MS else 12_000,
            twoRanges = twoRanges,
        )

        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)

        val baseRenderers = TvRenderersFactory(context, passthrough, displaySwitch, vsync, hdr10Plus, speechTap, frameCounts)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        // As much of the film as this TV can spare, up to two minutes ahead, topped up all the time. High-quality
        // films fill it by size first; the stall-free stretch then depends on the connection, not a fixed
        // half minute. What the TV can spare goes by its memory in all (FilmMemory), not the app's heap limit: Fire OS
        // allows 384 MB even on a Stick with 1.7 GB, where a 230 MB buffer left the TV short of memory and the film
        // losing frames.
        // And by what's free as the film starts: the decoder's own 4K frames (up to 200 MB) come out of the same memory.
        val free = FilmMemory.freeNow(context)
        val plan = FilmMemory.plan(FilmMemory.tier(context), Runtime.getRuntime().maxMemory(), free, keepBudgetBytes)
        val budget = plan.budgetBytes
        // What's kept behind comes out of the same memory as what's ahead (for a 4K remux, about 8 MB a second), so
        // TVs with less of it keep less, or the room ahead would shrink to a few seconds. Below 10 s, going back one
        // press (10 s, landing up to 2 s early) loads that stretch again (2 s in a 4K film, measured).
        val backBufferMs = plan.backBufferMs
        Log.i("FirePlayer", "Film buffer: ${plan.tier} memory, ${free shr 20} MiB free, ${budget shr 20} MiB" +
            (if (plan.byFreeMemory) " (held down by free memory, ${plan.tierBytes shr 20} MiB with room)" else "") +
            ", ${backBufferMs / 1000} s kept behind" +
            (if (plan.backCapBytes > 0) " up to ${plan.backCapBytes shr 20} MiB" else ""))
        // Owned here so the film's buffer can give memory back while the TV is short of it (FilmLoadControl.squeeze).
        val allocator = DefaultAllocator(/* trimOnReset = */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val loadControl = FilmLoadControl(
            DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setBufferDurationsMs(
                    /* minBufferMs = */ 120_000,
                    /* maxBufferMs = */ 120_000,
                    // Start quickly after a skip; after a stall, FilmLoadControl decides.
                    /* bufferForPlaybackMs = */ 1_000,
                    /* bufferForPlaybackAfterRebufferMs = */ 4_000,
                )
                // Media3's own rule stops at the tier's size; FilmLoadControl stops at the budget, which is the same
                // unless little memory was free just now, and can then be raised once room has been made.
                .setTargetBufferBytes(plan.tierBytes)
                .setPrioritizeTimeOverSizeThresholds(false)
                // The last 15 s are kept (less on TVs with little memory), so going back one press (10 s, landing up
                // to 2 s early) plays at once instead of loading again; more would come out of the memory a
                // high-quality film needs ahead. Kept from the keyframe before, without which the picture there
                // couldn't be decoded.
                .setBackBuffer(backBufferMs, /* retainBackBufferFromKeyframe = */ true)
                .build(),
            allocator,
            budget,
            plan.tierBytes,
            free,
            serverSwitch,
            byteMeter?.loader,
        )

        val builder = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(MEDIA_AUDIO, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            // Keeps Wi-Fi at full speed while playing; left to itself it may save power and fall behind the film.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekParameters(FILM_SEEK)

        // Slow IPTV servers drop connections now and then; keep retrying before giving up.
        val retryPolicy = FilmRetryPolicy(serverSwitch, freshLinks)

        if (!useAss) {
            val parsers = pictureText.readAhead(DefaultSubtitleParserFactory())
            // Set on the extractors themselves too: the MKV wrapper doesn't pass this setting on to them.
            extractors.setSubtitleParserFactory(parsers)
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, tapped(SeekableMkvExtractorsFactory(extractors, damageSkips?.let { it::add }), embeddedCues))
                .setLoadErrorHandlingPolicy(retryPolicy)
                .setSubtitleParserFactory(parsers)
            val sources = backCapped(mediaSourceFactory, plan, loadControl)
            val player = builder
                .setMediaSourceFactory(sources)
                .setRenderersFactory(DelayRenderersFactory(baseRenderers, delays, vsync))
                .build()
            return Built(player, null, { loadControl.wait }, { loadControl.allocatedBytes }, loadControl::squeeze, null, keptBehind(sources, plan), { loadControl.fillBytes }, loadControl::roomMade, { loadControl.budget }, { loadControl.flow })
        }

        // Same wiring as ass-media's buildWithAssSupport, plus our delay wrapper.
        val assHandler = AssHandler(
            AssRenderType.OVERLAY_OPEN_GL,
            AssHandlerConfig(maxRenderPixels = 1920 * 1080),
        )
        val parserFactory = AssSubtitleParserFactory(assHandler)
        val mediaSourceFactory = DefaultMediaSourceFactory(
            dataSourceFactory,
            tapped(SeekableMkvExtractorsFactory(extractors.withAssMkvSupport(parserFactory, assHandler), damageSkips?.let { it::add }), embeddedCues),
        )
        mediaSourceFactory.setSubtitleParserFactory(parserFactory)
        mediaSourceFactory.setLoadErrorHandlingPolicy(retryPolicy)
        val sources = backCapped(mediaSourceFactory, plan, loadControl)
        val player = builder
            .setMediaSourceFactory(sources)
            .setRenderersFactory(DelayRenderersFactory(baseRenderers.withAssSupport(assHandler), delays, vsync))
            .build()
        assHandler.init(player)
        // libass's OpenGL overlay goes over the film only once a styled track is chosen (Built.assOverlay): most films
        // have none, and it would otherwise keep its own render thread, GL context and full-screen buffer all film long.
        val overlay = AssOverlay { assSubtitleView.withAssSupport(assHandler) }
        return Built(player, assHandler, { loadControl.wait }, { loadControl.allocatedBytes }, loadControl::squeeze, overlay, keptBehind(sources, plan), { loadControl.fillBytes }, loadControl::roomMade, { loadControl.budget }, { loadControl.flow })
    }

    /** The film's sources, keeping no more behind than the plan's cap in memory ([BackBuffer]); as they are without one. */
    private fun backCapped(sources: MediaSource.Factory, plan: FilmBufferPlan, loadControl: FilmLoadControl): MediaSource.Factory =
        if (plan.backCapBytes <= 0L) sources
        else BackCappedSources(sources, plan.backCapBytes, plan.backBufferMs * 1000L, { loadControl.allocatedBytes }, { loadControl.stalledLately() })

    private fun keptBehind(sources: MediaSource.Factory, plan: FilmBufferPlan): () -> Long =
        (sources as? BackCappedSources)?.let { capped -> { capped.keptBehindMs } } ?: { plan.backBufferMs.toLong() }
}

/**
 * The film's load retries. IPTV servers often refuse a reconnect (403 and the like) for a few seconds while the old
 * connection still counts, so a refused load of the film is tried every second instead of at 0, 0, 1, 3, 6 and 10 s,
 * within the same 10 s or so before the error reaches PlayerActivity. A film that isn't there (404, 410) isn't tried
 * again at all, and a server that doesn't answer is tried three times rather than six, each try being a full timeout.
 * Everything else (dropped connections, 400/401, HLS and DASH) keeps Media3's spacing and limit.
 */
@OptIn(UnstableApi::class)
private class FilmRetryPolicy(
    private val serverSwitch: ServerSwitch,
    /** A refused link is final here: its add-on is asked for a fresh one instead (see [FreshLinks]). */
    private val freshLinks: Boolean = false,
) : DefaultLoadErrorHandlingPolicy(RETRIES) {
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
        // From the add-ons: a refused link (401, 403 too) stays refused. The error shows at once, and the fresh link
        // is asked for then, not after ten seconds of asking the old one.
        if (freshLinks && progressive && FreshLinks.refused(code)) {
            refused = false
            return C.TIME_UNSET
        }
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
 * starting at once and stopping again every few seconds. Stalls that keep coming in the same film also make the next
 * wait longer, up to all of the film that fits in this TV's memory ([StallCushion]), and leave less of the memory to
 * what's kept behind ([stalledLately], [BackBuffer.keepUs]).
 *
 * While the TV is short of memory the buffer fills to less than its budget ([squeeze]), and the memory it lets go of
 * is freed instead of kept for reuse. [allocator] is [inner]'s, owned by [PlayerFactory.build] for that.
 *
 * [plannedBytes] is the budget as the player was built, [tierBytes] what this TV's tier allows at most ([inner] stops
 * loading there). They differ only when little memory was free at that moment ([startFreeBytes]; 0 when not known);
 * the budget is then raised once room has been made ([roomMade]).
 */
@OptIn(UnstableApi::class)
private class FilmLoadControl(
    private val inner: DefaultLoadControl,
    private val allocator: DefaultAllocator,
    plannedBytes: Int,
    private val tierBytes: Int,
    private val startFreeBytes: Long,
    private val serverSwitch: ServerSwitch,
    /** Told when loading is held back, so the log's loader speed leaves the pause out ([LoaderRate.held]). */
    private val loaderRate: LoaderRate? = null,
) : LoadControl {

    /** The film's budget: [plannedBytes], or more once room was made. Set on the main thread, read on the playback thread. */
    @Volatile private var budgetBytes = plannedBytes
    /** How far the buffer is squeezed now ([squeeze]). Main thread. */
    private var step = 0
    /**
     * The most seen to come back to the TV at one time since the player was built ([roomMade]), in bytes. The most,
     * not the sum: two collections close together can each see part of the same memory come back. Main thread.
     */
    private var gainedBytes = 0L

    /** The film's budget now, in bytes. */
    val budget: Int get() = budgetBytes
    /** The budget was raised: how much of the film fits is measured afresh. Set on the main thread, taken on the playback thread. */
    @Volatile private var raised = false

    /**
     * What the buffer fills to: [budgetBytes], or less while the TV is short of memory. Set on the main thread, read on
     * the playback thread.
     */
    @Volatile private var capBytes = plannedBytes
    // When memory let go of was last freed while squeezed (elapsedRealtime), on the playback thread.
    private var trimmedAt = 0L

    // This film's stalls so far, on the playback thread only.
    private val stalls = StallCount()
    private var stallId = C.TIME_UNSET
    private var stallLogged = C.TIME_UNSET

    /** How the film is arriving and stalling as of the last check, for the main thread ([FilmFlow]). */
    @Volatile var flow: FilmFlow? = null
        private set

    /** The film has stalled lately: less is kept behind ([BackBuffer.keepUs]). Playback thread. */
    fun stalledLately(): Boolean = stalls.count(SystemClock.elapsedRealtime()) > 0

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

    // How much of this film was loaded ahead the last time the buffer was full (what fits, FilmMemory.roomUs); 0 until
    // it has been, and for another film. On the playback thread only.
    private var fullUs = 0L
    private var fullPeriod: Any? = null

    /** The memory the film's loaded part holds now, in bytes: any thread (Media3's allocator answers under its lock). */
    val allocatedBytes: Long get() = inner.allocator.totalBytesAllocated.toLong()

    /** What the buffer fills to now, in bytes. */
    val fillBytes: Long get() = capBytes.toLong()

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        // Live channels can't build up a cushion (the stream only comes as fast as it plays), so they keep the usual rule.
        if (isLive(parameters)) return inner.shouldStartPlayback(parameters)
        // All of this episode is loaded, and what's loading is the next one (queued after it): nothing more of this
        // one can come, so waiting on would only wait on the next episode's server.
        if (loadedToEnd(parameters)) return true
        measure(parameters, waiting = true)
        forgetRoomIfRaised()
        // Memory full means nothing more can come before playing on.
        val limit = FilmMemory.fillLimit(capBytes, budgetBytes, parameters.bufferedDurationUs)
        if (allocator.totalBytesAllocated >= limit) return true
        val buffered = parameters.bufferedDurationUs
        val rate = fillRate()
        val now = SystemClock.elapsedRealtime()
        // What fits: as measured when the buffer was last full. Before it ever was, worked out from what's loaded, but
        // only at a start or after a jump: in a stall the memory also holds what's kept behind, which would make the
        // room look smaller than it is (and the wait too short, on a line too slow to ever fill the buffer).
        val measured = if (fullPeriod == parameters.mediaPeriodId.periodUid) fullUs else 0L
        val room = if (parameters.rebuffering) measured
        else FilmMemory.roomUs(measured, buffered, allocator.totalBytesAllocated.toLong(), limit.toLong())
        val wantedUs = if (!parameters.rebuffering) {
            // Starting, or after a jump: a second's worth, once it's clear how fast the rest is coming (or plenty).
            if (rate == null) 10_000_000L else maxOf(START_US, cushionFor(rate))
        } else {
            StallCushion.wantedUs(stallsNow(parameters, now), rate)
        }
        // Never for all that fits in this TV's memory at a start or after a jump: half of it, where that's a few
        // seconds of a high-quality film. After a stall, more of it with each stall in the same film (StallCushion).
        val neededUs = if (parameters.rebuffering) StallCushion.waitUs(stallsNow(parameters, now), wantedUs, room)
        else FilmMemory.waitUs(wantedUs, room, START_US)
        if (parameters.rebuffering && stallLogged != stallId) {
            stallLogged = stallId
            // Once per stall. No links, no titles.
            Log.i(PlaybackDiag.TAG, "stall " + stallsNow(parameters, now) + " in this film: playing on with " +
                neededUs / 1000 + "ms ahead (wanted " + wantedUs / 1000 + "ms, " +
                (if (room > 0) "" + room / 1000 + "ms fit" else "what fits isn't measured: the full buffer ends the wait") +
                ", arriving at " + (rate?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "?") + "x)")
        }
        noteFlow(rate, now, if (parameters.rebuffering) neededUs / 1000 else -1L)
        // Whichever comes first ends the wait: enough film, or full memory.
        val fraction = maxOf(buffered.toFloat() / neededUs, allocator.totalBytesAllocated.toFloat() / limit)
        wait = BufferWait(fraction.coerceIn(0f, 1f), rate != null && rate < 1f, now)
        return buffered >= neededUs
    }

    /** Keeps [flow] up to date; a new one only when something in it changed. [stallWaitMs] under 0: as it was. */
    private fun noteFlow(measured: Float?, now: Long, stallWaitMs: Long) {
        // To a hundredth: it moves a little with every check.
        val rate = measured?.let { (it * 100f).toInt() / 100f }
        val was = flow
        val wait = if (stallWaitMs >= 0) stallWaitMs else was?.stallWaitMs ?: 0L
        val count = stalls.count(now)
        if (was == null || was.fillRate != rate || was.stalls != count || was.stallWaitMs != wait) flow = FilmFlow(rate, count, wait)
    }

    /** How many stalls this film has had, this one counted once (Media3 names each by when it began). */
    private fun stallsNow(parameters: LoadControl.Parameters, now: Long): Int {
        if (parameters.lastRebufferRealtimeMs != stallId) {
            stallId = parameters.lastRebufferRealtimeMs
            return stalls.stalled(now)
        }
        return stalls.count(now).coerceAtLeast(1)
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

    private fun cushionFor(rate: Float): Long = StallCushion.forRate(rate)

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
        forgetRoomIfRaised()
        // Media3's own rule stops at the tier's size (and at two minutes ahead); the budget, when free memory holds it
        // under that, and the lower cap while squeezed, stop it here.
        val wanted = inner.shouldContinueLoading(parameters)
        noteFlow(fillRate(), lastAtMs, -1L)
        val cap = capBytes
        val budget = budgetBytes
        if (cap < budget) freeLetGo(cap)
        val allocated = allocator.totalBytesAllocated
        val limit = FilmMemory.fillLimit(cap, budget, parameters.bufferedDurationUs)
        // Full by size (not by the two minutes ahead that a lighter film reaches first): this much of it fits. (A live
        // channel's is never read: shouldStartPlayback leaves those to Media3's own rule.)
        if (allocated >= limit && parameters.bufferedDurationUs >= FilmMemory.ROOM_MIN_US) {
            fullUs = parameters.bufferedDurationUs
            fullPeriod = parameters.mediaPeriodId.periodUid
        }
        val full = cap < tierBytes && allocated >= limit
        loading = wanted && !full
        if (!loading) loaderRate?.held()
        return loading
    }

    /** After a raise, what fit in the smaller budget says nothing about the new one: measured again when next full. */
    private fun forgetRoomIfRaised() {
        if (!raised) return
        raised = false
        fullUs = 0L
        fullPeriod = null
    }

    /**
     * Lowers what the buffer fills to while the TV is short of memory, or raises it back: [step] 0 is the whole budget,
     * 1 three quarters, 2 half ([FilmMemory.capFor]). Memory loaded and let go of is no longer kept beyond the new
     * size; what's in use (the film loaded ahead and kept behind) is let go of as the film plays on. On the main
     * thread; gives back the new size in bytes.
     */
    fun squeeze(step: Int): Int {
        this.step = step
        val cap = FilmMemory.capFor(budgetBytes, step)
        capBytes = cap
        allocator.setTargetBufferSize(cap)
        return cap
    }

    /**
     * Room has been made since the player was built: [freeBytes] are free now, and [gained] bytes were seen to come
     * back (0: not known). A budget held under the tier's by the memory free at the start is read again
     * ([FilmMemory.afterRoom]: only ever more), and the squeeze of the moment applies to the new one. On the main
     * thread; gives back the new budget in bytes, or 0 when it stays.
     */
    fun roomMade(freeBytes: Long, gained: Long): Int {
        if (gained > gainedBytes) gainedBytes = gained
        val now = budgetBytes
        val next = FilmMemory.afterRoom(now, tierBytes, freeBytes, allocatedBytes, startFreeBytes, gainedBytes)
        if (next <= now) return 0
        budgetBytes = next
        raised = true
        val cap = FilmMemory.capFor(next, step)
        capBytes = cap
        allocator.setTargetBufferSize(cap)
        return next
    }

    /**
     * Squeezed: the memory the film let go of since (what's played past, and kept behind no longer) is freed rather
     * than kept for reuse, at most once a second. Media3 frees it only on a jump. The cap is given to the allocator
     * again first: Media3 sets it back to the tier's size when tracks are chosen, which can cross a squeeze made on
     * the main thread at the same moment.
     */
    private fun freeLetGo(cap: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - trimmedAt < TRIM_EVERY_MS) return
        trimmedAt = now
        allocator.setTargetBufferSize(cap)
        allocator.trim()
    }

    override fun shouldContinuePreloading(timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long) =
        inner.shouldContinuePreloading(timeline, mediaPeriodId, bufferedDurationUs)

    override fun onPrepared(playerId: PlayerId) = inner.onPrepared(playerId)

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<ExoTrackSelection?>,
    ) {
        inner.onTracksSelected(parameters, trackGroups, trackSelections)
        // Media3 sets the memory kept for reuse back to the tier's size here; it stays at what the buffer fills to
        // (the budget, or the cap while squeezed).
        allocator.setTargetBufferSize(capBytes)
    }

    override fun onStopped(playerId: PlayerId) = inner.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = inner.onReleased(playerId)

    override fun getAllocator(): Allocator = allocator

    override fun getBackBufferDurationUs(playerId: PlayerId) = inner.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId) = inner.retainBackBufferFromKeyframe(playerId)

    private companion object {
        const val TRIM_EVERY_MS = 1_000L
        const val START_US = 1_000_000L
        // Loading time needed before the speed is trusted.
        const val MEASURE_MS = 700L
        const val RATE_MEMORY_MS = 30_000L
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
/** Renderers for live channels: the film's decoders and sound choice, without its pacing and speech taps. */
private class LiveRenderersFactory(context: Context, private val passthrough: Boolean) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? {
        if (passthrough) return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
        @Suppress("DEPRECATION")
        return DefaultAudioSink.Builder()
            .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }
}

private class TvRenderersFactory(
    context: Context,
    private val passthrough: Boolean,
    private val displaySwitch: DisplaySwitch,
    private val vsync: VsyncFix,
    private val hdr10Plus: Hdr10PlusGate,
    private val speechTap: SpeechTap,
    private val frameCounts: FrameCounts,
) : DefaultRenderersFactory(context) {
    /** Media3's video renderer, with the same settings, as [PacedVideoRenderer]. */
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: java.util.ArrayList<Renderer>,
    ) {
        val start = out.size
        super.buildVideoRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener,
            allowedVideoJoiningTimeMs, out,
        )
        val i = (start until out.size).firstOrNull { out[it] is MediaCodecVideoRenderer } ?: return
        out[i] = PacedVideoRenderer(
            MediaCodecVideoRenderer.Builder(context)
                .setCodecAdapterFactory(codecAdapterFactory)
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY),
            vsync,
            frameCounts,
            hdr10Plus,
        )
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink? {
        if (passthrough) {
            return super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                ?.let { SpeechTapAudioSink(SwitchTolerantAudioSink(it, displaySwitch), speechTap) }
        }
        // Without a context the sink uses these fixed capabilities instead of what the TV reports,
        // so surround formats get decoded to PCM rather than passed through.
        @Suppress("DEPRECATION")
        return SpeechTapAudioSink(
            DefaultAudioSink.Builder()
                .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .build(),
            speechTap,
        )
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
        // The lines a delay above zero would make the renderer miss are handed to it again ([TextOffsetRenderer]).
        renderer.trackType == C.TRACK_TYPE_TEXT ->
            TextOffsetRenderer(renderer, { -delays.subtitleUs }) { id ->
                delays.textFiles.entries.firstOrNull { (k, _) -> id == k || id.endsWith(":$k") }?.value
            }
        renderer.trackType == C.TRACK_TYPE_VIDEO ->
            OffsetRenderer(renderer) { delays.audioUs }
        else -> renderer
    }
}

/**
 * The live player's renderers with the audio delay, done as [DelayRenderersFactory] does it for films: the video
 * renderer sees a clock shifted by [Delays.audioUs], which moves the sound against the picture while playing.
 */
@OptIn(UnstableApi::class)
private class LiveDelayRenderersFactory(
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
    ).map { if (it.trackType == C.TRACK_TYPE_VIDEO) OffsetRenderer(it) { delays.audioUs } else it }.toTypedArray()
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
