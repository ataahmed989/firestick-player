package app.fireplayer.player

/**
 * Frames the decoder never gave: gaps in the film's times between frames sent to the screen, less the frames Media3
 * itself dropped or skipped in between (it counts those). A gap counts when it's more than [GAP] frames long.
 *
 * A frame's length is the middle one of the latest gaps between frames ([LENGTHS] of them): Matroska gives whole
 * milliseconds, so a 24 fps film's frames are 41 or 42 ms apart, and the middle one is either. Gaps are only counted once
 * a few have been measured: what the frames do wins over what the file says (an interlaced film can state its fields'
 * rate, twice its frames'). The length the film states ([stated]) is only [frameUs] until then, for the pacer.
 *
 * All on the playback thread, a few sums a frame and nothing made: see [PacedVideoRenderer].
 */
class FrameGaps {

    /** One frame's length as the film states it, in µs (0 when it doesn't): [frameUs] until the gaps have been measured. */
    var statedFrameUs = 0L
        private set

    private var lastUs = UNSET
    private var dropped = 0
    private var skipped = 0
    private val lengths = LongArray(LENGTHS)
    private val sorted = LongArray(LENGTHS)
    private var count = 0
    private var next = 0
    private var measuredUs = 0L

    /** Frames that should have come between the latest frame and the one before (Media3's drops included). */
    var between = 0
        private set

    /** A new decoder, or a jump: the next frame starts afresh. */
    fun reset() {
        lastUs = UNSET
        between = 0
    }

    /** The film's stated frame length, in µs (0: not stated), as its format comes: another one is measured afresh. */
    fun stated(frameUs: Long) {
        if (frameUs == statedFrameUs) return
        statedFrameUs = frameUs
        count = 0
        next = 0
        measuredUs = 0L
    }

    /**
     * A frame at [presentationUs] goes to the screen; [droppedSoFar] and [skippedSoFar] are Media3's counts
     * (droppedBufferCount, skippedOutputBufferCount) as it does. Returns how many frames before it the decoder never gave.
     */
    fun frame(presentationUs: Long, droppedSoFar: Int, skippedSoFar: Int): Int {
        // Media3's counts start again with a new renderer: then all of them are new.
        val newDropped = if (droppedSoFar >= dropped) droppedSoFar - dropped else droppedSoFar
        val newSkipped = if (skippedSoFar >= skipped) skippedSoFar - skipped else skippedSoFar
        dropped = droppedSoFar
        skipped = skippedSoFar
        val last = lastUs
        lastUs = presentationUs
        between = 0
        if (last == UNSET) return 0
        val d = presentationUs - last
        // A jump, or a frame out of order: not a gap.
        if (d <= 0L || d > BREAK_US) return 0
        remember(d)
        val frameUs = measuredUs
        if (frameUs <= 0L || d <= frameUs * GAP) return 0
        between = ((d + frameUs / 2) / frameUs - 1).toInt().coerceAtLeast(0)
        return (between - newDropped - newSkipped).coerceAtLeast(0)
    }

    /** The frame just seen was skipped here, not by Media3: its skip isn't one that explains a gap. */
    fun skippedHere() {
        skipped++
    }

    /** One frame's length in µs: as measured, else as the film states it; 0 until known. */
    val frameUs: Long get() = if (measuredUs > 0L) measuredUs else statedFrameUs

    private fun remember(d: Long) {
        lengths[next] = d
        next = (next + 1) % LENGTHS
        if (count < LENGTHS) count++
        // The middle one, worked out again every so often: the gaps change little from frame to frame.
        if (count >= MIN_LENGTHS && (next % REMEASURE == 0 || measuredUs == 0L)) {
            System.arraycopy(lengths, 0, sorted, 0, count)
            java.util.Arrays.sort(sorted, 0, count)
            measuredUs = sorted[count / 2]
        }
    }

    companion object {
        /** A gap longer than this many frames has frames missing from it. */
        const val GAP = 1.5
        /** Further apart than this, frames are on either side of a jump. */
        const val BREAK_US = 400_000L
        /** The latest gaps between frames a frame's length is measured from. */
        const val LENGTHS = 16
        private const val MIN_LENGTHS = 4
        private const val REMEASURE = 8
        private const val UNSET = Long.MIN_VALUE
    }
}
