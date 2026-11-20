package app.fireplayer.ui

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekPreviewTest {

    private fun video(w: Int, h: Int): Format =
        Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setWidth(w).setHeight(h).build()

    private fun group(vararg formats: Format, selected: Boolean = false) = Tracks.Group(
        TrackGroup(*formats),
        /* adaptiveSupported = */ false,
        IntArray(formats.size) { C.FORMAT_HANDLED },
        BooleanArray(formats.size) { selected && it == 0 },
    )

    private fun tracks(vararg groups: Tracks.Group) = Tracks(groups.toList())

    private val audio = group(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).build())

    private fun tooBig(t: Tracks) = SeekPreview.tooBigToPreview(t, 1920, 1080)

    @Test fun aFourKOnlyFilmLeftWithoutAPictureIsTooBig() {
        assertTrue(tooBig(tracks(group(video(3840, 2160)), audio)))
        // Scope films and 1440p are bigger than 1080p too.
        assertTrue(tooBig(tracks(group(video(3840, 1600)))))
        assertTrue(tooBig(tracks(group(video(2560, 1440)))))
        assertTrue(tooBig(tracks(group(video(3840, 2160)), group(video(3840, 2160)))))
    }

    @Test fun aPictureThatWasChosenIsNeverTooBig() {
        // A TV with memory to spare decodes the 4K picture (Media3 goes past the 1080p limit there).
        assertFalse(tooBig(tracks(group(video(3840, 2160), selected = true))))
    }

    @Test fun aSmallerPictureLeftUnchosenIsSomethingElse() {
        // Not chosen though it fits: no decoder for it, left as it was before the 1080p limit.
        assertFalse(tooBig(tracks(group(video(3840, 2160), video(1920, 1080)))))
        assertFalse(tooBig(tracks(group(video(1280, 720)))))
        assertFalse(tooBig(tracks(group(video(1920, 800)))))
        // Size not given: counts as fitting, as it does for Media3.
        assertFalse(tooBig(tracks(group(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build()))))
    }

    @Test fun noPictureAtAllIsNotTooBig() {
        assertFalse(tooBig(Tracks.EMPTY))
        assertFalse(tooBig(tracks(audio)))
    }
}
