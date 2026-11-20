package app.fireplayer.player

import java.io.IOException
import java.io.InterruptedIOException
import java.util.Collections
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TwoRangesTest {

    // --- The range check

    @Test
    fun onlyA206NamingTheRangeAskedForCounts() {
        val ok = RangeCheck.check(206, "bytes 100-999/1000", 100, -1)
        assertTrue(ok.honoured)
        assertEquals(1000L, ok.total)
        assertTrue(RangeCheck.check(206, "bytes 0-999/1000", 0, -1).honoured)
        assertTrue(RangeCheck.check(206, "bytes 100-199/1000", 100, 199).honoured)
        assertTrue(RangeCheck.check(206, "Bytes  100 - 199 / 1000 ", 100, 199).honoured)
        // The file's last segment is shorter than what was asked for.
        assertTrue(RangeCheck.check(206, "bytes 900-999/1000", 900, 1899).honoured)
    }

    @Test
    fun a200AMissingOrAnotherRangeAndA416AreRangesIgnored() {
        val whole = RangeCheck.check(200, null, 100, -1)
        assertFalse(whole.honoured)
        assertEquals("status 200", whole.reason)
        assertFalse(RangeCheck.check(200, "bytes 100-999/1000", 100, -1).honoured)
        assertEquals("no Content-Range", RangeCheck.check(206, null, 100, -1).reason)
        assertEquals("no Content-Range", RangeCheck.check(206, "garbage", 100, -1).reason)
        assertEquals("another range", RangeCheck.check(206, "bytes 0-999/1000", 100, -1).reason)
        assertEquals("another range", RangeCheck.check(206, "bytes 100-150/1000", 100, 199).reason)
        assertEquals("another range", RangeCheck.check(206, "bytes 100-500/1000", 100, -1).reason)
        assertEquals("no total length", RangeCheck.check(206, "bytes 100-199/*", 100, 199).reason)
        val beyond = RangeCheck.check(416, "bytes */1000", 2000, -1)
        assertFalse(beyond.honoured)
        assertEquals("status 416", beyond.reason)
    }

    // --- Segments

    @Test
    fun segmentsRunFromWhereTheConnectionOpensAndTakeTurns() {
        val plan = SegmentPlan(base = 250, end = 3_000, size = 1_000)
        assertEquals(3, plan.count)
        assertEquals(0, plan.segmentOf(250))
        assertEquals(0, plan.segmentOf(1_249))
        assertEquals(1, plan.segmentOf(1_250))
        assertEquals(1_250L, plan.start(1))
        assertEquals(2_250L, plan.endOf(1))
        assertEquals(1_000, plan.length(1))
        // The last one ends with the file.
        assertEquals(3_000L, plan.endOf(2))
        assertEquals(750, plan.length(2))
        assertFalse(plan.helpers(0))
        assertTrue(plan.helpers(1))
        assertFalse(plan.helpers(2))
        assertEquals(1, SegmentPlan(0, 1_000, 1_000).count)
        assertEquals(2, SegmentPlan(0, 1_001, 1_000).count)
        assertEquals(0, SegmentPlan(500, 500, 1_000).count)
    }

    @Test
    fun aSecondRequestThatKeepsTheFirstWaitingLongerThanItReadsIsTooSlow() {
        val pace = HelperPace(windowNs = 1_000)
        pace.read(400)
        // Not a whole window yet.
        assertFalse(pace.waited(300))
        // 400 read, 700 waited.
        assertTrue(pace.waited(400))
        // A new window: mostly reading.
        pace.read(900)
        assertFalse(pace.waited(200))
        // And each window on its own.
        pace.read(100)
        assertFalse(pace.waited(100))
        assertTrue(pace.waited(900))
    }

    // --- The stream

    private class Refused(val status: Int) : IOException("refused")

    /** A server holding [total] bytes of a known pattern, and one request to it. */
    private class Fake(private val total: Long, private val log: MutableList<String>, private val name: String) : TwoRanges.Request {
        var ranges = true
        var contentRange: ((Long, Long) -> String?)? = null
        var statusFor: (Long) -> Int = { 206 }
        var throwOnOpen: (Long) -> IOException? = { null }
        var endEarlyAt = -1L
        @Volatile var hold = false
        var readDelayMs = 0L
        @Volatile var cancelled = false
        @Volatile var closes = 0
        private var at = 0L
        private var end = 0L

        override fun open(start: Long, length: Long, fresh: Boolean, probe: Boolean): TwoRanges.Answer {
            log.add("$name $start+$length" + (if (fresh) " fresh" else "") + if (probe) " probe" else "")
            if (cancelled) throw IOException("cancelled")
            throwOnOpen(start)?.let { throw it }
            val status = statusFor(start)
            if (status != 206) {
                if (name == "first") throw Refused(status)
                return TwoRanges.Answer(status, null, -1)
            }
            at = start
            if (!ranges) {
                // The whole file, read past the start by the connection itself.
                end = total
                return TwoRanges.Answer(200, null, total - start)
            }
            end = if (length < 0) total else minOf(start + length, total)
            val header = contentRange?.let { it(start, end) } ?: "bytes $start-${end - 1}/$total"
            return TwoRanges.Answer(206, header, end - start)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            while (hold && !cancelled) Thread.sleep(2)
            if (cancelled) throw IOException("cancelled")
            if (readDelayMs > 0) Thread.sleep(readDelayMs)
            if (at >= end || (endEarlyAt in 0..at)) return -1
            val n = minOf(length.toLong(), end - at, 137L).toInt()
            for (i in 0 until n) buffer[offset + i] = byteAt(at + i)
            at += n
            return n
        }

        override fun close() {
            closes++
        }

        override fun cancel() {
            cancelled = true
        }

        override fun statusOf(e: IOException): Int = (e as? Refused)?.status ?: -1
    }

    private class Rig(total: Long = 10_500, config: TwoRanges.Config = SMALL) {
        val total = total
        val log: MutableList<String> = Collections.synchronizedList(ArrayList<String>())
        val lines: MutableList<String> = Collections.synchronizedList(ArrayList<String>())
        val film = TwoRanges(config) { lines.add(it) }
        val first = Fake(total, log, "first")
        val helpers: MutableList<Fake> = Collections.synchronizedList(ArrayList<Fake>())
        var onHelper: (Fake) -> Unit = {}
        val stream = TwoRangeStream(film, first) { Fake(total, log, "second").also { onHelper(it); helpers.add(it) } }

        fun readAll(from: Long, step: Int = 333): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(step)
            while (true) {
                val n = stream.read(buffer, 0, step)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
            assertEquals(total - from, out.size().toLong())
            return out.toByteArray()
        }

        fun secondOpens(): List<String> = synchronized(log) { log.filter { it.startsWith("second") } }

        fun firstOpens(): List<String> = synchronized(log) { log.filter { it.startsWith("first") } }
    }

    @Test
    fun everyByteInOrderAcrossSegmentsFromTwoRequests() {
        val rig = Rig()
        assertEquals(10_500L, rig.stream.open(0, "k"))
        assertArrayEquals(expected(0, 10_500), rig.readAll(0))
        rig.stream.close()
        assertEquals("2", rig.film.mode())
        assertNull(rig.film.fallback)
        // The first request: the whole rest once (the check), then its even segments one by one, where it landed.
        assertEquals(
            listOf("first 0+-1 fresh probe", "first 2000+1000", "first 4000+1000", "first 6000+1000", "first 8000+1000", "first 10000+500"),
            rig.firstOpens(),
        )
        // The second: the odd ones, each with a range of its own.
        assertEquals(
            listOf("second 1000+1000", "second 3000+1000", "second 5000+1000", "second 7000+1000", "second 9000+1000"),
            rig.secondOpens(),
        )
        assertTrue(rig.lines.toString(), rig.lines.contains("ranges status=206 match=yes total=10500 two requests"))
        assertTrue(rig.film.heldBytes <= SMALL.maxExtraBytes)
        assertTrue(rig.film.rates().startsWith("r1="))
    }

    @Test
    fun aLaterConnectionStartsWithTheBytesWantedAndABoundedRange() {
        val rig = Rig()
        rig.stream.open(0, "k")
        rig.stream.close()
        assertEquals(10_500L - 2_345, rig.stream.open(2_345, "k"))
        assertArrayEquals(expected(2_345, 10_500), rig.readAll(2_345, step = 1_024))
        rig.stream.close()
        // Segments count from the new position; no check again.
        assertEquals("first 2345+1000 fresh", rig.firstOpens()[1])
        assertEquals("first 4345+1000", rig.firstOpens()[2])
        assertEquals("second 3345+1000", rig.secondOpens()[0])
        // The file's end is less than a segment away: one plain request.
        rig.log.clear()
        assertEquals(400L, rig.stream.open(10_100, "k"))
        assertArrayEquals(expected(10_100, 10_500), rig.readAll(10_100))
        assertEquals(listOf("first 10100+-1 fresh"), rig.log.toList())
    }

    @Test
    fun aServerThatIgnoresRangesStaysOnOneRequest() {
        val rig = Rig()
        rig.first.ranges = false
        rig.stream.open(0, "k")
        assertArrayEquals(expected(0, 10_500), rig.readAll(0))
        rig.stream.close()
        rig.stream.open(4_000, "k")
        assertArrayEquals(expected(4_000, 10_500), rig.readAll(4_000))
        rig.stream.close()
        assertEquals(listOf("first 0+-1 fresh probe", "first 4000+-1 fresh"), rig.log.toList())
        assertTrue(rig.helpers.isEmpty())
        assertEquals("1(ranges ignored (status 200))", rig.film.mode())
        assertEquals(listOf("ranges status=200 match=no total=-1 one request: ranges ignored (status 200)"), rig.lines.toList())
        assertEquals(0L, rig.film.heldBytes)
    }

    @Test
    fun aMissingOrMismatchedContentRangeStaysOnOneRequest() {
        for (header in listOf<(Long, Long) -> String?>({ _, _ -> "none" }, { s, e -> "bytes ${s + 1}-${e - 1}/10500" }, { s, e -> "bytes $s-${e - 1}/*" })) {
            val rig = Rig()
            rig.first.contentRange = header
            rig.stream.open(0, "k")
            assertArrayEquals(expected(0, 10_500), rig.readAll(0))
            rig.stream.close()
            assertTrue(rig.helpers.isEmpty())
            assertTrue(rig.film.mode(), rig.film.mode().startsWith("1(ranges ignored"))
        }
    }

    @Test
    fun aSmallFileStaysOnOneRequestAndSaysNothingOfTheFilm() {
        val rig = Rig()
        val subtitles = Fake(3_000, rig.log, "first")
        val stream = TwoRangeStream(rig.film, subtitles) { fail("no second request for a small file"); subtitles }
        assertEquals(3_000L, stream.open(0, "subtitles"))
        val buffer = ByteArray(4_000)
        var got = 0
        while (true) {
            val n = stream.read(buffer, got, buffer.size - got)
            if (n < 0) break
            got += n
        }
        assertEquals(3_000, got)
        stream.close()
        assertEquals("1(small file)", rig.film.mode())
        // The film itself still gets two.
        rig.stream.open(0, "film")
        assertArrayEquals(expected(0, 10_500), rig.readAll(0))
        assertEquals("2", rig.film.mode())
    }

    @Test
    fun aJumpCutsTheSecondRequestOffAtOnceAndDropsWhatItFetched() {
        val rig = Rig()
        rig.onHelper = { it.hold = rig.helpers.isEmpty() }
        rig.stream.open(0, "k")
        val buffer = ByteArray(600)
        var got = 0
        while (got < 600) got += rig.stream.read(buffer, got, 600 - got)
        // The second request is under way, and waiting on the network.
        waitFor { rig.secondOpens().size == 1 }
        val old = rig.helpers[0]
        assertFalse(old.cancelled)
        rig.stream.close()
        assertTrue(old.cancelled)
        waitFor { old.closes == 1 }
        // The new connection: its own second request, from its own position; nothing of the old one's bytes.
        rig.stream.open(7_000, "k")
        assertArrayEquals(expected(7_000, 10_500), rig.readAll(7_000))
        rig.stream.close()
        assertEquals(listOf("second 1000+1000", "second 8000+1000", "second 10000+500"), rig.secondOpens())
        assertNull(rig.film.fallback)
        waitFor { rig.film.heldBytes <= SMALL.maxExtraBytes }
    }

    @Test
    fun theSecondRequestHoldsNoMoreThanItsTwoSegments() {
        val rig = Rig(total = 20_000)
        rig.stream.open(0, "k")
        val buffer = ByteArray(200)
        var got = 0
        while (got < 200) got += rig.stream.read(buffer, got, 200 - got)
        // The player takes nothing more: the second request fetches its two segments and waits.
        waitFor { rig.secondOpens().size == 2 }
        Thread.sleep(250)
        assertEquals(listOf("second 1000+1000", "second 3000+1000"), rig.secondOpens())
        assertEquals(2_000L, rig.film.heldBytes)
        // Taken up again, everything still comes in order.
        val rest = rig.readAll(200)
        assertArrayEquals(expected(200, 20_000), rest)
        rig.stream.close()
        assertEquals(2_000L, rig.film.heldBytes)
    }

    @Test
    fun aServerTurningTheSecondRequestAwayMeansOneRequestForTheRestOfTheFilm() {
        for (status in listOf(429, 503, 403, 509)) {
            val rig = Rig()
            rig.onHelper = { it.statusFor = { start -> if (start >= 3_000) status else 206 } }
            rig.stream.open(0, "k")
            assertArrayEquals(expected(0, 10_500), rig.readAll(0))
            rig.stream.close()
            assertEquals("http $status on the second request", rig.film.fallback)
            assertEquals("1(fallback: http $status on the second request)", rig.film.mode())
            assertEquals(listOf("second 1000+1000", "second 3000+1000"), rig.secondOpens())
            assertTrue(rig.lines.contains("fallback to one request: http $status on the second request"))
            // The next connection asks once, plainly, and no second request is ever made again.
            rig.log.clear()
            rig.stream.open(5_000, "k")
            assertArrayEquals(expected(5_000, 10_500), rig.readAll(5_000))
            rig.stream.close()
            assertEquals(listOf("first 5000+-1 fresh"), rig.log.toList())
            // The segments' memory is let go.
            waitFor { rig.film.heldBytes == 0L }
        }
    }

    @Test
    fun aServerTurningTheFirstRequestAwayMeansOneRequest() {
        for (status in listOf(429, 503, 403, 509)) {
            // In the middle of a connection: the next segment is refused, and the film goes on with one request.
            val rig = Rig()
            var refuse = true
            rig.first.statusFor = { start -> if (start == 2_000L && refuse) status.also { refuse = false } else 206 }
            rig.stream.open(0, "k")
            assertArrayEquals(expected(0, 10_500), rig.readAll(0))
            rig.stream.close()
            assertEquals("http $status", rig.film.fallback)
            assertEquals("first 2000+1000", rig.firstOpens()[1])
            assertEquals("first 2000+-1 fresh", rig.firstOpens()[2])
            assertEquals(3, rig.firstOpens().size)

            // As a connection opens: the same.
            val again = Rig()
            again.stream.open(0, "k")
            again.stream.close()
            var once = true
            again.first.statusFor = { if (once) status.also { once = false } else 206 }
            assertEquals(10_500L - 3_000, again.stream.open(3_000, "k"))
            assertArrayEquals(expected(3_000, 10_500), again.readAll(3_000))
            assertEquals("http $status", again.film.fallback)
        }
    }

    @Test
    fun aFailureAsAConnectionOpensIsThePlayersAsWithOneRequest() {
        val rig = Rig()
        rig.stream.open(0, "k")
        rig.stream.close()
        rig.first.throwOnOpen = { IOException("no route") }
        try {
            rig.stream.open(3_000, "k")
            fail("should have thrown")
        } catch (e: IOException) {
            assertEquals("no route", e.message)
        }
        // A 404 the same: nothing says two requests are to blame.
        rig.first.throwOnOpen = { null }
        rig.first.statusFor = { 404 }
        try {
            rig.stream.open(3_000, "k")
            fail("should have thrown")
        } catch (e: Refused) {
            assertEquals(404, e.status)
        }
        assertNull(rig.film.fallback)
    }

    @Test
    fun theNextSegmentFailingMeansOneRequest() {
        val rig = Rig()
        var failing = true
        rig.first.throwOnOpen = { start -> if (start == 4_000L && failing) IOException("reset").also { failing = false } else null }
        rig.stream.open(0, "k")
        assertArrayEquals(expected(0, 10_500), rig.readAll(0))
        rig.stream.close()
        assertEquals("request for the next segment failed", rig.film.fallback)
    }

    @Test
    fun aJumpInterruptingARequestIsNoFailure() {
        val rig = Rig()
        rig.first.throwOnOpen = { start -> if (start == 2_000L) IOException(InterruptedIOException()) else null }
        rig.stream.open(0, "k")
        val buffer = ByteArray(500)
        try {
            while (rig.stream.read(buffer, 0, 500) >= 0) Unit
            fail("should have thrown")
        } catch (e: IOException) {
            assertTrue(e.cause is InterruptedIOException)
        }
        rig.stream.close()
        assertNull(rig.film.fallback)
    }

    @Test
    fun aSecondRequestThatFailsOrIsRefusedMeansOneRequest() {
        val cases = listOf<Pair<(Fake) -> Unit, String>>(
            { f: Fake -> f.throwOnOpen = { IOException("refused") } } to "second request failed",
            { f: Fake -> f.endEarlyAt = 1_500 } to "second request ended early",
            { f: Fake -> f.ranges = false } to "second request: status 200",
            { f: Fake -> f.contentRange = { s, e -> "bytes $s-${e - 1}/999999" } } to "second request: another file size",
            { f: Fake -> f.contentRange = { s, e -> "bytes ${s + 5}-${e - 1}/10500" } } to "second request: another range",
        )
        for ((setUp, reason) in cases) {
            val rig = Rig()
            rig.onHelper = setUp
            rig.stream.open(0, "k")
            assertArrayEquals(reason, expected(0, 10_500), rig.readAll(0))
            rig.stream.close()
            assertEquals(reason, rig.film.fallback)
            assertEquals(1, rig.helpers.size)
            // From where the second request gave out, the first reads on alone.
            assertTrue(rig.firstOpens().last(), rig.firstOpens().last().endsWith("+-1 fresh"))
            assertEquals(2, rig.firstOpens().size)
        }
    }

    @Test
    fun aSecondRequestGoneQuietMeansOneRequest() {
        val rig = Rig(config = TwoRanges.Config(1_000, 2, 100, 5_000, stallMs = 150, paceWindowNs = Long.MAX_VALUE))
        rig.onHelper = { it.hold = true }
        rig.stream.open(0, "k")
        assertArrayEquals(expected(0, 10_500), rig.readAll(0))
        rig.stream.close()
        assertEquals("second request stalled", rig.film.fallback)
        assertTrue(rig.helpers[0].cancelled)
        waitFor { rig.film.heldBytes == 0L }
    }

    @Test
    fun aSecondRequestSlowerThanHalfTheFirstMeansOneRequest() {
        // The first request brings its segments at once; the second takes its time over every read.
        val rig = Rig(config = TwoRanges.Config(1_000, 2, 100, 5_000, stallMs = 5_000, paceWindowNs = 30_000_000L))
        rig.onHelper = { it.readDelayMs = 8 }
        rig.stream.open(0, "k")
        assertArrayEquals(expected(0, 10_500), rig.readAll(0))
        rig.stream.close()
        assertEquals("second request too slow", rig.film.fallback)
        assertTrue(rig.helpers[0].cancelled)
        assertEquals(1, rig.helpers.size)
    }

    @Test
    fun nothingOfTheLinkIsLogged() {
        val rig = Rig()
        rig.onHelper = { it.statusFor = { 429 } }
        rig.stream.open(0, "https://host.invalid/dl/SECRETKEY/film.mkv?token=abc")
        rig.readAll(0)
        rig.stream.close()
        val all = rig.lines.joinToString("\n") + rig.film.mode() + rig.film.rates()
        assertFalse(all, all.contains("http:") || all.contains("https") || all.contains("SECRET") || all.contains("token") || all.contains("host"))
    }

    @Test
    fun cancelledReadsAreToldFromFailures() {
        assertTrue(TwoRanges.cancelled(InterruptedIOException()))
        assertTrue(TwoRanges.cancelled(IOException(InterruptedIOException())))
        assertFalse(TwoRanges.cancelled(java.net.SocketTimeoutException()))
        assertFalse(TwoRanges.cancelled(IOException("reset")))
    }

    companion object {
        private val SMALL = TwoRanges.Config(
            segmentBytes = 1_000, buffers = 2, startAfterBytes = 100, minFileBytes = 5_000, stallMs = 3_000,
            paceWindowNs = Long.MAX_VALUE,
        )

        fun byteAt(position: Long): Byte = ((position * 7 + (position shr 8)) and 0xff).toByte()

        fun expected(from: Long, to: Long): ByteArray = ByteArray((to - from).toInt()) { byteAt(from + it) }

        fun waitFor(condition: () -> Boolean) {
            val until = System.currentTimeMillis() + 5_000
            while (!condition()) {
                if (System.currentTimeMillis() > until) fail("timed out")
                Thread.sleep(5)
            }
        }
    }
}
