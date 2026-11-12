package app.fireplayer.subs

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Listening ahead: the film's sound as it loads ([AheadAudio]), decoded on auto sync's thread, marked as talking or not
 * ([SpeechDetector], with the middle of stereo and the centre of 5.1 as when listening where it plays) into the same
 * [SpeechTimeline], and an [OffsetTracker] stepped along where that has got to, as [ReferenceSync] steps one along the
 * film's own subtitles as they load. The player loads up to two minutes ahead of where it plays, so the first delay is
 * found seconds after the film starts loading rather than after minutes of it playing, and a change after a break is
 * usually found before the break is on screen (it's put in when the film gets there).
 *
 * Plain Kotlin, so tests drive it the way [AutoSync]'s worker thread does. Not thread safe: that one thread feeds it.
 */
class SpeechAhead(val timeline: SpeechTimeline) {

    private val detector = SpeechDetector()
    private var sampler: MonoDownsampler? = null
    // Where the sound fed so far ends (film µs; -1 before any), counted from where it was last anchored.
    private var anchorUs = -1L
    private var fedFrames = 0L
    private var rate = 0
    // The next 10 ms to mark.
    private var frame = 0
    private var gapAllowed = false
    // The run the tracker has been stepped along (where it began), and how far.
    private var steppedRun = Long.MIN_VALUE
    private var steppedTo = -1L

    /** Where the stretch being heard now began (film ms): a new one after a jump; -1 before anything was heard. */
    var runFromMs = -1L
        private set
    /** How many stretches have been begun (jumps), for the log. */
    var runs = 0
        private set
    /** How much film has been listened to ahead, for the log (ms). */
    var decodedMs = 0L
        private set

    /** How far ahead the film has been heard (film ms): the end of what's marked; -1 before anything. */
    val edgeMs: Long get() = if (runFromMs < 0) -1 else frame * 10L

    /** Where the sound fed so far ends (film µs), or -1. */
    val nextUs: Long get() = if (anchorUs < 0 || rate <= 0) -1 else anchorUs + fedFrames * 1_000_000 / rate

    /** How far the tracker has been stepped (film ms), or -1. */
    val steppedToMs: Long get() = steppedTo

    /** The next sound fed may follow a gap (samples let go while behind): the same stretch however long it is. */
    fun expectGap() {
        gapAllowed = true
    }

    /**
     * Decoded sound at film time [filmUs]: [pcm] from its position to its limit (left as it is), [channels]
     * interleaved at [rate] Hz, as [encoding] (16, 24 or 32-bit integers or floats, little-endian).
     */
    fun feed(pcm: ByteBuffer, filmUs: Long, channels: Int, rate: Int, encoding: Int) {
        if (channels <= 0 || rate <= 0 || filmUs < 0) return
        val bytes = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val frameBytes = bytes * channels
        val frames = pcm.remaining() / frameBytes
        if (frames <= 0) return
        val expected = nextUs
        val off = if (expected < 0) 0L else filmUs - expected
        val s0 = sampler
        if (expected < 0 || abs(off) > TOLERANCE_US) {
            // Not where the last sound ended: counted from here. Going back, or a long way ahead, is a jump (loading
            // started somewhere else): a new stretch. A shorter gap (samples let go) is only a hole in this one.
            if (expected < 0 || off <= -BACK_US || (off >= JUMP_US && !gapAllowed)) {
                runFromMs = filmUs / 1000
                runs++
            }
            frame = (filmUs / 10_000).toInt()
            detector.reset()
            sampler = MonoDownsampler(rate, channels)
            anchorUs = filmUs
            fedFrames = 0
            this.rate = rate
        } else if (s0 == null || s0.channels != channels || s0.inRate != rate) {
            // Only the format changed: counted on from where the last sound ended (what the old format had waiting to be
            // marked is let go, so the marks go on from there too).
            frame = (expected / 10_000).toInt()
            detector.reset()
            sampler = MonoDownsampler(rate, channels)
            anchorUs = expected
            fedFrames = 0
            this.rate = rate
        }
        gapAllowed = false
        val s = sampler!!
        val buf = pcm.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val base = buf.position()
        when (encoding) {
            C.ENCODING_PCM_16BIT -> s.push(frames, { f, c -> buf.getShort(base + (f * channels + c) * 2) / 32_768f }) { out, side, n -> detect(out, side, n) }
            C.ENCODING_PCM_24BIT -> s.push(frames, { f, c ->
                val i = base + (f * channels + c) * 3
                val v = (buf.get(i).toInt() and 0xff) or ((buf.get(i + 1).toInt() and 0xff) shl 8) or (buf.get(i + 2).toInt() shl 16)
                v / 8_388_608f
            }) { out, side, n -> detect(out, side, n) }
            C.ENCODING_PCM_32BIT -> s.push(frames, { f, c -> buf.getInt(base + (f * channels + c) * 4) / 2_147_483_648f }) { out, side, n -> detect(out, side, n) }
            else -> s.push(frames, { f, c -> buf.getFloat(base + (f * channels + c) * 4) }) { out, side, n -> detect(out, side, n) }
        }
        fedFrames += frames
        decodedMs += frames * 1000L / rate
    }

    private fun detect(out: FloatArray, side: FloatArray?, n: Int) {
        detector.push(out, side, n) { talking -> timeline.mark(frame++, talking) }
    }

    /** For tests: a stretch heard from [ms] on (marked with [mark]), as a jump there would begin. */
    internal fun startRun(ms: Long) {
        runFromMs = ms
        runs++
        frame = (ms / 10).toInt()
        anchorUs = ms * 1000
        fedFrames = 0
        rate = 0
    }

    /** For tests: 10 ms [f] heard as talking or not, as the detector would mark it. */
    internal fun mark(f: Int, talking: Boolean) {
        timeline.mark(f, talking)
        if (f + 1 > frame) frame = f + 1
    }

    /** Whether there's enough heard since the last step for another (or a new stretch to begin on). */
    fun due(): Boolean = runFromMs >= 0 && (runFromMs != steppedRun || edgeMs - steppedTo >= OffsetTracker.STEP_MS)

    /**
     * Brings [tracker] up to what's been heard ahead: steps it every [OffsetTracker.STEP_MS] of film, at most
     * [maxSteps] times (the rest waits for the next call). A new stretch begun (loading started somewhere else) is a
     * jump, as playing jumping is.
     */
    fun advance(tracker: OffsetTracker, maxSteps: Int = ReferenceSync.MAX_STEPS): List<OffsetTracker.Decision> {
        if (runFromMs < 0) return emptyList()
        if (runFromMs != steppedRun) {
            steppedRun = runFromMs
            steppedTo = runFromMs
            tracker.seeked(steppedTo, timeline)
        }
        val out = ArrayList<OffsetTracker.Decision>()
        while (edgeMs - steppedTo >= OffsetTracker.STEP_MS && out.size < maxSteps) {
            steppedTo += OffsetTracker.STEP_MS
            out += tracker.step(timeline, steppedTo, OffsetTracker.STEP_MS)
        }
        return out
    }

    /**
     * A tracker new to what's been heard ahead (other subtitles for the same film, Resync finding the first delay
     * afresh, or listening taking over from the film's own subtitles): brought to where the others were stepped to and
     * matched there at once, on everything heard. Null while nothing has been stepped yet ([advance] takes it from the
     * start of the stretch then).
     */
    fun rebase(tracker: OffsetTracker): OffsetTracker.Decision? {
        if (runFromMs < 0 || steppedRun != runFromMs || steppedTo < 0) return null
        tracker.seeked(steppedTo, timeline)
        return tracker.step(timeline, steppedTo, OffsetTracker.FULL_STEP_MS)
    }

    /** Whether most (90%) of [fromMs]..[toMs] has been heard, ahead or where it played. */
    fun covers(fromMs: Long, toMs: Long): Boolean {
        val a = (maxOf(fromMs, 0L) / 10).toInt()
        val b = (maxOf(toMs, 0L) / 10).toInt()
        if (b <= a) return true
        val heard = timeline.heard
        var got = 0
        var i = heard.nextSetBit(a)
        while (i in 0 until b) {
            val end = minOf(heard.nextClearBit(i), b)
            got += end - i
            i = heard.nextSetBit(end)
        }
        return got * 10L >= (b - a) * 9L
    }

    /** Another film: nothing heard ahead yet. */
    fun reset() {
        sampler = null
        detector.reset()
        anchorUs = -1
        fedFrames = 0
        rate = 0
        frame = 0
        gapAllowed = false
        steppedRun = Long.MIN_VALUE
        steppedTo = -1
        runFromMs = -1
    }

    companion object {
        // Within this of where the last sound ended is the same sound going on (timestamps rounded to the ms, say).
        private const val TOLERANCE_US = 50_000L
        // Further ahead than this is a jump (loading started somewhere else), not a gap from samples let go; going
        // back this far, too.
        const val JUMP_US = 10_000_000L
        private const val BACK_US = 1_000_000L
    }
}
