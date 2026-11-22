package app.fireplayer.vod.data

import app.fireplayer.vod.Card
import app.fireplayer.vod.Kind
import app.fireplayer.vod.RowSpec
import app.fireplayer.vod.RowStyle
import app.fireplayer.vod.Source
import app.fireplayer.vod.Title

/**
 * A streaming service with a page of its own: its name, TMDB's watch-provider id for it (US subscriptions) and TMDB's
 * wordmark for its page's name line ([logo], a path; [mono]: a one-colour mark, drawn white). Its card's logo is the
 * app's own picture. [withAds]: TMDB's id for the service's with-ads tier, whose titles its page shows too. Declared
 * in Home's default order.
 */
enum class Service(val key: String, val label: String, val providerId: Int, val logo: String, val mono: Boolean, val withAds: Int? = null) {
    DISNEY("disney", "Disney+", 337, "/1edZOYAfoyZyZ3rklNSiUpXX30Q.png", true),
    HULU("hulu", "Hulu", 15, "/pqUTCleNUiTLAVlelGxUgWn1ELh.png", false),
    NETFLIX("netflix", "Netflix", 8, "/wwemzKWzjKYJFfCeiB57q3r4Bcm.png", false),
    HBO_MAX("hbo_max", "HBO Max", 1899, "/nmU0UMDJB3dRRQSTUqawzF2Od1a.png", true),
    APPLE_TV("apple_tv", "Apple TV", 350, "/4KAy34EHvRM25Ih8wb82AuGU7zJ.png", true),
    PRIME_VIDEO("prime_video", "Prime Video", 9, "/ifhbNuuVnlwYy5oXA5VIb2YR8AZ.png", false, withAds = 2100),
}

/**
 * A Disney+ brand and its collection: TMDB's production [companies] for its films and series (a series by its
 * [networks] instead when given), and TMDB's wordmark ([logo]; none: the card shows the name).
 */
enum class Brand(val key: String, val label: String, val companies: String, val networks: String?, val logo: String?, val mono: Boolean) {
    DISNEY("disney", "Disney", "2", null, "/wdrCwmRnLFJhEoH8GSfymY85KHT.png", true),
    PIXAR("pixar", "Pixar", "3", null, "/1TjvGVDMYsj6JBxOAkUHpPEwLf7.png", true),
    MARVEL("marvel", "Marvel", "420", null, "/837VMM4wOkODc1idNxGT0KQJlej.png", false),
    STAR_WARS("star_wars", "Star Wars", "1", null, null, false),
    NAT_GEO("nat_geo", "National Geographic", "7521", "43", "/q9rPBG1rHbUjII1Qn98VG2v7cFa.png", true),
}

/**
 * The services' cards, rows and collections, their order on Home, and the rail's service shortcuts: the services
 * pinned there and the ones visited last (pure, unit-tested).
 */
object Services {

    /** How many service shortcuts the rail has, and how many recently visited services are kept for them. */
    const val RECENT = 2

    private const val SERVICE_ID = "service:"
    private const val BRAND_ID = "brand:"
    private const val LOGOS = "${VodArt.TMDB_IMAGES}/w300"

    fun logoUrl(path: String?): String? = path?.let { LOGOS + it }

    /** Whether the wordmark at [url] is one colour (drawn white). */
    fun isMono(url: String?): Boolean = url != null && url in MONO

    private val MONO: Set<String> =
        (Service.entries.filter { it.mono }.map { it.logo } + Brand.entries.filter { it.mono }.mapNotNull { it.logo }).map { LOGOS + it }.toSet()

    /**
     * Home's row of services in [order] (the viewer's: [Services.order]): a card each, its logo one of the app's own
     * pictures (nothing is fetched for it).
     */
    fun cards(order: List<Service> = Service.entries): List<Card> = order.map { logoCard(SERVICE_ID + it.key, it.label) }

    /** Disney+'s row of studios: a card each, its logo likewise the app's own. */
    fun brandCards(): List<Card> = Brand.entries.map { logoCard(BRAND_ID + it.key, it.label) }

    private fun logoCard(id: String, name: String) = Card(Title(id, Kind.MOVIE, name))

    /** The service a card of [cards] stands for. */
    fun serviceOf(id: String): Service? =
        if (id.startsWith(SERVICE_ID)) Service.entries.firstOrNull { it.key == id.substring(SERVICE_ID.length) } else null

    /** The brand a card of [brandCards] stands for. */
    fun brandOf(id: String): Brand? =
        if (id.startsWith(BRAND_ID)) Brand.entries.firstOrNull { it.key == id.substring(BRAND_ID.length) } else null

    /** Home's row of the services, as Settings › Rows lists it: a card per service, each in its own slot. */
    fun homeRow(): RowSpec = RowSpec("home.services", "Streaming Services", RowStyle.SERVICES, Source.Services)

