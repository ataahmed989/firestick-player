package app.fireplayer.live.ui

import app.fireplayer.live.ui.PipRules.Arrow
import app.fireplayer.live.ui.PipRules.BackAct
import app.fireplayer.live.ui.PipRules.Button
import app.fireplayer.live.ui.PipRules.Move
import app.fireplayer.live.ui.PipRules.Page
import app.fireplayer.live.ui.PipRules.Step
import app.fireplayer.ui.SettingsCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live picture on Home and Search: the user's answers of 2026-11-19 (DECISIONS.md). */
class PipRulesTest {

    @Test fun onlyHomeAndSearchShowThePicture() {
        assertEquals(listOf(Page.SEARCH, Page.HOME), Page.entries.filter { PipRules.keepsPicture(it) })
    }

    @Test fun leavingLiveTvKeepsAPlayingChannelForHomeAndSearchOnly() {
        assertTrue(PipRules.keepOnLeavingLive(enabled = true, to = Page.HOME, playing = true, multiview = false, paused = false))
        assertTrue(PipRules.keepOnLeavingLive(enabled = true, to = Page.SEARCH, playing = true, multiview = false, paused = false))
        for (to in listOf(Page.MOVIES, Page.TV_SHOWS, Page.MY_LIST, Page.SETTINGS)) {
            assertFalse(to.name, PipRules.keepOnLeavingLive(enabled = true, to = to, playing = true, multiview = false, paused = false))
        }
    }

    @Test fun switchedOffNothingPlayingOrMultiviewLeavingLiveTvStopsTheChannelAsBefore() {
        assertFalse(PipRules.keepOnLeavingLive(enabled = false, to = Page.HOME, playing = true, multiview = false, paused = false))
        assertFalse(PipRules.keepOnLeavingLive(enabled = true, to = Page.HOME, playing = false, multiview = false, paused = false))
        assertFalse(PipRules.keepOnLeavingLive(enabled = true, to = Page.SEARCH, playing = true, multiview = true, paused = false))
    }

    @Test fun aChannelPausedOnLiveTvDoesNotFollowToHomeOrSearch() {
        // Still in the corner with no Play on the strip (and Play/Pause unused on Home): it stops, remembered.
        for (to in listOf(Page.HOME, Page.SEARCH)) {
            assertFalse(to.name, PipRules.keepOnLeavingLive(enabled = true, to = to, playing = true, multiview = false, paused = true))
        }
    }

    @Test fun whileThePicturePlaysOtherTabsStopItAndLiveTvTakesItBack() {
        assertEquals(Move.KEEP, PipRules.whenGoingTo(Page.HOME))
        assertEquals(Move.KEEP, PipRules.whenGoingTo(Page.SEARCH))
        assertEquals(Move.LIVE, PipRules.whenGoingTo(Page.LIVE))
        for (to in listOf(Page.MOVIES, Page.TV_SHOWS, Page.MY_LIST, Page.SETTINGS)) assertEquals(to.name, Move.STOP, PipRules.whenGoingTo(to))
    }

    @Test fun theStripShowsOnlyWhileItHasFocus() {
        assertTrue(PipRules.stripShown(focusInStrip = true))
        assertFalse(PipRules.stripShown(focusInStrip = false))
    }

    @Test fun focusArrivesOnVolumeAndTheButtonsAreInTheMockUpsOrder() {
        assertEquals(Button.VOLUME, PipRules.ARRIVES_ON)
        assertEquals(listOf(Button.VOLUME, Button.FULL_SCREEN, Button.CLOSE), Button.entries.toList())
    }

