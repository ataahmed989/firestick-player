package app.fireplayer.history

/**
 * What's been watched, for Glass Home's Continue Watching row (see [ContinueProvider]): one entry per film, and one per
 * show at its latest episode, with what it takes to play it again from there. Kept only in FirePlayer's own storage
 * ([encode]); the rows handed out ([rows]) never carry the link. Not thread-safe: [ContinueWatching] holds the lock.
 *
 * An entry shows once [MIN_PLAYED_MS] of it has really played, over any number of sittings. A finished film leaves
 * the row; a finished episode moves its show on to the next one when that's known, and otherwise leaves too. An entry
 * removed from the row is forgotten, so it comes back only after another [MIN_PLAYED_MS] of playing; so is one not
 * watched for a while ([MOVIE_KEPT_MS], [SHOW_KEPT_MS]: the row then no longer has it, see [expire]).
 */
class WatchHistory(records: List<Record> = emptyList()) {

    enum class Kind(val code: String) { MOVIE("movie"), EPISODE("episode") }

    /** How to open a video again just as the app that first opened it did. */
    data class Resume(
        val uri: String,
        val type: String? = null,
        val title: String? = null,
        /** Name, value, name, value…, as the opening app sent them. */
        val headers: List<String> = emptyList(),
        val subtitles: List<String> = emptyList(),
        val subtitleNames: List<String?> = emptyList(),
    )

    /** What one row of Continue Watching shows: no link, no login. */
    data class Row(
        val id: String,
        val kind: Kind,
        val title: String,
        val year: Int,
        val season: Int,
        val episode: Int,
        val episodeTitle: String?,
        val tmdbId: Int,
        val positionMs: Long,
        val durationMs: Long,
        val lastWatched: Long,
        /** The provider's address as FirePlayer's Source row shows it ([StreamLink.source]); null when it has none. */
        val source: String? = null,
        /** An episode whose show's episodes FirePlayer can list ([StreamLink.listsEpisodes]). */
        val hasEpisodes: Boolean = false,
    )

    data class Record(
        val id: String,
        val kind: Kind,
        /** What tells this film or show apart: the provider's id for it, its name. Any one in common is the same one. */
        val keys: Set<String>,
        /** Which video it's at: for a show, its episode. */
        val videoKey: String,
        val title: String,
        val year: Int,
        val season: Int,
        val episode: Int,
        val episodeTitle: String?,
        val tmdbId: Int,
        val positionMs: Long,
        val durationMs: Long,
        val lastWatched: Long,
        /** Real playing time, all sittings together. */
        val playedMs: Long,
        val resume: Resume,
    ) {
        val shown: Boolean get() = playedMs >= MIN_PLAYED_MS

        fun row(): Row {
            val link = StreamLink.parse(resume.uri)
            return Row(
                id, kind, title, year, season, episode, episodeTitle, tmdbId, positionMs, durationMs, lastWatched,
                source = link?.source,
                hasEpisodes = kind == Kind.EPISODE && link?.listsEpisodes == true,
            )
        }
    }

    /** The episode after a finished one. */
    data class Next(
        val videoKey: String,
        val season: Int,
        val episode: Int,
        val episodeTitle: String?,
        val resume: Resume,
    )

    /**
     * What FirePlayer's own TMDB match says a film or episode is (the show's guide, or a Fix match): the TMDB id (for an
     * episode, its show's), the name without the year, the year, and for an episode TMDB's numbers and English name
     * (0 / null when the episode itself isn't matched). It wins over names and numbers read from the link.
     */
    data class Match(
        val tmdbId: Int,
        val title: String,
        val year: Int = 0,
        val season: Int = 0,
        val episode: Int = 0,
        val episodeTitle: String? = null,
    )

    /** What the player saw of one video: where it is, how long it played since it last said, and whether it finished. */
    data class Watch(
        val kind: Kind,
        val keys: Set<String>,
        val videoKey: String,
        val title: String,
        val year: Int = 0,
        val season: Int = 0,
        val episode: Int = 0,
        val episodeTitle: String? = null,
        val tmdbId: Int = 0,
        val positionMs: Long,
        val durationMs: Long,
        val playedMs: Long,
        val finished: Boolean = false,
        /** For an episode: the one after it, when known. */
        val next: Next? = null,
        val resume: Resume,
    )

    // Newest first.
    private val records = records.sortedByDescending { it.lastWatched }.toMutableList()

    val all: List<Record> get() = records.toList()

    /** The rows, newest first. */
    fun rows(): List<Row> = records.filter { it.shown }.map { it.row() }

    fun record(id: String): Record? = records.firstOrNull { it.id == id }

