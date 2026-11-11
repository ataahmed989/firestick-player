package app.fireplayer.player

import app.fireplayer.subs.SyncMapping

/**
 * Which timestamps from SkipDB and IntroDB are trusted for this very file, all in milliseconds. Kept free of Android
 * and of the network so it can be tested on its own.
 *
 * SkipDB is asked with the file's length and says how its times fit it (exact, shifted, or too different); IntroDB
 * doesn't know any length, so each of its parts is weighed on its own (an ad put in after the intro moves the credits
 * and leaves the intro right): see [introDb]. Times are never stretched: a time that doesn't fit is left out, and the
 * next source may fill that part in. Lengths agreeing is evidence, never proof: a database's times only ever get
 * buttons; nothing happens by itself on them (see [Segments.autoAt]).
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
    const val INTRODB_SURE = 0.9
    // TMDB gives whole minutes, sometimes rounded either way.
    const val RUNTIME_SLACK_MS = 90_000L
    const val OUTRO_ANCHOR_MS = 5_000L
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
     * What there is to go on that IntroDB's times were taken on a file like this one. [tmdbAgrees]: TMDB's length for
     * the title is within [RUNTIME_SLACK_MS] of the file's. [creditsEndHere]: IntroDB's own credits end where this
     * file does (see [outroAnchors]).
     */
    class Evidence(val tmdbAgrees: Boolean, val creditsEndHere: Boolean)

    /**
     * IntroDB's word on one part. [none] is its "confirmed: no such part". Each part is weighed on its own:
     * - credits (and what follows them) need their own end to land on this file's end; TMDB's length says nothing
     *   about where in the file they are.
     * - an intro or recap needs two signs out of three: TMDB's length agreeing, IntroDB's credits ending here (the
     *   same length), and IntroDB being sure of it ([INTRODB_SURE]). TMDB's length alone is never enough, and a copy
     *   longer for an ad after the intro keeps its intro when the other two signs hold.
     */
    fun introDb(part: Part, s: IntroDbSegment?, none: Boolean, evidence: Evidence, duration: Long): Pick {
        if (none) return Pick.None
        if (s == null) return Pick.Missing
        if (s.confidence < INTRODB_MIN_CONFIDENCE) return Pick.Rejected("confidence ${s.confidence}")
        when (part) {
            Part.OUTRO, Part.PREVIEW -> if (!evidence.creditsEndHere) return Pick.Rejected("credits don't end where this file does")
            Part.INTRO, Part.RECAP -> {
                val signs = listOf(evidence.tmdbAgrees, evidence.creditsEndHere, s.confidence >= INTRODB_SURE).count { it }
                if (signs < 2) return Pick.Rejected("only $signs sign the times fit this file")
            }
        }
        return fits(part, s.start, s.end, duration)
    }

    /** True when TMDB's length (whole minutes) agrees with the file's. */
    fun runtimeAgrees(tmdbMinutes: Int, duration: Long): Boolean =
        tmdbMinutes > 0 && duration > 0 && Math.abs(duration - tmdbMinutes * 60_000L) <= RUNTIME_SLACK_MS

    /**
     * True when IntroDB's credits end where this file ends (within [OUTRO_ANCHOR_MS]): its credits were timed on a
     * file that ends like this one. Evidence for the credits; for the intro, one sign among others.
     */
    fun outroAnchors(outro: IntroDbSegment?, duration: Long): Boolean =
        outro != null && duration > 0 && outro.confidence >= INTRODB_MIN_CONFIDENCE &&
            Math.abs(duration - outro.end) <= OUTRO_ANCHOR_MS

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

    /**
     * SkipDB's own times (asked with adjust=none, so nothing of its shifting is applied twice) taken as times on the
     * downloaded subtitles' timeline. Only an "exact" answer, when asked with that timeline's length
     * ([app.fireplayer.subs.SyncMapping.cleanLength]), is evidence it was timed on the subtitles' version of the
     * episode; the same episode id alone isn't.
     */
    fun skipDbOnSubtitles(part: Part, s: SkipDbSegment?, cleanLength: Long): Pick {
        if (s == null) return Pick.Missing
        if (s.start == 0L && s.end == 0L) return Pick.None
        if (s.match != "exact") return Pick.Rejected("not timed on the subtitles' version (${s.match})")
        if (s.confidence < EXACT_MIN_CONFIDENCE) return Pick.Rejected("exact, but confidence ${s.confidence}")
        return fits(part, s.start, s.end, cleanLength)
    }

    /**
     * A part found on the subtitles' timeline ([onSubtitles], from [skipDbOnSubtitles] or from [introDb] weighed
     * against the subtitles' length) put on this file's through the offsets auto sync confirmed ([sync]). Both ends
     * must map under one confirmed offset; then the file's own bounds are checked again. Passing [fits] here only
     * says the times make sense for the file, the evidence for the alignment is what got the part this far.
     * Such times only ever get buttons, like every database's ([Segments.autoAt] stays with the file's chapters).
     */
    fun mapped(part: Part, onSubtitles: Pick, sync: SyncMapping, duration: Long): Pick {
        if (onSubtitles !is Pick.Found) return onSubtitles
        val range = sync.fileRangeOf(onSubtitles.start, onSubtitles.end, duration)
            ?: return Pick.Rejected("not lined up by auto sync there (or across a break)")
        return fits(part, range.first, range.last, duration)
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
