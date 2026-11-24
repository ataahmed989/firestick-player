package app.fireplayer.player

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Whether a film's picture is IMAX's expanded ratio, by measuring it, strictly: a copy counts as IMAX here only when
 * most of what was sampled is 1.90:1 (a 3840×2020 frame, or letterboxing that leaves 1.90:1) and none of it is a
 * full-screen 16:9 picture. A film that switches between widescreen and full screen, or between 2.39:1 and 1.90:1,
 * isn't counted: it can't be told for sure. Only films: a show's episodes are never IMAX here.
 *
 * Each sample is one measurable frame's picture width over its height without the black bars; the sampler leaves out
 * frames it can't measure (black or very dark ones). Pure, and nothing is made: the caller keeps the samples.
 */
object ImaxAspect {

    /** IMAX's expanded ratio as home releases carry it (3840×2020, 1920×1010). */
    const val IMAX = 1.90f

    /** A sample this close to a ratio counts as it. */
    const val NEAR = 0.03f

    /** A sample at or under this is a full-screen picture (16:9 is 1.78, IMAX's full frame 1.43). */
    const val FULL_SCREEN = 1.80f

    /** Fewer samples than this say nothing: every look of one play ([AspectSamples.PLAN_MS]), or of several together. */
    const val MIN_SAMPLES = 8

    /** The share of the samples one ratio must hold to be the picture's steady ratio. */
    const val STEADY_SHARE = 0.85f

    /** A widescreen picture with more full-screen samples than this share switches between the two. */
    const val FULL_SCREEN_SHARE = 0.02f

    enum class Verdict { IMAX, NOT_IMAX, UNKNOWN }

    /**
     * The ratio the first [count] of [samples] hold steadily (to two decimals), or 0 when there are too few, when no one
     * ratio holds [STEADY_SHARE] of them, or when a widescreen picture also has full-screen stretches.
     */
    fun steady(samples: FloatArray, count: Int): Float {
        val n = minOf(count, samples.size)
        if (n < MIN_SAMPLES) return 0f
        var bestAt = -1
        var bestCount = 0
        var full = 0
        for (i in 0 until n) {
            val a = samples[i]
            if (a <= FULL_SCREEN) full++
            var near = 0
            for (j in 0 until n) if (abs(samples[j] - a) <= NEAR) near++
            if (near > bestCount) {
                bestCount = near
                bestAt = i
            }
        }
        if (bestAt < 0 || bestCount < n * STEADY_SHARE) return 0f
        val ratio = samples[bestAt]
        if (ratio > FULL_SCREEN && full > n * FULL_SCREEN_SHARE) return 0f
        return (ratio * 100).roundToInt() / 100f
    }

    /** [steady] over the looks kept of a copy ([MediaSpec.aspects], from every play of it). */
    fun steady(samples: List<AspectSample>): Float =
        if (samples.size < MIN_SAMPLES) 0f else steady(FloatArray(samples.size) { samples[it].ratio.toFloat() }, samples.size)

    /** Whether a steady measured [aspect] ([steady]) is IMAX's expanded ratio. */
    fun isImax(aspect: Float): Boolean = aspect > 0f && abs(aspect - IMAX) <= NEAR

    /** IMAX, not IMAX, or unknown (too few samples, or not a [film]) from the first [count] of [samples]. */
    fun verdict(samples: FloatArray, count: Int, film: Boolean): Verdict {
        if (!film || minOf(count, samples.size) < MIN_SAMPLES) return Verdict.UNKNOWN
        return if (isImax(steady(samples, count))) Verdict.IMAX else Verdict.NOT_IMAX
    }
}
