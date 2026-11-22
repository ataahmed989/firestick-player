package app.fireplayer.data

import app.fireplayer.data.Dimmer.Value
import app.fireplayer.data.GlassShades.Control
import app.fireplayer.data.GlassShades.Kind
import app.fireplayer.ui.DimmerWindow
import app.fireplayer.ui.Glass
import app.fireplayer.ui.GlassBakes
import app.fireplayer.ui.GlassStyle
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The players' dimness settings (the Screen dimmer, the same in the film player and Live TV full screen): Default, which
 * is Settings › Look › Playback glass, the customs with their three values, and the customs saved before presets.
 */
class DimmerTest {

    /** Each test as a new run of the app: nothing left unsaved by another. */
    @Before fun freshRun() = Dimmer.forgetUnsaved()

    private fun custom(id: String, name: String, display: Int, glass: Int, auto: Boolean) =
        JSONObject().put("id", id).put("name", name).put("display", display).put("glass", glass).put("auto", auto)

    @Test fun theRowsAndTheirNames() {
        assertEquals(listOf("Display dimness", "Glass tint", "Glass dimness"), Value.entries.map { it.label })
        assertEquals("Presets", DimmerWindow.PRESETS)
        val p = Dimmer.Preset("c5", "Night", display = 20, glassTint = 15, glassDim = 30, auto = false)
        assertEquals(listOf(20, 15, 30), Value.entries.map { it.of(p) })
        // A preset's row while another is on: its three values, in the rows' order.
        assertEquals("20% · 15% · 30%", p.values)
        // Its glass is the Playback glass while it's on: the tint, then the dimness.
        assertEquals(GlassStyle.Shade(15, dim = 30), p.glass)
    }

    @Test fun defaultIsFirstAndIsLooksPlaybackGlass() {
        val saved = MemoryPrefs()
        val dimmer = Dimmer(sp = { saved })
        val presets = dimmer.presets()
        assertEquals(listOf("Default"), presets.map { it.name })
        val default = presets.first()
        assertTrue(default.isDefault)
        // Display dimness 0%, and the glass of Look's Playback glass (5% and 15% until changed).
        assertEquals(Dimmer.Preset(Dimmer.DEFAULT_ID, "Default", 0, 5, 15, auto = false), default)
        assertEquals(default, dimmer.active())
        assertEquals(0, dimmer.display())
        assertEquals(GlassStyle.PLAYBACK_SHADE, dimmer.glass())
        // Its glass has a tint and a dimness: the dimmer's button says its name.
        assertEquals("Default", dimmer.label())
        // Off only while it darkens and tints nothing at all.
        assertTrue(default.copy(glassTint = 0, glassDim = 0).isOff)
        assertFalse(dimmer.hasUnsaved())
        // Default can be neither deleted nor renamed, and its name isn't a custom's.
        dimmer.delete(Dimmer.DEFAULT_ID)
        dimmer.rename("Bright")
        assertEquals("Default", dimmer.active().name)
        assertEquals("Pick another name", dimmer.nameProblem("default", null))
        assertEquals("Pick another name", dimmer.nameProblem("Off", null))
    }

