package app.fireplayer.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.annotation.RequiresApi
import app.fireplayer.data.Prefs
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The blurred film that the glass shows (Glass look). Fire OS can't blur what's behind a window, so now and
 * then a small copy of the film is taken with PixelCopy (Android 7 and later; older TVs get the dark fill),
 * blurred on a background thread, and drawn inside each glass shape by [GlassDrawable].
 *
 * One per player, not shared: a new player opens before the old one closes, and the old one's onStop and
 * onDestroy must not stop the new one's copies. Copies are only taken while glass is drawn, so with the glass
 * look off nothing here runs. Everything happens on the main thread except the copy and the blur, which run on
 * the "FirePlayerGlass" thread. Between copies a tiny one checks for a camera cut ([probeTick]), so a new shot
 * reaches the glass at once instead of at the next refresh.
 */
class GlassBackdrop(private val activity: Activity, private val surfaceView: SurfaceView) {

    /** An activity whose glass shows its film. Others (Home) have no film: their glass gets the dark fill. */
    interface Host {
        val glassBackdrop: GlassBackdrop?
    }

    /** Two looks of the film: very soft and dimmed for windows, less blurred and brighter for the controls. */
    enum class Kind { PANEL, CONTROL }

    /** Its callback takes the probes' answers: messages, not posted lambdas, so a probe makes nothing new. */
    private val main = Handler(Looper.getMainLooper(), Handler.Callback { msg ->
        if (msg.what == MSG_PROBED) onProbed(msg.arg1, msg.arg2, msg.obj)
        true
    })
    private val density = activity.resources.displayMetrics.density
    private val prefs = Prefs(activity)

    /** Time between copies while the film plays, from Settings (Look, Glass refresh); read each time, so a change applies at once. */
    private val refreshMs: Long get() = 1_000L / prefs.glassRefreshPerSec.coerceIn(1, 8)

    /**
     * Each new copy fades in over nearly the whole gap to the next one, so the glass keeps drifting with the film
     * instead of sitting still and then catching up; it's done just before the next copy lands.
     */
    private val crossfadeMs: Float get() = refreshMs * FADE_SHARE
    private val drawables = WeakHashMap<GlassDrawable, Boolean>()
    private val visiting = ArrayList<GlassDrawable>()
    private val location = IntArray(2)

    private var started = false
    private var released = false
    /** Bumped by stop(): copies asked for before then are thrown away when they arrive. */
    @Volatile private var generation = 0
    /** Bumped when the picture changes (a seek, a new size): a copy asked for before then is out of date. */
    private var changeSerial = 0
    private var changedAt = 0L

    private val frames = arrayOfNulls<Bitmap>(2)
    private val fadingFrom = arrayOfNulls<Bitmap>(2)
    private var valid = false
    private var holdUntil = 0L
    private var capturedAt = 0L
    private var pausedAt = 0L
    private var fadeStart = 0L
    private var expiryAt = 0L

    /** Screen pixels per pixel of the blurred film, across and down. */
    var screenScale = 8f
        private set
    var screenScaleY = 8f
        private set
    /** Where the film's top-left corner is on screen. */
    var originX = 0
        private set
    var originY = 0
        private set

    private var disabled = false
    private var unknownErrors = 0
    private var stuckCopies = 0
    private var noDataTries = 0
    private var blocked = false
    private var waitingForSurface = surfaceView.holder.surface?.isValid != true
    private var capturePending = false
    private var inFlight = false
    private var requestId = 0
    private var lastRequestAt = 0L
    private var ticking = false
    private var captureAt = 0L
    private var thread: HandlerThread? = null
    private var worker: Handler? = null

    private var probeLooping = false
    private var probeInFlight = false
    private var probeFailures = 0
    private var probesOff = false
    /** The copy now asked for follows a camera cut. */
    private var cutRequested = false
    /** The newest copy followed a cut, so it fades in over CUT_FADE_MS rather than most of the gap. */
    private var fastFade = false
    /** The [copySignature] of the picture the glass shows, which probes are compared with. Never changed once made. */
    private var shownSignature: IntArray? = null

