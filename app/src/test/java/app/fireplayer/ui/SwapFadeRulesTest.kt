package app.fireplayer.ui

import app.fireplayer.ui.SwapFadeRules.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** When changing words crossfade, and for how long. */
class SwapFadeRulesTest {

    @Test fun aChangeAtRestFadesTheOldWordsOutFirst() {
        assertEquals(Step.OUT, SwapFadeRules.step(fadingOut = false, sinceLastMs = 5_000, shown = true))
        // A second press a moment later turns the fade round from where it is.
        assertEquals(Step.OUT, SwapFadeRules.step(fadingOut = false, sinceLastMs = SwapFadeRules.RAPID_MS, shown = true))
    }

    @Test fun aHeldKeysChangesAreSwappedAtOnce() {
        // Repeats come every 50 to 80 ms: no fade per row, the words keep up with the focus.
        assertEquals(Step.NOW, SwapFadeRules.step(fadingOut = false, sinceLastMs = 50, shown = true))
        assertEquals(Step.NOW, SwapFadeRules.step(fadingOut = false, sinceLastMs = 80, shown = true))
        assertEquals(Step.NOW, SwapFadeRules.step(fadingOut = false, sinceLastMs = 0, shown = true))
    }

    @Test fun aChangeWhileTheOldWordsFadeOutWaitsForTheSwap() {
        assertEquals(Step.WAIT, SwapFadeRules.step(fadingOut = true, sinceLastMs = 30, shown = true))
        assertEquals(Step.WAIT, SwapFadeRules.step(fadingOut = true, sinceLastMs = 5_000, shown = true))
    }

    @Test fun unseenItIsJustPutInPlace() {
        assertEquals(Step.NOW, SwapFadeRules.step(fadingOut = false, sinceLastMs = 5_000, shown = false))
        assertEquals(Step.NOW, SwapFadeRules.step(fadingOut = true, sinceLastMs = 5_000, shown = false))
    }

    @Test fun theWholeCrossfadeIsShort() {
        val whole = SwapFadeRules.OUT_MS + SwapFadeRules.IN_MS
        assertTrue(whole in 150L..250L)
        // The old words go faster than the new ones land.
        assertTrue(SwapFadeRules.OUT_MS < SwapFadeRules.IN_MS)
        // A first key repeat (about half a second after the press) finds the first crossfade over.
        assertTrue(whole < 400L)
    }

    @Test fun aFadeCarriesOnFromWhereItIs() {
        assertEquals(150L, SwapFadeRules.span(150L, 0f, 1f))
        assertEquals(75L, SwapFadeRules.span(150L, 0.5f, 1f))
        assertEquals(35L, SwapFadeRules.span(70L, 0.5f, 0f))
        assertEquals(0L, SwapFadeRules.span(150L, 1f, 1f))
        assertEquals(0L, SwapFadeRules.span(70L, 0f, 0f))
    }
}
