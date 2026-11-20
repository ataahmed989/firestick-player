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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import app.fireplayer.vod.Hdr
import app.fireplayer.vod.SoundFormat
import app.fireplayer.vod.ThisTv
import app.fireplayer.vod.TvCapabilities
import app.fireplayer.vod.TvPlan

/** The plan "Best for this TV" uses (design-brief §6.5), from a reading of the TV. Pure. */
object TvPlans {

    /**
     * Automatic: Dolby Vision first when the TV has it, then HDR10 and HDR10+ as equals (HDR10+ plays as HDR10 where
     * it isn't supported), then HLG; no tiers (SDR first) when the TV shows no HDR. The tallest mode it has; the sound
     * it takes as it is.
     */
    fun automatic(caps: TvCapabilities?): TvPlan {
        if (caps == null) return TvPlan(true, listOf(setOf(Hdr.HDR10, Hdr.HDR10_PLUS), setOf(Hdr.HLG)), 2160, emptySet())
        val tiers = ArrayList<Set<Hdr>>()
        if (Hdr.DOLBY_VISION in caps.hdr) tiers += setOf(Hdr.DOLBY_VISION)
        if (Hdr.HDR10 in caps.hdr || Hdr.HDR10_PLUS in caps.hdr) tiers += setOf(Hdr.HDR10, Hdr.HDR10_PLUS)
        if (Hdr.HLG in caps.hdr) tiers += setOf(Hdr.HLG)
        val top = when {
            caps.maxHeight >= 2160 -> 2160
            caps.maxHeight >= 1440 -> 1440
            caps.maxHeight >= 1080 || caps.maxHeight <= 0 -> 1080
            else -> 720
        }
        return TvPlan(true, tiers, top, caps.passthrough)
    }

    // One line each, so the reading and the user's plan survive restarts. Names only, no links.

    fun writeCaps(c: TvCapabilities): String =
        listOf(c.hdr.joinToString(",") { it.name }, c.maxWidth, c.maxHeight, c.passthrough.joinToString(",") { it.name }, c.checkedAt)
            .joinToString(";")

    fun readCaps(text: String?): TvCapabilities? {
        val f = text?.split(';') ?: return null
        if (f.size != 5) return null
        return TvCapabilities(
            hdr = names(f[0], Hdr.entries),
            maxWidth = f[1].toIntOrNull() ?: return null,
            maxHeight = f[2].toIntOrNull() ?: return null,
            passthrough = names(f[3], SoundFormat.entries),
            checkedAt = f[4].toLongOrNull() ?: 0L,
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
 * Settings › This TV: reads what this Stick's TV reports (display HDR types and modes, sound passthrough) and keeps
 * the last reading and the user's own plan in [prefs]. The automatic plan follows the last reading. Main thread.
 */
class ThisTvReader(private val app: Context, private val prefs: SharedPreferences, private val clock: () -> Long) : ThisTv {

    @Volatile private var caps: TvCapabilities? = TvPlans.readCaps(prefs.getString(CAPS, null))

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
        TvPlans.readPlan(prefs.getString(PLAN, null))?.let { return it }
        // The first reading, while ranking: nothing to rank again for.
        if (caps == null) runCatching { readNow() }
        return TvPlans.automatic(caps)
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
        )
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
