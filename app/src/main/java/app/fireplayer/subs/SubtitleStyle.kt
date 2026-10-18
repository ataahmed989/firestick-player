package app.fireplayer.subs

import android.graphics.Color
import app.fireplayer.data.Prefs
import org.json.JSONArray
import org.json.JSONObject

enum class SubtitleEdge(val label: String) {
    DROP_SHADOW("Soft shadow"), OUTLINE("Outline"), NONE("None")
}

enum class ShadowStrength(val label: String, val radius: Float, val offset: Float, val alpha: Int) {
    LIGHT("Light", 0.05f, 0.02f, 0x99), MEDIUM("Medium", 0.07f, 0.03f, 0xCC), STRONG("Strong", 0.10f, 0.04f, 0xFF)
}

/** NETFLIX uses the font sent from the phone when there is one, otherwise a built-in look-alike. */
enum class SubtitleFont(val label: String) {
    NETFLIX("Netflix style"), ARIAL("Arial style"), FIGTREE("Figtree"), SYSTEM("System")
}

/**
 * How plain-text subtitles look. Sizes and positions are fractions of the
 * screen height, so a style looks the same on every TV.
 */
data class SubtitleStyle(
    val name: String,
    val textSize: Float,
    val color: Int,
    val edge: SubtitleEdge,
    val box: Boolean,
    val bottom: Float,
    val font: SubtitleFont,
    /** Only used when [box] is on. */
    val boxOpacity: Float = 0.6f,
    val boxColor: Int = Color.BLACK,
    val boxRounded: Boolean = true,
    /** Only used with the soft shadow edge. */
    val shadow: ShadowStrength = ShadowStrength.MEDIUM,
    /** Only used with the outline edge. */
    val outlineColor: Int = Color.BLACK,
) {
    /** Same look, whatever it's called. */
    fun looksLike(other: SubtitleStyle) = copy(name = "") == other.copy(name = "")

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("textSize", textSize.toDouble())
        .put("color", color)
        .put("edge", edge.name)
        .put("box", box)
        .put("bottom", bottom.toDouble())
        .put("font", font.name)
        .put("boxOpacity", boxOpacity.toDouble())
        .put("boxColor", boxColor)
        .put("boxRounded", boxRounded)
        .put("shadow", shadow.name)
        .put("outlineColor", outlineColor)

    companion object {
        const val NETFLIX_NAME = "Netflix"
        const val CUSTOM_NAME = "Custom"

        /** White, soft drop shadow, Netflix-style font, low on the screen. */
        val NETFLIX = SubtitleStyle(
            name = NETFLIX_NAME,
            textSize = 0.050f,
            color = Color.WHITE,
            edge = SubtitleEdge.DROP_SHADOW,
            box = false,
            bottom = 0.05f,
            font = SubtitleFont.NETFLIX,
        )

        val SIZES = listOf(
            0.030f, 0.034f, 0.038f, 0.042f, 0.046f, 0.050f, 0.055f, 0.060f, 0.066f, 0.072f, 0.080f, 0.090f,
        )
        val BOTTOMS = listOf(0.03f, 0.05f, 0.08f, 0.12f, 0.16f)
        val BOX_OPACITIES = listOf(0.25f, 0.4f, 0.5f, 0.6f, 0.75f, 0.9f, 1f)
        val BOX_COLORS = listOf(
            Color.BLACK to "Black",
            Color.rgb(0x2A, 0x2D, 0x33) to "Charcoal",
            Color.rgb(0x0B, 0x1A, 0x33) to "Midnight blue",
        )
        val COLORS = listOf(
            Color.WHITE to "White",
            Color.rgb(0xFF, 0xE4, 0x5C) to "Yellow",
            Color.rgb(0x7F, 0xE3, 0xFF) to "Cyan",
            Color.rgb(0xD8, 0xD8, 0xD8) to "Light gray",
        )

        fun fromJson(o: JSONObject): SubtitleStyle = SubtitleStyle(
            name = o.optString("name", CUSTOM_NAME),
            textSize = o.optDouble("textSize", NETFLIX.textSize.toDouble()).toFloat(),
            color = o.optInt("color", Color.WHITE),
            edge = runCatching { SubtitleEdge.valueOf(o.optString("edge")) }
                .getOrDefault(SubtitleEdge.DROP_SHADOW),
            box = o.optBoolean("box", false),
            bottom = o.optDouble("bottom", NETFLIX.bottom.toDouble()).toFloat(),
            font = runCatching { SubtitleFont.valueOf(o.optString("font")) }
                .getOrDefault(SubtitleFont.NETFLIX),
            boxOpacity = o.optDouble("boxOpacity", 0.6).toFloat(),
            boxColor = o.optInt("boxColor", Color.BLACK),
            boxRounded = o.optBoolean("boxRounded", true),
            shadow = runCatching { ShadowStrength.valueOf(o.optString("shadow")) }
                .getOrDefault(ShadowStrength.MEDIUM),
            outlineColor = o.optInt("outlineColor", Color.BLACK),
        )

        fun colorLabel(color: Int): String =
            COLORS.firstOrNull { it.first == color }?.second ?: "Custom"
    }
}

