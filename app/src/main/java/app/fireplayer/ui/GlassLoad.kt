package app.fireplayer.ui

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import app.fireplayer.player.FilmMemory
import app.fireplayer.player.RamTier

/**
 * How hard the Glass look may work on the film playing, and what counts as heavy video. Each copy of the film
 * ([GlassBackdrop], and [BarWatch] in Fill screen or Stretch) makes the TV fetch the frame the decoder just made and
 * convert it on the same graphics chip that puts the film on screen. The glass treats a 4K film like any other: it copies
 * it as often as the Glass refresh setting says and looks for camera cuts every [PROBE_MS] (the user's choice, replacing
 * an earlier cap of 2 copies a second on a TV short of memory). [heavy] video (4K on a TV short of memory) still makes
 * [BarWatch] look half as often once the bars are known. With no glass on screen nothing is copied at all, whatever the
 * film ([GlassBackdrop]).
 */
object GlassLoad {

    /**
     * How often a probe looks for a camera cut while the film plays: soon enough that the glass changes with the new
     * shot, and cheap, as a probe is only 32 x 18 pixels with no blur.
     */
    const val PROBE_MS = 100L

    /** 4K: 2000 lines or more (2160p), or 3000 dots across (a wide 4K film is kept as 3840 x 1600). */
    const val UHD_MIN_HEIGHT = 2000
    const val UHD_MIN_WIDTH = 3000

    /** Under 1.8 GiB in all a TV is short of memory for this: the Fire TV Stick 4K Max has 1.7 GB. */
    const val SMALL_RAM_BYTES = 1_932_735_283L // 1.8 * 2^30

    /** The Glass refresh setting ([chosen], copies a second) as it applies: as chosen, 1 to 8, whatever the film. */
    fun refreshPerSec(chosen: Int): Int = chosen.coerceIn(1, 8)

    /** Asked for this much before the refresh it's meant for, so the drawing is ready by then. */
    const val FADE_STEP_EARLY_MS = 4L

    /**
     * How long to wait between the glass's drawings while a new copy of the film fades in: one of the TV's refreshes
     * ([refreshHz], as the display reports it: 23.976 on a TV switched to a film's rate), a little early. Drawing
     * oftener shows nothing more: on a Fire TV the app is asked to draw 60 times a second whatever the TV's rate, and
     * with a glass window open over a 4K film that was 48 full redraws a second for a TV showing 24. 0 means every
     * frame (a TV at 60 Hz or more).
     */
    fun fadeStepMs(refreshHz: Float): Long {
        val hz = if (refreshHz.isNaN() || refreshHz < 20f) 60f else refreshHz.coerceAtMost(120f)
        val step = (1000f / hz).toLong() - FADE_STEP_EARLY_MS
        // Under a 60 Hz frame there's nothing to wait for.
        return if (step < 16L) 0L else step
    }

    /** A TV with [totalMemBytes] in all (0: not known) is short of memory; [lowRam] is Fire OS's isLowRamDevice. */
    fun smallRam(totalMemBytes: Long, lowRam: Boolean): Boolean = lowRam || totalMemBytes in 1 until SMALL_RAM_BYTES

    /** 4K: [width] x [height] is 2000 lines or more, or 3000 dots across. */
    fun uhd(width: Int, height: Int): Boolean = height >= UHD_MIN_HEIGHT || width >= UHD_MIN_WIDTH

    /**
     * Whether the video is 4K HDR: [width] x [height] is 4K, and it's HDR10 or HDR10+ ([colorTransfer] PQ), HLG, or
     * Dolby Vision ([sampleMime], or [codecs] dvhe, dvh1, dvav, dva1 or dav1).
     */
    fun uhdHdr(width: Int, height: Int, colorTransfer: Int, sampleMime: String?, codecs: String?): Boolean {
        val dv = sampleMime == MimeTypes.VIDEO_DOLBY_VISION ||
            codecs != null && DV_CODECS.any { codecs.startsWith(it) }
        val hdr = dv || colorTransfer == C.COLOR_TRANSFER_ST2084 || colorTransfer == C.COLOR_TRANSFER_HLG
        return uhd(width, height) && hdr
    }

    /**
     * Heavy video: the [video] track playing is 4K (HDR or not: the frame copied is as big either way) on a TV that's
     * [smallRam]. Only [BarWatch] slows down for it now; the glass doesn't. The size shown ([shownWidth] x [shownHeight], 0 while not known) counts when the track doesn't say.
     */
    @OptIn(UnstableApi::class)
    fun heavy(video: Format?, shownWidth: Int, shownHeight: Int, smallRam: Boolean): Boolean {
        if (!smallRam || video == null) return false
        return uhd(maxOf(video.width, shownWidth), maxOf(video.height, shownHeight))
    }

    private val DV_CODECS = listOf("dvhe", "dvh1", "dvav", "dva1", "dav1")

    /**
     * Whether this TV is short of memory: the same reading the film's buffer is sized by ([FilmMemory.tier], read once),
     * so the player's memory care and the buffer never disagree about the TV.
     */
    fun smallRam(context: Context): Boolean = FilmMemory.tier(context).let { it == RamTier.TINY || it == RamTier.SMALL }
}
