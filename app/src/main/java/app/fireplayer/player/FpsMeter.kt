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
 * Works out the frame rate from the frames being shown, for streams (many MKV and TS files)
 * that don't say what it is. Reports once per [reset] on the main thread.
 */
@OptIn(UnstableApi::class)
class FpsMeter(private val onMeasured: (Float) -> Unit) : VideoFrameMetadataListener {

    private val main = Handler(Looper.getMainLooper())
    private val deltas = LongArray(40)
    private var count = 0
    private var lastUs = C.TIME_UNSET
    @Volatile private var done = false
    @Volatile private var generation = 0

    fun reset() {
        generation++
        count = 0
        lastUs = C.TIME_UNSET
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
        if (last == C.TIME_UNSET) return
        val delta = presentationTimeUs - last
        // Ignore jumps from seeks and dropped frames: 8 fps to 200 fps.
        if (delta !in 5_000..125_000) return
        deltas[count++] = delta
        if (count < deltas.size) return
        done = true
        val median = deltas.sorted()[deltas.size / 2]
        val fps = 1_000_000f / median
        val gen = generation
        main.post { if (gen == generation) onMeasured(fps) }
    }
}