    /** A service page's rows: Disney+'s brands, then what's popular on the service (films, series). */
    fun rows(service: Service): List<RowSpec> = listOfNotNull(
        RowSpec("service.${service.key}.brands", "Brands", RowStyle.LOGO, Source.Brands).takeIf { service == Service.DISNEY },
        RowSpec("service.${service.key}.movies", "Popular Movies", RowStyle.POSTER, Source.Discover(Kind.MOVIE, service.providerId, alsoProvider = service.withAds)),
        RowSpec("service.${service.key}.shows", "Popular Shows", RowStyle.POSTER, Source.Discover(Kind.SERIES, service.providerId, alsoProvider = service.withAds)),
    )

    /** A brand's whole collection on Disney+ in the US, films and series in turn, page after page. */
    fun collection(brand: Brand): RowSpec = RowSpec(
        "brand.${brand.key}", brand.label, RowStyle.POSTER,
        Source.Discover(null, Service.DISNEY.providerId, brand.companies, brand.networks, full = true),
    )

    /** The recently visited services after [visited] was opened: it goes first, once; the newest [max] are kept. */
    fun visited(recent: List<Service>, visited: Service, max: Int = RECENT): List<Service> =
        (listOf(visited) + recent.filter { it != visited }).take(max)

    fun encode(recent: List<Service>): String = recent.joinToString(",") { it.key }

    /** What was stored, newest first: unknown names and repeats left out. */
    fun decode(text: String?, max: Int = RECENT): List<Service> =
        text.orEmpty().split(',').mapNotNull { k -> Service.entries.firstOrNull { it.key == k } }.distinct().take(max)

    // ---- Home's order ---------------------------------------------------------------------------------------------

    /**
     * Home's services in the order the viewer stored ([encode]; nothing stored: the default, [Service]'s own), every
     * service once: unknown names and repeats left out, and a service the stored order doesn't name (one added to the
     * app since it was stored) after the others, in its default order among any such.
     */
    fun order(text: String?): List<Service> {
        val stored = decode(text, Service.entries.size)
        return stored + Service.entries.filter { it !in stored }
    }

    // ---- The rail's service shortcuts -----------------------------------------------------------------------------

    /**
     * What the rail's [RECENT] service shortcuts show, top first (null: that slot is empty), when they're [on]
     * (switched off: none). The services [pinned] (the viewer's, in slot order: [pin]) keep their slots; the slots
     * left show the services visited last ([recent], newest first) that aren't pinned, newest first. A slot nothing
     * can fill yet stays empty.
     */
    fun railSlots(on: Boolean, pinned: List<Service>, recent: List<Service>): List<Service?> {
        if (!on) return emptyList()
        val pins = pinned.distinct().take(RECENT)
        val rest = recent.distinct().filter { it !in pins }
        return List(RECENT) { i -> pins.getOrNull(i) ?: rest.getOrNull(i - pins.size) }
    }

    /**
     * The pinned services after [service] was chosen for shortcut [slot] (0 the top one; null: nothing pinned there,
     * the most recent instead). Each service is pinned once: one already in the other slot changes places with what
     * was there. The pins close up, so one pinned alone is always in the top slot.
     */
    fun pin(pinned: List<Service>, slot: Int, service: Service?): List<Service> {
        val pins = pinned.distinct().take(RECENT)
        if (slot !in 0 until RECENT) return pins
        val slots = MutableList(RECENT) { pins.getOrNull(it) }
        val other = if (service == null) -1 else slots.indexOf(service)
        if (other >= 0 && other != slot) slots[other] = slots[slot]
        slots[slot] = service
        return slots.filterNotNull()
    }
}

/**
 * A Home service card's small window (OK held on it): Move, then the one pin choice that fits the rail's two service
 * shortcuts as they are: pin it (the first shortcut, or the second when the first is taken), replace either when both
 * are taken, or unpin it when it's pinned. Pinning switches the shortcuts on (Show streaming services on rail). Pure,
 * unit-tested.
 */
object ServiceHold {

    enum class Option(val label: String) {
        MOVE("Move"),
        PIN("Pin to rail"),
        PIN_SECOND("Pin as second shortcut"),
        REPLACE_FIRST("Replace first shortcut"),
        REPLACE_SECOND("Replace second shortcut"),
        UNPIN("Unpin from rail"),
    }

    /**
     * The options for [service]'s card, top first, with [pinned] the services pinned now (top first) and the shortcuts
     * [on] or not. Pinned while they're off, Pin to rail shows them again.
     */
    fun options(on: Boolean, pinned: List<Service>, service: Service): List<Option> {
        val pins = pins(pinned)
        val pin = when {
            service in pins -> listOf(if (on) Option.UNPIN else Option.PIN)
            pins.isEmpty() -> listOf(Option.PIN)
            pins.size == 1 -> listOf(Option.PIN_SECOND)
            else -> listOf(Option.REPLACE_FIRST, Option.REPLACE_SECOND)
        }
        return listOf(Option.MOVE) + pin
    }

