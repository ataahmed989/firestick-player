package app.fireplayer.data

import app.fireplayer.data.GlassShades.Control
import app.fireplayer.data.GlassShades.Kind
import app.fireplayer.ui.GlassStyle
import app.fireplayer.ui.SettingsCategory
import app.fireplayer.ui.SettingsPanels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › Look's three glasses as saved, and their dropdowns' rows. */
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

    @Test fun eachDropdownOpensToItsTwoRows() {
        assertEquals(listOf("Tint", "Dimness"), Kind.APP.controls.map { it.label })
        assertEquals(listOf("Tint", "Charcoal"), Kind.RAIL.controls.map { it.label })
        assertEquals(listOf("Tint", "Dimness"), Kind.PLAYBACK.controls.map { it.label })
        val shade = GlassStyle.Shade(58, dim = 30, charcoal = 75)
        assertEquals(58, Control.TINT.of(shade))
        assertEquals(30, Control.DIMNESS.of(shade))
        assertEquals(75, Control.CHARCOAL.of(shade))
        // Shown as percentages.
        assertEquals("58%", SettingsPanels.percent(Control.TINT.of(shade)))
        assertEquals("0%", SettingsPanels.percent(0))
        assertEquals("100%", SettingsPanels.percent(100))
        // Six values, six keys of their own.
        val keys = Kind.entries.flatMap { k -> k.controls.map { k.keyOf(it) } }
        assertEquals(6, keys.toSet().size)
        assertEquals(listOf("glass_app_tint", "glass_app_dim"), Kind.APP.controls.map { Kind.APP.keyOf(it) })
        assertEquals(listOf("glass_rail_tint", "glass_rail_charcoal"), Kind.RAIL.controls.map { Kind.RAIL.keyOf(it) })
        assertEquals(listOf("glass_playback_tint", "glass_playback_dim"), Kind.PLAYBACK.controls.map { Kind.PLAYBACK.keyOf(it) })
        // A glass has no row it doesn't show.
        assertTrue(runCatching { Kind.RAIL.keyOf(Control.DIMNESS) }.isFailure)
        assertTrue(runCatching { Kind.APP.keyOf(Control.CHARCOAL) }.isFailure)
    }

    @Test fun eachDropdownSaysHowItsGlassStands() {
        assertEquals("58% · 15%", GlassShades.badge(Kind.APP, GlassStyle.APP_SHADE))
        assertEquals("58% · 100%", GlassShades.badge(Kind.RAIL, GlassStyle.RAIL_SHADE))
        // Nothing laid over the frosted glass at all: Clear.
        assertEquals("Clear", GlassShades.badge(Kind.PLAYBACK, GlassStyle.PLAYBACK_SHADE))
        assertTrue(GlassShades.isClear(GlassStyle.PLAYBACK_SHADE))
        // No tint is no colour, but a dimness alone still darkens the glass.
        assertEquals("0% · 70%", GlassShades.badge(Kind.PLAYBACK, GlassStyle.Shade(0, dim = 70)))
        assertFalse(GlassShades.isClear(GlassStyle.Shade(0, dim = 70)))
        // Charcoal is only the tint's colour: with no tint it lays nothing.
        assertEquals("Clear", GlassShades.badge(Kind.RAIL, GlassStyle.Shade(0, charcoal = 100)))
        assertEquals("5% · 0%", GlassShades.badge(Kind.APP, GlassStyle.Shade(5, dim = 0)))
    }

    @Test fun eachGlassStartsAtItsDefaults() {
        val shades = GlassShades(sp = { MemoryPrefs() })
        // App glass: Tint 58%, Dimness 15%. Rail, cards & header: Tint 58%, Charcoal 100%. Playback glass: none.
        assertEquals(GlassStyle.Shade(58, dim = 15), shades[Kind.APP])
        assertEquals(GlassStyle.Shade(58, charcoal = 100), shades[Kind.RAIL])
        assertEquals(GlassStyle.Shade(0, dim = 0), shades[Kind.PLAYBACK])
        for (kind in Kind.entries) assertEquals(kind.default, shades[kind])
        assertEquals(listOf(GlassStyle.APP_SHADE, GlassStyle.RAIL_SHADE, GlassStyle.PLAYBACK_SHADE), Kind.entries.map { it.default })
    }

    @Test fun eachValueIsSavedApartAndStepsWithinNoneAndAll() {
        val saved = MemoryPrefs()
        val shades = GlassShades(sp = { saved })
        // App glass from 58% and 15%: the tint onto the 5% steps and back onto its default; the dimness down to none.
        assertEquals(GlassStyle.Shade(60, dim = 15), shades.step(Kind.APP, Control.TINT, 1))
        assertEquals(GlassStyle.Shade(58, dim = 15), shades.step(Kind.APP, Control.TINT, -1))
        assertEquals(GlassStyle.Shade(55, dim = 15), shades.step(Kind.APP, Control.TINT, -1))
        repeat(2) { shades.step(Kind.APP, Control.DIMNESS, -1) }
        assertEquals(GlassStyle.Shade(55, dim = 0), shades.step(Kind.APP, Control.DIMNESS, -1))
        repeat(12) { shades.step(Kind.APP, Control.TINT, 1) }
        assertEquals(GlassStyle.Shade(100, dim = 0), shades[Kind.APP])
        repeat(25) { shades.step(Kind.APP, Control.TINT, -1) }
        assertEquals(GlassStyle.Shade(0, dim = 0), shades[Kind.APP])
        // Rail, cards & header's charcoal is at the top already; down a step is 95%.
        assertEquals(GlassStyle.Shade(58, charcoal = 100), shades.step(Kind.RAIL, Control.CHARCOAL, 1))
        assertEquals(GlassStyle.Shade(58, charcoal = 95), shades.step(Kind.RAIL, Control.CHARCOAL, -1))
        // Playback glass's own values only change by its own rows.
        assertEquals(GlassStyle.PLAYBACK_SHADE, shades[Kind.PLAYBACK])
        assertEquals(GlassStyle.Shade(0, dim = 5), shades.step(Kind.PLAYBACK, Control.DIMNESS, 1))
        // Each value under its own key, read back by a fresh reader of the same settings.
        assertEquals(setOf("glass_app_tint", "glass_app_dim", "glass_rail_charcoal", "glass_playback_dim"), saved.values.keys)
        val again = GlassShades(sp = { saved })
        assertEquals(GlassStyle.Shade(0, dim = 0), again[Kind.APP])
        assertEquals(GlassStyle.Shade(58, charcoal = 95), again[Kind.RAIL])
        assertEquals(GlassStyle.Shade(0, dim = 5), again[Kind.PLAYBACK])
    }

    @Test fun aSavedValueOutOfRangeIsReadAsTheNearestEnd() {
        val saved = MemoryPrefs("glass_app_tint" to 250, "glass_app_dim" to -40, "glass_rail_charcoal" to 130)
        val shades = GlassShades(sp = { saved })
        assertEquals(GlassStyle.Shade(100, dim = 0), shades[Kind.APP])
        assertEquals(GlassStyle.Shade(58, charcoal = 100), shades[Kind.RAIL])
    }
}
