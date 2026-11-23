package app.fireplayer.data

import android.content.Context
import android.content.SharedPreferences
import app.fireplayer.ui.GlassStyle
import org.json.JSONArray
import org.json.JSONObject

/**
 * The players' dimness settings (the Screen dimmer of the film player, and of Live TV full screen through LiveMore):
 * presets, one always on, and on for every video and channel after. Each has three values ([Value]): Display dimness,
 * how much the picture itself is darkened (0% to [MAX_DISPLAY]; it has no tint), and Glass tint and Glass dimness, the
 * Playback glass while it's on ([glass]: the windows over the picture, [GlassStyle.Shade]; 0% to 100%).
 *
 * - "Default", always first: display 0%, and the glass of Settings › Look › Playback glass (5% and 15% until changed,
 *   [GlassStyle.PLAYBACK_SHADE]). It IS those values: its glass rows here change Look's ([GlassShades]: shown at once,
 *   Unsaved until saved here or in Look), and Look's change it; Look's Reset to defaults puts it back
 *   ([GlassShades.reset]). A display dimness tried on it is kept with Save custom, as a new custom.
 * - Customs, added with Save custom from what's on: arrows change one on screen only until Save custom keeps the change;
 *   each can be renamed and deleted. "Auto" keeps a custom's display dimness and glass dimness the same.
 *
 * Customs saved before (a display amount and a glass amount each) are read as presets ([migrate]), and saved as presets
 * once as the app starts ([convertLegacyOnce]): the glass amount is their Glass dimness, with no Glass tint. Reading
 * never writes anything.
 */
class Dimmer(private val sp: () -> SharedPreferences) {

    constructor(context: Context) : this(GlassShades.settingsOf(context))

    /** A preset: its [display] dimness, and its glass's [glassTint] and [glassDim], in percent. */
    data class Preset(
        val id: String,
        val name: String,
        val display: Int,
        val glassTint: Int,
        val glassDim: Int,
        val auto: Boolean,
    ) {
        val isDefault: Boolean get() = id == DEFAULT_ID

        /** The Playback glass while it's on: its tint, then its darkening ([GlassStyle.Shade]). */
        val glass: GlassStyle.Shade get() = GlassStyle.Shade(glassTint, dim = glassDim)

        /** What its row says while another is on: its three values, in [Value]'s order ("20% · 0% · 20%"). */
        val values: String get() = Value.entries.joinToString(" · ") { "${it.of(this)}%" }

        /** Whether it darkens or tints nothing at all. */
        val isOff: Boolean get() = display == 0 && glassTint == 0 && glassDim == 0
    }

    /** A preset's three rows, in the window's order, and their names. */
    enum class Value(val label: String) {
        DISPLAY("Display dimness"),
        GLASS_TINT("Glass tint"),
        GLASS_DIM("Glass dimness"),
        ;

        /** Its value in [preset], percent. */
        fun of(preset: Preset): Int = when (this) {
            DISPLAY -> preset.display
            GLASS_TINT -> preset.glassTint
            GLASS_DIM -> preset.glassDim
        }
    }

    /** Settings › Look's glass: Default's is Playback glass's. */
    private val look = GlassShades(sp)

    /** The preset on, by id: Default until another is picked (or when the one picked is gone). */
    val activeId: String get() = activeIn(customs())

    private fun activeIn(customs: List<Preset>): String =
        sp().getString(ACTIVE_KEY, null)?.takeIf { id -> customs.any { it.id == id } } ?: DEFAULT_ID

    /** Default: no display dimness, and Look's Playback glass as it's saved now. */
    fun default(): Preset {
        val glass = look[GlassShades.Kind.PLAYBACK]
        return Preset(DEFAULT_ID, DEFAULT_NAME, 0, glass.tint, glass.dim, auto = false)
    }

    /** The customs, in the order they were added. Only reads: those from before presets are read as presets. */
    fun customs(): List<Preset> {
        val saved = sp().getString(PRESETS_KEY, null) ?: return migrate()
        return parse(saved)
    }

    /**
     * Once, as the app starts ([Settings]): the customs from before presets ([OLD_CUSTOMS_KEY], [OLD_LEVEL_KEY]) saved as
     * presets, only while there are no presets saved at all. Saved presets are never replaced, and the old values stay.
     */
    internal fun convertLegacyOnce() {
        val saved = sp()
        if (saved.contains(PRESETS_KEY)) return
        if (!saved.contains(OLD_CUSTOMS_KEY) && !saved.contains(OLD_LEVEL_KEY)) return
        store(migrate())
    }

    /** Every preset, as the window lists them: Default, then the customs. */
    fun presets(): List<Preset> = listOf(default()) + customs()

    /** The preset on, with any change not saved yet. */
    fun active(): Preset {
        val customs = customs()
        val id = activeIn(customs)
        val d = draft?.takeIf { it.id == id }
        if (id == DEFAULT_ID) {
            // Default's glass is always Look's as it is now; only a display dimness tried on it is its own.
            val base = default()
            return if (d == null) base else base.copy(display = d.display)
        }
        return d ?: customs.first { it.id == id }
    }

