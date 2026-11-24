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
 * HDR10's brightness data, the measured aspect ratio, the date; [CopyHealth]'s date), and the strict IMAX measure
 * ([ImaxAspect]).
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
            decoder = MediaSpec.Decoder.PLAIN, hdr10Plus = true, hdrInfo = true, aspect = 1.9f, playedAt = 1_764_000_000_000L)
        assertEquals(spec, MediaSpec.decode(MediaSpec.encode(spec)))
        assertEquals(spec.copy(hdrInfo = false), MediaSpec.decode(MediaSpec.encode(spec.copy(hdrInfo = false))))
        // What 1.0.356 kept (21 fields): read with none of them.
        val old = MediaSpec.decode(MediaSpec.encode(spec).split('|').take(21).joinToString("|"))!!
        assertEquals(7, old.dvBaseLayer)
        assertEquals(0, old.dvProfile)
        assertEquals(MediaSpec.Decoder.UNKNOWN, old.decoder)
        assertFalse(old.hdr10Plus)
        assertNull(old.hdrInfo)
        assertEquals(0f, old.aspect, 0f)
        assertEquals(0L, old.playedAt)
        // A later play adds to an earlier one: HDR10+ once seen stays, the newest date and aspect that say count.
        val later = MediaSpec(videoBps = 51_000_000, videoHow = MediaSpec.How.MEASURED, measuredSecs = 60, playedAt = 1_764_100_000_000L)
        val both = MediaSpec.merged(spec, later)
        assertTrue(both.hdr10Plus)
        assertEquals(1.9f, both.aspect, 0f)
        assertEquals(1_764_100_000_000L, both.playedAt)
        assertEquals(7, both.dvProfile)
        assertEquals(true, both.hdrInfo)
    }

    @Test
    fun theLooksAtThePictureFromEveryPlayDecideItsSteadyShape() {
        // Every look of one play at a 3840×2020 frame: IMAX by measure.
        val looks = (0 until 8).map { AspectSample(1.9, 20_000L + it * 60_000L) }
        assertEquals(1.9f, MediaSpec.merged(null, MediaSpec(measuredSecs = 60, aspects = looks)).aspect, 0.001f)
        // Fewer looks say nothing yet; a later play's looks at other parts of the film add to them.
        val few = MediaSpec.merged(null, MediaSpec(measuredSecs = 60, aspects = looks.take(5)))
        assertEquals(0f, few.aspect, 0f)
        val later = MediaSpec(measuredSecs = 60, aspects = (0 until 3).map { AspectSample(1.9, 3_600_000L + it * 60_000L) })
        val both = MediaSpec.merged(few, later)
        assertEquals(8, both.aspects.size)
        assertEquals(1.9f, both.aspect, 0.001f)
        // Kept between plays, after the fields before them.
        assertEquals(both, MediaSpec.decode(MediaSpec.encode(both)))
        // A 16:9 look among them: not sure, so not IMAX.
        assertEquals(0f, MediaSpec.merged(both, MediaSpec(measuredSecs = 60, aspects = listOf(AspectSample(1.78, 7_200_000L)))).aspect, 0f)
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

    // ---- IMAX, measured ----

    private fun samples(vararg runs: Pair<Float, Int>): FloatArray {
        val out = FloatArray(runs.sumOf { it.second })
        var i = 0
        for ((ratio, n) in runs) repeat(n) { out[i++] = ratio }
        return out
    }

    @Test
    fun onlyASteady190PictureIsImax() {
        // 3840×2020 frames, or letterboxing that leaves 1.90:1, throughout.
        val imax = samples(1.90f to 40)
        assertEquals(1.90f, ImaxAspect.steady(imax, imax.size), 0.001f)
        assertEquals(ImaxAspect.Verdict.IMAX, ImaxAspect.verdict(imax, imax.size, film = true))
        // Slightly off measures still count; a rare mis-read too.
        val near = samples(1.89f to 20, 1.91f to 18, 2.39f to 2)
        assertEquals(ImaxAspect.Verdict.IMAX, ImaxAspect.verdict(near, near.size, film = true))
        // 16:9 full screen and 2.39:1 scope are steady, but not IMAX.
        assertEquals(ImaxAspect.Verdict.NOT_IMAX, ImaxAspect.verdict(samples(1.78f to 40), 40, film = true))
        assertEquals(2.39f, ImaxAspect.steady(samples(2.39f to 40), 40), 0.001f)
        // A film that switches between widescreen and full screen: not sure, so not IMAX.
        val switching = samples(1.90f to 36, 1.78f to 4)
        assertEquals(0f, ImaxAspect.steady(switching, switching.size), 0f)
        assertEquals(ImaxAspect.Verdict.NOT_IMAX, ImaxAspect.verdict(switching, switching.size, film = true))
        val scopeAndImax = samples(2.39f to 20, 1.90f to 20)
        assertEquals(ImaxAspect.Verdict.NOT_IMAX, ImaxAspect.verdict(scopeAndImax, scopeAndImax.size, film = true))
        // Too little seen, or an episode: nothing said.
        assertEquals(ImaxAspect.Verdict.UNKNOWN, ImaxAspect.verdict(imax, ImaxAspect.MIN_SAMPLES - 1, film = true))
        assertEquals(ImaxAspect.Verdict.UNKNOWN, ImaxAspect.verdict(imax, imax.size, film = false))
        assertFalse(ImaxAspect.isImax(0f))
        assertTrue(ImaxAspect.isImax(2020f.let { 3840f / it }))
    }
}
