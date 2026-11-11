package app.fireplayer.subs

/**
 * Steps an [OffsetTracker] along one of the film's own subtitle tracks as it loads ([EmbeddedCues]), in place of the
 * talking heard: where its cues are on screen stands for talking, and the film loaded for what was heard. It's stepped
 * just as listening steps it (every [OffsetTracker.STEP_MS] of film, with nothing ahead of that visible), only along
 * where loading has got to rather than where playing has, so a change is usually found before it's on screen.
 *
 * Plain Kotlin, so tests drive it the way [AutoSync]'s worker thread does.
 */
class ReferenceSync {

    /** The track as a timeline, up to where the tracker was last stepped. */
    var timeline = SpeechTimeline()
        private set
    // The loading stretch being followed (where it began), and how far along it the tracker has been stepped.
    private var runFrom = Long.MIN_VALUE
    private var steppedTo = -1L

    /** How far the tracker has been stepped (film time, ms): the end of [timeline]; -1 before the first step. */
    val steppedToMs: Long get() = steppedTo

    /** Whether [source] has loaded enough since the last step for another (or started loading somewhere else). */
    fun due(source: EmbeddedCues.Source): Boolean {
        val (from, to) = source.loading()
        return from >= 0 && (from != runFrom || to - EmbeddedCues.SETTLE_MS - steppedTo >= OffsetTracker.STEP_MS)
    }

    /**
     * Brings [tracker] up to what [snap] has loaded: steps it every [OffsetTracker.STEP_MS] of film, at most [maxSteps]
     * times (the rest waits for the next call). Loading starting somewhere else is a jump, as playing jumping is.
     * Of a stretch loaded before this was first called, only the last [catchUpMs] is gone over.
     */
    fun advance(
        snap: EmbeddedCues.Snapshot,
        tracker: OffsetTracker,
        maxSteps: Int = MAX_STEPS,
        catchUpMs: Long = CATCH_UP_MS,
    ): List<OffsetTracker.Decision> {
        val edge = snap.loadedToMs - EmbeddedCues.SETTLE_MS
        if (snap.loadingFromMs < 0 || edge < 0) return emptyList()
        if (snap.loadingFromMs != runFrom) {
            runFrom = snap.loadingFromMs
            steppedTo = maxOf(runFrom, edge - catchUpMs, 0L)
            rebuild(snap, steppedTo)
            tracker.seeked(steppedTo, timeline)
        }
        val out = ArrayList<OffsetTracker.Decision>()
        while (edge - steppedTo >= OffsetTracker.STEP_MS && out.size < maxSteps) {
            steppedTo += OffsetTracker.STEP_MS
            rebuild(snap, steppedTo)
            out += tracker.step(timeline, steppedTo, OffsetTracker.STEP_MS)
        }
        return out
    }

    /** The cues as talking and the film loaded as heard, up to [untilMs] only, as heard so far when listening. */
    private fun rebuild(snap: EmbeddedCues.Snapshot, untilMs: Long) {
        timeline = timelineOf(snap.cues, snap.covered, untilMs)
    }

    /** How well one of the film's own tracks lines up with the downloaded subtitles ([rank]). */
    class Ranked(
        val info: ReferenceCues.Info,
        /** Its best overlap with the downloaded lines, how far that stands out, and the share of their lines it has. */
        val r: Double,
        val z: Double,
        val coverage: Double,
        val strength: Strength,
        val cues: Int,
    ) {
        override fun toString() = "${info.name()} ${strength.label} (r=${"%.2f".format(java.util.Locale.ROOT, r)}, " +
            "z=${"%.1f".format(java.util.Locale.ROOT, z)}, ${(coverage * 100).toInt()}% of lines, $cues cues)"
    }

    enum class Strength(val label: String) { STRONG("strong match"), FAIR("fair match"), WEAK("weak match") }

