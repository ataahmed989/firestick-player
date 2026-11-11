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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.BitSet
import kotlin.math.abs

/**
 * Lines up downloaded subtitles with the film's dialogue by itself.
 *
 * While a downloaded subtitle is shown, it listens to the sound being played ([SpeechTap]) for talking
 * ([SpeechDetector]) and slides the subtitle lines over it ([SyncMatcher]). Once it's sure (two tries in a row agree,
 * usually within a minute or two of dialogue) the shift goes into the subtitle delay and it stops listening. It
 * listens once more, for a minute or so, two thirds of the way into the film, to catch subtitles that slowly drift;
 * then it's off for the rest of the film. [resync] listens afresh. A delay set by hand always wins: [handOff].
 *
 * Kept light for the Fire TV Stick: one background thread at low priority, a 10 ms on/off mark of talking (about
 * 90 KB for two hours), nothing listened to beyond what plays, and it backs off while the picture drops frames.
 * The result is kept per subtitle file and film, so the same pair is lined up at once next time.
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
    private var lines: List<SubtitleTimes.Line> = emptyList()
    private var marks = BitSet()
    private var rechecked = false
    // Set when the delay was moved by hand, so nothing moves it again for this subtitle.
    private var byHand = false

    private val jobRef = java.util.concurrent.atomic.AtomicReference<Job?>(null)
    private var job: Job?
        get() = jobRef.get()
        set(v) = jobRef.set(v)
    @Volatile private var backOffUntil = 0L
    private var worker: Thread? = null

    /** One stretch of listening: [fromFrame] on, shifts within [centerMs] ± [rangeMs], given up after [maxHeardMs]. */
    private class Job(
        val generation: Int,
        val lines: List<SubtitleTimes.Line>,
        val marks: BitSet,
        val fromFrame: Int,
        val centerMs: Long,
        val rangeMs: Long,
        val maxHeardMs: Long,
        val recheck: Boolean,
    )

    private var generation = 0

    /**
     * The subtitle now shown: a downloaded file, or null for anything else (embedded, handed over, off). Following a
     * new file starts listening, or applies at once what was found for it before.
     */
    fun follow(subtitle: File?, filmKey: String?) {
        if (subtitle == file && filmKey == key) return
        stopListening()
        file = subtitle
        key = filmKey
        undoUs = null
        byHand = false
        rechecked = false
        lines = emptyList()
        marks = BitSet()
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
        val saved = prefs.syncedDelayMs(savedKey())
        if (saved != null) {
            moveDelay(saved * 1000)
            setState(State.SYNCED, "Lined up with the speech: ${SyncMatcher.formatShift(saved)}.")
            rechecked = true
            // Still read the lines, for Resync.
            loadLines(subtitle) {}
            return
        }
        loadLines(subtitle) { start(recheck = false) }
    }

    /** Throws away what was found and listens again from here. */
    fun resync() {
        if (file == null) return
        stopListening()
        byHand = false
        rechecked = true
        prefs.forgetSyncedDelay(savedKey())
        prefs.forgetHandDelay(savedKey())
        if (lines.isEmpty()) loadLines(file!!) { start(recheck = false) } else start(recheck = false)
    }

    /** The delay was changed by hand: auto sync stays out of it for this subtitle, and the delay is kept for it. */
    fun handOff(delayUs: Long) {
        if (file == null) return
        prefs.saveHandDelay(savedKey(), delayUs / 1000)
        if (state == State.OFF) return
        stopListening()
        byHand = true
        undoUs = null
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
        prefs.forgetSyncedDelay(savedKey())
        setState(State.OFF, "Off for these subtitles; the delay is back to ${SyncMatcher.formatShift(before / 1000)}.")
    }

    /** The picture dropped frames: listening waits a few seconds, so the film comes first. */
    fun framesDropped() {
        backOffUntil = SystemClock.elapsedRealtime() + 5_000
    }

    fun release() {
        stopListening()
        main.removeCallbacksAndMessages(null)
        worker?.interrupt()
        worker = null
    }

    // ---- Listening ----------------------------------------------------------

    private fun loadLines(subtitle: File, then: () -> Unit) {
        val gen = ++generation
        Thread {
            val read = SubtitleTimes.read(subtitle)
            main.post {
                if (gen != generation || subtitle != file) return@post
                lines = read
                marks = SubtitleTimes.frames(read)
                if (read.size < 20) setState(State.NOT_SYNCED, "These subtitles have too few lines to line up.") else then()
            }
        }.apply { isDaemon = true; name = "FirePlayerSyncRead" }.start()
    }

    private fun start(recheck: Boolean) {
        if (lines.size < 20 || byHand) return
        val gen = ++generation
        val (posMs, _) = position()
        job = if (recheck) {
            Job(gen, lines, marks, (posMs / 10).toInt(), delayUs() / 1000, RECHECK_RANGE_MS, RECHECK_MAX_HEARD_MS, true)
        } else {
            Job(gen, lines, marks, 0, 0, SyncMatcher.RANGE_MS, MAX_HEARD_MS, false)
        }
        if (!recheck) setState(State.LISTENING, "Listening for speech. Usually takes a minute or two of talking.")
        ensureWorker()
        tap.listening = true
        if (!recheck && !rechecked) main.postDelayed(watchForRecheck, 30_000)
    }

    private fun stopListening() {
        generation++
        job = null
        tap.listening = false
        main.removeCallbacks(watchForRecheck)
    }

    /** Two thirds in, a short second listen for drift. */
    private val watchForRecheck = object : Runnable {
        override fun run() {
            if (rechecked || byHand || file == null) return
            if (state != State.SYNCED) {
                if (state == State.LISTENING) main.postDelayed(this, 30_000)
                return
            }
            val (pos, length) = position()
            if (length < 20 * 60_000L) return
            if (pos >= length * 2 / 3) {
                rechecked = true
                start(recheck = true)
            } else {
                main.postDelayed(this, 30_000)
            }
        }
    }

    private fun ensureWorker() {
        if (worker?.isAlive == true) return
        worker = Thread({ listen() }, "FirePlayerSync").apply { isDaemon = true; start() }
    }

    /** On the worker thread: reads the sound pieces, marks talking, and tries matching as more is heard. */
    private fun listen() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val timeline = SpeechTimeline()
        val detector = SpeechDetector()
        var sampler: MonoDownsampler? = null
        var decoder: SideAudioDecoder? = null
        var decoderKind: SpeechTap.Kind? = null
        var cursor = -1.0
        var gen = -1
        // 10 ms marks heard for the first time in this stretch of listening, and when matching was last tried.
        var fresh = 0L
        var lastTry = 0L
        var agreed: SyncMatcher.Result? = null

        fun mark(talking: Boolean) {
            if (cursor >= 0 && timeline.mark(cursor.toInt(), talking)) fresh++
            cursor += 1.0
        }

        fun feed(pcm: ByteBuffer, filmUs: Long, channels: Int, rate: Int, float: Boolean) {
            if (channels <= 0 || rate <= 0) return
            val s = sampler?.takeIf { it.channels == channels && it.inRate == rate }
                ?: MonoDownsampler(rate, channels).also { sampler = it; detector.reset() }
            val frame = filmUs / 10_000.0
            // More than 50 ms away from where the last piece ended: a jump (or a gap), start counting from here.
            if (cursor < 0 || abs(frame - cursor) > 5) {
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
                if (!current.recheck) timeline.clear()
                cursor = -1.0
                fresh = 0
                lastTry = 0
                agreed = null
                decoder?.flush()
            }
            val wait = backOffUntil - SystemClock.elapsedRealtime()
            if (wait > 0) {
                try { Thread.sleep(minOf(wait, 1_000)) } catch (_: InterruptedException) { break }
                continue
            }
            val piece = tap.take(500) ?: continue
            try {
                val kind = piece.kind ?: continue
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

            if (job !== current) continue
            val heard = fresh * 10
            if (heard - lastTry < TRY_EVERY_MS || heard < MIN_HEARD_MS) continue
            lastTry = heard
            val (speech, h) = timeline.snapshot(current.fromFrame)
            val result = SyncMatcher.match(speech, h, current.marks, current.lines, current.centerMs, current.rangeMs)
            Log.d(TAG, "heard ${heard / 1000}s: $result")
            if (result != null && result.confident) {
                val before = agreed
                if (before != null && abs(before.delayMs - result.delayMs) <= 100) {
                    jobRef.compareAndSet(current, null)
                    main.post { found(current, result) }
                    continue
                }
                agreed = result
            } else {
                agreed = null
            }
            if (heard >= current.maxHeardMs) {
                jobRef.compareAndSet(current, null)
                main.post { notFound(current) }
            }
        }
        decoder?.release()
    }

    private fun cantHear(current: Job) {
        jobRef.compareAndSet(current, null)
        main.post {
            if (current.generation != generation) return@post
            tap.listening = false
            if (!current.recheck) setState(State.NOT_SYNCED, "Can't listen to this film's sound. Set the delay by hand.")
        }
    }

    /** On the main thread: a shift both tries agreed on. */
    private fun found(current: Job, result: SyncMatcher.Result) {
        if (current.generation != generation) return
        tap.listening = false
        val nowUs = delayUs()
        val newUs = result.delayMs * 1000
        if (abs(newUs - nowUs) >= MIN_MOVE_US) {
            if (!current.recheck || undoUs == null) undoUs = nowUs
            moveDelay(newUs)
        }
        prefs.saveSyncedDelay(savedKey(), delayUs() / 1000)
        val what = if (current.recheck) "Checked again later in the film" else "Lined up with the speech"
        setState(State.SYNCED, "$what: ${SyncMatcher.formatShift(delayUs() / 1000)}.")
    }

    private fun notFound(current: Job) {
        if (current.generation != generation) return
        tap.listening = false
        // A recheck that isn't sure leaves the first answer in place.
        if (!current.recheck) setState(State.NOT_SYNCED, "Couldn't line up with the speech. Try Resync, or set the delay by hand.")
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
        private const val TRY_EVERY_MS = 15_000L
        private const val MIN_HEARD_MS = 30_000L
        private const val MAX_HEARD_MS = 6 * 60_000L
        private const val RECHECK_RANGE_MS = 10_000L
        private const val RECHECK_MAX_HEARD_MS = 3 * 60_000L
        // Nobody sees less than this, so the delay isn't nudged for it.
        private const val MIN_MOVE_US = 150_000L
    }
}
