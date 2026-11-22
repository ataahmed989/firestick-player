package app.fireplayer.data

import android.content.SharedPreferences
import app.fireplayer.data.GlassShades.Kind
import app.fireplayer.ui.DimmerWindow
import app.fireplayer.ui.GlassStyle
import app.fireplayer.ui.SettingsPanels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › Look's tint and dimness of the two glasses, as saved, and the players' Screen dimmer's Tint. */
class GlassShadesTest {

    @Test fun eachGlassStartsAtItsDefaults() {
        val shades = GlassShades(sp = { MemoryPrefs() })
        assertEquals(GlassStyle.APP_SHADE, shades[Kind.APP])
        assertEquals(GlassStyle.PLAYBACK_SHADE, shades[Kind.PLAYBACK])
        // App glass: a tint and a dimness of its own; Playback glass: none of either.
        assertTrue(shades[Kind.APP].tint > 0 && shades[Kind.APP].dim > 0)
        assertEquals(GlassStyle.Shade(0, 0), shades[Kind.PLAYBACK])
        assertEquals(listOf("App glass", "Playback glass"), Kind.entries.map { it.title })
    }

    @Test fun theScreenDimmersTintIsPlaybackGlasssTint() {
        // The players' Screen dimmer window (films and full-screen Live TV alike) shows Playback glass's Tint.
        assertEquals(Kind.PLAYBACK, DimmerWindow.TINT)
        assertEquals("Tint", SettingsPanels.SHADE_TINT)
        val saved = MemoryPrefs()
        val look = GlassShades(sp = { saved })
        val film = GlassShades(sp = { saved })
        val live = GlassShades(sp = { saved })
        // Changed in a player: Look and the other player show it.
        film.stepTint(DimmerWindow.TINT, 1)
        film.stepTint(DimmerWindow.TINT, 1)
        assertEquals(10, look[Kind.PLAYBACK].tint)
        assertEquals(10, live[DimmerWindow.TINT].tint)
        // Changed in Look: the players show it.
        look.stepTint(Kind.PLAYBACK, -1)
        assertEquals(5, film[DimmerWindow.TINT].tint)
        assertEquals(5, live[DimmerWindow.TINT].tint)
        // Only that one value: Playback glass's dimness and App glass are as they were.
        assertEquals(0, look[Kind.PLAYBACK].dim)
        assertEquals(GlassStyle.APP_SHADE, look[Kind.APP])
    }

    @Test fun tintAndDimnessAreSavedApartAndStepWithinNoneAndAll() {
        val saved = MemoryPrefs()
        val shades = GlassShades(sp = { saved })
        // From 55% and 5%: dimness to none, a step below that stays none; tint up to all, and no further.
        assertEquals(GlassStyle.Shade(55, 0), shades.stepDim(Kind.APP, -1))
        assertEquals(GlassStyle.Shade(55, 0), shades.stepDim(Kind.APP, -1))
        repeat(12) { shades.stepTint(Kind.APP, 1) }
        assertEquals(GlassStyle.Shade(100, 0), shades[Kind.APP])
        repeat(25) { shades.stepTint(Kind.APP, -1) }
        assertEquals(GlassStyle.Shade(0, 0), shades[Kind.APP])
        // Playback glass's own values were never touched.
        assertEquals(GlassStyle.PLAYBACK_SHADE, shades[Kind.PLAYBACK])
        shades.stepDim(Kind.PLAYBACK, 1)
        assertEquals(GlassStyle.Shade(0, 5), shades[Kind.PLAYBACK])
        // Four values, four keys, read back by a fresh reader of the same settings.
        assertEquals(setOf("app_glass_tint", "app_glass_dim", "playback_glass_dim"), saved.values.keys)
        assertEquals(GlassStyle.Shade(0, 5), GlassShades(sp = { saved })[Kind.PLAYBACK])
        assertEquals(4, Kind.entries.flatMap { listOf(it.tintKey, it.dimKey) }.toSet().size)
    }

    @Test fun aSavedValueOutOfRangeIsReadAsTheNearestEnd() {
        val saved = MemoryPrefs("app_glass_tint" to 250, "app_glass_dim" to -40)
        assertEquals(GlassStyle.Shade(100, 0), GlassShades(sp = { saved })[Kind.APP])
    }

    @Test fun valuesShowAsPercentages() {
        assertEquals("55%", SettingsPanels.percent(55))
        assertEquals("0%", SettingsPanels.percent(0))
        assertEquals("Dimness", SettingsPanels.SHADE_DIM)
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
