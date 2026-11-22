package app.fireplayer.data

import android.content.SharedPreferences
import app.fireplayer.data.GlassShades.Control
import app.fireplayer.data.GlassShades.Kind
import app.fireplayer.ui.DimmerWindow
import app.fireplayer.ui.GlassStyle
import app.fireplayer.ui.SettingsCategory
import app.fireplayer.ui.SettingsPanels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › Look's three glasses as saved, their dropdowns' rows, and the players' Screen dimmer's Tint. */
class GlassShadesTest {

    @Test fun theThreeGlassesAreLooksDropdownsInOrder() {
        assertEquals(listOf("App glass", "Rail, cards & header", "Playback glass"), Kind.entries.map { it.title })
        // Each is a row of App settings' Look, after the look's own rows.
        val look = SettingsCategory.LOOK.settings
        assertEquals(Kind.entries.map { it.title }, look.takeLast(Kind.entries.size))
        assertTrue(look.indexOf("Info pods") < look.indexOf(Kind.APP.title))
        // Each opens and closes on its own.
        assertEquals(Kind.entries.size, Kind.entries.map { it.part }.toSet().size)
    }

    @Test fun eachDropdownOpensToTintAndDimness() {
        assertEquals(listOf("Tint", "Dimness"), Control.entries.map { it.label })
        val shade = GlassStyle.Shade(58, 30)
        assertEquals(58, Control.TINT.of(shade))
        assertEquals(30, Control.DIMNESS.of(shade))
        // Shown as percentages.
        assertEquals("58%", SettingsPanels.percent(Control.TINT.of(shade)))
        assertEquals("0%", SettingsPanels.percent(0))
        // Six values, six keys of their own.
        val keys = Kind.entries.flatMap { k -> Control.entries.map { it.keyOf(k) } }
        assertEquals(6, keys.toSet().size)
        assertEquals(listOf("glass_app_tint", "glass_app_dimness"), Control.entries.map { it.keyOf(Kind.APP) })
    }

    @Test fun eachDropdownSaysHowItsGlassStands() {
        assertEquals("58% · 30%", GlassShades.badge(GlassStyle.APP_SHADE))
        assertEquals("58% · 100%", GlassShades.badge(GlassStyle.RAIL_SHADE))
        // No tint is no colour layer at all, whatever the dimness.
        assertEquals("Clear", GlassShades.badge(GlassStyle.PLAYBACK_SHADE))
        assertEquals("Clear", GlassShades.badge(GlassStyle.Shade(0, 70)))
        assertEquals("5% · 0%", GlassShades.badge(GlassStyle.Shade(5, 0)))
    }

    @Test fun eachGlassStartsAtItsDefaults() {
        val shades = GlassShades(sp = { MemoryPrefs() })
        assertEquals(GlassStyle.Shade(58, 30), shades[Kind.APP])
        assertEquals(GlassStyle.Shade(58, 100), shades[Kind.RAIL])
        assertEquals(GlassStyle.Shade(0, 0), shades[Kind.PLAYBACK])
        for (kind in Kind.entries) assertEquals(kind.default, shades[kind])
        assertEquals(listOf(GlassStyle.APP_SHADE, GlassStyle.RAIL_SHADE, GlassStyle.PLAYBACK_SHADE), Kind.entries.map { it.default })
    }

    @Test fun theScreenDimmersTintIsPlaybackGlasssTint() {
        // The players' Screen dimmer window (films and full-screen Live TV alike, through LiveMore) shows Playback glass's
        // Tint, under that glass's name.
        assertEquals(Kind.PLAYBACK, DimmerWindow.TINT_GLASS)
        assertEquals("Playback glass", DimmerWindow.TINT_GLASS.title)
        val saved = MemoryPrefs()
        val look = GlassShades(sp = { saved })
        val film = GlassShades(sp = { saved })
        val live = GlassShades(sp = { saved })
        // Changed in a player: Look and the other player show it.
        film.step(DimmerWindow.TINT_GLASS, Control.TINT, 1)
        film.step(DimmerWindow.TINT_GLASS, Control.TINT, 1)
        assertEquals(10, look[Kind.PLAYBACK].tint)
        assertEquals(10, live[DimmerWindow.TINT_GLASS].tint)
        // Changed in Look: the players show it.
        look.step(Kind.PLAYBACK, Control.TINT, -1)
        assertEquals(5, film[DimmerWindow.TINT_GLASS].tint)
        assertEquals(5, live[DimmerWindow.TINT_GLASS].tint)
        // Only that one value: Playback glass's dimness and the other two glasses are as they were.
        assertEquals(0, look[Kind.PLAYBACK].dim)
        assertEquals(GlassStyle.APP_SHADE, look[Kind.APP])
        assertEquals(GlassStyle.RAIL_SHADE, look[Kind.RAIL])
        assertEquals(setOf("glass_playback_tint"), saved.values.keys)
    }

