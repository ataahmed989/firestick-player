package app.fireplayer.player

import android.util.Log
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * One film read with up to two HTTP range requests at a time ("Faster downloads (2 connections)", Off by default).
 *
 * A debrid server holds one connection to a speed below what the line carries (65-82 Mb/s measured on a 122 Mb/s line),
 * while a 4K remux needs about 69 Mb/s and more in busy scenes. Two requests for two different parts of the file each
 * get that speed. The film is cut into segments of [Config.segmentBytes] counted from where a connection opens: the
 * first request reads the even ones straight into the player, as before; the second fetches the odd ones ahead into
 * memory, and the player is handed every byte in order.
 *
 * - The bytes the player waits for always come first: a connection (and so every jump) starts with the first request
 *   alone, at the very byte wanted; the second only starts once [Config.startAfterBytes] have come, and only ever
 *   reads ahead.
 * - Memory: at most [Config.buffers] segments are held, [Config.maxExtraBytes] in all (16 MiB), made once per player
 *   and used again; a segment is given back the moment its last byte is handed on, and all of them when two requests
 *   are given up.
 * - A jump or a close cuts the second request off at once and drops what it had fetched.
 * - A film standing still (a long pause) gives the segments back without giving two requests up ([rest]): the second
 *   request starts again where reading goes on.
 * - One request for the rest of the film ([fallbackFor]) when the server answers 429, 503, 403 or 509 to either request,
 *   stops honouring a range, the second request fails, ends early or goes quiet, or the first request spends more time
 *   waiting for the second than reading ([HelperPace]): two must never be worse than one.
 *
 * A film is told by its size in bytes, so what is decided for it holds whichever of its addresses is asked (the link, or
 * where the provider sent it); another film in the same player (the next episode, another source) starts afresh.
 *
 * This object is one player's: its data sources share it. The log (tag [TAG]) never has a link, a host or a header:
 * only the status, sizes and reasons made here.
 */
class TwoRanges(val config: Config = Config(), private val log: (String) -> Unit = { Log.i(TAG, it) }) {

    class Config(
        /** One segment; also what one buffer holds. */
        val segmentBytes: Int = 8 shl 20,
        /** Segments the second request may hold in memory at once. */
        val buffers: Int = 2,
        /** The second request starts once the first has brought this much on a connection. */
        val startAfterBytes: Long = 1L shl 20,
        /** Smaller files stay on one request (subtitle files; and a small film has no need). */
        val minFileBytes: Long = 64L shl 20,
        /** The first request waiting this long for the second with nothing coming gives two requests up. */
        val stallMs: Long = 4_000,
        /** Over this much loading time the waiting is weighed against the reading ([HelperPace]). */
        val paceWindowNs: Long = 8_000_000_000L,
    ) {
        /** The most memory two requests hold beyond one. */
        val maxExtraBytes: Long get() = segmentBytes.toLong() * buffers
    }

    /** What a server answered to a request: its HTTP status, its Content-Range header, and the body's length (-1: not said). */
    class Answer(val status: Int, val contentRange: String?, val length: Long)

    /** One HTTP request for a part of the film. */
    interface Request {
        /**
         * Asks for [length] bytes from [start] (to the file's end when below 0). [fresh] goes to the link as given;
         * otherwise to where the last fresh request was answered from. [probe] sends a Range header even from byte 0.
         */
        @Throws(IOException::class)
        fun open(start: Long, length: Long, fresh: Boolean, probe: Boolean): Answer

        @Throws(IOException::class)
        fun read(buffer: ByteArray, offset: Int, length: Int): Int

        @Throws(IOException::class)
        fun close()

        /** From any thread: a read in progress ends at once. */
        fun cancel() {}

        /** The HTTP status [e] stands for, or -1. */
        fun statusOf(e: IOException): Int = -1
    }

    /** What is known of a link: whether it's read with two requests, its size, and why not. */
    class Link(val two: Boolean, val total: Long, val why: String)

    /** "Faster downloads" as it is set now: turned off while a film plays, its next connection is one request again. */
    @Volatile var enabled = true

    // Why a film (by its size) is on one request for good. Read anywhere; changed under poolLock.
    private val gaveUp = ConcurrentHashMap<Long, String>()

    // The film last opened (not a small file beside it): what the log's mode is about.
    @Volatile private var current: Link? = null

