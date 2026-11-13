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
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
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
 * disagreeing with the talking, to listening for good (for these subtitles in this film, the next time too).
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
 * Listening goes by the film's sound as it loads ([SpeechAhead]) where it can: the player loads up to two minutes ahead
 * of where it plays, and those very bytes are copied as they load ([EmbeddedCues.ahead]: no second connection, nothing
 * loaded twice), decoded and heard before they play, so the first delay is usually known seconds after the film starts
 * loading, and a change after a break before the break is on screen (put in as the film gets there). It's the same
 * tracker, timeline and proof as listening where the film plays, only with the evidence earlier; where the film plays
 * is still listened to for what wasn't heard ahead (the first moments, a stretch the listener fell behind on). Not for
 * live streams, nor for films Media3 doesn't read itself (streams in pieces): those are listened to where they play.
 * How long the first delay took is kept for the panel ([syncedInMs]).
 *
 * Kept light for the Fire TV Stick: one background thread at low priority (and FFmpeg's decode threads, for the sound
 * heard ahead and when the sound goes out undecoded, at the same), 10 ms marks of what was heard and of talking (about
 * 180 KB for two hours), the sound heard ahead decoded at most a quarter of the time while finding the first delay and
 * a tenth after, at most 3 MB of it waiting (let go rather than held up), film heard before never decoded again when
 * the player loads it again (a rewind), matching at most every 15 s of film and usually every few minutes (less often
 * while nothing heard fits), and it backs off while the picture drops frames. With the player gone (the activity
 * stopped) nothing is listened to, copied or followed ([playerGone]). What it costs is logged every few minutes (tag
 * AutoSync).
 *
 * It cleans up after itself: the delay it put in goes when its subtitles do ([follow]), and what it keeps for a subtitle
 * file is let go with the file ([SavedSubtitles.prune]).
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
    /** Whether the film playing is a film (not live): only then is its sound heard ahead. On the main thread. */
    private val isVod: () -> Boolean = { false },
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
    // The delay as it was before auto sync first put one in for these subtitles, and the one it last put in (µs): when
    // the subtitles go, the first is put back if the second is still in, so nothing else is shown shifted by it
    // (the film's own subtitles, the next episode's). Null while auto sync has put none in, and once one is set by hand.
    private var baseUs: Long? = null
    private var putUs: Long? = null
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
    // or the film's own subtitles disagreed with it), and a track of the film's own that disagreed with the talking
    // (that one is kept with what's found, so the next sitting starts as this one left it: see [start]).
    private var speechFirstKey: String? = null
    private val droppedTracks = HashMap<String, String>()
    private var lastResyncAt = 0L
    // The delays found before the last Resync, for Undo to put back (null when Undo goes back to before auto sync).
    private var beforeResync: List<OffsetTracker.Segment>? = null
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
    // Off for these subtitles, but the sound loading is copied all the same, for when it's turned on ([standbyAhead]).
    @Volatile private var aheadStandby = false

    /** Where the film is playing (ms), for the listening thread: updated every second while listening; -1 unknown. */
    @Volatile private var playheadMs = -1L
    private val beat = object : Runnable {
        override fun run() {
            if (job == null) return
            val at = position().first
            // Playing jumped (a skip or a rewind): how long the delay takes is counted from here.
            if (playheadMs >= 0 && abs(at - playheadMs) > JUMP_BEAT_MS) jumpAt = SystemClock.elapsedRealtime()
            playheadMs = at
            main.postDelayed(this, BEAT_MS)
        }
    }

    // When auto sync started on these subtitles (elapsed realtime), to time how long the first delay took.
    private var startedAt = 0L
    /**
     * How long it took from auto sync starting on these subtitles to their first delay (ms), for the panel; null while
     * not found yet, or when it wasn't found this time (kept from before, or set by hand).
     */
    var syncedInMs: Long? = null
        private set
    // Finding the first delay: the one sure once, put in use while it's confirmed ([tentative]), and since when.
    private var tentativeMs: Long? = null
    private var tentativeAt = 0L
    // Resync pressed with nothing kept (found afresh): checking again until it's found ([rechecking]).
    private var resyncRelearn = false

    // After a jump with delays known: the delay a quick look found, in use from where the jump landed until the film
    // since confirms or drops it ([provisional]); never saved. When it was put in, and whether Undo was set for it.
    private var provisional: OffsetTracker.Segment? = null
    private var provisionalAt = 0L
    private var provisionalSetUndo = false
    // When playing last jumped (elapsed realtime, 0: not since these subtitles came on): the badge counts from it.
    private var jumpAt = 0L
    // The checks as the film plays: when the last one bore the delay out, or put it right (elapsed realtime, 0: none).
    private var checkedAt = 0L
    private var checkedMoved = false

    /** How far the look in hand is towards its next decision, 0..100 (0: nothing to show), for the badge. */
    var progress = 0
        private set

    /** A delay one look was sure of is in use, being confirmed: how long that took (the badge's "Matched in"), or null. */
    val matchedInMs: Long?
        get() = when {
            state == State.LISTENING && tentativeMs != null && tentativeAt > 0 && startedAt > 0 -> tentativeAt - startedAt
            // After a jump: from the jump (or, the film just opened, from when auto sync started on it).
            state == State.SYNCED && provisional != null && provisionalAt > 0 && since() > 0 -> (provisionalAt - since()).coerceAtLeast(0L)
            else -> null
        }

    /**
     * What the checks made as the film plays last said, for the panel: "In sync · 2 min ago", "Adjusted · just now";
     * null before the first one.
     */
    val checkedText: String?
        get() {
            if (checkedAt <= 0 || state != State.SYNCED || byHand) return null
            val min = (SystemClock.elapsedRealtime() - checkedAt) / 60_000
            val ago = if (min < 1) "just now" else "$min min ago"
            return "${if (checkedMoved) "Adjusted" else "In sync"} · $ago"
        }

    // What "in N s" counts from: the last jump, else auto sync starting on these subtitles.
    private fun since(): Long = maxOf(jumpAt, startedAt)

    /** Resync was pressed and it's still checking (the badge's "Rechecking"). */
    val rechecking: Boolean
        get() = (state == State.SYNCED && checkingAt >= 0) || (state == State.LISTENING && resyncRelearn)

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
        /** The film's sound is heard as it loads ([SpeechAhead]): a film Media3 reads itself, not live. */
        val listenAhead: Boolean = false,
        /** With [relearnAtMs]: Resync, so the delay in use was turned down; else auto sync was turned on ([turnOn]). */
        val rejectInUse: Boolean = true,
        /** The talking's fine-tuning of the film's own track [trimTrack] found before ([trimMs]), put in again on it. */
        val trimTrack: String? = null,
        val trimMs: Long = 0,
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
        if (subtitle == file && filmKey == key) {
            // The player was gone meanwhile (the activity stopped) and is back: listening is picked up again.
            if (away) playerBack()
            return
        }
        away = false
        awayJob = null
        // These subtitles go: so does the delay auto sync put in for them (one set by hand stays, as it always did).
        baseUs?.let { if (delayUs() == putUs) setDelayUs(it) }
        baseUs = null
        putUs = null
        resyncRelearn = false
        stopListening()
        // Other subtitles for the same film: what's kept stays (taken over below); another film's isn't wanted.
        if (filmKey == null || filmKey != key) endStandby()
        clearMapping()
        stopHolding()
        startedAt = 0
        syncedInMs = null
        tentativeMs = null
        tentativeAt = 0L
        file = subtitle
        key = filmKey
        undoUs = null
        beforeResync = null
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
            // No downloaded subtitles (yet): the sound loading is kept ready all the same, so subtitles picked later are
            // synced in seconds.
            if (filmKey != null) standbyAhead() else endStandby()
            return
        }
        // Set by hand the last time these subtitles were on: that delay again, without listening.
        val hand = prefs.handDelayMs(savedKey())
        if (hand != null) {
            byHand = true
            moveDelay(hand * 1000)
            setState(State.OFF, "Your delay from last time: ${SyncMatcher.formatShift(hand)}.")
            loadLines(subtitle) {}
            standbyAhead()
            return
        }
        if (!prefs.autoSync) {
            setState(State.OFF, null)
            // Ready for auto sync to be turned on for this film.
            standbyAhead()
            return
        }
        val saved = OffsetTracker.restore(prefs.syncedSegments(savedKey()), prefs.syncedDelayMs(savedKey()))
        if (saved != null) {
            segments = saved
            basis = Basis.SAVED
            OffsetTracker.followedDelayMs(byHand, true, segments, position().first)?.let { moveDelay(it * 1000) }
            setState(State.SYNCED, syncedText())
            startFollowing()
            // Listens on from what was found, in case the timing changes somewhere new this time. The sound loading
            // meanwhile is copied already, to be heard ahead.
            armAhead()
            loadLines(subtitle) { start() }
            return
        }
        startedAt = SystemClock.elapsedRealtime()
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
        armAhead()
        loadLines(subtitle) { start() }
    }

    /**
     * Check sync (once called Resync): the timing where the film is gets checked again. With delays found, the stretch
     * around here is matched again (see the class notes) and the rest kept: the delay in use stays if the talking bears
     * it out, else another takes over. With none (or after a delay set by hand), it's found afresh around where the film
     * is, from the delay in use. Pressed again soon after, the viewer says it's wrong there: the delay in use is turned
     * down and the talking decides. Meanwhile the delay stays as it is.
     */
    fun resync() = resync(asked = true)

    /**
     * Auto sync turned on for these subtitles (from the Subtitle delay window, or in Settings): found afresh around where
     * the film is, as after Resync, but the delay in use isn't turned down (only nobody had it checked): heard to agree,
     * it's kept at once.
     */
    fun turnOn() = resync(asked = false)

    private fun resync(asked: Boolean) {
        val f = file ?: return
        val now = SystemClock.elapsedRealtime()
        // Pressed again soon after: the source used the first time didn't put it right, so the talking decides.
        val again = asked && now - lastResyncAt < RESYNC_AGAIN_MS
        if (asked) lastResyncAt = now
        if (again) speechFirstKey = savedKey()
        val at = position().first
        if (!byHand && segments.isNotEmpty() && state == State.SYNCED && prefs.autoSync) {
            stopHolding()
            checkingAt = at
            // Undo puts back what was found before this press, and auto sync carries on from it.
            beforeResync = segments
            undoUs = delayUs()
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
        beforeResync = null
        byHand = false
        basis = Basis.NONE
        priorMs = null
        segments = emptyList()
        startedAt = now
        syncedInMs = null
        tentativeMs = null
        tentativeAt = 0L
        prefs.forgetSyncedDelay(savedKey())
        prefs.forgetHandDelay(savedKey())
        resyncRelearn = asked
        // Check sync looks afresh from the delay in use; pressed again soon after, it's turned down.
        if (lines == null) loadLines(f) { start(relearnAtMs = at, rejectInUse = again) } else start(relearnAtMs = at, rejectInUse = again)
    }

    /** The delay was changed by hand: auto sync stays out of it for this subtitle, and the delay is kept for it. */
    fun handOff(delayUs: Long) {
        if (file == null) return
        // The viewer's own delay from here on: it stays when these subtitles go.
        baseUs = null
        putUs = null
        prefs.saveHandDelay(savedKey(), delayUs / 1000)
        stopHolding()
        startedAt = 0
        syncedInMs = null
        tentativeMs = null
        tentativeAt = 0L
        if (state == State.OFF) return
        stopListening()
        clearMapping()
        byHand = true
        undoUs = null
        beforeResync = null
        segments = emptyList()
        prefs.forgetSyncedDelay(savedKey())
        setState(State.OFF, "Off for these subtitles, as the delay was set by hand.")
        standbyAhead()
    }

    /**
     * Auto sync turned on or off in Settings, or from the Subtitle delay window. Off, nothing moves the delay any more;
     * the sound loading is still kept ready ([standbyAhead]), so turned on again it's synced in seconds.
     */
    fun setOn(on: Boolean) {
        if (on) {
            if (file != null && state == State.OFF) turnOn()
        } else {
            stopListening()
            clearMapping()
            stopHolding()
            beforeResync = null
            startedAt = 0
            syncedInMs = null
            tentativeMs = null
            tentativeAt = 0L
            setState(State.OFF, null)
            // Ready for it to be turned on again (only copied meanwhile).
            standbyAhead()
        }
    }

    /**
     * Puts the delay back to what it was before auto sync moved it. Right after Resync, it's what Resync might have
     * changed that goes back: the delays found before the press, everywhere in the film, with auto sync still on.
     */
    fun undo() {
        val before = undoUs ?: return
        val kept = beforeResync
        beforeResync = null
        if (kept != null && kept.isNotEmpty() && !byHand && prefs.autoSync) {
            stopListening()
            clearMapping()
            stopHolding()
            undoUs = null
            checkingAt = -1
            // A Resync after this is a first press again, checked against the film's own subtitles if it has them.
            lastResyncAt = 0L
            if (speechFirstKey == savedKey()) speechFirstKey = null
            segments = kept
            prefs.saveSyncedSegments(savedKey(), OffsetTracker.encode(kept))
            moveDelay(OffsetTracker.delayAt(kept, position().first)?.times(1000) ?: before)
            setState(State.SYNCED, "Put back as it was before Check sync: ${SyncMatcher.formatShift(delayUs() / 1000)} here.")
            startFollowing()
            start()
            return
        }
        stopListening()
        clearMapping()
        stopHolding()
        setDelayUs(before)
        // Back to the delay from before: nothing of auto sync's is in any more.
        baseUs = null
        putUs = null
        undoUs = null
        byHand = true
        startedAt = 0
        syncedInMs = null
        tentativeMs = null
        tentativeAt = 0L
        segments = emptyList()
        prefs.forgetSyncedDelay(savedKey())
        setState(State.OFF, "Off for these subtitles; the delay is back to ${SyncMatcher.formatShift(before / 1000)}.")
        standbyAhead()
    }

    /**
     * What to line up against, as chosen in the Subtitle delay window: null for the best of the film's own subtitle
     * tracks (or the talking without any), [SPEECH], or one track's key ([tracks]). Kept for the show, so its other
     * episodes use it too. What was found is kept; it carries on from there with the new choice.
     */
    fun chooseSyncWith(choice: String?) {
        prefs.saveSyncWith(choiceScope() ?: return, choice)
        if (choice != SPEECH && speechFirstKey == savedKey()) speechFirstKey = null
        if (choice != null && choice != SPEECH) {
            // A track picked by the viewer: one the talking turned down is tried again.
            droppedTracks.remove(savedKey())
            prefs.forgetDroppedSyncTrack(savedKey())
        }
        if (file == null || byHand || !prefs.autoSync || state == State.OFF) return
        // A delay only quickly found after a jump goes with the listening that found it, and Undo set only for it.
        if (provisional != null && provisionalSetUndo) undoUs = null
        stopListening()
        checkingAt = -1
        start()
        // Stopping also stopped the delay being kept to the one for where the film is: the one found for here is put
        // in again, and kept to as the film goes on (delays by position).
        if (state == State.SYNCED) applyHere()
        startFollowing()
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

    // The player is gone with the activity stopped ([playerGone]), since when: nothing listens meanwhile. What was
    // listening then, and whether the sound was only being copied ([standbyAhead]), for [playerBack] to pick up again.
    private var away = false
    private var awayAt = 0L
    private var awayJob: Job? = null
    private var awayStandby = false

    /**
     * The player was let go with the activity stopped (Home on a paused film, the screensaver, a screen of its own on
     * top): nothing is listened to, copied or followed meanwhile, the sound kept ready is let go, and the listening
     * thread only idles (its decoders freed). What was found, Undo and the state stay as they are. Once the player is
     * back, [follow] for the same subtitles and film picks it up again ([playerBack]); for any others it starts as usual.
     */
    fun playerGone() {
        // Gone before, never picked up since (the film's tracks didn't come back), and nothing started since: as it was.
        if (away && job == null && !aheadStandby) return
        away = true
        awayAt = SystemClock.elapsedRealtime()
        awayJob = job
        awayStandby = aheadStandby
        // A delay only quickly found after a jump goes with the listening that found it, and Undo set only for it (the
        // delay found for where the film is goes in again in [playerBack]: there's no film position to go by now).
        if (provisional != null && provisionalSetUndo) undoUs = null
        stopListening()
        aheadStandby = false
        embedded?.let {
            it.ahead.keepNewest = false
            it.aheadUri = null
            // The listening thread lets go of what was copied once it idles; with none, it's done here.
            if (worker?.isAlive != true) it.ahead.release()
        }
    }

    /** The player is back for the same subtitles and film: carries on from what was found, as before it went. */
    private fun playerBack() {
        val was = awayJob
        val now = SystemClock.elapsedRealtime()
        val gone = now - awayAt
        away = false
        awayJob = null
        // Something started listening since (Check sync pressed before the film's tracks were back): nothing to pick up.
        if (job != null) return
        val f = file
        if (f == null || byHand || !prefs.autoSync || (state != State.LISTENING && state != State.SYNCED)) {
            // Only the sound was copied, for auto sync to be turned on: again.
            if (awayStandby) standbyAhead()
            return
        }
        // A check asked for before the player went was dropped with the listening: the badge doesn't wait for it.
        if (checkingAt >= 0) {
            checkingAt = -1
            if (state == State.SYNCED) detail = syncedText()
        }
        if (state == State.SYNCED) {
            applyHere()
            startFollowing()
        }
        // Listening had stopped before the player went (it couldn't go on): what was found stays in use, as then.
        if (was == null && lines != null) {
            onChanged()
            return
        }
        // The time away doesn't count towards how long syncing took (the badge's "Matched in", "Synced in").
        if (startedAt > 0) startedAt = minOf(startedAt + gone, now)
        if (tentativeAt > 0) tentativeAt = minOf(tentativeAt + gone, now)
        if (jumpAt > 0) jumpAt = minOf(jumpAt + gone, now)
        // Finding the delay afresh (Check sync, or turned on) with none found yet: afresh again, around the delay in use.
        // One put in use since isn't the one the viewer turned down.
        val relearn = was?.relearnAtMs != null && segments.isEmpty()
        val go = {
            if (relearn) start(relearnAtMs = position().first, rejectInUse = was?.rejectInUse == true && tentativeMs == null)
            else start()
        }
        // The lines were still being read when the player went (that was dropped): read again first.
        if (lines == null) loadLines(f) { go() } else go()
        onChanged()
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
        aheadStandby = false
        embedded?.ahead?.keepNewest = false
        embedded?.ahead?.release()
        main.removeCallbacksAndMessages(null)
        worker?.interrupt()
        worker = null
    }

    // ---- Following the delay along the film ------------------------------------

    /** While the delay changes along the film, it's kept to the one for where the film is (after a jump, too). */
    private val following = object : Runnable {
        override fun run() {
            if (state != State.SYNCED || byHand || (segments.size < 2 && provisional == null)) return
            applyHere()
            main.postDelayed(this, FOLLOW_EVERY_MS)
        }
    }

    private fun startFollowing() {
        main.removeCallbacks(following)
        if (segments.size > 1 || provisional != null) main.post(following)
    }

    /** Puts in the delay found for where the film is now, unless it's in already (to within what anyone sees). */
    private fun applyHere() {
        val ms = OffsetTracker.followedDelayMs(byHand, state == State.SYNCED, segments, position().first, provisional) ?: return
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
        val took = syncedInMs?.takeIf { basis == Basis.TRACK || basis == Basis.SPEECH }?.let { " in ${tookText(it)}" } ?: ""
        return "$what$took: ${SyncMatcher.formatShift(ms)}$where."
    }

    /** What the panel says while Resync's check is going on. */
    private fun checkingText(): String =
        // Pressed again soon after, it's the talking alone that decides (see [resync]).
        if (trackInUse != null && speechFirstKey != savedKey()) "Checking the timing here again against the film's own subtitles and the speech…"
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
                if (read.size < 20) {
                    endStandby()
                    if (job == null) embedded?.aheadUri = null
                    setState(State.NOT_SYNCED, "These subtitles have too few lines to line up.")
                } else {
                    then()
                }
            }
        }.apply { isDaemon = true; name = "FirePlayerSyncRead" }.start()
    }

    /** Listens from what's known ([segments]), or for Resync afresh around [relearnAtMs]. */
    private fun start(relearnAtMs: Long? = null, rejectInUse: Boolean = true) {
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
        val ahead = armAhead()
        val trim = prefs.syncTrim(savedKey())
        // A track of the film's own the talking turned down for these subtitles in a sitting before: taken up as that
        // sitting left it ([trackDropped]), so it's never lined up against, and the talking goes first.
        if (droppedTracks[savedKey()] == null) prefs.droppedSyncTrack(savedKey())?.let {
            droppedTracks[savedKey()] = it
            speechFirstKey = savedKey()
        }
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
            listenAhead = ahead,
            rejectInUse = rejectInUse,
            trimTrack = trim?.first,
            trimMs = trim?.second ?: 0L,
        )
        aheadStandby = false
        main.removeCallbacks(beat)
        playheadMs = position().first
        main.postDelayed(beat, BEAT_MS)
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
        embedded?.aheadUri = null
        main.removeCallbacks(following)
        main.removeCallbacks(beat)
        // Nothing listens: a delay only quickly found isn't held on to, nor a look's progress.
        provisional = null
        provisionalAt = 0L
        provisionalSetUndo = false
        progress = 0
    }

    /**
     * Starts copying the film's sound as it loads, for listening ahead, if it can be: a film Media3 reads itself, not
     * live. True when it's copied.
     */
    private fun armAhead(): Boolean {
        val e = embedded ?: return false
        val uri = filmUri()
        if (!LISTEN_AHEAD || uri == null || !isVod()) return false
        // Listening (or about to): what's copied is taken as it comes, nothing waiting is let go for newer. Standing
        // by ends once the job is out ([start]), so the listening thread never sees neither.
        e.ahead.keepNewest = false
        e.aheadUri = uri
        return true
    }

    /**
     * Not listening to these subtitles (the delay set by hand, or auto sync turned off for them), but it may be turned
     * on: the sound loading is copied all the same, not decoded, the newest kept (about the two minutes loaded ahead,
     * at most 4 MB). Turned on (or Resync), that's heard at once, rather than only what loads from then on as the film
     * plays: seconds, not a minute or more; with the film's own subtitles to line up against, it checks them against the
     * talking at once. Also with no downloaded subtitles yet, and with auto sync off (in Settings too): it's only copied,
     * so picking subtitles or turning auto sync on is as quick. On-demand films only ([armAhead]).
     */
    private fun standbyAhead() {
        val l = lines
        // Too few lines to line up: nothing copied would ever be heard.
        if (key == null || (file != null && l != null && l.lines.size < 20) || !armAhead()) {
            endStandby()
            return
        }
        embedded?.ahead?.keepNewest = true
        aheadStandby = true
    }

    /** Copying for [standbyAhead] stops: what was kept is let go (by the listening thread when it's there). */
    private fun endStandby() {
        if (!aheadStandby) return
        aheadStandby = false
        val e = embedded ?: return
        e.ahead.keepNewest = false
        if (job == null) e.aheadUri = null
        if (worker?.isAlive != true) e.ahead.release()
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
            job?.let { stopped(it, "Couldn't line up with the speech. Try Check sync, or set the delay by hand.") }
        }
    }

    /** Where the worker's time goes: looking for the film's own subtitles (listening meanwhile), on them, or listening. */
    private enum class Mode { LOOKING, TRACK, SPEECH }

    /**
     * Listening ahead ([SpeechAhead]): not at all; copying, waiting for the first of it to be heard (listening where the
     * film plays meanwhile); or heard ahead of where the film plays, the tracker stepped along it.
     */
    private enum class Ahead { OFF, WAITING, ON }

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
        // The sure-once delay last told to the main thread to put in use ([tentative]).
        var toldTentative: Long? = null
        // A skip onto film not heard yet, with delays known: the tracker's count of those last seen (and which tracker),
        // and when the latest one's check began (until a look keeps or moves the delay), or 0.
        var jumpTracker: OffsetTracker? = null
        var jumpsSeen = 0
        var jumpSince = 0L
        // The delay quickly found after a jump, and the look's progress, as last told to the main thread.
        var toldProvisional: OffsetTracker.Segment? = null
        var toldProgress = 0
        // Checking the film's own subtitles against the talking ([CrossCheck]): listening now, from where (film time,
        // once heard), since when, when next, whether after Resync, and the last verdict.
        var crossing = false
        var crossFrom = -1L
        var crossSince = 0L
        var crossNext = 0L
        var crossUrgent = false
        var lastVerdict: CrossCheck.Verdict? = null
        // Lining up against the film's own subtitles: checked against the talking heard ahead all through the film, the
        // first at once at full speed ([aheadFast]) on the sound kept ready, then stretch by stretch (from [crossAheadFrom])
        // as it loads, at the usual pace.
        var crossAhead = false
        var crossAheadFrom = -1L
        var crossAheadRuns = 0
        var crossAheadChecks = 0
        var crossAheadJudgedTo = -1L
        var crossAheadSince = 0L
        // On the film's own subtitles: how much the talking fine-tuned them by ([considerTrim]), and the first of the two
        // looks that must agree before it does.
        var trimMs = 0L
        var trimCandidate: Long? = null
        fun trimmed(segs: List<OffsetTracker.Segment>) = if (trimMs == 0L) segs else segs.map { it.copy(delayMs = it.delayMs + trimMs) }
        var aheadFast = false
        // Listening ahead: the sound copied as it loads, its decoder and format (and why it's left, when it is), the
        // state, whether it failed for this film, since when it's waited and been behind, and the tracker last stepped
        // along it.
        val ring = embedded?.ahead
        val speechAhead = SpeechAhead(timeline)
        var aheadDecoder: SideAudioDecoder? = null
        var aheadFormat: Format? = null
        var aheadTrack = -1
        var aheadSkip: String? = null
        var aheadLastUs = -1L
        var aheadState = Ahead.OFF
        var aheadFailed = false
        var aheadSince = 0L
        var behindSince = 0L
        var aheadTracker: OffsetTracker? = null
        // Film loaded again that was heard before isn't decoded again ([SpeechAhead.skipIfHeard]), unless the sound track
        // was changed in this film (the track last heard ahead): heard with another sound, it's all heard again.
        var heardTrack = -1
        var skipHeard = true
        var aheadPassedOver = false
        var idleSince = 0L
        // Listening ahead was on when the last job ended (nothing to do since): see Resync below.
        var aheadWasOn = false
        // For the cost log: 10 ms marks heard where the film plays while listening ahead, the most pieces waiting.
        var filledFrames = 0L
        var aheadBacklog = 0
        val aheadOut = SideAudioDecoder.Output { pcm, ch, rate, us -> speechAhead.feed(pcm, us, ch, rate, C.ENCODING_PCM_16BIT) }
        var decodedSince = 0L
        var skippedSince = 0L
        var droppedSince = 0L
        var filledSince = 0L
        cost.ahead = { wall, decoderMs ->
            val decoded = speechAhead.decodedMs - decodedSince
            decodedSince = speechAhead.decodedMs
            val skipped = speechAhead.skippedMs - skippedSince
            skippedSince = speechAhead.skippedMs
            val droppedUs = ring?.droppedUs ?: 0L
            val dropped = droppedUs - droppedSince
            droppedSince = droppedUs
            val filled = filledFrames - filledSince
            filledSince = filledFrames
            val edge = speechAhead.edgeMs
            val lead = if (edge >= 0 && playheadMs >= 0) edge - playheadMs else 0L
            val backlog = aheadBacklog
            aheadBacklog = 0
            "listening ahead ${aheadState.name.lowercase()}: decoded ${OffsetTracker.clock(decoded)} of film " +
                "(x${"%.1f".format(java.util.Locale.ROOT, decoded.toDouble() / maxOf(1L, wall))}), " +
                "heard before and not decoded again ${OffsetTracker.clock(skipped)}, lead ${lead / 1000} s, " +
                "ahead decoder $decoderMs ms ${"%.2f%%".format(java.util.Locale.ROOT, decoderMs * 100.0 / maxOf(1L, wall))}, " +
                "sound let go ${dropped / 1_000_000} s (ring full), backlog max $backlog pieces, playhead filled ${filled / 100} s"
        }

        fun basisNow() = if (mode == Mode.TRACK) Basis.TRACK else Basis.SPEECH

        fun letDecoderGo() {
            decoder?.release(); decoder = null; decoderKind = null
        }

        fun letAheadDecoderGo() {
            runCatching { aheadDecoder?.release() }
            aheadDecoder = null
            aheadFormat = null
            aheadTrack = -1
            aheadSkip = null
            aheadLastUs = -1
        }

        /** Stops copying the sound as it loads, and lets go of what was kept for it. */
        fun aheadDisarm() {
            letAheadDecoderGo()
            // Armed on the main thread for [standbyAhead] after the job this thread was on stopped: not this one's to stop.
            if (aheadStandby && job == null) return
            embedded?.aheadUri = null
            ring?.release()
        }

        /** Listening ahead for [current], if it can: waits for the first of it to be heard. */
        fun aheadArm(current: Job) {
            if (!current.listenAhead || aheadFailed || ring == null || current.filmUri == null || mode == Mode.TRACK) {
                // Copying may have been started for this job on the main thread all the same (it failed here before).
                if (aheadState != Ahead.OFF || embedded?.aheadUri != null) aheadDisarm()
                aheadState = Ahead.OFF
                return
            }
            embedded?.aheadUri = current.filmUri
            // Listened to from now: what's copied is taken as it comes (what was kept meanwhile first).
            ring.keepNewest = false
            aheadState = Ahead.WAITING
            aheadSince = SystemClock.elapsedRealtime()
            behindSince = 0
        }

        /** Listening ahead stops for this film ([why] for the log): where the film plays is listened to again. */
        fun aheadOff(why: String) {
            if (aheadState == Ahead.OFF) return
            val wasOn = aheadState == Ahead.ON
            Log.i(TAG, "not listening ahead ($why): listening where the film plays")
            aheadFailed = true
            aheadState = Ahead.OFF
            aheadDisarm()
            if (wasOn) {
                // Stepped from where it plays again: the first step, behind where the tracker was, starts afresh there.
                tap.listening = true
                cursor = -1.0
                stepAt = played
                lastKind = null
            }
        }

        /** A decision of [tr]'s, on [tl]: put in, logged, and the skip times told. False: the job ended. */
        fun handle(current: Job, tr: OffsetTracker, tl: SpeechTimeline, d: OffsetTracker.Decision, cpuMs: Long): Boolean {
            val onTrack = mode == Mode.TRACK
            val ahead = !onTrack && aheadState == Ahead.ON
            // Skipped onto film not heard yet: checked there, the badge saying so until a look keeps the delay (told even
            // if told before) or moves it, or for a while at most.
            if (tr !== jumpTracker) {
                jumpTracker = tr
                jumpsSeen = tr.jumpChecks
                jumpSince = 0L
                // Another tracker: what the last one had quickly found isn't its.
                if (toldProvisional != null) {
                    toldProvisional = null
                    main.post { provisional(current, null, "") }
                }
            } else if (tr.jumpChecks != jumpsSeen) {
                jumpsSeen = tr.jumpChecks
                jumpSince = SystemClock.elapsedRealtime()
                main.post { jumpChecking(current, true) }
            }
            if (jumpSince > 0) {
                if (d.toMs > tr.jumpedToMs && (d.outcome == Outcome.KEPT || d.outcome == Outcome.MOVED)) {
                    jumpSince = 0L
                    // Told even if told before: that ends the check on the panel.
                    if (d.outcome == Outcome.KEPT) keptTold = null
                } else if (SystemClock.elapsedRealtime() - jumpSince > JUMP_CHECK_SHOW_MS) {
                    jumpSince = 0L
                    main.post { jumpChecking(current, false) }
                }
            }
            if (d.outcome != Outcome.WAIT) {
                cost.matched(cpuMs)
                Log.i(TAG, if (onTrack) "film's subtitles: $d${if (trimMs != 0L) " | fine-tuned by the talking ${SyncMatcher.formatShift(trimMs)}" else ""}"
                    else if (ahead) "ahead (playing ${OffsetTracker.clock(playheadMs)}): $d" else d.toString())
            }
            when (d.outcome) {
                Outcome.LEARNED, Outcome.MOVED, Outcome.TUNED -> {
                    val b = basisNow()
                    keptTold = b
                    val how = when {
                        onTrack -> "the film's own subtitles"
                        ahead -> "the speech heard ahead (to ${OffsetTracker.clock(d.toMs)}, playing ${OffsetTracker.clock(playheadMs)})"
                        else -> "the speech where the film plays"
                    }
                    // On the film's own subtitles, fine-tuned by the talking: the delays as they're put in.
                    val dd = if (onTrack && trimMs != 0L) {
                        OffsetTracker.Decision(d.outcome, trimmed(d.segments), d.delayMs + trimMs, d.atMs, d.fromMs, d.toMs, d.result, d.rHere, d.note)
                    } else d
                    main.post { decided(current, dd, b, how) }
                }
                Outcome.KEPT -> {
                    val b = basisNow()
                    if (keptTold != b) {
                        keptTold = b
                        main.post { kept(current, b) }
                    }
                    // Every check that bears the delay out is counted, for the panel's "In sync".
                    main.post { checked(current, moved = false) }
                }
                Outcome.GAVE_UP -> return false
                else -> {}
            }
            // Finding the first delay: what one look was sure of is put in use at once, while the next looks confirm it
            // (or find another, which takes over), rather than the subtitles waiting on the confirmation.
            if (d.outcome == Outcome.NOT_SURE) {
                val t = tr.tentativeMs
                if (t != null && t != toldTentative) {
                    toldTentative = t
                    val how = if (onTrack) "the film's own subtitles" else if (ahead) "the speech heard ahead" else "the speech where the film plays"
                    val inUse = t + if (onTrack) trimMs else 0L
                    main.post { tentative(current, inUse, how) }
                }
            }
            // After a jump: the delay a quick look found is in use at once, from where the jump landed, until the film
            // heard since confirms it (it's in the map then) or drops it.
            val pv = tr.provisional
            if (pv != toldProvisional) {
                toldProvisional = pv
                val how = if (ahead) "the speech heard ahead" else "the speech where the film plays"
                main.post { provisional(current, pv, how) }
            }
            val pr = tr.progress
            if (pr != toldProgress && (abs(pr - toldProgress) >= PROGRESS_STEP || pr == 0 || toldProgress == 0)) {
                toldProgress = pr
                main.post { progressed(current, pr) }
            }
            // What's confirmed grew or the offsets changed: the main thread gets a fresh snapshot for the skip times,
            // after decided() (a first delay learnt has made it SYNCED by then).
            val segs = if (onTrack) trimmed(tr.segments) else tr.segments
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
            // What's heard ahead goes on, now for the talking to line up against.
            crossAhead = false
            aheadFast = false
            trimMs = 0
            trimCandidate = null
            mode = Mode.SPEECH
            tracker = OffsetTracker(current.lines, known, centerMs, if (known.isEmpty()) OffsetTracker.RANGE_MS else current.rangeMs,
                knownJumps = current.jumps)
            cursor = -1.0
            played = 0
            stepAt = 0
            lastKind = null
            keptTold = null
            toldTentative = null
            cost.against = "speech"
            tap.listening = true
            aheadTracker = null
            aheadArm(current)
            main.post { usingSpeech(current, why) }
        }

        /** Lining up against track [key] of the film's own from here on, carrying on from what's known. */
        fun toTrack(current: Job, key: String, why: String) {
            val source = embedded?.source(current.filmUri) ?: return
            // What's known as it's put in (another track's fine-tuning added), then in this track's own terms: less the
            // talking's fine-tuning found for it before, put in again.
            val before = tracker?.segments?.takeIf { it.isNotEmpty() }?.let { if (mode == Mode.TRACK) trimmed(it) else it } ?: current.segments
            trimMs = if (current.trimTrack == key) current.trimMs else 0L
            trimCandidate = null
            val known = if (trimMs == 0L) before else before.map { it.copy(delayMs = it.delayMs - trimMs) }
            if (trimMs != 0L) Log.i(TAG, "the film's subtitles fine-tuned by the talking as before: ${SyncMatcher.formatShift(trimMs)}")
            Log.i(TAG, "lining up with the film's own subtitles ($why): $key; tracks: ${source.describe()}; not listening meanwhile")
            tracker = OffsetTracker(current.lines, known, current.centerMs, current.rangeMs, knownJumps = current.jumps, clean = true,
                priorMs = current.priorMs.takeIf { known.isEmpty() })
            reference = ReferenceSync()
            trackKey = key
            mode = Mode.TRACK
            crossing = false
            crossNext = SystemClock.elapsedRealtime() + CROSS_FIRST_MS
            keptTold = null
            toldTentative = null
            letDecoderGo()
            tap.listening = false
            // The film's own subtitles are read as it loads already. What's heard ahead (the sound kept ready, then as it loads)
            // checks them against the talking all along ([crossAhead]), the first time at once.
            crossAhead = aheadState != Ahead.OFF
            // Only from where the film is on: what was heard before (another job's stretch) goes by delays carried over.
            crossAheadFrom = maxOf(playheadMs, -1L)
            crossAheadRuns = speechAhead.runs
            crossAheadChecks = 0
            crossAheadJudgedTo = -1
            crossAheadSince = SystemClock.elapsedRealtime()
            aheadTracker = null
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
                // Not found in [OffsetTracker.LEARN_MAX_MS] of film: it goes on looking, afresh from here (the talking
                // may be clearer further on, or the viewer skips), the delay as it is meanwhile.
                tracker?.learnAgain()
                main.post { stillLooking(current) }
                return true
            } else {
                stopped(current, text)
            }
            return false
        }

        fun mark(talking: Boolean) {
            if (cursor >= 0) {
                timeline.mark(cursor.toInt(), talking)
                played++
                if (aheadState == Ahead.ON) filledFrames++
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
                    // Heard ahead, the tracker is stepped along that: here only holes in it are filled.
                    if (mode != Mode.TRACK && aheadState != Ahead.ON) tracker?.seeked((frame * 10).toLong(), timeline)
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
                s.push(frames, { f, c -> fb.get(f * channels + c) }) { out, side, n -> detector.push(out, side, n) { mark(it) } }
            } else {
                val sb = buf.asShortBuffer()
                val frames = sb.remaining() / channels
                s.push(frames, { f, c -> sb.get(f * channels + c) / 32768f }) { out, side, n -> detector.push(out, side, n) { mark(it) } }
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
                if (mode != Mode.TRACK && aheadState != Ahead.ON && was != null && was !== kind && !sameSound(was, kind) && cursor >= 0 &&
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

        /**
         * One piece of the sound copied as it loads (waiting at most [waitMs] for it), decoded and marked as talking or
         * not, then a pause in proportion after the first delay is found, so it takes at most a tenth of this thread's
         * time then. False when there was none.
         */
        fun pumpAhead(current: Job, waitMs: Long): Boolean {
            val r = ring ?: return false
            var piece = r.take(waitMs) ?: return false
            // Another film's (the one before, loaded still): nothing to do with this one.
            while (piece.uri != current.filmUri) {
                r.recycle(piece)
                piece = r.take(0) ?: return false
            }
            aheadBacklog = maxOf(aheadBacklog, r.pending() + 1)
            val t0 = SystemClock.elapsedRealtime()
            try {
                val fmt = piece.format
                if (fmt == null || piece.count == 0) return true
                if (fmt !== aheadFormat || piece.track != aheadTrack) {
                    val was = aheadFormat
                    val changed = was == null || piece.track != aheadTrack || !sameAudio(was, fmt)
                    // The sound changing format in the middle of the film (ads are often encoded differently): a hint.
                    if (was != null && changed && aheadState == Ahead.ON && speechAhead.edgeMs >= 0 &&
                        abs(piece.firstUs / 1000 - speechAhead.edgeMs) <= JUMP_FRAMES * 10L
                    ) {
                        tracker?.hint(piece.firstUs / 1000, OffsetTracker.Hint.FORMAT)
                        Log.i(TAG, "sound format changed at ${OffsetTracker.clock(piece.firstUs / 1000)} (ahead): a hint to check the timing")
                    }
                    if (changed) {
                        runCatching { aheadDecoder?.release() }
                        aheadDecoder = null
                        aheadSkip = null
                        aheadLastUs = -1
                        val mime = fmt.sampleMimeType
                        when {
                            mime == MimeTypes.AUDIO_RAW -> if (fmt.pcmEncoding !in READABLE_PCM) aheadSkip = "can't read this PCM"
                            // Blu-ray lossless: too heavy to decode on the side.
                            mime == MimeTypes.AUDIO_TRUEHD -> aheadSkip = "too heavy"
                            SideAudioDecoder.canDecode(mime) -> {
                                // Decoded on this (background) thread with [SideAudioDecoder.decodeNow]: one piece in
                                // and out, and FFmpeg's own thread stays idle.
                                aheadDecoder = try {
                                    SideAudioDecoder(fmt, 1)
                                } catch (e: Exception) {
                                    aheadSkip = "no decoder: ${e.message}"
                                    null
                                }
                            }
                            else -> aheadSkip = "no decoder for $mime"
                        }
                        aheadSkip?.let { Log.i(TAG, "not listening ahead to this sound ($it): ${describeAudio(fmt)}") }
                    }
                    aheadFormat = fmt
                    aheadTrack = piece.track
                }
                if (aheadSkip != null) return true
                val span = piece.lastUs - piece.firstUs
                val raw = aheadDecoder == null
                if (piece.count >= 4 && span >= 500_000 && piece.size * 1_000_000L / span > if (raw) HEAVY_PCM_BYTES_PER_S else HEAVY_BYTES_PER_S) {
                    aheadSkip = "too heavy"
                    Log.i(TAG, "not listening ahead to this sound (too heavy, ${piece.size * 1_000L / span} KB/s): ${describeAudio(fmt)}")
                    return true
                }
                // Samples were let go just before (the listener was behind): a hole, not a jump in the film.
                if (piece.afterDrop) speechAhead.expectGap()
                if (piece.track != heardTrack) {
                    if (heardTrack >= 0) skipHeard = false
                    heardTrack = piece.track
                }
                // Film heard before, loaded again (a rewind onto it, the player loading it again): not decoded again. The
                // stretch goes on through it all the same, and the decoder starts afresh at the next piece decoded.
                if (skipHeard && speechAhead.skipIfHeard(piece.firstUs, piece.endUs)) {
                    aheadPassedOver = true
                    return true
                }
                val d = aheadDecoder
                if (d == null) {
                    for (i in 0 until piece.count) {
                        val a = piece.startOf(i)
                        speechAhead.feed(ByteBuffer.wrap(piece.bytes, a, piece.ends[i] - a), piece.timesUs[i], fmt.channelCount, fmt.sampleRate, fmt.pcmEncoding)
                    }
                } else {
                    // Not following on from the last piece (or pieces between were passed over): the decoder starts afresh.
                    if (aheadPassedOver || (aheadLastUs >= 0 && abs(piece.firstUs - aheadLastUs) > AheadAudio.GAP_US)) d.flush()
                    // Decoded here, sample by sample: handing them to FFmpeg's thread and waiting on it (sleeps that a
                    // background thread's timer stretches) heard only about 8 times as fast as the film plays.
                    val cpu0 = SystemClock.currentThreadTimeMillis()
                    for (i in 0 until piece.count) {
                        val a = piece.startOf(i)
                        d.decodeNow(piece.bytes, a, piece.ends[i] - a, piece.timesUs[i], aheadOut)
                    }
                    cost.aheadDecodeMs += SystemClock.currentThreadTimeMillis() - cpu0
                }
                aheadPassedOver = false
                aheadLastUs = piece.lastUs
            } catch (e: InterruptedException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Listening ahead failed", t)
                aheadOff("decoding failed")
            } finally {
                r.recycle(piece)
            }
            val spent = SystemClock.elapsedRealtime() - t0
            if (spent > 0) Thread.sleep(minOf(spent * if (aheadFast || tracker?.let { it.learning || it.settling } != false) AHEAD_REST_LEARNING else AHEAD_REST, 1_000L))
            return true
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

        /**
         * The check against the talking heard ahead is done: nothing more is decoded, and the usual checks as the film plays
         * go on, the next in [nextInMs]. The sound loading is still copied, the newest kept (as standing by), so Audio only,
         * Resync or a track let go hears the two minutes loaded at once rather than only what loads from then on.
         */
        fun endCrossAhead(nextInMs: Long = CROSS_EVERY_MS) {
            crossAhead = false
            aheadFast = false
            if (aheadState != Ahead.OFF) {
                letAheadDecoderGo()
                ring?.keepNewest = true
            }
            aheadState = Ahead.OFF
            crossNext = SystemClock.elapsedRealtime() + nextInMs
        }

        /**
         * The talking, standing out clearly, says the film's own subtitles are a little off here: more than [TRIM_MIN_MS],
         * less than a disagreement. Twice the same way (within [TRIM_AGREE_MS]), every delay is fine-tuned by it: the track
         * still decides where the breaks are, the talking only how the whole lines up. Never more than [TRIM_LIMIT_MS] in
         * all (further, the usual check lets the track go). The subtitles stay on screen, only moved.
         */
        fun considerTrim(current: Job, tr: OffsetTracker, res: CrossCheck.Result) {
            val off = res.delayMs - res.inUseMs
            val clear = !res.z.isNaN() && res.z >= TRIM_Z
            if (!clear) return
            if (abs(off) < TRIM_MIN_MS) {
                // Agrees closely: nothing to fine-tune.
                trimCandidate = null
                return
            }
            if (abs(off) >= TRIM_MAX_MS) return
            val first = trimCandidate
            if (first == null || abs(first - off) > TRIM_AGREE_MS) {
                trimCandidate = off
                Log.i(TAG, "the talking says the film's subtitles are ${SyncMatcher.formatShift(off)} off here: checked again before fine-tuning")
                return
            }
            trimCandidate = null
            val by = (first + off) / 2
            if (abs(trimMs + by) > TRIM_LIMIT_MS) return
            trimMs += by
            val segs = trimmed(tr.segments)
            // Sent again with the next decision (the skip times).
            publishedSegments = null
            Log.i(TAG, "fine-tuned by the talking: ${SyncMatcher.formatShift(by)} (the film's subtitles ${SyncMatcher.formatShift(trimMs)} in all)")
            val track = trackKey
            val total = trimMs
            main.post { trimmedByTalking(current, segs, by, track, total) }
        }

        /** The talking is sure of another delay, twice: the track goes, and it's found by listening from what it said. */
        fun dropTrack(current: Job, res: CrossCheck.Result, why: String) {
            val key = trackKey ?: return
            dropped = key
            main.post { trackDropped(current, key) }
            toSpeech(current, why, emptyList(), res.delayMs)
            main.post { if (current.generation == generation) setState(state, "The film's own subtitles didn't match the speech, so it goes by the speech now.") }
        }

        /** The talking heard in this check judged; a track the talking disagrees with is dropped. */
        fun judgeCross(current: Job, tr: OffsetTracker) {
            val to = (cursor * 10).toLong()
            val res = CrossCheck.judge(timeline, current.lines, trimmed(tr.segments), crossFrom, to)
            Log.i(TAG, "checked against the speech: $res")
            val urgent = crossUrgent
            val verdict = res.verdict
            if (verdict == CrossCheck.Verdict.AGREES) main.post { checked(current, moved = false) }
            // Unsure after Resync: soon again; else as usual.
            endCross(if (verdict == CrossCheck.Verdict.UNSURE && urgent) CROSS_RETRY_MS else CROSS_EVERY_MS)
            val twice = verdict == CrossCheck.Verdict.DISAGREES && (urgent || lastVerdict == CrossCheck.Verdict.DISAGREES)
            if (verdict != CrossCheck.Verdict.UNSURE) lastVerdict = verdict
            if (twice) {
                dropTrack(current, res, "it disagrees with the speech")
                return
            }
            considerTrim(current, tr, res)
            // A little off once: checked again soon, before it's fine-tuned.
            if (trimCandidate != null) crossNext = minOf(crossNext, SystemClock.elapsedRealtime() + CROSS_RETRY_MS)
            if (urgent && verdict != CrossCheck.Verdict.UNSURE) {
                main.post { checkDone(current) }
            }
        }

        // However the loop ends, the side decoder (and its thread) is let go.
        try {
            while (!Thread.currentThread().isInterrupted) {
                val current = job
                if (current == null) {
                    // Nothing to do: the decoders are let go, and the thread waits. The sound copied as it loads is let
                    // go too, unless it's copied for listening about to start (for long, it isn't).
                    letDecoderGo()
                    letAheadDecoderGo()
                    if (aheadState == Ahead.ON) aheadWasOn = true
                    aheadState = Ahead.OFF
                    val now = SystemClock.elapsedRealtime()
                    if (idleSince == 0L) idleSince = now
                    // Kept while standing by for auto sync to be turned on for these subtitles ([standbyAhead]).
                    if (aheadStandby) {
                        // Nothing.
                    } else if (job != null) {
                        // A job just came out (its listening takes the copying over).
                    } else if (embedded?.aheadUri == null && now - idleSince > IDLE_RELEASE_MS) {
                        // Not copied any more (for a moment, it's only one job giving way to the next).
                        ring?.release()
                    } else if (now - idleSince > IDLE_ARMED_MS) {
                        aheadDisarm()
                    }
                    try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                    continue
                }
                idleSince = 0L
                if (current.generation != gen) {
                    gen = current.generation
                    if (current.filmKey != filmKey) {
                        timeline.clear()
                        filmKey = current.filmKey
                        tracker = null
                        // Another film: nothing of it heard ahead yet (what's waiting of the one before is passed over).
                        speechAhead.reset()
                        letAheadDecoderGo()
                        aheadFailed = false
                        heardTrack = -1
                        skipHeard = true
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
                    toldTentative = null
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
                    // Resync while listening ahead: the film heard ahead by the press was heard before it, though it's
                    // after where it was pressed, so the delay turned down doesn't come back on that.
                    val heardTo = if ((aheadState == Ahead.ON || aheadWasOn) && speechAhead.runFromMs >= 0) speechAhead.edgeMs else -1L
                    aheadWasOn = false
                    relearn?.let { t.relearn(it, current.centerMs, timeline, heardTo, reject = current.rejectInUse) }
                    tracker = t
                    // Resync tries at once, with what was heard already.
                    stepAt = if (relearn != null) -TRY_EVERY_FRAMES else 0
                    tap.listening = true
                    // The new tracker is brought to what's been heard ahead (and matched there at once) once it's on.
                    aheadTracker = null
                    crossAhead = false
                    aheadFast = false
                    aheadArm(current)
                }
                val wait = backOffUntil - SystemClock.elapsedRealtime()
                if (wait > 0) {
                    try { Thread.sleep(minOf(wait, 1_000)) } catch (_: InterruptedException) { break }
                    continue
                }

                // Resync pressed: the stretch around where the film is checked again, the rest kept. While still looking
                // for the film's own subtitles, it waits to know whether there are any, but not for long (a film whose
                // only track never has lines enough is looked at all through): then it's checked against the talking.
                resyncRef.get()?.let { if (it.generation != current.generation) resyncRef.compareAndSet(it, null) }
                resyncRef.get()?.takeIf {
                    (mode != Mode.LOOKING || it.speech || SystemClock.elapsedRealtime() - lookingSince > REFERENCE_WAIT_MS) &&
                        resyncRef.compareAndSet(it, null)
                }?.let { rq ->
                    val tr = tracker
                    val ref = reference
                    // Listening ahead: the film heard ahead by the press was heard before it, though it's after where it
                    // was pressed, so the delay turned down doesn't come back on that.
                    val heardTo = if (aheadState == Ahead.ON && speechAhead.runFromMs >= 0) speechAhead.edgeMs else -1L
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
                            toSpeech(current, "Resync pressed again", tr?.segments?.let { trimmed(it) } ?: current.segments)
                        }
                        // Check sync: the next KEPT says it's checked (else it'd be taken as told already, and the badge
                        // would stay on Rechecking).
                        keptTold = null
                        val tr2 = tracker
                        if (aheadState == Ahead.ON && tr2 != null && tr2 === aheadTracker && speechAhead.steppedToMs >= 0) {
                            // Heard ahead: the talking after the press (in the film) is mostly heard already, so it's
                            // checked at once, as against the film's own subtitles.
                            Log.i(TAG, "Resync at ${OffsetTracker.clock(rq.atMs)}: checking again against the speech heard ahead (to ${OffsetTracker.clock(speechAhead.steppedToMs)})")
                            // Check sync: checked as it is (kept if the talking bears it out); pressed again soon after,
                            // the viewer says it's wrong there, so the delay in use is turned down.
                            val d = tr2.recheck(rq.atMs, timeline, reject = rq.speech, nowMs = speechAhead.steppedToMs, heardToMs = heardTo)
                            if (d != null) {
                                if (d.outcome == Outcome.KEPT) keptTold = null
                                handle(current, tr2, timeline, d, 0)
                            }
                        } else {
                            Log.i(TAG, "Resync at ${OffsetTracker.clock(rq.atMs)}: checking again against the speech")
                            tracker?.recheck(rq.atMs, timeline, reject = rq.speech, heardToMs = heardTo)
                            stepAt = played - TRY_EVERY_FRAMES
                        }
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
                        toSpeech(current, "the film's subtitles are gone", trimmed(tr.segments))
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
                                toSpeech(current, "too few lines: ${source.describe()}", trimmed(tr.segments))
                            } else {
                                val cpu0 = SystemClock.currentThreadTimeMillis()
                                val decisions = try {
                                    ref.advance(snap, tr)
                                } catch (t: Exception) {
                                    Log.e(TAG, "Lining up with the film's subtitles failed", t)
                                    trackFailed = true
                                    toSpeech(current, "failed", trimmed(tr.segments))
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
                                            toSpeech(current, "no delay fits", trimmed(tr.segments))
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
                        // Once something's found: the talking heard ahead is asked whether it agrees, at once at full speed
                        // (the sound kept ready is heard in seconds), then each stretch as it loads, so a track from another
                        // cut is let go in seconds and a shift anywhere later is caught before it's on screen.
                        if (crossAhead && !crossing && tr2 != null && !tr2.learning) {
                            if (aheadState == Ahead.OFF) {
                                crossAhead = false
                            } else if (aheadSkip != null || (crossAheadChecks == 0 &&
                                    (SystemClock.elapsedRealtime() - crossAheadSince > CROSS_MAX_WALL_MS ||
                                        (speechAhead.runFromMs < 0 && SystemClock.elapsedRealtime() - crossAheadSince > AHEAD_WAIT_MS)))
                            ) {
                                // The sound loading can't be heard (too heavy, no decoder, protected so nothing is copied), or
                                // nothing came of it for long: checked as the film plays instead, from now.
                                Log.i(TAG, "not checking against the speech heard ahead (${aheadSkip ?: "too long"}): as the film plays")
                                // A sound that can't be heard ahead isn't worth keeping either.
                                if (aheadSkip != null) aheadDisarm()
                                endCrossAhead(0)
                            } else {
                                // The first at full speed (the sound kept ready, in seconds); then as it loads, at the usual pace.
                                aheadFast = crossAheadChecks == 0
                                val got = try { pumpAhead(current, 300) } catch (_: InterruptedException) { break }
                                if (job !== current || mode != Mode.TRACK || !crossAhead || aheadState == Ahead.OFF) continue
                                // Loading started somewhere else (a skip): the stretch starts afresh there.
                                if (speechAhead.runs != crossAheadRuns) {
                                    crossAheadRuns = speechAhead.runs
                                    crossAheadFrom = -1
                                    crossAheadJudgedTo = -1
                                }
                                val from = maxOf(crossAheadFrom, speechAhead.runFromMs)
                                // Only as far as the film's own subtitles have been lined up: past that, the delays aren't
                                // known yet (a break the track hasn't reached), so the talking would seem to disagree.
                                val to = minOf(speechAhead.edgeMs, reference?.steppedToMs ?: -1L)
                                val talk = if (from in 0 until to) timeline.speech.get((from / 10).toInt(), (to / 10).toInt()).cardinality() * 10L else 0L
                                // Judged once there's talking enough, or once all that's loaded is heard (the sound kept
                                // ready: seconds) with as much as a check needs; again only on more heard since.
                                val fresh = crossAheadJudgedTo < 0 || to - crossAheadJudgedTo >= CROSS_AHEAD_MORE_MS
                                val enough = talk >= CROSS_TALK_MS || to - from >= CROSS_MAX_FILM_MS || (!got && talk >= CrossCheck.MIN_TALK_MS)
                                if (from in 0 until to && fresh && enough) {
                                    crossAheadJudgedTo = to
                                    crossAheadChecks++
                                    val res = CrossCheck.judge(timeline, current.lines, trimmed(tr2.segments), from, to)
                                    Log.i(TAG, "checked against the speech heard ahead: $res")
                                    val verdict = res.verdict
                                    val twice = verdict == CrossCheck.Verdict.DISAGREES && lastVerdict == CrossCheck.Verdict.DISAGREES
                                    if (verdict != CrossCheck.Verdict.UNSURE) lastVerdict = verdict
                                    // The talking bears the film's own subtitles out: counted, for the panel's "In sync".
                                    if (verdict == CrossCheck.Verdict.AGREES) main.post { checked(current, moved = false) }
                                    if (!twice && verdict != CrossCheck.Verdict.DISAGREES) considerTrim(current, tr2, res)
                                    when {
                                        twice -> dropTrack(current, res, "it disagrees with the speech heard ahead")
                                        // Checked on, stretch after stretch, all through the film: the next one decides
                                        // what this one couldn't. Unsure: this one grows (up to twice a check's length).
                                        verdict != CrossCheck.Verdict.UNSURE -> crossAheadFrom = to
                                        to - from >= 2 * CROSS_MAX_FILM_MS -> crossAheadFrom = to - CROSS_MAX_FILM_MS
                                        else -> {}
                                    }
                                }
                                continue
                            }
                        }
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
                // Listening ahead: waiting for the first of it, then the tracker is stepped along it, and where the film
                // plays is only listened to for holes in it.
                if (aheadState != Ahead.OFF && mode != Mode.TRACK) {
                    val now = SystemClock.elapsedRealtime()
                    val playing = playheadMs
                    fun aheadReady() = speechAhead.runFromMs >= 0 && speechAhead.edgeMs >= playing
                    if (aheadState == Ahead.WAITING) {
                        if (!aheadReady()) {
                            try { pumpAhead(current, 0) } catch (_: InterruptedException) { break }
                            if (job !== current) continue
                        }
                        if (aheadState == Ahead.WAITING) {
                            if (aheadReady()) {
                                aheadState = Ahead.ON
                                behindSince = 0
                                val f = aheadFormat
                                Log.i(TAG, "listening ahead from ${OffsetTracker.clock(speechAhead.runFromMs)}" +
                                    "${f?.let { ": " + describeAudio(it) } ?: ""}, heard to ${OffsetTracker.clock(speechAhead.edgeMs)}, playing ${OffsetTracker.clock(playing)}")
                            } else if (now - aheadSince > if (speechAhead.runFromMs < 0) AHEAD_WAIT_MS else AHEAD_BEHIND_WAIT_MS) {
                                // A stream in pieces, a protected film or a sound with no decoder: where it plays, then.
                                aheadOff(if (speechAhead.runFromMs < 0) "nothing of the film's sound loaded ahead" else "it didn't catch up with the film")
                            }
                        }
                    }
                    if (aheadState == Ahead.ON) {
                        val covered = playing >= 0 && speechAhead.covers(playing, playing + FILL_LOOK_MS)
                        try { pumpAhead(current, if (covered) 300 else 0) } catch (_: InterruptedException) { break }
                        if (job !== current || aheadState != Ahead.ON) continue
                        cost.maybeLog(timeline, current.lines, tracker)
                        val tr = tracker ?: continue
                        var going = true
                        if (tr !== aheadTracker) {
                            aheadTracker = tr
                            val cpu0 = SystemClock.currentThreadTimeMillis()
                            val d = try {
                                speechAhead.rebase(tr)
                            } catch (t: Exception) {
                                Log.e(TAG, "Tracking ahead failed", t)
                                stopped(current, "Couldn't line up with the speech. Try Check sync, or set the delay by hand.")
                                continue
                            }
                            if (d != null && !handle(current, tr, timeline, d, SystemClock.currentThreadTimeMillis() - cpu0)) {
                                speechGaveUp(current, "gave up", "")
                                going = false
                            }
                        }
                        if (going && speechAhead.due()) {
                            val cpu0 = SystemClock.currentThreadTimeMillis()
                            // A mistake in the tracker must not take the film down: listening stops, what was found stays.
                            val decisions = try {
                                speechAhead.advance(tr)
                            } catch (t: Exception) {
                                Log.e(TAG, "Tracking ahead failed at ${OffsetTracker.clock(speechAhead.steppedToMs)}", t)
                                stopped(current, "Couldn't line up with the speech. Try Check sync, or set the delay by hand.")
                                continue
                            }
                            val cpuMs = (SystemClock.currentThreadTimeMillis() - cpu0) / maxOf(1, decisions.count { it.outcome != Outcome.WAIT })
                            for (d in decisions) {
                                if (job !== current || tracker !== tr) break
                                if (!handle(current, tr, timeline, d, cpuMs)) {
                                    // Gave up finding the first delay: the film's own subtitles, if it has any, else not synced.
                                    speechGaveUp(current, "gave up", "")
                                    break
                                }
                            }
                        }
                        if (job !== current || aheadState != Ahead.ON || mode == Mode.TRACK) continue
                        // Where the film plays, only what wasn't heard ahead (the first moments, a stretch let go).
                        val fill = playing < 0 || !speechAhead.covers(playing, playing + FILL_LOOK_MS)
                        if (fill != tap.listening) {
                            tap.listening = fill
                            if (!fill) {
                                letDecoderGo()
                                cursor = -1.0
                            }
                        }
                        if (fill) {
                            try { hear(current, 100) } catch (_: InterruptedException) { break }
                        }
                        // Not keeping up with the film for long: where it plays, then. Only counted once the film where it
                        // plays has loaded: right after a skip what's heard ends where the film was, and a new position slow
                        // to load isn't the listening falling behind (that would turn it off for the rest of the film).
                        val loadedTo = embedded?.source(current.filmUri)?.loading()?.second ?: Long.MAX_VALUE
                        if (speechAhead.behind(playing, loadedTo)) {
                            if (behindSince == 0L) behindSince = now
                            else if (now - behindSince > AHEAD_BEHIND_WAIT_MS) aheadOff("fell behind the film")
                        } else {
                            behindSince = 0L
                        }
                        continue
                    }
                }

                cost.maybeLog(timeline, current.lines, tracker)
                // Waiting here is where release() usually finds the thread: its interrupt ends listening.
                val ok = try { hear(current, 500) } catch (_: InterruptedException) { break }
                if (!ok) {
                    // The sound can't be heard (no decoder for it). While the film's own subtitles may yet do instead,
                    // or the first of it heard ahead, that can wait; else they're handed over to, or listening stops.
                    if (mode == Mode.LOOKING || aheadState == Ahead.WAITING) continue
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
                    stopped(current, "Couldn't line up with the speech. Try Check sync, or set the delay by hand.")
                    continue
                }
                if (!handle(current, tr, timeline, d, SystemClock.currentThreadTimeMillis() - cpu0)) {
                    // Gave up finding the first delay: the film's own subtitles, if it has any, else not synced.
                    speechGaveUp(current, "gave up", "")
                }
            }
        } finally {
            runCatching { decoder?.release() }
            runCatching { aheadDecoder?.release() }
        }
    }

    /**
     * What listening costs, logged every few minutes, to check it on the TV: processor time of this thread, of FFmpeg's
     * decode threads when the sound goes out undecoded and for the sound heard ahead (each runs on its own thread), and
     * of the whole player for scale; matching; sound pieces dropped; memory; and listening ahead ([ahead]).
     */
    private inner class Cost {
        private var since = SystemClock.elapsedRealtime()
        private var cpuSince = SystemClock.currentThreadTimeMillis()
        private var processSince = Process.getElapsedCpuTime()
        // The side decoders' threads (ids in this process), and their processor time at the last line.
        var decoderThreads: Set<Int> = emptySet()
        /** Processor time spent decoding the sound heard ahead, on the listening thread (ms), since the last line. */
        var aheadDecodeMs = 0L
        private val decoderSince = HashMap<Int, Long>()
        /** Listening ahead, for the line: given how long since the last (ms) and its decoder's processor time. */
        var ahead: ((wallMs: Long, decoderMs: Long) -> String)? = null
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
            fun threadsMs(threads: Set<Int>): Long {
                var ms = 0L
                for (tid in threads) {
                    if (threadName(tid)?.startsWith(DECODE_THREAD) != true) continue
                    val now = threadCpuMs(tid) ?: continue
                    ms += now - (decoderSince[tid] ?: 0L)
                    decoderSince[tid] = now
                }
                return ms
            }
            val decoderMs = threadsMs(decoderThreads)
            val aheadMs = aheadDecodeMs
            aheadDecodeMs = 0
            decoderSince.keys.retainAll(decoderThreads)
            val kb = (timeline.speech.size() + timeline.heard.size()) / 8 / 1024 + lines.frames.size() / 8 / 1024 + lines.ys.size * 8 / 1024
            fun share(ms: Long) = "%.2f%%".format(java.util.Locale.ROOT, ms * 100.0 / wall)
            Log.i(
                TAG,
                "cost over ${wall / 1000} s, processor (% of one core): listening thread $cpu ms ${share(cpu)}, " +
                    "side decoder $decoderMs ms ${share(decoderMs)}, whole player $process ms ${share(process)}; " +
                    "matching $matches × ${if (matches > 0) matchMs / matches else 0} ms (worst $worstMs ms), " +
                    "sound pieces dropped ${tap.dropped - droppedSince}, frame-drop back-offs ${backOffs - backOffsSince}, " +
                    "memory ~$kb KB, segments ${tracker?.segments?.size ?: 0}, lining up against $against" +
                    (ahead?.let { "; " + it(wall, aheadMs) } ?: ""),
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
            if (provisional != null) {
                provisional = null
                applyHere()
            }
            progress = 0
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

    /**
     * On the main thread: the film's own track [trackKey] disagreed with the talking; not used again for these subtitles
     * in this film, the next time it's opened either (kept with what's found, and let go with it).
     */
    private fun trackDropped(current: Job, trackKey: String) {
        if (current.generation != generation) return
        droppedTracks[savedKey()] = trackKey
        prefs.dropSyncTrack(savedKey(), trackKey)
        speechFirstKey = savedKey()
        checkingAt = -1
    }

    // Where the panel's "Rechecking" is for a skip ([jumpChecking]), not Check sync, or -1.
    private var jumpCheckAt = -1L

    /**
     * On the main thread: playing skipped onto film not heard yet, and the timing there is being checked ([on]), or that
     * check took too long to say (the badge goes back to how it was). Check sync's own check isn't taken over.
     */
    private fun jumpChecking(current: Job, on: Boolean) {
        if (current.generation != generation || byHand || state != State.SYNCED) return
        if (on) {
            if (checkingAt >= 0 && checkingAt != jumpCheckAt) return
            checkingAt = position().first.coerceAtLeast(0L)
            jumpCheckAt = checkingAt
        } else {
            if (checkingAt < 0 || checkingAt != jumpCheckAt) return
            checkingAt = -1
            detail = syncedText()
        }
        onChanged()
    }

    /**
     * On the main thread, after a jump with delays known: [seg], found by a quick look, is in use from where the jump
     * landed (not saved, not in [segments]) while the film since confirms it; null: dropped, or confirmed and in
     * [segments] by now ([decided] ran first). Rewinding before the landing shows the delay from before again.
     */
    private fun provisional(current: Job, seg: OffsetTracker.Segment?, how: String) {
        if (current.generation != generation) return
        if (seg == null) {
            if (provisional == null) return
            provisional = null
            provisionalAt = 0L
            // Dropped, and Undo was only for it: nothing to undo.
            if (provisionalSetUndo) undoUs = null
            provisionalSetUndo = false
            if (!byHand && state == State.SYNCED) applyHere()
            onChanged()
            return
        }
        if (byHand || state != State.SYNCED || segments.isEmpty()) return
        val was = provisional
        if (was == null || abs(seg.delayMs - was.delayMs) > TENTATIVE_AGREE_MS) {
            provisionalAt = SystemClock.elapsedRealtime()
            Log.i(TAG, "after a jump: in use after ${"%.1f".format(java.util.Locale.ROOT, (provisionalAt - since()) / 1000.0)} s, " +
                "confirming: ${SyncMatcher.formatShift(seg.delayMs)} from ${OffsetTracker.clock(seg.fromMs)}, by $how")
        }
        provisional = seg
        if (undoUs == null) {
            undoUs = delayUs()
            provisionalSetUndo = true
        }
        applyHere()
        startFollowing()
        onChanged()
    }

    /** On the main thread: how far the look in hand is towards its next decision (the badge's percent). */
    private fun progressed(current: Job, percent: Int) {
        if (current.generation != generation || progress == percent) return
        progress = percent
        onChanged()
    }

    /** On the main thread: a check as the film plays bore the delay out, or put it right ([moved]). */
    private fun checked(current: Job, moved: Boolean) {
        if (current.generation != generation || byHand) return
        checkedAt = SystemClock.elapsedRealtime()
        checkedMoved = moved
    }

    /** On the main thread: a match found the delay in use right ([b]: against what). */
    private fun kept(current: Job, b: Basis) {
        if (current.generation != generation || byHand || state != State.SYNCED) return
        basis = b
        stopHolding()
        val again = checkingAt >= 0
        // After a jump: how long it took from there (the badge's "Synced in").
        if (again && checkingAt == jumpCheckAt && since() > 0) syncedInMs = SystemClock.elapsedRealtime() - since()
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
     * On the main thread, finding the first delay: one look was sure of [ms] ([how]: against what). It's put in use now,
     * not saved, while the next looks confirm it (then it's kept as found) or find another, which takes over the same
     * way. Undo puts back the delay from before.
     */
    private fun tentative(current: Job, ms: Long, how: String) {
        if (current.generation != generation || byHand || segments.isNotEmpty() || state == State.SYNCED) return
        val now = SystemClock.elapsedRealtime()
        val was = tentativeMs
        if (was == null || abs(ms - was) > TENTATIVE_AGREE_MS) {
            tentativeAt = now
            if (startedAt > 0) {
                Log.i(TAG, "in use after ${"%.1f".format(java.util.Locale.ROOT, (now - startedAt) / 1000.0)} s, confirming: " +
                    "${SyncMatcher.formatShift(ms)}, sure once by $how")
            }
        }
        tentativeMs = ms
        val nowUs = delayUs()
        if (abs(ms * 1000 - nowUs) >= MIN_MOVE_US) {
            if (undoUs == null) undoUs = nowUs
            moveDelay(ms * 1000)
        }
        stopHolding()
        setState(State.LISTENING, "Lined up: ${SyncMatcher.formatShift(ms)}, confirming on the talking…")
    }

    /**
     * On the main thread: the talking fine-tuned the film's own subtitles by [byMs] ([totalMs] in all, on [track]): the
     * delays [segs] put in and kept, the subtitles moved smoothly where the film is. Undo last sync puts back the delay
     * from before.
     */
    private fun trimmedByTalking(current: Job, segs: List<OffsetTracker.Segment>, byMs: Long, track: String?, totalMs: Long) {
        if (current.generation != generation || byHand || state != State.SYNCED || segs.isEmpty()) return
        segments = segs
        prefs.saveSyncedSegments(savedKey(), OffsetTracker.encode(segs))
        if (track != null) prefs.saveSyncTrim(savedKey(), track, totalMs)
        val nowUs = delayUs()
        val wantUs = OffsetTracker.delayAt(segs, position().first)?.times(1000)
        if (wantUs != null && abs(wantUs - nowUs) >= MIN_MOVE_US) {
            if (undoUs == null) undoUs = nowUs
            moveDelay(wantUs)
        }
        checkedAt = SystemClock.elapsedRealtime()
        checkedMoved = true
        detail = "Fine-tuned by the audio: ${SyncMatcher.formatShift(byMs)}."
        onChanged()
    }

    /**
     * On the main thread: a delay found (the first, one after a break, or a fine-tuning), put in and kept; [b]: what it
     * was found against.
     */
    private fun decided(current: Job, d: OffsetTracker.Decision, b: Basis, how: String) {
        if (current.generation != generation || byHand || d.segments.isEmpty()) return
        basis = b
        // After a jump (its check on, or its quick delay in use): how long it took from there; that delay is kept now,
        // so Undo set for it stays.
        if (d.outcome != Outcome.LEARNED && since() > 0 && (provisional != null || checkingAt >= 0 && checkingAt == jumpCheckAt)) {
            syncedInMs = SystemClock.elapsedRealtime() - since()
        }
        provisionalSetUndo = false
        if (d.outcome != Outcome.LEARNED) {
            checkedAt = SystemClock.elapsedRealtime()
            checkedMoved = true
        }
        checkingAt = -1
        // How long the first delay took, for the panel (and the log, to check it on the TV).
        if (d.outcome == Outcome.LEARNED && syncedInMs == null && startedAt > 0) {
            val now = SystemClock.elapsedRealtime()
            val t = tentativeMs
            // In use already since one look was sure of it: right from then.
            val inUse = t != null && tentativeAt > 0 && abs(d.segments[0].delayMs - t) <= TENTATIVE_AGREE_MS
            val took = (if (inUse) tentativeAt else now) - startedAt
            // The badge's "Synced in": till it was confirmed ("Matched in" said when it was put in use).
            syncedInMs = now - startedAt
            Log.i(TAG, "first delay in ${"%.1f".format(java.util.Locale.ROOT, took / 1000.0)} s since auto sync started for these subtitles, by $how" +
                if (inUse) " (in use since, confirmed ${"%.1f".format(java.util.Locale.ROOT, (now - startedAt) / 1000.0)} s)" else "")
        }
        tentativeMs = null
        tentativeAt = 0L
        segments = d.segments
        prefs.saveSyncedSegments(savedKey(), OffsetTracker.encode(segments))
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

    /** On the main thread: no first delay yet after minutes of film; still looking (the badge stays as it is). */
    private fun stillLooking(current: Job) {
        if (current.generation != generation || byHand) return
        detail = if (tentativeMs != null) "In use while it's confirmed; the talking here is hard to match." else
            "Still looking: the talking here is hard to match."
        if (state == State.NOT_SYNCED) state = State.LISTENING
        onChanged()
    }

    private fun notFound(current: Job) {
        if (current.generation != generation) return
        tap.listening = false
        stopHolding()
        // One look was sure of a delay, never confirmed: that stays, unconfirmed.
        tentativeMs?.let {
            setState(State.NOT_SYNCED, "Couldn't confirm ${SyncMatcher.formatShift(it)} here; it stays. Try Check sync, or set it by hand.")
            return
        }
        // Started from the last episode's delay: that stays, unconfirmed.
        if (basis == Basis.PRIOR) {
            setState(State.NOT_SYNCED, "Couldn't check the last episode's delay here (${SyncMatcher.formatShift(delayUs() / 1000)}). Try Check sync, or set it by hand.")
            return
        }
        setState(State.NOT_SYNCED, "Couldn't line up with the speech. Try Check sync, or set the delay by hand.")
    }

    /** Puts a delay of auto sync's in (to the 10 ms), remembering the one from before it for when the subtitles go. */
    private fun moveDelay(us: Long) {
        val v = (us / 10_000) * 10_000
        if (baseUs == null) baseUs = delayUs()
        putUs = v
        setDelayUs(v)
    }

    private fun setState(s: State, text: String?) {
        if (s != State.LISTENING) resyncRelearn = false
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
        // Film heard ahead since the last check before another.
        private const val CROSS_AHEAD_MORE_MS = 30_000L
        // The talking fine-tunes the film's own subtitles when they're off by this much or more, less than a disagreement
        // (a second: the track goes), standing out this clearly, twice within this of each other; never more in all.
        private const val TRIM_MIN_MS = 300L
        private const val TRIM_MAX_MS = 1_000L
        private const val TRIM_Z = 4.0
        private const val TRIM_AGREE_MS = 200L
        private const val TRIM_LIMIT_MS = 1_500L
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
        // Nothing of the film read for this long (it's not a file Media3 reads itself): only listening, then. Check sync
        // waits no longer than this either for a track of the film's own worth lining up against.
        private const val REFERENCE_WAIT_MS = 30_000L
        // How often the delay is matched to where the film is, while it changes along the film.
        private const val FOLLOW_EVERY_MS = 300L
        private const val COST_EVERY_MS = 5 * 60_000L
        // Nobody sees less than this, so the delay isn't nudged for it.
        private const val MIN_MOVE_US = 150_000L

        private const val CANT_HEAR = "Can't listen to this film's sound. Set the delay by hand."

        // Listening ahead ([SpeechAhead]): on for films Media3 reads itself (not live).
        private const val LISTEN_AHEAD = true
        // Where the film is, for the listening thread, this often.
        private const val BEAT_MS = 1_000L
        // The playhead further than this from where it was a beat ago: playing jumped.
        private const val JUMP_BEAT_MS = 10_000L
        // The badge's percent is told again when it has moved this much.
        private const val PROGRESS_STEP = 5
        // Nothing of the film's sound heard ahead this long after starting (a stream in pieces, a protected film, a
        // sound with no decoder), or not caught up with where it plays: listened to where it plays instead.
        private const val AHEAD_WAIT_MS = 8_000L
        // Heard ahead only up to [SpeechAhead.BEHIND_MS] behind where the film plays (with the film there loaded), for
        // this long: listened to where it plays instead.
        private const val AHEAD_BEHIND_WAIT_MS = 20_000L
        // Where the film plays is listened to while this much from there isn't heard ahead.
        private const val FILL_LOOK_MS = 5_000L
        // Heavier sound than this (a second of film, compressed or not) isn't decoded on the side: DTS-HD MA, 7.1 PCM.
        private const val HEAVY_BYTES_PER_S = 256 * 1024L
        private const val HEAVY_PCM_BYTES_PER_S = 640 * 1024L
        // After each piece heard ahead, a pause this many times as long as it took: none while finding the first delay
        // (what loaded is heard as fast as it decodes, on this background thread, and dropped frames still make it wait),
        // a tenth after (about twice as fast as the film plays). A quarter at first heard only about 2.3 times as fast
        // (Fire TV Stick 4K Plus): a minute of film before the first delay.
        private const val AHEAD_REST_LEARNING = 0L
        private const val AHEAD_REST = 9L
        // Copying the sound for listening about to start, but none has for this long: let go.
        private const val IDLE_ARMED_MS = 10_000L
        // Copying stopped this long with nothing listening: what was kept is let go.
        private const val IDLE_RELEASE_MS = 1_500L
        // A first delay confirmed within this of the one put in use when sure once: right since then.
        private const val TENTATIVE_AGREE_MS = 500L
        // After a skip, "Rechecking" stays up this long at most while no look keeps or moves the delay.
        private const val JUMP_CHECK_SHOW_MS = 6 * 60_000L
        private val READABLE_PCM = setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)

        /** How long syncing took, for the panel: "14 s", or "2 min" from 100 s on. */
        fun tookText(ms: Long): String {
            val s = Math.round(ms / 1000.0).coerceAtLeast(1L)
            return if (s < 100) "$s s" else "${Math.round(ms / 60_000.0)} min"
        }

        /** The same sound as before: a new format object for it says nothing. */
        private fun sameAudio(a: Format, b: Format) =
            a.sampleMimeType == b.sampleMimeType && a.channelCount == b.channelCount && a.sampleRate == b.sampleRate && a.pcmEncoding == b.pcmEncoding

        /** For the log: "audio/mp4a-latm 2 ch 48000 Hz". */
        private fun describeAudio(f: Format) = "${f.sampleMimeType} ${f.channelCount} ch ${f.sampleRate} Hz"
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
