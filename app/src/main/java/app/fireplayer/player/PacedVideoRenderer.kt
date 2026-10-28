package app.fireplayer.player

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Media3's video renderer, showing each frame on its own refresh of a TV switched to the film's rate.
 *
 * Where Media3 can't line frames up with the TV's refreshes (see [VsyncFix]), it sends each one out at the time the
 * film gives it. Matroska gives whole milliseconds, so frames of a 24 fps film are 41 or 42 ms apart; when the TV's
 * refreshes fall close to those times, that half millisecond tips frames onto the refresh before or after, and pairs
 * of frames land on one refresh while the next shows nothing (one interval in ten on a 4K film, measured on a Fire TV
 * Stick 4K Plus). Here the TV's refreshes are timed from when frames actually reached the screen, and each frame goes
 * to the refresh after the one before; the film's own times only move that on by a refresh when the two drift more
 * than [SLACK] of one apart, as they must when the film's rate isn't quite the TV's.
 */
@OptIn(UnstableApi::class)
class PacedVideoRenderer(builder: Builder, private val vsync: VsyncFix) : MediaCodecVideoRenderer(builder) {

    // On the playback thread.
    private var refreshNs = 0L   // when a frame last reached the screen: one of the TV's refreshes
    private var lastTargetNs = 0L

    private val rendered = MediaCodecAdapter.OnFrameRenderedListener { _, _, nanoTime -> refreshNs = nanoTime }

    override fun onCodecInitialized(
        name: String,
        configuration: MediaCodecAdapter.Configuration,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        super.onCodecInitialized(name, configuration, initializedTimestampMs, initializationDurationMs)
        refreshNs = 0L
        lastTargetNs = 0L
        // Tunnelled playback has Media3's own listener, and Media3 its own timing; the film never plays tunnelled here.
        if (getConfiguration().tunneling) return
        val looper = Looper.myLooper() ?: return
        try {
            codec?.setOnFrameRenderedListener(rendered, Handler(looper))
        } catch (e: RuntimeException) {
            // Without the screen's times frames go out as Media3 sends them
        }
    }

    override fun renderOutputBufferV21(codec: MediaCodecAdapter, index: Int, presentationTimeUs: Long, releaseTimeNs: Long) {
        super.renderOutputBufferV21(codec, index, presentationTimeUs, paced(releaseTimeNs))
    }

    private fun paced(releaseTimeNs: Long): Long {
        val periodNs = vsync.tvPeriodNs
        val refresh = refreshNs
        if (!vsync.unaligned || periodNs <= 0L || refresh == 0L) {
            lastTargetNs = 0L
            return releaseTimeNs
        }
        val last = lastTargetNs
        var target: Long
        if (last == 0L || abs(releaseTimeNs - last) > 3 * periodNs) {
            // Starting, or after a jump or a pause: the refresh nearest the film's time
            target = refresh + ((releaseTimeNs - refresh).toDouble() / periodNs).roundToLong() * periodNs
        } else {
            // Kept on the TV's refreshes, where the screen says they are
            val off = Math.floorMod(last - refresh, periodNs)
            val onGrid = last - if (off > periodNs / 2) off - periodNs else off
            target = onGrid + periodNs
            val behind = releaseTimeNs - target
            if (behind > SLACK * periodNs) target += periodNs
            else if (behind < -SLACK * periodNs) target -= periodNs
        }
        lastTargetNs = target
        // Half a refresh early: the TV shows it at that refresh, whichever way the timing wobbles
        return target - periodNs / 2
    }

    private companion object {
        const val SLACK = 0.6
    }
}