    /** Why the film of [total] bytes is on one request from here on; null while two are allowed. */
    fun fallbackFor(total: Long): String? = gaveUp[total]

    /** The same for the film last opened. */
    val fallback: String? get() = current?.let { fallbackFor(it.total) }

    /** Takes a count of bytes without boxing it: called for every read of the second request. */
    fun interface ByteSink {
        fun add(bytes: Int)
    }

    /** Told of every byte the second request brings, for the download speed ([ByteMeter]); on its thread. */
    @Volatile var onHelperBytes: ByteSink? = null

    // Connections reading with two requests right now, whatever the setting has become since they opened.
    private val twoOpen = AtomicInteger()

    /** Whether a connection open now reads with two requests: one opened before the setting was turned off still does. */
    val twoNow: Boolean get() = twoOpen.get() > 0

    internal fun twoOpened() {
        twoOpen.incrementAndGet()
    }

    internal fun twoClosed() {
        twoOpen.decrementAndGet()
    }

    private val links = HashMap<String, Link>()
    private val free = ArrayList<ByteArray>()
    private var made = 0
    // The player is gone: no segment memory is kept or made again ([release]).
    private var released = false
    // The film stands still ([rest]): segments coming back are let go, until a second request asks for one again.
    private var resting = false
    private val poolLock = Object()
    // The second requests running, to cut them off from another thread ([rest]). Under its own lock.
    private val running = ArrayList<HelperRun>()

    private val primaryBytes = AtomicLong()
    private val primaryNs = AtomicLong()
    private val helperBytes = AtomicLong()
    private val helperNs = AtomicLong()
    private val waitNs = AtomicLong()
    private val reopens = AtomicLong()
    private val reopenNs = AtomicLong()

    internal fun link(key: String): Link? = synchronized(links) { links[key] }

    /** A connection to [link] opens. */
    internal fun opened(link: Link) {
        if (link.why != SMALL_FILE) current = link
    }

    /** The first answer from [key]: logged, and kept. */
    internal fun probed(key: String, status: Int, verdict: RangeCheck.Verdict): Link {
        val link = when {
            !verdict.honoured -> Link(false, verdict.total, "ranges ignored (${verdict.reason})")
            verdict.total < config.minFileBytes -> Link(false, verdict.total, SMALL_FILE)
            else -> Link(true, verdict.total, "")
        }
        synchronized(links) {
            if (links.size >= MAX_LINKS) links.clear()
            links[key] = link
        }
        log("ranges status=$status match=${if (verdict.honoured) "yes" else "no"} total=${verdict.total}" +
            if (link.two) " two requests" else " one request: ${link.why}")
        return link
    }

    /** One request from here on for the film of [total] bytes, for [reason]: the first reason stays. The segments' memory is let go. */
    internal fun fallBack(total: Long, reason: String) {
        synchronized(poolLock) {
            if (gaveUp.containsKey(total)) return
            gaveUp[total] = reason
            made -= free.size
            free.clear()
            poolLock.notifyAll()
        }
        log("fallback to one request: $reason")
    }

    /** A segment's memory for [run], waiting for one to come back; null once [run] is cancelled or two requests are given up. */
    @Throws(InterruptedException::class)
    internal fun take(run: HelperRun): ByteArray? {
        synchronized(poolLock) {
            while (!run.cancelled && !released && !gaveUp.containsKey(run.total)) {
                resting = false
                if (free.isNotEmpty()) return free.removeAt(free.size - 1)
                if (made < config.buffers) {
                    val buffer = ByteArray(config.segmentBytes)
                    made++
                    return buffer
                }
                poolLock.wait(100)
            }
        }
        return null
    }

    internal fun put(buffer: ByteArray, total: Long) = synchronized(poolLock) {
        // Given up, the player is gone, or the film stands still: nothing is kept for a second request that won't come.
        if (released || resting || gaveUp.containsKey(total)) made-- else free.add(buffer)
        poolLock.notifyAll()
    }

    internal fun started(run: HelperRun) {
        synchronized(running) { running.add(run) }
    }

    internal fun ended(run: HelperRun) {
        synchronized(running) { running.remove(run) }
    }