    /**
     * The preset on has changes that Save custom would keep (for Default: a display dimness, kept as a new custom, or its
     * glass changed and not saved, [GlassShades.hasUnsaved]).
     */
    fun hasUnsaved(): Boolean {
        val id = activeId
        val d = draft?.takeIf { it.id == id }
        if (id == DEFAULT_ID) return (d != null && d.display != 0) || look.hasUnsaved(GlassShades.Kind.PLAYBACK)
        return d != null && d != customs().firstOrNull { it.id == d.id }
    }

    /** Default's glass changed and not saved: what Save "Default" keeps ([saveDefault]). */
    fun defaultGlassUnsaved(): Boolean = activeId == DEFAULT_ID && look.hasUnsaved(GlassShades.Kind.PLAYBACK)

    /**
     * Save "Default": its glass as it shows now kept as Look's Playback glass ([GlassShades.save], put on the disk), then
     * [done] told whether it is, on the main thread ([main]). A display dimness tried on it stays a change Save custom
     * keeps as a new custom.
     */
    fun saveDefault(writer: SettingsWriter, main: (Runnable) -> Unit, done: (Boolean) -> Unit) {
        look.save(writer, main, listOf(GlassShades.Kind.PLAYBACK), done)
    }

    /** The picture's darkening now, percent. */
    fun display(): Int = active().display

    /** The Playback glass now: the preset on's (Default's: Look's). */
    fun glass(): GlassStyle.Shade = active().glass

    /** For the more row: the preset's name, or "Off" while it darkens and tints nothing. */
    fun label(): String = active().let { if (it.isOff) OFF else it.name }

    fun choose(id: String) {
        sp().edit().putString(ACTIVE_KEY, id).apply()
        draft = null
    }

    /**
     * [value] of the preset on a step up ([direction] 1) or down (-1). Default's glass is Look's, changed there too and
     * kept by Save "Default" or Look's Save; anything else shows at once and is kept by Save custom. With Auto, display
     * dimness and glass dimness move as one (to [MAX_DISPLAY] at most).
     */
    fun step(value: Value, direction: Int) {
        val now = active()
        if (now.isDefault && value != Value.DISPLAY) {
            val control = if (value == Value.GLASS_TINT) GlassShades.Control.TINT else GlassShades.Control.DIMNESS
            look.step(GlassShades.Kind.PLAYBACK, control, direction)
            return
        }
        edit {
            when (value) {
                Value.DISPLAY -> {
                    val d = clampDisplay(it.display + direction * STEP)
                    if (it.auto) it.copy(display = d, glassDim = d) else it.copy(display = d)
                }
                Value.GLASS_TINT -> it.copy(glassTint = GlassStyle.stepShade(it.glassTint, direction))
                Value.GLASS_DIM -> {
                    val g = GlassStyle.stepShade(it.glassDim, direction)
                    if (it.auto) clampDisplay(g).let { v -> it.copy(display = v, glassDim = v) } else it.copy(glassDim = g)
                }
            }
        }
    }

    /** Auto on puts a custom's glass dimness at its display dimness. Default has no Auto. */
    fun toggleAuto() {
        if (active().isDefault) return
        edit { if (it.auto) it.copy(auto = false) else it.copy(auto = true, glassDim = it.display) }
    }

    private fun edit(change: (Preset) -> Preset) {
        val next = change(active())
        draft = next.copy(
            display = clampDisplay(next.display),
            glassTint = GlassStyle.clampShade(next.glassTint),
            glassDim = GlassStyle.clampShade(next.glassDim),
        )
    }

    /** Keeps the changes to the custom on. */
    fun saveChanges() {
        val d = draft ?: return
        if (d.isDefault) return
        if (!replace(d)) return
        draft = null
        choose(d.id)
    }

    /** A new custom with what's on now (its three values and Auto), turned on for every video. */
    fun saveAsNew(name: String) {
        val customs = customs()
        var n = System.currentTimeMillis()
        while (customs.any { it.id == "c$n" }) n++
        val c = active().copy(id = "c$n", name = name)
        if (!store(customs + c)) return
        choose(c.id)
    }

    /** Renames the custom on (its unsaved changes are kept too, since Save was pressed). Default keeps its name. */
    fun rename(name: String) {
        val now = active()
        if (now.isDefault) return
        if (!replace(now.copy(name = name))) return
        choose(now.id)
    }

    /** Deletes a custom; Default is on again if it was the one on. */
    fun delete(id: String) {
        if (id == DEFAULT_ID) return
        store(customs().filter { it.id != id })
        if (sp().getString(ACTIVE_KEY, null) == id) choose(DEFAULT_ID)
    }

    /** Null when the name can be used, otherwise why not. */
    fun nameProblem(name: String, except: String?): String? = when {
        name.isBlank() -> "Pick a name"
        name.equals(OFF, ignoreCase = true) || name.equals(DEFAULT_NAME, ignoreCase = true) || name.toIntOrNull() != null ->
            "Pick another name"
        customs().any { it.id != except && it.name.equals(name, ignoreCase = true) } -> "That name is taken"
        else -> null
    }

