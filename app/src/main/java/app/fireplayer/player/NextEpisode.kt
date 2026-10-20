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
 * Finds which show an episode belongs to, and the episode after it. TiviMate hands over one episode at a time, but its
 * link (http://host:port/series/USER/PASSWORD/EPISODE.mkv) carries the provider's address and login, so the
 * provider's own catalogue can be asked: which show holds this episode, and what comes after it.
 *
 * The provider can't be asked which show an episode belongs to, so the show is looked up by name in its list of
 * shows and then confirmed by finding this very episode in it. When the episode came without the show's name (only
 * "S01E03"), the list is searched by when the episode was added instead (see [findById]). Once confirmed, the show's
 * episodes are remembered, so later episodes take one small request. Runs on a background thread, after the film has
 * started, and only reads the provider's lists, never video.
 */
class NextEpisode(context: Context, private val headers: Map<String, String>) {

    /** The show this episode belongs to, by the provider's name for it, and the episode after it (null if none). */
    class Found(val showName: String, val next: Next?)

    class Next(val uri: Uri, val title: String)

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

    private class Episode(
        val id: String,
        val extension: String,
        val season: Int,
        val number: Int,
        val title: String,
        /** When the provider added it (seconds), 0 when it doesn't say. */
        val added: Long,
    )

    /** One show: its episodes in watching order, and its name from the show's own details when the list has none. */
    private class Series(val name: String?, val episodes: List<Episode>)

    /** [modified] is when the provider last changed the show (seconds), usually when its latest episode came in. */
    private class Show(val id: String, val name: String, val title: String, val modified: Long) {
        val label get() = title.ifEmpty { name }
    }

    /** A name ready to compare: squeezed down to its letters and digits, and as its words. */
    private class Name(val squeezed: String, val words: Set<String>)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cacheDir = context.cacheDir

    /**
     * Null when the link isn't a provider's episode link or its show can't be found. [rawTitle] is the name the
     * episode came with, [show] the show's name as read from it.
     */
    fun find(uri: Uri, rawTitle: String?, show: String): Found? {
        val link = parse(uri) ?: return null
        status = "Looking up the episode after this one…"
        val wanted = wantedNames(rawTitle, show)
        remembered(prefs, link)?.let { (seriesId, name) ->
            val series = series(link, seriesId)
            if (series != null && series.episodes.any { it.id == link.episodeId }) {
                return found(link, seriesId, name, wanted.isNotEmpty(), show, series)
            }
        }
        var answered = false
        if (wanted.isNotEmpty()) {
            for (fresh in listOf(false, true)) {
                val shows = shows(link, fresh) ?: break
                answered = true
                for (candidate in candidates(shows, wanted)) {
                    val series = series(link, candidate.id) ?: continue
                    if (series.episodes.none { it.id == link.episodeId }) continue
                    return found(link, candidate.id, candidate.label, true, show, series)
                }
                // An old copy of the list may not have this show yet; a fresh one is fetched once.
            }
        }
        // No name to go by, or none that matched: look for the show the episode is in.
        status = "Looking for this episode's show in your provider's list…"
        val shows = shows(link, false)?.takeIf { list -> list.any { it.modified > 0 } } ?: shows(link, true)
        if (shows != null) {
            answered = true
            findById(link, shows)?.let { (candidate, series) ->
                return found(link, candidate.id, candidate.label, wanted.isNotEmpty(), show, series)
            }
        }
        status = when {
            !answered -> "Your provider didn't answer when asked for its list of shows."
            wanted.isEmpty() -> "This episode came named only by its number, and its show couldn't be found."
            else -> "Couldn't find \"$show\" in your provider's list of shows."
        }
        Log.i(TAG, "No show found for \"$rawTitle\" (${link.episodeId}): $status")
        return null
    }

    /** The show is known: remember it, and work out the episode after this one. */
    private fun found(link: Link, seriesId: String, listed: String, named: Boolean, show: String, series: Series): Found {
        val name = listed.ifBlank { series.name.orEmpty() }.ifBlank { show }
        remember(link, seriesId, name, series.episodes)
        // The name it came with reads best; one that was only its number gets the provider's name for the show.
        val shown = if (named) show else TitleInfo.parse(name, null).title
        return Found(name, after(link, shown, series.episodes))
    }

