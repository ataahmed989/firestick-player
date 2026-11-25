package app.fireplayer.player

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the film player does to its frames and the Fire TV's memory, in the log under one tag, "FirePlayerDiag", so a
 * choppy film can be told apart afterwards: Media3 dropping late frames, the pacer holding or skipping frames, the
 * decoder never giving them, the film's memory, the system short of memory, or heat.
 *
 * - Events, one line each: the video decoder chosen (and released), the picture's and the sound's format, the film
 *   stopping to load and going on, Media3's batches of dropped frames, and how long the film took to say what tracks
 *   it has ([PrepareClock]).
 * - Once every [LOG_EVERY_S] seconds while the film plays, one line: the frames shown and lost in those seconds and over
 *   the last minute ([FrameTally]), how much is loaded ahead and the memory it holds (of what it fills to), how fast the
 *   film downloads ([DownloadRate]: "down" over all of those seconds, "free" over the ones it loaded with room in the
 *   buffer, "best" the fastest three such seconds in a row so far), the picture, the glass's copies, the TV's refresh
 *   and the HDR it reports, the app's memory (Java heap, native heap, graphics, swapped out; read once a minute), the
 *   system's free memory, and the heat.
 * - 20 s into the film and every minute after, playing or not, the "loader" line: the download's speed while really
 *   loading, pauses left out ([LoaderRate]), what else on the TV downloaded, and whether a VPN carries it ([NetPath]).
 * - With the loader line while the film plays, and once as its player goes, the "bench" line under "FirePlayerBench":
 *   one request against two ([BenchTally], [TwoRanges]).
 *
 * Never a link, a title or a key (links can carry the viewer's Real-Debrid key): only counts, sizes, formats, times
 * and the decoder's name.
 *
 * [second] comes from the player screen's tick once a second, on the main thread, and only adds up counts there. The
 * TV, the memory and the heat are read on a thread of its own, as reading the app's memory takes a while: at most one
 * line at a time, so a slow read can't pile up.
 */
@OptIn(UnstableApi::class)
class PlaybackDiag(context: Context) : AnalyticsListener {

    private val app = context.applicationContext

    /** Given to the film's video renderer, which counts the frames lost that Media3 doesn't ([PlayerFactory.build]). */
    val counts = FrameCounts()

    /** The last minute's frames, kept from [second]. */
    val tally = FrameTally()

    /** The memory the film's loaded part holds now, in bytes: set with each new player ([PlayerFactory.Built.filmBytes]). */
    var filmBytes: () -> Long = NO_BYTES

    /** What the film's buffer fills to now, in bytes ([PlayerFactory.Built.fillBytes]). */
    var fillBytes: () -> Long = NO_BYTES

    /** Given to the film's connection, which counts the bytes that come ([PlayerFactory.build]). */
    val meter = ByteMeter()

    /** How fast the film downloads, kept from [second] (see [DownloadRate]). */
    val download = DownloadRate()

    /** The figures for comparing one request with two, under [TwoRanges.TAG] ([BenchTally]). */
    val bench = BenchTally()

    // This player's two requests ("Faster downloads"); null with the setting off.
    private var ranges: TwoRanges? = null
    private val pssBusy = AtomicBoolean(false)

    /** More for the line from the player screen (the glass's copies, what's kept behind): short, no links or titles. */
    var note: () -> String = { "" }

    // On the main thread.
    private var counters: DecoderCounters? = null
    private var seconds = 0
    private val sinceLog = LongArray(5)
    private var readyOnce = false
    private var waitingSince = 0L
    private var jumpedAt = 0L
    // The video decoder in use, by its name ("OMX.MTK.VIDEO.DECODER.HEVC"). Media3 tells its listeners on the main thread.
    private var decoder = "none"
    // How long the film took to say what tracks it has (the "prepared" line).
    private val prepared = PrepareClock()
    // The second before was one of loading with room in the buffer.
    private var wasFree = false
    // Whether the film was loading at the last second, and how often loading had been held back by then.
    private var wasLoading = false
    private var holdsSeen = 0
    // The loader line ([loaderSecond]): seconds since this player's film started, and since when its figures run.
    private var loaderSeconds = 0
    private var loaderFrom = 0L
    // What the TV and the app had received at the last loader line ([NetPath.received]); on the reader's thread.
    private var receivedBefore: LongArray? = null
    private var receivedAt = 0L

    private val busy = AtomicBoolean(false)
    private var reader: ExecutorService? = null
    // The app's memory for the line (" java=… native=… gfx=… pss=… swap=…"), read every [MEMORY_EVERY_S] seconds and
    // repeated in the lines between, and when it was read (elapsedRealtime, 0: never); on the reader's thread.
    private var memory = ""
    private var memoryAt = 0L

    /** A new player: its first wait to load isn't a stall. [twoRanges]: its two requests, with "Faster downloads" on. */
    fun newPlayer(twoRanges: TwoRanges? = null) {
        ranges = twoRanges
        bench.reset(SystemClock.elapsedRealtime())
        readyOnce = false
        waitingSince = 0L
        counters = null
        decoder = "none"
        download.reset()
        loaderSeconds = 0
        loaderFrom = SystemClock.elapsedRealtime()
        prepared.start(loaderFrom, meter.bytes)
        meter.loader.take()
        onReader {
            receivedBefore = NetPath.received()
            receivedAt = SystemClock.elapsedRealtime()
        }
    }

    /** The player was let go: nothing of it is held on to here. */
    fun playerGone() {
        if (bench.running) {
            // What the loader brought since its last line belongs to this player too.
            val s = meter.loader.take()
            bench.loader(s.countedBytes, s.countedNs, s.allBytes)
            Log.i(TwoRanges.TAG, benchLine(last = true))
            bench.stop()
        }
        ranges = null
        filmBytes = NO_BYTES
        fillBytes = NO_BYTES
    }

    /** Once a second: the frames so far into [tally]; every [LOG_EVERY_S] seconds while [player] plays, the line. */
    fun second(player: ExoPlayer) {
        val c = player.videoDecoderCounters
        if (c != null) {
            c.ensureUpdated()
            val fresh = c !== counters
            counters = c
            tally.sample(
                SystemClock.elapsedRealtime(), c.droppedBufferCount.toLong(), c.renderedOutputBufferCount.toLong(),
                counts.shownTwice, counts.sameRefresh, counts.neverDecoded, fresh,
            )
        } else {
            // No picture being decoded: only the pacer's and gaps' counts move on (they don't).
            tally.sample(SystemClock.elapsedRealtime(), lastDropped(), lastRendered(), counts.shownTwice, counts.sameRefresh,
                counts.neverDecoded)
        }
        for (k in sinceLog.indices) {
            sinceLog[k] += tally.added(k)
            bench.frames(k, tally.added(k))
        }
        bench.ahead(SystemClock.elapsedRealtime(), player.totalBufferedDuration)
        // The app's memory every few seconds, for its peak: on the log's thread, one read at a time.
        if (seconds % PSS_EVERY_S == 0 && pssBusy.compareAndSet(false, true)) {
            val ran = onReader {
                try {
                    bench.pss(Debug.getPss())
                } finally {
                    pssBusy.set(false)
                }
            }
            if (!ran) pssBusy.set(false)
        }
        // Loading freely: the buffer has room, so the server and the line set the speed (see DownloadRate).
        val held = filmBytes()
        val fill = fillBytes()
        val free = player.isLoading && fill > 0L && held < fill * DownloadRate.FREE_BELOW && wasFree
        wasFree = player.isLoading && fill > 0L && held < fill * DownloadRate.FREE_BELOW
        // Flat out: loading the whole second through, never held back by the buffer rules, however full the buffer.
        val holds = meter.loader.heldCount
        val through = player.isLoading && wasLoading && holds == holdsSeen
        wasLoading = player.isLoading
        holdsSeen = holds
        download.sample(SystemClock.elapsedRealtime(), meter.bytes, free,
            (free || through) && player.totalBufferedDuration < DownloadRate.FLAT_OUT_BELOW_MS)
        loaderSecond(player.isPlaying)
        if (++seconds % LOG_EVERY_S != 0) return
        val playing = player.isPlaying
        if (!playing || !Log.isLoggable(TAG, Log.INFO)) {
            sinceLog.fill(0)
            download.take()
            return
        }
        val line = StringBuilder(400)
            .append("play pos=").append(player.currentPosition / 1000).append('s')
            .append(" shown=").append(sinceLog[FrameTally.RENDERED])
            .append(" dropped=").append(sinceLog[FrameTally.DROPPED])
            .append(" twice=").append(sinceLog[FrameTally.TWICE])
            .append(" same=").append(sinceLog[FrameTally.SAME])
            .append(" never=").append(sinceLog[FrameTally.NEVER])
            .append(" min[shown=").append(tally.minute(FrameTally.RENDERED))
            .append(" dropped=").append(tally.minute(FrameTally.DROPPED))
            .append(" twice=").append(tally.minute(FrameTally.TWICE))
            .append(" same=").append(tally.minute(FrameTally.SAME))
            .append(" never=").append(tally.minute(FrameTally.NEVER)).append(']')
            .append(" ahead=").append(player.totalBufferedDuration).append("ms")
            .append(" buf=").append(mb(held)).append('/').append(mb(fill))
            .append(" down=").append(mbps(download.mbps))
            .append(" free=").append(mbps(download.freeMbps)).append('/').append(download.freeSeconds).append('s')
            .append(" best=").append(mbps(download.bestFreeMbps))
            .append(" sustained=").append(mbps(download.sustainedMbps)).append('/').append(download.sustainedSeconds).append('s')
            .append(' ').append(video(player.videoFormat))
            .append(" decoder=").append(decoder)
        note().takeIf { it.isNotEmpty() }?.let { line.append(' ').append(it) }
        sinceLog.fill(0)
        download.take()
        // The rest is read on its own thread; one at a time, a line left out if the one before hasn't finished.
        if (!busy.compareAndSet(false, true)) return
        val ran = onReader {
            try {
                Log.i(TAG, line.append(' ').append(system()).toString())
            } finally {
                busy.set(false)
            }
        }
        if (!ran) busy.set(false)
    }

    /** Runs [task] on the log's own thread; false when it can't (the screen is going). */
    private fun onReader(task: () -> Unit): Boolean {
        val pool = reader ?: Executors.newSingleThreadExecutor { r ->
            Thread(r, "FirePlayerDiag").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }.also { reader = it }
        return try {
            pool.execute(task)
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    /**
     * Once a second: [LOADER_FIRST_S] seconds into the film and every [LOADER_EVERY_S] after, the loader line, so its
     * real speed can be set beside "down=" and "sustained=" ([LoaderRate], THROUGHPUT.md):
     * "loader flat=78.3Mb/s/41s net-wait=93% cpu=31% loading=70%/60s got=512.0MB others=0.4Mb/s vpn=none".
     * flat: Mb/s over the seconds it loaded flat out; net-wait: the share of those spent waiting on the network; cpu: the
     * loading thread's share of one core then; loading: the share of the minute it loaded at all; got: all that came;
     * others: what the rest of the TV downloaded meanwhile; vpn: which way the film goes ([NetPath]).
     */
    private fun loaderSecond(playing: Boolean) {
        val n = ++loaderSeconds
        if (n < LOADER_FIRST_S || (n - LOADER_FIRST_S) % LOADER_EVERY_S != 0) return
        val now = SystemClock.elapsedRealtime()
        val s = meter.loader.take()
        bench.loader(s.countedBytes, s.countedNs, s.allBytes)
        if (playing) Log.i(TwoRanges.TAG, benchLine(last = false))
        val windowMs = now - loaderFrom
        loaderFrom = now
        if (!Log.isLoggable(TAG, Log.INFO)) return
        val line = StringBuilder(200).append("loader flat=")
        if (s.countedNs > 0L) {
            line.append(mbps(s.mbps)).append('/').append((s.countedNs + 500_000_000L) / 1_000_000_000L).append('s')
                .append(" net-wait=").append(s.waitPercent).append('%')
                .append(" cpu=").append(if (s.cpuPercent < 0) "?" else s.cpuPercent.toString() + "%")
        } else {
            // Never loaded flat out: the buffer stayed full (or nothing was loading), so the line wasn't put to the test.
            line.append("none")
        }
        line.append(" loading=").append(s.busyPercent(windowMs).coerceAtLeast(0)).append("%/").append((windowMs + 500) / 1000).append('s')
            .append(" got=").append(mb(s.allBytes))
        onReader {
            val after = NetPath.received()
            val at = SystemClock.elapsedRealtime()
            val others = receivedBefore?.let { NetPath.othersMbps(it, after, at - receivedAt) } ?: -1.0
            receivedBefore = after
            receivedAt = at
            line.append(" others=").append(if (others < 0.0) "?" else mbps(others))
                .append(' ').append(NetPath.vpn(app))
            Log.i(TAG, line.toString())
        }
    }

    private fun benchLine(last: Boolean): String =
        bench.line(SystemClock.elapsedRealtime(), ranges?.mode() ?: "1", ranges?.rates() ?: "", last)

    private fun lastDropped(): Long = counters?.droppedBufferCount?.toLong() ?: 0L
    private fun lastRendered(): Long = counters?.renderedOutputBufferCount?.toLong() ?: 0L

    /**
     * The TV, the app's memory, the system's, and the heat:
     * "tv=23.976Hz tvHdr=none java=270.0MB native=60.0MB gfx=118.0MB pss=448.0MB swap=20.0MB avail=412.0MB low=0 heat=0
     * headroom=0.41". Reading the app's memory has the kernel walk all of it (the more so with some of it swapped out),
     * so it is read once a minute ([MEMORY_EVERY_S]) and the lines between repeat it; the rest is read for each line.
     */
    private fun system(): String {
        val out = StringBuilder(200)
        runCatching {
            val display = (app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
            out.append("tv=").append(String.format(Locale.US, "%.3fHz", display.refreshRate))
            if (Build.VERSION.SDK_INT >= 24) {
                @Suppress("DEPRECATION")
                val types = display.hdrCapabilities?.supportedHdrTypes
                out.append(" tvHdr=").append(hdrTypes(types))
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (memoryDue(memoryAt, now)) {
            memoryAt = now
            memory = appMemory()
        }
        out.append(memory)
        runCatching {
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            out.append(" avail=").append(mb(mi.availMem)).append(" threshold=").append(mb(mi.threshold))
                .append(" low=").append(if (mi.lowMemory) 1 else 0)
        }
        runCatching {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= 29) out.append(" heat=").append(pm.currentThermalStatus)
            if (Build.VERSION.SDK_INT >= 30) {
                val headroom = pm.getThermalHeadroom(HEADROOM_AHEAD_S)
                if (!headroom.isNaN()) out.append(" headroom=").append(String.format(Locale.US, "%.2f", headroom))
            }
        }
        return out.toString().trim()
    }

    /** The app's memory, " java=270.0MB native=60.0MB gfx=118.0MB pss=448.0MB swap=20.0MB" (sizes only); "" when unread. */
    private fun appMemory(): String = runCatching {
        val m = Debug.MemoryInfo()
        Debug.getMemoryInfo(m)
        var javaKb = m.dalvikPss.toLong()
        var nativeKb = m.nativePss.toLong()
        var graphicsKb = -1L
        var swapKb = -1L
        if (Build.VERSION.SDK_INT >= 23) {
            m.getMemoryStat("summary.java-heap")?.toLongOrNull()?.let { javaKb = it }
            m.getMemoryStat("summary.native-heap")?.toLongOrNull()?.let { nativeKb = it }
            m.getMemoryStat("summary.graphics")?.toLongOrNull()?.let { graphicsKb = it }
            m.getMemoryStat("summary.total-swap")?.toLongOrNull()?.let { swapKb = it }
        }
        val out = StringBuilder(80)
        out.append(" java=").append(mb(javaKb * 1024)).append(" native=").append(mb(nativeKb * 1024))
        if (graphicsKb >= 0) out.append(" gfx=").append(mb(graphicsKb * 1024))
        out.append(" pss=").append(mb(m.totalPss.toLong() * 1024))
        if (swapKb >= 0) out.append(" swap=").append(mb(swapKb * 1024))
        bench.pss(m.totalPss.toLong())
        out.toString()
    }.getOrDefault("")

    /** The reader's thread is let go: with the player screen. */
    fun release() {
        reader?.shutdownNow()
        reader = null
    }

    // --- Events

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        decoder = decoderName
        Log.i(TAG, "decoder video $decoderName started in ${initializationDurationMs}ms")
    }

    override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
        if (decoder == decoderName) decoder = "none"
        Log.i(TAG, "decoder video $decoderName released")
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        Log.i(TAG, "format " + video(format) + (format.bitrate.takeIf { it > 0 }?.let { " bitrate=${it / 1000}kb/s" } ?: ""))
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        Log.i(TAG, "format audio=${format.sampleMimeType} channels=${format.channelCount} rate=${format.sampleRate}" +
            (format.codecs?.let { " codecs=$it" } ?: ""))
    }

    override fun onTracksChanged(eventTime: AnalyticsListener.EventTime, tracks: Tracks) {
        prepared.tracks(tracks.isEmpty, SystemClock.elapsedRealtime(), meter.bytes)?.let { Log.i(TAG, it) }
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        Log.i(TAG, "drops $droppedFrames in ${elapsedMs}ms")
    }

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            jumpedAt = SystemClock.elapsedRealtime()
            bench.jumped(jumpedAt)
        }
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        val now = SystemClock.elapsedRealtime()
        when (state) {
            Player.STATE_BUFFERING -> if (readyOnce && waitingSince == 0L) {
                waitingSince = now
                // The wait right after a jump is the jump's, not the line falling behind the film.
                if (now - jumpedAt >= AFTER_JUMP_MS) bench.stallStart(now)
                Log.i(TAG, "rebuffer start pos=${eventTime.currentPlaybackPositionMs / 1000}s " +
                    "ahead=${eventTime.totalBufferedDurationMs}ms" + if (now - jumpedAt < AFTER_JUMP_MS) " after a jump" else "")
            }
            Player.STATE_READY -> {
                if (waitingSince > 0L) Log.i(TAG, "rebuffer end after ${now - waitingSince}ms")
                waitingSince = 0L
                bench.stallEnd(now)
                if (!readyOnce) bench.firstFrame(now)
                readyOnce = true
            }
            else -> {
                waitingSince = 0L
                bench.stallEnd(now)
            }
        }
    }

    companion object {
        const val TAG = "FirePlayerDiag"
        /** Seconds between the lines while the film plays. */
        const val LOG_EVERY_S = 10
        /** The loader line's first, this many seconds after the film's player was built (its first fill), then every minute. */
        const val LOADER_FIRST_S = 20
        const val LOADER_EVERY_S = 60
        /**
         * Seconds between two reads of the app's memory for its peak ([BenchTally.pss]); the line's own read, once a
         * minute ([MEMORY_EVERY_S]), counts towards the peak too. Each read has the kernel walk the app's memory.
         */
        const val PSS_EVERY_S = 30
        /** Seconds between two reads of the app's memory for the line; the lines between repeat the last. */
        const val MEMORY_EVERY_S = 60

        /**
         * Whether the line reads the app's memory again at [now] (ms), last read at [readAt] (0: never). Half a line's
         * gap of slack, so the once-a-second tick's drift never puts it off to the line after.
         */
        fun memoryDue(readAt: Long, now: Long): Boolean =
            readAt == 0L || now - readAt >= MEMORY_EVERY_S * 1000L - LOG_EVERY_S * 500L
        private const val HEADROOM_AHEAD_S = 10
        private const val AFTER_JUMP_MS = 3_000L
        private val NO_BYTES: () -> Long = { 0L }

        private fun mb(bytes: Long): String = String.format(Locale.US, "%.1fMB", bytes / 1_048_576.0)

        private fun mbps(rate: Double): String = String.format(Locale.US, "%.1fMb/s", rate)

        /** The HDR types a TV reports (Display.HdrCapabilities' numbers), "HDR10,HLG"; "none" when it reports none. */
        fun hdrTypes(types: IntArray?): String {
            if (types == null || types.isEmpty()) return "none"
            return types.joinToString(",") {
                when (it) {
                    1 -> "DV"
                    2 -> "HDR10"
                    3 -> "HLG"
                    4 -> "HDR10+"
                    else -> "type$it"
                }
            }
        }

        /** "video=3840x2160@23.976 mime=video/hevc codecs=hvc1.2.4.L153.B0 hdr=HDR10 bits=10": no ids, no labels. */
        fun video(f: Format?): String {
            if (f == null) return "video=none"
            val spec = MediaSpec.of(f, null)
            val fps = if (f.frameRate > 0f) String.format(Locale.US, "@%.3f", f.frameRate) else ""
            return "video=${f.width}x${f.height}$fps mime=${f.sampleMimeType}" +
                (f.codecs?.let { " codecs=$it" } ?: "") +
                " hdr=${spec.hdr ?: "unstated"}" +
                (spec.bitDepth.takeIf { it > 0 }?.let { " bits=$it" } ?: "")
        }
    }
}

/**
 * The "prepared" line: how long a film took to say what tracks it has, from its player being made (or from the tracks
 * last being none, as when the player let the film go and opened it again), and how much had come by then: "prepared
 * +2140ms got=3.1MB". A film whose track list is only met at the end of its file reads all of it first. Once each
 * time; nothing per frame.
 */
internal class PrepareClock {
    private var from = 0L
    private var fromBytes = 0L
    private var waiting = false

    /** A new player: the clock starts at [now], with [bytes] come so far. */
    fun start(now: Long, bytes: Long) {
        from = now
        fromBytes = bytes
        waiting = true
    }

    /** The tracks changed ([empty]: to none): the line, when they're known for the first time since the clock started. */
    fun tracks(empty: Boolean, now: Long, bytes: Long): String? {
        if (empty) {
            if (!waiting) start(now, bytes)
            return null
        }
        if (!waiting) return null
        waiting = false
        return "prepared +${now - from}ms got=" + String.format(Locale.US, "%.1fMB", (bytes - fromBytes) / 1_048_576.0)
    }
}
