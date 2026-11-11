package app.fireplayer.subs

import androidx.media3.common.text.Cue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Picture subtitles read as text, with Position "As in the file": the words go where the picture was. */
class PicturePlaceTest {

    // Placed as Media3's PGS, VobSub and DVB parsers place a picture: its top left corner, its width and its height.
    private fun picture(x: Float, y: Float, w: Float, h: Float): Cue = Cue.Builder()
        .setText("picture")
        .setPosition(x).setPositionAnchor(Cue.ANCHOR_TYPE_START)
        .setLine(y, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START)
        .setSize(w).setBitmapHeight(h)
        .build()

    @Test
    fun centredLowPicturesAreOrdinaryBottomLines() {
        // A two-line subtitle in the middle near the bottom of a 1080p Blu-ray: 880x110 at (520, 860).
        val cue = placedLikePicture(picture(520 / 1920f, 860 / 1080f, 880 / 1920f, 110 / 1080f), "Where were you?")
        assertEquals(Cue.DIMEN_UNSET, cue.line)
        assertEquals(Cue.DIMEN_UNSET, cue.position)
        assertTrue(isOrdinaryBottom(cue))
    }

    @Test
    fun aPictureAtTheTopStaysAtTheTop() {
        // Moved up while there are words on screen at the bottom.
        val cue = placedLikePicture(picture(0.3f, 0.06f, 0.4f, 0.1f), "Not now.")
        assertEquals(0.06f, cue.line, 0.001f)
        assertEquals(Cue.ANCHOR_TYPE_START, cue.lineAnchor)
        assertEquals(Cue.DIMEN_UNSET, cue.position)
        assertFalse(isOrdinaryBottom(cue))
    }

    @Test
    fun aPictureOnOneSideStaysOnThatSide() {
        // Two people talking at once: one line low on the left.
        val cue = placedLikePicture(picture(0.05f, 0.82f, 0.3f, 0.08f), "Me too.")
        assertEquals(0.2f, cue.position, 0.001f)
        assertEquals(Cue.ANCHOR_TYPE_MIDDLE, cue.positionAnchor)
        assertEquals(0.9f, cue.line, 0.001f)
        assertEquals(Cue.ANCHOR_TYPE_END, cue.lineAnchor)
        assertFalse(isOrdinaryBottom(cue))
    }

    @Test
    fun aPictureWithNoPlaceIsAnOrdinaryLine() {
        val cue = placedLikePicture(Cue.Builder().setText("picture").build(), "Hello.")
        assertTrue(isOrdinaryBottom(cue))
        assertEquals("Hello.", cue.text.toString())
    }
}
