package app.fireplayer.vod.data

import app.fireplayer.vod.Card
import app.fireplayer.vod.Kind
import app.fireplayer.vod.Title
import app.fireplayer.vod.standInId
import java.text.Normalizer

/**
 * More Like This, put together (pure; the fetching is [DefaultVodRepository]'s). Up to [MAX] poster cards, never the
 * title itself, one per IMDb id:
 * - With a usable TMDB key: TMDB's recommendations in its order, topped up from its similar titles ([fromTmdb]), both
 *   from the details call the page makes anyway. Left out: adult titles, titles without a poster, very few votes
 *   (under [RECOMMENDED_MIN_VOTES] / [SIMILAR_MIN_VOTES]), no date or one after today (no sources yet), the title
 *   itself, repeats, and titles TMDB lately had no IMDb id for (they couldn't open).
 * - Without a key, when TMDB fails, or when it gives fewer than [FEW]: Cinemeta's, for a film first its director's
 *   films ([byDirector], a name search), then for both the main genre's Popular ranked by genres shared ([byGenre]).
 */
object MoreLikeThis {

    const val MAX = 15
    /** TMDB giving fewer than this is topped up from Cinemeta. */
    const val FEW = 5
    const val RECOMMENDED_MIN_VOTES = 20
    const val SIMILAR_MIN_VOTES = 50
    /** At most this many of a director's films. */
    const val DIRECTOR_MAX = 5
    /** How long a director search is kept (Cinemeta's own answer says a day). */
    const val SEARCH_FRESH_MS = 24 * 3600_000L

    /** A TMDB title chosen, with its IMDb id when known here. */
    data class Pick(val item: TmdbDetails.Item, val imdb: String?)

    /**
     * TMDB's lists in [extras] made into picks (see the class). [selfId] is the title's IMDb id, [today] "2026-11-18"
     * (UTC, compared with TMDB's dates as text), [known] what's known of an item's IMDb id.
     */
    fun fromTmdb(
        extras: TmdbDetails.Extras,
        selfId: String,
        today: String,
        known: (Kind, Int) -> TmdbTrending.Known,
        limit: Int = MAX,
    ): List<Pick> {
        val out = ArrayList<Pick>()
        val seenTmdb = hashSetOf("${extras.kind.type}#${extras.tmdbId}")
        val seenImdb = hashSetOf(selfId)
        for ((list, minVotes) in listOf(extras.recommendations to RECOMMENDED_MIN_VOTES, extras.similar to SIMILAR_MIN_VOTES)) {
            for (item in list) {
                if (out.size >= limit) return out
                if (item.adult || item.poster == null || item.votes < minVotes) continue
                val date = item.date ?: continue
                if (date > today) continue
                if (!seenTmdb.add("${item.kind.type}#${item.tmdbId}")) continue
                val imdb = when (val k = known(item.kind, item.tmdbId)) {
                    is TmdbTrending.Known.Hit -> k.imdb
                    TmdbTrending.Known.Miss -> continue
                    TmdbTrending.Known.Unknown -> null
                }
                if (imdb != null && !seenImdb.add(imdb)) continue
                out += Pick(item, imdb)
            }
        }
        return out
    }

    /**
     * The card for a TMDB pick. With its IMDb id: Cinemeta's title when known here ([cinemeta]), else one made from
     * TMDB's item with metahub's poster, as Top 10 does ([TmdbTrending.title]). Without: a stand-in ([standInId]) with
     * TMDB's poster at [VodArt.TMDB_POSTER] and the TMDB id, to [app.fireplayer.vod.VodRepository.resolve] when opened.
     * Either way TMDB's backdrop at `w1280` when it has one.
     */
    fun card(pick: Pick, cinemeta: Title?): Card {
        val i = pick.item
        if (pick.imdb != null) {
            val candidate = TmdbTrending.Candidate(i.tmdbId, i.kind, i.name, i.year, i.overview, i.backdrop)
            return Card(TmdbTrending.title(candidate, pick.imdb, cinemeta))
        }
        return Card(
            Title(
                id = standInId(i.kind, i.tmdbId),
                kind = i.kind,
                name = i.name,
                year = i.year,
                poster = i.poster?.let(VodArt::tmdbPoster),
                background = i.backdrop?.let(VodArt::tmdbBackdrop),
                description = i.overview,
                tmdbId = i.tmdbId,
            ),
        )
    }

    /**
     * A director's films from a Cinemeta name search ([found], in Cinemeta's order), at most [limit]. A name search
     * finds the name in any field, so left out: the title itself ([selfId]), anything without a year or after
     * [thisYear] (announced, no sources), and names holding the director's name (documentaries about them: "QT8").
     */
    fun byDirector(found: List<Title>, director: String, selfId: String, thisYear: Int, limit: Int = DIRECTOR_MAX): List<Title> {
        val full = fold(director).trim()
        if (full.isEmpty()) return emptyList()
        val surname = Regex("\\b" + Regex.escape(full.split(' ').last()) + "\\b")
        val out = ArrayList<Title>()
        val seen = hashSetOf(selfId)
        for (t in found) {
            if (out.size >= limit) break
            if (!t.id.startsWith("tt") || t.id in seen) continue
            val year = t.year?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }?.toInt() ?: continue
            if (year > thisYear) continue
            val name = fold(t.name)
            if (name.contains(full) || surname.containsMatchIn(name)) continue
            seen += t.id
            out += t
        }
        return out
    }

    /**
     * A genre's Popular page ([found], Cinemeta's order) ranked by the number of [genres] each shares with the title,
     * then Cinemeta's order; [exclude] (the title itself, those already chosen) left out; at most [limit].
     */
    fun byGenre(found: List<Title>, genres: List<String>, exclude: Set<String>, limit: Int): List<Title> {
        if (limit <= 0) return emptyList()
        val mine = genres.toSet()
        val seen = HashSet(exclude)
        return found.withIndex()
            .filter { (_, t) -> t.id.startsWith("tt") && seen.add(t.id) }
            .sortedWith(compareBy<IndexedValue<Title>>({ -it.value.genres.count { g -> g in mine } }, { it.index }))
            .take(limit)
            .map { it.value }
    }

    /** The TMDB id in a stand-in id ("tmdb:movie:693134" → 693134); null for any other id. */
    fun tmdbIdOfStandIn(id: String): Int? =
        if (id.startsWith("tmdb:")) id.substringAfterLast(':').toIntOrNull()?.takeIf { it > 0 } else null

    /** [first] then [more], each id once, never [self], at most [MAX]. */
    fun join(self: Title, first: List<Card>, more: List<Card>): List<Card> {
        val seen = hashSetOf(self.id)
        self.tmdbId?.let { seen += standInId(self.kind, it) }
        return (first + more).filter { seen.add(it.title.id) }.take(MAX)
    }

    /** Lower case without accents, for comparing names ("Séguéla" is "seguela"). */
    private fun fold(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")

    private val MARKS = Regex("""\p{M}+""")
}