    /**
     * The film stands still (a long pause): the second request is cut off and the segments' memory let go, the ones in
     * the pool now and the ones it holds as its thread ends. Two requests are not given up: the connection starts a
     * second request again at the segment reading has reached ([TwoRangeStream]), and memory is made again then. From
     * any thread; nothing to do with one request.
     */
    fun rest() {
        val runs = synchronized(running) { ArrayList(running) }
        // Cut off first: a run about to take a segment sees it and takes none.
        for (run in runs) run.rest()
        synchronized(poolLock) {
            if (released) return
            resting = true
            made -= free.size
            free.clear()
            poolLock.notifyAll()
        }
        if (runs.isNotEmpty()) log("second request rested")
    }

    /**
     * The player is let go of: the segments' memory waiting in the pool goes now, and any still with a second request
     * goes as that request's thread ends ([put]). Nothing is made again.
     */
    fun release() = synchronized(poolLock) {
        released = true
        made -= free.size
        free.clear()
        poolLock.notifyAll()
    }

    /** Segments' memory made and not let go, in bytes: never more than [Config.maxExtraBytes]. */
    val heldBytes: Long get() = synchronized(poolLock) { made.toLong() * config.segmentBytes }

    internal fun primaryRead(bytes: Int, ns: Long) {
        primaryBytes.addAndGet(bytes.toLong())
        primaryNs.addAndGet(ns)
    }

    internal fun primaryReopened(ns: Long) {
        primaryNs.addAndGet(ns)
        reopens.incrementAndGet()
        reopenNs.addAndGet(ns)
    }

    internal fun helperRead(bytes: Int) {
        helperBytes.addAndGet(bytes.toLong())
        onHelperBytes?.add(bytes)
    }

    internal fun helperTime(ns: Long) {
        helperNs.addAndGet(ns)
    }

    internal fun waited(ns: Long) {
        waitNs.addAndGet(ns)
    }

    /**
     * For the film last opened: "2", "1(fallback: http 429)", "1(ranges ignored (status 200))", "1(turned off)",
     * "2(turning off)" while a connection opened before the setting was turned off still reads with two, or
     * "2(not started)" before its first answer.
     */
    fun mode(): String {
        if (!enabled) return if (twoNow) "2(turning off)" else "1(turned off)"
        val film = current ?: return "2(not started)"
        fallbackFor(film.total)?.let { return "1(fallback: $it)" }
        return if (film.two) "2" else "1(${film.why})"
    }

    /**
     * Each request's own speed while it worked, the share of the first one's time spent waiting for the second, and
     * what asking for the next segment cost it: "r1=55.1Mb/s r2=53.9Mb/s wait=3% reopen=41ms/212". Empty before two
     * requests ran.
     */
    fun rates(): String {
        val hb = helperBytes.get()
        if (hb <= 0L) return ""
        val pn = primaryNs.get()
        val wn = waitNs.get()
        val n = reopens.get()
        return "r1=" + mbps(primaryBytes.get(), pn) + " r2=" + mbps(hb, helperNs.get()) +
            " wait=" + (if (pn + wn <= 0L) 0 else wn * 100 / (pn + wn)) + "%" +
            " reopen=" + (if (n <= 0L) 0 else reopenNs.get() / n / 1_000_000L) + "ms/" + n
    }

    private fun mbps(bytes: Long, ns: Long): String =
        String.format(Locale.US, "%.1fMb/s", if (ns <= 0L) 0.0 else bytes * 8_000.0 / ns)

    companion object {
        const val TAG = "FirePlayerBench"
        /** The statuses a server turns a request away with when there are too many, or the link's allowance is spent. */
        val LIMIT_STATUSES = setOf(429, 503, 403, 509)
        private const val MAX_LINKS = 16
        private const val SMALL_FILE = "small file"

        /** Whether [e] is a read cut short on purpose (a jump, a close), not a failure. */
        fun cancelled(e: IOException): Boolean {
            var c: Throwable? = e
            while (c != null) {
                if (c is InterruptedIOException && c !is SocketTimeoutException) return true
                c = c.cause
            }
            return false
        }
    }
}

/** Whether a server's answer to a range request gave the range asked for. */
object RangeCheck {

    /** [honoured]: exactly the bytes asked for are coming; [total]: the file's size (-1 when not said); [reason] when not. */
    class Verdict(val honoured: Boolean, val total: Long, val reason: String)

    private val CONTENT_RANGE = Regex("""^bytes\s+(\d+)\s*-\s*(\d+)\s*/\s*(\d+|\*)$""", RegexOption.IGNORE_CASE)