    @Test fun defaultMirrorsLookBothWays() {
        val saved = MemoryPrefs()
        val look = GlassShades(sp = { saved })
        val film = Dimmer(sp = { saved })
        val live = Dimmer(sp = { saved })
        // Changed in Look: Default shows it, in both players.
        look.step(Kind.PLAYBACK, Control.TINT, 1)
        look.step(Kind.PLAYBACK, Control.TINT, 1)
        look.step(Kind.PLAYBACK, Control.DIMNESS, 1)
        assertEquals(GlassStyle.Shade(15, dim = 20), film.glass())
        assertEquals(15, live.active().glassTint)
        assertEquals(20, live.active().glassDim)
        assertEquals("15% · 20%", GlassShades.badge(Kind.PLAYBACK, look[Kind.PLAYBACK]))
        assertEquals("Default", film.label())
        // Changed on Default in a player: Look shows it at once (nothing to save), and so does the other player.
        film.step(Value.GLASS_TINT, -1)
        film.step(Value.GLASS_DIM, 1)
        film.step(Value.GLASS_DIM, 1)
        assertEquals(GlassStyle.Shade(10, dim = 30), look[Kind.PLAYBACK])
        assertEquals(GlassStyle.Shade(10, dim = 30), live.glass())
        assertFalse(film.hasUnsaved())
        // Only Playback glass's two values: App glass and Rail, cards & header are as they were, and no custom was made.
        assertEquals(GlassStyle.APP_SHADE, look[Kind.APP])
        assertEquals(GlassStyle.RAIL_SHADE, look[Kind.RAIL])
        assertEquals(
            setOf("glass_playback_tint", "glass_playback_dim"),
            saved.values.keys - Dimmer.PRESETS_KEY - GlassShades.PLAYBACK_MOVED_KEY,
        )
        assertEquals(emptyList<Dimmer.Preset>(), film.customs())
        // Up to 100%, and never below none.
        repeat(30) { film.step(Value.GLASS_TINT, 1) }
        repeat(30) { live.step(Value.GLASS_DIM, -1) }
        assertEquals(GlassStyle.Shade(100, dim = 0), look[Kind.PLAYBACK])
    }

    @Test fun aDisplayDimnessTriedOnDefaultIsKeptAsANewCustom() {
        val saved = MemoryPrefs()
        val dimmer = Dimmer(sp = { saved })
        GlassShades(sp = { saved }).step(Kind.PLAYBACK, Control.TINT, 1)
        dimmer.step(Value.DISPLAY, 1)
        dimmer.step(Value.DISPLAY, 1)
        // Shown at once, but Default keeps no display dimness of its own: Save custom would keep it, as a new custom.
        assertEquals(10, dimmer.display())
        assertTrue(dimmer.hasUnsaved())
        assertEquals(0, dimmer.default().display)
        assertEquals(0, Dimmer(sp = { saved }).presets().first().display)
        dimmer.saveAsNew("Evening")
        val evening = dimmer.active()
        assertEquals(Dimmer.Preset(evening.id, "Evening", 10, 10, 15, auto = false), evening)
        assertEquals(listOf("Default", "Evening"), dimmer.presets().map { it.name })
        assertFalse(dimmer.hasUnsaved())
        // Default is as it was: no display dimness, Look's glass.
        assertEquals(Dimmer.Preset(Dimmer.DEFAULT_ID, "Default", 0, 10, 15, auto = false), dimmer.default())
        // A display dimness goes to 80% at most.
        repeat(30) { dimmer.step(Value.DISPLAY, 1) }
        assertEquals(Dimmer.MAX_DISPLAY, dimmer.display())
        assertEquals(80, Dimmer.MAX_DISPLAY)
    }

