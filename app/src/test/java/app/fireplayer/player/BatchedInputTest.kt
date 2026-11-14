package app.fireplayer.player

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.extractor.DefaultExtractorInput
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.EOFException
import kotlin.random.Random

class BatchedInputTest {

    private val bytes = Random(7).nextBytes(100_000)

    private fun input(): DefaultExtractorInput {
        var at = 0
        // Hands out at most a few bytes at a time, as a network read may.
        val source = DataReader { buffer, offset, length ->
            if (at >= bytes.size) return@DataReader C.RESULT_END_OF_INPUT
            val n = minOf(length, bytes.size - at, 3000)
            System.arraycopy(bytes, at, buffer, offset, n)
            at += n
            n
        }
        return DefaultExtractorInput(source, 0, bytes.size.toLong())
    }

    @Test fun tinyReadsAndSkipsGiveTheSameBytesAndPositions() {
        val base = input()
        val batched = BatchedInput(base)
        val random = Random(1)
        var at = 0
        while (at < 90_000) {
            when (random.nextInt(4)) {
                0 -> {
                    val n = random.nextInt(1, 9)
                    val out = ByteArray(n)
                    batched.readFully(out, 0, n)
                    assertArrayEquals(bytes.copyOfRange(at, at + n), out)
                    at += n
                }
                1 -> {
                    val n = random.nextInt(1, 40)
                    batched.skipFully(n)
                    at += n
                }
                2 -> {
                    val out = ByteArray(3)
                    val n = batched.read(out, 0, 3)
                    assertArrayEquals(bytes.copyOfRange(at, at + n), out.copyOf(n))
                    at += n
                }
                else -> {
                    // A long skip, past more than one piece.
                    if (random.nextInt(50) == 0) {
                        batched.skipFully(40_000.coerceAtMost(90_000 - at))
                        at += 40_000.coerceAtMost(90_000 - at)
                    }
                }
            }
            assertEquals(at.toLong(), batched.position)
        }
        batched.finish()
        // Base is left exactly where the reads got to, and reads on from there.
        assertEquals(at.toLong(), base.position)
        val next = ByteArray(10)
        base.readFully(next, 0, 10)
        assertArrayEquals(bytes.copyOfRange(at, at + 10), next)
    }

    @Test fun aPeekHandsEverythingToBase() {
        val base = input()
        val batched = BatchedInput(base)
        val out = ByteArray(5)
        batched.readFully(out, 0, 5)
        val peeked = ByteArray(4)
        batched.peekFully(peeked, 0, 4)
        assertArrayEquals(bytes.copyOfRange(5, 9), peeked)
        assertEquals(5L, base.position)
        batched.readFully(out, 0, 5)
        assertArrayEquals(bytes.copyOfRange(5, 10), out)
        assertEquals(10L, base.position)
    }

    @Test fun theEndIsTheEnd() {
        val base = input()
        val batched = BatchedInput(base)
        batched.skipFully(bytes.size - 2)
        val out = ByteArray(2)
        batched.readFully(out, 0, 2)
        assertFalse(batched.readFully(out, 0, 2, true))
        assertEquals(C.RESULT_END_OF_INPUT, batched.read(out, 0, 2))
        val threw = try { BatchedInput(input()).apply { skipFully(bytes.size - 1) }.readFully(out, 0, 2); false } catch (e: EOFException) { true }
        assertEquals(true, threw)
    }
}
