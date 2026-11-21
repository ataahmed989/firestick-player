package app.fireplayer.vod.data

import app.fireplayer.vod.CuratedTitle
import app.fireplayer.vod.Kind
import app.fireplayer.vod.RowSpec
import app.fireplayer.vod.RowStyle
import app.fireplayer.vod.Source
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * The Disney+ studio pages: each studio's rows of titles, in order, and the chosen lists behind the ordered ones (pure,
 * unit-tested). Every row holds only what Disney+ carries in the US: TMDB Discover narrowed to the studio
 * ([Source.Discover], most popular first, more as the viewer goes along), or a chosen list in its own order
 * ([Source.Curated]). A row with nothing to show is hidden by the page. National Geographic has no page of rows: its
 * card opens its collection ([Services.collection]).
 *
 * The lists hold TMDB's ids. Each entry carries words of the title's name and its year, which TMDB's answer must agree
 * with ([Curated.matches]), so a mistaken id leaves a title out and never shows another in its place.
 */
object Studios {

    private val DISNEY_PLUS = Service.DISNEY.providerId

    // TMDB's production companies.
    private const val WALT_DISNEY_PICTURES = "2"
    private const val WALT_DISNEY_ANIMATION = "6125"
    private const val DISNEY_BRANDED = "$WALT_DISNEY_PICTURES|$WALT_DISNEY_ANIMATION"
    private const val PIXAR = "3"
    private const val LUCASFILM = "1"
    /** Marvel Studios, Marvel Animation, Marvel Entertainment. */
    private const val MARVEL_ALL = "420|13252|7505"

    // TMDB's genres.
    private const val ADVENTURE = "12"
    private const val ANIMATION = "16"
    private const val MUSIC = "10402"

    /** A short film runs at most this long, minutes; a special, [SPECIAL_MINUTES]. */
    private const val SHORT_MINUTES = 40
    private const val SPECIAL_MINUTES = 60

    /** Whether [brand] has a page of rows (else its card opens its collection). */
    fun hasPage(brand: Brand): Boolean = rows(brand).isNotEmpty()

    /** [brand]'s rows, in order. */
    fun rows(brand: Brand): List<RowSpec> = when (brand) {
        Brand.DISNEY -> listOf(
            discover(brand, "popular", "Popular Disney Movies", Kind.MOVIE, DISNEY_BRANDED),
            discover(brand, "animation", "Walt Disney Animation Studios", Kind.MOVIE, WALT_DISNEY_ANIMATION),
            discover(brand, "adventures", "Live-Action Adventures", Kind.MOVIE, WALT_DISNEY_PICTURES, genres = ADVENTURE, without = ANIMATION),
            discover(brand, "musicals", "Disney Musicals", Kind.MOVIE, DISNEY_BRANDED, genres = MUSIC),
        )
        Brand.PIXAR -> listOf(
            // Films and series in turn: its See all is Pixar's whole collection.
            discover(brand, "popular", "Popular Pixar", null, PIXAR),
            curated(brand, "features", "Feature Films", Kind.MOVIE, PIXAR_FEATURES),
            discover(brand, "shorts", "Shorts", Kind.MOVIE, PIXAR, maxRuntime = SHORT_MINUTES),
            discover(brand, "series", "Series & Specials", Kind.SERIES, PIXAR),
        )
        Brand.MARVEL -> listOf(
            discover(brand, "popular", "Popular Marvel", null, brand.companies),
            curated(brand, "mcu_movies", "MCU Movies", Kind.MOVIE, MCU_MOVIES),
            curated(brand, "mcu_series", "MCU Series", Kind.SERIES, MCU_SERIES),
            discover(brand, "animation", "Marvel Animation", null, MARVEL_ALL, genres = ANIMATION),
            curated(brand, "beyond", "Beyond the MCU", Kind.MOVIE, BEYOND_THE_MCU),
        )
        Brand.STAR_WARS -> listOf(
            curated(brand, "saga", "Skywalker Saga", Kind.MOVIE, SKYWALKER_SAGA),
            curated(brand, "standalone", "Standalone Movies", Kind.MOVIE, STAR_WARS_STANDALONE),
            curated(brand, "live_action", "Live-Action Series", Kind.SERIES, STAR_WARS_LIVE_ACTION),
            curated(brand, "animated", "Animated Series", Kind.SERIES, STAR_WARS_ANIMATED),
            // Lucasfilm's short films and specials.
            discover(brand, "shorts", "Shorts & Specials", Kind.MOVIE, LUCASFILM, maxRuntime = SPECIAL_MINUTES),
        )
        Brand.NAT_GEO -> emptyList()
    }