    @Test fun tintAndDimnessAreSavedApartAndStepWithinNoneAndAll() {
        val saved = MemoryPrefs()
        val shades = GlassShades(sp = { saved })
        // App glass from 58% and 30%: the tint onto the 5% steps and back onto its default; the dimness down to none.
        assertEquals(GlassStyle.Shade(60, 30), shades.step(Kind.APP, Control.TINT, 1))
        assertEquals(GlassStyle.Shade(58, 30), shades.step(Kind.APP, Control.TINT, -1))
        assertEquals(GlassStyle.Shade(55, 30), shades.step(Kind.APP, Control.TINT, -1))
        repeat(6) { shades.step(Kind.APP, Control.DIMNESS, -1) }
        assertEquals(GlassStyle.Shade(55, 0), shades.step(Kind.APP, Control.DIMNESS, -1))
        repeat(12) { shades.step(Kind.APP, Control.TINT, 1) }
        assertEquals(GlassStyle.Shade(100, 0), shades[Kind.APP])
        repeat(25) { shades.step(Kind.APP, Control.TINT, -1) }
        assertEquals(GlassStyle.Shade(0, 0), shades[Kind.APP])
        // Rail, cards & header's dimness is at the top already; down a step is 95%.
        assertEquals(GlassStyle.Shade(58, 100), shades.step(Kind.RAIL, Control.DIMNESS, 1))
        assertEquals(GlassStyle.Shade(58, 95), shades.step(Kind.RAIL, Control.DIMNESS, -1))
        // Playback glass's own values were never touched.
        assertEquals(GlassStyle.PLAYBACK_SHADE, shades[Kind.PLAYBACK])
        shades.step(Kind.PLAYBACK, Control.DIMNESS, 1)
        assertEquals(GlassStyle.Shade(0, 5), shades[Kind.PLAYBACK])
        // Each value under its own key, read back by a fresh reader of the same settings.
        assertEquals(setOf("glass_app_tint", "glass_app_dimness", "glass_rail_dimness", "glass_playback_dimness"), saved.values.keys)
        val again = GlassShades(sp = { saved })
        assertEquals(GlassStyle.Shade(0, 0), again[Kind.APP])
        assertEquals(GlassStyle.Shade(58, 95), again[Kind.RAIL])
        assertEquals(GlassStyle.Shade(0, 5), again[Kind.PLAYBACK])
    }

    @Test fun aSavedValueOutOfRangeIsReadAsTheNearestEnd() {
        val saved = MemoryPrefs("glass_app_tint" to 250, "glass_app_dimness" to -40)
        assertEquals(GlassStyle.Shade(100, 0), GlassShades(sp = { saved })[Kind.APP])
    }

    /** SharedPreferences in memory. */
    private class MemoryPrefs(vararg initial: Pair<String, Any?>) : SharedPreferences {
        val values = linkedMapOf(*initial)

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = values[key] as MutableSet<String>? ?: defValues
        override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
        override fun contains(key: String) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val changes = linkedMapOf<String, Any?>()
            var clear = false
            override fun putString(key: String, value: String?) = apply { changes[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values }
            override fun putInt(key: String, value: Int) = apply { changes[key] = value }
            override fun putLong(key: String, value: Long) = apply { changes[key] = value }
            override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
            override fun remove(key: String) = apply { changes[key] = null }
            override fun clear() = apply { clear = true }
            private fun write() {
                if (clear) values.clear()
                changes.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
            }
            override fun commit(): Boolean {
                write()
                return true
            }
            override fun apply() = write()
        }
    }
}
