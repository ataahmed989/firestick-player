package app.fireplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import app.fireplayer.player.LiveBuffer

/**
 * How much a live channel needs loaded again after a stall, and how much it may hold while the TV is short of memory.
 * Pure (no Android, no Media3) so it can be unit-tested; times in microseconds as Media3 passes them.
 *
 * Why the wait after a stall grows: a plain live stream (MPEG-TS over HTTP) arrives at the speed it plays, so the
 * picture stays exactly as far ahead of what has arrived as it was when it started (or last restarted after a stall).
 * Starting after 1.5 s keeps channel changes quick, but leaves a 1.5 s cushion for the whole time the channel plays;
 * every hiccup longer than that stalls it again. Each stall on the same stream therefore waits a little longer before
 * playing on (the Live TV buffer's after-stall time, then twice, then three times, never more than its minimum
 * buffer), so a shaky stream ends up with a cushion that rides out its hiccups. A new channel starts afresh.
 */
class LiveCushion(private val afterStallUs: Long, private val minBufferUs: Long) {
    /** Stalls on the stream playing now (set by the player's owner, read on the playback thread). */
    @Volatile var stalls = 0

    /** Memory step: 0 no limit, 1 or 2 while the TV is short of memory ([capBytes]). */
    @Volatile var memoryStep = 0

    /** How much must be loaded to play on after a stall; at most half the stream's target live offset when it has one. */
    fun afterStallNeedUs(targetLiveOffsetUs: Long): Long {
        val times = stalls.coerceIn(1, MAX_TIMES)
        var need = (afterStallUs * times).coerceAtMost(maxOf(afterStallUs, minBufferUs))
        // As Media3's own rule: a live window can't hold more than its offset, so never wait for more than half of it.
        if (targetLiveOffsetUs > 0) need = minOf(need, targetLiveOffsetUs / 2)
        return need
    }

    /**
     * The most the buffer may hold now, in bytes. Even with memory to spare it's [NORMAL_BYTES]: the Live TV buffer is
     * set in seconds, and 30 s of a 4K channel at 25 Mbit/s would be over 90 MB on a 1.7 GB Stick (a plain 1080p
     * channel never comes near it). The [floorUs] still holds, so a heavy channel holds less ahead, it doesn't stall.
     */
    fun capBytes(): Int = when (memoryStep) {
        0 -> NORMAL_BYTES
        1 -> SQUEEZE_1_BYTES
        else -> SQUEEZE_2_BYTES
    }

    /** Below this much ahead loading goes on whatever the memory step: a squeezed channel holds less, it doesn't stall. */
    fun floorUs(): Long = maxOf(FLOOR_US, afterStallNeedUs(0))

    companion object {
        const val MAX_TIMES = 3
        const val NORMAL_BYTES = 64 shl 20
        const val SQUEEZE_1_BYTES = 24 shl 20
        const val SQUEEZE_2_BYTES = 12 shl 20
        const val FLOOR_US = 4_000_000L

        /** The memory step for an onTrimMemory [level] (the ComponentCallbacks2 levels while the app is in front). */
        fun stepFor(level: Int): Int = when {
            level >= 15 -> 2 // TRIM_MEMORY_RUNNING_CRITICAL
            level >= 10 -> 1 // TRIM_MEMORY_RUNNING_LOW
            else -> 0
        }
    }
}

/**
 * Tells a stall that is getting nowhere from one that is filling up again: a channel buffering with nothing arriving
 * for [limitMs] is opened again rather than left waiting on a dead connection, while one whose buffer grows (however
 * slowly it refills its cushion) is left alone. Pure; checked every few seconds while buffering, never per frame.
 */
class LiveStallWatch(private val limitMs: Long = LIMIT_MS, private val progressMs: Long = PROGRESS_MS) {
    private var lastBufferedMs = 0L
    private var lastProgressAt = 0L

    fun started(now: Long, bufferedMs: Long) {
        lastBufferedMs = bufferedMs
        lastProgressAt = now
    }

    /** True when the stall has made no progress for [limitMs]: time to reconnect. */
    fun stuck(now: Long, bufferedMs: Long): Boolean {
        if (bufferedMs < lastBufferedMs) lastBufferedMs = bufferedMs
        if (bufferedMs - lastBufferedMs >= progressMs) {
            lastBufferedMs = bufferedMs
            lastProgressAt = now
            return false
        }
        return now - lastProgressAt >= limitMs
    }

    companion object {
        const val CHECK_MS = 4_000L
        const val LIMIT_MS = 12_000L
        const val PROGRESS_MS = 500L
    }
}

/**
 * The live player's buffer rules: the Live TV buffer setting through Media3's own DefaultLoadControl, plus a wait after
 * a stall that grows with repeated stalls ([LiveCushion]), a lower ceiling while the TV is short of memory, and the
 * most that was loaded ahead since the last stall (for the log). Every method is passed on by hand: Kotlin's `by` skips
 * the interface's Java default methods, which then throw.
 */
@OptIn(UnstableApi::class)
class LiveLoadControl(buffer: LiveBuffer) : LoadControl {
    private val allocator = DefaultAllocator(/* trimOnReset = */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE)
    private val inner = DefaultLoadControl.Builder()
        .setAllocator(allocator)
        // Medium starts after 1.5 s of picture (3 s after a stall) and keeps up to 30 s ahead.
        .setBufferDurationsMs(buffer.minMs, buffer.maxMs, buffer.startMs, buffer.afterStallMs)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()
    val cushion = LiveCushion(buffer.afterStallMs * 1_000L, buffer.minMs * 1_000L)

    /** The most loaded ahead since [takePeakMs] was last read (ms; written on the playback thread). */
    @Volatile private var peakUs = 0L

    fun takePeakMs(): Long = (peakUs / 1_000).also { peakUs = 0L }

    /** Bytes the buffer holds now. */
    val allocatedBytes: Int get() = allocator.totalBytesAllocated

    /** A new stream: no stalls yet, and no memory limit until the TV asks again. */
    fun newStream() {
        cushion.stalls = 0
        peakUs = 0L
    }

    /** Lowers (or lifts) the ceiling for the memory [step]; memory let go of is freed rather than kept for reuse. */
    fun squeeze(step: Int) {
        cushion.memoryStep = step
        if (step > 0) {
            allocator.setTargetBufferSize(cushion.capBytes())
            allocator.trim()
        }
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        if (parameters.bufferedDurationUs > peakUs) peakUs = parameters.bufferedDurationUs
        val wanted = inner.shouldContinueLoading(parameters)
        val cap = cushion.capBytes()
        if (allocator.totalBytesAllocated >= cap && parameters.bufferedDurationUs >= cushion.floorUs()) return false
        return wanted
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (!inner.shouldStartPlayback(parameters)) return false
        if (!parameters.rebuffering) return true
        val buffered = Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed)
        val target = if (parameters.targetLiveOffsetUs == C.TIME_UNSET) 0L else parameters.targetLiveOffsetUs
        return buffered >= cushion.afterStallNeedUs(target)
    }

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
