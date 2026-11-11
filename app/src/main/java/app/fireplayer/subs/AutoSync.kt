package app.fireplayer.subs

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
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
 * A delay set by hand always wins ([handOff]): it's one delay for the whole film, and auto sync stays out of it for
 * these subtitles. [resync] forgets that and everything found, finds the delay again around where the film is (from
 * what was heard since the last break, so often at once), then carries on following. [undo] puts back the delay from
 * before auto sync and leaves it off for these subtitles.
 *
 * Kept light for the Fire TV Stick: one background thread at low priority, a 10 ms on/off mark of talking (about
 * 90 KB for two hours), nothing listened to beyond what plays, matching at most every 15 s of film and usually every
 * few minutes, and it backs off while the picture drops frames. What it costs is logged every few minutes (tag AutoSync).
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

    private val main = Handler(Looper.getMainLooper())
    private var key: String? = null
    private var lines: SyncMatcher.Lines? = null
    // Set when the delay was moved by hand, so nothing moves it again for this subtitle.
    private var byHand = false
    // The delays found, by film position: the main thread's copy of the listening thread's (empty while none).
    private var segments: List<OffsetTracker.Segment> = emptyList()

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
    )

    private var generation = 0

    /**
     * The subtitle now shown: a downloaded file, or null for anything else (embedded, handed over, off). Following a
     * new file starts listening, or applies at once what was found for it before and listens on from there.
     */
    fun follow(subtitle: File?, filmKey: String?) {
        if (subtitle == file && filmKey == key) return
        stopListening()
        file = subtitle
        key = filmKey
        undoUs = null
        byHand = false
        lines = null
        segments = emptyList()
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
            OffsetTracker.followedDelayMs(byHand, true, segments, position().first)?.let { moveDelay(it * 1000) }
            setState(State.SYNCED, syncedText())
            startFollowing()
            // Listens on from what was found, in case the timing changes somewhere new this time.
            loadLines(subtitle) { start() }
            return
        }
        loadLines(subtitle) { start() }
    }

    /**
     * Throws away what was found (and a delay set by hand), finds the delay again around where the film is, from what
     * was heard since the last break if there's enough, else from what's heard next, then follows the film as before.
     */
    fun resync() {
        val f = file ?: return
        stopListening()
        byHand = false
        segments = emptyList()
        prefs.forgetSyncedDelay(savedKey())
        prefs.forgetHandDelay(savedKey())
        val at = position().first
        if (lines == null) loadLines(f) { start(relearnAtMs = at) } else start(relearnAtMs = at)
    }

    /** The delay was changed by hand: auto sync stays out of it for this subtitle, and the delay is kept for it. */
    fun handOff(delayUs: Long) {
        if (file == null) return
        prefs.saveHandDelay(savedKey(), delayUs / 1000)
        if (state == State.OFF) return
        stopListening()
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
            setState(State.OFF, null)
        }
    }

    /** Puts the delay back to what it was before auto sync moved it. */
    fun undo() {
        val before = undoUs ?: return
        stopListening()
        setDelayUs(before)
        undoUs = null
        byHand = true
        segments = emptyList()
        prefs.forgetSyncedDelay(savedKey())
        setState(State.OFF, "Off for these subtitles; the delay is back to ${SyncMatcher.formatShift(before / 1000)}.")
    }

    /** The picture dropped frames: listening waits a few seconds, so the film comes first. */
    fun framesDropped() {
        backOffUntil = SystemClock.elapsedRealtime() + 5_000
        backOffs++
    }

    fun release() {
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
        detail = syncedText()
        onChanged()
    }

    /** What the panel says while synced, for the delay where the film is. */
    private fun syncedText(): String {
        val i = OffsetTracker.indexAt(segments, position().first)
        val ms = segments.getOrNull(i)?.delayMs ?: (delayUs() / 1000)
        val what = if (i == 0) "Lined up with the speech" else "Lined up again after a break"
        return "$what: ${SyncMatcher.formatShift(ms)}."
    }

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
        job = if (relearnAtMs != null) {
            // Resync looks further either way, around the delay in use: after a few breaks it can be minutes.
            Job(gen, k, l, known, delayUs() / 1000, OffsetTracker.RANGE_MS, relearnAtMs)
        } else {
            Job(gen, k, l, known, 0, SyncMatcher.RANGE_MS, null)
        }
        if (known.isEmpty()) setState(State.LISTENING, "Listening for speech. Usually takes a minute or two of talking.")
        ensureWorker()
        tap.listening = true
    }

    private fun stopListening() {
        generation++
        job = null
        tap.listening = false
        main.removeCallbacks(following)
    }

    private fun ensureWorker() {
        if (worker?.isAlive == true) return
        worker = Thread({ listen() }, "FirePlayerSync").apply { isDaemon = true; start() }
    }

    /** On the worker thread: reads the sound pieces, marks talking, and lets the tracker match as more is heard. */
    private fun listen() {
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
        // 10 ms marks heard (new or again) since the job started, and at the last step.
        var played = 0L
        var stepAt = 0L
        // The sound's format just before, to notice it change in the middle of the film.
        var lastKind: SpeechTap.Kind? = null
        val cost = Cost()

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
                if (cursor < 0 || abs(frame - cursor) > JUMP_FRAMES) tracker?.seeked((frame * 10).toLong(), timeline)
                cursor = frame
                detector.reset()
            }
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

        while (!Thread.currentThread().isInterrupted) {
            val current = job
            if (current == null) {
                // Nothing to do: the decoder is let go, and the thread waits.
                decoder?.release(); decoder = null; decoderKind = null
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
                // Resync with the same subtitles keeps the tracker's idea of where breaks were, to start from the last.
                val t = tracker?.takeIf { current.relearnAtMs != null && it.lines === current.lines }
                    ?: OffsetTracker(current.lines, current.segments, current.centerMs, current.rangeMs)
                current.relearnAtMs?.let { t.relearn(it, current.centerMs) }
                tracker = t
                cursor = -1.0
                played = 0
                // Resync tries at once, with what was heard already.
                stepAt = if (current.relearnAtMs != null) -TRY_EVERY_FRAMES else 0
                lastKind = null
                decoder?.flush()
            }
            val wait = backOffUntil - SystemClock.elapsedRealtime()
            if (wait > 0) {
                try { Thread.sleep(minOf(wait, 1_000)) } catch (_: InterruptedException) { break }
                continue
            }
            cost.maybeLog(timeline, current.lines, tracker)
            val piece = tap.take(500) ?: continue
            try {
                val kind = piece.kind ?: continue
                // The sound changing format in the middle of the film (ads are often encoded differently): a hint.
                val was = lastKind
                if (was != null && was !== kind && !sameSound(was, kind) && cursor >= 0 && abs(piece.filmUs / 10_000.0 - cursor) <= JUMP_FRAMES) {
                    tracker?.hint(piece.filmUs / 1000, OffsetTracker.Hint.FORMAT)
                    Log.i(TAG, "sound format changed at ${OffsetTracker.clock(piece.filmUs / 1000)}: a hint to check the timing")
                }
                lastKind = kind
                if (!kind.encoded) {
                    feed(ByteBuffer.wrap(piece.bytes, 0, piece.size), piece.filmUs, kind.channels, kind.sampleRate, kind.pcmEncoding == C.ENCODING_PCM_FLOAT)
                } else {
                    if (decoderKind !== kind) {
                        decoder?.release()
                        decoder = null
                        decoderKind = kind
                        if (SideAudioDecoder.canDecode(kind.format.sampleMimeType)) {
                            decoder = runCatching { SideAudioDecoder(kind.format) }.onFailure { Log.w(TAG, "No side decoder", it) }.getOrNull()
                        }
                        if (decoder == null) cantHear(current)
                    }
                    val d = decoder ?: continue
                    var tries = 0
                    while (!d.queue(piece.bytes, piece.size, piece.filmUs) && tries++ < 50) {
                        d.drain { pcm, ch, rate, us -> feed(pcm, us, ch, rate, false) }
                        Thread.sleep(2)
                    }
                    d.drain { pcm, ch, rate, us -> feed(pcm, us, ch, rate, false) }
                }
            } catch (_: InterruptedException) {
                break
            } catch (t: Throwable) {
                // A decoder that fails stays failed for this sound; the film plays on regardless.
                Log.w(TAG, "Listening failed", t)
                decoder?.release(); decoder = null
                cantHear(current)
            } finally {
                tap.recycle(piece)
            }

            if (job !== current || cursor < 0) continue
            val tr = tracker ?: continue
            if (played - stepAt < TRY_EVERY_FRAMES) continue
            val playedMs = (played - maxOf(stepAt, 0L)) * 10
            stepAt = played
            val cpu0 = SystemClock.currentThreadTimeMillis()
            val d = tr.step(timeline, (cursor * 10).toLong(), playedMs)
            if (d.outcome != Outcome.WAIT) {
                cost.matched(SystemClock.currentThreadTimeMillis() - cpu0)
                Log.i(TAG, d.toString())
            }
            when (d.outcome) {
                Outcome.LEARNED, Outcome.MOVED, Outcome.TUNED -> main.post { decided(current, d) }
                Outcome.GAVE_UP -> {
                    jobRef.compareAndSet(current, null)
                    main.post { notFound(current) }
                }
                else -> {}
            }
        }
        decoder?.release()
    }

    /** What listening costs, logged every few minutes, to check it on the TV: this thread's processor time and memory. */
    private inner class Cost {
        private var since = SystemClock.elapsedRealtime()
        private var cpuSince = SystemClock.currentThreadTimeMillis()
        private var droppedSince = tap.dropped
        private var backOffsSince = backOffs
        private var matches = 0
        private var matchMs = 0L
        private var worstMs = 0L

        fun matched(ms: Long) {
            matches++
            matchMs += ms
            worstMs = maxOf(worstMs, ms)
        }

        fun maybeLog(timeline: SpeechTimeline, lines: SyncMatcher.Lines, tracker: OffsetTracker?) {
            val wall = SystemClock.elapsedRealtime() - since
            if (wall < COST_EVERY_MS) return
            val cpu = SystemClock.currentThreadTimeMillis() - cpuSince
            val kb = (timeline.speech.size() + timeline.heard.size()) / 8 / 1024 + lines.frames.size() / 8 / 1024 + lines.ys.size * 8 / 1024
            Log.i(
                TAG,
                "cost over ${wall / 1000} s: listening thread ${cpu} ms of processor (${"%.2f".format(java.util.Locale.ROOT, cpu * 100.0 / wall)}% of one core), " +
                    "matching $matches × ${if (matches > 0) matchMs / matches else 0} ms (worst $worstMs ms), " +
                    "sound pieces dropped ${tap.dropped - droppedSince}, frame-drop back-offs ${backOffs - backOffsSince}, " +
                    "memory ~$kb KB, segments ${tracker?.segments?.size ?: 0}",
            )
            since = SystemClock.elapsedRealtime()
            cpuSince = SystemClock.currentThreadTimeMillis()
            droppedSince = tap.dropped
            backOffsSince = backOffs
            matches = 0
            matchMs = 0
            worstMs = 0
        }
    }

    private fun cantHear(current: Job) {
        jobRef.compareAndSet(current, null)
        main.post {
            if (current.generation != generation) return@post
            tap.listening = false
            // Still learning: say so. Already following: what was found stays in use.
            if (segments.isEmpty()) setState(State.NOT_SYNCED, "Can't listen to this film's sound. Set the delay by hand.")
        }
    }

    /** On the main thread: a delay found (the first, one after a break, or a fine-tuning), put in and kept. */
    private fun decided(current: Job, d: OffsetTracker.Decision) {
        if (current.generation != generation || byHand || d.segments.isEmpty()) return
        segments = d.segments
        prefs.saveSyncedSegments(savedKey(), OffsetTracker.encode(segments))
        // Also the one delay older versions read.
        prefs.saveSyncedDelay(savedKey(), segments[0].delayMs)
        val nowUs = delayUs()
        val wantUs = (OffsetTracker.delayAt(segments, position().first) ?: return) * 1000
        if (abs(wantUs - nowUs) >= MIN_MOVE_US) {
            if (d.outcome == Outcome.LEARNED || undoUs == null) undoUs = nowUs
            moveDelay(wantUs)
        }
        setState(State.SYNCED, syncedText())
        startFollowing()
    }

    private fun notFound(current: Job) {
        if (current.generation != generation) return
        tap.listening = false
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
        // Matching is tried at most this often: every 15 s of film played.
        private const val TRY_EVERY_FRAMES = 1_500L
        // Further than this (10 s) from where the last sound ended is a jump, not a gap from pieces dropped.
        private const val JUMP_FRAMES = 1_000
        // How often the delay is matched to where the film is, while it changes along the film.
        private const val FOLLOW_EVERY_MS = 300L
        private const val COST_EVERY_MS = 5 * 60_000L
        // Nobody sees less than this, so the delay isn't nudged for it.
        private const val MIN_MOVE_US = 150_000L

        /** The same sound as before (a new format object for it says nothing). */
        private fun sameSound(a: SpeechTap.Kind, b: SpeechTap.Kind) =
            a.encoded == b.encoded && a.channels == b.channels && a.sampleRate == b.sampleRate &&
                a.pcmEncoding == b.pcmEncoding && a.format.sampleMimeType == b.format.sampleMimeType
    }
}