    private fun after(link: Link, show: String, episodes: List<Episode>): Next? {
        val next = episodes.getOrNull(episodes.indexOfFirst { it.id == link.episodeId } + 1)
        if (next == null) {
            status = "This is the last episode your provider has."
            return null
        }
        // The same link with the next episode's file at the end, the login exactly as it came.
        val folder = link.uri.encodedPath.orEmpty().substringBeforeLast('/')
        val uri = link.uri.buildUpon().encodedPath("$folder/${Uri.encode(next.id)}.${Uri.encode(next.extension)}").build()
        // Names usually read "Show - S01E03 - Title"; otherwise (or when it's only "S01E03") one is made up like that.
        val parsed = TitleInfo.parse(next.title, null)
        val title = if (parsed.episode != null && parsed.named) next.title
        else String.format(Locale.ROOT, "%s S%02dE%02d %s", show, next.season, next.number, episodeName(next.title)).trim()
        status = "Found the next episode: season ${next.season}, episode ${next.number}."
        return Next(uri, title)
    }

    /** "S01E04 - Cancer Man" → "Cancer Man"; "S01E04" → "". */
    private fun episodeName(title: String): String {
        val mark = EPISODE_MARK.find(title) ?: return title
        return title.substring(mark.range.last + 1).trim(' ', '-', '–', '|', ':', '.')
    }

    // ---------------------------------------------------------------------
    // Finding the show by the episode alone

