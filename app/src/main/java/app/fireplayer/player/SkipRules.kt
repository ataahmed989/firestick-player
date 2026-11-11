package app.fireplayer.player

/**
 * Which timestamps from SkipDB and IntroDB are trusted for this very file, all in milliseconds. Kept free of Android
 * and of the network so it can be tested on its own.
 *
 * SkipDB is asked with the file's length and says how its times fit it (exact, shifted, or too different); IntroDB
 * doesn't know any length, so its times count only when TMDB's length for the title agrees with the file's. Times
 * are never stretched: a time that doesn't fit is left out, and the next source may fill that part in.
 */
object SkipRules {

    enum class Part { INTRO, RECAP, OUTRO, PREVIEW }

    /** What a source says about one part: found where, said not to exist, not known, or not trusted for this file. */
    sealed class Pick {
        class Found(val start: Long, val end: Long) : Pick()
        object None : Pick()
        object Missing : Pick()
        class Rejected(val why: String) : Pick()
    }

    /** One SkipDB segment as it came. */
    class SkipDbSegment(
        val start: Long,
        val end: Long,
        val match: String,
        val offset: Long,
        val confidence: Double,
    )

    /** One IntroDB segment as it came. */
    class IntroDbSegment(val start: Long, val end: Long, val confidence: Double)

    // SkipDB's own ranges: "exact" within 2 s of the file's length, "shifted" within 15 s.
    const val SHIFT_MAX_MS = 15_000L
    const val EXACT_MIN_CONFIDENCE = 0.5
    const val SHIFTED_MIN_CONFIDENCE = 0.7
    const val INTRODB_MIN_CONFIDENCE = 0.5
    // TMDB gives whole minutes, sometimes rounded either way.
    const val RUNTIME_SLACK_MS = 90_000L
    // An end a little past the file's (rounding) is taken as its end.
    private const val END_SLACK_MS = 2_000L
    private const val OPENING_MAX_MS = 5 * 60_000L
    private const val OPENING_MIN_MS = 3_000L
    private const val OUTRO_MIN_MS = 5_000L
    // Credits that end this close to the file's end run to its end; anything later than that is something after.
    const val TO_END_MS = 5_000L
    const val AFTER_MIN_MS = 15_000L

    /** SkipDB's word on one part, for a file [duration] long. A null segment is missing data, not "none". */
    fun skipDb(part: Part, s: SkipDbSegment?, duration: Long): Pick {
        if (s == null) return Pick.Missing
        // A submitter confirms "no such part" with 0 to 0.
        if (s.start == 0L && s.end == 0L) return Pick.None
        when (s.match) {
            "exact" -> if (s.confidence < EXACT_MIN_CONFIDENCE) return Pick.Rejected("exact, but confidence ${s.confidence}")
            "shifted" -> {
                if (s.confidence < SHIFTED_MIN_CONFIDENCE) return Pick.Rejected("shifted, confidence ${s.confidence}")
                if (Math.abs(s.offset) > SHIFT_MAX_MS) return Pick.Rejected("shifted by ${s.offset} ms")
            }
            else -> return Pick.Rejected("match ${s.match}")
        }
        return fits(part, s.start, s.end, duration)
    }

    /**
     * IntroDB's word on one part. [none] is its "confirmed: no such part". [runtimeAgrees]: TMDB's length for the
     * title is within [RUNTIME_SLACK_MS] of the file's, the only sign its times were taken on a file like this one.
     */
    fun introDb(part: Part, s: IntroDbSegment?, none: Boolean, runtimeAgrees: Boolean, duration: Long): Pick {
        if (none) return Pick.None
        if (s == null) return Pick.Missing
        if (!runtimeAgrees) return Pick.Rejected("length doesn't agree with TMDB's")
        if (s.confidence < INTRODB_MIN_CONFIDENCE) return Pick.Rejected("confidence ${s.confidence}")
        return fits(part, s.start, s.end, duration)
    }

    /** True when TMDB's length (whole minutes) agrees with the file's. */
    fun runtimeAgrees(tmdbMinutes: Int, duration: Long): Boolean =
        tmdbMinutes > 0 && duration > 0 && Math.abs(duration - tmdbMinutes * 60_000L) <= RUNTIME_SLACK_MS

    /** Times that make sense for this part of a file [duration] long; anything else is rejected. */
    fun fits(part: Part, start: Long, end: Long, duration: Long): Pick {
        if (duration <= 0) return Pick.Rejected("no length")
        if (start < 0 || end <= start) return Pick.Rejected("bad range $start..$end")
        if (end > duration + END_SLACK_MS) return Pick.Rejected("ends past the file")
        val e = minOf(end, duration)
        return when (part) {
            Part.INTRO, Part.RECAP -> when {
                e - start < OPENING_MIN_MS -> Pick.Rejected("too short")
                e - start > OPENING_MAX_MS -> Pick.Rejected("too long")
                e > duration / 2 -> Pick.Rejected("not in the first half")
                else -> Pick.Found(start, e)
            }
            Part.OUTRO -> when {
                start < duration / 2 -> Pick.Rejected("not in the second half")
                e - start < OUTRO_MIN_MS -> Pick.Rejected("too short")
                else -> Pick.Found(start, e)
            }
            Part.PREVIEW -> if (start < duration / 2) Pick.Rejected("not in the second half") else Pick.Found(start, e)
        }
    }

    /** True when a part is worth asking the next source about: unknown, or not trusted. "None" is an answer. */
    fun open(p: Pick?): Boolean = p == null || p is Pick.Missing || p is Pick.Rejected

    /** The first source's picks, with the second's for the parts the first left open. */
    fun combine(first: Map<Part, Pick>, second: Map<Part, Pick>?): Map<Part, Pick> {
        if (second == null) return first
        return Part.values().associateWith { part ->
            val a = first[part]
            if (open(a) && second[part] is Pick.Found) second[part]!! else a ?: second[part] ?: Pick.Missing
        }
    }

    /**
     * Where the parts are, for the buttons; null when nothing was found. Something after the credits (they end well
     * before the file does, or a preview follows them) is kept to be watched: no end card, Skip credits instead.
     */
    fun segments(picks: Map<Part, Pick>, duration: Long, from: String): Segments? {
        fun found(p: Part) = picks[p] as? Pick.Found
        val intro = found(Part.INTRO)
        val recap = found(Part.RECAP)
        val outro = found(Part.OUTRO)
        val preview = found(Part.PREVIEW)
        var after: Long? = null
        var toEnd = false
        if (outro != null) {
            if (duration - outro.end > AFTER_MIN_MS) after = outro.end
            else if (preview != null && preview.start >= outro.start) after = maxOf(preview.start, outro.start + 1)
            toEnd = after == null && duration - outro.end <= TO_END_MS
        }
        if (intro == null && recap == null && outro == null) return null
        return Segments(
            recap?.let { it.start..it.end }, intro?.let { it.start..it.end },
            outro?.start, after, from, toEnd,
        )
    }
}
