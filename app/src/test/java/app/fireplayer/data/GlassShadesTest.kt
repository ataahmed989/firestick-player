package app.fireplayer.data

import app.fireplayer.data.GlassShades.Control
import app.fireplayer.data.GlassShades.Kind
import app.fireplayer.ui.GlassStyle
import app.fireplayer.ui.SettingsCategory
import app.fireplayer.ui.SettingsPanels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Settings › Look's three glasses as saved, and their dropdowns' rows. */
class GlassShadesTest {

    /** Each test as a new run of the app: nothing left unsaved by another. */
    @Before fun freshRun() {
        GlassShades.forgetUnsaved()
        Dimmer.forgetUnsaved()
    }

    /** A writer that commits at once, and a main thread that runs at once. */
    private val now = SettingsWriter { it.run() }
    private val main: (Runnable) -> Unit = { it.run() }

    @Test fun theThreeGlassesAreLooksDropdownsInOrder() {
        assertEquals(listOf("App glass", "Rail, cards & header", "Playback glass"), Kind.entries.map { it.title })
        // Each is a row of App settings' Look, after the look's own rows.
        val look = SettingsCategory.LOOK.settings
        assertEquals(Kind.entries.map { it.title }, look.dropLast(2).takeLast(Kind.entries.size))
        // Then the row that keeps their changes, and the row that puts all three back.
        assertEquals(SettingsPanels.SAVE_SHADES, look[look.size - 2])
        assertEquals(SettingsPanels.RESET_SHADES, look.last())
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
        assertEquals("5% · 15%", GlassShades.badge(Kind.PLAYBACK, GlassStyle.PLAYBACK_SHADE))
        // Nothing laid over the frosted glass at all: Clear.
        assertEquals("Clear", GlassShades.badge(Kind.PLAYBACK, GlassStyle.Shade(0, dim = 0)))
        assertTrue(GlassShades.isClear(GlassStyle.Shade(0, dim = 0)))
        // No tint is no colour, but a dimness alone still darkens the glass.
        assertEquals("0% · 70%", GlassShades.badge(Kind.PLAYBACK, GlassStyle.Shade(0, dim = 70)))
        assertFalse(GlassShades.isClear(GlassStyle.Shade(0, dim = 70)))
        // Charcoal is only the tint's colour: with no tint it lays nothing.
        assertEquals("Clear", GlassShades.badge(Kind.RAIL, GlassStyle.Shade(0, charcoal = 100)))
        assertEquals("5% · 0%", GlassShades.badge(Kind.APP, GlassStyle.Shade(5, dim = 0)))
    }

    @Test fun eachGlassStartsAtItsDefaults() {
        val shades = GlassShades(sp = { MemoryPrefs() })
        // App glass: Tint 58%, Dimness 15%. Rail, cards & header: Tint 58%, Charcoal 100%. Playback glass: Tint 5%,
        // Dimness 15%.
        assertEquals(GlassStyle.Shade(58, dim = 15), shades[Kind.APP])
        assertEquals(GlassStyle.Shade(58, charcoal = 100), shades[Kind.RAIL])
        assertEquals(GlassStyle.Shade(5, dim = 15), shades[Kind.PLAYBACK])
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
        assertEquals(GlassStyle.Shade(5, dim = 20), shades.step(Kind.PLAYBACK, Control.DIMNESS, 1))
        // Shown at once, but nothing is written until Save; then all six values, each under its own key, read back by a
        // fresh reader of the same settings in the next run of the app.
        assertTrue(saved.values.isEmpty())
        assertTrue(shades.hasUnsaved())
        var onDisk: Boolean? = null
        shades.save(now, main) { onDisk = it }
        assertEquals(true, onDisk)
        assertFalse(shades.hasUnsaved())
        assertEquals(
            setOf("glass_app_tint", "glass_app_dim", "glass_rail_tint", "glass_rail_charcoal", "glass_playback_tint", "glass_playback_dim"),
            saved.values.keys,
        )
        GlassShades.forgetUnsaved()
        val again = GlassShades(sp = { saved })
        assertEquals(GlassStyle.Shade(0, dim = 0), again[Kind.APP])
        assertEquals(GlassStyle.Shade(58, charcoal = 95), again[Kind.RAIL])
        assertEquals(GlassStyle.Shade(5, dim = 20), again[Kind.PLAYBACK])
    }

    @Test fun aSavedValueOutOfRangeIsReadAsTheNearestEnd() {
        val saved = MemoryPrefs("glass_app_tint" to 250, "glass_app_dim" to -40, "glass_rail_charcoal" to 130)
        val shades = GlassShades(sp = { saved })
        assertEquals(GlassStyle.Shade(100, dim = 0), shades[Kind.APP])
        assertEquals(GlassStyle.Shade(58, charcoal = 100), shades[Kind.RAIL])
    }