    /**
     * The show holding this episode when its name is no help. Providers number everything they add in one sequence and
     * note when each episode came in, and every show carries a time too: when it last changed, or on some panels when
     * it was made. Either way a show's time is close to when this episode came in if it's among the show's first or
     * latest. So each show asked about tells, by its own episodes' numbers and times, when this one came in (between
     * the nearest numbers below and above it); the list sorted by time is halved towards that moment, then the shows
     * around it are tried, with now and then the most recently changed, for shows still getting new episodes.
     */
    private fun findById(link: Link, shows: List<Show>): Pair<Show, Series>? {
        val target = link.episodeId.toLongOrNull() ?: return null
        val byTime = shows.filter { it.modified > 0 }.sortedBy { it.modified }
        // Every show stamped at the same moment (some providers refresh them all at once) says nothing.
        if (byTime.map { it.modified }.distinct().size < MIN_DISTINCT_TIMES) return null
        val cameIn = java.util.TreeMap<Long, Long>()
        val tried = HashSet<String>()
        var asked = 0
        fun ask(show: Show): Series? {
            if (asked >= MAX_ASKED || !tried.add(show.id)) return null
            asked++
            val series = series(link, show.id) ?: return null
            series.episodes.forEach { e -> e.id.toLongOrNull()?.let { id -> if (e.added > 0) cameIn[id] = e.added } }
            return series
        }
        // Halve towards the moment this episode came in.
        var lo = 0
        var hi = byTime.lastIndex
        var centre = -1
        while (lo <= hi && asked < MAX_HALVINGS) {
            val mid = (lo + hi) ushr 1
            val series = ask(byTime[mid]) ?: break
            if (series.episodes.any { it.id == link.episodeId }) return byTime[mid] to series
            val after = cameIn.lowerEntry(target)?.value ?: 0L
            val before = cameIn.higherEntry(target)?.value ?: Long.MAX_VALUE
            val time = byTime[mid].modified
            when {
                time <= after -> lo = mid + 1
                time >= before -> hi = mid - 1
                // Known closely enough, and this show's time falls right in it: look around here.
                after > 0 && before != Long.MAX_VALUE && before - after <= CLOSE_ENOUGH_S -> {
                    centre = mid
                    break
                }
                // Otherwise towards the side less is known about.
                time - after < before - time -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        if (centre < 0) centre = lo.coerceIn(0, byTime.lastIndex)
        // Outwards from there, and every third try the most recently changed.
        var up = centre
        var down = centre - 1
        var top = byTime.lastIndex
        var turn = 0
        while (asked < MAX_ASKED && (up < byTime.size || down >= 0)) {
            val show = when {
                turn++ % 3 == 2 && top > up -> byTime[top--]
                (turn % 2 == 0 || down < 0) && up < byTime.size -> byTime[up++]
                down >= 0 -> byTime[down--]
                else -> break
            }
            if (show.id in tried) continue
            val series = ask(show) ?: continue
            if (series.episodes.any { it.id == link.episodeId }) return show to series
        }
        Log.i(TAG, "Episode ${link.episodeId} not found after asking about $asked shows")
        return null
    }

    // ---------------------------------------------------------------------
    // The provider's lists

    /** Every episode of a show in watching order, or null when the provider doesn't answer. */
    private fun series(link: Link, seriesId: String): Series? {
        val text = get(api(link, "get_series_info", "series_id" to seriesId), MAX_INFO_BYTES)?.toString(Charsets.UTF_8)
            ?: return null
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val seasons = mutableListOf<Pair<Int, Any?>>()
        // Seasons come keyed by number, or (when numbered from 0) as a plain list.
        when (val all = root.opt("episodes")) {
            is JSONObject -> all.keys().forEach { key -> seasons += (key.toIntOrNull() ?: 0) to all.opt(key) }
            is JSONArray -> for (i in 0 until all.length()) seasons += i to all.opt(i)
        }
        val episodes = seasons.sortedBy { it.first }.flatMap { (season, list) ->
            seasonItems(list).mapIndexedNotNull { i, e ->
                val id = e.str("id") ?: return@mapIndexedNotNull null
                Episode(
                    id = id,
                    extension = e.str("container_extension") ?: "mkv",
                    season = e.str("season")?.toIntOrNull() ?: season,
                    number = e.str("episode_num")?.toIntOrNull() ?: (i + 1),
                    title = e.str("title").orEmpty(),
                    added = e.str("added")?.toLongOrNull() ?: 0L,
                )
            }.sortedBy { it.number }
        }
        return Series(root.optJSONObject("info")?.str("name"), episodes)
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
        runCatching { file.writeText(shows.joinToString("\n") { "${it.id}\t${it.name}\t${it.title}\t${it.modified}" }) }
        return shows
    }

    private fun readShows(file: File): List<Show>? = runCatching {
        file.readLines().mapNotNull { line ->
            val parts = line.split('\t', limit = 4)
            if (parts.size < 2) null
            else Show(parts[0], parts[1], parts.getOrElse(2) { "" }, parts.getOrNull(3)?.toLongOrNull() ?: 0L)
        }
    }.getOrNull()

    /** The list can run to tens of megabytes, so only the id, names and time of each show are kept as it's read. */
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
                var modified = 0L
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    val token = reader.peek()
                    when {
                        token != JsonToken.STRING && token != JsonToken.NUMBER -> reader.skipValue()
                        key == "series_id" -> id = reader.nextString()
                        key == "name" -> name = reader.nextString()
                        key == "title" -> title = reader.nextString()
                        key == "last_modified" -> modified = reader.nextString().toLongOrNull() ?: 0L
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                // Some providers fill "title" with a cleaner name than "name"; both are kept to compare with.
                val clean = { s: String? -> s.orEmpty().replace('\t', ' ').replace('\n', ' ').trim() }
                if (id != null && (!name.isNullOrBlank() || !title.isNullOrBlank())) {
                    shows += Show(id, clean(name), clean(title), modified)
                }
            }
            shows.distinctBy { it.id }
        }.getOrNull()
    }