/** Loads and saves the Netflix preset, the user's saved presets and the "Custom" style. */
class SubtitlePresets(private val prefs: Prefs) {

    fun saved(): List<SubtitleStyle> {
        val arr = runCatching { JSONArray(prefs.subtitlePresetsJson) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { SubtitleStyle.fromJson(it) }
        }
    }

    fun custom(): SubtitleStyle {
        val json = prefs.customSubtitleStyleJson
        if (json.isBlank()) return SubtitleStyle.NETFLIX.copy(name = SubtitleStyle.CUSTOM_NAME)
        return runCatching { SubtitleStyle.fromJson(JSONObject(json)) }
            .getOrDefault(SubtitleStyle.NETFLIX)
            .copy(name = SubtitleStyle.CUSTOM_NAME)
    }

    /** Netflix first, then saved presets, then Custom. */
    fun all(): List<SubtitleStyle> = listOf(SubtitleStyle.NETFLIX) + saved() + custom()

    fun active(): SubtitleStyle {
        val name = prefs.activeSubtitlePreset
        return all().firstOrNull { it.name == name } ?: SubtitleStyle.NETFLIX
    }

    fun setActive(name: String) {
        prefs.activeSubtitlePreset = name
    }

    /** Any edit turns the current look into the "Custom" style. */
    fun editActive(change: (SubtitleStyle) -> SubtitleStyle) {
        val current = active()
        // Picking the value that's already set isn't a change.
        if (change(current).looksLike(current)) return
        val edited = change(current).copy(name = SubtitleStyle.CUSTOM_NAME)
        prefs.customSubtitleStyleJson = edited.toJson().toString()
        prefs.activeSubtitlePreset = SubtitleStyle.CUSTOM_NAME
    }

    fun saveActiveAs(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        if (clean == SubtitleStyle.NETFLIX_NAME || clean == SubtitleStyle.CUSTOM_NAME) return
        val style = active().copy(name = clean)
        val list = saved().filterNot { it.name == clean } + style
        write(list)
        prefs.activeSubtitlePreset = clean
    }

    fun isSaved(name: String): Boolean = saved().any { it.name == name }

    /** True when the Custom style is something new, not just a copy of Netflix or a saved preset. */
    fun customIsNew(): Boolean {
        val custom = custom()
        return (listOf(SubtitleStyle.NETFLIX) + saved()).none { it.looksLike(custom) }
    }

    fun delete(name: String) {
        write(saved().filterNot { it.name == name })
        if (prefs.activeSubtitlePreset == name) prefs.activeSubtitlePreset = SubtitleStyle.NETFLIX_NAME
    }

    private fun write(list: List<SubtitleStyle>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.subtitlePresetsJson = arr.toString()
    }
}
