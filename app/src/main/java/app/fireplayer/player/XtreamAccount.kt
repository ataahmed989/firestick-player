package app.fireplayer.player

import android.net.Uri
import android.util.Log
import app.fireplayer.titles.TitleProxy
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The provider account behind an Xtream link (".../movie/user/pass/123.mkv"): how many of its screens are in use.
 * Blocks; run it off the main thread.
 */
class XtreamAccount(private val headers: Map<String, String>) {

    /** Screens playing on the account right now, and how many it may play on at once. */
    class Screens(val inUse: Int, val allowed: Int) {
        val full: Boolean get() = allowed in 1..inUse
    }

    /** Null when the provider can't be asked or doesn't say. */
    fun screens(uri: Uri): Screens? {
        val info = api(uri, null)?.optJSONObject("user_info") ?: return null
        val inUse = info.optString("active_cons").trim().toIntOrNull() ?: return null
        val allowed = info.optString("max_connections").trim().toIntOrNull() ?: return null
        return Screens(inUse, allowed)
    }

    /**
     * The provider's answer to [action] for the account in [uri] ("get_vod_info" with "vod_id"…), or null when it
     * can't be asked or doesn't answer with an object. Blocks.
     */
    fun api(uri: Uri, action: String?, vararg params: Pair<String, String>, maxChars: Int = MAX_CHARS): JSONObject? {
        val (user, password) = login(uri) ?: return null
        val builder = Uri.Builder().scheme(uri.scheme).encodedAuthority(uri.encodedAuthority)
        // Through FirePlayer's title proxy, the provider is asked the same way.
        TitleProxy.serverIn(uri)?.let { builder.appendPath(it) }
        builder.appendPath("player_api.php")
            .appendQueryParameter("username", user)
            .appendQueryParameter("password", password)
        if (action != null) builder.appendQueryParameter("action", action)
        params.forEach { builder.appendQueryParameter(it.first, it.second) }
        val url = builder.build().toString()
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            // The provider may turn away an app it doesn't know, so it hears from the same app as the video.
            val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
            conn.setRequestProperty("User-Agent", userAgent ?: "FirePlayer/1.0 (Linux; Android)")
            headers.filterKeys { !it.equals("User-Agent", true) }.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
                val text = conn.inputStream.bufferedReader().use { reader ->
                    val out = StringBuilder()
                    val buffer = CharArray(8 * 1024)
                    while (out.length < maxChars) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        out.append(buffer, 0, n)
                    }
                    out.toString()
                }
                JSONObject(text)
            } finally {
                conn.disconnect()
            }
        }.onFailure { Log.i(TAG, "Couldn't ask the provider (${action ?: "account"}): $it") }.getOrNull()
    }

    companion object {
        private const val TAG = "FirePlayerAccount"
        private const val MAX_CHARS = 256 * 1024
        /** A show's whole list of episodes can be long. */
        const val SERIES_CHARS = 4 * 1024 * 1024
        private val KINDS = setOf("movie", "series", "live", "timeshift")

        /** The username and password in an Xtream link, or null when it isn't one. */
        fun login(uri: Uri): Pair<String, String>? {
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            val parts = uri.pathSegments.let { if (TitleProxy.serverIn(uri) != null) it.drop(1) else it }
            return when {
                parts.size >= 4 && parts[0].lowercase() in KINDS -> parts[1] to parts[2]
                // A live channel can also be ".../user/pass/123".
                parts.size == 3 && parts[2].substringBeforeLast('.').let { id -> id.isNotEmpty() && id.all { it.isDigit() } } ->
                    parts[0] to parts[1]
                else -> null
            }
        }
    }
}
