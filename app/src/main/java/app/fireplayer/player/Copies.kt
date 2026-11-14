package app.fireplayer.player

import android.net.Uri
import android.util.Log
import app.fireplayer.titles.TitleBook
import app.fireplayer.titles.TitleProxy
import org.json.JSONArray
import org.json.JSONObject

/**
 * The other copies a provider lists of the film or episode playing, each as the provider's probe of its file describes
 * it (picture, sound, data rate), so they can be compared without playing them. Copies are found in the title book,
 * by TMDB id; only links that come through FirePlayer's title server can be looked up. Blocks: run it off the main
 * thread. One small request per copy (a show's episode list per copy of the show), only when asked.
 */
class Copies(private val book: TitleBook, headers: Map<String, String>) {

    /** A copy: the provider's [name] for it, the [uri] it plays from, and what's known of it (null: the provider didn't say). */
    class Copy(val name: String, val uri: Uri, val spec: MediaSpec?, val current: Boolean)

    private val account = XtreamAccount(headers)

    /** The film at [uri] and its other copies; empty when they can't be looked up. */
    fun ofFilm(uri: Uri): List<Copy> {
        val server = TitleProxy.serverIn(uri) ?: return emptyList()
        val id = idIn(uri) ?: return emptyList()
        val tmdb = book.about(server, TitleBook.Kind.MOVIE, id)?.tmdb ?: 0
        val listed = book.copies(server, TitleBook.Kind.MOVIE, tmdb).ifEmpty { listOf(TitleBook.Copy(id, "")) }
        val all = if (listed.any { it.id == id }) listed else listOf(TitleBook.Copy(id, "")) + listed
        return all.take(MAX_COPIES).map { copy ->
            val info = account.api(uri, "get_vod_info", "vod_id" to copy.id.toString())
            val about = info?.optJSONObject("info")
            val data = info?.optJSONObject("movie_data")
            val ext = data?.optString("container_extension")?.takeIf { it.isNotBlank() } ?: extensionIn(uri)
            val name = copy.raw.ifEmpty { data?.optString("name").orEmpty() }
            Copy(name, linkFor(uri, copy.id.toString(), ext), about?.let { spec(it) }, copy.id == id)
        }.also { log("film", it) }
    }

    /** The episode at [uri] of show [seriesId], and the same episode in the provider's other copies of the show. */
    fun ofEpisode(uri: Uri, seriesId: String): List<Copy> {
        val server = TitleProxy.serverIn(uri) ?: return emptyList()
        val episodeId = idIn(uri)?.toString() ?: return emptyList()
        val show = seriesId.toIntOrNull() ?: return emptyList()
        val tmdb = book.about(server, TitleBook.Kind.SHOW, show)?.tmdb ?: 0
        val here = episodes(uri, seriesId) ?: return emptyList()
        val mine = here.firstOrNull { it.id == episodeId } ?: return emptyList()
        val others = book.copies(server, TitleBook.Kind.SHOW, tmdb).filter { it.id != show }.take(MAX_COPIES - 1)
        val out = ArrayList<Copy>()
        out += Copy(mine.name, uri, mine.spec, true)
        for (other in others) {
            val list = episodes(uri, other.id.toString()) ?: continue
            val same = list.firstOrNull { it.season == mine.season && it.number == mine.number } ?: continue
            out += Copy(other.raw.ifEmpty { same.name }, linkFor(uri, same.id, same.extension), same.spec, false)
        }
        log("episode", out)
        return out
    }

    private class Ep(val id: String, val season: Int, val number: Int, val extension: String, val name: String, val spec: MediaSpec?)

