package app.fireplayer.subs

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.SideAudioDecoder
import app.fireplayer.data.Prefs
import app.fireplayer.player.SpeechTap
import app.fireplayer.subs.OffsetTracker.Outcome
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Lines up downloaded subtitles with the film's dialogue by itself, and keeps them lined up all through the film.
 *
 * While a downloaded subtitle is shown, it listens to the sound being played ([SpeechTap]) for talking
 * ([SpeechDetector]) and slides the subtitle lines over it ([SyncMatcher]). Once it's sure (two tries in a row agree,
 * usually within a minute or two of dialogue) the shift goes into the subtitle delay. Then it keeps listening, lightly,
 * for the timing changing part way through ([OffsetTracker]): IPTV films often have ad breaks or bumpers spliced in,
 * after which the subtitles are late. What it finds is a delay by film position, so going back across a break brings
 * the earlier delay back. It's kept per subtitle file and film, so the same pair is lined up at once next time.
 *
 * When the film has subtitles of its own ([EmbeddedCues]: any language, text or pictures), their timing is used instead
 * of the talking: a track muxed into the file is timed to this very file, ads included, and it's read as the film loads,
 * so a change is usually found before it's on screen, and nothing is listened to meanwhile but now and then a minute
 * or two, to check the track against the talking ([CrossCheck]: a track from another cut would be wrong all through).
 * Which track is ranked by how well each lines up ([ReferenceSync.rank]), or chosen in the Subtitle delay window
 * ([chooseSyncWith], kept per show). One source giving up hands over to the other: the track having too few lines or no
 * delay fitting it, to listening; listening giving up, or the sound not being there to hear, to the track; the track
 * disagreeing with the talking, to listening for good (in this film).
 *
 * A new episode starts from the delay the show's last one started with, for subtitles from the same source, checked as
 * it plays; with nothing to go on, a downloaded line is held back for a moment ([holdsCues]) while the film's own
 * subtitles, which load ahead, say where it goes.
 *
 * A delay set by hand always wins ([handOff]): it's one delay for the whole film, and auto sync stays out of it for
 * these subtitles. [resync] checks the stretch around where the film is again, between the changes either side, and
 * keeps every other delay found: against the film's own subtitles when there are some (and the talking at once, so a
 * track that's wrong there shows), against the talking when there aren't, the track disagreed, or Resync was pressed a
 * second time in a row. Against the talking, the delay in use there only comes back on talking heard after the press.
 * With nothing found yet (or after a delay set by hand) it finds the delay afresh. [undo] puts back the delay from before
 * auto sync and leaves it off for these subtitles.
 *
 * Kept light for the Fire TV Stick: one background thread at low priority (and FFmpeg's decode thread, when the sound
 * goes out undecoded, at the same), 10 ms marks of what was heard and of talking (about 180 KB for two hours),
 * nothing listened to beyond what plays, matching at most every 15 s of film and usually every few minutes (less often
 * while nothing heard fits), and it backs off while the picture drops frames. What it costs is logged every few
 * minutes (tag AutoSync).
 */