    /**
     * [first] to [last] was asked for ([last] below 0: to the file's end). Only a 206 whose Content-Range names that
     * very range and the file's size will do: a 200 is the whole file from its start, and a server that answers with
     * another range (or none, or a 416) can't be read in two parts.
     */
    fun check(status: Int, contentRange: String?, first: Long, last: Long): Verdict {
        val m = contentRange?.trim()?.let { CONTENT_RANGE.matchEntire(it) }
        val total = m?.groupValues?.get(3)?.toLongOrNull() ?: -1L
        if (status != 206) return Verdict(false, total, "status $status")
        if (m == null) return Verdict(false, -1L, "no Content-Range")
        val from = m.groupValues[1].toLongOrNull() ?: -1L
        val to = m.groupValues[2].toLongOrNull() ?: -1L
        if (total < 0L) return Verdict(false, -1L, "no total length")
        val wanted = if (last < 0L) total - 1 else minOf(last, total - 1)
        if (from != first || to != wanted) return Verdict(false, total, "another range")
        return Verdict(true, total, "")
    }
}

/**
 * The film from [base] (where the connection opened) to [end] in segments of [size]: the even ones are the first
 * request's, the odd ones the second's.
 */
class SegmentPlan(val base: Long, val end: Long, val size: Int) {
    val count: Int get() = if (end <= base) 0 else ((end - base + size - 1) / size).toInt()

    fun segmentOf(position: Long): Int = ((position - base) / size).toInt()

    fun start(segment: Int): Long = base + segment.toLong() * size

    fun endOf(segment: Int): Long = minOf(start(segment) + size, end)

    fun length(segment: Int): Int = (endOf(segment) - start(segment)).toInt()

    fun helpers(segment: Int): Boolean = segment % 2 == 1
}

/**
 * Whether the second request holds the first one back. The first request waits whenever the player reaches a segment
 * the second hasn't finished; while it waits it brings nothing. Reading a segment takes the first request P and the
 * wait after it W: two requests bring two segments in P + W, one request two in 2 P, so two are slower than one as soon
 * as W is more than P. Weighed over [windowNs] of loading at a time.
 */
class HelperPace(private val windowNs: Long) {
    private var readNs = 0L
    private var waitNs = 0L

    fun read(ns: Long) {
        readNs += ns
    }

    /** The first request waited [ns] for the second. True when over a whole window it waited longer than it read. */
    fun waited(ns: Long): Boolean {
        waitNs += ns
        if (readNs + waitNs < windowNs) return false
        val slow = waitNs > readNs
        readNs = 0L
        waitNs = 0L
        return slow
    }
}

/**
 * One connection's bytes in order, from one request or two (see [TwoRanges]). [open], [read] and [close] on the
 * loading thread only, like any data source; the second request runs on a thread of its own ([HelperRun]).
 */
