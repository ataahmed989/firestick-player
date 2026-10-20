package app.fireplayer.subs

import app.fireplayer.data.Prefs
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Minimal client for the opensubtitles.com REST API. All calls block; run them off the main thread. */
class OpenSubtitles(private val prefs: Prefs) {

    /** [episode] is "S1 E8" when the site says which episode the subtitles are for. */
    data class Result(val fileId: Long, val release: String, val language: String, val downloads: Int, val episode: String?)

    // VIP accounts are sent to their own server when they sign in.
    private var base = "https://api.opensubtitles.com/api/v1"
    private var token: String? = null
    private var signInFailed = false

    /** How many downloads are left today, as the site said after the last one; null until then. */
    @Volatile var quota: String? = null
        private set

    val isConfigured: Boolean get() = prefs.openSubtitlesApiKey.isNotEmpty()

    /**
     * Subtitles in [language] for [query], or for a show's [season] and [episode]. With [tmdbId] (the film's, or the
     * show's when [show]) the site is asked for exactly that one; when it has nothing under the id, the name is tried.
     */
    fun search(query: String, language: String, season: Int?, episode: Int?, tmdbId: Int?, show: Boolean): List<Result> {
        val params = sortedMapOf<String, String>()
        if (language.isNotEmpty()) params["languages"] = siteLanguages(language)
        params["type"] = if (show) "episode" else "movie"
        if (show) {
            season?.let { params["season_number"] = it.toString() }
            episode?.let { params["episode_number"] = it.toString() }
        }
        if (tmdbId != null) {
            val byId = HashMap(params).apply { put(if (show) "parent_tmdb_id" else "tmdb_id", tmdbId.toString()) }
            val found = searchWith(byId)
            if (found.isNotEmpty()) return found
        }
        params["query"] = query.lowercase(Locale.ROOT)
        return searchWith(params)
    }

    private fun searchWith(params: Map<String, String>): List<Result> {
        // In alphabetical order and lower case, as the site asks, so it answers without sending us elsewhere first.
        val query = params.toSortedMap().entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        val json = JSONObject(request("GET", "$base/subtitles?$query", null))
        val data = json.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Result>()
        for (i in 0 until data.length()) {
            val attrs = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
            val file = attrs.optJSONArray("files")?.optJSONObject(0) ?: continue
            val details = attrs.optJSONObject("feature_details")
            val s = details?.optInt("season_number", -1) ?: -1
            val e = details?.optInt("episode_number", -1) ?: -1
            out += Result(
                fileId = file.optLong("file_id"),
                release = attrs.optString("release").ifEmpty { file.optString("file_name") },
                language = attrs.optString("language"),
                downloads = attrs.optInt("download_count"),
                episode = if (s > 0 && e > 0) "S$s E$e" else null,
            )
        }
        return out.sortedByDescending { it.downloads }
    }

    /** The site splits a few languages by region. */
    private fun siteLanguages(code: String): String = when (code) {
        "pt" -> "pt-br,pt-pt"
        "zh" -> "zh-cn,zh-tw"
        else -> code
    }

    /** Downloads the subtitle file into [dir] and returns it. */
    fun download(result: Result, dir: File): File {
        ensureLogin()
        val body = JSONObject().put("file_id", result.fileId).toString()
        val json = JSONObject(request("POST", "$base/download", body))
        val remaining = json.optInt("remaining", Int.MIN_VALUE)
        val reset = json.optString("reset_time").trim()
        quota = when {
            remaining == Int.MIN_VALUE -> null
            remaining <= 0 -> "No subtitle downloads left today" + if (reset.isNotEmpty()) "; more in $reset." else "."
            remaining == 1 -> "1 subtitle download left today."
            else -> "$remaining subtitle downloads left today."
        }?.let { if (signInFailed) "$it Signing in to OpenSubtitles failed, so the smaller limit applies." else it }
        val link = json.optString("link")
        if (link.isEmpty()) error(json.optString("message", "Download limit reached"))
        val name = json.optString("file_name").ifEmpty { "${result.fileId}.srt" }
        val file = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val conn = URL(link).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun ensureLogin() {
        if (token != null || prefs.openSubtitlesUser.isEmpty() || prefs.openSubtitlesPassword.isEmpty()) return
        val body = JSONObject()
            .put("username", prefs.openSubtitlesUser)
            .put("password", prefs.openSubtitlesPassword)
            .toString()
        val login = runCatching { JSONObject(request("POST", "$base/login", body)) }.getOrNull()
        token = login?.optString("token")?.ifEmpty { null }
        signInFailed = token == null
        login?.optString("base_url")?.takeIf { it.isNotBlank() && token != null }?.let { base = "https://$it/api/v1" }
    }

    private fun request(method: String, url: String, body: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Api-Key", prefs.openSubtitlesApiKey)
        conn.setRequestProperty("User-Agent", "FirePlayer v1.0")
        conn.setRequestProperty("Accept", "application/json")
        token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
            error(message?.ifEmpty { null } ?: "OpenSubtitles error $code")
        }
        return text
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