    /** Notes what was watched; true when the rows changed. [newId] names an entry seen for the first time. */
    fun watched(w: Watch, now: Long, newId: () -> String): Boolean {
        val before = rows()
        // One left long ago starts again: only another five minutes of it bring it back.
        expire(now)
        if (w.keys.isEmpty()) return rows() != before
        // Shows the provider tells apart by their ids stay apart, even with the same name ("Shogun" of 1980 and 2024)
        val showIds = w.keys.filter { it.startsWith(SHOW_KEY) }.toSet()
        val same = records.filter { r ->
            r.kind == w.kind && r.keys.any { k -> k in w.keys } &&
                (showIds.isEmpty() || r.keys.none { it.startsWith(SHOW_KEY) && it !in showIds })
        }
        // Nothing of it yet: only playing starts an entry, and a film played to its end has none to start.
        if (same.isEmpty() && (w.playedMs <= 0 || (w.finished && w.next == null))) return rows() != before
        // Another episode only opened, not played (or one that wouldn't play): the show stays where it was watched.
        if (w.playedMs <= 0 && !w.finished && same.none { it.videoKey == w.videoKey }) return rows() != before
        records.removeAll(same)
        // The same show found once by its id and once by its name: from now on one entry.
        val base = same.firstOrNull()
        val played = same.sumOf { it.playedMs } + w.playedMs.coerceAtLeast(0)
        val keys = same.flatMapTo(LinkedHashSet()) { it.keys } + w.keys
        val record = Record(
            id = base?.id ?: newId(),
            kind = w.kind,
            keys = keys,
            videoKey = w.videoKey,
            title = w.title.ifBlank { base?.title.orEmpty() },
            year = w.year.takeIf { it > 0 } ?: base?.year ?: 0,
            season = w.season,
            episode = w.episode,
            episodeTitle = w.episodeTitle,
            tmdbId = w.tmdbId.takeIf { it > 0 } ?: base?.tmdbId ?: 0,
            positionMs = w.positionMs.coerceAtLeast(0),
            durationMs = w.durationMs.coerceAtLeast(0),
            lastWatched = now,
            playedMs = played,
            resume = w.resume,
        )
        val next = w.next
        when {
            !w.finished -> records.add(0, record)
            // A finished film, or the last episode known of a show, leaves the row.
            w.kind == Kind.MOVIE || next == null -> Unit
            // Otherwise the show moves on to its next episode, from the start.
            else -> records.add(
                0,
                record.copy(
                    videoKey = next.videoKey,
                    season = next.season,
                    episode = next.episode,
                    episodeTitle = next.episodeTitle,
                    positionMs = 0,
                    durationMs = 0,
                    resume = next.resume,
                ),
            )
        }
        trim()
        return rows() != before
    }

    /**
     * A match put right by hand (Fix match): the entries of this [kind] with any of [keys], or at [videoKey], take its
     * TMDB id, name and year at once, whatever they had; the numbers too for the one at that very video. True when a
     * row changed.
     */
    fun rematch(kind: Kind, keys: Set<String>, videoKey: String, m: Match): Boolean {
        if (m.tmdbId <= 0) return false
        var changed = false
        for (i in records.indices) {
            val r = records[i]
            if (r.kind != kind || (r.videoKey != videoKey && r.keys.none { it in keys })) continue
            val here = r.videoKey == videoKey && m.season > 0
            val fixed = r.copy(
                tmdbId = m.tmdbId,
                title = m.title.ifBlank { r.title },
                year = m.year.takeIf { it > 0 } ?: r.year,
                season = if (here) m.season else r.season,
                episode = if (here) m.episode else r.episode,
                episodeTitle = if (here) m.episodeTitle else r.episodeTitle,
            )
            if (fixed != r) {
                records[i] = fixed
                if (r.shown) changed = true
            }
        }
        return changed
    }

    /** Removed from the row: forgotten, so only playing it again for a while brings it back. True when it was there. */
    fun remove(id: String): Boolean {
        val record = record(id) ?: return false
        records.remove(record)
        return record.shown
    }

    /**
     * Gives an entry the TMDB id found for it after it was noted (see [ContinueWatching]); the rest of it, and its place
     * in the row, stay as they were. True when the entry is there and had another id.
     */
    fun setTmdbId(id: String, tmdbId: Int): Boolean {
        val i = records.indexOfFirst { it.id == id }
        if (i < 0 || records[i].tmdbId == tmdbId) return false
        records[i] = records[i].copy(tmdbId = tmdbId)
        return true
    }

    /**
     * Films not watched for [MOVIE_KEPT_MS] and shows not watched for [SHOW_KEPT_MS] are forgotten, as if removed: the
     * row no longer has them, and only another [MIN_PLAYED_MS] of playing brings them back. Entries still counting up
     * to it go the same way, so minutes from long ago don't add up with new ones. True when the rows changed.
     */
    fun expire(now: Long): Boolean {
        val old = records.filter { now - it.lastWatched > if (it.kind == Kind.MOVIE) MOVIE_KEPT_MS else SHOW_KEPT_MS }
        if (old.isEmpty()) return false
        records.removeAll(old)
        return old.any { it.shown }
    }

