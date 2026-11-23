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
    fun theAppsMemoryIsReadOnceAMinuteNotForEveryLine() {
        // The first line reads it; the next five (every 10 s, a tick's drift either way) repeat it; the sixth reads again.
        assertEquals(true, PlaybackDiag.memoryDue(0L, 10_000L))
        val readAt = 10_000L
        for (line in 1..5) assertFalse(PlaybackDiag.memoryDue(readAt, readAt + line * 10_000L + 40L))
        assertEquals(true, PlaybackDiag.memoryDue(readAt, readAt + 60_000L - 40L))
        // Reads a minute while the film plays: the line's one and the peak's two, against 6 + 12 before (5 in 6 fewer).
        val reads = 60 / PlaybackDiag.MEMORY_EVERY_S + 60 / PlaybackDiag.PSS_EVERY_S
        assertEquals(3, reads)
        assertEquals(18, 60 / PlaybackDiag.LOG_EVERY_S + 60 / 5)
    }

    @Test
    fun theTvsHdrTypesByName() {
        assertEquals("none", PlaybackDiag.hdrTypes(null))
        assertEquals("none", PlaybackDiag.hdrTypes(IntArray(0)))
        assertEquals("HDR10,HLG,HDR10+,DV", PlaybackDiag.hdrTypes(intArrayOf(2, 3, 4, 1)))
        assertEquals("type9", PlaybackDiag.hdrTypes(intArrayOf(9)))
    }
}
