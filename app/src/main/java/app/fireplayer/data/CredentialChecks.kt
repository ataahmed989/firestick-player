package app.fireplayer.data

import android.os.Handler
import android.os.Looper
import app.fireplayer.subs.OpenSubtitles
import app.fireplayer.subs.Tmdb
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Whether the OpenSubtitles and TMDB credentials work, kept apart from whether they're saved: "configured" is a key
 * in the store, "working" is a check that passed for exactly those credentials. A result is saved with a fingerprint
 * (a one-way hash) of what was checked, so replacing a key makes it "not checked" again. Nothing secret is stored or
 * shown here; the fingerprint can't be turned back into the key.
 */
class CredentialChecks(private val prefs: Prefs) {

    enum class Service(val pref: String, val label: String) {
        OPENSUBTITLES("check_os", "OpenSubtitles"),
        TMDB("check_tmdb", "TMDB API"),
    }

    enum class State(val label: String) {
        NOT_SET("Not set"),
        NOT_CHECKED("Set · not checked"),
        CHECKING("Checking…"),
        WORKING("Working"),
        NOT_ACCEPTED("Not accepted"),
        UNREACHABLE("Couldn't check"),
    }

    class Status(val state: State, val problem: String?, val checkedAt: Long)

    fun status(service: Service): Status {
        val now = credentials(service) ?: return Status(State.NOT_SET, null, 0)
        if (service in running) return Status(State.CHECKING, null, 0)
        val saved = prefs.records.getString(service.pref, null)?.split('|', limit = 4)
        if (saved == null || saved.size < 3 || saved[0] != fingerprint(now)) return Status(State.NOT_CHECKED, null, 0)
        val state = runCatching { State.valueOf(saved[1]) }.getOrDefault(State.NOT_CHECKED)
        return Status(state, saved.getOrNull(3)?.ifEmpty { null }, saved[2].toLongOrNull() ?: 0)
    }

    /**
     * Checks [service] off the main thread (nothing happens when it isn't set or is being checked); [onDone] runs on
     * the main thread after the result is saved.
     */
    fun check(service: Service, onDone: () -> Unit = {}) {
        val creds = credentials(service) ?: return
        if (!running.add(service)) return
        thread(name = "FirePlayerKeyCheck", isDaemon = true) {
            val result = runCatching {
                when (service) {
                    Service.TMDB -> Tmdb(prefs).verify()
                    Service.OPENSUBTITLES -> OpenSubtitles(prefs).verify()
                }
            }.getOrElse { false to "Couldn't check" }
            val state = when {
                result == null -> State.WORKING
                result.first -> State.NOT_ACCEPTED
                else -> State.UNREACHABLE
            }
            val problem = result?.second?.replace('|', '/') ?: ""
            prefs.records.edit().putString(service.pref, "${fingerprint(creds)}|${state.name}|${System.currentTimeMillis()}|$problem").apply()
            main.post {
                running.remove(service)
                onDone()
            }
        }
    }

    /** Checks the services that are set but not checked (or couldn't be reached last time). */
    fun checkUnchecked(onDone: () -> Unit) {
        for (s in Service.entries) {
            val st = status(s).state
            if (st == State.NOT_CHECKED || st == State.UNREACHABLE) check(s, onDone)
        }
    }

    /** What's checked: the saved credentials, joined; null when the service isn't set up. */
    private fun credentials(service: Service): String? = when (service) {
        Service.TMDB -> prefs.keys[KeyStore.Key.TMDB].takeIf { it.isNotEmpty() }
        Service.OPENSUBTITLES -> prefs.keys[KeyStore.Key.OPENSUBTITLES].takeIf { it.isNotEmpty() }?.let {
            it + "\n" + prefs.keys[KeyStore.Key.OPENSUBTITLES_USER] + "\n" + prefs.keys[KeyStore.Key.OPENSUBTITLES_PASSWORD]
        }
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())
        /** Checks under way, for every window (the player's and the home screen's show the same state). */
        private val running: MutableSet<Service> = java.util.Collections.synchronizedSet(HashSet())

        internal fun fingerprint(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
    }
}
