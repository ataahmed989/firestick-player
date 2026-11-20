package app.fireplayer.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackDiagTest {

    @Test
    fun thePictureIsDescribedByItsFormatOnly() {
        val format = Format.Builder()
            // A track's id and label can carry anything the file or the add-on put there: never written.
            .setId("https://example.invalid/abcdef/film.mkv")
            .setLabel("My copy")
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs("hvc1.2.4.L153.B0")
            .setWidth(3840)
            .setHeight(2160)
            .setFrameRate(23.976f)
            .setColorInfo(
                ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_ST2084).setColorSpace(C.COLOR_SPACE_BT2020)
                    .setLumaBitdepth(10).build(),
            )
            .build()
        val line = PlaybackDiag.video(format)
        assertEquals("video=3840x2160@23.976 mime=video/hevc codecs=hvc1.2.4.L153.B0 hdr=HDR10 bits=10", line)
        assertFalse(line.contains("http") || line.contains("example") || line.contains("My copy"))
        assertEquals("video=none", PlaybackDiag.video(null))
    }

    @Test
    fun theTvsHdrTypesByName() {
        assertEquals("none", PlaybackDiag.hdrTypes(null))
        assertEquals("none", PlaybackDiag.hdrTypes(IntArray(0)))
        assertEquals("HDR10,HLG,HDR10+,DV", PlaybackDiag.hdrTypes(intArrayOf(2, 3, 4, 1)))
        assertEquals("type9", PlaybackDiag.hdrTypes(intArrayOf(9)))
    }
}
