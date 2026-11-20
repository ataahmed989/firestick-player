package app.fireplayer.live.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Live TV page's widths across the 960 dp screen, with the groups column open and in the grid. */
class LiveWidthsTest {

    @Test fun theOpenColumnFitsRailColumnMiniatureAndDetailsOnTheScreen() {
        // Rail 58 + column 224 + 12, miniature 368 + 18, details, margin 12.
        assertEquals(294, LiveWidths.openInset())
        assertEquals(268, LiveWidths.detailsOpen())
        assertEquals(
            LiveWidths.SCREEN,
            LiveWidths.RAIL + LiveWidths.COLUMN + LiveWidths.GAP + LiveWidths.MINI + LiveWidths.DETAILS_GAP +
                LiveWidths.detailsOpen() + LiveWidths.GAP,
        )
        // The details keep about the width they had beside the old rail (266 dp): their lines fit as before.
        assertTrue(LiveWidths.detailsOpen() >= 266)
    }

    @Test fun theDetailsLogoIsLargeAndLeavesRoomForTheNameBesideIt() {
        // Half as tall again as a guide row's 28 dp tile (it was 26 dp), about 2 to 1 as in the concept.
        assertEquals(42, LiveWidths.DETAIL_LOGO_H)
        assertTrue(LiveWidths.DETAIL_LOGO_W.toFloat() / LiveWidths.DETAIL_LOGO_H in 1.8f..2.1f)
        // The logo's box inside the tile keeps real room each side.
        assertTrue(LiveWidths.DETAIL_LOGO_W - 2 * LiveWidths.DETAIL_LOGO_PAD_X >= 64f)
        assertTrue(LiveWidths.DETAIL_LOGO_H - 2 * LiveWidths.DETAIL_LOGO_PAD_Y >= 30f)
        // Tile + gap + name + star's room fill the details exactly; "USA HBO Comedy*" at 16 sp is about 125 dp.
        assertEquals(140, LiveWidths.detailNameOpen())
        assertEquals(
            LiveWidths.detailsOpen(),
            LiveWidths.DETAIL_LOGO_W + LiveWidths.DETAIL_NAME_GAP + LiveWidths.detailNameOpen() + LiveWidths.DETAIL_STAR,
        )
    }

    @Test fun theDetailsStillFitBesideTheMiniatureUnderTheLargerLogo() {
        // Line heights at 1.2 x the size: the top line, the title (25 sp), where it is (14.5 sp), the times (15 sp) and
        // three lines of description (14.5 sp), with the spaces between, inside the miniature's 207 dp.
        val lines = LiveWidths.DETAIL_LOGO_H + LiveWidths.DETAIL_TITLE_GAP + 25 * 1.2f + 2 + 14.5f * 1.2f + 8 + 15 * 1.2f + 8 + 3 * 14.5f * 1.2f
        assertTrue(lines <= 207f)
        // Two lines of the name (16 sp) fit beside the logo.
        assertTrue(2 * 16 * 1.2f <= LiveWidths.DETAIL_LOGO_H)
    }

    @Test fun theRailIsASlimStrip() {
        assertTrue(LiveWidths.RAIL in 56..60)
    }

    @Test fun theGuideKeepsRoomForProgrammesBesideTheWiderChannelColumn() {
        assertEquals(654, LiveWidths.guideOpen())
        assertEquals(936, LiveWidths.guideGrid())
        // Beside the groups column: more than an hour and a quarter of programmes.
        assertTrue(LiveWidths.minutesShown(LiveWidths.guideOpen(), LiveWidths.CHANNELS_OPEN) > 75f)
        // In the grid: more than two hours, and the channel column wider than beside the groups column.
        assertTrue(LiveWidths.minutesShown(LiveWidths.guideGrid(), LiveWidths.CHANNELS_GRID) > 120f)
        assertTrue(LiveWidths.CHANNELS_GRID > LiveWidths.CHANNELS_OPEN)
    }

    @Test fun theChannelColumnIsUnderHalfTheGuide() {
        // The programmes, not the names, take most of the guide in both states.
        assertTrue(LiveWidths.CHANNELS_OPEN * 2 < LiveWidths.guideOpen())
        assertTrue(LiveWidths.CHANNELS_GRID * 2 < LiveWidths.guideGrid())
    }
}