    @Test fun aCustomHasThreeValuesKeptWithSaveCustom() {
        val saved = MemoryPrefs()
        val dimmer = Dimmer(sp = { saved })
        dimmer.saveAsNew("Night")
        val id = dimmer.activeId
        // A custom starts from what was on (Default: 0%, 5% and 15%), with no display tint: the picture is only darkened.
        assertEquals(Dimmer.Preset(id, "Night", 0, 5, 15, auto = false), dimmer.active())
        repeat(4) { dimmer.step(Value.DISPLAY, 1) }
        repeat(2) { dimmer.step(Value.GLASS_TINT, 1) }
        repeat(3) { dimmer.step(Value.GLASS_DIM, 1) }
        // Each its own value, shown at once: the picture, and the Playback glass over it.
        assertEquals(Dimmer.Preset(id, "Night", 20, 15, 30, auto = false), dimmer.active())
        assertEquals(GlassStyle.Shade(15, dim = 30), dimmer.glass())
        assertEquals(20, dimmer.display())
        assertEquals("Night", dimmer.label())
        // Kept only with Save custom.
        assertTrue(dimmer.hasUnsaved())
        assertEquals(Dimmer.Preset(id, "Night", 0, 5, 15, auto = false), dimmer.customs().single())
        dimmer.saveChanges()
        assertFalse(dimmer.hasUnsaved())
        // Saved: the other player and the next run show the same.
        Dimmer.forgetUnsaved()
        val other = Dimmer(sp = { saved })
        assertEquals(Dimmer.Preset(id, "Night", 20, 15, 30, auto = false), other.active())
        assertEquals("0% · 5% · 15%", other.presets().first().values)
        // Look's Playback glass is Default's, not touched by a custom.
        assertEquals(GlassStyle.PLAYBACK_SHADE, GlassShades(sp = { saved })[Kind.PLAYBACK])
        // Renamed, and a second one added; names are their own.
        other.rename("Late night")
        assertEquals("That name is taken", other.nameProblem("late NIGHT", null))
        assertNull(other.nameProblem("late night", id))
        other.saveAsNew("Bright")
        assertEquals(listOf("Default", "Late night", "Bright"), other.presets().map { it.name })
        assertEquals(3, other.presets().map { it.id }.toSet().size)
    }

    @Test fun presetsArePickedAndDeleted() {
        val saved = MemoryPrefs()
        val dimmer = Dimmer(sp = { saved })
        dimmer.step(Value.DISPLAY, 1)
        dimmer.saveAsNew("Night")
        val night = dimmer.activeId
        dimmer.step(Value.GLASS_DIM, 1)
        dimmer.saveChanges()
        // Picking Default: Look's glass over playback, no display dimness.
        dimmer.choose(Dimmer.DEFAULT_ID)
        assertEquals(GlassStyle.PLAYBACK_SHADE, dimmer.glass())
        assertEquals(0, dimmer.display())
        assertEquals(Dimmer.DEFAULT_ID, Dimmer(sp = { saved }).activeId)
        // Picking the custom again: its own.
        dimmer.choose(night)
        assertEquals(GlassStyle.Shade(5, dim = 20), dimmer.glass())
        assertEquals(5, dimmer.display())
        // Unsaved changes go when another preset is picked.
        dimmer.step(Value.GLASS_TINT, 1)
        dimmer.choose(Dimmer.DEFAULT_ID)
        dimmer.choose(night)
        assertEquals(5, dimmer.active().glassTint)
        // Deleting the one on puts Default on.
        dimmer.delete(night)
        assertEquals(Dimmer.DEFAULT_ID, dimmer.activeId)
        assertEquals(listOf("Default"), dimmer.presets().map { it.name })
    }

    @Test fun autoKeepsDisplayDimnessAndGlassDimnessTogether() {
        val saved = MemoryPrefs()
        val dimmer = Dimmer(sp = { saved })
        dimmer.saveAsNew("Night")
        repeat(3) { dimmer.step(Value.DISPLAY, 1) }
        dimmer.toggleAuto()
        // On: the glass dimness is put at the display's.
        assertEquals(Dimmer.Preset(dimmer.activeId, "Night", 15, 5, 15, auto = true), dimmer.active())
        dimmer.step(Value.DISPLAY, 1)
        assertEquals(20, dimmer.active().glassDim)
        dimmer.step(Value.GLASS_DIM, -1)
        assertEquals(15, dimmer.active().display)
        // The tint is its own.
        dimmer.step(Value.GLASS_TINT, 1)
        assertEquals(Dimmer.Preset(dimmer.activeId, "Night", 15, 10, 15, auto = true), dimmer.active())
        // Together they stop where the display does.
        repeat(30) { dimmer.step(Value.GLASS_DIM, 1) }
        assertEquals(80, dimmer.active().display)
        assertEquals(80, dimmer.active().glassDim)
        // Off: each its own again.
        dimmer.toggleAuto()
        dimmer.step(Value.GLASS_DIM, 1)
        assertEquals(Dimmer.Preset(dimmer.activeId, "Night", 80, 10, 85, auto = false), dimmer.active())
        // Default has no Auto.
        dimmer.choose(Dimmer.DEFAULT_ID)
        dimmer.toggleAuto()
        assertFalse(dimmer.active().auto)
        assertFalse(dimmer.hasUnsaved())
    }

