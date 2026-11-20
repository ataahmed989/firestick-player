package app.fireplayer.vod.data

import org.json.JSONObject

/** Real (and a few synthetic) Cinemeta and TMDB answers from test/resources/vod, recorded on 2026-11-18. */
object Fixtures {
    fun text(name: String): String =
        requireNotNull(Fixtures::class.java.getResource("/vod/$name")) { "missing fixture $name" }.readText()

    fun json(name: String) = JSONObject(text(name))

    /** The film and TV ids of tmdb_external_ids_map.json (null: TMDB has no IMDb id). */
    fun externalIds(): Map<String, Map<Int, String?>> {
        val o = json("tmdb_external_ids_map.json")
        return listOf("movie", "tv").associateWith { kind ->
            val m = o.getJSONObject(kind)
            m.keys().asSequence().associate { it.toInt() to (if (m.isNull(it)) null else m.getString(it)) }
        }
    }

    fun expectedTop10(kind: String): List<String> {
        val a = json("tmdb_expected_top10.json").getJSONArray(kind)
        return (0 until a.length()).map { a.getString(it) }
    }
}
