package app.fireplayer.history

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import app.fireplayer.data.KeyStore
import app.fireplayer.data.Prefs
import app.fireplayer.player.MatchFixes
import app.fireplayer.player.NextEpisode
import app.fireplayer.player.NextPick
import app.fireplayer.player.ShowGuide
import app.fireplayer.player.TitleInfo
import app.fireplayer.subs.Tmdb
import app.fireplayer.titles.TitleBook
import app.fireplayer.history.WatchHistory.Companion.matched
import java.io.File
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * FirePlayer's side of Glass Home's Continue Watching row. The player notes what it plays ([watched]); Glass Home reads
 * the rows and removes them through [ContinueProvider], and plays one again with [ACTION_RESUME]. For a show it also
 * lists every episode ([episodes]) and plays the one chosen ([ACTION_PLAY_EPISODE]). What it takes to play again (the
 * link, with the provider's login in it, and the headers and names the opening app sent) stays in a file of
 * FirePlayer's own, kept out of backups; the rows only say what the title is, how far along, and the provider's address.
 *
 * Films and shows are named as the player shows them: under their English names from the title server when it knows
 * them ([TitleBook]), with their TMDB ids, otherwise as read from the name they came with. One the title server has no
 * TMDB id for is asked about here once it's noted ([tmdbOf]), so Glass Home has its artwork too.
 */
object ContinueWatching {
    const val AUTHORITY = "app.fireplayer.continue"
    val ITEMS: Uri = Uri.parse("content://$AUTHORITY/items")
    const val ACTION_RESUME = "app.fireplayer.action.RESUME"
    /** Another episode of a row's show, by the provider's id for it ([EXTRA_EPISODE_ID], from [episodes]). */
    const val ACTION_PLAY_EPISODE = "app.fireplayer.action.PLAY_EPISODE"
    const val EXTRA_ID = "app.fireplayer.extra.CONTINUE_ID"
    const val EXTRA_EPISODE_ID = "app.fireplayer.extra.EPISODE_ID"

    /** One video as the player saw it, taken on the main thread; [watched] works out the rest. */
    class Played(
        val uri: Uri,
        val rawTitle: String?,
        val type: String?,
        /** Name, value, name, value…, as the opening app sent them. */
        val headers: List<String>,
        /** The subtitles the opening app handed over with it, and their names. */
        val subtitles: List<Pair<Uri, String?>>,
        /** Its name as on screen. */
        val shown: TitleInfo,
        val positionMs: Long,
        val durationMs: Long,
        /** Real playing time since the player last said. */
        val playedMs: Long,
        val finished: Boolean,
        /** Which episode it really is, by the provider's numbers, when the player knows (see [NextPick]). */
        val real: NextPick.Real? = null,
        /**
         * The episode after it, as the player found it with the provider (see [NextEpisode]) or as the real next one in
         * the list it came with: its link and name, and its real numbers when known.
         */
        val next: Pair<Uri, String?>?,
        val nextReal: NextPick.Real? = null,
        /** What FirePlayer's own TMDB match says it is (the show's guide, or a Fix match for a film); wins when given. */
        val match: WatchHistory.Match? = null,
        /** The same for the episode after it. */
        val nextMatch: WatchHistory.Match? = null,
    )

    private const val TAG = "FirePlayerContinue"
    private const val FILE = "continue_watching.txt"
    /** How long Glass Home waits for a show's episodes before it gets the ones kept from before. */
    private const val LIST_WAIT_S = 20L
    /** How many of Glass Home's asks for a show's episodes wait for a free thread. */
    private const val LIST_QUEUE = 4
    /** A show's guide kept this recently is listed as it is, without asking the provider again. */
    private const val GUIDE_YOUNG_MS = 10 * 60_000L
    private val YEAR = Regex("""\(((?:19|20)\d{2})\)""")
    private val MARKS = Regex("""\p{M}+""")

    private val lock = Any()
    private var history: WatchHistory? = null
    private var book: TitleBook? = null
    private val lookup = TmdbLookup { SystemClock.elapsedRealtime() }
    private val random = SecureRandom()
    // One at a time and in order, and not dropped when the player closes just after.
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ContinueWatching").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    // Glass Home's asks for a show's episodes. One that takes longer than it waits still finishes, and its guide is kept
    // for the next time. Only a few wait their turn: with the provider out of reach each can take minutes, and one more
    // is listed from those kept straight away rather than pile up behind them.
    private val lister = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(LIST_QUEUE)) { r ->
        Thread(r, "ContinueEpisodes").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }
    // The asks still running, by row and episode, so asking again waits on the same one.
    private val asks = HashMap<String, Future<ShowGuide?>>()
    // Asking TMDB can take a while (a timeout, without a network): on a thread of its own, so what was watched is kept
    // straight away and never waits for it, also when the player closes just after.
    private val tmdbWorker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ContinueWatchingTmdb").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    /** Notes what the player saw; Glass Home hears when its row changes. */
    fun watched(context: Context, played: Played) {
        val app = context.applicationContext
        worker.execute {
            try {
                val watch = watchOf(app, played) ?: return@execute
                val (changed, row) = synchronized(lock) {
                    val h = load(app)
                    val changed = h.watched(watch, System.currentTimeMillis(), ::newId).also { save(app, h) }
                    // Its row as it is now, if it has one.
                    val its = h.all.firstOrNull { it.kind == watch.kind && it.keys.any { k -> k in watch.keys } }
                    changed to its?.takeIf { it.shown }?.row()
                }
                if (changed) app.contentResolver.notifyChange(ITEMS, null)
                // Kept already; without a TMDB id it's looked up now, meanwhile.
                row?.let { lookUp(app, listOf(it)) }
            } catch (e: Exception) {
                // Only the kind of failure: a message may hold the link, and with it the login.
                Log.w(TAG, "Couldn't note what was watched: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Every film and episode played, newest first: what's known of each, and its link (login and all). */
    fun played(context: Context): List<WatchHistory.Record> = synchronized(lock) { current(context.applicationContext).played() }

    /**
     * The rows, newest first. Those without a TMDB id (noted before one could be found) are looked up meanwhile
     * ([lookUp]): not here, as Glass Home waits for the rows.
     */
    fun rows(context: Context): List<WatchHistory.Row> {
        val app = context.applicationContext
        val rows = synchronized(lock) { current(app).rows() }
        lookUp(app, rows)
        return rows
    }

    /**
     * A match put right by hand (Fix match) for the video at [uri]: its row, and its show's, take the TMDB id, name, year
     * and numbers at once, and Glass Home is told.
     */
    fun rematch(context: Context, uri: Uri, episode: Boolean, match: WatchHistory.Match) {
        val app = context.applicationContext
        worker.execute {
            try {
                val link = StreamLink.parse(uri.toString()) ?: return@execute
                val videoKey = link.id?.let { "${if (episode) "e" else "m"}|${link.server}|$it" } ?: "u|$uri"
                val showId = if (episode) runCatching { NextEpisode.rememberedShowId(app, uri) }.getOrNull() else null
                val keys = setOfNotNull(videoKey, showId?.let { "s|${link.server}|$it" })
                val kind = if (episode) WatchHistory.Kind.EPISODE else WatchHistory.Kind.MOVIE
                val changed = synchronized(lock) {
                    val h = load(app)
                    h.rematch(kind, keys, videoKey, match).also { if (it) save(app, h) }
                }
                if (changed) app.contentResolver.notifyChange(ITEMS, null)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't put the match right: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Removes one row; false when there's no such row. */
    fun remove(context: Context, id: String): Boolean {
        val app = context.applicationContext
        val removed = synchronized(lock) {
            val h = current(app)
            if (h.record(id) == null) return false
            h.remove(id).also { save(app, h) }
        }
        if (removed) app.contentResolver.notifyChange(ITEMS, null)
        return removed
    }

    /**
     * The player's own intent for one row: the video as its app first opened it, at the row's position. Null when
     * there's no such row any more.
     */
    fun resumeIntent(context: Context, id: String?): Intent? {
        if (id.isNullOrEmpty()) return null
        awaitNotes()
        val record = synchronized(lock) { current(context.applicationContext).record(id) } ?: return null
        return resumeIntentOf(record)
    }

    /**
     * Every episode of the show a row is at, for Glass Home's Episodes window ([ShowEpisodes]): the same ones FirePlayer's
     * Episodes sheet lists, the provider asked for them now and paired with TMDB's the first time. Empty when they can't
     * be listed: no such row, a film or a link of another shape, or the provider out of reach with none kept from
     * before. Blocks, for at most [LIST_WAIT_S] (then those kept from before): never on the main thread.
     */
    fun episodes(context: Context, id: String): List<ShowEpisodes.Row> {
        val app = context.applicationContext
        return try {
            val record = synchronized(lock) { current(app).record(id) } ?: return emptyList()
            if (record.kind != WatchHistory.Kind.EPISODE || StreamLink.parse(record.resume.uri)?.listsEpisodes != true) {
                return emptyList()
            }
            val uri = Uri.parse(record.resume.uri)
            // One kept a moment ago is listed at once, never behind asks still waiting on the provider.
            val kept = guideOf(app, record, uri, fresh = false)?.takeIf { young(app, it) }
            val guide = kept ?: try {
                asking(app, record, uri).get(LIST_WAIT_S, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "Listing the show's episodes from those kept: ${e.javaClass.simpleName}")
                guideOf(app, record, uri, fresh = false)
            } ?: return emptyList()
            val prefs = Prefs(app)
            ShowEpisodes.rows(guide.episodes, NextEpisode.episodeId(uri), record.positionMs) {
                prefs.savedPosition(NextEpisode.episodeUri(uri, it.id, it.extension).toString())
            }
        } catch (e: Exception) {
            // Only the kind of failure: a message may hold the link, and with it the login.
            Log.w(TAG, "Couldn't list the show's episodes: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    /**
     * The player's own intent for an episode of the show a row is at, chosen in Glass Home's Episodes window by the
     * provider's id for it ([episodes]): the row's own episode as [resumeIntent] has it; another one as FirePlayer's
     * Episodes sheet plays it, the same link with that episode's file at the end (the login and the opening app's
     * headers as they came), from where FirePlayer left it, else the start. Null when there's no such row, or the
     * episode isn't in the show's guide kept here.
     */
    fun episodeIntent(context: Context, id: String?, episodeId: String?): Intent? {
        if (id.isNullOrEmpty() || episodeId.isNullOrEmpty() || !episodeId.all { it.isDigit() }) return null
        awaitNotes()
        val app = context.applicationContext
        val record = synchronized(lock) { current(app).record(id) } ?: return null
        if (record.kind != WatchHistory.Kind.EPISODE) return null
        val uri = Uri.parse(record.resume.uri)
        if (NextEpisode.episodeId(uri) == episodeId) return resumeIntentOf(record)
        val guide = guideOf(app, record, uri, fresh = false) ?: return null
        val ep = guide.episode(episodeId) ?: return null
        val target = NextEpisode.episodeUri(uri, ep.id, ep.extension)
        // Its link ends in its own file type, so the type the row's episode came with is left out; so are its subtitles.
        val intent = Intent(Intent.ACTION_VIEW).setData(target)
        intent.putExtra("title", ShowEpisodes.title(guide.searchName.ifEmpty { record.title }, ep))
        if (record.resume.headers.isNotEmpty()) intent.putExtra("headers", record.resume.headers.toTypedArray())
        intent.putExtra("position", Prefs(app).savedPosition(target.toString()))
        return intent
    }

    /**
     * Played and closed a moment ago: what the player noted last is kept first, so the row plays the episode and place
     * it shows now, never the one before.
     */
    private fun awaitNotes() {
        try {
            worker.submit {}.get(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "Resuming before what was watched was kept: ${e.javaClass.simpleName}")
        }
    }

    private fun resumeIntentOf(record: WatchHistory.Record): Intent {
        val resume = record.resume
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(resume.uri), resume.type)
        resume.title?.let { intent.putExtra("title", it) }
        if (resume.headers.isNotEmpty()) intent.putExtra("headers", resume.headers.toTypedArray())
        if (resume.subtitles.isNotEmpty()) {
            intent.putExtra("subs", resume.subtitles.map { Uri.parse(it) as Parcelable }.toTypedArray())
            @Suppress("UNCHECKED_CAST")
            intent.putExtra("subs.name", resume.subtitleNames.toTypedArray() as Array<String>)
        }
        intent.putExtra("position", record.positionMs)
        return intent
    }

    /**
     * The guide ([ShowGuide]) of the show holding the row's episode at [uri]. The one kept from before; with [fresh], one
     * kept in the last [GUIDE_YOUNG_MS], or else the provider is asked for the show's episodes now (as the player does)
     * and they're paired with TMDB's and kept, so the player shows their names at once; the one kept from before when
     * that fails. Null when there's none with the row's episode. Blocks on the network when [fresh].
     */
    private fun guideOf(context: Context, record: WatchHistory.Record, uri: Uri, fresh: Boolean): ShowGuide? {
        val episodeId = NextEpisode.episodeId(uri) ?: return null
        val server = StreamLink.parse(record.resume.uri)?.server
        val showId = runCatching { NextEpisode.rememberedShowId(context, uri) }.getOrNull()
            ?: server?.let { ShowEpisodes.showIdIn(record.keys, it) }
        val key = showId?.let { NextEpisode.showKey(uri, it) }
        val saved = key?.let { ShowGuide.saved(context, it) }?.takeIf { it.episode(episodeId) != null }
        if (!fresh || (saved != null && young(context, saved))) return saved
        val found = runCatching {
            NextEpisode(context, headersOf(record.resume.headers)).find(uri, record.resume.title, record.title)
        }.getOrNull() ?: return saved
        val foundKey = NextEpisode.showKey(uri, found.seriesId) ?: return saved
        val portal = NextEpisode.portal(uri) ?: return saved
        val tmdb = Tmdb({ tmdbKey(context) }, memoryOf(titleBook(context)))
        return runCatching { ShowGuide.build(context, foundKey, portal, found, tmdb, MatchFixes(context)) }
            .onFailure { Log.w(TAG, "Couldn't pair the show's episodes: ${it.javaClass.simpleName}") }
            .getOrNull()?.takeIf { it.episode(episodeId) != null } ?: saved
    }

    /** Kept in the last [GUIDE_YOUNG_MS]. */
    private fun young(context: Context, guide: ShowGuide): Boolean =
        System.currentTimeMillis() - ShowGuide.savedAt(context, guide.key) in 0 until GUIDE_YOUNG_MS

    /**
     * The ask for the show's episodes of a row at its episode: the one still running, when Glass Home asks again (Retry)
     * before it's done, else a new one on [lister]. Throws when [lister] has as many waiting as it takes.
     */
    private fun asking(context: Context, record: WatchHistory.Record, uri: Uri): Future<ShowGuide?> {
        val key = "${record.id}|${NextEpisode.episodeId(uri)}"
        synchronized(asks) {
            asks.values.removeAll { it.isDone }
            return asks.getOrPut(key) { lister.submit(Callable { guideOf(context, record, uri, fresh = true) }) }
        }
    }

    /** Name, value, name, value… as the opening app sent them. */
    private fun headersOf(list: List<String>): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        var i = 0
        while (i + 1 < list.size) {
            map[list[i]] = list[i + 1]
            i += 2
        }
        return map
    }

    // ---------------------------------------------------------------------
    // What was watched

    /** Null for what isn't a film or an episode: live channels, catch-up, files on the TV. Blocks; on [worker]. */
    private fun watchOf(context: Context, p: Played): WatchHistory.Watch? {
        val url = p.uri.toString()
        val link = StreamLink.parse(url) ?: return null
        // An episode moved on from by itself is finished whatever its length was; anything else has to have one.
        if (link.isLive || (!p.finished && p.durationMs <= 0)) return null
        // An app's own subtitle files (content://) were only lent to that one opening, and would fail the film later.
        val handOn = p.subtitles.filter { it.first.scheme != ContentResolver.SCHEME_CONTENT }
        val resume = WatchHistory.Resume(
            uri = url,
            type = p.type,
            title = p.rawTitle,
            headers = p.headers,
            subtitles = handOn.map { it.first.toString() },
            subtitleNames = handOn.map { it.second },
        )
        val episode = link.kind == StreamLink.Kind.EPISODE || (link.kind == StreamLink.Kind.OTHER && p.shown.season != null)
        val book = titleBook(context)
        val server = link.proxied
        val id = link.id?.toIntOrNull()
        val videoKey = link.id?.let { "${if (episode) "e" else "m"}|${link.server}|$it" } ?: "u|$url"
        val position = if (p.positionMs > 30_000) p.positionMs else 0L
        if (!episode) {
            val about = if (server != null && id != null && link.kind == StreamLink.Kind.MOVIE) {
                look { book?.about(server, TitleBook.Kind.MOVIE, id) }
            } else null
            val shown = about?.title?.let { TitleInfo.parse(it, null) } ?: p.shown
            val name = shown.searchQuery.ifBlank { shown.title }.takeIf { it != TitleInfo.NO_NAME }
                ?: p.match?.title?.takeIf { it.isNotBlank() } ?: return null
            val year = yearIn(shown) ?: about?.year?.takeIf { it > 0 } ?: 0
            return WatchHistory.Watch(
                kind = WatchHistory.Kind.MOVIE,
                keys = setOf(videoKey),
                videoKey = videoKey,
                title = name,
                year = year,
                tmdbId = about?.tmdb?.takeIf { it > 0 } ?: cachedTmdb(book, name, year, false),
                positionMs = position,
                durationMs = p.durationMs,
                playedMs = p.playedMs,
                finished = p.finished,
                resume = resume,
            ).matched(p.match)
        }
        // The provider's numbers for it, not its place in TiviMate's list (which counts copies of an episode too): as the
        // title server noted them, or as the player knew them when the database couldn't be read just now.
        val noted = if (server != null && id != null && link.kind == StreamLink.Kind.EPISODE) {
            look { book?.episode(server, id) } ?: p.real?.let { TitleBook.Episode(id, it.series, it.season, it.episode) }
        } else null
        val real = noted?.let { NextPick.Real.of(it.series, it.season, it.number) } ?: p.real
        val about = noted?.let { look { book?.about(server!!, TitleBook.Kind.SHOW, it.series) } }
        val englishShow = about?.title?.let { TitleInfo.parse(it, null) }
        // A name that's only the episode's number ("S01E03 - Pilot") says nothing of the show.
        val shown = englishShow ?: p.shown.takeIf { it.named }
        // The show's guide knows the show even when the link's name is only the episode's number.
        val name = shown?.let { it.searchQuery.ifBlank { it.title } }?.takeIf { it != TitleInfo.NO_NAME }
            ?: p.match?.title?.takeIf { it.isNotBlank() }
        val showId = noted?.series?.toString() ?: runCatching { NextEpisode.rememberedShowId(context, p.uri) }.getOrNull()
        val keys = listOfNotNull(
            showId?.let { "s|${link.server}|$it" },
            name?.let { nameKey(it) },
            p.match?.title?.takeIf { it.isNotBlank() }?.let { nameKey(it) },
            // Its names as TiviMate sent it and as shown too, so it's the same show before and after the English name is known.
            p.shown.takeIf { it.named }?.let { nameKey(it.searchQuery) },
            TitleInfo.parse(p.rawTitle, p.uri).takeIf { it.named && it.season != null }?.let { nameKey(it.searchQuery) },
        ).filter { it.length > 2 }.toSet()
        if (name == null || keys.isEmpty()) return null
        val year = shown?.let { yearIn(it) } ?: about?.year?.takeIf { it > 0 } ?: 0
        val next = if (p.finished) nextOf(p, link, book, noted, shown?.title ?: name, resume) else null
        return WatchHistory.Watch(
            kind = WatchHistory.Kind.EPISODE,
            keys = keys,
            videoKey = videoKey,
            title = name,
            year = year,
            season = real?.season ?: p.shown.season ?: 0,
            episode = real?.episode ?: p.shown.episode ?: 0,
            tmdbId = about?.tmdb?.takeIf { it > 0 } ?: cachedTmdb(book, name, year, true),
            positionMs = position,
            durationMs = p.durationMs,
            playedMs = p.playedMs,
            finished = p.finished,
            next = next,
            resume = resume,
        ).matched(p.match)
    }

    /**
     * The episode after a finished one: the one the player found with the provider, or else the next one the title
     * server noted when TiviMate listed the show's episodes. Null when neither knows of one.
     */
    private fun nextOf(
        p: Played,
        link: StreamLink,
        book: TitleBook?,
        noted: TitleBook.Episode?,
        show: String,
        resume: WatchHistory.Resume,
    ): WatchHistory.Next? {
        p.next?.let { (uri, title) ->
            val nextLink = StreamLink.parse(uri.toString())
            val info = TitleInfo.parse(title, uri)
            val numbered = nextLink?.id?.toIntOrNull()?.let { id -> link.proxied?.let { look { book?.episode(it, id) } } }
                ?.let { NextPick.Real.of(it.series, it.season, it.number) } ?: p.nextReal
            return WatchHistory.Next(
                videoKey = nextLink?.id?.let { "e|${nextLink.server}|$it" } ?: "u|$uri",
                season = p.nextMatch?.season?.takeIf { it > 0 } ?: numbered?.season ?: info.season ?: 0,
                episode = p.nextMatch?.takeIf { it.season > 0 }?.episode ?: numbered?.episode ?: info.episode ?: 0,
                episodeTitle = p.nextMatch?.takeIf { it.season > 0 }?.episodeTitle,
                resume = WatchHistory.Resume(uri = uri.toString(), title = title, headers = resume.headers),
            )
        }
        val server = link.proxied ?: return null
        val after = noted?.let { look { book?.episodeAfter(server, it) } } ?: return null
        // The same link with the next episode's file at the end, the login exactly as it came.
        val extension = after.extension ?: link.extension ?: "mkv"
        val folder = p.uri.encodedPath.orEmpty().substringBeforeLast('/')
        val uri = p.uri.buildUpon().encodedPath("$folder/${after.id}.${Uri.encode(extension)}").build()
        val title = String.format(Locale.ROOT, "%s S%02dE%02d", show, after.season, after.number)
        return WatchHistory.Next(
            videoKey = "e|${link.server}|${after.id}",
            season = after.season,
            episode = after.number,
            episodeTitle = null,
            resume = WatchHistory.Resume(uri = uri.toString(), title = title, headers = resume.headers),
        )
    }

    /**
     * What the title database says, or null when it can't be read just now (the title server's own process may be busy
     * writing to it): the title is then noted by the name it came with, rather than its playing time lost.
     */
    private inline fun <T> look(read: () -> T?): T? = try {
        read()
    } catch (e: Exception) {
        null
    }

    private fun yearIn(info: TitleInfo): Int? = YEAR.find(info.title)?.groupValues?.get(1)?.toIntOrNull()

    /** A show by its name alone, written plainly: "Grey's Anatomy" and "Greys Anatomy" are the same. */
    private fun nameKey(name: String): String =
        "n|" + Normalizer.normalize(name.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(MARKS, "")
            .replace("&", "and").filter { it.isLetterOrDigit() }

    /** The TMDB id TiviMate's titles were matched with before, for a title named like this; 0 when there's none. */
    private fun cachedTmdb(book: TitleBook?, name: String, year: Int, show: Boolean): Int {
        val memory = book?.tmdbMemory ?: return 0
        return runCatching { Tmdb({ "" }, memory).cached(name, year.takeIf { it > 0 }, show)?.id }.getOrNull() ?: 0
    }

    /**
     * The TMDB id for a film or show named like this: the one TiviMate's titles were matched with before, or else the
     * one TMDB is sure of when asked now, as the title server would ([Tmdb.verify]; once while FirePlayer runs, see
     * [TmdbLookup]). 0 when there's none; null when TMDB couldn't be asked (no TMDB key, or it can't be reached just
     * now). Blocks; on [tmdbWorker].
     */
    private fun tmdbOf(context: Context, book: TitleBook?, name: String, year: Int, show: Boolean): Int? {
        cachedTmdb(book, name, year, show).takeIf { it > 0 }?.let { return it }
        val key = tmdbKey(context).ifEmpty { return null }
        val tmdb = Tmdb({ key }, memoryOf(book))
        // Not sure of it a few days ago (here or in the title server): TMDB isn't asked again yet.
        return lookup.id(name, year, show) { n, y, s ->
            if (tmdb.recentlyMissed(n, y, s)) null else tmdb.verify(n, y, s)?.id
        }
    }

    /** Of these rows, those without a TMDB id that aren't being looked up already are looked up on [tmdbWorker]. */
    private fun lookUp(context: Context, rows: List<WatchHistory.Row>) {
        val unknown = lookup.toLookUp(rows)
        if (unknown.isNotEmpty()) tmdbWorker.execute { fillTmdb(context, unknown) }
    }

    /**
     * Rows without a TMDB id: each is looked up ([tmdbOf]) and, when found, its entry has it from now on and Glass Home
     * is told, so it draws the artwork. Those TMDB couldn't be asked about are looked up again on a later read (see
     * [TmdbLookup.fill]). On [tmdbWorker].
     */
    private fun fillTmdb(context: Context, rows: List<WatchHistory.Row>) {
        // Without a key nothing is asked: they're looked up once there is one.
        if (tmdbKey(context).isEmpty()) return lookup.lookUpAgain(rows)
        val book = titleBook(context)
        try {
            val idOf = { row: WatchHistory.Row ->
                tmdbOf(context, book, row.title, row.year, row.kind == WatchHistory.Kind.EPISODE)
            }
            lookup.fill(rows, idOf) { row, id ->
                val changed = synchronized(lock) {
                    val h = load(context)
                    // Not one it got meanwhile, by being watched.
                    h.record(row.id)?.tmdbId == 0 && h.setTmdbId(row.id, id).also { if (it) save(context, h) }
                }
                if (changed) context.contentResolver.notifyChange(ITEMS, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't look up TMDB ids: ${e.javaClass.simpleName}")
        }
    }

    /** The user's own TMDB key from Settings, "" for none. */
    private fun tmdbKey(context: Context): String =
        runCatching { KeyStore.of(context)[KeyStore.Key.TMDB] }.getOrDefault("")

    /**
     * TMDB's answers as the title server keeps them, so one found here is known after a restart too. Its database may
     * be busy in the title server's process just now: that answer then just isn't kept.
     */
    private fun memoryOf(book: TitleBook?) = object : Tmdb.Memory {
        override fun get(key: String): String? = book?.let { runCatching { it.tmdbMemory.get(key) }.getOrNull() }

        override fun put(key: String, value: String) {
            book?.let { runCatching { it.tmdbMemory.put(key, value) } }
        }
    }

    // Both from [worker] and [tmdbWorker]: one TitleBook for both.
    @Synchronized
    private fun titleBook(context: Context): TitleBook? =
        book ?: runCatching { TitleBook(context) }.getOrNull()?.also { book = it }

    // ---------------------------------------------------------------------
    // Kept in FirePlayer's own storage, out of backups

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, FILE))

    private fun load(context: Context): WatchHistory = history ?: run {
        val text = runCatching { String(file(context).readFully(), Charsets.UTF_8) }.getOrNull()
        WatchHistory.decode(text).also { history = it }
    }

    /** The history without what was left too long ago (see [WatchHistory.expire]), which is then gone from the file too. */
    private fun current(context: Context): WatchHistory =
        load(context).also { h -> if (h.expire(System.currentTimeMillis())) save(context, h) }

    private fun save(context: Context, h: WatchHistory) {
        val file = file(context)
        val out = file.startWrite()
        try {
            out.write(h.encode().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            Log.w(TAG, "Couldn't keep the watch history: ${e.javaClass.simpleName}")
        }
    }

    private fun newId(): String = String.format(Locale.ROOT, "%016x", random.nextLong())
}
