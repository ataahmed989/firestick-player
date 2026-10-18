package app.fireplayer.player

import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import kotlin.math.abs

/**
 * Works out the frame rate from the frames being shown, for streams (MKV and many TS files)
 * that don't say what it is. Reports once per [reset] on the main thread.
 *
 * MKV timestamps are whole milliseconds, so frame gaps at 23.976 fps alternate 41 and 42 ms.
 * The rate is therefore taken over a run of frames, then snapped to the nearest standard rate.
 */
@OptIn(UnstableApi::class)
class FpsMeter(private val onMeasured: (Float) -> Unit) : VideoFrameMetadataListener {

    private val main = Handler(Looper.getMainLooper())
    private var runStartUs = C.TIME_UNSET
    private var lastUs = C.TIME_UNSET
    private var frames = 0
    @Volatile private var done = false
    @Volatile private var generation = 0

    fun reset() {
        generation++
        runStartUs = C.TIME_UNSET
        lastUs = C.TIME_UNSET
        frames = 0
        done = false
    }

    override fun onVideoFrameAboutToBeRendered(
        presentationTimeUs: Long,
        releaseTimeNs: Long,
        format: Format,
        mediaFormat: MediaFormat?,
    ) {
        if (done) return
        val last = lastUs
        lastUs = presentationTimeUs
        val delta = if (last == C.TIME_UNSET) -1L else presentationTimeUs - last
        // A jump (seek), a dropped frame or a step backwards starts a new run: 8 fps to 200 fps only.
        if (delta !in 4_000..125_000 || (frames > 0 && delta > 1.6 * (last - runStartUs) / frames)) {
            runStartUs = presentationTimeUs
            frames = 0
            return
        }
        frames++
        if (frames < FRAMES) return
        done = true
        val fps = snap(frames * 1_000_000f / (presentationTimeUs - runStartUs))
        val gen = generation
        main.post { if (gen == generation) onMeasured(fps) }
    }

    companion object {
        private const val FRAMES = 96
        val STANDARD = listOf(23.976f, 24f, 25f, 29.97f, 30f, 47.952f, 48f, 50f, 59.94f, 60f, 100f, 119.88f, 120f)

        /** The standard rate within 0.6%, or the measured value if none is that close. */
        fun snap(fps: Float): Float {
            val nearest = STANDARD.minByOrNull { abs(it - fps) } ?: return fps
            return if (abs(nearest - fps) / nearest < 0.006f) nearest else fps
        }
    }
}
