package app.fireplayer.live

import android.net.Uri
import app.fireplayer.player.Copies
import app.fireplayer.player.CopyHealth
import app.fireplayer.player.CopyRank
import app.fireplayer.player.MediaSpec
import app.fireplayer.player.NextPick
import app.fireplayer.player.XtreamAccount
import app.fireplayer.titles.TitleBook
import java.net.HttpURLConnection
import java.net.URL

/** Where [SmartPlayOrchestrator] hands a confirmed source; the VOD player will implement it (step 4). */
interface PlaybackSink {
    fun play(uri: Uri, startPositionMs: Long, headers: Map<String, String>, spec: MediaSpec)
}

/** What to play: a film, or one episode of a show, by the source the user picked. */
sealed class Selection(open val seedUri: Uri, open val startPositionMs: Long) {
    class Movie(seedUri: Uri, val tmdbId: Int, startPositionMs: Long = 0) : Selection(seedUri, startPositionMs)
    class Episode(seedUri: Uri, val seriesId: String, val real: NextPick.Real, val tmdbId: Int, startPositionMs: Long = 0) : Selection(seedUri, startPositionMs)
}

/**
 * Smart Play: verify the title, discover its copies across playlists, rank them, health-check the
 * top few, and hand one confirmed source to a [PlaybackSink]. Reuses the trunk [Copies], [CopyRank],
 * [CopyHealth] and [XtreamAccount]; probes are serialized (single-flight) and the provider's
 * connection limit is respected before launching.
 */
class SmartPlayOrchestrator(
    private val book: TitleBook,
    private val headers: Map<String, String>,
    private val hdrScreen: Boolean = false,
    private val health: (Uri) -> CopyHealth? = { null },
    measured: (Uri) -> MediaSpec? = { null },
) {

    private val copies = Copies(book, headers, measured)
    private val account = XtreamAccount(headers)
    private val probeLock = Any()

    /** Resolves the best confirmed source and hands it to [sink]; false when nothing playable is found. */
    fun play(selection: Selection, sink: PlaybackSink): Boolean {
        val tmdb = verify(selection)
        val list = discover(selection, tmdb)
        if (list.isEmpty()) return false
        val chosen = select(list, order(list)) ?: return false
        sink.play(chosen.uri, selection.startPositionMs, headers, chosen.spec ?: MediaSpec())
        return true
    }

    /**
     * The TMDB id the discovery matches on. A film's identity is its name/year as TMDB resolved it;
     * a show's is its series, season and episode ([Selection.Episode.real]). [Copies] does the deeper
     * name/year and season/number matching against the title book, so this only carries the fallback.
     */
    private fun verify(selection: Selection): Int = when (selection) {
        is Selection.Movie -> selection.tmdbId
        is Selection.Episode -> selection.tmdbId
    }

    private fun discover(selection: Selection, tmdb: Int): List<Copies.Copy> = when (selection) {
        is Selection.Movie -> copies.ofFilm(selection.seedUri, tmdb)
        is Selection.Episode -> copies.ofEpisode(selection.seedUri, selection.seriesId, tmdb)
    }

    /** Best-first ordering: [CopyRank]'s verdict, then the quality group and data rate of the rest. */
    private fun order(list: List<Copies.Copy>): List<Int> {
        val specs = list.map { it.spec ?: MediaSpec() }
        val healths = list.map { health(it.uri) }
        val groups = list.map { Copies.groupRank(it.group) }
        val current = list.indexOfFirst { it.current }.coerceAtLeast(0)
        val best = CopyRank.rank(specs, current, hdrScreen, healths, emptyList(), { null }, emptyList(), groups).best
        val rest = (0 until list.size)
            .filter { it != best }
            .sortedWith(compareByDescending<Int> { groups.getOrElse(it) { 0 } }.thenByDescending { specs[it].overallBps })
        return listOfNotNull(best) + rest
    }

    /** Health-check the top candidates in order, then fall back; one probe runs at a time. */
    private fun select(list: List<Copies.Copy>, order: List<Int>): Copies.Copy? = synchronized(probeLock) {
        for (i in order.take(PROBE_COUNT)) {
            val copy = list[i]
            if (health(copy.uri)?.troubled == true) continue
            if (!launchable(copy.uri)) continue
            if (alive(copy.uri)) return@synchronized copy
        }
        // Beyond the probed few, fall back to the best-ranked copy that isn't known-troubled.
        order.firstOrNull { i ->
            val copy = list[i]
            health(copy.uri)?.troubled != true && launchable(copy.uri)
        }?.let { list[it] }
    }

    /** The provider isn't already at its connection limit. */
    private fun launchable(uri: Uri): Boolean = account.screens(uri)?.full != true

    /** A short range fetch: the source answers, so it isn't dead. Non-http links count as alive. */
    private fun alive(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return true
        return runCatching {
            val conn = URL(uri.toString()).openConnection() as HttpURLConnection
            conn.connectTimeout = PROBE_TIMEOUT_MS
            conn.readTimeout = PROBE_TIMEOUT_MS
            conn.setRequestProperty("Range", "bytes=0-1023")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            try {
                val code = conn.responseCode
                code in 200..299 || code == 206
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    private companion object {
        const val PROBE_COUNT = 3
        const val PROBE_TIMEOUT_MS = 5_000
    }
}
