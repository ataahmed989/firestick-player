package app.fireplayer.player

import android.content.Context
import android.util.Log
import app.fireplayer.subs.Tmdb
import app.fireplayer.titles.TitleMatcher
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/**
 * One show as FirePlayer plays it: every episode the provider has (so each one plays the right file), each paired with
 * TMDB's episode when it can be, for its English name, picture and story. The on-screen title, the Episodes list and
 * Next episode all read from it, so they always agree. Built by [build] off the main thread, kept in the cache folder
 * so the next episode of the show shows its name at once, and corrected by hand with [MatchFixes].
 */
class ShowGuide(
    /** Which show this is: by provider, login and show (see [NextEpisode.showKey]). */
    val key: String,
    val seriesId: String,
    /** The provider, as its address reads ("premium-pro2.com:8789"). */
    val portal: String,
    /** The show's name on screen: TMDB's with its year ("Breaking Bad (2008)"), else the provider's. */
    val show: String,
    /** The show's name alone, for a subtitle search. */
    val searchName: String,
    /** The show's TMDB id; 0 when TMDB didn't confirm one. */
    val tmdbId: Int,
    val episodes: List<Ep>,
) {
    /** How sure the pairing with TMDB is. NONE: the show isn't on TMDB (or there's no TMDB key), names are the provider's. */
    enum class Match { SURE, UNSURE, NONE }

    class Ep(
        val id: String,
        val extension: String,
        /** Where the provider puts it, and what it calls it (the episode's own part of its name, often empty). */
        val providerSeason: Int,
        val providerNumber: Int,
        val providerTitle: String,
        /** Where it really is (TMDB's numbers once paired), and its name ("" when none is known). */
        val season: Int,
        val number: Int,
        val name: String,
        val overview: String,
        /** A picture from it: TMDB's, else the provider's; null when neither has one. */
        val image: String?,
        val runtimeMin: Int,
        val airDate: String,
        val match: Match,
        /** Why FirePlayer isn't sure, in words for Settings; null when it is. */
        val why: String?,
        /** TMDB's own length for it, in minutes; 0 when TMDB doesn't say (unlike [runtimeMin], never the provider's). */
        val tmdbMin: Int = 0,
    ) {
        val label: String get() = "S$season E$number"
    }

    fun episode(id: String?): Ep? = if (id == null) null else episodes.firstOrNull { it.id == id }

    /** The seasons in order, by the numbers shown. */
    val seasons: List<Int> get() = episodes.map { it.season }.distinct().sorted()

    fun inSeason(season: Int): List<Ep> = episodes.filter { it.season == season }.sortedBy { it.number }

    private fun toJson(): JSONObject = JSONObject()
        .put("v", VERSION).put("key", key).put("series", seriesId).put("portal", portal).put("show", show)
        .put("search", searchName).put("tmdb", tmdbId)
        .put("episodes", JSONArray().apply {
            episodes.forEach { e ->
                put(JSONObject().put("id", e.id).put("ext", e.extension).put("ps", e.providerSeason).put("pn", e.providerNumber)
                    .put("pt", e.providerTitle).put("s", e.season).put("n", e.number).put("name", e.name).put("ov", e.overview)
                    .put("img", e.image ?: "").put("rt", e.runtimeMin).put("air", e.airDate).put("m", e.match.name)
                    .put("why", e.why ?: "").put("tm", e.tmdbMin))
            }
        })

    companion object {
        private const val TAG = "FirePlayerGuide"
        // A guide written under older rules is built again rather than read.
        private const val VERSION = 2
        private const val SEASON_KEPT_MS = 3 * 24 * 60 * 60_000L
        private const val MAX_SEASONS = 40

        private fun guideFile(context: Context, key: String) =
            File(File(context.cacheDir, "guides").apply { mkdirs() }, "${key.hashCode().toUInt()}.json")

        /** The guide kept from the last time this show played, or null. Reads a small file: off the main thread. */
        fun saved(context: Context, key: String): ShowGuide? = runCatching {
            val file = guideFile(context, key)
            if (!file.exists()) return null
            val o = JSONObject(file.readText())
            if (o.optInt("v") != VERSION || o.optString("key") != key) return null
            val list = o.getJSONArray("episodes")
            val episodes = (0 until list.length()).map { i ->
                val e = list.getJSONObject(i)
                Ep(
                    id = e.getString("id"), extension = e.optString("ext", "mkv"),
                    providerSeason = e.optInt("ps"), providerNumber = e.optInt("pn"), providerTitle = e.optString("pt"),
                    season = e.optInt("s"), number = e.optInt("n"), name = e.optString("name"), overview = e.optString("ov"),
                    image = e.optString("img").ifEmpty { null }, runtimeMin = e.optInt("rt"), airDate = e.optString("air"),
                    match = runCatching { Match.valueOf(e.optString("m")) }.getOrDefault(Match.NONE),
                    why = e.optString("why").ifEmpty { null }, tmdbMin = e.optInt("tm"),
                )
            }
            ShowGuide(key, o.optString("series"), o.optString("portal"), o.optString("show"), o.optString("search"), o.optInt("tmdb"), episodes)
        }.onFailure { Log.i(TAG, "Couldn't read a kept guide: $it") }.getOrNull()

        private fun keep(context: Context, guide: ShowGuide) {
            runCatching { guideFile(context, guide.key).writeText(guide.toJson().toString()) }
        }

        /**
         * Pairs every episode the provider has for a show with TMDB's. Blocks on TMDB; run it off the main thread. Without
         * a TMDB key, or when TMDB doesn't know the show, the provider's own names and numbers are used.
         */
        fun build(context: Context, key: String, portal: String, found: NextEpisode.Found, tmdb: Tmdb, fixes: MatchFixes): ShowGuide {
            val series = found.series
            val fix = fixes.show(key)
            val providerShow = TitleInfo.parse(found.showName, null)
            var show = providerShow.title
            var searchName = providerShow.searchQuery
            var tmdbId = 0
            if (tmdb.isConfigured) {
                runCatching {
                    tmdbId = fix?.tmdbId?.takeIf { it > 0 }
                        ?: TitleMatcher(tmdb).englishName(found.showName, series.tmdb, series.year, true, null)?.tmdb ?: 0
                    if (tmdbId > 0) {
                        val details = tmdb.details(tmdbId, true, allNames = false)
                        if (details != null) {
                            show = details.year?.let { "${details.name} ($it)" } ?: details.name
                            searchName = details.name
                        } else tmdbId = 0
                    }
                }.onFailure { Log.i(TAG, "TMDB didn't answer about the show: $it"); tmdbId = 0 }
            }
            val showNames = listOf(found.showName, show, searchName, series.name.orEmpty()).map { simple(TitleInfo.parse(it, null).searchQuery) }
                .filter { it.isNotEmpty() }.toSet()
            val episodes = if (tmdbId > 0) {
                runCatching { paired(context, tmdb, tmdbId, series.episodes, fix, showNames) }
                    .onFailure { Log.i(TAG, "TMDB didn't answer about the episodes: $it") }
                    .getOrNull()
            } else null
            val guide = ShowGuide(
                key, found.seriesId, portal, show, searchName, if (episodes != null) tmdbId else 0,
                episodes ?: series.episodes.map { providerOnly(it, showNames, fix) },
            )
            keep(context, guide)
            return guide
        }

        /** An episode with only what the provider says about it. */
        private fun providerOnly(e: NextEpisode.Episode, showNames: Set<String>, fix: MatchFixes.Show?): Ep {
            val (season, number) = fix?.place(e.id, e.season, e.number) ?: (e.season to e.number)
            return Ep(
                e.id, e.extension, e.season, e.number, episodeName(e.title, showNames), season, number,
                episodeName(e.title, showNames), e.plot, e.image, e.durationSecs / 60, e.airDate, Match.NONE, null,
            )
        }

        /** Every provider episode paired with TMDB's (see the class notes). */
        private fun paired(
            context: Context,
            tmdb: Tmdb,
            tmdbId: Int,
            provider: List<NextEpisode.Episode>,
            fix: MatchFixes.Show?,
            showNames: Set<String>,
        ): List<Ep> {
            val wantedSeasons = (provider.map { it.season } + (fix?.seasons() ?: emptyList())).distinct().take(MAX_SEASONS)
            val seasons = HashMap<Int, List<Tmdb.TvEpisode>>()
            fun load(n: Int) = seasons.getOrPut(n) { season(context, tmdb, tmdbId, n) }
            wantedSeasons.forEach { load(it) }
            // A show numbered straight through (anime, "Episode 140"): one season in the provider's list, many more
            // episodes than TMDB's season has. Paired by order against all TMDB's seasons.
            val providerSeasons = provider.map { it.season }.distinct()
            val straight: List<Tmdb.TvEpisode>? = if (providerSeasons.size == 1) {
                val only = providerSeasons[0]
                val count = provider.size
                val tmdbCount = seasons[only]?.size ?: 0
                if (count > tmdbCount + 2) {
                    runCatching { tmdb.seasonNumbers(tmdbId) }.getOrNull()?.takeIf { it.size > 1 }
                        ?.flatMap { load(it) }?.takeIf { it.size >= count - 2 }
                } else null
            } else null
            val all = seasons.values.flatten()
            val byName = all.groupBy { simple(it.name) }.filterKeys { it.length >= 3 }
            fun find(season: Int, number: Int) = seasons[season]?.firstOrNull { it.number == number }
                ?: if (seasons.containsKey(season)) null else load(season).firstOrNull { it.number == number }
            val ordered = provider.sortedWith(compareBy({ it.season }, { it.number }))
            return provider.map { e ->
                val title = episodeName(e.title, showNames)
                val titled = simple(title).takeIf { it.length >= 3 }?.let { byName[it]?.singleOrNull() }
                val place = fix?.place(e.id, e.season, e.number)
                val picked: Tmdb.TvEpisode?
                var match: Match
                var why: String? = null
                when {
                    place != null -> {
                        picked = find(place.first, place.second)
                        match = Match.SURE
                    }
                    straight != null -> {
                        val k = ordered.indexOf(e)
                        picked = straight.getOrNull(k)
                        match = if (picked != null && titled === picked) Match.SURE else Match.UNSURE
                        if (match == Match.UNSURE) why = if (picked != null) {
                            "Your provider numbers this show straight through: this is its episode ${k + 1}, " +
                                "which is ${picked.label()} on TMDB."
                        } else "TMDB has fewer episodes of this show than your provider."
                    }
                    else -> {
                        val same = find(e.season, e.number)
                        if (titled != null && same != null && !sameName(title, same.name) && titled !== same) {
                            picked = titled
                            match = Match.UNSURE
                            why = "Your provider calls this S${e.season} E${e.number}, but its title “$title” is " +
                                "${titled.label()} on TMDB."
                        } else if (same != null) {
                            picked = same
                            match = Match.SURE
                        } else if (titled != null) {
                            picked = titled
                            match = Match.UNSURE
                            why = "TMDB has no S${e.season} E${e.number}; its title “$title” is ${titled.label()} there."
                        } else {
                            picked = null
                            match = Match.UNSURE
                            why = "TMDB has no S${e.season} E${e.number} for this show, so the name is your provider's."
                        }
                    }
                }
                if (fix != null && e.id in fix.confirmed) {
                    match = Match.SURE
                    why = null
                }
                if (picked == null) {
                    val (season, number) = place ?: (e.season to e.number)
                    Ep(e.id, e.extension, e.season, e.number, title, season, number, title, e.plot, e.image,
                        e.durationSecs / 60, e.airDate, match, why)
                } else {
                    Ep(e.id, e.extension, e.season, e.number, title, picked.season, picked.number, picked.name,
                        picked.overview.ifEmpty { e.plot }, picked.still ?: e.image,
                        picked.runtime.takeIf { it > 0 } ?: (e.durationSecs / 60), picked.airDate.ifEmpty { e.airDate }, match, why,
                        picked.runtime)
                }
            }
        }

        private fun Tmdb.TvEpisode.label() = "S$season E$number"

        /** A season from TMDB, kept for a few days in the cache folder. */
        private fun season(context: Context, tmdb: Tmdb, id: Int, season: Int): List<Tmdb.TvEpisode> {
            val file = File(File(context.cacheDir, "tmdb").apply { mkdirs() }, "tv-$id-s$season.json")
            if (file.exists() && System.currentTimeMillis() - file.lastModified() < SEASON_KEPT_MS) {
                runCatching {
                    val list = JSONArray(file.readText())
                    return (0 until list.length()).map { i ->
                        val e = list.getJSONObject(i)
                        Tmdb.TvEpisode(e.optInt("id"), e.optInt("s"), e.optInt("n"), e.optString("name"), e.optString("ov"),
                            e.optString("still").ifEmpty { null }, e.optInt("rt"), e.optString("air"))
                    }
                }
            }
            val fresh = tmdb.season(id, season)
            runCatching {
                file.writeText(JSONArray().apply {
                    fresh.forEach { e ->
                        put(JSONObject().put("id", e.id).put("s", e.season).put("n", e.number).put("name", e.name)
                            .put("ov", e.overview).put("still", e.still ?: "").put("rt", e.runtime).put("air", e.airDate))
                    }
                }.toString())
            }
            return fresh
        }

        // "S01E03", "1x03", "Season 1 Episode 3", "Ep 3": where an episode's own part of its name starts.
        private val MARK = Regex("""(?i)\b(S\d{1,2}\s*[ ._-]?\s*EP?\s*\d{1,4}|\d{1,2}x\d{2,4}|Season\s*\d{1,2}\s*[,.-]?\s*Episode\s*\d{1,4})\b""")
        private val JUNK = Regex("""(?i)\b(2160p|1080p|720p|480p|4k|uhd|hdr10?\+?|hdr|web-?dl|webrip|bluray|brrip|remux|hdtv|x26[45]|h\.?26[45]|hevc|aac|e?ac3|ddp?5\.1|dts|atmos|10bit|multi|proper|repack)\b.*$""")
        private val ONLY_NUMBER = Regex("""(?i)^(episode|ep|e|part|chapter)?\s*\d{1,4}$""")
        private val SEPARATORS = charArrayOf(' ', '-', '–', '—', '|', ':', '.', '_', '·')

        /**
         * The episode's own name out of the provider's ("Breaking Bad - S02E03 - Bit by a Dead Bee" → "Bit by a Dead
         * Bee"); "" when it has none of its own ("S02E03", "Episode 3", just the show's name).
         */
        fun episodeName(title: String, showNames: Set<String>): String {
            var rest = title.trim()
            val mark = MARK.find(rest)
            if (mark != null) rest = rest.substring(mark.range.last + 1)
            else showNames.firstOrNull { it.isNotEmpty() && simple(rest).startsWith(it) }?.let { show ->
                // No number in it: only the show's name in front is taken off.
                var cut = 0
                var seen = 0
                while (cut < rest.length && seen < show.length) {
                    if (rest[cut].isLetterOrDigit()) seen++
                    cut++
                }
                rest = rest.substring(cut)
            }
            rest = rest.replace('.', ' ').replace('_', ' ')
            rest = JUNK.replace(rest, "").replace(Regex("""\s*[\[(][^\])]*[\])]\s*$"""), "").trim(*SEPARATORS).trim()
            if (rest.isEmpty() || ONLY_NUMBER.matches(rest) || simple(rest) in showNames) return ""
            return rest
        }

        /** The same name, give or take punctuation, case, accents and "the" in front, or one a part of the other. */
        fun sameName(a: String, b: String): Boolean {
            val x = simple(a)
            val y = simple(b)
            if (x.isEmpty() || y.isEmpty()) return false
            if (x == y) return true
            val (short, long) = if (x.length <= y.length) x to y else y to x
            return short.length >= 5 && short.length * 10 >= long.length * 7 && long.contains(short)
        }

        private val MARKS = Regex("""\p{M}+""")
        private val ARTICLE = Regex("""^(the|a|an)\s+""")

        /** Only letters and digits, without accents and "the" in front. */
        fun simple(s: String): String = Normalizer.normalize(s.lowercase().trim().replace(ARTICLE, ""), Normalizer.Form.NFD)
            .replace(MARKS, "").replace("&", "and").filter { it.isLetterOrDigit() }
    }
}

