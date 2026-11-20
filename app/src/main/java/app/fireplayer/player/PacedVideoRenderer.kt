package app.fireplayer.player

import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer

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
 * It also keeps HDR10+ from the decoder while the TV doesn't report HDR10+ ([hdr10Plus], [Hdr10PlusMask]).
 */
@OptIn(UnstableApi::class)
class PacedVideoRenderer(
    builder: Builder,
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
    /** HDR10+ was masked since the decoder was made: logged the first time, which shows the film has HDR10+. */
    private var masked = false

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
        masked = false
        // Tunnelled playback has Media3's own listener, and Media3 its own timing; the film never plays tunnelled here.
        if (getConfiguration().tunneling) return
        val looper = Looper.myLooper() ?: return
        try {
            codec?.setOnFrameRenderedListener(rendered, Handler(looper))
        } catch (e: RuntimeException) {
            // Without the screen's times frames go out as Media3 sends them
        }
    }

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
        // The next film or episode: Media3 waits for its first frame.
        firstSent = false
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
     * in place when the TV doesn't report HDR10+. Only the few hundred bytes before the picture data are read, with
     * nothing made, so it costs microseconds a frame.
     */
    override fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
        if (hevc && hdr10Plus.mask && !buffer.isEncrypted) {
            val data = buffer.data
            if (data != null && Hdr10PlusMask.mask(data) > 0 && !masked) {
                masked = true
                Log.i(TAG, "HDR10+ metadata in this video is masked before the decoder (the TV doesn't report HDR10+)")
            }
        }
        super.onQueueInputBuffer(buffer)
    }

    private companion object {
        const val TAG = "FirePlayer"
    }
}
