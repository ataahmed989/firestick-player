package app.fireplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AspectSamplesTest {
    private val w = AspectSamples.COPY_WIDTH
    private val h = AspectSamples.COPY_HEIGHT
    private val wide = AspectSamples.frameRatio(3840, 2160, 1f)

    /** A copy of a 16:9 frame: a lit picture, [top] and [bottom] lines and [left] and [right] columns of black around it. */
    private fun copy(top: Int, bottom: Int = top, left: Int = 0, right: Int = left, lit: Int = 0xFF7A6E60.toInt()): BlackBars.Look {
        val px = IntArray(w * h)
        for (y in top until h - bottom) for (x in left until w - right) px[y * w + x] = lit
        return BlackBars.measure(px, w, h)
    }

    /** The lines of bar a film of [ratio] has at each end of a 16:9 frame copied at [h] lines. */
    private fun barFor(ratio: Double): Int = Math.round((h - h * wide / ratio) / 2).toInt()

    // --- Where the shape comes from

    @Test
    fun aSixteenByNineFrameIsCopiedAndAnyOtherIsItsOwnShape() {
        assertEquals(16.0 / 9.0, wide, 1e-9)
        assertTrue(AspectSamples.needsCopy(wide))
        assertTrue(AspectSamples.needsCopy(AspectSamples.frameRatio(1920, 1080, 1f)))
        // Anamorphic pixels: what's shown counts.
        assertTrue(AspectSamples.needsCopy(AspectSamples.frameRatio(1440, 1080, 4f / 3f)))
        // Encoded at its own shape: no bars to look for.
        assertEquals(2.388, AspectSamples.frameRatio(3840, 1608, 1f), 0.001)
        assertFalse(AspectSamples.needsCopy(AspectSamples.frameRatio(3840, 1608, 1f)))
        assertFalse(AspectSamples.needsCopy(AspectSamples.frameRatio(3840, 2022, 1f)))
        assertFalse(AspectSamples.needsCopy(AspectSamples.frameRatio(3840, 2076, 1f)))
        assertEquals(0.0, AspectSamples.frameRatio(0, 2160, 1f), 0.0)
    }

    // --- What a copy says

    @Test
    fun barsInTheFileGiveTheFilmsShape() {
        assertEquals(2.39, AspectSamples.ratioOf(copy(barFor(2.39)), wide)!!, 0.02)
        assertEquals(1.90, AspectSamples.ratioOf(copy(barFor(1.90)), wide)!!, 0.015)
        assertEquals(1.85, AspectSamples.ratioOf(copy(barFor(1.85)), wide)!!, 0.015)
        // 1.85 and 1.90 come apart.
        assertTrue(AspectSamples.ratioOf(copy(barFor(1.90)), wide)!! - AspectSamples.ratioOf(copy(barFor(1.85)), wide)!! > 0.03)
        // The picture filling the frame: its shape.
        assertEquals(wide, AspectSamples.ratioOf(copy(0), wide)!!, 1e-9)
        // A line's difference between the two bars (where bar and picture blend) is still the same film.
        assertEquals(2.39, AspectSamples.ratioOf(copy(barFor(2.39), barFor(2.39) + 1), wide)!!, 0.03)
        // An old 4:3 film between bars at the sides.
        assertEquals(4.0 / 3.0, AspectSamples.ratioOf(copy(0, left = 16), wide)!!, 0.02)
        // A ragged column of black at each side is the encode's, not a narrower film.
        assertEquals(1.90, AspectSamples.ratioOf(copy(barFor(1.90), left = 1), wide)!!, 0.015)
    }

    @Test
    fun aCopyThatCouldMisleadSaysNothing() {
        // Black all over: an opening, a fade.
        assertNull(AspectSamples.ratioOf(copy(top = h), wide))
        // A dark scene: the picture next to the frame's edges isn't lit across.
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) if (x in 50..77 || y in 130..157) px[y * w + x] = 0xFF908070.toInt()
        assertNull(AspectSamples.ratioOf(BlackBars.measure(px, w, h), wide))
        // A dark sky above (the top "bar" much deeper than the bottom one), or writing in one bar.
        assertNull(AspectSamples.ratioOf(copy(80, barFor(2.39)), wide))
        val subtitled = IntArray(w * h)
        for (y in barFor(2.39) until h - barFor(2.39)) for (x in 0 until w) subtitled[y * w + x] = 0xFF7A6E60.toInt()
        for (x in 40..88) subtitled[(h - 12) * w + x] = 0xFFFFFFFF.toInt()
        assertNull(AspectSamples.ratioOf(BlackBars.measure(subtitled, w, h), wide))
        // A lit shape in the middle of a dark scene, black all round.
        assertNull(AspectSamples.ratioOf(copy(60, left = 20), wide))
        // More bar than picture isn't believed.
        assertNull(AspectSamples.ratioOf(copy(80), wide))
        assertNull(AspectSamples.ratioOf(copy(barFor(2.39)), 0.0))
    }

    // --- When to look

    @Test
    fun looksComeAtTheirTimesOfPlayingOnly() {
        val plan = AspectSamples()
        var now = 1_000L
        fun tick(ms: Long, playing: Boolean = true): Boolean {
            var due = false
            val end = now + ms
            while (now < end) {
                now += 200
                due = plan.due(now, playing)
                if (due) break
            }
            return due
        }
        assertFalse(tick(19_000))
        // Paused: no time played.
        assertFalse(tick(60_000, playing = false))
        assertTrue(tick(2_000))
        // Due until taken.
        assertTrue(plan.due(now + 200, true))
        plan.took(2.39, 600_000)
        assertFalse(tick(30_000))
        assertTrue(tick(15_000))
        plan.took(null, 640_000)
        // A look that told nothing is one look fewer: it's not tried again.
        assertEquals(listOf(AspectSample(2.39, 600_000)), plan.samples)
        for (at in AspectSamples.PLAN_MS.drop(2)) {
            assertTrue(tick(at))
            plan.took(1.90, at)
        }
        assertTrue(plan.done)
        assertFalse(tick(3_600_000))
        assertEquals(AspectSamples.PLAN_MS.size - 1, plan.samples.size)
        plan.reset()
        assertTrue(plan.samples.isEmpty())
        assertFalse(plan.done)
    }

    // --- Kept with the copy's measured facts

    @Test
    fun theSamplesAreKeptWithWhatWasMeasuredAndReadBack() {
        val samples = listOf(AspectSample(1.9, 60_000), AspectSample(2.39, 120_000))
        assertEquals("1.900@60000;2.390@120000", AspectSample.encode(samples))
        assertEquals(samples, AspectSample.decode(AspectSample.encode(samples)))
        assertTrue(AspectSample.decode("").isEmpty())
        assertTrue(AspectSample.decode(null).isEmpty())
        assertEquals(listOf(AspectSample(2.0, 5)), AspectSample.decode("x@1;1.9@;@4;2.0@5;99@6"))
        val spec = MediaSpec(videoCodec = "HEVC", width = 3840, height = 2160, videoBps = 41_000_000, measuredSecs = 60, aspects = samples)
        assertEquals(spec, MediaSpec.decode(MediaSpec.encode(spec)))
        // What was kept before the shapes were reads with none.
        assertTrue(MediaSpec.decode(MediaSpec.encode(spec).substringBeforeLast('|'))!!.aspects.isEmpty())
    }

    @Test
    fun anotherPlayAddsWhatItSawElsewhereInTheFilm() {
        val kept = listOf(AspectSample(1.78, 30_000), AspectSample(1.9, 600_000))
        val now = listOf(AspectSample(1.9, 40_000), AspectSample(2.39, 4_000_000))
        // The same part of the film: this play's look stands for it.
        assertEquals(
            listOf(AspectSample(1.9, 40_000), AspectSample(1.9, 600_000), AspectSample(2.39, 4_000_000)),
            AspectSample.merged(kept, now),
        )
        assertEquals(kept, AspectSample.merged(kept, emptyList()))
        assertEquals(now, AspectSample.merged(emptyList(), now))
        val many = (1..20).map { AspectSample(1.9, it * 100_000L) }
        assertEquals(AspectSample.MAX_KEPT, AspectSample.merged(many, now).size)
        // A play that looked at nothing keeps what was seen before, however the rates are merged.
        val before = MediaSpec(videoBps = 40_000_000, measuredSecs = 60, aspects = kept)
        assertEquals(kept, MediaSpec.merged(before, MediaSpec(videoBps = 42_000_000, measuredSecs = 60)).aspects)
        assertEquals(kept, MediaSpec.merged(before, MediaSpec(videoBps = 42_000_000)).aspects)
        assertEquals(now, MediaSpec.merged(null, MediaSpec(aspects = now)).aspects)
    }
}