    @Test fun arrowsMoveAlongTheStripAndOutLeftOrDown() {
        assertEquals(Step.To(Button.FULL_SCREEN), PipRules.step(Button.VOLUME, Arrow.RIGHT))
        assertEquals(Step.To(Button.CLOSE), PipRules.step(Button.FULL_SCREEN, Arrow.RIGHT))
        assertEquals(Step.Stay, PipRules.step(Button.CLOSE, Arrow.RIGHT))
        assertEquals(Step.To(Button.FULL_SCREEN), PipRules.step(Button.CLOSE, Arrow.LEFT))
        assertEquals(Step.To(Button.VOLUME), PipRules.step(Button.FULL_SCREEN, Arrow.LEFT))
        // Left from Volume: back to where focus came from (the banner's Details, Search's bin).
        assertEquals(Step.OutLeft, PipRules.step(Button.VOLUME, Arrow.LEFT))
        for (b in Button.entries) {
            assertEquals(Step.Stay, PipRules.step(b, Arrow.UP))
            assertEquals(Step.OutDown, PipRules.step(b, Arrow.DOWN))
        }
    }

    @Test fun backFromTheStripReturnsFocusAndHeldBackOpensFullScreen() {
        assertEquals(BackAct.RETURN_FOCUS, PipRules.back(held = false, inStrip = true, playing = true, pageInFront = true))
        // Held anywhere on the page while the picture plays (sound on or off): full screen.
        assertEquals(BackAct.FULL_SCREEN, PipRules.back(held = true, inStrip = true, playing = true, pageInFront = true))
        assertEquals(BackAct.FULL_SCREEN, PipRules.back(held = true, inStrip = false, playing = true, pageInFront = true))
        // A short Back elsewhere is the page's (or the rail's) as ever.
        assertEquals(BackAct.PAGE, PipRules.back(held = false, inStrip = false, playing = true, pageInFront = true))
    }

    @Test fun heldBackIsThePagesWhenSomethingCoversItOrNothingPlays() {
        assertEquals(BackAct.PAGE, PipRules.back(held = true, inStrip = false, playing = true, pageInFront = false))
        assertEquals(BackAct.PAGE, PipRules.back(held = true, inStrip = false, playing = false, pageInFront = true))
        assertEquals(BackAct.PAGE, PipRules.back(held = false, inStrip = true, playing = false, pageInFront = true))
    }

    @Test fun closeOnHomeFocusesTheFirstRecentChannelAndOnSearchWhereItWas() {
        assertEquals(PipRules.AfterClose.FIRST_RECENT, PipRules.focusAfterClose(Page.HOME))
        assertEquals(PipRules.AfterClose.WHERE_IT_WAS, PipRules.focusAfterClose(Page.SEARCH))
    }

    @Test fun thePictureShowsUnlessCoveredAndFollowsThePageWhereItCan() {
        assertTrue(PipRules.pictureShows(covered = false, shift = 0f, canFollow = true))
        assertTrue(PipRules.pictureShows(covered = false, shift = -120f, canFollow = true))
        assertFalse(PipRules.pictureShows(covered = true, shift = 0f, canFollow = true))
        // Android 6 and older: only at rest.
        assertTrue(PipRules.pictureShows(covered = false, shift = 0f, canFollow = false))
        assertFalse(PipRules.pictureShows(covered = false, shift = -120f, canFollow = false))
    }

    @Test fun theSettingsDefaultsKeepThePictureWithSound() {
        assertTrue(PipRules.KEEP_DEFAULT)
        assertTrue(PipRules.SOUND_DEFAULT)
        assertFalse(PipRules.mutedAtStart(startWithSound = PipRules.SOUND_DEFAULT))
        assertTrue(PipRules.mutedAtStart(startWithSound = false))
    }

    @Test fun theSettingsAreInTheirOwnCategory() {
        assertEquals(
            listOf(SettingsCategory.PICTURE_IN_PICTURE),
            SettingsCategory.entries.filter { PipRules.KEEP_LABEL in it.settings },
        )
        assertEquals(listOf(PipRules.KEEP_LABEL, PipRules.SOUND_LABEL), SettingsCategory.PICTURE_IN_PICTURE.settings)
        assertEquals("Picture-in-picture", SettingsCategory.PICTURE_IN_PICTURE.title)
    }
}
