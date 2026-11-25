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
        var crashOnOpen = false
        var endEarlyAt = -1L
        @Volatile var hold = false
        var readDelayMs = 0L
        /** The server's time to answer a range. */
        var openDelayMs = 0L
        @Volatile var cancelled = false
        @Volatile var closes = 0
        private var at = 0L
        private var end = 0L

        override fun open(start: Long, length: Long, fresh: Boolean, probe: Boolean): TwoRanges.Answer {
            log.add("$name $start+$length" + (if (fresh) " fresh" else "") + if (probe) " probe" else "")
            if (openDelayMs > 0) Thread.sleep(openDelayMs)
            if (cancelled) throw IOException("cancelled")
            throwOnOpen(start)?.let { throw it }
            if (crashOnOpen) throw IllegalStateException("http://secret.example/key")
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

    private class Rig(total: Long = 10_500, config: TwoRanges.Config = SMALL, long: Boolean = false) {
        val total = total
        val log: MutableList<String> = Collections.synchronizedList(ArrayList<String>())
        val lines: MutableList<String> = Collections.synchronizedList(ArrayList<String>())
        // Told of every line logged, from whichever thread.
        private val heard = Object()
        val film = TwoRanges(config) { line ->
            synchronized(heard) {
                lines.add(line)
                heard.notifyAll()
            }
        }.also { if (long) it.longSpans() }
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

        /** The lines logged so far, copied while no thread adds one. */
        fun linesNow(): List<String> = synchronized(lines) { ArrayList(lines) }

        /**
         * Waits for [line] to be logged. A line the second request's thread logs can come after the reading is done:
         * its fallback is seen here as soon as it's made, and the line written just after.
         */
        fun awaitLine(line: String) = synchronized(heard) {
            val until = System.nanoTime() + 5_000_000_000L
            while (!lines.contains(line)) {
                val left = until - System.nanoTime()
                if (left <= 0L) fail("never logged: $line, only ${linesNow()}")
                heard.wait(left / 1_000_000L + 1)
            }
        }

        /** Every request made for a second request or sent ahead is closed or cut off. */
        fun helpersDone(): Boolean = synchronized(helpers) { helpers.all { it.closes > 0 || it.cancelled } }
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
        // Nothing of the film yet.
        assertEquals("2(not started)", rig.film.mode())
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
        // The player let go of: the pool's memory goes, and none is made again.
        rig.film.release()
        waitFor { rig.film.heldBytes == 0L }
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
            rig.awaitLine("fallback to one request: http $status on the second request")
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
    fun givingUpIsForThatFilmOnly() {
        val rig = Rig()
        rig.onHelper = { it.statusFor = { 429 } }
        rig.stream.open(0, "one")
        assertArrayEquals(expected(0, 10_500), rig.readAll(0))
        rig.stream.close()
        assertEquals("http 429 on the second request", rig.film.fallback)
        // The same film at the address its provider sent it to: one request, though its ranges are checked anew.
        rig.log.clear()
        rig.stream.open(0, "one, where it landed")
        assertArrayEquals(expected(0, 10_500), rig.readAll(0))
        rig.stream.close()
        assertEquals(listOf("first 0+-1 fresh probe"), rig.log.toList())
        // Another film in the same player (the next episode, another source) gets its two.
        val next = Fake(20_000, rig.log, "first")
        val seconds = ArrayList<Fake>()
        val stream = TwoRangeStream(rig.film, next) { Fake(20_000, rig.log, "second").also { seconds.add(it) } }
        assertEquals(20_000L, stream.open(0, "two"))
        val out = ByteArray(20_000)
        var got = 0
        while (true) {
            val n = stream.read(out, got, minOf(700, out.size - got))
            if (n <= 0) break
            got += n
        }
        stream.close()
        assertArrayEquals(expected(0, 20_000), out)
        assertEquals("2", rig.film.mode())
        assertNull(rig.film.fallback)
        assertEquals(1, seconds.size)
        assertEquals("http 429 on the second request", rig.film.fallbackFor(10_500))
    }

    @Test
    fun turnedOffWhileAFilmPlaysItsNextConnectionIsOneRequest() {
        val rig = Rig()
        rig.stream.open(0, "k")
        rig.stream.close()
        rig.film.enabled = false
        rig.log.clear()
        rig.stream.open(2_000, "k")
        assertArrayEquals(expected(2_000, 10_500), rig.readAll(2_000))
        rig.stream.close()
        assertEquals(listOf("first 2000+-1 fresh"), rig.log.toList())
        assertEquals("1(turned off)", rig.film.mode())
        // And a film first met while it's off isn't even asked for a range.
        rig.stream.open(0, "other")
        assertEquals("first 0+-1 fresh", rig.log.last())
        rig.stream.close()
    }

    @Test
    fun turnedOffWhileAConnectionReadsWithTwoItStillSaysTwo() {
        val rig = Rig()
        rig.stream.open(0, "k")
        assertTrue(rig.film.twoNow)
        rig.film.enabled = false
        // Still two requests until this connection ends: the log and the speed kept must say so.
        assertEquals("2(turning off)", rig.film.mode())
        assertTrue(rig.film.twoNow)
        assertArrayEquals(expected(0, 10_500), rig.readAll(0))
        assertTrue(rig.secondOpens().isNotEmpty())
        rig.stream.close()
        assertFalse(rig.film.twoNow)
        assertEquals("1(turned off)", rig.film.mode())
        // The next connection is one request, and counts as one.
        rig.stream.open(2_000, "k")
        assertFalse(rig.film.twoNow)
        rig.stream.close()
    }

    @Test
    fun aConnectionPutBackOnOneRequestNoLongerCountsAsTwo() {
        val rig = Rig()
        rig.onHelper = { it.throwOnOpen = { IOException("refused") } }
        rig.stream.open(0, "k")
        assertTrue(rig.film.twoNow)
        assertArrayEquals(expected(0, 10_500), rig.readAll(0))
        assertFalse(rig.film.twoNow)
        rig.stream.close()
        assertFalse(rig.film.twoNow)
    }

    @Test
    fun aFilmStandingStillGivesItsSegmentsBackAndGoesOnWithTwoRequests() {
        val rig = Rig(total = 20_000)
        rig.stream.open(0, "k")
        val buffer = ByteArray(200)
        var got = 0
        while (got < 200) got += rig.stream.read(buffer, got, 200 - got)
        waitFor { rig.secondOpens().size == 2 }
        waitFor { rig.film.heldBytes == 2_000L }
        rig.film.rest()
        // Nothing is held while it stands still, and two requests are not given up.
        waitFor { rig.film.heldBytes == 0L }
        assertTrue(rig.helpers[0].cancelled)
        assertNull(rig.film.fallback)
        assertEquals("2", rig.film.mode())
        // Taken up again: every byte in order, the second request back at the next odd segment.
        assertArrayEquals(expected(200, 20_000), rig.readAll(200))
        assertNull(rig.film.fallback)
        assertEquals(2, rig.helpers.size)
        assertEquals("second 1000+1000", rig.secondOpens()[2])
        assertTrue(rig.secondOpens().contains("second 19000+1000"))
        assertTrue(rig.film.heldBytes <= SMALL.maxExtraBytes)
        rig.stream.close()
        rig.film.release()
        waitFor { rig.film.heldBytes == 0L }
    }

    @Test
    fun restingInTheMiddleOfASecondRequestsSegmentLosesNoByte() {
        val rig = Rig(total = 20_000)
        rig.stream.open(0, "k")
        val buffer = ByteArray(100)
        var got = 0
        // Into the second request's first segment (1000 to 2000), part of it handed on.
        while (got < 1_400) got += rig.stream.read(buffer, 0, 100)
        rig.film.rest()
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val n = rig.stream.read(buffer, 0, 100)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        assertArrayEquals(expected(got.toLong(), 20_000), out.toByteArray())
        assertNull(rig.film.fallback)
        rig.stream.close()
        // Rested twice, and with nothing running: no harm.
        rig.film.rest()
        rig.film.rest()
        waitFor { rig.film.heldBytes == 0L }
    }

    @Test
    fun restingDoesNothingOnOneRequestOrOnceThePlayerIsGone() {
        val rig = Rig()
        rig.film.enabled = false
        rig.stream.open(0, "k")
        rig.film.rest()
        assertArrayEquals(expected(0, 10_500), rig.readAll(0))
        rig.stream.close()
        rig.film.release()
        rig.film.rest()
        assertEquals(0L, rig.film.heldBytes)
        assertTrue(rig.linesNow().none { it.contains("rested") })
    }

    @Test
    fun aCrashOnTheSecondRequestsThreadMeansOneRequestAndSaysNothingOfTheLink() {
        val rig = Rig()
        rig.onHelper = { it.crashOnOpen = true }
        rig.stream.open(0, "k")
        assertArrayEquals(expected(0, 10_500), rig.readAll(0))
        rig.stream.close()
        assertEquals("second request failed", rig.film.fallback)
        // Its line in first, so what's checked is everything said of the crash.
        rig.awaitLine("fallback to one request: second request failed")
        assertTrue(rig.linesNow().none { it.contains("secret") })
        waitFor { rig.film.heldBytes == 0L }
    }

    @Test
    fun theWaitThatEndsInAFailureIsCounted() {
        // The second request goes quiet with nothing brought: the read waits for it until it's given up, so the read
        // that fails has always waited first, however this thread is held up. (A failure that comes from the second
        // request itself can be there before the read starts: no wait at all, rightly counted as none.)
        val film = TwoRanges(TwoRanges.Config(1_000, 2, 100, 5_000, stallMs = 50, paceWindowNs = Long.MAX_VALUE))
        val request = Fake(10_500, Collections.synchronizedList(ArrayList<String>()), "second")
        request.hold = true
        val run = HelperRun(film, request, SegmentPlan(0, 10_500, 1_000))
        run.start()
        try {
            run.read(1, 1_000, ByteArray(1_000), 0, 1_000)
            fail("should have thrown")
        } catch (e: HelperGone) {
            assertEquals("second request stalled", e.reason)
        }
        // At least the time it took to give the second request up.
        assertTrue(run.lastWaitNs.toString(), run.lastWaitNs >= 50_000_000L)
        run.cancel()
        run.join(5_000)
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

            // As a connection opens: the player's to handle, as with one request (a stale remembered address's 403 is
            // asked of the provider again), and two requests aren't given up for it.
            val again = Rig()
            again.stream.open(0, "k")
            again.stream.close()
            var once = true
            again.first.statusFor = { if (once) status.also { once = false } else 206 }
            try {
                again.stream.open(3_000, "k")
                fail("the refusal reaches the player")
            } catch (_: IOException) {
            }
            again.stream.close()
            assertNull(again.film.fallback)
            assertEquals(10_500L - 3_000, again.stream.open(3_000, "k"))
            assertArrayEquals(expected(3_000, 10_500), again.readAll(3_000))
            assertNull(again.film.fallback)
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
        val rig = Rig(
            config = TwoRanges.Config(1_000, 2, 100, 5_000, stallMs = 150, paceWindowNs = Long.MAX_VALUE, aheadMinNs = Long.MAX_VALUE),
        )
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
        // The first request brings its segments at once; the second takes its time over every read. Only the second
        // request's pace is weighed: nothing is asked ahead, and the time spent on answers is never too much.
        val rig = Rig(
            config = TwoRanges.Config(
                1_000, 2, 100, 5_000, stallMs = 5_000, paceWindowNs = 30_000_000L, aheadMinNs = Long.MAX_VALUE, answerShare = 1.0,
            ),
        )
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
        rig.awaitLine("fallback to one request: http 429 on the second request")
        val all = rig.linesNow().joinToString("\n") + rig.film.mode() + rig.film.rates()
        assertFalse(all, all.contains("http:") || all.contains("https") || all.contains("SECRET") || all.contains("token") || all.contains("host"))
    }

    // --- Long spans, answers and requests sent ahead

    @Test
    fun longSpansAreChosenBeforeAnyMemoryIsMadeAndSayWhatTheyAdd() {
        val film = TwoRanges(TwoRanges.Config(segmentBytes = 1_000, buffers = 2, longSegmentBytes = 3_000, longBuffers = 1))
        assertEquals(1_000, film.spanBytes)
        // 3,000 held at most instead of 2,000: the film's buffer gives up the 1,000 more.
        assertEquals(1_000L, film.longSpans())
        assertEquals(3_000, film.spanBytes)
        // Never smaller than nothing: a long span held as one buffer that's no bigger adds nothing.
        assertEquals(0L, TwoRanges(TwoRanges.Config(segmentBytes = 1_000, buffers = 2, longSegmentBytes = 2_000, longBuffers = 1)).longSpans())
    }

    @Test
    fun longSpansMeanFewerRangesEachReadToItsLastByte() {
        val rig = Rig(total = 20_000, config = LONG, long = true)
        assertEquals(20_000L, rig.stream.open(0, "k"))
        assertArrayEquals(expected(0, 20_000), rig.readAll(0))
        rig.stream.close()
        assertNull(rig.film.fallback)
        assertEquals(
            listOf("first 0+-1 fresh probe", "first 6000+3000", "first 12000+3000", "first 18000+2000"),
            rig.firstOpens(),
        )
        assertEquals(listOf("second 3000+3000", "second 9000+3000", "second 15000+3000"), rig.secondOpens())
        // One long span held at a time.
        assertTrue(rig.film.heldBytes <= LONG.maxLongBytes)
        // Every range but the probe (cut off at its span's end) was read to its last byte and closed once.
        assertEquals(3, rig.first.closes - 1)
    }

    @Test
    fun slowAnswersSendEachSpansRequestAheadOnAConnectionOfItsOwn() {
        val rig = Rig(total = 40_000, config = AHEAD)
        rig.first.openDelayMs = 30
        rig.onHelper = { it.openDelayMs = 30 }
        rig.film.countSockets { 7L }
        assertEquals(40_000L, rig.stream.open(0, "k"))
        assertArrayEquals(expected(0, 40_000), rig.readAll(0))
        rig.stream.close()
        assertNull(rig.film.fallback)
        assertTrue(rig.film.sendAhead(40_000L))
        assertTrue(rig.film.answerNs >= 30_000_000L)
        // Once answers were seen to be slow, the first request's later spans were asked for ahead, on requests of their
        // own (made like the second's), and read from there on the loading thread.
        val evenAhead = rig.secondOpens().filter { it.substringAfter(' ').substringBefore('+').toLong() / 4_000 % 2 == 0L }
        assertTrue(rig.log.toString(), evenAhead.isNotEmpty())
        assertTrue(rig.firstOpens().size < 5)
        // Every range asked once.
        val asked = synchronized(rig.log) { ArrayList(rig.log) }
        val starts = asked.map { it.split(' ')[1].substringBefore('+') }
        assertEquals(asked.toString(), starts.size, starts.toSet().size)
        // The bench line tells the answer time and the connections made.
        assertTrue(rig.film.rates(), rig.film.rates().contains(" answer="))
        assertTrue(rig.film.rates(), rig.film.rates().endsWith(" new=0"))
        // Every request sent is closed or cut off by the end.
        waitFor { rig.helpersDone() }
    }

    @Test
    fun aServerTurningAwayARequestSentAheadStopsTheAskingAheadNotTwoRequests() {
        val rig = Rig(total = 40_000, config = AHEAD)
        rig.first.openDelayMs = 30
        val made = java.util.concurrent.atomic.AtomicInteger()
        rig.onHelper = { f ->
            f.openDelayMs = 30
            // The second request's own connection is let in; one more (a request sent ahead) is turned away, as a
            // server allowing two connections would, and let in once asked again.
            if (made.getAndIncrement() > 0) {
                val opens = java.util.concurrent.atomic.AtomicInteger()
                f.statusFor = { if (opens.getAndIncrement() == 0) 429 else 206 }
            }
        }
        assertEquals(40_000L, rig.stream.open(0, "k"))
        assertArrayEquals(expected(0, 40_000), rig.readAll(0))
        rig.stream.close()
        // Two requests kept: the span turned away was asked for again in place, and nothing more is asked ahead.
        assertNull(rig.film.fallback)
        assertEquals("2", rig.film.mode())
        rig.awaitLine("asking ahead stopped: http 429")
        assertEquals(1, synchronized(rig.lines) { rig.lines.count { it.startsWith("asking ahead stopped") } })
        assertFalse(rig.film.sendAhead(40_000L))
        // Another film in the same player starts afresh.
        assertTrue(rig.film.sendAhead(80_000L))
        waitFor { rig.helpersDone() }
    }

    @Test
    fun aRequestSentAheadThatFailsIsAskedForAgainInPlace() {
        val rig = Rig(total = 40_000, config = AHEAD)
        rig.first.openDelayMs = 30
        val made = java.util.concurrent.atomic.AtomicInteger()
        rig.onHelper = { f ->
            f.openDelayMs = 30
            if (made.getAndIncrement() > 0) {
                val opens = java.util.concurrent.atomic.AtomicInteger()
                f.throwOnOpen = { if (opens.getAndIncrement() == 0) IOException("reset") else null }
            }
        }
        assertEquals(40_000L, rig.stream.open(0, "k"))
        assertArrayEquals(expected(0, 40_000), rig.readAll(0))
        rig.stream.close()
        // Not a limit: two requests kept, and asking ahead goes on.
        assertNull(rig.film.fallback)
        assertTrue(rig.film.sendAhead(40_000L))
        assertFalse(rig.lines.toString(), rig.linesNow().any { it.startsWith("asking ahead stopped") })
        waitFor { rig.helpersDone() }
    }

    @Test
    fun aJumpDropsARequestSentAhead() {
        val rig = Rig(total = 40_000, config = AHEAD)
        rig.onHelper = { it.openDelayMs = 40 }
        rig.stream.open(0, "k")
        val buffer = ByteArray(100)
        var got = 0
        while (got < 200) got += rig.stream.read(buffer, 0, 100)
        // The second request's first answer tells how slow answers are.
        waitFor { rig.film.answerNs > 0L }
        // To the span's last 100 bytes: when its next request goes ahead depends on the speed measured, so on how long
        // this thread took to get here, and with that little left it is due unless that was many seconds.
        while (got < 3_900) got += rig.stream.read(buffer, 0, 100)
        waitFor { rig.secondOpens().any { it.startsWith("second 8000+") } }
        rig.stream.close()
        // Cut off: the request sent ahead too, not only the second request.
        waitFor { rig.helpersDone() }
        assertNull(rig.film.fallback)
        // The next connection starts with the bytes wanted, as ever.
        rig.log.clear()
        rig.stream.open(20_000, "k")
        assertArrayEquals(expected(20_000, 40_000), rig.readAll(20_000))
        rig.stream.close()
        assertEquals("first 20000+4000 fresh", rig.firstOpens().first())
        assertNull(rig.film.fallback)
    }

    @Test
    fun aFilmStandingStillDropsTheSpanAskedForAheadAndAsksAgainAfter() {
        val rig = Rig(total = 40_000, config = AHEAD)
        rig.onHelper = { it.openDelayMs = 40 }
        rig.stream.open(0, "k")
        val buffer = ByteArray(100)
        var got = 0
        while (got < 200) got += rig.stream.read(buffer, 0, 100)
        waitFor { rig.film.answerNs > 0L }
        // To the span's last 100 bytes, as above.
        while (got < 3_900) got += rig.stream.read(buffer, 0, 100)
        waitFor { rig.secondOpens().any { it.startsWith("second 8000+") } }
        rig.film.rest()
        // Nothing sent ahead is left waiting, the second request included.
        waitFor { rig.helpersDone() }
        // Reading goes on: every byte in order, the span asked for again, two requests kept.
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val n = rig.stream.read(buffer, 0, 100)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        assertArrayEquals(expected(got.toLong(), 40_000), out.toByteArray())
        assertNull(rig.film.fallback)
        val asked = synchronized(rig.log) { ArrayList(rig.log) }
        assertTrue(asked.toString(), asked.count { it.contains(" 8000+4000") } >= 2)
        rig.stream.close()
    }

    @Test
    fun answersTakingTooMuchOfTheFirstRequestsTimeAreWorseThanOneRequest() {
        val pace = HelperPace(windowNs = 1_000, answerShare = 0.3)
        pace.read(600)
        pace.reopened(350)
        // A third of the time waiting for answers: one open-ended request pays that once.
        assertTrue(pace.waited(100))
        assertTrue(pace.why, pace.why.startsWith("answers too slow (33%"))
        // A fifth: two still bring more.
        pace.read(700)
        pace.reopened(200)
        assertFalse(pace.waited(100))
        // Waiting for the second request is still told apart.
        pace.read(300)
        assertTrue(pace.waited(800))
        assertEquals("second request too slow", pace.why)
    }

    @Test
    fun theNextRequestGoesAboutAnAnswerAheadOfNeed() {
        val mb = 1L shl 20
        // 59 Mb/s within a span (7.4 MB a second) and answers of a second: sent with about 1.45 s of the span left.
        assertTrue(SendAhead.due(8 * mb, 7_375_000.0, 1_000_000_000L))
        assertFalse(SendAhead.due(16 * mb, 7_375_000.0, 1_000_000_000L))
        // Not before the span's speed or the answer time is known.
        assertFalse(SendAhead.due(1, 0.0, 1_000_000_000L))
        assertFalse(SendAhead.due(1, 7_375_000.0, 0L))
        assertEquals(1L shl 20, SendAhead.least(32 * mb))
        assertEquals(250L, SendAhead.least(1_000))
    }

    @Test
    fun asThePlayerMakesItOnlyAnswersOf150MsOrMoreSendRequestsAhead() {
        // The player's own settings (the tests above set theirs): fast answers never open a connection more at each
        // span's end. Told the answer times here, with no clock.
        val film = TwoRanges(log = {})
        assertFalse(film.sendAhead(40_000L))
        film.answered(1_000_000L)
        assertFalse(film.sendAhead(40_000L))
        // Slow answers lately: the next span goes ahead.
        film.answered(1_000_000_000L)
        assertTrue(film.sendAhead(40_000L))
        // Where it starts.
        assertFalse(TwoRanges(log = {}).apply { answered(149_999_999L) }.sendAhead(40_000L))
        assertTrue(TwoRanges(log = {}).apply { answered(150_000_000L) }.sendAhead(40_000L))
    }

    @Test
    fun cancelledReadsAreToldFromFailures() {
        assertTrue(TwoRanges.cancelled(InterruptedIOException()))
        assertTrue(TwoRanges.cancelled(IOException(InterruptedIOException())))
        assertFalse(TwoRanges.cancelled(java.net.SocketTimeoutException()))
        assertFalse(TwoRanges.cancelled(IOException("reset")))
    }

    companion object {
        // Nothing is asked ahead with these, however long an answer seems to take: answers here come at once, but one
        // measured on a busy machine (a thread held up as it asked) would otherwise send requests ahead, which the tests
        // using these don't count on.
        private val SMALL = TwoRanges.Config(
            segmentBytes = 1_000, buffers = 2, startAfterBytes = 100, minFileBytes = 5_000, stallMs = 3_000,
            paceWindowNs = Long.MAX_VALUE, aheadMinNs = Long.MAX_VALUE,
        )

        private val LONG = TwoRanges.Config(
            segmentBytes = 1_000, buffers = 2, startAfterBytes = 100, minFileBytes = 5_000, stallMs = 3_000,
            paceWindowNs = Long.MAX_VALUE, longSegmentBytes = 3_000, longBuffers = 1, aheadMinNs = Long.MAX_VALUE,
        )

        // Spans of 4,000 bytes, and answers of 1 ms or more count as slow.
        private val AHEAD = TwoRanges.Config(
            segmentBytes = 4_000, buffers = 2, startAfterBytes = 100, minFileBytes = 5_000, stallMs = 3_000,
            paceWindowNs = Long.MAX_VALUE, aheadMinNs = 1_000_000L,
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
