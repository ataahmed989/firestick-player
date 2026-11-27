package app.fireplayer.player

import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * An MKV whose index lists its video's keyframes no longer lands so: its jumps go by those alone ([CueReader]; the
 * stretches that didn't start on a keyframe were the index's points for subtitle lines). This is still what catches
 * it in an MKV with no index, or one whose index couldn't be read for keyframes, and in other kinds of file.
 *
 * Here the first frame after a break in the frames (a jump, or the start) is told to onFirstFrame on the main thread with
 * its time and when it was handed over to be shown (SystemClock.elapsedRealtime), and the caller compares it with where
 * the film is.
 */
@OptIn(UnstableApi::class)
class JumpFrameCheck(
    private val inner: VideoFrameMetadataListener,
    private val onFirstFrame: (frameMs: Long, shownAt: Long) -> Unit,
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
        val shownAt = SystemClock.elapsedRealtime()
        main.post { onFirstFrame(frameMs, shownAt) }
    }

    private companion object {
        const val BREAK_US = 400_000L
    }
}

/**
 * The log's line for each jump once its first picture is shown, so a TV's log says how quick jumps are and whether the
 * picture came with the sound: where it was to go, where the film went on from (the sound starts there), the first
 * picture's time against that, how long after the jump it showed, and the keyframe the film was loaded from when it
 * loaded again from an MKV's index ([MkvJump]). Times only, in ms of film. Pure.
 */
object JumpLine {
    /**
     * "jump to 2650732 ms: on from 2649280 ms, first picture at 2649280 ms (+0 ms from the sound) after 412 ms, loaded
     * from the keyframe at 2649280 ms (0 ms before)". [start]: a film starting part-way in, not a jump. [keyMs]: -1 when
     * the film didn't load again from an MKV's keyframes for it (it had it loaded, or it's another kind of file).
     */
    fun text(targetMs: Long, landedMs: Long, frameMs: Long, waitMs: Long, keyMs: Long = -1L, start: Boolean = false): String {
        val off = frameMs - landedMs
        val key = if (keyMs < 0) "" else ", loaded from the keyframe at $keyMs ms (${landedMs - keyMs} ms before)"
        return "${if (start) "start at" else "jump to"} $targetMs ms: on from $landedMs ms, first picture at $frameMs ms " +
            "(${if (off >= 0) "+" else ""}$off ms from the sound) after $waitMs ms$key"
    }

    /**
     * Whether the first frame after a break, from [frameMs] of the film and handed over to be shown at [shownAt], is the
     * first picture of the jump made at [armedAt] that went on from [landedMs]. Not one shown before the jump was made
     * (the picture of the jump before it, when keys follow each other faster than pictures come), nor one from before
     * where the film goes on or from far after it.
     */
    fun own(frameMs: Long, landedMs: Long, shownAt: Long, armedAt: Long): Boolean =
        shownAt >= armedAt && frameMs >= landedMs - BEFORE_MS && frameMs <= landedMs + AHEAD_MS

    /** A jump's first picture is at most this much before where the film goes on (a frame, rounded), or after it. */
    private const val BEFORE_MS = 1_000L
    private const val AHEAD_MS = 20_000L

    /** The keyframe [mark] gave for the jump that went on from [landedMs], in ms; -1 when it isn't that jump's. */
    internal fun keyMs(mark: MkvJump.Mark?, landedMs: Long): Long =
        if (mark == null || mark.keyUs < 0 || mark.timeUs / 1000 != landedMs) -1L else mark.keyUs / 1000
}
