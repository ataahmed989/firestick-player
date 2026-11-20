package app.fireplayer.shell

import app.fireplayer.ui.GlassStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RailShapeTest {

    // The rail's four states at rest: (wide, attach).
    private val narrowWindow = 0f to 0f
    private val wideWindow = 1f to 0f
    private val narrowStrip = 0f to 1f
    private val wideStrip = 1f to 1f
    private val states = listOf(narrowWindow, wideWindow, narrowStrip, wideStrip)

    private fun tabsLeft(s: Pair<Float, Float>) = RailShape.tabsLeftDp(s.first, s.second)
    private fun tabsRight(s: Pair<Float, Float>) = RailShape.tabsRightDp(s.first, s.second)
    private fun right(s: Pair<Float, Float>) = RailShape.rightDp(s.first, s.second)
    private fun width(s: Pair<Float, Float>) = RailShape.windowWidthDp(s.first, s.second)

    /** Shares of the way, 0 to 1 inclusive. */
    private val shares = (0..100).map { it / 100f }

    /** A motion run a 60 Hz frame at a time until it has arrived; the values it had on the way. */
    private fun run(motion: RailMotion): List<Float> {
        val seen = ArrayList<Float>()
        var frames = 0
        while (motion.running) {
            motion.step(RailShape.FRAME_MS)
            seen += motion.value
            check(++frames < 1_000) { "the motion never arrives" }
        }
        return seen
    }

    // ---- The shapes at rest (as they were before the motion was made one) -------------------------------------------

    @Test fun collapsedTheIconsStandWhereTheyDidAttachedOrFloating() {
        // The tabs are as far from the view's edges in both, so nothing moves as Live TV is entered or left.
        assertEquals(tabsLeft(narrowStrip), tabsLeft(narrowWindow), 0f)
        assertEquals(NavRail.SIDE_DP.toFloat(), tabsLeft(narrowStrip), 0f)
    }

    @Test fun theCollapsedWindowIsExactlyAsWideAsTheTabs() {
        // 58 dp wide, 7 dp each side: the 44 dp tabs fill the floating window.
        assertEquals(44f, width(narrowWindow), 0f)
        assertEquals(tabsRight(narrowWindow) - tabsLeft(narrowWindow), width(narrowWindow), 0f)
        assertEquals(RailShape.insetXDp(0f), tabsLeft(narrowWindow), 0f)
    }

    @Test fun widenedAsAWindowTheTabsKeepClearOfItsEdges() {
        val side = tabsLeft(wideWindow)
        assertEquals((RailShape.FLOAT_SIDE_DP + NavRail.WINDOW_PAD_DP).toFloat(), side, 0f)
        // The tabs lie inside the window, with the same room each side.
        val tabs = tabsRight(wideWindow) - tabsLeft(wideWindow)
        assertEquals(2f * NavRail.WINDOW_PAD_DP, width(wideWindow) - tabs, 0f)
        assertEquals(NavRail.WINDOW_PAD_DP.toFloat(), right(wideWindow) - tabsRight(wideWindow), 0f)
    }

    @Test fun widenedOnLiveTvTheStripIsAsItWas() {
        assertEquals(NavRail.SIDE_DP.toFloat(), tabsLeft(wideStrip), 0f)
        assertEquals((NavRail.EXPANDED_DP - NavRail.SIDE_DP).toFloat(), tabsRight(wideStrip), 0f)
        // Full width, full height, square.
        assertEquals(NavRail.EXPANDED_DP.toFloat(), right(wideStrip), 0f)
        assertEquals(0f, RailShape.insetXDp(1f), 0f)
        assertEquals(0f, RailShape.insetYDp(1f), 0f)
        assertEquals(0f, RailShape.radiusDp(1f), 0f)
    }

    @Test fun theWindowIsInsetFromTheScreensEdges() {
        assertTrue(RailShape.FLOAT_SIDE_DP > 0)
        assertTrue(RailShape.FLOAT_END_DP > 0)
        assertEquals(RailShape.FLOAT_SIDE_DP.toFloat(), RailShape.insetXDp(0f), 0f)
        assertEquals(RailShape.FLOAT_END_DP.toFloat(), RailShape.insetYDp(0f), 0f)
        // And never reaches into the page, which starts where the collapsed rail ends.
        assertTrue(right(narrowWindow) < NavRail.WIDTH_DP)
        // The strip ends exactly there.
        assertEquals(NavRail.WIDTH_DP.toFloat(), right(narrowStrip), 0f)
    }

    @Test fun theMoveBetweenThemStartsAndEndsExactly() {
        assertEquals(14f, RailShape.between(14f, 0f, 0f), 0f)
        assertEquals(0f, RailShape.between(14f, 0f, 1f), 0f)
        assertEquals(7f, RailShape.between(14f, 0f, 0.5f), 0f)
        assertEquals(1080f, RailShape.between(1064f, 1080f, 1f), 0f)
    }

    @Test fun theWindowHasTheSharedWindowsCornersAndTheStripNone() {
        assertEquals(GlassStyle.RADIUS_POPUP, RailShape.radiusDp(0f), 0f)
        assertEquals(1f, RailShape.outline(0f), 0f)
        assertEquals(0f, RailShape.outline(1f), 0f)
    }

    // ---- Widening and collapsing: one edge that moves ---------------------------------------------------------------

    @Test fun wideningMovesOnlyTheRightEdge() {
        for (attach in listOf(0f, 1f)) {
            var last = RailShape.rightDp(0f, attach)
            for (wide in shares) {
                val r = RailShape.rightDp(wide, attach)
                assertTrue("the edge only ever moves outwards", r >= last)
                last = r
                // Its left edge stays where it is: the shape's width grows by exactly what the right edge moves.
                assertEquals(r - RailShape.insetXDp(attach), RailShape.windowWidthDp(wide, attach), 1e-4f)
            }
        }
        assertEquals(51f, right(narrowWindow), 0f)
        assertEquals(205f, right(wideWindow), 0f)
        assertEquals(58f, right(narrowStrip), 0f)
        assertEquals(212f, right(wideStrip), 0f)
    }

    @Test fun theChosenTabsWhiteGrowsWithTheEdge() {
        for (attach in listOf(0f, 0.5f, 1f)) {
            var last = RailShape.tabsRightDp(0f, attach)
            for (wide in shares) {
                val l = RailShape.tabsLeftDp(wide, attach)
                val r = RailShape.tabsRightDp(wide, attach)
                assertTrue("the white follows the edge out", r >= last)
                last = r
                // Always inside the shape, and never narrower than a collapsed tab.
                assertTrue(l >= RailShape.insetXDp(attach) - 1e-3f)
                assertTrue(r <= RailShape.rightDp(wide, attach) + 1e-3f)
                assertTrue(r - l >= 44f - 1e-3f)
            }
        }
    }

    @Test fun theLabelsComeInWithTheEdgeAndAreGoneCollapsed() {
        assertEquals(0f, RailShape.labelAlpha(0f), 0f)
        assertEquals(1f, RailShape.labelAlpha(1f), 0f)
        assertEquals(0f, RailShape.labelSlideDp(1f), 0f)
        assertEquals(-RailShape.LABEL_SLIDE_DP, RailShape.labelSlideDp(0f), 0f)
        var last = 0f
        for (wide in shares) {
            val a = RailShape.labelAlpha(wide)
            assertTrue(a in 0f..1f)
            assertTrue("they only ever grow clearer as the rail widens", a >= last)
            last = a
            // A few dp from the left, never past their place.
            assertTrue(RailShape.labelSlideDp(wide) in -RailShape.LABEL_SLIDE_DP..0f)
        }
        // Nothing of a label shows before the edge has left the icons behind (a label starts 54 dp in).
        val firstSeen = shares.first { RailShape.labelAlpha(it) > 0f }
        assertTrue(RailShape.rightDp(firstSeen, 0f) > 54f)
        // And they are whole before the motion's soft landing ends.
        assertTrue(RailShape.LABEL_FULL < 1f)
    }

    @Test fun theMarkGrowsFromItsCollapsedSizeToItsWidenedOne() {
        assertEquals(RailShape.MARK_DP / RailShape.MARK_WIDE_DP.toFloat(), RailShape.markScale(0f), 1e-6f)
        assertEquals(1f, RailShape.markScale(1f), 0f)
        assertTrue(RailShape.markScale(0.5f) > RailShape.markScale(0f))
        assertTrue(RailShape.markScale(0.5f) < 1f)
    }

    @Test fun theBlurredPageShowsOnlyWhileTheRailIsWiderThanItsIcons() {
        assertEquals(0f, RailShape.filmAlpha(0f), 0f)
        assertEquals(1f, RailShape.filmAlpha(RailShape.FILM_FULL), 0f)
        assertEquals(1f, RailShape.filmAlpha(1f), 0f)
        // Whole before any label shows: the page's words never show sharp through the labels.
        assertTrue(RailShape.FILM_FULL <= RailShape.LABEL_FROM)
    }

    // ---- Live TV entered and left: the strip and the window, one shape ----------------------------------------------

    @Test fun theStripBecomesTheWindowByMovingItsBoundsAndRoundingItsCorners() {
        var lastInset = -1f
        var lastRadius = -1f
        for (toWindow in shares) {
            val attach = 1f - toWindow
            val inset = RailShape.insetXDp(attach)
            val radius = RailShape.radiusDp(attach)
            assertTrue(inset >= lastInset && radius >= lastRadius)
            lastInset = inset
            lastRadius = radius
            // The corners are never rounder than the shape is wide or high.
            assertTrue(radius <= RailShape.windowWidthDp(0f, attach) / 2f)
            // The outline and the shadow come in with the corners.
            assertEquals(toWindow, RailShape.outline(attach), 1e-6f)
        }
        assertEquals(0f, RailShape.radiusDp(1f), 0f)
        assertEquals(RailShape.RADIUS_DP, RailShape.radiusDp(0f), 0f)
    }

    @Test fun narrowTheIconsNeverMoveAsLiveTvIsEnteredOrLeft() {
        for (attach in shares) assertEquals(0f, RailShape.shiftDp(0f, attach), 0f)
    }

    @Test fun leavingLiveTvWithTheRailWideTheIconsStandStill() {
        // The widened strip becomes the narrow window, both motions in step: the tabs end where they began, and
        // don't wander on the way.
        for (p in shares) assertEquals(0f, RailShape.shiftDp(1f - p, 1f - p), 0f)
    }

    @Test fun theTabsStepInOnlyFromAWidenedWindowsEdge() {
        for (wide in shares) for (attach in shares) {
            val s = RailShape.shiftDp(wide, attach)
            assertTrue(s in 0f..NavRail.WINDOW_PAD_DP.toFloat())
        }
        assertEquals(NavRail.WINDOW_PAD_DP.toFloat(), RailShape.shiftDp(1f, 0f), 0f)
        assertEquals(0f, RailShape.shiftDp(1f, 1f), 0f)
        // Entering Live TV from the widened window, they go back once, never to and fro.
        var last = RailShape.shiftDp(1f, 0f)
        for (p in shares) {
            val s = RailShape.shiftDp(1f - p, p)
            assertTrue(s <= last)
            last = s
        }
    }

    // ---- The curve --------------------------------------------------------------------------------------------------

    @Test fun theCurveStartsAndEndsExactly() {
        assertEquals(0f, RailShape.ease(0f), 0f)
        assertEquals(1f, RailShape.ease(1f), 0f)
        assertEquals(0f, RailShape.ease(-0.5f), 0f)
        assertEquals(1f, RailShape.ease(1.5f), 0f)
    }

    @Test fun theCurveOnlyEverGoesForward() {
        var last = 0f
        for (i in 0..1_000) {
            val v = RailShape.ease(i / 1_000f)
            assertTrue("at $i", v >= last - 1e-5f)
            assertTrue(v in 0f..1f)
            last = maxOf(last, v)
        }
    }

    @Test fun theCurveLeavesFastAndLandsSoftly() {
        // cubic-bezier(.2, 0, 0, 1): half the way in the first fifth of the time.
        assertEquals(0.5f, RailShape.ease(0.2f), 1e-3f)
        // The first frame is a gentle one (no lurch as it sets off).
        assertTrue(RailShape.ease(RailShape.FRAME_MS / RailShape.MOVE_MS) < 0.08f)
        // The last third of the time covers only the last few hundredths of the way.
        assertTrue(RailShape.ease(2f / 3f) > 0.95f)
        // And the last frame moves less than a dp of the widening's 154.
        val lastFrame = 1f - RailShape.ease(1f - RailShape.FRAME_MS / RailShape.MOVE_MS)
        assertTrue(lastFrame * (NavRail.EXPANDED_DP - NavRail.WIDTH_DP) < 1f)
    }

    @Test fun theMotionTakesAboutAQuarterOfASecond() {
        assertTrue(RailShape.MOVE_MS in 240L..320L)
        assertEquals(RailShape.MOVE_MS.toFloat(), RailShape.moveMs(1f), 0f)
        assertEquals(0f, RailShape.moveMs(0f), 0f)
    }

    // ---- A motion ---------------------------------------------------------------------------------------------------

    @Test fun aMotionArrivesExactlyAndInItsTime() {
        val m = RailMotion(0f)
        assertFalse(m.running)
        m.aim(1f)
        assertTrue(m.running)
        assertEquals("aiming moves nothing by itself", 0f, m.value, 0f)
        val seen = run(m)
        assertEquals(1f, m.value, 0f)
        assertEquals(1f, m.target, 0f)
        // 280 ms at 60 frames a second.
        assertEquals(17, seen.size)
        // Forward all the way, no step back and no overshoot.
        var last = 0f
        for (v in seen) {
            assertTrue(v >= last && v <= 1f)
            last = v
        }
    }

    @Test fun noFrameOfTheWideningJumps() {
        val m = RailMotion(0f)
        m.aim(1f)
        var last = 0f
        var biggest = 0f
        for (v in run(m)) {
            biggest = maxOf(biggest, v - last)
            last = v
        }
        // The fastest frame covers well under a third of the way (it was the whole of it in one step before).
        assertTrue("the fastest frame moves $biggest of the way", biggest < 0.3f)
    }

    @Test fun turnedRoundItSetsOffFromWhereItIs() {
        val m = RailMotion(0f)
        m.aim(1f)
        repeat(4) { m.step(RailShape.FRAME_MS) }
        val at = m.value
        assertTrue(at > 0f && at < 1f)
        m.aim(0f)
        assertEquals("turning round moves nothing by itself", at, m.value, 0f)
        m.step(RailShape.FRAME_MS)
        // The first frame back is a small step back from there: never a jump to an end.
        assertTrue(m.value < at)
        assertTrue(at - m.value < 0.15f)
        var last = m.value
        for (v in run(m)) {
            assertTrue(v <= last)
            last = v
        }
        assertEquals(0f, m.value, 0f)
    }

    @Test fun turnedRoundPartWayItTakesPartOfTheTimeButNeverRushes() {
        assertEquals(RailShape.MOVE_MS * 0.75f, RailShape.moveMs(0.75f), 1e-3f)
        assertEquals(RailShape.MOVE_MS * RailShape.SHORT_MOVE, RailShape.moveMs(0.1f), 1e-3f)
        val m = RailMotion(0f)
        m.aim(1f)
        m.step(RailShape.FRAME_MS)
        m.aim(0f)
        // Barely off its start: half the full time, not a frame or two.
        assertEquals(9, run(m).size)
    }

    @Test fun aimedWhereItIsAlreadyGoingItCarriesOn() {
        val m = RailMotion(0f)
        m.aim(1f)
        repeat(5) { m.step(RailShape.FRAME_MS) }
        val at = m.value
        m.aim(1f)
        m.step(RailShape.FRAME_MS)
        val fresh = RailMotion(0f)
        fresh.aim(1f)
        repeat(6) { fresh.step(RailShape.FRAME_MS) }
        assertTrue(m.value > at)
        assertEquals(fresh.value, m.value, 0f)
    }

    @Test fun snappedItIsThereAtOnce() {
        val m = RailMotion(0f)
        m.aim(1f)
        m.step(RailShape.FRAME_MS)
        m.snap(m.target)
        assertFalse(m.running)
        assertEquals(1f, m.value, 0f)
        m.step(RailShape.FRAME_MS)
        assertEquals(1f, m.value, 0f)
    }

    @Test fun aSlowFrameIsNotJumpedOver() {
        assertEquals(RailShape.MAX_STEP_MS, RailShape.stepMs(500f), 0f)
        assertEquals(RailShape.FRAME_MS, RailShape.stepMs(RailShape.FRAME_MS), 0f)
        assertEquals(0f, RailShape.stepMs(-3f), 0f)
        // A frame that took half a second (a page's first drawing) moves the rail on by two frames, not to its end.
        val m = RailMotion(0f)
        m.aim(1f)
        m.step(RailShape.FRAME_MS)
        m.step(RailShape.stepMs(500f))
        val twoFrames = RailMotion(0f)
        twoFrames.aim(1f)
        repeat(3) { twoFrames.step(RailShape.FRAME_MS) }
        assertEquals(twoFrames.value, m.value, 1e-4f)
        assertTrue(m.running)
    }

    @Test fun bothMotionsTogetherEndExactlyInTheFinalState() {
        // Live TV opened from the widened window: it collapses while it becomes the strip. And every other pairing,
        // one started a few frames into the other, one turned round on the way.
        for ((from, to) in listOf(wideWindow to narrowStrip, wideStrip to narrowWindow, narrowWindow to wideStrip, narrowStrip to wideWindow)) {
            for (lag in 0..6) {
                val wide = RailMotion(from.first)
                val attach = RailMotion(from.second)
                attach.aim(to.second)
                repeat(lag) { attach.step(RailShape.FRAME_MS) }
                wide.aim(1f - to.first)
                wide.step(RailShape.FRAME_MS)
                wide.aim(to.first)
                var frames = 0
                while (wide.running || attach.running) {
                    wide.step(RailShape.FRAME_MS)
                    attach.step(RailShape.FRAME_MS)
                    val right = RailShape.rightDp(wide.value, attach.value)
                    // On the way the shape is always one between the four: nothing outside them.
                    assertTrue(right in 51f..212f)
                    assertTrue(RailShape.radiusDp(attach.value) in 0f..RailShape.RADIUS_DP)
                    check(++frames < 1_000)
                }
                assertEquals(to.first, wide.value, 0f)
                assertEquals(to.second, attach.value, 0f)
                assertEquals(right(to), RailShape.rightDp(wide.value, attach.value), 0f)
                assertEquals(tabsLeft(to), RailShape.tabsLeftDp(wide.value, attach.value), 0f)
                assertEquals(tabsRight(to), RailShape.tabsRightDp(wide.value, attach.value), 0f)
            }
        }
    }

    @Test fun everyStateAtRestIsOneOfTheFour() {
        for (s in states) {
            assertTrue(RailShape.labelAlpha(s.first) == 0f || RailShape.labelAlpha(s.first) == 1f)
            assertTrue(RailShape.outline(s.second) == 0f || RailShape.outline(s.second) == 1f)
            assertTrue(RailShape.filmAlpha(s.first) == 0f || RailShape.filmAlpha(s.first) == 1f)
        }
    }

    // ---- The page's picture behind the widened rail ------------------------------------------------------------------

    @Test fun thePageIsNotCopiedAgainUntilTheWideningHasEnded() {
        // Held off for the whole motion and a little more (slow frames stretch it).
        assertTrue(RailShape.FILM_HOLD_MS > RailShape.MOVE_MS)
        // And left alone, the next copy of a page that keeps changing comes later than the motion ends anyway.
        val nextCopy = GlassStyle.nextCaptureAt(now = 1_000L, capturedAt = 1_000L, perSec = 8) - 1_000L
        assertTrue(nextCopy > RailShape.MOVE_MS)
    }
}
