package app.fireplayer.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * How much of the film is kept behind where it plays, held to a size in memory as well as a length of time.
 *
 * Media3 keeps a fixed length behind (6 s on a Fire TV Stick 4K Max, [RamTier]), read once when the player is built.
 * In a 4K remux at 84 Mb/s those 6 s are 63 MB of a 128 MiB buffer, leaving 6 s ahead; measured on the Stick, that
 * film ran the TV out of memory. So what's kept behind is also held to [FilmBufferPlan.backCapBytes] (24 MiB there):
 * 2.3 s of that film, all 6 s of anything under 32 Mb/s. The rest goes to what's loaded ahead, which is what keeps
 * the film from stopping.
 *
 * [keepUs] is the rule, apart from Media3 so it can be tested; [BackCappedSources] applies it by letting go of the
 * film a little later than Media3 asks ([MediaPeriod.discardBuffer]).
 */
object BackBuffer {
    /** Never less than this is kept behind, however big the film: a frame-step back and the keyframe before it. */
    const val LEAST_KEPT_US = 2_000_000L
    /** The film's size per second is only trusted with this much of it held. */
    const val MEASURE_SPAN_US = 3_000_000L
    /** A film that keeps stalling for want of room ahead keeps this little behind ([keepUs], tight). */
    const val TIGHT_LEAST_KEPT_US = 1_000_000L

    /**
     * How long a stretch to keep behind, in µs: what [capBytes] holds of a film of which [heldBytes] are held for
     * [heldSpanUs] of it, between [LEAST_KEPT_US] and [backBufferUs] (Media3's own length). [backBufferUs] while the
     * film's size isn't known yet, or with no cap ([capBytes] 0 or less).
     *
     * [tight]: the film has stalled lately ([StallCount]). Where the cap already cuts what's kept behind (a film too
     * big for this TV's memory to hold its usual length), half the cap is kept, down to [TIGHT_LEAST_KEPT_US]: what's
     * behind and what's ahead come out of the same memory, and a film running dry needs it ahead. In an 84 Mb/s
     * remux on a Fire TV Stick 4K Max that is 12 MiB more ahead, a second and more of a room of five. A film the cap
     * doesn't cut (under 32 Mb/s there) keeps all it kept: memory isn't what it's short of.
     */
    fun keepUs(capBytes: Long, heldBytes: Long, heldSpanUs: Long, backBufferUs: Long, tight: Boolean = false): Long {
        if (capBytes <= 0L || backBufferUs <= LEAST_KEPT_US) return backBufferUs
        if (heldBytes <= 0L || heldSpanUs < MEASURE_SPAN_US) return backBufferUs
        val keep = (capBytes.toDouble() * heldSpanUs / heldBytes).toLong()
        if (!tight || keep >= backBufferUs) return keep.coerceIn(LEAST_KEPT_US, backBufferUs)
        return (keep / 2).coerceIn(TIGHT_LEAST_KEPT_US, backBufferUs)
    }
}

/**
 * The film's media sources, each keeping no more behind than [capBytes] ([BackBuffer]). [held] is the memory the
 * film's loaded part holds now (the allocator's), [backBufferUs] the length Media3 keeps behind.
 */
@OptIn(UnstableApi::class)
class BackCappedSources(
    private val inner: MediaSource.Factory,
    private val capBytes: Long,
    private val backBufferUs: Long,
    private val held: () -> Long,
    /** The film has stalled lately: less is kept behind where the cap already cuts it ([BackBuffer.keepUs]). Playback thread. */
    private val tight: () -> Boolean = { false },
) : MediaSource.Factory {

    /** How long a stretch is kept behind now, in ms: Media3's own length until the film's size is known. */
    @Volatile var keptBehindMs: Long = backBufferUs / 1000
        private set

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
        inner.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        inner.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    override fun getSupportedTypes(): IntArray = inner.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource = Source(inner.createMediaSource(mediaItem))

    private inner class Source(source: MediaSource) : WrappingMediaSource(source) {
        override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
            Period(mediaSource.createPeriod(id, allocator, startPositionUs))

        override fun releasePeriod(mediaPeriod: MediaPeriod) =
            mediaSource.releasePeriod((mediaPeriod as? Period)?.inner ?: mediaPeriod)
    }

    /** Media3's period, with [discardBuffer] moved on by what the cap doesn't keep. All on the playback thread. */
    private inner class Period(val inner: MediaPeriod) : MediaPeriod {
        // Where the film was last let go up to, and how much is kept behind (µs).
        private var keptFromUs = C.TIME_UNSET
        private var keepUs = backBufferUs

        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
            // Media3 knows the period it made by this wrapper, so that's what it's told of.
            inner.prepare(
                object : MediaPeriod.Callback {
                    override fun onPrepared(mediaPeriod: MediaPeriod) = callback.onPrepared(this@Period)

                    override fun onContinueLoadingRequested(source: MediaPeriod) = callback.onContinueLoadingRequested(this@Period)
                },
                positionUs,
            )
        }

        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) {
            // Media3 asks with where the film is, less the length it keeps behind.
            val playingUs = positionUs + backBufferUs
            val endUs = inner.bufferedPositionUs
            if (endUs != C.TIME_END_OF_SOURCE && endUs > playingUs) {
                // What's held runs from where it was last let go up to (when that's behind where it plays) to its end.
                val from = keptFromUs.takeIf { it != C.TIME_UNSET && it >= positionUs && it <= playingUs } ?: positionUs
                keepUs = BackBuffer.keepUs(capBytes, held(), endUs - maxOf(from, 0L), backBufferUs, tight())
                keptBehindMs = keepUs / 1000
            }
            val to = playingUs - keepUs
            keptFromUs = to
            inner.discardBuffer(to, toKeyframe)
        }

        override fun maybeThrowPrepareError() = inner.maybeThrowPrepareError()

        override fun getTrackGroups(): TrackGroupArray = inner.trackGroups

        override fun getStreamKeys(trackSelections: MutableList<ExoTrackSelection>): MutableList<StreamKey> =
            inner.getStreamKeys(trackSelections)

        override fun selectTracks(
            selections: Array<out ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long,
        ): Long = inner.selectTracks(selections, mayRetainStreamFlags, streams, streamResetFlags, positionUs)

        override fun readDiscontinuity(): Long = inner.readDiscontinuity()

        override fun seekToUs(positionUs: Long): Long = inner.seekToUs(positionUs)

        override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
            inner.getAdjustedSeekPositionUs(positionUs, seekParameters)

        override fun getBufferedPositionUs(): Long = inner.bufferedPositionUs

        override fun getNextLoadPositionUs(): Long = inner.nextLoadPositionUs

        override fun continueLoading(loadingInfo: LoadingInfo): Boolean = inner.continueLoading(loadingInfo)

        override fun isLoading(): Boolean = inner.isLoading

        override fun reevaluateBuffer(positionUs: Long) = inner.reevaluateBuffer(positionUs)
    }
}