class TwoRangeStream(
    private val film: TwoRanges,
    private val primary: TwoRanges.Request,
    private val helper: () -> TwoRanges.Request,
) {
    private val segment = film.config.segmentBytes
    private var pace = HelperPace(film.config.paceWindowNs)
    private var plain = true
    // Counted in [TwoRanges.twoNow] while this connection reads with two requests.
    private var counted = false
    private var plainLength = -1L
    private var position = 0L
    private var plan = SegmentPlan(0L, 0L, segment)
    private var primaryOpen = false
    private var primaryEnd = 0L
    private var delivered = 0L
    private var run: HelperRun? = null
    // The segment a second request was started again for after a rest, or -1: its wait is the pause's, not weighed.
    private var refetched = -1

    /** Opens at [position] to the file's end; [key] names the link in memory only. Gives the length to come (-1: not said). */
    @Throws(IOException::class)
    fun open(position: Long, key: String): Long {
        run?.cancel()
        run = null
        refetched = -1
        this.position = position
        setPlain()
        primaryOpen = false
        delivered = 0L
        if (!film.enabled) return primary.open(position, -1L, fresh = true, probe = false).length
        val link = film.link(key)
        if (link == null) {
            // The first answer says whether the server gives ranges, and the file's size.
            val answer = primary.open(position, -1L, fresh = true, probe = true)
            val verdict = RangeCheck.check(answer.status, answer.contentRange, position, -1L)
            val known = film.probed(key, answer.status, verdict)
            film.opened(known)
            if (usable(known, position) && answer.length == known.total - position) {
                // This request reads on as the first segment; it's ended there (it asked for the whole rest).
                two(position, known.total)
                primaryOpen = true
                primaryEnd = plan.endOf(0)
            }
            return answer.length
        }
        film.opened(link)
        if (!usable(link, position)) return primary.open(position, -1L, fresh = true, probe = false).length
        two(position, link.total)
        openSegment(first = true)
        return if (plain) plainLength else link.total - position
    }

    // The server gives ranges, the film hasn't been put back on one request, and there's a segment for each.
    private fun usable(link: TwoRanges.Link, position: Long): Boolean =
        link.two && film.fallbackFor(link.total) == null && position >= 0L && link.total - position > segment

    private fun gaveUp(): Boolean = film.fallbackFor(plan.end) != null

    private fun fallBack(reason: String) = film.fallBack(plan.end, reason)

    private fun two(base: Long, end: Long) {
        plain = false
        plan = SegmentPlan(base, end, segment)
        if (!counted) {
            counted = true
            film.twoOpened()
        }
    }

    private fun setPlain() {
        plain = true
        if (counted) {
            counted = false
            film.twoClosed()
        }
    }

    @Throws(IOException::class)
    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (plain) return primary.read(buffer, offset, length)
        if (length == 0) return 0
        if (position >= plan.end) return -1
        val seg = plan.segmentOf(position)
        return if (plan.helpers(seg)) readHelper(seg, buffer, offset, length) else readPrimary(buffer, offset, length)
    }

    private fun readPrimary(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!primaryOpen) {
            if (gaveUp()) goPlain() else openSegment(first = false)
            if (plain) return primary.read(buffer, offset, length)
        }
        val started = System.nanoTime()
        val n = primary.read(buffer, offset, minOf(length.toLong(), primaryEnd - position).toInt())
        if (n < 0) throw EOFException("A range ended early")
        if (n == 0) return 0
        val ns = System.nanoTime() - started
        film.primaryRead(n, ns)
        pace.read(ns)
        position += n
        delivered += n
        if (position >= primaryEnd) {
            // Read to its last byte, so its connection can serve the next request.
            primaryOpen = false
            primary.close()
        }
        // A rested second request (a long pause) starts again as reading goes on.
        val again = run?.rested == true
        if (again) run = null
        if (run == null && delivered >= film.config.startAfterBytes && !gaveUp()) {
            val next = plan.segmentOf(position).let { if (plan.helpers(it)) it else it + 1 }
            if (next < plan.count) {
                startHelper(next)
                if (again) refetched = next
            }
        }
        return n
    }

    private fun readHelper(seg: Int, buffer: ByteArray, offset: Int, length: Int): Int {
        var r = if (gaveUp()) null else current(seg)
        var n = -1
        while (r != null) {
            val asked = r
            try {
                n = asked.read(seg, position, buffer, offset, length)
                break
            } catch (e: HelperGone) {
                // Rested while this read waited: no failure, a new second request takes the segment up.
                if (!asked.rested || gaveUp()) {
                    fallBack(e.reason)
                    break
                }
                r = current(seg)
            } finally {
                // Also the wait that a failure, the film's end or a jump cut short.
                film.waited(asked.lastWaitNs)
            }
        }
        if (r != null && n > 0 && seg != refetched) {
            if (refetched >= 0) {
                // Past the segment fetched again after a rest: the pace is weighed afresh from here.
                refetched = -1
                pace = HelperPace(film.config.paceWindowNs)
            }
            if (pace.waited(r.lastWaitNs)) {
                // It goes on with one request from the next segment; the second stops fetching now.
                fallBack("second request too slow")
                r.cancel()
                run = null
            }
        }
        if (n <= 0) {
            goPlain()
            return primary.read(buffer, offset, length)
        }
        position += n
        delivered += n
        return n
    }

    // The second request serving [seg]: the one running, or a new one from [seg] when there is none or it was rested.
    private fun current(seg: Int): HelperRun {
        val r = run
        if (r != null && !r.rested) return r
        // The wait for a segment fetched again is the pause's, not a slow second request's.
        if (r != null) refetched = seg
        return startHelper(seg)
    }

    private fun startHelper(from: Int): HelperRun = HelperRun(film, helper(), plan, from).also {
        run = it
        it.start()
    }

    /** Asks the first request for the segment at [position]; [first]: as a connection opens, not in the middle of one. */
    private fun openSegment(first: Boolean) {
        val to = plan.endOf(plan.segmentOf(position))
        val started = System.nanoTime()
        val answer = try {
            primary.open(position, to - position, fresh = first, probe = false)
        } catch (e: IOException) {
            if (TwoRanges.cancelled(e)) throw e
            val status = primary.statusOf(e)
            // As a connection opens, any failure is the player's to handle, as with one request (a stale remembered
            // address answering 403 is asked of the provider again, and is no reason to give up two requests). In the
            // middle of one, the film goes on with one request instead.
            if (first) throw e
            closeQuietly()
            fallBack(if (status > 0) "http $status" else "request for the next segment failed")
            goPlain()
            return
        }
        val verdict = RangeCheck.check(answer.status, answer.contentRange, position, to - 1)
        if (!verdict.honoured || verdict.total != plan.end) {
            closeQuietly()
            fallBack("range not given" + if (verdict.honoured) "" else " (${verdict.reason})")
            goPlain()
            return
        }
        if (!first) {
            val ns = System.nanoTime() - started
            film.primaryReopened(ns)
            pace.read(ns)
        }
        primaryOpen = true
        primaryEnd = to
    }

    /** The rest of this connection comes from one request, from [position] on. */
    private fun goPlain() {
        run?.cancel()
        run = null
        if (primaryOpen) closeQuietly()
        primaryOpen = false
        setPlain()
        plainLength = primary.open(position, -1L, fresh = true, probe = false).length
    }

    private fun closeQuietly() {
        try {
            primary.close()
        } catch (_: IOException) {
            // Its connection is gone either way.
        }
    }

    /** A jump or the film's end: the second request is cut off and what it fetched dropped. The first is the caller's to close. */
    fun close() {
        run?.cancel()
        run = null
        primaryOpen = false
        setPlain()
    }
}