/**
 * Matches put right by hand in Settings (Fix match, Looks right), kept for good. A show's fixes win over the automatic
 * pairing in [ShowGuide.build]; a film's picks its name on screen.
 */
class MatchFixes(context: Context) {

    private val prefs = context.getSharedPreferences("match_fixes", Context.MODE_PRIVATE)

    /**
     * What was put right for one show: the TMDB show it is (0: as found), episodes placed one by one, and runs of a
     * season moved together ("from E5 on, one back"), and episodes said to be right as they are.
     */
    class Show(val tmdbId: Int, val picks: Map<String, Pair<Int, Int>>, val shifts: List<Shift>, val confirmed: Set<String>) {
        /** From [from] on, the provider's season [season] is TMDB's [toSeason], each number moved by [delta]. */
        class Shift(val season: Int, val from: Int, val toSeason: Int, val delta: Int)

        /** Where an episode belongs by these fixes, or null when they don't say. */
        fun place(id: String, season: Int, number: Int): Pair<Int, Int>? {
            picks[id]?.let { return it }
            val shift = shifts.filter { it.season == season && number >= it.from }.maxByOrNull { it.from } ?: return null
            return shift.toSeason to (number + shift.delta).coerceAtLeast(0)
        }

        fun seasons(): List<Int> = picks.values.map { it.first } + shifts.map { it.toSeason }

