package app.fireplayer.player

import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.VideoFrameMetadataListener

/**
 * Catches the picture landing ahead of the sound after a jump.
 *
 * A jump lands where the file's index says a stretch starts (an MKV's Cues, or a cluster [SeekableMkv] found), and the
 * sound starts right there. But some files index stretches that don't start on a keyframe (high bitrate copies with
 * long gaps between keyframes): the picture can only start at the next keyframe, seconds later. Media3 shows that
 * frame at once and holds it while the sound plays up to it, so the picture freezes on a moment that hasn't come yet.
 *
 * Here the first frame after a break in the frames (a jump, or the start) is told to onFirstFrame on the main thread with
 * its time, and the caller compares it with where the film is.
 */
@OptIn(UnstableApi::class)
class JumpFrameCheck(
    private val inner: VideoFrameMetadataListener,
    private val onFirstFrame: (frameMs: Long) -> Unit,
) : VideoFrameMetadataListener {

    private val main = Handler(Looper.getMainLooper())
    // On the playback thread.
    private var lastUs = C.TIME_UNSET

    override fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        format: Format,
        mediaFormat: MediaFormat?,
    ) {
        inner.onVideoFrameAboutToBeRendered(presentationTimeUs, releaseTimeNs, format, mediaFormat)
        val last = lastUs
        lastUs = presentationTimeUs
        // Frames that follow on (dropped ones included) aren't a new start.
        if (last != C.TIME_UNSET && presentationTimeUs - last in 1..BREAK_US) return
        val frameMs = presentationTimeUs / 1000
        main.post { onFirstFrame(frameMs) }
    }

    private companion object {
        const val BREAK_US = 400_000L
    }
}