    @Test fun customsSavedBeforePresetsKeepTheirValuesWithTheGlassAmountAsGlassDimness() {
        val old = JSONArray()
            .put(custom("c1", "Custom 1", 30, 30, auto = true))
            .put(custom("c99", "Night", 40, 20, auto = false))
        val saved = MemoryPrefs("dim_customs" to old.toString(), "dim_custom" to "c99")
        val dimmer = Dimmer(sp = { saved })
        assertEquals(
            listOf(
                Dimmer.Preset(Dimmer.DEFAULT_ID, "Default", 0, 5, 15, auto = false),
                Dimmer.Preset("c1", "Custom 1", 30, 0, 30, auto = true),
                Dimmer.Preset("c99", "Night", 40, 0, 20, auto = false),
            ),
            dimmer.presets(),
        )
        // The one on stays on: its glass amount is now the Playback glass's dimness, with no tint.
        assertEquals("c99", dimmer.activeId)
        assertEquals(GlassStyle.Shade(0, dim = 20), dimmer.glass())
        assertEquals(40, dimmer.display())
        // Read once: kept as presets from then on, the old list left as it was.
        assertTrue(saved.values.containsKey(Dimmer.PRESETS_KEY))
        assertEquals(old.toString(), saved.values["dim_customs"])
        dimmer.delete("c1")
        assertEquals(listOf("Default", "Night"), Dimmer(sp = { saved }).presets().map { it.name })
    }

    @Test fun anUntouchedCustom1GivesWayToDefault() {
        // Custom 1 was always there; never changed, Default takes its place, and it's on if Custom 1 was.
        val untouched = MemoryPrefs(
            "dim_customs" to JSONArray().put(custom("c1", "Custom 1", 0, 0, auto = true)).toString(),
            "dim_custom" to "c1",
        )
        val dimmer = Dimmer(sp = { untouched })
        assertEquals(listOf("Default"), dimmer.presets().map { it.name })
        assertEquals(Dimmer.DEFAULT_ID, dimmer.activeId)
        // Renamed, it's the user's: kept.
        val renamed = MemoryPrefs("dim_customs" to JSONArray().put(custom("c1", "Reading", 0, 0, auto = true)).toString())
        assertEquals(listOf("Default", "Reading"), Dimmer(sp = { renamed }).presets().map { it.name })
        // From before customs, a dimmer level left on (8% a step) is Custom 1.
        val level = MemoryPrefs("dim_level" to 5)
        assertEquals(Dimmer.Preset("c1", "Custom 1", 40, 0, 40, auto = true), Dimmer(sp = { level }).customs().single())
        // Nothing saved at all: Default alone.
        assertEquals(emptyList<Dimmer.Preset>(), Dimmer(sp = { MemoryPrefs() }).customs())
    }

