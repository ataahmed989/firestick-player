package app.fireplayer.player

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a play saves of a copy for "Best for this TV" ([MediaSpec]: Dolby Vision profile, the decoder, HDR10+ seen,
 * HDR10's brightness data, the date; [CopyHealth]'s date).
 */
class PlaySavesTest {

    private val pq = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020).setColorTransfer(C.COLOR_TRANSFER_ST2084).build()

    @Test
    fun aDolbyVisionFileOnThePlainDecoderSavesItsProfileTheDecoderAndTheBrightnessDataTheTvGets() {
        // Avatar's IMAX copy: profile 8, sent to the HEVC decoder by Media3, no static metadata in the file: FirePlayer
        // gives the decoder HDR10's generic data (DvBaseLayer.hevcColour), so the TV gets brightness data.
        val dv8 = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs("dvhe.08.06")
            .setWidth(3840).setHeight(2160).setColorInfo(pq).build()
        val spec = MediaSpec.of(dv8, null, decoderMime = MimeTypes.VIDEO_H265, hdr10Plus = false)
        assertEquals(8, spec.dvProfile)
        assertEquals(MediaSpec.Decoder.PLAIN, spec.decoder)
        assertEquals(true, spec.hdrInfo)
        // On a Dolby Vision decoder: the file's own, none; not known which decoder.
        val onDv = MediaSpec.of(dv8, null, decoderMime = MimeTypes.VIDEO_DOLBY_VISION)
        assertEquals(MediaSpec.Decoder.DOLBY_VISION, onDv.decoder)
        assertEquals(false, onDv.hdrInfo)
        assertEquals(MediaSpec.Decoder.UNKNOWN, MediaSpec.of(dv8, null).decoder)
        // A profile 7 base layer is given brightness data when its file has none.
        val p7 = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs("dvhe.07.06").setColorInfo(pq).build()
        val base = MediaSpec.of(p7, null, baseLayer = true, hdr10Plus = true)
        assertEquals(true, base.hdrInfo)
        assertTrue(base.hdr10Plus)
        assertEquals(MediaSpec.Decoder.PLAIN, base.decoder)
        // SDR says nothing about it.
        val sdr = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setColorInfo(ColorInfo.SDR_BT709_LIMITED).build()
        assertNull(MediaSpec.of(sdr, null).hdrInfo)
    }

    @Test
    fun theNewFactsAreKeptBetweenPlaysAndAnOlderRecordStillReads() {
        val spec = MediaSpec(videoCodec = "HEVC", width = 3840, height = 2020, hdr = "HDR10", videoBps = 49_000_000,
            videoHow = MediaSpec.How.MEASURED, sizeBytes = 86_261_275_745L, measuredSecs = 120, dvBaseLayer = 7, dvProfile = 7,
            decoder = MediaSpec.Decoder.PLAIN, hdr10Plus = true, hdrInfo = true, playedAt = 1_764_000_000_000L)
        assertEquals(spec, MediaSpec.decode(MediaSpec.encode(spec)))
        assertEquals(spec.copy(hdrInfo = false), MediaSpec.decode(MediaSpec.encode(spec.copy(hdrInfo = false))))
        // What 1.0.356 kept (21 fields): read with none of them.
        val old = MediaSpec.decode(MediaSpec.encode(spec).split('|').take(21).joinToString("|"))!!
        assertEquals(7, old.dvBaseLayer)
        assertEquals(0, old.dvProfile)
        assertEquals(MediaSpec.Decoder.UNKNOWN, old.decoder)
        assertFalse(old.hdr10Plus)
        assertNull(old.hdrInfo)
        assertEquals(0L, old.playedAt)
        // A record that kept a measured picture shape (field 25, and its looks after the date) still reads, its date
        // too; nothing of the shape is written now.
        val fields = MediaSpec.encode(spec).split('|')
        assertEquals(28, fields.size)
        assertEquals("", fields[25])
        assertEquals("0", fields[27])
        val shaped = (fields.take(25) + "1.9" + fields[26] + "1.900@20000;1.900@80000").joinToString("|")
        assertEquals(spec, MediaSpec.decode(shaped))
        // A later play adds to an earlier one: HDR10+ once seen stays, the newest date counts.
        val later = MediaSpec(videoBps = 51_000_000, videoHow = MediaSpec.How.MEASURED, measuredSecs = 60, playedAt = 1_764_100_000_000L)
        val both = MediaSpec.merged(spec, later)
        assertTrue(both.hdr10Plus)
        assertEquals(1_764_100_000_000L, both.playedAt)
        assertEquals(7, both.dvProfile)
        assertEquals(true, both.hdrInfo)
    }

    @Test
    fun aCopysHealthKeepsWhenItLastPlayed() {
        val h = CopyHealth(watchedMs = 600_000, stalls = 12, stallMs = 120_000, at = 1_764_000_000_000L)
        assertEquals(h, CopyHealth.decode(CopyHealth.encode(h)))
        assertEquals("600000|12|120000|0|0|0|0|1764000000000", CopyHealth.encode(h))
        // Without a date it is written as before.
        assertEquals("600000|12|120000|0|0|0|0", CopyHealth.encode(h.copy(at = 0)))
        assertEquals(1_764_000_000_000L, (h + h.copy(at = 5L)).at)
        // A play with no picture here comes after the date.
        val none = CopyHealth(failures = 1, at = 1_764_000_000_000L, noPicture = 1)
        assertEquals("0|0|0|1|0|0|0|1764000000000|1", CopyHealth.encode(none))
        assertEquals(none, CopyHealth.decode(CopyHealth.encode(none)))
    }

    @Test
    fun theDecodersLostFramesAreKeptApartAndOlderRecordsReadWithNone() {
        val h = CopyHealth(watchedMs = 300_000, droppedFrames = 400, renderedFrames = 7_000, lateFrames = 300, at = 1_764_000_000_000L)
        assertEquals("300000|0|0|0|0|400|7000|1764000000000|0|300", CopyHealth.encode(h))
        assertEquals(h, CopyHealth.decode(CopyHealth.encode(h)))
        // Seven- and nine-field strings (before it): none; a record without any is written as before.
        assertEquals(0L, CopyHealth.decode("600000|2|5000|0|1|10|9000")!!.lateFrames)
        assertEquals(0L, CopyHealth.decode("8000|0|0|0|0|0|0|0|1")!!.lateFrames)
        assertEquals("8000|0|0|0|0|0|0", CopyHealth.encode(CopyHealth(watchedMs = 8_000)))
        // Every play adds to it.
        assertEquals(600L, (h + h).lateFrames)
        // Only buffering is "buffers": frames lost alone are not.
        assertFalse(CopyHealth(watchedMs = 20 * 60_000L, droppedFrames = 2_000, renderedFrames = 10_000).buffers)
        assertTrue(CopyHealth(watchedMs = 600_000, stalls = 12, stallMs = 120_000).buffers)
    }

    @Test
    fun aDolbyVisionFilesBaseSignalIsKeptWithItsMeasuredFacts() {
        val spec = MediaSpec(hdr = "Dolby Vision", dvProfile = 8, decoder = MediaSpec.Decoder.PLAIN, dvSignal = DvBaseLayer.SIGNAL_HLG,
            measuredSecs = 100)
        val text = MediaSpec.encode(spec)
        assertEquals("4", text.split('|')[27])
        assertEquals(spec, MediaSpec.decode(text))
        // An older 27-field record: not known.
        assertEquals(0, MediaSpec.decode(text.split('|').take(27).joinToString("|"))!!.dvSignal)
        // A later play that didn't read it keeps it; one that did, its own.
        assertEquals(DvBaseLayer.SIGNAL_HLG, MediaSpec.merged(spec, spec.copy(dvSignal = 0)).dvSignal)
        assertEquals(DvBaseLayer.SIGNAL_HDR10, MediaSpec.merged(spec, spec.copy(dvSignal = DvBaseLayer.SIGNAL_HDR10)).dvSignal)
    }
}
