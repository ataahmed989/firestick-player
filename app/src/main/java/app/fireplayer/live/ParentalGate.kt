package app.fireplayer.live

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import app.fireplayer.data.KeyStore
import app.fireplayer.data.Settings
import java.security.MessageDigest

/**
 * The parental gate for Live TV: a PIN (hashed, kept in the KeyStore file, never logged), the
 * "require PIN for" rules, channel/group blocking, and the lockout after five wrong PINs.
 *
 * Channels are keyed by (playlistId, channelId) in [LiveDb]; no PIN is set means everything is
 * allowed. The PIN input dialog is built elsewhere — this module only checks and records.
 */
class ParentalGate(context: Context) {

    enum class Rule { BLOCKED_CHANNEL, BLOCKED_GROUP, SETTINGS }
    enum class Access { ALLOWED, REQUIRES_PIN }
    enum class Verify { OK, WRONG, LOCKED }

    private val app = context.applicationContext
    private val secrets: SharedPreferences = app.getSharedPreferences(KeyStore.FILE, Context.MODE_PRIVATE)
    private val settings: SharedPreferences = Settings.of(app)
    private val live = LiveDb.get(app)

    init {
        val db = live.writableDatabase
        if (!hasColumn(db, "channel_group", "blocked")) {
            db.execSQL("ALTER TABLE channel_group ADD COLUMN blocked INTEGER NOT NULL DEFAULT 0")
        }
    }

    /** True once a PIN has been set. */
    fun isPinSet(): Boolean = !storedHash.isNullOrBlank()

    /** Creates or changes the PIN: replaces the stored hash. False when the PIN is too short. */
    fun setPin(pin: String): Boolean {
        val clean = pin.trim()
        if (clean.length < PIN_MIN_LENGTH) return false
        val saved = secrets.edit().putString("pin_hash", hash(clean)).commit()
        if (saved) clearLockout()
        return saved
    }

    /** Confirms [pin] against the stored one, counting failures and starting the lockout. */
    fun verify(pin: String): Verify {
        val now = System.currentTimeMillis()
        if (cooldownUntil > now) return Verify.LOCKED
        val current = storedHash ?: return Verify.WRONG
        if (hash(pin.trim()) == current) {
            clearLockout()
            return Verify.OK
        }
        val count = failures + 1
        if (count >= LOCKOUT_AFTER) {
            failures = 0
            cooldownUntil = now + COOLDOWN_MS
            return Verify.LOCKED
        }
        failures = count
        return Verify.WRONG
    }

    /** How long the lockout still runs, 0 when not locked. */
    fun cooldownRemainingMs(): Long = (cooldownUntil - System.currentTimeMillis()).coerceAtLeast(0L)

    /** The rules that require a PIN; kept in the Prefs file. */
    var requireFor: Set<Rule>
        get() = settings.getStringSet("parental_require", emptySet()).orEmpty()
            .mapNotNull { runCatching { Rule.valueOf(it) }.getOrNull() }.toSet()
        set(v) = settings.edit().putStringSet("parental_require", v.map { it.name }.toSet()).apply()

    fun blockChannel(playlistId: String, channelId: String) = setChannelBlocked(playlistId, channelId, true)
    fun unblockChannel(playlistId: String, channelId: String) = setChannelBlocked(playlistId, channelId, false)
    fun blockGroup(playlistId: String, groupId: String) = setGroupBlocked(playlistId, groupId, true)
    fun unblockGroup(playlistId: String, groupId: String) = setGroupBlocked(playlistId, groupId, false)

    /** Whether opening this channel needs a PIN: its own blocked flag, or its group's, per the rules. */
    fun checkAccess(playlistId: String, channelId: String): Access {
        if (!isPinSet()) return Access.ALLOWED
        val rules = requireFor
        val db = live.readableDatabase
        var channelBlocked = false
        var groupId: String? = null
        db.rawQuery("SELECT blocked, group_id FROM channel WHERE playlist_id = ? AND id = ?", arrayOf(playlistId, channelId)).use { c ->
            if (c.moveToFirst()) {
                channelBlocked = c.getInt(0) == 1
                groupId = if (c.isNull(1)) null else c.getString(1)
            }
        }
        if (channelBlocked && Rule.BLOCKED_CHANNEL in rules) return Access.REQUIRES_PIN
        if (groupId != null && Rule.BLOCKED_GROUP in rules && isGroupBlocked(db, playlistId, groupId)) return Access.REQUIRES_PIN
        return Access.ALLOWED
    }

    private fun setChannelBlocked(playlistId: String, channelId: String, blocked: Boolean) {
        val db = live.writableDatabase
        db.update("channel", ContentValues().apply { put("blocked", if (blocked) 1 else 0) }, "playlist_id = ? AND id = ?", arrayOf(playlistId, channelId))
        // Every channel list leaves blocked channels out: the lists Live TV keeps are read again (LiveDb.listChanges).
        live.listChanges.incrementAndGet()
    }

    private fun setGroupBlocked(playlistId: String, groupId: String, blocked: Boolean) {
        val db = live.writableDatabase
        db.update("channel_group", ContentValues().apply { put("blocked", if (blocked) 1 else 0) }, "playlist_id = ? AND id = ?", arrayOf(playlistId, groupId))
        live.listChanges.incrementAndGet()
    }

    private fun isGroupBlocked(db: SQLiteDatabase, playlistId: String, groupId: String): Boolean =
        db.rawQuery("SELECT blocked FROM channel_group WHERE playlist_id = ? AND id = ?", arrayOf(playlistId, groupId)).use { c ->
            c.moveToFirst() && c.getInt(0) == 1
        }

    private val storedHash: String?
        get() = secrets.getString("pin_hash", null)?.takeIf { it.isNotBlank() }

    private var failures: Int
        get() = settings.getInt("pin_failures", 0)
        set(v) = settings.edit().putInt("pin_failures", v).apply()

    private var cooldownUntil: Long
        get() = settings.getLong("pin_cooldown_until", 0L)
        set(v) = settings.edit().putLong("pin_cooldown_until", v).apply()

    private fun clearLockout() {
        settings.edit().remove("pin_failures").remove("pin_cooldown_until").apply()
    }

    private fun hash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((PIN_SALT + pin).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            var found = false
            while (c.moveToNext()) if (c.getString(1) == column) found = true
            found
        }

    private companion object {
        const val PIN_MIN_LENGTH = 4
        const val LOCKOUT_AFTER = 5
        const val COOLDOWN_MS = 60_000L
        const val PIN_SALT = "app.fireplayer.parental"
    }
}
