package app.fireplayer.vod.data

import android.util.Log
import app.fireplayer.subs.Tmdb
import app.fireplayer.vod.Cancel
import app.fireplayer.vod.Card
import app.fireplayer.vod.CuratedTitle
import app.fireplayer.vod.Episode
import app.fireplayer.vod.EpisodeRef
import app.fireplayer.vod.HeaderFeed
import app.fireplayer.vod.Kind
import app.fireplayer.vod.LastPlayed
import app.fireplayer.vod.Page
import app.fireplayer.vod.Position
import app.fireplayer.vod.RowSpec
import app.fireplayer.vod.Source
import app.fireplayer.vod.Title
import app.fireplayer.vod.TitleDetails
import app.fireplayer.vod.VodRepository
import app.fireplayer.vod.hasImdbId
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicLong

/**
 * Films & Shows' data: rows from Cinemeta, Top 10 from TMDB's trending lists with the user's key, and the library kept
 * on this TV ([VodLibrary]). Answers come from the cache on disk at once and are refreshed in the background (Cinemeta
 * pages about 4 h, the manifest and metas a day, Top 10 6 h).
 *
 * The title page (step 2): a title's details from Cinemeta's meta (kept about 4 h) and, with a usable key, one TMDB
 * details call (kept about 24 h) whose appended lists also make More Like This; without a key More Like This comes from
 * Cinemeta (a film's director, the main genre). Stored answers come first, fresh ones after.
 *
 * Threads: [io] (one thread) reads the cache and the database and writes the database; [net] fetches; [lookups] runs
 * TMDB's id lookups and Cinemeta metas for a Top 10 side by side, and a title page's TMDB details beside its meta; every
 * callback goes through [main] and is dropped once its [Cancel] is called. The same page, list, meta or details asked
 * for by several at once is fetched once.
 *
 * Privacy: no address is logged (TMDB's carries the key), only what kind of failure it was.
 */
