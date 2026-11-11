package app.fireplayer.player

import app.fireplayer.player.SkipRules.IntroDbSegment
import app.fireplayer.player.SkipRules.Part
import app.fireplayer.player.SkipRules.Pick
import app.fireplayer.player.SkipRules.SkipDbSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipRulesTest {
    private val length = 47 * 60_000L

    private fun found(p: Pick) = p as Pick.Found

    @Test
    fun exactAndConfidentIsUsed() {
        val p = SkipRules.skipDb(Part.INTRO, SkipDbSegment(61_000, 91_000, "exact", 0, 0.9), length)
        assertEquals(61_000L, found(p).start)
        assertEquals(91_000L, found(p).end)
    }

    @Test
    fun shiftedOnlyWhenSureAndSmall() {
        assertTrue(SkipRules.skipDb(Part.INTRO, SkipDbSegment(61_000, 91_000, "shifted", -8_000, 0.8), length) is Pick.Found)
        assertTrue(SkipRules.skipDb(Part.INTRO, SkipDbSegment(61_000, 91_000, "shifted", -8_000, 0.6), length) is Pick.Rejected)
        assertTrue(SkipRules.skipDb(Part.INTRO, SkipDbSegment(61_000, 91_000, "shifted", 20_000, 0.9), length) is Pick.Rejected)
    }

    @Test
    fun aDifferentCutIsNeverUsed() {
        // The live answer for a file 11 minutes longer than the one timed: out-of-range, left out, not stretched.
        assertTrue(SkipRules.skipDb(Part.INTRO, SkipDbSegment(229_500, 246_500, "out-of-range", -680_000, 0.6), length) is Pick.Rejected)
        assertTrue(SkipRules.skipDb(Part.INTRO, SkipDbSegment(229_500, 246_500, "agnostic", 0, 1.0), length) is Pick.Rejected)
    }

    @Test
    fun noneMissingAndBadTimesAreToldApart() {
        assertTrue(SkipRules.skipDb(Part.RECAP, null, length) is Pick.Missing)
        assertTrue(SkipRules.skipDb(Part.RECAP, SkipDbSegment(0, 0, "exact", 0, 0.9), length) is Pick.None)
        assertTrue(SkipRules.skipDb(Part.INTRO, SkipDbSegment(91_000, 61_000, "exact", 0, 0.9), length) is Pick.Rejected)
        assertTrue(SkipRules.skipDb(Part.OUTRO, SkipDbSegment(length - 60_000, length + 30_000, "exact", 0, 0.9), length) is Pick.Rejected)
        // An intro late in the file, or credits early in it, aren't what they say.
        assertTrue(SkipRules.skipDb(Part.INTRO, SkipDbSegment(30 * 60_000L, 31 * 60_000L, "exact", 0, 0.9), length) is Pick.Rejected)
        assertTrue(SkipRules.skipDb(Part.OUTRO, SkipDbSegment(10 * 60_000L, 11 * 60_000L, "exact", 0, 0.9), length) is Pick.Rejected)
    }

    @Test
    fun introDbNeedsTmdbsLengthToAgree() {
        val seg = IntroDbSegment(2_500, 58_000, 0.92)
        assertTrue(SkipRules.introDb(Part.INTRO, seg, false, SkipRules.runtimeAgrees(47, length), length) is Pick.Found)
        assertTrue(SkipRules.introDb(Part.INTRO, seg, false, SkipRules.runtimeAgrees(58, length), length) is Pick.Rejected)
        assertTrue(SkipRules.introDb(Part.INTRO, seg, false, SkipRules.runtimeAgrees(0, length), length) is Pick.Rejected)
        assertTrue(SkipRules.introDb(Part.INTRO, null, true, true, length) is Pick.None)
        assertTrue(SkipRules.introDb(Part.INTRO, null, false, true, length) is Pick.Missing)
    }

    @Test
    fun introDbCreditsEndingWithTheFileVouchForIt() {
        // X-Men '97 S1E1: TMDB has no length for it; IntroDB's credits end at 1830.17 s, the file is 1830 s long.
        val file = 1_830_000L
        assertTrue(SkipRules.outroAnchors(IntroDbSegment(1_712_510, 1_830_170, 1.0), file))
        assertTrue(SkipRules.introDb(Part.INTRO, IntroDbSegment(4_000, 75_000, 1.0), false, SkipRules.outroAnchors(IntroDbSegment(1_712_510, 1_830_170, 1.0), file), file) is Pick.Found)
        // A file 40 s longer (an ad bumper, another cut): not vouched for.
        assertTrue(!SkipRules.outroAnchors(IntroDbSegment(1_712_510, 1_830_170, 1.0), file + 40_000))
        assertTrue(!SkipRules.outroAnchors(null, file))
    }

    @Test
    fun introDbFillsOnlyWhatSkipDbLeftOpen() {
        val skip = mapOf(
            Part.INTRO to SkipRules.skipDb(Part.INTRO, SkipDbSegment(61_000, 91_000, "exact", 0, 0.9), length),
            Part.RECAP to Pick.None,
            Part.OUTRO to Pick.Rejected("out-of-range"),
            Part.PREVIEW to Pick.Missing,
        )
        val intro = mapOf(
            Part.INTRO to Pick.Found(1_000, 40_000),
            Part.RECAP to Pick.Found(0, 30_000),
            Part.OUTRO to Pick.Found(length - 60_000, length),
            Part.PREVIEW to Pick.Missing,
        )
        val both = SkipRules.combine(skip, intro)
        assertEquals(61_000L, found(both[Part.INTRO]!!).start) // SkipDB's kept
        assertTrue(both[Part.RECAP] is Pick.None) // SkipDB's "none" respected
        assertEquals(length - 60_000, found(both[Part.OUTRO]!!).start) // filled in
    }

    @Test
    fun creditsToTheEndAllowExitAndTheEndCard() {
        val s = SkipRules.segments(mapOf(Part.OUTRO to Pick.Found(length - 60_000, length)), length, "skipdb")!!
        assertEquals(length - 60_000, s.endCardAt)
        assertEquals(length - 60_000, s.exitAt)
        assertNull(s.afterCredits)
    }

    @Test
    fun aSceneAfterTheCreditsIsKept() {
        // Credits end two minutes before the file does: a scene follows. No end card, no Exit; Skip credits jumps to it.
        val s = SkipRules.segments(mapOf(Part.OUTRO to Pick.Found(length - 5 * 60_000L, length - 2 * 60_000L)), length, "skipdb")!!
        assertNull(s.endCardAt)
        assertNull(s.exitAt)
        assertEquals("Skip credits", s.skip(length - 4 * 60_000L)!!.label)
        assertEquals(length - 2 * 60_000L, s.skip(length - 4 * 60_000L)!!.target)
        // A preview after the credits counts too.
        val p = SkipRules.segments(mapOf(
            Part.OUTRO to Pick.Found(length - 60_000, length),
            Part.PREVIEW to Pick.Found(length - 20_000, length),
        ), length, "introdb")!!
        assertNull(p.exitAt)
        assertEquals(length - 20_000, p.afterCredits)
    }

    @Test
    fun aShortTailMakesExitUncertain() {
        // Credits stop 10 s before the end: too short to be sure there's a scene, too long to be sure there isn't.
        val s = SkipRules.segments(mapOf(Part.OUTRO to Pick.Found(length - 70_000, length - 10_000)), length, "skipdb")!!
        assertNull(s.exitAt)
        assertEquals(length - 70_000, s.endCardAt)
    }

    @Test
    fun skipButtonsOnlyInsideTheirPart() {
        val s = SkipRules.segments(mapOf(
            Part.RECAP to Pick.Found(0, 40_000),
            Part.INTRO to Pick.Found(61_000, 91_000),
        ), length, "skipdb")!!
        assertEquals("Skip recap", s.skip(10_000)!!.label)
        assertEquals(40_000L, s.skip(10_000)!!.target)
        assertNull(s.skip(50_000))
        assertEquals("Skip intro", s.skip(61_000)!!.label)
        assertEquals(91_000L, s.skip(70_000)!!.target)
        // The last two seconds: not worth a jump. And after a seek past it, no button.
        assertNull(s.skip(89_500))
        assertNull(s.skip(95_000))
    }

    @Test
    fun nothingFoundIsNull() {
        assertNull(SkipRules.segments(mapOf(Part.INTRO to Pick.Missing, Part.OUTRO to Pick.Rejected("x")), length, "skipdb"))
    }
}
