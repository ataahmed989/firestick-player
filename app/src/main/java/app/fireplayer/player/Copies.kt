package app.fireplayer.player

import android.net.Uri
import android.util.Log
import app.fireplayer.titles.TitleBook
import app.fireplayer.titles.Quality
import app.fireplayer.titles.TitleProxy
import org.json.JSONArray
import org.json.JSONObject

/**
 * The other copies listed of the film or episode playing, on every playlist TiviMate reaches through FirePlayer, each
 * as the provider's probe of its file describes it (picture, sound, data rate) when the provider says, so they can be
 * compared without playing them. Copies are found in the title book, by TMDB id (or English name and year); only links
 * that come through FirePlayer's title server can be looked up, and another playlist's copies only once TiviMate has
 * used it through FirePlayer (its login is needed for the link). Blocks: run it off the main thread. One small request
 * per copy (a show's episode list per copy of the show), only when asked.
 */
class Copies(
    private val book: TitleBook,
    headers: Map<String, String>,
    /** What FirePlayer measured of a copy when it was played, if it was. */
    private val measured: (Uri) -> MediaSpec? = { null },
) {

    /**
     * A copy: the provider's [name] for it, the [uri] it plays from, what's known of it (null: nothing), the playlist
     * ([playlist], its server) it's on, and the quality its name or category gives away ([group]: "4K", "1080p HDR").
     */
    class Copy(
        val name: String,
        val uri: Uri,
        val spec: MediaSpec?,
        val current: Boolean,
        val playlist: String = "",
        val group: String? = null,
    )

    private val account = XtreamAccount(headers)

    /**
     * The film at [uri] and its other copies; empty when they can't be looked up. [tmdbFallback]: its TMDB id as found by
     * name, for a film the title book doesn't know (one whose playlist doesn't go through the title server).
     */
    fun ofFilm(uri: Uri, tmdbFallback: Int = 0): List<Copy> {
        val server = serverOf(uri) ?: return emptyList()
        val id = idIn(uri) ?: return emptyList()
        val known = book.about(server, TitleBook.Kind.MOVIE, id)
        val tmdb = known?.tmdb?.takeIf { it > 0 } ?: tmdbFallback
        // By TMDB id, and by the English name and year TMDB gave: a copy matched to another id is still found.
        val byTitle = known?.title?.let { book.copiesByTitle(TitleBook.Kind.MOVIE, it, known.year) }.orEmpty()
        val listed = (book.copies(TitleBook.Kind.MOVIE, tmdb) + byTitle).distinctBy { it.server to it.id }
        // The copy playing first, always (with its listed name), then the others, those of a better quality group first.
        val self = listed.firstOrNull { it.server == server && it.id == id } ?: TitleBook.Copy(server, id, "")
        val others = listed.filter { !(it.server == server && it.id == id) }
            .sortedByDescending { groupRank(Quality.of(it.raw, it.category)) }.take(MAX_COPIES - 1)
        Log.i(TAG, "film: tmdb ${tmdb > 0}, ${listed.size} listed on ${listed.map { it.server }.distinct().size} playlists (${byTitle.size} by name)")
        return (listOf(self) + others).mapNotNull { copy ->
            val base = onPlaylist(uri, copy.server, "movie") ?: return@mapNotNull null
            val info = account.api(base, "get_vod_info", "vod_id" to copy.id.toString())
            val about = info?.optJSONObject("info")
            val data = info?.optJSONObject("movie_data")
            val ext = data?.optString("container_extension")?.takeIf { it.isNotBlank() } ?: extensionIn(uri)
            val name = copy.raw.ifEmpty { data?.optString("name").orEmpty() }
            val link = linkFor(base, copy.id.toString(), ext)
            Copy(name, link, best(about?.let { spec(it) }, measured(link)), copy === self, copy.server, Quality.of(copy.raw, copy.category))
        }.also { log("film", it) }
    }

    /** The episode at [uri] of show [seriesId], and the same episode in the other copies of the show, on every playlist. */
    fun ofEpisode(uri: Uri, seriesId: String, tmdbFallback: Int = 0): List<Copy> {
        val server = serverOf(uri) ?: return emptyList()
        val episodeId = idIn(uri)?.toString() ?: return emptyList()
        val show = seriesId.toIntOrNull() ?: return emptyList()
        val tmdb = book.about(server, TitleBook.Kind.SHOW, show)?.tmdb?.takeIf { it > 0 } ?: tmdbFallback
        val here = episodes(uri, seriesId) ?: return emptyList()
        val mine = here.firstOrNull { it.id == episodeId } ?: return emptyList()
        val listed = book.copies(TitleBook.Kind.SHOW, tmdb)
        val selfListing = listed.firstOrNull { it.server == server && it.id == show }
        val others = listed.filter { !(it.server == server && it.id == show) }
            .sortedByDescending { groupRank(Quality.of(it.raw, it.category)) }.take(MAX_COPIES - 1)
        val out = ArrayList<Copy>()
        out += Copy(mine.name, uri, best(mine.spec, measured(uri)), true, server, selfListing?.let { Quality.of(it.raw, it.category) })
        for (other in others) {
            val base = onPlaylist(uri, other.server, "series") ?: continue
            val list = episodes(base, other.id.toString()) ?: continue
            val same = list.firstOrNull { it.season == mine.season && it.number == mine.number } ?: continue
            val link = linkFor(base, same.id, same.extension)
            out += Copy(other.raw.ifEmpty { same.name }, link, best(same.spec, measured(link)), false, other.server,
                Quality.of(other.raw, other.category))
        }
        log("episode", out)
        return out
    }

    /**
     * A link like [current] on the playlist at [server]: the same one for its own playlist, else through FirePlayer's
     * title server with that playlist's login, or null when it isn't known yet.
     */
    private fun onPlaylist(current: Uri, server: String, kind: String): Uri? {
        if (server == serverOf(current)) return current
        val (user, pass) = book.login(server) ?: return null
        return Uri.parse("http://127.0.0.1:${TitleProxy.PORT}").buildUpon()
            .encodedPath("/$server/$kind/${Uri.encode(user)}/${Uri.encode(pass)}/0.${extensionIn(current)}")
            .build()
    }

    /**
     * The playlist a link plays from, as the title book names it: through the title server, the server it stands for;
     * a provider's own link ("http://example.tv:80/movie/…"), its address.
     */
    private fun serverOf(uri: Uri): String? =
        TitleProxy.serverIn(uri) ?: uri.encodedAuthority?.substringAfterLast('@')?.takeIf { XtreamAccount.login(uri) != null }

    /** The provider's description, with what FirePlayer measured standing in for what it didn't say. */
    private fun best(provider: MediaSpec?, seen: MediaSpec?): MediaSpec? = when {
        provider == null -> seen
        seen == null -> provider
        provider.videoBps <= 0 && seen.videoBps > 0 -> provider.copy(videoBps = seen.videoBps, videoHow = seen.videoHow)
            .let { if (it.videoCodec == null) seen.copy(overallBps = it.overallBps.takeIf { o -> o > 0 } ?: seen.overallBps) else it }
        else -> provider
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
        private const val MAX_COPIES = 16

        /** "4K HDR" over "4K" over "1080p" over "720p" over "HD" over "SD" over nothing said. */
        fun groupRank(group: String?): Int {
            val g = group ?: return 0
            val level = when {
                g.startsWith("4K") -> 6
                g.startsWith("1080p") -> 5
                g.startsWith("720p") -> 4
                g.startsWith("HD") -> 3
                g.startsWith("SD") -> 1
                else -> 0
            }
            return level * 2 + if (g.endsWith("HDR")) 1 else 0
        }

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
