package app.fireplayer.vod.data

import app.fireplayer.vod.Card
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.Kind
import app.fireplayer.vod.Title
import org.json.JSONArray
import org.json.JSONObject

/** Cinemeta's (and Stremio's) name for a kind: "movie" or "series". */
val Kind.type: String get() = if (this == Kind.MOVIE) "movie" else "series"

/** The kind Cinemeta calls [type]; null for anything else ("channel", "tv"…). */
fun kindOf(type: String?): Kind? = when (type) {
    "movie" -> Kind.MOVIE
    "series" -> Kind.SERIES
    else -> null
}

/**
 * Titles and cards as small JSON objects, for the cache on disk and the library database: only [Title]'s own fields
 * (a series page from Cinemeta is about 0.6 MB; trimmed like this, about 45 KB per 50 titles).
 */
object TitleJson {

    fun write(t: Title): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("type", t.kind.type)
        put("name", t.name)
        t.year?.let { put("year", it) }
        t.poster?.let { put("poster", it) }
        t.background?.let { put("background", it) }
        t.logo?.let { put("logo", it) }
        t.description?.let { put("description", it) }
        t.imdbRating?.let { put("imdbRating", it) }
        if (t.genres.isNotEmpty()) put("genres", JSONArray(t.genres))
        t.runtime?.let { put("runtime", it) }
        t.tmdbId?.let { put("tmdbId", it) }
    }

    /** Null when the object lacks an id, a known kind or a name. */
    fun read(o: JSONObject?): Title? {
        if (o == null) return null
        val id = o.text("id") ?: return null
        val kind = kindOf(o.text("type")) ?: return null
        val name = o.text("name") ?: return null
        return Title(
            id = id,
            kind = kind,
            name = name,
            year = o.text("year"),
            poster = o.text("poster"),
            background = o.text("background"),
            logo = o.text("logo"),
            description = o.text("description"),
            imdbRating = o.text("imdbRating"),
            genres = o.optJSONArray("genres").texts(),
            runtime = o.text("runtime"),
            tmdbId = o.optInt("tmdbId", 0).takeIf { it > 0 },
        )
    }

    fun writeTitles(titles: List<Title>): JSONArray = JSONArray().apply { titles.forEach { put(write(it)) } }

    fun readTitles(a: JSONArray?): List<Title> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { read(a.optJSONObject(it)) }

    fun writeCard(c: Card): JSONObject = JSONObject().apply {
        put("title", write(c.title))
        c.progress?.let { put("progress", it.toDouble()) }
        c.caption?.let { put("caption", it) }
        c.rank?.let { put("rank", it) }
        c.episode?.let { e ->
            put("season", e.season)
            put("episode", e.episode)
            e.name?.let { put("episodeName", it) }
        }
        c.badge?.let { put("badge", it) }
    }

    fun readCard(o: JSONObject?): Card? {
        val title = read(o?.optJSONObject("title")) ?: return null
        return Card(
            title = title,
            progress = if (o!!.has("progress")) o.optDouble("progress").toFloat() else null,
            caption = o.text("caption"),
            rank = o.optInt("rank", 0).takeIf { it > 0 },
            episode = if (o.has("season") && o.has("episode")) {
                EpisodeRef(o.optInt("season"), o.optInt("episode"), o.text("episodeName"))
            } else {
                null
            },
            badge = o.text("badge"),
        )
    }

    fun writeCards(cards: List<Card>): JSONArray = JSONArray().apply { cards.forEach { put(writeCard(it)) } }

    fun readCards(a: JSONArray?): List<Card> =
        if (a == null) emptyList() else (0 until a.length()).mapNotNull { readCard(a.optJSONObject(it)) }
}

/**
 * A non-blank string, else null. org.json's optString gives "null" for a JSON null and "" for a missing key; Cinemeta
 * sends both, and numbers where a string is usual (a rating of 7 instead of "7.0").
 */
internal fun JSONObject.text(key: String): String? = when (val v = opt(key)) {
    is String -> v.trim().takeIf { it.isNotEmpty() }
    is Number -> v.toString()
    else -> null
}

/** The array's non-blank strings, in order. */
internal fun JSONArray?.texts(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { (opt(it) as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }
}