@OptIn(UnstableApi::class)
class AutoSync(
    private val tap: SpeechTap,
    private val prefs: Prefs,
    /** The subtitle delay now (µs), and setting it. Called on the main thread. */
    private val delayUs: () -> Long,
    private val setDelayUs: (Long) -> Unit,
    /** Where the film is and how long it is (ms), on the main thread; duration 0 while unknown. */
    private val position: () -> Pair<Long, Long>,
    /** Something to show changed (the state, or the delay). */
    private val onChanged: () -> Unit,
    /** The show the film is an episode of ("tmdb:ID"), or null: its jumps are remembered for its other episodes. */
    private val showKey: () -> String? = { null },
    /** The film's own subtitle tracks as they load, lined up against when there's one (see [EmbeddedCues]). */
    private val embedded: EmbeddedCues? = null,
    /** The address the film playing now was loaded from (its key in [embedded]); on the main thread. */
    private val filmUri: () -> String? = { null },
) {

    enum class State { OFF, LISTENING, SYNCED, NOT_SYNCED }

    var state = State.OFF
        private set
    /** What the panel says under the state, or null. */
    var detail: String? = null
        private set
    /** The delay before auto sync last moved it (µs), for undo; null when it hasn't. */
    var undoUs: Long? = null
        private set
    /** The downloaded subtitle file being followed, or null. */
    var file: File? = null
        private set
    /**
     * What auto sync has confirmed, for use elsewhere (the skip buttons): see [SyncMapping] for the contract. Null while
     * nothing is confirmed, under a delay set by hand, or with auto sync off. [onMappingChanged] hears every change.
     */
    var mapping: SyncMapping? = null
        private set
    var onMappingChanged: (() -> Unit)? = null
    private var mappingVersion = 0

    private val main = Handler(Looper.getMainLooper())
    private var key: String? = null
    private var lines: SyncMatcher.Lines? = null
    // Set when the delay was moved by hand, so nothing moves it again for this subtitle.
    private var byHand = false
    // The delays found, by film position: the main thread's copy of the listening thread's (empty while none).
    private var segments: List<OffsetTracker.Segment> = emptyList()
    // What the delays in use were last borne out by (see [Basis]).
    private var basis = Basis.NONE
    // The source the downloaded subtitles came from (their release, less the episode): the show's last episode's
    // starting delay is kept by it.
    private var subtitleSource: String? = null
    // The last episode's starting delay put in at the start, while it's checked.
    private var priorMs: Long? = null
    // The downloaded subtitles' language, when known: a track of the film's own in it is lined up against first.
    private var subtitleLanguage: String? = null
    // For these subtitles (their saved key), in this sitting: go by the talking first (Resync pressed twice in a row,
    // or the film's own subtitles disagreed with it), and a track of the film's own that disagreed with the talking.
    private var speechFirstKey: String? = null
    private val droppedTracks = HashMap<String, String>()
    private var lastResyncAt = 0L
    // Where Resync was last pressed, while its check is going on (the panel says so), or -1.
    private var checkingAt = -1L

    /** What the delays in use were last borne out by: shown in the Subtitle delay window. */
    enum class Basis { NONE, SAVED, PRIOR, TRACK, SPEECH }

    /**
     * The film's own subtitle tracks auto sync could line up against, best first, with how well each does: for the
     * "Sync with" choice. Empty while the film has none (or they haven't loaded yet).
     */
    var tracks: List<ReferenceSync.Ranked> = emptyList()
        private set
    /** The key of the film's own track being lined up against now, or null (listening). */
    var trackInUse: String? = null
        private set

    /** Something to show the subtitles again for: a line held back ([holdsCues]) may be shown now. */
    var onHoldEnded: (() -> Unit)? = null
    // A new episode with nothing to go on: downloaded lines are held back a moment while the film's own subtitles say
    // where they go ([holdsCues]); when the first one was.
    private var holding = false
    private var heldSince = 0L

    private val jobRef = java.util.concurrent.atomic.AtomicReference<Job?>(null)
    private var job: Job?
        get() = jobRef.get()
        set(v) = jobRef.set(v)
    @Volatile private var backOffUntil = 0L
    @Volatile private var backOffs = 0
    private var worker: Thread? = null

    /**
     * Listening for one subtitle file and film, carrying on from [segments] found before; with none, the first delay
     * is looked for within [centerMs] ± [rangeMs]. For Resync, [relearnAtMs] is where the film was.
     */
    private class Job(
        val generation: Int,
        val filmKey: String,
        val lines: SyncMatcher.Lines,
        val segments: List<OffsetTracker.Segment>,
        val centerMs: Long,
        val rangeMs: Long,
        val relearnAtMs: Long?,
        /** Jumps other episodes of the show made, tried first after a break. */
        val jumps: List<Long> = emptyList(),
        /** Where the film was loaded from, to find its own subtitles in [embedded] by; null to only listen. */
        val filmUri: String? = null,
        /** The film's own track to line up against (chosen, or picked for this film before); null for the best. */
        val trackKey: String? = null,
        /** Only ever the talking (chosen so). */
        val speechOnly: Boolean = false,
        /** The talking first; the film's own subtitles only should listening give up. */
        val speechFirst: Boolean = false,
        /** A track of the film's own that disagreed with the talking: never used. */
        val dropped: String? = null,
        /** The show's last episode's starting delay, tried first while nothing is known. */
        val priorMs: Long? = null,
        /** Languages to prefer among the film's own tracks, the downloaded subtitles' own first. */
        val preferred: List<String> = emptyList(),
    )

    /** Resync pressed: the stretch around [atMs] checked again; [speech]: by the talking, whatever there is. */
    private class Resync(val generation: Int, val atMs: Long, val speech: Boolean)

    private val resyncRef = java.util.concurrent.atomic.AtomicReference<Resync?>(null)

    private var generation = 0

    /**
     * The subtitle now shown: a downloaded file, or null for anything else (embedded, handed over, off). Following a
     * new file starts listening, or applies at once what was found for it before and listens on from there. [release]
     * is the release the file was made for, when known: subtitles from the same source start from the delay the show's
     * last episode started with.
     */
    fun follow(subtitle: File?, filmKey: String?, release: String? = null, language: String? = null) {
        if (subtitle == file && filmKey == key) return
        stopListening()
        clearMapping()
        stopHolding()
        file = subtitle
        key = filmKey
        undoUs = null
        byHand = false
        basis = Basis.NONE
        priorMs = null
        checkingAt = -1
        lines = null
        segments = emptyList()
        tracks = emptyList()
        trackInUse = null
        subtitleSource = release?.let { sourceOf(it) }
        subtitleLanguage = language
        if (subtitle == null || filmKey == null) {
            setState(State.OFF, null)
            return
        }
        // Set by hand the last time these subtitles were on: that delay again, without listening.
        val hand = prefs.handDelayMs(savedKey())
        if (hand != null) {
            byHand = true
            moveDelay(hand * 1000)
            setState(State.OFF, "Your delay from last time: ${SyncMatcher.formatShift(hand)}.")
            loadLines(subtitle) {}
            return
        }
        if (!prefs.autoSync) {
            setState(State.OFF, null)
            return
        }
        val saved = OffsetTracker.restore(prefs.syncedSegments(savedKey()), prefs.syncedDelayMs(savedKey()))
        if (saved != null) {
            segments = saved
            basis = Basis.SAVED
            OffsetTracker.followedDelayMs(byHand, true, segments, position().first)?.let { moveDelay(it * 1000) }
            setState(State.SYNCED, syncedText())
            startFollowing()
            // Listens on from what was found, in case the timing changes somewhere new this time.
            loadLines(subtitle) { start() }
            return
        }
        // A new episode: the delay the show's last one started with (subtitles from the same source), straight away.
        val prior = showStart()
        if (prior != null) {
            priorMs = prior
            basis = Basis.PRIOR
            val now = delayUs()
            if (abs(prior * 1000 - now) >= MIN_MOVE_US) {
                undoUs = now
                moveDelay(prior * 1000)
            }
            setState(State.LISTENING, "From the last episode (${SyncMatcher.formatShift(prior)}), checking…")
        } else if (embedded != null && syncWith() != SPEECH) {
            // Nothing to go on: the first lines wait a moment for the film's own subtitles, if it has any.
            holding = true
        }
        loadLines(subtitle) { start() }
    }

    /**
     * Resync: the viewer says the timing is wrong where the film is. With delays found, the stretch around here is
     * checked again (see the class notes) and the rest kept; with none (or after a delay set by hand), it's found
     * afresh around where the film is. Meanwhile the delay stays as it is.
     */
    fun resync() {
        val f = file ?: return
        val now = SystemClock.elapsedRealtime()
        // Pressed again soon after: the source used the first time didn't put it right, so the talking decides.
        val again = now - lastResyncAt < RESYNC_AGAIN_MS
        lastResyncAt = now
        if (again) speechFirstKey = savedKey()
        val at = position().first
        if (!byHand && segments.isNotEmpty() && state == State.SYNCED && prefs.autoSync) {
            stopHolding()
            checkingAt = at
            val ask = {
                // Not following now (it stopped, or was finding the first delay afresh): it carries on from what's known.
                if (job.let { it == null || it.relearnAtMs != null }) start()
                job?.let { resyncRef.set(Resync(it.generation, at, again)) }
            }
            if (lines == null) loadLines(f) { ask() } else ask()
            detail = checkingText()
            onChanged()
            return
        }
        stopListening()
        clearMapping()
        stopHolding()
        byHand = false
        basis = Basis.NONE
        priorMs = null
        segments = emptyList()
        prefs.forgetSyncedDelay(savedKey())
        prefs.forgetHandDelay(savedKey())
        if (lines == null) loadLines(f) { start(relearnAtMs = at) } else start(relearnAtMs = at)
    }

    /** The delay was changed by hand: auto sync stays out of it for this subtitle, and the delay is kept for it. */
    fun handOff(delayUs: Long) {
        if (file == null) return
        prefs.saveHandDelay(savedKey(), delayUs / 1000)
        stopHolding()
        if (state == State.OFF) return
        stopListening()
        clearMapping()
        byHand = true
        undoUs = null
        segments = emptyList()
        prefs.forgetSyncedDelay(savedKey())
        setState(State.OFF, "Off for these subtitles, as the delay was set by hand.")
    }

    /** Auto sync turned on or off in Settings, or for this subtitle from the Subtitle delay window. */
    fun setOn(on: Boolean) {
        if (on) {
            if (file != null && state == State.OFF) resync()
        } else {
            stopListening()
            clearMapping()
            stopHolding()
            setState(State.OFF, null)
        }
    }

    /** Puts the delay back to what it was before auto sync moved it. */
    fun undo() {
        val before = undoUs ?: return
        stopListening()
        clearMapping()
        stopHolding()
        setDelayUs(before)
        undoUs = null
        byHand = true
        segments = emptyList()
        prefs.forgetSyncedDelay(savedKey())
        setState(State.OFF, "Off for these subtitles; the delay is back to ${SyncMatcher.formatShift(before / 1000)}.")
    }

    /**
     * What to line up against, as chosen in the Subtitle delay window: null for the best of the film's own subtitle
     * tracks (or the talking without any), [SPEECH], or one track's key ([tracks]). Kept for the show, so its other
     * episodes use it too. What was found is kept; it carries on from there with the new choice.
     */
    fun chooseSyncWith(choice: String?) {
        prefs.saveSyncWith(choiceScope() ?: return, choice)
        if (choice != SPEECH && speechFirstKey == savedKey()) speechFirstKey = null
        if (choice != null && choice != SPEECH) droppedTracks.remove(savedKey())
        if (file == null || byHand || !prefs.autoSync || state == State.OFF) return
        stopListening()
        checkingAt = -1
        start()
    }

    /** The choice [chooseSyncWith] made for these subtitles' show (or film): null (auto), [SPEECH] or a track's key. */
    fun syncWith(): String? = choiceScope()?.let { prefs.syncWith(it) }

    /**
     * Whether a downloaded line about to show is held back: only at the start of a new episode with nothing to go on,
     * while the film's own subtitles may yet say where it goes, and for at most [HOLD_MS] from the first line held.
     */
    fun holdsCues(): Boolean {
        if (!holding) return false
        val now = SystemClock.elapsedRealtime()
        if (heldSince == 0L) {
            heldSince = now
            main.postDelayed(endHold, HOLD_MS)
        }
        return now - heldSince < HOLD_MS
    }

    private val endHold = Runnable { stopHolding() }

    private fun stopHolding() {
        if (!holding) return
        holding = false
        main.removeCallbacks(endHold)
        val held = heldSince > 0
        heldSince = 0
        if (held) onHoldEnded?.invoke()
    }

    /** The picture dropped frames: listening waits a few seconds, so the film comes first. */
    fun framesDropped() {
        backOffUntil = SystemClock.elapsedRealtime() + 5_000
        backOffs++
    }

    fun release() {
        mapping = null
        onMappingChanged = null
        onHoldEnded = null
        stopListening()
        main.removeCallbacksAndMessages(null)
        worker?.interrupt()
        worker = null
    }

    // ---- Following the delay along the film ------------------------------------

    /** While the delay changes along the film, it's kept to the one for where the film is (after a jump, too). */
    private val following = object : Runnable {
        override fun run() {
            if (state != State.SYNCED || byHand || segments.size < 2) return
            applyHere()
            main.postDelayed(this, FOLLOW_EVERY_MS)
        }
    }

    private fun startFollowing() {
        main.removeCallbacks(following)
        if (segments.size > 1) main.post(following)
    }

    /** Puts in the delay found for where the film is now, unless it's in already (to within what anyone sees). */
    private fun applyHere() {
        val ms = OffsetTracker.followedDelayMs(byHand, state == State.SYNCED, segments, position().first) ?: return
        if (abs(ms * 1000 - delayUs()) < MIN_MOVE_US) return
        moveDelay(ms * 1000)
        if (checkingAt < 0) detail = syncedText()
        onChanged()
    }

    /** What the panel says while synced, for the delay where the film is, and what bore it out. */
    private fun syncedText(): String {
        val i = OffsetTracker.indexAt(segments, position().first)
        val ms = segments.getOrNull(i)?.delayMs ?: (delayUs() / 1000)
        val what = when (basis) {
            Basis.TRACK -> "Lined up with the film's own subtitles"
            Basis.SPEECH -> "Lined up with the speech"
            Basis.SAVED -> "Lined up as last time"
            Basis.PRIOR -> "From the last episode"
            Basis.NONE -> "Lined up"
        }
        val where = if (i > 0) " here, after a break" else ""
        return "$what: ${SyncMatcher.formatShift(ms)}$where."
    }

    /** What the panel says while Resync's check is going on. */
    private fun checkingText(): String =
        if (trackInUse != null) "Checking the timing here again against the film's own subtitles and the speech…"
        else "Listening to check the timing here again…"

    /** The show's last episode's starting delay for subtitles from this source, if known. */
    private fun showStart(): Long? {
        val show = showKey() ?: return null
        val source = subtitleSource ?: return null
        return prefs.showStartMs(show, source)
    }

    /** Where a "Sync with" choice is kept: the show, or this film when it isn't known as an episode. */
    private fun choiceScope(): String? = showKey() ?: key?.let { "film:$it" }

    // ---- Listening ----------------------------------------------------------

    private fun loadLines(subtitle: File, then: () -> Unit) {
        val gen = ++generation
        Thread {
            // Whatever goes wrong reading them, it must not take the player down from this thread: no lines, not synced.
            val read = runCatching { SubtitleTimes.read(subtitle) }.getOrDefault(emptyList())
            // Worked out here, once: the matching side of the lines (about 0.6 MB for a two-hour film).
            val prepared = SyncMatcher.Lines(read)
            main.post {
                if (gen != generation || subtitle != file) return@post
                lines = prepared
                if (read.size < 20) setState(State.NOT_SYNCED, "These subtitles have too few lines to line up.") else then()
            }
        }.apply { isDaemon = true; name = "FirePlayerSyncRead" }.start()
    }

    /** Listens from what's known ([segments]), or for Resync afresh around [relearnAtMs]. */
    private fun start(relearnAtMs: Long? = null) {
        val l = lines ?: return
        val k = key ?: return
        if (l.lines.size < 20 || byHand) return
        val gen = ++generation
        val known = if (relearnAtMs == null) segments else emptyList()
        val jumps = showKey()?.let { prefs.showJumps(it) } ?: emptyList()
        val choice = syncWith()
        val speechOnly = choice == SPEECH
        // Even when only listening, the film's own tracks are ranked, for the "Sync with" choice.
        val uri = if (embedded != null) filmUri() else null
        val trackKey = choice?.takeIf { it != SPEECH } ?: prefs.syncTrack(savedKey())
        val prior = priorMs.takeIf { known.isEmpty() && relearnAtMs == null }
        val preferred = listOfNotNull(subtitleLanguage, prefs.subtitleLanguage.ifEmpty { null }, "en").distinct()
        job = Job(
            gen, k, l, known,
            centerMs = if (relearnAtMs != null) delayUs() / 1000 else prior ?: 0,
            // Resync looks further either way, around the delay in use: after a few breaks it can be minutes.
            rangeMs = if (relearnAtMs != null) OffsetTracker.RANGE_MS else SyncMatcher.RANGE_MS,
            relearnAtMs = relearnAtMs,
            jumps = jumps,
            filmUri = uri,
            trackKey = trackKey,
            speechOnly = speechOnly,
            speechFirst = speechFirstKey == savedKey(),
            dropped = droppedTracks[savedKey()],
            priorMs = prior,
            preferred = preferred,
        )
        if (known.isEmpty() && prior == null) {
            setState(State.LISTENING, if (uri != null && !speechOnly) "Lining up… (looking for the film's own subtitles, listening meanwhile)" else LISTENING_TEXT)
        }
        ensureWorker()
        tap.listening = true
    }

    private fun stopListening() {
        generation++
        job = null
        resyncRef.set(null)
        tap.listening = false
        main.removeCallbacks(following)
    }

    private fun ensureWorker() {
        if (worker?.isAlive == true) return
        worker = Thread({ listen() }, "FirePlayerSync").apply { isDaemon = true; start() }
    }

    /**
     * On the worker thread. Nothing may escape it: an exception there would take the whole app down. [release]
     * interrupting it ends it; anything else that goes wrong stops listening, and what was found stays in use.
     */
    private fun listen() {
        try {
            listenLoop()
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            Log.e(TAG, "Listening stopped", t)
            job?.let { stopped(it, "Couldn't line up with the speech. Try Resync, or set the delay by hand.") }
        }
    }

    /** Where the worker's time goes: looking for the film's own subtitles (listening meanwhile), on them, or listening. */
    private enum class Mode { LOOKING, TRACK, SPEECH }

    /**
     * Lines the subtitles up against the film's own subtitles when there are any worth it, else against the talking
     * (sound pieces read, talking marked), handing over from one to the other as the class notes say.
     */
    private fun listenLoop() {
        // Threads started from here (FFmpeg's decode thread in SideAudioDecoder) take this thread's Java priority, not
        // the nice value set below, so they'd run at normal priority next to playback. On Android, Java priority 4 is
        // nice 10, the same as THREAD_PRIORITY_BACKGROUND.
        Thread.currentThread().priority = Thread.NORM_PRIORITY - 1
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        // Kept while the film is the same (another subtitle file, Resync): what was heard is used again.
        val timeline = SpeechTimeline()
        val detector = SpeechDetector()
        var sampler: MonoDownsampler? = null
        var decoder: SideAudioDecoder? = null
        var decoderKind: SpeechTap.Kind? = null
        var cursor = -1.0
        var gen = -1
        var filmKey: String? = null
        var tracker: OffsetTracker? = null
        var publishedCount = -1
        var publishedSegments: List<OffsetTracker.Segment>? = null
        var publishedChanges: List<Long>? = null
        // 10 ms marks heard (new or again) since the job started, and at the last step.
        var played = 0L
        var stepAt = 0L
        // The sound's format just before, to notice it change in the middle of the film.
        var lastKind: SpeechTap.Kind? = null
        val cost = Cost()
        var mode = Mode.SPEECH
        // On the film's own subtitles: following them as they load, which track, and when the tracks are ranked again.
        var reference: ReferenceSync? = null
        var trackKey: String? = null
        var lookAt = 0L
        var lookingSince = 0L
        var rankAt = 0L
        var ranks = 0
        // In this job: the film's own subtitles failed (too few lines, no delay fits), listening failed (gave up, or the
        // sound can't be heard), a track that disagreed with the talking.
        var trackFailed = false
        var speechFailed = false
        var dropped: String? = null
        // What a decision of the delay in use being right was last told for (once per source).
        var keptTold: Basis? = null
        // Checking the film's own subtitles against the talking ([CrossCheck]): listening now, from where (film time,
        // once heard), since when, when next, whether after Resync, and the last verdict.
        var crossing = false
        var crossFrom = -1L
        var crossSince = 0L
        var crossNext = 0L
        var crossUrgent = false
        var lastVerdict: CrossCheck.Verdict? = null

        fun basisNow() = if (mode == Mode.TRACK) Basis.TRACK else Basis.SPEECH

        fun letDecoderGo() {
            decoder?.release(); decoder = null; decoderKind = null
        }

        /** A decision of [tr]'s, on [tl]: put in, logged, and the skip times told. False: the job ended. */
        fun handle(current: Job, tr: OffsetTracker, tl: SpeechTimeline, d: OffsetTracker.Decision, cpuMs: Long): Boolean {
            val onTrack = mode == Mode.TRACK
            if (d.outcome != Outcome.WAIT) {
                cost.matched(cpuMs)
                Log.i(TAG, if (onTrack) "film's subtitles: $d" else d.toString())
            }
            when (d.outcome) {
                Outcome.LEARNED, Outcome.MOVED, Outcome.TUNED -> {
                    val b = basisNow()
                    keptTold = b
                    main.post { decided(current, d, b) }
                }
                Outcome.KEPT -> {
                    val b = basisNow()
                    if (keptTold != b) {
                        keptTold = b
                        main.post { kept(current, b) }
                    }
                }
                Outcome.GAVE_UP -> return false
                else -> {}
            }
            // What's confirmed grew or the offsets changed: the main thread gets a fresh snapshot for the skip times,
            // after decided() (a first delay learnt has made it SYNCED by then).
            val segs = tr.segments
            val changes = tr.changeFrom(tl)
            if (tr.confirmedCount != publishedCount || segs != publishedSegments || changes != publishedChanges) {
                publishedCount = tr.confirmedCount
                publishedSegments = segs
                publishedChanges = changes
                val confirmed = tr.confirmedSeconds()
                main.post { publishMapping(current, segs, confirmed, changes) }
            }
            return true
        }

        /** Listening from here on, carrying on from what's known ([why] for the log and the panel). */
        fun toSpeech(current: Job, why: String, known: List<OffsetTracker.Segment>, centerMs: Long = current.centerMs) {
            Log.i(TAG, "listening instead of lining up with the film's subtitles ($why)")
            reference = null
            trackKey = null
            crossing = false
            mode = Mode.SPEECH
            tracker = OffsetTracker(current.lines, known, centerMs, if (known.isEmpty()) OffsetTracker.RANGE_MS else current.rangeMs,
                knownJumps = current.jumps)
            cursor = -1.0
            played = 0
            stepAt = 0
            lastKind = null
            keptTold = null
            cost.against = "speech"
            tap.listening = true
            main.post { usingSpeech(current, why) }
        }

        /** Lining up against track [key] of the film's own from here on, carrying on from what's known. */
        fun toTrack(current: Job, key: String, why: String) {
            val source = embedded?.source(current.filmUri) ?: return
            val known = tracker?.segments?.takeIf { it.isNotEmpty() } ?: current.segments
            Log.i(TAG, "lining up with the film's own subtitles ($why): $key; tracks: ${source.describe()}; not listening meanwhile")
            tracker = OffsetTracker(current.lines, known, current.centerMs, current.rangeMs, knownJumps = current.jumps, clean = true,
                priorMs = current.priorMs.takeIf { known.isEmpty() })
            reference = ReferenceSync()
            trackKey = key
            mode = Mode.TRACK
            crossing = false
            crossNext = SystemClock.elapsedRealtime() + CROSS_FIRST_MS
            keptTold = null
            letDecoderGo()
            tap.listening = false
            cost.against = "film's subtitles ($key)"
            main.post { usingTrack(current, key) }
        }

        /** The film's own tracks, best first (logged and told), or empty while none is worth it. */
        fun rank(current: Job, source: EmbeddedCues.Source): List<ReferenceSync.Ranked> {
            val candidates = source.candidates().filter { it.info.key != dropped && it.info.key != current.dropped }
            if (candidates.isEmpty()) return emptyList()
            val covered = source.covered()
            val until = source.loading().second
            val known = tracker?.segments?.takeIf { it.isNotEmpty() }
            val center = known?.let { OffsetTracker.delayAt(it, until) } ?: current.centerMs
            val range = if (known != null) RANK_NEAR_MS else current.rangeMs
            val ranked = ReferenceSync.rank(candidates, covered, until, current.lines, center, range, current.preferred)
            Log.i(TAG, "the film's own subtitles, best first: ${ranked.joinToString("; ")}")
            main.post { ranked(current, ranked) }
            return ranked
        }

        /** The track to hand over to when listening fails, if the film has one worth it. */
        fun fallbackTrack(current: Job): String? {
            if (current.speechOnly || trackFailed || embedded == null || current.filmUri == null) return null
            val source = embedded.source(current.filmUri) ?: return null
            val snap = source.snapshot(current.trackKey) ?: return null
            if (snap.cueCount < REFERENCE_MIN_CUES || snap.track.key == dropped || snap.track.key == current.dropped) return null
            return snap.track.key
        }

        /** Listening for [current] can't go on: hands over to the film's own subtitles if it can, else stops. */
        fun speechGaveUp(current: Job, why: String, text: String): Boolean {
            speechFailed = true
            val key = fallbackTrack(current)
            if (key != null) {
                toTrack(current, key, why)
                return true
            }
            if (why == "gave up") {
                jobRef.compareAndSet(current, null)
                main.post { notFound(current) }
            } else {
                stopped(current, text)
            }
            return false
        }

        fun mark(talking: Boolean) {
            if (cursor >= 0) {
                timeline.mark(cursor.toInt(), talking)
                played++
            }
            cursor += 1.0
        }

        fun feed(pcm: ByteBuffer, filmUs: Long, channels: Int, rate: Int, float: Boolean) {
            if (channels <= 0 || rate <= 0) return
            val s = sampler?.takeIf { it.channels == channels && it.inRate == rate }
                ?: MonoDownsampler(rate, channels).also { sampler = it; detector.reset() }
            val frame = filmUs / 10_000.0
            // More than 50 ms away from where the last piece ended: a jump (or a gap), start counting from here.
            if (cursor < 0 || abs(frame - cursor) > 5) {
                // A real jump (or the start) onto film not heard yet: the timing there gets checked.
                if (cursor < 0 || abs(frame - cursor) > JUMP_FRAMES) {
                    if (mode != Mode.TRACK) tracker?.seeked((frame * 10).toLong(), timeline)
                    // Checking against the talking: from here on.
                    if (crossing) crossFrom = (frame * 10).toLong()
                }
                cursor = frame
                detector.reset()
            }
            if (crossing && crossFrom < 0) crossFrom = (frame * 10).toLong()
            val buf = pcm.duplicate().order(ByteOrder.nativeOrder())
            val start = buf.position()
            if (float) {
                val fb = buf.asFloatBuffer()
                val frames = fb.remaining() / channels
                s.push(frames, { f, c -> fb.get(f * channels + c) }) { out, n -> detector.push(out, n) { mark(it) } }
            } else {
                val sb = buf.asShortBuffer()
                val frames = sb.remaining() / channels
                s.push(frames, { f, c -> sb.get(f * channels + c) / 32768f }) { out, n -> detector.push(out, n) { mark(it) } }
            }
            buf.position(start)
        }

        /**
         * One sound piece (waiting at most [waitMs] for it): marked as talking or not, decoded first when it goes out
         * undecoded. False when the job can't go on listening (nothing to hear it with).
         */
        fun hear(current: Job, waitMs: Long): Boolean {
            val piece = tap.take(waitMs) ?: return true
            try {
                val kind = piece.kind ?: return true
                // The sound changing format in the middle of the film (ads are often encoded differently): a hint.
                val was = lastKind
                if (mode != Mode.TRACK && was != null && was !== kind && !sameSound(was, kind) && cursor >= 0 &&
                    abs(piece.filmUs / 10_000.0 - cursor) <= JUMP_FRAMES
                ) {
                    tracker?.hint(piece.filmUs / 1000, OffsetTracker.Hint.FORMAT)
                    Log.i(TAG, "sound format changed at ${OffsetTracker.clock(piece.filmUs / 1000)}: a hint to check the timing")
                }
                lastKind = kind
                if (!kind.encoded) {
                    feed(ByteBuffer.wrap(piece.bytes, 0, piece.size), piece.filmUs, kind.channels, kind.sampleRate, kind.pcmEncoding == C.ENCODING_PCM_FLOAT)
                    return true
                }
                if (decoderKind !== kind) {
                    letDecoderGo()
                    decoderKind = kind
                    if (SideAudioDecoder.canDecode(kind.format.sampleMimeType)) {
                        val before = threadIds()
                        decoder = runCatching { SideAudioDecoder(kind.format) }.onFailure { Log.w(TAG, "No side decoder", it) }.getOrNull()
                        // Its decode thread, for what it costs.
                        cost.decoderThreads = threadIds() - before
                    }
                }
                val d = decoder ?: return false
                var tries = 0
                while (!d.queue(piece.bytes, piece.size, piece.filmUs) && tries++ < 50) {
                    d.drain { pcm, ch, rate, us -> feed(pcm, us, ch, rate, false) }
                    Thread.sleep(2)
                }
                d.drain { pcm, ch, rate, us -> feed(pcm, us, ch, rate, false) }
                return true
            } catch (e: InterruptedException) {
                throw e
            } catch (t: Throwable) {
                // A decoder that fails stays failed for this sound; the film plays on regardless.
                Log.w(TAG, "Listening failed", t)
                letDecoderGo()
                decoderKind = piece.kind
                return false
            } finally {
                tap.recycle(piece)
            }
        }

        /** Starts listening for a while to check the track against the talking ([CrossCheck]). */
        fun startCross(urgent: Boolean) {
            crossing = true
            crossUrgent = urgent
            crossFrom = -1
            crossSince = SystemClock.elapsedRealtime()
            cursor = -1.0
            detector.reset()
            lastKind = null
            tap.listening = true
        }

        fun endCross(nextInMs: Long) {
            crossing = false
            tap.listening = false
            letDecoderGo()
            crossNext = SystemClock.elapsedRealtime() + nextInMs
        }

        /** The talking heard in this check judged; a track the talking disagrees with is dropped. */
        fun judgeCross(current: Job, tr: OffsetTracker) {
            val to = (cursor * 10).toLong()
            val res = CrossCheck.judge(timeline, current.lines, tr.segments, crossFrom, to)
            Log.i(TAG, "checked against the speech: $res")
            val urgent = crossUrgent
            val verdict = res.verdict
            // Unsure after Resync: soon again; else as usual.
            endCross(if (verdict == CrossCheck.Verdict.UNSURE && urgent) CROSS_RETRY_MS else CROSS_EVERY_MS)
            val twice = verdict == CrossCheck.Verdict.DISAGREES && (urgent || lastVerdict == CrossCheck.Verdict.DISAGREES)
            if (verdict != CrossCheck.Verdict.UNSURE) lastVerdict = verdict
            if (twice) {
                val key = trackKey ?: return
                dropped = key
                main.post { trackDropped(current, key) }
                // The talking is sure of another delay: what the track found goes, and it's found again by listening,
                // starting from what the talking said.
                toSpeech(current, "it disagrees with the speech", emptyList(), res.delayMs)
                main.post { if (current.generation == generation) setState(state, "The film's own subtitles didn't match the speech, so it goes by the speech now.") }
            } else if (urgent && verdict != CrossCheck.Verdict.UNSURE) {
                main.post { checkDone(current) }
            }
        }

        // However the loop ends, the side decoder (and its thread) is let go.
        try {
            while (!Thread.currentThread().isInterrupted) {
                val current = job
                if (current == null) {
                    // Nothing to do: the decoder is let go, and the thread waits.
                    letDecoderGo()
                    try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                    continue
                }
                if (current.generation != gen) {
                    gen = current.generation
                    if (current.filmKey != filmKey) {
                        timeline.clear()
                        filmKey = current.filmKey
                        tracker = null
                    }
                    publishedCount = -1
                    publishedSegments = null
                    publishedChanges = null
                    cursor = -1.0
                    played = 0
                    lastKind = null
                    decoder?.flush()
                    reference = null
                    trackKey = null
                    trackFailed = false
                    speechFailed = false
                    dropped = current.dropped
                    keptTold = null
                    crossing = false
                    lastVerdict = null
                    lookAt = 0L
                    lookingSince = SystemClock.elapsedRealtime()
                    rankAt = 0L
                    ranks = 0
                    cost.against = "speech"
                    val relearn = current.relearnAtMs
                    mode = if (current.filmUri == null || embedded == null || current.speechOnly || current.speechFirst) Mode.SPEECH else Mode.LOOKING
                    // Resync with nothing found yet: by the talking (the film's own subtitles start afresh anyway).
                    val t = tracker?.takeIf { relearn != null && it.lines === current.lines && !it.clean }
                        ?: OffsetTracker(current.lines, current.segments, current.centerMs, current.rangeMs, knownJumps = current.jumps,
                            priorMs = current.priorMs)
                    relearn?.let { t.relearn(it, current.centerMs, timeline) }
                    tracker = t
                    // Resync tries at once, with what was heard already.
                    stepAt = if (relearn != null) -TRY_EVERY_FRAMES else 0
                    tap.listening = true
                }
                val wait = backOffUntil - SystemClock.elapsedRealtime()
                if (wait > 0) {
                    try { Thread.sleep(minOf(wait, 1_000)) } catch (_: InterruptedException) { break }
                    continue
                }

                // Resync pressed: the stretch around where the film is checked again, the rest kept. While still looking
                // for the film's own subtitles, it waits to know whether there are any.
                resyncRef.get()?.let { if (it.generation != current.generation) resyncRef.compareAndSet(it, null) }
                resyncRef.get()?.takeIf { (mode != Mode.LOOKING || it.speech) && resyncRef.compareAndSet(it, null) }?.let { rq ->
                    val tr = tracker
                    val ref = reference
                    if (mode == Mode.TRACK && tr != null && ref != null && !rq.speech) {
                        Log.i(TAG, "Resync at ${OffsetTracker.clock(rq.atMs)}: checking again against the film's subtitles, and the speech")
                        val d = tr.recheck(rq.atMs, ref.timeline, reject = false, nowMs = ref.steppedToMs)
                        if (d != null) {
                            if (d.outcome == Outcome.KEPT) keptTold = null
                            handle(current, tr, ref.timeline, d, 0)
                        }
                        startCross(urgent = true)
                    } else {
                        if (mode == Mode.TRACK) {
                            toSpeech(current, "Resync pressed again", tr?.segments ?: current.segments)
                        }
                        Log.i(TAG, "Resync at ${OffsetTracker.clock(rq.atMs)}: checking again against the speech")
                        tracker?.recheck(rq.atMs, timeline, reject = true)
                        stepAt = played - TRY_EVERY_FRAMES
                    }
                }

                // Looking for the film's own subtitles, listening meanwhile: a track worth it is taken up at once.
                if (mode == Mode.LOOKING) {
                    val now = SystemClock.elapsedRealtime()
                    if (now >= lookAt) {
                        lookAt = now + REFERENCE_CHECK_MS
                        val source = embedded?.source(current.filmUri)
                        val best = source?.candidates()?.filter { it.info.key != dropped }?.maxOfOrNull { it.count } ?: 0
                        if (source != null && best >= REFERENCE_MIN_CUES) {
                            val ranked = rank(current, source)
                            rankAt = now + RANK_EVERY_MS
                            ranks = 1
                            val chosen = current.trackKey?.takeIf { k -> ranked.any { it.info.key == k } } ?: ranked.firstOrNull()?.info?.key
                            if (chosen != null) toTrack(current, chosen, if (chosen == current.trackKey) "as chosen" else "the best of them")
                        } else if (if (source == null) now - lookingSince > REFERENCE_WAIT_MS else source.tracksKnown && !source.hasCandidates()) {
                            // Nothing of the film read here (a stream in pieces, say), or no track but forced ones.
                            Log.i(TAG, "no subtitles of the film's own to line up against${source?.let { ": " + it.describe() } ?: ""}")
                            mode = Mode.SPEECH
                            main.post { noTrack(current) }
                        }
                    }
                }

                // On the film's own subtitles: followed as the film loads; now and then checked against the talking.
                if (mode == Mode.TRACK) {
                    val ref = reference
                    val tr = tracker
                    val source = embedded?.source(current.filmUri)
                    if (ref == null || tr == null) {
                        mode = Mode.SPEECH
                    } else if (source == null) {
                        trackFailed = true
                        toSpeech(current, "the film's subtitles are gone", tr.segments)
                    } else {
                        cost.maybeLog(ref.timeline, current.lines, tr)
                        val now = SystemClock.elapsedRealtime()
                        if (now >= rankAt && ranks < RANKS_MAX) {
                            rankAt = now + RANK_EVERY_MS
                            ranks++
                            rank(current, source)
                        }
                        // Only once there's more loaded to go over (a few seconds' worth), or loading jumped.
                        if (ref.due(source)) {
                            val snap = source.snapshot(trackKey)
                            if (snap == null) {
                                // The track turned out to have only some lines (forced, signs): not worth lining up against.
                                trackFailed = true
                                toSpeech(current, "too few lines: ${source.describe()}", tr.segments)
                            } else {
                                val cpu0 = SystemClock.currentThreadTimeMillis()
                                val decisions = try {
                                    ref.advance(snap, tr)
                                } catch (t: Exception) {
                                    Log.e(TAG, "Lining up with the film's subtitles failed", t)
                                    trackFailed = true
                                    toSpeech(current, "failed", tr.segments)
                                    emptyList()
                                }
                                val cpuMs = (SystemClock.currentThreadTimeMillis() - cpu0) / maxOf(1, decisions.count { it.outcome != Outcome.WAIT })
                                for (d in decisions) {
                                    if (job !== current || mode != Mode.TRACK) break
                                    if (d.outcome == Outcome.GAVE_UP) {
                                        Log.i(TAG, "film's subtitles: $d")
                                        trackFailed = true
                                        // Listening already gave up: nothing else to try.
                                        if (speechFailed) {
                                            jobRef.compareAndSet(current, null)
                                            main.post { notFound(current) }
                                        } else {
                                            toSpeech(current, "no delay fits", tr.segments)
                                        }
                                        break
                                    }
                                    handle(current, tr, ref.timeline, d, cpuMs)
                                }
                            }
                        }
                    }
                    if (mode == Mode.TRACK) {
                        val tr2 = tracker
                        // Once something's found, the talking is asked now and then whether it agrees.
                        if (!crossing && tr2 != null && !tr2.learning && SystemClock.elapsedRealtime() >= crossNext) startCross(urgent = false)
                        if (crossing && tr2 != null) {
                            val heard = crossFrom >= 0 && cursor >= 0
                            val to = (cursor * 10).toLong()
                            val talk = if (heard) timeline.speech.get((crossFrom / 10).toInt(), cursor.toInt()).cardinality() * 10L else 0L
                            val long = SystemClock.elapsedRealtime() - crossSince > CROSS_MAX_WALL_MS
                            if (heard && (talk >= CROSS_TALK_MS || to - crossFrom >= CROSS_MAX_FILM_MS) || long) {
                                if (heard) judgeCross(current, tr2) else endCross(CROSS_EVERY_MS)
                            } else {
                                val ok = try { hear(current, 300) } catch (_: InterruptedException) { break }
                                // Nothing to hear it with: the track goes unchecked.
                                if (!ok) endCross(Long.MAX_VALUE / 4)
                            }
                            continue
                        }
                        try { Thread.sleep(REFERENCE_EVERY_MS) } catch (_: InterruptedException) { break }
                        continue
                    }
                }

                // Listening with the film's own subtitles there (chosen so, or after they failed): ranked now and then all
                // the same, for the "Sync with" choice.
                if (mode == Mode.SPEECH && ranks < RANKS_MAX && current.filmUri != null) {
                    val now = SystemClock.elapsedRealtime()
                    if (now >= rankAt) {
                        rankAt = now + if (ranks == 0) REFERENCE_CHECK_MS * 5 else RANK_EVERY_MS
                        val source = embedded?.source(current.filmUri)
                        if (source != null && (source.candidates().maxOfOrNull { it.count } ?: 0) >= REFERENCE_MIN_CUES) {
                            ranks++
                            rank(current, source)
                        }
                    }
                }
                cost.maybeLog(timeline, current.lines, tracker)
                // Waiting here is where release() usually finds the thread: its interrupt ends listening.
                val ok = try { hear(current, 500) } catch (_: InterruptedException) { break }
                if (!ok) {
                    // The sound can't be heard (no decoder for it). While the film's own subtitles may yet do instead,
                    // that can wait; else they're handed over to, or listening stops.
                    if (mode == Mode.LOOKING) continue
                    if (!speechGaveUp(current, "can't hear the sound", CANT_HEAR)) continue
                    continue
                }

                if (job !== current || cursor < 0) continue
                val tr = tracker ?: continue
                if (played - stepAt < TRY_EVERY_FRAMES) continue
                val playedMs = (played - maxOf(stepAt, 0L)) * 10
                stepAt = played
                val cpu0 = SystemClock.currentThreadTimeMillis()
                // A mistake in the tracker must not take the film down: listening stops, what was found stays in use.
                val d = try {
                    tr.step(timeline, (cursor * 10).toLong(), playedMs)
                } catch (t: Exception) {
                    Log.e(TAG, "Tracking failed at ${OffsetTracker.clock((cursor * 10).toLong())}", t)
                    stopped(current, "Couldn't line up with the speech. Try Resync, or set the delay by hand.")
                    continue
                }
                if (!handle(current, tr, timeline, d, SystemClock.currentThreadTimeMillis() - cpu0)) {
                    // Gave up finding the first delay: the film's own subtitles, if it has any, else not synced.
                    speechGaveUp(current, "gave up", "")
                }
            }
        } finally {
            runCatching { decoder?.release() }
        }
    }

    /**
     * What listening costs, logged every few minutes, to check it on the TV: processor time of this thread, of FFmpeg's
     * decode thread when the sound goes out undecoded (it runs on its own thread), and of the whole player for scale;
     * matching; sound pieces dropped; memory.
     */
    private inner class Cost {
        private var since = SystemClock.elapsedRealtime()
        private var cpuSince = SystemClock.currentThreadTimeMillis()
        private var processSince = Process.getElapsedCpuTime()
        // The side decoder's threads (ids in this process), and their processor time at the last line.
        var decoderThreads: Set<Int> = emptySet()
        private val decoderSince = HashMap<Int, Long>()
        private var droppedSince = tap.dropped
        private var backOffsSince = backOffs
        private var matches = 0
        private var matchMs = 0L
        private var worstMs = 0L
        /** What's lined up against: the talking, or the film's own subtitles. */
        var against = "speech"

        fun matched(ms: Long) {
            matches++
            matchMs += ms
            worstMs = maxOf(worstMs, ms)
        }

        fun maybeLog(timeline: SpeechTimeline, lines: SyncMatcher.Lines, tracker: OffsetTracker?) {
            val wall = SystemClock.elapsedRealtime() - since
            if (wall < COST_EVERY_MS) return
            val cpu = SystemClock.currentThreadTimeMillis() - cpuSince
            val process = Process.getElapsedCpuTime() - processSince
            // Threads gone since (the decoder let go) count nothing more, nor any other thread that started meanwhile.
            var decoderMs = 0L
            for (tid in decoderThreads) {
                if (threadName(tid)?.startsWith(DECODE_THREAD) != true) continue
                val now = threadCpuMs(tid) ?: continue
                decoderMs += now - (decoderSince[tid] ?: 0L)
                decoderSince[tid] = now
            }
            decoderSince.keys.retainAll(decoderThreads)
            val kb = (timeline.speech.size() + timeline.heard.size()) / 8 / 1024 + lines.frames.size() / 8 / 1024 + lines.ys.size * 8 / 1024
            fun share(ms: Long) = "%.2f%%".format(java.util.Locale.ROOT, ms * 100.0 / wall)
            Log.i(
                TAG,
                "cost over ${wall / 1000} s, processor (% of one core): listening thread $cpu ms ${share(cpu)}, " +
                    "side decoder $decoderMs ms ${share(decoderMs)}, whole player $process ms ${share(process)}; " +
                    "matching $matches × ${if (matches > 0) matchMs / matches else 0} ms (worst $worstMs ms), " +
                    "sound pieces dropped ${tap.dropped - droppedSince}, frame-drop back-offs ${backOffs - backOffsSince}, " +
                    "memory ~$kb KB, segments ${tracker?.segments?.size ?: 0}, lining up against $against",
            )
            since = SystemClock.elapsedRealtime()
            cpuSince = SystemClock.currentThreadTimeMillis()
            processSince = Process.getElapsedCpuTime()
            droppedSince = tap.dropped
            backOffsSince = backOffs
            matches = 0
            matchMs = 0
            worstMs = 0
        }
    }

    /** Listening for [current] stops (from the worker thread). Still learning: [text] says why. Already following: what was found stays in use. */
    private fun stopped(current: Job, text: String) {
        jobRef.compareAndSet(current, null)
        main.post {
            if (current.generation != generation) return@post
            tap.listening = false
            stopHolding()
            if (segments.isEmpty()) setState(State.NOT_SYNCED, text)
        }
    }

    /** On the main thread: lining up against the film's own track [trackKey] now. */
    private fun usingTrack(current: Job, trackKey: String) {
        if (current.generation != generation) return
        trackInUse = trackKey
        // The same track next time, unless one is chosen.
        if (syncWith() == null) prefs.saveSyncTrack(savedKey(), trackKey)
        if (state == State.LISTENING && basis != Basis.PRIOR) setState(State.LISTENING, "Lining up with the film's own subtitles…")
        else onChanged()
    }

    /** On the main thread: listening now (the film's own subtitles weren't worth it, or failed, or disagreed). */
    private fun usingSpeech(current: Job, why: String) {
        if (current.generation != generation) return
        trackInUse = null
        // Listening takes a minute or two: lines aren't held that long.
        stopHolding()
        if (state == State.LISTENING && basis != Basis.PRIOR) setState(State.LISTENING, LISTENING_TEXT)
        else onChanged()
        Log.i(TAG, "going by the speech: $why")
    }

    /** On the main thread: the film has no subtitles of its own worth lining up against. */
    private fun noTrack(current: Job) {
        if (current.generation != generation) return
        stopHolding()
        tracks = emptyList()
        trackInUse = null
        if (state == State.LISTENING && basis != Basis.PRIOR) setState(State.LISTENING, LISTENING_TEXT)
    }

    /** On the main thread: the film's own tracks, ranked again. */
    private fun ranked(current: Job, list: List<ReferenceSync.Ranked>) {
        if (current.generation != generation) return
        tracks = list
        onChanged()
    }

    /** On the main thread: the film's own track [trackKey] disagreed with the talking; not used again in this film. */
    private fun trackDropped(current: Job, trackKey: String) {
        if (current.generation != generation) return
        droppedTracks[savedKey()] = trackKey
        speechFirstKey = savedKey()
        checkingAt = -1
    }

    /** On the main thread: a match found the delay in use right ([b]: against what). */
    private fun kept(current: Job, b: Basis) {
        if (current.generation != generation || byHand || state != State.SYNCED) return
        basis = b
        stopHolding()
        val again = checkingAt >= 0
        checkingAt = -1
        detail = if (again) "Checked again. ${syncedText()}" else syncedText()
        onChanged()
    }

    /** On the main thread: Resync's check against the talking agreed with the film's own subtitles. */
    private fun checkDone(current: Job) {
        if (current.generation != generation || byHand || state != State.SYNCED) return
        checkingAt = -1
        detail = "Checked again; the speech agrees. ${syncedText()}"
        onChanged()
    }

    /**
     * On the main thread: a delay found (the first, one after a break, or a fine-tuning), put in and kept; [b]: what it
     * was found against.
     */
    private fun decided(current: Job, d: OffsetTracker.Decision, b: Basis) {
        if (current.generation != generation || byHand || d.segments.isEmpty()) return
        basis = b
        checkingAt = -1
        segments = d.segments
        prefs.saveSyncedSegments(savedKey(), OffsetTracker.encode(segments))
        // Also the one delay older versions read.
        prefs.saveSyncedDelay(savedKey(), segments[0].delayMs)
        val show = showKey()
        if (d.outcome == Outcome.MOVED && show != null) {
            prefs.saveShowJumps(show, OffsetTracker.rememberJumps(prefs.showJumps(show), segments))
        }
        // How this episode starts: the next one (subtitles from the same source) starts from it.
        if (show != null && d.outcome != Outcome.MOVED) subtitleSource?.let { prefs.saveShowStart(show, it, segments[0].delayMs) }
        val nowUs = delayUs()
        val wantUs = OffsetTracker.delayAt(segments, position().first)?.times(1000)
        if (wantUs != null && abs(wantUs - nowUs) >= MIN_MOVE_US) {
            if (undoUs == null) undoUs = nowUs
            moveDelay(wantUs)
        }
        setState(State.SYNCED, syncedText())
        startFollowing()
        stopHolding()
    }

    private fun publishMapping(current: Job, segs: List<OffsetTracker.Segment>, confirmed: java.util.BitSet, changes: List<Long>) {
        if (current.generation != generation || byHand || state != State.SYNCED || segs.isEmpty()) return
        mapping = SyncMapping(segs, confirmed, ++mappingVersion, current.filmKey, changes)
        onMappingChanged?.invoke()
    }

    /** New subtitles or film, Resync, Undo, a delay by hand, auto sync off: nothing confirmed stands any more. */
    private fun clearMapping() {
        if (mapping == null) return
        mapping = null
        mappingVersion++
        onMappingChanged?.invoke()
    }

    private fun notFound(current: Job) {
        if (current.generation != generation) return
        tap.listening = false
        stopHolding()
        // Started from the last episode's delay: that stays, unconfirmed.
        if (basis == Basis.PRIOR) {
            setState(State.NOT_SYNCED, "Couldn't check the last episode's delay here (${SyncMatcher.formatShift(delayUs() / 1000)}). Try Resync, or set it by hand.")
            return
        }
        setState(State.NOT_SYNCED, "Couldn't line up with the speech. Try Resync, or set the delay by hand.")
    }

    private fun moveDelay(us: Long) {
        setDelayUs((us / 10_000) * 10_000)
    }

    private fun setState(s: State, text: String?) {
        state = s
        detail = text
        onChanged()
    }

    private fun savedKey() = "${file?.name}|$key"

    companion object {
        private const val TAG = "AutoSync"
        // The tracker is stepped this often: every 5 s of film played (it matches every 15 s at most, and in between
        // only looks at the few delays worth trying first).
        private const val TRY_EVERY_FRAMES = OffsetTracker.STEP_MS / 10
        // Further than this (10 s) from where the last sound ended is a jump, not a gap from pieces dropped.
        private const val JUMP_FRAMES = 1_000
        // While lining up with the film's own subtitles: how often what has loaded is looked at, how often a film
        // without a track worth it is looked at again for one, and how many lines one needs before it's taken up.
        private const val REFERENCE_EVERY_MS = 500L
        private const val REFERENCE_CHECK_MS = 1_000L
        private const val REFERENCE_MIN_CUES = 8
        // The film's own tracks are ranked again this often (for the "Sync with" choice), at most this many times, and
        // once the delay is known, only around it.
        private const val RANK_EVERY_MS = 2 * 60_000L
        private const val RANKS_MAX = 5
        private const val RANK_NEAR_MS = 20_000L
        // Checking the film's own subtitles against the talking: the first a while after lining up, then this often;
        // each until this much talking is heard (or this much film, or this long), and after Resync if unsure, again soon.
        private const val CROSS_FIRST_MS = 60_000L
        private const val CROSS_EVERY_MS = 15 * 60_000L
        private const val CROSS_RETRY_MS = 2 * 60_000L
        private const val CROSS_TALK_MS = 40_000L
        private const val CROSS_MAX_FILM_MS = 4 * 60_000L
        private const val CROSS_MAX_WALL_MS = 6 * 60_000L
        // A downloaded line is held back at most this long at the start of a new episode with nothing to go on.
        private const val HOLD_MS = 6_000L
        // Resync pressed again within this long: the talking decides.
        private const val RESYNC_AGAIN_MS = 10 * 60_000L

        /** The "Sync with" choice for only ever listening ([chooseSyncWith]). */
        const val SPEECH = "speech"

        private const val LISTENING_TEXT = "Listening for speech. Usually takes a minute or two of talking."

        /**
         * A subtitle release less the show and episode, for subtitles from the same source: what says how they're
         * timed ("720p hdtv x264 avs" for "Legion.S01E04.720p.HDTV.x264-AVS").
         */
        fun sourceOf(release: String): String {
            val name = release.replace(Regex("(?i)\\.(srt|ass|ssa|sub|vtt|txt)$"), "")
            val tech = name.lowercase().split(Regex("[^a-z0-9]+")).filter { TECH.matches(it) }
            val group = name.substringAfterLast('-', "").lowercase().takeIf { it.length in 2..14 && it.all(Char::isLetterOrDigit) }
            return (tech + listOfNotNull(group)).distinct().joinToString(" ").ifEmpty { "any" }
        }

        private val TECH = Regex("\\d{3,4}p|hdtv|pdtv|web|webrip|webdl|bluray|bdrip|brrip|dvdrip|hdrip|amzn|nf|hulu|dsnp|hmax|atvp|pcok|x26[45]|h26[45]|hevc|xvid|avc|remux")
        // Nothing of the film read for this long (it's not a file Media3 reads itself): only listening, then.
        private const val REFERENCE_WAIT_MS = 30_000L
        // How often the delay is matched to where the film is, while it changes along the film.
        private const val FOLLOW_EVERY_MS = 300L
        private const val COST_EVERY_MS = 5 * 60_000L
        // Nobody sees less than this, so the delay isn't nudged for it.
        private const val MIN_MOVE_US = 150_000L

        private const val CANT_HEAR = "Can't listen to this film's sound. Set the delay by hand."
        // Clock ticks per second in /proc times.
        private val TICKS = runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).coerceAtLeast(1L)

        /** This process's threads, by id. */
        private fun threadIds(): Set<Int> = File("/proc/self/task").list()?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()

        // The name Media3 gives FFmpeg's decode thread ("ExoPlayer:SimpleDecoder"), as the system keeps it: 15 letters.
        private const val DECODE_THREAD = "ExoPlayer:Simpl"

        private fun threadName(tid: Int): String? = runCatching { File("/proc/self/task/$tid/comm").readText().trim() }.getOrNull()

        /** Processor time (ms) thread [tid] of this process has used, or null when it's gone. */
        private fun threadCpuMs(tid: Int): Long? = runCatching {
            // After the thread's name (in brackets, may hold spaces), utime and stime are the 12th and 13th fields.
            val f = File("/proc/self/task/$tid/stat").readText().substringAfterLast(')').trim().split(' ')
            (f[11].toLong() + f[12].toLong()) * 1000 / TICKS
        }.getOrNull()

        /** The same sound as before (a new format object for it says nothing). */
        private fun sameSound(a: SpeechTap.Kind, b: SpeechTap.Kind) =
            a.encoded == b.encoded && a.channels == b.channels && a.sampleRate == b.sampleRate &&
                a.pcmEncoding == b.pcmEncoding && a.format.sampleMimeType == b.format.sampleMimeType
    }
}
