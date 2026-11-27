package app.fireplayer.player

import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer.CodecMaxValues

/**
 * Media3's video renderer, showing each frame on its own refresh of a TV switched to the film's rate.
 *
 * Where Media3 can't line frames up with the TV's refreshes (see [VsyncFix]), it sends each one out at the time the
 * film gives it. Matroska gives whole milliseconds, so frames of a 24 fps film are 41 or 42 ms apart; when the TV's
 * refreshes fall close to those times, that half millisecond tips frames onto the refresh before or after, and pairs
 * of frames land on one refresh while the next shows nothing (one interval in ten on a 4K film, measured on a Fire TV
 * Stick 4K Plus). Here the TV's refreshes are timed from when frames actually reached the screen, and each frame goes
 * to the refresh after the one before; the film's own times only move that on by a refresh when the two have drifted,
 * on average over the latest frames, more than [FramePacer.SLACK] of one apart, as they must when the film's rate isn't
 * quite the TV's (or at once when a frame is a whole refresh off: one before it never came). When the screen's times
 * don't fit the refresh measured, frames go out on Media3's times and [VsyncFix] measures again, until they fit again
 * (see [FramePacer]).
 *
 * It also counts the frames lost that Media3 doesn't ([counts]): frames the pacer held a refresh longer or put on the
 * refresh of the frame before (that one isn't sent at all: the screen would only have shown it in place of the frame
 * before, and the decoder gets its buffer back sooner), and frames the decoder never gave ([FrameGaps]). A frame
 * Media3 waits on as the first one shown (after a jump, a new decoder or surface, the next film) is always sent: until
 * one is shown Media3 isn't ready, and skipping it would only hold the picture back.
 *
 * It also keeps HDR10+ from the decoder while the TV isn't to get it ([hdr10Plus], [Hdr10PlusMask]), and for a whole
 * film whose HDR10+ would make the TV's picture flash ([Hdr10PlusWatch]).
 *
 * And it gives a Dolby Vision profile 7 track to the HEVC decoder, as its HDR10 base layer, when none of this TV's
 * Dolby Vision decoders takes profile 7 ([DvBaseLayer]; [selector] is the one the renderer was built with). The track's
 * format is handed on as HEVC (sample type video/hevc, no codecs string; its size, rate, colour and hvcC parameter sets
 * as they are) both where Media3 asks how well the renderer plays it (track selection) and where the decoder is made,
 * so all of Media3 after sees an HEVC film: the HEVC decoders are the ones listed and tried, falling back from one to
 * the next as for any HEVC film; their format checks pass, rather than warning that the format exceeds the decoder;
 * and the decoder is configured as video/hevc with the colour the track states (for profile 7, BT.2020 and PQ), without
 * the Dolby Vision profile Media3 sets for a Dolby Vision format, and HDR10 colour where the file states none
 * ([DvBaseLayer.hdr10]: brightness data from the SEI in its parameter sets where the track has none,
 * [DvBaseLayer.seiStaticInfo], read once). Its samples go to it with their Dolby Vision units hidden
 * ([DvBaseLayer.hideDolbyVision]).
 * A profile 8 track Media3 itself gives to an HEVC decoder gets HDR10 colour the same way, its brightness data from its
 * SEI too ([getMediaFormat]).
 */
