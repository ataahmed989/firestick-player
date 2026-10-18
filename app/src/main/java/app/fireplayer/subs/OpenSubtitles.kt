package app.fireplayer.subs

import app.fireplayer.data.Prefs
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Minimal client for the opensubtitles.com REST API. All calls block; run them off the main thread. */
class OpenSubtitles(private val prefs: Prefs) {

    data class Result(val fileId: Long, val release: String, val language: String, val downloads: Int)

    private val base = "https://api.opensubtitles.com/api/v1"
    private var token: String? = null

    val isConfigured: Boolean get() = prefs.openSubtitlesApiKey.isNotEmpty()

    fun search(query: String, language: String, season: Int?, episode: Int?): List<Result> {
        val params = buildString {
            append("query=").append(enc(query))
            if (language.isNotEmpty()) append("&languages=").append(enc(siteLanguages(language)))
            if (season != null) append("&season_number=").append(season)
            if (episode != null) append("&episode_number=").append(episode)
        }
        val json = JSONObject(request("GET", "$base/subtitles?$params", null))
        val data = json.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Result>()
        for (i in 0 until data.length()) {
            val attrs = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
            val file = attrs.optJSONArray("files")?.optJSONObject(0) ?: continue
            out += Result(
                fileId = file.optLong("file_id"),
                release = attrs.optString("release").ifEmpty { file.optString("file_name") },
                language = attrs.optString("language"),
                downloads = attrs.optInt("download_count"),
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
        token = runCatching { JSONObject(request("POST", "$base/login", body)).optString("token") }
            .getOrNull()?.ifEmpty { null }
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
