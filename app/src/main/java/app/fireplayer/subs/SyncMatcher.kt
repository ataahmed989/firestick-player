package app.fireplayer.subs

import java.io.File
import java.util.BitSet
import kotlin.math.sqrt

/**
 * When people talk in the film, as heard so far: one mark per 10 ms of film time. [heard] says which 10 ms have been
 * listened to at all (only what plays is heard, and the viewer may jump around), [speech] which of those sounded like
 * talking. A two-hour film is 720,000 marks, about 90 KB for each.
 */
class SpeechTimeline {
    val speech = BitSet()
    val heard = BitSet()

    /** Marks one 10 ms; true if it hadn't been heard before. */
    @Synchronized
    fun mark(frame: Int, talking: Boolean): Boolean {
        if (frame < 0) return false
        val fresh = !heard.get(frame)
        heard.set(frame)
        if (talking) speech.set(frame) else speech.clear(frame)
        return fresh
    }

    @Synchronized
    fun clear() {
        speech.clear()
        heard.clear()
    }

    /** A copy to work on without holding up the listening, limited to frames from [fromFrame] on. */
    @Synchronized
    fun snapshot(fromFrame: Int = 0): Pair<BitSet, BitSet> {
        val h = heard.clone() as BitSet
        if (fromFrame > 0) h.clear(0, fromFrame)
        return speech.clone() as BitSet to h
    }
}

/** The times subtitle lines are on screen, read straight from a downloaded file (SRT, WebVTT, ASS/SSA). */
object SubtitleTimes {

    class Line(val startMs: Long, val endMs: Long)

    private val SRT = Regex("""(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,3})\s*-->\s*(?:(\d{1,2}):)?(\d{1,2}):(\d{2})[,.](\d{1,3})""")
    private val ASS = Regex("""^Dialogue:\s*[^,]*,(\d+):(\d{2}):(\d{2})[.,](\d{1,3}),(\d+):(\d{2}):(\d{2})[.,](\d{1,3}),([^,]*),(?:[^,]*,){5}(.*)$""")
    // Every literal brace escaped: Android's regex engine (ICU) rejects a bare "}", which desktop Java lets through.
    private val TAGS = Regex("""\{[^}]*\}|<[^>]*>""")
    // Lines that aren't anyone talking: sound notes ("[door slams]", "(MUSIC)") and song lyrics ("♪ ... ♪").
    private val NOT_TALK = Regex("""^[\s\-]*(\[[^\]]*]|\([^)]*\)|[♪♫#*].*)[\s.]*$""")

    fun read(file: File): List<Line> {
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return parse(text, file.extension.lowercase() in setOf("ass", "ssa"))
    }

    fun parse(text: String, ass: Boolean): List<Line> {
        val out = ArrayList<Line>()
        if (ass) {
            for (raw in text.lineSequence()) {
                val m = ASS.find(raw.trim()) ?: continue
                val g = m.groupValues
                val words = g[10].replace("\\N", " ").replace("\\n", " ").replace(TAGS, "").trim()
                // Drawings and signs ("{\p1}m 0 0 l ...") carry no words once their tags are gone.
                if (words.isEmpty() || words.startsWith("m ") || NOT_TALK.matches(words)) continue
                val start = ms(g[1], g[2], g[3], g[4])
                val end = ms(g[5], g[6], g[7], g[8])
                if (end > start) out += Line(start, end)
            }
        } else {
            val lines = text.replace("\r", "").split('\n')
            var i = 0
            while (i < lines.size) {
                val m = SRT.find(lines[i])
                if (m == null) { i++; continue }
                val g = m.groupValues
                val start = ms(g[1], g[2], g[3], g[4])
                val end = ms(g[5], g[6], g[7], g[8])
                val words = StringBuilder()
                i++
                while (i < lines.size && lines[i].isNotBlank() && SRT.find(lines[i]) == null) {
                    words.append(lines[i].replace(TAGS, "")).append(' ')
                    i++
                }
                val said = words.toString().trim()
                if (end > start && said.isNotEmpty() && !NOT_TALK.matches(said)) out += Line(start, end)
            }
        }
        out.sortBy { it.startMs }
        return out
    }

