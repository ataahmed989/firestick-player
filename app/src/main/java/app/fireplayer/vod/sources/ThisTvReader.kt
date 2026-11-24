package app.fireplayer.vod.sources

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import app.fireplayer.data.Prefs
import app.fireplayer.player.DvBaseLayer
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.SoundFormat
import app.fireplayer.vod.ThisTv
import app.fireplayer.vod.TvCapabilities
import app.fireplayer.vod.TvPlan

/** The plan "Best for this TV" uses (design-brief §6.5), from a reading of the TV. Pure. */
object TvPlans {

    /**
     * Automatic: Dolby Vision first when the TV has it, then HDR10 and HDR10+ as equals (HDR10+ only when the TV reports
     * it: elsewhere an HDR10+ file shows as HDR10, [SourceRank.shownHdr]), then HLG; no tiers (SDR first) when the TV
     * shows no HDR. The tallest mode it has; the sound it takes as it is; what this Stick does with profile 7.
     */
    fun automatic(caps: TvCapabilities?): TvPlan {
        if (caps == null) return TvPlan(true, listOf(setOf(Hdr.HDR10, Hdr.HDR10_PLUS), setOf(Hdr.HLG)), 2160, emptySet())
        val dv7 = caps.dv7AsHdr10
        val tiers = ArrayList<Set<Hdr>>()
        if (Hdr.DOLBY_VISION in caps.hdr) tiers += setOf(Hdr.DOLBY_VISION)
        if (Hdr.HDR10 in caps.hdr || Hdr.HDR10_PLUS in caps.hdr) {
            tiers += if (Hdr.HDR10_PLUS in caps.hdr) setOf(Hdr.HDR10, Hdr.HDR10_PLUS) else setOf(Hdr.HDR10)
        }
        if (Hdr.HLG in caps.hdr) tiers += setOf(Hdr.HLG)
        val top = when {
            caps.maxHeight >= 2160 -> 2160
            caps.maxHeight >= 1440 -> 1440
            caps.maxHeight >= 1080 || caps.maxHeight <= 0 -> 1080
            else -> 720
        }
        return TvPlan(true, tiers, top, caps.passthrough, dv7AsHdr10 = dv7)
    }

    // One line each, so the reading and the user's plan survive restarts. Names only, no links.

    /** "HDR10,HLG;3840;2160;AC3;1700000000000;1": the last field, profile 7 as HDR10, came later (1, 0, or empty). */
    fun writeCaps(c: TvCapabilities): String =
        listOf(c.hdr.joinToString(",") { it.name }, c.maxWidth, c.maxHeight, c.passthrough.joinToString(",") { it.name }, c.checkedAt,
            c.dv7AsHdr10?.let { if (it) 1 else 0 } ?: "")
            .joinToString(";")

    /** A reading written by [writeCaps], or an older one without its last field ([TvCapabilities.dv7AsHdr10] null). */
    fun readCaps(text: String?): TvCapabilities? {
        val f = text?.split(';') ?: return null
        if (f.size != 5 && f.size != 6) return null
        return TvCapabilities(
            hdr = names(f[0], Hdr.entries),
            maxWidth = f[1].toIntOrNull() ?: return null,
            maxHeight = f[2].toIntOrNull() ?: return null,
            passthrough = names(f[3], SoundFormat.entries),
            checkedAt = f[4].toLongOrNull() ?: 0L,
            dv7AsHdr10 = when (f.getOrNull(5)) {
                "1" -> true
                "0" -> false
                else -> null
            },
        )
    }

    fun writePlan(p: TvPlan): String = listOf(
        p.hdrOrder.joinToString("/") { tier -> tier.joinToString(",") { it.name } },
        p.topHeight,
        p.sound.joinToString(",") { it.name },
        p.maxMbps ?: "",
    ).joinToString(";")

    fun readPlan(text: String?): TvPlan? {
        val f = text?.split(';') ?: return null
        if (f.size != 4) return null
        val tiers = f[0].split('/').filter { it.isNotEmpty() }.map { names(it, Hdr.entries) }.filter { it.isNotEmpty() }
        return TvPlan(false, tiers, f[1].toIntOrNull() ?: return null, names(f[2], SoundFormat.entries), f[3].toIntOrNull())
    }

    private fun <E : Enum<E>> names(text: String, all: List<E>): Set<E> =
        text.split(',').mapNotNull { n -> all.firstOrNull { it.name == n } }.toCollection(LinkedHashSet())
}

/**
 * Settings › This TV: reads what this Stick's TV reports (display HDR types and modes, sound passthrough) and what its
 * Dolby Vision decoders do with profile 7, and keeps the last reading and the user's own plan in [prefs]. The automatic
 * plan follows the last reading; a reading from before profile 7 was read is read again once. IMAX first: always is
 * one of the viewer's settings ([settings], [Prefs.IMAX_FIRST_KEY]). Main thread.
 */