    // The worker thread's own state.
    private var copyBitmap: Bitmap? = null
    private var composite: Bitmap? = null
    private var compositeCanvas: Canvas? = null
    private val compositePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var pixels = IntArray(0)
    private var work = IntArray(0)
    private var temp = IntArray(0)
    private var copying = false
    private var queued: Job? = null
    private var probeBitmap: Bitmap? = null
    /** A PixelCopy.OnPixelCopyFinishedListener (Android 7), made once: probes come ten times a second. */
    private var probeListener: Any? = null
    private var probing = false
    private var probeGen = 0
    private var probeRef: IntArray? = null
    private val samples = IntArray(PROBE_WIDTH * PROBE_HEIGHT)
    private val sampleRows = IntArray(COPY_WIDTH * 2)
    private val probeSignature = IntArray(SIGNATURE_SIZE)

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = surfaceReady()

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = surfaceReady()

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            waitingForSurface = true
        }
    }

    init {
        surfaceView.holder.addCallback(surfaceCallback)
    }

    /** Whether the film is playing (onIsPlayingChanged): playing, the copy is renewed every second. */
    var playing = false
        set(value) {
            if (field == value) return
            val now = SystemClock.uptimeMillis()
            if (value) {
                // A copy taken during the pause shows the picture as it starts again; an older one only aged until the pause.
                if (pausedAt > 0L) capturedAt = if (capturedAt >= pausedAt) now else capturedAt + (now - pausedAt)
            } else {
                pausedAt = now
            }
            field = value
            if (!started) return
            scheduleExpiry()
            if (!anyVisible()) return
            // Paused: one more copy, of the picture that now stays.
            if (value) startTicking(now) else scheduleCapture(CHANGE_DELAY_MS)
        }

    /** True while the TV changes its refresh rate: no copies then, the screen is blank and timing is measured. */
    var suspended = false
        set(value) {
            if (field == value) return
            field = value
            if (!started) return
            if (value) cancelCapture() else if (anyVisible()) scheduleCapture(CHANGE_DELAY_MS)
        }

    /**
     * The picture changed (a seek landed, the first frame showed, a new size or display mode). Glass on screen
     * keeps the old picture until a new copy fades in; glass that appears later starts from the dark fill.
     */
    fun frameChanged() {
        if (!started) return
        val now = SystemClock.uptimeMillis()
        changeSerial++
        changedAt = now
        noDataTries = 0
        blocked = false
        val visible = anyVisible()
        if (frames[0] != null) {
            if (visible && drawableAt(now)) {
                holdUntil = now + HOLD_MS
                valid = false
                scheduleExpiry()
            } else {
                dropFrames(fade = false, now = now)
            }
        }
        cancelCapture()
        if (visible) scheduleCapture(CHANGE_DELAY_MS)
    }

    /** The activity's onStart. */
    fun start() {
        if (released) return
        started = true
        noDataTries = 0
        blocked = false
        waitingForSurface = surfaceView.holder.surface?.isValid != true
    }

    /** The activity's onStop: no more copies, and the old picture is forgotten so it can't flash on return. */
    fun stop() {
        if (!started) return
        started = false
        generation++
        cancelCapture()
        main.removeCallbacks(tick)
        main.removeCallbacks(watchdog)
        main.removeCallbacks(probeTick)
        main.removeCallbacks(probeWatchdog)
        main.removeMessages(MSG_PROBED)
        ticking = false
        inFlight = false
        probeLooping = false
        probeInFlight = false
        noDataTries = 0
        blocked = false
        dropFrames(fade = false, now = 0L)
        capturedAt = 0L
    }

    /** The activity's onDestroy. Everything after it does nothing. */
    fun release() {
        if (released) return
        stop()
        released = true
        surfaceView.holder.removeCallback(surfaceCallback)
        thread?.quitSafely()
        thread = null
        worker = null
        drawables.clear()
    }

    // ---- For GlassDrawable (main thread) -------------------------------

    /** The blurred film to show now, or null for the dark fill (none yet, out of date, or copies impossible). */
    fun frame(kind: Kind): Bitmap? = if (drawableAt(SystemClock.uptimeMillis())) frames[kind.ordinal] else null

    /** During a fade, what the new film fades in over; null means the dark fill. */
    fun previous(kind: Kind): Bitmap? = if (fadeFraction() < 1f) fadingFrom[kind.ordinal] else null

    /** How far the fade to the newest film is, from 0 to 1 (done). */
    fun fadeFraction(): Float {
        if (fadeStart == 0L) return 1f
        val length = if (fastFade) CUT_FADE_MS.toFloat() else crossfadeMs
        val f = (SystemClock.uptimeMillis() - fadeStart).toFloat() / length
        if (f >= 1f) return 1f
        if (f <= 0f) return 0f
        // Eased at both ends, so one fade flows into the next without a visible start or stop.
        return f * f * (3f - 2f * f)
    }

    /** Glass to repaint when a new copy arrives. Kept weakly: rows are thrown away on every key press. */
    internal fun register(drawable: GlassDrawable) {
        if (!released) drawables[drawable] = true
    }

    /** Called by each glass shape as it draws: glass on screen is what asks for copies. */
    fun onGlassDrawn() {
        if (!started || !copiesPossible()) return
        val now = SystemClock.uptimeMillis()
        if (playing) startTicking(now)
        if (frames[0] == null || !valid || (playing && now - capturedAt >= refreshMs)) scheduleCapture(0L)
    }

    // ---- Scheduling (main thread) ---------------------------------------

    private fun copiesPossible() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !disabled

    private fun canCapture() = started && copiesPossible() && !suspended && !waitingForSurface && !blocked

    private fun drawableAt(time: Long): Boolean {
        if (frames[0] == null) return false
        if (!valid && time >= holdUntil) return false
        // Playing, the picture moves on; paused, a copy taken since the pause stays right.
        return (if (playing) time else pausedAt) - capturedAt <= STALE_MS
    }

    private fun scheduleCapture(delay: Long) {
        if (capturePending || inFlight || !canCapture()) return
        val now = SystemClock.uptimeMillis()
        // Never right after a change (the new picture may not be up yet), nor more often than MIN_GAP_MS.
        val at = maxOf(now + delay, lastRequestAt + MIN_GAP_MS, changedAt + CHANGE_DELAY_MS)
        capturePending = true
        captureAt = at
        main.postAtTime(captureRunnable, at)
    }

    private fun cancelCapture() {
        main.removeCallbacks(captureRunnable)
        capturePending = false
        cutRequested = false
    }

    private val captureRunnable = Runnable {
        capturePending = false
        val cut = cutRequested
        cutRequested = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !inFlight && canCapture()) requestCapture(cut)
    }

    /** While the film plays under glass that's on screen: a new copy every second. */
    private val tick = object : Runnable {
        override fun run() {
            ticking = false
            if (!started || !playing || !copiesPossible() || !anyVisible()) return
            val now = SystemClock.uptimeMillis()
            val due = if (frames[0] != null && valid) capturedAt + refreshMs else now
            val next = if (now >= due - TICK_SLACK_MS) {
                scheduleCapture(0L)
                now + refreshMs
            } else {
                due
            }
            ticking = true
            main.postAtTime(this, next)
        }
    }

    private fun startTicking(now: Long) {
        startProbing(now)
        if (ticking || !started || !copiesPossible()) return
        ticking = true
        val due = if (frames[0] != null && valid) capturedAt + refreshMs else now
        main.postAtTime(tick, max(due, now))
    }

    // ---- Cut probe (main thread) -----------------------------------------

    /**
     * Beside [tick], under the same conditions: every PROBE_MS a tiny copy is compared with the picture the glass
     * shows. A camera cut or a big flash then gets a copy at once, rather than up to a whole refresh later.
     */
    private val probeTick = object : Runnable {
        override fun run() {
            probeLooping = false
            if (!started || !playing || probesOff || !copiesPossible() || !anyVisible()) return
            val now = SystemClock.uptimeMillis()
            requestProbe(now)
            probeLooping = true
            main.postAtTime(this, now + PROBE_MS)
        }
    }

    private fun startProbing(now: Long) {
        if (probeLooping || probesOff || !started || !copiesPossible()) return
        probeLooping = true
        main.postAtTime(probeTick, now + PROBE_MS)
    }

    /** No probe while a copy is on its way or about due (that copy shows the cut anyway), or nothing is shown. */
    private fun requestProbe(now: Long) {
        val ref = shownSignature ?: return
        val handler = worker ?: return
        if (probeInFlight || inFlight || !valid || !canCapture() || !drawableAt(now)) return
        if (capturePending && captureAt - now < PROBE_LEAD_MS) return
        if (now >= capturedAt + refreshMs - PROBE_LEAD_MS) return
        probeInFlight = true
        main.postDelayed(probeWatchdog, WATCHDOG_MS)
        handler.obtainMessage(MSG_PROBE, generation, 0, ref).sendToTarget()
    }

    /** A probe that never answers counts as a stuck copy, as in [watchdog]. */
    private val probeWatchdog = Runnable {
        if (probeInFlight) {
            probeInFlight = false
            if (++stuckCopies >= 2) disable()
        }
    }

    /** Back on the main thread with a probe's answer; [ref] is the signature it was compared with. */
    private fun onProbed(gen: Int, code: Int, ref: Any?) {
        if (!started || gen != generation) return
        probeInFlight = false
        main.removeCallbacks(probeWatchdog)
        when (code) {
            COPY_SUCCESS, PROBE_CUT -> {
                stuckCopies = 0
                probeFailures = 0
                // Only if the glass still shows the picture it was compared with, and no copy is on its way.
                if (code == PROBE_CUT && ref === shownSignature && playing && valid && !inFlight) cut()
            }
            // A timeout held up the TV's drawing, as for a copy: never again for this player.
            COPY_TIMEOUT -> disable()
            // Usually the surface going away mid-probe; if it keeps failing, the regular copies carry on alone.
            FAILED -> if (++probeFailures >= PROBE_FAILURES) stopProbing()
            // Nothing shown, no surface, or busy: the regular copies deal with those, and the next probe tries again.
            COPY_NO_DATA, COPY_SOURCE_INVALID, NO_SURFACE, PROBE_BUSY -> Unit
            else -> if (++unknownErrors >= 2) disable()
        }
    }

    private fun stopProbing() {
        probesOff = true
        main.removeCallbacks(probeTick)
        probeLooping = false
    }

    /**
     * The film cut to another shot: a copy now (MIN_GAP_MS and CHANGE_DELAY_MS still apply), faded in over
     * CUT_FADE_MS, and the regular copies count on from it.
     */
    private fun cut() {
        cancelCapture()
        scheduleCapture(0L)
        cutRequested = capturePending
    }

    private fun scheduleExpiry() {
        main.removeCallbacks(expiry)
        if (!started || frames[0] == null) return
        var at = Long.MAX_VALUE
        if (!valid) at = holdUntil
        if (playing) at = min(at, capturedAt + STALE_MS)
        if (at == Long.MAX_VALUE) return
        expiryAt = at
        main.postAtTime(expiry, at + 1)
    }

    /** A picture that's too old or out of date: glass showing it fades to the dark fill, and it's forgotten. */
    private val expiry = Runnable {
        val now = SystemClock.uptimeMillis()
        if (frames[0] != null && !drawableAt(now)) {
            dropFrames(fade = drawableAt(expiryAt - 1) && anyVisible(), now = now)
        } else {
            scheduleExpiry()
        }
    }

    /** A copy that never answers: stop waiting for it, and give up on copies if it happens twice. */
    private val watchdog = Runnable {
        if (inFlight) {
            inFlight = false
            if (++stuckCopies >= 2) disable()
        }
    }

    /** Forgets the pictures; glass showing one fades to the dark fill rather than jumping to it. */
    private fun dropFrames(fade: Boolean, now: Long) {
        for (i in frames.indices) {
            fadingFrom[i] = if (fade) frames[i] else null
            frames[i] = null
        }
        fadeStart = if (fade) now else 0L
        fastFade = false
        shownSignature = null
        valid = false
        holdUntil = 0L
        main.removeCallbacks(expiry)
        invalidateAll()
    }

    /** For the rest of this player the glass keeps the dark fill: copies failed in a way that could repeat. */
    private fun disable() {
        disabled = true
        cancelCapture()
        main.removeCallbacks(tick)
        main.removeCallbacks(watchdog)
        main.removeCallbacks(probeTick)
        main.removeCallbacks(probeWatchdog)
        ticking = false
        probeLooping = false
        probeInFlight = false
        if (frames[0] != null) {
            val now = SystemClock.uptimeMillis()
            dropFrames(fade = drawableAt(now) && anyVisible(), now = now)
        }
    }

    private fun surfaceReady() {
        if (released) return
        waitingForSurface = false
        frameChanged()
    }

    private fun anyVisible(): Boolean {
        if (drawables.isEmpty()) return false
        for (drawable in drawables.keys) if (drawable.isOnScreen()) return true
        return false
    }

    private fun invalidateAll() {
        if (drawables.isEmpty()) return
        visiting.addAll(drawables.keys)
        for (drawable in visiting) drawable.invalidateSelf()
        visiting.clear()
    }

    // ---- Copying ---------------------------------------------------------

    /** What a copy needs to know about the screen, worked out on the main thread. */
    private class Job(
        val id: Int,
        val gen: Int,
        val serial: Int,
        val at: Long,
        val height: Int,
        val bw: Int,
        val bh: Int,
        val kx: Float,
        val ky: Float,
        val ox: Int,
        val oy: Int,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val panelSigma: Float,
        val controlSigma: Float,
        val topScrim: Float,
        val bottomScrim: Float,
        val cut: Boolean,
    )

    private fun buildJob(cut: Boolean): Job? {
        val root = activity.window?.decorView ?: return null
        val w = root.width
        val h = root.height
        if (w <= 0 || h <= 0 || surfaceView.width <= 0 || surfaceView.height <= 0) return null
        root.getLocationOnScreen(location)
        val ox = location[0]
        val oy = location[1]
        // Where the film is on screen: Zoom scales video_frame, so every scale on the way up counts.
        var sx = 1f
        var sy = 1f
        var v: View? = surfaceView
        while (v != null) {
            sx *= v.scaleX
            sy *= v.scaleY
            v = v.parent as? View
        }
        surfaceView.getLocationOnScreen(location)
        val bw = (w / 8f).roundToInt().coerceAtLeast(1)
        val bh = (h / 8f).roundToInt().coerceAtLeast(1)
        val kx = w.toFloat() / bw
        val ky = h.toFloat() / bh
        val left = (location[0] - ox) / kx
        val top = (location[1] - oy) / ky
        return Job(
            id = ++requestId,
            gen = generation,
            serial = changeSerial,
            at = SystemClock.uptimeMillis(),
            height = h,
            bw = bw,
            bh = bh,
            kx = kx,
            ky = ky,
            ox = ox,
            oy = oy,
            left = left,
            top = top,
            right = left + surfaceView.width * sx / kx,
            bottom = top + surfaceView.height * sy / ky,
            panelSigma = PANEL_BLUR_DP * density / kx,
            controlSigma = CONTROL_BLUR_DP * density / kx,
            topScrim = TOP_SCRIM_DP * density,
            bottomScrim = BOTTOM_SCRIM_DP * density,
            cut = cut,
        )
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun requestCapture(cut: Boolean) {
        val job = buildJob(cut) ?: return
        val handler = worker ?: startWorker()
        inFlight = true
        lastRequestAt = job.at
        main.postDelayed(watchdog, WATCHDOG_MS)
        handler.post { copy(job, handler) }
    }

    /** The thread starts with the first copy, so with the glass look off there's none. */
    private fun startWorker(): Handler {
        val t = HandlerThread("FirePlayerGlass", Process.THREAD_PRIORITY_BACKGROUND)
        t.start()
        thread = t
        // Probes come as messages, like their answers (see [main]).
        return Handler(t.looper, Handler.Callback { msg ->
            val ref = msg.obj as? IntArray
            if (msg.what == MSG_PROBE && ref != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                probe(msg.arg1, ref, msg.target)
            }
            true
        }).also { worker = it }
    }

    /**
     * On the worker. PixelCopy waits on the TV's render thread, so never on the main thread, where it would
     * upset VsyncFix's frame timing.
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun copy(job: Job, handler: Handler) {
        if (job.gen != generation) return
        // Newer Android copies in the background: one at a time, as they share the bitmap.
        if (copying) {
            queued = job
            return
        }
        val surface = surfaceView.holder.surface
        if (surface == null || !surface.isValid) {
            answer(job, NO_SURFACE, null, null)
            return
        }
        val dest = copyBitmap ?: Bitmap.createBitmap(COPY_WIDTH, COPY_HEIGHT, Bitmap.Config.ARGB_8888).also { copyBitmap = it }
        copying = true
        try {
            PixelCopy.request(
                surfaceView,
                dest,
                PixelCopy.OnPixelCopyFinishedListener { result -> copied(job, result, dest, handler) },
                handler,
            )
        } catch (e: RuntimeException) {
            // The surface went away between the check and the copy.
            copying = false
            answer(job, FAILED, null, null)
            next(handler)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun copied(job: Job, result: Int, dest: Bitmap, handler: Handler) {
        copying = false
        if (job.gen == generation) {
            if (result == COPY_SUCCESS) {
                try {
                    val signature = copySignature(dest)
                    val looks = blurred(job, dest)
                    answer(job, COPY_SUCCESS, looks[0], looks[1], signature)
                } catch (e: RuntimeException) {
                    answer(job, FAILED, null, null)
                } catch (e: OutOfMemoryError) {
                    answer(job, FAILED, null, null)
                }
            } else {
                answer(job, result, null, null)
            }
        }
        next(handler)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun next(handler: Handler) {
        val job = queued ?: return
        queued = null
        copy(job, handler)
    }

    private fun answer(job: Job, code: Int, panel: Bitmap?, control: Bitmap?, signature: IntArray? = null) {
        main.post { onResult(job, code, panel, control, signature) }
    }

    /** Back on the main thread with a copy, or why there's none. */
    private fun onResult(job: Job, code: Int, panel: Bitmap?, control: Bitmap?, signature: IntArray?) {
        if (!started || job.gen != generation) return
        // An answer the watchdog already gave up on leaves the newer copy waiting.
        if (job.id == requestId) {
            inFlight = false
            main.removeCallbacks(watchdog)
        }
        when (code) {
            COPY_SUCCESS -> {
                noDataTries = 0
                stuckCopies = 0
                if (panel != null && control != null && job.serial == changeSerial) {
                    store(job, panel, control, signature)
                } else if (anyVisible()) {
                    // Taken before the picture changed: take it again.
                    scheduleCapture(0L)
                }
            }
            COPY_NO_DATA -> {
                // Nothing shown yet (before the first frame, or just after the player was rebuilt): a few tries.
                if (++noDataTries <= NO_DATA_TRIES) scheduleCapture(NO_DATA_RETRY_MS) else blocked = true
            }
            COPY_SOURCE_INVALID -> waitingForSurface = true
            NO_SURFACE -> {
                if (surfaceView.holder.surface?.isValid == true) scheduleCapture(NO_DATA_RETRY_MS) else waitingForSurface = true
            }
            // A timeout held up the TV's drawing for half a second: never again for this player.
            COPY_TIMEOUT, FAILED -> disable()
            else -> if (++unknownErrors >= 2) disable()
        }
    }

    private fun store(job: Job, panel: Bitmap, control: Bitmap, signature: IntArray?) {
        val now = SystemClock.uptimeMillis()
        // The new film fades in over what glass shows now; over the dark fill if the old one wasn't shown.
        val shown = drawableAt(now)
        // A copy landing while the last one is still fading in (after a cut) starts from the blend on screen,
        // not from the newer picture alone, so the glass never jumps.
        val f = fadeFraction()
        for (i in frames.indices) {
            val from = fadingFrom[i]
            val to = frames[i]
            fadingFrom[i] = when {
                !shown -> null
                f < 1f && from != null && to != null -> blend(from, to, f) ?: to
                else -> to
            }
        }
        frames[Kind.PANEL.ordinal] = panel
        frames[Kind.CONTROL.ordinal] = control
        screenScale = job.kx
        screenScaleY = job.ky
        originX = job.ox
        originY = job.oy
        capturedAt = job.at
        valid = true
        holdUntil = 0L
        blocked = false
        fadeStart = now
        fastFade = job.cut
        shownSignature = signature
        scheduleExpiry()
        if (playing) startTicking(now)
        invalidateAll()
    }

    private var blendA = IntArray(0)
    private var blendB = IntArray(0)

    /** [from] faded [f] of the way to [to], as a new picture (copies are small: a few milliseconds, only after a cut). */
    private fun blend(from: Bitmap, to: Bitmap, f: Float): Bitmap? {
        val w = to.width
        val h = to.height
        if (from.width != w || from.height != h) return null
        val n = w * h
        if (blendA.size < n) {
            blendA = IntArray(n)
            blendB = IntArray(n)
        }
        from.getPixels(blendA, 0, w, 0, 0, w, h)
        to.getPixels(blendB, 0, w, 0, 0, w, h)
        val t = (f * 256f).toInt().coerceIn(0, 256)
        for (i in 0 until n) {
            val a = blendA[i]
            val b = blendB[i]
            val r = ((a shr 16) and 0xFF) + ((((b shr 16) and 0xFF) - ((a shr 16) and 0xFF)) * t shr 8)
            val g = ((a shr 8) and 0xFF) + ((((b shr 8) and 0xFF) - ((a shr 8) and 0xFF)) * t shr 8)
            val bl = (a and 0xFF) + (((b and 0xFF) - (a and 0xFF)) * t shr 8)
            blendA[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(blendA, 0, w, 0, 0, w, h)
        return out
    }

    // ---- Cut probe (worker thread) ---------------------------------------

    /** A tiny copy, one at a time and never beside a full copy, compared with [ref] once it arrives. */
    @RequiresApi(Build.VERSION_CODES.N)
    private fun probe(gen: Int, ref: IntArray, handler: Handler) {
        // Asked for before stop(): the main thread has already forgotten it.
        if (gen != generation) return
        if (probing || copying) {
            reply(gen, PROBE_BUSY, ref)
            return
        }
        val surface = surfaceView.holder.surface
        if (surface == null || !surface.isValid) {
            reply(gen, NO_SURFACE, ref)
            return
        }
        try {
            val dest = probeBitmap
                ?: Bitmap.createBitmap(PROBE_WIDTH, PROBE_HEIGHT, Bitmap.Config.ARGB_8888).also { probeBitmap = it }
            val listener = probeListener as? PixelCopy.OnPixelCopyFinishedListener
                ?: PixelCopy.OnPixelCopyFinishedListener { result -> probed(result) }.also { probeListener = it }
            probing = true
            probeGen = gen
            probeRef = ref
            PixelCopy.request(surfaceView, dest, listener, handler)
        } catch (e: RuntimeException) {
            // The surface went away between the check and the copy.
            probing = false
            probeRef = null
            reply(gen, FAILED, ref)
        } catch (e: OutOfMemoryError) {
            probing = false
            probeRef = null
            reply(gen, FAILED, ref)
        }
    }

    private fun probed(result: Int) {
        probing = false
        val ref = probeRef ?: return
        probeRef = null
        val gen = probeGen
        if (gen != generation) return
        var code = result
        val dest = probeBitmap
        if (result == COPY_SUCCESS && dest != null) {
            code = try {
                dest.getPixels(samples, 0, PROBE_WIDTH, 0, 0, PROBE_WIDTH, PROBE_HEIGHT)
                cellSums(samples, probeSignature)
                if (difference(probeSignature, ref) >= CUT_THRESHOLD) PROBE_CUT else COPY_SUCCESS
            } catch (e: RuntimeException) {
                FAILED
            }
        }
        reply(gen, code, ref)
    }

    private fun reply(gen: Int, code: Int, ref: IntArray) {
        main.obtainMessage(MSG_PROBED, gen, code, ref).sendToTarget()
    }

    /**
     * What a probe would have seen of a full copy: each of its 12 x 12 blocks sampled in the middle, as PixelCopy
     * samples the film for a probe. An average of the whole block would differ from a probe on busy film.
     */
    private fun copySignature(copy: Bitmap): IntArray {
        val stepX = COPY_WIDTH / PROBE_WIDTH
        val stepY = COPY_HEIGHT / PROBE_HEIGHT
        val midX = stepX / 2 - 1
        for (y in 0 until PROBE_HEIGHT) {
            copy.getPixels(sampleRows, 0, COPY_WIDTH, 0, y * stepY + stepY / 2 - 1, COPY_WIDTH, 2)
            for (x in 0 until PROBE_WIDTH) {
                val i = x * stepX + midX
                samples[y * PROBE_WIDTH + x] = OPAQUE or
                    (mean4(sampleRows, i, 16) shl 16) or (mean4(sampleRows, i, 8) shl 8) or mean4(sampleRows, i, 0)
            }
        }
        // A new one each copy: the main thread keeps it while the copy is shown.
        return IntArray(SIGNATURE_SIZE).also { cellSums(samples, it) }
    }

    /** One colour's mean over the 2 x 2 pixels at [i] of [sampleRows]' two rows, rounded. */
    private fun mean4(rows: IntArray, i: Int, shift: Int): Int {
        val j = COPY_WIDTH + i
        val sum = ((rows[i] shr shift) and 0xFF) + ((rows[i + 1] shr shift) and 0xFF) +
            ((rows[j] shr shift) and 0xFF) + ((rows[j + 1] shr shift) and 0xFF)
        return (sum + 2) shr 2
    }

    /** The red, green and blue sums of each grid cell of a probe-sized picture. */
    private fun cellSums(px: IntArray, out: IntArray) {
        out.fill(0)
        for (y in 0 until PROBE_HEIGHT) {
            val cellRow = (y / CELL_HEIGHT) * GRID_WIDTH
            val row = y * PROBE_WIDTH
            for (x in 0 until PROBE_WIDTH) {
                val c = px[row + x]
                val k = (cellRow + x / CELL_WIDTH) * 3
                out[k] += (c shr 16) and 0xFF
                out[k + 1] += (c shr 8) and 0xFF
                out[k + 2] += c and 0xFF
            }
        }
    }

    /** The mean difference of the cells' colours, from 0 (the same) to 1 (black against white). */
    private fun difference(a: IntArray, b: IntArray): Float {
        var sum = 0
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum / (a.size * CELL_WIDTH * CELL_HEIGHT * 255f)
    }

    // ---- Blur (worker thread) --------------------------------------------

    /** Lays the copy where the film is on screen, over black like the bars, then makes both looks from it. */
    private fun blurred(job: Job, copy: Bitmap): Array<Bitmap> {
        val bw = job.bw
        val bh = job.bh
        val n = bw * bh
        val bitmap = composite?.takeIf { it.width == bw && it.height == bh }
            ?: Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also {
                composite = it
                compositeCanvas = Canvas(it)
            }
        val canvas = compositeCanvas ?: Canvas(bitmap).also { compositeCanvas = it }
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(copy, null, RectF(job.left, job.top, job.right, job.bottom), compositePaint)
        if (pixels.size != n) {
            pixels = IntArray(n)
            work = IntArray(n)
            temp = IntArray(n)
        }
        bitmap.getPixels(pixels, 0, bw, 0, 0, bw, bh)
        val panel = look(job, n, job.panelSigma, PANEL_SATURATION, IntArray(bh) { PANEL_BRIGHTNESS }, knee = false)
        val control = look(job, n, job.controlSigma, CONTROL_SATURATION, controlGains(job), knee = true)
        // New bitmaps every time: the old ones may still be on screen. They're left to the garbage collector.
        return arrayOf(panel, control)
    }

    private fun look(job: Job, n: Int, sigma: Float, saturation: Int, gains: IntArray, knee: Boolean): Bitmap {
        System.arraycopy(pixels, 0, work, 0, n)
        blur(work, temp, job.bw, job.bh, sigma)
        tone(work, job.bw, job.bh, saturation, gains, knee)
        return Bitmap.createBitmap(work, 0, job.bw, job.bw, job.bh, Bitmap.Config.ARGB_8888)
    }

    /**
     * Brightness per row for the controls, with the shading under them (scrim_top, scrim_bottom) baked in:
     * the controls sit on the dark bottom shading, and plain film would make them twice as bright as around them.
     */
    private fun controlGains(job: Job): IntArray {
        val from = job.height - job.bottomScrim
        return IntArray(job.bh) { y ->
            val sy = (y + 0.5f) * job.ky
            val top = if (sy < job.topScrim) TOP_SCRIM_ALPHA * (1f - sy / job.topScrim) else 0f
            val bottom = if (sy > from) BOTTOM_SCRIM_ALPHA * ((sy - from) / job.bottomScrim).coerceAtMost(1f) else 0f
            ((1f - top) * (1f - bottom) * CONTROL_BRIGHTNESS * 1024f).roundToInt()
        }
    }

    /** A Gaussian blur made of three box blurs ("boxes for Gauss", Kutskir), with the edges repeated outwards. */
    private fun blur(px: IntArray, tmp: IntArray, w: Int, h: Int, sigma: Float) {
        if (sigma < 0.5f) return
        val passes = 3
        val ideal = sqrt(12.0 * sigma * sigma / passes + 1.0)
        var wl = floor(ideal).toInt()
        if (wl % 2 == 0) wl--
        val wu = wl + 2
        val m = ((12.0 * sigma * sigma - passes * wl * wl - 4.0 * passes * wl - 3.0 * passes) / (-4.0 * wl - 4.0)).roundToInt()
        for (i in 0 until passes) {
            val r = ((if (i < m) wl else wu) - 1) / 2
            if (r > 0) {
                boxAcross(px, tmp, w, h, r)
                boxDown(tmp, px, w, h, r)
            }
        }
    }

    private fun boxAcross(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val mul = (ONE + (2 * r + 1) / 2) / (2 * r + 1)
        val last = w - 1
        for (y in 0 until h) {
            val row = y * w
            var sr = 0
            var sg = 0
            var sb = 0
            for (k in -r..r) {
                val c = src[row + (if (k < 0) 0 else if (k > last) last else k)]
                sr += (c shr 16) and 0xFF
                sg += (c shr 8) and 0xFF
                sb += c and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = OPAQUE or (((sr * mul + HALF) ushr 20) shl 16) or
                    (((sg * mul + HALF) ushr 20) shl 8) or ((sb * mul + HALF) ushr 20)
                val add = x + r + 1
                val sub = x - r
                val a = src[row + (if (add > last) last else add)]
                val s = src[row + (if (sub < 0) 0 else sub)]
                sr += ((a shr 16) and 0xFF) - ((s shr 16) and 0xFF)
                sg += ((a shr 8) and 0xFF) - ((s shr 8) and 0xFF)
                sb += (a and 0xFF) - (s and 0xFF)
            }
        }
    }

    private fun boxDown(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val mul = (ONE + (2 * r + 1) / 2) / (2 * r + 1)
        val last = h - 1
        for (x in 0 until w) {
            var sr = 0
            var sg = 0
            var sb = 0
            for (k in -r..r) {
                val c = src[(if (k < 0) 0 else if (k > last) last else k) * w + x]
                sr += (c shr 16) and 0xFF
                sg += (c shr 8) and 0xFF
                sb += c and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = OPAQUE or (((sr * mul + HALF) ushr 20) shl 16) or
                    (((sg * mul + HALF) ushr 20) shl 8) or ((sb * mul + HALF) ushr 20)
                val add = y + r + 1
                val sub = y - r
                val a = src[(if (add > last) last else add) * w + x]
                val s = src[(if (sub < 0) 0 else sub) * w + x]
                sr += ((a shr 16) and 0xFF) - ((s shr 16) and 0xFF)
                sg += ((a shr 8) and 0xFF) - ((s shr 8) and 0xFF)
                sb += (a and 0xFF) - (s and 0xFF)
            }
        }
    }

    /**
     * Saturation as ColorMatrix.setSaturation does it (the same weights as CSS saturate), then brightness per row,
     * all in 1/1024ths. With [knee], light above 62% is pressed down so white icons stay readable on bright film.
     */
    private fun tone(px: IntArray, w: Int, h: Int, saturation: Int, gains: IntArray, knee: Boolean) {
        for (y in 0 until h) {
            val gain = gains[y]
            var i = y * w
            val end = i + w
            while (i < end) {
                val c = px[i]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val l = (r * LUMA_R + g * LUMA_G + b * LUMA_B) shr 10
                var nr = (max(0, ((l shl 10) + (r - l) * saturation) shr 10) * gain) shr 10
                var ng = (max(0, ((l shl 10) + (g - l) * saturation) shr 10) * gain) shr 10
                var nb = (max(0, ((l shl 10) + (b - l) * saturation) shr 10) * gain) shr 10
                if (knee) {
                    val nl = (nr * LUMA_R + ng * LUMA_G + nb * LUMA_B) shr 10
                    if (nl > KNEE) {
                        val target = KNEE + (((nl - KNEE) * KNEE_SLOPE) shr 10)
                        nr = nr * target / nl
                        ng = ng * target / nl
                        nb = nb * target / nl
                    }
                }
                px[i] = OPAQUE or (min(nr, 255) shl 16) or (min(ng, 255) shl 8) or min(nb, 255)
                i++
            }
        }
    }

    private companion object {
        const val TICK_SLACK_MS = 30L
        /** The share of the gap between copies that a new one takes to fade in. */
        const val FADE_SHARE = 0.9f
        /** Older than this while playing, a copy isn't shown: it would be another scene. */
        const val STALE_MS = 2_000L
        const val MIN_GAP_MS = 100L
        const val CHANGE_DELAY_MS = 150L
        const val HOLD_MS = 1_000L
        const val NO_DATA_RETRY_MS = 500L
        const val NO_DATA_TRIES = 3
        const val WATCHDOG_MS = 5_000L

        /**
         * How often a probe looks for a cut while the film plays: soon enough that the glass changes with the new
         * shot, and cheap, as a probe is only PROBE_WIDTH x PROBE_HEIGHT pixels with no blur.
         */
        const val PROBE_MS = 100L
        /** No probe when a copy is due this soon: that copy shows the cut anyway. */
        const val PROBE_LEAD_MS = 60L
        /**
         * How far the cells' colours must move, as a share of full range, to count as a cut. A pan or someone
         * walking through moves these coarse averages a few percent between copies; a new shot or a big flash moves
         * them well past this. A cut it misses is between shots alike enough that the blurred glass barely changes.
         */
        const val CUT_THRESHOLD = 0.12f
        /** A copy after a cut fades in this fast: the slow fade would dissolve the old shot into the new one. */
        const val CUT_FADE_MS = 160L
        /** After this many probes in a row fail, none for this player; the regular copies carry on. */
        const val PROBE_FAILURES = 3

        /** A probe's size: each pixel is exactly a 12 x 12 block of a full copy, so the two compare like for like. */
        const val PROBE_WIDTH = 32
        const val PROBE_HEIGHT = 18
        /** Compared as 8 x 6 cells of 4 x 3 probe pixels: coarse, so motion inside a cell averages out. */
        const val GRID_WIDTH = 8
        const val GRID_HEIGHT = 6
        const val CELL_WIDTH = PROBE_WIDTH / GRID_WIDTH
        const val CELL_HEIGHT = PROBE_HEIGHT / GRID_HEIGHT
        const val SIGNATURE_SIZE = GRID_WIDTH * GRID_HEIGHT * 3

        const val MSG_PROBE = 1
        const val MSG_PROBED = 2

        /** Copied a little larger than the blurred film, then shrunk smoothly: straight from 4K it would sparkle. */
        const val COPY_WIDTH = 384
        const val COPY_HEIGHT = 216

        // PixelCopy's results (its own constants need Android 7), and two of ours.
        const val COPY_SUCCESS = 0
        const val COPY_TIMEOUT = 2
        const val COPY_NO_DATA = 3
        const val COPY_SOURCE_INVALID = 4
        const val NO_SURFACE = 100
        const val FAILED = 101
        const val PROBE_CUT = 102
        const val PROBE_BUSY = 103

        const val PANEL_BLUR_DP = 20f
        const val CONTROL_BLUR_DP = 11f
        const val PANEL_SATURATION = 1843 // 1.8
        const val PANEL_BRIGHTNESS = 870 // 0.85
        const val CONTROL_SATURATION = 1946 // 1.9
        const val CONTROL_BRIGHTNESS = 1.08f
        const val KNEE = 158 // 0.62
        const val KNEE_SLOPE = 358 // 0.35
        const val LUMA_R = 218 // 0.213
        const val LUMA_G = 732 // 0.715
        const val LUMA_B = 74 // 0.072

        // The player's shading (scrim_top, scrim_bottom in activity_player.xml).
        const val TOP_SCRIM_DP = 105f
        const val TOP_SCRIM_ALPHA = 0xA6 / 255f
        const val BOTTOM_SCRIM_DP = 195f
        const val BOTTOM_SCRIM_ALPHA = 0xDE / 255f

        const val OPAQUE = -0x1000000
        const val ONE = 1048576 // 1 shl 20
        const val HALF = 524288 // 1 shl 19
    }
}
