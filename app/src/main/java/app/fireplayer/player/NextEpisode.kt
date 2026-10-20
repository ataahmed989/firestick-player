package app.fireplayer.player

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale

/**
 * Finds the episode after the one playing. TiviMate hands over one episode at a time, but its link
 * (http://host:port/series/USER/PASSWORD/EPISODE.mkv) carries the provider's address and login, so the provider's own
 * catalogue can be asked: which show holds this episode, and what comes after it.
 *
 * The provider can't be asked which show an episode belongs to, so the show is looked up by name in its list of
 * shows and then confirmed by finding this very episode in it. Once confirmed, every episode of the show is
 * remembered, so later episodes take one small request. Runs on a background thread, after the film has started,
 * and only reads the provider's lists, never video.
 */
class NextEpisode(context: Context, private val headers: Map<String, String>) {

    class Found(val uri: Uri, val title: String)

    private class Link(
        val uri: Uri,
        val scheme: String,
        val authority: String,
        val user: String,
        val password: String,
        val episodeId: String,
    ) {
        val base get() = "$scheme://$authority"
    }

    private class Episode(val id: String, val extension: String, val season: Int, val number: Int, val title: String)

    private class Show(val id: String, val name: String, val title: String)

    /** A name ready to compare: squeezed down to its letters and digits, and as its words. */
    private class Name(val squeezed: String, val words: Set<String>)

    private val prefs = context.getSharedPreferences("next_episode", Context.MODE_PRIVATE)
    private val cacheDir = context.cacheDir

    /**
     * Null when the link isn't a provider's episode link, or the next one can't be found (or there is none). [rawTitle]
     * is the name the episode came with, [show] the show's name as read from it.
     */
    fun find(uri: Uri, rawTitle: String?, show: String): Found? {
        val link = parse(uri) ?: return null
        status = "Looking up the episode after this one…"
        val known = prefs.getString(seriesKey(link, link.episodeId), null)
        if (known != null) {
            val episodes = episodes(link, known)
            if (episodes != null && episodes.any { it.id == link.episodeId }) {
                remember(link, known, episodes)
                return after(link, show, episodes)
            }
        }
        val wanted = wantedNames(rawTitle, show)
        if (wanted.isEmpty()) {
            status = "This episode came without the show's name, so its next episode can't be looked up."
            return null
        }
        var answered = false
        for (fresh in listOf(false, true)) {
            val shows = shows(link, fresh) ?: break
            answered = true
            for (seriesId in candidates(shows, wanted)) {
                val episodes = episodes(link, seriesId) ?: continue
                if (episodes.none { it.id == link.episodeId }) continue
                remember(link, seriesId, episodes)
                return after(link, show, episodes)
            }
            // An old copy of the list may not have this show yet; a fresh one is fetched once.
        }
        status = if (answered) "Couldn't find \"$show\" in your provider's list of shows."
        else "Your provider didn't answer when asked for its list of shows."
        Log.i(TAG, "No next episode for \"$rawTitle\" (${link.episodeId}): $status")
        return null
    }

    private fun after(link: Link, show: String, episodes: List<Episode>): Found? {
        val next = episodes.getOrNull(episodes.indexOfFirst { it.id == link.episodeId } + 1)
        if (next == null) {
            status = "This is the last episode your provider has."
            return null
        }
        // The same link with the next episode's file at the end, the login exactly as it came.
        val folder = link.uri.encodedPath.orEmpty().substringBeforeLast('/')
        val uri = link.uri.buildUpon().encodedPath("$folder/${Uri.encode(next.id)}.${Uri.encode(next.extension)}").build()
        // Names usually read "Show - S01E03 - Title"; otherwise one is made up in that shape.
        val named = TitleInfo.parse(next.title, null).episode != null
        val title = if (named) next.title
        else String.format(Locale.ROOT, "%s S%02dE%02d %s", show, next.season, next.number, next.title).trim()
        status = "Found the next episode: season ${next.season}, episode ${next.number}."
        return Found(uri, title)
    }