class ThisTvReader(
    private val app: Context,
    private val prefs: SharedPreferences,
    private val clock: () -> Long,
    private val settings: SharedPreferences? = null,
) : ThisTv {

    @Volatile private var caps: TvCapabilities? = TvPlans.readCaps(prefs.getString(CAPS, null))

    /** Read once by [plan] this run already (a reading that can't tell profile 7 isn't read again and again). */
    private var readOnce = false

    override fun capabilities(): TvCapabilities? = caps

    /** The plan may have changed (a new reading, the user's own plan, back to Automatic): lists in memory rank again. */
    var onChanged: (() -> Unit)? = null

    override fun checkAgain(onDone: (TvCapabilities) -> Unit) {
        val read = readNow()
        onDone(read)
        onChanged?.invoke()
    }

    private fun readNow(): TvCapabilities {
        val read = read()
        caps = read
        prefs.edit().putString(CAPS, TvPlans.writeCaps(read)).apply()
        return read
    }

    override fun plan(): TvPlan {
        // The first reading (or one from before profile 7 was read), while ranking: nothing to rank again for.
        if (!readOnce && caps?.dv7AsHdr10 == null) {
            readOnce = true
            runCatching { readNow() }
        }
        // What this Stick does with profile 7 is the TV's, whatever the plan.
        TvPlans.readPlan(prefs.getString(PLAN, null))?.let { return it.copy(dv7AsHdr10 = caps?.dv7AsHdr10) }
        return TvPlans.automatic(caps)
    }

    override fun imaxAlways(): Boolean = settings?.getBoolean(Prefs.IMAX_FIRST_KEY, false) == true

    override fun setImaxAlways(on: Boolean) {
        val s = settings ?: return
        s.edit().putBoolean(Prefs.IMAX_FIRST_KEY, on).apply()
        onChanged?.invoke()
    }

    override fun setPlan(plan: TvPlan) {
        prefs.edit().putString(PLAN, TvPlans.writePlan(plan.copy(automatic = false))).apply()
        onChanged?.invoke()
    }

    override fun useAutomatic() {
        prefs.edit().remove(PLAN).apply()
        onChanged?.invoke()
    }

    private fun read(): TvCapabilities {
        val display = (app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
        return TvCapabilities(
            hdr = display?.let { hdrOf(it) } ?: emptySet(),
            maxWidth = display?.let { largest(it).x } ?: 0,
            maxHeight = display?.let { largest(it).y } ?: 0,
            passthrough = runCatching { passthrough() }.getOrDefault(emptySet()),
            checkedAt = clock(),
            dv7AsHdr10 = runCatching { dv7AsHdr10() }.getOrNull(),
        )
    }

    /**
     * Whether this Stick plays Dolby Vision profile 7 as its HDR10 base layer, from its decoder lists as the film's
     * renderer reads them ([DvBaseLayer.baseLayerInstead]): true when none of its Dolby Vision decoders takes profile 7.
     */
    @OptIn(UnstableApi::class)
    private fun dv7AsHdr10(): Boolean {
        val dv = MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, false).map { info ->
            val levels = info.capabilities?.profileLevels
            DvBaseLayer.Decoder(info.name, if (levels == null) IntArray(0) else IntArray(levels.size) { levels[it].profile })
        }
        val hevc = MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H265, false, false).size
        return DvBaseLayer.baseLayerInstead(DvBaseLayer.PROFILE, dv, hevc)
    }

    @Suppress("DEPRECATION")
    private fun hdrOf(display: Display): Set<Hdr> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptySet()
        val types = runCatching { display.hdrCapabilities?.supportedHdrTypes }.getOrNull() ?: return emptySet()
        return types.toList().mapNotNull {
            when (it) {
                1 -> Hdr.DOLBY_VISION
                2 -> Hdr.HDR10
                3 -> Hdr.HLG
                4 -> Hdr.HDR10_PLUS
                else -> null
            }
        }.toCollection(LinkedHashSet())
    }

    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    private fun largest(display: Display): Point {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val best = display.supportedModes.maxByOrNull { it.physicalWidth.toLong() * it.physicalHeight }
            if (best != null) return Point(best.physicalWidth, best.physicalHeight)
        }
        return Point().also { display.getRealSize(it) }
    }

    @OptIn(UnstableApi::class)
    private fun passthrough(): Set<SoundFormat> {
        val caps = AudioCapabilities.getCapabilities(app, AudioAttributes.DEFAULT, null)
        val out = LinkedHashSet<SoundFormat>()
        if (caps.supportsEncoding(C.ENCODING_AC3)) out += SoundFormat.AC3
        if (caps.supportsEncoding(C.ENCODING_E_AC3)) out += SoundFormat.EAC3
        if (caps.supportsEncoding(C.ENCODING_E_AC3_JOC)) out += SoundFormat.EAC3_JOC
        if (caps.supportsEncoding(C.ENCODING_DOLBY_TRUEHD)) out += SoundFormat.TRUEHD
        if (caps.supportsEncoding(C.ENCODING_DTS)) out += SoundFormat.DTS
        if (caps.supportsEncoding(C.ENCODING_DTS_HD)) out += SoundFormat.DTS_HD
        return out
    }

    companion object {
        const val FILE = "fireplayer_this_tv"
        private const val CAPS = "caps"
        private const val PLAN = "plan"
    }
}