    @Test fun theGlassInUseIsThePresetOns() {
        val app = Glass.appShade
        val rail = Glass.railShade
        val playback = Glass.playbackShade
        try {
            val saved = MemoryPrefs()
            val look = GlassShades(sp = { saved })
            val dimmer = Dimmer(sp = { saved })
            // Default: Look's Playback glass over playback; App glass and Rail, cards & header at their own.
            look.step(Kind.PLAYBACK, Control.TINT, 1)
            look.apply()
            assertEquals(GlassStyle.Shade(10, dim = 15), Glass.playbackShade)
            assertEquals(GlassStyle.APP_SHADE, Glass.appShade)
            assertEquals(GlassStyle.RAIL_SHADE, Glass.railShade)
            assertEquals(0.15f, Glass.dim, 0.0001f)
            // A custom on: its glass tint and glass dimness, whatever Look's are. The player's other glass (its buttons
            // and chips) is darkened by the same glass dimness; its windows by their Playback glass layer.
            dimmer.saveAsNew("Night")
            repeat(2) { dimmer.step(Value.GLASS_DIM, 1) }
            repeat(3) { dimmer.step(Value.GLASS_TINT, 1) }
            assertTrue(look.apply())
            assertEquals(GlassStyle.Shade(25, dim = 25), Glass.playbackShade)
            assertEquals(0.25f, Glass.dim, 0.0001f)
            assertEquals(GlassStyle.Shade(25, dim = 25).color, GlassBakes.tintOf(Glass.Kind.PANEL, GlassBakes.FILM))
            assertFalse(look.apply())
            // Back to Default: Look's again.
            dimmer.choose(Dimmer.DEFAULT_ID)
            assertTrue(look.apply())
            assertEquals(GlassStyle.Shade(10, dim = 15), Glass.playbackShade)
            assertEquals(0.15f, Glass.dim, 0.0001f)
        } finally {
            Glass.useShades(app, rail, playback)
        }
    }

    @Test fun resetToDefaultsPutsTheGlassesBackAndKeepsTheCustomsAndThePresetOn() {
        val saved = MemoryPrefs()
        val look = GlassShades(sp = { saved })
        val dimmer = Dimmer(sp = { saved })
        repeat(3) { look.step(Kind.APP, Control.TINT, 1) }
        look.step(Kind.APP, Control.DIMNESS, -1)
        repeat(2) { look.step(Kind.RAIL, Control.CHARCOAL, -1) }
        look.step(Kind.PLAYBACK, Control.TINT, 1)
        // A custom made from Default as it was (10% and 15%), picked.
        dimmer.saveAsNew("Night")
        val night = dimmer.activeId
        repeat(2) { dimmer.step(Value.DISPLAY, 1) }
        dimmer.saveChanges()
        look.reset()
        // All three at their defaults: App glass 58% · 15%, Rail, cards & header 58% · 100%, Playback glass 5% · 15%.
        assertEquals(GlassStyle.Shade(58, dim = 15), look[Kind.APP])
        assertEquals(GlassStyle.Shade(58, charcoal = 100), look[Kind.RAIL])
        assertEquals(GlassStyle.Shade(5, dim = 15), look[Kind.PLAYBACK])
        // The custom is kept as it was, and stays on; Default is back to its defaults.
        assertEquals(night, Dimmer(sp = { saved }).activeId)
        assertEquals(listOf("Default", "Night"), dimmer.presets().map { it.name })
        assertEquals(Dimmer.Preset(night, "Night", 10, 10, 15, auto = false), dimmer.active())
        assertEquals(Dimmer.Preset(Dimmer.DEFAULT_ID, "Default", 0, 5, 15, auto = false), dimmer.default())
        // A custom's changes not saved yet stay too.
        dimmer.step(Value.GLASS_TINT, 1)
        look.reset()
        assertEquals(15, dimmer.active().glassTint)
        assertTrue(dimmer.hasUnsaved())
        // With Default on, it shows the defaults: a display dimness tried on it and not saved goes.
        dimmer.choose(Dimmer.DEFAULT_ID)
        repeat(2) { look.step(Kind.PLAYBACK, Control.DIMNESS, 1) }
        dimmer.step(Value.DISPLAY, 1)
        assertEquals(5, dimmer.display())
        look.reset()
        assertEquals(Dimmer.DEFAULT_ID, dimmer.activeId)
        assertEquals(Dimmer.Preset(Dimmer.DEFAULT_ID, "Default", 0, 5, 15, auto = false), dimmer.active())
        assertEquals(GlassStyle.PLAYBACK_SHADE, dimmer.glass())
        assertFalse(dimmer.hasUnsaved())
        // As if never changed: the glasses' own values are gone, the customs and the one on are kept.
        assertEquals(setOf(Dimmer.PRESETS_KEY, Dimmer.ACTIVE_KEY, GlassShades.PLAYBACK_MOVED_KEY), saved.values.keys)
    }
}
