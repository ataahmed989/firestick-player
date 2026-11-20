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
 * How hard the Glass look may work on the film playing. Each copy of the film ([GlassBackdrop], and [BarWatch] in Fill
 * screen or Stretch) makes the TV fetch the frame the decoder just made and convert it on the same graphics chip that
 * puts the film on screen, while the same TV is fetching and decoding the film. On a Fire TV Stick 4K Max, with 1.7 GB
 * of memory in all, heavy films lost frames while the Stick was out of memory. So while a film of [HEAVY_GLASS_BPS] or
 * more (its size over its running time) plays on a TV with that little memory ([heavyGlass]), the glass copies it at
 * most [HEAVY_REFRESH_PER_SEC] times a second (fewer if that's the setting) and looks for camera cuts every
 * [HEAVY_PROBE_MS] instead of every [PROBE_MS]; under that rate, 4K included, the glass follows the setting (the user's
 * choice). [BarWatch] still slows down for any 4K film on such a TV ([heavy]). The look itself and the saved setting
 * stay as they are. With no glass on screen nothing is copied at all, whatever the film ([GlassBackdrop]).
 */
object GlassLoad {

    /**
     * How often a probe looks for a camera cut while the film plays: soon enough that the glass changes with the new
     * shot, and cheap, as a probe is only 32 x 18 pixels with no blur.
     */
    const val PROBE_MS = 100L
    /** The same with heavy video. */
    const val HEAVY_PROBE_MS = 500L
    /** The most copies a second with heavy video. */
    const val HEAVY_REFRESH_PER_SEC = 2

    /** From this average rate (70 Mb/s, the user's line) a film is heavy for the glass. */
    const val HEAVY_GLASS_BPS = 70_000_000L

    /** 4K: 2000 lines or more (2160p), or 3000 dots across (a wide 4K film is kept as 3840 x 1600). */
    const val UHD_MIN_HEIGHT = 2000
    const val UHD_MIN_WIDTH = 3000

    /** Under 1.8 GiB in all a TV is short of memory for this: the Fire TV Stick 4K Max has 1.7 GB. */
    const val SMALL_RAM_BYTES = 1_932_735_283L // 1.8 * 2^30

    /** The Glass refresh setting ([chosen], copies a second) as it applies now: at most 2 with [heavy] video ([heavyGlass]). */
    fun refreshPerSec(chosen: Int, heavy: Boolean): Int {
        val rate = chosen.coerceIn(1, 8)
        return if (heavy) minOf(rate, HEAVY_REFRESH_PER_SEC) else rate
    }

    fun probeMs(heavy: Boolean): Long = if (heavy) HEAVY_PROBE_MS else PROBE_MS

    /** The glass is never redrawn through a fade faster than this with heavy video (30 times a second). */
    const val HEAVY_FADE_STEP_MS = 33L
    /** Asked for this much before the refresh it's meant for, so the drawing is ready by then. */
    const val FADE_STEP_EARLY_MS = 4L

    /**
     * How long to wait between the glass's drawings while a new copy of the film fades in: one of the TV's refreshes
     * ([refreshHz], as the display reports it: 23.976 on a TV switched to a film's rate), a little early. Drawing
     * oftener shows nothing more: on a Fire TV the app is asked to draw 60 times a second whatever the TV's rate, and
     * with a glass window open over a 4K film that was 48 full redraws a second for a TV showing 24. With [heavy] video
     * never oftener than every [HEAVY_FADE_STEP_MS]. 0 means every frame (a TV at 60 Hz or more, light video).
     */
    fun fadeStepMs(refreshHz: Float, heavy: Boolean): Long {
        val hz = if (refreshHz.isNaN() || refreshHz < 20f) 60f else refreshHz.coerceAtMost(120f)
        val step = (1000f / hz).toLong() - FADE_STEP_EARLY_MS
        val least = if (heavy) HEAVY_FADE_STEP_MS else 0L
        // Under a 60 Hz frame there's nothing to wait for.
        return maxOf(if (step < 16L) 0L else step, least)
    }

    /**
     * What the Glass refresh row shows while the setting is lowered for heavy video, such as "8 a second · 2 at 70+ Mb/s";
     * null when it isn't (the row then shows the setting's own name).
     */
    fun refreshLabel(chosen: Int, heavy: Boolean): String? {
        val now = refreshPerSec(chosen, heavy)
        return if (heavy && now < chosen) "$chosen a second · $now at 70+ Mb/s" else null
    }

    /**
     * Heavy for the glass: the film averages [HEAVY_GLASS_BPS] or more ([averageBps], its size over its running time; 0
     * while not known) on a TV that's [smallRam]. Not known counts as not heavy.
     */
    fun heavyGlass(averageBps: Long, smallRam: Boolean): Boolean = smallRam && averageBps >= HEAVY_GLASS_BPS

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
     * Heavy video for [BarWatch]: the [video] track playing is 4K (HDR or not: the frame copied is as big either way) on
     * a TV that's [smallRam]. The glass goes by the film's rate instead ([heavyGlass]). The size shown ([shownWidth] x [shownHeight], 0 while not known) counts when the track doesn't say.
     */
    @OptIn(UnstableApi::class)
    fun heavy(video: Format?, shownWidth: Int, shownHeight: Int, smallRam: Boolean): Boolean {
        if (!smallRam || video == null) return false
        return uhd(maxOf(video.width, shownWidth), maxOf(video.height, shownHeight))
    }

    private val DV_CODECS = listOf("dvhe", "dvh1", "dvav", "dva1", "dav1")

    /**
     * Whether this TV is short of memory: the same reading the film's buffer is sized by ([FilmMemory.tier], read once),
     * so the glass and the buffer never disagree about the TV.
     */
    fun smallRam(context: Context): Boolean = FilmMemory.tier(context).let { it == RamTier.TINY || it == RamTier.SMALL }
}