    private fun replace(c: Preset): Boolean = store(customs().map { if (it.id == c.id) c else it })

    /**
     * Saves [list] as the customs. An entry of the saved ones that can't be read is kept as it is, after them; and when
     * what's saved can't be read at all, nothing is written over it (false): a custom is never lost to a write.
     */
    private fun store(list: List<Preset>): Boolean {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("display", it.display)
                    .put("tint", it.glassTint).put("dim", it.glassDim).put("auto", it.auto),
            )
        }
        val saved = sp().getString(PRESETS_KEY, null)
        if (saved != null) {
            val old = runCatching { JSONArray(saved) }.getOrNull() ?: return false
            for (i in 0 until old.length()) if (entry(old.opt(i)) == null && !isDefaultEntry(old.opt(i))) arr.put(old.opt(i))
        }
        sp().edit().putString(PRESETS_KEY, arr.toString()).apply()
        return true
    }

    /** The saved customs: an entry that can't be read is passed over, and the rest are still read. */
    private fun parse(json: String): List<Preset> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val list = ArrayList<Preset>()
        for (i in 0 until arr.length()) entry(arr.opt(i))?.let { list += it }
        return list
    }

    /** One saved custom, or null when it can't be read (or is Default, which is never saved as a custom). */
    private fun entry(value: Any?): Preset? {
        val o = value as? JSONObject ?: return null
        return runCatching {
            val id = o.getString("id")
            if (id == DEFAULT_ID) {
                null
            } else {
                Preset(
                    id,
                    o.getString("name"),
                    clampDisplay(o.optInt("display", 0)),
                    GlassStyle.clampShade(o.optInt("tint", 0)),
                    GlassStyle.clampShade(o.optInt("dim", 0)),
                    o.optBoolean("auto", false),
                )
            }
        }.getOrNull()
    }

    private fun isDefaultEntry(value: Any?): Boolean = (value as? JSONObject)?.optString("id") == DEFAULT_ID

    /**
     * The customs saved before presets, as presets ([OLD_CUSTOMS_KEY]: a display amount and a glass amount each): the
     * glass amount becomes Glass dimness, with no Glass tint; names, Auto and the one on stay. Custom 1, always there
     * then (from before customs, at the dimmer level left on), is carried over unless it was never changed: Default takes
     * its place at the top.
     */
    internal fun migrate(): List<Preset> {
        val list = ArrayList<Preset>()
        runCatching {
            val arr = JSONArray(sp().getString(OLD_CUSTOMS_KEY, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                if (id == DEFAULT_ID) continue
                list += Preset(
                    id,
                    o.getString("name"),
                    clampDisplay(o.optInt("display", OLD_DEFAULT_AMOUNT)),
                    0,
                    GlassStyle.clampShade(o.optInt("glass", OLD_DEFAULT_AMOUNT)),
                    o.optBoolean("auto", true),
                )
            }
        }
        if (list.none { it.id == OLD_FIRST_ID }) {
            val old = clampDisplay(sp().getInt(OLD_LEVEL_KEY, 0).coerceIn(0, 10) * OLD_LEVEL_STEP)
            list.add(0, Preset(OLD_FIRST_ID, OLD_FIRST_NAME, old, 0, old, auto = true))
        }
        list.removeAll { it.id == OLD_FIRST_ID && it.name == OLD_FIRST_NAME && it.isOff }
        return list
    }

    companion object {
        const val DEFAULT_ID = "default"
        const val DEFAULT_NAME = "Default"
        const val OFF = "Off"
        /** Display dimness's step, percent (the glass's steps are Look's, [GlassStyle.SHADE_STEP]). */
        const val STEP = 5
        /** The most the picture is darkened, percent: never so far that it can't be seen. */
        const val MAX_DISPLAY = 80

        /** The presets on and saved. */
        internal const val ACTIVE_KEY = "dim_custom"
        internal const val PRESETS_KEY = "dim_presets"
        /** What was saved before presets: the customs, Custom 1's id and name, and before those the dimmer's level. */
        internal const val OLD_CUSTOMS_KEY = "dim_customs"
        internal const val OLD_FIRST_ID = "c1"
        internal const val OLD_FIRST_NAME = "Custom 1"
        internal const val OLD_LEVEL_KEY = "dim_level"
        private const val OLD_LEVEL_STEP = 8
        private const val OLD_DEFAULT_AMOUNT = 40

        fun clampDisplay(percent: Int): Int = percent.coerceIn(0, MAX_DISPLAY)

        /** Changes to the preset on not kept yet; for this run of the app only, like anything not saved. */
        private var draft: Preset? = null

        /** As a new run of the app: changes not kept are gone (the tests start each from nothing). */
        internal fun forgetUnsaved() {
            draft = null
        }

        /** A display dimness tried on Default and not saved goes ([GlassShades.reset]); a custom's changes stay. */
        internal fun forgetDefaultDraft() {
            if (draft?.isDefault == true) draft = null
        }
    }
}
