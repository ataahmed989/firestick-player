package app.fireplayer.titles

import app.fireplayer.player.TitleInfo
import app.fireplayer.subs.Tmdb
import kotlin.math.abs

/**
 * The English name a provider's film or show gets in TiviMate: "The Matrix (1999) [4K]", or null to keep the
 * provider's own when TMDB isn't sure. Blocks on TMDB; run it off the main thread.
 */
class TitleMatcher(private val tmdb: Tmdb) {

    /**
     * [raw] is the provider's name, [tmdbId] the TMDB id it gave (0 for none), [year] the year it gave elsewhere (0
     * for none) and [category] the name of the category it's in.
     */
    fun englishName(raw: String, tmdbId: Int, year: Int, show: Boolean, category: String?): String? {
        val info = TitleInfo.parse(raw, null)
        val name = info.searchQuery.trim()
        if (name.isEmpty()) return null
        val wantedYear = YEAR.find(info.title)?.groupValues?.get(1)?.toIntOrNull() ?: year.takeIf { it > 0 }
        // "Radioactive Detectives 2017" may be the name and its year, or a name ending in a number ("Blade Runner
        // 2049"): read both ways, the name as it is first; only what TMDB confirms counts either way.
        val bare = BARE_YEAR.find(name)?.takeIf { it.range.first > 0 && wantedYear == null }
        val readings = listOf(name to wantedYear) +
            listOfNotNull(bare?.let { name.substring(0, it.range.first).trim() to it.groupValues[1].toInt() })
        val match = readings.firstNotNullOfOrNull { (n, y) -> if (tmdbId > 0) byId(tmdbId, n, y, show) else null }
            ?: readings.firstNotNullOfOrNull { (n, y) -> tmdb.verify(n, y, show)?.let { Found(it.name, it.year) } }
            ?: return null
        val quality = Quality.of(raw, category)
        return buildString {
            append(match.name)
            match.year?.let { append(" (").append(it).append(')') }
            quality?.let { append(" [").append(it).append(']') }
        }
    }

    private class Found(val name: String, val year: String?)

    /**
     * The title the provider says this is, when TMDB has it under this very name in some language (its English,
     * original or a translated or other name), from about the same year. Most names are confirmed by the first, small
     * answer; only the rest need every name TMDB has.
     */
    private fun byId(id: Int, name: String, year: Int?, show: Boolean): Found? {
        val wanted = tmdb.simplify(name)
        if (wanted.isEmpty()) return null
        val first = tmdb.details(id, show, allNames = false) ?: return null
        if (!fits(first.year, year, show)) return null
        val details = if (agrees(first.names, wanted, year != null)) first
        else tmdb.details(id, show, allNames = true)?.takeIf { agrees(it.names, wanted, year != null) } ?: return null
        return Found(details.name, details.year)
    }

    /**
     * The same name, or (when the year agrees too) one that starts or ends the other and isn't much shorter: "DAHMER -
     * Monster: The Jeffrey Dahmer Story" for "Monster: The Jeffrey Dahmer Story", not "It" for "It Follows".
     */
    private fun agrees(names: Set<String>, wanted: String, withYear: Boolean): Boolean = names.any { n ->
        if (n == wanted) return@any true
        if (!withYear) return@any false
        val (short, long) = if (n.length <= wanted.length) n to wanted else wanted to n
        short.length * 10 >= long.length * 6 && (long.startsWith(short) || long.endsWith(short))
    }

    /** TMDB's year and the provider's, give or take one; a show's may be a later season's. */
    private fun fits(found: String?, wanted: Int?, show: Boolean): Boolean {
        val y = found?.toIntOrNull() ?: return true
        if (wanted == null) return true
        return if (show) y <= wanted + 1 else abs(y - wanted) <= 1
    }

    private companion object {
        val YEAR = Regex("""\(((?:19|20)\d{2})\)""")
        val BARE_YEAR = Regex("""\s((?:19|20)\d{2})$""")
    }
}
