package app.fireplayer.player

import java.nio.ByteBuffer

/**
 * One frame's HDR10+ message read as numbers (SMPTE ST 2094-40, as CTA-861-H's Annex S lays it out in the T.35 SEI
 * [Hdr10PlusMask] finds), and whether a TV could be given it as it is: [bad] says what breaks HDR10+'s own rules, the
 * kind of metadata a TV may answer with a black screen or a pumping picture. One instance per renderer, filled again for
 * each frame: nothing is made per frame (the message, some 60 to 90 bytes, is copied into [raw] without its
 * emulation-prevention bytes and read from there).
 */
class Hdr10PlusValues {

    /** A message was read into the fields below since [clear]. */
    var read = false
        private set

    /** What breaks HDR10+'s rules ([VERSION]…), 0 for none; and what is only unusual ([SATURATION]…). */
    var bad = 0
        private set
    var odd = 0
        private set

    /** The display the metadata was made for, cd/m2 (0: none stated, HDR10+'s profile A). */
    var target = 0
        private set
    /** The scene's brightest colour channel and its average, in 0.1 cd/m2 (maxscl's largest, average_maxrgb). */
    var peak = 0
        private set
    var average = 0
        private set
    /** The tone curve's knee (0 to 4095 each) and how many Bezier anchors it has; -1 anchors without a curve. */
    var kneeX = 0
        private set
    var kneeY = 0
        private set
    var anchors = -1
        private set
    /** A sum of the values above and the curve's anchors: two frames of one scene have the same. */
    var sum = 0
        private set

    private val raw = ByteArray(CAP)
    private var size = 0
    private var bit = 0
    private var short = false

    fun clear() {
        read = false
        bad = 0
        odd = 0
    }

    /**
     * Reads the HDR10+ message whose T.35 payload of [payloadSize] bytes starts at [start] in [buffer] (before [end],
     * with [zeros] zero bytes just before it), emulation-prevention bytes left out. Nothing in [buffer] changes.
     */
    fun take(buffer: ByteBuffer, start: Int, end: Int, zeros: Int, payloadSize: Int) {
        var at = start
        var z = zeros
        var n = 0
        val want = if (payloadSize < CAP) payloadSize else CAP
        while (n < want && at < end) {
            val b = buffer.get(at)
            at++
            if (b.toInt() == 0x03 && z >= 2) {
                z = 0
                continue
            }
            z = if (b.toInt() == 0) z + 1 else 0
            raw[n++] = b
        }
        size = n
        parse()
    }

    /** For tests: reads a message from its bytes, from the country code on, with no emulation prevention. */
    fun take(message: ByteArray) {
        size = if (message.size < CAP) message.size else CAP
        System.arraycopy(message, 0, raw, 0, size)
        parse()
    }

    private fun parse() {
        read = true
        bad = 0
        odd = 0
        short = false
        anchors = -1
        kneeX = 0
        kneeY = 0
        // After the country, provider and application codes [Hdr10PlusMask] matched (6 bytes).
        bit = 48
        var total = 0
        if (bits(8) > 1) bad = bad or VERSION
        if (bits(2) != 1) {
            // More than one window isn't HDR10+, and what follows can't be read as one.
            bad = bad or WINDOWS
            return
        }
        target = bits(27)
        if (target > 10_000) bad = bad or TARGET
        if (bits(1) == 1) {
            bad = bad or PEAK_TABLE
            return
        }
        var top = 0
        for (i in 0 until 3) {
            val v = bits(17)
            if (v > top) top = v
        }
        peak = top
        average = bits(17)
        if (top > LEVEL_MAX || average > LEVEL_MAX) bad = bad or LEVELS
        if (top == 0 && average == 0) odd = odd or NO_LEVELS
        val percentiles = bits(4)
        if (percentiles != 9 && percentiles != 10) bad = bad or PERCENTILES
        for (i in 0 until percentiles) {
            if (bits(7) > 100) bad = bad or PERCENTILES
            val v = bits(17)
            if (v > LEVEL_MAX) bad = bad or LEVELS
            total += v
        }
        bits(10)
        if (bits(1) == 1) {
            bad = bad or PEAK_TABLE
            return
        }
        if (bits(1) == 1) {
            kneeX = bits(12)
            kneeY = bits(12)
            val count = bits(4)
            anchors = count
            // HDR10+ sends a TV nine anchors at most.
            if (count > 9) bad = bad or ANCHORS
            var last = 0
            for (i in 0 until count) {
                val v = bits(10)
                if (v < last) odd = odd or CURVE
                last = v
                total = total * 31 + v
            }
        }
        if (bits(1) == 1) {
            odd = odd or SATURATION
            bits(6)
        }
        if (short) bad = bad or CUT_SHORT
        sum = ((total * 31 + target) * 31 + peak) * 31 + average + kneeX * 4096 + kneeY
    }

    /** The next [count] bits (27 at most); 0, and [short] set, past the message's end. */
    private fun bits(count: Int): Int {
        if (bit + count > size * 8) {
            short = true
            bit = size * 8
            return 0
        }
        var v = 0
        var left = count
        while (left > 0) {
            val inByte = 8 - (bit and 7)
            val take = if (left < inByte) left else inByte
            val b = raw[bit shr 3].toInt() and 0xFF
            v = (v shl take) or ((b shr (inByte - take)) and ((1 shl take) - 1))
            bit += take
            left -= take
        }
        return v
    }

    companion object {
        /** More than any HDR10+ message without the tables HDR10+ doesn't allow: read whole. */
        private const val CAP = 128
        /** 10000 cd/m2 in the levels' units of 0.1. */
        private const val LEVEL_MAX = 100_000

        /** [bad]: an application version HDR10+ doesn't have (0 and 1 are). */
        const val VERSION = 1
        /** [bad]: not exactly one window. */
        const val WINDOWS = 2
        /** [bad]: made for a display above 10000 cd/m2. */
        const val TARGET = 4
        /** [bad]: an actual-peak table, which HDR10+ doesn't allow. */
        const val PEAK_TABLE = 8
        /** [bad]: a brightness level above 10000 cd/m2. */
        const val LEVELS = 16
        /** [bad]: not 9 or 10 percentiles, or one above 100 %. */
        const val PERCENTILES = 32
        /** [bad]: more than nine curve anchors. */
        const val ANCHORS = 64
        /** [bad]: the message ends before its values do. */
        const val CUT_SHORT = 128

        /** [odd]: a colour saturation weight, which HDR10+ doesn't use. */
        const val SATURATION = 1
        /** [odd]: no brightness at all in the scene's levels. */
        const val NO_LEVELS = 2
        /** [odd]: a tone curve that goes down. */
        const val CURVE = 4

        /** [bad] in words, for the log. */
        fun words(bad: Int): String {
            val out = ArrayList<String>()
            if (bad and VERSION != 0) out += "unknown version"
            if (bad and WINDOWS != 0) out += "not one window"
            if (bad and TARGET != 0) out += "target display above 10000 nits"
            if (bad and PEAK_TABLE != 0) out += "a peak table"
            if (bad and LEVELS != 0) out += "levels above 10000 nits"
            if (bad and PERCENTILES != 0) out += "wrong percentiles"
            if (bad and ANCHORS != 0) out += "too many curve anchors"
            if (bad and CUT_SHORT != 0) out += "cut short"
            return out.joinToString(", ")
        }
    }
}
