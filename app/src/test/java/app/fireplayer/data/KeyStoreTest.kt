package app.fireplayer.data

import android.content.SharedPreferences
import app.fireplayer.data.KeyStore.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyStoreTest {

    @Test
    fun keysOnlyTheOldFileHasAreCopiedAndStayThere() {
        val old = MemoryPrefs(
            "tmdb_key" to "tmdb-old", "os_api_key" to "os-old", "os_user" to "someone", "os_pass" to " pass word ",
            "sub_lang" to "en",
        )
        val keys = MemoryPrefs()
        KeyStore({ keys }, { old }).migrate()
        assertEquals("tmdb-old", keys.values["tmdb_key"])
        assertEquals("os-old", keys.values["os_api_key"])
        assertEquals("someone", keys.values["os_user"])
        assertEquals(" pass word ", keys.values["os_pass"])
        // Only the keys move, and the old file keeps them for an older version.
        assertFalse(keys.values.containsKey("sub_lang"))
        assertEquals("tmdb-old", old.values["tmdb_key"])
        assertEquals(" pass word ", old.values["os_pass"])
        assertEquals(1, keys.commits)
        assertEquals(0, keys.applies)
    }

    @Test
    fun aKeyAlreadyInTheNewFileIsNeverReplacedByTheOldOne() {
        val old = MemoryPrefs("tmdb_key" to "stale", "os_api_key" to "", "os_user" to "  ")
        val keys = MemoryPrefs("tmdb_key" to "fresh")
        KeyStore({ keys }, { old }).migrate()
        assertEquals("fresh", keys.values["tmdb_key"])
        // Nothing worth copying: blank values stay behind, and nothing is written.
        assertFalse(keys.values.containsKey("os_api_key"))
        assertFalse(keys.values.containsKey("os_user"))
        assertEquals(0, keys.commits)
    }

    @Test
    fun migratingTwiceChangesNothing() {
        val old = MemoryPrefs("tmdb_key" to "k")
        val keys = MemoryPrefs()
        val store = KeyStore({ keys }, { old })
        store.migrate()
        store.migrate()
        assertEquals(1, keys.commits)
        assertEquals("k", store[Key.TMDB])
    }

    @Test
    fun readsTheOldFileWhenTheNewOneHasNone() {
        val store = KeyStore({ MemoryPrefs("os_user" to "") }, { MemoryPrefs("os_user" to "someone", "tmdb_key" to "k") })
        assertEquals("someone", store[Key.OPENSUBTITLES_USER])
        assertEquals("k", store[Key.TMDB])
        assertEquals("", store[Key.OPENSUBTITLES])
        assertTrue(store.isSet(Key.TMDB))
        assertFalse(store.isSet(Key.OPENSUBTITLES_PASSWORD))
    }

    @Test
    fun anEmptyBoxNeverRemovesASavedKey() {
        val keys = MemoryPrefs("tmdb_key" to "t", "os_api_key" to "o", "os_user" to "u", "os_pass" to "p")
        val old = MemoryPrefs("tmdb_key" to "t", "os_api_key" to "o", "os_user" to "u", "os_pass" to "p")
        val store = KeyStore({ keys }, { old })
        for (key in Key.entries) {
            assertFalse(store.save(key, ""))
            assertFalse(store.save(key, "   "))
        }
        assertEquals("t", store[Key.TMDB])
        assertEquals("o", store[Key.OPENSUBTITLES])
        assertEquals("u", store[Key.OPENSUBTITLES_USER])
        assertEquals("p", store[Key.OPENSUBTITLES_PASSWORD])
        assertEquals(0, keys.commits + keys.applies + old.commits + old.applies)
    }

    @Test
    fun aNewKeyIsCommittedToBothFiles() {
        val keys = MemoryPrefs("tmdb_key" to "before")
        val old = MemoryPrefs()
        val store = KeyStore({ keys }, { old })
        assertTrue(store.save(Key.TMDB, "  after  "))
        assertEquals("after", keys.values["tmdb_key"])
        assertEquals("after", old.values["tmdb_key"])
        // A password keeps its spaces.
        assertTrue(store.save(Key.OPENSUBTITLES_PASSWORD, " secret "))
        assertEquals(" secret ", store[Key.OPENSUBTITLES_PASSWORD])
        assertEquals(2, keys.commits)
        assertEquals(2, old.commits)
        assertEquals(0, keys.applies + old.applies)
    }

    /** SharedPreferences in memory, counting how its changes were written. */
    private class MemoryPrefs(vararg initial: Pair<String, Any?>) : SharedPreferences {
        val values = linkedMapOf(*initial)
        var commits = 0
        var applies = 0

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
                commits++
                return true
            }
            override fun apply() {
                write()
                applies++
            }
        }
    }
}