    /**
     * The shows whose names match, best first: the same name, then one name's words all in the other's ("The Office"
     * and "The Office (US)"), then one name inside the other, then names sharing most of their words. Any of them is
     * only taken once this very episode is found in it, so a few close ones are worth asking about.
     */
    private fun candidates(shows: List<Show>, wanted: List<Name>): List<Show> {
        val scored = shows.mapNotNull { show ->
            val names = listOfNotNull(nameOf(show.name), show.title.takeIf { it != show.name }?.let { nameOf(it) })
            val best = names.maxOfOrNull { have -> wanted.maxOf { score(it, have) } } ?: 0f
            if (best > 0f) show to best else null
        }
        val picked = scored.sortedByDescending { it.second }.map { it.first }.distinctBy { it.id }.take(MAX_CANDIDATES)
        Log.i(TAG, "Shows that may hold this episode: " + picked.joinToString { "\"${it.label}\"" })
        return picked
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

    /**
     * The ways [rawTitle] may give its show's name: as read for the screen, cut before the episode, or its first
     * part. Empty when it gives none, as with "S01E03 - Pilot".
     */
    private fun wantedNames(rawTitle: String?, show: String): List<Name> {
        val texts = mutableListOf(show)
        if (!rawTitle.isNullOrBlank()) {
            texts += rawTitle
            texts += rawTitle.split(" - ", " – ", " | ").first()
        }
        // Only what comes before the episode's number can be the show's name.
        val names = texts.map { text -> EPISODE_MARK.find(text)?.let { text.substring(0, it.range.first) } ?: text }
            .mapNotNull { nameOf(it) }
        val main = names.firstOrNull()
        return (listOfNotNull(main) + names.drop(1).filter { it.squeezed.length >= 4 }).distinctBy { it.squeezed }
    }

    /** All of the show's episodes, so any of them later is known at once, with the provider's name for the show. */
    private fun remember(link: Link, seriesId: String, name: String, episodes: List<Episode>) {
        val all = prefs.all
        val edit = prefs.edit()
        // Older versions kept one entry per episode; one entry per show replaces them.
        all.keys.filter { !it.startsWith(SHOW_PREFIX) }.forEach { edit.remove(it) }
        val shows = all.filterKeys { it.startsWith(SHOW_PREFIX) }
        if (shows.size >= MAX_SHOWS) {
            // The ones not watched for the longest go first.
            shows.entries.sortedBy { (it.value as? String)?.substringBefore('\t')?.toLongOrNull() ?: 0L }
                .take(shows.size - MAX_SHOWS + 1).forEach { edit.remove(it.key) }
        }
        val ids = episodes.joinToString(",") { it.id }
        val clean = name.replace('\t', ' ').replace('\n', ' ')
        edit.putString(showKey(link, seriesId), "${System.currentTimeMillis()}\t$clean\t$ids").apply()
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

    companion object {
        private const val TAG = "FirePlayerNext"
        private const val PREFS = "next_episode"
        private const val SHOW_PREFIX = "show|"
        private const val SHOWS_FOR_MS = 24 * 60 * 60_000L
        private const val MAX_INFO_BYTES = 8 shl 20
        private const val MAX_CANDIDATES = 8
        private const val MAX_SHOWS = 300
        private const val MAX_HALVINGS = 17
        private const val MAX_ASKED = 40
        private const val MIN_DISTINCT_TIMES = 20
        private const val CLOSE_ENOUGH_S = 6 * 60 * 60L
        private val startedAt = System.currentTimeMillis()

        /** How the last look-up went, for Settings; null when there was nothing to look up. */
        @Volatile var status: String? = null

        /** Whether [uri] is a provider's episode link the next episode can be found from. */
        fun canFind(uri: Uri): Boolean = parse(uri) != null

        /** Whether [uri] looks like a provider's episode at all ("…/series/…"). */
        fun isEpisodeLink(uri: Uri): Boolean = uri.pathSegments.any { it.equals("series", true) }

        /** The provider's name for the show holding the episode at [uri], when it was found before. */
        fun rememberedShow(context: Context, uri: Uri): String? {
            val link = parse(uri) ?: return null
            return remembered(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), link)?.second
        }

        /** (show id, show name) remembered for this episode. */
        private fun remembered(prefs: android.content.SharedPreferences, link: Link): Pair<String, String>? {
            val prefix = "$SHOW_PREFIX${link.base}|${link.user}|"
            for ((key, value) in prefs.all) {
                if (!key.startsWith(prefix) || value !is String) continue
                val parts = value.split('\t', limit = 3)
                if (parts.size == 3 && parts[2].split(',').contains(link.episodeId)) {
                    return key.removePrefix(prefix) to parts[1]
                }
            }
            return null
        }

        private fun showKey(link: Link, seriesId: String) = "$SHOW_PREFIX${link.base}|${link.user}|$seriesId"

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
            if (text.isBlank()) return null
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
