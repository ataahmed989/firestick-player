package app.fireplayer.vod.sources

import android.content.SharedPreferences
import android.os.Process
import app.fireplayer.data.CredentialChecks
import app.fireplayer.data.CredentialChecks.State
import app.fireplayer.vod.Addon
import app.fireplayer.vod.Kind
import app.fireplayer.vod.SecretUrl
import app.fireplayer.vod.SourceTarget
import app.fireplayer.vod.Title
import app.fireplayer.vod.encodeLinkChars
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.EnumMap
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Whether each saved add-on and its debrid service work (Settings › Add-ons), in the API keys' states and words
 * ([CredentialChecks]). A check asks the add-on for its manifest and for one well-known film's streams ([AddonCheck]);
 * real use notes the same for free ([noted]: a look-up with cached sources, a play's link). A result is kept in the
 * records ([records]) as its state, a short problem, the debrid service's name, the time and a fingerprint (one-way
 * hash) of the link checked, so a changed link reads "not checked" again; the link itself is never stored, logged or
 * shown. Checks run only when asked: a link added or changed, Check now, or Settings opening a day after the last
 * ([checkStale]). Main thread, except the requests.
 */
class AddonChecks(
    private val records: SharedPreferences,
    private val links: (Addon) -> SecretUrl?,
    private val manifest: ManifestHttp,
    private val streams: StreamsHttp,
    /** Runs a check's requests off the main thread. */
    private val background: (Runnable) -> Unit,
    private val main: MainLoop,
    private val wall: () -> Long,
    private val online: () -> Boolean,
) {

    /** Asks an add-on for its manifest (`{base}/manifest.json`). Blocks; throws as [StreamsHttp] does. */
    fun interface ManifestHttp {
        @Throws(IOException::class)
        fun get(base: SecretUrl, timeoutMs: Int): String
    }

    class Status(val state: State, val problem: String?, val service: String?, val checkedAt: Long) {
        /** The note beside it ([AddonCheck.detail]): "Real-Debrid · checked 5 min ago", "debrid key refused". */
        fun detail(now: Long): String? = AddonCheck.detail(state, problem, service, checkedAt, now)
    }

    private class Record(val fingerprint: String, val state: State, val at: Long, val service: String?, val problem: String?)

    /** Checks under way, by the fingerprint of the link each is checking; and the window waiting for each. */
    private val running = EnumMap<Addon, String>(Addon::class.java)
    private val waiting = EnumMap<Addon, () -> Unit>(Addon::class.java)

    fun status(addon: Addon): Status {
        val link = links(addon) ?: return Status(State.NOT_SET, null, null, 0)
        val fp = fingerprint(link)
        if (running[addon] == fp) return Status(State.CHECKING, null, null, 0)
        val saved = read(addon)?.takeIf { it.fingerprint == fp } ?: return Status(State.NOT_CHECKED, null, null, 0)
        return Status(saved.state, saved.problem, saved.service, saved.at)
    }

    /** Whether any add-on is being checked now. */
    val checking: Boolean get() = running.isNotEmpty()

    /**
     * Checks [addon] off the main thread (nothing when it isn't set; joined when it's being checked); [onDone] runs on
     * the main thread once the result is kept. It's for the window showing the status, so it takes the place of one
     * given before for the same add-on (a window drawn again asks again, and is told once).
     */
    fun check(addon: Addon, onDone: () -> Unit = {}) {
        val link = links(addon) ?: return
        val fp = fingerprint(link)
        waiting[addon] = onDone
        if (running[addon] == fp) return
        running[addon] = fp
        background(Runnable {
            val outcome = runCatching { ask(addon, link) }.getOrElse { AddonCheck.Outcome(State.UNREACHABLE, AddonCheck.UNREADABLE) }
            main.post {
                // Kept only for the link checked: one changed meanwhile is checked again by its own.
                if (running[addon] == fp) running.remove(addon)
                if (links(addon)?.let { fingerprint(it) } == fp) keep(addon, fp, outcome)
                if (running[addon] == null) waiting.remove(addon)?.invoke()
            }
        })
    }

    /**
     * The saved add-ons not checked yet, or last checked (or used) over a day ago: Settings opening. One being checked
     * already (Settings tab opening, say) is joined, so [onDone] still hears of it.
     */
    fun checkStale(onDone: () -> Unit = {}) {
        val now = wall()
        for (addon in Addon.entries) {
            val st = status(addon)
            if (st.state == State.NOT_SET) continue
            val due = st.state == State.CHECKING || st.state == State.NOT_CHECKED || now - st.checkedAt >= STALE_MS || now < st.checkedAt
            if (due) check(addon, onDone)
        }
    }

    /**
     * What real use found ([AddonCheck.fromLookup], [AddonCheck.fromLinkCheck]), kept as a check's result would be. The
     * same result again within [NOTE_AGAIN_MS] isn't written again, so browsing doesn't keep writing the records.
     */
    fun noted(addon: Addon, outcome: AddonCheck.Outcome) {
        val link = links(addon) ?: return
        val fp = fingerprint(link)
        val saved = read(addon)?.takeIf { it.fingerprint == fp }
        val service = outcome.service ?: saved?.service
        if (saved != null && saved.state == outcome.state && saved.problem == outcome.problem && saved.service == service &&
            wall() - saved.at in 0 until NOTE_AGAIN_MS
        ) return
        keep(addon, fp, outcome.copy(service = service))
    }

    /** [addon]'s link was removed: its result goes with it. */
    fun forget(addon: Addon) {
        records.edit().remove(key(addon)).apply()
    }

    /** The check itself (background): the manifest, then the test film's streams. */
    private fun ask(addon: Addon, link: SecretUrl): AddonCheck.Outcome {
        val body = try {
            manifest.get(link, MANIFEST_TIMEOUT_MS)
        } catch (e: Exception) {
            return AddonCheck.fromError(e, online())
        }
        AddonCheck.fromManifest(body)?.let { return it }
        val json = try {
            streams.get(link, TEST_TARGET, (addon.answerLimitMs + STREAMS_EXTRA_MS).toInt(), CallHandle())
        } catch (e: Exception) {
            return AddonCheck.fromError(e, online())
        }
        return AddonCheck.fromAnswer(json, StreamParser.parse(addon, json, null))
    }

    private fun keep(addon: Addon, fp: String, o: AddonCheck.Outcome) {
        val text = listOf(fp, o.state.name, wall().toString(), clean(o.service), clean(o.problem)).joinToString("|")
        records.edit().putString(key(addon), text).apply()
    }

    private fun read(addon: Addon): Record? {
        val parts = records.getString(key(addon), null)?.split('|', limit = 5) ?: return null
        if (parts.size < 5) return null
        val state = runCatching { State.valueOf(parts[1]) }.getOrNull() ?: return null
        return Record(parts[0], state, parts[2].toLongOrNull() ?: 0, parts[3].ifEmpty { null }, parts[4].ifEmpty { null })
    }

    private fun clean(text: String?) = text?.replace('|', '/').orEmpty()

    companion object {
        /** Settings opening checks an add-on again once its last check (or use) is this old. */
        const val STALE_MS = 24 * 60 * 60_000L
        /** Real use writes the same result again only after this long (the "checked … ago" stays near). */
        const val NOTE_AGAIN_MS = 10 * 60_000L
        const val MANIFEST_TIMEOUT_MS = 10_000
        /** The streams request may take this much past the add-on's own answer limit. */
        const val STREAMS_EXTRA_MS = 10_000L
        /** Manifests are a few KB; more is refused. */
        const val MANIFEST_MAX_BYTES = 256 shl 10

        /** The film the check asks for ([AddonCheck.TEST_ID]). */
        val TEST_TARGET = SourceTarget(Title(AddonCheck.TEST_ID, Kind.MOVIE, "The Shawshank Redemption"))

        /** In the records: "check_addon_torrentio". */
        fun key(addon: Addon) = "check_addon_${addon.name.lowercase(Locale.ROOT)}"

        private fun fingerprint(link: SecretUrl) = CredentialChecks.fingerprint(link.reveal())

        /** The app's: HttpURLConnection, like [StreamsHttp.URL_STREAMS]; the link is never logged. */
        val URL_MANIFEST = ManifestHttp { base, timeoutMs ->
            val conn = URL(encodeLinkChars(base.reveal().trimEnd('/') + "/manifest.json")).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Accept", "application/json")
                val code = conn.responseCode
                if (code == 429) throw StreamsHttp.Busy()
                if (code != HttpURLConnection.HTTP_OK) throw StreamsHttp.Status(code)
                conn.inputStream.use { StreamsHttp.readCapped(it, MANIFEST_MAX_BYTES) }
            } finally {
                conn.disconnect()
            }
        }

        /** A thread of its own for each check, below the film's (a check can run beside a playing film). */
        val THREAD: (Runnable) -> Unit = { r ->
            thread(name = "FirePlayerAddonCheck", isDaemon = true) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }
        }
    }
}
