package app.fireplayer.subs

import java.util.TreeMap

/**
 * The cue times of one of the film's own subtitle tracks, as they load ([EmbeddedCues]): what auto sync lines
 * downloaded subtitles up against when the file has one. A track muxed into the file is timed to this very file, ads
 * and bumpers included, so where its cues are on screen is a far better guide than where people talk: any language
 * will do (only the timing is used, not the words), and picture subtitles too.
 *
 * Plain Kotlin (no Android, no Media3), so it can be tested on its own. Not thread safe: [EmbeddedCues] guards it.
 */
class ReferenceCues(val info: Info) {

    /** What the file says about the track. [picture]: Blu-ray, DVD or DVB pictures rather than text. */
    class Info(
        val id: String?,
        val language: String?,
        val label: String?,
        val forced: Boolean,
        /** Marked for the deaf or hard of hearing (SDH, CC): sound notes in brackets, which aren't anyone talking. */
        val hearingImpaired: Boolean,
        val picture: Boolean,
    ) {
        /**
         * The track as a choice to keep (for a show, so its other episodes use it too): its language, name and kind,
         * not its number in the file, which other episodes may not share.
         */
        val key: String
            get() = listOf(language ?: "und", label.orEmpty(), if (picture) "pictures" else "text", if (hearingImpaired) "sdh" else "")
                .joinToString("|")

        /** For people: "Spanish", "Spanish (Castilian)", "English SDH", "French (pictures)". */
        fun name(): String {
            val lang = language?.takeIf { it.isNotBlank() && it != "und" }
                ?.let { java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.ENGLISH).takeIf { n -> n.isNotBlank() && n != it } ?: it }
            val base = when {
                lang == null -> label?.takeIf { it.isNotBlank() } ?: "Unknown language"
                label.isNullOrBlank() || label.equals(lang, ignoreCase = true) -> lang
                label.contains(lang, ignoreCase = true) -> label
                else -> "$lang ($label)"
            }
            return base + (if (hearingImpaired && !SDH.containsMatchIn(base)) " SDH" else "") + (if (picture) " (pictures)" else "")
        }

        override fun toString() = buildString {
            append(language ?: "und")
            if (picture) append(" pictures")
            if (hearingImpaired) append(" SDH")
            if (forced) append(" forced")
            label?.let { append(" \"").append(it).append('"') }
        }
    }

    // Cues by start (ms) to end (ms); an end of OPEN until the next cue or a clear says when it went.
    private val cues = TreeMap<Long, Long>()
    // Where a cue with no end of its own waits for one (pictures are shown until the next one, or a clear).
    private var openFrom = -1L

    /** Cues with words (or pictures) said so far. */
    val count: Int get() = cues.size

    /**
     * One sample of the track at [startMs]: [cueCount] cues (none: everything is cleared from here), shown for
     * [durationMs] (null: until the next sample), with [texts] the words of the text cues among them.
     */
    fun add(startMs: Long, durationMs: Long?, cueCount: Int, texts: List<String>) {
        if (startMs < 0) return
        // Whatever was shown with no end of its own ends here.
        closeOpen(startMs)
        if (cueCount == 0) return
        // Only sound notes or song lyrics: nobody talking, as for downloaded subtitles (SubtitleTimes).
        if (texts.size == cueCount && texts.none { talks(it) }) return
        if (durationMs != null && durationMs > 0) {
            val end = startMs + minOf(durationMs, MAX_CUE_MS)
            // A cue loaded again (playing that part again) is the same cue.
            cues[startMs] = maxOf(end, cues[startMs]?.takeIf { it != OPEN } ?: end)
        } else {
            if (cues[startMs].let { it == null || it == OPEN }) cues[startMs] = OPEN
            openFrom = startMs
        }
        while (cues.size > MAX_CUES) cues.pollFirstEntry()
    }

    private fun closeOpen(atMs: Long) {
        val from = openFrom
        if (from < 0) return
        openFrom = -1
        if (cues[from] == OPEN && atMs > from) cues[from] = from + minOf(atMs - from, MAX_CUE_MS)
    }

    /** The cues as (start, end) pairs, film time in ms; a cue still showing ends at [untilMs] at the latest. */
    fun intervals(untilMs: Long): List<LongArray> = cues.entries.map { (s, e) ->
        longArrayOf(s, if (e == OPEN) minOf(s + MAX_CUE_MS, maxOf(untilMs, s + 1)) else e)
    }

    /** Cues per minute of [coveredMs] film loaded: a track with only a few (forced, or signs) isn't worth lining up to. */
    fun perMinute(coveredMs: Long): Double = if (coveredMs <= 0) 0.0 else count * 60_000.0 / coveredMs

    companion object {
        private const val OPEN = Long.MIN_VALUE
        // A cue longer than this is a sign or a picture never cleared: as talking, it would only blur the timing.
        private const val MAX_CUE_MS = 10_000L
        // About two films' worth of lines, so memory stays small (16 bytes a cue) whatever the file does.
        private const val MAX_CUES = 6_000
        /** Fewer cues than this per minute of film loaded (over a few minutes): only some lines, as forced tracks have. */
        const val MIN_PER_MINUTE = 2.0

        // Sound notes in brackets ("[door slams]", "(MUSIC)") and lyrics ("♪ ... ♪"); a speaker's name before a colon.
        private val NOTE = Regex("""\[[^\]]*]|\([^)]*\)|[♪♫#*][^♪♫#*]*[♪♫#*]?""")
        private val SPEAKER = Regex("""^\s*[-–]?\s*[\p{Lu}][\p{Lu}\d .'’-]{1,24}:""", RegexOption.MULTILINE)
        private val TAGS = Regex("""\{[^}]*\}|<[^>]*>""")

        /** Whether a cue's [text] has anyone talking in it: something left once sound notes and lyrics are taken out. */
        fun talks(text: String): Boolean {
            val words = text.replace(TAGS, " ").replace(NOTE, " ").replace(SPEAKER, " ")
            return words.any { it.isLetterOrDigit() }
        }

        /**
         * Which of [tracks] (with how many cues each has had over [coveredMs] of film loaded) to line up against, or
         * null for none: never a forced track (only a few lines), nor one with too few cues for the film loaded
         * (once [MIN_COVERED_MS] of it says so); regular subtitles before SDH/CC; text before pictures (sound notes
         * can be told from talking); the one with the most cues among equals.
         */
        fun choose(tracks: List<ReferenceCues>, coveredMs: Long): ReferenceCues? = tracks
            .filter { !it.info.forced }
            .filter { coveredMs < MIN_COVERED_MS || it.perMinute(coveredMs) >= MIN_PER_MINUTE }
            .sortedWith(compareBy<ReferenceCues>({ it.info.hearingImpaired }, { it.info.picture }, { -it.count }))
            .firstOrNull()

        /** Film loaded before a track's cue count says anything about it (forced tracks look like others at first). */
        const val MIN_COVERED_MS = 3 * 60_000L

        /** Whether a track's [label] (its name in the file) says it's forced, or for the hard of hearing. */
        fun forcedLabel(label: String?): Boolean = label != null && FORCED.containsMatchIn(label)

        fun hearingImpairedLabel(label: String?): Boolean = label != null && SDH.containsMatchIn(label)

        private val FORCED = Regex("""(?i)\bforced\b|\bforc[ée]s?\b|\bsigns?\b""")
        private val SDH = Regex("""(?i)\bSDH\b|\bCC\b|hearing|\bHI\b|sourds|malentendants""")
    }
}
