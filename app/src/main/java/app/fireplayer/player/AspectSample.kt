package app.fireplayer.player

import java.util.Locale
import kotlin.math.abs

/**
 * One look at a film's picture as it played: its shape, width over height with any black bars in the file left out
 * ([ratio]: 2.39 for a scope film, 1.90 for IMAX's digital frame, 1.78 for a picture filling a 16:9 frame), and where
 * in the film it was taken ([atMs]). Kept with what was measured of the copy ([MediaSpec.aspects]), for whatever judges
 * the copy's shape from them.
 */
data class AspectSample(val ratio: Double, val atMs: Long) {
    companion object {
        /** At most this many kept for a copy, from every play of it. */
        const val MAX_KEPT = 16

        /** A sample of one play this close in the film to one of another stands for that part of the film. */
        private const val SAME_PART_MS = 30_000L

        /** "1.900@60000;2.390@120000": no "|" (what [MediaSpec.encode] splits on). */
        fun encode(samples: List<AspectSample>): String =
            samples.joinToString(";") { String.format(Locale.US, "%.3f@%d", it.ratio, it.atMs) }

        /** What [encode] wrote; what doesn't read is left out. */
        fun decode(text: String?): List<AspectSample> {
            if (text.isNullOrEmpty()) return emptyList()
            return text.split(';').mapNotNull { part ->
                val at = part.indexOf('@')
                if (at <= 0) return@mapNotNull null
                val ratio = part.substring(0, at).toDoubleOrNull()?.takeIf { it > 0.0 && it < 10.0 } ?: return@mapNotNull null
                val ms = part.substring(at + 1).toLongOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
                AspectSample(ratio, ms)
            }
        }

        /**
         * This play's samples ([now]) with those of earlier plays ([kept]) from other parts of the film, in the film's
         * order: at most [MAX_KEPT], this play's first.
         */
        fun merged(kept: List<AspectSample>, now: List<AspectSample>): List<AspectSample> {
            if (kept.isEmpty()) return now
            val others = kept.filter { k -> now.none { abs(it.atMs - k.atMs) < SAME_PART_MS } }
            if (others.isEmpty()) return now
            return (now.take(MAX_KEPT) + others.take((MAX_KEPT - now.size).coerceAtLeast(0))).sortedBy { it.atMs }
        }
    }
}