    companion object {
        // Steps taken in one go: loading can run minutes ahead at the start or after a jump; the rest waits a moment.
        const val MAX_STEPS = 6
        // A stretch already loaded when the film's track is taken up: its last 4 minutes are gone over.
        const val CATCH_UP_MS = 4 * 60_000L
        // How strong a track's match is: at least this overlap, standing out this far, with this share of the lines.
        private const val STRONG_R = 0.45
        private const val STRONG_Z = 4.5
        private const val STRONG_COVERAGE = 0.6
        private const val FAIR_R = 0.3
        private const val FAIR_Z = 3.0
        // Of the film loaded, the last this much is gone over to rank the tracks (the cost is this × the shifts tried).
        private const val RANK_WINDOW_MS = 6 * 60_000L

        /** [cues] as talking where [covered] (loaded) as heard, up to [untilMs]. */
        fun timelineOf(cues: List<LongArray>, covered: List<LongArray>, untilMs: Long): SpeechTimeline {
            val tl = SpeechTimeline()
            val until = frame(untilMs)
            for (c in covered) {
                val a = frame(c[0])
                val b = minOf(frame(c[1]), until)
                if (b > a) tl.heard.set(a, b)
            }
            for (c in cues) {
                val a = frame(c[0])
                val b = minOf(frame(c[1]), until)
                if (b > a) tl.speech.set(a, b)
            }
            // Talking only counts where the film was loaded.
            tl.speech.and(tl.heard)
            return tl
        }

        /**
         * The film's own [tracks] best first for lining [lines] up against, each with how well it does over the film
         * loaded ([covered], up to [untilMs]), around [centerMs] ± [rangeMs]. By how strong the match is ([Strength]),
         * then regular subtitles before SDH before pictures, then the languages in [preferred] (the downloaded
         * subtitles' own first: their lines are split alike), then the overlap itself, then the file's order. Strength
         * comes in steps and the language before the exact overlap so the choice doesn't change from one opening to
         * the next with which lines happen to have loaded.
         */
        fun rank(
            tracks: List<EmbeddedCues.Track>,
            covered: List<LongArray>,
            untilMs: Long,
            lines: SyncMatcher.Lines,
            centerMs: Long,
            rangeMs: Long,
            preferred: List<String>,
        ): List<Ranked> {
            val lastCovered = minOf(untilMs, covered.maxOfOrNull { it[1] } ?: 0L)
            val from = maxOf(0L, lastCovered - RANK_WINDOW_MS)
            val ranked = tracks.map { t ->
                val tl = timelineOf(t.cues, covered, untilMs)
                val h = SyncMatcher.heard(tl.speech, tl.heard, frame(from), frame(lastCovered) + 1)
                if (h == null) return@map Ranked(t.info, 0.0, 0.0, 0.0, Strength.WEAK, t.count)
                val res = SyncMatcher.match(h, lines, centerMs, rangeMs)
                val coverage = coverage(t.cues, covered, lines, res.delayMs, from, lastCovered)
                val strength = when {
                    res.r >= STRONG_R && res.z >= STRONG_Z && res.runnerUp <= 0.6 * res.r && coverage >= STRONG_COVERAGE -> Strength.STRONG
                    res.r >= FAIR_R && res.z >= FAIR_Z -> Strength.FAIR
                    else -> Strength.WEAK
                }
                Ranked(t.info, res.r, res.z, coverage, strength, t.count)
            }
            fun languageRank(info: ReferenceCues.Info): Int {
                val lang = info.language?.lowercase()?.substringBefore('-') ?: return preferred.size
                val i = preferred.indexOfFirst { it.lowercase().substringBefore('-') == lang }
                return if (i < 0) preferred.size else i
            }
            return ranked.withIndex().sortedWith(
                compareBy<IndexedValue<Ranked>>(
                    { it.value.strength.ordinal },
                    { if (it.value.info.hearingImpaired) 1 else 0 },
                    { if (it.value.info.picture) 1 else 0 },
                    { languageRank(it.value.info) },
                    { -Math.round(it.value.r * 20) },
                    { it.index },
                ),
            ).map { it.value }
        }

        /** The share of [lines] on screen in [fromMs]..[toMs] (at [delayMs]) where loaded that one of [cues] overlaps. */
        private fun coverage(cues: List<LongArray>, covered: List<LongArray>, lines: SyncMatcher.Lines, delayMs: Long, fromMs: Long, toMs: Long): Double {
            val starts = LongArray(cues.size) { cues[it][0] }
            var total = 0
            var hit = 0
            for (l in lines.lines) {
                val a = l.startMs + delayMs
                val b = l.endMs + delayMs
                if (a < fromMs || b > toMs || covered.none { a >= it[0] && b <= it[1] }) continue
                total++
                // Cues are in order of start: the last one starting before this line ends, and the few before it.
                var i = java.util.Arrays.binarySearch(starts, b).let { if (it < 0) -it - 2 else it - 1 }
                var steps = 0
                while (i >= 0 && steps++ < 4) {
                    if (cues[i][1] > a) { hit++; break }
                    i--
                }
            }
            return if (total == 0) 0.0 else hit.toDouble() / total
        }

        private fun frame(ms: Long): Int = (ms / 10).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
    }
}
