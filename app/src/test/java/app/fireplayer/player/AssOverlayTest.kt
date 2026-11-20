package app.fireplayer.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssOverlayTest {

    @Test fun putUpOnlyWhenAsked() {
        var attached = 0
        val overlay = AssOverlay { attached++ }
        // Built with the player: nothing over the film yet.
        assertFalse(overlay.shown)
        assertEquals(0, attached)
        overlay.show()
        assertTrue(overlay.shown)
        assertEquals(1, attached)
    }

    @Test fun putUpOnceHoweverOftenAStyledTrackIsChosen() {
        var attached = 0
        val overlay = AssOverlay { attached++ }
        repeat(5) { overlay.show() }
        assertEquals(1, attached)
    }

    @Test fun styledTracksAreAssAndSsa() {
        val ssa = Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build()
        assertTrue(AssOverlay.isStyled(ssa))
        // Read ahead as it loads, the track keeps its kind in codecs.
        val readAhead = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).setCodecs(MimeTypes.TEXT_SSA).build()
        assertTrue(AssOverlay.isStyled(readAhead))
    }

    @Test fun otherSubtitlesAndNoneAreNotStyled() {
        assertFalse(AssOverlay.isStyled(null))
        for (mime in listOf(MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_VTT, MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_TTML)) {
            assertFalse(mime, AssOverlay.isStyled(Format.Builder().setSampleMimeType(mime).build()))
        }
        val readAheadSrt = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .setCodecs(MimeTypes.APPLICATION_SUBRIP).build()
        assertFalse(AssOverlay.isStyled(readAheadSrt))
    }
}
