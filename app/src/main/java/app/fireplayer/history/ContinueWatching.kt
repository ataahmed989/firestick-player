package app.fireplayer.history

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import android.util.AtomicFile
import android.util.Log
import app.fireplayer.player.NextEpisode
import app.fireplayer.player.NextPick
import app.fireplayer.player.TitleInfo
import app.fireplayer.subs.Tmdb
import app.fireplayer.titles.TitleBook
import java.io.File
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors

/**
 * FirePlayer's side of Glass Home's Continue Watching row. The player notes what it plays ([watched]); Glass Home reads
 * the rows and removes them through [ContinueProvider], and plays one again with [ACTION_RESUME]. What it takes to play
 * again (the link, with the provider's login in it, and the headers and names the opening app sent) stays in a file of
 * FirePlayer's own, kept out of backups; the rows only say what the title is and how far along.
 *
 * Films and shows are named as the player shows them: under their English names from the title server when it knows
 * them ([TitleBook]), with their TMDB ids, otherwise as read from the name they came with.
 */
object ContinueWatching {
    const val AUTHORITY = "app.fireplayer.continue"
    val ITEMS: Uri = Uri.parse("content://$AUTHORITY/items")
    const val ACTION_RESUME = "app.fireplayer.action.RESUME"
    const val EXTRA_ID = "app.fireplayer.extra.CONTINUE_ID"

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
    )

    private const val TAG = "FirePlayerContinue"
    private const val FILE = "continue_watching.txt"
    private val YEAR = Regex("""\(((?:19|20)\d{2})\)""")
    private val MARKS = Regex("""\p{M}+""")

    private val lock = Any()
    private var history: WatchHistory? = null
    private var book: TitleBook? = null
    private val random = SecureRandom()
    // One at a time and in order, and not dropped when the player closes just after.
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ContinueWatching").apply {
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
                val changed = synchronized(lock) {
                    val h = load(app)
                    h.watched(watch, System.currentTimeMillis(), ::newId).also { save(app, h) }
                }
                if (changed) app.contentResolver.notifyChange(ITEMS, null)
            } catch (e: Exception) {
                // Only the kind of failure: a message may hold the link, and with it the login.
                Log.w(TAG, "Couldn't note what was watched: ${e.javaClass.simpleName}")
            }
        }
    }

    /** The rows, newest first. */
    fun rows(context: Context): List<WatchHistory.Row> = synchronized(lock) { current(context.applicationContext).rows() }

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
        val record = synchronized(lock) { current(context.applicationContext).record(id) } ?: return null
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
            val name = shown.searchQuery.ifBlank { shown.title }.takeIf { it != TitleInfo.NO_NAME } ?: return null
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
            )
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
        val name = shown?.let { it.searchQuery.ifBlank { it.title } }?.takeIf { it != TitleInfo.NO_NAME }
        val showId = noted?.series?.toString() ?: runCatching { NextEpisode.rememberedShowId(context, p.uri) }.getOrNull()
        val keys = listOfNotNull(
            showId?.let { "s|${link.server}|$it" },
            name?.let { nameKey(it) },
            // Its names as TiviMate sent it and as shown too, so it's the same show before and after the English name is known.
            p.shown.takeIf { it.named }?.let { nameKey(it.searchQuery) },
            TitleInfo.parse(p.rawTitle, p.uri).takeIf { it.named && it.season != null }?.let { nameKey(it.searchQuery) },
        ).filter { it.length > 2 }.toSet()
        if (shown == null || name == null || keys.isEmpty()) return null
        val year = yearIn(shown) ?: about?.year?.takeIf { it > 0 } ?: 0
        val next = if (p.finished) nextOf(p, link, book, noted, shown.title, resume) else null
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
        )
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
                season = numbered?.season ?: info.season ?: 0,
                episode = numbered?.episode ?: info.episode ?: 0,
                episodeTitle = null,
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
