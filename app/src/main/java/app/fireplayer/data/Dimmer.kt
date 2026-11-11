package app.fireplayer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The screen dimmer's customs, each with its own display amount (the film) and glass amount (every glass control and
 * window, Glass look). One is always on, and it stays on for every video after; 0% and 0% is no dimming.
 *
 * Custom 1 is always there and keeps every change at once; it can be renamed with Save custom. Other customs are
 * made with Save custom: arrows change them on screen only, until Save custom keeps the change. "Auto" keeps a
 * custom's two amounts the same.
 */
class Dimmer(context: Context) {

    data class Custom(val id: String, val name: String, val display: Int, val glass: Int, val auto: Boolean)

    /** What's on now: the two amounts in percent, and its name for the more row. */
    data class Amounts(val display: Int, val glass: Int)

    private val sp = context.applicationContext.getSharedPreferences(KeyStore.OLD_FILE, Context.MODE_PRIVATE)

    /** The custom on, by id: Custom 1 until another is picked. */
    var activeId: String
        get() = sp.getString("dim_custom", null)?.takeIf { id -> customs().any { it.id == id } } ?: FIRST_ID
        private set(v) = sp.edit().putString("dim_custom", v).apply()

    fun customs(): List<Custom> {
        val list = ArrayList<Custom>()
        runCatching {
            val arr = JSONArray(sp.getString("dim_customs", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += Custom(
                    o.getString("id"),
                    o.getString("name"),
                    o.optInt("display", DEFAULT_AMOUNT).coerceIn(0, MAX_AMOUNT),
                    o.optInt("glass", DEFAULT_AMOUNT).coerceIn(0, MAX_AMOUNT),
                    o.optBoolean("auto", true),
                )
            }
        }
        if (list.none { it.id == FIRST_ID }) {
            // Before customs, the dimmer had levels 0 to 10 (8% a step): Custom 1 starts where that was left.
            val old = (sp.getInt("dim_level", 0).coerceIn(0, 10) * LEVEL_STEP).coerceAtMost(MAX_AMOUNT)
            list.add(0, Custom(FIRST_ID, FIRST_NAME, old, old, true))
        }
        return list
    }

    private fun store(list: List<Custom>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("display", it.display).put("glass", it.glass).put("auto", it.auto))
        }
        sp.edit().putString("dim_customs", arr.toString()).apply()
    }

    /** The custom on, with any change not saved yet. */
    fun active(): Custom {
        val id = activeId
        draft?.takeIf { it.id == id }?.let { return it }
        return customs().first { it.id == id }
    }

    /** The custom on has changes that Save custom would keep. Never for Custom 1, which keeps them at once. */
    fun hasUnsaved(): Boolean {
        val d = draft ?: return false
        return d.id == activeId && d != customs().firstOrNull { it.id == d.id }
    }

    fun amounts(): Amounts = active().let { Amounts(it.display, it.glass) }

    /** For the more row: the custom's name, or "Off" while it doesn't dim anything. */
    fun label(): String = active().let { if (it.display == 0 && it.glass == 0) "Off" else it.name }

    fun chooseCustom(id: String) {
        activeId = id
        draft = null
    }

    /** Changes the custom on: Custom 1 keeps it, any other only shows it until saved. */
    fun edit(change: (Custom) -> Custom) {
        val now = active()
        val next = change(now).let { it.copy(display = it.display.coerceIn(0, MAX_AMOUNT), glass = it.glass.coerceIn(0, MAX_AMOUNT)) }
        if (next.id == FIRST_ID) {
            replace(next)
            draft = null
        } else {
            draft = next
        }
    }

    /** Arrows on the display amount; with Auto the glass follows. */
    fun stepDisplay(direction: Int) = edit {
        val d = it.display + direction * STEP
        if (it.auto) it.copy(display = d, glass = d) else it.copy(display = d)
    }

    /** Arrows on the glass amount; with Auto the display follows. */
    fun stepGlass(direction: Int) = edit {
        val g = it.glass + direction * STEP
        if (it.auto) it.copy(display = g, glass = g) else it.copy(glass = g)
    }

    /** Auto on puts the glass at the display's amount. */
    fun toggleAuto() = edit { if (it.auto) it.copy(auto = false) else it.copy(auto = true, glass = it.display) }

    /** Keeps the changes to the custom on. */
    fun saveChanges() {
        val d = draft ?: return
        replace(d)
        draft = null
        activeId = d.id
    }

    /** A new custom with what's on now, turned on for every video. */
    fun saveAsNew(name: String) {
        val a = amounts()
        val auto = active().auto
        val c = Custom("c" + System.currentTimeMillis(), name, a.display, a.glass, auto)
        store(customs() + c)
        draft = null
        activeId = c.id
    }

    /** Renames the custom on (its unsaved changes are kept too, since Save was pressed). */
    fun rename(name: String) {
        val now = active()
        replace(now.copy(name = name))
        draft = null
        activeId = now.id
    }

    fun delete(id: String) {
        if (id == FIRST_ID) return
        store(customs().filter { it.id != id })
        if (sp.getString("dim_custom", null) == id) chooseCustom(FIRST_ID)
    }

    /** Null when the name can be used, otherwise why not. */
    fun nameProblem(name: String, except: String?): String? = when {
        name.isBlank() -> "Pick a name"
        name.equals("Off", ignoreCase = true) || name.toIntOrNull() != null -> "Pick another name"
        customs().any { it.id != except && it.name.equals(name, ignoreCase = true) } -> "That name is taken"
        else -> null
    }

    private fun replace(c: Custom) {
        store(customs().map { if (it.id == c.id) c else it })
    }

    companion object {
        const val FIRST_ID = "c1"
        const val FIRST_NAME = "Custom 1"
        const val STEP = 5
        const val MAX_AMOUNT = 80
        const val LEVEL_STEP = 8
        private const val DEFAULT_AMOUNT = 40

        /** Changes to a saved custom not kept yet; for this run of the app only, like anything not saved. */
        private var draft: Custom? = null
    }
}
