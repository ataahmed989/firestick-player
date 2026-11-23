package app.fireplayer.data

import android.content.SharedPreferences
import app.fireplayer.vod.Page
import app.fireplayer.vod.data.RowLayoutStore
import app.fireplayer.vod.data.RowSettings
import app.fireplayer.vod.data.Service
import app.fireplayer.vod.data.Services
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The settings stay as the viewer set them: one process writes them and nothing reloads an older copy over them
 * ([Settings]), each change is on the disk at once ([SettingsWriter]), what's noted while watching lives apart
 * ([Settings.moveRecords]), and what's done for older versions only adds what's missing.
 */
class SettingsPersistTest {

    @Before fun freshRun() {
        GlassShades.forgetUnsaved()
        Dimmer.forgetUnsaved()
    }

    // ---- The source: the title server's process never opens the settings, and nothing reloads them ----

    private val main: File = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

    private fun sources(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()

    @Test fun noSettingsFileIsReadForAnotherProcess() {
        // MODE_MULTI_PROCESS reloads a file from the disk over what's in memory, and its backup restore can put an older
        // copy back over a write in progress.
        assertEquals(emptyList<String>(), sources(main).filter { "MODE_MULTI_PROCESS" in it.readText() }.map { it.name })
    }

    @Test fun theTitleServerNeverOpensTheSettings() {
        val titles = sources(File(main, "app/fireplayer/titles"))
        assertTrue(titles.size > 3)
        for (file in titles) {
            val text = file.readText()
            for (banned in listOf("getSharedPreferences", "KeyStore.OLD_FILE", "\"fireplayer\"", "Prefs(", "Settings.of", "KeyStore.")) {
                assertFalse("${file.name} uses $banned", banned in text)
            }
        }
    }

    @Test fun theSettingsFileIsNamedInOnePlace() {
        assertEquals(listOf("Settings.kt"), sources(main).filter { "\"fireplayer\"" in it.readText() }.map { it.name })
        assertEquals(
            emptyList<String>(),
            sources(main).filter { it.name != "KeyStore.kt" && Regex("""\bOLD_FILE\b""").containsMatchIn(it.readText()) }.map { it.name },
        )
    }

    @Test fun keepEmbeddedStylingIsOnlyChangedByItsOwnRow() {
        // Its default is On: nothing but the viewer's own switch (Subtitle style › Keep embedded styling) sets it.
        val writers = sources(main).filter { Regex("""keepAssStyling\s*=[^=]""").containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(listOf("SettingsPanels.kt"), writers)
        assertEquals(listOf("Prefs.kt"), sources(main).filter { "\"keep_ass\"" in it.readText() }.map { it.name })
    }

    // ---- One writer: each change on the disk at once ----

    @Test fun aChangeIsPutOnTheDiskAtOnceAndRepeatsWhileOneWaitsAreOneWrite() {
        val queued = ArrayList<Runnable>()
        val writer = SettingsWriter { queued += it }
        val file = CountingPrefs()
        val settings = Settings.Durable(file, writer)
        settings.edit().putBoolean("faster_downloads", true).apply()
        // In memory at once: every read has it straight away.
        assertTrue(settings.getBoolean("faster_downloads", false))
        settings.edit().putBoolean("keep_ass", true).apply()
        settings.edit().putString("vod_services_order", "netflix,disney").apply()
        assertEquals(1, queued.size)
        // A change made after the write was asked for, before it ran: an empty commit writes what's there now, so the
        // newer value is the one kept, never an older copy.
        settings.edit().putBoolean("keep_ass", false).apply()
        queued.removeAt(0).run()
        assertEquals(1, file.commits)
        assertFalse(settings.getBoolean("keep_ass", true))
        assertEquals("netflix,disney", file.real.values["vod_services_order"])
        // Once it has started, a later change has a write of its own.
        settings.edit().putInt("glass_app_tint", 40).apply()
        assertEquals(1, queued.size)
        queued.removeAt(0).run()
        assertEquals(2, file.commits)
        // An explicit Save is told whether the write reached the disk.
        var onDisk: Boolean? = null
        writer.flush(file) { onDisk = it }
        queued.removeAt(0).run()
        assertEquals(true, onDisk)
        writer.flush(FailingPrefs(MemoryPrefs())) { onDisk = it }
        queued.removeAt(0).run()
        assertEquals(false, onDisk)
    }

    // ---- Records: apart from the settings, moved once, never over a newer one ----

    @Test fun recordsMoveOutOfTheSettingsOnceAndNeverReplaceNewerOnes() {
        val writer = SettingsWriter { it.run() }
        val settings = MemoryPrefs(
            "pos:film" to 120_000L, "sync|a.srt|film|at" to "0:250", "subdelay|a.srt|film" to 300L,
            "syncjumps|tmdb:1" to "500", "syncstart|tmdb:1|web" to 200L, "syncwith|tmdb:1" to "speech",
            "check_tmdb" to "f|WORKING|1|", "player_beat" to 5L,
            "keep_ass" to false, "faster_downloads" to true, "dim_presets" to "[]", "auto_sync" to true,
        )
        val records = MemoryPrefs("pos:film" to 240_000L)
        Settings.moveRecords(settings, records, writer)
        // A newer one already there is kept; the rest are copied as they were.
        assertEquals(240_000L, records.values["pos:film"])
        assertEquals("0:250", records.values["sync|a.srt|film|at"])
        assertEquals(300L, records.values["subdelay|a.srt|film"])
        assertEquals("500", records.values["syncjumps|tmdb:1"])
        assertEquals(200L, records.values["syncstart|tmdb:1|web"])
        assertEquals("speech", records.values["syncwith|tmdb:1"])
        assertEquals("f|WORKING|1|", records.values["check_tmdb"])
        assertEquals(true, records.values[Settings.RECORDS_MOVED])
        assertFalse(records.values.containsKey("player_beat"))
        // The settings keep only the settings, each as it was.
        assertEquals(
            mapOf<String, Any?>("keep_ass" to false, "faster_downloads" to true, "dim_presets" to "[]", "auto_sync" to true),
            settings.values.toMap(),
        )
        // Run again: nothing changes.
        val before = records.values.toMap()
        Settings.moveRecords(settings, records, writer)
        assertEquals(before, records.values.toMap())
        // Stopped before the settings were cleared: the next run only clears them, copying nothing again (a record
        // forgotten since stays forgotten).
        val leftover = MemoryPrefs("subdelay|a.srt|film" to 1L, "keep_ass" to false)
        val moved = MemoryPrefs(Settings.RECORDS_MOVED to true)
        Settings.moveRecords(leftover, moved, writer)
        assertFalse(moved.values.containsKey("subdelay|a.srt|film"))
        assertEquals(mapOf<String, Any?>("keep_ass" to false), leftover.values.toMap())
        // Records that couldn't be put on the disk: the settings keep theirs, to be moved next time.
        val kept = MemoryPrefs("pos:film" to 1L)
        Settings.moveRecords(kept, FailingPrefs(MemoryPrefs()), writer)
        assertEquals(1L, kept.values["pos:film"])
        // Only what's noted while watching is a record.
        for (k in listOf("pos:x", "sync|a|b", "syncjumps|s", "syncstart|s|r", "syncwith|film:x", "subdelay|a|b", "check_os", "check_tmdb")) {
            assertTrue(k, Settings.isRecord(k))
        }
        for (k in listOf("keep_ass", "faster_downloads", "dim_presets", "vod_services_order", "vod_rows_home", "auto_sync", "sub_lang", "sync")) {
            assertFalse(k, Settings.isRecord(k))
        }
    }

    // ---- What's done for older versions only adds what's missing ----

    @Test fun nothingDoneAtStartChangesAValueTheViewerSet() {
        val night = JSONObject().put("id", "c7").put("name", "Night").put("display", 20).put("tint", 5).put("dim", 30).put("auto", false)
        val user = linkedMapOf<String, Any?>(
            "keep_ass" to false, "faster_downloads" to true, "seek_previews" to true,
            "dim_presets" to JSONArray().put(night).toString(), "dim_custom" to "c7",
            "dim_customs" to JSONArray().put(JSONObject().put("id", "c1").put("name", "Old").put("display", 40).put("glass", 40)).toString(),
            "dim_level" to 3,
            "glass_playback_tint" to 0, "glass_playback_dim" to 0, "glass_app_tint" to 70,
            "vod_services_order" to "netflix,disney", "vod_rows_home" to "home.continue:1,home.my_list:0",
        )
        val settings = MemoryPrefs(*user.map { it.key to it.value }.toTypedArray())
        repeat(2) {
            Settings.moveRecords(settings, MemoryPrefs(), SettingsWriter { it.run() })
            Dimmer(sp = { settings }).convertLegacyOnce()
            GlassShades(sp = { settings }).movePlaybackDefaultsOnce()
        }
        for ((key, value) in user) assertEquals(key, value, settings.values[key])
        // Only the once-only marker is added.
        assertEquals(user.keys + GlassShades.PLAYBACK_MOVED_KEY, settings.values.keys)
        // And reading the presets and the glass writes nothing.
        val before = settings.values.toMap()
        val dimmer = Dimmer(sp = { settings })
        assertEquals(listOf("Default", "Night"), dimmer.presets().map { it.name })
        assertEquals("c7", dimmer.activeId)
        dimmer.glass()
        dimmer.hasUnsaved()
        GlassShades(sp = { settings })[GlassShades.Kind.PLAYBACK]
        assertEquals(before, settings.values.toMap())
    }

    @Test fun aCustomThatCantBeReadIsPassedOverAndNeverLost() {
        fun preset(id: String, name: String) =
            JSONObject().put("id", id).put("name", name).put("display", 20).put("tint", 5).put("dim", 30).put("auto", false)
        val bad = JSONObject().put("id", "c2")
        val saved = MemoryPrefs("dim_presets" to JSONArray().put(preset("c1", "Night")).put(bad).put("junk").put(preset("c3", "Bright")).toString())
        val dimmer = Dimmer(sp = { saved })
        // The rest are still read; reading writes nothing.
        assertEquals(listOf("Default", "Night", "Bright"), dimmer.presets().map { it.name })
        assertEquals(setOf("dim_presets"), saved.values.keys)
        // Saving another keeps the ones that can't be read, as they were.
        dimmer.saveAsNew("Late")
        assertEquals(listOf("Night", "Bright", "Late"), Dimmer(sp = { saved }).customs().map { it.name })
        assertEquals(5, JSONArray(saved.values["dim_presets"] as String).length())
        // What can't be read at all is never written over.
        val broken = MemoryPrefs("dim_presets" to "{not json")
        val other = Dimmer(sp = { broken })
        assertEquals(emptyList<Dimmer.Preset>(), other.customs())
        other.saveAsNew("Night")
        assertEquals("{not json", broken.values["dim_presets"])
        assertEquals(Dimmer.DEFAULT_ID, other.activeId)
        // Presets from before are only saved as presets while none are saved.
        val both = MemoryPrefs("dim_presets" to JSONArray().put(preset("c1", "Night")).toString(), "dim_level" to 5)
        Dimmer(sp = { both }).convertLegacyOnce()
        assertEquals(listOf("Night"), Dimmer(sp = { both }).customs().map { it.name })
    }

    // ---- Rows and the services' order ----

    @Test fun theServiceOrderAndHiddenRowsOutliveARestartAndANewService() {
        // Stored before Prime Video was added, with a name no longer known: the viewer's order stays, the new one after.
        assertEquals(
            listOf(Service.NETFLIX, Service.DISNEY, Service.HULU, Service.HBO_MAX, Service.APPLE_TV, Service.PRIME_VIDEO),
            Services.order("netflix,old_service,disney,hulu,hbo_max,apple_tv"),
        )
        // Set in Settings › Rows, and read back as set by the next run of the app (a new year's labels too).
        val store = RowLayoutStore.Memory()
        val first = RowSettings(store) { 2026 }
        val order = listOf(Service.HULU, Service.PRIME_VIDEO, Service.NETFLIX, Service.DISNEY, Service.HBO_MAX, Service.APPLE_TV)
        first.setServiceOrder(order)
        first.setOn(Page.HOME, "home.my_list", false)
        val next = RowSettings(store) { 2027 }
        assertEquals(order, next.serviceOrder())
        assertFalse(next.entries(Page.HOME).first { it.spec.key == "home.my_list" }.on)
        assertFalse("home.my_list" in next.rows(Page.HOME).map { it.key })
    }

    // ---- The title server's copy of the TMDB key ----

    @Test fun aSavedKeyIsPassedOnAndABlankOneNeverIs() {
        val told = ArrayList<Pair<KeyStore.Key, String>>()
        val keys = MemoryPrefs()
        val old = MemoryPrefs()
        val store = KeyStore({ keys }, { old }) { key, value -> told += key to value }
        assertTrue(store.save(KeyStore.Key.TMDB, "  abc  "))
        assertFalse(store.save(KeyStore.Key.TMDB, "   "))
        assertEquals(listOf(KeyStore.Key.TMDB to "abc"), told)
    }
}

/** The settings file, counting the commits that reach it. */
internal class CountingPrefs(val real: MemoryPrefs = MemoryPrefs()) : SharedPreferences by real {
    var commits = 0

    override fun edit(): SharedPreferences.Editor {
        val e = real.edit()
        return object : SharedPreferences.Editor by e {
            override fun commit(): Boolean {
                commits++
                return e.commit()
            }
        }
    }
}

/** A settings file whose writes never reach the disk (a full or failing storage): commit says false. */
internal class FailingPrefs(private val real: MemoryPrefs) : SharedPreferences by real {
    override fun edit(): SharedPreferences.Editor {
        val e = real.edit()
        return object : SharedPreferences.Editor by e {
            override fun commit(): Boolean = false
        }
    }
}