@OptIn(UnstableApi::class)
class PacedVideoRenderer(
    builder: Builder,
    private val selector: MediaCodecSelector,
    private val vsync: VsyncFix,
    private val counts: FrameCounts,
    private val hdr10Plus: Hdr10PlusGate,
) : MediaCodecVideoRenderer(builder) {

    // On the playback thread.
    private val pacer = FramePacer()
    private val gaps = FrameGaps()
    // A frame has been sent since Media3 last started waiting for its first one: only then may one be skipped.
    private var firstSent = false
    /** The decoder is given HEVC (Dolby Vision on HEVC included), whose HDR10+ can be masked. */
    private var hevc = false
    /** What this film's HDR10+ is done with, kept from one decoder of the film to the next. */
    private val plus = Hdr10PlusWatch()
    private var plusFrames = 0
    /**
     * The decoder is an HEVC one given a Dolby Vision track as it is (profile 8, as Media3 falls back on a TV without
     * Dolby Vision). Not profile 7 played as its base layer: that goes to the decoder as the HEVC film it is.
     */
    private var dvOnHevc = false
    /** The Dolby Vision format last looked at and its HEVC base layer (null: played as it is), made once a track. */
    @Volatile private var seen: BaseLayer? = null
    /**
     * The decoder is being given a track as its base layer: set by each format the decoder is given, before its
     * samples, so an HEVC film or a Dolby Vision 5 or 8 one after it (the next in the list) is never touched.
     */
    private var asBase = false
    /** Dolby Vision units were hidden since the decoder was made: logged the first time. */
    private var dvHidden = false
    /** The Dolby Vision format whose base layer the log last told of, and how many Dolby Vision decoders the TV has. */
    private var told: Format? = null
    private var dvDecoders = 0

    private val rendered = MediaCodecAdapter.OnFrameRenderedListener { _, _, nanoTime ->
        val periodNs = vsync.tvPeriodNs
        val wasOff = pacer.offGridPeriodNs != 0L
        if (pacer.rendered(nanoTime, vsync.unaligned, periodNs, vsync.modes)) {
            Log.i(TAG, "Frames reached the screen between the TV's refreshes as measured (every ${periodNs / 1000} us): " +
                "Media3 times them until it's measured again")
            vsync.screenDisagrees()
        } else if (wasOff && pacer.offGridPeriodNs == 0L) {
            Log.i(TAG, "Frames reach the screen on the TV's refreshes as measured again (every ${periodNs / 1000} us): " +
                "each on its own refresh again")
        }
    }

    override fun onCodecInitialized(
        name: String,
        configuration: MediaCodecAdapter.Configuration,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        super.onCodecInitialized(name, configuration, initializedTimestampMs, initializationDurationMs)
        pacer.reset()
        gaps.reset()
        firstSent = false
        hevc = Hdr10PlusMask.hevcStream(codecInfo?.mimeType, codecInputFormat?.codecs)
        PlayFacts.decoderMime = codecInfo?.mimeType
        dvOnHevc = !asBase && codecInfo?.mimeType == MimeTypes.VIDEO_H265 &&
            codecInputFormat?.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
        tellPlus()
        dvHidden = false
        // Tunnelled playback has Media3's own listener, and Media3 its own timing; the film never plays tunnelled here.
        if (getConfiguration().tunneling) return
        val looper = Looper.myLooper() ?: return
        try {
            codec?.setOnFrameRenderedListener(rendered, Handler(looper))
        } catch (e: RuntimeException) {
            // Without the screen's times frames go out as Media3 sends them
        }
    }

    /**
     * Each output format the decoder reports. Some report one a frame for a while (the 4K Max's MediaTek decoder logged
     * "updateFormatChanged" on every frame for a few seconds of a profile 7 film): nothing here may log, measure or make
     * anything, and the same frame length is a single compare.
     */
    override fun onOutputFormatChanged(format: Format, mediaFormat: MediaFormat?) {
        super.onOutputFormatChanged(format, mediaFormat)
        // A frame's length as the film states it, to tell a gap in its times by.
        gaps.stated(if (format.frameRate > 0f) (1_000_000.0 / format.frameRate).toLong() else 0L)
    }

    override fun onEnabled(joining: Boolean, mayRenderStartOfStream: Boolean) {
        super.onEnabled(joining, mayRenderStartOfStream)
        firstSent = false
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean) {
        super.onPositionReset(positionUs, joining)
        // A jump: the frames after it don't follow on from those before.
        gaps.reset()
        firstSent = false
    }

    override fun onProcessedStreamChange() {
        super.onProcessedStreamChange()
        // The next film or episode: Media3 waits for its first frame. Its samples have been going to the decoder since
        // [onStreamChanged], so what was found of its HDR10+ is its own.
        firstSent = false
        tellPlus()
    }

    /** What this film's HDR10+ is done with, for Picture & sound and what the play keeps of the copy ([PlayFacts]). */
    private fun tellPlus() {
        PlayFacts.hdr10PlusSeen = plus.found
        PlayFacts.hdr10PlusKept = plus.found && plus.masking
        PlayFacts.hdr10PlusHeld = if (plus.found) plus.kept else 0
    }

    override fun onStreamChanged(
        formats: Array<out Format>,
        startPositionUs: Long,
        offsetUs: Long,
        mediaPeriodId: MediaSource.MediaPeriodId,
    ) {
        super.onStreamChanged(formats, startPositionUs, offsetUs, mediaPeriodId)
        // The next film or episode's samples come from here on: its HDR10+ is looked at afresh.
        plus.newStream()
    }

    override fun handleMessage(messageType: Int, message: Any?) {
        super.handleMessage(messageType, message)
        // A new surface: Media3 waits for the first frame on it.
        if (messageType == Renderer.MSG_SET_VIDEO_OUTPUT) firstSent = false
    }

    override fun renderOutputBufferV21(codec: MediaCodecAdapter, index: Int, presentationTimeUs: Long, releaseTimeNs: Long) {
        val decoded = decoderCounters
        val missing = gaps.frame(presentationTimeUs, decoded.droppedBufferCount, decoded.skippedOutputBufferCount)
        if (missing > 0) counts.neverDecoded += missing
        val frameUs = gaps.frameUs
        val at = pacer.paced(releaseTimeNs, vsync.unaligned, vsync.tvPeriodNs, vsync.modes, frameUs * 1000, gaps.between)
        when (pacer.move) {
            FramePacer.SAME -> if (firstSent) {
                // It would only take the place of the frame before on the screen: given back to the decoder at once
                // instead, and counted by Media3 as a frame skipped.
                counts.sameRefresh++
                skipOutputBuffer(codec, index, presentationTimeUs)
                gaps.skippedHere()
                return
            } // Else sent all the same, on that refresh: Media3 waits for it, and the frame it replaces is from before.
            // The frame before held for an extra refresh: a move later, or a refresh further on with no frame missing
            // before it. Counted once the film's frame length is known: until then the pacer can't tell a TV at twice
            // the film's rate, where every frame is two refreshes on.
            FramePacer.LATER -> if (frameUs > 0L) counts.shownTwice++
            FramePacer.FOLLOWED -> if (frameUs > 0L && gaps.between == 0) counts.shownTwice++
        }
        firstSent = true
        super.renderOutputBufferV21(codec, index, presentationTimeUs, at)
    }

    /**
     * Each sample on its way to the decoder, after Media3 has put it together and before it's queued: HDR10+ is masked
     * in place when the TV isn't to get it, for this TV or for this film ([Hdr10PlusWatch]); else it is read, changing
     * nothing. Either way what Picture & sound and the Sources ranking say of the film ([PlayFacts.hdr10PlusSeen],
     * [PlayFacts.hdr10PlusHeld]).
     * Only the few hundred bytes before the picture data are read, with nothing made, so it costs microseconds a frame.
     * A track played as its base layer has its Dolby Vision units hidden: the whole sample is searched for them, 8
     * bytes at a time, with nothing made (a quarter to a third of the time reading every byte takes). Encrypted samples
     * are never touched.
     */
    override fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
        val data = buffer.data
        if (hevc && data != null && !buffer.isEncrypted) {
            plus.sample(data, hdr10Plus.why, dvOnHevc)?.let {
                tellPlus()
                Log.i(TAG, it)
            }
            // The last minute's HDR10+, asked every few seconds of a film that has it.
            if (plus.found && (++plusFrames and 127) == 0) {
                plus.summary(SystemClock.elapsedRealtime())?.let { Log.i(TAG, it) }
            }
        }
        if (asBase && !buffer.isEncrypted) {
            val data = buffer.data
            if (data != null && DvBaseLayer.hideDolbyVision(data) > 0 && !dvHidden) {
                dvHidden = true
                Log.i(TAG, "Dolby Vision units hidden from the HEVC decoder: it plays the HDR10 base layer only")
            }
        }
        super.onQueueInputBuffer(buffer)
    }

    /**
     * How well the renderer plays [format]: a profile 7 track played as its base layer, as the HEVC film it then is, on a
     * decoder that isn't its own (as Media3 reports its own Dolby Vision fallback to another codec). So where the file
     * has another video track (profile 7 as a separate HEVC base layer track), Media3 picks that one, as before.
     */
    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int {
        val base = baseLayer(format) ?: return super.supportsFormat(mediaCodecSelector, format)
        val c = super.supportsFormat(mediaCodecSelector, base)
        return RendererCapabilities.create(
            RendererCapabilities.getFormatSupport(c), RendererCapabilities.getAdaptiveSupport(c),
            RendererCapabilities.getTunnelingSupport(c), RendererCapabilities.getHardwareAccelerationSupport(c),
            RendererCapabilities.DECODER_SUPPORT_FALLBACK_MIMETYPE)
    }

    /**
     * The decoder's set-up: a Dolby Vision profile 8 track that Media3 gives to an HEVC decoder (its own way back on a
     * TV without Dolby Vision) gets HDR10 colour and static metadata where the track states none and its picture or
     * record doesn't say HLG or SDR ([DvBaseLayer.hevcColour]), so the TV isn't left to guess the brightness: the film's
     * own from the SEI in its parameter sets where it has them, else generic ([DvBaseLayer.hevcHdr10]). Once per
     * decoder, never per frame.
     */
    override fun getMediaFormat(
        format: Format,
        codecMimeType: String,
        codecMaxValues: CodecMaxValues,
        codecOperatingRate: Float,
        deviceNeedsNoPostProcessWorkaround: Boolean,
        tunnelingAudioSessionId: Int,
    ): MediaFormat {
        val made = super.getMediaFormat(format, codecMimeType, codecMaxValues, codecOperatingRate,
            deviceNeedsNoPostProcessWorkaround, tunnelingAudioSessionId)
        val hdr10 = DvBaseLayer.hevcHdr10(format, codecMimeType) ?: return made
        MediaFormatUtil.maybeSetColorInfo(made, hdr10.colour)
        Log.i(TAG, hdr10.line)
        return made
    }

    /**
     * A new format for the decoder: a profile 7 track played as its base layer goes on as HEVC, said once in the log,
     * with where its brightness data came from.
     */
    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        val format = formatHolder.format
        val base = format?.let { baseLayer(it) }
        asBase = base != null
        if (base != null) {
            formatHolder.format = base
            if (format !== told) {
                told = format
                Log.i(TAG, DvBaseLayer.logLine(dvDecoders))
                seen?.takeIf { it.dv === format }?.brightness?.let { Log.i(TAG, it) }
            }
        }
        return super.onInputFormatChanged(formatHolder)
    }

    /**
     * [format] as its HEVC base layer when it's a Dolby Vision profile 7 track none of this TV's Dolby Vision decoders
     * takes ([DvBaseLayer.baseLayerInstead]); null to play it as it is. The decoders are Media3's own lists, which it
     * keeps once read. Looked at once per format, never per sample.
     */
    private fun baseLayer(format: Format): Format? {
        if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return null
        seen?.let { if (it.dv === format) return it.base }
        val profile = DvBaseLayer.profileOf(format.codecs)
        if (profile != DvBaseLayer.PROFILE) return null
        val instead = try {
            val dv = selector.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, false)
            dvDecoders = dv.size
            val decoders = dv.map { info ->
                val levels = info.capabilities?.profileLevels
                DvBaseLayer.Decoder(info.name, if (levels == null) IntArray(0) else IntArray(levels.size) { levels[it].profile })
            }
            DvBaseLayer.baseLayerInstead(profile, decoders, selector.getDecoderInfos(MimeTypes.VIDEO_H265, false, false).size)
        } catch (e: MediaCodecUtil.DecoderQueryException) {
            // Without the lists it plays as Media3 has it.
            return null
        }
        DvBaseLayer.tvPlaysBase = instead
        var brightness: String? = null
        val base = if (instead) {
            // The film's own brightness data from its parameter sets' SEI, where the track states none.
            val own = format.colorInfo?.hdrStaticInfo
            val sei = if (own == null) DvBaseLayer.seiStaticInfo(format.initializationData) else null
            val colour = DvBaseLayer.hdr10(format.colorInfo, sei)
            brightness = DvBaseLayer.brightnessLine(colour.hdrStaticInfo, fromTrack = own != null, fromSei = sei != null)
            format.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(null).setColorInfo(colour).build()
        } else null
        seen = BaseLayer(format, base, brightness)
        return base
    }

    /** [brightness]: the log's line on where the base layer's brightness data came from ([DvBaseLayer.brightnessLine]). */
    private class BaseLayer(val dv: Format, val base: Format?, val brightness: String?)

    private companion object {
        const val TAG = "FirePlayer"
    }
}