    @Test fun aSavedPlaybackGlassIsTheViewersEvenClear() {
        // Never changed: nothing saved takes the new defaults, 5% and 15%, and the once-only look at it saves nothing more.
        val none = MemoryPrefs()
        GlassShades(sp = { none }).movePlaybackDefaultsOnce()
        assertEquals(GlassStyle.Shade(5, dim = 15), GlassShades(sp = { none })[Kind.PLAYBACK])
        assertEquals(setOf(GlassShades.PLAYBACK_MOVED_KEY), none.values.keys)
        // Clear (no tint, no dimness) saved by the viewer is theirs: kept, read as it is, Default's too.
        val clear = MemoryPrefs("glass_playback_tint" to 0, "glass_playback_dim" to 0)
        GlassShades(sp = { clear }).movePlaybackDefaultsOnce()
        assertEquals(GlassStyle.Shade(0, dim = 0), GlassShades(sp = { clear })[Kind.PLAYBACK])
        assertEquals(0, clear.values["glass_playback_tint"])
        assertEquals(0, clear.values["glass_playback_dim"])
        assertEquals(Dimmer.Preset(Dimmer.DEFAULT_ID, "Default", 0, 0, 0, auto = false), Dimmer(sp = { clear }).default())
        // One value saved from before the new defaults: the other showed as none beside it, and is saved as that once.
        val tint = MemoryPrefs("glass_playback_tint" to 20)
        GlassShades(sp = { tint }).movePlaybackDefaultsOnce()
        assertEquals(GlassStyle.Shade(20, dim = 0), GlassShades(sp = { tint })[Kind.PLAYBACK])
        val dim = MemoryPrefs("glass_playback_dim" to 35)
        GlassShades(sp = { dim }).movePlaybackDefaultsOnce()
        assertEquals(GlassStyle.Shade(0, dim = 35), GlassShades(sp = { dim })[Kind.PLAYBACK])
        // Done once: a later one-value save isn't completed again, and nothing saved is ever changed.
        val later = MemoryPrefs(GlassShades.PLAYBACK_MOVED_KEY to true, "glass_playback_tint" to 40)
        GlassShades(sp = { later }).movePlaybackDefaultsOnce()
        assertEquals(setOf(GlassShades.PLAYBACK_MOVED_KEY, "glass_playback_tint"), later.values.keys)
        // Reading never writes, and the other two glasses are left as they are.
        val others = MemoryPrefs("glass_app_tint" to 30)
        assertEquals(GlassStyle.Shade(30, dim = 15), GlassShades(sp = { others })[Kind.APP])
        assertEquals(GlassStyle.Shade(5, dim = 15), GlassShades(sp = { others })[Kind.PLAYBACK])
        assertEquals(setOf("glass_app_tint"), others.values.keys)
    }

    @Test fun lookKeepsAChangeOnlyWithSaveAsTheScreenDimmerDoes() {
        val saved = MemoryPrefs("glass_app_tint" to 30, "glass_app_dim" to 10)
        val shades = GlassShades(sp = { saved })
        // A step shows at once, everywhere the glass is read, and is Unsaved; nothing is written.
        assertEquals(GlassStyle.Shade(35, dim = 10), shades.step(Kind.APP, Control.TINT, 1))
        assertEquals(GlassStyle.Shade(35, dim = 10), GlassShades(sp = { saved })[Kind.APP])
        assertTrue(shades.hasUnsaved())
        assertTrue(shades.hasUnsaved(Kind.APP))
        assertFalse(shades.hasUnsaved(Kind.PLAYBACK))
        assertEquals(mapOf<String, Any?>("glass_app_tint" to 30, "glass_app_dim" to 10), saved.values.toMap())
        // Back to what's saved: nothing to save.
        shades.step(Kind.APP, Control.TINT, -1)
        assertFalse(shades.hasUnsaved())
        // Reset to defaults: shown at once, but Unsaved, so a stray press takes nothing away.
        shades.reset()
        assertEquals(GlassStyle.APP_SHADE, shades[Kind.APP])
        assertTrue(shades.hasUnsaved())
        assertEquals(30, saved.values["glass_app_tint"])
        // Not saved, it's gone in the next run of the app.
        GlassShades.forgetUnsaved()
        assertEquals(GlassStyle.Shade(30, dim = 10), GlassShades(sp = { saved })[Kind.APP])
        // A Save that can't reach the disk stays Unsaved.
        val failing = FailingPrefs(saved)
        val onFailing = GlassShades(sp = { failing })
        onFailing.reset()
        var onDisk: Boolean? = null
        onFailing.save(now, main) { onDisk = it }
        assertEquals(false, onDisk)
        assertTrue(onFailing.hasUnsaved())
        // Saved: once, all six values in one change, and no longer Unsaved.
        shades.reset()
        shades.save(now, main) { onDisk = it }
        assertEquals(true, onDisk)
        assertFalse(shades.hasUnsaved())
        GlassShades.forgetUnsaved()
        for (kind in Kind.entries) assertEquals(kind.default, GlassShades(sp = { saved })[kind])
    }

    @Test fun defaultsGlassSavedFromTheScreenDimmerKeepsLooksOtherChangesUnsaved() {
        val saved = MemoryPrefs()
        val look = GlassShades(sp = { saved })
        val dimmer = Dimmer(sp = { saved })
        look.step(Kind.APP, Control.DIMNESS, 1)
        dimmer.step(Dimmer.Value.GLASS_TINT, 1)
        assertTrue(dimmer.hasUnsaved())
        assertTrue(dimmer.defaultGlassUnsaved())
        var onDisk: Boolean? = null
        dimmer.saveDefault(now, main) { onDisk = it }
        assertEquals(true, onDisk)
        // Default's glass (Look's Playback glass) is saved; App glass's change is still Look's to save.
        assertEquals(setOf("glass_playback_tint", "glass_playback_dim"), saved.values.keys)
        assertEquals(GlassStyle.Shade(10, dim = 15), look.saved(Kind.PLAYBACK))
        assertFalse(dimmer.hasUnsaved())
        assertTrue(look.hasUnsaved())
        assertTrue(look.hasUnsaved(Kind.APP))
    }
}