    /**
     * The grid a studio row's See all opens when it isn't the row's own titles: the studio's whole collection on
     * Disney+, from its page's first row (Popular Pixar, Popular Marvel, the Skywalker Saga). Null: the row's own.
     */
    fun seeAll(spec: RowSpec): RowSpec? = when (spec.key) {
        "studio.${Brand.PIXAR.key}.popular" -> Services.collection(Brand.PIXAR)
        "studio.${Brand.MARVEL.key}.popular" -> Services.collection(Brand.MARVEL)
        "studio.${Brand.STAR_WARS.key}.saga" -> Services.collection(Brand.STAR_WARS)
        else -> null
    }

    private fun discover(
        brand: Brand, key: String, label: String, kind: Kind?, companies: String,
        genres: String? = null, without: String? = null, maxRuntime: Int? = null,
    ) = RowSpec(
        "studio.${brand.key}.$key", label, RowStyle.POSTER,
        Source.Discover(kind, DISNEY_PLUS, companies, genres = genres, withoutGenres = without, maxRuntime = maxRuntime, paged = true),
    )

    private fun curated(brand: Brand, key: String, label: String, kind: Kind, titles: List<CuratedTitle>) =
        RowSpec("studio.${brand.key}.$key", label, RowStyle.POSTER, Source.Curated(kind, DISNEY_PLUS, titles))

    private fun t(id: Int, name: String, year: Int) = CuratedTitle(id, name, year)

    /** Episodes I to IX, in episode order. */
    val SKYWALKER_SAGA = listOf(
        t(1893, "The Phantom Menace", 1999),
        t(1894, "Attack of the Clones", 2002),
        t(1895, "Revenge of the Sith", 2005),
        t(11, "Star Wars", 1977),
        t(1891, "The Empire Strikes Back", 1980),
        t(1892, "Return of the Jedi", 1983),
        t(140607, "The Force Awakens", 2015),
        t(181808, "The Last Jedi", 2017),
        t(181812, "The Rise of Skywalker", 2019),
    )

    /** The films outside the saga; the last two are the Ewok films (Caravan of Courage, The Battle for Endor). */
    val STAR_WARS_STANDALONE = listOf(
        t(330459, "Rogue One", 2016),
        t(348350, "Solo", 2018),
        t(12180, "The Clone Wars", 2008),
        t(1884, "Ewok", 1984),
        t(1885, "Ewok", 1985),
    )

    val STAR_WARS_LIVE_ACTION = listOf(
        t(82856, "The Mandalorian", 2019),
        t(115036, "The Book of Boba Fett", 2021),
        t(92830, "Obi-Wan Kenobi", 2022),
        t(83867, "Andor", 2022),
        t(114461, "Ahsoka", 2023),
        t(114479, "The Acolyte", 2024),
        t(202879, "Skeleton Crew", 2024),
    )

    val STAR_WARS_ANIMATED = listOf(
        t(4194, "The Clone Wars", 2008),
        t(60554, "Rebels", 2014),
        t(79093, "Resistance", 2018),
        t(105971, "The Bad Batch", 2021),
        t(114478, "Visions", 2021),
        t(203085, "Tales of the Jedi", 2022),
        t(202998, "Young Jedi Adventures", 2023),
        t(3122, "Clone Wars", 2003),
        t(3478, "Droids", 1985),
        t(3479, "Ewoks", 1985),
    )