    // ---------------------------------------------------------------------
    // The provider's lists

    /** Every episode of a show in watching order, or null when the provider doesn't answer. */
    private fun episodes(link: Link, seriesId: String): List<Episode>? {
        val text = get(api(link, "get_series_info", "series_id" to seriesId), MAX_INFO_BYTES)?.toString(Charsets.UTF_8)
            ?: return null
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val seasons = mutableListOf<Pair<Int, Any?>>()
        // Seasons come keyed by number, or (when numbered from 0) as a plain list.
        when (val all = root.opt("episodes")) {
            is JSONObject -> all.keys().forEach { key -> seasons += (key.toIntOrNull() ?: 0) to all.opt(key) }
            is JSONArray -> for (i in 0 until all.length()) seasons += i to all.opt(i)
        }
        return seasons.sortedBy { it.first }.flatMap { (season, list) ->
            seasonItems(list).mapIndexedNotNull { i, e ->
                val id = e.str("id") ?: return@mapIndexedNotNull null
                Episode(
                    id = id,
                    extension = e.str("container_extension") ?: "mkv",
                    season = e.str("season")?.toIntOrNull() ?: season,
                    number = e.str("episode_num")?.toIntOrNull() ?: (i + 1),
                    title = e.str("title").orEmpty(),
                )
            }.sortedBy { it.number }
        }
    }

    /** A season's episodes, which come as a list, as episodes keyed "0", "1"…, or (just one) as that episode. */
    private fun seasonItems(list: Any?): List<JSONObject> = when (list) {
        is JSONArray -> (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        is JSONObject -> if (list.has("id")) listOf(list)
        else list.keys().asSequence().toList().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }.mapNotNull { list.optJSONObject(it) }
        else -> emptyList()
    }

    /** The text at [key], or null when it's missing, empty or JSON's null (which optString would turn into "null"). */
    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    /** The provider's shows, from a copy kept for a day unless [fresh]. */
    private fun shows(link: Link, fresh: Boolean): List<Show>? {
        val file = File(cacheDir, "shows-${(link.base + "|" + link.user).hashCode().toUInt()}.tsv")
        val young = file.exists() && System.currentTimeMillis() - file.lastModified() < SHOWS_FOR_MS
        if (!fresh && young) {
            readShows(file)?.let { return it }
        }
        // A copy just fetched won't be any different.
        if (fresh && young && file.lastModified() > startedAt) return null
        val shows = fetchShows(link) ?: return null
        runCatching { file.writeText(shows.joinToString("\n") { "${it.id}\t${it.name}\t${it.title}" }) }
        return shows
    }