    /** At most [MAX_SHOWN] films and shows each in the row, and a few more counting up to it; the oldest go. */
    private fun trim() {
        val movies = records.filter { it.shown && it.kind == Kind.MOVIE }
        val shows = records.filter { it.shown && it.kind == Kind.EPISODE }
        val waiting = records.filter { !it.shown }
        records.removeAll(movies.drop(MAX_SHOWN) + shows.drop(MAX_SHOWN) + waiting.drop(MAX_WAITING))
    }

    // ---------------------------------------------------------------------
    // Kept as text: a line per entry, its fields between tabs.

    fun encode(): String = buildString {
        append(FORMAT).append('\n')
        for (r in records) {
            val fields = listOf(
                esc(r.id), r.kind.code, list(r.keys.toList()), esc(r.videoKey), esc(r.title), r.year.toString(),
                r.season.toString(), r.episode.toString(), esc(r.episodeTitle), r.tmdbId.toString(), r.positionMs.toString(),
                r.durationMs.toString(), r.lastWatched.toString(), r.playedMs.toString(), esc(r.resume.uri), esc(r.resume.type),
                esc(r.resume.title), list(r.resume.headers), list(r.resume.subtitles), list(r.resume.subtitleNames),
            )
            append(fields.joinToString("\t")).append('\n')
        }
    }

    companion object {
        /** This watch as FirePlayer's own match says it is (see [Match]); as it was without one. */
        fun Watch.matched(m: Match?): Watch {
            if (m == null || m.tmdbId <= 0) return this
            val numbered = kind == Kind.EPISODE && m.season > 0
            return copy(
                title = m.title.ifBlank { title },
                year = m.year.takeIf { it > 0 } ?: year,
                tmdbId = m.tmdbId,
                season = if (numbered) m.season else season,
                episode = if (numbered) m.episode else episode,
                episodeTitle = if (numbered) m.episodeTitle ?: episodeTitle else episodeTitle,
            )
        }

        /** Real playing time before a title shows. */
        /** How a show's provider id begins among its keys (see ContinueWatching). */
        const val SHOW_KEY = "s|"
        const val MIN_PLAYED_MS = 5 * 60_000L
        const val MAX_SHOWN = 20
        /** How long a film, and a show, stays in the row without being watched. */
        const val MOVIE_KEPT_MS = 30 * 24 * 3600_000L
        const val SHOW_KEPT_MS = 60 * 24 * 3600_000L
        private const val MAX_WAITING = 60
        private const val FORMAT = "continue 1"
        private const val NULL = "\\0"
        private const val ITEM = '\u001f'

        /** What [encode] wrote; lines it can't read are left out. */
        fun decode(text: String?): WatchHistory {
            val lines = text?.split('\n').orEmpty()
            if (lines.firstOrNull() != FORMAT) return WatchHistory()
            val records = lines.drop(1).mapNotNull { line ->
                val f = line.split('\t')
                if (f.size < 20) return@mapNotNull null
                runCatching {
                    Record(
                        id = unesc(f[0])!!,
                        kind = Kind.entries.first { it.code == f[1] },
                        keys = unlist(f[2]).filterNotNull().toSet(),
                        videoKey = unesc(f[3])!!,
                        title = unesc(f[4]).orEmpty(),
                        year = f[5].toInt(),
                        season = f[6].toInt(),
                        episode = f[7].toInt(),
                        episodeTitle = unesc(f[8]),
                        tmdbId = f[9].toInt(),
                        positionMs = f[10].toLong(),
                        durationMs = f[11].toLong(),
                        lastWatched = f[12].toLong(),
                        playedMs = f[13].toLong(),
                        resume = Resume(
                            uri = unesc(f[14])!!,
                            type = unesc(f[15]),
                            title = unesc(f[16]),
                            headers = unlist(f[17]).map { it.orEmpty() },
                            subtitles = unlist(f[18]).map { it.orEmpty() },
                            subtitleNames = unlist(f[19]),
                        ),
                    )
                }.getOrNull()
            }
            return WatchHistory(records)
        }

        private fun esc(s: String?): String {
            if (s == null) return NULL
            val out = StringBuilder(s.length)
            for (c in s) when (c) {
                '\\' -> out.append("\\\\")
                '\t' -> out.append("\\t")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                ITEM -> out.append("\\u")
                else -> out.append(c)
            }
            return out.toString()
        }

        private fun unesc(s: String): String? {
            if (s == NULL) return null
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i++]
                if (c != '\\' || i >= s.length) {
                    out.append(c)
                    continue
                }
                when (val e = s[i++]) {
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    'u' -> out.append(ITEM)
                    else -> out.append(e)
                }
            }
            return out.toString()
        }

        // An empty list is written as nothing; a list of one empty item as the separator alone would be lost, so
        // every item is followed by it.
        private fun list(items: List<String?>): String = items.joinToString("") { esc(it) + ITEM }

        private fun unlist(s: String): List<String?> = if (s.isEmpty()) emptyList() else s.removeSuffix(ITEM.toString()).split(ITEM).map { unesc(it) }
    }
}
