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
        val tl = SpeechTimeline()
        val until = frame(untilMs)
        for (c in snap.covered) {
            val a = frame(c[0])
            val b = minOf(frame(c[1]), until)
            if (b > a) tl.heard.set(a, b)
        }
        for (c in snap.cues) {
            val a = frame(c[0])
            val b = minOf(frame(c[1]), until)
            if (b > a) tl.speech.set(a, b)
        }
        // Talking only counts where the film was loaded.
        tl.speech.and(tl.heard)
        timeline = tl
    }

    private fun frame(ms: Long): Int = (ms / 10).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    companion object {
        // Steps taken in one go: loading can run minutes ahead at the start or after a jump; the rest waits a moment.
        const val MAX_STEPS = 6
        // A stretch already loaded when the film's track is taken up: its last 4 minutes are gone over.
        const val CATCH_UP_MS = 4 * 60_000L
    }
}