    /** The Marvel Cinematic Universe's films, in release order. */
    val MCU_MOVIES = listOf(
        t(1726, "Iron Man", 2008),
        t(1724, "The Incredible Hulk", 2008),
        t(10138, "Iron Man 2", 2010),
        t(10195, "Thor", 2011),
        t(1771, "The First Avenger", 2011),
        t(24428, "The Avengers", 2012),
        t(68721, "Iron Man 3", 2013),
        t(76338, "The Dark World", 2013),
        t(100402, "The Winter Soldier", 2014),
        t(118340, "Guardians of the Galaxy", 2014),
        t(99861, "Age of Ultron", 2015),
        t(102899, "Ant-Man", 2015),
        t(271110, "Civil War", 2016),
        t(284052, "Doctor Strange", 2016),
        t(283995, "Guardians of the Galaxy Vol. 2", 2017),
        t(315635, "Homecoming", 2017),
        t(284053, "Ragnarok", 2017),
        t(284054, "Black Panther", 2018),
        t(299536, "Infinity War", 2018),
        t(363088, "Ant-Man and the Wasp", 2018),
        t(299537, "Captain Marvel", 2019),
        t(299534, "Endgame", 2019),
        t(429617, "Far From Home", 2019),
        t(497698, "Black Widow", 2021),
        t(566525, "Shang-Chi", 2021),
        t(524434, "Eternals", 2021),
        t(634649, "No Way Home", 2021),
        t(453395, "Multiverse of Madness", 2022),
        t(616037, "Love and Thunder", 2022),
        t(505642, "Wakanda Forever", 2022),
        t(640146, "Quantumania", 2023),
        t(447365, "Guardians of the Galaxy Vol. 3", 2023),
        t(609681, "The Marvels", 2023),
        t(533535, "Deadpool & Wolverine", 2024),
        t(822119, "Brave New World", 2025),
        t(986056, "Thunderbolts", 2025),
        t(617126, "First Steps", 2025),
    )

    /** The MCU's series: Marvel Studios' own in release order, then Marvel Television's set in the same world. */
    val MCU_SERIES = listOf(
        t(85271, "WandaVision", 2021),
        t(88396, "The Falcon and the Winter Soldier", 2021),
        t(84958, "Loki", 2021),
        t(91363, "What If", 2021),
        t(88329, "Hawkeye", 2021),
        t(92749, "Moon Knight", 2022),
        t(92782, "Ms. Marvel", 2022),
        t(92783, "She-Hulk", 2022),
        t(114472, "Secret Invasion", 2023),
        t(122226, "Echo", 2024),
        t(138501, "Agatha All Along", 2024),
        t(202555, "Born Again", 2025),
        t(114471, "Ironheart", 2025),
        t(1403, "Agents of S.H.I.E.L.D.", 2013),
        t(61550, "Agent Carter", 2015),
        t(61889, "Daredevil", 2015),
        t(38472, "Jessica Jones", 2015),
        t(62126, "Luke Cage", 2016),
        t(62127, "Iron Fist", 2017),
        t(62285, "The Defenders", 2017),
        t(67178, "The Punisher", 2017),
    )

    /** Marvel's heroes outside the MCU: the X-Men films, Deadpool, the earlier Fantastic Four and others. */
    val BEYOND_THE_MCU = listOf(
        t(36657, "X-Men", 2000),
        t(36658, "X2", 2003),
        t(36668, "The Last Stand", 2006),
        t(2080, "Wolverine", 2009),
        t(49538, "First Class", 2011),
        t(76170, "The Wolverine", 2013),
        t(127585, "Days of Future Past", 2014),
        t(293660, "Deadpool", 2016),
        t(246655, "Apocalypse", 2016),
        t(263115, "Logan", 2017),
        t(383498, "Deadpool 2", 2018),
        t(320288, "Dark Phoenix", 2019),
        t(340102, "The New Mutants", 2020),
        t(9738, "Fantastic Four", 2005),
        t(1979, "Rise of the Silver Surfer", 2007),
        t(166424, "Fantastic Four", 2015),
        t(9480, "Daredevil", 2003),
        t(9947, "Elektra", 2005),
        t(177572, "Big Hero 6", 2014),
    )

