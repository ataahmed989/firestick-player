package app.fireplayer.player

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a film's HDR10+ is done with ([Hdr10PlusWatch]) and how its messages are read ([Hdr10PlusValues]), on HEVC
 * access units built here bit by bit as ST 2094-40 lays them out.
 */
class Hdr10PlusWatchTest {

    private class Bits {
        val out = ByteArrayOutputStream()
        private var cur = 0
        private var n = 0
        fun put(value: Int, count: Int): Bits {
            for (i in count - 1 downTo 0) {
                cur = (cur shl 1) or ((value shr i) and 1)
                if (++n == 8) { out.write(cur); cur = 0; n = 0 }
            }
            return this
        }
        fun bytes(): ByteArray {
            while (n != 0) put(0, 1)
            return out.toByteArray()
        }
    }

    /** An HDR10+ message's T.35 payload. */
    private fun payload(
        version: Int = 1,
        windows: Int = 1,
        target: Int = 400,
        peakTable: Boolean = false,
        maxscl: IntArray = intArrayOf(8123, 7000, 6500),
        average: Int = 951,
        percentiles: Int = 9,
        percentage: Int = 50,
        anchors: IntArray? = intArrayOf(102, 205, 307, 410, 512, 614, 717, 819, 922),
        saturation: Boolean = false,
        cut: Int = 0,
    ): ByteArray {
        val b = Bits()
        for (v in intArrayOf(0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04)) b.put(v, 8)
        b.put(version, 8).put(windows, 2).put(target, 27).put(if (peakTable) 1 else 0, 1)
        for (v in maxscl) b.put(v, 17)
        b.put(average, 17).put(percentiles, 4)
        for (i in 0 until percentiles) b.put(percentage, 7).put(100 * (i + 1), 17)
        b.put(12, 10).put(0, 1)
        if (anchors == null) b.put(0, 1) else {
            b.put(1, 1).put(700, 12).put(900, 12).put(anchors.size, 4)
            for (a in anchors) b.put(a, 10)
        }
        b.put(if (saturation) 1 else 0, 1)
        if (saturation) b.put(20, 6)
        val all = b.bytes()
        return all.copyOf(all.size - cut)
    }