    /** What's pinned, top first, after [option] on [service]'s card ([Services.pin]: each service pinned once). */
    fun pinsAfter(option: Option, pinned: List<Service>, service: Service): List<Service> {
        val pins = pins(pinned)
        return when (option) {
            Option.MOVE -> pins
            Option.PIN, Option.PIN_SECOND -> if (service in pins) pins else Services.pin(pins, minOf(pins.size, Services.RECENT - 1), service)
            Option.REPLACE_FIRST -> Services.pin(pins, 0, service)
            Option.REPLACE_SECOND -> Services.pin(pins, 1, service)
            Option.UNPIN -> pins - service
        }
    }

    /** Whether the rail shows its service shortcuts after [option] ([on]: before it): pinning shows them. */
    fun onAfter(option: Option, on: Boolean): Boolean = on || option != Option.MOVE && option != Option.UNPIN

    /** An option's quieter second line: what a replace option replaces ("Replaces Netflix"); none for the others. */
    fun detail(option: Option, pinned: List<Service>): String? {
        val slot = when (option) {
            Option.REPLACE_FIRST -> 0
            Option.REPLACE_SECOND -> 1
            else -> return null
        }
        return pins(pinned).getOrNull(slot)?.let { "Replaces ${it.label}" }
    }

    private fun pins(pinned: List<Service>) = pinned.distinct().take(Services.RECENT)
}

/**
 * TMDB Discover for the titles a US subscription service carries ([Source.Discover]): the query, which of TMDB's
 * pages a request needs, and the titles of an answer (pure, unit-tested).
 */
object Discover {

    /** TMDB's page size. */
    const val PAGE = 20

    /** How long a page is fresh (a service's catalogue and what's popular on it move slowly). */
    const val FRESH_MS = 12 * 3600_000L

    class Page(val items: List<TmdbDetails.Item>, val last: Boolean)

    fun path(kind: Kind): String = if (kind == Kind.SERIES) "discover/tv" else "discover/movie"

    /** The query for [page] (from 1) of [kind]: on the provider with a US subscription, the most popular first. */
    fun params(s: Source.Discover, kind: Kind, page: Int): List<Pair<String, String>> = listOfNotNull(
        "language" to "en-US",
        "include_adult" to "false",
        "sort_by" to "popularity.desc",
        "watch_region" to "US",
        // Either provider when there are two: TMDB reads "|" as or.
        "with_watch_providers" to (s.alsoProvider?.let { "${s.provider}|$it" } ?: s.provider.toString()),
        "with_watch_monetization_types" to "flatrate",
        if (kind == Kind.SERIES && s.networks != null) "with_networks" to s.networks else s.companies?.let { "with_companies" to it },
        s.genres?.let { "with_genres" to it },
        s.withoutGenres?.let { "without_genres" to it },
        s.maxRuntime?.let { "with_runtime.lte" to it.toString() },
        "page" to page.toString(),
    )

    /** Where a page is kept. */
    fun key(s: Source.Discover, kind: Kind, page: Int): String {
        val narrowed = if (s.genres == null && s.withoutGenres == null && s.maxRuntime == null) ""
        else "g${s.genres.orEmpty()}x${s.withoutGenres.orEmpty()}r${s.maxRuntime ?: ""}|"
        val providers = s.alsoProvider?.let { "${s.provider}+$it" } ?: s.provider.toString()
        return "tmdb|discover|v1|${kind.type}|$providers|${s.companies.orEmpty()}|${s.networks.orEmpty()}|$narrowed$page"
    }

    /** TMDB's answer for [page] of [kind]. */
    fun parse(text: String, kind: Kind, page: Int): Page {
        val o = org.json.JSONObject(text)
        return Page(TmdbDetails.items(o, kind), page >= o.optInt("total_pages", 0))
    }

    /** TMDB's pages (from 1) holding positions [skip] until [skip] + [limit], with [kinds] lists taken in turn. */
    fun pages(skip: Int, limit: Int, kinds: Int): IntRange {
        if (limit <= 0) return IntRange.EMPTY
        val span = PAGE * kinds
        val from = skip.coerceAtLeast(0)
        return from / span + 1..(from + limit - 1) / span + 1
    }

    /**
     * The titles at positions [skip] until [skip] + [limit]: each of [pages] is its kinds' lists taken in turn (film
     * first), at positions from (page - 1) x 20 x kinds. [got] gives a kind's page, null when there is none.
     */
    fun slice(pages: IntRange, kinds: List<Kind>, skip: Int, limit: Int, got: (Kind, Int) -> List<TmdbDetails.Item>?): List<TmdbDetails.Item> {
        val span = PAGE * kinds.size
        val from = skip.coerceAtLeast(0)
        val out = ArrayList<TmdbDetails.Item>()
        for (p in pages) {
            val lists = kinds.map { got(it, p).orEmpty() }
            val mixed = if (lists.size == 1) lists[0] else TmdbTrending.interleave(lists[0], lists[1], span)
            mixed.forEachIndexed { i, item ->
                val at = (p - 1) * span + i
                if (at >= from && at < from.toLong() + limit) out += item
            }
        }
        return out
    }
}