    /** Pixar's feature films, in release order. */
    val PIXAR_FEATURES = listOf(
        t(862, "Toy Story", 1995),
        t(9487, "A Bug's Life", 1998),
        t(863, "Toy Story 2", 1999),
        t(585, "Monsters, Inc.", 2001),
        t(12, "Finding Nemo", 2003),
        t(9806, "The Incredibles", 2004),
        t(920, "Cars", 2006),
        t(2062, "Ratatouille", 2007),
        t(10681, "WALL·E", 2008),
        t(14160, "Up", 2009),
        t(10193, "Toy Story 3", 2010),
        t(49013, "Cars 2", 2011),
        t(62177, "Brave", 2012),
        t(62211, "Monsters University", 2013),
        t(150540, "Inside Out", 2015),
        t(105864, "The Good Dinosaur", 2015),
        t(127380, "Finding Dory", 2016),
        t(260514, "Cars 3", 2017),
        t(354912, "Coco", 2017),
        t(260513, "Incredibles 2", 2018),
        t(301528, "Toy Story 4", 2019),
        t(508439, "Onward", 2020),
        t(508442, "Soul", 2020),
        t(508943, "Luca", 2021),
        t(508947, "Turning Red", 2022),
        t(718789, "Lightyear", 2022),
        t(976573, "Elemental", 2023),
        t(1022789, "Inside Out 2", 2024),
        t(1022787, "Elio", 2025),
    )
}

/**
 * A chosen title as TMDB has it ([Source.Curated]): read from its details with where it can be watched, checked
 * against the list's entry, and kept (pure, unit-tested).
 */
object Curated {

    /** The parts appended to a chosen title's details call. */
    const val APPEND = "watch/providers,external_ids"

    /** How many of a list's titles are asked for at a time. */
    const val SIDE_BY_SIDE = 8

    /** A title, its IMDb id when TMDB has one, and whether the provider carries it with a US subscription ([on]). */
    class Entry(val item: TmdbDetails.Item, val imdb: String?, val on: Boolean)

    fun key(kind: Kind, provider: Int, tmdbId: Int): String = "tmdb|curated|v1|${kind.type}|$provider|$tmdbId"

    /** TMDB's details answer for one title; null when it holds no title. */
    fun parse(text: String, kind: Kind, provider: Int): Entry? {
        val o = JSONObject(text)
        val item = TmdbDetails.items(JSONObject().put("results", JSONArray().put(o)), kind).firstOrNull() ?: return null
        val imdb = (o.optJSONObject("external_ids")?.optString("imdb_id").orEmpty().ifEmpty { o.optString("imdb_id") })
            .takeIf { it.startsWith("tt") }
        val us = o.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject("US")?.optJSONArray("flatrate")
        val on = us != null && (0 until us.length()).any { us.optJSONObject(it)?.optInt("provider_id", 0) == provider }
        return Entry(item.copy(kind = kind), imdb, on)
    }

    /**
     * Whether TMDB's [item] is the title [pick] means: its name holds the entry's words (letters and digits compared,
     * whatever the case or punctuation) and it came out within a year of the entry's.
     */
    fun matches(pick: CuratedTitle, item: TmdbDetails.Item): Boolean {
        val year = item.year?.toIntOrNull() ?: return false
        return abs(year - pick.year) <= 1 && plain(item.name).contains(plain(pick.name))
    }

    private fun plain(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    fun write(e: Entry): JSONObject =
        JSONObject().put("items", TmdbDetails.writeItems(listOf(e.item))).put("imdb", e.imdb ?: "").put("on", e.on)

    fun read(o: JSONObject): Entry? {
        val item = TmdbDetails.readItems(o.optJSONArray("items")).firstOrNull() ?: return null
        return Entry(item, o.optString("imdb").takeIf { it.startsWith("tt") }, o.optBoolean("on", false))
    }
}