    private fun escape(rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var zeros = 0
        for (b in rbsp) {
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v <= 3) { out.write(3); zeros = 0 }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private fun nal(type: Int, body: ByteArray) = byteArrayOf(0, 0, 0, 1, (type shl 1).toByte(), 1) + body

    /** A frame: an HDR10+ prefix SEI when [plus] is given, picture data, then a Dolby Vision RPU (type 62) when [rpu]. */
    private fun frame(plus: ByteArray?, rpu: Boolean = false): ByteBuffer {
        var au = nal(35, byteArrayOf(0x50))
        if (plus != null) au += nal(39, escape(byteArrayOf(4, plus.size.toByte()) + plus + byteArrayOf(0x80.toByte())))
        au += nal(1, byteArrayOf(0x42, 0x17, 0x55, 0x66))
        if (rpu) au += nal(62, byteArrayOf(0x19, 0x08, 0x09, 0x55))
        return ByteBuffer.wrap(au)
    }

    private fun masked(b: ByteBuffer) = Hdr10PlusMask.count(b) == 0
    private fun values(p: ByteArray) = Hdr10PlusValues().also { it.take(p) }

    @Test
    fun `a well formed message reads as its numbers`() {
        val v = values(payload())
        assertEquals(0, v.bad)
        assertEquals(0, v.odd)
        assertEquals(400, v.target)
        assertEquals(8123, v.peak)
        assertEquals(951, v.average)
        assertEquals(9, v.anchors)
        assertEquals(700, v.kneeX)
        assertEquals(900, v.kneeY)
        assertEquals(-1, values(payload(anchors = null)).anchors)
        assertEquals(0, values(payload(anchors = null, version = 0, target = 0, percentiles = 10)).bad)
    }

    @Test
    fun `the numbers are read past emulation prevention in the stream`() {
        // Target 0 and zero levels put runs of zero bytes in the message, which the encoder escapes.
        val p = payload(target = 0, maxscl = intArrayOf(0, 0, 3), average = 2)
        val v = Hdr10PlusValues()
        assertEquals(1, Hdr10PlusMask.count(frame(p), v))
        assertTrue(v.read)
        assertEquals(0, v.bad)
        assertEquals(3, v.peak)
        assertEquals(2, v.average)
        assertEquals(9, v.anchors)
    }

    @Test
    fun `what breaks HDR10+'s rules is told apart`() {
        assertEquals(Hdr10PlusValues.VERSION, values(payload(version = 7)).bad)
        assertEquals(Hdr10PlusValues.WINDOWS, values(payload(windows = 2)).bad)
        assertEquals(Hdr10PlusValues.TARGET, values(payload(target = 4_000_000)).bad)
        assertEquals(Hdr10PlusValues.PEAK_TABLE, values(payload(peakTable = true)).bad)
        assertEquals(Hdr10PlusValues.LEVELS, values(payload(maxscl = intArrayOf(1, 131_000, 2))).bad)
        assertEquals(Hdr10PlusValues.LEVELS, values(payload(average = 120_000)).bad)
        assertEquals(Hdr10PlusValues.PERCENTILES, values(payload(percentiles = 4)).bad)
        assertEquals(Hdr10PlusValues.PERCENTILES, values(payload(percentage = 120)).bad)
        assertEquals(Hdr10PlusValues.ANCHORS, values(payload(anchors = IntArray(12) { it * 50 })).bad)
        assertEquals(Hdr10PlusValues.CUT_SHORT, values(payload(cut = 20)).bad)
        assertEquals("levels above 10000 nits, cut short", Hdr10PlusValues.words(Hdr10PlusValues.LEVELS or Hdr10PlusValues.CUT_SHORT))
    }

    @Test
    fun `the unusual is noted and not held against the film`() {
        assertEquals(Hdr10PlusValues.SATURATION, values(payload(saturation = true)).odd)
        assertEquals(Hdr10PlusValues.NO_LEVELS, values(payload(maxscl = IntArray(3), average = 0)).odd)
        assertEquals(Hdr10PlusValues.CURVE, values(payload(anchors = intArrayOf(500, 400, 600))).odd)
        assertEquals(0, values(payload(saturation = true)).bad)
    }

    @Test
    fun `a film's own HDR10+ goes to the decoder untouched`() {
        val w = Hdr10PlusWatch()
        val first = frame(payload())
        val before = first.array().clone()
        assertEquals("HDR10+ metadata in this video goes to the decoder as it is (the TV reports HDR10+)",
            w.sample(first, Hdr10PlusWatch.PASS, hybrid = false))
        assertTrue(w.found)
        assertFalse(w.masking)
        repeat(500) {
            val f = frame(payload(average = 900 + it))
            assertNull(w.sample(f, Hdr10PlusWatch.PASS, hybrid = false))
            assertFalse(masked(f))
        }
        assertTrue(before.contentEquals(first.array()))
        assertEquals(0, w.kept)
    }

    @Test
    fun `HDR10+ added to a Dolby Vision file is masked from the first frame to the last`() {
        val w = Hdr10PlusWatch()
        val first = frame(payload(), rpu = true)
        val line = w.sample(first, Hdr10PlusWatch.PASS, hybrid = true)
        assertTrue(line!!.contains("for the whole film"))
        assertTrue(masked(first))
        assertTrue(w.masking)
        repeat(200) {
            val f = frame(payload(), rpu = true)
            assertNull(w.sample(f, Hdr10PlusWatch.PASS, hybrid = true))
            assertTrue(masked(f))
        }
        assertEquals(Hdr10PlusWatch.HYBRID, w.kept)
    }

    @Test
    fun `masking changes one byte and leaves the picture and the Dolby Vision unit alone`() {
        val f = frame(payload(), rpu = true)
        val before = f.array().clone()
        Hdr10PlusWatch().sample(f, Hdr10PlusWatch.SETTING, hybrid = false)
        val after = f.array()
        val changed = before.indices.filter { before[it] != after[it] }
        assertEquals(1, changed.size)
        assertEquals(Hdr10PlusMask.MASKED_TYPE.toByte(), after[changed[0]])
        assertEquals(0, f.position())
        assertEquals(after.size, f.limit())
        // The RPU after the picture data is still type 62.
        assertEquals(62, (after[after.size - 6].toInt() and 0x7E) shr 1)
    }

    @Test
    fun `the setting off and a TV without HDR10+ mask every frame`() {
        for (gate in intArrayOf(Hdr10PlusWatch.TV, Hdr10PlusWatch.SETTING)) {
            val w = Hdr10PlusWatch()
            val first = frame(payload())
            assertNotNull(w.sample(first, gate, hybrid = false))
            assertTrue(masked(first))
            repeat(100) {
                val f = frame(payload())
                assertNull(w.sample(f, gate, hybrid = false))
                assertTrue(masked(f))
            }
            // Nothing is held against the film itself.
            assertEquals(0, w.kept)
        }
        assertEquals(Hdr10PlusWatch.TV, Hdr10PlusGate.reason(tvLacks = true, reports = false, reaches = true))
        assertEquals(Hdr10PlusWatch.SETTING, Hdr10PlusGate.reason(tvLacks = false, reports = true, reaches = false))
        assertEquals(Hdr10PlusWatch.PASS, Hdr10PlusGate.reason(tvLacks = false, reports = true, reaches = true))
        // Android 7 to 9 can't report HDR10+, and Settings › This TV doesn't show the setting there: a TV with HDR is
        // given HDR10+ as before, whatever the setting holds.
        assertEquals(Hdr10PlusWatch.PASS, Hdr10PlusGate.reason(tvLacks = false, reports = false, reaches = false))
        assertEquals(Hdr10PlusWatch.TV, Hdr10PlusGate.reason(tvLacks = true, reports = false, reaches = false))
    }

    @Test
    fun `a malformed message masks from its own frame on and never goes back`() {
        val w = Hdr10PlusWatch()
        repeat(100) { w.sample(frame(payload()), Hdr10PlusWatch.PASS, hybrid = false) }
        val bad = frame(payload(maxscl = intArrayOf(131_000, 5, 5)))
        val line = w.sample(bad, Hdr10PlusWatch.PASS, hybrid = false)
        assertTrue(line!!.contains("malformed (levels above 10000 nits)"))
        assertTrue(masked(bad))
        repeat(300) {
            val f = frame(payload())
            assertNull(w.sample(f, Hdr10PlusWatch.PASS, hybrid = false))
            assertTrue(masked(f))
        }
        assertEquals(Hdr10PlusWatch.MALFORMED, w.kept)
    }

    @Test
    fun `a frame without HDR10+ after frames with it masks the rest of the film`() {
        val w = Hdr10PlusWatch()
        repeat(100) { w.sample(frame(payload()), Hdr10PlusWatch.PASS, hybrid = false) }
        val line = w.sample(frame(null), Hdr10PlusWatch.PASS, hybrid = false)
        assertTrue(line!!.startsWith("HDR10+ metadata in this video isn't on every frame"))
        repeat(300) {
            val f = frame(if (it % 3 == 0) null else payload())
            assertNull(w.sample(f, Hdr10PlusWatch.PASS, hybrid = false))
            assertTrue(masked(f))
        }
        assertEquals(Hdr10PlusWatch.GAPS, w.kept)
    }

    @Test
    fun `a film without HDR10+ is never changed and never summed up`() {
        val w = Hdr10PlusWatch()
        repeat(500) {
            val f = frame(null, rpu = true)
            val before = f.array().clone()
            assertNull(w.sample(f, Hdr10PlusWatch.PASS, hybrid = true))
            assertTrue(before.contentEquals(f.array()))
        }
        assertFalse(w.found)
        assertFalse(w.masking)
        assertNull(w.summary(1_000))
        assertNull(w.summary(999_000))
    }

    @Test
    fun `a film's first HDR10+ after frames without any never reaches the decoder`() {
        // x265's dhdr10-opt, joined between two messages: frames without, then one with at the next scene.
        val w = Hdr10PlusWatch()
        repeat(200) { assertNull(w.sample(frame(null), Hdr10PlusWatch.PASS, hybrid = false)) }
        val first = frame(payload())
        val line = w.sample(first, Hdr10PlusWatch.PASS, hybrid = false)
        assertTrue(line!!.startsWith("HDR10+ metadata in this video isn't on every frame"))
        assertTrue(masked(first))
        assertEquals(Hdr10PlusWatch.GAPS, w.kept)
        repeat(300) {
            val f = frame(if (it % 40 == 0) payload() else null)
            assertNull(w.sample(f, Hdr10PlusWatch.PASS, hybrid = false))
            assertTrue(masked(f))
        }
    }

    @Test
    fun `HDR10+ that comes late is still masked for a TV that isn't to get it`() {
        for (gate in intArrayOf(Hdr10PlusWatch.TV, Hdr10PlusWatch.SETTING)) {
            val w = Hdr10PlusWatch()
            repeat(300) { assertNull(w.sample(frame(null), gate, hybrid = false)) }
            val late = frame(payload())
            assertNotNull(w.sample(late, gate, hybrid = false))
            assertTrue(masked(late))
            assertTrue(w.masking)
            // The TV's reason or the setting's: nothing is held against the film.
            assertEquals(0, w.kept)
        }
    }

    @Test
    fun `a sample without picture data says nothing of the film`() {
        val w = Hdr10PlusWatch()
        // Empty, cut short before its picture, and not Annex B at all.
        val odd = listOf(ByteBuffer.wrap(ByteArray(0)), ByteBuffer.wrap(nal(35, byteArrayOf(0x50))),
            ByteBuffer.wrap(byteArrayOf(0, 0, 0, 9, 0x26, 1, 0x42, 0x17, 0x55)))
        for (b in odd) assertNull(w.sample(b, Hdr10PlusWatch.PASS, hybrid = false))
        // So the film's first frame with HDR10+ isn't taken to come after frames without it.
        val first = frame(payload())
        assertTrue(w.sample(first, Hdr10PlusWatch.PASS, hybrid = false)!!.contains("goes to the decoder as it is"))
        assertFalse(masked(first))
        // Nor is one of them, among frames with HDR10+, a gap.
        for (b in odd) assertNull(w.sample(b, Hdr10PlusWatch.PASS, hybrid = false))
        assertEquals(0, w.kept)
        assertFalse(w.masking)
        assertEquals(Hdr10PlusMask.NO_PICTURE, Hdr10PlusMask.frame(odd[1], write = false))
        assertEquals(0, Hdr10PlusMask.frame(frame(null), write = false))
        assertEquals(1, Hdr10PlusMask.frame(frame(payload()), write = false))
    }

    @Test
    fun `a Dolby Vision unit before or after the picture is neither HDR10+ nor touched`() {
        // An RPU (type 62) ahead of the picture data, as some muxers put it, and one after: both left as they are.
        val au = nal(35, byteArrayOf(0x50)) + nal(62, byteArrayOf(0x19, 0x08, 0x09, 0x55)) +
            nal(39, escape(byteArrayOf(4, payload().size.toByte()) + payload() + byteArrayOf(0x80.toByte()))) +
            nal(1, byteArrayOf(0x42, 0x17, 0x55, 0x66)) + nal(62, byteArrayOf(0x19, 0x08, 0x09, 0x55))
        val f = ByteBuffer.wrap(au.clone())
        val w = Hdr10PlusWatch()
        assertTrue(w.sample(f, Hdr10PlusWatch.PASS, hybrid = true)!!.contains("for the whole film"))
        val changed = au.indices.filter { au[it] != f.array()[it] }
        assertEquals(1, changed.size)
        assertEquals(Hdr10PlusMask.MASKED_TYPE.toByte(), f.array()[changed[0]])
        // A Dolby Vision file without HDR10+ (RPU only): nothing found, nothing changed.
        val plain = frame(null, rpu = true)
        val before = plain.array().clone()
        assertNull(Hdr10PlusWatch().sample(plain, Hdr10PlusWatch.PASS, hybrid = true))
        assertTrue(before.contentEquals(plain.array()))
    }

    @Test
    fun `the next film is looked at afresh`() {
        val w = Hdr10PlusWatch()
        w.sample(frame(payload()), Hdr10PlusWatch.PASS, hybrid = false)
        w.sample(frame(null), Hdr10PlusWatch.PASS, hybrid = false)
        assertEquals(Hdr10PlusWatch.GAPS, w.kept)
        w.newStream()
        assertFalse(w.found)
        val f = frame(payload())
        assertNotNull(w.sample(f, Hdr10PlusWatch.PASS, hybrid = false))
        assertFalse(masked(f))
        assertEquals(0, w.kept)
    }

    @Test
    fun `each minute is summed up once, with no more than numbers`() {
        val w = Hdr10PlusWatch()
        w.sample(frame(payload()), Hdr10PlusWatch.PASS, hybrid = true)
        assertNull(w.summary(10_000))
        repeat(99) { w.sample(frame(payload(average = 500 + it * 10, anchors = if (it < 50) null else intArrayOf(1, 2, 3))), Hdr10PlusWatch.PASS, hybrid = true) }
        assertNull(w.summary(40_000))
        assertEquals(
            "HDR10+ this minute: 100 frames with it, 0 without; masked (added to a Dolby Vision file); target 400-400 nits, " +
                "peak 812-812 nits, average 50-148 nits, curve on 50 frames (0-9 anchors), values changed 100 times, " +
                "malformed 0, unusual 0",
            w.summary(70_000))
        assertNull(w.summary(80_000))
        w.sample(frame(payload()), Hdr10PlusWatch.PASS, hybrid = true)
        w.sample(frame(null), Hdr10PlusWatch.PASS, hybrid = true)
        assertTrue(w.summary(130_000)!!.startsWith("HDR10+ this minute: 1 frames with it, 1 without; masked"))
    }
}