    private fun readShows(file: File): List<Show>? = runCatching {
        file.readLines().mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size < 2) null else Show(parts[0], parts[1], parts.getOrElse(2) { "" })
        }
    }.getOrNull()

    /** The list can run to tens of megabytes, so only the id and names of each show are kept as it's read. */
    private fun fetchShows(link: Link): List<Show>? = open(api(link, "get_series"))?.use { stream ->
        runCatching {
            val reader = JsonReader(stream.reader(Charsets.UTF_8))
            // A login the provider turns down comes back as an object instead of a list.
            if (reader.peek() != JsonToken.BEGIN_ARRAY) return@runCatching null
            val shows = mutableListOf<Show>()
            reader.beginArray()
            while (reader.hasNext()) {
                var id: String? = null
                var name: String? = null
                var title: String? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    val token = reader.peek()
                    when {
                        token != JsonToken.STRING && token != JsonToken.NUMBER -> reader.skipValue()
                        key == "series_id" -> id = reader.nextString()
                        key == "name" -> name = reader.nextString()
                        key == "title" -> title = reader.nextString()
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                // Some providers fill "title" with a cleaner name than "name"; both are kept to compare with.
                val clean = { s: String? -> s.orEmpty().replace('\t', ' ').replace('\n', ' ').trim() }
                if (id != null && (!name.isNullOrBlank() || !title.isNullOrBlank())) shows += Show(id, clean(name), clean(title))
            }
            shows.distinctBy { it.id }
        }.getOrNull()
    }

    /**
     * The shows whose names match, best first: the same name, then one name's words all in the other's ("The Office"
     * and "The Office (US)"), then one name inside the other, then names sharing most of their words. Any of them is
     * only taken once this very episode is found in it, so a few close ones are worth asking about.
     */
    private fun candidates(shows: List<Show>, wanted: List<Name>): List<String> {
        val scored = shows.mapNotNull { show ->
            val names = listOfNotNull(nameOf(show.name), show.title.takeIf { it != show.name }?.let { nameOf(it) })
            val best = names.maxOfOrNull { have -> wanted.maxOf { score(it, have) } } ?: 0f
            if (best > 0f) show to best else null
        }
        val picked = scored.sortedByDescending { it.second }.map { it.first }.distinctBy { it.id }.take(MAX_CANDIDATES)
        Log.i(TAG, "Shows that may hold this episode: " + picked.joinToString { "\"${it.title.ifEmpty { it.name }}\"" })
        return picked.map { it.id }
    }

    private fun score(wanted: Name, have: Name): Float {
        if (have.squeezed == wanted.squeezed) return 4f
        val (fewer, more) = if (have.words.size <= wanted.words.size) have.words to wanted.words else wanted.words to have.words
        if (fewer.isNotEmpty() && more.containsAll(fewer)) return 3f + fewer.size.toFloat() / more.size
        val (shorter, longer) = if (have.squeezed.length <= wanted.squeezed.length) have.squeezed to wanted.squeezed
        else wanted.squeezed to have.squeezed
        if (shorter.length >= 4 && shorter in longer) return 2f + shorter.length.toFloat() / longer.length
        val common = wanted.words.count { it in have.words }
        val shared = 2f * common / (wanted.words.size + have.words.size).coerceAtLeast(1)
        return if (shared >= 0.5f) shared else 0f
    }

    /** The ways [rawTitle] may give its show's name: as read for the screen, cut before the episode, or its first part. */
    private fun wantedNames(rawTitle: String?, show: String): List<Name> {
        val extra = mutableListOf<String>()
        if (!rawTitle.isNullOrBlank()) {
            EPISODE_MARK.find(rawTitle)?.let { extra += rawTitle.substring(0, it.range.first) }
            extra += rawTitle.split(" - ", " – ", " | ").first()
        }
        val names = listOfNotNull(nameOf(show)) + extra.mapNotNull { nameOf(it) }.filter { it.squeezed.length >= 4 }
        return names.distinctBy { it.squeezed }
    }

    /** Only this episode and the next few, so the next ones need just the one small request. */
    private fun remember(link: Link, seriesId: String, episodes: List<Episode>) {
        // Clearing them all now and then keeps it small; a show is found again by name when it's needed.
        if (prefs.all.size > MAX_REMEMBERED) prefs.edit().clear().apply()
        val at = episodes.indexOfFirst { it.id == link.episodeId }.coerceAtLeast(0)
        val edit = prefs.edit()
        episodes.subList(at, minOf(episodes.size, at + 1 + REMEMBER_AHEAD)).forEach { edit.putString(seriesKey(link, it.id), seriesId) }
        edit.apply()
    }

    // ---------------------------------------------------------------------
    // Requests

    private fun api(link: Link, action: String, vararg params: Pair<String, String>): String {
        val builder = Uri.parse(link.base).buildUpon().appendPath("player_api.php")
            .appendQueryParameter("username", link.user)
            .appendQueryParameter("password", link.password)
            .appendQueryParameter("action", action)
        params.forEach { builder.appendQueryParameter(it.first, it.second) }
        return builder.build().toString()
    }

    private fun get(url: String, maxBytes: Int): ByteArray? = open(url)?.use { stream ->
        runCatching {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > maxBytes) return@runCatching null
            }
            out.toByteArray()
        }.getOrNull()
    }

    private fun open(url: String): InputStream? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        // The provider may turn away an app it doesn't know, so it hears from the same app as the video.
        val userAgent = headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value
        conn.setRequestProperty("User-Agent", userAgent ?: "FirePlayer/1.0 (Linux; Android)")
        headers.filterKeys { !it.equals("User-Agent", true) }.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            conn.disconnect()
            return@runCatching null
        }
        conn.inputStream
    }.onFailure { Log.i(TAG, "Couldn't ask the provider: $it") }.getOrNull()

    private fun seriesKey(link: Link, episodeId: String) = "${link.base}|${link.user}|$episodeId"

    companion object {
        private const val TAG = "FirePlayerNext"
        private const val SHOWS_FOR_MS = 24 * 60 * 60_000L
        private const val MAX_INFO_BYTES = 8 shl 20
        private const val MAX_CANDIDATES = 8
        private const val MAX_REMEMBERED = 500
        private const val REMEMBER_AHEAD = 5
        private val startedAt = System.currentTimeMillis()

        /** How the last look-up went, for Settings; null when there was nothing to look up. */
        @Volatile var status: String? = null

        /** Whether [uri] is a provider's episode link the next episode can be found from. */
        fun canFind(uri: Uri): Boolean = parse(uri) != null

        /** Whether [uri] looks like a provider's episode at all ("…/series/…"). */
        fun isEpisodeLink(uri: Uri): Boolean = uri.pathSegments.any { it.equals("series", true) }

        private fun parse(uri: Uri): Link? {
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            val authority = uri.encodedAuthority ?: return null
            val parts = uri.pathSegments
            if (parts.size != 4 || !parts[0].equals("series", true)) return null
            val episodeId = parts[3].substringBeforeLast('.').takeIf { id -> id.isNotEmpty() && id.all { it.isDigit() } }
                ?: return null
            return Link(uri, scheme, authority, parts[1], parts[2], episodeId)
        }

        // Where an episode's own part of a name starts: "S01E03", "S1 EP3", "1x03", "Season 1", "Episode 3".
        private val EPISODE_MARK =
            Regex("""(?i)\b(S\d{1,2}\s*[ ._-]?\s*EP?\s*\d{1,3}|\d{1,2}x\d{2,3}|Season\s*\d{1,2}|Series\s*\d{1,2}|Episode\s*\d{1,3}|EP\s*\d{1,3}|S\d{1,2})\b""")
        private val ACCENTS = Regex("""\p{Mn}+""")
        private val YEAR = Regex("""(19|20)\d{2}""")
        private val SMALL_WORDS = setOf("the", "a", "an", "and", "of")

        /**
         * Without the provider's tags, the year, accents and punctuation, so "EN - Grey's Anatomy (2005)" and
         * "Greys Anatomy" compare as the same, and "&" reads as "and".
         */
        private fun nameOf(text: String): Name? {
            val plain = Normalizer.normalize(TitleInfo.parse(text, null).searchQuery, Normalizer.Form.NFD)
                .replace(ACCENTS, "")
                .lowercase()
                .replace("&", " and ")
                .replace("'", "").replace("’", "")
                .map { if (it.isLetterOrDigit()) it else ' ' }
                .joinToString("")
            val words = plain.split(' ').filter { it.isNotEmpty() }
            val squeezed = words.joinToString("")
            if (squeezed.isEmpty()) return null
            // Single letters ("House M.D.") say little about which show it is; the squeezed name still has them.
            val telling = words.filter { (it.length > 1 || it[0].isDigit()) && it !in SMALL_WORDS && !YEAR.matches(it) }
            return Name(squeezed, telling.toSet())
        }
    }
}