/** The second request gave out: [reason] is why the film goes on with one. */
internal class HelperGone(val reason: String) : IOException(reason)

/**
 * The second request of one connection: fetches [plan]'s odd segments from [from] on, one after another into memory,
 * each with a range request of its own, at most [TwoRanges.Config.buffers] ahead of what the player has taken. [read] on the loading
 * thread, [cancel] from it too.
 *
 * A segment's memory is its fetching thread's until the segment is whole (then the reader's, who gives it back) or the
 * thread has ended: a cancelled run's memory goes back as its thread ends, never while it may still write to it.
 */
internal class HelperRun(
    private val film: TwoRanges,
    private val request: TwoRanges.Request,
    private val plan: SegmentPlan,
    private val from: Int = 1,
) : Runnable {

    private class Slot(val segment: Int, val start: Long, val length: Int, val buffer: ByteArray) {
        var filled = 0
    }

    private val lock = Object()
    private val slots = ArrayDeque<Slot>()
    @Volatile var cancelled = false
        private set
    /** Cut off because the film stands still ([TwoRanges.rest]), not for a failure: another takes its place. */
    @Volatile var rested = false
        private set
    private var failed: String? = null
    private var ended = false
    private val thread = Thread(this, "FilmSecondRequest").apply { isDaemon = true }

    /** The film's size: what tells it from another. */
    val total: Long get() = plan.end

    /** How long the last [read] waited for bytes, in ns. */
    var lastWaitNs = 0L
        private set

    fun start() {
        film.started(this)
        thread.start()
    }

    /** [cancel], for a film standing still. From any thread. */
    fun rest() {
        rested = true
        cancel()
    }

    override fun run() {
        try {
            var seg = from
            while (!cancelled && seg < plan.count) {
                val buffer = film.take(this) ?: break
                val slot = Slot(seg, plan.start(seg), plan.length(seg), buffer)
                synchronized(lock) {
                    slots.add(slot)
                    lock.notifyAll()
                }
                if (!fetch(slot)) break
                seg += 2
            }
        } catch (e: OutOfMemoryError) {
            fail("no memory for a segment")
        } catch (e: InterruptedException) {
            // Cancelled while waiting for memory.
        } finally {
            film.ended(this)
            synchronized(lock) {
                ended = true
                if (cancelled || failed != null) drain()
                lock.notifyAll()
            }
        }
    }

    private fun fetch(slot: Slot): Boolean {
        val started = System.nanoTime()
        try {
            val answer = request.open(slot.start, slot.length.toLong(), fresh = false, probe = false)
            if (answer.status in TwoRanges.LIMIT_STATUSES) return fail("http ${answer.status} on the second request")
            val verdict = RangeCheck.check(answer.status, answer.contentRange, slot.start, slot.start + slot.length - 1)
            if (!verdict.honoured) return fail("second request: ${verdict.reason}")
            if (verdict.total != plan.end) return fail("second request: another file size")
            var filled = 0
            while (filled < slot.length) {
                if (cancelled) return false
                val n = request.read(slot.buffer, filled, slot.length - filled)
                if (n < 0) return fail("second request ended early")
                if (n == 0) continue
                filled += n
                film.helperRead(n)
                synchronized(lock) {
                    slot.filled = filled
                    lock.notifyAll()
                }
            }
            return true
        } catch (e: IOException) {
            return if (cancelled) false else fail("second request failed")
        } catch (e: RuntimeException) {
            // On this thread nothing else would catch it and the app would end; on the first request the player makes
            // a load error of the same. Fixed words only: the exception's own may carry the address.
            return if (cancelled) false else fail("second request failed")
        } finally {
            try {
                request.close()
            } catch (_: IOException) {
                // Gone either way.
            } catch (_: RuntimeException) {
                // The same.
            }
            film.helperTime(System.nanoTime() - started)
        }
    }

    private fun fail(reason: String): Boolean {
        synchronized(lock) {
            if (failed == null) failed = reason
            lock.notifyAll()
        }
        if (!cancelled) film.fallBack(plan.end, reason)
        return false
    }

    // Under the lock, with the thread ended: nothing writes to these any more.
    private fun drain() {
        for (slot in slots) film.put(slot.buffer, plan.end)
        slots.clear()
    }

    /**
     * Up to [length] bytes of segment [seg] from [position] into [out], waiting for them to come.
     * Throws [HelperGone] when they won't.
     */
    @Throws(IOException::class)
    fun read(seg: Int, position: Long, out: ByteArray, offset: Int, length: Int): Int {
        lastWaitNs = 0L
        return synchronized(lock) { readLocked(seg, position, out, offset, length) }
    }

    private fun readLocked(seg: Int, position: Long, out: ByteArray, offset: Int, length: Int): Int {
        var waitFrom = 0L
        var progressAt = 0L
        var seen = -2
        try {
            while (true) {
                failed?.let { throw HelperGone(it) }
                val slot = slots.peekFirst()
                if (slot != null && slot.segment != seg) throw HelperGone("segments out of order")
                if (slot != null) {
                    val at = (position - slot.start).toInt()
                    val have = slot.filled - at
                    if (have > 0) {
                        val n = minOf(length, have)
                        System.arraycopy(slot.buffer, at, out, offset, n)
                        if (at + n == slot.length) {
                            // Whole and handed on: its memory is free for the segment after next.
                            slots.removeFirst()
                            film.put(slot.buffer, plan.end)
                        }
                        return n
                    }
                }
                if (ended) throw HelperGone("second request stopped")
                val now = System.nanoTime()
                val filled = slot?.filled ?: -1
                if (waitFrom == 0L) waitFrom = now
                if (filled != seen) {
                    seen = filled
                    progressAt = now
                } else if (now - progressAt >= film.config.stallMs * 1_000_000L) {
                    throw HelperGone("second request stalled")
                }
                try {
                    lock.wait(50)
                } catch (e: InterruptedException) {
                    // The loading thread is being cancelled (a jump): Media3 expects this.
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException()
                }
            }
        } finally {
            // However the wait ended: bytes, a failure, the end, or a jump.
            if (waitFrom != 0L) lastWaitNs = System.nanoTime() - waitFrom
        }
    }

    /** Cuts the request off at once; what it fetched is dropped, its memory given back as its thread ends. */
    fun cancel() {
        synchronized(lock) {
            if (cancelled) return
            cancelled = true
            if (ended) drain()
            lock.notifyAll()
        }
        request.cancel()
        thread.interrupt()
    }

    /** For tests: waits for the fetching thread to end. */
    fun join(ms: Long) = thread.join(ms)
}
