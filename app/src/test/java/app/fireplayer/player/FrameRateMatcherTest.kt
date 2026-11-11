package app.fireplayer.player

import app.fireplayer.player.FrameRateMatcher.ModeInfo
import app.fireplayer.player.FrameRateMatcher.Rematch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateMatcherTest {

    // The modes a Fire TV Stick 4K Plus offered on the TV it was seen on (some left out).
    private val modes = listOf(
        ModeInfo(1, 1920, 1080, 60f),
        ModeInfo(2, 1920, 1080, 59.94f),
        ModeInfo(3, 3840, 2160, 59.94f),
        ModeInfo(6, 3840, 2160, 60f),
        ModeInfo(10, 3840, 2160, 24f),
        ModeInfo(11, 3840, 2160, 23.976f),
        ModeInfo(14, 1920, 1080, 23.976f),
        ModeInfo(15, 1920, 1080, 24f),
    )

    private fun mode(id: Int) = modes.first { it.id == id }

    @Test
    fun picksTheFilmsModeFromATvAt5994() {
        // The TV came on at 2160p 59.94 Hz in the middle of a 23.976 fps film.
        assertEquals(11, FrameRateMatcher.pickMode(modes, mode(3), 23.976f)?.id)
        assertFalse(FrameRateMatcher.suits(modes, mode(3), 23.976f))
        // The same size is kept.
        assertEquals(14, FrameRateMatcher.pickMode(modes, mode(2), 23.976f)?.id)
    }

    @Test
    fun aModeAlreadyShowingTheRateSuits() {
        // Where the mode is now recorded as asked for, so Android keeps it and a return to the player finds it again.
        assertSame(mode(11), FrameRateMatcher.pickMode(modes, mode(11), 23.976f))
        assertTrue(FrameRateMatcher.suits(modes, mode(11), 23.976f))
        assertTrue(FrameRateMatcher.suits(modes, mode(14), 23.976f))
    }

    @Test
    fun theExactRateFirstThenItsFamilyThenAMultiple() {
        assertEquals(10, FrameRateMatcher.pickMode(modes, mode(3), 24f)?.id)
        // A TV with only 24 Hz still gets it for 23.976.
        val only24 = modes.filter { it.id != 11 }
        assertEquals(10, FrameRateMatcher.pickMode(only24, mode(3), 23.976f)?.id)
        // 29.97 fps on 59.94 Hz is shown twice each, evenly: it suits.
        assertTrue(FrameRateMatcher.suits(modes, mode(3), 29.97f))
        // 25 fps with no 25 or 50 Hz mode: nothing to ask for, which counts as suiting.
        assertNull(FrameRateMatcher.pickMode(modes, mode(3), 25f))
        assertTrue(FrameRateMatcher.suits(modes, mode(3), 25f))
    }

    private fun rematch(
        now: Long = 1_000_000L,
        matchOn: Boolean = true,
        matched: Boolean = true,
        refused: Boolean = false,
        fps: Float = 23.976f,
        suits: Boolean = false,
        switchAt: Long = 0L,
        askedAt: Long = 0L,
    ) = FrameRateMatcher.rematch(now, matchOn, matched, refused, fps, suits, switchAt, askedAt, settleMs = 3_000L, againMs = 10_000L)

    @Test
    fun theTvTurnedOnMinutesLaterIsAskedAgain() {
        // Matched (or found suiting) at 11:58, the TV on at 59.94 Hz at 12:03.
        val now = 12 * 3_600_000L + 3 * 60_000L
        assertSame(Rematch.Ask, rematch(now = now, switchAt = now - 5 * 60_000L, askedAt = now - 5 * 60_000L))
        // Never asked (the mode suited at the start): asked now.
        assertSame(Rematch.Ask, rematch(now = now))
    }

    @Test
    fun nothingWhenItSuitsOrIsntWanted() {
        assertSame(Rematch.Leave, rematch(suits = true))
        assertSame(Rematch.Leave, rematch(matchOn = false))
        assertSame(Rematch.Leave, rematch(matched = false))
        assertSame(Rematch.Leave, rematch(refused = true))
        assertSame(Rematch.Leave, rematch(fps = 0f))
        assertSame(Rematch.Leave, rematch(fps = -1f))
    }

    @Test
    fun thePlayersOwnSwitchSettlesFirst() {
        val now = 1_000_000L
        val wait = rematch(now = now, switchAt = now - 1_000L, askedAt = now - 1_000L)
        assertTrue(wait is Rematch.Wait)
        assertEquals(2_000L, (wait as Rematch.Wait).ms)
    }

    @Test
    fun aTvThatLeavesTheModeRightAfterIsAskedAgainOnceTheTimeIsUp() {
        // Asked when the TV came on, and Fire OS put its own mode back 4 s later (a second HDMI hotplug): not given up on.
        val now = 1_000_000L
        val soon = rematch(now = now, switchAt = now - 4_000L, askedAt = now - 4_000L)
        assertTrue(soon is Rematch.Wait)
        assertEquals(6_000L, (soon as Rematch.Wait).ms)
        assertEquals(1L, (rematch(now = now, switchAt = now - 9_999L, askedAt = now - 9_999L) as Rematch.Wait).ms)
        assertSame(Rematch.Ask, rematch(now = now, switchAt = now - 10_000L, askedAt = now - 10_000L))
        // The film's own first ask counts the same: a soundbar turned on just after play is asked through.
        assertEquals(8_000L, (rematch(now = now, askedAt = now - 2_000L) as Rematch.Wait).ms)
        // Once the most asks are made for the video, nothing more.
        assertSame(Rematch.Leave, rematch(now = now, refused = true, switchAt = now - 4_000L, askedAt = now - 4_000L))
    }
}