    private fun ms(h: String, m: String, s: String, frac: String): Long {
        val f = when (frac.length) { 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
        return ((h.ifEmpty { "0" }.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + f
    }

    /** The lines as 10 ms marks, the same grid as [SpeechTimeline]. */
    fun frames(lines: List<Line>): BitSet {
        val bits = BitSet()
        for (l in lines) bits.set((l.startMs / 10).toInt(), (l.endMs / 10).toInt().coerceAtLeast((l.startMs / 10).toInt() + 1))
        return bits
    }
}

/**
 * Finds how far a subtitle file is off from the film's talking: it slides the lines over what was heard, from
 * [SyncMatcher.RANGE_MS] earlier to as much later, and keeps the shift where lines and talking overlap best.
 *
 * Coarse first, in 100 ms steps over 100 ms blocks (a few million additions for six minutes heard), then in 10 ms steps
 * around the best one. A shift is only trusted when it clearly beats every other shift more than 2 s away, and enough
 * lines and talking went into it.
 *
 * The two sides are worked out apart ([Lines] once per subtitle file, [Heard] once per stretch of film), so
 * [OffsetTracker] can try one stretch against several shifts without working it out again.
 */
object SyncMatcher {

    const val RANGE_MS = 60_000L

    /** A subtitle shift for people: "+1.4 s", "−0.3 s", "0.0 s". */
    fun formatShift(ms: Long): String {
        val s = Math.round(ms / 100.0) / 10.0
        return when {
            s > 0 -> "+%.1f s".format(java.util.Locale.ROOT, s)
            s < 0 -> "\u2212%.1f s".format(java.util.Locale.ROOT, -s)
            else -> "0.0 s"
        }
    }
    private const val BLOCK = 10 // frames: 100 ms
    private const val SMOOTH = 50 // blocks: 5 s either side
    private const val MIN_SPEECH_MS = 30_000L
    private const val MIN_LINES = 12
    // How sure is sure: the best overlap well above the rest (z), and the next best place clearly behind it.
    private const val MIN_Z = 4.5
    private const val MAX_RUNNER_UP = 0.8
    // ...or the next best place far behind, even if the overlap swings a lot from shift to shift.
    private const val CLEAR_RUNNER_UP = 0.6
    private const val MIN_R = 0.15

    class Result(
        /** How much later (positive) or earlier the subtitles should show, in ms: the subtitle delay. */
        val delayMs: Long,
        val confident: Boolean,
        val r: Double,
        val runnerUp: Double,
        val z: Double,
        val speechMs: Long,
        val lines: Int,
    ) {
        override fun toString() = "delay=${delayMs}ms sure=$confident r=${"%.3f".format(r)} next=${"%.3f".format(runnerUp)} " +
            "z=${"%.1f".format(z)} talk=${speechMs / 1000}s lines=$lines"
    }

    /**
     * The subtitle side, worked out once per file: the lines as 10 ms marks, and how much of each 100 ms block has a
     * line on screen, less its average over the 10 s around it (see [detrend]). About 0.6 MB for two hours.
     */
    class Lines(val lines: List<SubtitleTimes.Line>, val frames: BitSet = SubtitleTimes.frames(lines)) {
        internal val ys: DoubleArray

        init {
            val n = frames.length() / BLOCK + 1
            ys = DoubleArray(n)
            var s = frames.nextSetBit(0)
            while (s >= 0) {
                ys[s / BLOCK] += 1.0 / BLOCK
                s = frames.nextSetBit(s + 1)
            }
            detrend(ys, n)
        }

        /** How many lines, shown [delayMs] later, are on screen at some point between film times [fromMs] and [toMs]. */
        fun onScreen(delayMs: Long, fromMs: Long, toMs: Long): Int =
            lines.count { it.endMs + delayMs > fromMs && it.startMs + delayMs < toMs }
    }

    /**
     * One stretch of heard film, marks [fromFrame] (inclusive) to [toFrame] (exclusive), ready to slide lines over:
     * its 100 ms blocks that were mostly heard, and how much of each was talking less the 10 s average around it.
     */
    class Heard internal constructor(
        val speech: BitSet,
        val heard: BitSet,
        val fromFrame: Int,
        val toFrame: Int,
        internal val idx: IntArray,
        internal val xs: DoubleArray,
        /** How many blocks went in. */
        val blocks: Int,
        internal val sxx: Double,
        /** Talking heard in the stretch. */
        val speechMs: Long,
    ) {
        /** Film time where block [k] starts. */
        fun blockMs(k: Int): Long = idx[k] * BLOCK * 10L
    }

    /** [speech] where [heard], from mark [fromFrame] up to [toFrame]; null when there's too little to go on. */
    fun heard(speech: BitSet, heard: BitSet, fromFrame: Int = 0, toFrame: Int = Int.MAX_VALUE): Heard? {
        val from = maxOf(fromFrame, 0)
        val to = minOf(toFrame, heard.length())
        val first = heard.nextSetBit(from)
        if (first < 0 || first >= to) return null
        val speechMs = speech.get(from, to).also { it.and(heard.get(from, to)) }.cardinality() * 10L

        // Blocks of 100 ms that were (mostly) heard, and how much of each was talking.
        val firstBlock = first / BLOCK
        val cap = (to - 1) / BLOCK - firstBlock + 1
        val idx = IntArray(cap)
        val xs = DoubleArray(cap)
        var nb = 0
        var b = firstBlock
        while (b >= 0) {
            val a = maxOf(b * BLOCK, from)
            val e = minOf(b * BLOCK + BLOCK, to)
            var h = 0
            var t = 0
            for (f in a until e) if (heard.get(f)) { h++; if (speech.get(f)) t++ }
            if (h >= BLOCK / 2) {
                idx[nb] = b
                xs[nb] = t.toDouble() / h
                nb++
            }
            val next = heard.nextSetBit(b * BLOCK + BLOCK)
            b = if (next < 0 || next >= to) -1 else next / BLOCK
        }
        if (nb < 100) return null

        // Talking comes and goes in stretches (a chatty scene, then a chase), and so do lines: taking away each one's
        // average over the 10 s around it leaves the starts and stops, which only line up at the right shift.
        detrend(xs, nb)
        var sxx = 0.0
        for (i in 0 until nb) sxx += xs[i] * xs[i]
        if (sxx <= 0) return null
        return Heard(speech, heard, from, to, idx, xs, nb, sxx, speechMs)
    }

    /**
     * Matches [subs] (from [SubtitleTimes.frames]) against [speech] where [heard]. Shifts are tried within
     * [centerMs] ± [rangeMs].
     */
    fun match(speech: BitSet, heard: BitSet, subs: BitSet, lines: List<SubtitleTimes.Line>, centerMs: Long = 0, rangeMs: Long = RANGE_MS): Result? {
        if (heard.isEmpty || subs.isEmpty) return null
        val h = heard(speech, heard) ?: return null
        return match(h, Lines(lines, subs), centerMs, rangeMs)
    }

    /** Slides [subs] over [h], shifts within [centerMs] ± [rangeMs]. The work is blocks heard × shifts tried. */
    fun match(h: Heard, subs: Lines, centerMs: Long = 0, rangeMs: Long = RANGE_MS): Result {
        val center = (centerMs / 100).toInt()
        val reach = (rangeMs / 100).toInt()
        val lags = 2 * reach + 1
        val rs = DoubleArray(lags)
        for (k in 0 until lags) rs[k] = rAtLag(h, subs.ys, center - reach + k)
        var best = 0
        for (k in 1 until lags) if (rs[k] > rs[best]) best = k
        var runnerUp = 0.0
        var mean = 0.0
        for (k in 0 until lags) {
            mean += rs[k]
            if (kotlin.math.abs(k - best) > 15 && rs[k] > runnerUp) runnerUp = rs[k]
        }
        mean /= lags
        var sd = 0.0
        for (k in 0 until lags) sd += (rs[k] - mean) * (rs[k] - mean)
        sd = sqrt(sd / lags)
        val z = if (sd > 0) (rs[best] - mean) / sd else 0.0

        // The same around the best block, 10 ms at a time, mark by mark.
        val coarse = (center - reach + best) * BLOCK
        var fineBest = coarse
        var fineR = -2.0
        for (lag in coarse - 15..coarse + 15) {
            val r = fineR(h, subs.frames, lag)
            if (r > fineR) { fineR = r; fineBest = lag }
        }
        val delayMs = fineBest * 10L
        val heardLines = subs.lines.count { l ->
            val from = ((l.startMs + delayMs) / 10).toInt()
            val to = minOf(((l.endMs + delayMs) / 10).toInt(), h.toFrame)
            from >= 0 && h.heard.nextSetBit(maxOf(from, h.fromFrame)).let { it in 0 until to }
        }
        val r = rs[best]
        val standsOut = runnerUp <= r * MAX_RUNNER_UP && (z >= MIN_Z || runnerUp <= r * CLEAR_RUNNER_UP)
        val sure = h.speechMs >= MIN_SPEECH_MS && heardLines >= MIN_LINES && r >= MIN_R && standsOut
        return Result(delayMs, sure, r, runnerUp, z, h.speechMs, heardLines)
    }

    /** The overlap (what [Result.r] measures) of [h] with the lines shown [delayMs] later, to the nearest 100 ms. */
    fun rAt(h: Heard, subs: Lines, delayMs: Long): Double = rAtLag(h, subs.ys, lagOf(delayMs))

    /**
     * What each block of [h] adds to the overlap with the lines shown [delayMs] later: above 0 on the whole where that
     * delay fits, around 0 where it doesn't. Added up along the film, it shows where a delay stops fitting.
     */
    fun fits(h: Heard, subs: Lines, delayMs: Long): DoubleArray {
        val lag = lagOf(delayMs)
        val ys = subs.ys
        return DoubleArray(h.blocks) { i ->
            val j = h.idx[i] - lag
            if (j in ys.indices) h.xs[i] * ys[j] else 0.0
        }
    }

    private fun lagOf(delayMs: Long): Int = Math.floorDiv(delayMs + 50, 100L).toInt()

    /** Speech at block t goes with the subtitle that was at t - [lag] before shifting. */
    private fun rAtLag(h: Heard, ys: DoubleArray, lag: Int): Double {
        val idx = h.idx
        val xs = h.xs
        var syy = 0.0
        var sxy = 0.0
        for (i in 0 until h.blocks) {
            val j = idx[i] - lag
            if (j < 0 || j >= ys.size) continue
            val y = ys[j]
            syy += y * y; sxy += xs[i] * y
        }
        return if (syy <= 0) 0.0 else sxy / sqrt(h.sxx * syy)
    }

    /** Takes away from each value the average of the [SMOOTH] values either side of it. */
    private fun detrend(v: DoubleArray, n: Int) {
        if (n == 0) return
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + v[i]
        for (i in 0 until n) {
            val a = maxOf(0, i - SMOOTH)
            val b = minOf(n, i + SMOOTH + 1)
            v[i] -= (prefix[b] - prefix[a]) / (b - a)
        }
    }

    /** Correlation of talking (where heard, within [h]'s stretch) with the lines moved [lag] marks later. */
    private fun fineR(h: Heard, subs: BitSet, lag: Int): Double {
        val speech = h.speech
        val heard = h.heard
        var n = 0.0; var sx = 0.0; var sy = 0.0; var sxy = 0.0
        var i = heard.nextSetBit(h.fromFrame)
        while (i in 0 until h.toFrame) {
            val x = speech.get(i)
            val j = i - lag
            val y = j >= 0 && subs.get(j)
            n++
            if (x) sx++
            if (y) sy++
            if (x && y) sxy++
            i = heard.nextSetBit(i + 1)
        }
        val varX = sx - sx * sx / n
        val varY = sy - sy * sy / n
        if (varX <= 0 || varY <= 0) return 0.0
        return (sxy - sx * sy / n) / sqrt(varX * varY)
    }
}