        fun toJson(): String = JSONObject().put("tmdb", tmdbId)
            .put("picks", JSONObject().apply { picks.forEach { (id, p) -> put(id, "${p.first},${p.second}") } })
            .put("shifts", JSONArray().apply { shifts.forEach { put("${it.season},${it.from},${it.toSeason},${it.delta}") } })
            .put("ok", JSONArray().apply { confirmed.forEach { put(it) } })
            .toString()

        companion object {
            fun fromJson(text: String): Show? = runCatching {
                val o = JSONObject(text)
                val picks = HashMap<String, Pair<Int, Int>>()
                o.optJSONObject("picks")?.let { p ->
                    p.keys().forEach { id ->
                        val parts = p.optString(id).split(',')
                        if (parts.size == 2) picks[id] = parts[0].toInt() to parts[1].toInt()
                    }
                }
                val shifts = o.optJSONArray("shifts")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        a.optString(i).split(',').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 }?.let { Shift(it[0], it[1], it[2], it[3]) }
                    }
                }.orEmpty()
                val ok = o.optJSONArray("ok")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
                Show(o.optInt("tmdb"), picks, shifts, ok)
            }.getOrNull()
        }
    }

    fun show(key: String): Show? = prefs.getString("show|$key", null)?.let { Show.fromJson(it) }

    private fun saveShow(key: String, show: Show) = prefs.edit().putString("show|$key", show.toJson()).apply()

    /** "Looks right": this episode is kept as it was paired. */
    fun confirm(key: String, episodeId: String) {
        val old = show(key) ?: Show(0, emptyMap(), emptyList(), emptySet())
        saveShow(key, Show(old.tmdbId, old.picks, old.shifts, old.confirmed + episodeId))
    }

    /**
     * Fix match: the show is TMDB's [tmdbId], and this episode (the provider's [season] / [number]) is TMDB's
     * [toSeason] / [toNumber]; with [restOfSeason], the provider's later episodes of that season move the same way.
     * Another TMDB show than before drops the fixes made for the old one.
     */
    fun place(key: String, tmdbId: Int, episodeId: String, season: Int, number: Int, toSeason: Int, toNumber: Int, restOfSeason: Boolean) {
        val old = show(key)?.takeIf { it.tmdbId == 0 || it.tmdbId == tmdbId }
        val picks = HashMap(old?.picks.orEmpty())
        picks[episodeId] = toSeason to toNumber
        val shifts = old?.shifts.orEmpty().filterNot { restOfSeason && it.season == season && it.from >= number }.toMutableList()
        if (restOfSeason) shifts += Show.Shift(season, number, toSeason, toNumber - number)
        saveShow(key, Show(tmdbId, picks, shifts, old?.confirmed.orEmpty() - episodeId))
    }

    /** Fix match on a film: the name it goes by, by the provider and its id for it. */
    fun film(portal: String, id: String): String? = prefs.getString("film|$portal|$id", null)

    fun setFilm(portal: String, id: String, title: String) = prefs.edit().putString("film|$portal|$id", title).apply()

    /** Forgets every fix made for a provider (when its playlist is gone from TiviMate). */
    fun forgetPortal(portal: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("film|$portal|") || (it.startsWith("show|") && it.contains("/$portal|")) }
            .forEach { editor.remove(it) }
        editor.apply()
    }
}