class DefaultVodRepository(
    private val http: Http,
    private val tmdb: TmdbSource,
    private val tmdbKey: () -> String,
    private val cache: JsonCache,
    idStore: IdStore,
    libraryStore: LibraryStore,
    private val main: Executor,
    private val io: Executor,
    private val net: Executor,
    private val lookups: Executor,
    private val clock: () -> Long = System::currentTimeMillis,
    rowStore: RowLayoutStore = RowLayoutStore.Memory(),
) : VodRepository {

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** [libraryVersion]: one up per library change. */
    private val libraryVersion = AtomicLong()

    /** Shows whose meta Continue Watching has asked for while the app runs ([showEpisodes]): once each. */
    private val metaAsked: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * The library; also for later steps (playback records where it stopped with [VodLibrary.recordProgress]). A show's
     * place in Continue Watching follows its stored episodes ([showEpisodes]).
     */
    val library = VodLibrary(libraryStore, clock, io, { showEpisodes(it) }) { libraryChanged() }

    /** Settings › Rows: each page's rows as the viewer set them (main thread). */
    val rowSettings = RowSettings(rowStore) { newYear(it) }

    private val ids = IdMap(idStore, clock, io)
    private val pages = Lru<String, Stored<Cinemeta.Page>>(PAGES_KEPT)
    private val byId = Lru<String, Title>(TITLES_KEPT)
    private val byTmdb = Lru<String, Title>(TITLES_KEPT)
    private val top10 = ConcurrentHashMap<Kind, Stored<List<Card>>>()
    /** The Search tab's answers by address, in memory only. */
    private val found = Lru<String, Stored<List<Title>>>(SEARCHES_KEPT)
    private val metas = Lru<String, Stored<CinemetaDetails.Meta>>(DETAILS_KEPT)
    private val tmdbDetails = Lru<String, Stored<TmdbDetails.Extras?>>(DETAILS_KEPT)
    private val running = ConcurrentHashMap<String, FutureTask<*>>()
    @Volatile private var manifest: Stored<Cinemeta.Manifest>? = null
    @Volatile private var refusedKey: String? = null
    @Volatile private var tmdbPausedUntil = 0L

    init {
        io.execute {
            cache.read(REFUSED)?.takeIf { it.isFresh(clock()) }?.let { refusedKey = it.data.optString("key").ifEmpty { null } }
            storedManifest()
            cache.trim()
        }
    }

    // ---- Rows ----

    override fun rows(page: Page): List<RowSpec> {
        val m = manifest
        if (m == null || !m.isFresh(clock())) refreshManifest()
        return rowSettings.rows(page)
    }

    override fun addRowsListener(listener: () -> Unit) = rowSettings.addListener(listener)

    override fun removeRowsListener(listener: () -> Unit) = rowSettings.removeListener(listener)

    override fun setServiceOrder(order: List<Service>) = rowSettings.setServiceOrder(order)

    override fun load(row: RowSpec, skip: Int, limit: Int, onCards: (cards: List<Card>, fresh: Boolean) -> Unit): Cancel {
        val req = Req()
        val sink: (List<Card>, Boolean) -> Unit = { cards, fresh -> post(req) { onCards(cards, fresh) } }
        when (val s = row.source) {
            is Source.Catalog -> catalogCards(resolve(s), skip, limit, req) { titles, fresh -> sink(titles.map { Card(it) }, fresh) }
            is Source.Trending -> s.kind?.let { trendingCards(it, skip, limit, req, sink) } ?: trendingBoth(skip, limit, req, sink)
            is Source.ContinueWatching -> local(req, skip, limit, sink) { library.continueWatching(s.kind) }
            Source.MyList -> local(req, skip, limit, sink) { library.myList().map { Card(it) } }
            is Source.MyListOf -> local(req, skip, limit, sink) { library.myList().filter { it.kind == s.kind }.map { Card(it) } }
            is Source.Search -> searchCards(s, skip, limit, req, sink)
            is Source.NextEpisodes -> local(req, skip, limit, sink) { nextEpisodes() }
            // Chips come from genres().
            is Source.Genres -> local(req, skip, limit, sink) { emptyList() }
            // The title page fills these rows itself (details(), moreLikeThis()).
            is Source.Fixed -> local(req, skip, limit, sink) { emptyList() }
            is Source.Discover -> discoverCards(s, skip, limit, req, sink)
            is Source.Curated -> curatedCards(s, skip, limit, req, sink)
            // In the viewer's order, read here (main thread) for the disk thread.
            Source.Services -> rowSettings.serviceOrder().let { order -> local(req, skip, limit, sink) { Services.cards(order) } }
            Source.Brands -> local(req, skip, limit, sink) { Services.brandCards() }
        }
        return req
    }

    override fun genres(kind: Kind, onGenres: (List<String>) -> Unit): Cancel {
        val req = Req()
        io.execute {
            if (req.cancelled) return@execute
            val stored = storedManifest()
            val first = Cinemeta.genres(stored?.value, kind)
            post(req) { onGenres(first) }
            if (stored != null && stored.isFresh(clock())) return@execute
            net.execute {
                if (req.cancelled) return@execute
                val list = Cinemeta.genres(fetchManifest()?.value ?: return@execute, kind)
                if (list != first) post(req) { onGenres(list) }
            }
        }
        return req
    }

    override fun header(page: Page, onFeed: (HeaderFeed) -> Unit): Cancel {
        val req = Req()
        val kinds = when (page) {
            Page.HOME -> listOf(Kind.MOVIE, Kind.SERIES)
            Page.MOVIES -> listOf(Kind.MOVIE)
            Page.SHOWS -> listOf(Kind.SERIES)
        }
        val mix = HeaderMix(kinds) { feed -> post(req) { onFeed(feed) } }
        for (kind in kinds) headerTitles(kind, req) { titles, fresh -> mix.update(kind, titles, fresh) }
        return req
    }

    override fun hasTmdbKey(): Boolean = usableKey()

    // ---- Library ----

    override fun inMyList(id: String) = library.inMyList(id)
    override fun setInMyList(title: Title, inList: Boolean) = library.setInMyList(title, inList)
    override fun isWatched(id: String) = library.isWatched(id)
    override fun setWatched(title: Title, watched: Boolean) = library.setWatched(title, watched)
    override fun removeFromContinueWatching(id: String) = library.removeFromContinueWatching(id)

    override fun addLibraryListener(listener: () -> Unit) {
        listeners += listener
    }

    override fun removeLibraryListener(listener: () -> Unit) {
        listeners -= listener
    }

    override fun libraryVersion(): Long = libraryVersion.get()

    override fun continueCard(title: Title): Card? = library.continueCard(title.kind, title.id)

    // ---- Title page (step 2) ----

    override fun details(title: Title, onDetails: (details: TitleDetails, fresh: Boolean) -> Unit): Cancel {
        val req = Req()
        io.execute {
            if (req.cancelled) return@execute
            val kind = title.kind
            val meta = storedMeta(kind, title.id)
            val key = usableKey()
            val knownTmdb = title.tmdbId ?: meta?.value?.title?.tmdbId ?: if (key) tmdbIdOf(kind, title.id, fetch = false) else null
            val extras = if (key && knownTmdb != null) storedExtras(kind, knownTmdb) else null
            // Chosen once for the visit, so the backdrop never changes: TMDB's (smaller) when its details are stored.
            val background = extras?.value?.backdrop?.let(VodArt::tmdbBackdrop) ?: title.background
                ?: meta?.value?.title?.background ?: VodArt.background(title.id)
            val now = clock()
            // A More Like This stand-in has no meta to ask for (the page resolves it first; TMDB's id still works).
            val metaFresh = !title.hasImdbId || (meta != null && meta.isFresh(now))
            val tmdbSettled = !key || now < tmdbPausedUntil || (extras != null && extras.isFresh(now))
            val job = DetailsJob(title, background, req, onDetails)
            job.start(meta?.value, extras?.value, metaPending = !metaFresh, tmdbPending = !tmdbSettled)
            if (!metaFresh) {
                net.execute {
                    val got = if (req.cancelled) null else fetchMeta(kind, title.id)
                    job.meta(got?.value)
                    if (!tmdbSettled && knownTmdb == null) {
                        // TMDB waited for the meta's TMDB id (or, without one, TMDB's find).
                        val id = if (req.cancelled) null else (got ?: meta)?.value?.title?.tmdbId ?: tmdbIdOf(kind, title.id, fetch = true)
                        job.tmdb(id?.let { tmdbFor(kind, it, req) })
                    }
                }
            } else if (!tmdbSettled && knownTmdb == null) {
                lookups.execute {
                    val id = if (req.cancelled) null else tmdbIdOf(kind, title.id, fetch = true)
                    job.tmdb(id?.let { tmdbFor(kind, it, req) })
                }
            }
            if (!tmdbSettled && knownTmdb != null) lookups.execute { job.tmdb(tmdbFor(kind, knownTmdb, req)) }
        }
        return req
    }

    /**
     * One [details] request: the parts known so far, passed on (merged) each time one arrives; the last call fresh once
     * nothing is pending. A part that couldn't be fetched (null) keeps what was there.
     */
    private inner class DetailsJob(
        private val asked: Title,
        private val background: String,
        private val req: Req,
        private val onDetails: (TitleDetails, Boolean) -> Unit,
    ) {
        private var meta: CinemetaDetails.Meta? = null
        private var extras: TmdbDetails.Extras? = null
        private var metaPending = false
        private var tmdbPending = false
        private var sent: TitleDetails? = null
        private var done = false

        @Synchronized
        fun start(meta: CinemetaDetails.Meta?, extras: TmdbDetails.Extras?, metaPending: Boolean, tmdbPending: Boolean) {
            this.meta = meta
            this.extras = extras
            this.metaPending = metaPending
            this.tmdbPending = tmdbPending
            update()
        }

        @Synchronized
        fun meta(got: CinemetaDetails.Meta?) {
            metaPending = false
            if (got != null) meta = got
            update()
        }

        @Synchronized
        fun tmdb(got: TmdbDetails.Extras?) {
            tmdbPending = false
            if (got != null) extras = got
            update()
        }

        private fun update() {
            if (done || req.cancelled) return
            val fresh = !metaPending && !tmdbPending
            // Nothing stored: wait for the first part (with nothing at all, the one call is the title alone).
            if (!fresh && meta == null && extras == null) return
            val details = DetailsMerge.compose(asked, meta, extras, background, top10Rank(asked.kind, asked.id))
            if (!fresh && details == sent) return
            sent = details
            if (fresh) done = true
            post(req) { onDetails(details, fresh) }
        }
    }

    override fun moreLikeThis(title: Title, onCards: (cards: List<Card>, fresh: Boolean) -> Unit): Cancel {
        val req = Req()
        io.execute {
            if (req.cancelled) return@execute
            val stored = Parts(fetch = false, req)
            val first = similarCards(title, stored)
            if (stored.allFresh) {
                post(req) { onCards(first, true) }
                return@execute
            }
            // Built from stored parts that are only old: shown at once. With a part never stored (a first visit: no meta,
            // no TMDB details), the fresh answer would replace it (the director's films first, TMDB's picks): it waits.
            if (first.isNotEmpty() && !stored.missing) post(req) { onCards(first, false) }
            net.execute {
                if (req.cancelled) return@execute
                val cards = similarCards(title, Parts(fetch = true, req))
                post(req) { onCards(cards, true) }
            }
        }
        return req
    }

    /** More Like This from the parts [parts] gives (stored only, or fetched when old or missing). */
    private fun similarCards(title: Title, parts: Parts): List<Card> {
        val kind = title.kind
        var metaAsked = false
        var meta: CinemetaDetails.Meta? = null
        val metaOf = {
            if (!metaAsked) {
                metaAsked = true
                if (title.hasImdbId) meta = parts.get(storedMeta(kind, title.id)) { fetchMeta(kind, title.id) }
            }
            meta
        }
        var tmdbCards = emptyList<Card>()
        if (usableKey()) {
            val tmdbId = title.tmdbId ?: metaOf()?.title?.tmdbId
                ?: if (title.hasImdbId) tmdbIdOf(kind, title.id, parts.fetch) else null
            val extras = tmdbId?.let { id -> parts.get(storedExtras(kind, id)) { fetchExtras(kind, id) } }
            if (extras != null) {
                tmdbCards = MoreLikeThis.fromTmdb(extras, title.id, Dates.isoDay(clock()), ::knownImdb)
                    .map { pick -> MoreLikeThis.card(pick, pick.imdb?.let { byId["${pick.item.kind.type}|$it"] }) }
            }
        }
        if (tmdbCards.size >= MoreLikeThis.FEW || parts.cancelled) return MoreLikeThis.join(title, tmdbCards, emptyList())
        return MoreLikeThis.join(title, tmdbCards, cinemetaSimilar(title, metaOf(), tmdbCards, parts))
    }

    /**
     * Cinemeta's More Like This (no key, TMDB failing or giving few): a film's director's films, then the main
     * genre's Popular ranked by genres shared.
     */
    private fun cinemetaSimilar(title: Title, meta: CinemetaDetails.Meta?, already: List<Card>, parts: Parts): List<Card> {
        val kind = title.kind
        val out = ArrayList<Title>()
        if (kind == Kind.MOVIE) {
            meta?.directors?.firstOrNull()?.let { director ->
                val url = Cinemeta.searchUrl(Kind.MOVIE, director)
                val found = parts.get(storedPage(url, learn = false)) { fetchSearch(url) }?.titles.orEmpty()
                out += MoreLikeThis.byDirector(found, director, title.id, currentYear())
            }
        }
        val genres = (meta?.title?.genres ?: title.genres).ifEmpty { title.genres }
        genres.firstOrNull()?.let { genre ->
            val q = Query(kind, Cinemeta.TOP, genre)
            val page = parts.get(storedPage(q.url(0))) { fetchPage(q, 0) }
            val exclude = HashSet<String>().apply {
                add(title.id)
                already.forEach { add(it.title.id) }
                out.forEach { add(it.id) }
            }
            out += MoreLikeThis.byGenre(page?.titles.orEmpty(), genres, exclude, MoreLikeThis.MAX - already.size - out.size)
        }
        return out.map { Card(it) }
    }

    /**
     * What a lookup uses: stored parts, fresh or not ([fetch] false: [allFresh] says whether all were, [missing] whether
     * one was never stored), or fetched when old or missing ([fetch] true; a failed fetch keeps the stored copy).
     */
    private inner class Parts(val fetch: Boolean, private val req: Req) {
        var allFresh = true
            private set
        var missing = false
            private set
        val cancelled: Boolean get() = req.cancelled

        fun <T> get(stored: Stored<T>?, fetcher: () -> Stored<T>?): T? {
            if (stored != null && stored.isFresh(clock())) return stored.value
            if (!fetch || req.cancelled) {
                allFresh = false
                if (stored == null) missing = true
                return stored?.value
            }
            return (fetcher() ?: stored)?.value
        }
    }

    override fun resolve(title: Title, onTitle: (Title?) -> Unit): Cancel {
        val req = Req()
        if (title.hasImdbId) {
            post(req) { onTitle(title) }
            return req
        }
        val kind = title.kind
        val tmdbId = title.tmdbId ?: MoreLikeThis.tmdbIdOfStandIn(title.id)
        if (tmdbId == null) {
            post(req) { onTitle(null) }
            return req
        }
        io.execute {
            if (req.cancelled) return@execute
            when (val k = knownImdb(kind, tmdbId)) {
                is TmdbTrending.Known.Hit -> return@execute post(req) { onTitle(resolved(title, k.imdb, tmdbId)) }
                TmdbTrending.Known.Miss -> return@execute post(req) { onTitle(null) }
                TmdbTrending.Known.Unknown -> Unit
            }
            val stored = storedExtras(kind, tmdbId)
            stored?.value?.imdbId?.let { tt -> return@execute post(req) { onTitle(resolved(title, tt, tmdbId)) } }
            // TMDB answered lately and had no IMDb id (or no such title).
            if (stored != null && stored.isFresh(clock())) return@execute post(req) { onTitle(null) }
            // The details call the opened page makes anyway: no extra request.
            net.execute {
                if (req.cancelled) return@execute
                val tt = fetchExtras(kind, tmdbId)?.value?.imdbId
                post(req) { onTitle(tt?.let { resolved(title, it, tmdbId) }) }
            }
        }
        return req
    }

    /**
     * A More Like This title with its IMDb id found: Cinemeta's when known here, else the card's with metahub's poster
     * and logo (one poster per IMDb id); the card's backdrop and TMDB id kept.
     */
    private fun resolved(card: Title, imdb: String, tmdbId: Int): Title {
        val base = byId["${card.kind.type}|$imdb"]
            ?: card.copy(id = imdb, poster = VodArt.poster(imdb), logo = VodArt.logo(imdb))
        return base.copy(
            background = card.background ?: base.background ?: VodArt.background(imdb),
            description = base.description ?: card.description,
            tmdbId = tmdbId,
        )
    }

    /** What's known here of a TMDB title's IMDb id: Cinemeta's index, then the id map (which may read the database). */
    private fun knownImdb(kind: Kind, tmdbId: Int): TmdbTrending.Known =
        byTmdb["${kind.type}#$tmdbId"]?.let { TmdbTrending.Known.Hit(it.id) } ?: ids.known(kind, tmdbId)

    override fun isEpisodeWatched(episode: Episode): Boolean = library.isEpisodeWatched(episode)

    override fun setEpisodesWatched(episodes: List<Episode>, watched: Boolean) = library.setEpisodesWatched(episodes, watched)

    override fun markWatchedUpTo(details: TitleDetails, upTo: Episode) =
        library.setEpisodesWatched(EpisodeMarks.upTo(details.episodes, upTo, clock()), true)

    override fun markUnwatchedUpTo(details: TitleDetails, upTo: Episode) {
        val episodes = EpisodeMarks.upTo(details.episodes, upTo, clock())
        if (episodes.isNotEmpty()) library.unwatchEpisodes(episodes, upTo)
    }

    override fun inContinueWatching(id: String): Boolean = library.inContinueWatching(id)

    override fun lastPlayed(title: Title): LastPlayed? = library.lastPlayed(title.kind, title.id)

    override fun upNext(title: Title): EpisodeRef? = library.upNext(title)

    override fun positionOf(episode: Episode): Position? = library.positionOf(episode)

    override fun recordProgress(title: Title, episode: EpisodeRef?, positionMs: Long, durationMs: Long) =
        library.recordProgress(title, episode, positionMs, durationMs)

    /**
     * Next Episodes (§6.2): shows caught up on that have a new episode since ([VodLibrary.nextEpisodes]), from the
     * shows' stored episodes; the latest played first. On [io].
     */
    private fun nextEpisodes(): List<Card> = library.nextEpisodes().map { (title, ep) ->
        Card(title = title, caption = "S${ep.season} E${ep.episode}", episode = ep.ref, picture = ep.still)
    }

    /**
     * A show's episodes for the library's Continue Watching: from its stored meta (in memory, else on disk), as the title
     * page stored it. None stored (never opened here, or trimmed away): null for now, and the meta is fetched once in
     * the background (once per show while the app runs), the library's listeners then called so the rows work out where
     * the show goes on. Any thread.
     */
    private fun showEpisodes(title: Title): List<Episode>? {
        storedMeta(Kind.SERIES, title.id)?.let { return it.value.episodes }
        if (title.hasImdbId && metaAsked.add(title.id)) {
            net.execute { if (fetchMeta(Kind.SERIES, title.id) != null) libraryChanged() }
        }
        return null
    }

    /** The version goes up at once, on the changing thread; the listeners are told on the main thread. */
    private fun libraryChanged() {
        libraryVersion.incrementAndGet()
        main.execute { listeners.forEach { it() } }
    }

    // ---- Details: Cinemeta's meta and TMDB's details, stored and fetched ----

    private fun storedMeta(kind: Kind, id: String): Stored<CinemetaDetails.Meta>? {
        val key = CinemetaDetails.key(kind, id)
        metas[key]?.let { return it }
        val entry = cache.read(key) ?: return null
        val meta = CinemetaDetails.read(entry.data) ?: return null
        return Stored(meta, entry.at, entry.freshMs).also { metas[key] = it }
    }

    /** A title's meta, fetched (once, however many ask meanwhile), stored trimmed; its ids learnt. Null on failure. */
    private fun fetchMeta(kind: Kind, id: String): Stored<CinemetaDetails.Meta>? {
        val key = CinemetaDetails.key(kind, id)
        return single(key) {
            try {
                val meta = CinemetaDetails.parse(http.get(Cinemeta.metaUrl(kind, id)), kind, id)
                if (meta == null) {
                    Log.w(TAG, "Cinemeta details: no meta")
                    return@single null
                }
                val now = clock()
                cache.write(key, CinemetaDetails.write(meta), CinemetaDetails.FRESH_MS, now)
                index(listOf(meta.title))
                ids.learn(listOf(meta.title))
                Stored(meta, now, CinemetaDetails.FRESH_MS).also { metas[key] = it }
            } catch (t: Throwable) {
                Log.w(TAG, "Cinemeta details not read: ${t.javaClass.simpleName}")
                null
            }
        }
    }

    /** TMDB's stored details; a stored null value: TMDB had no such title lately. */
    private fun storedExtras(kind: Kind, tmdbId: Int): Stored<TmdbDetails.Extras?>? {
        val key = TmdbDetails.key(kind, tmdbId)
        tmdbDetails[key]?.let { return it }
        val entry = cache.read(key) ?: return null
        val value = if (entry.data.optBoolean(MISSING)) null else TmdbDetails.read(entry.data) ?: return null
        return Stored(value, entry.at, entry.freshMs).also { tmdbDetails[key] = it }
    }

    /**
     * TMDB's details fetched (once, however many ask meanwhile) and stored trimmed; the IMDb id it gives is learnt. A
     * stored null value: TMDB has no such title. Null: not fetched (no usable key, paused after "too many requests",
     * or a failure, which [tmdbFailed] handles).
     */
    private fun fetchExtras(kind: Kind, tmdbId: Int): Stored<TmdbDetails.Extras?>? {
        if (!usableKey() || clock() < tmdbPausedUntil) return null
        val key = TmdbDetails.key(kind, tmdbId)
        return single(key) {
            try {
                val text = tmdb.details(kind, tmdbId)
                val now = clock()
                if (text == null) {
                    cache.write(key, JSONObject().put(MISSING, true), TmdbDetails.MISSING_MS, now)
                    return@single Stored<TmdbDetails.Extras?>(null, now, TmdbDetails.MISSING_MS).also { tmdbDetails[key] = it }
                }
                val got = TmdbDetails.parse(text, kind) ?: return@single null
                cache.write(key, TmdbDetails.write(got), TmdbDetails.FRESH_MS, now)
                when {
                    got.imdbId != null -> ids.found(kind, tmdbId, got.imdbId)
                    ids.known(kind, tmdbId) == TmdbTrending.Known.Unknown -> ids.missed(kind, tmdbId)
                }
                Stored<TmdbDetails.Extras?>(got, now, TmdbDetails.FRESH_MS).also { tmdbDetails[key] = it }
            } catch (t: Throwable) {
                tmdbFailed(t)
                null
            }
        }
    }

    /** TMDB's details for a [details] request: stored when still fresh, else fetched; null when neither. */
    private fun tmdbFor(kind: Kind, tmdbId: Int, req: Req): TmdbDetails.Extras? {
        val stored = storedExtras(kind, tmdbId)
        if (stored != null && stored.isFresh(clock())) return stored.value
        if (req.cancelled) return null
        return (fetchExtras(kind, tmdbId) ?: stored)?.value
    }

    /**
     * The TMDB id of a title whose meta has none (rare): TMDB's find by IMDb id, kept a month. Without [fetch], only
     * a stored answer.
     */
    private fun tmdbIdOf(kind: Kind, imdb: String, fetch: Boolean): Int? {
        if (!imdb.startsWith("tt")) return null
        val key = TmdbDetails.findKey(imdb)
        val stored = cache.read(key)
        if (stored != null && (stored.isFresh(clock()) || !fetch)) return stored.data.optInt(kind.type, 0).takeIf { it > 0 }
        if (!fetch || !usableKey() || clock() < tmdbPausedUntil) return null
        return single(key) {
            try {
                val text = tmdb.find(imdb)
                val found = Kind.entries.associateWith { TmdbDetails.idFromFind(text, it) }
                cache.write(key, JSONObject().apply { found.forEach { (k, v) -> put(k.type, v ?: 0) } }, TmdbDetails.FIND_FRESH_MS)
                found[kind]?.also { ids.found(kind, it, imdb) }
            } catch (t: Throwable) {
                tmdbFailed(t)
                null
            }
        }
    }

    /** A director search (More Like This without a key), fetched once however many ask, kept as Cinemeta says. */
    private fun fetchSearch(url: String): Stored<Cinemeta.Page>? = single(url) {
        try {
            val page = Cinemeta.parseSearch(http.get(url), Kind.MOVIE)
            val now = clock()
            cache.write(url, JSONObject().put("titles", TitleJson.writeTitles(page.titles)).put("hasMore", false), page.maxAgeMs, now)
            Stored(page, now, page.maxAgeMs).also { pages[url] = it }
        } catch (t: Throwable) {
            Log.w(TAG, "Cinemeta search not read: ${t.javaClass.simpleName}")
            null
        }
    }

    /**
     * The Search tab's titles for [s], one answer (fresh = true) to [sink] on a background thread: kept in memory
     * [Cinemeta.SEARCH_FRESH_MS] (a few minutes, never on disk), else fetched; a failed fetch gives the older answer, else
     * none. A request cancelled before its turn (the viewer typed on) is never sent.
     */
    private fun searchCards(s: Source.Search, skip: Int, limit: Int, req: Req, sink: (List<Card>, Boolean) -> Unit) {
        val query = s.query.trim()
        if (query.isEmpty()) {
            local(req, skip, limit, sink) { emptyList() }
            return
        }
        val url = Cinemeta.searchUrl(s.kind, query)
        val stored = found[url]
        if (stored != null && stored.isFresh(clock())) {
            local(req, skip, limit, sink) { stored.value.map { Card(it) } }
            return
        }
        net.execute {
            if (req.cancelled) return@execute
            val titles = fetchFound(url, s.kind)?.value ?: stored?.value.orEmpty()
            if (!req.cancelled) sink(titles.map { Card(it) }.window(skip, limit), true)
        }
    }

    /** A Search tab answer, fetched once however many ask; null on failure. Its titles aren't learned (they're thin). */
    private fun fetchFound(url: String, kind: Kind): Stored<List<Title>>? = single("found|$url") {
        try {
            val titles = Cinemeta.parseSearch(http.get(url), kind).titles
            Stored(titles, clock(), Cinemeta.SEARCH_FRESH_MS).also { found[url] = it }
        } catch (t: Throwable) {
            Log.w(TAG, "Cinemeta search not read: ${t.javaClass.simpleName}")
            null
        }
    }

    /**
     * This week's Top 10 place of [id] (1..10) from the stored list, kept for a day; null without a usable key or
     * outside it. Never stored with the details.
     */
    private fun top10Rank(kind: Kind, id: String): Int? {
        if (!usableKey()) return null
        val stored = storedTop10(kind) ?: return null
        if (clock() - stored.at !in 0 until RANK_KEPT_MS) return null
        return stored.value.firstOrNull { it.title.id == id }?.rank
    }

    // ---- Cinemeta catalogs ----

    /** A catalog with its genre settled ("New" always asks for a year). */
    private data class Query(val kind: Kind, val catalog: String, val genre: String?) {
        fun url(base: Int) = Cinemeta.catalogUrl(kind, catalog, genre, base)
    }

    private fun resolve(s: Source.Catalog) =
        Query(s.kind, s.catalog, if (s.catalog == Cinemeta.YEAR) s.genre ?: newYear(s.kind).toString() else s.genre)

    /**
     * Titles at [skip] until [skip] + [limit] of [q], to [sink] on a background thread: stored pages at once (fresh
     * when they all still are), then the fetched ones. A page that can't be fetched keeps its stored copy.
     */
    private fun catalogCards(q: Query, skip: Int, limit: Int, req: Req, sink: (List<Title>, Boolean) -> Unit) {
        val bases = Paging.bases(skip, limit)
        io.execute {
            if (req.cancelled) return@execute
            val stored = bases.map { storedPage(q.url(it)) }
            val cut = { pages: List<Cinemeta.Page?> -> Paging.slice(bases, pages, skip, limit) }
            val now = clock()
            if (Paging.allFresh(stored, { it.isFresh(now) }, { !it.value.hasMore })) {
                sink(cut(stored.map { it?.value }), true)
                return@execute
            }
            if (stored.any { it != null }) sink(cut(stored.map { it?.value }), false)
            net.execute {
                if (req.cancelled) return@execute
                val got = ArrayList<Cinemeta.Page?>()
                var ended = false
                for ((i, base) in bases.withIndex()) {
                    val old = stored[i]
                    val page = when {
                        ended -> null
                        old != null && old.isFresh(clock()) -> old.value
                        else -> fetchPage(q, base)?.value ?: old?.value
                    }
                    got += page
                    if (page != null && !page.hasMore) ended = true
                }
                sink(cut(got), true)
            }
        }
    }

    /**
     * The page as stored in memory or on disk (its titles indexed, unless [learn] is false: a name search's titles are
     * thinner than a catalog's); null when never fetched.
     */
    private fun storedPage(url: String, learn: Boolean = true): Stored<Cinemeta.Page>? {
        pages[url]?.let { return it }
        val entry = cache.read(url) ?: return null
        val page = Cinemeta.Page(
            TitleJson.readTitles(entry.data.optJSONArray("titles")),
            entry.data.optBoolean("hasMore", true),
            entry.freshMs,
        )
        return Stored(page, entry.at, entry.freshMs).also {
            pages[url] = it
            if (learn) index(page.titles)
        }
    }

    /** Fetches a page (once, however many ask meanwhile), stores it trimmed and learns its ids. Null on failure. */
    private fun fetchPage(q: Query, base: Int): Stored<Cinemeta.Page>? {
        val url = q.url(base)
        return single(url) {
            try {
                val page = Cinemeta.parseCatalog(http.get(url), q.kind, q.catalog, q.genre, currentYear())
                val now = clock()
                cache.write(url, JSONObject().put("titles", TitleJson.writeTitles(page.titles)).put("hasMore", page.hasMore), page.maxAgeMs, now)
                index(page.titles)
                ids.learn(page.titles)
                Stored(page, now, page.maxAgeMs).also { pages[url] = it }
            } catch (t: Throwable) {
                Log.w(TAG, "Cinemeta catalog not read: ${t.javaClass.simpleName}")
                null
            }
        }
    }

    private fun index(titles: List<Title>) {
        for (t in titles) {
            byId["${t.kind.type}|${t.id}"] = t
            t.tmdbId?.let { byTmdb["${t.kind.type}#$it"] = t }
        }
    }

    // ---- Manifest ----

    private fun storedManifest(): Stored<Cinemeta.Manifest>? {
        manifest?.let { return it }
        val entry = cache.read(Cinemeta.manifestUrl()) ?: return null
        return Stored(readManifest(entry.data), entry.at, entry.freshMs).also { manifest = it }
    }

    private fun refreshManifest() {
        io.execute {
            val stored = storedManifest()
            if (stored == null || !stored.isFresh(clock())) net.execute { fetchManifest() }
        }
    }

    private fun fetchManifest(): Stored<Cinemeta.Manifest>? = single("manifest") {
        try {
            val m = Cinemeta.parseManifest(http.get(Cinemeta.manifestUrl()))
            val now = clock()
            cache.write(Cinemeta.manifestUrl(), writeManifest(m), Cinemeta.MANIFEST_FRESH_MS, now)
            Stored(m, now, Cinemeta.MANIFEST_FRESH_MS).also { manifest = it }
        } catch (t: Throwable) {
            Log.w(TAG, "Cinemeta manifest not read: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun newYear(kind: Kind) = Cinemeta.newYear(manifest?.value?.years?.get(kind), currentYear())

    private fun currentYear() = Calendar.getInstance().apply { timeInMillis = clock() }.get(Calendar.YEAR)

    // ---- Top 10 (TMDB) ----

    /** A Top 10 slice to [sink]; empty without a usable key. Stored list at once, then the refreshed one. */
    private fun trendingCards(kind: Kind, skip: Int, limit: Int, req: Req, sink: (List<Card>, Boolean) -> Unit) {
        io.execute {
            if (req.cancelled) return@execute
            if (!usableKey()) {
                sink(emptyList(), true)
                return@execute
            }
            val stored = storedTop10(kind)
            if (stored != null && stored.isFresh(clock())) {
                sink(stored.value.window(skip, limit), true)
                return@execute
            }
            if (stored != null) sink(stored.value.window(skip, limit), false)
            net.execute {
                if (req.cancelled) return@execute
                val fresh = refreshTop10(kind)
                sink((fresh ?: stored?.value.orEmpty()).window(skip, limit), true)
            }
        }
    }

    /** Films' and series' Top 10 in turn, ranked 1 to 10 together (a Top 10 row added for both). */
    private fun trendingBoth(skip: Int, limit: Int, req: Req, sink: (List<Card>, Boolean) -> Unit) {
        val mix = TopMix { cards, fresh -> sink(cards.window(skip, limit), fresh) }
        for (kind in Kind.entries) trendingCards(kind, 0, TmdbTrending.WANTED, req) { cards, fresh -> mix.update(kind, cards, fresh) }
    }

    /**
     * Puts films' and series' Top 10 together as each answers: films and series in turn, ranked again from 1. Passed
     * on once both have answered, again when it changes, and last with fresh = true once both are fresh.
     */
    internal class TopMix(private val emit: (List<Card>, Boolean) -> Unit) {
        private val cards = HashMap<Kind, List<Card>>()
        private val settled = HashSet<Kind>()
        private var sent: List<Card>? = null
        private var sentFresh = false

        @Synchronized
        fun update(kind: Kind, list: List<Card>, fresh: Boolean) {
            cards[kind] = list
            if (fresh) settled += kind
            if (!Kind.entries.all { it in cards }) return
            val mixed = TmdbTrending.interleave(cards[Kind.MOVIE].orEmpty(), cards[Kind.SERIES].orEmpty())
                .mapIndexed { i, c -> c.copy(rank = i + 1) }
            val done = Kind.entries.all { it in settled }
            if (mixed == sent && (sentFresh || !done)) return
            sent = mixed
            sentFresh = done
            emit(mixed, done)
        }
    }

    private fun storedTop10(kind: Kind): Stored<List<Card>>? {
        top10[kind]?.let { return it }
        val entry = cache.read(top10Key(kind)) ?: return null
        val cards = TitleJson.readCards(entry.data.optJSONArray("cards"))
        return Stored(cards, entry.at, entry.freshMs).also { top10[kind] = it }
    }

    /** A new Top 10 (once, however many ask meanwhile); empty when TMDB refuses the key, null to keep the stored one. */
    private fun refreshTop10(kind: Kind): List<Card>? = single("top10|${kind.type}") { buildTop10(kind) }

    private fun buildTop10(kind: Kind): List<Card>? {
        if (!usableKey()) return emptyList()
        if (clock() < tmdbPausedUntil) return null
        // Cinemeta's Popular page carries the TMDB id of each of its titles: most of the Top 10 costs no lookup.
        val popular = Query(kind, Cinemeta.TOP, null)
        if (storedPage(popular.url(0)) == null) fetchPage(popular, 0)

        val candidates = try {
            TmdbTrending.parse(tmdb.trending(kind), kind)
        } catch (t: Throwable) {
            return tmdbFailed(t)
        }
        val known = { c: TmdbTrending.Candidate ->
            byTmdb["${kind.type}#${c.tmdbId}"]?.let { TmdbTrending.Known.Hit(it.id) } ?: ids.known(kind, c.tmdbId)
        }
        var partial = false
        while (true) {
            val ask = TmdbTrending.toAsk(candidates, known)
            if (ask.isEmpty()) break
            val failure = lookUp(kind, ask) ?: continue
            if (failure is Tmdb.HttpError && (failure.code == 401 || failure.code == 403)) return tmdbFailed(failure)
            if (failure is Tmdb.HttpError && failure.code == 429) tmdbFailed(failure)
            else Log.w(TAG, "TMDB ids not read: ${failure.javaClass.simpleName}")
            // What's known makes this Top 10 for now; the rest is asked again in a few minutes.
            partial = true
            break
        }
        val ranked = TmdbTrending.ranked(candidates, known)
        val metas = ranked.filter { (_, imdb) -> byId["${kind.type}|$imdb"] == null }
            .associate { (_, imdb) -> imdb to FutureTask { metaTitle(kind, imdb) }.also(lookups::execute) }
        val cards = ranked.mapIndexed { i, (c, imdb) ->
            val cinemeta = byId["${kind.type}|$imdb"] ?: metas[imdb]?.let { runCatching { it.get() }.getOrNull() }
            Card(TmdbTrending.title(c, imdb, cinemeta), rank = i + 1)
        }
        val now = clock()
        val freshMs = if (partial || cards.isEmpty()) RETRY_MS else TmdbTrending.FRESH_MS
        cache.write(top10Key(kind), JSONObject().put("cards", TitleJson.writeCards(cards)), freshMs, now)
        top10[kind] = Stored(cards, now, freshMs)
        return cards
    }

    // ---- Streaming services (TMDB Discover) ----

    /**
     * A service's titles at [skip] until [skip] + [limit] to [sink], once, fresh; empty without a usable key. Only the
     * TMDB pages those positions need are read: a row's is the first alone, a collection's ([Source.Discover.full]) go
     * on as the grid asks. Pages are kept [Discover.FRESH_MS]; one that can't be fetched keeps its stored copy.
     */
    private fun discoverCards(s: Source.Discover, skip: Int, limit: Int, req: Req, sink: (List<Card>, Boolean) -> Unit) {
        val kinds = s.kind?.let { listOf(it) } ?: Kind.entries.toList()
        val pages = if (s.full || s.paged) Discover.pages(skip, limit, kinds.size) else 1..1
        io.execute {
            if (req.cancelled) return@execute
            if (!usableKey()) {
                sink(emptyList(), true)
                return@execute
            }
            val got = HashMap<String, Discover.Page>()
            val cut = {
                Discover.slice(pages, kinds, skip, limit) { k, p -> got[Discover.key(s, k, p)]?.items }.mapNotNull(::discoverCard)
            }
            var missing = false
            for (k in kinds) for (p in pages) {
                val key = Discover.key(s, k, p)
                val page = storedDiscover(key, fresh = true)
                if (page == null) missing = true else got[key] = page
                if (page == null || page.last) break
            }
            if (!missing) {
                sink(cut(), true)
                return@execute
            }
            net.execute {
                if (req.cancelled) return@execute
                for (k in kinds) for (p in pages) {
                    val key = Discover.key(s, k, p)
                    val page = got[key] ?: fetchDiscover(s, k, p) ?: storedDiscover(key, fresh = false) ?: break
                    got[key] = page
                    if (page.last) break
                }
                sink(cut(), true)
            }
        }
    }

    /**
     * A chosen list's titles at [skip] until [skip] + [limit] to [sink], once, fresh; empty without a usable key. The
     * list is walked in its order, a few titles side by side (each kept [Discover.FRESH_MS]; one that can't be fetched
     * keeps its stored copy), only as far as those positions need: the titles the service carries in the US count.
     */
    private fun curatedCards(s: Source.Curated, skip: Int, limit: Int, req: Req, sink: (List<Card>, Boolean) -> Unit) {
        net.execute {
            if (req.cancelled) return@execute
            if (!usableKey() || limit <= 0) {
                sink(emptyList(), true)
                return@execute
            }
            val from = skip.coerceAtLeast(0)
            val shown = ArrayList<Card>()
            for (chunk in s.titles.chunked(Curated.SIDE_BY_SIDE)) {
                if (req.cancelled) return@execute
                if (shown.size >= from + limit) break
                val tasks = chunk.map { pick -> FutureTask { curatedEntry(s, pick) }.also(lookups::execute) }
                for ((pick, task) in chunk.zip(tasks)) {
                    val entry = try {
                        task.get()
                    } catch (e: ExecutionException) {
                        null
                    } catch (e: InterruptedException) {
                        null
                    }
                    if (entry == null || !entry.on || !Curated.matches(pick, entry.item)) continue
                    // Its IMDb id came with it: the card is the whole title.
                    if (entry.imdb != null && knownImdb(s.kind, pick.tmdbId) !is TmdbTrending.Known.Hit) ids.found(s.kind, pick.tmdbId, entry.imdb)
                    discoverCard(entry.item)?.let { shown += it }
                }
            }
            sink(shown.window(from, limit), true)
        }
    }

    /** One chosen title as TMDB has it: the stored copy while fresh, else fetched else the old copy. */
    private fun curatedEntry(s: Source.Curated, pick: CuratedTitle): Curated.Entry? {
        val key = Curated.key(s.kind, s.provider, pick.tmdbId)
        val stored = cache.read(key)
        if (stored != null && stored.isFresh(clock())) return Curated.read(stored.data)
        val fetched = single(key) {
            if (clock() < tmdbPausedUntil) return@single null
            try {
                val entry = tmdb.curated(s.kind, pick.tmdbId)?.let { Curated.parse(it, s.kind, s.provider) } ?: return@single null
                cache.write(key, Curated.write(entry), Discover.FRESH_MS)
                entry
            } catch (t: Throwable) {
                tmdbFailed(t)
                null
            }
        }
        return fetched ?: stored?.let { Curated.read(it.data) }
    }

    private fun storedDiscover(key: String, fresh: Boolean): Discover.Page? {
        val entry = cache.read(key)?.takeIf { !fresh || it.isFresh(clock()) } ?: return null
        return Discover.Page(TmdbDetails.readItems(entry.data.optJSONArray("items")), entry.data.optBoolean("last", false))
    }

    /**
     * Fetches a Discover page (once, however many ask meanwhile) and stores it. A row's titles get their IMDb ids
     * looked up as a Top 10's do (kept for good), so its cards and the header are whole titles; a collection's wait
     * until one is opened ([resolve]). Null on failure.
     */
    private fun fetchDiscover(s: Source.Discover, kind: Kind, page: Int): Discover.Page? = single(Discover.key(s, kind, page)) {
        if (clock() < tmdbPausedUntil) return@single null
        try {
            val got = Discover.parse(tmdb.discover(kind, Discover.params(s, kind, page)), kind, page)
            if (!s.full) {
                val ask = got.items.filter { knownImdb(it.kind, it.tmdbId) == TmdbTrending.Known.Unknown }
                    .map { TmdbTrending.Candidate(it.tmdbId, it.kind, it.name, it.year, it.overview, it.backdrop) }
                if (ask.isNotEmpty()) lookUp(kind, ask)?.let { Log.w(TAG, "TMDB ids not read: ${it.javaClass.simpleName}") }
            }
            cache.write(
                Discover.key(s, kind, page),
                JSONObject().put("items", TmdbDetails.writeItems(got.items)).put("last", got.last),
                Discover.FRESH_MS,
            )
            got
        } catch (t: Throwable) {
            tmdbFailed(t)
            null
        }
    }

    /** A Discover title's card: the title itself when its IMDb id is known, else a stand-in; none when TMDB has no id. */
    private fun discoverCard(item: TmdbDetails.Item): Card? {
        if (item.adult) return null
        val imdb = when (val k = knownImdb(item.kind, item.tmdbId)) {
            is TmdbTrending.Known.Hit -> k.imdb
            TmdbTrending.Known.Miss -> return null
            TmdbTrending.Known.Unknown -> null
        }
        return MoreLikeThis.card(MoreLikeThis.Pick(item, imdb), imdb?.let { byId["${item.kind.type}|$it"] })
    }

    /** Asks TMDB for the IMDb ids of [ask] side by side and keeps the answers; the first failure, if any. */
    private fun lookUp(kind: Kind, ask: List<TmdbTrending.Candidate>): Throwable? {
        val tasks = ask.map { c -> c to FutureTask { tmdb.externalIds(kind, c.tmdbId)?.let(TmdbTrending::imdbOf) }.also(lookups::execute) }
        var failure: Throwable? = null
        for ((c, task) in tasks) {
            try {
                val imdb = task.get()
                if (imdb != null) ids.found(kind, c.tmdbId, imdb) else ids.missed(kind, c.tmdbId)
            } catch (e: ExecutionException) {
                if (failure == null) failure = e.cause ?: e
            } catch (e: InterruptedException) {
                if (failure == null) failure = e
            }
        }
        return failure
    }

    /** A Cinemeta meta for a Top 10 title not on its pages (kept a day): rating, genres, runtime, a series' years. */
    private fun metaTitle(kind: Kind, id: String): Title? {
        val url = Cinemeta.metaUrl(kind, id)
        cache.read(url)?.takeIf { it.isFresh(clock()) }?.let { return TitleJson.read(it.data.optJSONObject("title")) }
        return try {
            val title = Cinemeta.parseMeta(http.get(url), kind)?.takeIf { it.id == id } ?: return null
            cache.write(url, JSONObject().put("title", TitleJson.write(title)), Cinemeta.META_FRESH_MS)
            title.also { index(listOf(it)) }
        } catch (t: Throwable) {
            Log.w(TAG, "Cinemeta meta not read: ${t.javaClass.simpleName}")
            null
        }
    }

    /**
     * What a TMDB failure means: a refused key is remembered (no Top 10, age ratings or TMDB More Like This until it
     * changes), 429 pauses TMDB. Only the code or the kind of failure is logged, never an address.
     */
    private fun tmdbFailed(t: Throwable): List<Card>? {
        val code = (t as? Tmdb.HttpError)?.code
        Log.w(TAG, "TMDB not read: ${code ?: t.javaClass.simpleName}")
        when (code) {
            401, 403 -> {
                val hash = keyHash(tmdbKey())
                refusedKey = hash
                cache.write(REFUSED, JSONObject().put("key", hash), REFUSED_KEPT_MS)
                return emptyList()
            }
            429 -> tmdbPausedUntil = clock() + RETRY_MS
        }
        return null
    }

    /** A key is set and TMDB hasn't refused it (a refused key counts as none until it changes, or for a day). */
    private fun usableKey(): Boolean {
        val key = tmdbKey()
        return key.isNotEmpty() && keyHash(key) != refusedKey
    }

    // ---- Header ----

    /** A kind's titles for the header: its Top 10, else (no key, or none) Cinemeta Popular's first 10. */
    private fun headerTitles(kind: Kind, req: Req, sink: (List<Title>, Boolean) -> Unit) {
        trendingCards(kind, 0, TmdbTrending.WANTED, req) { cards, fresh ->
            when {
                cards.isNotEmpty() -> sink(cards.map { it.title }, fresh)
                fresh -> catalogCards(Query(kind, Cinemeta.TOP, null), 0, TmdbTrending.WANTED, req, sink)
            }
        }
    }

    /**
     * Puts a page's header feed together from its kinds' titles (Home: films and series in turn) and passes it on
     * when it changes, once every kind has answered. An empty feed only once every kind has its final answer.
     */
    internal class HeaderMix(private val kinds: List<Kind>, private val emit: (HeaderFeed) -> Unit) {
        private val titles = HashMap<Kind, List<Title>>()
        private val settled = HashSet<Kind>()
        private var sent: List<Title>? = null

        @Synchronized
        fun update(kind: Kind, list: List<Title>, fresh: Boolean) {
            titles[kind] = list
            if (fresh) settled += kind
            if (!kinds.all { it in titles }) return
            val feed = if (kinds.size == 1) {
                titles.getValue(kinds[0]).take(TmdbTrending.WANTED)
            } else {
                TmdbTrending.interleave(titles[Kind.MOVIE].orEmpty(), titles[Kind.SERIES].orEmpty())
            }
            if (feed.isEmpty() && !kinds.all { it in settled }) return
            if (feed == sent) return
            sent = feed
            emit(HeaderFeed(feed))
        }
    }

    // ---- Plumbing ----

    private fun local(req: Req, skip: Int, limit: Int, sink: (List<Card>, Boolean) -> Unit, cards: () -> List<Card>) {
        io.execute { if (!req.cancelled) sink(cards().window(skip, limit), true) }
    }

    private fun post(req: Req, action: () -> Unit) = main.execute { if (!req.cancelled) action() }

    /** Runs [work] once for [key] however many threads ask at the same time; the others wait for its answer. */
    @Suppress("UNCHECKED_CAST")
    private fun <T> single(key: String, work: () -> T): T {
        val mine = FutureTask<T> { work() }
        val other = running.putIfAbsent(key, mine) as FutureTask<T>?
        if (other != null) return other.get()
        try {
            mine.run()
        } finally {
            running.remove(key, mine)
        }
        return mine.get()
    }

    private class Req : Cancel {
        @Volatile var cancelled = false
        override fun cancel() {
            cancelled = true
        }
    }

    /** A value and how long it stays fresh from [at]. */
    private class Stored<T>(val value: T, val at: Long, val freshMs: Long) {
        fun isFresh(now: Long) = now >= at && now - at < freshMs
    }

    /** A small least-recently-used map, safe from any thread. */
    private class Lru<K, V>(private val max: Int) {
        private val map = object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
        }

        @Synchronized operator fun get(key: K): V? = map[key]

        @Synchronized operator fun set(key: K, value: V) {
            map[key] = value
        }
    }

    private fun <T> List<T>.window(skip: Int, limit: Int) = drop(skip.coerceAtLeast(0)).take(limit.coerceAtLeast(0))

    companion object {
        private const val TAG = "FirePlayerVod"
        private const val PAGES_KEPT = 24
        private const val TITLES_KEPT = 1500
        /** Search answers kept in memory (both kinds of the last few queries). */
        private const val SEARCHES_KEPT = 16
        /** A Top 10 made while TMDB failed part-way, or a pause after "too many requests": asked again after this. */
        const val RETRY_MS = 10 * 60_000L
        const val REFUSED_KEPT_MS = 24 * 3600_000L
        private const val REFUSED = "tmdb|refused"
        /** Metas and TMDB details kept in memory: the 3-page stack and a few returns. */
        private const val DETAILS_KEPT = 6
        /** How long a stored Top 10 still gives a title page its "#4 in Top 10 this week". */
        const val RANK_KEPT_MS = 24 * 3600_000L
        /** A stored TMDB answer meaning "no such title". */
        private const val MISSING = "missing"

        private fun top10Key(kind: Kind) = "tmdb|trending|${kind.type}|week"

        private fun writeManifest(m: Cinemeta.Manifest) = JSONObject().apply {
            put("genres", JSONObject().apply { m.genres.forEach { (k, v) -> put(k.type, JSONArray(v)) } })
            put("years", JSONObject().apply { m.years.forEach { (k, v) -> put(k.type, JSONArray(v)) } })
        }

        private fun readManifest(o: JSONObject): Cinemeta.Manifest {
            fun lists(name: String) = Kind.entries.associateWith { o.optJSONObject(name)?.optJSONArray(it.type).texts() }
                .filterValues { it.isNotEmpty() }
            return Cinemeta.Manifest(lists("genres"), lists("years"))
        }

        /** Enough of a key's SHA-256 to tell keys apart; the key itself is never stored here. */
        internal fun keyHash(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
    }
}