    private fun episodes(uri: Uri, seriesId: String): List<Ep>? {
        val root = account.api(uri, "get_series_info", "series_id" to seriesId, maxChars = XtreamAccount.SERIES_CHARS) ?: return null
        val out = ArrayList<Ep>()
        fun take(season: Int, e: JSONObject) {
            val id = e.optString("id").takeIf { it.isNotBlank() } ?: return
            out += Ep(
                id = id,
                season = e.optString("season").toIntOrNull() ?: season,
                number = e.optString("episode_num").toIntOrNull() ?: 0,
                extension = e.optString("container_extension").takeIf { it.isNotBlank() } ?: "mkv",
                name = e.optString("title"),
                spec = e.optJSONObject("info")?.let { spec(it) },
            )
        }
        fun season(key: Int, list: Any?) {
            when (list) {
                is JSONArray -> for (i in 0 until list.length()) list.optJSONObject(i)?.let { take(key, it) }
                is JSONObject -> if (list.has("id")) take(key, list) else list.keys().forEach { k -> list.optJSONObject(k)?.let { take(key, it) } }
            }
        }
        when (val all = root.opt("episodes")) {
            is JSONObject -> all.keys().forEach { k -> season(k.toIntOrNull() ?: 0, all.opt(k)) }
            is JSONArray -> for (i in 0 until all.length()) season(i, all.opt(i))
        }
        return out
    }

    private fun log(what: String, copies: List<Copy>) {
        // What the provider said of each, without links or logins: to see how much providers tell.
        Log.i(TAG, "$what copies: " + copies.joinToString(" | ") { c ->
            val s = c.spec
            if (s == null) "${if (c.current) "*" else ""}no info"
            else "${if (c.current) "*" else ""}${s.videoCodec} ${s.width}x${s.height} ${s.bitDepth}bit ${s.hdr} " +
                "v=${s.videoBps}(${s.videoHow}) a=${s.audioCodec}${if (s.atmos) "+Atmos" else ""}/${s.channels} ${s.audioBps} all=${s.overallBps}"
        })
    }

    companion object {
        private const val TAG = "FirePlayerCopies"
        private const val MAX_COPIES = 8

        /** The provider's id in a link: "123" of ".../movie/user/pass/123.mkv". */
        fun idIn(uri: Uri): Int? = uri.lastPathSegment?.substringBeforeLast('.')?.toIntOrNull()

        private fun extensionIn(uri: Uri): String = uri.lastPathSegment?.substringAfterLast('.', "mkv")?.ifEmpty { "mkv" } ?: "mkv"

        /** Another file in the same folder of the provider's links: the same login and path, [id].[extension] at the end. */
        fun linkFor(current: Uri, id: String, extension: String): Uri {
            val folder = current.encodedPath.orEmpty().substringBeforeLast('/')
            return current.buildUpon().encodedPath("$folder/${Uri.encode(id)}.${Uri.encode(extension)}").build()
        }

        /** A copy as its "info" object describes it: its "video" and "audio" probes and "bitrate" (kb/s). */
        fun spec(info: JSONObject): MediaSpec? {
            val video = info.optJSONObject("video")?.let { flatten(it) }.orEmpty()
            val audio = info.optJSONObject("audio")?.let { flatten(it) }.orEmpty()
            val kbps = info.optString("bitrate").trim().toLongOrNull() ?: 0L
            if (video.isEmpty() && audio.isEmpty() && kbps <= 0) return null
            return MediaSpec.fromProvider(video, audio, kbps)
        }

        /** Its fields as text, "tags.BPS" for a tag, and "side_data" the kinds of side data it has. */
        private fun flatten(o: JSONObject): Map<String, String> {
            val out = HashMap<String, String>()
            o.keys().forEach { k ->
                when (val v = o.opt(k)) {
                    is JSONObject -> v.keys().forEach { t -> out["$k.$t"] = v.opt(t)?.toString().orEmpty() }
                    is JSONArray -> if (k == "side_data_list") {
                        out["side_data"] = (0 until v.length()).mapNotNull { v.optJSONObject(it)?.optString("side_data_type") }.joinToString(";")
                    }
                    null -> {}
                    else -> out[k] = v.toString()
                }
            }
            return out
        }
    }
}
